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
 * « Systèmes » de JARVIS : des modes complets créés, réglés, activés et désactivés à la demande (« installe le mode
 * maison sécurisée », « ajoute le numéro de Marie », « active la maison sécurisée »…). Chaque système a :
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
    )

    /** Début de la question de confirmation d'une désactivation (voix du propriétaire exigée si le téléphone est verrouillé). */
    const val QUESTION = "🛡️ Désactiver le système de sécurité"

    val MODELES = linkedMapOf(
        "telephone_securise" to "📱 Téléphone sécurisé (veille + verrouillage, déverrouillage par ta voix)",
        "maison_securisee" to "🏠 Maison sécurisée (portes, volets, alarme, photos si une personne est détectée)",
        "personnalise" to "🧩 Système personnalisé (étapes au choix)",
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
                .put("viaHa", s.viaHa).put("viaMms", s.viaMms).put("automationHa", s.automationHa).put("actif", s.actif).put("activeLe", s.activeLe))
        }
    }

    internal fun depuisJson(a: JSONArray): List<Systeme> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
        Systeme(o.optString("id"), o.optString("nom"), o.optString("modele", "personnalise"), liste(o, "phrasesActiver"), liste(o, "phrasesDesactiver"),
            etapes(o, "activation"), etapes(o, "desactivation"), liste(o, "capteurs"), liste(o, "cameras"), liste(o, "notifierHa"),
            liste(o, "destinataires"), liste(o, "sirenes"), o.optBoolean("sireneSurDetection"), o.optBoolean("viaHa", true), o.optBoolean("viaMms", true),
            o.optString("automationHa"), o.optBoolean("actif"), o.optLong("activeLe"))
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
    fun parPhrase(app: JarvisApp, texte: String): Pair<Systeme, Boolean>? {
        if (texte.length > 90) return null
        val t = norm(texte).removePrefix("jarvis ").trim()
        if (t.isBlank() || Regex("\\b(installe|cree|configure|ajoute|retire|enleve|modifie|supprime|parametre|regle)\\b").containsMatchIn(t)) return null
        val l = liste(app)
        l.firstOrNull { s -> s.phrasesDesactiver.any { norm(it) == t } }?.let { return it to false }
        l.firstOrNull { s -> s.phrasesActiver.any { norm(it) == t } }?.let { return it to true }
        val desactiver = Regex("^(desactive|coupe|arrete|desarme|stoppe|quitte|sors du|eteins)\\b").containsMatchIn(t)
        val activer = !desactiver && Regex("^(active|lance|enclenche|arme|demarre|mets|passe)\\b").containsMatchIn(t)
        if (!desactiver && !activer) return null
        val reste = t.replace(Regex("^(desactive|coupe|arrete|desarme|stoppe|quitte|sors du|eteins|active|lance|enclenche|arme|demarre|mets|passe)\\s+(en\\s+)?(le |la |l |mon |ma )?(mode |systeme )?"), "").trim()
        if (reste.length < 4) return null
        val s = l.firstOrNull { norm(it.nom).removePrefix("mode ") == reste.removePrefix("mode ") } ?: return null
        return s to activer
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
        "telephone" -> when (e.cible) { "verrouiller", "veille" -> "📴 Veille et verrouillage du téléphone"; else -> "📱 Téléphone : ${e.cible}" }
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

    // ------------------------------------------------------------------ présentation

    fun presenter(s: Systeme): String = buildString {
        append("## ${if (s.actif) "🟢" else "⚪"} ${s.nom}  (${MODELES[s.modele]?.substringBefore(" (") ?: s.modele})\n")
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
        if (l.isEmpty()) return "Aucun système installé. Modèles disponibles :\n" + MODELES.values.joinToString("\n") { "- $it" } +
            "\nDis par exemple « installe le mode maison sécurisée »."
        return "# 🛡️ Systèmes JARVIS\n" + l.joinToString("\n\n") { presenter(it) }
    }
}
