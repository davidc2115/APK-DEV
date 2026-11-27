package fr.dd.jarvis.societe.core

/**
 * Actions d'une intervention rangées en VRAIES catégories (« Coffret AC », « Coffret Enedis », « Nettoyage »,
 * « Remise en service », « Compteur Linky »…), chacune devenant sa propre section du rapport au lieu d'une ligne en gras
 * dans « Actions réalisées ».
 *
 * Une rubrique dictée (« Coffret AC : ») ouvre sa catégorie ; ses contrôles (« Serrage : OK ») la suivent. Un contrôle
 * qui relève clairement d'un autre domaine (« Nettoyage et ventilation OK » dicté après « Coffret Enedis : ») rejoint
 * la catégorie de ce domaine. Ce qui n'appartient à aucune catégorie reste dans « Actions réalisées ».
 */
object CategoriesActions {
    data class Categorie(val cle: String, val titre: String, val emoji: String, val lignes: List<String>)

    private data class Domaine(val cle: String, val titre: String, val emoji: String, val motif: Regex)

    // L'ordre compte : « Nettoyage des onduleurs » est du nettoyage, « Pas de défaut de terre » de la terre.
    private val DOMAINES = listOf(
        Domaine("terre", "Vérification de terre", "🌍", Regex("""(?i)\bterre\b""")),
        Domaine("linky", "Compteur Linky", "📟", Regex("""(?i)linky|\bcompteur\b|\bnon[ -]?conso|\bconsommation\b|\bproduction\s*:|\bindex\b""")),
        Domaine("nettoyage", "Nettoyage", "🧹", Regex("""(?i)nettoy|d[ée]poussi[ée]r|ventilat|a[ée]ration|\blavage\b""")),
        Domaine("remise", "Remise en service", "▶️", Regex("""(?i)remise en service|mise en service|red[ée]marr|pleinement fonctionnel|bon fonctionnement|fonctionne(?:nt)? correctement|aucun probl[èe]me""")),
        Domaine("onduleurs", "Onduleurs", "🔌", Regex("""(?i)\bonduleurs?\b|poign[ée]e\s+ess|\bess\b"""))
    )

    private val ONDULEUR_N = Regex("""(?i)^onduleur\s*(?:n[°o]\s*)?\d+\b""")
    /** « Coffret AC : serrage OK, parafoudre OK » : la rubrique et ses contrôles sur une seule ligne. */
    private val EN_LIGNE = Regex("""(?i)^(coffrets?\s+[\p{L}\d]+(?:\s+[\p{L}\d]+)?|v[ée]rification\s+de\s+(?:la\s+)?terre|nettoyage(?:\s+et\s+maintenance)?|compteur(?:\s+linky)?|linky|remise\s+en\s+service)\s*:\s*(.+)$""")
    private val RESULTAT_SEUL = Regex("""(?i)^(ok|fait|ras|conforme|valid[ée]e?|bon|nok|hs)\.?$""")
    private val PROBLEME = Regex("""(?i)\b(nok|hs|d[ée]fauts?|anomalies?|[àa] remplacer|[àa] pr[ée]voir|cass[ée]e?s?|endommag\w*|fuites?|br[ûu]l\w*|oxyd\w*|d[ée]fectu\w*|non conforme)\b""")
    private val SANS_PROBLEME = Regex("""(?i)\b(pas d[e'’]\s*|aucun\w*\s+|sans\s+)(d[ée]faut|anomalie|probl[èe]me|fuite)""")

    /**
     * Domaine d'une ligne, ou null si aucun ou si elle en couvre plusieurs (« Contrôle des coffrets, vérification de la
     * terre, nettoyage… » : un résumé, pas un contrôle). « Onduleurs » s'efface devant un autre domaine (« Nettoyage des onduleurs »).
     */
    /**
     * « Coffret DC : contrôle parafoudre OK, serrage OK » → (« Coffret DC », [« contrôle parafoudre OK », « serrage OK »]).
     * null pour une ligne qui n'ouvre pas de rubrique, dont « Remise en service : OK » (un contrôle, pas une rubrique).
     */
    fun enLigne(t: String): Pair<String, List<String>>? {
        val m = EN_LIGNE.find(t.trim()) ?: return null
        val contenu = m.groupValues[2].trim()
        if (RESULTAT_SEUL.matches(contenu)) return null
        val parties = contenu.split(Regex("""\s*[,;•]\s*""")).map { it.trim() }.filter { it.isNotBlank() }
        return m.groupValues[1].trim() to (if (parties.size > 1 && parties.all { it.length >= 3 && it.any(Char::isLetter) }) parties else listOf(contenu))
    }

    /** Clé du domaine d'une ligne (« nettoyage », « linky »…), ou null. */
    fun cleDomaine(texte: String): String? = domaineDe(texte)?.cle

    private fun domaineDe(texte: String): Domaine? {
        val tous = DOMAINES.filter { it.motif.containsMatchIn(texte) }
        val utiles = if (tous.size > 1) tous.filter { it.cle != "onduleurs" } else tous
        return utiles.singleOrNull()
    }

    private val NOMS_PROPRES = listOf("enedis" to "Enedis", "linky" to "Linky", "ac" to "AC", "dc" to "DC", "ess" to "ESS", "sma" to "SMA",
        "tgbt" to "TGBT", "agcp" to "AGCP", "huawei" to "Huawei", "fronius" to "Fronius", "sungrow" to "Sungrow")

    /** Majuscule initiale et noms propres / sigles écrits correctement (« coffret enedis » → « Coffret Enedis »). */
    private fun majuscule(s: String): String {
        var t = s.trim().replaceFirstChar { it.uppercase() }
        for ((mot, propre) in NOMS_PROPRES) t = t.replace(Regex("""(?i)(?<![\p{L}\d])$mot(?![\p{L}\d])"""), propre)
        return t
    }

