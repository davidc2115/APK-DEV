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
    private val ALLUMER = Regex("\\b(allume|allumer|allumes?|rallume|rallumer|relance|relancer|redemarre|redemarrer|demarre|demarrer|mets en marche|remets en marche|remet en marche|lance|lancer|allumage|en marche)\\b")
    private val ETEINDRE = Regex("\\b(eteins|eteint|eteints|eteindre|coupe|couper|arrete|arreter|stoppe|stopper|extinction|a l arret)\\b")
    /** Ce que ce réflexe ne traite pas (consigne, mode, programmation, question…) : laissé au cerveau. */
    private val AUTRE = Regex("\\d|\\b(degres?|temperature|consigne|mode|nuit|silence|silencieux|natural|naturel|eco|turbo|boost|puissance|ventil\\w*|programm\\w*|chrono|minuteur|demain|ce soir|dans|pendant|jusqu|quand|si|est ce|combien|pourquoi|comment|etat|note|notes|prends?)\\b")
    private val VIDES = setOf("le", "la", "les", "l", "mon", "ma", "mes", "de", "du", "des", "d", "a", "au", "aux", "en", "dans", "sur", "et", "jarvis",
        "stp", "svp", "plait", "il", "te", "s", "peux", "tu", "moi", "nous", "notre", "piece")
    /** Mots de la pièce / de l'étage, avec leurs équivalents. */
    private val LIEUX = mapOf(
        "etage" to listOf("etage", "haut", "1er", "premier", "upstairs", "mezzanine"),
        "haut" to listOf("etage", "haut", "1er", "premier", "upstairs", "mezzanine"),
        "bas" to listOf("bas", "rdc", "rez", "chaussee", "downstairs", "salon", "sejour"),
        "salon" to listOf("salon", "sejour", "bas", "rdc", "rez", "chaussee", "downstairs", "living"),
        "sejour" to listOf("salon", "sejour", "bas", "rdc", "rez", "chaussee", "downstairs", "living"),
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

    /** Mots qui désignent une pièce ou un niveau de la maison. */
    private val PIECES = LIEUX.values.flatten().toSet() + setOf("cuisine", "chambre", "bureau", "garage", "veranda", "cave", "grenier",
        "entree", "couloir", "buanderie", "bibliotheque", "living")

    /**
     * Le nom de l'entité ([t], issu de texte()) désigne une AUTRE pièce que celle demandée : « switch.poele_etage_… »
     * pour le poêle du salon. Une telle entité n'est jamais prise (ni retenue) pour cette pièce, même si une consigne
     * — parfois apprise de travers par l'IA — l'associe au salon.
     */
    internal fun lieuContredit(t: String, lieu: List<String>): Boolean {
        val demandees = lieu.filter { it in PIECES }
        if (demandees.isEmpty() || PIECES.none { t.contains(" $it ") }) return false
        return demandees.none { m -> (LIEUX[m] ?: listOf(m)).any { t.contains(" $it ") } }
    }

    /**
     * Entité à piloter parmi les états Home Assistant (entity_id, nom) : le climate du poêle (au lieu demandé), sinon son
     * interrupteur principal ; jamais un mode. [notees] : entités notées par l'utilisateur pour ce poêle (prioritaires).
     */
    internal fun choisir(etats: List<Pair<String, String>>, d: Demande, notees: List<String> = emptyList()): String? {
        val parId = etats.toMap()
        notees.filter { id -> id in parId && !lieuContredit(texte(id, parId.getValue(id)), d.lieu) &&
                (id.startsWith("climate.") || (id.startsWith("switch.") && !MODE.containsMatchIn(texte(id, parId.getValue(id))))) }
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

    // ------------------------------------------------------------------ consigne : « règle le poêle du salon à 20 degrés »

    /** [valeur] : consigne voulue (°C) ; sinon [ecart] : « monte d'un degré » (+1), « baisse de 2 degrés » (-2). */
    data class Reglage(val valeur: Double?, val ecart: Double?, val lieu: List<String>, val tous: Boolean)

    private val CHAUFFE = Regex("\\b(poeles?|poiles?|poils?|poel|granules?|pellets?|inserts?|stove|chauffages?|thermostats?|radiateurs?)\\b")
    private val REGLER = Regex("\\b(regle|regler|reglez|reglez|mets|met|mettre|passe|passer|fixe|fixer|programme|change|changer|ajuste|ajuster|remets|remettre)\\b")
    private val MONTER = Regex("\\b(monte|monter|remonte|remonter|augmente|augmenter|rehausse|pousse|chauffe plus)\\b")
    private val BAISSER = Regex("\\b(baisse|baisser|diminue|diminuer|descends|descendre|redescends|reduis|reduire)\\b")
    /** Programmation, mode, heure, question : laissés au cerveau. */
    private val PAS_REGLAGE = Regex("\\b(demain|ce soir|cette nuit|dans|pendant|jusqu|quand|si|heures?|h|minutes?|min|mode|nuit|silence|eco|turbo|boost|puissance|" +
        "ventil\\w*|programmation|chrono|est ce|combien|pourquoi|comment|quelle?s?|allume|allumer|rallume|eteins|eteindre)\\b")
    private val NOMBRES = linkedMapOf("vingt neuf" to 29, "vingt huit" to 28, "vingt sept" to 27, "vingt six" to 26, "vingt cinq" to 25, "vingt quatre" to 24,
        "vingt trois" to 23, "vingt deux" to 22, "vingt et un" to 21, "vingt et une" to 21, "dix neuf" to 19, "dix huit" to 18, "dix sept" to 17,
        "seize" to 16, "quinze" to 15, "quatorze" to 14, "treize" to 13, "douze" to 12, "onze" to 11, "dix" to 10, "vingt" to 20, "trente" to 30,
        "neuf" to 9, "huit" to 8, "sept" to 7, "six" to 6, "cinq" to 5, "quatre" to 4, "trois" to 3, "deux" to 2, "un" to 1, "une" to 1)
    private val VIDES_REGLAGE = VIDES + setOf("regle", "regler", "mets", "met", "mettre", "passe", "passer", "fixe", "fixer", "programme", "change", "changer",
        "ajuste", "ajuster", "remets", "remettre", "monte", "monter", "remonte", "augmente", "augmenter", "baisse", "baisser", "diminue", "diminuer",
        "descends", "redescends", "reduis", "degre", "degres", "temperature", "consigne", "sa", "son", "un", "peu", "plus", "moins", "et", "demi", "demie",
        "tous", "toutes", "deux", "chacun", "ensemble", "celui", "celle", "poele", "poeles", "chauffage", "chauffages", "thermostat", "radiateur", "radiateurs",
        "pour", "c", "est", "fait", "faire", "reglage")

    /** Lettres simples : minuscules sans accents, « 19,5 » gardé, ponctuation → espace. */
    private fun simple(t: String) = java.text.Normalizer.normalize(t.lowercase(java.util.Locale.FRENCH), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace("°", " degres ").replace(Regex("(\\d)[.,](\\d)"), "$1#$2")
        .replace(Regex("[^a-z0-9#]+"), " ").replace('#', '.').replace(Regex("\\s+"), " ").trim()

    /** « vingt et un » → 21, « dix-neuf et demi » → 19.5 (la valeur et sa position dans le texte). */
    private fun nombreEcrit(n: String): Pair<Double, IntRange>? {
        for ((mot, v) in NOMBRES) {
            val m = Regex("\\b$mot\\b(?! peu)( et demie?)?").find(n) ?: continue   // « un peu » n'est pas un nombre
            return (v + if (m.groupValues[1].isNotBlank()) 0.5 else 0.0) to m.range
        }
        return null
    }

    /** Réglage de la température d'un poêle / chauffage (texte brut), ou null. */
    fun reglage(texte: String): Reglage? {
        val n = simple(texte)
        if (n.split(' ').size > 16 || !CHAUFFE.containsMatchIn(n) || PAS_REGLAGE.containsMatchIn(n)) return null
        val monter = MONTER.containsMatchIn(n); val baisser = BAISSER.containsMatchIn(n)
        // Sans verbe (« Poêle du salon à 18 degrés », écrit tel quel dans une routine) : une valeur en degrés, ou « à N ».
        val sansVerbe = !REGLER.containsMatchIn(n) && !monter && !baisser
        if (sansVerbe && !Regex("\\b(temperature|consigne)\\b").containsMatchIn(n) &&
            !Regex("\\b(a|sur)? ?\\d{1,2}(\\.\\d)? degres?\\b|\\ba \\d{1,2}(\\.\\d)?$|\\b(a|sur) (dix|onze|douze|treize|quatorze|quinze|seize|vingt|trente)").containsMatchIn(n)) return null
        if (sansVerbe && texte.trim().endsWith("?")) return null
        val chiffre = Regex("\\b(\\d{1,2}(?:\\.\\d)?)\\b( et demie?)?").find(n)
        val (v, ou) = chiffre?.let { m -> (m.groupValues[1].toDouble() + if (m.groupValues[2].isNotBlank()) 0.5 else 0.0) to m.range } ?: nombreEcrit(n) ?: (null to null)
        val avant = if (ou != null) n.substring(0, ou.first) else n
        // « monte le poêle de 2 degrés », « baisse d'un degré » : un écart ; « monte le poêle à 21 » : une valeur.
        val relatif = (monter || baisser) && (ou == null || Regex("\\b(de|d)\\s*$").containsMatchIn(avant))
        val reglage = when {
            relatif -> {
                val e = (v ?: if (Regex("\\bun peu\\b").containsMatchIn(n) || ou == null) 1.0 else return null)
                if (e <= 0 || e > 5) return null
                Reglage(null, if (baisser) -e else e, emptyList(), false)
            }
            v == null -> return null
            v < 5 || v > 35 -> return null
            else -> Reglage(v, null, emptyList(), false)
        }
        val tous = Regex("\\b(tous|toutes|les deux|les 2|chacun|ensemble|poeles|chauffages|radiateurs)\\b").containsMatchIn(n)
        val reste = (if (ou != null) n.removeRange(ou) else n).split(' ')
            .filter { it.length >= 2 && it !in VIDES_REGLAGE && !CHAUFFE.matches(it) && !it.any(Char::isDigit) && NOMBRES.keys.none { k -> k == it } }
        if (reste.size > 3) return null
        return reglage.copy(lieu = reste, tous = tous && reste.isEmpty())
    }

    /** Poêles (entités climate) visés par le réglage ; vide si introuvable, plusieurs pour « les poêles ». */
    internal fun cibles(toutes: List<JSONObject>, r: Reglage, notees: List<String>): List<String> {
        val paires = toutes.map { it.optString("entity_id") to it.optJSONObject("attributes")?.optString("friendly_name").orEmpty() }
        val climats = paires.filter { it.first.startsWith("climate.") }
        if (r.tous) {
            val poeles = climats.filter { (id, nom) -> POELE.containsMatchIn(texte(id, nom)) }.ifEmpty { climats }
            return poeles.map { it.first }.sorted()
        }
        val id = choisir(paires, Demande(true, r.lieu), notees)
        return listOfNotNull(id?.takeIf { it.startsWith("climate.") })
    }

    /** Consigne finale pour une entité : valeur demandée, ou consigne actuelle ± écart ; bornée et arrondie au pas du poêle. */
    internal fun cible(r: Reglage, attributs: JSONObject?): Double? {
        val a = attributs ?: JSONObject()
        val brut = r.valeur ?: a.optDouble("temperature").takeIf { !it.isNaN() }?.plus(r.ecart ?: 0.0) ?: return null
        val pas = a.optDouble("target_temp_step").takeIf { !it.isNaN() && it > 0 } ?: 0.5
        val min = a.optDouble("min_temp").takeIf { !it.isNaN() } ?: 5.0
        val max = a.optDouble("max_temp").takeIf { !it.isNaN() } ?: 35.0
        return (Math.round(brut / pas) * pas).coerceIn(min, max)
    }

    /**
     * Règle la consigne du poêle (Home Assistant, climate.set_temperature) et RELIT l'état : « réglé » seulement si le
     * poêle affiche bien la nouvelle consigne. Avant, la phrase partait à l'IA, qui répondait « 20 degrés » sans rien
     * régler. Null si ce n'est pas un réglage ou si Home Assistant n'est pas configuré (le cerveau reprend).
     */
    suspend fun executerReglage(app: JarvisApp, texte: String): String? {
        val r = reglage(texte) ?: return null
        if (!Serveur.haConfigure(app)) return null
        val arr = JSONArray(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Serveur.ha(app, "GET", "/api/states") })
        val toutes = (0 until arr.length()).map { arr.getJSONObject(it) }
        val parId = toutes.associateBy { it.optString("entity_id") }
        val notees = runCatching { fr.dd.jarvis.device.Carnet.entitesNotees(app, "poele " + r.lieu.joinToString(" ")) }.getOrDefault(emptyList()).map { it.first }
        val ids = cibles(toutes, r, notees)
        if (ids.isEmpty()) {
            val poeles = toutes.filter { it.optString("entity_id").startsWith("climate.") }
                .map { it.optJSONObject("attributes")?.optString("friendly_name").orEmpty().ifBlank { it.optString("entity_id") } }
            fr.dd.jarvis.voice.JournalVoix.noter("maison", "poêle : réglage ${r.valeur ?: r.ecart} → aucun poêle unique pour ${r.lieu}")
            return if (poeles.size > 1) "🔥 Quel poêle régler : ${poeles.joinToString(" ou ")} ? (ou « les deux »)" else null
        }
        val lignes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ids.map { id ->
                val o = parId[id]
                val nom = o?.optJSONObject("attributes")?.optString("friendly_name").orEmpty().ifBlank { id }
                val t = cible(r, o?.optJSONObject("attributes")) ?: return@map "⚠️ $nom : consigne actuelle inconnue, dis la température voulue (« à 20 degrés »)."
                fr.dd.jarvis.voice.JournalVoix.noter("maison", "poêle : climate.set_temperature $t → $id")
                val res = runCatching { Serveur.haService(app, "climate", "set_temperature", id, JSONObject().put("temperature", t)) }
                    .getOrElse { e -> return@map "❌ $nom : Home Assistant n'a pas accepté le réglage (${e.message ?: e.javaClass.simpleName})." }
                val confirme = res.startsWith("✅") || (!res.contains("INDISPONIBLE") && confirmerLongtemps(app, id) { x ->
                    kotlin.math.abs((x.optJSONObject("attributes")?.optDouble("temperature") ?: Double.NaN) - t) < 0.26
                } != null)
                val apres = runCatching { JSONObject(Serveur.ha(app, "GET", "/api/states/$id")) }.getOrNull() ?: o
                val etat = apres?.optString("state").orEmpty()
                val mesure = apres?.optJSONObject("attributes")?.optDouble("current_temperature")?.takeIf { !it.isNaN() }
                val eteint = etat == "off"
                when {
                    etat == "unavailable" -> "⚠️ $nom est indisponible dans Home Assistant : consigne non prise."
                    confirme -> "🔥 $nom réglé à ${nombre(t)} °C" + (mesure?.let { " (il fait ${nombre(it)} °C)" } ?: "") +
                        (if (eteint) ". Il est éteint : dis « allume le poêle » pour le démarrer." else ".")
                    else -> "⚠️ $nom : consigne ${nombre(t)} °C envoyée, mais il affiche encore " +
                        (apres?.optJSONObject("attributes")?.optDouble("temperature")?.takeIf { !it.isNaN() }?.let { "${nombre(it)} °C" } ?: "une autre consigne") +
                        " — il ne l'a pas prise (hors ligne, ou réglage refusé par le poêle)."
                }
            }
        }
        return lignes.joinToString("\n")
    }

    // ------------------------------------------------------------------ modes : « mets le poêle du salon en mode nuit »

    data class ModeDemande(val mode: Set<String>, val activer: Boolean, val lieu: List<String>, val tous: Boolean)

    private val MODE_ON = Regex("\\b(mets|met|mettre|remets|passe|passer|repasse|active|activer|allume|allumer|lance|enclenche|bascule|basculer)\\b")
    private val MODE_OFF = Regex("\\b(desactive|desactivee?s?|desactiver|enleve|enlever|retire|retirer|coupe|couper|eteins|eteindre|arrete|arreter|sors|sortir|quitte|quitter|stoppe|" +
        "fin|sans|hors|off|arret|retrait|plus de|pas de|annule|annuler|termine|terminer)\\b")
    private val MODE_NORMAL = Regex("\\b(normal|normale|standard|aucun|none|off|desactive|manuel|manual)\\b")
    private val VIDES_MODE = VIDES + setOf("mode", "mets", "met", "mettre", "remets", "passe", "passer", "repasse", "active", "activer", "allume", "allumer",
        "lance", "enclenche", "bascule", "basculer", "desactive", "desactiver", "enleve", "enlever", "retire", "retirer", "coupe", "couper", "eteins",
        "eteindre", "arrete", "arreter", "sors", "sortir", "quitte", "quitter", "stoppe", "tous", "toutes", "deux", "chacun", "ensemble", "poeles")

    /** Mode d'un poêle à activer / retirer (texte brut), ou null. */
    fun modeDemande(texte: String): ModeDemande? {
        // Entité écrite dans la phrase (« … avec switch.xxx ») : retirée avant de chercher la pièce.
        val n = simple(texte.replace(Regex("(?i)\\b(?:avec |via |par |entite |l entite )?(?:climate|switch|select|input_boolean|input_select|button|input_button|script|scene)\\.[a-z0-9_]+"), " "))
        if (n.split(' ').size > 14 || !POELE.containsMatchIn(n) || texte.trim().endsWith("?")) return null
        if (Regex("\\d|\\b(degres?|temperature|demain|ce soir|dans|pendant|jusqu|quand|si|est ce|combien|pourquoi|quel|quelle)\\b").containsMatchIn(n)) return null
        // « Remets le poêle en mode normal / jour » : on RETIRE les modes particuliers (nuit, silence, éco…) qui sont actifs.
        if (Regex("\\bmode (normal|normale|standard|jour|classique|habituel)\\b").containsMatchIn(n) && SYNONYMES_MODE.none { g -> g.any { Regex("\\b$it\\b").containsMatchIn(n) } }) {
            val tous = Regex("\\b(tous|toutes|les deux|les 2|chacun|ensemble|poeles)\\b").containsMatchIn(n)
            val lieu = n.split(' ').filter { it.length >= 2 && it !in VIDES_MODE && !POELE.matches(it) && it !in setOf("en", "sur", "normal", "normale", "standard", "jour", "classique", "habituel") }
            if (lieu.size > 3) return null
            return ModeDemande(SYNONYMES_MODE.flatten().toSet(), false, lieu, tous && lieu.isEmpty())
        }
        val groupe = SYNONYMES_MODE.firstOrNull { g -> g.any { Regex("\\b$it\\b").containsMatchIn(n) } } ?: return null
        val off = MODE_OFF.containsMatchIn(n)
        // Sans verbe (« Poêle du salon en mode nuit », comme on l'écrit dans une routine) : activer le mode.
        if (!off && !MODE_ON.containsMatchIn(n) && !Regex("\\bmode\\b").containsMatchIn(n)) return null
        if (!off && !MODE_ON.containsMatchIn(n) && Regex("^(est|quel|quelle|le poele est|les poeles sont)\\b|\\b(est il|sont ils)\\b").containsMatchIn(n)) return null
        val tous = Regex("\\b(tous|toutes|les deux|les 2|chacun|ensemble|poeles)\\b").containsMatchIn(n)
        val lieu = n.split(' ').filter { it.length >= 2 && it !in VIDES_MODE && !POELE.matches(it) && it !in groupe && it !in setOf("en", "sur", "nuit", "activer", "active", "plus", "pas", "off") &&
            !MODE_OFF.matches(it) && !MODE_ON.matches(it) }
        if (lieu.size > 3) return null
        return ModeDemande(groupe, !off, lieu, tous && lieu.isEmpty())
    }

    /** Une commande Home Assistant : (domaine, service, entité, données). */
    data class Commande(val domaine: String, val service: String, val id: String, val donnees: JSONObject?)

    /**
     * Commandes pour le mode demandé, poêle par poêle : préréglage de son entité climate (preset_modes), sinon son
     * interrupteur ou sélecteur de ce mode (au bon endroit). Null si introuvable ou ambigu (le cerveau reprend).
     */
    internal fun commandesMode(toutes: List<JSONObject>, d: ModeDemande, notees: List<String> = emptyList(),
                               notes: List<Pair<String, String>> = emptyList(), explicites: List<String> = emptyList()): List<Commande>? {
        val parId = toutes.associateBy { it.optString("entity_id") }
        val paires = toutes.map { it.optString("entity_id") to it.optJSONObject("attributes")?.optString("friendly_name").orEmpty() }
        fun t(id: String) = texte(id, parId[id]?.optJSONObject("attributes")?.optString("friendly_name").orEmpty())
        fun contientMode(x: String) = d.mode.any { x.contains(" $it") }
        fun options(id: String, cle: String) = parId[id]?.optJSONObject("attributes")?.optJSONArray(cle)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        /**
         * Option à choisir parmi [opts] : celle du mode (activer) ou la normale (retirer) ; sinon celle que TA consigne
         * nomme ([extrait] : « … pour retirer le mode nuit, mets-le sur Confort »).
         */
        fun option(opts: List<String>, extrait: String): String? {
            val e = " " + normaliser(extrait) + " "
            val citees = opts.filter { o -> normaliser(o).length >= 2 && e.contains(" " + normaliser(o) + " ") }
            return if (d.activer) opts.firstOrNull { contientMode(" " + normaliser(it) + " ") } ?: citees.singleOrNull()
            else opts.firstOrNull { MODE_NORMAL.containsMatchIn(normaliser(it)) } ?: citees.filter { !contientMode(" " + normaliser(it) + " ") }.singleOrNull()
        }
        /** Commande pour une entité DÉSIGNÉE (dans la phrase ou dans tes notes pour ce mode), quel que soit son nom. */
        fun pour(id: String, extrait: String = ""): Commande? {
            if (id !in parId) return null
            return when (id.substringBefore('.')) {
                "switch", "input_boolean" -> Commande(id.substringBefore('.'), if (d.activer) "turn_on" else "turn_off", id, null)
                "select", "input_select" -> option(options(id, "options"), extrait)?.let { Commande(id.substringBefore('.'), "select_option", id, JSONObject().put("option", it)) }
                "climate" -> option(options(id, "preset_modes"), extrait)?.let { Commande("climate", "set_preset_mode", id, JSONObject().put("preset_mode", it)) }
                    ?: option(options(id, "fan_modes"), extrait)?.let { Commande("climate", "set_fan_mode", id, JSONObject().put("fan_mode", it)) }
                "button", "input_button" -> if (d.activer) Commande(id.substringBefore('.'), "press", id, null) else null
                "script" -> if (d.activer) Commande("script", "turn_on", id, null) else null
                "scene" -> if (d.activer) Commande("scene", "turn_on", id, null) else null
                else -> null
            }
        }
        // 0. Entité écrite dans la phrase (« … mode nuit avec switch.xxx ») : c'est elle.
        explicites.mapNotNull { pour(it) }.takeIf { it.isNotEmpty() }?.let { return it }
        // 0 bis. Tes notes pour CE mode de CE poêle (« le mode nuit du poêle du salon, c'est select.xxx ») : elles priment.
        // Consigne qui parle de ce mode, et de cette pièce — dans sa phrase OU dans le nom de l'entité citée.
        // Jamais une entité dont le NOM dit une autre pièce (consigne apprise de travers : « salon → switch.poele_etage_… »).
        val noteesMode = notes.filter { (id, extrait) -> contientMode(" " + normaliser(extrait) + " ") && !lieuContredit(t(id), d.lieu) &&
            (d.lieu.isEmpty() || lieuOk(" " + normaliser(extrait) + " ", d.lieu) || (id in parId && lieuOk(t(id), d.lieu))) }
            .distinctBy { it.first }
            .mapNotNull { (id, extrait) -> if (id.startsWith("climate.") && options(id, "preset_modes").isEmpty() && options(id, "fan_modes").isEmpty()) null else pour(id, extrait) }
        if (noteesMode.isNotEmpty() && (d.tous || noteesMode.size == 1)) return noteesMode
        // Une consigne qui cite les deux poêles (« mode nuit : switch.a pour l'étage, switch.b pour le rez-de-chaussée ») :
        // l'entité de la pièce demandée (son nom la dit).
        if (noteesMode.size > 1 && d.lieu.isNotEmpty()) noteesMode.filter { lieuOk(t(it.id), d.lieu) }.takeIf { it.size == 1 }?.let { return it }
        val climats = paires.filter { it.first.startsWith("climate.") }
        // Poêles visés : celui du lieu (ou noté), ou tous pour « les poêles ».
        val visés: List<String?> = if (d.tous) climats.filter { (id, nom) -> POELE.containsMatchIn(texte(id, nom)) }.ifEmpty { climats }.map { it.first }.ifEmpty { listOf(null) }
            else listOf(choisir(paires, Demande(true, d.lieu), notees))
        val res = mutableListOf<Commande>()
        for (climat in visés) {
            // 1. Préréglage de l'entité climate (« night », « eco »…).
            val presets = climat?.let { parId[it]?.optJSONObject("attributes")?.optJSONArray("preset_modes") }?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val preset = if (d.activer) presets.firstOrNull { contientMode(" " + normaliser(it) + " ") }
                else presets.firstOrNull { MODE_NORMAL.containsMatchIn(normaliser(it)) }
            if (climat != null && preset != null) { res += Commande("climate", "set_preset_mode", climat, JSONObject().put("preset_mode", preset)); continue }
            // 1 bis. Mode de ventilation / puissance de l'entité climate (« night », « nuit »…).
            val fan = if (d.activer && climat != null) options(climat, "fan_modes").firstOrNull { contientMode(" " + normaliser(it) + " ") } else null
            if (climat != null && fan != null) { res += Commande("climate", "set_fan_mode", climat, JSONObject().put("fan_mode", fan)); continue }
            // 2. Interrupteur / sélecteur de ce mode, au lieu demandé et rattaché à ce poêle.
            val marques = Regex("\\b(mcz|maestro|palazzetti|edilkamin|rika|extraflame|ravelli|micronova|fumis|cadel|laminox|piazzetta)\\b")
            val jetons = climat?.let { c -> (c.substringAfter('.').split('_') + normaliser(parId[c]?.optJSONObject("attributes")?.optString("friendly_name").orEmpty()).split(' ')) }
                .orEmpty().filter { it.length >= 3 && !POELE.matches(it) && !marques.matches(it) && it !in VIDES }.distinct()
            fun duPoele(id: String): Boolean {
                val x = t(id)
                val lieuOk = if (d.lieu.isEmpty()) true else lieuOk(x, d.lieu)
                // « Les poêles » : la pièce de CE poêle doit apparaître (sinon les deux interrupteurs iraient aux deux poêles).
                val rattache = if (d.tous && jetons.isNotEmpty()) jetons.any { x.contains(" $it") }
                    else POELE.containsMatchIn(x) || jetons.any { x.contains(" $it") } || marques.containsMatchIn(x)
                return lieuOk && rattache
            }
            val notee = notees.firstOrNull { id -> id in parId && (id.startsWith("switch.") || id.startsWith("input_boolean.")) && contientMode(t(id)) &&
                !lieuContredit(t(id), d.lieu) && (!d.tous || duPoele(id)) }
            val inters = (listOfNotNull(notee) + paires.map { it.first }.filter { id ->
                (id.startsWith("switch.") || id.startsWith("input_boolean.")) && contientMode(t(id)) && duPoele(id)
            }).distinct()
            if (inters.size == 1 && (notee != null || d.tous || d.lieu.isNotEmpty() || climats.size <= 1)) {
                val dom = inters.single().substringBefore('.')
                res += Commande(dom, if (d.activer) "turn_on" else "turn_off", inters.single(), null); continue
            }
            if (!d.activer && inters.size > 1 && (d.tous || d.lieu.isNotEmpty() || climats.size <= 1)) {
                val allumes = inters.filter { parId[it]?.optString("state") == "on" }
                if (allumes.isNotEmpty()) { allumes.forEach { res += Commande(it.substringBefore('.'), "turn_off", it, null) }; continue }
                // Aucun allumé : rien à retirer pour ce poêle (déjà en mode normal).
                if (inters.all { parId[it]?.optString("state") == "off" }) continue
            }
            val selects = paires.map { it.first }.filter { id -> (id.startsWith("select.") || id.startsWith("input_select.")) && duPoele(id) }
                .mapNotNull { id ->
                    val options = parId[id]?.optJSONObject("attributes")?.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    val option = if (d.activer) options.firstOrNull { contientMode(" " + normaliser(it) + " ") } else options.firstOrNull { MODE_NORMAL.containsMatchIn(normaliser(it)) }
                    option?.let { id to it }
                }
            if (selects.size == 1 && (d.tous || d.lieu.isNotEmpty() || climats.size <= 1)) {
                val (id, option) = selects.single()
                res += Commande(id.substringBefore('.'), "select_option", id, JSONObject().put("option", option)); continue
            }
            return null
        }
        return res.ifEmpty { null }
    }

    /**
     * Un poêle (intégration cloud MCZ, Palazzetti…) peut mettre 5 à 15 s à afficher un réglage : au lieu d'un échec après
     * 2,5 s, l'état est relu jusqu'à [ms]. Vrai dès que [ok] est vérifié.
     */
    private fun confirmerLongtemps(app: JarvisApp, id: String, ms: Long = 12_000, ok: (JSONObject) -> Boolean): JSONObject? {
        val fin = System.currentTimeMillis() + ms
        var o: JSONObject? = null
        while (System.currentTimeMillis() < fin) {
            o = runCatching { JSONObject(Serveur.ha(app, "GET", "/api/states/$id")) }.getOrNull() ?: o
            if (o != null && ok(o)) return o
            Thread.sleep(1000)
        }
        return null
    }

    /**
     * L'IA a réussi le mode d'un poêle (ha_service confirmé) : la commande est retenue dans tes consignes (« maison »),
     * avec le mode, la pièce, le service et l'entité — la prochaine fois (routine comprise), la commande directe la
     * refait sans IA.
     */
    fun retenir(app: JarvisApp, demande: String, args: JSONObject) {
        val d = modeDemande(demande) ?: return
        val id = args.optString("entity_id").split(",").map { it.trim() }.singleOrNull()?.takeIf { it.isNotBlank() } ?: return
        val service = args.optString("service").trim().ifBlank { return }
        val donnees = (args.opt("donnees") as? JSONObject)?.let { o -> o.keys().asSequence().filter { it != "entity_id" }.joinToString(", ") { "$it ${o.opt(it)}" } }
            ?: (args.opt("donnees") as? String)?.takeIf { it.isNotBlank() }.orEmpty()
        // L'IA a parfois basculé le MAUVAIS poêle (ou les deux) : une entité dont le nom dit une autre pièce n'est pas
        // retenue pour celle-ci (sinon la commande directe refaisait l'erreur à chaque routine).
        val nom = runCatching { JSONObject(Serveur.ha(app, "GET", "/api/states/$id")).optJSONObject("attributes")?.optString("friendly_name") }.getOrNull().orEmpty()
        if (lieuContredit(texte(id, nom), d.lieu)) {
            fr.dd.jarvis.voice.JournalVoix.noter("maison", "consigne NON apprise : $id (« $nom ») n'est pas le poêle ${d.lieu.joinToString(" ")}")
            return
        }
        val texte = consigneApprise(d, service, id, donnees)
        // Même mode, même sens, même poêle : la ligne apprise est remplacée — celles des autres poêles restent.
        val cle = texte.substringBefore(" : service") + " :"
        val r = app.memo.remplacerConsigne("maison", texte) { it.startsWith(cle) }
        fr.dd.jarvis.voice.JournalVoix.noter("maison", "consigne apprise du poêle : $texte ($r)")
    }

    /** Ligne apprise par [retenir] : sens, mode, pièce (peut être vide), service, entité. */
    private val APPRISE = Regex("^pour (activer|retirer) (?:le mode (\\S+)|les modes particuliers) (?:du poêle(?: (.+?))?|des poêles) : service (\\S+) sur ([a-z_]+\\.[a-z0-9_]+)")

    /**
     * Consignes apprises qui se contredisent elles-mêmes (« pour activer le mode nuit du poêle salon : … sur
     * switch.poele_etage_… ») : retirées de la mémoire. [noms] : entity_id → nom affiché (états Home Assistant).
     * Rend les lignes retirées.
     */
    internal fun appriseFausses(lignes: List<String>, noms: Map<String, String>): List<String> = lignes.filter { l ->
        val m = APPRISE.find(l.trim()) ?: return@filter false
        val lieu = m.groupValues[3].split(' ').map { normaliser(it) }.filter { it.isNotBlank() }
        val id = m.groupValues[5]
        lieuContredit(texte(id, noms[id].orEmpty()), lieu)
    }

    private fun nettoyerApprises(app: JarvisApp, toutes: List<JSONObject>) {
        val lignes = runCatching { app.memo.consignesListe().filter { it.first == "maison" }.map { it.second } }.getOrDefault(emptyList())
        if (lignes.isEmpty()) return
        val noms = toutes.associate { it.optString("entity_id") to it.optJSONObject("attributes")?.optString("friendly_name").orEmpty() }
        appriseFausses(lignes, noms).forEach { l ->
            if (runCatching { app.memo.retirerConsigne("maison", l) }.getOrDefault(false))
                fr.dd.jarvis.voice.JournalVoix.noter("maison", "consigne apprise fausse retirée : $l")
        }
    }

    /**
     * Entités de poêle des modes JARVIS (Réglages › Modes) qui parlent de CE mode (« Mode nuit des poêles » : activation
     * switch.…_natural_mode turn_on, désactivation turn_off) : (entity_id, extrait) comme une consigne.
     */
    internal fun notesDesModes(systemes: List<fr.dd.jarvis.securite.Systemes.Systeme>, d: ModeDemande): List<Pair<String, String>> =
        systemes.filter { s ->
            val x = " " + normaliser(s.nom + " " + s.description + " " + (s.phrasesActiver + s.phrasesDesactiver).joinToString(" ")) + " "
            d.mode.any { x.contains(" $it ") }
        }.flatMap { s ->
            (s.activation + s.desactivation).filter { it.type == "ha" }.flatMap { e ->
                e.entites.map { id -> id to "mode JARVIS « ${s.nom} » : mode ${d.mode.first()} des poêles, ${e.cible} sur $id" }
            }
        }.distinctBy { it.first }

    internal fun consigneApprise(d: ModeDemande, service: String, id: String, donnees: String): String {
        val mode = if (d.mode.size > 3) "les modes particuliers" else "le mode ${d.mode.first()}"
        val lieu = if (d.tous) "des poêles" else "du poêle" + (if (d.lieu.isNotEmpty()) " " + d.lieu.joinToString(" ") else "")
        return "pour ${if (d.activer) "activer" else "retirer"} $mode $lieu : service $service sur $id" + (if (donnees.isNotBlank()) " ($donnees)" else "")
    }

    /** « Mets le poêle du salon en mode nuit » : exécuté puis relu dans Home Assistant ; null si introuvable (le cerveau reprend). */
    suspend fun executerMode(app: JarvisApp, texte: String): String? {
        val d = modeDemande(texte) ?: return null
        if (!Serveur.haConfigure(app)) return null
        val arr = JSONArray(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Serveur.ha(app, "GET", "/api/states") })
        val toutes = (0 until arr.length()).map { arr.getJSONObject(it) }
        nettoyerApprises(app, toutes)
        val noms = toutes.associate { it.optString("entity_id") to it.optJSONObject("attributes")?.optString("friendly_name").orEmpty() }
        // Tes modes JARVIS qui pilotent ce mode des poêles (Réglages › Modes) comptent comme des consignes.
        val desModes = runCatching { notesDesModes(fr.dd.jarvis.securite.Systemes.liste(app), d) }.getOrDefault(emptyList())
            .filter { (id, _) -> id in noms && POELE.containsMatchIn(texte(id, noms.getValue(id))) }
        val notes = (runCatching { fr.dd.jarvis.device.Carnet.entitesNoteesToutes(app, "poele " + d.lieu.joinToString(" ") + " mode " + d.mode.first()) }.getOrDefault(emptyList()) + desModes)
            .distinctBy { it.first }
        val explicites = Regex("\\b(?:climate|switch|select|input_boolean|input_select|button|input_button|script|scene)\\.[a-z0-9_]+").findAll(texte.lowercase()).map { it.value }.toList()
        val cmds = commandesMode(toutes, d, notes.map { it.first }, notes, explicites) ?: run {
            // Pour comprendre la prochaine fois : ce que JARVIS a vu (interrupteurs / sélecteurs des poêles, consignes).
            val vus = toutes.map { it.optString("entity_id") }.filter { id -> listOf("switch.", "select.", "input_boolean.", "input_select.", "button.").any { id.startsWith(it) } }
                .filter { id -> val x = texte(id, toutes.first { it.optString("entity_id") == id }.optJSONObject("attributes")?.optString("friendly_name").orEmpty()); POELE.containsMatchIn(x) || d.lieu.any { x.contains(" $it") } || MODE.containsMatchIn(x) }
                .take(12)
            fr.dd.jarvis.voice.JournalVoix.noter("maison", "poêle : ${if (d.activer) "activer" else "retirer"} mode ${d.mode.first()} ${d.lieu} → aucune entité unique ; vus : ${vus.joinToString()} ; consignes : ${notes.joinToString { it.first }}")
            return null
        }
        val nomMode = d.mode.first().let { if (it == "eco") "éco" else it }
        val lignes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            cmds.map { c ->
                fr.dd.jarvis.voice.JournalVoix.noter("maison", "poêle : ${c.domaine}.${c.service} → ${c.id}")
                val nom = toutes.firstOrNull { it.optString("entity_id") == c.id }?.optJSONObject("attributes")?.optString("friendly_name").orEmpty().ifBlank { c.id }
                val r = runCatching { Serveur.haService(app, c.domaine, c.service, c.id, c.donnees?.let { JSONObject(it.toString()) }) }.getOrElse { "❌ ${it.message}" }
                // Pas confirmé en 2,5 s : relu encore jusqu'à 12 s (poêle lent) avant de parler d'échec.
                val tard = !r.startsWith("✅") && !r.startsWith("❌") && !r.contains("INDISPONIBLE") && confirmerLongtemps(app, c.id) { o ->
                    Serveur.confirmeService(c.domaine, c.service, c.donnees ?: JSONObject(), o)
                } != null
                when {
                    r.startsWith("✅") || tard -> "🔥 $nom : mode $nomMode ${if (d.activer) "activé" else "retiré"}."
                    r.contains("INDISPONIBLE") -> "⚠️ $nom est indisponible dans Home Assistant : mode $nomMode non appliqué."
                    else -> "⚠️ $nom : mode $nomMode demandé, mais Home Assistant ne le confirme pas (l'appareil n'a peut-être pas suivi)."
                }
            }
        }
        return lignes.joinToString("\n")
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
    /**
     * Même garde-fou, mais une entité que TES consignes associent au mode demandé (« le mode nuit du poêle de l'étage,
     * c'est switch.poele_etage_natural_mode ») passe : c'est ton réglage, même si son nom parle d'un autre mode.
     */
    fun modeNonDemande(app: JarvisApp, args: JSONObject, demandeUtilisateur: String): String? {
        val refus = modeNonDemande(args, demandeUtilisateur) ?: return null
        val ids = args.optString("entity_id").split(",").map { it.trim() }.filter { it.isNotBlank() }
        val notes = runCatching { fr.dd.jarvis.device.Carnet.entitesNotees(app, demandeUtilisateur) }.getOrDefault(emptyList())
        val demande = " " + normaliser(demandeUtilisateur) + " "
        val modesDemandes = SYNONYMES_MODE.filter { g -> g.any { demande.contains(" $it ") } }.flatten()
        val autorise = modesDemandes.isNotEmpty() && ids.all { id ->
            notes.any { (e, extrait) -> e == id && modesDemandes.any { (" " + normaliser(extrait) + " ").contains(" $it ") } }
        }
        return if (autorise) null else refus
    }

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
