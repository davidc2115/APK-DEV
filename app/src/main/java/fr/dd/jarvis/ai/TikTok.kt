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
        return "https://www.tiktok.com/v2/auth/authorize/?client_key=$key&scope=user.info.basic,video.list" +
            "&response_type=code&redirect_uri=$redirect&state=$state"
    }

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

    /** Vidéos du compte connecté, avec leurs statistiques (jusqu'à 20 par appel — max_count TikTok). */
    suspend fun mesVideos(app: JarvisApp, max: Int = 20): JSONArray = withContext(Dispatchers.IO) {
        val champs = "id,title,video_description,duration,cover_image_url,share_url,view_count,like_count,comment_count,share_count,create_time"
        val corps = JSONObject().put("max_count", max.coerceIn(1, 20))
        appel(app, "https://open.tiktokapis.com/v2/video/list/?fields=$champs", corps)
            .optJSONObject("data")?.optJSONArray("videos") ?: JSONArray()
    }

    fun deconnecter(app: JarvisApp) {
        app.store.tiktokAccessToken.set(""); app.store.tiktokRefreshToken.set("")
        app.store.tiktokOpenId.set(""); app.store.tiktokTokenExpiry.set("0")
    }
}
