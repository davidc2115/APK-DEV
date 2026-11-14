package fr.dd.jarvis.securite

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Serveur
import fr.dd.jarvis.ai.ToolBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Modes / « systèmes » de JARVIS : des modes complets créés, réglés, activés, désactivés et supprimés à la demande
 * (« crée un mode cinéma qui ferme les volets et baisse les lumières », « installe le mode maison sécurisée »,
 * « ajoute le numéro de Marie », « active la maison sécurisée »…). Une demande en français suffit : Jarvis la traduit
 * en étapes concrètes avec les vrais appareils de Home Assistant (voir [composer]). Chaque mode a :
 *  - des étapes d'activation et de désactivation (service Home Assistant, action du téléphone, SMS, demande en français) ;
 *  - pour la maison : une surveillance (capteurs de personne → photos des caméras → notification Home Assistant avec
 *    image et/ou MMS aux numéros configurés, sirène en option).
 * Modèles prêts à l'emploi : téléphone sécurisé, maison sécurisée ; n'importe quel autre système peut être composé.
 */
object Systemes {
    data class Etape(val type: String, val cible: String = "", val entites: List<String> = emptyList(), val texte: String = "")

    data class Systeme(
        val id: String,
        val nom: String,
        val modele: String = "personnalise",
        val phrasesActiver: List<String> = emptyList(),
        val phrasesDesactiver: List<String> = emptyList(),
        val activation: List<Etape> = emptyList(),
        val desactivation: List<Etape> = emptyList(),
        val capteurs: List<String> = emptyList(),
        val cameras: List<String> = emptyList(),
        val notifierHa: List<String> = emptyList(),
        val destinataires: List<String> = emptyList(),
        val sirenes: List<String> = emptyList(),
        val sireneSurDetection: Boolean = false,
        val viaHa: Boolean = true,
        val viaMms: Boolean = true,
        val automationHa: String = "",
        val actif: Boolean = false,
        val activeLe: Long = 0L,
        val description: String = "",
    ) {
        /** Mode de sécurité : sa désactivation exige la voix du propriétaire quand le téléphone est verrouillé. */
        val securite get() = modele in setOf("telephone_securise", "maison_securisee") || capteurs.isNotEmpty()
    }

    /** Début de la question de confirmation d'une désactivation (voix du propriétaire exigée si le téléphone est verrouillé). */
    const val QUESTION = "🛡️ Désactiver le système de sécurité"

    val MODELES = linkedMapOf(
        "telephone_securise" to "📱 Téléphone sécurisé (veille + verrouillage, déverrouillage par ta voix)",
        "maison_securisee" to "🏠 Maison sécurisée (portes, volets, alarme, photos si une personne est détectée)",
        "personnalise" to "🧩 Mode personnalisé (décris-le, Jarvis le compose)",
    )

    // ------------------------------------------------------------------ stockage

    private fun fichier(app: JarvisApp) = File(app.filesDir, "systemes.json")

    @Synchronized fun liste(app: JarvisApp): List<Systeme> =
        runCatching { depuisJson(JSONArray(fichier(app).readText())) }.getOrDefault(emptyList())

    @Synchronized private fun enregistrer(app: JarvisApp, l: List<Systeme>) { fichier(app).writeText(versJson(l).toString(2)) }

    @Synchronized fun sauver(app: JarvisApp, s: Systeme) {
        val l = liste(app).toMutableList()
        val i = l.indexOfFirst { it.id == s.id }
        if (i >= 0) l[i] = s else l += s
        enregistrer(app, l)
    }

    @Synchronized fun retirer(app: JarvisApp, id: String) = enregistrer(app, liste(app).filter { it.id != id })

