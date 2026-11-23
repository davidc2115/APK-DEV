package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.DatesFr
import fr.dd.jarvis.data.Store
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * « Le rendez-vous est terminé », « le rendez-vous avec Dupont est fini, mets-le en gris », « tous mes rendez-vous
 * d'aujourd'hui sont terminés » : le rendez-vous prend la couleur demandée (sinon celle choisie pour les rendez-vous
 * terminés) et JARVIS le considère comme terminé (planning, prochain rendez-vous, navigation, mode rendez-vous, points
 * du matin et du soir). Aucune annotation n'est écrite dans l'événement : ni titre, ni description ne changent ; l'état
 * « terminé » reste dans JARVIS. La couleur va aussi dans Google Agenda (réglable : JARVIS seulement).
 */
object RdvTermines {
    private const val GARDE_MS = 120L * 86_400_000L

    fun cle(e: EventInfo) = "${e.id}|${e.begin}"

    fun lire(json: String): Map<String, JSONObject> = runCatching {
        val o = JSONObject(json.ifBlank { "{}" })
        o.keys().asSequence().associateWith { o.getJSONObject(it) }
    }.getOrDefault(emptyMap())

    fun lire(store: Store) = lire(store.rdvTermines.value)

    fun marque(termines: Map<String, JSONObject>, e: EventInfo): JSONObject? = termines[cle(e)]
    fun estTermine(store: Store, e: EventInfo) = marque(lire(store), e) != null
    /** Couleur gardée dans JARVIS seulement (Google Agenda non modifié), pour l'affichage du planning. */
    fun couleurLocale(termines: Map<String, JSONObject>, e: EventInfo): Int? =
        marque(termines, e)?.takeIf { it.optBoolean("local") && it.has("c") }?.optInt("c")

    private fun sauver(store: Store, m: Map<String, JSONObject>) {
        val limite = System.currentTimeMillis() - GARDE_MS
        val o = JSONObject()
        m.filterValues { it.optLong("b") > limite }.forEach { (k, v) -> o.put(k, v) }
        store.rdvTermines.set(o.toString())
    }

    // ------------------------------------------------------------------ couleurs

    private val COULEURS = listOf("vert fonce", "bleu ciel", "rouge", "tomate", "rose", "flamant", "orange", "mandarine", "jaune", "banane", "vert", "sauge",
        "basilic", "turquoise", "paon", "bleu", "myrtille", "violet", "raisin", "lavande", "gris", "graphite", "noir", "blanc")
    private val ALT = COULEURS.joinToString("|")
    /** « en jaune », « couleur jaune », « au jaune », « mets-le jaune », « passe-les orange », « terminé jaune »… */
    private val R_COULEUR = Regex("(?:\\ben|\\bcouleur|\\bde couleur|\\bau|\\bdu|\\bavec(?: du| le| la)?|\\bmets?(?: le| la| les)?|\\bmettre(?: le| la| les)?|" +
        "\\bpasse(?:r|s)?(?: le| la| les)?|\\bcolor\\w*(?: le| la| les)?(?: en)?|\\btermine(?:e|s|es)?|\\bfinie?s?|\\bfaite?s?)\\s+($ALT)\\b")
    /** Couleur dite seule en fin de phrase : « …, jaune ». */
    private val R_COULEUR_FIN = Regex("(?:^|[ ,])($ALT)\\s*$")
    private val ANGLAIS = mapOf("yellow" to "jaune", "red" to "rouge", "green" to "vert", "dark green" to "vert fonce", "blue" to "bleu",
        "light blue" to "bleu ciel", "sky blue" to "bleu ciel", "purple" to "violet", "violet" to "violet", "pink" to "rose", "orange" to "orange",
        "grey" to "gris", "gray" to "gris", "black" to "noir", "white" to "blanc", "turquoise" to "turquoise", "lavender" to "lavande")

    internal fun couleurDite(n: String): String? = R_COULEUR.find(n)?.groupValues?.get(1) ?: R_COULEUR_FIN.find(n.trim())?.groupValues?.get(1)

