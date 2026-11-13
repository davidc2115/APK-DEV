package fr.dd.jarvis.societe.core

import java.time.LocalDate
import java.time.YearMonth

/**
 * Résultat de la société, recalculé automatiquement à chaque facture encaissée :
 * chiffre d'affaires encaissé (HT) − salaires versés (coût employeur : brut + charges patronales) − charges fixes
 * = ce qu'il reste à la société (résultat avant impôt) → impôt sur les sociétés → bénéfice net → réserve légale
 * → bénéfice distribuable → dividende de fin d'année (brut, puis net après prélèvement forfaitaire unique).
 *
 * Règles françaises 2026 par défaut (modifiables dans les infos société) : IS 15 % jusqu'à 42 500 € de bénéfice puis 25 %
 * (taux réduit PME), réserve légale 5 % du bénéfice net tant qu'elle n'atteint pas 10 % du capital, PFU 31,4 %
 * (12,8 % d'impôt + 18,6 % de prélèvements sociaux). Estimation de gestion : ne remplace pas l'expert-comptable.
 */
object Resultat {

    data class Salaires(val cout: Double, val net: Double, val estime: Double, val parEmploye: List<Pair<String, Double>>, val moisEstimes: Int)

    data class Bilan(
        val annee: Int,
        val arrete: LocalDate,
        val mois: Int,                 // mois de l'année pris en compte (janvier → mois d'arrêté)
        val nbFactures: Int,
        val caHt: Double,
        val tva: Double,               // TVA encaissée, à reverser (n'appartient pas à la société)
        val salaires: Salaires,
        val charges: Double,
        val avantImpot: Double,        // ce qu'il reste à la société avant impôt
        val soumisIs: Boolean,
        val impot: Double,
        val net: Double,               // bénéfice net (après IS)
        val reserveLegale: Double,
        val distribuable: Double,
        val dividendeBrut: Double,
        val dividendeNet: Double,      // après PFU
        val gardeSociete: Double,      // bénéfice laissé dans la société (réserves facultatives / report à nouveau)
        val dejaMisDeCote: Double,     // réserves déjà mises de côté facture par facture (reserve_pct)
        val notes: List<String>
    )

    data class PartFacture(
        val numero: String, val ht: Double, val tva: Double,
        val couvre: Double,            // part qui couvre les salaires et charges pas encore couverts
        val benefice: Double, val impot: Double, val reserveLegale: Double, val dividendeBrut: Double, val gardeSociete: Double
    )

    fun soumisIs(s: Societe): Boolean = s.soumisIs ?: !Regex("micro|auto.?entrepr|\\bei\\b|entreprise individuelle").containsMatchIn(normaliser(s.forme))

    fun impot(benefice: Double, s: Societe): Double {
        if (benefice <= 0.0) return 0.0
        val reduit = minOf(benefice, s.isPlafondReduit.coerceAtLeast(0.0))
        return round2(reduit * s.isTauxReduit / 100.0 + (benefice - reduit) * s.isTauxNormal / 100.0)
    }

    /** Réserve légale à doter sur un bénéfice net (5 %, plafonnée à 10 % du capital moins ce qui est déjà constitué). */
    fun reserveLegale(net: Double, s: Societe, dejaDotee: Double = 0.0): Double {
        if (net <= 0.0) return 0.0
        val cinq = net * 0.05
        if (s.capitalSocial <= 0.0) return round2(cinq)
        return round2(minOf(cinq, (s.capitalSocial * 0.10 - s.reserveLegaleCumulee - dejaDotee).coerceAtLeast(0.0)))
    }

    private fun factures(d: SocieteData, annee: Int, jusqua: LocalDate, sauf: String? = null) = d.docs.filter {
        it.type == "facture" && it.statut == "payee" && it.numero != sauf &&
            runCatching { LocalDate.parse(it.payeLe.ifBlank { it.date }) }.getOrNull()?.let { p -> p.year == annee && !p.isAfter(jusqua) } == true
    }

