package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Appareils et services du réseau que JARVIS pilote directement, et COMMENT il les joint : en local (Wi-Fi de la maison)
 * et à distance (dehors). Sert au sous-onglet « Liés à Jarvis » de l'onglet Wi-Fi, et à la question « quels appareils
 * contrôles-tu ? ».
 */
object AppareilsLies {
    data class Lien(
        val id: String,
        val emoji: String,
        val nom: String,
        val role: String,
        val local: String,
        val distant: String,
        val configure: Boolean,
        val reglage: String = "",
        /** Adresses à tester (null : rien à tester de ce côté). « tunnel://ip:port » = par le serveur SSH. */
        val testLocal: String? = null,
        val testDistant: String? = null,
        /** Interface web de l'appareil sur le réseau de la maison (« http://192.168.1.30 »), null s'il n'en a pas. */
        val web: String? = null,
        /** Adresse web publique déjà ouverte (accès à distance direct), sinon null : le tunnel SSH prend le relais. */
        val webDistant: String? = null,
    )

    private fun hote(url: String) = url.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':')
    private fun http(url: String) = url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }

    // ------------------------------------------------------------------ appareils retirés de la liste par l'utilisateur

    fun masques(app: JarvisApp): Set<String> = runCatching { org.json.JSONArray(app.store.liesMasques.value).let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } }.getOrDefault(emptySet())
    fun masquer(app: JarvisApp, id: String, oui: Boolean = true) =
        app.store.liesMasques.set(org.json.JSONArray((if (oui) masques(app) + id else masques(app) - id).toList()).toString())

    /** Liste visible (sans les appareils retirés par l'utilisateur). */
    fun liste(app: JarvisApp): List<Lien> { val m = masques(app); return tousLiens(app).filter { it.id !in m } }
    fun retires(app: JarvisApp): List<Lien> { val m = masques(app); return tousLiens(app).filter { it.id in m } }

    /** Liste complète à partir des réglages et de l'inventaire Wi-Fi (sans réseau). */
    fun tousLiens(app: JarvisApp): List<Lien> {
        val s = app.store
        val l = mutableListOf<Lien>()
        val registre = runCatching { Registre.tous(app) }.getOrDefault(emptyList())
        val ssh = Serveur.sshConfigure(app)

        // Home Assistant. L'adresse des Réglages peut être PUBLIQUE (DuckDNS, nom de domaine) : c'est alors l'accès à
        // distance, et l'adresse locale est celle du serveur sur le réseau de la maison (port 8123).
        val haUrl = s.haUrl.value.trim()
        val hoteHa = hote(haUrl)
        val haPublique = haUrl.isNotBlank() && !Serveur.ipPrivee(hoteHa) && !hoteHa.endsWith(".local") && hoteHa != "localhost"
        val haLan: String? = when {
            haUrl.isBlank() -> null
            !haPublique -> hoteHa
            else -> registre.firstOrNull { e -> e.optJSONArray("ports")?.let { a -> (0 until a.length()).any { a.optInt(it) == 8123 } } == true }?.optString("ip")
                ?: listOf(s.serveurIpLocale.value.trim(), s.sshHost.value.trim()).firstOrNull { Serveur.ipPrivee(it) }
        }
        run {
            val ok = Serveur.haConfigure(app)
            val schema = if (haUrl.startsWith("https")) "https" else "http"
            val local = when { haUrl.isBlank() -> null; !haPublique -> http(haUrl); haLan != null -> "$schema://$haLan:8123"; else -> null }
            // Accès ouverts par JARVIS vers Home Assistant (redirection de la box), en plus de l'adresse publique.
            val ouverts = registre.firstOrNull { it.optString("ip") == haLan }?.optJSONArray("distant")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optInt("port") == 8123 || it.optInt("port") == 0 }.map { it.optString("adresse") }.filter { it.isNotBlank() }
            }.orEmpty()
            val distant = s.haUrlDistant.value.trim().takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "https://$it" }
                ?: (if (haPublique) http(haUrl) else null)
                ?: ouverts.firstOrNull { it.startsWith("http") }
                ?: if (haLan != null && Serveur.ipPrivee(haLan) && ssh && (haLan == s.sshHost.value.trim() || haLan == s.serveurIpLocale.value.trim())) "tunnel SSH par le serveur (dernier recours)" else null
            val autres = ouverts.filter { it != distant }
            l += Lien("ha", "🏠", "Home Assistant", "lumières, volets, prises, chauffage, téléviseurs, enceintes Google/Cast, annonces vocales TTS et notifications compatibles, positions — par son API avec le jeton",
                local?.let { "$it (API + jeton)" } ?: "—",
                (distant ?: "aucune adresse extérieure : dis « rends Home Assistant accessible à distance »") +
                    (if (autres.isNotEmpty()) " · aussi : ${autres.joinToString(", ")}" else ""),
                ok, "Réglages › Maison › Home Assistant",
                testLocal = local?.let { "${it.trimEnd('/')}/manifest.json" },
                testDistant = distant?.takeIf { it.startsWith("http") }?.let { "${it.trimEnd('/')}/manifest.json" }
                    ?: if (ssh && haLan != null && Serveur.ipPrivee(haLan)) "tunnel://$haLan:${portDe(local ?: "", 8123)}" else null,
                // Adresse publique (certificat à son nom) : c'est elle qu'on ouvre, même à la maison (pas d'alerte de certificat).
                web = if (haPublique) http(haUrl) else local, webDistant = distant?.takeIf { it.startsWith("http") })
        }
        // Plex
        run {
            val ok = s.plexToken.value.isNotBlank()
            val ip = runCatching { Plex.ipsLocales(app).firstOrNull() }.getOrNull()
            l += Lien("plex", "🎬", "Plex Media Server", "films, séries, lecture sur la télé et les lecteurs Plex — par son API",
                ip?.let { "http://$it:32400" } ?: "adresse locale pas encore relevée (analyse le Wi-Fi)",
                "connexion distante annoncée par plex.tv (adresse publique, sinon relais Plex)" + (if (ssh) " ; tunnel SSH en dernier recours" else ""),
                ok, "Réglages › Maison › Serveur › Plex",
                testLocal = ip?.let { "http://$it:32400/identity" }, testDistant = if (ssh && ip != null) "tunnel://$ip:32400" else null,
                web = ip?.let { "http://$it:32400/web" })
        }
        // Freebox
        run {
            val ok = s.freeboxAppToken.value.isNotBlank()
            val dom = s.freeboxApiDomain.value.trim()
            val port = s.freeboxHttpsPort.value.trim().ifBlank { "443" }
            l += Lien("freebox", "📶", "Freebox", "Wi-Fi, appareils connectés, VPN, redirections, disque, téléphone — par Freebox OS",
                "http://mafreebox.freebox.fr (réseau de la maison)",
                if (dom.isNotBlank()) "https://$dom:$port (accès à distance de Freebox OS)" else "accès à distance pas encore relevé (associe la Freebox)",
                ok, "Réglages › Maison › Box Internet › Associer",
                testLocal = "http://mafreebox.freebox.fr/api_version", testDistant = if (dom.isNotBlank()) "https://$dom:$port/api_version" else null,
                web = "http://mafreebox.freebox.fr", webDistant = if (dom.isNotBlank()) "https://$dom:$port" else null)
        }
        // Serveur Debian (SSH)
        run {
            val lan = listOf(s.sshHost.value.trim(), s.serveurIpLocale.value.trim()).firstOrNull { Serveur.ipPrivee(it) }
            val dist = s.sshHostDistant.value.trim().ifBlank { s.sshHost.value.trim().takeIf { it.isNotBlank() && !Serveur.ipPrivee(it) }.orEmpty() }
            val portL = s.sshPort.value.trim().ifBlank { "22" }
            val portD = s.sshPortDistant.value.trim().ifBlank { portL }
            l += Lien("serveur", "🖥️", "Serveur Debian (SSH)", "commandes, Docker, services, fichiers — seulement quand tu dis « serveur »",
                lan?.let { "$it:$portL" } ?: "—", if (dist.isNotBlank()) "$dist:$portD" else "aucune adresse distante dans les Réglages",
                ssh, "Réglages › Maison › Serveur",
                testLocal = lan?.let { "tcp://$it:$portL" }, testDistant = if (dist.isNotBlank()) "tcp://$dist:$portD" else null)
        }
        // Télé (connexion directe : débogage réseau par le serveur, API IP des Sony Bravia)
        if (s.teleIp.value.isNotBlank()) run {
            val ip = s.teleIp.value.trim().substringBefore(':')
            val bravia = s.teleType.value == "bravia"
            l += Lien("tele", "📺", "Télé" + (if (bravia) " (Sony Bravia)" else ""), "touches, applis, réglages, " + (if (bravia) "image et son, " else "") + "capture d'écran — connexion directe",
                "débogage réseau $ip:5555 (par le serveur)" + (if (bravia) " · API IP http://$ip/sony" else ""),
                if (ssh) "par le serveur de la maison (tunnel SSH)" else "— (configure l'accès SSH au serveur)", ssh,
                "Réglages › Maison › Télé", testLocal = "tcp://$ip:5555", testDistant = if (ssh) "tunnel://$ip:5555" else null,
                web = if (bravia) "http://$ip" else null)
        }
        // Imprimantes du réseau
        registre.filter { e -> e.optString("type").contains("imprim", true) || e.optJSONArray("ports")?.let { a -> (0 until a.length()).any { a.optInt(it) in setOf(631, 9100) } } == true }
            .take(4).forEach { e ->
                val ip = e.optString("ip")
                val ports = e.optJSONArray("ports")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
                val web = when { 80 in ports -> "http://$ip"; 443 in ports -> "https://$ip"; 8080 in ports -> "http://$ip:8080"; else -> "http://$ip" }
                l += Lien("imp_${e.optString("mac").ifBlank { ip }}", "🖨️", Registre.nomDe(e), "impression des documents de Jarvis (IPP, sans pilote) et page de réglages de l'imprimante",
                    "ipp://$ip:631 · réglages : $web", if (ssh) "par le serveur de la maison (tunnel SSH : impression et page de réglages)" else "— (configure l'accès SSH au serveur pour l'imprimer de dehors)", true,
                    testLocal = "tcp://$ip:631", testDistant = if (ssh) "tunnel://$ip:631" else null, web = web)
            }
        // Disque de la box (autre que Freebox)
        if (s.boxHost.value.isNotBlank() && s.freeboxAppToken.value.isBlank()) l += Lien("disque", "💽", "Disque de la box", "films et fichiers du disque branché sur la box (${s.boxProto.value.uppercase()})",
            "${s.boxProto.value}://${s.boxHost.value}", "—", true, "Réglages › Maison › Box Internet", testLocal = "tcp://${s.boxHost.value}:${if (s.boxProto.value == "ftp") s.boxPort.value.ifBlank { "21" } else "445"}")
        // Appareils rendus joignables à distance par JARVIS (caméras, NAS…)
        registre.filter { e -> (e.optJSONArray("distant")?.length() ?: 0) > 0 }.forEach { e ->
            val ip = e.optString("ip")
            // Home Assistant a déjà sa propre carte (avec cet accès) : pas de doublon.
            val acces = e.optJSONArray("distant")!!.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                .filterNot { ip == haLan && (it.optInt("port") == 8123 || it.optInt("port") == 0) }
            acces.forEach { d ->
                val port = d.optInt("port")
                val adr = d.optString("adresse").ifBlank { "port public ${d.optInt("externe")}" }
                l += Lien("dist_${ip}_$port", "🌍", Registre.nomDe(e), "${d.optString("service").ifBlank { AccesDistant.service(port) }} — accès ouvert par JARVIS",
                    "http://$ip:$port", adr, true,
                    testLocal = "tcp://$ip:$port",
                    // Adresse publique « 82.65.75.176:40631 » (imprimante…) : testée en direct, comme celles en http.
                    testDistant = adr.takeIf { it.startsWith("http") }
                        ?: adr.takeIf { Regex("^[\\w.-]+:\\d{2,5}$").matches(it) }?.let { "tcp://$it" }
                        ?: if (ssh) "tunnel://$ip:$port" else null,
                    web = if (port in setOf(80, 443, 8080, 8443, 8000, 5000, 9000, 8123, 32400)) "${if (port in setOf(443, 8443)) "https" else "http"}://$ip:$port" else null,
                    webDistant = adr.takeIf { it.startsWith("http") })
            }
        }
        return l
    }

    private val client = OkHttpClient.Builder().connectTimeout(2500, TimeUnit.MILLISECONDS).readTimeout(3500, TimeUnit.MILLISECONDS)
        .followRedirects(false).build()

    private fun portDe(url: String, defaut: Int) = runCatching { java.net.URI(url).port }.getOrDefault(-1).takeIf { it > 0 }
        ?: if (url.startsWith("https")) 443 else if (url.startsWith("http://") && !url.contains(Regex(":\\d+"))) 80 else defaut

    /** Le téléphone est-il sur le réseau de la maison ? (même réseau que le serveur, sinon que les appareils relevés) */
    fun aLaMaison(app: JarvisApp): Boolean {
        val lan = listOf(app.store.sshHost.value.trim(), app.store.serveurIpLocale.value.trim()).firstOrNull { Serveur.ipPrivee(it) }
        if (lan != null) return Serveur.telephoneSurReseau(lan)
        return runCatching { Registre.tous(app).take(10).any { Serveur.telephoneSurReseau(it.optString("ip")) } }.getOrDefault(false)
    }

    /**
     * Adresse à ouvrir pour se connecter DIRECTEMENT à l'interface d'un appareil : à la maison, son adresse locale ; dehors,
     * son adresse publique si un accès est ouvert, sinon un tunnel SSH par le serveur (127.0.0.1:port du téléphone).
     */
    fun adresseOuverture(app: JarvisApp, l: Lien): String {
        val web = l.web ?: throw IllegalStateException("${l.nom} n'a pas d'interface web connue.")
        if (aLaMaison(app)) return web
        l.webDistant?.let { return it }
        if (!Serveur.sshConfigure(app)) throw IllegalStateException("Dehors, ${l.nom} n'est joignable que par le serveur : configure l'accès SSH à distance (Réglages › Maison › Serveur).")
        val u = java.net.URI(web)
        val hote = u.host ?: throw IllegalStateException("adresse invalide : $web")
        val port = if (u.port > 0) u.port else if (u.scheme == "https") 443 else 80
        val local = Serveur.tunnelVers(app, hote, port)
        return "${u.scheme}://127.0.0.1:$local" + (u.rawPath ?: "")
    }

    /** L'adresse répond-elle ? « tcp://hôte:port » : simple connexion ; « tunnel://ip:port » : par le serveur ; sinon HTTP. */
    fun repond(app: JarvisApp, adresse: String): Boolean = if (adresse.startsWith("tunnel://")) runCatching {
        val (h, p) = adresse.removePrefix("tunnel://").let { it.substringBeforeLast(':') to it.substringAfterLast(':').toInt() }
        // Le serveur joint-il l'appareil ? (connexion TCP faite depuis le serveur lui-même)
        Serveur.exec(app, "timeout 4 bash -c ${Serveur.q("echo > /dev/tcp/$h/$p")} && echo JARVIS_OK", 12).second.contains("JARVIS_OK")
    }.getOrDefault(false) else repond(adresse)

    /**
     * Client du SIMPLE TEST de réponse : le certificat n'est pas vérifié (Home Assistant en HTTPS sur son adresse locale ou
     * par une redirection a un certificat au nom DuckDNS, que le test refusait → 🔴 alors que tout marchait). Rien n'est
     * envoyé : seule la réponse compte.
     */
    private val clientTest: OkHttpClient by lazy {
        val tm = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
        val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), java.security.SecureRandom()) }
        client.newBuilder().sslSocketFactory(ssl.socketFactory, tm).hostnameVerifier { _, _ -> true }.build()
    }

    private fun tcp(h: String, p: Int): Boolean = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(h, p), 2500) }; true }.getOrDefault(false)
    private fun web(adresse: String): Boolean = runCatching { clientTest.newCall(Request.Builder().url(adresse).build()).execute().use { it.code in 100..599 } }.getOrDefault(false)

    /**
     * L'adresse répond-elle ? « tcp://hôte:port » : simple connexion. Adresse web : la page, sinon la même en http ↔ https
     * (Home Assistant passé en HTTPS derrière une redirection notée en http), sinon le port lui-même.
     */
    fun repond(adresse: String): Boolean = runCatching {
        if (adresse.startsWith("tcp://")) {
            val (h, p) = adresse.removePrefix("tcp://").let { it.substringBeforeLast(':') to it.substringAfterLast(':').toInt() }
            return@runCatching tcp(h, p)
        }
        if (web(adresse)) return@runCatching true
        val autre = if (adresse.startsWith("https://")) "http://" + adresse.removePrefix("https://") else "https://" + adresse.removePrefix("http://")
        if (web(autre)) return@runCatching true
        val u = java.net.URI(adresse)
        tcp(u.host ?: return@runCatching false, if (u.port > 0) u.port else if (u.scheme == "https") 443 else 80)
    }.getOrDefault(false)

    /** Texte pour la conversation : « quels appareils contrôles-tu ? ». */
    fun texte(app: JarvisApp): String {
        val l = liste(app)
        return "# 🔗 Appareils liés à Jarvis\n" + runCatching { "_${Serveur.situation(app)}_\n" }.getOrDefault("") +
            l.joinToString("\n") { x ->
                "\n**${x.emoji} ${x.nom}** — ${x.role}" + (if (!x.configure) " · ⚙️ à configurer (${x.reglage})" else "") +
                    "\n- 🏠 Local : ${x.local}\n- 🌍 À distance : ${x.distant}"
            } + "\n\n[📡 Ouvrir l'onglet Wi-Fi › Liés à Jarvis](jarvis://ecran/wifi_lies)"
    }
}
