package fr.dd.jarvis.ai

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import fr.dd.jarvis.JarvisApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Serveur personnel : SSH (commandes, fichiers) et Home Assistant (API REST + fichiers de configuration YAML). */
object Serveur {
    private val http = OkHttpClient.Builder()
        .dns(DnsMemoire)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------ SSH

    fun sshConfigure(app: JarvisApp) = app.store.sshHost.value.isNotBlank() && app.store.sshUser.value.isNotBlank() &&
        (app.store.sshPass.value.isNotBlank() || app.store.sshKey.value.isNotBlank())

    /** Autre machine à joindre en SSH avec des identifiants donnés par l'utilisateur (sinon le serveur des Réglages). */
    data class Cible(val hote: String, val port: Int, val user: String, val pass: String, val jump: Boolean = false)

    /** Traduit l'erreur technique JSch en explication compréhensible (mauvais mot de passe, hôte injoignable, port fermé…). */
    fun expliquer(e: Exception, hote: String, port: Int): String {
        val m = (e.message ?: "").lowercase()
        return when {
            e is java.net.UnknownHostException -> "Adresse « $hote » introuvable (faute de frappe, ou nom qui ne se résout pas depuis ce réseau)."
            m.contains("auth fail") || m.contains("auth cancel") -> "Authentification refusée : utilisateur, mot de passe ou clé incorrects (vérifie Réglages › Maison › Serveur)."
            e is java.net.ConnectException || m.contains("connection refused") -> "Connexion refusée par $hote:$port — rien n'écoute sur ce port, ou un pare-feu bloque SSH."
            e is java.net.NoRouteToHostException -> "$hote est injoignable depuis ce réseau (hors ligne, mauvaise IP, ou réseau différent)."
            e is java.net.SocketTimeoutException || m.contains("timeout") -> "Pas de réponse de $hote:$port dans le délai imparti (pare-feu, port fermé, ou mauvaise adresse distante/redirection de port)."
            m.contains("host key") -> "L'empreinte du serveur a changé (réinstallation ?) — efface « Empreinte mémorisée » dans Réglages › Maison › Serveur et réessaie."
            m.contains("key") && m.contains("invalid") -> "Clé privée invalide ou mal collée (format PEM/OpenSSH attendu)."
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    private fun ouvrir(app: JarvisApp, cible: Cible? = null): Session {
        invaliderConnexionsSiReseauChange(app)
        if (cible != null) {
            val s = JSch().getSession(cible.user, cible.hote, cible.port)
            s.setPassword(cible.pass); s.setConfig("StrictHostKeyChecking", "no"); s.connect(12000); return s
        }
        val st = app.store
        val jsch = JSch()
        if (st.sshKey.value.isNotBlank()) {
            jsch.addIdentity("jarvis", st.sshKey.value.trim().replace("\r", "").plus("\n").toByteArray(), null,
                st.sshPass.value.takeIf { it.isNotBlank() }?.toByteArray())
        }
        val local = st.sshHost.value.trim() to (st.sshPort.value.toIntOrNull() ?: 22)
        val distant = st.sshHostDistant.value.trim().takeIf { it.isNotBlank() }
            ?.let { it to (st.sshPortDistant.value.toIntOrNull() ?: local.second) }
            ?: local.takeIf { it.first.isNotBlank() && !ipPrivee(it.first) }
        val lanIp = st.serveurIpLocale.value.trim().takeIf { ipPrivee(it) }
        // Une adresse locale n'est prioritaire que si Android confirme une route Wi-Fi/VPN et que SSH répond dessus.
        // Les IP privées attribuées par l'opérateur mobile ne sont jamais utilisées pour conclure « même réseau ».
        val lan = buildList {
            if (lanIp != null) { add(lanIp to local.second); add(lanIp to 22) }
            if (ipPrivee(local.first)) { add(local.first to local.second); add(local.first to 22) }
        }.distinct()
        val surReseauMaison = (lan + listOf(local)).any { (hote, port) ->
            ipPrivee(hote) && telephoneSurReseau(app, hote, port)
        }
        val ordre = ordreCiblesSsh(local, distant, lan, surReseauMaison)
        if (ordre.isEmpty()) throw IllegalStateException(
            "Le téléphone n'a pas de route Wi-Fi/VPN vers le serveur et aucune adresse SSH distante n'est configurée. " +
                "Renseigne l'adresse distante dans Réglages › Maison › Serveur (nom DNS ou IP publique avec port SSH redirigé), ou active WireGuard."
        )
        var erreur: Exception? = null
        val essais = mutableListOf<String>()
        for ((i, cibleAdr) in ordre.withIndex()) {
            val s = jsch.getSession(st.sshUser.value.trim(), cibleAdr.first, cibleAdr.second)
            // Une cible privée doit utiliser exactement le réseau Wi-Fi/VPN qui a validé sa route ;
            // ne laisse pas le socket JSch repartir silencieusement par le réseau mobile par défaut.
            if (ipPrivee(cibleAdr.first)) {
                val reseauCible = reseauPourCible(app, cibleAdr.first, cibleAdr.second)
                if (reseauCible == null) {
                    essais += "${cibleAdr.first}:${cibleAdr.second} → route Wi-Fi/VPN devenue indisponible"
                    erreur = java.net.NoRouteToHostException("Route Wi-Fi/VPN indisponible vers ${cibleAdr.first}")
                    continue
                }
                s.setSocketFactory(socketFactoryLieeAuReseau(reseauCible, if (i < ordre.size - 1) 5_000 else 12_000))
            }
            // Si une ancienne clé privée est encore renseignée, ne pas empêcher le repli sur le mot de passe.
            // JSch n'essaie l'authentification password que si le mot de passe est explicitement fourni à la session.
            if (st.sshPass.value.isNotBlank()) s.setPassword(st.sshPass.value)
            s.setConfig("StrictHostKeyChecking", "no")
            s.setConfig("PreferredAuthentications", when {
                st.sshKey.value.isNotBlank() && st.sshPass.value.isNotBlank() -> "publickey,password,keyboard-interactive"
                st.sshKey.value.isNotBlank() -> "publickey"
                else -> "password,keyboard-interactive,publickey"
            })
            try {
                s.connect(if (i < ordre.size - 1) 5000 else 12000)
            } catch (e: Exception) {
                runCatching { s.disconnect() }
                essais += "${cibleAdr.first}:${cibleAdr.second} → ${expliquer(e, cibleAdr.first, cibleAdr.second)}"
                erreur = e
                continue
            }
            derniereAdresse = cibleAdr.first to System.currentTimeMillis()
            // Empreinte du serveur mémorisée à la première connexion ; refus si elle change (protège d'un faux serveur).
            val fp = s.hostKey.getFingerPrint(jsch)
            val connu = st.sshFp.value
            if (connu.isBlank()) st.sshFp.set(fp)
            else if (connu != fp) {
                s.disconnect()
                val mismatch = IllegalStateException("Empreinte SSH inattendue sur ${cibleAdr.first}:${cibleAdr.second} ($fp au lieu de $connu).")
                essais += "${cibleAdr.first}:${cibleAdr.second} → hôte SSH différent (empreinte non reconnue)"
                erreur = mismatch
                // Un Wi-Fi tiers peut réutiliser le même sous-réseau privé : on essaie l'adresse distante,
                // mais elle devra présenter exactement la même empreinte connue.
                continue
            }
            return s
        }
        if (essais.size > 1) throw IllegalStateException("Connexion SSH impossible par toutes les adresses connues : " + essais.joinToString(" ; "), erreur)
        throw erreur ?: IllegalStateException("Connexion SSH impossible")
    }

    /** Compare deux IPv4 selon le vrai préfixe réseau fourni par Android (pas un /24 supposé). */
    internal fun memeSousReseau(ipA: String, ipB: String, prefixe: Int): Boolean {
        if (prefixe !in 0..32) return false
        fun octets(ip: String): IntArray? {
            val parties = ip.trim().split(".")
            if (parties.size != 4) return null
            val valeurs = parties.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
            return valeurs.toIntArray()
        }
        val a = octets(ipA) ?: return false
        val b = octets(ipB) ?: return false
        var bits = prefixe
        for (i in 0..3) {
            val masque = when { bits >= 8 -> 255; bits <= 0 -> 0; else -> (255 shl (8 - bits)) and 255 }
            if ((a[i] and masque) != (b[i] and masque)) return false
            bits -= 8
        }
        return true
    }

    /**
     * Confirme l'accès au serveur depuis un réseau Wi-Fi ou VPN Android.
     * Les interfaces cellulaires sont ignorées : les opérateurs attribuent aussi des adresses privées 10.x.x.x.
     * Avec [port], un test TCP lié au réseau Android confirme que SSH/API répond par cette route.
     */
    /** Renvoie le réseau Android Wi-Fi/VPN qui possède réellement la route vers cette cible. */
    private fun reseauPourCible(app: JarvisApp, ipServeur: String, port: Int? = null): android.net.Network? = runCatching {
        val parties = ipServeur.trim().split(".")
        if (parties.size != 4) return null
        val valeurs = parties.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
        val cible = InetAddress.getByAddress(valeurs.map { it.toByte() }.toByteArray())
        val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return null
        val reseaux = (cm.allNetworks.toList() + listOfNotNull(cm.activeNetwork)).distinct()
        reseaux.firstOrNull { reseau ->
            val caps = cm.getNetworkCapabilities(reseau) ?: return@firstOrNull false
            val wifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            val vpn = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            if (!wifi && !vpn) return@firstOrNull false
            val lp = cm.getLinkProperties(reseau) ?: return@firstOrNull false
            val memeLan = lp.linkAddresses.any { adresse ->
                adresse.address is java.net.Inet4Address &&
                    memeSousReseau(ipServeur, adresse.address.hostAddress.orEmpty(), adresse.prefixLength)
            }
            val routeSpecifique = lp.routes.any { it.destination.prefixLength > 0 && it.matches(cible) }
            val routeVpn = vpn && lp.routes.any { it.matches(cible) }
            if (!memeLan && !routeSpecifique && !routeVpn) return@firstOrNull false
            if (port == null) return@firstOrNull true
            if (port !in 1..65535) return@firstOrNull false
            runCatching {
                reseau.socketFactory.createSocket().use { socket ->
                    socket.connect(InetSocketAddress(cible, port), 900)
                    true
                }
            }.getOrDefault(false)
        }
    }.getOrNull()

    /** SocketFactory JSch explicitement liée au réseau Android choisi (Wi-Fi ou VPN). */
    private fun socketFactoryLieeAuReseau(reseau: android.net.Network, delaiMs: Int): com.jcraft.jsch.SocketFactory =
        object : com.jcraft.jsch.SocketFactory {
            override fun createSocket(host: String, port: Int): Socket {
                val adresse = reseau.getByName(host)
                return reseau.socketFactory.createSocket().apply {
                    connect(InetSocketAddress(adresse, port), delaiMs)
                }
            }

            override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
            override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
        }

    /** Confirme la route et, si demandé, la joignabilité TCP sur le réseau Wi-Fi/VPN correspondant. */
    fun telephoneSurReseau(app: JarvisApp, ipServeur: String, port: Int? = null): Boolean =
        reseauPourCible(app, ipServeur, port) != null

    /** Adresses IPv4 du téléphone : (interface, adresse). wlan = Wi-Fi ; rmnet / ccmni = données mobiles. */
    fun adressesTelephone(): List<Pair<String, String>> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { n -> n.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().map { n.name to it.hostAddress.orEmpty() } }
    }.getOrDefault(emptyList())

    /** Situation réseau en clair : à la maison (connexion locale) ou dehors (connexion à distance), et par quelle adresse. */
    fun situation(app: JarvisApp): String {
        val st = app.store
        val lan = listOf(st.serveurIpLocale.value.trim(), st.sshHost.value.trim()).firstOrNull { ipPrivee(it) }
        val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val vpn = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        val mobile = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val wifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true ||
            cm?.allNetworks?.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true } == true
        val distant = st.sshHostDistant.value.trim().ifBlank { st.sshHost.value.trim().takeIf { !ipPrivee(it) }.orEmpty() }
        val surMaison = lan != null && listOf(st.sshPort.value.toIntOrNull() ?: 22, 22).distinct()
            .any { telephoneSurReseau(app, lan, it) }
        val reseau = when { vpn -> "VPN actif"; mobile -> "données mobiles"; wifi -> "Wi-Fi"; else -> "réseau inconnu" }
        val ou = when {
            surMaison -> "🏠 accès local confirmé (route Wi-Fi/VPN et SSH joignable) → connexion LOCALE au serveur ($lan)"
            lan == null && distant.isNotBlank() -> "🌍 réseau du téléphone : $reseau → connexion SSH À DISTANCE par $distant (aucune IP LAN enregistrée)"
            lan == null -> "réseau du téléphone : $reseau (adresse locale du serveur pas encore relevée)"
            else -> "🌍 téléphone hors du réseau de la maison ($reseau) → connexion À DISTANCE" +
                (if (distant.isNotBlank()) " par $distant" else " : aucune adresse SSH distante configurée")
        }
        val d = derniereAdresse?.let { (adr, quand) -> " · dernière connexion SSH réussie par $adr il y a ${(System.currentTimeMillis() - quand) / 60_000} min" }.orEmpty()
        return ou + d
    }

