package fr.dd.jarvis.images

import java.text.Normalizer

/**
 * Garde-fous du Studio d'images, appliqués EN LOCAL avant tout appel réseau (et de nouveau sur le prompt réécrit par
 * une IA) : ils ne dépendent d'aucun modèle et ne se désactivent pas.
 *
 * Contenu adulte autorisé (personnages adultes, fictifs, consentants). Refusé dès que la demande est explicite :
 *  - tout ce qui évoque un mineur ou une apparence juvénile (mots, âges < 18 ans, uniforme scolaire…) ;
 *  - une personne réelle ou identifiable (célébrité, sosie, « ma voisine », un nom présent dans les contacts…) ;
 *  - l'absence de consentement (viol, contrainte, personne endormie / droguée / inconsciente…) ;
 *  - l'inceste et les animaux.
 */
object GardeImages {

    enum class Motif(val message: String) {
        MINEUR("Rien d'explicite avec un mineur ou une apparence juvénile : tous les personnages doivent être clairement adultes."),
        PERSONNE_REELLE("Pas d'image explicite d'une personne réelle ou identifiable (célébrité, proche, contact, sosie) : uniquement des personnages fictifs."),
        CONSENTEMENT("Pas de scène explicite sans consentement (contrainte, personne endormie, droguée ou inconsciente)."),
        INCESTE("Pas de scène explicite entre membres d'une même famille."),
        ANIMAL("Pas d'animal dans une scène explicite.")
    }

    data class Refus(val motif: Motif, val terme: String) {
        val message: String get() = "🚫 " + motif.message + " (« $terme »)"
    }

    /** Minuscules, sans accents, apostrophes et tirets réduits à des espaces : « Lycéenne » → « lyceenne ». */
    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase().replace("œ", "oe").replace("æ", "ae"), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[’'`´_\\-]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun contient(n: String, terme: String): Boolean {
        val t = norm(terme)
        if (t.isEmpty()) return false
        return Regex("(^|[^a-z0-9])" + Regex.escape(t) + "([^a-z0-9]|$)").containsMatchIn(n)
    }

    private fun premier(n: String, termes: Collection<String>): String? = termes.firstOrNull { contient(n, it) }

    /** Vocabulaire qui rend une demande « adulte » (même sans le bouton 🔞). */
    val EXPLICITE = listOf(
        "nsfw", "18+", "adulte", "contenu adulte", "uncensored", "non censure", "non censuree",
        "nu", "nue", "nus", "nues", "toute nue", "tout nu", "nudite", "nude", "nudes", "naked", "nudity", "topless", "seins nus",
        "sexy", "erotique", "erotiques", "erotisme", "erotic", "erotica", "sensuel", "sensuelle", "sensual", "lascive", "lascif",
        "lingerie", "sous vetements", "string", "boudoir", "strip tease", "striptease", "stripteaseuse",
        "porn", "porno", "pornographique", "pornographic", "xxx", "hentai", "ecchi", "explicite", "explicit",
        "sexe", "sexuel", "sexuelle", "sexual", "sex", "bdsm", "fetish", "fetiche", "fetichiste", "coquine", "coquin",
        "seins", "poitrine nue", "fesses", "sein"
    )

    private val MINEURS = listOf(
        "enfant", "enfants", "enfantin", "enfantine", "gamin", "gamine", "gamins", "gosse", "gosses", "fillette", "fillettes",
        "garconnet", "petite fille", "petit garcon", "jeune fille", "jeunes filles", "jeune garcon", "ado", "ados",
        "adolescent", "adolescente", "adolescents", "adolescentes", "mineur", "mineure", "mineurs", "mineures",
        "collegien", "collegienne", "collegiennes", "lyceen", "lyceenne", "lyceennes", "ecolier", "ecoliere", "ecolieres",
        "bebe", "bebes", "nourrisson", "bambin", "puberte", "prepubere", "impubere", "maternelle", "ecole primaire", "college", "lycee",
        "uniforme scolaire", "cartable", "air jeune", "air enfantin", "visage d enfant", "corps d enfant", "tres jeune", "toute jeune",
        "child", "children", "childlike", "childish", "kid", "kids", "kiddo", "toddler", "baby", "infant", "teen", "teens",
        "teenager", "teenagers", "teenage", "underage", "under age", "minor", "minors", "preteen", "pre teen", "tween",
        "young girl", "little girl", "young boy", "little boy", "schoolgirl", "schoolgirls", "schoolboy", "school uniform",
        "school girl", "middle school", "high school", "elementary school", "loli", "lolis", "lolita", "lolicon", "shota",
        "shotacon", "jailbait", "young looking", "youthful", "juvenile", "juvenil", "juvenile", "barely legal", "barely 18",
        "just turned 18", "tout juste majeure", "a peine majeure", "petite ado", "very young", "girl next door teen"
    )

