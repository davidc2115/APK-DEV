package com.jarvis.assistant

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CalendarController {

    fun getTodayEvents(context: Context): String {
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val endOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        return getEventsTimeRange(context, startOfDay, endOfDay, "📅 **Événements prévus aujourd'hui**")
    }

    fun getUpcomingEvents(context: Context, days: Int = 7): String {
        val start = Calendar.getInstance().timeInMillis
        val end = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, days)
        }.timeInMillis

        return getEventsTimeRange(context, start, end, "📅 **Événements des $days prochains jours**")
    }

    /** Construit une table ID de calendrier -> "Nom (compte)" pour annoter les événements. */
    private fun buildCalendarNameMap(context: Context): Map<Long, String> {
        val map = mutableMapOf<Long, String>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: "?"
                    val account = c.getString(2) ?: ""
                    val nickname = Prefs.getCalendarNickname(context, id)
                    map[id] = nickname.ifBlank { "$name ($account)" }
                }
            }
        } catch (_: Exception) { /* table vide en cas d'erreur, pas bloquant */ }
        return map
    }

    private fun getEventsTimeRange(context: Context, startMillis: Long, endMillis: Long, title: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de lecture de l'agenda non accordée."
        }

        val calendarNames = buildCalendarNameMap(context)

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.CALENDAR_ID
        )

        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.DELETED} = 0"
        val selectionArgs = arrayOf(startMillis.toString(), endMillis.toString())

        return try {
            val cursor: Cursor? = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "$title :\n\n  aucun événement trouvé."

                val sb = StringBuilder("$title :\n\n")
                val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH)
                var idx = 0

                while (c.moveToNext()) {
                    val eventId = c.getLong(0)
                    val eventTitle = c.getString(1) ?: "Sans titre"
                    val dtStart = c.getLong(2)
                    val location = c.getString(3) ?: ""
                    val calendarId = c.getLong(5)
                    val calendarName = calendarNames[calendarId] ?: "Calendrier inconnu"
                    val timeStr = sdf.format(Date(dtStart))

                    sb.append("${idx + 1}. **$eventTitle** — $timeStr (ID: $eventId)\n")
                    sb.append("   🗓️ Calendrier : $calendarName\n")
                    if (location.isNotBlank()) sb.append("   📍 $location\n")
                    sb.append("\n")
                    idx++
                }
                sb.toString().trimEnd()
            } ?: "❌ Échec de l'accès à l'agenda."
        } catch (e: Exception) {
            "❌ Erreur lors de la lecture de l'agenda : ${e.message}"
        }
    }

    fun createEvent(
        context: Context,
        title: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        description: String = "",
        location: String = "",
        calendarRef: String? = null
    ): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de modification de l'agenda non accordée."
        }

        val calendarId = resolveCalendarId(context, calendarRef)
            ?: return "❌ Aucun calendrier disponible pour ajouter l'événement."

        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.DTSTART, startTimeMillis)
                put(CalendarContract.Events.DTEND, endTimeMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                val sdf = SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.FRENCH)
                "✅ Événement **$title** créé avec succès pour le ${sdf.format(Date(startTimeMillis))} !"
            } else {
                "❌ Impossible de créer l'événement."
            }
        } catch (e: Exception) {
            "❌ Échec de la création de l'événement : ${e.message}"
        }
    }

    fun deleteEvent(context: Context, eventId: Long): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de modification de l'agenda non accordée."
        }

        return try {
            val rows = context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString())
            )
            if (rows > 0) "🗑️ Événement supprimé." else "❌ Événement introuvable."
        } catch (e: Exception) {
            "❌ Erreur lors de la suppression : ${e.message}"
        }
    }

    /**
     * Modifie un événement existant : renommer, changer les horaires,
     * le lieu ou la description. Seuls les champs fournis (non nuls)
     * sont modifiés, les autres restent inchangés.
     */
    fun updateEvent(
        context: Context,
        eventId: Long,
        newTitle: String? = null,
        newStartTimeMillis: Long? = null,
        newEndTimeMillis: Long? = null,
        newDescription: String? = null,
        newLocation: String? = null
    ): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de modification de l'agenda non accordée."
        }

        return try {
            val values = ContentValues().apply {
                newTitle?.let { put(CalendarContract.Events.TITLE, it) }
                newStartTimeMillis?.let { put(CalendarContract.Events.DTSTART, it) }
                newEndTimeMillis?.let { put(CalendarContract.Events.DTEND, it) }
                newDescription?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                newLocation?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            }

            if (values.size() == 0) return "❌ Aucune modification à appliquer."

            val rows = context.contentResolver.update(
                CalendarContract.Events.CONTENT_URI,
                values,
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString())
            )
            if (rows > 0) "✏️ Événement mis à jour avec succès." else "❌ Événement introuvable."
        } catch (e: Exception) {
            "❌ Erreur lors de la modification : ${e.message}"
        }
    }

    fun searchEvents(context: Context, query: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de lecture de l'agenda non accordée."
        }

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.EVENT_LOCATION
        )

        return try {
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                "${CalendarContract.Events.TITLE} LIKE ? AND ${CalendarContract.Events.DELETED} = 0",
                arrayOf("%$query%"),
                "${CalendarContract.Events.DTSTART} DESC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "🔍 Aucun événement trouvé pour « $query »."

                val sb = StringBuilder("🔍 **Résultats de recherche dans l'agenda pour « $query »** :\n\n")
                val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH)
                var idx = 0

                while (c.moveToNext() && idx < 10) {
                    val eventId = c.getLong(0)
                    val title = c.getString(1) ?: "Sans titre"
                    val date = c.getLong(2)
                    val location = c.getString(3) ?: ""

                    sb.append("${idx + 1}. **$title** — ${sdf.format(Date(date))} (ID: $eventId)\n")
                    if (location.isNotBlank()) sb.append("   📍 $location\n")
                    sb.append("\n")
                    idx++
                }
                sb.toString().trimEnd()
            } ?: "❌ Échec de la recherche dans l'agenda."
        } catch (e: Exception) {
            "❌ Erreur : ${e.message}"
        }
    }

    fun getCalendarList(context: Context): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de lecture de l'agenda non accordée."
        }

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT
        )

        return try {
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                "${CalendarContract.Calendars.ACCOUNT_NAME} ASC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "📅 Aucun calendrier disponible."

                val sb = StringBuilder("📅 **Calendriers disponibles** :\n\n")
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: "Inconnu"
                    val account = c.getString(2) ?: "?"
                    val nickname = Prefs.getCalendarNickname(context, id)
                    val nicknameStr = if (nickname.isNotBlank()) " — surnom : « $nickname »" else ""
                    sb.append("• **$name** (compte : $account, ID: $id)$nicknameStr\n")
                }
                sb.append(
                    "\n💡 Pour distinguer deux calendriers similaires, donne-leur un surnom avec " +
                        "l'action name_calendar (ex : « appelle le calendrier ID 3 'Perso' »)."
                )
                sb.toString()
            } ?: "❌ Erreur lors de la récupération des calendriers."
        } catch (e: Exception) {
            "❌ Erreur : ${e.message}"
        }
    }

    /** Attribue un surnom mémorisable à un calendrier (ex: "Perso", "Boulot"), pour le distinguer facilement. */
    fun nameCalendar(context: Context, calendarId: Long, nickname: String): String {
        Prefs.saveCalendarNickname(context, calendarId, nickname)
        return "✅ Le calendrier ID $calendarId s'appellera désormais « $nickname »."
    }

    /** Résout un identifiant de calendrier à partir d'un surnom, d'un nom affiché, ou d'un ID numérique direct. */
    private fun resolveCalendarId(context: Context, calendarRef: String?): Long? {
        if (calendarRef.isNullOrBlank()) return getDefaultCalendarId(context)
        calendarRef.toLongOrNull()?.let { return it }

        val nicknameMatch = Prefs.findCalendarIdByNickname(context, calendarRef)
        if (nicknameMatch != null) return nicknameMatch

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME
        )
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: ""
                val account = c.getString(2) ?: ""
                if (name.contains(calendarRef, ignoreCase = true) || account.contains(calendarRef, ignoreCase = true)) {
                    return c.getLong(0)
                }
            }
        }
        return getDefaultCalendarId(context)
    }

    private fun getDefaultCalendarId(context: Context): Long? {
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            null,
            null,
            null
        )

        cursor?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return null
    }
}
