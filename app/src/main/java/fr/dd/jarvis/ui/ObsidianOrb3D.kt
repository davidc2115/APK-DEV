package fr.dd.jarvis.ui

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import fr.dd.jarvis.JarvisApp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * Orbe Obsidienne 3D : la même constellation que la vue 2D (style de l'image de référence), posée sur un dôme
 * en perspective autour d'une vraie sphère d'obsidienne 3D (OpenGL ES 2.0, sans moteur externe).
 *
 *  - OpenGL dessine la profondeur : sphère d'obsidienne (veines violettes, reflets), anneaux orbitaux, étoiles.
 *  - Une couche Compose dessine par-dessus, à la position projetée de chaque élément 3D, les hubs, étiquettes,
 *    flèches et le cerveau néon, exactement dans le style 2D (toujours face à l'écran, donc toujours lisibles).
 *  - Caméra : léger balancement automatique ; glisser pour tourner, pincer pour zoomer, toucher pour ouvrir.
 */
@Composable
fun ObsidianOrb3D(app: JarvisApp, enTete: @Composable () -> Unit = {}) {
    val etat = rememberConstellation(app)
    var noteOuverte by remember { mutableStateOf<String?>(null) }
    var hubListe by remember { mutableStateOf<String?>(null) }
    val c = constellationDe(etat)
    if (c == null) ChargementEnCours(etat.erreur, enTete)
    else Orbe3DVue(c, enTete, onNote = { noteOuverte = it }, onHub = { cle -> if (c.hub(cle)?.type != TypeHub.PIECES && c.hub(cle)?.type != TypeHub.TAGS) hubListe = cle },
        onFichier = { chemin -> app.obsidian.ouvrirFichier(chemin) },
        onBasculer = { chemin -> etat.deplies = ConstellationLayout.basculer(c.deplies, chemin) })
    DialoguesConstellation(app, c, noteOuverte, { noteOuverte = it }, hubListe, { hubListe = it })
}

/** Unité « monde » 3D = 64 dp de la mise en page 2D (le rayon du cerveau). */
private const val DP_PAR_UNITE = 64f
private const val FOV_Y = 40f

/** Position 3D d'un point de la mise en page 2D : dôme qui s'éloigne vers les bords (y écran vers le bas → y monde vers le haut). */
private fun versDome(p: Pt, avance: Float = 0f): FloatArray {
    val x = p.x / DP_PAR_UNITE; val y = -p.y / DP_PAR_UNITE
    val z = -(x * x + y * y) * 0.045f + avance
    return floatArrayOf(x, y, z)
}

/** Caméra partagée entre la couche Compose et le rendu OpenGL (mêmes matrices des deux côtés). */
private class Camera3D {
    @Volatile var lacet = 0f      // rotation autour de Y (degrés)
    @Volatile var tangage = -10f  // rotation autour de X (degrés)
    @Volatile var zoom = 1f
    @Volatile var distance = 9f
    @Volatile var tempsCoeur = 0f
    /** Décalage vertical de l'image (en coordonnées normalisées) : recentre la constellation entre l'en-tête et le
     *  bas de l'écran sans déplacer le centre de rotation (la sphère). */
    @Volatile var decalageNdcY = 0f

    fun projection(largeur: Float, hauteur: Float): FloatArray {
        val p = FloatArray(16); val t = FloatArray(16); val out = FloatArray(16)
        Matrix.perspectiveM(p, 0, FOV_Y, largeur / max(hauteur, 1f), 0.1f, 60f)
        Matrix.setIdentityM(t, 0); t[13] = decalageNdcY
        Matrix.multiplyMM(out, 0, t, 0, p, 0)
        return out
    }

    fun matrices(largeur: Float, hauteur: Float, mvp: FloatArray, mv: FloatArray, modele: FloatArray = FloatArray(16)) {
        val proj = projection(largeur, hauteur); val vue = FloatArray(16)
        Matrix.setLookAtM(vue, 0, 0f, 0f, distance, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.setIdentityM(modele, 0)
        Matrix.rotateM(modele, 0, tangage, 1f, 0f, 0f)
        Matrix.rotateM(modele, 0, lacet, 0f, 1f, 0f)
        Matrix.scaleM(modele, 0, zoom, zoom, zoom)
        Matrix.multiplyMM(mv, 0, vue, 0, modele, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)
    }
}

/** Point projeté : position écran, profondeur (distance devant la caméra) et pixels par unité monde à cet endroit. */
private data class Projete(val ecran: Offset, val profondeur: Float, val pxParUnite: Float, val zVue: Float)

private fun projeter(mvp: FloatArray, mv: FloatArray, p: FloatArray, w: Float, h: Float, zoom: Float): Projete? {
    val clip = FloatArray(4)
    Matrix.multiplyMV(clip, 0, mvp, 0, floatArrayOf(p[0], p[1], p[2], 1f), 0)
    if (clip[3] < 0.05f) return null
    val vue = FloatArray(4)
    Matrix.multiplyMV(vue, 0, mv, 0, floatArrayOf(p[0], p[1], p[2], 1f), 0)
    val prof = -vue[2]
    val sx = (clip[0] / clip[3] + 1f) * w * 0.5f
    val sy = (1f - clip[1] / clip[3]) * h * 0.5f
    val ppu = zoom * (h * 0.5f) / (tan(Math.toRadians(FOV_Y / 2.0)).toFloat() * prof)
    return Projete(Offset(sx, sy), prof, ppu, vue[2])
}

/** [avecOpenGl] et [tempsFixe] servent uniquement aux captures d'écran hors appareil (pas d'OpenGL, image figée). */
@Composable
internal fun Orbe3DVue(
    c: Constellation, enTete: @Composable () -> Unit, onNote: (String) -> Unit, onHub: (String) -> Unit,
    avecOpenGl: Boolean = true, tempsFixe: Float? = null, onFichier: (String) -> Unit = {}, onBasculer: (String) -> Unit = {}
) {
    // Taille connue dès la composition (et non après le premier dessin) : décor et cadrage prêts tout de suite.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Orbe3DContenu(c, enTete, onNote, onHub, onFichier, onBasculer, avecOpenGl, tempsFixe,
            androidx.compose.ui.geometry.Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()))
    }
}

