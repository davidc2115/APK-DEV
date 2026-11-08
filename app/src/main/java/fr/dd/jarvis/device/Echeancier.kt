package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Tout ce qui est programmé, au même endroit : rappels, alarmes et minuteurs, routines, automatismes d'Hermès
 * (tâches planifiées), automatismes intégrés de Jarvis (journal, alertes de départ et météo) et travaux en cours.
 * Sert à la page « Rappels et automatisations » et à la réponse « qu'est-ce qui est programmé ? ».
 */
object Echeancier {
    enum class Etat(val titre: String) {
        EN_COURS("⏳ En cours"), A_VENIR("📅 À venir"), EVENEMENT("⚡ Sur évènement"), PAUSE("⏸️ En pause"), PASSE("🕘 Passés (7 derniers jours)")
    }

    data class Element(
        val source: String,
        val icone: String,
        val titre: String,
        val detail: String,
        /** Prochaine échéance (à venir, en cours) ou dernière exécution (passés) ; null si sur évènement. */
        val quand: Long?,
        val etat: Etat,
        /** « rappel:12 », « horloge:10005 », « routine:je_pars », « hermes:<nom> »… (actions de la page). */
        val cle: String = "",
    )

    private const val SEMAINE = 7 * 86_400_000L

    fun tout(app: JarvisApp): List<Element> {
        val now = System.currentTimeMillis()
        val l = mutableListOf<Element>()

        // 1. Rappels
        runCatching {
            Rappels.liste(app).forEach { r ->
                val passe = r.repetition.isBlank() && r.at <= now
                val rep = Rappels.REPETITIONS[r.repetition].orEmpty()
                l += Element("rappel", if (passe && r.fait) "✅" else "⏰", r.texte,
                    if (passe) "Rappel · ${if (r.fait) "fait" else "passé"}" else "Rappel · " + (if (r.repetition.isBlank()) "une fois" else rep),
                    if (passe) maxOf(r.at, r.dernier) else r.at, if (passe) Etat.PASSE else Etat.A_VENIR, "rappel:${r.id}")
            }
        }

        // 2. Alarmes et minuteurs
        runCatching {
            val noms = listOf("", "dim", "lun", "mar", "mer", "jeu", "ven", "sam")
            Horloge.elements(app).forEach { o ->
                val at = o.optLong("at")
                val id = o.optInt("id")
                if (o.optString("kind") == "timer") {
                    if (at > now) l += Element("minuteur", "⏳", o.optString("label").ifBlank { "Minuteur" }, "Minuteur · fin ${Rappels.format(at)}", at, Etat.EN_COURS, "horloge:$id")
                } else {
                    val jours = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
                    val rep = when {
                        jours.isEmpty() -> "une fois"
                        jours.size == 7 -> "tous les jours"
                        jours.sorted() == listOf(2, 3, 4, 5, 6) -> "du lundi au vendredi"
                        else -> jours.sorted().joinToString(" ") { noms.getOrElse(it) { "?" } }
                    }
                    l += Element("alarme", "🔔", o.optString("label").ifBlank { "Alarme" }, "Alarme · $rep", at, if (at > now) Etat.A_VENIR else Etat.PASSE, "horloge:$id")
                }
            }
        }

        // 3. Routines
        runCatching {
            Routines.liste(app).forEach { r ->
                val decl = Routines.DECLENCHEURS[r.declencheur] ?: r.declencheur
                val actions = "${r.actions.size} action${if (r.actions.size > 1) "s" else ""}"
                when {
                    !r.active -> l += Element("routine", "⚙️", r.nom, "Routine · $decl · désactivée", null, Etat.PAUSE, "routine:${r.id}")
                    r.declencheur == "heure" -> l += Element("routine", "⚙️", r.nom, "Routine · ${r.heure} (${Routines.JOURS[r.jours] ?: r.jours}) · $actions",
                        prochaineHeure(r.heure, r.jours), Etat.A_VENIR, "routine:${r.id}")
                    else -> l += Element("routine", "⚙️", r.nom, "Routine · $decl" +
                        (if (r.declencheur == "phrase" && r.phrases.isNotEmpty()) " « ${r.phrases.first()} »" else "") + " · $actions", null, Etat.EVENEMENT, "routine:${r.id}")
                }
            }
        }

        // 4. Automatismes d'Hermès (tâches planifiées), synchronisés par le hook toutes les 30 s
        runCatching {
            app.memo.automatismesHermes().forEach { j ->
                val nom = j.optString("nom").ifBlank { "Automatisme Hermès" }
                val derniere = date(j.optString("derniere"))
                val quandBrut = j.optString("quand")
                // Prochaine exécution : celle d'Hermès, sinon calculée depuis l'horaire (cron, « every 30m »…).
                val prochaine = date(j.optString("prochaine")) ?: prochaineHermes(quandBrut, derniere)
                val actif = j.optBoolean("actif", true)
                val statut = j.optString("statut")
                val consigne = j.optString("consigne").replace(Regex("\\s+"), " ").take(120)
                val detail = "Hermès · ${lisible(quandBrut).ifBlank { "planifié" }}" +
                    (j.optString("repetition").takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                    (if (consigne.isNotBlank()) "\n$consigne" else "")
                l += Element("hermes", "🤖", nom, detail, prochaine,
                    when { !actif -> Etat.PAUSE; prochaine != null && prochaine > now -> Etat.A_VENIR; else -> Etat.EVENEMENT }, "hermes:$nom")
                if (derniere != null && now - derniere < SEMAINE) l += Element("hermes", if (statut == "error" || j.optString("erreur").isNotBlank()) "❌" else "🤖", nom,
                    "Hermès · exécuté" + (if (statut.isNotBlank()) " ($statut)" else "") + (j.optString("erreur").takeIf { it.isNotBlank() }?.let { "\n⚠️ $it" } ?: ""),
                    derniere, Etat.PASSE, "")
            }
        }

        // 5. Automatismes intégrés de Jarvis
        runCatching {
            val s = app.store
            if (s.journalAuto.value) {
                val h = s.journalHeure.value.ifBlank { "21" }
                l += Element("jarvis", "📓", "Journal de bord", "Jarvis · chaque jour à ${h}h (note Obsidian)", prochaineHeure(if (h.contains(':') || h.contains('h')) h else "$h:00", "tous"), Etat.A_VENIR, "jarvis:journal")
            }
            if (s.departAlertes.value) l += Element("jarvis", "🚗", "Alerte de départ", "Jarvis · environ 20 min avant chaque rendez-vous avec une adresse (trafic compris)", null, Etat.EVENEMENT, "jarvis:depart")
            if (s.meteoAlertes.value) l += Element("jarvis", "🌦️", "Alertes météo", "Jarvis · la veille au soir et le matin en cas de pluie, gel ou vent fort", null, Etat.EVENEMENT, "jarvis:meteo")
            val b = runCatching { JSONObject(s.menageBilan.value) }.getOrDefault(JSONObject())
            if (b.length() > 0 && !b.optBoolean("fini")) l += Element("jarvis", "🧹", "Grand ménage des mails", GrandMenage.etat(app).lines().first(), now, Etat.EN_COURS, "jarvis:menage")
        }

        return l.sortedWith(compareBy<Element> { it.etat.ordinal }.thenBy { e -> if (e.etat == Etat.PASSE) -(e.quand ?: 0L) else e.quand ?: Long.MAX_VALUE })
    }

    // ------------------------------------------------------------------ horaires d'Hermès (cron, anglais) en français

    private val JOURS_FR = listOf("dimanche", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi")
    private val MOIS_FR = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")
    private val JOURS_EN = mapOf("sunday" to 0, "monday" to 1, "tuesday" to 2, "wednesday" to 3, "thursday" to 4, "friday" to 5, "saturday" to 6,
        "sun" to 0, "mon" to 1, "tue" to 2, "wed" to 3, "thu" to 4, "fri" to 5, "sat" to 6)
    private val CRON = Regex("^\\s*(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s*$")

    private fun hm(h: Int, m: Int) = if (m == 0) "${h}h" else "${h}h" + m.toString().padStart(2, '0')
    private fun et(l: List<String>) = if (l.size <= 1) l.joinToString() else l.dropLast(1).joinToString(", ") + " et " + l.last()

    /** Valeurs permises par un champ cron (étoile, pas « étoile/n », plages a-b et a-b/n, listes) ; null si illisible. */
    internal fun champ(f: String, min: Int, max: Int): Set<Int>? = runCatching {
        f.split(',').flatMap { part ->
            val (plage, pas) = part.split('/').let { it[0] to (it.getOrNull(1)?.toInt() ?: 1) }
            val (a, b) = when {
                plage == "*" -> min to max
                plage.contains('-') -> plage.split('-').let { it[0].toInt() to it[1].toInt() }
                else -> plage.toInt().let { v -> v to (if (part.contains('/')) max else v) }
            }
            (a..b step pas).toList()
        }.map { if (max == 7 && it == 7) 0 else it }.filter { it in min..max }.toSortedSet()
    }.getOrNull()

    /** Horaire d'Hermès en français : « 0 21 * * 0 » → « chaque dimanche à 21h », « every 30m » → « toutes les 30 min »… */
    internal fun lisible(q: String): String {
        val t = q.trim()
        if (t.isBlank()) return ""
        CRON.matchEntire(t)?.let { m ->
            val (fm, fh, fdom, fmo, fdow) = m.destructured
            val minutes = champ(fm, 0, 59) ?: return t
            val heures = champ(fh, 0, 23) ?: return t
            val dom = champ(fdom, 1, 31) ?: return t
            val mois = champ(fmo, 1, 12) ?: return t
            val dow = champ(fdow, 0, 7) ?: return t
            // Fréquences : toutes les N minutes / heures
            if (fm.startsWith("*/") && fh == "*") return "toutes les ${fm.removePrefix("*/")} min"
            if (fm == "*" && fh == "*") return "toutes les minutes"
            val heureTexte = when {
                fh == "*" -> "toutes les heures à :" + minutes.first().toString().padStart(2, '0')
                fh.startsWith("*/") -> "toutes les ${fh.removePrefix("*/")} h" + (if (minutes.first() != 0) " (à :${minutes.first().toString().padStart(2, '0')})" else "")
                else -> "à " + et(heures.flatMap { h -> minutes.map { mi -> hm(h, mi) } }.take(6))
            }
            val jours = when {
                fdom == "*" && fdow == "*" -> if (fh == "*" || fh.startsWith("*/")) "" else "tous les jours"
                fdom == "*" && dow == setOf(1, 2, 3, 4, 5) -> "du lundi au vendredi"
                fdom == "*" && dow == setOf(0, 6) -> "le week-end"
                fdom == "*" && dow.size == 1 -> "chaque ${JOURS_FR[dow.first()]}"
                fdom == "*" -> "le " + et(dow.sortedBy { (it + 6) % 7 }.map { JOURS_FR[it] })
                fdow == "*" && fmo == "*" -> "le " + et(dom.map { if (it == 1) "1er" else "$it" }) + " de chaque mois"
                fdow == "*" -> "le " + et(dom.map { if (it == 1) "1er" else "$it" }) + " " + et(mois.map { MOIS_FR[it - 1] })
                else -> "le " + et(dom.map { "$it" }) + " du mois ou le " + et(dow.map { JOURS_FR[it] })
            }
            val moisTexte = if (fmo != "*" && fdow != "*" ) " (" + et(mois.map { MOIS_FR[it - 1] }) + ")" else ""
            return listOf(jours, heureTexte).filter { it.isNotBlank() }.joinToString(" ") + moisTexte
        }
        val n = t.lowercase()
        // « every 30m », « every 2 hours », « every 1d »
        Regex("^every (\\d+)\\s*(m|min|mins|minutes?|h|hr|hrs|hours?|d|days?|w|weeks?)$").find(n)?.let { m ->
            val v = m.groupValues[1]
            return when (m.groupValues[2].first()) { 'm' -> "toutes les $v min"; 'h' -> if (v == "1") "toutes les heures" else "toutes les $v h"; 'd' -> if (v == "1") "tous les jours" else "tous les $v jours"; else -> if (v == "1") "chaque semaine" else "toutes les $v semaines" }
        }
        // « every day at 8am », « every monday at 18:30 », « every weekday at 7:15pm »
        Regex("^every (day|weekday|weekend|[a-z]+) at (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$").find(n)?.let { m ->
            var h = m.groupValues[2].toInt(); val mi = m.groupValues[3].toIntOrNull() ?: 0
            if (m.groupValues[4] == "pm" && h < 12) h += 12; if (m.groupValues[4] == "am" && h == 12) h = 0
            val quand = when (val j = m.groupValues[1]) {
                "day" -> "tous les jours"; "weekday" -> "du lundi au vendredi"; "weekend" -> "le week-end"
                else -> JOURS_EN[j]?.let { "chaque ${JOURS_FR[it]}" } ?: return t
            }
            return "$quand à ${hm(h, mi)}"
        }
        // Exécution unique : « once at 2026-11-09T09:00 », « at 2026-11-09 09:00 », date ISO seule
        Regex("^(?:once )?(?:at |on )?(\\d{4}-\\d{2}-\\d{2}[t ][\\d:.]+(?:[+-]\\d{2}:?\\d{2}|z)?)$").find(n)?.let { m ->
            date(m.groupValues[1].uppercase().replace("Z", "+00:00"))?.let { return "une fois, " + Rappels.format(it) }
        }
        return t
    }

    /** Prochaine exécution d'un automatisme d'Hermès quand le hook ne la donne pas : cron, ou « every N… » après la dernière. */
    internal fun prochaineHermes(q: String, derniere: Long?, maintenant: LocalDateTime = LocalDateTime.now()): Long? {
        val t = q.trim()
        CRON.matchEntire(t)?.let { return prochaineCron(t, maintenant)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() }
        Regex("^every (\\d+)\\s*(m|min|mins|minutes?|h|hr|hrs|hours?|d|days?)$").find(t.lowercase())?.let { m ->
            val d = derniere ?: return null
            val pas = m.groupValues[1].toLong() * when (m.groupValues[2].first()) { 'm' -> 60_000L; 'h' -> 3_600_000L; else -> 86_400_000L }
            val now = maintenant.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            var x = d + pas
            while (x <= now) x += pas
            return x
        }
        Regex("^every (day|weekday|weekend|[a-z]+) at (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$").find(t.lowercase())?.let { m ->
            var h = m.groupValues[2].toInt(); val mi = m.groupValues[3].toIntOrNull() ?: 0
            if (m.groupValues[4] == "pm" && h < 12) h += 12; if (m.groupValues[4] == "am" && h == 12) h = 0
            val dow = when (val j = m.groupValues[1]) { "day" -> "*"; "weekday" -> "1-5"; "weekend" -> "0,6"; else -> JOURS_EN[j]?.toString() ?: return null }
            return prochaineCron("$mi $h * * $dow", maintenant)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        }
        return null
    }

    /** Prochaine date (strictement après [apres]) d'une expression cron à 5 champs ; null si illisible. */
    internal fun prochaineCron(expr: String, apres: LocalDateTime): LocalDateTime? {
        val m = CRON.matchEntire(expr) ?: return null
        val (fm, fh, fdom, fmo, fdow) = m.destructured
        val minutes = champ(fm, 0, 59) ?: return null
        val heures = champ(fh, 0, 23) ?: return null
        val dom = champ(fdom, 1, 31) ?: return null
        val mois = champ(fmo, 1, 12) ?: return null
        val dow = champ(fdow, 0, 7) ?: return null
        if (minutes.isEmpty() || heures.isEmpty()) return null
        var jour = apres.toLocalDate()
        repeat(800) {
            val jdow = jour.dayOfWeek.value % 7
            val jourOk = jour.monthValue in mois && when {
                fdom != "*" && fdow != "*" -> jour.dayOfMonth in dom || jdow in dow
                fdom != "*" -> jour.dayOfMonth in dom
                fdow != "*" -> jdow in dow
                else -> true
            }
            if (jourOk) for (h in heures) for (mi in minutes) {
                val x = jour.atTime(h, mi)
                if (x.isAfter(apres)) return x
            }
            jour = jour.plusDays(1)
        }
        return null
    }

    /** Prochaine occurrence d'une heure « HH:MM » / « 7h30 » selon les jours (tous, semaine, weekend). */
    internal fun prochaineHeure(heure: String, jours: String, maintenant: LocalDateTime = LocalDateTime.now()): Long? {
        val m = Regex("(\\d{1,2})\\s*[h:]\\s*(\\d{2})?").find(heure.trim()) ?: return null
        val t = runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0) }.getOrNull() ?: return null
        var d: LocalDate = maintenant.toLocalDate()
        var n = 0
        while (n < 8 && (!d.atTime(t).isAfter(maintenant) || !Routines.joursOk(jours, d.dayOfWeek))) { d = d.plusDays(1); n++ }
        return d.atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** Date ISO (avec ou sans fuseau) ou horodatage en secondes/millisecondes → millisecondes. */
    internal fun date(v: String): Long? {
        val s = v.trim()
        if (s.isBlank() || s == "None" || s == "null") return null
        s.toDoubleOrNull()?.let { x -> return if (x > 1e12) x.toLong() else (x * 1000).toLong() }
        runCatching { return OffsetDateTime.parse(s.replace(' ', 'T')).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(s.replace(' ', 'T').take(19)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        return null
    }

    private val VOIR = Regex(
        "^\\s*(?:(?:ok|hey|dis|bon|alors)[ ,]+)?(?:jarvis|herm[eè]s)?[ ,!.]*(?:(?:est[- ]ce que )?tu (?:peux|pourrais)|peux[- ]tu)?\\s*" +
            "(?:(?:montre|affiche|ouvre|donne|liste|lis|dis)[- ]?(?:moi|nous)? (?:la liste de |la page des? |tous |toutes )?(?:mes|les|tes) " +
            "(?:rappels?|automati(?:sations?|smes?)|alarmes et rappels|t[âa]ches planifi[ée]es|programmations?)" +
            "|(?:qu'?est[- ]ce qui|qu'est ce qui|quoi) (?:est|sont) (?:programm[ée]s?|pr[ée]vus? (?:comme rappels?|en rappel))" +
            "|(?:quels|quelles) (?:sont )?(?:mes|les) (?:rappels?|automati(?:sations?|smes?))(?: (?:en cours|à venir|a venir|programm[ée]s?))*)" +
            "(?: (?:en cours|à venir|a venir|programm[ée]s?|s'il te pla[iî]t|stp))*\\s*[?.!]*$", RegexOption.IGNORE_CASE)

    /** « Montre mes rappels / mes automatisations » : ouvre la page (si l'écran est utilisé) et résume. */
    fun reflexe(app: JarvisApp, texte: String): String? {
        if (texte.length > 120 || !VOIR.matches(texte.trim())) return null
        if (!app.enVoiture) runCatching {
            app.startActivity(android.content.Intent(app, fr.dd.jarvis.MainActivity::class.java).putExtra("terrain", "rappels")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        return texte(app)
    }

    internal fun demandeVoir(texte: String) = texte.length <= 120 && VOIR.matches(texte.trim())

    /** Résumé pour le chat (outil rappels_liste, « qu'est-ce qui est programmé ? »). */
    fun texte(app: JarvisApp, inclurePasses: Boolean = false): String {
        val l = tout(app).filter { inclurePasses || it.etat != Etat.PASSE }
        if (l.isEmpty()) return "Rien de programmé pour l'instant : aucun rappel, alarme, routine ni automatisme.\n[📋 Ouvrir la page des rappels et automatisations](jarvis://ecran/rappels)"
        return "# ⏰ Rappels et automatisations\n" + l.groupBy { it.etat }.entries.joinToString("\n") { (etat, el) ->
            "\n## ${etat.titre}\n" + el.joinToString("\n") { e ->
                "- ${e.icone} **${e.titre}**" + (e.quand?.let { " — ${Rappels.format(it)}" } ?: "") + " · ${e.detail.lines().first()}"
            }
        } + "\n\n[📋 Ouvrir la page des rappels et automatisations](jarvis://ecran/rappels)"
    }
}
