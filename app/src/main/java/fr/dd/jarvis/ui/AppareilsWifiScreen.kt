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

/** Sous-onglet affiché (« Liés à Jarvis » demandé depuis un lien de la conversation). */
object OngletWifi { val lies = androidx.compose.runtime.mutableStateOf(false) }

/**
 * Onglet « Wi-Fi », en deux sous-onglets : « Réseau Wi-Fi » (tous les appareils du réseau) et « Liés à Jarvis » (les
 * appareils et services que JARVIS pilote, avec la façon de les joindre en local et à distance).
 */
@Composable
fun AppareilsWifiScreen(app: JarvisApp, onDemander: (String) -> Unit) {
    val lies by OngletWifi.lies
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudChip("📡 Réseau Wi-Fi", !lies) { OngletWifi.lies.value = false }
            HudChip("🔗 Liés à Jarvis", lies) { OngletWifi.lies.value = true }
        }
        if (lies) LiesAJarvis(app, onDemander) else ReseauWifi(app, onDemander)
    }
}

/**
 * Sous-onglet « Liés à Jarvis » : Home Assistant, Plex, Freebox, serveur, imprimantes, appareils ouverts à distance…
 * avec l'adresse utilisée à la maison (local) et dehors (à distance), et un test de chaque chemin.
 */
@Composable
private fun LiesAJarvis(app: JarvisApp, onDemander: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val liens = remember(tick) { fr.dd.jarvis.ai.AppareilsLies.liste(app) }
    val situation = remember(tick) { runCatching { fr.dd.jarvis.ai.Serveur.situation(app) }.getOrDefault("") }
    // Résultat des tests : clé « id/local » ou « id/distant » → true (répond), false (ne répond pas), null (en cours).
    val tests = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean?>() }
    var enTest by remember { mutableStateOf(false) }

    fun tester() {
        if (enTest) return
        enTest = true; tests.clear()
        scope.launch {
            val travaux = liens.flatMap { l -> listOfNotNull(l.testLocal?.let { "${l.id}/local" to it }, l.testDistant?.let { "${l.id}/distant" to it }) }
            travaux.forEach { (k, _) -> tests[k] = null }
            kotlinx.coroutines.coroutineScope {
                travaux.forEach { (k, adr) -> launch(Dispatchers.IO) { val ok = fr.dd.jarvis.ai.AppareilsLies.repond(adr); withContext(Dispatchers.Main) { tests[k] = ok } } }
            }
            enTest = false
        }
    }
    LaunchedEffect(Unit) { tester() }

    fun pastille(k: String): String = if (!tests.containsKey(k)) "" else when (tests[k]) { true -> " 🟢"; false -> " 🔴"; null -> " ⏳" }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "🔗 Appareils liés à Jarvis") {
                Text("Ce que JARVIS pilote directement, et comment il le joint : 🏠 en local quand le téléphone est sur le Wi-Fi de la maison, " +
                    "🌍 à distance quand tu es dehors. 🟢 répond · 🔴 ne répond pas d'ici · ⏳ test en cours.", color = HudDim, fontSize = 11.sp)
                if (situation.isNotBlank()) Text("📍 $situation", color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton(if (enTest) "Test…" else "🔄 Tester les connexions", Modifier.weight(1f), enabled = !enTest) { tick++; tester() }
                    HudButton("💬 Demander", Modifier.weight(1f)) { onDemander("Quels appareils contrôles-tu, en local et à distance ?") }
                }
            }
        }
        items(liens, key = { it.id }) { l ->
            HudPanel(Modifier.fillMaxWidth()) {
                Text("${l.emoji} ${l.nom}" + (if (!l.configure) " · ⚙️ à configurer" else ""), color = HudText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(l.role, color = HudDim, fontSize = 11.sp)
                Text("🏠 Local : ${l.local}" + pastille("${l.id}/local"), color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Text("🌍 À distance : ${l.distant}" + pastille("${l.id}/distant"), color = HudText, fontSize = 12.sp)
                if (!l.configure && l.reglage.isNotBlank()) Text("⚙️ ${l.reglage}", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                if (l.configure && l.testDistant == null && l.id in setOf("ha")) Row(Modifier.padding(top = 6.dp)) {
                    HudButton("🌍 Rendre accessible à distance", Modifier.weight(1f)) { onDemander("Rends ${l.nom} accessible à distance") }
                }
            }
        }
    }
}

/**
 * Sous-onglet « Réseau Wi-Fi » : tous les appareils du réseau Wi-Fi (relevés automatiquement à chaque connexion et
 * régulièrement), avec leur nom, leur adresse, leurs services ; un appui pour les nommer et pour les rendre joignables
 * à distance (JARVIS réserve l'IP, ouvre le port sur la Freebox et donne l'adresse publique).
 */
@Composable
private fun ReseauWifi(app: JarvisApp, onDemander: (String) -> Unit) {
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
