package fr.dd.jarvis.device

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.service.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pilote visuel du téléphone : Jarvis VOIT l'écran (capture réelle + arbre d'accessibilité numéroté) et AGIT dans
 * n'importe quelle application (appuyer, saisir, faire défiler, revenir, ouvrir une appli…), une action à la fois,
 * en vérifiant après chaque geste que l'écran a réagi comme prévu. Le modèle de vision Gemini décide de l'action ;
 * les gestes passent par le service d'accessibilité de Jarvis.
 *
 * Garde-fous : jamais de mot de passe, code ou donnée bancaire saisis ; tout envoi, paiement, achat, suppression,
 * appel ou publication non demandé explicitement est soumis à confirmation de l'utilisateur.
 */
object PiloteEcran {
    private const val MAX_ETAPES = 18

    private class Elt(val n: Int, val node: AccessibilityNodeInfo, val label: String, val cls: String, val r: Rect,
                      val clic: Boolean, val saisie: Boolean, val defil: Boolean, val coche: Boolean?)

    private val SYSTEME = """
Tu pilotes un smartphone Android à la place de son propriétaire, comme une personne qui regarde l'écran et touche les boutons.
À chaque tour tu reçois l'OBJECTIF, les actions déjà faites, la CAPTURE de l'écran actuel et la liste numérotée des éléments d'interface.
Réponds UNIQUEMENT par un objet JSON :
{"observation":"ce que tu vois d'utile pour l'objectif","action":"cliquer|saisir|appui_long|defiler_bas|defiler_haut|glisser_gauche|glisser_droite|retour|accueil|ouvrir_app|attendre|confirmer|terminer|echec","element":numéro de la liste ou 0,"x":0,"y":0,"texte":"texte à saisir, nom de l'application à ouvrir, ou question de confirmation","valider":true ou false,"resultat":"pour terminer/echec : compte rendu factuel de ce qui est réellement visible"}
Règles :
- Une seule action par réponse. Désigne de préférence l'élément par son numéro ; sinon donne x,y en pixels de la CAPTURE.
- saisir : l'élément est le champ de saisie ; valider=true pour appuyer ensuite sur Entrée/Rechercher.
- Avant d'enchaîner, vérifie sur la capture que l'action précédente a eu l'effet attendu ; si l'écran n'a pas changé, essaie autrement (autre élément, défilement, retour, attendre un chargement).
- Ferme les fenêtres gênantes (publicité, cookies, mise à jour, notation) sans rien accepter de payant.
- terminer uniquement quand l'objectif est VISIBLEMENT atteint (ex. musique : bouton pause affiché et bon titre visible). echec si c'est impossible (connexion requise, contenu introuvable, écran protégé) en expliquant pourquoi.
- Sécurité : ne saisis jamais de mot de passe, code PIN, code de validation ni donnée bancaire. Avant tout envoi de message ou de mail, paiement, achat, abonnement, suppression, appel ou publication qui n'est pas EXPLICITEMENT demandé dans l'objectif, réponds action=confirmer avec la question à poser dans « texte ».
""".trimIndent()

    private fun service(): JarvisAccessibilityService? = JarvisAccessibilityService.instance

    fun disponible(app: JarvisApp) = service() != null && app.store.geminiKeys.isNotEmpty()

    private fun pourquoiIndisponible(app: JarvisApp): String = when {
        service() == null -> "❌ Le service d'accessibilité JARVIS n'est pas actif : active-le dans Réglages Android › Accessibilité › JARVIS (sur Xiaomi : autoriser aussi le démarrage automatique et la batterie sans restriction)."
        app.store.geminiKeys.isEmpty() -> "❌ Il me faut une clé Gemini (Réglages › Agents Gemini) pour voir l'écran."
        else -> ""
    }

    // ------------------------------------------------------------------ voir

    /** Capture de l'écran : accessibilité (Android 11+), sinon ADB/Shizuku si disponible. */
    fun capturer(app: JarvisApp): Bitmap? {
        service()?.capture()?.let { return it }
        if (!PhoneShell.ready(app)) return null
        return runCatching {
            val f = File(app.getExternalFilesDir(null), "ecran_shell.png")
            f.delete()
            PhoneShell.exec(app, "screencap -p '${f.absolutePath}'")
            android.graphics.BitmapFactory.decodeFile(f.absolutePath).also { f.delete() }
        }.getOrNull()
    }

