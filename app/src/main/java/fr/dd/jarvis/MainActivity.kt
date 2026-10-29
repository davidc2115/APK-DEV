package fr.dd.jarvis

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.ui.ChatScreen
import fr.dd.jarvis.ui.ChatViewModel
import fr.dd.jarvis.ui.ContactsScreen
import fr.dd.jarvis.ui.HudBackground
import fr.dd.jarvis.ui.HudDim
import fr.dd.jarvis.ui.HudOrb
import fr.dd.jarvis.ui.HudText
import fr.dd.jarvis.ui.HudTheme
import fr.dd.jarvis.ui.LocalAccent
import fr.dd.jarvis.ui.MailScreen
import fr.dd.jarvis.ui.PlanningScreen
import fr.dd.jarvis.ui.SettingsScreen
import fr.dd.jarvis.ui.accentById
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val app = application as JarvisApp
        setContent {
            HudTheme(accentById(app.store.accent.value).color, app.store.tileAlpha.value) {
                HudBackground { Root(app, vm) }
            }
        }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Chat", Icons.Filled.Forum),
    Tab("Agenda", Icons.Filled.CalendarMonth),
    Tab("Mails", Icons.Filled.Email),
    Tab("Contacts", Icons.Filled.Contacts),
    Tab("Réglages", Icons.Filled.Settings)
)

@Composable
private fun Root(app: JarvisApp, vm: ChatViewModel) {
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    var tab by rememberSaveable { mutableStateOf(0) }

    // Dernier plantage (affiché au lancement suivant, sans ADB)
    var crash by remember {
        val f = File(ctx.filesDir, JarvisApp.CRASH_FILE)
        mutableStateOf(if (f.exists()) f.readText() else null)
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HudOrb(Modifier.size(44.dp), active = vm.busy || vm.listening)
            Column(Modifier.padding(start = 12.dp)) {
                Text("J.A.R.V.I.S", color = accent, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 4.sp, fontFamily = FontFamily.Monospace)
                Text(
                    when {
                        vm.listening -> "À l'écoute…"
                        vm.busy -> vm.status.ifBlank { "Réflexion…" }
                        else -> tabs[tab].label.uppercase()
                    },
                    color = HudDim, fontSize = 11.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> ChatScreen(vm, app.store)
                1 -> PlanningScreen(app)
                2 -> MailScreen(app)
                3 -> ContactsScreen(app)
                else -> SettingsScreen(app)
            }
        }

        // Barre de navigation HUD
        Row(
            Modifier.fillMaxWidth().background(accent.copy(alpha = 0.08f)).border(1.dp, accent.copy(alpha = 0.35f)).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            tabs.forEachIndexed { i, t ->
                val sel = i == tab
                Column(
                    Modifier.clickable { tab = i }.padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(t.icon, null, tint = if (sel) accent else HudDim, modifier = Modifier.size(24.dp))
                    Text(t.label.uppercase(), color = if (sel) accent else HudDim, fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }

    crash?.let { text ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = Color(0xFF0B1424), titleContentColor = accent, textContentColor = HudText,
            title = { Text("JARVIS s'est arrêté au dernier lancement") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text.take(3500), fontSize = 10.sp, fontFamily = FontFamily.Monospace) } },
            confirmButton = {
                TextButton({
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", text))
                }) { Text("Copier", color = accent) }
            },
            dismissButton = {
                TextButton({
                    File(ctx.filesDir, JarvisApp.CRASH_FILE).delete()
                    crash = null
                }) { Text("Fermer", color = HudDim) }
            }
        )
    }
}