    /** Adresse qui a fonctionné en dernier (et quand). */
    @Volatile private var derniereAdresse: Pair<String, Long>? = null

    /** Ordre strict : LAN seulement après confirmation Wi-Fi/VPN ; hors LAN, ne tente que l'adresse distante. */
    internal fun ordreCiblesSsh(
        local: Pair<String, Int>,
        distant: Pair<String, Int>?,
        lan: List<Pair<String, Int>>,
        surReseauMaison: Boolean
    ): List<Pair<String, Int>> {
        val candidates = if (surReseauMaison) lan + listOf(local) + listOfNotNull(distant)
            else listOfNotNull(distant ?: local.takeIf { it.first.isNotBlank() && !ipPrivee(it.first) })
        return candidates.filter { it.first.isNotBlank() && it.second in 1..65535 }.distinct()
    }

    // Session SSH principale gardée ouverte quelques minutes : les commandes suivantes ne refont ni la connexion ni l'authentification.
    @Volatile private var principale: Session? = null
    @Volatile private var principaleCle = ""
    @Volatile private var principaleUtilisee = 0L
    private const val INACTIVITE = 4 * 60_000L

    private fun signatureReseau(app: JarvisApp): String = runCatching {
        val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val active = cm.activeNetwork?.toString().orEmpty()
        val reseaux = cm.allNetworks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            val lp = cm.getLinkProperties(n)
            val transports = listOf(
                "wifi" to android.net.NetworkCapabilities.TRANSPORT_WIFI,
                "mobile" to android.net.NetworkCapabilities.TRANSPORT_CELLULAR,
                "vpn" to android.net.NetworkCapabilities.TRANSPORT_VPN,
                "ethernet" to android.net.NetworkCapabilities.TRANSPORT_ETHERNET
            ).filter { caps.hasTransport(it.second) }.joinToString("+") { it.first }
            "$transports:${lp?.linkAddresses?.joinToString(",") { "${it.address.hostAddress}/${it.prefixLength}" }}:${lp?.routes?.joinToString(",") { it.destination.toString() }}"
        }.sorted()
        active + "|" + reseaux.joinToString(";")
    }.getOrDefault("réseau-inconnu")

    @Volatile private var derniereSignatureReseau: String? = null

    /** Une bascule Wi-Fi/4G/VPN invalide les sessions et tunnels JSch encore marqués connectés. */
    @Synchronized private fun invaliderConnexionsSiReseauChange(app: JarvisApp) {
        val signature = signatureReseau(app)
        val precedente = derniereSignatureReseau
        if (precedente != null && precedente != signature) {
            principale?.let { runCatching { it.disconnect() } }
            principale = null
            principaleCle = ""
            (tunnels.values.map { it.first } + tunnelsLan.values.map { it.first }).distinct().forEach {
                runCatching { it.disconnect() }
            }
            tunnels.clear()
            tunnelsLan.clear()
        }
        derniereSignatureReseau = signature
    }

    private fun cleReglages(app: JarvisApp) = with(app.store) {
        listOf(sshHost.value, sshPort.value, sshHostDistant.value, sshPortDistant.value, sshUser.value, sshPass.value.hashCode(), sshKey.value.hashCode()).joinToString("|")
    }

    @Synchronized private fun sessionPrincipale(app: JarvisApp): Session {
        invaliderConnexionsSiReseauChange(app)
        val cle = cleReglages(app)
        principale?.let { s ->
            if (s.isConnected && cle == principaleCle && System.currentTimeMillis() - principaleUtilisee < INACTIVITE) {
                principaleUtilisee = System.currentTimeMillis(); return s
            }
            runCatching { s.disconnect() }
        }
        val s = ouvrir(app)
        runCatching { s.setServerAliveInterval(15_000); s.serverAliveCountMax = 2 }
        principale = s; principaleCle = cle; principaleUtilisee = System.currentTimeMillis()
        return s
    }

    @Synchronized private fun oublierPrincipale(s: Session) {
        if (principale === s) { runCatching { s.disconnect() }; principale = null }
    }

    /** Ferme la session gardée ouverte (réglages modifiés, test de connexion). */
    @Synchronized fun fermerSession() { principale?.let { runCatching { it.disconnect() } }; principale = null }

    private val tunnels = HashMap<Int, Pair<Session, Int>>()

    /**
     * Tunnel SSH persistant vers un port du serveur LUI-MÊME (Ollama, coffre-fort… : joignables même si le port n'est
     * pas ouvert sur la box ni dans le pare-feu) ; renvoie le port local (127.0.0.1) à utiliser. Réutilisé tant que la
     * session SSH reste ouverte.
     */
    @Synchronized
    fun tunnel(app: JarvisApp, portDistant: Int): Int {
        invaliderConnexionsSiReseauChange(app)
        tunnels[portDistant]?.let { (s, p) -> if (s.isConnected) return p; runCatching { s.disconnect() } }
        val s = try { ouvrir(app) } catch (e: Exception) {
            throw IllegalStateException(expliquer(e, app.store.sshHost.value, app.store.sshPort.value.toIntOrNull() ?: 22), e)
        }
        runCatching { s.setServerAliveInterval(30_000) }
        val p = s.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", portDistant)
        tunnels[portDistant] = s to p
        return p
    }

    fun tunnelOllama(app: JarvisApp, portDistant: Int): Int = tunnel(app, portDistant)

    /** Ferme le tunnel vers [portDistant] (session SSH morte après une nuit de veille) : le prochain appel en rouvre un. */
    fun fermerTunnel(portDistant: Int) { synchronized(this) { tunnels.remove(portDistant) }?.let { (s, _) -> runCatching { s.disconnect() } } }

    private val tunnelsLan = java.util.concurrent.ConcurrentHashMap<String, Pair<com.jcraft.jsch.Session, Int>>()

    /**
     * Tunnel vers un appareil du réseau de la maison ([hote]:[port], ex. l'imprimante 192.168.1.30:80), en passant par le
     * serveur : depuis n'importe où, l'appareil répond sur 127.0.0.1:<port renvoyé> du téléphone, sans rien ouvrir sur la box.
     */
    @Synchronized
    fun tunnelVers(app: JarvisApp, hote: String, port: Int): Int {
        invaliderConnexionsSiReseauChange(app)
        val k = "$hote:$port"
        tunnelsLan[k]?.let { (s, p) -> if (s.isConnected) return p; runCatching { s.disconnect() } }
        val s = try { ouvrir(app) } catch (e: Exception) {
            throw IllegalStateException(expliquer(e, app.store.sshHost.value, app.store.sshPort.value.toIntOrNull() ?: 22), e)
        }
        runCatching { s.setServerAliveInterval(30_000) }
        val p = s.setPortForwardingL("127.0.0.1", 0, hote, port)
        tunnelsLan[k] = s to p
        return p
    }

    /** Adresse IPv4 du serveur sur le réseau de la maison (192.168.x.x…), lue par SSH. */
    fun ipLocaleServeur(app: JarvisApp): String? = runCatching {
        exec(app, "hostname -I 2>/dev/null; ip -4 -o addr show scope global 2>/dev/null | awk '{print \$4}'", 15).second
            .split(Regex("[\\s/]+")).filter { ipPrivee(it) }
            // Les 172.x sont souvent les réseaux internes de Docker : la box est en 192.168.x ou 10.x.
            .sortedBy { if (it.startsWith("192.168.")) 0 else if (it.startsWith("10.")) 1 else 2 }.firstOrNull()
    }.getOrNull()

    fun ipPrivee(ip: String) = Regex("^(10\\.\\d+|192\\.168|172\\.(1[6-9]|2\\d|3[01]))\\.\\d+\\.\\d+$").matches(ip.trim())

    private fun <T> session(app: JarvisApp, cible: Cible? = null, f: (Session) -> T): T {
        val hote = cible?.hote ?: app.store.sshHost.value; val port = cible?.port ?: (app.store.sshPort.value.toIntOrNull() ?: 22)
        try {
            if (cible != null && cible.jump) {
                // Rebond par le serveur : tunnel SSH vers une machine de SON réseau local (accessible de n'importe où si le serveur l'est).
                val main = sessionPrincipale(app)
                val lp = main.setPortForwardingL(0, cible.hote, cible.port)
                try {
                    val s2 = JSch().getSession(cible.user, "127.0.0.1", lp)
                    s2.setPassword(cible.pass); s2.setConfig("StrictHostKeyChecking", "no"); s2.connect(12000)
                    try { return f(s2) } finally { runCatching { s2.disconnect() } }
                } finally { runCatching { main.delPortForwardingL(lp) } }
            }
            if (cible != null) {
                val s = ouvrir(app, cible)
                try { return f(s) } finally { runCatching { s.disconnect() } }
            }
            // Serveur principal : session réutilisée ; si elle était tombée entre-temps, une seule reconnexion.
            val s = sessionPrincipale(app)
            return try { f(s) } catch (e: com.jcraft.jsch.JSchException) {
                oublierPrincipale(s)
                if (!Regex("session is down|channel is not opened|not connected|socket|closed", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())) throw e
                f(sessionPrincipale(app))
            }
        } catch (e: Exception) {
            val detail = if (e is IllegalStateException && e.message?.startsWith("Connexion SSH impossible par") == true) e.message!! else expliquer(e, hote, port)
            // Les identifiants SONT dans Jarvis : l'IA ne doit pas les redemander mais traiter le problème de connexion.
            throw IllegalStateException("$detail (Identifiants SSH bien enregistrés dans Jarvis — ne pas les redemander ; ${situation(app)}.)", e)
        }
    }

    /**
     * Commande avec « sudo » : TOUTE la commande passe en root en une seule fois (`sudo -S bash -c '…'`, mot de passe
     * envoyé une fois sur l'entrée standard). Avant, chaque « sudo » d'une même ligne redemandait le mot de passe
     * (sans terminal) et seule la première partie s'exécutait. Testable.
     */
    internal fun commandeSudo(commande: String, root: Boolean): String {
        if (!Regex("\\bsudo\\b").containsMatchIn(commande)) return commande
        // « sudo -n » ne demande jamais de mot de passe : commande laissée telle quelle (ex. relevé des services).
        if (Regex("\\bsudo\\s+-n\\b").containsMatchIn(commande)) return commande
        // « sudo -u autre … » : on garde chaque sudo tel quel (mot de passe lu par le premier).
        if (Regex("\\bsudo\\s+(-[A-Za-z]+\\s+)*-u\\b").containsMatchIn(commande)) return commande.replace(Regex("\\bsudo\\s+(?!-S)"), "sudo -S -p '' ")
        val sans = commande.replace(Regex("\\bsudo\\s+(-[A-Za-z]+\\s+(''\\s+)?)*"), "")
        // HOME de l'utilisateur conservé : « ~ » et les chemins relatifs à son dossier restent les mêmes qu'avant.
        return if (root) sans else "sudo -S -p '' env HOME=\"\$HOME\" bash -c " + q(sans)
    }

    /** Exécute une commande ; « sudo » utilise le mot de passe enregistré. Retourne (code de sortie, sortie). */
    fun exec(app: JarvisApp, commande: String, timeoutS: Int = 60, cible: Cible? = null): Pair<Int, String> = session(app, cible) { s ->
        val sudo = Regex("\\bsudo\\b").containsMatchIn(commande)
        val cmd = commandeSudo(commande, (cible?.user ?: app.store.sshUser.value).trim() == "root")
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand("export LANG=C.UTF-8; $cmd")
        val out = ByteArrayOutputStream()
        ch.setInputStream(if (sudo && (cible?.pass ?: app.store.sshPass.value).isNotBlank()) ByteArrayInputStream(((cible?.pass ?: app.store.sshPass.value) + "\n").toByteArray()) else null)
        ch.setErrStream(out)
        val inp = ch.inputStream
        ch.connect(10000)
        val buf = ByteArray(4096)
        val fin = System.currentTimeMillis() + timeoutS * 1000L
        while (true) {
            while (inp.available() > 0) { val n = inp.read(buf, 0, buf.size); if (n < 0) break; out.write(buf, 0, n) }
            if (ch.isClosed) { if (inp.available() > 0) continue; break }
            if (System.currentTimeMillis() > fin) { out.write("\n[… délai de ${timeoutS}s dépassé, commande interrompue]".toByteArray()); ch.disconnect(); return@session -1 to out.toString("UTF-8") }
            Thread.sleep(80)
        }
        val code = ch.exitStatus
        ch.disconnect()
        code to out.toString("UTF-8")
    }

    /**
     * Vérifie qu'une commande / un script bash est COMPLET avant de l'exécuter (guillemet non fermé, bloc if/for sans fin,
     * « EOF » de fin manquant, ligne coupée après « | » ou « && »…) : une réponse d'IA coupée en plein milieu donnait des
     * commandes tronquées, exécutées telles quelles. Renvoie null si la commande est complète, sinon l'explication.
     */
    internal fun commandeIncomplete(cmd: String): String? {
        val t = cmd.trimEnd()
        if (t.isEmpty()) return "commande vide"
        if (Regex("(\\\\|\\|\\||&&|\\|)$").containsMatchIn(t)) return "la commande s'arrête au milieu (« ${t.takeLast(20)} »)"
        // Documents « << FIN » : chaque délimiteur doit être refermé par une ligne seule.
        Regex("(?<!<)<<(?!<)-?\\s*['\"]?([A-Za-z_][A-Za-z0-9_]*)['\"]?").findAll(t).forEach { m ->
            val fin = m.groupValues[1]
            val apres = t.substring(m.range.last + 1)
            if (!Regex("(?m)^\\s*" + Regex.escape(fin) + "\\s*$").containsMatchIn(apres)) return "le bloc « <<$fin » n'est jamais refermé par une ligne « $fin »"
        }
        // Guillemets non refermés, if/for/case sans fin… : vérifiés par « bash -n » sur le serveur (voir syntaxeBash).
        return null
    }

    /** Contrôle de syntaxe bash d'un fichier déjà envoyé sur le serveur (null = correct). */
    private fun syntaxeBash(app: JarvisApp, fichier: String): String? {
        val r = exec(app, "bash -n ${q(fichier)} 2>&1; echo JARVIS_RC=\$?", 20)
        val rc = Regex("JARVIS_RC=(\\d+)").find(r.second)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val msg = r.second.replace(Regex("JARVIS_RC=\\d+"), "").replace(fichier, "commande").trim()
        return when {
            rc != 0 -> msg.ifBlank { "erreur de syntaxe" }.take(400)
            msg.contains("delimited by end-of-file", true) -> msg.take(400)
            else -> null
        }
    }

    private const val TACHES_CMD = "/tmp/jarvis_taches"

    /**
     * Exécute une commande qui doit aller JUSQU'AU BOUT (redémarrage, mise à jour, docker compose, apt…) : elle est
     * vérifiée (complète, syntaxe bash), puis lancée DÉTACHÉE sur le serveur (setsid nohup) — un délai dépassé ou une
     * coupure réseau ne l'interrompt plus. Avant, au bout du délai la connexion était coupée et la commande tuée en
     * plein milieu (ex. « docker compose up » arrêté entre la suppression et la recréation du conteneur : Home
     * Assistant restait arrêté). On attend sa fin jusqu'à [timeoutS] ; au-delà elle CONTINUE et on rend son journal
     * (code -2). Code -3 : commande refusée car incomplète (rien n'a été exécuté).
     */
    fun execComplet(app: JarvisApp, commande: String, timeoutS: Int = 120, nom: String = ""): Pair<Int, String> {
        commandeIncomplete(commande)?.let { return -3 to "⛔ Commande incomplète, rien n'a été exécuté : $it. Renvoie la commande COMPLÈTE." }
        val root = Regex("\\bsudo\\b").containsMatchIn(commande) && app.store.sshUser.value.trim() != "root"
        val script = if (root) commande.replace(Regex("\\bsudo\\s+(-[A-Za-z]+\\s+(''\\s+)?)*"), "") else commande
        val id = "c" + System.currentTimeMillis().toString(36)
        val d = TACHES_CMD
        exec(app, "mkdir -p $d && chmod 1777 $d 2>/dev/null; true", 15)
        envoyerOctets(app, "$d/$id.sh", script.toByteArray(Charsets.UTF_8))
        envoyerOctets(app, "$d/$id.nom", (nom.ifBlank { commande.lines().first().take(80) }).toByteArray(Charsets.UTF_8))
        syntaxeBash(app, "$d/$id.sh")?.let { err ->
            exec(app, "rm -f $d/$id.sh $d/$id.nom", 10)
            return -3 to "⛔ Commande mal formée ou coupée, rien n'a été exécuté :\n$err\nRenvoie la commande COMPLÈTE."
        }
        val lanceur = "cd ~ 2>/dev/null; chmod 700 $d/$id.sh; setsid nohup bash -c " + q("bash $d/$id.sh > $d/$id.log 2>&1 < /dev/null; echo \$? > $d/$id.rc; rm -f $d/$id.sh") +
            " > /dev/null 2>&1 < /dev/null & echo JARVIS_LANCE"
        val l = exec(app, (if (root) "sudo " else "") + lanceur, 25)
        if (!l.second.contains("JARVIS_LANCE")) return (l.first.takeIf { it != 0 } ?: 1) to "La commande n'a pas pu démarrer : ${l.second.trim().take(300)}"
        val fin = System.currentTimeMillis() + timeoutS * 1000L
        var pause = 300L
        while (System.currentTimeMillis() < fin) {
            Thread.sleep(pause); pause = (pause * 3 / 2).coerceAtMost(2000)
            val rc = runCatching { exec(app, "cat $d/$id.rc 2>/dev/null", 10).second.trim() }.getOrDefault("")
            if (rc.isNotEmpty()) {
                val log = runCatching { exec(app, "cat $d/$id.log 2>/dev/null | tail -c 60000; rm -f $d/$id.log $d/$id.rc $d/$id.nom", 20).second }.getOrDefault("")
                return (rc.lines().last().trim().toIntOrNull() ?: 1) to log
            }
        }
        val log = runCatching { exec(app, "tail -c 6000 $d/$id.log 2>/dev/null", 15).second }.getOrDefault("")
        return -2 to log + "\n[⏳ Toujours en cours sur le serveur après ${timeoutS} s — la commande N'EST PAS interrompue, elle va jusqu'au bout. Suivi : serveur_tache etat id=$id]"
    }

    @Volatile private var maisonCache: Pair<String, String>? = null

    /** Dossier personnel de l'utilisateur SSH sur le serveur ($HOME). */
    fun maison(app: JarvisApp): String {
        val cle = app.store.sshUser.value.trim() + "@" + app.store.sshHost.value.trim()
        maisonCache?.takeIf { it.first == cle }?.let { return it.second }
        val h = exec(app, "printf '%s' \"\$HOME\"", 20).second.trim().lines().lastOrNull()?.trim().orEmpty()
            .takeIf { it.startsWith("/") } ?: (if (app.store.sshUser.value.trim() == "root") "/root" else "/home/" + app.store.sshUser.value.trim())
        maisonCache = cle to h
        return h
    }

    /** Chemin absolu sur le serveur : « ~ », « ~/… » et les chemins relatifs partent du dossier personnel de l'utilisateur SSH
     *  (SFTP et les commandes entre guillemets ne développent pas « ~ » : sans ça, l'écriture visait un dossier « ~ » inexistant). */
    fun absolu(app: JarvisApp, chemin: String): String {
        val c = chemin.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        if (c.startsWith("/")) return c
        val home = maison(app).trimEnd('/')
        return when {
            c.isEmpty() || c == "~" -> home
            c.startsWith("~/") -> home + c.substring(1)
            c.startsWith("\$HOME/") -> home + c.removePrefix("\$HOME")
            else -> home + "/" + c.removePrefix("./")
        }
    }

    /** Commande essayée d'abord avec les droits de l'utilisateur, puis avec sudo (jamais les deux mélangés). */
    private fun essayer(app: JarvisApp, cmd: String, delai: Int = 60): Pair<Int, String> =
        exec(app, cmd, delai).takeIf { it.first == 0 } ?: exec(app, "sudo $cmd", delai)

    fun lire(app: JarvisApp, chemin0: String, max: Int = 400_000): String {
        val chemin = absolu(app, chemin0)
        return session(app) { s ->
            val c = s.openChannel("sftp") as ChannelSftp; c.connect(10000)
            try {
                val st = c.stat(chemin)
                if (st.isDir) throw IllegalStateException("$chemin est un dossier : utilise action=lister.")
                if (st.size > max) throw IllegalStateException("Fichier trop gros (${st.size / 1024} Ko).")
                c.get(chemin).use { String(it.readBytes(), Charsets.UTF_8) }
            } catch (e: com.jcraft.jsch.SftpException) {
                when (e.id) {
                    ChannelSftp.SSH_FX_PERMISSION_DENIED -> null
                    ChannelSftp.SSH_FX_NO_SUCH_FILE -> throw IllegalStateException("Fichier introuvable sur le serveur : $chemin")
                    else -> throw e
                }
            } finally { c.disconnect() }
        } ?: exec(app, "sudo cat " + q(chemin)).let { (code, out) ->
            if (code != 0) throw IllegalStateException("Lecture de $chemin refusée : ${out.trim().take(200)}")
            out
        }
    }

    /** Contenu binaire d'un fichier du serveur (SFTP ; fichier protégé : lu en root, transmis en base64). */
    fun lireOctets(app: JarvisApp, chemin: String, max: Long = 50_000_000L): ByteArray = session(app) { s ->
        val c = s.openChannel("sftp") as ChannelSftp; c.connect(10000)
        try {
            val st = c.stat(chemin)
            if (st.size > max) throw IllegalStateException("Fichier trop gros (${st.size / 1_048_576} Mo, limite ${max / 1_048_576} Mo).")
            c.get(chemin).use { it.readBytes() }
        } catch (e: com.jcraft.jsch.SftpException) {
            if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) null else throw IllegalStateException("Fichier introuvable : $chemin")
        } finally { c.disconnect() }
    } ?: exec(app, "sudo base64 -w0 " + q(chemin), 300).let { (code, out) ->
        if (code != 0) throw IllegalStateException("Lecture refusée : ${out.take(200)}")
        android.util.Base64.decode(out.trim().lines().last(), android.util.Base64.DEFAULT)
    }

    /**
     * Écrit un fichier (dossier créé au besoin, sauvegarde « .jarvis-bak-… » de l'ancienne version) et vérifie le contenu
     * relu. Sans les droits : réécrit en place avec sudo (propriétaire et droits du fichier conservés ; un nouveau fichier
     * prend le propriétaire de son dossier). Renvoie le chemin de la sauvegarde, ou null si le fichier n'existait pas.
     */
    fun ecrire(app: JarvisApp, chemin0: String, contenu: String, sauvegarde: Boolean = true): String? {
        val chemin = absolu(app, chemin0)
        val dossier = chemin.substringBeforeLast('/').ifBlank { "/" }
        if (exec(app, "test -d ${q(dossier)}").first != 0) {
            val r = essayer(app, "mkdir -p ${q(dossier)}")
            if (r.first != 0) throw IllegalStateException("Impossible de créer le dossier $dossier : ${r.second.trim().take(200)}")
        }
        val existe = exec(app, "test -e ${q(chemin)}").first == 0
        if (existe && exec(app, "test -d ${q(chemin)}").first == 0) throw IllegalStateException("$chemin est un dossier, pas un fichier.")
        var bak: String? = null
        if (sauvegarde && existe) {
            val b = "$chemin.jarvis-bak-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            if (essayer(app, "cp -p ${q(chemin)} ${q(b)}").first == 0) bak = b
        }
        val octets = contenu.toByteArray(Charsets.UTF_8)
        val direct = runCatching { envoyerOctets(app, chemin, octets) }.isSuccess
        if (!direct) {
            val tmp = "/tmp/jarvis_up_${System.currentTimeMillis()}"
            envoyerOctets(app, tmp, octets)
            val r = try {
                if (existe) exec(app, "sudo tee ${q(chemin)} < ${q(tmp)} > /dev/null")
                else exec(app, "sudo cp ${q(tmp)} ${q(chemin)} && sudo chown --reference=${q(dossier)} ${q(chemin)} && sudo chmod 644 ${q(chemin)}")
            } finally { runCatching { exec(app, "rm -f ${q(tmp)}") } }
            if (r.first != 0) throw IllegalStateException("Écriture de $chemin refusée : ${r.second.trim().take(300)}" +
                if (app.store.sshPass.value.isBlank() && app.store.sshUser.value.trim() != "root") " (aucun mot de passe sudo enregistré dans Réglages › Maison › Serveur)" else "")
        }
        // Vérification : le fichier relu doit être exactement celui qui a été écrit.
        val attendu = java.security.MessageDigest.getInstance("SHA-256").digest(octets).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val v = essayer(app, "sha256sum ${q(chemin)}", 30)
        if (v.first == 0 && !v.second.contains(attendu)) throw IllegalStateException("Vérification échouée : le contenu relu de $chemin ne correspond pas à ce qui a été écrit." + (bak?.let { " Ancienne version : $it" } ?: ""))
        return bak
    }

    fun envoyerOctets(app: JarvisApp, chemin: String, octets: ByteArray) = session(app) { s ->
        val c = s.openChannel("sftp") as ChannelSftp; c.connect(10000)
        try { c.put(ByteArrayInputStream(octets), chemin, ChannelSftp.OVERWRITE) } finally { c.disconnect() }
    }

    fun lister(app: JarvisApp, dossier: String): String = absolu(app, dossier).let { d ->
        exec(app, "ls -lAh --time-style=long-iso ${q(d)} | head -150").let { r ->
            if (r.first != 0 && r.second.contains("Permission denied", ignoreCase = true)) exec(app, "sudo ls -lAh --time-style=long-iso ${q(d)} | head -150").second else r.second
        }
    }

    /** Analyse le LAN du serveur Debian : sous-réseaux réellement configurés, table ARP/neigh et ports TCP.
     *  Seuls les appareils qui ont répondu (adresse MAC relevée, ou port ouvert) sont gardés. */
    fun scanReseau(app: JarvisApp): List<Reseau.Appareil> {
        val commande = "nets=\$(ip -4 route show scope link | awk '\$1 ~ /^[0-9]+\\./ {print \$1}' | sort -u); " +
            "for net in \$nets; do python3 -c 'import ipaddress,sys; n=ipaddress.ip_network(sys.argv[1],strict=False); print(\"\\n\".join(str(x) for x in n.hosts()) if n.num_addresses <= 1024 else \"\")' \$net; done | " +
            "while read ip; do (ping -c1 -W1 \$ip >/dev/null 2>&1) & while [ \$(jobs -rp | wc -l) -ge 48 ]; do wait -n 2>/dev/null || break; done; done; wait; sleep 1; " +
            // Seulement les voisins qui ont RÉPONDU (adresse MAC connue) : les entrées INCOMPLETE créées par le ping de chaque
            // adresse libre faisaient apparaître tout le sous-réseau (des dizaines d'« appareils » fantômes).
            "ip neigh show | awk '\$1 ~ /^[0-9]+\\./ && \$0 !~ /FAILED|INCOMPLETE/ {mac=\"\"; for(i=1;i<=NF;i++) if(\$i==\"lladdr\" && i<NF) mac=\$(i+1); if (mac != \"\") print \"N \"\$1\" \"mac}'; " +
            "for ip in \$(ip neigh show | awk '\$1 ~ /^[0-9]+\\./ && \$0 !~ /FAILED|INCOMPLETE/ && /lladdr/ {print \$1}' | sort -u); do " +
            "(for p in 22 80 443 445 631 9100 8123 8080 554 1883; do timeout 0.8 bash -c \"echo >/dev/tcp/\$ip/\$p\" 2>/dev/null && echo \"P \$ip \$p\"; done) & " +
            "while [ \$(jobs -rp | wc -l) -ge 48 ]; do wait -n 2>/dev/null || break; done; done; wait";
        val r = exec(app, commande, 180).second
        val mac = HashMap<String, String>(); val ports = HashMap<String, MutableList<Int>>()
        val vus = LinkedHashSet<String>()
        r.lines().forEach { l ->
            val t = l.trim().split(Regex("\\s+"))
            if (t.size >= 2 && t[0] == "N") { vus += t[1]; if (t.size >= 3 && t[2].matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))) mac[t[1]] = t[2] }
            if (t.size >= 3 && t[0] == "P") { vus += t[1]; ports.getOrPut(t[1]) { mutableListOf() }.add(t[2].toIntOrNull() ?: 0) }
        }
        return vus.filter { mac.containsKey(it) || ports.containsKey(it) }.sortedBy { ip -> ip.split(".").map { it.toIntOrNull() ?: 0 }.fold(0L) { a, b -> a * 256 + b } }.map { ip ->
            val p = ports[ip].orEmpty().distinct().sorted()
            val m = mac[ip].orEmpty()
            val type = Reseau.typeDeApp(p, "").let { t -> if (t == "❔ appareil" && m.isNotBlank()) Reseau.fabricant(m) ?: t else t }
            Reseau.Appareil(ip, "", p, "", type, if (m.isNotBlank()) "MAC $m" else "")
        }
    }

    // ------------------------------------------------------------------ Scan de ports (depuis le téléphone, sans SSH)

    /** Ports SSH non standards les plus courants (au cas où le port configuré est faux). */
    val PORTS_SSH_COURANTS = listOf(22, 222, 2222, 2022, 22022, 50022, 10022, 2200, 8022)
    /** Autres services serveur fréquents, utiles pour un diagnostic plus large. */
    val PORTS_SERVICES_COURANTS = listOf(80, 443, 8080, 8443, 8123, 3000, 9000, 9090, 32400, 445, 139, 21, 3306, 5432, 6379, 8096, 1883)

    /** Teste une liste de ports TCP sur [hote] DIRECTEMENT DEPUIS LE TÉLÉPHONE (ne passe pas par SSH, donc fonctionne même
     *  quand SSH ne se connecte pas) : utile pour retrouver le bon port quand celui des Réglages est faux. Ne marche que si
     *  le téléphone peut atteindre cette adresse (même réseau local que le serveur, ou port déjà redirigé vers l'extérieur). */
    fun scannerPorts(hote: String, ports: List<Int>): List<Pair<Int, Boolean>> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(24)
        return try {
            ports.map { p -> pool.submit(java.util.concurrent.Callable { p to fr.dd.jarvis.ai.Reseau.ouvertTcp(hote, p, 1200) }) }.map { it.get() }
        } finally { pool.shutdown() }
    }

    /** Si un port ouvert répond bien comme un serveur SSH, sa bannière commence par « SSH-… » ; sinon null. */
    fun banniereSsh(hote: String, port: Int, ms: Int = 1500): String? = try {
        java.net.Socket().use { sk ->
            sk.connect(java.net.InetSocketAddress(hote, port), ms)
            sk.soTimeout = ms
            sk.getInputStream().bufferedReader().readLine()?.takeIf { it.startsWith("SSH-") }
        }
    } catch (e: Exception) { null }

    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private val DANGER = Regex(
        "\\b(rm|rmdir|mkfs\\S*|dd|shred|reboot|shutdown|poweroff|halt|fdisk|parted|wipefs|userdel|passwd|chpasswd|iptables|ufw|kill|killall|pkill|truncate|mv|crontab)\\b" +
            "|docker\\s+(rm|rmi|system\\s+prune|volume\\s+rm|stop|kill|down|restart|compose\\s+down)|systemctl\\s+(stop|disable|mask|restart|reboot|poweroff)" +
            "|apt(-get)?\\s+(remove|purge|autoremove|upgrade|dist-upgrade)|>\\s*/(etc|boot|dev|var|usr)|chmod\\s+-R|chown\\s+-R|\\|\\s*(sudo\\s+)?(sh|bash)\\b|sed\\s+-i|tee\\s",
        RegexOption.IGNORE_CASE
    )
    fun dangereuse(cmd: String) = DANGER.containsMatchIn(cmd)

    // ------------------------------------------------------------------ Home Assistant (API REST)

    fun haConfigure(app: JarvisApp) = app.store.haUrl.value.isNotBlank() && app.store.haToken.value.isNotBlank()

    private fun base(app: JarvisApp) = app.store.haUrl.value.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }

    /** Adresse extérieure de Home Assistant : celle des réglages, sinon l'accès à distance ouvert depuis l'onglet Wi-Fi. */
    private fun haDistant(app: JarvisApp, hoteLocal: String, portLocal: Int): String? {
        app.store.haUrlDistant.value.trim().trimEnd('/').takeIf { it.isNotBlank() }?.let { return if (it.startsWith("http")) it else "https://$it" }
        return runCatching {
            Registre.tous(app).firstOrNull { it.optString("ip") == hoteLocal }?.optJSONArray("distant")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optInt("port") == portLocal }?.optString("adresse")?.takeIf { it.startsWith("http") }
            }
        }.getOrNull()
    }

    /** Adresse directe de l'instance Home Assistant (locale à la maison, extérieure dehors), sans tunnel SSH. */
    fun haBaseDirecte(app: JarvisApp): String {
        val b = base(app)
        val u = runCatching { java.net.URI(b) }.getOrNull() ?: return b
        val hote = u.host.orEmpty()
        if (!ipPrivee(hote) || telephoneSurReseau(app, hote, if (u.port > 0) u.port else if (u.scheme == "https") 443 else 80)) return b
        return haDistant(app, hote, if (u.port > 0) u.port else if (u.scheme == "https") 443 else 80) ?: b
    }

    /**
     * Adresse effective de l'API Home Assistant : TOUJOURS l'instance elle-même, avec le jeton — adresse locale à la
     * maison, adresse extérieure dehors (réglage, ou accès ouvert depuis l'onglet Wi-Fi). Le tunnel SSH par le serveur
     * n'est plus qu'un dernier recours (adresse locale, téléphone dehors, et aucune adresse extérieure connue).
     */
    private fun acces(app: JarvisApp): Pair<String, OkHttpClient> {
        val b = base(app)
        val u = runCatching { java.net.URI(b) }.getOrNull() ?: return b to http
        val hote = u.host.orEmpty()
        if (!ipPrivee(hote) || telephoneSurReseau(app, hote, if (u.port > 0) u.port else if (u.scheme == "https") 443 else 80)) return b to http
        val portLocal = if (u.port > 0) u.port else if (u.scheme == "https") 443 else 80
        haDistant(app, hote, portLocal)?.let { return it to http }
        val estServeur = hote == app.store.sshHost.value.trim() || hote == app.store.serveurIpLocale.value.trim()
        if (!estServeur || !sshConfigure(app)) return b to http
        val port = if (u.port > 0) u.port else if (u.scheme == "https") 443 else 80
        val lp = runCatching { tunnel(app, port) }.getOrElse { return b to http }
        val client = if (u.scheme == "https") http.newBuilder().hostnameVerifier { _, session ->
            javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(hote, session) || runCatching {
                (session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate)?.subjectAlternativeNames?.any { it.getOrNull(1) == hote } == true
            }.getOrDefault(false)
        }.build() else http
        return "${u.scheme}://127.0.0.1:$lp" + u.path.orEmpty().trimEnd('/') to client
    }

    // ------------------------------------------------------------------ états partagés + connexion préparée

    /**
     * Liste complète des états (/api/states) gardée quelques secondes : une même demande la lisait jusqu'à 3 fois
     * (réflexe télé / lumière, puis réflexe appareil, puis outil ha_entites du cerveau) — chaque fois tout l'inventaire
     * de la maison par le réseau. Toute action (POST) l'oublie aussitôt : un état n'est jamais relu périmé après un ordre.
     */
    private const val ETATS_DUREE_MS = 4_000L
    private class EtatsHa(val cle: String, val corps: String, val le: Long)
    @Volatile private var etatsHa: EtatsHa? = null
    private val generationEtats = java.util.concurrent.atomic.AtomicInteger()

    /** Oublie la liste d'états gardée (après une action faite hors de [ha] : administration, WebSocket…). */
    fun oublierEtatsHa() { generationEtats.incrementAndGet(); etatsHa = null }

    @Volatile private var prechauffeHaLe = 0L

    /**
     * Ouvre à l'avance la connexion vers Home Assistant (pendant que l'utilisateur parle) : la commande part ensuite sur
     * une connexion prête. Adresse directe seulement (jamais de tunnel SSH ici), au plus une fois par minute.
     */
    fun prechaufferHa(app: JarvisApp) {
        if (!haConfigure(app)) return
        val maintenant = System.currentTimeMillis()
        if (maintenant - prechauffeHaLe < 60_000L) return
        prechauffeHaLe = maintenant
        runCatching {
            val req = Request.Builder().url(haBaseDirecte(app) + "/api/").header("Authorization", "Bearer ${app.store.haToken.value.trim()}").get().build()
            http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build().newCall(req).execute().close()
        }
    }

    /** [frais] : relit toujours Home Assistant (attente d'une entité qui vient d'être créée, par exemple). */
    /**
     * DNS qui garde la dernière adresse trouvée : un raté passager de résolution (économiseur de batterie qui vient de
     * s'activer pendant une routine, Wi-Fi qui se reconnecte) ne coupe plus Home Assistant — l'adresse connue sert.
     */
    internal object DnsMemoire : okhttp3.Dns {
        private val connues = java.util.concurrent.ConcurrentHashMap<String, List<java.net.InetAddress>>()
        override fun lookup(hostname: String): List<java.net.InetAddress> = try {
            okhttp3.Dns.SYSTEM.lookup(hostname).also { if (it.isNotEmpty()) connues[hostname] = it }
        } catch (e: java.net.UnknownHostException) {
            connues[hostname]?.also { fr.dd.jarvis.voice.JournalVoix.noter("maison", "« $hostname » ne se résout pas pour l'instant : dernière adresse connue utilisée") } ?: throw e
        }
    }

    /** Délais différenciés : lectures d'état et commandes domestiques ne doivent pas hériter des 45 s d'une opération admin. */
    internal data class DelaisHa(val connexion: Long, val lecture: Long, val appel: Long)

    internal fun delaisHa(methode: String, chemin: String): DelaisHa = when {
        methode != "POST" && (chemin == "/api/states" || chemin.startsWith("/api/states/")) ->
            DelaisHa(connexion = 4, lecture = 8, appel = 10)
        methode == "POST" && chemin.startsWith("/api/services/") ->
            DelaisHa(connexion = 6, lecture = 12, appel = 15)
        else -> DelaisHa(connexion = 10, lecture = 40, appel = 45)
    }

    internal fun estLectureEtatRapide(methode: String, chemin: String) =
        methode != "POST" && (chemin == "/api/states" || chemin.startsWith("/api/states/"))

    private fun clientHa(client: OkHttpClient, methode: String, chemin: String): OkHttpClient {
        val d = delaisHa(methode, chemin)
        return if (d.connexion == 10L && d.lecture == 40L && d.appel == 45L) client else client.newBuilder()
            .connectTimeout(d.connexion, TimeUnit.SECONDS)
            .readTimeout(d.lecture, TimeUnit.SECONDS)
            .callTimeout(d.appel, TimeUnit.SECONDS)
            .build()
    }

    /** Attentes entre deux essais ; une lecture d'état rapide n'a qu'un seul nouvel essai court. */
    private val REESSAIS_HA = longArrayOf(1_500, 3_000)

    /**
     * Erreur réseau passagère : la requête n'a pas atteint Home Assistant (adresse non résolue, connexion refusée ou
     * impossible) — on peut la refaire sans risque. Un délai dépassé n'est refait que pour une lecture (un POST a pu
     * partir).
     */
    internal fun passager(e: Throwable, methode: String): Boolean = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException -> true
        is java.net.SocketTimeoutException -> methode != "POST"
        else -> false
    }

    fun ha(app: JarvisApp, methode: String, chemin: String, corps: JSONObject? = null, frais: Boolean = false): String {
        var essai = 0
        while (true) {
            try {
                return haUneFois(app, methode, chemin, corps, frais)
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                val lectureRapide = estLectureEtatRapide(methode, chemin)
                val maxEssais = if (lectureRapide) 1 else REESSAIS_HA.size
                if (passager(e, methode) && essai < maxEssais) {
                    val attente = if (lectureRapide) 500L else REESSAIS_HA[essai]
                    fr.dd.jarvis.voice.JournalVoix.noter("maison", "Home Assistant : ${e.javaClass.simpleName} sur $methode ${chemin.take(60)} → nouvel essai dans ${attente / 1000.0} s")
                    Thread.sleep(attente)
                    essai++
                    continue
                }
                throw erreurHa(app, e)
            }
        }
    }

    private fun erreurHa(app: JarvisApp, e: Exception): IllegalStateException = when (e) {
        is java.net.UnknownHostException -> IllegalStateException("Home Assistant : adresse introuvable (« ${app.store.haUrl.value} »), même après 3 essais. Si l'adresse est juste dans Réglages › Maison › Serveur, le réseau du téléphone était coupé à ce moment (économiseur de batterie, Wi-Fi qui se reconnecte) : réessaie dans quelques secondes.")
        is java.net.ConnectException, is java.net.NoRouteToHostException -> IllegalStateException("Home Assistant : connexion refusée sur « ${base(app)} ». Soit Home Assistant est arrêté (vérifie avec serveur_diagnostic), soit le port/l'adresse est faux dans Réglages › Maison › Serveur, soit le téléphone n'est pas sur le même réseau (et l'accès distant n'est pas configuré).")
        is java.net.SocketTimeoutException -> IllegalStateException("Home Assistant : pas de réponse (délai dépassé) sur « ${base(app)} ». Le serveur est peut-être éteint/inaccessible depuis ce réseau, ou surchargé.")
        is javax.net.ssl.SSLException -> IllegalStateException("Home Assistant : erreur TLS/SSL sur « ${base(app)} ». Si l'adresse commence par https:// mais que Home Assistant n'a pas de certificat valide configuré, utilise http:// à la place (Réglages › Maison › Serveur).")
        else -> IllegalStateException("Home Assistant : connexion impossible sur « ${base(app)} » (${e.javaClass.simpleName} : ${e.message}).")
    }

    private fun haUneFois(app: JarvisApp, methode: String, chemin: String, corps: JSONObject?, frais: Boolean): String {
        if (!haConfigure(app)) throw IllegalStateException("Home Assistant non configuré (Réglages › Maison › Serveur : adresse et jeton d'accès longue durée).")
        val (adresse, client) = acces(app)
        val listeEtats = methode != "POST" && chemin == "/api/states"
        val cle = adresse + "|" + app.store.haToken.value.trim().hashCode()
        if (listeEtats && !frais) etatsHa?.takeIf { it.cle == cle && System.currentTimeMillis() - it.le < ETATS_DUREE_MS }?.let { return it.corps }
        if (methode == "POST") oublierEtatsHa()
        val generation = generationEtats.get()
        val clientCible = clientHa(client, methode, chemin)
        val b = Request.Builder().url(adresse + chemin).header("Authorization", "Bearer ${app.store.haToken.value.trim()}")
        if (methode == "POST") b.post((corps?.toString() ?: "{}").toRequestBody("application/json".toMediaType())) else b.get()
        clientCible.newCall(b.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (methode == "POST") oublierEtatsHa()
            else if (listeEtats && r.isSuccessful && generation == generationEtats.get()) etatsHa = EtatsHa(cle, t, System.currentTimeMillis())
            if (r.code == 401) throw IllegalStateException("Home Assistant 401 : le jeton est invalide ou expiré. Dans Home Assistant, connecte-toi avec le compte qui possède les droits d'administration, puis crée un nouveau jeton d'accès longue durée dans ton profil et remplace-le dans JARVIS › Réglages › Maison › Serveur.")
            if (r.code == 403) throw IllegalStateException("Home Assistant 403 — action interdite pour le compte associé au jeton. Ce n'est pas une permission Android/Shizuku : le jeton reprend les droits du compte Home Assistant qui l'a créé. Vérifie que ce compte peut piloter cet appareil et, si nécessaire, crée un nouveau jeton depuis un compte administrateur. Détail HA : ${t.take(500)}")
            if (r.code == 404) throw IllegalStateException("Home Assistant 404 : service ou entité introuvable. Vérifie le domaine, le nom du service et l'identifiant exact de l'appareil. Détail HA : ${t.take(400)}")
            if (!r.isSuccessful) throw IllegalStateException("Home Assistant ${r.code} : ${t.take(500)}")
            return t
        }
    }

    /** Même appareil sous plusieurs noms (« télé » ↔ tv ↔ media_player…), et le domaine Home Assistant qu'il évoque. */
    private val SYNONYMES_HA: List<Pair<Set<String>, String?>> = listOf(
        setOf("tele", "tv", "television", "televiseur", "telly", "bravia", "freebox player", "chromecast", "android tv", "media player") to "media_player",
        setOf("enceinte", "speaker", "sonos", "google home", "nest", "echo", "alexa", "barre de son", "soundbar") to "media_player",
        setOf("lumiere", "lumieres", "lampe", "lampes", "eclairage", "ampoule", "spot", "light", "plafonnier", "led") to "light",
        setOf("chauffage", "radiateur", "thermostat", "clim", "climatisation", "pac", "climate") to "climate",
        // Poêle à granulés : la reconnaissance vocale entend souvent « poils », « poile »… ; intégrations par marque.
        setOf("poele", "poel", "poile", "poil", "poils", "poiles", "granule", "granules", "pellet", "pellets", "stove", "stufa",
            "mcz", "maestro", "palazzetti", "edilkamin", "rika", "firenet", "extraflame", "piazzetta", "ravelli", "jolly", "micronova",
            "aguaiot", "fumis", "duepi", "laminox", "cadel", "freepoint", "evacalor", "haas", "wodtke") to null,
        setOf("personne", "person", "localisation", "position", "tracker", "gps", "ou est") to "person",
        setOf("volet", "volets", "store", "stores", "rideau", "cover") to "cover",
        setOf("prise", "prises", "plug", "interrupteur", "switch") to "switch",
        setOf("temperature", "humidite", "capteur", "sensor") to "sensor",
        setOf("portail", "garage", "porte") to null,
        setOf("volume", "son") to "media_player",
    )

    /**
     * Filtre tolérant des entités Home Assistant : sans accents ni casse, « _ » = espace, par mots (avec synonymes),
     * les plus pertinentes d'abord. [entites] = (entity_id, friendly_name). Renvoie les index retenus.
     */
    internal fun filtrerEntites(entites: List<Pair<String, String>>, recherche: String, domaine: String): List<Int> {
        val d = domaine.trim().lowercase().removeSuffix(".")
        fun norm(x: String) = fr.dd.jarvis.societe.core.normaliser(x.replace('_', ' ').replace('.', ' '))
        val q = norm(recherche)
        val candidats = entites.indices.filter { d.isBlank() || entites[it].first.lowercase().startsWith("$d.") }
        if (q.isBlank()) return candidats.sortedBy { entites[it].first }
        val textes = candidats.associateWith { " " + norm(entites[it].first + " " + entites[it].second) + " " }
        // 1. La recherche telle quelle.
        candidats.filter { textes.getValue(it).contains(q) }.takeIf { it.isNotEmpty() }?.let { l -> return l.sortedBy { entites[it].first } }
        // 2. Mot à mot, avec synonymes ; score = nombre de mots de la recherche retrouvés.
        val vides = setOf("le", "la", "les", "de", "du", "des", "mon", "ma", "mes", "un", "une", "et", "a", "au", "aux", "en", "sur", "dans")
        val mots = q.split(' ').filter { it.length >= 2 && it !in vides }
        if (mots.isEmpty()) return emptyList()
        val groupes = mots.map { m -> setOf(m) + SYNONYMES_HA.filter { (g, _) -> m in g }.flatMap { it.first } }
        val domaines = mots.flatMap { m -> SYNONYMES_HA.filter { (g, _) -> m in g }.mapNotNull { it.second } }.toSet()
        return candidats.map { i ->
            val t = textes.getValue(i)
            val dom = entites[i].first.substringBefore('.')
            i to (groupes.count { g -> g.any { t.contains(" $it") } } * 2 + (if (dom in domaines) 1 else 0))
        }.filter { it.second >= 2 || (it.second == 1 && domaines.size == 1 && mots.size == 1) }
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { entites[it.first].first }).map { it.first }
    }

    /** Similarité de deux mots (bigrammes de lettres, 0..1) : tolère les fautes et les mots mal entendus. */
    internal fun similarite(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return 0.0
        val ba = a.windowed(2).toMutableList(); val bb = b.windowed(2)
        var commun = 0
        for (g in bb) { val i = ba.indexOf(g); if (i >= 0) { commun++; ba.removeAt(i) } }
        return 2.0 * commun / (a.length - 1 + b.length - 1)
    }

    /** Entités les plus proches d'une recherche qui n'a rien donné (mots ressemblants), pour proposer au lieu d'inventer. */
    internal fun proches(entites: List<Pair<String, String>>, recherche: String, n: Int = 8): List<Int> {
        fun norm(x: String) = fr.dd.jarvis.societe.core.normaliser(x.replace('_', ' ').replace('.', ' '))
        val mots = norm(recherche).split(' ').filter { it.length >= 3 }
        if (mots.isEmpty()) return emptyList()
        return entites.indices.map { i ->
            val ws = norm(entites[i].first + " " + entites[i].second).split(' ').filter { it.length >= 3 }
            i to mots.sumOf { m -> ws.maxOfOrNull { w -> maxOf(similarite(m, w), if (w.startsWith(m.take(4)) && m.length >= 4) 0.7 else 0.0) } ?: 0.0 } / mots.size
        }.filter { it.second >= 0.45 }.sortedByDescending { it.second }.take(n).map { it.first }
    }

    internal fun correspondEtatHa(etatBrut: String, filtre: String): Boolean {
        val e = fr.dd.jarvis.societe.core.normaliser(etatBrut)
        val f = fr.dd.jarvis.societe.core.normaliser(filtre)
        if (f.isBlank()) return true
        return when {
            f in setOf("indisponible", "injoignable", "hors ligne", "offline", "deconnecte", "deconnectee", "unavailable") -> e == "unavailable"
            f in setOf("inconnu", "unknown", "etat inconnu") -> e == "unknown"
            f in setOf("indisponible ou inconnu", "injoignable ou inconnu", "tous les problemes") -> e in setOf("unavailable", "unknown")
            else -> e == f
        }
    }

    /** Valeurs d'un thermostat / poêle (climate, chauffe-eau) que l'état seul (« heat ») ne dit pas : mesure, consigne, marche. */
    internal fun valeursClimat(o: JSONObject): String {
        val id = o.optString("entity_id")
        if (!id.startsWith("climate.") && !id.startsWith("water_heater.")) return ""
        val a = o.optJSONObject("attributes") ?: return ""
        fun n(x: Double) = if (x == Math.floor(x)) x.toLong().toString() else String.format(java.util.Locale.FRANCE, "%.1f", x)
        val l = listOfNotNull(
            a.optDouble("current_temperature").takeIf { !it.isNaN() }?.let { "mesurée ${n(it)} °C" },
            a.optDouble("temperature").takeIf { !it.isNaN() }?.let { "consigne ${n(it)} °C" },
            a.optString("hvac_action").takeIf { it.isNotBlank() }?.let { "action $it" },
        )
        return if (l.isEmpty()) "" else " · " + l.joinToString(" · ")
    }

    fun haEntites(app: JarvisApp, recherche: String, domaine: String, decalage: Int = 0, limite: Int = 100, etat: String = ""): String {
        val termeRechercheNormalise = fr.dd.jarvis.societe.core.normaliser(recherche)
        val filtreImplicitementDansRecherche = recherche.takeIf { termeRechercheNormalise in setOf("indisponible", "injoignable", "hors ligne", "offline", "deconnecte", "deconnectee", "unavailable", "inconnu", "unknown", "etat inconnu", "indisponible ou inconnu", "injoignable ou inconnu", "tous les problemes") }
        val rechercheEntite = if (etat.isBlank() && filtreImplicitementDansRecherche != null) "" else recherche
        val filtreEtat = etat.ifBlank { filtreImplicitementDansRecherche.orEmpty() }
        val arr = JSONArray(ha(app, "GET", "/api/states"))
        val toutes = (0 until arr.length()).map { arr.getJSONObject(it) }
        val paires = toutes.map { it.getString("entity_id") to it.optJSONObject("attributes")?.optString("friendly_name").orEmpty() }
        val l = filtrerEntites(paires, rechercheEntite, domaine).map { toutes[it] }.filter { correspondEtatHa(it.optString("state"), filtreEtat) }
        // Entités que l'utilisateur a notées pour ce sujet (carnet, mémoire) : en tête, avec leur état réel.
        val notees = if (rechercheEntite.isBlank() || decalage > 0) emptyList() else runCatching { fr.dd.jarvis.device.Carnet.entitesNotees(app, rechercheEntite) }.getOrDefault(emptyList())
        val blocNotes = if (notees.isEmpty()) "" else "📝 D'APRÈS TES NOTES (à utiliser en priorité, sans chercher d'autre entité) :\n" + notees.joinToString("\n") { (id, note) ->
            val o = toutes.firstOrNull { it.getString("entity_id") == id }
            "- `$id` · " + (o?.let { x -> val a = x.optJSONObject("attributes"); "${a?.optString("friendly_name").orEmpty()} : **${x.getString("state")}${a?.optString("unit_of_measurement").orEmpty()}**" }
                ?: "⚠️ introuvable dans Home Assistant (renommée ?)") + " — note : « ${note.take(160)} »"
        } + "\n\n"
        if (l.isEmpty() && blocNotes.isNotBlank()) return blocNotes.trim()
        if (l.isEmpty()) {
            // Jamais un simple « aucune entité » : on donne de quoi réessayer (domaines présents, appareils multimédia…).
            val parDomaine = paires.groupingBy { it.first.substringBefore('.') }.eachCount().entries.sortedByDescending { it.value }
            val medias = paires.filter { it.first.startsWith("media_player.") }.take(15)
            val voisines = proches(paires, recherche).map { toutes[it] }
            if (voisines.isNotEmpty()) return "Aucune entité ne correspond exactement à « $recherche » (${arr.length()} entités au total). " +
                "Les plus proches (vérifie avec l'utilisateur avant d'agir, n'invente rien d'autre) :\n" + voisines.joinToString("\n") { o ->
                    val a = o.optJSONObject("attributes"); "- `${o.getString("entity_id")}` · ${a?.optString("friendly_name").orEmpty()} : **${o.getString("state")}${a?.optString("unit_of_measurement").orEmpty()}**"
                }
            val libelleFiltre = rechercheEntite.ifBlank { domaine.ifBlank { filtreEtat.ifBlank { "toutes les entités" } } }
            return "Aucune entité Home Assistant ne correspond au filtre « $libelleFiltre » (${arr.length()} entités au total). Dis-le clairement à l'utilisateur, sans inventer. " +
                "Domaines présents : " + parDomaine.take(14).joinToString(", ") { "${it.key} (${it.value})" } + "." +
                (if (medias.isNotEmpty()) "\nAppareils multimédia (télé, enceintes) : " + medias.joinToString(" ; ") { "`${it.first}` ${it.second}" } else "") +
                "\nRéessaie avec un autre mot (nom de la pièce, marque, tv…) ou un domaine, et regarde les notes de l'utilisateur (notes_lire / memoire_lire / obsidian_chercher)."
        }
        val debut = decalage.coerceIn(0, l.size)
        val taille = limite.coerceIn(1, 250)
        val page = l.drop(debut).take(taille)
        val fin = debut + page.size
        val suite = if (fin < l.size) "\n\nIl reste ${l.size - fin} entité(s). Pour continuer, rappelle ha_entites avec decalage=$fin et la même recherche/domaine." else "\n\nToutes les entités correspondant au filtre ont été parcourues."
        return blocNotes + "# 🏠 Entités : ${l.size} résultat(s) · affichage ${if (page.isEmpty()) 0 else debut + 1}–$fin\n\n" + page.joinToString("\n") { o ->
            val a = o.optJSONObject("attributes"); val unit = a?.optString("unit_of_measurement").orEmpty()
            "- `${o.getString("entity_id")}` · ${a?.optString("friendly_name").orEmpty()} : **${o.getString("state")}$unit**" + valeursClimat(o)
        } + suite
    }

    /**
     * Position d'une personne suivie par Home Assistant (entités person.* et device_tracker.*) : zone, coordonnées
     * GPS, adresse précise, précision, fraîcheur, distance, lien de carte. Dit clairement quand la position est inconnue.
     */
    suspend fun haPosition(app: JarvisApp, nom: String, concise: Boolean = false): String {
        val arr = JSONArray(ha(app, "GET", "/api/states"))
        val toutes = (0 until arr.length()).map { arr.getJSONObject(it) }
        val suivis = toutes.filter { it.getString("entity_id").startsWith("person.") || it.getString("entity_id").startsWith("device_tracker.") }
        if (suivis.isEmpty()) return if (concise) "📍 Position indisponible." else "Home Assistant ne suit aucune personne (aucune entité person.* ni device_tracker.*). Je ne peux donc pas dire où elle se trouve."
        val paires = suivis.map { it.getString("entity_id") to it.optJSONObject("attributes")?.optString("friendly_name").orEmpty() }
        val personnes = suivis.filter { it.getString("entity_id").startsWith("person.") }
        val idx = if (nom.isBlank()) personnes.map { suivis.indexOf(it) } else filtrerEntites(paires, nom, "").ifEmpty { proches(paires, nom, 3) }
        if (idx.isEmpty()) return if (concise) "📍 Position de $nom indisponible." else "Je ne trouve personne nommé « $nom » dans Home Assistant. Personnes suivies : " +
            personnes.joinToString(", ") { it.optJSONObject("attributes")?.optString("friendly_name").orEmpty().ifBlank { it.getString("entity_id") } }.ifBlank { "aucune" } + "."
        // Une personne (person.*) plutôt que l'un de ses appareils.
        val choisies = idx.map { suivis[it] }.sortedBy { if (it.getString("entity_id").startsWith("person.")) 0 else 1 }.take(if (nom.isBlank()) 6 else 1)
        val moi = if (concise) null else runCatching { fr.dd.jarvis.device.Locator.point(app) }.getOrNull()
        val fiches = mutableListOf<String>()
        for (o in choisies) fiches += fichePosition(app, o, toutes, moi, concise)
        return fiches.joinToString("\n\n")
    }

    private suspend fun fichePosition(app: JarvisApp, o: JSONObject, toutes: List<JSONObject>, moi: fr.dd.jarvis.societe.Geo.Point?, concise: Boolean = false): String {
        val a = o.optJSONObject("attributes") ?: JSONObject()
        val nom = a.optString("friendly_name").ifBlank { o.getString("entity_id") }
        val etat = o.optString("state")
        // Coordonnées : celles de la personne, sinon celles de l'appareil qui la suit (attribut « source »).
        var lat = a.optDouble("latitude"); var lon = a.optDouble("longitude"); var precision = a.optDouble("gps_accuracy")
        val source = a.optString("source")
        if ((lat.isNaN() || lon.isNaN()) && source.isNotBlank()) toutes.firstOrNull { it.getString("entity_id") == source }?.optJSONObject("attributes")?.let { s ->
            lat = s.optDouble("latitude"); lon = s.optDouble("longitude"); precision = s.optDouble("gps_accuracy")
        }
        val zone = when (etat) {
            "home" -> "🏠 à la maison"
            "not_home" -> "🚶 absent·e (hors des zones connues)"
            "unknown", "unavailable", "" -> "❓ position inconnue pour Home Assistant"
            else -> "📍 zone « $etat »"
        }
        if (concise) {
            val localisation = if (!lat.isNaN() && !lon.isNaN()) {
                val p = fr.dd.jarvis.societe.Geo.Point(lat, lon)
                runCatching { fr.dd.jarvis.societe.Geo.adresseDe(p)?.libelle }.getOrNull()?.takeIf { it.isNotBlank() } ?: zone
            } else zone
            return "📍 $nom : $localisation"
        }
        val maj = runCatching { java.time.OffsetDateTime.parse(o.optString("last_updated")).toInstant().toEpochMilli() }.getOrNull()
        val age = maj?.let { val m = (System.currentTimeMillis() - it) / 60_000; when { m < 1 -> "à l'instant"; m < 60 -> "il y a $m min"; m < 1440 -> "il y a ${m / 60} h"; else -> "il y a ${m / 1440} j" } }
        return buildString {
            append("📍 **$nom** — $zone\n")
            if (lat.isNaN() || lon.isNaN()) {
                append("- Home Assistant ne fournit PAS de coordonnées GPS pour cette personne : je ne peux pas donner de position plus précise.")
                append(" (Il faut que son téléphone envoie sa position : application Home Assistant Companion, capteur « Localisation » activé, lié à sa personne.)\n")
            } else {
                val p = fr.dd.jarvis.societe.Geo.Point(lat, lon)
                val adr = runCatching { fr.dd.jarvis.societe.Geo.adresseDe(p) }.getOrNull()
                append("- Adresse : ${adr?.libelle ?: "inconnue (coordonnées seulement)"}" + (if (!precision.isNaN() && precision > 0) " (± ${precision.toInt()} m)" else "") + "\n")
                append("- Coordonnées : ${"%.5f".format(java.util.Locale.US, lat)}, ${"%.5f".format(java.util.Locale.US, lon)} · [Voir sur la carte](${fr.dd.jarvis.societe.Geo.lienGoogleMaps(p)})\n")
                if (moi != null) append("- À ${"%.1f".format(fr.dd.jarvis.societe.Geo.haversine(moi, p))} km de toi\n")
                if (!precision.isNaN() && precision > 500) append("- ⚠️ Précision faible (± ${precision.toInt()} m) : position approximative.\n")
            }
            if (age != null) append("- Mise à jour : $age" + (if (source.isNotBlank()) " (appareil : $source)" else "") + "\n")
        }.trimEnd()
    }

    fun haEtat(app: JarvisApp, id: String): String {
        val o = JSONObject(ha(app, "GET", "/api/states/$id"))
        return "`$id` = **${o.getString("state")}**\n" + (o.optJSONObject("attributes")?.toString(1)?.take(1800) ?: "")
    }

    /**
     * Exécute un service puis CONFIRME le résultat : Home Assistant renvoie les états changés pendant l'appel ; pour un
     * appareil plus lent (Zigbee, télé, poêle…), l'état est relu jusqu'à ~2,5 s. Le compte rendu dit ce qui est
     * réellement constaté — avant, « exécuté » était annoncé même quand l'appareil, injoignable, n'avait pas bougé.
     */
    internal fun compteRenduSansEntite(domaine: String, service: String): String =
        "✅ Appel $domaine.$service accepté par Home Assistant (aucun état cible à relire : l'affichage ou la lecture sur l'appareil n'est pas confirmé)."

    fun haService(app: JarvisApp, domaine: String, service: String, entite: String, donnees: JSONObject?): String {
        val corps = donnees ?: JSONObject()
        val ids = entite.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (ids.isNotEmpty()) corps.put("entity_id", if (ids.size > 1) JSONArray(ids) else ids.first())
        val demandes = JSONObject(corps.toString())
        val reponse = ha(app, "POST", "/api/services/$domaine/$service", corps)
        if (ids.isEmpty()) return compteRenduSansEntite(domaine, service)
        val changes = HashMap<String, JSONObject>()
        runCatching { JSONArray(reponse) }.getOrNull()?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { changes[it.optString("entity_id")] = it } }
        val limite = System.currentTimeMillis() + 2_500
        return ids.joinToString("\n") { id ->
            var o: JSONObject? = changes[id]
            while ((o == null || !confirmeService(domaine, service, demandes, o)) && System.currentTimeMillis() < limite) {
                Thread.sleep(400)
                o = runCatching { JSONObject(ha(app, "GET", "/api/states/$id")) }.getOrNull() ?: o
            }
            if (o == null) o = runCatching { JSONObject(ha(app, "GET", "/api/states/$id")) }.getOrNull()
            compteRenduService(domaine, service, id, demandes, o)
        }
    }

    /** L'état relu correspond-il à ce que le service devait produire ? (vrai si rien de précis n'est attendu) */
    internal fun confirmeService(domaine: String, service: String, donnees: JSONObject, o: JSONObject): Boolean {
        val etat = o.optString("state")
        if (etat in setOf("unavailable", "unknown")) return false
        val a = o.optJSONObject("attributes") ?: JSONObject()
        return when {
            service == "turn_on" -> etat !in setOf("off", "standby")
            service == "turn_off" -> etat in setOf("off", "standby")
            service in setOf("open_cover", "open") -> etat in setOf("open", "opening")
            service in setOf("close_cover", "close") -> etat in setOf("closed", "closing")
            service == "lock" -> etat in setOf("locked", "locking")
            service == "unlock" -> etat in setOf("unlocked", "unlocking", "open")
            service == "set_temperature" && donnees.has("temperature") ->
                kotlin.math.abs(a.optDouble("temperature", Double.NaN) - donnees.optDouble("temperature", Double.NaN)) < 0.26
            service == "set_hvac_mode" && donnees.has("hvac_mode") -> etat == donnees.optString("hvac_mode")
            service == "set_preset_mode" && donnees.has("preset_mode") -> a.optString("preset_mode").equals(donnees.optString("preset_mode"), true)
            service == "set_fan_mode" && donnees.has("fan_mode") -> a.optString("fan_mode").equals(donnees.optString("fan_mode"), true)
            service == "volume_set" && donnees.has("volume_level") ->
                kotlin.math.abs(a.optDouble("volume_level", Double.NaN) - donnees.optDouble("volume_level", Double.NaN)) < 0.03
            service == "volume_mute" && donnees.has("is_volume_muted") -> a.optBoolean("is_volume_muted") == donnees.optBoolean("is_volume_muted")
            service == "select_source" && donnees.has("source") -> a.optString("source").equals(donnees.optString("source"), true)
            service == "select_option" && donnees.has("option") -> etat.equals(donnees.optString("option"), true)
            service == "set_value" && donnees.has("value") -> etat == donnees.opt("value").toString() || etat.toDoubleOrNull() == donnees.optDouble("value", Double.NaN)
            else -> true
        }
    }

    internal fun compteRenduService(domaine: String, service: String, id: String, donnees: JSONObject, o: JSONObject?): String {
        if (o == null) return "⚠️ $domaine.$service envoyé pour `$id`, mais son état n'a pas pu être relu."
        val etat = o.optString("state")
        val a = o.optJSONObject("attributes") ?: JSONObject()
        val nom = a.optString("friendly_name").ifBlank { id }
        val detail = buildList {
            if (a.has("temperature") && domaine == "climate") add("consigne ${a.optDouble("temperature")}°")
            if (a.has("current_temperature") && domaine == "climate") add("mesurée ${a.optDouble("current_temperature")}°")
            if (a.has("brightness") && etat == "on") add("luminosité ${(a.optInt("brightness") * 100 / 255)} %")
            if (a.has("volume_level") && domaine == "media_player") add("volume ${(a.optDouble("volume_level") * 100).toInt()} %")
            if (a.has("source") && domaine == "media_player") add("source ${a.optString("source")}")
            if (a.has("current_position") && domaine == "cover") add("position ${a.optInt("current_position")} %")
        }.joinToString(", ").let { if (it.isBlank()) "" else " ($it)" }
        return when {
            etat == "unavailable" -> "⚠️ $nom (`$id`) est INDISPONIBLE dans Home Assistant : la commande $domaine.$service a été acceptée mais l'appareil ne répond pas (hors ligne, intégration déconnectée…)."
            confirmeService(domaine, service, donnees, o) -> "✅ $nom (`$id`) : $domaine.$service confirmé — état « $etat »$detail."
            else -> "⚠️ $nom (`$id`) : $domaine.$service accepté par Home Assistant, mais l'état reste « $etat »$detail après 2,5 s. Ne dis PAS que c'est fait : l'appareil n'a peut-être pas suivi (ou il réagit lentement)."
        }
    }

    /** Valide la configuration (API, sinon vérification dans le conteneur). Retourne null si valide, sinon le message d'erreur. */
    fun haValider(app: JarvisApp): String? = HaSur.verifier(app)?.let { "Configuration invalide : $it" }

    fun haConteneur(app: JarvisApp): String? = runCatching { HaSur.conteneur(app)?.nom }.getOrNull()

    /** Dossier de configuration de Home Assistant sur le serveur (réglage, sinon détection Docker puis emplacements usuels). */
    fun haDossier(app: JarvisApp): String? {
        app.store.haDir.value.trim().takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
        val nom = haConteneur(app)
        if (nom != null) {
            val src = exec(app, "docker inspect $nom --format '{{range .Mounts}}{{if eq .Destination \"/config\"}}{{.Source}}{{end}}{{end}}'").second.trim()
            if (src.startsWith("/")) { app.store.haDir.set(src); return src }
        }
        for (c in listOf("/config", "~/homeassistant", "/opt/homeassistant/config", "/opt/homeassistant", "/home/homeassistant/.homeassistant", "/usr/share/hassio/homeassistant", "~/.homeassistant")) {
            if (exec(app, "test -f $c/configuration.yaml").first == 0) {
                val abs = exec(app, "cd $c && pwd").second.trim().ifBlank { c }
                app.store.haDir.set(abs); return abs
            }
        }
        return null
    }
}
