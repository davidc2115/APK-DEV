package fr.dd.jarvis.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun openLink(ctx: Context, url: String) {
    // Médias créés par JARVIS (images, vidéos IA, fichiers) : ouverts dans la visionneuse du téléphone.
    val media = Regex("^(jarvis-img|jarvis-video-ia|jarvis-file):(.+)$").find(url.trim())
    if (media != null) { ouvrirMedia(ctx, media.groupValues[1], media.groupValues[2]); return }
    // Liens internes « jarvis://ecran/rappels » : ouvrent un écran de l'application (page des rappels…).
    if (url.startsWith("jarvis://ecran/")) {
        runCatching {
            ctx.startActivity(Intent(ctx, fr.dd.jarvis.MainActivity::class.java).putExtra("terrain", url.removePrefix("jarvis://ecran/").substringBefore('?'))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        return
    }
    // Chemin ou lien local vers une image de JARVIS (« images/img_….png », file://…) : ouvert dans la visionneuse.
    if (!url.startsWith("http", true)) {
        val nom = url.substringAfterLast('/').substringBefore('?')
        if (Regex("(?i).+\\.(png|jpe?g|webp)$").matches(nom) && fichierMedia(ctx, "jarvis-img", nom).exists()) { ouvrirMedia(ctx, "jarvis-img", nom); return }
    }
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        android.widget.Toast.makeText(ctx, "Impossible d'ouvrir ce lien : $url", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Fichier local d'un média JARVIS (images/, videos_ia/, exports/). */
internal fun fichierMedia(ctx: Context, type: String, nom: String): java.io.File = java.io.File(java.io.File(ctx.filesDir, when (type) {
    "jarvis-img" -> "images"; "jarvis-video-ia" -> "videos_ia"; else -> "exports"
}), nom.substringAfterLast('/'))

/** Ouvre une image / vidéo / fichier créé par JARVIS dans l'application adaptée (visionneuse, lecteur…). */
internal fun ouvrirMedia(ctx: Context, type: String, nom: String, partager: Boolean = false) {
    val file = fichierMedia(ctx, type, nom)
    if (!file.exists()) { android.widget.Toast.makeText(ctx, "Fichier introuvable : ${file.name}", android.widget.Toast.LENGTH_SHORT).show(); return }
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val mime = when (type) { "jarvis-img" -> "image/" + (if (file.name.endsWith(".jpg", true) || file.name.endsWith(".jpeg", true)) "jpeg" else "png"); "jarvis-video-ia" -> "video/mp4"; else -> fr.dd.jarvis.ai.Fichiers.mimeDe(file.name) }
        val i = if (partager) Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Partager")
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { android.widget.Toast.makeText(ctx, "Aucune application pour ouvrir ${file.name}", android.widget.Toast.LENGTH_SHORT).show() }
}

private val inlineRegex = Regex("""\*\*(.+?)\*\*|\[([^\]]+)]\(([^)\s]+)\)""")

private fun inline(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in inlineRegex.findAll(text)) {
        append(text.substring(last, m.range.first))
        val bold = m.groups[1]
        if (bold != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold.value) }
        } else {
            val label = m.groups[2]!!.value
            val url = m.groups[3]!!.value
            pushStringAnnotation("URL", url)
            withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(label) }
            pop()
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/** Rendu Markdown minimal : titres, tableaux, listes, gras, liens cliquables ; plannings (🕗) en cartes par événement. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = HudText) {
    val accent = LocalAccent.current
    var body = text.trimStart()
    if (body.startsWith("---\n")) {
        val fin = body.indexOf("\n---", 3)
        if (fin > 0) body = body.substring(fin + 4)
    }
    val lines = body.lines()
    if (!body.contains("🕗")) {
        Column(modifier) { MdLines(lines, color, accent) }
        return
    }
    // Mode planning : un bloc visuel par événement (## …), titres de jour en grand, séparateurs --- supprimés.
    val blocs = mutableListOf<Pair<Boolean, MutableList<String>>>() // (carte ?, lignes)
    for (l in lines) {
        val t = l.trim()
        when {
            t == "---" || t == "***" -> continue
            l.startsWith("## ") -> blocs += true to mutableListOf(l)
            l.startsWith("# ") -> blocs += false to mutableListOf(l)
            else -> if (blocs.isEmpty()) blocs += false to mutableListOf(l) else blocs.last().second += l
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((carte, ls) in blocs) {
            val contenu = ls.dropLastWhile { it.isBlank() }
            if (contenu.isEmpty()) continue
            if (carte) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.09f)).height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                ) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(accent.copy(alpha = 0.85f)))
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) { MdLines(contenu, color, accent) }
                }
            } else Column { MdLines(contenu, color, accent) }
        }
    }
}

@Composable
private fun MdLines(lines: List<String>, color: Color, accent: Color) {
    val ctx = LocalContext.current
    var i = 0
    while (i < lines.size) {
        val line = imagesLocales(ctx, lines[i].trimEnd())
        i++
        // Image, vidéo ou fichier n'importe où dans la ligne (même reformulé par Hermès) : affiché en vrai, pas en lien.
        val morceaux = if (line.contains("jarvis-") && !line.trimStart().startsWith("|")) fr.dd.jarvis.ai.Medias.decouper(line) else null
        if (morceaux != null) {
            for (m in morceaux) when (m) {
                is fr.dd.jarvis.ai.Medias.Morceau.Texte -> Line(inline(m.texte, accent), color, 14.sp, FontWeight.Normal, accent, ctx)
                is fr.dd.jarvis.ai.Medias.Morceau.Image -> ChatImage(m.nom)
                is fr.dd.jarvis.ai.Medias.Morceau.Video -> ChatVideo(m.nom, accent)
                is fr.dd.jarvis.ai.Medias.Morceau.Fichier -> FichierChip(m.libelle, m.nom, accent)
            }
            continue
        }
        when {
            line.trimStart().startsWith("|") -> {
                val rows = mutableListOf(line)
                while (i < lines.size && lines[i].trimStart().startsWith("|")) { rows += lines[i].trimEnd(); i++ }
                Table(rows, color, accent, ctx)
            }
            line.isBlank() -> Spacer(Modifier.height(4.dp))
            line.trim() == "---" || line.trim() == "***" -> {
                Spacer(Modifier.height(4.dp))
                HudDivider()
                Spacer(Modifier.height(4.dp))
            }
            line.startsWith("### ") -> Line(inline(line.removePrefix("### "), accent), color, 14.sp, FontWeight.SemiBold, accent, ctx)
            line.startsWith("## ") -> Line(inline(line.removePrefix("## "), accent), color, 15.sp, FontWeight.Bold, accent, ctx)
            line.startsWith("# ") -> Line(inline(line.removePrefix("# "), accent), accent, 18.sp, FontWeight.Bold, accent, ctx)
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                Line(inline("• " + line.trimStart().drop(2), accent), color, 14.sp, FontWeight.Normal, accent, ctx)
            else -> Line(inline(line, accent), color, 14.sp, FontWeight.Normal, accent, ctx)
        }
    }
}

@Composable
private fun Line(
    text: AnnotatedString, color: Color, size: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight, accent: Color, ctx: Context
) {
    ClickableText(
        text = text,
        modifier = Modifier.padding(vertical = 1.dp),
        style = TextStyle(color = color, fontSize = size, fontWeight = weight, lineHeight = size * 1.35f, fontFamily = FontFamily.Default),
        onClick = { off ->
            text.getStringAnnotations("URL", off, off).firstOrNull()?.let { openLink(ctx, it.item) }
        }
    )
}

@Composable
private fun Table(rows: List<String>, color: Color, accent: Color, ctx: Context) {
    val cells = rows.map { r -> r.trim().trim('|').split('|').map { it.trim() } }
    val right = cells.getOrNull(1)?.map { it.endsWith(":") && !it.startsWith(":") } ?: emptyList()
    val data = cells.filterIndexed { idx, c -> !(idx == 1 && c.all { it.matches(Regex(":?-{2,}:?")) }) }
    val cols = data.maxOfOrNull { it.size } ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        data.forEachIndexed { ri, row ->
            Row(Modifier.fillMaxWidth().background(if (ri == 0) accent.copy(alpha = 0.14f) else Color.Transparent).padding(vertical = 3.dp, horizontal = 2.dp)) {
                for (c in 0 until cols) {
                    val t = row.getOrElse(c) { "" }
                    val first = c == 0
                    Box(Modifier.weight(if (first && cols > 2) 2.2f else 1f).padding(horizontal = 3.dp)) {
                        androidx.compose.material3.Text(
                            inline(t, accent), color = color, fontSize = 12.sp,
                            fontWeight = if (ri == 0) FontWeight.Bold else FontWeight.Normal,
                            textAlign = if (right.getOrElse(c) { false } && ri > 0) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            HudDivider()
        }
    }
}

/** Lien ou chemin vers un fichier image (relatif, absolu, file://, content:// de JARVIS) — pas une adresse web. */
private val LIEN_IMAGE_LOCALE = Regex("""!?\[([^\]]*)]\(((?!https?:|jarvis-)[^)\s]*?([\w.\-]+\.(?:png|jpe?g|webp)))\)|(?<![\w(/:-])((?:file://)?/[^\s)]*/images/([\w.\-]+\.(?:png|jpe?g|webp)))""", RegexOption.IGNORE_CASE)

/**
 * Image générée citée sous une autre forme que « jarvis-img: » (Hermès écrit parfois « ![images](images/img_….png) »,
 * un chemin du téléphone ou un lien file://) : si le fichier existe dans les images de JARVIS, on l'affiche quand même.
 */
internal fun imagesLocales(ctx: Context, ligne: String): String {
    if (!ligne.contains('.') || ligne.contains("jarvis-img:")) return ligne
    return LIEN_IMAGE_LOCALE.replace(ligne) { m ->
        val nom = m.groupValues[3].ifBlank { m.groupValues[5] }
        if (nom.isNotBlank() && fichierMedia(ctx, "jarvis-img", nom).exists()) "![image](jarvis-img:$nom)" else m.value
    }
}

/** Miniatures déjà décodées (le chat ne redécode pas l'image à chaque défilement). */
private val miniatures = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(24)

@Composable
private fun ChatImage(name: String) {
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    val file = fichierMedia(ctx, "jarvis-img", name)
    // Décodage hors du fil principal : le défilement de la conversation reste fluide.
    val etat = androidx.compose.runtime.produceState<Any?>(initialValue = miniatures.get(file.path), file.path) {
        if (value != null) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bornes = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, bornes)
                var echantillon = 1
                while (bornes.outWidth / (echantillon * 2) >= 900) echantillon *= 2
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = echantillon })
                    ?.asImageBitmap()?.also { miniatures.put(file.path, it) }
            }.getOrNull() ?: false
        }
    }
    when (val v = etat.value) {
        null -> Box(Modifier.fillMaxWidth().height(180.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.08f)))
        is androidx.compose.ui.graphics.ImageBitmap -> Column(Modifier.padding(vertical = 4.dp)) {
            androidx.compose.foundation.Image(
                v, contentDescription = "Image générée",
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { ouvrirMedia(ctx, "jarvis-img", name) },
                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                androidx.compose.material3.Text("🔍 Ouvrir", color = accent, fontSize = 12.sp, modifier = Modifier.clickable { ouvrirMedia(ctx, "jarvis-img", name) })
                androidx.compose.material3.Text("↗ Partager", color = accent, fontSize = 12.sp, modifier = Modifier.clickable { ouvrirMedia(ctx, "jarvis-img", name, partager = true) })
            }
        }
        else -> androidx.compose.material3.Text("🖼️ Image introuvable : ${file.name}", color = HudDim, fontSize = 12.sp)
    }
}


@Composable
private fun FichierChip(label: String, name: String, accent: Color) {
    val ctx = LocalContext.current
    val file = java.io.File(java.io.File(ctx.filesDir, "exports"), name.substringAfterLast('/'))
    androidx.compose.material3.Text(
        label, color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.14f))
            .clickable {
                runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
                    val mime = fr.dd.jarvis.ai.Fichiers.mimeDe(file.name)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Ouvrir " + file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }.padding(horizontal = 12.dp, vertical = 8.dp)
    )
}


@Composable
private fun ChatVideo(name: String, accent: Color) {
    val ctx = LocalContext.current
    val file = java.io.File(java.io.File(ctx.filesDir, "videos_ia"), name.substringAfterLast('/'))
    androidx.compose.material3.Text(
        "▶ Lire la vidéo · " + file.name,
        color = accent,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.14f)).clickable {
                if (!file.exists()) return@clickable
                runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
                    ctx.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "Lire la vidéo"
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }.padding(horizontal = 12.dp, vertical = 9.dp)
    )
}
