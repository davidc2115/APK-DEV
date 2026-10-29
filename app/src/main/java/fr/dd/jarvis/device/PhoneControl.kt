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

    private fun adbOk() = PhoneShell.ready(ctx) || AdbShell.hasKey(ctx)
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun findPackage(name: String): Pair<String, String>? {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val q = name.trim().lowercase()
        val m = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: apps.firstOrNull { it.activityInfo.packageName.lowercase() == q }
            ?: return null
        return m.activityInfo.packageName to m.loadLabel(pm).toString()
    }

    private fun installed(pkg: String) = try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    /** Lance une activité même depuis l'arrière-plan : via ADB (am start) si disponible, sinon par intent classique. */
    fun start(action: String?, data: String? = null, pkg: String? = null, extras: Map<String, String> = emptyMap()): String {
        if (adbOk()) {
            val cmd = buildString {
                append("am start -f 0x10000000")
                if (action != null) append(" -a ").append(action)
                if (data != null) append(" -d ").append(q(data))
                if (pkg != null) append(" -p ").append(pkg)
                extras.forEach { (k, v) -> append(" --es ").append(k).append(' ').append(q(v)) }
            }
            val r = PhoneShell.exec(ctx, cmd)
            if (!r.contains("Error", true) && !r.contains("Exception", true) && !r.startsWith("ADB non") && !r.startsWith("Erreur ADB")) return ""
        }
        return try {
            val i = Intent(action ?: Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (data != null) i.data = Uri.parse(data)
            if (pkg != null) i.setPackage(pkg)
            extras.forEach { (k, v) -> i.putExtra(k, v) }
            ctx.startActivity(i); ""
        } catch (e: Exception) { "Lancement impossible : ${e.message}. (Pour lancer des applis quand JARVIS est en arrière-plan : appairer l'ADB intégré ou autoriser l'affichage par-dessus les autres applis.)" }
    }

    fun openApp(name: String): String {
        val (pkg, label) = findPackage(name) ?: return "Application « $name » introuvable."
        if (adbOk()) {
            val r = PhoneShell.exec(ctx, "monkey -p $pkg -c android.intent.category.LAUNCHER 1")
            if (r.contains("Events injected", true) || r.contains("(commande exécutée)")) return "Ouverture de $label."
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return "Impossible de lancer $label."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(launch); "Ouverture de $label." } catch (e: Exception) { "Impossible de lancer $label : ${e.message}" }
    }

    /** Recherche et lecture d'un titre / artiste dans l'application de musique choisie (Spotify, YouTube Music, Deezer…). */
    fun playMusic(requete: String, appli: String): String {
        if (requete.isBlank()) return "Que veux-tu écouter ?"
        val known = mapOf(
            "spotify" to "com.spotify.music", "youtube music" to "com.google.android.apps.youtube.music", "ytmusic" to "com.google.android.apps.youtube.music",
            "deezer" to "deezer.android.app", "amazon music" to "com.amazon.mp3", "apple music" to "com.apple.android.music", "vlc" to "org.videolan.vlc"
        )
        val a = appli.trim().lowercase()
        var pkg: String? = if (a.isBlank()) null else known.entries.firstOrNull { a.contains(it.key) }?.value?.takeIf { installed(it) } ?: findPackage(a)?.first
        if (a.isBlank()) pkg = known.values.firstOrNull { installed(it) }
        if (a.contains("youtube") && !a.contains("music")) {
            val r = start(Intent.ACTION_VIEW, "https://www.youtube.com/results?search_query=" + Uri.encode(requete), "com.google.android.youtube")
            return r.ifBlank { "Recherche « $requete » ouverte dans YouTube." }
        }
        val r = start("android.media.action.MEDIA_PLAY_FROM_SEARCH", null, pkg,
            mapOf("query" to requete, "android.intent.extra.focus" to "vnd.android.cursor.item/*"))
        return r.ifBlank { "🎵 Lecture de « $requete »" + (pkg?.let { " dans " + (findPackage(it)?.second ?: it) } ?: "") + " lancée." }
    }

    fun mediaKey(action: String): String {
        val code = when (action.lowercase().replace("-", "_")) {
            "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            "next", "suivant" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT; "previous", "precedent", "précédent" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP; else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        return "⏯️ Commande média « $action » envoyée."
    }

    /** Lance la navigation Google Maps vers une destination (d = voiture, w = marche, b = vélo, l = deux-roues). */
    fun navigate(dest: String, mode: String): String {
        val m = when (mode.trim().lowercase()) { "marche", "a pied", "à pied", "walking", "w" -> "w"; "velo", "vélo", "bicycling", "b" -> "b"; else -> "d" }
        val r = start(Intent.ACTION_VIEW, "google.navigation:q=" + Uri.encode(dest) + "&mode=$m", "com.google.android.apps.maps")
        return r.ifBlank { "🧭 Navigation vers $dest lancée." }
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


    private fun isError(r: String) = r.startsWith("Erreur ADB") || r.startsWith("ADB non connecté") || r.startsWith("Aucun accès")

    /** Exécute une commande shell (ADB intégré ou Shizuku) hors du thread appelant. */
    private fun sh(cmd: String): String {
        if (!PhoneShell.ready(ctx) && !AdbShell.hasKey(ctx)) return "Aucun accès adb : appairer l'ADB intégré dans Réglages › Contrôle du téléphone."
        var out = ""
        val t = Thread { out = PhoneShell.exec(ctx, cmd) }
        t.start(); t.join(25_000)
        return out
    }

    private fun stateOn(key: String): Boolean? {
        val r = when (key) {
            "mobile_data" -> sh("settings get global mobile_data")
            else -> sh("settings get global $key")
        }.trim()
        return when (r) { "1" -> true; "0" -> false; else -> null }
    }

    /** Essaie chaque commande, puis vérifie l'état réel avant de répondre. */
    private fun toggle(label: String, on: Boolean, key: String, cmds: List<String>): String {
        val errors = mutableListOf<String>()
        for (c in cmds) {
            val r = sh(c)
            if (isError(r)) return "Échec $label : $r"
            Thread.sleep(1200)
            val st = stateOn(key)
            if (st == null || st == on) {
                return if (st == on) "$label ${if (on) "activé" else "désactivé"}." else "$label : commande envoyée (état non vérifiable)."
            }
            errors += "$c → $r"
        }
        return "$label n'a pas changé d'état malgré ${cmds.size} commande(s) : ${errors.joinToString(" | ")}"
    }

    fun perform(action: String, value: String): String = try {
        when (action) {
            "torch_on" -> torch(true)
            "torch_off" -> torch(false)
            "open_app" -> openApp(value)
            "open_url" -> openUrl(value)
            "volume" -> volume(value.filter { it.isDigit() }.toIntOrNull() ?: 50)
            "wifi_on" -> toggle("Wi-Fi", true, "wifi_on", listOf("cmd wifi set-wifi-enabled enabled", "svc wifi enable"))
            "wifi_off" -> toggle("Wi-Fi", false, "wifi_on", listOf("cmd wifi set-wifi-enabled disabled", "svc wifi disable"))
            "bluetooth_on" -> toggle("Bluetooth", true, "bluetooth_on", listOf("cmd bluetooth_manager enable", "svc bluetooth enable"))
            "bluetooth_off" -> toggle("Bluetooth", false, "bluetooth_on", listOf("cmd bluetooth_manager disable", "svc bluetooth disable"))
            "data_on" -> toggle("Données mobiles", true, "mobile_data", listOf("svc data enable"))
            "data_off" -> toggle("Données mobiles", false, "mobile_data", listOf("svc data disable"))
            "brightness" -> {
                val p = (value.filter { it.isDigit() }.toIntOrNull() ?: 50).coerceIn(0, 100)
                val r = sh("settings put system screen_brightness_mode 0; settings put system screen_brightness ${p * 255 / 100}")
                if (isError(r)) "Échec luminosité : $r" else "Luminosité réglée à $p %."
            }
            "call" -> {
                val r = sh("am start -a android.intent.action.CALL -d tel:${Uri.encode(value)}")
                if (isError(r) || r.contains("Error", true) || r.contains("Exception", true)) "Échec de l'appel : $r" else "Appel de $value lancé."
            }
            "music" -> playMusic(value.substringBefore('|'), value.substringAfter('|', ""))
            "media" -> mediaKey(value)
            "navigate" -> navigate(value, "")
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
        val SENSITIVE = setOf("call", "click_text", "type_text", "tap", "wifi_off", "bluetooth_off", "data_off", "lock")
    }
}
