package fr.dd.jarvis.device

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import fr.dd.jarvis.service.JarvisAccessibilityService

/** Actions sur le téléphone : API Android quand c'est possible, accessibilité ou Shizuku sinon. */
class PhoneControl(private val ctx: Context) {

    fun accessibilityEnabled(): Boolean = JarvisAccessibilityService.instance != null

    fun accessibilityInSettings(): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains(ctx.packageName)
    }

    private fun needA11y(): String = "Le service d'accessibilité JARVIS n'est pas actif (Réglages › Téléphone)."

    fun torch(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Pas de flash disponible."
        cm.setTorchMode(id, on)
        return if (on) "Lampe allumée." else "Lampe éteinte."
    }

    fun openApp(name: String): String {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val q = name.trim().lowercase()
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: apps.firstOrNull { it.activityInfo.packageName.lowercase() == q }
            ?: return "Application « $name » introuvable."
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return "Impossible de lancer ${match.loadLabel(pm)}."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(launch)
        return "Ouverture de ${match.loadLabel(pm)}."
    }

    fun openUrl(url: String): String {
        val u = if (url.startsWith("http") || url.startsWith("tel:") || url.startsWith("geo:") || url.startsWith("mailto:")) url else "https://$url"
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Ouverture de $u."
    }

    /** Volume multimédia, en pourcentage (0–100). */
    fun volume(percent: Int): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (percent.coerceIn(0, 100) * max / 100)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        return "Volume réglé à ${percent.coerceIn(0, 100)} %."
    }

    private fun a11y(action: Int, okText: String): String {
        val s = JarvisAccessibilityService.instance ?: return needA11y()
        return if (s.global(action)) okText else "Action impossible."
    }

    fun perform(action: String, value: String): String = try {
        when (action) {
            "torch_on" -> torch(true)
            "torch_off" -> torch(false)
            "open_app" -> openApp(value)
            "open_url" -> openUrl(value)
            "volume" -> volume(value.filter { it.isDigit() }.toIntOrNull() ?: 50)
            "wifi_on" -> ShizukuShell.exec("svc wifi enable").let { "Wi-Fi activé. $it" }
            "wifi_off" -> ShizukuShell.exec("svc wifi disable").let { "Wi-Fi désactivé. $it" }
            "bluetooth_on" -> ShizukuShell.exec("svc bluetooth enable").let { "Bluetooth activé. $it" }
            "bluetooth_off" -> ShizukuShell.exec("svc bluetooth disable").let { "Bluetooth désactivé. $it" }
            "brightness" -> {
                val p = (value.filter { it.isDigit() }.toIntOrNull() ?: 50).coerceIn(0, 100)
                ShizukuShell.exec("settings put system screen_brightness_mode 0; settings put system screen_brightness ${p * 255 / 100}")
                "Luminosité réglée à $p %."
            }
            "call" -> ShizukuShell.exec("am start -a android.intent.action.CALL -d tel:${Uri.encode(value)}").let { "Appel de $value lancé. $it" }
            "back" -> a11y(AccessibilityService.GLOBAL_ACTION_BACK, "Retour.")
            "home" -> a11y(AccessibilityService.GLOBAL_ACTION_HOME, "Accueil.")
            "recents" -> a11y(AccessibilityService.GLOBAL_ACTION_RECENTS, "Applications récentes.")
            "notifications" -> a11y(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications ouvertes.")
            "quick_settings" -> a11y(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Réglages rapides ouverts.")
            "lock" -> if (Build.VERSION.SDK_INT >= 28) a11y(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Écran verrouillé.") else "Non supporté."
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) a11y(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Capture d'écran prise.") else "Non supporté."
            "read_screen" -> JarvisAccessibilityService.instance?.readScreen()?.ifBlank { "(écran vide ou illisible)" } ?: needA11y()
            "click_text" -> JarvisAccessibilityService.instance?.let { if (it.clickText(value)) "Clic sur « $value »." else "« $value » introuvable à l'écran." } ?: needA11y()
            "type_text" -> JarvisAccessibilityService.instance?.let { if (it.typeText(value)) "Texte saisi." else "Aucun champ de saisie actif." } ?: needA11y()
            "scroll_down" -> JarvisAccessibilityService.instance?.let { if (it.scroll(true)) "Défilement vers le bas." else "Échec." } ?: needA11y()
            "scroll_up" -> JarvisAccessibilityService.instance?.let { if (it.scroll(false)) "Défilement vers le haut." else "Échec." } ?: needA11y()
            "tap" -> {
                val xy = value.split(",", " ", ";").mapNotNull { it.trim().toFloatOrNull() }
                if (xy.size < 2) "Format attendu : x,y (pixels)."
                else JarvisAccessibilityService.instance?.let { if (it.tap(xy[0], xy[1])) "Appui effectué." else "Échec." } ?: needA11y()
            }
            else -> "Action inconnue : $action"
        }
    } catch (e: Exception) {
        "Erreur : ${e.message}"
    }

    companion object {
        /** Actions qui demandent une confirmation explicite avant exécution. */
        val SENSITIVE = setOf("call", "click_text", "type_text", "tap", "wifi_off", "bluetooth_off", "lock")
    }
}
