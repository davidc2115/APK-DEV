package fr.dd.jarvis.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import fr.dd.jarvis.JarvisApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Graphiques (courbe, barres, camembert, aires) dessinés en PNG et affichés dans la conversation. */
object Graphique {
    private val COULEURS = intArrayOf(0xFF00E5FF.toInt(), 0xFFFFB300.toInt(), 0xFF69F0AE.toInt(), 0xFFFF5252.toInt(), 0xFFB388FF.toInt(), 0xFFFF80AB.toInt(), 0xFF82B1FF.toInt(), 0xFFFFFF8D.toInt())
    private const val FOND = 0xFF0B1620.toInt()
    private const val TEXTE = 0xFFE6F1F7.toInt()
    private const val GRILLE = 0x33FFFFFF

    private class Serie(val nom: String, val valeurs: List<Double?>)

    private fun fmt(v: Double): String = when {
        abs(v) >= 1000 && v == floor(v) -> "%,d".format(v.toLong()).replace(',', ' ')
        v == floor(v) -> v.toLong().toString()
        abs(v) >= 100 -> "%.0f".format(v)
        else -> "%.1f".format(v).trimEnd('0').trimEnd('.')
    }

    private fun nombre(x: Any?): Double? = when (x) {
        null, JSONObject.NULL -> null
        is Number -> x.toDouble()
        else -> x.toString().replace(Regex("[^0-9,.\\-]"), "").replace(',', '.').toDoubleOrNull()
    }

