package fr.dd.jarvis.device

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Exécution de commandes shell avec les droits « adb » via Shizuku.
 * Shizuku doit être installé et démarré (débogage sans fil), puis autorisé pour JARVIS.
 */
object ShizukuShell {

    private const val PKG = "moe.shizuku.privileged.api"
    private const val DELAI_COMMANDE_SECONDES = 20L
    private const val LIMITE_SORTIE = 4000

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

    /** Lit entièrement un flux pour éviter de bloquer le processus, mais ne conserve que [limite] caractères. */
    internal fun lireFluxLimite(flux: InputStream, limite: Int = LIMITE_SORTIE): String {
        val sortie = StringBuilder(minOf(limite, 1024))
        var tronque = false
        flux.bufferedReader(Charsets.UTF_8).use { lecteur ->
            val tampon = CharArray(1024)
            while (true) {
                val n = lecteur.read(tampon)
                if (n < 0) break
                val restant = (limite - sortie.length).coerceAtLeast(0)
                if (restant > 0) sortie.append(tampon, 0, minOf(n, restant))
                if (n > restant) tronque = true
            }
        }
        if (tronque) sortie.append("\n… (sortie tronquée)")
        return sortie.toString()
    }

    /** Exécute [command] via sh -c ; stdout/stderr sont drainés en parallèle et le processus est borné à 20 s. */
    fun exec(command: String): String {
        if (!running()) return "Shizuku n'est pas démarré."
        if (!granted()) return "Shizuku n'est pas autorisé pour JARVIS."
        var processus: Process? = null
        val lecteurs = Executors.newFixedThreadPool(2)
        try {
            // newProcess est privée depuis Shizuku 13 : accès par réflexion (méthode utilisée par les apps clientes).
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            processus = m.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val p = processus
            // Lire les deux pipes en parallèle évite le deadlock si stderr se remplit pendant la lecture de stdout.
            val stdout = lecteurs.submit(Callable { lireFluxLimite(p.inputStream) })
            val stderr = lecteurs.submit(Callable { lireFluxLimite(p.errorStream) })
            val termine = p.waitFor(DELAI_COMMANDE_SECONDES, TimeUnit.SECONDS)
            if (!termine) {
                p.destroy()
                if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroyForcibly()
                p.waitFor(2, TimeUnit.SECONDS)
            }
            val out = runCatching { stdout.get(2, TimeUnit.SECONDS) }.getOrDefault("")
            val err = runCatching { stderr.get(2, TimeUnit.SECONDS) }.getOrDefault("")
            val brut = listOf(out, err).filter { it.isNotBlank() }.joinToString("\n").trim()
            val texte = if (brut.length > LIMITE_SORTIE) brut.take(LIMITE_SORTIE) + "\n… (sortie totale limitée à $LIMITE_SORTIE caractères)" else brut
            val resultat = texte.ifEmpty { "(commande exécutée)" }
            return if (termine) resultat else "⏱️ Commande interrompue après $DELAI_COMMANDE_SECONDES secondes.\n$resultat"
        } catch (interruption: InterruptedException) {
            processus?.destroyForcibly()
            Thread.currentThread().interrupt()
            return "Commande Shizuku interrompue."
        } catch (e: Throwable) {
            processus?.destroyForcibly()
            return "Erreur shell : ${(e.cause ?: e).message ?: e.javaClass.simpleName}"
        } finally {
            lecteurs.shutdownNow()
            processus?.let { runCatching { it.inputStream.close() }; runCatching { it.errorStream.close() } }
        }
    }
}
