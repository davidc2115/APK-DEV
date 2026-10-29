package fr.dd.jarvis.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Télécharge la dernière version de Shizuku (GitHub officiel) et lance l'installation Android. */
object ShizukuInstaller {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private fun download(ctx: Context): File {
        val rel = http.newCall(Request.Builder().url("https://api.github.com/repos/RikkaApps/Shizuku/releases/latest")
            .header("User-Agent", "JarvisHUD").build()).execute().use { it.body?.string() ?: "" }
        val assets = JSONObject(rel).getJSONArray("assets")
        var url = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) { url = a.getString("browser_download_url"); break }
        }
        require(url.isNotBlank()) { "APK Shizuku introuvable sur GitHub." }
        val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "shizuku.apk")
        http.newCall(Request.Builder().url(url).header("User-Agent", "JarvisHUD").build()).execute().use { r ->
            require(r.isSuccessful) { "Téléchargement refusé (${r.code})." }
            r.body!!.byteStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        }
        return f
    }

    /** Tout-en-un via l'ADB intégré : téléchargement, installation silencieuse, lancement puis démarrage de Shizuku. */
    suspend fun installAuto(ctx: Context): String = withContext(Dispatchers.IO) {
        try {
            if (!AdbShell.hasKey(ctx)) return@withContext "Appaire d'abord l'ADB intégré (ci-dessous) : l'installation sera alors automatique, sans écran de confirmation."
            val f = download(ctx)
            val r = AdbShell.installApk(ctx, f)
            if (!r.contains("Success", true)) return@withContext "❌ Installation : $r"
            AdbShell.exec(ctx, "monkey -p moe.shizuku.privileged.api -c android.intent.category.LAUNCHER 1")
            kotlinx.coroutines.delay(4000)
            "✅ Shizuku installé.\n" + startViaAdb(ctx)
        } catch (e: Throwable) {
            "❌ " + (e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun install(ctx: Context): String = withContext(Dispatchers.IO) {
        try {
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@withContext "Autorise d'abord « Installer des applis inconnues » pour JARVIS (écran ouvert), puis retouche « Installer Shizuku »."
            }
            val rel = http.newCall(Request.Builder().url("https://api.github.com/repos/RikkaApps/Shizuku/releases/latest")
                .header("User-Agent", "JarvisHUD").build()).execute().use { it.body?.string() ?: "" }
            val assets = JSONObject(rel).getJSONArray("assets")
            var url = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) { url = a.getString("browser_download_url"); break }
            }
            if (url.isBlank()) return@withContext "❌ APK Shizuku introuvable sur GitHub."
            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            val f = File(dir, "shizuku.apk")
            http.newCall(Request.Builder().url(url).header("User-Agent", "JarvisHUD").build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext "❌ Téléchargement refusé (${r.code})."
                r.body!!.byteStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            }
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            "✅ Shizuku téléchargé (${f.length() / 1024} Ko) : confirme l'installation, puis touche « Démarrer Shizuku via ADB »."
        } catch (e: Throwable) {
            "❌ " + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** Démarre le service Shizuku grâce à l'ADB intégré (une fois Shizuku installé et ouvert au moins une fois). */
    suspend fun startViaAdb(ctx: Context): String {
        val r = AdbShell.exec(ctx, "sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh")
        return "Démarrage Shizuku : $r\n(Si « No such file » : ouvre l'app Shizuku une fois, puis réessaie.)"
    }
}
