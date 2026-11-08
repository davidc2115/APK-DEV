package fr.dd.jarvis.ai

import android.util.Base64
import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
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
    // flux-2-dev : rendu réaliste haute qualité (plus lent). flux-2-klein-9b : qualité proche, plus rapide/léger. flux-1-schnell : le plus rapide.
    val MODELES = listOf(
        "@cf/black-forest-labs/flux-1-schnell",
        "@cf/black-forest-labs/flux-2-dev",
        "@cf/black-forest-labs/flux-2-klein-9b",
        "@cf/stabilityai/stable-diffusion-xl-base-1.0",
        "@cf/bytedance/stable-diffusion-xl-lightning",
        "@cf/lykon/dreamshaper-8-lcm"
    )

    private fun genere(account: String, model: String, prompt: String): ByteArray {
        val (id, token) = account.split(":", limit = 2)
        // Les modèles flux-2-* (flux-2-dev, flux-2-klein) attendent du multipart/form-data, pas du JSON.
        val req = if (model.contains("flux-2")) {
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("steps", "28")
                .build()
            Request.Builder().url("https://api.cloudflare.com/client/v4/accounts/$id/ai/run/$model")
                .header("Authorization", "Bearer $token").post(form).build()
        } else {
            val body = JSONObject().put("prompt", prompt).apply { if (model.contains("flux")) put("steps", 6) }
            Request.Builder().url("https://api.cloudflare.com/client/v4/accounts/$id/ai/run/$model")
                .header("Authorization", "Bearer $token").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        }
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

    /** Alias pratiques pour le paramètre « modele » de l'outil generer_image. */
    private fun alias(modele: String): String = when (modele.trim().lowercase()) {
        "flux", "flux2", "flux-2", "flux-2-dev", "flux dev" -> "@cf/black-forest-labs/flux-2-dev"
        "flux-klein", "flux klein", "flux2klein", "flux-2-klein", "klein" -> "@cf/black-forest-labs/flux-2-klein-9b"
        "flux-schnell", "flux schnell", "schnell", "flux-1", "flux1" -> "@cf/black-forest-labs/flux-1-schnell"
        else -> modele
    }

    /** Génère une image, la garde dans l'appli et dans Obsidian. Retourne (fichier, emplacement Obsidian). */
    suspend fun generate(app: JarvisApp, prompt: String, modele: String = ""): Pair<File, String> = withContext(Dispatchers.IO) {
        val list = app.store.cfAccounts.filter { it.contains(':') }
        if (list.isEmpty()) throw IllegalStateException("Aucun compte Cloudflare : ajoute « idCompte:jeton » dans Réglages › IA › Images & vidéo.")
        val model = modele.trim().let { if (it.isBlank()) app.store.cfModel.value.ifBlank { MODELES[0] } else alias(it) }
        val now = System.currentTimeMillis()
        val order = list.indices.map { (next + it) % list.size }
        val (ready, cooling) = order.partition { (cooldown[list[it]] ?: 0L) <= now }
        var last: Exception? = null
        for (i in ready + cooling) {
            try {
                val bytes = genere(list[i], model, prompt)
                next = (i + 1) % list.size
                // Nom daté + quelques mots de la description : l'image se retrouve aussi par son sujet (dans Obsidian comme par JARVIS).
                val sujet = java.text.Normalizer.normalize(prompt.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                    .replace(Regex("[^a-z0-9]+"), " ").trim().split(' ').filter { it.length > 2 && it !in setOf("the", "and", "with", "for", "style", "detailed", "high", "quality") }
                    .take(4).joinToString("-").take(40)
                val name = "img_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.FRENCH).format(java.util.Date()) +
                    (if (sujet.isNotBlank()) "_$sujet" else "") + "_" + (100 + (Math.random() * 899).toInt()) + ".png"
                val dossierImages = File(app.filesDir, "images").apply { mkdirs() }
                val f = File(dossierImages, name)
                f.writeBytes(bytes)
                memoriserDescription(dossierImages, name, prompt, bytes)
                val loc = app.obsidian.saveBytes("JARVIS/Images", name, bytes, "image/png")
                return@withContext f to loc
            } catch (e: ApiException) {
                last = e
                cooldown[list[i]] = System.currentTimeMillis() + when (e.code) { 429 -> 60_000L; 401, 403 -> 600_000L; else -> 15_000L }
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("Génération impossible.")
    }

    /** Garde la description (prompt) de chaque image générée, pour la retrouver plus tard par son sujet. */
    @Synchronized
    private fun memoriserDescription(dossier: File, nom: String, prompt: String, bytes: ByteArray) {
        runCatching {
            // À côté du dossier des images (et non dedans) : il ne doit jamais passer pour une image.
            val f = File(dossier.parentFile, "images_descriptions.json")
            val j = if (f.isFile) JSONObject(f.readText()) else JSONObject()
            j.put(nom, prompt.take(300))
            // Identifiant fondé sur le contenu : la description survit à un renommage ou déplacement dans Obsidian.
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            j.put("sha256:$hash", prompt.take(300))
            f.writeText(j.toString())
        }
    }

    /** Retrouve une image déjà générée par son nom de fichier (ou « derniere »/« dernier » pour la plus récente). */
    fun fichier(app: JarvisApp, nom: String): File? {
        val dossier = File(app.filesDir, "images")
        val n = nom.trim().removePrefix("jarvis-img:").trim()
        if (n.isBlank() || n.lowercase() in setOf("derniere", "dernière", "dernier", "last", "recente", "récente")) {
            return dossier.listFiles()?.filter { it.isFile && !it.name.startsWith("ecran_") }?.maxByOrNull { it.lastModified() }
        }
        return File(dossier, n).takeIf { it.exists() }
    }

    /** Génère plusieurs images d'affilée (une par prompt) ; utile pour un livre de coloriage, une planche, etc. Une erreur sur un prompt n'arrête pas les autres. */
    suspend fun generateMany(app: JarvisApp, prompts: List<String>, modele: String = ""): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        for (p in prompts) {
            runCatching { generate(app, p, modele) }.onSuccess { (f, _) -> out.add(p to f) }
        }
        return out
    }
}
