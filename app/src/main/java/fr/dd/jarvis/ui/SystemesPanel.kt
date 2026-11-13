package fr.dd.jarvis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.securite.Systemes
import fr.dd.jarvis.securite.TelephoneSecurise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Modes de JARVIS (Réglages › Sécurité › Systèmes et Agents › Modes) : liste, activer / désactiver, modifier, supprimer, codes chiffrés. */
@Composable
fun SystemesPanel(app: JarvisApp, onAsk: (String) -> Unit) {
    val store = app.store
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf("") }
    var occupe by remember { mutableStateOf(false) }
    var ouvert by remember { mutableStateOf("") }
    var aSupprimer by remember { mutableStateOf("") }
    val systemes = remember(tick) { Systemes.liste(app) }

    HudPanel(Modifier.fillMaxWidth(), title = "🧩 Modes à la demande") {
        Text("Crée, modifie et supprime tes modes simplement en le demandant à JARVIS (à la voix ou par écrit) : « crée un mode cinéma qui ferme " +
            "les volets du salon et baisse les lumières », « dans le mode nuit, ajoute la lumière du couloir », « installe le mode maison sécurisée », " +
            "« ajoute le numéro de Marie au mode maison sécurisée », « supprime le mode cinéma »… Puis « active le mode cinéma ».", color = HudDim, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton("📱 Téléphone sécurisé", Modifier.weight(1f)) { onAsk("Installe le mode téléphone sécurisé") }
            HudButton("🏠 Maison sécurisée", Modifier.weight(1f)) { onAsk("Installe le mode maison sécurisée") }
        }
        Spacer(Modifier.height(6.dp))
        HudButton("➕ Nouveau mode (décris-le à Jarvis)", Modifier.fillMaxWidth()) { onAsk("Je voudrais créer un nouveau mode. Demande-moi ce qu'il doit faire.") }
        Spacer(Modifier.height(10.dp))
        if (systemes.isEmpty()) Text("Aucun mode créé pour l'instant.", color = HudText, fontSize = 12.sp)
        systemes.forEach { s ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text((if (s.actif) "🟢 " else "⚪ ") + s.nom + (if (s.actif) " — actif" else ""), color = HudText, fontSize = 13.sp,
                    modifier = Modifier.clickable { ouvert = if (ouvert == s.id) "" else s.id })
                if (ouvert == s.id) Text(Systemes.presenter(s).replace("## ", ""), color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    HudButton(if (s.actif) "⏹️ Désactiver" else "▶️ Activer", Modifier.weight(1f), enabled = !occupe) {
                        scope.launch {
                            occupe = true; msg = "⏳"
                            msg = withContext(Dispatchers.IO) { runCatching { if (s.actif) Systemes.desactiver(app, s) else Systemes.activer(app, s) }.getOrElse { "❌ ${it.message}" } }
                            occupe = false; tick++
                        }
                    }
                    HudButton(if (ouvert == s.id) "Masquer" else "Détails", Modifier.weight(1f)) { ouvert = if (ouvert == s.id) "" else s.id }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    HudButton("✏️ Modifier", Modifier.weight(1f)) { onAsk("Je voudrais modifier le mode « ${s.nom} ». Demande-moi ce qu'il faut changer.") }
                    HudButton(if (aSupprimer == s.id) "Confirmer ?" else "🗑️ Supprimer", Modifier.weight(1f), enabled = !occupe) {
                        if (aSupprimer != s.id) aSupprimer = s.id else scope.launch {
                            occupe = true
                            msg = withContext(Dispatchers.IO) { runCatching {
                                if (s.actif) Systemes.desactiver(app, s)
                                if (s.automationHa.isNotBlank()) runCatching { fr.dd.jarvis.ai.Serveur.ha(app, "POST", "/api/services/automation/turn_off", org.json.JSONObject().put("entity_id", s.automationHa)) }
                                Systemes.retirer(app, s.id); "🗑️ « ${s.nom} » supprimé."
                            }.getOrElse { "❌ ${it.message}" } }
                            aSupprimer = ""; occupe = false; tick++
                        }
                    }
                }
            }
        }
        if (msg.isNotBlank()) Text(msg, color = HudText, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        Text("🔢 Codes (stockage chiffré du téléphone, jamais envoyés à l'IA)", color = HudText, fontSize = 13.sp)
        if (!store.codeDeverrouillage.available) Text("⚠️ Stockage chiffré indisponible : les codes ne peuvent pas être enregistrés.", color = HudDim, fontSize = 11.sp)
        else {
            val enregistre = store.codeDeverrouillage.value
            var typeSchema by remember { mutableStateOf(enregistre.startsWith("schema:")) }
            var code by remember { mutableStateOf(if (enregistre.startsWith("schema:")) "" else enregistre) }
            var schema by remember { mutableStateOf(enregistre.removePrefix("schema:").takeIf { enregistre.startsWith("schema:") }.orEmpty()) }
            Text("Verrouillage de l'écran du téléphone :", color = HudDim, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudChip("🔢 Code / mot de passe", !typeSchema) { typeSchema = false }
                HudChip("✳️ Schéma", typeSchema) { typeSchema = true }
            }
            if (!typeSchema) HudTextField(code, { code = it.trim() }, Modifier.fillMaxWidth(), label = "Code de l'écran de verrouillage (déverrouillage à la voix)", password = true)
            else {
                HudTextField(schema, { schema = it.filter { c -> c in '1'..'9' } }, Modifier.fillMaxWidth(), label = "Schéma : points dans l'ordre du tracé (ex. 1-5-9-6)", password = true)
                Text("Points numérotés comme un clavier de téléphone :  1 2 3  /  4 5 6  /  7 8 9  (en haut à gauche = 1).", color = HudDim, fontSize = 11.sp)
            }
            var alarme by remember { mutableStateOf(store.codeAlarmeHa.value) }
            HudTextField(alarme, { alarme = it.trim() }, Modifier.fillMaxWidth(), label = "Code de l'alarme Home Assistant (si elle en demande un)", password = true)
            var cleHa by remember { mutableStateOf(store.cleSauvegardeHa.value) }
            HudTextField(cleHa, { cleHa = it.trim() }, Modifier.fillMaxWidth(), label = "Clé de chiffrement des sauvegardes Home Assistant (restauration)", password = true)
            HudButton("Enregistrer les codes", Modifier.fillMaxWidth()) {
                msg = when {
                    !typeSchema && code.isNotBlank() && !TelephoneSecurise.codeValide(code) -> "⚠️ Code d'écran : 4 à 32 chiffres ou lettres, sans espace."
                    typeSchema && schema.isNotBlank() && TelephoneSecurise.schema("schema:$schema") == null -> "⚠️ Schéma : au moins 4 points différents, de 1 à 9."
                    else -> runCatching {
                        store.codeDeverrouillage.set(if (typeSchema) (if (schema.isBlank()) "" else "schema:$schema") else code)
                        store.codeAlarmeHa.set(alarme); store.cleSauvegardeHa.set(cleHa); "✅ Codes enregistrés."
                    }.getOrElse { "❌ ${it.message}" }
                }
            }
            Text("Le déverrouillage à la voix demande aussi ton empreinte vocale (Sécurité › Empreinte vocale) et l'ADB intégré ou Shizuku " +
                "(Téléphone › Contrôle). Jarvis tape alors ce code ou trace ce schéma à ta place, uniquement après avoir reconnu ta voix.", color = HudDim, fontSize = 11.sp)
        }
    }
}
