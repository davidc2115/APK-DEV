package fr.dd.jarvis.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import fr.dd.jarvis.JarvisApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Impression via IPP (Internet Printing Protocol) : standard géré par pratiquement toutes les imprimantes réseau récentes
 * (Canon, Epson, HP, Brother, Samsung, Xerox, Ricoh, Kyocera…), sans pilote. En local depuis le téléphone, ou « à distance »
 * en passant par le serveur Debian (SSH) qui envoie le travail à l'imprimante de son réseau.
 */
object Impression {
    private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    private val CHEMINS = listOf("/ipp/print", "/ipp", "/ipp/printer", "/printers/ipp", "/")

    data class Imprimante(val ip: String, val chemin: String, val attrs: Map<String, List<String>>) {
        val nom get() = attrs["printer-name"]?.firstOrNull() ?: attrs["printer-info"]?.firstOrNull() ?: ip
        val modele get() = attrs["printer-make-and-model"]?.firstOrNull().orEmpty()
        val formats get() = attrs["document-format-supported"].orEmpty()
        val etat get() = when (attrs["printer-state"]?.firstOrNull()) { "3" -> "prête"; "4" -> "impression en cours"; "5" -> "à l'arrêt" else -> "?" }
        val raisons get() = attrs["printer-state-reasons"].orEmpty().filter { it != "none" }
    }

    // ---------------------------------------------------------------- encodage / décodage IPP

    private fun ByteArrayOutputStream.s(v: Int) { write(v shr 8 and 255); write(v and 255) }
    private fun ByteArrayOutputStream.attr(tag: Int, name: String, value: String) { write(tag); s(name.length); write(name.toByteArray()); val b = value.toByteArray(); s(b.size); write(b) }
    private fun ByteArrayOutputStream.int(name: String, v: Int) { write(0x21); s(name.length); write(name.toByteArray()); s(4); write(v ushr 24 and 255); write(v ushr 16 and 255); write(v ushr 8 and 255); write(v and 255) }

