package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import org.json.JSONObject

/**
 * Inventaire du réseau Wi-Fi et accès à distance automatique : JARVIS relève les appareils (analyse du réseau + liste de
 * la Freebox), puis, pour rendre un appareil joignable depuis Internet, il fait tout seul ce qu'on ferait à la main dans
 * Freebox OS — réserver son adresse IP (bail DHCP fixe, pour que la redirection reste valable), choisir un port externe
 * libre, créer la redirection de port, relever l'IP publique — et retient l'adresse obtenue.
 */
object AccesDistant {
    /** Services courants : port → nom, et risque si exposé directement sur Internet. */
    val SERVICES = linkedMapOf(
        8123 to "Home Assistant", 32400 to "Plex", 443 to "Web sécurisé (HTTPS)", 80 to "Web (HTTP)", 8080 to "Web (8080)", 8443 to "Web sécurisé (8443)",
        9443 to "Portainer (HTTPS)", 9000 to "Portainer", 554 to "Caméra (RTSP)", 8000 to "Web (8000)", 5000 to "Web (5000)", 1883 to "MQTT",
        22 to "SSH", 3389 to "Bureau à distance (RDP)", 5900 to "VNC", 445 to "Partage de fichiers (SMB)", 5555 to "ADB (Android)",
        631 to "Imprimante (IPP)", 9100 to "Imprimante", 8009 to "Chromecast", 21 to "FTP",
    )
    /** Services dangereux à ouvrir sur Internet (préférer le VPN WireGuard de la Freebox). */
    private val SENSIBLES = setOf(21, 22, 23, 80, 445, 554, 1883, 3389, 5000, 5555, 5900, 631, 8000, 8009, 8080, 8123, 8883, 9000, 9100, 32400)

    /** Services qui ne doivent JAMAIS être publiés tels quels : contrôle distant via tunnel SSH ou VPN uniquement. */
    private val TUNNEL_OBLIGATOIRE = setOf(21, 22, 23, 80, 445, 554, 1883, 3389, 5000, 5555, 5900, 631, 8000, 8009, 8080, 8123, 8883, 9000, 9100, 32400)

    internal fun tunnelObligatoire(port: Int): Boolean = port in TUNNEL_OBLIGATOIRE

    /** Seuls les points d'entrée HTTPS connus peuvent recevoir une redirection directe. */
    internal fun publicationDirecteAutorisee(port: Int): Boolean = port in setOf(443, 8443, 9443)

    fun service(port: Int) = SERVICES[port] ?: "port $port"

    /** « Rends la caméra du garage accessible à distance », « ferme l'accès à distance de 192.168.1.20 » : arguments de l'outil, ou null. */
    fun intention(app: JarvisApp, texte: String): JSONObject? {
        val n = fr.dd.jarvis.societe.core.normaliser(texte)
        if (!Regex("\\bdistance\\b|depuis (l )?internet|de l exterieur").containsMatchIn(n)) return null
        val fermer = Regex("\\b(ferme|fermer|coupe|couper|supprime|supprimer|retire|retirer|desactive|desactiver|bloque|bloquer)\\b").containsMatchIn(n)
        val ouvrir = Regex("\\b(rends?|rendre|ouvre|ouvrir|configure|configurer|active|activer|donne|donner|mets?|mettre|accessible|acceder)\\b").containsMatchIn(n)
        if (!fermer && !ouvrir) return null
        val ip = Regex("\\b(\\d{1,3}(?:\\.\\d{1,3}){3})\\b").find(texte)?.groupValues?.get(1)
        val appareil = ip ?: Registre.tous(app).firstOrNull { e ->
            listOf("alias", "hote").map { fr.dd.jarvis.societe.core.normaliser(e.optString(it)) }.any { it.length >= 3 && n.contains(it) }
        }?.optString("ip") ?: return null
        val port = Regex("\\bport (\\d{1,5})\\b").find(n)?.groupValues?.get(1)
        return JSONObject().put("action", if (fermer) "fermer" else "ouvrir").put("appareil", appareil).apply { if (port != null) put("port", port) }
    }

