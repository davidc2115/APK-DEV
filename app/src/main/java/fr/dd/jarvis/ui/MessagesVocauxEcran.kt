package fr.dd.jarvis.ui

import android.media.MediaPlayer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.voice.MessagesVocaux
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Génération en cours conservée si l'écran est fermé, et lecteur unique. */
private object EtatMessagesVocaux {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var job: Job? = null
    var enCours by mutableStateOf(false)
    var progres by mutableStateOf("")
    var erreur by mutableStateOf("")
    var dernier by mutableStateOf<String?>(null)
    var version by mutableIntStateOf(0)

    private var lecteur: MediaPlayer? = null
    var enLecture by mutableStateOf("")

    fun jouer(f: File) {
        arreter()
        runCatching {
            lecteur = MediaPlayer().apply {
                setDataSource(f.path)
                setOnCompletionListener { arreter() }
                prepare(); start()
            }
            enLecture = f.path
        }.onFailure { erreur = "Lecture impossible : ${it.message ?: it.javaClass.simpleName}" }
    }

    fun arreter() {
        runCatching { lecteur?.stop() }; runCatching { lecteur?.release() }
        lecteur = null; enLecture = ""
    }
}

/**
 * Écran « Messages vocaux » : un message (ou un dialogue) dit par des voix de personnages — Père Noël, lutin, fée,
 * pirate… — à écouter, partager (WhatsApp, SMS…) ou enregistrer dans la musique du téléphone.
 */
