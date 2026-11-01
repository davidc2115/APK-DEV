package fr.dd.jarvis.device

import android.content.Intent
import android.net.Uri
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Box
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Relais HTTP local : rend un fichier SMB/FTP lisible par les lecteurs Android via ACTION_VIEW. */
object MediaServeur {
    private const val PORT = 38173
    private data class Media(val app: JarvisApp, val chemin: String)
    private val pool = Executors.newCachedThreadPool()
    private val medias = ConcurrentHashMap<String, Media>()
    @Volatile private var serveur: ServerSocket? = null

    /** URL HTTP locale réutilisable par les lecteurs Android et par le lecteur intégré de Jarvis. */
    fun url(app: JarvisApp, chemin: String): String {
        demarrer()
        val id = UUID.randomUUID().toString().replace("-", "")
        medias[id] = Media(app, chemin)
        return "http://127.0.0.1:$PORT/$id"
    }

    /** Prépare un flux indépendant et ouvre le sélecteur Android des lecteurs vidéo réellement installés. */
    fun ouvrir(app: JarvisApp, chemin: String): String {
        val url = url(app, chemin)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), Box.mimeVideo(chemin))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val handlers = app.packageManager.queryIntentActivities(view, 0)
        if (handlers.isEmpty()) {
            medias.remove(Uri.parse(url).lastPathSegment.orEmpty())
            return "Aucun lecteur compatible n'est détecté pour le format ${Box.mimeVideo(chemin)}. Installe ou active un lecteur vidéo puis réessaie."
        }
        app.startActivity(Intent.createChooser(view, "Lire le film avec…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "🎬 Ouverture de « ${chemin.substringAfterLast('/')} » dans le sélecteur Android. Choisis l'un des lecteurs proposés."
    }

    @Synchronized private fun demarrer() {
        serveur?.let { if (!it.isClosed) return }
        val s = ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))
        serveur = s
        pool.execute {
            while (!s.isClosed) {
                val sock = try { s.accept() } catch (_: Exception) { break }
                pool.execute { runCatching { traiter(sock) } }
            }
        }
    }

    private fun traiter(sock: Socket) {
        sock.use { sk ->
            sk.soTimeout = 30000
            val input = sk.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val request = input.readLine() ?: return
            val method = request.substringBefore(' ').uppercase()
            val path = request.substringAfter(' ', "").substringBefore(' ').substringBefore('?').trim('/')
            var rangeHeader: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isBlank()) break
                if (line.startsWith("Range:", true)) rangeHeader = line.substringAfter(':').trim()
            }
            val media = medias[path]
            val out = sk.getOutputStream()
            if (media == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
            }
            val meta = try { Box.ouvrirFlux(media.app, media.chemin, 0).let { it.stream.close(); it.longueur to it.type } }
                catch (e: Exception) {
                    val body = ("Erreur de lecture : ${e.message}").toByteArray()
                    out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body); return
                }
            val total = meta.first
            var start = 0L
            var end = if (total > 0) total - 1 else -1L
            var partial = false
            if (rangeHeader != null && total > 0) {
                val parts = rangeHeader!!.removePrefix("bytes=").trim().split('-', limit = 2)
                if (parts.size != 2) {
                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\n\r\n".toByteArray()); return
                }
                if (parts[0].isBlank()) start = (total - (parts[1].toLongOrNull() ?: 0L).coerceAtLeast(0)).coerceAtLeast(0)
                else {
                    start = parts[0].toLongOrNull() ?: 0L
                    if (parts[1].isNotBlank()) end = parts[1].toLongOrNull() ?: end
                }
                if (start >= total || end < start) {
                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\n\r\n".toByteArray()); return
                }
                end = end.coerceAtMost(total - 1); partial = true
            }
            val length = if (total > 0) end - start + 1 else -1L
            val headers = StringBuilder(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            if (length >= 0) headers.append("Content-Length: $length\r\n")
            if (partial) headers.append("Content-Range: bytes $start-$end/$total\r\n")
            headers.append("Accept-Ranges: bytes\r\nContent-Type: ${meta.second}\r\nConnection: close\r\n\r\n")
            out.write(headers.toString().toByteArray(Charsets.ISO_8859_1))
            if (method == "HEAD") return
            val flux = try { Box.ouvrirFlux(media.app, media.chemin, start) } catch (_: Exception) { return }
            flux.stream.use { stream ->
                val buf = ByteArray(65536)
                var restant = length
                while (restant != 0L) {
                    val demande = if (restant > 0) minOf(buf.size.toLong(), restant).toInt() else buf.size
                    val n = try { stream.read(buf, 0, demande) } catch (_: Exception) { break }
                    if (n <= 0) break
                    try { out.write(buf, 0, n) } catch (_: Exception) { break }
                    if (restant > 0) restant -= n
                }
            }
            runCatching { out.flush() }
        }
    }
}