    /** Inventaire immédiat : analyse du réseau Wi-Fi + appareils connus de la Freebox, enregistrés dans le registre. */
    suspend fun inventorier(app: JarvisApp, onStatus: (String) -> Unit = {}): String {
        val i = Reseau.infos(app) ?: return "📵 Pas de réseau : connecte-toi au Wi-Fi pour lister ses appareils."
        if (!i.wifi) return "📶 Le téléphone n'est pas en Wi-Fi : l'inventaire se fait sur le réseau Wi-Fi de la maison."
        val reseau = "Wi-Fi ${i.ip.substringBeforeLast('.')}.x"
        onStatus("📡 Analyse du réseau…")
        val scan = runCatching { Reseau.scanner(app, onStatus) }.getOrDefault(emptyList())
        // La Freebox sait exactement qui est connecté : elle fait foi. L'analyse du téléphone n'ajoute que les appareils
        // vraiment identifiés (service ouvert, nom) — plus de dizaines d'« appareils » fantômes.
        val freebox = FreeboxOs.appareilsReseau(app)
        val retenus = if (freebox != null) { val actifs = freebox.map { it.ip }.toSet(); scan.filter { it.ip in actifs || !it.faible } } else scan
        var neufs = runCatching { Registre.appareils(app, retenus, reseau) }.getOrDefault(0)
        if (freebox != null) {
            neufs += runCatching { Registre.appareils(app, freebox, reseau) }.getOrDefault(0)
            // Tout ce que la Freebox ne liste pas comme connecté (et qui n'a rien répondu de concret) n'est plus « en ligne ».
            runCatching { Registre.marquerAbsents(app, (freebox.map { it.ip } + retenus.filter { !it.faible }.map { it.ip }).toSet()) }
        }
        val oublies = runCatching { Registre.purger(app) }.getOrDefault(0)
        val redirections = if (freebox != null) runCatching { FreeboxOs.gerer(app, "redirections", JSONObject()) { _, _ -> true } }.getOrDefault("") else ""
        val redirectionsDangereuses = lignesRedirectionsDangereuses(redirections)
        val dmzEtat = if (freebox != null) runCatching { FreeboxOs.gerer(app, "dmz", JSONObject()) { _, _ -> true } }.getOrDefault("") else ""
        val dmzDejaActive = dmzActive(dmzEtat)
        (FreeboxOs.ipPublique(app) ?: Reseau.ippPublique()).takeIf { it.isNotBlank() }?.let { Registre.ipPublique(app, it, reseau) }
        val enLigne = Registre.tous(app).filter { Registre.enLigne(it) }
        val wifi = enLigne.count { it.optString("lien") == "wifi" }; val cable = enLigne.count { it.optString("lien") == "ethernet" }
        return "✅ ${enLigne.size} appareil(s) connecté(s) sur $reseau" + (if (wifi + cable > 0) " — $wifi en Wi-Fi, $cable par câble" else "") +
            (if (freebox != null) " (liste de la Freebox)" else " (analyse du téléphone ; associe la Freebox pour une liste exacte)") +
            (if (neufs > 0) " · $neufs nouveau(x)" else "") + (if (oublies > 0) " · $oublies ancien(s) ou fantôme(s) oublié(s)" else "") + "." +
            (if (redirectionsDangereuses.isNotEmpty()) "\n⚠️ Redirections Freebox existantes non HTTPS détectées (elles ne sont pas supprimées automatiquement) :\n" + redirectionsDangereuses.joinToString("\n") + "\nDésactive-les après vérification ou utilise le tunnel SSH/WireGuard." else "") +
            (if (dmzDejaActive) "\n⚠️ " + dmzEtat + " : tous les ports entrants sont exposés. Dis « désactive la DMZ » pour fermer cet accès." else "") +
            Reseau.diag.lines().firstOrNull { it.startsWith("⚠️") }?.let { "\n$it" }.orEmpty()
    }

