package fr.dd.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp

/** Une section d'un groupe de réglages : les volets d'un ancien (onglet, sous-onglet). */
data class SectionReglages(val onglet: String, val sous: String, val nom: String) {
    val cle get() = "$onglet/$sous"
}

/** Un des 6 groupes de l'accueil des Réglages. */
data class GroupeReglages(val id: String, val icone: String, val nom: String, val aide: String, val sections: List<SectionReglages>)

private fun s(onglet: String, sous: String, nom: String) = SectionReglages(onglet, sous, nom)

/** Les Réglages en 6 tuiles (au lieu de 11 onglets) ; chaque tuile ouvre une page de sections repliables. */
val GROUPES_REGLAGES = listOf(
    GroupeReglages("ia_voix", "🧠", "IA & Voix", "Clés et modèles d'IA, Hermès, mémoire, écoute « Jarvis », voix et annonces.", listOf(
        s("ia", "cles", "🔑 Clés & modèles"), s("voix", "ecoute", "👂 Écoute « Jarvis »"), s("voix", "conversation", "💬 Conversation"),
        s("voix", "voix", "🗣️ Voix de JARVIS"), s("voix", "annonces", "📣 Annonces"), s("ia", "hermes", "🪽 Hermès"),
        s("ia", "extensions", "🧩 Extensions"), s("ia", "memoire", "🧠 Mémoire"), s("ia", "medias", "🖼️ Images & vidéo"))),
    GroupeReglages("telephone", "📱", "Téléphone & voiture", "Contrôle du téléphone, applications par défaut, Android Auto, sonnerie des rappels.", listOf(
        s("telephone", "controle", "🕹️ Contrôle du téléphone"), s("telephone", "auto", "🚗 Android Auto"),
        s("telephone", "applis", "📲 Applis par défaut"), s("general", "rappels", "⏰ Sonnerie des rappels"))),
    GroupeReglages("maison", "🏠", "Maison & comptes", "Serveur et Home Assistant, box, imprimante, télé, mails, Obsidian, Discord, TikTok.", listOf(
        s("maison", "serveur", "🖥️ Serveur & domotique"), s("comptes", "mails", "✉️ Mails"), s("comptes", "obsidian", "📓 Obsidian"),
        s("maison", "box", "📶 Box Internet"), s("maison", "imprimante", "🖨️ Imprimante"), s("maison", "tele", "📺 Télé"),
        s("comptes", "discord", "🎮 Discord"), s("comptes", "tiktok", "🎬 TikTok"))),
    GroupeReglages("automatismes", "🤖", "Automatismes", "Agents autonomes, routines, points du matin et du soir, courrier, modes, métier PV.", listOf(
        s("agents", "agents", "🤖 Agents autonomes"), s("agents", "proactif", "🌅 Assistant proactif"), s("agents", "routines", "⚙️ Routines"),
        s("agents", "modes", "🌙 Modes"), s("agents", "rendez-vous", "📵 Mode rendez-vous"), s("agents", "rdv-termines", "✅ Rendez-vous terminés"),
        s("agents", "anniversaires", "🎂 Anniversaires"), s("agents", "metier", "☀️ Métier PV"))),
    GroupeReglages("securite", "🛡️", "Sécurité & données", "Systèmes de sécurité, anti-vol, empreinte vocale, confirmations, coffre-fort, sauvegardes, fichiers.", listOf(
        s("securite", "systemes", "🛡️ Systèmes"), s("securite", "antivol", "🚨 Anti-vol"), s("securite", "confirmations", "🔒 Confirmations"),
        s("securite", "empreinte", "🎤 Empreinte vocale"), s("securite", "coffre", "🗄️ Coffre-fort"), s("societe", "donnees", "📁 Freebox & sauvegardes"),
        s("societe", "facturation", "🧾 Facture électronique"), s("fichiers", "", "📂 Fichiers de JARVIS"))),
    GroupeReglages("systeme", "⚙️", "Système", "Apparence et style, fonctionnement, autorisations, diagnostic, bilan de santé, journal.", listOf(
        s("general", "apparence", "🎨 Apparence & style"), s("general", "batterie", "⚡ Fonctionnement"), s("general", "autorisations", "🔐 Autorisations"),
        s("diagnostic", "telephone", "📱 Surveillance du téléphone"), s("diagnostic", "moteurs", "🧠 Moteurs IA"), s("diagnostic", "sante", "🩺 Bilan quotidien"),
        s("diagnostic", "journal", "📜 Journal des actions"))),
)

