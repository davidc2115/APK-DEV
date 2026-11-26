package fr.dd.jarvis.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.Courrier
import fr.dd.jarvis.societe.Frais
import fr.dd.jarvis.societe.core.NoteFrais
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Onglet « Courrier » (à part de la Société) : colis, rendez-vous, factures et devis repérés dans les SMS et mails, à
 * classer « Société » ou « Perso ». Ce qui vient d'un client de la Société, cite un devis / une facture de la Société ou
 * arrive sur la boîte mail de la Société est classé tout seul ; un classement fait à la main est retenu pour l'expéditeur.
 */
@Composable
fun CourrierScreen(app: JarvisApp) = CourrierListe(app, null)

/** Liste des éléments reçus ; [domaineFixe] = « societe » : seulement ceux de la Société (onglet Société › Reçus). */
@Composable
fun CourrierListe(app: JarvisApp, domaineFixe: String?) {
    var version by remember { mutableIntStateOf(0) }
    var actionInfo by remember { mutableStateOf("") }
    var domaine by rememberSaveable { mutableStateOf(domaineFixe ?: "tous") }
    var type by rememberSaveable { mutableStateOf("tous") }
    var filtreEtat by rememberSaveable("courrier_filtre_etat_v2") { mutableStateOf("a_traiter") }
    val tout = remember(version) { Courrier.liste(app).sortedByDescending { it.ts } }
    val l = tout.filter { e ->
        (filtreEtat == "tout" || (filtreEtat == "traites" && e.traite) || (filtreEtat == "a_traiter" && !e.traite)) && (type == "tous" || e.type == type) &&
            when (domaine) { "tous" -> true; "a_classer" -> e.domaine.isBlank(); else -> e.domaine == domaine }
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(app) {
        val resultat = withContext(Dispatchers.IO) {
            val sms = Courrier.importerSmsRecents(app)
            val mails = runCatching { Courrier.importerMailsRecents(app, 200) }
            Triple(sms, mails.getOrDefault(0), mails.exceptionOrNull())
        }
        if (resultat.first + resultat.second > 0) version++
        actionInfo = resultat.third?.let { "⚠️ Import des mails impossible : ${it.message ?: it.javaClass.simpleName}. Vérifie Réglages › Comptes › Mails." }
            ?: "Synchronisation terminée : ${resultat.first} nouvel(aux) SMS et ${resultat.second} nouvel(aux) mail(s) détecté(s)."
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HudPanel(Modifier.fillMaxWidth(), title = if (domaineFixe == Courrier.SOCIETE) "📬 Reçus pour la Société" else "📬 Courrier reçu") {
                if (actionInfo.isNotBlank()) Text(actionInfo, color = HudDim, fontSize = 11.sp)
                Text(if (domaineFixe == Courrier.SOCIETE) "Factures, devis, rendez-vous et colis classés « Société ». Les éléments perso ou à classer sont dans l'onglet Courrier."
                    else "Colis, rendez-vous, factures et devis repérés dans tes SMS et mails. Classe chacun 🏢 Société ou 🏠 Perso : ce qui vient d'un client de la Société (ou cite un de tes devis / factures, ou arrive sur la boîte mail de la Société) est classé tout seul, et ton choix est retenu pour les prochains messages du même expéditeur.",
                    color = HudDim, fontSize = 12.sp)
                if (domaineFixe == null) {
                    val aClasser = tout.count { it.domaine.isBlank() && !it.traite }
                    Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudChip("Tout", domaine == "tous") { domaine = "tous" }
                        HudChip("❔ À classer" + if (aClasser > 0) " ($aClasser)" else "", domaine == "a_classer") { domaine = "a_classer" }
                        HudChip("🏢 Société", domaine == Courrier.SOCIETE) { domaine = Courrier.SOCIETE }
                        HudChip("🏠 Perso", domaine == Courrier.PERSO) { domaine = Courrier.PERSO }
                    }
                }
                Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("tous" to "Tous", Courrier.COLIS to "📦 Colis", Courrier.RDV to "📅 Rendez-vous", Courrier.FACTURE to "🧾 Factures", Courrier.DEVIS to "📄 Devis")
                        .forEach { (k, v) -> HudChip(v, type == k) { type = k } }
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudChip("À traiter", filtreEtat == "a_traiter") { filtreEtat = "a_traiter" }
                    HudChip("Traités", filtreEtat == "traites") { filtreEtat = "traites" }
                    HudChip("Tout", filtreEtat == "tout") { filtreEtat = "tout" }
                }
            }
        }
        if (l.isEmpty()) item {
            Text(if (tout.isEmpty()) "Rien pour l'instant. (Réglages › Analyse des SMS et mails pour l'activer.)" else "Rien dans ce filtre.",
                color = HudDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        }
        items(l, key = { it.id }) { e ->
            HudPanel(Modifier.fillMaxWidth()) {
                MarkdownText(Courrier.ligne(e) + if (e.traite) " · ✅ traité" else "")
                Text(e.source + " · " + java.time.Instant.ofEpochMilli(e.ts).atZone(java.time.ZoneId.systemDefault()).toLocalDate().let { fr.dd.jarvis.ai.DatesFr.libelle(it, LocalDate.now()) },
                    color = HudDim, fontSize = 10.sp)
                Text(e.resume.take(200), color = HudDim, fontSize = 11.sp)
                // Classement
                Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudChip("🏢 Société", e.domaine == Courrier.SOCIETE) { Courrier.classer(app, e.id, Courrier.SOCIETE); version++ }
                    HudChip("🏠 Perso", e.domaine == Courrier.PERSO) { Courrier.classer(app, e.id, Courrier.PERSO); version++ }
                    if (e.domaine.isNotBlank()) Text(if (e.auto) "classé par JARVIS" else "classé par toi", color = HudDim, fontSize = 10.sp, fontWeight = FontWeight.Light,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                }
                // Actions
                Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (e.type == Courrier.FACTURE && e.montant != null && e.domaine != Courrier.PERSO) HudChip("💶 En note de frais") {
                        Frais.ajouter(app, NoteFrais("", LocalDate.now().toString(), e.emetteur, "Facture reçue (${e.source})", Frais.categorieDe(e.emetteur), e.montant, source = "message"))
                        if (e.domaine.isBlank()) Courrier.classer(app, e.id, Courrier.SOCIETE)
                        Courrier.marquer(app, e.id); version++
                    }
                    if (e.type == Courrier.RDV && e.dateRdv != null) HudChip("📅 Ajouter à l'agenda") {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { fr.dd.jarvis.ai.ToolBox(app) { _, _ -> true }.execute("create_event", Courrier.argsEvenement(e)) }
                                    .getOrElse { fr.dd.jarvis.ai.ToolResult("Échec de création : " + (it.message ?: "erreur inconnue")) }
                            }
                            actionInfo = result.text
                            if (result.text.startsWith("Événement «")) {
                                Courrier.marquer(app, e.id)
                                version++
                            }
                        }
                    }
                    if (e.lien.isNotBlank()) HudChip("🔗 Suivi") {
                        runCatching { app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(e.lien)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    if (!e.traite) HudChip("✅ Traité") { Courrier.marquer(app, e.id); version++ } else HudChip("↩️ À traiter") { Courrier.marquer(app, e.id, false); version++ }
                    HudChip("🗑️ Supprimer") { Courrier.supprimer(app, e.id); version++ }
                }
            }
        }
    }
}
