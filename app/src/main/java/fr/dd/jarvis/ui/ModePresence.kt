package fr.dd.jarvis.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.voice.Presence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sin

/** Ce que l'écran de présence affiche (instantané comparé à chaque tour : pas de recomposition inutile). */
private data class VuePresence(val etat: Presence.Etat, val entendu: String, val sousTitre: String)

/** Texte sous le dragon selon l'état (testable). */
internal fun lignePresence(etat: Presence.Etat, entendu: String, sousTitre: String, statut: String, nom: String): String = when (etat) {
    Presence.Etat.ECOUTE -> if (entendu.isNotBlank()) "« $entendu »" else "Je t'écoute…"
    Presence.Etat.REFLEXION -> statut.ifBlank { "Je réfléchis…" }
    Presence.Etat.PAROLE -> sousTitre.ifBlank { "…" }
    Presence.Etat.REPOS -> "Dis « Jarvis » ou touche le dragon" + (if (nom.isNotBlank()) ", $nom" else "") + "."
}

/**
 * Mode présence : JARVIS en plein écran, comme un compagnon posé sur le bureau ou la table de nuit. Grand dragon
 * vivant (bouche synchronisée avec sa voix, anneaux quand il t'écoute), heure, prochain rendez-vous, météo, et
 * sous-titres de ce qu'il dit. L'écran reste allumé ; toucher le dragon pour lui parler, toucher ailleurs pour sortir.
 */
@Composable
fun ModePresence(app: JarvisApp, statut: String, onParler: () -> Unit, onFermer: () -> Unit) {
    val vue = LocalView.current
    DisposableEffect(Unit) { vue.keepScreenOn = true; onDispose { vue.keepScreenOn = false } }

    val etat by produceState(VuePresence(Presence.Etat.REPOS, "", "")) {
        while (true) { value = VuePresence(Presence.etat, Presence.entendu, Presence.sousTitre); delay(120) }
    }
    val maintenant by produceState(LocalDateTime.now()) { while (true) { value = LocalDateTime.now(); delay(10_000) } }
    val infos by produceState("") {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { infosDuJour(app) }.getOrDefault("") }
            delay(10 * 60_000L)
        }
    }
    ModePresenceVue(
        heure = maintenant.format(DateTimeFormatter.ofPattern("HH:mm")),
        date = maintenant.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)).replaceFirstChar { it.uppercase() },
        infos = infos,
        texte = lignePresence(etat.etat, etat.entendu, etat.sousTitre, statut, app.store.userName.value.trim()),
        parle = etat.etat == Presence.Etat.PAROLE, onParler = onParler, onFermer = onFermer,
    )
}

/** Affichage du mode présence (sans dépendance à l'application : prévisualisable). */
@Composable
fun ModePresenceVue(heure: String, date: String, infos: String, texte: String, parle: Boolean, onParler: () -> Unit, onFermer: () -> Unit) {
    val accent = LocalAccent.current
    val temps by produceState(0f) {
        val debut = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - debut) / 1_000_000_000f }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF020108))
    ) {
        // Ciel étoilé qui dérive lentement, lueur qui respire derrière le dragon.
        Canvas(Modifier.fillMaxSize()) {
            val t = temps
            drawCircle(Brush.radialGradient(listOf(Color(0xFF2A0F4A).copy(alpha = 0.55f + 0.15f * sin(t * 0.7f)), Color.Transparent),
                Offset(size.width / 2f, size.height * 0.45f), size.minDimension * 0.75f), size.minDimension * 0.75f, Offset(size.width / 2f, size.height * 0.45f))
            for (i in 0 until 70) {
                val x = ((i * 137.5f + t * (2f + i % 5)) % size.width + size.width) % size.width
                val y = (i * 89.3f) % size.height
                val a = 0.25f + 0.55f * (0.5f + 0.5f * sin(t * (0.6f + i % 7 * 0.2f) + i))
                drawCircle(Color.White.copy(alpha = a * 0.7f), 1f + (i % 3), Offset(x, y))
            }
        }
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(heure, color = accent, fontSize = 64.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light)
            Text(date,
                color = HudDim, fontSize = 15.sp, letterSpacing = 2.sp)
            if (infos.isNotBlank()) Text(infos, color = HudText, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.weight(1f))
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val cote = min(maxWidth * 0.82f, 360.dp)
                Box(Modifier.size(cote).clickable(interactionSource = MutableInteractionSource(), indication = null) { onParler() }) {
                    TeteDragonAnimee(Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(texte, color = if (parle) Color.White else HudText,
                fontSize = if (parle) 20.sp else 16.sp, textAlign = TextAlign.Center,
                maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().height(110.dp))
            Spacer(Modifier.weight(1f))
            Text("QUITTER LE MODE PRÉSENCE", color = HudDim.copy(alpha = 0.72f), fontSize = 11.sp,
                modifier = Modifier.clickable(interactionSource = MutableInteractionSource(), indication = null) { onFermer() }.padding(10.dp))
        }
    }
}

