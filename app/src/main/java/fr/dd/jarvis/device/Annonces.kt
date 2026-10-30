package fr.dd.jarvis.device

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import fr.dd.jarvis.JarvisApp
import java.util.Locale

/**
 * Annonces vocales des SMS, mails et notifications (en arrière-plan) : une sonnerie au choix par catégorie, puis JARVIS lit à voix haute.
 * Catégories : sms, mail, autres (applications listées dans Réglages › Annonces). Modes : off | annonce (expéditeur seulement) | lecture (contenu).
 */
object Annonces {
    val CATEGORIES = listOf("sms" to "💬 SMS", "mail" to "📧 Mails", "autres" to "🔔 Autres notifications")
    private val MAIL_PKGS = setOf("com.google.android.gm", "com.google.android.gm.lite", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail",
        "ch.protonmail.android", "com.fsck.k9", "com.samsung.android.email.provider", "com.android.email", "com.mi.android.globalminusscreen.mail")
    private val SMS_PKGS = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.miui.mms", "com.android.messaging")

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsPret = false
    private var ringtone: Ringtone? = null
    private val deja = LinkedHashSet<String>()
    private var enAttente = 0

    fun categorie(ctx: Context, pkg: String, categorieAndroid: String?): String? {
        val defSms = runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull()
        return when {
            pkg == defSms || pkg in SMS_PKGS -> "sms"
            pkg in MAIL_PKGS || categorieAndroid == android.app.Notification.CATEGORY_EMAIL -> "mail"
            else -> "autres"
        }
    }

    private fun appAutorisee(app: JarvisApp, pkg: String, label: String): Boolean {
        val liste = app.store.annApps.value.lowercase().split(Regex("[,;\\n]+")).map { it.trim() }.filter { it.isNotBlank() }
        return liste.any { pkg.lowercase().contains(it) || label.lowercase().contains(it) }
    }

    private fun ecouteursOuVoiture(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val types = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    /** Appelé pour chaque notification reçue ; décide, sonne puis lit. */
    fun traiter(ctx: Context, app: JarvisApp, key: String, pkg: String, label: String, categorieAndroid: String?, titre: String, texte: String) {
        val store = app.store
        if (!store.annOn.value) return
        val cat = categorie(ctx, pkg, categorieAndroid) ?: return
        if (cat == "autres" && !appAutorisee(app, pkg, label)) return
        val mode = store.annMode(cat)
        if (mode == "off") return
        if (store.annPrive.value && !ecouteursOuVoiture(ctx)) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.mode != AudioManager.MODE_NORMAL) return          // appel en cours
        synchronized(deja) { if (!deja.add(key)) return; while (deja.size > 200) deja.remove(deja.first()) }
        if (enAttente >= 4) return
        val phrase = when (cat) {
            "sms" -> "Nouveau SMS de ${titre.ifBlank { "un inconnu" }}." + (if (mode == "lecture" && texte.isNotBlank()) " ${texte.take(300)}" else "")
            "mail" -> "Nouveau mail de ${titre.ifBlank { "un expéditeur inconnu" }}." + (if (mode == "lecture" && texte.isNotBlank()) " ${texte.take(300)}" else "")
            else -> "Notification $label." + (if (mode == "lecture") " ${titre.take(80)}. ${texte.take(250)}" else "")
        }
        annoncer(ctx, store.annSon(cat), phrase)
    }

    /** Sonnerie (si choisie) puis lecture à voix haute. */
    fun annoncer(ctx: Context, son: String, phrase: String) {
        val c = ctx.applicationContext
        enAttente++
        main.post {
            val duree = jouerSon(c, son)
            main.postDelayed({ parler(c, phrase) }, duree)
        }
    }

    private fun jouerSon(c: Context, son: String): Long {
        if (son.isBlank()) return 0
        return try {
            val uri = if (son == "default") RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else Uri.parse(son)
            ringtone?.stop()
            val r = RingtoneManager.getRingtone(c, uri) ?: return 0
            r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            r.play(); ringtone = r
            main.postDelayed({ runCatching { if (ringtone === r) r.stop() } }, 2800)
            2000
        } catch (e: Throwable) { 0 }
    }

    private fun parler(c: Context, phrase: String) {
        fun dire() {
            val t = tts ?: return
            val ok = t.speak(phrase, TextToSpeech.QUEUE_ADD, android.os.Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_NOTIFICATION) }, "ann${System.nanoTime()}")
            if (ok != TextToSpeech.SUCCESS) enAttente = maxOf(0, enAttente - 1)
        }
        if (tts != null && ttsPret) { dire(); return }
        if (tts == null) {
            tts = TextToSpeech(c) { st ->
                if (st == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.FRENCH
                    tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { enAttente = maxOf(0, enAttente - 1) }
                        @Deprecated("Deprecated in Java") override fun onError(id: String?) { enAttente = maxOf(0, enAttente - 1) }
                    })
                    ttsPret = true
                    main.post { dire() }
                } else enAttente = maxOf(0, enAttente - 1)
            }
        } else main.postDelayed({ parler(c, phrase) }, 400)
    }
}
