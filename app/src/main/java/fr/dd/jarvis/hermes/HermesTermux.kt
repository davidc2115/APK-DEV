package fr.dd.jarvis.hermes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
    fun executer(ctx: Context, script: String, arrierePlan: Boolean = true, libelle: String = "Jarvis"): String? {
        if (!termuxInstalle(ctx)) return "Termux n'est pas installé."
        if (!permissionOk(ctx)) return "Permission « Exécuter des commandes dans Termux » non accordée à JARVIS."
        val i = Intent("com.termux.RUN_COMMAND").setClassName(TERMUX, "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", arrierePlan)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", libelle)
        return try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            null
        } catch (e: Throwable) { "Termux a refusé la commande : ${e.message ?: e.javaClass.simpleName}" }
    }

    /** Fonction shell `p` : affiche ET envoie une ligne de progression au pont local de Jarvis. */
    private fun prelude(app: JarvisApp) = scriptPrelude(app.store.mcpJeton.value)

    internal fun scriptPrelude(jeton: String, port: Int = PontMcp.PORT) = """
J_URL=${q("http://127.0.0.1:$port/progres")}; J_TOK=${q(jeton)}
p() { echo "${'$'}*"; command -v curl >/dev/null 2>&1 && curl -s -m 4 -X POST -H "Authorization: Bearer ${'$'}J_TOK" --data-binary "${'$'}*" "${'$'}J_URL" >/dev/null 2>&1; true; }
""".trimIndent()

    private fun scriptSignal(app: JarvisApp, texte: String) = prelude(app) + "\np ${q(texte)}\n"

    // ------------------------------------------------------------------ 3-5. Installation, configuration, démarrage

    private fun configuration(app: JarvisApp): String {
        val s = app.store
        if (s.hermesCle.value.isBlank()) s.hermesCle.set(UUID.randomUUID().toString().replace("-", ""))
        if (s.mcpJeton.value.isBlank()) s.mcpJeton.set(UUID.randomUUID().toString().replace("-", ""))
        return scriptConfiguration(
            cleApi = s.hermesCle.value, jeton = s.mcpJeton.value,
            gemini = s.geminiKeys.firstOrNull().orEmpty(), groq = s.groqKeys.firstOrNull().orEmpty(),
            modele = s.hermesModele.value.ifBlank { s.geminiModel.value }.ifBlank { "gemini-2.5-flash" },
            modeleGroq = s.groqModel.value.ifBlank { "openai/gpt-oss-120b" }
        )
    }

    internal fun scriptConfiguration(cleApi: String, jeton: String, gemini: String, groq: String, modele: String, modeleGroq: String, urlMcp: String = PontMcp.url()): String {
        val yaml = buildString {
            appendLine("# Géré par Jarvis (fr.dd.jarvis) : régénéré à chaque « Configurer Hermès » depuis l'application.")
            appendLine("# Ancienne configuration éventuelle : config.yaml.avant-jarvis")
            appendLine("model:")
            appendLine("  provider: gemini")
            appendLine("  default: $modele")
            if (groq.isNotBlank()) {
                appendLine("fallback_providers:")
                appendLine("  - provider: custom")
                appendLine("    model: $modeleGroq")
                appendLine("    base_url: https://api.groq.com/openai/v1")
                appendLine("    key_env: GROQ_API_KEY")
            }
            appendLine("mcp_servers:")
            appendLine("  jarvis:")
            appendLine("    url: \"$urlMcp\"")
            appendLine("    headers:")
            appendLine("      Authorization: \"Bearer $jeton\"")
            appendLine("    timeout: 180")
            appendLine("    connect_timeout: 20")
            appendLine("gateway:")
            appendLine("  api_server:")
            appendLine("    enabled: true")
            appendLine("    port: $PORT_API")
            // Outils de Jarvis exposés directement au modèle (sinon Hermès les cache derrière tool_search / tool_call).
            appendLine("tools:")
            appendLine("  tool_search:")
            appendLine("    enabled: \"off\"")
        }
        return """
H="${'$'}HOME/.hermes"; mkdir -p "${'$'}H/logs"
setenv() { touch "${'$'}H/.env"; grep -v "^${'$'}1=" "${'$'}H/.env" > "${'$'}H/.env.j" 2>/dev/null; printf '%s=%s\n' "${'$'}1" "${'$'}2" >> "${'$'}H/.env.j"; mv "${'$'}H/.env.j" "${'$'}H/.env"; }
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
pkill -f 'hermes gateway' >/dev/null 2>&1; sleep 1
if [ "${'$'}(cat "${'$'}{H}/.jarvis-mode" 2>/dev/null)" = proot ]; then
  CMD="proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin hermes gateway run"
else
  CMD="hermes gateway run"
fi
if command -v setsid >/dev/null 2>&1; then setsid nohup sh -c "${'$'}{CMD}" >> "${'$'}{H}/logs/gateway.log" 2>&1 < /dev/null &
else nohup sh -c "${'$'}{CMD}" >> "${'$'}{H}/logs/gateway.log" 2>&1 < /dev/null & fi
for i in ${'$'}(seq 1 60); do
  if curl -s -m 2 http://127.0.0.1:$PORT_API/health >/dev/null 2>&1; then p "✅ Hermès est en ligne."; exit 0; fi
  sleep 2
done
p "❌ Hermès ne répond pas. Dernières lignes du journal :"
tail -n 6 "${'$'}{H}/logs/gateway.log" 2>/dev/null | while IFS= read -r l; do p "   ${'$'}{l}"; done
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
  if ! proot-distro login debian --termux-home -- env -i HOME=/root PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/bin:/usr/bin:/bin test -x /root/.hermes/hermes-agent/venv/bin/python; then
    p "❌ Installation incomplète : /root/.hermes/hermes-agent/venv/bin/python est absent. La configuration et le démarrage sont interrompus."
    tail -n 15 "${'$'}{INSTALL_LOG}" | sed 's/\\x1b\\[[0-9;]*m//g' | while IFS= read -r l; do p "   $(printf '%s' "${'$'}{l}" | cut -c1-120)"; done
    exit 1
  fi
  if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/bin:/usr/bin:/bin hermes --version >/dev/null 2>&1; then
    MODE=proot
  else
    p "❌ Hermès n'a pas pu être installé. Dernières lignes :"
    tail -n 5 "${'$'}{LOG}" | sed 's/\x1b\[[0-9;]*m//g' | while IFS= read -r l; do p "   ${'$'}(printf '%s' "${'$'}{l}" | cut -c1-110)"; done
    exit 1
  fi
fi
echo "${'$'}{MODE}" > "${'$'}{H}/.jarvis-mode"
p "✅ Hermès installé (${'$'}{MODE})."
""".trimIndent()

    /** Lance l'installation complète (arrière-plan dans Termux), puis la configuration et le démarrage. */
    fun installer(app: JarvisApp): String {
        PontMcp.demarrer(app)
        app.store.hermesInstalle.set(false)
        val script = prelude(app) + "\n" + installation + "\n" + configuration(app) + "\n" + demarrage
        message = "⏳ Installation envoyée à Termux…"
        return executer(app, script, libelle = "Installation d'Hermès") ?: "🚀 Installation lancée dans Termux : suis la progression ici (5 à 20 min)."
    }

    /** Sauvegarde locale privée de l'environnement Hermes ; aucune donnée n'est envoyée hors du téléphone. */
    fun sauvegarder(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="${'OME/.hermes"
[ -d "$H" ] || { p "❌ Dossier ~/.hermes introuvable."; exit 1; }
B="$H/backups"; mkdir -p "$B"; chmod 700 "$B"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$B/jarvis-hermes-$TS.tar.gz"
tar -czf "$OUT" --exclude='./.hermes/backups' --exclude='*/.git' --exclude='*/venv' --exclude='*/__pycache__' --exclude='*/.cache' -C "$HOME" .hermes
chmod 600 "$OUT"
p "✅ Sauvegarde privée créée : $(basename "$OUT") ($(du -h "$OUT" | cut -f1))."
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Sauvegarde Hermes") ?: "💾 Sauvegarde lancée."
    }

    /** Diagnostic en lecture seule ; les valeurs secrètes du fichier .env ne sont jamais affichées. */
    fun diagnostiquer(app: JarvisApp): String {
        val script = prelude(app) + """
set +e
H="$HOME/.hermes"
p "=== DIAGNOSTIC HERMES ==="
[ -d "$H" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "$H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}HOME/.hermes"
[ -d "${'" ] || { p "❌ Dossier ~/.hermes introuvable."; exit 1; }
B="$H/backups"; mkdir -p "$B"; chmod 700 "$B"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$B/jarvis-hermes-$TS.tar.gz"
tar -czf "$OUT" --exclude='./.hermes/backups' --exclude='*/.git' --exclude='*/venv' --exclude='*/__pycache__' --exclude='*/.cache' -C "$HOME" .hermes
chmod 600 "$OUT"
p "✅ Sauvegarde privée créée : $(basename "$OUT") ($(du -h "$OUT" | cut -f1))."
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Sauvegarde Hermes") ?: "💾 Sauvegarde lancée."
    }

    /** Diagnostic en lecture seule ; les valeurs secrètes du fichier .env ne sont jamais affichées. */
    fun diagnostiquer(app: JarvisApp): String {
        val script = prelude(app) + """
set +e
H="$HOME/.hermes"
p "=== DIAGNOSTIC HERMES ==="
[ -d "$H" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "$H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}H" ] || { p "❌ Dossier ~/.hermes introuvable."; exit 1; }
B="${'/backups"; mkdir -p "$B"; chmod 700 "$B"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$B/jarvis-hermes-$TS.tar.gz"
tar -czf "$OUT" --exclude='./.hermes/backups' --exclude='*/.git' --exclude='*/venv' --exclude='*/__pycache__' --exclude='*/.cache' -C "$HOME" .hermes
chmod 600 "$OUT"
p "✅ Sauvegarde privée créée : $(basename "$OUT") ($(du -h "$OUT" | cut -f1))."
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Sauvegarde Hermes") ?: "💾 Sauvegarde lancée."
    }

    /** Diagnostic en lecture seule ; les valeurs secrètes du fichier .env ne sont jamais affichées. */
    fun diagnostiquer(app: JarvisApp): String {
        val script = prelude(app) + """
set +e
H="$HOME/.hermes"
p "=== DIAGNOSTIC HERMES ==="
[ -d "$H" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "$H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}H/backups"; mkdir -p "${'"; chmod 700 "$B"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$B/jarvis-hermes-$TS.tar.gz"
tar -czf "$OUT" --exclude='./.hermes/backups' --exclude='*/.git' --exclude='*/venv' --exclude='*/__pycache__' --exclude='*/.cache' -C "$HOME" .hermes
chmod 600 "$OUT"
p "✅ Sauvegarde privée créée : $(basename "$OUT") ($(du -h "$OUT" | cut -f1))."
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Sauvegarde Hermes") ?: "💾 Sauvegarde lancée."
    }

    /** Diagnostic en lecture seule ; les valeurs secrètes du fichier .env ne sont jamais affichées. */
    fun diagnostiquer(app: JarvisApp): String {
        val script = prelude(app) + """
set +e
H="$HOME/.hermes"
p "=== DIAGNOSTIC HERMES ==="
[ -d "$H" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "$H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}B"; chmod 700 "${'"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$B/jarvis-hermes-$TS.tar.gz"
tar -czf "$OUT" --exclude='./.hermes/backups' --exclude='*/.git' --exclude='*/venv' --exclude='*/__pycache__' --exclude='*/.cache' -C "$HOME" .hermes
chmod 600 "$OUT"
p "✅ Sauvegarde privée créée : $(basename "$OUT") ($(du -h "$OUT" | cut -f1))."
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Sauvegarde Hermes") ?: "💾 Sauvegarde lancée."
    }

    /** Diagnostic en lecture seule ; les valeurs secrètes du fichier .env ne sont jamais affichées. */
    fun diagnostiquer(app: JarvisApp): String {
        val script = prelude(app) + """
set +e
H="$HOME/.hermes"
p "=== DIAGNOSTIC HERMES ==="
[ -d "$H" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "$H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}B"
TS="${'date +%Y%m%d-%H%M%S)"
OUT="$B/jarvis-hermes-$TS.tar.gz"
tar -czf "$OUT" --exclude='./.hermes/backups' --exclude='*/.git' --exclude='*/venv' --exclude='*/__pycache__' --exclude='*/.cache' -C "$HOME" .hermes
chmod 600 "$OUT"
p "✅ Sauvegarde privée créée : $(basename "$OUT") ($(du -h "$OUT" | cut -f1))."
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Sauvegarde Hermes") ?: "💾 Sauvegarde lancée."
    }

    /** Diagnostic en lecture seule ; les valeurs secrètes du fichier .env ne sont jamais affichées. */
    fun diagnostiquer(app: JarvisApp): String {
        val script = prelude(app) + """
set +e
H="$HOME/.hermes"
p "=== DIAGNOSTIC HERMES ==="
[ -d "$H" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "$H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}(date +%Y%m%d-%H%M%S)"
OUT="${'/jarvis-hermes-$TS.tar.gz"
tar -czf "$OUT" --exclude='./.hermes/backups' --exclude='*/.git' --exclude='*/venv' --exclude='*/__pycache__' --exclude='*/.cache' -C "$HOME" .hermes
chmod 600 "$OUT"
p "✅ Sauvegarde privée créée : $(basename "$OUT") ($(du -h "$OUT" | cut -f1))."
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Sauvegarde Hermes") ?: "💾 Sauvegarde lancée."
    }

    /** Diagnostic en lecture seule ; les valeurs secrètes du fichier .env ne sont jamais affichées. */
    fun diagnostiquer(app: JarvisApp): String {
        val script = prelude(app) + """
set +e
H="$HOME/.hermes"
p "=== DIAGNOSTIC HERMES ==="
[ -d "$H" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "$H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}B/jarvis-hermes-${'S.tar.gz"
tar -czf "$OUT" --exclude='./.hermes/backups' --exclude='*/.git' --exclude='*/venv' --exclude='*/__pycache__' --exclude='*/.cache' -C "$HOME" .hermes
chmod 600 "$OUT"
p "✅ Sauvegarde privée créée : $(basename "$OUT") ($(du -h "$OUT" | cut -f1))."
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Sauvegarde Hermes") ?: "💾 Sauvegarde lancée."
    }

    /** Diagnostic en lecture seule ; les valeurs secrètes du fichier .env ne sont jamais affichées. */
    fun diagnostiquer(app: JarvisApp): String {
        val script = prelude(app) + """
set +e
H="$HOME/.hermes"
p "=== DIAGNOSTIC HERMES ==="
[ -d "$H" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "$H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}TS.tar.gz"
tar -czf "${'UT" --exclude='./.hermes/backups' --exclude='*/.git' --exclude='*/venv' --exclude='*/__pycache__' --exclude='*/.cache' -C "$HOME" .hermes
chmod 600 "$OUT"
p "✅ Sauvegarde privée créée : $(basename "$OUT") ($(du -h "$OUT" | cut -f1))."
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Sauvegarde Hermes") ?: "💾 Sauvegarde lancée."
    }

    /** Diagnostic en lecture seule ; les valeurs secrètes du fichier .env ne sont jamais affichées. */
    fun diagnostiquer(app: JarvisApp): String {
        val script = prelude(app) + """
set +e
H="$HOME/.hermes"
p "=== DIAGNOSTIC HERMES ==="
[ -d "$H" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "$H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}OUT" --exclude='./.hermes/backups' --exclude='*/.git' --exclude='*/venv' --exclude='*/__pycache__' --exclude='*/.cache' -C "${'OME" .hermes
chmod 600 "$OUT"
p "✅ Sauvegarde privée créée : $(basename "$OUT") ($(du -h "$OUT" | cut -f1))."
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Sauvegarde Hermes") ?: "💾 Sauvegarde lancée."
    }

    /** Diagnostic en lecture seule ; les valeurs secrètes du fichier .env ne sont jamais affichées. */
    fun diagnostiquer(app: JarvisApp): String {
        val script = prelude(app) + """
set +e
H="$HOME/.hermes"
p "=== DIAGNOSTIC HERMES ==="
[ -d "$H" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "$H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}HOME" .hermes
chmod 600 "${'UT"
p "✅ Sauvegarde privée créée : $(basename "$OUT") ($(du -h "$OUT" | cut -f1))."
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Sauvegarde Hermes") ?: "💾 Sauvegarde lancée."
    }

    /** Diagnostic en lecture seule ; les valeurs secrètes du fichier .env ne sont jamais affichées. */
    fun diagnostiquer(app: JarvisApp): String {
        val script = prelude(app) + """
set +e
H="$HOME/.hermes"
p "=== DIAGNOSTIC HERMES ==="
[ -d "$H" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "$H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}OUT"
p "✅ Sauvegarde privée créée : ${'basename "$OUT") ($(du -h "$OUT" | cut -f1))."
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Sauvegarde Hermes") ?: "💾 Sauvegarde lancée."
    }

    /** Diagnostic en lecture seule ; les valeurs secrètes du fichier .env ne sont jamais affichées. */
    fun diagnostiquer(app: JarvisApp): String {
        val script = prelude(app) + """
set +e
H="$HOME/.hermes"
p "=== DIAGNOSTIC HERMES ==="
[ -d "$H" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "$H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}(basename "${'UT") ($(du -h "$OUT" | cut -f1))."
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Sauvegarde Hermes") ?: "💾 Sauvegarde lancée."
    }

    /** Diagnostic en lecture seule ; les valeurs secrètes du fichier .env ne sont jamais affichées. */
    fun diagnostiquer(app: JarvisApp): String {
        val script = prelude(app) + """
set +e
H="$HOME/.hermes"
p "=== DIAGNOSTIC HERMES ==="
[ -d "$H" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "$H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}OUT") (${'du -h "$OUT" | cut -f1))."
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Sauvegarde Hermes") ?: "💾 Sauvegarde lancée."
    }

    /** Diagnostic en lecture seule ; les valeurs secrètes du fichier .env ne sont jamais affichées. */
    fun diagnostiquer(app: JarvisApp): String {
        val script = prelude(app) + """
set +e
H="$HOME/.hermes"
p "=== DIAGNOSTIC HERMES ==="
[ -d "$H" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "$H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}(du -h "${'UT" | cut -f1))."
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Sauvegarde Hermes") ?: "💾 Sauvegarde lancée."
    }

    /** Diagnostic en lecture seule ; les valeurs secrètes du fichier .env ne sont jamais affichées. */
    fun diagnostiquer(app: JarvisApp): String {
        val script = prelude(app) + """
set +e
H="$HOME/.hermes"
p "=== DIAGNOSTIC HERMES ==="
[ -d "$H" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "$H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}OUT" | cut -f1))."
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Sauvegarde Hermes") ?: "💾 Sauvegarde lancée."
    }

    /** Diagnostic en lecture seule ; les valeurs secrètes du fichier .env ne sont jamais affichées. */
    fun diagnostiquer(app: JarvisApp): String {
        val script = prelude(app) + """
set +e
H="${'OME/.hermes"
p "=== DIAGNOSTIC HERMES ==="
[ -d "$H" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "$H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}HOME/.hermes"
p "=== DIAGNOSTIC HERMES ==="
[ -d "${'" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "$H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}H" ] && p "OK : dossier Hermes présent" || p "ERREUR : dossier Hermes absent"
[ -s "${'/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "$H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}H/config.yaml" ] && p "OK : config.yaml présent" || p "ERREUR : config.yaml absent"
[ -s "${'/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "$H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}H/.env" ] && p "OK : .env présent (valeurs masquées)" || p "ERREUR : .env absent"
if proot-distro login debian --termux-home -- env -u TERMUX_VERSION -u PREFIX -u LD_PRELOAD PATH=/root/.local/bin:/root/.hermes/hermes-agent/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc 'hermes --version && test -x /root/.hermes/hermes-agent/venv/bin/python'; then
  p "OK : Hermes et environnement Python accessibles"
else
  p "ERREUR : Hermes ou Python inaccessible dans Debian"
fi
grep -q 'jarvis:' "${'/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "$H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}H/config.yaml" 2>/dev/null && p "OK : entrée MCP Jarvis présente" || p "ERREUR : entrée MCP Jarvis absente"
grep -q '127.0.0.1:38291/mcp' "${'/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "$H/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}H/config.yaml" 2>/dev/null && p "OK : adresse du pont Jarvis détectée" || p "ATTENTION : adresse MCP différente ou absente"
[ -d "${'/backups" ] && find "$H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}H/backups" ] && find "${'/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "$f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}H/backups" -maxdepth 1 -type f -name 'jarvis-hermes-*.tar.gz' -printf 'SAUVEGARDE : %f\\n' | while IFS= read -r f; do p "${'"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="$HOME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}f"; done
p "=== FIN DU DIAGNOSTIC ==="
""".trimIndent()
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Diagnostic Hermes") ?: "🔎 Diagnostic lancé."
    }

    /** Restaure la dernière archive locale validée, en fusionnant les fichiers sans supprimer l'installation courante. */
    fun restaurer(app: JarvisApp): String {
        val script = prelude(app) + """
set -eu
H="${'OME/.hermes"; B="$H/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}HOME/.hermes"; B="${'/backups"
[ -d "$B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}H/backups"
[ -d "${'" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="$(ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}B" ] || { p "❌ Aucune sauvegarde disponible."; exit 1; }
LATEST="${'ls -1t "$B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}(ls -1t "${'"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "$LATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}B"/jarvis-hermes-*.tar.gz 2>/dev/null | head -n1)"
[ -n "${'ATEST" ] && [ -f "$LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}LATEST" ] && [ -f "${'ATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "$LATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}LATEST" ] || { p "❌ Aucune archive Hermes trouvée."; exit 1; }
tar -tzf "${'ATEST" >/dev/null
p "⏳ Archive vérifiée : $(basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}LATEST" >/dev/null
p "⏳ Archive vérifiée : ${'basename "$LATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}(basename "${'ATEST")"
tar -xzf "$LATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}LATEST")"
tar -xzf "${'ATEST" -C "$HOME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}LATEST" -C "${'OME"
[ -f "$H/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}HOME"
[ -f "${'/.env" ] && chmod 600 "$H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}H/.env" ] && chmod 600 "${'/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

}H/.env"
p "✅ Fichiers restaurés. La passerelle va être redémarrée."
""".trimIndent() + "\n" + demarrage
        PontMcp.demarrer(app)
        return executer(app, script, libelle = "Restauration Hermes") ?: "♻️ Restauration lancée."
    }

    /** Réécrit la configuration (clés, modèle, pont MCP) puis redémarre Hermès. */
    fun configurer(app: JarvisApp): String {
        PontMcp.demarrer(app)
        val script = prelude(app) + "\n" + configuration(app) + "\n" + demarrage
        return executer(app, script, libelle = "Configuration d'Hermès") ?: "⚙️ Configuration envoyée, redémarrage d'Hermès…"
    }

    fun demarrer(app: JarvisApp): String {
        PontMcp.demarrer(app)
        return executer(app, prelude(app) + "\n" + demarrage, libelle = "Démarrage d'Hermès") ?: "▶️ Démarrage d'Hermès…"
    }

    fun arreter(app: JarvisApp): String =
        executer(app, prelude(app) + "\npkill -f 'hermes gateway' >/dev/null 2>&1; termux-wake-unlock >/dev/null 2>&1; p '⏹️ Hermès arrêté.'", libelle = "Arrêt d'Hermès") ?: "⏹️ Arrêt d'Hermès…"

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