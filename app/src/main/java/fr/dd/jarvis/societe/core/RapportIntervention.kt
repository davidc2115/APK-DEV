package fr.dd.jarvis.societe.core

import java.text.Normalizer

/** Découpe un champ libre (actions, pièces, mesures) en éléments de liste. */
object ListesRapport {
    private val PUCE = Regex("""^\s*(?:[-•*·▪►✓✔✅]+|\d{1,2}\s*[.)]|\(\d{1,2}\))\s*""")

    fun elements(texte: String): List<String> =
        texte.split(Regex("""\r?\n|;|\s+[•·▪]\s+"""))
            .map { it.replace(PUCE, "").trim().trimEnd('.', ',').trim() }
            .filter { it.isNotBlank() }
}

/**
 * Préserve les équipements explicitement numérotés dans la dictée.
 * La fidélité générique peut écarter une ligne descriptive longue ; les libellés
 * « Onduleur 1 », « Onduleur 2 », etc. sont donc extraits directement de la source.
 */
object OnduleursIntervention {
    private const val MOTS = "un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|treize|quatorze|quinze|seize|vingt"
    /** « Onduleur 2 », « onduleur n° 2 », « onduleur numéro deux », « onduleur trois : … » (dictée vocale). */
    private val MARQUEUR = Regex("""(?i)\bonduleur\s*(n[°o]\.?\s*|num[ée]ro\s*)?(\d+|(?:$MOTS)\b)\s*[:=\-–]?\s*""")
    private val EN_LETTRES = mapOf("un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7,
        "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16, "vingt" to 20)

    /**
     * Marqueurs d'onduleur d'une ligne, avec leur numéro. « un / une » en lettres n'en est un qu'après « numéro » ou
     * suivi de « : » (« l'onduleur un peu chaud » n'est pas l'onduleur 1).
     */
    private fun marqueurs(ligne: String): List<Pair<MatchResult, Int>> = MARQUEUR.findAll(ligne).mapNotNull { m ->
        val mot = m.groupValues[2].lowercase()
        val n = mot.toIntOrNull() ?: EN_LETTRES[mot] ?: return@mapNotNull null
        if ((mot == "un" || mot == "une") && m.groupValues[1].isBlank()) {
            val apres = ligne.substring((m.groups[2]?.range?.last ?: m.range.last) + 1).trimStart()
            if (apres.isNotEmpty() && apres[0] !in ":=-–,") return@mapNotNull null
        }
        m to n
    }.toList()
    private val FIN_BLOC = Regex("""(?i)^\s*(?:[•*▪-]\s*)?(?:pdl\b|coffret\b|partie\b|remise en service\b|aucun probl[eè]me\b|nettoyage\b|contr[oô]le\b|diagnostic\b|conclusion\b|compteur\b|linky\b|production\b|v[ée]rification de (?:la )?terre\b)""")

    data class Bloc(val numero: Int, val nom: String, val contenu: String)

    /** Récupère les lignes de chaque onduleur jusqu'au marqueur du suivant. */
    fun blocs(source: String): List<Bloc> {
        val contenus = linkedMapOf<Int, StringBuilder>()
        var courant: Int? = null
        for (ligne in source.split(Regex("""[\r\n;]+"""))) {
            val matches = marqueurs(ligne)
            if (matches.isEmpty()) {
                if (FIN_BLOC.containsMatchIn(ligne)) {
                    courant = null
                    continue
                }
                // Lignes gardées telles quelles (une observation par ligne : « Poignée ESS : OK »).
                courant?.let { n -> contenus.getOrPut(n) { StringBuilder() }.append("\n").append(ligne.trim()) }
                continue
            }
            // « Poignée ESS: OK Onduleur 2: » : ce qui précède le marqueur termine le bloc en cours.
            val avant = ligne.substring(0, matches.first().first.range.first).trim().trim(',', ';', ':', '-', '–', '.', ' ', '•')
            if (avant.isNotBlank() && !FIN_BLOC.containsMatchIn(avant)) courant?.let { n -> contenus.getOrPut(n) { StringBuilder() }.append("\n").append(avant) }
            for ((index, mn) in matches.withIndex()) {
                val (match, numero) = mn
                if (numero !in 1..100) continue
                // Si un résumé IA est suivi de la dictée source, le second bloc numéroté remplace le premier
                // au lieu de doubler artificiellement le nombre de séries.
                if (courant != numero && contenus.containsKey(numero)) contenus[numero] = StringBuilder()
                courant = numero
                val fin = matches.getOrNull(index + 1)?.first?.range?.first ?: ligne.length
                val detail = ligne.substring(match.range.last + 1, fin).trim().trim(',', ';', ':', '-', '–', '.', ' ')
                val cible = contenus.getOrPut(numero) { StringBuilder() }
                if (detail.isNotBlank()) {
                    if (cible.isNotEmpty()) cible.append("\n")
                    cible.append(detail)
                }
            }
        }
        return contenus.toSortedMap().map { (numero, contenu) ->
            Bloc(numero, "Onduleur $numero", contenu.toString().trim())
        }
    }

    /**
     * Lignes d'une liste (actions…) SANS les blocs « Onduleur N » (en-tête, séries, observations) : quand les séries
     * sont présentées en tableaux par onduleur, elles ne sont pas répétées dans la liste.
     */
    fun horsBlocs(lignes: List<String>): List<String> {
        var dansBloc = false
        return lignes.filter { l ->
            when {
                marqueurs(l).any { it.first.range.first <= 2 } -> { dansBloc = true; false }
                dansBloc && FIN_BLOC.containsMatchIn(l) -> { dansBloc = false; true }
                dansBloc -> false
                else -> true
            }
        }
    }

    /** Représentation complète des blocs numérotés, utilisée pour préserver la dictée brute. */
    fun numerotes(source: String): List<String> = blocs(source).map { bloc ->
        bloc.nom + if (bloc.contenu.isBlank()) "" else " : ${bloc.contenu}"
    }

    /** Ne conserve dans l'en-tête que l'identité (marque/modèle), jamais les mesures de séries. */
    fun identites(source: String): List<String> {
        val resultat = sortedMapOf<Int, String>()
        for (ligne in source.split(Regex("""[\r\n;]+"""))) {
            val matches = marqueurs(ligne)
            for ((index, mn) in matches.withIndex()) {
                val (match, numero) = mn
                if (numero !in 1..100) continue
                val fin = matches.getOrNull(index + 1)?.first?.range?.first ?: ligne.length
                val detail = ligne.substring(match.range.last + 1, fin).trim()
                    .trim(',', ';', ':', '-', '–', '.', ' ', '\'', '"', '[', ']')
                val normalise = Fidelite.enChiffres(detail)
                if (Regex("""(?i)\b\d{1,3}\s*(?:s[ée]ries?|strings?|cha[îi]nes?)\b""").containsMatchIn(normalise)) continue
                if (detail.isNotBlank() && !resultat.containsKey(numero)) resultat[numero] = detail
            }
        }
        return resultat.map { (numero, modele) -> "Onduleur $numero : $modele" }
    }

    /** Fusionne les identités de la dictée dans le champ équipement, sans y mélanger les mesures. */
    fun completer(texteIA: String, source: String): String {
        val lignes = linkedMapOf<String, String>()
        texteIA.split(Regex("""[\r\n;]+""")).map { it.trim() }.filter { it.isNotBlank() }
            .forEach { lignes[Fidelite.normaliser(it)] = it }
        identites(source).forEach { ligne ->
            val cle = Regex("""(?i)^onduleur\s+(\d+)""").find(ligne)?.groupValues?.get(1)
            if (cle != null) {
                val prefixe = Regex("""(?i)^onduleur\s+$cle\b""")
                val existante = lignes.entries.firstOrNull { prefixe.containsMatchIn(it.value) }
                if (existante != null) lignes.remove(existante.key)
            }
            lignes[Fidelite.normaliser(ligne)] = ligne
        }
        return lignes.values.joinToString("\n")
    }

    /** Un onduleur de l'installation : numéro, marque, modèle et numéro de série (ce qui a été dit). */
    data class Fiche(val numero: Int, val marque: String, val modele: String, val serie: String, val texte: String) {
        /** Libellé court (« SMA », « SMA Tripower 25000 », ou le texte dit). */
        val court: String get() = listOf(marque, modele).filter { it.isNotBlank() }.joinToString(" ").ifBlank { texte }
    }

    private const val FIN_CHAMP = """(?=\s*(?:[,;/]|\bmarque\b|\bmod[eè]le\b|\bn°|\bno\b|\bnum[ée]ro\b|\bs/?n\b|\bpuissance\b|$))"""
    private val R_SERIE = Regex("""(?i)\b(?:n[°o]\s*(?:de\s*)?s[ée]rie|num[ée]ro\s+de\s+s[ée]rie|s/n|sn)\s*[:=]?\s*([A-Za-z0-9][A-Za-z0-9\-./]{2,})""")
    private val R_MARQUE = Regex("""(?i)\bmarque\s*[:=]?\s*(.+?)$FIN_CHAMP""")
    private val R_MODELE = Regex("""(?i)\bmod[eè]le\s*[:=]?\s*(.+?)$FIN_CHAMP""")

    /**
     * Onduleurs de l'installation, numérotés : « Onduleur 3 : marque SMA modèle STP 25000 n° de série 123 » → fiche.
     * Une liste sans numéros (« SMA Tripower\nFronius Symo ») est numérotée dans l'ordre.
     */
    fun fiches(source: String): List<Fiche> {
        val lignes = identites(source).ifEmpty {
            source.replace(Regex("""[\[\]'"]"""), "").split(Regex("""[\r\n;]+""")).map { it.trim().trim(',', '-', '•', '|', ' ') }
                .filter { it.isNotBlank() }.mapIndexed { i, l -> "Onduleur ${i + 1} : $l" }
        }
        return lignes.mapNotNull { l ->
            val m = Regex("""(?i)^onduleur\s+(\d+)\s*:\s*(.*)$""").find(l.trim()) ?: return@mapNotNull null
            val texte = m.groupValues[2].trim().trimEnd('|', ' ', '.')
            val serie = R_SERIE.find(texte)?.groupValues?.get(1).orEmpty()
            val marque = R_MARQUE.find(texte)?.groupValues?.get(1)?.trim().orEmpty()
            val modele = R_MODELE.find(texte)?.groupValues?.get(1)?.trim().orEmpty()
            Fiche(m.groupValues[1].toInt(), marque, modele, serie, texte)
        }
    }

    private fun cellule(t: String) = t.replace('|', '/').replace(Regex("""\s+"""), " ").trim()

    /** Tableau aligné des onduleurs (colonnes vides retirées). */
    fun tableau(fiches: List<Fiche>): String {
        if (fiches.isEmpty()) return ""
        val detaille = fiches.any { it.marque.isNotBlank() || it.modele.isNotBlank() }
        val colonnes = mutableListOf<Pair<String, (Fiche) -> String>>("Onduleur" to { f -> "Onduleur ${f.numero}" })
        if (detaille) {
            // Une ligne sans « marque » ni « modèle » garde son texte dans la colonne Marque.
            if (fiches.any { it.marque.isNotBlank() || it.modele.isBlank() }) colonnes += "Marque" to { f -> f.marque.ifBlank { if (f.modele.isBlank()) f.texte else "" } }
            if (fiches.any { it.modele.isNotBlank() }) colonnes += "Modèle" to { f -> f.modele }
        } else colonnes += "Marque / modèle" to { f -> R_SERIE.replace(f.texte, "").trim(' ', ',', ';', '-').ifBlank { f.texte } }
        if (fiches.any { it.serie.isNotBlank() }) colonnes += "N° de série" to { f -> f.serie }
        return buildString {
            append("| ").append(colonnes.joinToString(" | ") { it.first }).append(" |\n")
            append("|").append(colonnes.joinToString("|") { "---" }).append("|\n")
            fiches.forEach { f -> append("| ").append(colonnes.joinToString(" | ") { cellule(it.second(f)) }).append(" |\n") }
        }.trimEnd()
    }

    /** Résumé sur une ligne (case d'un tableau) : « 6 onduleurs · SMA ». */
    fun resume(fiches: List<Fiche>): String = when (fiches.size) {
        0 -> ""
        1 -> fiches[0].court
        else -> "${fiches.size} onduleurs · " + fiches.map { it.court }.distinct().joinToString(", ")
    }
}

/**
 * Prochaine maintenance : jamais la date de l'intervention elle-même. Non dite pour une visite annuelle (maintenance,
 * nettoyage, contrôle) → un an après ; dite mais égale ou antérieure à l'intervention (date du jour recopiée) → un an après.
 */
object ProchaineMaintenance {
    private val ANNUELLES = setOf("maintenance", "maintenance preventive", "nettoyage", "controle", "entretien", "visite annuelle")

