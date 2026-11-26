package fr.dd.jarvis.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser

/**
 * Réseau mobile du téléphone : diagnostic (opérateur, 5G/4G, qualité du signal, données, itinérance), APN, réception
 * des SMS / MMS, qualité des appels — et ce qui peut être réglé ou amélioré.
 *
 * Android réserve à l'opérateur l'écriture des APN et du type de réseau : JARVIS LIT tout ce qu'Android permet,
 * explique ce qui ne va pas, applique lui-même les corrections sûres (données mobiles, mode avion, réenregistrement
 * sur l'antenne — avec Shizuku / l'ADB intégré, toujours après confirmation) et ouvre le bon écran de réglage pour le
 * reste, avec les valeurs à saisir. Rien n'est modifié sans ton accord.
 */
object ReseauMobile {

    // ------------------------------------------------------------------ partie pure (testable)

    enum class Qualite(val libelle: String, val pastille: String) {
        EXCELLENTE("excellent", "🟢"), BONNE("bon", "🟢"), MOYENNE("moyen", "🟡"), FAIBLE("faible", "🟠"), TRES_FAIBLE("très faible", "🔴"), AUCUNE("aucun", "⚫")
    }

    /** Signal de la cellule servante. [dbm] : RSRP en 4G / 5G, RSSI en 2G / 3G. */
    data class Signal(val techno: String, val dbm: Int?, val rsrq: Int? = null, val sinr: Int? = null, val niveau: Int? = null, val nsa: Boolean = false)

    /** Qualité à partir des seuils usuels (RSRP 4G/5G ; RSSI 2G/3G), corrigée par le rapport signal / bruit quand il est connu. */
    internal fun qualite(s: Signal?): Qualite {
        val d = s?.dbm ?: return if (s?.niveau != null && s.niveau > 0) listOf(Qualite.TRES_FAIBLE, Qualite.FAIBLE, Qualite.MOYENNE, Qualite.BONNE)[(s.niveau - 1).coerceIn(0, 3)] else Qualite.AUCUNE
        val lte = s.techno == "4G" || s.techno == "5G"
        var q = when {
            lte && d >= -80 -> Qualite.EXCELLENTE
            lte && d >= -90 -> Qualite.BONNE
            lte && d >= -100 -> Qualite.MOYENNE
            lte && d >= -110 -> Qualite.FAIBLE
            lte -> Qualite.TRES_FAIBLE
            d >= -70 -> Qualite.EXCELLENTE
            d >= -85 -> Qualite.BONNE
            d >= -100 -> Qualite.MOYENNE
            d >= -110 -> Qualite.FAIBLE
            else -> Qualite.TRES_FAIBLE
        }
        // Beaucoup de brouillage (SINR < 0 dB) : la qualité réelle est moins bonne que la puissance ne le laisse croire.
        if (s.sinr != null && s.sinr < 0 && q.ordinal < Qualite.FAIBLE.ordinal) q = Qualite.values()[q.ordinal + 1]
        return q
    }

    data class Etat(
        val operateur: String = "",
        val marqueSim: String = "",
        val mccMnc: String = "",
        val simPrete: Boolean = true,
        val simEtat: String = "",
        val signal: Signal? = null,
        val itinerance: Boolean = false,
        val modeAvion: Boolean = false,
        val donnees: Boolean? = null,
        val wifi: Boolean = false,
        val mobileActif: Boolean = false,
        val debitKbps: Int? = null,
        val smsParDefaut: String = "",
        val smsOptimiseBatterie: Boolean? = null,
        val telephoneParDefaut: String = "",
        val nePasDeranger: Boolean = false,
        val stockageLibreGo: Double? = null,
        val dernierSmsIlYaMin: Long? = null,
        val enAppel: Boolean = false,
    )

    /** Ce qui ne va pas et ce qu'il faut faire, du plus important au moins important. */
    internal fun recommandations(e: Etat): List<String> {
        val out = ArrayList<String>()
        val q = qualite(e.signal)
        if (e.modeAvion) out += "✈️ Le mode avion est activé : ni appels, ni SMS, ni données mobiles. (« Jarvis, améliore mon réseau » le coupe après ton accord.)"
        if (!e.simPrete) out += "💳 La carte SIM n'est pas prête (${e.simEtat.ifBlank { "état inconnu" }}) : vérifie qu'elle est bien en place, sinon redémarre le téléphone."
        if (!e.modeAvion && e.simPrete && q == Qualite.AUCUNE)
            out += "📵 Aucun réseau : je peux relancer la recherche d'antenne (« Jarvis, relance le réseau »), sinon déplace-toi vers une fenêtre ou dehors."
        if (q == Qualite.FAIBLE || q == Qualite.TRES_FAIBLE) {
            out += "📶 Signal ${q.libelle} : à l'intérieur, près d'une fenêtre ou en hauteur, la réception est souvent bien meilleure."
            if (e.signal?.techno == "5G" || e.signal?.nsa == true)
                out += "📡 En 5G avec un signal faible, passer en 4G (Réglages › Réseau mobile › Type de réseau préféré) stabilise souvent les appels et les données."
            if (e.wifi) out += "📞 Tu es en Wi-Fi : active les appels Wi-Fi (« ouvre les réglages des appels Wi-Fi ») pour téléphoner et recevoir tes SMS sans dépendre de l'antenne."
            out += "🔄 Une relance de la recherche d'antenne aide quand le téléphone reste accroché à une antenne éloignée (« Jarvis, relance le réseau »)."
        }
        if (e.donnees == false && !e.wifi) out += "🌐 Les données mobiles sont coupées et tu n'es pas en Wi-Fi : plus d'internet ni de MMS (« active les données mobiles »)."
        if (e.itinerance) out += "🌍 Itinérance active : vérifie que l'itinérance des données est autorisée (et ton forfait à l'étranger)."
        if (e.nePasDeranger) out += "🔕 « Ne pas déranger » est actif : les appels et les SMS arrivent sans sonner."
        if (e.smsOptimiseBatterie == true)
            out += "🔋 L'appli SMS${e.smsParDefaut.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()} est soumise à l'optimisation de la batterie : HyperOS peut retarder les messages. Mets-la « Sans restriction » et autorise son démarrage automatique (« ouvre les réglages de l'appli SMS »)."
        e.stockageLibreGo?.takeIf { it < 1.0 }?.let { out += "💾 Stockage presque plein (${"%.1f".format(java.util.Locale.FRANCE, it)} Go libres) : les MMS et les pièces jointes peuvent ne plus arriver." }
        return out
    }

