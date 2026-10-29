package fr.dd.jarvis.device

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import fr.dd.jarvis.data.Store
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

data class CalInfo(val id: Long, val name: String, val account: String, val color: Int, val writable: Boolean)

data class EventInfo(
    val id: Long,
    val calId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String,
    val description: String,
    val eventColor: Int?
)

class CalendarRepo(private val ctx: Context, private val store: Store) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(): List<CalInfo> {
        if (!hasPermission()) return emptyList()
        val out = mutableListOf<CalInfo>()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, proj, null, null,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    CalInfo(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "Agenda",
                        account = c.getString(2) ?: "",
                        color = c.getInt(3),
                        writable = c.getInt(4) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                    )
                )
            }
        }
        return out
    }

    /** Nom affiché : alias choisi par l'utilisateur, sinon nom d'origine. */
    fun displayName(cal: CalInfo): String = store.calAlias[cal.id] ?: cal.name

    fun calendarColor(cal: CalInfo): Int = store.calColor[cal.id] ?: cal.color

    fun eventColor(e: EventInfo, cals: Map<Long, CalInfo>): Int =
        store.eventColor[e.id] ?: e.eventColor ?: store.calColor[e.calId] ?: cals[e.calId]?.color ?: 0xFF00E5FF.toInt()

    /** Événements des [days] jours à partir de [from] (heure locale), agendas masqués exclus si [onlyVisible]. */
    fun events(from: LocalDate, days: Int, onlyVisible: Boolean = true): List<EventInfo> {
        if (!hasPermission()) return emptyList()
        val zone = ZoneId.systemDefault()
        val startMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = from.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val wideStart = startMs - 86_400_000L
        val wideEnd = endMs + 86_400_000L
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, wideStart)
        ContentUris.appendId(builder, wideEnd)
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_COLOR
        )
        val out = mutableListOf<EventInfo>()
        ctx.contentResolver.query(builder.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(5) == 1
                val b = c.getLong(3)
                val e = c.getLong(4)
                val calId = c.getLong(1)
                if (onlyVisible && calId in store.hiddenCals) continue
                val inRange = if (allDay) {
                    // Les événements « journée entière » sont stockés en UTC : on compare les dates.
                    val d = Instant.ofEpochMilli(b).atZone(ZoneOffset.UTC).toLocalDate()
                    !d.isBefore(from) && d.isBefore(from.plusDays(days.toLong()))
                } else {
                    e > startMs && b < endMs
                }
                if (!inRange) continue
                out.add(
                    EventInfo(
                        id = c.getLong(0),
                        calId = calId,
                        title = c.getString(2) ?: "(sans titre)",
                        begin = b,
                        end = e,
                        allDay = allDay,
                        location = c.getString(6) ?: "",
                        description = c.getString(7) ?: "",
                        eventColor = if (c.isNull(8)) null else c.getInt(8)
                    )
                )
            }
        }
        return out
    }

    fun create(
        calId: Long, title: String, start: LocalDateTime, end: LocalDateTime,
        location: String = "", description: String = ""
    ): Long? {
        val zone = ZoneId.systemDefault()
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (description.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, description)
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return null
        return ContentUris.parseId(uri)
    }

    fun update(
        eventId: Long, title: String?, start: LocalDateTime?, end: LocalDateTime?,
        location: String?, description: String?
    ): Boolean {
        val zone = ZoneId.systemDefault()
        val v = ContentValues()
        title?.let { v.put(CalendarContract.Events.TITLE, it) }
        start?.let { v.put(CalendarContract.Events.DTSTART, it.atZone(zone).toInstant().toEpochMilli()) }
        end?.let { v.put(CalendarContract.Events.DTEND, it.atZone(zone).toInstant().toEpochMilli()) }
        location?.let { v.put(CalendarContract.Events.EVENT_LOCATION, it) }
        description?.let { v.put(CalendarContract.Events.DESCRIPTION, it) }
        if (v.size() == 0) return false
        return ctx.contentResolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), v, null, null
        ) > 0
    }

    fun delete(eventId: Long): Boolean =
        ctx.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null
        ) > 0

    /** Trouve un agenda par nom (alias ou nom d'origine, sans tenir compte de la casse). */
    fun findCalendar(query: String?, writableOnly: Boolean): CalInfo? {
        val all = calendars().filter { !writableOnly || it.writable }
        if (!query.isNullOrBlank()) {
            val q = query.trim().lowercase()
            all.firstOrNull { displayName(it).lowercase() == q || it.name.lowercase() == q }?.let { return it }
            all.firstOrNull { displayName(it).lowercase().contains(q) || it.name.lowercase().contains(q) }?.let { return it }
        }
        return all.firstOrNull()
    }
}
