package fr.dd.jarvis.device

import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import fr.dd.jarvis.JarvisApp

/**
 * Réglages avancés du réseau mobile : choix 4G / 5G (réglé par JARVIS avec Shizuku / l'ADB intégré, état d'avant
 * gardé pour revenir en arrière), cartes SIM (double SIM : laquelle sert aux données, aux appels, aux SMS), et vraie
 * 5G (la 5G « NSA » s'appuie sur la 4G : l'icône 5G s'affiche alors que la cellule principale est en 4G).
 */
object ReseauReglages {

    // ------------------------------------------------------------------ types de réseau autorisés (masque Android)

    /** Bit d'un type de réseau Android (NETWORK_TYPE_*) dans le masque des réseaux autorisés : 1 << (type - 1). */
    private fun bit(type: Int) = 1L shl (type - 1)
    internal val BIT_NR = bit(20)
    private val BIT_LTE = bit(13) or bit(19)
    /** 2G / 3G / 4G (sans 5G), tel que le propose l'écran « Type de réseau préféré ». */
    internal val PRESET_4G = listOf(1, 2, 3, 8, 9, 10, 13, 15, 16, 17, 19).fold(0L) { m, t -> m or bit(t) }
    internal val PRESET_5G = PRESET_4G or BIT_NR

    private val NOMS = mapOf("GPRS" to 1, "EDGE" to 2, "UMTS" to 3, "CDMA" to 4, "EVDO_0" to 5, "EVDO_A" to 6, "1XRTT" to 7, "HSDPA" to 8, "HSUPA" to 9,
        "HSPA" to 10, "IDEN" to 11, "EVDO_B" to 12, "LTE" to 13, "EHRPD" to 14, "HSPAP" to 15, "GSM" to 16, "TD_SCDMA" to 17, "IWLAN" to 18, "LTE_CA" to 19, "NR" to 20)

    /** Masque lu dans la sortie de `cmd phone get-allowed-network-types-for-users` (binaire, décimal ou noms) ; (masque, binaire ?). */
    internal fun lireMasque(sortie: String): Pair<Long, Boolean>? {
        val t = sortie.trim()
        if (t.isBlank() || Regex("(?i)(unknown|exception|error|denied|not allowed|usage:|invalid)").containsMatchIn(t)) return null
        Regex("\\b[01]{8,32}\\b").find(t)?.let { return java.lang.Long.parseLong(it.value, 2) to true }
        val noms = Regex("[A-Z0-9_]+").findAll(t.uppercase()).mapNotNull { NOMS[it.value] }.toList()
        if (noms.size >= 2) return noms.fold(0L) { m, ty -> m or bit(ty) } to true
        Regex("\\b\\d{3,9}\\b").find(t)?.let { return it.value.toLong() to false }
        return null
    }

    internal fun ecrireMasque(m: Long, binaire: Boolean): String = if (binaire) java.lang.Long.toBinaryString(m) else m.toString()

    /**
     * Nouveau masque pour [cible] (« 4g » : la 5G retirée ; « 5g » : la 5G ajoutée), sans jamais toucher au reste — et
     * jamais sans la 4G (un masque sans 4G laisserait le téléphone en 3G / 2G, voire sans réseau).
     */
    internal fun appliquer(m: Long, cible: String): Long {
        val base = if (m and BIT_LTE == 0L) m or BIT_LTE else m
        return if (cible == "4g") base and BIT_NR.inv() else base or BIT_NR
    }

    internal fun libelle(m: Long): String = when {
        m and BIT_NR != 0L -> "5G / 4G automatique"
        m and BIT_LTE != 0L -> "4G (5G désactivée)"
        else -> "3G / 2G seulement"
    }

    // ------------------------------------------------------------------ téléphone

    private fun executer(app: JarvisApp, cmd: String, delaiMs: Long = 10_000): String {
        var out = ""
        val t = Thread { out = runCatching { PhoneShell.exec(app, cmd) }.getOrElse { "Erreur : ${it.message}" } }.apply { isDaemon = true }
        t.start()
        t.join(delaiMs)
        if (t.isAlive) {
            t.interrupt()
            return "⏱️ Commande réseau interrompue après $delaiMs ms : résultat non vérifiable."
        }
        return out
    }

    /** Emplacement (0, 1) de la SIM utilisée pour les données. */
    private fun emplacementDonnees(): Int = runCatching {
        if (Build.VERSION.SDK_INT >= 29) SubscriptionManager.getSlotIndex(SubscriptionManager.getDefaultDataSubscriptionId()).takeIf { it >= 0 } else null
    }.getOrNull() ?: 0