    data class Apn(val operateur: String, val apn: String, val mmsApn: String, val mmsc: String, val proxyMms: String = "", val portMms: String = "",
                   val utilisateur: String = "", val motDePasse: String = "")

    /** Valeurs d'APN usuelles des opérateurs français (et de leurs marques). Null : opérateur inconnu (valeurs à demander à l'opérateur). */
    internal fun apnRecommande(mccMnc: String, marque: String): Apn? {
        val m = normaliser(marque)
        val free = Apn("Free Mobile", "free", "mmsfree", "http://mms.free.fr")
        val orange = Apn("Orange", "orange", "orange.acte", "http://mms.orange.fr", "192.168.10.200", "8080", "orange", "orange")
        val sfr = Apn("SFR", "sl2sfr", "mmssfr", "http://mms1", "10.151.0.1", "8080")
        val bouygues = Apn("Bouygues Telecom", "mmsbouygtel.com", "mmsbouygtel.com", "http://mms.bouyguestelecom.fr/mms/wapenc", "62.201.129.226", "8080")
        // La marque d'abord : un opérateur virtuel (NRJ, La Poste…) emprunte le réseau d'un autre mais a SES propres APN.
        return when {
            Regex("\\bfree\\b").containsMatchIn(m) -> free
            Regex("\\b(orange|sosh)\\b").containsMatchIn(m) -> orange
            Regex("\\b(sfr|red)\\b").containsMatchIn(m) -> sfr
            Regex("\\b(bouygues|b you|byou|bytel)\\b").containsMatchIn(m) -> bouygues
            m.isNotBlank() -> null
            mccMnc == "20815" || mccMnc == "20816" -> free
            mccMnc == "20801" || mccMnc == "20802" -> orange
            mccMnc == "20810" || mccMnc == "20811" || mccMnc == "20813" -> sfr
            mccMnc == "20820" || mccMnc == "20821" || mccMnc == "20888" -> bouygues
            else -> null
        }
    }

    /** Lit la sortie de `content query --uri content://telephony/carriers/preferapn` : champs de l'APN utilisé, ou null. */
    internal fun interpreterApn(sortie: String): Map<String, String>? {
        val ligne = sortie.lineSequence().firstOrNull { it.trimStart().startsWith("Row:") } ?: return null
        val corps = ligne.substringAfter("Row:").trim().substringAfter(' ')
        val champs = LinkedHashMap<String, String>()
        Regex("(?:^|, )([a-z_]+)=").findAll(corps).toList().let { cles ->
            cles.forEachIndexed { i, m ->
                val debut = m.range.last + 1
                val fin = if (i + 1 < cles.size) cles[i + 1].range.first else corps.length
                champs[m.groupValues[1]] = corps.substring(debut, fin).trim().let { if (it == "NULL") "" else it }
            }
        }
        return champs.takeIf { it.containsKey("apn") }
    }

    private val PAS_MOBILE = Regex("\\b(wi ?fi|freebox|box|fibre|adsl|ethernet|serveur|sociaux|social|discord|tiktok|instagram|facebook|youtube|linkedin|" +
        "home assistant|tele|tv|television|enceinte|plex|bot|domotique|neuronal|neurones|entreprise|professionnel|contacts? pro)\\b")
    /** « La maison » n'exclut que si rien ne désigne le réseau du téléphone (« le réseau chez moi » / « la réception à la maison » restent mobiles). */
    private val MAISON_SEULE = Regex("\\bmaison\\b")
    private val INDICE_MOBILE = Regex("\\b(signal|capte|captes|reception|4g|5g|antenne|barres?|appels?|sms|mms|mobile|telephone|portable|operateur|free|orange|sfr|bouygues|apn|dbm)\\b")
    private val SUJET = Regex("\\b(reseau|reseaux|signal|reception|capte|captes|barres?|4g|5g|3g|couverture|antenne|debit mobile|donnees mobiles|connexion mobile|operateur|apn)\\b")

