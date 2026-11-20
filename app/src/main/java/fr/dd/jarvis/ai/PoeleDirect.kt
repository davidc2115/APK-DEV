package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONArray
import org.json.JSONObject

/**
 * « Rallume mon poêle à l'étage », « éteins le poêle du salon » : marche / arrêt du POÊLE lui-même, par Home Assistant,
 * sans IA.
 *
 * Un poêle à granulés expose dans Home Assistant son entité principale (climate.*) ET plusieurs interrupteurs de MODE
 * (mode nuit, silence, natural, éco…). Le modèle prenait « allumer » au pied de la lettre et allumait un de ces
 * interrupteurs de mode au lieu du poêle. Ici, l'appareil visé est toujours l'entité climate du poêle (ou son vrai
 * interrupteur marche / arrêt), jamais un mode — un mode ne s'active que si tu le nommes.
 */
object PoeleDirect {
    data class Demande(val allumer: Boolean, val lieu: List<String>)

    private val POELE = Regex("\\b(poeles?|poiles?|poils?|granules?|pellets?|inserts?|stove)\\b")
    private val ALLUMER = Regex("\\b(allume|allumer|rallume|rallumer|relance|relancer|redemarre|redemarrer|demarre|demarrer|mets en marche|remets en marche|remet en marche|lance|lancer)\\b")
    private val ETEINDRE = Regex("\\b(eteins|eteint|eteindre|coupe|couper|arrete|arreter|stoppe|stopper)\\b")
    /** Ce que ce réflexe ne traite pas (consigne, mode, programmation, question…) : laissé au cerveau. */
    private val AUTRE = Regex("\\d|\\b(degres?|temperature|consigne|mode|nuit|silence|silencieux|natural|naturel|eco|turbo|boost|puissance|ventil\\w*|programm\\w*|chrono|minuteur|demain|ce soir|dans|pendant|jusqu|quand|si|est ce|combien|pourquoi|comment|etat|note|notes|prends?)\\b")
    private val VIDES = setOf("le", "la", "les", "l", "mon", "ma", "mes", "de", "du", "des", "d", "a", "au", "aux", "en", "dans", "sur", "et", "jarvis",
        "stp", "svp", "plait", "il", "te", "s", "peux", "tu", "moi", "nous", "notre", "piece")
    /** Mots de la pièce / de l'étage, avec leurs équivalents. */
    private val LIEUX = mapOf(
        "etage" to listOf("etage", "haut", "1er", "premier", "upstairs", "mezzanine"),
        "haut" to listOf("etage", "haut", "1er", "premier", "upstairs", "mezzanine"),
        "bas" to listOf("bas", "rdc", "rez", "chaussee", "downstairs", "salon", "sejour"),
        "rdc" to listOf("bas", "rdc", "rez", "chaussee", "downstairs"),
    )
    /** Interrupteurs / sélecteurs de MODE d'un poêle : jamais pris pour le poêle lui-même. */
    internal val MODE = Regex("\\b(nuit|night|silence|silent|silencieux|natural|naturel|nature|eco|economique|turbo|boost|sleep|confort|comfort|chrono|ventil\\w*|fan|auto|vacances|antigel|hors gel|ambiance|puissance|power level|niveau|pellet level|alarme|reset|nettoyage|cleaning|crono|programme)\\b")

