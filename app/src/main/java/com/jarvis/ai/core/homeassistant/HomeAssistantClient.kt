package com.jarvis.ai.core.homeassistant

import com.jarvis.ai.data.settings.SettingsDataStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client Home Assistant : REST pour les appels de service (allumer une lumière, activer une
 * scène...) et lecture d'état. L'URL (locale ou distante via Nabu Casa / reverse proxy perso)
 * et le Long-Lived Access Token sont 100% configurables dans les réglages.
 *
 * TODO : ajouter un client WebSocket (`/api/websocket`) pour les mises à jour d'état en
 * temps réel, utile pour que l'orb reflète l'état de la maison sans polling.
 */
@Singleton
class HomeAssistantClient @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsDataStore
) {
    private fun baseUrl() = settings.getHomeAssistantUrl().trimEnd('/')
    private fun token() = settings.getHomeAssistantToken()

    suspend fun callService(call: HAServiceCall) {
        val body = JSONObject().put("entity_id", call.entityId)
        val request = Request.Builder()
            .url("${baseUrl()}/api/services/${call.domain}/${call.service}")
            .addHeader("Authorization", "Bearer ${token()}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Home Assistant error ${resp.code}")
        }
    }

    suspend fun getStates(): List<HAEntityState> {
        val request = Request.Builder()
            .url("${baseUrl()}/api/states")
            .addHeader("Authorization", "Bearer ${token()}")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Home Assistant error ${resp.code}")
            val arr = JSONArray(resp.body?.string().orEmpty())
            return (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                HAEntityState(entityId = obj.getString("entity_id"), state = obj.getString("state"))
            }
        }
    }
}
