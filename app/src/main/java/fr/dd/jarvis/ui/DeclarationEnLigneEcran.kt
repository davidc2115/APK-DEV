package fr.dd.jarvis.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.DeclarationEnLigne
import fr.dd.jarvis.societe.Declarations
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Navigateur de JARVIS pour faire une déclaration sur le site officiel : il se connecte, navigue, remplit, et demande
 * l'accord avant le bouton final. L'utilisateur voit tout, peut reprendre la main (taper un code reçu, corriger un champ)
 * puis toucher « Continuer ».
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeclarationEnLigneEcran(app: JarvisApp, echeanceId: String, onFermer: () -> Unit) {
    val e = remember(echeanceId) { Declarations.echeances(app.societe.data).firstOrNull { it.id == echeanceId } }
    if (e == null) {
        HudPanel(Modifier.padding(12.dp).fillMaxWidth(), title = "Déclaration") {
            Text("Cette échéance n'existe plus (déjà passée ?).", color = HudText, fontSize = 13.sp)
            HudButton("Fermer") { onFermer() }
        }
        return
    }
    val scope = rememberCoroutineScope()
    val journal = remember { mutableStateListOf<String>() }
    var statut by remember { mutableStateOf("Préparation…") }
    var web by remember { mutableStateOf<WebView?>(null) }
    var enCours by remember { mutableStateOf<Job?>(null) }
    var attente by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    var validation by remember { mutableStateOf<Pair<String, CompletableDeferred<Boolean>>?>(null) }
    var fini by remember { mutableStateOf(false) }
    var tourne by remember { mutableStateOf(false) }
    var voirJournal by remember { mutableStateOf(false) }
    val chargement = remember { longArrayOf(0L) }

    suspend fun js(w: WebView, code: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c ->
            w.evaluateJavascript(code) { r ->
                val t = runCatching { org.json.JSONArray("[$r]").optString(0) }.getOrDefault(r.orEmpty())
                if (c.isActive) c.resume(if (t == "null") "" else t)
            }
        }
    }

    suspend fun stable(w: WebView) {
        delay(1200)
        for (i in 0 until 16) {
            if (js(w, "document.readyState") == "complete" && System.currentTimeMillis() - chargement[0] > 900) break
            delay(500)
        }
        delay(600)
    }

    fun noter(t: String) { journal += t; statut = t }

    fun lancer() {
        enCours?.cancel()
        enCours = scope.launch {
          tourne = true
          try {
            val w = web ?: return@launch
            val consigne = DeclarationEnLigne.consigne(e, app.societe.data.societe)
            val faits = mutableListOf<String>()
            val connexions = mutableMapOf<String, Int>()
            var valide = false
            var derniere = ""
            var repetitions = 0
            for (etape in 1..40) {
                stable(w)
                val brut = js(w, DeclarationEnLigne.JS_LIRE)
                val url = DeclarationEnLigne.adresse(brut)
                if (url.isNotBlank() && !DeclarationEnLigne.officiel(url)) { noter("⛔ Page hors des sites officiels ($url) : j'arrête par sécurité."); return@launch }
                val site = DeclarationEnLigne.site(url)
                val texte = DeclarationEnLigne.page(brut)
                statut = "🤖 Étape $etape : je lis la page…"
                val reponse = runCatching {
                    withContext(Dispatchers.IO) {
                        app.llm.agent(consigne, "Étape $etape.\nACTIONS DÉJÀ FAITES :\n" + faits.takeLast(12).joinToString("\n").ifBlank { "(aucune)" } + "\n\n" + texte)
                    }
                }.getOrElse { noter("❌ IA indisponible : ${it.message?.take(120)}"); return@launch }
                val a = DeclarationEnLigne.action(reponse) ?: run { noter("⚠️ Réponse illisible de l'IA, je réessaie."); faits += "(réponse illisible)"; null } ?: continue
                val cle = "${a.action}|${a.id}|${a.texte}|$url"
                repetitions = if (cle == derniere) repetitions + 1 else 0
                derniere = cle
                if (repetitions >= 2) { noter("⚠️ Je tourne en rond sur cette page : à toi de jouer, puis touche Continuer."); attente = CompletableDeferred<Unit>().also { it.await() }; attente = null; repetitions = 0; continue }
                if (a.pensee.isNotBlank()) journal += "💭 ${a.pensee.take(160)}"
                when (a.action) {
                    "connexion" -> {
                        if (site == null) { noter("🔐 Page de connexion inconnue : connecte-toi toi-même, puis touche Continuer."); attente = CompletableDeferred<Unit>().also { it.await() }; attente = null; faits += "connexion faite par l'utilisateur"; continue }
                        val id = DeclarationEnLigne.identifiants(app, site.cle)
                        val essais = connexions[site.cle] ?: 0
                        if (id == null || essais >= 3) {
                            noter(if (id == null) "🔐 Aucun identifiant enregistré pour « ${site.nom} » : connecte-toi toi-même (ou enregistre-les dans Société › Déclarations), puis touche Continuer."
                                else "🔐 La connexion échoue : vérifie l'identifiant et le mot de passe de « ${site.nom} », connecte-toi, puis touche Continuer.")
                            attente = CompletableDeferred<Unit>().also { it.await() }; attente = null; faits += "connexion faite par l'utilisateur"; continue
                        }
                        connexions[site.cle] = essais + 1
                        val r = js(w, DeclarationEnLigne.jsConnexion(id.login, id.mdp))
                        noter("🔐 Connexion à ${site.nom} : ${if (r == "ok") "identifiants saisis" else if (r == "identifiant") "identifiant saisi" else r}")
                        faits += "connexion ($r)"
                    }
                    "cliquer" -> { val r = js(w, DeclarationEnLigne.jsCliquer(a.id)); noter("👆 Clic [${a.id}] ${r.takeIf { it != "ok" }.orEmpty()}"); faits += "cliquer ${a.id} → $r" }
                    "saisir" -> { val r = js(w, DeclarationEnLigne.jsSaisir(a.id, a.texte)); noter("⌨️ [${a.id}] = ${a.texte} ${r.takeIf { it != "ok" }.orEmpty()}"); faits += "saisir ${a.id} = ${a.texte} → $r" }
                    "choisir" -> { val r = js(w, DeclarationEnLigne.jsChoisir(a.id, a.texte)); noter("☑️ [${a.id}] → ${a.texte} $r"); faits += "choisir ${a.id} = ${a.texte} → $r" }
                    "cocher", "decocher" -> { val r = js(w, DeclarationEnLigne.jsCocher(a.id, a.action == "cocher")); noter("☑️ ${a.action} [${a.id}] $r"); faits += "${a.action} ${a.id} → $r" }
                    "defiler" -> { js(w, DeclarationEnLigne.JS_DEFILER); faits += "défiler" }
                    "attendre" -> { delay(2500); faits += "attendre" }
                    "aller" -> {
                        if (!DeclarationEnLigne.officiel(a.texte)) { noter("⛔ Adresse refusée (pas un site officiel) : ${a.texte}"); faits += "aller refusé : ${a.texte}"; continue }
                        withContext(Dispatchers.Main) { w.loadUrl(a.texte) }; noter("🌐 ${a.texte}"); faits += "aller ${a.texte}"
                    }
                    "code" -> {
                        noter("🔑 ${a.texte.ifBlank { "Le site demande une vérification" }} — fais-le dans la page, puis touche Continuer.")
                        attente = CompletableDeferred<Unit>().also { it.await() }; attente = null; faits += "vérification faite par l'utilisateur"
                    }
                    "valider_final" -> {
                        val ok = if (app.store.declarationValidationAuto.value) true
                            else CompletableDeferred<Boolean>().also { validation = a.recap.ifBlank { "Envoi de la déclaration." } to it }.await().also { validation = null }
                        if (!ok) { noter("✋ Envoi annulé : vérifie ou corrige dans la page, puis touche Continuer (ou ferme)."); attente = CompletableDeferred<Unit>().also { it.await() }; attente = null; faits += "envoi refusé par l'utilisateur ; il a pu corriger"; continue }
                        val r = js(w, DeclarationEnLigne.jsCliquer(a.id)); valide = true
                        noter("✅ Envoi validé : ${a.recap.take(200)}"); faits += "VALIDATION FINALE cliquée (${a.recap.take(120)}) → $r"
                    }
                    "termine" -> {
                        if (valide) { DeclarationEnLigne.terminer(app, e, a.texte, journal.toList()); noter("🎉 Terminé : ${a.texte.take(240)} — échéance marquée faite.") }
                        else noter("ℹ️ ${a.texte.take(240)}")
                        fini = true; return@launch
                    }
                    "echec" -> { noter("❓ ${a.texte.ifBlank { "Je ne sais pas continuer." }} — fais-le dans la page, puis touche Continuer."); attente = CompletableDeferred<Unit>().also { it.await() }; attente = null; faits += "l'utilisateur a repris la main (${a.texte.take(80)})" }
                    else -> faits += "action inconnue « ${a.action} »"
                }
            }
            noter("⏹️ Trop d'étapes : termine dans la page.")
          } finally { tourne = false }
        }
    }

    DisposableEffect(Unit) { onDispose { enCours?.cancel(); web?.destroy() } }

    Column(Modifier.fillMaxSize()) {
        HudPanel(Modifier.fillMaxWidth().padding(8.dp), title = "🤖 ${e.titre}") {
            Text(e.organisme + (if (e.montant.isNotBlank()) " · ${e.montant}" else ""), color = HudDim, fontSize = 11.sp)
            Text(statut, color = HudText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (attente != null) HudChip("▶️ Continuer", true) { attente?.complete(Unit) }
                else if (!tourne && !fini) HudChip("▶️ Reprendre", true) { lancer() }
                if (tourne && attente == null) HudChip("⏸ Pause") { enCours?.cancel(); statut = "En pause : touche Reprendre." }
                HudChip(if (voirJournal) "Masquer le détail" else "Détail (${journal.size})") { voirJournal = !voirJournal }
                HudChip("✖ Fermer") { enCours?.cancel(); onFermer() }
            }
            if (voirJournal) Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
                journal.takeLast(60).forEach { Text(it, color = HudDim, fontSize = 10.sp) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(androidx.compose.ui.graphics.Color.White)) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { chargement[0] = System.currentTimeMillis() }
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { chargement[0] = Long.MAX_VALUE / 2 }
                        // Liens hors des sites officiels (publicité, réseaux…) : jamais ouverts dans ce navigateur.
                        // (ouverts dans le navigateur du téléphone, par exemple une page de paiement de la banque).
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString().orEmpty()
                            if (DeclarationEnLigne.officiel(u) || request?.isForMainFrame == false) return false
                            if (u.startsWith("https://")) runCatching { c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            return true
                        }
                    }
                    loadUrl(e.lien)
                    web = this
                }
            })
        }
    }

    LaunchedEffect(web) { if (web != null && enCours == null) { delay(800); lancer() } }

    validation?.let { (recap, d) ->
        AlertDialog(onDismissRequest = {},
            title = { Text("Envoyer la déclaration ?") },
            text = { Text("$recap\n\nVérifie aussi la page derrière. JARVIS cliquera sur le bouton d'envoi si tu confirmes.", fontSize = 13.sp) },
            confirmButton = { TextButton({ d.complete(true) }) { Text("✅ Envoyer") } },
            dismissButton = { TextButton({ d.complete(false) }) { Text("Pas encore") } })
    }
}
