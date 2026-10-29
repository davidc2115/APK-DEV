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
    var query by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    val list = d.clients.filter { query.isBlank() || normaliser(it.nom).contains(normaliser(query)) }.sortedBy { normaliser(it.nom) }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudTextField(query, { query = it }, Modifier.weight(1f), placeholder = "Rechercher un client…")
                HudRoundButton(Icons.Filled.Add) { creating = true }
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
        }) { MarkdownText(md) }
    }
    if (creating) ClientForm(onClose = { creating = false }) { c ->
        svc.repo.update { it.copy(clients = it.clients + c) }
        creating = false
    }
}

@Composable
private fun ClientForm(onClose: () -> Unit, onSave: (Client) -> Unit) {
    var nom by remember { mutableStateOf("") }
    var tel by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var adresse by remember { mutableStateOf("") }
    var depuis by remember { mutableStateOf(LocalDate.now().year.toString()) }
    var pro by remember { mutableStateOf(false) }
    var siteNom by remember { mutableStateOf("") }
    var siteAdr by remember { mutableStateOf("") }
    var kwc by remember { mutableStateOf("") }
    Dialogue("Nouveau client", onClose, actions = {
        Action("Créer") {
            if (nom.isNotBlank()) {
                val sites = if (kwc.isNotBlank() || siteNom.isNotBlank() || siteAdr.isNotBlank())
                    listOf(Site(java.util.UUID.randomUUID().toString().take(8), siteNom.ifBlank { "Installation 1" }, siteAdr.ifBlank { adresse }, kwc.replace(',', '.').toDoubleOrNull() ?: 0.0))
                else emptyList()
                onSave(Client(java.util.UUID.randomUUID().toString().take(8), nom.trim(), if (pro) "professionnel" else "particulier",
                    tel = tel, email = email, adresse = adresse, sites = sites, depuis = if (depuis.matches(Regex("\\d{4}"))) "$depuis-01-01" else depuis))
            }
        }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudChip("Particulier", !pro) { pro = false }
            HudChip("Professionnel", pro) { pro = true }
        }
        HudTextField(nom, { nom = it }, Modifier.fillMaxWidth(), label = "Nom")
        HudTextField(tel, { tel = it }, Modifier.fillMaxWidth(), label = "Téléphone")
        HudTextField(email, { email = it }, Modifier.fillMaxWidth(), label = "Email")
        HudTextField(adresse, { adresse = it }, Modifier.fillMaxWidth(), label = "Adresse")
        HudTextField(depuis, { depuis = it }, Modifier.fillMaxWidth(), label = "Client depuis (année)")
        Text("Première installation (optionnel)", color = HudDim, fontSize = 12.sp)
        HudTextField(siteNom, { siteNom = it }, Modifier.fillMaxWidth(), label = "Nom de l'installation")
        HudTextField(siteAdr, { siteAdr = it }, Modifier.fillMaxWidth(), label = "Adresse (si différente)")
        HudTextField(kwc, { kwc = it }, Modifier.fillMaxWidth(), label = "Puissance (kWc)")
    }
}

// ---------------------------------------------------------------------- Devis / Factures

@Composable
private fun DocsTab(app: JarvisApp, type: String) {
    val svc = app.societe
    val d = svc.data
    var selected by remember { mutableStateOf<String?>(null) }
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
                if (x.statut != "annulee" && x.statut != "refuse" && x.statut != "facture") Action("Annuler") { svc.annuler(x.numero); selected = null }
            }) { MarkdownText(md) }
        }
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
            }
        }
    }
    bulletin?.let { md -> Dialogue("Bulletin", { bulletin = null }, actions = { Action("PDF") { svc.partagerPdf("Bulletin $mois", md) } }) { MarkdownText(md) } }
    bilan?.let { md -> Dialogue("Réserve", { bilan = null }) { MarkdownText(md) } }
    if (adding) {
        var nom by remember { mutableStateOf("") }
        var poste by remember { mutableStateOf("") }
        var brut by remember { mutableStateOf("") }
        var pas by remember { mutableStateOf("0") }
        var dir by remember { mutableStateOf(d.employes.none { it.statut == "dirigeant" }) }
        Dialogue("Nouvel employé", { adding = false }, actions = {
            Action("Créer") {
                if (nom.isNotBlank()) {
                    svc.repo.update { it.copy(employes = it.employes + Employe(svc.newId(), nom.trim(), poste, if (dir) "dirigeant" else "salarie",
                        brut.replace(',', '.').toDoubleOrNull() ?: 0.0, pas.replace(',', '.').toDoubleOrNull() ?: 0.0, LocalDate.now().toString())) }
                    adding = false
                }
            }
        }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { HudChip("Dirigeant", dir) { dir = true }; HudChip("Salarié", !dir) { dir = false } }
            HudTextField(nom, { nom = it }, Modifier.fillMaxWidth(), label = "Nom")
            HudTextField(poste, { poste = it }, Modifier.fillMaxWidth(), label = "Poste")
            HudTextField(brut, { brut = it }, Modifier.fillMaxWidth(), label = "Brut mensuel (€)")
            HudTextField(pas, { pas = it }, Modifier.fillMaxWidth(), label = "Prélèvement à la source (%)")
        }
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
                        TextButton({ svc.repo.update { x -> x.copy(tarifs = x.tarifs.filterNot { it.designation == t.designation }) } }) { Text("Suppr.", color = Color(0xFFFF4D6D), fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                HudButton("Ajouter un tarif", icon = Icons.Filled.Add) { tarifEdit = true }
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
        var des by remember { mutableStateOf("") }
        var mode by remember { mutableStateOf(Mode.FORFAIT) }
        var tr by remember { mutableStateOf("") }
        Dialogue("Nouveau tarif", { tarifEdit = false }, actions = {
            Action("Enregistrer") {
                val t = Pricing.parserTranches(tr).ifEmpty { num(tr)?.let { listOf(Tranche(KWC_MAX, it)) } ?: emptyList() }
                if (des.isNotBlank() && t.isNotEmpty()) {
                    svc.repo.update { it.copy(tarifs = it.tarifs.filterNot { x -> x.designation == des.trim() } + Tarif(des.trim(), mode, t)) }
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
