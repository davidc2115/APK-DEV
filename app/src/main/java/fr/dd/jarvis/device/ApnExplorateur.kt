package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * APN amélioré PAR LUI-MÊME : JARVIS ne se contente plus des valeurs de l'opérateur, il cherche.
 *
 * Il part de l'APN qui marche aujourd'hui (quel que soit l'opérateur, opérateurs virtuels compris), puis explore,
 * étape par étape, ce qui peut changer la vitesse : le NOM d'APN (d'autres passerelles du même réseau), le PROTOCOLE
 * (IPv4/IPv6, IPv4 seul, IPv6 seul), la MTU (taille des paquets, autour de celle mesurée sur le chemin réel). Chaque
 * essai est mesuré par le réseau mobile ; le meilleur est re-mesuré face à l'APN d'origine (deux fois chacun, pour
 * écarter les variations du réseau) et n'est gardé que s'il est nettement meilleur (+ 10 %).
 *
 * Il APPREND : chaque essai est mémorisé pour ce réseau ; un réglage qui n'a pas marché n'est pas retenté pendant 30
 * jours, et le gagnant précédent sert de point de départ la fois suivante.
 *
 * Sûreté : après ton accord seulement ; les APN de l'opérateur ne sont jamais modifiés ni supprimés ; chaque essai est
 * un APN « JARVIS » temporaire, retiré après la mesure ; internet est vérifié à la fin, sinon retour à l'APN d'origine ;
 * interruption propre si un appel arrive. Tout se fait en arrière-plan (notification de progression) et le bilan
 * arrive dans la conversation.
 */
object ApnExplorateur {

    // ------------------------------------------------------------------ partie pure

    data class Config(
        val apn: String, val protocole: String = "IPV4V6", val mtu: Int? = null,
        val user: String = "", val password: String = "", val authtype: Int = -1, val type: String = "default,supl",
        val mmsc: String = "", val mmsproxy: String = "", val mmsport: String = "", val proxy: String = "", val port: String = "",
    ) {
        val cle get() = "${apn.lowercase()}|$protocole|${mtu ?: 0}"
        val libelle get() = "« $apn » · ${protocoleLisible(protocole)} · MTU ${mtu ?: "auto"}"
    }

    internal fun protocoleLisible(p: String) = when (p.uppercase()) { "IP" -> "IPv4"; "IPV6" -> "IPv6"; else -> "IPv4/IPv6" }

    /** Configuration recopiée de l'APN en service (ses identifiants, ses MMS, son proxy). */
    internal fun depuisApn(a: Map<String, String>): Config {
        val type = a["type"].orEmpty().ifBlank { "default,supl" }.let { t -> if (t.split(',').any { it.trim() == "default" || it.trim() == "*" }) t else "default,supl" }
        return Config(
            apn = a["apn"].orEmpty(), protocole = a["protocol"].orEmpty().uppercase().ifBlank { "IPV4V6" },
            mtu = (a["mtu_v4"] ?: a["mtu"])?.toIntOrNull()?.takeIf { it in 576..9000 },
            user = a["user"].orEmpty(), password = a["password"].orEmpty(), authtype = a["authtype"]?.toIntOrNull() ?: -1, type = type,
            mmsc = a["mmsc"].orEmpty(), mmsproxy = a["mmsproxy"].orEmpty(), mmsport = a["mmsport"].orEmpty(), proxy = a["proxy"].orEmpty(), port = a["port"].orEmpty(),
        )
    }

    /** Autres noms d'APN du même réseau (passerelles différentes), à essayer en plus de l'actuel et de celui de l'opérateur. */
    internal fun nomsAlternatifs(operateur: String?): List<String> = when (operateur) {
        "Orange" -> listOf("orange", "orange.fr", "orange-mib")
        "SFR" -> listOf("sl2sfr", "websfr")
        "Bouygues Telecom" -> listOf("mmsbouygtel.com", "ebouygtel.com")
        "Free Mobile" -> listOf("free")
        else -> listOf("internet")
    }

    /** Étape 1 : noms d'APN (en IPv4/IPv6), sans ceux qui ont échoué récemment. */
    internal fun etapeNoms(base: Config, reco: ReseauMobile.Apn?, echecs: Set<String>): List<Config> {
        val noms = (listOf(base.apn) + listOfNotNull(reco?.apn) + nomsAlternatifs(reco?.operateur)).filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        return noms.map { n ->
            val memeNom = n.equals(base.apn, true)
            val ident = when {
                memeNom -> Triple(base.user, base.password, base.authtype)
                reco != null && reco.utilisateur.isNotBlank() -> Triple(reco.utilisateur, reco.motDePasse, 1)
                else -> Triple("", "", -1)
            }
            base.copy(apn = n, protocole = "IPV4V6", user = ident.first, password = ident.second, authtype = ident.third)
        }.filter { it.cle != base.cle && it.cle !in echecs }
    }

