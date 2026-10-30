package fr.dd.jarvis.ai

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import fr.dd.jarvis.JarvisApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Serveur personnel : SSH (commandes, fichiers) et Home Assistant (API REST + fichiers de configuration YAML). */
object Serveur {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()

    // ------------------------------------------------------------------ SSH

    fun sshConfigure(app: JarvisApp) = app.store.sshHost.value.isNotBlank() && app.store.sshUser.value.isNotBlank() &&
        (app.store.sshPass.value.isNotBlank() || app.store.sshKey.value.isNotBlank())

    /** Autre machine à joindre en SSH avec des identifiants donnés par l'utilisateur (sinon le serveur des Réglages). */
    data class Cible(val hote: String, val port: Int, val user: String, val pass: String)

    private fun ouvrir(app: JarvisApp, cible: Cible? = null): Session {
        if (cible != null) {
            val s = JSch().getSession(cible.user, cible.hote, cible.port)
            s.setPassword(cible.pass); s.setConfig("StrictHostKeyChecking", "no"); s.connect(15000); return s
        }
        val st = app.store
        val jsch = JSch()
        if (st.sshKey.value.isNotBlank()) jsch.addIdentity("jarvis", st.sshKey.value.trim().replace("\r", "").plus("\n").toByteArray(), null, st.sshPass.value.takeIf { it.isNotBlank() }?.toByteArray())
        val s = jsch.getSession(st.sshUser.value.trim(), st.sshHost.value.trim(), st.sshPort.value.toIntOrNull() ?: 22)
        if (st.sshKey.value.isBlank()) s.setPassword(st.sshPass.value)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", if (st.sshKey.value.isNotBlank()) "publickey,password,keyboard-interactive" else "password,keyboard-interactive,publickey")
        s.connect(15000)
        // Empreinte du serveur mémorisée à la première connexion ; refus si elle change (protège d'un faux serveur).
        val fp = s.hostKey.getFingerPrint(jsch)
        val connu = st.sshFp.value
        if (connu.isBlank()) st.sshFp.set(fp)
        else if (connu != fp) { s.disconnect(); throw IllegalStateException("L'empreinte du serveur SSH a changé ($fp au lieu de $connu). Si c'est normal (réinstallation), efface-la dans Réglages › Serveur.") }
        return s
    }

    private fun <T> session(app: JarvisApp, cible: Cible? = null, f: (Session) -> T): T {
        val s = ouvrir(app, cible)
        try { return f(s) } finally { runCatching { s.disconnect() } }
    }

    /** Exécute une commande ; « sudo » utilise le mot de passe enregistré. Retourne (code de sortie, sortie). */
    fun exec(app: JarvisApp, commande: String, timeoutS: Int = 60, cible: Cible? = null): Pair<Int, String> = session(app, cible) { s ->
        val sudo = commande.contains("sudo ")
        val cmd = if (sudo) commande.replace(Regex("\\bsudo\\s+(?!-S)"), "sudo -S -p '' ") else commande
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand("export LANG=C.UTF-8; $cmd")
        val out = ByteArrayOutputStream()
        ch.setInputStream(if (sudo && (cible?.pass ?: app.store.sshPass.value).isNotBlank()) ByteArrayInputStream(((cible?.pass ?: app.store.sshPass.value) + "\n").toByteArray()) else null)
        ch.setErrStream(out)
        val inp = ch.inputStream
        ch.connect(10000)
        val buf = ByteArray(4096)
        val fin = System.currentTimeMillis() + timeoutS * 1000L
        while (true) {
            while (inp.available() > 0) { val n = inp.read(buf, 0, buf.size); if (n < 0) break; out.write(buf, 0, n) }
            if (ch.isClosed) { if (inp.available() > 0) continue; break }
            if (System.currentTimeMillis() > fin) { out.write("\n[… délai de ${timeoutS}s dépassé, commande interrompue]".toByteArray()); ch.disconnect(); return@session -1 to out.toString("UTF-8") }
            Thread.sleep(80)
        }
        val code = ch.exitStatus
        ch.disconnect()
        code to out.toString("UTF-8")
    }

