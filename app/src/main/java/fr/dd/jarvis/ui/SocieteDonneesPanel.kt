package fr.dd.jarvis.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.coffre.Sauvegardes
import fr.dd.jarvis.societe.FactureElectronique
import fr.dd.jarvis.societe.MiroirFreebox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun quand(ms: String) = ms.toLongOrNull()?.takeIf { it > 0 }?.let { java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE).format(java.util.Date(it)) } ?: "jamais"

/** Données de la société : copie lisible sur la Freebox + sauvegardes chiffrées datées sur le serveur. */
@Composable
fun DonneesSocietePanel(app: JarvisApp) {
    val s = app.store
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var occupe by remember { mutableStateOf(false) }
    var dossier by remember { mutableStateOf(s.miroirFreeboxDossier.value) }
    var liste by remember { mutableStateOf<List<Sauvegardes.Archive>?>(null) }
    var voirCode by remember { mutableStateOf(false) }
    var saisieCode by remember { mutableStateOf("") }
    var aRestaurer by remember { mutableStateOf<String?>(null) }
    fun lancer(f: suspend () -> String) { if (occupe) return; occupe = true; msg = "⏳ …"; scope.launch { msg = runCatching { withContext(Dispatchers.IO) { f() } }.getOrElse { "❌ ${it.message}" }; occupe = false } }

    HudPanel(Modifier.fillMaxWidth(), title = "📁 Copie sur la Freebox (accès depuis un ordinateur)") {
        Text("Les devis, factures, bulletins (PDF), photos d'interventions, tableaux Excel (CSV) et la base complète sont copiés dans un dossier du disque " +
            "de la Freebox, ~30 s après chaque modification. Depuis un PC : Explorateur › \\\\mafreebox.freebox.fr ; Mac : smb://mafreebox.freebox.fr. " +
            "Le téléphone reste la référence ; le coffre-fort et les sauvegardes chiffrées continuent en parallèle.", color = HudDim, fontSize = 11.sp)
        if (!MiroirFreebox.disponible(app)) Text("⚠️ JARVIS n'est pas associé à la Freebox : Réglages › Maison › Box Internet › Associer.", color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        else {
            Text("Dernière copie : ${quand(s.miroirFreeboxDerniere.value)}" + (s.miroirFreeboxBilan.value.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudChip(if (s.miroirFreebox.value) "✅ Copie automatique" else "⏸️ Copie désactivée", s.miroirFreebox.value) {
                    s.miroirFreebox.set(!s.miroirFreebox.value); MiroirFreebox.planifier(app); if (s.miroirFreebox.value) MiroirFreebox.signalerChangement(app, 2)
                }
                HudChip(if (occupe) "⏳" else "🔄 Copier maintenant") { lancer { MiroirFreebox.synchroniser(app) } }
                HudChip("♻️ Tout renvoyer") { lancer { MiroirFreebox.synchroniser(app, toutRenvoyer = true) } }
            }
            HudTextField(dossier, { dossier = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = "Dossier sur le disque de la Freebox", placeholder = "JARVIS Société")
            if (dossier.trim() != s.miroirFreeboxDossier.value) HudButton("Enregistrer le dossier", Modifier.padding(top = 4.dp)) {
                s.miroirFreeboxDossier.set(dossier.trim().trim('/').ifBlank { "JARVIS Société" }); dossier = s.miroirFreeboxDossier.value
                MiroirFreebox.signalerChangement(app, 2); msg = "✅ Nouveau dossier : la copie complète part dans quelques secondes."
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    HudPanel(Modifier.fillMaxWidth(), title = "🗄️ Sauvegardes chiffrées sur le serveur") {
        Text("Chaque nuit (vers 3 h), une archive complète des données de la société est chiffrée sur le téléphone puis envoyée sur ton serveur (~/${s.sauvegardeDossier.value}). " +
            "On garde les 30 derniers jours et une sauvegarde par mois pendant un an : tu peux revenir à l'état exact d'un jour donné.", color = HudDim, fontSize = 11.sp)
        if (!Sauvegardes.configure(app)) Text("⚠️ Serveur SSH non configuré : Réglages › Maison › Serveur.", color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        else {
            Text("Dernière : ${quand(s.sauvegardeDerniere.value)}" + (s.sauvegardeBilan.value.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudChip(if (s.sauvegardeAuto.value) "✅ Chaque nuit" else "⏸️ Désactivées", s.sauvegardeAuto.value) { s.sauvegardeAuto.set(!s.sauvegardeAuto.value); Sauvegardes.planifier(app) }
                HudChip(if (occupe) "⏳" else "💾 Sauvegarder maintenant") { lancer { Sauvegardes.sauvegarder(app, forcer = true) } }
                HudChip("📋 Voir les sauvegardes") { lancer { liste = Sauvegardes.lister(app); if (liste!!.isEmpty()) "Aucune sauvegarde sur le serveur pour l'instant." else "${liste!!.size} sauvegarde(s)." } }
                HudChip(if (voirCode) "Masquer le code" else "🔑 Code de récupération") { voirCode = !voirCode }
            }
            liste?.let { l ->
                l.take(45).forEach { a ->
                    Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("• " + (Sauvegardes.dateDe(a.nom)?.let { fr.dd.jarvis.societe.core.dateFr(it.toString()) } ?: a.nom) + " " +
                            Regex("_(\\d{2})(\\d{2})").find(a.nom)?.let { "${it.groupValues[1]}h${it.groupValues[2]}" }.orEmpty() + " — ${a.taille / 1024} Ko",
                            color = HudText, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        HudChip(if (aRestaurer == a.nom) "Confirmer ?" else "↩️ Restaurer", aRestaurer == a.nom) {
                            if (aRestaurer != a.nom) aRestaurer = a.nom else { aRestaurer = null; lancer { Sauvegardes.restaurer(app, a.nom) } }
                        }
                    }
                }
                if (aRestaurer != null) Text("⚠️ Restaurer remplace la base actuelle par celle de la sauvegarde (l'état actuel est gardé sur le téléphone). Appuie sur « Confirmer ? » pour continuer.",
                    color = HudText, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (voirCode) {
                val code = Sauvegardes.codeRecuperation(app)
                if (code == null) Text("Aucune clé pour l'instant : elle est créée à la première sauvegarde.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                else {
                    Text((if (Sauvegardes.cleDuCoffre(app)) "C'est le même code que celui du coffre-fort. " else "") +
                        "⚠️ Garde ce code en lieu sûr (papier, gestionnaire de mots de passe) : sans lui, un nouveau téléphone ne peut pas lire les sauvegardes.",
                        color = HudText, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    Text(code, color = LocalAccent.current, fontSize = 15.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                    HudChip("📋 Copier le code") { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Code des sauvegardes", code)) }
                }
                HudTextField(saisieCode, { saisieCode = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = "Nouveau téléphone : code de récupération des sauvegardes", password = true)
                if (saisieCode.isNotBlank()) HudButton("Enregistrer ce code", Modifier.padding(top = 4.dp)) {
                    msg = if (Sauvegardes.importerCode(app, saisieCode)) "✅ Code enregistré : ce téléphone peut lire les sauvegardes." else "❌ Code invalide (52 caractères attendus)."
                    saisieCode = ""
                }
            }
        }
        if (msg.isNotBlank()) Text(msg, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Facturation électronique : désactivée par défaut, activable ou supprimable à tout moment. */
@Composable
fun FactureElectroniquePanel(app: JarvisApp) {
    val s = app.store
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var plateforme by remember { mutableStateOf(s.facturePlateforme.value) }
    var confirmerSuppression by remember { mutableStateOf(false) }
    HudPanel(Modifier.fillMaxWidth(), title = "🧾 Facturation électronique (Factur-X)") {
        Text("Réforme française : réception des factures électroniques obligatoire depuis le 1er septembre 2026 ; émission par une plateforme agréée à partir du " +
            "1er septembre 2027 pour les TPE et micro-entreprises. Activée, JARVIS produit chaque facture émise au format Factur-X (le PDF habituel + le fichier XML " +
            "structuré attaché), l'envoie ainsi par mail, la copie sur la Freebox et prépare l'e-reporting des ventes aux particuliers. " +
            "La conformité doit être validée par ta plateforme agréée avant tout usage réel.", color = HudDim, fontSize = 11.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudChip(if (s.factureElectronique.value) "✅ Activée" else "⏸️ Désactivée (société en test)", s.factureElectronique.value) {
                msg = FactureElectronique.activer(app, !s.factureElectronique.value).lines().first()
            }
            HudChip("🔎 État et contrôle") { msg = FactureElectronique.etat(app) }
            HudChip(if (confirmerSuppression) "Confirmer la suppression ?" else "🗑️ Supprimer les fichiers générés", confirmerSuppression) {
                if (!confirmerSuppression) confirmerSuppression = true
                else { confirmerSuppression = false; scope.launch { msg = withContext(Dispatchers.IO) { FactureElectronique.supprimerFichiers(app) } } }
            }
        }
        HudTextField(plateforme, { plateforme = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = "Plateforme agréée choisie (plus tard)", placeholder = "ex. le nom de ta plateforme")
        if (plateforme.trim() != s.facturePlateforme.value) HudButton("Enregistrer", Modifier.padding(top = 4.dp)) { s.facturePlateforme.set(plateforme.trim()); msg = "✅ Enregistré." }
        Text("Désactiver arrête toute production de fichiers ; « Supprimer les fichiers générés » efface ce qui a déjà été produit (la copie Freebox suit). " +
            "Les factures elles-mêmes ne sont jamais touchées.", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        if (msg.isNotBlank()) Text(msg, color = HudText, fontSize = 12.sp, fontWeight = FontWeight.Normal, modifier = Modifier.padding(top = 6.dp))
    }
}
