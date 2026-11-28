package fr.dd.jarvis.device

/**
 * « Tout relier » : chaque note et chaque fichier du coffre Obsidian reçoit au moins un lien.
 *
 * 1. MENTIONS NON LIÉES : le titre d'une note écrit en clair dans une autre note (« j'ai appelé Dupont ») devient un
 *    lien ([[Dupont]]) — première mention de chaque note seulement, jamais dans le frontmatter, le code, un lien
 *    existant ou une adresse web, et jamais dans les notes que JARVIS génère et relit (Société, JARVIS, Contacts).
 * 2. Ce qui reste sans lien entrant (notes, images, PDF…) est rangé dans l'INDEX de son dossier (« Index — Clients »),
 *    lui-même relié depuis « Index du coffre ». Les index sont régénérés à chaque passage ; leurs propres liens ne
 *    comptent jamais comme des liens « réels » (un fichier sorti de l'isolement par un vrai lien quitte l'index).
 *
 * Partie pure (testable) : le plan et la transformation d'un texte. L'écriture dans le coffre est faite par [relier].
 */
object LiensCoffre {
    const val INDEX_RACINE = "Index du coffre"
    const val PREFIXE_INDEX = "Index — "

    /** Dossiers dont JARVIS génère et relit les notes : jamais modifiés (on n'y transforme pas de mention). */
    private val DOSSIERS_PROTEGES = listOf("société/", "societe/", "jarvis/", "contacts/")

    /** Clé de comparaison des chemins : minuscules, forme Unicode NFC (Android renvoie parfois les accents décomposés). */
    fun cle(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC).lowercase()

    fun estIndex(chemin: String): Boolean {
        val n = chemin.substringAfterLast('/').removeSuffix(".md")
        return n == INDEX_RACINE || n.startsWith(PREFIXE_INDEX)
    }

    internal fun protegee(chemin: String): Boolean = chemin.lowercase().let { c -> DOSSIERS_PROTEGES.any { c.startsWith(it) } } || estIndex(chemin)

    private fun id(chemin: String) = chemin.removeSuffix(".md").removeSuffix(".MD")
    private fun nom(chemin: String) = id(chemin).substringAfterLast('/')
    private fun dossier(chemin: String) = chemin.substringBeforeLast('/', "")

    // ------------------------------------------------------------------ résolution des liens existants

    private val WIKI = Regex("!?\\[\\[([^\\]|#^]+)[^\\]]*]]")
    private val MARKDOWN = Regex("!?\\[[^\\]\\n]*]\\(<?([^)>\\n]+?)>?(?:\\s+\"[^\"]*\")?\\)")

    /** Note (chemin sans .md) ou fichier (chemin complet) visé par un lien. */
    data class Cible(val note: String? = null, val fichier: String? = null)

    class Index(notes: Collection<String>, fichiers: Collection<String>) {
        val ids = notes.map { id(it) }
        private val parId = ids.associateBy { cle(it) }
        val parNom: Map<String, List<String>> = ids.groupBy { cle(it.substringAfterLast('/')) }
        private val fichiersParChemin = fichiers.associateBy { cle(it) }
        private val fichiersParNom = fichiers.groupBy { cle(it.substringAfterLast('/')) }

        private fun normaliser(chemin: String): String {
            val pile = ArrayList<String>()
            for (p in chemin.split('/')) when (p) { "", "." -> {}; ".." -> if (pile.isNotEmpty()) pile.removeAt(pile.size - 1); else -> pile += p }
            return pile.joinToString("/")
        }

        fun resoudre(brut: String, dossierSource: String): Cible? {
            val c = brut.trim()
            if (c.isBlank() || c.startsWith("#") || Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(c)) return null
            val essais = listOfNotNull(normaliser(c), dossierSource.takeIf { it.isNotBlank() }?.let { normaliser("$it/$c") })
            for (e in essais) {
                val l = cle(e)
                parId[l.removeSuffix(".md")]?.let { return Cible(note = it) }
                fichiersParChemin[l]?.let { return Cible(fichier = it) }
            }
            // Par simple nom : seulement s'il n'y a qu'une note (ou un fichier) de ce nom. Avec des homonymes, on ne devine
            // pas laquelle est visée (le graphe pouvait en choisir une autre) : elles sont toutes rangées dans leur index.
            val n = cle(c.substringAfterLast('/'))
            parNom[n.removeSuffix(".md")]?.singleOrNull()?.let { return Cible(note = it) }
            fichiersParNom[n]?.singleOrNull()?.let { return Cible(fichier = it) }
            return null
        }

