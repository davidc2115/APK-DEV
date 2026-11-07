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
AASCjD6ZKn5ko6cU5Vxh8GA1KqRi6p2GQzndxHtuUmwY8RvBbhZ0GIL7bQ4f08ae
JOv0ycWjEW0fyOnAw6AYdsN6y1eNvH2DVfoXQyGoCSvXQNAUxla+sJuLGICRYiZz
mnijYzBhMB0GA1UdDgQWBBTIB3c2GlbV6EIh2ErEMJvFxMz/QTAfBgNVHSMEGDAW
gBTIB3c2GlbV6EIh2ErEMJvFxMz/QTAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB
/wQEAwIBhjAKBggqhkjOPQQDAgNoADBlAjA8tzEMRVX8vrFuOGDhvZr7OSJjbBr8
gl2I70LeVNGEXZsAThUkqj5Rg9bV8xw3aSMCMQCDjB5CgsLH8EdZmiksdBRRKM2r
vxo6c0dSSNrr7dDN+m2/dRvgoIpGL2GauOGqDFY=
-----END CERTIFICATE-----"""

    private fun confiance(): X509TrustManager {
        val cf = CertificateFactory.getInstance("X.509")
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        ks.setCertificateEntry("freebox_ecc_root", cf.generateCertificate(rootPem.byteInputStream()))
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun socketFactory(): javax.net.ssl.SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(confiance()), null) }.socketFactory

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

    // ================================================================== gestion de la Freebox (droits accordés dans Freebox OS)

    @Volatile private var versionApi = 0

    /** Version majeure de l'API annoncée par la box (les routes v4 restent valides ; plus récent si disponible). */
    private fun version(app: JarvisApp): Int {
        if (versionApi > 0) return versionApi
        versionApi = runCatching {
            val domain = app.store.freeboxApiDomain.value.trim()
            val port = app.store.freeboxHttpsPort.value.toIntOrNull() ?: 443
            val c = URL("https://$domain:$port/api_version").openConnection() as HttpsURLConnection
            c.sslSocketFactory = socketFactory(); c.connectTimeout = 6000; c.readTimeout = 6000
            try { JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("api_version").substringBefore('.').toInt() } finally { c.disconnect() }
        }.getOrDefault(4).coerceIn(4, 15)
        return versionApi
    }

    private class Reponse(val code: Int, val type: String, val nom: String, val octets: ByteArray)

    /** Requête authentifiée sur la version d'API annoncée ; renvoie le JSON (« result » éventuel) ou des octets bruts. */
    private fun requete(app: JarvisApp, path: String, session: String, method: String = "GET", body: JSONObject? = null): Reponse {
        val domain = app.store.freeboxApiDomain.value.trim()
        val port = app.store.freeboxHttpsPort.value.toIntOrNull() ?: 443
        val c = URL("https://$domain:$port/api/v${version(app)}/" + path.trimStart('/')).openConnection() as HttpsURLConnection
        c.sslSocketFactory = socketFactory(); c.connectTimeout = 8000; c.readTimeout = 20000
        c.requestMethod = method
        c.setRequestProperty("X-Fbx-App-Auth", session)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        try {
            val flux = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val octets = flux?.use { it.readBytes() } ?: ByteArray(0)
            val nom = c.getHeaderField("Content-Disposition")?.substringAfter("filename=", "")?.trim('"', ' ', ';').orEmpty()
            return Reponse(c.responseCode, c.contentType.orEmpty(), nom, octets)
        } finally { c.disconnect() }
    }

    private fun json(app: JarvisApp, path: String, session: String, method: String = "GET", body: JSONObject? = null): Any? {
        val r = requete(app, path, session, method, body)
        val j = runCatching { JSONObject(String(r.octets, Charsets.UTF_8)) }.getOrElse { throw IllegalStateException("Réponse inattendue de la Freebox (HTTP ${r.code}).") }
        if (!j.optBoolean("success", false)) {
            val code = j.optString("error_code"); val msg = j.optString("msg")
            throw IllegalStateException(when (code) {
                "insufficient_rights" -> j.optString("missing_right").ifBlank { "settings" }.let { d ->
                    "la Freebox refuse : le droit « ${DROITS[d] ?: d} » n'est pas accordé à JARVIS. Active-le dans Freebox OS › Paramètres de la Freebox › Gestion des accès › Applications › JARVIS, puis réessaie."
                }
                "auth_required", "invalid_session" -> "session Freebox expirée, réessaie."
                else -> msg.ifBlank { code.ifBlank { "erreur HTTP ${r.code}" } }
            })
        }
        return j.opt("result")
    }

    /** Libellés des droits tels qu'ils apparaissent dans Freebox OS (Gestion des accès › Applications). */
    private val DROITS = mapOf(
        "explorer" to "Accès au gestionnaire de fichiers", "settings" to "Modification des réglages de la Freebox",
        "downloader" to "Accès au gestionnaire de téléchargements", "contacts" to "Accès à la base de contacts de la Freebox",
        "calls" to "Accès au journal d'appels", "parental" to "Accès au contrôle parental", "pvr" to "Programmation des enregistrements",
        "camera" to "Accès aux caméras", "home" to "Gestion de l'alarme et maison connectée", "vm" to "Contrôle des VM",
    )

    private fun <T> avecSession(app: JarvisApp, bloc: (String) -> T): T {
        val token = app.store.freeboxAppToken.value
        require(token.isNotBlank()) { "JARVIS n'est pas encore associé à la Freebox (Réglages › Box › Associer)." }
        val challenge = call(app, "login/").getJSONObject("result").getString("challenge")
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val password = mac.doFinal(challenge.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val session = call(app, "login/session/", "POST", JSONObject().put("app_id", APP_ID).put("app_version", "1.0").put("password", password))
            .getJSONObject("result").getString("session_token")
        try { return bloc(session) } finally { runCatching { call(app, "login/logout/", "POST", JSONObject(), session) } }
    }

    private fun liste(v: Any?): List<JSONObject> = (v as? org.json.JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
    private fun debit(octetsParS: Long) = when { octetsParS >= 1_000_000 -> "%.1f Mo/s".format(octetsParS / 1e6); octetsParS >= 1000 -> "${octetsParS / 1000} Ko/s"; else -> "$octetsParS o/s" }
    private fun bande(bits: Long) = if (bits >= 1_000_000_000) "%.1f Gb/s".format(bits / 1e9) else "${bits / 1_000_000} Mb/s"

    private fun hotes(app: JarvisApp, s: String): List<JSONObject> = liste(json(app, "lan/browser/pub/", s))
    private fun ipv4(h: JSONObject): String = liste(h.optJSONArray("l3connectivities")).filter { it.optString("af") == "ipv4" }
        .sortedByDescending { it.optBoolean("active") }.firstOrNull()?.optString("addr").orEmpty()

    /** Adresse IP d'un appareil du réseau à partir de son nom (tel qu'affiché par la Freebox). */
    private fun ipDe(app: JarvisApp, s: String, nomOuIp: String): String {
        val v = nomOuIp.trim()
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(v)) return v
        val n = v.lowercase()
        val h = hotes(app, s).filter { it.optString("primary_name").lowercase().contains(n) && ipv4(it).isNotBlank() }
        return when (h.size) {
            0 -> throw IllegalArgumentException("aucun appareil « $v » sur le réseau de la Freebox.")
            1 -> ipv4(h[0])
            else -> h.firstOrNull { it.optBoolean("active") }?.let { ipv4(it) } ?: throw IllegalArgumentException("plusieurs appareils « $v » : ${h.joinToString { it.optString("primary_name") + " " + ipv4(it) }}.")
        }
    }

    private fun serveurVpn(app: JarvisApp, s: String, demande: String): String {
        val noms = liste(json(app, "vpn/", s)).map { it.optString("name") }.filter { it.isNotBlank() }
        val d = demande.trim().lowercase().replace(" ", "_")
        return noms.firstOrNull { it == d } ?: noms.firstOrNull { d.isNotBlank() && it.contains(d) }
            ?: noms.firstOrNull { it.contains("wireguard") } ?: noms.firstOrNull { it == "openvpn_routed" } ?: noms.firstOrNull()
            ?: throw IllegalStateException("aucun serveur VPN proposé par cette Freebox.")
    }

    private fun motDePasse(): String {
        val r = java.security.SecureRandom(); val c = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        return (1..16).map { c[r.nextInt(c.length)] }.joinToString("")
    }

    /**
     * Pilotage de la Freebox : état, appareils, redirections de ports, VPN (serveur, utilisateurs, fichier de
     * configuration), Wi-Fi, DMZ, baux DHCP, journal d'appels, redémarrage. Les modifications sont confirmées.
     */
    suspend fun gerer(app: JarvisApp, actionBrute: String, a: JSONObject, confirm: suspend (String, Boolean) -> Boolean): String {
        val action = actionBrute.trim().lowercase().replace(' ', '_').replace('-', '_')
        if (app.store.freeboxAppToken.value.isBlank()) return "JARVIS n'est pas encore associé à la Freebox : Réglages › Box › Associer la Freebox (à valider sur l'écran de la box)."
        // Confirmation AVANT d'ouvrir la session (pas de session ouverte pendant que l'utilisateur réfléchit).
        val question = when (action) {
            "ajouter_redirection" -> "Freebox : ouvrir le port ${a.optString("port_externe").ifBlank { a.optString("port") }} (${a.optString("protocole").ifBlank { "tcp" }}) vers ${a.optString("appareil").ifBlank { a.optString("ip") }}:${a.optString("port")} ?"
            "supprimer_redirection" -> "Freebox : supprimer la redirection ${a.optString("id").ifBlank { "du port " + a.optString("port") }} ?"
            "activer_redirection", "desactiver_redirection" -> "Freebox : ${if (action.startsWith("activer")) "activer" else "désactiver"} la redirection ${a.optString("id")} ?"
            "activer_vpn", "desactiver_vpn" -> "Freebox : ${if (action.startsWith("activer")) "activer" else "désactiver"} le serveur VPN ${a.optString("serveur").ifBlank { "(WireGuard par défaut)" }} ?"
            "ajouter_vpn_utilisateur" -> "Freebox : créer l'utilisateur VPN « ${a.optString("login")} » ?"
            "supprimer_vpn_utilisateur" -> "Freebox : supprimer l'utilisateur VPN « ${a.optString("login")} » ?"
            "config_vpn" -> "Freebox : générer le fichier de configuration VPN de « ${a.optString("login")} » ? (l'ancien fichier de cet utilisateur ne fonctionnera plus)"
            "wifi_activer", "wifi_desactiver" -> "Freebox : ${if (action == "wifi_activer") "activer" else "COUPER"} le Wi-Fi de la box ?" + (if (action == "wifi_desactiver") " Les appareils en Wi-Fi (dont peut-être ce téléphone) seront déconnectés." else "")
            "dmz" -> if (a.optString("ip").isNotBlank() || a.optString("appareil").isNotBlank() || a.optString("etat").isNotBlank()) "Freebox : modifier la DMZ (${a.optString("etat").ifBlank { "activer" }} ${a.optString("appareil").ifBlank { a.optString("ip") }}) ?" else null
            "ajouter_bail" -> "Freebox : réserver l'adresse ${a.optString("ip")} pour ${a.optString("mac")} ?"
            "redemarrer" -> "Freebox : REDÉMARRER la box ? Internet, TV et téléphone seront coupés quelques minutes."
            "deplacer", "deplacer_fichier", "copier", "copier_fichier", "envoyer_fichier", "envoyer", "ajouter_fichier", "televerser" ->
                if (a.optBoolean("remplacer", false)) "Freebox : REMPLACER les fichiers du même nom déjà présents dans « ${a.optString("destination").ifBlank { a.optString("chemin") }} » ?" else null
            else -> null
        }
        if (question != null && !confirm(question, true)) return "Annulé : rien n'a été modifié sur la Freebox."
        if (action in ACTIONS_SUPPRESSION) return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val cibles = avecSession(app) { s -> trouverElements(app, s, a.optString("chemin").ifBlank { a.optString("fichier") }, volumes(app, s)) }
                if (cibles.isEmpty()) return@withContext "❌ Freebox : rien ne correspond à « ${a.optString("chemin")} » sur le disque de la box."
                if (cibles.size > 25) return@withContext "⚠️ ${cibles.size} éléments correspondent : précise le chemin (je ne supprime pas plus de 25 éléments à la fois)."
                val liste = cibles.joinToString("\n") { (if (it.dossier) "📁 " else "📄 ") + lisible(it.chemin) }
                if (!confirm("Freebox : SUPPRIMER définitivement du disque de la box ?\n$liste", true)) return@withContext "Annulé : rien n'a été supprimé."
                avecSession(app) { s -> tache(app, s, json(app, "fs/rm/", s, "POST", JSONObject().put("files", org.json.JSONArray(cibles.map { it.path64 }))), "Supprimé : ${cibles.joinToString(", ") { it.nom }}") }
            }
        } catch (e: Exception) { "❌ Freebox : ${e.message ?: e.javaClass.simpleName}" }
        return try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { avecSession(app) { s -> executer(app, s, action, a) } } }
        catch (e: Exception) { "❌ Freebox : ${e.message ?: e.javaClass.simpleName}" }
    }

    // ------------------------------------------------------------------ disque dur de la box (droit « explorer »)

    /** Chemin en base64 pour l'URL (« / » et « + » du base64 échappés, sinon ils couperaient l'adresse). */
    private fun b64(chemin: String) = java.util.Base64.getEncoder().encodeToString(chemin.toByteArray(Charsets.UTF_8))
        .replace("+", "%2B").replace("/", "%2F")
    private fun deB64(v: String) = runCatching { String(java.util.Base64.getDecoder().decode(v), Charsets.UTF_8) }.getOrDefault("")

    /** Disque vu par la Freebox (interne, USB, SATA…) : partition montée → chemin dans l'explorateur. */
    private data class Volume(val chemin: String, val libelle: String, val type: String, val etat: String, val total: Long, val libre: Long)

    /** Tous les disques et partitions connus de la Freebox (API stockage), montés ou non. */
    private fun volumes(app: JarvisApp, s: String): List<Volume> = runCatching {
        liste(json(app, "storage/disk/", s)).flatMap { d ->
            val type = d.optString("type").ifBlank { d.optString("connector") }
            val parts = liste(d.optJSONArray("partitions"))
            if (parts.isEmpty()) listOf(Volume("", d.optString("model").ifBlank { "Disque" }, type, d.optString("state"), d.optLong("total_bytes"), 0))
            else parts.map { p ->
                val chemin = deB64(p.optString("path")).ifBlank { p.optString("label").takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty() }
                Volume(chemin, p.optString("label").ifBlank { d.optString("model").ifBlank { "Disque" } }, type, p.optString("state"), p.optLong("total_bytes"), p.optLong("free_bytes"))
            }
        }
    }.getOrDefault(emptyList())
    internal fun cle(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim().removeSuffix("s")

    private fun ls(app: JarvisApp, s: String, chemin: String): List<JSONObject> =
        liste(json(app, "fs/ls/${b64(chemin)}?onlyFolder=0&countSubFolder=1&removeHidden=1", s)).filter { it.optString("name") !in setOf(".", "..") }

    /**
     * Retrouve un dossier du disque de la box à partir d'un nom approximatif (« téléchargement », « films/séries »,
     * « /Disque dur/Téléchargements ») : chaque morceau est comparé sans accents, majuscules ni pluriel.
     */
    private fun resoudre(app: JarvisApp, s: String, demande: String, volumes: List<Volume>): String? =
        resoudreAvec({ c -> ls(app, s, c) }, demande, volumes.filter { it.etat == "mounted" }.map { it.chemin })

    internal fun resoudreAvec(ls: (String) -> List<JSONObject>, demande: String, racinesConnues: List<String> = emptyList(), exact: Boolean = false): String? {
        val morceaux = demande.trim().trim('/').split('/').map { it.trim() }.filter { it.isNotBlank() }
        if (morceaux.isEmpty()) return "/"
        // Racines de l'explorateur, complétées par les disques montés connus de l'API stockage (USB compris).
        val disques = (runCatching { ls("/") }.getOrDefault(emptyList()).filter { it.optString("type") == "dir" }.map { it.optString("name") } +
            racinesConnues.map { it.trim('/') }.filter { it.isNotBlank() && !it.contains('/') }).distinct()
        fun suivre(depart: String, reste: List<String>): String? {
            var courant = depart
            for (m in reste) {
                val enfants = runCatching { ls(courant) }.getOrNull() ?: return null
                val voulu = cle(m)
                val trouve = enfants.firstOrNull { it.optString("type") == "dir" && cle(it.optString("name")) == voulu }
                    ?: (if (exact) null else enfants.firstOrNull { it.optString("type") == "dir" && cle(it.optString("name")).let { n -> n.startsWith(voulu) || voulu.startsWith(n) } })
                    ?: return null
                courant = courant.trimEnd('/') + "/" + trouve.optString("name")
            }
            return courant
        }
        val premier = disques.firstOrNull { cle(it) == cle(morceaux.first()) }
        if (premier != null) return suivre("/$premier", morceaux.drop(1))
        for (d in disques) suivre("/$d", morceaux)?.let { return it }
        return null
    }

    private fun taille(o: Long) = when { o >= 1L shl 30 -> "%.1f Go".format(o / 1073741824.0); o >= 1L shl 20 -> "%.1f Mo".format(o / 1048576.0); o >= 1024 -> "${o / 1024} Ko"; else -> "$o o" }

    private fun libelleVolume(v: Volume) = "${when (v.type.lowercase()) { "usb" -> "🔌 USB"; "internal" -> "💽 interne"; "sata", "esata" -> "💽 SATA"; else -> "💽 ${v.type}" }} « ${v.libelle} »" +
        (if (v.total > 0) " — ${taille(v.total)}" + (if (v.libre > 0) ", ${taille(v.libre)} libres" else "") else "") +
        when (v.etat) { "mounted" -> ""; "umounted", "unmounted" -> " — ⚠️ non monté"; "error" -> " — ⚠️ en erreur"; "checking" -> " — vérification en cours"; else -> if (v.etat.isBlank()) "" else " — ${v.etat}" }

    private fun lignes(l: List<JSONObject>): String {
        val date = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRENCH)
        return l.sortedWith(compareBy<JSONObject> { it.optString("type") != "dir" }.thenBy { it.optString("name").lowercase() }).take(150).joinToString("\n") {
            if (it.optString("type") == "dir") "📁 ${it.optString("name")}" + it.optInt("filecount", -1).let { f -> if (f >= 0) " · $f élément(s)" else "" }
            else "📄 ${it.optString("name")} — ${taille(it.optLong("size"))}" + it.optLong("modification").let { m -> if (m > 0) " — ${date.format(java.util.Date(m * 1000))}" else "" }
        } + (if (l.size > 150) "\n… (${l.size} éléments)" else "")
    }

    private fun fichiers(app: JarvisApp, s: String, demande: String): String {
        val entete = "🔐 Freebox OS (association JARVIS)\n"
        val vols = volumes(app, s)
        val montes = vols.filter { it.etat == "mounted" && it.chemin.isNotBlank() }
        val bilanDisques = if (vols.isEmpty()) "" else "Disques vus par la Freebox :\n" + vols.joinToString("\n") { "• " + libelleVolume(it) + (if (it.chemin.isNotBlank()) " → ${it.chemin}" else "") } + "\n\n"
        if (demande.isBlank()) {
            // Vue d'ensemble : chaque disque monté et son premier niveau.
            if (vols.isEmpty() && runCatching { ls(app, s, "/") }.getOrDefault(emptyList()).isEmpty())
                return entete + "💽 La Freebox ne signale aucun disque. Vérifie que le disque USB est branché et alimenté, puis qu'il apparaît dans Freebox OS › Explorateur de fichiers (ou Paramètres › Disques)."
            val racines = if (montes.isNotEmpty()) montes.map { it.chemin } else ls(app, s, "/").filter { it.optString("type") == "dir" }.map { "/" + it.optString("name") }
            return entete + bilanDisques + racines.joinToString("\n\n") { r ->
                val l = runCatching { ls(app, s, r) }.getOrElse { e -> return@joinToString "📂 $r : ❌ ${e.message}" }
                "📂 Freebox › ${r.trim('/').replace("/", " › ")} — ${l.count { it.optString("type") != "dir" }} fichier(s), ${l.count { it.optString("type") == "dir" }} dossier(s)\n" +
                    (if (l.isEmpty()) "(vide)" else lignes(l))
            }
        }
        val chemin = resoudre(app, s, demande, vols)
            ?: return entete + bilanDisques + "📂 Aucun dossier « $demande » trouvé sur ${if (montes.size > 1) "les disques" else "le disque"} de la Freebox." +
                (if (vols.isEmpty()) " La Freebox ne signale aucun disque monté : vérifie le branchement du disque USB." else " Demande-moi « liste le disque de la box » pour voir les dossiers disponibles.")
        val l = ls(app, s, chemin)
        val titre = "📂 Freebox › ${chemin.trim('/').replace("/", " › ")}"
        if (l.isEmpty()) return entete + "$titre : dossier vide (aucun fichier)."
        return entete + "$titre — ${l.count { it.optString("type") != "dir" }} fichier(s), ${l.count { it.optString("type") == "dir" }} dossier(s)\n" + lignes(l)
    }

    private fun telechargements(app: JarvisApp, s: String): String {
        val l = liste(json(app, "downloads/", s))
        if (l.isEmpty()) return "⬇️ Aucun téléchargement dans le gestionnaire de la Freebox."
        val etats = mapOf("downloading" to "en cours", "done" to "terminé", "seeding" to "terminé (partage)", "stopped" to "en pause", "queued" to "en attente",
            "error" to "erreur", "checking" to "vérification", "extracting" to "extraction", "repairing" to "réparation", "retry" to "nouvel essai")
        return "⬇️ Téléchargements de la Freebox (${l.size}) :\n" + l.take(60).joinToString("\n") {
            "• ${it.optString("name")} — ${etats[it.optString("status")] ?: it.optString("status")} (${it.optInt("rx_pct") / 100} %, ${taille(it.optLong("size"))})" +
                it.optLong("rx_rate").let { r -> if (r > 0) " ⬇️ ${debit(r)}" else "" }
        }
    }

    // ------------------------------------------------------------------ gestion des fichiers du disque de la box
    // (renommer, déplacer, copier, supprimer, créer un dossier, envoyer depuis le téléphone, récupérer sur le téléphone)
    // Tout passe par l'association Freebox OS (droit « Accès au gestionnaire de fichiers ») : aucun partage SMB/FTP requis.

    private val ACTIONS_SUPPRESSION = setOf("supprimer_fichier", "supprimer_fichiers", "supprimer_dossier", "effacer_fichier")

    /** Fichier ou dossier du disque : chemin lisible + chemin encodé EXACT renvoyé par la box (réutilisé tel quel). */
    internal data class Element(val chemin: String, val path64: String, val nom: String, val dossier: Boolean, val taille: Long)

    private fun b64brut(chemin: String) = java.util.Base64.getEncoder().encodeToString(chemin.toByteArray(Charsets.UTF_8))

    private fun element(o: JSONObject, parent: String): Element {
        val nom = o.optString("name")
        val chemin = deB64(o.optString("path")).ifBlank { parent.trimEnd('/') + "/" + nom }
        return Element(chemin, o.optString("path").ifBlank { b64brut(chemin) }, nom, o.optString("type") == "dir", o.optLong("size"))
    }

    private fun lisible(chemin: String) = "Freebox › " + chemin.trim('/').replace("/", " › ")

    /**
     * Noms d'un dossier qui correspondent à [voulu] : nom exact (sans accents ni majuscules), sans l'extension, puis
     * début du nom, puis contenu ; « *.mkv », « film* »… acceptés (jokers).
     */
    internal fun choisirNoms(noms: List<String>, voulu: String): List<String> {
        val v = voulu.trim()
        if (v.isBlank()) return emptyList()
        if (v.contains('*') || v.contains('?')) {
            val r = Regex(v.split('*').joinToString(".*") { p -> p.split('?').joinToString(".") { Regex.escape(it) } }, RegexOption.IGNORE_CASE)
            return noms.filter { r.matches(it) }
        }
        val c = cle(v)
        if (c.isBlank()) return emptyList()
        noms.filter { cle(it) == c }.takeIf { it.isNotEmpty() }?.let { return it }
        noms.filter { cle(it.substringBeforeLast('.')) == c }.takeIf { it.isNotEmpty() }?.let { return it }
        if (c.length < 3) return emptyList()
        noms.filter { cle(it).startsWith(c) }.takeIf { it.isNotEmpty() }?.let { return it }
        return noms.filter { cle(it).contains(c) }
    }

    /** Racines de recherche : disques montés (USB compris), sinon les dossiers de premier niveau de l'explorateur. */
    private fun racines(app: JarvisApp, s: String, vols: List<Volume>): List<String> =
        vols.filter { it.etat == "mounted" && it.chemin.isNotBlank() }.map { it.chemin }
            .ifEmpty { ls(app, s, "/").filter { it.optString("type") == "dir" }.map { "/" + it.optString("name") } }

    /** Fichiers ou dossiers désignés par [demande] : « Téléchargements/film.mkv », un joker (« *.mkv » dans un dossier) ou un simple nom. */
    private fun trouverElements(app: JarvisApp, s: String, demande: String, vols: List<Volume>): List<Element> {
        val d = demande.trim().trim('/')
        require(d.isNotBlank()) { "précise le fichier ou le dossier (paramètre chemin)." }
        val parent = d.substringBeforeLast('/', "")
        val nom = d.substringAfterLast('/')
        if (parent.isNotBlank()) {
            val p = resoudre(app, s, parent, vols)
            if (p != null) {
                val enfants = ls(app, s, p)
                val noms = choisirNoms(enfants.map { it.optString("name") }, nom).toSet()
                return enfants.filter { it.optString("name") in noms }.map { element(it, p) }
            }
            // Le dossier parent n'existe pas tel quel : peut-être un dossier entier (« Vidéos/Films »).
            resoudre(app, s, d, vols)?.let { c -> return listOf(Element(c, b64brut(c), c.substringAfterLast('/'), true, 0)) }
            return emptyList()
        }
        return chercher(app, s, nom, vols)
    }

    /** Recherche par nom dans tout le disque (5 niveaux, 200 dossiers lus au plus) : correspondances exactes d'abord. */
    private fun chercher(app: JarvisApp, s: String, nom: String, vols: List<Volume>, max: Int = 15): List<Element> {
        val exacts = mutableListOf<Element>(); val partiels = mutableListOf<Element>()
        val file = ArrayDeque(racines(app, s, vols).map { it to 0 })
        val c = cle(nom); val joker = nom.contains('*') || nom.contains('?')
        var lus = 0
        while (file.isNotEmpty() && lus < 200 && exacts.size < max) {
            val (dossier, prof) = file.removeFirst(); lus++
            val enfants = runCatching { ls(app, s, dossier) }.getOrDefault(emptyList())
            val retenus = choisirNoms(enfants.map { it.optString("name") }, nom).toSet()
            for (o in enfants) {
                val n = o.optString("name")
                if (n in retenus) {
                    if (joker || cle(n) == c || cle(n.substringBeforeLast('.')) == c) exacts += element(o, dossier) else partiels += element(o, dossier)
                }
                if (o.optString("type") == "dir" && prof < 5) file.addLast(dossier.trimEnd('/') + "/" + n to prof + 1)
            }
        }
        return (exacts.ifEmpty { partiels }).distinctBy { it.path64 }.take(max)
    }

    /** Un seul élément, sinon une explication (aucun / plusieurs candidats). */
    private fun unSeul(l: List<Element>, demande: String): Element = when (l.size) {
        1 -> l[0]
        0 -> throw IllegalArgumentException("rien ne correspond à « $demande » sur le disque de la box (essaie chercher_fichier, ou donne le chemin, ex. « Téléchargements/nom du fichier »).")
        else -> throw IllegalArgumentException("plusieurs éléments correspondent à « $demande » — lequel ?\n" + l.take(10).joinToString("\n") { "• " + lisible(it.chemin) })
    }

    /** Dossier de destination (créé s'il manque, avec ses parents, quand [creer]). */
    private fun dossierCible(app: JarvisApp, s: String, demande: String, vols: List<Volume>, creer: Boolean): String {
        val morceaux = demande.trim().trim('/').split('/').map { it.trim() }.filter { it.isNotBlank() }
        require(morceaux.isNotEmpty()) { "précise le dossier de destination (paramètre destination)." }
        // Plus long début de chemin qui existe déjà (noms exacts, sans accents ni majuscules).
        var i = morceaux.size; var base: String? = null
        while (i > 0) { base = resoudreAvec({ c -> ls(app, s, c) }, morceaux.take(i).joinToString("/"), vols.filter { it.etat == "mounted" }.map { it.chemin }, exact = true); if (base != null) break; i-- }
        if (i == morceaux.size) return base!!
        if (!creer) throw IllegalArgumentException("le dossier « ${demande.trim('/')} » n'existe pas sur le disque de la box.")
        var courant = base ?: racines(app, s, vols).firstOrNull() ?: throw IllegalStateException("aucun disque monté sur la Freebox.")
        for (m in morceaux.drop(i)) {
            json(app, "fs/mkdir/", s, "POST", JSONObject().put("parent", b64brut(courant)).put("dirname", m))
            courant = courant.trimEnd('/') + "/" + m
        }
        return courant
    }

    /** Suit une tâche de la box (déplacement, copie, suppression) jusqu'à la fin (90 s au plus). */
    private fun tache(app: JarvisApp, s: String, r: Any?, libelle: String): String {
        val t = r as? JSONObject ?: return "✅ $libelle"
        val id = t.optInt("id", -1)
        if (id < 0) return "✅ $libelle"
        var dernier = t; var etat = t.optString("state")
        val fin = System.currentTimeMillis() + 90_000
        while (etat in setOf("queued", "running", "paused", "") && System.currentTimeMillis() < fin) {
            Thread.sleep(700)
            dernier = runCatching { json(app, "fs/tasks/$id", s) as? JSONObject }.getOrNull() ?: break
            etat = dernier.optString("state")
        }
        return when (etat) {
            "done" -> { runCatching { json(app, "fs/tasks/$id", s, "DELETE") }; "✅ $libelle" }
            "failed" -> "❌ $libelle : échec (" + when (val e = dernier.optString("error")) {
                "file_not_found" -> "fichier introuvable"; "dest_is_not_dir" -> "la destination n'est pas un dossier"
                "file_exists", "dest_exists" -> "un élément du même nom existe déjà"; "access_denied" -> "accès refusé"
                "out_of_space", "disk_full" -> "disque plein"; else -> e.ifBlank { "erreur inconnue" }
            } + ")."
            else -> "⏳ $libelle : la Freebox termine en arrière-plan (gros fichiers) — redemande la liste du dossier dans un moment pour vérifier."
        }
    }

    private fun renommer(app: JarvisApp, s: String, e: Element, nouveau: String): String {
        val n = nouveau.trim().trim('/')
        require(n.isNotBlank() && !n.contains('/')) { "précise le nouveau nom (sans dossier)." }
        if (n == e.nom) return "ℹ️ « ${e.nom} » porte déjà ce nom."
        json(app, "fs/rename/", s, "POST", JSONObject().put("src", e.path64).put("dst", n))
        return "✅ Renommé : ${e.nom} → $n (${lisible(e.chemin.substringBeforeLast('/'))})"
    }

    private fun deplacerOuCopier(app: JarvisApp, s: String, l: List<Element>, dest: String, copier: Boolean, remplacer: Boolean): String {
        require(l.isNotEmpty()) { "rien à ${if (copier) "copier" else "déplacer"}." }
        val corps = JSONObject().put("files", org.json.JSONArray(l.map { it.path64 })).put("dst", b64brut(dest)).put("mode", if (remplacer) "overwrite" else "both")
        val r = json(app, if (copier) "fs/cp/" else "fs/mv/", s, "POST", corps)
        return tache(app, s, r, (if (copier) "Copié" else "Déplacé") + " vers ${lisible(dest)} : " + l.joinToString(", ") { it.nom } +
            if (!remplacer) "" else " (fichiers existants remplacés)")
    }

    /** box_disque « renommer/deplacer » : chemin actuel → nouveau chemin complet (dossier créé si besoin, puis nouveau nom). */
    private fun versNouveauChemin(app: JarvisApp, s: String, source: String, nouveau: String, vols: List<Volume>): String {
        val e = unSeul(trouverElements(app, s, source, vols), source)
        val n = nouveau.trim().trim('/')
        require(n.isNotBlank()) { "précise le nouveau nom ou chemin." }
        if (!n.contains('/')) return renommer(app, s, e, n)
        val dest = dossierCible(app, s, n.substringBeforeLast('/'), vols, creer = true)
        val nom = n.substringAfterLast('/')
        val parentActuel = e.chemin.substringBeforeLast('/')
        if (dest.trimEnd('/') == parentActuel.trimEnd('/')) return renommer(app, s, e, nom)
        val r = deplacerOuCopier(app, s, listOf(e), dest, copier = false, remplacer = false)
        if (!r.startsWith("✅") || nom == e.nom) return r
        val deplace = Element(dest.trimEnd('/') + "/" + e.nom, b64brut(dest.trimEnd('/') + "/" + e.nom), e.nom, e.dossier, e.taille)
        return r + "\n" + runCatching { renommer(app, s, deplace, nom) }.getOrElse { "⚠️ Déplacé, mais renommage impossible : ${it.message}" }
    }

    /** Fichier du téléphone : chemin absolu, relatif au stockage, ou fichier créé par Jarvis (exports, images, vidéos). */
    private fun fichierLocal(app: JarvisApp, nom: String): java.io.File? {
        val n = nom.trim()
        if (n.isBlank()) return null
        java.io.File(n).takeIf { it.isAbsolute && it.isFile }?.let { return it }
        val stockage = android.os.Environment.getExternalStorageDirectory()
        return listOf(stockage, java.io.File(stockage, "Download"), java.io.File(stockage, "Documents"), java.io.File(stockage, "DCIM/Camera"), java.io.File(stockage, "Pictures"),
            Fichiers.dossier(app), java.io.File(app.filesDir, "images"), Fichiers.dossierVideos(app))
            .map { java.io.File(it, n.trimStart('/')) }.firstOrNull { it.isFile }
            ?: listOf(Fichiers.dossier(app), java.io.File(app.filesDir, "images"), Fichiers.dossierVideos(app))
                .firstNotNullOfOrNull { d -> d.listFiles()?.firstOrNull { it.isFile && it.name.equals(n.substringAfterLast('/'), true) } }
    }

    /**
     * Envoi d'un fichier du téléphone sur le disque de la box : API WebSocket de Freebox OS (l'ancien envoi HTTP n'est
     * plus accepté). Le contenu part par blocs de 256 Ko ; [ecraser] = remplacer un fichier du même nom.
     */
    private fun televerser(app: JarvisApp, s: String, f: java.io.File, dossier: String, nom: String, ecraser: Boolean): String {
        val domain = app.store.freeboxApiDomain.value.trim()
        val port = app.store.freeboxHttpsPort.value.toIntOrNull() ?: 443
        val client = okhttp3.OkHttpClient.Builder().sslSocketFactory(socketFactory(), confiance())
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).build()
        val req = okhttp3.Request.Builder().url("https://$domain:$port/api/v${version(app)}/ws/upload").header("X-Fbx-App-Auth", s).build()
        val id = (System.currentTimeMillis() % 1_000_000).toInt()
        val demarre = java.util.concurrent.CountDownLatch(1)
        val termine = java.util.concurrent.CountDownLatch(1)
        val erreur = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val pret = java.util.concurrent.atomic.AtomicBoolean(false)
        val finalise = java.util.concurrent.atomic.AtomicBoolean(false)
        val ws = client.newWebSocket(req, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                webSocket.send(JSONObject().put("action", "upload_start").put("request_id", id).put("size", f.length())
                    .put("dirname", b64brut(dossier)).put("filename", nom).put("force", if (ecraser) "overwrite" else "missing").toString())
            }
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                val j = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (!j.optBoolean("success", true)) {
                    erreur.compareAndSet(null, when (val c = j.optString("error_code")) {
                        "destination_conflict" -> "un fichier « $nom » existe déjà dans ce dossier (dis « remplace-le » pour l'écraser)"
                        "access_denied" -> "écriture refusée dans ce dossier"
                        "insufficient_rights" -> "droit « ${DROITS["explorer"]} » non accordé à JARVIS dans Freebox OS"
                        else -> j.optString("msg").ifBlank { c.ifBlank { "envoi refusé" } }
                    })
                    demarre.countDown(); termine.countDown(); return
                }
                val action = j.optString("action")
                if (!pret.get() && (action == "upload_start" || action.isBlank())) { pret.set(true); demarre.countDown() }
                val res = j.optJSONObject("result")
                if (finalise.get() && (action == "upload_finalize" || j.optBoolean("complete") || res?.optBoolean("complete") == true)) termine.countDown()
            }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                erreur.compareAndSet(null, "connexion interrompue (${t.message ?: t.javaClass.simpleName})"); demarre.countDown(); termine.countDown()
            }
            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) { demarre.countDown(); termine.countDown() }
        })
        try {
            if (!demarre.await(20, java.util.concurrent.TimeUnit.SECONDS) || erreur.get() != null || !pret.get())
                throw IllegalStateException(erreur.get() ?: "la Freebox n'a pas accepté l'envoi (pas de réponse).")
            f.inputStream().use { ins ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n <= 0) break
                    while (ws.queueSize() > 4L * 1024 * 1024) { erreur.get()?.let { throw IllegalStateException(it) }; Thread.sleep(40) }
                    if (!ws.send(okio.ByteString.of(*buf.copyOf(n)))) throw IllegalStateException(erreur.get() ?: "envoi interrompu.")
                }
            }
            while (ws.queueSize() > 0) { erreur.get()?.let { throw IllegalStateException(it) }; Thread.sleep(50) }
            finalise.set(true)
            ws.send(JSONObject().put("action", "upload_finalize").put("request_id", id).toString())
            termine.await(30, java.util.concurrent.TimeUnit.SECONDS)
            erreur.get()?.let { throw IllegalStateException(it) }
        } finally { runCatching { ws.close(1000, null) } }
        // Vérification : le fichier est bien là, à la bonne taille.
        val present = runCatching { ls(app, s, dossier) }.getOrDefault(emptyList()).firstOrNull { it.optString("name") == nom }
        return if (present != null && present.optLong("size") == f.length()) "✅ Envoyé sur la box : ${lisible("$dossier/$nom")} (${taille(f.length())})."
            else if (present != null) "⚠️ ${lisible("$dossier/$nom")} est sur la box mais sa taille diffère (${taille(present.optLong("size"))} au lieu de ${taille(f.length())}) : renvoie-le."
            else "⚠️ Envoi terminé sans confirmation de la Freebox : vérifie le dossier ${lisible(dossier)}."
    }

    /** Copie un fichier de la box sur le téléphone (fichiers de Jarvis), en flux (500 Mo au plus). */
    private fun recuperer(app: JarvisApp, s: String, e: Element): String {
        require(!e.dossier) { "« ${e.nom} » est un dossier : précise un fichier." }
        if (e.taille > 500L * 1024 * 1024) throw IllegalArgumentException("fichier trop gros pour le téléphone (${taille(e.taille)}, 500 Mo au plus).")
        val domain = app.store.freeboxApiDomain.value.trim()
        val port = app.store.freeboxHttpsPort.value.toIntOrNull() ?: 443
        val c = URL("https://$domain:$port/api/v${version(app)}/dl/" + java.net.URLEncoder.encode(e.path64, "UTF-8")).openConnection() as HttpsURLConnection
        c.sslSocketFactory = socketFactory(); c.connectTimeout = 8000; c.readTimeout = 60000
        c.setRequestProperty("X-Fbx-App-Auth", s)
        val nom = e.nom.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val dest = java.io.File(Fichiers.dossier(app), nom)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("la Freebox refuse le téléchargement (HTTP ${c.responseCode}).")
            c.inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } finally { c.disconnect() }
        return "[📎 $nom (${taille(dest.length())})](jarvis-file:$nom)\n✅ Copié depuis ${lisible(e.chemin.substringBeforeLast('/'))}."
    }

    private fun lireTexte(app: JarvisApp, s: String, e: Element): String {
        require(!e.dossier) { "« ${e.nom} » est un dossier." }
        if (e.taille > 300_000) return "Fichier trop gros pour être lu ici (${taille(e.taille)}) : demande-moi de le récupérer sur le téléphone."
        val r = requete(app, "dl/" + java.net.URLEncoder.encode(e.path64, "UTF-8"), s)
        if (r.code !in 200..299) throw IllegalStateException("lecture refusée (HTTP ${r.code}).")
        val t = String(r.octets, Charsets.UTF_8)
        return if (t.count { it == '\uFFFD' } > 5) "Fichier binaire (${taille(e.taille)}) : demande-moi de le récupérer sur le téléphone." else "📄 ${lisible(e.chemin)}\n```\n" + t.take(8000) + "\n```"
    }

    /** État pour le tableau de santé : (connexion active, octets libres, octets au total) du plus grand disque monté. */
    fun etat(app: JarvisApp): Triple<Boolean, Long, Long>? {
        if (app.store.freeboxAppToken.value.isBlank()) return null
        return avecSession(app) { s ->
            val cx = json(app, "connection/", s) as? JSONObject ?: JSONObject()
            val d = volumes(app, s).filter { it.etat == "mounted" && it.total > 0 }.maxByOrNull { it.total }
            Triple(cx.optString("state") == "up", d?.libre ?: -1L, d?.total ?: -1L)
        }
    }

    /** Résumé d'une ligne pour le centre de commande : connexion, débit descendant, place libre sur le disque. */
    fun resume(app: JarvisApp): String? {
        if (app.store.freeboxAppToken.value.isBlank()) return null
        return avecSession(app) { s ->
            val cx = json(app, "connection/", s) as? JSONObject ?: JSONObject()
            val disque = volumes(app, s).filter { it.etat == "mounted" && it.total > 0 }.maxByOrNull { it.total }
            (if (cx.optString("state") == "up") "🟢 En ligne" else "🔴 ${cx.optString("state").ifBlank { "hors ligne" }}") +
                " · ⬇️ ${debit(cx.optLong("rate_down"))}" + (disque?.let { " · 💽 ${taille(it.libre)} libres" } ?: "")
        }
    }

    /** Actions de box_disque quand la Freebox est associée : tout passe par Freebox OS (plus de SMB/FTP à configurer). */
    suspend fun gererDisque(app: JarvisApp, action: String, chemin: String, fichier: String, confirm: suspend (String, Boolean) -> Boolean): String {
        val a = JSONObject().put("chemin", chemin)
        return when (action.lowercase()) {
            "partages", "lister" -> gerer(app, "fichiers", JSONObject().put("chemin", if (action.lowercase() == "partages") "" else chemin), confirm)
            "lire" -> gerer(app, "lire_fichier", a, confirm)
            "telecharger" -> gerer(app, "recuperer_fichier", a, confirm)
            "envoyer" -> gerer(app, "envoyer_fichier", JSONObject().put("fichier", fichier).put("destination", chemin), confirm)
            "supprimer" -> gerer(app, "supprimer_fichier", a, confirm)
            "dossier" -> gerer(app, "creer_dossier", a, confirm)
            "renommer", "deplacer" -> gerer(app, "renommer_ou_deplacer", a.put("nouveau_chemin", fichier), confirm)
            else -> "Action inconnue (partages, lister, lire, telecharger, envoyer, supprimer, dossier, renommer, deplacer)."
        }
    }

    private fun executer(app: JarvisApp, s: String, action: String, a: JSONObject): String = when (action) {
        "fichiers", "disque", "lister_fichiers", "explorer", "dossier" -> fichiers(app, s, a.optString("chemin").ifBlank { a.optString("dossier") })
        "chercher_fichier", "chercher", "rechercher_fichier", "trouver_fichier" -> {
            val q = a.optString("nom").ifBlank { a.optString("chemin") }
            val l = chercher(app, s, q.trim().trim('/').substringAfterLast('/'), volumes(app, s), max = 25)
            if (l.isEmpty()) "🔎 Rien ne correspond à « $q » sur le disque de la box."
            else "🔎 ${l.size} résultat(s) pour « $q » :\n" + l.joinToString("\n") { (if (it.dossier) "📁 " else "📄 ") + it.chemin + (if (!it.dossier) " — ${taille(it.taille)}" else "") }
        }
        "renommer", "renommer_fichier" -> {
            val vols = volumes(app, s)
            renommer(app, s, unSeul(trouverElements(app, s, a.optString("chemin"), vols), a.optString("chemin")), a.optString("nouveau_nom").ifBlank { a.optString("nom") })
        }
        "renommer_ou_deplacer" -> versNouveauChemin(app, s, a.optString("chemin"), a.optString("nouveau_chemin"), volumes(app, s))
        "deplacer", "deplacer_fichier", "copier", "copier_fichier" -> {
            val vols = volumes(app, s)
            val sources = a.optString("chemin").split(Regex("\\s*[;\\n]\\s*")).filter { it.isNotBlank() }.flatMap { trouverElements(app, s, it, vols).ifEmpty { throw IllegalArgumentException("rien ne correspond à « $it » sur le disque de la box.") } }.distinctBy { it.path64 }
            require(sources.size <= 200) { "${sources.size} éléments : précise la sélection (200 au plus)." }
            val dest = dossierCible(app, s, a.optString("destination").ifBlank { a.optString("dossier") }, vols, creer = true)
            deplacerOuCopier(app, s, sources, dest, copier = action.startsWith("copier"), remplacer = a.optBoolean("remplacer", false))
        }
        "creer_dossier", "nouveau_dossier", "mkdir" -> {
            val c = a.optString("chemin").ifBlank { a.optString("dossier") }
            "📁 Dossier prêt : " + lisible(dossierCible(app, s, c, volumes(app, s), creer = true))
        }
        "envoyer_fichier", "envoyer", "ajouter_fichier", "televerser" -> {
            val f = fichierLocal(app, a.optString("fichier"))
                ?: throw IllegalArgumentException("fichier « ${a.optString("fichier")} » introuvable sur le téléphone (donne son chemin complet, ex. /storage/emulated/0/Download/photo.jpg).")
            val dest = dossierCible(app, s, a.optString("destination").ifBlank { a.optString("chemin").ifBlank { "Téléchargements" } }, volumes(app, s), creer = true)
            televerser(app, s, f, dest, a.optString("nouveau_nom").ifBlank { f.name }, ecraser = a.optBoolean("remplacer", false))
        }
        "recuperer_fichier", "recuperer", "telecharger_fichier" -> {
            val vols = volumes(app, s)
            recuperer(app, s, unSeul(trouverElements(app, s, a.optString("chemin"), vols), a.optString("chemin")))
        }
        "lire_fichier" -> {
            val vols = volumes(app, s)
            lireTexte(app, s, unSeul(trouverElements(app, s, a.optString("chemin"), vols), a.optString("chemin")))
        }
        "disques", "stockage" -> volumes(app, s).let { v -> if (v.isEmpty()) "💽 La Freebox ne signale aucun disque (branché, alimenté et visible dans Freebox OS ?)." else "💽 Disques de la Freebox :\n" + v.joinToString("\n") { "• " + libelleVolume(it) + (if (it.chemin.isNotBlank()) " → ${it.chemin}" else "") } }
        "telechargements", "telechargements_en_cours", "downloads" -> telechargements(app, s)
        "etat", "statut", "" -> {
            val sys = json(app, "system/", s) as? JSONObject ?: JSONObject()
            val cx = json(app, "connection/", s) as? JSONObject ?: JSONObject()
            buildString {
                append("📶 Freebox ${sys.optJSONObject("model_info")?.optString("pretty_name").orEmpty().ifBlank { sys.optString("board_name") }} — firmware ${sys.optString("firmware_version")}")
                sys.optString("uptime").takeIf { it.isNotBlank() }?.let { append(", allumée depuis $it") }
                append("\n🌐 Connexion : ${cx.optString("state")} (${cx.optString("media")}) · IP publique ${cx.optString("ipv4")}" + (cx.optString("ipv6").takeIf { it.isNotBlank() }?.let { " / $it" } ?: ""))
                append("\n⬇️ ${debit(cx.optLong("rate_down"))} sur ${bande(cx.optLong("bandwidth_down"))} · ⬆️ ${debit(cx.optLong("rate_up"))} sur ${bande(cx.optLong("bandwidth_up"))}")
            }
        }
        "appareils" -> {
            val h = hotes(app, s).sortedWith(compareByDescending<JSONObject> { it.optBoolean("active") }.thenBy { it.optString("primary_name").lowercase() })
            val actifs = h.count { it.optBoolean("active") }
            "🖧 $actifs appareil(s) connecté(s) sur ${h.size} connus :\n" + h.take(60).joinToString("\n") {
                (if (it.optBoolean("active")) "🟢 " else "⚪ ") + it.optString("primary_name").ifBlank { "(sans nom)" } +
                    ipv4(it).let { ip -> if (ip.isNotBlank()) " — $ip" else "" } + it.optString("vendor_name").let { v -> if (v.isNotBlank()) " · $v" else "" }
            }
        }
        "redirections" -> {
            val r = liste(json(app, "fw/redir/", s))
            if (r.isEmpty()) "Aucune redirection de port sur la Freebox."
            else "🔀 Redirections de ports :\n" + r.joinToString("\n") {
                (if (it.optBoolean("enabled")) "🟢" else "⚪") + " #${it.optInt("id")} ${it.optString("ip_proto").uppercase()} " +
                    (if (it.optInt("wan_port_start") == it.optInt("wan_port_end")) "${it.optInt("wan_port_start")}" else "${it.optInt("wan_port_start")}-${it.optInt("wan_port_end")}") +
                    " → ${it.optString("lan_ip")}:${it.optInt("lan_port")}" + it.optString("hostname").let { h -> if (h.isNotBlank()) " ($h)" else "" } +
                    it.optString("comment").let { c -> if (c.isNotBlank()) " — $c" else "" } +
                    it.optString("src_ip").let { ip -> if (ip.isNotBlank() && ip != "0.0.0.0") " · source $ip uniquement" else "" }
            }
        }
        "ajouter_redirection" -> {
            val ip = ipDe(app, s, a.optString("appareil").ifBlank { a.optString("ip") })
            val portLan = a.optString("port").filter { it.isDigit() }.toIntOrNull() ?: throw IllegalArgumentException("précise le port de l'appareil (port).")
            val debut = a.optString("port_externe").filter { it.isDigit() }.toIntOrNull() ?: portLan
            val fin = a.optString("port_externe_fin").filter { it.isDigit() }.toIntOrNull() ?: debut
            val protos = when (a.optString("protocole").lowercase()) { "udp" -> listOf("udp"); "les deux", "both", "tcp+udp", "tcp/udp", "tous" -> listOf("tcp", "udp"); else -> listOf("tcp") }
            protos.joinToString("\n") { proto ->
                val r = json(app, "fw/redir/", s, "POST", JSONObject().put("enabled", true).put("comment", a.optString("commentaire").ifBlank { "JARVIS" })
                    .put("lan_port", portLan).put("wan_port_start", debut).put("wan_port_end", fin).put("lan_ip", ip).put("ip_proto", proto)
                    .put("src_ip", a.optString("source").ifBlank { "0.0.0.0" })) as? JSONObject
                "✅ Redirection #${r?.optInt("id")} : ${proto.uppercase()} $debut${if (fin != debut) "-$fin" else ""} → $ip:$portLan"
            }
        }
        "supprimer_redirection", "activer_redirection", "desactiver_redirection" -> {
            val id = a.optString("id").filter { it.isDigit() }.toIntOrNull() ?: run {
                val port = a.optString("port").filter { it.isDigit() }.toIntOrNull() ?: throw IllegalArgumentException("précise l'identifiant (#) ou le port de la redirection.")
                liste(json(app, "fw/redir/", s)).filter { it.optInt("wan_port_start") == port || it.optInt("lan_port") == port }
                    .singleOrNull()?.optInt("id") ?: throw IllegalArgumentException("aucune (ou plusieurs) redirection(s) sur le port $port : précise son identifiant.")
            }
            if (action.startsWith("supprimer")) { json(app, "fw/redir/$id", s, "DELETE"); "🗑️ Redirection #$id supprimée." }
            else { json(app, "fw/redir/$id", s, "PUT", JSONObject().put("enabled", action.startsWith("activer"))); "✅ Redirection #$id ${if (action.startsWith("activer")) "activée" else "désactivée"}." }
        }
        "vpn" -> {
            val serveurs = liste(json(app, "vpn/", s))
            if (serveurs.isEmpty()) "Aucun serveur VPN proposé par cette Freebox."
            else "🔐 Serveurs VPN de la Freebox :\n" + serveurs.joinToString("\n") { v ->
                val nom = v.optString("name")
                val conf = runCatching { json(app, "vpn/$nom/config/", s) as? JSONObject }.getOrNull()
                (if (conf?.optBoolean("enabled") == true) "🟢 " else "⚪ ") + nom + " (" + v.optString("type") + ") — " + v.optString("state") +
                    (conf?.optInt("port")?.takeIf { it > 0 }?.let { " · port $it" } ?: "") + " · ${v.optInt("connection_count")} connexion(s)"
            } + "\n\nUtilisateurs : demande « vpn_utilisateurs ». Pour te connecter : crée un utilisateur puis génère son fichier (config_vpn)."
        }
        "activer_vpn", "desactiver_vpn" -> {
            val nom = serveurVpn(app, s, a.optString("serveur"))
            json(app, "vpn/$nom/config/", s, "PUT", JSONObject().put("enabled", action == "activer_vpn"))
            Thread.sleep(1500)
            val etat = liste(json(app, "vpn/", s)).firstOrNull { it.optString("name") == nom }?.optString("state").orEmpty()
            "✅ Serveur VPN $nom ${if (action == "activer_vpn") "activé" else "désactivé"}" + (if (etat.isNotBlank()) " (état : $etat)" else "") + "." +
                (if (action == "activer_vpn" && liste(json(app, "vpn/user/", s)).isEmpty()) "\nAucun utilisateur VPN : crée-en un (ajouter_vpn_utilisateur) puis génère son fichier de connexion (config_vpn)." else "")
        }
        "vpn_utilisateurs" -> {
            val u = liste(json(app, "vpn/user/", s))
            if (u.isEmpty()) "Aucun utilisateur VPN." else "👤 Utilisateurs VPN : " + u.joinToString { it.optString("login") + (it.optString("ip_reservation").let { ip -> if (ip.isNotBlank()) " ($ip)" else "" }) }
        }
        "ajouter_vpn_utilisateur" -> {
            val login = a.optString("login").trim().ifBlank { throw IllegalArgumentException("précise l'identifiant (login) de l'utilisateur VPN.") }
            val mdp = a.optString("mot_de_passe").trim().ifBlank { motDePasse() }
            require(mdp.length in 8..32) { "le mot de passe VPN doit faire 8 à 32 caractères." }
            json(app, "vpn/user/", s, "POST", JSONObject().put("login", login).put("password", mdp).put("ip_reservation", a.optString("ip")))
            "✅ Utilisateur VPN « $login » créé. Mot de passe : $mdp (note-le, il ne sera plus affiché).\nGénère ensuite son fichier de connexion : config_vpn."
        }
        "supprimer_vpn_utilisateur" -> {
            val login = a.optString("login").trim().ifBlank { throw IllegalArgumentException("précise le login.") }
            json(app, "vpn/user/$login", s, "DELETE"); "🗑️ Utilisateur VPN « $login » supprimé."
        }
        "config_vpn" -> {
            val nom = serveurVpn(app, s, a.optString("serveur"))
            val login = a.optString("login").trim().ifBlank { liste(json(app, "vpn/user/", s)).singleOrNull()?.optString("login") ?: throw IllegalArgumentException("précise le login de l'utilisateur VPN.") }
            val r = requete(app, "vpn/download_config/$nom/$login", s)
            val texte = String(r.octets, Charsets.UTF_8)
            if (r.code !in 200..299 || texte.trimStart().startsWith("{")) {
                val j = runCatching { JSONObject(texte) }.getOrNull()
                throw IllegalStateException(j?.optString("msg")?.ifBlank { null } ?: "téléchargement refusé (HTTP ${r.code}).")
            }
            val ext = if (nom.contains("wireguard")) "conf" else if (nom.contains("openvpn")) "ovpn" else "txt"
            val fichier = r.nom.ifBlank { "freebox_${nom}_$login.$ext" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
            java.io.File(Fichiers.dossier(app), fichier).writeBytes(r.octets)
            "🔑 Fichier VPN prêt (serveur $nom, utilisateur $login) :\n[📎 $fichier](jarvis-file:$fichier)\n" +
                (if (ext == "conf") "Importe-le dans l'appli WireGuard (« + » › Importer depuis un fichier)." else "Importe-le dans OpenVPN Connect.") +
                "\n⚠️ Il contient la clé de connexion : ne le partage pas."
        }
        "wifi" -> {
            val conf = json(app, "wifi/config/", s) as? JSONObject ?: JSONObject()
            val bss = runCatching { liste(json(app, "wifi/bss/", s)) }.getOrDefault(emptyList())
            "📡 Wi-Fi de la box : ${if (conf.optBoolean("enabled")) "activé" else "désactivé"}" +
                bss.mapNotNull { b -> b.optJSONObject("config")?.let { c -> "\n- ${c.optString("ssid")} : ${if (c.optBoolean("enabled")) "actif" else "inactif"}" + (b.optJSONObject("status")?.optString("state")?.let { st -> " ($st)" } ?: "") } }.distinct().joinToString("")
        }
        "wifi_activer", "wifi_desactiver" -> {
            json(app, "wifi/config/", s, "PUT", JSONObject().put("enabled", action == "wifi_activer"))
            "✅ Wi-Fi de la box ${if (action == "wifi_activer") "activé" else "coupé"}."
        }
        "dmz" -> {
            val cible = a.optString("appareil").ifBlank { a.optString("ip") }
            val etat = a.optString("etat").lowercase()
            if (cible.isBlank() && etat.isBlank()) {
                val d = json(app, "fw/dmz/", s) as? JSONObject ?: JSONObject()
                "DMZ : " + if (d.optBoolean("enabled")) "activée vers ${d.optString("ip")}" else "désactivée"
            } else {
                val activer = !etat.startsWith("d") && etat != "off" && etat != "non"
                val corps = JSONObject().put("enabled", activer)
                if (activer) corps.put("ip", ipDe(app, s, cible))
                json(app, "fw/dmz/", s, "PUT", corps)
                if (activer) "✅ DMZ activée vers ${corps.optString("ip")} (tous les ports entrants non redirigés y arrivent)." else "✅ DMZ désactivée."
            }
        }
        "baux_dhcp" -> {
            val b = liste(json(app, "dhcp/static_lease/", s))
            if (b.isEmpty()) "Aucune adresse IP réservée (bail DHCP statique)." else "📌 IP réservées :\n" + b.joinToString("\n") { "- ${it.optString("ip")} ← ${it.optString("mac")}" + it.optString("comment").let { c -> if (c.isNotBlank()) " ($c)" else "" } + it.optString("hostname").let { h -> if (h.isNotBlank()) " · $h" else "" } }
        }
        "ajouter_bail" -> {
            val mac = a.optString("mac").trim().ifBlank { throw IllegalArgumentException("précise l'adresse MAC.") }
            val ip = a.optString("ip").trim().ifBlank { throw IllegalArgumentException("précise l'adresse IP à réserver.") }
            json(app, "dhcp/static_lease/", s, "POST", JSONObject().put("mac", mac).put("ip", ip).put("comment", a.optString("commentaire")))
            "✅ L'adresse $ip est désormais réservée à $mac."
        }
        "appels" -> {
            val l = liste(json(app, "call/log/", s)).take(a.optString("nombre").toIntOrNull() ?: 15)
            if (l.isEmpty()) "Aucun appel dans le journal de la Freebox."
            else "📞 Derniers appels (ligne fixe) :\n" + l.joinToString("\n") {
                val type = when (it.optString("type")) { "missed" -> "❌ manqué"; "accepted" -> "✅ reçu"; "outgoing" -> "📤 émis"; else -> it.optString("type") }
                val date = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRENCH).format(java.util.Date(it.optLong("datetime") * 1000))
                "$date $type — ${it.optString("name").ifBlank { it.optString("number") }}" + (it.optInt("duration").takeIf { d -> d > 0 }?.let { d -> " (${d / 60} min ${d % 60} s)" } ?: "")
            }
        }
        "redemarrer" -> { json(app, "system/reboot/", s, "POST", JSONObject()); "🔄 Redémarrage de la Freebox lancé (2 à 5 minutes)." }
        "droits" -> verifierAcces(app)
        else -> "Action Freebox inconnue : $action. Actions : etat, appareils, redirections, ajouter_redirection, supprimer_redirection, activer_redirection, desactiver_redirection, vpn, activer_vpn, desactiver_vpn, vpn_utilisateurs, ajouter_vpn_utilisateur, supprimer_vpn_utilisateur, config_vpn, wifi, wifi_activer, wifi_desactiver, dmz, baux_dhcp, ajouter_bail, appels, redemarrer, droits."
    }
}
