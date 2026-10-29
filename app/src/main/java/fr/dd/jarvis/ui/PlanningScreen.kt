package fr.dd.jarvis.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.CalInfo
import fr.dd.jarvis.device.EventInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayTitleFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
private val hourFmt = DateTimeFormatter.ofPattern("HH:mm")

private fun pickDate(ctx: Context, initial: LocalDate, onPick: (LocalDate) -> Unit) {
    DatePickerDialog(ctx, { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}

fun pickTime(ctx: Context, initial: LocalTime, onPick: (LocalTime) -> Unit) {
    TimePickerDialog(ctx, { _, h, mi -> onPick(LocalTime.of(h, mi)) }, initial.hour, initial.minute, true).show()
}

private fun eventDay(e: EventInfo): LocalDate =
    if (e.allDay) Instant.ofEpochMilli(e.begin).atZone(ZoneOffset.UTC).toLocalDate()
    else Instant.ofEpochMilli(e.begin).atZone(ZoneId.systemDefault()).toLocalDate()

private fun timeLabel(e: EventInfo): String {
    if (e.allDay) return "JOURNÉE ENTIÈRE"
    val z = ZoneId.systemDefault()
    return Instant.ofEpochMilli(e.begin).atZone(z).format(hourFmt) + " – " + Instant.ofEpochMilli(e.end).atZone(z).format(hourFmt)
}

@Composable
fun PlanningScreen(app: JarvisApp) {
    val store = app.store
    val repo = app.calendar
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(repo.hasPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = repo.hasPermission()
    }

    if (!granted) {
        HudPanel(Modifier.padding(12.dp).fillMaxWidth(), title = "Agenda") {
            Text("JARVIS a besoin d'accéder à ton agenda pour afficher et modifier tes rendez-vous.", color = HudText, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            HudButton("Autoriser l'agenda", icon = Icons.Filled.CalendarMonth, filled = true) {
                launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            }
        }
        return
    }

    var day by remember { mutableStateOf(LocalDate.now()) }
    var range by remember { mutableIntStateOf(1) }
    var refresh by remember { mutableIntStateOf(0) }
    var cals by remember { mutableStateOf(emptyList<CalInfo>()) }
    var events by remember { mutableStateOf(emptyList<EventInfo>()) }
    var editCal by remember { mutableStateOf<CalInfo?>(null) }
    var selected by remember { mutableStateOf<EventInfo?>(null) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(day, range, refresh, store.hiddenCals.size) {
        withContext(Dispatchers.IO) {
            cals = repo.calendars()
            events = repo.events(day, range)
        }
    }

    val calMap = cals.associateBy { it.id }
    val grouped = events.groupBy { eventDay(it) }.toSortedMap()

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            HudPanel(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HudRoundButton(Icons.Filled.ChevronLeft) { day = day.minusDays(range.toLong()) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            day.format(dayTitleFmt).replaceFirstChar { it.uppercase() },
                            color = HudText, fontWeight = FontWeight.Bold, fontSize = 16.sp
                        )
                        if (range > 1) Text("→ ${day.plusDays((range - 1).toLong()).format(dayTitleFmt)}", color = HudDim, fontSize = 12.sp)
                    }
                    HudRoundButton(Icons.Filled.ChevronRight) { day = day.plusDays(range.toLong()) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudChip("Aujourd'hui") { day = LocalDate.now() }
                    HudChip("Jour", range == 1) { range = 1 }
                    HudChip("Semaine", range == 7) { range = 7 }
                    HudChip("+ RDV") { creating = true }
                }
            }
        }

        item {
            HudPanel(Modifier.fillMaxWidth(), title = "Agendas (touche pour masquer, ✎ pour renommer / colorer)") {
                if (cals.isEmpty()) Text("Aucun agenda trouvé sur ce téléphone.", color = HudDim, fontSize = 13.sp)
                cals.forEach { cal ->
                    val hidden = cal.id in store.hiddenCals
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(16.dp).clip(CircleShape)
                                .background(Color(repo.calendarColor(cal)).copy(alpha = if (hidden) 0.25f else 1f))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(
                            Modifier.weight(1f).clickable { store.toggleCalendar(cal.id) }
                        ) {
                            Text(
                                repo.displayName(cal),
                                color = if (hidden) HudDim else HudText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                            )
                            Text(cal.account, color = HudDim, fontSize = 11.sp)
                        }
                        Icon(
                            Icons.Filled.Edit, null, tint = LocalAccent.current,
                            modifier = Modifier.size(20.dp).clickable { editCal = cal }
                        )
                    }
                }
            }
        }

        if (grouped.isEmpty()) {
            item {
                HudPanel(Modifier.fillMaxWidth()) {
                    Text("🎉 Aucun événement sur cette période.", color = HudText, fontSize = 14.sp)
                }
            }
        }

        grouped.forEach { (d, list) ->
            item(key = "h$d") {
                Text(
                    "📅 " + d.format(dayTitleFmt).replaceFirstChar { it.uppercase() },
                    color = LocalAccent.current, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            list.forEach { e ->
                item(key = "e${e.id}-${e.begin}") {
                    val color = Color(repo.eventColor(e, calMap))
                    HudPanel(Modifier.fillMaxWidth(), accent = color, onClick = { selected = e }) {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                            Box(Modifier.width(5.dp).fillMaxHeight().background(color))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("🕗 " + timeLabel(e), color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                Text(e.title, color = HudText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                if (e.location.isNotBlank()) Text("📍 ${e.location}", color = HudDim, fontSize = 12.sp)
                                calMap[e.calId]?.let { Text(repo.displayName(it), color = HudDim, fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- Renommer / colorer un agenda
    editCal?.let { cal ->
        var alias by remember(cal.id) { mutableStateOf(store.calAlias[cal.id] ?: "") }
        AlertDialog(
            onDismissRequest = { editCal = null },
            containerColor = Color(0xFF0B1424), titleContentColor = LocalAccent.current, textContentColor = HudText,
            title = { Text("Agenda : ${cal.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HudTextField(alias, { alias = it }, label = "Nom affiché (laisser vide = nom d'origine)")
                    Text("Couleur", color = HudDim, fontSize = 12.sp)
                    ColorPalette(store.calColor[cal.id]) { store.setCalColor(cal.id, it) }
                    TextButton({ store.setCalColor(cal.id, null) }) { Text("Couleur d'origine", color = LocalAccent.current) }
                }
            },
            confirmButton = {
                TextButton({
                    store.setCalAlias(cal.id, alias)
                    editCal = null
                    refresh++
                }) { Text("Enregistrer", color = LocalAccent.current) }
            },
            dismissButton = { TextButton({ editCal = null }) { Text("Annuler", color = HudDim) } }
        )
    }

    // ---- Détail d'un rendez-vous : couleur, suppression
    selected?.let { e ->
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor = Color(0xFF0B1424), titleContentColor = LocalAccent.current, textContentColor = HudText,
            title = { Text(e.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🕗 " + eventDay(e).format(dayTitleFmt) + " · " + timeLabel(e), fontSize = 13.sp)
                    if (e.location.isNotBlank()) {
                        Text(
                            "📍 ${e.location}", color = LocalAccent.current, fontSize = 13.sp,
                            modifier = Modifier.clickable {
                                openLink(ctx, "https://www.google.com/maps/search/?api=1&query=" + java.net.URLEncoder.encode(e.location, "UTF-8"))
                            }
                        )
                    }
                    if (e.description.isNotBlank()) Text(e.description.take(600), color = HudDim, fontSize = 12.sp)
                    Text("Couleur du rendez-vous", color = HudDim, fontSize = 12.sp)
                    ColorPalette(store.eventColor[e.id]) {
                        store.setEventColor(e.id, it)
                        refresh++
                    }
                    TextButton({
                        store.setEventColor(e.id, null)
                        refresh++
                    }) { Text("Couleur de l'agenda", color = LocalAccent.current) }
                }
            },
            confirmButton = { TextButton({ selected = null }) { Text("Fermer", color = LocalAccent.current) } },
            dismissButton = {
                TextButton({
                    repo.delete(e.id)
                    selected = null
                    refresh++
                }) { Text("Supprimer", color = Color(0xFFFF4D6D)) }
            }
        )
    }

    // ---- Nouveau rendez-vous
    if (creating) {
        val writable = cals.filter { it.writable }
        var title by remember { mutableStateOf("") }
        var location by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(day) }
        var start by remember { mutableStateOf(LocalTime.of(9, 0)) }
        var end by remember { mutableStateOf(LocalTime.of(10, 0)) }
        var calId by remember { mutableStateOf(writable.firstOrNull()?.id ?: -1L) }
        AlertDialog(
            onDismissRequest = { creating = false },
            containerColor = Color(0xFF0B1424), titleContentColor = LocalAccent.current, textContentColor = HudText,
            title = { Text("Nouveau rendez-vous") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudTextField(title, { title = it }, label = "Titre")
                    HudTextField(location, { location = it }, label = "Lieu (optionnel)")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudChip("📅 " + date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))) { pickDate(ctx, date) { date = it } }
                        HudChip("De " + start.format(hourFmt)) { pickTime(ctx, start) { t -> start = t; if (!end.isAfter(t)) end = t.plusHours(1) } }
                        HudChip("à " + end.format(hourFmt)) { pickTime(ctx, end) { end = it } }
                    }
                    Text("Agenda", color = HudDim, fontSize = 12.sp)
                    writable.forEach { c ->
                        HudChip(repo.displayName(c), calId == c.id) { calId = c.id }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    if (title.isNotBlank() && calId >= 0) {
                        repo.create(calId, title.trim(), date.atTime(start), date.atTime(if (end.isAfter(start)) end else start.plusHours(1)), location.trim())
                        creating = false
                        refresh++
                    }
                }) { Text("Créer", color = LocalAccent.current) }
            },
            dismissButton = { TextButton({ creating = false }) { Text("Annuler", color = HudDim) } }
        )
    }
}