    /** Étape 2 : les autres protocoles pour le meilleur nom. */
    internal fun etapeProtocoles(meilleur: Config, essayes: Set<String>, echecs: Set<String>): List<Config> =
        listOf("IPV4V6", "IP", "IPV6").map { meilleur.copy(protocole = it) }.filter { it.cle !in essayes && it.cle !in echecs }

    /** Étape 3 : MTU autour de celle du chemin réel (ou des valeurs courantes si elle n'a pas pu être mesurée). */
    internal fun etapeMtu(meilleur: Config, chemin: Int?, lien: Int?, essayes: Set<String>, echecs: Set<String>): List<Config> {
        val valeurs = when {
            chemin != null && chemin < (lien ?: 1500) - 8 -> listOf(chemin, chemin - 40)
            chemin != null -> emptyList()            // le chemin passe la MTU du lien : rien à gagner
            else -> listOf(1440, 1400)
        }
        return valeurs.filter { it in 1280..1500 }.map { meilleur.copy(mtu = it) }.filter { it.cle !in essayes && it.cle !in echecs }
    }

    /** Commande d'ajout d'un APN pour [c] ([niveauMtu] : colonnes MTU connues de cet Android). */
    internal fun commande(c: Config, nom: String, mccMnc: String, niveauMtu: Int): String {
        val q = ApnConfig::q
        val l = mutableListOf("content insert --uri content://telephony/carriers",
            "--bind ${q("name:s:$nom")}", "--bind ${q("numeric:s:$mccMnc")}", "--bind ${q("mcc:s:${mccMnc.take(3)}")}", "--bind ${q("mnc:s:${mccMnc.drop(3)}")}",
            "--bind ${q("apn:s:${c.apn}")}", "--bind ${q("type:s:${c.type}")}", "--bind protocol:s:${c.protocole}", "--bind roaming_protocol:s:${c.protocole}",
            "--bind carrier_enabled:i:1")
        if (c.user.isNotBlank()) { l += "--bind ${q("user:s:${c.user}")}"; l += "--bind ${q("password:s:${c.password}")}" }
        if (c.authtype >= 0) l += "--bind authtype:i:${c.authtype}"
        listOf("mmsc" to c.mmsc, "mmsproxy" to c.mmsproxy, "mmsport" to c.mmsport, "proxy" to c.proxy, "port" to c.port)
            .filter { it.second.isNotBlank() }.forEach { (k, v) -> l += "--bind ${q("$k:s:$v")}" }
        l += ApnOptimiseur.bindsMtu(c.mtu, niveauMtu)
        return l.joinToString(" ")
    }

    /** Garder le gagnant ? Moyennes de ses mesures contre celles de l'APN d'origine, avec [seuil] de marge. */
    internal fun garder(notesBase: List<Double>, notesGagnant: List<Double>, seuil: Double = 1.10): Boolean {
        val g = notesGagnant.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average() ?: return false
        val b = notesBase.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average() ?: return true
        return g > b * seuil
    }

    // ------------------------------------------------------------------ mémoire des essais

    private fun prefs(app: JarvisApp) = app.getSharedPreferences("reseau_mobile", 0)

    internal data class Souvenir(val cle: String, val note: Double, val ok: Boolean, val t: Long)

    internal fun lireMemoire(s: String?): List<Souvenir> = runCatching {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { Souvenir(it.getString("k"), it.optDouble("n", 0.0), it.optBoolean("ok"), it.optLong("t")) }
    }.getOrDefault(emptyList())

    internal fun ecrireMemoire(l: List<Souvenir>): String = JSONArray().also { a ->
        l.takeLast(80).forEach { s -> a.put(JSONObject().put("k", s.cle).put("n", s.note).put("ok", s.ok).put("t", s.t)) }
    }.toString()

    /** Réglages qui n'ont pas marché ces 30 derniers jours. */
    internal fun echecsRecents(l: List<Souvenir>, maintenant: Long) = l.filter { !it.ok && maintenant - it.t < 30L * 86_400_000L }.map { it.cle }.toSet()

    // ------------------------------------------------------------------ exploration (arrière-plan)

    @Volatile private var progression: String? = null

