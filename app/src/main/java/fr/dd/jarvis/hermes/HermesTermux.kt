package fr.dd.jarvis.hermes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.AdbShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Installe et pilote Hermès Agent (Nous Research) dans Termux, depuis Jarvis :
 *  1. Termux (GitHub officiel) — installation silencieuse via l'ADB intégré s'il est appairé ;
 *  2. autorisation « Exécuter des commandes dans Termux » + `allow-external-apps` (une seule fois) ;
 *  3. script d'installation lancé en arrière-plan dans Termux (paquet officiel signé, sinon sources) ;
 *  4. configuration : Gemini comme modèle, Groq en secours, serveur API local, pont MCP vers les outils de Jarvis ;
 *  5. démarrage de la passerelle (`hermes gateway run`) et vérification sur http://127.0.0.1:8642/health.
 */
object HermesTermux {
    const val TERMUX = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val PORT_API = 8642
    private const val HOME = "/data/data/com.termux/files/home"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val EMPREINTE = "C572B5FDD1A29CCFA9A912B6840B0848E139156D"
    private const val NOTIF_ID = 8642

    private val configurationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()

    /** Dernier message d'étape (affiché dans les réglages). */
    var message by mutableStateOf("")

    fun termuxInstalle(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(TERMUX, 0); true }.getOrDefault(false)
    fun permissionOk(ctx: Context) = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    // ------------------------------------------------------------------ 1. Termux

