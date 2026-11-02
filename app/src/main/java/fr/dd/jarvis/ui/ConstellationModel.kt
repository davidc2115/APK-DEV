package fr.dd.jarvis.ui

import fr.dd.jarvis.device.ObsidianWriter
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Modèle commun du « Cerveau / Graphe Obsidian » (vue 2D et Orbe 3D), dans le style de l'image de référence :
 * un CERVEAU central, un hub par dossier racine RÉEL du coffre (icône + titre + sous-titre), les notes en
 * étiquettes autour de leur hub, un hub « Pièces jointes » (images, PDF, audio, vidéos réellement présents),
 * les #tags réellement écrits dans les notes, et les panneaux « Mentions non liées » / « Fichiers orphelins ».
 *
 * Règle de l'architecture Orbe : rien n'est inventé pour décorer — aucun dossier, note, tag, lien ou pièce
 * jointe fictif. Un coffre de 8 notes donne un graphe de 8 notes.
 *
 * Coordonnées « monde » en dp à l'échelle 1, centre du cerveau en (0,0), pensées pour un écran portrait.
 */

enum class Icone { CERVEAU, DOSSIER, PERSONNE, TRAVAIL, RECHERCHE, ARCHIVE, TROMBONE, JOURNAL, SOLEIL, ETOILE, ETIQUETTE, IMAGE, VIDEO, AUDIO, PDF, NOTE, FICHIER }

enum class TypeHub { DOSSIER, PIECES, TAGS }

enum class TypeEtiquette { NOTE, PIECE, TAG, PLUS }

data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(k: Float) = Pt(x * k, y * k)
    fun len() = hypot(x, y)
    fun norm(): Pt { val l = len(); return if (l < 1e-4f) Pt(0f, -1f) else Pt(x / l, y / l) }
}

data class Hub(
    val cle: String,
    val titre: String,
    val sousTitre: String,
    val icone: Icone,
    val type: TypeHub,
    val total: Int,
    val pos: Pt,
    val rayon: Float
)

data class Etiquette(
    val id: String,
    val texte: String,
    val icone: Icone,
    val type: TypeEtiquette,
    val hub: String,
    /** Note Obsidian (id sans .md) pour TypeEtiquette.NOTE. */
    val noteId: String? = null,
    /** Tag (sans #) pour TypeEtiquette.TAG. */
    val tag: String? = null,
    val pos: Pt,
    val w: Float,
    val h: Float
)

/** Vrai lien [[wikilink]] orienté entre deux notes affichées. */
data class LienReel(val de: String, val vers: String)

data class Constellation(
    val hubs: List<Hub>,
    val etiquettes: List<Etiquette>,
    val liens: List<LienReel>,
    /** Tous les liens réels du coffre (y compris entre notes non affichées), pour la fiche d'une note. */
    val tousLiens: List<LienReel>,
    /** Notes qu'aucune autre note ne cite (aucun lien entrant). */
    val sansLienEntrant: List<String>,
    /** Notes sans aucun lien (ni entrant ni sortant) + pièces jointes citées nulle part. */
    val orphelins: List<String>,
    val notesParHub: Map<String, List<ObsidianWriter.GraphNode>>,
    val tagsParNote: Map<String, List<String>>,
    val totalNotes: Int,
    val totalLiens: Int,
    val totalTags: Int,
    val totalPieces: Int,
    val rayonCerveau: Float,
    val minX: Float, val minY: Float, val maxX: Float, val maxY: Float
) {
    val vide get() = totalNotes == 0 && totalPieces == 0
    fun hub(cle: String) = hubs.firstOrNull { it.cle == cle }
}

object ConstellationLayout {
    const val RAYON_CERVEAU = 80f
    const val RAYON_HUB = 46f
    const val H_ETIQUETTE = 24f
    private const val MAX_NOTES_PAR_HUB = 5
    private const val MAX_HUBS_DOSSIER = 7
    private const val MAX_TAGS = 6

    /** Largeur estimée d'une étiquette (police serif ~11sp) : icône + texte + marges. */
    fun largeurEtiquette(texte: String, avecIcone: Boolean = true): Float =
        (if (avecIcone) 22f else 8f) + texte.length * 6.1f + 12f

    fun racine(folder: String): String = folder.substringBefore('/').trim().ifBlank { "Racine" }

    private fun sansAccents(s: String) =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

