package fr.dd.jarvis.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.DocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DocumentLibraryScreen(app: JarvisApp, onAsk: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            status = "Import en cours…"
            val result = withContext(Dispatchers.IO) { uris.map { uri -> runCatching { DocumentStore.importFile(context, uri) }.getOrElse { "ERREUR : ${it.message}" } } }
            status = result.joinToString("\n")
            refresh++
        }
    }
    val files = remember(refresh) { DocumentStore.files(context) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HudPanel(Modifier.fillMaxWidth(), title = "Bibliothèque documentaire") {
            Text("Importe des documents depuis le téléphone. Les originaux restent conservés dans l'espace privé de Jarvis.", color = HudDim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionDoc("＋ Importer des fichiers") { picker.launch(arrayOf("*/*")) }
                ActionDoc("Actualiser") { refresh++ }
            }
            if (status.isNotBlank()) Text(status, color = LocalAccent.current, fontSize = 11.sp)
            Text("${files.size} fichier(s) importé(s)", color = HudText, fontSize = 12.sp)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files, key = { it.name }) { file ->
                HudPanel(Modifier.fillMaxWidth(), title = file.name) {
                    Text("${(file.length() + 1023) / 1024} Ko · .${file.extension.lowercase()}", color = HudDim, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ActionDoc("Analyser") { onAsk("Analyse le fichier importé « ${file.name} ». Utilise document_analyser pour les PDF, images et vidéos ; document_lire pour les documents texte, bureautiques, KML et ZIP. Puis résume les informations utiles.") }
                        ActionDoc("Utiliser") { onAsk("Utilise le document importé « ${file.name} » comme source pour ma demande suivante. Commence par en extraire le contenu.") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionDoc(label: String, action: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = action) { Text(label, color = LocalAccent.current, fontSize = 12.sp) }
}
