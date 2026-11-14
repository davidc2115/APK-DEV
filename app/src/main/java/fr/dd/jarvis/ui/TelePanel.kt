package fr.dd.jarvis.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Tele
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Télé : adresse, type, clé Sony, autorisation, et télécommande directe. */
@Composable
fun TelePanel(app: JarvisApp, onAsk: (String) -> Unit) {
    val s = app.store
    val scope = rememberCoroutineScope()
    var ip by remember { mutableStateOf(s.teleIp.value) }
    var cle by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var occupe by remember { mutableStateOf(false) }
    fun lancer(f: () -> String) { if (occupe) return; occupe = true; msg = "⏳ …"; scope.launch { msg = withContext(Dispatchers.IO) { runCatching { f() }.getOrElse { "❌ ${it.message}" } }; occupe = false } }

    HudPanel(Modifier.fillMaxWidth(), title = "📺 Télé — connexion directe") {
        Text("Pilote la télé et change ses réglages sans Home Assistant. Android TV / Google TV : sur la télé, Paramètres › Système › À propos › 7 appuis sur « Version », " +
            "puis Options pour les développeurs › Débogage réseau (ou USB) activé ; à la première connexion, accepte « Toujours autoriser ». La connexion passe par ton serveur : elle marche aussi quand tu es dehors. " +
            "Sony Bravia : en plus, Paramètres › Réseau › Contrôle IP › Authentification « Normal et clé pré-partagée » pour régler l'image et le son.", color = HudDim, fontSize = 11.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudChip("🔎 Détecter") { lancer { Tele.detecter(app).let { l ->
                if (l.size == 1 && s.teleIp.value.isBlank()) { s.teleIp.set(l[0].ip); s.teleType.set(l[0].type); ip = l[0].ip }
                if (l.isEmpty()) "Aucune télé dans l'inventaire du Wi-Fi : allume-la et analyse le Wi-Fi, ou saisis son adresse." else l.joinToString("\n") { "📺 ${it.nom} — ${it.ip} — ${it.raison}" } } } }
            listOf("androidtv" to "Android/Google TV", "bravia" to "Sony Bravia", "samsung" to "Samsung", "lg" to "LG").forEach { (t, n) ->
                HudChip(n, Tele.type(app) == t) { s.teleType.set(t) }
            }
        }
        HudTextField(ip, { ip = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = "Adresse IP de la télé", placeholder = "192.168.1.40")
        if (ip.trim() != s.teleIp.value) HudButton("Enregistrer l'adresse", Modifier.padding(top = 4.dp)) { s.teleIp.set(ip.trim()); msg = "✅ Adresse enregistrée." }
        if (Tele.type(app) == "bravia") {
            HudTextField(cle, { cle = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = "Clé pré-partagée de la Bravia" + (if (s.teleCle.value.isNotBlank()) " (enregistrée)" else ""), password = true)
            if (cle.isNotBlank()) HudButton("Enregistrer la clé", Modifier.padding(top = 4.dp)) { runCatching { s.teleCle.set(cle.trim()) }; cle = ""; msg = "✅ Clé enregistrée (stockage chiffré)." }
        }
        if (Tele.type(app) in setOf("samsung", "lg")) Text("Samsung / LG : pilotage par Home Assistant (lecteur multimédia de la télé) en attendant la connexion directe.", color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        if (Tele.configuree(app) && Tele.type(app) in setOf("androidtv", "bravia")) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudChip(if (occupe) "⏳" else "🔗 Connecter / autoriser") { lancer { Tele.connecter(app) } }
                HudChip("📸 Capture") { onAsk("Fais une capture de l'écran de la télé") }
                HudChip("⚙️ Réglages") { lancer { Tele.reglages(app, "general") } }
                HudChip("🖼️ Image") { if (Tele.type(app) == "bravia" && s.teleCle.value.isNotBlank()) onAsk("Montre les réglages d'image de la télé") else lancer { Tele.reglages(app, "image") } }
                HudChip("🔊 Son") { lancer { Tele.reglages(app, "son") } }
            }
            Text("Télécommande", color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            listOf(listOf("⏻" to "allumer_eteindre", "🏠" to "accueil", "↩️" to "retour", "📥" to "source"),
                listOf("⬆️" to "haut", "⬇️" to "bas", "⬅️" to "gauche", "➡️" to "droite", "🆗" to "ok"),
                listOf("🔉" to "volume_moins", "🔊" to "volume_plus", "🔇" to "muet", "⏯️" to "lecture_pause", "⚙️" to "reglages")).forEach { ligne ->
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ligne.forEach { (icone, touche) -> HudChip(icone) { lancer { Tele.touche(app, touche) } } }
                }
            }
        }
        if (msg.isNotBlank()) Text(msg, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
