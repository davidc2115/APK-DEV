package fr.dd.jarvis.societe.core

import kotlinx.serialization.Serializable

/** Coût mensuel officiel d'un employé pour son salaire contractuel (calcul URSSAF mis en cache). */
@Serializable
data class CoutMensuel(val brut: Double, val cout: Double, val netAvantImpot: Double, val date: String)

/**
 * Barèmes officiels relevés automatiquement auprès de l'URSSAF (API publique de mon-entreprise.urssaf.fr, même moteur de
 * calcul que les simulateurs officiels, mis à jour par l'URSSAF à chaque changement de loi) : plafond de la Sécurité
 * sociale, SMIC, impôt sur les sociétés, prélèvement forfaitaire sur les dividendes, coût de chaque salarié.
 */
@Serializable
data class Baremes(
    val dateMaj: String = "",                 // date de la dernière vérification réussie (yyyy-MM-dd)
    val source: String = "",
    val pmss: Double = 0.0,                   // plafond mensuel de la Sécurité sociale
    val smicHoraire: Double = 0.0,
    val isTauxReduit: Double = 0.0,
    val isPlafondReduit: Double = 0.0,
    val isTauxNormal: Double = 0.0,
    val pfuPct: Double = 0.0,
    val prelevementsSociauxPct: Double = 0.0,
    val couts: Map<String, CoutMensuel> = emptyMap(), // par id d'employé
    val changements: List<String> = emptyList(),      // dernières valeurs qui ont changé
    val erreur: String = ""                            // dernière erreur de mise à jour (vide si OK)
) {
    fun valide() = pmss > 0 && isTauxNormal > 0 && pfuPct > 0
}

/** Calculs autour des barèmes (sans réseau, testables). */
object BaremesCalcul {
    const val SOURCE = "URSSAF — mon-entreprise.urssaf.fr (barèmes officiels)"

    /** Plafond du taux réduit d'IS déduit du montant d'IS pour un résultat R : R·tn − p·(tn − tr) = montant. */
    fun plafondIs(resultat: Double, montant: Double, tauxReduit: Double, tauxNormal: Double): Double {
        if (tauxNormal <= tauxReduit) return 0.0
        return round2((resultat * tauxNormal / 100.0 - montant) / ((tauxNormal - tauxReduit) / 100.0))
    }

    /** Lignes « avant → après » des valeurs qui ont changé (pour prévenir l'utilisateur). */
    fun changements(avant: Baremes, apres: Baremes): List<String> {
        if (!avant.valide()) return emptyList()
        val out = mutableListOf<String>()
        fun cmp(nom: String, a: Double, b: Double, f: (Double) -> String) { if (a > 0 && b > 0 && kotlin.math.abs(a - b) > 0.004) out += "$nom : ${f(a)} → ${f(b)}" }
        val pct = { x: Double -> nombre(x) + " %" }
        cmp("Plafond mensuel de la Sécurité sociale", avant.pmss, apres.pmss, ::euro)
        cmp("SMIC horaire brut", avant.smicHoraire, apres.smicHoraire, ::euro)
        cmp("IS taux réduit", avant.isTauxReduit, apres.isTauxReduit, pct)
        cmp("Plafond du taux réduit d'IS", avant.isPlafondReduit, apres.isPlafondReduit, ::euro)
        cmp("IS taux normal", avant.isTauxNormal, apres.isTauxNormal, pct)
        cmp("Prélèvement forfaitaire sur les dividendes", avant.pfuPct, apres.pfuPct, pct)
        return out
    }

    /** Applique les barèmes à la société et à la paie (si la mise à jour automatique est active). */
    fun appliquer(d: SocieteData, b: Baremes): SocieteData {
        if (!b.valide() || !d.societe.baremesAuto) return d.copy(baremes = b)
        return d.copy(
            baremes = b,
            societe = d.societe.copy(isTauxReduit = b.isTauxReduit, isPlafondReduit = b.isPlafondReduit.takeIf { it > 0 } ?: d.societe.isPlafondReduit,
                isTauxNormal = b.isTauxNormal, pfuPct = b.pfuPct),
            paie = d.paie.copy(plafondSecu = b.pmss)
        )
    }

    /** Montants officiels d'un bulletin (€/mois), dans l'ordre de [Urssaf.EXPRESSIONS_PAIE]. */
    data class MontantsPaie(val v: Map<String, Double>) {
        operator fun get(k: String) = v[k] ?: 0.0
    }