    private val AGES_MOTS = listOf(
        "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze", "treize", "quatorze",
        "quinze", "seize", "dix sept", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen"
    )

    private val CONSENTEMENT = listOf(
        "viol", "viole", "violee", "violer", "violent", "rape", "raped", "raping", "rapist", "non consentant", "non consentante",
        "nonconsentant", "sans consentement", "sans son consentement", "contre sa volonte", "contre son gre", "de force",
        "par la force", "non consensual", "nonconsensual", "noncon", "forcee", "forcees", "forced", "forcing", "contrainte",
        "droguee", "drogue", "sous drogue", "drugged", "inconscient", "inconsciente", "unconscious", "endormi", "endormie",
        "asleep", "sleeping", "evanoui", "evanouie", "passed out", "ivre morte", "kidnappe", "kidnappee", "kidnapped",
        "enlevee", "abducted", "sequestre", "sequestree", "esclave sexuelle", "sex slave", "agression", "agressee",
        "assault", "assaulted", "molested", "attouchement", "attouchements", "en pleurs", "crying", "victime", "victim",
        "snuff", "gore", "mutile", "mutilee", "torture", "torturee", "hypnotisee", "hypnotized", "mind control"
    )

    private val PERSONNES_REELLES = listOf(
        "celebrite", "celebrites", "celebrity", "celebrities", "celebre", "famous", "star de", "star du", "actrice", "acteur",
        "chanteuse", "chanteur", "influenceuse", "influenceur", "youtubeuse", "youtubeur", "streameuse", "streamer",
        "tiktokeuse", "instagrameuse", "politicienne", "politicien", "deputee", "ministre", "sosie", "lookalike", "look alike",
        "ressemblant a", "qui ressemble a", "ressemble a", "looks like", "resembling", "deepfake", "deep fake", "face swap",
        "faceswap", "d apres la photo", "a partir de la photo", "real person", "personne reelle", "vraie personne", "en vrai",
        "mon ex", "mon ancienne", "ma voisine", "mon voisin", "ma collegue", "mon collegue", "ma patronne", "mon patron",
        "ma prof", "mon prof", "ma professeure", "ma femme", "mon mari", "ma copine", "mon copain", "ma compagne",
        "mon compagnon", "ma petite amie", "mon petit ami", "ma cousine", "mon cousin", "ma secretaire", "ma cliente",
        "mon client", "ma meilleure amie", "mon meilleur ami", "ma belle soeur", "mon beau frere", "mon amie", "mon ami",
        "my ex", "my neighbor", "my neighbour", "my coworker", "my colleague", "my boss", "my wife", "my husband",
        "my girlfriend", "my boyfriend", "my cousin", "my teacher", "my friend", "my secretary"
    )

    private val INCESTE = listOf(
        "inceste", "incest", "incestueux", "incestueuse", "ma soeur", "mon frere", "ma mere", "mon pere", "ma fille", "mon fils",
        "ma tante", "mon oncle", "ma niece", "mon neveu", "sa soeur", "son frere", "sa mere", "son pere", "sa fille", "son fils",
        "sa tante", "son oncle", "sa niece", "son neveu", "maman", "papa", "frere et soeur", "mere et fils", "pere et fille",
        "my sister", "my brother", "my mother", "my mom", "my father", "my dad", "my daughter", "my son", "his sister",
        "her brother", "his mother", "her father", "his daughter", "her son", "mother and son", "father and daughter",
        "brother and sister", "mommy", "daddy"
    )

    private val ANIMAUX = listOf(
        "zoophilie", "zoophile", "bestialite", "bestiality", "zoophilia", "animal", "animaux", "chien", "chienne", "cheval",
        "jument", "etalon", "dog", "dogs", "horse", "horses", "canine", "equin"
    )

    /** Demande à caractère sexuel ou de nudité (détection locale, sans IA). */
    fun estExplicite(texte: String): Boolean {
        var n = norm(texte)
        ANODINS.forEach { n = n.replace(Regex("(^|[^a-z0-9])" + Regex.escape(it) + "([^a-z0-9]|$)"), " ") }
        return premier(n, EXPLICITE) != null
    }