        fun liens(texte: String, source: String): List<Cible> {
            val d = dossier(source)
            val out = ArrayList<Cible>()
            WIKI.findAll(texte).forEach { m -> resoudre(m.groupValues[1], d)?.let { out += it } }
            MARKDOWN.findAll(texte).forEach { m ->
                // « # » = titre visé ; un « # » du nom de fichier est encodé (%23) : on coupe AVANT de décoder.
                val brut = m.groupValues[1].substringBefore('#')
                val cible = runCatching { java.net.URLDecoder.decode(brut.replace("+", "%2B"), "UTF-8") }.getOrDefault(brut)
                resoudre(cible, d)?.let { out += it }
            }
            return out
        }
    }

    // ------------------------------------------------------------------ mentions en clair → liens

    /** Titre à repérer dans le texte et le lien à écrire à sa place. */
    data class Mention(val titre: String, val cible: String, val ambigu: Boolean)

    private val MOT = Regex("[\\p{L}\\p{N}]+")

    /** Plages du texte où l'on n'écrit jamais : frontmatter, blocs et morceaux de code, liens existants, adresses. */
    internal fun zonesProtegees(texte: String): List<IntRange> {
        val z = ArrayList<IntRange>()
        if (texte.startsWith("---")) {
            val fin = texte.indexOf("\n---", 3)
            if (fin > 0) z += 0..(fin + 4).coerceAtMost(texte.length - 1)
        }
        listOf(Regex("```[\\s\\S]*?(```|$)"), Regex("`[^`\\n]+`"), Regex("!?\\[\\[[^\\]]*]]"), Regex("!?\\[[^\\]\\n]*]\\([^)\\n]*\\)"),
            Regex("<[^>\\n]+>"), Regex("(https?|ftp|mailto|obsidian|jarvis-file):[^\\s)\\]]+", RegexOption.IGNORE_CASE),
            Regex("(?m)^\\s*[\\w-]+::.*$"), Regex("(?<![\\p{L}\\p{N}])#[\\p{L}\\p{N}_/-]+")
        ).forEach { r -> r.findAll(texte).forEach { z += it.range } }
        return z
    }

    private fun mots(titre: String) = MOT.findAll(titre.lowercase()).map { it.value }.toList()

    /** Un titre assez précis pour être cherché en clair (pas « a », « Note 1 »… ni un nombre seul). */
    internal fun titreUtilisable(titre: String): Boolean {
        val m = mots(titre)
        if (m.isEmpty() || m.size > 8) return false
        if (m.none { w -> w.any { it.isLetter() } }) return false
        return if (m.size == 1) m[0].length >= 4 else titre.trim().length >= 5
    }

    /**
     * Transforme dans [texte] la PREMIÈRE mention en clair de chaque [mentions] en lien Obsidian. Renvoie le texte et
     * les cibles reliées. Le texte de la mention est conservé (« [[Dupont|dupont]] » si la casse diffère).
     */
    fun lierMentions(texte: String, mentions: List<Mention>): Pair<String, List<String>> = lierMentions(texte, Recherche(mentions), null)

    /** Titres à chercher, indexés par leur premier mot (préparé une fois pour tout le coffre). */
    class Recherche(mentions: List<Mention>) {
        internal val parPremierMot = HashMap<String, MutableList<Pair<List<String>, Mention>>>().also { m ->
            mentions.filter { titreUtilisable(it.titre) }.forEach { x -> val ms = mots(x.titre); m.getOrPut(ms[0]) { mutableListOf() } += ms to x }
            m.values.forEach { l -> l.sortByDescending { it.first.size } }
        }
    }