    /** Demande de marche / arrêt d'un poêle (texte brut), ou null. */
    fun demande(texte: String): Demande? {
        val n = normaliser(texte).replace(Regex("[?!.,;:]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (n.split(' ').size > 12 || !POELE.containsMatchIn(n) || AUTRE.containsMatchIn(n)) return null
        val allumer = ALLUMER.containsMatchIn(n)
        val eteindre = ETEINDRE.containsMatchIn(n)
        if (allumer == eteindre) return null
        val lieu = n.split(' ').filter { it.length >= 2 && it !in VIDES && !POELE.matches(it) && !ALLUMER.matches(it) && !ETEINDRE.matches(it) &&
            it !in setOf("rallume", "allume", "eteins", "marche", "remets", "remet", "mets") }
        if (lieu.size > 3) return null
        return Demande(allumer, lieu)
    }

    private fun texte(id: String, nom: String) = " " + normaliser(id.substringAfter('.').replace('_', ' ') + " " + nom) + " "

    private fun lieuOk(t: String, lieu: List<String>) = lieu.all { m -> (LIEUX[m] ?: listOf(m)).any { t.contains(" $it") } }

    /**
     * Entité à piloter parmi les états Home Assistant (entity_id, nom) : le climate du poêle (au lieu demandé), sinon son
     * interrupteur principal ; jamais un mode. [notees] : entités notées par l'utilisateur pour ce poêle (prioritaires).
     */
    internal fun choisir(etats: List<Pair<String, String>>, d: Demande, notees: List<String> = emptyList()): String? {
        val parId = etats.toMap()
        notees.filter { id -> id in parId && (id.startsWith("climate.") || (id.startsWith("switch.") && !MODE.containsMatchIn(texte(id, parId.getValue(id))))) }
            .let { l -> val c = l.filter { it.startsWith("climate.") }.ifEmpty { l }; if (c.size == 1) return c.single() }
        val climats = etats.filter { (id, _) -> id.startsWith("climate.") }
        val poeles = climats.filter { (id, nom) -> POELE.containsMatchIn(texte(id, nom)) || Regex("\\b(mcz|maestro|palazzetti|edilkamin|rika|extraflame|ravelli|micronova|fumis|cadel|laminox|piazzetta)\\b").containsMatchIn(texte(id, nom)) }
        val base = poeles.ifEmpty { climats }
        val auLieu = if (d.lieu.isEmpty()) base else base.filter { (id, nom) -> lieuOk(texte(id, nom), d.lieu) }
        if (auLieu.size == 1) return auLieu.single().first
        if (auLieu.isEmpty() && d.lieu.isEmpty() && base.size == 1) return base.single().first
        // Pas d'entité climate : le vrai interrupteur marche / arrêt du poêle (jamais un mode).
        if (climats.isEmpty()) {
            val inter = etats.filter { (id, nom) -> id.startsWith("switch.") && POELE.containsMatchIn(texte(id, nom)) && !MODE.containsMatchIn(texte(id, nom)) }
                .filter { (id, nom) -> d.lieu.isEmpty() || lieuOk(texte(id, nom), d.lieu) }
            if (inter.size == 1) return inter.single().first
        }
        return null
    }

    /** Service Home Assistant pour une entité climate (selon ses modes) : (service, données). */
    internal fun service(allumer: Boolean, attributs: JSONObject?): Pair<String, JSONObject?> {
        val modes = attributs?.optJSONArray("hvac_modes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        return if (allumer) when {
            "heat" in modes -> "set_hvac_mode" to JSONObject().put("hvac_mode", "heat")
            "auto" in modes -> "set_hvac_mode" to JSONObject().put("hvac_mode", "auto")
            else -> "turn_on" to null
        } else when {
            "off" in modes -> "set_hvac_mode" to JSONObject().put("hvac_mode", "off")
            else -> "turn_off" to null
        }
    }

    /** Exécute la demande ; null si elle n'en est pas une ou si le poêle n'est pas trouvé sans ambiguïté (le cerveau reprend). */
    suspend fun executer(app: JarvisApp, texte: String): String? {
        val d = demande(texte) ?: return null
        if (!Serveur.haConfigure(app)) return null
        val arr = JSONArray(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Serveur.ha(app, "GET", "/api/states") })
        val toutes = (0 until arr.length()).map { arr.getJSONObject(it) }
        val paires = toutes.map { it.getString("entity_id") to it.optJSONObject("attributes")?.optString("friendly_name").orEmpty() }
        val notees = runCatching { fr.dd.jarvis.device.Carnet.entitesNotees(app, "poele " + d.lieu.joinToString(" ")) }.getOrDefault(emptyList()).map { it.first }
        val id = choisir(paires, d, notees) ?: return null
        val attributs = toutes.firstOrNull { it.getString("entity_id") == id }?.optJSONObject("attributes")
        val (domaine, service, donnees) = if (id.startsWith("climate.")) service(d.allumer, attributs).let { (s, x) -> Triple("climate", s, x) }
            else Triple("switch", if (d.allumer) "turn_on" else "turn_off", null)
        fr.dd.jarvis.voice.JournalVoix.noter("maison", "poêle : $domaine.$service → $id")
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { "🔥 " + Serveur.haService(app, domaine, service, id, donnees) }
    }

    private val APPAREIL_MAISON = Regex("\\b(poeles?|poiles?|poils?|granules?|pellets?|inserts?|chauffage|radiateurs?|thermostat|clim|climatisation|pac|vmc|ventilation|ventilateurs?|aspirateur|robot|lave linge|lave vaisselle|seche linge|four|hotte|chaudiere|chauffe eau|deshumidificateur|purificateur|home assistant)\\b")
    private val TELEPHONE = Regex("\\b(telephone|portable|smartphone|mobile|tel|sonnerie|vibreur|ne pas deranger|npd)\\b")

    /** « mets le poêle en mode silence » : la demande vise un appareil de la maison, pas le mode silencieux du téléphone. */
    fun visePasLeTelephone(demandeUtilisateur: String): Boolean {
        val n = normaliser(demandeUtilisateur)
        return APPAREIL_MAISON.containsMatchIn(n) && !TELEPHONE.containsMatchIn(n)
    }

    const val REFUS_MODE_TELEPHONE = "⛔ Non exécuté : la demande concerne un appareil de la maison (poêle, chauffage…), pas le mode " +
        "silencieux du TÉLÉPHONE. Son mode silence / nuit / natural se règle dans Home Assistant (ha_entites puis ha_service sur " +
        "l'entité de cet appareil) ; pour l'allumer ou l'éteindre, vise son entité climate.*."

    private val SYNONYMES_MODE = listOf(setOf("nuit", "night", "sleep"), setOf("silence", "silent", "silencieux"),
        setOf("natural", "naturel", "nature"), setOf("eco", "economique"), setOf("confort", "comfort"), setOf("chrono", "crono", "programme"))

    /**
     * Garde-fou pour le modèle : un appel ha_service sur un interrupteur / sélecteur de MODE d'un poêle (mode nuit,
     * silence, natural…) alors que la demande ne nomme pas ce mode → refusé, avec l'explication pour viser le poêle
     * lui-même. Null : l'appel peut partir.
     */
    fun modeNonDemande(args: JSONObject, demandeUtilisateur: String): String? {
        val domaine = args.optString("domaine").trim().lowercase()
        val ids = args.optString("entity_id").split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (ids.isEmpty() || domaine == "climate") return null
        if (domaine !in setOf("switch", "select", "input_boolean", "input_select", "button", "fan", "number", "input_number")) return null
        val demande = " " + normaliser(demandeUtilisateur) + " "
        val chauffage = POELE.containsMatchIn(demande) || Regex("\\b(chauffage|radiateur|thermostat|clim)\\b").containsMatchIn(demande)
        for (id in ids) {
            val t = texte(id, "")
            if (!chauffage && !POELE.containsMatchIn(t)) continue
            val modes = MODE.findAll(t).map { it.value }.toList()
            if (modes.isEmpty()) continue
            val nomme = modes.any { m -> (SYNONYMES_MODE.firstOrNull { m in it } ?: setOf(m)).any { demande.contains(" $it") } }
            if (!nomme) return "⛔ Non exécuté : `$id` est un MODE du poêle (« ${modes.joinToString()} »), pas le poêle lui-même, et l'utilisateur " +
                "n'a pas demandé ce mode. Pour allumer / rallumer / éteindre le poêle, vise son entité climate.* (climate.set_hvac_mode " +
                "{hvac_mode: heat} ou off, sinon climate.turn_on / turn_off), ou son vrai interrupteur marche/arrêt. Cherche-la avec " +
                "ha_entites (domaine climate) ou dans les notes de l'utilisateur."
        }
        return null
    }
}
