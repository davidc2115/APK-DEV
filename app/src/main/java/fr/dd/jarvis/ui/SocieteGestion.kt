package fr.dd.jarvis.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.Courrier
import fr.dd.jarvis.societe.Creation
import fr.dd.jarvis.societe.DeclarationEnLigne
import fr.dd.jarvis.societe.Declarations
import fr.dd.jarvis.societe.Frais
import fr.dd.jarvis.societe.core.NoteFrais
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

// ======================================================================================= Notes de frais

@Composable
internal fun FraisTab(app: JarvisApp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var filtre by remember { mutableStateOf("a_traiter") }
    var edition by remember { mutableStateOf<NoteFrais?>(null) }
    var justificatif by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }  // octets + extension
    var info by remember { mutableStateOf("") }
    var occupe by remember { mutableStateOf(false) }
    var aSupprimer by remember { mutableStateOf<NoteFrais?>(null) }
    var attendu by rememberSaveable { mutableStateOf<String?>(null) }

    fun lire(octets: ByteArray, ext: String) {
        justificatif = octets to ext
        occupe = true; info = "📷 Lecture du justificatif…"
        scope.launch {
            val nf = runCatching { Frais.lireTicket(app, octets, if (ext == "pdf") "application/pdf" else "image/jpeg") }
            edition = nf.getOrNull() ?: NoteFrais("", LocalDate.now().toString(), source = "photo")
            info = if (nf.isSuccess) "Vérifie et corrige si besoin, puis enregistre." else "❌ Lecture impossible (${nf.exceptionOrNull()?.message}) : saisis les montants."
            occupe = false
        }
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = attendu?.let { Uri.parse(it) }; attendu = null
        if (ok && u != null) scope.launch { withContext(Dispatchers.IO) { jpegReduit(ctx, u, 1800) }?.let { lire(it, "jpg") } }
    }
    val fichier = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val type = ctx.contentResolver.getType(u).orEmpty()
            val octets = withContext(Dispatchers.IO) { if (type.contains("pdf")) ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } else jpegReduit(ctx, u, 1800) }
            if (octets != null) lire(octets, if (type.contains("pdf")) "pdf" else "jpg") else info = "❌ Fichier illisible."
        }
    }

    val tous = Frais.liste(app)
    val liste = tous.filter { filtre == "tous" || it.statut == filtre }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "💶 Notes de frais") {
                Text("Photographie le ticket ou la facture : JARVIS lit le montant, la TVA, la date et le fournisseur. Le justificatif est gardé avec la note (et dans le coffre Obsidian › Société › Notes de frais).", color = HudDim, fontSize = 12.sp)
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("📷 Photo du ticket", filled = true, enabled = !occupe) {
                        val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "frais_${System.currentTimeMillis()}.jpg")
                        val u = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                        attendu = u.toString(); runCatching { photo.launch(u) }
                    }
                    HudButton("📎 Fichier / PDF", enabled = !occupe) { runCatching { fichier.launch(arrayOf("image/*", "application/pdf")) } }
                    HudButton("✍️ Saisie", enabled = !occupe) { justificatif = null; edition = NoteFrais("", LocalDate.now().toString()) }
                }
                if (info.isNotBlank()) Text(info, color = LocalAccent.current, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                val mois = LocalDate.now().toString().take(7)
                val t = Frais.totaux(Frais.duMois(app, mois))
                val at = Frais.totaux(tous.filter { it.statut == "a_traiter" })
                Text("Ce mois-ci : ${t.nombre} note(s), ${Frais.euros(t.ttc)} TTC dont TVA ${Frais.euros(t.tva)} · à traiter : ${at.nombre} (${Frais.euros(at.ttc)})", color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (Frais.STATUTS + ("tous" to "Toutes")).forEach { (k, v) -> HudChip(v + " (" + (if (k == "tous") tous.size else tous.count { it.statut == k }) + ")", filtre == k) { filtre = k } }
                }
            }
        }
        if (liste.isEmpty()) item { Text("Aucune note de frais ici.", color = HudDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
        items(liste, key = { it.id }) { nf ->
            HudPanel(Modifier.fillMaxWidth(), title = "${Frais.libelle(nf.categorie)} · ${Frais.euros(nf.ttc)}") {
                Text("${nf.date} · ${nf.fournisseur.ifBlank { "Fournisseur ?" }}${if (nf.description.isNotBlank()) " — ${nf.description}" else ""}", color = HudText, fontSize = 12.sp)
                Text("HT ${Frais.euros(nf.ttc - nf.tva)} · TVA ${Frais.euros(nf.tva)}${if (nf.paiement.isNotBlank()) " · ${Frais.PAIEMENTS[nf.paiement] ?: nf.paiement}" else ""} · ${Frais.STATUTS[nf.statut]}" +
                    if (nf.justificatif.isBlank()) " · ⚠️ sans justificatif" else "", color = HudDim, fontSize = 11.sp)
                Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Frais.fichier(app, nf)?.let { f -> HudChip("📎 Voir") {
                        runCatching {
                            val u = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, if (f.extension == "pdf") "application/pdf" else "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    } }
                    if (nf.statut != "traite") HudChip("✅ Traitée") { Frais.marquer(app, nf.id, "traite") }
                    if (nf.paiement == "cb_perso" && nf.statut != "rembourse") HudChip("💶 Remboursée") { Frais.marquer(app, nf.id, "rembourse") }
                    if (nf.statut != "a_traiter") HudChip("⏳ À traiter") { Frais.marquer(app, nf.id, "a_traiter") }
                    HudChip("✏️ Modifier") { justificatif = null; edition = nf }
                    HudChip("🗑️ Supprimer") { aSupprimer = nf }
                }
            }
        }
    }

    edition?.let { nf0 ->
        var fournisseur by remember(nf0) { mutableStateOf(nf0.fournisseur) }
        var description by remember(nf0) { mutableStateOf(nf0.description) }
        var date by remember(nf0) { mutableStateOf(nf0.date) }
        var ttc by remember(nf0) { mutableStateOf(if (nf0.ttc > 0) nf0.ttc.toString().replace('.', ',') else "") }
        var tva by remember(nf0) { mutableStateOf(if (nf0.tva > 0) nf0.tva.toString().replace('.', ',') else "") }
        var categorie by remember(nf0) { mutableStateOf(nf0.categorie) }
        var paiement by remember(nf0) { mutableStateOf(nf0.paiement) }
        AlertDialog(onDismissRequest = { edition = null }, containerColor = Color(0xFF0B1424), titleContentColor = LocalAccent.current, textContentColor = HudText,
            title = { Text(if (nf0.id.isBlank()) "Nouvelle note de frais" else "Modifier la note de frais", fontSize = 16.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (justificatif != null) Text("📎 Justificatif joint (${justificatif!!.second.uppercase()})", color = Color(0xFF78D6A0), fontSize = 12.sp)
                    HudTextField(fournisseur, { fournisseur = it }, Modifier.fillMaxWidth(), label = "Fournisseur")
                    HudTextField(description, { description = it }, Modifier.fillMaxWidth(), label = "Objet")
                    HudTextField(date, { date = it }, Modifier.fillMaxWidth(), label = "Date (aaaa-mm-jj)")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudTextField(ttc, { ttc = it }, Modifier.weight(1f), label = "Montant TTC €")
                        HudTextField(tva, { tva = it }, Modifier.weight(1f), label = "dont TVA €")
                    }
                    Text("Catégorie", color = HudDim, fontSize = 11.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Frais.CATEGORIES.forEach { (k, v) -> HudChip(v, categorie == k) { categorie = k } }
                    }
                    Text("Paiement", color = HudDim, fontSize = 11.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Frais.PAIEMENTS.forEach { (k, v) -> HudChip(v, paiement == k) { paiement = k } }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    val montant = ttc.replace(',', '.').replace(" ", "").toDoubleOrNull()
                    if (montant == null || montant <= 0) { info = "❌ Montant TTC invalide."; return@TextButton }
                    val j = justificatif
                    val chemin = if (j != null) runCatching { Frais.garderJustificatif(app, j.first, j.second, date, fournisseur) }.getOrDefault("") else nf0.justificatif
                    val nf = nf0.copy(fournisseur = fournisseur.trim(), description = description.trim(), date = date.trim().takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: LocalDate.now().toString(),
                        ttc = montant, tva = tva.replace(',', '.').replace(" ", "").toDoubleOrNull() ?: 0.0, categorie = categorie, paiement = paiement, justificatif = chemin)
                    scope.launch {
                        withContext(Dispatchers.IO) { if (nf.id.isBlank()) Frais.ajouter(app, nf) else Frais.modifier(app, nf) }
                        info = "✅ Note de frais enregistrée."
                    }
                    edition = null; justificatif = null
                }) { Text("Enregistrer", color = LocalAccent.current) }
            },
            dismissButton = { TextButton({ edition = null; justificatif = null }) { Text("Annuler", color = HudDim) } })
    }
    aSupprimer?.let { nf ->
        AlertDialog(onDismissRequest = { aSupprimer = null }, containerColor = Color(0xFF0B1424), titleContentColor = LocalAccent.current, textContentColor = HudText,
            title = { Text("Supprimer cette note de frais ?") }, text = { Text("${nf.fournisseur} · ${Frais.euros(nf.ttc)} — le justificatif est supprimé aussi.") },
            confirmButton = { TextButton({ Frais.supprimer(app, nf.id); aSupprimer = null }) { Text("Supprimer", color = Color(0xFFFF6B6B)) } },
            dismissButton = { TextButton({ aSupprimer = null }) { Text("Annuler", color = HudDim) } })
    }
}

// ======================================================================================= Reçus (SMS / mails analysés)

@Composable
internal fun RecusTab(app: JarvisApp) = CourrierListe(app, Courrier.SOCIETE)

// ======================================================================================= Déclarations

@Composable
internal fun DeclarationsTab(app: JarvisApp) {
    val ctx = LocalContext.current
    val s = app.societe.data.societe
    val st = Declarations.statut(s)
    var ouvert by remember { mutableStateOf<String?>(null) }
    fun maj(f: (fr.dd.jarvis.societe.core.Societe) -> fr.dd.jarvis.societe.core.Societe) = app.societe.repo.update { d -> d.copy(societe = f(d.societe)) }
    val faites = app.societe.data.declarationsFaites
    val echeances = Declarations.echeances(app.societe.data)
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "📋 Statut et régime") {
                Text("Choisis ton statut : JARVIS te dit quoi déclarer, quand, où, et avec quel montant (calculé depuis tes factures encaissées, notes de frais et bulletins).", color = HudDim, fontSize = 12.sp)
                Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Declarations.Statut.values().forEach { x -> HudChip(x.libelle, st == x) {
                        maj { it.copy(forme = if (x == Declarations.Statut.MICRO) "Micro-entreprise" else x.libelle, franchiseTva = if (x == Declarations.Statut.MICRO) it.franchiseTva || it.regimeTva.isBlank() else it.franchiseTva) }
                    } }
                }
                if (st == Declarations.Statut.MICRO) {
                    Text("Activité", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Declarations.ACTIVITES.forEach { (k, a) -> HudChip(a.libelle, s.activite == k) { maj { it.copy(activite = k) } } }
                    }
                    Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudChip("Déclaration mensuelle", s.periodicite != "trimestrielle") { maj { it.copy(periodicite = "mensuelle") } }
                        HudChip("Trimestrielle", s.periodicite == "trimestrielle") { maj { it.copy(periodicite = "trimestrielle") } }
                        HudChip((if (s.versementLiberatoire) "✅ " else "") + "Versement libératoire", s.versementLiberatoire) { maj { it.copy(versementLiberatoire = !it.versementLiberatoire) } }
                    }
                } else {
                    Text("TVA", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("franchise" to "Franchise", "reel_simplifie" to "Réel simplifié (2026)", "reel_normal" to "Réel normal (mensuel)").forEach { (k, v) ->
                            HudChip(v, s.regimeTva.ifBlank { if (s.franchiseTva) "franchise" else "reel_simplifie" } == k) { maj { it.copy(regimeTva = k, franchiseTva = k == "franchise") } }
                        }
                    }
                }
                Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (st == Declarations.Statut.MICRO || s.regimeTva.isBlank()) HudChip((if (s.franchiseTva) "✅ " else "") + "Franchise de TVA", s.franchiseTva) { maj { it.copy(franchiseTva = !it.franchiseTva) } }
                    HudChip((if (s.acre) "✅ " else "") + "ACRE", s.acre) { maj { it.copy(acre = !it.acre) } }
                }
                var debut by remember(s.dateCreation) { mutableStateOf(s.dateCreation) }
                HudTextField(debut, { debut = it; if (it.isBlank() || runCatching { LocalDate.parse(it) }.isSuccess) maj { x -> x.copy(dateCreation = it) } }, Modifier.fillMaxWidth().padding(top = 6.dp), label = "Début d'activité (aaaa-mm-jj)")
            }
        }
        item { IdentifiantsSites(app) }
        val pieces = Declarations.pieces(app.societe.data)
        if (pieces.isNotEmpty()) item {
            HudPanel(Modifier.fillMaxWidth(), title = "🗂️ Pièces à traiter avant de déclarer") { pieces.forEach { MarkdownText("- $it") } }
        }
        item { Text("ÉCHÉANCES", color = LocalAccent.current, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }
        items(echeances, key = { it.id }) { e ->
            val fait = faites.containsKey(e.id)
            val retard = !fait && e.date.isBefore(LocalDate.now())
            HudPanel(Modifier.fillMaxWidth(), title = (if (fait) "✅ " else if (retard) "⚠️ " else "🗓️ ") + Declarations.jour(e.date), onClick = { ouvert = if (ouvert == e.id) null else e.id }) {
                Text(e.titre, color = HudText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(e.organisme, color = HudDim, fontSize = 11.sp)
                if (e.montant.isNotBlank()) Text(e.montant, color = LocalAccent.current, fontSize = 12.sp)
                if (ouvert == e.id) {
                    MarkdownText(e.etapes.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n") + (if (e.note.isNotBlank()) "\n\n_${e.note}_" else "") + "\n\n[🌐 ${e.lien.removePrefix("https://")}](${e.lien})")
                    if (!fait && DeclarationEnLigne.automatisable(e)) HudButton("🤖 Faire la déclaration avec JARVIS", Modifier.fillMaxWidth().padding(top = 6.dp), filled = true) {
                        runCatching { ctx.startActivity(Intent(ctx, fr.dd.jarvis.MainActivity::class.java).putExtra("terrain", "declarer:${e.id}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                    }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!fait) HudChip("✅ C'est fait") { app.societe.repo.update { d -> d.copy(declarationsFaites = d.declarationsFaites + (e.id to LocalDate.now().toString())) } }
                        else HudChip("↩️ Pas encore") { app.societe.repo.update { d -> d.copy(declarationsFaites = d.declarationsFaites - e.id) } }
                    }
                } else Text("Touche pour la marche à suivre", color = HudDim, fontSize = 10.sp)
            }
        }
        item { Text("Montants calculés d'après les données saisies dans JARVIS. Taux et seuils 2026 vérifiés sur service-public.fr, urssaf.fr et impots.gouv.fr ; ton expert-comptable ou ton service des impôts des entreprises fait foi.", color = HudDim, fontSize = 10.sp) }
    }
}

/** Identifiants des sites officiels, chiffrés sur le téléphone (jamais envoyés à une IA). */
@Composable
private fun IdentifiantsSites(app: JarvisApp) {
    var ouvert by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    HudPanel(Modifier.fillMaxWidth(), title = "🔐 Déclarations en ligne par JARVIS", onClick = { ouvert = !ouvert }) {
        Text("Avec tes identifiants, JARVIS ouvre le site officiel, se connecte, va à la bonne page et remplit les montants calculés ici. Avant d'envoyer ou de payer, il te montre le récapitulatif et attend ton accord. Si le site envoie un code par SMS ou mail, tu le tapes dans la page.",
            color = HudDim, fontSize = 12.sp)
        if (ouvert) {
            Text("Les identifiants sont chiffrés sur ce téléphone et ne sont JAMAIS envoyés à une IA : ils sont tapés par le téléphone, seulement dans la page du site officiel correspondant.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            DeclarationEnLigne.SITES.forEach { site ->
                key(site.cle, version) {
                    val actuel = remember(version) { DeclarationEnLigne.identifiants(app, site.cle) }
                    var login by remember(version) { mutableStateOf(actuel?.login.orEmpty()) }
                    var mdp by remember(version) { mutableStateOf(actuel?.mdp.orEmpty()) }
                    Text((if (actuel != null) "✅ " else "⬜ ") + site.nom, color = HudText, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                    HudTextField(login, { login = it }, Modifier.fillMaxWidth(), label = site.identifiant)
                    HudTextField(mdp, { mdp = it }, Modifier.fillMaxWidth().padding(top = 4.dp), label = "Mot de passe", password = true)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudChip("💾 Enregistrer") { runCatching { DeclarationEnLigne.enregistrer(app, site.cle, login, mdp) }; version++ }
                        if (actuel != null) HudChip("🗑️ Effacer") { runCatching { DeclarationEnLigne.enregistrer(app, site.cle, "", "") }; version++ }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).clickable { app.store.declarationValidationAuto.set(!app.store.declarationValidationAuto.value) }) {
                Text((if (app.store.declarationValidationAuto.value) "✅ " else "⬜ ") + "Envoyer sans me demander (déconseillé : JARVIS clique lui-même sur Valider / Payer)", color = HudText, fontSize = 12.sp)
            }
        } else Text("Touche pour enregistrer tes identifiants (URSSAF, impôts, net-entreprises…)", color = HudDim, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

// ======================================================================================= Création

@Composable
internal fun CreationTab(app: JarvisApp) {
    val scope = rememberCoroutineScope()
    val s = app.societe.data.societe
    val st = Declarations.statut(s)
    val faites = app.societe.data.creation
    var ouvert by remember { mutableStateOf<String?>(null) }
    var statutVu by remember { mutableStateOf<Declarations.Statut?>(null) }
    var siret by remember { mutableStateOf(s.siret) }
    var resultat by remember { mutableStateOf("") }
    var fiche by remember { mutableStateOf<Creation.Fiche?>(null) }
    val etapes = Creation.etapes(st)
    val societe = st.societeIs
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "🏗️ Créer ma société") {
                Text("${etapes.count { faites[it.id] == true }} / ${etapes.size} étapes faites — statut choisi : ${st.libelle}.", color = HudText, fontSize = 12.sp)
                Text("Choisis ton statut : la liste des étapes, les pièces à fournir et les coûts s'adaptent. Touche une étape pour tout le détail (pourquoi, comment faire, pièces, choix possibles, coût, délai, pièges), puis coche-la quand c'est fait.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Declarations.Statut.values().forEach { x -> HudChip(x.libelle, st == x) { app.societe.repo.update { d -> d.copy(societe = d.societe.copy(forme = if (x == Declarations.Statut.MICRO) "Micro-entreprise" else x.libelle)) } } }
                }
            }
        }
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "🗺️ Vue d'ensemble", onClick = { ouvert = if (ouvert == "ensemble") null else "ensemble" }) {
                if (ouvert == "ensemble") MarkdownText(
                    "La création se fait en ${if (societe) "4" else "3"} temps :\n" +
                        Creation.Phase.values().filter { societe || it != Creation.Phase.CONSTITUER }.joinToString("\n") { "- **${it.titre}** — ${it.description}" } +
                        "\n\n### ⏱️ Durée totale\n" + (if (societe) "Compte 3 à 6 semaines : 1 à 2 semaines pour les statuts, le capital et l'annonce, puis quelques jours à 3 semaines pour l'immatriculation." else "Compte 1 à 3 semaines : la déclaration se fait en une heure, le SIRET arrive en général sous 1 à 4 semaines.") +
                        "\n\n### 💶 Budget de départ (hors assurances et matériel)\n" + when (st) {
                            Declarations.Statut.MICRO, Declarations.Statut.EI -> "Formalités gratuites. Prévois surtout les assurances (décennale, RC pro), l'outillage, le véhicule et une réserve de trésorerie de quelques mois."
                            Declarations.Statut.EURL -> "≈ 190 € de formalités en 2026 (annonce 124 € HT + immatriculation 33,83 € + artisanat 15 € + bénéficiaires effectifs 19,33 €), plus la rédaction des statuts si tu la confies, le capital et l'expert-comptable."
                            Declarations.Statut.SASU -> "≈ 210 € de formalités en 2026 (annonce 142 € HT + immatriculation 33,83 € + artisanat 15 € + bénéficiaires effectifs 19,33 €), plus la rédaction des statuts si tu la confies, le capital et l'expert-comptable."
                            Declarations.Statut.SAS -> "≈ 270 € de formalités en 2026 (annonce 199 € HT + immatriculation 33,83 € + artisanat 15 € + bénéficiaires effectifs 19,33 €), plus les statuts et le pacte d'associés, le capital et l'expert-comptable."
                            Declarations.Statut.SARL -> "≈ 215 € de formalités en 2026 (annonce 148 € HT + immatriculation 33,83 € + artisanat 15 € + bénéficiaires effectifs 19,33 €), plus les statuts, le capital et l'expert-comptable."
                        } +
                        "\n\n### 🙋 Où se faire aider gratuitement\n- Chambre de métiers (CMA) de ton département : réunions d'information, rendez-vous conseil.\n- France Travail : maintien du chômage, ARCE, accompagnement à la création.\n- Simulateurs officiels : mon-entreprise.urssaf.fr."
                ) else Text("Les grandes étapes, la durée et le budget — touche pour lire", color = HudDim, fontSize = 11.sp)
            }
        }
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "⚖️ Quel statut choisir ?", onClick = { ouvert = if (ouvert == "comparatif") null else "comparatif" }) {
                if (ouvert == "comparatif") {
                    MarkdownText(Creation.TABLEAU)
                    Text("Touche un statut pour sa fiche complète :", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Declarations.Statut.values().forEach { x -> HudChip(x.libelle, (statutVu ?: st) == x) { statutVu = x } }
                    }
                    val vu = statutVu ?: st
                    MarkdownText(Creation.fiche(vu).markdown(vu), modifier = Modifier.padding(top = 6.dp))
                    if (vu != st) Row(Modifier.padding(top = 6.dp)) {
                        HudChip("✔️ Choisir ${vu.libelle}") { app.societe.repo.update { d -> d.copy(societe = d.societe.copy(forme = if (vu == Declarations.Statut.MICRO) "Micro-entreprise" else vu.libelle)) } }
                    }
                } else Text("Tableau comparatif et fiche détaillée de chaque statut — touche pour ouvrir", color = HudDim, fontSize = 11.sp)
            }
        }
        Creation.Phase.values().forEach { phase ->
            val duPhase = etapes.filter { it.phase == phase }
            if (duPhase.isEmpty()) return@forEach
            item(key = "phase-${phase.name}") {
                Column(Modifier.padding(top = 6.dp)) {
                    Text(phase.titre.uppercase(), color = LocalAccent.current, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(phase.description, color = HudDim, fontSize = 11.sp)
                    Text("${duPhase.count { faites[it.id] == true }} / ${duPhase.size} faites", color = HudDim, fontSize = 10.sp)
                }
            }
            items(duPhase, key = { it.id }) { e ->
                val fait = faites[e.id] == true
                HudPanel(Modifier.fillMaxWidth(), title = (if (fait) "✅ " else "⬜ ") + e.titre, onClick = { ouvert = if (ouvert == e.id) null else e.id }) {
                    if (ouvert == e.id) {
                        MarkdownText(e.markdown())
                        if (e.id == "siret") {
                            HudTextField(siret, { siret = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = "SIRET (14 chiffres) ou SIREN")
                            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                HudButton("🔎 Vérifier le SIRET", filled = true) {
                                    val chiffres = siret.filter { it.isDigit() }
                                    if (chiffres.length == 14 && !Creation.siretValide(chiffres)) { resultat = "❌ Ce SIRET n'est pas valide (clé de contrôle) : vérifie les chiffres."; return@HudButton }
                                    resultat = "Recherche dans l'annuaire officiel…"
                                    scope.launch {
                                        val f = runCatching { Creation.rechercher(siret) }.getOrNull()
                                        fiche = f
                                        resultat = if (f == null) "Aucune entreprise trouvée (le SIRET peut mettre quelques jours à apparaître après l'immatriculation)."
                                            else "**${f.nom}** — SIREN ${f.siren}, SIRET ${f.siret}\n${f.adresse}\nAPE ${f.ape} · créée le ${f.dateCreation} · ${f.etat}"
                                    }
                                }
                                fiche?.let { f -> HudButton("📥 Remplir l'identité") {
                                    app.societe.repo.update { d -> d.copy(societe = Creation.appliquer(d.societe, f), creation = d.creation + ("siret" to true)) }
                                    resultat += "\n\n✅ Identité de la société mise à jour (Société › Réglages pour vérifier)."
                                } }
                            }
                            if (resultat.isNotBlank()) MarkdownText(resultat)
                        }
                        Row(Modifier.padding(top = 6.dp)) {
                            HudChip(if (fait) "↩️ Pas encore fait" else "✅ C'est fait") { app.societe.repo.update { d -> d.copy(creation = d.creation + (e.id to !fait)) } }
                        }
                    } else Text(e.detail.firstOrNull()?.take(140)?.let { "$it…" } ?: "Touche pour le détail", color = HudDim, fontSize = 10.sp)
                }
            }
        }
        item { Text("Demande aussi à JARVIS : « explique-moi l'étape des assurances », « quelle est la différence entre EURL et SASU ? », « qu'est-ce que je dois déclarer ? ». Informations 2026 vérifiées sur service-public.fr, urssaf.fr et impots.gouv.fr ; la chambre de métiers ou un expert-comptable font foi pour ta situation.", color = HudDim, fontSize = 11.sp) }
    }
}
