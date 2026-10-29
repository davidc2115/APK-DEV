package fr.dd.jarvis.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Llm
import fr.dd.jarvis.device.ShizukuShell
import fr.dd.jarvis.work.Scheduler
import kotlinx.coroutines.launch
import java.time.LocalTime

@Composable
fun SettingsScreen(app: JarvisApp) {
    val store = app.store
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ------------------------------------------------------------ Apparence
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "Apparence") {
                Text("Couleur du thème", color = HudDim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Accents.forEach { a ->
                        val sel = store.accent.value == a.id
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(a.color.copy(alpha = 0.85f))
                                .border(if (sel) 3.dp else 1.dp, if (sel) Color.White else Color(0x66FFFFFF), CircleShape)
                                .clickable { store.accent.set(a.id) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Transparence des tuiles", color = HudDim, fontSize = 12.sp)
                Slider(
                    value = store.tileAlpha.value, onValueChange = { store.tileAlpha.set(it) },
                    valueRange = 0.04f..0.40f,
                    colors = SliderDefaults.colors(thumbColor = LocalAccent.current, activeTrackColor = LocalAccent.current)
                )
                HudTextField(store.userName.value, { store.userName.set(it) }, label = "Ton prénom (pour les salutations)")
            }
        }

        // ------------------------------------------------------------ Cerveau Groq
        item {
            KeyPanel(
                title = "Cerveau — clés Groq (rotation automatique)",
                hint = "Une clé par ligne. À chaque requête JARVIS passe à la clé suivante ; une clé saturée (429) est mise en pause.",
                keys = store.groqKeys,
                onAdd = { store.addGroqKeys(it) },
                onRemove = { store.removeGroqKey(it) },
                onTest = { app.llm.testGroqKeys() },
                modelLabel = "Modèle Groq", model = store.groqModel.value, onModel = { store.groqModel.set(it) },
                onListModels = { app.llm.listGroqModels() }
            )
        }

        // ------------------------------------------------------------ Agents Gemini
        item {
            KeyPanel(
                title = "Agents — clés Gemini (rotation automatique)",
                hint = "Utilisées pour le planning, les mails et les contacts. Sans clé Gemini, les agents utilisent Groq.",
                keys = store.geminiKeys,
                onAdd = { store.addGeminiKeys(it) },
                onRemove = { store.removeGeminiKey(it) },
                onTest = { app.llm.testGeminiKeys() },
                modelLabel = "Modèle Gemini", model = store.geminiModel.value, onModel = { store.geminiModel.set(it) },
                onListModels = { app.llm.listGeminiModels() }
            )
        }

        // ------------------------------------------------------------ Mails
        item {
            var result by remember { mutableStateOf("") }
            HudPanel(Modifier.fillMaxWidth(), title = "Mails") {
                GoogleConnect(app)
                Spacer(Modifier.height(10.dp))
                Text("Ou IMAP :", color = HudDim, fontSize = 12.sp)
                Text("Gmail : active l'IMAP, puis crée un « mot de passe d'application » (Compte Google › Sécurité).", color = HudDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                HudTextField(store.mailHost.value, { store.mailHost.set(it) }, Modifier.fillMaxWidth(), label = "Serveur IMAP")
                Spacer(Modifier.height(6.dp))
                HudTextField(store.mailPort.value, { store.mailPort.set(it) }, Modifier.fillMaxWidth(), label = "Port (993)")
                Spacer(Modifier.height(6.dp))
                HudTextField(store.mailUser.value, { store.mailUser.set(it.trim()) }, Modifier.fillMaxWidth(), label = "Adresse mail")
                Spacer(Modifier.height(6.dp))
                HudTextField(store.mailPass.value, { store.mailPass.set(it) }, Modifier.fillMaxWidth(), label = "Mot de passe d'application", password = true)
                Spacer(Modifier.height(8.dp))
                HudButton("Tester la connexion", enabled = store.mailConfigured()) {
                    scope.launch {
                        result = "⏳ Connexion…"
                        result = try {
                            val n = app.mail.fetchRecent(1, false).size
                            "✅ Connexion réussie ($n mail lu)."
                        } catch (e: Exception) {
                            "❌ ${e.message}"
                        }
                    }
                }
                if (result.isNotBlank()) Text(result, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // ------------------------------------------------------------ Obsidian
        item {
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    try {
                        ctx.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                    store.obsidianUri.set(uri.toString())
                }
            }
            HudPanel(Modifier.fillMaxWidth(), title = "Obsidian — fiches contacts") {
                Text(
                    if (store.obsidianUri.value.isBlank()) "Aucun dossier choisi : les fiches sont enregistrées dans le dossier de l'application."
                    else "Dossier choisi : " + Uri.decode(store.obsidianUri.value).substringAfterLast(":"),
                    color = HudDim, fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Choisir le coffre Obsidian") { picker.launch(null) }
                    if (store.obsidianUri.value.isNotBlank()) HudButton("Retirer") { store.obsidianUri.set("") }
                }
                Text("Les fiches sont écrites dans le sous-dossier « Contacts » du coffre.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // ------------------------------------------------------------ Briefing du matin
        item {
            val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            HudPanel(Modifier.fillMaxWidth(), title = "Point du matin") {
                Text("Chaque matin, JARVIS prépare ton planning du jour et le tri de tes mails, et t'envoie une notification.", color = HudDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HudChip(if (store.briefingOn.value) "✅ Activé" else "Désactivé", store.briefingOn.value) {
                        val on = !store.briefingOn.value
                        store.briefingOn.set(on)
                        if (on) {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            Scheduler.schedule(ctx, store)
                        } else Scheduler.cancel(ctx)
                    }
                    HudChip("🕗 %02d:%02d".format(store.briefingHour.value, store.briefingMinute.value)) {
                        pickTime(ctx, LocalTime.of(store.briefingHour.value, store.briefingMinute.value)) { t ->
                            store.briefingHour.set(t.hour)
                            store.briefingMinute.set(t.minute)
                            if (store.briefingOn.value) Scheduler.schedule(ctx, store)
                        }
                    }
                }
                Text(
                    "Xiaomi / HyperOS : autorise le démarrage automatique de JARVIS et mets l'économie de batterie sur « Aucune restriction », sinon le point du matin peut ne pas partir.",
                    color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)
                )
                Spacer(Modifier.height(6.dp))
                HudButton("Infos de l'application") {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }

        // ------------------------------------------------------------ Téléphone
        item {
            val a11yOn = remember(tick) { app.phone.accessibilityEnabled() }
            val shInstalled = remember(tick) { ShizukuShell.installed(ctx) }
            val shRunning = remember(tick) { ShizukuShell.running() }
            val shGranted = remember(tick) { ShizukuShell.granted() }
            HudPanel(Modifier.fillMaxWidth(), title = "Contrôle du téléphone") {
                StatusRow("Accessibilité (lire l'écran, toucher, défiler)", a11yOn)
                Spacer(Modifier.height(4.dp))
                HudButton("Ouvrir les réglages d'accessibilité") {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                Text(
                    "Android 13+ : si l'option est grisée, ouvre « Infos de l'application » › ⋮ › « Autoriser les paramètres restreints », puis réessaie.",
                    color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                )

                StatusRow("Shizuku installé", shInstalled)
                StatusRow("Shizuku démarré", shRunning)
                StatusRow("Shizuku autorisé pour JARVIS", shGranted)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Autoriser Shizuku", enabled = shRunning && !shGranted) {
                        ShizukuShell.requestPermission()
                    }
                    HudButton("Actualiser") { tick++ }
                }
                var shMsg by remember { mutableStateOf("") }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Installer Shizuku (auto via ADB)", enabled = !shInstalled, filled = true) {
                        scope.launch { shMsg = "⏳ Téléchargement et installation…"; shMsg = fr.dd.jarvis.device.ShizukuInstaller.installAuto(ctx); tick++ }
                    }
                    HudButton("Installer manuellement", enabled = !shInstalled) {
                        scope.launch { shMsg = "⏳ Téléchargement…"; shMsg = fr.dd.jarvis.device.ShizukuInstaller.install(ctx) }
                    }
                    HudButton("Démarrer Shizuku via ADB", enabled = shInstalled && !shRunning) {
                        scope.launch { shMsg = "⏳"; shMsg = fr.dd.jarvis.device.ShizukuInstaller.startViaAdb(ctx); tick++ }
                    }
                }
                if (shMsg.isNotBlank()) Text(shMsg, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Text(
                    "Shizuku donne à JARVIS les droits « adb » (Wi-Fi, Bluetooth, luminosité, appels, commandes shell). Installe l'app Shizuku, démarre-la via le débogage sans fil, puis autorise JARVIS ici.",
                    color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
                )

                HudDivider()
                Spacer(Modifier.height(8.dp))
                var adbMsg by remember { mutableStateOf("") }
                val adbOn = remember(tick, adbMsg) { fr.dd.jarvis.device.AdbShell.connected() }
                val notifPerm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
                    fr.dd.jarvis.device.AdbShell.postPairingNotification(ctx)
                }
                Text("ADB intégré (remplace Shizuku)", color = LocalAccent.current, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 13.sp)
                StatusRow("ADB connecté", adbOn)
                Text(
                    "1) Active Options pour développeurs › Débogage sans fil (Wi-Fi requis).\n" +
                        "2) Touche « Appairer » ci-dessous : une notification JARVIS apparaît.\n" +
                        "3) Dans Débogage sans fil, touche « Associer l'appareil avec un code d'appairage ».\n" +
                        "4) Tire la notification JARVIS, saisis le code à 6 chiffres. Fini : à faire une seule fois.",
                    color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Appairer", filled = true) {
                        if (android.os.Build.VERSION.SDK_INT >= 33) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        else fr.dd.jarvis.device.AdbShell.postPairingNotification(ctx)
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    HudButton("Connecter") {
                        scope.launch { adbMsg = "⏳"; adbMsg = fr.dd.jarvis.device.AdbShell.connect(ctx) }
                    }
                    HudButton("Tester") {
                        scope.launch { adbMsg = "⏳"; adbMsg = fr.dd.jarvis.device.AdbShell.exec(ctx, "id") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Déconnecter") { fr.dd.jarvis.device.AdbShell.disconnect(); adbMsg = "ADB déconnecté."; tick++ }
                    HudButton("Désactiver le débogage") {
                        scope.launch { adbMsg = "⏳"; adbMsg = fr.dd.jarvis.device.AdbShell.disableDebugging(ctx); tick++ }
                    }
                    HudButton("Options dév.") {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                if (adbMsg.isNotBlank()) Text(adbMsg, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))

                HudDivider()
                Spacer(Modifier.height(8.dp))
                var locOk by remember { mutableStateOf(fr.dd.jarvis.device.Locator.granted(ctx)) }
                val locPerm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
                    locOk = fr.dd.jarvis.device.Locator.granted(ctx)
                }
                StatusRow("Localisation (« où suis-je », distances depuis ma position)", locOk)
                Spacer(Modifier.height(4.dp))
                HudButton("Autoriser la localisation", enabled = !locOk) {
                    locPerm.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                }
                Spacer(Modifier.height(8.dp))
                var smsOk by remember { mutableStateOf(fr.dd.jarvis.device.Telephony.smsGranted(ctx) && fr.dd.jarvis.device.Telephony.callGranted(ctx)) }
                val smsPerm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
                    smsOk = fr.dd.jarvis.device.Telephony.smsGranted(ctx) && fr.dd.jarvis.device.Telephony.callGranted(ctx)
                }
                StatusRow("SMS (lire, envoyer) et appels directs", smsOk)
                Spacer(Modifier.height(4.dp))
                HudButton("Autoriser SMS et appels", enabled = !smsOk) {
                    smsPerm.launch(arrayOf(android.Manifest.permission.READ_SMS, android.Manifest.permission.SEND_SMS, android.Manifest.permission.CALL_PHONE))
                }
                Text("Android 13+ : si l'autorisation est refusée, ouvre « Infos de l'application » › ⋮ › « Autoriser les paramètres restreints ». Chaque SMS ou appel demande ta confirmation.",
                    color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HudChip(if (store.confirmSensitive.value) "✅ Confirmer les actions sensibles" else "Confirmation désactivée", store.confirmSensitive.value) {
                        store.confirmSensitive.set(!store.confirmSensitive.value)
                    }
                }
                Text(
                    "Les commandes shell demandent toujours une confirmation, quel que soit ce réglage.",
                    color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        item {
            Text(
                "JARVIS 1.0 · " + app.llm.keyStatus(),
                color = HudDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "🟢" else "⚪", fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text(label, color = HudText, fontSize = 13.sp)
    }
}

@Composable
private fun KeyPanel(
    title: String,
    hint: String,
    keys: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (Int) -> Unit,
    onTest: suspend () -> List<String>,
    modelLabel: String,
    model: String,
    onModel: (String) -> Unit,
    onListModels: suspend () -> List<String>
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<String>>(emptyList()) }
    var testing by remember { mutableStateOf(false) }

    HudPanel(Modifier.fillMaxWidth(), title = title) {
        Text(hint, color = HudDim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        keys.forEachIndexed { i, k ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🔑 ${Llm.mask(k)}", color = HudText, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Delete, null, tint = Color(0xFFFF4D6D), modifier = Modifier.size(22.dp).clickable { onRemove(i) })
            }
        }
        if (keys.isEmpty()) Text("Aucune clé pour l'instant.", color = HudDim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        HudTextField(input, { input = it }, Modifier.fillMaxWidth(), placeholder = "Colle une ou plusieurs clés (une par ligne)", singleLine = false, maxLines = 4)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton("Ajouter", filled = true, enabled = input.isNotBlank()) {
                onAdd(input)
                input = ""
            }
            HudButton(if (testing) "Test…" else "Tester", enabled = keys.isNotEmpty() && !testing) {
                scope.launch {
                    testing = true
                    results = onTest()
                    testing = false
                }
            }
        }
        results.forEach { Text(it, color = HudText, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.height(10.dp))
        HudTextField(model, onModel, Modifier.fillMaxWidth(), label = modelLabel)
        Spacer(Modifier.height(8.dp))
        var models by remember { mutableStateOf<List<String>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf("") }
        HudButton(if (loading) "Recherche…" else "Lister les modèles disponibles", enabled = keys.isNotEmpty() && !loading) {
            scope.launch {
                loading = true; err = ""
                try { models = onListModels() } catch (e: Exception) { err = (e.message ?: "erreur").take(160) }
                loading = false
            }
        }
        if (err.isNotBlank()) Text(err, color = Color(0xFFFF4D6D), fontSize = 12.sp)
        if (models.isNotEmpty()) {
            Text("Touche un modèle pour le choisir :", color = HudDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            models.forEach { m -> Box(Modifier.padding(top = 4.dp)) { HudChip(m, selected = m == model) { onModel(m) } } }
        }
    }
}
