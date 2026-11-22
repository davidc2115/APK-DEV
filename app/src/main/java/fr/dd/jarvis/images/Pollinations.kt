package fr.dd.jarvis.images

import fr.dd.jarvis.ai.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Client Pollinations.ai.
 *  - Avec une clé (enter.pollinations.ai, « sk_… » ou « pk_… ») : passerelle gen.pollinations.ai, tous les modèles
 *    (flux, zimage, turbo, qwen-image, gptimage…) et le texte compatible OpenAI pour la réécriture.
 *  - Sans clé : ancien point d'accès image.pollinations.ai (modèle flux, taille plafonnée, une image à la fois).
 * `safe=false` seulement pour une demande adulte (filtre Pollinations actif sinon), `private=true` (pas de flux public), `nologo=true`.
 */
object Pollinations {
    const val PASSERELLE = "https://gen.pollinations.ai"
    const val ANCIEN = "https://image.pollinations.ai"
    const val TEXTE_SANS_CLE = "https://text.pollinations.ai/openai"
    const val SITE_CLE = "https://enter.pollinations.ai"
    /** Surface max sans clé : 768 × 768 pixels (les proportions sont gardées). */
    const val MAX_SANS_CLE = 768
    /** Sans clé : une image à la fois, au moins 45 s entre deux demandes (par adresse IP). */
    const val ECART_SANS_CLE_MS = 46_000L
    @Volatile private var dernierSansCle = 0L

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(170, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS).build()

    data class ModeleImage(val nom: String, val description: String, val payant: Boolean, val aliases: List<String> = emptyList())

    /** Modèles connus si la liste n'a pas pu être lue. */
    val MODELES_DEFAUT = listOf(
        ModeleImage("flux", "Flux Schnell — polyvalent", false), ModeleImage("zimage", "Z-Image Turbo — photo réaliste", false),
        ModeleImage("klein", "Flux 2 Klein — rapide", false), ModeleImage("flux-2-dev", "Flux 2 Dev — haute qualité", false),
        ModeleImage("gptimage", "GPT Image — consignes complexes (filtré)", false)
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * Taille envoyée, multiple de 16, proportions gardées : côté ≤ 2048 avec clé ; sans clé, surface ≤ 768 × 768
     * (un portrait 832 × 1216 devient 624 × 928 au lieu d'être écrasé à 512 × 768).
     */
    fun taille(l: Int, h: Int, avecCle: Boolean): Pair<Int, Int> {
        val k = if (avecCle) minOf(1.0, 2048.0 / maxOf(l, h, 1))
            else minOf(1.0, kotlin.math.sqrt(MAX_SANS_CLE.toDouble() * MAX_SANS_CLE / (l.toDouble() * h).coerceAtLeast(1.0)))
        fun r(v: Int): Int = ((v * k + 1e-6).toInt() / 16 * 16).coerceAtLeast(256)
        return r(l) to r(h)
    }

    /** [filtre] : demande classique → filtre anti-nudité de Pollinations activé (`safe=true`) ; adulte → `safe=false`. */
    fun urlImage(prompt: String, negatif: String, modele: String, l: Int, h: Int, seed: Long, avecCle: Boolean, filtre: Boolean = false): String {
        // « / », « ? » et « # » cassent le chemin chez certains relais même encodés : remplacés par des espaces.
        val p = enc(prompt.replace(Regex("[/?#\\\\]"), " ").replace(Regex("\\s+"), " ").trim().take(1400))
        val (w, hh) = taille(l, h, avecCle)
        val base = if (avecCle) "$PASSERELLE/image/$p" else "$ANCIEN/prompt/$p"
        return base + "?model=" + enc(modele.ifBlank { "flux" }) + "&width=$w&height=$hh&seed=$seed" +
            "&nologo=true&private=true&enhance=false&safe=$filtre" +
            (if (negatif.isNotBlank()) "&negative_prompt=" + enc(negatif.take(600)) else "")
    }

    /** Lecture tolérante de /image/models : tableau de noms ou d'objets {name, description, paid_only, aliases…}. */
    fun parserModeles(json: String): List<ModeleImage> {
        val a = runCatching { JSONArray(json.trim()) }.getOrNull()
            ?: runCatching { JSONObject(json.trim()).optJSONArray("data") ?: JSONObject(json.trim()).optJSONArray("models") }.getOrNull()
            ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            when (val v = a.opt(i)) {
                is String -> ModeleImage(v, "", false)
                is JSONObject -> {
                    val nom = v.optString("name", v.optString("id", "")).trim()
                    if (nom.isBlank()) return@mapNotNull null
                    val sorties = v.optJSONArray("output_modalities")
                    if (sorties != null && (0 until sorties.length()).none { sorties.optString(it) == "image" }) return@mapNotNull null
                    val aliases = v.optJSONArray("aliases")?.let { al -> (0 until al.length()).map { al.optString(it) }.filter(String::isNotBlank) }.orEmpty()
                    ModeleImage(nom, v.optString("description", "").trim(), v.optBoolean("paid_only", false) || v.optBoolean("paidOnly", false), aliases)
                }
                else -> null
            }
        }.distinctBy { it.nom }
    }

    fun messageErreur(code: Int, corps: String, avecCle: Boolean): String {
        val detail = runCatching { JSONObject(corps).let { j -> j.optJSONObject("error")?.optString("message") ?: j.optString("error", j.optString("message", "")) } }
            .getOrNull().orEmpty().ifBlank { corps.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim() }.take(220)
        return when (code) {
            401 -> if (avecCle) "Clé Pollinations refusée (401) : vérifie-la dans l'onglet Images." else "Pollinations demande une clé (401) : crée-en une gratuite sur enter.pollinations.ai."
            402 -> if (avecCle) "Solde Pollen épuisé (402)" + (if (detail.isNotBlank()) " : $detail" else "") + ". Depuis juillet 2026, " +
                "Pollinations ne recharge plus de Pollen gratuit chaque heure, même pour flux ou zimage : il faut en gagner avec les Quêtes " +
                "ou en acheter sur enter.pollinations.ai, et donner un budget à la clé pour qu'elle puisse dépenser le Pollen de quête."
                else "Mode gratuit : une image toutes les 45 s par adresse IP (402)" + (if (detail.isNotBlank() && !detail.startsWith("{")) " — $detail" else "") + "."
            403 -> "Pollinations a refusé la demande (403)" + (if (detail.isNotBlank()) " : $detail" else ".")
            429 -> "Trop de demandes (429) : Pollinations limite le débit" + (if (avecCle) "." else " sans clé (environ une image toutes les 15-45 s).")
            in 500..599 -> "Pollinations indisponible ($code)" + (if (detail.isNotBlank()) " : $detail" else ".")
            else -> "Pollinations $code" + (if (detail.isNotBlank()) " : $detail" else "")
        }
    }

    /** Signature d'image (JPEG, PNG, WebP) : une page d'erreur servie avec un code 200 n'est pas enregistrée. */
    fun estImage(b: ByteArray): Boolean = b.size > 64 && (
        (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) ||
            (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte()) ||
            (b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte()))

    fun extension(b: ByteArray): String = when {
        b.size > 2 && b[0] == 0x89.toByte() -> "png"
        b.size > 9 && b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte() -> "webp"
        else -> "jpg"
    }

    private fun requete(url: String, cle: String) = Request.Builder().url(url).header("User-Agent", "JARVIS-Android")
        .apply { if (cle.isNotBlank()) header("Authorization", "Bearer $cle") }

    /**
     * Télécharge l'image. Sans clé, respecte l'écart de 45 s (attente annoncée par [attente]) et, si Pollinations
     * répond 402/429 avec Retry-After, patiente puis réessaie (2 fois).
     */
    suspend fun image(cle: String, url: String, attente: (Int) -> Unit = {}): ByteArray = withContext(Dispatchers.IO) {
        val sansCle = cle.isBlank()
        var essai = 0
        var resultat: ByteArray? = null
        while (resultat == null) {
            if (sansCle) {
                val reste = dernierSansCle + ECART_SANS_CLE_MS - System.currentTimeMillis()
                if (reste > 0) { attente(((reste + 999) / 1000).toInt()); delay(reste) }
                dernierSansCle = System.currentTimeMillis()
            }
            val (code, corps, retry) = http.newCall(requete(url, cle).get().build()).execute().use { r ->
                val b = r.body?.bytes() ?: ByteArray(0)
                if (r.isSuccessful && estImage(b)) { resultat = b; return@use Triple(200, "", null) }
                Triple(if (r.isSuccessful) 500 else r.code, String(b.take(400).toByteArray()), r.header("Retry-After")?.toLongOrNull())
            }
            if (resultat != null) break
            val limite = code == 429 || (sansCle && code == 402)
            if (limite && essai < 2) {
                essai++
                val s = (retry ?: 46L).coerceIn(5L, 90L)
                attente(s.toInt()); delay(s * 1000L)
                if (sansCle) dernierSansCle = 0L
                continue
            }
            if (code in 502..504 && essai < 1) { essai++; delay(4000L); continue }
            throw ApiException(code, messageErreur(code, if (code == 500 && corps.isBlank()) "réponse sans image" else corps, !sansCle))
        }
        resultat!!
    }

    suspend fun modelesImage(cle: String): List<ModeleImage> = withContext(Dispatchers.IO) {
        val url = if (cle.isNotBlank()) "$PASSERELLE/image/models" else "$ANCIEN/models"
        http.newCall(requete(url, cle).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, messageErreur(r.code, r.body?.string().orEmpty(), cle.isNotBlank()))
            parserModeles(r.body?.string().orEmpty())
        }
    }

