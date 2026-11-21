package fr.dd.jarvis.device

import android.net.ConnectivityManager
import android.net.Network
import fr.dd.jarvis.JarvisApp

/**
 * Optimisation COMPLÈTE de l'APN, par la mesure (avec Shizuku / l'ADB intégré, toujours après accord) :
 *
 * 1. mesure de l'APN actuel par le réseau mobile (latence + débit) et de la MTU réelle du chemin (taille maximale des
 *    paquets : une MTU trop grande fait « bloquer » des pages et des applis) ;
 * 2. création de réglages optimisés aux valeurs de l'opérateur — IPv4/IPv6 et IPv4 seul (certains réseaux ont une IPv6
 *    imparfaite), MTU corrigée si besoin, MMS réparés si besoin — et mesure de chacun dans les mêmes conditions ;
 * 3. le plus rapide est gardé s'il bat nettement l'actuel (+ 15 %), sinon tout est remis comme avant ; internet est
 *    vérifié à la fin, avec retour arrière automatique.
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

    private fun choisir(app: JarvisApp, id: String) = ApnConfig.executer(app, "content insert --uri ${ApnConfig.uriPrefere()} --bind apn_id:i:$id")

    /** Colonnes MTU que connaît cet Android (0 = mtu_v4 / mtu_v6, 1 = mtu, 2 = aucune), sondées sans rien écrire. */
    private fun niveauMtu(app: JarvisApp): Int {
        fun existe(col: String) = !Regex("(?i)no such column|exception|error").containsMatchIn(
            ApnConfig.executer(app, "content query --uri content://telephony/carriers --projection $col --where ${ApnConfig.q("_id=-1")}"))
        return when { existe("mtu_v4") && existe("mtu_v6") && existe("mtu") -> 0; existe("mtu") -> 1; else -> 2 }
    }

    /** Crée la variante (un seul essai, colonnes MTU connues) ; true si Android l'a acceptée. */
    private fun creerAvecMtu(app: JarvisApp, fabrique: (List<String>) -> List<Pair<String, String>>, mtu: Int?, niveau: Int): Boolean {
        val sorties = fabrique(bindsMtu(mtu, niveau)).map { ApnConfig.executer(app, it.second) }
        return sorties.none { ApnConfig.refuse(it) || Regex("(?i)exception|error").containsMatchIn(it) }
    }

    private fun jarvis(app: JarvisApp, mccMnc: String) = ApnConfig.lignes(ApnConfig.executer(app,
        "content query --uri content://telephony/carriers --where ${ApnConfig.q("name LIKE '${ApnConfig.PREFIXE}-%' AND numeric='$mccMnc'")}"))

    private fun supprimer(app: JarvisApp, ids: Collection<String>) { if (ids.isNotEmpty()) ApnConfig.executer(app, "content delete --uri content://telephony/carriers --where ${ApnConfig.q("_id IN (${ids.joinToString(",")})")}") }

    // ------------------------------------------------------------------ optimisation complète

    suspend fun optimiser(app: JarvisApp, confirm: suspend (String, Boolean) -> Boolean): String {
        val e = ReseauMobile.etat(app)
        val reco = ReseauMobile.apnRecommande(e.mccMnc, e.marqueSim)
            ?: return "🧾 Je ne connais pas les valeurs APN de ${e.marqueSim.ifBlank { "cet opérateur" }} : je ne peux pas comparer de réglages sûrs. Prends-les sur son site, puis « ouvre les réglages APN »."
        if (!PhoneShell.ready(app)) return "🧾 Pour optimiser l'APN par la mesure, il me faut Shizuku ou l'ADB intégré (Réglages › Téléphone › Contrôle)."
        if (e.enAppel) return "📞 Un appel est en cours : je lancerai l'optimisation après."
        if (e.modeAvion) return "✈️ Le mode avion est activé : désactive-le d'abord."
        if (e.donnees == false) return "🌐 Les données mobiles sont coupées : active-les (« active les données mobiles ») pour que je puisse mesurer."
        val lecture = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ApnConfig.lire(app, e.mccMnc) }
        if (lecture == null || lecture.refuse) return "🔒 Ce téléphone refuse la modification des APN, même avec Shizuku : « montre-moi l'APN » pour les valeurs à saisir."
        val analyse = ApnConfig.analyser(lecture.actuel, lecture.tous, reco)
        val mmsAReparer = analyse.problemes.any { it.contains("MMS") || it.contains("MMSC") }
        if (!confirm("Optimisation complète de l'APN : je mesure l'APN actuel, puis 2 réglages optimisés ${reco.operateur} (IPv4/IPv6 et IPv4 seul, MTU mesurée" +
                (if (mmsAReparer) ", MMS réparés" else "") + ") par le réseau mobile, et je garde le plus rapide — sinon je remets tout comme avant. " +
                "≈ 1 à 2 minutes et ≈ 15 Mo de données ; la connexion mobile se coupe quelques secondes à chaque essai. On y va ?", true))
            return "Optimisation de l'APN annulée."
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val avant = lecture.actuel?.get("_id")
            val nomAvant = lecture.actuel?.get("name").orEmpty().ifBlank { "APN actuel" }
            // 1. Référence : APN actuel + MTU réelle.
            var mtuLien: Int? = null; var mtuChemin: Int? = null
            val base = Essai(nomAvant, avant, TestDebit.parMobile(app) { n, c -> mesurerMtu(app, n).let { mtuLien = it.first; mtuChemin = it.second }; TestDebit.mesureCourte(c) }, actuel = true)
            val mtu = mtuConseillee(mtuLien, mtuChemin)
            // 2. Candidats (les anciens APN « JARVIS » sont remplacés ; jamais ceux de l'opérateur).
            supprimer(app, jarvis(app, e.mccMnc).mapNotNull { it["_id"] })
            val niveau = if (mtu != null) niveauMtu(app) else 2
            val ok46 = creerAvecMtu(app, { m -> ApnConfig.commandesAjout(reco, e.mccMnc, avecMms = mmsAReparer, protocole = "IPV4V6", mtu = m) }, mtu, niveau)
            val ok4 = creerAvecMtu(app, { m -> ApnConfig.commandesAjout(reco, e.mccMnc, avecMms = false, protocole = "IP", mtu = m, suffixe = "-IPv4") }, mtu, niveau)
            val crees = jarvis(app, e.mccMnc)
            val donnees = crees.filter { ApnConfig.estDonnees(it) }
            if (!ok46 && !ok4 || donnees.isEmpty()) {
                supprimer(app, crees.mapNotNull { it["_id"] })
                return@withContext "🔒 Android a refusé la création des APN de test : rien n'a changé."
            }
            val essais = donnees.map { a ->
                val id = a["_id"].orEmpty()
                choisir(app, id); Thread.sleep(3_000)
                val m = if (ApnConfig.internetMobileOk(app, 20)) mesurer(app) else null
                Essai(a["name"].orEmpty() + if (a["protocol"].equals("IP", true)) " · IPv4" else " · IPv4/IPv6", id, m)
            }
            // 3. Choix, nettoyage, vérification.
            val garde = meilleur(base, essais)
            val idsJarvis = crees.mapNotNull { it["_id"] }
            if (garde.actuel || garde.apnId == null) {
                avant?.let { choisir(app, it) }
                // Les MMS réparés restent (APN « mms » à part) ; les APN de test internet sont retirés.
                supprimer(app, donnees.mapNotNull { it["_id"] })
            } else {
                choisir(app, garde.apnId)
                if (avant != null && avant !in idsJarvis) app.getSharedPreferences("reseau_mobile", 0).edit().putString("apn_avant", avant).apply()
                supprimer(app, donnees.mapNotNull { it["_id"] }.filter { it != garde.apnId })
            }
            Thread.sleep(3_000)
            if (!ApnConfig.internetMobileOk(app, 20)) {
                avant?.let { choisir(app, it) }
                supprimer(app, idsJarvis)
                return@withContext "↩️ Internet mobile ne répondait plus à la fin : j'ai tout remis comme avant (« $nomAvant »)."
            }
            val l = ArrayList<String>()
            l += "🧪 Optimisation de l'APN (${reco.operateur}) :"
            l += ligne(base, garde === base)
            essais.forEach { l += ligne(it, garde === it) }
            l += when {
                garde === base -> "➡️ L'APN actuel est déjà le meilleur ici : je l'ai gardé" + (if (mmsAReparer) ", et j'ai ajouté un APN MMS correct." else ".")
                else -> "➡️ Gardé : ${garde.nom} (+${((TestDebit.note(garde.mesure) / TestDebit.note(base.mesure).coerceAtLeast(0.01) - 1) * 100).toInt().coerceAtLeast(0)} %)." +
                    " « remets l'APN d'origine » pour revenir à « $nomAvant »."
            }
            l += "📏 MTU : " + when {
                mtuChemin == null -> "non mesurable ici (ping bloqué par le réseau)."
                mtu != null -> "chemin réel $mtuChemin octets < lien ${mtuLien ?: 1500} : MTU $mtu " + (if (niveau < 2) "écrite dans l'APN (évite les pages et applis qui bloquent)." else "conseillée, mais cet Android ne permet pas de l'écrire dans l'APN.")
                else -> "$mtuChemin octets, conforme au lien — rien à corriger."
            }
            if (analyse.ameliorations.isNotEmpty() || analyse.problemes.isNotEmpty()) l += "🧾 Points corrigés par les réglages JARVIS : " +
                (analyse.problemes + analyse.ameliorations).joinToString(" ; ") { it.substringBefore(" :").substringBefore(" (") }.take(400)
            l += "💡 DNS : « teste les DNS » compare celui de l'opérateur aux DNS privés (Cloudflare, Google, Quad9)."
            l.joinToString("\n")
        }
    }

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
            val modeAvant = ApnConfig.executer(app, "settings get global private_dns_mode").trim().ifBlank { "null" }
            val hoteAvant = ApnConfig.executer(app, "settings get global private_dns_specifier").trim().ifBlank { "null" }
            if (!p.contains("dns_mode_avant")) p.edit().putString("dns_mode_avant", modeAvant).putString("dns_hote_avant", hoteAvant).apply()
            ApnConfig.executer(app, "settings put global private_dns_specifier $hote; settings put global private_dns_mode hostname")
            Thread.sleep(3_000)
            val ok = TestDebit.ping(app) || internetActuelOk(app)
            if (!ok) {
                restaurerDns(app)
                return@withContext "↩️ Avec le DNS $nom, internet ne répondait pas : j'ai remis le réglage d'avant."
            }
            "✅ DNS privé $nom activé et vérifié. (« remets le DNS d'origine » pour revenir en arrière.)"
        }
    }

    private fun internetActuelOk(app: JarvisApp): Boolean = runCatching {
        okhttp3.OkHttpClient.Builder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS).readTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
            .newCall(okhttp3.Request.Builder().url("https://speed.cloudflare.com/__down?bytes=0").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun restaurerDns(app: JarvisApp) {
        val p = app.getSharedPreferences("reseau_mobile", 0)
        val mode = p.getString("dns_mode_avant", "null").orEmpty()
        val hote = p.getString("dns_hote_avant", "null").orEmpty()
        ApnConfig.executer(app, (if (hote == "null" || hote.isBlank()) "settings delete global private_dns_specifier" else "settings put global private_dns_specifier $hote") + "; " +
            (if (mode == "null" || mode.isBlank()) "settings put global private_dns_mode opportunistic" else "settings put global private_dns_mode $mode"))
        p.edit().remove("dns_mode_avant").remove("dns_hote_avant").apply()
    }

    suspend fun dnsRestaurer(app: JarvisApp): String {
        if (!PhoneShell.ready(app)) return "Il me faut Shizuku ou l'ADB intégré pour remettre le DNS."
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { restaurerDns(app); "↩️ DNS d'origine remis." }
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