    private fun emojiRubrique(titre: String): String {
        val n = normaliser(titre)
        return when {
            ONDULEUR_N.containsMatchIn(titre) -> "🔌"
            "coffret" in n || "disjoncteur" in n || "parafoudre" in n || "sectionneur" in n || "tableau" in n -> "⚡"
            "controle" in n || "verification" in n || "inspection" in n -> "🔍"
            "toiture" in n || "panneau" in n || "module" in n -> "☀️"
            "cable" in n || "connect" in n -> "🔗"
            else -> "🛠️"
        }
    }

    /** Pictogramme d'un contrôle : ✅ fait / conforme, ⚠️ problème relevé, 📏 valeur relevée. */
    fun emojiLigne(ligne: String): String = when {
        PROBLEME.containsMatchIn(ligne) && !SANS_PROBLEME.containsMatchIn(ligne) -> "⚠️"
        Regex("""\d""").containsMatchIn(ligne) && !Regex("""(?i)\bok\b""").containsMatchIn(ligne) -> "📏"
        else -> "✅"
    }

    /**
     * Range [lignes] (actions déjà nettoyées) par catégorie, dans l'ordre où elles apparaissent. La catégorie de clé ""
     * regroupe ce qui n'a pas de catégorie (« Actions réalisées »).
     */
    fun classer(lignes: List<String>): List<Categorie> {
        data class Cat(val cle: String, var titre: String, val emoji: String, val lignes: MutableList<String> = mutableListOf(), var dictee: Boolean = false)
        val cats = linkedMapOf<String, Cat>()
        fun cat(cle: String, titre: String, emoji: String, dictee: Boolean): Cat {
            val c = cats.getOrPut(cle) { Cat(cle, titre, emoji) }
            // La rubrique dictée (« Nettoyage et maintenance ») l'emporte sur le titre générique.
            if (dictee && !c.dictee) { c.titre = titre; c.dictee = true }
            return c
        }
        fun rubrique(titreBrut: String): Cat {
            val titre = majuscule(titreBrut.trim().removeSuffix(":").trim())
            if (ONDULEUR_N.containsMatchIn(titre)) return cat(normaliser(titre), titre, "🔌", true)
            val d = domaineDe(titre)
            return if (d != null && d.cle != "onduleurs") cat(d.cle, titre, d.emoji, true) else cat(normaliser(titre), titre, emojiRubrique(titre), true)
        }
        var courant: Cat? = null
        fun ranger(ligne: String) {
            val l = majuscule(ligne)
            if (l.isBlank()) return
            val c = courant
            val d = domaineDe(l)
            // Sous « Onduleur 3 : », ce qui concerne l'onduleur y reste (ventilateurs, poignée ESS, tension à la terre…).
            val cible = if (c != null && ONDULEUR_N.containsMatchIn(c.titre) && (d == null || d.cle in setOf("nettoyage", "onduleurs", "terre"))) c else {
                when {
                    d == null -> c ?: cat("", "Actions réalisées", "🛠️", false)
                    // « Nettoyage et maintenance : … Remise en service : OK » : la remise en service fait partie de la maintenance.
                    c != null && (c.cle == d.cle || (d.cle == "remise" && "maintenance" in normaliser(c.titre))) -> c
                    else -> cat(d.cle, d.titre, d.emoji, false)
                }
            }
            if (cible.lignes.none { normaliser(it) == normaliser(l) }) cible.lignes += l
        }
        for (brute in lignes) {
            val t = brute.trim().trimStart('-', '•', '*', '·', ' ').trim()
            if (t.isBlank()) continue
            val titre = ActionsDetaillees.titre(t)
            val e = if (titre == null) enLigne(t) else null
            when {
                titre != null -> courant = rubrique(titre)
                e != null -> { courant = rubrique(e.first); e.second.forEach { ranger(it) } }
                else -> ranger(t)
            }
        }
        return cats.values.filter { it.lignes.isNotEmpty() }.map { Categorie(it.cle, it.titre, it.emoji, it.lignes.toList()) }
    }

    /** Vrai s'il y a au moins une vraie catégorie (sinon la liste simple d'actions suffit). */
    fun structure(categories: List<Categorie>) = categories.any { it.cle.isNotEmpty() }

    // ---------------------------------------------------------------- doublons

    private val VIDES = setOf("dans", "avec", "pour", "sans", "plus", "tres", "bien", "bonne", "fait", "faite", "effectue", "effectuee", "realise", "realisee")

    /** Racines des mots porteurs (« nettoyage et ventilation OK » → {nettoy, ventil}) et nombres. */
    internal fun racines(s: String): Set<String> = normaliser(s).split(' ').filter { it.isNotBlank() && it != "ok" && it !in VIDES }
        .mapNotNull { m -> if (m.all(Char::isDigit)) m else if (m.length >= 4) m.take(6) else null }.toSet()

    /** [ligne] n'apporte rien de plus que [existante] (mêmes mots porteurs). */
    internal fun doublon(ligne: String, existante: String): Boolean {
        val a = racines(ligne); val b = racines(existante)
        return a.isNotEmpty() && b.containsAll(a)
    }

    /** Clés de domaine dont la section existe peut-être déjà dans le modèle (« 🧹 Nettoyage », « Compteur Linky et relevés »). */
    internal fun titreCorrespond(cle: String, titre: String): Boolean {
        val n = normaliser(titre)
        return when (cle) {
            "nettoyage" -> "nettoy" in n
            "linky" -> "linky" in n || "compteur" in n
            "terre" -> Regex("""\bterre\b""").containsMatchIn(n)
            "remise" -> "remise en service" in n
            else -> false
        }
    }
}