    suspend fun modelesTexte(cle: String): List<String> = withContext(Dispatchers.IO) {
        http.newCall(requete("$PASSERELLE/v1/models", cle).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, messageErreur(r.code, r.body?.string().orEmpty(), cle.isNotBlank()))
            val j = r.body?.string().orEmpty()
            val a = runCatching { JSONObject(j).optJSONArray("data") }.getOrNull() ?: runCatching { JSONArray(j) }.getOrNull() ?: JSONArray()
            (0 until a.length()).mapNotNull { i -> (a.opt(i) as? JSONObject)?.optString("id")?.takeIf(String::isNotBlank) ?: (a.opt(i) as? String) }
        }
    }

    /** Réécriture par un modèle de texte Pollinations (API compatible OpenAI). */
    suspend fun texte(cle: String, modele: String, systeme: String, demande: String): String = withContext(Dispatchers.IO) {
        // Sans clé : un seul modèle (GPT-OSS 20B), le nom envoyé est ignoré ou refusé → on n'en envoie pas.
        val corps = JSONObject().apply { if (cle.isNotBlank()) put("model", modele.ifBlank { "openai" }) }.put("temperature", 0.8).put("max_tokens", 700)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", systeme))
                .put(JSONObject().put("role", "user").put("content", demande)))
        val url = if (cle.isNotBlank()) "$PASSERELLE/v1/chat/completions" else TEXTE_SANS_CLE
        http.newCall(requete(url, cle).post(corps.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            val b = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, messageErreur(r.code, b, cle.isNotBlank()))
            runCatching { JSONObject(b).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "") }
                .getOrDefault(b).trim()
        }
    }
}
