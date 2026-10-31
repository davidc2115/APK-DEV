package fr.dd.jarvis.societe.core

/** Une ligne de cotisation du bulletin : base, taux et montant côté salarié et côté employeur. */
data class LigneCotisation(
    val libelle: String,
    val base: Double,
    val tauxSalarial: Double,
    val montantSalarial: Double,
    val tauxPatronal: Double,
    val montantPatronal: Double
)

data class Bulletin(
    val employe: Employe,
    val mois: String,
    val heuresMensuelles: Double,
    val tauxHoraire: Double,
    val brutBase: Double,
    val primes: Double,
    val brut: Double,
    val lignes: List<LigneCotisation>,
    val totalCotisationsSalariales: Double,
    val netAvantImpot: Double,
    val tauxPas: Double,
    val pas: Double,
    val netAPayer: Double,
    val totalChargesPatronales: Double,
    val coutEmployeur: Double
) {
    // Compatibilité avec l'ancien modèle (taux globaux équivalents, utilisés nulle part en interne désormais).
    val cotisationsSalariales get() = totalCotisationsSalariales
    val chargesPatronales get() = totalChargesPatronales
}

/**
 * Calcul de bulletin DÉTAILLÉ par organisme/cotisation, à partir de taux standards (régime général, France,
 * ordre de grandeur 2026 — maladie, vieillesse plafonnée/déplafonnée, allocations familiales, AT/MP, chômage,
 * AGS, retraite complémentaire AGIRC-ARRCO, CSG/CRDS, formation professionnelle, taxe d'apprentissage).
 * Un dirigeant assimilé salarié (président de SASU…) ne cotise ni au chômage ni à l'AGS.
 * ⚠️ Taux indicatifs et simplifiés : ne remplace ni un logiciel de paie ni un expert-comptable — les taux réels
 * dépendent de la convention collective, de l'activité (AT/MP), du statut exact du dirigeant (assimilé salarié ou
 * travailleur non salarié) et sont révisés chaque année.
 */
object Paie {

    const val AVERTISSEMENT =
        "Bulletin détaillé à titre d'estimation (taux standards du régime général, ordre de grandeur 2026, à ajuster si besoin dans Réglages › Paie). " +
            "Ne remplace ni un logiciel de paie ni un expert-comptable — à faire valider avant tout usage officiel. " +
            "Un dirigeant travailleur non salarié (gérant majoritaire d'EURL/SARL) relève d'un régime différent (SSI), non couvert par ce calcul."

    /** Heures mensuelles « mensualisées » pour une base hebdomadaire donnée (35 h → 151,67 h/mois, 39 h → 169 h, 40 h → 173,33 h…). */
    fun heuresMensuelles(heuresHebdo: Double): Double = round2(if (heuresHebdo > 0.0) heuresHebdo * 52.0 / 12.0 else 151.67)

    /** Détail des cotisations pour un brut donné. */
    fun cotisations(brut: Double, dirigeant: Boolean, cfg: ConfigPaie): List<LigneCotisation> {
        val plafond = if (cfg.plafondSecu > 0.0) cfg.plafondSecu else 3925.0
        val baseT1 = minOf(brut, plafond)
        val baseCsg = round2(brut * 0.9825) // abattement forfaitaire de 1,75 % pour frais professionnels

        fun ligne(libelle: String, base: Double, ts: Double, tp: Double) =
            LigneCotisation(libelle, round2(base), ts, round2(base * ts / 100.0), tp, round2(base * tp / 100.0))

        val l = mutableListOf(
            ligne("Sécurité sociale — maladie, maternité, invalidité, décès", brut, 0.0, 7.00),
            ligne("Assurance vieillesse plafonnée", baseT1, 6.90, 8.55),
            ligne("Assurance vieillesse déplafonnée", brut, 0.40, 1.90),
            ligne("Allocations familiales", brut, 0.0, 3.45),
            ligne("Accident du travail / maladies professionnelles", brut, 0.0, cfg.tauxAtmp)
        )
        if (!dirigeant) {
            l += ligne("Assurance chômage", brut, 0.0, 4.05)
            l += ligne("AGS — garantie des salaires", brut, 0.0, 0.15)
        }
        l += ligne("Retraite complémentaire AGIRC-ARRCO — tranche 1", baseT1, 3.15, 4.72)
        l += ligne("CEG — contribution d'équilibre général T1", baseT1, 0.86, 1.29)
        if (cfg.cadre) {
            l += ligne("APEC", baseT1, 0.024, 0.036)
            l += ligne("CET — contribution d'équilibre technique", brut, 0.14, 0.21)
        }
        l += ligne("CSG déductible", baseCsg, 6.80, 0.0)
        l += ligne("CSG/CRDS non déductible", baseCsg, 2.90, 0.0)
        l += ligne("Formation professionnelle continue", brut, 0.0, cfg.tauxFormationPct)
        l += ligne("Taxe d'apprentissage", brut, 0.0, cfg.tauxTaxeApprentissagePct)
        return l
    }