    fun iconeDossier(nom: String): Icone {
        val n = sansAccents(nom)
        return when {
            n == "racine" -> Icone.NOTE
            listOf("contact", "client", "personnel", "famille", "perso", "ami").any { it in n } -> Icone.PERSONNE
            listOf("societe", "travail", "entreprise", "facture", "devis", "pro", "boulot", "compta").any { it in n } -> Icone.TRAVAIL
            listOf("recherche", "veille", "apprendre", "cours", "doc").any { it in n } -> Icone.RECHERCHE
            "archive" in n -> Icone.ARCHIVE
            listOf("journal", "memo", "memoire", "note").any { it in n } -> Icone.JOURNAL
            listOf("point", "matin", "briefing", "agenda", "planning").any { it in n } -> Icone.SOLEIL
            "jarvis" in n -> Icone.ETOILE
            listOf("image", "photo").any { it in n } -> Icone.IMAGE
            listOf("video", "tiktok", "film").any { it in n } -> Icone.VIDEO
            listOf("audio", "musique", "son").any { it in n } -> Icone.AUDIO
            listOf("piece", "fichier", "attachment").any { it in n } -> Icone.TROMBONE
            else -> Icone.DOSSIER
        }
    }

    private fun iconePiece(kind: String) = when (kind) {
        "IMAGE" -> Icone.IMAGE; "PDF" -> Icone.PDF; "AUDIO" -> Icone.AUDIO; "VIDEO" -> Icone.VIDEO; else -> Icone.FICHIER
    }

    private fun libellePiece(kind: String) = when (kind) {
        "IMAGE" -> "Images"; "PDF" -> "PDF"; "AUDIO" -> "Audio"; "VIDEO" -> "Vidéos"; else -> "Fichiers"
    }

    private fun pluriel(n: Int, un: String, plusieurs: String) = "$n ${if (n > 1) plusieurs else un}"