    /** [exclure] : cible jamais reliée dans ce texte (la note elle-même). */
    internal fun lierMentions(texte: String, recherche: Recherche, exclure: String?): Pair<String, List<String>> {
        val parPremierMot = recherche.parPremierMot
        if (parPremierMot.isEmpty() || texte.isEmpty()) return texte to emptyList()
        val zones = zonesProtegees(texte).sortedBy { it.first }
        fun libre(r: IntRange) = zones.none { it.first <= r.last && r.first <= it.last }
        // Mot collé à un autre par un trait d'union, un « _ » ou un point (« Saint-Jean », « devis.pdf ») : pas une mention.
        fun soude(r: IntRange): Boolean {
            fun lettre(i: Int) = i in texte.indices && texte[i].isLetterOrDigit()
            val avant = r.first - 1; val apres = r.last + 1
            return (avant >= 0 && texte[avant] in "-_." && lettre(avant - 1)) || (apres < texte.length && texte[apres] in "-_." && lettre(apres + 1))
        }
        val jetons = MOT.findAll(texte).toList()
        val faits = HashSet<String>()
        val remplacements = ArrayList<Pair<IntRange, String>>()
        var i = 0
        while (i < jetons.size) {
            val candidats = parPremierMot[jetons[i].value.lowercase()]
            var avance = 1
            if (candidats != null) for ((ms, m) in candidats) {
                if (m.cible in faits || m.cible == exclure || i + ms.size > jetons.size) continue
                if ((ms.indices).any { k -> jetons[i + k].value.lowercase() != ms[k] }) continue
                val plage = jetons[i].range.first..jetons[i + ms.size - 1].range.last
                val vu = texte.substring(plage)
                if (!vu.equals(m.titre, ignoreCase = true) || !libre(plage) || soude(plage)) continue
                val lien = when {
                    m.ambigu -> "[[${m.cible}|$vu]]"
                    vu == m.titre -> "[[${m.titre}]]"
                    else -> "[[${m.titre}|$vu]]"
                }
                remplacements += plage to lien
                faits += m.cible
                avance = ms.size
                break
            }
            i += avance
        }
        if (remplacements.isEmpty()) return texte to emptyList()
        val sb = StringBuilder(texte)
        remplacements.sortedByDescending { it.first.first }.forEach { (r, l) -> sb.replace(r.first, r.last + 1, l) }
        return sb.toString() to faits.toList()
    }

    // ------------------------------------------------------------------ plan complet

    data class Plan(
        /** Note à modifier → mentions à y transformer en liens. */
        val mentions: Map<String, List<Mention>>,
        val nbMentions: Int,
        /** Index à écrire : chemin (.md) → contenu ; contenu vide = index devenu inutile (à supprimer). */
        val index: Map<String, String>,
        val notesIndexees: Int,
        val fichiersIndexes: Int,
        /** Notes sans lien entrant et fichiers jamais liés, avant. */
        val isolesAvant: Int,
    ) {
        val rienAFaire get() = nbMentions == 0 && notesIndexees == 0 && fichiersIndexes == 0 && index.values.none { it.isBlank() }
    }

