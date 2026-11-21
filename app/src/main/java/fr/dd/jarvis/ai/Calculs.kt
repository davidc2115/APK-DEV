package fr.dd.jarvis.ai

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Calculs, pourcentages, conversions d'unités et questions de calendrier (« combien de jours avant Noël », « quel jour
 * tombe le 3 décembre ») : réponse exacte et instantanée, calculée sur le téléphone — sans IA (avant, 2 à 3 s d'IA et
 * parfois un résultat faux).
 */
object Calculs {
    // ------------------------------------------------------------------ texte

    private fun prep(t: String): String = Normalizer.normalize(t.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .replace('×', '*').replace('÷', '/').replace('−', '-').replace('’', ' ').replace('\'', ' ')
        .replace(Regex("(?<=[a-z])-(?=[a-z])"), " ")
        .replace(Regex("(\\d)\\s*,\\s*(\\d)"), "$1.$2")
        .replace(Regex("(\\d)\\s+(\\d{3})(?!\\d)"), "$1$2")            // 1 250 → 1250
        .replace(Regex("[^a-z0-9.+\\-*/%^() ]"), " ").replace(Regex("\\s+"), " ").trim()

    private const val POLI = "(?:(?:jarvis|ok|dis moi|alors|est ce que tu peux|tu peux|peux tu|pourrais tu|stp|s il te plait) )*"
    private const val NB = "(\\d+(?:\\.\\d+)?)"

    /** Nombre à la française : « 1 234,5 », sans zéros inutiles. */
    internal fun fr(x: Double): String {
        if (x.isNaN() || x.isInfinite()) return "∞"
        val r = Math.round(x * 1_000_000.0) / 1_000_000.0
        val ent = floor(abs(r)).toLong()
        val dec = abs(r) - ent
        val txtEnt = ent.toString().reversed().chunked(3).joinToString(" ").reversed()
        val txtDec = if (dec < 1e-9) "" else "," + String.format(java.util.Locale.ROOT, "%.6f", dec).substringAfter('.').trimEnd('0').take(6)
        return (if (r < 0) "-" else "") + txtEnt + txtDec.let { if (it == ",") "" else it }
    }

    // ------------------------------------------------------------------ expressions

    /** Mots d'opérations → symboles (« 12 fois 3 plus 4 » → « 12*3+4 »). */
    private fun symboles(e: String): String = (" $e ")
        .replace(Regex("\\bracine (?:carree )?de ?$NB"), " sqrt($1) ")
        .replace(Regex("\\bau carre\\b"), "^2").replace(Regex("\\bau cube\\b"), "^3")
        .replace(Regex("\\b(?:puissance|exposant)\\b"), "^")
        .replace(Regex("\\b(?:multiplie|multiplies|multiplier) par\\b|\\bfois\\b|(?<=[\\d)]) ?x ?(?=[\\d(])"), "*")
        .replace(Regex("\\b(?:divise|divises|diviser|partage) (?:par|en)\\b|(?<=\\d) sur (?=\\d)"), "/")
        .replace(Regex("\\bplus\\b"), "+").replace(Regex("\\bmoins\\b"), "-")
        .replace(Regex("\\b(?:egal|egale|=)\\s*$"), "").replace(Regex("\\s+"), "").trim()

    private class Lecteur(val s: String) {
        var i = 0
        fun expr(): Double { var v = terme(); while (i < s.length && (s[i] == '+' || s[i] == '-')) { val o = s[i++]; val w = terme(); v = if (o == '+') v + w else v - w }; return v }
        fun terme(): Double { var v = facteur(); while (i < s.length && (s[i] == '*' || s[i] == '/')) { val o = s[i++]; val w = facteur(); if (o == '/' && w == 0.0) throw ArithmeticException("division par zéro"); v = if (o == '*') v * w else v / w }; return v }
        fun facteur(): Double { val b = unaire(); if (i < s.length && s[i] == '^') { i++; return b.pow(facteur()) }; return b }
        fun unaire(): Double { if (i < s.length && s[i] == '-') { i++; return -unaire() }; if (i < s.length && s[i] == '+') { i++; return unaire() }; return primaire() }
        fun primaire(): Double {
            if (s.startsWith("sqrt(", i)) { i += 5; val v = expr(); attendre(')'); if (v < 0) throw ArithmeticException("racine d'un nombre négatif"); return sqrt(v) }
            if (i < s.length && s[i] == '(') { i++; val v = expr(); attendre(')'); return v }
            val d = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (d == i) throw IllegalArgumentException("nombre attendu")
            var v = s.substring(d, i).toDouble()
            if (i < s.length && s[i] == '%') { i++; v /= 100 }
            return v
        }
        fun attendre(c: Char) { if (i >= s.length || s[i] != c) throw IllegalArgumentException("« $c » attendu"); i++ }
    }

    /** Valeur d'une expression déjà en symboles, ou null si ce n'en est pas une. */
    internal fun evaluer(e: String): Double? {
        if (e.isBlank() || !Regex("^[0-9.+\\-*/^()%sqrt]+$").matches(e) || !e.any { it.isDigit() }) return null
        val l = Lecteur(e)
        return runCatching { l.expr().takeIf { l.i == e.length } }.getOrElse { if (it is ArithmeticException) Double.NaN else null }
    }

    private fun joli(e: String) = e.replace("*", " × ").replace("/", " ÷ ").replace("+", " + ").replace(Regex("(?<=[0-9)])-"), " − ")
        .replace("^", "^").replace("sqrt", "√").replace(Regex("\\d+(?:\\.\\d+)?")) { fr(it.value.toDouble()) }.replace(Regex("\\s+"), " ").trim()

    // ------------------------------------------------------------------ unités

    private data class Unite(val dim: String, val facteur: Double, val nom: String)
    private val UNITES: List<Pair<String, Unite>> = run {
        fun u(dim: String, f: Double, nom: String, vararg alias: String) = alias.map { it to Unite(dim, f, nom) }
        listOf(
            u("long", 1000.0, "km", "km", "kilometre", "kilometres"), u("long", 1.0, "m", "m", "metre", "metres", "mettre", "mettres"),
            u("long", 0.01, "cm", "cm", "centimetre", "centimetres"), u("long", 0.001, "mm", "mm", "millimetre", "millimetres"),
            u("long", 1852.0, "milles marins", "milles marins", "mille marin", "miles nautiques", "mile nautique", "milles nautiques"),
            u("long", 1609.344, "miles", "mile", "miles", "mi"), u("long", 0.3048, "pieds", "pied", "pieds", "ft", "feet"),
            u("long", 0.0254, "pouces", "pouce", "pouces", "inch", "inches", "in"), u("long", 0.9144, "yards", "yard", "yards", "yd"),
            u("masse", 1.0, "kg", "kg", "kilo", "kilos", "kilogramme", "kilogrammes"), u("masse", 0.001, "g", "g", "gramme", "grammes", "gr"),
            u("masse", 1e-6, "mg", "mg", "milligramme", "milligrammes"), u("masse", 1000.0, "t", "tonne", "tonnes"),
            u("masse", 0.45359237, "livres", "livre", "livres", "lb", "lbs"), u("masse", 0.028349523125, "onces", "once", "onces", "oz"),
            u("vol", 1.0, "L", "l", "litre", "litres"), u("vol", 0.1, "dL", "dl", "decilitre", "decilitres"), u("vol", 0.01, "cL", "cl", "centilitre", "centilitres"),
            u("vol", 0.001, "mL", "ml", "millilitre", "millilitres"), u("vol", 1000.0, "m³", "m3", "metre cube", "metres cubes"),
            u("vol", 3.785411784, "gallons US", "gallon", "gallons"), u("vol", 0.56826125, "pintes", "pinte", "pintes"),
            u("vitesse", 1 / 3.6, "km/h", "km/h", "kmh", "km h", "kilometres heure", "kilometres par heure"), u("vitesse", 1.0, "m/s", "m/s", "metres par seconde"),
            u("vitesse", 0.44704, "mph", "mph", "miles par heure", "miles heure"), u("vitesse", 0.514444, "nœuds", "noeud", "noeuds"),
            u("temps", 1.0, "s", "s", "seconde", "secondes", "sec"), u("temps", 60.0, "min", "min", "minute", "minutes"), u("temps", 3600.0, "h", "h", "heure", "heures"),
            u("temps", 86400.0, "jours", "jour", "jours"), u("temps", 604800.0, "semaines", "semaine", "semaines"),
            u("donnees", 1.0, "octets", "octet", "octets"), u("donnees", 1e3, "Ko", "ko", "kilooctet", "kilooctets"), u("donnees", 1e6, "Mo", "mo", "megaoctet", "megaoctets"),
            u("donnees", 1e9, "Go", "go", "gigaoctet", "gigaoctets", "giga", "gigas"), u("donnees", 1e12, "To", "to", "teraoctet", "teraoctets"),
            u("surface", 1.0, "m²", "m2", "metre carre", "metres carres"), u("surface", 1e6, "km²", "km2", "kilometre carre", "kilometres carres"),
            u("surface", 1e4, "ha", "ha", "hectare", "hectares"), u("surface", 100.0, "ares", "are", "ares"), u("surface", 1e-4, "cm²", "cm2", "centimetre carre", "centimetres carres"),
            u("energie", 3.6e6, "kWh", "kwh", "kilowattheure", "kilowattheures", "kilowatt heure", "kilowatts heure"), u("energie", 3600.0, "Wh", "wh", "wattheure", "wattheures"),
            u("energie", 1.0, "J", "joule", "joules"), u("energie", 4184.0, "kcal", "kcal", "kilocalorie", "kilocalories", "calories"),
            u("temp", 0.0, "°C", "celsius", "degres celsius", "degre celsius", "c"), u("temp", 0.0, "°F", "fahrenheit", "degres fahrenheit", "degre fahrenheit", "f"),
            u("temp", 0.0, "K", "kelvin", "kelvins", "k"),
        ).flatten().sortedByDescending { it.first.length }
    }
    private val U = UNITES.joinToString("|") { Regex.escape(it.first) }
    private fun unite(s: String) = UNITES.firstOrNull { it.first == s.trim() }?.second

    private fun convertir(v: Double, de: Unite, vers: Unite): Double? {
        if (de.dim != vers.dim) return null
        if (de.dim != "temp") return v * de.facteur / vers.facteur
        val c = when (de.nom) { "°F" -> (v - 32) * 5 / 9; "K" -> v - 273.15; else -> v }
        return when (vers.nom) { "°F" -> c * 9 / 5 + 32; "K" -> c + 273.15; else -> c }
    }

    // ------------------------------------------------------------------ calendrier

    private fun paques(a: Int): LocalDate {
        val g = a % 19; val c = a / 100; val h = (c - c / 4 - (8 * c + 13) / 25 + 19 * g + 15) % 30
        val i = h - (h / 28) * (1 - (h / 28) * (29 / (h + 1)) * ((21 - g) / 11)); val j = (a + a / 4 + i + 2 - c + c / 4) % 7
        val l = i - j; val m = 3 + (l + 40) / 44; val d = l + 28 - 31 * (m / 4)
        return LocalDate.of(a, m, d)
    }

    /** Fête ou jour connu → (nom, date de l'année [a]). */
    private val FETES: List<Pair<Regex, (Int) -> Pair<String, LocalDate>>> = listOf(
        Regex("\\bnoel\\b") to { a -> "Noël" to LocalDate.of(a, 12, 25) },
        Regex("\\b(nouvel an|jour de l an|premier de l an)\\b") to { a -> "le jour de l'An" to LocalDate.of(a, 1, 1) },
        Regex("\\b(reveillon|saint sylvestre|st sylvestre)\\b") to { a -> "la Saint-Sylvestre" to LocalDate.of(a, 12, 31) },
        Regex("\\b(saint valentin|st valentin)\\b") to { a -> "la Saint-Valentin" to LocalDate.of(a, 2, 14) },
        Regex("\\blundi de paques\\b") to { a -> "le lundi de Pâques" to paques(a).plusDays(1) },
        Regex("\\bpaques\\b") to { a -> "Pâques" to paques(a) },
        Regex("\\bascension\\b") to { a -> "l'Ascension" to paques(a).plusDays(39) },
        Regex("\\blundi de pentecote\\b") to { a -> "le lundi de Pentecôte" to paques(a).plusDays(50) },
        Regex("\\bpentecote\\b") to { a -> "la Pentecôte" to paques(a).plusDays(49) },
        Regex("\\b(fete du travail|1er mai|premier mai)\\b") to { a -> "le 1er mai" to LocalDate.of(a, 5, 1) },
        Regex("\\b(14 juillet|fete nationale)\\b") to { a -> "le 14 juillet" to LocalDate.of(a, 7, 14) },
        Regex("\\b(15 aout|assomption)\\b") to { a -> "le 15 août" to LocalDate.of(a, 8, 15) },
        Regex("\\btoussaint\\b") to { a -> "la Toussaint" to LocalDate.of(a, 11, 1) },
        Regex("\\b(armistice|11 novembre)\\b") to { a -> "le 11 novembre" to LocalDate.of(a, 11, 11) },
        Regex("\\bhalloween\\b") to { a -> "Halloween" to LocalDate.of(a, 10, 31) },
        Regex("\\bfete des meres\\b") to { a ->
            val fin = LocalDate.of(a, 5, 31).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            "la fête des mères" to (if (fin == paques(a).plusDays(49)) fin.plusWeeks(1) else fin)
        },
        Regex("\\bfete des peres\\b") to { a -> "la fête des pères" to LocalDate.of(a, 6, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.SUNDAY)) },
        Regex("\\b(ete|l ete)\\b") to { a -> "l'été" to LocalDate.of(a, 6, 21) },
        Regex("\\b(hiver|l hiver)\\b") to { a -> "l'hiver" to LocalDate.of(a, 12, 21) },
        Regex("\\b(printemps)\\b") to { a -> "le printemps" to LocalDate.of(a, 3, 20) },
        Regex("\\b(automne)\\b") to { a -> "l'automne" to LocalDate.of(a, 9, 22) },
    )