    private fun requete(op: Int, ip: String, chemin: String, extra: ByteArrayOutputStream.() -> Unit, doc: ByteArray? = null): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(1); o.write(1); o.s(op); o.write(0); o.write(0); o.write(0); o.write(1)
        o.write(0x01)
        o.attr(0x47, "attributes-charset", "utf-8"); o.attr(0x48, "attributes-natural-language", "fr")
        o.attr(0x45, "printer-uri", "ipp://$ip:631$chemin")
        o.extra()
        o.write(0x03)
        if (doc != null) o.write(doc)
        return o.toByteArray()
    }

    fun parse(b: ByteArray): Pair<Int, Map<String, List<String>>> {
        if (b.size < 8) return -1 to emptyMap()
        val status = (b[2].toInt() and 255 shl 8) or (b[3].toInt() and 255)
        val m = LinkedHashMap<String, MutableList<String>>()
        var p = 8; var last = ""
        fun u16(i: Int) = (b[i].toInt() and 255 shl 8) or (b[i + 1].toInt() and 255)
        try {
            while (p < b.size) {
                val tag = b[p].toInt() and 255; p++
                if (tag == 0x03) break
                if (tag <= 0x05) continue
                val nl = u16(p); p += 2
                val name = String(b, p, nl); p += nl
                val vl = u16(p); p += 2
                val v = when (tag) {
                    0x21, 0x23 -> if (vl == 4) (((b[p].toInt() and 255) shl 24) or ((b[p + 1].toInt() and 255) shl 16) or ((b[p + 2].toInt() and 255) shl 8) or (b[p + 3].toInt() and 255)).toString() else ""
                    0x22 -> if (vl > 0 && b[p].toInt() != 0) "true" else "false"
                    in 0x40..0x4F -> String(b, p, vl)
                    else -> ""
                }
                p += vl
                if (name.isNotEmpty()) last = name
                if (v.isNotEmpty()) m.getOrPut(last) { mutableListOf() }.let { if (it.size < 40) it.add(v) }
            }
        } catch (_: Exception) { }
        return status to m
    }

    // ---------------------------------------------------------------- transport : direct (téléphone) ou via le serveur Debian

    private fun envoyer(app: JarvisApp, via: String, ip: String, chemin: String, corps: ByteArray): ByteArray? {
        if (via != "serveur") {
            val r = Request.Builder().url("http://$ip:631$chemin").post(corps.toRequestBody("application/ipp".toMediaType())).build()
            return runCatching { http.newCall(r).execute().use { it.body?.bytes() } }.getOrNull()
        }
        val tmp = "/tmp/jarvis_ipp_${System.nanoTime()}"
        Serveur.envoyerOctets(app, "$tmp.req", corps)
        val r = Serveur.exec(app, "curl -s -m 70 -H 'Content-Type: application/ipp' --data-binary @$tmp.req -o $tmp.resp 'http://$ip:631$chemin'; base64 -w0 $tmp.resp 2>/dev/null; rm -f $tmp.req $tmp.resp", 90)
        return runCatching { android.util.Base64.decode(r.second.trim().lines().last(), android.util.Base64.DEFAULT) }.getOrNull()
    }

    private val ATTRS: ByteArrayOutputStream.() -> Unit = {
        val l = listOf("printer-name", "printer-info", "printer-make-and-model", "printer-state", "printer-state-reasons", "document-format-supported", "printer-is-accepting-jobs", "marker-names", "marker-levels", "sides-supported", "printer-location")
        l.forEachIndexed { i, n -> write(0x44); s(if (i == 0) "requested-attributes".length else 0); if (i == 0) write("requested-attributes".toByteArray()); s(n.length); write(n.toByteArray()) }
    }

    fun interroger(app: JarvisApp, ip: String, via: String = "local", chemin: String? = null): Imprimante? {
        for (c in listOfNotNull(chemin) + CHEMINS) {
            val rep = envoyer(app, via, ip, c, requete(0x000B, ip, c, ATTRS)) ?: continue
            val (st, at) = parse(rep)
            if (st in 0..2 && at.isNotEmpty()) return Imprimante(ip, c, at)
        }
        return null
    }

    // ---------------------------------------------------------------- documents

    private fun pdfImage(b: Bitmap): ByteArray {
        val doc = PdfDocument(); val W = 595; val H = 842
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val sc = minOf((W - 40f) / b.width, (H - 40f) / b.height)
        val w = b.width * sc; val h = b.height * sc
        page.canvas.drawBitmap(b, null, android.graphics.RectF((W - w) / 2, (H - h) / 2, (W + w) / 2, (H + h) / 2), null)
        doc.finishPage(page)
        val o = ByteArrayOutputStream(); doc.writeTo(o); doc.close(); return o.toByteArray()
    }

    /** Prépare le document : (octets, type MIME) adapté aux formats que l'imprimante accepte. */
    private fun document(app: JarvisApp, fichier: String, texte: String, titre: String, formats: List<String>): Pair<ByteArray, String> {
        val pdfOk = formats.isEmpty() || "application/pdf" in formats
        if (texte.isNotBlank()) {
            if (!pdfOk && "text/plain" in formats) return texte.toByteArray() to "text/plain"
            return Fichiers.pdf(texte, titre) to "application/pdf"
        }
        val nom = fichier.substringAfterLast('/')
        val f = listOf(File(app.filesDir, "exports"), File(app.filesDir, "images")).map { File(it, nom) }.firstOrNull { it.exists() } ?: throw IllegalArgumentException("Fichier « $nom » introuvable (il doit avoir été créé par JARVIS).")
        return when (f.extension.lowercase()) {
            "pdf" -> if (pdfOk) f.readBytes() to "application/pdf" else throw IllegalStateException("Cette imprimante n'accepte pas le PDF (formats : ${formats.take(6).joinToString()}).")
            "jpg", "jpeg" -> if ("image/jpeg" in formats) f.readBytes() to "image/jpeg" else pdfImage(BitmapFactory.decodeFile(f.absolutePath)) to "application/pdf"
            "png", "webp" -> {
                val bmp = BitmapFactory.decodeFile(f.absolutePath)
                if ("image/jpeg" in formats) ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray() to "image/jpeg" else pdfImage(bmp) to "application/pdf"
            }
            "txt", "md", "csv" -> Fichiers.pdf(f.readText(), f.nameWithoutExtension) to "application/pdf"
            else -> throw IllegalStateException("Format .${f.extension} non imprimable directement (PDF, images ou texte).")
        }
    }

    fun imprimer(app: JarvisApp, ip: String, fichier: String, texte: String, titre: String, copies: Int, via: String): String {
        val p = interroger(app, ip, via) ?: return "❌ Aucune imprimante IPP n'a répondu sur $ip (port 631)" + (if (via == "serveur") " depuis le serveur." else ". Vérifie l'adresse, que tu es sur le même Wi-Fi, ou utilise via=serveur.")
        if (p.attrs["printer-is-accepting-jobs"]?.firstOrNull() == "false") return "⚠️ ${p.nom} n'accepte pas de travaux pour l'instant (${p.raisons.joinToString().ifBlank { p.etat }})."
        val (doc, mime) = document(app, fichier, texte, titre, p.formats)
        val job = requete(0x0002, ip, p.chemin, {
            attr(0x42, "requesting-user-name", "jarvis"); attr(0x42, "job-name", titre.ifBlank { "JARVIS" }.take(60))
            attr(0x49, "document-format", mime)
            if (copies > 1) int("copies", copies.coerceIn(1, 99))
        }, doc)
        val rep = envoyer(app, via, ip, p.chemin, job) ?: return "❌ Pas de réponse de l'imprimante pendant l'envoi."
        val (st, at) = parse(rep)
        val num = at["job-id"]?.firstOrNull()
        return if (st in 0..2) "🖨️ Envoyé à ${p.nom} (${p.modele.ifBlank { ip }})" + (if (via == "serveur") " via le serveur" else "") + (num?.let { " — travail n°$it" } ?: "") + ". ${doc.size / 1024} Ko en $mime."
        else "❌ Refus de l'imprimante (code IPP 0x${st.toString(16)}) ${at["status-message"]?.firstOrNull().orEmpty()}"
    }
}
