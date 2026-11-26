package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp

/**
 * APN : analyse détaillée et configuration par JARVIS.
 *
 * Avec Shizuku / l'ADB intégré (droits « adb »), Android laisse lire et AJOUTER des APN : JARVIS lit tous ceux de ta
 * SIM, repère ce qui freine ou bloque (APN faux, MMS absent ou mal réglé, IPv4 seul, assistance GPS manquante, APN
 * désactivé ou limité à certains réseaux), et peut créer un APN propre « JARVIS » aux valeurs de l'opérateur et le
 * choisir — sans jamais modifier ni supprimer ceux de l'opérateur. L'APN d'avant est gardé : s'il n'y a plus internet
 * après le changement, JARVIS revient seul en arrière ; « remets l'APN d'origine » aussi.
 */
object ApnConfig {
    const val PREFIXE = "JARVIS"

    // ------------------------------------------------------------------ partie pure

    /** Lignes de `content query --uri content://telephony/carriers…` → champs de chaque APN. */
    internal fun lignes(sortie: String): List<Map<String, String>> = sortie.lineSequence()
        .filter { it.trimStart().startsWith("Row:") }
        .mapNotNull { ReseauMobile.interpreterApn(it) }
        .toList()

    internal fun refuse(sortie: String) = ShellResult.isFailure(sortie) || Regex("(?i)(securityexception|no permission|permission denial|not allowed|unknown command|failure \\[)").containsMatchIn(sortie)

    private fun types(a: Map<String, String>) = a["type"].orEmpty().lowercase().split(',').map { it.trim() }.filter { it.isNotBlank() }
    internal fun estDonnees(a: Map<String, String>) = types(a).let { it.isEmpty() || "default" in it || "*" in it }
    private fun estMms(a: Map<String, String>) = types(a).let { "mms" in it || "*" in it }

    /** Bits LTE / 5G du masque des réseaux d'un APN (0 = tous). */
    private const val BITS_4G_5G = (1L shl 12) or (1L shl 18) or (1L shl 19)

    data class Analyse(val problemes: List<String>, val ameliorations: List<String>)

    /**
     * Ce qui ne va pas ([actuel] = APN en service, [tous] = APN de la SIM) au regard des valeurs de l'opérateur.
     */
    internal fun analyser(actuel: Map<String, String>?, tous: List<Map<String, String>>, reco: ReseauMobile.Apn?): Analyse {
        val p = ArrayList<String>(); val a = ArrayList<String>()
        val donnees = actuel ?: tous.firstOrNull { estDonnees(it) }
        if (donnees == null) p += "Aucun APN internet pour cette SIM : pas de données mobiles possibles."
        donnees?.let { d ->
            if (reco != null && !d["apn"].orEmpty().equals(reco.apn, true))
                p += "L'APN internet en service est « ${d["apn"]} » au lieu de « ${reco.apn} » (${reco.operateur}) : c'est souvent la cause d'internet lent ou absent."
            if (d["carrier_enabled"] == "0") p += "L'APN en service est DÉSACTIVÉ."
            d["network_type_bitmask"]?.toLongOrNull()?.let { m -> if (m != 0L && m and BITS_4G_5G == 0L) p += "L'APN en service est limité à la 2G / 3G : il n'est pas utilisé en 4G ni en 5G." }
            val proto = d["protocol"].orEmpty().uppercase()
            if (proto == "IP") a += "Protocole « IPv4 » seul : « IPv4/IPv6 » (IPV4V6) permet l'IPv6 de l'opérateur — connexions plus directes, moins de traduction d'adresses."
            if (!types(d).contains("supl") && !types(d).contains("*")) a += "Type sans « supl » : l'assistance GPS de l'opérateur n'est pas utilisée (position plus lente à trouver). Type conseillé : default,supl."
            if (reco != null && reco.utilisateur.isBlank() && d["user"].orEmpty().isNotBlank())
                a += "Un nom d'utilisateur est renseigné (« ${d["user"]} ») alors que ${reco.operateur} n'en demande pas : inutile, à laisser vide."
        }
        // MMS.
        val mms = tous.filter { estMms(it) }
        if (reco != null) {
            if (mms.isEmpty()) p += "Aucun APN MMS : les MMS (photos par message, messages de groupe) ne peuvent pas arriver."
            else if (mms.none { it["mmsc"].orEmpty().trimEnd('/').equals(reco.mmsc.trimEnd('/'), true) })
                p += "MMSC différent de celui de ${reco.operateur} (${mms.first()["mmsc"].orEmpty().ifBlank { "vide" }} au lieu de ${reco.mmsc}) : MMS bloqués."
            else mms.firstOrNull { it["mmsc"].orEmpty().trimEnd('/').equals(reco.mmsc.trimEnd('/'), true) }?.let { m ->
                if (reco.proxyMms.isNotBlank() && m["mmsproxy"].orEmpty() != reco.proxyMms) p += "Proxy MMS « ${m["mmsproxy"].orEmpty().ifBlank { "vide" }} » au lieu de ${reco.proxyMms}:${reco.portMms} : MMS bloqués."
            }
        }
        return Analyse(p, a)
    }