    /** Net avant impôt pour un brut donné (brut − total des cotisations salariales). */
    fun netAvantImpotPour(brut: Double, dirigeant: Boolean, cfg: ConfigPaie): Double =
        round2(brut - cotisations(brut, dirigeant, cfg).sumOf { it.montantSalarial })

    /** Brut nécessaire pour obtenir le net avant impôt visé (recherche dichotomique : le détail des cotisations
     *  n'est pas une fonction linéaire simple à cause du plafond de la Sécurité sociale). */
    fun brutDepuisNet(netVise: Double, dirigeant: Boolean, cfg: ConfigPaie): Double {
        if (netVise <= 0.0) return 0.0
        var lo = 0.0
        var hi = netVise * 2.5 + 2000.0
        repeat(60) {
            val mid = (lo + hi) / 2.0
            if (netAvantImpotPour(mid, dirigeant, cfg) < netVise) lo = mid else hi = mid
        }
        return round2(hi)
    }

    fun bulletin(e: Employe, cfg: ConfigPaie, mois: String, primes: Double = 0.0): Bulletin {
        val dirigeant = e.statut == "dirigeant"
        val brutBase = if (e.netMensuelVise > 0.0) brutDepuisNet(e.netMensuelVise, dirigeant, cfg) else round2(e.brutMensuel)
        val brut = round2(brutBase + primes)
        val lignes = cotisations(brut, dirigeant, cfg)
        val totalSal = round2(lignes.sumOf { it.montantSalarial })
        val totalPat = round2(lignes.sumOf { it.montantPatronal })
        val netAvant = round2(brut - totalSal)
        val pas = round2(netAvant * e.tauxPas / 100.0)
        val heures = heuresMensuelles(e.heuresHebdo)
        return Bulletin(
            employe = e, mois = mois, heuresMensuelles = heures,
            tauxHoraire = if (heures > 0.0) round2(brutBase / heures) else 0.0,
            brutBase = brutBase, primes = round2(primes), brut = brut,
            lignes = lignes, totalCotisationsSalariales = totalSal, netAvantImpot = netAvant,
            tauxPas = e.tauxPas, pas = pas, netAPayer = round2(netAvant - pas),
            totalChargesPatronales = totalPat, coutEmployeur = round2(brut + totalPat)
        )
    }
}

data class BilanReserve(
    val annee: Int,
    val total: Double,
    val nbFactures: Int,
    val dirigeant: Double,
    val salaries: List<Pair<Employe, Double>>,
    val nonAffecte: Double
)

object Reserve {
    /** Part d'une facture encaissée mise de côté (sur le montant HT). */
    fun provision(facture: Doc, totaux: Totaux, pct: Double, date: String, id: String): Provision? {
        if (pct <= 0.0) return null
        return Provision(
            id = id, date = date, factureNumero = facture.numero,
            baseHt = totaux.ht, pct = pct, montant = round2(totaux.ht * pct / 100.0)
        )
    }

    /**
     * Répartition indicative de la réserve d'une année : part dirigeant (dividende) et part salariés
     * (participation), au prorata du brut versé dans l'année, à défaut à parts égales.
     */
    fun bilan(
        annee: Int,
        provisions: List<Provision>,
        employes: List<Employe>,
        bulletins: List<BulletinRef>,
        partDirigeantPct: Double
    ): BilanReserve {
        val delAnnee = provisions.filter { it.date.startsWith(annee.toString()) }
        val total = round2(delAnnee.sumOf { it.montant })
        val dirigeant = round2(total * partDirigeantPct / 100.0)
        val partSalaries = round2(total - dirigeant)
        val salaries = employes.filter { it.statut != "dirigeant" }
        if (salaries.isEmpty() || partSalaries <= 0.0) {
            return BilanReserve(annee, total, delAnnee.size, dirigeant, emptyList(), partSalaries)
        }
        val brutParEmploye = salaries.associateWith { e ->
            bulletins.filter { it.employeId == e.id && it.mois.startsWith(annee.toString()) }.sumOf { it.brut }
        }
        val somme = brutParEmploye.values.sum()
        val parts = salaries.map { e ->
            val montant = if (somme > 0.0) round2(partSalaries * (brutParEmploye[e] ?: 0.0) / somme)
            else round2(partSalaries / salaries.size)
            e to montant
        }
        val reste = round2(partSalaries - parts.sumOf { it.second })
        return BilanReserve(annee, total, delAnnee.size, dirigeant, parts, reste)
    }
}
