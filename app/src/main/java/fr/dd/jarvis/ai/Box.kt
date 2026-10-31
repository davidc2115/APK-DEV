package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import java.util.Properties

/** Disque dur branché sur la box : partage SMB (Windows) ou FTP. Adresse de la box = réglage, sinon la passerelle du Wi-Fi. */
object Box {
    private const val MAX_TELECH = 80L * 1024 * 1024

    fun hote(app: JarvisApp): String = app.store.boxHost.value.trim().ifBlank { Reseau.infos(app)?.passerelle.orEmpty() }

    /** Traduit l'erreur SMB/FTP en explication compréhensible. */
    private fun expliquer(e: Exception, h: String): String {
        val m = (e.message ?: "").lowercase()
        return when {
            e is java.net.UnknownHostException -> "Adresse « $h » introuvable."
            m.contains("logon failure") || m.contains("access is denied") || m.contains("access_denied") || m.contains("identifiants") -> "Identifiants refusés par la box : vérifie l'utilisateur et le mot de passe du partage dans Réglages › Box (ou laisse-les vides pour l'invité, s'il est autorisé)."
            m.contains("stacksavana") || m.contains("logon_failure") -> "Identifiants refusés par la box."
            e is java.net.ConnectException || m.contains("connection refused") -> "Connexion refusée par $h : le partage de fichiers n'est peut-être pas activé sur la box (interface de la box › réglages du disque dur / partage réseau)."
            e is java.net.SocketTimeoutException || m.contains("timeout") -> "Pas de réponse de $h : vérifie l'adresse de la box dans Réglages › Box, et que le partage de fichiers est activé."
            m.contains("no such file") || m.contains("does not exist") -> "Dossier ou partage introuvable à ce chemin."
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    private fun fmt(n: Long) = when { n >= 1 shl 30 -> "%.1f Go".format(n / 1073741824.0); n >= 1 shl 20 -> "%.1f Mo".format(n / 1048576.0); n >= 1024 -> "${n / 1024} Ko"; else -> "$n o" }
    private val dateFmt get() = java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.FRENCH)

    // ------------------------------------------------------------------ SMB

    private fun ctx(app: JarvisApp): CIFSContext {
        val p = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB1"); setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.connTimeout", "8000"); setProperty("jcifs.smb.client.responseTimeout", "20000")
            setProperty("jcifs.resolveOrder", "DNS")
        }
        val base = BaseContext(PropertyConfiguration(p))
        val u = app.store.boxUser.value.trim()
        return if (u.isBlank()) base.withCredentials(NtlmPasswordAuthenticator(null, "guest", "")) else base.withCredentials(NtlmPasswordAuthenticator(null, u, app.store.boxPass.value))
    }

    private fun url(app: JarvisApp, chemin: String, dossier: Boolean): String {
        val c = chemin.trim().trim('/')
        return "smb://" + hote(app) + "/" + c + if (dossier && c.isNotEmpty()) "/" else ""
    }

    private fun smbListe(app: JarvisApp, chemin: String): String {
        val d = SmbFile(url(app, chemin, true), ctx(app))
        val l = d.listFiles().sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).filter { !it.name.startsWith(".") }
        if (l.isEmpty()) return "(dossier vide)"
        return l.take(150).joinToString("\n") { f ->
            if (f.isDirectory) "📁 ${f.name.trimEnd('/')}" else "📄 ${f.name} · ${fmt(runCatching { f.length() }.getOrDefault(0))} · ${dateFmt.format(java.util.Date(runCatching { f.lastModified() }.getOrDefault(0)))}"
        } + if (l.size > 150) "\n… (${l.size} éléments)" else ""
    }

    // ------------------------------------------------------------------ FTP

    private fun <T> ftp(app: JarvisApp, f: (FTPClient) -> T): T {
        val c = FTPClient()
        c.connectTimeout = 8000; c.defaultTimeout = 20000
        c.connect(hote(app), app.store.boxPort.value.toIntOrNull() ?: 21)
        try {
            val u = app.store.boxUser.value.trim().ifBlank { "anonymous" }
            if (!c.login(u, app.store.boxPass.value.ifBlank { "jarvis@" })) throw IllegalStateException("FTP : identifiants refusés par la box.")
            c.enterLocalPassiveMode(); c.setFileType(FTP.BINARY_FILE_TYPE); c.controlEncoding = "UTF-8"
            return f(c)
        } finally { runCatching { c.logout() }; runCatching { c.disconnect() } }
    }

    private fun ftpListe(app: JarvisApp, chemin: String): String = ftp(app) { c ->
        val l = c.listFiles("/" + chemin.trim().trim('/')).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        if (l.isEmpty()) "(dossier vide)" else l.take(150).joinToString("\n") { if (it.isDirectory) "📁 ${it.name}" else "📄 ${it.name} · ${fmt(it.size)}" }
    }

    // ------------------------------------------------------------------ interface commune

    private fun dossierExports(app: JarvisApp) = Fichiers.dossier(app)

    /** Actions : partages | lister | lire | telecharger | envoyer | supprimer | dossier. */
    fun action(app: JarvisApp, action: String, chemin: String, fichier: String): String {
        val h = hote(app)
        if (h.isBlank()) return "Adresse de la box inconnue : connecte-toi à son Wi-Fi ou renseigne-la dans Réglages › Box."
        val smb = app.store.boxProto.value != "ftp"
        return try { actionInterne(app, action, chemin, fichier, h, smb) } catch (e: Exception) { throw IllegalStateException(expliquer(e, h), e) }
    }

    private fun actionInterne(app: JarvisApp, action: String, chemin: String, fichier: String, h: String, smb: Boolean): String {
        return when (action.lowercase()) {
            "partages", "lister" -> {
                val c = if (action.lowercase() == "partages") "" else chemin
                "📂 ${if (smb) "smb" else "ftp"}://$h/${c.trim('/')}\n" + (if (smb) smbListe(app, c) else ftpListe(app, c))
            }
            "lire" -> {
                val b: ByteArray = if (smb) SmbFile(url(app, chemin, false), ctx(app)).let { f -> if (f.length() > 300_000) throw IllegalStateException("Fichier trop gros pour être lu (${fmt(f.length())}) : utilise « telecharger ».") else f.inputStream.use { it.readBytes() } }
                else ftp(app) { c -> val o = java.io.ByteArrayOutputStream(); if (!c.retrieveFile("/" + chemin.trim('/'), o)) throw IllegalStateException("Fichier introuvable."); o.toByteArray() }
                val t = String(b, Charsets.UTF_8)
                if (t.count { it == '�' } > 5) "Fichier binaire (${fmt(b.size.toLong())}) : utilise « telecharger »." else "```\n" + t.take(8000) + "\n```"
            }
            "telecharger" -> {
                val nom = chemin.trim('/').substringAfterLast('/').replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "fichier" }
                val dest = File(dossierExports(app), nom)
                if (smb) {
                    val f = SmbFile(url(app, chemin, false), ctx(app))
                    if (f.length() > MAX_TELECH) throw IllegalStateException("Fichier trop gros (${fmt(f.length())}, maximum ${fmt(MAX_TELECH)}).")
                    f.inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                } else ftp(app) { c -> dest.outputStream().use { o -> if (!c.retrieveFile("/" + chemin.trim('/'), o)) throw IllegalStateException("Fichier introuvable.") } }
                "[📎 $nom (${fmt(dest.length())})](jarvis-file:$nom)\n✅ Copié depuis la box."
            }
            "envoyer" -> {
                val nom = fichier.substringAfterLast('/')
                val src = listOf(dossierExports(app), File(app.filesDir, "images")).map { File(it, nom) }.firstOrNull { it.exists() } ?: throw IllegalArgumentException("Fichier « $nom » introuvable (il doit avoir été créé par JARVIS).")
                val cible = (chemin.trim('/') + "/" + nom).trim('/')
                if (smb) SmbFile(url(app, cible, false), ctx(app)).outputStream.use { o -> src.inputStream().use { it.copyTo(o) } }
                else ftp(app) { c -> src.inputStream().use { if (!c.storeFile("/$cible", it)) throw IllegalStateException("Envoi refusé.") } }
                "✅ $nom envoyé sur la box (/$cible, ${fmt(src.length())})."
            }
            "supprimer" -> {
                if (smb) SmbFile(url(app, chemin, false), ctx(app)).delete() else ftp(app) { c -> c.deleteFile("/" + chemin.trim('/')) }
                "🗑️ Supprimé : /${chemin.trim('/')}"
            }
            "dossier" -> {
                if (smb) SmbFile(url(app, chemin, true), ctx(app)).mkdirs() else ftp(app) { c -> c.makeDirectory("/" + chemin.trim('/')) }
                "📁 Dossier créé : /${chemin.trim('/')}"
            }
            "renommer", "deplacer" -> {
                val dest = fichier.trim().trim('/')
                if (dest.isBlank()) throw IllegalArgumentException("Précise le nouveau chemin/nom (paramètre « fichier »).")
                if (smb) {
                    val src = SmbFile(url(app, chemin, false), ctx(app))
                    src.renameTo(SmbFile(url(app, dest, src.isDirectory), ctx(app)))
                } else ftp(app) { c -> if (!c.rename("/" + chemin.trim('/'), "/$dest")) throw IllegalStateException("Renommage/déplacement refusé (chemin de destination déjà utilisé ou invalide).") }
                "✅ /${chemin.trim('/')} → /$dest"
            }
            else -> "Action inconnue (partages, lister, lire, telecharger, envoyer, supprimer, dossier, renommer, deplacer)."
        }
    }
}
