package fr.dd.jarvis.car

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.ChatMsg
import fr.dd.jarvis.service.CarBridge
import fr.dd.jarvis.voice.VoiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Voix de JARVIS en voiture : la même voix naturelle (personnage choisi dans les réglages) que sur le téléphone,
 * routée vers l'autoradio, avec repli sur la voix Android du même genre.
 */
object VoixVoiture {
    private val main = Handler(Looper.getMainLooper())
    private var vm: VoiceManager? = null

    private fun voix(ctx: Context): VoiceManager = vm ?: VoiceManager(ctx.applicationContext).also {
        it.bargeIn = false; it.duplex = false; vm = it
    }

    /** Prépare la voix (à l'ouverture de JARVIS dans la voiture) pour que la première phrase parte sans attendre. */
    fun preparer(ctx: Context) { main.post { voix(ctx) } }

    fun parler(ctx: Context, texte: String, puis: () -> Unit = {}) {
        main.post { voix(ctx).speak(texte) { main.post(puis) } }
    }

    fun taire() { main.post { vm?.stopSpeaking() } }

    fun liberer() { main.post { vm?.release(); vm = null } }
}

/** Raccourcis de la voiture : demandes toutes faites, une par ligne (modifiables dans les réglages). */
object RaccourcisVoiture {
    val DEFAUT = listOf(
        "Fais-moi le point de ma journée",
        "Quel est mon prochain rendez-vous et combien de temps de route ?",
        "Résume mes mails non lus importants",
        "Lis mes SMS non lus",
        "Quel temps fera-t-il cet après-midi ?",
    )

    fun liste(app: JarvisApp): List<String> =
        app.store.raccourcisVoiture.value.lines().map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { DEFAUT }
}

/** Accueil à la connexion à la voiture : un message de JARVIS (et, si choisi, une annonce à voix haute). */
object AccueilVoiture {
    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)
    @Volatile private var dernier = 0L

    /** Texte de l'accueil (sans IA : instantané et fiable). */
    internal fun composer(prenom: String, heure: Int, restants: List<Triple<Long, String, String>>, meteo: String?): String {
        val salut = (if (heure in 5..17) "Bonjour" else "Bonsoir") + (if (prenom.isNotBlank()) " $prenom" else "") + "."
        val agenda = when {
            restants.isEmpty() -> "Plus aucun rendez-vous aujourd'hui."
            else -> {
                val (debut, titre, lieu) = restants.first()
                val h = Instant.ofEpochMilli(debut).atZone(ZoneId.systemDefault()).format(hm)
                (if (restants.size == 1) "Un rendez-vous encore aujourd'hui" else "${restants.size} rendez-vous encore aujourd'hui") +
                    " ; le prochain à $h : ${titre.ifBlank { "(sans titre)" }}" + (if (lieu.isNotBlank()) ", ${lieu.take(60)}" else "") + "."
            }
        }
        return listOfNotNull(salut, agenda, meteo?.let { "Météo : $it." }, "Dis-moi ce dont tu as besoin.").joinToString(" ")
    }

    suspend fun annoncer(app: JarvisApp) {
        if (!app.store.voitureAccueil.value) return
        val maintenant = System.currentTimeMillis()
        if (maintenant - dernier < 30 * 60_000L) return // une seule fois par trajet (reconnexions rapides ignorées)
        dernier = maintenant
        val texte = withContext(Dispatchers.IO) {
            val evts = runCatching { app.calendar.events(LocalDate.now(), 1) }.getOrDefault(emptyList())
                .filter { !it.allDay && it.end > maintenant }.sortedBy { it.begin }.map { Triple(it.begin, it.title, it.location) }
            val meteo = runCatching { withTimeoutOrNull(5000) { fr.dd.jarvis.ai.Meteo.actuel(app) } }.getOrNull()?.let { "${it.second}, ${it.third}" }
            composer(app.store.userName.value.trim(), LocalTime.now().hour, evts, meteo)
        }
        CarBridge.ajouter(ChatMsg(false, texte))
        runCatching { CarBridge.post(app, texte) }
        if (app.store.voitureAccueilVocal.value) VoixVoiture.parler(app, texte)
    }
}