    internal fun q(v: String) = "'" + v.replace("'", "'\\''") + "'"

    /** Commandes d'ajout des APN « JARVIS » (internet, et MMS si l'opérateur en a un distinct). */
    internal fun commandesAjout(reco: ReseauMobile.Apn, mccMnc: String, avecMms: Boolean, protocole: String = "IPV4V6",
                                mtu: List<String> = emptyList(), suffixe: String = ""): List<Pair<String, String>> {
        val mcc = mccMnc.take(3); val mnc = mccMnc.drop(3)
        fun ajout(nom: String, apn: String, type: String, extra: List<String>) = nom to (listOf("content insert --uri content://telephony/carriers",
            "--bind ${q("name:s:$nom")}", "--bind ${q("numeric:s:$mccMnc")}", "--bind ${q("mcc:s:$mcc")}", "--bind ${q("mnc:s:$mnc")}",
            "--bind ${q("apn:s:$apn")}", "--bind ${q("type:s:$type")}", "--bind protocol:s:$protocole", "--bind roaming_protocol:s:$protocole",
            "--bind carrier_enabled:i:1") + extra).joinToString(" ")
        val ident = buildList {
            if (reco.utilisateur.isNotBlank()) { add("--bind ${q("user:s:${reco.utilisateur}")}"); add("--bind ${q("password:s:${reco.motDePasse}")}"); add("--bind authtype:i:1") }
        }
        val mmsChamps = buildList {
            add("--bind ${q("mmsc:s:${reco.mmsc}")}")
            if (reco.proxyMms.isNotBlank()) { add("--bind ${q("mmsproxy:s:${reco.proxyMms}")}"); add("--bind ${q("mmsport:s:${reco.portMms}")}") }
        }
        val op = reco.operateur.replace(' ', '-') + suffixe
        return if (reco.mmsApn.equals(reco.apn, true))
            listOf(ajout("$PREFIXE-$op", reco.apn, "default,supl,mms", ident + mmsChamps + mtu))
        else listOfNotNull(ajout("$PREFIXE-$op", reco.apn, "default,supl", ident + mtu),
            if (avecMms) ajout("$PREFIXE-$op-MMS", reco.mmsApn, "mms", ident + mmsChamps) else null)
    }

    // ------------------------------------------------------------------ téléphone

    internal fun executer(app: JarvisApp, cmd: String, delaiMs: Long = 10_000): String {
        var out = ""
        val t = Thread { out = runCatching { PhoneShell.exec(app, cmd) }.getOrElse { "Erreur : ${it.message}" } }.apply { isDaemon = true }
        t.start()
        t.join(delaiMs)
        if (t.isAlive) {
            t.interrupt()
            return "⏱️ Commande APN interrompue après $delaiMs ms : résultat non vérifiable."
        }
        return out
    }

    private fun subDonnees(): Int = runCatching { android.telephony.SubscriptionManager.getDefaultDataSubscriptionId() }.getOrDefault(-1)
    internal fun uriPrefere() = subDonnees().let { if (it >= 0) "content://telephony/carriers/preferapn/subId/$it" else "content://telephony/carriers/preferapn" }
    private fun prefs(app: JarvisApp) = app.getSharedPreferences("reseau_mobile", 0)

    data class Lecture(val actuel: Map<String, String>?, val tous: List<Map<String, String>>, val refuse: Boolean)

