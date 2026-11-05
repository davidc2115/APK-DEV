package fr.dd.jarvis.hermes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reçoit le résultat officiel de RUN_COMMAND envoyé par Termux via PendingIntent.
 * Ce retour révèle les refus internes que le seul pont HTTP ne peut pas voir.
 */
class HermesRunResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bundle = intent.getBundleExtra("com.termux.service.EXTRA_PLUGIN_RESULT_BUNDLE")
        if (bundle == null) {
            HermesTermux.recevoirRetourTermux(-1, -2, "Termux n'a retourné aucun paquet de résultat.", "")
            return
        }
        val exitCode = bundle.getInt("com.termux.service.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE", -1)
        val internalError = bundle.getInt("com.termux.service.EXTRA_PLUGIN_RESULT_BUNDLE_ERR", -1)
        val errorMessage = bundle.getString("com.termux.service.EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG").orEmpty()
        val stderr = bundle.getString("com.termux.service.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR").orEmpty()
        HermesTermux.recevoirRetourTermux(exitCode, internalError, errorMessage, stderr)
    }
}
