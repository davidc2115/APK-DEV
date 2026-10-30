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
import androidx.compose.foundation.horizontalScroll
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

        // ------------------------------------------------------------ Annonces vocales
        item {
            var cible by remember { mutableStateOf("") }
            val picker = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
                val uri = r.data?.getParcelableExtra<android.net.Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                if (cible.isNotBlank() && r.resultCode == android.app.Activity.RESULT_OK) store.setAnnSon(cible, uri?.toString() ?: "")
            }
            HudPanel(Modifier.fillMaxWidth(), title = "Annonces vocales (SMS, mails, notifications)") {
                Text("JARVIS annonce à voix haute l'arrivée d'un SMS, d'un mail ou d'une notification, même en arrière-plan, après la sonnerie de ton choix. Demande l'accès aux notifications (Réglages › Permissions).", color = HudDim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudChip(if (store.annOn.value) "✅ Annonces actives" else "Annonces désactivées", store.annOn.value) { store.annOn.set(!store.annOn.value) }
                    HudChip("🎧 Écouteurs/voiture seulement", store.annPrive.value) { store.annPrive.set(!store.annPrive.value) }
                }
                fr.dd.jarvis.device.Annonces.CATEGORIES.forEach { (cat, label) ->
                    Spacer(Modifier.height(8.dp))
                    Text(label, color = HudText, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("off" to "Silence", "annonce" to "Annonce l'expéditeur", "lecture" to "Lit le message").forEach { (m, t) ->
                            HudChip(t, store.annMode(cat) == m) { store.setAnnMode(cat, m) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    val son = store.annSon(cat)
                    val nomSon = when { son.isBlank() -> "aucune"; son == "default" -> "par défaut"; else -> runCatching { android.media.RingtoneManager.getRingtone(ctx, android.net.Uri.parse(son)).getTitle(ctx) }.getOrDefault("choisie") }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Sonnerie : $nomSon", color = HudDim, fontSize = 12.sp)
                        HudChip("Choisir") {
                            cible = cat
                            picker.launch(Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION or android.media.RingtoneManager.TYPE_ALARM or android.media.RingtoneManager.TYPE_RINGTONE)
                                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, if (son.isBlank() || son == "default") null else android.net.Uri.parse(son)))
                        }
                        HudChip("Tester") { fr.dd.jarvis.device.Annonces.annoncer(ctx, store.annSon(cat), "Ceci est un test d'annonce JARVIS.") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                HudTextField(store.annApps.value, { store.annApps.set(it) }, Modifier.fillMaxWidth(), label = "Applis annoncées (autres notifications) : WhatsApp, Messenger…")
                Spacer(Modifier.height(4.dp))
                Text("Seules les applications listées ici sont annoncées dans « Autres notifications ». JARVIS se tait pendant un appel.", color = HudDim, fontSize = 11.sp)
            }
        }

        // ------------------------------------------------------------ Applications par défaut
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "Applications par défaut") {
                Text("Quand tu demandes un itinéraire ou de la musique, JARVIS te demande l'application s'il y en a plusieurs. Dis « toujours avec Waze » pour la mémoriser, ou « cette fois seulement » pour ne pas la garder.", color = HudDim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                listOf("navigation" to "🧭 Itinéraires", "musique" to "🎵 Musique").forEach { (k, label) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$label : ${store.appPrefs[k] ?: "demander à chaque fois"}", color = HudText, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (store.appPrefs[k] != null) Text("Réinitialiser", color = Color(0xFFFF4D6D), fontSize = 12.sp, modifier = Modifier.clickable { store.setAppPref(k, null) })
                    }
                }
            }
        }

        // ------------------------------------------------------------ Images (Cloudflare)
        item {
            CfPanel(store, app)
        }

        // ------------------------------------------------------------ Serveur (SSH + Home Assistant)
        item {
            ServeurPanel(store, app)
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    HudChip(if (store.analyseGemini.value) "✅ Gemini analyse chaque demande" else "Analyse Gemini désactivée", store.analyseGemini.value) {
                        store.analyseGemini.set(!store.analyseGemini.value)
                    }
                }
                Text(
                    "Les commandes shell demandent toujours une confirmation, quel que soit ce réglage.",
                    color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // ------------------------------------------------------------ Autorisations (centre)
        item {
            var t2 by remember { mutableIntStateOf(0) }
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            val notifOn = remember(t2) { androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
            val batt = remember(t2) { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
            val overlay = remember(t2) { Settings.canDrawOverlays(ctx) }
            val install = remember(t2) { ctx.packageManager.canRequestPackageInstalls() }
            fun g(p: String) = ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val micOk = remember(t2) { g(android.Manifest.permission.RECORD_AUDIO) }
            val calOk = remember(t2) { g(android.Manifest.permission.READ_CALENDAR) && g(android.Manifest.permission.WRITE_CALENDAR) }
            val ctcOk = remember(t2) { g(android.Manifest.permission.READ_CONTACTS) && g(android.Manifest.permission.WRITE_CONTACTS) }
            val smsOk2 = remember(t2) { fr.dd.jarvis.device.Telephony.smsGranted(ctx) && fr.dd.jarvis.device.Telephony.callGranted(ctx) }
            val locOk2 = remember(t2) { fr.dd.jarvis.device.Locator.granted(ctx) }
            val ntfOk = remember(t2) { android.os.Build.VERSION.SDK_INT < 33 || g(android.Manifest.permission.POST_NOTIFICATIONS) }
            var msg by remember { mutableStateOf("") }
            val all = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { t2++ }
            HudPanel(Modifier.fillMaxWidth(), title = "Autorisations — tout en un") {
                StatusRow("Notifications", ntfOk); StatusRow("Micro", micOk); StatusRow("Agenda", calOk); StatusRow("Contacts", ctcOk)
                StatusRow("SMS et appels", smsOk2); StatusRow("Localisation", locOk2)
                StatusRow("Accès aux notifications (lire / résumer)", notifOn)
                StatusRow("Batterie sans restriction (arrière-plan)", batt)
                StatusRow("Affichage par-dessus les autres applis (lancer des applis)", overlay)
                StatusRow("Installer des applis", install)
                StatusRow("Accessibilité", app.phone.accessibilityEnabled())
                Spacer(Modifier.height(8.dp))
                HudButton("Autoriser toutes les permissions Android", filled = true) {
                    all.launch(buildList {
                        add(android.Manifest.permission.RECORD_AUDIO); add(android.Manifest.permission.READ_CALENDAR); add(android.Manifest.permission.WRITE_CALENDAR)
                        add(android.Manifest.permission.READ_CONTACTS); add(android.Manifest.permission.WRITE_CONTACTS); add(android.Manifest.permission.READ_SMS)
                        add(android.Manifest.permission.SEND_SMS); add(android.Manifest.permission.CALL_PHONE); add(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
                    }.toTypedArray())
                }
                Spacer(Modifier.height(6.dp))
                HudButton("⚡ Tout autoriser via ADB (le plus complet)") {
                    scope.launch { msg = "⏳"; msg = fr.dd.jarvis.device.AdbShell.grantAll(ctx); t2++ }
                }
                Text("Avec l'ADB intégré connecté : permissions, accès aux notifications, accessibilité, batterie et arrière-plan sont accordés sans passer par les écrans Android.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Accès notifications", enabled = !notifOn) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    HudButton("Batterie", enabled = !batt) {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    HudButton("Superposition", enabled = !overlay) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    HudButton("Actualiser") { t2++ }
                }
                if (msg.isNotBlank()) Text(msg, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // ------------------------------------------------------------ Arrière-plan et agents autonomes
        item {
            val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
                if (ok) { store.wakeOn.set(true); fr.dd.jarvis.service.JarvisService.start(ctx) }
            }
            var runMsg by remember { mutableStateOf("") }
            HudPanel(Modifier.fillMaxWidth(), title = "Arrière-plan — écoute et agents autonomes") {
                Text("Écoute « Jarvis » : dis « Jarvis, lance un itinéraire vers chez Dupont » même écran éteint. Utilise le micro en continu (notification permanente, consommation batterie).", color = HudDim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                HudChip(if (store.wakeOn.value) "✅ Écoute « Jarvis » active" else "Écoute « Jarvis » désactivée", store.wakeOn.value) {
                    if (store.wakeOn.value) { store.wakeOn.set(false); fr.dd.jarvis.service.JarvisService.stop(ctx) }
                    else if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        store.wakeOn.set(true); fr.dd.jarvis.service.JarvisService.start(ctx)
                    } else micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
                Spacer(Modifier.height(8.dp))
                var picoInput by remember { mutableStateOf(store.picoKey.value) }
                Text("Le mot « Jarvis » est reconnu hors-ligne par un détecteur gratuit installé automatiquement au premier démarrage de l'écoute (aucune clé nécessaire). Facultatif : une clé Picovoice gratuite (console.picovoice.ai › AccessKey) donne un détecteur encore plus fiable. Le mot d'éveil « Ok Google » lui-même est réservé au système : pour appeler JARVIS comme Google, définis-le en assistant par défaut (bouton ci-dessous) et appelle-le par appui long sur le bouton d'alimentation ou d'accueil.", color = HudDim, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                HudTextField(picoInput, { picoInput = it }, Modifier.fillMaxWidth(), label = "Clé Picovoice (facultatif)")
                Spacer(Modifier.height(4.dp))
                HudButton(if (store.picoKey.value == picoInput.trim() && picoInput.isNotBlank()) "Clé enregistrée ✓" else "Enregistrer la clé", filled = true, enabled = picoInput.trim() != store.picoKey.value) {
                    store.picoKey.set(picoInput.trim())
                    if (store.wakeOn.value) { fr.dd.jarvis.service.JarvisService.stop(ctx); fr.dd.jarvis.service.JarvisService.start(ctx) }
                }
                Spacer(Modifier.height(8.dp))
                HudButton("Définir JARVIS comme assistant par défaut") {
                    ctx.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                Spacer(Modifier.height(8.dp))
                var voskReady by remember { mutableStateOf(fr.dd.jarvis.voice.VoskModel.ready(ctx)) }
                var voskMsg by remember { mutableStateOf("") }
                StatusRow("Détecteur hors-ligne de secours (Vosk)", voskReady)
                if (!voskReady) HudButton("Télécharger le détecteur (≈ 40 Mo)") {
                    scope.launch {
                        voskMsg = "⏳ Téléchargement…"
                        voskMsg = fr.dd.jarvis.voice.VoskModel.download(ctx) { p -> voskMsg = "⏳ Téléchargement… $p %" }
                        voskReady = fr.dd.jarvis.voice.VoskModel.ready(ctx)
                        if (voskReady && store.wakeOn.value) { fr.dd.jarvis.service.JarvisService.stop(ctx); fr.dd.jarvis.service.JarvisService.start(ctx) }
                    }
                }
                if (voskMsg.isNotBlank()) Text(voskMsg, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Text("Sans clé Picovoice ni détecteur hors-ligne, l'écoute « Jarvis » ne démarre pas (plus d'écoute en boucle avec bips).", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Conversation ouverte", color = HudDim, fontSize = 12.sp)
                    listOf(15, 25, 45, 90).forEach { sec ->
                        HudChip("$sec s", store.convSeconds.value == sec) { store.convSeconds.set(sec) }
                    }
                }
                Text("Après « Jarvis », tu peux enchaîner les demandes sans répéter le mot, tant que tu parles dans ce délai. Dis « merci » ou « c'est tout » pour terminer.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(6.dp))
                HudChip(if (store.muteBeeps.value) "🔇 Bips coupés pendant l'écoute" else "Bips actifs", store.muteBeeps.value) { store.muteBeeps.set(!store.muteBeeps.value) }
                Spacer(Modifier.height(10.dp))
                HudDivider()
                Spacer(Modifier.height(8.dp))
                Text("Agents autonomes (travaillent seuls, alertes + journal Obsidian « JARVIS/Journal »)", color = LocalAccent.current, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                HudChip(if (store.agentsOn.value) "✅ Agents actifs" else "Agents désactivés", store.agentsOn.value) {
                    store.agentsOn.set(!store.agentsOn.value); fr.dd.jarvis.work.AgentsScheduler.apply(ctx, store)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudChip("📧 Mails", store.agentMails.value) { store.agentMails.set(!store.agentMails.value) }
                    HudChip("💶 Factures/devis", store.agentFactures.value) { store.agentFactures.set(!store.agentFactures.value) }
                    HudChip("🚗 Départs RDV", store.agentPlanning.value) { store.agentPlanning.set(!store.agentPlanning.value) }
                    HudChip("🔔 Notifications", store.agentNotifs.value) { store.agentNotifs.set(!store.agentNotifs.value) }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudChip("🏷️ Ranger les mails dans Gmail", store.agentRanger.value) { store.agentRanger.set(!store.agentRanger.value) }
                    HudChip("📥 Archiver pubs/notifs", store.agentArchiver.value) { store.agentArchiver.set(!store.agentArchiver.value) }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Toutes les", color = HudDim, fontSize = 12.sp)
                    listOf(15, 30, 60).forEach { m ->
                        HudChip("$m min", store.agentMinutes.value == m) { store.agentMinutes.set(m); if (store.agentsOn.value) fr.dd.jarvis.work.AgentsScheduler.apply(ctx, store) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                HudButton("Lancer les agents maintenant") { fr.dd.jarvis.work.AgentsScheduler.runNow(ctx); runMsg = "Agents lancés : alertes et journal dans quelques instants." }
                if (runMsg.isNotBlank()) Text(runMsg, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Text("Android impose au moins 15 min entre deux passages et peut les retarder en économie d'énergie : autorise « Batterie sans restriction ».", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
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


@Composable
private fun CfPanel(store: fr.dd.jarvis.data.Store, app: fr.dd.jarvis.JarvisApp) {
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    HudPanel(Modifier.fillMaxWidth(), title = "Images — comptes Cloudflare Workers AI (rotation)") {
        Text(
            "Pour chaque compte : l'ID de compte (32 caractères, dans l'URL dash.cloudflare.com ou Workers AI › « Utiliser l'API REST ») et un jeton API avec la permission « Workers AI ». Plusieurs comptes = plus d'images gratuites par jour.",
            color = HudDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        store.cfAccounts.forEachIndexed { i, a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🔑 ${a.substringBefore(':').take(6)}… / ${fr.dd.jarvis.ai.Llm.mask(a.substringAfter(':'))}", color = HudText, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Delete, null, tint = Color(0xFFFF4D6D), modifier = Modifier.size(22.dp).clickable { store.removeCfAccount(i) })
            }
        }
        if (store.cfAccounts.isEmpty()) Text("Aucun compte pour l'instant.", color = HudDim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        HudTextField(id, { id = it }, Modifier.fillMaxWidth(), label = "ID de compte")
        Spacer(Modifier.height(6.dp))
        HudTextField(key, { key = it }, Modifier.fillMaxWidth(), label = "Clé API (jeton)")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton("Ajouter", filled = true, enabled = id.isNotBlank() || key.isNotBlank()) {
                val err = store.addCfAccount(id, key)
                if (err == null) { id = ""; key = ""; msg = "✅ Compte enregistré." } else msg = "❌ $err"
            }
            HudButton(if (testing) "Test…" else "Tester", enabled = store.cfAccounts.isNotEmpty() && !testing) {
                scope.launch {
                    testing = true
                    msg = try { val (f, _) = fr.dd.jarvis.ai.ImageGen.generate(app, "a small blue circle on white background"); "✅ Image générée (${f.length() / 1024} Ko)" }
                    catch (e: Exception) { "❌ ${e.message}" }
                    testing = false
                }
            }
        }
        if (msg.isNotBlank()) Text(msg, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(10.dp))
        HudTextField(store.cfModel.value, { store.cfModel.set(it) }, Modifier.fillMaxWidth(), label = "Modèle d'image")
        fr.dd.jarvis.ai.ImageGen.MODELES.forEach { m -> Box(Modifier.padding(top = 4.dp)) { HudChip(m, selected = m == store.cfModel.value) { store.cfModel.set(m) } } }
    }
}


@Composable
private fun ServeurPanel(store: fr.dd.jarvis.data.Store, app: fr.dd.jarvis.JarvisApp) {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    HudPanel(Modifier.fillMaxWidth(), title = "Serveur — SSH et Home Assistant") {
        Text(
            "Permet à JARVIS de piloter ton serveur (commandes, fichiers, Docker) et Home Assistant (appareils, fichiers YAML de configuration). Les commandes destructrices demandent toujours ta confirmation ; chaque fichier modifié est sauvegardé avant écriture.",
            color = HudDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Text("Home Assistant", color = HudText, fontSize = 13.sp)
        HudTextField(store.haUrl.value, { store.haUrl.set(it) }, Modifier.fillMaxWidth(), label = "Adresse (ex. http://192.168.1.20:8123)")
        Spacer(Modifier.height(6.dp))
        HudTextField(store.haToken.value, { store.haToken.set(it) }, Modifier.fillMaxWidth(), label = "Jeton d'accès longue durée (profil HA)", password = true)
        Spacer(Modifier.height(10.dp))
        Text("Serveur SSH", color = HudText, fontSize = 13.sp)
        HudTextField(store.sshHost.value, { store.sshHost.set(it.trim()) }, Modifier.fillMaxWidth(), label = "Adresse du serveur (IP ou nom)")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudTextField(store.sshUser.value, { store.sshUser.set(it.trim()) }, Modifier.weight(1f), label = "Utilisateur")
            HudTextField(store.sshPort.value, { store.sshPort.set(it.filter { c -> c.isDigit() }) }, Modifier.width(90.dp), label = "Port")
        }
        Spacer(Modifier.height(6.dp))
        HudTextField(store.sshPass.value, { store.sshPass.set(it) }, Modifier.fillMaxWidth(), label = "Mot de passe (sert aussi pour sudo / phrase de passe de la clé)", password = true)
        Spacer(Modifier.height(6.dp))
        HudTextField(store.sshKey.value, { store.sshKey.set(it) }, Modifier.fillMaxWidth(), label = "Clé privée (optionnelle, texte PEM/OpenSSH)", singleLine = false, maxLines = 4)
        Spacer(Modifier.height(6.dp))
        HudTextField(store.haDir.value, { store.haDir.set(it.trim()) }, Modifier.fillMaxWidth(), label = "Dossier de config HA sur le serveur (vide = détection auto)")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton(if (busy) "Test…" else "Tester HA", enabled = !busy && fr.dd.jarvis.ai.Serveur.haConfigure(app)) {
                scope.launch {
                    busy = true
                    msg = try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { org.json.JSONObject(fr.dd.jarvis.ai.Serveur.ha(app, "GET", "/api/")).optString("message") }.let { "✅ Home Assistant : $it" }
                    } catch (e: Exception) { "❌ ${e.message}" }
                    busy = false
                }
            }
            HudButton(if (busy) "Test…" else "Tester SSH", enabled = !busy && fr.dd.jarvis.ai.Serveur.sshConfigure(app)) {
                scope.launch {
                    busy = true
                    msg = try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fr.dd.jarvis.ai.Serveur.exec(app, "hostname; uname -sr; id -un", 20).second.trim() }.let { "✅ SSH OK :\n$it" }
                    } catch (e: Exception) { "❌ ${e.message}" }
                    busy = false
                }
            }
        }
        if (store.sshFp.value.isNotBlank()) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🔒 Empreinte mémorisée", color = HudDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("Effacer", color = HudText, fontSize = 12.sp, modifier = Modifier.clickable { store.sshFp.set("") })
        }
        if (msg.isNotBlank()) Text(msg, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
