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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.ime
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
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import fr.dd.jarvis.ui.GraphScreen
import fr.dd.jarvis.ui.HudBackground
import fr.dd.jarvis.ui.HudDim
import fr.dd.jarvis.ui.HudOrb
import fr.dd.jarvis.ui.HudText
import fr.dd.jarvis.ui.HudTheme
import fr.dd.jarvis.ui.LocalAccent
import fr.dd.jarvis.ui.MailScreen
import fr.dd.jarvis.ui.PlanningScreen
import fr.dd.jarvis.ui.SettingsScreen
import fr.dd.jarvis.ui.SocieteScreen
import fr.dd.jarvis.ui.accentById
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: ChatViewModel by viewModels()

    fun demarrerEcoute() { if (!vm.listening && !vm.busy) vm.toggleListening() }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleListen(intent)
        handleSharedVideo(intent)
        handleSharedKml(intent)
        handleTikTokCallback(intent)
    }

    /** Reçoit un fichier KML partagé depuis Fichiers, Google Earth ou une autre application. */
    private fun handleSharedKml(i: android.content.Intent?) {
        if (i?.action != android.content.Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri = i.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM) ?: return
        val mime = i.type.orEmpty()
        val name = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0).orEmpty() else ""
            }
        }.getOrDefault("")
        if (!name.endsWith(".kml", true) && !mime.contains("kml", true) && !mime.contains("google-earth", true)) return
        i.removeExtra(android.content.Intent.EXTRA_STREAM)
        runCatching {
            val out = File((application as JarvisApp).filesDir, "clients_a_importer.kml")
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= 25L * 1024L * 1024L) { "Fichier KML trop volumineux (limite 25 Mo)." }
                        output.write(buffer, 0, n)
                    }
                }
            } ?: error("Fichier inaccessible")
        }.onSuccess {
            android.widget.Toast.makeText(this, "KML reçu. Demande à JARVIS : « importe les clients du KML ».", android.widget.Toast.LENGTH_LONG).show()
        }.onFailure {
            android.widget.Toast.makeText(this, "Réception KML impossible : ${it.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Retour de la connexion OAuth TikTok (via la page relais GitHub Pages → fr.dd.jarvis://tiktok-callback?...). */
    private fun handleTikTokCallback(i: android.content.Intent?) {
        val uri = i?.data ?: return
        if (uri.scheme != "fr.dd.jarvis" || uri.host != "tiktok-callback") return
        i.data = null
        val app = application as JarvisApp
        val erreur = uri.getQueryParameter("error")
        val etatAttendu = app.store.tiktokOAuthState.value
        app.store.tiktokOAuthState.set("")
        if (erreur != null) {
            android.widget.Toast.makeText(this, "❌ Connexion TikTok refusée : ${uri.getQueryParameter("error_description") ?: erreur}", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val code = uri.getQueryParameter("code")
        val etat = uri.getQueryParameter("state")
        if (code.isNullOrBlank() || etat != etatAttendu) {
            android.widget.Toast.makeText(this, "❌ Retour TikTok invalide (code manquant ou state incorrect).", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        vm.viewModelScope.launch {
            val r = fr.dd.jarvis.ai.TikTok.echangerCode(app, code)
            android.widget.Toast.makeText(this@MainActivity, r, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Vidéo reçue via « Partager » depuis une autre appli (ex. TikTok) : on la garde dans JARVIS et on lance l'analyse. */
    private fun handleSharedVideo(i: android.content.Intent?) {
        if (i?.action != android.content.Intent.ACTION_SEND || i.type?.startsWith("video/") != true) return
        @Suppress("DEPRECATION") val uri = i.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM) ?: return
        i.removeExtra(android.content.Intent.EXTRA_STREAM)
        val app = application as JarvisApp
        val ext = contentResolver.getType(uri)?.substringAfterLast('/')?.takeIf { it.length in 2..4 } ?: "mp4"
        val nom = "video_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.FRENCH).format(java.util.Date()) + "_" + (100 + (Math.random() * 899).toInt()) + ".$ext"
        runCatching {
            contentResolver.openInputStream(uri)?.use { ins ->
                java.io.File(fr.dd.jarvis.ai.Fichiers.dossierVideos(app), nom).outputStream().use { out -> ins.copyTo(out) }
            } ?: throw IllegalStateException("flux illisible")
        }.onSuccess {
            vm.send("Analyse la vidéo que je viens de partager (fichier $nom).")
        }.onFailure {
            vm.send("Je n'ai pas réussi à récupérer la vidéo partagée : ${it.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val app = application as JarvisApp
        if (app.store.wakeOn.value && !fr.dd.jarvis.service.JarvisService.running &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) fr.dd.jarvis.service.JarvisService.start(this)
        handleListen(intent)
        handleSharedVideo(intent)
        handleSharedKml(intent)
        handleTikTokCallback(intent)
        setContent {
            HudTheme(accentById(app.store.accent.value).color, app.store.tileAlpha.value) {
                HudBackground { Root(app, vm) }
            }
        }
    }
}

private fun MainActivity.handleListen(i: android.content.Intent?) {
    if (i?.getBooleanExtra("listen", false) == true && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
        i.removeExtra("listen")
        window.decorView.postDelayed({ (this as? MainActivity)?.demarrerEcoute() }, 400)
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Chat", Icons.Filled.Forum),
    Tab("Agenda", Icons.Filled.CalendarMonth),
    Tab("Mails", Icons.Filled.Email),
    Tab("Contacts", Icons.Filled.Contacts),
    Tab("Société", Icons.Filled.Business),
    Tab("Graphe", Icons.Filled.Hub),
    Tab("Réglages", Icons.Filled.Settings)
)

@Composable
private fun Root(app: JarvisApp, vm: ChatViewModel) {
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    // Android 13+ bloque toutes les notifications tant que l'autorisation runtime
    // n'a pas été accordée. Sans cette demande, minuteurs et chronomètre pouvaient
    // être lancés en interne tout en restant totalement invisibles.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var tab by rememberSaveable { mutableStateOf(0) }

    // Dernier plantage (affiché au lancement suivant, sans ADB)
    var crash by remember {
        val f = File(ctx.filesDir, JarvisApp.CRASH_FILE)
        mutableStateOf(if (f.exists()) f.readText() else null)
    }

    // Clavier ouvert : l'en-tête et la barre d'onglets se masquent pour laisser toute la place à la conversation.
    val imeOpen = androidx.compose.foundation.layout.WindowInsets.Companion.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        if (!(imeOpen && tab == 0)) Row(
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
                0 -> ChatScreen(vm, app.store, app)
                1 -> PlanningScreen(app)
                2 -> MailScreen(app)
                3 -> ContactsScreen(app)
                4 -> SocieteScreen(app)
                5 -> GraphScreen(app)
                else -> SettingsScreen(app)
            }
        }

        // Barre de navigation HUD
        if (!imeOpen) Row(
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
