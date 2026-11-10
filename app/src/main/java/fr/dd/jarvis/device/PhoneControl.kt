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

    private fun needA11y(): String = "Le service d'accessibilité JARVIS n'est pas actif : va dans Réglages › Général › Autorisations pour le réactiver (désactive puis réactive l'interrupteur dans Réglages Android › Accessibilité, et sur Xiaomi/MIUI vérifie aussi « Démarrage automatique » et la batterie sans restriction pour JARVIS)."

    /** Force Android à rompre puis recréer la connexion aux services système (accès notifications, accessibilité) :
     *  bascule le composant « désactivé » puis « activé », ce qui force une vraie reconnexion même quand
     *  l'interrupteur des réglages Android semble actif mais que JARVIS reste indiqué comme déconnecté
     *  (bug connu sur MIUI/Xiaomi : couper/remettre l'interrupteur système ne suffit pas toujours, et le
     *  bouton « Accès notifications » de JARVIS se désactive dès qu'Android dit la permission accordée,
     *  même si le service lui n'a jamais rebind). N'arrête pas l'appli (DONT_KILL_APP).
     */
    fun forcerReconnexionServices(): String {
        val pm = ctx.packageManager
        var touche = false
        fun bascule(cls: Class<*>) = runCatching {
            val cn = android.content.ComponentName(ctx, cls)
            pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
            Thread.sleep(400)
            pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
            touche = true
        }
        bascule(fr.dd.jarvis.service.JarvisNotificationListener::class.java)
        bascule(fr.dd.jarvis.service.JarvisAccessibilityService::class.java)
        if (Build.VERSION.SDK_INT >= 24) runCatching {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(ctx, fr.dd.jarvis.service.JarvisNotificationListener::class.java))
        }
        return if (touche)
            "🔄 Reconnexion forcée envoyée à Android. Patiente 10-15 secondes puis appuie sur « Actualiser ». Si ça reste rouge : ferme complètement JARVIS depuis les applications récentes (pas juste l'écran) et rouvre-la — et sur Xiaomi/MIUI, vérifie dans l'appli Sécurité que « Démarrage automatique » est activé pour JARVIS et qu'elle n'est pas verrouillée/fermée automatiquement dans les apps récentes, sinon le système la tue en arrière-plan et elle ne peut plus se reconnecter tant qu'on ne la rouvre pas."
        else "❌ Impossible de forcer la reconnexion sur ce téléphone."
    }

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

    /** Inventaire réel des applications lançables, sans supposer qu'une application connue est installée. */
    fun installedApps(): String {
        val pm = ctx.packageManager
        val apps = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .distinctBy { it.activityInfo.packageName }
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString().ifBlank { it.activityInfo.packageName } }
                .sortedBy { it.second.lowercase(java.util.Locale.ROOT) }
        }.getOrDefault(emptyList())
        if (apps.isEmpty()) return "Aucune application lançable détectée."
        val shown = apps.take(100).joinToString("\n") { (pkg, label) -> "• $label ($pkg)" }
        val extra = if (apps.size > 100) "\n… et ${apps.size - 100} autre(s)." else ""
        return "Applications installées et lançables (${apps.size}) :\n$shown$extra"
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

    // ---------------------------------------------------------------- Horloge native (alarme, minuteur, chronomètre)

    /** Lance un intent de l'horloge sans afficher son interface (EXTRA_SKIP_UI) ; en arrière-plan via ADB si possible. */
    private fun horloge(action: String, ints: Map<String, Int> = emptyMap(), strs: Map<String, String> = emptyMap(),
                        bools: Map<String, Boolean> = emptyMap(), ints2: Map<String, ArrayList<Int>> = emptyMap()): Boolean {
        if (adbOk()) {
            val cmd = buildString {
                append("am start -f 0x10000000 -a ").append(action)
                ints.forEach { (k, v) -> append(" --ei ").append(k).append(' ').append(v) }
                strs.forEach { (k, v) -> append(" --es ").append(k).append(' ').append(q(v)) }
                bools.forEach { (k, v) -> append(" --ez ").append(k).append(' ').append(v) }
                ints2.forEach { (k, v) -> append(" --eial ").append(k).append(' ').append(v.joinToString(",")) }
            }
            val r = PhoneShell.exec(ctx, cmd)
            if (!r.contains("Error", true) && !r.contains("Exception", true) && !r.startsWith("ADB non") && !r.startsWith("Erreur ADB")) return true
        }
        return try {
            val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ints.forEach { (k, v) -> i.putExtra(k, v) }
            strs.forEach { (k, v) -> i.putExtra(k, v) }
            bools.forEach { (k, v) -> i.putExtra(k, v) }
            ints2.forEach { (k, v) -> i.putIntegerArrayListExtra(k, v) }
            ctx.startActivity(i); true
        } catch (e: Exception) { false }
    }

    /** Crée une alarme dans l'horloge du téléphone. [jours] : lun, mar, mer, jeu, ven, sam, dim (vide = une seule fois). */
    fun creerAlarme(heure: String, libelle: String, jours: String): String {
        val m = Regex("(\\d{1,2})\\s*(?:[:hH]\\s*(\\d{1,2})?)?").find(heure.trim()) ?: return "Heure d'alarme non comprise : « $heure »."
        val h = m.groupValues[1].toInt(); val mn = m.groupValues[2].toIntOrNull() ?: 0
        if (h !in 0..23 || mn !in 0..59) return "Heure invalide : $heure."
        val map = mapOf("dim" to 1, "lun" to 2, "mar" to 3, "mer" to 4, "jeu" to 5, "ven" to 6, "sam" to 7)
        val days = arrayListOf<Int>()
        val low = jours.lowercase()
        if (low.contains("tous") || low.contains("quotidien")) days.addAll(1..7)
        else if (low.contains("semaine") || low.contains("ouvr")) days.addAll(2..6)
        else map.forEach { (k, v) -> if (low.contains(k)) days.add(v) }
        val ok = horloge(
            android.provider.AlarmClock.ACTION_SET_ALARM,
            ints = mapOf(android.provider.AlarmClock.EXTRA_HOUR to h, android.provider.AlarmClock.EXTRA_MINUTES to mn),
            strs = if (libelle.isNotBlank()) mapOf(android.provider.AlarmClock.EXTRA_MESSAGE to libelle) else emptyMap(),
            bools = mapOf(android.provider.AlarmClock.EXTRA_SKIP_UI to true),
            ints2 = if (days.isNotEmpty()) mapOf(android.provider.AlarmClock.EXTRA_DAYS to days) else emptyMap()
        )
        val quand = "%02d:%02d".format(h, mn) + if (days.isNotEmpty()) " (" + jours.trim() + ")" else ""
        return if (ok) "⏰ Alarme réglée à $quand" + (if (libelle.isNotBlank()) " — $libelle" else "") + "." else "Impossible de régler l'alarme (application Horloge indisponible)."
    }

    /** Lance un minuteur dans l'horloge du téléphone. */
    fun creerMinuteur(secondes: Int, libelle: String): String {
        if (secondes <= 0) return "Durée du minuteur invalide."
        val ok = horloge(
            android.provider.AlarmClock.ACTION_SET_TIMER,
            ints = mapOf(android.provider.AlarmClock.EXTRA_LENGTH to secondes),
            strs = if (libelle.isNotBlank()) mapOf(android.provider.AlarmClock.EXTRA_MESSAGE to libelle) else emptyMap(),
            bools = mapOf(android.provider.AlarmClock.EXTRA_SKIP_UI to true)
        )
        val h = secondes / 3600; val mi = secondes % 3600 / 60; val se = secondes % 60
        val txt = listOfNotNull(if (h > 0) "$h h" else null, if (mi > 0) "$mi min" else null, if (se > 0) "$se s" else null).joinToString(" ")
        return if (ok) "⏳ Minuteur de $txt lancé." + (if (libelle.isNotBlank()) " ($libelle)" else "") else "Impossible de lancer le minuteur."
    }

    /**
     * Demande à l'application Horloge de supprimer une alarme.
     * Android ne fournit ni liste ni API d'édition universelle : la suppression dépend donc du support
     * de ACTION_DISMISS_ALARM par l'Horloge installée. Cette action ne requiert pas SCHEDULE_EXACT_ALARM.
     */
    fun supprimerAlarme(heure: String, libelle: String): String {
        val m = Regex("(\\d{1,2})\\s*[:hH]\\s*(\\d{0,2})").find(heure)
        if (m == null && libelle.isBlank()) return "Précise l'heure HH:MM ou le libellé exact de l'alarme à supprimer."
        val intent = Intent(android.provider.AlarmClock.ACTION_DISMISS_ALARM)
        if (m != null) {
            intent.putExtra(android.provider.AlarmClock.EXTRA_HOUR, m.groupValues[1].toInt())
            intent.putExtra(android.provider.AlarmClock.EXTRA_MINUTES, m.groupValues[2].toIntOrNull() ?: 0)
            intent.putExtra(android.provider.AlarmClock.EXTRA_ALARM_SEARCH_MODE, android.provider.AlarmClock.ALARM_SEARCH_MODE_TIME)
        } else {
            intent.putExtra(android.provider.AlarmClock.EXTRA_ALARM_SEARCH_MODE, android.provider.AlarmClock.ALARM_SEARCH_MODE_LABEL)
            intent.putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, libelle)
        }
        val handler = runCatching {
            ctx.packageManager.resolveActivity(intent, 0)
        }.getOrNull()
        if (handler == null) {
            val ouvert = horloge("android.intent.action.SHOW_ALARMS")
            return if (ouvert)
                "L'Horloge installée ne déclare pas la suppression directe des alarmes. J'ai ouvert sa liste : supprime l'alarme manuellement. Ce n'est pas un problème d'autorisation Android."
            else "Aucune application Horloge ne prend en charge la suppression directe et je n'ai pas pu ouvrir la liste des alarmes."
        }
        val ok = horloge(android.provider.AlarmClock.ACTION_DISMISS_ALARM,
            ints = if (m != null) mapOf(android.provider.AlarmClock.EXTRA_HOUR to m.groupValues[1].toInt(),
                android.provider.AlarmClock.EXTRA_MINUTES to (m.groupValues[2].toIntOrNull() ?: 0)) else emptyMap(),
            strs = if (m != null) mapOf(android.provider.AlarmClock.EXTRA_ALARM_SEARCH_MODE to android.provider.AlarmClock.ALARM_SEARCH_MODE_TIME)
                else mapOf(android.provider.AlarmClock.EXTRA_ALARM_SEARCH_MODE to android.provider.AlarmClock.ALARM_SEARCH_MODE_LABEL,
                    android.provider.AlarmClock.EXTRA_MESSAGE to libelle))
        return if (ok) "Demande de suppression de l'alarme envoyée à l'Horloge. Vérifie qu'elle a bien disparu : Android ne confirme pas universellement la suppression."
        else {
            val ouvert = horloge("android.intent.action.SHOW_ALARMS")
            if (ouvert) "L'Horloge a refusé la suppression directe. Sa liste est ouverte pour une suppression manuelle."
            else "Impossible d'envoyer la suppression à l'Horloge."
        }
    }

    /** Chronomètre : Android n'offre pas d'API publique, on ouvre l'onglet chronomètre de l'horloge. */
    fun chronometre(): String {
        val ok = horloge("android.intent.action.SHOW_ALARMS", ints = mapOf("deskclock.select.tab" to 2)) 
        return if (ok) "⏱️ Chronomètre ouvert (le démarrage automatique n'est pas possible : Android ne l'expose pas)." else "Impossible d'ouvrir le chronomètre."
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

    /** Recherche et lecture d'un titre / artiste dans l'application de musique choisie (Spotify, YouTube Music, Deezer…) ; pilote la session média en arrière-plan, sans forcément ouvrir l'appli à l'écran. */
    fun playMusic(requete: String, appli: String): String {
        if (requete.isBlank()) return "Que veux-tu écouter ?"
        val known = mapOf(
            "spotify" to "com.spotify.music", "youtube music" to "com.google.android.apps.youtube.music", "ytmusic" to "com.google.android.apps.youtube.music",
            "morphe" to "app.morphe.android.apps.youtube.music", "youtube music morphe" to "app.morphe.android.apps.youtube.music",
            "deezer" to "deezer.android.app", "amazon music" to "com.amazon.mp3", "apple music" to "com.apple.android.music", "vlc" to "org.videolan.vlc"
        )
        var a = appli.trim().lowercase()
        // YT Music Morphe installée : « YouTube Music », « YT Music », « Morphe » ou l'appli officielle mémorisée = Morphe,
        // jamais l'appli YouTube Music officielle.
        if (installed(Musique.MORPHE_PACKAGE) && (a.contains("morphe") || a.contains("youtube music") || a.contains("yt music") ||
                a.contains("ytmusic") || a == YtMusique.YTM_OFFICIEL)) a = Musique.MORPHE_PACKAGE
        var pkg: String? = when {
            a.isBlank() -> null
            a == Musique.MORPHE_PACKAGE -> a
            installed(appli.trim()) -> appli.trim()   // déjà un nom de paquet exact (préférence mémorisée, résolue une fois pour toutes)
            else -> known.entries.firstOrNull { a == it.key }?.value?.takeIf { installed(it) }   // correspondance exacte d'abord
                ?: known.entries.firstOrNull { a.contains(it.key) }?.value?.takeIf { installed(it) }
                ?: findPackage(a)?.first
        }
        // Rien n'a été précisé du tout (ni demandé ni mémorisé) : seulement là on choisit la première appli de musique installée.
        if (pkg == null && a.isBlank()) pkg = musicApps().values.firstOrNull() ?: known.values.firstOrNull { installed(it) }
        if (a.contains("youtube") && !a.contains("music") && a.isNotBlank() && pkg == null) {
            val r = start(Intent.ACTION_VIEW, "https://www.youtube.com/results?search_query=" + Uri.encode(requete), "com.google.android.youtube")
            return r.ifBlank { "Recherche « $requete » ouverte dans YouTube." }
        }
        // Une appli précise a été demandée/mémorisée mais n'est pas installée : le dire, jamais en lancer une autre à la place.
        if (pkg == null && a.isNotBlank()) return "« $appli » n'est pas installée ou introuvable sur ce téléphone. Applis de musique installées : ${musicApps().keys.joinToString(", ").ifBlank { "aucune détectée" }}."
        val nom = if (pkg == Musique.MORPHE_PACKAGE) YtMusique.NOM else pkg?.let { findPackage(it)?.second ?: musicApps().entries.firstOrNull { e -> e.value == it }?.key ?: it } ?: "l'application de musique"
        if (pkg == null) return "Aucune application de musique installée (Spotify, YouTube Music, Deezer…) : installe-en une, ou précise laquelle utiliser."
        // Pas la peine d'essayer de piloter la session média si la permission qui le permet n'est pas accordée :
        // c'est un « accès spécial », pas une autorisation listée dans la page Autorisations classique de l'appli.
        val accordee = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        if (!accordee) {
            return "⚠️ Pour lancer vraiment la lecture j'ai besoin de l'accès spécial « Accès aux notifications ». Il n'est PAS dans la page « Autorisations » classique de l'appli : ouvre JARVIS → Réglages → section Autorisations → bouton « Accès notifications », ou Réglages Android → Notifications → Accès aux notifications → JARVIS → Activer. Redemande ensuite « lance la lecture »."
        }
        if (!fr.dd.jarvis.service.JarvisNotificationListener.active) {
            return "⚠️ L'accès aux notifications est activé dans Android mais pas encore connecté à JARVIS. Ferme complètement JARVIS (pas juste l'écran — depuis les applications récentes) puis rouvre-la, et redemande « lance la lecture »."
        }
        // Ordre (corrigé) :
        //  1) l'appli tourne déjà : on demande le titre à SA session média (playFromSearch), sans rien ouvrir ;
        //  2) sinon (ou si elle ignore la demande) : intent standard « joue depuis une recherche vocale » (celui de
        //     « Dis Google, joue… », avec le focus « recherche libre ») qui démarre l'appli AVEC la requête ;
        //  3) si l'appli a seulement repris l'ancien morceau, on renvoie la recherche à sa session.
        // On ne confirme que si le morceau qui joue correspond à la demande (ou a changé), et on n'appuie JAMAIS sur
        // « lecture » tant que le titre n'a pas changé : c'est ce qui relançait la musique déjà en cours au lieu de la demandée.
        // Morphe / YouTube Music : JARVIS cherche lui-même dans le catalogue et fait lire LE bon résultat par son lien
        // (rapide et exact, sans piloter l'écran). En cas d'échec (pas de réseau, aucun résultat), méthode classique.
        if (YtMusique.estYtm(pkg)) jouerYtm(pkg, requete, YtMusique.NOM)?.let { return it }
        val sessionAvant = session(pkg)
        val titreAvant = sessionAvant?.let { titreDe(it) }
        var etat = "aucune"
        if (sessionAvant != null) etat = demanderEtVerifier(sessionAvant, requete, titreAvant)
        if (etat != "lecture") {
            val viaRechercheVocale = demarrerDepuisRechercheVocale(pkg, requete)
            if (!viaRechercheVocale && sessionAvant == null) {
                val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return "Impossible de lancer $nom."
                try { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { return "Impossible de lancer $nom : ${e.message}" }
            }
            val c = if (viaRechercheVocale || sessionAvant == null) attendreSession(pkg, 10) else sessionAvant
            if (c != null) {
                if (viaRechercheVocale) etat = verifier(c, requete, titreAvant, 7)
                if (etat != "lecture") etat = demanderEtVerifier(c, requete, titreDe(c))
            }
        }
        if (etat == "lecture") {
            // Visuel : si l'appli de musique est à l'écran, capture du lecteur jointe à la réponse.
            val visuel = runCatching {
                val svc = fr.dd.jarvis.service.JarvisAccessibilityService.instance
                if (svc?.rootInActiveWindow?.packageName?.toString() == pkg) {
                    Thread.sleep(1200)
                    svc.capture()?.let { b -> PiloteEcran.enregistrer(ctx.applicationContext as fr.dd.jarvis.JarvisApp, b, "musique") }
                } else null
            }.getOrNull()
            val titre = session(pkg)?.let { titreDe(it) }
            val confirmation = "🎵 « $requete » est en cours de lecture dans $nom (en arrière-plan)." +
                (if (!titre.isNullOrBlank()) "\nTitre affiché par le lecteur : $titre" else "") +
                (if (visuel != null) "\n\n![Lecteur](jarvis-img:$visuel)" else "")
            // Lecture confirmée : on remet JARVIS (ou l'appli précédente) au premier plan, l'appli de musique reste en arrière-plan.
            if (fr.dd.jarvis.service.JarvisAccessibilityService.instance != null) runCatching { fr.dd.jarvis.service.JarvisAccessibilityService.instance?.global(AccessibilityService.GLOBAL_ACTION_HOME) }
            return confirmation
        }
        // Beaucoup d'applis tierces (YouTube Music, Spotify, Deezer…) n'implémentent pas vraiment playFromSearch() : la session existe
        // mais ignore la recherche demandée. Plutôt que de laisser l'ancien titre tourner sans rien faire, on ouvre directement l'écran
        // de recherche de l'appli (lien profond) pour que le morceau demandé soit déjà affiché, prêt à lancer.
        val rechercheUrl: ((String) -> String)? = when (pkg) {
            "com.google.android.apps.youtube.music" -> { q -> "https://music.youtube.com/search?q=" + Uri.encode(q) }
            "com.spotify.music" -> { q -> "spotify:search:" + Uri.encode(q) }
            "deezer.android.app" -> { q -> "deezer://search/" + Uri.encode(q) }
            else -> null
        }
        if (rechercheUrl != null) {
            // D'abord en forçant le paquet (ouvre directement dans l'appli) ; si ce paquet précis ne déclare pas ce lien exact
            // (ActivityNotFoundException → start() renvoie une erreur), on retente sans forcer le paquet plutôt que d'abandonner,
            // pour laisser Android résoudre lui-même le lien (navigateur si besoin, mieux que rien).
            var err = start(Intent.ACTION_VIEW, rechercheUrl(requete), pkg)
            if (err.isNotBlank()) err = start(Intent.ACTION_VIEW, rechercheUrl(requete), null)
            if (err.isBlank()) return "🔎 $nom ne confirme pas la lecture à distance de « $requete » : j'ai ouvert sa recherche avec ce titre déjà affiché, il ne reste plus qu'à appuyer sur lecture."
        }
        return when (etat) {
            "session" -> "🎵 J'ai demandé la lecture de « $requete » dans $nom, mais je n'ai pas pu confirmer qu'elle a démarré. Si rien ne joue, ouvre $nom et lance un titre une fois à la main (première utilisation)."
            else -> "⚠️ Je n'ai pas trouvé la session de lecture de $nom (l'appli met parfois quelques secondes, ou doit être connectée/ouverte manuellement une première fois). Réessaie, ou ouvre $nom toi-même une fois."
        }
    }

    /**
     * Tente l'intent STANDARD Android de lecture depuis une recherche vocale (celui utilisé par Google Assistant / Android Auto
     * pour « joue … »), beaucoup plus largement supporté par les applis tierces (y compris les lecteurs locaux/constructeur type
     * VLC) que l'API optionnelle MediaController.playFromSearch(). On vérifie d'abord qu'une activité de [pkg] sait vraiment la
     * gérer (resolveActivity scopé au paquet) avant de la lancer, pour ne pas ouvrir l'appli « à l'aveugle » si elle ne la gère pas.
     */
    private fun demarrerDepuisRechercheVocale(pkg: String, requete: String): Boolean {
        val intent = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").apply {
            putExtra(android.app.SearchManager.QUERY, requete)
            // Recherche libre (titre, artiste, album ou style) : sans ce focus certaines applis se contentent de reprendre la lecture.
            putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val gere = try { ctx.packageManager.resolveActivity(intent, 0) != null } catch (e: Exception) { false }
        if (!gere) return false
        return try { ctx.startActivity(intent); true } catch (e: Exception) { false }
    }

    /**
     * Lecture par le catalogue YouTube Music : recherche, meilleur résultat (titre, album, artiste, playlist selon la
     * demande), ouverture de CE résultat dans l'appli, vérification sur la session média (relance « lecture » si l'appli
     * s'est ouverte en pause). Renvoie null si ça n'a pas pu aboutir (on retombe alors sur la méthode classique).
     */
    fun jouerYtm(pkg: String, requete: String, nomDemande: String, resultat: YtMusique.Resultat? = null, radio: Boolean = false): String? {
        val nom = if (pkg == Musique.MORPHE_PACKAGE) YtMusique.NOM else nomDemande
        val liste = if (resultat != null) YtMusique.derniers else runCatching { YtMusique.rechercher(requete) }.getOrNull() ?: return null
        val choisi = (resultat ?: YtMusique.meilleur(requete, liste) ?: return null).let { YtMusique.completer(it) }
        val avant = session(pkg)?.let { titreDe(it) }
        if (!YtMusique.ouvrir(ctx, pkg, choisi, radio)) return null
        var c: android.media.session.MediaController? = null
        val fin = System.currentTimeMillis() + 9000
        var relance = false
        while (System.currentTimeMillis() < fin) {
            Thread.sleep(600)
            c = session(pkg) ?: continue
            val titre = titreDe(c)
            val change = titre != null && titre != avant
            if (joue(c) && (change || correspond(c, choisi.titre))) break
            // Ouvert sur le bon titre mais en pause : on lance la lecture (une seule fois).
            if (!joue(c) && change && !relance && System.currentTimeMillis() > fin - 6000) { runCatching { c.transportControls.play() }; relance = true }
        }
        val joueBien = c != null && joue(c)
        val autres = liste.filter { it.cle() != choisi.cle() && it.type != YtMusique.Type.AUTRE }.take(4)
        val suite = if (autres.isEmpty()) "" else "\nAutres résultats (dis « mets le 2 », etc.) :\n" +
            autres.mapIndexed { i, r -> "${i + 2}. ${r.ligne()}" }.joinToString("\n")
        YtMusique.retenir(listOf(choisi) + autres)
        if (joueBien && fr.dd.jarvis.service.JarvisAccessibilityService.instance != null) runCatching { fr.dd.jarvis.service.JarvisAccessibilityService.instance?.global(AccessibilityService.GLOBAL_ACTION_HOME) }
        return (if (joueBien) "🎵 En lecture dans $nom : ${choisi.ligne()}" + (c?.let { titreDe(it) }?.takeIf { it.isNotBlank() && !choisi.titre.contains(it, true) }?.let { "\n(lecteur : $it)" } ?: "")
            else "🎵 J'ai ouvert ${choisi.ligne()} dans $nom" + (if (c == null) " (l'appli n'a pas encore confirmé la lecture)." else " ; la lecture n'est pas confirmée — appuie sur lecture si besoin.")) + suite
    }

    /** Titre en cours de lecture dans [pkg] (paquet ou nom d'appli), ou null si rien ne joue. */
    fun lectureEnCours(pkg: String): String? = runCatching {
        val p = if (installed(pkg)) pkg else findPackage(pkg)?.first ?: pkg
        session(p)?.takeIf { joue(it) }?.let { titreDe(it) ?: "lecture en cours" }
    }.getOrNull()

    private fun session(pkg: String): android.media.session.MediaController? {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
        val cn = android.content.ComponentName(ctx, fr.dd.jarvis.service.JarvisNotificationListener::class.java)
        return try { msm.getActiveSessions(cn).firstOrNull { it.packageName == pkg } } catch (e: Exception) { null }
    }

    /** Attend (jusqu'à [secondes]) que l'appli [pkg] crée sa session média après son lancement. */
    private fun attendreSession(pkg: String, secondes: Int): android.media.session.MediaController? {
        for (i in 0 until secondes) { session(pkg)?.let { return it }; Thread.sleep(1000) }
        return session(pkg)
    }

    private fun titreDe(c: android.media.session.MediaController): String? =
        c.metadata?.getText(android.media.MediaMetadata.METADATA_KEY_TITLE)?.toString()

    private fun joue(c: android.media.session.MediaController) = c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING

    private val motsVidesMusique = setOf(
        "musique", "musiques", "chanson", "chansons", "titre", "titres", "morceau", "playlist", "album", "joue", "jouer", "mets", "mettre",
        "lance", "lancer", "ecouter", "the", "les", "des", "une", "par", "sur", "dans", "avec", "pour", "moi", "son", "sons", "feat"
    )

    /** Le morceau en cours correspond-il à la demande ? (mots de la requête retrouvés dans titre / artiste / album) */
    private fun correspond(c: android.media.session.MediaController, requete: String): Boolean {
        val m = c.metadata ?: return false
        fun n(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val meta = n(listOf(android.media.MediaMetadata.METADATA_KEY_TITLE, android.media.MediaMetadata.METADATA_KEY_ARTIST,
            android.media.MediaMetadata.METADATA_KEY_ALBUM, android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE, android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            .mapNotNull { k -> m.getText(k)?.toString() }.joinToString(" "))
        val mots = n(requete).split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in motsVidesMusique }.distinct()
        if (mots.isEmpty() || meta.isBlank()) return false
        return mots.count { meta.contains(it) } >= maxOf(1, (mots.size + 1) / 2)
    }

    /**
     * Attend que la demande soit réellement jouée : le morceau correspond à la requête, ou il a changé depuis [reference]
     * (titres d'une playlist / d'un style). Si un nouveau morceau est chargé mais en pause, on lance la lecture — mais
     * jamais quand c'est encore l'ancien morceau.
     */
    private fun verifier(c: android.media.session.MediaController, requete: String, reference: String?, secondes: Int): String {
        var relance = false
        for (i in 0 until secondes) {
            Thread.sleep(1000)
            val change = reference != null && titreDe(c) != null && titreDe(c) != reference
            val bon = correspond(c, requete) || change
            if (bon && joue(c)) return "lecture"
            if (bon && !relance) { relance = true; runCatching { c.transportControls.play() } }
        }
        return "session"
    }

    /** Envoie la recherche à la session média de l'appli (playFromSearch) puis vérifie qu'elle est jouée. */
    private fun demanderEtVerifier(c: android.media.session.MediaController, requete: String, reference: String?): String {
        if (correspond(c, requete) && joue(c)) return "lecture"
        runCatching { c.transportControls.playFromSearch(requete, android.os.Bundle()) }
        return verifier(c, requete, reference, 8)
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

    /** Applications de navigation installées (nom affiché → paquet). */
    fun navApps(): Map<String, String> = linkedMapOf("Waze" to "com.waze", "Google Maps" to "com.google.android.apps.maps").filterValues { installed(it) }

    /** Applications de musique RÉELLEMENT installées (interrogation du téléphone, jamais devinées) : tout paquet qui déclare
     * un service de navigation média (Spotify, YouTube Music, Deezer, VLC, lecteurs constructeur…), nom affiché → paquet. */
    fun musicApps(): Map<String, String> {
        val pm = ctx.packageManager
        val out = LinkedHashMap<String, String>()
        runCatching {
            pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0).forEach { ri ->
                val pkg = ri.serviceInfo.packageName
                if (pkg == ctx.packageName) return@forEach
                val label = runCatching { ri.serviceInfo.loadLabel(pm).toString() }.getOrDefault(pkg).ifBlank { pkg }
                out[label] = pkg
            }
        }
        // Filet de sécurité pour les applis connues qui ne déclarent pas ce service sur certains téléphones.
        mapOf("Spotify" to "com.spotify.music", "YouTube Music" to "com.google.android.apps.youtube.music",
            "YT Music Morphe" to "app.morphe.android.apps.youtube.music",
            "Deezer" to "deezer.android.app", "Amazon Music" to "com.amazon.mp3", "Apple Music" to "com.apple.android.music", "VLC" to "org.videolan.vlc")
            .forEach { (label, pkg) -> if (installed(pkg) && out.values.none { it == pkg }) out[label] = pkg }
        // YT Music Morphe installée : l'appli YouTube Music officielle n'est jamais proposée ni utilisée.
        if (installed(Musique.MORPHE_PACKAGE)) {
            out.entries.removeAll { it.value == YtMusique.YTM_OFFICIEL || it.value == Musique.MORPHE_PACKAGE }
            out["YT Music Morphe"] = Musique.MORPHE_PACKAGE
        }
        return out
    }

    /** Lance la navigation vers une destination dans l'application [appli] (Waze, Google Maps ou autre ; vide = Google Maps). */
    fun navigate(dest: String, mode: String, appli: String = "", point: Pair<Double, Double>? = null): String {
        val m = when (mode.trim().lowercase()) { "marche", "a pied", "à pied", "walking", "w" -> "w"; "velo", "vélo", "bicycling", "b" -> "b"; else -> "d" }
        val a = appli.trim().lowercase()
        if (a.contains("waze")) {
            val uri = if (point != null) "waze://?ll=${point.first},${point.second}&navigate=yes" else "waze://?q=" + Uri.encode(dest) + "&navigate=yes"
            val r = start(Intent.ACTION_VIEW, uri, "com.waze")
            return r.ifBlank { "🧭 Waze : navigation vers $dest lancée." }
        }
        if (a.isNotBlank() && !a.contains("google") && !a.contains("maps")) {
            val (pkg, label) = findPackage(a) ?: return "Application « $appli » introuvable."
            val geo = if (point != null) "geo:${point.first},${point.second}?q=${point.first},${point.second}(${Uri.encode(dest)})" else "geo:0,0?q=" + Uri.encode(dest)
            val r = start(Intent.ACTION_VIEW, geo, pkg)
            return r.ifBlank { "🧭 $label : $dest ouvert." }
        }
        val r = start(Intent.ACTION_VIEW, "google.navigation:q=" + Uri.encode(dest) + "&mode=$m", "com.google.android.apps.maps")
        return r.ifBlank { "🧭 Google Maps : navigation vers $dest lancée." }
    }

    /** Lance la lecture d'une vidéo (URL http:// locale ou distante) dans le lecteur vidéo par défaut du téléphone — aucune app spécifique requise. */
    fun lireVideoDistante(url: String, type: String = "video/*"): String {
        return try {
            val i = Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setDataAndType(Uri.parse(url), type)
            ctx.startActivity(i)
            "▶️ Lecture lancée dans le lecteur vidéo du téléphone."
        } catch (e: Exception) { "Impossible de lancer la lecture : ${e.message}. Installe une app vidéo (VLC, MX Player…) si le téléphone n'en a aucune pour ce type de fichier." }
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
        if (!PhoneShell.ready(ctx) && !AdbShell.hasKey(ctx)) return "Aucun accès adb : appairer l'ADB intégré dans Réglages › Téléphone › Contrôle."
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
            "list_apps" -> installedApps()
            "open_url" -> openUrl(value)
            "volume" -> volume(value.filter { it.isDigit() }.toIntOrNull() ?: 50)
            "wifi_on" -> toggle("Wi-Fi", true, "wifi_on", listOf("cmd wifi set-wifi-enabled enabled", "svc wifi enable"))
            "wifi_off" -> toggle("Wi-Fi", false, "wifi_on", listOf("cmd wifi set-wifi-enabled disabled", "svc wifi disable"))
            "wifi_connect" -> start(Settings.ACTION_WIFI_SETTINGS).ifBlank { "Réglages Wi-Fi ouverts. Je peux lire les réseaux affichés et t'aider à sélectionner celui demandé ; Android peut demander une validation système ou le mot de passe." }
            "bluetooth_pair" -> start(Settings.ACTION_BLUETOOTH_SETTINGS).ifBlank { "Réglages Bluetooth ouverts. Je peux rechercher l'appareil affiché et lancer sa sélection ; Android peut demander un code PIN ou une confirmation d'appairage." }
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
            "read_screen", "inspect_screen", "watch_screen" -> JarvisAccessibilityService.instance?.inspectScreen()?.ifBlank { "(écran vide ou illisible)" } ?: needA11y()
            "click_text" -> JarvisAccessibilityService.instance?.let { if (it.clickText(value)) "Clic sur « $value »." else "« $value » introuvable à l'écran." } ?: needA11y()
            "click_best_match" -> JarvisAccessibilityService.instance?.clickBestMatch(value) ?: needA11y()
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
        val SENSITIVE = setOf("call", "click_text", "click_best_match", "type_text", "tap", "wifi_off", "bluetooth_off", "data_off", "lock")
    }
}
