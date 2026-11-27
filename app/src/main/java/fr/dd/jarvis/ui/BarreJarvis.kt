package fr.dd.jarvis.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/** Onglets rangés dans « Plus » (indices de l'écran principal). */
val ONGLETS_PLUS = setOf(3, 4, 5, 6, 7, 8, 9)

private val FormeBarre = CutCornerShape(topStart = 18.dp, topEnd = 18.dp)

/**
 * Barre du bas : Accueil, Chat, le bouton JARVIS au centre (toucher : parler ; appui long : conversation), Agenda,
 * Plus (tous les autres écrans en tuiles).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BarreJarvis(
    onglet: Int, plusOuvert: Boolean, ecoute: Boolean, occupe: Boolean, sons: Boolean,
    onOnglet: (Int) -> Unit, onPlus: () -> Unit, onJarvis: () -> Unit, onJarvisLong: () -> Unit,
) {
    val accent = LocalAccent.current
    val style = LocalStyleHud.current
    Box(Modifier.fillMaxWidth().height(84.dp)) {
        // Socle de la barre.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(66.dp)
                .clip(FormeBarre)
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.16f), HudBg.copy(alpha = 0.96f))))
                .bordureHud(accent, FormeBarre, 1.2.dp, base = 0.55f)
        )
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(66.dp), verticalAlignment = Alignment.CenterVertically) {
            ItemBarre("Accueil", Icons.Filled.Dashboard, onglet == 0 && !plusOuvert, Modifier.weight(1f)) { SonsHud.clic(style, sons); onOnglet(0) }
            ItemBarre("Chat", Icons.Filled.Forum, onglet == 1 && !plusOuvert, Modifier.weight(1f)) { SonsHud.clic(style, sons); onOnglet(1) }
            Spacer(Modifier.weight(1.15f))
            ItemBarre("Agenda", Icons.Filled.CalendarMonth, onglet == 2 && !plusOuvert, Modifier.weight(1f)) { SonsHud.clic(style, sons); onOnglet(2) }
            ItemBarre("Plus", Icons.Filled.GridView, plusOuvert || onglet in ONGLETS_PLUS, Modifier.weight(1f)) { SonsHud.ouverture(style, sons); onPlus() }
        }
        // Bouton JARVIS : orbe lumineux qui dépasse de la barre.
        Box(
            Modifier.align(Alignment.TopCenter).size(70.dp).clip(CircleShape)
                .combinedClickable(onClick = { SonsHud.ouverture(style, sons); onJarvis() }, onLongClick = onJarvisLong),
            contentAlignment = Alignment.Center
        ) {
            OrbeJarvis(Modifier.fillMaxSize(), ecoute = ecoute, occupe = occupe)
            Icon(if (ecoute) Icons.Filled.Mic else Icons.Filled.MicNone, "Parler à JARVIS", tint = if (ecoute) Color.White else accent, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun ItemBarre(nom: String, icone: ImageVector, choisi: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Column(
        modifier.height(62.dp).clickable(onClick = onClick).padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Box(
            Modifier.size(width = 46.dp, height = 28.dp).clip(HudShapeSmall)
                .background(if (choisi) accent.copy(alpha = 0.18f) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) { Icon(icone, null, tint = if (choisi) accent else HudDim, modifier = Modifier.size(22.dp)) }
        Text(nom.uppercase(), color = if (choisi) accent else HudDim, fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace,
            fontWeight = if (choisi) FontWeight.Bold else FontWeight.Normal, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        // Trait lumineux sous l'onglet choisi.
        Box(Modifier.padding(top = 3.dp).size(width = if (choisi) 22.dp else 0.dp, height = 2.dp).background(accent))
    }
}

/** Orbe du bouton central : anneaux qui tournent (plus vite quand JARVIS réfléchit), cœur qui pulse quand il écoute. */
@Composable
fun OrbeJarvis(modifier: Modifier, ecoute: Boolean, occupe: Boolean) {
    val accent = LocalAccent.current
    val phase = LocalPhaseHud.current
    val style = LocalStyleHud.current
    Canvas(modifier) {
        val p = phase.value
        val c = center
        val r = size.minDimension / 2f
        val tours = if (occupe) 8f else if (style == StyleHud.IRONMAN) 3f else 1.5f
        val pulse = 0.5f + 0.5f * sin(2f * PI.toFloat() * p * (if (ecoute) 12f else 4f))
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = if (ecoute) 0.75f else 0.35f), HudBg), c, r), r, c)
        drawCircle(accent.copy(alpha = 0.9f), r * 0.97f, c, style = Stroke(1.5.dp.toPx()))
        if (ecoute) drawCircle(accent.copy(alpha = 0.25f + 0.35f * pulse), r * (0.55f + 0.12f * pulse), c)
        rotate(p * 360f * tours, c) {
            drawArc(accent, 10f, 70f, false, Offset(c.x - r * 0.8f, c.y - r * 0.8f), Size(r * 1.6f, r * 1.6f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            drawArc(accent, 190f, 70f, false, Offset(c.x - r * 0.8f, c.y - r * 0.8f), Size(r * 1.6f, r * 1.6f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        rotate(-p * 360f * tours * 1.4f, c) {
            drawArc(accent.copy(alpha = 0.6f), 100f, 60f, false, Offset(c.x - r * 0.62f, c.y - r * 0.62f), Size(r * 1.24f, r * 1.24f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            drawArc(accent.copy(alpha = 0.6f), 280f, 60f, false, Offset(c.x - r * 0.62f, c.y - r * 0.62f), Size(r * 1.24f, r * 1.24f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/** Une tuile du panneau « Plus ». [cible] : indice d'onglet (≥ 0) ou écran par-dessus (« terrain »). */
data class TuilePlus(val icone: String, val nom: String, val onglet: Int = -1, val terrain: String = "", val etat: String = "")

/** Panneau « Plus » : tous les écrans et raccourcis en tuiles, par-dessus l'écran en cours. */
@Composable
fun PanneauPlus(app: JarvisApp, ongletCourant: Int, onOnglet: (Int) -> Unit, onTerrain: (String) -> Unit, onFermer: () -> Unit) {
    val accent = LocalAccent.current
    val style = LocalStyleHud.current
    androidx.activity.compose.BackHandler { onFermer() }
    var aTraiter by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        aTraiter = withContext(Dispatchers.IO) {
            runCatching { fr.dd.jarvis.device.Courrier.liste(app).count { !it.traite } }.getOrDefault(0).let { if (it > 0) "$it à traiter" else "" }
        }
    }
    val brouillons = runCatching { app.societe.data.brouillonsRapport.size }.getOrDefault(0)
    val ecrans = listOf(
        TuilePlus("🏢", "Société", onglet = 5, etat = if (brouillons > 0) "$brouillons brouillon(s)" else "clients · devis · factures"),
        TuilePlus("📬", "Courrier", onglet = 6, etat = aTraiter.ifBlank { "colis · factures · rdv" }),
        TuilePlus("✉️", "Mails", onglet = 3, etat = "${app.store.gmailAccounts.size} compte(s)"),
        TuilePlus("👥", "Contacts", onglet = 4, etat = "carnet & fiches"),
        TuilePlus("🕸️", "Graphe", onglet = 7, etat = "mémoire Obsidian"),
        TuilePlus("📶", "Wi-Fi", onglet = 8, etat = "appareils du réseau"),
        TuilePlus("⚙️", "Réglages", onglet = 9, etat = "6 rubriques"),
    )
    val raccourcis = listOf(
        TuilePlus("📝", "Compte rendu", terrain = "compte_rendu"),
        TuilePlus("👁️", "Regarder", terrain = "regarder"),
        TuilePlus("🎵", "Musique", terrain = "musique"),
        TuilePlus("⏰", "Rappels", terrain = "rappels"),
        TuilePlus("🩺", "Santé", terrain = "sante"),
        TuilePlus("🎙️", "Messages vocaux", terrain = "messages_vocaux"),
        TuilePlus("🐉", "Mode présence", terrain = "presence"),
    )
    GrillePlus(ecrans, raccourcis, ongletCourant,
        onEcran = { t -> SonsHud.clic(style, app.store.sonsInterface.value); onOnglet(t.onglet) },
        onRaccourci = { t -> SonsHud.clic(style, app.store.sonsInterface.value); onTerrain(t.terrain) })
}

/** Grille du panneau « Plus » (tuiles fournies : testable sans l'application). */
@Composable
fun GrillePlus(ecrans: List<TuilePlus>, raccourcis: List<TuilePlus>, ongletCourant: Int, onEcran: (TuilePlus) -> Unit, onRaccourci: (TuilePlus) -> Unit) {
    Box(
        Modifier.fillMaxSize().background(HudBg.copy(alpha = 0.94f)).clickable(enabled = true, onClick = {})
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(span = { GridItemSpan(2) }) { EnteteSection("ÉCRANS") }
            items(ecrans, key = { "e" + it.nom }) { t ->
                TuileHud(t.icone, t.nom, t.etat, choisi = t.onglet == ongletCourant) { onEcran(t) }
            }
            item(span = { GridItemSpan(2) }) { EnteteSection("RACCOURCIS") }
            items(raccourcis, key = { "r" + it.nom }) { t ->
                TuileHud(t.icone, t.nom, t.etat, compacte = true) { onRaccourci(t) }
            }
            item(span = { GridItemSpan(2) }) {
                Text("Astuce : appui long sur le bouton JARVIS pour ouvrir la conversation.", color = HudDim, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
fun EnteteSection(titre: String) {
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 12.dp).background(accent))
        Text(titre, color = accent, fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 8.dp))
        Box(Modifier.padding(start = 10.dp).weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.6f), Color.Transparent))))
    }
}

/** Tuile holographique : icône, nom, état en direct (une ligne). */
@Composable
fun TuileHud(icone: String, nom: String, etat: String, modifier: Modifier = Modifier, choisi: Boolean = false, compacte: Boolean = false, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Column(
        modifier.fillMaxWidth().height(if (compacte) 64.dp else 96.dp)
            .clip(HudShape)
            .background(Brush.linearGradient(listOf(accent.copy(alpha = if (choisi) 0.30f else 0.15f), accent.copy(alpha = 0.03f))))
            .bordureHud(accent, HudShape, 1.5.dp, base = if (choisi) 0.95f else 0.6f)
            .clickable(onClick = onClick)
            .drawBehind {
                // Repères d'angle (haut-droit, bas-gauche).
                val l = 10.dp.toPx(); val w = 2.dp.toPx(); val c = accent.copy(alpha = 0.9f)
                drawLine(c, Offset(size.width - l - 6.dp.toPx(), w), Offset(size.width - 6.dp.toPx(), w), w)
                drawLine(c, Offset(6.dp.toPx(), size.height - w), Offset(6.dp.toPx() + l, size.height - w), w)
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = if (compacte) Arrangement.Center else Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icone, fontSize = if (compacte) 18.sp else 22.sp)
            Text(nom.uppercase(), color = if (choisi) Color.White else HudText, fontSize = if (compacte) 11.sp else 12.sp, letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp))
        }
        if (etat.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
            Text(etat, color = HudDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
        }
    }
}
