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
        val memoWords = if (onlyVisible) runCatching { (ctx.applicationContext as fr.dd.jarvis.JarvisApp).memo.agendaExclus() }.getOrDefault(emptyList()) else emptyList()
        val excludedByMemo = if (memoWords.isEmpty()) emptySet() else calendars().filter { c ->
            val n = (displayName(c) + " " + c.name).lowercase()
            memoWords.any { it in n }
        }.map { it.id }.toSet()
        ctx.contentResolver.query(builder.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(5) == 1
                val b = c.getLong(3)
                val e = c.getLong(4)
                val calId = c.getLong(1)
                if (onlyVisible && (calId in store.hiddenCals || calId in excludedByMemo)) continue
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

    // ------------------------------------------------------------------ couleurs (écrites dans le fournisseur → synchronisées avec Google)

    private fun meta(calId: Long): Triple<String, String, String>? {
        ctx.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calId),
            arrayOf(CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars._SYNC_ID), null, null, null
        )?.use { c -> if (c.moveToFirst()) return Triple(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "") }
        return null
    }

    private fun dist(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 255) - ((b shr 16) and 255); val dg = ((a shr 8) and 255) - ((b shr 8) and 255); val db = (a and 255) - (b and 255)
        return dr * dr + dg * dg + db * db
    }

    /** Couleur d'événement : clé de la palette Google la plus proche (synchronisée), sinon couleur libre. null = couleur de l'agenda. */
    fun setEventColor(eventId: Long, color: Int?): String {
        if (!hasPermission()) return "Permission agenda manquante."
        val calId = ctx.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events.CALENDAR_ID), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else null }
            ?: return "Événement introuvable."
        val (acc, type, _) = meta(calId) ?: return "Agenda introuvable."
        val v = ContentValues()
        var libelle = ""
        if (color == null) { v.putNull(CalendarContract.Events.EVENT_COLOR_KEY); v.putNull(CalendarContract.Events.EVENT_COLOR) }
        else {
            var key: String? = null; var best = Int.MAX_VALUE; var bestCol = color
            ctx.contentResolver.query(CalendarContract.Colors.CONTENT_URI,
                arrayOf(CalendarContract.Colors.COLOR_KEY, CalendarContract.Colors.COLOR),
                "${CalendarContract.Colors.ACCOUNT_NAME}=? AND ${CalendarContract.Colors.ACCOUNT_TYPE}=? AND ${CalendarContract.Colors.COLOR_TYPE}=?",
                arrayOf(acc, type, CalendarContract.Colors.TYPE_EVENT.toString()), null)?.use { c ->
                while (c.moveToNext()) { val d = dist(c.getInt(1), color); if (d < best) { best = d; key = c.getString(0); bestCol = c.getInt(1) } }
            }
            if (key != null) { v.put(CalendarContract.Events.EVENT_COLOR_KEY, key); libelle = " (couleur Google la plus proche)" }
            else v.put(CalendarContract.Events.EVENT_COLOR, color)
            if (key != null) store.setEventColor(eventId, null)
        }
        val ok = try { ctx.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), v, null, null) > 0 } catch (e: Exception) { false }
        if (!ok && color != null) { store.setEventColor(eventId, color); return "Couleur enregistrée dans JARVIS seulement (Google Agenda a refusé)." }
        if (color == null) store.setEventColor(eventId, null)
        return (if (color == null) "Couleur de l'agenda rétablie." else "Couleur du rendez-vous changée$libelle") + " — synchronisée avec Google Agenda."
    }

    /** Couleur d'un agenda : Google Calendar API (visible partout) + fournisseur local. */
    suspend fun setCalendarColor(cal: CalInfo, color: Int?): String {
        store.setCalColor(cal.id, null)
        if (color == null) return "Couleur d'origine rétablie (locale)."
        val (acc, type, syncId) = meta(cal.id) ?: return "Agenda introuvable."
        val local = try {
            val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, acc).appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, type).build()
            ctx.contentResolver.update(uri, ContentValues().apply { put(CalendarContract.Calendars.CALENDAR_COLOR, color) },
                "${CalendarContract.Calendars._ID}=?", arrayOf(cal.id.toString())) > 0
        } catch (e: Exception) { false }
        if (type != "com.google" || syncId.isBlank()) {
            if (!local) store.setCalColor(cal.id, color)
            return "Couleur de l'agenda changée (agenda non-Google : local)."
        }
        val err = fr.dd.jarvis.device.GmailApi.setCalendarColor(ctx, acc, syncId, color)
        if (err == null) return "Couleur de l'agenda changée sur Google Agenda."
        if (!local) store.setCalColor(cal.id, color)
        return "Couleur changée sur le téléphone seulement : $err"
    }

    companion object {
        /** « rouge », « bleu ciel », « #ff8800 »… → couleur ARGB, ou null. */
        fun parseColor(s: String): Int? {
            val t = s.trim().lowercase()
            if (t.isBlank()) return null
            if (t.startsWith("#") || t.matches(Regex("[0-9a-f]{6}"))) return runCatching { (0xFF000000L or t.removePrefix("#").toLong(16)).toInt() }.getOrNull()
            val m = mapOf(
                "rouge" to 0xD50000, "tomate" to 0xD50000, "rose" to 0xE67C73, "flamant" to 0xE67C73, "orange" to 0xF4511E, "mandarine" to 0xF4511E,
                "jaune" to 0xF6BF26, "banane" to 0xF6BF26, "vert" to 0x33B679, "sauge" to 0x33B679, "vert fonce" to 0x0B8043, "vert foncé" to 0x0B8043, "basilic" to 0x0B8043,
                "turquoise" to 0x039BE5, "paon" to 0x039BE5, "bleu ciel" to 0x039BE5, "bleu" to 0x3F51B5, "myrtille" to 0x3F51B5,
                "violet" to 0x8E24AA, "raisin" to 0x8E24AA, "lavande" to 0x7986CB, "gris" to 0x616161, "graphite" to 0x616161, "noir" to 0x212121, "blanc" to 0xFFFFFF
            )
            return m[t]?.let { 0xFF000000.toInt() or it }
        }
    }
}
