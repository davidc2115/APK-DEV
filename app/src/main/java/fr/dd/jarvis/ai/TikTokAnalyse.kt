package fr.dd.jarvis.ai

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Calculs TikTok faits par JARVIS lui-même (pas par l'IA) : moyennes, médianes, engagement, meilleurs jours / heures /
 * durées, hashtags qui marchent, sons et accroches des tendances, profil des concurrents. L'IA ne fait ensuite
 * qu'interpréter ces chiffres réels.
 */
object TikTokAnalyse {
    data class Publication(
        val titre: String, val vues: Long, val likes: Long, val commentaires: Long, val partages: Long, val favoris: Long,
        val dureeS: Int, val creeLe: Long, val lien: String = "", val son: String = "", val auteur: String = ""
    ) {
        val engagement: Double get() = if (vues <= 0) 0.0 else (likes + commentaires + partages + favoris).toDouble() / vues
    }

    /** Vidéo de TON compte (API officielle TikTok). */
    fun depuisApi(o: JSONObject) = Publication(
        titre = o.optString("title").ifBlank { o.optString("video_description") }.trim(),
        vues = o.optLong("view_count"), likes = o.optLong("like_count"), commentaires = o.optLong("comment_count"),
        partages = o.optLong("share_count"), favoris = 0, dureeS = o.optInt("duration"), creeLe = o.optLong("create_time"),
        lien = o.optString("share_url")
    )

    fun depuisTikWm(v: TikWm.Video) = Publication(v.titre, v.vues, v.likes, v.commentaires, v.partages, v.favoris, v.dureeS, v.creeLe, v.lien, v.musique, v.auteur)

    // ------------------------------------------------------------------ outils

    /** 1 234 → « 1,2 k », 2 500 000 → « 2,5 M ». */
    fun nombre(n: Long): String = when {
        n >= 1_000_000_000 -> String.format(Locale.FRANCE, "%.1f Md", n / 1e9)
        n >= 1_000_000 -> String.format(Locale.FRANCE, "%.1f M", n / 1e6)
        n >= 10_000 -> String.format(Locale.FRANCE, "%.0f k", n / 1e3)
        n >= 1_000 -> String.format(Locale.FRANCE, "%.1f k", n / 1e3)
        else -> n.toString()
    }.replace(",0 ", " ")

    fun pourcent(x: Double): String = String.format(Locale.FRANCE, "%.1f %%", x * 100)

    fun mediane(l: List<Long>): Long {
        if (l.isEmpty()) return 0
        val s = l.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else ((s[s.size / 2 - 1] + s[s.size / 2]) / 2.0).roundToLong()
    }

    private val HASHTAG = Regex("""#([\p{L}\p{N}_]{2,60})""")

    fun hashtags(texte: String): List<String> = HASHTAG.findAll(texte).map { it.groupValues[1].lowercase() }.distinct().toList()

    /** Début du texte sans hashtags (l'accroche écrite). */
    fun accroche(titre: String, mots: Int = 10): String =
        titre.replace(HASHTAG, "").replace(Regex("\\s+"), " ").trim().split(' ').take(mots).joinToString(" ").trim()

    private fun court(t: String, n: Int = 60) = accroche(t, 14).ifBlank { "(sans texte)" }.let { if (it.length > n) it.take(n - 1) + "…" else it }

    private fun ligne(p: Publication) = "« ${court(p.titre)} » — 👁️ ${nombre(p.vues)} · ❤️ ${nombre(p.likes)} · engagement ${pourcent(p.engagement)} · ${p.dureeS} s" +
        (if (p.lien.isNotBlank()) " · ${p.lien}" else "")

    fun tranche(dureeS: Int) = when {
        dureeS < 15 -> "moins de 15 s"
        dureeS < 30 -> "15 à 30 s"
        dureeS < 60 -> "30 à 60 s"
        else -> "plus d'1 min"
    }

    fun creneau(heure: Int) = when (heure) {
        in 6..11 -> "matin (6 h-12 h)"
        in 12..13 -> "midi (12 h-14 h)"
        in 14..17 -> "après-midi (14 h-18 h)"
        in 18..21 -> "soirée (18 h-22 h)"
        else -> "nuit (22 h-6 h)"
    }

    /** Médiane des vues par groupe (au moins [min] vidéos), du meilleur au moins bon. */
    private fun <K> parGroupe(pubs: List<Publication>, min: Int = 1, cle: (Publication) -> K?): List<Triple<K, Long, Int>> =
        pubs.mapNotNull { p -> cle(p)?.let { it to p } }.groupBy({ it.first }, { it.second })
            .filter { it.value.size >= min }.map { (k, l) -> Triple(k, mediane(l.map { it.vues }), l.size) }.sortedByDescending { it.second }

    private fun rythme(pubs: List<Publication>): Double? {
        val dates = pubs.map { it.creeLe }.filter { it > 0 }.sorted()
        if (dates.size < 2) return null
        val semaines = (dates.last() - dates.first()) / (7 * 86_400.0)
        return if (semaines < 0.5) null else dates.size / semaines
    }

    // ------------------------------------------------------------------ bilans

    /** Bilan chiffré d'une liste de vidéos (ton compte ou un concurrent). */
    fun bilan(pubs: List<Publication>, zone: ZoneId = ZoneId.of("Europe/Paris")): String {
        if (pubs.isEmpty()) return "Aucune vidéo à analyser."
        val vues = pubs.map { it.vues }
        fun date(s: Long) = Instant.ofEpochSecond(s).atZone(zone).toLocalDate()
        val avecDate = pubs.filter { it.creeLe > 0 }
        return buildString {
            append("### 📊 ${pubs.size} vidéos")
            if (avecDate.isNotEmpty()) append(" (du ${date(avecDate.minOf { it.creeLe })} au ${date(avecDate.maxOf { it.creeLe })})")
            append("\n- Vues : médiane ${nombre(mediane(vues))} · moyenne ${nombre(vues.average().roundToLong())} · total ${nombre(vues.sum())}")
            append("\n- Engagement moyen : ${pourcent(pubs.map { it.engagement }.average())} (likes + commentaires + partages + favoris ÷ vues)")
            rythme(pubs)?.let { append("\n- Rythme : ${String.format(Locale.FRANCE, "%.1f", it)} vidéo(s) par semaine") }
            val tri = pubs.sortedByDescending { it.vues }
            append("\n\n**Les meilleures**\n" + tri.take(3).joinToString("\n") { "- " + ligne(it) })
            if (pubs.size >= 6) append("\n\n**Les moins vues**\n" + tri.takeLast(3).reversed().joinToString("\n") { "- " + ligne(it) })
            val durees = parGroupe(pubs) { if (it.dureeS > 0) tranche(it.dureeS) else null }
            if (durees.size >= 2) append("\n\n**Durée** (vues médianes)\n" + durees.joinToString("\n") { (k, v, n) -> "- $k : ${nombre(v)} ($n vidéo${if (n > 1) "s" else ""})" })
            val jours = parGroupe(avecDate) { Instant.ofEpochSecond(it.creeLe).atZone(zone).dayOfWeek }
            if (jours.size >= 2) append("\n\n**Jour de publication** (vues médianes)\n" + jours.joinToString("\n") { (k, v, n) ->
                "- ${(k as DayOfWeek).getDisplayName(TextStyle.FULL, Locale.FRANCE)} : ${nombre(v)} ($n)" })
            val heures = parGroupe(avecDate) { creneau(Instant.ofEpochSecond(it.creeLe).atZone(zone).hour) }
            if (heures.size >= 2) append("\n\n**Heure de publication** (vues médianes)\n" + heures.joinToString("\n") { (k, v, n) -> "- $k : ${nombre(v)} ($n)" })
            val tags = pubs.flatMap { p -> hashtags(p.titre).map { it to p } }.groupBy({ it.first }, { it.second })
                .filter { it.value.size >= 2 }.map { (t, l) -> Triple(t, mediane(l.map { it.vues }), l.size) }.sortedByDescending { it.second }
            if (tags.isNotEmpty()) append("\n\n**Hashtags utilisés plusieurs fois** (vues médianes)\n" + tags.take(8).joinToString("\n") { (t, v, n) -> "- #$t : ${nombre(v)} ($n)" })
        }
    }

    /** Ce qui ressort d'un lot de vidéos tendance : hashtags, sons, durées, accroches. */
    fun tendances(videos: List<TikWm.Video>, titre: String): String {
        if (videos.isEmpty()) return "Aucune vidéo tendance trouvée."
        val pubs = videos.map(::depuisTikWm)
        return buildString {
            append("### 🔥 $titre — ${videos.size} vidéos")
            append("\n- Vues : médiane ${nombre(mediane(pubs.map { it.vues }))} · engagement moyen ${pourcent(pubs.map { it.engagement }.average())}")
            append("\n- Durée médiane : ${mediane(pubs.map { it.dureeS.toLong() })} s")
            val tags = pubs.flatMap { p -> hashtags(p.titre).map { it to p.vues } }.groupBy({ it.first }, { it.second })
                .map { (t, v) -> Triple(t, v.size, v.sum()) }.sortedWith(compareByDescending<Triple<String, Int, Long>> { it.second }.thenByDescending { it.third })
            if (tags.isNotEmpty()) append("\n\n**Hashtags les plus présents**\n" + tags.take(12).joinToString("\n") { (t, n, v) -> "- #$t : $n vidéo(s), ${nombre(v)} vues" })
            val sons = videos.filter { it.musique.isNotBlank() && !it.musiqueOriginale }.groupBy { it.musique }
                .map { (s, l) -> Triple(s, l.size, l.sumOf { it.vues }) }.sortedWith(compareByDescending<Triple<String, Int, Long>> { it.second }.thenByDescending { it.third })
            if (sons.isNotEmpty()) append("\n\n**Sons repris**\n" + sons.take(8).joinToString("\n") { (s, n, v) -> "- $s : $n vidéo(s), ${nombre(v)} vues" })
            append("\n\n**Vidéos les plus vues**\n" + pubs.sortedByDescending { it.vues }.take(8).joinToString("\n") { p ->
                "- @${p.auteur} " + ligne(p) + (if (p.son.isNotBlank()) " · 🎵 ${p.son.take(50)}" else "") })
        }
    }

    /** Profil chiffré d'un concurrent. */
    fun concurrent(c: TikWm.Compte, videos: List<TikWm.Video>): String {
        val pubs = videos.map(::depuisTikWm)
        return buildString {
            append("## 🎯 @${c.pseudo}" + (if (c.nom.isNotBlank() && c.nom != c.pseudo) " (${c.nom})" else "") + (if (c.verifie) " ✔️" else ""))
            append("\n- Abonnés : ${nombre(c.abonnes)} · likes cumulés : ${nombre(c.likes)} · vidéos : ${c.videos}")
            if (c.bio.isNotBlank()) append("\n- Bio : ${c.bio.replace('\n', ' ').take(160)}")
            if (pubs.isNotEmpty() && c.abonnes > 0) append("\n- Vues médianes ÷ abonnés : ${pourcent(mediane(pubs.map { it.vues }).toDouble() / c.abonnes)}")
            append("\n\n" + bilan(pubs))
            val accroches = pubs.sortedByDescending { it.vues }.take(5).map { accroche(it.titre) }.filter { it.isNotBlank() }
            if (accroches.isNotEmpty()) append("\n\n**Accroches de ses vidéos les plus vues**\n" + accroches.joinToString("\n") { "- « $it »" })
        }
    }

    /** Une niche : hashtags (volume), créateurs qui dominent, vidéos qui marchent. */
    fun niche(motsCles: String, tags: List<TikWm.Hashtag>, videos: List<TikWm.Video>): String = buildString {
        append("### 🧭 Niche « $motsCles »")
        if (tags.isNotEmpty()) append("\n\n**Hashtags** (vues cumulées · vidéos)\n" + tags.sortedByDescending { it.vues }.take(12)
            .joinToString("\n") { "- #${it.nom} : ${nombre(it.vues)} vues · ${nombre(it.videos)} vidéos" })
        if (videos.isNotEmpty()) {
            val pubs = videos.map(::depuisTikWm)
            append("\n\n- ${videos.size} vidéos trouvées : vues médianes ${nombre(mediane(pubs.map { it.vues }))}, durée médiane ${mediane(pubs.map { it.dureeS.toLong() })} s")
            val createurs = videos.filter { it.auteur.isNotBlank() }.groupBy { it.auteur }.map { (a, l) -> Triple(a, l.size, l.sumOf { it.vues }) }
                .sortedByDescending { it.third }
            if (createurs.isNotEmpty()) append("\n\n**Créateurs qui dominent**\n" + createurs.take(8).joinToString("\n") { (a, n, v) -> "- @$a : $n vidéo(s), ${nombre(v)} vues" })
            append("\n\n**Vidéos qui marchent**\n" + pubs.sortedByDescending { it.vues }.take(8).joinToString("\n") { "- @${it.auteur} " + ligne(it) })
        }
    }
}
