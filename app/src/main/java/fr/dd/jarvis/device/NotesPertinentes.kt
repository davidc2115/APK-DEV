package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Notes de l'utilisateur utiles pour LA demande en cours, injectées en tête de réflexion des deux cerveaux :
 * consignes mémorisées par Jarvis, mémoire d'Hermès et notes Obsidian dont le titre correspond. C'est ce qui
 * permet d'appliquer « la télé = media_player.salon » noté la veille sans que l'utilisateur doive dire « regarde
 * tes notes » — et d'envoyer au modèle les seules notes utiles plutôt que toute la mémoire (moins de jetons).
 */
object NotesPertinentes {
    private val VIDES = setOf(
        "les", "des", "une", "est", "que", "qui", "pour", "par", "pas", "plus", "mon", "mes", "ton", "tes", "son", "ses", "sur",
        "dans", "avec", "sans", "tout", "tous", "toute", "toutes", "fais", "faire", "peux", "peut", "veux", "moi", "toi", "lui",
        "jarvis", "hermes", "stp", "merci", "bien", "tres", "aussi", "alors", "encore", "comme", "quoi", "quel", "quelle",
        "regarde", "dis", "dit", "donne", "mets", "met", "ensuite", "puis", "cette", "cet", "ces", "aux", "leur", "leurs", "notes", "note"
    )
    /** Le même objet sous plusieurs noms (« baisse le son de la télé » doit retrouver la note « TV salon : volume »). */
    private val SYNONYMES: List<Set<String>> = listOf(
        setOf("tele", "tv", "television", "televiseur", "telé"),
        setOf("volume", "son", "sonore"),
        setOf("lumiere", "lumieres", "lampe", "lampes", "eclairage", "ampoule"),
        setOf("chauffage", "radiateur", "thermostat", "clim", "climatisation", "pac"),
        // Poêle à granulés (la dictée écrit souvent « poils » ou « poile »).
        setOf("poele", "poeles", "poile", "poiles", "poils", "pellet", "pellets", "granule", "granules", "insert"),
        setOf("portail", "garage", "porte"),
        setOf("voiture", "auto", "vehicule"),
        setOf("box", "freebox", "routeur"),
        setOf("serveur", "debian", "ssh"),
        setOf("musique", "spotify", "deezer", "chanson", "morceau", "titre", "artiste", "album", "playlist"),
        setOf("position", "localisation", "localiser", "gps", "personne", "person", "device_tracker", "emplacement", "situe", "situer", "ha_position"),
    )

    /** Mots significatifs de la demande (+ synonymes). « son » seul est trop ambigu : gardé seulement avec « volume ». */
    internal fun motsCles(texte: String): Set<String> {
        val mots = normaliser(texte).split(' ').filter { it.length >= 2 }.toMutableSet()
        val out = mots.filter { (it.length >= 3 && it !in VIDES) || it == "tv" }.toMutableSet()
        if ("son" in mots && Regex("\\b(baisse|monte|augmente|diminue|coupe|remets?)\\b").containsMatchIn(normaliser(texte))) out += "volume"
        SYNONYMES.forEach { g -> if (g.any { it in out }) out += g.filter { it != "son" } }
        // Les formulations vocales sont souvent elliptiques : « où est X ? » ne contient pas forcément
        // le mot « localisation » présent dans la consigne mémorisée.
        val n = normaliser(texte)
        if (Regex("\\b(ou est|ou se trouve|localis\\w*|position|situe\\w*|gps|personne)\\b").containsMatchIn(n)) {
            out += setOf("position", "localisation", "gps", "personne", "person", "device_tracker", "emplacement", "ha_position", "lieu")
        }
        if (Regex("\\b(musique|chanson|morceau|titre|artiste|spotify|deezer|youtube music|yt music)\\b").containsMatchIn(n)) {
            out += setOf("musique", "chanson", "morceau", "titre", "artiste", "spotify", "deezer", "youtube")
        }
        return out
    }

    internal fun score(texte: String, mots: Set<String>): Int {
        val n = " " + normaliser(texte) + " "
        return mots.count { m -> n.contains(" $m") }
    }

