package fr.dd.jarvis.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.dd.jarvis.JarvisApp

/**
 * Économie d'énergie de JARVIS. Trois réglages (Réglages › Général › Batterie & vitesse) :
 *  - « auto » (défaut) : économie dès que l'économiseur de batterie d'Android est actif, ou sous 20 % hors charge ;
 *  - « performance » : jamais d'économie ;
 *  - « eco » : toujours en économie.
 * En économie : animations ralenties, synchronisations et relevés de fond espacés. Les fonctions restent toutes là
 * (écoute « Jarvis », rappels, anti-vol, agents) : seules les dépenses invisibles sont réduites.
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

    @Volatile private var suivi = false

    /** Décision pure (testable). */
    internal fun decider(mode: String, economiseur: Boolean, niveau: Int, charge: Boolean): Boolean = when (mode) {
        "performance" -> false
        "eco" -> true
        else -> economiseur || (!charge && niveau in 0..SEUIL_BATTERIE)
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
        economiseurAndroid = runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode }.getOrDefault(false)
        economie = decider(app.store.modeEnergie.value, economiseurAndroid, batterie, enCharge)
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

    /** Hors interface (workers) : état à jour sans dépendre du suivi. */
    fun economieMaintenant(ctx: Context): Boolean { maj(ctx); return economie }

    fun libelle(app: JarvisApp): String = when {
        app.store.modeEnergie.value == "performance" -> "⚡ Performance : aucune économie"
        economie && app.store.modeEnergie.value == "eco" -> "🔋 Économie forcée"
        economie && economiseurAndroid -> "🔋 Économie (économiseur de batterie d'Android actif)"
        economie -> "🔋 Économie (batterie à $batterie %, hors charge)"
        else -> "✅ Normal" + (if (batterie >= 0) " · batterie $batterie %" + (if (enCharge) " en charge" else "") else "")
    }
}
