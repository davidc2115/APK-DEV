package fr.dd.jarvis.societe.core

import java.text.Normalizer
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

fun round2(x: Double): Double = round(x * 100.0) / 100.0

/** 1 234,56 € */
fun euro(x: Double): String {
    val s = String.format(Locale.US, "%,.2f", x)
    return s.replace(',', ' ').replace('.', ',') + " €"
}

fun nombre(x: Double): String =
    if (x == floor(x)) x.toLong().toString() else String.format(Locale.US, "%.2f", x).trimEnd('0').trimEnd('.').replace('.', ',')

fun dateFr(iso: String): String = try {
    val d = LocalDate.parse(iso.take(10))
    "%02d/%02d/%04d".format(d.dayOfMonth, d.monthValue, d.year)
} catch (e: Exception) {
    iso
}

fun normaliser(s: String): String =
    Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

data class Remise(val pct: Double, val detail: String)

data class Totaux(
    val ht: Double,
    val tvaParTaux: List<Triple<Double, Double, Double>>, // (taux, base HT, montant TVA)
    val tva: Double,
    val ttc: Double,
    val remiseTotale: Double
)

data class PrixTarif(val puHt: Double?, val qte: Double, val unite: String, val avertissement: String = "")

object Pricing {

    /** Prix unitaire et quantité d'un tarif pour une installation de [kwc] kWc. */
    fun prix(t: Tarif, kwc: Double, qte: Double = 1.0): PrixTarif {
        val tr = t.tranches.sortedBy { it.jusquaKwc }
        if (tr.isEmpty()) return PrixTarif(null, qte, "", "Aucun prix défini pour « ${t.designation} ».")
        return when (t.mode) {
            Mode.HEURE -> PrixTarif(tr.first().prix, qte, "h")
            Mode.UNITE -> PrixTarif(tr.first().prix, qte, "")
            Mode.FORFAIT, Mode.PAR_KWC -> {
                if (kwc <= 0.0) return PrixTarif(null, qte, "", "Puissance de l'installation inconnue : « ${t.designation} » dépend de la puissance (kWc).")
                val hit = tr.firstOrNull { kwc <= it.jusquaKwc }
                val tranche = hit ?: tr.last()
                val warn = if (hit == null) "Puissance ${nombre(kwc)} kWc au-dessus de la dernière tranche : dernier tarif appliqué." else ""
                if (t.mode == Mode.FORFAIT) PrixTarif(tranche.prix, qte, "forfait", warn)
                else PrixTarif(tranche.prix, round2(kwc * qte), "kWc", warn)
            }
        }
    }

    fun trouverTarif(tarifs: List<Tarif>, nom: String): Tarif? {
        val q = normaliser(nom)
        if (q.isBlank()) return null
        tarifs.firstOrNull { normaliser(it.designation) == q }?.let { return it }
        val contient = tarifs.filter { normaliser(it.designation).contains(q) || q.contains(normaliser(it.designation)) }
        if (contient.size == 1) return contient.first()
        // meilleur recouvrement de mots
        val mots = q.split(" ").filter { it.length > 2 }.toSet()
        if (mots.isEmpty()) return null
        val scored = tarifs.map { t -> t to normaliser(t.designation).split(" ").count { it in mots } }.filter { it.second > 0 }
        val best = scored.maxByOrNull { it.second } ?: return null
        return if (scored.count { it.second == best.second } == 1) best.first else null
    }

    fun anciennete(depuis: String, aujourdhui: LocalDate = LocalDate.now()): Int = try {
        if (depuis.isBlank()) 0 else ChronoUnit.YEARS.between(LocalDate.parse(depuis.take(10)), aujourdhui).toInt().coerceAtLeast(0)
    } catch (e: Exception) {
        0
    }

    /** Remise commerciale automatique : fidélité (années) et volume (nombre d'installations). */
    fun remiseAuto(r: Regles, annees: Int, nbInstallations: Int): Remise {
        val fid = r.remiseFidelite.filter { annees >= it.seuil }.maxByOrNull { it.pct }
        val vol = r.remiseVolume.filter { nbInstallations >= it.seuil }.maxByOrNull { it.pct }
        val parts = mutableListOf<String>()
        fid?.let { parts += "fidélité ${annees} an(s) : ${nombre(it.pct)} %" }
        vol?.let { parts += "$nbInstallations installation(s) : ${nombre(it.pct)} %" }
        var pct = when {
            fid == null && vol == null -> 0.0
            r.remiseCumul -> (fid?.pct ?: 0.0) + (vol?.pct ?: 0.0)
            else -> max(fid?.pct ?: 0.0, vol?.pct ?: 0.0)
        }
        if (r.remiseMax > 0.0 && pct > r.remiseMax) {
            parts += "plafonnée à ${nombre(r.remiseMax)} %"
            pct = r.remiseMax
        }
        return Remise(pct, parts.joinToString(" ; "))
    }