    /** Contenu des notes Obsidian déjà lues (chemin → texte, heure) : le coffre passe par le fournisseur de documents
     *  d'Android, lent ; une note relue à chaque demande coûtait plusieurs centaines de millisecondes. */
    private val cacheNotes = object : LinkedHashMap<String, Pair<String, Long>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Long>>?) = size > 40
    }

    private fun lireNote(app: JarvisApp, chemin: String): String {
        val maintenant = System.currentTimeMillis()
        synchronized(cacheNotes) { cacheNotes[chemin]?.takeIf { maintenant - it.second < 10 * 60_000 }?.let { return it.first } }
        val t = app.obsidian.readNote(chemin).orEmpty()
        synchronized(cacheNotes) { cacheNotes[chemin] = t to maintenant }
        return t
    }

    /**
     * Bloc à placer dans le prompt (vide s'il n'y a rien d'utile).
     * Carnet, consignes mémorisées et mémoire d'Hermès (lus en mémoire, instantanés) sont TOUJOURS gardés ; seule la
     * partie coffre Obsidian a un délai (0,9 s). Avant, un coffre lent faisait dépasser le délai global de 1,2 s et TOUT
     * était perdu — y compris la note « la télé = media_player.salon » dont Home Assistant avait besoin.
     */
    suspend fun pour(app: JarvisApp, demande: String, delaiCoffreMs: Long = 900): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val mots = motsCles(demande)
        if (mots.isEmpty()) return@withContext ""
        val lignes = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, String>>())
        locales(app, mots, lignes)
        kotlinx.coroutines.withTimeoutOrNull(delaiCoffreMs) { coffre(app, mots, lignes) }
        val copie = synchronized(lignes) { lignes.toList() }
        if (copie.isEmpty()) return@withContext ""
        "\n\nCONSIGNES ET NOTES DE L'UTILISATEUR À APPLIQUER POUR CETTE DEMANDE (priorité aux consignes explicites ; les préférences générales s'appliquent aussi même si les mots exacts ne sont pas répétés ; respecte strictement les contraintes de format et n'ajoute pas de détails que l'utilisateur a demandé d'omettre ; " +
            "par ex. un nom d'entité, d'appareil, d'adresse ou une procédure déjà notés — une entité Home Assistant notée s'utilise telle quelle, " +
            "sans la rechercher ni en choisir une autre) :\n" +
            copie.sortedByDescending { it.first }.take(8).joinToString("\n") { "- " + it.second }.take(3000)
    }

    private fun locales(app: JarvisApp, mots: Set<String>, lignes: MutableList<Pair<Int, String>>) {
        // 0. Carnet de notes (« prends note… ») : priorité la plus haute, les plus récentes d'abord à score égal.
        runCatching {
            Carnet.toutes(app).asReversed().forEach { n ->
                val s = score(n.sujet + " " + n.texte, mots)
                if (s > 0) lignes += s * 3 + 1 to "[carnet · ${n.sujet}] ${extrait(n.texte, mots, 600)}"
            }
        }
        // 1. Consignes mémorisées par Jarvis (rubrique + contenu).
        runCatching {
            val rubriquesGenerales = setOf("general", "preferences", "preference", "habitudes", "profil", "style", "ton", "langue")
            app.memo.all().forEach { (k, v) ->
                val s = score("$k $v", mots)
                val generale = normaliser(k) in rubriquesGenerales
                // Les préférences générales sont des règles transversales : elles ne doivent pas disparaître
                // dès que la mémoire dépasse 1 800 caractères ou que la demande emploie un synonyme.
                if (s > 0 || generale) {
                    val priorite = s * 2 + if (generale) 12 else 0
                    lignes += priorite to "[mémoire · $k] ${extrait(v, mots, if (generale) 700 else 500)}"
                }
            }
        }
        // 2. Mémoire d'Hermès (notes et profil).
        runCatching {
            app.memo.entreesHermesListe().forEach { e ->
                val s = score(e, mots)
                if (s > 0) lignes += s * 2 to "[notes d'Hermès] ${e.replace('\n', ' ').take(400)}"
            }
        }
    }

    private suspend fun coffre(app: JarvisApp, mots: Set<String>, lignes: MutableList<Pair<Int, String>>) {
        // 3. Notes Obsidian dont le titre correspond (index du coffre, lecture des 2 meilleures seulement).
        runCatching {
            // Les recherches de titres sont indépendantes : les lancer en parallèle évite d'additionner
            // quatre latences d'index Obsidian sur chaque demande spécialisée.
            val recherches = coroutineScope {
                mots.filter { it.length >= 4 }.sortedByDescending { it.length }.take(4).map { mot ->
                    async(Dispatchers.IO) {
                        runCatching { app.knowledgeRepository.searchNodes(mot, 15) }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
            val candidats = recherches.flatten()
                .filter { it.sourcePath.endsWith(".md", true) && !it.sourcePath.contains("Mémoire JARVIS") && !it.sourcePath.contains("Mémoire Hermès") && !it.sourcePath.contains(Carnet.DOSSIER) }
                .distinctBy { it.sourcePath }
                .map { it to score(it.title, mots) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(2)
            // Les deux notes sont lues en même temps (et gardées en cache 10 min).
            coroutineScope {
                candidats.map { (node, s) ->
                    async(Dispatchers.IO) {
                        val contenu = runCatching { lireNote(app, node.sourcePath) }.getOrDefault("")
                        if (contenu.isNotBlank() && score(contenu, mots) >= 1) lignes += s * 2 + 1 to "[note « ${node.sourcePath.removeSuffix(".md")} »] ${extrait(contenu, mots, 700)}"
                    }
                }.awaitAll()
            }
        }
    }

    /** Les lignes d'un texte qui contiennent les mots de la demande (sinon le début). */
    private fun extrait(texte: String, mots: Set<String>, max: Int): String {
        val lignes = texte.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("---") }
        val utiles = lignes.filter { score(it, mots) > 0 }
        return (if (utiles.isNotEmpty()) utiles else lignes).joinToString(" · ").replace(Regex("\\s+"), " ").take(max)
    }
}