/** Groupe d'une ancienne rubrique (onglet / sous-onglet), y compris une rubrique non listée (rangée par onglet). */
fun groupeDe(onglet: String, sous: String): GroupeReglages =
    GROUPES_REGLAGES.firstOrNull { g -> g.sections.any { it.onglet == onglet && it.sous == sous } }
        ?: GROUPES_REGLAGES.firstOrNull { g -> g.sections.any { it.onglet == onglet } } ?: GROUPES_REGLAGES.last()

/** Chemin lisible d'un réglage : « 🧠 IA & Voix › 🔑 Clés & modèles ». */
fun cheminReglage(onglet: String, sous: String, titre: String): String {
    val g = groupeDe(onglet, sous)
    val section = g.sections.firstOrNull { it.onglet == onglet && it.sous == sous }?.nom ?: titre
    return "${g.icone} ${g.nom} › $section"
}

/** Sections d'un groupe, complétées des volets rangés sous une rubrique que la liste ne cite pas (jamais invisibles). */
fun sectionsDe(g: GroupeReglages, volets: List<VoletReglages>): List<SectionReglages> {
    val connues = GROUPES_REGLAGES.flatMap { gr -> gr.sections.map { it.cle } }.toSet()
    val orphelines = volets.filter { "${it.onglet}/${it.sous}" !in connues && groupeDe(it.onglet, it.sous).id == g.id }
        .distinctBy { "${it.onglet}/${it.sous}" }.map { SectionReglages(it.onglet, it.sous, "• " + it.titre) }
    return g.sections + orphelines
}

/** État en direct affiché sur chaque tuile (une ligne) et sa pastille : vrai = en ordre, faux = à configurer. */
fun etatGroupe(app: JarvisApp, id: String): Pair<String, Boolean> {
    val st = app.store
    return runCatching {
        when (id) {
            "ia_voix" -> {
                val cles = st.groqKeys.size + st.geminiKeys.size + st.openaiKeys.size
                "$cles clé(s) IA · écoute « Jarvis » ${if (st.wakeOn.value) "ON" else "OFF"}" to (cles > 0)
            }
            "telephone" -> {
                val acc = fr.dd.jarvis.service.JarvisAccessibilityService.instance != null
                (if (acc) "contrôle actif" else "accessibilité à activer") + " · Android Auto" to acc
            }
            "maison" -> {
                val ha = st.haUrl.value.isNotBlank()
                (if (ha) "Home Assistant relié" else "Home Assistant à relier") + " · ${st.gmailAccounts.size} boîte(s) mail" to ha
            }
            "automatismes" -> (if (st.agentsOn.value) "agents actifs" else "agents en pause") + " · courrier ${if (st.courrierAnalyse.value) "analysé" else "ignoré"}" to st.agentsOn.value
            "securite" -> (if (st.antivolActif.value) "anti-vol armé" else "anti-vol désactivé") + " · confirmations" to st.antivolActif.value
            else -> "style ${StyleHud.de(st.styleInterface.value).nom} · énergie ${st.modeEnergie.value}" to true
        }
    }.getOrDefault("" to true)
}

