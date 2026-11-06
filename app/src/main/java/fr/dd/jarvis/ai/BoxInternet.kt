package fr.dd.jarvis.ai

import android.content.Context
import android.net.ConnectivityManager

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

    fun adapterAvailable(profile: BoxInternetProfile): Boolean =
        profile.adapterId?.let(integratedAdapterIds::contains) == true

    fun managementStatus(profile: BoxInternetProfile): String = when {
        adapterAvailable(profile) && profile.isManageable -> "Adaptateur disponible"
        profile.vendor == BoxInternetVendor.OTHER -> "Fabricant à identifier"
        else -> "Box détectable, adaptateur constructeur à intégrer"
    }
}