    /** Remplace les lignes et totaux d'un bulletin par les montants officiels (le reste — heures, primes — est conservé). */
    fun bulletinOfficiel(local: Bulletin, m: MontantsPaie, pmss: Double, date: String): Bulletin {
        val brut = local.brut
        val plafonnee = if (pmss > 0) minOf(brut, pmss) else brut
        val csgBase = round2(brut * 0.9825)
        fun t(montant: Double, base: Double) = if (base > 0 && montant > 0) round2(montant / base * 100.0) else 0.0
        fun l(lib: String, base: Double, sal: Double, pat: Double) = LigneCotisation(lib, round2(base), t(sal, base), round2(sal), t(pat, base), round2(pat))
        val lignes = buildList {
            add(l("Santé — maladie, maternité, invalidité, décès", brut, m["maladie.salarie"], m["maladie.employeur"]))
            if (m["sante.salarie"] + m["sante.employeur"] > 0) add(LigneCotisation("Complémentaire santé (mutuelle)", 0.0, 0.0, round2(m["sante.salarie"]), 0.0, round2(m["sante.employeur"])))
            add(l("Accidents du travail — maladies professionnelles", brut, 0.0, m["atmp"]))
            add(l("Retraite — Sécurité sociale plafonnée", plafonnee, m["vieillesse.plafonnee.salarie"], m["vieillesse.plafonnee.employeur"]))
            add(l("Retraite — Sécurité sociale déplafonnée", brut, m["vieillesse.deplafonnee.salarie"], m["vieillesse.deplafonnee.employeur"]))
            add(l("Retraite complémentaire AGIRC-ARRCO (+ CEG, CET)", brut, m["retraite.salarie"], m["retraite.employeur"]))
            if (m["apec.salarie"] + m["apec.employeur"] > 0) add(l("APEC (cadres)", brut, m["apec.salarie"], m["apec.employeur"]))
            if (m["prevoyance.employeur"] > 0) add(l("Prévoyance (incapacité, invalidité, décès)", brut, 0.0, m["prevoyance.employeur"]))
            add(l("Famille — allocations familiales", brut, 0.0, m["famille"]))
            if (m["chomage"] > 0) add(l("Assurance chômage (+ AGS)", brut, 0.0, m["chomage"]))
            if (m["autres"] > 0) add(l("Autres contributions employeur (FNAL, CSA, formation, apprentissage, dialogue social…)", brut, 0.0, m["autres"]))
            add(l("CSG déductible de l'impôt sur le revenu", csgBase, m["csg.deductible"], 0.0))
            add(l("CSG/CRDS non déductible de l'impôt sur le revenu", csgBase, m["csg.non_deductible"] + m["csg.non_imposable"], 0.0))
            if (m["exo.employeur"] > 0) add(LigneCotisation("Réduction générale / exonérations de cotisations patronales", 0.0, 0.0, 0.0, 0.0, -round2(m["exo.employeur"])))
            if (m["exo.salarie"] > 0) add(LigneCotisation("Exonérations de cotisations salariales", 0.0, 0.0, -round2(m["exo.salarie"]), 0.0, 0.0))
        }
        val totalSal = round2(m["total.salarie"] - local.reductionHeuresSup.coerceAtLeast(0.0) * (if (m["exo.salarie"] > 0) 0.0 else 1.0))
        val netAvant = round2(brut - totalSal)
        val netImposable = round2(m["net.imposable"].takeIf { it > 0 } ?: (netAvant + m["csg.non_deductible"]))
        val pas = round2(netImposable * local.tauxPas / 100.0)
        val patronal = round2(m["total.employeur"])
        return local.copy(
            lignes = lignes, totalCotisationsSalariales = totalSal, netAvantImpot = netAvant, pas = pas, netAPayer = round2(netAvant - pas),
            totalChargesPatronales = patronal, coutEmployeur = round2(brut + patronal),
            netImposable = netImposable, netSocial = round2(m["net.social"].takeIf { it > 0 } ?: netAvant), allegements = round2(m["exo.employeur"]),
            source = "Cotisations calculées par le moteur officiel de l'URSSAF (mon-entreprise.urssaf.fr) avec les barèmes en vigueur au $date. " +
                "À faire valider par un expert-comptable ou un logiciel de paie avant tout usage officiel (convention collective, prévoyance et mutuelle réelles, taux AT/MP notifié)."
        )
    }
}
