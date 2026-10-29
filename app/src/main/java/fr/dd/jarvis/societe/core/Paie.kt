package fr.dd.jarvis.societe.core

data class Bulletin(
    val employe: Employe,
    val mois: String,
    val brutBase: Double,
    val primes: Double,
    val brut: Double,
    val tauxSalarial: Double,
    val cotisationsSalariales: Double,
    val netAvantImpot: Double,
    val tauxPas: Double,
    val pas: Double,
    val netAPayer: Double,
    val tauxPatronal: Double,
    val chargesPatronales: Double,
    val coutEmployeur: Double
)

/**
 * Bulletin SIMPLIFIÉ : taux globaux configurables, sans détail par organisme.
 * Il ne remplace ni un logiciel de paie ni un expert-comptable.
 */
object Paie {
    fun bulletin(e: Employe, cfg: ConfigPaie, mois: String, primes: Double = 0.0): Bulletin {
        val dirigeant = e.statut == "dirigeant"
        val ts = if (dirigeant) cfg.tauxSalarialDirigeant else cfg.tauxSalarial
        val tp = if (dirigeant) cfg.tauxPatronalDirigeant else cfg.tauxPatronal
        val brut = round2(e.brutMensuel + primes)
        val cotis = round2(brut * ts / 100.0)
        val netAvant = round2(brut - cotis)
        val pas = round2(netAvant * e.tauxPas / 100.0)
        val charges = round2(brut * tp / 100.0)
        return Bulletin(
            employe = e, mois = mois, brutBase = round2(e.brutMensuel), primes = round2(primes), brut = brut,
            tauxSalarial = ts, cotisationsSalariales = cotis, netAvantImpot = netAvant,
            tauxPas = e.tauxPas, pas = pas, netAPayer = round2(netAvant - pas),
            tauxPatronal = tp, chargesPatronales = charges, coutEmployeur = round2(brut + charges)
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