    fun lire(app: JarvisApp, chemin: String, max: Int = 400_000): String = session(app) { s ->
        val c = s.openChannel("sftp") as ChannelSftp; c.connect(10000)
        try {
            val st = c.stat(chemin)
            if (st.size > max) throw IllegalStateException("Fichier trop gros (${st.size / 1024} Ko).")
            c.get(chemin).use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: com.jcraft.jsch.SftpException) {
            if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) null else throw e
        } finally { c.disconnect() }
    } ?: exec(app, "sudo cat " + q(chemin)).second

    /** Écrit un fichier (sauvegarde .jarvis-bak-… avant écrasement) ; passe par sudo si les droits manquent. */
    fun ecrire(app: JarvisApp, chemin: String, contenu: String, sauvegarde: Boolean = true): String? {
        val bak = if (sauvegarde) "$chemin.jarvis-bak-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) else null
        if (bak != null && exec(app, "test -f ${q(chemin)} && (cp -p ${q(chemin)} ${q(bak)} 2>/dev/null || sudo cp -p ${q(chemin)} ${q(bak)})").first != 0) {
            // fichier inexistant : pas de sauvegarde à faire
        }
        val ok = try {
            session(app) { s ->
                val c = s.openChannel("sftp") as ChannelSftp; c.connect(10000)
                try { c.put(ByteArrayInputStream(contenu.toByteArray()), chemin, ChannelSftp.OVERWRITE); true }
                catch (e: com.jcraft.jsch.SftpException) { false } finally { c.disconnect() }
            }
        } catch (e: Exception) { throw e }
        if (!ok) {
            val tmp = "/tmp/jarvis_up_${System.currentTimeMillis()}"
            session(app) { s ->
                val c = s.openChannel("sftp") as ChannelSftp; c.connect(10000)
                try { c.put(ByteArrayInputStream(contenu.toByteArray()), tmp, ChannelSftp.OVERWRITE) } finally { c.disconnect() }
            }
            val r = exec(app, "sudo cp ${q(tmp)} ${q(chemin)} && rm -f ${q(tmp)}")
            if (r.first != 0) throw IllegalStateException("Écriture refusée : ${r.second.take(200)}")
        }
        return bak
    }

    fun envoyerOctets(app: JarvisApp, chemin: String, octets: ByteArray) = session(app) { s ->
        val c = s.openChannel("sftp") as ChannelSftp; c.connect(10000)
        try { c.put(ByteArrayInputStream(octets), chemin, ChannelSftp.OVERWRITE) } finally { c.disconnect() }
    }

    fun lister(app: JarvisApp, dossier: String): String = exec(app, "ls -lAh --time-style=long-iso ${q(dossier)} | head -150").second

    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private val DANGER = Regex(
        "\\b(rm|rmdir|mkfs\\S*|dd|shred|reboot|shutdown|poweroff|halt|fdisk|parted|wipefs|userdel|passwd|chpasswd|iptables|ufw|kill|killall|pkill|truncate|mv|crontab)\\b" +
            "|docker\\s+(rm|rmi|system\\s+prune|volume\\s+rm|stop|kill|down|restart|compose\\s+down)|systemctl\\s+(stop|disable|mask|restart|reboot|poweroff)" +
            "|apt(-get)?\\s+(remove|purge|autoremove|upgrade|dist-upgrade)|>\\s*/(etc|boot|dev|var|usr)|chmod\\s+-R|chown\\s+-R|\\|\\s*(sudo\\s+)?(sh|bash)\\b|sed\\s+-i|tee\\s",
        RegexOption.IGNORE_CASE
    )
    fun dangereuse(cmd: String) = DANGER.containsMatchIn(cmd)

    // ------------------------------------------------------------------ Home Assistant (API REST)

    fun haConfigure(app: JarvisApp) = app.store.haUrl.value.isNotBlank() && app.store.haToken.value.isNotBlank()

    private fun base(app: JarvisApp) = app.store.haUrl.value.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }

    fun ha(app: JarvisApp, methode: String, chemin: String, corps: JSONObject? = null): String {
        if (!haConfigure(app)) throw IllegalStateException("Home Assistant non configuré (Réglages › Serveur : adresse et jeton d'accès longue durée).")
        val b = Request.Builder().url(base(app) + chemin).header("Authorization", "Bearer ${app.store.haToken.value.trim()}")
        if (methode == "POST") b.post((corps?.toString() ?: "{}").toRequestBody("application/json".toMediaType())) else b.get()
        http.newCall(b.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (r.code == 401) throw IllegalStateException("Home Assistant 401 : jeton refusé (crée un « jeton d'accès longue durée » dans ton profil HA).")
            if (!r.isSuccessful) throw IllegalStateException("Home Assistant ${r.code} : ${t.take(300)}")
            return t
        }
    }

    fun haEntites(app: JarvisApp, recherche: String, domaine: String): String {
        val arr = JSONArray(ha(app, "GET", "/api/states"))
        val q = recherche.trim().lowercase(); val d = domaine.trim().lowercase()
        val l = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { o ->
            val id = o.getString("entity_id")
            (d.isBlank() || id.startsWith("$d.")) && (q.isBlank() || id.lowercase().contains(q) || o.optJSONObject("attributes")?.optString("friendly_name").orEmpty().lowercase().contains(q))
        }
        if (l.isEmpty()) return "Aucune entité trouvée (${arr.length()} au total)."
        return "# 🏠 Entités (${l.size}${if (l.size > 60) ", 60 affichées" else ""})\n\n" + l.take(60).joinToString("\n") { o ->
            val a = o.optJSONObject("attributes"); val unit = a?.optString("unit_of_measurement").orEmpty()
            "- `${o.getString("entity_id")}` · ${a?.optString("friendly_name").orEmpty()} : **${o.getString("state")}$unit**"
        }
    }

    fun haEtat(app: JarvisApp, id: String): String {
        val o = JSONObject(ha(app, "GET", "/api/states/$id"))
        return "`$id` = **${o.getString("state")}**\n" + (o.optJSONObject("attributes")?.toString(1)?.take(1800) ?: "")
    }

    fun haService(app: JarvisApp, domaine: String, service: String, entite: String, donnees: JSONObject?): String {
        val corps = donnees ?: JSONObject()
        if (entite.isNotBlank()) corps.put("entity_id", if (entite.contains(',')) JSONArray(entite.split(",").map { it.trim() }) else entite)
        ha(app, "POST", "/api/services/$domaine/$service", corps)
        return "✅ $domaine.$service" + (if (entite.isNotBlank()) " → $entite" else "") + " exécuté."
    }

    /** Valide la configuration (API, sinon commande Docker). Retourne null si valide, sinon le message d'erreur. */
    fun haValider(app: JarvisApp): String? {
        if (haConfigure(app)) runCatching {
            val o = JSONObject(ha(app, "POST", "/api/config/core/check_config"))
            return if (o.optString("result") == "valid") null else "Configuration invalide : " + o.optString("errors", o.toString()).take(900)
        }
        if (sshConfigure(app)) {
            val nom = haConteneur(app) ?: return "Validation impossible (API Home Assistant injoignable et conteneur Docker introuvable)."
            val r = exec(app, "docker exec $nom python -m homeassistant --script check_config -c /config 2>&1 | tail -40", 120)
            return if (r.second.contains("Failed config", true) || r.second.contains("error", true) && !r.second.contains("Successful config", true)) "Configuration invalide :\n" + r.second.take(900) else null
        }
        return "Validation impossible (ni API, ni SSH)."
    }

    fun haConteneur(app: JarvisApp): String? =
        exec(app, "docker ps -a --format '{{.Names}}\t{{.Image}}' | grep -i home-assistant | head -1 | cut -f1").second.trim().ifBlank { null }

    /** Dossier de configuration de Home Assistant sur le serveur (réglage, sinon détection Docker puis emplacements usuels). */
    fun haDossier(app: JarvisApp): String? {
        app.store.haDir.value.trim().takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
        val nom = haConteneur(app)
        if (nom != null) {
            val src = exec(app, "docker inspect $nom --format '{{range .Mounts}}{{if eq .Destination \"/config\"}}{{.Source}}{{end}}{{end}}'").second.trim()
            if (src.startsWith("/")) { app.store.haDir.set(src); return src }
        }
        for (c in listOf("/config", "~/homeassistant", "/opt/homeassistant/config", "/opt/homeassistant", "/home/homeassistant/.homeassistant", "/usr/share/hassio/homeassistant", "~/.homeassistant")) {
            if (exec(app, "test -f $c/configuration.yaml").first == 0) {
                val abs = exec(app, "cd $c && pwd").second.trim().ifBlank { c }
                app.store.haDir.set(abs); return abs
            }
        }
        return null
    }
}
