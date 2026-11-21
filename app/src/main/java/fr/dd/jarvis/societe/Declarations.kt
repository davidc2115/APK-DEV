package fr.dd.jarvis.societe

import fr.dd.jarvis.societe.core.Doc
import fr.dd.jarvis.societe.core.Societe
import fr.dd.jarvis.societe.core.SocieteData
import fr.dd.jarvis.societe.core.normaliser
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * Ce que la société doit DÉCLARER, quand, où et comment — selon son statut juridique (micro-entreprise, EI au réel,
 * EURL, SASU, SAS, SARL) — avec les montants calculés depuis les factures encaissées, les notes de frais et les
 * bulletins de paie enregistrés dans JARVIS.
 *
 * Valeurs 2026 vérifiées sur les sources officielles (novembre 2026) :
 * - micro : cotisations 12,3 % (ventes), 21,2 % (prestations BIC), 25,6 % (BNC SSI), 23,2 % (BNC CIPAV) — service-public
 *   F37353 ; CFP 0,1 % commerçant / 0,3 % artisan / 0,2 % libéral ; taxe CMA 0,48 % (prestations) / 0,22 % (ventes) ;
 *   versement libératoire 1 % / 1,7 % / 2,2 % ; déclaration mensuelle (défaut) avant la fin du mois suivant, ou
 *   trimestrielle (30 avril, 31 juillet, 31 octobre, 31 janvier) ; ACRE 50 % (25 % pour une création à partir du
 *   1er juillet 2026, demande dans les 60 jours — décret 2026-69) ; plafonds 2026-2028 : 83 600 € / 203 100 € ;
 *   franchise de TVA : 37 500 € (41 250 € majoré) prestations, 85 000 € (93 500 €) ventes ; 2042-C-PRO : 5KO / 5KP /
 *   5HQ (5TA / 5TB / 5TE avec versement libératoire).
 * - sociétés à l'IS : IS 15 % jusqu'à 42 500 € puis 25 % ; acomptes 15 mars, 15 juin, 15 septembre, 15 décembre ;
 *   solde (2572) le 15 mai ; liasse 2065 + 2033 mi-mai ; TVA : CA3 mensuelle (réel normal) ou, en 2026, régime
 *   simplifié (acomptes juillet 55 % / décembre 40 %, CA12 début mai) — régime simplifié supprimé au 1er janvier 2027
 *   (CA3 trimestrielle si moins de 4 000 € de TVA par an, sinon mensuelle) ; PFU 31,4 % ; CFE le 15 décembre.
 * - facturation électronique : réception obligatoire au 1er septembre 2026, émission au 1er septembre 2027 (PME, micro).
 */
object Declarations {
    enum class Statut(val libelle: String, val societeIs: Boolean) {
        MICRO("Micro-entreprise", false), EI("Entreprise individuelle (réel)", false), EURL("EURL", true),
        SASU("SASU", true), SAS("SAS", true), SARL("SARL", true)
    }

    fun statut(s: Societe): Statut {
        val f = normaliser(s.forme)
        return when {
            Regex("\\bsasu\\b").containsMatchIn(f) -> Statut.SASU
            Regex("\\bsas\\b").containsMatchIn(f) -> Statut.SAS
            Regex("\\beurl\\b").containsMatchIn(f) -> Statut.EURL
            Regex("\\bsarl\\b").containsMatchIn(f) -> Statut.SARL
            Regex("\\b(ei|entreprise individuelle)\\b").containsMatchIn(f) && !Regex("micro").containsMatchIn(f) -> Statut.EI
            else -> Statut.MICRO
        }
    }

    // ------------------------------------------------------------------ barèmes micro 2026

    data class Micro(val cle: String, val libelle: String, val cotisations: Double, val cfp: Double, val chambre: Double, val vl: Double,
                     val case2042: String, val caseVl: String, val abattement: Int, val plafond: Double, val seuilTva: Double, val seuilTvaMajore: Double,
                     val rubriqueUrssaf: String)

