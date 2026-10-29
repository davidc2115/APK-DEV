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
            if (!r.isSuccessful) throw ApiException(r.code, "Cloudflare ${r.code} : " + (r.body?.string().orEmpty().take(200)))
            val bytes = r.body!!.bytes()
            if (ct.startsWith("image/")) return bytes
            val j = JSONObject(String(bytes))
            val b64 = j.optJSONObject("result")?.optString("image").orEmpty()
            if (b64.isBlank()) throw ApiException(500, "Réponse sans image : " + String(bytes).take(160))
            return Base64.decode(b64, Base64.DEFAULT)
        }
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
