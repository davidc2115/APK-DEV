package fr.dd.jarvis.device

import android.content.Context
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
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

    private fun titre(c: MediaController?): String? = c?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
    private fun joue(c: MediaController?) = c?.playbackState?.state == PlaybackState.STATE_PLAYING

    /** JARVIS va lancer de la musique : il rend le focus audio de la conversation et revérifiera la lecture à la fin. */
    private fun preparerLecture(paquet: String) {
        LectureGarantie.noter(paquet)
        fr.dd.jarvis.voice.VoiceManager.laisserPlaceMusique()
    }

    /**
     * Démarre la lecture de ce qui vient d'être ouvert (playlist, album…) et attend la confirmation du lecteur.
     * Le simple fait d'ouvrir une playlist ou son écran n'est jamais considéré comme une lecture démarrée.
     * Pas en lecture au bout de 4 s : les méthodes de relance du téléphone (lecture, touche lecture, pause puis lecture,
     * bouton de l'appli) — avant, la même commande « lecture » répétée 20 fois, ignorée par un lecteur dont la demande
     * de focus audio avait échoué pendant que JARVIS parlait.
     */
    suspend fun demarrerLecture(ctx: Context, paquet: String, aleatoire: Boolean = false, repetition: String = "aucune"): Boolean {
        preparerLecture(paquet)
        val avant = titre(controleur(ctx, paquet))
        // Ne jamais cliquer sur le bouton « Aléatoire » pour démarrer : dans YT Music il peut basculer
        // un état déjà actif et produire exactement l'inverse du mode demandé. Le mode est envoyé à la session.
        // Correspondance exacte : « Lecture » ne doit surtout pas correspondre à « Lecture aléatoire ».
        val bouton = attendre(paquet, BOUTONS_LECTURE, 2200, exact = true)
        if (bouton != null) cliquer(bouton)
        var modeEnvoye = false
        val fin = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < fin) {
            val c = controleur(ctx, paquet)
            if (c != null) {
                if (!modeEnvoye && titre(c) != null) { appliquerMode(ctx, c, aleatoire, repetition); modeEnvoye = true }
                if (joue(c)) return true
            } else if (attendre(paquet, listOf("Pause", "Mettre en pause", "Pause la lecture"), 180, exact = false) != null) return true
            delay(400)
        }
        val c = controleur(ctx, paquet) ?: return false
        if (!modeEnvoye) appliquerMode(ctx, c, aleatoire, repetition)
        // Ni bouton Lecture ni nouveau morceau : « lecture » relancerait l'ANCIEN morceau → pas de fausse réussite.
        if (bouton == null && titre(c) == avant) return false
        val app = ctx.applicationContext as? fr.dd.jarvis.JarvisApp ?: return false
        return app.phone.forcerLecture(paquet, "bibliothèque")
    }

    enum class Lancement { LECTURE, OUVERT, ECHEC }

    /**
     * Liste « J'aime » (titres aimés, playlist « LM » du compte de l'appli) : ouverte par lien, sans parcourir l'écran —
     * marche aussi téléphone verrouillé. Vérifie que la liste a VRAIMENT été chargée (le morceau change) : avant, un
     * lien ignoré laissait « lecture » relancer l'ancien morceau, annoncé comme ta liste J'aime. Si le lien de lecture
     * ne charge rien, page de la playlist + bouton « Lecture ». En aléatoire, passe au titre suivant pour ne pas
     * toujours commencer par le même.
     */
    suspend fun jouerJaime(ctx: Context, paquet: String, aleatoire: Boolean, repetition: String): Lancement {
        val c0 = controleur(ctx, paquet)
        val avant = titre(c0)
        preparerLecture(paquet)
        val liens = listOf("https://music.youtube.com/watch?list=LM", "https://music.youtube.com/playlist?list=LM")
        var charge: MediaController? = null
        for ((i, lien) in liens.withIndex()) {
            if (!YtMusique.ouvrirLien(ctx, paquet, lien)) continue
            var bouton = i == 0
            val fin = System.currentTimeMillis() + (if (i == 0) 7000 else 9000)
            while (System.currentTimeMillis() < fin) {
                delay(400)
                // Page de la playlist : bouton « Lecture » (jamais « Aléatoire » : le mode est envoyé à la session).
                if (!bouton) racine(paquet)?.let { r -> trouver(r, BOUTONS_LECTURE)?.let { b -> cliquer(b); bouton = true } }
                val c = controleur(ctx, paquet) ?: continue
                val t = titre(c) ?: continue
                // Nouveau morceau, ou file d'attente nommée « J'aime » (si le premier titre aimé est celui qui jouait déjà).
                val file = normaliser(c.queueTitle?.toString().orEmpty())
                if (t != avant || file.contains("j aime") || file.contains("liked") || file.contains("titres aimes")) { charge = c; break }
            }
            if (charge != null) break
            fr.dd.jarvis.voice.JournalVoix.noter("musique", "J'aime : le lien ${if (i == 0) "de lecture" else "de la playlist"} n'a pas chargé la liste")
        }
        val c = charge ?: run { LectureGarantie.oublier(); return Lancement.ECHEC }
        appliquerMode(ctx, c, aleatoire, repetition)
        if (aleatoire) { delay(300); runCatching { c.transportControls.skipToNext() } }
        repeat(6) { if (joue(controleur(ctx, paquet))) return Lancement.LECTURE; delay(350) }
        val app = ctx.applicationContext as? fr.dd.jarvis.JarvisApp ?: return Lancement.OUVERT
        return if (app.phone.forcerLecture(paquet, "J'aime")) Lancement.LECTURE else Lancement.OUVERT
    }

    // ------------------------------------------------------------------ playlists personnelles déjà vues

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("musique_bibliotheque", Context.MODE_PRIVATE)

    /** Retient les noms des playlists de ta bibliothèque (lues à l'écran) : « mets la playlist sport » ira chez toi. */
    fun memoriserPlaylists(ctx: Context, noms: Collection<String>) {
        if (noms.isEmpty()) return
        val deja = prefs(ctx).getStringSet("playlists", emptySet()).orEmpty()
        val tout = (deja + noms.map { it.trim() }.filter { it.length > 1 }).take(300).toSet()
        prefs(ctx).edit().putStringSet("playlists", tout).apply()
    }

    /** Nom exact d'une playlist de ta bibliothèque qui correspond à [demande] (« la playlist Sport » → « Sport »), ou null. */
    fun playlistConnue(ctx: Context, demande: String): String? {
        val connues = prefs(ctx).getStringSet("playlists", emptySet()).orEmpty()
        return playlistParmi(connues, demande)
    }

    internal fun playlistParmi(connues: Collection<String>, demande: String): String? {
        val q = normaliser(demande).replace(Regex("^(?:(?:la|ma|mes|les) )?(?:playlist |liste )?"), "").trim()
        if (q.length < 2) return null
        return connues.firstOrNull { normaliser(it) == q }
    }

    /** Ouvre l'appli sur la bibliothèque, filtre [section] sélectionné. Renvoie une erreur lisible, ou null si c'est bon. */
    private suspend fun ouvrirSection(ctx: Context, paquet: String, section: String): String? {
        if (service() == null) return "Il faut le service d'accessibilité de JARVIS pour lire ta bibliothèque (Réglages › Téléphone › Contrôle)."
        // Playlists, albums… ne se trouvent qu'en parcourant l'appli à l'écran (YT Music ne les donne qu'à son compte connecté).
        val verrouille = runCatching { (ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked }.getOrDefault(false)
        if (verrouille) return "Téléphone verrouillé : pour ta bibliothèque (playlists, albums…), JARVIS doit parcourir l'appli à l'écran. Déverrouille-le puis redemande. (Tes titres J'aime, eux, se lancent même verrouillé.)"
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
        if (section == "playlists") memoriserPlaylists(ctx, l.map { it.titre })
        return l to (if (l.isEmpty()) "Rien de lisible dans « $section » (bibliothèque vide, ou compte non connecté dans l'appli)." else null)
    }

    /** Lance [nom] depuis la bibliothèque (playlist, album, artiste ou titre) ; [aleatoire] : lecture aléatoire. */
    suspend fun jouer(ctx: Context, paquet: String, section: String, nom: String, aleatoire: Boolean = false, repetition: String = "aucune"): String {
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
        val nomReel = (cible.text ?: cible.contentDescription)?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: nom
        if (section == "playlists") memoriserPlaylists(ctx, listOf(nomReel))
        if (!cliquer(cible)) return "❌ Impossible d'ouvrir « $nom »."
        delay(900)
        return if (demarrerLecture(ctx, paquet, aleatoire, repetition)) {
            "▶️ « $nom » est en lecture" + when {
                aleatoire -> " en aléatoire"
                repetition == "titre" -> " avec répétition du titre"
                repetition == "playlist" -> " avec répétition de toute la playlist"
                else -> " dans l'ordre normal"
            } + " depuis ta bibliothèque."
        } else {
            "📂 « $nom » est ouvert, mais le démarrage de la lecture n'a pas été confirmé. Vérifie le lecteur dans l'application."
        }
    }

    // ------------------------------------------------------------------ modes du lecteur (session média de l'appli)

    /**
     * Envoie les modes via MediaControllerCompat, seule API réellement exposée par les sessions Android tierces.
     * Vérifie ensuite la valeur relue dans la session : l'absence d'exception ne suffit pas à annoncer un succès.
     * Les paramètres nullable permettent de modifier un mode sans écraser l'autre.
     */
    private fun appliquerModes(ctx: Context, c: MediaController, aleatoire: Boolean?, repetition: String?): Boolean {
        if (aleatoire == null && repetition == null) return false
        val token = runCatching { MediaSessionCompat.Token.fromToken(c.sessionToken) }.getOrNull() ?: return false
        val compat = runCatching { MediaControllerCompat(ctx, token) }.getOrNull() ?: return false
        val modeShuffle = if (aleatoire == true) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE
        val modeRepeat = when (fr.dd.jarvis.societe.core.normaliser(repetition.orEmpty())) {
            "titre", "repeter titre", "repeter_titre", "one" -> PlaybackStateCompat.REPEAT_MODE_ONE
            "playlist", "repeter playlist", "repeter_playlist", "all" -> PlaybackStateCompat.REPEAT_MODE_ALL
            else -> PlaybackStateCompat.REPEAT_MODE_NONE
        }
        // Ne pas envoyer les commandes avant que le contrôleur distant ait terminé sa négociation.
        var pret = false
        repeat(10) {
            if (runCatching { compat.isSessionReady() }.getOrDefault(false)) { pret = true; return@repeat }
            android.os.SystemClock.sleep(100)
        }
        if (!pret) return false
        val envoye = runCatching {
            if (aleatoire != null) compat.transportControls.setShuffleMode(modeShuffle)
            if (repetition != null) compat.transportControls.setRepeatMode(modeRepeat)
        }.isSuccess
        if (!envoye) return false

        // Les sessions peuvent publier leur nouvel état quelques instants après la commande.
        repeat(10) {
            val shuffleOk = aleatoire == null || runCatching { compat.shuffleMode == modeShuffle }.getOrDefault(false)
            val repeatOk = repetition == null || runCatching { compat.repeatMode == modeRepeat }.getOrDefault(false)
            if (shuffleOk && repeatOk) return true
            android.os.SystemClock.sleep(100)
        }
        return false
    }

    private fun appliquerMode(ctx: Context, c: MediaController, aleatoire: Boolean, repetition: String): Boolean =
        appliquerModes(ctx, c, aleatoire, repetition)

    /** Modifie indépendamment lecture aléatoire et répétition ; « normal » désactive les deux. */
    fun configurerMode(ctx: Context, mode: String, paquet: String? = null): String {
        val c = controleur(ctx, paquet) ?: return "Aucune session musicale active : mode non modifié."
        val m = fr.dd.jarvis.societe.core.normaliser(mode).replace(' ', '_')
        val aleatoire: Boolean? = when (m) {
            "aleatoire", "shuffle" -> true
            "aleatoire_off", "shuffle_off", "sans_aleatoire", "ordre_sequentiel" -> false
            "normal" -> false
            else -> null
        }
        val repetition: String? = when (m) {
            "repeter_titre", "repeat_one", "titre" -> "titre"
            "repeter_playlist", "repeat_all", "playlist" -> "playlist"
            "repeter_aucune", "repeat_off", "sans_repetition", "aucune_repetition" -> "aucune"
            "normal" -> "aucune"
            else -> null
        }
        if (aleatoire == null && repetition == null) return "Mode musical inconnu : $mode."
        if (!appliquerModes(ctx, c, aleatoire, repetition)) {
            val disponibles = c.playbackState?.customActions.orEmpty().joinToString { it.name.toString() }
            return "Le lecteur n'a pas accepté la commande de mode. Actions exposées : ${disponibles.ifBlank { "aucune" }}."
        }
        return when (m) {
            "aleatoire", "shuffle" -> "🔀 Lecture aléatoire activée (la répétition actuelle est conservée)."
            "aleatoire_off", "shuffle_off", "sans_aleatoire", "ordre_sequentiel" -> "🔀 Lecture aléatoire désactivée (la répétition actuelle est conservée)."
            "repeter_titre", "repeat_one", "titre" -> "🔂 Répétition du morceau activée (l'aléatoire actuel est conservé)."
            "repeter_playlist", "repeat_all", "playlist" -> "🔁 Répétition de toute la playlist activée (l'aléatoire actuel est conservé)."
            "repeter_aucune", "repeat_off", "sans_repetition", "aucune_repetition" -> "↪️ Répétition désactivée (l'aléatoire actuel est conservé)."
            else -> "▶️ Mode normal activé : ordre d'origine, aléatoire et répétition désactivés."
        }
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