    private fun jour(s: String) = runCatching { java.time.LocalDate.parse(s.trim()) }.getOrNull()

    /** À l'enregistrement d'une intervention. */
    fun planifier(date: String, prochaine: String, categorie: String): String {
        val d = jour(date) ?: return prochaine
        if (prochaine.isBlank()) return if (normaliser(categorie) in ANNUELLES) d.plusYears(1).toString() else ""
        val p = jour(prochaine) ?: return prochaine
        return if (p.isAfter(d)) p.toString() else d.plusYears(1).toString()
    }

    /** À l'affichage d'une fiche déjà enregistrée : corrige seulement une date égale ou antérieure à l'intervention. */
    fun effective(date: String, prochaine: String): String {
        val p = jour(prochaine) ?: return prochaine
        val d = jour(date) ?: return prochaine
        return if (p.isAfter(d)) prochaine else d.plusYears(1).toString()
    }
}

fun Intervention.prochaineEffective(): String = ProchaineMaintenance.effective(date, prochaineMaintenance)

/**
 * Liste DÉTAILLÉE des actions écrite ou dictée par l'utilisateur (« Coffret AC : », « Serrage : OK », « Vérification de
 * terre : »…) : l'IA la résumait parfois en une phrase. Les blocs « Onduleur N » restent dans la liste (le rendu les
 * retire quand les séries sont présentées en tableaux).
 */
object ActionsDetaillees {
    private val ENTETE = Regex("""(?i)^\s*actions?(?:\s+r[ée]alis[ée]es?)?\s*:\s*""")
    private val ACTION = Regex("""(?i)(:|\bok\b|effectu|r[ée]alis|contr[oô]l|v[ée]rifi|nettoy|serrage|resserr|remise en service|d[ée]faut|remplac|changement|\btest|mesur|production|conso|disjonct|parafoudre|sectionneur|coffret|onduleur)""")

