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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Prompts
import fr.dd.jarvis.device.MailItem
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun MailScreen(app: JarvisApp) {
    val store = app.store
    val scope = rememberCoroutineScope()
    var mails by remember { mutableStateOf<List<MailItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var summarizing by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH) }

    val load: () -> Unit = {
        scope.launch {
            loading = true
            error = ""
            try {
                mails = app.mail.fetchRecent(25, false)
            } catch (e: Exception) {
                error = e.message ?: "Erreur de connexion"
            }
            loading = false
        }
    }

    LaunchedEffect(store.mailHost.value, store.mailUser.value, store.mailPass.value) {
        if (store.mailConfigured()) load()
    }

    if (!store.mailConfigured()) {
        HudPanel(Modifier.padding(12.dp).fillMaxWidth(), title = "Mails") {
            Text(
                "Renseigne ton serveur IMAP dans Réglages › Mails.\nGmail : active l'IMAP et crée un « mot de passe d'application » (Compte Google › Sécurité).",
                color = HudText, fontSize = 14.sp
            )
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton(if (loading) "Chargement…" else "Actualiser", icon = Icons.Filled.Refresh, enabled = !loading) { load() }
                HudButton(if (summarizing) "Analyse…" else "Résumer (IA)", filled = true, enabled = !summarizing && mails.isNotEmpty()) {
                    scope.launch {
                        summarizing = true
                        summary = try {
                            app.llm.agent(Prompts.MAIL, "Aujourd'hui : ${Prompts.now()}\n\n" + app.mail.toPrompt(mails))
                        } catch (e: Exception) {
                            "⚠️ ${e.message}"
                        }
                        summarizing = false
                    }
                }
            }
        }
        if (error.isNotBlank()) {
            item { HudPanel(Modifier.fillMaxWidth(), title = "Erreur") { Text(error, color = HudText, fontSize = 13.sp) } }
        }
        if (summary.isNotBlank()) {
            item { HudPanel(Modifier.fillMaxWidth(), title = "Résumé") { MarkdownText(summary) } }
        }
        items(mails) { m ->
            HudPanel(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        (if (m.unread) "● " else "") + m.from, color = if (m.unread) LocalAccent.current else HudText,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f)
                    )
                    Text(m.date?.let { fmt.format(it) } ?: "", color = HudDim, fontSize = 11.sp)
                }
                Text(m.subject, color = HudText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(m.snippet.take(160), color = HudDim, fontSize = 12.sp)
            }
        }
    }
}