    private fun etapesJson(l: List<Etape>) = JSONArray().also { a -> l.forEach { e -> a.put(JSONObject().put("type", e.type).put("cible", e.cible).put("entites", JSONArray(e.entites)).put("texte", e.texte)) } }
    private fun liste(o: JSONObject, k: String) = o.optJSONArray(k)?.let { x -> (0 until x.length()).map { x.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
    private fun etapes(o: JSONObject, k: String) = o.optJSONArray(k)?.let { x -> (0 until x.length()).mapNotNull { x.optJSONObject(it) }.map { e ->
        Etape(e.optString("type"), e.optString("cible"), liste(e, "entites"), e.optString("texte")) } }.orEmpty()

    internal fun versJson(l: List<Systeme>) = JSONArray().also { a ->
        l.forEach { s ->
            a.put(JSONObject().put("id", s.id).put("nom", s.nom).put("modele", s.modele)
                .put("phrasesActiver", JSONArray(s.phrasesActiver)).put("phrasesDesactiver", JSONArray(s.phrasesDesactiver))
                .put("activation", etapesJson(s.activation)).put("desactivation", etapesJson(s.desactivation))
                .put("capteurs", JSONArray(s.capteurs)).put("cameras", JSONArray(s.cameras)).put("notifierHa", JSONArray(s.notifierHa))
                .put("destinataires", JSONArray(s.destinataires)).put("sirenes", JSONArray(s.sirenes)).put("sireneSurDetection", s.sireneSurDetection)
                .put("viaHa", s.viaHa).put("viaMms", s.viaMms).put("automationHa", s.automationHa).put("actif", s.actif).put("activeLe", s.activeLe).put("description", s.description))
        }
    }

    internal fun depuisJson(a: JSONArray): List<Systeme> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
        Systeme(o.optString("id"), o.optString("nom"), o.optString("modele", "personnalise"), liste(o, "phrasesActiver"), liste(o, "phrasesDesactiver"),
            etapes(o, "activation"), etapes(o, "desactivation"), liste(o, "capteurs"), liste(o, "cameras"), liste(o, "notifierHa"),
            liste(o, "destinataires"), liste(o, "sirenes"), o.optBoolean("sireneSurDetection"), o.optBoolean("viaHa", true), o.optBoolean("viaMms", true),
            o.optString("automationHa"), o.optBoolean("actif"), o.optLong("activeLe"), o.optString("description"))
    }

    // ------------------------------------------------------------------ recherche par nom / phrase

    internal fun norm(s: String) = fr.dd.jarvis.societe.core.normaliser(s)

    fun trouver(app: JarvisApp, nom: String): Systeme? {
        val n = norm(nom).removePrefix("mode ").removePrefix("systeme ").trim()
        if (n.isBlank()) return null
        val l = liste(app)
        return l.firstOrNull { norm(it.nom) == n || it.id == nom.trim() || it.modele == nom.trim() }
            ?: l.firstOrNull { norm(it.nom).contains(n) || n.contains(norm(it.nom).removePrefix("mode ")) }
            ?: l.firstOrNull { s -> n.split(' ').filter { it.length >= 4 }.let { m -> m.isNotEmpty() && m.all { norm(s.nom).contains(it) } } }
    }

    /** Phrase d'activation / de désactivation d'un système (true = activer). Seulement pour une phrase courte. */
    fun parPhrase(app: JarvisApp, texte: String): Pair<Systeme, Boolean>? =
        parPhrase(texte, liste(app))?.let { (i, activer) -> liste(app)[i] to activer }

    /**
     * Activation / désactivation demandée sans préciser lequel de plusieurs modes (« passe en mode sécurisé ») :
     * noms des modes possibles (au moins un mot de leur nom cité), pour poser la question tout de suite. Testable.
     */
    internal fun ambigus(texte: String, l: List<Systeme>): Pair<List<String>, Boolean>? {
        val t = norm(texte).removePrefix("jarvis ").trim()
        if (t.split(' ').size > 10 || !Regex("\\bmode\\b").containsMatchIn(t)) return null
        val desactiver = Regex("\\b(desactiv\\w*|coupe\\w*|arret\\w*|desarm\\w*|stop\\w*|quitt\\w*)\\b").containsMatchIn(t)
        val activer = !desactiver && Regex("\\b(activ\\w*|lance\\w*|mets|met|passe\\w*|bascule\\w*|enclench\\w*)\\b").containsMatchIn(t)
        if (!desactiver && !activer) return null
        val mots = t.split(' ')
        val possibles = l.filter { s -> norm(s.nom).split(' ').filter { it.length >= 4 && it != "mode" }.any { c -> mots.any { m -> m.startsWith(c.take(5)) } } }
        return if (possibles.size >= 2) possibles.map { it.nom } to activer else null
    }

    fun ambigus(app: JarvisApp, texte: String) = ambigus(texte, liste(app))

    /** Réponse à « Quel mode veux-tu activer : … ? » (« le téléphone », « la maison ») : le mode désigné par un mot qui lui est propre. */
    internal fun choix(texte: String, l: List<Systeme>): Int? {
        val mots = norm(texte).split(' ')
        val cles = l.map { s -> norm(s.nom).split(' ').filter { it.length >= 4 && it != "mode" }.map { it.take(5) } }
        val communs = cles.flatten().groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        val trouves = cles.indices.filter { i -> cles[i].filter { it !in communs }.any { c -> mots.any { m -> (SYNONYMES[c] ?: listOf(c)).any { m.startsWith(it) } } } }
        return trouves.singleOrNull()
    }

    fun choix(app: JarvisApp, texte: String): Systeme? = liste(app).let { l -> choix(texte, l)?.let { l[it] } }

    /** Familles de mots équivalents dans un nom de mode (« téléphone » ≈ portable, « sécurisé » ≈ sécurité…). */
    private val SYNONYMES = mapOf(
        "telep" to listOf("telep", "porta", "smart", "mobil"), "porta" to listOf("telep", "porta", "smart", "mobil"),
        "maiso" to listOf("maiso", "domic", "logem", "foyer"), "secur" to listOf("secur", "proteg"),
    )

    /**
     * Activer / désactiver un mode par une phrase naturelle : phrase enregistrée, sinon un verbe (« active », « lance »,
     * « mets », « sécurise »… / « désactive », « coupe », « arrête »…) + TOUS les mots du nom du mode, dans n'importe quel
     * ordre (« mets le téléphone en mode sécurisé », « désactive la sécurité de la maison »). Index du mode + activer. Testable.
     */
    internal fun parPhrase(texte: String, l: List<Systeme>): Pair<Int, Boolean>? {
        if (texte.length > 120) return null
        val t = norm(texte).removePrefix("jarvis ").trim()
        if (t.isBlank() || Regex("\\b(installe|reinstalle|cree|configure|ajoute|retire|enleve|modifie|supprime|parametre|regle|renomme)\\b").containsMatchIn(t)) return null
        if (Regex("\\b(est ce|est il|est elle|quel|quels|pourquoi|comment)\\b").containsMatchIn(t)) return null
        l.indexOfFirst { s -> s.phrasesDesactiver.any { norm(it) == t } }.takeIf { it >= 0 }?.let { return it to false }
        l.indexOfFirst { s -> s.phrasesActiver.any { norm(it) == t } }.takeIf { it >= 0 }?.let { return it to true }
        if (t.split(' ').size > 14) return null
        val desactiver = Regex("\\b(desactiv\\w*|coupe\\w*|arret\\w*|desarm\\w*|stop\\w*|quitt\\w*|sors|sortir|eteins|eteindre|termine\\w*|fin du)\\b").containsMatchIn(t)
        val activer = !desactiver && Regex("\\b(activ\\w*|lance\\w*|enclench\\w*|arme\\w*|demarr\\w*|mets|met|mettre|passe\\w*|bascule\\w*|securise\\w*|verrouille\\w*)\\b").containsMatchIn(t)
        if (!desactiver && !activer) return null
        val mots = t.split(' ').filter { it.isNotBlank() }
        fun present(cle: String): Boolean {
            val racine = cle.take(5)
            val familles = SYNONYMES[racine] ?: listOf(racine)
            return mots.any { m -> familles.any { m.startsWith(it) } }
        }
        val scores = l.mapIndexed { i, s ->
            val cles = norm(s.nom).split(' ').filter { it.length >= 3 && it != "mode" && it != "systeme" }
            i to (if (cles.isNotEmpty() && cles.all { present(it) }) cles.size else 0)
        }.filter { it.second > 0 }
        val meilleur = scores.maxOfOrNull { it.second } ?: return null
        val gagnants = scores.filter { it.second == meilleur }
        if (gagnants.size != 1) return null
        return gagnants.single().first to activer
    }

    /**
     * Demande de gestion d'un mode reconnue sans IA (créer, modifier, supprimer, lister) → arguments de l'outil
     * modes_jarvis ; null si la phrase n'en est pas une (ou trop vague : l'IA posera alors ses questions).
     * Évite qu'une IA confonde « installe le mode téléphone sécurisé » avec les modes d'alimentation du téléphone.
     */
    fun intention(app: JarvisApp, texte: String): JSONObject? = intention(texte, liste(app).map { it.nom })

    internal fun intention(texte: String, noms: List<String>): JSONObject? {
        if (texte.length > 400) return null
        val t = norm(texte).removePrefix("jarvis ").trim()
        if (!Regex("\\b(modes?|systemes?)\\b").containsMatchIn(t)) return null
        // Mode existant cité dans la phrase (le nom le plus long l'emporte).
        val cite = noms.filter { n -> norm(n).removePrefix("mode ").let { it.length >= 3 && Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(t) } }.maxByOrNull { it.length }
        if (Regex("^(montre|liste|affiche|quels sont|donne moi|dis moi)\\b.{0,30}\\bmodes\\b|^mes modes$").containsMatchIn(t)) return JSONObject().put("action", "lister")
        if (cite != null && Regex("^(supprime|efface|desinstalle|retire|enleve) (le |la |mon |ma )?(mode |systeme )?").containsMatchIn(t) &&
            t.replace(Regex("^(supprime|efface|desinstalle|retire|enleve) (le |la |mon |ma )?(mode |systeme )?"), "").trim().let { reste -> norm(cite).removePrefix("mode ").let { reste == it || reste == "mode $it" } })
            return JSONObject().put("action", "supprimer").put("nom", cite)
        val modele = when {
            Regex("\\b(telephone|portable|smartphone) securise").containsMatchIn(t) -> "telephone_securise"
            Regex("\\b(maison|domicile) securisee").containsMatchIn(t) -> "maison_securisee"
            else -> ""
        }
        val creer = Regex("^(installe|reinstalle|cree|creer|creons|mets en place|met en place|prepare|configure (un|une|le|la) nouve|fais moi|fabrique|ajoute un nouveau|nouveau)\\b").containsMatchIn(t) ||
            Regex("^(je veux|je voudrais|j aimerais|peux tu|tu peux) (installer|creer|mettre en place|un mode|le mode)").containsMatchIn(t)
        // « Active le mode téléphone sécurisé » alors qu'il n'est pas encore installé : on l'installe d'abord.
        val installerModele = modele.isNotBlank() && cite == null && Regex("^(active|lance|mets|passe|demarre|enclenche)\\b").containsMatchIn(t)
        if ((creer && (cite == null || modele.isNotBlank())) || installerModele) {
            if (modele.isBlank() && !(Regex("\\b(qui|pour|avec|quand|ou)\\b|:").containsMatchIn(t) && t.split(' ').size >= 7)) return null
            return JSONObject().put("action", "creer").put("description", texte.trim()).also {
                if (modele.isNotBlank()) it.put("modele", modele)
                if (installerModele) it.put("activer_apres", true)   // « active le mode … » pas encore installé : installé PUIS activé
            }
        }
        val modifier = cite != null && (Regex("^(ajoute|enleve|retire|supprime|modifie|change|remplace|renomme|mets|met|ne |n )").containsMatchIn(t) ||
            Regex("^dans (le |la |mon |ma )?(mode |systeme )?").containsMatchIn(t) || creer)
        if (modifier && cite != null) return JSONObject().put("action", "modifier").put("nom", cite).put("consigne", texte.trim())
        return null
    }

    // ------------------------------------------------------------------ installation des modèles

    private fun id(nom: String) = norm(nom).replace(' ', '_').take(30).ifBlank { "systeme" }

    fun nouveauTelephone(): Systeme = Systeme(
        id = "telephone_securise", nom = "Téléphone sécurisé", modele = "telephone_securise",
        phrasesActiver = listOf("active le mode telephone securise", "securise le telephone", "mode telephone securise", "verrouille le telephone"),
        phrasesDesactiver = listOf("desactive le mode telephone securise"),
        activation = listOf(Etape("telephone", "verrouiller")),
        viaHa = false, viaMms = false,
    )

    /** Maison sécurisée : les appareils sont trouvés dans Home Assistant (serrures, volets, alarme, caméras, capteurs). */
    suspend fun nouvelleMaison(app: JarvisApp, nom: String = "Maison sécurisée"): Pair<Systeme, String> = withContext(Dispatchers.IO) {
        val etats = JSONArray(Serveur.ha(app, "GET", "/api/states"))
        data class E(val id: String, val nom: String, val classe: String)
        val es = (0 until etats.length()).mapNotNull { etats.optJSONObject(it) }.map {
            val at = it.optJSONObject("attributes") ?: JSONObject()
            E(it.optString("entity_id"), at.optString("friendly_name"), at.optString("device_class"))
        }
        fun dom(d: String) = es.filter { it.id.startsWith("$d.") }
        val serrures = dom("lock").map { it.id }
        val volets = dom("cover").filter { it.classe in setOf("shutter", "blind", "window", "shade", "curtain", "awning", "garage", "gate") ||
            Regex("volet|store|fenetre|rideau|garage|portail|shutter|blind|window").containsMatchIn(norm(it.id + " " + it.nom)) }.map { it.id }
        val alarmes = dom("alarm_control_panel").map { it.id }
        val sirenes = dom("siren").map { it.id }
        val cameras = dom("camera").map { it.id }
        val personnes = dom("binary_sensor").filter { Regex("person|personne|human|humain|people").containsMatchIn(norm(it.id + " " + it.nom)) }.map { it.id }
        val capteurs = personnes.ifEmpty { dom("binary_sensor").filter { it.classe in setOf("motion", "occupancy", "presence") }.map { it.id } }
        val notifier = runCatching {
            val svc = JSONArray(Serveur.ha(app, "GET", "/api/services"))
            (0 until svc.length()).mapNotNull { svc.optJSONObject(it) }.firstOrNull { it.optString("domain") == "notify" }
                ?.optJSONObject("services")?.keys()?.asSequence()?.filter { it.startsWith("mobile_app_") }?.map { "notify.$it" }?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val activation = buildList {
            if (serrures.isNotEmpty()) add(Etape("ha", "lock.lock", serrures))
            if (volets.isNotEmpty()) add(Etape("ha", "cover.close_cover", volets))
            if (alarmes.isNotEmpty()) add(Etape("ha", "alarm_control_panel.alarm_arm_away", alarmes))
        }
        val desactivation = buildList {
            if (alarmes.isNotEmpty()) add(Etape("ha", "alarm_control_panel.alarm_disarm", alarmes))
        }
        val s = Systeme(
            id = id(nom), nom = nom, modele = "maison_securisee",
            phrasesActiver = listOf("active le mode maison securisee", "securise la maison", "active la maison securisee", "mode maison securisee"),
            phrasesDesactiver = listOf("desactive le mode maison securisee", "desactive la maison securisee"),
            activation = activation, desactivation = desactivation,
            capteurs = capteurs, cameras = cameras, notifierHa = notifier, sirenes = sirenes,
        )
        val note = buildString {
            append("Trouvé dans Home Assistant : ${serrures.size} serrure(s), ${volets.size} volet(s)/fenêtre(s) motorisé(s), ${alarmes.size} alarme(s), ")
            append("${sirenes.size} sirène(s), ${cameras.size} caméra(s), ${capteurs.size} capteur(s) de ${if (personnes.isNotEmpty()) "personne" else "mouvement"}, ")
            append("${notifier.size} téléphone(s) avec l'appli Home Assistant.")
        }
        s to note
    }

    // ------------------------------------------------------------------ automatisation Home Assistant (détection)

    /** Crée / met à jour l'automatisation HA « détection → photo → notification (→ sirène) », désactivée hors mode actif. */
    suspend fun synchroniserHa(app: JarvisApp, s: Systeme): Pair<Systeme, String> = withContext(Dispatchers.IO) {
        if (!s.viaHa || s.capteurs.isEmpty() || (s.notifierHa.isEmpty() && !(s.sireneSurDetection && s.sirenes.isNotEmpty()))) return@withContext s to ""
        if (!Serveur.haConfigure(app)) return@withContext s to "Home Assistant n'est pas configuré : automatisation non créée."
        val autoId = "jarvis_" + s.id
        val actions = JSONArray()
        val image = s.cameras.firstOrNull()
        s.notifierHa.forEach { n ->
            s.cameras.ifEmpty { listOf<String?>(null) }.forEach { cam ->
                val data = JSONObject().put("priority", "high").put("ttl", 0).put("channel", "alarm_stream").put("tag", "jarvis_${s.id}")
                if (cam != null) data.put("image", "/api/camera_proxy/$cam")
                actions.put(JSONObject().put("service", n).put("data", JSONObject()
                    .put("title", "🚨 ${s.nom}").put("message", "Personne détectée : {{ trigger.to_state.name }}" + (cam?.let { " — caméra $it" } ?: ""))
                    .put("data", data)))
            }
        }
        if (s.sireneSurDetection && s.sirenes.isNotEmpty()) actions.put(JSONObject().put("service", "siren.turn_on").put("target", JSONObject().put("entity_id", JSONArray(s.sirenes))))
        actions.put(JSONObject().put("delay", JSONObject().put("seconds", 120)))
        val config = JSONObject().put("id", autoId).put("alias", "Jarvis ${s.nom} détection")
            .put("description", "Créée par JARVIS : tant que « ${s.nom} » est actif, une personne détectée déclenche les photos des caméras" +
                (if (image != null) "" else "") + " et les notifications. Activée / désactivée par JARVIS.")
            .put("mode", "single").put("max_exceeded", "silent")
            .put("trigger", JSONArray().put(JSONObject().put("platform", "state").put("entity_id", JSONArray(s.capteurs)).put("to", "on")))
            .put("action", actions)
        Serveur.ha(app, "POST", "/api/config/automation/config/$autoId", config)
        runCatching { Serveur.ha(app, "POST", "/api/services/automation/reload") }
        // Identifiant de l'entité : celui dont l'attribut « id » est l'identifiant de configuration.
        var entite = s.automationHa
        repeat(4) {
            if (entite.isNotBlank()) return@repeat
            kotlinx.coroutines.delay(800)
            entite = runCatching {
                val etats = JSONArray(Serveur.ha(app, "GET", "/api/states"))
                (0 until etats.length()).mapNotNull { etats.optJSONObject(it) }
                    .firstOrNull { it.optString("entity_id").startsWith("automation.") && it.optJSONObject("attributes")?.optString("id") == autoId }
                    ?.optString("entity_id").orEmpty()
            }.getOrDefault("")
        }
        if (entite.isNotBlank()) runCatching {
            Serveur.ha(app, "POST", "/api/services/automation/" + (if (s.actif) "turn_on" else "turn_off"), JSONObject().put("entity_id", entite))
        }
        s.copy(automationHa = entite) to (if (entite.isNotBlank()) "Automatisation Home Assistant « Jarvis ${s.nom} détection » à jour ($entite)." else "Automatisation Home Assistant envoyée, mais son entité n'a pas été retrouvée.")
    }

    // ------------------------------------------------------------------ exécution

    private fun ok(r: String) = !r.startsWith("❌") && !r.startsWith("⚠️")

    private suspend fun executerEtape(app: JarvisApp, s: Systeme, e: Etape, tools: ToolBox?): String = withContext(Dispatchers.IO) {
        try {
            when (e.type) {
                "ha" -> {
                    val (domaine, service) = e.cible.split('.', limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
                    if (domaine.isBlank() || service.isBlank()) return@withContext "❌ Service Home Assistant invalide : ${e.cible}"
                    val corps = JSONObject()
                    if (e.entites.isNotEmpty()) corps.put("entity_id", JSONArray(e.entites))
                    if (domaine == "alarm_control_panel") runCatching { app.store.codeAlarmeHa.value }.getOrDefault("").takeIf { it.isNotBlank() }?.let { corps.put("code", it) }
                    if (e.texte.isNotBlank()) runCatching { val x = JSONObject(e.texte); x.keys().forEach { k -> corps.put(k, x.get(k)) } }
                    Serveur.ha(app, "POST", "/api/services/$domaine/$service", corps)
                    "✅ ${libelle(e)}"
                }
                "telephone" -> when (e.cible) {
                    "verrouiller", "veille" -> TelephoneSecurise.verrouiller(app)
                    "economie", "equilibre", "performance" -> fr.dd.jarvis.device.ModeTelephone.appliquer(app, e.cible)
                    "ne_pas_deranger_on", "ne_pas_deranger_off" -> fr.dd.jarvis.device.ModeSonore.appliquer(app, if (e.cible.endsWith("_on")) "npd" else "npd_off")
                    "silencieux", "vibreur", "sonnerie" -> fr.dd.jarvis.device.ModeSonore.appliquer(app, when (e.cible) { "silencieux" -> "npd"; "vibreur" -> "vibreur"; else -> "sonnerie" })
                    in ACTIONS_TELEPHONE -> "📱 " + app.phone.perform(e.cible, e.texte)
                    else -> "⚠️ Action du téléphone inconnue : ${e.cible}"
                }
                "message" -> {
                    if (s.destinataires.isEmpty()) "⚠️ Message non envoyé : aucun destinataire configuré."
                    else s.destinataires.joinToString(" ; ") { d ->
                        val n = fr.dd.jarvis.device.Telephony.resolve(app, d)
                        if (n == null) "⚠️ $d introuvable" else fr.dd.jarvis.device.Telephony.envoyer(app, n.second, e.texte)
                    }.let { "✉️ $it" }
                }
                "jarvis" -> {
                    val r = fr.dd.jarvis.device.Routines.executer(app, fr.dd.jarvis.device.Routine("systeme_${s.id}", s.nom, actions = listOf(e.texte)), "", tools)
                    "🤖 " + r.lines().drop(1).joinToString(" ").ifBlank { e.texte }
                }
                else -> "⚠️ Étape inconnue : ${e.type}"
            }
        } catch (x: Exception) {
            "❌ ${libelle(e)} : ${(x.message ?: x.javaClass.simpleName).take(220)}"
        }
    }

    /** Actions directes du téléphone utilisables dans un mode (en plus de veille, modes d'alimentation, ne pas déranger, sonnerie). */
    val ACTIONS_TELEPHONE = setOf("torch_on", "torch_off", "volume", "wifi_on", "wifi_off", "bluetooth_on", "bluetooth_off", "data_on", "data_off",
        "brightness", "music", "media", "open_app", "open_url", "navigate", "lock")

    fun libelle(e: Etape): String = when (e.type) {
        "ha" -> when (e.cible) {
            "lock.lock" -> "🔒 Verrouiller ${e.entites.joinToString()}"
            "lock.unlock" -> "🔓 Déverrouiller ${e.entites.joinToString()}"
            "cover.close_cover" -> "🪟 Fermer ${e.entites.joinToString()}"
            "cover.open_cover" -> "🪟 Ouvrir ${e.entites.joinToString()}"
            "alarm_control_panel.alarm_arm_away" -> "🚨 Armer l'alarme ${e.entites.joinToString()}"
            "alarm_control_panel.alarm_disarm" -> "🔕 Désarmer l'alarme ${e.entites.joinToString()}"
            else -> "🏠 ${e.cible} ${e.entites.joinToString()}".trim()
        }
        "telephone" -> when (e.cible) {
            "verrouiller", "veille" -> "📴 Veille et verrouillage du téléphone"
            "ne_pas_deranger_on" -> "🔕 Ne pas déranger"; "ne_pas_deranger_off" -> "🔔 Fin du ne pas déranger"
            "economie", "equilibre", "performance" -> "🔋 Téléphone en mode ${e.cible}"
            else -> "📱 Téléphone : ${e.cible}" + (if (e.texte.isNotBlank()) " ${e.texte}" else "")
        }
        "message" -> "✉️ SMS aux destinataires : « ${e.texte} »"
        "jarvis" -> "🤖 ${e.texte}"
        else -> "${e.type} ${e.cible}"
    }

    /** Fenêtres / portes encore ouvertes (capteurs d'ouverture) : signalées à l'activation, rien ne peut les fermer. */
    private suspend fun ouvertures(app: JarvisApp): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val etats = JSONArray(Serveur.ha(app, "GET", "/api/states"))
            (0 until etats.length()).mapNotNull { etats.optJSONObject(it) }.filter {
                it.optString("entity_id").startsWith("binary_sensor.") && it.optString("state") == "on" &&
                    it.optJSONObject("attributes")?.optString("device_class") in setOf("window", "door", "opening", "garage_door")
            }.map { it.optJSONObject("attributes")?.optString("friendly_name")?.ifBlank { null } ?: it.optString("entity_id") }
        }.getOrDefault(emptyList())
    }

    suspend fun activer(app: JarvisApp, s0: Systeme, tools: ToolBox? = null): String {
        var s = s0
        val lignes = mutableListOf<String>()
        for (e in s.activation) lignes += executerEtape(app, s, e, tools).also { if (e.type == "telephone" && e.cible == "verrouiller") kotlinx.coroutines.delay(300) }
        s = s.copy(actif = true, activeLe = System.currentTimeMillis())
        if (s.modele == "maison_securisee" || s.capteurs.isNotEmpty()) {
            if (s.viaHa && s.capteurs.isNotEmpty()) {
                if (s.automationHa.isBlank()) runCatching { synchroniserHa(app, s) }.onSuccess { (n, _) -> s = n }
                if (s.automationHa.isNotBlank()) lignes += runCatching {
                    Serveur.ha(app, "POST", "/api/services/automation/turn_on", JSONObject().put("entity_id", s.automationHa)); "✅ 📸 Surveillance Home Assistant active (photos + notifications)"
                }.getOrElse { "❌ Surveillance Home Assistant : ${it.message?.take(160)}" }
            }
            if (s.viaMms && s.capteurs.isNotEmpty()) lignes += if (s.destinataires.isEmpty()) "⚠️ MMS : aucun numéro configuré (dis « ajoute le numéro … au mode ${s.nom} »)"
                else "✅ 📱 Surveillance du téléphone active : photos par MMS à ${s.destinataires.joinToString()}"
            if (s.capteurs.isEmpty()) lignes += "⚠️ Aucun capteur de personne ou de mouvement : pas de détection."
            ouvertures(app).takeIf { it.isNotEmpty() }?.let { lignes += "⚠️ Encore ouvert : ${it.joinToString()}" }
        }
        sauver(app, s)
        SurveillanceMaison.actualiser(app)
        val titre = if (s.modele == "telephone_securise") "📱 Mode « ${s.nom} » activé — dis « Jarvis, déverrouille » pour le déverrouiller (ta voix sera vérifiée)."
            else "🛡️ « ${s.nom} » activé."
        return (listOf(titre) + lignes.filter { it.isNotBlank() }).joinToString("\n")
    }

    suspend fun desactiver(app: JarvisApp, s0: Systeme, tools: ToolBox? = null): String {
        var s = s0
        val lignes = mutableListOf<String>()
        for (e in s.desactivation) lignes += executerEtape(app, s, e, tools)
        if (s.automationHa.isNotBlank()) lignes += runCatching {
            Serveur.ha(app, "POST", "/api/services/automation/turn_off", JSONObject().put("entity_id", s.automationHa)); "✅ Surveillance Home Assistant arrêtée"
        }.getOrElse { "❌ Arrêt de la surveillance Home Assistant : ${it.message?.take(160)}" }
        if (s.sireneSurDetection && s.sirenes.isNotEmpty()) runCatching { Serveur.ha(app, "POST", "/api/services/siren/turn_off", JSONObject().put("entity_id", JSONArray(s.sirenes))) }
        s = s.copy(actif = false)
        sauver(app, s)
        SurveillanceMaison.actualiser(app)
        return (listOf("✅ « ${s.nom} » désactivé.") + lignes).joinToString("\n")
    }

    // ------------------------------------------------------------------ composition à partir d'une demande en français

    private val DOMAINES_UTILES = setOf("light", "switch", "cover", "lock", "alarm_control_panel", "climate", "media_player", "scene", "script",
        "fan", "siren", "camera", "input_boolean", "vacuum", "water_heater", "humidifier", "valve", "button", "automation")

    /** Appareils de Home Assistant utiles à un mode : (entity_id, nom, type). */
    suspend fun inventaireHa(app: JarvisApp): List<Triple<String, String, String>> = withContext(Dispatchers.IO) {
        if (!Serveur.haConfigure(app)) return@withContext emptyList()
        runCatching {
            val etats = JSONArray(Serveur.ha(app, "GET", "/api/states"))
            (0 until etats.length()).mapNotNull { etats.optJSONObject(it) }.mapNotNull { o ->
                val id = o.optString("entity_id")
                val dom = id.substringBefore('.')
                val at = o.optJSONObject("attributes") ?: JSONObject()
                val classe = at.optString("device_class")
                val utile = dom in DOMAINES_UTILES ||
                    (dom == "binary_sensor" && (classe in setOf("motion", "occupancy", "presence", "door", "window", "opening", "garage_door") ||
                        Regex("person|personne|human").containsMatchIn(id)))
                if (!utile || id.startsWith("automation.jarvis_")) null else Triple(id, at.optString("friendly_name"), classe)
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun telephonesHa(app: JarvisApp): List<String> = withContext(Dispatchers.IO) {
        if (!Serveur.haConfigure(app)) return@withContext emptyList()
        runCatching {
            val svc = JSONArray(Serveur.ha(app, "GET", "/api/services"))
            (0 until svc.length()).mapNotNull { svc.optJSONObject(it) }.firstOrNull { it.optString("domain") == "notify" }
                ?.optJSONObject("services")?.keys()?.asSequence()?.filter { it.startsWith("mobile_app_") }?.map { "notify.$it" }?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    private val CONSIGNE = """
Tu composes un MODE de l'assistant JARVIS (Android + Home Assistant) à partir d'une demande en français. Réponds UNIQUEMENT par un objet JSON, sans texte autour :
{"nom": "Mode …", "description": "une phrase", "phrases_activation": ["…"], "phrases_desactivation": ["…"],
 "activation": [ÉTAPE, …], "desactivation": [ÉTAPE, …],
 "surveillance": {"capteurs": ["binary_sensor.…"], "cameras": ["camera.…"], "sirenes": ["siren.…"], "sirene_sur_detection": false},
 "destinataires": ["numéro ou nom de contact"], "notifier_ha": ["notify.mobile_app_…"], "remarques": "…"}
ÉTAPE = {"type":"ha","service":"domaine.service","entites":["entity_id", …],"donnees":{…}}
      | {"type":"telephone","action":"verrouiller|ne_pas_deranger_on|ne_pas_deranger_off|silencieux|vibreur|sonnerie|economie|equilibre|performance|wifi_on|wifi_off|bluetooth_on|bluetooth_off|data_on|data_off|torch_on|torch_off|volume|brightness|music|open_app","valeur":"…"}
      | {"type":"sms","texte":"message envoyé aux destinataires"}
      | {"type":"jarvis","texte":"demande en français que JARVIS exécutera avec ses outils (tout ce qui n'a pas d'étape directe)"}
RÈGLES :
- N'utilise QUE des entity_id présents dans la liste fournie, choisis d'après leur nom (pièce, appareil). N'en invente jamais. Si l'appareil demandé n'existe pas, mets une étape "jarvis" qui décrit l'action et signale-le dans "remarques".
- Services courants : light.turn_on / light.turn_off (donnees brightness_pct), switch.turn_on / turn_off, cover.close_cover / open_cover / set_cover_position (position), lock.lock / lock.unlock, alarm_control_panel.alarm_arm_away / alarm_arm_home / alarm_arm_night / alarm_disarm, climate.set_temperature (temperature) / set_hvac_mode (hvac_mode), media_player.turn_on / turn_off / media_pause / volume_set (volume_level 0 à 1), scene.turn_on, script.turn_on, fan.turn_on / turn_off, vacuum.start / return_to_base, siren.turn_on / turn_off, input_boolean.turn_on / turn_off.
- Regroupe dans une même étape les appareils qui reçoivent le même service.
- La désactivation remet logiquement les choses comme avant (rallumer, rouvrir, désarmer ce qui a été armé…) sauf si la demande dit autre chose. Ne déverrouille jamais une porte à la désactivation sans demande explicite.
- "surveillance" uniquement si la demande parle de détecter une présence / un intrus / de prendre des photos ; sinon listes vides.
- Phrases : formulations françaises courtes et naturelles, sans « Jarvis ». Toujours au moins « active le mode <nom> » et « désactive le mode <nom> ».
- MODIFICATION : si un mode actuel est fourni, renvoie le mode COMPLET après modification, en gardant tout ce que la demande ne change pas.
- Aucun code (écran, alarme) dans le JSON.
""".trim()

    /**
     * Crée ou modifie un mode à partir d'une simple demande en français : l'IA de Jarvis la traduit en étapes concrètes
     * avec les vrais appareils de Home Assistant ; les entités inconnues sont écartées et signalées.
     */
    suspend fun composer(app: JarvisApp, demande: String, existant: Systeme?): Pair<Systeme, String> {
        val inventaire = inventaireHa(app)
        val telephones = telephonesHa(app)
        val connus = inventaire.map { it.first }.toSet()
        val user = buildString {
            append("Demande : ").append(demande.trim()).append("\n\n")
            if (existant != null) append("Mode actuel à modifier (JSON) :\n")
                .append(versJson(listOf(existant)).getJSONObject(0).apply { remove("automationHa"); remove("actif"); remove("activeLe"); remove("id") }.toString()).append("\n\n")
            if (inventaire.isEmpty()) append("(Home Assistant non configuré ou injoignable : aucun appareil disponible, utilise des étapes « telephone », « sms » ou « jarvis ».)\n")
            else {
                append("Appareils Home Assistant disponibles (entity_id | nom | type) :\n")
                inventaire.take(400).forEach { (id, nom, classe) -> append(id).append(" | ").append(nom).append(if (classe.isNotBlank()) " | $classe" else "").append('\n') }
            }
            if (telephones.isNotEmpty()) append("\nTéléphones avec l'appli Home Assistant : ").append(telephones.joinToString())
        }.take(26_000)
        val brut = app.llm.agent(CONSIGNE, user)
        return interpreter(brut, existant, connus, telephones, liste(app).map { it.id }.toSet())
    }

    /** Réponse JSON de l'IA → mode validé (entités inconnues écartées, phrases de base ajoutées) + remarques. Testable. */
    internal fun interpreter(brut: String, existant: Systeme?, connus: Set<String>, telephones: List<String>, idsPris: Set<String>): Pair<Systeme, String> {
        val o = runCatching { JSONObject(brut.substring(brut.indexOf('{'), brut.lastIndexOf('}') + 1)) }
            .getOrElse { throw IllegalStateException("la demande n'a pas pu être traduite en mode (réponse de l'IA illisible), reformule-la") }
        val remarques = mutableListOf<String>()
        o.optString("remarques").takeIf { it.isNotBlank() && it != "null" }?.let { remarques += it }
        fun garder(ids: List<String>, prefixe: String): List<String> {
            val ok = ids.filter { it.startsWith(prefixe) && (connus.isEmpty() || it in connus) }
            (ids - ok.toSet()).takeIf { it.isNotEmpty() }?.let { remarques += "Appareil(s) inconnu(s) de Home Assistant écarté(s) : ${it.joinToString()}" }
            return ok
        }
        fun etapesDe(a: JSONArray?): List<Etape> = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }.mapNotNull { e ->
            when (e.optString("type")) {
                "ha" -> {
                    val svc = e.optString("service").ifBlank { e.optString("cible") }
                    val ids = liste(e, "entites").filter { connus.isEmpty() || it in connus }
                    (liste(e, "entites") - ids.toSet()).takeIf { it.isNotEmpty() }?.let { remarques += "Appareil(s) inconnu(s) écarté(s) : ${it.joinToString()}" }
                    val donnees = e.optJSONObject("donnees")?.takeIf { it.length() > 0 }?.toString().orEmpty()
                    if (!svc.contains('.') || (ids.isEmpty() && liste(e, "entites").isNotEmpty())) null else Etape("ha", svc, ids, donnees)
                }
                "telephone" -> Etape("telephone", e.optString("action").ifBlank { e.optString("cible") }, texte = e.optString("valeur").takeIf { it != "null" }.orEmpty())
                "sms", "message" -> Etape("message", texte = e.optString("texte")).takeIf { it.texte.isNotBlank() }
                "jarvis" -> Etape("jarvis", texte = e.optString("texte")).takeIf { it.texte.isNotBlank() }
                else -> null
            }
        }
        fun chaines(k: String) = liste(o, k).filter { it != "null" }
        val nom = o.optString("nom").trim().takeIf { it.isNotBlank() && it != "null" } ?: existant?.nom ?: "Nouveau mode"
        val surv = o.optJSONObject("surveillance") ?: JSONObject()
        var s = (existant ?: Systeme(id = id(nom).let { base -> if (base in idsPris) base + "_" + (System.currentTimeMillis() % 1000) else base }, nom = nom)).copy(
            nom = nom,
            description = o.optString("description").takeIf { it.isNotBlank() && it != "null" } ?: existant?.description.orEmpty(),
            phrasesActiver = chaines("phrases_activation").ifEmpty { existant?.phrasesActiver.orEmpty() },
            phrasesDesactiver = chaines("phrases_desactivation").ifEmpty { existant?.phrasesDesactiver.orEmpty() },
            activation = if (o.has("activation")) etapesDe(o.optJSONArray("activation")) else existant?.activation.orEmpty(),
            desactivation = if (o.has("desactivation")) etapesDe(o.optJSONArray("desactivation")) else existant?.desactivation.orEmpty(),
            capteurs = garder(liste(surv, "capteurs"), "binary_sensor."),
            cameras = garder(liste(surv, "cameras"), "camera."),
            sirenes = garder(liste(surv, "sirenes"), "siren."),
            sireneSurDetection = surv.optBoolean("sirene_sur_detection", existant?.sireneSurDetection ?: false),
            destinataires = if (o.has("destinataires")) chaines("destinataires") else existant?.destinataires.orEmpty(),
            notifierHa = (if (o.has("notifier_ha")) chaines("notifier_ha") else existant?.notifierHa.orEmpty())
                .filter { telephones.isEmpty() || it in telephones },
        )
        // Toujours de quoi l'activer et le désactiver à la voix.
        val court = norm(s.nom).removePrefix("mode ").trim()
        if (court.isNotBlank()) s = s.copy(
            phrasesActiver = (s.phrasesActiver + "active le mode $court").distinctBy { norm(it) },
            phrasesDesactiver = (s.phrasesDesactiver + "desactive le mode $court").distinctBy { norm(it) })
        if (s.activation.isEmpty() && s.capteurs.isEmpty()) remarques += "Aucune étape n'a pu être composée : précise ce que le mode doit faire."
        return s to remarques.distinct().joinToString("\n")
    }

    // ------------------------------------------------------------------ présentation

    fun presenter(s: Systeme): String = buildString {
        append("## ${if (s.actif) "🟢" else "⚪"} ${s.nom}  (${MODELES[s.modele]?.substringBefore(" (") ?: s.modele})\n")
        if (s.description.isNotBlank()) append("${s.description}\n")
        if (s.phrasesActiver.isNotEmpty()) append("🗣️ Activer : « ${s.phrasesActiver.take(3).joinToString("», « ")} »\n")
        if (s.phrasesDesactiver.isNotEmpty()) append("🗣️ Désactiver : « ${s.phrasesDesactiver.take(3).joinToString("», « ")} »\n")
        if (s.activation.isNotEmpty()) append("▶️ À l'activation :\n" + s.activation.joinToString("\n") { "- " + libelle(it) } + "\n")
        if (s.desactivation.isNotEmpty()) append("⏹️ À la désactivation :\n" + s.desactivation.joinToString("\n") { "- " + libelle(it) } + "\n")
        if (s.modele == "telephone_securise") append("🔓 Déverrouillage : « Jarvis, déverrouille » + vérification de ta voix (empreinte vocale)\n")
        if (s.capteurs.isNotEmpty() || s.modele == "maison_securisee") {
            append("👁️ Détection : ${s.capteurs.joinToString().ifBlank { "aucun capteur" }}\n")
            append("📸 Caméras : ${s.cameras.joinToString().ifBlank { "aucune" }}\n")
            append("🔔 Notifications Home Assistant : ${if (!s.viaHa) "désactivées" else s.notifierHa.joinToString().ifBlank { "aucun téléphone" }}\n")
            append("📱 MMS : ${if (!s.viaMms) "désactivés" else s.destinataires.joinToString().ifBlank { "aucun numéro" }}\n")
            append("🚨 Sirène en cas de détection : ${if (s.sireneSurDetection) s.sirenes.joinToString().ifBlank { "aucune sirène" } else "non"}\n")
        }
    }.trimEnd()

    fun presenterTous(app: JarvisApp): String {
        val l = liste(app)
        if (l.isEmpty()) return "Aucun mode installé. Modèles disponibles :\n" + MODELES.values.joinToString("\n") { "- $it" } +
            "\nDis par exemple « installe le mode maison sécurisée » ou « crée un mode cinéma qui ferme les volets et baisse les lumières »."
        return "# 🧩 Modes JARVIS\n" + l.joinToString("\n\n") { presenter(it) }
    }
}
