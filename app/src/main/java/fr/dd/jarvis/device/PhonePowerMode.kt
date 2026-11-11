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

    /**
     * Sélection déterministe via l'arbre d'accessibilité Android : aucun appel Gemini,
     * aucune estimation de coordonnées. Le sélecteur Xiaomi expose normalement « Mode actuel »
     * puis les choix Équilibré / Performance / Économiseur.
     */
    private suspend fun choisirModeParAccessibilite(mode: String): String? = withContext(Dispatchers.IO) {
        delay(500) // laisse le temps au sélecteur HyperOS de devenir la fenêtre active
        val service = fr.dd.jarvis.service.JarvisAccessibilityService.instance ?: return@withContext null
        // Réservé aux modes Xiaomi Équilibré/Performance. Économie est exclue.
        if (mode == "economie") return@withContext null
        val choix = if (mode == "equilibre") listOf("Équilibré", "Balanced")
            else listOf("Mode performance", "Performance mode", "Performance")
        val autres = listOf("Équilibré", "Balanced", "Mode performance", "Performance mode", "Performance")
        val ouvre = service.clickText("Mode actuel") || service.clickText("Current mode")
        if (ouvre) delay(350)
        var clique = choix.any { service.clickText(it) }
        if (!clique && !ouvre) {
            // Certains firmwares affichent directement les choix sur la page Batterie.
            clique = choix.any { service.clickText(it) }
        }
        if (!clique) return@withContext null
        delay(650)
        val ecran = service.inspectScreen().lowercase(java.util.Locale.ROOT)
        val cibleVisible = choix.any { ecran.contains(it.lowercase(java.util.Locale.ROOT)) }
        val alternativesVisibles = autres.count { libelle ->
            ecran.contains(libelle.lowercase(java.util.Locale.ROOT)) &&
                choix.none { cible -> cible.equals(libelle, ignoreCase = true) }
        }
        if (cibleVisible && alternativesVisibles == 0) {
            "✅ Mode « ${choix.first()} » sélectionné directement par l'accessibilité HyperOS."
        } else {
            "Sélection envoyée par l'accessibilité, mais l'état final n'est pas suffisamment identifiable à l'écran."
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

        // L'économie Android est appliquée directement par le service shell ADB/Shizuku.
        // Aucun écran HyperOS, accessibilité, coordonnées ou pilote Gemini ne doit être utilisé ici.
        if (mode == "economie") {
            if (economieActive(app)) return "✅ Mode économie déjà actif et confirmé par Android."
            if (!PhoneShell.ready(app)) {
                return "Activation directe impossible : Shizuku doit être démarré et autorisé, ou ADB sans fil connecté. Aucun écran n'a été ouvert et aucun pilotage du téléphone n'a été lancé."
            }
            val shell = withContext(Dispatchers.IO) {
                PhoneShell.exec(app, "cmd power set-mode 1")
            }
            delay(700)
            if (economieActive(app)) {
                return "✅ Mode économie activé directement par ADB/Shizuku et confirmé par PowerManager Android. Certaines activités en arrière-plan et synchronisations peuvent être réduites."
            }
            val detail = shell.take(220).ifBlank { "La commande n'a pas renvoyé de détail." }
            // IMPORTANT : ne jamais poursuivre vers HyperOS/PiloteEcran pour l'économie.
            return "❌ Android ne confirme pas l'activation du mode économie. Réponse ADB/Shizuku : $detail. Aucun écran n'a été ouvert et aucun pilotage du téléphone n'a été lancé."
        }

        // Seuls les modes Xiaomi propriétaires Équilibré/Performance utilisent le sélecteur.
        val ouverture = ouvrirSelecteur(app)
        if (ouverture.startsWith("Impossible")) return ouverture

        val cible = if (mode == "equilibre") "Équilibré" else "Mode performance"
        // Ce bloc n'est accessible que pour Équilibré/Performance, jamais pour Économie.
        val direct = choisirModeParAccessibilite(mode)
        if (direct != null && direct.startsWith("✅")) return direct

        if (PiloteEcran.disponible(app)) {
            val objectif = if (mode == "equilibre")
                "Dans le sélecteur des modes de batterie Xiaomi/HyperOS, sélectionne « Équilibré » (ou « Balanced »). Si nécessaire, ouvre d'abord « Mode actuel ». Ne choisis pas Performance ni Économiseur."
            else
                "Dans le sélecteur des modes de batterie Xiaomi/HyperOS, sélectionne « Mode performance » (ou « Performance mode »). Si nécessaire, ouvre d'abord « Mode actuel ». Termine uniquement lorsque le mode Performance est sélectionné."
            val resultat = PiloteEcran.piloter(app, objectif)
            val eco = economieActive(app)
            return when {
                resultat.contains("termin", true) && !eco ->
                    "✅ Sélection du mode « $cible » effectuée dans HyperOS. L'état Battery Saver Android est désactivé. $resultat"
                else -> "Sélecteur ouvert, mais le changement n'a pas pu être confirmé automatiquement. $resultat"
            }
        }

        return if (mode == "equilibre")
            "Le sélecteur batterie est ouvert. Choisis « Équilibré ». Android ne permet pas à une application ordinaire de forcer directement ce mode Xiaomi sans passer par l'interface HyperOS."
        else
            "Le sélecteur batterie est ouvert. Choisis « Mode performance ». Ce mode peut augmenter la consommation et la température du téléphone."
    }
}