    private val DEMANDE = Regex("""(?i)^(?:jarvis[, ]+)?(?:modifi\w*|refai\w*|refais|mets|met|ajoute\w*|corrig\w*|rempla\w*|voici|voil[aà]|compl[eè]t\w*|mise? [aà] jour|reprend\w*|utilise\w*|fais|peux[- ]tu)\b|\bl['’]intervention de\b""")

    /** Fiche ou historique affiché par JARVIS (« # Historique des interventions », « ID #11912fb4 ») : jamais une liste d'actions. */
    private val SORTIE_JARVIS = Regex("""(?im)^\s*#{1,6}\s|historique des interventions|\bID\s*#[0-9a-f]{6,}""")

    /**
     * Lignes d'action d'une liste vraiment structurée (rubriques « Coffret AC : » et contrôles « Serrage : OK »), sinon vide
     * (texte libre, dictée d'une traite, fiche ou historique affichés par JARVIS).
     */
    fun extraire(texte: String): List<String> {
        if (SORTIE_JARVIS.containsMatchIn(texte)) return emptyList()
        // La demande elle-même (« Modifie l'intervention de lapree : ») n'est pas une action.
        val lignes = ListesRapport.elements(texte).map { it.replace(ENTETE, "").trim() }
            .filter { it.isNotBlank() && it.length <= 160 && !DEMANDE.containsMatchIn(it) }
        val rubriques = lignes.count { rubrique(it) || Regex("""(?i)^onduleur\s*\d+\s*:?$""").matches(it) }
        val resultats = lignes.count { Regex("""(?i)\bok\b|:\s*\S""").containsMatchIn(it) }
        return if (rubriques >= 2 && resultats >= 3 && lignes.size >= 5 && lignes.count { ACTION.containsMatchIn(it) } >= 4) lignes else emptyList()
    }

