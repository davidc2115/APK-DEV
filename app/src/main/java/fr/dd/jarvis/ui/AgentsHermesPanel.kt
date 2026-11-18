package fr.dd.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.hermes.Hermes
import fr.dd.jarvis.hermes.PontMcp

/**
 * Panneau commun au Graphe et à l'Orbe : état réel du cerveau Hermès, du pont Jarvis et des automatismes.
 * Les sous-agents Hermès sont décrits comme une capacité de délégation à la demande, jamais comme des processus
 * permanents fictifs. Les agents d'arrière-plan reflètent les interrupteurs réellement configurés dans Store.
 */
@Composable
fun AgentsHermesButton(app: JarvisApp) {
    val store = app.store
    val global = store.agentsOn.value
    val mail = store.agentMails.value
    val factures = store.agentFactures.value
    val planning = store.agentPlanning.value
    val notifs = store.agentNotifs.value
    val ranger = store.agentRanger.value
    val actifs = if (global) listOf(mail, factures, planning, notifs).count { it } else 0
    val total = 4
    var ouvert by remember { mutableStateOf(false) }
    var hermesEnLigne by remember { mutableStateOf(Hermes.enLigneConnu()) }
    LaunchedEffect(Unit) { hermesEnLigne = Hermes.enLigne(force = true) }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF120A20))
            .border(1.dp, Neon.violet.copy(alpha = 0.8f), RoundedCornerShape(50))
            .clickable { ouvert = true }
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("IA", color = Neon.lavande, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("$actifs/$total", color = Neon.blancViolet, fontSize = 10.sp)
        Text(if (hermesEnLigne) "●" else "○", color = if (hermesEnLigne) Color(0xFF78D99A) else Neon.sousTitre, fontSize = 9.sp)
    }

    if (ouvert) {
        val mode = when (store.hermesMode.value) {
            "tout" -> "Hermès est le cerveau principal de Jarvis"
            "demande" -> "Hermès intervient lorsqu'il est demandé"
            "off" -> "Hermès est désactivé ; Jarvis utilise son moteur de secours"
            else -> store.hermesMode.value
        }
        val historique = store.agentRunHistory.value.lines().filter { it.isNotBlank() }.takeLast(5).asReversed()
        AlertDialog(
            onDismissRequest = { ouvert = false },
            containerColor = Color(0xFF0D0717),
            titleContentColor = Neon.lavande,
            textContentColor = Neon.blancViolet,
            title = { Text("Hermès · Jarvis · agents", fontFamily = Neon.serif, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text("CERVEAU CENTRAL", color = Neon.violet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("Hermès : ${if (hermesEnLigne) "passerelle en ligne" else "passerelle hors ligne ou non joignable"}", fontSize = 12.sp)
                    Text(mode, color = Neon.sousTitre, fontSize = 11.sp)
                    Text("Pont MCP Jarvis : ${if (PontMcp.enService()) "actif sur le téléphone" else "non actif actuellement"}", fontSize = 11.sp)
                    Text("Ce pont donne à Hermès accès aux outils Jarvis autorisés : téléphone, coffre Obsidian, agenda, mails, Société, domotique, Freebox et fichiers.", color = Neon.sousTitre, fontSize = 11.sp)

                    Spacer(Modifier.height(3.dp))
                    Text("SOUS-AGENTS HERMÈS", color = Neon.violet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("Hermès peut déléguer une mission longue ou indépendante à des sous-agents à la demande. Ils passent par le routeur IA de Jarvis (fournisseurs configurés, quotas et replis communs) et peuvent utiliser les outils du pont MCP. Ils ne sont pas présentés comme actifs en permanence : leur présence dépend des missions réellement lancées.", fontSize = 11.sp)

                    Spacer(Modifier.height(3.dp))
                    Text("AGENTS AUTOMATIQUES JARVIS", color = Neon.violet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    AgentHermesLigne("Courriels", global && mail,
                        "Repère les nouveaux mails, classe leur priorité et signale les urgences. Les mails non lus restent dans la boîte de réception.")
                    AgentHermesLigne("Tri des mails lus", global && mail && ranger,
                        "Range les messages déjà lus dans les libellés configurés ; ce traitement dépend de l'agent Courriels.")
                    AgentHermesLigne("Factures et devis", global && factures,
                        "Surveille les factures en retard ou proches de l'échéance, les devis à relancer et les maintenances à venir.")
                    AgentHermesLigne("Planning et tâches", global && planning,
                        "Suit les rendez-vous, prépare les informations de déplacement et gère les tâches personnelles enregistrées.")
                    AgentHermesLigne("Notifications", global && notifs,
                        "Analyse les notifications du téléphone lorsque l'accès Android correspondant est accordé.")
                    Text("Les agents automatiques sont ${if (global) "autorisés globalement" else "suspendus globalement"} ; les lignes indiquent aussi leurs interrupteurs individuels.", color = Neon.sousTitre, fontSize = 10.sp)

                    Spacer(Modifier.height(3.dp))
                    Text("SURVEILLANCE DU TÉLÉPHONE", color = Neon.violet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("PhoneHealthWorker collecte séparément les mesures locales accessibles (batterie, stockage, mémoire, réseau et état des autorisations). Ce suivi n'est pas un agent IA et reste indépendant de l'interrupteur des agents.", fontSize = 11.sp)

                    Spacer(Modifier.height(3.dp))
                    Text("MÉMOIRE ET JOURNAL OBSIDIAN", color = Neon.violet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("Les cycles des agents écrivent leurs comptes rendus dans JARVIS/Journal, dans le coffre Obsidian configuré. Hermès et Jarvis partagent leurs capacités et leur mémoire via le pont local ; les notes restent dans le coffre choisi.", fontSize = 11.sp)
                    Text("Dernier cycle : ${store.agentLastRunAt.value.ifBlank { "aucun" }} · ${store.agentLastRunStatus.value}", color = Neon.sousTitre, fontSize = 10.sp)
                    if (historique.isNotEmpty()) {
                        Text("Historique récent", color = Neon.lavande, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        historique.forEach { Text("• $it", color = Neon.sousTitre, fontSize = 10.sp) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { ouvert = false }) { Text("Fermer", color = Neon.violet) } }
        )
    }
}

@Composable
private fun AgentHermesLigne(titre: String, actif: Boolean, description: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .background(Color(0xFF171020)).padding(horizontal = 9.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (actif) "●" else "○", color = if (actif) Color(0xFF78D99A) else Neon.sousTitre, fontSize = 10.sp)
            Text("  $titre", color = Neon.blancViolet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(if (actif) "ACTIF" else "ARRÊTÉ", color = if (actif) Color(0xFF78D99A) else Neon.sousTitre, fontSize = 9.sp)
        }
        Text(description, color = Neon.sousTitre, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
    }
}
