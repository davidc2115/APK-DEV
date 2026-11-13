package fr.dd.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp

/** Onglet des Réglages : icône, nom court, ce qu'on y trouve, sous-onglets (identifiant → nom). */
data class OngletReglages(val id: String, val icone: String, val nom: String, val aide: String, val sous: List<Pair<String, String>>)

/** Organisation des Réglages : 10 onglets, chacun découpé en sous-onglets (un sujet par page). */
val ONGLETS_REGLAGES = listOf(
    OngletReglages("general", "⚙️", "Général", "Apparence, modes de fonctionnement, sonnerie des rappels et autorisations Android.",
        listOf("apparence" to "🎨 Apparence", "batterie" to "⚡ Fonctionnement", "rappels" to "⏰ Rappels", "autorisations" to "🔐 Autorisations")),
    OngletReglages("ia", "🧠", "IA", "Clés et modèles d'IA, Hermès, mémoire, images et vidéos.",
        listOf("cles" to "🔑 Clés & modèles", "hermes" to "🪽 Hermès", "extensions" to "🧩 Extensions", "memoire" to "🧠 Mémoire", "medias" to "🖼️ Images & vidéo")),
    OngletReglages("voix", "🎙️", "Voix", "Écoute « Jarvis », conversation à la voix, voix de JARVIS, annonces.",
        listOf("ecoute" to "👂 Écoute « Jarvis »", "conversation" to "💬 Conversation", "voix" to "🗣️ Voix", "annonces" to "📣 Annonces")),
    OngletReglages("telephone", "📱", "Téléphone", "Contrôle du téléphone, applications par défaut, Android Auto.",
        listOf("controle" to "🕹️ Contrôle", "applis" to "📲 Applis par défaut", "auto" to "🚗 Android Auto")),
    OngletReglages("comptes", "💬", "Comptes", "Mails, Obsidian, Discord, TikTok.",
        listOf("mails" to "✉️ Mails", "obsidian" to "📓 Obsidian", "discord" to "🎮 Discord", "tiktok" to "🎬 TikTok")),
    OngletReglages("maison", "🏠", "Maison", "Serveur et Home Assistant, box Internet, imprimante.",
        listOf("serveur" to "🖥️ Serveur & domotique", "box" to "📶 Box Internet", "imprimante" to "🖨️ Imprimante")),
    OngletReglages("agents", "🤖", "Agents", "Agents autonomes, routines, assistant proactif, modes automatiques.",
        listOf("agents" to "🤖 Agents autonomes", "routines" to "⚙️ Routines", "proactif" to "🌅 Assistant proactif", "modes" to "🌙 Modes", "metier" to "☀️ Métier PV")),
    OngletReglages("securite", "🛡️", "Sécurité", "Systèmes de sécurité, anti-vol, empreinte vocale, confirmations, coffre-fort de la société.",
        listOf("systemes" to "🛡️ Systèmes", "antivol" to "🚨 Anti-vol", "empreinte" to "🎤 Empreinte vocale", "confirmations" to "🔒 Confirmations", "coffre" to "🗄️ Coffre-fort")),
    OngletReglages("diagnostic", "📊", "Diagnostic", "État du téléphone et des moteurs d'IA.",
        listOf("telephone" to "📱 Téléphone", "moteurs" to "🧠 Moteurs IA")),
    OngletReglages("fichiers", "📁", "Fichiers", "Bibliothèque des fichiers créés et reçus par JARVIS.", emptyList()),
)

/** Un panneau de réglages, rangé dans un onglet / sous-onglet, avec des mots-clés pour la recherche. */
class VoletReglages(val onglet: String, val sous: String, val titre: String, val mots: String, val contenu: @Composable () -> Unit) {
    private val texte by lazy {
        val o = ONGLETS_REGLAGES.firstOrNull { it.id == onglet }
        fr.dd.jarvis.societe.core.normaliser("$titre $mots ${o?.nom.orEmpty()} ${o?.sous?.firstOrNull { it.first == sous }?.second.orEmpty()}")
    }

    /** Tous les mots de la recherche (sans accents ni majuscules) se retrouvent dans le titre, les mots-clés ou l'onglet. */
    fun correspond(recherche: String): Boolean {
        val mots = fr.dd.jarvis.societe.core.normaliser(recherche).split(' ').filter { it.length >= 2 }
        return mots.isNotEmpty() && mots.all { texte.contains(it) }
    }

    fun chemin(): String {
        val o = ONGLETS_REGLAGES.firstOrNull { it.id == onglet } ?: return titre
        return "${o.icone} ${o.nom}" + (o.sous.firstOrNull { it.first == sous }?.second?.let { " › $it" } ?: "")
    }
}

