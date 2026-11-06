package fr.dd.jarvis.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.HttpURLConnection

/**
 * Socle multi-constructeurs pour l'administration des box Internet.
 *
 * Une box n'expose pas forcément la même API selon son opérateur, son modèle
 * et sa version logicielle. Les adaptateurs sont donc isolés : aucune commande
 * de configuration ne doit être envoyée à un équipement sans adaptateur
 * identifié et sans autorisation explicite.
 */
enum class BoxInternetVendor(val label: String) {
    FREE("Free"),
    ORANGE("Orange"),
    BOUYGUES("Bouygues Telecom"),
    SFR("SFR"),
    RED("RED by SFR"),
    FRITZ("AVM FRITZ!Box"),
    ASUS("ASUS"),
    TP_LINK("TP-Link"),
    NETGEAR("NETGEAR"),
    OTHER("Autre fabricant")
}

enum class BoxInternetCapability {
    DEVICE_DISCOVERY,
    STATUS_READ,
    WIFI_READ,
    WIFI_CONFIGURE,
    DHCP_READ,
    DHCP_CONFIGURE,
    PORT_FORWARDING_READ,
    PORT_FORWARDING_CONFIGURE,
    FIREWALL_READ,
    FIREWALL_CONFIGURE,
    REBOOT
}

enum class BoxInternetProtocol {
    VENDOR_API,
    TR064,
    UPNP_IGD,
    SNMP,
    HTTPS_ADMIN
}

data class BoxInternetProfile(
    val vendor: BoxInternetVendor,
    val model: String,
    val firmware: String = "",
    val gatewayAddress: String = "",
    val protocols: Set<BoxInternetProtocol> = emptySet(),
    val capabilities: Set<BoxInternetCapability> = emptySet(),
    val adapterId: String? = null,
    val pairingRequired: Boolean = true
) {
    val isManageable: Boolean get() = !adapterId.isNullOrBlank() && capabilities.isNotEmpty()
}

/**
 * Registre des adaptateurs réellement intégrés.
 *
 * La liste des fabricants reconnus est volontairement plus large que celle
 * des adaptateurs opérationnels : détecter une marque ne signifie pas que
 * Jarvis sait déjà modifier sa configuration.
 */
object BoxInternetAdapters {
    const val FREEBOX_OS = "freebox_os"

    val knownVendors: List<BoxInternetVendor> = BoxInternetVendor.values().toList()

    /** Adaptateurs de configuration dont le code est effectivement présent. */
    val integratedAdapterIds: Set<String> = setOf(FREEBOX_OS)

    /** Protocoles génériques à sonder sans modifier la configuration. */
    val discoveryProtocols: Set<BoxInternetProtocol> = setOf(
        BoxInternetProtocol.TR064,
        BoxInternetProtocol.UPNP_IGD,
        BoxInternetProtocol.HTTPS_ADMIN
    )

