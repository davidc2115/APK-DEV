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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.Autorisations
import fr.dd.jarvis.work.BilanSante
import kotlinx.coroutines.launch

private fun date(ms: Long) = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE).format(java.util.Date(ms))

/** Bilan de santé quotidien : dernier rapport, lancement à la demande, activation. */
@Composable
fun BilanSantePanel(app: JarvisApp) {
    val s = app.store
    val scope = rememberCoroutineScope()
    var occupe by remember { mutableStateOf(false) }
    HudPanel(Modifier.fillMaxWidth(), title = "🩺 Bilan de santé quotidien") {
        Text("Chaque matin vers 7 h 30, JARVIS vérifie ses clés IA (sans consommer de quota), Home Assistant, Plex, la Freebox, Discord, le serveur " +
            "(disque, mémoire, conteneurs Docker arrêtés) et les sauvegardes. Une notification n'arrive que s'il y a un problème.", color = HudDim, fontSize = 11.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudChip(if (s.santeAuto.value) "✅ Chaque matin" else "⏸️ Désactivé", s.santeAuto.value) { s.santeAuto.set(!s.santeAuto.value); BilanSante.planifier(app) }
            HudChip(if (occupe) "⏳ Vérification…" else "▶️ Faire le bilan maintenant") {
                if (!occupe) { occupe = true; scope.launch { runCatching { BilanSante.executer(app, notifier = false) }; occupe = false } }
            }
        }
        val quand = s.santeQuand.value.toLongOrNull() ?: 0L
        if (quand > 0) Text("Dernier bilan : ${date(quand)}", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        if (s.santeDernier.value.isNotBlank()) Text(s.santeDernier.value.replace("**", ""), color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Journal des actions exécutées sans confirmation grâce à une autorisation permanente. */
@Composable
fun JournalAutorisationsPanel(app: JarvisApp) {
    var tick by remember { mutableIntStateOf(0) }
    val l = remember(tick, app.store.journalAutorisations.value) { Autorisations.journal(app) }
    HudPanel(Modifier.fillMaxWidth(), title = "📜 Actions faites sans te demander") {
        Text("Chaque action exécutée sans confirmation parce que tu l'avais autorisée « pour toujours » (type d'action ou commande précise). " +
            "Pour retirer une autorisation : Réglages › Téléphone › Contrôle (autorisations permanentes).", color = HudDim, fontSize = 11.sp)
        if (l.isEmpty()) Text("Aucune pour l'instant.", color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        l.take(100).forEach { t ->
            Text("• ${date(t.quand)} — ${t.outil} : ${t.action}", color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Text("   (${t.motif})", color = HudDim, fontSize = 10.sp)
        }
        if (l.isNotEmpty()) HudChip("🧹 Vider le journal") { Autorisations.viderJournal(app); tick++ }
    }
}
