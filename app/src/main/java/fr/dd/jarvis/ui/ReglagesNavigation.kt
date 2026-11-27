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

/** Organisation des Réglages : 11 onglets, chacun découpé en sous-onglets (un sujet par page). */
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
    OngletReglages("maison", "🏠", "Maison", "Serveur et Home Assistant, box Internet, imprimante, télé.",
        listOf("serveur" to "🖥️ Serveur & domotique", "box" to "📶 Box Internet", "imprimante" to "🖨️ Imprimante", "tele" to "📺 Télé")),
    OngletReglages("agents", "🤖", "Agents", "Agents autonomes, routines, assistant proactif, modes automatiques.",
        listOf("agents" to "🤖 Agents autonomes", "routines" to "⚙️ Routines", "proactif" to "🌅 Assistant proactif", "modes" to "🌙 Modes", "metier" to "☀️ Métier PV")),
    OngletReglages("securite", "🛡️", "Sécurité", "Systèmes de sécurité, anti-vol, empreinte vocale, confirmations, coffre-fort de la société.",
        listOf("systemes" to "🛡️ Systèmes", "antivol" to "🚨 Anti-vol", "empreinte" to "🎤 Empreinte vocale", "confirmations" to "🔒 Confirmations", "coffre" to "🗄️ Coffre-fort")),
    OngletReglages("societe", "🏢", "Société", "Données de la société sur la Freebox, sauvegardes chiffrées, facturation électronique.",
        listOf("donnees" to "📁 Freebox & sauvegardes", "facturation" to "🧾 Facture électronique")),
    OngletReglages("diagnostic", "📊", "Diagnostic", "État du téléphone et des moteurs d'IA, bilan de santé quotidien, journal des actions.",
        listOf("telephone" to "📱 Téléphone", "moteurs" to "🧠 Moteurs IA", "sante" to "🩺 Bilan quotidien", "journal" to "📜 Journal des actions")),
    OngletReglages("fichiers", "📁", "Fichiers", "Bibliothèque des fichiers créés et reçus par JARVIS.", emptyList()),
)

/** Un panneau de réglages, rangé dans un onglet / sous-onglet, avec des mots-clés pour la recherche. */
class VoletReglages(val onglet: String, val sous: String, val titre: String, val mots: String, val contenu: @Composable () -> Unit) {
    private val texte by lazy {
        val o = ONGLETS_REGLAGES.firstOrNull { it.id == onglet }
        fr.dd.jarvis.societe.core.normaliser("$titre $mots ${o?.nom.orEmpty()} ${o?.sous?.firstOrNull { it.first == sous }?.second.orEmpty()} ${cheminReglage(onglet, sous, titre)}")
    }

    /** Tous les mots de la recherche (sans accents ni majuscules) se retrouvent dans le titre, les mots-clés ou l'onglet. */
    fun correspond(recherche: String): Boolean {
        val mots = fr.dd.jarvis.societe.core.normaliser(recherche).split(' ').filter { it.length >= 2 }
        return mots.isNotEmpty() && mots.all { texte.contains(it) }
    }

    fun chemin(): String = cheminReglage(onglet, sous, titre)
}

/**
 * Navigation des réglages compacte : la place est réservée aux options elles-mêmes.
 * Le titre de l'écran est déjà affiché par l'en-tête général de JARVIS.
 */
@Composable
fun ReglagesNavigation(
    onglet: String, sous: String, recherche: String?,
    onOnglet: (String) -> Unit, onSous: (String) -> Unit, onRecherche: (String?) -> Unit,
) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        HudTextField(
            value = recherche.orEmpty(),
            onValueChange = { onRecherche(it.takeIf(String::isNotBlank)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Rechercher un réglage…",
            singleLine = true
        )
        if (recherche.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ONGLETS_REGLAGES.forEach { o ->
                    val choisi = o.id == onglet
                    Row(
                        Modifier.clip(HudShapeSmall)
                            .background(accent.copy(alpha = if (choisi) 0.20f else 0.045f))
                            .border(1.dp, accent.copy(alpha = if (choisi) 0.78f else 0.15f), HudShapeSmall)
                            .clickable { onOnglet(o.id) }
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(o.icone, fontSize = 13.sp)
                        Text(o.nom, color = if (choisi) Color.White else HudText, fontSize = 10.sp,
                            fontWeight = if (choisi) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
            val o = ONGLETS_REGLAGES.firstOrNull { it.id == onglet } ?: ONGLETS_REGLAGES.first()
            if (o.sous.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    o.sous.forEach { (id, nom) ->
                        SousOnglet(nom, id == sous) { onSous(id) }
                    }
                }
            }
        } else {
            Text("RÉSULTATS DE RECHERCHE", color = accent, fontSize = 9.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
        }
        Spacer(Modifier.height(5.dp))
        HudDivider()
    }
}

/** Sous-rubrique compacte mais suffisamment grande pour être touchée facilement. */
@Composable
private fun SousOnglet(nom: String, choisi: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier.clip(HudShapeSmall)
            .background(accent.copy(alpha = if (choisi) 0.22f else 0.045f))
            .border(1.dp, accent.copy(alpha = if (choisi) 0.78f else 0.17f), HudShapeSmall)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(nom, color = if (choisi) Color.White else HudText, fontSize = 10.sp,
            fontWeight = if (choisi) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
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
        HudChip(if (store.hermesRapide.value) "⚡ Actions par Jarvis : oui" else "Actions par Jarvis : non", store.hermesRapide.value) {
            store.hermesRapide.set(!store.hermesRapide.value)
        }
        Text("Les actions (téléphone, appels, SMS, mails, agenda, rappels, alarmes, maison, Plex, Freebox, société, fichiers, notes…) " +
            "sont faites directement par Jarvis — sans IA du tout pour les plus courantes — ; si Jarvis n'y arrive pas, Hermès prend le relais tout seul. " +
            "Les questions, recherches sur internet, rédactions, automatismes et le serveur passent par Hermès.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
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