    /** Salaires de janvier au mois [jusqua] : bulletins enregistrés, sinon estimés depuis la fiche de l'employé. */
    fun salaires(d: SocieteData, annee: Int, jusqua: YearMonth): Salaires {
        var cout = 0.0; var net = 0.0; var estime = 0.0; var moisEstimes = 0
        val parEmploye = linkedMapOf<String, Double>()
        val debut = YearMonth.of(annee, 1)
        // Bulletins réels (même d'un employé retiré depuis).
        d.bulletins.filter { b -> runCatching { YearMonth.parse(b.mois) }.getOrNull()?.let { it.year == annee && !it.isAfter(jusqua) } == true }.forEach { b ->
            cout += b.coutEmployeur; net += b.netAPayer
            val nom = d.employes.firstOrNull { it.id == b.employeId }?.nom ?: "Ancien salarié"
            parEmploye[nom] = (parEmploye[nom] ?: 0.0) + b.coutEmployeur
        }
        // Mois sans bulletin : estimation d'après le salaire contractuel.
        d.employes.filter { it.brutMensuel > 0.0 }.forEach { e ->
            val entree = runCatching { YearMonth.from(LocalDate.parse(e.dateEntree)) }.getOrNull()
            var m = if (entree != null && entree.isAfter(debut)) entree else debut
            while (!m.isAfter(jusqua)) {
                if (d.bulletins.none { it.employeId == e.id && it.mois == m.toString() }) {
                    // Coût officiel URSSAF relevé chaque semaine pour ce salaire, sinon calcul local.
                    val officiel = d.baremes.couts[e.id]?.takeIf { kotlin.math.abs(it.brut - e.brutMensuel) < 0.01 }
                    val (c, n) = officiel?.let { it.cout to it.netAvantImpot } ?: Paie.bulletin(e, d.paie, m.toString()).let { it.coutEmployeur to it.netAPayer }
                    cout += c; net += n; estime += c; moisEstimes++
                    parEmploye[e.nom] = (parEmploye[e.nom] ?: 0.0) + c
                }
                m = m.plusMonths(1)
            }
        }
        return Salaires(round2(cout), round2(net), round2(estime), parEmploye.map { it.key to round2(it.value) }, moisEstimes)
    }

    fun bilan(d: SocieteData, annee: Int = LocalDate.now().year, arrete: LocalDate = LocalDate.now(), sauf: String? = null): Bilan {
        val s = d.societe
        val fin = if (arrete.year > annee) LocalDate.of(annee, 12, 31) else arrete
        val mois = fin.monthValue
        val fs = factures(d, annee, fin, sauf)
        val tot = fs.map { d.totaux(it) }
        val caHt = round2(tot.sumOf { it.ht })
        val tva = if (s.franchiseTva) 0.0 else round2(tot.sumOf { it.ttc - it.ht })
        val sal = salaires(d, annee, YearMonth.from(fin))
        val charges = round2(s.chargesMensuelles.coerceAtLeast(0.0) * mois)
        val avant = round2(caHt - sal.cout - charges)
        val is_ = soumisIs(s)
        val impot = if (is_) impot(avant, s) else 0.0
        val net = round2(avant - impot)
        val rl = if (is_) reserveLegale(net, s) else 0.0
        val distribuable = if (is_) round2((net - rl).coerceAtLeast(0.0)) else 0.0
        val divBrut = round2(distribuable * s.distributionPct.coerceIn(0.0, 100.0) / 100.0)
        val notes = mutableListOf<String>()
        if (sal.moisEstimes > 0) notes += "${sal.moisEstimes} mois de salaire sans bulletin enregistré : estimés d'après la fiche de chaque employé (${euro(sal.estime)})."
        if (s.chargesMensuelles <= 0.0) notes += "Aucune charge fixe renseignée (loyer, assurance, comptable, véhicule…) : dis « mes charges fixes sont de … € par mois » pour un résultat plus juste."
        if (is_ && s.capitalSocial <= 0.0) notes += "Capital social non renseigné : réserve légale estimée à 5 % sans plafond."
        if (!is_) notes += "Entreprise à l'impôt sur le revenu (${s.forme.ifBlank { "forme non précisée" }}) : pas d'impôt sur les sociétés ni de dividende ; le bénéfice est imposé à ton nom."
        if (is_ && Regex("sarl|eurl").containsMatchIn(normaliser(s.forme)))
            notes += "Gérant majoritaire de SARL/EURL : la part des dividendes au-delà de 10 % du capital (+ primes et compte courant) supporte des cotisations sociales TNS au lieu des 18,6 % de prélèvements sociaux."
        return Bilan(
            annee, fin, mois, fs.size, caHt, tva, sal, charges, avant, is_, impot, net, rl, distribuable, divBrut,
            round2(divBrut * (1 - s.pfuPct.coerceIn(0.0, 100.0) / 100.0)), round2(distribuable - divBrut),
            round2(d.provisions.filter { it.date.startsWith(annee.toString()) }.sumOf { it.montant }), notes
        )
    }