    private val RESTE_PERMIS = setOf("", "prochain", "prochaine", "qui vient", "de cette annee", "cette annee", "de l annee prochaine", "l annee prochaine")

    /** Fête (prochaine occurrence) ou date dite — et RIEN d'autre (« mon rendez-vous de lundi » n'en est pas une), ou null. */
    private fun dateDe(x0: String, aujourdhui: LocalDate): Pair<String, LocalDate>? {
        val x = x0.trim().removePrefix("le ").removePrefix("la ").removePrefix("l ").trim()
        for ((r, f) in FETES) r.find(x)?.let { m ->
            val reste = x.replace(m.value, " ").replace(Regex("\\s+"), " ").trim()
            if (reste !in RESTE_PERMIS) return null
            val cette = f(aujourdhui.year + if (reste.contains("annee prochaine")) 1 else 0)
            return if (cette.second.isBefore(aujourdhui)) f(aujourdhui.year + 1) else cette
        }
        val n = DatesFr.normaliser(x)
        val t = DatesFr.jour(n, aujourdhui) ?: return null
        if (n.replace(t.texte, " ").replace(Regex("\\s+"), " ").trim() !in RESTE_PERMIS) return null
        return DatesFr.libelle(t.date, aujourdhui) to t.date
    }

    private fun complet(d: LocalDate): String {
        val mois = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")
        return "${DatesFr.JOURS[d.dayOfWeek.value - 1]} ${if (d.dayOfMonth == 1) "1er" else d.dayOfMonth} ${mois[d.monthValue - 1]} ${d.year}"
    }

