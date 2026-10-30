package fr.dd.jarvis.ai

import android.util.Base64
import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Génération d'images avec Cloudflare Workers AI ; plusieurs comptes (« idCompte:jeton ») utilisés à tour de rôle. */
object ImageGen {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(100, TimeUnit.SECONDS).build()
    private var next = 0
    private val cooldown = mutableMapOf<String, Long>()
    val MODELES = listOf("@cf/black-forest-labs/flux-1-schnell", "@cf/stabilityai/stable-diffusion-xl-base-1.0", "@cf/bytedance/stable-diffusion-xl-lightning", "@cf/lykon/dreamshaper-8-lcm")

    private fun genere(account: String, model: String, prompt: String): ByteArray {
        val (id, token) = account.split(":", limit = 2)
        val body = JSONObject().put("prompt", prompt).apply { if (model.contains("flux")) put("steps", 6) }
        val req = Request.Builder().url("https://api.cloudflare.com/client/v4/accounts/$id/ai/run/$model")
            .header("Authorization", "Bearer $token").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val ct = r.header("Content-Type").orEmpty()
            if (!r.isSuccessful) {
                val brut = r.body?.string().orEmpty().take(200)
                throw ApiException(r.code, if (r.code == 401 || r.code == 403) diagnostic(id, token, r.code, brut) else "Cloudflare ${r.code} : $brut")
            }
            val bytes = r.body!!.bytes()
            if (ct.startsWith("image/")) return bytes
            val j = JSONObject(String(bytes))
            val b64 = j.optJSONObject("result")?.optString("image").orEmpty()
            if (b64.isBlank()) throw ApiException(500, "Réponse sans image : " + String(bytes).take(160))
            return Base64.decode(b64, Base64.DEFAULT)
        }
    }

    /** Explique pourquoi Cloudflare refuse : mauvais type de clé, jeton sans droit Workers AI ou mauvais compte. */
    private fun diagnostic(id: String, token: String, code: Int, brut: String): String {
        val entete = "Cloudflare $code (authentification refusée). "
        return try {
            val v = Request.Builder().url("https://api.cloudflare.com/client/v4/user/tokens/verify").header("Authorization", "Bearer $token").build()
            http.newCall(v).execute().use { r ->
                val ok = runCatching { JSONObject(r.body?.string().orEmpty()).optBoolean("success") }.getOrDefault(false)
                when {
                    !ok && Regex("[0-9a-f]{37}").matches(token.lowercase()) ->
                        entete + "Cette clé est une « Global API Key » (37 caractères), pas un jeton. Crée un jeton : dash.cloudflare.com › Mon profil › Jetons API › Créer un jeton › modèle « Workers AI », puis colle-le ici."
                    !ok -> entete + "Ce n'est pas un jeton API valide (copié en entier ? sans espace ?). Crée-en un : Mon profil › Jetons API › Créer un jeton › modèle « Workers AI »."
                    else -> entete + "Le jeton est valide mais n'a pas accès à ce compte ou à Workers AI : vérifie l'ID de compte (${id.take(6)}…) et que le jeton a la permission « Workers AI » (Lecture/Édition) sur ce compte. Si tu viens de créer le compte, ouvre une fois dash.cloudflare.com › AI › Workers AI pour l'activer."
                }
            }
        } catch (e: Exception) { "Cloudflare $code : $brut" }
    }

    /** Génère une image, la garde dans l'appli et dans Obsidian. Retourne (fichier, emplacement Obsidian). */
    suspend fun generate(app: JarvisApp, prompt: String): Pair<File, String> = withContext(Dispatchers.IO) {
        val list = app.store.cfAccounts.filter { it.contains(':') }
        if (list.isEmpty()) throw IllegalStateException("Aucun compte Cloudflare : ajoute « idCompte:jeton » dans Réglages › Images.")
        val now = System.currentTimeMillis()
        val order = list.indices.map { (next + it) % list.size }
        val (ready, cooling) = order.partition { (cooldown[list[it]] ?: 0L) <= now }
        var last: Exception? = null
        for (i in ready + cooling) {
            try {
                val bytes = genere(list[i], app.store.cfModel.value.ifBlank { MODELES[0] }, prompt)
                next = (i + 1) % list.size
                val name = "img_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.FRENCH).format(java.util.Date()) + ".png"
                val f = File(File(app.filesDir, "images").apply { mkdirs() }, name)
                f.writeBytes(bytes)
                val loc = runCatching { app.obsidian.saveBytes("JARVIS/Images", name, bytes, "image/png") }.getOrDefault("")
                return@withContext f to loc
            } catch (e: ApiException) {
                last = e
                cooldown[list[i]] = System.currentTimeMillis() + when (e.code) { 429 -> 60_000L; 401, 403 -> 600_000L; else -> 15_000L }
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("Génération impossible.")
    }
}
