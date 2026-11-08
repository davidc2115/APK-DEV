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
                val prochaine = date(j.optString("prochaine"))
                val derniere = date(j.optString("derniere"))
                val actif = j.optBoolean("actif", true)
                val statut = j.optString("statut")
                val consigne = j.optString("consigne").replace(Regex("\\s+"), " ").take(120)
                val detail = "Hermès · ${j.optString("quand").ifBlank { "planifié" }}" +
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
