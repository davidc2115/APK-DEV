package com.jarvis.ai.core.freebox

import com.jarvis.ai.data.settings.SettingsDataStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client Freebox OS. `baseUrl` local typique : http://mafreebox.freebox.fr (découverte mDNS
 * possible en Phase 3). Accès distant : domaine `<id>.fbxos.fr` fourni par la Freebox si
 * l'accès distant est activé côté box — les deux sont configurables dans les réglages.
 */
@Singleton
class FreeboxClient @Inject constructor(
    private val client: OkHttpClient,
    private val auth: FreeboxAuth,
    private val settings: SettingsDataStore
) {
    private fun baseUrl() = settings.getFreeboxUrl().trimEnd('/').ifBlank { "http://mafreebox.freebox.fr" }

    suspend fun listConnectedDevices(): List<String> {
        val sessionToken = auth.openSession(baseUrl())
        val request = Request.Builder()
            .url("${baseUrl()}/api/v8/lan/browser/pub/")
            .addHeader("X-Fbx-App-Auth", sessionToken)
            .get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Freebox error ${resp.code}")
            val arr = JSONArray(resp.body?.string().orEmpty())
            return (0 until arr.length()).map { arr.getJSONObject(it).optString("primary_name", "?") }
        }
    }

    suspend fun rebootBox() {
        val sessionToken = auth.openSession(baseUrl())
        val request = Request.Builder()
            .url("${baseUrl()}/api/v8/system/reboot/")
            .addHeader("X-Fbx-App-Auth", sessionToken)
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Freebox reboot error ${resp.code}")
        }
    }

    // TODO Phase 3 : contrôle Wi-Fi on/off (/api/v8/wifi/config/), profil parental,
    // état VPN, découverte automatique via mDNS (_fbx-api._tcp).
}
