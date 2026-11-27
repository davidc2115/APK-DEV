package fr.dd.jarvis.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Images GRATUITES de secours sur Hugging Face (Spaces « ZeroGPU », sans carte bancaire) quand le quota quotidien de
 * Cloudflare est épuisé : Z-Image-Turbo (Space officiel de Tongyi, puis une copie communautaire). Un compte gratuit n'a
 * que 5 min de GPU par jour ET quelques utilisations seulement (≈ 3 par jour en 2026), chaque appel réservant au moins
 * 60 s : réservé aux images demandées une à une, jamais aux montages (le quota sert à animer leurs images).
 * FLUX.2 klein n'est pas utilisé : son Space réserve 95 s sur un GPU « xlarge » (compté double) et refuse plus de 1024 px.
 */
object HfImage {
    data class Space(val nom: String, val base: String, val point: String)

    val SPACES = listOf(
        Space("Z-Image-Turbo (Hugging Face)", "https://tongyi-mai-z-image-turbo.hf.space", "generate"),
        Space("Z-Image-Turbo bis (Hugging Face)", "https://mrfakename-z-image-turbo.hf.space", "generate_image")
    )

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).callTimeout(200, TimeUnit.SECONDS).build()

    /** Format multiple de 16 entre 256 et 1536 (0 → 1024). */
    internal fun cote(v: Int): Int = (if (v <= 0) 1024 else v).coerceIn(256, 1536) / 16 * 16

    /** Résolution proposée par Z-Image-Turbo la plus proche du format voulu (« 720x1280 ( 9:16 ) »). */
    internal fun resolutionZImage(largeur: Int, hauteur: Int): String {
        val r = cote(largeur).toDouble() / cote(hauteur)
        return when {
            r < 0.7 -> "720x1280 ( 9:16 )"
            r < 0.9 -> "896x1152 ( 7:9 )"
            r <= 1.1 -> "1024x1024 ( 1:1 )"
            r <= 1.45 -> "1152x896 ( 9:7 )"
            else -> "1280x720 ( 16:9 )"
        }
    }

    /** Paramètres de l'API Gradio de chaque Space (ordre exact des champs de l'interface). */
    internal fun donnees(space: Space, prompt: String, largeur: Int, hauteur: Int): JSONArray = when {
        space.base.contains("tongyi") -> JSONArray().put(prompt.take(1500)).put(resolutionZImage(largeur, hauteur)).put(42).put(8).put(3.0).put(true).put(JSONArray())
        // Copie communautaire : prompt, hauteur, largeur (1024 px au plus), étapes, graine, graine aléatoire.
        space.base.contains("mrfakename") -> resolutionZImage(largeur, hauteur).substringBefore(' ').split('x').map { it.toInt() }.let { (l, h) ->
            val k = 1024.0 / maxOf(l, h)
            JSONArray().put(prompt.take(1500)).put((h * k).toInt() / 16 * 16).put((l * k).toInt() / 16 * 16).put(9).put(42).put(true)
        }
        // FLUX.2 klein : prompt, images de référence, mode distillé 4 étapes, graine, graine aléatoire, largeur, hauteur, étapes, guidance, réécriture.
        else -> JSONArray().put(prompt.take(1500)).put(JSONArray()).put("Distilled (4 steps)").put(0).put(true)
            .put(cote(largeur)).put(cote(hauteur)).put(4).put(1.0).put(false)
    }

    /** Premier fichier d'une réponse Gradio (objet FileData, galerie, texte) : son adresse complète, ou null. */
    internal fun premierFichier(base: String, v: Any?): String? = when (v) {
        is JSONObject -> v.optString("url").takeIf { it.startsWith("http") }
            ?: v.optString("path").takeIf { it.isNotBlank() && it != "null" }?.let { "$base/gradio_api/file=$it" }
            ?: v.keys().asSequence().firstNotNullOfOrNull { premierFichier(base, v.opt(it)) }
        is JSONArray -> (0 until v.length()).firstNotNullOfOrNull { premierFichier(base, v.opt(it)) }
        is String -> when {
            v.startsWith("http") -> v
            v.startsWith("/tmp/") || v.startsWith("/home/") -> "$base/gradio_api/file=$v"
            else -> null
        }
        else -> null
    }

    /** Le Space refuse pour cause de quota GPU (message ZeroGPU), ou sans détail (souvent la même raison). */
    internal fun quota(erreur: String): Boolean = Regex("(?i)zerogpu|quota|gpu (task|limit)|exceeded").containsMatchIn(erreur)

    class QuotaEpuise(message: String) : Exception(message)
    /** Refus du Space sans aucune explication (« null ») : en pratique, le quota GPU du compte est épuisé. */
    class RefusMuet(message: String) : Exception(message)

    /** « nom (compte gratuit) » d'après la réponse de whoami-v2. */
    internal fun descriptionCompte(j: JSONObject): String =
        "« " + j.optString("name").ifBlank { "?" } + " »" + if (j.optBoolean("isPro")) " (compte PRO)" else " (compte gratuit)"

    /** Vérifie le jeton auprès de Hugging Face : compte associé, ou ApiException 401 si le jeton est refusé. Bloquant. */
    fun compte(token: String): String {
        if (token.isBlank()) throw ApiException(401, "aucun jeton Hugging Face (Réglages › IA › Images & vidéo)")
        val req = Request.Builder().url("https://huggingface.co/api/whoami-v2").header("Authorization", "Bearer ${token.trim()}").build()
        http.newCall(req).execute().use { r ->
            val brut = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw ApiException(r.code, "jeton Hugging Face refusé (invalide, expiré ou supprimé) : recrée-le sur huggingface.co/settings/tokens (type « Read »)")
            if (!r.isSuccessful) throw ApiException(r.code, "Hugging Face ne répond pas (${r.code})")
            return descriptionCompte(JSONObject(brut))
        }
    }

    private val diagnostics = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    /**
     * Explication d'un refus sans détail : jeton refusé, ou quota GPU gratuit du compte épuisé (il se recharge au fil
     * des heures). Vérifié au plus toutes les 10 minutes. Bloquant.
     */
    fun diagnosticRefus(token: String): String {
        diagnostics[token]?.let { (t, m) -> if (m.isNotBlank() && System.currentTimeMillis() - t < 10 * 60_000L) return m }
        val m = try {
            "quota GPU gratuit épuisé pour le compte Hugging Face ${compte(token)} (≈ 3 utilisations et 5 min de GPU par jour) : il revient 24 h après la première utilisation"
        } catch (e: ApiException) { e.message.orEmpty() }
        catch (e: Exception) { "quota GPU gratuit probablement épuisé (vérification du jeton impossible : ${e.message?.take(60)})" }
        diagnostics[token] = System.currentTimeMillis() to m
        return m
    }

    /** Une image sur [space] ; octets (webp ou png). */
    private fun generer(space: Space, token: String, prompt: String, largeur: Int, hauteur: Int): ByteArray {
        fun req(url: String) = Request.Builder().url(url).apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
        val corps = JSONObject().put("data", donnees(space, prompt, largeur, hauteur)).toString().toRequestBody("application/json".toMediaType())
        val eventId = http.newCall(req("${space.base}/gradio_api/call/${space.point}").post(corps).build()).execute().use { r ->
            val brut = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw ApiException(r.code, "jeton Hugging Face refusé")
            if (!r.isSuccessful) throw ApiException(r.code, "${space.nom} indisponible (${r.code})")
            runCatching { JSONObject(brut).getString("event_id") }.getOrNull() ?: throw ApiException(0, "réponse inattendue de ${space.nom}")
        }
        http.newCall(req("${space.base}/gradio_api/call/${space.point}/$eventId").build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "suivi refusé par ${space.nom} (${r.code})")
            val lecteur = r.body?.charStream()?.buffered() ?: throw ApiException(0, "réponse vide de ${space.nom}")
            var evenement = ""
            while (true) {
                val ligne = lecteur.readLine() ?: break
                if (ligne.startsWith("event:")) evenement = ligne.removePrefix("event:").trim()
                else if (ligne.startsWith("data:")) {
                    val donnee = ligne.removePrefix("data:").trim()
                    if (evenement == "error") {
                        val detail = runCatching { JSONObject(donnee).let { it.optString("error").ifBlank { it.optString("title") } } }.getOrDefault("")
                        if (quota(detail)) throw QuotaEpuise("quota GPU gratuit du jour épuisé sur Hugging Face")
                        if (detail.isBlank()) throw RefusMuet("${space.nom} a refusé l'image sans explication")
                        throw ApiException(500, "${space.nom} a refusé l'image : ${detail.take(150)}")
                    }
                    if (evenement == "complete") {
                        val url = premierFichier(space.base, runCatching { JSONArray(donnee) }.getOrNull())
                            ?: throw ApiException(0, "pas d'image dans la réponse de ${space.nom}")
                        return http.newCall(req(url).build()).execute().use { f ->
                            if (!f.isSuccessful) throw ApiException(f.code, "téléchargement de l'image refusé (${f.code})")
                            f.body?.bytes()?.takeIf { it.size > 1_000 } ?: throw ApiException(0, "image vide")
                        }
                    }
                }
            }
        }
        throw ApiException(0, "${space.nom} n'a rien renvoyé (service peut-être saturé)")
    }

    /** Une image gratuite : chaque Space à tour de rôle ; erreur claire si aucun ne répond. Appel bloquant (réseau). */
    fun image(token: String, prompt: String, largeur: Int, hauteur: Int): ByteArray {
        val erreurs = mutableListOf<String>()
        var muets = 0
        for (s in SPACES) {
            try { return generer(s, token, prompt, largeur, hauteur) }
            catch (e: QuotaEpuise) { throw e }
            catch (e: RefusMuet) { muets++; erreurs += e.message.orEmpty() }
            catch (e: ApiException) { if (e.code == 401 || e.code == 403) throw e; erreurs += e.message.orEmpty() }
            catch (e: Exception) { erreurs += "${s.nom} : ${e.message ?: e.javaClass.simpleName}" }
        }
        // Tous les Spaces refusent sans rien dire : jeton refusé ou quota épuisé, vérifié auprès de Hugging Face.
        if (muets == SPACES.size) throw QuotaEpuise(diagnosticRefus(token))
        throw ApiException(0, erreurs.joinToString(" ; ").take(300))
    }
}
