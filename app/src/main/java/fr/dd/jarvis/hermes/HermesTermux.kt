package fr.dd.jarvis.hermes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.AdbShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Installe et pilote Hermès Agent (Nous Research) dans Termux, depuis Jarvis :
 *  1. Termux (GitHub officiel) — installation silencieuse via l'ADB intégré s'il est appairé ;
 *  2. autorisation « Exécuter des commandes dans Termux » + `allow-external-apps` (une seule fois) ;
 *  3. script d'installation lancé en arrière-plan dans Termux (paquet officiel signé, sinon sources) ;
 *  4. configuration : Gemini comme modèle, Groq en secours, serveur API local, pont MCP vers les outils de Jarvis ;
 *  5. démarrage de la passerelle (`hermes gateway run`) et vérification sur http://127.0.0.1:8642/health.
 */
object HermesTermux {
    const val TERMUX = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val PORT_API = 8642
    private const val HOME = "/data/data/com.termux/files/home"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val EMPREINTE = "C572B5FDD1A29CCFA9A912B6840B0848E139156D"
    private const val NOTIF_ID = 8642

    private val configurationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()

    /** Dernier message d'étape (affiché dans les réglages). */
    var message by mutableStateOf("")

    fun termuxInstalle(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(TERMUX, 0); true }.getOrDefault(false)
    fun permissionOk(ctx: Context) = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    // ------------------------------------------------------------------ 1. Termux

