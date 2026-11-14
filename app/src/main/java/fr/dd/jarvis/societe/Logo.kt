package fr.dd.jarvis.societe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Logo de la société pour l'en-tête des devis/factures PDF. Stocké en PNG (sans recompression avec perte) pour une
 *  qualité d'impression parfaite ; seule une image démesurée est redimensionnée (pour ne pas alourdir le PDF), jamais
 *  en dessous d'une taille largement suffisante pour l'impression. */
object Logo {
    private const val MAX_PX = 1600

    private fun fichier(ctx: Context) = File(ctx.filesDir, "societe_logo.png")

    fun existe(ctx: Context) = fichier(ctx).exists()

    fun enregistrer(ctx: Context, bytes: ByteArray): Boolean {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        val bmp = if (src.width > MAX_PX || src.height > MAX_PX) {
            val ratio = minOf(MAX_PX.toFloat() / src.width, MAX_PX.toFloat() / src.height)
            Bitmap.createScaledBitmap(src, (src.width * ratio).toInt().coerceAtLeast(1), (src.height * ratio).toInt().coerceAtLeast(1), true)
        } else src
        fichier(ctx).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return true
    }

    fun supprimer(ctx: Context) = fichier(ctx).delete()

    /** PNG du logo (documents Word de la société) et ses dimensions en pixels. */
    fun png(ctx: Context): Triple<ByteArray, Int, Int>? {
        val f = fichier(ctx).takeIf { it.isFile } ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        return Triple(f.readBytes(), o.outWidth, o.outHeight)
    }

    fun bitmap(ctx: Context): Bitmap? = if (existe(ctx)) runCatching { BitmapFactory.decodeFile(fichier(ctx).absolutePath) }.getOrNull() else null
}