    /** Projection au 31 décembre au rythme actuel (chiffre d'affaires moyen par mois, salaires et charges de l'année entière). */
    fun projection(d: SocieteData, annee: Int = LocalDate.now().year, arrete: LocalDate = LocalDate.now()): Bilan? {
        val b = bilan(d, annee, arrete)
        if (b.mois >= 12 || b.caHt <= 0.0) return null
        val s = d.societe
        val caHt = round2(b.caHt / b.mois * 12)
        val sal = salaires(d, annee, YearMonth.of(annee, 12))
        val charges = round2(s.chargesMensuelles.coerceAtLeast(0.0) * 12)
        val avant = round2(caHt - sal.cout - charges)
        val impot = if (b.soumisIs) impot(avant, s) else 0.0
        val net = round2(avant - impot)
        val rl = if (b.soumisIs) reserveLegale(net, s) else 0.0
        val distribuable = if (b.soumisIs) round2((net - rl).coerceAtLeast(0.0)) else 0.0
        val divBrut = round2(distribuable * s.distributionPct.coerceIn(0.0, 100.0) / 100.0)
        return b.copy(arrete = LocalDate.of(annee, 12, 31), mois = 12, caHt = caHt, tva = round2(b.tva / b.mois * 12), salaires = sal, charges = charges,
            avantImpot = avant, impot = impot, net = net, reserveLegale = rl, distribuable = distribuable, dividendeBrut = divBrut,
            dividendeNet = round2(divBrut * (1 - s.pfuPct.coerceIn(0.0, 100.0) / 100.0)), gardeSociete = round2(distribuable - divBrut), notes = emptyList())
    }

    /** Ce que devient une facture encaissée : d'abord les salaires et charges pas encore couverts, puis le bénéfice. */
    fun partFacture(d: SocieteData, facture: Doc): PartFacture {
        val s = d.societe
        val t = d.totaux(facture)
        val paye = runCatching { LocalDate.parse(facture.payeLe.ifBlank { facture.date }) }.getOrDefault(LocalDate.now())
        val avant = bilan(d, paye.year, paye, sauf = facture.numero)
        val couvre = round2(if (avant.avantImpot < 0) minOf(t.ht, -avant.avantImpot) else 0.0)
        val benefice = round2(t.ht - couvre)
        val apres = round2(avant.avantImpot + t.ht)
        val is_ = soumisIs(s)
        val impot = if (is_) round2(impot(apres, s) - impot(avant.avantImpot, s)) else 0.0
        val net = round2(benefice - impot)
        val rl = if (is_) round2(reserveLegale(apres - impot(apres, s), s) - reserveLegale(avant.net, s)).coerceIn(0.0, net.coerceAtLeast(0.0)) else 0.0
        val distribuable = if (is_) (net - rl).coerceAtLeast(0.0) else 0.0
        val div = round2(distribuable * s.distributionPct.coerceIn(0.0, 100.0) / 100.0)
        return PartFacture(facture.numero, t.ht, if (s.franchiseTva) 0.0 else round2(t.ttc - t.ht), couvre, benefice, impot, rl, div, round2(distribuable - div))
    }