@Composable
private fun Orbe3DContenu(
    c: Constellation, enTete: @Composable () -> Unit, onNote: (String) -> Unit, onHub: (String) -> Unit, onFichier: (String) -> Unit,
    onBasculer: (String) -> Unit, avecOpenGl: Boolean, tempsFixe: Float?, taille: androidx.compose.ui.geometry.Size
) {
    val parId = remember(c) { c.etiquettes.associateBy { it.id } }
    val camera = remember { Camera3D() }
    val mesureur = rememberTextMeasurer(cacheSize = 200)
    val fontScale = LocalDensity.current.fontScale
    val dens = LocalDensity.current.density
    var temps by remember { mutableFloatStateOf(0f) }
    var lacetUser by remember { mutableFloatStateOf(0f) }
    var tangageUser by remember { mutableFloatStateOf(0f) }
    var zoomUser by remember { mutableFloatStateOf(1f) }
    var dernierGeste by remember { mutableFloatStateOf(-100f) }
    val surface = remember { arrayOfNulls<OrbeSurface>(1) }

    // Positions 3D de chaque élément de la constellation (hubs un peu en avant du dôme, étiquettes dessus).
    val pos3d = remember(c) {
        val m = HashMap<String, FloatArray>()
        c.hubs.forEach { m["hub:${it.cle}"] = versDome(it.pos, 0.25f) }
        // Les branches (sous-dossiers et leurs fichiers) restent presque à la profondeur de leur hub : sinon le dôme
        // les fait reculer et se tasser les unes sur les autres en bout de branche.
        c.etiquettes.forEach { e ->
            val p = versDome(e.pos, 0.12f)
            val h = c.hub(e.hub)
            if (h != null && h.type == TypeHub.DOSSIER) p[2] = max(p[2], versDome(h.pos, 0.12f)[2] - 0.12f)
            m[e.id] = p
        }
        m
    }
    // Recul de caméra : toute la constellation tient dans l'écran (portrait) au zoom 1.
    // La zone utile exclut l'en-tête (~64 dp) et la consigne du bas (~28 dp) ; l'image est recentrée sur la
    // constellation (et non sur la sphère) par un décalage vertical de la projection.
    val (distanceAuto, decalageAuto) = remember(c, taille) {
        val hx = max(abs(c.minX), abs(c.maxX)) / DP_PAR_UNITE + 0.4f
        val haut = -c.minY / DP_PAR_UNITE; val bas = -c.maxY / DP_PAR_UNITE
        val cy = (haut + bas) / 2f; val hy = (haut - bas) / 2f + 0.4f
        val tanY = tan(Math.toRadians(FOV_Y / 2.0)).toFloat()
        val h = if (taille.height > 0f) taille.height else 1600f
        val w = if (taille.width > 0f) taille.width else 800f
        val hDispo = (h - 92f * dens).coerceAtLeast(h * 0.6f)
        val d = max(hx / (tanY * w / h), hy / (tanY * hDispo / h)) * 1.06f + 0.6f
        val cibleNdc = 1f - 2f * (64f * dens + hDispo / 2f) / h
        d to (cibleNdc - cy / (tanY * d))
    }

    // Horloge d'animation : balancement doux de la caméra quand l'utilisateur ne touche pas l'écran.
    if (tempsFixe != null) temps = tempsFixe
    else LaunchedEffect(Unit) {
        var avant = 0L
        while (true) {
            withFrameNanos { t ->
                if (avant != 0L) temps += ((t - avant) / 1e9f).coerceAtMost(0.1f)
                avant = t
            }
        }
    }

    // Décor (serpent-dragon, veines, arbre, ruines, cercle runique) : rendu une fois, puis envoyé à OpenGL comme
    // texture plein écran derrière la sphère.
    val fond = rememberFondDragon(taille.width.toInt(), taille.height.toInt(), synchrone = !avecOpenGl)
    val dragon = rememberDragon(taille.width.toInt(), synchrone = !avecOpenGl)
    val outilsDragon = remember { OutilsDragon() }
    LaunchedEffect(fond) { fond?.let { f -> surface[0]?.rendu?.definirFond(f.asAndroidBitmap()) } }

    Box(Modifier.fillMaxSize().background(Color(0xFF030106))) {
        if (avecOpenGl) AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> OrbeSurface(ctx, camera).also { surface[0] = it; fond?.let { f -> it.rendu.definirFond(f.asAndroidBitmap()) } } }
        ) else Canvas(Modifier.fillMaxSize()) {
            // Substitut 2D de la sphère OpenGL (captures d'écran hors appareil uniquement).
            if (fond != null) drawImage(fond)
            val r = size.height * 0.5f / (tan(Math.toRadians(FOV_Y / 2.0)).toFloat() * camera.distance) * camera.zoom
            val ctr = Offset(size.width / 2f, size.height * 0.5f * (1f - camera.decalageNdcY))
            drawCircle(Brush.radialGradient(listOf(Color(0xFF2A1446), Color(0xFF0B0617), Color(0xFF020104)), ctr - Offset(r * 0.3f, r * 0.35f), r * 1.4f), r, ctr)
        }
        DisposableEffect(Unit) {
            onDispose { runCatching { surface[0]?.onPause() } }
        }
        val zones = remember { HashMap<String, Rect>() }
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        dernierGeste = temps
                        zoomUser = (zoomUser * zoom).coerceIn(0.6f, 3.2f)
                        lacetUser = (lacetUser + pan.x * 0.25f).coerceIn(-75f, 75f)
                        tangageUser = (tangageUser + pan.y * 0.2f).coerceIn(-60f, 60f)
                    }
                }
                .pointerInput(c) {
                    detectTapGestures(onLongPress = { p ->
                        // Appui long sur un cercle de dossier : son contenu complet.
                        zones.entries.firstOrNull { it.key.startsWith("hub:") && it.value.inflate(6f * dens).contains(p) }?.key?.let { onHub(it.removePrefix("hub:")) }
                    }) { tap ->
                        // Les étiquettes (dessinées au-dessus) passent avant les cercles.
                        val touches = zones.entries.filter { it.value.inflate(6f * dens).contains(tap) }.map { it.key }
                        val id = touches.firstOrNull { !it.startsWith("hub:") } ?: touches.firstOrNull() ?: return@detectTapGestures
                        when {
                            id.startsWith("hub:") -> {
                                val h = c.hub(id.removePrefix("hub:"))
                                if (h != null && h.depliable) onBasculer(h.cle) else onHub(id.removePrefix("hub:"))
                            }
                            else -> c.etiquettes.firstOrNull { it.id == id }?.let { e ->
                                when (e.type) {
                                    TypeEtiquette.NOTE -> onNote(e.noteId!!)
                                    TypeEtiquette.PLUS -> onHub(e.dossier ?: e.hub)
                                    TypeEtiquette.DOSSIER -> onHub(e.dossier!!)
                                    TypeEtiquette.FICHIER -> onFichier(e.chemin!!)
                                    else -> {}
                                }
                            }
                        }
                    }
                }
        ) {
            // Caméra mise à jour pendant le DESSIN (et non la composition) : seul ce Canvas est redessiné à
            // chaque image, l'écran n'est pas recomposé 60 fois par seconde.
            val calme = (temps - dernierGeste).coerceIn(0f, 4f) / 4f
            camera.lacet = lacetUser + calme * 18f * sin(temps * 0.22f)
            camera.tangage = (-8f + tangageUser + calme * 5f * sin(temps * 0.15f)).coerceIn(-75f, 75f)
            camera.zoom = zoomUser
            camera.distance = distanceAuto
            camera.decalageNdcY = decalageAuto
            camera.tempsCoeur = temps
            val mvp = FloatArray(16); val mv = FloatArray(16)
            camera.matrices(size.width, size.height, mvp, mv)
            fun proj(id: String) = pos3d[id]?.let { projeter(mvp, mv, it, size.width, size.height, camera.zoom) }
            val centre = projeter(mvp, mv, floatArrayOf(0f, 0f, 0f), size.width, size.height, camera.zoom) ?: return@Canvas
            val rCoeur = centre.pxParUnite * 1f
            val pulse = 0.84f + 0.16f * sin(temps * 2.2f)
            val profRef = camera.distance
            fun alphaProfondeur(p: Projete) = (1.1f - (p.profondeur - profRef) * 0.12f).coerceIn(0.72f, 1f)
            // Un élément derrière la sphère (plus loin que le centre et projeté sur son disque) est masqué.
            fun cache(p: Projete) = p.zVue < centre.zVue - 0.2f && (p.ecran - centre.ecran).getDistance() < rCoeur * 0.98f

            // Serpent-dragon animé autour de l'écran (sous le graphe, loin de la sphère centrale).
            if (dragon != null) dessinerDragon(dragon, outilsDragon, temps)
            // Halo autour de la sphère (anneau transparent au centre pour ne pas voiler l'obsidienne).
            drawCircle(
                Brush.radialGradient(
                    0f to Color.Transparent, 0.52f to Color.Transparent, 0.56f to Neon.violetVif.copy(alpha = 0.5f * pulse),
                    0.75f to Neon.violetProfond.copy(alpha = 0.18f), 1f to Color.Transparent,
                    center = centre.ecran, radius = rCoeur * 1.85f
                ), rCoeur * 1.85f, centre.ecran
            )

            // Toile cerveau → hubs, puis flèches hub → étiquettes.
            val hubsAnneau = c.hubs.filter { it.type != TypeHub.TAGS && it.niveau == 0 }
            val hubsProj = hubsAnneau.mapNotNull { h -> proj("hub:${h.cle}")?.let { h to it } }
            dessinerToile(centre.ecran, rCoeur, hubsProj.filterNot { cache(it.second) }.map { (h, p) -> p.ecran to h.rayon / DP_PAR_UNITE * p.pxParUnite }, rCoeur / DP_PAR_UNITE, pulse)
            // Catégorie → cercles de sous-dossiers.
            for (h in c.hubs) {
                val p = h.parent?.let { c.hub(it) } ?: continue
                val pp = proj("hub:${p.cle}") ?: continue
                val ph = proj("hub:${h.cle}") ?: continue
                if (cache(pp) || cache(ph)) continue
                val d = ph.ecran - pp.ecran
                val l = d.getDistance().coerceAtLeast(1f)
                val rP = p.rayon / DP_PAR_UNITE * pp.pxParUnite; val rH = h.rayon / DP_PAR_UNITE * ph.pxParUnite
                dessinerLienDossier(pp.ecran + d * (rP * 1.13f / l), pp.ecran + d * ((l - rH * 1.16f).coerceAtLeast(0f) / l), ph.pxParUnite / DP_PAR_UNITE, pulse, alphaProfondeur(ph))
            }
            for (e in c.etiquettes) {
                if (e.type == TypeEtiquette.TAG) continue
                val h = c.hub(e.hub) ?: continue
                val pe = proj(e.id) ?: continue
                if (cache(pe)) continue
                // Fichier d'un sous-dossier : la branche part de l'étiquette du sous-dossier.
                val par = e.parent?.let { parId[it] }
                if (par != null) {
                    val pp = proj(par.id) ?: continue
                    if (cache(pp)) continue
                    fleche(pp.ecran, pe.ecran, Neon.lavande, 1.1f * pe.pxParUnite / DP_PAR_UNITE, alphaProfondeur(pe) * 0.75f)
                    continue
                }
                val ph = proj("hub:${h.cle}") ?: continue
                val rH = h.rayon / DP_PAR_UNITE * ph.pxParUnite
                val d = pe.ecran - ph.ecran
                val l = d.getDistance().coerceAtLeast(1f)
                val a = ph.ecran + d * (rH * 1.13f / l)
                val ke = pe.pxParUnite / DP_PAR_UNITE
                val b = ph.ecran + d * ((l - (e.w / 2f + 6f) * ke * abs(d.x / l) - (e.h / 2f + 6f) * ke * abs(d.y / l)).coerceAtLeast(0f) / l)
                fleche(a, b, Neon.violet, 1.4f * ke, alphaProfondeur(pe) * 0.95f)
            }
            c.hub("__tags")?.let { _ ->
                val pt = proj("hub:__tags")
                if (pt != null && !cache(pt)) {
                    drawLine(Neon.violet.copy(alpha = 0.55f), centre.ecran + Offset(0f, rCoeur), pt.ecran, 1.4f * pt.pxParUnite / DP_PAR_UNITE)
                    c.etiquettes.filter { it.type == TypeEtiquette.TAG }.forEach { e ->
                        proj(e.id)?.let { pe -> drawLine(Neon.violet.copy(alpha = 0.4f), pt.ecran, pe.ecran, 1f * pe.pxParUnite / DP_PAR_UNITE) }
                    }
                }
            }
            // Vrais liens [[wikilinks]] (flèche vers la note citée).
            val parNote = c.etiquettes.filter { it.noteId != null }.associateBy { it.noteId!! }
            for (l in c.liens) {
                val ea = parNote[l.de] ?: continue; val eb = parNote[l.vers] ?: continue
                val pa = proj(ea.id) ?: continue; val pb = proj(eb.id) ?: continue
                if (cache(pa) || cache(pb)) continue
                fleche(pa.ecran, pb.ecran, Neon.lavande, 1.5f * pb.pxParUnite / DP_PAR_UNITE, 0.75f, courbe = courbeVersCentre(pa.ecran, pb.ecran, centre.ecran))
            }

            // Cerveau néon par-dessus la sphère d'obsidienne 3D (liseré, cerveau, textes).
            drawCircle(Neon.lavande.copy(alpha = 0.9f), rCoeur, centre.ecran, style = Stroke(max(rCoeur * 0.02f, 1.5f)))
            drawCircle(Neon.violetVif.copy(alpha = 0.35f), rCoeur * 1.03f, centre.ecran, style = Stroke(rCoeur * 0.07f))
            dessinerNoyau(centre.ecran, rCoeur, pulse, mesureur, fontScale, sphere = false, sousTitre = "Orbe Obsidienne", cerveau = dragon?.cerveau)

            // Hubs et étiquettes, du plus lointain au plus proche (les proches passent devant).
            zones.clear()
            data class Element(val id: String, val p: Projete)
            val elements = (c.hubs.map { "hub:${it.cle}" } + c.etiquettes.map { it.id })
                .mapNotNull { id -> proj(id)?.let { Element(id, it) } }
                .sortedByDescending { it.p.profondeur }
            for (el in elements) {
                if (cache(el.p)) continue
                val a = alphaProfondeur(el.p)
                val kk = el.p.pxParUnite / DP_PAR_UNITE
                if (el.id.startsWith("hub:")) {
                    val h = c.hub(el.id.removePrefix("hub:")) ?: continue
                    if (h.type == TypeHub.TAGS) dessinerHubTags(h, el.p.ecran, kk, mesureur, fontScale, a)
                    else {
                        val r = h.rayon * kk
                        dessinerHub(h, el.p.ecran, r, mesureur, fontScale, a, pulse = pulse)
                        if (h.depliable) dessinerBadgeDepli(el.p.ecran + Offset(0f, r * 1.02f), max(r * 0.22f, 7f * kk), h.deplie, a)
                        zones[el.id] = Rect(el.p.ecran - Offset(r, r), androidx.compose.ui.geometry.Size(2 * r, 2 * r))
                    }
                } else {
                    val e = c.etiquettes.firstOrNull { it.id == el.id } ?: continue
                    zones[e.id] = dessinerEtiquette(e, el.p.ecran, kk, mesureur, fontScale, a, taillePxMin = 8f * dens)
                }
            }
        }

        // En-tête : titre + bascule 2D/3D.
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
                .background(Neon.fondPanneau, RoundedCornerShape(12.dp))
                .border(1.dp, Neon.violet.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconeCompose(Icone.CERVEAU, 18.dp)
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text("Orbe Obsidienne", color = Neon.blancViolet, fontSize = 14.sp, fontFamily = Neon.serif, fontWeight = FontWeight.Bold)
                Text("${c.totalNotes} notes · ${c.totalLiens} liens · ${c.totalPieces} pièces", color = Neon.sousTitre, fontSize = 10.sp, fontFamily = Neon.serif)
            }
            enTete()
        }
        Text(
            "Glisser pour tourner · pincer pour zoomer · toucher une note",
            color = Neon.sousTitre.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = Neon.serif,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
        )
        if (c.vide) Text(
            "Aucune note trouvée : choisis ton coffre Obsidian dans Réglages.",
            color = Neon.sousTitre, fontSize = 13.sp, fontFamily = Neon.serif,
            modifier = Modifier.align(Alignment.Center).padding(32.dp)
        )
    }
}