    /** Frais de déplacement pour une distance [kmAller] (trajet simple, siège → client). */
    fun deplacement(r: Regles, kmAller: Double): Double {
        if (kmAller <= 0.0) return 0.0
        val factures = max(0.0, kmAller - r.kmGratuits)
        val total = factures * (if (r.allerRetour) 2 else 1) * r.kmPrix
        val min = if (r.kmPrix > 0.0 || r.deplacementMin > 0.0) r.deplacementMin else 0.0
        return round2(max(total, min))
    }

    fun deplacementConfigure(r: Regles) = r.kmPrix > 0.0 || r.deplacementMin > 0.0

    fun totalLigne(l: Ligne): Double = round2(l.qte * l.puHt * (1.0 - l.remisePct / 100.0))

    fun totaux(lignes: List<Ligne>, franchiseTva: Boolean): Totaux {
        val groupes = lignes.groupBy { if (franchiseTva) 0.0 else it.tva }
        val rows = groupes.entries.sortedBy { it.key }.map { (taux, ls) ->
            val base = round2(ls.sumOf { totalLigne(it) })
            Triple(taux, base, round2(base * taux / 100.0))
        }
        val ht = round2(rows.sumOf { it.second })
        val tva = round2(rows.sumOf { it.third })
        val brut = lignes.sumOf { round2(it.qte * it.puHt) }
        val remise = round2(brut - lignes.sumOf { totalLigne(it) })
        return Totaux(ht, rows, tva, round2(ht + tva), remise)
    }

    /** Construit les lignes d'un devis à partir des prestations demandées. */
    fun construireLignes(
        prestations: List<Triple<Tarif, Double, Double>>,   // (tarif, quantité demandée, puissance kWc)
        remisePct: Double,
        fraisDeplacement: Double,
        distanceKm: Double?,
        regles: Regles,
        tvaDefaut: Double,
        franchiseTva: Boolean
    ): Pair<List<Ligne>, List<String>> {
        val lignes = mutableListOf<Ligne>()
        val avertissements = mutableListOf<String>()
        for ((t, q, kwc) in prestations) {
            val p = prix(t, kwc, q)
            if (p.avertissement.isNotBlank()) avertissements += p.avertissement
            val pu = p.puHt ?: continue
            val suffixe = if (t.mode == Mode.FORFAIT || t.mode == Mode.PAR_KWC) " – ${nombre(kwc)} kWc" else ""
            lignes += Ligne(
                designation = t.designation + suffixe,
                qte = p.qte, unite = p.unite, puHt = pu,
                remisePct = remisePct,
                tva = if (franchiseTva) 0.0 else (t.tva ?: tvaDefaut)
            )
        }
        if (fraisDeplacement > 0.0) {
            val km = distanceKm?.let { " (${nombre(round2(it))} km" + (if (regles.allerRetour) " aller, A/R" else "") + ")" } ?: ""
            lignes += Ligne(
                designation = "Frais de déplacement$km",
                qte = 1.0, unite = "forfait", puHt = fraisDeplacement,
                remisePct = if (regles.remiseSurDeplacement) remisePct else 0.0,
                tva = if (franchiseTva) 0.0 else tvaDefaut,
                genre = "deplacement"
            )
        }
        return lignes to avertissements
    }

    /** Parse « 0-3:150; 3-9:220; 9+:350 » ou « jusqu'à 3:150 » en tranches. */
    fun parserTranches(texte: String): List<Tranche> {
        val out = mutableListOf<Tranche>()
        for (part in texte.split(';', '\n', '|')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val idx = p.lastIndexOf(':').takeIf { it >= 0 } ?: p.lastIndexOf('=').takeIf { it >= 0 } ?: continue
            val gauche = p.substring(0, idx).trim()
            val prix = p.substring(idx + 1).replace("€", "").replace("eur", "", true).replace(',', '.').trim().toDoubleOrNull() ?: continue
            val jusqua = when {
                gauche.contains("+") || gauche.contains("∞") || gauche.contains("plus", true) || gauche.contains("max", true) -> KWC_MAX
                else -> Regex("[0-9]+([.,][0-9]+)?").findAll(gauche).map { it.value.replace(',', '.').toDouble() }.lastOrNull() ?: KWC_MAX
            }
            out += Tranche(jusqua, prix)
        }
        return out.sortedBy { it.jusquaKwc }
    }

    fun parserSeuils(texte: String): List<Seuil> {
        val out = mutableListOf<Seuil>()
        for (part in texte.split(';', '\n', '|')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val bits = p.split(':', '=')
            if (bits.size < 2) continue
            val seuil = Regex("[0-9]+").find(bits[0])?.value?.toIntOrNull() ?: continue
            val pct = bits[1].replace("%", "").replace(',', '.').trim().toDoubleOrNull() ?: continue
            out += Seuil(seuil, pct)
        }
        return out.sortedBy { it.seuil }
    }

    fun libelleTranches(t: Tarif): String = t.tranches.sortedBy { it.jusquaKwc }.joinToString(" ; ") {
        (if (it.jusquaKwc >= KWC_MAX) "au-delà" else "≤ ${nombre(it.jusquaKwc)} kWc") + " : " + euro(it.prix)
    }
}
