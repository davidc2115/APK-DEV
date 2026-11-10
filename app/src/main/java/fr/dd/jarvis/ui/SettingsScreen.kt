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
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.verticalScroll
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

/**
 * Réglages rangés en onglets et sous-onglets (voir ONGLETS_REGLAGES) : un sujet par page, et une recherche (🔎) qui
 * retrouve n'importe quel réglage par son nom ou un mot-clé. Le dernier onglet ouvert est retenu.
 */
@Composable
fun SettingsScreen(app: JarvisApp, onAsk: (String) -> Unit = {}) {
    val store = app.store
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val vue = remember { store.reglagesVue.value }
    var onglet by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(vue.substringBefore('/').takeIf { id -> ONGLETS_REGLAGES.any { it.id == id } } ?: "general") }
    var sous by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(vue.substringAfter('/', "")) }
    var recherche by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val ong = ONGLETS_REGLAGES.firstOrNull { it.id == onglet } ?: ONGLETS_REGLAGES.first()
    val sousCourant = ong.sous.firstOrNull { it.first == sous }?.first ?: ong.sous.firstOrNull()?.first.orEmpty()

    val volets: List<VoletReglages> = listOf(
        VoletReglages("general", "apparence", "Apparence", "theme couleur accent transparence tuiles prenom nom") {
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
        },
        VoletReglages("general", "apparence", "Mode présence", "presence dragon plein ecran charge horloge") {
            HudPanel(Modifier.fillMaxWidth(), title = "🐉 Mode présence (plein écran)") {
                HudChip(if (store.presenceAuto.value) "🐉 Mode présence automatique : oui" else "Mode présence automatique : non", store.presenceAuto.value) { store.presenceAuto.set(!store.presenceAuto.value) }
                HudChip(if (store.presenceVerrouillage.value) "🔒 Visible sur écran verrouillé : oui" else "Visible sur écran verrouillé : non", store.presenceVerrouillage.value) { store.presenceVerrouillage.set(!store.presenceVerrouillage.value) }
                Text("Téléphone posé en charge, JARVIS ouvert et plus touché depuis 45 s : le dragon passe en plein écran. Si l'option verrouillage est activée, JARVIS demande à Android d'afficher l'activité au-dessus du verrouillage, de réveiller l'écran à son affichage et de le garder allumé pendant le mode présence. Ouvre d'abord le mode présence avant de verrouiller. Le bouton Marche/Arrêt reste prioritaire : Android peut éteindre l'écran lorsqu'il est pressé, et HyperOS peut limiter l'affichage. Désactive l'option ici à tout moment.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        },
        VoletReglages("general", "batterie", "Batterie & vitesse", "batterie economie energie performance vitesse rapide lent animations hermes analyse gemini") {
            PerformancePanel(app)
        },
        VoletReglages("general", "batterie", "Fonctionnement en arrière-plan", "arriere plan xiaomi hyperos demarrage automatique optimisation batterie restriction") {
            HudPanel(Modifier.fillMaxWidth(), title = "Fonctionnement en arrière-plan (écran éteint, appli fermée)") {
                var tArr by remember { mutableStateOf(0) }
                val battOk = remember(tArr) { (ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) }
                fun ouvrir(vararg intents: Intent) {
                    for (i in intents) if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
                }
                val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                Text("Pour que l'écoute tourne en permanence, écran éteint et appli fermée :", color = HudText, fontSize = 12.sp)
                StatusRow("Batterie sans restriction", battOk)
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Batterie", enabled = !battOk) {
                        ouvrir(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")), details)
                    }
                    HudButton("Démarrage auto (Xiaomi)") {
                        ouvrir(Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")), details)
                    }
                    HudButton("Économie d'énergie (Xiaomi)") {
                        ouvrir(
                            Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY").putExtra("package_name", ctx.packageName).putExtra("package_label", "JARVIS"),
                            Intent().setComponent(android.content.ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                                .putExtra("package_name", ctx.packageName).putExtra("package_label", "JARVIS"),
                            details
                        )
                    }
                    HudButton("Actualiser") { tArr++ }
                }
                Text("Sur Xiaomi : active « Démarrage automatique », mets l'économie d'énergie sur « Aucune restriction », et verrouille JARVIS dans les applis récentes (appui long sur sa vignette → cadenas). Après un redémarrage du téléphone, Android interdit de rallumer le micro tout seul : une notification « Relancer l'écoute » apparaît, un toucher suffit. Définir JARVIS comme assistant par défaut (bouton au-dessus) lui permet aussi de rouvrir le micro plus facilement.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        },
        VoletReglages("general", "autorisations", "Autorisations", "autorisations permissions acces") {
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
            val nearbyWifiOk = remember(t2) { android.os.Build.VERSION.SDK_INT < 33 || g(android.Manifest.permission.NEARBY_WIFI_DEVICES) }
            val localNetworkOk = remember(t2) { android.os.Build.VERSION.SDK_INT < 36 || g("android.permission.ACCESS_LOCAL_NETWORK") }
            var msg by remember { mutableStateOf("") }
            val all = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { t2++ }
            HudPanel(Modifier.fillMaxWidth(), title = "Centre d'administration réseau") {
                val wifiOn = remember(t2) {
                    runCatching { ctx.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled == true }.getOrDefault(false)
                }
                val adbOn = remember(t2) { fr.dd.jarvis.device.AdbShell.connected() }
                val shizukuOn = remember(t2) { ShizukuShell.running() && ShizukuShell.granted() }
                val freeboxAssociee = store.freeboxAppToken.value.isNotBlank()
                var diagReseau by remember { mutableStateOf("") }
                var droitsFreebox by remember { mutableStateOf("") }
                val nearbyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { t2++ }
                val localNetLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { t2++ }
                val adbNotifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    fr.dd.jarvis.device.AdbShell.postPairingNotification(ctx)
                }
                StatusRow("Wi-Fi du téléphone", wifiOn)
                StatusRow("Découverte Wi-Fi à proximité", nearbyWifiOk)
                if (Build.VERSION.SDK_INT >= 36) StatusRow("Accès Android au réseau local", localNetworkOk)
                StatusRow("ADB sans fil connecté", adbOn)
                StatusRow("Shizuku démarré et autorisé", shizukuOn)
                StatusRow("Freebox associée", freeboxAssociee)
                Text(
                    "Ce centre distingue les droits Android, l'accès ADB/Shizuku et les droits propres à la Freebox. Il ne contourne pas les validations système : l'appairage ADB, l'autorisation Shizuku et les droits Freebox doivent être acceptés sur leurs écrans respectifs.",
                    color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 5.dp)
                )
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Wi-Fi", filled = true) {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    HudButton("Débogage sans fil") {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    HudButton("Permissions de l'application") {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Appairer ADB") {
                        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            adbNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            fr.dd.jarvis.device.AdbShell.postPairingNotification(ctx)
                        }
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    HudButton("Connecter ADB") {
                        scope.launch {
                            diagReseau = fr.dd.jarvis.device.AdbShell.connect(ctx)
                            t2++
                        }
                    }
                    HudButton("Autoriser Shizuku", enabled = ShizukuShell.running() && !ShizukuShell.granted()) {
                        ShizukuShell.requestPermission()
                    }
                }
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Autoriser Wi-Fi proche", enabled = Build.VERSION.SDK_INT >= 33 && !nearbyWifiOk) {
                        if (Build.VERSION.SDK_INT >= 33) nearbyLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                    }
                    if (Build.VERSION.SDK_INT >= 36) HudButton("Autoriser réseau local", enabled = !localNetworkOk) {
                        localNetLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
                    }
                    HudButton("Actualiser les droits") { t2++ }
                }
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Vérifier les droits Freebox", enabled = freeboxAssociee) {
                        scope.launch {
                            droitsFreebox = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching { fr.dd.jarvis.ai.FreeboxOs.verifierAcces(app) }
                                    .getOrElse { "Diagnostic Freebox impossible : " + (it.message ?: it.javaClass.simpleName) }
                            }
                        }
                    }
                    HudButton("Diagnostic réseau ADB", enabled = adbOn) {
                        scope.launch {
                            diagReseau = fr.dd.jarvis.device.AdbShell.exec(
                                ctx,
                                "ip -brief addr; echo ROUTES; ip route; echo WIFI; cmd wifi status; echo LOCAL_NETWORK_PERMISSION; pm check-permission android.permission.ACCESS_LOCAL_NETWORK ${ctx.packageName}"
                            )
                        }
                    }
                }
                if (droitsFreebox.isNotBlank()) Text(droitsFreebox, color = HudText, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                if (diagReseau.isNotBlank()) Text(diagReseau, color = HudText, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
                Text(
                    "Les redirections de ports, réservations DHCP, VPN et appareils connus se gèrent dans le volet Maison › Freebox. Pour le serveur Debian/Plex, il faut une session SSH distincte : les droits de la box ne donnent pas automatiquement les droits Linux.",
                    color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
                )
            }
            HudPanel(Modifier.fillMaxWidth(), title = "Autorisations — tout en un") {
                StatusRow("Notifications", ntfOk); StatusRow("Micro", micOk); StatusRow("Agenda", calOk); StatusRow("Contacts", ctcOk)
                StatusRow("SMS et appels", smsOk2); StatusRow("Localisation", locOk2)
                StatusRow("Appareils Wi-Fi à proximité", nearbyWifiOk)
                if (android.os.Build.VERSION.SDK_INT >= 36) StatusRow("Accès au réseau local (Android 16+)", localNetworkOk)
                StatusRow("Accès aux notifications (lire / résumer)", notifOn)
                StatusRow("Batterie sans restriction (arrière-plan)", batt)
                StatusRow("Superposition — confirmations sur les autres applis", overlay)
                StatusRow("Installer des applis", install)
                Text(if (overlay) "✅ Les confirmations JARVIS peuvent s'afficher en fenêtre flottante au-dessus de l'application utilisée, avec les boutons Accepter et Refuser." else "Pour afficher les confirmations au-dessus des autres applications, active « Superposition » ci-dessous. Sans cette autorisation, JARVIS utilise uniquement la notification (si elle est autorisée).", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
                StatusRow3("Accessibilité", app.phone.accessibilityEnabled(), app.phone.accessibilityInSettings())
                Spacer(Modifier.height(8.dp))
                HudButton("Autoriser toutes les permissions Android", filled = true) {
                    all.launch(buildList {
                        add(android.Manifest.permission.RECORD_AUDIO); add(android.Manifest.permission.READ_CALENDAR); add(android.Manifest.permission.WRITE_CALENDAR)
                        add(android.Manifest.permission.READ_CONTACTS); add(android.Manifest.permission.WRITE_CONTACTS); add(android.Manifest.permission.READ_SMS)
                        add(android.Manifest.permission.SEND_SMS); add(android.Manifest.permission.CALL_PHONE); add(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (android.os.Build.VERSION.SDK_INT >= 33) { add(android.Manifest.permission.POST_NOTIFICATIONS); add(android.Manifest.permission.NEARBY_WIFI_DEVICES) }
                        if (android.os.Build.VERSION.SDK_INT >= 36) add("android.permission.ACCESS_LOCAL_NETWORK")
                    }.toTypedArray())
                }
                Spacer(Modifier.height(6.dp))
                HudButton("⚡ Tout autoriser via ADB (le plus complet)") {
                    scope.launch { msg = "⏳"; msg = fr.dd.jarvis.device.AdbShell.grantAll(ctx); t2++ }
                }
                Text("Avec l'ADB intégré connecté : permissions, accès aux notifications, accessibilité, batterie et arrière-plan sont accordés sans passer par les écrans Android.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Accès notifications", enabled = !notifOn) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    HudButton("Forcer la reconnexion") { msg = app.phone.forcerReconnexionServices() }
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
        },
        VoletReglages("ia", "cles", "Clés Groq", "groq cles api modele cerveau rotation") {
            if (!store.apiKeysSecureAvailable) {
                Text(
                    "⚠️ Le stockage chiffré Android est indisponible. Les anciennes clés restent conservées, mais Jarvis bloque leur modification et leur transfert vers Hermès pour éviter une nouvelle sauvegarde en clair.",
                    color = Color(0xFFFFB74D), fontSize = 12.sp
                )
            }
            KeyPanel(
                title = "Cerveau — clés Groq (rotation automatique)",
                hint = "Une clé par ligne. À chaque requête JARVIS passe à la clé suivante ; une clé saturée (429) est mise en pause.",
                keys = store.groqKeys,
                onAdd = {
                    if (store.apiKeysSecureAvailable) {
                        store.addGroqKeys(it)
                        if (store.hermesInstalle.value) fr.dd.jarvis.hermes.HermesTermux.synchroniserCles(app)
                    }
                },
                onRemove = {
                    if (store.apiKeysSecureAvailable) {
                        store.removeGroqKey(it)
                        if (store.hermesInstalle.value) fr.dd.jarvis.hermes.HermesTermux.synchroniserCles(app)
                    }
                },
                onTest = { app.llm.testGroqKeys() },
                modelLabel = "Modèle Groq", model = store.groqModel.value, onModel = { store.groqModel.set(it) },
                onListModels = { app.llm.listGroqModels() }
            )
        },
        VoletReglages("ia", "cles", "Clés Gemini", "gemini cles api modele agents rotation") {
            KeyPanel(
                title = "Agents — clés Gemini (rotation automatique)",
                hint = "Utilisées pour le planning, les mails et les contacts. Sans clé Gemini, les agents utilisent Groq.",
                keys = store.geminiKeys,
                onAdd = {
                    if (store.apiKeysSecureAvailable) {
                        store.addGeminiKeys(it)
                        if (store.hermesInstalle.value) fr.dd.jarvis.hermes.HermesTermux.synchroniserCles(app)
                    }
                },
                onRemove = {
                    if (store.apiKeysSecureAvailable) {
                        store.removeGeminiKey(it)
                        if (store.hermesInstalle.value) fr.dd.jarvis.hermes.HermesTermux.synchroniserCles(app)
                    }
                },
                onTest = { app.llm.testGeminiKeys() },
                modelLabel = "Modèle Gemini", model = store.geminiModel.value, onModel = { store.geminiModel.set(it) },
                onListModels = { app.llm.listGeminiModels() }
            )
        },
        VoletReglages("ia", "cles", "Clés OpenAI", "openai clé api modèle chat gpt rotation fournisseur") {
            if (!store.apiKeysSecureAvailable) {
                Text("⚠️ Stockage chiffré Android indisponible : les clés OpenAI ne peuvent pas être modifiées.", color = Color(0xFFFFB74D), fontSize = 12.sp)
            }
            KeyPanel(
                title = "OpenAI — fournisseur IA (rotation automatique)",
                hint = "Clés API de la plateforme OpenAI. Elles sont distinctes d'un abonnement ChatGPT et peuvent entraîner une facturation API.",
                keys = store.openaiKeys,
                onAdd = {
                    if (store.apiKeysSecureAvailable) {
                        store.addOpenAiKeys(it)
                        if (store.hermesInstalle.value) fr.dd.jarvis.hermes.HermesTermux.synchroniserCles(app)
                    }
                },
                onRemove = {
                    if (store.apiKeysSecureAvailable) {
                        store.removeOpenAiKey(it)
                        if (store.hermesInstalle.value) fr.dd.jarvis.hermes.HermesTermux.synchroniserCles(app)
                    }
                },
                onTest = { app.llm.testOpenAiKeys() },
                modelLabel = "Modèle OpenAI", model = store.openaiModel.value, onModel = { store.openaiModel.set(it) },
                onListModels = { app.llm.listOpenAiModels() }
            )
        },
        VoletReglages("ia", "hermes", "Hermès", "hermes termux cerveau principal installer configurer demarrer repartition mode adulte modele vitesse") {
            val h = fr.dd.jarvis.hermes.HermesTermux
            var hTick by remember { mutableIntStateOf(0) }
            var hMsg by remember { mutableStateOf("") }
            var enLigne by remember { mutableStateOf(false) }
            val termuxOk = remember(tick, hTick) { h.termuxInstalle(ctx) }
            val permOk = remember(tick, hTick) { h.permissionOk(ctx) }
            androidx.compose.runtime.LaunchedEffect(tick, hTick, h.message) {
                if (h.message.isNotBlank()) hMsg = h.message
                enLigne = fr.dd.jarvis.hermes.Hermes.enLigne(force = true)
            }
            val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { hTick++ }
            HudPanel(Modifier.fillMaxWidth(), title = "Hermès — cerveau principal (Termux)") {
                Text(
                    "Hermès (Nous Research) tourne dans Termux sur ce téléphone et devient le cerveau principal : il réfléchit avec Gemini " +
                        "et agit avec tous les outils de JARVIS. Groq garde les commandes rapides (appels, SMS, musique, agenda, domotique, Android Auto), " +
                        "Gemini reste l'agent spécialisé (analyse, mails, planning, images).",
                    color = HudDim, fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                StatusRow("Termux installé", termuxOk)
                StatusRow("Permission « commandes Termux » accordée à JARVIS", permOk)
                StatusRow("Hermès en ligne (127.0.0.1:${h.PORT_API})", enLigne)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudButton(if (termuxOk) "1. Termux ✅" else "1. Installer Termux", Modifier.fillMaxWidth(), enabled = !termuxOk, filled = !termuxOk) {
                        scope.launch { hMsg = "⏳ Téléchargement de Termux…"; hMsg = h.installerTermux(ctx); hTick++ }
                    }
                    HudButton("2. Autoriser JARVIS dans Termux", Modifier.fillMaxWidth(), enabled = termuxOk, filled = termuxOk && !enLigne) {
                        if (!permOk && !fr.dd.jarvis.device.AdbShell.hasKey(ctx)) permLauncher.launch(h.PERMISSION)
                        else scope.launch { hMsg = "⏳ Autorisation…"; hMsg = h.autoriser(app); hTick++ }
                    }
                    HudButton(if (store.hermesInstalle.value) "3. Réinstaller / mettre à jour Hermès" else "3. Installer Hermès", Modifier.fillMaxWidth(), enabled = termuxOk && permOk && store.apiKeysSecureAvailable, filled = termuxOk && permOk && store.apiKeysSecureAvailable && !store.hermesInstalle.value) {
                        fr.dd.jarvis.hermes.PontMcp.progression.clear()
                        hMsg = h.installer(app)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Configurer", enabled = permOk && store.apiKeysSecureAvailable) {
                        scope.launch {
                            hMsg = "⏳ Préparation de la configuration Hermès…"
                            try {
                                val resultat = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    h.configurer(app)
                                }
                                hMsg = resultat
                            } catch (e: Exception) {
                                hMsg = "❌ Préparation Hermès interrompue : ${e.message ?: e.javaClass.simpleName}"
                            } finally {
                                hTick++
                            }
                        }
                    }
                    HudButton("Démarrer", enabled = permOk && !enLigne) { hMsg = h.demarrer(app) }
                    HudButton("Arrêter", enabled = permOk && enLigne) { hMsg = h.arreter(app) }
                    HudButton("Actualiser") { hTick++ }
                }

                var recoveryConfirm by remember { mutableStateOf("") }
                Spacer(Modifier.height(8.dp))
                Text("Sauvegarde et récupération", color = HudDim, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Sauvegarder") { hMsg = h.sauvegarder(app) }
                    HudButton("Diagnostic") { hMsg = h.diagnostiquer(app) }
                    HudButton("Restaurer") {
                        if (recoveryConfirm != "restore") {
                            recoveryConfirm = "restore"
                            hMsg = "⚠️ Retouche « Restaurer » pour confirmer la restauration de la dernière sauvegarde."
                        } else {
                            recoveryConfirm = ""
                            hMsg = h.restaurerDerniereSauvegarde(app)
                        }
                    }
                    HudButton("Réinstaller") {
                        if (recoveryConfirm != "reinstall") {
                            recoveryConfirm = "reinstall"
                            hMsg = "⚠️ Cette opération sauvegarde ~/.hermes puis réinstalle le moteur. Retouche « Réinstaller » pour confirmer."
                        } else {
                            recoveryConfirm = ""
                            hMsg = h.reinstallerComplet(app)
                        }
                    }
                }
                if (hMsg.isNotBlank()) Text(hMsg, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                val lignes = fr.dd.jarvis.hermes.PontMcp.progression
                if (lignes.isNotEmpty()) Column(Modifier.padding(top = 6.dp)) {
                    lignes.takeLast(8).forEach { Text(it, color = HudText, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                }
                Spacer(Modifier.height(8.dp))
                var vitesse by remember { mutableStateOf("") }
                var mesure by remember { mutableStateOf(false) }
                HudButton(if (mesure) "Mesure en cours…" else "Mesurer la vitesse d'Hermès", Modifier.fillMaxWidth(), enabled = !mesure) {
                    scope.launch {
                        mesure = true
                        vitesse = runCatching { fr.dd.jarvis.hermes.Hermes.mesurerVitesse(app) }.getOrElse { "Mesure impossible : ${it.message}" }
                        mesure = false
                    }
                }
                if (vitesse.isNotBlank()) Text(vitesse, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                Text("Répartition des demandes", color = HudDim, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("tout" to "Hermès pour tout (recommandé)", "auto" to "Jarvis d'abord", "off" to "Jarvis seul").forEach { (id, nom) ->
                        HudChip(nom, selected = store.hermesMode.value == id) { store.hermesMode.set(id) }
                    }
                }
                Text(
                    when (store.hermesMode.value) {
                        "tout" -> "Toutes les demandes passent par Hermès (réponses marquées 🪽) : mémoire longue, notes, skills et plugins, " +
                            "confie les actions du téléphone à l'agent Jarvis, le travail lourd à ses sous-agents Gemini et Groq, et crée tes automatismes. " +
                            "Seules les commandes instantanées du téléphone (lampe torche, volume, Wi-Fi, Bluetooth, lecture/pause) sont exécutées directement, sans IA. " +
                            "Si Hermès est hors ligne, Jarvis répond à sa place et le signale (⚠️)."
                        "off" -> "Jarvis répond uniquement avec sa partie rapide ; le moteur approfondi (Hermès) n'est pas utilisé."
                        else -> "Jarvis et Hermès ne forment qu'un seul assistant : une voix, une conversation, une mémoire. " +
                            "Jarvis répond tout de suite avec sa partie rapide ; si ce n'est pas suffisant (pas d'outil, rien trouvé, information manquante, erreur), " +
                            "il approfondit automatiquement avec son moteur Hermès (notes, skills, sous-agents). Automatismes, demandes en plusieurs étapes, " +
                            "serveur/SSH et recherche sur internet passent directement par le moteur approfondi. Jamais besoin de dire « Hermès »."
                    },
                    color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
                )
                if (store.hermesMode.value == "tout") {
                    Spacer(Modifier.height(6.dp))
                    HudChip(if (store.hermesRapide.value) "⚡ Réponses rapides : oui" else "Réponses rapides : non", store.hermesRapide.value) { store.hermesRapide.set(!store.hermesRapide.value) }
                    Text("Commandes simples (appel, SMS, musique, lumières, chauffage, agenda, rappel…) faites directement par Jarvis, en un seul appel d'IA ; Hermès prend le relais s'il n'y arrive pas.",
                        color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.height(8.dp))
                HudChip("Écriture adulte consensuelle", selected = store.modeAdulte.value) {
                    store.modeAdulte.set(!store.modeAdulte.value)
                }
                Text(
                    "Autorise un ton mature, sensuel et suggestif dans les créations entre adultes consentants. Ne désactive pas les protections du modèle ni celles des services externes.",
                    color = HudDim, fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                HudTextField(store.hermesModele.value, { store.hermesModele.set(it.trim()) }, Modifier.fillMaxWidth(),
                    label = "Modèle Gemini d'Hermès (vide = ${store.geminiModel.value.ifBlank { "modèle des agents" }})")
                Text(
                    "Après un changement de clé ou de modèle, touche « Configurer ». Avec l'ADB intégré appairé, l'étape 2 est automatique ; " +
                        "sinon colle la commande copiée dans Termux une seule fois. Xiaomi : batterie de Termux sur « Aucune restriction ».",
                    color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        VoletReglages("ia", "extensions", "Extensions Hermès", "skills plugins mcp outils suno extensions catalogue") {
            var extTab by remember { mutableStateOf("skills") }
            var extName by remember { mutableStateOf("") }
            var extMsg by remember { mutableStateOf("") }
            var extConfirm by remember { mutableStateOf("") }
            fun runHermesExtension(args: List<String>, confirm: Boolean = false) {
                val key = args.joinToString(" ")
                if (confirm && extConfirm != key) {
                    extConfirm = key
                    extMsg = "⚠️ Confirme en retouchant l'action : $key"
                    return
                }
                extConfirm = ""
                fr.dd.jarvis.hermes.PontMcp.progression.clear()
                extMsg = fr.dd.jarvis.hermes.HermesTermux.lancerExtension(app, args, "Centre d'extensions Hermès")
            }
            HudPanel(Modifier.fillMaxWidth(), title = "Centre d'extensions — Hermès") {
                Text("Gestion directe des Skills, Plugins, serveurs MCP et outils de ton installation Hermes. Les commandes sont exécutées dans Debian, sans réinstaller Hermes ni réécrire sa configuration.", color = HudDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("skills" to "Skills", "plugins" to "Plugins", "mcp" to "MCP", "tools" to "Outils").forEach { (id, label) ->
                        HudChip(label, selected = extTab == id) { extTab = id; extConfirm = ""; extMsg = "" }
                    }
                }
                Spacer(Modifier.height(8.dp))
                HudTextField(extName, { extName = it }, Modifier.fillMaxWidth(), label = when (extTab) {
                    "skills" -> "Nom ou identifiant de Skill"
                    "plugins" -> "Nom, identifiant ou dépôt Git du Plugin"
                    "mcp" -> "Nom du serveur ou identifiant du catalogue"
                    else -> "Nom de l'outil"
                })
                Spacer(Modifier.height(8.dp))
                when (extTab) {
                    "skills" -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HudButton("Catalogue disponible") { runHermesExtension(listOf("skills", "browse", "--source", "official")) }
                            HudButton("Skills installés") { runHermesExtension(listOf("skills", "list")) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HudButton("Rechercher") { if (extName.isNotBlank()) runHermesExtension(listOf("skills", "search", extName.trim())) }
                            HudButton("Inspecter") { if (extName.isNotBlank()) runHermesExtension(listOf("skills", "inspect", extName.trim())) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HudButton("Installer") { if (extName.isNotBlank()) runHermesExtension(listOf("skills", "install", extName.trim()), true) }
                            HudButton("Mettre à jour") { runHermesExtension(listOf("skills", "update"), true) }
                            HudButton("Vérifier") { runHermesExtension(listOf("skills", "check")) }
                        }
                        HudButton("Désinstaller") { if (extName.isNotBlank()) runHermesExtension(listOf("skills", "uninstall", extName.trim()), true) }
                        Text("Le catalogue officiel peut être parcouru, puis recherche aussi dans les registres skills.sh et browse.sh en saisissant un mot-clé. L'installation lance le contrôle de sécurité d'Hermès.", color = HudDim, fontSize = 11.sp)
                    }
                    "plugins" -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HudButton("Catalogue complet") { runHermesExtension(listOf("plugins", "search", "--json")) }
                            HudButton("Plugins installés") { runHermesExtension(listOf("plugins", "list")) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HudButton("Rechercher") { if (extName.isNotBlank()) runHermesExtension(listOf("plugins", "search", extName.trim(), "--json")) }
                            HudButton("Capacités") { if (extName.isNotBlank()) runHermesExtension(listOf("plugins", "capabilities", extName.trim())) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HudButton("Télécharger désactivé") { if (extName.isNotBlank()) runHermesExtension(listOf("plugins", "install", extName.trim(), "--no-deps", "--no-enable"), true) }
                            HudButton("Activer sans override") { if (extName.isNotBlank()) runHermesExtension(listOf("plugins", "enable", extName.trim(), "--no-allow-tool-override"), true) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HudButton("Désactiver") { if (extName.isNotBlank()) runHermesExtension(listOf("plugins", "disable", extName.trim()), true) }
                            HudButton("Supprimer") { if (extName.isNotBlank()) runHermesExtension(listOf("plugins", "remove", extName.trim()), true) }
                        }
                        Text("Le téléchargement laisse le plugin désactivé et n'installe pas ses dépendances. Consulte ses capacités avant activation ; l'autorisation de remplacer les outils intégrés n'est jamais accordée ici.", color = HudDim, fontSize = 11.sp)
                    }
                    "mcp" -> {
                        Text("Suno — création musicale", color = LocalAccent.current, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            "Connexion officielle Suno MCP (bêta). Hermès pourra préparer les paroles, transmettre le style musical et créer le morceau dans ton compte Suno. La connexion OAuth demande une autorisation dans le navigateur et utilise les crédits de ton compte.",
                            color = HudDim, fontSize = 11.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HudButton(if (store.sunoMcpActive.value) "Déconnecter Suno" else "Connecter Suno", filled = !store.sunoMcpActive.value) {
                                if (!store.hermesInstalle.value) {
                                    extMsg = "❌ Installe d'abord Hermès dans le panneau précédent."
                                } else {
                                    val active = !store.sunoMcpActive.value
                                    store.sunoMcpActive.set(active)
                                    extMsg = fr.dd.jarvis.hermes.HermesTermux.configurer(app)
                                    if (extMsg.startsWith("⛔") || extMsg.startsWith("ℹ️")) store.sunoMcpActive.set(!active)
                                }
                            }
                            HudButton("Autoriser le compte", enabled = store.sunoMcpActive.value) {
                                runHermesExtension(listOf("mcp", "login", "suno"))
                            }
                            HudButton("Tester Suno", enabled = store.sunoMcpActive.value) {
                                runHermesExtension(listOf("mcp", "test", "suno"))
                            }
                        }
                        Text(
                            if (store.sunoMcpActive.value) "Suno est ajouté à la configuration d'Hermès. Termine l'autorisation du compte si le navigateur la demande." else "Connexion désactivée. Aucun crédit Suno n'est utilisé.",
                            color = HudDim, fontSize = 11.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HudButton("Catalogue MCP complet") { runHermesExtension(listOf("mcp", "catalog")) }
                            HudButton("MCP installés") { runHermesExtension(listOf("mcp", "list")) }
                        }
                        HudButton("Tester le serveur") { if (extName.isNotBlank()) runHermesExtension(listOf("mcp", "test", extName.trim())) }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HudButton("Installer catalogue") { if (extName.isNotBlank()) runHermesExtension(listOf("mcp", "install", extName.trim()), true) }
                            HudButton("Configurer") { if (extName.isNotBlank()) runHermesExtension(listOf("mcp", "configure", extName.trim())) }
                            HudButton("Supprimer") { if (extName.isNotBlank() && !extName.trim().equals("jarvis", ignoreCase = true)) runHermesExtension(listOf("mcp", "remove", extName.trim()), true) }
                        }
                        Text("Le pont MCP jarvis reliant Hermes à ton téléphone est protégé contre la suppression depuis ce panneau. Pour ajouter un serveur personnalisé, utilise hermes mcp add avec une URL ou une commande.", color = HudDim, fontSize = 11.sp)
                    }
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HudButton("Tous les outils") { runHermesExtension(listOf("hermes-agent", "--list-tools")) }
                            HudButton("Outils actifs") { runHermesExtension(listOf("tools", "--summary")) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HudButton("Activer") { if (extName.isNotBlank()) runHermesExtension(listOf("tools", "enable", extName.trim(), "--platform", "cli"), true) }
                            HudButton("Désactiver") { if (extName.isNotBlank()) runHermesExtension(listOf("tools", "disable", extName.trim(), "--platform", "cli"), true) }
                        }
                        Text("La liste complète affiche les outils déclarés par le moteur. Les outils réellement utilisables dépendent des plateformes, des autorisations, des clés API et des extensions activées.", color = HudDim, fontSize = 11.sp)
                    }
                }
                if (extMsg.isNotBlank()) Text(extMsg, color = HudText, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(8.dp))
                Text("Sortie Hermes", color = HudDim, fontSize = 12.sp)
                val extLines = fr.dd.jarvis.hermes.PontMcp.progression
                if (extLines.isEmpty()) Text("Aucun résultat pour le moment. Lance une commande ci-dessus.", color = HudDim, fontSize = 11.sp)
                else Column(Modifier.height(220.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    extLines.takeLast(40).forEach { Text(it, color = HudText, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                }
            }
        },
        VoletReglages("ia", "memoire", "Mémoire", "memoire habitudes apprentissage preferences consignes") {
            var memoryCount by remember { mutableIntStateOf(0) }
            var learningCount by remember { mutableIntStateOf(0) }
            var learningSummary by remember { mutableStateOf("Chargement de la mémoire…") }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    app.memo.all() to app.memo.apprentissagesEnCours()
                }
                memoryCount = snapshot.first.size
                learningSummary = snapshot.second
                learningCount = snapshot.second.lines().count { it.trimStart().startsWith("- ") }
            }
            HudPanel(Modifier.fillMaxWidth(), title = "Mémoire intelligente et habitudes") {
                Text("Mémoire durable : $memoryCount rubrique(s) enregistrée(s). Les préférences observées restent candidates jusqu'à trois preuves distinctes ; les données sensibles sont exclues de l'apprentissage automatique.", color = HudDim, fontSize = 11.sp)
                AgentStatusRow("Consignes durables", memoryCount > 0, "$memoryCount rubrique(s)")
                AgentStatusRow("Habitudes candidates", learningCount > 0, if (learningCount == 0) "aucune" else "$learningCount en apprentissage")
                if (learningSummary.isNotBlank()) Text(learningSummary.take(900), color = HudText, fontSize = 11.sp)
                HudButton("Consulter les préférences en apprentissage", Modifier.fillMaxWidth()) {
                    onAsk("Affiche les préférences candidates en cours d'apprentissage et le nombre de preuves distinctes pour chacune.")
                }
                HudButton("Consulter ma mémoire durable", Modifier.fillMaxWidth()) {
                    onAsk("Affiche les consignes durables enregistrées dans ma mémoire JARVIS.")
                }
            }
        },
        VoletReglages("ia", "medias", "Images", "images cloudflare workers generation dessin") {
            CfPanel(store, app)
        },
        VoletReglages("ia", "medias", "Vidéo IA", "video fal wan generation animation") {
            PollinationsPanel(store)
        },
        VoletReglages("voix", "ecoute", "Écoute « Jarvis »", "ecoute jarvis mot eveil micro picovoice vosk detecteur assistant par defaut econome batterie") {
            val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
                if (ok) { store.wakeOn.set(true); fr.dd.jarvis.service.JarvisService.start(ctx) }
            }
            HudPanel(Modifier.fillMaxWidth(), title = "👂 Écoute « Jarvis »") {
                Text("Écoute « Jarvis » : dis « Jarvis », attends le petit bip, puis ta demande (« lance un itinéraire vers chez Dupont »), même écran éteint. Utilise le micro en continu (notification permanente, consommation batterie).", color = HudDim, fontSize = 12.sp)
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
                HudChip(if (store.ecouteEconome.value) "🔋 Écoute économe : oui" else "Écoute économe : non", store.ecouteEconome.value) {
                    store.ecouteEconome.set(!store.ecouteEconome.value)
                    if (store.wakeOn.value) { fr.dd.jarvis.service.JarvisService.stop(ctx); fr.dd.jarvis.service.JarvisService.start(ctx) }
                }
                Text("Écoute économe (détecteur hors-ligne) : le détecteur ne travaille que lorsqu'il y a du son, pas pendant le silence — beaucoup moins de batterie, surtout la nuit. Si « Jarvis » est moins bien entendu de loin ou avec la télé allumée, désactive-la.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
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
            }
        },
        VoletReglages("voix", "conversation", "Conversation vocale", "conversation silence couper parole duplex bips fin") {
            HudPanel(Modifier.fillMaxWidth(), title = "💬 Conversation à la voix") {
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Fin après", color = HudDim, fontSize = 12.sp)
                    listOf(4, 6, 8, 12).forEach { sec ->
                        HudChip("$sec s de silence", store.silenceSec.value == sec) { store.silenceSec.set(sec) }
                    }
                }
                Text("Après « Jarvis », tu enchaînes les demandes sans répéter le mot. Quand JARVIS a répondu et que tu ne dis plus rien pendant ce délai, la conversation se referme d'elle-même (petit bip descendant). « Merci » ou « c'est tout » la termine aussi.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudChip(if (store.coupeParole.value) "✋ Couper la parole : oui" else "Couper la parole : non", store.coupeParole.value) {
                        store.coupeParole.set(!store.coupeParole.value)
                        if (store.wakeOn.value) { fr.dd.jarvis.service.JarvisService.stop(ctx); fr.dd.jarvis.service.JarvisService.start(ctx) }
                    }
                    HudChip(if (store.duplex.value) "📞 Mode duplex : oui" else "Mode duplex : non", store.duplex.value) {
                        store.duplex.set(!store.duplex.value)
                        if (store.wakeOn.value) { fr.dd.jarvis.service.JarvisService.stop(ctx); fr.dd.jarvis.service.JarvisService.start(ctx) }
                    }
                }
                Text("Couper la parole : si tu reparles pendant que JARVIS répond, il se tait et t'écoute. Mode duplex (haut-parleur du téléphone seulement) : sa voix passe en mode « appel » le temps de la réponse, pour que l'annulation d'écho du téléphone ne confonde pas sa voix avec la tienne — la coupure est bien plus fiable. Le volume suit alors celui des appels ; désactive-le si la voix te semble trop faible.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(6.dp))
                HudChip(if (store.muteBeeps.value) "🔇 Bips coupés pendant l'écoute" else "Bips actifs", store.muteBeeps.value) { store.muteBeeps.set(!store.muteBeeps.value) }
            }
        },
        VoletReglages("voix", "voix", "Voix de JARVIS", "voix naturelle gemini personnage android tts homme femme") {
            HudPanel(Modifier.fillMaxWidth(), title = "🗣️ Voix de JARVIS") {
                var etatVoix by remember { mutableStateOf(fr.dd.jarvis.voice.VoixNaturelle.etat(app)) }
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudChip("🎙️ Voix naturelle (Gemini)", store.voixNaturelle.value) {
                        store.voixNaturelle.set(true); fr.dd.jarvis.voice.VoixNaturelle.reactiver(); etatVoix = fr.dd.jarvis.voice.VoixNaturelle.etat(app)
                    }
                    HudChip("🤖 Voix Android", !store.voixNaturelle.value) { store.voixNaturelle.set(false); etatVoix = fr.dd.jarvis.voice.VoixNaturelle.etat(app) }
                }
                // Personnages de voix : homme, femme, jeune, âgé, IA… (voix Gemini + description ; voix Android du même genre en repli)
                val vn = fr.dd.jarvis.voice.VoixNaturelle
                val perso = vn.personnage(app)
                Text("Personnage : ${perso.libelle}", color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                vn.PERSONNAGES.groupBy { it.groupe }.forEach { (groupe, liste) ->
                    Text(groupe, color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        liste.forEach { p -> HudChip(p.libelle, perso.id == p.id) { store.voixPersonnage.set(p.id) } }
                    }
                }
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudChip("🎛️ Sur mesure", perso.id == vn.SUR_MESURE) { store.voixPersonnage.set(vn.SUR_MESURE) }
                }
                if (perso.id == vn.SUR_MESURE) {
                    Text("Voix de base (30 voix Gemini)", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        vn.CATALOGUE.forEach { (nom, d) -> HudChip("$nom · ${d.first}, ${d.second}", perso.voix == nom) { store.voixNom.set(nom) } }
                    }
                    HudTextField(store.voixStyle.value, { store.voixStyle.set(it) }, Modifier.fillMaxWidth().padding(top = 4.dp),
                        label = "Décris la voix (âge, ton, accent, débit…)", placeholder = "ex. homme de 60 ans, léger accent du Sud, jovial", singleLine = false, maxLines = 3)
                }
                Text("Tu peux aussi le demander : « Jarvis, prends une voix de femme âgée ». Le ton, le débit et l'accent suivent bien la description ; l'âge est rendu de façon plus approximative. La nuit, la voix se fait plus douce ; en voiture, plus articulée.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                var essaiVoix by remember { mutableStateOf(false) }
                HudButton(if (essaiVoix) "Écoute…" else "▶ Écouter ${perso.libelle}", Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !essaiVoix) {
                    essaiVoix = true
                    fr.dd.jarvis.voice.VoixNaturelle.reactiver()
                    val v = fr.dd.jarvis.voice.VoiceManager(ctx)
                    v.speak("Bonjour ${store.userName.value.ifBlank { "" }}. Voici ma voix « ${perso.nom} ». Je commence à te répondre dès la première phrase, pendant que je continue à réfléchir.") {
                        v.release(); essaiVoix = false; etatVoix = fr.dd.jarvis.voice.VoixNaturelle.etat(app)
                    }
                }
                Text("État : $etatVoix", color = HudText, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                var diagVoix by remember { mutableStateOf("") }
                if (store.voixNaturelle.value) HudButton(if (diagVoix == "…") "Diagnostic…" else "Diagnostiquer la voix naturelle", Modifier.fillMaxWidth().padding(top = 4.dp), enabled = diagVoix != "…") {
                    diagVoix = "…"
                    scope.launch {
                        diagVoix = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { fr.dd.jarvis.voice.VoixNaturelle.diagnostic(app) }.getOrElse { "❌ ${it.message}" } }
                        if (diagVoix.contains("✅")) fr.dd.jarvis.voice.VoixNaturelle.reactiver()
                        etatVoix = fr.dd.jarvis.voice.VoixNaturelle.etat(app)
                    }
                }
                if (diagVoix.isNotBlank() && diagVoix != "…") Text(diagVoix, color = HudText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                // Voix Android de repli : moteur Google et meilleure voix française, ou celle choisie ici.
                var voixAndroid by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
                androidx.compose.runtime.LaunchedEffect(Unit) { voixAndroid = runCatching { fr.dd.jarvis.voice.VoixAndroid.liste(ctx) }.getOrDefault(emptyList()) }
                Text("Voix Android (repli)" + (if (!fr.dd.jarvis.voice.VoixAndroid.googleInstalle(ctx)) " — installe « Speech Services by Google » pour des voix plus naturelles" else ""), color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                if (voixAndroid.isNotEmpty()) Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudChip("Automatique (la meilleure)", store.voixAndroid.value.isBlank()) { store.voixAndroid.set("") }
                    voixAndroid.forEach { (nom, libelle) -> HudChip(libelle, store.voixAndroid.value == nom) { store.voixAndroid.set(nom) } }
                }
                Text("La voix naturelle utilise tes clés Gemini (synthèse vocale) ; si elle n'est pas disponible (quota, réseau), la voix Android prend le relais automatiquement. Dans tous les cas, JARVIS commence à parler dès la première phrase de la réponse.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        },
        VoletReglages("voix", "annonces", "Annonces vocales", "annonces sms mails notifications sonnerie voix haute lecture") {
            var cible by remember { mutableStateOf("") }
            val picker = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
                val uri = r.data?.getParcelableExtra<android.net.Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                if (cible.isNotBlank() && r.resultCode == android.app.Activity.RESULT_OK) store.setAnnSon(cible, uri?.toString() ?: "")
            }
            HudPanel(Modifier.fillMaxWidth(), title = "Annonces vocales (SMS, mails, notifications)") {
                Text("JARVIS annonce à voix haute l'arrivée d'un SMS, d'un mail ou d'une notification, même en arrière-plan, après la sonnerie de ton choix. Demande l'accès aux notifications (Réglages › Général › Autorisations).", color = HudDim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudChip(if (store.annOn.value) "✅ Annonces actives" else "Annonces désactivées", store.annOn.value) { store.annOn.set(!store.annOn.value) }
                    HudChip("🎧 Écouteurs/voiture seulement", store.annPrive.value) { store.annPrive.set(!store.annPrive.value) }
                }
                Spacer(Modifier.height(10.dp))
                HudDivider()
                Spacer(Modifier.height(8.dp))
                Text("Jarvis n'affiche plus ses propres notifications pour les SMS, mails et appels : celles d'Android suffisent. Il continue de les lire pour te les résumer, les annoncer à voix haute (réglage ci-dessus) et alimenter ses agents.", color = HudDim, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                HudDivider()
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
        },
        VoletReglages("telephone", "controle", "Contrôle du téléphone", "accessibilite shizuku adb controle ecran toucher") {
            val a11yOn = remember(tick) { app.phone.accessibilityEnabled() }
            val a11yInSettings = remember(tick) { app.phone.accessibilityInSettings() }
            val shInstalled = remember(tick) { ShizukuShell.installed(ctx) }
            val shRunning = remember(tick) { ShizukuShell.running() }
            val shGranted = remember(tick) { ShizukuShell.granted() }
            HudPanel(Modifier.fillMaxWidth(), title = "Contrôle du téléphone") {
                StatusRow3("Accessibilité (lire l'écran, toucher, défiler)", a11yOn, a11yInSettings)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Ouvrir les réglages d'accessibilité") {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    if (a11yInSettings && !a11yOn) HudButton("Infos de l'application") {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                Spacer(Modifier.height(10.dp))

                val stockageOn = remember(tick) { fr.dd.jarvis.ai.Stockage.autorise() }
                StatusRow("Accès à tous les fichiers (Téléchargements, Films, Musique…)", stockageOn)
                Spacer(Modifier.height(4.dp))
                HudButton(if (stockageOn) "✅ Autorisé" else "Autoriser l'accès à tous les fichiers", enabled = !stockageOn) {
                    runCatching {
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure {
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                Spacer(Modifier.height(10.dp))

                StatusRow("Shizuku installé", shInstalled)
                StatusRow("Shizuku démarré", shRunning)
                StatusRow("Shizuku autorisé pour JARVIS", shGranted)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Autoriser Shizuku", enabled = shRunning && !shGranted) {
                        ShizukuShell.requestPermission()
                    }
                    HudButton("Actualiser") { tick++ }
                }
                var shMsg by remember { mutableStateOf("") }
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudButton("Installer Shizuku (auto via ADB)", Modifier.fillMaxWidth(), enabled = !shInstalled, filled = true) {
                        scope.launch { shMsg = "⏳ Téléchargement et installation…"; shMsg = fr.dd.jarvis.device.ShizukuInstaller.installAuto(ctx); tick++ }
                    }
                    HudButton("Installer manuellement", Modifier.fillMaxWidth(), enabled = !shInstalled) {
                        scope.launch { shMsg = "⏳ Téléchargement…"; shMsg = fr.dd.jarvis.device.ShizukuInstaller.install(ctx) }
                    }
                    HudButton("Démarrer Shizuku via ADB", Modifier.fillMaxWidth(), enabled = shInstalled && !shRunning) {
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
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                val roleManager = if (Build.VERSION.SDK_INT >= 29) ctx.getSystemService(android.app.role.RoleManager::class.java) else null
                val screeningRole = Build.VERSION.SDK_INT >= 29 && roleManager?.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_SCREENING) == true
                val screeningOn = screeningRole && roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING) == true
                val callRoleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
                val overlayCallerId = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)
                StatusRow("Identification des appels entrants (fiches JARVIS + Obsidian)", screeningOn)
                Text("JARVIS peut identifier un appelant même s'il n'est pas dans les contacts Android. Android doit autoriser JARVIS comme application d'identification ; l'application Téléphone native continue de gérer l'appel.", color = HudDim, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                HudButton(if (screeningOn) "Identification des appels activée ✓" else "Activer l'identification des appels", enabled = screeningRole && !screeningOn) {
                    if (Build.VERSION.SDK_INT >= 29 && roleManager != null) callRoleLauncher.launch(roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING))
                }
                if (!screeningRole) Text("Le rôle Android d'identification des appels n'est pas disponible sur cette version.", color = HudDim, fontSize = 11.sp)
                if (screeningOn) {
                    StatusRow("Bandeau d'identification au-dessus de l'appel", overlayCallerId)
                    if (!overlayCallerId) Text("Autorisation commune : Réglages JARVIS → Général → Autorisations → Superposition.", color = HudDim, fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
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
        },
        VoletReglages("telephone", "applis", "Applications par défaut", "applications defaut itineraire musique waze maps") {
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
        },
        VoletReglages("telephone", "auto", "Android Auto", "android auto voiture visible raccourcis accueil") {
            val aa = "com.google.android.projection.gearhead"
            val aaInstalle = remember(tick) { runCatching { ctx.packageManager.getPackageInfo(aa, 0); true }.getOrDefault(false) }
            HudPanel(Modifier.fillMaxWidth(), title = "Android Auto") {
                StatusRow("Android Auto installé sur le téléphone", aaInstalle)
                StatusRow("Connecté à la voiture en ce moment", app.enVoiture)
                val source = remember(tick) { fr.dd.jarvis.car.VisibiliteAuto.libelleSource(ctx) }
                val play = remember(tick) { fr.dd.jarvis.car.VisibiliteAuto.marqueValide(ctx) }
                StatusRow("Marque « Play Store » reconnue par Android Auto — source : $source", play)
                Text(
                    "Android Auto n'affiche une appli hors Play Store que dans deux cas : 1) elle porte une marque « Play Store » posée par l'INSTALLATEUR GOOGLE (bouton « Rendre JARVIS visible » : un appui sur « Mettre à jour », à refaire après chaque mise à jour) — alors tout JARVIS est accepté, écrans voiture compris ; 2) « Sources inconnues » est coché ET c'est une appli média. Dans la voiture, 3 onglets : 🎙️ JARVIS (une tuile micro : bip, tu parles, il répond à voix haute), 📅 Aujourd'hui (tes rendez-vous du jour et de demain ; un appui lance Waze vers l'adresse), ⚡ Raccourcis (demandes toutes faites, relire, reprendre la musique). L'écran de lecture a trois boutons : 🎙️ parler, 🧭 prochain rendez-vous dans Waze, 🔁 relire ; ▶ ouvre aussi le micro. Étapes :\n" +
                        "1) Touche « 🚗 Rendre JARVIS visible » ci-dessous, puis « Mettre à jour ». Et en plus, par sécurité, le réglage « Sources inconnues » d'Android Auto (JARVIS ne peut pas le cocher lui-même) : « Ouvrir Android Auto » › tout en bas, touche 10 fois « Version » › accepte « Autoriser les paramètres de développement » › menu ⋮ en haut à droite › « Paramètres pour les développeurs » › coche « Sources inconnues ».\n" +
                        "2) Installe la dernière version de JARVIS (son numéro change à chaque version : Android Auto la revérifie), puis « 🔄 Relancer Android Auto » ci-dessous (ou redémarre le téléphone).\n" +
                        "3) Branche-toi à la voiture : JARVIS est dans le lanceur, avec les applis audio (et dans « Personnaliser le lanceur »). S'il n'y est pas, ouvre « 🩺 Diagnostic » dans la minute : il lit le verdict d'Android Auto dans le journal du téléphone (Shizuku ou ADB intégré) — envoie-le-moi.\n" +
                        "Une marque posée par Shizuku / ADB (« initiateur com.android.shell ») n'est PAS reconnue par Android Auto : seul l'installateur Google compte.",
                    color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp)
                )
                var autoMsg by remember { mutableStateOf("") }
                HudButton(if (autoMsg == "…") "Préparation…" else if (play) "✅ Marque Play Store — la reposer" else "🚗 Rendre JARVIS visible (installateur Google)",
                    Modifier.fillMaxWidth(), enabled = autoMsg != "…", filled = !play) {
                    autoMsg = "…"; scope.launch { autoMsg = fr.dd.jarvis.car.VisibiliteAuto.installerCommePlay(ctx); tick++ }
                }
                if (autoMsg.isNotBlank() && autoMsg != "…") Text(autoMsg, color = HudText, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                // Mise à jour de JARVIS qui GARDE la marque : l'APK téléchargé passe par l'installateur Google (une seule installation).
                val choixApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) { autoMsg = "…"; scope.launch { autoMsg = fr.dd.jarvis.car.VisibiliteAuto.installerCommePlay(ctx, uri); tick++ } }
                }
                HudButton("📦 Installer une mise à jour de JARVIS (garde Android Auto)", Modifier.fillMaxWidth().padding(top = 6.dp), enabled = autoMsg != "…") {
                    choixApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/zip"))
                }
                Text("Choisis l'APK téléchargé (ex. dans Téléchargements) : il est installé par l'installateur Google avec la marque « Play Store », plus besoin de « Rétablir » ensuite.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(6.dp))
                HudButton("Ouvrir Android Auto", Modifier.fillMaxWidth(), enabled = aaInstalle, filled = true) {
                    val pm = ctx.packageManager
                    val reglages = runCatching {
                        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).setPackage(aa), 0)
                            .map { it.activityInfo }.filter { it.exported }
                            .sortedByDescending { it.name.contains("setting", true) }.firstOrNull()
                            ?.let { Intent(Intent.ACTION_MAIN).setClassName(it.packageName, it.name) }
                    }.getOrNull() ?: pm.getLaunchIntentForPackage(aa)
                    runCatching { ctx.startActivity((reglages ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$aa"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$aa")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                Spacer(Modifier.height(6.dp))
                var diagAuto by remember { mutableStateOf("") }
                var relance by remember { mutableStateOf("") }
                HudButton(if (relance == "…") "Relance…" else "🔄 Relancer Android Auto (nouvelle vérification de JARVIS)", Modifier.fillMaxWidth(), enabled = relance != "…") {
                    relance = "…"
                    scope.launch { relance = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fr.dd.jarvis.car.VisibiliteAuto.relancerAndroidAuto(ctx) } }
                }
                if (relance.isNotBlank() && relance != "…") Text(relance, color = HudText, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(6.dp))
                HudButton(when (diagAuto) { "" -> "🩺 Diagnostic Android Auto"; "…" -> "Diagnostic…"; else -> "Masquer le diagnostic" }, Modifier.fillMaxWidth(), enabled = diagAuto != "…") {
                    if (diagAuto.isBlank()) {
                        diagAuto = "…"
                        scope.launch { diagAuto = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { fr.dd.jarvis.car.VisibiliteAuto.diagnostic(ctx) }.getOrElse { "❌ ${it.message}" } } }
                    } else diagAuto = ""
                }
                if (diagAuto.isNotBlank() && diagAuto != "…") {
                    Text(diagAuto, color = HudText, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    HudChip("📋 Copier") {
                        (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("Diagnostic Android Auto", diagAuto))
                    }
                }
                Spacer(Modifier.height(6.dp))
                HudChip(if (store.autoEcrans.value) "⚠️ Écrans voiture (Car App) : activés" else "Écrans voiture (Car App) : désactivés", store.autoEcrans.value) {
                    store.autoEcrans.set(!store.autoEcrans.value); fr.dd.jarvis.car.VisibiliteAuto.appliquerMode(ctx); tick++
                }
                Text("À n'activer que si JARVIS est réellement installé par le Play Store : sinon Android Auto refuse ces écrans et peut cacher tout JARVIS.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(10.dp))
                Text("Dans la voiture", color = HudText, fontSize = 13.sp)
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudChip(if (store.voitureAccueil.value) "👋 Accueil à la connexion : oui" else "Accueil à la connexion : non", store.voitureAccueil.value) { store.voitureAccueil.set(!store.voitureAccueil.value) }
                    HudChip(if (store.voitureAccueilVocal.value) "🔊 Accueil dit à voix haute" else "Accueil en message seulement", store.voitureAccueilVocal.value) { store.voitureAccueilVocal.set(!store.voitureAccueilVocal.value) }
                }
                Text("En montant en voiture, JARVIS t'envoie un message : prochain rendez-vous, nombre de rendez-vous restants, météo. Dans la voiture, il parle avec la voix choisie (Réglages › Voix › Voix), plus articulée.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                HudTextField(store.raccourcisVoiture.value.ifBlank { fr.dd.jarvis.car.RaccourcisVoiture.DEFAUT.joinToString("\n") }, { store.raccourcisVoiture.set(it) },
                    Modifier.fillMaxWidth().padding(top = 6.dp), label = "⚡ Raccourcis de la voiture (une demande par ligne)", singleLine = false, maxLines = 8)
                Text("Ex. « Ouvre le portail », « Allume le chauffage de la maison », « Appelle Thomas », « Lance ma playlist »… Un appui dans la voiture et JARVIS s'en occupe.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        },
        VoletReglages("comptes", "mails", "Mails", "mails gmail imap google compte courriel") {
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
        },
        VoletReglages("comptes", "obsidian", "Obsidian", "obsidian coffre notes fiches contacts dossier") {
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
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Choisir le coffre Obsidian") { picker.launch(null) }
                    if (store.obsidianUri.value.isNotBlank()) HudButton("Retirer") { store.obsidianUri.set("") }
                }
                Text("Les fiches sont écrites dans le sous-dossier « Contacts » du coffre.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        VoletReglages("comptes", "discord", "Discord", "discord bot roles salons serveur") {
            DiscordPanel(store, app)
        },
        VoletReglages("comptes", "tiktok", "TikTok", "tiktok compte video publication") {
            TikTokPanel(store, app)
        },
        VoletReglages("maison", "serveur", "Serveur & Home Assistant", "serveur ssh home assistant domotique debian docker") {
            ServeurPanel(store, app)
        },
        VoletReglages("maison", "box", "Freebox", "freebox box internet association diagnostic disque detection") {
            var freeboxResult by remember { mutableStateOf("") }
            var freeboxBusy by remember { mutableStateOf(false) }
            var gatewayResult by remember { mutableStateOf("") }
            var gatewayBusy by remember { mutableStateOf(false) }
            HudPanel(Modifier.fillMaxWidth(), title = "Box Internet — administration sécurisée") {
                Text(
                    if (store.freeboxAppToken.value.isBlank()) "Aucune association Freebox. Connecte le téléphone au Wi-Fi de la Freebox avant de demander l'autorisation."
                    else "Freebox associée · domaine API : " + store.freeboxApiDomain.value,
                    color = HudDim, fontSize = 12.sp
                )
                HudButton(if (gatewayBusy) "Détection…" else "Détecter et identifier ma box", enabled = !gatewayBusy) {
                    scope.launch {
                        gatewayBusy = true
                        gatewayResult = try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val gateway = fr.dd.jarvis.ai.BoxInternetAdapters.detecterPasserelle(app)
                                val detected = fr.dd.jarvis.ai.BoxInternetAdapters.detecterFabricantUpnp(app)
                                when {
                                    detected != null -> "Box identifiée : ${detected.vendor.label} ${detected.model} · ${detected.gatewayAddress}. Découverte UPnP en lecture seule ; les commandes de configuration dépendent encore de l’adaptateur constructeur."
                                    gateway != null -> "Passerelle détectée : ${gateway.gatewayAddress}. Le fabricant n’a pas répondu à la découverte UPnP ; aucune configuration n’a été modifiée."
                                    else -> "Aucune box détectée. Vérifie que le téléphone est connecté au réseau local de la box et que l’accès au réseau local est autorisé."
                                }
                            }
                        } catch (e: Exception) { "❌ Détection impossible : ${e.message ?: e.javaClass.simpleName}" }
                        gatewayBusy = false
                    }
                }
                if (gatewayResult.isNotBlank()) Text(gatewayResult, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(8.dp))
                Text("L'association affiche une demande sur l'écran de la Freebox. Le jeton est enregistré dans le stockage chiffré Android. Jarvis ne peut pas s'accorder lui-même les droits administrateur : ils doivent être autorisés dans Freebox OS.", color = HudDim, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton(if (freeboxBusy) "Connexion…" else if (store.freeboxAppToken.value.isBlank()) "Associer la Freebox" else "Vérifier les autorisations", enabled = !freeboxBusy) {
                        scope.launch {
                            freeboxBusy = true
                            freeboxResult = if (store.freeboxAppToken.value.isBlank()) "Demande d'association en cours. Valide-la sur l'écran de la box…" else "Vérification des droits…"
                            freeboxResult = try {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    if (store.freeboxAppToken.value.isBlank()) fr.dd.jarvis.ai.FreeboxOs.associer(app)
                                    else fr.dd.jarvis.ai.FreeboxOs.verifierAcces(app)
                                }
                            } catch (e: Exception) { "❌ " + (e.message ?: e.javaClass.simpleName) }
                            freeboxBusy = false
                            tick++
                        }
                    }
                    if (store.freeboxAppToken.value.isNotBlank()) HudButton("Dissocier", enabled = !freeboxBusy) {
                        fr.dd.jarvis.ai.FreeboxOs.dissocier(app)
                        freeboxResult = "Association locale supprimée de Jarvis."
                    }
                }
                if (freeboxResult.isNotBlank()) Text(freeboxResult, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                // Diagnostic brut du disque (disque annoncé vide alors qu'il est plein, etc.) : texte à copier tel quel.
                if (store.freeboxAppToken.value.isNotBlank()) {
                    var diagDisque by remember { mutableStateOf("") }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HudButton(if (diagDisque == "…") "Diagnostic…" else "🩺 Diagnostic du disque", enabled = diagDisque != "…") {
                            diagDisque = "…"
                            scope.launch { diagDisque = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fr.dd.jarvis.ai.FreeboxOs.diagnosticDisque(app) } }
                        }
                        if (diagDisque.length > 1) HudButton("Copier") {
                            (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("diagnostic Freebox", diagDisque))
                        }
                    }
                    if (diagDisque.length > 1) Text(diagDisque, color = HudText, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
                }
                Text("À savoir : le droit « settings » doit être activé dans Freebox OS › Paramètres de la Freebox › Gestion des accès › Applications pour modifier les réglages réseau. L'accès distant sécurisé sera utilisé après l'association locale.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        VoletReglages("maison", "box", "Disque de la box", "box disque usb smb ftp partage") {
            BoxPanel(store, app)
        },
        VoletReglages("maison", "imprimante", "Imprimante", "imprimante impression reseau") {
            ImprimantePanel(store, app)
        },
        VoletReglages("agents", "agents", "Agents autonomes", "agents autonomes courrier factures planning notifications frequence cycle") {
            var runMsg by remember { mutableStateOf("") }
            val lastRunLabel = remember(store.agentLastRunAt.value) {
                store.agentLastRunAt.value.toLongOrNull()?.let { millis ->
                    runCatching {
                        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy à HH:mm"))
                    }.getOrNull()
                } ?: "Aucun cycle enregistré"
            }
            HudPanel(Modifier.fillMaxWidth(), title = "Agents autonomes") {
                val enabledAgents = listOf(store.agentMails.value, store.agentFactures.value, store.agentPlanning.value, store.agentNotifs.value).count { it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DashboardMetric("AGENTS ACTIFS", "${enabledAgents} / 4")
                    DashboardMetric("FRÉQUENCE", "${store.agentMinutes.value} min")
                    DashboardMetric("ÉTAT GLOBAL", if (store.agentsOn.value) "EN SERVICE" else "EN PAUSE")
                }
                Text("Active ou suspends les tâches de fond de JARVIS. Chaque agent peut être piloté séparément ; les tâches périodiques respectent les limites d'Android et peuvent être retardées par HyperOS.", color = HudDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                HudChip(if (store.agentsOn.value) "Automatisation générale activée" else "Automatisation générale suspendue", store.agentsOn.value) {
                    store.agentsOn.set(!store.agentsOn.value)
                    fr.dd.jarvis.work.AgentsScheduler.apply(ctx, store)
                }
                Spacer(Modifier.height(8.dp))
            }
            HudPanel(Modifier.fillMaxWidth(), title = "Pilotage des agents") {
                Text("AGENTS SPÉCIALISÉS · VIGNETTES DE CONTRÔLE", color = LocalAccent.current, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 12.sp)
                Text("Chaque vignette décrit le travail réellement effectué par l'agent. Les interrupteurs prennent effet au prochain cycle.", color = HudDim, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    HudPanel(Modifier.fillMaxWidth(), title = "📧 Agent courrier") {
                        Text("Surveille les nouveaux mails, les classe par priorité et repère les messages urgents. Peut aussi appliquer les libellés Gmail « Urgent », « Clients »…", color = HudText, fontSize = 12.sp)
                        Text("IA responsable : " + when { store.geminiKeys.isNotEmpty() -> "Gemini (principal) → Groq (secours)"; store.groqKeys.isNotEmpty() -> "Groq (secours disponible, Gemini non configuré)"; else -> "Aucune clé IA configurée — classement de secours limité" }, color = LocalAccent.current, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (store.agentMails.value) "ACTIF" else "EN PAUSE", color = if (store.agentMails.value) LocalAccent.current else HudDim, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            HudChip(if (store.agentMails.value) "Désactiver" else "Activer", store.agentMails.value) { store.agentMails.set(!store.agentMails.value) }
                        }
                        HudDivider()
                        Text("Options de rangement Gmail", color = HudDim, fontSize = 10.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HudChip(if (store.agentRanger.value) "🏷️ Tri activé" else "🏷️ Tri désactivé", store.agentRanger.value) { store.agentRanger.set(!store.agentRanger.value) }
                            HudChip(if (store.agentArchiver.value) "📥 Archivage activé" else "📥 Archivage désactivé", store.agentArchiver.value) { store.agentArchiver.set(!store.agentArchiver.value) }
                        }
                    }
                    HudPanel(Modifier.fillMaxWidth(), title = "💶 Agent entreprise & photovoltaïque") {
                        Text("Repère les factures en retard ou bientôt échues, les devis sans réponse depuis plus de 10 jours et les maintenances photovoltaïques en retard ou prévues sous 14 jours. Préserve les données réelles des clients et installations.", color = HudText, fontSize = 12.sp)
                        Text("Moteur responsable : règles métier locales JARVIS (pas d’appel IA pour les calculs et échéances).", color = LocalAccent.current, fontSize = 11.sp)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (store.agentFactures.value) "ACTIF" else "EN PAUSE", color = if (store.agentFactures.value) LocalAccent.current else HudDim, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            HudChip(if (store.agentFactures.value) "Désactiver" else "Activer", store.agentFactures.value) { store.agentFactures.set(!store.agentFactures.value) }
                        }
                    }
                    HudPanel(Modifier.fillMaxWidth(), title = "🚗 Agent planning & tâches") {
                        Text("Analyse les 7 prochains jours, détecte les chevauchements d'agenda, prépare le prochain rendez-vous et calcule une alerte de départ selon le trajet. Surveille aussi les tâches personnelles en retard ou dues sous 24 h.", color = HudText, fontSize = 12.sp)
                        Text("Moteur responsable : règles locales JARVIS + calcul d’itinéraire ; pas d’appel IA pour les alertes automatiques.", color = LocalAccent.current, fontSize = 11.sp)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (store.agentPlanning.value) "ACTIF" else "EN PAUSE", color = if (store.agentPlanning.value) LocalAccent.current else HudDim, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            HudChip(if (store.agentPlanning.value) "Désactiver" else "Activer", store.agentPlanning.value) { store.agentPlanning.set(!store.agentPlanning.value) }
                        }
                    }
                    HudPanel(Modifier.fillMaxWidth(), title = "🔔 Agent notifications") {
                        Text("Lit les nouvelles notifications reçues depuis le dernier passage et filtre les éléments qui demandent une attention rapide : clients, devis, factures, paiements, chantiers et rendez-vous. Ignore les contenus sans priorité.", color = HudText, fontSize = 12.sp)
                        Text("IA responsable : " + when { store.geminiKeys.isNotEmpty() -> "Gemini (principal) → Groq (secours)"; store.groqKeys.isNotEmpty() -> "Groq (secours disponible, Gemini non configuré)"; else -> "Aucune clé IA configurée — filtrage IA indisponible" }, color = LocalAccent.current, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (store.agentNotifs.value) "ACTIF" else "EN PAUSE", color = if (store.agentNotifs.value) LocalAccent.current else HudDim, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            HudChip(if (store.agentNotifs.value) "Désactiver" else "Activer", store.agentNotifs.value) { store.agentNotifs.set(!store.agentNotifs.value) }
                        }
                    }
                }
                Text("Fréquence des tâches", color = HudDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(15, 30, 60).forEach { minutes ->
                        HudChip("Toutes les $minutes min", store.agentMinutes.value == minutes) {
                            store.agentMinutes.set(minutes)
                            if (store.agentsOn.value) fr.dd.jarvis.work.AgentsScheduler.apply(ctx, store)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                HudButton("Lancer un cycle maintenant", Modifier.fillMaxWidth(), enabled = store.agentsOn.value, filled = true) {
                    fr.dd.jarvis.work.AgentsScheduler.runNow(ctx)
                    runMsg = "Cycle demandé. Le résultat apparaîtra après l'exécution."
                }
                if (!store.agentsOn.value) Text("Active les agents autonomes pour pouvoir lancer un cycle.", color = HudDim, fontSize = 11.sp)
                if (runMsg.isNotBlank()) Text(runMsg, color = HudText, fontSize = 11.sp)
                HudDivider()
                Text("Dernière exécution", color = LocalAccent.current, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 13.sp)
                AgentStatusRow("Dernier cycle", store.agentLastRunAt.value.isNotBlank(), lastRunLabel)
                AgentStatusRow("Résultat", store.agentLastRunStatus.value == "Cycle terminé sans erreur", store.agentLastRunStatus.value)
                Text("Historique des 5 derniers cycles", color = LocalAccent.current, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 12.sp)
                val recentRuns = store.agentRunHistory.value.lines().filter { it.isNotBlank() }.takeLast(5).asReversed()
                if (recentRuns.isEmpty()) Text("L'historique se remplira au prochain passage des agents.", color = HudDim, fontSize = 11.sp)
                else recentRuns.forEach { Text("• $it", color = HudText, fontSize = 11.sp) }
                Text("Journal détaillé : Obsidian → JARVIS/Journal. L'historique local ne conserve que les heures et statuts, pas le contenu des mails ou notifications.", color = HudDim, fontSize = 11.sp)
                Text("Android impose au moins 15 min entre deux passages et peut les retarder en économie d'énergie : autorise « Batterie sans restriction ».", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        VoletReglages("agents", "routines", "Routines", "routines phrase lieu heure declencheur") {
            fr.dd.jarvis.ui.RoutinesPanel(app)
        },
        VoletReglages("agents", "proactif", "Assistant proactif", "depart meteo journal montre proactif rendez-vous") {
            fr.dd.jarvis.ui.AssistantProactifPanel(app)
        },
        VoletReglages("agents", "proactif", "Point du matin", "point du matin briefing notification matin") {
            val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            HudPanel(Modifier.fillMaxWidth(), title = "Point du matin") {
                Text("Chaque matin, JARVIS prépare ton planning du jour et le tri de tes mails, et t'envoie une notification.", color = HudDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
        },
        VoletReglages("agents", "modes", "Modes automatiques", "modes automatiques voiture chantier nuit maison") {
            HudPanel(Modifier.fillMaxWidth(), title = "🌙 Modes automatiques") {
                Text("JARVIS adapte ses réponses, sa voix et ses notifications : 🚗 voiture (Android Auto), 👷 chantier (à moins de 250 m d'une installation cliente), 🌙 nuit, 🏠 maison (au siège).", color = HudDim, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { store.modesAuto.set(!store.modesAuto.value) }) {
                    Text((if (store.modesAuto.value) "✅ " else "⬜ ") + "Modes automatiques", color = HudText, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                }
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Nuit de", color = HudDim, fontSize = 12.sp)
                    listOf("21", "22", "23").forEach { h -> HudChip("${h}h", store.nuitDebut.value == h) { store.nuitDebut.set(h) } }
                    Text("à", color = HudDim, fontSize = 12.sp)
                    listOf("6", "7", "8").forEach { h -> HudChip("${h}h", store.nuitFin.value == h) { store.nuitFin.set(h) } }
                }
            }
        },
        VoletReglages("agents", "metier", "Métier photovoltaïque", "photovoltaique interventions chantier raccourcis journee") {
            val clients = app.societe.data.clients
            val siteCount = clients.sumOf { it.sites.size }
            val interventionCount = clients.sumOf { client -> client.sites.sumOf { it.interventions.size } }
            HudPanel(Modifier.fillMaxWidth(), title = "Contexte et interventions photovoltaïques") {
                Text("Données actuellement enregistrées : ${clients.size} client(s), ${siteCount} installation(s), ${interventionCount} intervention(s). Le bilan utilise uniquement les fiches réelles présentes dans JARVIS.", color = HudDim, fontSize = 11.sp)
                AgentStatusRow("Historique photovoltaïque", interventionCount > 0, "${interventionCount} intervention(s)")
                HudButton("Ouvrir la synthèse des interventions", Modifier.fillMaxWidth()) {
                    onAsk("Présente la synthèse complète de l'historique des interventions photovoltaïques, les suivis en attente et les prochaines maintenances.")
                }
                HudDivider()
                Text("Raccourcis contextuels", color = LocalAccent.current, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 13.sp)
                HudButton("Préparer mon départ au travail", Modifier.fillMaxWidth()) {
                    onAsk("Prépare mon départ au travail : vérifie mon agenda, l'heure du prochain rendez-vous et le trajet utile.")
                }
                HudButton("Préparer ma journée de demain", Modifier.fillMaxWidth()) {
                    onAsk("Prépare ma journée de demain à partir de mon agenda, de mes tâches et des éléments importants à anticiper.")
                }
                HudButton("Préparer le contexte de mon prochain chantier", Modifier.fillMaxWidth()) {
                    onAsk("Prépare le contexte de mon prochain chantier : identifie le rendez-vous pertinent, le client et l'installation, puis consulte son historique photovoltaïque. Demande une précision si plusieurs chantiers correspondent.")
                }
            }
        },
        VoletReglages("securite", "antivol", "Anti-vol", "anti-vol vol sms code verrouillage alarme position perdu") {
            fr.dd.jarvis.ui.AntiVolPanel(app)
        },
        VoletReglages("securite", "empreinte", "Empreinte vocale", "empreinte vocale voix reconnaissance") {
            fr.dd.jarvis.ui.EmpreinteVocalePanel(app)
        },
        VoletReglages("securite", "confirmations", "Confirmations", "empreinte digitale code confirmation actions sensibles biometrie") {
            HudPanel(Modifier.fillMaxWidth(), title = "🔒 Confirmations des actions sensibles") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { store.biometrie.set(!store.biometrie.value) }) {
                    Text((if (store.biometrie.value) "✅ " else "⬜ ") + "🔒 Empreinte (ou code) avant les actions sensibles", color = HudText, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                }
                Text("Suppressions, envois de mails et SMS, paiements, redémarrages : après ton « oui », JARVIS demande ton empreinte. À la voix, ces actions ne passent que si le téléphone est déverrouillé.", color = HudDim, fontSize = 11.sp)
            }
        },
        VoletReglages("securite", "coffre", "Coffre-fort", "coffre-fort serveur sauvegarde societe donnees salaries recuperation") {
            fr.dd.jarvis.ui.CoffrePanel(app)
        },
        VoletReglages("diagnostic", "telephone", "Centre de surveillance", "surveillance batterie stockage ram temperature services") {
            HudPanel(Modifier.fillMaxWidth(), title = "Centre de surveillance du téléphone") {
                    val phone = remember(tick) { fr.dd.jarvis.device.PhoneHealth.dashboard(app) }
                    val samples = remember(tick, store.agentLastRunAt.value) { fr.dd.jarvis.device.PhoneHealth.history(app) }
                    val batterySeries = samples.mapNotNull { it.battery?.toFloat() }
                    val storageSeries = samples.map { (it.freeBytes / (1024.0 * 1024.0 * 1024.0)).toFloat() }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DashboardMetric("BATTERIE", phone.battery?.let { "${it} %" } ?: "—")
                        DashboardMetric("STOCKAGE LIBRE", phone.storageFreeGb?.let { String.format(java.util.Locale.FRANCE, "%.1f Go", it) } ?: "—")
                        DashboardMetric("RAM LIBRE", String.format(java.util.Locale.FRANCE, "%.1f Go", phone.ramFreeGb))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DashboardMetric("TEMP. BATTERIE", phone.temperatureC?.let { String.format(java.util.Locale.FRANCE, "%.1f °C", it) } ?: "—")
                        DashboardMetric("STOCKAGE UTILISÉ", phone.storageUsedPct?.let { "${it} %" } ?: "—")
                        DashboardMetric("ÉTAT THERMIQUE", phone.thermal)
                    }
                    Text("Évolution de la batterie", color = HudDim, fontSize = 11.sp)
                    if (batterySeries.size >= 2) TrendLine(batterySeries, Modifier.fillMaxWidth().height(72.dp), Color(0xFF55D6C2))
                    else Text("Historique en constitution : les relevés sont enregistrés toutes les 30 minutes.", color = HudDim, fontSize = 10.sp)
                    Text("Évolution du stockage libre", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    if (storageSeries.size >= 2) TrendLine(storageSeries, Modifier.fillMaxWidth().height(72.dp), Color(0xFF9B8CFF))
                    else Text("La courbe apparaîtra après plusieurs relevés.", color = HudDim, fontSize = 10.sp)
                    HudDivider()
                    Text("ÉTAT DES SERVICES ET AUTORISATIONS", color = LocalAccent.current, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 11.sp)
                    AgentStatusRow("Connexion Internet", phone.network != "Hors ligne", phone.network)
                    AgentStatusRow("Shizuku", phone.shizukuReady, if (phone.shizukuReady) "Autorisé et opérationnel" else "Non prêt")
                    AgentStatusRow("Accessibilité JARVIS", phone.accessibility, if (phone.accessibility) "Connectée" else "Déconnectée")
                    AgentStatusRow("Accès aux notifications", phone.notificationListener, if (phone.notificationListener) "Autorisé" else "Non autorisé")
                    AgentStatusRow("Notifications Android", phone.notificationsAllowed, if (phone.notificationsAllowed) "Autorisées" else "Bloquées")
                    AgentStatusRow("Alarmes exactes", phone.exactAlarmsAllowed, if (phone.exactAlarmsAllowed) "Autorisées" else "Non autorisées")
                    AgentStatusRow("Batterie sans restriction", phone.unrestrictedBattery, if (phone.unrestrictedBattery) "Activée" else "Optimisation active")
                    AgentStatusRow("Santé batterie", phone.batteryHealth == "Bonne", phone.batteryHealth + if (phone.charging) " · en charge" else "")
                    AgentStatusRow("Réseau thermique", phone.thermal !in setOf("Élevé", "Critique", "Urgence", "Arrêt imminent"), phone.thermal)
                    Text("Mesures locales en lecture seule. Aucune analyse des messages, photos ou contenus personnels.", color = HudDim, fontSize = 10.sp)
                    HudButton("Actualiser les mesures", Modifier.fillMaxWidth()) { fr.dd.jarvis.device.PhoneHealth.recordSample(app); tick++ }
                }
        },
        VoletReglages("diagnostic", "telephone", "Santé du système", "sante systeme diagnostic") {
            SystemHealthPanel(app, tick) { tick++ }
            HudButton("Demander un diagnostic technique complet à JARVIS", Modifier.fillMaxWidth()) {
                onAsk("Lance un diagnostic technique complet du téléphone et explique les alertes, les accès manquants et les limites Android détectées.")
            }
        },
        VoletReglages("diagnostic", "moteurs", "État des moteurs IA", "etat moteurs ia cles test hermes groq gemini") {
            var agentTest by remember { mutableStateOf("") }
            var agentTesting by remember { mutableStateOf(false) }
            var hermesOnline by remember { mutableStateOf(false) }
            var hermesChecked by remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(tick) {
                hermesOnline = runCatching { fr.dd.jarvis.hermes.Hermes.enLigne(force = true) }.getOrDefault(false)
                hermesChecked = true
            }
            HudPanel(Modifier.fillMaxWidth(), title = "État des moteurs IA") {
                Text("État de configuration des moteurs disponibles. Une clé enregistrée ne garantit ni la disponibilité du service ni un quota restant.", color = HudDim, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                AgentStatusRow("Hermès · orchestrateur Termux", hermesOnline, when {
                    store.hermesMode.value == "off" -> "mode désactivé"
                    hermesOnline -> "en ligne"
                    !store.hermesInstalle.value -> "installation non confirmée"
                    hermesChecked -> "installé · passerelle hors ligne"
                    else -> "vérification…"
                })
                AgentStatusRow("Groq · " + store.groqModel.value, store.groqKeys.isNotEmpty(), "${store.groqKeys.size} clé(s)")
                AgentStatusRow("Gemini · " + store.geminiModel.value, store.geminiKeys.isNotEmpty(), "${store.geminiKeys.size} clé(s)")
                AgentStatusRow("OpenAI · " + store.openaiModel.value, store.openaiKeys.isNotEmpty(), "${store.openaiKeys.size} clé(s)")
                AgentStatusRow("Cloudflare Workers AI", store.cfAccounts.isNotEmpty(), "${store.cfAccounts.size} compte(s)")
                AgentStatusRow("Ollama · serveur de la maison (secours)", fr.dd.jarvis.ai.Ollama.configure(app), if (fr.dd.jarvis.ai.Ollama.configure(app)) fr.dd.jarvis.ai.Ollama.etat else "désactivé ou sans adresse")
                AgentStatusRow("fal.ai · vidéo", store.falApiKey.value.isNotBlank(), if (store.falApiKey.value.isNotBlank()) "clé configurée" else "non configuré")
                AgentStatusRow("Hugging Face", store.huggingFaceToken.value.isNotBlank(), if (store.huggingFaceToken.value.isNotBlank()) "jeton configuré" else "non configuré")
                val external = store.externalProvidersConfigured()
                AgentStatusRow("Autres fournisseurs", external.isNotEmpty(), external.ifEmpty { listOf("aucun configuré") }.joinToString(", "))
                Text("Les secrets ne sont jamais affichés dans ce centre.", color = HudDim, fontSize = 10.sp)
                HudButton("Actualiser les états", Modifier.fillMaxWidth()) { tick++ }
                HudButton(if (agentTesting) "Test en cours…" else "Tester les clés et connexions IA", Modifier.fillMaxWidth(), enabled = !agentTesting, filled = true) {
                    scope.launch {
                        agentTesting = true
                        agentTest = runCatching { app.llm.testerClesApi("tous") }
                            .getOrElse { "Test impossible : ${it.message ?: it.javaClass.simpleName}" }
                        agentTesting = false
                    }
                }
                if (agentTest.isNotBlank()) Text(agentTest, color = HudText, fontSize = 11.sp)
            }
        },
    )

    Column(Modifier.fillMaxWidth()) {
        ReglagesNavigation(
            onglet = onglet, sous = sousCourant, recherche = recherche,
            onOnglet = { id -> onglet = id; sous = ""; recherche = null; store.reglagesVue.set(id) },
            onSous = { s -> sous = s; store.reglagesVue.set("$onglet/$s") },
            onRecherche = { recherche = it },
        )
        val r = recherche
        if (r == null && onglet == "fichiers") {
            DocumentLibraryScreen(app, onAsk)
        } else {
            val visibles = if (r != null) volets.filter { it.correspond(r) } else volets.filter { it.onglet == onglet && it.sous == sousCourant }
            LazyColumn(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (r != null && r.isNotBlank() && visibles.isEmpty()) item {
                    Text("Aucun réglage ne correspond à « $r ». Essaie un autre mot (ex. voix, batterie, Freebox, clés, anti-vol).", color = HudDim, fontSize = 12.sp)
                }
                if (r != null && r.isBlank()) item {
                    Text("Tape un mot : le réglage s'affiche, avec l'onglet où il se trouve.", color = HudDim, fontSize = 12.sp)
                }
                visibles.forEach { v ->
                    item(key = v.onglet + "/" + v.sous + "/" + v.titre) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Résultat de recherche : d'où vient ce réglage (toucher pour y aller).
                            if (r != null) Text("📍 " + v.chemin(), color = LocalAccent.current, fontSize = 12.sp,
                                modifier = Modifier.clickable { onglet = v.onglet; sous = v.sous; recherche = null; store.reglagesVue.set(v.onglet + "/" + v.sous) })
                            v.contenu()
                        }
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
    }
}

@Composable
private fun SystemHealthPanel(app: JarvisApp, refresh: Int, onRefresh: () -> Unit) {
    val ctx = LocalContext.current
    val battery = remember(refresh) {
        runCatching {
            val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        }.getOrDefault(-1)
    }
    val charging = remember(refresh) {
        val intent = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) in setOf(
            android.os.BatteryManager.BATTERY_STATUS_CHARGING,
            android.os.BatteryManager.BATTERY_STATUS_FULL
        )
    }
    val storage = remember(refresh) {
        runCatching {
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().absolutePath)
            val free = stat.availableBytes / (1024.0 * 1024.0 * 1024.0)
            val total = stat.totalBytes / (1024.0 * 1024.0 * 1024.0)
            Triple(free, total, ((stat.totalBytes - stat.availableBytes) * 100 / stat.totalBytes).toInt())
        }.getOrDefault(Triple(0.0, 0.0, 0))
    }
    val ram = remember(refresh) {
        runCatching {
            val info = android.app.ActivityManager.MemoryInfo()
            (ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(info)
            Triple(info.availMem / (1024.0 * 1024.0 * 1024.0), info.totalMem / (1024.0 * 1024.0 * 1024.0), info.lowMemory)
        }.getOrDefault(Triple(0.0, 0.0, false))
    }
    val batteryDetails = remember(refresh) {
        val intent = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it >= 0 }?.div(10.0)
        val health = when (intent?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1)) {
            android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "bonne"
            android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "surchauffe"
            android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "défectueuse"
            else -> "non communiquée"
        }
        temp to health
    }
    val shizukuInstalled = remember(refresh) { fr.dd.jarvis.device.ShizukuShell.installed(ctx) }
    val shizukuRunning = remember(refresh) { fr.dd.jarvis.device.ShizukuShell.running() }
    val shizukuReady = remember(refresh) { fr.dd.jarvis.device.ShizukuShell.ready() }
    val a11ySettings = remember(refresh) { app.phone.accessibilityInSettings() }
    val a11yActive = remember(refresh) { app.phone.accessibilityEnabled() }
    val listenerEnabled = remember(refresh) {
        val raw = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        raw.contains(ctx.packageName)
    }
    val notifications = remember(refresh) {
        android.os.Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val batteryFree = remember(refresh) {
        (ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    val network = remember(refresh) {
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val active = cm.activeNetwork
        active != null && cm.getNetworkCapabilities(active)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    val exactAlarm = remember(refresh) {
        android.os.Build.VERSION.SDK_INT < 31 || (ctx.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()
    }
    HudPanel(Modifier.fillMaxWidth(), title = "Centre de surveillance du téléphone") {
        Text("Lecture des états Android accessibles à JARVIS. Les autorisations sont distinguées de la connexion réelle des services.", color = HudDim, fontSize = 11.sp)
        Spacer(Modifier.height(5.dp))
        Text("🔋 Batterie : " + (if (battery >= 0) "$battery %" else "indisponible") + (if (charging) " · en charge" else ""), color = HudText, fontSize = 13.sp)
        Text("🔋 Santé batterie : ${batteryDetails.second}" + (batteryDetails.first?.let { " · ${String.format(java.util.Locale.FRANCE, "%.1f", it)} °C" } ?: ""), color = HudText, fontSize = 12.sp)
        Text("💾 Stockage interne : ${"%.2f".format(java.util.Locale.FRANCE, storage.first)} Go libres / ${"%.1f".format(java.util.Locale.FRANCE, storage.second)} Go · ${storage.third} % utilisés", color = HudText, fontSize = 12.sp)
        Text("🧠 RAM : ${"%.2f".format(java.util.Locale.FRANCE, ram.first)} Go libres / ${"%.2f".format(java.util.Locale.FRANCE, ram.second)} Go" + if (ram.third) " · pression mémoire" else "", color = HudText, fontSize = 12.sp)
        AgentStatusRow("Connexion Internet", network, if (network) "connectée" else "hors ligne")
        AgentStatusRow("Shizuku installé", shizukuInstalled, if (shizukuInstalled) "détecté" else "absent")
        AgentStatusRow("Shizuku autorisé et prêt", shizukuReady, if (shizukuReady) "opérationnel" else if (shizukuRunning) "autorisation manquante" else "arrêté")
        AgentStatusRow("Accessibilité JARVIS", a11yActive, if (a11yActive) "connectée" else if (a11ySettings) "activée dans Android, non connectée" else "désactivée")
        AgentStatusRow("Accès aux notifications", listenerEnabled, if (listenerEnabled) "autorisé" else "non autorisé")
        AgentStatusRow("Notifications Android", notifications, if (notifications) "autorisées" else "bloquées")
        AgentStatusRow("Alarmes exactes", exactAlarm, if (exactAlarm) "autorisées" else "non autorisées")
        AgentStatusRow("Batterie sans restriction", batteryFree, if (batteryFree) "autorisée" else "restriction active")
        AgentStatusRow("Écoute vocale JARVIS", fr.dd.jarvis.service.JarvisService.running, if (fr.dd.jarvis.service.JarvisService.running) "service actif" else if (app.store.wakeOn.value) "souhaitée mais arrêtée" else "désactivée")
        val crash = java.io.File(ctx.filesDir, fr.dd.jarvis.JarvisApp.CRASH_FILE).exists()
        AgentStatusRow("Rapport de plantage", crash, if (crash) "à consulter" else "aucun rapport")
        Spacer(Modifier.height(6.dp))
        HudButton("Actualiser le diagnostic", onClick = onRefresh)
    }
}

@Composable
private fun AgentStatusRow(label: String, configured: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (configured) "🟢" else "⚪", fontSize = 11.sp)
        Spacer(Modifier.width(7.dp))
        Text(label, color = HudText, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(detail, color = HudDim, fontSize = 10.sp)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        color = LocalAccent.current, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(top = 6.dp, start = 2.dp, bottom = 2.dp)
    )
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "🟢" else "⚪", fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text(label, color = HudText, fontSize = 13.sp)
    }
}

/** Comme StatusRow, mais distingue « activé dans Android mais pas encore connecté à JARVIS » (🟠) de « non activé » (⚪). */
@Composable
private fun StatusRow3(label: String, actif: Boolean, activeDansAndroid: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (actif) "🟢" else if (activeDansAndroid) "🟠" else "⚪", fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text(label, color = HudText, fontSize = 13.sp)
    }
    if (!actif && activeDansAndroid) Text(
        "L'interrupteur est déjà activé dans Android, mais le service n'est toujours pas réellement connecté — c'est le signe du blocage « paramètres restreints » d'Android 13+ sur une app installée hors Play Store. Ça ne se répare PAS en redémarrant JARVIS ni en laissant l'interrupteur tel quel. 1) « Infos de l'application » › ⋮ (en haut à droite) › « Autoriser les paramètres restreints ». 2) Reviens dans les réglages d'accessibilité, DÉSACTIVE l'interrupteur puis RÉACTIVE-le (c'est cette bascule off→on qui force la vraie connexion).",
        color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(start = 20.dp, bottom = 4.dp)
    )
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
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { store.scanAuto.set(!store.scanAuto.value); fr.dd.jarvis.work.ReseauScheduler.apply(app, store) }) {
            Text((if (store.scanAuto.value) "✅ " else "⬜ ") + "Analyse automatique du réseau (à chaque connexion Wi-Fi et toutes les heures) : enregistre appareils et IP publique", color = HudText, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
        Spacer(Modifier.height(6.dp))
        HudTextField(store.sshHostDistant.value, { store.sshHostDistant.set(it.trim()) }, Modifier.fillMaxWidth(), label = "Adresse distante du serveur (IP publique ou nom DDNS, optionnel)")
        Spacer(Modifier.height(6.dp))
        HudTextField(store.sshPortDistant.value, { store.sshPortDistant.set(it.filter { c -> c.isDigit() }) }, Modifier.fillMaxWidth(), label = "Port distant (redirigé par la box, défaut = port SSH)")
        Spacer(Modifier.height(8.dp))
        Text("Plex — serveur multimédia Debian", color = HudText, fontSize = 13.sp)
        Text("Jarvis utilise le tunnel SSH existant vers le port Plex 32400 : aucun port Plex supplémentaire à ouvrir sur la Freebox. Le jeton est conservé dans le stockage chiffré Android. Pour piloter une TV, son application Plex doit être ouverte, connectée au compte et autoriser le contrôle à distance ; elle doit apparaître dans la liste des lecteurs Plex.", color = HudDim, fontSize = 11.sp)
        if (store.plexToken.available) {
            HudTextField(store.plexToken.value, { runCatching { store.plexToken.set(it.trim()) }.onFailure { e -> android.widget.Toast.makeText(app, e.message ?: "Stockage sécurisé indisponible", android.widget.Toast.LENGTH_LONG).show() } },
                Modifier.fillMaxWidth(), label = "Jeton Plex (X-Plex-Token)", password = true)
        } else {
            Text("⚠️ Stockage sécurisé indisponible : le jeton Plex ne peut pas être enregistré sur cet appareil.", color = HudDim, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        HudTextField(store.ghToken.value, { store.ghToken.set(it.trim()) }, Modifier.fillMaxWidth(), label = "Jeton GitHub (optionnel : dépôts privés, plus de requêtes)", password = true)
        Spacer(Modifier.height(10.dp))
        Text("Ollama — IA locale de secours (serveur de la maison)", color = HudText, fontSize = 13.sp)
        Text("Utilisée quand Groq, Gemini et Cloudflare ne répondent plus (quotas, pas d'internet) et proposée à Hermès comme sous-agent « jarvis-ollama ». Adresse vide = le serveur SSH ci-dessus, port 11434 ; si Ollama n'écoute que sur le serveur lui-même, Jarvis passe par un tunnel SSH.", color = HudDim, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { store.ollamaActif.set(!store.ollamaActif.value) }) {
            Text((if (store.ollamaActif.value) "✅ " else "⬜ ") + "Utiliser Ollama en secours", color = HudText, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
        HudTextField(store.ollamaUrl.value, { store.ollamaUrl.set(it.trim()) }, Modifier.fillMaxWidth(), label = "Adresse d'Ollama (vide = serveur SSH:11434, ex. 192.168.1.30:11434)")
        Spacer(Modifier.height(6.dp))
        HudTextField(store.ollamaModele.value, { store.ollamaModele.set(it.trim()) }, Modifier.fillMaxWidth(), label = "Modèle (vide = choix automatique, ex. qwen2.5:1.5b)")
        Spacer(Modifier.height(6.dp))
        HudButton(if (busy) "Test…" else "Tester Ollama", Modifier.fillMaxWidth(), enabled = !busy && fr.dd.jarvis.ai.Ollama.configure(app)) {
            scope.launch {
                busy = true
                msg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { fr.dd.jarvis.ai.Ollama.tester(app) }.getOrElse { "❌ Ollama : ${it.message}" } }
                busy = false
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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


@Composable
private fun DiscordPanel(store: fr.dd.jarvis.data.Store, app: fr.dd.jarvis.JarvisApp) {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    HudPanel(Modifier.fillMaxWidth(), title = "Discord — bot (rôles et permissions de salons)") {
        Text(
            "Permet à JARVIS de lire l'historique et les nouveaux messages, créer/modifier des rôles, attribuer un rôle et régler les permissions par salon. Dans discord.com/developers/applications, active « Server Members Intent » et « Message Content Intent » ; donne au bot « Voir les salons », « Lire l'historique des messages », et, pour l'administration, « Gérer les rôles » / « Gérer les salons ». Place son rôle au-dessus des rôles qu'il doit gérer. Pour limiter un rôle à un salon, JARVIS peut créer le rôle puis appliquer l'autorisation sur ce salon.",
            color = HudDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        HudTextField(store.discordToken.value, { store.discordToken.set(it.trim()) }, Modifier.fillMaxWidth(), label = "Jeton du bot", password = true)
        Spacer(Modifier.height(6.dp))
        HudTextField(store.discordGuildId.value, { store.discordGuildId.set(it.trim()) }, Modifier.fillMaxWidth(), label = "Nom ou ID du serveur par défaut (optionnel si un seul)")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton(if (busy) "Test…" else "Tester", enabled = !busy && store.discordToken.value.isNotBlank()) {
                scope.launch {
                    busy = true
                    msg = try {
                        val arr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fr.dd.jarvis.ai.Discord.guildes(app) }
                        val noms = (0 until arr.length()).joinToString(", ") { arr.getJSONObject(it).optString("name") }
                        "✅ Bot connecté. Serveurs : ${noms.ifBlank { "aucun" }}"
                    } catch (e: Exception) { "❌ ${e.message}" }
                    busy = false
                }
            }
        }
        if (msg.isNotBlank()) Text(msg, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun TikTokPanel(store: fr.dd.jarvis.data.Store, app: fr.dd.jarvis.JarvisApp) {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val connecte = store.tiktokAccessToken.value.isNotBlank()
    HudPanel(Modifier.fillMaxWidth(), title = "TikTok — compte personnel") {
        Text(
            "Permet à JARVIS de lire les statistiques de TES vidéos (vues, likes, commentaires, partages) et de te conseiller pour en faire plus — pas de comparaison avec d'autres comptes, ces données ne sont pas publiques. " +
                "Sur developers.tiktok.com (ton app « Jarvis21 ») : passe l'app en mode Bac à sable (Sandbox, pas de validation requise), ajoute le produit Login Kit avec les scopes user.info.basic et video.list, " +
                "ajoute l'URI de redirection ci-dessous dans Login Kit, puis ajoute ton propre compte TikTok comme « utilisateur de test ».",
            color = HudDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("URI de redirection : " + fr.dd.jarvis.ai.TikTok.REDIRECT_URI, color = HudText, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("Copier", color = HudDim, fontSize = 12.sp, modifier = Modifier.clickable {
                val cm = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("redirect_uri", fr.dd.jarvis.ai.TikTok.REDIRECT_URI))
            })
        }
        Spacer(Modifier.height(8.dp))
        HudTextField(store.tiktokClientKey.value, { store.tiktokClientKey.set(it.trim()) }, Modifier.fillMaxWidth(), label = "Clé client (Client Key)")
        Spacer(Modifier.height(6.dp))
        HudTextField(store.tiktokClientSecret.value, { store.tiktokClientSecret.set(it.trim()) }, Modifier.fillMaxWidth(), label = "Secret client (Client Secret)", password = true)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (connecte) "✅ Compte connecté" else "⬜ Compte non connecté"), color = HudText, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton(if (busy) "…" else "Se connecter", enabled = !busy && store.tiktokClientKey.value.isNotBlank() && store.tiktokClientSecret.value.isNotBlank()) {
                val state = java.util.UUID.randomUUID().toString()
                store.tiktokOAuthState.set(state)
                val err = app.phone.start(android.content.Intent.ACTION_VIEW, fr.dd.jarvis.ai.TikTok.urlAutorisation(app, state), null)
                msg = if (err.isNotBlank()) "❌ $err" else ""
            }
            if (connecte) HudButton("Se déconnecter", enabled = !busy) { fr.dd.jarvis.ai.TikTok.deconnecter(app); msg = "Déconnecté." }
            HudButton(if (busy) "Test…" else "Tester", enabled = !busy && connecte) {
                scope.launch {
                    busy = true
                    msg = try {
                        val u = fr.dd.jarvis.ai.TikTok.monProfil(app)
                        "✅ ${u.optString("display_name")} — ${u.optLong("follower_count")} abonnés, ${u.optLong("video_count")} vidéos"
                    } catch (e: Exception) { "❌ ${e.message}" }
                    busy = false
                }
            }
        }
        if (msg.isNotBlank()) Text(msg, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun PollinationsPanel(store: fr.dd.jarvis.data.Store) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    HudPanel(Modifier.fillMaxWidth(), title = "Vidéo IA — fournisseurs") {
        Text("Clés conservées dans le stockage chiffré Android. Les générations peuvent consommer des crédits ou l'abonnement du fournisseur.", color = HudDim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text("Google Flow — UseAPI (service tiers, non affilié à Google)", color = HudText, fontSize = 13.sp)
        Text("Jeton UseAPI et compte Flow associé nécessaires. Les crédits Flow de ton compte Google sont utilisés.", color = HudDim, fontSize = 11.sp)
        HudTextField(store.googleFlowApiToken.value, { v -> runCatching { store.googleFlowApiToken.set(v.trim()) } }, Modifier.fillMaxWidth(), label = "Clé API UseAPI", password = true)
        HudTextField(store.googleFlowAccountEmail.value, { store.googleFlowAccountEmail.set(it.trim()) }, Modifier.fillMaxWidth(), label = "E-mail compte Flow (facultatif)")
        HudTextField(store.googleFlowModel.value, { store.googleFlowModel.set(it.trim()) }, Modifier.fillMaxWidth(), label = "Modèle Flow", placeholder = "veo-3.1-fast")
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton("Gérer le jeton UseAPI") { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://useapi.net/account")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            HudButton("Effacer Flow") { store.googleFlowApiToken.clear() }
        }
        Spacer(Modifier.height(12.dp))
        Text("Dreamina — BytePlus ModelArk (API officielle)", color = HudText, fontSize = 13.sp)
        Text("Le modèle Seedance doit être activé et disposer de crédits ou d'un forfait compatible.", color = HudDim, fontSize = 11.sp)
        HudTextField(store.dreaminaApiKey.value, { v -> runCatching { store.dreaminaApiKey.set(v.trim()) } }, Modifier.fillMaxWidth(), label = "Clé API BytePlus", password = true)
        HudTextField(store.dreaminaModel.value, { store.dreaminaModel.set(it.trim()) }, Modifier.fillMaxWidth(), label = "Modèle Seedance", placeholder = "seedance-1-5-pro-251215")
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton("Gérer la clé ModelArk") { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://console.byteplus.com/ark/region:ap-southeast-1/apiKey")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            HudButton("Effacer Dreamina") { store.dreaminaApiKey.clear() }
        }
        Spacer(Modifier.height(12.dp))
        Text("Hugging Face — service gratuit avec quota limité", color = HudText, fontSize = 13.sp)
        HudTextField(store.huggingFaceToken.value, { store.huggingFaceToken.set(it.trim()) }, Modifier.fillMaxWidth(), label = "Token Hugging Face", password = true)
        Text("Créer un token Hugging Face", color = HudDim, fontSize = 12.sp, modifier = Modifier.clickable {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://huggingface.co/settings/tokens")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        Text("Ordre prévu : Hugging Face gratuit, puis Flow, puis Dreamina. Dreamina peut facturer les générations. fal.ai reste disponible pour l'animation d'image.", color = HudDim, fontSize = 11.sp)
    }
}

@Composable
private fun ImprimantePanel(store: fr.dd.jarvis.data.Store, app: fr.dd.jarvis.JarvisApp) {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    HudPanel(Modifier.fillMaxWidth(), title = "Imprimante réseau — accès local et distant") {
        Text(
            "Détecte les imprimantes IPP annoncées sur le Wi-Fi. Une fois enregistrée, Jarvis peut envoyer les impressions localement ou passer par le serveur Debian lorsque le téléphone est en 4G/5G. Le serveur Debian doit rester allumé et joignable en SSH.",
            color = HudDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        HudTextField(store.printerHost.value, { store.printerHost.set(it.trim()) }, Modifier.fillMaxWidth(), label = "Adresse IP de l'imprimante")
        Spacer(Modifier.height(6.dp))
        HudTextField(store.printerPath.value, { store.printerPath.set(it.trim().ifBlank { "/ipp/print" }) }, Modifier.fillMaxWidth(), label = "Chemin IPP (détecté automatiquement)")
        if (store.printerName.value.isNotBlank()) {
            Text("Imprimante enregistrée : ${store.printerName.value}", color = HudText, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton(if (busy) "Recherche…" else "Détecter sur le Wi-Fi", enabled = !busy) {
                scope.launch {
                    busy = true
                    msg = try {
                        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val services = fr.dd.jarvis.ai.Reseau.decouvrir(app, listOf("_ipp._tcp", "_ipps._tcp", "_printer._tcp"), 5000)
                            val ips = services.map { it.host }.distinct().take(12)
                            var result: fr.dd.jarvis.ai.Impression.Imprimante? = null
                            for (ip in ips) {
                                val service = services.firstOrNull { it.host == ip }
                                result = fr.dd.jarvis.ai.Impression.interroger(app, ip, "local", service?.chemin?.takeIf { it.isNotBlank() })
                                if (result != null) break
                            }
                            result
                        }
                        if (found != null) {
                            store.printerHost.set(found.ip)
                            store.printerName.set(found.nom)
                            store.printerPath.set(found.chemin)
                            "✅ ${found.nom} détectée sur ${found.ip}."
                        } else "Aucune imprimante IPP détectée. Vérifie qu'elle est sur le même Wi-Fi et que le service IPP est activé."
                    } catch (e: Exception) { "❌ ${e.message ?: e.javaClass.simpleName}" }
                    busy = false
                }
            }
            HudButton(if (busy) "Test…" else "Tester l'accès", enabled = !busy && store.printerHost.value.isNotBlank()) {
                scope.launch {
                    busy = true
                    msg = try {
                        var via = "local"
                        val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val ip = store.printerHost.value.trim()
                            val chemin = store.printerPath.value.trim()
                            val direct = if (fr.dd.jarvis.ai.Reseau.infos(app)?.wifi == true)
                                fr.dd.jarvis.ai.Impression.interroger(app, ip, "local", chemin) else null
                            if (direct != null) direct
                            else if (fr.dd.jarvis.ai.Serveur.sshConfigure(app)) {
                                via = "serveur"
                                fr.dd.jarvis.ai.Impression.interroger(app, ip, "serveur", chemin)
                            } else null
                        }
                        if (p != null) {
                            store.printerName.set(p.nom)
                            store.printerPath.set(p.chemin)
                            "✅ ${p.nom} — ${p.modele.ifBlank { p.ip }} — ${p.etat} (via ${if (via == "serveur") "Debian" else "Wi-Fi"})."
                        } else "❌ Imprimante inaccessible en direct et via Debian. Vérifie SSH, l'adresse IPP et que Debian peut joindre l'imprimante."
                    } catch (e: Exception) { "❌ ${e.message ?: e.javaClass.simpleName}" }
                    busy = false
                }
            }
        }
        Text("À distance, Jarvis utilise SSH vers Debian puis IPP sur le réseau local. Aucun port 631 ne doit être ouvert sur Internet.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        if (msg.isNotBlank()) Text(msg, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun BoxPanel(store: fr.dd.jarvis.data.Store, app: fr.dd.jarvis.JarvisApp) {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    HudPanel(Modifier.fillMaxWidth(), title = "Box internet — disque dur USB") {
        Text(
            "Permet à JARVIS de parcourir, lire, récupérer et déposer des fichiers sur le disque dur branché sur ta box (partage Windows/SMB ou FTP). Active le partage de fichiers dans l'interface de ta box ; identifiants = ceux du partage (vides = invité).",
            color = HudDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        HudTextField(store.boxHost.value, { store.boxHost.set(it.trim()) }, Modifier.fillMaxWidth(), label = "Adresse locale de la box (passerelle Wi-Fi)")
        Spacer(Modifier.height(6.dp))
        HudButton(if (busy) "Diagnostic…" else "Détecter la box et l'IP publique", enabled = !busy) {
            scope.launch {
                busy = true
                msg = try {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val local = fr.dd.jarvis.ai.Reseau.infos(app)
                        val publicIp = fr.dd.jarvis.ai.Reseau.ippPublique()
                        local to publicIp
                    }
                    val local = result.first
                    if (!local?.passerelle.isNullOrBlank() && fr.dd.jarvis.ai.Reseau.prive(local!!.passerelle)) store.boxHost.set(local.passerelle)
                    "Réseau local : ${local?.ip ?: "indisponible"} · Passerelle/box : ${local?.passerelle ?: "indisponible"} · IP publique : ${result.second.ifBlank { "indisponible" }}. La redirection distante doit encore être validée selon les fonctions de ta box et ton opérateur."
                } catch (e: Exception) { "❌ Diagnostic réseau : ${e.message ?: e.javaClass.simpleName}" }
                busy = false
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudChip("SMB (Windows)", selected = store.boxProto.value != "ftp") { store.boxProto.set("smb") }
            HudChip("FTP", selected = store.boxProto.value == "ftp") { store.boxProto.set("ftp") }
        }
        Spacer(Modifier.height(6.dp))
        HudTextField(store.boxUser.value, { store.boxUser.set(it.trim()) }, Modifier.fillMaxWidth(), label = "Utilisateur du partage (vide = invité)")
        Spacer(Modifier.height(6.dp))
        HudTextField(store.boxPass.value, { store.boxPass.set(it) }, Modifier.fillMaxWidth(), label = "Mot de passe", password = true)
        Spacer(Modifier.height(8.dp))
        HudButton(if (busy) "Test…" else "Tester (lister les partages)", enabled = !busy) {
            scope.launch {
                busy = true
                msg = try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fr.dd.jarvis.ai.Box.action(app, "partages", "", "") }.take(600) }
                catch (e: Exception) { "❌ ${e.message ?: e.javaClass.simpleName}" }
                busy = false
            }
        }
        if (msg.isNotBlank()) Text(msg, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun DashboardMetric(label: String, value: String) {
    Column(Modifier.width(104.dp).background(Color(0x1AFFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(9.dp)) {
        Text(label, color = HudDim, fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(value, color = HudText, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

@Composable
private fun TrendLine(values: List<Float>, modifier: Modifier = Modifier, lineColor: Color = LocalAccent.current) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.minOrNull() ?: 0f
        val max = values.maxOrNull() ?: 1f
        val range = (max - min).coerceAtLeast(0.5f)
        val left = 5.dp.toPx()
        val right = size.width - 5.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        for (i in 0..3) {
            val y = top + (bottom - top) * i / 3f
            drawLine(Color.White.copy(alpha = 0.10f), androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(right, y), 1.dp.toPx())
        }
        val path = androidx.compose.ui.graphics.Path()
        values.forEachIndexed { i, v ->
            val x = left + (right - left) * i / (values.size - 1).toFloat()
            val y = bottom - (v - min) / range * (bottom - top)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        val lastY = bottom - (values.last() - min) / range * (bottom - top)
        drawCircle(lineColor, 4.dp.toPx(), androidx.compose.ui.geometry.Offset(right, lastY))
    }
}
