package fr.dd.jarvis.device

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Exécution de commandes shell avec les droits « adb » via Shizuku.
 * Shizuku doit être installé et démarré (débogage sans fil), puis autorisé pour JARVIS.
 */
object ShizukuShell {

    private const val PKG = "moe.shizuku.privileged.api"

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun running(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun granted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(1001)
        } catch (_: Throwable) {
        }
    }

    fun ready(): Boolean = running() && granted()

    /** Exécute [command] via `sh -c` et renvoie stdout + stderr (tronqués). */
    fun exec(command: String): String {
        if (!running()) return "Shizuku n'est pas démarré."
        if (!granted()) return "Shizuku n'est pas autorisé pour JARVIS."
        return try {
            // newProcess est privée depuis Shizuku 13 : accès par réflexion (méthode utilisée par les apps clientes).
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            val res = (out + err).trim()
            if (res.isEmpty()) "(commande exécutée)" else res.take(4000)
        } catch (e: Throwable) {
            "Erreur shell : ${(e.cause ?: e).message}"
        }
    }
}