    /** Une optimisation tourne-t-elle (les autres réglages d'APN attendent) ? Texte de l'étape, ou null. */
    fun enCours(): String? = progression
    private val portee = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private const val NOTIF = 7720

    private fun notifier(app: JarvisApp, titre: String, texte: String, enCours: Boolean) = runCatching {
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(android.app.NotificationChannel("reseau_mobile", "Réseau mobile", android.app.NotificationManager.IMPORTANCE_LOW))
        val pi = android.app.PendingIntent.getActivity(app, NOTIF, android.content.Intent(app, fr.dd.jarvis.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        androidx.core.app.NotificationManagerCompat.from(app).notify(NOTIF, androidx.core.app.NotificationCompat.Builder(app, "reseau_mobile")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(titre).setContentText(texte)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(texte)).setOngoing(enCours).setOnlyAlertOnce(true)
            .setContentIntent(pi).setAutoCancel(!enCours).build())
    }

    /** Vérifie les conditions, demande l'accord, puis lance l'exploration en arrière-plan. */
    suspend fun lancer(app: JarvisApp, confirm: suspend (String, Boolean) -> Boolean, essaisMax: Int = 9): String {
        progression?.let { return "🧪 Optimisation de l'APN en cours : $it" }
        val e = ReseauMobile.etat(app)
        if (!PhoneShell.ready(app)) return "🧾 Pour que j'améliore l'APN moi-même, il me faut Shizuku ou l'ADB intégré (Réglages › Téléphone › Contrôle)."
        if (e.enAppel) return "📞 Un appel est en cours : je lancerai l'optimisation après."
        if (e.modeAvion) return "✈️ Le mode avion est activé : désactive-le d'abord."
        if (e.donnees == false) return "🌐 Les données mobiles sont coupées : active-les pour que je puisse mesurer."
        if (e.mccMnc.length < 5) return "🧾 SIM non lisible : impossible de savoir pour quel réseau chercher."
        val lecture = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ApnConfig.lire(app, e.mccMnc) }
        if (lecture == null || lecture.refuse) return "🔒 Ce téléphone refuse la modification des APN, même avec Shizuku : « montre-moi l'APN » pour les valeurs à saisir."
        if (lecture.actuel == null && ReseauMobile.apnRecommande(e.mccMnc, e.marqueSim) == null)
            return "🧾 Je ne vois pas l'APN en service et ne connais pas ton opérateur : rien de sûr sur quoi partir."
        if (!confirm("Je cherche moi-même le meilleur APN : en partant de celui qui marche aujourd'hui, j'essaie d'autres noms d'APN, protocoles (IPv4/IPv6, IPv4, IPv6) et tailles de paquets (MTU), " +
                "je mesure chacun par le réseau mobile et je ne garde le gagnant que s'il est nettement plus rapide. Jusqu'à $essaisMax essais, ≈ 3 à 5 minutes, jusqu'à ≈ 40 Mo de données ; " +
                "la connexion mobile se coupe quelques secondes à chaque essai, et tout revient comme avant si rien n'est mieux. Ça se fait en arrière-plan. On y va ?", true))
            return "Optimisation de l'APN annulée."
        progression = "démarrage…"
        portee.launch {
            val rapport = runCatching { explorer(app, e, lecture, essaisMax) }.getOrElse { "❌ Optimisation de l'APN interrompue : ${it.message}" }
            progression = null
            notifier(app, "🧪 Optimisation de l'APN terminée", rapport.lineSequence().filter { it.startsWith("➡️") || it.startsWith("↩️") || it.startsWith("❌") }.joinToString("\n").ifBlank { rapport.take(300) }, false)
            android.os.Handler(android.os.Looper.getMainLooper()).post { app.chatAjouter(fr.dd.jarvis.ai.ChatMsg(false, rapport)) }
        }
        return "🧪 C'est parti : je cherche le meilleur APN en arrière-plan (≈ 3 à 5 min). La progression s'affiche en notification et le bilan arrivera ici. Évite les appels pendant ce temps."
    }

    private fun etape(app: JarvisApp, texte: String) { progression = texte; notifier(app, "🧪 Optimisation de l'APN…", texte, true) }

    private fun mesure(app: JarvisApp): TestDebit.Mesure? = TestDebit.parMobile(app) { _, c -> TestDebit.mesureCourte(c) }