    internal fun dmzActive(texte: String): Boolean = texte.trimStart().startsWith("DMZ : activée")

    /** Redirections actives dont le port LAN n'est pas un point d'entrée HTTPS autorisé. Lecture seule : aucune règle n'est supprimée. */
    internal fun lignesRedirectionsDangereuses(texte: String): List<String> = texte.lineSequence().filter { ligne ->
        ligne.trimStart().startsWith("🟢") &&
            Regex("→\\s*[^:]+:(\\d+)").find(ligne)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.let { !publicationDirecteAutorisee(it) } == true
    }.toList()

    /** Port à ouvrir : celui demandé, sinon le service principal de l'appareil (ou null si plusieurs, à demander). */
    fun portPrincipal(e: JSONObject): Int? {
        val ports = e.optJSONArray("ports")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
        val utiles = SERVICES.keys.filter { it in ports && it !in SENSIBLES }
        return utiles.singleOrNull() ?: ports.singleOrNull()
    }

    /** Port externe libre : le même que l'appareil s'il n'est pas déjà pris, sinon un port haut libre. */
    fun portExterne(portLan: Int, pris: Set<Int>): Int {
        if (portLan !in pris && portLan >= 1024 || (portLan in setOf(80, 443) && portLan !in pris)) return portLan
        var p = 40000 + portLan % 10000
        while (p in pris) p++
        return p
    }