    val ACTIVITES = linkedMapOf(
        "services_artisan" to Micro("services_artisan", "Prestations de services artisanales (BIC)", 21.2, 0.3, 0.48, 1.7, "5KP", "5TB", 50, 83_600.0, 37_500.0, 41_250.0,
            "Prestations de services commerciales ou artisanales (BIC)"),
        "services_commercial" to Micro("services_commercial", "Prestations de services commerciales (BIC)", 21.2, 0.1, 0.044, 1.7, "5KP", "5TB", 50, 83_600.0, 37_500.0, 41_250.0,
            "Prestations de services commerciales ou artisanales (BIC)"),
        "ventes" to Micro("ventes", "Ventes de marchandises (BIC)", 12.3, 0.1, 0.015, 1.0, "5KO", "5TA", 71, 203_100.0, 85_000.0, 93_500.0,
            "Ventes de marchandises, d'objets, de fournitures, de denrées"),
        "bnc" to Micro("bnc", "Profession libérale (BNC, SSI)", 25.6, 0.2, 0.0, 2.2, "5HQ", "5TE", 34, 83_600.0, 37_500.0, 41_250.0, "Prestations de services (BNC)"),
        "bnc_cipav" to Micro("bnc_cipav", "Profession libérale réglementée (BNC, CIPAV)", 23.2, 0.2, 0.0, 2.2, "5HQ", "5TE", 34, 83_600.0, 37_500.0, 41_250.0, "Prestations de services (BNC – CIPAV)"),
    )

    fun micro(s: Societe) = ACTIVITES[s.activite] ?: ACTIVITES.getValue("services_artisan")

    /** ACRE : réduction des cotisations jusqu'à la fin du 3e trimestre civil qui suit le début d'activité. */
    fun facteurAcre(s: Societe, periodeFin: LocalDate): Double {
        if (!s.acre) return 1.0
        val debut = runCatching { LocalDate.parse(s.dateCreation) }.getOrNull() ?: return 1.0
        val trimestreDebut = (debut.monthValue - 1) / 3
        val finAcre = LocalDate.of(debut.year, 1, 1).plusMonths(((trimestreDebut + 4) * 3).toLong()).minusDays(1)
        if (periodeFin.isAfter(finAcre)) return 1.0
        return if (debut.isBefore(LocalDate.of(2026, 7, 1))) 0.5 else 0.75
    }

    // ------------------------------------------------------------------ montants depuis les données

    private fun payeLe(d: Doc) = runCatching { LocalDate.parse(d.payeLe.take(10)) }.getOrNull()

    /** Factures ENCAISSÉES entre deux dates (incluses). */
    fun encaissees(data: SocieteData, debut: LocalDate, fin: LocalDate) = data.docs.filter { it.type == "facture" && it.statut == "payee" }
        .filter { d -> payeLe(d)?.let { !it.isBefore(debut) && !it.isAfter(fin) } == true }

    data class Tva(val baseParTaux: Map<Double, Double>, val collectee: Double, val deductible: Double) { val nette get() = collectee - deductible }

    fun tva(data: SocieteData, debut: LocalDate, fin: LocalDate): Tva {
        val bases = mutableMapOf<Double, Double>(); var coll = 0.0
        for (d in encaissees(data, debut, fin)) for ((taux, base, taxe) in data.totaux(d).tvaParTaux) {
            if (taux <= 0) continue
            bases[taux] = (bases[taux] ?: 0.0) + base; coll += taxe
        }
        val ded = data.frais.filter { f -> runCatching { LocalDate.parse(f.date) }.getOrNull()?.let { !it.isBefore(debut) && !it.isAfter(fin) } == true }.sumOf { it.tva }
        return Tva(bases.mapValues { r2(it.value) }, r2(coll), r2(ded))
    }

    fun caHt(data: SocieteData, debut: LocalDate, fin: LocalDate) = r2(encaissees(data, debut, fin).sumOf { data.totaux(it).ht })

    private fun r2(x: Double) = Math.round(x * 100) / 100.0
    fun euros(x: Double) = String.format(java.util.Locale.FRANCE, "%,.2f €", x).replace(' ', ' ').replace(' ', ' ')
    private fun e0(x: Double) = String.format(java.util.Locale.FRANCE, "%,.0f €", x).replace(' ', ' ').replace(' ', ' ')

    private val MOIS = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")
    fun jour(d: LocalDate) = "${if (d.dayOfMonth == 1) "1er" else d.dayOfMonth} ${MOIS[d.monthValue - 1]} ${d.year}"
    private fun mois(m: YearMonth) = "${MOIS[m.monthValue - 1]} ${m.year}"

    /** Jour ouvré (samedi, dimanche → lundi). */
    private fun ouvre(d: LocalDate) = when (d.dayOfWeek) { DayOfWeek.SATURDAY -> d.plusDays(2); DayOfWeek.SUNDAY -> d.plusDays(1); else -> d }

    // ------------------------------------------------------------------ échéances

