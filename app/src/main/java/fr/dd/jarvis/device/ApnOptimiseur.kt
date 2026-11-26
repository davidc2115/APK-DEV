package fr.dd.jarvis.device

import android.net.ConnectivityManager
import android.net.Network
import fr.dd.jarvis.JarvisApp

/**
 * Outils de mesure de l'APN utilisés par [ApnExplorateur] (qui cherche lui-même le meilleur APN) : MTU réelle du
 * chemin (ping sans fragmentation par l'interface mobile ; une MTU trop grande fait « bloquer » des pages et des
 * applis), colonnes MTU de cet Android, comparaison de réglages, gestion des APN « JARVIS ».
 *
 * Plus : DNS — comparaison du DNS de l'opérateur avec les DNS privés (Cloudflare, Google, Quad9) et activation du plus
 * rapide après accord, vérifiée, réversible.
 */
object ApnOptimiseur {

    // ------------------------------------------------------------------ partie pure

    /** Un ping (sortie de `ping -c 1`) a-t-il eu sa réponse ? */
    internal fun pingOk(sortie: String): Boolean =
        Regex("\\b1 (packets )?received\\b|bytes from").containsMatchIn(sortie) && !Regex("(?i)message too long|frag needed|100% packet loss").containsMatchIn(sortie)

    /**
     * Plus grande MTU qui passe sans fragmentation, par dichotomie entre [min] et [max] ([passe] : la MTU m passe-t-elle ?).
     * Null si même [min] ne passe pas (ping bloqué : MTU non mesurable).
     */
    internal fun chercherMtu(min: Int = 1200, max: Int = 1500, passe: (Int) -> Boolean): Int? {
        if (!passe(min)) return null
        if (passe(max)) return max
        var bas = min; var haut = max
        while (haut - bas > 4) { val m = (bas + haut) / 2; if (passe(m)) bas = m else haut = m }
        return bas
    }

    /** MTU à écrire dans l'APN : seulement si le chemin réel est plus petit que le lien (sinon on laisse l'opérateur décider). */
    internal fun mtuConseillee(lien: Int?, chemin: Int?): Int? {
        if (chemin == null) return null
        val l = lien?.takeIf { it in 576..9000 } ?: 1500
        return if (chemin < l - 8) chemin else null
    }

    /** Colonnes MTU selon la version d'Android : 0 = mtu_v4 + mtu_v6 + mtu, 1 = mtu, 2 = aucune. */
    internal fun bindsMtu(mtu: Int?, niveau: Int): List<String> = when {
        mtu == null || niveau >= 2 -> emptyList()
        niveau == 0 -> listOf("--bind mtu_v4:i:$mtu", "--bind mtu_v6:i:$mtu", "--bind mtu:i:$mtu")
        else -> listOf("--bind mtu:i:$mtu")
    }

    data class Essai(val nom: String, val apnId: String?, val mesure: TestDebit.Mesure?, val actuel: Boolean = false)

    /** Le réglage à garder : un candidat seulement s'il bat nettement l'actuel ([seuil]) ou si l'actuel ne marche pas. */
    internal fun meilleur(base: Essai, candidats: List<Essai>, seuil: Double = 1.15): Essai {
        val top = candidats.filter { TestDebit.note(it.mesure) > 0 }.maxByOrNull { TestDebit.note(it.mesure) } ?: return base
        return if (TestDebit.note(base.mesure) <= 0 || TestDebit.note(top.mesure) > TestDebit.note(base.mesure) * seuil) top else base
    }

    internal fun ligne(e: Essai, garde: Boolean): String {
        val m = e.mesure
        val d = m?.descenteMbps?.let { "%.1f".format(java.util.Locale.FRANCE, it) + " Mbit/s" } ?: "échec"
        return "${if (garde) "✅" else "•"} ${e.nom}${if (e.actuel) " (actuel)" else ""} : ⬇️ $d · ⏱️ ${m?.latenceMs?.let { "$it ms" } ?: "?"}"
    }

    internal val DNS_PRIVES = listOf("one.one.one.one" to "Cloudflare", "dns.google" to "Google", "dns.quad9.net" to "Quad9")

