package fr.dd.jarvis.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.ToolBox
import fr.dd.jarvis.device.ContactInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ContactsScreen(app: JarvisApp) {
    val repo = app.contacts
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(repo.hasPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = repo.hasPermission()
    }

    if (!granted) {
        HudPanel(Modifier.padding(12.dp).fillMaxWidth(), title = "Contacts") {
            Text("JARVIS a besoin d'accéder à tes contacts pour les lister, les créer et les enrichir.", color = HudText, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            HudButton("Autoriser les contacts", icon = Icons.Filled.Contacts, filled = true) {
                launcher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
            }
        }
        return
    }

    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf(emptyList<ContactInfo>()) }
    var selected by remember { mutableStateOf<ContactInfo?>(null) }
    var editing by remember { mutableStateOf<ContactInfo?>(null) }
    var creating by remember { mutableStateOf(false) }
    var cardText by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val tools = remember { ToolBox(app) { _, _ -> true } }

    LaunchedEffect(query, refresh) {
        delay(250)
        results = withContext(Dispatchers.IO) { repo.search(query) }
    }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudTextField(query, { query = it }, Modifier.weight(1f), placeholder = "Rechercher un contact…")
                HudRoundButton(Icons.Filled.Add) { creating = true }
            }
        }
        if (working) item { Text("⏳ JARVIS rédige la fiche…", color = LocalAccent.current, fontSize = 13.sp) }
        items(results, key = { it.id }) { c ->
            HudPanel(Modifier.fillMaxWidth(), onClick = { selected = c }) {
                Text(c.name, color = HudText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (c.company.isNotBlank()) Text("🏢 ${c.company}", color = HudDim, fontSize = 12.sp)
                c.phones.firstOrNull()?.let { Text("📞 $it", color = HudDim, fontSize = 12.sp) }
                c.emails.firstOrNull()?.let { Text("✉️ $it", color = HudDim, fontSize = 12.sp) }
            }
        }
    }

    selected?.let { c ->
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor = Color(0xFF0B1424), titleContentColor = LocalAccent.current, textContentColor = HudText,
            title = { Text(c.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (c.company.isNotBlank()) Text("🏢 ${c.company}")
                    c.phones.forEach { p ->
                        Text("📞 $p", color = LocalAccent.current, modifier = Modifier.clickableLink {
                            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$p")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        })
                    }
                    c.emails.forEach { e -> Text("✉️ $e") }
                    if (c.note.isNotBlank()) Text("📝 ${c.note}", color = HudDim, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    HudButton("Modifier") {
                        editing = c
                        selected = null
                    }
                    HudButton("Fiche Obsidian (IA)", filled = true) {
                        selected = null
                        working = true
                        scope.launch {
                            cardText = try {
                                tools.buildCard(c)
                            } catch (e: Exception) {
                                "⚠️ ${e.message}"
                            }
                            working = false
                        }
                    }
                }
            },
            confirmButton = { TextButton({ selected = null }) { Text("Fermer", color = LocalAccent.current) } }
        )
    }

    cardText?.let { t ->
        AlertDialog(
            onDismissRequest = { cardText = null },
            containerColor = Color(0xFF0B1424), titleContentColor = LocalAccent.current, textContentColor = HudText,
            title = { Text("Fiche contact") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { MarkdownText(t) } },
            confirmButton = { TextButton({ cardText = null }) { Text("OK", color = LocalAccent.current) } }
        )
    }

    if (creating || editing != null) {
        val base = editing
        var name by remember(base?.id) { mutableStateOf(base?.name ?: "") }
        var phone by remember(base?.id) { mutableStateOf(base?.phones?.firstOrNull() ?: "") }
        var email by remember(base?.id) { mutableStateOf(base?.emails?.firstOrNull() ?: "") }
        var company by remember(base?.id) { mutableStateOf(base?.company ?: "") }
        var note by remember(base?.id) { mutableStateOf(base?.note ?: "") }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            containerColor = Color(0xFF0B1424), titleContentColor = LocalAccent.current, textContentColor = HudText,
            title = { Text(if (base == null) "Nouveau contact" else "Modifier le contact") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudTextField(name, { name = it }, label = "Nom")
                    HudTextField(phone, { phone = it }, label = "Téléphone")
                    HudTextField(email, { email = it }, label = "Email")
                    HudTextField(company, { company = it }, label = "Société")
                    HudTextField(note, { note = it }, label = "Notes", singleLine = false)
                }
            },
            confirmButton = {
                TextButton({
                    if (name.isNotBlank()) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (base == null) repo.create(name.trim(), phone.trim(), email.trim(), company.trim(), note.trim())
                                else repo.update(base.id, name.trim(), phone.trim(), email.trim(), company.trim(), note.trim())
                            }
                            creating = false
                            editing = null
                            refresh++
                        }
                    }
                }) { Text("Enregistrer", color = LocalAccent.current) }
            },
            dismissButton = { TextButton({ creating = false; editing = null }) { Text("Annuler", color = HudDim) } }
        )
    }
}

private fun Modifier.clickableLink(onClick: () -> Unit): Modifier = this.then(Modifier.clickable { onClick() })