    /**
     * Actions enregistrées remises au propre : retire ce qui n'est pas une action (titres markdown, identifiant, date,
     * statut, catégorie, description, diagnostic, notes… recopiés d'une fiche affichée) ; « Mesures : Onduleur 1 : … »
     * redevient « Onduleur 1 : … » (classé ensuite dans les tableaux).
     */
    fun nettoyer(lignes: List<String>, description: String, categorie: String, statut: String): List<String> {
        val desc = normaliser(description).trimEnd('.', ' ')
        val etiquettes = setOf(normaliser(categorie), normaliser(statut), "terminee", "a suivre", "en attente", "maintenance")
        return lignes.mapNotNull { brut ->
            val l = brut.trim()
            val n = normaliser(l)
            when {
                l.startsWith("#") -> null
                Regex("""(?i)^id\s*#?\s*[0-9a-f]{6,}$""").matches(l) -> null
                Regex("""^\d{4}-\d{2}-\d{2}$""").matches(l) -> null
                l.length < 24 && n in etiquettes -> null
                Regex("""(?i)^(diagnostic|onduleur\(s\)|onduleurs|prochaine maintenance|notes?|installation|statut|cat[ée]gorie|date)\s*:""").containsMatchIn(l) -> null
                desc.isNotBlank() && n.trimEnd('.', ' ') == desc -> null
                else -> l.replace(Regex("""(?i)^mesures?\s*:\s*"""), "").trim().ifBlank { null }
            }
        }
    }

    private val COFFRET_SEUL = Regex("""(?i)^coffrets?\s+[\p{L}\d]+(?:\s+[\p{L}\d]+)?$""")

    /** Titre d'une ligne de rubrique (« Coffret AC : », ou « Coffret DC » seul sur sa ligne), sinon null. */
    fun titre(ligne: String): String? {
        val l = ligne.trim()
        return when {
            rubrique(l) -> l.removeSuffix(":").trim().ifBlank { null }
            COFFRET_SEUL.matches(l) -> l
            else -> null
        }
    }

    /** Rubrique et ses contrôles ; [entete] null pour les lignes écrites avant toute rubrique. */
    class Bloc(val entete: String?, val titre: String?, val lignes: MutableList<String> = mutableListOf()) {
        val cle: String get() = titre?.let { normaliser(it).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() }.orEmpty()
    }

    /** Liste d'actions découpée en rubriques (« Coffret DC : contrôle parafoudre OK » ouvre la rubrique Coffret DC). */
    fun enBlocs(lignes: List<String>): List<Bloc> {
        val r = mutableListOf(Bloc(null, null))
        for (brut in lignes) {
            val l = brut.trim()
            if (l.isBlank()) continue
            val t = titre(l)
            if (t != null) { r += Bloc(l, t); continue }
            val e = CategoriesActions.enLigne(l)
            if (e != null) { r += Bloc("${e.first} :", e.first, e.second.toMutableList()); continue }
            r.last().lignes += l
        }
        return r.filter { it.titre != null || it.lignes.isNotEmpty() }
    }

    private fun aplatir(blocs: List<Bloc>): List<String> = blocs.flatMap { b -> listOfNotNull(b.entete) + b.lignes }

    /** Même contrôle (mêmes mots porteurs : « nettoyage et ventilation » / « Nettoyage des ventilateurs : OK »). */
    private fun similaire(a: String, b: String) = CategoriesActions.doublon(a, b) || CategoriesActions.doublon(b, a)