    fun construire(data: ObsidianWriter.GraphData): Constellation {
        val notes = data.nodes.filterNot { it.ghost }
        val ids = notes.map { it.id }.toHashSet()
        // Liens réels orientés entre notes existantes (les cibles absentes ne deviennent pas des nœuds).
        val liensTous = data.references.filter { it.resolved && it.from in ids && it.to in ids && it.from != it.to }
            .map { LienReel(it.from, it.to) }.distinct()
            .ifEmpty { data.edges.filter { it.from in ids && it.to in ids && it.from != it.to }.map { LienReel(it.from, it.to) } }
        val entrants = HashMap<String, Int>(); val sortants = HashMap<String, Int>()
        liensTous.forEach { entrants[it.vers] = (entrants[it.vers] ?: 0) + 1; sortants[it.de] = (sortants[it.de] ?: 0) + 1 }
        val degre = { id: String -> (entrants[id] ?: 0) + (sortants[id] ?: 0) }

        // ---- hubs de dossiers réels (les plus gros d'abord ; au-delà de MAX, regroupés dans « Autres »)
        val parRacineBrut = notes.groupBy { racine(it.folder) }
        val ordreRacines = parRacineBrut.keys.sortedWith(compareByDescending<String> { parRacineBrut[it]!!.size }.thenBy { it.lowercase() })
        val parHub = LinkedHashMap<String, List<ObsidianWriter.GraphNode>>()
        ordreRacines.take(MAX_HUBS_DOSSIER).forEach { parHub[it] = parRacineBrut[it]!! }
        ordreRacines.drop(MAX_HUBS_DOSSIER).takeIf { it.isNotEmpty() }?.let { reste -> parHub["Autres"] = reste.flatMap { parRacineBrut[it]!! } }

        // ---- pièces jointes réelles, par type
        val pieces = data.attachments
        val piecesParType = pieces.groupBy { it.kind }
        val ordreTypes = listOf("IMAGE", "PDF", "AUDIO", "VIDEO", "FICHIER").filter { piecesParType[it].orEmpty().isNotEmpty() }
        val aPieces = ordreTypes.isNotEmpty()

        // ---- tags réels (les plus fréquents)
        val compteTags = HashMap<String, Int>()
        data.tags.forEach { (id, l) -> if (id in ids) l.distinct().forEach { t -> compteTags[t] = (compteTags[t] ?: 0) + 1 } }
        val tagsAffiches = compteTags.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .take(MAX_TAGS).map { it.key }

        // ---- hubs de dossiers : le long d'un arc d'ellipse qui passe par le HAUT, comme Projets / Personnel /
        // Travail / Recherche / Archives sur l'image (les deux extrêmes juste sous l'horizontale), espacés
        // régulièrement le long de la courbe ; le plus gros dossier en haut. Le bas reste aux tags et au hub
        // « Pièces jointes ». Ellipse haute : pensée pour l'écran portrait du téléphone.
        val clesAnneau = parHub.keys.toList()
        val m = clesAnneau.size
        val rx = 128f
        val ry = 340f + min(m, 7) * 14f
        val emplacements = positionsSurArc(m, rx, ry)
        // Ordre des emplacements : du plus proche du sommet au plus éloigné (le 1er dossier = le plus gros).
        val ordreEmplacements = emplacements.indices.sortedBy { i -> abs(emplacements[i].x) / rx + (emplacements[i].y + ry) / ry * 0.01f }
        val hubs = mutableListOf<Hub>()
        clesAnneau.forEachIndexed { i, cle ->
            val nb = parHub[cle]!!.size
            val titre = if (cle == "Racine") "Coffre" else cle
            hubs += Hub(cle, titre, pluriel(nb, "note", "notes"), iconeDossier(cle), TypeHub.DOSSIER, nb, emplacements[ordreEmplacements[i]], RAYON_HUB)
        }
        if (aPieces) hubs += Hub("__pieces", "Pièces jointes", "Médias & fichiers", Icone.TROMBONE, TypeHub.PIECES, pieces.size, Pt(0f, ry * 0.98f), RAYON_HUB)

        // ---- étiquettes initiales autour de chaque hub
        val etiquettes = mutableListOf<Etiquette>()
        for (hub in hubs) {
            val items = mutableListOf<Etiquette>()
            if (hub.type == TypeHub.DOSSIER) {
                val notesHub = parHub[hub.cle]!!.sortedWith(compareByDescending<ObsidianWriter.GraphNode> { degre(it.id) }.thenBy { it.label.lowercase() })
                notesHub.take(MAX_NOTES_PAR_HUB).forEach { n ->
                    val t = n.label.let { if (it.length > 20) it.take(19) + "…" else it }
                    items += Etiquette("note:${n.id}", t, Icone.NOTE, TypeEtiquette.NOTE, hub.cle, noteId = n.id,
                        pos = hub.pos, w = largeurEtiquette(t), h = H_ETIQUETTE)
                }
                val reste = notesHub.size - MAX_NOTES_PAR_HUB
                if (reste > 0) items += Etiquette("plus:${hub.cle}", "+$reste autres", Icone.NOTE, TypeEtiquette.PLUS, hub.cle,
                    pos = hub.pos, w = largeurEtiquette("+$reste autres", false), h = H_ETIQUETTE)
            } else if (hub.type == TypeHub.PIECES) {
                ordreTypes.forEach { kind ->
                    val t = "${libellePiece(kind)} · ${piecesParType[kind]!!.size}"
                    items += Etiquette("piece:$kind", t, iconePiece(kind), TypeEtiquette.PIECE, hub.cle,
                        pos = hub.pos, w = largeurEtiquette(t), h = H_ETIQUETTE)
                }
            }
            etiquettes += placerAutourDuHub(hub, items)
        }

        // ---- tags : grappe de pastilles sous le cerveau, avec une petite étiquette « Tags » en dessous
        if (tagsAffiches.isNotEmpty()) {
            val chips = tagsAffiches.map { "#$it".let { t -> if (t.length > 14) t.take(13) + "…" else t } }
            val parLigne = 3
            val lignes = chips.chunked(parLigne)
            val y0 = RAYON_CERVEAU + 58f
            lignes.forEachIndexed { li, ligne ->
                val largeurs = ligne.map { largeurEtiquette(it, false) }
                val total = largeurs.sum() + (ligne.size - 1) * 8f
                var x = -total / 2f
                ligne.forEachIndexed { ci, t ->
                    val w = largeurs[ci]
                    val tag = tagsAffiches[li * parLigne + ci]
                    etiquettes += Etiquette("tag:$tag", t, Icone.ETIQUETTE, TypeEtiquette.TAG, "__tags", tag = tag,
                        pos = Pt(x + w / 2f, y0 + li * (H_ETIQUETTE + 8f)), w = w, h = H_ETIQUETTE)
                    x += w + 8f
                }
            }
            val yHubTags = y0 + lignes.size * (H_ETIQUETTE + 8f) + 26f
            hubs += Hub("__tags", "Tags", "Mes étiquettes", Icone.ETIQUETTE, TypeHub.TAGS, compteTags.size, Pt(0f, yHubTags), 0f)
        }

        relacher(etiquettes, hubs)

        // ---- liens réels entre notes affichées
        val notesAffichees = etiquettes.mapNotNull { it.noteId }.toHashSet()
        val liens = liensTous.filter { it.de in notesAffichees && it.vers in notesAffichees }

        // ---- panneaux
        val sansEntrant = notes.filter { (entrants[it.id] ?: 0) == 0 }.sortedBy { it.label.lowercase() }.map { it.label }
        val toutesCibles = data.references.map { it.rawTarget.substringAfterLast('/').lowercase() }.toHashSet()
        val piecesOrphelines = pieces.filter { it.name.lowercase() !in toutesCibles && it.path.lowercase() !in toutesCibles }.map { it.name }
        val notesOrphelines = notes.filter { degre(it.id) == 0 }.map { it.label }
        val orphelins = (notesOrphelines.sortedBy { it.lowercase() } + piecesOrphelines.sortedBy { it.lowercase() })

        // ---- bornes (cerveau, hubs avec leur texte, étiquettes)
        var minX = -RAYON_CERVEAU; var maxX = RAYON_CERVEAU; var minY = -RAYON_CERVEAU; var maxY = RAYON_CERVEAU + 30f
        hubs.forEach { h ->
            val r = max(h.rayon, 30f)
            minX = min(minX, h.pos.x - r - 10f); maxX = max(maxX, h.pos.x + r + 10f)
            minY = min(minY, h.pos.y - r - 10f); maxY = max(maxY, h.pos.y + r + 10f)
        }
        etiquettes.forEach { e ->
            minX = min(minX, e.pos.x - e.w / 2f); maxX = max(maxX, e.pos.x + e.w / 2f)
            minY = min(minY, e.pos.y - e.h / 2f); maxY = max(maxY, e.pos.y + e.h / 2f)
        }
        return Constellation(
            hubs = hubs, etiquettes = etiquettes, liens = liens, tousLiens = liensTous,
            sansLienEntrant = sansEntrant, orphelins = orphelins,
            notesParHub = parHub, tagsParNote = data.tags.filterKeys { it in ids },
            totalNotes = notes.size, totalLiens = liensTous.size, totalTags = compteTags.size, totalPieces = pieces.size,
            rayonCerveau = RAYON_CERVEAU,
            minX = minX, minY = minY, maxX = maxX, maxY = maxY
        )
    }