    fun texteFacture(p: PartFacture, s: Societe): String = buildString {
        append("💶 **Répartition de ${p.numero}** (${euro(p.ht)} HT)\n")
        if (p.tva > 0) append("- TVA encaissée à reverser : ${euro(p.tva)}\n")
        if (p.couvre > 0) append("- Couvre les salaires et charges de l'année : ${euro(p.couvre)}\n")
        append("- Bénéfice apporté : **${euro(p.benefice)}**\n")
        if (soumisIs(s) && p.benefice > 0) {
            append("- Impôt sur les sociétés à prévoir : ${euro(p.impot)}\n")
            if (p.reserveLegale > 0) append("- Réserve légale : ${euro(p.reserveLegale)}\n")
            append("- Dividende de fin d'année possible : **${euro(p.dividendeBrut)}** brut")
            if (p.gardeSociete > 0) append(" · gardé dans la société : ${euro(p.gardeSociete)}")
            append('\n')
        }
    }

    fun texte(b: Bilan, projection: Bilan?, s: Societe): String = buildString {
        append("# 📊 Résultat ${b.annee} au ${b.arrete.dayOfMonth}/${"%02d".format(b.arrete.monthValue)}\n\n")
        append("- Factures encaissées : ${b.nbFactures} → **${euro(b.caHt)} HT**")
        if (b.tva > 0) append(" (+ ${euro(b.tva)} de TVA à reverser, qui n'est pas à la société)")
        append('\n')
        append("- Salaires versés (coût employeur, janvier → ${b.mois}/${b.annee}) : − ${euro(b.salaires.cout)}")
        if (b.salaires.net > 0) append(" (dont ${euro(b.salaires.net)} nets versés)")
        append('\n')
        b.salaires.parEmploye.forEach { (nom, c) -> append("  - $nom : ${euro(c)}\n") }
        if (b.charges > 0) append("- Charges fixes (${euro(s.chargesMensuelles)} × ${b.mois} mois) : − ${euro(b.charges)}\n")
        append("- **Reste à la société avant impôt : ${euro(b.avantImpot)}**\n")
        if (b.soumisIs) {
            append("- Impôt sur les sociétés (${nombre(s.isTauxReduit)} % jusqu'à ${euro(s.isPlafondReduit)}, ${nombre(s.isTauxNormal)} % au-delà) : − ${euro(b.impot)}\n")
            append("- **Bénéfice net : ${euro(b.net)}**\n")
            if (b.reserveLegale > 0) append("- Réserve légale (5 %) : − ${euro(b.reserveLegale)}\n")
            append("- Bénéfice distribuable : ${euro(b.distribuable)}\n")
            append("- **Dividende de fin d'année possible (${nombre(s.distributionPct)} % du distribuable) : ${euro(b.dividendeBrut)} brut → ${euro(b.dividendeNet)} net** après prélèvement forfaitaire de ${nombre(s.pfuPct)} %\n")
            if (b.gardeSociete > 0) append("- Gardé dans la société : ${euro(b.gardeSociete)}\n")
        }
        if (b.dejaMisDeCote > 0) append("- Déjà mis de côté facture par facture (réserve ${nombre(s.reservePct)} %) : ${euro(b.dejaMisDeCote)}\n")
        projection?.let { p ->
            append("\n## 🔭 Projection au 31/12 (au rythme actuel)\n")
            append("- Chiffre d'affaires : ${euro(p.caHt)} HT · salaires : − ${euro(p.salaires.cout)}" + (if (p.charges > 0) " · charges : − ${euro(p.charges)}" else "") + "\n")
            append("- Reste avant impôt : ${euro(p.avantImpot)}")
            if (p.soumisIs) append(" · bénéfice net : ${euro(p.net)} · **dividende possible : ${euro(p.dividendeBrut)} brut (${euro(p.dividendeNet)} net)**")
            append('\n')
        }
        if (b.notes.isNotEmpty()) { append('\n'); b.notes.forEach { append("ℹ️ $it\n") } }
        append("\n_Estimation de gestion (règles 2026) : le dividende se vote en assemblée après l'arrêté des comptes ; à valider avec l'expert-comptable._")
    }
}
