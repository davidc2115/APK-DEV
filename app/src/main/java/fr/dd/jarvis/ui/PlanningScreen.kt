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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    var editing by remember { mutableStateOf<EventInfo?>(null) }

    LaunchedEffect(day, range, refresh, store.hiddenCals.size) {
        withContext(Dispatchers.IO) {
            cals = repo.calendars()
            events = repo.events(day, range)
        }
    }

    val calMap = cals.associateBy { it.id }
    val grouped = events.groupBy { eventDay(it) }.toSortedMap()
    val dayEvents = events.filter { eventDay(it) == day }.sortedBy { it.begin }
    val nextEvent = dayEvents.firstOrNull { !it.allDay && it.end >= System.currentTimeMillis() }
    val weekStart = day.with(java.time.DayOfWeek.MONDAY)
    val weekDays = (0..6).map { weekStart.plusDays(it.toLong()) }
    var showCalendars by remember { mutableStateOf(false) }
    val activeCalendars = cals.count { it.id !in store.hiddenCals }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HudPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("JARVIS  /  ORGANISATION", color = LocalAccent.current, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text("Mon planning", color = HudText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text(day.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)).replaceFirstChar { it.uppercase() }, color = HudDim, fontSize = 13.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(day.dayOfMonth.toString().padStart(2, '0'), color = LocalAccent.current, fontSize = 34.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace)
                            Text(day.format(DateTimeFormatter.ofPattern("MMMM", Locale.FRENCH)).uppercase(Locale.FRENCH), color = HudDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        weekDays.forEach { d ->
                            val chosen = d == day
                            Column(
                                Modifier.weight(1f).clip(CircleShape).background(if (chosen) LocalAccent.current.copy(alpha = 0.20f) else Color.Transparent)
                                    .clickable { day = d; range = 1 }.padding(vertical = 9.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(d.format(DateTimeFormatter.ofPattern("EE", Locale.FRENCH)).uppercase(Locale.FRENCH), color = if (chosen) LocalAccent.current else HudDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Text(d.dayOfMonth.toString(), color = if (chosen) HudText else HudDim, fontSize = 14.sp, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
                                Box(Modifier.size(4.dp).clip(CircleShape).background(if (d == LocalDate.now()) LocalAccent.current else Color.Transparent))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        HudChip("Aujourd'hui") { day = LocalDate.now(); range = 1 }
                        HudChip("Jour", range == 1) { range = 1 }
                        HudChip("Semaine", range == 7) { range = 7 }
                        Spacer(Modifier.weight(1f))
                        HudRoundButton(Icons.Filled.ChevronLeft) { day = day.minusDays(range.toLong()) }
                        HudRoundButton(Icons.Filled.ChevronRight) { day = day.plusDays(range.toLong()) }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                HudPanel(Modifier.weight(1f)) {
                    Text("RENDEZ-VOUS", color = HudDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(dayEvents.size.toString().padStart(2, '0'), color = HudText, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("sur la journée", color = HudDim, fontSize = 10.sp)
                }
                HudPanel(Modifier.weight(1f)) {
                    Text("AGENDAS ACTIFS", color = HudDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(activeCalendars.toString().padStart(2, '0'), color = LocalAccent.current, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("sources visibles", color = HudDim, fontSize = 10.sp)
                }
                HudPanel(Modifier.weight(1f)) {
                    Text("PROCHAINE ÉTAPE", color = HudDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(nextEvent?.let { Instant.ofEpochMilli(it.begin).atZone(ZoneId.systemDefault()).format(hourFmt) } ?: "—", color = HudText, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text(if (nextEvent == null) "aucun rendez-vous" else "à venir", color = HudDim, fontSize = 10.sp)
                }
            }
        }

        item {
            HudPanel(Modifier.fillMaxWidth(), accent = LocalAccent.current) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("✦  ASSISTANT PROACTIF", color = LocalAccent.current, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(
                            when {
                                nextEvent != null -> "Prochain rendez-vous"
                                dayEvents.isEmpty() -> "Une journée encore libre"
                                else -> "Votre journée est en cours"
                            },
                            color = HudText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            when {
                                nextEvent != null -> nextEvent.title + (if (nextEvent.location.isNotBlank()) " · ${nextEvent.location}" else "")
                                dayEvents.isEmpty() -> "Aucun événement enregistré pour cette date. Jarvis pourra t'aider à organiser tes priorités."
                                else -> "${dayEvents.size} rendez-vous planifié(s). Garde un œil sur les horaires et les lieux."
                            },
                            color = HudDim, fontSize = 12.sp
                        )
                    }
                    Text("✧", color = LocalAccent.current, fontSize = 28.sp)
                }
                if (store.proactiveSummary.value.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(store.proactiveSummary.value, color = if (store.proactiveSummary.value.startsWith("⚠️")) Color(0xFFFFB45C) else LocalAccent.current, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                if (!store.agentsOn.value) {
                    Spacer(Modifier.height(8.dp))
                    Text("La veille automatique des agents est désactivée dans les réglages.", color = HudDim, fontSize = 10.sp)
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text("Veille des agents activée · alertes et suivi du planning selon tes réglages.", color = LocalAccent.current, fontSize = 10.sp)
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (range == 7) "VUE DE LA SEMAINE" else "FIL DE LA JOURNÉE", color = LocalAccent.current, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(if (range == 7) "Tes rendez-vous, dans l'ordre" else "Le déroulé de tes rendez-vous", color = HudText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                HudButton("+ Rendez-vous", icon = Icons.Filled.Add) { creating = true }
            }
        }

        if (grouped.isEmpty()) {
            item {
                HudPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("✧", color = LocalAccent.current, fontSize = 30.sp)
                        Text("Aucun événement sur cette période", color = HudText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Profite de cet espace libre pour préparer tes priorités.", color = HudDim, fontSize = 12.sp)
                        HudButton("Créer un rendez-vous", icon = Icons.Filled.Add) { creating = true }
                    }
                }
            }
        }

        grouped.forEach { (d, list) ->
            item(key = "h$d") {
                Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(d.format(dayTitleFmt).replaceFirstChar { it.uppercase() }, color = HudText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${list.size} ÉVÉNEMENT(S)", color = HudDim, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
            list.forEach { e ->
                item(key = "e${e.id}-${e.begin}") {
                    val color = Color(repo.eventColor(e, calMap))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(54.dp).padding(top = 9.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(if (e.allDay) "JOUR" else Instant.ofEpochMilli(e.begin).atZone(ZoneId.systemDefault()).format(hourFmt), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            if (!e.allDay) Text(Instant.ofEpochMilli(e.end).atZone(ZoneId.systemDefault()).format(hourFmt), color = HudDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                        Box(Modifier.padding(horizontal = 9.dp).width(3.dp).height(78.dp).clip(CircleShape).background(color.copy(alpha = 0.8f)))
                        HudPanel(Modifier.weight(1f), accent = color, onClick = { selected = e }) {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(e.title, color = HudText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                if (e.location.isNotBlank()) Text("⌖  ${e.location}", color = HudDim, fontSize = 11.sp)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(timeLabel(e), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    Spacer(Modifier.weight(1f))
                                    calMap[e.calId]?.let { Text(repo.displayName(it), color = HudDim, fontSize = 9.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            HudPanel(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SOURCES DU PLANNING", color = HudText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("$activeCalendars agenda(s) visible(s) · ${cals.size} au total", color = HudDim, fontSize = 10.sp)
                    }
                    HudChip(if (showCalendars) "Masquer" else "Afficher") { showCalendars = !showCalendars }
                }
                if (showCalendars) {
                    Spacer(Modifier.height(8.dp))
                    if (cals.isEmpty()) Text("Aucun agenda trouvé sur ce téléphone.", color = HudDim, fontSize = 12.sp)
                    cals.forEach { cal ->
                        val hidden = cal.id in store.hiddenCals
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(repo.calendarColor(cal)).copy(alpha = if (hidden) 0.25f else 1f)))
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f).clickable { store.toggleCalendar(cal.id); refresh++ }) {
                                Text(repo.displayName(cal), color = if (hidden) HudDim else HudText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(cal.account, color = HudDim, fontSize = 10.sp)
                            }
                            Icon(Icons.Filled.Edit, null, tint = LocalAccent.current, modifier = Modifier.size(19.dp).clickable { editCal = cal })
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
                    var calMsg by remember(cal.id) { mutableStateOf("") }
                    val calScope = rememberCoroutineScope()
                    ColorPalette(cal.color) { c -> if (c != null) calScope.launch { calMsg = "⏳"; calMsg = withContext(Dispatchers.IO) { repo.setCalendarColor(cal, c) }; cals = repo.calendars(); refresh++ } }
                    TextButton({ calScope.launch { calMsg = repo.setCalendarColor(cal, null); refresh++ } }) { Text("Couleur d'origine", color = LocalAccent.current) }
                    if (calMsg.isNotBlank()) Text(calMsg, color = HudDim, fontSize = 11.sp)
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
                    HudButton("✎ Modifier le rendez-vous") { editing = e; selected = null }
                    Text("Couleur du rendez-vous", color = HudDim, fontSize = 12.sp)
                    ColorPalette(store.eventColor[e.id] ?: e.eventColor) {
                        repo.setEventColor(e.id, it)
                        refresh++
                    }
                    TextButton({
                        repo.setEventColor(e.id, null)
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

    // ---- Modifier un rendez-vous
    editing?.let { e ->
        val z = ZoneId.systemDefault()
        var title by remember(e.id) { mutableStateOf(e.title) }
        var location by remember(e.id) { mutableStateOf(e.location) }
        var desc by remember(e.id) { mutableStateOf(e.description) }
        var date by remember(e.id) { mutableStateOf(Instant.ofEpochMilli(e.begin).atZone(if (e.allDay) java.time.ZoneOffset.UTC else z).toLocalDate()) }
        var start by remember(e.id) { mutableStateOf(Instant.ofEpochMilli(e.begin).atZone(z).toLocalTime().withSecond(0).withNano(0)) }
        var end by remember(e.id) { mutableStateOf(Instant.ofEpochMilli(e.end).atZone(z).toLocalTime().withSecond(0).withNano(0)) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Color(0xFF0B1424), titleContentColor = LocalAccent.current, textContentColor = HudText,
            title = { Text("Modifier le rendez-vous") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudTextField(title, { title = it }, label = "Titre")
                    HudTextField(location, { location = it }, label = "Lieu")
                    HudTextField(desc, { desc = it }, label = "Notes", singleLine = false, maxLines = 5)
                    if (!e.allDay) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudChip("📅 " + date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))) { pickDate(ctx, date) { date = it } }
                        HudChip("De " + start.format(hourFmt)) { pickTime(ctx, start) { t -> start = t; if (!end.isAfter(t)) end = t.plusHours(1) } }
                        HudChip("à " + end.format(hourFmt)) { pickTime(ctx, end) { end = it } }
                    } else Text("Événement sur la journée : date et horaires non modifiables ici.", color = HudDim, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton({
                    val ok = repo.update(
                        e.id, title.trim().ifBlank { e.title },
                        if (e.allDay) null else date.atTime(start), if (e.allDay) null else date.atTime(if (end.isAfter(start)) end else start.plusHours(1)),
                        location.trim(), desc
                    )
                    if (!ok) { /* rien de modifié */ }
                    editing = null; refresh++
                }) { Text("Enregistrer", color = LocalAccent.current) }
            },
            dismissButton = { TextButton({ editing = null }) { Text("Annuler", color = HudDim) } }
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
