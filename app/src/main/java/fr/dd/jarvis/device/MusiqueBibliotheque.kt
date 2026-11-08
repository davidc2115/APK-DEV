package fr.dd.jarvis.device

import android.content.Context
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.view.accessibility.AccessibilityNodeInfo
import fr.dd.jarvis.service.JarvisAccessibilityService
import fr.dd.jarvis.societe.core.normaliser
import kotlinx.coroutines.delay

/**
 * Bibliothèque de YT Music Morphe (playlists, titres, albums, artistes, « J'aime ») et commandes du lecteur
 * (j'aime, aléatoire, répétition, file d'attente) : ce que YT Music Morphe sait faire, depuis JARVIS.
 * La bibliothèque est personnelle (compte Google de l'appli) : elle est lue à l'écran par l'accessibilité de JARVIS.
 */
object MusiqueBibliotheque {
    /** Sections de la bibliothèque : identifiant → libellés possibles du filtre dans l'appli (français, anglais). */
    val SECTIONS = linkedMapOf(
        "playlists" to listOf("Playlists"),
        "titres" to listOf("Titres", "Songs"),
        "albums" to listOf("Albums"),
        "artistes" to listOf("Artistes", "Artists"),
        "telechargements" to listOf("Téléchargements", "Downloads"),
    )
    private val ONGLET_BIBLIO = listOf("Bibliothèque", "Library", "Ma bibliothèque")
    private val BOUTONS_LECTURE = listOf("Lecture", "Lire", "Tout lire", "Play", "Play all", "Écouter")
    private val BOUTONS_ALEATOIRE = listOf("Aléatoire", "Lecture aléatoire", "Shuffle")
    private val IGNORES = (SECTIONS.values.flatten() + ONGLET_BIBLIO + listOf("Accueil", "Home", "Explorer", "Explore", "Samples", "Récents", "Recents",
        "Activité récente", "Trier", "Tri", "Rechercher", "Search", "Podcasts", "Profils", "Profiles", "Toutes les catégories")).map { normaliser(it) }.toSet()

    data class Element(val titre: String, val sousTitre: String)

    private fun service() = JarvisAccessibilityService.instance

    private fun racine(paquet: String): AccessibilityNodeInfo? = service()?.rootInActiveWindow?.takeIf { it.packageName?.toString() == paquet }

    private fun cliquable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? { var c = n; while (c != null && !c.isClickable) c = c.parent; return c }

