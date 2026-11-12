package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser

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
        setOf("musique", "spotify", "deezer", "chanson"),
    )

    /** Mots significatifs de la demande (+ synonymes). « son » seul est trop ambigu : gardé seulement avec « volume ». */
    internal fun motsCles(texte: String): Set<String> {
        val mots = normaliser(texte).split(' ').filter { it.length >= 2 }.toMutableSet()
        val out = mots.filter { (it.length >= 3 && it !in VIDES) || it == "tv" }.toMutableSet()
        if ("son" in mots && Regex("\\b(baisse|monte|augmente|diminue|coupe|remets?)\\b").containsMatchIn(normaliser(texte))) out += "volume"
        SYNONYMES.forEach { g -> if (g.any { it in out }) out += g.filter { it != "son" } }
        return out
    }

    internal fun score(texte: String, mots: Set<String>): Int {
        val n = " " + normaliser(texte) + " "
        return mots.count { m -> n.contains(" $m") }
    }

    /** Bloc à placer dans le prompt (vide s'il n'y a rien d'utile). */
    suspend fun pour(app: JarvisApp, demande: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        kotlinx.coroutines.withTimeoutOrNull(1200) { calculer(app, demande) }.orEmpty()
    }

    private suspend fun calculer(app: JarvisApp, demande: String): String {
        val mots = motsCles(demande)
        if (mots.isEmpty()) return ""
        val lignes = mutableListOf<Pair<Int, String>>()
        // 0. Carnet de notes (« prends note… ») : priorité la plus haute, les plus récentes d'abord à score égal.
        runCatching {
            Carnet.toutes(app).asReversed().forEach { n ->
                val s = score(n.sujet + " " + n.texte, mots)
                if (s > 0) lignes += s * 3 + 1 to "[carnet · ${n.sujet}] ${extrait(n.texte, mots, 600)}"
            }
        }
        // 1. Consignes mémorisées par Jarvis (rubrique + contenu).
        runCatching {
            app.memo.all().forEach { (k, v) ->
                val s = score("$k $v", mots)
                if (s > 0) lignes += s * 2 to "[mémoire · $k] ${extrait(v, mots, 500)}"
            }
        }
        // 2. Mémoire d'Hermès (notes et profil).
        runCatching {
            app.memo.entreesHermesListe().forEach { e ->
                val s = score(e, mots)
                if (s > 0) lignes += s * 2 to "[notes d'Hermès] ${e.replace('\n', ' ').take(400)}"
            }
        }
        // 3. Notes Obsidian dont le titre correspond (index du coffre, lecture des 2 meilleures seulement).
        runCatching {
            val candidats = mots.filter { it.length >= 4 }.sortedByDescending { it.length }.take(4)
                .flatMap { m -> runCatching { app.knowledgeRepository.searchNodes(m, 15) }.getOrDefault(emptyList()) }
                .filter { it.sourcePath.endsWith(".md", true) && !it.sourcePath.contains("Mémoire JARVIS") && !it.sourcePath.contains("Mémoire Hermès") && !it.sourcePath.contains(Carnet.DOSSIER) }
                .distinctBy { it.sourcePath }
                .map { it to score(it.title, mots) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(2)
            candidats.forEach { (node, s) ->
                val contenu = app.obsidian.readNote(node.sourcePath).orEmpty()
                if (contenu.isNotBlank() && score(contenu, mots) >= 1) lignes += s * 2 + 1 to "[note « ${node.sourcePath.removeSuffix(".md")} »] ${extrait(contenu, mots, 700)}"
            }
        }
        if (lignes.isEmpty()) return ""
        return "\n\nNOTES DE L'UTILISATEUR UTILES POUR CETTE DEMANDE (à appliquer en priorité, avant toute supposition ; " +
            "par ex. un nom d'entité, d'appareil, d'adresse ou une procédure déjà notés — une entité Home Assistant notée s'utilise telle quelle, " +
            "sans la rechercher ni en choisir une autre) :\n" +
            lignes.sortedByDescending { it.first }.take(8).joinToString("\n") { "- " + it.second }.take(3000)
    }

    /** Les lignes d'un texte qui contiennent les mots de la demande (sinon le début). */
    private fun extrait(texte: String, mots: Set<String>, max: Int): String {
        val lignes = texte.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("---") }
        val utiles = lignes.filter { score(it, mots) > 0 }
        return (if (utiles.isNotEmpty()) utiles else lignes).joinToString(" · ").replace(Regex("\\s+"), " ").take(max)
    }
}
