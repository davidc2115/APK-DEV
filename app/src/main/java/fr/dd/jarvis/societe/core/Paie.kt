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
    val heuresContrat: Double,          // heures mensuelles contractuelles (base 35h/39h/40h… mensualisée)
    val heuresEffectuees: Double,       // heures réellement retenues pour ce bulletin (= heuresContrat si pas de saisie particulière)
    val heuresSup25: Double,            // heures supplémentaires majorées à 25 %
    val heuresSup50: Double,            // heures supplémentaires majorées à 50 %
    val tauxHoraire: Double,            // taux horaire brut (déduit du salaire contractuel)
    val brutHeuresNormales: Double,
    val brutHeuresSup: Double,
    val reductionHeuresSup: Double,     // réduction de cotisations salariales sur heures sup (loi TEPA), déjà comptée dans les lignes
    val primes: Double,
    val brut: Double,
    val lignes: List<LigneCotisation>,
    val totalCotisationsSalariales: Double,
    val netAvantImpot: Double,
    val tauxPas: Double,
    val pas: Double,
    val netAPayer: Double,
    val totalChargesPatronales: Double,
    val coutEmployeur: Double,
    val source: String = ""             // vide : calcul local ; sinon calcul officiel URSSAF (avec sa date)
) {
    // Compatibilité avec l'ancien modèle.
    val heuresMensuelles get() = heuresContrat
    val brutBase get() = round2(brutHeuresNormales + brutHeuresSup)
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
            "Heures supplémentaires majorées 25 %/50 % avec réduction forfaitaire de cotisations salariales (loi TEPA) ; " +
            "l'exonération d'impôt sur le revenu des heures sup (plafond annuel 7 500 €) n'est pas modélisée (le PAS indiqué est donc une estimation haute en cas d'heures sup). " +
            "Ne remplace ni un logiciel de paie ni un expert-comptable — à faire valider avant tout usage officiel. " +
            "Un dirigeant travailleur non salarié (gérant majoritaire d'EURL/SARL) relève d'un régime différent (SSI), non couvert par ce calcul."

    /** Heures mensuelles « mensualisées » pour une base hebdomadaire donnée (35 h → 151,67 h/mois, 39 h → 169 h, 40 h → 173,33 h…). */
    fun heuresMensuelles(heuresHebdo: Double): Double = round2(if (heuresHebdo > 0.0) heuresHebdo * 52.0 / 12.0 else 151.67)

    /** Détail des cotisations pour un brut donné. */
    fun cotisations(brut: Double, dirigeant: Boolean, cfg: ConfigPaie): List<LigneCotisation> {
        val plafond = if (cfg.plafondSecu > 0.0) cfg.plafondSecu else 4005.0
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

    /** Seuil mensualisé des 8 premières heures sup/semaine (majorées à 25 %, loi, au-delà 50 %). */
    private const val SEUIL_SUP_25 = 8.0 * 52.0 / 12.0 // ≈ 34,67 h/mois

    /** Taux horaire brut contractuel (déduit du salaire — brut ou net visé — pour les heures contractuelles). */
    fun tauxHoraire(e: Employe, cfg: ConfigPaie): Double {
        val dirigeant = e.statut == "dirigeant"
        val brutContractuel = if (e.netMensuelVise > 0.0) brutDepuisNet(e.netMensuelVise, dirigeant, cfg) else round2(e.brutMensuel)
        val heures = heuresMensuelles(e.heuresHebdo)
        return if (heures > 0.0) round2(brutContractuel / heures) else 0.0
    }

    /** Bulletin pour un nombre d'heures effectuées donné (contractuelles par défaut) : les heures au-delà de la base
     *  contractuelle sont majorées (25 % puis 50 % au-delà de 8 h/semaine) et bénéficient de la réduction de
     *  cotisations salariales sur les heures supplémentaires (loi TEPA, taux forfaitaire de 11,31 %). */
    fun bulletinPourHeures(e: Employe, cfg: ConfigPaie, mois: String, primes: Double, heuresEffectuees: Double): Bulletin {
        val dirigeant = e.statut == "dirigeant"
        val heuresContrat = heuresMensuelles(e.heuresHebdo)
        val taux = tauxHoraire(e, cfg)
        val sup = (heuresEffectuees - heuresContrat).coerceAtLeast(0.0)
        val sup25 = minOf(sup, SEUIL_SUP_25)
        val sup50 = maxOf(0.0, sup - SEUIL_SUP_25)
        val heuresNormales = minOf(heuresEffectuees, heuresContrat)
        val brutNormal = round2(taux * heuresNormales)
        val brutSup = round2(taux * 1.25 * sup25 + taux * 1.5 * sup50)
        val brut = round2(brutNormal + brutSup + primes)
        val lignes = cotisations(brut, dirigeant, cfg).toMutableList()
        val remunerationSup = brutSup
        val reduction = if (remunerationSup > 0.0) round2(remunerationSup * 0.1131) else 0.0
        if (reduction > 0.0) lignes += LigneCotisation("Réduction de cotisations salariales — heures supplémentaires (loi TEPA)", round2(remunerationSup), 0.0, -reduction, 0.0, 0.0)
        val totalSal = round2(lignes.sumOf { it.montantSalarial })
        val totalPat = round2(lignes.sumOf { it.montantPatronal })
        val netAvant = round2(brut - totalSal)
        val pas = round2(netAvant * e.tauxPas / 100.0)
        return Bulletin(
            employe = e, mois = mois, heuresContrat = heuresContrat, heuresEffectuees = round2(heuresEffectuees),
            heuresSup25 = round2(sup25), heuresSup50 = round2(sup50), tauxHoraire = taux,
            brutHeuresNormales = brutNormal, brutHeuresSup = brutSup, reductionHeuresSup = reduction,
            primes = round2(primes), brut = brut,
            lignes = lignes, totalCotisationsSalariales = totalSal, netAvantImpot = netAvant,
            tauxPas = e.tauxPas, pas = pas, netAPayer = round2(netAvant - pas),
            totalChargesPatronales = totalPat, coutEmployeur = round2(brut + totalPat)
        )
    }

    /** Nombre d'heures qu'il a fallu effectuer pour obtenir le NET RÉELLEMENT VERSÉ (net à payer, après PAS) donné,
     *  par recherche dichotomique (recherche inverse de bulletinPourHeures). */
    fun heuresDepuisNetVerse(netVerse: Double, e: Employe, cfg: ConfigPaie, mois: String, primes: Double): Double {
        val heuresContrat = heuresMensuelles(e.heuresHebdo)
        if (netVerse <= 0.0) return 0.0
        var lo = 0.0
        var hi = heuresContrat * 3.0 + 100.0
        repeat(50) {
            val mid = (lo + hi) / 2.0
            if (bulletinPourHeures(e, cfg, mois, primes, mid).netAPayer < netVerse) lo = mid else hi = mid
        }
        return round2(hi)
    }

    /**
     * Bulletin du mois. Par défaut, utilise les heures contractuelles (comportement d'avant). Si [heuresEffectuees]
     * est fourni, les heures sup/manquantes et leur majoration sont calculées automatiquement. Si [netVerse] est
     * fourni à la place (montant réellement viré au salarié ce mois-ci), le nombre d'heures effectuées est déduit
     * automatiquement par recherche inverse.
     */
    fun bulletin(e: Employe, cfg: ConfigPaie, mois: String, primes: Double = 0.0, heuresEffectuees: Double? = null, netVerse: Double? = null): Bulletin {
        val heures = when {
            netVerse != null && netVerse > 0.0 -> heuresDepuisNetVerse(netVerse, e, cfg, mois, primes)
            heuresEffectuees != null -> heuresEffectuees
            else -> heuresMensuelles(e.heuresHebdo)
        }
        return bulletinPourHeures(e, cfg, mois, primes, heures)
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
