package fr.dd.jarvis.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.x509.X509V3CertificateGenerator
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * ADB intégré : JARVIS s'appaire lui-même avec le « Débogage sans fil » d'Android 11+ et obtient les droits adb
 * (même niveau que Shizuku) sans application supplémentaire. Wi-Fi requis ; à refaire seulement si l'appairage est révoqué.
 */
object AdbShell {
    const val CHANNEL = "adb_pair"
    const val KEY_CODE = "pair_code"
    const val ACTION_PAIR = "fr.dd.jarvis.ADB_PAIR"

    private var manager: Manager? = null

    private class Manager(private val ctx: Context) : AbsAdbConnectionManager() {
        private val keyFile = File(ctx.filesDir, "adb_key.pk8")
        private val certFile = File(ctx.filesDir, "adb_cert.der")
        private var priv: PrivateKey
        private var cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            setHostAddress("127.0.0.1")
            val loaded = try {
                if (keyFile.exists() && certFile.exists()) {
                    val k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    val c = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
                    k to c
                } else null
            } catch (e: Exception) {
                null
            }
            if (loaded != null) {
                priv = loaded.first; cert = loaded.second
            } else {
                val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
                val subject = X500Principal("CN=JARVIS")
                val gen = X509V3CertificateGenerator()
                gen.setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                gen.setIssuerDN(subject); gen.setSubjectDN(subject)
                gen.setNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
                gen.setNotAfter(Date(System.currentTimeMillis() + 3_650L * 86_400_000L))
                gen.setPublicKey(kp.public)
                gen.setSignatureAlgorithm("SHA256withRSA")
                @Suppress("DEPRECATION")
                val c = gen.generate(kp.private)
                priv = kp.private; cert = c
                keyFile.writeBytes(kp.private.encoded)
                certFile.writeBytes(c.encoded)
            }
        }

