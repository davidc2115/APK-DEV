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
 * un CERVEAU central, un hub par dossier racine RÉEL du coffre (catégorie : icône + titre + sous-titre), ses
 * SOUS-DOSSIERS en branches (ex. Société › Clients, Devis, Factures, Paie) qui mènent chacun à leurs fichiers
 * (notes et pièces jointes), les fichiers posés à la racine de la catégorie autour du hub, un hub « Pièces
 * jointes » (images, PDF, audio, vidéos réellement présents),
 * les #tags réellement écrits dans les notes, et les panneaux « Mentions non liées » / « Fichiers orphelins ».
 *
 * Règle de l'architecture Orbe : rien n'est inventé pour décorer — aucun dossier, note, tag, lien ou pièce
 * jointe fictif. Un coffre de 8 notes donne un graphe de 8 notes.
 *
 * Coordonnées « monde » en dp à l'échelle 1, centre du cerveau en (0,0), pensées pour un écran portrait.
 */

enum class Icone { CERVEAU, DOSSIER, PERSONNE, TRAVAIL, RECHERCHE, ARCHIVE, TROMBONE, JOURNAL, SOLEIL, ETOILE, ETIQUETTE, IMAGE, VIDEO, AUDIO, PDF, NOTE, FICHIER }

enum class TypeHub { DOSSIER, PIECES, TAGS }

enum class TypeEtiquette { NOTE, PIECE, TAG, PLUS, DOSSIER, FICHIER }

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
    val rayon: Float,
    /** Catégorie d'un sous-dossier affiché en cercle (null pour une catégorie). */
    val parent: String? = null,
    /** 0 = catégorie (dossier racine), 1 = sous-dossier. */
    val niveau: Int = 0,
    /** A du contenu à montrer (sous-dossiers ou fichiers) : se déplie / se replie d'un toucher. */
    val depliable: Boolean = false,
    val deplie: Boolean = false
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
    val h: Float,
    /** Étiquette « dossier » d'où part la branche (null : rattachée directement au hub). */
    val parent: String? = null,
    /** Chemin du dossier pour TypeEtiquette.DOSSIER (et dossier à lister pour PLUS). */
    val dossier: String? = null,
    /** Chemin du fichier pour TypeEtiquette.FICHIER (pièce jointe : PDF, image…). */
    val chemin: String? = null,
    /** Taille du texte relative (fichiers en bout de branche un peu plus petits). */
    val echelle: Float = 1f
)