    /** Nom de couleur reconnu (« Jaune », « yellow », « en jaune », « #F6BF26 ») ou null. */
    internal fun couleurConnue(s: String?): String? {
        val t = normaliser(s.orEmpty()).trim()
        if (t.isBlank()) return null
        if (s!!.trim().startsWith("#")) return s.trim()
        return t.takeIf { it in COULEURS } ?: ANGLAIS[t] ?: couleurDite("en $t") ?: couleurDite(t)
    }
    private fun affiche(c: String) = c.replace("vert fonce", "vert foncé")

    // ------------------------------------------------------------------ compréhension (sans IA)

    data class Cible(val jour: LocalDate? = null, val heure: Int? = null, val minute: Int? = null, val partie: Pair<String, IntRange>? = null,
                     val mots: List<String> = emptyList(), val chez: Boolean = false, val rang: Int? = null, val tous: Boolean = false)

    sealed interface Intention {
        data class Terminer(val cible: Cible, val couleur: String?) : Intention
        data class Reprendre(val cible: Cible) : Intention
        data class Reglage(val couleur: String?, val google: Boolean?) : Intention
        data class Lister(val jour: LocalDate?) : Intention
    }

    private val SUJET = Regex("\\b(rendez vous|rendezvous|rdv|reunion|visite|consultation)s?\\b")
    private const val PART = "(?:termine|fini|acheve|clos|cloture|boucle)e?s?"
    private val FINI = listOf(
        Regex("\\b(?:est|sont|c est|a ete|ont ete|voila)\\s+(?:bien |enfin |maintenant |deja |donc )?(?:$PART|faite?s?)\\b"),
        Regex("\\b(?:rendez vous|rdv|reunion|visite|consultation)s?\\b(?:\\s+\\S+){0,6}?\\s+$PART\\b"),
        Regex("\\b(?:j ai|on a|nous avons|je viens de|on vient de|j en ai)\\s+(?:enfin |bien |deja )?(?:termine|fini|boucle|acheve|cloture)r?\\b"),
        Regex("\\b(?:marque|marquer|note|noter|considere|indique|passe|mets?)\\b.{0,60}\\bcomme (?:$PART|faite?s?)\\b"),
        Regex("\\bfin (?:du|de mon|de ma|de la|de l) (?:rendez vous|rdv|reunion|visite|consultation)\\b"),
        Regex("\\b(?:comme|en) (?:marquee?s?|$PART|faite?s?)\\b"),
        // Impératif : « termine tous les rendez-vous d'hier », « clôture le rendez-vous Dupont ».
        Regex("\\b(?:termine|cloture|boucle|clos)\\s+(?:tous |toutes )?(?:les |mes |le |mon |ce |ces |la |ma )?(?:\\d+ )?(?:rendez vous|rdv|reunion|visite|consultation)s?\\b"),
    )
    /**
     * Retirer l'état terminé : « retire comme quoi le rendez-vous est terminé », « enlève la coche », « décoche-le »,
     * « remets-le à faire », « il n'est plus terminé », « annule le rendez-vous terminé »…
     */
    private val NEG = listOf(
        Regex("\\b(?:pas|plus|jamais|non) (?:encore |vraiment |du tout )?(?:termine|fini|fait|acheve)e?s?\\b"),
        Regex("\\b(?:decoche|demarque|devalide)\\w*"),
        Regex("\\b(?:enleve|retire|ote|annule|defai|supprime|efface)\\w*\\b.{0,60}\\b(?:comme quoi|l etat|le statut|le termine|le fini|le fait que|" +
            "la coche|l encoche|le marquage|la marque|la mention|le check|marquee? comme|comme (?:termine|fini|fait)|coches?|encoches?|marquages?)"),
        Regex("\\b(?:enleve|retire|ote|annule|defai)\\w*\\b.{0,40}\\b(?:rendez vous|rdv|reunion|visite)s? (?:termine|fini|marque|fait)e?s?\\b"),
        Regex("\\b(?:remets?|remettre|repasse|repasser|reviens|revenir|rebascule|redevien\\w*)\\b.{0,60}\\b(?:a faire|en cours|non termine|pas termine|comme avant|en attente|normal|d origine|initial|a venir)\\b"),
        Regex("\\b(?:comme|en) (?:a faire|non termine|pas termine)\\b"),
    )
    private val QUESTION = Regex("^(?:est ce que|est ce qu|qu est ce|quand|pourquoi|comment|ou)\\b|\\b(?:se termine|se finit|finit a|termine a|termine vers|va (?:se )?terminer|a quelle heure|quelle heure|combien de temps|avant de|apres le|une fois (?:le|que))\\b")
    private val LISTER = Regex("\\b(?:quels?|quelles?|lesquels|lesquelles|liste|montre|combien)\\b.{0,40}\\b(?:rendez vous|rdv|reunions?|visites?)\\b.{0,40}\\b(?:termine|fini|fait)e?s?\\b|\\bmes (?:rendez vous|rdv) termines\\b")
    private val HEURE = Regex("\\b(?:de|a|pour|vers)? ?(\\d{1,2}) ?(?:h|heures?)(?: ?(\\d{2}))?\\b")
    private val RANGS = listOf(Regex("\\b(premier|premiere|1er|1ere)\\b") to 1, Regex("\\b(deuxieme|second|seconde|2e|2eme)\\b") to 2,
        Regex("\\b(troisieme|3e|3eme)\\b") to 3, Regex("\\b(dernier|derniere)\\b") to -1)
    private val VIDES = setOf(
        "le", "la", "les", "l", "un", "une", "des", "de", "du", "d", "mon", "ma", "mes", "ton", "ta", "son", "sa", "ses", "notre", "nos", "ce", "cet", "cette", "ces",
        "a", "au", "aux", "en", "avec", "chez", "pour", "sur", "dans", "et", "est", "sont", "c", "ete", "ont", "voila", "bien", "enfin", "maintenant", "deja", "donc",
        "termine", "terminee", "termines", "terminees", "terminer", "fini", "finie", "finis", "finies", "finir", "fait", "faite", "faits", "faites", "acheve", "achevee",
        "clos", "close", "cloture", "cloturee", "boucle", "bouclee", "fin", "j", "ai", "on", "nous", "avons", "je", "viens", "vient", "marque", "marquer", "note",
        "noter", "considere", "indique", "passe", "passer", "mets", "met", "mettre", "le", "comme", "couleur", "colore", "colorer", "rendez", "vous", "rdv",
        "rendezvous", "reunion", "reunions", "visite", "visites", "consultation", "tous", "toutes", "tout", "jarvis", "stp", "svp", "plait", "il", "elle", "s", "te",
        "merci", "ok", "alors", "bon", "voilà", "aujourd", "hui", "demain", "hier", "matin", "soir", "apres", "midi", "jour", "journee", "h", "heure", "heures",
        "premier", "premiere", "1er", "1ere", "deuxieme", "second", "seconde", "2e", "2eme", "troisieme", "3e", "3eme", "dernier", "derniere", "client", "cliente",
        "actuel", "cours", "qui", "que", "en", "vert", "fonce", "ciel", "par", "defaut", "toujours", "marques", "marquee", "marquees", "etat", "statut",
        "terminez", "cloturer", "boucler", "deux", "trois", "quatre", "cinq", "six", "ceux", "celles", "celui",
        "retire", "retirer", "enleve", "enlever", "ote", "oter", "annule", "annuler", "supprime", "efface", "decoche", "decocher", "demarque",
        "remets", "remettre", "repasse", "repasser", "quoi", "plus", "pas", "non", "faire", "coche", "encoche", "marquage", "mention",
        "erreur", "etait", "avant", "normal", "attente", "venir", "origine", "les", "aussi", "mettre",
    ) + COULEURS.flatMap { it.split(' ') }

