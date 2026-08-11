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

    private fun getEventsTimeRange(context: Context, startMillis: Long, endMillis: Long, title: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de lecture de l'agenda non accordée."
        }

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION
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
                    val timeStr = sdf.format(Date(dtStart))

                    sb.append("${idx + 1}. **$eventTitle** — $timeStr (ID: $eventId)\n")
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
        location: String = ""
    ): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de modification de l'agenda non accordée."
        }

        val calendarId = getDefaultCalendarId(context)
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
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        )

        return try {
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            )

            cursor?.use { c ->
                if (c.count == 0) return "📅 Aucun calendrier disponible."

                val sb = StringBuilder("📅 **Calendriers disponibles** :\n\n")
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: "Inconnu"
                    sb.append("• **$name** (ID: $id)\n")
                }
                sb.toString()
            } ?: "❌ Erreur lors de la récupération des calendriers."
        } catch (e: Exception) {
            "❌ Erreur : ${e.message}"
        }
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