    private fun dans(n: Long) = when {
        n == 0L -> "c'est aujourd'hui"
        n == 1L -> "c'est demain"
        n > 0 -> "dans $n jours" + if (n >= 14) " (${n / 7} semaines${if (n % 7 > 0) " et ${n % 7} jour${if (n % 7 > 1) "s" else ""}" else ""})" else ""
        else -> "il y a ${-n} jour${if (n < -1) "s" else ""}"
    }

    private fun calendrier(p: String, aujourdhui: LocalDate): String? {
        // « combien de jours avant Noël / jusqu'au 24 décembre », « dans combien de jours est Pâques »
        Regex("^${POLI}(?:combien (?:de jours|de temps|reste t il de jours|il reste de jours|me reste t il|il reste)|dans combien de (?:jours|temps)|c est dans combien de jours|il reste combien de jours)" +
            " ?(?:avant|jusqu a|jusqu au|jusqu aux|d ici|pour|est|c est|sera|tombe)? (?:le |la |l |les |a |au )?(.{2,40}?)$").matchEntire(p)?.let { m ->
            val (nom, d) = dateDe(m.groupValues[1], aujourdhui) ?: return@let
            return "📅 ${nom.replaceFirstChar { it.uppercase() }} (${complet(d)}) : ${dans(ChronoUnit.DAYS.between(aujourdhui, d))}."
        }
        // « combien de jours entre le 3 et le 24 novembre »
        Regex("^${POLI}combien (?:de jours|de semaines|de temps) (?:il y a )?(?:entre|du|depuis) (.+?) (?:et|au|jusqu au|jusqu a) (.+)$").matchEntire(p)?.let { m ->
            var a = DatesFr.jour(DatesFr.normaliser(m.groupValues[1]), aujourdhui, futur = false)?.date
            val b = (dateDe(m.groupValues[2], aujourdhui)?.second) ?: return@let
            // « entre le 3 et le 24 novembre » : le mois du second s'applique au premier.
            if (a == null) Regex("^(?:le )?(\\d{1,2}|1er)$").matchEntire(m.groupValues[1].trim())?.let { j ->
                a = runCatching { b.withDayOfMonth(if (j.groupValues[1] == "1er") 1 else j.groupValues[1].toInt()) }.getOrNull()
            }
            val debut = a ?: return@let
            val n = ChronoUnit.DAYS.between(debut, b)
            return "📅 Entre le ${complet(debut)} et le ${complet(b)} : ${abs(n)} jour${if (abs(n) > 1) "s" else ""}" + (if (abs(n) >= 14) " (${abs(n) / 7} semaines et ${abs(n) % 7} j)" else "") + "."
        }
        // « quel jour tombe / sera le 3 décembre », « le 24 décembre c'est quel jour »
        (Regex("^${POLI}(?:quel jour (?:de la semaine )?(?:tombe|sera|est|etait|sera t il|tombera|ce sera|c est|c etait|on sera|on est)(?: t il)?|c est quel jour) (.+)$").matchEntire(p)?.groupValues?.get(1)
            ?: Regex("^${POLI}(.+?) (?:c est|ce sera|ca tombe|tombe) quel jour$").matchEntire(p)?.groupValues?.get(1))?.let { x ->
            val (nom, d) = dateDe(x, aujourdhui) ?: return@let
            if (Regex("^(aujourd hui|demain|apres demain|hier)$").matches(nom)) return "📅 ${nom.replaceFirstChar { it.uppercase() }}, c'est ${complet(d)}."
            return "📅 ${if (nom.first().isDigit() || nom.startsWith("lundi") || DatesFr.JOURS.any { nom.startsWith(it) }) "Le ${complet(d)}" else "${nom.replaceFirstChar { it.uppercase() }} tombe le ${complet(d)}"}" +
                (if (DatesFr.JOURS.any { nom.startsWith(it) }) "" else " — ${dans(ChronoUnit.DAYS.between(aujourdhui, d))}") + "."
        }
        // « quelle date serons-nous dans 10 jours », « quel jour sera-t-on dans 3 semaines »
        Regex("^${POLI}(?:quelle date|quel jour|on sera quel jour|on sera le combien)(?: (?:serons nous|sera t on|on sera|sera t il|ce sera|sera|on est))? dans (\\d{1,4}|une|un|deux|trois|quatre|cinq|six|sept|huit|neuf|dix) (jours?|semaines?|mois|ans?)$").matchEntire(p)?.let { m ->
            val k = m.groupValues[1].toIntOrNull() ?: mapOf("un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10).getValue(m.groupValues[1])
            val d = when { m.groupValues[2].startsWith("semaine") -> aujourdhui.plusWeeks(k.toLong()); m.groupValues[2] == "mois" -> aujourdhui.plusMonths(k.toLong()); m.groupValues[2].startsWith("an") -> aujourdhui.plusYears(k.toLong()); else -> aujourdhui.plusDays(k.toLong()) }
            return "📅 Dans ${m.groupValues[1]} ${m.groupValues[2]} : ${complet(d)}."
        }
        // Numéro de semaine
        if (Regex("^${POLI}(?:on est (?:en |dans )?quelle semaine|quelle semaine (?:sommes nous|on est|est on)|(?:quel est le )?numero (?:de|de la) semaine|c est quelle semaine)$").matches(p))
            return "📅 Nous sommes en semaine ${aujourdhui.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} (${complet(aujourdhui)})."
        return null
    }

    // ------------------------------------------------------------------ réponse

    /** Réponse calculée à la phrase, ou null si ce n'est pas un calcul / une conversion / une question de calendrier. */
    fun repondre(texte: String, aujourdhui: LocalDate = LocalDate.now()): String? {
        val brut = texte.trim()
        if (brut.isBlank() || brut.length > 160) return null
        val p = prep(brut).replace(Regex(" ?(stp|s il te plait|merci|jarvis)$"), "").trim()
        calendrier(p, aujourdhui)?.let { return it }

        // Pourcentages : « 15 % de 240 », « 20 pour cent de 80 »
        Regex("^${POLI}(?:combien (?:font|fait|ca fait|cela fait|represente|represent)|calcule(?: moi)?|ca fait combien|c est combien|ca fait|fais)? ?(?:les |le )?$NB ?(?:%|pour ?cent|pourcents?) (?:de|des|du|sur) $NB(?: .{0,12})?$").matchEntire(p)?.let { m ->
            val pct = m.groupValues[1].toDouble(); val base = m.groupValues[2].toDouble()
            return "🧮 ${fr(pct)} % de ${fr(base)} = ${fr(base * pct / 100)}"
        }
        // « 80 plus 20 % », « ajoute 20 % à 150 », « 150 moins 15 % », « remise de 15 % sur 80 »
        Regex("^${POLI}(?:combien (?:font|fait|ca fait)|calcule(?: moi)?|ca fait combien|c est combien)? ?$NB (plus|moins|\\+|-) $NB ?(?:%|pour ?cent)$").matchEntire(p)?.let { m ->
            val base = m.groupValues[1].toDouble(); val pct = m.groupValues[3].toDouble(); val plus = m.groupValues[2] in setOf("plus", "+")
            val r = base * (1 + (if (plus) pct else -pct) / 100)
            return "🧮 ${fr(base)} ${if (plus) "+" else "−"} ${fr(pct)} % = ${fr(r)}" + " (${if (plus) "+" else "−"}${fr(abs(r - base))})"
        }
        Regex("^${POLI}(?:ajoute|rajoute|applique) $NB ?(?:%|pour ?cent) (?:a|au|sur|de) $NB$").matchEntire(p)?.let { m ->
            val pct = m.groupValues[1].toDouble(); val base = m.groupValues[2].toDouble()
            return "🧮 ${fr(base)} + ${fr(pct)} % = ${fr(base * (1 + pct / 100))} (+${fr(base * pct / 100)})"
        }
        Regex("^${POLI}(?:une |la )?(?:remise|reduction|rabais|ristourne|promo|baisse) de $NB ?(?:%|pour ?cent) (?:sur|de|a) $NB(?: .{0,12})?$").matchEntire(p)?.let { m ->
            val pct = m.groupValues[1].toDouble(); val base = m.groupValues[2].toDouble()
            return "🧮 ${fr(base)} − ${fr(pct)} % = ${fr(base * (1 - pct / 100))} (remise de ${fr(base * pct / 100)})"
        }
        // TVA : « 100 HT en TTC », « 120 TTC en HT », « TVA de 10 % sur 50 »
        Regex("^${POLI}(?:combien (?:font|fait)|calcule(?: moi)?|convertis|passe)? ?$NB (?:euros? |e )?(ht|ttc) (?:en|vers|=) (ht|ttc)(?: (?:a|avec|tva) $NB ?(?:%|pour ?cent))?$").matchEntire(p)?.let { m ->
            val v = m.groupValues[1].toDouble(); val taux = m.groupValues[4].toDoubleOrNull() ?: 20.0
            if (m.groupValues[2] == m.groupValues[3]) return@let
            val r = if (m.groupValues[2] == "ht") v * (1 + taux / 100) else v / (1 + taux / 100)
            return "🧮 ${fr(v)} € ${m.groupValues[2].uppercase()} = ${fr(Math.round(r * 100) / 100.0)} € ${m.groupValues[3].uppercase()} (TVA ${fr(taux)} % : ${fr(Math.round(abs(r - v) * 100) / 100.0)} €)"
        }
        Regex("^${POLI}(?:la )?tva (?:de |a )?$NB ?(?:%|pour ?cent) (?:sur|de) $NB(?: (?:euros?|e))?(?: ht)?$").matchEntire(p)?.let { m ->
            val taux = m.groupValues[1].toDouble(); val v = m.groupValues[2].toDouble()
            return "🧮 TVA ${fr(taux)} % sur ${fr(v)} € HT = ${fr(Math.round(v * taux) / 100.0)} € → ${fr(Math.round(v * (100 + taux)) / 100.0)} € TTC"
        }
        // Conversions : « convertis 30 miles en km », « 100 fahrenheit en celsius », « combien de cl dans 1,5 litre »
        Regex("^${POLI}(?:convertis(?: moi)?|convertir|conversion(?: de)?|combien (?:font|fait|ca fait|font il|vaut|valent)|ca fait combien(?: de)?|c est combien)? ?(?:en |de )?$NB ?(?:degres? )?($U)\\b (?:en|vers|=|egal|ca fait combien(?: de| en)?|en combien de|combien de|c est combien en|ca donne combien en|ca fait combien en) (?:degres? )?($U)$").matchEntire(p)?.let { m ->
            val v = m.groupValues[1].toDouble(); val de = unite(m.groupValues[2]) ?: return@let; val vers = unite(m.groupValues[3]) ?: return@let
            val r = convertir(v, de, vers) ?: return "Je ne peux pas convertir des ${de.nom} en ${vers.nom} (ce ne sont pas les mêmes grandeurs)."
            val icone = when (de.dim) { "temp" -> "🌡️"; "vitesse" -> "🚗"; "masse" -> "⚖️"; "vol" -> "🧪"; "temps" -> "⏱️"; "donnees" -> "💾"; "energie" -> "⚡"; else -> "📏" }
            return "$icone ${fr(v)} ${de.nom} = ${fr(r)} ${vers.nom}"
        }
        Regex("^${POLI}(?:combien (?:de|d)|ca fait combien de) ($U) (?:dans|pour|font|fait|en|y a t il dans|il y a dans) $NB ?(?:degres? )?($U)$").matchEntire(p)?.let { m ->
            val vers = unite(m.groupValues[1]) ?: return@let; val v = m.groupValues[2].toDouble(); val de = unite(m.groupValues[3]) ?: return@let
            val r = convertir(v, de, vers) ?: return@let
            return "📏 ${fr(v)} ${de.nom} = ${fr(r)} ${vers.nom}"
        }
        // Opérations : « combien font 12 fois 45 », « calcule 1250 / 12 », « 12*45 », « racine carrée de 144 »
        val corps = Regex("^${POLI}(?:combien (?:font|fait|ca fait|cela fait|egal|egale)|calcule(?: moi)?|ca fait combien|c est combien|quel est le resultat de|resultat de|fais le calcul(?: de)?|fais|calcul) (.+?)(?: (?:egal|egale|=))?$").matchEntire(p)?.groupValues?.get(1)
            ?: p.takeIf { Regex("^[0-9.() ]+(?:[+*/^x-]|plus|moins|fois|divise par|sur)").containsMatchIn(it) && Regex("^[0-9.+\\-*/%^()x =a-z]+$").matches(it) &&
                !Regex("^\\d{1,2}/\\d{1,2}(/\\d{2,4})?$").matches(it) }
            ?: Regex("^${POLI}(?:quelle est la |c est quoi la )?racine (?:carree )?de $NB$").matchEntire(p)?.let { "racine de ${it.groupValues[1]}" }
            ?: return null
        val e = symboles(corps)
        // Au moins une opération (« combien font 12 » n'est pas un calcul).
        if (!Regex("[+*/^%]|sqrt").containsMatchIn(e) && !e.drop(1).contains('-')) return null
        val v = evaluer(e) ?: return null
        if (v.isNaN()) return "🧮 Calcul impossible : division par zéro (ou racine d'un nombre négatif)."
        return "🧮 ${joli(e)} = ${fr(v)}"
    }
}