    private fun prefs(app: JarvisApp) = app.getSharedPreferences("reseau_mobile", 0)

    /** Types de réseau autorisés actuellement, ou null (pas de Shizuku / ADB, ou téléphone qui refuse). */
    fun masqueActuel(app: JarvisApp): Pair<Long, Boolean>? {
        if (!PhoneShell.ready(app)) return null
        return lireMasque(executer(app, "cmd phone get-allowed-network-types-for-users -s ${emplacementDonnees()}"))
    }

    /**
     * Passe en « 4g » (5G coupée), « 5g » (5G réactivée) ou « avant » (réglage d'avant le dernier changement). Toujours
     * après accord ; vérifié après coup, et remis comme avant si le téléphone n'a pas pris le réglage.
     */
    suspend fun choisirType(app: JarvisApp, cibleBrute: String, confirm: suspend (String, Boolean) -> Boolean): String {
        val cible = cibleBrute.lowercase().let { c -> when { c.contains("avant") || c.contains("restaur") -> "avant"; c.contains("4") -> "4g"; else -> "5g" } }
        // L'écran « Type de réseau préféré » n'est ouvert qu'en solution de repli.
        fun ecran() = ReseauMobile.ouvrirReglage(app, "reseau")
        if (!PhoneShell.ready(app)) return "📡 Pour choisir la 4G ou la 5G moi-même, il me faut Shizuku ou l'ADB intégré (Réglages › Téléphone › Contrôle). " +
            "Sinon : Type de réseau préféré.\n${ecran()}"
        val slot = emplacementDonnees()
        val (actuel, binaire) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { masqueActuel(app) }
            ?: return "📡 Ce téléphone ne me laisse pas lire son type de réseau : règle-le dans Type de réseau préféré.\n${ecran()}"
        val nouveau = when (cible) {
            "avant" -> prefs(app).getLong("masque_avant", -1L).takeIf { it > 0 } ?: return "Je n'ai pas de réglage précédent à remettre (actuel : ${libelle(actuel)})."
            else -> appliquer(actuel, cible)
        }
        if (nouveau == actuel) return "📡 C'est déjà le cas : ${libelle(actuel)}."
        val pourquoi = if (cible == "4g") " La 4G seule est souvent plus stable quand la 5G est faible (appels, données) ; elle consomme aussi moins de batterie." else ""
        if (!confirm("Je passe le réseau mobile en ${libelle(nouveau)} (actuellement ${libelle(actuel)}).$pourquoi Le réseau se coupe 2 à 3 secondes. On y va ?", true))
            return "Changement de type de réseau annulé."
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            prefs(app).edit().putLong("masque_avant", actuel).apply()
            executer(app, "cmd phone set-allowed-network-types-for-users -s $slot ${ecrireMasque(nouveau, binaire)}")
            Thread.sleep(1_500)
            val verifie = masqueActuel(app)?.first
            if (verifie == null || (verifie and BIT_NR != 0L) != (nouveau and BIT_NR != 0L)) {
                executer(app, "cmd phone set-allowed-network-types-for-users -s $slot ${ecrireMasque(actuel, binaire)}")
                return@withContext "❌ Le téléphone n'a pas pris le réglage : rien n'a changé. Tu peux le faire dans Type de réseau préféré.\n${ecran()}"
            }
            Thread.sleep(3_000)
            "✅ Réseau mobile : ${libelle(verifie)}.\n" + ReseauMobile.ligneSignalActuelle(app) + "\n(« remets le réseau comme avant » pour revenir en arrière.)"
        }
    }

    // ------------------------------------------------------------------ appels Wi-Fi

    private val LIBELLE_WFC = Regex("(?i)(appels? (via |par |en |sur )?(le )?wi.?fi|wi.?fi calling|vowifi|appel wi.?fi|wlan call)")
    private val PREFERENCE = Regex("(?i)(pr[ée]f[ée]rence|mode d.appel|calling preference|mode de connexion)")
    private val WIFI_PREFERE = Regex("(?i)(wi.?fi (de )?pr[ée]f[ée]r|pr[ée]f[ée]rer le wi.?fi|wi.?fi preferred|privil[ée]gier le wi.?fi)")

    private fun noeuds(racine: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> {
        val out = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
        fun p(n: android.view.accessibility.AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 30 || out.size > 600) return
            out += n
            for (i in 0 until n.childCount) p(n.getChild(i), d + 1)
        }
        p(racine, 0)
        return out
    }

    private fun texte(n: android.view.accessibility.AccessibilityNodeInfo) = (n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty()).trim()

    /** L'interrupteur lié à l'étiquette [n] : lui-même, ou un élément « cochable » de sa ligne (jusqu'à 3 niveaux au-dessus). */
    private fun interrupteur(n: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        if (n.isCheckable) return n
        var p: android.view.accessibility.AccessibilityNodeInfo? = n.parent
        repeat(3) { val c = p ?: return null; noeuds(c).firstOrNull { it.isCheckable }?.let { return it }; p = c.parent }
        return null
    }

    private fun cliquer(n: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        var c: android.view.accessibility.AccessibilityNodeInfo? = n
        repeat(4) { val x = c ?: return false; if (x.isClickable) return x.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK); c = x.parent }
        return false
    }

    /**
     * Active les appels Wi-Fi (et le mode « Wi-Fi de préférence » quand l'écran le propose) : par l'écran de réglage,
     * piloté par l'accessibilité de JARVIS, avec vérification de l'interrupteur ; sinon (Android ≤ 13 + Shizuku) par le
     * réglage de la SIM ; sinon l'écran est ouvert. Une ligne de compte rendu.
     */
    suspend fun activerAppelsWifi(app: JarvisApp): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (app.enVoiture) return@withContext "🚗 En voiture, je n'ouvre pas les réglages : les appels Wi-Fi s'activeront une fois arrêté."
        val acc = fr.dd.jarvis.service.JarvisAccessibilityService.instance
        if (acc == null) {
            // Sans accessibilité : réglage de la SIM (Android 13 et avant ; à partir d'Android 14 il n'est plus relu ainsi).
            val sub = runCatching { SubscriptionManager.getDefaultVoiceSubscriptionId() }.getOrDefault(-1)
            if (Build.VERSION.SDK_INT < 34 && sub >= 0 && PhoneShell.ready(app)) {
                executer(app, "content update --uri content://telephony/siminfo --bind wfc_ims_enabled:i:1 --bind wfc_ims_mode:i:2 --where ${ApnConfig.q("_id=$sub")}")
                val lu = executer(app, "content query --uri content://telephony/siminfo --projection wfc_ims_enabled:wfc_ims_mode --where ${ApnConfig.q("_id=$sub")}")
                if (lu.contains("wfc_ims_enabled=1")) return@withContext "✅ Appels Wi-Fi activés (Wi-Fi de préférence)."
            }
            return@withContext "📞 Appels Wi-Fi : active-les dans l'écran ouvert (le service d'accessibilité de JARVIS me permettrait de le faire moi-même). " + ReseauMobile.ouvrirReglage(app, "appels_wifi")
        }
        val ouvert = ReseauMobile.ouvrirReglage(app, "appels_wifi")
        if (!ouvert.startsWith("⚙️")) return@withContext "📞 Appels Wi-Fi : $ouvert"
        Thread.sleep(1_800)
        var retours = 1
        fun chercher() = noeuds(acc.rootInActiveWindow).firstOrNull { LIBELLE_WFC.containsMatchIn(texte(it)) }
        var etiquette = chercher()
        var inter = etiquette?.let { interrupteur(it) }
        // Écran du réseau mobile (repli) : la ligne « Appels Wi-Fi » mène à sa page.
        if (etiquette != null && inter == null) { cliquer(etiquette); Thread.sleep(1_500); retours++; etiquette = chercher(); inter = etiquette?.let { interrupteur(it) } }
        val resultat = when {
            inter == null -> "📞 Je n'ai pas trouvé l'interrupteur des appels Wi-Fi sur cet écran : active-les à la main (Réglages › Cartes SIM et réseaux mobiles › ta SIM › Appels Wi-Fi). Ton opérateur doit les proposer."
            inter.isChecked -> "✅ Appels Wi-Fi déjà activés."
            else -> {
                if (!inter.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) cliquer(inter)
                Thread.sleep(900)
                val verifie = chercher()?.let { interrupteur(it) }?.apply { refresh() }?.isChecked == true
                if (verifie) "✅ Appels Wi-Fi activés." else "⚠️ J'ai appuyé sur l'interrupteur des appels Wi-Fi mais je ne peux pas confirmer qu'il est activé : vérifie l'écran."
            }
        }
        // Préférence « Wi-Fi de préférence », si l'écran la propose.
        var pref = ""
        if (resultat.startsWith("✅")) noeuds(acc.rootInActiveWindow).firstOrNull { PREFERENCE.containsMatchIn(texte(it)) }?.let { ligne ->
            if (WIFI_PREFERE.containsMatchIn(noeuds(ligne.parent ?: ligne).joinToString(" ") { texte(it) })) pref = " Mode « Wi-Fi de préférence » déjà choisi."
            else if (cliquer(ligne)) {
                Thread.sleep(1_000)
                noeuds(acc.rootInActiveWindow).firstOrNull { WIFI_PREFERE.containsMatchIn(texte(it)) }?.let { if (cliquer(it)) pref = " Mode « Wi-Fi de préférence » choisi." }
            }
        }
        repeat(retours) { acc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); Thread.sleep(300) }
        resultat + pref
    }

    // ------------------------------------------------------------------ cartes SIM

    data class Sim(val emplacement: Int, val nom: String, val operateur: String, val donnees: Boolean, val appels: Boolean, val sms: Boolean, val subId: Int)

    /** Cartes SIM actives (permission « téléphone »), ou liste vide. */
    fun sims(app: JarvisApp): List<Sim> {
        if (androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        val sm = app.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val d = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }.getOrDefault(-1)
        val v = runCatching { SubscriptionManager.getDefaultVoiceSubscriptionId() }.getOrDefault(-1)
        val s = runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }.getOrDefault(-1)
        @Suppress("MissingPermission")
        return runCatching { sm.activeSubscriptionInfoList.orEmpty() }.getOrDefault(emptyList()).map { i ->
            Sim(i.simSlotIndex + 1, i.displayName?.toString().orEmpty(), i.carrierName?.toString().orEmpty(), i.subscriptionId == d, i.subscriptionId == v, i.subscriptionId == s, i.subscriptionId)
        }.sortedBy { it.emplacement }
    }

    fun texteSims(app: JarvisApp): String {
        val l = sims(app)
        if (l.isEmpty()) return "💳 Cartes SIM : autorise la permission « téléphone » de JARVIS (Réglages › Autoriser toutes les permissions) pour les voir."
        val tm = app.getSystemService(TelephonyManager::class.java)
        return "💳 Cartes SIM (${l.size}) :\n" + l.joinToString("\n") { s ->
            val sig = runCatching { ReseauMobile.signalDe(app, tm.createForSubscriptionId(s.subId)) }.getOrNull()
            val roles = listOfNotNull("données".takeIf { s.donnees }, "appels".takeIf { s.appels }, "SMS".takeIf { s.sms }).joinToString(", ")
            "- SIM ${s.emplacement} : ${s.nom.ifBlank { s.operateur }}" + (if (s.operateur.isNotBlank() && !s.operateur.equals(s.nom, true)) " (${s.operateur})" else "") +
                (if (roles.isNotBlank()) " · sert aux $roles" else "") + (sig?.let { " · " + ReseauMobile.resumeSignal(it) }.orEmpty())
        } + (if (l.size > 1) "\nPour changer la SIM des données, des appels ou des SMS : « ouvre les réglages des cartes SIM »." else "")
    }

    // ------------------------------------------------------------------ 5G réelle (affichage opérateur)

    /** « 5G (NSA, avec la 4G) », « 5G+ », « 4G+ »… tel que l'affiche la barre d'état ; vide si inconnu. */
    fun affichage(app: JarvisApp): String {
        if (Build.VERSION.SDK_INT < 31) return ""
        if (androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) return ""
        val tm = app.getSystemService(TelephonyManager::class.java) ?: return ""
        val fini = java.util.concurrent.CountDownLatch(1)
        var info: android.telephony.TelephonyDisplayInfo? = null
        val cb = object : android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.DisplayInfoListener {
            override fun onDisplayInfoChanged(i: android.telephony.TelephonyDisplayInfo) { info = i; fini.countDown() }
        }
        return runCatching {
            tm.registerTelephonyCallback({ it.run() }, cb)
            fini.await(1_200, java.util.concurrent.TimeUnit.MILLISECONDS)
            tm.unregisterTelephonyCallback(cb)
            val i = info ?: return ""
            when (i.overrideNetworkType) {
                android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G (NSA, appuyée sur la 4G)"
                android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G+"
                android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "4G+"
                android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "4G+ (LTE Advanced Pro)"
                else -> if (i.networkType == 20) "5G (autonome)" else ""
            }
        }.getOrDefault("")
    }
}