    /** Tournures avec « nu » sans rapport avec la nudité. */
    private val ANODINS = listOf(
        "pieds nus", "pied nu", "nu pieds", "nus pieds", "tete nue", "nu tete", "bras nus", "epaules nues", "jambes nues",
        "torse nu", "dos nu", "a l oeil nu", "mur nu", "murs nus", "sol nu", "arbre nu", "arbres nus", "branches nues", "fil nu"
    )

    /** Âge chiffré ou écrit inférieur à 18 ans (« 16 ans », « 15yo », « seize ans », « fifteen years old »). */
    fun ageMineur(texte: String): String? {
        val n = norm(texte)
        Regex("(?<![0-9])([0-9]{1,2}) ?(ans|an|years? old|yo|y o|yrs? old|year old)(?![a-z])").findAll(n).forEach { m ->
            val v = m.groupValues[1].toIntOrNull() ?: return@forEach
            if (v < 18) return m.value.trim()
        }
        // « dix-huit ans », « vingt-deux ans », « twenty one years old » : le mot d'unité suit une dizaine → majeur.
        val dizaine = "(?<!(dix|vingt|trente|quarante|cinquante|soixante|septante|octante|nonante|et|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety) )"
        AGES_MOTS.forEach { mot ->
            val r = Regex("(^|[^a-z0-9])$dizaine" + Regex.escape(mot) + " (ans|an|years? old)([^a-z0-9]|$)")
            r.find(n)?.let { return it.value.trim() }
        }
        return null
    }

    /**
     * Refus éventuel. Hors contenu explicite rien n'est bloqué ici (un enfant dans un parc, une célébrité en illustration
     * de presse… relèvent des règles normales du fournisseur). [nomsProteges] : noms des contacts du téléphone.
     */
    fun verifier(texte: String, explicite: Boolean, nomsProteges: Collection<String> = emptyList()): Refus? {
        if (!explicite) return null
        val n = norm(texte)
        premier(n, MINEURS)?.let { return Refus(Motif.MINEUR, it) }
        ageMineur(texte)?.let { return Refus(Motif.MINEUR, it) }
        premier(n, CONSENTEMENT)?.let { return Refus(Motif.CONSENTEMENT, it) }
        premier(n, INCESTE)?.let { return Refus(Motif.INCESTE, it) }
        premier(n, PERSONNES_REELLES)?.let { return Refus(Motif.PERSONNE_REELLE, it) }
        nomProtege(n, nomsProteges)?.let { return Refus(Motif.PERSONNE_REELLE, it) }
        premier(n, ANIMAUX)?.let { return Refus(Motif.ANIMAL, it) }
        return null
    }

    /**
     * Nom d'un contact cité dans la demande : le nom complet (dans n'importe quel ordre), ou un contact à un seul mot
     * (« Sophie », « Karim ») d'au moins 4 lettres. Les libellés génériques (« Maman », « Docteur », « Garage »…) sont ignorés.
     */
    internal fun nomProtege(n: String, noms: Collection<String>): String? {
        for (brut in noms) {
            val mots = norm(brut).split(' ').map { it.trim('.', ',', '(', ')') }.filter { it.length >= 2 && it.none(Char::isDigit) }
            if (mots.isEmpty()) continue
            if (mots.size == 1) {
                val m = mots[0]
                if (m.length >= 4 && m !in GENERIQUES && contient(n, m)) return brut.trim()
            } else {
                val significatifs = mots.filter { it.length >= 3 && it !in GENERIQUES }
                if (significatifs.size >= 2 && significatifs.all { contient(n, it) }) return brut.trim()
                // Prénom seul d'un contact « Prénom Nom » : seulement s'il est rare (≥ 5 lettres), pour ne pas tout bloquer.
                val prenom = mots[0]
                if (prenom.length >= 5 && prenom !in GENERIQUES && contient(n, prenom)) return brut.trim()
            }
        }
        return null
    }

    private val GENERIQUES = setOf(
        "maman", "papa", "mamie", "papi", "docteur", "dr", "garage", "travail", "bureau", "maison", "taxi", "pizza", "banque",
        "mairie", "ecole", "creche", "pharmacie", "cabinet", "dentiste", "medecin", "plombier", "electricien", "contact",
        "service", "client", "inconnu", "numero", "femme", "homme", "fille", "garcon", "cherie", "cheri", "amour", "belle",
        "blonde", "brune", "rousse", "grande", "petite", "jeune", "sexy", "studio", "photo", "image", "style", "noir", "blanc"
    )
}