    /**
     * Lignes [ajouts] ajoutées à [actuelles], RUBRIQUE PAR RUBRIQUE : « Coffret DC : serrage OK » complète la rubrique
     * Coffret DC (créée au besoin, après les autres coffrets) même si « Serrage OK » existe déjà sous Coffret AC. Sans
     * rubrique, une ligne qui reprend UNE action existante la remplace à sa place ; sinon elle rejoint la rubrique de son
     * domaine (nettoyage…) ou les actions générales.
     */
    fun completer(actuelles: List<String>, ajouts: List<String>): List<String> {
        val blocs = enBlocs(actuelles).toMutableList()
        for (aj in enBlocs(ajouts)) {
            if (aj.titre == null) {
                for (l in aj.lignes) {
                    val trouves = blocs.flatMapIndexed { bi, b -> b.lignes.indices.filter { similaire(b.lignes[it], l) }.map { bi to it } }
                    when {
                        trouves.size == 1 -> blocs[trouves[0].first].lignes[trouves[0].second] = l
                        trouves.isEmpty() -> {
                            val d = CategoriesActions.cleDomaine(l)
                            val cible = blocs.lastOrNull { b -> b.titre != null && d != null && CategoriesActions.cleDomaine(b.titre) == d }
                                ?: blocs.firstOrNull { it.titre == null } ?: Bloc(null, null).also { blocs.add(0, it) }
                            cible.lignes += l
                        }
                        // Plusieurs rubriques ont ce contrôle (« Serrage OK ») : sans rubrique précisée, rien n'est déplacé.
                        else -> Unit
                    }
                }
            } else {
                val existant = blocs.firstOrNull { it.titre != null && it.cle == aj.cle }
                if (existant != null) {
                    for (l in aj.lignes) {
                        val k = existant.lignes.indexOfFirst { similaire(it, l) }
                        if (k >= 0) existant.lignes[k] = l else existant.lignes += l
                    }
                } else inserer(blocs, Bloc(aj.entete, aj.titre, aj.lignes.toMutableList()))
            }
        }
        return aplatir(blocs)
    }

    /** Nouvelle rubrique : après la dernière de la même famille (« Coffret DC » après « Coffret Enedis »), sinon à la fin. */
    private fun inserer(blocs: MutableList<Bloc>, nouveau: Bloc) {
        val famille = nouveau.cle.substringBefore(' ')
        val j = blocs.indexOfLast { it.titre != null && it.cle.substringBefore(' ') == famille }
        if (j >= 0) blocs.add(j + 1, nouveau) else blocs += nouveau
    }

    /**
     * « Modifie la partie coffret DC : contrôle parafoudre OK, contrôle sectionneur OK, serrage OK » : chaque rubrique
     * donnée REMPLACE la rubrique du même nom (créée au besoin) ; les autres rubriques restent telles quelles. Les lignes
     * sans rubrique complètent la liste ([completer]).
     */
    fun remplacerRubriques(actuelles: List<String>, nouvelles: List<String>): List<String> {
        val blocs = enBlocs(actuelles).toMutableList()
        val sansRubrique = mutableListOf<String>()
        for (nb in enBlocs(nouvelles)) {
            if (nb.titre == null) { sansRubrique += nb.lignes; continue }
            val i = blocs.indexOfFirst { it.titre != null && it.cle == nb.cle }
            if (i >= 0) blocs[i] = Bloc(blocs[i].entete, blocs[i].titre, nb.lignes.toMutableList()) else inserer(blocs, Bloc(nb.entete, nb.titre, nb.lignes.toMutableList()))
        }
        return completer(aplatir(blocs), sansRubrique)
    }

    /**
     * Vrai si [liste] contient TOUT ce que dit [autres], rubrique par rubrique : une ancienne liste dictée, même plus
     * longue, ne remplace jamais des actions qui ont été ajoutées depuis (« Coffret DC : serrage OK »).
     */
    fun couvre(liste: List<String>, autres: List<String>): Boolean {
        val a = enBlocs(liste)
        return enBlocs(autres).all { b ->
            b.lignes.all { l ->
                if (b.titre == null) a.any { x -> x.lignes.any { similaire(it, l) } }
                else a.any { x -> x.cle == b.cle && x.lignes.any { similaire(it, l) } }
            }
        }
    }

    /** Rubrique (« Coffret AC : », « Nettoyage et maintenance : ») : titre sans contenu après les deux-points. */
    fun rubrique(ligne: String): Boolean = ligne.trimEnd().endsWith(":") && ligne.length <= 60
}

/**
 * Mesures par série (string) : « 20 séries à environ 500 V » → Série 1 … Série 20 à ≈ 500 V ; « séries 1 à 10 : 480 V »,
 * « série 7 : 320 V », « S3 = 512 V / 9,1 A » précisent ou corrigent. Le reste (température, isolement…) reste tel quel.
 */
object MesuresSeries {
    data class Serie(val numero: Int, val valeur: String)
    data class Resultat(val series: List<Serie>, val autres: List<String>)