        override fun getPrivateKey(): PrivateKey = priv
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "JARVIS"
    }

    @Synchronized
    private fun mgr(ctx: Context): Manager = manager ?: Manager(ctx.applicationContext).also { manager = it }

    /** Une clé ADB existe : appairage fait au moins une fois (la connexion se rétablit seule). */
    fun hasKey(ctx: Context): Boolean = File(ctx.filesDir, "adb_key.pk8").exists()

    fun connected(): Boolean = try { manager?.isConnected == true } catch (e: Throwable) { false }

    /** Connexion (découverte automatique du port via mDNS). Nécessite d'être appairé et en Wi-Fi. */
    suspend fun connect(ctx: Context): String = withContext(Dispatchers.IO) {
        try {
            val m = mgr(ctx)
            if (m.isConnected) return@withContext "✅ Déjà connecté."
            if (m.connectTls(ctx.applicationContext, 8000)) "✅ ADB connecté." else "❌ Connexion refusée (appairage requis ?)."
        } catch (e: Throwable) {
            "❌ " + (e.message ?: e.javaClass.simpleName) + "\n(Vérifie : Wi-Fi activé, « Débogage sans fil » activé, appairage fait.)"
        }
    }

    /** Installation silencieuse d'un APK via ADB (flux « exec: », sans fichier intermédiaire). */
    suspend fun installApk(ctx: Context, apk: File): String = withContext(Dispatchers.IO) {
        try {
            val m = mgr(ctx)
            if (!m.isConnected && !m.connectTls(ctx.applicationContext, 6000)) return@withContext "ADB non connecté."
            m.openStream("exec:cmd package install -S ${apk.length()}").use { st ->
                st.openOutputStream().use { o -> apk.inputStream().use { it.copyTo(o) }; o.flush() }
                st.openInputStream().bufferedReader().readText().trim()
            }
        } catch (e: Throwable) {
            "Erreur ADB : ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Accorde à JARVIS un maximum d'autorisations en une fois (nécessite l'ADB connecté). */
    suspend fun grantAll(ctx: Context): String {
        val pkg = ctx.packageName
        val perms = listOf(
            "POST_NOTIFICATIONS", "RECORD_AUDIO", "READ_CALENDAR", "WRITE_CALENDAR", "READ_CONTACTS", "WRITE_CONTACTS",
            "READ_SMS", "SEND_SMS", "CALL_PHONE", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "WRITE_SECURE_SETTINGS"
        )
        val listener = "$pkg/fr.dd.jarvis.service.JarvisNotificationListener"
        val a11y = "$pkg/fr.dd.jarvis.service.JarvisAccessibilityService"
        val cmds = buildList {
            add("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
            perms.forEach { add("pm grant $pkg android.permission.$it") }
            listOf("SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES", "RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND", "WAKE_LOCK").forEach { add("appops set $pkg $it allow") }
            add("dumpsys deviceidle whitelist +$pkg")
            add("cmd notification allow_listener $listener")
            add("cur=\$(settings get secure enabled_accessibility_services); case \"\$cur\" in *$a11y*) ;; null|'') settings put secure enabled_accessibility_services $a11y;; *) settings put secure enabled_accessibility_services \"\$cur:$a11y\";; esac; settings put secure accessibility_enabled 1")
        }
        val out = exec(ctx, cmds.joinToString("; "))
        val errs = out.lines().filter { it.contains("Exception", true) || it.contains("Unknown permission", true) || it.contains("not a changeable", true) || it.startsWith("Erreur") || it.startsWith("ADB non") }
        return if (errs.isEmpty()) "✅ Autorisations accordées via ADB (permissions, arrière-plan, accès aux notifications, accessibilité)."
        else "⚠️ Accordé avec ${errs.size} refus :\n" + errs.take(6).joinToString("\n") { it.take(140) }
    }

    /** Coupe la connexion ADB (l'appairage reste valable). */
    fun disconnect() {
        try { manager?.disconnect() } catch (_: Throwable) {}
    }

    /** Désactive le débogage sans fil et le débogage USB (nécessite une connexion ADB active), puis coupe la connexion. */
    suspend fun disableDebugging(ctx: Context): String {
        val r = exec(ctx, "settings put global adb_wifi_enabled 0; settings put global adb_enabled 0")
        disconnect()
        return if (r.startsWith("Erreur") || r.startsWith("ADB non connecté")) r
        else "✅ Débogage désactivé. Pour reprendre le contrôle : réactive « Débogage sans fil » dans les options pour développeurs puis touche « Connecter »."
    }

    /** Appairage avec le code à 6 chiffres affiché par Android ; le port d'appairage est trouvé automatiquement. */
    suspend fun pair(ctx: Context, code: String): String = withContext(Dispatchers.IO) {
        try {
            val app = ctx.applicationContext
            val parts = code.trim().split(Regex("\\s+"))
            val manualPort = if (parts.size == 2) parts[0].toIntOrNull() else null
            val pin = parts.last()
            val found = CompletableDeferred<Pair<InetAddress?, Int>>()
            val m = mgr(app)
            val host: String
            val port: Int
            if (manualPort != null) {
                host = "127.0.0.1"; port = manualPort
            } else {
                val mdns = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { h, p -> if (p > 0) found.complete(h to p) }
                mdns.start()
                val r = try { withTimeoutOrNull(15_000) { found.await() } } finally { mdns.stop() }
                    ?: return@withContext "❌ Fenêtre d'appairage introuvable. Garde l'écran « Associer avec un code » ouvert (Wi-Fi), ou saisis « port code » (ex. 37891 123456) dans la notification."
                host = r.first?.hostAddress ?: "127.0.0.1"; port = r.second
            }
            // Appairage borné à 20 s (sinon la notification tournerait indéfiniment)
            val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
            val fut = pool.submit(java.util.concurrent.Callable { m.pair(host, port, pin) })
            try {
                fut.get(20, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                fut.cancel(true)
                return@withContext "❌ Appairage sans réponse (20 s). Vérifie le code et que l'écran d'appairage est toujours affiché."
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            } finally {
                pool.shutdownNow()
            }
            "✅ Appairage réussi. " + connect(app)
        } catch (e: Throwable) {
            "❌ Appairage échoué : " + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** Exécute une commande shell (bloquant ; appeler hors thread principal). */
    suspend fun exec(ctx: Context, command: String): String = withContext(Dispatchers.IO) {
        try {
            val m = mgr(ctx)
            if (!m.isConnected && !m.connectTls(ctx.applicationContext, 6000)) return@withContext "ADB non connecté (Réglages › Contrôle du téléphone)."
            m.openStream("shell:$command").use { s ->
                val out = s.openInputStream().bufferedReader().readText().trim()
                if (out.isEmpty()) "(commande exécutée)" else out.take(4000)
            }
        } catch (e: Throwable) {
            "Erreur ADB : ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Notification avec champ de saisie : on tape le code sans quitter la fenêtre d'appairage Android. */
    fun postPairingNotification(ctx: Context, text: String = "Ouvre « Associer l'appareil avec un code d'appairage », puis saisis le code ici.") {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Appairage ADB", NotificationManager.IMPORTANCE_HIGH))
        val remote = RemoteInput.Builder(KEY_CODE).setLabel("Code d'appairage (6 chiffres)").build()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, AdbPairReceiver::class.java).setAction(ACTION_PAIR), flags)
        val action = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "Saisir le code", pi).addRemoteInput(remote).build()
        nm.notify(77, NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("JARVIS — appairage ADB").setContentText(text).addAction(action).setOnlyAlertOnce(true).build())
    }

    fun postResult(ctx: Context, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(77, NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("JARVIS — ADB").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).build())
    }
}

class AdbPairReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AdbShell.KEY_CODE)?.toString().orEmpty()
        val nums = Regex("\\d+").findAll(raw).map { it.value }.toList()
        val code = when {
            nums.size >= 2 && nums.last().length == 6 -> nums[nums.size - 2] + " " + nums.last()
            nums.isNotEmpty() && nums.last().length == 6 -> nums.last()
            else -> ""
        }
        if (code.isBlank()) { AdbShell.postPairingNotification(context, "Code invalide : 6 chiffres attendus (ou « port code »)."); return }
        AdbShell.postResult(context, "⏳ Appairage en cours…")
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val r = try { AdbShell.pair(context, code) } catch (e: Throwable) { "❌ " + (e.message ?: e.javaClass.simpleName) }
            AdbShell.postResult(context, r)
            pending.finish()
        }
    }
}

/** Shell du téléphone : Shizuku s'il est prêt, sinon ADB intégré. */
object PhoneShell {
    fun ready(ctx: Context): Boolean = ShizukuShell.ready() || AdbShell.connected()

    fun exec(ctx: Context, command: String): String =
        if (ShizukuShell.ready()) ShizukuShell.exec(command)
        else kotlinx.coroutines.runBlocking(Dispatchers.IO) { AdbShell.exec(ctx, command) }
}
