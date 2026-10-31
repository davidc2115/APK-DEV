package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** Petit serveur HTTP local (127.0.0.1 uniquement) qui relaie en streaming un fichier du disque de la box vers le lecteur
 *  vidéo du téléphone : ça permet d'utiliser n'importe quelle app vidéo installée (même celle par défaut du téléphone,
 *  sans avoir besoin de VLC) pour lire un fichier SMB/FTP, avec prise en charge des requêtes Range (avance/recherche). */
object MediaServeur {
    private const val PORT = 38173
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var serveur: ServerSocket? = null
    @Volatile private var cheminActuel: String = ""
    @Volatile private var appRef: JarvisApp? = null

    /** Démarre le serveur si besoin, enregistre le fichier à servir, et retourne l'URL locale à ouvrir. */
    fun url(app: JarvisApp, chemin: String): String {
        appRef = app
        cheminActuel = chemin
        demarrer()
        return "http://127.0.0.1:$PORT/jarvis.stream"
    }

    private fun demarrer() {
        if (serveur != null) return
        val s = try { ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1")) } catch (e: Exception) { return }
        serveur = s
        pool.execute {
            while (true) {
                val sock = try { s.accept() } catch (e: Exception) { break }
                pool.execute { runCatching { traiter(sock) } }
            }
        }
    }

    private fun traiter(sock: Socket) {
        sock.use { sk ->
            sk.soTimeout = 15000
            val entree = sk.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            entree.readLine() ?: return
            var debut = 0L
            while (true) {
                val l = entree.readLine() ?: break
                if (l.isBlank()) break
                if (l.startsWith("Range:", true)) debut = l.substringAfter("bytes=").substringBefore('-').trim().toLongOrNull() ?: 0L
            }
            val app = appRef ?: return
            val flux = try { fr.dd.jarvis.ai.Box.ouvrirFlux(app, cheminActuel, debut) } catch (e: Exception) {
                val msg = "Erreur : ${e.message}".toByteArray()
                sk.getOutputStream().write(("HTTP/1.1 502 Bad Gateway\r\nContent-Length: ${msg.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                sk.getOutputStream().write(msg); return
            }
            val sortie = sk.getOutputStream()
            val total = flux.longueur
            val entete = StringBuilder()
            if (debut > 0 && total > 0) {
                entete.append("HTTP/1.1 206 Partial Content\r\n")
                entete.append("Content-Range: bytes $debut-${total - 1}/$total\r\n")
                entete.append("Content-Length: ${total - debut}\r\n")
            } else {
                entete.append("HTTP/1.1 200 OK\r\n")
                if (total > 0) entete.append("Content-Length: $total\r\n")
            }
            entete.append("Accept-Ranges: bytes\r\nContent-Type: ${flux.type}\r\nConnection: close\r\n\r\n")
            sortie.write(entete.toString().toByteArray(Charsets.ISO_8859_1))
            flux.stream.use { ins ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = try { ins.read(buf) } catch (e: Exception) { break }
                    if (n <= 0) break
                    try { sortie.write(buf, 0, n) } catch (e: Exception) { break }
                }
            }
            runCatching { sortie.flush() }
        }
    }
}
