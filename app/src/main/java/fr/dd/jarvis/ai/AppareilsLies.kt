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
        /** Adresses à tester (null : rien à tester de ce côté). */
        val testLocal: String? = null,
        val testDistant: String? = null,
    )

    private fun hote(url: String) = url.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':')
    private fun http(url: String) = url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }

    /** Liste à partir des réglages et de l'inventaire Wi-Fi (sans réseau). */
    fun liste(app: JarvisApp): List<Lien> {
        val s = app.store
        val l = mutableListOf<Lien>()
        val registre = runCatching { Registre.tous(app) }.getOrDefault(emptyList())
        val ssh = Serveur.sshConfigure(app)

        // Home Assistant
        run {
            val ok = Serveur.haConfigure(app)
            val local = if (s.haUrl.value.isBlank()) "—" else http(s.haUrl.value)
            val hoteHa = hote(s.haUrl.value)
            val distantReg = registre.firstOrNull { it.optString("ip") == hoteHa }?.optJSONArray("distant")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("adresse").startsWith("http") }?.optString("adresse")
            }
            val distant = s.haUrlDistant.value.trim().takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "https://$it" }
                ?: distantReg
                ?: if (Serveur.ipPrivee(hoteHa) && ssh && (hoteHa == s.sshHost.value.trim() || hoteHa == s.serveurIpLocale.value.trim())) "tunnel SSH par le serveur (dernier recours)" else null
            l += Lien("ha", "🏠", "Home Assistant", "lumières, volets, prises, chauffage, télé, positions — par son API avec le jeton",
                "$local (API + jeton)".takeIf { s.haUrl.value.isNotBlank() } ?: "—",
                distant ?: "aucune adresse extérieure : dis « rends Home Assistant accessible à distance »",
                ok, "Réglages › Maison › Home Assistant",
                testLocal = if (s.haUrl.value.isNotBlank()) "$local/manifest.json" else null,
                testDistant = distant?.takeIf { it.startsWith("http") }?.let { "${it.trimEnd('/')}/manifest.json" })
        }
        // Plex
        run {
            val ok = s.plexToken.value.isNotBlank()
            val ip = runCatching { Plex.ipsLocales(app).firstOrNull() }.getOrNull()
            l += Lien("plex", "🎬", "Plex Media Server", "films, séries, lecture sur la télé et les lecteurs Plex — par son API",
                ip?.let { "http://$it:32400" } ?: "adresse locale pas encore relevée (analyse le Wi-Fi)",
                "connexion distante annoncée par plex.tv (adresse publique, sinon relais Plex)" + (if (ssh) " ; tunnel SSH en dernier recours" else ""),
                ok, "Réglages › Maison › Serveur › Plex",
                testLocal = ip?.let { "http://$it:32400/identity" }, testDistant = null)
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
                testLocal = "http://mafreebox.freebox.fr/api_version", testDistant = if (dom.isNotBlank()) "https://$dom:$port/api_version" else null)
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
        // Imprimantes du réseau
        registre.filter { e -> e.optString("type").contains("imprim", true) || e.optJSONArray("ports")?.let { a -> (0 until a.length()).any { a.optInt(it) in setOf(631, 9100) } } == true }
            .take(4).forEach { e ->
                val ip = e.optString("ip")
                l += Lien("imp_$ip", "🖨️", Registre.nomDe(e), "impression des documents de Jarvis (IPP, sans pilote)",
                    "ipp://$ip:631", if (ssh) "par le serveur (impression envoyée via le serveur de la maison)" else "—", true,
                    testLocal = "tcp://$ip:631", testDistant = null)
            }
        // Disque de la box (autre que Freebox)
        if (s.boxHost.value.isNotBlank() && s.freeboxAppToken.value.isBlank()) l += Lien("disque", "💽", "Disque de la box", "films et fichiers du disque branché sur la box (${s.boxProto.value.uppercase()})",
            "${s.boxProto.value}://${s.boxHost.value}", "—", true, "Réglages › Maison › Box Internet", testLocal = "tcp://${s.boxHost.value}:${if (s.boxProto.value == "ftp") s.boxPort.value.ifBlank { "21" } else "445"}")
        // Appareils rendus joignables à distance par JARVIS (caméras, NAS…)
        registre.filter { e -> (e.optJSONArray("distant")?.length() ?: 0) > 0 && e.optString("ip") != hote(s.haUrl.value) }.forEach { e ->
            val ip = e.optString("ip")
            val acces = e.optJSONArray("distant")!!.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            acces.forEach { d ->
                val port = d.optInt("port")
                val adr = d.optString("adresse").ifBlank { "port public ${d.optInt("externe")}" }
                l += Lien("dist_${ip}_$port", "🌍", Registre.nomDe(e), "${d.optString("service").ifBlank { AccesDistant.service(port) }} — accès ouvert par JARVIS",
                    "http://$ip:$port", adr, true,
                    testLocal = "tcp://$ip:$port", testDistant = adr.takeIf { it.startsWith("http") })
            }
        }
        return l
    }

    private val client = OkHttpClient.Builder().connectTimeout(2500, TimeUnit.MILLISECONDS).readTimeout(3500, TimeUnit.MILLISECONDS)
        .followRedirects(false).build()

    /** L'adresse répond-elle ? (« tcp://hôte:port » : simple connexion ; sinon requête HTTP, toute réponse compte). */
    fun repond(adresse: String): Boolean = runCatching {
        if (adresse.startsWith("tcp://")) {
            val (h, p) = adresse.removePrefix("tcp://").let { it.substringBeforeLast(':') to it.substringAfterLast(':').toInt() }
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(h, p), 2500) }
            true
        } else client.newCall(Request.Builder().url(adresse).build()).execute().use { it.code in 100..599 }
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
