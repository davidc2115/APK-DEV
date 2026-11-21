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

    // ------------------------------------------------------------------ lecture : température et état des poêles

    data class Lecture(val lieu: List<String>)

    private val VALEUR = Regex("\\b(temperatures?|temp|degres?|chaud|froid|consignes?|etats?|statut|il fait|ca donne|combien)\\b")
    private val QUESTION_ETAT = Regex("\\b(est ce que|est il|sont ils|est elle|tourne|marche|allumes?|eteints?|en route|en chauffe|chauffe t il)\\b")
    private val ACTION = Regex("\\b(mets?|met|mettre|regle|regler|passe|passer|monte|monter|baisse|baisser|augmente|augmenter|diminue|diminuer|fixe|fixer|" +
        "programme|programmer|eteins|eteindre|coupe|couper|rallume|rallumer|relance|relancer|lance|lancer|demarre|demarrer|arrete|arreter|active|desactive|change|changer)\\b")
    private val VIDES_LECTURE = VIDES + setOf("quelle", "quel", "quelles", "quels", "est", "sont", "ce", "que", "qu", "il", "elle", "ils", "fait", "donne",
        "temperature", "temperatures", "temp", "degres", "degre", "chaud", "froid", "consigne", "consignes", "etat", "etats", "statut", "combien",
        "allume", "allumes", "eteint", "eteints", "tourne", "marche", "route", "chauffe", "actuelle", "actuellement", "maintenant", "donne", "dis", "moi",
        "ca", "y", "t", "quoi", "comment", "va", "vont", "presente", "presentes", "affiche", "affiches", "montre", "montres", "indique", "indiques",
        "tableau", "forme", "sous", "avec", "liste", "actuel", "actuels", "actuelles", "leur", "leurs", "deux", "tous", "toutes")

    /**
     * Présentation demandée (« … sous forme de tableau avec la température actuelle et la consigne ») et « tous / les
     * deux » : retirés avant de chercher la pièce — la lecture rend déjà un tableau de TOUS les poêles.
     */
    private val PRESENTATION = Regex("\\b(sous (la )?forme|en (un )?tableau|dans un tableau|en colonnes?|en liste|avec (la |les |leur |leurs )?(temperatures?|consignes?|etats?)|tableau)\\b.*$")
    private val TOUS = Regex("\\b(tous les deux|toutes les deux|les deux|les 2|tous|toutes|chacun|chacune|ensemble|l un et l autre)\\b")

    /** « Quelle est la température de mes poêles ? », « le poêle de l'étage est allumé ? » (texte brut), ou null. */
    fun lecture(texte: String): Lecture? {
        val brut = texte.trim()
        val n0 = normaliser(brut).replace(Regex("[?!.,;:]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (n0.split(' ').size > 30 || !POELE.containsMatchIn(n0)) return null
        // La présentation demandée ne doit ni faire refuser la phrase (trop longue) ni passer pour une pièce.
        val sansPresentation = n0.replace(PRESENTATION, "").trim()
        val n = (if (POELE.containsMatchIn(sansPresentation) && VALEUR.containsMatchIn(sansPresentation)) sansPresentation else n0).replace(TOUS, " ").replace(Regex("\\s+"), " ").trim()
        if (n.split(' ').size > 16) return null
        if (ACTION.containsMatchIn(n) || Regex("\\d").containsMatchIn(n)) return null
        val valeur = VALEUR.containsMatchIn(n)
        val etat = (brut.endsWith("?") || n.startsWith("est ce")) && QUESTION_ETAT.containsMatchIn(n)
        if (!valeur && !etat) return null
        val lieu = n.split(' ').filter { it.length >= 2 && it !in VIDES_LECTURE && !POELE.matches(it) }
        if (lieu.size > 3) return null
        return Lecture(lieu)
    }

    private fun nombre(x: Double): String = if (x == Math.floor(x)) x.toLong().toString() else String.format(java.util.Locale.FRANCE, "%.1f", x)

    /** Une ligne lisible pour un poêle (entité climate) : température mesurée, consigne, marche. */
    internal fun ligneClimat(o: JSONObject): String {
        val a = o.optJSONObject("attributes") ?: JSONObject()
        val nom = a.optString("friendly_name").ifBlank { o.optString("entity_id") }
        val etat = o.optString("state")
        if (etat == "unavailable" || etat == "unknown") return "🔥 $nom : indisponible dans Home Assistant"
        val mesure = a.optDouble("current_temperature").takeIf { !it.isNaN() }
        val consigne = a.optDouble("temperature").takeIf { !it.isNaN() }
        val marche = when (a.optString("hvac_action")) {
            "heating" -> "en chauffe"; "idle" -> "au repos"; "off" -> "éteint"; "fan" -> "ventilation"
            else -> when (etat) { "off" -> "éteint"; "heat", "auto", "heat_cool" -> "allumé"; else -> etat }
        }
        val details = listOfNotNull(consigne?.let { "consigne ${nombre(it)} °C" }, marche.takeIf { it.isNotBlank() }).joinToString(", ")
        return "🔥 $nom : " + (mesure?.let { "${nombre(it)} °C" } ?: "température non fournie") + (if (details.isNotBlank()) " ($details)" else "")
    }

    /** Température / état des poêles (au lieu demandé), lus dans Home Assistant ; null si aucun poêle trouvé. */
    internal fun lire(toutes: List<JSONObject>, l: Lecture, notees: List<String> = emptyList()): String? {
        val parId = toutes.associateBy { it.optString("entity_id") }
        val paires = toutes.map { it.optString("entity_id") to it.optJSONObject("attributes")?.optString("friendly_name").orEmpty() }
        fun nomDe(id: String) = parId[id]?.optJSONObject("attributes")?.optString("friendly_name").orEmpty()
        val marques = Regex("\\b(mcz|maestro|palazzetti|edilkamin|rika|extraflame|ravelli|micronova|fumis|cadel|laminox|piazzetta)\\b")
        fun estPoele(id: String) = POELE.containsMatchIn(texte(id, nomDe(id))) || marques.containsMatchIn(texte(id, nomDe(id)))
        fun chauffe(id: String) = parId[id]?.optJSONObject("attributes")?.optJSONArray("hvac_modes")?.let { a -> (0 until a.length()).any { a.optString(it) == "heat" } } ?: true
        // Poêles nommés comme tels ; sinon (entités « climate.etage », « climate.salon »…) tous les chauffages : « mes
        // poêles » veut dire TOUS, jamais « lequel ? ».
        val climats = notees.filter { it.startsWith("climate.") && it in parId }.ifEmpty {
            paires.filter { (id, _) -> id.startsWith("climate.") && estPoele(id) }.map { it.first }
        }.ifEmpty { paires.filter { (id, _) -> id.startsWith("climate.") && chauffe(id) }.map { it.first } }
        val auLieu = if (l.lieu.isEmpty()) climats else climats.filter { lieuOk(texte(it, nomDe(it)), l.lieu) }
        if (auLieu.size == 1) return ligneClimat(parId.getValue(auLieu.single()))
        if (auLieu.isNotEmpty()) return tableauClimats(auLieu.sorted().map { parId.getValue(it) })
        // Pas d'entité climate : capteurs de température du poêle (sensor.*), jamais un mode.
        val capteurs = paires.filter { (id, nom) ->
            id.startsWith("sensor.") && estPoele(id) && !MODE.containsMatchIn(texte(id, nom)) &&
                (Regex("\\b(temp\\w*)\\b").containsMatchIn(texte(id, nom)) || parId[id]?.optJSONObject("attributes")?.optString("device_class") == "temperature")
        }.map { it.first }.filter { l.lieu.isEmpty() || lieuOk(texte(it, nomDe(it)), l.lieu) }
        if (capteurs.isEmpty()) return null
        fun valeur(id: String) = parId.getValue(id).let { o -> o.optString("state") + o.optJSONObject("attributes")?.optString("unit_of_measurement")?.let { " $it" }.orEmpty() }
        if (capteurs.size == 1) return "🌡️ ${nomDe(capteurs.single()).ifBlank { capteurs.single() }} : ${valeur(capteurs.single())}"
        return "🌡️ Températures des poêles\n\n| Capteur | Valeur |\n|---|---|\n" +
            capteurs.sorted().take(8).joinToString("\n") { id -> "| ${nomDe(id).ifBlank { id }} | ${valeur(id)} |" }
    }

    /** Plusieurs poêles : un tableau (température actuelle, consigne, état), plus lisible qu'une liste. */
    internal fun tableauClimats(l: List<JSONObject>): String = "🔥 Poêles\n\n| Poêle | Température actuelle | Consigne | État |\n|---|---|---|---|\n" +
        l.joinToString("\n") { o ->
            val a = o.optJSONObject("attributes") ?: JSONObject()
            val nom = a.optString("friendly_name").ifBlank { o.optString("entity_id") }
            val etat = o.optString("state")
            if (etat == "unavailable" || etat == "unknown") "| $nom | — | — | indisponible |"
            else {
                val mesure = a.optDouble("current_temperature").takeIf { !it.isNaN() }?.let { "${nombre(it)} °C" } ?: "—"
                val consigne = a.optDouble("temperature").takeIf { !it.isNaN() }?.let { "${nombre(it)} °C" } ?: "—"
                val marche = when (a.optString("hvac_action")) {
                    "heating" -> "en chauffe"; "idle" -> "au repos"; "off" -> "éteint"; "fan" -> "ventilation"
                    else -> when (etat) { "off" -> "éteint"; "heat", "auto", "heat_cool" -> "allumé"; else -> etat }
                }
                "| $nom | $mesure | $consigne | $marche |"
            }
        }

    /** Répond à la lecture ; null si ce n'en est pas une ou si aucun poêle n'est trouvé (le cerveau reprend). */
    private val SUITE_TOUS = Regex("^(et )?(les deux|les 2|tous|toutes|tous les deux|toutes les deux|les deux poeles|tous les poeles|chacun|l un et l autre|tous les 2|ensemble|" +
        "(donne|dis)( moi)? (les deux|tous|pour les deux|pour tous)|pour (les deux|tous|chacun))( stp| s il te plait| svp)?$")

    /**
     * « Les deux » / « tous » juste après « quel poêle ? » : la lecture précédente, pour TOUS les poêles (null sinon).
     * Avant, la suite partait à l'IA qui rendait une liste au lieu des valeurs.
     */
    fun suiteTous(texte: String, history: List<fr.dd.jarvis.ai.ChatMsg>): String? {
        val n = normaliser(texte).replace(Regex("[?!.,;:]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (!SUITE_TOUS.matches(n)) return null
        val avant = history.dropLast(1)
        val question = avant.lastOrNull { !it.fromUser }?.text ?: return null
        if (!POELE.containsMatchIn(normaliser(question)) || '?' !in question) return null
        val demande = avant.lastOrNull { it.fromUser }?.text ?: return null
        val l = lecture(demande) ?: return null
        // La pièce éventuellement comprise de travers dans la première demande est ignorée : tous les poêles.
        return if (l.lieu.isEmpty()) demande else "quelle est la température de mes poêles"
    }

    suspend fun executerLecture(app: JarvisApp, texte: String): String? {
        val l = lecture(texte) ?: return null
        if (!Serveur.haConfigure(app)) return null
        val arr = JSONArray(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Serveur.ha(app, "GET", "/api/states") })
        val toutes = (0 until arr.length()).map { arr.getJSONObject(it) }
        val notees = runCatching { fr.dd.jarvis.device.Carnet.entitesNotees(app, "poele " + l.lieu.joinToString(" ")) }.getOrDefault(emptyList()).map { it.first }
        fr.dd.jarvis.voice.JournalVoix.noter("maison", "poêle : lecture ${l.lieu}")
        return lire(toutes, l, notees)
    }

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