    /** APN de la SIM des données et APN en service (null si pas de Shizuku / ADB). */
    fun lire(app: JarvisApp, mccMnc: String): Lecture? {
        if (!PhoneShell.ready(app)) return null
        val tous = executer(app, "content query --uri content://telephony/carriers --where ${q("numeric='$mccMnc'")}")
        val pref = executer(app, "content query --uri ${uriPrefere()}").let { if (lignes(it).isEmpty()) executer(app, "content query --uri content://telephony/carriers/preferapn") else it }
        return Lecture(lignes(pref).firstOrNull(), lignes(tous), refuse(tous) && lignes(tous).isEmpty())
    }

    /** Bilan lisible de l'APN, avec ce qui peut l'améliorer. */
    fun bilan(app: JarvisApp, e: ReseauMobile.Etat): String? {
        val l = lire(app, e.mccMnc) ?: return null
        if (l.refuse) return "🔒 Ce téléphone refuse la lecture des APN, même avec Shizuku : la configuration se fera par l'écran des APN."
        val reco = ReseauMobile.apnRecommande(e.mccMnc, e.marqueSim)
        val an = analyser(l.actuel, l.tous, reco)
        val out = ArrayList<String>()
        out += "📋 APN de la SIM (${l.tous.size}) : " + l.tous.joinToString(" · ") { "${it["name"].orEmpty()} (${it["apn"].orEmpty()}, ${it["type"].orEmpty().ifBlank { "tous types" }})" }
        if (an.problemes.isEmpty() && an.ameliorations.isEmpty()) out += "✅ APN conformes aux valeurs de l'opérateur."
        if (an.problemes.isNotEmpty()) out += "❌ Problèmes :\n" + an.problemes.joinToString("\n") { "- $it" }
        if (an.ameliorations.isNotEmpty()) out += "💡 Améliorations possibles :\n" + an.ameliorations.joinToString("\n") { "- $it" }
        if (reco != null && (an.problemes.isNotEmpty() || an.ameliorations.isNotEmpty()))
            out += "🛠️ « Jarvis, configure l'APN » : je crée un APN « $PREFIXE-${reco.operateur.replace(' ', '-')} » aux bonnes valeurs (IPv4/IPv6, assistance GPS, MMS) et je le choisis, après ton accord — ceux de l'opérateur ne sont pas touchés, et je reviens en arrière seul s'il n'y a plus internet.\n🚀 « Jarvis, optimise l'APN à fond » : je mesure l'actuel et plusieurs réglages (IPv4/IPv6, IPv4, MTU) et je garde le plus rapide."
        return out.joinToString("\n")
    }

    /** La connexion mobile marche-t-elle (requête par le réseau mobile) ? Essaie pendant [secondes]. */
    internal fun internetMobileOk(app: JarvisApp, secondes: Int): Boolean {
        val fin = System.currentTimeMillis() + secondes * 1000L
        while (System.currentTimeMillis() < fin) {
            if (runCatching { TestDebit.ping(app) }.getOrDefault(false)) return true
            Thread.sleep(2_000)
        }
        return false
    }

