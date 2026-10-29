package fr.dd.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Prompts
import fr.dd.jarvis.societe.core.*
import java.time.LocalDate

private val SUBS = listOf("Résumé", "Clients", "Devis", "Factures", "Paie", "Réglages")

@Composable
fun SocieteScreen(app: JarvisApp) {
    var sub by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SUBS.forEachIndexed { i, t -> HudChip(t, selected = i == sub) { sub = i } }
        }
        when (sub) {
            0 -> ResumeTab(app)
            1 -> ClientsTab(app)
            2 -> DocsTab(app, "devis")
            3 -> DocsTab(app, "facture")
            4 -> PaieTab(app)
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

@Composable
private fun ClientsTab(app: JarvisApp) {
    val svc = app.societe
    val d = svc.data
    var selected by remember { mutableStateOf<Client?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Client?>(null) }
    var query by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    val kmlScope = rememberCoroutineScope()
    val list = d.clients.filter { query.isBlank() || normaliser(it.nom).contains(normaliser(query)) }.sortedBy { normaliser(it.nom) }

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
                    info = runCatching { svc.exporterKml().lines().drop(2).joinToString("\n").trim() }.getOrElse { "❌ ${it.message}" }
                    svc.partagerKml()
                }
            }
        }
        if (info.isNotBlank()) item { Text(info, color = LocalAccent.current, fontSize = 12.sp) }
        if (list.isEmpty()) item { Text("Aucun client. Touche + ou demande à JARVIS.", color = HudDim, fontSize = 13.sp) }
        items(list, key = { it.id }) { c ->
            HudPanel(Modifier.fillMaxWidth(), onClick = { selected = c }) {
                Text(c.nom, color = HudText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("☀️ ${c.sites.size} installation(s) · ${nombre(c.sites.sumOf { it.kwc })} kWc · CA ${euro(d.caClient(c))}", color = HudDim, fontSize = 12.sp)
                if (c.adresse.isNotBlank()) Text("📍 ${c.adresse}", color = HudDim, fontSize = 12.sp)
            }
        }
    }

    selected?.let { c0 ->
        val c = d.client(c0.id) ?: c0
        val md = Rendu.rendreClient(d, c)
        Dialogue(c.nom, { selected = null }, actions = {
            Action("Obsidian") { info = "📁 " + svc.exporterClient(c).second; selected = null }
            Action("PDF") { svc.partagerPdf("Fiche ${c.nom}", md) }
            Action("✎ Modifier") { editing = c; selected = null }
        }) { MarkdownText(md) }
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
    val onduleur: String, val panneaux: String, val notes: String
)

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
            initial?.sites?.forEach { l += SiteDraft(it.id, it.nom, it.adresse, if (it.kwc > 0) nombre(it.kwc) else "", it.miseEnService, it.onduleur, if (it.nbPanneaux > 0) it.nbPanneaux.toString() else "", it.notes) }
        }
    }
    Dialogue(if (initial == null) "Nouveau client" else "Modifier ${initial.nom}", onClose, actions = {
        Action(if (initial == null) "Créer" else "Enregistrer") {
            if (nom.isNotBlank()) {
                val newSites = sites.map { d ->
                    val old = initial?.sites?.firstOrNull { it.id == d.id }
                    val adr = d.adresse.ifBlank { adresse }
                    val same = old != null && old.adresse == d.adresse
                    Site(
                        d.id, d.nom.ifBlank { "Installation" }, d.adresse, d.kwc.replace(',', '.').toDoubleOrNull() ?: 0.0,
                        d.mes, d.onduleur, d.panneaux.toIntOrNull() ?: 0, d.notes,
                        if (same) old?.lat else null, if (same) old?.lon else null,
                        if (same) old?.distanceKm else null, if (same) old?.dureeMin else null, if (same) old?.distanceCle ?: "" else ""
                    ).also { adr.length }
                }
                onSave(Client(
                    initial?.id ?: java.util.UUID.randomUUID().toString().take(8), nom.trim(), if (pro) "professionnel" else "particulier",
                    contact = contact, tel = tel, email = email, adresse = adresse, sites = newSites,
                    depuis = if (depuis.matches(Regex("\\d{4}"))) "$depuis-01-01" else depuis, notes = notes
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
                HudTextField(d.kwc, { sites[i] = d.copy(kwc = it) }, Modifier.fillMaxWidth(), label = "Puissance (kWc)")
                HudTextField(d.mes, { sites[i] = d.copy(mes = it) }, Modifier.fillMaxWidth(), label = "Mise en service (aaaa-mm-jj)")
                HudTextField(d.onduleur, { sites[i] = d.copy(onduleur = it) }, Modifier.fillMaxWidth(), label = "Onduleur")
                HudTextField(d.panneaux, { sites[i] = d.copy(panneaux = it) }, Modifier.fillMaxWidth(), label = "Nombre de panneaux")
                HudTextField(d.notes, { sites[i] = d.copy(notes = it) }, Modifier.fillMaxWidth(), label = "Notes", singleLine = false)
                TextButton({ sites.removeAt(i) }) { Text("Supprimer cette installation", color = Color(0xFFFF4D6D), fontSize = 12.sp) }
            }
        }
        HudButton("Ajouter une installation", icon = Icons.Filled.Add) {
            sites += SiteDraft(java.util.UUID.randomUUID().toString().take(8), "Installation ${sites.size + 1}", "", "", "", "", "", "")
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
    var info by remember { mutableStateOf("") }
    val list = d.docs.filter { it.type == type }.sortedByDescending { it.numero }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                if (type == "devis") "Pour créer un devis, dis par exemple : « Fais un devis maintenance pour M. Dupont »."
                else "Pour facturer : « Convertis le devis DEV-… en facture » ou « Fais une facture… ».", color = HudDim, fontSize = 12.sp
            )
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
                Action("PDF") { svc.partagerPdf(x.numero, md) }
                Action("Obsidian") { info = "📁 " + svc.exporter(x); selected = null }
                if (x.type == "devis" && x.statut in setOf("brouillon", "envoye", "accepte"))
                    Action("→ Facture") { svc.convertirEnFacture(x).onSuccess { info = "✅ ${it.doc.numero} créée" }.onFailure { info = it.message ?: "" }; selected = null }
                if (x.type == "facture" && x.statut == "a_payer")
                    Action("Payée") {
                        val r = svc.marquerPayee(x.numero, "")
                        info = "✅ Payée" + (r?.second?.let { " · réserve ${euro(it.montant)}" } ?: ""); selected = null
                    }
                if (x.statut in setOf("brouillon", "envoye", "accepte", "a_payer")) Action("✎ Modifier") { editingDoc = x.numero; selected = null }
                if (x.statut != "annulee" && x.statut != "refuse" && x.statut != "facture") Action("Annuler") { svc.annuler(x.numero); selected = null }
            }) { MarkdownText(md) }
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

// ---------------------------------------------------------------------- Paie

@Composable
private fun PaieTab(app: JarvisApp) {
    val svc = app.societe
    val d = svc.data
    var bulletin by remember { mutableStateOf<String?>(null) }
    var bilan by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editEmp by remember { mutableStateOf<Employe?>(null) }
    var mois by remember { mutableStateOf(LocalDate.now().toString().take(7)) }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "Bulletins de paie") {
                Text("⚠️ Estimation simplifiée (taux globaux, modifiables dans Réglages). À faire valider par un expert-comptable ou un logiciel de paie.", color = HudDim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                HudTextField(mois, { mois = it }, Modifier.fillMaxWidth(), label = "Mois (aaaa-mm)")
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
            HudPanel(Modifier.fillMaxWidth(), onClick = {
                if (Regex("\\d{4}-\\d{2}").matches(mois)) bulletin = svc.bulletin(e, mois, 0.0).first
            }) {
                Text("${e.nom} (${e.statut})", color = HudText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("${e.poste.ifBlank { "—" }} · brut ${euro(e.brutMensuel)}/mois · touche pour le bulletin de $mois", color = HudDim, fontSize = 12.sp)
                TextButton({ editEmp = e }) { Text("✎ Modifier", color = LocalAccent.current, fontSize = 12.sp) }
            }
        }
    }
    bulletin?.let { md -> Dialogue("Bulletin", { bulletin = null }, actions = { Action("PDF") { svc.partagerPdf("Bulletin $mois", md) } }) { MarkdownText(md) } }
    bilan?.let { md -> Dialogue("Réserve", { bilan = null }) { MarkdownText(md) } }
    if (adding) EmployeForm(null, d.employes.none { it.statut == "dirigeant" }, { adding = false }) { e ->
        svc.repo.update { it.copy(employes = it.employes + e) }; adding = false
    }
    editEmp?.let { e0 ->
        EmployeForm(e0, e0.statut == "dirigeant", { editEmp = null }) { e ->
            svc.repo.update { x -> x.copy(employes = x.employes.map { if (it.id == e.id) e else it }) }; editEmp = null
        }
    }
}

@Composable
private fun EmployeForm(initial: Employe?, dirDefaut: Boolean, onClose: () -> Unit, onSave: (Employe) -> Unit) {
    var nom by remember { mutableStateOf(initial?.nom ?: "") }
    var poste by remember { mutableStateOf(initial?.poste ?: "") }
    var brut by remember { mutableStateOf(initial?.let { nombre(it.brutMensuel) } ?: "") }
    var pas by remember { mutableStateOf(initial?.let { nombre(it.tauxPas) } ?: "0") }
    var dir by remember { mutableStateOf(dirDefaut) }
    Dialogue(if (initial == null) "Nouvel employé" else "Modifier ${initial.nom}", onClose, actions = {
        Action(if (initial == null) "Créer" else "Enregistrer") {
            if (nom.isNotBlank()) onSave(Employe(initial?.id ?: java.util.UUID.randomUUID().toString().take(8), nom.trim(), poste, if (dir) "dirigeant" else "salarie",
                brut.replace(',', '.').toDoubleOrNull() ?: 0.0, pas.replace(',', '.').toDoubleOrNull() ?: 0.0, initial?.dateEntree ?: LocalDate.now().toString()))
        }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { HudChip("Dirigeant", dir) { dir = true }; HudChip("Salarié", !dir) { dir = false } }
        HudTextField(nom, { nom = it }, Modifier.fillMaxWidth(), label = "Nom")
        HudTextField(poste, { poste = it }, Modifier.fillMaxWidth(), label = "Poste")
        HudTextField(brut, { brut = it }, Modifier.fillMaxWidth(), label = "Brut mensuel (€)")
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
    var tauxS by remember { mutableStateOf(nombre(d.paie.tauxSalarial)) }
    var tauxP by remember { mutableStateOf(nombre(d.paie.tauxPatronal)) }
    var tarifEdit by remember { mutableStateOf(false) }
    var tarifInit by remember { mutableStateOf<Tarif?>(null) }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            HudPanel(Modifier.fillMaxWidth(), title = "Paie (taux globaux estimés, %)") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudTextField(tauxS, { tauxS = it }, Modifier.fillMaxWidth(), label = "Cotisations salariales")
                    HudTextField(tauxP, { tauxP = it }, Modifier.fillMaxWidth(), label = "Charges patronales")
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
                            franchiseTva = franchise, tvaDefaut = num(tva) ?: 20.0,
                            delaiPaiementJours = delai.toIntOrNull() ?: 30, validiteDevisJours = validite.toIntOrNull() ?: 30,
                            mentions = mentions, reservePct = (num(reserve) ?: 0.0).coerceIn(0.0, 100.0),
                            partDirigeantPct = (num(partDir) ?: 50.0).coerceIn(0.0, 100.0)
                        ),
                        regles = Regles(
                            Pricing.parserSeuils(fid), Pricing.parserSeuils(vol), cumul, num(rmax) ?: 0.0,
                            it.regles.remiseSurDeplacement, num(kmPrix) ?: 0.0, num(kmGratuit) ?: 0.0, num(depMin) ?: 0.0, ar
                        ),
                        paie = it.paie.copy(tauxSalarial = num(tauxS) ?: 22.0, tauxSalarialDirigeant = num(tauxS) ?: 22.0, tauxPatronal = num(tauxP) ?: 42.0)
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
        Dialogue(if (ini == null) "Nouveau tarif" else "Modifier le tarif", { tarifEdit = false }, actions = {
            Action("Enregistrer") {
                val t = Pricing.parserTranches(tr).ifEmpty { num(tr)?.let { listOf(Tranche(KWC_MAX, it)) } ?: emptyList() }
                if (des.isNotBlank() && t.isNotEmpty()) {
                    svc.repo.update { it.copy(tarifs = it.tarifs.filterNot { x -> x.designation == des.trim() || x.designation == ini?.designation } + Tarif(des.trim(), mode, t, ini?.tva)) }
                    tarifEdit = false
                }
            }
        }) {
            HudTextField(des, { des = it }, Modifier.fillMaxWidth(), label = "Désignation")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Mode.values().forEach { m -> HudChip(m.name.lowercase(), m == mode) { mode = m } }
            }
            HudTextField(tr, { tr = it }, Modifier.fillMaxWidth(), label = "Prix : 0-3:150; 3-9:220; 9+:350", singleLine = false)
        }
    }
}