    /** DNS privé à conseiller : nettement plus rapide (estimation d'une requête < 70 % de celle de l'opérateur). */
    internal fun dnsConseille(systemeMs: Long?, prives: List<Pair<String, Long?>>): Pair<String, Long>? {
        val top = prives.mapNotNull { (h, ms) -> ms?.let { h to it } }.minByOrNull { it.second } ?: return null
        return if (systemeMs == null || top.second < systemeMs * 0.7) top else null
    }

    // ------------------------------------------------------------------ mesures

    private fun lien(app: JarvisApp, n: Network): Pair<String?, Int?> = runCatching {
        val lp = app.getSystemService(ConnectivityManager::class.java).getLinkProperties(n)
        lp?.interfaceName to (if (android.os.Build.VERSION.SDK_INT >= 29) lp?.mtu?.takeIf { it > 0 } else null)
    }.getOrDefault(null to null)

    /** (MTU du lien, MTU réelle du chemin) sur la connexion mobile [n] ; ping par l'interface mobile (shell). */
    fun mesurerMtu(app: JarvisApp, n: Network): Pair<Int?, Int?> {
        val (iface, mtuLien) = lien(app, n)
        if (iface == null || !PhoneShell.ready(app)) return mtuLien to null
        val max = (mtuLien ?: 1500).coerceIn(1280, 1500)
        val chemin = chercherMtu(1200, max) { mtu -> pingOk(ApnConfig.executer(app, "ping -I $iface -M do -s ${mtu - 28} -c 1 -W 2 1.1.1.1", 5_000)) }
        return mtuLien to chemin
    }

    /** Une mesure courte par le réseau mobile, avec l'APN actuellement choisi. */
    private fun mesurer(app: JarvisApp): TestDebit.Mesure? = TestDebit.parMobile(app) { _, c -> TestDebit.mesureCourte(c) }

    internal fun choisir(app: JarvisApp, id: String) = ApnConfig.executer(app, "content insert --uri ${ApnConfig.uriPrefere()} --bind apn_id:i:$id")

    /** Colonnes MTU que connaît cet Android (0 = mtu_v4 / mtu_v6, 1 = mtu, 2 = aucune), sondées sans rien écrire. */
    internal fun niveauMtu(app: JarvisApp): Int {
        fun existe(col: String) = !Regex("(?i)no such column|exception|error").containsMatchIn(
            ApnConfig.executer(app, "content query --uri content://telephony/carriers --projection $col --where ${ApnConfig.q("_id=-1")}"))
        return when { existe("mtu_v4") && existe("mtu_v6") && existe("mtu") -> 0; existe("mtu") -> 1; else -> 2 }
    }

    /** Crée la variante (un seul essai, colonnes MTU connues) ; true si Android l'a acceptée. */
    private fun creerAvecMtu(app: JarvisApp, fabrique: (List<String>) -> List<Pair<String, String>>, mtu: Int?, niveau: Int): Boolean {
        val sorties = fabrique(bindsMtu(mtu, niveau)).map { ApnConfig.executer(app, it.second) }
        return sorties.none { ApnConfig.refuse(it) || Regex("(?i)exception|error").containsMatchIn(it) }
    }

    internal fun jarvis(app: JarvisApp, mccMnc: String) = ApnConfig.lignes(ApnConfig.executer(app,
        "content query --uri content://telephony/carriers --where ${ApnConfig.q("name LIKE '${ApnConfig.PREFIXE}-%' AND numeric='$mccMnc'")}"))

    internal fun supprimer(app: JarvisApp, ids: Collection<String>) { if (ids.isNotEmpty()) ApnConfig.executer(app, "content delete --uri content://telephony/carriers --where ${ApnConfig.q("_id IN (${ids.joinToString(",")})")}") }

    // ------------------------------------------------------------------ DNS

