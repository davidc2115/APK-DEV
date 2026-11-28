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

/**
 * Génération d'images avec Cloudflare Workers AI ; plusieurs comptes (« idCompte:jeton ») utilisés à tour de rôle.
 * Quota gratuit : 10 000 « neurones » par compte et par jour (remis à zéro à minuit UTC). Coût d'une image verticale
 * 768 × 1344 : ≈ 100 à 160 neurones avec flux-2-klein-4b, ≈ 1 400 avec flux-2-klein-9b (7 images par jour seulement),
 * ≈ 60 avec flux-1-schnell (carré). Quand tous les comptes ont épuisé leur quota, les images sont faites gratuitement
 * sur Hugging Face ([HfImage]) avec le jeton Hugging Face.
 */
object ImageGen {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(100, TimeUnit.SECONDS).build()
    private var next = 0
    private val cooldown = mutableMapOf<String, Long>()
    /** Comptes dont le quota gratuit du jour est épuisé, jusqu'à la remise à zéro (minuit UTC). */
    private val epuises = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // flux-2-dev : rendu réaliste haute qualité (plus lent, très coûteux en quota). flux-2-klein-4b : net, rapide et
    // économe (images des montages). flux-2-klein-9b : un peu plus fin mais 10 fois plus coûteux. flux-1-schnell : le plus sobre.
    val MODELES = listOf(
        "@cf/black-forest-labs/flux-1-schnell",
        "@cf/black-forest-labs/flux-2-dev",
        "@cf/black-forest-labs/flux-2-klein-4b",
        "@cf/black-forest-labs/flux-2-klein-9b",
        "@cf/stabilityai/stable-diffusion-xl-base-1.0",
        "@cf/bytedance/stable-diffusion-xl-lightning",
        "@cf/lykon/dreamshaper-8-lcm"
    )

