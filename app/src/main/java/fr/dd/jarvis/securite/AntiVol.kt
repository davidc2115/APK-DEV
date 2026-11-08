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
    internal fun lire(texte: String): Commande? {
        val m = FORME.matchEntire(texte.trim()) ?: return null
        val code = m.groupValues[1].trimEnd(',', '.', ':', ';', '!')
        if (code.length < 4) return null
        val reste = m.groupValues[2].trim()
        val mot = normaliser(reste.substringBefore(' ').substringBefore('\n')).trim().trimEnd('.', '!', '?')
        val argument = reste.substringAfter(' ', "").trim()
        val action = when (mot) {
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

    private fun cle(numero: String) = numero.filter { it.isDigit() }.takeLast(9)

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
    fun recevoir(app: JarvisApp, numero: String, texte: String): Boolean {
        val c = lire(texte) ?: return false
        if (c.action == null) return false
        if (!app.store.antivolActif.value || app.store.antivolCode.value.isBlank()) return true
        val echecs = runCatching { JSONObject(app.store.antivolEchecs.value) }.getOrDefault(JSONObject())
        if (bloque(echecs, numero, System.currentTimeMillis())) { journal(app, "⛔ Commande ignorée (numéro bloqué) : $numero"); return true }
        if (!verifier(c.code, app.store.antivolCode.value)) { noterEchec(app, numero); return true }
        journal(app, "✅ ${c.action.name} depuis $numero")
        portee.launch { executer(app, c, numero) }
        return true
    }

    private suspend fun executer(app: JarvisApp, c: Commande, numero: String) {
        val s = app.store
        when (c.action) {
            Action.AIDE -> sms(app, numero, AIDE)
            Action.STOP -> { arreter(app); sms(app, numero, "✅ JARVIS : alerte arrêtée, téléphone rendu à la normale.") }
            Action.VERROUILLE -> {
                val ok = verrouiller(app); s.antivolAlerte.set(true)
                sms(app, numero, (if (ok) "🔒 Téléphone verrouillé" else "⚠️ Verrouillage impossible (active « Administrateur de l'appareil » dans JARVIS)") + ". JARVIS est bloqué.\n" + position(app))
            }
            Action.SONNE -> { verrouiller(app); alarme(app, true); sms(app, numero, "🔊 Le téléphone sonne à fond (même en silencieux).\n" + position(app)) }
            Action.POSITION -> sms(app, numero, position(app))
            Action.SUIVI -> { suivre(app, numero); sms(app, numero, "📡 Suivi activé : position toutes les $SUIVI_MIN min pendant 24 h (« JARVIS <code> STOP » pour arrêter).\n" + position(app)) }
            Action.MESSAGE -> {
                s.antivolMessage.set(c.argument.ifBlank { messageParDefaut(app) }); s.antivolAlerte.set(true)
                verrouiller(app); afficher(app, sonore = false)
                sms(app, numero, "📝 Message affiché sur l'écran : « ${s.antivolMessage.value} »")
            }
            Action.VOL -> {
                s.antivolAlerte.set(true)
                if (s.antivolMessage.value.isBlank() || c.argument.isNotBlank()) s.antivolMessage.set(c.argument.ifBlank { messageParDefaut(app) })
                val ok = verrouiller(app); alarme(app, true); suivre(app, numero)
                sms(app, numero, "🚨 JARVIS : mode VOL activé — " + (if (ok) "verrouillé" else "⚠️ verrouillage impossible") +
                    ", alarme, message à l'écran, position toutes les $SUIVI_MIN min.\n" + position(app) + "\n« JARVIS <code> STOP » pour arrêter.")
            }
            null -> Unit
        }
    }

    private const val AIDE = "JARVIS anti-vol — envoie « JARVIS <code> <commande> » :\nVOL : tout (verrouille, sonne, message, suivi)\n" +
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

    /** Alarme : volume des alarmes au maximum, notification insistante + écran d'alerte par-dessus le verrouillage. */
    fun alarme(app: JarvisApp, on: Boolean) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (on) {
            if (app.store.antivolVolume.value.isBlank()) app.store.antivolVolume.set(am.getStreamVolume(AudioManager.STREAM_ALARM).toString())
            runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
            afficher(app, sonore = true)
        } else {
            runCatching { NotificationManagerCompat.from(app).cancel(NOTIF) }
            app.store.antivolVolume.value.toIntOrNull()?.let { v -> runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
            app.store.antivolVolume.set("")
        }
    }

    /** Écran « téléphone volé » (par-dessus l'écran verrouillé) et notification ; [sonore] : alarme en boucle. */
    fun afficher(app: JarvisApp, sonore: Boolean) {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CANAL, "Alerte anti-vol", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true); vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800); setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
            nm.createNotificationChannel(NotificationChannel("antivol_message", "Message anti-vol", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        }
        val ecran = Intent(app, AlerteVolActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(app, NOTIF, ecran, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, if (sonore) CANAL else "antivol_message").setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("📵 Téléphone perdu ou volé").setContentText(app.store.antivolMessage.value.ifBlank { messageParDefaut(app) })
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
                "Ce SMS vient du nouveau numéro.\n" + position(app) + "\nRéponds « JARVIS <code> VOL » à ce numéro pour le bloquer.")
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
                    val superposition: Boolean, val pleinEcran: Boolean, val accessibilite: Boolean)

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
        )
    }

    private fun sms(ctx: Context, numero: String, texte: String) { runCatching { fr.dd.jarvis.device.Telephony.envoyer(ctx, numero, texte) } }
}

/** SMS reçus : les commandes anti-vol sont traitées immédiatement (avant toute autre lecture). */
class AntiVolSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = context.applicationContext as? JarvisApp ?: return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        // Un SMS long arrive en plusieurs morceaux : on les recolle par expéditeur.
        messages.groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "" }.forEach { (numero, parts) ->
            val texte = parts.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
            if (numero.isNotBlank()) runCatching { AntiVol.recevoir(app, numero, texte) }
        }
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
