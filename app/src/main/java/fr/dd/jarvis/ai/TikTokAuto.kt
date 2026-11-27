package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Détection AUTOMATIQUE de ce qui fait ta chaîne, à partir de tes propres vidéos (API TikTok officielle si le compte est
 * connecté, sinon tikwm avec ton pseudo) :
 *  - la niche, le style visuel et le bandeau de tes vidéos (analysés par l'IA d'après tes titres et hashtags) ;
 *  - tes concurrents : les comptes qui font le plus de vues sur les mêmes mots-clés et hashtags (recherche tikwm) ;
 *  - des images de référence : les miniatures de tes vidéos les plus vues, données au générateur d'images (FLUX.2 klein)
 *    pour que les nouvelles images ressemblent aux tiennes (même phénix, mêmes couleurs, même ambiance).
 * Refait chaque semaine ; ce que tu as écrit toi-même dans les Réglages n'est jamais remplacé.
 */
object TikTokAuto {
    data class MaVideo(val titre: String, val vues: Long, val couverture: String, val lien: String)
    data class Profil(val niche: String, val motsCles: List<String>, val style: String, val bandeau: String)

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    private const val SEMAINE = 7 * 24 * 3_600_000L

    private const val PROMPT = """Tu analyses une chaîne TikTok à partir des titres, descriptions et hashtags de ses vidéos (avec leurs vues).
Réponds UNIQUEMENT par un objet JSON :
{"niche": "la niche en une phrase en français (thème, angle, ton), fidèle à ce que montrent les vidéos",
 "mots_cles": ["3 à 5 mots-clés ou hashtags (sans #) qui décrivent la niche, tels qu'on les taperait dans la recherche TikTok"],
 "style": "le style visuel récurrent des vidéos en ANGLAIS pour un générateur d'images (sujet principal, couleurs, décor, lumière, ambiance), 25 mots au plus ; vide si les titres ne permettent pas de le savoir",
 "bandeau": "le court bandeau de 2 à 4 mots en MAJUSCULES séparés par « · » qui conviendrait en haut de chaque vidéo de cette chaîne (ex. LIBERTÉ · ÉGALITÉ · FRATERNITÉ) ; vide si rien ne s'impose"}
N'invente rien qui ne ressorte pas des vidéos."""

    /** « https://www.tiktok.com/@dd.patriote/video/123?… » → « dd.patriote ». */
    internal fun pseudoDepuisLien(lien: String): String = Regex("tiktok\\.com/@([A-Za-z0-9._]+)").find(lien)?.groupValues?.get(1).orEmpty()

    /**
     * Concurrents : comptes (hors toi) des vidéos trouvées sur tes mots-clés, classés par vues cumulées pondérées par le
     * nombre de leurs vidéos dans la niche (un compte régulier passe devant un seul succès isolé).
     */
    internal fun classerConcurrents(videos: List<TikWm.Video>, moi: String, max: Int = 5): List<String> =
        videos.filter { it.auteur.isNotBlank() && !it.auteur.equals(moi, ignoreCase = true) }
            .distinctBy { it.id }
            .groupBy { it.auteur.lowercase() }
            .map { (a, l) -> a to l.sumOf { it.vues }.toDouble() * kotlin.math.sqrt(l.size.toDouble()) }
            .sortedByDescending { it.second }.take(max).map { it.first }

    /** Taille d'une image de référence FLUX.2 (moins de 512 px de côté), proportions gardées. */
    internal fun tailleReference(l: Int, h: Int, max: Int = 500): Pair<Int, Int> {
        if (l <= 0 || h <= 0) return max to max
        val k = max.toDouble() / maxOf(l, h)
        return if (k >= 1.0) l to h else (l * k).toInt().coerceAtLeast(16) to (h * k).toInt().coerceAtLeast(16)
    }

    /** Lecture tolérante de la réponse JSON de l'IA. */
    internal fun lireProfil(brut: String): Profil? = runCatching {
        val j = JSONObject(brut.substring(brut.indexOf('{'), brut.lastIndexOf('}') + 1))
        val mots = j.optJSONArray("mots_cles")?.let { a -> (0 until a.length()).map { a.optString(it).trim().removePrefix("#") } }.orEmpty().filter { it.isNotBlank() }
        Profil(j.optString("niche").trim(), mots, j.optString("style").trim(), j.optString("bandeau").trim().uppercase())
    }.getOrNull()?.takeIf { it.niche.isNotBlank() }

