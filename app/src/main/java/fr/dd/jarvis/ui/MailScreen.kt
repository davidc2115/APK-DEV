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
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import fr.dd.jarvis.device.GmailApi
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
            Text("Connecte ton compte Google (lecture seule de ta boîte de réception), ou renseigne un serveur IMAP dans Réglages › Mails.", color = HudText, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            GoogleConnect(app)
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

/** Comptes Google connectés (plusieurs possibles) : sélecteur de comptes du téléphone puis consentement lecture seule. */
@Composable
fun GoogleConnect(app: JarvisApp, onDone: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf("") }

    suspend fun finish(token: String?, fallbackEmail: String) {
        if (token == null) { msg = "❌ Autorisation refusée."; return }
        try {
            val email = GmailApi.profile(token).ifBlank { fallbackEmail }
            app.store.addGmailAccount(email)
            msg = "✅ Compte ajouté : $email"
            onDone()
        } catch (e: Exception) { msg = "❌ ${e.message}" }
    }
    suspend fun authorize(email: String, launch: (IntentSenderRequest) -> Unit) {
        msg = "⏳ Autorisation…"
        try {
            val r = GmailApi.authorize(ctx, email)
            if (r.hasResolution()) launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
            else finish(r.accessToken, email)
        } catch (e: Exception) {
            msg = "❌ " + (e.message ?: "erreur") + "\n(Si « 10 » ou « DEVELOPER_ERROR » : la configuration Google Cloud n'est pas faite, voir la notice.)"
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch {
            if (res.resultCode == Activity.RESULT_OK) {
                try { finish(GmailApi.resultFromIntent(ctx, res.data).accessToken, pending) } catch (e: Exception) { msg = "❌ ${e.message}" }
            } else msg = "Connexion annulée."
        }
    }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val name = res.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
        if (res.resultCode == Activity.RESULT_OK && !name.isNullOrBlank()) {
            pending = name
            scope.launch { authorize(name) { consent.launch(it) } }
        } else msg = "Aucun compte choisi."
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        app.store.gmailAccounts.toList().forEach { acc ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("✅ $acc", color = HudText, fontSize = 13.sp, modifier = Modifier.weight(1f))
                HudButton("Retirer") { app.store.removeGmailAccount(acc) }
            }
        }
        HudButton(if (app.store.gmailAccounts.isEmpty()) "Connecter mon compte Google" else "Ajouter un autre compte Google", filled = true) {
            @Suppress("DEPRECATION")
            val i = android.accounts.AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)
            chooser.launch(i)
        }
        if (msg.isNotBlank()) Text(msg, color = HudText, fontSize = 12.sp)
    }
}