    private fun explorer(app: JarvisApp, e: ReseauMobile.Etat, lecture: ApnConfig.Lecture, essaisMax: Int): String {
        val reco = ReseauMobile.apnRecommande(e.mccMnc, e.marqueSim)
        val avant = lecture.actuel?.get("_id")
        val nomAvant = lecture.actuel?.get("name").orEmpty().ifBlank { "APN d'origine" }
        val base = lecture.actuel?.let { depuisApn(it) } ?: Config(reco!!.apn, user = reco.utilisateur, password = reco.motDePasse, authtype = if (reco.utilisateur.isNotBlank()) 1 else -1)
        val cleMemoire = "apn_memoire_${e.mccMnc}"
        val memoire = lireMemoire(prefs(app).getString(cleMemoire, null)).toMutableList()
        val echecs = echecsRecents(memoire, System.currentTimeMillis())
        val niveau = ApnOptimiseur.niveauMtu(app)
        val essais = ArrayList<Pair<Config, TestDebit.Mesure?>>()
        val essayes = HashSet<String>()

        // Référence : l'APN d'origine + MTU réelle du chemin.
        etape(app, "mesure de l'APN actuel ($nomAvant) et de la MTU réelle…")
        var mtuLien: Int? = null; var mtuChemin: Int? = null
        val mBase = TestDebit.parMobile(app) { n, c -> ApnOptimiseur.mesurerMtu(app, n).let { mtuLien = it.first; mtuChemin = it.second }; TestDebit.mesureCourte(c) }
        val notesBase = mutableListOf(TestDebit.note(mBase))
        essayes += base.cle

        ApnOptimiseur.supprimer(app, ApnOptimiseur.jarvis(app, e.mccMnc).mapNotNull { it["_id"] })
        var echecsDeSuite = 0
        var interrompu: String? = null

        fun essayer(c: Config): TestDebit.Mesure? {
            essayes += c.cle
            if (ReseauMobile.etat(app).enAppel) { interrompu = "un appel est arrivé"; return null }
            etape(app, "essai ${essais.size + 1}/$essaisMax : ${c.libelle}")
            val sortie = ApnConfig.executer(app, commande(c, "${ApnConfig.PREFIXE}-Essai", e.mccMnc, if (c.mtu != null) niveau else 2))
            if (ApnConfig.refuse(sortie)) { interrompu = "Android a refusé un APN de test"; return null }
            val id = ApnOptimiseur.jarvis(app, e.mccMnc).firstOrNull { it["name"] == "${ApnConfig.PREFIXE}-Essai" }?.get("_id") ?: return null
            ApnOptimiseur.choisir(app, id)
            Thread.sleep(3_000)
            val m = if (ApnConfig.internetMobileOk(app, 20)) mesure(app) else null
            ApnOptimiseur.supprimer(app, listOf(id))
            avant?.let { ApnOptimiseur.choisir(app, it) }
            val ok = m?.descenteMbps != null
            memoire += Souvenir(c.cle, TestDebit.note(m), ok, System.currentTimeMillis())
            essais += c to m
            echecsDeSuite = if (ok) 0 else echecsDeSuite + 1
            if (echecsDeSuite >= 3) interrompu = "trois réglages de suite sans connexion"
            return m
        }

        fun meilleurConnu(): Pair<Config, Double> = (listOf(base to notesBase.first()) + essais.map { it.first to TestDebit.note(it.second) }).maxByOrNull { it.second }!!

        // Point de départ appris : le gagnant de la dernière fois, s'il n'est pas l'actuel.
        prefs(app).getString("apn_gagnant_${e.mccMnc}", null)?.let { j -> runCatching { configDepuisJson(JSONObject(j)) }.getOrNull() }
            ?.takeIf { it.cle !in essayes && it.cle !in echecs }?.let { if (interrompu == null && essais.size < essaisMax) essayer(it) }
        // 1. Noms d'APN.
        for (c in etapeNoms(base, reco, echecs)) { if (interrompu != null || essais.size >= essaisMax) break; if (c.cle !in essayes) essayer(c) }
        // 2. Protocoles, pour le meilleur nom.
        for (c in etapeProtocoles(meilleurConnu().first, essayes, echecs)) { if (interrompu != null || essais.size >= essaisMax) break; essayer(c) }
        // 3. MTU (si cet Android permet de l'écrire).
        if (niveau < 2) for (c in etapeMtu(meilleurConnu().first, mtuChemin, mtuLien, essayes, echecs)) { if (interrompu != null || essais.size >= essaisMax) break; essayer(c) }

        prefs(app).edit().putString(cleMemoire, ecrireMemoire(memoire)).apply()
        val (gagnant, _) = meilleurConnu()
        val lignes = ArrayList<String>()
        lignes += "🧪 APN amélioré par JARVIS — ${e.marqueSim.ifBlank { e.operateur }} (${essais.size} essai(s))"
        lignes += "• Origine ($nomAvant) ${base.libelle} : ${ligneMesure(mBase)}"
        essais.forEach { (c, m) -> lignes += "${if (c == gagnant) "✅" else "•"} ${c.libelle} : ${ligneMesure(m)}" }
        interrompu?.let { lignes += "⏸️ Exploration arrêtée plus tôt : $it." }

        // 4. Confirmation : gagnant et origine re-mesurés, puis décision.
        if (gagnant == base) {
            lignes += "➡️ L'APN d'origine reste le meilleur : rien n'a changé (les essais sont mémorisés, je ne les referai pas inutilement)."
            return lignes.joinToString("\n") + ligneMtu(mtuLien, mtuChemin)
        }
        etape(app, "vérification du gagnant face à l'APN d'origine…")
        notesBase += TestDebit.note(mesure(app))
        val nomFinal = "${ApnConfig.PREFIXE}-Optimal"
        ApnConfig.executer(app, commande(gagnant, nomFinal, e.mccMnc, if (gagnant.mtu != null) niveau else 2))
        val idFinal = ApnOptimiseur.jarvis(app, e.mccMnc).firstOrNull { it["name"] == nomFinal }?.get("_id")
        if (idFinal == null) {
            lignes += "❌ Le réglage gagnant n'a pas pu être enregistré : rien n'a changé."
            return lignes.joinToString("\n")
        }
        ApnOptimiseur.choisir(app, idFinal)
        Thread.sleep(3_000)
        val notesGagnant = listOf(TestDebit.note(essais.first { it.first == gagnant }.second)) +
            (if (ApnConfig.internetMobileOk(app, 20)) listOf(TestDebit.note(mesure(app))) else listOf(0.0))
        if (!garder(notesBase, notesGagnant) || !ApnConfig.internetMobileOk(app, 10)) {
            avant?.let { ApnOptimiseur.choisir(app, it) }
            ApnOptimiseur.supprimer(app, listOf(idFinal))
            lignes += "↩️ Re-mesuré face à l'origine, le gagnant n'est pas nettement meilleur (écart dans les variations du réseau) : j'ai remis l'APN d'origine."
            return lignes.joinToString("\n") + ligneMtu(mtuLien, mtuChemin)
        }
        if (avant != null) prefs(app).edit().putString("apn_avant", avant).apply()
        prefs(app).edit().putString("apn_gagnant_${e.mccMnc}", configEnJson(gagnant).toString()).apply()
        val gain = ((notesGagnant.filter { it > 0 }.average() / notesBase.filter { it > 0 }.average().coerceAtLeast(0.01) - 1) * 100).toInt()
        lignes += "➡️ Gardé : APN « $nomFinal » ${gagnant.libelle} — environ +$gain % par rapport à l'origine (moyenne de deux mesures chacun). « remets l'APN d'origine » pour revenir à « $nomAvant »."
        return lignes.joinToString("\n") + ligneMtu(mtuLien, mtuChemin)
    }

