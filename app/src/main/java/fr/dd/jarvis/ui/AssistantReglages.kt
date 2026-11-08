package fr.dd.jarvis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.Routine
import fr.dd.jarvis.device.Routines
import fr.dd.jarvis.voice.EmpreinteVocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Réglages des routines : liste, lancer, activer, modifier, créer. */
@Composable
fun RoutinesPanel(app: JarvisApp) {
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val liste = remember(version) { Routines.liste(app) }
    var edition by remember { mutableStateOf<Routine?>(null) }
    var resultat by remember { mutableStateOf("") }
    HudPanel(Modifier.fillMaxWidth(), title = "⚙️ Routines") {
        Text("Plusieurs actions d'un coup : « Jarvis, je pars » éteint la maison, lance l'itinéraire du premier client et ta musique, prévient l'équipe… Aussi en arrivant ou en quittant la maison ou un chantier, en montant en voiture, ou à heure fixe. Tu peux aussi les créer à la voix : « Jarvis, crée une routine… ».",
            color = HudDim, fontSize = 11.sp)
        liste.forEach { r ->
            Spacer(Modifier.height(8.dp))
            Text((if (r.active) "" else "⏸ ") + r.nom, color = HudText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text((Routines.DECLENCHEURS[r.declencheur] ?: r.declencheur) +
                (if (r.declencheur == "phrase") " : « ${r.phrases.joinToString("», «")} »" else "") +
                (if (r.declencheur == "heure") " ${r.heure} · ${Routines.JOURS[r.jours] ?: r.jours}" else "") + " · ${r.actions.size} action(s)",
                color = HudDim, fontSize = 11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudChip("▶ Lancer") {
                    resultat = "⏳ Routine « ${r.nom} »…"
                    scope.launch { resultat = Routines.executer(app, r) }
                }
                HudChip(if (r.active) "Active" else "Désactivée", r.active) { Routines.ajouterOuModifier(app, r.copy(active = !r.active)); version++ }
                HudChip("✏️ Modifier") { edition = r }
                HudChip("🗑") { Routines.supprimer(app, r.id); version++ }
            }
        }
        Spacer(Modifier.height(8.dp))
        HudButton("➕ Nouvelle routine", Modifier.fillMaxWidth()) { edition = Routine("", "", "phrase") }
        edition?.let { r ->
            EditeurRoutine(r, onAnnuler = { edition = null }) { nouvelle ->
                Routines.ajouterOuModifier(app, if (nouvelle.id.isBlank()) nouvelle.copy(id = Routines.nouvelId(nouvelle.nom)) else nouvelle)
                edition = null; version++
            }
        }
        if (resultat.isNotBlank()) Text(resultat, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun EditeurRoutine(r: Routine, onAnnuler: () -> Unit, onValider: (Routine) -> Unit) {
    var nom by remember(r) { mutableStateOf(r.nom) }
    var decl by remember(r) { mutableStateOf(r.declencheur) }
    var phrases by remember(r) { mutableStateOf(r.phrases.joinToString("\n")) }
    var heure by remember(r) { mutableStateOf(r.heure.ifBlank { "07:30" }) }
    var jours by remember(r) { mutableStateOf(r.jours) }
    var actions by remember(r) { mutableStateOf(r.actions.joinToString("\n")) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        HudTextField(nom, { nom = it }, Modifier.fillMaxWidth(), label = "Nom (ex. Je pars)")
        Text("Déclencheur", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Routines.DECLENCHEURS.forEach { (id, lib) -> HudChip(lib, decl == id) { decl = id } }
        }
        if (decl == "phrase") HudTextField(phrases, { phrases = it }, Modifier.fillMaxWidth().padding(top = 4.dp),
            label = "Phrases qui la lancent (une par ligne)", singleLine = false, maxLines = 4)
        if (decl == "heure") {
            HudTextField(heure, { heure = it }, Modifier.fillMaxWidth().padding(top = 4.dp), label = "Heure (HH:MM)")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Routines.JOURS.forEach { (id, lib) -> HudChip(lib, jours == id) { jours = id } }
            }
        }
        HudTextField(actions, { actions = it }, Modifier.fillMaxWidth().padding(top = 4.dp),
            label = "Actions, une par ligne (ex. Éteins les lumières)", singleLine = false, maxLines = 8)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton("Enregistrer", filled = true, enabled = nom.isNotBlank() && actions.isNotBlank()) {
                fun l(t: String) = t.lines().map { it.trim() }.filter { it.isNotBlank() }
                onValider(r.copy(nom = nom.trim(), declencheur = decl, phrases = if (decl == "phrase") l(phrases).ifEmpty { listOf(nom.trim()) } else emptyList(),
                    heure = if (decl == "heure") heure.trim() else "", jours = jours, actions = l(actions)))
            }
            HudButton("Annuler", onClick = onAnnuler)
        }
    }
}

/** Départs, météo des rendez-vous, journal de bord, montre. */
@Composable
fun AssistantProactifPanel(app: JarvisApp) {
    val store = app.store
    val scope = rememberCoroutineScope()
    var journalMsg by remember { mutableStateOf("") }
    HudPanel(Modifier.fillMaxWidth(), title = "🧭 Assistant proactif") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudChip(if (store.departAlertes.value) "🚗 Alerte de départ : oui" else "Alerte de départ : non", store.departAlertes.value) { store.departAlertes.set(!store.departAlertes.value) }
            HudChip(if (store.meteoAlertes.value) "⛈️ Météo des rendez-vous : oui" else "Météo des rendez-vous : non", store.meteoAlertes.value) { store.meteoAlertes.set(!store.meteoAlertes.value) }
        }
        Text("JARVIS calcule l'heure de départ de ton prochain rendez-vous depuis ta position et te prévient ~20 min avant (« Pars à 13 h 20 »). La veille au soir et le matin, il t'alerte si orage, fortes rafales ou forte pluie sont prévus à l'heure d'un rendez-vous, et peut te proposer un autre créneau.",
            color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        HudTextField(store.tomtomKey.value, { store.tomtomKey.set(it.trim()) }, Modifier.fillMaxWidth().padding(top = 6.dp),
            label = "Clé TomTom (facultative, gratuite) : bouchons en temps réel", password = true)
        Text("Sans clé : estimation selon les heures de pointe. Clé gratuite sur developer.tomtom.com (2 500 calculs par jour).", color = HudDim, fontSize = 11.sp)

        Spacer(Modifier.height(12.dp))
        Text("📓 Journal de bord", color = HudText, fontSize = 13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudChip(if (store.journalAuto.value) "Chaque soir : oui" else "Chaque soir : non", store.journalAuto.value) { store.journalAuto.set(!store.journalAuto.value) }
            listOf("19", "20", "21", "22", "23").forEach { h -> HudChip("${h} h", store.journalHeure.value == h) { store.journalHeure.set(h) } }
        }
        Text("Une note Obsidian « Journal/date » : rendez-vous, interventions, devis/factures/paiements, km estimés, ce que tu as demandé à JARVIS et un résumé ; le dimanche, le bilan de la semaine.",
            color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        HudButton(if (journalMsg == "…") "Écriture…" else "Écrire le journal d'aujourd'hui", Modifier.fillMaxWidth().padding(top = 4.dp), enabled = journalMsg != "…") {
            journalMsg = "…"
            scope.launch { journalMsg = runCatching { "✅ " + fr.dd.jarvis.work.Journal.ecrire(app, java.time.LocalDate.now()).first }.getOrElse { "❌ ${it.message}" } }
        }
        if (journalMsg.length > 1) Text(journalMsg, color = HudText, fontSize = 11.sp)

        Spacer(Modifier.height(12.dp))
        Text("⌚ JARVIS sur la montre", color = HudText, fontSize = 13.sp)
        HudChip(if (store.poignet.value) "✅ Conversation relayée sur la montre" else "Relais sur la montre : non", store.poignet.value) {
            fr.dd.jarvis.service.Poignet.activer(app, !store.poignet.value)
        }
        Text("La conversation « JARVIS » apparaît sur ta montre connectée : « Répondre » pour lui parler à la voix ou choisir une réponse toute faite (tes routines, prochain rendez-vous, mails, météo) ; sa réponse revient au poignet. Fonctionne avec Wear OS, Galaxy Watch et les montres qui affichent les notifications avec réponse.",
            color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Empreinte vocale : installation des modèles, enregistrement de 3 phrases, test, activation. */
@Composable
fun EmpreinteVocalePanel(app: JarvisApp) {
    val store = app.store
    val scope = rememberCoroutineScope()
    var etat by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    val enregistree = remember(version) { EmpreinteVocale.enregistree(app) }
    HudPanel(Modifier.fillMaxWidth(), title = "🗣️ Reconnaissance de ta voix") {
        Text("JARVIS apprend ta voix (3 phrases, hors ligne). Ensuite, une action sensible demandée à la voix alors que le téléphone est verrouillé (envoi, suppression, paiement…) passe seulement si c'est bien TA voix : il te fait dire « ${EmpreinteVocale.PHRASE_VERIF} ». Sinon, il faut déverrouiller. Nécessite « Empreinte avant les actions sensibles » (Centre de contrôle).",
            color = HudDim, fontSize = 11.sp)
        Text(if (enregistree) "✅ Empreinte enregistrée" else "Aucune empreinte enregistrée", color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudChip(if (enregistree) "🎙️ Réenregistrer ma voix" else "🎙️ Enregistrer ma voix") {
                if (etat.startsWith("⏳") || etat.startsWith("🎙️")) return@HudChip
                scope.launch {
                    etat = "⏳ Préparation…"
                    val r = EmpreinteVocale.installer(app) { etat = "⏳ $it" }
                    if (!r.startsWith("✅")) { etat = r; return@launch }
                    val vecteurs = mutableListOf<FloatArray>()
                    for ((i, p) in EmpreinteVocale.PHRASES.withIndex()) {
                        etat = "🎙️ Phrase ${i + 1}/3 — lis à voix haute :\n« $p »"
                        val v = withContext(Dispatchers.IO) { EmpreinteVocale.enregistrer(8000, 6000)?.let { EmpreinteVocale.vecteur(app, it) } }
                        if (v == null) { etat = "❌ Je n'ai pas entendu la phrase ${i + 1}. Recommence dans un endroit calme."; return@launch }
                        vecteurs += v
                    }
                    val seuil = EmpreinteVocale.sauver(app, vecteurs)
                    store.empreinteVocale.set(true); version++
                    etat = "✅ Voix enregistrée (seuil personnel ${"%.2f".format(seuil)}). Touche « Tester » pour vérifier."
                }
            }
            if (enregistree) HudChip("🧪 Tester") {
                scope.launch {
                    etat = "🎙️ Dis : « ${EmpreinteVocale.PHRASE_VERIF} »"
                    val r = EmpreinteVocale.verifier(app)
                    etat = (if (r.ok) "✅ " else "❌ ") + r.message + " — score ${"%.2f".format(r.score)} (seuil ${"%.2f".format(r.seuil)})"
                }
            }
            if (enregistree) HudChip(if (store.empreinteVocale.value) "Active" else "Désactivée", store.empreinteVocale.value) { store.empreinteVocale.set(!store.empreinteVocale.value) }
            if (enregistree) HudChip("🗑") { EmpreinteVocale.effacer(app); store.empreinteVocale.set(false); version++; etat = "Empreinte effacée." }
        }
        if (enregistree) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Exigence :", color = HudDim, fontSize = 11.sp)
            listOf("" to "Auto", "0.45" to "Souple", "0.55" to "Normale", "0.65" to "Stricte").forEach { (v, l) ->
                HudChip(l, store.empreinteSeuil.value == v) { store.empreinteSeuil.set(v) }
            }
        }
        if (etat.isNotBlank()) Text(etat, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).clickable { })
    }
}
