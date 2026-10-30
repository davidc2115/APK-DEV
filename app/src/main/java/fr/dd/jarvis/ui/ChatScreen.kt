package fr.dd.jarvis.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
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
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
    var showHistory by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

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
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).zIndex(1f)) {
                Text("⋮", color = HudDim, fontSize = 22.sp, modifier = Modifier.clickable { menu = true }.padding(horizontal = 14.dp, vertical = 4.dp))
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Color(0xFF0B1424))) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text((if (store.speakReplies.value) "✓ " else "") + "🔊 Réponse vocale", color = HudText) },
                        onClick = { store.speakReplies.set(!store.speakReplies.value); menu = false })
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text((if (store.conversationMode.value) "✓ " else "") + "🎧 Conversation", color = HudText) },
                        onClick = { store.conversationMode.set(!store.conversationMode.value); menu = false })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("➕ Nouvelle conversation", color = HudText) }, onClick = { vm.nouvelle(); menu = false })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("🗂️ Conversations", color = HudText) }, onClick = { showHistory = true; menu = false })
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

    if (showHistory) {
        val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext as fr.dd.jarvis.JarvisApp
        var requete by remember { mutableStateOf("") }
        var rafraichir by remember { mutableStateOf(0) }
        val fmt = remember { java.text.SimpleDateFormat("EEE dd/MM HH:mm", java.util.Locale.FRENCH) }
        val liste = remember(requete, rafraichir) { fr.dd.jarvis.ai.Conversations.chercher(appCtx, requete) }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showHistory = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxSize().background(Color(0xFF070D1A)).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.Companion.systemBars).imePadding().padding(14.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("CONVERSATIONS", color = accent, fontFamily = FontFamily.Monospace, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Text("✕", color = HudDim, fontSize = 22.sp, modifier = Modifier.clickable { showHistory = false }.padding(8.dp))
                }
                Text("🧠 La mémoire (consignes, préférences) est commune à toutes les conversations.", color = HudDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HudTextField(requete, { requete = it }, Modifier.weight(1f), placeholder = "Rechercher (mot, sujet, emojis…)", singleLine = true)
                    HudButton("➕ Nouvelle", filled = true) { vm.nouvelle(); showHistory = false }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (liste.isEmpty()) item { Text(if (requete.isBlank()) "Aucune conversation archivée. Chaque échange est rangé ici automatiquement." else "Aucun résultat.", color = HudDim, fontSize = 13.sp) }
                    items(liste.size) { i ->
                        val c = liste[i]
                        val enCours = c.id == appCtx.convId
                        HudPanel(Modifier.fillMaxWidth().clickable { vm.ouvrir(c.id); showHistory = false }, title = if (enCours) "EN COURS" else null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                                    Text(c.titre, color = HudText, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 2)
                                    Text("${fmt.format(java.util.Date(c.ts))} · ${c.count} messages" + (if (c.emojis > 5) " · ${c.emojis} emojis" else ""), color = accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    if (c.apercu.isNotBlank()) Text(c.apercu, color = HudDim, fontSize = 12.sp, maxLines = 2)
                                }
                                Text("✕", color = Color(0xFFFF4D6D), fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp).clickable { vm.supprimer(c.id); rafraichir++ }.padding(6.dp))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ vm.toutEffacer(); rafraichir++ }) { Text("Vider l'historique", color = accent, fontSize = 12.sp) }
                    TextButton({ showHistory = false; confirmReset = true }) { Text("Tout remettre à zéro", color = Color(0xFFFF4D6D), fontSize = 12.sp) }
                }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Color(0xFF0B1424), titleContentColor = accent, textContentColor = HudText,
            title = { Text("Remise à zéro complète ?") },
            text = { Text("Efface la conversation, tout l'historique, les consignes mémorisées (note « Mémoire JARVIS ») et les images générées sur le téléphone. Les clés, la société, les devis et factures sont conservés.", fontSize = 13.sp) },
            confirmButton = { TextButton({ vm.remiseAZero(); confirmReset = false }) { Text("Tout effacer", color = Color(0xFFFF4D6D)) } },
            dismissButton = { TextButton({ confirmReset = false }) { Text("Annuler", color = HudDim) } }
        )
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
