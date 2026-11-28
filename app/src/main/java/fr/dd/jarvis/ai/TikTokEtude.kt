package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Étude approfondie TikTok :
 *  - récupérer une vidéo (la tienne ou celle d'un concurrent) depuis TikTok, sans filigrane (tikwm), dans l'espace
 *    privé de JARVIS (jamais dans la galerie) ; la tienne peut être retravaillée ou renvoyée en brouillon ;
 *  - l'analyser EN ENTIER (images, textes à l'écran, voix, musique) avec Gemini, chiffres à l'appui ;
 *  - trouver les meilleurs créneaux de publication (tes vidéos + celles des concurrents, performance relative) ;
 *  - repérer les vidéos des concurrents qui SURPERFORMENT et en tirer des idées originales pour ta chaîne (les
 *    mécanismes qui marchent, jamais une copie : leurs vidéos ne sont jamais republiées).
 */
object TikTokEtude {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

    // ------------------------------------------------------------------ fonctions pures (testées)

    private fun sansAccents(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    /** « 1 », « la 3e », « la dernière », « la plus vue », « la moins vue » → la vidéo voulue (1 = la plus récente), ou null. */
    internal fun rang(texte: String, videos: List<TikWm.Video>): TikWm.Video? {
        val t = sansAccents(texte)
        if (videos.isEmpty()) return null
        val recentes = videos.sortedByDescending { it.creeLe }
        // Phrase courte seulement (« ma vidéo la plus vue ») : « ma vidéo sur la meilleure baguette de Paris » désigne un titre.
        val court = t.split(Regex("\\s+")).size <= 6
        return when {
            court && Regex("plus vue|meilleure|plus de vues|\\btop\\b|cartonn").containsMatchIn(t) -> videos.maxByOrNull { it.vues }
            court && Regex("moins vue|pire|moins de vues|\\bflop").containsMatchIn(t) -> videos.minByOrNull { it.vues }
            court && Regex("avant.?derniere").containsMatchIn(t) -> recentes.getOrNull(1)
            court && Regex("derniere|plus recente").containsMatchIn(t) -> recentes.firstOrNull()
            else -> Regex("^(?:(?:ma|la|le|video|n°|no|numero|#)\\s*)*(\\d{1,3})\\s*(?:e|eme|ere|er)?(?:\\s+(?:video|tiktok))?$")
                .find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { recentes.getOrNull(it - 1) }
        }
    }

    /** Vidéos qui font au moins [seuil] fois les vues médianes de LEUR compte (ce qui marche vraiment, pas juste les gros comptes). */
    internal fun surperformances(videos: List<TikWm.Video>, seuil: Double = 1.8, minVues: Long = 2_000): List<Pair<TikWm.Video, Double>> =
        videos.groupBy { it.auteur.lowercase() }.flatMap { (_, l) ->
            val med = TikTokAnalyse.mediane(l.map { it.vues }).coerceAtLeast(1)
            l.filter { it.vues >= minVues }.map { it to it.vues.toDouble() / med }.filter { it.second >= seuil }
        }.sortedByDescending { (v, r) -> r * kotlin.math.ln(v.vues.toDouble() + 10) }

    data class Creneau(val libelle: String, val ratioMedian: Double, val n: Int)

    /** Ratio de chaque vidéo par rapport aux vues médianes de son compte : (date de publication en s, ratio). */
    internal fun ratios(videos: List<TikWm.Video>): List<Pair<Long, Double>> = videos.groupBy { it.auteur.lowercase() }.flatMap { (_, l) ->
        val med = TikTokAnalyse.mediane(l.map { it.vues }).coerceAtLeast(1)
        l.map { it.creeLe to it.vues.toDouble() / med }
    }

    /**
     * Créneaux (tranches de 2 h, heure de Paris) et jours classés par performance RELATIVE : vues de la vidéo ÷ vues
     * médianes de son compte (une vidéo d'un petit compte compte autant que celle d'un gros). [points] : sortie de [ratios].
     */
    internal fun creneaux(points: List<Pair<Long, Double>>, minParCreneau: Int = 2, zone: ZoneId = PARIS): Pair<List<Creneau>, List<Creneau>> {
        fun med(l: List<Double>) = l.sorted().let { s -> if (s.isEmpty()) 0.0 else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
        val p = points.filter { it.first > 0 }
        val heures = p.groupBy { Instant.ofEpochSecond(it.first).atZone(zone).hour / 2 }
            .filter { it.value.size >= minParCreneau }
            .map { (h, l) -> Creneau(String.format(Locale.FRANCE, "%02dh-%02dh", h * 2, (h * 2 + 2) % 24), med(l.map { it.second }), l.size) }
            .sortedWith(compareByDescending<Creneau> { it.ratioMedian }.thenByDescending { it.n })
        val jours = p.groupBy { Instant.ofEpochSecond(it.first).atZone(zone).dayOfWeek }
            .filter { it.value.size >= minParCreneau }
            .map { (j, l) -> Creneau(j.getDisplayName(TextStyle.FULL, Locale.FRANCE), med(l.map { it.second }), l.size) }
            .sortedWith(compareByDescending<Creneau> { it.ratioMedian }.thenByDescending { it.n })
        return heures to jours
    }

    /** Créneaux conseillés : tes données comptent double, celles des concurrents complètent ; un créneau très peu fourni pèse moins. */
    internal fun conseil(moi: List<Creneau>, concurrents: List<Creneau>, nombre: Int = 3): List<String> {
        val score = LinkedHashMap<String, Double>()
        moi.forEach { score[it.libelle] = (score[it.libelle] ?: 0.0) + 2 * it.ratioMedian * minOf(it.n, 5) }
        concurrents.forEach { score[it.libelle] = (score[it.libelle] ?: 0.0) + it.ratioMedian * minOf(it.n, 5) }
        return score.entries.sortedByDescending { it.value }.take(nombre).map { it.key }
    }

    /** Nom du fichier téléchargé : « tiktok_moi_<id>.mp4 » pour tes vidéos, « tiktok_de_<compte>_<id>.mp4 » pour les autres. */
    internal fun nomFichier(v: TikWm.Video, aMoi: Boolean): String =
        if (aMoi) "tiktok_moi_${v.id}.mp4" else "tiktok_de_${v.auteur.ifBlank { "inconnu" }.replace(Regex("[^A-Za-z0-9._-]"), "_")}_${v.id}.mp4"

    /** Compte d'origine d'une vidéo de concurrent téléchargée par JARVIS (null : la tienne ou un autre fichier). */
    internal fun auteurConcurrent(nom: String): String? =
        Regex("^tiktok_de_(.+)_(\\d+)\\.mp4$", RegexOption.IGNORE_CASE).find(nom.substringAfterLast('/').trim())?.groupValues?.get(1)

    // ------------------------------------------------------------------ tes vidéos

    /** Tes vidéos (API TikTok officielle si le compte est connecté, sinon tikwm avec ton pseudo). */
    suspend fun mesVideos(app: JarvisApp, max: Int = 40): List<TikWm.Video> {
        if (TikTok.connecte(app)) runCatching {
            val a = TikTok.mesVideos(app, max)
            val l = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { v ->
                val lien = v.optString("share_url")
                TikWm.Video(
                    id = v.optString("id"),
                    titre = listOf(v.optString("title"), v.optString("video_description")).filter { it.isNotBlank() }.distinct().joinToString(" — "),
                    auteur = TikTokAuto.pseudoDepuisLien(lien).ifBlank { app.store.tiktokPseudo.value.trim().removePrefix("@") },
                    vues = v.optLong("view_count"), likes = v.optLong("like_count"), commentaires = v.optLong("comment_count"),
                    partages = v.optLong("share_count"), favoris = 0, dureeS = v.optInt("duration"), creeLe = v.optLong("create_time"),
                    musique = "", musiqueOriginale = false, lecture = "", couverture = v.optString("cover_image_url")
                )
            }
            if (l.isNotEmpty()) return l
        }
        val pseudo = app.store.tiktokPseudo.value.trim()
        if (pseudo.isBlank()) throw IllegalStateException("connecte ton compte TikTok (Réglages › Comptes › TikTok) ou donne-moi ton pseudo TikTok")
        return TikWm.videosDe(pseudo, max)
    }

    /** Vidéo retrouvée, et si elle est À TOI (seules tes vidéos peuvent être retravaillées et renvoyées en brouillon). */
    data class Cible(val video: TikWm.Video, val aMoi: Boolean)

    /** Une vidéo désignée par un lien TikTok, un rang (« la dernière », « la plus vue », « 3 ») ou un bout de titre parmi les tiennes. */
    suspend fun trouver(app: JarvisApp, cible: String): Cible {
        val c = cible.trim()
        if (c.contains("tiktok.com", ignoreCase = true)) {
            val d = TikWm.get("", mapOf("url" to c, "hd" to "1")).optJSONObject("data") ?: throw IllegalStateException("vidéo introuvable sur TikTok : $c")
            val v = TikWm.video(d).let { if (it.auteur.isBlank()) it.copy(auteur = TikTokAuto.pseudoDepuisLien(c)) else it }
            return Cible(v, estAMoi(app, v))
        }
        val miennes = mesVideos(app)
        if (miennes.isEmpty()) throw IllegalStateException("aucune vidéo trouvée sur ton compte TikTok")
        rang(c.ifBlank { "la dernière" }, miennes)?.let { return Cible(it, true) }
        val brut = sansAccents(c)
        val mots = brut.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 3 && it !in setOf("video", "videos", "tiktok", "derniere", "celle") }
        val v = miennes.firstOrNull { sansAccents(it.titre).contains(brut) }
            ?: miennes.map { v -> v to mots.count { m -> sansAccents(v.titre).contains(m) } }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
            ?: throw IllegalStateException("aucune de tes vidéos ne correspond à « $c » (dis « la dernière », « la plus vue », un numéro ou colle le lien)")
        return Cible(v, true)
    }

    /** Ta vidéo ? Pseudo enregistré, ou identifiant présent dans les vidéos du compte connecté. */
    suspend fun estAMoi(app: JarvisApp, v: TikWm.Video): Boolean {
        val pseudo = app.store.tiktokPseudo.value.trim().removePrefix("@")
        if (pseudo.isNotBlank() && v.auteur.isNotBlank()) return v.auteur.equals(pseudo, ignoreCase = true)
        if (!TikTok.connecte(app)) return false
        return runCatching { TikTok.mesVideos(app, 60).let { a -> (0 until a.length()).any { a.optJSONObject(it)?.optString("id") == v.id } } }.getOrDefault(false)
    }

    /** Télécharge la vidéo (sans filigrane) dans l'espace privé de JARVIS : utilisable par son nom (montage, brouillon, analyse). */
    suspend fun telecharger(app: JarvisApp, v: TikWm.Video, aMoi: Boolean): File = withContext(Dispatchers.IO) {
        DocumentStore.purgerPiecesJointes(app)
        val dest = File(DocumentStore.dossierPiecesJointes(app), nomFichier(v, aMoi))
        if (dest.isFile && dest.length() > 50_000) return@withContext dest.also { it.setLastModified(System.currentTimeMillis()) }
        val lien = v.lien.ifBlank { "https://www.tiktok.com/@${v.auteur}/video/${v.id}" }
        // Version HD sans filigrane d'abord ; l'adresse de lecture de la liste ensuite (elle expire vite).
        val adresses = listOf(runCatching { TikWm.fichierVideo(lien) }.getOrDefault(""), v.lecture.let { if (it.startsWith("/")) "https://www.tikwm.com$it" else it })
            .filter { it.startsWith("http") }.distinct()
        if (adresses.isEmpty()) throw IllegalStateException("tikwm n'a pas donné le fichier de cette vidéo (service gratuit saturé ?) : réessaie plus tard")
        var erreur = ""
        for (url in adresses) {
            val ok = runCatching {
                http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 16) JARVIS").build()).execute().use { r ->
                    val corps = r.body ?: throw IllegalStateException("téléchargement vide")
                    if (!r.isSuccessful) throw IllegalStateException("téléchargement refusé (${r.code})")
                    if (corps.contentLength() > 300L * 1024 * 1024) throw IllegalStateException("vidéo trop lourde (> 300 Mo)")
                    dest.outputStream().use { out -> corps.byteStream().copyTo(out) }
                }
                dest.length() > 50_000
            }.getOrElse { erreur = it.message.orEmpty(); false }
            if (ok) return@withContext dest
            dest.delete()
        }
        throw IllegalStateException("téléchargement de la vidéo impossible (${erreur.ifBlank { "fichier incomplet" }})")
    }

    /** Retrouve puis télécharge la vidéo. */
    suspend fun recuperer(app: JarvisApp, cible: String): Pair<Cible, File> {
        val c = trouver(app, cible)
        return c to telecharger(app, c.video, c.aMoi)
    }

    private fun quand(creeLe: Long) = if (creeLe <= 0) "date inconnue" else Instant.ofEpochSecond(creeLe).atZone(PARIS).let {
        "${it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.FRANCE)} ${it.toLocalDate()} à ${it.hour}h${"%02d".format(it.minute)}"
    }

    /** Ligne de chiffres d'une vidéo (avec le rapport aux vues médianes du compte quand on le connaît). */
    internal fun stats(v: TikWm.Video, medianeCompte: Long?): String =
        "@${v.auteur.ifBlank { "?" }} · ${TikTokAnalyse.nombre(v.vues)} vues · ${TikTokAnalyse.nombre(v.likes)} likes · ${TikTokAnalyse.nombre(v.commentaires)} commentaires · " +
            "${TikTokAnalyse.nombre(v.partages)} partages · engagement ${TikTokAnalyse.pourcent(v.engagement)} · ${v.dureeS} s · publiée le ${quand(v.creeLe)}" +
            (medianeCompte?.takeIf { it > 0 }?.let { " · ${"%.1f".format(Locale.FRANCE, v.vues.toDouble() / it)} × les vues médianes du compte (${TikTokAnalyse.nombre(it)})" } ?: "") +
            (if (v.musique.isNotBlank()) " · son : ${v.musique}" else "")

    // ------------------------------------------------------------------ analyse complète d'une vidéo

    private fun consigne(aMoi: Boolean, chiffres: String, niche: String) = """Tu es un expert TikTok francophone. Regarde cette vidéo EN ENTIER (images, textes à l'écran, voix, musique, rythme) et analyse-la.
Chiffres réels : $chiffres
${if (niche.isNotBlank()) "Niche de ma chaîne : $niche\n" else ""}Réponds en français, concret, sans rien inventer de ce que tu ne vois ou n'entends pas, avec ces parties :
## 🪝 Accroche (0-3 s) — ce qu'on voit et entend ; retient-elle ? note sur 10
## 🎬 Structure et rythme — nombre de plans approximatif, durée moyenne d'un plan, progression
## 📝 Textes à l'écran — lesquels, lisibilité, utilité
## 🎙️ Voix et son — ce qui est dit (résumé fidèle), musique, ambiance
## 📣 Appel à l'action — présent ? efficace ?
## 📊 Pourquoi ces résultats — relie ce que tu vois aux chiffres
## ✅ Points forts · ⚠️ Points faibles
""" + if (aMoi) "## 🔧 5 améliorations concrètes pour la refaire (accroche réécrite mot pour mot, coupes, textes, son, durée)"
    else "## 💡 Mécanismes à reprendre pour MA chaîne — formats, structures d'accroche, rythmes ; JAMAIS recopier ses images, ses textes ni sa voix"

    /** Analyse complète : chiffres (comparés à la médiane du compte) + contenu regardé par Gemini. */
    suspend fun analyser(app: JarvisApp, cible: String): String {
        val (v, aMoi) = trouver(app, cible)
        val compte = runCatching { if (aMoi) mesVideos(app, 30) else TikWm.videosDe(v.auteur, 25) }.getOrDefault(emptyList())
        val chiffres = stats(v, TikTokAnalyse.mediane(compte.map { it.vues }).takeIf { compte.size >= 3 })
        val entete = "# 🔍 « ${TikTokAnalyse.accroche(v.titre, 14).ifBlank { "vidéo ${v.id}" }} »" + (if (aMoi) "" else " (concurrent)") + "\n$chiffres\n${v.lien}\n\n"
        if (app.store.geminiKeys.isEmpty()) return entete + "⚠️ Pour que l'IA regarde la vidéo en entier, ajoute une clé Gemini (gratuite) dans Réglages › IA."
        val f = runCatching { telecharger(app, v, aMoi) }.getOrElse { return entete + "⚠️ Vidéo non téléchargée (${it.message}) : analyse limitée aux chiffres ci-dessus." }
        val contenu = runCatching { app.llm.gemini.analyserVideo(f, consigne(aMoi, chiffres, app.store.tiktokNiche.value.trim())) }
            .getOrElse { return entete + "⚠️ Gemini n'a pas pu regarder la vidéo (${it.message?.take(150)}). Fichier gardé : « ${f.name} »." }
        return entete + contenu + "\n\n📁 Fichier : « ${f.name} » (gardé 2 jours dans JARVIS, jamais dans ta galerie)" +
            if (aMoi) " — pour la retravailler : « refais cette vidéo avec … » ; pour la renvoyer telle quelle en brouillon : « renvoie-la en brouillon »."
            else " — vidéo d'un autre compte : elle sert à l'analyse, elle ne sera ni retravaillée ni republiée."
    }

    // ------------------------------------------------------------------ meilleurs horaires

    suspend fun horaires(app: JarvisApp): String {
        val miennes = runCatching { mesVideos(app, 60) }.getOrDefault(emptyList())
        if (app.store.tiktokConcurrents.value.isBlank() && TikTokAuto.possible(app)) runCatching { TikTokAuto.detecter(app) }
        val comptes = TikTokPropositions.comptes(app.store.tiktokConcurrents.value).take(5)
        val concurrentes = comptes.flatMap { c -> runCatching { TikWm.videosDe(c, 30) }.getOrDefault(emptyList()) }
        if (miennes.isEmpty() && concurrentes.isEmpty()) return "❌ Pas assez de données : connecte ton compte TikTok (ou donne ton pseudo) et indique tes concurrents."
        val (hMoi, jMoi) = creneaux(ratios(miennes))
        val (hConc, jConc) = creneaux(ratios(concurrentes), minParCreneau = 3)
        fun liste(l: List<Creneau>) = l.take(4).joinToString("\n") { "- **${it.libelle}** : ${"%.1f".format(Locale.FRANCE, it.ratioMedian)} × la médiane (${it.n} vidéos)" }
        val heures = conseil(hMoi, hConc)
        val jours = conseil(jMoi, jConc, 2)
        return buildString {
            append("# ⏰ Meilleurs moments pour publier (heure de Paris)\n")
            append("Mesure : vues de chaque vidéo ÷ vues médianes de son compte (1,0 = normal, 2,0 = deux fois plus).\n")
            if (hMoi.isNotEmpty()) append("\n**Tes ${miennes.size} vidéos — créneaux**\n${liste(hMoi)}\n")
            else if (miennes.isNotEmpty()) append("\n_Tes ${miennes.size} vidéos : pas encore 2 publications sur un même créneau, les concurrents servent de repère._\n")
            if (jMoi.isNotEmpty()) append("\n**Tes vidéos — jours**\n${liste(jMoi)}\n")
            if (hConc.isNotEmpty()) append("\n**Concurrents (${comptes.joinToString { "@$it" }}) — créneaux**\n${liste(hConc)}\n")
            if (jConc.isNotEmpty()) append("\n**Concurrents — jours**\n${liste(jConc)}\n")
            if (heures.isNotEmpty()) append("\n## ✅ Conseil\nPublie de préférence **${heures.joinToString(" ou ")}**" +
                (if (jours.isNotEmpty()) ", idéalement le **${jours.joinToString(" ou ")}**" else "") + ".\n")
            if (miennes.size + concurrentes.size < 25) append("\n⚠️ Peu de vidéos analysées : tendance à confirmer au fil des publications.\n")
            append("\nℹ️ L'appli TikTok (Statistiques › Abonnés) montre en plus les heures où TES abonnés sont connectés : ce chiffre n'est pas accessible aux applis extérieures.")
        }
    }

    // ------------------------------------------------------------------ idées tirées de la concurrence

    private const val SYNTHESE = """Tu es un stratège TikTok. On te donne les vidéos des concurrents qui SURPERFORMENT (bien au-dessus des vues habituelles de leur compte), parfois avec l'analyse de leur contenu, et la niche et le style de MA chaîne.
Produis en français :
## 🔎 Ce qui marche chez eux — 4 à 6 mécanismes communs et précis (accroche, format, durée, émotion, angle, son, appel à l'action), chacun appuyé par des exemples chiffrés de la liste
## 💡 5 idées ORIGINALES pour ma chaîne — pour chacune : titre, accroche (0-3 s) mot pour mot, déroulé en 4-6 plans, texte à l'écran, appel à l'action, durée conseillée, pourquoi ça devrait marcher (mécanisme repris)
## 🚫 À éviter
Règle absolue : reprendre des MÉCANISMES, jamais copier une vidéo (ni ses images, ni ses textes, ni sa voix, ni ses personnages) ; tout doit coller à ma niche et à mon style."""

    suspend fun ideesConcurrents(app: JarvisApp, comptesTexte: String = "", approfondir: Int = 2): String {
        if (comptesTexte.isBlank() && app.store.tiktokConcurrents.value.isBlank() && TikTokAuto.possible(app)) runCatching { TikTokAuto.detecter(app) }
        val comptes = TikTokPropositions.comptes(comptesTexte.ifBlank { app.store.tiktokConcurrents.value }).take(6)
        if (comptes.isEmpty()) return "❌ Aucun concurrent connu : dis « détecte ma niche et mes concurrents » ou donne-moi des comptes (@…)."
        val videos = comptes.flatMap { c -> runCatching { TikWm.videosDe(c, 30) }.getOrDefault(emptyList()) }
        if (videos.isEmpty()) return "❌ Impossible de lire les vidéos des concurrents (tikwm, service gratuit non officiel, ne répond pas) : réessaie plus tard."
        val top = surperformances(videos).take(10).ifEmpty {
            // Personne ne sort du lot : les plus engageantes servent de repère.
            videos.sortedByDescending { it.engagement * kotlin.math.ln(it.vues + 10.0) }.take(6).map { it to 1.0 }
        }
        val analyses = if (app.store.geminiKeys.isEmpty()) emptyList() else top.take(approfondir.coerceIn(0, 3)).mapNotNull { (v, _) ->
            runCatching {
                val f = telecharger(app, v, false)
                try {
                    val a = app.llm.gemini.analyserVideo(f, "Décris en 8 lignes au plus, en français : l'accroche des 3 premières secondes (mot pour mot), la structure, " +
                        "le rythme, les textes à l'écran, le ton de la voix, le son, l'appel à l'action. Rien d'inventé.")
                    "@${v.auteur} « ${TikTokAnalyse.accroche(v.titre, 10)} » :\n$a"
                } finally { runCatching { f.delete() } }
            }.getOrNull()
        }
        val contexte = buildString {
            append("MA chaîne — niche : ${app.store.tiktokNiche.value.ifBlank { "non renseignée" }} ; style : ${app.store.tiktokStyle.value.ifBlank { "non renseigné" }}\n\n")
            append("Vidéos des concurrents qui surperforment (× vues médianes de leur compte) :\n")
            top.forEach { (v, r) ->
                append("- @${v.auteur} · ${"%.1f".format(Locale.FRANCE, r)}× · ${TikTokAnalyse.nombre(v.vues)} vues · engagement ${TikTokAnalyse.pourcent(v.engagement)} · ${v.dureeS} s · " +
                    "« ${TikTokAnalyse.accroche(v.titre, 16)} »" + (if (v.musique.isNotBlank()) " · son : ${v.musique.take(50)}" else "") + "\n")
            }
            if (analyses.isNotEmpty()) append("\nContenu regardé :\n" + analyses.joinToString("\n\n"))
        }
        val synthese = runCatching { app.llm.agent(SYNTHESE, contexte) }.getOrElse { "⚠️ Synthèse impossible (${it.message?.take(100)})." }
        return "# 🥊 Ce qui marche chez tes concurrents (${comptes.joinToString { "@$it" }})\n" +
            top.take(6).joinToString("\n") { (v, r) -> "- @${v.auteur} · **${"%.1f".format(Locale.FRANCE, r)}×** sa médiane · ${TikTokAnalyse.nombre(v.vues)} vues · « ${TikTokAnalyse.accroche(v.titre, 10)} » ${v.lien}" } +
            (if (analyses.isNotEmpty()) "\n_(${analyses.size} vidéo${if (analyses.size > 1) "s" else ""} regardée${if (analyses.size > 1) "s" else ""} en entier)_" else "") +
            "\n\n" + synthese
    }
}