    private fun jpeg(b: Bitmap, largeurMax: Int = 720, qualite: Int = 70): Pair<ByteArray, Float> {
        val echelle = if (b.width > largeurMax) largeurMax.toFloat() / b.width else 1f
        val r = if (echelle < 1f) Bitmap.createScaledBitmap(b, (b.width * echelle).toInt(), (b.height * echelle).toInt(), true) else b
        val out = ByteArrayOutputStream(); r.compress(Bitmap.CompressFormat.JPEG, qualite, out)
        return out.toByteArray() to echelle
    }

    /** Enregistre la capture pour l'afficher dans la conversation (« ![…](jarvis-img:…) »). */
    fun enregistrer(app: JarvisApp, b: Bitmap, prefixe: String = "ecran"): String? = runCatching {
        val dossier = File(app.filesDir, "images").apply { mkdirs() }
        dossier.listFiles()?.filter { it.name.startsWith("ecran_") }?.sortedByDescending { it.lastModified() }?.drop(15)?.forEach { it.delete() }
        val nom = "ecran_${prefixe}_${System.currentTimeMillis()}.jpg"
        File(dossier, nom).writeBytes(jpeg(b, 900, 80).first)
        nom
    }.getOrNull()

    private fun applicationAuPremierPlan(): String = runCatching { service()?.rootInActiveWindow?.packageName?.toString() }.getOrNull().orEmpty()

    /** « Regarde mon écran » : capture + description par Gemini (et réponse à la question), image jointe. */
    suspend fun voir(app: JarvisApp, question: String): String = withContext(Dispatchers.IO) {
        pourquoiIndisponible(app).takeIf { it.isNotBlank() }?.let { return@withContext it }
        val b = capturer(app) ?: return@withContext "❌ Capture d'écran refusée par Android (écran protégé, ou capacité de capture pas encore accordée : désactive puis réactive l'accessibilité JARVIS une fois)." +
            "\n\nCe que l'accessibilité lit :\n" + (service()?.inspectScreen()?.take(3000) ?: "")
        val (octets, _) = jpeg(b)
        val q = "Application au premier plan : ${applicationAuPremierPlan().ifBlank { "inconnue" }}.\n" +
            (if (question.isNotBlank()) "Question de l'utilisateur : $question\n" else "") +
            "Décris en français ce qui est affiché à l'écran (application, contenu principal, état : lecture en cours, erreur, formulaire…) et réponds précisément à la question s'il y en a une. " +
            "Réponds en JSON : {\"reponse\":\"…\"}"
        val rep = runCatching { app.llm.gemini.visionJson("", octets, "image/jpeg", q) }.getOrElse { return@withContext "❌ Analyse de l'écran impossible : ${it.message}" }
        val texte = runCatching { JSONObject(rep.substring(rep.indexOf('{'), rep.lastIndexOf('}') + 1)).optString("reponse") }.getOrNull().orEmpty().ifBlank { rep }
        val nom = enregistrer(app, b)
        texte + (if (nom != null) "\n\n![Écran](jarvis-img:$nom)" else "")
    }

    // ------------------------------------------------------------------ agir

    private fun elements(): Pair<String, List<Elt>> {
        val s = service() ?: return "" to emptyList()
        val root = s.rootInActiveWindow ?: return "" to emptyList()
        val pkg = root.packageName?.toString().orEmpty()
        val out = mutableListOf<Elt>()
        fun walk(n: AccessibilityNodeInfo?, profondeur: Int) {
            if (n == null || out.size >= 150 || profondeur > 40) return
            val r = Rect(); n.getBoundsInScreen(r)
            if (n.isVisibleToUser && !r.isEmpty) {
                val texte = when {
                    n.isPassword -> "[champ protégé]"
                    else -> (n.text?.toString() ?: n.contentDescription?.toString()
                        ?: (if (android.os.Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null)).orEmpty()
                }.replace('\n', ' ').trim()
                val id = n.viewIdResourceName?.substringAfterLast('/').orEmpty()
                val utile = texte.isNotBlank() || n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
                if (utile) out += Elt(out.size + 1, n, texte.ifBlank { id }.take(90), n.className?.toString()?.substringAfterLast('.') ?: "View", r,
                    n.isClickable || n.isLongClickable, n.isEditable, n.isScrollable, if (n.isCheckable) n.isChecked else null)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), profondeur + 1)
        }
        walk(root, 0)
        return pkg to out
    }