    /** [notes] : chemin (.md) → texte (index générés compris) ; [fichiers] : chemins des autres fichiers du coffre. */
    /** [mentions] = false : rien n'est écrit dans les notes, tout ce qui est isolé va dans les index (passage automatique). */
    fun planifier(notes: Map<String, String>, fichiers: List<String>, mentions: Boolean = true): Plan {
        val idx = Index(notes.keys, fichiers)
        // Liens réels (hors index générés).
        val entrants = HashSet<String>(); val fichiersLies = HashSet<String>()
        for ((chemin, texte) in notes) {
            if (estIndex(chemin)) continue
            val src = id(chemin)
            for (c in idx.liens(texte, chemin)) {
                c.note?.let { if (it != src) entrants += it }
                c.fichier?.let { fichiersLies += it }
            }
        }
        val isolees = notes.keys.filter { !estIndex(it) && id(it) !in entrants }
        val fichiersIsoles = fichiers.filter { it !in fichiersLies }

        // 1. Mentions en clair des notes isolées.
        val homonymes = idx.parNom.filterValues { it.size > 1 }.keys
        val aChercher = if (!mentions) emptyList() else isolees.mapNotNull { c ->
            val n = nom(c)
            if (!titreUtilisable(n)) null else Mention(n, id(c), cle(n) in homonymes)
        }
        val aLier = LinkedHashMap<String, List<Mention>>()
        val lieesParMention = HashSet<String>()
        var total = 0
        val recherche = Recherche(aChercher)
        if (aChercher.isNotEmpty()) for ((chemin, texte) in notes) {
            if (protegee(chemin)) continue
            val liees = lierMentions(texte, recherche, exclure = id(chemin)).second.toSet()
            if (liees.isEmpty()) continue
            aLier[chemin] = aChercher.filter { it.cible in liees }
            lieesParMention += liees
            total += liees.size
        }

        // 2. Ce qui reste isolé → index de son dossier.
        val restantes = isolees.filter { id(it) !in lieesParMention }
        val parDossier = LinkedHashMap<String, Pair<MutableList<String>, MutableList<String>>>()
        restantes.forEach { parDossier.getOrPut(dossier(it)) { mutableListOf<String>() to mutableListOf() }.first += it }
        fichiersIsoles.forEach { parDossier.getOrPut(dossier(it)) { mutableListOf<String>() to mutableListOf() }.second += it }
        val index = LinkedHashMap<String, String>()
        val racineNotes = parDossier[""]?.first.orEmpty(); val racineFichiers = parDossier[""]?.second.orEmpty()
        val indexDossiers = ArrayList<String>()
        for ((d, contenu) in parDossier.toSortedMap()) {
            if (d.isEmpty()) continue
            val chemin = "$d/$PREFIXE_INDEX${d.substringAfterLast('/')}.md"
            index[chemin] = page("📇 ${d.replace("/", " › ")}", "Notes et fichiers du dossier « $d » qui n'avaient aucun lien.", contenu.first, contenu.second)
            indexDossiers += chemin
        }
        // Index de dossiers devenus inutiles : vidés (supprimés à l'écriture).
        notes.keys.filter { estIndex(it) && nom(it) != INDEX_RACINE && it !in index }.forEach { index[it] = "" }
        val sections = StringBuilder()
        if (indexDossiers.isNotEmpty()) sections.append("## Dossiers\n").append(indexDossiers.joinToString("\n") { "- ${lien(it)}" }).append("\n\n")
        val racineExiste = notes.keys.any { it.equals("$INDEX_RACINE.md", ignoreCase = true) }
        if (indexDossiers.isNotEmpty() || racineNotes.isNotEmpty() || racineFichiers.isNotEmpty() || racineExiste)
            index["$INDEX_RACINE.md"] = if (indexDossiers.isEmpty() && racineNotes.isEmpty() && racineFichiers.isEmpty()) "" else page("📇 Index du coffre", "Relie chaque note et chaque fichier qui n'avait aucun lien (régénéré par JARVIS à chaque « Tout relier »).",
                racineNotes, racineFichiers, sections.toString())
        return Plan(aLier, total, index, restantes.size, fichiersIsoles.size, isolees.size + fichiersIsoles.size)
    }

    /** Caractères interdits dans un [[lien]] Obsidian (titre, bloc, alias, crochets). */
    private val HORS_WIKI = Regex("[#|^\\[\\]]")

    /**
     * Lien vers une note ou un fichier. Un nom contenant « # », « | », « ^ » ou des crochets casse un [[lien]] (Obsidian
     * le lit comme un titre ou un alias : le fichier restait orphelin) : lien Markdown à chemin encodé à la place.
     */
    internal fun lien(chemin: String): String {
        val estNote = chemin.endsWith(".md", true)
        val cible = if (estNote) id(chemin) else chemin
        val libelle = if (estNote) nom(chemin) else chemin.substringAfterLast('/')
        if (!HORS_WIKI.containsMatchIn(chemin)) return "[[$cible|$libelle]]"
        val encode = chemin.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        // Libellé : crochets en parenthèses, « # » en « ＃ » (sinon « #urgent » d'un nom de fichier deviendrait un faux tag).
        return "[${libelle.replace('[', '(').replace(']', ')').replace('#', '＃')}]($encode)"
    }

    private fun page(titre: String, sousTitre: String, notes: List<String>, fichiers: List<String>, avant: String = ""): String = buildString {
        append("---\njarvis: index\n---\n# $titre\n\n_${sousTitre}_\n\n")
        append(avant)
        if (notes.isNotEmpty()) append("## Notes\n").append(notes.sortedBy { it.lowercase() }.joinToString("\n") { "- ${lien(it)}" }).append("\n\n")
        if (fichiers.isNotEmpty()) append("## Fichiers\n").append(fichiers.sortedBy { it.lowercase() }.joinToString("\n") { "- ${lien(it)}" }).append("\n")
    }.trimEnd() + "\n"

