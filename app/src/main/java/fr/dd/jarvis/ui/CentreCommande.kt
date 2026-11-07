package fr.dd.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** État d'une tuile du centre de commande. */
enum class EtatTuile { OK, ALERTE, INACTIF, CHARGEMENT }

data class Tuile(val icone: String, val titre: String, val valeur: String, val detail: String = "", val etat: EtatTuile = EtatTuile.OK)

/** Une tuile du centre de commande et ce qu'un toucher déclenche. */
data class TuileAction(val tuile: Tuile, val onglet: Int? = null, val demande: String? = null, val action: (() -> Unit)? = null)

/**
 * Centre de commande (onglet Accueil) : la tête du dragon, l'heure, un bouton pour parler, et des tuiles en direct —
 * agenda, météo, maison, Freebox, serveur, Hermès, mails, téléphone. Chaque tuile ouvre l'onglet concerné ou pose la
 * question à Jarvis. Les données se rafraîchissent seules (chacune à son rythme, sans bloquer les autres).
 */
@Composable
fun CentreCommande(app: JarvisApp, vm: ChatViewModel, onOnglet: (Int) -> Unit, onDemander: (String) -> Unit,
                   onRegarder: () -> Unit = {}, onCompteRendu: () -> Unit = {}) {
    val agenda by rafraichi(app, 120_000, Tuile("📅", "Agenda", "…", etat = EtatTuile.CHARGEMENT)) { tuileAgenda(app) }
    val meteo by rafraichi(app, 900_000, Tuile("🌤️", "Météo", "…", etat = EtatTuile.CHARGEMENT)) { tuileMeteo(app) }
    val maison by rafraichi(app, 120_000, Tuile("🏠", "Maison", "…", etat = EtatTuile.CHARGEMENT)) { tuileMaison(app) }
    val box by rafraichi(app, 300_000, Tuile("📶", "Freebox", "…", etat = EtatTuile.CHARGEMENT)) { tuileFreebox(app) }
    val serveur by rafraichi(app, 120_000, Tuile("🖥️", "Serveur", "…", etat = EtatTuile.CHARGEMENT)) { tuileServeur(app) }
    val hermes by rafraichi(app, 30_000, Tuile("🪽", "Hermès", "…", etat = EtatTuile.CHARGEMENT)) { tuileHermes(app) }
    val mails by rafraichi(app, 300_000, Tuile("✉️", "Mails", "…", etat = EtatTuile.CHARGEMENT)) { tuileMails(app) }
    val tel by rafraichi(app, 60_000, Tuile("📱", "Téléphone", "…", etat = EtatTuile.CHARGEMENT)) { tuileTelephone(app) }

    val tuiles = listOf(
        TuileAction(agenda, onglet = 2),
        TuileAction(meteo, demande = "Quelle météo aujourd'hui et demain ?"),
        TuileAction(maison, demande = "Fais-moi le point sur la maison (Home Assistant)."),
        TuileAction(box, demande = "Fais-moi le point sur la Freebox et son disque."),
        TuileAction(serveur, demande = "Fais un diagnostic rapide du serveur."),
        TuileAction(hermes, demande = "Quels sont mes automatismes en place ?", action = if (fr.dd.jarvis.hermes.Hermes.degrade()) ({ fr.dd.jarvis.hermes.Hermes.reprendre() }) else null),
        TuileAction(mails, onglet = 3),
        TuileAction(tel, demande = "Fais le point sur la santé du téléphone."),
    )
    val dernier = vm.messages.lastOrNull { !it.fromUser }?.text.orEmpty()
    CentreCommandeVue(
        nom = app.store.userName.value,
        ecoute = vm.listening, occupe = vm.busy, parle = vm.parle,
        statut = when { vm.listening -> "À l'écoute…"; vm.busy -> vm.status.ifBlank { "Je réfléchis…" }; else -> "" },
        enDirect = vm.enDirect, dernier = dernier, tuiles = tuiles,
        onParler = { vm.toggleListening() }, onEcrire = { onOnglet(1) },
        onTuile = { t -> t.action?.invoke() ?: t.onglet?.let(onOnglet) ?: t.demande?.let(onDemander) },
        onRaccourci = onDemander, onRegarder = onRegarder, onCompteRendu = onCompteRendu,
    )
}