/** Contenu réel d'un dossier du coffre : sous-dossiers directs, notes et pièces jointes directes, total récursif. */
data class ContenuDossier(
    val chemin: String,
    val sousDossiers: List<String>,
    val notes: List<ObsidianWriter.GraphNode>,
    val pieces: List<ObsidianWriter.GraphAttachment>,
    val total: Int
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
    /** Arborescence réelle : chemin de dossier ("" = racine, "Autres" = catégories regroupées) → contenu. */
    val dossiers: Map<String, ContenuDossier>,
    /** Dossiers dépliés dans cette mise en page (catégories et sous-dossiers). */
    val deplies: Set<String>,
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
    private const val MAX_HUBS_DOSSIER = 9
    private const val MAX_SOUS_DOSSIERS = 6
    private const val MAX_TAGS = 6
    /** Hauteur d'une étiquette de fichier en bout de branche, et pas vertical entre deux. */
    private const val H_FEUILLE = 21f
    private const val PAS_FEUILLE = 25f
    private const val PAS = H_ETIQUETTE + 9f

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

    private fun court(t: String, n: Int) = if (t.length > n) t.take(n - 1) + "…" else t
    private fun nom(chemin: String) = chemin.substringAfterLast('/')

    /** Une ligne d'étiquettes à côté d'un hub : un dossier et ses fichiers, ou un fichier seul. */
    const val RAYON_SOUS = 32f

    /** Rayon du cercle d'un dossier selon sa profondeur (1 = sous-dossier d'une catégorie). */
    fun rayonNiveau(niveau: Int) = when (niveau) { 0 -> RAYON_HUB; 1 -> RAYON_SOUS; 2 -> 28f; else -> 25f }

    /**
     * Une ligne d'une branche : un DOSSIER en cercle (avec, s'il est déplié, ses propres sous-dossiers en cercles et
     * ses fichiers en liste), ou un fichier / un « +N ». Les dossiers sont des cercles à toutes les profondeurs.
     */
    private class Rangee(val sous: Hub?, val tete: Etiquette?, val enfants: List<Rangee> = emptyList(), val feuille: Boolean = false) {
        val hauteur: Float by lazy {
            if (sous != null) max(2 * sous.rayon + 28f, enfants.sumOf { it.hauteur.toDouble() }.toFloat() + 12f)
            else if (feuille) PAS_FEUILLE else H_ETIQUETTE + 9f
        }
    }

    /** Déplie [chemin] s'il est replié, le replie (avec tout ce qu'il contient) s'il est déplié. */
    fun basculer(deplies: Set<String>, chemin: String): Set<String> =
        if (chemin in deplies) deplies.filterTo(HashSet()) { it != chemin && !it.startsWith("$chemin/") } else deplies + chemin

    fun construire(data: ObsidianWriter.GraphData, depliesDemandes: Set<String>? = null): Constellation {
        val notes = data.nodes.filterNot { it.ghost }
        val ids = notes.map { it.id }.toHashSet()
        // Liens réels orientés entre notes existantes (les cibles absentes ne deviennent pas des nœuds).
        val liensTous = data.references.filter { it.resolved && it.from in ids && it.to in ids && it.from != it.to }
            .map { LienReel(it.from, it.to) }.distinct()
            .ifEmpty { data.edges.filter { it.from in ids && it.to in ids && it.from != it.to }.map { LienReel(it.from, it.to) } }
        val entrants = HashMap<String, Int>(); val sortants = HashMap<String, Int>()
        liensTous.forEach { entrants[it.vers] = (entrants[it.vers] ?: 0) + 1; sortants[it.de] = (sortants[it.de] ?: 0) + 1 }
        val degre = { id: String -> (entrants[id] ?: 0) + (sortants[id] ?: 0) }
        val pieces = data.attachments

        // ---- arborescence réelle du coffre : dossiers (même vides), notes et pièces jointes, comptes récursifs
        val dirs = LinkedHashSet<String>()
        fun ajouterChemin(d: String) { var p = d.trim('/'); while (p.isNotBlank()) { if (!dirs.add(p)) break; p = p.substringBeforeLast('/', "") } }
        data.dossiers.forEach { ajouterChemin(it) }
        notes.forEach { ajouterChemin(it.folder) }
        pieces.forEach { ajouterChemin(it.path.substringBeforeLast('/', "")) }
        val enfantsDe = dirs.groupBy { it.substringBeforeLast('/', "") }
        val notesDe = notes.groupBy { it.folder }
        val piecesDe = pieces.groupBy { it.path.substringBeforeLast('/', "") }
        val totaux = HashMap<String, Int>()
        fun total(d: String): Int = totaux.getOrPut(d) {
            (notesDe[d]?.size ?: 0) + (piecesDe[d]?.size ?: 0) + enfantsDe[d].orEmpty().sumOf { total(it) }
        }
        val parTaille = compareByDescending<String> { total(it) }.thenBy { it.lowercase() }
        val dossiers = HashMap<String, ContenuDossier>()
        (dirs + "").forEach { d ->
            dossiers[d] = ContenuDossier(d, enfantsDe[d].orEmpty().sortedWith(parTaille),
                notesDe[d].orEmpty().sortedBy { it.label.lowercase() }, piecesDe[d].orEmpty().sortedBy { it.name.lowercase() }, total(d))
        }
        fun notesRecursives(d: String): List<ObsidianWriter.GraphNode> = notesDe[d].orEmpty() + enfantsDe[d].orEmpty().flatMap { notesRecursives(it) }

        // ---- catégories = dossiers racine (les plus remplis d'abord ; au-delà de MAX, regroupés dans « Autres »)
        val racines = enfantsDe[""].orEmpty().sortedWith(parTaille).toMutableList()
        val categories = mutableListOf<String>()
        if ((notesDe[""]?.size ?: 0) + (piecesDe[""]?.size ?: 0) > 0) racines += "Racine"
        racines.sortWith(compareByDescending<String> { if (it == "Racine") (notesDe[""]?.size ?: 0) + (piecesDe[""]?.size ?: 0) else total(it) }.thenBy { it.lowercase() })
        categories += racines.take(MAX_HUBS_DOSSIER)
        val regroupees = racines.drop(MAX_HUBS_DOSSIER)
        if (regroupees.isNotEmpty()) {
            categories += "Autres"
            dossiers["Autres"] = ContenuDossier("Autres", regroupees.filter { it != "Racine" }, emptyList(), emptyList(), regroupees.sumOf { if (it == "Racine") 0 else total(it) })
        }
        dossiers["Racine"] = ContenuDossier("Racine", emptyList(), notesDe[""].orEmpty().sortedBy { it.label.lowercase() }, piecesDe[""].orEmpty(), (notesDe[""]?.size ?: 0) + (piecesDe[""]?.size ?: 0))
        val notesParHub = LinkedHashMap<String, List<ObsidianWriter.GraphNode>>()
        categories.forEach { cat ->
            notesParHub[cat] = when (cat) {
                "Racine" -> notesDe[""].orEmpty()
                "Autres" -> regroupees.filter { it != "Racine" }.flatMap { notesRecursives(it) }
                else -> notesRecursives(cat)
            }
        }

        // ---- pièces jointes réelles, par type (hub « Pièces jointes »)
        val piecesParType = pieces.groupBy { it.kind }
        val ordreTypes = listOf("IMAGE", "PDF", "AUDIO", "VIDEO", "FICHIER").filter { piecesParType[it].orEmpty().isNotEmpty() }
        val aPieces = ordreTypes.isNotEmpty()

        // ---- tags réels (les plus fréquents)
        val compteTags = HashMap<String, Int>()
        data.tags.forEach { (id, l) -> if (id in ids) l.distinct().forEach { t -> compteTags[t] = (compteTags[t] ?: 0) + 1 } }
        // Les #tags ne sont plus affichés dans l'Orbe ni le Graphe (ni pastilles « #… », ni hub « Tags ») : vue plus épurée.
        val tagsAffiches = emptyList<String>()

        // ---- état déplié / replié : par défaut chaque catégorie montre ses sous-dossiers (repliés)
        val deplies: Set<String> = depliesDemandes ?: categories.toSet()

        fun etiquetteNote(n: ObsidianWriter.GraphNode, hub: String, feuille: Boolean): Etiquette {
            val t = court(n.label, if (feuille) 18 else 20)
            val e = if (feuille) 0.9f else 1f
            return Etiquette("note:${n.id}", t, Icone.NOTE, TypeEtiquette.NOTE, hub, noteId = n.id, pos = Pt(0f, 0f),
                w = largeurEtiquette(t) * e, h = if (feuille) H_FEUILLE else H_ETIQUETTE, echelle = e)
        }
        fun etiquettePiece(a: ObsidianWriter.GraphAttachment, hub: String, feuille: Boolean): Etiquette {
            val t = court(a.name, if (feuille) 18 else 20)
            val e = if (feuille) 0.9f else 1f
            return Etiquette("fichier:${a.path}", t, iconePiece(a.kind), TypeEtiquette.FICHIER, hub, pos = Pt(0f, 0f),
                w = largeurEtiquette(t) * e, h = if (feuille) H_FEUILLE else H_ETIQUETTE, chemin = a.path, echelle = e)
        }
        fun etiquetteDossier(d: String, hub: String): Etiquette {
            val t = court(nom(d), 16) + " · " + total(d)
            return Etiquette("dir:$d", t, iconeDossier(nom(d)), TypeEtiquette.DOSSIER, hub, pos = Pt(0f, 0f),
                w = largeurEtiquette(t) * 0.9f, h = H_FEUILLE, dossier = d, echelle = 0.9f)
        }
        fun plus(hub: String, dossier: String, n: Int, mot: String, feuille: Boolean = false): Etiquette {
            val t = "+$n $mot"
            return Etiquette("plus:$dossier:$mot", t, Icone.NOTE, TypeEtiquette.PLUS, hub, pos = Pt(0f, 0f),
                w = largeurEtiquette(t, false), h = if (feuille) H_FEUILLE else H_ETIQUETTE, dossier = dossier, echelle = if (feuille) 0.9f else 1f)
        }
        val parDegre = compareByDescending<ObsidianWriter.GraphNode> { degre(it.id) }.thenBy { it.label.lowercase() }
        fun contenuDe(cat: String) = dossiers[when (cat) { "Autres" -> "Autres"; "Racine" -> "Racine"; else -> cat }]
        fun sousTitreDe(c: ContenuDossier?): String {
            val nd = c?.sousDossiers?.size ?: 0
            val tot = c?.total ?: 0
            return if (nd > 0) "${pluriel(nd, "dossier", "dossiers")} · $tot" else pluriel(tot, "fichier", "fichiers")
        }

        /** Dossier en cercle ; déplié : ses sous-dossiers (cercles, récursivement) puis ses fichiers (liste). */
        fun rangeeDossier(d: String, parent: String, niveau: Int, fige: Boolean = false): Rangee {
            val cd = dossiers[d]
            val depliable = cd != null && (cd.sousDossiers.isNotEmpty() || cd.notes.isNotEmpty() || cd.pieces.isNotEmpty())
            val ouvert = depliable && d in deplies && !fige && niveau < 9
            val sous = Hub(d, nom(d), sousTitreDe(cd), iconeDossier(nom(d)), TypeHub.DOSSIER, cd?.total ?: 0, Pt(0f, 0f), rayonNiveau(niveau),
                parent = parent, niveau = niveau, depliable = depliable, deplie = ouvert)
            val enfants = mutableListOf<Rangee>()
            if (ouvert && cd != null) {
                val sd = cd.sousDossiers.take(MAX_SOUS_DOSSIERS)
                sd.forEach { enfants += rangeeDossier(it, d, niveau + 1) }
                if (cd.sousDossiers.size > sd.size) enfants += Rangee(null, plus(d, d, cd.sousDossiers.size - sd.size, "dossiers", feuille = true), feuille = true)
                val fichiers = cd.notes.sortedWith(parDegre).map { etiquetteNote(it, d, true) } + cd.pieces.map { etiquettePiece(it, d, true) }
                fichiers.take(6).forEach { enfants += Rangee(null, it, feuille = true) }
                if (fichiers.size > 6) enfants += Rangee(null, plus(d, d, fichiers.size - 6, "autres", feuille = true), feuille = true)
            }
            return Rangee(sous, null, enfants)
        }

        val rangeesParCat = LinkedHashMap<String, List<Rangee>>()
        for (cat in categories) {
            val c = contenuDe(cat) ?: continue
            if (cat !in deplies) { rangeesParCat[cat] = emptyList(); continue }
            val rangees = mutableListOf<Rangee>()
            val branches = c.sousDossiers.take(MAX_SOUS_DOSSIERS)
            branches.forEach { d -> rangees += rangeeDossier(d, cat, 1, cat == "Autres") }
            if (c.sousDossiers.size > branches.size) rangees += Rangee(null, plus(cat, c.chemin, c.sousDossiers.size - branches.size, "dossiers"))
            val nbRacine = if (branches.isEmpty()) 5 else 3
            val fichiersRacine = c.notes.sortedWith(parDegre).map { etiquetteNote(it, cat, false) } + c.pieces.map { etiquettePiece(it, cat, false) }
            fichiersRacine.take(nbRacine).forEach { rangees += Rangee(null, it) }
            if (fichiersRacine.size > nbRacine) rangees += Rangee(null, plus(cat, c.chemin, fichiersRacine.size - nbRacine, "autres"))
            rangeesParCat[cat] = rangees
        }

        // ---- hubs de catégories le long d'un arc d'ellipse qui passe par le HAUT (comme Projets / Personnel /
        // Travail… sur l'image), espacés selon la place que prennent leurs branches ; la plus grosse en haut. Le bas
        // reste aux tags et au hub « Pièces jointes ». Ellipse haute : pensée pour l'écran portrait du téléphone.
        val m = categories.size
        val poids = categories.map { cat ->
            val rs = rangeesParCat[cat].orEmpty()
            val hauteur = if (rs.isEmpty()) 0f else rs.sumOf { it.hauteur.toDouble() }.toFloat()
            max(hauteur, 2 * RAYON_HUB + 40f)
        }
        val rx = 128f
        val ry = max(340f + min(m, 7) * 14f, poids.sum() / 2.3f)
        val emplacements = positionsSurArc(m, rx, ry)
        val ordreEmplacements = emplacements.indices.sortedBy { i -> abs(emplacements[i].x) / rx + (emplacements[i].y + ry) / ry * 0.01f }
        // Emplacement de chaque catégorie, puis espacement pondéré par la taille réelle de chaque groupe.
        val emplacementDe = IntArray(m) { -1 }
        categories.indices.forEach { i -> emplacementDe[i] = ordreEmplacements[i] }
        val poidsParEmplacement = FloatArray(m)
        categories.indices.forEach { i -> poidsParEmplacement[emplacementDe[i]] = poids[i] }
        val positions = positionsPonderees(poidsParEmplacement, rx, ry)
        val hubs = mutableListOf<Hub>()
        categories.forEachIndexed { i, cat ->
            val c = contenuDe(cat)
            val titre = when (cat) { "Racine" -> "Coffre"; else -> nom(cat) }
            val depliable = c != null && (c.sousDossiers.isNotEmpty() || c.notes.isNotEmpty() || c.pieces.isNotEmpty())
            hubs += Hub(cat, titre, sousTitreDe(c), iconeDossier(nom(cat)), TypeHub.DOSSIER, c?.total ?: 0, positions[emplacementDe[i]], RAYON_HUB,
                depliable = depliable, deplie = depliable && cat in deplies)
        }
        if (aPieces) hubs += Hub("__pieces", "Pièces jointes", "Médias & fichiers", Icone.TROMBONE, TypeHub.PIECES, pieces.size, Pt(0f, ry * 0.98f), RAYON_HUB)

        // ---- placement des étiquettes autour de chaque hub
        val etiquettes = mutableListOf<Etiquette>()
        for (hub in hubs.toList()) {
            if (hub.type == TypeHub.DOSSIER) {
                val (sousHubs, ets) = placerBranches(hub, rangeesParCat[hub.cle].orEmpty())
                hubs += sousHubs; etiquettes += ets
            }
            else if (hub.type == TypeHub.PIECES) {
                val items = ordreTypes.map { kind ->
                    val t = "${libellePiece(kind)} · ${piecesParType[kind]!!.size}"
                    Etiquette("piece:$kind", t, iconePiece(kind), TypeEtiquette.PIECE, hub.cle, pos = hub.pos, w = largeurEtiquette(t), h = H_ETIQUETTE)
                }
                etiquettes += placerAutourDuHub(hub, items)
            }
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
            notesParHub = notesParHub, dossiers = dossiers, deplies = deplies, tagsParNote = data.tags.filterKeys { it in ids },
            totalNotes = notes.size, totalLiens = liensTous.size, totalTags = compteTags.size, totalPieces = pieces.size,
            rayonCerveau = RAYON_CERVEAU,
            minX = minX, minY = minY, maxX = maxX, maxY = maxY
        )
    }

    /**
     * Positions des hubs sur l'arc, la distance entre deux hubs voisins étant proportionnelle à la place que
     * prennent leurs branches ([poids], dans l'ordre des emplacements de gauche à droite).
     */
    private fun positionsPonderees(poids: FloatArray, rx: Float, ry: Float): List<Pt> {
        val m = poids.size
        if (m <= 2) return positionsSurArc(m, rx, ry)
        val egales = positionsSurArc(m, rx, ry)
        // Ordre des emplacements le long de l'arc (de gauche à droite en passant par le haut) = ordre angulaire.
        val ordre = egales.indices.sortedBy { i -> val a = atan2(egales[i].y, egales[i].x); if (a > 0.5f) a - 2 * PI_F else a }
        val w = ordre.map { poids[it].coerceAtLeast(1f) }
        val total = w.sum()
        val fractions = FloatArray(m)
        var cumul = 0f
        ordre.forEachIndexed { k, i -> fractions[i] = (cumul + w[k] / 2f) / total; cumul += w[k] }
        // Recale pour que les deux extrêmes restent aux bouts de l'arc (comme la répartition régulière).
        val f0 = fractions[ordre.first()]; val f1 = fractions[ordre.last()]
        return List(m) { i -> pointSurArc((fractions[i] - f0) / (f1 - f0).coerceAtLeast(1e-3f), rx, ry) }
    }

    private const val PI_F = Math.PI.toFloat()

    private fun pointSurArc(f: Float, rx: Float, ry: Float): Pt {
        val debut = Math.toRadians(172.0); val fin = Math.toRadians(368.0)
        val pas = 600
        val longueurs = FloatArray(pas + 1)
        var prec = Pt(cos(debut).toFloat() * rx, sin(debut).toFloat() * ry)
        for (i in 1..pas) {
            val t = debut + (fin - debut) * i / pas
            val p = Pt(cos(t).toFloat() * rx, sin(t).toFloat() * ry)
            longueurs[i] = longueurs[i - 1] + (p - prec).len(); prec = p
        }
        val cible = f.coerceIn(0f, 1f) * longueurs[pas]
        val i = longueurs.indexOfFirst { it >= cible }.coerceIn(0, pas)
        val t = debut + (fin - debut) * i / pas
        return Pt(cos(t).toFloat() * rx, sin(t).toFloat() * ry)
    }

    /**
     * Branches d'une catégorie : chaque rangée (un sous-dossier en cercle, ou un fichier de la racine) est posée à côté
     * du hub, vers l'extérieur ; les fichiers d'un sous-dossier déplié prolongent sa branche un cran plus loin.
     * Hub sur le côté : une colonne ; hub du haut ou du bas : deux colonnes, une de chaque côté.
     */
    private fun placerBranches(hub: Hub, rangees: List<Rangee>): Pair<List<Hub>, List<Etiquette>> {
        if (rangees.isEmpty()) return emptyList<Hub>() to emptyList()
        val hubs = mutableListOf<Hub>()
        val out = mutableListOf<Etiquette>()
        val cote = abs(hub.pos.x) > 60f
        val colonnes: List<Pair<Float, List<Rangee>>> =
            if (cote) listOf((if (hub.pos.x > 0) 1f else -1f) to rangees)
            else listOf(-1f to rangees.filterIndexed { i, _ -> i % 2 == 0 }, 1f to rangees.filterIndexed { i, _ -> i % 2 == 1 })
        for ((sx, rs) in colonnes) {
            if (rs.isEmpty()) continue
            val hauteur = rs.sumOf { it.hauteur.toDouble() }.toFloat()
            // Hub du haut : les branches montent un peu (elles ne doivent pas redescendre vers le cerveau).
            var y = hub.pos.y - hauteur / 2f - (if (!cote && hub.pos.y < 0) hauteur * 0.25f else 0f)
            for (r in rs) {
                placer(r, hub.pos.x, hub.rayon, y + r.hauteur / 2f, sx, hubs, out)
                y += r.hauteur
            }
        }
        return hubs to out
    }

    /** Pose une rangée à droite (sx = 1) ou à gauche (sx = -1) de son parent, puis ses enfants un cran plus loin. */
    private fun placer(r: Rangee, xParent: Float, rParent: Float, cy: Float, sx: Float, hubs: MutableList<Hub>, out: MutableList<Etiquette>) {
        if (r.sous != null) {
            val sous = r.sous.copy(pos = Pt(xParent + sx * (rParent + 30f + r.sous.rayon), cy))
            hubs += sous
            val total = r.enfants.sumOf { it.hauteur.toDouble() }.toFloat()
            var y = cy - total / 2f
            for (e in r.enfants) {
                placer(e, sous.pos.x, sous.rayon, y + e.hauteur / 2f, sx, hubs, out)
                y += e.hauteur
            }
        } else if (r.tete != null) {
            out += r.tete.copy(pos = Pt(xParent + sx * (rParent + (if (r.feuille) 26f else 24f) + r.tete.w / 2f), cy))
        }
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
