package com.jarvis.ai.core.agents

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.provider.CalendarContract
import com.jarvis.ai.core.phonecontrol.PermissionsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent « agenda » : lit tous les calendriers du téléphone (Google, importés...), donne des
 * surnoms aux calendriers (ex. « pro »), liste les rendez-vous, change couleur/titre/horaire.
 */
@Singleton
class CalendarAgent @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionsManager
) : Agent {
    override val name = "calendar"
    override val description =
        "Agendas du téléphone : lister les calendriers et rendez-vous, surnommer un calendrier, changer couleur/titre/horaire, créer un rendez-vous."
    override val actions = listOf(
        AgentAction("list_calendars", "{}"),
        AgentAction("set_alias", "{\"alias\":\"pro\",\"calendar_id\":11}"),
        AgentAction("list_events", "{\"date\":\"yyyy-MM-dd\",\"days\":1,\"calendar\":\"surnom ou id (optionnel)\"}"),
        AgentAction(
            "set_event_color",
            "{\"event_id\":1,\"color\":\"rouge|bleu|vert|jaune|orange|violet|rose|gris|#RRGGBB\"}",
            sensitive = true
        ),
        AgentAction(
            "update_event",
            "{\"event_id\":1,\"title\":\"...\",\"start\":\"yyyy-MM-dd HH:mm\",\"end\":\"yyyy-MM-dd HH:mm\",\"location\":\"...\"} (champs optionnels sauf event_id)",
            sensitive = true
        ),
        AgentAction(
            "create_event",
            "{\"calendar\":\"surnom ou id\",\"title\":\"...\",\"start\":\"yyyy-MM-dd HH:mm\",\"end\":\"yyyy-MM-dd HH:mm\",\"location\":\"...\"}",
            sensitive = true
        )
    )

    private val prefs = context.getSharedPreferences("elite_calendar", Context.MODE_PRIVATE)
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val localFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    override suspend fun execute(action: String, args: JSONObject): String {
        val needsWrite = action in setOf("set_event_color", "update_event", "create_event")
        val permission = if (needsWrite) Manifest.permission.WRITE_CALENDAR else Manifest.permission.READ_CALENDAR
        if (!permissions.isGranted(permission)) {
            return "❌ Permission agenda manquante (${permission.substringAfterLast('.')}) : accordez-la dans les réglages de l'application."
        }
        return when (action) {
            "list_calendars" -> listCalendars()
            "set_alias" -> setAlias(args.getString("alias"), args.getLong("calendar_id"))
            "list_events" -> listEvents(args)
            "set_event_color" -> setEventColor(args.getLong("event_id"), args.getString("color"))
            "update_event" -> updateEvent(args)
            "create_event" -> createEvent(args)
            else -> "Action inconnue : $action"
        }
    }

    // --- Surnoms de calendriers ---------------------------------------------------------

    private fun aliases(): JSONObject = JSONObject(prefs.getString(KEY_ALIASES, "{}"))

    private fun setAlias(alias: String, calendarId: Long): String {
        val a = aliases()
        val key = alias.trim().lowercase()
        // Un surnom = un calendrier ; un calendrier = un seul surnom (le dernier donné).
        a.keys().asSequence().toList().filter { a.getLong(it) == calendarId }.forEach { a.remove(it) }
        a.put(key, calendarId)
        prefs.edit().putString(KEY_ALIASES, a.toString()).apply()
        return "🏷️ Le calendrier $calendarId s'appelle désormais « $alias »."
    }

    private fun resolveCalendar(ref: String): Long? {
        val a = aliases()
        val key = ref.trim().lowercase()
        return if (a.has(key)) a.getLong(key) else ref.trim().toLongOrNull()
    }

    // --- Lecture ------------------------------------------------------------------------

    private fun listCalendars(): String {
        val a = aliases()
        val byId = a.keys().asSequence().associate { a.getLong(it) to it }
        val arr = JSONArray()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_COLOR,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                arr.put(
                    JSONObject()
                        .put("id", id)
                        .put("name", c.getString(1))
                        .put("account", c.getString(2))
                        .put("color", "#%06X".format(c.getInt(3) and 0xFFFFFF))
                        .put("writable", c.getInt(4) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
                        .put("alias", byId[id] ?: JSONObject.NULL)
                )
            }
        }
        return arr.toString()
    }

    private fun listEvents(args: JSONObject): String {
        val start = LocalDate.parse(args.getString("date")).atStartOfDay(zone)
        val days = args.optInt("days", 1).coerceIn(1, 31)
        val end = start.plusDays(days.toLong())

        val ref = args.optString("calendar", "")
        var calendarFilter: Long? = null
        if (ref.isNotBlank()) {
            calendarFilter = resolveCalendar(ref) ?: return "❌ Calendrier inconnu : $ref"
        }

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,          // 0
            CalendarContract.Instances.TITLE,             // 1
            CalendarContract.Instances.BEGIN,             // 2
            CalendarContract.Instances.END,               // 3
            CalendarContract.Instances.EVENT_LOCATION,    // 4
            CalendarContract.Instances.DESCRIPTION,       // 5
            CalendarContract.Instances.CALENDAR_ID,       // 6
            CalendarContract.Instances.ALL_DAY            // 7
        )
        data class Row(val begin: Long, val json: JSONObject)
        val rows = mutableListOf<Row>()
        CalendarContract.Instances.query(
            context.contentResolver, projection, start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli()
        )?.use { c ->
            while (c.moveToNext()) {
                if (calendarFilter != null && c.getLong(6) != calendarFilter) continue
                val allDay = c.getInt(7) == 1
                val begin = c.getLong(2)
                rows += Row(
                    begin,
                    JSONObject()
                        .put("event_id", c.getLong(0))
                        .put("calendar_id", c.getLong(6))
                        .put("title", c.getString(1).orEmpty())
                        .put("all_day", allDay)
                        .put("start", if (allDay) "journée entière" else fmt(begin))
                        .put("end", if (allDay) "" else fmt(c.getLong(3)))
                        .put("location", c.getString(4).orEmpty())
                        .put("description", cleanHtml(c.getString(5)))
                )
            }
        }
        if (rows.isEmpty()) return "[]"
        return JSONArray().also { arr -> rows.sortedBy { it.begin }.forEach { arr.put(it.json) } }.toString()
    }

    // --- Écriture -----------------------------------------------------------------------

    private fun setEventColor(eventId: Long, color: String): String {
        val argb = parseColor(color) ?: return "❌ Couleur inconnue : $color"
        val values = ContentValues().apply { put(CalendarContract.Events.EVENT_COLOR, argb) }
        val rows = context.contentResolver.update(eventUri(eventId), values, null, null)
        return if (rows > 0) "🎨 Couleur du rendez-vous $eventId changée ($color)." else "❌ Rendez-vous $eventId introuvable."
    }

    private fun updateEvent(args: JSONObject): String {
        val id = args.getLong("event_id")
        val values = ContentValues()
        if (args.has("title")) values.put(CalendarContract.Events.TITLE, args.getString("title"))
        if (args.has("location")) values.put(CalendarContract.Events.EVENT_LOCATION, args.getString("location"))
        if (args.has("start")) values.put(CalendarContract.Events.DTSTART, toMillis(args.getString("start")))
        if (args.has("end")) values.put(CalendarContract.Events.DTEND, toMillis(args.getString("end")))
        if (values.size() == 0) return "Rien à modifier."
        val rows = context.contentResolver.update(eventUri(id), values, null, null)
        return if (rows > 0) "✏️ Rendez-vous $id modifié." else "❌ Rendez-vous $id introuvable."
    }

    private fun createEvent(args: JSONObject): String {
        val calendarId = resolveCalendar(args.getString("calendar")) ?: return "❌ Calendrier inconnu."
        val startMs = toMillis(args.getString("start"))
        val endMs = toMillis(args.getString("end"))
        require(endMs > startMs) { "La fin doit être après le début." }
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, args.getString("title"))
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            if (args.has("location")) put(CalendarContract.Events.EVENT_LOCATION, args.getString("location"))
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        return if (uri != null) "📅 Rendez-vous créé (id ${ContentUris.parseId(uri)})." else "❌ Création impossible."
    }

    // --- Utilitaires --------------------------------------------------------------------

    private fun eventUri(id: Long) = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)

    private fun fmt(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).format(localFmt)

    private fun toMillis(text: String): Long =
        LocalDateTime.parse(text.trim().replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli()

    private fun cleanHtml(raw: String?): String =
        (raw ?: "")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .trim()
            .take(800)

    private fun parseColor(text: String): Int? {
        val named = mapOf(
            "rouge" to 0xD50000, "bleu" to 0x1E88E5, "vert" to 0x43A047, "jaune" to 0xFDD835,
            "orange" to 0xFB8C00, "violet" to 0x8E24AA, "rose" to 0xEC407A, "gris" to 0x757575
        )
        named[text.trim().lowercase()]?.let { return 0xFF000000.toInt() or it }
        return runCatching { Color.parseColor(text.trim()) }.getOrNull()
    }

    private companion object {
        const val KEY_ALIASES = "aliases"
    }
}