    // ------------------------------------------------------------------ dans le coffre

    /** Compte rendu d'un passage. */
    data class Bilan(val plan: Plan, val notesModifiees: Int, val indexEcrits: Int, val erreurs: List<String>) {
        fun texte(applique: Boolean): String = buildString {
            val p = plan
            if (p.rienAFaire) return "🔗 Tout est déjà relié : aucune note ni aucun fichier sans lien dans le coffre."
            append(if (applique) "🔗 Coffre relié.\n" else "🔗 Aperçu (rien n'est encore modifié) :\n")
            append("• ${p.isolesAvant} élément(s) sans lien au départ\n")
            append("• ${p.nbMentions} mention(s) en clair ${if (applique) "transformée(s)" else "à transformer"} en liens, dans ${p.mentions.size} note(s)\n")
            append("• ${p.notesIndexees} note(s) et ${p.fichiersIndexes} fichier(s) ${if (applique) "rangés" else "à ranger"} dans l'index de leur dossier " +
                "(${p.index.count { it.value.isNotBlank() }} index, reliés depuis « $INDEX_RACINE »)")
            if (applique) append("\n• $notesModifiees note(s) modifiée(s), $indexEcrits index écrit(s)")
            if (erreurs.isNotEmpty()) append("\n⚠️ ${erreurs.size} écriture(s) impossible(s) : ${erreurs.take(4).joinToString(" ; ")}")
        }
    }

    /**
     * Analyse tout le coffre et, si [appliquer], écrit les liens (notes de l'utilisateur) et les index. À appeler hors du
     * fil principal. [etape] : avancement lisible.
     */
    fun relier(app: fr.dd.jarvis.JarvisApp, appliquer: Boolean, mentions: Boolean = true, etape: (String) -> Unit = {}): Bilan {
        val o = app.obsidian
        etape("Inventaire du coffre…")
        o.oublierCache()
        val tout = o.toutLister()
        val fichiers = tout.filter { !it.second && !it.first.endsWith(".md", true) }.map { it.first }
        etape("Lecture des notes…")
        val notes = LinkedHashMap<String, String>()
        o.parcourirContenus(limite = 3000) { chemin, texte -> notes[chemin.replace(java.io.File.separatorChar, '/')] = texte }
        etape("Recherche des mentions et des fichiers isolés…")
        val plan = planifier(notes, fichiers, mentions)
        if (!appliquer || plan.rienAFaire) return Bilan(plan, 0, 0, emptyList())
        val erreurs = ArrayList<String>()
        var modifiees = 0; var ecrits = 0
        // Toutes les lectures complètes AVANT les écritures (le texte lu plus haut peut être tronqué : jamais réécrit tel quel).
        etape("Préparation de ${plan.mentions.size} note(s)…")
        val nouveaux = plan.mentions.mapNotNull { (chemin, mentions) ->
            val complet = runCatching { o.readNote(chemin) }.getOrNull() ?: return@mapNotNull null.also { erreurs += "$chemin (illisible)" }
            val (texte, liees) = lierMentions(complet, mentions)
            if (liees.isEmpty() || texte == complet) null else chemin to texte
        }
        o.enLot {
            nouveaux.forEachIndexed { i, (chemin, texte) ->
                if (i % 10 == 0) etape("Liens : ${i + 1}/${nouveaux.size}…")
                runCatching { o.writeNote(chemin, texte, append = false); modifiees++ }.onFailure { erreurs += "$chemin (${it.message})" }
            }
            etape("Index des dossiers…")
            for ((chemin, contenu) in plan.index) {
                if (contenu.isBlank()) { runCatching { if (notes.containsKey(chemin)) o.supprimerNote(chemin) }; continue }
                // Index identique à celui déjà écrit : rien à faire (pas de réécriture à chaque passage automatique).
                if (notes[chemin] == contenu) continue
                runCatching { o.writeNote(chemin, contenu, append = false); ecrits++ }.onFailure { erreurs += "$chemin (${it.message})" }
            }
        }
        fr.dd.jarvis.voice.JournalVoix.noter("obsidian", "coffre relié : ${plan.nbMentions} mentions, $ecrits index, ${erreurs.size} erreur(s)")
        return Bilan(plan, modifiees, ecrits, erreurs)
    }
}
