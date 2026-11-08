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

    fun libelleSource(ctx: Context): String = when (val i = installateur(ctx)) {
        PLAY -> "Play Store ✅"
        null -> "inconnue (installation manuelle)"
        "com.google.android.packageinstaller", "com.android.packageinstaller" -> "installateur Android (fichier APK)"
        else -> i
    }

    /** Tout ce que JARVIS peut vérifier lui-même sur sa visibilité dans Android Auto (à copier pour un dépannage). */
    fun diagnostic(ctx: Context): String = buildString {
        val pm = ctx.packageManager
        val aa = "com.google.android.projection.gearhead"
        append("🚗 Diagnostic Android Auto\n")
        val v = runCatching { pm.getPackageInfo(aa, 0).versionName }.getOrNull()
        append("• Android Auto : ${v?.let { "installé (version $it)" } ?: "❌ non installé"}\n")
        val src = runCatching { if (android.os.Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(ctx.packageName) else null }.getOrNull()
        append("• Source de JARVIS : ${libelleSource(ctx)}" + (if (commePlayStore(ctx)) " ✅" else " ❌ (Android Auto le cache tant que « Sources inconnues » n'est pas coché)") + "\n")
        src?.let { append("   installateur=${it.installingPackageName} · initiateur=${it.initiatingPackageName}\n") }
        val services = runCatching {
            pm.queryIntentServices(Intent("androidx.car.app.CarAppService").setPackage(ctx.packageName), android.content.pm.PackageManager.GET_RESOLVED_FILTER)
        }.getOrDefault(emptyList())
        if (services.isEmpty()) append("• Service voiture : ❌ introuvable\n") else services.forEach { r ->
            val f = r.filter
            val cats = (0 until (f?.countCategories() ?: 0)).map { f!!.getCategory(it).substringAfterLast('.') }
            val etat = pm.getComponentEnabledSetting(android.content.ComponentName(ctx.packageName, r.serviceInfo.name))
            append("• Service voiture : ✅ ${r.serviceInfo.name.substringAfterLast('.')} · catégories ${cats.joinToString("+")} · " +
                (if (etat == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED) "❌ désactivé" else "activé") + "\n")
        }
        val debug = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        append("• Version de JARVIS : ${if (debug) "test (débogable, tous les hôtes acceptés)" else "normale (hôtes Google uniquement)"}\n")
        append("• « Sources inconnues » dans Android Auto : impossible à lire depuis JARVIS — à vérifier (étape 2)\n")
        append("• Connecté à la voiture : ${if ((ctx.applicationContext as? JarvisApp)?.enVoiture == true) "oui" else "non"}")
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
            else return@withContext "❌ Il faut Shizuku démarré (ou l'ADB intégré appairé) : Réglages › Téléphone › Shizuku."
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
        if (commePlayStore(ctx)) {
            if (recent) notifier(ctx, "✅ JARVIS est visible dans Android Auto", "Réinstallation réussie. Rebranche le téléphone à la voiture (ou relance Android Auto).", ouvrir = true)
        } else if (!recent) {
            notifier(ctx, "🚗 JARVIS n'est plus visible dans Android Auto", "La mise à jour a retiré la marque « Play Store ». Touche « Rétablir » : JARVIS se réinstalle en quelques secondes.", ouvrir = false)
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
        if (!ouvrir) b.addAction(0, "Rétablir", PendingIntent.getBroadcast(ctx, NOTIF + 1,
            Intent(ctx, VisibiliteReceiver::class.java).setAction(VisibiliteReceiver.ACTION).setPackage(ctx.packageName), flags))
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
