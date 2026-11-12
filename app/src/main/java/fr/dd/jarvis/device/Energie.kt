package fr.dd.jarvis.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.dd.jarvis.JarvisApp

/**
 * Économie d'énergie de JARVIS. Trois réglages (Réglages › Général › Batterie & vitesse) :
 *  - « performance » : privilégie la réactivité et les traitements internes ;
 *  - « balanced » (défaut, et ancien « auto ») : adapte l'activité à la batterie et à l'état énergétique Android ;
 *  - « eco » : réduit les travaux secondaires, sans désactiver l'écoute ni les commandes.
 * Android conserve la maîtrise des fréquences CPU, de la RAM et des limites thermiques : JARVIS optimise uniquement
 * ses propres tâches et ne tente jamais de contourner le système.
 */
object Energie {
    const val SEUIL_BATTERIE = 20

    /** Vrai quand JARVIS économise la batterie (observé par l'interface). */
    var economie by mutableStateOf(false)
        private set
    var batterie by mutableStateOf(-1)
        private set
    var enCharge by mutableStateOf(false)
        private set
    var economiseurAndroid by mutableStateOf(false)
        private set
    var contrainteThermique by mutableStateOf(false)
        private set

    @Volatile private var suivi = false

    /** Décision pure (testable). */
    internal fun decider(mode: String, economiseur: Boolean, niveau: Int, charge: Boolean, thermique: Boolean = false): Boolean = when (mode) {
        "performance" -> false
        "eco" -> true
        else -> economiseur || thermique || (!charge && niveau in 0..SEUIL_BATTERIE)
    }

    fun maj(ctx: Context, intentBatterie: Intent? = null) {
        val app = ctx.applicationContext as? JarvisApp ?: return
        val i = intentBatterie ?: runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        if (i != null) {
            val niveau = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val echelle = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            batterie = if (niveau < 0) -1 else niveau * 100 / echelle
            val statut = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            enCharge = statut == BatteryManager.BATTERY_STATUS_CHARGING || statut == BatteryManager.BATTERY_STATUS_FULL
        }
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        economiseurAndroid = runCatching { pm.isPowerSaveMode }.getOrDefault(false)
        contrainteThermique = if (Build.VERSION.SDK_INT >= 29) runCatching {
            pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        }.getOrDefault(false) else false
        economie = decider(app.store.modeEnergie.value, economiseurAndroid, batterie, enCharge, contrainteThermique)
    }

    /** Suit la batterie et l'économiseur d'Android (appelé au démarrage de l'appli, sans coût : diffusions système). */
    fun suivre(ctx: Context) {
        if (suivi) return
        suivi = true
        val app = ctx.applicationContext
        maj(app)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) maj(c, intent) else maj(c)
            }
        }
        runCatching {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, f)
        }
    }

    /** Applique le profil aux travaux périodiques JARVIS sans toucher aux réglages énergétiques Android. */
    fun appliquerMode(ctx: Context) {
        val app = ctx.applicationContext as? JarvisApp ?: return
        maj(ctx)
        runCatching { fr.dd.jarvis.work.AgentsScheduler.apply(ctx, app.store) }
        runCatching { fr.dd.jarvis.work.ReseauScheduler.apply(ctx, app.store) }
        runCatching { fr.dd.jarvis.work.VeilleWorker.planifier(ctx) }
    }

    /** Hors interface (workers) : état à jour sans dépendre du suivi. */
    fun economieMaintenant(ctx: Context): Boolean { maj(ctx); return economie }

    fun libelle(app: JarvisApp): String = when {
        app.store.modeEnergie.value == "performance" -> "⚡ Performance · priorité à la réactivité"
        app.store.modeEnergie.value == "eco" -> "🔋 Économie · tâches secondaires réduites"
        economie && contrainteThermique -> "🌡️ Équilibré · activité réduite pour la température"
        economie && economiseurAndroid -> "🔋 Équilibré · économiseur Android actif"
        economie -> "🔋 Équilibré · batterie à $batterie %"
        else -> "⚖️ Équilibré · ressources disponibles" + (if (batterie >= 0) " · batterie $batterie %" + (if (enCharge) " en charge" else "") else "")
    }
}