    /** [references] : images modèles (moins de 512 px) pour FLUX.2 (même sujet, mêmes couleurs, même ambiance), 4 au plus. */
    private fun genere(account: String, model: String, prompt: String, largeur: Int = 0, hauteur: Int = 0, references: List<File> = emptyList()): ByteArray {
        val (id, token) = account.split(":", limit = 2)
        // Les modèles flux-2-* (flux-2-dev, flux-2-klein) attendent du multipart/form-data, pas du JSON.
        val req = if (model.contains("flux-2")) {
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("prompt", prompt)
                // klein : 4 étapes fixes (modèle distillé) ; seul flux-2-dev accepte un nombre d'étapes (facturé à l'étape).
                .apply { if (model.contains("flux-2-dev")) addFormDataPart("steps", "28") }
                .apply { if (largeur > 0 && hauteur > 0) { addFormDataPart("width", "$largeur"); addFormDataPart("height", "$hauteur") } }
                .apply {
                    references.filter { it.isFile }.take(4).forEachIndexed { k, f ->
                        addFormDataPart("input_image_$k", f.name, f.readBytes().toRequestBody("image/jpeg".toMediaType()))
                    }
                }
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
        "flux-klein-4b", "flux-2-klein-4b", "klein-4b", "klein 4b" -> "@cf/black-forest-labs/flux-2-klein-4b"
        "flux-klein", "flux klein", "flux2klein", "flux-2-klein", "klein", "flux-2-klein-9b", "klein-9b" -> "@cf/black-forest-labs/flux-2-klein-9b"
        "flux-schnell", "flux schnell", "schnell", "flux-1", "flux1" -> "@cf/black-forest-labs/flux-1-schnell"
        else -> modele
    }

    /** Refus « you have used up your daily free allocation of 10,000 neurons » (code 4006) : quota du jour épuisé. */
    internal fun quotaJour(code: Int, message: String): Boolean =
        code == 429 && Regex("(?i)daily free allocation|neurons|\\b4006\\b").containsMatchIn(message)

    /** Remise à zéro du quota Cloudflare : minuit UTC suivant [maintenant]. */
    internal fun minuitUtc(maintenant: Long): Long = (maintenant / 86_400_000L + 1) * 86_400_000L

    private fun comptes(app: JarvisApp) = app.store.cfAccounts.filter { it.contains(':') }

    /** Une image peut être faite : compte Cloudflare ou jeton Hugging Face (secours gratuit). */
    fun disponible(app: JarvisApp) = comptes(app).isNotEmpty() || HfJetons.configure(app)

    /** Génère une image, la garde dans l'appli et dans Obsidian. Retourne (fichier, emplacement Obsidian). */
    /** [largeur] × [hauteur] : format demandé (modèles FLUX.2 seulement, ex. 768 × 1344 pour un TikTok vertical). */
    /** [huggingFace] = false : pas de secours sur Hugging Face (son petit quota gratuit est gardé pour animer les images). */
    suspend fun generate(app: JarvisApp, prompt: String, modele: String = "", largeur: Int = 0, hauteur: Int = 0, huggingFace: Boolean = true,
                         references: List<File> = emptyList()): Pair<File, String> = withContext(Dispatchers.IO) {
        val list = comptes(app)
        val hfOk = HfJetons.configure(app)
        if (list.isEmpty() && !hfOk) throw IllegalStateException("Aucun compte Cloudflare : ajoute « idCompte:jeton » dans Réglages › IA › Images & vidéo (ou ton jeton Hugging Face gratuit).")
        val model = modele.trim().let { if (it.isBlank()) app.store.cfModel.value.ifBlank { MODELES[0] } else alias(it) }
        val now = System.currentTimeMillis()
        val actifs = list.indices.map { (next + it) % list.size }.filter { (epuises[list[it]] ?: 0L) <= now }
        val (ready, cooling) = actifs.partition { (cooldown[list[it]] ?: 0L) <= now }
        var last: Exception? = null
        for (i in ready + cooling) {
            val compte = list[i]
            try {
                val bytes = try { genere(compte, model, prompt, largeur, hauteur, references) } catch (e: ApiException) {
                    if (e.code !in setOf(400, 404, 413, 422)) throw e
                    // Images modèles refusées (format, taille) : même modèle sans elles ; modèle refusé : le modèle de base.
                    if (references.isNotEmpty() && model.contains("flux-2")) runCatching { genere(compte, model, prompt, largeur, hauteur) }
                        .getOrElse { if (model != MODELES[0]) genere(compte, MODELES[0], prompt) else throw e }
                    else if (model != MODELES[0]) genere(compte, MODELES[0], prompt) else throw e
                }
                next = (i + 1) % list.size
                return@withContext enregistrer(app, prompt, bytes)
            } catch (e: ApiException) {
                last = e
                if (quotaJour(e.code, e.message.orEmpty())) epuises[compte] = minuitUtc(System.currentTimeMillis())
                else cooldown[compte] = System.currentTimeMillis() + when (e.code) { 429 -> 60_000L; 401, 403 -> 600_000L; else -> 15_000L }
            } catch (e: Exception) { last = e }
        }
        // Cloudflare impossible (quota du jour épuisé, panne) : image gratuite sur Hugging Face.
        val tousEpuises = list.isNotEmpty() && list.all { (epuises[it] ?: 0L) > System.currentTimeMillis() }
        val raisonCf = when {
            list.isEmpty() -> "aucun compte Cloudflare"
            tousEpuises -> "quota gratuit Cloudflare du jour épuisé (10 000 neurones par compte, remis à zéro à " +
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRENCH).format(java.util.Date(minuitUtc(System.currentTimeMillis()))) + ")"
            else -> last?.message?.take(160) ?: "Cloudflare indisponible"
        }
        if (!huggingFace) throw IllegalStateException(raisonCf)
        if (!hfOk) throw IllegalStateException("$raisonCf — ajoute ton jeton Hugging Face gratuit (Réglages › IA › Images & vidéo) pour continuer les images sur Hugging Face.")
        val octets = try { HfJetons.avec(app) { t -> HfImage.image(t, prompt, largeur, hauteur) } } catch (e: Exception) {
            throw IllegalStateException("$raisonCf ; Hugging Face : ${e.message?.take(160)}")
        }
        enregistrer(app, prompt, enPng(octets))
    }