    /** Télécharge Termux (GitHub termux/termux-app) puis l'installe : sans écran via l'ADB intégré, sinon via l'installateur Android. */
    suspend fun installerTermux(ctx: Context): String = withContext(Dispatchers.IO) {
        if (termuxInstalle(ctx)) return@withContext "✅ Termux est déjà installé."
        try {
            val rel = http.newCall(Request.Builder().url("https://api.github.com/repos/termux/termux-app/releases/latest")
                .header("User-Agent", "JarvisHUD").build()).execute().use { it.body?.string() ?: "" }
            val assets = JSONObject(rel).getJSONArray("assets")
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            val noms = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name").endsWith(".apk") }
            val choix = noms.firstOrNull { it.getString("name").contains(abi) } ?: noms.firstOrNull { it.getString("name").contains("universal") }
                ?: return@withContext "❌ APK Termux introuvable sur GitHub."
            val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "termux.apk")
            http.newCall(Request.Builder().url(choix.getString("browser_download_url")).header("User-Agent", "JarvisHUD").build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext "❌ Téléchargement de Termux refusé (${r.code})."
                r.body!!.byteStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            }
            if (AdbShell.hasKey(ctx)) {
                val r = runCatching { AdbShell.installApk(ctx, f) }.getOrElse { it.message ?: "" }
                if (r.contains("Success", true)) return@withContext "✅ Termux installé (${choix.getString("name")})."
            }
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@withContext "Autorise « Installer des applis inconnues » pour JARVIS (écran ouvert), puis retouche « Installer Termux »."
            }
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            "📦 Termux téléchargé : confirme l'installation."
        } catch (e: Throwable) { "❌ " + (e.message ?: e.javaClass.simpleName) }
    }

    // ------------------------------------------------------------------ 2. Autorisation

    /** Commande à saisir UNE fois dans Termux pour accepter les commandes de Jarvis. */
    const val COMMANDE_AUTORISATION =
        "mkdir -p ~/.termux; grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null && " +
            "sed -i 's/^allow-external-apps.*/allow-external-apps = true/' ~/.termux/termux.properties || " +
            "echo 'allow-external-apps = true' >> ~/.termux/termux.properties; termux-reload-settings; echo '✅ Jarvis autorisé'"

    /** Version courte, tapée automatiquement via l'ADB intégré (pas d'espace ni de guillemet à échapper). */
    private const val COMMANDE_ADB = "mkdir%s-p%s~/.termux;echo%sallow-external-apps=true>>~/.termux/termux.properties;termux-reload-settings"

    /** Vérifie que Termux accepte les commandes de Jarvis (le script renvoie « pong » au pont local). */
    suspend fun autorisationOk(app: JarvisApp, attenteMs: Long = 6000): Boolean {
        if (!termuxInstalle(app) || !permissionOk(app)) return false
        PontMcp.demarrer(app)
        val avant = PontMcp.dernierSignal
        if (executer(app, scriptSignal(app, "pong"), arrierePlan = true) != null) return false
        val fin = System.currentTimeMillis() + attenteMs
        while (System.currentTimeMillis() < fin) { if (PontMcp.dernierSignal > avant) return true; delay(300) }
        return false
    }

    /**
     * Rend Termux pilotable par Jarvis. Avec l'ADB intégré : permission accordée, Termux ouvert, commande tapée
     * automatiquement, limites Android sur les processus de Termux levées. Sinon : commande copiée, Termux ouvert.
     */
    suspend fun autoriser(app: JarvisApp): String = withContext(Dispatchers.IO) {
        if (!termuxInstalle(app)) return@withContext "Installe d'abord Termux."
        val adb = AdbShell.hasKey(app)
        if (adb) {
            runCatching { AdbShell.exec(app, "pm grant ${app.packageName} $PERMISSION") }
            // Android 12+ tue les processus « fantômes » (enfants) de Termux : on lève cette limite et l'optimisation batterie.
            runCatching {
                AdbShell.exec(app, "settings put global settings_enable_monitor_phantom_procs false; " +
                    "device_config set_sync_disabled_for_tests persistent; device_config put activity_manager max_phantom_processes 2147483647; " +
                    "dumpsys deviceidle whitelist +$TERMUX; cmd appops set $TERMUX RUN_ANY_IN_BACKGROUND allow")
            }
        }
        if (!permissionOk(app)) return@withContext "Accorde d'abord à JARVIS la permission « Exécuter des commandes dans Termux » (bouton ci-dessus)."
        if (autorisationOk(app, 4000)) return@withContext "✅ Termux accepte déjà les commandes de Jarvis."
        if (adb) {
            message = "⏳ Ouverture de Termux et saisie automatique de l'autorisation…"
            runCatching { AdbShell.exec(app, "am start -n $TERMUX/.app.TermuxActivity") }
            for (essai in 1..3) {
                delay(if (essai == 1) 12_000 else 20_000) // 1re ouverture : Termux installe ses paquets de base
                runCatching { AdbShell.exec(app, "input text '$COMMANDE_ADB' && input keyevent 66") }
                delay(2500)
                if (autorisationOk(app, 5000)) {
                    runCatching { AdbShell.exec(app, "am start -n ${app.packageName}/.MainActivity") }
                    return@withContext "✅ Termux autorisé automatiquement."
                }
            }
            runCatching { AdbShell.exec(app, "am start -n ${app.packageName}/.MainActivity") }
        }
        withContext(Dispatchers.Main) {
            (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Jarvis → Termux", COMMANDE_AUTORISATION))
            app.packageManager.getLaunchIntentForPackage(TERMUX)?.let { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        "📋 Commande copiée et Termux ouvert : attends la fin de sa première préparation, fais un appui long › Coller, puis Entrée. " +
            "Reviens ensuite dans Jarvis et touche « Installer Hermès »." +
            (if (!adb) "\nConseil Android 12+ : Options pour développeurs › « Désactiver les restrictions des processus enfants », et batterie de Termux sur « Aucune restriction »." else "")
    }

    // ------------------------------------------------------------------ Exécution dans Termux

    /** Envoie une commande bash à Termux (RUN_COMMAND). Renvoie un message d'erreur, ou null si elle est partie. */
    fun executer(ctx: Context, script: String, arrierePlan: Boolean = true, libelle: String = "Jarvis", retourNatif: Boolean = false): String? {
        if (!termuxInstalle(ctx)) return "Termux n'est pas installé."
        if (!permissionOk(ctx)) return "Permission « Exécuter des commandes dans Termux » non accordée à JARVIS."
        val i = Intent("com.termux.RUN_COMMAND").setClassName(TERMUX, "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", arrierePlan)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", libelle)
        if (retourNatif) {
            val retourIntent = Intent(ctx, HermesRunResultReceiver::class.java)
                .setAction("fr.dd.jarvis.HERMES_RUN_RESULT")
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, UUID.randomUUID().hashCode(), retourIntent, flags)
            i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
        }
        return try {
            // La documentation Termux utilise startService pour RUN_COMMAND ; Termux gère lui-même le service.
            ctx.startService(i)
            null
        } catch (e: Throwable) { "Termux a refusé la commande : ${e.message ?: e.javaClass.simpleName}" }
    }

    /** Fonction shell `p` : affiche ET envoie une ligne de progression au pont local de Jarvis. */
    private fun prelude(app: JarvisApp) = scriptPrelude(app.store.mcpJeton.value)

    internal fun scriptPrelude(jeton: String, port: Int = PontMcp.PORT) = """
J_URL=${q("http://127.0.0.1:$port/progres")}; J_TOK=${q(jeton)}
p() {
  local message="${'$'}*"
  if [ -z "${'$'}message" ]; then return 0; fi
  echo "${'$'}message"
  if ! command -v curl >/dev/null 2>&1; then
    echo "JARVIS_PROGRESS_ERROR: curl absent" >&2
    return 127
  fi
  local response code
  if response=$(curl -fsS --connect-timeout 3 -m 8 -X POST -H "Authorization: Bearer ${'$'}J_TOK" --data-binary "${'$'}message" "${'$'}J_URL"); then
    :
  else
    code=${'$'}?
    echo "JARVIS_PROGRESS_ERROR: échec HTTP/curl (${'$'}code)" >&2
    return "${'$'}code"
  fi
  if [ "${'$'}response" != '{"accepted":true}' ]; then
    echo "JARVIS_PROGRESS_ERROR: accusé de réception invalide: ${'$'}response" >&2
    return 1
  fi
}
""".trimIndent()
    private fun scriptSignal(app: JarvisApp, texte: String) = prelude(app) + "\np ${q(texte)}\n"

    // ------------------------------------------------------------------ 3-5. Installation, configuration, démarrage

    private fun configuration(app: JarvisApp): String {
        val s = app.store
        if (s.hermesCle.value.isBlank()) s.hermesCle.set(UUID.randomUUID().toString().replace("-", ""))
        if (s.mcpJeton.value.isBlank()) s.mcpJeton.set(UUID.randomUUID().toString().replace("-", ""))
        val geminiKeys = if (s.apiKeysSecureAvailable) s.geminiKeys.toList() else emptyList()
        val groqKeys = if (s.apiKeysSecureAvailable) s.groqKeys.toList() else emptyList()
        val base = scriptConfiguration(
            cleApi = s.hermesCle.value, jeton = s.mcpJeton.value,
            gemini = geminiKeys.firstOrNull().orEmpty(), groq = groqKeys.firstOrNull().orEmpty(),
            modele = s.hermesModele.value.ifBlank { s.geminiModel.value }.ifBlank { "gemini-2.5-flash" },
            modeleGroq = s.groqModel.value.ifBlank { "llama-3.3-70b-versatile" },
            sunoActif = s.sunoMcpActive.value
        )
        val extraEnv = buildString {
            geminiKeys.drop(1).forEachIndexed { index, key -> appendLine("setenv GOOGLE_API_KEY_" + (index + 2) + " " + q(key)) }
            groqKeys.drop(1).forEachIndexed { index, key -> appendLine("setenv GROQ_API_KEY_" + (index + 2) + " " + q(key)) }
        }
        return base.replace("setenv API_SERVER_MODEL_NAME hermes-agent", "setenv API_SERVER_MODEL_NAME hermes-agent\n" + extraEnv)
    }

    internal fun scriptConfiguration(cleApi: String, jeton: String, gemini: String, groq: String, modele: String, modeleGroq: String, urlMcp: String = PontMcp.url(), sunoActif: Boolean = false): String {
        val yaml = buildString {
            appendLine("# Géré par Jarvis (fr.dd.jarvis) : régénéré à chaque « Configurer Hermès » depuis l'application.")
            appendLine("# Ancienne configuration éventuelle : config.yaml.avant-jarvis")
            appendLine("model:")
            appendLine("  provider: gemini")
            appendLine("  default: $modele")
            if (groq.isNotBlank()) {
                appendLine("providers:")
                appendLine("  groq:")
                appendLine("    api: https://api.groq.com/openai/v1")
                appendLine("    key_env: GROQ_API_KEY")
                appendLine("fallback_providers:")
                appendLine("  - provider: custom:groq")
                appendLine("    model: \"$modeleGroq\"")
            }
            appendLine("credential_pool_strategies:")
            appendLine("  gemini: round_robin")
            if (groq.isNotBlank()) appendLine("  groq: round_robin")
            // Active explicitement la mémoire native évolutive d'Hermès.
            // La mémoire Jarvis/Obsidian reste la référence partagée pour les préférences utilisateur.
            appendLine("memory:")
            appendLine("  memory_enabled: true")
            appendLine("  user_profile_enabled: true")
            appendLine("  write_approval: false")
            appendLine("mcp_servers:")
            appendLine("  jarvis:")
            appendLine("    url: \"$urlMcp\"")
            appendLine("    headers:")
            appendLine("      Authorization: \"Bearer $jeton\"")
            appendLine("    timeout: 180")
            appendLine("    connect_timeout: 20")
            if (sunoActif) {
                appendLine("  suno:")
                appendLine("    url: https://api.suno.com/mcp")
                appendLine("    auth: oauth")
                appendLine("    timeout: 180")
                appendLine("    connect_timeout: 30")
            }
            appendLine("gateway:")
            appendLine("  api_server:")
            appendLine("    enabled: true")
            appendLine("    port: $PORT_API")
            // Découverte progressive : les schémas MCP complets restent différés derrière tool_search / tool_describe / tool_call.
            appendLine("tools:")
            appendLine("  tool_search:")
            appendLine("    enabled: \"on\"")
            appendLine("    search_default_limit: 8")
            appendLine("    max_search_limit: 20")
            appendLine("    listing: \"off\"")
            appendLine("    listing_max_tokens: 2000")
        }
        return """
H="${'$'}HOME/.hermes"; mkdir -p "${'$'}H/logs"
setenv() { touch "${'$'}H/.env"; grep -v "^${'$'}1=" "${'$'}H/.env" > "${'$'}H/.env.j" 2>/dev/null; printf '%s=%s\n' "${'$'}1" "${'$'}2" >> "${'$'}H/.env.j"; mv "${'$'}H/.env.j" "${'$'}H/.env"; }
removeenvfamily() { grep -v -E "^${'$'}1(_[0-9]+)?=" "${'$'}H/.env" > "${'$'}H/.env.j" 2>/dev/null || true; mv "${'$'}H/.env.j" "${'$'}H/.env"; }
removeenvfamily GOOGLE_API_KEY
removeenvfamily GROQ_API_KEY
setenv API_SERVER_ENABLED true
setenv API_SERVER_HOST 127.0.0.1
setenv API_SERVER_PORT $PORT_API
setenv API_SERVER_KEY ${q(cleApi)}
setenv API_SERVER_MODEL_NAME hermes-agent
${if (gemini.isNotBlank()) "setenv GOOGLE_API_KEY ${q(gemini)}" else "p '⚠️ Aucune clé Gemini dans Jarvis : ajoute-en une (Réglages › Agents Gemini) puis « Configurer Hermès ».'"}
${if (groq.isNotBlank()) "setenv GROQ_API_KEY ${q(groq)}" else ""}
chmod 600 "${'$'}H/.env"
# Version de schéma de la configuration installée : conservée, sinon Hermès croit à une vieille config et la migre.
CV=${'$'}(grep -m1 '^_config_version:' "${'$'}H/config.yaml" 2>/dev/null || grep -m1 '^_config_version:' "${'$'}H/config.yaml.avant-jarvis" 2>/dev/null)
if [ -f "${'$'}H/config.yaml" ] && ! grep -q 'Géré par Jarvis' "${'$'}H/config.yaml"; then cp "${'$'}H/config.yaml" "${'$'}H/config.yaml.avant-jarvis"; p "💾 Ancienne configuration d'Hermès sauvegardée (config.yaml.avant-jarvis)."; fi
cat > "${'$'}H/config.yaml" <<'JARVIS_YAML'
${yaml.trimEnd()}
JARVIS_YAML
[ -n "${'$'}CV" ] && printf '%s\n' "${'$'}CV" >> "${'$'}H/config.yaml"
p "⚙️ Configuration écrite : modèle Gemini $modele${if (groq.isNotBlank()) ", Groq en secours" else ""}, pont MCP Jarvis, API locale :$PORT_API."
""".trimIndent()
    }

    /**
     * Démarrage de la passerelle : Hermès natif Termux (paquet officiel) ou, le plus souvent, Hermès installé dans
     * Debian (proot-distro) — le dossier ~/.hermes est partagé avec Termux (--termux-home), la configuration écrite
     * par Jarvis vaut donc pour les deux, et 127.0.0.1 est le même réseau que celui de Jarvis.
     */
    internal val demarrage = """
H="${'$'}{HOME}/.hermes"; mkdir -p "${'$'}{H}/logs"
termux-wake-lock >/dev/null 2>&1 || true
if curl -s -m 2 http://127.0.0.1:$PORT_API/health >/dev/null 2>&1; then
  p "✅ Hermès est déjà en ligne."
  exit 0
fi
p '🔄 Préparation du démarrage de la passerelle Hermès…'
# Détecter Debian même si le marqueur historique .jarvis-mode est vide.
HERMES_PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
if [ "$(cat "${'$'}{H}/.jarvis-mode" 2>/dev/null)" = proot ] || proot-distro login debian --termux-home -- env -i HOME=/root PATH="${'$'}{HERMES_PATH}" sh -c 'command -v hermes >/dev/null 2>&1'; then
  CMD="proot-distro login debian --termux-home -- env -i HOME=/root USER=root LOGNAME=root SHELL=/bin/bash PATH=${'$'}{HERMES_PATH} DEBIAN_FRONTEND=noninteractive hermes gateway run"
else
  if ! command -v hermes >/dev/null 2>&1; then
    p "❌ CLI Hermès introuvable dans Termux et Debian. Relance « Installer Hermès » pour réparer l’installation."
    exit 127
  fi
  CMD="hermes gateway run"
fi
if command -v setsid >/dev/null 2>&1; then
  setsid nohup sh -c "${'$'}{CMD}" >> "${'$'}{H}/logs/gateway.log" 2>&1 < /dev/null &
else
  nohup sh -c "${'$'}{CMD}" >> "${'$'}{H}/logs/gateway.log" 2>&1 < /dev/null &
fi
p "🚀 Passerelle lancée, vérification de l’API locale…"
for i in ${'$'}(seq 1 60); do
  if curl -s -m 2 http://127.0.0.1:$PORT_API/health >/dev/null 2>&1; then p "✅ Hermès est en ligne."; exit 0; fi
  sleep 2
done
p "❌ Hermès ne répond pas. Dernières lignes du journal :"
tail -n 10 "${'$'}{H}/logs/gateway.log" 2>/dev/null | while IFS= read -r l; do p "   ${'$'}{l}"; done
exit 1
""".trimIndent()
    /**
     * Installation. Le paquet Termux officiel d'Hermès est (pour l'instant) indisponible, et l'installation « pip »
     * directement dans Termux échoue (dépendances sans version Android). Contournement fiable : une Debian légère
     * dans Termux (proot-distro, sans root) où l'installateur officiel Linux aarch64 fonctionne normalement.
     */
    internal val installation = """
export DEBIAN_FRONTEND=noninteractive
APT="apt-get -y -o Dpkg::Options::=--force-confnew -o Dpkg::Options::=--force-confdef"
H="${'$'}{HOME}/.hermes"; mkdir -p "${'$'}{H}/logs"; LOG="${'$'}{H}/logs/jarvis-install.log"
p "🚀 Installation d'Hermès lancée (10 à 25 min, le téléphone reste utilisable)."
termux-wake-lock >/dev/null 2>&1 || true
# Nettoyage d'une ancienne tentative « depuis les sources » dans Termux (compilation vouée à l'échec).
# Ne pas utiliser pkill -f ici : le motif figure dans la commande RUN_COMMAND elle-même et peut tuer ce script parent.
if [ -d "${'$'}{HOME}/hermes-agent/.venv" ]; then rm -rf "${'$'}{HOME}/hermes-agent"; p "🧹 Ancienne tentative d'installation supprimée."; fi
if [ -L "${'$'}{PREFIX}/bin/hermes" ] && ! hermes --version >/dev/null 2>&1; then rm -f "${'$'}{PREFIX}/bin/hermes"; fi
pkg update -y >/dev/null 2>&1 || apt-get update >/dev/null 2>&1
${'$'}{APT} install curl gnupg util-linux >/dev/null 2>&1 || p "⚠️ Paquets de base : échec partiel."
case "${'$'}(uname -m)" in aarch64) ;; *) p "⚠️ Architecture ${'$'}(uname -m) : seul aarch64 est pris en charge officiellement.";; esac
# Forcer Debian sous proot : le compilateur Android/Bionic ne peut pas construire pillow-heif.
# Dans Debian aarch64/glibc, uv peut récupérer la roue manylinux précompilée.
MODE=""
p "ℹ️ Installation dans Debian légère (proot) pour éviter les compilations incompatibles avec Android."
if [ -z "${'$'}{MODE}" ]; then
  ${'$'}{APT} install proot-distro >> "${'$'}{LOG}" 2>&1 || { p "❌ Impossible d'installer proot-distro (réseau ?)."; exit 1; }
  if ! proot-distro login debian -- true >/dev/null 2>&1; then
    p "🐧 Téléchargement de Debian (≈ 50 Mo)…"
    proot-distro install debian >> "${'$'}{LOG}" 2>&1 || { p "❌ Installation de Debian impossible (voir ~/.hermes/logs/jarvis-install.log)."; exit 1; }
  fi
  p "📦 Préparation de Debian (git, curl…)…"
  # Debian doit utiliser exclusivement ses outils glibc : --termux-home peut transmettre des variables
  # de compilation Termux (CC=aarch64-linux-android-clang) qui contaminent uv/pip dans proot.
  proot-distro login debian --termux-home -- env -i HOME=/root USER=root LOGNAME=root SHELL=/bin/bash \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin DEBIAN_FRONTEND=noninteractive \
    bash -c 'apt-get update && apt-get install -y curl git ca-certificates xz-utils tar libatomic1 procps build-essential pkg-config libheif-dev libwebp-dev python3 python3-venv python3-dev' >> /data/data/com.termux/files/home/.hermes/logs/jarvis-install.log 2>&1 \
    || { p "❌ Préparation de Debian impossible (voir ~/.hermes/logs/jarvis-install.log)."; exit 1; }
  p "🔎 Vérification du compilateur Debian (GCC, glibc)…"
  proot-distro login debian --termux-home -- env -i HOME=/root PATH=/usr/bin:/bin bash -c 'printf "CC="; command -v cc; cc --version | head -n1; printf "ARCH="; uname -m; printf "LIBC="; ldd --version | head -n1' >> "${'$'}{LOG}" 2>&1
  p "⬇️ Installateur officiel d'Hermès (Linux aarch64)… c'est l'étape la plus longue."
  INSTALL_LOG="${'$'}{H}/logs/hermes-installer.log"
  : > "${'$'}{INSTALL_LOG}"
  p "⏳ Installation des dépendances Hermès démarrée. Variables de compilation Android neutralisées."
  proot-distro login debian --termux-home -- env -i HOME=/root USER=root LOGNAME=root SHELL=/bin/bash \
    PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    DEBIAN_FRONTEND=noninteractive PIP_DISABLE_PIP_VERSION_CHECK=1 \
     UV_PYTHON_INSTALL_DIR=/usr/local/share/uv/python UV_CACHE_DIR=/var/cache/uv \
     bash -c 'unset CC CXX CPP AR AS LD CFLAGS CXXFLAGS CPPFLAGS LDFLAGS LDSHARED CMAKE_ARGS VIRTUAL_ENV CONDA_PREFIX TERMUX_VERSION PREFIX LD_PRELOAD; set -o pipefail; mkdir -p /usr/local/share/uv/python /var/cache/uv /root/.hermes; curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash -s -- --non-interactive --skip-browser --skip-computer-use' > "${'$'}{INSTALL_LOG}" 2>&1 &
  INSTALL_PID=$!
  while kill -0 "${'$'}{INSTALL_PID}" 2>/dev/null; do
    sleep 20
    if kill -0 "${'$'}{INSTALL_PID}" 2>/dev/null; then
      DERNIERES="$(tail -n 3 "${'$'}{INSTALL_LOG}" 2>/dev/null | sed 's/\\x1b\\[[0-9;]*m//g' | cut -c1-115 | tr '\\n' ' ')"
      p "⏳ Hermès installe ses composants : ${'$'}{DERNIERES:-travail en cours, aucune nouvelle sortie pour le moment}."
    fi
  done
  wait "${'$'}{INSTALL_PID}"
  INSTALL_RC=$?
  cat "${'$'}{INSTALL_LOG}" >> "${'$'}{LOG}"
  if [ "${'$'}{INSTALL_RC}" -ne 0 ]; then
    p "❌ L’installation d’Hermès a échoué (code ${'$'}{INSTALL_RC}). Dernières lignes :"
    tail -n 10 "${'$'}{INSTALL_LOG}" | sed 's/\\x1b\\[[0-9;]*m//g' | while IFS= read -r l; do p "   $(printf '%s' "${'$'}{l}" | cut -c1-120)"; done
    exit "${'$'}{INSTALL_RC}"
  fi
  p "🔎 Vérification de l'environnement Python Hermes…"
  if ! proot-distro login debian --termux-home -- env -i HOME=/root PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/bin:/usr/bin:/bin bash -lc 'command -v python3 >/dev/null && python3 --version >/dev/null && hermes --version >/dev/null 2>&1'; then
    p "❌ Runtime Python/CLI Hermès introuvable dans Debian. La configuration et le démarrage sont interrompus."
    tail -n 15 "${'$'}{INSTALL_LOG}" | sed 's/\\x1b\\[[0-9;]*m//g' | while IFS= read -r l; do p "   ${'$'}(printf '%s' "${'$'}{l}" | cut -c1-120)"; done
    exit 1
  fi
fi
MODE="proot"
echo "${'$'}{MODE}" > "${'$'}{H}/.jarvis-mode"
p "✅ Hermès installé (${'$'}{MODE})."
""".trimIndent()

    /** Lance l'installation complète (arrière-plan dans Termux), puis la configuration et le démarrage. */
    fun installer(app: JarvisApp): String {
        if (!app.store.apiKeysSecureAvailable) {
            return "⛔ Le stockage chiffré Android est indisponible. Hermès n'a pas été configuré pour éviter de transférer des clés non protégées."
        }
        PontMcp.demarrer(app)
        app.store.hermesInstalle.set(false)
        val script = prelude(app) + "\n" + installation + "\n" + configuration(app) + "\n" + demarrage
        message = "⏳ Installation envoyée à Termux…"
        return executer(app, script, libelle = "Installation d'Hermès") ?: "🚀 Installation lancée dans Termux : suis la progression ici (5 à 20 min)."
    }

    /** Réécrit la configuration (clés, modèle, pont MCP) puis redémarre Hermès. */
    fun configurer(app: JarvisApp): String {
        if (!app.store.apiKeysSecureAvailable) {
            return "⛔ Le stockage chiffré Android est indisponible. Configuration Hermès interrompue pour éviter de supprimer ou de transmettre des clés non protégées."
        }
        if (!termuxInstalle(app) || !permissionOk(app)) return "ℹ️ Termux et son autorisation sont nécessaires pour configurer Hermès."
        return synchroniserCles(app)
    }


    /** Résultat natif RUN_COMMAND de Termux : distingue un refus du service d'un échec du script. */
    internal fun recevoirRetourTermux(exitCode: Int, erreurInterne: Int, messageErreur: String, stderr: String) {
        if (erreurInterne != -1) {
            message = "❌ Termux a refusé ou interrompu la commande (erreur interne $erreurInterne) : " +
                messageErreur.ifBlank { "aucun détail fourni par Termux" } +
                ". Vérifie allow-external-apps et la permission RUN_COMMAND."
        } else if (exitCode != 0) {
            val detail = stderr.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }?.take(180).orEmpty()
            message = "❌ Le script a démarré dans Termux mais s'est arrêté (code $exitCode)." +
                if (detail.isNotBlank()) " Détail : $detail" else " Consulte les journaux Hermès dans ~/.hermes/logs."
        } else if (PontMcp.dernierSignal <= dernierSignalAvantCommande) {
            message = "⚠️ Termux confirme l'exécution (code 0), mais Jarvis n'a reçu aucun POST de progression sur le port ${PontMcp.PORT}. Le service Termux fonctionne ; le pont HTTP doit être vérifié."
        }
    }

    @Volatile private var dernierSignalAvantCommande: Long = 0L

    /** Synchronise les clés et le routage multi-agents sans écraser les autres réglages Hermes. */
    fun synchroniserCles(app: JarvisApp): String {
        val s = app.store
        if (!s.apiKeysSecureAvailable) return "⛔ Stockage chiffré Android indisponible : aucune clé transmise à Hermès."
        if (!termuxInstalle(app) || !permissionOk(app)) return "ℹ️ Clés chiffrées ; synchronisation en attente de Termux et de son autorisation."
        message = "⏳ Préparation Hermès en arrière-plan…"
        configurationScope.launch {
            try {
                val resultat = synchroniserClesEnArrierePlan(app)
                if (resultat.startsWith("❌") || resultat.startsWith("⛔")) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { message = resultat }
                }
            } catch (e: Throwable) {
                val detail = e.message ?: e.javaClass.simpleName
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    message = "❌ Préparation Hermès interrompue : $detail. Aucune nouvelle configuration n'a été appliquée."
                }
            }
        }
        return "⏳ Préparation Hermès lancée en arrière-plan. Les étapes et erreurs apparaîtront ici."
    }

    private fun synchroniserClesEnArrierePlan(app: JarvisApp): String {
        val s = app.store
        if (!s.apiKeysSecureAvailable) return "⛔ Stockage chiffré Android indisponible : aucune clé transmise à Hermès."
        if (!termuxInstalle(app) || !permissionOk(app)) return "ℹ️ Clés chiffrées ; synchronisation en attente de Termux et de son autorisation."
        // Le serveur de progression doit être démarré AVANT l'envoi du script Termux.
        // Après un redémarrage de Jarvis avec hermesInstalle=false, il n'est pas encore lancé :
        // sans ce pont, Termux exécute la mise à niveau mais tous les POST /progres échouent silencieusement,
        // laissant l'interface bloquée sur « Configuration Hermès lancée ».
        PontMcp.progression.clear()
        if (!PontMcp.demarrer(app) || !PontMcp.actif()) {
            message = "❌ Suivi Hermès indisponible : le serveur local de progression n\u0027écoute pas sur 127.0.0.1:${PontMcp.PORT}. Aucun script de mise à niveau n\u0027a été lancé. Ferme puis rouvre Jarvis ; si le problème persiste, vérifie qu\u0027aucun ancien service n\u0027occupe ce port."
            return message
        }
        message = "✅ Pont local de progression actif sur 127.0.0.1:${PontMcp.PORT}. Préparation du script Hermès…"
        val gemini = s.geminiKeys.toList()
        val groq = s.groqKeys.toList()
        val cloudflare = s.cfAccounts.toList().mapNotNull { raw ->
            val id = raw.substringBefore(':').trim()
            val key = raw.substringAfter(':', "").trim()
            if (Regex("[0-9a-fA-F]{32}").matches(id) && key.isNotBlank()) id to key else null
        }
        val external = listOf(
            Triple("zai", "JARVIS_ZAI_API_KEY", "https://api.z.ai/api/paas/v4"),
            Triple("openrouter", "JARVIS_OPENROUTER_API_KEY", "https://openrouter.ai/api/v1"),
            Triple("nvidia", "JARVIS_NVIDIA_API_KEY", "https://integrate.api.nvidia.com/v1"),
            Triple("mistral", "JARVIS_MISTRAL_API_KEY", "https://api.mistral.ai/v1"),
            Triple("huggingface", "JARVIS_HF_TOKEN", "https://router.huggingface.co/v1")
        ).mapNotNull { (id, env, defaultUrl) ->
            val key = s.externalProviderKey(id)
            if (key.isBlank()) null else listOf(id, env, s.externalProviderEndpoint(id).ifBlank { defaultUrl }.trimEnd('/'), s.externalProviderModel(id), key)
        }
        message = "⏳ Clés et fournisseurs IA récupérés. Construction des règles multi-agents…"
        val model = s.groqModel.value.ifBlank { "llama-3.3-70b-versatile" }
        val cloudflareModel = s.cfChatModel.value.ifBlank { "@cf/meta/llama-3.3-70b-instruct-fp8-fast" }
        val envScript = buildString {
            appendLine("H=\"${'$'}HOME/.hermes\"; mkdir -p \"${'$'}H/logs\"")
            appendLine("setenv() { touch \"${'$'}H/.env\"; grep -v \"^${'$'}1=\" \"${'$'}H/.env\" > \"${'$'}H/.env.j\" 2>/dev/null || true; echo \"${'$'}1=${'$'}2\" >> \"${'$'}H/.env.j\"; mv \"${'$'}H/.env.j\" \"${'$'}H/.env\"; }")
            appendLine("removeenvfamily() { grep -v -E \"^${'$'}1(_[0-9]+)?=\" \"${'$'}H/.env\" > \"${'$'}H/.env.j\" 2>/dev/null || true; mv \"${'$'}H/.env.j\" \"${'$'}H/.env\"; }")
            appendLine("removeenvfamily GOOGLE_API_KEY")
            appendLine("removeenvfamily GROQ_API_KEY")
            appendLine("removeenvfamily JARVIS_CF_KEY")
            listOf("JARVIS_ZAI_API_KEY", "JARVIS_OPENROUTER_API_KEY", "JARVIS_NVIDIA_API_KEY", "JARVIS_MISTRAL_API_KEY", "JARVIS_HF_TOKEN").forEach { appendLine("removeenvfamily " + it) }
            gemini.forEachIndexed { index, key -> appendLine("setenv " + (if (index == 0) "GOOGLE_API_KEY" else "GOOGLE_API_KEY_" + (index + 1)) + " " + q(key)) }
            groq.forEachIndexed { index, key -> appendLine("setenv " + (if (index == 0) "GROQ_API_KEY" else "GROQ_API_KEY_" + (index + 1)) + " " + q(key)) }
            cloudflare.forEachIndexed { index, pair -> appendLine("setenv JARVIS_CF_KEY_" + (index + 1) + " " + q(pair.second)) }
            listOf("ZAI", "OPENROUTER", "NVIDIA", "MISTRAL", "HF").forEach { appendLine("setenv JARVIS_HAS_" + it + " 0") }
            external.forEach { entry ->
                val id = entry[0].uppercase()
                appendLine("setenv " + entry[1] + " " + q(entry[4]))
                appendLine("setenv JARVIS_HAS_" + id + " 1")
                appendLine("setenv JARVIS_" + id + "_MODEL " + q(entry[3]))
                appendLine("setenv JARVIS_" + id + "_ENDPOINT " + q(entry[2]))
            }
            appendLine("chmod 600 \"${'$'}H/.env\"")
        }
        val py = """
import os, shutil, tempfile
from pathlib import Path
import yaml
p = Path("/root/.hermes/config.yaml")
if not p.exists():
    raise SystemExit("Configuration Hermes absente.")
data = yaml.safe_load(p.read_text()) or {}
# Force la divulgation progressive à chaque synchronisation : MCP ne doit jamais être envoyé en bloc à Groq.
tools_cfg = data.setdefault("tools", {})
if not isinstance(tools_cfg, dict):
    raise SystemExit("Section tools invalide ; aucune modification.")
search_cfg = tools_cfg.setdefault("tool_search", {})
if isinstance(search_cfg, bool):
    search_cfg = {"enabled": "on"} if search_cfg else {}
    tools_cfg["tool_search"] = search_cfg
if not isinstance(search_cfg, dict):
    raise SystemExit("Section tools.tool_search invalide ; aucune modification.")
search_cfg["enabled"] = "on"
search_cfg["search_default_limit"] = 8
search_cfg["max_search_limit"] = 20
search_cfg["listing"] = "on"
search_cfg["listing_max_tokens"] = 4000
# Le serveur Jarvis doit exposer tous ses outils métier : supprimer d'éventuels filtres include/exclude périmés.
mcp_servers = data.setdefault("mcp_servers", {})
if not isinstance(mcp_servers, dict):
    raise SystemExit("Section mcp_servers invalide ; aucune modification.")
jarvis_mcp = mcp_servers.setdefault("jarvis", {})
if not isinstance(jarvis_mcp, dict):
    raise SystemExit("Configuration MCP Jarvis invalide ; aucune modification.")
jarvis_mcp["enabled"] = True
jarvis_tools = jarvis_mcp.get("tools")
if isinstance(jarvis_tools, dict):
    jarvis_tools.pop("include", None)
    jarvis_tools.pop("exclude", None)
providers = data.setdefault("providers", {})
if not isinstance(providers, dict):
    raise SystemExit("Section providers invalide ; aucune modification.")
model_config = data.get("model")
if isinstance(model_config, dict) and str(model_config.get("provider", "")) == "gemini" and os.environ.get("JARVIS_HAS_GEMINI") == "1":
    model_config["default"] = os.environ["JARVIS_GEMINI_MODEL"]
fallbacks = data.get("fallback_providers") or []
if not isinstance(fallbacks, list):
    raise SystemExit("Section fallback_providers invalide ; aucune modification.")
def is_groq(item):
    if not isinstance(item, dict): return False
    provider = str(item.get("provider", ""))
    base_url = str(item.get("base_url", ""))
    return provider in ("custom:groq", "groq") or provider.startswith("custom:jarvis_cf_") or (provider == "custom" and "groq.com" in base_url)
fallbacks = [item for item in fallbacks if not is_groq(item)]
main_provider = str(model_config.get("provider", "")) if isinstance(model_config, dict) else ""
if os.environ.get("JARVIS_HAS_GEMINI") == "1" and main_provider != "gemini":
    if not any(isinstance(x, dict) and x.get("provider") == "gemini" and x.get("model") == os.environ["JARVIS_GEMINI_MODEL"] for x in fallbacks):
        fallbacks.append({"provider": "gemini", "model": os.environ["JARVIS_GEMINI_MODEL"]})
strategies = data.setdefault("credential_pool_strategies", {})
if not isinstance(strategies, dict):
    raise SystemExit("Section credential_pool_strategies invalide ; aucune modification.")
if os.environ.get("JARVIS_HAS_GEMINI") == "1": strategies["gemini"] = "round_robin"
else: strategies.pop("gemini", None)
if os.environ.get("JARVIS_HAS_GROQ") == "1":
    providers["groq"] = {"api": "https://api.groq.com/openai/v1", "key_env": "GROQ_API_KEY"}
    fallbacks.append({"provider": "custom:groq", "model": os.environ["JARVIS_GROQ_MODEL"]})
    strategies["groq"] = "round_robin"
else:
    providers.pop("groq", None)
    strategies.pop("groq", None)
cf_ids = [x for x in os.environ.get("JARVIS_CF_IDS", "").split(",") if len(x) == 32]
for name in list(providers):
    if str(name).startswith("jarvis_cf_"):
        providers.pop(name, None)
for index, account_id in enumerate(cf_ids, 1):
    providers[f"jarvis_cf_{index}"] = {"api": f"https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1", "key_env": f"JARVIS_CF_KEY_{index}"}
for index in range(1, len(cf_ids) + 1):
    fallbacks.append({"provider": f"custom:jarvis_cf_{index}", "model": os.environ["JARVIS_CF_MODEL"]})
data["fallback_providers"] = fallbacks

# Multi-agent orchestration, preserving all unrelated Hermes configuration.
delegation = data.setdefault("delegation", {})
if not isinstance(delegation, dict):
    raise SystemExit("Section delegation invalide ; aucune modification.")
delegation["max_concurrent_children"] = 3
delegation["max_spawn_depth"] = 2
delegation["orchestrator_enabled"] = True
child_fallbacks = delegation.get("fallback_providers") or []
if not isinstance(child_fallbacks, list): child_fallbacks = []
child_fallbacks = [x for x in child_fallbacks if not is_groq(x)]
if os.environ.get("JARVIS_HAS_GROQ") == "1": child_fallbacks.append({"provider": "custom:groq", "model": os.environ["JARVIS_GROQ_MODEL"]})
if os.environ.get("JARVIS_HAS_GEMINI") == "1" and not any(isinstance(x, dict) and x.get("provider") == "gemini" and x.get("model") == os.environ["JARVIS_GEMINI_MODEL"] for x in child_fallbacks):
    child_fallbacks.append({"provider": "gemini", "model": os.environ["JARVIS_GEMINI_MODEL"]})
for index in range(1, len(cf_ids) + 1): child_fallbacks.append({"provider": f"custom:jarvis_cf_{index}", "model": os.environ["JARVIS_CF_MODEL"]})
for item in fallbacks:
    if isinstance(item, dict):
        provider, model_name = str(item.get("provider", "")), str(item.get("model", ""))
        if provider and model_name and not any(isinstance(x, dict) and x.get("provider") == provider and x.get("model") == model_name for x in child_fallbacks):
            child_fallbacks.append({"provider": provider, "model": model_name})
if child_fallbacks: delegation["fallback_providers"] = child_fallbacks
backends = delegation.setdefault("backends", {})
if not isinstance(backends, dict): backends = {}; delegation["backends"] = backends
model_map = backends.setdefault("model_map", {})
if not isinstance(model_map, dict): model_map = {}; backends["model_map"] = model_map
options = model_map.get("options") or []
if not isinstance(options, list): options = []
options = [x for x in options if not (isinstance(x, dict) and str(x.get("name", "")).startswith("jarvis-"))]
def add_route(name, description, provider, model_name):
    if provider and model_name and not any(isinstance(x, dict) and x.get("provider") == provider and x.get("model") == model_name for x in options):
        options.append({"name": name, "description": description, "provider": provider, "model": model_name})
if os.environ.get("JARVIS_HAS_GEMINI") == "1": add_route("jarvis-gemini", "Analyse approfondie et raisonnement via Gemini", "gemini", os.environ["JARVIS_GEMINI_MODEL"])
if os.environ.get("JARVIS_HAS_GROQ") == "1": add_route("jarvis-groq", "Agent rapide et technique via Groq", "custom:groq", os.environ["JARVIS_GROQ_MODEL"])
for index in range(1, len(cf_ids) + 1): add_route(f"jarvis-cloudflare-{index}", "Agent de secours Cloudflare Workers AI", f"custom:jarvis_cf_{index}", os.environ["JARVIS_CF_MODEL"])
# Expose aussi le modèle principal actif (y compris Codex/OAuth s’il est déjà connecté).
if isinstance(model_config, dict):
    main_model = str(model_config.get("default", ""))
    already_routed = any(isinstance(x, dict) and x.get("provider") == main_provider and x.get("model") == main_model for x in options)
    if main_provider and main_model and main_provider != "custom" and not already_routed:
        add_route("jarvis-main", f"Modèle principal Hermes : {main_provider}", main_provider, main_model)
# Les endpoints personnalisés existants deviennent des routes s’ils déclarent un modèle par défaut.
for provider_name, provider_config in providers.items():
    if provider_name == "groq" or str(provider_name).startswith("jarvis_cf_") or not isinstance(provider_config, dict):
        continue
    provider_model = provider_config.get("default_model")
    models = provider_config.get("models")
    if not provider_model and isinstance(models, list) and models:
        first_model = models[0]
        provider_model = first_model.get("id") or first_model.get("model") if isinstance(first_model, dict) else first_model
    if provider_model:
        safe_name = "".join(ch.lower() if ch.isalnum() else "-" for ch in str(provider_name)).strip("-")[:20] or "provider"
        add_route(f"jarvis-provider-{safe_name}", f"Endpoint personnalisé Hermes : {provider_name}", f"custom:{provider_name}", str(provider_model))
# Fournisseurs API ajoutés depuis Jarvis : les secrets restent dans ~/.hermes/.env.
external_specs = [
    ("zai", "JARVIS_ZAI_API_KEY", "https://api.z.ai/api/paas/v4"),
    ("openrouter", "JARVIS_OPENROUTER_API_KEY", "https://openrouter.ai/api/v1"),
    ("nvidia", "JARVIS_NVIDIA_API_KEY", "https://integrate.api.nvidia.com/v1"),
    ("mistral", "JARVIS_MISTRAL_API_KEY", "https://api.mistral.ai/v1"),
    ("huggingface", "JARVIS_HF_TOKEN", "https://router.huggingface.co/v1"),
]
for provider_id, key_env, default_api in external_specs:
    if os.environ.get("JARVIS_HAS_" + provider_id.upper()) == "1":
        custom_name = "jarvis_" + provider_id
        api_url = os.environ.get("JARVIS_" + provider_id.upper() + "_ENDPOINT", default_api)
        providers[custom_name] = {"api": api_url, "key_env": key_env}
        model_name = os.environ.get("JARVIS_" + provider_id.upper() + "_MODEL", "")
        if model_name:
            route = {"provider": "custom:" + custom_name, "model": model_name}
            if not any(isinstance(x, dict) and x.get("provider") == route["provider"] and x.get("model") == route["model"] for x in fallbacks):
                fallbacks.append(route)
known = {(str(x.get("provider", "")), str(x.get("model", ""))) for x in options if isinstance(x, dict)}
for index, item in enumerate(fallbacks, 1):
    if isinstance(item, dict):
        provider, model_name = str(item.get("provider", "")), str(item.get("model", ""))
        if provider and model_name and (provider, model_name) not in known:
            add_route(f"jarvis-configured-{index}", f"Fournisseur déjà configuré : {provider}", provider, model_name)
            known.add((provider, model_name))
aliases = data.get("model_aliases")
if isinstance(aliases, dict):
    for alias, item in aliases.items():
        if isinstance(item, dict):
            provider, model_name = str(item.get("provider", "")), str(item.get("model", ""))
            if provider and model_name and (provider, model_name) not in known:
                safe = "".join(ch.lower() if ch.isalnum() else "-" for ch in str(alias)).strip("-")[:20] or "modele"
                add_route(f"jarvis-alias-{safe}", f"Alias Hermes existant : {alias}", provider, model_name)
                known.add((provider, model_name))
model_map["options"] = options
if os.environ.get("JARVIS_HAS_GEMINI") == "1": model_map["default"] = "jarvis-gemini"
elif os.environ.get("JARVIS_HAS_GROQ") == "1": model_map["default"] = "jarvis-groq"
elif cf_ids: model_map["default"] = "jarvis-cloudflare-1"
if os.environ.get("JARVIS_ADVANCED_DELEGATION") == "1" and delegation.get("backend", "default") in ("default", "model_map"):
    delegation["backend"] = "model_map"
elif delegation.get("backend") == "model_map":
    delegation["backend"] = "default"
backup = p.with_name("config.yaml.before-jarvis-key-sync")
if not backup.exists(): shutil.copy2(p, backup)
fd, name = tempfile.mkstemp(prefix="config.yaml.", dir=str(p.parent))
with os.fdopen(fd, "w") as stream: yaml.safe_dump(data, stream, sort_keys=False, allow_unicode=True)
os.chmod(name, 0o600)
os.replace(name, p)
""".trimIndent()
        message = "⏳ Règles multi-agents préparées. Assemblage des commandes de synchronisation…"
        val hasGemini = if (gemini.isNotEmpty()) "1" else "0"
        val hasGroq = if (groq.isNotEmpty()) "1" else "0"
        val cfIds = cloudflare.joinToString(",") { it.first }
        val pluginCommand = "PLUGIN_OK=0; proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin hermes plugins install advanced-delegation --no-deps --no-enable >/dev/null 2>&1 || true; if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin hermes plugins enable advanced-delegation --no-allow-tool-override >/dev/null 2>&1; then PLUGIN_OK=1; p \"🧠 Routage multi-modèles advanced-delegation activé.\"; else p \"⚠️ Extension avancée indisponible : délégation Hermes native conservée.\"; fi"
        val extEnv = external.joinToString(" ") { entry ->
            val id = entry[0].uppercase()
            "JARVIS_HAS_$id=1 JARVIS_${id}_MODEL=${q(entry[3])} JARVIS_${id}_ENDPOINT=${q(entry[2])}"
        }
        val configCommand = "proot-distro login debian --termux-home -- env " + extEnv + " JARVIS_HAS_GEMINI=" + hasGemini + " JARVIS_HAS_GROQ=" + hasGroq + " JARVIS_GEMINI_MODEL=" + q(app.store.hermesModele.value.ifBlank { app.store.geminiModel.value }.ifBlank { "gemini-2.5-flash" }) + " JARVIS_GROQ_MODEL=" + q(model) + " JARVIS_CF_MODEL=" + q(cloudflareModel) + " JARVIS_CF_IDS=" + q(cfIds) + " JARVIS_ADVANCED_DELEGATION=\"\$PLUGIN_OK\" python3 -c " + q(py) + " || exit 1"
        // L'installation Jarvis est une installation source dans Debian : utiliser son exécutable direct.
        // bash -lc réinitialisait le PATH et pouvait lancer un autre Hermes, d'où l'échec silencieux précédent.
        // En cas d'échec, arrêter la configuration : ne jamais annoncer une nouvelle version qui n'a pas été installée.
        // L'installation est stockée dans le HOME Termux partagé. PRoot démarre par défaut
        // avec l'identité virtuelle root (UID 0), alors que les fichiers appartiennent à l'UID
        // Android de Termux (ex. 10386). Hermes refuse à juste titre de mettre à jour un venv
        // appartenant à un autre UID. On relance donc Debian avec --user UID:GID, sans chown.
        val upgradeScript = listOf(
            "set -eu",
            "REPO=/root/.hermes/hermes-agent",
            "test -x \"\$REPO/.hermes/bin/hermes\"",
            "test -d /root/.hermes/installs || { echo 'Répertoire des environnements Hermès introuvable.'; exit 2; }",
            "cd \"\$REPO\"",
            "exec ./.hermes/bin/hermes update --yes"
        ).joinToString("\n")
        val upgradeCommand = "set -o pipefail; p '🔄 Vérification du propriétaire de l’installation Hermès…'; " +
            "OWNER_IDS=\$(proot-distro login debian --termux-home -- stat -c '%u:%g' /root/.hermes/hermes-agent/.hermes/bin/hermes 2>/dev/null) || exit 1; " +
            "case \"\$OWNER_IDS\" in *[!0-9:]*|'') p '❌ Impossible d’identifier le propriétaire de l’installation Hermès.'; exit 126;; esac; " +
            "p \"🔐 Mise à niveau sous l’identité propriétaire UID:GID \$OWNER_IDS (sans changement de droits)…\"; " +
            "proot-distro login debian --user \"\$OWNER_IDS\" --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD HOME=/root USER=jarvis LOGNAME=jarvis " +
            "PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
            "bash -c " + q(upgradeScript) + " 2>&1 | while IFS= read -r ligne; do p \"\$ligne\"; done; " +
            "UPDATE_STATUS=\$?; if [ \"\$UPDATE_STATUS\" -ne 0 ]; then " +
            "p '❌ Mise à niveau Hermès échouée : consulte les lignes de progression ci-dessus. La configuration est interrompue.'; exit \"\$UPDATE_STATUS\"; fi; " +
            "p '✅ Mise à niveau Hermès terminée.'"
        val restartCommand = "proot-distro login debian --termux-home -- pkill -f '[h]ermes gateway run' >/dev/null 2>&1 || true; " +
            "pkill -f '[h]ermes gateway run' >/dev/null 2>&1 || true; sleep 3"
        message = "⏳ Commandes de synchronisation prêtes. Assemblage du script final…"
        val script = listOf(
            prelude(app),
            "curl -fsS -m 4 -X POST -H \"Authorization: Bearer ${'$'}J_TOK\" --data-binary pong \"${'$'}J_URL\" >/dev/null || { rc=${'$'}?; p \"❌ Contrôle du pont Jarvis impossible (curl ${'$'}rc) vers ${'$'}J_URL\"; exit \"${'$'}rc\"; }",
            "p '🚀 Configuration Hermès : suivi des étapes confirmé.'",
            upgradeCommand,
            "p '🔐 Mise à jour des clés et variables des fournisseurs…'",
            envScript,
            "p '🧩 Synchronisation des plugins et agents…'",
            pluginCommand,
            "p '⚙️ Écriture et validation de la configuration Hermès…'",
            configCommand,
            "p '🔄 Redémarrage de la passerelle Hermès…'",
            restartCommand,
            demarrage
        ).joinToString(System.lineSeparator())
        message = "⏳ Script Hermès construit (${script.length} caractères). Transmission à Termux…"
        val signalAvantEnvoi = PontMcp.dernierSignal
        dernierSignalAvantCommande = signalAvantEnvoi
        val erreur = executer(app, script, libelle = "Mise à niveau et orchestration multi-agents Hermès", retourNatif = true)
        if (erreur != null) {
            message = "❌ Termux a refusé le lancement : $erreur. Aucune mise à niveau n'a été confirmée."
            return message
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (PontMcp.dernierSignal <= signalAvantEnvoi &&
                !message.startsWith("❌") && !message.startsWith("✅ Hermès est en ligne")) {
                message = "❌ Termux n'a pas confirmé l'exécution du script après 35 secondes. Vérifie l'autorisation « Exécuter des commandes dans Termux », les restrictions d'arrière-plan et le port 38291. Aucun succès n'est déclaré."
            }
        }, 35_000)
        return "⏳ Commande transmise à Termux ; attente de son accusé de réception réel."
    }

    /** Catalogue et installation non interactive des plugins/MCP Hermes. */
    fun gererExtension(app: JarvisApp, actionRaw: String, nameRaw: String = ""): String {
        val action = actionRaw.trim().lowercase()
        val name = nameRaw.trim()
        val allowed = setOf("skill_catalogue", "skill_rechercher", "skill_liste", "skill_inspecter", "skill_installer",
            "skill_verifier", "skill_mettre_a_jour", "plugin_catalogue", "plugin_liste", "plugin_installer", "plugin_activer",
            "mcp_catalogue", "mcp_liste", "mcp_installer")
        if (action !in allowed) return "Action d'extension inconnue."
        if (action.endsWith("_installer") || action.endsWith("_activer") || action == "skill_inspecter") {
            if (!Regex("[a-zA-Z0-9][a-zA-Z0-9._/-]{0,100}").matches(name) || name.contains(".."))
                return "Nom d'extension invalide."
        }
        val args = when (action) {
            "skill_catalogue" -> listOf("skills", "browse")
            "skill_rechercher" -> listOf("skills", "search", name)
            "skill_liste" -> listOf("skills", "list")
            "skill_inspecter" -> listOf("skills", "inspect", name)
            "skill_installer" -> listOf("skills", "install", name, "--yes")
            "skill_verifier" -> listOf("skills", "check")
            "skill_mettre_a_jour" -> listOf("skills", "update")
            "plugin_catalogue" -> listOf("plugins", "catalog")
            "plugin_liste" -> listOf("plugins", "list")
            "plugin_installer" -> listOf("plugins", "install", name, "--no-enable")
            "plugin_activer" -> listOf("plugins", "enable", name)
            "mcp_catalogue" -> listOf("mcp", "catalog")
            "mcp_liste" -> listOf("mcp", "list")
            "mcp_installer" -> listOf("mcp", "install", name)
            else -> return "Action non prise en charge."
        }
        return lancerExtension(app, args, "Catalogue / extensions Hermès")
    }

    fun demarrer(app: JarvisApp): String {
        PontMcp.demarrer(app)
        return executer(app, prelude(app) + "\n" + demarrage, libelle = "Démarrage d'Hermès") ?: "▶️ Démarrage d'Hermès…"
    }

    fun arreter(app: JarvisApp): String =
        executer(app, prelude(app) + "\npkill -f '[h]ermes gateway run' >/dev/null 2>&1 || true; termux-wake-unlock >/dev/null 2>&1; p '⏹️ Hermès arrêté.'", libelle = "Arrêt d'Hermès") ?: "⏹️ Arrêt d'Hermès…"

    /**
     * Exécute une commande CLI Hermes dans Debian sans toucher à l'installation ni à config.yaml.
     * Les arguments sont quotés séparément pour éviter toute interprétation shell des saisies utilisateur.
     */
    fun lancerExtension(app: JarvisApp, arguments: List<String>, libelle: String = "Extensions Hermès"): String {
        if (arguments.isEmpty()) return "❌ Commande Hermès vide."
        PontMcp.demarrer(app)
        val cli = arguments.joinToString(" ") { q(it) }
        val commandeDebian = "cd /root/.hermes/hermes-agent && test -x ./.hermes/bin/hermes && ./.hermes/bin/hermes $cli"
        val commandeTermux = "set -o pipefail; proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc ${q(commandeDebian)} 2>&1 | while IFS= read -r ligne; do p \"\$ligne\"; done"
        message = "⏳ Commande Hermès : ${arguments.take(3).joinToString(" ")}"
        return executer(app, prelude(app) + "\n" + commandeTermux, libelle = libelle)
            ?: "🚀 Commande envoyée à Hermès."
    }
    // ------------------------------------------------------------------ Sauvegarde et récupération autonome
    private fun executerDebian(app: JarvisApp, scriptDebian: String, libelle: String): String {
        PontMcp.demarrer(app)
        val commande = "set -o pipefail; proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD " +
            "PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
            "bash -lc ${q(scriptDebian)} 2>&1 | while IFS= read -r ligne; do p \"\$ligne\"; done"
        message = "⏳ $libelle…"
        return executer(app, prelude(app) + "\n" + commande, libelle = libelle)
            ?: "🚀 $libelle lancé : suis la progression dans Jarvis."
    }

    /** Archive privée de ~/.hermes, en excluant caches, dépôts Git et environnements Python recréables. */
    private val scriptSauvegarde = """
set -eu
test -d /root/.hermes || { echo "❌ Dossier Hermes introuvable dans Debian."; exit 1; }
mkdir -p /root/.hermes/backups
tar -czf "/root/.hermes/backups/hermes-$(date +%Y%m%d-%H%M%S).tar.gz" \
  --exclude='.hermes/backups' --exclude='.hermes/hermes-agent/.git' \
  --exclude='.hermes/hermes-agent/venv' --exclude='.hermes/hermes-agent/.venv' \
  --exclude='.hermes/hermes-agent/__pycache__' -C /root .hermes
chmod 600 /root/.hermes/backups/hermes-*.tar.gz
echo "✅ Sauvegarde privée créée dans /root/.hermes/backups."
du -h /root/.hermes/backups/hermes-*.tar.gz | tail -n 1
""".trimIndent()

    fun sauvegarder(app: JarvisApp): String =
        executerDebian(app, scriptSauvegarde, "Sauvegarde Hermès")

    /** Contrôle les fichiers essentiels sans afficher les clés API ni les jetons. */
    fun diagnostiquer(app: JarvisApp): String {
        val script = """
set +e
echo "=== DIAGNOSTIC HERMÈS (aucun secret affiché) ==="
[ -x /root/.hermes/hermes-agent/.hermes/bin/hermes ] && echo "CLI Hermes : OK" || echo "CLI Hermes : ABSENT"
if [ -x /root/.hermes/hermes-agent/venv/bin/python ]; then
  echo "Environnement Python Hermès : venv/ OK"
elif [ -x /root/.hermes/hermes-agent/.venv/bin/python ]; then
  echo "Environnement Python Hermès : .venv/ OK"
elif [ -x /root/.hermes/hermes-agent/.hermes/bin/python ]; then
  echo "Environnement Python Hermès : .hermes/bin/ OK"
else
  echo "Environnement Python dédié : non trouvé"
fi
if command -v python3 >/dev/null 2>&1; then
  echo "Python Debian : $(python3 --version 2>&1)"
  echo "Interpréteur système : $(command -v python3)"
else
  echo "Python Debian : ABSENT"
fi
if command -v hermes >/dev/null 2>&1; then
  echo "CLI Hermès : $(command -v hermes)"
  hermes --version 2>&1 | head -n 1
fi
[ -f /root/.hermes/config.yaml ] && echo "Configuration : présente" || echo "Configuration : ABSENTE"
[ -f /root/.hermes/.env ] && echo "Fichier de clés : présent (contenu masqué)" || echo "Fichier de clés : ABSENT"
grep -q 'jarvis:' /root/.hermes/config.yaml 2>/dev/null && echo "Entrée MCP Jarvis : présente" || echo "Entrée MCP Jarvis : absente"
grep -q '127.0.0.1:38291/mcp' /root/.hermes/config.yaml 2>/dev/null && echo "URL du pont MCP : OK" || echo "URL du pont MCP : à vérifier"
curl -fsS -m 3 http://127.0.0.1:${PORT_API}/health >/dev/null 2>&1 && echo "Passerelle API : EN LIGNE" || echo "Passerelle API : hors ligne"
echo "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        return executerDebian(app, script, "Diagnostic Hermès")
    }

    /** Restaure la dernière archive après vérification, puis relance la passerelle. */
    fun restaurerDerniereSauvegarde(app: JarvisApp): String {
        PontMcp.demarrer(app)
        val restore = """
set -eu
find /root/.hermes/backups -maxdepth 1 -type f -name 'hermes-*.tar.gz' | grep -q . || { echo "❌ Aucune sauvegarde Hermès trouvée."; exit 1; }
tar -tzf "$(find /root/.hermes/backups -maxdepth 1 -type f -name 'hermes-*.tar.gz' | sort | tail -n 1)" >/dev/null
tar -xzf "$(find /root/.hermes/backups -maxdepth 1 -type f -name 'hermes-*.tar.gz' | sort | tail -n 1)" -C /root
chmod 600 /root/.hermes/.env 2>/dev/null || true
echo "✅ Données et configuration restaurées."
""".trimIndent()
        val commande = "set -o pipefail; proot-distro login debian --termux-home -- bash -lc ${q(restore)} 2>&1 | while IFS= read -r ligne; do p \"\$ligne\"; done"
        return executer(app, prelude(app) + "\n" + commande + "\n" + demarrage, libelle = "Restauration Hermès")
            ?: "🚀 Restauration lancée, puis redémarrage d'Hermès."
    }

    /** Réinstallation propre : sauvegarde complète, suppression du seul moteur, puis installateur officiel. */
    fun reinstallerComplet(app: JarvisApp): String {
        PontMcp.demarrer(app)
        app.store.hermesInstalle.set(false)
        val preparation = scriptSauvegarde + "\nrm -rf /root/.hermes/hermes-agent\necho '🧹 Ancien moteur supprimé après sauvegarde.'"
        val commande = "set -euo pipefail; proot-distro login debian --termux-home -- bash -lc ${q(preparation)} 2>&1 | while IFS= read -r ligne; do p \"\$ligne\"; done"
        val script = prelude(app) + "\n" + commande + "\n" + installation + "\n" + configuration(app) + "\n" + demarrage
        message = "⏳ Sauvegarde puis réinstallation complète d'Hermès…"
        return executer(app, script, libelle = "Réinstallation complète Hermès")
            ?: "🚀 Sauvegarde effectuée puis réinstallation lancée. Suis les étapes dans Jarvis."
    }
    /** Appelé par le pont local à chaque ligne de progression du script. */
    fun surProgression(app: JarvisApp, ligne: String) {
        if (ligne == "pong") return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            message = ligne
            if (ligne.startsWith("✅ Hermès est en ligne")) { app.store.hermesInstalle.set(true); Hermes.marquerEnLigne(true) }
        }
        val important = ligne.startsWith("✅ Hermès") || ligne.startsWith("❌") || ligne.startsWith("⛔") || ligne.startsWith("🚀")
        if (important) runCatching {
            NotificationManagerCompat.from(app).notify(NOTIF_ID, NotificationCompat.Builder(app, JarvisApp.CHANNEL_AGENTS)
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Hermès")
                .setContentText(ligne).setStyle(NotificationCompat.BigTextStyle().bigText(ligne)).setAutoCancel(true).build())
        }
    }
}