    data class Echeance(
        val id: String, val date: LocalDate, val titre: String, val organisme: String, val lien: String,
        val montant: String = "", val etapes: List<String> = emptyList(), val note: String = "",
    )

    private const val URSSAF_AE = "https://www.autoentrepreneur.urssaf.fr"
    private const val IMPOTS_PRO = "https://www.impots.gouv.fr/professionnel"
    private const val IMPOTS_PART = "https://www.impots.gouv.fr/accueil"
    private const val NET_ENTREPRISES = "https://www.net-entreprises.fr"
    private const val GREFFE = "https://formalites.entreprises.gouv.fr"

    /** Échéances des 12 prochains mois (et celles en retard de moins de 2 mois), avec montants et marche à suivre. */
    fun echeances(data: SocieteData, aujourdhui: LocalDate = LocalDate.now()): List<Echeance> {
        val s = data.societe
        val st = statut(s)
        val out = mutableListOf<Echeance>()
        if (st == Statut.MICRO) micro(data, aujourdhui, out) else reel(data, st, aujourdhui, out)
        // Communes à tous
        cfe(data, aujourdhui, out)
        if (aujourdhui.isBefore(LocalDate.of(2027, 9, 1))) out += Echeance("efacture-2027", LocalDate.of(2027, 9, 1), "Facturation électronique : émission obligatoire",
            "Réforme de la facturation électronique", "https://www.impots.gouv.fr/facturation-electronique-et-plateformes-agreees",
            etapes = listOf("Depuis le 1er septembre 2026, toute entreprise doit pouvoir RECEVOIR des factures électroniques : choisis une plateforme agréée (PA).",
                "À partir du 1er septembre 2027, les micro-entreprises et PME doivent ÉMETTRE leurs factures entre professionnels au format électronique via cette plateforme.",
                "Les factures aux particuliers relèvent de l'e-reporting (transmission des données à l'administration)."))
        return out.filter { !it.date.isBefore(aujourdhui.minusMonths(2)) && !it.date.isAfter(aujourdhui.plusMonths(12)) }.sortedBy { it.date }
    }