// ================================================================================================ OpenGL

private class OrbeSurface(context: Context, camera: Camera3D) : GLSurfaceView(context) {
    val rendu = OrbeRenderer(camera)
    init {
        setEGLContextClientVersion(2)
        setRenderer(rendu)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}

/**
 * Rendu OpenGL : étoiles (parallaxe), sphère d'obsidienne au centre (veines violettes, reflets, liseré
 * fresnel) et deux anneaux orbitaux inclinés. Toute erreur de shader est attrapée : la scène reste alors
 * simplement noire derrière la couche Compose, au lieu de faire planter l'application.
 */
private class OrbeRenderer(private val camera: Camera3D) : GLSurfaceView.Renderer {
    private var programme = 0
    private var programmePoints = 0
    private var sphere: FloatBuffer? = null
    private var nbSphere = 0
    private var etoiles: FloatBuffer? = null
    private var nbEtoiles = 0
    private var anneaux: FloatBuffer? = null
    private var nbAnneau = 0
    private var largeur = 1f
    private var hauteur = 1f
    private var ok = false
    private var programmeFond = 0
    private var textureFond = 0
    private var quad: FloatBuffer? = null
    /** Décor à envoyer en texture (posé depuis le thread UI, chargé sur le thread OpenGL). */
    @Volatile private var fondEnAttente: android.graphics.Bitmap? = null
    @Volatile private var fondCourant: android.graphics.Bitmap? = null