/**
 * En-tête des Réglages : la grille des onglets (tous visibles d'un coup), la ligne de sous-onglets et la recherche.
 * Sans dépendance à l'application : prévisualisable.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ReglagesNavigation(
    onglet: String, sous: String, recherche: String?,
    onOnglet: (String) -> Unit, onSous: (String) -> Unit, onRecherche: (String?) -> Unit,
) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        // Grille 5 × 2 : chaque onglet avec son icône et son nom.
        ONGLETS_REGLAGES.chunked(5).forEach { ligne ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ligne.forEach { o ->
                    val choisi = o.id == onglet && recherche == null
                    Column(
                        Modifier.weight(1f).clip(HudShapeSmall)
                            .background(accent.copy(alpha = if (choisi) 0.34f else 0.06f))
                            .border(1.dp, accent.copy(alpha = if (choisi) 0.95f else 0.28f), HudShapeSmall)
                            .clickable { onOnglet(o.id) }
                            .padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(o.icone, fontSize = 17.sp)
                        Text(o.nom, color = if (choisi) Color.White else HudText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (choisi) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)
                    }
                }
                repeat(5 - ligne.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (recherche != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudTextField(recherche, { onRecherche(it) }, Modifier.weight(1f), placeholder = "Rechercher un réglage : voix, batterie, Freebox…", singleLine = true)
                HudChip("✕") { onRecherche(null) }
            }
        } else {
            val o = ONGLETS_REGLAGES.firstOrNull { it.id == onglet } ?: ONGLETS_REGLAGES.first()
            // Sous-onglets sur plusieurs lignes si besoin : tous visibles, rien de caché hors de l'écran.
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SousOnglet("🔎 Chercher", false) { onRecherche("") }
                o.sous.forEach { (id, nom) -> SousOnglet(nom, id == sous) { onSous(id) } }
            }
            Text(o.aide, color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(4.dp))
        HudDivider()
    }
}

/** Sous-onglet : pastille compacte (police normale), pour en voir plusieurs par ligne. */
@Composable
private fun SousOnglet(nom: String, choisi: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier.clip(HudShapeSmall)
            .background(accent.copy(alpha = if (choisi) 0.38f else 0.07f))
            .border(1.dp, accent.copy(alpha = if (choisi) 0.95f else 0.4f), HudShapeSmall)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        Text(nom, color = if (choisi) Color.White else HudText, fontSize = 12.sp, fontWeight = if (choisi) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

/** Réglages du mode de fonctionnement interne de JARVIS. */
@Composable
fun PerformancePanel(app: JarvisApp) {
    val store = app.store
    val ctx = androidx.compose.ui.platform.LocalContext.current
    HudPanel(Modifier.fillMaxWidth(), title = "⚡ Mode de fonctionnement de JARVIS") {
        Text("État : " + fr.dd.jarvis.device.Energie.libelle(app), color = HudText, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Text("Choisis comment JARVIS utilise les ressources disponibles :", color = HudDim, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("performance" to "⚡ Performance", "balanced" to "⚖️ Équilibré", "eco" to "🔋 Économie").forEach { (id, nom) ->
                HudChip(nom, store.modeEnergie.value == id || (id == "balanced" && store.modeEnergie.value == "auto")) {
                    store.modeEnergie.set(id); fr.dd.jarvis.device.Energie.appliquerMode(ctx)
                }
            }
        }
        Text(
            when (store.modeEnergie.value) {
                "performance" -> "Priorité à la réactivité : JARVIS évite de ralentir ses propres traitements, favorise les tâches simultanées et réduit les attentes internes. Android garde toutefois le contrôle du CPU, de la RAM et de la température."
                "eco" -> "Réduit les tâches secondaires, les synchronisations et les vérifications de fond. Les opérations lourdes sont différées autant que possible. L'écoute, les commandes vocales, les rappels et la réception des demandes restent actifs."
                else -> "Adaptation automatique : JARVIS ajuste ses travaux selon la batterie, l'économiseur Android et la température du téléphone. Il accélère lorsque les ressources sont disponibles et ralentit si le téléphone est sollicité."
            }, color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        Text("L'écoute vocale et la réception des demandes restent prioritaires dans les trois modes. Le mode Performance ne peut pas forcer Android à déverrouiller les fréquences CPU ou réserver la RAM ; il évite simplement les limitations volontaires de JARVIS.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        HudDivider()
        Text("Vitesse des réponses", color = HudText, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        HudChip(if (store.hermesRapide.value) "⚡ Réponses rapides : oui" else "Réponses rapides : non", store.hermesRapide.value) {
            store.hermesRapide.set(!store.hermesRapide.value)
        }
        Text("Avec « Hermès pour tout », les commandes simples (appel, SMS, musique, lumières, chauffage, agenda, rappel…) sont faites " +
            "directement par Jarvis en un seul appel d'IA ; si Jarvis n'y arrive pas, Hermès prend le relais tout seul. Les demandes " +
            "complexes, les automatismes et les recherches passent toujours par Hermès.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(6.dp))
        HudChip(if (store.analyseGemini.value) "🔍 Analyse Gemini avant de répondre : oui" else "Analyse Gemini avant de répondre : non", store.analyseGemini.value) {
            store.analyseGemini.set(!store.analyseGemini.value)
        }
        Text("Gemini choisit d'abord les outils utiles à la demande (plus précis). Elle est limitée à 4,5 s et mémorisée 15 min pour une même demande ; " +
            "désactivée, les outils sont choisis par mots-clés (un peu plus rapide, parfois moins juste).", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        Text("Toujours appliqué : connexion à l'IA préparée pendant que tu parles ou écris, recherche dans tes notes en même temps que l'analyse.",
            color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
