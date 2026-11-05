package fr.dd.jarvis.hermes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reçoit le résultat officiel RUN_COMMAND envoyé par Termux via PendingIntent.
 *
 * Termux utilise les valeurs littérales définies dans TermuxConstants :
 * bundle "result", puis "exitCode", "err", "errmsg" et "stderr".
 */
class HermesRunResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
        if (bundle == null) {
            HermesTermux.recevoirRetourTermux(
                exitCode = -1,
                erreurInterne = -2,
                messageErreur = "Le retour Termux ne contient pas le bundle « result ». " +
                    "Vérifie la version de Termux (0.109 minimum) et le retour PendingIntent.",
                stderr = ""
            )
            return
        }

        val exitCode = bundle.getInt(EXTRA_EXIT_CODE, -1)
        val internalError = bundle.getInt(EXTRA_ERROR, -1)
        val errorMessage = bundle.getString(EXTRA_ERROR_MESSAGE).orEmpty()
        val stderr = bundle.getString(EXTRA_STDERR).orEmpty()

        HermesTermux.recevoirRetourTermux(exitCode, internalError, errorMessage, stderr)
    }

    private companion object {
        const val EXTRA_RESULT_BUNDLE = "result"
        const val EXTRA_EXIT_CODE = "exitCode"
        const val EXTRA_ERROR = "err"
        const val EXTRA_ERROR_MESSAGE = "errmsg"
        const val EXTRA_STDERR = "stderr"
    }
}