    private fun axe(mini: Double, maxi: Double): Triple<Double, Double, Double> {
        var lo = min(mini, 0.0); var hi = max(maxi, 0.0)
        if (hi == lo) hi = lo + 1
        val brut = (hi - lo) / 5
        val p = 10.0.pow(floor(log10(brut)))
        val pas = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * p }.first { it >= brut }
        lo = floor(lo / pas) * pas; hi = ceil(hi / pas) * pas
        return Triple(lo, hi, pas)
    }

    fun creer(app: JarvisApp, a: JSONObject): String {
        val type = a.optString("type", "ligne").lowercase()
        val titre = a.optString("titre")
        val etiq = a.optJSONArray("etiquettes") ?: JSONArray()
        val labels = (0 until etiq.length()).map { etiq.get(it).toString() }
        val series = mutableListOf<Serie>()
        val sa = a.optJSONArray("series")
        if (sa != null) for (i in 0 until sa.length()) {
            val s = sa.optJSONObject(i) ?: continue
            val v = s.optJSONArray("valeurs") ?: continue
            series += Serie(s.optString("nom", "Série ${i + 1}"), (0 until v.length()).map { nombre(v.get(it)) })
        }
        if (series.isEmpty()) {
            val v = a.optJSONArray("valeurs")
            if (v != null) series += Serie(a.optString("nom", titre), (0 until v.length()).map { nombre(v.get(it)) })
        }
        if (series.isEmpty()) return "❌ Graphique impossible : aucune valeur fournie."
        val n = series.maxOf { it.valeurs.size }
        val noms = (0 until n).map { labels.getOrElse(it) { (it + 1).toString() } }

        val W = 1080; val H = 720
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(FOND)
        val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXTE; textSize = 40f; typeface = Typeface.DEFAULT_BOLD }
        val pp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXTE; textSize = 26f }
        if (titre.isNotBlank()) c.drawText(titre.take(48), 40f, 62f, pt)
        val haut = if (titre.isNotBlank()) 92f else 30f

        if (type.startsWith("camem") || type == "pie" || type == "donut") {
            val vals = series[0].valeurs.map { max(it ?: 0.0, 0.0) }
            val tot = vals.sum().takeIf { it > 0 } ?: return "❌ Graphique impossible : total nul."
            val r = RectF(60f, haut + 20, 60f + 460f, haut + 20 + 460f)
            var ang = -90f
            val pa = Paint(Paint.ANTI_ALIAS_FLAG)
            vals.forEachIndexed { i, v ->
                pa.color = COULEURS[i % COULEURS.size]
                val sw = (v / tot * 360).toFloat()
                c.drawArc(r, ang, sw, true, pa); ang += sw
            }
            if (type == "donut") { pa.color = FOND; c.drawCircle(r.centerX(), r.centerY(), 130f, pa) }
            vals.forEachIndexed { i, v ->
                val y = haut + 50 + i * 44f
                if (y < H - 20) {
                    pa.color = COULEURS[i % COULEURS.size]
                    c.drawRect(560f, y - 24, 590f, y + 4, pa)
                    c.drawText("${noms.getOrElse(i) { "" }.take(18)} — ${fmt(v)} (${"%.0f".format(v / tot * 100)} %)", 604f, y, pp)
                }
            }
        } else {
            val tous = series.flatMap { it.valeurs }.filterNotNull()
            if (tous.isEmpty()) return "❌ Graphique impossible : valeurs non numériques."
            val barres = type.startsWith("barre") || type == "bar" || type == "histogramme"
            var mini = tous.min(); var maxi = tous.max()
            if (type.startsWith("aire") || type == "area") mini = min(mini, 0.0)
            val (lo, hi, pas) = axe(mini, maxi)
            val leg = if (series.size > 1) 44f else 0f
            val g = RectF(120f, haut + 10 + leg, W - 40f, H - 90f)
            val pg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRILLE; strokeWidth = 2f }
            var v = lo
            pp.textAlign = Paint.Align.RIGHT; pp.textSize = 24f
            while (v <= hi + pas / 2) {
                val y = g.bottom - ((v - lo) / (hi - lo)).toFloat() * g.height()
                c.drawLine(g.left, y, g.right, y, pg)
                c.drawText(fmt(v), g.left - 12, y + 8, pp)
                v += pas
            }
            pp.textAlign = Paint.Align.CENTER; pp.textSize = if (n > 12) 18f else 22f
            val pasX = g.width() / n
            val saut = max(1, ceil(n / 14.0).toInt())
            fun xc(i: Int) = g.left + pasX * (i + 0.5f)
            fun yv(x: Double) = g.bottom - ((x - lo) / (hi - lo)).toFloat() * g.height()
            for (i in 0 until n) if (i % saut == 0) c.drawText(noms[i].take(10), xc(i), g.bottom + 34, pp)
            val zero = yv(0.0)
            c.drawLine(g.left, zero, g.right, zero, Paint(pg).apply { alpha = 120; strokeWidth = 3f })
            series.forEachIndexed { si, s ->
                val col = COULEURS[si % COULEURS.size]
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; strokeWidth = 5f; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
                if (barres) {
                    val lb = pasX * 0.8f / series.size
                    p.style = Paint.Style.FILL
                    s.valeurs.forEachIndexed { i, x ->
                        if (x != null) {
                            val x0 = xc(i) - pasX * 0.4f + lb * si
                            c.drawRect(x0, min(yv(x), zero), x0 + lb - 3, max(yv(x), zero), p)
                        }
                    }
                } else {
                    val path = Path(); var debut = true
                    s.valeurs.forEachIndexed { i, x ->
                        if (x == null) { debut = true } else { if (debut) { path.moveTo(xc(i), yv(x)); debut = false } else path.lineTo(xc(i), yv(x)) }
                    }
                    if (type.startsWith("aire") || type == "area") {
                        val fill = Path(path)
                        val idx = s.valeurs.indices.filter { s.valeurs[it] != null }
                        if (idx.isNotEmpty()) {
                            fill.lineTo(xc(idx.last()), zero); fill.lineTo(xc(idx.first()), zero); fill.close()
                            c.drawPath(fill, Paint(p).apply { style = Paint.Style.FILL; alpha = 70 })
                        }
                    }
                    c.drawPath(path, p)
                    val pd = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
                    s.valeurs.forEachIndexed { i, x -> if (x != null && n <= 40) c.drawCircle(xc(i), yv(x), 7f, pd) }
                }
                // valeurs affichées si peu de points
                if (n <= 10 && series.size <= 2) {
                    val pv = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXTE; textSize = 22f; textAlign = Paint.Align.CENTER }
                    s.valeurs.forEachIndexed { i, x -> if (x != null) c.drawText(fmt(x), xc(i) + (if (barres) (si - (series.size - 1) / 2f) * (pasX * 0.8f / series.size) else 0f), yv(x) - 14, pv) }
                }
            }
            if (series.size > 1) {
                var lx = 40f
                val pl = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; color = TEXTE }
                series.forEachIndexed { si, s ->
                    val pc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COULEURS[si % COULEURS.size] }
                    c.drawRect(lx, haut + 8, lx + 26, haut + 32, pc)
                    c.drawText(s.nom.take(16), lx + 36, haut + 30, pl)
                    lx += 60 + pl.measureText(s.nom.take(16))
                }
            }
            a.optString("unite").takeIf { it.isNotBlank() }?.let {
                val pu = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXTE; textSize = 22f; textAlign = Paint.Align.RIGHT }
                c.drawText(it, W - 40f, H - 20f, pu)
            }
        }
        val dir = File(app.filesDir, "images").apply { mkdirs() }
        val f = File(dir, "graph_${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        runCatching { Fichiers.dossier(app).let { f.copyTo(File(it, f.name), true) } }
        return "![graphique](jarvis-img:${f.name})"
    }
}
