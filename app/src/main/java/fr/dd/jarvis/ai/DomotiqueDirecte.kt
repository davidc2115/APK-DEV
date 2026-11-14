package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * « Allume la lumière du salon », « ferme les volets de la chambre », « éteins la télé » : commande Home Assistant
 * directe (API), sans IA ni Hermès, quand l'appareil visé est trouvé sans ambiguïté (ou désigné dans tes notes).
 * Chauffage, poêle, valeurs chiffrées, portes, portail, alarme : laissés au cerveau (notes, confirmations).
 */
object DomotiqueDirecte {
    data class Commande(val verbe: String, val cible: String, val domaines: List<String>, val pluriel: Boolean)

    private val VERBE = Regex("^(?:(?:jarvis|ok|alors|tu peux|peux tu|est ce que tu peux|s il te plait|stp)\\s+)*" +
        "(allume|allumer|eteins|eteint|eteindre|ouvre|ouvrir|ferme|fermer|monte|remonte|leve|descends|descend|baisse|abaisse|coupe|arrete|stoppe|active|desactive|demarre|mets en marche|lance)\\s+(.+?)" +
        "(?:\\s+(?:s il te plait|stp|svp|merci|jarvis))*$")
    private val INTERDIT = Regex("\\b(chauffage|poele|poil|poils|granules?|thermostat|radiateurs?|clim|climatisation|degres?|temperature|portail|garage|portes?|serrure|alarme|camera|cameras|" +
        "four|lave|seche|aspirateur|piscine|arrosage|musique|chanson|film|serie|plex|son|volume|chaine|wifi|bluetooth|torche|telephone|portable|mode|routine|minuteur|alarmes?|vpn|box|freebox|" +
        "et|puis|si|quand|dans|pendant|tous|chaque|demain|ce soir)\\b|\\d")
    private val NOMS = listOf(
        Regex("\\b(lumieres?|lampes?|eclairages?|plafonniers?|led|leds|spots?|ampoules?|lustres?|guirlandes?|veilleuses?|appliques?)\\b") to listOf("light", "switch"),
        Regex("\\b(volets?|stores?|rideaux?|persiennes?|velux)\\b") to listOf("cover"),
        Regex("\\b(prises?|interrupteurs?)\\b") to listOf("switch"),
        Regex("\\b(ventilateurs?|ventilos?|ventilation|vmc)\\b") to listOf("fan", "switch"),
        Regex("\\b(tele|tv|television|televiseur|enceintes?|barre de son)\\b") to listOf("media_player"),
    )

    /** Commande reconnue (sans rien exécuter), ou null. Texte attendu : déjà normalisé (minuscules, sans accents). */
    fun commande(n0: String): Commande? {
        val n = n0.trim().replace(Regex("\\s+"), " ")
        if (n.split(' ').size > 10) return null
        val m = VERBE.matchEntire(n) ?: return null
        val cible = m.groupValues[2].trim()
        if (INTERDIT.containsMatchIn(cible)) return null
        val doms = NOMS.firstOrNull { it.first.containsMatchIn(cible) }?.second ?: return null
        val v = m.groupValues[1]
        val verbe = when {
            v.startsWith("allum") || v == "active" || v == "demarre" || v == "mets en marche" || v == "lance" -> if ("cover" in doms) return null else "on"
            v.startsWith("etein") || v == "coupe" || v == "desactive" -> if ("cover" in doms) return null else "off"
            v.startsWith("ouvr") || v == "monte" || v == "remonte" || v == "leve" -> if ("cover" in doms) "ouvrir" else return null
            v.startsWith("ferm") || v.startsWith("descend") || v == "baisse" || v == "abaisse" -> if ("cover" in doms) "fermer" else return null
            v == "arrete" || v == "stoppe" -> if ("cover" in doms) "stop" else "off"
            else -> return null
        }
        val pluriel = Regex("^(les|tous les|toutes les)\\b|\\b(lumieres|lampes|volets|stores|prises|spots|leds|rideaux)\\b").containsMatchIn(cible)
        return Commande(verbe, cible.replace(Regex("^(le|la|les|l|mon|ma|mes|tous les|toutes les)\\s+"), ""), doms, pluriel)
    }

    private fun service(domaine: String, verbe: String) = when (domaine) {
        "cover" -> when (verbe) { "ouvrir" -> "open_cover"; "fermer" -> "close_cover"; else -> "stop_cover" }
        else -> if (verbe == "on") "turn_on" else "turn_off"
    }

