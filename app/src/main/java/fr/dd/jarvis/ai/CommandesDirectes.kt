package fr.dd.jarvis.ai

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Actions courantes reconnues SANS IA ni Hermès : heure, date, batterie, alarme, minuteur, chronomètre, appel, SMS,
 * ouverture d'une application, météo, planning, SMS et notifications reçus, point du jour, rappels, tâches, navigation,
 * musique. Seules les phrases sans ambiguïté sont prises ; sinon null et la demande suit le chemin habituel (cerveau
 * rapide de Jarvis avec ses outils, puis Hermès s'il n'aboutit pas).
 */
object CommandesDirectes {
    /**
     * Action reconnue : [outil] et ses arguments, ou calcul [local] (heure, date, batterie). [appli] : application à
     * vérifier (installée ?) avant d'agir. [texteLibre] : la phrase porte un texte libre (SMS, rappel) — jamais découpée
     * avec d'autres actions.
     */
    data class Intention(val outil: String?, val args: JSONObject = JSONObject(), val statut: String = "⚡ …",
                         val local: String? = null, val appli: String? = null, val texteLibre: Boolean = false)

    /** Minuscules sans accents, tout ce qui n'est ni lettre ni chiffre → espace, MÊME LONGUEUR que l'original. */
    internal fun plat(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val b = when (c) {
                'œ', 'Œ' -> 'o'; 'æ', 'Æ' -> 'a'
                else -> java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().let { if (it.length == 1) it[0] else ' ' }
            }
            sb.append(if (b in 'a'..'z' || b in '0'..'9') b else ' ')
        }
        return sb.toString()
    }

    private fun serre(s: String) = s.trim().replace(Regex("\\s+"), " ")

    private const val POLI = "(?:jarvis|ok|okay|alors|bon|eh|du coup|dis moi|est ce que tu peux|est ce que tu pourrais|tu peux|peux tu|pourrais tu|tu pourrais|" +
        "tu veux bien|s il te plait|s il vous plait|stp|svp|je voudrais que tu|j aimerais que tu|je veux que tu|vas y|allez)"
    private const val FIN = "(?:s il te plait|s il vous plait|stp|svp|merci|jarvis|vite|tout de suite)"

    /** Espaces d'un motif : « ? » = séparateurs facultatifs, « » = un ou plusieurs séparateurs. */
    private fun esp(m: String) = m.replace(" ?", "\\s*").replace(" ", "\\s+")

    /** Phrase entière : politesse permise autour ; chaque espace du motif accepte un ou plusieurs séparateurs. */
    private fun motif(corps: String) = Regex(esp("^\\s*(?:$POLI )*" + corps + "(?: $FIN)*\\s*$"))

    private fun MatchResult.de(o: String, i: Int): String? = groups[i]?.range?.let { o.substring(it.first, it.last + 1) }
        ?.trim()?.trim(',', '.', '!', '?', ';', ':', '"', '«', '»', ' ', '-')?.takeIf { it.isNotBlank() }

    private fun MatchResult.n(i: Int): String = serre(groupValues.getOrElse(i) { "" })

    // ------------------------------------------------------------------ nombres, durées, heures

    private val NB = mapOf("un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9,
        "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16, "vingt" to 20, "vingt cinq" to 25,
        "trente" to 30, "quarante" to 40, "quarante cinq" to 45, "cinquante" to 50, "soixante" to 60)
    private const val NBR = "(?:\\d{1,3}|vingt cinq|quarante cinq|un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|treize|quatorze|quinze|seize|vingt|trente|quarante|cinquante|soixante)"
    private fun nb(s: String?): Int? = s?.let { serre(it) }?.let { it.toIntOrNull() ?: NB[it] }

    /** « 10 minutes », « 1h30 », « une heure et demie », « 90 secondes », « un quart d'heure » → secondes. */
    internal fun duree(s0: String): Int? {
        val s = serre(s0)
        when (s) {
            "une demi heure", "une demie heure", "demi heure" -> return 1800
            "un quart d heure", "quart d heure" -> return 900
            "trois quarts d heure", "trois quart d heure" -> return 2700
        }
        Regex("^($NBR) ?(?:h|heures?)(?: ?(?:et )?(demie?|quart|$NBR)(?: ?(?:min|minutes?|mn))?)?$").matchEntire(s)?.let { m ->
            val h = nb(m.groupValues[1]) ?: return null
            val r = m.groupValues[2]
            val mi = when { r.isBlank() -> 0; r.startsWith("demi") -> 30; r == "quart" -> 15; else -> nb(r) ?: return null }
            return (h * 3600 + mi * 60).takeIf { it > 0 }
        }
        Regex("^($NBR) ?(?:min|minutes?|mn)(?: ?(?:et )?($NBR)(?: ?(?:s|sec|secondes?))?)?$").matchEntire(s)?.let { m ->
            val mi = nb(m.groupValues[1]) ?: return null
            val se = if (m.groupValues[2].isBlank()) 0 else nb(m.groupValues[2]) ?: return null
            return (mi * 60 + se).takeIf { it > 0 }
        }
        Regex("^($NBR) ?(?:s|sec|secondes?)$").matchEntire(s)?.let { m -> return nb(m.groupValues[1])?.takeIf { it > 0 } }
        return null
    }

    /** « 7h », « 7 h 30 », « 19 heures », « 7h et demie », « 8h moins le quart », « sept heures du soir », « midi » → (h, min). */
    internal fun heureDe(s0: String): Pair<Int, Int>? {
        var s = serre(s0).replace(Regex("^(a|pour|vers|de) "), "")
        var soir = false
        Regex(" (du soir|de l apres midi|de l aprem|le soir)$").find(s)?.let { soir = true; s = s.substring(0, it.range.first) }
        s = s.replace(Regex(" (du matin|le matin)$"), "")
        val base = when {
            s.startsWith("midi") -> 12 to s.removePrefix("midi")
            s.startsWith("minuit") -> 0 to s.removePrefix("minuit")
            else -> Regex("^($NBR) ?(?:heures?|h)(.*)$").matchEntire(s)?.let { m -> (nb(m.groupValues[1]) ?: return null) to m.groupValues[2] }
                ?: Regex("^(\\d{1,2}) (\\d{2})$").matchEntire(s)?.let { m -> return valide(m.groupValues[1].toInt(), m.groupValues[2].toInt(), soir) }
                ?: return null
        }
        val (h, reste0) = base
        val reste = serre(reste0)
        val mi: Int; var hh = h
        when {
            reste.isBlank() -> mi = 0
            Regex("^(et )?demie?$").matches(reste) -> mi = 30
            Regex("^et quart$").matches(reste) -> mi = 15
            Regex("^moins (le )?quart$").matches(reste) -> { mi = 45; hh = h - 1 }
            Regex("^(et )?\\d{1,2}( ?(min|minutes?))?$").matches(reste) -> mi = reste.filter { it.isDigit() }.toInt()
            else -> mi = nb(reste.removePrefix("et ").replace(Regex(" ?(min|minutes?)$"), "")) ?: return null
        }
        return valide(if (hh < 0) 23 else hh, mi, soir)
    }

    private fun valide(h: Int, m: Int, soir: Boolean): Pair<Int, Int>? {
        val hh = if (soir && h in 1..11) h + 12 else h
        return if (hh in 0..23 && m in 0..59) hh to m else null
    }

    private fun hhmm(p: Pair<Int, Int>) = "%02d:%02d".format(p.first, p.second)

    // ------------------------------------------------------------------ motifs

    private val R_HEURE = motif("(?:quelle heure (?:est il|il est)|il est quelle heure|quelle heure|l heure|donne moi l heure|dis moi l heure|tu as l heure|t as l heure)(?: (?:maintenant|exactement|actuellement))?")
    private val R_DATE = motif("(?:on est (?:le )?quel jour|quel jour (?:sommes nous|on est|est on|nous sommes|est ce)|quelle (?:est la )?date(?: d aujourd hui| du jour)?|" +
        "on est le combien|nous sommes le combien|c est quel jour|quel jour est on|la date(?: d aujourd hui)?|on est quelle date)(?: aujourd hui)?")
    private val R_BATTERIE = motif("(?:(?:quel est le |c est quoi le |donne moi le )?(?:niveau|etat|pourcentage) (?:de|d) (?:la |ma )?batterie|combien (?:de|d) batterie|" +
        "il (?:me )?reste combien (?:de|d) batterie|(?:la |ma )?batterie|j ai combien (?:de|d) batterie|combien il (?:me )?reste (?:de|d) batterie|(?:la |ma )?batterie est a combien|" +
        "niveau batterie|combien de pourcent(?:age)? (?:de batterie|il reste))(?: du telephone| du portable| de mon telephone)?")

    private val R_ALARME = motif("(?:(?:mets|met|regle|programme|cree|ajoute|active|lance|configure|place|fixe)(?: moi)? (?:une |un |mon |ma |l |le )?)?(?:alarme|reveil)(?: (?:a|pour|de|sur))? (.+)")
    private val R_REVEILLE = motif("(?:reveille moi|reveille nous|fais moi lever|tu me reveilles)(?: (?:a|pour|vers))? (.+)")
    private val R_MINUTEUR = motif("(?:(?:mets|met|lance|demarre|programme|cree|regle|active|declenche|enclenche)(?: moi)? (?:un |le |une )?)?(?:minuteur|compte a rebours|timer|minuterie)(?: (?:de|pour|sur|a|d))? (.+)")
    private val R_CHRONO = motif("(lance|demarre|declenche|mets|active|arrete|stoppe|stop|coupe|ou en est|temps du|etat du)(?: le| un| mon)? chrono(?:metre)?")
    private val R_APPEL = motif("(?:appelle|appeler|rappelle|telephone a|telephone au|telephoner a|passe moi|passe un coup de fil a|passe un appel a|fais un appel a|lance un appel a|lance un appel vers|compose le|compose) (.+)")
    private val R_SMS = listOf(
        motif("(?:envoie|envoyer|envoi|ecris|ecrire|redige|balance|fais)(?: moi)? (?:un |une )?(?:petit )?(?:sms|texto|message texte) (?:a|au|pour) (.+?) (?:pour (?:lui |leur )?dire (?:que |qu )?|en (?:lui |leur )?disant (?:que |qu )?|disant (?:que |qu )?|qui dit (?:que |qu )?|avec le texte |avec le message |que |qu )(.+)"),
        motif("(?:envoie|ecris)(?: a| au) (.+?) (?:un |une )?(?:sms|texto) (?:pour (?:lui |leur )?dire (?:que |qu )?|disant (?:que |qu )?|qui dit (?:que |qu )?|que |qu )?(.+)"),
        motif("dis (?:a|au) (.+?) par (?:sms|texto) (?:que |qu )?(.+)"),
    )
    /** Séparateur « : » de « envoie un SMS à Paul : j'arrive » (disparu dans le texte aplati). */
    private val R_SMS_DEUX_POINTS = Regex("^(?:[^:]*?)\\b(?:sms|texto)\\b\\s*(?:à|a|au|pour)\\s+([^:]{1,60}?)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val R_OUVRE = motif("(?:ouvre|ouvrir|lance|lancer|demarre|demarrer)(?: moi)?(?: l appli| l application| l app)?(?: de)? (.+)")
    private val METEO = listOf(
        motif("(?:(?:donne|dis|montre|affiche|lis)(?: moi)? )?(?:la |le |les )?(?:meteo|previsions meteo|bulletin meteo)(?: (.+))?"),
        motif("(?:quel|quelle) (?:temps|meteo) (?:fait il|il fait|fera t il|va t il faire|il va faire|on va avoir|est prevue?|prevoit on|on aura)(?: (.+))?"),
        motif("il (?:fait|fera|va faire) quel temps(?: (.+))?"),
        motif("(?:est ce qu il va|va t il|il va|est ce qu il) (?:pleuvoir|neiger|faire beau|faire chaud|faire froid|geler|y avoir du vent|y avoir de l orage)(?: (.+))?"),
        motif("(?:quelle|quel) (?:est la )?temperature (?:dehors|exterieure|il fait|fait il|fera t il|va t il faire)(?: (.+))?"),
        motif("il fait combien(?: de degres)?(?: dehors)?(?: (.+))?"),
    )
    private const val PERIODE = "(?:aujourd hui|d aujourd hui|du jour|ce matin|cet apres midi|ce soir|demain(?: matin| soir| apres midi)?|de demain|apres demain|d apres demain|" +
        "cette semaine|de la semaine|de cette semaine|la semaine prochaine|de la semaine prochaine|ce week end|du week end|" +
        "(?:de |le |ce |pour )?(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)(?: prochain)?)"
    private val PLANNING = listOf(
        motif("(?:(?:donne|montre|affiche|lis|dis|fais)(?: moi)? )?(?:(?:mon|le|l|ton) )?(?:planning|agenda|emploi du temps)(?: ($PERIODE))?"),
        motif("(?:(?:donne|montre|affiche|lis|dis)(?: moi)? )?mon programme(?: ($PERIODE))?"),
        motif("(?:qu est ce que j ai|qu ai je|j ai quoi|qu est ce qu il y a|qu est ce qui est prevu|qu y a t il|quoi)(?: de prevu| au programme| dans (?:mon|l) agenda| dans mon planning)? ($PERIODE)"),
        motif("(?:qu est ce que j ai|qu ai je|j ai quoi) de prevu"),
        motif("(?:quels sont |c est quoi |donne moi |montre moi |liste |lis moi )?(?:mes|les) (?:rendez vous|rdv|evenements)(?: ($PERIODE))?"),
        motif("(?:est ce que )?j ai (?:des |un |quelque chose de |quelque chose |du monde )?(?:rendez vous|rdv|prevu|de prevu|quelque chose)(?: de prevu)?(?: ($PERIODE))?"),
        motif("(?:quel est |c est quoi )?mon prochain (?:rendez vous|rdv)"),
    )
    private val R_SMS_LIRE = listOf(
        motif("(?:lis|lire|relis|montre|affiche|donne|quels sont)(?: moi)? (?:mes |les )?(?:derniers |nouveaux |dernier |nouveau )?(?:sms|textos?|messages texte)(?: (?:non lus?|recus))?"),
        motif("(?:est ce que )?j ai (?:des |de nouveaux |recu des |recu de nouveaux |un |recu un )?(?:nouveaux )?(?:sms|textos?)(?: non lus?)?"),
        motif("(?:mes )?(?:sms|textos) non lus?"),
    )
    private val R_NOTIFS = listOf(
        motif("(?:lis|lire|relis|montre|affiche|donne|quelles sont)(?: moi)? (?:mes |les )?(?:dernieres |nouvelles )?notifications?"),
        motif("(?:est ce que )?j ai (?:des |de nouvelles |recu des )?notifications?"),
    )
    private val R_BRIEFING = motif("(?:(?:fais|donne|lance|dis)(?: moi)? )?(?:le |un |mon )?(?:point du jour|point du matin|briefing|brief du jour|point de (?:la |ma )?journee|resume de (?:la |ma )?journee)|(?:fais|faisons)(?: moi)? le point")
    private val R_TACHES = motif("(?:(?:liste|montre|affiche|donne|lis|quelles sont)(?: moi)? )?(?:mes taches|ma liste de taches|la liste de mes taches|mes taches en cours|mes taches a faire)")
    private val R_RAPPEL = motif("(?:rappelle moi|rappelle nous|fais moi penser|fais nous penser|n oublie pas de me rappeler|pense a me rappeler|tu me rappelleras|tu peux me rappeler|" +
        "(?:mets|met|cree|ajoute|programme|fais)(?: moi)? un rappel|un rappel|rappel) (.+)")
    private val R_NAV = motif("(?:emmene moi|emmene nous|amene moi|conduis moi|guide moi|guide nous|lance la navigation|demarre la navigation|lance le gps|navigation|navigue|" +
        "(?:lance|ouvre|demarre) (?:waze|google maps|maps))(?: (?:a|au|aux|vers|jusqu a|jusqu au|jusque|pour|en|direction|sur))* (.+)")
    private const val APPLIS_MUSIQUE = "spotify|deezer|youtube music|yt music|apple music|amazon music|youtube"
    private val R_JOUE = listOf(
        motif("(?:joue|jouer|mets|met|lance|ecoute|ecouter|passe|fais moi ecouter)(?: moi)? (?:la chanson|le morceau|le titre|les chansons de|la musique de|de la musique de|des chansons de|du rap de) (.+?)(?: (?:sur|dans|avec) ($APPLIS_MUSIQUE))?"),
        motif("(?:joue|jouer|mets|met|lance|ecoute|ecouter|passe|fais moi ecouter)(?: moi)? (?:l |la |ma )?((?:album|playlist) .+?)(?: (?:sur|dans|avec) ($APPLIS_MUSIQUE))?"),
        motif("joue (.+?)(?: (?:sur|dans|avec) ($APPLIS_MUSIQUE))?"),
        motif("(?:mets|met|lance|ecoute|passe)(?: moi)? (.+?) (?:sur|dans|avec) ($APPLIS_MUSIQUE)"),
    )

    // ------------------------------------------------------------------ rappels : quand ?

    private const val H = "(?:(?:a|vers|pour) )?(?:\\d{1,2} ?h(?: ?\\d{2})?|\\d{1,2} heures?(?: \\d{2})?|midi|minuit)(?: (?:et demie?|et quart))?"
    private const val J = "(?:aujourd hui|demain|apres demain|ce matin|ce midi|ce soir|cet apres midi|cette nuit|(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)(?: prochain)?|" +
        "le \\d{1,2}(?:er)?(?: (?:janvier|fevrier|mars|avril|mai|juin|juillet|aout|septembre|octobre|novembre|decembre))?)(?: (?:matin|midi|soir|apres midi))?"
    private const val D = "dans (?:$NBR|une demi|un quart d) ?(?:min|minutes?|mn|h|heures?|heure|jours?|semaines?)(?: (?:et )?\\d{1,2}(?: ?min(?:utes?)?)?)?"
    private const val REP = "(?:tous les jours|chaque jour|tous les matins|tous les soirs|chaque matin|chaque soir|en semaine|du lundi au vendredi|un jour sur deux|tous les deux jours|" +
        "chaque semaine|toutes les semaines|chaque mois|tous les mois|(?:tous les|chaque) (?:lundis?|mardis?|mercredis?|jeudis?|vendredis?|samedis?|dimanches?))"
    private const val QUAND = "(?:(?:$REP )?(?:$J(?: $H)?|$H(?: $J)?|$D)|$REP(?: $H)?)"
    private val QUAND_FIN = Regex(esp("(?:^| )($QUAND)\\s*$"))
    private val QUAND_DEBUT = Regex(esp("^\\s*($QUAND)(?: |$)"))

    /** « dans deux heures » → « dans 2 heures », « dans un quart d'heure » → « dans 15 min » (compris par les rappels). */
    internal fun quandLisible(q: String): String = serre(q).replace(Regex("^dans un quart d heure$"), "dans 15 min")
        .replace(Regex("^dans ($NBR) ")) { m -> "dans ${nb(m.groupValues[1]) ?: m.groupValues[1]} " }

    // ------------------------------------------------------------------ reconnaissance

    /** Action reconnue dans [texte] (sans rien exécuter), ou null. */
    fun intention(texte: String, maintenant: LocalDateTime = LocalDateTime.now()): Intention? {
        val o = texte.trim()
        if (o.isBlank() || o.length > 300) return null
        val p = plat(o)
        val k = serre(p)
        if (Regex("\\bhermes\\b").containsMatchIn(k)) return null

        if (R_HEURE.matches(p)) return Intention(null, local = "heure", statut = "🕐 Heure")
        if (R_DATE.matches(p)) return Intention(null, local = "date", statut = "📅 Date")
        if (R_BATTERIE.matches(p)) return Intention(null, local = "batterie", statut = "🔋 Batterie")

        alarme(p, maintenant)?.let { return it }
        R_MINUTEUR.matchEntire(p)?.let { m ->
            val r = m.n(1)
            val (dureeTxt, libelle) = Regex("^(.+?) pour (.+)$").matchEntire(r)?.let { it.groupValues[1] to it.groupValues[2] } ?: (r to "")
            val s = duree(dureeTxt) ?: return@let
            return Intention("minuteur_creer", JSONObject().put("secondes", s.toString()).put("libelle", libelle), "⏱️ Minuteur…")
        }
        R_CHRONO.matchEntire(p)?.let { m ->
            val v = m.n(1)
            val action = when { v.startsWith("arret") || v.startsWith("stop") || v == "coupe" -> "arreter"; v.startsWith("ou") || v.startsWith("temps") || v.startsWith("etat") -> "etat"; else -> "demarrer" }
            return Intention("chronometre", JSONObject().put("action", action), "⏱️ Chronomètre…")
        }
        rappel(o, p, maintenant)?.let { return it }
        appel(o, p)?.let { return it }
        sms(o, p)?.let { return it }
        meteo(p)?.let { return it }
        planning(p, maintenant.toLocalDate())?.let { return it }
        if (R_SMS_LIRE.any { it.matches(p) }) return Intention("lire_sms", JSONObject().put("count", "10")
            .apply { if (Regex("non lu|nouveau|j ai").containsMatchIn(k)) put("non_lus", "true") }, "💬 SMS…")
        if (R_NOTIFS.any { it.matches(p) }) return Intention("lire_notifications", JSONObject().put("count", "15"), "🔔 Notifications…")
        if (R_BRIEFING.matches(p)) return Intention("morning_briefing", JSONObject(), "☀️ Point du jour…")
        if (R_TACHES.matches(p)) return Intention("taches_lister", JSONObject(), "✅ Tâches…")
        navigation(o, p)?.let { return it }
        musique(o, p)?.let { return it }
        ouvrir(o, p)?.let { return it }
        return null
    }

    private fun alarme(p: String, maintenant: LocalDateTime): Intention? {
        val m = R_ALARME.matchEntire(p) ?: R_REVEILLE.matchEntire(p) ?: return null
        var r = m.n(1)
        var libelle = ""
        Regex(" pour (?!demain|ce |cet |aujourd|tous|chaque|le week|la semaine|\\d)(.+)$").find(r)?.let { libelle = it.groupValues[1]; r = r.substring(0, it.range.first) }
        var jours = ""
        val j = "(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)s?"
        listOf(
            Regex("\\b(?:tous les jours|chaque jour|tous les matins|chaque matin)\\b") to "tous les jours",
            Regex("\\b(?:en semaine|du lundi au vendredi|les jours de semaine|jours ouvres|les jours ouvres)\\b") to "semaine",
            Regex("\\b(?:le week end|les week ends|tous les week ends|le weekend|chaque week end)\\b") to "sam dim",
        ).firstOrNull { it.first.containsMatchIn(r) }?.let { (re, v) -> jours = v; r = r.replace(re, " ") }
        if (jours.isBlank()) Regex("\\b(?:tous les|chaque|le) ($j(?: (?:et|,) $j)*)\\b").find(r)?.let { mm ->
            jours = Regex(j).findAll(mm.groupValues[1]).map { it.value.removeSuffix("s").take(3) }.joinToString(" ")
            r = r.replace(mm.value, " ")
        }
        r = serre(r.replace(Regex("\\b(?:demain matin|demain|aujourd hui|cette nuit|ce matin|le matin|pour demain)\\b"), " ")).removeSuffix(" et").trim()
        val heure = Regex("^dans (.+)$").matchEntire(r)?.let { d -> duree(d.groupValues[1])?.let { s -> maintenant.plusSeconds(s.toLong()).let { it.hour to it.minute } } }
            ?: heureDe(r.replace(Regex("\\bce soir\\b"), "").trim().let { if (Regex("\\bce soir\\b").containsMatchIn(r)) "$it du soir" else it })
            ?: return null
        return Intention("alarme_creer", JSONObject().put("heure", hhmm(heure)).put("libelle", libelle).put("jours", jours), "⏰ Alarme…")
    }

    private fun rappel(o: String, p: String, maintenant: LocalDateTime): Intention? {
        val m = R_RAPPEL.matchEntire(p) ?: return null
        val debut = m.groups[1]?.range?.first ?: return null
        val reste = m.groups[1]!!.range.let { o.substring(it.first, it.last + 1) }
        val restePlat = p.substring(debut, debut + reste.length)
        val (quand, quoi) = QUAND_FIN.find(restePlat)?.takeIf { it.range.first > 0 }?.let { q -> serre(q.groupValues[1]) to reste.substring(0, q.range.first) }
            ?: QUAND_DEBUT.find(restePlat)?.takeIf { it.range.last + 1 < restePlat.length }?.let { q -> serre(q.groupValues[1]) to reste.substring(q.range.last + 1) }
            ?: return null
        val texte = quoi.trim().trim(',', '.', ' ')
            .replace(Regex("^(?:de |d'|d’|qu'|qu’|que |qu |pour |a |à )", RegexOption.IGNORE_CASE), "").trim()
            .replace(Regex("(?:,?\\s*(?:s'il te pla[iî]t|stp|svp|merci))+$", RegexOption.IGNORE_CASE), "").trim().trimEnd('.', '!', ',')
        if (texte.length < 2) return null
        val q = quandLisible(quand)
        if (fr.dd.jarvis.device.Rappels.interpreter(q, maintenant) == null) return null
        return Intention("rappel_creer", JSONObject().put("texte", texte.replaceFirstChar { it.uppercase() }).put("quand", q), "⏰ Rappel…", texteLibre = true)
    }

    private fun appel(o: String, p: String): Intention? {
        val m = R_APPEL.matchEntire(p) ?: return null
        val n = m.n(1)
        val numero = Regex("^(?:le )?\\+?[\\d ]{2,20}$").matches(n)
        if (!numero && n.split(' ').size > 5 || Regex("^(moi|toi|nous|vous|lui|leur|les|me|te)\\b").containsMatchIn(n) ||
            Regex("\\b(pour|et|puis|si|quand|demain|ce soir|dans|plus tard|apres|avant|whatsapp|messenger|signal|skype|teams|discord|facetime|visio|video|haut parleur|que|qu)\\b|\\ba \\d").containsMatchIn(n) && !numero) return null
        val contact = m.de(o, 1)?.replace(Regex("(?i)^(le |la |l')(?=\\d)"), "") ?: return null
        return Intention("appeler_contact", JSONObject().put("contact", contact), "📞 Appel…")
    }

    private fun sms(o: String, p: String): Intention? {
        // « Envoie un SMS à Paul : j'arrive » (deux-points dans le texte d'origine).
        R_SMS_DEUX_POINTS.matchEntire(o)?.takeIf { Regex(esp("^\\s*(?:$POLI )*(?:envoie|envoyer|envoi|ecris|ecrire|redige|balance|fais)\\b")).containsMatchIn(p) }?.let { m ->
            val dest = m.groupValues[1].trim(); val msg = m.groupValues[2].trim()
            if (dest.split(Regex("\\s+")).size <= 5 && msg.isNotBlank()) return smsIntention(dest, msg)
        }
        for (r in R_SMS) {
            val m = r.matchEntire(p) ?: continue
            val dest = m.de(o, 1) ?: continue
            val msg = m.groups[2]?.range?.let { o.substring(it.first) }?.trim() ?: continue
            if (dest.split(Regex("\\s+")).size > 5 || msg.isBlank()) continue
            return smsIntention(dest, msg)
        }
        return null
    }

    private fun smsIntention(dest: String, msg0: String): Intention {
        val msg = msg0.replace(Regex("(?:,?\\s*(?:s'il te pla[iî]t|stp|svp|jarvis))+[.!]?$", RegexOption.IGNORE_CASE), "").trim().trimStart(':', ',', ' ')
        return Intention("envoyer_sms", JSONObject().put("destinataire", dest).put("texte", msg.replaceFirstChar { it.uppercase() }), "💬 SMS…", texteLibre = true)
    }

    private fun meteo(p: String): Intention? {
        val m = METEO.firstNotNullOfOrNull { it.matchEntire(p) } ?: return null
        var r = m.n(1)
        var jours: Int? = null
        val periodes = listOf(
            Regex("\\b(?:apres demain|d apres demain)\\b") to 3,
            Regex("\\b(?:demain|de demain)(?: (?:matin|soir|apres midi))?\\b") to 2,
            Regex("\\b(?:ce week end|du week end|cette semaine|de la semaine|la semaine prochaine|pour la semaine|des prochains jours|les prochains jours|cette fin de semaine|(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)(?: prochain)?)\\b") to 7,
            Regex("\\b(?:aujourd hui|d aujourd hui|ce matin|cet apres midi|ce soir|cette nuit|maintenant|en ce moment|actuellement|dehors|du jour|cet aprem)\\b") to 1,
        )
        for ((re, j) in periodes) if (re.containsMatchIn(r)) { jours = maxOf(jours ?: 0, j); r = re.replace(r, " ") }
        r = serre(r)
        var lieu = ""
        if (r.isNotBlank()) {
            val l = Regex("^(?:a|au|aux|pour|sur|en|de|du|vers|dans|chez) (.+)$").matchEntire(r)?.groupValues?.get(1) ?: return null
            if (l.split(' ').size > 4 || Regex("\\b(chantier|client|installation|rendez vous|rdv|prochain|serveur|salon|chambre|cuisine|maison du)\\b").containsMatchIn(l)) return null
            lieu = l.split(' ').joinToString(" ") { w -> if (w in setOf("le", "la", "les", "de", "du", "des", "sur", "en", "l", "d")) w else w.replaceFirstChar { it.uppercase() } }
                .replaceFirstChar { it.uppercase() }
        }
        return Intention("meteo", JSONObject().put("lieu", lieu).put("jours", (jours ?: 3).toString()), "🌦️ Météo…")
    }

    private fun planning(p: String, aujourdhui: LocalDate): Intention? {
        val k = serre(p)
        if (Regex("^(?:quel est |c est quoi )?mon prochain (?:rendez vous|rdv)$").matches(k)) return Intention("get_planning",
            JSONObject().put("date", aujourdhui.toString()).put("days", "7").put("question", "Quel est mon prochain rendez-vous ?"), "📅 Planning…")
        val m = PLANNING.firstNotNullOfOrNull { it.matchEntire(p) } ?: return null
        val per = serre(m.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty())
        val jours = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
        val (date, n) = when {
            per.isBlank() || Regex("aujourd hui|du jour|ce matin|cet apres midi|ce soir").containsMatchIn(per) -> aujourdhui to 1
            per.contains("apres demain") -> aujourdhui.plusDays(2) to 1
            per.contains("demain") -> aujourdhui.plusDays(1) to 1
            per.contains("semaine prochaine") -> aujourdhui.plusDays((8 - aujourdhui.dayOfWeek.value).toLong()) to 7
            per.contains("semaine") -> aujourdhui to (8 - aujourdhui.dayOfWeek.value)
            per.contains("week end") -> if (aujourdhui.dayOfWeek.value == 7) aujourdhui to 1 else aujourdhui.plusDays((6 - aujourdhui.dayOfWeek.value).toLong()) to 2
            else -> {
                val i = jours.indexOfFirst { per.contains(it) }.takeIf { it >= 0 } ?: return null
                var d = aujourdhui; var x = 0
                while (d.dayOfWeek.value != i + 1 || (per.contains("prochain") && x == 0)) { d = d.plusDays(1); x++ }
                d to 1
            }
        }
        return Intention("get_planning", JSONObject().put("date", date.toString()).put("days", n.toString()), "📅 Planning…")
    }

    private fun navigation(o: String, p: String): Intention? {
        val m = R_NAV.matchEntire(p) ?: return null
        val n = m.n(1)
        if (n.split(' ').size > 8 || Regex("\\b(rendez vous|rdv|prochain|chantier|client|installation|et|puis)\\b").containsMatchIn(n)) return null
        // « Chez Martin » : client ou contact à retrouver d'abord — le cerveau s'en charge.
        if (Regex("\\bchez (?!moi\\b)").containsMatchIn(serre(p))) return null
        var dest = m.de(o, 1) ?: return null
        dest = dest.replace(Regex("(?i)\\s+(?:avec|sur|par|dans)\\s+(?:waze|google maps|maps)$"), "").trim()
        val appli = when { Regex("\\bwaze\\b").containsMatchIn(p) -> "Waze"; Regex("\\b(google maps|maps)\\b").containsMatchIn(p) -> "Google Maps"; else -> "" }
        if (dest.isBlank()) return null
        return Intention("naviguer", JSONObject().put("destination", if (serre(plat(dest)) == "chez moi") "maison" else dest).put("appli", appli), "🧭 Navigation…")
    }

    private fun musique(o: String, p: String): Intention? {
        val m = R_JOUE.firstNotNullOfOrNull { it.matchEntire(p) } ?: return null
        val n = m.n(1)
        if (n.isBlank() || n.split(' ').size > 8 || Regex("\\b(film|films|serie|series|episode|video|videos|tele|tv|plex|avec moi|un jeu|aux|chauffage|poele|lumiere|volets?|son|volume|alarme|minuteur|vibreur|sonnerie|mode|wifi|torche|clim|ventilateur|routine|chrono)\\b").containsMatchIn(n)) return null
        val requete = m.de(o, 1) ?: return null
        val appli = m.n(2).let { a -> when (a) { "" -> ""; "spotify" -> "Spotify"; "deezer" -> "Deezer"; "youtube" -> "YouTube"; "apple music" -> "Apple Music"; "amazon music" -> "Amazon Music"; else -> "YouTube Music" } }
        return Intention("jouer_musique", JSONObject().put("requete", requete).put("appli", appli), "🎵 Musique…")
    }

    private fun ouvrir(o: String, p: String): Intention? {
        val m = R_OUVRE.matchEntire(p) ?: return null
        val n = m.n(1).replace(Regex("^(le|la|les|l|mon|ma|mes|un|une) "), "")
        if (n.isBlank() || n.split(' ').size > 4 || Regex("\\d|\\b(et|puis|sur|dans|pour)\\b").containsMatchIn(n)) return null
        val nom = m.de(o, 1)?.replace(Regex("(?i)^(le |la |les |l'|l’|mon |ma |mes |un |une )"), "") ?: return null
        return Intention("phone_action", JSONObject().put("action", "open_app").put("value", nom), "📱 Ouverture…", appli = nom)
    }

    // ------------------------------------------------------------------ exécution

    /** Application installée dont le nom correspond exactement (ou en début de nom) : (paquet, nom affiché), ou null. */
    fun appliInstallee(ctx: android.content.Context, nom: String): Pair<String, String>? = runCatching {
        val pm = ctx.packageManager
        val q = serre(plat(nom))
        if (q.length < 2) return null
        val applis = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }
        applis.firstOrNull { serre(plat(it.second)) == q } ?: applis.filter { q.length >= 4 && serre(plat(it.second)).startsWith("$q ") }.singleOrNull()
    }.getOrNull()

    /** Réponse locale (heure, date, batterie). */
    fun local(ctx: android.content.Context, quoi: String): String = when (quoi) {
        "heure" -> "🕐 Il est " + java.time.LocalTime.now().let { if (it.minute == 0) "${it.hour} h" else "${it.hour} h ${"%02d".format(it.minute)}" } + "."
        "date" -> "📅 Nous sommes le " + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", java.util.Locale.FRENCH)) + "."
        else -> runCatching {
            val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
            val niveau = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charge = bm.isCharging
            val reste = if (!charge) "" else runCatching { bm.computeChargeTimeRemaining().takeIf { it > 0 }?.let { ms -> " — pleine dans ${ms / 3_600_000} h ${(ms / 60_000) % 60} min" } }.getOrNull().orEmpty()
            (if (niveau <= 15 && !charge) "🪫" else "🔋") + " Batterie à $niveau %" + (if (charge) " (en charge$reste)" else "") + "."
        }.getOrDefault("Je n'arrive pas à lire le niveau de batterie.")
    }
}