    private const val NOMBRE = """(?<![\d.,])(\d+(?:[.,]\d+)?)"""
    private const val UNITE = """(kV|mV|mA|V|volts?|A|amp(?:[èe]res?)?|W|kW|Ω|ohms?|MΩ)"""
    private const val APPROX = """(environ|env\.?|~|≈|approx(?:imativement)?\.?|autour de|à peu près|a peu pres|vers)?"""
    private const val SERIE = """(?:s[ée]ries?|strings?|cha[îi]nes?)"""
    private val VALEUR = """$APPROX\s*$NOMBRE\s*$UNITE\b(?:\s*(?:/|et|,)\s*$NOMBRE\s*$UNITE\b)?"""
    private val GROUPE = Regex("""(?i)\b(\d{1,3})\s*$SERIE\b[^\d\n]{0,25}?$VALEUR""")
    private val PLAGE = Regex("""(?i)\b$SERIE\s*(?:n°\s*)?(\d{1,3})\b\s*(?:à|a|au|-|–)\s*(\d{1,3})\b\s*[:=]?\s*[^\d\n]{0,12}?$VALEUR""")
    private val UNE = Regex("""(?i)(?:\b$SERIE|\bS)\s*(?:n°\s*)?(\d{1,3})\b\s*[:=]?\s*[^\d\n]{0,12}?$VALEUR""")
    private val VALEUR_ORIGINALE = Regex("""(?i)\d+(?:[.,]\d+)?\s*(?:kV|mV|mA|volts?|V|A|W|kW|Ω|MΩ|ohms?)(?![\p{L}])""")
    /** « Séries : 352, 352 V et 377, 358 V » (deux-points obligatoire : « série 7 : 320 V » est une seule série). */
    // Tensions de 3-4 chiffres (jamais des numéros de séries) séparées par « , », « ; », « / », « - » ou « et » :
    // « Séries : 352, 352 V et 377, 358 V », « séries 352 V et 377-358 V ».
    private val LISTE = Regex("""(?i)\bs[ée]ries\s*:?\s*((?:\d{3,4}(?:,\d{1,2})?\s*(?:v|volts?)?\s*(?:,|;|/|-|–|\bet\b)\s*)+\d{3,4}(?:,\d{1,2})?)\s*(?:v|volts?)\b""")
    private val ETIQUETTE = Regex("""(?i)^(?:tensions?|courants?|mesures?|valeurs?)?\s*(?:relev[ée]e?s?|mesur[ée]e?s?)?\s*[:\-–]?\s*""")

    private fun unite(u: String): String = when (u.lowercase()) {
        "v", "volt", "volts" -> "V"
        "a", "amp", "ampere", "amperes", "ampère", "ampères" -> "A"
        "ohm", "ohms" -> "Ω"
        else -> u
    }

    /** « ≈ 500 V » / « 512 V · 9,1 A » à partir des groupes capturés par [VALEUR] (à partir de l'indice [i]). */
    private fun valeur(g: List<String>, i: Int): String {
        val approx = g[i].isNotBlank()
        val premier = g[i + 1].replace('.', ',') + " " + unite(g[i + 2])
        val second = if (g.getOrNull(i + 3).isNullOrBlank()) "" else " · " + g[i + 3].replace('.', ',') + " " + unite(g[i + 4])
        return (if (approx) "≈ " else "") + premier + second
    }

    private val MESURE = Regex("""(?i)(\d\s*(v|volts?|mv|kv|a|ma|w|kw|kwh|wh|mΩ|Ω|ohms?|°\s?c|%|hz)\b|isolement|tension|courant|temp[ée]rature|r[ée]sistance|\bterre\b|mesur|relev|production|index|compteur|conso)""")

    /** Une « autre mesure » ressemble à une mesure (valeur avec unité, isolement, compteur…), pas à une phrase du chat. */
    fun ressembleMesure(ligne: String): Boolean = ligne.length <= 300 && MESURE.containsMatchIn(ligne)

    /** Analyse séparément les séries de chaque onduleur ; les numéros repartent à 1. */
    fun parOnduleur(texte: String): List<Pair<String, Resultat>> =
        OnduleursIntervention.blocs(texte).map { bloc -> bloc.nom to analyser(bloc.contenu) }

