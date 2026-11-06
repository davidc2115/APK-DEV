package fr.dd.jarvis.ai

import android.os.Build
import fr.dd.jarvis.JarvisApp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Association Freebox OS validée physiquement sur la box ; jeton applicatif conservé chiffré. */
object FreeboxOs {
    private const val APP_ID = "fr.dd.jarvis"
    private val rootPem = """-----BEGIN CERTIFICATE-----
MIICWTCCAd+gAwIBAgIJAMaRcLnIgyukMAoGCCqGSM49BAMCMGExCzAJBgNVBAYT
AkZSMQ8wDQYDVQQIDAZGcmFuY2UxDjAMBgNVBAcMBVBhcmlzMRMwEQYDVQQKDApG
cmVlYm94IFNBMRwwGgYDVQQDDBNGcmVlYm94IEVDQyBSb290IENBMB4XDTE1MDkw
MTE4MDIwN1oXDTM1MDgyNzE4MDIwN1owYTELMAkGA1UEBhMCRlIxDzANBgNVBAgM
BkZyYW5jZTEOMAwGA1UEBwwFUGFyaXMxEzARBgNVBAoMCkZyZWVib3ggU0ExHDAa
BgNVBAMME0ZyZWVib3ggRUNDIFJvb3QgQ0EwdjAQBgcqhkjOPQIBBgUrgQQAIgNi
AASCjD6ZKn5ko6cU5VxhxHtuUmwY8RvBbhZ0GIL7bQ4f08aeJOv0ycWjEW0fyOnA
w6AYdsN6y1eNvH2DVfoXQNAUxla+sJuLGICRYiZzmnijYzBhMB0GA1UdDgQWBBTI
B3c2GlbV6EIh2ErEMJvFxMz/QTAfBgNVHSMEGDAWgBTIB3c2GlbV6EIh2ErEMJvF
xMz/QTAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBhjAKBggqhkjOPQQD
AgNoADBlAjA8tzEMRVX8vrFuOGDhvZr7OSJjbBr8gl2I70LeVNGEXZsAThUkqj5R
g9bV8xw3aSMCMQCDjB5CgsLH8EdZmiksdBRRKM2rvxo0dSSNrr7dDN+m2/dRvgoI
pGL2GauOGqDFY=
-----END CERTIFICATE-----"""

    private fun socketFactory(): javax.net.ssl.SSLSocketFactory {
        val cf = CertificateFactory.getInstance("X.509")
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        ks.setCertificateEntry("freebox_ecc_root", cf.generateCertificate(rootPem.byteInputStream()))
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val trust = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory
    }

    private fun discover(app: JarvisApp) {
        val c = URL("http://mafreebox.freebox.fr/api_version").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        try {
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val domain = j.optString("api_domain")
            val port = j.optInt("https_port", 443)
            check(domain.isNotBlank() && port in 1..65535 && j.optBoolean("https_available", false)) {
                "La Freebox n'annonce pas d'accès API HTTPS disponible."
            }
            app.store.freeboxApiDomain.set(domain)
            app.store.freeboxHttpsPort.set(port.toString())
            app.store.freeboxUid.set(j.optString("uid"))
        } finally { c.disconnect() }
    }

    private fun call(app: JarvisApp, path: String, method: String = "GET", body: JSONObject? = null, session: String? = null): JSONObject {
        val domain = app.store.freeboxApiDomain.value.trim()
        val port = app.store.freeboxHttpsPort.value.toIntOrNull() ?: 443
        require(domain.isNotBlank()) { "Freebox non découverte : lance l'association depuis son Wi-Fi." }
        val c = URL("https://" + domain + ":" + port + "/api/v4/" + path.trimStart('/')).openConnection() as HttpsURLConnection
        c.sslSocketFactory = socketFactory()
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.requestMethod = method
        c.setRequestProperty("Accept", "application/json")
        if (session != null) c.setRequestProperty("X-Fbx-App-Auth", session)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        try {
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val j = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            if (!j.optBoolean("success", false)) {
                val msg = j.optString("msg").ifBlank { j.optString("error_code") }
                throw IllegalStateException(msg.ifBlank { "Erreur API Freebox HTTP " + c.responseCode })
            }
            return j
        } finally { c.disconnect() }
    }

    /** Envoie la demande à la Freebox ; l'utilisateur doit l'accepter physiquement sur son écran. */
    fun associer(app: JarvisApp): String {
        check(app.store.freeboxAppToken.available) { "Stockage chiffré Android indisponible : jeton non sauvegardé." }
        discover(app)
        val body = JSONObject().put("app_id", APP_ID).put("app_name", "JARVIS")
            .put("app_version", "1.0").put("device_name", Build.MANUFACTURER + " " + Build.MODEL)
        val result = call(app, "login/authorize/", "POST", body).getJSONObject("result")
        val token = result.getString("app_token")
        val trackId = result.getInt("track_id")
        app.store.freeboxTrackId.set(trackId.toString())
        var status = "pending"
        for (i in 0 until 60) {
            status = call(app, "login/authorize/" + trackId).getJSONObject("result").optString("status", "unknown")
            if (status == "granted") {
                app.store.freeboxAppToken.set(token)
                app.store.freeboxTrackId.set("")
                return verifierAcces(app)
            }
            if (status == "denied" || status == "timeout" || status == "unknown") break
            Thread.sleep(1500)
        }
        return when (status) {
            "denied" -> "Demande refusée sur l'écran de la Freebox."
            "timeout" -> "Demande expirée. Relance l'association depuis le Wi-Fi de la Freebox."
            else -> "Demande envoyée. Valide-la sur l'écran de la Freebox Server, puis relance l'association si nécessaire."
        }
    }

    /** Ouvre une session temporaire et affiche les droits effectivement accordés. */
    fun verifierAcces(app: JarvisApp): String {
        val token = app.store.freeboxAppToken.value
        require(token.isNotBlank()) { "Jarvis n'est pas encore associé à la Freebox." }
        val challenge = call(app, "login/").getJSONObject("result").getString("challenge")
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val password = mac.doFinal(challenge.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val session = call(app, "login/session/", "POST",
            JSONObject().put("app_id", APP_ID).put("app_version", "1.0").put("password", password)
        ).getJSONObject("result")
        val rights = session.optJSONObject("permissions") ?: JSONObject()
        val names = listOf("settings", "contacts", "calls", "explorer", "downloader", "parental", "pvr")
        val report = names.joinToString("\n") { n -> "• " + n + " : " + if (rights.optBoolean(n, false)) "autorisé" else "non autorisé" }
        runCatching { call(app, "login/logout/", "POST", JSONObject(), session.getString("session_token")) }
        return "Association Freebox active. Droits réellement accordés :\n" + report +
            "\n\nPour configurer le réseau, active « settings » dans Freebox OS › Paramètres de la Freebox › Gestion des accès › Applications. Les droits restent modifiables et révocables depuis la box."
    }

    fun dissocier(app: JarvisApp) {
        app.store.freeboxAppToken.clear()
        app.store.freeboxApiDomain.set("")
        app.store.freeboxHttpsPort.set("443")
        app.store.freeboxUid.set("")
        app.store.freeboxTrackId.set("")
    }
}