/** Prochain rendez-vous du jour et météo, en une ou deux lignes. */
private suspend fun infosDuJour(app: JarvisApp): String {
    val maintenant = System.currentTimeMillis()
    val prochain = runCatching { app.calendar.events(LocalDate.now(), 1) }.getOrDefault(emptyList())
        .filter { !it.allDay && it.end > maintenant }.minByOrNull { it.begin }
    val rdv = prochain?.let {
        "📅 " + java.time.Instant.ofEpochMilli(it.begin).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")) + " · " + it.title.take(40)
    }
    val meteo = runCatching { kotlinx.coroutines.withTimeoutOrNull(6000) { fr.dd.jarvis.ai.Meteo.actuel(app) } }.getOrNull()?.let { "${it.first} ${it.second}" }
    return listOfNotNull(rdv, meteo).joinToString("   ")
}

/**
 * Déclenchement automatique du mode présence : téléphone EN CHARGE et IMMOBILE (posé), sans toucher l'écran depuis
 * [delaiMs]. S'il a été ouvert automatiquement ([auto]), le reprendre en main (téléphone incliné de plus de 35° par
 * rapport à sa position posée) le referme ; ouvert à la main, il ne se ferme qu'en touchant l'écran ou par Retour.
 * Le capteur ne tourne que pendant que JARVIS est à l'écran.
 */
@Composable
fun PresenceAuto(actif: Boolean, dernierToucher: () -> Long, ouvert: Boolean, auto: Boolean, onOuvrir: () -> Unit, onFermer: () -> Unit, delaiMs: Long = 45_000) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(actif, ouvert, auto) {
        if (!actif || (ouvert && !auto)) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            val immobile = Immobilite(ctx)
            immobile.demarrer()
            try {
                if (ouvert) {
                    // Position « posée » de référence, puis surveillance : pris en main = forte inclinaison qui dure.
                    delay(1500)
                    val reference = immobile.gravite.copyOf()
                    var depuis = 0L
                    while (true) {
                        delay(300)
                        if (inclinaison(reference, immobile.gravite) > 35f) {
                            if (depuis == 0L) depuis = System.currentTimeMillis()
                            else if (System.currentTimeMillis() - depuis > 600) { onFermer(); break }
                        } else depuis = 0L
                    }
                } else while (true) {
                    delay(2000)
                    val calme = System.currentTimeMillis() - maxOf(immobile.dernierMouvement, dernierToucher())
                    if (enCharge(ctx) && calme > delaiMs) { onOuvrir(); break }
                }
            } finally { immobile.arreter() }
        }
    }
}

/** Angle (degrés) entre deux directions de la gravité : de combien le téléphone a été incliné (testable). */
internal fun inclinaison(a: FloatArray, b: FloatArray): Float {
    val na = kotlin.math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); val nb = kotlin.math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
    if (na < 1f || nb < 1f) return 0f
    val c = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)).coerceIn(-1f, 1f)
    return Math.toDegrees(kotlin.math.acos(c).toDouble()).toFloat()
}

private fun enCharge(ctx: Context): Boolean {
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
    val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    return st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL || i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
}

/** Détecte les mouvements du téléphone (accéléromètre) : écart à la gravité au-delà d'un petit seuil. */
private class Immobilite(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    @Volatile var dernierMouvement = System.currentTimeMillis()
    private var precedent: FloatArray? = null
    /** Direction de la gravité, lissée (filtre passe-bas). */
    @Volatile var gravite = floatArrayOf(0f, 0f, 9.81f)
    private var initialisee = false

    fun demarrer() {
        val a = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, a, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun arreter() { runCatching { sm?.unregisterListener(this) } }

    override fun onSensorChanged(e: SensorEvent) {
        val p = precedent
        if (p != null && bouge(p, e.values)) dernierMouvement = System.currentTimeMillis()
        precedent = e.values.copyOf()
        val g = gravite.copyOf()
        val k = if (initialisee) 0.15f else 1f
        for (i in 0..2) g[i] += (e.values[i] - g[i]) * k
        gravite = g; initialisee = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        fun bouge(a: FloatArray, b: FloatArray) = bougeAccel(a, b)
    }
}

/** Mouvement du téléphone si l'accélération varie de plus de 0,35 m/s² entre deux mesures (testable). */
internal fun bougeAccel(a: FloatArray, b: FloatArray): Boolean =
    kotlin.math.abs(a[0] - b[0]) + kotlin.math.abs(a[1] - b[1]) + kotlin.math.abs(a[2] - b[2]) > 0.35f
