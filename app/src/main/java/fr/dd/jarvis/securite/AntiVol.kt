package fr.dd.jarvis.securite

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Telephony
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.Locator
import fr.dd.jarvis.societe.core.normaliser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Anti-vol par SMS : depuis N'IMPORTE QUEL téléphone, sans application, un SMS « JARVIS <code> <commande> » pilote le
 * téléphone, même sans Internet tant que la carte SIM est dedans.
 *  - VOL : tout à la fois (verrouillage, alarme, message à l'écran, position, suivi) ;
 *  - VERROUILLE, SONNE, OU (position), SUIVI (position toutes les 10 min), MESSAGE <texte>, STOP, AIDE.
 * Le code est haché (PBKDF2) ; un mauvais code ne reçoit aucune réponse et 3 erreurs en une heure bloquent ce numéro 24 h.
 * Pendant l'alerte, JARVIS refuse toute demande. Un changement de carte SIM prévient le numéro de secours.
 */
object AntiVol {
    const val CANAL = "antivol_alarme"
    private const val NOTIF = 7900
    const val ACT_SUIVI = "fr.dd.jarvis.ANTIVOL_SUIVI"
    private const val SUIVI_MIN = 10
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class Action { VOL, VERROUILLE, SONNE, POSITION, SUIVI, MESSAGE, STOP, AIDE }
    data class Commande(val code: String, val action: Action?, val argument: String)

    // ------------------------------------------------------------------ lecture des SMS

    private val FORME = Regex("^\\s*jarvis[\\s,:;.!-]+(\\S+)\\s*(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** « JARVIS 482913 VOL » → (code, action, argument) ; null si ce n'est pas la forme d'une commande. */
    private fun actionDe(motBrut: String): Action? = when (normaliser(motBrut).trim().trimEnd('.', '!', '?')) {
        "vol", "vole", "volee", "perdu", "perdue", "alerte" -> Action.VOL
        "verrouille", "verrouiller", "verrou", "bloque", "lock" -> Action.VERROUILLE
        "sonne", "sonner", "alarme", "sirene", "ring", "bip" -> Action.SONNE
        "ou", "position", "localise", "localisation", "gps", "loc", "where" -> Action.POSITION
        "suivi", "suis", "suivre", "track" -> Action.SUIVI
        "message", "msg", "affiche", "ecris" -> Action.MESSAGE
        "stop", "fin", "arret", "arrete", "retrouve", "ok" -> Action.STOP
        "aide", "help", "?", "" -> Action.AIDE
        else -> null
    }

    /** Chevrons, guillemets, crochets autour du code (« JARVIS <010815> SONNE », recopié du mode d'emploi) : ignorés. */
    private val ENTOURAGE = Regex("[<>«»\\[\\]{}\"“”]")

    internal fun lire(texte: String): Commande? {
        val propre = ENTOURAGE.replace(texte, " ").replace(Regex("[ \\t]+"), " ").trim()
        val m = FORME.matchEntire(propre) ?: return null
        var code = m.groupValues[1].trimEnd(',', '.', ':', ';', '!')
        var reste = m.groupValues[2].trim()
        // « JARVIS VOL 123456 » : la commande écrite avant le code est acceptée aussi.
        if (actionDe(code) != null) {
            val suivant = reste.substringBefore(' ').substringBefore('\n').trimEnd(',', '.', ':', ';', '!')
            if (suivant.length >= 4 && actionDe(suivant) == null) {
                reste = (code + " " + reste.removePrefix(reste.substringBefore(' ').substringBefore('\n'))).trim()
                code = suivant
            }
        }
        if (code.length < 4) return null
        val action = actionDe(reste.substringBefore(' ').substringBefore('\n'))
        val argument = reste.substringAfter(' ', "").trim()
        return Commande(code, action, argument)
    }

    /** Le texte ressemble à une commande anti-vol (pour ne jamais l'annoncer, le résumer ni l'afficher avec le code). */
    fun ressembleCommande(texte: String): Boolean = lire(texte)?.action != null

    /** Masque le code dans un texte affiché (« JARVIS •••••• VOL »). */
    fun masquer(texte: String): String = FORME.matchEntire(texte.trim())?.let { m ->
        if (lire(texte)?.action != null) "JARVIS •••••• ${m.groupValues[2]}".trim() else null
    } ?: texte

    // ------------------------------------------------------------------ code secret

    internal fun hacher(code: String, sel: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }, iterations: Int = 20_000): String {
        val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(code.trim().toCharArray(), sel, iterations, 256)).encoded
        val b64 = java.util.Base64.getEncoder()
        return "pbkdf2\$$iterations\$${b64.encodeToString(sel)}\$${b64.encodeToString(k)}"
    }

    internal fun verifier(code: String, empreinte: String): Boolean {
        val p = empreinte.split('$')
        if (p.size != 4 || p[0] != "pbkdf2") return false
        val d = java.util.Base64.getDecoder()
        val attendu = runCatching { hacher(code, d.decode(p[2]), p[1].toInt()) }.getOrNull() ?: return false
        return MessageDigest.isEqual(attendu.toByteArray(), empreinte.toByteArray())
    }

    /** Code acceptable : 6 caractères au moins, pas une suite évidente. */
    fun codeValide(code: String): String? {
        val c = code.trim()
        return when {
            c.length < 6 -> "6 caractères au moins."
            c.contains(' ') -> "Sans espace."
            c.all { it == c[0] } || "0123456789".contains(c) || "9876543210".contains(c) -> "Trop facile à deviner."
            else -> null
        }
    }

    fun definirCode(app: JarvisApp, code: String) = app.store.antivolCode.set(hacher(code))

    // ------------------------------------------------------------------ limitation des essais

    private fun cle(numero: String) = numero.filter { it.isDigit() }.takeLast(9).ifBlank { "nom:" + normaliser(numero).trim() }

    /** Un vrai numéro de téléphone (pour répondre par SMS) ? */
    internal fun estNumero(numero: String) = numero.count { it.isDigit() } >= 6 && !numero.any { it.isLetter() }

    /** Ce numéro est-il bloqué (3 mauvais codes en une heure → 24 h) ? */
    internal fun bloque(echecs: JSONObject, numero: String, maintenant: Long): Boolean {
        val l = echecs.optJSONArray(cle(numero)) ?: return false
        val t = (0 until l.length()).map { l.optLong(it) }.filter { maintenant - it < 86_400_000L }.sorted()
        for (i in 0..t.size - 3) if (t[i + 2] - t[i] < 3_600_000L) return true
        return false
    }

    private fun noterEchec(app: JarvisApp, numero: String) {
        val o = runCatching { JSONObject(app.store.antivolEchecs.value) }.getOrDefault(JSONObject())
        val now = System.currentTimeMillis()
        val l = (o.optJSONArray(cle(numero))?.let { a -> (0 until a.length()).map { a.optLong(it) } } ?: emptyList()).filter { now - it < 86_400_000L } + now
        o.put(cle(numero), JSONArray(l.takeLast(10)))
        app.store.antivolEchecs.set(o.toString())
        journal(app, "❌ Mauvais code depuis $numero")
        // Plusieurs essais : le numéro de secours est prévenu.
        if (l.size == 3) app.store.antivolSecours.value.takeIf { it.isNotBlank() }?.let {
            sms(app, it, "⚠️ JARVIS : 3 mauvais codes anti-vol reçus de $numero sur ton téléphone. Ce numéro est bloqué 24 h.")
        }
    }

    fun journal(app: JarvisApp, ligne: String) {
        val a = runCatching { JSONArray(app.store.antivolJournal.value) }.getOrDefault(JSONArray())
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("l", ligne))
        val garde = JSONArray(); for (i in maxOf(0, a.length() - 30) until a.length()) garde.put(a.get(i))
        app.store.antivolJournal.set(garde.toString())
    }

    // ------------------------------------------------------------------ réception

    /** SMS reçu : renvoie vrai s'il s'agissait d'une commande anti-vol (traitée ou refusée). */
    fun recevoir(app: JarvisApp, numero: String, texte: String, via: String = "SMS"): Boolean {
        val c = lire(texte) ?: return false
        if (c.action == null) return false
        // Le même message peut arriver deux fois (SMS reçu + notification de l'appli de messagerie) : traité une seule fois.
        if (dejaTraite(texte)) return true
        if (!app.store.antivolActif.value || app.store.antivolCode.value.isBlank()) {
            journal(app, "⚠️ Commande reçue ($via) mais l'anti-vol est " + (if (app.store.antivolCode.value.isBlank()) "sans code secret" else "désactivé") + " : rien fait")
            return true
        }
        val echecs = runCatching { JSONObject(app.store.antivolEchecs.value) }.getOrDefault(JSONObject())
        if (bloque(echecs, numero, System.currentTimeMillis())) { journal(app, "⛔ Commande ignorée (numéro bloqué) : $numero"); return true }
        if (!verifier(c.code, app.store.antivolCode.value)) { noterEchec(app, numero); return true }
        journal(app, "✅ ${c.action.name} depuis $numero ($via)" + (if (!estNumero(numero)) " — réponse par SMS impossible (numéro inconnu)" else ""))
        portee.launch { executer(app, c, numero) }
        return true
    }

    private val recents = java.util.concurrent.ConcurrentHashMap<String, Long>()

    internal fun dejaTraite(texte: String, maintenant: Long = System.currentTimeMillis()): Boolean {
        val k = MessageDigest.getInstance("SHA-256").digest(texte.trim().replace(Regex("\\s+"), " ").lowercase().toByteArray()).joinToString("") { "%02x".format(it) }
        recents.entries.removeIf { maintenant - it.value > 90_000L }
        return recents.putIfAbsent(k, maintenant) != null
    }

    /**
     * Secours quand Android ne transmet pas le SMS à JARVIS : message parti en « Chat » (RCS, Google Messages),
     * blocage Xiaomi/HyperOS, autorisation SMS refusée. La notification de l'appli de messagerie contient le texte :
     * la commande est traitée à partir d'elle (3 s plus tard, pour laisser passer le SMS direct qui a le numéro).
     */
    fun depuisNotification(app: JarvisApp, titre: String, texte: String, n: android.app.Notification) {
        if (lire(texte)?.action == null) return
        val numero = numeroDe(app, titre, n)
        portee.launch {
            kotlinx.coroutines.delay(3000)
            runCatching { recevoir(app, numero.ifBlank { titre.ifBlank { "inconnu" } }, texte, via = "notification") }
        }
    }

    /** Numéro de l'expéditeur d'une notification de messagerie : fiche « tel: », titre en chiffres, ou contact du même nom. */
    private fun numeroDe(app: JarvisApp, titre: String, n: android.app.Notification): String {
        val e = n.extras
        if (Build.VERSION.SDK_INT >= 28) {
            val personnes = runCatching {
                @Suppress("DEPRECATION") (e.getParcelableArrayList<android.app.Person>(android.app.Notification.EXTRA_PEOPLE_LIST) ?: arrayListOf())
            }.getOrDefault(arrayListOf())
            val messages = runCatching {
                @Suppress("DEPRECATION") e.getParcelableArray(android.app.Notification.EXTRA_MESSAGES)?.mapNotNull { (it as? android.os.Bundle)?.getParcelable<android.app.Person>("sender_person") }
            }.getOrNull().orEmpty()
            (messages.reversed() + personnes).mapNotNull { it.uri }.firstOrNull { it.startsWith("tel:") }?.let { return android.net.Uri.decode(it.removePrefix("tel:")) }
        }
        if (estNumero(titre)) return titre.trim()
        if (titre.isBlank() || ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        return runCatching {
            app.contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " = ?", arrayOf(titre.trim()), null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull().orEmpty()
    }

    /** Ce que JARVIS comprend d'un texte de commande (test dans les Réglages, rien n'est exécuté). */
    fun tester(app: JarvisApp, texte: String): String {
        val c = lire(texte) ?: return "❌ Pas reconnu : le message doit commencer par « JARVIS », puis ton code, puis la commande (ex. « JARVIS monCode VOL »)."
        if (c.action == null) return "❌ Commande inconnue après le code. Commandes : VOL · VERROUILLE · SONNE · OU · SUIVI · MESSAGE <texte> · STOP · AIDE."
        val code = when {
            app.store.antivolCode.value.isBlank() -> "⚠️ aucun code secret enregistré"
            verifier(c.code, app.store.antivolCode.value) -> "✅ code correct"
            else -> "❌ code incorrect (attention aux majuscules et aux espaces)"
        }
        return "Commande ${c.action.name}" + (if (c.argument.isNotBlank()) " « ${c.argument.take(60)} »" else "") + " · $code" +
            (if (!app.store.antivolActif.value) " · ⚠️ anti-vol désactivé" else "")
    }

    private suspend fun executer(app: JarvisApp, c: Commande, numero: String) {
        val s = app.store
        when (c.action) {
            Action.AIDE -> sms(app, numero, AIDE)
            Action.STOP -> { arreter(app); sms(app, numero, "✅ JARVIS : alerte arrêtée, téléphone rendu à la normale.") }
            Action.VERROUILLE -> {
                val ok = verrouiller(app); s.antivolAlerte.set(true); masquerNotifications(app, true)
                sms(app, numero, (if (ok) "🔒 Téléphone verrouillé" else "⚠️ Verrouillage impossible (active « Administrateur de l'appareil » dans JARVIS)") + ". JARVIS est bloqué.\n" + position(app))
            }
            Action.SONNE -> {
                s.antivolAlerte.set(true); masquerNotifications(app, true)
                verrouiller(app); alarme(app, true)
                sms(app, numero, "🔊 Le téléphone sonne à fond (même en silencieux ou vibreur) et il est verrouillé.\n" + position(app) + "\n« JARVIS tonCode STOP » pour arrêter.")
            }
            Action.POSITION -> sms(app, numero, position(app))
            Action.SUIVI -> { suivre(app, numero); sms(app, numero, "📡 Suivi activé : position toutes les $SUIVI_MIN min pendant 24 h (« JARVIS tonCode STOP » pour arrêter).\n" + position(app)) }
            Action.MESSAGE -> {
                s.antivolMessage.set(c.argument.ifBlank { messageParDefaut(app) }); s.antivolAlerte.set(true); masquerNotifications(app, true)
                verrouiller(app); afficher(app, sonore = false)
                sms(app, numero, "📝 Message affiché sur l'écran : « ${s.antivolMessage.value} »")
            }
            Action.VOL -> {
                s.antivolAlerte.set(true); masquerNotifications(app, true)
                if (c.argument.isNotBlank()) s.antivolMessage.set(c.argument)
                val ok = verrouiller(app); alarme(app, true); suivre(app, numero)
                sms(app, numero, "🚨 JARVIS : mode VOL activé — " + (if (ok) "verrouillé" else "⚠️ verrouillage impossible") +
                    ", alarme, message à l'écran, position toutes les $SUIVI_MIN min.\n" + position(app) + "\n« JARVIS tonCode STOP » pour arrêter.")
            }
            null -> Unit
        }
    }

    private const val AIDE = "JARVIS anti-vol — envoie « JARVIS tonCode COMMANDE » (ton code tel quel, sans < >) :\nVOL : tout (verrouille, sonne, message, suivi)\n" +
        "VERROUILLE · SONNE · OU (position) · SUIVI · MESSAGE <texte> · STOP"

    private fun messageParDefaut(app: JarvisApp) = "📵 Ce téléphone a été perdu ou volé." +
        (app.store.antivolSecours.value.takeIf { it.isNotBlank() }?.let { " Merci d'appeler le $it." } ?: "")

    // ------------------------------------------------------------------ actions

    fun adminActif(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(ComponentName(ctx, AdminJarvis::class.java))
    }.getOrDefault(false)

    /** Verrouille l'écran : administrateur de l'appareil, sinon service d'accessibilité. */
    fun verrouiller(ctx: Context): Boolean {
        if (adminActif(ctx)) runCatching { (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow(); return true }
        if (Build.VERSION.SDK_INT >= 28) fr.dd.jarvis.service.JarvisAccessibilityService.instance?.let { return it.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }
        return false
    }

    /**
     * Alarme : jouée par JARVIS lui-même sur le canal des ALARMES (qui sonne même en silencieux ou vibreur), volume
     * remis au maximum toutes les 2 s (le voleur ne peut pas le baisser), « Ne pas déranger » levé si JARVIS en a le
     * droit, vibrations, notification insistante + écran d'alerte par-dessus le verrouillage.
     */
    fun alarme(app: JarvisApp, on: Boolean) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (on) {
            if (app.store.antivolVolume.value.isBlank()) app.store.antivolVolume.set(am.getStreamVolume(AudioManager.STREAM_ALARM).toString())
            runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
            sortirDuSilence(app, true)
            val joue = jouer(app)
            afficher(app, sonore = !joue, sonnerieInterne = joue)
        } else {
            arreterSon(app)
            runCatching { NotificationManagerCompat.from(app).cancel(NOTIF) }
            app.store.antivolVolume.value.toIntOrNull()?.let { v -> runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
            app.store.antivolVolume.set("")
            sortirDuSilence(app, false)
        }
    }

    @Volatile private var lecteur: android.media.MediaPlayer? = null
    private val principal by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var maintien: Runnable? = null

    /** Vrai si l'alarme sonne en ce moment (l'écran d'alerte bloque alors les touches de volume). */
    val sonne: Boolean get() = lecteur != null

    private fun jouer(app: JarvisApp): Boolean {
        arreterSon(app)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
        val l = runCatching {
            android.media.MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(app, uri)
                isLooping = true
                setWakeMode(app, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                prepare(); start()
            }
        }.getOrNull() ?: return false
        lecteur = l
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val vib = runCatching {
            if (Build.VERSION.SDK_INT >= 31) (app.getSystemService(android.os.VibratorManager::class.java)).defaultVibrator
            else @Suppress("DEPRECATION") app.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }.getOrNull()
        runCatching { vib?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0)) }
        val r = object : Runnable {
            override fun run() {
                if (lecteur == null) return
                runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
                principal.postDelayed(this, 2000)
            }
        }
        maintien = r; principal.post(r)
        journal(app, "🔊 Alarme lancée (canal des alarmes)")
        return true
    }

    private fun arreterSon(app: JarvisApp) {
        maintien?.let { principal.removeCallbacks(it) }; maintien = null
        lecteur?.let { l -> runCatching { l.stop() }; runCatching { l.release() } }
        lecteur = null
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) app.getSystemService(android.os.VibratorManager::class.java).defaultVibrator.cancel()
            else @Suppress("DEPRECATION") (app.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator).cancel()
        }
    }

    /**
     * Mode silencieux / vibreur / Ne pas déranger : levés le temps de l'alarme si JARVIS a l'accès « Ne pas déranger »
     * (sinon par Shizuku / l'ADB intégré), puis remis comme avant à l'arrêt.
     */
    private fun sortirDuSilence(app: JarvisApp, oui: Boolean) {
        val nm = app.getSystemService(NotificationManager::class.java)
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = app.store
        if (oui) {
            if (s.antivolSilence.value.isBlank()) s.antivolSilence.set("${nm.currentInterruptionFilter}|${am.ringerMode}")
            if (nm.isNotificationPolicyAccessGranted) {
                runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
                runCatching { am.ringerMode = AudioManager.RINGER_MODE_NORMAL }
            } else if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) shell(app, "cmd notification set_dnd off")
        } else {
            val avant = s.antivolSilence.value.split('|').mapNotNull { it.toIntOrNull() }
            s.antivolSilence.set("")
            if (avant.size != 2) return
            if (nm.isNotificationPolicyAccessGranted) {
                runCatching { am.ringerMode = avant[1] }
                runCatching { nm.setInterruptionFilter(avant[0]) }
            }
        }
    }

    /** Commande système (Shizuku ou ADB intégré) en arrière-plan ; ignorée si aucun des deux n'est disponible. */
    private fun shell(app: JarvisApp, commande: String, apres: (String) -> Unit = {}) {
        if (!fr.dd.jarvis.device.ShizukuShell.ready() && !fr.dd.jarvis.device.AdbShell.hasKey(app)) return
        Thread { runCatching { apres(fr.dd.jarvis.device.PhoneShell.exec(app, commande)) } }.start()
    }

    /**
     * Notifications sur l'écran verrouillé : MASQUÉES pendant l'alerte (le voleur ne lit ni les messages ni les codes
     * de vérification), remises comme avant à l'arrêt. Il faut Shizuku ou l'ADB intégré (réglage système protégé).
     */
    fun masquerNotifications(app: JarvisApp, oui: Boolean) {
        val s = app.store
        if (oui) {
            if (s.antivolNotifsEcran.value.isNotBlank()) return
            shell(app, "settings get secure lock_screen_show_notifications") { avant ->
                s.antivolNotifsEcran.set(avant.trim().takeIf { it == "0" || it == "1" } ?: "1")
                runCatching { fr.dd.jarvis.device.PhoneShell.exec(app, "settings put secure lock_screen_show_notifications 0") }
                journal(app, "🙈 Notifications masquées sur l'écran verrouillé")
            }
        } else {
            val avant = s.antivolNotifsEcran.value
            if (avant.isBlank()) return
            s.antivolNotifsEcran.set("")
            shell(app, "settings put secure lock_screen_show_notifications $avant")
        }
    }

    /** Écran « téléphone volé » (par-dessus l'écran verrouillé) et notification ; [sonore] : alarme en boucle. */
    /** Texte de l'écran d'alerte en cours (test, message de la commande MESSAGE / VOL, ou message par défaut). */
    @Volatile var messageEcran: String? = null

    fun messageAffiche(app: JarvisApp): String = messageEcran
        ?: app.store.antivolMessage.value.takeIf { it.isNotBlank() && !it.startsWith("Test de l'alerte") }
        ?: messageParDefaut(app)

    fun afficher(app: JarvisApp, sonore: Boolean, message: String? = null, sonnerieInterne: Boolean = false) {
        messageEcran = message
        val nm = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CANAL, "Alerte anti-vol", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true); vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800); setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
            nm.createNotificationChannel(NotificationChannel("antivol_message", "Message anti-vol", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
            // Alarme jouée par JARVIS lui-même : notification d'alerte sans son propre (pas de double sonnerie).
            nm.createNotificationChannel(NotificationChannel("antivol_alerte", "Alerte anti-vol (écran)", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(false); lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
        }
        val ecran = Intent(app, AlerteVolActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(app, NOTIF, ecran, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, when { sonore -> CANAL; sonnerieInterne -> "antivol_alerte"; else -> "antivol_message" }).setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("📵 Téléphone perdu ou volé").setContentText(messageAffiche(app))
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true).setAutoCancel(false).setFullScreenIntent(pi, true).setContentIntent(pi).build()
        if (sonore) n.flags = n.flags or android.app.Notification.FLAG_INSISTENT
        runCatching { nm.notify(NOTIF, n) }
        runCatching { app.startActivity(ecran) }
    }

    fun arreter(app: JarvisApp) {
        app.store.antivolAlerte.set(false)
        app.store.antivolSuiviJusqua.set("0")
        alarme(app, false)
        masquerNotifications(app, false)
        app.store.antivolMessage.set("")
        messageEcran = null
        runCatching { (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(piSuivi(app)) }
        AlerteVolActivity.fermer()
        journal(app, "⏹️ Alerte arrêtée")
    }

    // ------------------------------------------------------------------ position et suivi

    suspend fun position(app: JarvisApp): String {
        val l = withTimeoutOrNull(20_000) { runCatching { Locator.precise(app, 15_000) }.getOrNull() } ?: Locator.last(app)
        val batterie = (app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (l == null) return "📍 Position indisponible (localisation coupée ou refusée). 🔋 $batterie %"
        val age = ((System.currentTimeMillis() - l.time) / 60_000).coerceAtLeast(0)
        return "📍 https://maps.google.com/?q=${"%.6f".format(java.util.Locale.US, l.latitude)},${"%.6f".format(java.util.Locale.US, l.longitude)}" +
            " (±${l.accuracy.toInt()} m${if (age > 1) ", il y a $age min" else ""}) · 🔋 $batterie %"
    }

    private fun piSuivi(ctx: Context) = PendingIntent.getBroadcast(ctx, NOTIF + 1, Intent(ctx, AntiVolReceiver::class.java).setAction(ACT_SUIVI),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun suivre(app: JarvisApp, numero: String) {
        app.store.antivolSuiviNumero.set(numero)
        app.store.antivolSuiviJusqua.set((System.currentTimeMillis() + 86_400_000L).toString())
        planifierSuivi(app)
    }

    private fun planifierSuivi(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + SUIVI_MIN * 60_000L
        runCatching {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, piSuivi(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, piSuivi(ctx))
        }
    }

    internal fun tourDeSuivi(app: JarvisApp) {
        val fin = app.store.antivolSuiviJusqua.value.toLongOrNull() ?: 0L
        val num = app.store.antivolSuiviNumero.value
        if (System.currentTimeMillis() > fin || num.isBlank()) return
        portee.launch { sms(app, num, "📡 JARVIS suivi : " + position(app)) }
        planifierSuivi(app)
    }

    // ------------------------------------------------------------------ changement de carte SIM

    /** Signature de la carte SIM (opérateur, pays, état) — sans permission particulière. */
    private fun signatureSim(ctx: Context): String? {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (tm.simState != TelephonyManager.SIM_STATE_READY) return if (tm.simState == TelephonyManager.SIM_STATE_ABSENT) "absente" else null
        return listOf(tm.simOperator, tm.simOperatorName, tm.simCountryIso).joinToString("|")
    }

    /** Appelée par la veille et au démarrage : SIM changée ou retirée → alerte au numéro de secours (+ verrouillage). */
    fun verifierSim(app: JarvisApp) {
        if (!app.store.antivolActif.value) return
        val sig = signatureSim(app) ?: return
        val avant = app.store.antivolSim.value
        if (avant.isBlank()) { if (sig != "absente") app.store.antivolSim.set(sig); return }
        if (sig == avant) return
        if (sig == "absente") { journal(app, "⚠️ Carte SIM retirée"); if (app.store.antivolSimVerrou.value) { verrouiller(app); app.store.antivolAlerte.set(true) }; return }
        app.store.antivolSim.set(sig)
        journal(app, "⚠️ Carte SIM changée : ${sig.substringBefore('|')}")
        if (app.store.antivolSimVerrou.value) { verrouiller(app); app.store.antivolAlerte.set(true) }
        val secours = app.store.antivolSecours.value
        if (secours.isNotBlank()) portee.launch {
            sms(app, secours, "⚠️ JARVIS : la carte SIM de ton téléphone a été changée (opérateur ${sig.split('|').getOrNull(1).orEmpty().ifBlank { sig.substringBefore('|') }}). " +
                "Ce SMS vient du nouveau numéro.\n" + position(app) + "\nRéponds « JARVIS tonCode VOL » à ce numéro pour le bloquer.")
        }
    }

    /** Au démarrage du téléphone : SIM vérifiée, alerte et suivi repris s'ils étaient en cours. */
    fun reprendre(app: JarvisApp) {
        verifierSim(app)
        if (!app.store.antivolAlerte.value) return
        verrouiller(app)
        afficher(app, sonore = false)
        if ((app.store.antivolSuiviJusqua.value.toLongOrNull() ?: 0L) > System.currentTimeMillis()) {
            planifierSuivi(app)
            app.store.antivolSuiviNumero.value.takeIf { it.isNotBlank() }?.let { n -> portee.launch { sms(app, n, "🔄 JARVIS : le téléphone vient de redémarrer.\n" + position(app)) } }
        }
    }

    fun memoriserSim(app: JarvisApp) { signatureSim(app)?.takeIf { it != "absente" }?.let { app.store.antivolSim.set(it) } }

    // ------------------------------------------------------------------ état pour l'écran de réglages

    data class Etat(val sms: Boolean, val envoi: Boolean, val localisation: Boolean, val arrierePlan: Boolean, val admin: Boolean,
                    val superposition: Boolean, val pleinEcran: Boolean, val accessibilite: Boolean, val notifications: Boolean = false, val contacts: Boolean = false,
                    val neDerangePas: Boolean = false, val shell: Boolean = false)

    fun etat(ctx: Context): Etat {
        fun ok(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        return Etat(
            sms = ok(Manifest.permission.RECEIVE_SMS), envoi = ok(Manifest.permission.SEND_SMS),
            localisation = ok(Manifest.permission.ACCESS_FINE_LOCATION),
            arrierePlan = Build.VERSION.SDK_INT < 29 || ok(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            admin = adminActif(ctx),
            superposition = android.provider.Settings.canDrawOverlays(ctx),
            pleinEcran = Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
            accessibilite = fr.dd.jarvis.service.JarvisAccessibilityService.instance != null,
            notifications = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName),
            contacts = ok(Manifest.permission.READ_CONTACTS),
            neDerangePas = runCatching { ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted }.getOrDefault(false),
            shell = fr.dd.jarvis.device.ShizukuShell.ready() || fr.dd.jarvis.device.AdbShell.hasKey(ctx),
        )
    }

    private fun sms(ctx: Context, numero: String, texte: String) {
        if (!estNumero(numero)) return   // expéditeur connu seulement par son nom (notification) : pas de réponse possible
        runCatching { fr.dd.jarvis.device.Telephony.envoyer(ctx, numero, texte) }
    }
}

/** SMS reçus : les commandes anti-vol sont traitées immédiatement (avant toute autre lecture). */
class AntiVolSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = context.applicationContext as? JarvisApp ?: return
        // Diagnostic (Réglages › Sécurité › Anti-vol) : preuve qu'Android transmet bien les SMS à JARVIS.
        runCatching { app.store.antivolDernierSms.set(System.currentTimeMillis().toString()) }
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        val fin = goAsync()
        Thread {
            try {
                // Un SMS long arrive en plusieurs morceaux : on les recolle par expéditeur.
                messages.groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "" }.forEach { (numero, parts) ->
                    val texte = parts.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
                    if (numero.isNotBlank()) runCatching { AntiVol.recevoir(app, numero, texte) }
                }
            } finally { fin.finish() }
        }.start()
    }
}

class AntiVolReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? JarvisApp ?: return
        if (intent.action == AntiVol.ACT_SUIVI) AntiVol.tourDeSuivi(app)
    }
}

/** Administrateur de l'appareil : verrouillage à distance (et JARVIS ne peut pas être désinstallé sans le désactiver). */
class AdminJarvis : DeviceAdminReceiver()
