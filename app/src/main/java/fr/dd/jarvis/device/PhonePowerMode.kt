package fr.dd.jarvis.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Modes d'alimentation du téléphone.
 *
 * Android permet de lire l'état Battery Saver publiquement, mais son activation directe est réservée
 * aux applications système. JARVIS passe donc par ADB/Shizuku lorsqu'ils sont autorisés.
 * Les modes Balanced/Performance de Xiaomi sont propriétaires : on ouvre le sélecteur HyperOS et
 * on utilise l'accessibilité si elle est active, sans écrire de propriétés CPU/GPU cachées.
 */
object PhonePowerMode {
    fun economieActive(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(PowerManager::class.java).isPowerSaveMode
    }.getOrDefault(false)

    fun etat(ctx: Context): String =
        if (economieActive(ctx)) "Économiseur de batterie Android actif"
        else "Économiseur Android désactivé · mode équilibré ou performance HyperOS à vérifier"

    private fun erreurShell(s: String) =
        s.contains("Error", true) || s.contains("Exception", true) ||
            s.startsWith("ADB non", true) || s.startsWith("Shizuku n'est pas", true) ||
            s.startsWith("Erreur", true)

    private suspend fun ouvrirSelecteur(app: JarvisApp): String = withContext(Dispatchers.IO) {
        val ctx = app.applicationContext
        if (PhoneShell.ready(ctx)) {
            val r = PhoneShell.exec(ctx, "am start -n com.android.settings/com.android.settings.fuelgauge.PowerModeSettings")
            if (!erreurShell(r)) return@withContext "Sélecteur HyperOS ouvert."
        }
        try {
            ctx.startActivity(
                Intent().setComponent(
                    ComponentName("com.android.settings", "com.android.settings.fuelgauge.PowerModeSettings")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "Sélecteur HyperOS ouvert."
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Réglages de batterie Android ouverts."
            } catch (e: Exception) {
                "Impossible d'ouvrir les réglages de batterie : ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    suspend fun appliquer(app: JarvisApp, demande: String): String {
        val mode = when (demande.trim().lowercase()) {
            "eco", "economie", "économie", "economy", "battery", "battery_saver", "saver" -> "economie"
            "normal", "equilibre", "équilibré", "balanced", "normal/equilibre" -> "equilibre"
            "performance", "performances", "perf", "haute performance" -> "performance"
            "etat", "état", "status", "lire" -> "etat"
            else -> return "Mode inconnu. Choisis économie, équilibré ou performance."
        }
        if (mode == "etat") return etat(app)

        if (mode == "economie" && PhoneShell.ready(app)) {
            val shell = withContext(Dispatchers.IO) { PhoneShell.exec(app, "cmd power set-mode 1") }
            delay(500)
            if (!erreurShell(shell) && economieActive(app)) {
                return "✅ Mode économie activé et vérifié par Android. L'activité en arrière-plan et certaines synchronisations peuvent être réduites."
            }
            if (economieActive(app)) return "✅ Mode économie actif selon Android."
        }

        val ouverture = ouvrirSelecteur(app)
        if (ouverture.startsWith("Impossible")) return ouverture

        val cible = when (mode) {
            "economie" -> "Économiseur de batterie"
            "equilibre" -> "Équilibré"
            else -> "Mode performance"
        }
        if (PiloteEcran.disponible(app)) {
            val objectif = when (mode) {
                "economie" -> "Dans le sélecteur des modes de batterie Xiaomi/HyperOS, active « Économiseur de batterie » ou « Économie de batterie ». Si un choix doit d'abord être ouvert, ouvre « Mode actuel », puis sélectionne l'option exacte. Termine uniquement lorsque l'écran montre que l'option est sélectionnée."
                "equilibre" -> "Dans le sélecteur des modes de batterie Xiaomi/HyperOS, sélectionne « Équilibré » (ou « Balanced »). Si nécessaire, ouvre d'abord « Mode actuel ». Ne choisis pas Performance ni Économiseur. Termine uniquement lorsque l'écran montre que le mode équilibré est sélectionné."
                else -> "Dans le sélecteur des modes de batterie Xiaomi/HyperOS, sélectionne « Mode performance » (ou « Performance mode »). Si nécessaire, ouvre d'abord « Mode actuel ». Termine uniquement lorsque l'écran montre que le mode Performance est sélectionné."
            }
            val resultat = PiloteEcran.piloter(app, objectif)
            val eco = economieActive(app)
            return when {
                resultat.contains("termin", true) && mode == "economie" && eco ->
                    "✅ Mode économie activé et vérifié par Android. $resultat"
                resultat.contains("termin", true) && mode != "economie" && !eco ->
                    "✅ Sélection du mode « $cible » effectuée dans HyperOS. L'état Battery Saver Android est désactivé. $resultat"
                else -> "Sélecteur ouvert, mais le changement n'a pas pu être confirmé automatiquement. $resultat"
            }
        }

        return when (mode) {
            "economie" -> "Le sélecteur batterie est ouvert. Pour activer l'économie, touche « Économiseur de batterie ». Pour le pilotage vocal automatique, active l'accessibilité JARVIS et configure une clé Gemini."
            "equilibre" -> "Le sélecteur batterie est ouvert. Choisis « Équilibré ». Android ne permet pas à une application ordinaire de forcer directement ce mode Xiaomi sans passer par l'interface HyperOS."
            else -> "Le sélecteur batterie est ouvert. Choisis « Mode performance ». Ce mode peut augmenter la consommation et la température du téléphone."
        }
    }
}