    fun analyser(texte: String): Resultat {
        val valeurs = sortedMapOf<Int, String>()
        val autres = mutableListOf<String>()
        // Les quantités successives décrivent des groupes distincts : 2 séries puis 5 séries = séries 1 à 7.
        var prochaineSerie = 1
        for (origine in ListesRapport.elements(texte)) {
            // « Séries : 352, 352 V et 377, 358 V » : une tension par série, dans l'ordre.
            LISTE.find(origine)?.let { m ->
                val nombres = Regex("""\d{3,4}(?:,\d{1,2})?(?!\d)""").findAll(m.groupValues[1]).map { it.value }.toList()
                if (nombres.size >= 2) {
                    nombres.forEach { v -> if (prochaineSerie <= 200) valeurs[prochaineSerie++] = "$v V" }
                    val r = origine.replace(m.value, " ").replace(Regex("""\s+"""), " ").trim(' ', ',', ';', ':', '-', '–', '.')
                    if (r.count { it.isLetter() } >= 4) autres += r
                    continue
                }
            }
            val ligneBrute = Fidelite.enChiffres(origine)
            // Une dictée peut donner l'unité une seule fois (« 16 séries à 775 volts et 2 séries à 805 »).
            // On propage cette unité uniquement aux groupes sans unité, jamais aux totaux (« 18 séries au total »).
            // Chercher une unité uniquement après une valeur numérique : après normalisation,
            // « à » devient « a » et ne doit jamais être interprété comme l'unité ampère « A ».
            val uniteLigne = Regex("""(?i)\b\d+(?:[.,]\d+)?\s*(kV|mV|mA|volts?|V|A|amp(?:ères?)?|W|kW|Ω|ohms?|MΩ)\b""")
                .find(ligneBrute)?.groupValues?.get(1)
            val ligne = if (uniteLigne == null) ligneBrute else {
                val sansUnite = Regex("""(?i)(\b\d{1,3}\s*$SERIE\s*[^\d\n]{0,20}?\d++(?:[.,]\d++)?+)(?!\s*(?:kV|mV|mA|volts?|V|A|amp(?:ères?)?|W|kW|Ω|ohms?|MΩ)\b)""")
                sansUnite.replace(ligneBrute) { m ->
                    // Le texte a déjà été normalisé sans accents : « à » devient « a ».
                    // Tester la présence de l'unité sur tout le groupe confondait ce « a »
                    // avec l'unité ampère « A » et supprimait la propagation des volts.
                    // Le motif ne capture que les groupes dont l'unité manque après la valeur.
                    m.value + " " + uniteLigne
                }
            }
            var reste = ligne
            var trouve = false
            GROUPE.findAll(ligne).forEach { m ->
                val n = m.groupValues[1].toInt()
                if (n in 1..200) {
                    val v = valeur(m.groupValues, 2)
                    val fin = (prochaineSerie + n - 1).coerceAtMost(200)
                    for (k in prochaineSerie..fin) valeurs[k] = v
                    prochaineSerie = fin + 1
                    reste = reste.replace(m.value, " "); trouve = true
                }
            }
            PLAGE.findAll(ligne).forEach { m ->
                val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
                if (a in 1..200 && b in a..200) {
                    val v = valeur(m.groupValues, 3)
                    for (k in a..b) valeurs[k] = v
                    reste = reste.replace(m.value, " "); trouve = true
                }
            }
            if (!trouve || UNE.containsMatchIn(reste)) UNE.findAll(ligne).forEach { m ->
                if (PLAGE.find(ligne)?.range?.let { r -> m.range.first in r } == true) return@forEach
                val k = m.groupValues[1].toInt()
                if (k in 1..200) { valeurs[k] = valeur(m.groupValues, 2); reste = reste.replace(m.value, " "); trouve = true }
            }
            // Rien de mesuré sur la ligne : gardée telle qu'écrite (accents, majuscules), pas sous sa forme normalisée.
            if (!trouve) { autres += origine; continue }
            // Ce qui suit la dernière valeur (« 6 séries de 520 V, poignée ESS OK ») est gardé tel qu'écrit.
            val apres = VALEUR_ORIGINALE.findAll(origine).lastOrNull()?.let { origine.substring(it.range.last + 1) }
                ?.trim(' ', ',', ';', ':', '-', '–', '.', '•', ')')
            if (apres != null && apres.count { it.isLetter() } >= 4 && !Regex("""(?i)s[ée]ries?|\d""").containsMatchIn(apres)) { autres += apres; continue }
            // Sinon, ce qui reste d'utile sur la ligne (ex. « isolement OK ») est gardé à part.
            val r = reste.replace(Regex("""\s+"""), " ").trim(' ', ',', ';', ':', '-', '–', '.').replace(ETIQUETTE, "").trim(' ', ',', ':', '-')
            if (r.count { it.isLetter() } >= 4) autres += r
        }
        // Des numéros isolés (« série 7 : 320 V ») sans groupe : on ne liste que ceux donnés.
        return Resultat(valeurs.map { (k, v) -> Serie(k, v) }, autres)
    }
}

/**
 * Fidélité d'un rapport : rien qui n'ait été dit par l'utilisateur. Une ligne est écartée si elle contient un nombre
 * jamais dit (ex. « Température onduleur : 38 °C » inventée), si c'est un commentaire sur le rapport lui-même
 * (« température retirée du rapport »), ou si l'essentiel de ses mots ne vient pas de ce qu'il a dit.
 */
object Fidelite {
    data class Verif(val garde: String, val retires: List<String>)

    private val VIDES = setOf(
        "avec", "pour", "dans", "sans", "sous", "chez", "vers", "entre", "apres", "avant", "pendant", "lors", "depuis",
        "plus", "moins", "tres", "bien", "aussi", "ainsi", "alors", "donc", "mais", "comme", "cette", "celle", "celui",
        "tout", "tous", "toute", "toutes", "etait", "etre", "avoir", "fait", "faite", "faits", "faites", "effectue",
        "effectuee", "effectues", "effectuees", "realise", "realisee", "realises", "realisees", "suite", "niveau",
        "ensemble", "partie", "chaque", "leur", "leurs", "nous", "vous", "elle", "elles", "sont", "etat", "aucun",
        "aucune", "quelques", "certains", "certaines", "notamment", "egalement", "general", "generale", "global",
        "globale", "complet", "complete", "intervention", "installation", "photovoltaique", "photovoltaiques",
        // Étiquettes de présentation (« Tension relevée : … ») : ce ne sont pas des faits.
        "tension", "tensions", "releve", "relevee", "relevees", "releves", "mesure", "mesures", "mesuree", "mesurees",
        "valeur", "valeurs", "remarque", "remarques", "note", "notes", "detail", "details",
    )
    private val META = Regex("""(?i)(retir|supprim|enlev|ajout|modifi)\p{L}*\s+(du|dans le|au|le|la|de|des)?\s*(rapport|compte[- ]rendu|fiche)|\bà la demande\b|\bnon communiqu|\bnon fourni|\bnon pr[ée]cis""")
    private val SYNONYMES = mapOf(
        "strin" to "serie", "chain" to "serie", "serie" to "serie",
        "verif" to "contr", "contr" to "contr", "modul" to "panne", "panne" to "panne",
    )