    fun dossierReferences(app: JarvisApp) = File(app.filesDir, "tiktok_style").apply { mkdirs() }

    /** Miniatures de tes vidéos les plus vues (4 au plus), prêtes à servir de références. */
    fun references(app: JarvisApp): List<File> =
        dossierReferences(app).listFiles()?.filter { it.isFile && it.name.startsWith("ref_") && it.length() > 1_000 }?.sortedBy { it.name }?.take(4).orEmpty()

    /** À refaire : jamais fait, plus d'une semaine, ou niche / références manquantes. */
    fun aRafraichir(app: JarvisApp): Boolean {
        val s = app.store
        val date = s.tiktokAutoDate.value.toLongOrNull() ?: 0L
        return System.currentTimeMillis() - date > SEMAINE || s.tiktokNiche.value.isBlank() || references(app).isEmpty()
    }

    /** Possible : compte TikTok connecté, ou pseudo connu. */
    fun possible(app: JarvisApp) = TikTok.connecte(app) || app.store.tiktokPseudo.value.isNotBlank()

    /** Tes vidéos : API TikTok officielle (compte connecté), sinon tikwm avec ton pseudo. */
    private suspend fun mesVideos(app: JarvisApp): List<MaVideo> {
        if (TikTok.connecte(app)) runCatching {
            val a = TikTok.mesVideos(app, 40)
            val l = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { v ->
                MaVideo(listOf(v.optString("title"), v.optString("video_description")).filter { it.isNotBlank() }.distinct().joinToString(" — "),
                    v.optLong("view_count"), v.optString("cover_image_url"), v.optString("share_url"))
            }
            if (l.isNotEmpty()) return l
        }
        val pseudo = app.store.tiktokPseudo.value.trim()
        if (pseudo.isBlank()) return emptyList()
        return TikWm.videosDe(pseudo, 40).map { MaVideo(it.titre, it.vues, it.couverture, it.lien) }
    }

    /** Miniature → image de référence (moins de 512 px), ou null. */
    private fun telechargerReference(url: String, dest: File): Boolean = runCatching {
        val octets = http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null } ?: return@runCatching false
        val b = android.graphics.BitmapFactory.decodeByteArray(octets, 0, octets.size) ?: return@runCatching false
        val (l, h) = tailleReference(b.width, b.height)
        val r = android.graphics.Bitmap.createScaledBitmap(b, l, h, true)
        dest.outputStream().use { r.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        if (r !== b) r.recycle()
        b.recycle()
        dest.length() > 1_000
    }.getOrDefault(false)