    /**
     * Demande sur le réseau mobile reconnue sans IA → action de l'outil (diagnostic, optimiser, reenregistrer, apn,
     * messages, appels), ou null.
     */
    fun intention(texte: String): String? {
        val n = normaliser(texte).replace(Regex("[?!.,;:]+"), " ").replace(Regex("\\s+"), " ").trim()
        // « Active les appels Wi-Fi » (le Wi-Fi est exclu plus bas : traité avant).
        if (Regex("\\b(active|activer|mets|mettre|allume|autorise|autoriser)\\b.{0,15}\\b(appels? (via |par |en )?(le )?wi ?fi|wifi calling|vowifi)\\b").containsMatchIn(n)) return "appels_wifi"
        if (n.isBlank() || n.split(' ').size > 18 || PAS_MOBILE.containsMatchIn(n)) return null
        if (MAISON_SEULE.containsMatchIn(n) && !INDICE_MOBILE.containsMatchIn(n) && !Regex("\\b(ameliore|ameliorer|optimise|optimiser|booste|booster)\\b").containsMatchIn(n)) return null
        // Réception des SMS / MMS, qualité des appels : diagnostics dédiés.
        // Envoyer / répondre à un message n'est pas un problème de réception (« envoie un SMS à Martin : je serai en retard »).
        val envoi = Regex("\\b(envoie|envoyer|envoi|ecris|ecrire|reponds|repondre|transfere|transferer|dis lui|dire|previens|prevenir|lis|lire)\\b").containsMatchIn(n)
        if (!envoi && Regex("\\b(recois pas|recois plus|recoit pas|recoit plus|ne recois|ne recoit|pas recu|pas recus|jamais recu|arrivent pas|arrivent plus|n arrivent|" +
                "probleme de reception|reception des|arrivent en retard|recois en retard)\\b").containsMatchIn(n) && Regex("\\b(sms|mms|messages?|textos?)\\b").containsMatchIn(n)) return "messages"
        if (Regex("\\btombe (directement |toujours |direct )?sur (la|ma) messagerie\\b").containsMatchIn(n)) return "appels"
        if (!envoi && Regex("\\b(appels?|m appelle|m appellent|appelle)\\b").containsMatchIn(n) && Regex("\\b(coupent|coupe|coupes|coupees|passent pas|passe pas|ne passent|ne passe|" +
                "qualite|gresille|entends? mal|entend mal|m entend pas|m entendent pas|messagerie|manques?|probleme|hache|saccade)\\b").containsMatchIn(n)) return "appels"
        // DNS (du téléphone : la Freebox / le Wi-Fi sont exclus plus haut).
        if (Regex("\\bdns\\b").containsMatchIn(n)) return when {
            Regex("\\b(remets|remettre|restaure|restaurer|retablis|retablir|origine|annule|annuler|enleve|retire|desactive)\\b").containsMatchIn(n) -> "dns_restaurer"
            Regex("\\b(active|activer|mets|mettre|passe|passer|utilise|utiliser|choisis|choisir|prends|configure|configurer)\\b").containsMatchIn(n) &&
                Regex("\\b(cloudflare|google|quad9|quad 9|1 1 1 1|prive)\\b").containsMatchIn(n) -> "dns_appliquer:" + (Regex("\\b(cloudflare|google|quad9)\\b").find(n.replace("quad 9", "quad9"))?.value ?: "cloudflare")
            else -> "dns"
        }
        if (Regex("\\bmtu\\b").containsMatchIn(n)) return "mtu"
        // Choix 4G / 5G.
        if (Regex("\\b(passe|passer|mets|mettre|force|forcer|bascule|basculer|reste|rester)\\b.{0,20}\\b(en|sur|la)\\b.{0,6}\\b4g\\b|\\b(desactive|desactiver|coupe|couper|enleve|enlever|retire|retirer|sans)\\b.{0,12}\\b5g\\b").containsMatchIn(n)) return "type_reseau:4g"
        if (Regex("\\b(active|activer|remets|remettre|reactive|reactiver|rallume|passe|passer|mets|mettre|bascule|basculer|autorise|autoriser)\\b.{0,20}\\b5g\\b").containsMatchIn(n)) return "type_reseau:5g"
        if (Regex("\\b(remets|remettre|retablis|retablir|restaure|restaurer)\\b.{0,25}\\breseau\\b.{0,20}\\b(comme avant|d avant|precedent)\\b").containsMatchIn(n)) return "type_reseau:avant"
        // Test de débit (du réseau mobile).
        // (« la vitesse du vent », « le débit de mon compte » ne sont pas des tests de connexion.)
        if (!Regex("\\b(vent|riviere|compte|banque|bancaire|carte bancaire|virement|prelevement|eau|robinet|voiture|vehicule)\\b").containsMatchIn(n) &&
            Regex("\\b(test|teste|tester|mesure|mesurer)\\b.{0,12}\\b(debit|vitesse|connexion|internet|latence|ping)\\b|\\bspeed ?test\\b|" +
                "\\b(quel|quelle|combien)\\b.{0,15}\\bdebit\\b|\\b(debit|vitesse|latence|ping|bande passante)\\b.{0,12}\\b(internet|connexion|reseau|4g|5g|mobile|telephone|donnees)\\b").containsMatchIn(n)) return "debit"
        // Historique de la réception.
        if (Regex("\\b(ou|a quelle heure|quand)\\b.{0,20}\\b(capte|captes|capter|recois|reception|reseau)\\b.{0,15}\\b(mal|pas|moins|faible)\\b|\\bhistorique\\b.{0,20}\\b(reseau|reception|signal)\\b").containsMatchIn(n)) return "historique"
        if (Regex("\\b(cartes? sim|double sim|deux sim|ma sim|mes sim|quelle sim)\\b").containsMatchIn(n) && !Regex("\\b(code|pin|puk)\\b").containsMatchIn(n)) return "sim"
        if (!SUJET.containsMatchIn(n)) return null
        if (Regex("\\bapn\\b").containsMatchIn(n)) return when {
            Regex("\\b(remets|remettre|restaure|restaurer|retablis|retablir|reviens|revenir|annule|annuler)\\b").containsMatchIn(n) -> "apn_restaurer"
            // Amélioration par la mesure (réglages comparés, le plus rapide gardé).
            Regex("\\b(ameliore|ameliorer|optimise|optimiser|booste|booster|accelere|accelerer|meilleur|meilleure|teste|tester|test|compare|comparer|a fond|pleinement|completement|au maximum)\\b").containsMatchIn(n) -> "apn_optimiser"
            Regex("\\b(configure|configurer|regle|regler|corrige|corriger|cree|creer|parametre|parametrer|change|changer|modifie|modifier|repare|reparer|mets a jour)\\b").containsMatchIn(n) -> "apn_configurer"
            else -> "apn"
        }
        if (Regex("\\b(relance|relancer|reinitialise|reinitialiser|redemarre|redemarrer|reconnecte|reconnecter|rafraichis|rafraichir)\\b").containsMatchIn(n)) return "reenregistrer"
        if (Regex("\\b(ameliore|ameliorer|optimise|optimiser|repare|reparer|corrige|corriger|regle|regler|booste|booster|arrange|arranger|resous|resoudre)\\b").containsMatchIn(n)) return "optimiser"
        if (Regex("\\b(comment|quel|quelle|quels|quelles|est ce|j ai|ai je|etat|qualite|niveau|teste|tester|verifie|verifier|diagnostic|diagnostique|combien|capte|captes|bien|mal|faible|pourquoi)\\b").containsMatchIn(n)) return "diagnostic"
        return null
    }