    fun definirFond(b: android.graphics.Bitmap) { if (b !== fondCourant) fondEnAttente = b }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.012f, 0.005f, 0.024f, 1f)
        // Contexte OpenGL (re)créé : l'ancienne texture n'existe plus, on recharge le décor.
        textureFond = 0
        fondCourant?.let { if (fondEnAttente == null) fondEnAttente = it }
        fondCourant = null
        ok = runCatching {
            programmeFond = creerProgramme(VS_FOND, FS_FOND)
            quad = tampon(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            programme = creerProgramme(VS_SPHERE, FS_SPHERE)
            programmePoints = creerProgramme(VS_POINTS, FS_POINTS)
            sphere = creerSphere(64, 48); nbSphere = 64 * 48 * 6
            val r = Random(11)
            val e = FloatArray(420 * 4)
            for (i in 0 until 420) {
                val u = r.nextFloat() * 2f - 1f; val t = r.nextFloat() * 2f * PI.toFloat()
                val s = kotlin.math.sqrt(1f - u * u); val rad = 7f + r.nextFloat() * 6f
                e[i * 4] = s * cos(t) * rad; e[i * 4 + 1] = u * rad; e[i * 4 + 2] = s * sin(t) * rad; e[i * 4 + 3] = r.nextFloat()
            }
            etoiles = tampon(e); nbEtoiles = 420
            // Deux anneaux orbitaux autour de la sphère (cercles inclinés), en points serrés pour un trait lumineux.
            val n = 360
            val a = FloatArray(n * 2 * 4)
            for (i in 0 until n) {
                val t = 2f * PI.toFloat() * i / n
                val r1 = 1.32f
                a[i * 4] = cos(t) * r1; a[i * 4 + 1] = sin(t) * r1 * 0.22f; a[i * 4 + 2] = sin(t) * r1; a[i * 4 + 3] = 0.6f + 0.4f * sin(t * 3f)
                val r2 = 1.55f; val j = n + i
                a[j * 4] = cos(t) * r2; a[j * 4 + 1] = cos(t) * r2 * 0.35f; a[j * 4 + 2] = sin(t) * r2 * 0.9f; a[j * 4 + 3] = 0.5f + 0.5f * cos(t * 2f)
            }
            anneaux = tampon(a); nbAnneau = n * 2
            true
        }.getOrDefault(false)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        largeur = width.toFloat(); hauteur = height.toFloat()
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!ok) return
        runCatching { dessiner() }
    }

    private fun dessiner() {
        val mvp = FloatArray(16); val mv = FloatArray(16); val modele = FloatArray(16)
        camera.matrices(largeur, hauteur, mvp, mv, modele)

        // Décor plein écran (serpent-dragon…), léger effet de parallaxe quand la caméra tourne.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        fondEnAttente?.let { b ->
            fondEnAttente = null
            if (!b.isRecycled) {
                if (textureFond == 0) { val t = IntArray(1); GLES20.glGenTextures(1, t, 0); textureFond = t[0] }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureFond)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
                fondCourant = b
            }
        }
        if (textureFond != 0 && fondCourant != null) {
            GLES20.glUseProgram(programmeFond)
            val aPos = GLES20.glGetAttribLocation(programmeFond, "aPos")
            val q = quad
            if (q != null) {
                q.position(0); GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, q)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureFond)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(programmeFond, "uTex"), 0)
                GLES20.glUniform2f(GLES20.glGetUniformLocation(programmeFond, "uDecalage"),
                    (camera.lacet / 75f).coerceIn(-1f, 1f) * 0.012f, (camera.tangage / 75f).coerceIn(-1f, 1f) * -0.012f)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(aPos)
            }
        }

        // Étoiles (additif, sans profondeur) : plus discrètes quand le décor est présent (il a ses propres étoiles).
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        dessinerPoints(etoiles, nbEtoiles, mvp, 2.2f, 0.85f, 0.78f, 1f, if (fondCourant != null) 0.35f else 0.75f)

        // Sphère d'obsidienne (tourne lentement sur elle-même, indépendamment de la caméra).
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        val modeleSphere = FloatArray(16)
        System.arraycopy(modele, 0, modeleSphere, 0, 16)
        Matrix.rotateM(modeleSphere, 0, camera.tempsCoeur * 7f, 0.2f, 1f, 0f)
        val proj = camera.projection(largeur, hauteur); val vue = FloatArray(16); val mvpSphere = FloatArray(16); val tmp = FloatArray(16)
        Matrix.setLookAtM(vue, 0, 0f, 0f, camera.distance, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(tmp, 0, vue, 0, modeleSphere, 0)
        Matrix.multiplyMM(mvpSphere, 0, proj, 0, tmp, 0)
        GLES20.glUseProgram(programme)
        val aPos = GLES20.glGetAttribLocation(programme, "aPosition")
        val aNor = GLES20.glGetAttribLocation(programme, "aNormal")
        val b = sphere ?: return
        b.position(0); GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, b)
        b.position(3); GLES20.glEnableVertexAttribArray(aNor); GLES20.glVertexAttribPointer(aNor, 3, GLES20.GL_FLOAT, false, 24, b)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programme, "uMvp"), 1, false, mvpSphere, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programme, "uModele"), 1, false, modeleSphere, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(programme, "uOeil"), 0f, 0f, camera.distance)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programme, "uTemps"), camera.tempsCoeur)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, nbSphere)
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aNor)

        // Anneaux orbitaux : testés en profondeur (la sphère cache la partie arrière), sans écrire la profondeur.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        dessinerPoints(anneaux, nbAnneau, mvp, 3.2f, 0.72f, 0.45f, 1f, 0.55f)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun dessinerPoints(b: FloatBuffer?, n: Int, mvp: FloatArray, taille: Float, r: Float, g: Float, bl: Float, a: Float) {
        if (b == null || n == 0) return
        GLES20.glUseProgram(programmePoints)
        val aPos = GLES20.glGetAttribLocation(programmePoints, "aPosition")
        val aEc = GLES20.glGetAttribLocation(programmePoints, "aEclat")
        b.position(0); GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 16, b)
        b.position(3); GLES20.glEnableVertexAttribArray(aEc); GLES20.glVertexAttribPointer(aEc, 1, GLES20.GL_FLOAT, false, 16, b)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programmePoints, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programmePoints, "uTaille"), taille * max(1f, hauteur / 900f))
        GLES20.glUniform4f(GLES20.glGetUniformLocation(programmePoints, "uCouleur"), r, g, bl, a)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, n)
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aEc)
    }

    private fun tampon(v: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

    private fun creerSphere(piles: Int, tranches: Int): FloatBuffer {
        val v = FloatArray(piles * tranches * 6 * 6)
        var k = 0
        fun sommet(phi: Double, theta: Double) {
            val x = (sin(phi) * cos(theta)).toFloat(); val y = cos(phi).toFloat(); val z = (sin(phi) * sin(theta)).toFloat()
            v[k++] = x; v[k++] = y; v[k++] = z; v[k++] = x; v[k++] = y; v[k++] = z
        }
        for (i in 0 until piles) {
            val p0 = PI * i / piles; val p1 = PI * (i + 1) / piles
            for (j in 0 until tranches) {
                val t0 = 2 * PI * j / tranches; val t1 = 2 * PI * (j + 1) / tranches
                sommet(p0, t0); sommet(p1, t0); sommet(p1, t1)
                sommet(p0, t0); sommet(p1, t1); sommet(p0, t1)
            }
        }
        return tampon(v)
    }

    private fun creerProgramme(vs: String, fs: String): Int {
        val v = shader(GLES20.GL_VERTEX_SHADER, vs); val f = shader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
        val st = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0)
        check(st[0] == GLES20.GL_TRUE) { "Liaison du shader impossible : " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val st = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0)
        check(st[0] == GLES20.GL_TRUE) { "Compilation du shader impossible : " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    companion object {
        const val VS_FOND = """
            attribute vec2 aPos;
            uniform vec2 uDecalage;
            varying vec2 vUv;
            void main() {
                vUv = vec2((aPos.x + 1.0) * 0.5, (1.0 - aPos.y) * 0.5) * 0.97 + vec2(0.015) + uDecalage;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """
        const val FS_FOND = """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTex, vUv); }
        """
        const val VS_POINTS = """
            uniform mat4 uMvp;
            uniform float uTaille;
            attribute vec3 aPosition;
            attribute float aEclat;
            varying float vEclat;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                gl_PointSize = uTaille * (0.6 + aEclat);
                vEclat = aEclat;
            }
        """
        const val FS_POINTS = """
            precision mediump float;
            uniform vec4 uCouleur;
            varying float vEclat;
            void main() {
                vec2 p = gl_PointCoord - vec2(0.5);
                float halo = 1.0 - smoothstep(0.05, 0.5, length(p));
                gl_FragColor = vec4(uCouleur.rgb, uCouleur.a * halo * (0.35 + 0.65 * vEclat));
            }
        """
        const val VS_SPHERE = """
            uniform mat4 uMvp;
            uniform mat4 uModele;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            varying vec3 vMonde;
            varying vec3 vNormale;
            varying vec3 vLocal;
            void main() {
                vMonde = (uModele * vec4(aPosition, 1.0)).xyz;
                vNormale = normalize(mat3(uModele) * aNormal);
                vLocal = aPosition;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """
        const val FS_SPHERE = """
            precision mediump float;
            uniform vec3 uOeil;
            uniform float uTemps;
            varying vec3 vMonde;
            varying vec3 vNormale;
            varying vec3 vLocal;
            float h31(vec3 p) { p = fract(p * 0.1031); p += dot(p, p.yzx + 33.33); return fract((p.x + p.y) * p.z); }
            float bruit(vec3 p) {
                vec3 i = floor(p); vec3 f = fract(p); f = f * f * (3.0 - 2.0 * f);
                return mix(mix(mix(h31(i), h31(i + vec3(1.0, 0.0, 0.0)), f.x), mix(h31(i + vec3(0.0, 1.0, 0.0)), h31(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
                           mix(mix(h31(i + vec3(0.0, 0.0, 1.0)), h31(i + vec3(1.0, 0.0, 1.0)), f.x), mix(h31(i + vec3(0.0, 1.0, 1.0)), h31(i + vec3(1.0, 1.0, 1.0)), f.x), f.y), f.z);
            }
            float fbm(vec3 p) { float v = 0.0; float a = 0.5; for (int i = 0; i < 4; i++) { v += bruit(p) * a; p *= 2.03; a *= 0.5; } return v; }
            void main() {
                vec3 n = normalize(vNormale);
                vec3 vue = normalize(uOeil - vMonde);
                vec3 l1 = normalize(vec3(-0.6, 0.75, 0.9));
                vec3 l2 = normalize(vec3(0.8, -0.3, 0.5));
                float diff = max(dot(n, l1), 0.0);
                float spec = pow(max(dot(reflect(-l1, n), vue), 0.0), 60.0);
                float spec2 = pow(max(dot(reflect(-l2, n), vue), 0.0), 24.0);
                float fres = pow(1.0 - max(dot(n, vue), 0.0), 2.6);
                float deform = fbm(vLocal * 3.2 + vec3(0.0, uTemps * 0.05, 0.0));
                float fissure = abs(fbm(vLocal * 6.5 + deform * 2.4) - 0.5);
                float veines = 1.0 - smoothstep(0.008, 0.045, fissure);
                float grain = fbm(vLocal * 22.0);
                vec3 base = vec3(0.012, 0.008, 0.022) + grain * vec3(0.02, 0.012, 0.035);
                vec3 couleur = base * (0.45 + 0.55 * diff);
                couleur += vec3(0.34, 0.1, 0.7) * veines * (0.35 + 0.5 * deform);
                couleur += vec3(0.45, 0.22, 0.85) * fres * 0.85;
                couleur += vec3(0.85, 0.7, 1.0) * spec * 0.9 + vec3(0.55, 0.3, 1.0) * spec2 * 0.35;
                gl_FragColor = vec4(couleur, 1.0);
            }
        """
    }
}