    private fun decrire(l: List<Elt>): String = l.joinToString("\n") { e ->
        "[${e.n}] ${e.cls}" + (if (e.label.isNotBlank()) " « ${e.label} »" else "") + " (${e.r.left},${e.r.top},${e.r.right},${e.r.bottom})" +
            (if (e.clic) " cliquable" else "") + (if (e.saisie) " saisie" else "") + (if (e.defil) " défilable" else "") +
            (e.coche?.let { if (it) " coché" else " non coché" } ?: "")
    }.ifBlank { "(aucun élément exposé : utilise la capture et des coordonnées)" }

    private fun cliquable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        return c
    }

    private fun centre(e: Elt) = e.r.exactCenterX() to e.r.exactCenterY()

    /** Exécute une action ; renvoie un compte rendu court (pour l'historique). */
    private suspend fun executer(app: JarvisApp, j: JSONObject, l: List<Elt>, echelle: Float, largeur: Int, hauteur: Int): String {
        val s = service() ?: return "service d'accessibilité perdu"
        val e = l.firstOrNull { it.n == j.optInt("element", 0) }
        fun xy(): Pair<Float, Float>? = e?.let { centre(it) } ?: run {
            val x = j.optDouble("x", 0.0); val y = j.optDouble("y", 0.0)
            if (x <= 0 && y <= 0) null else (x / echelle).toFloat() to (y / echelle).toFloat()
        }
        val cible = e?.let { "[${it.n}] « ${it.label} »" } ?: "(${j.optInt("x")},${j.optInt("y")})"
        return when (j.optString("action")) {
            "cliquer" -> {
                val ok = e?.let { cliquable(it.node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true
                if (ok) "clic sur $cible" else xy()?.let { (x, y) -> if (s.tap(x, y)) "appui sur $cible" else "appui refusé" } ?: "cible introuvable"
            }
            "appui_long" -> {
                val ok = e?.let { cliquable(it.node)?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) } == true
                if (ok) "appui long sur $cible" else xy()?.let { (x, y) -> if (s.appuiLong(x, y)) "appui long sur $cible" else "appui long refusé" } ?: "cible introuvable"
            }
            "saisir" -> {
                val texte = j.optString("texte")
                val champ = e?.node ?: s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (champ == null) "aucun champ de saisie"
                else {
                    champ.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    champ.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    val ok = champ.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texte)
                    })
                    var r = if (ok) "saisi « ${texte.take(60)} »" else "saisie refusée"
                    if (ok && j.optBoolean("valider")) {
                        delay(300)
                        val entree = android.os.Build.VERSION.SDK_INT >= 30 &&
                            champ.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                        r += if (entree) " puis Entrée" else if (PhoneShell.ready(app)) { PhoneShell.exec(app, "input keyevent 66"); " puis Entrée (clavier)" } else " (Entrée indisponible : appuie sur la loupe)"
                    }
                    r
                }
            }
            "defiler_bas", "defiler_haut" -> {
                val bas = j.optString("action") == "defiler_bas"
                val zone = e?.node?.takeIf { it.isScrollable }
                val ok = zone?.performAction(if (bas) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true || s.scroll(bas)
                if (ok) "défilement ${if (bas) "vers le bas" else "vers le haut"}" else "défilement refusé"
            }
            "glisser_gauche" -> if (s.swipe(largeur * 0.85f, hauteur * 0.5f, largeur * 0.15f, hauteur * 0.5f)) "glissement vers la gauche" else "glissement refusé"
            "glisser_droite" -> if (s.swipe(largeur * 0.15f, hauteur * 0.5f, largeur * 0.85f, hauteur * 0.5f)) "glissement vers la droite" else "glissement refusé"
            "retour" -> if (s.global(AccessibilityService.GLOBAL_ACTION_BACK)) "retour" else "retour refusé"
            "accueil" -> if (s.global(AccessibilityService.GLOBAL_ACTION_HOME)) "écran d'accueil" else "accueil refusé"
            "ouvrir_app" -> app.phone.openApp(j.optString("texte"))
            "attendre" -> { delay(2500); "attente du chargement" }
            else -> "action inconnue « ${j.optString("action")} »"
        }
    }

    /**
     * Atteint [objectif] en pilotant l'écran. [verifier] (facultatif) confirme le résultat par un moyen indépendant
     * (ex. session média réellement en lecture) ; [confirmer] pose une question à l'utilisateur (actions sensibles).
     */
    suspend fun piloter(
        app: JarvisApp, objectif: String, onStatus: (String) -> Unit = {},
        confirmer: suspend (String) -> Boolean = { false }, verifier: (suspend () -> String?)? = null
    ): String = withContext(Dispatchers.IO) {
        pourquoiIndisponible(app).takeIf { it.isNotBlank() }?.let { return@withContext it }
        val historique = mutableListOf<String>()
        var derniereImage: Bitmap? = null
        var verificationsRatees = 0
        var memeEcran = 0
        var empreintePrecedente = ""
        for (etape in 1..MAX_ETAPES) {
            val (pkg, l) = elements()
            val b = capturer(app)
            if (b != null) derniereImage = b
            val m = app.resources.displayMetrics
            val (octets, echelle) = if (b != null) jpeg(b) else (null to 1f)
            val empreinte = pkg + l.take(40).joinToString("|") { it.label }
            memeEcran = if (empreinte == empreintePrecedente) memeEcran + 1 else 0
            empreintePrecedente = empreinte
            val q = buildString {
                append("OBJECTIF : $objectif\n")
                append("Étape $etape/$MAX_ETAPES. Application au premier plan : ${pkg.ifBlank { "inconnue" }}. ")
                append("Écran ${m.widthPixels}×${m.heightPixels} px")
                if (b != null) append(" ; capture jointe réduite à ${(b.width * echelle).toInt()}×${(b.height * echelle).toInt()} px (coordonnées x,y en pixels de la capture)")
                append(".\n")
                if (memeEcran >= 2) append("ATTENTION : l'écran n'a pas changé depuis ${memeEcran} actions, change de méthode.\n")
                append("ACTIONS DÉJÀ FAITES :\n").append(historique.takeLast(10).joinToString("\n").ifBlank { "(aucune)" }).append("\n")
                append("ÉLÉMENTS (accessibilité, coordonnées en pixels écran) :\n").append(decrire(l))
            }
            onStatus("👁️ Écran : étape $etape…")
            val brut = runCatching {
                if (octets != null) app.llm.gemini.visionJson(SYSTEME, octets, "image/jpeg", q)
                else app.llm.agent(SYSTEME, q + "\n(Capture indisponible : décide avec la liste d'éléments.)")
            }.getOrElse { return@withContext "❌ Pilotage interrompu (vision indisponible) : ${it.message}" + recap(historique) }
            val j = runCatching { JSONObject(brut.substring(brut.indexOf('{'), brut.lastIndexOf('}') + 1)) }.getOrNull()
            if (j == null) { historique += "$etape. réponse illisible du modèle"; continue }
            val action = j.optString("action")
            when (action) {
                "terminer" -> {
                    val controle = verifier?.invoke()
                    if (verifier != null && controle == null && verificationsRatees < 2) {
                        verificationsRatees++
                        historique += "$etape. tu as dit « terminé » mais le contrôle du système indique que l'objectif n'est PAS atteint : continue."
                        continue
                    }
                    return@withContext fin(app, "✅ " + j.optString("resultat").ifBlank { "Objectif atteint." } +
                        (controle?.let { "\n$it" } ?: ""), historique, derniereImage)
                }
                "echec" -> return@withContext fin(app, "⚠️ " + j.optString("resultat").ifBlank { "Je n'ai pas pu atteindre l'objectif." }, historique, derniereImage)
                "confirmer" -> {
                    val question = j.optString("texte").ifBlank { "Continuer cette action sur le téléphone ?" }
                    if (!confirmer(question)) return@withContext fin(app, "⏹️ Arrêté : tu n'as pas confirmé « $question ».", historique, derniereImage)
                    historique += "$etape. l'utilisateur a CONFIRMÉ : $question"
                }
                else -> {
                    val r = executer(app, j, l, echelle, m.widthPixels, m.heightPixels)
                    historique += "$etape. $action → $r" + j.optString("observation").takeIf { it.isNotBlank() }?.let { " (vu : ${it.take(120)})" }.orEmpty()
                    onStatus("👆 ${r.take(60)}")
                    delay(if (action == "ouvrir_app") 2500 else 1300)
                }
            }
        }
        fin(app, "⚠️ Pas terminé après $MAX_ETAPES étapes.", historique, derniereImage)
    }

    private fun recap(h: List<String>) = if (h.isEmpty()) "" else "\n\nÉtapes :\n" + h.takeLast(12).joinToString("\n") { "• $it" }

    private fun fin(app: JarvisApp, texte: String, h: List<String>, image: Bitmap?): String {
        val finale = capturer(app) ?: image
        val nom = finale?.let { enregistrer(app, it) }
        return texte + recap(h) + (if (nom != null) "\n\n![Écran](jarvis-img:$nom)" else "")
    }
}