    /**
     * [m] positions régulièrement espacées (en longueur de courbe, pas en angle : sur une ellipse haute, des
     * angles égaux tasseraient les hubs près du sommet) sur l'arc supérieur de l'ellipse, de la gauche
     * (un peu sous l'horizontale) à la droite en passant par le haut.
     */
    private fun positionsSurArc(m: Int, rx: Float, ry: Float): List<Pt> {
        if (m <= 0) return emptyList()
        if (m == 1) return listOf(Pt(0f, -ry))
        val debut = Math.toRadians(172.0); val fin = Math.toRadians(368.0)
        val pas = 600
        val longueurs = FloatArray(pas + 1)
        var prec = Pt(cos(debut).toFloat() * rx, sin(debut).toFloat() * ry)
        for (i in 1..pas) {
            val t = debut + (fin - debut) * i / pas
            val p = Pt(cos(t).toFloat() * rx, sin(t).toFloat() * ry)
            longueurs[i] = longueurs[i - 1] + (p - prec).len(); prec = p
        }
        val total = longueurs[pas]
        val fractions = if (m == 2) listOf(0.27f, 0.73f) else List(m) { it / (m - 1f) }
        return fractions.map { f ->
            val cible = f * total
            var i = longueurs.indexOfFirst { it >= cible }.coerceAtLeast(0)
            if (i > pas) i = pas
            val t = debut + (fin - debut) * i / pas
            Pt(cos(t).toFloat() * rx, sin(t).toFloat() * ry)
        }
    }

    /**
     * Position initiale des étiquettes d'un hub, comme sur l'image : pour un hub sur le côté, une colonne
     * d'étiquettes vers l'extérieur ; pour le hub du haut, un éventail au-dessus ; pour celui du bas, deux
     * colonnes de part et d'autre. Les chevauchements restants sont corrigés ensuite par [relacher].
     */
    private fun placerAutourDuHub(hub: Hub, items: List<Etiquette>): List<Etiquette> {
        if (items.isEmpty()) return emptyList()
        val u = hub.pos.norm()
        val pas = H_ETIQUETTE + 9f
        return when {
            abs(hub.pos.x) > 60f -> {
                val sx = if (u.x > 0) 1f else -1f
                items.mapIndexed { i, e ->
                    val y = hub.pos.y + (i - (items.size - 1) / 2f) * pas
                    e.copy(pos = Pt(hub.pos.x + sx * (hub.rayon + 22f + e.w / 2f), y))
                }
            }
            u.y < 0 -> {
                // Éventail au-dessus du hub du haut.
                val base = atan2(u.y, u.x)
                val ecart = if (items.size <= 3) 0.62f else 0.5f
                items.mapIndexed { i, e ->
                    val a = base + (i - (items.size - 1) / 2f) * ecart
                    val r = hub.rayon + 46f + abs(i - (items.size - 1) / 2f) * 6f
                    e.copy(pos = Pt(hub.pos.x + cos(a) * (r + e.w * 0.3f), hub.pos.y + sin(a) * r))
                }
            }
            else -> {
                // Deux colonnes de part et d'autre du hub du bas.
                items.mapIndexed { i, e ->
                    val gauche = i % 2 == 0
                    val ligne = i / 2
                    val nbLignes = (items.size + 1) / 2
                    val y = hub.pos.y + (ligne - (nbLignes - 1) / 2f) * pas
                    val sx = if (gauche) -1f else 1f
                    e.copy(pos = Pt(hub.pos.x + sx * (hub.rayon + 22f + e.w / 2f), y))
                }
            }
        }
    }