/** Champ de recherche des réglages, toujours en haut. */
@Composable
fun RechercheReglages(recherche: String?, onRecherche: (String?) -> Unit) {
    HudTextField(
        value = recherche.orEmpty(),
        onValueChange = { onRecherche(it.takeIf(String::isNotBlank)) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        placeholder = "🔍 Rechercher un réglage…",
        singleLine = true
    )
}

/** Accueil des Réglages : 6 tuiles holographiques avec leur état en direct. */
@Composable
fun AccueilReglages(app: JarvisApp, onGroupe: (GroupeReglages) -> Unit) {
    val style = LocalStyleHud.current
    GrilleReglages(GROUPES_REGLAGES.associate { it.id to etatGroupe(app, it.id) }, "JARVIS · " + app.llm.keyStatus()) { g ->
        SonsHud.ouverture(style, app.store.sonsInterface.value); onGroupe(g)
    }
}

/** Grille des 6 tuiles (états fournis : testable sans l'application). */
@Composable
fun GrilleReglages(etats: Map<String, Pair<String, Boolean>>, pied: String, onGroupe: (GroupeReglages) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(span = { GridItemSpan(2) }) { EnteteSection("RÉGLAGES") }
        items(GROUPES_REGLAGES, key = { it.id }) { g ->
            val (etat, ok) = etats[g.id] ?: ("" to true)
            TuileGroupe(g, etat, ok) { onGroupe(g) }
        }
        item(span = { GridItemSpan(2) }) {
            Text(pied, color = HudDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun TuileGroupe(g: GroupeReglages, etat: String, ok: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Column(
        Modifier.fillMaxWidth().height(132.dp)
            .clip(HudShape)
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.18f), accent.copy(alpha = 0.03f))))
            .bordureHud(accent, HudShape, 1.5.dp)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(g.icone, fontSize = 24.sp)
            Text("${g.sections.size}", color = accent.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f).padding(end = 2.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Text(g.nom.uppercase(), color = HudText, fontSize = 13.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (ok) Color(0xFF00E676) else Color(0xFFFFB300)))
            Text(etat, color = HudDim, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/**
 * Page d'un groupe : retour, description, puis une section repliable par rubrique. [ouverts] : clés des sections
 * dépliées. La section « Fichiers » ouvre la bibliothèque en pleine page ([onFichiers]).
 */
@Composable
fun PageGroupeReglages(
    g: GroupeReglages, volets: List<VoletReglages>, ouverts: Set<String>, etat: LazyListState,
    onRetour: () -> Unit, onBasculer: (SectionReglages) -> Unit, onFichiers: () -> Unit,
) {
    val accent = LocalAccent.current
    val sections = sectionsDe(g, volets)
    LazyColumn(
        state = etat,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "entete") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onRetour).padding(vertical = 4.dp)) {
                    Text("‹ RÉGLAGES", color = accent, fontSize = 11.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text(g.icone, fontSize = 26.sp)
                    Text(g.nom.uppercase(), color = HudText, fontSize = 16.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 10.dp))
                }
                Text(g.aide, color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
            }
        }
        sections.forEach { sec ->
            val ouvert = sec.cle in ouverts
            val contenu = volets.filter { it.onglet == sec.onglet && it.sous == sec.sous }
            val fichiers = sec.onglet == "fichiers"
            if (contenu.isEmpty() && !fichiers) return@forEach
            item(key = "s:" + sec.cle) {
                Row(
                    Modifier.fillMaxWidth().clip(HudShapeSmall)
                        .background(accent.copy(alpha = if (ouvert) 0.16f else 0.06f))
                        .bordureHud(accent, HudShapeSmall, 1.dp, base = if (ouvert) 0.85f else 0.4f)
                        .clickable { if (fichiers) onFichiers() else onBasculer(sec) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (fichiers) "↗" else if (ouvert) "▾" else "▸", color = accent, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Text(sec.nom, color = if (ouvert) Color.White else HudText, fontSize = 13.sp, fontWeight = if (ouvert) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f).padding(start = 10.dp))
                    if (!fichiers) Text("${contenu.size}", color = HudDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
            if (ouvert) contenu.forEach { v ->
                item(key = "v:" + v.onglet + "/" + v.sous + "/" + v.titre) {
                    // Colonne (et non Box) : un réglage fait de plusieurs panneaux les empile au lieu de les superposer.
                    Column(Modifier.padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { v.contenu() }
                }
            }
        }
    }
}
