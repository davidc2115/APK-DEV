package fr.dd.jarvis.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import android.graphics.BitmapFactory
import java.io.File
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Prompts
import fr.dd.jarvis.societe.Geo
import fr.dd.jarvis.societe.core.*
import java.time.LocalDate
import androidx.compose.ui.platform.LocalView

private val SUBS = listOf("Résumé", "Clients", "Interventions", "Devis", "Factures", "Équipe", "Fiches de paie", "Réglages")

@Composable
fun SocieteScreen(app: JarvisApp) {
    var sub by remember { mutableIntStateOf(0) }
    // Réaligne la liste Devis/Factures sur le coffre à chaque ouverture de l'onglet : un document
    // supprimé directement dans Obsidian (hors JARVIS) ne prévient sinon personne, et la liste ici
    // restait périmée tant que l'app n'était pas relancée.
    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { app.societe.synchroniserAvecCoffre() } }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SUBS.forEachIndexed { i, t -> HudChip(t, selected = i == sub) { sub = i } }
        }
        when (sub) {
            0 -> ResumeTab(app)
            1 -> ClientsTab(app)
            2 -> InterventionsTab(app)
            3 -> DocsTab(app, "devis")
            4 -> DocsTab(app, "facture")
            5 -> EquipeTab(app)
            6 -> FichesPaieTab(app)
            else -> ReglagesTab(app)
        }
    }
}

@Composable
private fun Dialogue(titre: String, onClose: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Color(0xFF0B1424), titleContentColor = LocalAccent.current, textContentColor = HudText,
        title = { Text(titre, fontSize = 16.sp) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } },
        confirmButton = { Row { actions() } },
        dismissButton = { TextButton(onClose) { Text("Fermer", color = HudDim) } }
    )
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    TextButton(onClick) { Text(label, color = LocalAccent.current, fontSize = 13.sp) }
}

// ---------------------------------------------------------------------- Historique photovoltaïque

private data class InterventionPhotoTarget(
    val clientId: String,
    val siteId: String,
    val interventionId: String,
    val avant: Boolean
)

@Composable
private fun PhotosIntervention(paths: List<String>, label: String) {
    val context = LocalContext.current
    if (paths.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = HudDim, fontSize = 11.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            paths.forEach { path ->
                val bitmap = remember(path) {
                    BitmapFactory.decodeFile(File(context.filesDir, "societe/$path").absolutePath)?.asImageBitmap()
                }
                if (bitmap != null) Image(bitmap, contentDescription = label, modifier = Modifier.size(88.dp))
            }
        }
    }
}

