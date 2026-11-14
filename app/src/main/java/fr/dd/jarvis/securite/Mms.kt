package fr.dd.jarvis.securite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Envoi d'une photo par MMS depuis le téléphone (sans être l'application SMS par défaut) : la requête « m-send-req »
 * est encodée ici (WAP-209 / OMA MMS 1.2) puis confiée au service MMS d'Android (`SmsManager.sendMultimediaMessage`),
 * qui passe par l'APN MMS de l'opérateur.
 */
object Mms {
    /** Taille visée pour la photo : la plupart des opérateurs refusent au-delà de ~300 Ko. */
    private const val MAX_IMAGE = 280_000

    private fun uintvar(o: ByteArrayOutputStream, valeur: Int) {
        var v = valeur
        val octets = ArrayList<Int>()
        octets += v and 0x7F
        v = v ushr 7
        while (v > 0) { octets += (v and 0x7F) or 0x80; v = v ushr 7 }
        octets.reversed().forEach { o.write(it) }
    }

    private fun texteNul(o: ByteArrayOutputStream, s: String) { o.write(s.toByteArray(Charsets.US_ASCII)); o.write(0) }

    /** Numéro au format attendu par le MMSC : chiffres (et « + »), suivi de /TYPE=PLMN. */
    internal fun adresse(numero: String) = numero.filter { it.isDigit() || it == '+' } + "/TYPE=PLMN"

    /** Requête MMS complète : en-têtes + corps multipart (texte UTF-8 puis photo JPEG). */
    internal fun pdu(destinataires: List<String>, texte: String, jpeg: ByteArray?, transaction: String = "J" + System.currentTimeMillis().toString(16)): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0x8C); o.write(0x80)                       // X-Mms-Message-Type : m-send-req
        o.write(0x98); texteNul(o, transaction)            // X-Mms-Transaction-ID
        o.write(0x8D); o.write(0x92)                       // X-Mms-MMS-Version : 1.2
        o.write(0x89); o.write(0x01); o.write(0x81)        // From : renseigné par le réseau (insert-address-token)
        destinataires.forEach { o.write(0x97); texteNul(o, adresse(it)) }   // To
        o.write(0x96); texteNul(o, "Alerte JARVIS")        // Subject (ASCII)
        o.write(0x8A); o.write(0x80)                       // X-Mms-Message-Class : personal
        o.write(0x86); o.write(0x81)                       // X-Mms-Delivery-Report : no
        o.write(0x90); o.write(0x81)                       // X-Mms-Read-Report : no
        o.write(0x84); o.write(0xA3)                       // Content-Type : application/vnd.wap.multipart.mixed (toujours en dernier)
        val parties = mutableListOf<Pair<ByteArray, ByteArray>>()
        if (texte.isNotBlank()) {
            val h = ByteArrayOutputStream()
            h.write(0x03); h.write(0x83); h.write(0x81); h.write(0xEA)   // text/plain ; charset=utf-8
            h.write(0x8E); texteNul(h, "texte.txt")                       // Content-Location
            parties += h.toByteArray() to texte.toByteArray(Charsets.UTF_8)
        }
        if (jpeg != null) {
            val h = ByteArrayOutputStream()
            val nom = "photo.jpg".toByteArray(Charsets.US_ASCII)
            h.write(1 + 1 + nom.size + 1); h.write(0x9E); h.write(0x85); h.write(nom); h.write(0)   // image/jpeg ; name=photo.jpg
            h.write(0x8E); texteNul(h, "photo.jpg")                                                // Content-Location
            parties += h.toByteArray() to jpeg
        }
        uintvar(o, parties.size)
        parties.forEach { (entetes, donnees) -> uintvar(o, entetes.size); uintvar(o, donnees.size); o.write(entetes); o.write(donnees) }
        return o.toByteArray()
    }

    /** Photo réduite (1280 px max) et recompressée jusqu'à tenir dans la limite MMS. */
    fun reduire(jpeg: ByteArray): ByteArray {
        if (jpeg.size <= MAX_IMAGE) return jpeg
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        var cote = 1280
        var qualite = 80
        while (true) {
            val r = cote.toFloat() / maxOf(src.width, src.height)
            val bmp = if (r < 1f) Bitmap.createScaledBitmap(src, (src.width * r).toInt().coerceAtLeast(1), (src.height * r).toInt().coerceAtLeast(1), true) else src
            val out = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, qualite, it) }.toByteArray()
            if (out.size <= MAX_IMAGE || cote <= 480) return out
            if (qualite > 55) qualite -= 10 else cote = (cote * 0.75f).toInt()
        }
    }

    /** Envoie [texte] + [jpeg] à [numeros]. Renvoie un compte rendu (l'acheminement final dépend de l'opérateur). */
    fun envoyer(ctx: Context, numeros: List<String>, texte: String, jpeg: ByteArray?): String {
        val dest = numeros.map { it.trim() }.filter { n -> n.count { it.isDigit() } >= 6 }
        if (dest.isEmpty()) return "aucun numéro valide"
        if (ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "autorisation SMS refusée"
        return runCatching {
            val dossier = File(ctx.cacheDir, "mms").apply { mkdirs() }
            dossier.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000L }?.forEach { it.delete() }
            val f = File(dossier, "envoi_${System.currentTimeMillis()}.pdu")
            f.writeBytes(pdu(dest, texte, jpeg?.let { reduire(it) }))
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            @Suppress("DEPRECATION")
            val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sm.sendMultimediaMessage(ctx, uri, null, null, null)
            "MMS confié au téléphone pour ${dest.size} numéro(s)"
        }.getOrElse { "échec MMS : ${it.message ?: it.javaClass.simpleName}" }
    }
}
