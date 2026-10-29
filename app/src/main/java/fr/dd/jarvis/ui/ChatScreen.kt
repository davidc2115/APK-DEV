package fr.dd.jarvis.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import fr.dd.jarvis.ai.ChatMsg
import fr.dd.jarvis.data.Store

@Composable
fun ChatScreen(vm: ChatViewModel, store: Store) {
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val count = vm.messages.size + if (vm.busy) 1 else 0

    LaunchedEffect(count) {
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.toggleListening() else vm.status = "Permission micro refusée."
    }

    fun onMic() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.toggleListening()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.messages.isEmpty() && !vm.busy) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    HudOrb(Modifier.size(96.dp))
                    Text(
                        "Écris ou parle à JARVIS.\nPlanning, mails, contacts, téléphone…",
                        color = HudDim, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(vm.messages) { m -> Bubble(m) }
                if (vm.busy) {
                    item {
                        HudPanel(Modifier.fillMaxWidth(0.7f), title = "JARVIS") {
                            Text(vm.status.ifBlank { "Réflexion…" }, color = HudDim, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { HudChip("🌅 Point du matin") { vm.send("Fais-moi mon point du matin") } }
            item { HudChip("📅 Planning du jour") { vm.send("Montre-moi mon planning d'aujourd'hui") } }
            item { HudChip("📅 Demain") { vm.send("Montre-moi mon planning de demain") } }
            item { HudChip("📧 Résumé mails") { vm.send("Résume mes derniers mails") } }
            item { HudChip("🔦 Lampe") { vm.send("Allume la lampe torche") } }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HudChip("🔊 Réponse vocale", store.speakReplies.value) { store.speakReplies.set(!store.speakReplies.value) }
            HudChip("🎧 Conversation", store.conversationMode.value) { store.conversationMode.set(!store.conversationMode.value) }
            HudChip("Effacer") { vm.clear() }
        }

        if (vm.partial.isNotBlank() || (vm.status.isNotBlank() && !vm.busy)) {
            Text(
                if (vm.partial.isNotBlank()) "🎙️ ${vm.partial}" else vm.status,
                color = accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HudTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = "Écris à JARVIS…", singleLine = false, maxLines = 4
            )
            HudRoundButton(if (vm.listening) Icons.Filled.MicOff else Icons.Filled.Mic, active = vm.listening) { onMic() }
            HudRoundButton(Icons.AutoMirrored.Filled.Send, enabled = !vm.busy && input.isNotBlank()) {
                vm.send(input)
                input = ""
            }
        }
    }

    vm.pendingConfirm?.let { question ->
        AlertDialog(
            onDismissRequest = { vm.answerConfirm(false) },
            containerColor = Color(0xFF0B1424),
            titleContentColor = accent,
            textContentColor = HudText,
            title = { Text("Confirmation", fontFamily = FontFamily.Monospace) },
            text = { Text(question) },
            confirmButton = { TextButton({ vm.answerConfirm(true) }) { Text("Autoriser", color = accent) } },
            dismissButton = { TextButton({ vm.answerConfirm(false) }) { Text("Refuser", color = HudDim) } }
        )
    }
}

@Composable
private fun Bubble(m: ChatMsg) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        HudPanel(
            Modifier.fillMaxWidth(if (m.fromUser) 0.86f else 0.98f),
            title = if (m.fromUser) null else "JARVIS",
            fillAlpha = if (m.fromUser) 0.34f else LocalTileAlpha.current
        ) {
            MarkdownText(m.text)
        }
    }
}