    /** Commentaire sur le rapport lui-même (« température retirée du rapport »), jamais un fait d'intervention. */
    fun estMeta(ligne: String) = META.containsMatchIn(ligne)

    /** [texte] sans ses commentaires sur le rapport. */
    fun sansMeta(texte: String): String =
        texte.split(Regex("""\r?\n|(?<=[.!?])\s+(?=[A-ZÀ-Ý])""")).filterNot { it.isBlank() || estMeta(it) }
            .joinToString(if (texte.contains('\n')) "\n" else " ").trim()

    fun normaliser(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    // ---------------------------------------------------------------- nombres (chiffres et mots)

    private val UNITES = mapOf(
        "zero" to 0, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7,
        "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14,
        "quinze" to 15, "seize" to 16, "vingt" to 20, "vingts" to 20, "trente" to 30, "quarante" to 40,
        "cinquante" to 50, "soixante" to 60,
    )

    /** Nombres écrits en lettres (« vingt séries à cinq cents volts ») convertis en chiffres. */
    internal fun enChiffres(texte: String): String {
        val mots = normaliser(texte).split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        val sortie = StringBuilder()
        var total = 0; var courant = 0; var dansNombre = false
        fun vider() { if (dansNombre) sortie.append(' ').append(total + courant).append(' '); total = 0; courant = 0; dansNombre = false }
        for (m in mots) {
            when {
                m == "et" && dansNombre -> {}
                UNITES.containsKey(m) -> {
                    val v = UNITES[m]!!
                    courant = if (courant in listOf(60, 80) && v in 10..19) courant + v
                        else if (courant >= 20 && courant % 10 == 0 && v < 10) courant + v
                        else if (courant == 4 && v == 20) 80
                        else if (courant == 0) v else courant + v
                    dansNombre = true
                }
                m == "cent" || m == "cents" -> { courant = (if (courant == 0) 1 else courant) * 100; dansNombre = true }
                m == "mille" -> { total += (if (courant == 0) 1 else courant) * 1000; courant = 0; dansNombre = true }
                else -> { vider(); sortie.append(' ').append(m) }
            }
        }
        vider()
        return sortie.toString()
    }

    /** Nombres d'un texte, normalisés (« 38,5 » → « 38.5 », « 500 » → « 500 »). */
    fun nombres(texte: String): Set<String> {
        val t = texte + " " + enChiffres(texte)
        return Regex("""\d+(?:[.,]\d+)?""").findAll(t).map { n ->
            n.value.replace(',', '.').let { if (it.contains('.')) it.trimEnd('0').trimEnd('.') else it.trimStart('0').ifEmpty { "0" } }
        }.toSet()
    }

    // ---------------------------------------------------------------- mots

    private fun mots(texte: String): List<String> = normaliser(texte).split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 4 && it !in VIDES && it.any { c -> c.isLetter() } }

    private fun racine(m: String): String { val r = m.take(5); return SYNONYMES[r] ?: r }

    private fun appuye(m: String, dits: Set<String>): Boolean = racine(m) in dits

    /** Part des mots importants de [texte] présents dans [support] (1.0 si aucun mot important). */
    fun appui(texte: String, support: String): Double {
        val m = mots(texte)
        if (m.isEmpty()) return 1.0
        val dits = mots(support).map { racine(it) }.toSet()
        return m.count { appuye(it, dits) }.toDouble() / m.size
    }

    /** Lignes (ou phrases) d'un champ libre. */
    private fun morceaux(champ: String): List<String> =
        champ.split(Regex("""\r?\n|;|(?<=[.!?])\s+(?=[A-ZÀ-Ý])""")).map { it.trim() }.filter { it.isNotBlank() }

    /** Garde de [champ] ce qui est appuyé par [support] (paroles de l'utilisateur + données déjà connues). */
    fun verifier(champ: String, support: String): Verif {
        if (champ.isBlank()) return Verif("", emptyList())
        val nombresDits = nombres(support)
        val dits = mots(support).map { racine(it) }.toSet()
        val gardes = mutableListOf<String>(); val retires = mutableListOf<String>()
        for (l in morceaux(champ)) {
            val inventes = nombres(l).filter { it !in nombresDits }
            val m = mots(l)
            val part = if (m.isEmpty()) 1.0 else m.count { appuye(it, dits) }.toDouble() / m.size
            val ok = inventes.isEmpty() && !META.containsMatchIn(l) && (part >= 0.5 || (m.size <= 2 && part > 0.0))
            if (ok) gardes += l else retires += l
        }
        val sep = if (champ.contains('\n')) "\n" else " "
        return Verif(gardes.joinToString(sep).trim(), retires)
    }
}
