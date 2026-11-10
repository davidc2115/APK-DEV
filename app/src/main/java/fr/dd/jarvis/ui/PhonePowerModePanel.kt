package fr.dd.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.launch

@Composable
fun PhonePowerModePanel(app: JarvisApp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val batterySaver = remember(refresh) { fr.dd.jarvis.device.PhonePowerMode.economieActive(ctx) }
    val shellReady = remember(refresh) { fr.dd.jarvis.device.PhoneShell.ready(ctx) }
    val accessibilityReady = remember(refresh) { app.phone.accessibilityEnabled() }
    HudPanel(Modifier.fillMaxWidth(), title = "⚡ Modes d'alimentation du téléphone") {
        Text(
            "Contrôle les vrais modes du Xiaomi/HyperOS : économie, équilibré et performance. Ce réglage agit sur tout le téléphone, pas seulement sur JARVIS.",
            color = HudDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(6.dp))
        PowerStatusRow("Économiseur Android", batterySaver, if (batterySaver) "actif" else "inactif")
        PowerStatusRow("ADB / Shizuku", shellReady, if (shellReady) "prêt" else "non connecté")
        PowerStatusRow("Accessibilité JARVIS", accessibilityReady, if (accessibilityReady) "connectée" else "non active")
        Text(
            "État détecté : " + fr.dd.jarvis.device.PhonePowerMode.etat(ctx),
            color = HudText, fontSize = 11.sp, modifier = Modifier.height(22.dp)
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            HudButton(if (busy) "…" else "🔋 Économie", enabled = !busy, filled = batterySaver) {
                busy = true
                scope.launch {
                    message = fr.dd.jarvis.device.PhonePowerMode.appliquer(app, "economie")
                    busy = false
                    refresh++
                }
            }
            HudButton(if (busy) "…" else "⚖️ Équilibré", enabled = !busy) {
                busy = true
                scope.launch {
                    message = fr.dd.jarvis.device.PhonePowerMode.appliquer(app, "equilibre")
                    busy = false
                    refresh++
                }
            }
            HudButton(if (busy) "…" else "🚀 Performance", enabled = !busy) {
                busy = true
                scope.launch {
                    message = fr.dd.jarvis.device.PhonePowerMode.appliquer(app, "performance")
                    busy = false
                    refresh++
                }
            }
            HudButton("Actualiser", enabled = !busy) { refresh++ }
        }
        if (message.isNotBlank()) Text(message, color = HudText, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
        Text(
            "Économie est appliquée directement par ADB/Shizuku quand disponible. Équilibré et Performance utilisent le sélecteur HyperOS ; JARVIS peut le choisir automatiquement si l'accessibilité est active et qu'une clé Gemini est configurée. Sinon, l'écran s'ouvre pour une sélection manuelle. Aucun réglage CPU/GPU caché ni root.",
            color = HudDim, fontSize = 11.sp
        )
    }
}

@Composable
private fun PowerStatusRow(label: String, active: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text((if (active) "🟢 " else "⚪ ") + label, color = HudText, fontSize = 12.sp)
        Text(detail, color = HudDim, fontSize = 11.sp)
    }
}