    /** « tous les rendez-vous », « les rendez-vous d'hier », « mes 3 rendez-vous », « … sont terminés » : tous ceux du jour visé. */
    private val PLURIEL = listOf(
        Regex("\\b(?:tous|toutes) (?:mes |les |ces |nos )?(?:\\d+ )?(?:rendez vous|rdvs?|reunions|visites|consultations)\\b"),
        Regex("\\b(?:les|mes|ces|nos|des) (?:\\d+ |deux |trois |quatre |cinq |six )?(?:rendez vous|rdvs?|reunions|visites|consultations)\\b"),
        Regex("\\b(?:sont|ont ete) (?:bien |enfin |maintenant |deja |donc |tous |toutes )?(?:termine|fini|acheve|clos|cloture|boucle|fait)e?s\\b"),
        Regex("\\b(?:marque|mets?|passe)[ -]les\\b"),
    )

    internal fun cible(texte: String, aujourdhui: LocalDate): Cible {
        val brut = DatesFr.normaliser(texte)
        val date = DatesFr.jour(brut, aujourdhui, false)
        val sansDate = (if (date != null) brut.replaceFirst(date.texte, " ") else brut).replace('/', ' ')
        val partie = DatesFr.partie(sansDate)
        val n = sansDate.replace(Regex("\\b(ce |cet |le |en )?(matin(ee)?|apres midi|aprem|soir(ee)?|midi)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        val h = HEURE.find(n)
        val sansHeure = n.replace(h?.value ?: "\u0000", " ")
        return Cible(
            jour = date?.date, heure = h?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..23 }, minute = h?.groupValues?.get(2)?.toIntOrNull(),
            partie = partie,
            mots = sansHeure.split(' ').filter { it.length >= 3 && it !in VIDES && !it.all(Char::isDigit) },
            chez = Regex("\\b(?:chez|avec) (?!moi\\b|nous\\b|la couleur\\b|du\\b|le terme\\b)").containsMatchIn(n),
            rang = RANGS.firstOrNull { (r, _) -> r.containsMatchIn(sansHeure) }?.second,
            tous = PLURIEL.any { it.containsMatchIn(n) },
        )
    }

    /** Phrase qui retire l'état terminé (« remets-le à faire », « enlève le terminé »…). */
    fun estRetrait(texte: String): Boolean {
        val n = DatesFr.normaliser(texte)
        return Regex("termin|fini|fait|a faire|coche|marquage|demarque|en cours").containsMatchIn(n) && NEG.any { it.containsMatchIn(n) } && !QUESTION.containsMatchIn(n)
    }

    /** Heure du dernier marquage « terminé » (0 s'il n'y en a pas). */
    fun dernierMarquage(store: Store): Long = lire(store).values.maxOfOrNull { it.optLong("ts") } ?: 0L

    /**
     * [dernierMarque] : heure du dernier marquage. Juste après avoir marqué un rendez-vous, « remets-le à faire » ou
     * « enlève le terminé » (sans dire « rendez-vous ») le visent aussi.
     */
    fun intention(texte: String, aujourdhui: LocalDate = LocalDate.now(), dernierMarque: Long = 0L, maintenant: Long = System.currentTimeMillis()): Intention? {
        val o = texte.trim()
        if (o.isBlank() || o.length > 240) return null
        val n = DatesFr.normaliser(o).replace(Regex("^(?:(?:jarvis|ok|alors|bon|voila|bien)\\s+)+"), "")
        if (!SUJET.containsMatchIn(n) && dernierMarque > 0 && maintenant - dernierMarque < 3 * 3_600_000L && o.length < 90 && estRetrait(o) &&
            (Regex("termin|a faire|marquage|fini|\\bfait|en cours").containsMatchIn(n) || Regex("^(?:decoche|demarque|devalide)\\w* (?:le|la|les)$").matches(n.trim())) &&
            !Regex("\\b(?:listes?|courses|taches?|alarmes?|reveils?|minuteurs?|rappels?|notes?|cases?)\\b").containsMatchIn(n))
            return Intention.Reprendre(Cible())
        if (!SUJET.containsMatchIn(n) || !Regex("termin|fini|fait|achev|clos|clotur|boucl|\\bfin\\b|comme marque|a faire|coche|marquage|demarque|(?:remets?|repasse)\\b.{0,40}\\ben cours").containsMatchIn(n)) return null
        // Réglages : « quand un rendez-vous est terminé, mets-le en gris », « la couleur seulement dans l'application ».
        val couleur = couleurDite(n)
        val google = when {
            Regex("\\b(?:seulement|uniquement|que) (?:dans|sur) (?:l application|l appli|jarvis)|\\bpas (?:dans|sur) google\\b|\\bsans (?:toucher a |modifier )?google\\b").containsMatchIn(n) -> false
            Regex("\\b(?:aussi|egalement) (?:dans|sur) google\\b|\\bsynchronise[rs]? (?:avec|dans|sur) google\\b").containsMatchIn(n) -> true
            else -> null
        }
        // Règle pour l'avenir (« quand… », « désormais… », « pour les rendez-vous terminés… ») ; « mets mes rendez-vous
        // terminés en jaune » sans mot de règle colore les rendez-vous eux-mêmes (avant : seul le réglage changeait).
        val regle = Regex("\\b(?:quand|lorsque|des que|chaque fois que|a chaque fois qu?e?)\\b|\\b(?:par defaut|desormais|dorenavant|a l avenir|a partir de maintenant)\\b|\\bcouleur des (?:rendez vous|rdv)\\b|\\bpour (?:les|mes) (?:rendez vous|rdv) termines\\b")
        val reglageGoogle = Regex("\\b(?:les|mes) (?:rendez vous|rdv) termines\\b")
        if ((couleur != null || google != null) && regle.containsMatchIn(n)) return Intention.Reglage(couleur, google)
        if (google != null && reglageGoogle.containsMatchIn(n)) return Intention.Reglage(couleur, google)
        // « mets mes rendez-vous terminés en jaune » est une action, pas une question.
        if (couleur == null && LISTER.containsMatchIn(n)) return Intention.Lister(DatesFr.jour(n, aujourdhui, false)?.date)
        if (QUESTION.containsMatchIn(n)) return null
        if (NEG.any { it.containsMatchIn(n) }) return Intention.Reprendre(cible(o, aujourdhui))
        if (FINI.none { it.containsMatchIn(n) }) return null
        return Intention.Terminer(cible(o, aujourdhui), couleur)
    }

    // ------------------------------------------------------------------ choix du rendez-vous

    sealed interface Choix {
        data class Trouve(val l: List<EventInfo>) : Choix
        data class Echec(val message: String) : Choix
    }

    private fun texte(e: EventInfo) = " " + normaliser("${e.title} ${e.location} ${e.description.take(400)}") + " "
    private fun hm(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).let { "${it.hour} h${if (it.minute > 0) "%02d".format(it.minute) else ""}" }

    /**
     * Le(s) rendez-vous visé(s). Sans précision : celui en cours, sinon le dernier commencé (pas déjà terminé) ; un nom,
     * une heure, un rang ou un jour le désignent ; « tous » : tous ceux du jour (déjà commencés, pour aujourd'hui).
     */
    internal fun choisir(evenements: List<EventInfo>, c: Cible, maintenant: Long, termines: Set<String>, reprendre: Boolean = false, zone: ZoneId = ZoneId.systemDefault()): Choix {
        val auj = Instant.ofEpochMilli(maintenant).atZone(zone).toLocalDate()
        fun jour(e: EventInfo) = Instant.ofEpochMilli(e.begin).atZone(if (e.allDay) java.time.ZoneOffset.UTC else zone).toLocalDate()
        val jourVise = c.jour ?: auj
        val quand = DatesFr.libelle(jourVise, auj)
        val tous = evenements.sortedBy { it.begin }
        var l = tous.filter { jour(it) == jourVise }
        if (c.mots.isNotEmpty()) {
            val fenetre = if (c.jour != null) l else tous.filter { jour(it) == auj || jour(it) == auj.minusDays(1) }
            val scores = fenetre.associateWith { e -> val t = texte(e); c.mots.count { m -> t.contains(" $m") } }
            val meilleur = scores.values.maxOrNull() ?: 0
            if (meilleur > 0) l = fenetre.filter { scores[it] == meilleur }.let { x -> x.filter { jour(it) == jourVise }.ifEmpty { x } }
            else if (c.chez) return Choix.Echec("Je ne trouve aucun rendez-vous « ${c.mots.joinToString(" ")} » $quand dans ton agenda.")
        }
        if (c.heure != null) {
            l = l.filter { e -> val z = Instant.ofEpochMilli(e.begin).atZone(zone); !e.allDay && z.hour == c.heure && (c.minute == null || z.minute == c.minute) }
            if (l.isEmpty()) return Choix.Echec("Aucun rendez-vous à ${c.heure} h${c.minute?.let { "%02d".format(it) }.orEmpty()} $quand.")
        } else if (c.partie != null) {
            l.filter { e -> !e.allDay && Instant.ofEpochMilli(e.begin).atZone(zone).hour in c.partie.second }.let { if (it.isNotEmpty()) l = it }
        }
        // Journée entière (congés, anniversaires…) : seulement si on la nomme.
        if (c.mots.isEmpty()) l = l.filter { !it.allDay }
        if (l.isEmpty()) return Choix.Echec("Aucun rendez-vous $quand dans ton agenda.")
        if (c.tous) return Choix.Trouve(if (jourVise == auj) l.filter { it.begin <= maintenant }.ifEmpty { l } else l)
        c.rang?.let { r -> return (if (r == -1) l.last() else l.getOrNull(r - 1))?.let { Choix.Trouve(listOf(it)) } ?: Choix.Echec("Tu n'as que ${l.size} rendez-vous $quand.") }
        if (l.size == 1) return Choix.Trouve(l)
        // Plusieurs possibles : celui en cours, sinon le dernier commencé — parmi ceux qui ne sont pas déjà dans l'état visé.
        val possibles = l.filter { (cle(it) in termines) == reprendre }.ifEmpty { return Choix.Echec(if (reprendre) "Aucun rendez-vous $quand n'est marqué terminé." else "Tes rendez-vous $quand sont déjà tous marqués terminés.") }
        possibles.firstOrNull { it.begin <= maintenant && it.end > maintenant }?.let { return Choix.Trouve(listOf(it)) }
        if (reprendre) possibles.maxByOrNull { it.begin }?.let { return Choix.Trouve(listOf(it)) }
        possibles.filter { it.begin <= maintenant }.maxByOrNull { it.begin }?.let { return Choix.Trouve(listOf(it)) }
        if (possibles.size == 1) return Choix.Trouve(possibles)
        return Choix.Echec("Lequel est terminé ? " + possibles.take(6).joinToString(" · ") { "${hm(it.begin, zone)} ${it.title}" } + " — dis par exemple « le rendez-vous de ${hm(possibles.first().begin, zone)} est terminé ».")
    }

    // ------------------------------------------------------------------ actions

    private fun evenements(app: JarvisApp, c: Cible?, aujourdhui: LocalDate): List<EventInfo> {
        val j = c?.jour ?: aujourdhui
        val debut = minOf(j, aujourdhui.minusDays(1))
        val jours = (java.time.temporal.ChronoUnit.DAYS.between(debut, maxOf(j, aujourdhui)) + 1).toInt().coerceIn(2, 370)
        return app.calendar.events(debut, jours)
    }

    /** Marque [e] terminé : couleur demandée (sinon celle des rendez-vous terminés), sans rien écrire dans l'événement. */
    fun marquer(app: JarvisApp, e: EventInfo, couleur: String?): String {
        val st = app.store
        val demandee = couleurConnue(couleur)
        val inconnue = couleur?.takeIf { it.isNotBlank() && demandee == null }
        val nom = demandee ?: couleurConnue(st.rdvTermineCouleur.value) ?: "vert"
        val c = CalendarRepo.parseColor(nom) ?: CalendarRepo.parseColor("vert")!!
        val m = JSONObject().put("id", e.id).put("b", e.begin).put("t", e.title).put("c", c).put("nom", nom).put("ts", System.currentTimeMillis())
        // Déjà marqué terminé (nouvelle couleur) : la couleur d'origine reste celle d'avant le premier marquage.
        val ancien = lire(st)[cle(e)]
        if (ancien != null) { if (ancien.has("avant")) m.put("avant", ancien.optInt("avant")); if (ancien.has("avantLocal")) m.put("avantLocal", ancien.optInt("avantLocal")) }
        else { e.eventColor?.let { m.put("avant", it) }; st.eventColor[e.id]?.let { m.put("avantLocal", it) } }
        val ou: String
        if (st.rdvTermineGoogle.value) {
            if (app.calendar.estRecurrent(e.id)) {
                val ex = app.calendar.couleurOccurrence(e, c)
                if (ex != null) { m.put("exception", ex); ou = "dans JARVIS et Google Agenda (cette occurrence seulement)" }
                else { m.put("local", true); ou = "dans JARVIS (rendez-vous récurrent : Google Agenda inchangé)" }
            } else {
                val r = app.calendar.setEventColor(e.id, c)
                if (Regex("JARVIS seulement|introuvable|manquante").containsMatchIn(r)) { m.put("local", true); ou = "dans JARVIS (Google Agenda a refusé la couleur)" }
                else ou = "dans JARVIS et Google Agenda"
            }
        } else { m.put("local", true); ou = "dans JARVIS seulement" }
        val tous = lire(st).toMutableMap()
        tous[cle(e)] = m
        m.optLong("exception").takeIf { it > 0 }?.let { tous["$it|${e.begin}"] = m }
        sauver(st, tous)
        // Mode rendez-vous en cours pour ce rendez-vous : le son revient tout de suite.
        val son = SilenceRdv.etat(app)?.takeIf { it.optLong("id") == e.id || m.optLong("exception") == it.optLong("id") }?.let {
            SilenceRdv.fin(app, null, manuel = true); " 🔔 Son rétabli."
        }.orEmpty()
        return "✅ « ${e.title} » (${hm(e.begin, ZoneId.systemDefault())}) terminé — en ${affiche(nom)} $ou.$son" +
            (inconnue?.let { " (couleur « $it » inconnue : couleur par défaut)" } ?: "")
    }

    /** Retire l'état terminé et rend au rendez-vous sa couleur d'avant. */
    fun reprendre(app: JarvisApp, e: EventInfo): String {
        val st = app.store
        val tous = lire(st).toMutableMap()
        val m = tous[cle(e)] ?: return "« ${e.title} » n'était pas marqué terminé."
        if (!m.optBoolean("local")) {
            val avant = if (m.has("avant")) m.optInt("avant") else null
            val id = m.optLong("exception").takeIf { it > 0 } ?: e.id
            runCatching { app.calendar.setEventColor(id, avant) }
        }
        st.setEventColor(e.id, if (m.has("avantLocal")) m.optInt("avantLocal") else null)
        tous.entries.removeAll { it.value.optLong("id") == m.optLong("id") && it.value.optLong("b") == m.optLong("b") }
        sauver(st, tous)
        return "↩️ « ${e.title} » n'est plus marqué terminé (couleur d'avant rétablie)."
    }

    fun executer(app: JarvisApp, i: Intention, maintenant: Long = System.currentTimeMillis()): String {
        val st = app.store
        val aujourdhui = Instant.ofEpochMilli(maintenant).atZone(ZoneId.systemDefault()).toLocalDate()
        if (i is Intention.Reglage) {
            i.couleur?.let { st.rdvTermineCouleur.set(it) }
            i.google?.let { st.rdvTermineGoogle.set(it) }
            return "🎨 Rendez-vous terminés : en ${affiche(st.rdvTermineCouleur.value)}, " +
                (if (st.rdvTermineGoogle.value) "couleur posée aussi dans Google Agenda" else "couleur gardée dans JARVIS seulement (Google Agenda inchangé)") +
                ". L'état « terminé » reste dans JARVIS, sans rien écrire dans le rendez-vous."
        }
        if (!app.calendar.hasPermission()) return "Autorise l'agenda pour que je retrouve tes rendez-vous."
        val termines = lire(st)
        return when (i) {
            is Intention.Lister -> {
                val j = i.jour ?: aujourdhui
                val l = app.calendar.events(j, 1).filter { !it.allDay }.sortedBy { it.begin }
                if (l.isEmpty()) "Aucun rendez-vous ${DatesFr.libelle(j, aujourdhui)}."
                else {
                    val (faits, reste) = l.partition { cle(it) in termines }
                    val z = ZoneId.systemDefault()
                    "# ✅ Rendez-vous ${DatesFr.libelle(j, aujourdhui)}\n" +
                        (if (faits.isEmpty()) "Aucun marqué terminé." else faits.joinToString("\n") { "- ✅ ${hm(it.begin, z)} ${it.title}" }) +
                        (if (reste.isNotEmpty()) "\n\n**Reste :**\n" + reste.joinToString("\n") { "- ${hm(it.begin, z)} ${it.title}" } else "")
                }
            }
            is Intention.Terminer -> when (val c = choisir(evenements(app, i.cible, aujourdhui), i.cible, maintenant, termines.keys)) {
                is Choix.Echec -> "📅 ${c.message}"
                is Choix.Trouve -> {
                    val r = c.l.map { e -> marquer(app, e, i.couleur) }
                    if (r.size == 1) { fr.dd.jarvis.ai.Annulation.noterRdvTermine(c.l.map { cle(it) }); r.first() }
                    else { fr.dd.jarvis.ai.Annulation.noterRdvTermine(c.l.map { cle(it) }); "✅ ${r.size} rendez-vous terminés :\n" + r.joinToString("\n") { "- " + it.removePrefix("✅ ") } }
                }
            }
            is Intention.Reprendre -> {
                val c0 = i.cible
                val marques = termines.values.distinctBy { it.optLong("id") to it.optLong("b") }
                if (marques.isEmpty()) return "Aucun rendez-vous n'est marqué terminé."
                val precis = c0.jour != null || c0.heure != null || c0.minute != null || c0.mots.isNotEmpty() || c0.rang != null || c0.tous || c0.partie != null
                // Sans précision : le DERNIER marqué terminé, quel que soit son jour. Avant, seuls les rendez-vous du jour
                // étaient cherchés : un rendez-vous d'hier (ou l'unique rendez-vous du jour, non marqué) restait « terminé ».
                if (!precis) return reprendre(app, evenementDe(app, marques.maxBy { it.optLong("ts") }))
                when (val c = choisir(evenements(app, c0, aujourdhui), c0, maintenant, termines.keys, reprendre = true)) {
                    is Choix.Echec -> "📅 ${c.message}"
                    is Choix.Trouve -> {
                        val l = c.l.filter { cle(it) in termines }
                        if (l.isEmpty()) "📅 " + c.l.joinToString(", ") { "« ${it.title} »" } + (if (c.l.size > 1) " n'étaient" else " n'était") + " pas marqué terminé."
                        else l.joinToString("\n") { reprendre(app, it) }
                    }
                }
            }
            else -> ""
        }
    }

    /** Rendez-vous correspondant à un marquage (retrouvé dans l'agenda, sinon reconstitué depuis le marquage). */
    private fun evenementDe(app: JarvisApp, m: JSONObject): EventInfo {
        val debut = m.optLong("b")
        val j = Instant.ofEpochMilli(debut).atZone(ZoneId.systemDefault()).toLocalDate()
        val ids = setOf(m.optLong("id"), m.optLong("exception"))
        return runCatching { app.calendar.events(j.minusDays(1), 3, onlyVisible = false) }.getOrDefault(emptyList())
            .firstOrNull { it.begin == debut && it.id in ids }
            ?: EventInfo(m.optLong("id"), 0, m.optString("t"), debut, debut, false, "", "", null)
    }

    /** Défait le dernier « terminé » (« annule ») : rendez-vous retrouvés par leur clé. */
    fun annuler(app: JarvisApp, cles: List<String>): Int {
        val termines = lire(app.store)
        var n = 0
        for (k in cles) {
            val m = termines[k] ?: continue
            val debut = m.optLong("b")
            val j = Instant.ofEpochMilli(debut).atZone(ZoneId.systemDefault()).toLocalDate()
            val e = runCatching { app.calendar.events(j.minusDays(1), 3) }.getOrDefault(emptyList()).firstOrNull { cle(it) == k }
                ?: EventInfo(m.optLong("id"), 0, m.optString("t"), debut, debut, false, "", "", null)
            reprendre(app, e); n++
        }
        return n
    }
}