    private fun micro(data: SocieteData, aujourdhui: LocalDate, out: MutableList<Echeance>) {
        val s = data.societe
        val a = micro(s)
        val tauxTotal = { fin: LocalDate -> a.cotisations * facteurAcre(s, fin) + a.cfp + (if (s.activite == "services_artisan" || s.activite == "ventes") a.chambre else 0.0) + (if (s.versementLiberatoire) a.vl else 0.0) }
        fun urssaf(debut: LocalDate, fin: LocalDate, echeance: LocalDate, periode: String, id: String) {
            val ca = caHt(data, debut, fin)
            val t = tauxTotal(fin)
            val cot = r2(ca * t / 100)
            val detail = "cotisations ${fr(a.cotisations * facteurAcre(s, fin))} %${if (facteurAcre(s, fin) < 1) " (ACRE)" else ""} + CFP ${fr(a.cfp)} %" +
                (if (a.chambre > 0 && (s.activite == "services_artisan" || s.activite == "ventes")) " + taxe chambre ${fr(a.chambre)} %" else "") +
                (if (s.versementLiberatoire) " + impôt (versement libératoire) ${fr(a.vl)} %" else "")
            out += Echeance(id, echeance, "Déclaration du chiffre d'affaires — $periode", "URSSAF (micro-entrepreneur)", URSSAF_AE,
                "Chiffre d'affaires encaissé : ${euros(ca)} → à payer ≈ ${euros(cot)}",
                listOf("Ouvre $URSSAF_AE (ou l'application « AutoEntrepreneur Urssaf ») et connecte-toi.",
                    "Va dans « Déclarer et payer », période : $periode.",
                    "Dans la ligne « ${a.rubriqueUrssaf} », saisis ${euros(ca)} : c'est le chiffre d'affaires ENCAISSÉ sur la période" + (if (!s.franchiseTva) ", hors TVA" else "") + " (factures payées entre le ${jour(debut)} et le ${jour(fin)}).",
                    if (ca == 0.0) "Aucun encaissement : déclare quand même 0 € (sinon pénalité de retard)." else "Vérifie le montant calculé : ≈ ${euros(cot)} ($detail).",
                    "Valide avant le ${jour(echeance)} ; le paiement est prélevé à l'échéance (ou paie en ligne).",
                    "Garde le récapitulatif : JARVIS le marque « fait » quand tu le coches ici."),
                note = "Montant calculé d'après les factures marquées payées dans JARVIS — vérifie qu'aucun encaissement ne manque.")
        }
        // Périodes : les 2 dernières échues + la suivante.
        if (s.periodicite == "trimestrielle") {
            val trimestre = { y: Int, q: Int -> LocalDate.of(y, q * 3 + 1, 1) to LocalDate.of(y, q * 3 + 1, 1).plusMonths(3).minusDays(1) }
            var y = aujourdhui.year; var q = (aujourdhui.monthValue - 1) / 3 - 2
            repeat(3) {
                var yy = y; var qq = q
                while (qq < 0) { qq += 4; yy-- }
                val (deb, fin) = trimestre(yy, qq)
                urssaf(deb, fin, fin.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth()), "${qq + 1}e trimestre $yy", "urssaf-$yy-T${qq + 1}")
                q++
            }
        } else {
            for (k in 2 downTo 0) {
                val m = YearMonth.from(aujourdhui).minusMonths(k.toLong())
                urssaf(m.atDay(1), m.atEndOfMonth(), m.plusMonths(1).atEndOfMonth(), mois(m), "urssaf-$m")
            }
        }
        // Impôt sur le revenu (mai-juin) : chiffre d'affaires de l'année précédente.
        val anneeIr = if (aujourdhui.monthValue >= 7) aujourdhui.year + 1 else aujourdhui.year
        val caAn = caHt(data, LocalDate.of(anneeIr - 1, 1, 1), LocalDate.of(anneeIr - 1, 12, 31))
        out += Echeance("ir-${anneeIr}", LocalDate.of(anneeIr, 5, 31), "Déclaration de revenus ${anneeIr} (revenus ${anneeIr - 1})", "Impôts (particulier)", IMPOTS_PART,
            "Chiffre d'affaires ${anneeIr - 1} à reporter : ${euros(caAn)}",
            listOf("Sur impots.gouv.fr (ton espace PARTICULIER), ouvre la déclaration de revenus et ajoute l'annexe 2042-C-PRO (revenus des professions non salariées).",
                if (s.versementLiberatoire) "Tu as opté pour le versement libératoire : reporte ${euros(caAn)} en case ${a.caseVl} (l'impôt est déjà payé ; ce montant sert au revenu fiscal de référence)."
                else "Reporte ${euros(caAn)} (chiffre d'affaires brut, sans rien déduire) en case ${a.case2042} : l'abattement forfaitaire de ${a.abattement} % est appliqué automatiquement.",
                "Date limite : fin mai / début juin selon ton département (affichée sur impots.gouv.fr)."))
        // Seuils de l'année (franchise de TVA, plafond micro).
        val caYtd = caHt(data, LocalDate.of(aujourdhui.year, 1, 1), aujourdhui)
        val alertes = mutableListOf<String>()
        if (s.franchiseTva) {
            when {
                caYtd > a.seuilTvaMajore -> alertes += "⚠️ ${euros(caYtd)} encaissés en ${aujourdhui.year} : seuil majoré de franchise de TVA (${e0(a.seuilTvaMajore)}) dépassé — tu dois facturer la TVA dès la facture qui l'a fait dépasser : demande un numéro de TVA intracommunautaire et retire la mention « TVA non applicable, art. 293 B du CGI »."
                caYtd > a.seuilTva -> alertes += "⚠️ ${euros(caYtd)} encaissés en ${aujourdhui.year} : seuil de franchise de TVA (${e0(a.seuilTva)}) dépassé — TVA à facturer à partir du 1er janvier prochain (ou dès que tu dépasses ${e0(a.seuilTvaMajore)})."
                caYtd > a.seuilTva * 0.8 -> alertes += "ℹ️ ${euros(caYtd)} encaissés en ${aujourdhui.year} : plus de 80 % du seuil de franchise de TVA (${e0(a.seuilTva)})."
            }
        }
        if (caYtd > a.plafond * 0.8) alertes += "${if (caYtd > a.plafond) "⚠️" else "ℹ️"} Plafond du régime micro : ${euros(caYtd)} sur ${e0(a.plafond)} cette année (au-delà deux années de suite : passage au régime réel)."
        if (alertes.isNotEmpty()) out += Echeance("seuils-${aujourdhui.year}", aujourdhui, "Seuils de l'année ${aujourdhui.year}", "Contrôle JARVIS", "https://entreprendre.service-public.gouv.fr/vosdroits/F21746", etapes = alertes)
        if (s.acre && s.dateCreation.isNotBlank()) runCatching {
            val debut = LocalDate.parse(s.dateCreation)
            val limite = debut.plusDays(60)
            if (!aujourdhui.isAfter(limite)) out += Echeance("acre-demande", limite, "Demande d'ACRE (60 jours après le début d'activité)", "URSSAF", URSSAF_AE,
                etapes = listOf("Fais la demande d'ACRE depuis ton espace $URSSAF_AE (messagerie / formulaire de demande d'ACRE), au plus tard le ${jour(limite)}.",
                    "Réduction : ${if (debut.isBefore(LocalDate.of(2026, 7, 1))) "50 %" else "25 %"} des cotisations jusqu'à la fin du 3e trimestre qui suit le début d'activité."))
        }
    }

    private fun reel(data: SocieteData, st: Statut, aujourdhui: LocalDate, out: MutableList<Echeance>) {
        val s = data.societe
        val regime = s.regimeTva.ifBlank { if (s.franchiseTva) "franchise" else "reel_simplifie" }
        // TVA
        if (regime == "reel_normal" || (regime == "reel_simplifie" && aujourdhui.year >= 2027)) {
            for (k in 2 downTo 0) {
                val m = YearMonth.from(aujourdhui).minusMonths(k.toLong())
                val t = tva(data, m.atDay(1), m.atEndOfMonth())
                val ca = caHt(data, m.atDay(1), m.atEndOfMonth())
                val lignes = t.baseParTaux.entries.sortedByDescending { it.key }.map { (taux, base) ->
                    val ligne = when (taux) { 20.0 -> "08"; 10.0 -> "9B"; 5.5 -> "09"; 2.1 -> "10"; else -> "(taux ${fr(taux)} %)" }
                    "Ligne $ligne (taux ${fr(taux)} %) : base ${euros(base)}, taxe ${euros(r2(base * taux / 100))}."
                }
                out += Echeance("tva-$m", ouvre(m.plusMonths(1).atDay(15)), "Déclaration de TVA (CA3) — ${mois(m)}", "Impôts (espace professionnel)", IMPOTS_PRO,
                    "TVA collectée ${euros(t.collectee)} − déductible ${euros(t.deductible)} = ${if (t.nette >= 0) "à payer ${euros(t.nette)}" else "crédit ${euros(-t.nette)}"}",
                    listOf("impots.gouv.fr › Espace professionnel › Déclarer › TVA › déclaration CA3 de ${mois(m)} (date limite entre le 15 et le 24 du mois suivant, affichée dans ton espace).",
                        "Cadre A, ligne A1 (ventes, prestations de services) : ${euros(ca)} HT encaissés." ) + lignes +
                        listOf("Ligne 20 (TVA déductible sur autres biens et services) : ${euros(t.deductible)} (TVA des notes de frais du mois, avec justificatif).",
                            if (t.nette >= 0) "Ligne 28 (TVA nette due) : ${euros(t.nette)} ; télérègle le total (ligne 32) depuis le compte de la société."
                            else "Ligne 25 (crédit de TVA) : ${euros(-t.nette)}, à reporter le mois suivant (ou à demander en remboursement).",
                            "Vérifie les numéros de ligne sur le formulaire 3310-CA3 de l'année, et qu'aucune facture encaissée ni dépense ne manque."),
                    note = if (regime == "reel_simplifie") "Régime simplifié supprimé au 1er janvier 2027 : CA3 mensuelle (ou trimestrielle si moins de 4 000 € de TVA par an, sur option)." else "")
            }
        } else if (regime == "reel_simplifie") {
            val an = aujourdhui.year
            val tAn = tva(data, LocalDate.of(an - 1, 1, 1), LocalDate.of(an - 1, 12, 31))
            out += Echeance("tva-acompte-juillet-$an", LocalDate.of(an, 7, 24), "Acompte de TVA de juillet (régime simplifié)", "Impôts (espace professionnel)", IMPOTS_PRO,
                "55 % de la TVA due au titre de ${an - 1} ≈ ${euros(r2(tAn.nette.coerceAtLeast(0.0) * 0.55))}",
                listOf("impots.gouv.fr › Espace professionnel › Déclarer › TVA › acompte (2581) de juillet.", "Pas d'acompte si la TVA due l'an dernier était inférieure à 1 000 €."))
            out += Echeance("tva-acompte-decembre-$an", LocalDate.of(an, 12, 24), "Acompte de TVA de décembre (régime simplifié)", "Impôts (espace professionnel)", IMPOTS_PRO,
                "40 % de la TVA due au titre de ${an - 1} ≈ ${euros(r2(tAn.nette.coerceAtLeast(0.0) * 0.40))}",
                listOf("impots.gouv.fr › Espace professionnel › Déclarer › TVA › acompte de décembre.", "Le régime simplifié disparaît au 1er janvier 2027 : passage à la CA3."))
            val tCour = tva(data, LocalDate.of(an, 1, 1), LocalDate.of(an, 12, 31))
            out += Echeance("tva-ca12-${an + 1}", ouvre(LocalDate.of(an + 1, 5, 4)), "Déclaration annuelle de TVA CA12 (exercice $an)", "Impôts (espace professionnel)", IMPOTS_PRO,
                "TVA collectée ${euros(tCour.collectee)} − déductible ${euros(tCour.deductible)} (à ce jour)",
                listOf("impots.gouv.fr › Espace professionnel › Déclarer › TVA › CA12 (formulaire 3517-S), au plus tard le 2e jour ouvré après le 1er mai.",
                    "Les acomptes versés en juillet et décembre se déduisent du solde."))
        }
        // IS
        if (st.societeIs) {
            val b = runCatching { fr.dd.jarvis.societe.core.Resultat.bilan(data, aujourdhui.year, aujourdhui) }.getOrNull()
            val an = aujourdhui.year
            listOf(3, 6, 9, 12).forEach { m -> out += Echeance("is-acompte-$an-$m", LocalDate.of(an, m, 15), "Acompte d'impôt sur les sociétés (${MOIS[m - 1]})", "Impôts (espace professionnel)", IMPOTS_PRO,
                etapes = listOf("Relevé d'acompte 2571 sur impots.gouv.fr › Espace professionnel › Déclarer › IS.",
                    "Dû seulement si l'IS de l'exercice précédent dépassait 3 000 € ; aucun acompte pour le premier exercice.")) }
            out += Echeance("is-solde-${an + 1}", LocalDate.of(an + 1, 5, 15), "Solde de l'impôt sur les sociétés (exercice $an)", "Impôts (espace professionnel)", IMPOTS_PRO,
                b?.let { "IS estimé à ce jour : ${euros(it.impot)} (15 % jusqu'à 42 500 € de bénéfice, puis 25 %)" }.orEmpty(),
                listOf("Relevé de solde 2572 sur impots.gouv.fr › Espace professionnel, au plus tard le 15 mai.", "Les acomptes versés en cours d'année se déduisent du solde."))
            out += Echeance("liasse-${an + 1}", ouvre(LocalDate.of(an + 1, 5, 4)).plusDays(15), "Liasse fiscale (2065 + 2033) — exercice $an", "Impôts (espace professionnel)", IMPOTS_PRO,
                b?.let { "CA HT ${euros(it.caHt)} · charges ${euros(it.charges)} · salaires ${euros(it.salaires.cout)} · résultat avant IS ${euros(it.avantImpot)}" }.orEmpty(),
                listOf("Déclaration de résultats 2065 et annexes 2033 (régime simplifié) en mode EDI (expert-comptable ou logiciel) ou EFI sur l'espace professionnel.",
                    "Date limite : 2e jour ouvré après le 1er mai + 15 jours en ligne (vers le 20 mai)."))
            out += Echeance("ag-${an + 1}", LocalDate.of(an + 1, 6, 30), "Approbation des comptes $an", if (st == Statut.SASU || st == Statut.EURL) "Associé unique" else "Assemblée des associés", GREFFE,
                etapes = listOf("Dans les 6 mois après la clôture : décision de l'associé unique / assemblée qui approuve les comptes et affecte le résultat (dividendes, réserves).",
                    "Puis dépôt des comptes au greffe dans le mois (2 mois en ligne) sur $GREFFE."))
            if (b != null && b.dividendeBrut > 0) out += Echeance("dividendes-$an", LocalDate.of(an + 1, 7, 15), "Si tu verses des dividendes", "Impôts", IMPOTS_PRO,
                "Dividende brut estimé ${euros(b.dividendeBrut)} → net ${euros(b.dividendeNet)} après PFU 31,4 %",
                listOf("Déclaration 2777 et paiement du prélèvement forfaitaire (PFU) avant le 15 du mois qui suit le versement.", "IFU 2561 avant le 15 février de l'année suivante."))
        } else {
            val an = aujourdhui.year
            out += Echeance("liasse-ei-${an + 1}", ouvre(LocalDate.of(an + 1, 5, 4)).plusDays(15), "Liasse fiscale BIC réel (2031 + 2033) — $an", "Impôts (espace professionnel)", IMPOTS_PRO,
                etapes = listOf("Déclaration 2031 et annexes 2033 sur l'espace professionnel (EDI / EFI), puis report du bénéfice sur ta 2042-C-PRO."))
        }
        // Rémunérations : DSN (bulletins enregistrés)
        val m = YearMonth.from(aujourdhui).minusMonths(1)
        val bul = data.bulletins.filter { it.mois == m.toString() }
        if (bul.isNotEmpty()) out += Echeance("dsn-$m", ouvre(m.plusMonths(1).atDay(15)), "DSN de ${mois(m)}", "net-entreprises.fr", NET_ENTREPRISES,
            "${bul.size} bulletin(s) : brut ${euros(bul.sumOf { it.brut })}, net à payer ${euros(bul.sumOf { it.netAPayer })}, coût employeur ${euros(bul.sumOf { it.coutEmployeur })}",
            listOf("Transmets la DSN mensuelle de ${mois(m)} (salaires, cotisations, prélèvement à la source) via net-entreprises.fr ou ton logiciel de paie : avant le 15 (le 5 à partir de 50 salariés).",
                "Les cotisations URSSAF et le prélèvement à la source sont prélevés d'après cette DSN."))
        else if (data.employes.isNotEmpty()) out += Echeance("dsn-$m", ouvre(m.plusMonths(1).atDay(15)), "Bulletins de ${mois(m)} à établir", "Paie", NET_ENTREPRISES,
            etapes = listOf("Aucun bulletin enregistré pour ${mois(m)} : établis-les (onglet Société › Bulletins) puis transmets la DSN avant le 15.",
                if (st == Statut.SASU || st == Statut.SAS) "Président non rémunéré : aucune DSN n'est due pour lui." else "Gérant majoritaire (TNS) : pas de bulletin, ses cotisations sont appelées par l'URSSAF."))
        if (st == Statut.EURL || st == Statut.SARL || st == Statut.EI) out += Echeance("tns-${aujourdhui.year + 1}", LocalDate.of(aujourdhui.year + 1, 5, 31), "Revenus du dirigeant non salarié (volet social)", "Impôts / URSSAF", IMPOTS_PART,
            etapes = listOf("Déclare ta rémunération de gérant (et dividendes soumis à cotisations) dans la déclaration de revenus, volet social de la 2042-C-PRO : elle sert au calcul définitif des cotisations URSSAF.",
                "Les cotisations provisionnelles sont prélevées chaque mois par l'URSSAF (espace urssaf.fr)."))
    }

    private fun cfe(data: SocieteData, aujourdhui: LocalDate, out: MutableList<Echeance>) {
        val s = data.societe
        val an = if (aujourdhui.monthValue == 12 && aujourdhui.dayOfMonth > 15) aujourdhui.year + 1 else aujourdhui.year
        val creation = runCatching { LocalDate.parse(s.dateCreation) }.getOrNull()
        val premiere = creation?.year == an
        val caAn = caHt(data, LocalDate.of(an - 2, 1, 1), LocalDate.of(an - 2, 12, 31))
        out += Echeance("cfe-$an", LocalDate.of(an, 12, 15), "Cotisation foncière des entreprises (CFE) $an", "Impôts (espace professionnel)", IMPOTS_PRO,
            if (premiere) "Exonérée l'année de création" else "",
            listOf("L'avis est mis en ligne en novembre dans ton espace professionnel impots.gouv.fr (pas d'envoi papier) : paie-le avant le 15 décembre (prélèvement ou paiement en ligne).",
                if (premiere) "Année de création : pas de CFE à payer cette année ; pense à la déclaration initiale 1447-C-SD si elle t'est demandée (avant le 31 décembre)."
                else if (statut(s) == Statut.MICRO && caAn in 0.01..5_000.0) "Chiffre d'affaires de ${an - 2} ≤ 5 000 € : exonération de la cotisation minimum."
                else "Montant selon la commune et la valeur locative (cotisation minimum si tu travailles depuis ton domicile)."))
    }

    private fun fr(x: Double) = if (x == Math.floor(x)) "%.0f".format(x) else "%.2f".format(java.util.Locale.FRANCE, x).trimEnd('0').trimEnd(',')

    // ------------------------------------------------------------------ pièces à traiter

    /** État des pièces : factures, notes de frais, paie — ce qu'il reste à traiter avant de déclarer. */
    fun pieces(data: SocieteData, aujourdhui: LocalDate = LocalDate.now()): List<String> {
        val out = mutableListOf<String>()
        val impayees = data.docs.filter { it.type == "facture" && it.statut == "a_payer" }
        if (impayees.isNotEmpty()) {
            val retard = impayees.filter { runCatching { LocalDate.parse(it.echeance) }.getOrNull()?.isBefore(aujourdhui) == true }
            out += "🧾 ${impayees.size} facture(s) à encaisser (${euros(impayees.sumOf { data.totaux(it).ttc })})" + (if (retard.isNotEmpty()) ", dont ${retard.size} en retard : relance-les." else ".") +
                " Une facture ne compte dans le chiffre d'affaires à déclarer que quand elle est PAYÉE : marque-la « payée » à l'encaissement."
        }
        val brouillons = data.docs.count { it.type == "facture" && it.statut == "brouillon" }
        if (brouillons > 0) out += "📝 $brouillons facture(s) en brouillon : à émettre."
        val fraisAT = data.frais.filter { it.statut == "a_traiter" }
        if (fraisAT.isNotEmpty()) out += "💶 ${fraisAT.size} note(s) de frais à traiter (${euros(fraisAT.sumOf { it.ttc })})" +
            (fraisAT.count { it.justificatif.isBlank() }.takeIf { it > 0 }?.let { ", dont $it sans justificatif (photographie le ticket : sans justificatif, pas de TVA récupérable ni de charge déductible)" } ?: "") + "."
        if (statut(data.societe) == Statut.MICRO && data.frais.isNotEmpty())
            out += "ℹ️ En micro-entreprise, les frais ne se déduisent pas (abattement forfaitaire) : les notes de frais servent au suivi et aux justificatifs, pas à la déclaration."
        val m = YearMonth.from(aujourdhui).minusMonths(1)
        val sansBulletin = data.employes.filter { e -> data.bulletins.none { it.employeId == e.id && it.mois == m.toString() } }
        if (sansBulletin.isNotEmpty()) out += "👷 Bulletin de ${mois(m)} manquant pour : ${sansBulletin.joinToString(", ") { it.nom }}."
        if (data.societe.siret.isBlank()) out += "🆔 SIRET non renseigné (onglet Création ou Identité) : obligatoire sur les devis et factures."
        return out
    }

    // ------------------------------------------------------------------ texte (voix / chat)

    fun texte(data: SocieteData, faites: Set<String> = emptySet(), aujourdhui: LocalDate = LocalDate.now()): String {
        val st = statut(data.societe)
        val l = echeances(data, aujourdhui).filter { it.id !in faites }
        val maintenant = l.filter { !it.date.isAfter(aujourdhui.plusDays(45)) }
        val suite = l.filter { it.date.isAfter(aujourdhui.plusDays(45)) }.take(6)
        return buildString {
            append("# 📋 Déclarations — ${st.libelle}${if (st == Statut.MICRO) " (${micro(data.societe).libelle})" else ""}\n")
            if (maintenant.isEmpty()) append("\nRien à déclarer dans les 45 prochains jours.\n")
            else {
                append("\n## À faire maintenant\n")
                maintenant.forEach { e ->
                    append("\n### ${if (e.date.isBefore(aujourdhui)) "⚠️ En retard — " else ""}${e.titre} — avant le ${jour(e.date)}\n")
                    append("*${e.organisme}* · [${e.lien.removePrefix("https://")}](${e.lien})\n")
                    if (e.montant.isNotBlank()) append("\n**${e.montant}**\n")
                    e.etapes.forEachIndexed { i, t -> append("${i + 1}. $t\n") }
                    if (e.note.isNotBlank()) append("_${e.note}_\n")
                }
            }
            if (suite.isNotEmpty()) append("\n## Ensuite\n" + suite.joinToString("\n") { "- ${jour(it.date)} — ${it.titre}${if (it.montant.isNotBlank()) " (${it.montant})" else ""}" } + "\n")
            val p = pieces(data, aujourdhui)
            if (p.isNotEmpty()) append("\n## Pièces à traiter\n" + p.joinToString("\n") { "- $it" } + "\n")
            append("\n_Montants calculés d'après les données saisies dans JARVIS (factures encaissées, notes de frais, bulletins). En cas de doute, ton expert-comptable ou le service des impôts des entreprises fait foi._")
        }
    }
}
