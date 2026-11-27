package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * TikTok (OAuth2, compte personnel uniquement — Display API « Login Kit ») : connexion, rafraîchissement
 * automatique des jetons, profil et liste des vidéos avec leurs statistiques (vues, likes, commentaires, partages).
 *
 * redirect_uri : TikTok exige une URL absolue en https (un schéma personnalisé type fr.dd.jarvis://… est refusé).
 * On passe donc par une petite page relais hébergée sur GitHub Pages (docs/tiktok-callback.html du dépôt
 * APK-DEV), qui redirige immédiatement vers fr.dd.jarvis://tiktok-callback?... — intercepté par MainActivity.
 */
object TikTok {
    const val REDIRECT_URI = "https://davidc2115.github.io/APK-DEV/tiktok-callback.html"

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun configure(app: JarvisApp) = app.store.tiktokClientKey.value.trim().isNotBlank() && app.store.tiktokClientSecret.value.trim().isNotBlank()
    fun connecte(app: JarvisApp) = app.store.tiktokAccessToken.value.trim().isNotBlank()

    /** URL d'autorisation à ouvrir dans le navigateur ; [state] doit être mémorisé pour être vérifié au retour. */
    fun urlAutorisation(app: JarvisApp, state: String): String {
        val key = java.net.URLEncoder.encode(app.store.tiktokClientKey.value.trim(), "UTF-8")
        val redirect = java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8")
        return "https://www.tiktok.com/v2/auth/authorize/?client_key=$key&scope=${scopes(app)}" +
            "&response_type=code&redirect_uri=$redirect&state=$state"
    }

    /**
     * Autorisations demandées : lecture du profil et des vidéos ; + video.upload (envoi dans les brouillons) si la
     * publication est activée dans les réglages — ce scope doit d'abord être ajouté à l'appli sur developers.tiktok.com,
     * sinon TikTok refuse toute la connexion.
     */
    fun scopes(app: JarvisApp) = "user.info.basic,video.list" + if (app.store.tiktokPublication.value) ",video.upload" else ""

    /** Le compte connecté a autorisé l'envoi en brouillon. */
    fun envoiAutorise(app: JarvisApp) = connecte(app) && app.store.tiktokScopes.value.split(',', ' ').any { it.trim() == "video.upload" }

    /** Échange le code d'autorisation (reçu sur le lien de retour) contre les jetons, et les enregistre. */
    suspend fun echangerCode(app: JarvisApp, code: String): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_key", app.store.tiktokClientKey.value.trim())
            .add("client_secret", app.store.tiktokClientSecret.value.trim())
            .add("code", code)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", REDIRECT_URI)
            .build()
        val req = Request.Builder().url("https://open.tiktokapis.com/v2/oauth/token/")
            .header("Content-Type", "application/x-www-form-urlencoded").header("Cache-Control", "no-cache")
            .post(body).build()
        http.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            val j = runCatching { JSONObject(t) }.getOrNull()
            if (!r.isSuccessful || j == null || !j.has("access_token")) {
                return@withContext "❌ Connexion TikTok refusée : " + (j?.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: t.take(250))
            }
            sauvegarder(app, j)
            "✅ Compte TikTok connecté."
        }
    }

    private fun sauvegarder(app: JarvisApp, j: JSONObject) {
        app.store.tiktokAccessToken.set(j.optString("access_token"))
        if (j.has("refresh_token")) app.store.tiktokRefreshToken.set(j.optString("refresh_token"))
        app.store.tiktokOpenId.set(j.optString("open_id"))
        if (j.has("scope")) app.store.tiktokScopes.set(j.optString("scope"))
        val expiry = System.currentTimeMillis() / 1000 + j.optLong("expires_in", 86400)
        app.store.tiktokTokenExpiry.set(expiry.toString())
    }

    private fun rafraichir(app: JarvisApp): Boolean {
        val rt = app.store.tiktokRefreshToken.value.trim()
        if (rt.isBlank()) return false
        val body = FormBody.Builder()
            .add("client_key", app.store.tiktokClientKey.value.trim())
            .add("client_secret", app.store.tiktokClientSecret.value.trim())
            .add("grant_type", "refresh_token")
            .add("refresh_token", rt)
            .build()
        val req = Request.Builder().url("https://open.tiktokapis.com/v2/oauth/token/")
            .header("Content-Type", "application/x-www-form-urlencoded").header("Cache-Control", "no-cache")
            .post(body).build()
        return http.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            val j = runCatching { JSONObject(t) }.getOrNull()
            if (!r.isSuccessful || j == null || !j.has("access_token")) return@use false
            sauvegarder(app, j)
            true
        }
    }

    private fun assurerJeton(app: JarvisApp): Boolean {
        if (!connecte(app)) return false
        val expiry = app.store.tiktokTokenExpiry.value.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() / 1000 < expiry - 60) return true
        return rafraichir(app)
    }

    private fun appel(app: JarvisApp, url: String, corps: JSONObject? = null): JSONObject {
        if (!assurerJeton(app)) throw IllegalStateException("Compte TikTok non connecté (ou jeton expiré/révoqué) : utilise tiktok_connexion.")
        val token = app.store.tiktokAccessToken.value.trim()
        val b = Request.Builder().url(url).header("Authorization", "Bearer $token")
        val req = if (corps != null) b.header("Content-Type", "application/json").post(corps.toString().toRequestBody("application/json".toMediaType())).build() else b.build()
        http.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            val err = runCatching { JSONObject(t).optJSONObject("error") }.getOrNull()
            if (err != null && err.optString("code") != "ok") throw IllegalStateException("TikTok : ${err.optString("message").ifBlank { err.optString("code") }}")
            if (!r.isSuccessful) throw IllegalStateException("TikTok ${r.code} : ${t.take(300)}")
            return JSONObject(t)
        }
    }

    suspend fun monProfil(app: JarvisApp): JSONObject = withContext(Dispatchers.IO) {
        appel(app, "https://open.tiktokapis.com/v2/user/info/?fields=open_id,display_name,avatar_url,follower_count,following_count,likes_count,video_count")
            .optJSONObject("data")?.optJSONObject("user") ?: JSONObject()
    }

    /** Vidéos du compte connecté, avec leurs statistiques (20 par appel au plus — max_count TikTok —, pages suivies jusqu'à [max]). */
    suspend fun mesVideos(app: JarvisApp, max: Int = 20): JSONArray = withContext(Dispatchers.IO) {
        val champs = "id,title,video_description,duration,cover_image_url,share_url,view_count,like_count,comment_count,share_count,create_time"
        val tout = JSONArray()
        var curseur: Long? = null
        while (tout.length() < max.coerceIn(1, 100)) {
            val corps = JSONObject().put("max_count", minOf(20, max - tout.length()).coerceAtLeast(1))
            if (curseur != null) corps.put("cursor", curseur)
            val d = appel(app, "https://open.tiktokapis.com/v2/video/list/?fields=$champs", corps).optJSONObject("data") ?: break
            val lot = d.optJSONArray("videos") ?: break
            for (i in 0 until lot.length()) tout.put(lot.get(i))
            if (!d.optBoolean("has_more", false) || lot.length() == 0) break
            curseur = d.optLong("cursor")
        }
        tout
    }

    /**
     * Envoie [fichier] dans la boîte de réception TikTok du compte (brouillon) : l'utilisateur touche la notification
     * TikTok, ajoute le son et le texte, puis publie. Envoi en un morceau jusqu'à 64 Mo, sinon par morceaux de 10 Mo
     * (le dernier prend le reste, règles de TikTok). Renvoie le message à afficher.
     */
    suspend fun envoyerBrouillon(app: JarvisApp, fichier: java.io.File): String = withContext(Dispatchers.IO) {
        if (!envoiAutorise(app)) return@withContext "❌ L'envoi en brouillon n'est pas autorisé pour ce compte : active « Envoyer dans mes brouillons TikTok » dans " +
            "Réglages › Comptes › TikTok (après avoir ajouté le produit Content Posting API et le scope video.upload à ton appli Jarvis21), puis reconnecte le compte."
        val taille = fichier.length()
        if (taille <= 0) return@withContext "❌ Fichier vidéo vide."
        val (morceau, nombre) = decoupage(taille)
        val init = JSONObject().put("source_info", JSONObject().put("source", "FILE_UPLOAD").put("video_size", taille)
            .put("chunk_size", morceau).put("total_chunk_count", nombre))
        val d = appel(app, "https://open.tiktokapis.com/v2/post/publish/inbox/video/init/", init).optJSONObject("data")
            ?: return@withContext "❌ TikTok n'a pas accepté l'envoi (réponse sans identifiant)."
        val publishId = d.optString("publish_id"); val uploadUrl = d.optString("upload_url")
        if (publishId.isBlank() || uploadUrl.isBlank()) return@withContext "❌ TikTok n'a pas renvoyé d'adresse d'envoi."
        val envoi = okhttp3.OkHttpClient.Builder().connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(5, java.util.concurrent.TimeUnit.MINUTES).readTimeout(2, java.util.concurrent.TimeUnit.MINUTES).build()
        java.io.RandomAccessFile(fichier, "r").use { f ->
            for (k in 0 until nombre) {
                val debut = k.toLong() * morceau
                val fin = if (k == nombre - 1) taille - 1 else debut + morceau - 1
                val octets = ByteArray((fin - debut + 1).toInt())
                f.seek(debut); f.readFully(octets)
                val req = Request.Builder().url(uploadUrl).header("Content-Type", "video/mp4")
                    .header("Content-Range", "bytes $debut-$fin/$taille")
                    .put(octets.toRequestBody("video/mp4".toMediaType())).build()
                envoi.newCall(req).execute().use { r ->
                    if (!r.isSuccessful && r.code != 206) return@withContext "❌ Envoi du morceau ${k + 1}/$nombre refusé par TikTok (${r.code}) : ${r.body?.string().orEmpty().take(200)}"
                }
            }
        }
        // Suivi : TikTok traite la vidéo puis la dépose dans la boîte de réception (SEND_TO_USER_INBOX).
        var statut = ""
        repeat(20) {
            kotlinx.coroutines.delay(3000)
            val st = runCatching { appel(app, "https://open.tiktokapis.com/v2/post/publish/status/fetch/", JSONObject().put("publish_id", publishId)).optJSONObject("data") }.getOrNull()
            statut = st?.optString("status").orEmpty()
            when (statut) {
                "SEND_TO_USER_INBOX", "PUBLISH_COMPLETE" -> return@withContext "📥 Vidéo envoyée dans tes brouillons TikTok : touche la notification TikTok pour ajouter le son tendance et la publier."
                "FAILED" -> return@withContext "❌ TikTok a refusé la vidéo : ${st?.optString("fail_reason").orEmpty().ifBlank { "raison non précisée" }}"
            }
        }
        "⏳ Vidéo envoyée à TikTok, encore en traitement (${statut.ifBlank { "statut inconnu" }}) : elle arrivera dans tes brouillons d'ici quelques minutes."
    }

    /** Taille de morceau et nombre de morceaux selon les règles TikTok (5 à 64 Mo ; le dernier morceau absorbe le reste). */
    internal fun decoupage(taille: Long): Pair<Long, Int> {
        val max = 64L * 1024 * 1024
        if (taille <= max) return taille to 1
        val morceau = 10L * 1024 * 1024
        return morceau to (taille / morceau).toInt().coerceAtLeast(1)
    }

    fun deconnecter(app: JarvisApp) {
        app.store.tiktokAccessToken.set(""); app.store.tiktokRefreshToken.set("")
        app.store.tiktokOpenId.set(""); app.store.tiktokTokenExpiry.set("0"); app.store.tiktokScopes.set("")
    }
}