@Composable
fun MessagesVocauxEcran(app: JarvisApp, idInitial: String = "", onFermer: () -> Unit) {
    val E = EtatMessagesVocaux
    val accent = LocalAccent.current
    var texte by rememberSaveable { mutableStateOf("") }
    var principalId by rememberSaveable { mutableStateOf("pere_noel") }
    var plusieurs by rememberSaveable { mutableStateOf(false) }
    var idee by rememberSaveable { mutableStateOf("") }
    var clochettes by rememberSaveable { mutableStateOf(true) }
    var effets by rememberSaveable { mutableStateOf(true) }
    var redaction by remember { mutableStateOf(false) }
    var essaiEnCours by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var aSupprimer by remember { mutableStateOf<MessagesVocaux.Message?>(null) }
    val principal = MessagesVocaux.parId(principalId) ?: MessagesVocaux.FANTAISIE.first()
    val messages = remember(E.version) { MessagesVocaux.liste(app) }

    LaunchedEffect(idInitial) { if (idInitial.isNotBlank()) { E.dernier = idInitial; E.version++ } }
    DisposableEffect(Unit) { onDispose { E.arreter() } }

    fun rolesDuTexte(): List<fr.dd.jarvis.voice.VoixNaturelle.Personnage> =
        (listOf(principal) + MessagesVocaux.scenario(texte, principal).map { it.role }).distinctBy { it.id }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🎙️ MESSAGES VOCAUX", color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            HudButton("Fermer", onClick = onFermer)
        }

        // ── 1. Voix ──
        HudPanel(Modifier.fillMaxWidth(), title = "1 · La voix") {
            listOf(MessagesVocaux.NOEL, MessagesVocaux.CONTES, MessagesVocaux.QUOTIDIEN).forEach { g ->
                Text(g, color = HudText, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Rangee {
                    MessagesVocaux.TOUS.filter { it.groupe == g }.forEach { p ->
                        HudChip(p.libelle, p.id == principalId) {
                            principalId = p.id
                            if (p.groupe == MessagesVocaux.NOEL) clochettes = true
                        }
                    }
                }
            }
            Text(principal.style.substringBefore(". ").take(220) + ".", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                HudChip(if (essaiEnCours == principal.id) "⏳ Préparation…" else "🔊 Écouter un essai") {
                    if (essaiEnCours.isNotBlank()) return@HudChip
                    essaiEnCours = principal.id
                    val r = principal
                    E.scope.launch {
                        try { E.jouer(MessagesVocaux.essai(app, r, effets)) }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { info = "⚠️ Essai impossible : ${e.message ?: e.javaClass.simpleName}" }
                        finally { essaiEnCours = "" }
                    }
                }
                if (E.enLecture.contains("essai_")) { Spacer(Modifier.size(6.dp)); HudChip("⏹ Stop") { E.arreter() } }
            }
        }

        // ── 2. Texte ──
        HudPanel(Modifier.fillMaxWidth().padding(top = 10.dp), title = "2 · Le message") {
            Rangee {
                HudChip("👤 Une voix", !plusieurs) { plusieurs = false }
                HudChip("👥 Plusieurs voix (dialogue)", plusieurs) { plusieurs = true }
            }
            if (plusieurs) {
                Text("Une réplique par ligne, au format « Père Noël : … » puis « Lutin : … ». Touche un personnage pour ajouter sa réplique :",
                    color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                Rangee {
                    MessagesVocaux.TOUS.forEach { p ->
                        HudChip("＋ ${p.libelle}") {
                            texte = (texte.trimEnd() + (if (texte.isBlank()) "" else "\n") + "${p.nom} : ")
                        }
                    }
                }
            }
            HudTextField(texte, { texte = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = "Texte du message",
                placeholder = if (plusieurs) "Père Noël : Ho ho ho ! Bonjour Léo !\nLutin : Hi hi, on a vu que tu étais très sage !"
                    else "Ho ho ho ! Bonjour Léo, c'est le Père Noël…", singleLine = false, maxLines = 12)
            Text("✍️ JARVIS peut l'écrire pour toi :", color = HudText, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            HudTextField(idee, { idee = it }, Modifier.fillMaxWidth().padding(top = 4.dp), label = "Pour qui, quel âge, quoi dire ?",
                placeholder = "ex : pour Léo, 6 ans, il a été sage, il doit ranger sa chambre, surprise sous le sapin", singleLine = false, maxLines = 4)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                HudButton(if (redaction) "Écriture…" else "✍️ Écrire le message", enabled = !redaction && idee.isNotBlank()) {
                    redaction = true; info = ""
                    val roles = if (plusieurs) rolesDuTexte().let { if (it.size < 2) it + listOfNotNull(MessagesVocaux.parId(if (principal.id == "lutin") "pere_noel" else "lutin")) else it }
                        else listOf(principal)
                    E.scope.launch {
                        try { texte = MessagesVocaux.rediger(app, idee, roles) }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { info = "⚠️ ${e.message ?: "Écriture impossible"}" }
                        finally { redaction = false }
                    }
                }
                if (redaction) { Spacer(Modifier.size(8.dp)); CircularProgressIndicator(Modifier.size(18.dp), color = accent, strokeWidth = 2.dp) }
            }
            if (info.isNotBlank()) Text(info, color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }

        // ── 3. Options et création ──
        HudPanel(Modifier.fillMaxWidth().padding(top = 10.dp), title = "3 · Options") {
            Interrupteur("🔔 Clochettes de Noël au début et à la fin", clochettes) { clochettes = it }
            Interrupteur("🎚️ Effets de voix (lutin plus aigu, Père Noël plus grave, écho de la fée…)", effets) { effets = it }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                HudButton(if (E.enCours) "Création…" else "🎙️ Créer le message vocal", filled = true, enabled = !E.enCours && texte.isNotBlank()) {
                    E.arreter()
                    E.enCours = true; E.erreur = ""; E.progres = "Préparation…"
                    val t = texte; val p = principal; val c = clochettes; val fx = effets
                    E.job = E.scope.launch {
                        try {
                            val m = MessagesVocaux.creer(app, t, p, c, fx) { s -> E.scope.launch { E.progres = s } }
                            E.dernier = m.id; E.version++
                            E.jouer(m.fichier)
                        } catch (e: CancellationException) { E.erreur = "Création annulée."
                        } catch (e: Exception) { E.erreur = e.message ?: e.javaClass.simpleName
                        } finally { E.enCours = false; E.progres = "" }
                    }
                }
                if (E.enCours) {
                    Spacer(Modifier.size(8.dp))
                    CircularProgressIndicator(Modifier.size(20.dp), color = accent, strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    HudChip("Annuler") { E.job?.cancel() }
                }
            }
            if (E.progres.isNotBlank()) Text(E.progres, color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            if (E.erreur.isNotBlank()) Text(E.erreur, color = Color(0xFFFF8A80), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (app.store.geminiKeys.none { it.isNotBlank() })
                Text("Sans clé Gemini, la voix Android est utilisée (moins expressive) : ajoute une clé dans Réglages › IA pour les vraies voix de personnages.",
                    color = HudDim, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        }

        // ── Messages ──
        Text("🗂️ MES MESSAGES (${messages.size})", color = accent, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        if (messages.isEmpty()) Text("Aucun message pour l'instant.", color = HudDim, fontSize = 12.sp)
        val ordonnes = messages.sortedByDescending { it.id == E.dernier }
        ordonnes.forEach { m ->
            CarteMessage(app, m, nouveau = m.id == E.dernier,
                onReprendre = {
                    texte = m.texte
                    m.roles.firstOrNull()?.let { principalId = it }
                    plusieurs = m.roles.size > 1
                },
                onSupprimer = { aSupprimer = m },
                onInfo = { info = it })
            Spacer(Modifier.height(8.dp))
        }
    }

    aSupprimer?.let { m ->
        AlertDialog(onDismissRequest = { aSupprimer = null }, containerColor = Color(0xFF0B1424), titleContentColor = accent, textContentColor = HudText,
            title = { Text("Supprimer « ${m.titre} » ?") },
            text = { Text("Le message est effacé de JARVIS (une copie déjà partagée ou enregistrée dans la musique reste).") },
            confirmButton = { TextButton({ if (E.enLecture == m.fichier.path) E.arreter(); MessagesVocaux.supprimer(app, m); E.version++; aSupprimer = null }) { Text("Supprimer", color = Color(0xFFFF8A80)) } },
            dismissButton = { TextButton({ aSupprimer = null }) { Text("Annuler", color = HudDim) } })
    }
}

@Composable
private fun CarteMessage(app: JarvisApp, m: MessagesVocaux.Message, nouveau: Boolean, onReprendre: () -> Unit, onSupprimer: () -> Unit, onInfo: (String) -> Unit) {
    val E = EtatMessagesVocaux
    val icones = m.roles.mapNotNull { MessagesVocaux.parId(it)?.icone }.joinToString("")
    val quand = java.time.Instant.ofEpochMilli(m.ts).atZone(java.time.ZoneId.systemDefault()).let { z ->
        fr.dd.jarvis.ai.DatesFr.libelle(z.toLocalDate(), java.time.LocalDate.now()) + " " + "%02d:%02d".format(z.hour, z.minute)
    }
    var resultat by remember(m.id) { mutableStateOf("") }
    HudPanel(Modifier.fillMaxWidth()) {
        Text((if (nouveau) "✨ " else "") + "$icones ${m.titre}", color = HudText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("${(m.dureeMs + 500) / 1000} s · $quand · ${m.source}", color = HudDim, fontSize = 10.sp)
        Text(m.texte, color = HudDim, fontSize = 11.sp, maxLines = if (nouveau) 8 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Rangee {
            if (E.enLecture == m.fichier.path) HudChip("⏹ Stop", true) { E.arreter() } else HudChip("▶️ Écouter") { E.jouer(m.fichier) }
            HudChip("📤 Partager") { runCatching { MessagesVocaux.partager(app, m) }.onFailure { onInfo("⚠️ Partage impossible : ${it.message}") } }
            HudChip("💾 Musique") {
                resultat = if (MessagesVocaux.versMusique(app, m)) "✅ Copié dans Musique › JARVIS Messages." else "⚠️ Copie impossible."
            }
            HudChip("✏️ Reprendre le texte", onClick = onReprendre)
            HudChip("🗑️", onClick = onSupprimer)
        }
        if (resultat.isNotBlank()) Text(resultat, color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Rangee(content: @Composable () -> Unit) =
    Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }

@Composable
private fun Interrupteur(texte: String, valeur: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(texte, color = HudText, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(valeur, onChange, colors = SwitchDefaults.colors(checkedTrackColor = LocalAccent.current))
    }
}