    /**
     * Détecte niche, style, bandeau, concurrents et images de référence. [force] : remplace aussi ce que tu as écrit
     * toi-même (seulement quand tu le demandes). Renvoie un résumé lisible.
     */
    suspend fun detecter(app: JarvisApp, force: Boolean = false): String = withContext(Dispatchers.IO) {
        val s = app.store
        val videos = mesVideos(app)
        if (videos.isEmpty()) return@withContext "❌ Je n'ai pas pu lire tes vidéos : connecte ton compte TikTok (Réglages › Comptes › TikTok) ou donne-moi ton pseudo TikTok."
        val pseudo = videos.firstNotNullOfOrNull { pseudoDepuisLien(it.lien).takeIf { p -> p.isNotBlank() } } ?: s.tiktokPseudo.value.trim()
        if (pseudo.isNotBlank()) s.tiktokPseudo.set(pseudo)
        val top = videos.sortedByDescending { it.vues }
        val tags = videos.flatMap { TikTokAnalyse.hashtags(it.titre) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12)

        // 1. Niche, style, bandeau : l'IA lit tes titres et hashtags.
        val contexte = buildString {
            append("Compte @$pseudo — ${videos.size} vidéos lues.\n")
            if (tags.isNotEmpty()) append("Hashtags les plus utilisés : " + tags.joinToString(", ") { "#${it.key} (${it.value})" } + "\n")
            append("Vidéos, de la plus vue à la moins vue :\n")
            top.take(25).forEach { append("- « ${it.titre.take(160)} » (${TikTokAnalyse.nombre(it.vues)} vues)\n") }
        }
        val profil = runCatching { lireProfil(app.llm.agent(PROMPT, contexte)) }.getOrNull()
        val resume = mutableListOf<String>()
        if (profil != null) {
            if (force || s.tiktokNiche.value.isBlank() || s.tiktokNicheAuto.value) {
                s.tiktokNiche.set(profil.niche + if (profil.motsCles.isNotEmpty()) " (" + profil.motsCles.joinToString(", ") + ")" else "")
                s.tiktokNicheAuto.set(true)
                resume += "🎯 Niche : ${s.tiktokNiche.value}"
            } else resume += "🎯 Niche gardée (écrite par toi) : ${s.tiktokNiche.value}"
            if (profil.style.isNotBlank() && (force || s.tiktokStyle.value.isBlank())) { s.tiktokStyle.set(profil.style); resume += "🎨 Style : ${profil.style}" }
            if (profil.bandeau.isNotBlank() && (force || s.tiktokBandeau.value.isBlank())) { s.tiktokBandeau.set(profil.bandeau); resume += "🏷️ Bandeau : ${profil.bandeau}" }
        } else resume += "⚠️ Niche non déterminée (IA indisponible) : réessaie plus tard."

        // 2. Concurrents : qui fait le plus de vues sur les mêmes mots-clés et hashtags.
        if (force || s.tiktokConcurrents.value.isBlank() || s.tiktokConcurrentsAuto.value) {
            val recherches = (profil?.motsCles.orEmpty() + tags.map { it.key }).map { it.trim() }.filter { it.length > 2 }.distinct().take(4)
            val trouvees = recherches.flatMap { q -> runCatching { TikWm.recherche(q, 30) }.getOrDefault(emptyList()) }
            val concurrents = classerConcurrents(trouvees, pseudo)
            if (concurrents.isNotEmpty()) {
                s.tiktokConcurrents.set(concurrents.joinToString(", ") { "@$it" })
                s.tiktokConcurrentsAuto.set(true)
                resume += "🥊 Concurrents : ${s.tiktokConcurrents.value}"
            } else resume += "⚠️ Concurrents non trouvés (tikwm indisponible ?)"
        } else resume += "🥊 Concurrents gardés (écrits par toi) : ${s.tiktokConcurrents.value}"

        // 3. Images de référence : miniatures de tes 4 vidéos les plus vues.
        val dossier = dossierReferences(app)
        val nouvelles = top.filter { it.couverture.startsWith("http") }.take(6).mapIndexedNotNull { k, v ->
            File(dossier, "tmp_$k.jpg").takeIf { telechargerReference(v.couverture, it) }
        }.take(4)
        if (nouvelles.isNotEmpty()) {
            dossier.listFiles()?.filter { it.name.startsWith("ref_") }?.forEach { it.delete() }
            nouvelles.forEachIndexed { k, f -> f.renameTo(File(dossier, "ref_$k.jpg")) }
            resume += "🖼️ ${nouvelles.size} miniature${if (nouvelles.size > 1) "s" else ""} de tes vidéos les plus vues gardée${if (nouvelles.size > 1) "s" else ""} comme modèle pour les images"
        }
        dossier.listFiles()?.filter { it.name.startsWith("tmp_") }?.forEach { it.delete() }

        s.tiktokAutoDate.set(System.currentTimeMillis().toString())
        "🔍 Chaîne @$pseudo analysée (${videos.size} vidéos) :\n" + resume.joinToString("\n")
    }

    /** Détection seulement si besoin (première fois, ou plus d'une semaine) ; ne bloque jamais la suite en cas d'échec. */
    suspend fun siBesoin(app: JarvisApp): String? =
        if (possible(app) && aRafraichir(app)) runCatching { kotlinx.coroutines.withTimeout(120_000L) { detecter(app) } }.getOrNull() else null
}