    /**
     * Rend [appareil] (nom ou IP) joignable depuis Internet sur [port] (service principal si vide). [confirm] : une seule
     * confirmation pour l'ensemble (bail fixe + redirection).
     */
    suspend fun configurer(app: JarvisApp, appareil: String, port: Int?, confirm: suspend (String, Boolean) -> Boolean): String {
        val e = Registre.trouver(app, appareil)
            ?: return "Appareil « $appareil » introuvable dans l'inventaire : ouvre l'onglet Wi-Fi (ou dis « analyse le réseau ») puis réessaie."
        val nom = Registre.nomDe(e); val ip = e.optString("ip"); val mac = e.optString("mac")
        val ports = e.optJSONArray("ports")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
        val p = port ?: portPrincipal(e)
            ?: return "CHOIX_REQUIS : quel service de « $nom » rendre accessible ? Ports ouverts : " +
                (if (ports.isEmpty()) "aucun détecté (précise le port)" else ports.joinToString { "$it (${service(it)})" }) + "."
        if (tunnelObligatoire(p)) return "⛔ Le port $p (${service(p)}) ne sera pas redirigé directement sur Internet : il expose une interface d’administration ou un protocole insuffisamment protégé. Utilise le tunnel SSH sécurisé du serveur ou le VPN WireGuard. Pour Home Assistant, configure une URL distante HTTPS dans Réglages › Maison › Home Assistant."
        if (!publicationDirecteAutorisee(p)) return "⛔ La publication directe du port $p (${service(p)}) n'est pas autorisée. JARVIS n'ouvre directement que les services HTTPS connus (443, 8443, 9443). Pour les autres appareils, utilise le tunnel SSH sécurisé ou WireGuard ; pour Plex, utilise l'accès distant Plex."
        val publique = (FreeboxOs.ipPublique(app) ?: Reseau.ippPublique()).ifBlank { Registre.derniereIpPublique(app) }
        if (app.store.freeboxAppToken.value.isBlank())
            return "Pour ouvrir l'accès automatiquement, associe d'abord JARVIS à la Freebox (Réglages › Maison › Box Internet). " +
                "À faire à la main sinon : réserver $ip${if (mac.isNotBlank()) " pour $mac" else ""} et rediriger le port $p vers $ip:$p dans l'interface de la box." +
                (if (publique.isNotBlank()) " Adresse publique actuelle : $publique." else "")
        val sensible = p in SENSIBLES
        val externe = portExterne(p, FreeboxOs.portsRediriges(app))
        if (!confirm("Rendre « $nom » ($ip, ${service(p)}) accessible depuis Internet ? JARVIS va réserver son adresse IP et ouvrir le port $externe de la Freebox vers $ip:$p.\n\nVÉRIFICATION OBLIGATOIRE : confirme uniquement si tu as configuré un reverse proxy HTTPS/TLS réel sur ce port, avec certificat valide et sans accès HTTP non chiffré. Le numéro 443/8443/9443 ne garantit pas le chiffrement du service derrière la box.", true))
            return "Annulé : rien n'a été modifié."
        val oui: suspend (String, Boolean) -> Boolean = { _, _ -> true }
        val bail = if (mac.isNotBlank()) runCatching { FreeboxOs.gerer(app, "ajouter_bail", JSONObject().put("mac", mac).put("ip", ip).put("commentaire", "JARVIS · $nom"), oui) }
            .getOrElse { "⚠️ ${it.message}" } else "⚠️ Adresse MAC inconnue : IP non réservée (elle pourrait changer)."
        val redir = FreeboxOs.gerer(app, "ajouter_redirection", JSONObject().put("ip", ip).put("port", p.toString()).put("port_externe", externe.toString())
            .put("https_verifie", true).put("protocole", "tcp").put("commentaire", "JARVIS · $nom · ${service(p)}"), oui)
        if (!redir.contains("✅")) return "❌ La redirection n'a pas pu être créée : $redir"
        val schema = when (p) { 443, 8443, 9443 -> "https"; 80, 8123, 32400, 8080, 8000, 5000, 9000 -> "http"; 554 -> "rtsp"; 22 -> "ssh"; else -> "" }
        val adresse = if (publique.isBlank()) "" else (if (schema == "ssh") "ssh -p $externe utilisateur@$publique" else (if (schema.isBlank()) "" else "$schema://") + "$publique:$externe")
        Registre.noterAcces(app, ip, JSONObject().put("port", p).put("externe", externe).put("service", service(p)).put("adresse", adresse)
            .put("le", System.currentTimeMillis()), p)
        return "🌍 « $nom » est accessible depuis Internet" + (if (adresse.isNotBlank()) " : **$adresse**" else "") + "\n" +
            redir.lines().joinToString("\n") { "- $it" } + "\n- " + (if (bail.contains("✅")) bail else bail.removePrefix("⚠️ ").let { "IP fixe : $it" }) +
            "\nℹ️ Si ton IP publique change (adresse dynamique), dis « rafraîchis l'accès à distance » : JARVIS donne la nouvelle adresse." +
            (if (sensible) "\n⚠️ Service sensible ouvert : coupe-le dès que tu n'en as plus besoin (« ferme l'accès à distance de $nom »)." else "")
    }

    /** Ferme l'accès à distance d'un appareil (toutes ses redirections créées par JARVIS, ou celle du port donné). */
    suspend fun fermer(app: JarvisApp, appareil: String, port: Int?, confirm: suspend (String, Boolean) -> Boolean): String {
        val e = Registre.trouver(app, appareil) ?: return "Appareil « $appareil » introuvable dans l'inventaire."
        val acces = e.optJSONArray("distant")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty().filter { port == null || it.optInt("port") == port }
        if (acces.isEmpty()) return "Aucun accès à distance ouvert par JARVIS pour « ${Registre.nomDe(e)} »."
        if (!confirm("Fermer l'accès à distance de « ${Registre.nomDe(e)} » (${acces.joinToString { "port ${it.optInt("externe")}" }}) ?", true)) return "Annulé."
        val oui: suspend (String, Boolean) -> Boolean = { _, _ -> true }
        val sortie = mutableListOf<String>()
        for (a in acces) {
            sortie += runCatching { FreeboxOs.gerer(app, "supprimer_redirection", JSONObject().put("port", a.optInt("externe").toString()), oui) }.getOrElse { "❌ ${it.message}" }
            Registre.noterAcces(app, e.optString("ip"), null, a.optInt("port"))
        }
        return sortie.joinToString("\n")
    }
}