    /** Télécharge Termux (GitHub termux/termux-app) puis l'installe : sans écran via l'ADB intégré, sinon via l'installateur Android. */
    suspend fun installerTermux(ctx: Context): String = withContext(Dispatchers.IO) {
        if (termuxInstalle(ctx)) return@withContext "✅ Termux est déjà installé."
        try {
            val rel = http.newCall(Request.Builder().url("https://api.github.com/repos/termux/termux-app/releases/latest")
                .header("User-Agent", "JarvisHUD").build()).execute().use { it.body?.string() ?: "" }
            val assets = JSONObject(rel).getJSONArray("assets")
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            val noms = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name").endsWith(".apk") }
            val choix = noms.firstOrNull { it.getString("name").contains(abi) } ?: noms.firstOrNull { it.getString("name").contains("universal") }
                ?: return@withContext "❌ APK Termux introuvable sur GitHub."
            val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "termux.apk")
            http.newCall(Request.Builder().url(choix.getString("browser_download_url")).header("User-Agent", "JarvisHUD").build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext "❌ Téléchargement de Termux refusé (${r.code})."
                r.body!!.byteStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            }
            if (AdbShell.hasKey(ctx)) {
                val r = runCatching { AdbShell.installApk(ctx, f) }.getOrElse { it.message ?: "" }
                if (r.contains("Success", true)) return@withContext "✅ Termux installé (${choix.getString("name")})."
            }
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@withContext "Autorise « Installer des applis inconnues » pour JARVIS (écran ouvert), puis retouche « Installer Termux »."
            }
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            "📦 Termux téléchargé : confirme l'installation."
        } catch (e: Throwable) { "❌ " + (e.message ?: e.javaClass.simpleName) }
    }

    // ------------------------------------------------------------------ 2. Autorisation

    /** Commande à saisir UNE fois dans Termux pour accepter les commandes de Jarvis. */
    const val COMMANDE_AUTORISATION =
        "mkdir -p ~/.termux; grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null && " +
            "sed -i 's/^allow-external-apps.*/allow-external-apps = true/' ~/.termux/termux.properties || " +
            "echo 'allow-external-apps = true' >> ~/.termux/termux.properties; termux-reload-settings; echo '✅ Jarvis autorisé'"

    /** Version courte, tapée automatiquement via l'ADB intégré (pas d'espace ni de guillemet à échapper). */
    private const val COMMANDE_ADB = "mkdir%s-p%s~/.termux;echo%sallow-external-apps=true>>~/.termux/termux.properties;termux-reload-settings"

    /** Vérifie que Termux accepte les commandes de Jarvis (le script renvoie « pong » au pont local). */
    suspend fun autorisationOk(app: JarvisApp, attenteMs: Long = 6000): Boolean {
        if (!termuxInstalle(app) || !permissionOk(app)) return false
        PontMcp.demarrer(app)
        val avant = PontMcp.dernierSignal
        if (executer(app, scriptSignal(app, "pong"), arrierePlan = true) != null) return false
        val fin = System.currentTimeMillis() + attenteMs
        while (System.currentTimeMillis() < fin) { if (PontMcp.dernierSignal > avant) return true; delay(300) }
        return false
    }

    /**
     * Rend Termux pilotable par Jarvis. Avec l'ADB intégré : permission accordée, Termux ouvert, commande tapée
     * automatiquement, limites Android sur les processus de Termux levées. Sinon : commande copiée, Termux ouvert.
     */
    suspend fun autoriser(app: JarvisApp): String = withContext(Dispatchers.IO) {
        if (!termuxInstalle(app)) return@withContext "Installe d'abord Termux."
        val adb = AdbShell.hasKey(app)
        if (adb) {
            runCatching { AdbShell.exec(app, "pm grant ${app.packageName} $PERMISSION") }
            // Android 12+ tue les processus « fantômes » (enfants) de Termux : on lève cette limite et l'optimisation batterie.
            runCatching {
                AdbShell.exec(app, "settings put global settings_enable_monitor_phantom_procs false; " +
                    "device_config set_sync_disabled_for_tests persistent; device_config put activity_manager max_phantom_processes 2147483647; " +
                    "dumpsys deviceidle whitelist +$TERMUX; cmd appops set $TERMUX RUN_ANY_IN_BACKGROUND allow")
            }
        }
        if (!permissionOk(app)) return@withContext "Accorde d'abord à JARVIS la permission « Exécuter des commandes dans Termux » (bouton ci-dessus)."
        if (autorisationOk(app, 4000)) return@withContext "✅ Termux accepte déjà les commandes de Jarvis."
        if (adb) {
            message = "⏳ Ouverture de Termux et saisie automatique de l'autorisation…"
            runCatching { AdbShell.exec(app, "am start -n $TERMUX/.app.TermuxActivity") }
            for (essai in 1..3) {
                delay(if (essai == 1) 12_000 else 20_000) // 1re ouverture : Termux installe ses paquets de base
                runCatching { AdbShell.exec(app, "input text '$COMMANDE_ADB' && input keyevent 66") }
                delay(2500)
                if (autorisationOk(app, 5000)) {
                    runCatching { AdbShell.exec(app, "am start -n ${app.packageName}/.MainActivity") }
                    return@withContext "✅ Termux autorisé automatiquement."
                }
            }
            runCatching { AdbShell.exec(app, "am start -n ${app.packageName}/.MainActivity") }
        }
        withContext(Dispatchers.Main) {
            (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Jarvis → Termux", COMMANDE_AUTORISATION))
            app.packageManager.getLaunchIntentForPackage(TERMUX)?.let { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        "📋 Commande copiée et Termux ouvert : attends la fin de sa première préparation, fais un appui long › Coller, puis Entrée. " +
            "Reviens ensuite dans Jarvis et touche « Installer Hermès »." +
            (if (!adb) "\nConseil Android 12+ : Options pour développeurs › « Désactiver les restrictions des processus enfants », et batterie de Termux sur « Aucune restriction »." else "")
    }

    // ------------------------------------------------------------------ Exécution dans Termux

    /** Envoie une commande bash à Termux (RUN_COMMAND). Renvoie un message d'erreur, ou null si elle est partie. */
    fun executer(ctx: Context, script: String, arrierePlan: Boolean = true, libelle: String = "Jarvis"): String? {
        if (!termuxInstalle(ctx)) return "Termux n'est pas installé."
        if (!permissionOk(ctx)) return "Permission « Exécuter des commandes dans Termux » non accordée à JARVIS."
        val i = Intent("com.termux.RUN_COMMAND").setClassName(TERMUX, "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", arrierePlan)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", libelle)
        return try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            null
        } catch (e: Throwable) { "Termux a refusé la commande : ${e.message ?: e.javaClass.simpleName}" }
    }

    /** Fonction shell `p` : affiche ET envoie une ligne de progression au pont local de Jarvis. */
    private fun prelude(app: JarvisApp) = scriptPrelude(app.store.mcpJeton.value)

    internal fun scriptPrelude(jeton: String, port: Int = PontMcp.PORT) = """
J_URL=${q("http://127.0.0.1:$port/progres")}; J_TOK=${q(jeton)}
p() {
  local message="${'$'
