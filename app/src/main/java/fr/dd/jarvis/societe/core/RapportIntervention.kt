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
    private val MARQUEUR = Regex("""(?i)\bonduleur\s*(?:n[°o]\s*)?(\d+)\s*[:=\-–]?\s*""")

    data class Bloc(val numero: Int, val nom: String, val contenu: String)

    /** Récupère les lignes de chaque onduleur jusqu'au marqueur du suivant. */
    fun blocs(source: String): List<Bloc> {
        val contenus = linkedMapOf<Int, StringBuilder>()
        var courant: Int? = null
        for (ligne in source.split(Regex("""[\r\n;]+"""))) {
            val matches = MARQUEUR.findAll(ligne).toList()
            if (matches.isEmpty()) {
                courant?.let { n -> contenus.getOrPut(n) { StringBuilder() }.append(" ").append(ligne.trim()) }
                continue
            }
            for ((index, match) in matches.withIndex()) {
                val numero = match.groupValues[1].toIntOrNull() ?: continue
                if (numero !in 1..100) continue
                courant = numero
                val fin = matches.getOrNull(index + 1)?.range?.first ?: ligne.length
                val detail = ligne.substring(match.range.last + 1, fin).trim().trim(',', ';', ':', '-', '–', '.', ' ')
                val cible = contenus.getOrPut(numero) { StringBuilder() }
                if (detail.isNotBlank()) {
                    if (cible.isNotEmpty()) cible.append(" ")
                    cible.append(detail)
                }
            }
        }
        return contenus.toSortedMap().map { (numero, contenu) ->
            Bloc(numero, "Onduleur $numero", contenu.toString().trim())
        }
    }

    fun numerotes(source: String): List<String> = blocs(source).map {
        it.nom + if (it.contenu.isBlank()) "" else " : ${it.contenu}"
    }

    /** Fusionne la sortie IA avec les mentions numérotées exactes de la dictée, sans doublons. */
    fun completer(texteIA: String, source: String): String {
        val lignes = linkedMapOf<String, String>()
        texteIA.split(Regex("""[\r\n;]+""")).map { it.trim() }.filter { it.isNotBlank() }
            .forEach { lignes[Fidelite.normaliser(it)] = it }
        numerotes(source).forEach { ligne ->
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

    /** Analyse séparément les séries de chaque onduleur ; les numéros repartent à 1. */
    fun parOnduleur(texte: String): List<Pair<String, Resultat>> =
        OnduleursIntervention.blocs(texte).map { bloc -> bloc.nom to analyser(bloc.contenu) }

    fun analyser(texte: String): Resultat {
        val valeurs = sortedMapOf<Int, String>()
        val autres = mutableListOf<String>()
        // Les quantités successives décrivent des groupes distincts : 2 séries puis 5 séries = séries 1 à 7.
        var prochaineSerie = 1
        for (ligne in ListesRapport.elements(Fidelite.enChiffres(texte))) {
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
            if (!trouve) { autres += ligne; continue }
            // Ce qui reste d'utile sur la ligne (ex. « isolement OK ») est gardé à part.
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