    /**
     * Crée l'APN « JARVIS » aux valeurs de l'opérateur et le choisit (après accord) ; vérifie internet par le réseau
     * mobile et revient à l'APN d'avant en cas d'échec.
     */
    suspend fun configurer(app: JarvisApp, confirm: suspend (String, Boolean) -> Boolean): String {
        val e = ReseauMobile.etat(app)
        val reco = ReseauMobile.apnRecommande(e.mccMnc, e.marqueSim)
            ?: return "🧾 Je ne connais pas les valeurs APN de ${e.marqueSim.ifBlank { "cet opérateur" }} : prends-les sur son site, puis « ouvre les réglages APN »."
        if (e.mccMnc.length < 5) return "🧾 SIM non lisible : impossible de savoir pour quel réseau créer l'APN."
        val ecran = { ReseauMobile.ouvrirReglage(app, "apn") }
        val valeurs = "APN « ${reco.apn} », protocole IPv4/IPv6, type default,supl" + (if (reco.utilisateur.isNotBlank()) ", utilisateur ${reco.utilisateur} / ${reco.motDePasse}" else "") +
            "; MMS : APN « ${reco.mmsApn} », MMSC ${reco.mmsc}" + (if (reco.proxyMms.isNotBlank()) ", proxy ${reco.proxyMms}:${reco.portMms}" else "")
        if (!PhoneShell.ready(app)) return "🧾 Pour configurer l'APN moi-même, il me faut Shizuku ou l'ADB intégré. Sinon, crée un APN avec : $valeurs.\n${ecran()}"
        if (e.enAppel) return "📞 Un appel est en cours : je configurerai l'APN après."
        val lecture = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { lire(app, e.mccMnc) }
        if (lecture == null || lecture.refuse) return "🔒 Ce téléphone refuse la modification des APN, même avec Shizuku. Crée-le à la main : $valeurs.\n${ecran()}"
        if (!confirm("Je crée l'APN « $PREFIXE-${reco.operateur} » ($valeurs) et je le choisis pour les données. Ceux de l'opérateur restent en place ; la connexion mobile se coupe quelques secondes. On y va ?", true))
            return "Configuration de l'APN annulée."
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val avant = lecture.actuel?.get("_id")
            // Anciens APN « JARVIS » remplacés (jamais ceux de l'opérateur).
            executer(app, "content delete --uri content://telephony/carriers --where ${q("name LIKE '$PREFIXE-%' AND numeric='${e.mccMnc}'")}")
            val avecMms = !reco.mmsApn.equals(reco.apn, true)
            val sorties = commandesAjout(reco, e.mccMnc, avecMms).map { (nom, cmd) -> nom to executer(app, cmd) }
            sorties.firstOrNull { refuse(it.second) }?.let { return@withContext "🔒 Android a refusé la création de l'APN (${it.second.take(120)}). Crée-le à la main : $valeurs.\n${ecran()}" }
            val crees = lignes(executer(app, "content query --uri content://telephony/carriers --where ${q("name LIKE '$PREFIXE-%' AND numeric='${e.mccMnc}'")}"))
            val donnees = crees.firstOrNull { estDonnees(it) }?.get("_id")
                ?: return@withContext "❌ L'APN n'a pas été créé (le téléphone n'a rien enregistré). Crée-le à la main : $valeurs.\n${ecran()}"
            if (avant != null && !crees.any { it["_id"] == avant }) prefs(app).edit().putString("apn_avant", avant).apply()
            executer(app, "content insert --uri ${uriPrefere()} --bind apn_id:i:$donnees")
            Thread.sleep(4_000)
            if (!internetMobileOk(app, 20)) {
                avant?.let { executer(app, "content insert --uri ${uriPrefere()} --bind apn_id:i:$it") }
                executer(app, "content delete --uri content://telephony/carriers --where ${q("name LIKE '$PREFIXE-%' AND numeric='${e.mccMnc}'")}")
                return@withContext "↩️ Avec le nouvel APN, internet mobile ne répondait pas : je suis revenu à l'APN d'avant (« ${lecture.actuel?.get("name").orEmpty()} ») et j'ai retiré le mien."
            }
            "✅ APN configuré : « $PREFIXE-${reco.operateur} » ($valeurs) — internet mobile vérifié.\n" + ReseauMobile.ligneSignalActuelle(app) +
                "\n(« remets l'APN d'origine » pour revenir à celui de l'opérateur.)"
        }
    }

    /** Revient à l'APN choisi avant JARVIS et retire les APN « JARVIS ». */
    suspend fun restaurer(app: JarvisApp, confirm: suspend (String, Boolean) -> Boolean): String {
        if (!PhoneShell.ready(app)) return "Il me faut Shizuku ou l'ADB intégré pour remettre l'APN.\n" + ReseauMobile.ouvrirReglage(app, "apn")
        val e = ReseauMobile.etat(app)
        val avant = prefs(app).getString("apn_avant", null)
        if (!confirm("Je remets l'APN de l'opérateur et je retire les APN « $PREFIXE ». On y va ?", true)) return "Annulé."
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (avant != null) executer(app, "content insert --uri ${uriPrefere()} --bind apn_id:i:$avant")
            executer(app, "content delete --uri content://telephony/carriers --where ${q("name LIKE '$PREFIXE-%' AND numeric='${e.mccMnc}'")}")
            prefs(app).edit().remove("apn_avant").apply()
            Thread.sleep(3_000)
            "↩️ APN de l'opérateur remis" + (if (avant == null) " (Android a repris son APN par défaut)" else "") + ".\n" + ReseauMobile.ligneSignalActuelle(app)
        }
    }
}