    /** Copie réduite de [src] (moins de 512 px de côté) pour servir d'image modèle à FLUX.2 ; null si illisible. */
    fun reference(src: File, dest: File): File? = runCatching {
        val b = android.graphics.BitmapFactory.decodeFile(src.absolutePath) ?: return@runCatching null
        val (l, h) = TikTokAuto.tailleReference(b.width, b.height)
        val r = android.graphics.Bitmap.createScaledBitmap(b, l, h, true)
        dest.outputStream().use { r.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        if (r !== b) r.recycle()
        b.recycle()
        dest.takeIf { it.length() > 1_000 }
    }.getOrNull()

    /** Hugging Face renvoie du WebP : converti en PNG (format attendu partout dans l'appli et dans Obsidian). */
    private fun enPng(octets: ByteArray): ByteArray = runCatching {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(octets, 0, octets.size) ?: return@runCatching octets
        java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray()
    }.getOrDefault(octets)

    /** Garde l'image dans l'appli et dans Obsidian, sous un nom daté qui reprend son sujet. */
    private fun enregistrer(app: JarvisApp, prompt: String, bytes: ByteArray): Pair<File, String> {
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
        return f to loc
    }

    // ------------------------------------------------------------------ banque des images déjà générées

    private val MOTS_VIDES = setOf("vertical", "portrait", "composition", "cinematic", "lighting", "highly", "detailed", "text", "letters",
        "watermark", "with", "from", "into", "over", "under", "above", "that", "this", "their", "the", "and", "epic", "style", "quality",
        "high", "horizontal", "shot", "scene", "view", "background", "very", "dramatic", "image", "photo", "realistic", "ultra", "sharp",
        "focus", "camera", "angle", "close", "wide", "beautiful", "majestic", "stunning", "glowing", "light", "colors", "colours")

    /** Mots importants d'une description (anglais) : sans accents, sans mots de style, au singulier. */
    internal fun motsImportants(t: String): Set<String> =
        java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .split(Regex("[^a-z0-9]+")).filter { it.length >= 4 && it !in MOTS_VIDES }
            .map { if (it.length > 4 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }.toSet()

    /** Mots importants communs entre ce qu'on cherche et la description d'une image déjà faite. */
    internal fun score(requete: String, description: String): Int = motsImportants(requete).intersect(motsImportants(description)).size

    /**
     * Images IA déjà générées par JARVIS dont la description ressemble à [requete] (au moins [minimum] mots importants
     * communs), les plus proches puis les plus récentes d'abord : le secours gratuit et SUR LE SUJET quand le quota du
     * jour est épuisé (mieux qu'une photo libre de droits au hasard).
     */
    fun banque(app: JarvisApp, requete: String, exclues: Set<String>, minimum: Int = 2): List<File> = runCatching {
        val dossier = File(app.filesDir, "images")
        val f = File(app.filesDir, "images_descriptions.json")
        if (!f.isFile) return@runCatching emptyList<File>()
        val j = JSONObject(f.readText())
        j.keys().asSequence().filter { !it.startsWith("sha256:") && it !in exclues }
            .mapNotNull { nom -> File(dossier, nom).takeIf { it.isFile && it.length() > 10_000 }?.let { it to score(requete, j.optString(nom)) } }
            .filter { it.second >= minimum }
            .sortedWith(compareByDescending<Pair<File, Int>> { it.second }.thenByDescending { it.first.lastModified() })
            .map { it.first }.toList()
    }.getOrDefault(emptyList())

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
        // Sinon : image jointe à la conversation (bouton +) ou importée dans la bibliothèque.
        return File(dossier, n).takeIf { it.exists() }
            ?: DocumentStore.find(app, n)?.takeIf { it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "bmp", "heic", "heif", "gif") }
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
