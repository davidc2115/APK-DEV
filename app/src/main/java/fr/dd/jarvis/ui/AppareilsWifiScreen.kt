package fr.dd.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.AccesDistant
import fr.dd.jarvis.ai.Registre
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Onglet « Wi-Fi » : tous les appareils du réseau Wi-Fi (relevés automatiquement à chaque connexion et régulièrement),
 * avec leur nom, leur adresse, leurs services ; un appui pour les nommer et pour les rendre joignables à distance
 * (JARVIS réserve l'IP, ouvre le port sur la Freebox et donne l'adresse publique).
 */
@Composable
fun AppareilsWifiScreen(app: JarvisApp, onDemander: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var etat by remember { mutableStateOf("") }
    var occupe by remember { mutableStateOf(false) }
    var ouvert by remember { mutableStateOf("") }
    var nomEdite by remember { mutableStateOf("") }
    val appareils = remember(tick) { Registre.tous(app) }
    val ipPublique = remember(tick) { Registre.derniereIpPublique(app) }
    val maintenant = System.currentTimeMillis()

    fun analyser() {
        if (occupe) return
        occupe = true; etat = "⏳ Analyse du Wi-Fi…"
        scope.launch {
            etat = withContext(Dispatchers.IO) { runCatching { AccesDistant.inventorier(app) { s -> etat = s } }.getOrElse { "❌ ${it.message}" } }
            occupe = false; tick++
        }
    }
    // Ouvert : inventaire relancé si le dernier relevé date de plus de 10 minutes.
    LaunchedEffect(Unit) { if (appareils.none { maintenant - it.optLong("vu") < 10 * 60_000 }) analyser() }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "📡 Appareils Wi-Fi") {
                Text("Relevés automatiquement à chaque connexion au Wi-Fi et régulièrement. Touche un appareil pour le nommer ou le rendre accessible à distance : " +
                    "JARVIS réserve son adresse IP, ouvre le port sur la Freebox et te donne l'adresse publique.", color = HudDim, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Text("🌍 IP publique : ${ipPublique.ifBlank { "pas encore relevée" }}" + (if (app.store.freeboxAppToken.value.isBlank()) " · ⚠️ Freebox non associée (Réglages › Maison › Box)" else " · Freebox associée ✅"),
                    color = HudText, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton(if (occupe) "Analyse…" else "🔄 Analyser maintenant", Modifier.weight(1f), enabled = !occupe) { analyser() }
                    HudButton("💬 Demander", Modifier.weight(1f)) { onDemander("Quels appareils sont sur mon Wi-Fi et lesquels sont accessibles à distance ?") }
                }
                if (etat.isNotBlank()) Text(etat, color = HudText, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        val enLigne = appareils.filter { maintenant - it.optLong("vu") < 30 * 60_000 }
        val autres = appareils - enLigne.toSet()
        if (appareils.isEmpty()) item { Text("Aucun appareil encore relevé : touche « Analyser maintenant » (en Wi-Fi).", color = HudDim, fontSize = 12.sp) }
        if (enLigne.isNotEmpty()) item { Text("🟢 En ligne (${enLigne.size})", color = HudText, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        items(enLigne, key = { "on" + it.optString("ip") }) { e -> Carte(app, e, true, ouvert == e.optString("ip"), { ouvert = if (ouvert == e.optString("ip")) "" else e.optString("ip"); nomEdite = e.optString("alias") },
            nomEdite, { nomEdite = it }, onDemander) { tick++ } }
        if (autres.isNotEmpty()) item { Text("⚪ Déjà vus (${autres.size})", color = HudText, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        items(autres, key = { "off" + it.optString("ip") }) { e -> Carte(app, e, false, ouvert == e.optString("ip"), { ouvert = if (ouvert == e.optString("ip")) "" else e.optString("ip"); nomEdite = e.optString("alias") },
            nomEdite, { nomEdite = it }, onDemander) { tick++ } }
    }
}

@Composable
private fun Carte(app: JarvisApp, e: JSONObject, enLigne: Boolean, deplie: Boolean, onToucher: () -> Unit, nom: String, onNom: (String) -> Unit,
                  onDemander: (String) -> Unit, onMaj: () -> Unit) {
    val ip = e.optString("ip")
    val ports = e.optJSONArray("ports")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
    val distant = e.optJSONArray("distant")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
    val vu = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRENCH).format(java.util.Date(e.optLong("vu")))
    HudPanel(Modifier.fillMaxWidth(), onClick = onToucher) {
        Text((if (enLigne) "🟢 " else "⚪ ") + Registre.nomDe(e), color = HudText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("$ip · ${e.optString("type")}" + (if (e.optString("mac").isNotBlank()) " · ${e.optString("mac")}" else "") + " · vu le $vu", color = HudDim, fontSize = 11.sp)
        if (ports.isNotEmpty()) Text("Services : " + ports.joinToString { "${AccesDistant.service(it)} ($it)" }, color = HudDim, fontSize = 11.sp)
        distant.forEach { d -> Text("🌍 ${d.optString("service")} : ${d.optString("adresse").ifBlank { "port ${d.optInt("externe")}" }}", color = HudText, fontSize = 12.sp) }
        if (deplie) Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudTextField(nom, onNom, Modifier.fillMaxWidth(), label = "Nom de l'appareil (ex. caméra du garage, télé du salon)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton("✏️ Nommer", Modifier.weight(1f)) { if (nom.isNotBlank() && Registre.nommer(app, ip, nom)) onMaj() }
                HudButton(if (distant.isEmpty()) "🌍 Accès à distance" else "🔒 Fermer l'accès", Modifier.weight(1f)) {
                    // Passe par JARVIS : confirmation, bail fixe, redirection Freebox et adresse publique, avec le compte rendu.
                    onDemander(if (distant.isEmpty()) "Rends l'appareil $ip (${Registre.nomDe(e)}) accessible à distance" else "Ferme l'accès à distance de l'appareil $ip (${Registre.nomDe(e)})")
                }
            }
            if (ports.size > 1 && distant.isEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ports.take(4).forEach { p -> HudChip("${AccesDistant.service(p)}", false) { onDemander("Rends le service ${AccesDistant.service(p)} (port $p) de l'appareil $ip accessible à distance") } }
            }
        }
    }
}
