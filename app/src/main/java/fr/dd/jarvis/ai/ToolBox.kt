package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.PhoneControl
import fr.dd.jarvis.device.ShizukuShell
import fr.dd.jarvis.societe.SocieteTools
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** [direct] = le texte est déjà mis en forme et est affiché tel quel (pas de seconde passe du cerveau). */
data class ToolResult(val text: String, val direct: Boolean = false)

class ToolBox(
    private val app: JarvisApp,
    private val confirm: suspend (String, Boolean) -> Boolean
) {
    private val societe = SocieteTools(app, confirm)
    private val store get() = app.store
    private val llm get() = app.llm

    // ------------------------------------------------------------------ schéma des outils (format OpenAI)

    private fun prop(desc: String) = JSONObject().put("type", "string").put("description", desc)

    private fun tool(name: String, desc: String, props: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()): JSONObject {
        val p = JSONObject()
        props.forEach { (k, v) -> p.put(k, v) }
        val params = JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required))
        return JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", name).put("description", desc).put("parameters", params))
    }

    val schema: JSONArray = JSONArray().apply {
        put(tool("get_planning", "Affiche le planning (agenda) mis en forme pour une période.",
            mapOf(
                "date" to prop("Date de début, yyyy-MM-dd. Par défaut aujourd'hui."),
                "days" to prop("Nombre de jours à afficher (1 à 14). Par défaut 1."),
                "calendar" to prop("Nom d'un agenda précis (optionnel)."),
                "question" to prop("Question précise de l'utilisateur sur le planning (optionnel).")
            )))
        put(tool("create_event", "Crée un rendez-vous dans l'agenda.",
            mapOf(
                "title" to prop("Titre du rendez-vous."),
                "start" to prop("Début, yyyy-MM-dd'T'HH:mm (heure locale)."),
                "end" to prop("Fin, yyyy-MM-dd'T'HH:mm. Par défaut début + 1 h."),
                "calendar" to prop("Nom de l'agenda (optionnel)."),
                "location" to prop("Lieu (optionnel)."),
                "description" to prop("Notes (optionnel).")
            ), listOf("title", "start")))
        put(tool("update_event", "Modifie un rendez-vous existant (identifiant visible dans le planning : #id).",
            mapOf(
                "event_id" to prop("Identifiant numérique de l'événement."),
                "title" to prop("Nouveau titre (optionnel)."),
                "start" to prop("Nouveau début yyyy-MM-dd'T'HH:mm (optionnel)."),
                "end" to prop("Nouvelle fin (optionnel)."),
                "location" to prop("Nouveau lieu (optionnel).")
            ), listOf("event_id")))
        put(tool("delete_event", "Supprime un rendez-vous.", mapOf("event_id" to prop("Identifiant numérique de l'événement.")), listOf("event_id")))
        put(tool("summarize_mails", "Trie et résume les derniers mails de la boîte de réception.",
            mapOf(
                "count" to prop("Nombre de mails à analyser (5 à 40). Par défaut 20."),
                "unread_only" to prop("« true » pour ne lire que les non lus.")
            )))
        put(tool("morning_briefing", "Point du jour : planning du jour et mails importants."))
        put(tool("search_contacts", "Cherche des contacts du téléphone (nom, numéro, email, société).",
            mapOf("query" to prop("Texte recherché.")), listOf("query")))
        put(tool("create_contact", "Crée un contact dans le téléphone.",
            mapOf(
                "name" to prop("Nom complet."),
                "phone" to prop("Téléphone (optionnel)."),
                "email" to prop("Email (optionnel)."),
                "company" to prop("Société (optionnel)."),
                "note" to prop("Notes (optionnel).")
            ), listOf("name")))
        put(tool("update_contact", "Modifie un contact existant (trouvé par son nom).",
            mapOf(
                "name" to prop("Nom du contact à modifier."),
                "new_name" to prop("Nouveau nom (optionnel)."),
                "phone" to prop("Nouveau téléphone (optionnel)."),
                "email" to prop("Nouvel email (optionnel)."),
                "company" to prop("Nouvelle société (optionnel)."),
                "note" to prop("Nouvelles notes (optionnel).")
            ), listOf("name")))
        put(tool("create_contact_card", "Génère la fiche contact complète (Markdown Obsidian) d'un contact et l'enregistre.",
            mapOf("name" to prop("Nom du contact.")), listOf("name")))
        put(tool("phone_action", "Agit sur le téléphone. Actions : torch_on, torch_off, open_app, open_url, volume, wifi_on, wifi_off, bluetooth_on, bluetooth_off, brightness, call, back, home, recents, notifications, quick_settings, lock, screenshot, read_screen, click_text, type_text, scroll_down, scroll_up, tap.",
            mapOf(
                "action" to prop("Nom de l'action."),
                "value" to prop("Paramètre : nom d'app, URL, pourcentage, numéro, texte à cliquer ou à saisir, ou « x,y » pour tap.")
            ), listOf("action")))
        put(tool("run_shell", "Exécute une commande shell sur le téléphone via Shizuku (réservé aux besoins que phone_action ne couvre pas).",
            mapOf("command" to prop("Commande shell.")), listOf("command")))
    }

    /** Les outils Société ne sont proposés au modèle que si la conversation les concerne (économie de contexte). */
    fun schemaFor(recentUserText: String): JSONArray {
        if (!SocieteTools.concerned(recentUserText)) return schema
        return JSONArray().also { out ->
            for (i in 0 until schema.length()) out.put(schema.get(i))
            societe.schema.forEach { out.put(it) }
        }
    }

    // ------------------------------------------------------------------ exécution

    suspend fun execute(name: String, a: JSONObject): ToolResult = when (name) {
        "get_planning" -> getPlanning(a)
        "create_event" -> createEvent(a)
        "update_event" -> updateEvent(a)
        "delete_event" -> deleteEvent(a)
        "summarize_mails" -> summarizeMails(a)
        "morning_briefing" -> ToolResult(briefing(), direct = true)
        "search_contacts" -> searchContacts(a)
        "create_contact" -> createContact(a)
        "update_contact" -> updateContact(a)
        "create_contact_card" -> createCard(a)
        "phone_action" -> phoneAction(a)
        "run_shell" -> runShell(a)
        in SocieteTools.NAMES -> societe.execute(name, a)
        else -> ToolResult("Outil inconnu : $name")
    }

    private val dayFmt = DateTimeFormatter.ofPattern("EEE dd/MM", Locale.FRENCH)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    private fun parseDateTime(s: String?): LocalDateTime? {
        if (s.isNullOrBlank()) return null
        val t = s.trim().replace(' ', 'T')
        return try {
            LocalDateTime.parse(t)
        } catch (e: Exception) {
            try {
                LocalDate.parse(t.take(10)).atTime(9, 0)
            } catch (e2: Exception) {
                null
            }
        }
    }

    /** Texte compact des événements, destiné à l'agent Planning. */
    fun planningData(from: LocalDate, days: Int, calendarFilter: String?): String {
        val cal = app.calendar
        val cals = cal.calendars().associateBy { it.id }
        val zone = ZoneId.systemDefault()
        val filterId = calendarFilter?.takeIf { it.isNotBlank() }?.let { cal.findCalendar(it, false)?.id }
        val events = cal.events(from, days).filter { filterId == null || it.calId == filterId }
        if (events.isEmpty()) return ""
        return events.joinToString("\n") { e ->
            val s = java.time.Instant.ofEpochMilli(e.begin).atZone(if (e.allDay) java.time.ZoneOffset.UTC else zone)
            val en = java.time.Instant.ofEpochMilli(e.end).atZone(if (e.allDay) java.time.ZoneOffset.UTC else zone)
            val when_ = if (e.allDay) "${s.format(dayFmt)} journée entière" else "${s.format(dayFmt)} ${s.format(timeFmt)}→${en.format(timeFmt)}"
            val calName = cals[e.calId]?.let { cal.displayName(it) } ?: "Agenda"
            "• #${e.id} | $when_ | agenda : $calName | ${e.title}" +
                (if (e.location.isNotBlank()) " | lieu : ${e.location}" else "") +
                (if (e.description.isNotBlank()) " | notes : ${e.description.replace('\n', ' ').take(400)}" else "")
        }
    }

    private suspend fun getPlanning(a: JSONObject): ToolResult {
        if (!app.calendar.hasPermission()) return ToolResult("Permission agenda manquante : l'autoriser dans l'onglet Agenda.")
        val from = try {
            a.optString("date").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it.take(10)) } ?: LocalDate.now()
        } catch (e: Exception) {
            LocalDate.now()
        }
        val days = (a.optString("days").toIntOrNull() ?: 1).coerceIn(1, 14)
        val data = planningData(from, days, a.optString("calendar"))
        if (data.isBlank()) {
            val title = from.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH))
            return ToolResult("# 📅 $title\n\nAucun événement sur cette période. 🎉", direct = true)
        }
        val q = a.optString("question")
        val user = "Aujourd'hui : ${Prompts.now()}\nPériode : du $from sur $days jour(s)\n" +
            (if (q.isNotBlank()) "Question : $q\n" else "") + "Événements :\n$data"
        return ToolResult(llm.agent(Prompts.planning(), user), direct = true)
    }

    private suspend fun createEvent(a: JSONObject): ToolResult {
        if (!app.calendar.hasPermission()) return ToolResult("Permission agenda manquante.")
        val title = a.optString("title")
        val start = parseDateTime(a.optString("start")) ?: return ToolResult("Date de début invalide.")
        val end = parseDateTime(a.optString("end")) ?: start.plusHours(1)
        val cal = app.calendar.findCalendar(a.optString("calendar"), writableOnly = true) ?: return ToolResult("Aucun agenda modifiable trouvé.")
        val id = app.calendar.create(cal.id, title, start, end, a.optString("location"), a.optString("description"))
        return if (id != null) ToolResult("Événement « $title » créé le $start dans « ${app.calendar.displayName(cal)} » (#$id).")
        else ToolResult("Échec de la création.")
    }

    private suspend fun updateEvent(a: JSONObject): ToolResult {
        val id = a.optString("event_id").trim().removePrefix("#").toLongOrNull() ?: return ToolResult("Identifiant invalide.")
        val ok = app.calendar.update(
            id,
            a.optString("title").ifBlank { null },
            parseDateTime(a.optString("start")),
            parseDateTime(a.optString("end")),
            a.optString("location").ifBlank { null },
            null
        )
        return ToolResult(if (ok) "Événement #$id modifié." else "Rien à modifier ou événement introuvable.")
    }

    private suspend fun deleteEvent(a: JSONObject): ToolResult {
        val id = a.optString("event_id").trim().removePrefix("#").toLongOrNull() ?: return ToolResult("Identifiant invalide.")
        if (!confirm("Supprimer l'événement #$id de l'agenda ?", false)) return ToolResult("Suppression refusée par l'utilisateur.")
        return ToolResult(if (app.calendar.delete(id)) "Événement #$id supprimé." else "Événement introuvable.")
    }

    private suspend fun summarizeMails(a: JSONObject): ToolResult {
        if (!store.mailConfigured()) return ToolResult("Les mails ne sont pas configurés : renseigner le serveur IMAP dans Réglages › Mails.")
        val count = (a.optString("count").toIntOrNull() ?: 20).coerceIn(5, 40)
        val unread = a.optString("unread_only").equals("true", ignoreCase = true)
        val mails = app.mail.fetchRecent(count, unread)
        if (mails.isEmpty()) return ToolResult("📭 Aucun mail ${if (unread) "non lu" else "récent"}.", direct = true)
        val text = llm.agent(Prompts.MAIL, "Aujourd'hui : ${Prompts.now()}\n\n" + app.mail.toPrompt(mails))
        return ToolResult(text, direct = true)
    }

    /** Point du jour : agenda + mails, rédigé par l'agent Briefing. */
    suspend fun briefing(): String {
        val planning = if (app.calendar.hasPermission()) {
            planningData(LocalDate.now(), 1, null).ifBlank { "Aucun événement aujourd'hui." }
        } else "Permission agenda non accordée."
        val mails = if (store.mailConfigured()) {
            try {
                val m = app.mail.fetchRecent(15, true)
                if (m.isEmpty()) "Aucun mail non lu." else app.mail.toPrompt(m)
            } catch (e: Exception) {
                "Mails indisponibles : ${e.message}"
            }
        } else "Mails non configurés."
        val user = "Utilisateur : ${store.userName.value.ifBlank { "l'utilisateur" }}\nMaintenant : ${Prompts.now()}\n\n" +
            "=== AGENDA DU JOUR ===\n$planning\n\n=== MAILS NON LUS ===\n$mails"
        return llm.agent(Prompts.briefing(), user)
    }

    private suspend fun searchContacts(a: JSONObject): ToolResult {
        if (!app.contacts.hasPermission()) return ToolResult("Permission contacts manquante : l'autoriser dans l'onglet Contacts.")
        val list = app.contacts.search(a.optString("query"), 15)
        if (list.isEmpty()) return ToolResult("Aucun contact trouvé.")
        return ToolResult(list.joinToString("\n---\n") { it.toPrompt() })
    }

    private suspend fun createContact(a: JSONObject): ToolResult {
        if (!app.contacts.hasPermission()) return ToolResult("Permission contacts manquante.")
        val name = a.optString("name")
        if (name.isBlank()) return ToolResult("Nom manquant.")
        app.contacts.create(name, a.optString("phone"), a.optString("email"), a.optString("company"), a.optString("note"))
        return ToolResult("Contact « $name » créé.")
    }

    private suspend fun updateContact(a: JSONObject): ToolResult {
        if (!app.contacts.hasPermission()) return ToolResult("Permission contacts manquante.")
        val found = app.contacts.search(a.optString("name"), 5)
        if (found.isEmpty()) return ToolResult("Contact introuvable.")
        if (found.size > 1 && found.none { it.name.equals(a.optString("name"), true) })
            return ToolResult("Plusieurs contacts correspondent : ${found.joinToString { it.name }}. Préciser le nom.")
        val c = found.firstOrNull { it.name.equals(a.optString("name"), true) } ?: found.first()
        val ok = app.contacts.update(
            c.id, a.optString("new_name").ifBlank { null }, a.optString("phone").ifBlank { null },
            a.optString("email").ifBlank { null }, a.optString("company").ifBlank { null }, a.optString("note").ifBlank { null }
        )
        return ToolResult(if (ok) "Contact « ${c.name} » mis à jour." else "Échec de la mise à jour.")
    }

    private suspend fun createCard(a: JSONObject): ToolResult {
        if (!app.contacts.hasPermission()) return ToolResult("Permission contacts manquante.")
        val found = app.contacts.search(a.optString("name"), 5)
        val c = found.firstOrNull { it.name.equals(a.optString("name"), true) } ?: found.firstOrNull()
            ?: return ToolResult("Contact introuvable.")
        return ToolResult(buildCard(c), direct = true)
    }

    /** Génère la fiche via l'agent Contacts puis l'écrit dans le dossier Obsidian. */
    suspend fun buildCard(c: fr.dd.jarvis.device.ContactInfo): String {
        val md = llm.agent(Prompts.CONTACT_CARD, "Date du jour : ${LocalDate.now()}\n\n${c.toPrompt()}")
        val where = app.obsidian.save(c.name, md)
        return "✅ Fiche « ${c.name} » enregistrée : $where\n\n---\n\n$md"
    }

    private suspend fun phoneAction(a: JSONObject): ToolResult {
        val action = a.optString("action").trim()
        val value = a.optString("value")
        if (action in PhoneControl.SENSITIVE && !confirm("Exécuter l'action « $action »" + (if (value.isNotBlank()) " ($value)" else "") + " sur le téléphone ?", false)) {
            return ToolResult("Action refusée par l'utilisateur.")
        }
        return ToolResult(app.phone.perform(action, value))
    }

    private suspend fun runShell(a: JSONObject): ToolResult {
        val cmd = a.optString("command").trim()
        if (cmd.isBlank()) return ToolResult("Commande vide.")
        if (!ShizukuShell.ready()) return ToolResult("Shizuku n'est pas prêt (démarré + autorisé). Voir Réglages › Téléphone.")
        if (!confirm("Exécuter cette commande shell ?\n\n$cmd", true)) return ToolResult("Commande refusée par l'utilisateur.")
        return ToolResult(ShizukuShell.exec(cmd))
    }
}