    private fun ligneMesure(m: TestDebit.Mesure?): String =
        m?.descenteMbps?.let { "⬇️ ${"%.1f".format(java.util.Locale.FRANCE, it)} Mbit/s · ⏱️ ${m.latenceMs?.let { l -> "$l ms" } ?: "?"}" } ?: "pas de connexion"

    private fun ligneMtu(lien: Int?, chemin: Int?) = "\n📏 MTU : lien ${lien ?: "?"} · chemin réel ${chemin ?: "non mesurable"}"

    private fun configEnJson(c: Config) = JSONObject().put("apn", c.apn).put("protocole", c.protocole).put("mtu", c.mtu ?: 0).put("user", c.user).put("password", c.password)
        .put("authtype", c.authtype).put("type", c.type).put("mmsc", c.mmsc).put("mmsproxy", c.mmsproxy).put("mmsport", c.mmsport).put("proxy", c.proxy).put("port", c.port)

    internal fun configDepuisJson(o: JSONObject) = Config(o.getString("apn"), o.optString("protocole", "IPV4V6"), o.optInt("mtu", 0).takeIf { it > 0 },
        o.optString("user"), o.optString("password"), o.optInt("authtype", -1), o.optString("type", "default,supl"), o.optString("mmsc"), o.optString("mmsproxy"),
        o.optString("mmsport"), o.optString("proxy"), o.optString("port"))
}