@Composable
private fun InterventionsTab(app: JarvisApp) {
    var photoInfo by remember { mutableStateOf("") }
    var photoTarget by remember { mutableStateOf<InterventionPhotoTarget?>(null) }
    var editTarget by remember { mutableStateOf<Triple<Client, Site, Intervention>?>(null) }
    var date by remember { mutableStateOf("") }; var categorie by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }; var diagnostic by remember { mutableStateOf("") }
    var actions by remember { mutableStateOf("") }; var pieces by remember { mutableStateOf("") }
    var mesures by remember { mutableStateOf("") }; var statut by remember { mutableStateOf("") }
    var prochaine by remember { mutableStateOf("") }; var notes by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = photoTarget
        if (target != null && uris.isNotEmpty()) scope.launch {
            photoInfo = "Enregistrement des photos…"
            photoInfo = withContext(Dispatchers.IO) { app.societe.ajouterPhotosIntervention(target.clientId, target.siteId, target.interventionId, target.avant, uris) }
        }
        photoTarget = null
    }
    val sites = app.societe.data.clients.flatMap { client -> client.sites.map { site -> client to site } }.sortedBy { it.first.nom.lowercase() + " " + it.second.nom.lowercase() }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { HudPanel(Modifier.fillMaxWidth(), title = "🛠️ Carnet technique photovoltaïque") {
            Text("Un dossier structuré par installation : constats, réparations, pièces, mesures et photos. Les corrections faites par Hermès sont répercutées ici et dans les rapports.", color = HudDim, fontSize = 12.sp)
            val interventions = sites.flatMap { (client, site) -> site.interventions.map { client to it } }
            val terminees = interventions.count { it.second.statut == "terminee" }
            val aSuivre = interventions.count { it.second.statut == "a_suivre" }
            val enAttente = interventions.count { it.second.statut == "en_attente" }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                HudPanel(Modifier.weight(1f), title = "INSTALLATIONS") {
                    Text(sites.size.toString(), color = LocalAccent.current, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                HudPanel(Modifier.weight(1f), title = "INTERVENTIONS") {
                    Text(interventions.size.toString(), color = HudText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                HudPanel(Modifier.weight(1f), title = "TERMINÉES") {
                    Text(terminees.toString(), color = Color(0xFF78D6A0), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                HudPanel(Modifier.weight(1f), title = "À SUIVRE") {
                    Text((aSuivre + enAttente).toString(), color = Color(0xFFFFB45C), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text("Les modifications effectuées par Hermès actualisent cette liste et les rapports associés.", color = HudDim, fontSize = 11.sp)
            if (photoInfo.isNotBlank()) Text(photoInfo, color = LocalAccent.current, fontSize = 12.sp)
        } }
        if (sites.isEmpty()) item { Text("Aucune installation enregistrée. Ajoute d'abord un client et son installation.", color = HudDim, fontSize = 13.sp) }
        items(sites, key = { it.first.id + "_" + it.second.id }) { pair ->
            val client = pair.first; val site = pair.second
            HudPanel(Modifier.fillMaxWidth(), title = "☀️ " + client.nom + " · " + site.nom) {
                Text(site.adresse.ifBlank { "Adresse non renseignée" }, color = HudDim, fontSize = 11.sp)
                Text(site.kwc.toString() + " kWc · " + site.onduleur.ifBlank { "Onduleur non renseigné" }, color = HudText, fontSize = 12.sp)
                HudDivider()
                Text("HISTORIQUE · " + site.interventions.size + " fiche(s)", color = LocalAccent.current, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                if (site.interventions.isEmpty()) Text("Aucune intervention enregistrée.", color = HudDim, fontSize = 12.sp)
                site.interventions.sortedByDescending { it.date }.forEach { item ->
                    HudPanel(Modifier.fillMaxWidth(), title = item.categorie.replaceFirstChar { it.uppercase() } + " · " + dateFr(item.date)) {
                        val statusText = when (item.statut.lowercase()) { "terminee" -> "✓ TERMINÉE"; "a_suivre" -> "◷ À SUIVRE"; "en_attente" -> "Ⅱ EN ATTENTE"; else -> item.statut.uppercase() }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(statusText, color = if (item.statut == "terminee") Color(0xFF78D6A0) else Color(0xFFFFB45C), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text("#${item.id}", color = HudDim, fontSize = 9.sp)
                        }
                        Text(item.description, color = HudText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        if (item.diagnostic.isNotBlank()) { HudDivider(); Text("DIAGNOSTIC / CONSTAT", color = LocalAccent.current, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(item.diagnostic, color = HudText, fontSize = 12.sp) }
                        if (item.actions.isNotBlank()) { HudDivider(); Text("TRAVAUX RÉALISÉS", color = LocalAccent.current, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(item.actions, color = HudText, fontSize = 12.sp) }
                        if (item.pieces.isNotBlank()) { HudDivider(); Text("PIÈCES / RÉFÉRENCES", color = LocalAccent.current, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(item.pieces, color = HudText, fontSize = 12.sp) }
                        if (item.mesures.isNotBlank()) { HudDivider(); Text("MESURES RELEVÉES", color = LocalAccent.current, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(item.mesures, color = HudText, fontSize = 12.sp) }
                        if (item.prochaineMaintenance.isNotBlank()) Text("PROCHAINE MAINTENANCE · " + dateFr(item.prochaineMaintenance), color = Color(0xFFFFC56E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (item.notes.isNotBlank()) Text("Notes : " + item.notes, color = HudDim, fontSize = 11.sp)
                        if (item.photosAvant.isNotEmpty() || item.photosApres.isNotEmpty()) { HudDivider(); Text("REPORTAGE PHOTO", color = LocalAccent.current, fontSize = 10.sp, fontWeight = FontWeight.Bold); PhotosIntervention(item.photosAvant, "Avant · " + item.photosAvant.size); PhotosIntervention(item.photosApres, "Après · " + item.photosApres.size) }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Action("✏️ Modifier") {
                                editTarget = Triple(client, site, item); date = item.date; categorie = item.categorie
                                description = item.description; diagnostic = item.diagnostic; actions = item.actions
                                pieces = item.pieces; mesures = item.mesures; statut = item.statut
                                prochaine = item.prochaineMaintenance; notes = item.notes
                            }
                            Action("📷 Avant") { photoTarget = InterventionPhotoTarget(client.id, site.id, item.id, true); picker.launch(arrayOf("image/*")) }
                            Action("📷 Après") { photoTarget = InterventionPhotoTarget(client.id, site.id, item.id, false); picker.launch(arrayOf("image/*")) }
                        }
                    }
                }
            }
        }
    }
    editTarget?.let { target ->
        Dialogue("Modifier l'intervention · " + target.first.nom, { editTarget = null }, actions = {
            TextButton(onClick = {
                val updated = target.third.copy(date = date.trim(), categorie = categorie.trim(), description = description.trim(), diagnostic = diagnostic.trim(), actions = actions.trim(), pieces = pieces.trim(), mesures = mesures.trim(), statut = statut.trim().lowercase(), prochaineMaintenance = prochaine.trim(), notes = notes.trim())
                scope.launch { photoInfo = withContext(Dispatchers.IO) { app.societe.modifierIntervention(target.first.id, target.second.id, target.third.id, updated) } }
                editTarget = null
            }) { Text("Enregistrer", color = LocalAccent.current) }
        }) {
            Text(target.second.nom + " · ID " + target.third.id, color = HudDim, fontSize = 10.sp)
            HudTextField(date, { date = it }, label = "Date (yyyy-MM-dd)")
            HudTextField(categorie, { categorie = it }, label = "Catégorie")
            HudTextField(statut, { statut = it }, label = "Statut : terminee, a_suivre, en_attente")
            HudTextField(description, { description = it }, label = "Résumé", singleLine = false, maxLines = 4)
            HudTextField(diagnostic, { diagnostic = it }, label = "Diagnostic / constat", singleLine = false, maxLines = 4)
            HudTextField(actions, { actions = it }, label = "Travaux réalisés", singleLine = false, maxLines = 4)
            HudTextField(pieces, { pieces = it }, label = "Pièces / références", singleLine = false, maxLines = 3)
            HudTextField(mesures, { mesures = it }, label = "Mesures et unités", singleLine = false, maxLines = 3)
            HudTextField(prochaine, { prochaine = it }, label = "Prochaine maintenance (yyyy-MM-dd)")
            HudTextField(notes, { notes = it }, label = "Notes complémentaires", singleLine = false, maxLines = 3)
        }
    }
}

// ---------------------------------------------------------------------- Résumé

@Composable
private fun ResumeTab(app: JarvisApp) {
    val d = app.societe.data
    val annee = LocalDate.now().year
    val factures = d.docs.filter { it.type == "facture" && it.statut != "annulee" }
    val encaisse = factures.filter { it.statut == "payee" && it.payeLe.startsWith(annee.toString()) }.sumOf { d.totaux(it).ttc }
    val aPayer = factures.filter { it.statut == "a_payer" }.sumOf { d.totaux(it).ttc }
    val devisOuverts = d.docs.count { it.type == "devis" && it.statut in setOf("brouillon", "envoye", "accepte") }
    val b = app.societe.bilan(annee)
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HudPanel(Modifier.fillMaxWidth(), title = d.societe.nom.ifBlank { "Société" }) {
                if (d.societe.nom.isBlank()) Text("Dis à JARVIS : « Enregistre ma société : nom…, siège…, SIRET… » ou remplis l'onglet Réglages.", color = HudDim, fontSize = 13.sp)
                else Text("📍 ${d.societe.adresse.ifBlank { "siège non renseigné" }}", color = HudText, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("👥 ${d.clients.size} client(s) · ☀️ ${d.clients.sumOf { it.sites.size }} installation(s)", color = HudText, fontSize = 13.sp)
                Text("🧾 $devisOuverts devis en cours · ${factures.count { it.statut == "a_payer" }} facture(s) à encaisser", color = HudText, fontSize = 13.sp)
            }
        }
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "Chiffres $annee") {
                Text("Encaissé (TTC) : ${euro(encaisse)}", color = HudText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Reste à encaisser : ${euro(aPayer)}", color = HudText, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("💰 Réserve bénéfices : ${euro(b.total)}" + (if (d.societe.reservePct > 0) " (${nombre(d.societe.reservePct)} % du HT encaissé)" else " — non configurée"), color = HudText, fontSize = 13.sp)
                if (d.societe.reservePct > 0) {
                    Text("   Dirigeant : ${euro(b.dirigeant)}" + b.salaries.joinToString("") { " · ${it.first.nom} : ${euro(it.second)}" }, color = HudDim, fontSize = 12.sp)
                }
            }
        }
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "Avec JARVIS") {
                Text(
                    "« Mon tarif maintenance : jusqu'à 3 kWc 150 €, jusqu'à 9 kWc 220 €, au-delà 350 € »\n" +
                        "« 0,60 € du km, 10 km offerts, aller-retour »\n« 5 % de remise dès 5 ans de fidélité, 3 % dès 3 installations »\n" +
                        "« Fais un devis de maintenance pour M. Dupont »\n« Mets 10 % de chaque facture payée en réserve »\n« Change la présentation de mes factures »",
                    color = HudDim, fontSize = 12.sp
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- Clients

/** Référence stable pour invalider le cache distance aussi bien avec une adresse qu'avec un point GPS seul. */
private fun cleReferenceGeo(adresse: String, lat: Double?, lon: Double?): String =
    if (adresse.isNotBlank()) normaliser(adresse)
    else if (lat != null && lon != null) "gps:${Geo.texteGps(Geo.Point(lat, lon))}" else ""

@Composable
private fun ClientsTab(app: JarvisApp) {
    val svc = app.societe
    // Recharge le JSON persistant à chaque ouverture de l'onglet : la liste ne dépend pas
    // uniquement de l'instantané qui était en mémoire avant l'action de l'agent.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { svc.repo.refresh() }
    }
    val d = svc.data
    var selected by remember { mutableStateOf<Client?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Client?>(null) }
    var deleteTarget by remember { mutableStateOf<Client?>(null) }
    var query by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    val kmlScope = rememberCoroutineScope()
    val view = LocalView.current
    var diagnosticConfirm by remember { mutableStateOf(false) }
    val list = d.clients.filter { client -> query.isBlank() || normaliser(listOf(client.nom, client.contact, client.type, client.tel, client.email, client.adresse, client.sites.joinToString(" ") { site -> "${site.nom} ${site.adresse} ${site.onduleur}" }).joinToString(" ")).contains(normaliser(query)) }.sortedBy { normaliser(it.nom) }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudTextField(query, { query = it }, Modifier.weight(1f), placeholder = "Rechercher un client…")
                HudRoundButton(Icons.Filled.Add) { creating = true }
            }
        }
        item {
            HudButton("🗺️ Exporter les points GPS (KML Google Earth)") {
                kmlScope.launch {
                    info = "⏳ Géolocalisation des adresses…"
                    info = runCatching { svc.exporterKml(query).lines().drop(2).joinToString("\n").trim() }.getOrElse { "❌ ${it.message}" }
                    svc.partagerKml()
                }
            }
        }
        if (info.isNotBlank()) item { Text(info, color = LocalAccent.current, fontSize = 12.sp) }
        if (list.isEmpty()) item { Text("Aucun client. Touche + ou demande à JARVIS.", color = HudDim, fontSize = 13.sp) }
        items(list, key = { it.id }) { c ->
            val hq = d.societe
            val hqAdresse = hq.adresse
            val sitePourDistance = if (c.adresse.isBlank()) c.sites.firstOrNull {
                it.adresse.isNotBlank() || (it.lat != null && it.lon != null)
            } else null
            val destination = sitePourDistance?.adresse?.ifBlank { null } ?: c.adresse
            val hqRef = cleReferenceGeo(hqAdresse, hq.lat, hq.lon)
            val destinationRef = cleReferenceGeo(destination, sitePourDistance?.lat, sitePourDistance?.lon)
            val distanceKey = "$hqRef|$destinationRef"
            var distanceLabel by remember(c.id, hqRef, destinationRef, c.distanceKm, c.distanceCle) {
                mutableStateOf(
                    if (c.distanceKm != null && c.distanceCle == distanceKey)
                        "🚗 ${nombre(c.distanceKm)} km du siège" +
                            (c.distanceDureeMin?.takeIf { it > 0 }?.let { " · ${it.toInt()} min" } ?: "")
                    else if (hqRef.isBlank() || destinationRef.isBlank()) "Distance non calculable · renseigne l'adresse ou le GPS du siège et du client"
                    else "Calcul de la distance…"
                )
            }
            LaunchedEffect(c.id, hqRef, destinationRef, c.distanceKm, c.distanceCle) {
                if (hqRef.isNotBlank() && destinationRef.isNotBlank() &&
                    (c.distanceKm == null || c.distanceCle != distanceKey)) {
                    val route = runCatching { svc.distanceClient(c) }.getOrNull()
                    distanceLabel = when {
                        route == null -> "Distance indisponible · vérifier l'adresse ou le positionnement GPS"
                        route.estime -> "≈ ${nombre(route.km)} km · estimation"
                        else -> "🚗 ${nombre(route.km)} km du siège" +
                            (route.minutes.takeIf { it > 0 }?.let { " · ${it.toInt()} min" } ?: "")
                    }
                }
            }
            HudPanel(Modifier.fillMaxWidth(), onClick = { selected = c }) {
                Text(c.nom, color = HudText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("☀️ ${c.sites.size} installation(s) · ${nombre(c.sites.sumOf { it.kwc })} kWc · CA ${euro(d.caClient(c))}", color = HudDim, fontSize = 12.sp)
                if (c.adresse.isNotBlank()) Text("📍 ${c.adresse}", color = HudDim, fontSize = 12.sp)
                c.sites.filter { it.lat != null && it.lon != null }.forEach { site ->
                    val point = Geo.Point(site.lat!!, site.lon!!)
                    Text(
                        "🛰️ ${site.nom}: ${Geo.texteGps(point)}" +
                            " · ${(Geo.precisionDe(site.gpsPrecision) ?: Geo.Precision.INCONNUE).libelle}",
                        color = LocalAccent.current, fontSize = 11.sp
                    )
                }
                Text(distanceLabel, color = LocalAccent.current, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    // Hermès peut mettre à jour le GPS pendant que le dialogue client reste ouvert.
    // Reprendre à chaque recomposition la fiche fraîche du dépôt, pas l'ancien instantané sélectionné.
    val selectedLive = selected?.let { chosen -> d.client(chosen.id) ?: chosen }
    selectedLive?.let { c0 ->
        val c = d.client(c0.id) ?: c0
        val md = Rendu.rendreClient(d, c)
        Dialogue(c.nom, { selected = null }, actions = {
            Action("Obsidian") { info = "📁 " + svc.exporterClient(c).second; selected = null }
            Action("PDF") { svc.partagerPdf("Fiche ${c.nom}", md) }
            Action("✎ Modifier") { editing = c; selected = null }
            Action("🗑 Supprimer") { deleteTarget = c; selected = null }
            Action("Diagnostic") { diagnosticConfirm = true }
        }) {
            MarkdownText(md)
            val sitesGps = c.sites.filter { it.lat != null && it.lon != null }
            if (sitesGps.isNotEmpty()) {
                HudDivider()
                Text("Coordonnées GPS actuelles", color = LocalAccent.current, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                sitesGps.forEach { site ->
                    val point = Geo.Point(site.lat!!, site.lon!!)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(site.nom, color = HudText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text(Geo.texteGps(point), color = LocalAccent.current, fontSize = 12.sp)
                        Text(
                            "${(Geo.precisionDe(site.gpsPrecision) ?: Geo.Precision.INCONNUE).libelle} · ${Geo.texteDms(point)}",
                            color = HudDim, fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
    deleteTarget?.let { target ->
        Dialogue("Supprimer le client · ${target.nom}", { deleteTarget = null }, actions = {
            Action("Supprimer définitivement") {
                kmlScope.launch {
                    info = "⏳ Vérification et suppression de ${target.nom}…"
                    val resultat = withContext(Dispatchers.IO) {
                        runCatching {
                            val current = svc.repo.refresh()
                            val client = current.client(target.id)
                                ?: return@runCatching "ℹ️ La fiche « ${target.nom} » est déjà absente de la base Société."
                            val documents = current.docs.filter { it.clientId == client.id }
                            if (documents.isNotEmpty()) {
                                val numeros = documents.take(8).joinToString(", ") { it.numero }
                                return@runCatching "⛔ Suppression bloquée : ${client.nom} possède ${documents.size} devis/facture(s) liés ($numeros${if (documents.size > 8) "…" else ""}). Traite ou transfère d'abord ces documents pour préserver l'historique comptable."
                            }
                            svc.repo.update { data -> data.copy(clients = data.clients.filterNot { it.id == client.id }) }
                            if (svc.repo.refresh().client(client.id) != null)
                                return@runCatching "❌ Échec : ${client.nom} est toujours présent dans la base Société. Aucune suppression n'est confirmée."
                            val path = "Société/Clients/${client.nom}.md"
                            val notes = app.obsidian.listNotes("Société/Clients")
                            if (path in notes) {
                                val deleted = app.obsidian.supprimerNote(path)
                                val remains = path in app.obsidian.listNotes("Société/Clients")
                                if (!deleted || remains)
                                    return@runCatching "⚠️ ${client.nom} a été retiré de la base Jarvis, mais sa fiche Obsidian n'a pas pu être supprimée ($path). Vérifie les droits du coffre."
                            }
                            "🗑️ Client « ${client.nom} » et sa fiche Société/Clients supprimés. La disparition de la base interne a été vérifiée."
                        }.getOrElse { "❌ Suppression impossible : ${it.message ?: it.javaClass.simpleName}" }
                    }
                    info = resultat
                    deleteTarget = null
                }
            }
        }) {
            Text("Cette action supprimera la fiche client et ses installations de la base Société ainsi que sa copie Markdown dans Obsidian. Les devis et factures liés seront conservés : tant qu'il en existe, Jarvis bloquera la suppression.", color = HudText, fontSize = 13.sp)
            Text("Cette opération est irréversible.", color = Color(0xFFFF6B75), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
    if (diagnosticConfirm && selectedLive != null) {
        AlertDialog(
            onDismissRequest = { diagnosticConfirm = false },
            title = { Text("Partager un diagnostic client ?") },
            text = {
                Text(
                    "Le fichier contiendra une capture de l'écran Jarvis et les données techniques de la fiche ${selectedLive.nom} : adresses, coordonnées GPS, précision, distances et état du siège. Rien n'est envoyé automatiquement. Android te laissera choisir l'application à laquelle le partager."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    diagnosticConfirm = false
                    val client = selectedLive
                    view.post { runCatching { partagerDiagnosticClient(view.context, view, app, client) }
                        .onFailure { android.widget.Toast.makeText(view.context, "Diagnostic impossible : ${it.message}", android.widget.Toast.LENGTH_LONG).show() } }
                }) { Text("Créer et partager") }
            },
            dismissButton = {
                TextButton(onClick = { diagnosticConfirm = false }) { Text("Annuler") }
            }
        )
    }
    if (creating) ClientForm(null, onClose = { creating = false }) { c ->
        svc.repo.update { it.copy(clients = it.clients + c) }
        creating = false
    }
    editing?.let { e0 ->
        ClientForm(e0, onClose = { editing = null }) { c ->
            svc.repo.update { d0 -> d0.copy(clients = d0.clients.map { if (it.id == c.id) c else it }) }
            info = "✅ Fiche de ${c.nom} mise à jour"
            editing = null
        }
    }
}

private data class SiteDraft(
    val id: String, val nom: String, val adresse: String, val kwc: String, val mes: String,
    val onduleur: String, val panneaux: String, val notes: String,
    /** Coordonnées GPS au format Google Maps (« 47.541558, 4.459845 »), vide si inconnues. */
    val gps: String = ""
)

/** Coordonnées enregistrées d'une installation, au format Google Maps ; vide si aucune. */
private fun gpsTexte(s: Site?): String =
    if (s?.lat != null && s.lon != null) Geo.texteGps(Geo.Point(s.lat, s.lon)) else ""

@Composable
private fun ClientForm(initial: Client?, onClose: () -> Unit, onSave: (Client) -> Unit) {
    var nom by remember { mutableStateOf(initial?.nom ?: "") }
    var contact by remember { mutableStateOf(initial?.contact ?: "") }
    var tel by remember { mutableStateOf(initial?.tel ?: "") }
    var email by remember { mutableStateOf(initial?.email ?: "") }
    var adresse by remember { mutableStateOf(initial?.adresse ?: "") }
    var depuis by remember { mutableStateOf(initial?.depuis?.ifBlank { null } ?: LocalDate.now().year.toString()) }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var pro by remember { mutableStateOf(initial?.type == "professionnel") }
    val sites = remember {
        androidx.compose.runtime.mutableStateListOf<SiteDraft>().also { l ->
            initial?.sites?.forEach { l += SiteDraft(it.id, it.nom, it.adresse, if (it.kwc > 0) nombre(it.kwc) else "", it.miseEnService, it.onduleur, if (it.nbPanneaux > 0) it.nbPanneaux.toString() else "", it.notes, gpsTexte(it)) }
        }
    }
    Dialogue(if (initial == null) "Nouveau client" else "Modifier ${initial.nom}", onClose, actions = {
        Action(if (initial == null) "Créer" else "Enregistrer") {
            if (nom.isNotBlank()) {
                val newSites = sites.map { d ->
                    val old = initial?.sites?.firstOrNull { it.id == d.id }
                    val same = old != null && old.adresse == d.adresse
                    // Coordonnées modifiées à la main (format Google Maps, DMS ou lien) : prioritaires et jamais recalculées.
                    val gpsAvant = gpsTexte(old)
                    val saisi = if (d.gps.isNotBlank() && d.gps.trim() != gpsAvant) Geo.lireCoordonnees(d.gps) else null
                    val efface = d.gps.isBlank() && gpsAvant.isNotBlank()
                    val base = Site(
                        d.id, d.nom.ifBlank { "Installation" }, d.adresse, d.kwc.replace(',', '.').toDoubleOrNull() ?: 0.0,
                        d.mes, d.onduleur, d.panneaux.toIntOrNull() ?: 0, d.notes
                    )
                    when {
                        saisi != null -> base.copy(lat = saisi.lat, lon = saisi.lon, gpsPrecision = Geo.Precision.MANUELLE.name, gpsTrouve = "coordonnées GPS saisies")
                        same && !efface && old != null -> base.copy(
                            lat = old.lat, lon = old.lon, gpsPrecision = old.gpsPrecision, gpsTrouve = old.gpsTrouve,
                            distanceKm = old.distanceKm, dureeMin = old.dureeMin, distanceCle = old.distanceCle
                        )
                        // Adresse modifiée ou coordonnées effacées : la position sera recalculée (sauf saisie manuelle conservée).
                        old != null && !efface && Geo.precisionDe(old.gpsPrecision) == Geo.Precision.MANUELLE -> base.copy(
                            lat = old.lat, lon = old.lon, gpsPrecision = old.gpsPrecision, gpsTrouve = old.gpsTrouve
                        )
                        else -> base
                    }
                }
                onSave(Client(
                    initial?.id ?: java.util.UUID.randomUUID().toString().take(8), nom.trim(), if (pro) "professionnel" else "particulier",
                    contact = contact, tel = tel, email = email, adresse = adresse, sites = newSites,
                    depuis = if (depuis.matches(Regex("\\d{4}"))) "$depuis-01-01" else depuis, notes = notes,
                    distanceKm = initial?.distanceKm, distanceDureeMin = initial?.distanceDureeMin, distanceCle = initial?.distanceCle ?: ""
                ))
            }
        }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudChip("Particulier", !pro) { pro = false }
            HudChip("Professionnel", pro) { pro = true }
        }
        HudTextField(nom, { nom = it }, Modifier.fillMaxWidth(), label = "Nom")
        HudTextField(contact, { contact = it }, Modifier.fillMaxWidth(), label = "Contact (interlocuteur)")
        HudTextField(tel, { tel = it }, Modifier.fillMaxWidth(), label = "Téléphone")
        HudTextField(email, { email = it }, Modifier.fillMaxWidth(), label = "Email")
        HudTextField(adresse, { adresse = it }, Modifier.fillMaxWidth(), label = "Adresse")
        HudTextField(depuis, { depuis = it }, Modifier.fillMaxWidth(), label = "Client depuis (année ou aaaa-mm-jj)")
        HudTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = "Notes", singleLine = false)
        Text("Installations", color = LocalAccent.current, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        sites.forEachIndexed { i, d ->
            HudPanel(Modifier.fillMaxWidth()) {
                HudTextField(d.nom, { sites[i] = d.copy(nom = it) }, Modifier.fillMaxWidth(), label = "Nom de l'installation")
                HudTextField(d.adresse, { sites[i] = d.copy(adresse = it) }, Modifier.fillMaxWidth(), label = "Adresse (vide = celle du client)")
                HudTextField(d.gps, { sites[i] = d.copy(gps = it) }, Modifier.fillMaxWidth(), label = "Coordonnées GPS (comme Google Maps : 47.541558, 4.459845)")
                val ancien = initial?.sites?.firstOrNull { it.id == d.id }
                val lisible = d.gps.isBlank() || Geo.lireCoordonnees(d.gps) != null
                Text(
                    when {
                        !lisible -> "Format non reconnu : colle les coordonnées copiées dans Google Maps (appui long sur le toit → touche les coordonnées), des degrés-minutes-secondes ou un lien Google Maps."
                        d.gps.isBlank() -> "Vide : la position sera calculée depuis l'adresse (Base Adresse Nationale)."
                        d.gps.trim() != gpsTexte(ancien) -> "Coordonnées saisies : elles seront utilisées telles quelles."
                        else -> "Précision : " + (Geo.precisionDe(ancien?.gpsPrecision) ?: Geo.Precision.INCONNUE).libelle +
                            (ancien?.gpsTrouve?.takeIf { it.isNotBlank() && ancien.gpsPrecision != Geo.Precision.MANUELLE.name }?.let { " — $it" } ?: "")
                    },
                    color = if (lisible) HudDim else Color(0xFFFF4D6D), fontSize = 11.sp
                )
                HudTextField(d.kwc, { sites[i] = d.copy(kwc = it) }, Modifier.fillMaxWidth(), label = "Puissance (kWc)")
                HudTextField(d.mes, { sites[i] = d.copy(mes = it) }, Modifier.fillMaxWidth(), label = "Mise en service (aaaa-mm-jj)")
                HudTextField(d.onduleur, { sites[i] = d.copy(onduleur = it) }, Modifier.fillMaxWidth(), label = "Onduleur")
                HudTextField(d.panneaux, { sites[i] = d.copy(panneaux = it) }, Modifier.fillMaxWidth(), label = "Nombre de panneaux")
                HudTextField(d.notes, { sites[i] = d.copy(notes = it) }, Modifier.fillMaxWidth(), label = "Notes", singleLine = false)
                TextButton({ sites.removeAt(i) }) { Text("Supprimer cette installation", color = Color(0xFFFF4D6D), fontSize = 12.sp) }
            }
        }
        HudButton("Ajouter une installation", icon = Icons.Filled.Add) {
            sites += SiteDraft(java.util.UUID.randomUUID().toString().take(8), "Installation ${sites.size + 1}", "", "", "", "", "", "", "")
        }
    }
}

// ---------------------------------------------------------------------- Devis / Factures

@Composable
private fun DocsTab(app: JarvisApp, type: String) {
    val svc = app.societe
    val d = svc.data
    var selected by remember { mutableStateOf<String?>(null) }
    var editingDoc by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    var confirmSuppr by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val list = d.docs.filter { it.type == type }.sortedByDescending { it.numero }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (type == "devis") "Dis « Fais un devis maintenance pour M. Dupont », ou touche + pour en créer un manuellement."
                    else "Dis « Convertis le devis DEV-… en facture », ou touche + pour une facture directe.", color = HudDim, fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                HudRoundButton(Icons.Filled.Add) { creating = true }
            }
        }
        if (info.isNotBlank()) item { Text(info, color = LocalAccent.current, fontSize = 12.sp) }
        if (list.isEmpty()) item { Text("Aucun document.", color = HudDim, fontSize = 13.sp) }
        items(list, key = { it.numero }) { x ->
            HudPanel(Modifier.fillMaxWidth(), onClick = { selected = x.numero }) {
                Text("${x.numero} — ${d.client(x.clientId)?.nom.orEmpty()}", color = HudText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("${dateFr(x.date)} · ${euro(d.totaux(x).ttc)} TTC · ${Rendu.statutLabel(x.type, x.statut)}", color = HudDim, fontSize = 12.sp)
            }
        }
    }

    selected?.let { num ->
        val x = d.doc(num)
        if (x == null) selected = null else {
            val md = Rendu.rendreDoc(d, x)
            Dialogue(x.numero, { selected = null }, actions = {
                Action("PDF") { svc.partagerPdf(x.numero, md, avecLogo = true) }
                Action("Obsidian") { info = "📁 " + svc.exporter(x); selected = null }
                if (x.type == "devis" && x.statut in setOf("brouillon", "envoye"))
                    Action("✅ Valider") { svc.changerStatut(x.numero, "accepte"); info = "✅ ${x.numero} accepté"; selected = null }
                if (x.type == "devis" && x.statut in setOf("brouillon", "envoye", "accepte"))
                    Action("→ Facture") { svc.convertirEnFacture(x).onSuccess { info = "✅ ${it.doc.numero} créée" }.onFailure { info = it.message ?: "" }; selected = null }
                if (x.type == "facture" && x.statut == "a_payer")
                    Action("Payée") {
                        val r = svc.marquerPayee(x.numero, "")
                        info = "✅ Payée" + (r?.second?.let { " · réserve ${euro(it.montant)}" } ?: ""); selected = null
                    }
                if (x.statut in setOf("brouillon", "envoye", "accepte", "a_payer")) Action("✎ Modifier") { editingDoc = x.numero; selected = null }
                if (x.statut != "annulee" && x.statut != "refuse" && x.statut != "facture") Action("Annuler") { svc.annuler(x.numero); selected = null }
                Action("🗑 Supprimer") { confirmSuppr = x.numero; selected = null }
            }) { MarkdownText(md) }
        }
    }

    confirmSuppr?.let { num ->
        val x = d.doc(num)
        if (x == null) confirmSuppr = null else Dialogue("Supprimer ${x.numero} ?", { confirmSuppr = null }, actions = {
            Action("Supprimer définitivement") {
                svc.supprimerDocument(num)
                info = "🗑️ ${x.numero} supprimé."
                confirmSuppr = null
            }
        }) {
            Text("Cette action est irréversible : le document sera totalement effacé (y compris son export dans Obsidian/PDF).", color = HudText, fontSize = 13.sp)
            if (x.type == "facture") Text(
                "⚠️ Une facture émise doit légalement garder une numérotation continue. La supprimer laisse un trou dans la séquence. Pour une facture déjà envoyée ou encaissée, préfère « Annuler » (qui conserve le numéro) — la suppression n'est vraiment indiquée que pour une erreur jamais envoyée.",
                color = Color(0xFFFF4D6D), fontSize = 12.sp
            )
        }
    }

    editingDoc?.let { num ->
        val x = d.doc(num)
        if (x == null) editingDoc = null else DocEditor(x, onClose = { editingDoc = null }) { nouveau ->
            svc.repo.update { d0 -> d0.copy(docs = d0.docs.map { if (it.numero == nouveau.numero) nouveau else it }) }
            info = "✅ ${nouveau.numero} modifié · " + svc.exporter(nouveau)
            editingDoc = null
        }
    }
    if (creating) DocCreator(app, type, onClose = { creating = false }) { client, site, lignes, deplacement, notes ->
        scope.launch {
            info = "⏳ Création…"
            val r = runCatching { svc.creerDocument(type, client, site, emptyList(), lignes, deplacement, null, notes) }
            info = r.fold(
                { c -> "✅ ${c.doc.numero} créé" + (c.avertissements.takeIf { it.isNotEmpty() }?.joinToString("") { " · $it" } ?: "") },
                { e -> "❌ ${e.message ?: e.javaClass.simpleName}" }
            )
            creating = false
        }
    }
}

@Composable
private fun DocCreator(app: JarvisApp, type: String, onClose: () -> Unit, onCreate: (Client, Site?, List<Ligne>, Boolean, String) -> Unit) {
    val svc = app.societe
    val d = svc.data
    var query by remember { mutableStateOf("") }
    var client by remember { mutableStateOf<Client?>(null) }
    var site by remember { mutableStateOf<Site?>(null) }
    var deplacement by remember { mutableStateOf(Pricing.deplacementConfigure(d.regles)) }
    var notes by remember { mutableStateOf("") }
    var tarifPicker by remember { mutableStateOf(false) }
    val lignes = remember { androidx.compose.runtime.mutableStateListOf<LigneDraft>() }
    var err by remember { mutableStateOf("") }
    fun n(t: String) = t.replace(',', '.').trim().toDoubleOrNull()
    val matches = if (client != null || query.isBlank()) emptyList() else d.clients.filter { normaliser(it.nom).contains(normaliser(query)) }.take(6)

    Dialogue(if (type == "devis") "Nouveau devis" else "Nouvelle facture", onClose, actions = {
        Action("Créer") {
            val c = client
            if (c == null) { err = "Choisis un client." }
            else if (lignes.isEmpty()) { err = "Ajoute au moins une ligne (ou une prestation depuis un tarif)." }
            else {
                val ls = lignes.map { l -> Ligne(l.des.trim().ifBlank { "Prestation" }, n(l.qte) ?: 1.0, l.unite.trim(), n(l.pu) ?: 0.0, 0.0, n(l.tva) ?: d.societe.tvaDefaut, "prestation") }
                onCreate(c, site, ls, deplacement, notes)
            }
        }
    }) {
        if (client == null) {
            HudTextField(query, { query = it }, Modifier.fillMaxWidth(), label = "Rechercher un client…")
            matches.forEach { c -> HudPanel(Modifier.fillMaxWidth(), onClick = { client = c; query = c.nom }) { Text(c.nom, color = HudText, fontSize = 13.sp) } }
            if (d.clients.isEmpty()) Text("Aucun client enregistré — crée-le d'abord dans l'onglet Clients.", color = HudDim, fontSize = 12.sp)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("👤 ${client!!.nom}", color = HudText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                TextButton({ client = null; site = null; query = "" }) { Text("Changer", color = LocalAccent.current, fontSize = 12.sp) }
            }
            if (client!!.sites.isNotEmpty()) {
                Text("Installation (optionnel)", color = HudDim, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudChip("Aucune", site == null) { site = null }
                    client!!.sites.forEach { s -> HudChip(s.nom.ifBlank { "Installation" }, site?.id == s.id) { site = s } }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudChip("+ frais de déplacement", deplacement) { deplacement = !deplacement }
        }
        Text("Lignes", color = LocalAccent.current, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        lignes.forEachIndexed { i, l ->
            HudPanel(Modifier.fillMaxWidth()) {
                HudTextField(l.des, { lignes[i] = l.copy(des = it) }, Modifier.fillMaxWidth(), label = "Désignation")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudTextField(l.qte, { lignes[i] = l.copy(qte = it) }, Modifier.weight(1f), label = "Qté")
                    HudTextField(l.unite, { lignes[i] = l.copy(unite = it) }, Modifier.weight(1f), label = "Unité")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudTextField(l.pu, { lignes[i] = l.copy(pu = it) }, Modifier.weight(1f), label = "PU HT €")
                    HudTextField(l.tva, { lignes[i] = l.copy(tva = it) }, Modifier.weight(1f), label = "TVA %")
                }
                TextButton({ lignes.removeAt(i) }) { Text("Supprimer la ligne", color = Color(0xFFFF4D6D), fontSize = 12.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton("Ligne libre", icon = Icons.Filled.Add) { lignes += LigneDraft("", "1", "", "", "0", nombre(d.societe.tvaDefaut), "prestation") }
            if (d.tarifs.isNotEmpty()) HudButton("Depuis un tarif") { tarifPicker = true }
        }
        HudTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = "Notes", singleLine = false)
        if (err.isNotBlank()) Text(err, color = Color(0xFFFF4D6D), fontSize = 12.sp)
    }

    if (tarifPicker) {
        Dialogue("Choisir un tarif", { tarifPicker = false }) {
            d.tarifs.forEach { t ->
                HudPanel(Modifier.fillMaxWidth(), onClick = {
                    val kwc = site?.kwc ?: 0.0
                    val p = Pricing.prix(t, kwc, 1.0)
                    val pu = p.puHt
                    if (pu == null) err = p.avertissement
                    else lignes.add(LigneDraft(t.designation + (if (kwc > 0) " – ${nombre(kwc)} kWc" else ""), nombre(p.qte), p.unite, nombre(pu), "0", nombre(t.tva ?: d.societe.tvaDefaut), "prestation"))
                    tarifPicker = false
                }) {
                    Text(t.designation, color = HudText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("${t.mode.name.lowercase()} — ${Pricing.libelleTranches(t)}", color = HudDim, fontSize = 12.sp)
                }
            }
        }
    }
}

private data class LigneDraft(val des: String, val qte: String, val unite: String, val pu: String, val remise: String, val tva: String, val genre: String)

@Composable
private fun DocEditor(doc: Doc, onClose: () -> Unit, onSave: (Doc) -> Unit) {
    var date by remember { mutableStateOf(doc.date) }
    var echeance by remember { mutableStateOf(doc.echeance) }
    var notes by remember { mutableStateOf(doc.notes) }
    var remiseInfo by remember { mutableStateOf(doc.remiseInfo) }
    var statut by remember { mutableStateOf(doc.statut) }
    var err by remember { mutableStateOf("") }
    val lignes = remember {
        androidx.compose.runtime.mutableStateListOf<LigneDraft>().also { l ->
            doc.lignes.forEach { l += LigneDraft(it.designation, nombre(it.qte), it.unite, nombre(it.puHt), nombre(it.remisePct), nombre(it.tva), it.genre) }
        }
    }
    fun n(t: String) = t.replace(',', '.').trim().toDoubleOrNull()
    Dialogue("Modifier ${doc.numero}", onClose, actions = {
        Action("Enregistrer") {
            try {
                LocalDate.parse(date.trim()); if (echeance.isNotBlank()) LocalDate.parse(echeance.trim())
                if (lignes.isEmpty()) { err = "Au moins une ligne." } else {
                    val ls = lignes.map { l ->
                        Ligne(l.des.trim().ifBlank { "Prestation" }, n(l.qte) ?: 1.0, l.unite.trim(), n(l.pu) ?: 0.0, (n(l.remise) ?: 0.0).coerceIn(0.0, 100.0), n(l.tva) ?: 20.0, l.genre)
                    }
                    onSave(doc.copy(date = date.trim(), echeance = echeance.trim(), notes = notes, remiseInfo = remiseInfo, lignes = ls, statut = statut))
                }
            } catch (e: Exception) { err = "Dates au format aaaa-mm-jj." }
        }
    }) {
        if (doc.type == "facture") Text("⚠️ Facture émise : une modification doit rester exceptionnelle (préférer une annulation puis une nouvelle facture).", color = Color(0xFFFFB020), fontSize = 12.sp)
        HudTextField(date, { date = it }, Modifier.fillMaxWidth(), label = "Date (aaaa-mm-jj)")
        HudTextField(echeance, { echeance = it }, Modifier.fillMaxWidth(), label = if (doc.type == "devis") "Valable jusqu'au" else "Échéance")
        if (doc.type == "devis") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("brouillon", "envoye", "accepte").forEach { st -> HudChip(Rendu.statutLabel("devis", st), statut == st) { statut = st } }
            }
        }
        HudTextField(remiseInfo, { remiseInfo = it }, Modifier.fillMaxWidth(), label = "Remise (mention affichée)")
        HudTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = "Notes", singleLine = false)
        Text("Lignes", color = LocalAccent.current, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        lignes.forEachIndexed { i, l ->
            HudPanel(Modifier.fillMaxWidth()) {
                HudTextField(l.des, { lignes[i] = l.copy(des = it) }, Modifier.fillMaxWidth(), label = "Désignation")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudTextField(l.qte, { lignes[i] = l.copy(qte = it) }, Modifier.weight(1f), label = "Qté")
                    HudTextField(l.unite, { lignes[i] = l.copy(unite = it) }, Modifier.weight(1f), label = "Unité")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudTextField(l.pu, { lignes[i] = l.copy(pu = it) }, Modifier.weight(1f), label = "PU HT €")
                    HudTextField(l.remise, { lignes[i] = l.copy(remise = it) }, Modifier.weight(1f), label = "Remise %")
                    HudTextField(l.tva, { lignes[i] = l.copy(tva = it) }, Modifier.weight(1f), label = "TVA %")
                }
                TextButton({ lignes.removeAt(i) }) { Text("Supprimer la ligne", color = Color(0xFFFF4D6D), fontSize = 12.sp) }
            }
        }
        HudButton("Ajouter une ligne", icon = Icons.Filled.Add) { lignes += LigneDraft("", "1", "", "", "0", "20", "prestation") }
        if (err.isNotBlank()) Text(err, color = Color(0xFFFF4D6D), fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------------- Équipe (employés + dirigeant, base contractuelle)

@Composable
private fun EquipeTab(app: JarvisApp) {
    val svc = app.societe
    val d = svc.data
    var bilan by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editEmp by remember { mutableStateOf<Employe?>(null) }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "Équipe") {
                Text("Déclare ici chaque salarié (ou toi-même en dirigeant) avec sa base contractuelle : 35h/39h/40h et le salaire net mensuel visé (ou brut). C'est cette base qui sert ensuite pour la création des fiches de paie.", color = HudDim, fontSize = 12.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton("Ajouter", icon = Icons.Filled.Add) { adding = true }
                HudButton("Bilan réserve") { bilan = svc.bilanTexte(LocalDate.now().year) }
            }
        }
        if (d.employes.isEmpty()) item { Text("Aucun employé. Le dirigeant se déclare aussi ici (statut dirigeant).", color = HudDim, fontSize = 13.sp) }
        items(d.employes, key = { it.id }) { e ->
            HudPanel(Modifier.fillMaxWidth(), onClick = { editEmp = e }) {
                Text("${e.nom} (${e.statut})", color = HudText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "${e.poste.ifBlank { "—" }} · " + (if (e.netMensuelVise > 0) "net visé ${euro(e.netMensuelVise)}/mois" else "brut ${euro(e.brutMensuel)}/mois") +
                        " · contrat ${nombre(e.heuresHebdo)} h/semaine",
                    color = HudDim, fontSize = 12.sp
                )
                TextButton({ editEmp = e }) { Text("✎ Modifier", color = LocalAccent.current, fontSize = 12.sp) }
            }
        }
    }
    bilan?.let { md -> Dialogue("Réserve", { bilan = null }) { MarkdownText(md) } }
    if (adding) EmployeForm(app, null, d.employes.none { it.statut == "dirigeant" }, { adding = false }) { e ->
        svc.repo.update { it.copy(employes = it.employes + e) }; adding = false
    }
    editEmp?.let { e0 ->
        EmployeForm(app, e0, e0.statut == "dirigeant", { editEmp = null }) { e ->
            svc.repo.update { x -> x.copy(employes = x.employes.map { if (it.id == e.id) e else it }) }; editEmp = null
        }
    }
}

// ---------------------------------------------------------------------- Fiches de paie (création d'un bulletin pour un mois)

@Composable
private fun FichesPaieTab(app: JarvisApp) {
    val svc = app.societe
    val d = svc.data
    var empId by remember { mutableStateOf(d.employes.firstOrNull()?.id) }
    var mois by remember { mutableStateOf(LocalDate.now().toString().take(7)) }
    var mode by remember { mutableStateOf(0) } // 0 = standard (contrat), 1 = heures effectuées, 2 = net versé
    var saisie by remember { mutableStateOf("") }
    var primes by remember { mutableStateOf("0") }
    var bulletin by remember { mutableStateOf<String?>(null) }
    var erreur by remember { mutableStateOf("") }
    fun n(t: String) = t.replace(',', '.').trim().toDoubleOrNull()
    val e = d.employes.firstOrNull { it.id == empId }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "Création d'une fiche de paie") {
                Text("⚠️ Estimation simplifiée (taux standards, modifiables dans Réglages). À faire valider par un expert-comptable ou un logiciel de paie.", color = HudDim, fontSize = 12.sp)
            }
        }
        if (d.employes.isEmpty()) {
            item { Text("Aucun employé déclaré. Ajoute d'abord ton équipe dans l'onglet Équipe.", color = HudDim, fontSize = 13.sp) }
        } else {
            item {
                HudPanel(Modifier.fillMaxWidth(), title = "Qui et quand") {
                    Text("Salarié / dirigeant", color = HudDim, fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.employes.forEach { emp -> HudChip(emp.nom, selected = emp.id == empId) { empId = emp.id; bulletin = null; erreur = "" } }
                    }
                    Spacer(Modifier.height(6.dp))
                    HudTextField(mois, { mois = it; bulletin = null }, Modifier.fillMaxWidth(), label = "Mois (aaaa-mm)")
                }
            }
            if (e != null) item {
                HudPanel(Modifier.fillMaxWidth(), title = "Ce mois-ci") {
                    Text("Base contractuelle : ${nombre(e.heuresHebdo)} h/semaine.", color = HudDim, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HudChip("Heures standard", mode == 0) { mode = 0; saisie = ""; bulletin = null }
                        HudChip("Heures effectuées", mode == 1) { mode = 1; bulletin = null }
                        HudChip("Net versé", mode == 2) { mode = 2; bulletin = null }
                    }
                    Spacer(Modifier.height(6.dp))
                    when (mode) {
                        1 -> HudTextField(saisie, { saisie = it; bulletin = null }, Modifier.fillMaxWidth(), label = "Heures réellement travaillées ce mois")
                        2 -> HudTextField(saisie, { saisie = it; bulletin = null }, Modifier.fillMaxWidth(), label = "Montant net réellement versé ce mois (€)")
                        else -> Text("Heures contractuelles du mois, sans heure supplémentaire.", color = HudDim, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    HudTextField(primes, { primes = it; bulletin = null }, Modifier.fillMaxWidth(), label = "Primes du mois (€, optionnel)")
                    if (erreur.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(erreur, color = Color(0xFFFF4D6D), fontSize = 12.sp) }
                    Spacer(Modifier.height(8.dp))
                    HudButton("Générer la fiche de paie") {
                        if (!Regex("\\d{4}-\\d{2}").matches(mois)) { erreur = "Mois invalide (format aaaa-mm)."; return@HudButton }
                        if (mode != 0 && n(saisie) == null) { erreur = "Renseigne une valeur valide."; return@HudButton }
                        erreur = ""
                        val heuresEff = if (mode == 1) n(saisie) else null
                        val netV = if (mode == 2) n(saisie) else null
                        bulletin = svc.bulletin(e, mois, n(primes) ?: 0.0, heuresEff, netV).first
                    }
                }
            }
            bulletin?.let { md ->
                item {
                    HudPanel(Modifier.fillMaxWidth(), title = "Bulletin généré") {
                        MarkdownText(md)
                        Spacer(Modifier.height(8.dp))
                        HudButton("Partager en PDF") { svc.partagerPdf("Bulletin $mois", md) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmployeForm(app: JarvisApp, initial: Employe?, dirDefaut: Boolean, onClose: () -> Unit, onSave: (Employe) -> Unit) {
    val cfg = app.societe.data.paie
    var nom by remember { mutableStateOf(initial?.nom ?: "") }
    var poste by remember { mutableStateOf(initial?.poste ?: "") }
    var dir by remember { mutableStateOf(dirDefaut) }
    var saisieNet by remember { mutableStateOf((initial?.netMensuelVise ?: 0.0) > 0) }
    var montant by remember { mutableStateOf(initial?.let { if (it.netMensuelVise > 0) nombre(it.netMensuelVise) else nombre(it.brutMensuel) } ?: "") }
    var heures by remember { mutableStateOf(nombre(initial?.heuresHebdo ?: 35.0)) }
    var pas by remember { mutableStateOf(initial?.let { nombre(it.tauxPas) } ?: "0") }
    fun n(t: String) = t.replace(',', '.').trim().toDoubleOrNull()
    val montantNum = n(montant) ?: 0.0
    val apercu = if (montantNum > 0.0) {
        if (saisieNet) "≈ brut ${euro(Paie.brutDepuisNet(montantNum, dir, cfg))}/mois" else "≈ net avant impôt ${euro(Paie.netAvantImpotPour(montantNum, dir, cfg))}/mois"
    } else ""
    Dialogue(if (initial == null) "Nouvel employé" else "Modifier ${initial.nom}", onClose, actions = {
        Action(if (initial == null) "Créer" else "Enregistrer") {
            if (nom.isNotBlank()) {
                val id = initial?.id ?: java.util.UUID.randomUUID().toString().take(8)
                val statut = if (dir) "dirigeant" else "salarie"
                val brutCalc = if (saisieNet) Paie.brutDepuisNet(montantNum, dir, cfg) else montantNum
                onSave(Employe(
                    id = id, nom = nom.trim(), poste = poste, statut = statut,
                    brutMensuel = brutCalc, netMensuelVise = if (saisieNet) montantNum else 0.0,
                    heuresHebdo = n(heures) ?: 35.0, tauxPas = n(pas) ?: 0.0,
                    dateEntree = initial?.dateEntree ?: LocalDate.now().toString()
                ))
            }
        }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { HudChip("Dirigeant", dir) { dir = true }; HudChip("Salarié", !dir) { dir = false } }
        HudTextField(nom, { nom = it }, Modifier.fillMaxWidth(), label = "Nom")
        HudTextField(poste, { poste = it }, Modifier.fillMaxWidth(), label = "Poste")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudChip("Saisie en brut", !saisieNet) { saisieNet = false }
            HudChip("Saisie en net visé", saisieNet) { saisieNet = true }
        }
        HudTextField(montant, { montant = it }, Modifier.fillMaxWidth(), label = if (saisieNet) "Net mensuel visé (€, avant impôt)" else "Brut mensuel (€)")
        if (apercu.isNotBlank()) Text(apercu, color = LocalAccent.current, fontSize = 12.sp)
        if (saisieNet) Text("Le brut est recalculé automatiquement à chaque bulletin selon les cotisations réelles (il peut légèrement varier si les taux changent).", color = HudDim, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudChip("35 h", heures == "35") { heures = "35" }
            HudChip("39 h", heures == "39") { heures = "39" }
            HudChip("40 h", heures == "40") { heures = "40" }
        }
        HudTextField(heures, { heures = it }, Modifier.fillMaxWidth(), label = "Base horaire hebdomadaire (h)")
        Text("= ${nombre(Paie.heuresMensuelles(n(heures) ?: 35.0))} h/mois" + (if (montantNum > 0 && !saisieNet) " · taux horaire ≈ ${euro(round2(montantNum / Paie.heuresMensuelles(n(heures) ?: 35.0)))}" else ""), color = HudDim, fontSize = 11.sp)
        HudTextField(pas, { pas = it }, Modifier.fillMaxWidth(), label = "Prélèvement à la source (%)")
    }
}

// ---------------------------------------------------------------------- Réglages société

@Composable
private fun ReglagesTab(app: JarvisApp) {
    val svc = app.societe
    val d = svc.data
    val s = d.societe
    var nom by remember { mutableStateOf(s.nom) }
    var forme by remember { mutableStateOf(s.forme) }
    var siret by remember { mutableStateOf(s.siret) }
    var tvaNum by remember { mutableStateOf(s.tvaNum) }
    var adresse by remember { mutableStateOf(s.adresse) }
    var tel by remember { mutableStateOf(s.tel) }
    var email by remember { mutableStateOf(s.email) }
    var iban by remember { mutableStateOf(s.iban) }
    var bic by remember { mutableStateOf(s.bic) }
    var tva by remember { mutableStateOf(nombre(s.tvaDefaut)) }
    var franchise by remember { mutableStateOf(s.franchiseTva) }
    var tarifsTtc by remember { mutableStateOf(s.tarifsSaisisTtc) }
    var delai by remember { mutableStateOf(s.delaiPaiementJours.toString()) }
    var validite by remember { mutableStateOf(s.validiteDevisJours.toString()) }
    var mentions by remember { mutableStateOf(s.mentions) }
    var reserve by remember { mutableStateOf(nombre(s.reservePct)) }
    var partDir by remember { mutableStateOf(nombre(s.partDirigeantPct)) }
    var saved by remember { mutableStateOf("") }
    fun num(t: String) = t.replace(',', '.').trim().toDoubleOrNull()

    val r = d.regles
    var fid by remember { mutableStateOf(r.remiseFidelite.joinToString("; ") { "${it.seuil}:${nombre(it.pct)}" }) }
    var vol by remember { mutableStateOf(r.remiseVolume.joinToString("; ") { "${it.seuil}:${nombre(it.pct)}" }) }
    var cumul by remember { mutableStateOf(r.remiseCumul) }
    var rmax by remember { mutableStateOf(nombre(r.remiseMax)) }
    var kmPrix by remember { mutableStateOf(nombre(r.kmPrix)) }
    var kmGratuit by remember { mutableStateOf(nombre(r.kmGratuits)) }
    var depMin by remember { mutableStateOf(nombre(r.deplacementMin)) }
    var ar by remember { mutableStateOf(r.allerRetour) }
    var cadre by remember { mutableStateOf(d.paie.cadre) }
    var plafondSecu by remember { mutableStateOf(nombre(d.paie.plafondSecu)) }
    var tauxAtmp by remember { mutableStateOf(nombre(d.paie.tauxAtmp)) }
    var tauxFormation by remember { mutableStateOf(nombre(d.paie.tauxFormationPct)) }
    var tarifEdit by remember { mutableStateOf(false) }
    var tarifInit by remember { mutableStateOf<Tarif?>(null) }

    val ctx = LocalContext.current
    var logoVersion by remember { mutableStateOf(0) }
    val logoBmp = remember(logoVersion) { fr.dd.jarvis.societe.Logo.bitmap(ctx) }
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()?.let { bytes ->
                if (fr.dd.jarvis.societe.Logo.enregistrer(ctx, bytes)) logoVersion++
            }
        }
    }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "Logo (devis et factures)") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (logoBmp != null) {
                        Image(logoBmp.asImageBitmap(), contentDescription = "Logo", modifier = Modifier.height(90.dp))
                        Text("${logoBmp.width} × ${logoBmp.height} px — qualité d'origine conservée, redimensionné uniquement à l'affichage.", color = HudDim, fontSize = 11.sp)
                    } else {
                        Text("Aucun logo. Ajoute une image nette (PNG de préférence, fond transparent si possible) : elle apparaîtra en haut des PDF de devis et factures.", color = HudDim, fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HudButton(if (logoBmp != null) "Changer le logo" else "Ajouter un logo") { logoPicker.launch("image/*") }
                        if (logoBmp != null) HudButton("Retirer") { fr.dd.jarvis.societe.Logo.supprimer(ctx); logoVersion++ }
                    }
                }
            }
        }
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "Société") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudTextField(nom, { nom = it }, Modifier.fillMaxWidth(), label = "Nom")
                    HudTextField(forme, { forme = it }, Modifier.fillMaxWidth(), label = "Forme (EURL, SASU, micro…)")
                    HudTextField(siret, { siret = it }, Modifier.fillMaxWidth(), label = "SIRET")
                    HudTextField(tvaNum, { tvaNum = it }, Modifier.fillMaxWidth(), label = "N° TVA intracommunautaire")
                    HudTextField(adresse, { adresse = it }, Modifier.fillMaxWidth(), label = "Adresse du siège (départ des déplacements)")
                    HudTextField(tel, { tel = it }, Modifier.fillMaxWidth(), label = "Téléphone")
                    HudTextField(email, { email = it }, Modifier.fillMaxWidth(), label = "Email")
                    HudTextField(iban, { iban = it }, Modifier.fillMaxWidth(), label = "IBAN")
                    HudTextField(bic, { bic = it }, Modifier.fillMaxWidth(), label = "BIC")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HudChip("TVA applicable", !franchise) { franchise = false }
                        HudChip("Franchise TVA", franchise) { franchise = true }
                    }
                    HudTextField(tva, { tva = it }, Modifier.fillMaxWidth(), label = "TVA par défaut (%)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HudChip("Tarifs saisis en HT", !tarifsTtc) { tarifsTtc = false }
                        HudChip("Tarifs saisis en TTC", tarifsTtc) { tarifsTtc = true }
                    }
                    if (tarifsTtc) Text("Les prix que tu saisis pour les tarifs (ci-dessous) sont compris comme TTC et convertis en HT automatiquement selon la TVA.", color = HudDim, fontSize = 11.sp)
                    HudTextField(delai, { delai = it }, Modifier.fillMaxWidth(), label = "Délai de paiement (jours)")
                    HudTextField(validite, { validite = it }, Modifier.fillMaxWidth(), label = "Validité des devis (jours)")
                    HudTextField(mentions, { mentions = it }, Modifier.fillMaxWidth(), label = "Mentions légales (assurance, RCS…)", singleLine = false)
                    HudTextField(reserve, { reserve = it }, Modifier.fillMaxWidth(), label = "Réserve bénéfices (% du HT encaissé)")
                    HudTextField(partDir, { partDir = it }, Modifier.fillMaxWidth(), label = "Part dirigeant de la réserve (%)")
                }
            }
        }
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "Remises et déplacement") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudTextField(fid, { fid = it }, Modifier.fillMaxWidth(), label = "Fidélité : années:% (ex. 10:5; 5:3)")
                    HudTextField(vol, { vol = it }, Modifier.fillMaxWidth(), label = "Volume : installations:% (ex. 3:2; 5:4)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HudChip("Cumulables", cumul) { cumul = true }
                        HudChip("La plus forte", !cumul) { cumul = false }
                    }
                    HudTextField(rmax, { rmax = it }, Modifier.fillMaxWidth(), label = "Plafond de remise (%, 0 = aucun)")
                    HudTextField(kmPrix, { kmPrix = it }, Modifier.fillMaxWidth(), label = "Prix du km (€)")
                    HudTextField(kmGratuit, { kmGratuit = it }, Modifier.fillMaxWidth(), label = "Km offerts")
                    HudTextField(depMin, { depMin = it }, Modifier.fillMaxWidth(), label = "Déplacement minimum (€)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HudChip("Aller-retour", ar) { ar = true }
                        HudChip("Aller simple", !ar) { ar = false }
                    }
                }
            }
        }
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "Paie (bulletins détaillés)") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Taux standards du régime général déjà appliqués (sécurité sociale, retraite, chômage, CSG/CRDS…). Ajuste ici ce qui dépend de ton activité ou de ton équipe.", color = HudDim, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HudChip("Non-cadre", !cadre) { cadre = false }
                        HudChip("Cadre", cadre) { cadre = true }
                    }
                    HudTextField(plafondSecu, { plafondSecu = it }, Modifier.fillMaxWidth(), label = "Plafond mensuel Sécurité sociale (PMSS, €)")
                    HudTextField(tauxAtmp, { tauxAtmp = it }, Modifier.fillMaxWidth(), label = "Taux accident du travail / maladies pro (%)")
                    HudTextField(tauxFormation, { tauxFormation = it }, Modifier.fillMaxWidth(), label = "Contribution formation professionnelle (%)")
                }
            }
        }
        item {
            HudButton("Enregistrer", filled = true) {
                svc.repo.update {
                    it.copy(
                        societe = it.societe.copy(
                            nom = nom.trim(), forme = forme.trim(), siret = siret.trim(), tvaNum = tvaNum.trim(),
                            adresse = adresse.trim(), tel = tel.trim(), email = email.trim(), iban = iban.trim(), bic = bic.trim(),
                            lat = if (adresse.trim() == it.societe.adresse) it.societe.lat else null,
                            lon = if (adresse.trim() == it.societe.adresse) it.societe.lon else null,
                            gpsPrecision = if (adresse.trim() == it.societe.adresse) it.societe.gpsPrecision else "",
                            franchiseTva = franchise, tvaDefaut = num(tva) ?: 20.0, tarifsSaisisTtc = tarifsTtc,
                            delaiPaiementJours = delai.toIntOrNull() ?: 30, validiteDevisJours = validite.toIntOrNull() ?: 30,
                            mentions = mentions, reservePct = (num(reserve) ?: 0.0).coerceIn(0.0, 100.0),
                            partDirigeantPct = (num(partDir) ?: 50.0).coerceIn(0.0, 100.0)
                        ),
                        regles = Regles(
                            Pricing.parserSeuils(fid), Pricing.parserSeuils(vol), cumul, num(rmax) ?: 0.0,
                            it.regles.remiseSurDeplacement, num(kmPrix) ?: 0.0, num(kmGratuit) ?: 0.0, num(depMin) ?: 0.0, ar
                        ),
                        paie = it.paie.copy(
                            cadre = cadre, plafondSecu = num(plafondSecu) ?: 3925.0,
                            tauxAtmp = num(tauxAtmp) ?: 1.2, tauxFormationPct = num(tauxFormation) ?: 0.55
                        )
                    )
                }
                saved = "✅ Enregistré"
            }
            if (saved.isNotBlank()) Text(saved, color = LocalAccent.current, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "Tarifs par puissance") {
                if (d.tarifs.isEmpty()) Text("Aucun tarif. Ajoute-en ici ou dis-les à JARVIS.", color = HudDim, fontSize = 12.sp)
                d.tarifs.forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(t.designation, color = HudText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("${t.mode.name.lowercase()} — ${Pricing.libelleTranches(t)}", color = HudDim, fontSize = 12.sp)
                        }
                        TextButton({ tarifInit = t; tarifEdit = true }) { Text("✎", color = LocalAccent.current, fontSize = 14.sp) }
                        TextButton({ svc.repo.update { x -> x.copy(tarifs = x.tarifs.filterNot { it.designation == t.designation }) } }) { Text("Suppr.", color = Color(0xFFFF4D6D), fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                HudButton("Ajouter un tarif", icon = Icons.Filled.Add) { tarifInit = null; tarifEdit = true }
            }
        }
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "Présentations") {
                Text("Dis à JARVIS « change la présentation des factures… ». Ici tu peux revenir au modèle d'origine.", color = HudDim, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("client", "devis", "facture", "paie", "planning").forEach { c ->
                        HudChip(if (d.modeles.containsKey(c)) "$c ✎ ↺" else c, d.modeles.containsKey(c)) {
                            if (d.modeles.containsKey(c)) {
                                svc.repo.update { x -> x.copy(modeles = x.modeles - c, modelesPrec = x.modelesPrec + (c to x.modeles.getValue(c))) }
                                if (c == "planning") Prompts.planningFormat = Templates.PLANNING_FORMAT_DEFAUT
                            }
                        }
                    }
                }
            }
        }
    }

    if (tarifEdit) {
        val ini = tarifInit
        var des by remember { mutableStateOf(ini?.designation ?: "") }
        var mode by remember { mutableStateOf(ini?.mode ?: Mode.FORFAIT) }
        var tr by remember { mutableStateOf(ini?.tranches?.sortedBy { it.jusquaKwc }?.joinToString("; ") { (if (it.jusquaKwc >= KWC_MAX) "+" else nombre(it.jusquaKwc)) + ":" + nombre(it.prix) } ?: "") }
        var tvaTarif by remember { mutableStateOf(ini?.tva?.let { nombre(it) } ?: "") }
        Dialogue(if (ini == null) "Nouveau tarif" else "Modifier le tarif", { tarifEdit = false }, actions = {
            Action("Enregistrer") {
                var t = Pricing.parserTranches(tr).ifEmpty { num(tr)?.let { listOf(Tranche(KWC_MAX, it)) } ?: emptyList() }
                val tvaUtilisee = num(tvaTarif) ?: s.tvaDefaut
                if (s.tarifsSaisisTtc && !s.franchiseTva) t = t.map { it.copy(prix = versHt(it.prix, tvaUtilisee, true)) }
                if (des.isNotBlank() && t.isNotEmpty()) {
                    svc.repo.update { it.copy(tarifs = it.tarifs.filterNot { x -> x.designation == des.trim() || x.designation == ini?.designation } + Tarif(des.trim(), mode, t, num(tvaTarif))) }
                    tarifEdit = false
                }
            }
        }) {
            HudTextField(des, { des = it }, Modifier.fillMaxWidth(), label = "Désignation")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Mode.values().forEach { m -> HudChip(m.name.lowercase(), m == mode) { mode = m } }
            }
            HudTextField(tr, { tr = it }, Modifier.fillMaxWidth(), label = "Prix ${if (s.tarifsSaisisTtc) "TTC" else "HT"} : 0-3:150; 3-9:220; 9+:350", singleLine = false)
            HudTextField(tvaTarif, { tvaTarif = it }, Modifier.fillMaxWidth(), label = "TVA de ce tarif (%, vide = ${nombre(s.tvaDefaut)} % par défaut)")
            if (s.tarifsSaisisTtc && !s.franchiseTva) Text("Les prix ci-dessus sont compris comme TTC et seront enregistrés en HT.", color = HudDim, fontSize = 11.sp)
        }
    }
}


/**
 * Produit un paquet de diagnostic explicite pour une fiche client : capture de l'écran
 * Jarvis + état réellement persisté dans la base Société. Le partage Android reste manuel.
 */
private fun partagerDiagnosticClient(
    context: android.content.Context,
    view: android.view.View,
    app: JarvisApp,
    clientSelectionne: Client
) {
    val client = app.societe.data.client(clientSelectionne.id) ?: clientSelectionne
    val data = app.societe.data
    val siege = data.societe
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "inconnue"
    }.getOrDefault("inconnue")
    val horodatage = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
        .format(java.util.Date())
    val dossier = java.io.File(context.cacheDir, "share").apply { mkdirs() }
    val image = java.io.File(dossier, "jarvis-client-$horodatage.png")
    val bitmap = android.graphics.Bitmap.createBitmap(
        view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888
    )
    view.draw(android.graphics.Canvas(bitmap))
    java.io.FileOutputStream(image).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()

    val rapport = buildString {
        appendLine("DIAGNOSTIC JARVIS — FICHE CLIENT")
        appendLine("Date : ${java.time.Instant.now()}")
        appendLine("Version : $version")
        appendLine("Android : ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("Appareil : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine()
        appendLine("SIÈGE")
        appendLine("Adresse : ${siege.adresse}")
        appendLine("GPS : ${if (siege.lat != null && siege.lon != null) "${siege.lat}, ${siege.lon}" else "absent"}")
        appendLine("Précision GPS : ${siege.gpsPrecision}")
        appendLine()
        appendLine("CLIENT")
        appendLine("Nom : ${client.nom}")
        appendLine("ID : ${client.id}")
        appendLine("Adresse client : ${client.adresse}")
        appendLine("Distance client enregistrée : ${client.distanceKm ?: "absente"} km")
        appendLine("Durée enregistrée : ${client.distanceDureeMin ?: "absente"} min")
        appendLine("Clé distance : ${client.distanceCle}")
        appendLine("Nombre d'installations : ${client.sites.size}")
        client.sites.forEachIndexed { index, site ->
            appendLine()
            appendLine("INSTALLATION ${index + 1}")
            appendLine("Nom : ${site.nom}")
            appendLine("ID : ${site.id}")
            appendLine("Adresse : ${site.adresse}")
            appendLine("GPS : ${if (site.lat != null && site.lon != null) "${site.lat}, ${site.lon}" else "absent"}")
            appendLine("Précision GPS : ${site.gpsPrecision}")
            appendLine("Source / adresse reconnue : ${site.gpsTrouve}")
            appendLine("Distance enregistrée : ${site.distanceKm ?: "absente"} km")
            appendLine("Durée enregistrée : ${site.dureeMin ?: "absente"} min")
            appendLine("Clé distance : ${site.distanceCle}")
        }
        val crash = java.io.File(context.filesDir, JarvisApp.CRASH_FILE)
        if (crash.isFile) {
            appendLine()
            appendLine("DERNIER PLANTAGE (extrait)")
            appendLine(crash.readText().takeLast(6000))
        }
        appendLine()
        appendLine("Capture écran incluse : capture_ecran.png")
        appendLine("Aucun message, email, contact ou autre fiche client n'est inclus.")
    }
    val archive = java.io.File(dossier, "jarvis-diagnostic-client-$horodatage.zip")
    java.util.zip.ZipOutputStream(java.io.FileOutputStream(archive)).use { zip ->
        zip.putNextEntry(java.util.zip.ZipEntry("diagnostic.txt"))
        zip.write(rapport.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(java.util.zip.ZipEntry("capture_ecran.png"))
        image.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.files", archive
    )
    val partage = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Diagnostic JARVIS — ${client.nom}")
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(partage, "Partager le diagnostic JARVIS"))
}
