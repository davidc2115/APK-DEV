package com.jarvis.assistant

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Capture tout crash non géré de l'application et l'enregistre dans un fichier
 * texte lisible (filesDir/crash_log.txt). Au prochain lancement, MainActivity
 * affiche ce contenu dans une fenêtre copiable — plus besoin d'ADB ou de
 * brancher le téléphone à un PC pour diagnostiquer un plantage.
 */
class JarvisApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))

                val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date())
                val report = buildString {
                    append("═══ CRASH JARVIS — $timestamp ═══\n")
                    append("Appareil : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})\n")
                    append("Thread : ${thread.name}\n\n")
                    append(sw.toString())
                }

                File(filesDir, "crash_log.txt").writeText(report)
            } catch (e: Exception) {
                // Si l'écriture du log échoue elle-même, on ne bloque pas le crash normal.
            }

            // Laisse Android gérer le crash normalement ensuite (fermeture de l'app).
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