    /** Écarte les étiquettes qui se chevauchent entre elles, ou qui recouvrent un hub ou le cerveau. */
    private fun relacher(etiquettes: MutableList<Etiquette>, hubs: List<Hub>) {
        if (etiquettes.isEmpty()) return
        val xs = FloatArray(etiquettes.size) { etiquettes[it].pos.x }
        val ys = FloatArray(etiquettes.size) { etiquettes[it].pos.y }
        val fixes = BooleanArray(etiquettes.size) { etiquettes[it].type == TypeEtiquette.TAG }
        val marge = 6f
        repeat(120) {
            var bouge = false
            for (i in etiquettes.indices) for (j in i + 1 until etiquettes.size) {
                val a = etiquettes[i]; val b = etiquettes[j]
                val dx = xs[j] - xs[i]; val dy = ys[j] - ys[i]
                val px = (a.w + b.w) / 2f + marge - abs(dx)
                val py = (a.h + b.h) / 2f + marge - abs(dy)
                if (px > 0 && py > 0) {
                    bouge = true
                    val fi = if (fixes[i]) 0f else if (fixes[j]) 1f else 0.5f
                    val fj = 1f - fi
                    if (py < px) {
                        val s = if (dy >= 0) 1f else -1f
                        ys[i] -= s * py * fi; ys[j] += s * py * fj
                    } else {
                        val s = if (dx >= 0) 1f else -1f
                        xs[i] -= s * px * fi; xs[j] += s * px * fj
                    }
                }
            }
            // Obstacles fixes : cerveau et hubs (cercles), plus leur texte sous l'icône.
            val obstacles = hubs.filter { it.rayon > 0f }.map { it.pos to it.rayon + 8f } + (Pt(0f, 0f) to RAYON_CERVEAU + 10f)
            for (i in etiquettes.indices) {
                if (fixes[i]) continue
                val e = etiquettes[i]
                for ((c, r) in obstacles) {
                    // Point du rectangle le plus proche du centre du cercle.
                    val nx = c.x.coerceIn(xs[i] - e.w / 2f, xs[i] + e.w / 2f)
                    val ny = c.y.coerceIn(ys[i] - e.h / 2f, ys[i] + e.h / 2f)
                    val d = hypot(nx - c.x, ny - c.y)
                    if (d < r) {
                        bouge = true
                        val dir = if (d > 1e-3f) Pt((nx - c.x) / d, (ny - c.y) / d) else Pt(xs[i] - c.x, ys[i] - c.y).norm()
                        val push = r - d + 1f
                        xs[i] += dir.x * push; ys[i] += dir.y * push
                    }
                }
            }
            if (!bouge) return@repeat
        }
        for (i in etiquettes.indices) etiquettes[i] = etiquettes[i].copy(pos = Pt(xs[i], ys[i]))
    }

    /** Point d'ancrage d'une flèche sur le bord d'une étiquette, du côté d'où vient le trait. */
    fun bordEtiquette(e: Etiquette, depuis: Pt): Pt {
        val d = depuis - e.pos
        if (abs(d.x) < 1e-3f && abs(d.y) < 1e-3f) return e.pos
        val tx = if (abs(d.x) > 1e-3f) (e.w / 2f) / abs(d.x) else Float.MAX_VALUE
        val ty = if (abs(d.y) > 1e-3f) (e.h / 2f) / abs(d.y) else Float.MAX_VALUE
        val t = min(tx, ty)
        return Pt(e.pos.x + d.x * t, e.pos.y + d.y * t)
    }

    /** Point sur le cercle d'un hub en direction de [vers]. */
    fun bordCercle(c: Pt, r: Float, vers: Pt): Pt {
        val d = (vers - c).norm()
        return c + d * r
    }
}