    private val VIDES = setOf("le", "la", "les", "l", "de", "du", "des", "d", "mon", "ma", "mes", "un", "une", "a", "au", "aux", "en", "sur", "dans", "tous", "toutes", "toute", "tout", "piece")
    private val MOTS_LUMIERE = Regex("lumiere|lampe|light|eclairage|led|spot|ampoule|plafonnier|lustre|guirlande|veilleuse|applique")
    private val MOTS_VENTIL = Regex("ventil|vmc|fan")

    /**
     * Entités visées : celles du premier domaine possible dont l'identifiant ou le nom contient TOUS les mots distinctifs
     * de la cible (la pièce, le nom : « salon », « bureau »…). Sans mot distinctif : toutes celles du domaine.
     */
    internal fun candidats(paires: List<Pair<String, String>>, c: Commande): List<String> {
        val mots = c.cible.split(' ').filter { it.length >= 2 && it !in VIDES && NOMS.none { (re, _) -> re.matches(it) } }
        val lumiere = NOMS[0].first.containsMatchIn(c.cible)
        for (d in c.domaines) {
            val cand = paires.filter { it.first.startsWith("$d.") }.map { (id, nom) -> id to " " + fr.dd.jarvis.societe.core.normaliser(id.replace('_', ' ').replace('.', ' ') + " " + nom) + " " }
                // Une prise n'est prise pour une lumière (ou un ventilateur) que si son nom le dit.
                .filter { (id, t) -> !(d == "switch" && c.domaines.first() != "switch") || (if (lumiere) MOTS_LUMIERE else MOTS_VENTIL).containsMatchIn(t) }
            val ok = if (mots.isEmpty()) cand else cand.filter { (_, t) -> mots.all { m -> t.contains(" $m") || t.contains(" ${m.removeSuffix("s")}") } }
            if (ok.isNotEmpty()) return ok.map { it.first }
        }
        return emptyList()
    }

    /** Exécute la commande si l'appareil est trouvé sans ambiguïté ; null sinon (le cerveau reprend, avec ses outils). */
    suspend fun executer(app: JarvisApp, tools: ToolBox, texte: String): String? {
        val c = commande(fr.dd.jarvis.societe.core.normaliser(texte)) ?: return null
        if (!Serveur.haConfigure(app)) return null
        val arr = JSONArray(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Serveur.ha(app, "GET", "/api/states") })
        val toutes = (0 until arr.length()).map { arr.getJSONObject(it) }
        val paires = toutes.map { it.getString("entity_id") to it.optJSONObject("attributes")?.optString("friendly_name").orEmpty() }
        // Ce que l'utilisateur a noté pour ce sujet (« la lumière du salon, c'est light.salon_plafond ») passe en premier.
        val notees = runCatching { fr.dd.jarvis.device.Carnet.entitesNotees(app, c.cible) }.getOrDefault(emptyList()).map { it.first }
            .filter { id -> c.domaines.any { id.startsWith("$it.") } && paires.any { it.first == id } }
        val ids: List<String> = when {
            notees.size == 1 || (notees.isNotEmpty() && c.pluriel) -> notees
            else -> {
                val trouves = candidats(paires, c)
                val exact = trouves.filter { id -> fr.dd.jarvis.societe.core.normaliser(paires.first { it.first == id }.second) == c.cible }
                when {
                    trouves.size == 1 -> trouves
                    exact.size == 1 -> exact
                    c.pluriel && trouves.size in 2..8 -> trouves
                    else -> return null
                }
            }
        }
        if (ids.isEmpty()) return null
        val res = ids.map { id ->
            val dom = id.substringBefore('.')
            val nom = paires.first { it.first == id }.second.ifBlank { id }
            val r = runCatching { tools.execute("ha_service", JSONObject().put("domaine", dom).put("service", service(dom, c.verbe)).put("entity_id", id)).text }
                .getOrElse { "❌ ${it.message}" }
            if (r.startsWith("✅")) "✅ $nom" else "$nom : $r"
        }
        val action = when (c.verbe) { "on" -> "allumé"; "off" -> "éteint"; "ouvrir" -> "ouvert"; "fermer" -> "fermé"; else -> "arrêté" }
        return if (res.all { it.startsWith("✅") }) "🏠 " + (if (ids.size == 1) res.first().removePrefix("✅ ") + " : $action." else "$action : " + res.joinToString(", ") { it.removePrefix("✅ ") } + ".")
        else "🏠 " + res.joinToString("\n")
    }
}