/**
 * Visibilité dans Android Auto. Les versions récentes d'Android Auto n'affichent une appli « Car App » que si Android
 * la considère installée depuis le Play Store (« Sources inconnues » ne suffit plus). JARVIS peut se réinstaller
 * lui-même avec cette marque grâce à Shizuku ou à l'ADB intégré (droits « adb ») : mêmes données, même signature.
 * Chaque mise à jour installée à la main retire la marque : une notification propose alors de la remettre en un geste.
 */
object VisibiliteAuto {
    const val PLAY = "com.android.vending"
    private const val CANAL = "android_auto_jarvis"
    private const val NOTIF = 7720

    fun installateur(ctx: Context): String? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
        else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(ctx.packageName)
    }.getOrNull()

    fun commePlayStore(ctx: Context) = installateur(ctx) == PLAY

    /** Installateur Google d'Android : seul « initiateur » dont Android Auto croit la source déclarée (Play Store). */
    const val INSTALLATEUR_GOOGLE = "com.google.android.packageinstaller"

    fun initiateur(ctx: Context): String? = if (android.os.Build.VERSION.SDK_INT >= 30)
        runCatching { ctx.packageManager.getInstallSourceInfo(ctx.packageName).initiatingPackageName }.getOrNull() else null

    /**
     * Marque « Play Store » que Android Auto reconnaît : source Play Store ET installation lancée par l'installateur
     * Google. Posée par le shell (Shizuku / ADB), l'initiateur est « com.android.shell » et Android Auto l'ignore.
     */
    fun marqueValide(ctx: Context) = installateur(ctx) == PLAY && initiateur(ctx) == INSTALLATEUR_GOOGLE

    private fun intentionInstallateur(uri: android.net.Uri) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive").setPackage(INSTALLATEUR_GOOGLE)

    fun installateurGoogleDispo(ctx: Context): Boolean = runCatching {
        ctx.packageManager.queryIntentActivities(intentionInstallateur(android.net.Uri.parse("content://${ctx.packageName}.files/share/jarvis.apk")), 0).isNotEmpty()
    }.getOrDefault(false)

    /**
     * Réinstalle JARVIS par-dessus lui-même avec l'installateur GOOGLE et « Play Store » comme source (méthode
     * « KingInstaller ») : Android Auto l'accepte alors comme une appli du Play Store, écrans voiture compris. Il suffit
     * de toucher « Mettre à jour » ; données et réglages sont conservés. À refaire après chaque mise à jour manuelle.
     */
    suspend fun installerCommePlay(ctx: Context): String {
        val app = ctx.applicationContext as JarvisApp
        if (android.os.Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            runCatching {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return "🔐 Autorise JARVIS à « installer des applis inconnues » (écran ouvert), reviens, puis retouche le bouton."
        }
        if (!installateurGoogleDispo(ctx)) return "❌ L'installateur Google d'Android (com.google.android.packageinstaller) est absent ou désactivé sur ce téléphone : Android Auto ne peut pas croire à une installation « Play Store »."
        val info = ctx.applicationInfo
        if (!info.splitSourceDirs.isNullOrEmpty()) return "❌ JARVIS est installé en plusieurs morceaux (split APK) : réinstallation impossible ici."
        val dest = File(ctx.cacheDir, "share/jarvis-auto.apk")
        val copie = withContext(Dispatchers.IO) { runCatching { dest.parentFile?.mkdirs(); File(info.sourceDir).copyTo(dest, overwrite = true); true }.getOrDefault(false) }
        if (!copie) return "❌ Copie de l'application impossible (espace disque ?)."
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", dest)
        app.store.autoVisibilite.set(true)
        app.store.autoReinstallTs.set(System.currentTimeMillis().toString())
        val i = intentionInstallateur(uri)
            .putExtra("android.intent.extra.INSTALLER_PACKAGE_NAME", PLAY)
            .putExtra("android.intent.extra.NOT_UNKNOWN_SOURCE", true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            withContext(Dispatchers.Main) { ctx.startActivity(i) }
            "📦 Touche « Mettre à jour » (ou « Installer ») dans la fenêtre d'Android. JARVIS se ferme quelques secondes puis revient ; ouvre ensuite le 🩺 Diagnostic : « initiateur » doit valoir $INSTALLATEUR_GOOGLE."
        } catch (e: Throwable) { "❌ Installateur Google impossible à ouvrir : ${e.message ?: e.javaClass.simpleName}" }
    }

    /**
     * État du contrôleur d'Android Auto (« CarProjectionValidator » dans dumpsys) : la case « Sources inconnues » et les
     * applis refusées. Nécessite Shizuku ou l'ADB intégré ; null si illisible.
     */
    fun etatValidateur(ctx: Context): Pair<Boolean?, String>? {
        if (!possible(ctx)) return null
        val r = runCatching {
            fr.dd.jarvis.device.PhoneShell.exec(ctx, "dumpsys activity service com.google.android.projection.gearhead 2>/dev/null | grep -i -A30 CarProjectionValidator | head -n 45")
        }.getOrNull() ?: return null
        if (r.startsWith("Erreur") || r.startsWith("ADB non") || r.startsWith("Shizuku") || !r.contains("CarProjectionValidator", true)) return null
        val sources = Regex("(?i)unknownSourcesEnabled\\s*=\\s*(true|false)").find(r)?.groupValues?.get(1)?.toBoolean()
        return sources to r.lines().filter { it.isNotBlank() }.take(30).joinToString("\n") { "   " + it.trim().take(160) }
    }

    fun libelleSource(ctx: Context): String = when (val i = installateur(ctx)) {
        PLAY -> "Play Store ✅"
        null -> "inconnue (installation manuelle)"
        "com.google.android.packageinstaller", "com.android.packageinstaller" -> "installateur Android (fichier APK)"
        else -> i
    }

    private fun composantEcrans(ctx: Context) = android.content.ComponentName(ctx.packageName, "fr.dd.jarvis.car.JarvisCarService")

    /**
     * Android Auto 17+ refuse les applis « écrans voiture » (Car App Library) installées hors Play Store, et peut alors
     * cacher tout le paquet, y compris la partie média. Par défaut JARVIS se présente donc UNIQUEMENT comme appli média :
     * le service des écrans voiture est désactivé. L'option « Écrans voiture » le réactive (installation par le Play Store).
     */
    fun appliquerMode(ctx: Context) {
        val app = ctx.applicationContext as? JarvisApp ?: return
        val pm = ctx.packageManager
        // Écrans voiture : avec une vraie marque Play Store (installateur Google), ou si on les force.
        val voulu = if (app.store.autoEcrans.value || marqueValide(ctx)) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            if (pm.getComponentEnabledSetting(composantEcrans(ctx)) != voulu)
                pm.setComponentEnabledSetting(composantEcrans(ctx), voulu, android.content.pm.PackageManager.DONT_KILL_APP)
        }
    }

    /** Capacités déclarées à Android Auto (fichier automotive_app_desc) : « media » est indispensable hors Play Store. */
    fun capacites(ctx: Context): List<String> = runCatching {
        val x = ctx.resources.getXml(fr.dd.jarvis.R.xml.automotive_app_desc)
        val l = mutableListOf<String>()
        while (x.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (x.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && x.name == "uses") x.getAttributeValue(null, "name")?.let { l += it }
        }
        l
    }.getOrDefault(emptyList())

    /**
     * Verdict d'Android Auto sur JARVIS, lu dans le journal système (lignes « CAR.VALIDATOR ») grâce à Shizuku ou à
     * l'ADB intégré. Le journal tourne vite : à lancer dans la minute qui suit le branchement à la voiture.
     */
    fun journal(ctx: Context): String {
        if (!possible(ctx)) return "Journal d'Android Auto illisible : il faut Shizuku démarré ou l'ADB intégré appairé (Réglages › Téléphone › Contrôle)."
        val brut = runCatching {
            fr.dd.jarvis.device.PhoneShell.exec(ctx, "logcat -d -v time 2>/dev/null | grep -E 'CAR\\.VALIDATOR|PlayGearhead' | tail -n 80")
        }.getOrElse { "Erreur : ${it.message}" }
        if (brut.startsWith("Erreur") || brut.startsWith("ADB non") || brut.startsWith("Shizuku")) return "Journal illisible : ${brut.take(160)}"
        val lignes = brut.lines().map { it.trim() }.filter { it.contains("VALIDATOR") || it.contains("PlayGearhead") }
        val miennes = lignes.filter { it.contains(ctx.packageName) }
        val autres = lignes.size - miennes.size
        if (miennes.isEmpty()) return "Aucune trace de JARVIS dans le journal d'Android Auto ($autres autre(s) ligne(s)). Branche-toi à la voiture puis relance le diagnostic dans la minute."
        val refus = miennes.lastOrNull { it.contains("DENIED", true) }
        val accord = miennes.lastOrNull { it.contains("ALLOW", true) || it.contains("APPROVED", true) || it.contains("GRANTED", true) }
        val verdict = when {
            refus != null && (accord == null || miennes.indexOf(refus) > miennes.indexOf(accord)) ->
                "❌ Android Auto REFUSE JARVIS : " + refus.substringAfter("DENIED", refus).trim(';', ' ', ':').take(160)
            accord != null -> "✅ Android Auto accepte JARVIS"
            else -> "ℹ️ JARVIS vu par Android Auto (verdict non écrit)"
        }
        val explication = if (refus != null && miennes.any { it.contains("owners empty", true) })
            "\n   → Android Auto a demandé au Play Store à qui appartient JARVIS (personne), puis aucune autre règle ne l'a accepté : " +
                "ni marque « Play Store » posée par l'installateur Google, ni « Sources inconnues » pour l'appli média." else ""
        return verdict + explication + "\n" + miennes.takeLast(6).joinToString("\n") { "   " + it.substringAfter(": ", it).take(200) }
    }

    /** Relevé automatique du verdict peu après le branchement à la voiture (si Shizuku / l'ADB intégré est disponible). */
    fun capturer(ctx: Context) {
        val app = ctx.applicationContext as? JarvisApp ?: return
        if (!possible(ctx)) return
        val j = journal(ctx)
        if (j.contains("JARVIS") && !j.startsWith("Aucune trace")) {
            val quand = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.FRANCE))
            app.store.autoJournal.set("($quand) $j")
        }
    }

    /** Arrêt forcé d'Android Auto : il revérifie ses applis au prochain lancement (à faire à l'arrêt). */
    fun relancerAndroidAuto(ctx: Context): String {
        if (!possible(ctx)) return "❌ Il faut Shizuku démarré ou l'ADB intégré appairé. Sinon : Réglages Android › Applications › Android Auto › Forcer l'arrêt."
        val r = fr.dd.jarvis.device.PhoneShell.exec(ctx, "am force-stop com.google.android.projection.gearhead")
        return if (r.startsWith("Erreur") || r.startsWith("ADB non")) "❌ $r" else "✅ Android Auto relancé : il revérifie ses applis à la prochaine connexion. Rebranche le téléphone."
    }

    /** Tout ce que JARVIS peut vérifier lui-même sur sa visibilité dans Android Auto (à copier pour un dépannage). Bloquant. */
    fun diagnostic(ctx: Context): String = buildString {
        val pm = ctx.packageManager
        val aa = "com.google.android.projection.gearhead"
        append("🚗 Diagnostic Android Auto\n")
        val v = runCatching { pm.getPackageInfo(aa, 0).versionName }.getOrNull()
        append("• Android Auto : ${v?.let { "installé (version $it)" } ?: "❌ non installé"}\n")
        val src = runCatching { if (android.os.Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(ctx.packageName) else null }.getOrNull()
        append("• Source de JARVIS : ${libelleSource(ctx)}\n")
        src?.let { append("   installateur=${it.installingPackageName} · initiateur=${it.initiatingPackageName}\n") }
        append("• Marque « Play Store » reconnue par Android Auto : " + when {
            marqueValide(ctx) -> "✅ oui (installateur Google + Play Store)"
            commePlayStore(ctx) -> "❌ non : posée par ${initiateur(ctx)} (Android Auto ne croit que l'installateur Google)"
            else -> "❌ non"
        } + "\n")
        append("• Installateur Google ($INSTALLATEUR_GOOGLE) : " + (if (installateurGoogleDispo(ctx)) "✅ disponible" else "❌ absent ou désactivé") + "\n")
        val media = runCatching { pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(ctx.packageName), 0) }.getOrDefault(emptyList())
        append("• Service MÉDIA (seul accepté par Android Auto 17+ hors Play Store) : " + (if (media.isNotEmpty()) "✅ ${media.first().serviceInfo.name.substringAfterLast('.')}" else "❌ absent") + "\n")
        val caps = capacites(ctx)
        append("• Déclaré à Android Auto : ${caps.joinToString(", ").ifBlank { "rien" }}" + (if ("media" in caps) " ✅" else " ❌ (« media » manquant)") + "\n")
        val ecrans = pm.getComponentEnabledSetting(composantEcrans(ctx)) != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        append("• Écrans voiture (Car App) : " + (if (ecrans) "activés ⚠️ (refusés hors Play Store, peuvent cacher JARVIS)" else "désactivés ✅ (JARVIS = appli média)") + "\n")
        val pi = runCatching { pm.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val code = pi?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }
        append("• Version : ${pi?.versionName} (code $code)" + (if (code == 1L) " ⚠️ numéro fixe : Android Auto peut garder son ancien verdict" else "") + "\n")
        val debug = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        append("• Version de JARVIS : ${if (debug) "test (débogable, tous les hôtes acceptés)" else "normale (hôtes Google uniquement)"}\n")
        val etat = etatValidateur(ctx)
        append("• « Sources inconnues » dans Android Auto : " + when (etat?.first) {
            true -> "✅ cochée (lu dans Android Auto)"
            false -> "❌ DÉCOCHÉE (lu dans Android Auto) : c'est elle qui bloque l'appli média"
            null -> "illisible (Shizuku ou ADB intégré nécessaire) — à vérifier"
        } + "\n")
        append("• Connecté à la voiture : ${if ((ctx.applicationContext as? JarvisApp)?.enVoiture == true) "oui" else "non"}\n")
        append("\n📜 Verdict d'Android Auto (journal système) :\n")
        append(journal(ctx))
        etat?.second?.let { append("\n\n🔎 Contrôleur d'Android Auto (dumpsys) :\n$it") }
        val avant = (ctx.applicationContext as? JarvisApp)?.store?.autoJournal?.value.orEmpty()
        if (avant.isNotBlank()) append("\n\n🕘 Relevé à la dernière connexion à la voiture :\n$avant")
    }

    /** Droits « adb » disponibles pour la réinstallation. */
    fun possible(ctx: Context) = fr.dd.jarvis.device.ShizukuShell.ready() || fr.dd.jarvis.device.AdbShell.hasKey(ctx)

    /**
     * Réinstalle JARVIS par-dessus lui-même avec la marque « Play Store » (données conservées). En cas de succès,
     * Android ferme JARVIS pendant la mise à jour ; l'écoute et les alarmes repartent toutes seules ensuite.
     */
    suspend fun reinstaller(ctx: Context): String = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext as JarvisApp
        val info = ctx.applicationInfo
        if (!info.splitSourceDirs.isNullOrEmpty()) return@withContext "❌ JARVIS est installé en plusieurs morceaux (split APK) : réinstallation impossible ici."
        val apk = File(info.sourceDir)
        if (!apk.canRead()) return@withContext "❌ Fichier de l'application illisible."
        app.store.autoVisibilite.set(true)
        app.store.autoReinstallTs.set(System.currentTimeMillis().toString())
        val options = "-r -i $PLAY"
        val r = if (fr.dd.jarvis.device.ShizukuShell.ready()) shizuku(apk, options)
            else if (fr.dd.jarvis.device.AdbShell.hasKey(ctx)) fr.dd.jarvis.device.AdbShell.installApk(ctx, apk, options)
            else return@withContext "❌ Il faut Shizuku démarré (ou l'ADB intégré appairé) : Réglages › Téléphone › Contrôle."
        // Si on lit encore ce résultat, c'est que l'installation n'a pas remplacé l'appli (sinon JARVIS a redémarré).
        if (r.contains("Success", true)) "✅ Réinstallé « depuis le Play Store » : JARVIS va se fermer quelques secondes." else "❌ Réinstallation refusée : ${r.take(300)}"
    }

    /** Installation par Shizuku : l'APK est envoyé sur l'entrée standard de « cmd package install -S taille ». */
    private fun shizuku(apk: File, options: String): String = try {
        val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", "cmd package install $options -S ${apk.length()}"), null, null) as Process
        p.outputStream.use { o -> apk.inputStream().use { it.copyTo(o, 256 * 1024) } }
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        p.waitFor()
        out.trim()
    } catch (e: Throwable) { "Erreur Shizuku : ${(e.cause ?: e).message}" }

    /** Après une mise à jour : confirme la visibilité, ou propose de la rétablir (si l'utilisateur l'avait déjà fait). */
    fun apresMiseAJour(ctx: Context) {
        val app = ctx.applicationContext as? JarvisApp ?: return
        if (!app.store.autoVisibilite.value) return
        val recent = System.currentTimeMillis() - (app.store.autoReinstallTs.value.toLongOrNull() ?: 0L) < 10 * 60_000L
        appliquerMode(ctx)
        if (marqueValide(ctx)) {
            if (recent) notifier(ctx, "✅ JARVIS reconnu « Play Store »", "Rebranche le téléphone à la voiture (ou relance Android Auto) : JARVIS est dans le lanceur.", ouvrir = true)
        } else if (!recent) {
            notifier(ctx, "🚗 Android Auto : JARVIS à rétablir", "La mise à jour a retiré la marque « Play Store ». Touche « Rétablir » puis « Mettre à jour » : 10 secondes.", ouvrir = false)
        }
    }

    private fun notifier(ctx: Context, titre: String, texte: String, ouvrir: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(android.app.NotificationChannel(CANAL, "Android Auto", NotificationManager.IMPORTANCE_DEFAULT))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val app = PendingIntent.getActivity(ctx, NOTIF, Intent(ctx, fr.dd.jarvis.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val b = NotificationCompat.Builder(ctx, CANAL).setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(titre).setContentText(texte).setStyle(NotificationCompat.BigTextStyle().bigText(texte))
            .setContentIntent(app).setAutoCancel(true)
        // « Rétablir » ouvre JARVIS, qui lance l'installateur Google (une fenêtre d'Android ne peut s'ouvrir qu'au premier plan).
        if (!ouvrir) b.addAction(0, "Rétablir", PendingIntent.getActivity(ctx, NOTIF + 1,
            Intent(ctx, fr.dd.jarvis.MainActivity::class.java).putExtra("auto_play", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags))
        runCatching { nm.notify(NOTIF, b.build()) }
    }

    fun lancerEnFond(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF)
        WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<VisibiliteWorker>().build())
    }
}

class VisibiliteReceiver : android.content.BroadcastReceiver() {
    companion object { const val ACTION = "fr.dd.jarvis.AUTO_VISIBLE" }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) VisibiliteAuto.lancerEnFond(context)
    }
}

class VisibiliteWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val r = VisibiliteAuto.reinstaller(applicationContext)
        if (!r.startsWith("✅")) {
            val nm = applicationContext.getSystemService(NotificationManager::class.java)
            runCatching {
                nm.notify(7722, NotificationCompat.Builder(applicationContext, JarvisApp.CHANNEL_CAR).setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Android Auto : réinstallation impossible").setContentText(r.take(120))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(r)).setAutoCancel(true).build())
            }
        }
        return Result.success()
    }
}
