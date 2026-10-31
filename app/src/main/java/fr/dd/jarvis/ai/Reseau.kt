package fr.dd.jarvis.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Analyse du réseau local (Wi-Fi) : adresses, appareils, ports, services mDNS. Limité aux adresses privées (ton réseau), jamais Internet. */
object Reseau {
    data class Infos(val ip: String, val prefix: Int, val passerelle: String, val wifi: Boolean)
    data class Appareil(val ip: String, val nom: String, val ports: List<Int>, val titre: String, val type: String, val info: String = "")
    data class Service(val nom: String, val host: String, val port: Int, val chemin: String, val type: String)

    private val http = OkHttpClient.Builder().connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(2500, TimeUnit.MILLISECONDS).followRedirects(true)
        .hostnameVerifier { _, _ -> true }.sslSocketFactory(trustAll().first, trustAll().second).build()
    private val dns = Executors.newCachedThreadPool()
    val clientLax: OkHttpClient get() = http
    fun ouvertTcp(ip: String, port: Int, ms: Int = 1500) = ouvert(ip, port, ms) == 1

    private fun trustAll(): Pair<javax.net.ssl.SSLSocketFactory, javax.net.ssl.X509TrustManager> {
        val tm = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
        }
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS"); ctx.init(null, arrayOf(tm), null)
        return ctx.socketFactory to tm
    }

    /** Vrai pour 10.x, 172.16-31.x, 192.168.x, 169.254.x (réseau local uniquement). */
    fun prive(ip: String): Boolean = runCatching {
        val a = InetAddress.getByName(ip)
        a is Inet4Address && (a.isSiteLocalAddress || a.isLinkLocalAddress)
    }.getOrDefault(false)

    @Volatile private var wifiNet: android.net.Network? = null
    @Volatile var diag: String = ""

    fun infos(ctx: Context): Infos? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Le réseau Wi-Fi lui-même (et non le réseau « actif », qui peut être un VPN ou les données mobiles).
        val wifi = cm.allNetworks.firstOrNull { n -> cm.getNetworkCapabilities(n)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true }
        val n = wifi ?: cm.activeNetwork ?: return null
        wifiNet = wifi
        val lp = cm.getLinkProperties(n) ?: return null
        val caps = cm.getNetworkCapabilities(n)
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress.orEmpty()
        return Infos(la.address.hostAddress.orEmpty(), la.prefixLength, gw, caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
    }

    fun ippPublique(): String = runCatching {
        http.newCall(Request.Builder().url("https://api.ipify.org").build()).execute().use { it.body?.string().orEmpty().trim() }
    }.getOrDefault("")

    private fun ouvert(ip: String, port: Int, ms: Int = 350): Int {   // 1 = ouvert, 0 = fermé mais l'hôte répond, -1 = pas de réponse
        return try { Socket().use { sk -> runCatching { wifiNet?.bindSocket(sk) }; sk.connect(InetSocketAddress(ip, port), ms) }; 1 }
        catch (e: ConnectException) { if ((e.message ?: "").contains("refused", true)) 0 else -1 }
        catch (e: Exception) { -1 }
    }

    /** Résultat brut d'une connexion TCP (pour le diagnostic) : ouvert, refusé, délai dépassé ou message d'erreur du système. */
    private fun detailTcp(ip: String, port: Int, ms: Int = 1200): String = try {
        Socket().use { sk -> runCatching { wifiNet?.bindSocket(sk) }; sk.connect(InetSocketAddress(ip, port), ms) }; "ouvert"
    } catch (e: java.net.SocketTimeoutException) { "délai dépassé" }
    catch (e: Exception) { (e.message ?: e.javaClass.simpleName).substringAfterLast(": ").take(48) }

    /** Pourquoi le scan ne voit personne : permission réseau local, VPN, et réponse brute de la box. */
    private fun diagBlocage(ctx: Context, i: Infos): String {
        val sb = StringBuilder()
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            val ok = androidx.core.content.ContextCompat.checkSelfPermission(ctx, "android.permission.ACCESS_LOCAL_NETWORK") == android.content.pm.PackageManager.PERMISSION_GRANTED
            sb.append("Permission « Appareils à proximité / réseau local » : ${if (ok) "accordée" else "REFUSÉE ❌ (Réglages Android › Applications › JARVIS › Autorisations › Appareils à proximité)"}\n")
        }
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val vpn = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        sb.append("VPN actif : ${if (vpn) "oui ⚠️" else "non"}\n")
        if (i.passerelle.isNotBlank()) {
            sb.append("Box ${i.passerelle} : " + listOf(80, 443, 53).joinToString(" · ") { "port $it → ${detailTcp(i.passerelle, it)}" } + "\n")
            sb.append("Ping box : ${runCatching { InetAddress.getByName(i.passerelle).isReachable(1200) }.getOrDefault(false)}")
        }
        return sb.toString().trim()
    }

    private val PORTS = listOf(80, 443, 22, 445, 139, 9100, 631, 515, 8080, 8123, 5000, 554, 1883, 8009, 7000, 62078, 53, 23, 3389, 5900, 8000, 32400, 8096)

    private fun typeDe(p: List<Int>, titre: String): String {
        val t = titre.lowercase()
        return when {
            9100 in p || 631 in p || 515 in p || listOf("printer", "imprim", "canon", "epson", "brother", "laserjet", "officejet", "pixma", "ecotank").any { t.contains(it) } -> "🖨️ imprimante"
            8123 in p || t.contains("home assistant") -> "🏠 Home Assistant"
            554 in p || t.contains("camera") || t.contains("hikvision") || t.contains("dahua") -> "📷 caméra"
            62078 in p -> "📱 iPhone/iPad"
            8009 in p -> "📺 Chromecast / TV"
            7000 in p -> "📺 AirPlay"
            32400 in p || 8096 in p -> "🎬 serveur média"
            445 in p && 22 !in p && 80 !in p -> "💻 PC Windows / NAS"
            22 in p -> "🖥️ serveur / Linux (SSH)"
            53 in p || 80 in p && t.contains("router") -> "📡 routeur"
            80 in p || 443 in p || 8080 in p -> "🌐 appareil web"
            else -> "❔ appareil"
        }
    }

    fun titreWeb(ip: String, port: Int): String {
        val url = (if (port == 443 || port == 8443) "https" else "http") + "://$ip:$port/"
        return runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "JARVIS").build()).execute().use { r ->
                val t = r.body?.string().orEmpty().take(20000)
                Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(t)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")?.take(60)
                    ?: r.header("Server").orEmpty().take(40)
            }
        }.getOrDefault("")
    }

    private fun nomHote(ip: String): String = runCatching {
        dns.submit<String> { InetAddress.getByName(ip).canonicalHostName }.get(900, TimeUnit.MILLISECONDS).takeIf { it != ip }.orEmpty()
    }.getOrDefault("")

    private val pool = Executors.newFixedThreadPool(160).asCoroutineDispatcher()
    private val ESSENTIELS = listOf(631, 9100, 80, 443, 515, 22, 445, 8080)
    private val RAPIDES = listOf(80, 443, 22, 445, 62078, 8009, 5353, 8080)

    /** Nom NetBIOS (PC Windows, NAS, imprimantes) par requête UDP 137 : répond même quand TCP est filtré. */
    private fun netbios(ip: String): String? = runCatching {
        val q = byteArrayOf(0x82.toByte(), 0x28, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0x20) + "CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".toByteArray() + byteArrayOf(0, 0, 0x21, 0, 1)
        java.net.DatagramSocket().use { s ->
            runCatching { wifiNet?.bindSocket(s) }
            s.soTimeout = 350
            s.send(java.net.DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(600); val p = java.net.DatagramPacket(buf, buf.size); s.receive(p)
            if (p.length > 72) String(buf, 57, 15).trim().takeIf { it.isNotBlank() } else "?"
        }
    }.getOrNull()

    /** Balayage du sous-réseau Wi-Fi (limité à /22) : ICMP, TCP, NetBIOS, puis UPnP et mDNS. */
    suspend fun scanner(ctx: Context, onStatus: (String) -> Unit = {}): List<Appareil> = withContext(pool) {
        val i = infos(ctx) ?: return@withContext emptyList()
        if (!prive(i.ip)) { diag = "Adresse ${i.ip} non locale."; return@withContext emptyList() }
        val pref = i.prefix.coerceIn(22, 30)
        val base = InetAddress.getByName(i.ip).address
        val ipInt = ((base[0].toInt() and 255) shl 24) or ((base[1].toInt() and 255) shl 16) or ((base[2].toInt() and 255) shl 8) or (base[3].toInt() and 255)
        val mask = (-1 shl (32 - pref))
        val net = ipInt and mask
        val hosts = (1 until (1 shl (32 - pref)) - 1).map { net + it }.map { "${it ushr 24 and 255}.${it ushr 16 and 255}.${it ushr 8 and 255}.${it and 255}" }
        val icmpOk = java.util.concurrent.atomic.AtomicInteger(); val tcpOk = java.util.concurrent.atomic.AtomicInteger()
        val trouves = coroutineScope { hosts.map { h -> async { sonder(h, icmpOk, tcpOk) } }.awaitAll() }.filterNotNull()
        val upnp = runCatching { ssdp(ctx) }.getOrDefault(emptyMap())
        val mdns = runCatching { decouvrir(ctx, listOf("_http._tcp", "_googlecast._tcp", "_airplay._tcp", "_smb._tcp", "_ssh._tcp", "_hap._tcp", "_workstation._tcp", "_ipp._tcp", "_companion-link._tcp", "_spotify-connect._tcp"), 3500) }.getOrDefault(emptyList()).groupBy({ it.host }, { it.nom })
        val parIp = trouves.associateBy { it.ip }.toMutableMap()
        for (ip in (upnp.keys + mdns.keys)) if (!parIp.containsKey(ip) && prive(ip)) parIp[ip] = Appareil(ip, nomHote(ip), emptyList(), "", "❔ appareil")
        diag = "Sous-réseau ${hosts.first().substringBeforeLast('.')}.0/$pref (${hosts.size} adresses testées, liées au Wi-Fi ${if (wifiNet != null) "✔" else "✘ (réseau actif par défaut)"}) · réponses ICMP : ${icmpOk.get()} · TCP : ${tcpOk.get()} · UPnP : ${upnp.size} · mDNS : ${mdns.size}"
        if (trouves.count { it.ip != i.ip } == 0 && upnp.isEmpty() && mdns.isEmpty()) diag += "\n" + diagBlocage(ctx, i)
        parIp.values.map { d ->
            val info = (upnp[d.ip].orEmpty().ifBlank { mdns[d.ip]?.distinct()?.joinToString(", ").orEmpty() }).take(70)
            d.copy(info = info)
        }.sortedBy { a -> a.ip.split(".").map { it.toInt() }.fold(0L) { acc, x -> acc * 256 + x } }
    }

    fun typeDeApp(p: List<Int>, titre: String) = typeDe(p, titre)

    private fun sonder(ip: String, icmpOk: java.util.concurrent.atomic.AtomicInteger? = null, tcpOk: java.util.concurrent.atomic.AtomicInteger? = null): Appareil? {
        val ouverts = mutableListOf<Int>()
        var vivant = runCatching { InetAddress.getByName(ip).isReachable(700) }.getOrDefault(false)
        if (vivant) icmpOk?.incrementAndGet()
        // Ports essentiels (une imprimante en veille met parfois un moment à répondre) ; « fermé mais l'hôte répond » prouve aussi sa présence.
        for (p in ESSENTIELS) { val r = ouvert(ip, p, if (vivant) 500 else 300); if (r == 1) { ouverts += p; vivant = true } else if (r == 0) vivant = true }
        if (!vivant) for (p in RAPIDES) if (p !in ESSENTIELS) { val r = ouvert(ip, p, 250); if (r >= 0) { vivant = true; if (r == 1) ouverts += p } }
        var nb: String? = null
        if (!vivant) { nb = netbios(ip); if (nb != null) vivant = true }
        if (!vivant) return null
        tcpOk?.incrementAndGet()
        for (p in PORTS) if (p !in ESSENTIELS && p !in RAPIDES && ouvert(ip, p, 250) == 1) ouverts += p
        val web = ouverts.firstOrNull { it in listOf(80, 8080, 443, 8123, 5000, 8000) }
        val titre = web?.let { titreWeb(ip, it) }.orEmpty()
        val nom = nomHote(ip).ifBlank { (nb ?: netbios(ip))?.takeIf { it != "?" }.orEmpty() }
        return Appareil(ip, nom, ouverts, titre, typeDe(ouverts, titre))
    }

    /** Sonde un seul appareil (adresse privée uniquement) : ports ouverts, titre de la page web. */
    suspend fun appareil(ip: String): Appareil? = withContext(Dispatchers.IO) { if (!prive(ip)) null else {
        val r = coroutineScope { PORTS.map { p -> async { p to ouvert(ip, p, 500) } }.awaitAll() }
        val ouverts = r.filter { it.second == 1 }.map { it.first }
        if (ouverts.isEmpty() && r.none { it.second == 0 }) null
        else {
            val web = ouverts.firstOrNull { it in listOf(80, 8080, 443, 8123, 5000, 8000) }
            val titre = web?.let { titreWeb(ip, it) }.orEmpty()
            Appareil(ip, nomHote(ip), ouverts, titre, typeDe(ouverts, titre))
        }
    } }

    /** Lit une page web d'un appareil du réseau local (interface d'administration d'une imprimante, d'une box…). */
    fun lirePage(ip: String, port: Int, chemin: String): String {
        if (!prive(ip)) throw IllegalArgumentException("Adresse non locale refusée ($ip).")
        val url = (if (port == 443 || port == 8443) "https" else "http") + "://$ip:$port/" + chemin.trimStart('/')
        http.newCall(Request.Builder().url(url).header("User-Agent", "JARVIS").build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            val txt = t.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]+>"), " ").replace(Regex("&nbsp;"), " ").replace(Regex("\\s+"), " ").trim()
            return "HTTP ${r.code}\n" + txt.take(3500)
        }
    }

    /** Découverte UPnP/SSDP : adresse → « fabricant modèle (nom) » pour box, TV, NAS, imprimantes, enceintes… */
    suspend fun ssdp(ctx: Context, ms: Int = 2500): Map<String, String> = withContext(Dispatchers.IO) {
        val res = java.util.concurrent.ConcurrentHashMap<String, String>()
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val lock = runCatching { wm.createMulticastLock("jarvis_ssdp").also { it.acquire() } }.getOrNull()
        val locs = HashMap<String, String>()
        try {
            java.net.DatagramSocket().use { sock ->
                runCatching { wifiNet?.bindSocket(sock) }
                sock.soTimeout = 400
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n".toByteArray()
                repeat(2) { runCatching { sock.send(java.net.DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900)) } }
                val fin = System.currentTimeMillis() + ms
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < fin) {
                    try {
                        val p = java.net.DatagramPacket(buf, buf.size); sock.receive(p)
                        val ip = p.address.hostAddress ?: continue
                        val t = String(p.data, 0, p.length)
                        val loc = Regex("(?im)^location:\\s*(\\S+)").find(t)?.groupValues?.get(1)
                        val srv = Regex("(?im)^server:\\s*(.+)$").find(t)?.groupValues?.get(1)?.trim().orEmpty()
                        if (loc != null && !locs.containsKey(ip)) locs[ip] = loc
                        if (!res.containsKey(ip)) res[ip] = srv.take(50)
                    } catch (_: java.net.SocketTimeoutException) { }
                }
            }
        } finally { runCatching { lock?.release() } }
        // Description UPnP : nom convivial, fabricant, modèle
        for ((ip, loc) in locs) runCatching {
            http.newCall(Request.Builder().url(loc).build()).execute().use { r ->
                val x = r.body?.string().orEmpty().take(30000)
                fun tag(n: String) = Regex("<$n>(.*?)</$n>", RegexOption.DOT_MATCHES_ALL).find(x)?.groupValues?.get(1)?.trim().orEmpty()
                val d = listOf(tag("manufacturer"), tag("modelName"), tag("friendlyName").let { if (it.isNotBlank()) "($it)" else "" }).filter { it.isNotBlank() }.joinToString(" ")
                if (d.isNotBlank()) res[ip] = d.take(70)
            }
        }
        res
    }

    /** Services mDNS (_ipp._tcp, _printer._tcp…) vus sur le réseau pendant [ms] millisecondes. */
    suspend fun decouvrir(ctx: Context, types: List<String>, ms: Long = 4000): List<Service> = withContext(Dispatchers.IO) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val lock = runCatching { wm.createMulticastLock("jarvis").also { it.acquire() } }.getOrNull()
        val vus = java.util.concurrent.ConcurrentHashMap<String, Service>()
        val file = java.util.concurrent.LinkedBlockingQueue<Pair<NsdServiceInfo, String>>()
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()
        try {
            for (t in types) {
                val l = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(s: String) {}
                    override fun onDiscoveryStopped(s: String) {}
                    override fun onStartDiscoveryFailed(s: String, e: Int) {}
                    override fun onStopDiscoveryFailed(s: String, e: Int) {}
                    override fun onServiceLost(i: NsdServiceInfo) {}
                    override fun onServiceFound(i: NsdServiceInfo) { file.offer(i to t) }
                }
                runCatching { nsd.discoverServices(t, NsdManager.PROTOCOL_DNS_SD, l); listeners += l }
            }
            val fin = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < fin) {
                val (info, type) = file.poll(300, TimeUnit.MILLISECONDS) ?: continue
                val latch = java.util.concurrent.CountDownLatch(1)
                runCatching {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, e: Int) { latch.countDown() }
                        override fun onServiceResolved(i: NsdServiceInfo) {
                            @Suppress("DEPRECATION") val host = i.host?.hostAddress.orEmpty()
                            val rp = i.attributes?.get("rp")?.let { String(it) }.orEmpty()
                            if (host.isNotBlank()) vus[i.serviceName + type] = Service(i.serviceName, host, i.port, rp, type)
                            latch.countDown()
                        }
                    })
                    latch.await(1500, TimeUnit.MILLISECONDS)
                }
            }
        } finally {
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
            runCatching { lock?.release() }
        }
        vus.values.toList()
    }
}