    /**
     * Lit la passerelle réellement utilisée par Android sur le réseau actif.
     * Cette étape est passive : elle n'essaie aucun mot de passe et ne modifie
     * aucun réglage de la box.
     */
    fun detecterPasserelle(context: Context): BoxInternetProfile? {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.activeNetwork ?: return null
        val gateway = cm.getLinkProperties(network)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway != null }
            ?.gateway?.hostAddress
            ?.substringBefore('%')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return BoxInternetProfile(
            vendor = BoxInternetVendor.OTHER,
            model = "Passerelle réseau détectée",
            gatewayAddress = gateway,
            protocols = discoveryProtocols,
            capabilities = setOf(BoxInternetCapability.DEVICE_DISCOVERY),
            adapterId = null,
            pairingRequired = true
        )
    }

    /**
     * Découverte UPnP/SSDP en lecture seule. La réponse de description n'est
     * téléchargée que depuis une adresse IP privée ou link-local du réseau.
     * Aucun identifiant n'est demandé et aucune configuration n'est modifiée.
     */
    fun detecterFabricantUpnp(context: Context): BoxInternetProfile? {
        val ctx = context.applicationContext
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wifi?.createMulticastLock("jarvis_box_discovery") }.getOrNull()
        return try {
            lock?.setReferenceCounted(false)
            lock?.acquire()
            val request = (
                "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: ssdp:all\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
            var location: String? = null
            DatagramSocket().use { socket ->
                socket.soTimeout = 350
                val packet = DatagramPacket(
                    request, request.size,
                    InetAddress.getByName("239.255.255.250"), 1900
                )
                socket.send(packet)
                val deadline = System.currentTimeMillis() + 1800L
                val buffer = ByteArray(8192)
                while (location == null && System.currentTimeMillis() < deadline) {
                    try {
                        val response = DatagramPacket(buffer, buffer.size)
                        socket.receive(response)
                        val text = String(response.data, response.offset, response.length, Charsets.US_ASCII)
                        location = text.lineSequence()
                            .firstOrNull { it.substringBefore(':').trim().equals("location", true) }
                            ?.substringAfter(':')?.trim()
                    } catch (_: SocketTimeoutException) {
                        // Continue jusqu'à l'expiration de la courte fenêtre SSDP.
                    }
                }
            }
            val loc = location ?: return null
            val uri = runCatching { java.net.URI(loc) }.getOrNull() ?: return null
            if (!uri.scheme.equals("http", true) || uri.userInfo != null || uri.host.isNullOrBlank()) return null
            val ip = runCatching { InetAddress.getByName(uri.host) }.getOrNull() ?: return null
            if (!(ip.isSiteLocalAddress || ip.isLinkLocalAddress || ip.isLoopbackAddress)) return null
            val connection = (URL(loc).openConnection() as? HttpURLConnection) ?: return null
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/xml,text/xml")
            try {
                if (connection.responseCode !in 200..299) return null
                if (connection.contentLengthLong > 1_000_000L) return null
                val xml = connection.inputStream.bufferedReader().use { it.readText().take(1_000_000) }
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                parser.setInput(StringReader(xml))
                var manufacturer = ""
                var model = ""
                var tag = ""
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> tag = parser.name.lowercase()
                        XmlPullParser.TEXT -> when (tag) {
                            "manufacturer" -> manufacturer += parser.text
                            "modelname" -> model += parser.text
                        }
                        XmlPullParser.END_TAG -> tag = ""
                    }
                    event = parser.next()
                }
                val maker = manufacturer.trim()
                val modelName = model.trim().ifBlank { "Box Internet" }
                val combined = "$maker $modelName".lowercase()
                val vendor = when {
                    "freebox" in combined || "free sas" in combined -> BoxInternetVendor.FREE
                    "orange" in combined || "livebox" in combined -> BoxInternetVendor.ORANGE
                    "bouygues" in combined || "bbox" in combined -> BoxInternetVendor.BOUYGUES
                    "sfr" in combined || "altice" in combined || "neufbox" in combined -> BoxInternetVendor.SFR
                    "avm" in combined || "fritz" in combined -> BoxInternetVendor.FRITZ
                    "asus" in combined -> BoxInternetVendor.ASUS
                    "tp-link" in combined || "tplink" in combined -> BoxInternetVendor.TP_LINK
                    "netgear" in combined -> BoxInternetVendor.NETGEAR
                    else -> BoxInternetVendor.OTHER
                }
                BoxInternetProfile(
                    vendor = vendor,
                    model = modelName,
                    gatewayAddress = uri.host,
                    protocols = setOf(BoxInternetProtocol.UPNP_IGD),
                    capabilities = setOf(BoxInternetCapability.DEVICE_DISCOVERY, BoxInternetCapability.STATUS_READ),
                    adapterId = null,
                    pairingRequired = true
                )
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    fun adapterAvailable(profile: BoxInternetProfile): Boolean =
        profile.adapterId?.let(integratedAdapterIds::contains) == true

    fun managementStatus(profile: BoxInternetProfile): String = when {
        adapterAvailable(profile) && profile.isManageable -> "Adaptateur disponible"
        profile.vendor == BoxInternetVendor.OTHER -> "Fabricant à identifier"
        else -> "Box détectable, adaptateur constructeur à intégrer"
    }
}
