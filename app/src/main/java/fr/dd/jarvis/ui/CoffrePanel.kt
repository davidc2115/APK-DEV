package fr.dd.jarvis.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.coffre.Coffre
import fr.dd.jarvis.coffre.CoffreCrypto
import fr.dd.jarvis.coffre.CoffreInstallation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** QR code (noir sur blanc) d'un texte. */
private fun qr(texte: String, taille: Int = 720): Bitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(texte, com.google.zxing.BarcodeFormat.QR_CODE, taille, taille,
        mapOf(com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M, com.google.zxing.EncodeHintType.MARGIN to 2))
    val px = IntArray(taille * taille) { i -> if (m.get(i % taille, i / taille)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, taille, taille, Bitmap.Config.ARGB_8888)
}

private fun copier(ctx: Context, libelle: String, texte: String) =
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(libelle, texte))

/**
 * Coffre-fort de la société : installation sur le serveur, état de la synchronisation, ajout d'appareils (QR code),
 * récupération sur un nouveau téléphone, liste et révocation des appareils, code de récupération.
 */
@Composable
fun CoffrePanel(app: JarvisApp) {
    val s = app.store
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var occupe by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var invitation by remember { mutableStateOf<Pair<String, String>?>(null) }
    var appareils by remember { mutableStateOf<List<JSONObject>?>(null) }
    var voirCode by remember { mutableStateOf(false) }
    var saisie by remember { mutableStateOf("") }
    var adresse by remember { mutableStateOf("") }
    fun lancer(f: suspend () -> String) { if (occupe) return; occupe = true; msg = "⏳ …"; scope.launch { msg = runCatching { f() }.getOrElse { "❌ ${it.message}" }; occupe = false; tick++ } }

    HudPanel(Modifier.fillMaxWidth(), title = "🗄️ Coffre-fort de la société (serveur)") {
        Text("Les données de la société (clients, devis, factures, planning, interventions, photos, paie) sont chiffrées sur ce téléphone puis gardées sur TON serveur, " +
            "avec l'historique des versions, une corbeille de 30 jours et une sauvegarde chaque nuit. Chaque installation de JARVIS (ton autre téléphone, une tablette, un salarié) " +
            "s'y connecte avec ses propres droits ; un téléphone perdu se récupère avec ton code.", color = HudDim, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        if (!Coffre.configure(app)) {
            // ---------------------------------------------------------------- pas encore relié
            Text("Ce téléphone n'est relié à aucun coffre.", color = HudText, fontSize = 12.sp)
            HudButton(if (occupe) "Installation…" else "🛠️ Installer le coffre sur mon serveur (SSH)", Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !occupe, filled = true) {
                lancer { CoffreInstallation.installer(app) { p -> msg = "⏳ $p" } }
            }
            Text("Installe le service « coffre-jarvis » (Docker, ~50 Mo de mémoire) à côté de Home Assistant, puis enregistre ce téléphone comme administrateur.",
                color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(8.dp))
            Text("Ou relier ce téléphone à un coffre existant :", color = HudText, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudChip("📷 Scanner une invitation") {
                    val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
                    com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(ctx, opts).startScan()
                        .addOnSuccessListener { b -> b.rawValue?.let { v -> lancer { CoffreInstallation.rejoindre(app, v) } } }
                        .addOnFailureListener { e -> msg = "❌ Lecture du QR code impossible : ${e.message}" }
                }
            }
            HudTextField(saisie, { saisie = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = "…ou colle une invitation, ton kit ou ton code de récupération", singleLine = false, maxLines = 3)
            if (saisie.isNotBlank() && !saisie.trim().startsWith("JARVIS")) HudTextField(adresse, { adresse = it }, Modifier.fillMaxWidth().padding(top = 4.dp),
                label = "Adresse du coffre (avec le code de récupération)", placeholder = "82.65.75.176 ou mon-nom.freeboxos.fr")
            HudButton("Relier ce téléphone", Modifier.padding(top = 4.dp), enabled = saisie.isNotBlank() && !occupe) {
                val t = saisie.trim()
                lancer { if (t.startsWith("JARVISCOFFRE:")) CoffreInstallation.rejoindre(app, t) else CoffreInstallation.recuperer(app, t, adresse) }
            }
        } else {
            // ---------------------------------------------------------------- relié
            val derniere = s.coffreDerniere.value.toLongOrNull() ?: 0L
            Text("✅ Relié au coffre · ${Coffre.ROLES[s.coffreRole.value] ?: s.coffreRole.value}", color = HudText, fontSize = 12.sp)
            Text((s.coffreUrlLocal.value.ifBlank { "—" }) + (s.coffreUrlDistant.value.takeIf { it.isNotBlank() }?.let { " · hors maison : $it" } ?: ""), color = HudDim, fontSize = 11.sp)
            Text("Dernière synchro : " + (if (derniere > 0) java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE).format(java.util.Date(derniere)) else "jamais") +
                (s.coffreBilan.value.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), color = HudDim, fontSize = 11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudChip(if (occupe) "⏳" else "🔄 Synchroniser maintenant") { lancer { Coffre.synchroniser(app) } }
                HudChip(if (s.coffreAuto.value) "✅ Automatique (15 min + à chaque modification)" else "Synchro manuelle", s.coffreAuto.value) {
                    s.coffreAuto.set(!s.coffreAuto.value); if (s.coffreAuto.value) Coffre.planifier(app)
                }
            }
            if (Coffre.estAdmin(app)) {
                Spacer(Modifier.height(8.dp))
                Text("Ajouter un appareil", color = HudText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Coffre.ROLES.forEach { (role, libelle) ->
                        HudChip("＋ $libelle") { lancer { val t = CoffreInstallation.inviter(app, role, ""); invitation = role to t; "Invitation prête : scanne ce QR code avec l'autre téléphone (JARVIS › Réglages › Coffre-fort › Scanner une invitation). Valable 1 h, une seule fois." } }
                    }
                }
                invitation?.let { (role, texte) ->
                    val image = remember(texte) { qr(texte).asImageBitmap() }
                    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(image, "QR code d'invitation", Modifier.size(240.dp).background(Color.White))
                        Text("Invitation « ${Coffre.ROLES[role]} »", color = HudDim, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HudChip("📋 Copier (à transmettre en main propre)") { copier(ctx, "Invitation coffre JARVIS", texte) }
                            HudChip("Fermer") { invitation = null }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudChip("📱 Appareils reliés") { scope.launch { appareils = runCatching { withContext(Dispatchers.IO) { Coffre.client(app).appareils() } }.getOrElse { msg = "❌ ${it.message}"; null }?.let { o ->
                        o.getJSONArray("appareils").let { a -> (0 until a.length()).map { a.getJSONObject(it) } } } } }
                    HudChip(if (voirCode) "Masquer le code" else "🔑 Code de récupération") { voirCode = !voirCode }
                    HudChip("🌍 Ouvrir le port $CoffreInstallation_PORT sur la Freebox") { lancer { CoffreInstallation.ouvrirPort(app) } }
                }
                appareils?.let { l ->
                    l.forEach { a ->
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text((if (a.optBoolean("revoque")) "⛔ " else if (a.optBoolean("moi")) "📍 " else "📱 ") + a.optString("nom") + " · " +
                                (Coffre.ROLES[a.optString("role")]?.substringBefore(" (") ?: a.optString("role")) +
                                " · vu " + java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE).format(java.util.Date(a.optLong("vu") * 1000)),
                                color = if (a.optBoolean("revoque")) HudDim else HudText, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            if (!a.optBoolean("revoque") && !a.optBoolean("moi")) HudChip("Révoquer") {
                                lancer { withContext(Dispatchers.IO) { Coffre.client(app).revoquer(a.getString("id")) }; appareils = null; "⛔ ${a.optString("nom")} n'a plus accès au coffre." }
                            }
                        }
                    }
                }
                if (voirCode) {
                    val maitre = s.coffreMaitre.value.takeIf { it.isNotBlank() }?.let { CoffreCrypto.deB64(it) }
                    if (maitre != null) {
                        val code = CoffreCrypto.codeRecuperation(maitre)
                        Text("⚠️ Garde ce code en lieu sûr (papier rangé, gestionnaire de mots de passe), jamais dans une note du téléphone : " +
                            "avec lui et l'adresse du coffre, n'importe quel téléphone retrouve TOUTES les données de la société.", color = HudText, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                        Text(code, color = LocalAccent.current, fontSize = 15.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HudChip("📋 Copier le code") { copier(ctx, "Code de récupération", code) }
                            HudChip("📋 Copier le kit complet (adresse + code)") { copier(ctx, "Kit de récupération du coffre", CoffreInstallation.kit(app)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            HudChip("Délier ce téléphone du coffre") { Coffre.oublier(app); invitation = null; appareils = null; msg = "Ce téléphone n'est plus relié (les données restent sur le coffre et sur ce téléphone)." }
        }
        if (msg.isNotBlank()) Text(msg, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

private const val CoffreInstallation_PORT = CoffreInstallation.PORT
