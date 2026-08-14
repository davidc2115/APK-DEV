package com.jarvis.ai.core.freebox

import com.jarvis.ai.data.settings.SettingsDataStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.InvalidKeyException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authentification Freebox OS : première connexion = demande d'un app_token (l'utilisateur
 * doit valider manuellement sur l'écran de la Freebox), puis chaque session s'ouvre via un
 * challenge signé en HMAC-SHA1 avec le track_id/app_token. Le app_token est stocké chiffré
 * dans les réglages une fois obtenu — jamais redemandé ensuite.
 */
@Singleton
class FreeboxAuth @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsDataStore
) {
    /** Étape 1 (une seule fois) : demander l'autorisation, à valider physiquement sur la box. */
    suspend fun requestAppToken(baseUrl: String): String {
        val body = JSONObject()
            .put("app_id", "com.jarvis.ai")
            .put("app_name", "Jarvis")
            .put("app_version", "0.1")
            .put("device_name", "Jarvis Mobile")
        val request = Request.Builder()
            .url("$baseUrl/api/v8/login/authorize/")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            val result = json.getJSONObject("result")
            settings.setFreeboxTrackId(result.getInt("track_id").toString())
            return result.getString("app_token") // à stocker chiffré via settings une fois validé sur la box
        }
    }

    /** Étape 2 : ouvrir une session avec le challenge fourni par la box (signé HMAC-SHA1). */
    suspend fun openSession(baseUrl: String): String {
        val challengeRequest = Request.Builder()
            .url("$baseUrl/api/v8/login/")
            .get().build()
        val challenge = client.newCall(challengeRequest).execute().use { resp ->
            JSONObject(resp.body?.string().orEmpty()).getJSONObject("result").getString("challenge")
        }
        val appToken = settings.getApiKey("freebox_app_token")
        val password = hmacSha1(appToken, challenge)

        val body = JSONObject().put("app_id", "com.jarvis.ai").put("password", password)
        val sessionRequest = Request.Builder()
            .url("$baseUrl/api/v8/login/session/")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(sessionRequest).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            return json.getJSONObject("result").getString("session_token")
        }
    }

    private fun hmacSha1(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        try {
            mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA1"))
        } catch (e: InvalidKeyException) {
            error("Clé Freebox invalide")
        }
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