    /** Nœud dont le texte ou la description correspond exactement (sans casse ni accents) à l'un des libellés. */
    private fun trouver(r: AccessibilityNodeInfo, libelles: List<String>, exact: Boolean = true): AccessibilityNodeInfo? {
        val voulus = libelles.map { normaliser(it) }
        var trouve: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || trouve != null || d > 50) return
            val t = normaliser((n.text ?: n.contentDescription)?.toString().orEmpty())
            if (t.isNotBlank() && n.isVisibleToUser && voulus.any { if (exact) t == it else t.contains(it) }) { trouve = n; return }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(r, 0)
        return trouve
    }

    private suspend fun attendre(paquet: String, libelles: List<String>, ms: Long = 6000, exact: Boolean = true): AccessibilityNodeInfo? {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) {
            racine(paquet)?.let { r -> trouver(r, libelles, exact)?.let { return it } }
            delay(250)
        }
        return null
    }

    private fun cliquer(n: AccessibilityNodeInfo?): Boolean = cliquable(n)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    /** Ouvre l'appli sur la bibliothèque, filtre [section] sélectionné. Renvoie une erreur lisible, ou null si c'est bon. */
    private suspend fun ouvrirSection(ctx: Context, paquet: String, section: String): String? {
        if (service() == null) return "Il faut le service d'accessibilité de JARVIS pour lire ta bibliothèque (Réglages › Téléphone › Contrôle)."
        val lancement = ctx.packageManager.getLaunchIntentForPackage(paquet) ?: return "Appli de musique introuvable."
        ctx.startActivity(lancement.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val onglet = attendre(paquet, ONGLET_BIBLIO, 8000) ?: return "Onglet « Bibliothèque » introuvable dans l'appli."
        cliquer(onglet); delay(900)
        val libelles = SECTIONS[section] ?: return null
        attendre(paquet, libelles, 4000)?.let { if (!it.isSelected) { cliquer(it); delay(900) } }
        return null
    }

    /** Éléments visibles de la liste (titre + sous-titre de chaque ligne cliquable), en faisant défiler [pages] fois. */
    private suspend fun lireListe(paquet: String, pages: Int): List<Element> {
        val vus = LinkedHashMap<String, Element>()
        repeat(pages + 1) { page ->
            val r = racine(paquet) ?: return vus.values.toList()
            var liste: AccessibilityNodeInfo? = null
            fun lignes(n: AccessibilityNodeInfo?, d: Int) {
                if (n == null || d > 40) return
                if (n.isScrollable && liste == null && (n.className?.contains("RecyclerView") == true || n.className?.contains("ListView") == true)) liste = n
                if (n.isClickable && n.isVisibleToUser) {
                    val textes = mutableListOf<String>()
                    fun t(c: AccessibilityNodeInfo?, p: Int) {
                        if (c == null || p > 8) return
                        c.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { textes += it }
                        for (i in 0 until c.childCount) t(c.getChild(i), p + 1)
                    }
                    t(n, 0)
                    val titre = textes.firstOrNull()
                    if (titre != null && normaliser(titre) !in IGNORES && titre.length > 1) {
                        vus.putIfAbsent(normaliser(titre), Element(titre, textes.drop(1).firstOrNull().orEmpty()))
                        return
                    }
                }
                for (i in 0 until n.childCount) lignes(n.getChild(i), d + 1)
            }
            lignes(r, 0)
            if (page < pages) {
                if (liste?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true) return vus.values.toList()
                delay(700)
            }
        }
        return vus.values.toList()
    }

    /** Liste d'une section de la bibliothèque (lue dans l'appli). */
    suspend fun lister(ctx: Context, paquet: String, section: String, pages: Int = 3): Pair<List<Element>, String?> {
        ouvrirSection(ctx, paquet, section)?.let { return emptyList<Element>() to it }
        delay(600)
        val l = lireListe(paquet, pages)
        return l to (if (l.isEmpty()) "Rien de lisible dans « $section » (bibliothèque vide, ou compte non connecté dans l'appli)." else null)
    }

    /** Lance [nom] depuis la bibliothèque (playlist, album, artiste ou titre) ; [aleatoire] : lecture aléatoire. */
    suspend fun jouer(ctx: Context, paquet: String, section: String, nom: String, aleatoire: Boolean = false): String {
        ouvrirSection(ctx, paquet, section)?.let { return "❌ $it" }
        delay(600)
        var cible: AccessibilityNodeInfo? = null
        for (essai in 0 until 6) {
            val r = racine(paquet) ?: break
            cible = trouver(r, listOf(nom)) ?: trouver(r, listOf(nom), exact = false)
            if (cible != null) break
            // Défilement de la liste jusqu'à trouver l'élément.
            var liste: AccessibilityNodeInfo? = null
            fun chercher(x: AccessibilityNodeInfo?, d: Int) { if (x == null || liste != null || d > 40) return; if (x.isScrollable) liste = x; for (i in 0 until x.childCount) chercher(x.getChild(i), d + 1) }
            chercher(r, 0)
            if (liste?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true) break
            delay(700)
        }
        if (cible == null) return "❌ « $nom » introuvable dans ta bibliothèque (${section})."
        if (!cliquer(cible)) return "❌ Impossible d'ouvrir « $nom »."
        if (section == "titres") return "▶️ « $nom » lancé depuis ta bibliothèque."
        delay(1500)
        val bouton = attendre(paquet, if (aleatoire) BOUTONS_ALEATOIRE else BOUTONS_LECTURE, 5000)
            ?: attendre(paquet, BOUTONS_LECTURE + BOUTONS_ALEATOIRE, 1500, exact = false)
        return if (bouton != null && cliquer(bouton)) "▶️ « $nom » lancé" + (if (aleatoire) " en aléatoire" else "") + " depuis ta bibliothèque."
        else "📂 « $nom » ouvert ; bouton de lecture introuvable (appuie sur Lecture)."
    }

    // ------------------------------------------------------------------ lecteur (session média de l'appli)

    fun controleur(ctx: Context, paquet: String? = null): MediaController? = runCatching {
        val m = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
        m.getActiveSessions(android.content.ComponentName(ctx, fr.dd.jarvis.service.JarvisNotificationListener::class.java))
            .filter { it.packageName != ctx.packageName && (paquet == null || it.packageName == paquet) }
            .sortedByDescending { it.playbackState?.state == PlaybackState.STATE_PLAYING }.firstOrNull()
    }.getOrNull()

    data class EnCours(val app: String, val titre: String, val artiste: String, val album: String, val lecture: Boolean,
                       val actions: List<String>, val file: List<Pair<Long, String>>, val positionMs: Long, val dureeMs: Long, val image: android.graphics.Bitmap?,
                       /** Identifiant, dans [file], du titre en cours de lecture (-1 si inconnu). */
                       val actif: Long = -1L)

    fun enCours(ctx: Context, paquet: String? = null): EnCours? {
        val c = controleur(ctx, paquet) ?: return null
        val m = c.metadata
        val s = c.playbackState
        val nomApp = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(c.packageName, 0)).toString() }.getOrDefault(c.packageName)
        return EnCours(nomApp, m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(), m?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(), s?.state == PlaybackState.STATE_PLAYING,
            s?.customActions?.map { it.name.toString() }.orEmpty(),
            // File complète (plus de coupure à 25 / 50 titres ; 1 000 au plus par sécurité).
            c.queue?.take(1000)?.map { it.queueId to (it.description.title?.toString().orEmpty() + (it.description.subtitle?.let { st -> " — $st" } ?: "")) }.orEmpty(),
            s?.position ?: 0L, m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            s?.activeQueueItemId ?: -1L)
    }

    private val SYNONYMES = mapOf(
        "jaime" to listOf("j'aime", "jaime", "like", "pouce", "thumbs up", "ajouter aux titres", "favori"),
        "jaimepas" to listOf("je n'aime pas", "dislike", "thumbs down", "pas aime"),
        "aleatoire" to listOf("aleatoire", "shuffle", "melanger"),
        "repeter" to listOf("repet", "repeat", "boucle"),
        "radio" to listOf("radio", "mix"),
    )

    /** Action du lecteur : j'aime, je n'aime pas, aléatoire, répéter… (actions propres à l'appli, sinon note « pouce »). */
    fun action(ctx: Context, quoi: String, paquet: String? = null): String {
        val c = controleur(ctx, paquet) ?: return "Aucune musique en cours."
        val voulu = normaliser(quoi)
        val cles = SYNONYMES.entries.firstOrNull { (k, v) -> voulu == k || v.any { voulu.contains(normaliser(it)) } }?.value ?: listOf(quoi)
        val dispo = c.playbackState?.customActions.orEmpty()
        val a = dispo.firstOrNull { ca -> val n = normaliser(ca.name.toString() + " " + ca.action); cles.any { n.contains(normaliser(it)) } }
        if (a != null) { c.transportControls.sendCustomAction(a, null); return "✅ « ${a.name} » envoyé au lecteur." }
        if (voulu in setOf("jaime", "jaimepas") || cles == SYNONYMES["jaime"] || cles == SYNONYMES["jaimepas"]) {
            val pouce = cles == SYNONYMES["jaime"]
            runCatching { c.transportControls.setRating(Rating.newThumbRating(pouce)) }.onSuccess { return if (pouce) "👍 Titre ajouté à « J'aime »." else "👎 Noté « je n'aime pas »." }
        }
        return "Cette action n'est pas proposée par le lecteur" + (if (dispo.isNotEmpty()) " (actions disponibles : ${dispo.joinToString { it.name }})." else ".")
    }

    fun allerA(ctx: Context, idFile: Long, paquet: String? = null): Boolean =
        controleur(ctx, paquet)?.let { runCatching { it.transportControls.skipToQueueItem(idFile) }.isSuccess } ?: false
}
