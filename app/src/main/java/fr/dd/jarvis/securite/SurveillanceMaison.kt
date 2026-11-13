package fr.dd.jarvis.securite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Serveur
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Surveillance de la maison PAR LE TÉLÉPHONE (en plus de l'automatisation Home Assistant) : tant qu'un système avec
 * MMS est actif, Jarvis reste abonné aux capteurs de Home Assistant (WebSocket, instantané, très peu de données). Une
 * personne détectée → photo de chaque caméra (API Home Assistant) → MMS aux numéros configurés + notification ici,
 * sirène si demandé (et si Home Assistant ne s'en charge pas déjà). Deux minutes de répit entre deux alertes.
 */
class SurveillanceMaison : Service() {
    companion object {
        private const val CANAL = "jarvis_surveillance"
        private const val NOTIF = 48200
        @Volatile var actif = false
            private set

        /** Démarre ou arrête la surveillance selon les systèmes actifs. */
        fun actualiser(app: JarvisApp) {
            val besoin = Systemes.liste(app).any { it.actif && it.viaMms && it.capteurs.isNotEmpty() && it.destinataires.isNotEmpty() }
            val i = Intent(app, SurveillanceMaison::class.java)
            if (besoin) runCatching { if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i) }
            else if (actif) runCatching { app.stopService(i) }
        }

        internal fun urlWs(haUrl: String): String {
            val b = haUrl.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
            return b.replaceFirst(Regex("^http"), "ws") + "/api/websocket"
        }
    }

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var ws: WebSocket? = null
    private var tentatives = 0
    private val derniereAlerte = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private lateinit var app: JarvisApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as JarvisApp
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CANAL, "Surveillance de la maison", NotificationManager.IMPORTANCE_LOW))
        val n = notification("🛡️ Maison sécurisée : surveillance active", "Une personne détectée → photos par MMS.")
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIF, n)
        }.onFailure { stopSelf(); return }
        actif = true
        connecter()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        actif = false
        runCatching { ws?.close(1000, "arrêt") }
        portee.cancel()
        super.onDestroy()
    }

    private fun notification(titre: String, texte: String): Notification =
        NotificationCompat.Builder(this, CANAL).setSmallIcon(android.R.drawable.ic_lock_idle_lock).setContentTitle(titre).setContentText(texte)
            .setOngoing(true).setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 48201, Intent(this, fr.dd.jarvis.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()

    private fun capteursSurveilles(): Map<String, Systemes.Systeme> = Systemes.liste(app)
        .filter { it.actif && it.viaMms && it.destinataires.isNotEmpty() }
        .flatMap { s -> s.capteurs.map { it to s } }.toMap()

    private fun connecter() {
        val capteurs = capteursSurveilles()
        if (capteurs.isEmpty() || !Serveur.haConfigure(app)) { stopSelf(); return }
        val req = Request.Builder().url(urlWs(app.store.haUrl.value)).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (m.optString("type")) {
                    "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", app.store.haToken.value.trim()).toString())
                    "auth_ok" -> {
                        tentatives = 0
                        webSocket.send(JSONObject().put("id", 1).put("type", "subscribe_trigger").put("trigger",
                            JSONObject().put("platform", "state").put("entity_id", JSONArray(capteurs.keys.toList())).put("to", "on")).toString())
                    }
                    "auth_invalid" -> { stopSelf() }
                    "event" -> {
                        val vers = m.optJSONObject("event")?.optJSONObject("variables")?.optJSONObject("trigger")?.optJSONObject("to_state") ?: return
                        val capteur = vers.optString("entity_id")
                        val nom = vers.optJSONObject("attributes")?.optString("friendly_name")?.ifBlank { null } ?: capteur
                        capteursSurveilles()[capteur]?.let { s -> portee.launch { alerter(s, nom) } }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = reconnecter()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (actif && code != 1000) reconnecter() }
        })
    }

    private fun reconnecter() {
        if (!actif) return
        tentatives++
        portee.launch {
            kotlinx.coroutines.delay((5_000L * tentatives).coerceAtMost(120_000L))
            if (actif) connecter()
        }
    }

    /** Photo brute d'une caméra Home Assistant. */
    private fun photo(camera: String): ByteArray? = runCatching {
        val base = app.store.haUrl.value.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        val req = Request.Builder().url("$base/api/camera_proxy/$camera").header("Authorization", "Bearer ${app.store.haToken.value.trim()}").build()
        OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build().newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    }.getOrNull()

    private suspend fun alerter(s: Systemes.Systeme, capteur: String) {
        val maintenant = System.currentTimeMillis()
        if (maintenant - (derniereAlerte[s.id] ?: 0L) < 120_000L) return
        derniereAlerte[s.id] = maintenant
        val heure = java.time.LocalTime.now().withNano(0).toString()
        val texte = "🚨 ${s.nom} : personne détectée ($capteur) à $heure."
        // Sirène : seulement si l'automatisation Home Assistant ne s'en charge pas déjà.
        if (s.sireneSurDetection && s.sirenes.isNotEmpty() && (!s.viaHa || s.automationHa.isBlank()))
            runCatching { Serveur.ha(app, "POST", "/api/services/siren/turn_on", JSONObject().put("entity_id", JSONArray(s.sirenes))) }
        val dossier = File(getExternalFilesDir(null) ?: filesDir, "securite").apply { mkdirs() }
        val photos = s.cameras.mapNotNull { cam -> photo(cam)?.also { File(dossier, "${cam.substringAfter('.')}_$maintenant.jpg").writeBytes(it) } }
        val numeros = s.destinataires.mapNotNull { fr.dd.jarvis.device.Telephony.resolve(app, it)?.second }
        val comptes = if (photos.isEmpty()) listOf(Mms.envoyer(this, numeros, "$texte (aucune photo disponible)", null))
            else photos.mapIndexed { i, p -> Mms.envoyer(this, numeros, if (i == 0) texte else "📸 Caméra ${i + 1}", p) }
        fr.dd.jarvis.securite.AntiVol.journal(app, "$texte MMS : ${comptes.joinToString(" ; ")}")
        // Notification sur ce téléphone, avec la première photo.
        val b = NotificationCompat.Builder(this, CANAL).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 ${s.nom}").setContentText("Personne détectée : $capteur").setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM).setAutoCancel(true)
        photos.firstOrNull()?.let { p -> BitmapFactory.decodeByteArray(p, 0, p.size)?.let { b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(it)) } }
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("jarvis_alertes_maison", "Alertes de la maison", NotificationManager.IMPORTANCE_HIGH))
        b.setChannelId("jarvis_alertes_maison")
        runCatching { getSystemService(NotificationManager::class.java).notify((maintenant % 100000).toInt(), b.build()) }
    }
}