/** Valeur rafraîchie périodiquement en arrière-plan (chaque tuile a sa propre boucle, avec délai maximal). */
@Composable
private fun rafraichi(app: JarvisApp, periodeMs: Long, initial: Tuile, charger: suspend () -> Tuile) =
    produceState(initial, app) {
        while (true) {
            value = withContext(Dispatchers.IO) { withTimeoutOrNull(20_000) { runCatching { charger() }.getOrNull() } }
                ?: initial.copy(valeur = "Indisponible", etat = EtatTuile.ALERTE)
            delay(periodeMs)
        }
    }

private val RACCOURCIS = listOf(
    "☀️ Prépare ma journée" to "Prépare ma journée : rendez-vous, météo, trajets, mails importants et tâches.",
    "🏠 Point maison" to "Fais-moi le point sur la maison.",
    "✉️ Mails importants" to "Quels sont mes mails importants non lus ?",
    "🧭 Ma tournée" to "Prépare ma tournée optimisée d'aujourd'hui (ordre des clients, trajets, météo et ensoleillement sur place).",
    "👷 Mes clients" to "Où en sont mes clients et mes prochaines interventions ?",
    "🌙 Bilan du jour" to "Fais-moi le bilan de ma journée et prépare demain.",
)

@Composable
fun CentreCommandeVue(
    nom: String, ecoute: Boolean, occupe: Boolean, parle: Boolean, statut: String, enDirect: String, dernier: String,
    tuiles: List<TuileAction>, onParler: () -> Unit, onEcrire: () -> Unit, onTuile: (TuileAction) -> Unit, onRaccourci: (String) -> Unit,
    heure: String? = null, onRegarder: () -> Unit = {}, onCompteRendu: () -> Unit = {},
) {
    val accent = LocalAccent.current
    val maintenant by produceState(heure ?: horloge()) { if (heure == null) while (true) { value = horloge(); delay(15_000) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp)) {
        // --- Tête du dragon, salutation, heure et bouton pour parler
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(132.dp).clickable { onParler() }, contentAlignment = Alignment.Center) {
                TeteDragonAnimee(Modifier.size(132.dp), active = ecoute || occupe, parle = parle)
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(salutation() + (if (nom.isNotBlank()) " $nom" else ""), color = HudText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(maintenant, color = accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoutonParler(ecoute, onParler)
                    HudButton("Écrire", onClick = onEcrire)
                }
            }
        }
        // --- Terrain : caméra et compte-rendu d'intervention
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HudButton("📷 Regarder", Modifier.weight(1f), onClick = onRegarder)
            HudButton("📝 Compte-rendu", Modifier.weight(1f), onClick = onCompteRendu)
        }
        // --- Ce que Jarvis fait / vient de dire
        val ligne = when {
            enDirect.isNotBlank() -> enDirect.trim().takeLast(260)
            statut.isNotBlank() -> statut
            else -> dernier.replace(Regex("[#*`>|]+"), "").replace(Regex("\\s+"), " ").trim().take(200)
        }
        if (ligne.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            HudPanel(Modifier.fillMaxWidth(), title = if (statut.isNotBlank() || enDirect.isNotBlank()) "En direct" else "Dernière réponse", onClick = onEcrire) {
                Text(ligne, color = HudText, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        // --- Tuiles en direct, deux par ligne
        Spacer(Modifier.height(12.dp))
        tuiles.chunked(2).forEach { rangee ->
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rangee.forEach { t -> TuileVue(t.tuile, Modifier.weight(1f).fillMaxHeight()) { onTuile(t) } }
                if (rangee.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
        // --- Raccourcis
        Text("RACCOURCIS", color = accent, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RACCOURCIS.forEach { (libelle, demande) -> HudChip(libelle) { onRaccourci(demande) } }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BoutonParler(ecoute: Boolean, onParler: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.clip(CircleShape).background(accent.copy(alpha = if (ecoute) 0.55f else 0.25f))
            .border(1.5.dp, accent, CircleShape).clickable { onParler() }.padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (ecoute) "■  STOP" else "🎙️  PARLER", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun TuileVue(t: Tuile, modifier: Modifier, onClick: () -> Unit) {
    val couleur = when (t.etat) { EtatTuile.OK -> Color(0xFF4CE0A0); EtatTuile.ALERTE -> Color(0xFFFFB74D); EtatTuile.INACTIF -> HudDim; EtatTuile.CHARGEMENT -> HudDim }
    HudPanel(modifier.heightIn(min = 104.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.icone, fontSize = 18.sp)
            Text(t.titre.uppercase(), color = LocalAccent.current, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 6.dp).weight(1f), maxLines = 1)
            Box(Modifier.size(8.dp).clip(CircleShape).background(couleur))
        }
        Spacer(Modifier.height(6.dp))
        Text(t.valeur, color = HudText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (t.detail.isNotBlank()) Text(t.detail, color = HudDim, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun horloge() = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM · HH:mm", Locale.FRENCH)).replaceFirstChar { it.uppercase() }

private fun salutation(): String = when (java.time.LocalTime.now().hour) { in 5..11 -> "Bonjour"; in 12..17 -> "Bon après-midi"; else -> "Bonsoir" }

// ------------------------------------------------------------------ chargement des tuiles (fil d'arrière-plan)

private fun tuileAgenda(app: JarvisApp): Tuile {
    val maintenant = System.currentTimeMillis()
    val evts = app.calendar.events(LocalDate.now(), 2).filter { it.end > maintenant }.sortedBy { it.begin }
    if (evts.isEmpty()) return Tuile("📅", "Agenda", "Rien de prévu", "aujourd'hui ni demain", EtatTuile.INACTIF)
    val f = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)
    fun quand(e: fr.dd.jarvis.device.EventInfo): String {
        val d = java.time.Instant.ofEpochMilli(e.begin).atZone(java.time.ZoneId.systemDefault())
        val jour = if (d.toLocalDate() == LocalDate.now()) "" else "demain "
        return jour + if (e.allDay) "journée" else d.format(f)
    }
    val p = evts.first()
    return Tuile("📅", "Agenda", "${quand(p)} · ${p.title}",
        (if (p.location.isNotBlank()) "📍 ${p.location}\n" else "") + evts.drop(1).take(2).joinToString("\n") { "${quand(it)} · ${it.title}" })
}

private suspend fun tuileMeteo(app: JarvisApp): Tuile {
    val (ic, t, txt) = fr.dd.jarvis.ai.Meteo.actuel(app) ?: return Tuile("🌤️", "Météo", "Position inconnue", "autorise la localisation", EtatTuile.INACTIF)
    return Tuile(ic, "Météo", t, txt)
}

private fun tuileMaison(app: JarvisApp): Tuile {
    if (!fr.dd.jarvis.ai.Serveur.haConfigure(app)) return Tuile("🏠", "Maison", "Non configurée", "Home Assistant (Réglages › Serveur)", EtatTuile.INACTIF)
    val arr = org.json.JSONArray(fr.dd.jarvis.ai.Serveur.ha(app, "GET", "/api/states"))
    val etats = (0 until arr.length()).map { arr.getJSONObject(it) }
    val lumieres = etats.count { it.optString("entity_id").startsWith("light.") && it.optString("state") == "on" }
    val indispo = etats.count { it.optString("state") == "unavailable" }
    val temp = etats.firstOrNull { it.optString("entity_id").startsWith("climate.") && it.optJSONObject("attributes")?.has("current_temperature") == true }
        ?.optJSONObject("attributes")?.optDouble("current_temperature")
    val medias = etats.count { it.optString("entity_id").startsWith("media_player.") && it.optString("state") == "playing" }
    return Tuile("🏠", "Maison", "💡 $lumieres allumée${if (lumieres > 1) "s" else ""}" + (temp?.takeIf { !it.isNaN() }?.let { " · 🌡️ ${"%.1f".format(Locale.FRENCH, it)}°" } ?: ""),
        listOfNotNull(if (medias > 0) "▶️ $medias lecture(s) en cours" else null, if (indispo > 0) "⚠️ $indispo appareil(s) injoignable(s)" else null).joinToString("\n"),
        if (indispo > 5) EtatTuile.ALERTE else EtatTuile.OK)
}

private fun tuileFreebox(app: JarvisApp): Tuile {
    val r = fr.dd.jarvis.ai.FreeboxOs.resume(app) ?: return Tuile("📶", "Freebox", "Non associée", "Réglages › Box", EtatTuile.INACTIF)
    val (premier, reste) = r.split(" · ", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    return Tuile("📶", "Freebox", premier, reste.replace(" · ", "\n"), if (premier.startsWith("🟢")) EtatTuile.OK else EtatTuile.ALERTE)
}

private fun tuileServeur(app: JarvisApp): Tuile {
    val hote = app.store.sshHost.value.trim()
    if (hote.isBlank()) return Tuile("🖥️", "Serveur", "Non configuré", "Réglages › Serveur", EtatTuile.INACTIF)
    val port = app.store.sshPort.value.toIntOrNull() ?: 22
    val debut = System.currentTimeMillis()
    val ok = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(hote, port), 2000) } }.isSuccess
    return if (ok) Tuile("🖥️", "Serveur", "🟢 Joignable", "$hote · ${System.currentTimeMillis() - debut} ms")
        else Tuile("🖥️", "Serveur", "🔴 Injoignable", "$hote:$port (hors du réseau de la maison ?)", EtatTuile.ALERTE)
}

private suspend fun tuileHermes(app: JarvisApp): Tuile {
    val mode = when (app.store.hermesMode.value) { "off" -> "Jarvis seul"; "auto" -> "Jarvis d'abord"; else -> "Hermès pour tout" }
    if (app.store.hermesMode.value == "off") return Tuile("🪽", "Hermès", "Désactivé", mode, EtatTuile.INACTIF)
    val enLigne = fr.dd.jarvis.hermes.Hermes.enLigne()
    val sature = fr.dd.jarvis.hermes.Hermes.degrade()
    return when {
        !enLigne -> Tuile("🪽", "Hermès", "🔴 Hors ligne", "Jarvis répond à sa place", EtatTuile.ALERTE)
        sature -> Tuile("🪽", "Hermès", "🟠 Saturé ${fr.dd.jarvis.hermes.Hermes.minutesSaturation()} min",
            fr.dd.jarvis.hermes.Hermes.derniereRaison.take(90).ifBlank { "quota de l'IA" } + "\nTouche pour réessayer tout de suite", EtatTuile.ALERTE)
        else -> Tuile("🪽", "Hermès", "🟢 En ligne", mode + " · voix " + if (fr.dd.jarvis.voice.VoixNaturelle.active(app)) "naturelle" else "Android")
    }
}

private suspend fun tuileMails(app: JarvisApp): Tuile {
    val l = withTimeoutOrNull(15_000) { app.mail.fetchRecent(30, unreadOnly = true) } ?: return Tuile("✉️", "Mails", "Indisponible", "", EtatTuile.ALERTE)
    if (l.isEmpty()) return Tuile("✉️", "Mails", "Aucun non lu", "", EtatTuile.INACTIF)
    return Tuile("✉️", "Mails", "${l.size}${if (l.size >= 30) "+" else ""} non lu${if (l.size > 1) "s" else ""}",
        l.take(2).joinToString("\n") { "${it.from.substringBefore('<').trim().take(22)} : ${it.subject.take(40)}" })
}

private fun tuileTelephone(app: JarvisApp): Tuile {
    val d = fr.dd.jarvis.device.PhoneHealth.dashboard(app)
    val bat = d.battery?.let { "🔋 $it %" + if (d.charging) " ⚡" else "" } ?: "🔋 ?"
    val stockage = d.storageFreeGb?.let { "💾 ${"%.0f".format(Locale.FRENCH, it)} Go libres" }.orEmpty()
    return Tuile("📱", "Téléphone", bat, listOf(stockage, d.network).filter { it.isNotBlank() }.joinToString("\n"),
        if ((d.battery ?: 100) < 20 && !d.charging) EtatTuile.ALERTE else EtatTuile.OK)
}