    /**
     * Suite d'un échange sur le réseau mobile (« améliore-le », « vas-y, règle ça », « tu peux l'optimiser ? ») : à
     * traiter comme « améliore mon réseau » — sinon le modèle se contente d'expliquer.
     */
    fun suiteAmelioration(texte: String, reponsePrecedente: String): Boolean {
        val n = normaliser(texte).replace(Regex("[?!.,;:]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (n.isBlank() || n.split(' ').size > 12) return false
        val reseau = Regex("(Réseau mobile|dBm|APN|signal|réception|appels Wi-Fi|4G|5G)", RegexOption.IGNORE_CASE).findAll(reponsePrecedente).count() >= 2
        if (!reseau) return false
        return Regex("\\b(ameliore|ameliorer|optimise|optimiser|regle|regler|corrige|corriger|repare|reparer|booste|booster|arrange|arranger|" +
            "vas y|fais le|fais la|fais ca|go|lance|applique|appliquer|fais le necessaire|occupe t en|mieux)\\b").containsMatchIn(n) &&
            !Regex("\\b(musique|alarme|reveil|mail|sms a|message a|planning|lumiere|tele|poele|chauffage|note|facture|devis)\\b").containsMatchIn(n)
    }

    // ------------------------------------------------------------------ lecture du téléphone

    private fun permis(ctx: Context, p: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun libelleAppli(ctx: Context, pkg: String?): String = pkg?.let { p ->
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
    }.orEmpty()

    private fun techno(type: Int): String = when (type) {
        20 -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE, 19 -> "4G"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_TD_SCDMA, TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A -> "3G"
        TelephonyManager.NETWORK_TYPE_GSM, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_CDMA -> "2G"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "Wi-Fi (appels Wi-Fi)"
        else -> ""
    }

    /** Signal lu dans un relevé Android (cellule servante ; 5G NSA = 4G + 5G), ou null s'il n'y a aucun réseau. */
    fun depuis(ss: android.telephony.SignalStrength): Signal? {
        if (Build.VERSION.SDK_INT < 29) return null
        val l = ss.cellSignalStrengths
        val nr = l.filterIsInstance<android.telephony.CellSignalStrengthNr>().firstOrNull { it.ssRsrp != Int.MAX_VALUE }
        val lte = l.filterIsInstance<android.telephony.CellSignalStrengthLte>().firstOrNull { it.rsrp != Int.MAX_VALUE }
        fun v(x: Int) = x.takeIf { it != Int.MAX_VALUE }
        when {
            nr != null && lte != null -> return Signal("4G", v(lte.rsrp), v(lte.rsrq), v(lte.rssnr), lte.level, nsa = true)
            nr != null -> return Signal("5G", v(nr.ssRsrp), v(nr.ssRsrq), v(nr.ssSinr), nr.level)
            lte != null -> return Signal("4G", v(lte.rsrp), v(lte.rsrq), v(lte.rssnr), lte.level)
        }
        return l.firstOrNull { it.dbm != Int.MAX_VALUE && it.dbm < 0 }?.let { c ->
            val t = when (c) { is android.telephony.CellSignalStrengthWcdma, is android.telephony.CellSignalStrengthTdscdma -> "3G"; is android.telephony.CellSignalStrengthGsm -> "2G"; else -> "" }
            Signal(t, c.dbm, niveau = c.level)
        }
    }

    internal fun signalDe(app: JarvisApp, tm: TelephonyManager): Signal? = signal(app, tm)

    /** « 4G · bon 🟢 (-88 dBm) ». */
    fun resumeSignal(s: Signal): String = "${if (s.nsa) "5G NSA" else s.techno.ifBlank { "réseau" }} · ${qualite(s).libelle} ${qualite(s).pastille}" + (s.dbm?.let { " ($it dBm)" }.orEmpty())

    fun ligneSignalActuelle(app: JarvisApp): String = ligneSignal(etat(app))

    /** Signal de la cellule servante, sans permission particulière (API 29+), sinon par les cellules (localisation). */
    private fun signal(ctx: Context, tm: TelephonyManager): Signal? {
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            tm.signalStrength?.let { ss -> depuis(ss)?.let { return it } }
            val l = tm.signalStrength?.cellSignalStrengths.orEmpty()
            val nr = l.filterIsInstance<android.telephony.CellSignalStrengthNr>().firstOrNull { it.ssRsrp != Int.MAX_VALUE }
            val lte = l.filterIsInstance<android.telephony.CellSignalStrengthLte>().firstOrNull { it.rsrp != Int.MAX_VALUE }
            fun v(x: Int) = x.takeIf { it != Int.MAX_VALUE }
            when {
                nr != null && lte != null -> return Signal("4G", v(lte.rsrp), v(lte.rsrq), v(lte.rssnr), lte.level, nsa = true)
                nr != null -> return Signal("5G", v(nr.ssRsrp), v(nr.ssRsrq), v(nr.ssSinr), nr.level)
                lte != null -> return Signal("4G", v(lte.rsrp), v(lte.rsrq), v(lte.rssnr), lte.level)
            }
            l.firstOrNull { it.dbm != Int.MAX_VALUE && it.dbm < 0 }?.let { s ->
                val t = when (s) { is android.telephony.CellSignalStrengthWcdma, is android.telephony.CellSignalStrengthTdscdma -> "3G"; is android.telephony.CellSignalStrengthGsm -> "2G"; else -> "" }
                return Signal(t, s.dbm, niveau = s.level)
            }
        }
        if (permis(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)) runCatching {
            @Suppress("MissingPermission")
            val c = tm.allCellInfo.orEmpty().firstOrNull { it.isRegistered } ?: return null
            return when (c) {
                is android.telephony.CellInfoLte -> Signal("4G", c.cellSignalStrength.rsrp.takeIf { it != Int.MAX_VALUE }, niveau = c.cellSignalStrength.level)
                is android.telephony.CellInfoWcdma -> Signal("3G", c.cellSignalStrength.dbm, niveau = c.cellSignalStrength.level)
                is android.telephony.CellInfoGsm -> Signal("2G", c.cellSignalStrength.dbm, niveau = c.cellSignalStrength.level)
                else -> if (Build.VERSION.SDK_INT >= 29 && c is android.telephony.CellInfoNr) Signal("5G", (c.cellSignalStrength as? android.telephony.CellSignalStrengthNr)?.ssRsrp, niveau = c.cellSignalStrength.level) else null
            }
        }
        return null
    }

    fun etat(app: JarvisApp): Etat {
        val ctx: Context = app
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val reseau = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val simEtat = runCatching { tm.simState }.getOrDefault(TelephonyManager.SIM_STATE_UNKNOWN)
        var sig = runCatching { signal(ctx, tm) }.getOrNull()
        // Type de réseau exact (avec la permission « téléphone ») : complète la techno lue sur le signal.
        if (permis(ctx, android.Manifest.permission.READ_PHONE_STATE)) runCatching {
            @Suppress("MissingPermission") val t = techno(tm.dataNetworkType)
            if (t.isNotBlank() && sig != null && sig.techno.isBlank()) sig = sig.copy(techno = t)
        }
        val smsPkg = runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull()
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        val audio = ctx.getSystemService(android.media.AudioManager::class.java)
        val libre = runCatching { android.os.StatFs(android.os.Environment.getDataDirectory().path).availableBytes / 1e9 }.getOrNull()
        val dernierSms = if (permis(ctx, android.Manifest.permission.READ_SMS)) runCatching {
            ctx.contentResolver.query(android.provider.Telephony.Sms.Inbox.CONTENT_URI, arrayOf("date"), null, null, "date DESC")?.use { c ->
                if (c.moveToFirst()) (System.currentTimeMillis() - c.getLong(0)) / 60_000 else null
            }
        }.getOrNull() else null
        return Etat(
            operateur = runCatching { tm.networkOperatorName }.getOrNull().orEmpty(),
            marqueSim = runCatching { tm.simOperatorName }.getOrNull().orEmpty(),
            mccMnc = runCatching { tm.simOperator }.getOrNull().orEmpty(),
            simPrete = simEtat == TelephonyManager.SIM_STATE_READY,
            simEtat = when (simEtat) {
                TelephonyManager.SIM_STATE_ABSENT -> "absente"; TelephonyManager.SIM_STATE_PIN_REQUIRED -> "code PIN demandé"
                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "code PUK demandé"; TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "bloquée par l'opérateur"
                TelephonyManager.SIM_STATE_READY -> "prête"; else -> "inconnu"
            },
            signal = sig,
            itinerance = runCatching { tm.isNetworkRoaming }.getOrDefault(false),
            modeAvion = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1,
            donnees = runCatching { tm.isDataEnabled }.getOrNull() ?: runCatching { Settings.Global.getInt(ctx.contentResolver, "mobile_data") == 1 }.getOrNull(),
            wifi = reseau?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            mobileActif = reseau?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            debitKbps = reseau?.linkDownstreamBandwidthKbps?.takeIf { it > 0 },
            smsParDefaut = libelleAppli(ctx, smsPkg),
            smsOptimiseBatterie = smsPkg?.let { p -> runCatching { !pm.isIgnoringBatteryOptimizations(p) }.getOrNull() },
            telephoneParDefaut = libelleAppli(ctx, runCatching { ctx.getSystemService(android.telecom.TelecomManager::class.java).defaultDialerPackage }.getOrNull()),
            nePasDeranger = runCatching { nm.currentInterruptionFilter > android.app.NotificationManager.INTERRUPTION_FILTER_ALL }.getOrDefault(false),
            stockageLibreGo = libre,
            dernierSmsIlYaMin = dernierSms,
            enAppel = runCatching { audio.mode == android.media.AudioManager.MODE_IN_CALL || audio.mode == android.media.AudioManager.MODE_IN_COMMUNICATION }.getOrDefault(false),
        )
    }

    // ------------------------------------------------------------------ comptes rendus

    private fun ligneSignal(e: Etat): String {
        val s = e.signal
        val q = qualite(s)
        if (e.modeAvion) return "✈️ Mode avion activé"
        if (s == null || q == Qualite.AUCUNE) return "📵 Aucun réseau mobile"
        val mesures = listOfNotNull(s.dbm?.let { "$it dBm" }, s.rsrq?.let { "RSRQ $it dB" }, s.sinr?.let { "SINR $it dB" }).joinToString(", ")
        val techno = if (s.nsa) "5G (avec la 4G)" else s.techno.ifBlank { "réseau" }
        return "📶 $techno · signal ${q.libelle} ${q.pastille}" + (if (mesures.isNotBlank()) " ($mesures)" else "") + (s.niveau?.let { " · $it/4 barres" }.orEmpty())
    }

    private fun entete(e: Etat): String {
        val op = e.operateur.ifBlank { e.marqueSim }.ifBlank { "opérateur inconnu" }
        val marque = e.marqueSim.takeIf { it.isNotBlank() && !it.equals(e.operateur, true) }?.let { " (SIM $it)" }.orEmpty()
        return "📱 Réseau mobile — $op$marque"
    }

    fun diagnostic(app: JarvisApp): String {
        val e = etat(app)
        val l = ArrayList<String>()
        l += entete(e)
        l += ligneSignal(e)
        l += "🌐 Données mobiles : " + when (e.donnees) { true -> "activées"; false -> "coupées"; null -> "état inconnu" } +
            " · connexion actuelle : " + (if (e.wifi) "Wi-Fi" else if (e.mobileActif) "réseau mobile" else "aucune") +
            (e.debitKbps?.let { " (débit estimé ${if (it >= 1000) "${it / 1000} Mbit/s" else "$it kbit/s"})" }.orEmpty())
        l += "🌍 Itinérance : ${if (e.itinerance) "oui" else "non"}"
        runCatching { ReseauReglages.affichage(app) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { l += "🛰️ Affiché par l'opérateur : $it" }
        runCatching { ReseauReglages.masqueActuel(app) }.getOrNull()?.let { (m, _) -> l += "⚙️ Réseaux autorisés : ${ReseauReglages.libelle(m)}" }
        runCatching { ReseauReglages.sims(app) }.getOrNull()?.takeIf { it.size > 1 }?.let { l += ReseauReglages.texteSims(app) }
        runCatching { ReseauHistorique.resume24h(app) }.getOrNull()?.let { l += it }
        val r = recommandations(e)
        return l.joinToString("\n") + "\n\n" + (if (r.isEmpty()) "✅ Rien d'anormal sur le réseau mobile." else "💡 À faire :\n" + r.joinToString("\n") { "- $it" })
    }

    fun messages(app: JarvisApp): String {
        val e = etat(app)
        val l = ArrayList<String>()
        l += "✉️ Réception des SMS / MMS"
        l += ligneSignal(e)
        l += "📨 Appli SMS par défaut : ${e.smsParDefaut.ifBlank { "inconnue" }}" +
            (when (e.smsOptimiseBatterie) { true -> " · ⚠️ optimisation de batterie ACTIVE"; false -> " · batterie sans restriction ✅"; null -> "" })
        e.dernierSmsIlYaMin?.let { m -> l += "🕒 Dernier SMS reçu : " + when { m < 60 -> "il y a $m min"; m < 1440 -> "il y a ${m / 60} h"; else -> "il y a ${m / 1440} jour(s)" } }
        l += "🌐 MMS : il leur faut les données mobiles" + when (e.donnees) { true -> " (activées ✅)"; false -> " (⚠️ coupées)"; null -> "" }
        val r = recommandations(e).toMutableList()
        apnRecommande(e.mccMnc, e.marqueSim)?.let { a -> r += "🧾 Si les MMS n'arrivent pas : vérifie l'APN MMS de ${a.operateur} → APN « ${a.mmsApn} », MMSC ${a.mmsc}" +
            (if (a.proxyMms.isNotBlank()) ", proxy ${a.proxyMms}:${a.portMms}" else "") + " (« Jarvis, montre-moi l'APN »)." }
        r += "🧹 Messages bloqués : vider le cache de l'appli SMS (pas ses données) et redémarrer le téléphone règle la plupart des retards."
        return l.joinToString("\n") + "\n\n💡 À faire :\n" + r.joinToString("\n") { "- $it" }
    }

    fun appels(app: JarvisApp): String {
        val e = etat(app)
        val l = ArrayList<String>()
        l += "📞 Qualité des appels"
        l += ligneSignal(e)
        l += "☎️ Appli téléphone : ${e.telephoneParDefaut.ifBlank { "inconnue" }}"
        val r = recommandations(e).toMutableList()
        if (e.wifi && r.none { it.contains("appels Wi-Fi") }) r += "📞 Tu es en Wi-Fi : les appels Wi-Fi (VoWiFi) donnent un son plus net quand l'antenne est loin."
        r += "🔁 Tu tombes directement sur ta messagerie ? Compose *#21# pour voir si un renvoi d'appel est actif."
        r += "🗣️ Son haché pendant les appels : les appels 4G (VoLTE) doivent être activés (Réglages › Réseau mobile › VoLTE)."
        return l.joinToString("\n") + "\n\n💡 À faire :\n" + r.joinToString("\n") { "- $it" }
    }

    /** APN utilisé (si Android le laisse lire) comparé aux valeurs de l'opérateur ; [ouvrir] : écran des APN. */
    fun apn(app: JarvisApp, ouvrir: Boolean): String {
        val e = etat(app)
        val reco = apnRecommande(e.mccMnc, e.marqueSim)
        val actuel = if (PhoneShell.ready(app)) runCatching {
            interpreterApn(executer(app, "content query --uri content://telephony/carriers/preferapn", 8_000))
        }.getOrNull() else null
        val l = ArrayList<String>()
        l += "🧾 APN — ${e.marqueSim.ifBlank { e.operateur }.ifBlank { "opérateur inconnu" }}"
        if (actuel != null) l += "En service : « ${actuel["name"].orEmpty()} » · APN ${actuel["apn"].orEmpty()}" +
            (actuel["mmsc"]?.takeIf { it.isNotBlank() }?.let { " · MMSC $it" }.orEmpty())
        else l += "APN en service : non lisible (Android réserve sa lecture à l'opérateur ; possible avec Shizuku sur certains téléphones)."
        if (reco != null) {
            l += "\nValeurs usuelles ${reco.operateur} :"
            l += "• Internet : APN « ${reco.apn} »" + (if (reco.utilisateur.isNotBlank()) ", utilisateur ${reco.utilisateur}, mot de passe ${reco.motDePasse}" else "") + ", type default,supl"
            l += "• MMS : APN « ${reco.mmsApn} », MMSC ${reco.mmsc}" + (if (reco.proxyMms.isNotBlank()) ", proxy ${reco.proxyMms}, port ${reco.portMms}" else "") + ", type mms"
            if (actuel != null && !actuel["apn"].orEmpty().equals(reco.apn, true) && !actuel["apn"].orEmpty().equals(reco.mmsApn, true))
                l += "⚠️ L'APN en service (${actuel["apn"]}) ne correspond pas : c'est souvent la cause d'internet ou de MMS qui ne marchent pas."
            l += "_(valeurs habituelles : en cas de doute, celles du site de ton opérateur font foi)_"
        } else l += "Je ne connais pas les APN de cet opérateur : prends-les sur son site (rubrique « paramètres APN »)."
        runCatching { ApnConfig.bilan(app, e) }.getOrNull()?.let { l += "\n$it" }
        if (ouvrir) l += "\n" + ouvrirReglage(app, "apn")
        else if (!PhoneShell.ready(app)) l += "\nPour les modifier : « Jarvis, ouvre les réglages APN » — ou active Shizuku / l'ADB intégré et dis « configure l'APN » : je le fais pour toi."
        return l.joinToString("\n")
    }

    // ------------------------------------------------------------------ actions

    /** Commande shell (Shizuku / ADB intégré) avec délai maximal ; texte de sortie. */
    private fun executer(app: JarvisApp, cmd: String, delaiMs: Long = 15_000): String {
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

    private fun avionActif(app: JarvisApp) = Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    private fun modeAvion(app: JarvisApp, actif: Boolean): Boolean {
        val v = if (actif) "enable" else "disable"
        for (cmd in listOf("cmd connectivity airplane-mode $v", "settings put global airplane_mode_on ${if (actif) 1 else 0}; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $actif")) {
            executer(app, cmd, 8_000)
            repeat(10) { if (avionActif(app) == actif) return true; Thread.sleep(300) }
        }
        return avionActif(app) == actif
    }

    /**
     * Relance la recherche d'antenne : mode avion 5 secondes, puis reconnexion, mesure avant / après. Jamais pendant un
     * appel ; le mode avion n'est JAMAIS laissé actif (plusieurs tentatives, puis alerte).
     */
    suspend fun reenregistrer(app: JarvisApp, confirm: suspend (String, Boolean) -> Boolean): String {
        val avant = etat(app)
        if (avant.enAppel) return "📞 Un appel est en cours : je ne relance pas le réseau maintenant."
        if (!PhoneShell.ready(app)) return "🔄 Pour relancer le réseau moi-même, il me faut Shizuku ou l'ADB intégré (Réglages › Téléphone › Contrôle). " +
            "Sinon : active le mode avion 5 secondes puis désactive-le.\n" + ouvrirReglage(app, "mode_avion")
        if (!confirm("Je coupe le réseau mobile 5 secondes (mode avion) pour que le téléphone se reconnecte à la meilleure antenne. Un appel entrant pendant ce temps irait sur la messagerie. On y va ?", true))
            return "Relance du réseau annulée."
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (!modeAvion(app, true)) return@withContext "❌ Le mode avion n'a pas pu être activé : rien n'a changé."
            Thread.sleep(5_000)
            if (!modeAvion(app, false) && !modeAvion(app, false))
                return@withContext "⚠️ Le mode avion est resté ACTIVÉ : désactive-le dans les réglages rapides (glisse depuis le haut de l'écran)."
            // Laisse le temps de retrouver le réseau, puis mesure.
            var apres = etat(app)
            var attente = 0
            while (qualite(apres.signal) == Qualite.AUCUNE && attente++ < 20) { Thread.sleep(1_000); apres = etat(app) }
            "🔄 Réseau relancé.\nAvant : ${ligneSignal(avant)}\nAprès : ${ligneSignal(apres)}" +
                (if (qualite(apres.signal).ordinal > qualite(avant.signal).ordinal) "\nLe signal n'est pas meilleur : la couverture est faible ici (fenêtre, extérieur, appels Wi-Fi)." else "")
        }
    }

    /** Diagnostic + corrections sûres appliquées après accord (mode avion, données, antenne) + conseils pour le reste. */
    /**
     * « Améliore mon réseau » : JARVIS AGIT. Il établit un plan d'après la situation réelle (mode avion, données, signal,
     * 5G qui décroche, Wi-Fi disponible), le propose en UNE confirmation, l'exécute, puis compare avant / après et dit
     * honnêtement ce qu'aucun réglage ne peut changer (la puissance de l'antenne) et ce qui le peut (appels Wi-Fi,
     * femtocell, emplacement). Plus jamais un simple exposé.
     */
    suspend fun optimiser(app: JarvisApp, confirm: suspend (String, Boolean) -> Boolean): String {
        val e = etat(app)
        val q = qualite(e.signal)
        val shell = PhoneShell.ready(app)
        val faible = !e.modeAvion && (q == Qualite.FAIBLE || q == Qualite.TRES_FAIBLE || q == Qualite.AUCUNE)
        val cinqG = e.signal?.techno == "5G" || e.signal?.nsa == true ||
            runCatching { ReseauReglages.masqueActuel(app)?.first?.let { it and ReseauReglages.BIT_NR != 0L } }.getOrNull() == true
        val oui: suspend (String, Boolean) -> Boolean = { _, _ -> true }
        // Le plan : (ce que je fais, l'action).
        val plan = ArrayList<Pair<String, suspend () -> String>>()
        if (e.modeAvion && shell) plan += "désactiver le mode avion" to { if (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { modeAvion(app, false) }) "✅ Mode avion désactivé." else "❌ Le mode avion n'a pas pu être désactivé." }
        if (e.donnees == false && !e.wifi && shell) plan += "réactiver les données mobiles" to { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.phone.perform("data_on", "") } }
        if (faible && cinqG && shell) plan += "passer en 4G (avec un signal aussi faible, la 5G décroche et fait chuter appels et données)" to { ReseauReglages.choisirType(app, "4g", oui) }
        if (faible && e.wifi) plan += "activer les appels Wi-Fi en « Wi-Fi de préférence » (appels et SMS passent par ta box quand l'antenne est faible)" to { ReseauReglages.activerAppelsWifi(app) }
        if (faible && shell && !e.enAppel) plan += "relancer la recherche d'antenne (5 s de mode avion : le téléphone se raccroche à la meilleure antenne)" to { reenregistrer(app, oui) }
        val l = ArrayList<String>()
        if (plan.isEmpty()) {
            l += if (faible) "📶 Signal ${q.libelle} (${e.signal?.dbm?.let { "$it dBm" } ?: "aucun"}) et rien que je puisse régler moi-même ici" +
                (if (!shell) " sans Shizuku / l'ADB intégré" else "") + "." else "✅ Ton réseau mobile n'a rien à corriger : ${ligneSignal(e)}."
        } else {
            if (!confirm("Pour améliorer ton réseau, je vais :\n" + plan.mapIndexed { i, p -> "${i + 1}. ${p.first}" }.joinToString("\n") +
                    "\nLa connexion peut se couper quelques secondes. On y va ?", true)) return "Amélioration du réseau annulée."
            l += "🛠️ Fait :"
            for ((quoi, action) in plan) l += "- " + runCatching { action() }.getOrElse { "❌ $quoi : ${it.message}" }.lineSequence().first()
            val apres = etat(app)
            l += "\nAvant : ${ligneSignal(e)}\nAprès : ${ligneSignal(apres)}"
        }
        // Ce qu'aucun réglage ne change, et ce qui le peut.
        if (faible && e.signal?.dbm != null) {
            l += "\n📡 ${e.signal.dbm} dBm, c'est la puissance de l'antenne qui arrive jusqu'au téléphone : aucun réglage (APN compris) ne l'augmente — " +
                "l'APN ne joue que sur l'accès à internet une fois connecté."
            l += "💡 Ce qui améliore vraiment la réception chez toi :"
            if (!e.wifi) l += "- te connecter au Wi-Fi, puis les appels Wi-Fi prennent le relais (« active les appels Wi-Fi ») ;"
            l += "- un boîtier femtocell de l'opérateur (une mini-antenne branchée sur ta box ; Free en propose à ses abonnés Freebox — à demander dans ton espace abonné) ;"
            l += "- poser le téléphone près d'une fenêtre ou à l'étage ; les amplificateurs de signal vendus en ligne sont interdits sans l'accord de l'opérateur."
        } else if (!faible && !e.wifi && shell) l += "\n🚀 Pour gagner en débit : « optimise l'APN » (je cherche moi-même le réglage le plus rapide)."
        if (!shell) l += "\nℹ️ Avec Shizuku ou l'ADB intégré, je peux aussi relancer l'antenne, passer en 4G et couper le mode avion moi-même."
        return l.joinToString("\n")
    }

    /** Ouvre l'écran de réglage voulu (apn, reseau, operateur, appels, appels_wifi, sms, mode_avion, sim). */
    fun ouvrirReglage(app: JarvisApp, cible: String): String {
        if (app.enVoiture) return "🚗 En voiture, je n'ouvre pas les réglages du téléphone : redemande-le une fois arrêté."
        val c = normaliser(cible).replace(' ', '_')
        val smsPkg = runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(app) }.getOrNull()
        val essais: List<Triple<String, String?, String>> = when {
            c.startsWith("apn") -> listOf(Triple(Settings.ACTION_APN_SETTINGS, null, "Réglages APN ouverts."), Triple(Settings.ACTION_DATA_ROAMING_SETTINGS, null, "Réglages du réseau mobile ouverts (APN : « Noms des points d'accès »)."))
            c.contains("wifi") -> listOf(Triple("android.settings.WIFI_CALLING_SETTINGS", null, "Réglages des appels Wi-Fi ouverts."), Triple(Settings.ACTION_DATA_ROAMING_SETTINGS, null, "Réglages du réseau mobile ouverts (appels Wi-Fi / VoWiFi dans la carte SIM)."))
            c.startsWith("appel") -> listOf(Triple(android.telecom.TelecomManager.ACTION_SHOW_CALL_SETTINGS, null, "Réglages des appels ouverts."))
            c.startsWith("operateur") -> listOf(Triple(Settings.ACTION_NETWORK_OPERATOR_SETTINGS, null, "Choix de l'opérateur ouvert."))
            c.startsWith("sms") || c.contains("message") -> listOfNotNull(smsPkg?.let { Triple(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$it", "Réglages de l'appli SMS ouverts (Batterie › Sans restriction ; Stockage › Vider le cache).") })
            c.contains("avion") -> listOf(Triple(Settings.ACTION_AIRPLANE_MODE_SETTINGS, null, "Réglages du mode avion ouverts."))
            c.startsWith("sim") -> listOf(Triple("android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS", null, "Réglages des cartes SIM ouverts."), Triple(Settings.ACTION_DATA_ROAMING_SETTINGS, null, "Réglages du réseau mobile ouverts."))
            else -> listOf(Triple(Settings.ACTION_DATA_ROAMING_SETTINGS, null, "Réglages du réseau mobile ouverts."), Triple(Settings.ACTION_WIRELESS_SETTINGS, null, "Réglages réseau ouverts."))
        }
        for ((action, data, ok) in essais) if (app.phone.start(action, data).isBlank()) return "⚙️ $ok"
        return "⚙️ Cet écran n'existe pas sur ce téléphone : Réglages › Cartes SIM et réseaux mobiles."
    }
}