    /** Temps (ms) d'une connexion chiffrée DNS (port 853) vers [hote] par [n] ; estimation d'une requête = moitié. */
    private fun tempsDot(n: Network, hote: String): Long? = runCatching {
        val t0 = System.nanoTime()
        val s = n.socketFactory.createSocket()
        s.use {
            it.connect(java.net.InetSocketAddress(n.getAllByName(hote).first(), 853), 3_000)
            val ssl = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory).createSocket(it, hote, 853, true) as javax.net.ssl.SSLSocket
            ssl.use { x -> x.soTimeout = 3_000; x.startHandshake() }
        }
        (System.nanoTime() - t0) / 2_000_000
    }.getOrNull()

    /** Temps médian (ms) d'une résolution par le DNS du système (noms jamais en cache). */
    private fun tempsSysteme(n: Network): Long? = (1..3).mapNotNull {
        val nom = "j" + java.util.UUID.randomUUID().toString().take(10) + ".google.com"
        val t0 = System.nanoTime()
        runCatching { n.getAllByName(nom) }   // NXDOMAIN attendu : seule la durée compte
        ((System.nanoTime() - t0) / 1_000_000).takeIf { it in 1..5_000 }
    }.sorted().getOrNull(1)

    fun dnsTester(app: JarvisApp): String {
        val mode = runCatching { android.provider.Settings.Global.getString(app.contentResolver, "private_dns_mode") }.getOrNull().orEmpty()
        val hote = runCatching { android.provider.Settings.Global.getString(app.contentResolver, "private_dns_specifier") }.getOrNull().orEmpty()
        val r = TestDebit.parMobile(app) { n, _ -> tempsSysteme(n) to DNS_PRIVES.map { (h, _) -> h to tempsDot(n, h) } }
            ?: return "🌐 Test des DNS impossible : pas de connexion mobile."
        val (sys, prives) = r
        val actuel = when (mode) { "hostname" -> "DNS privé $hote"; "off" -> "DNS de l'opérateur (DNS privé désactivé)"; else -> "automatique (DNS de l'opérateur, chiffré s'il le permet)" }
        val l = ArrayList<String>()
        l += "🌐 DNS (réseau mobile) — réglage actuel : $actuel"
        l += "• Opérateur / système : ${sys?.let { "$it ms" } ?: "?"}"
        prives.forEach { (h, ms) -> l += "• ${DNS_PRIVES.first { it.first == h }.second} ($h) : ${ms?.let { "≈ $it ms" } ?: "injoignable"}" }
        l += dnsConseille(sys, prives)?.let { (h, ms) ->
            val nom = DNS_PRIVES.first { it.first == h }.second
            "💡 $nom est nettement plus rapide (≈ $ms ms) et chiffré : « active le DNS $nom » (vérifié, réversible avec « remets le DNS d'origine »)."
        } ?: "✅ Le DNS actuel est déjà rapide : rien à gagner à en changer."
        return l.joinToString("\n")
    }

    suspend fun dnsAppliquer(app: JarvisApp, choix: String, confirm: suspend (String, Boolean) -> Boolean): String {
        val c = fr.dd.jarvis.societe.core.normaliser(choix)
        val (hote, nom) = DNS_PRIVES.firstOrNull { (h, n) -> c.contains(n.lowercase()) || c.contains(h) } ?: DNS_PRIVES.first()
        if (!PhoneShell.ready(app)) return "Il me faut Shizuku ou l'ADB intégré pour changer le DNS privé. Sinon : Réglages › Connexion et partage › DNS privé › $hote."
        if (!confirm("J'active le DNS privé $nom ($hote) : résolutions chiffrées, souvent plus rapides ; il s'applique aussi au Wi-Fi. Je vérifie internet juste après et je reviens en arrière sinon. On y va ?", true))
            return "Changement de DNS annulé."
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val p = app.getSharedPreferences("reseau_mobile", 0)
            val modeAvant = ApnConfig.executer(app, "settings get global private_dns_mode").trim()
            val hoteAvant = ApnConfig.executer(app, "settings get global private_dns_specifier").trim()
            if (ApnConfig.refuse(modeAvant) || ApnConfig.refuse(hoteAvant)) {
                return@withContext "❌ Lecture du DNS actuel impossible : \${listOf(modeAvant, hoteAvant).filter { ApnConfig.refuse(it) }.joinToString(" ; ")}"
            }
            if (!p.contains("dns_mode_avant")) p.edit()
                .putString("dns_mode_avant", modeAvant.ifBlank { "null" })
                .putString("dns_hote_avant", hoteAvant.ifBlank { "null" }).apply()
            val ecriture = ApnConfig.executer(app, "settings put global private_dns_specifier $hote && settings put global private_dns_mode hostname")
            if (ApnConfig.refuse(ecriture)) {
                val retour = restaurerDns(app)
                return@withContext "❌ Écriture du DNS refusée : $ecriture" + if (retour) " Le réglage précédent a été restauré." else " ⚠️ La restauration doit être vérifiée dans les réglages DNS."
            }
            Thread.sleep(1_500)
            val modeApres = ApnConfig.executer(app, "settings get global private_dns_mode").trim()
            val hoteApres = ApnConfig.executer(app, "settings get global private_dns_specifier").trim()
            if (modeApres != "hostname" || hoteApres != hote) {
                val retour = restaurerDns(app)
                return@withContext "❌ Le DNS demandé n'a pas été confirmé (mode=$modeApres, hôte=$hoteApres)." +
                    if (retour) " Le réglage précédent a été restauré." else " ⚠️ Vérifie manuellement le DNS privé."
            }
            Thread.sleep(1_500)
            val ok = TestDebit.ping(app) || internetActuelOk(app)
            if (!ok) {
                val retour = restaurerDns(app)
                return@withContext "↩️ Avec le DNS $nom, internet ne répondait pas : " +
                    if (retour) "le réglage d'avant a été restauré." else "⚠️ la restauration n'a pas pu être confirmée."
            }
            "✅ DNS privé $nom activé et vérifié. (« remets le DNS d'origine » pour revenir en arrière.)"
        }
    }

    private fun internetActuelOk(app: JarvisApp): Boolean = runCatching {
        okhttp3.OkHttpClient.Builder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS).readTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
            .newCall(okhttp3.Request.Builder().url("https://speed.cloudflare.com/__down?bytes=0").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun restaurerDns(app: JarvisApp): Boolean {
        val p = app.getSharedPreferences("reseau_mobile", 0)
        val mode = p.getString("dns_mode_avant", "null").orEmpty()
        val hote = p.getString("dns_hote_avant", "null").orEmpty()
        val commande = (if (hote == "null" || hote.isBlank()) "settings delete global private_dns_specifier" else "settings put global private_dns_specifier $hote") + " && " +
            (if (mode == "null" || mode.isBlank()) "settings put global private_dns_mode opportunistic" else "settings put global private_dns_mode $mode")
        val ecriture = ApnConfig.executer(app, commande)
        if (ApnConfig.refuse(ecriture)) return false
        val modeLu = ApnConfig.executer(app, "settings get global private_dns_mode").trim()
        val hoteLu = ApnConfig.executer(app, "settings get global private_dns_specifier").trim().ifBlank { "null" }
        val modeAttendu = if (mode == "null" || mode.isBlank()) "opportunistic" else mode
        val hoteAttendu = if (hote == "null" || hote.isBlank()) "null" else hote
        val ok = modeLu == modeAttendu && hoteLu == hoteAttendu
        if (ok) p.edit().remove("dns_mode_avant").remove("dns_hote_avant").apply()
        return ok
    }

    suspend fun dnsRestaurer(app: JarvisApp): String {
        if (!PhoneShell.ready(app)) return "Il me faut Shizuku ou l'ADB intégré pour remettre le DNS."
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (restaurerDns(app)) "↩️ DNS d'origine remis et vérifié."
            else "❌ Restauration du DNS non confirmée : les informations d'origine sont conservées pour réessayer."
        }
    }

    /** MTU seule : mesure et explication. */
    fun mtu(app: JarvisApp): String {
        val r = TestDebit.parMobile(app) { n, _ -> mesurerMtu(app, n) } ?: return "📏 Mesure de la MTU impossible : pas de connexion mobile."
        val (lienMtu, chemin) = r
        val c = mtuConseillee(lienMtu, chemin)
        return "📏 MTU — lien : ${lienMtu ?: "?"} · chemin réel : ${chemin ?: "non mesurable (ping bloqué ou Shizuku absent)"}" + when {
            c != null -> "\n⚠️ Le chemin est plus petit que le lien : des pages ou applis peuvent bloquer. « optimise l'APN à fond » écrit une MTU de $c dans l'APN."
            chemin != null -> "\n✅ MTU correcte : rien à corriger."
            else -> ""
        }
    }
}
