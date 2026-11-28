package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Scripts et automatisations Home Assistant qui MARCHENT : configuration remise au format attendu par la version de
 * Home Assistant (un script a une « sequence », jamais « triggers / actions » ; « service: » avant 2024.8, « action: »
 * ensuite ; « platform: » avant 2024.10 pour les déclencheurs), identifiant valide, et choix de la bonne méthode pour
 * piloter un appareil d'après ce qu'il sait VRAIMENT faire sur cette installation (allumer une télé ne se fait pas de
 * la même façon sur une LG webOS, une Samsung, une Android TV ou un Chromecast).
 */
object HaScripts {

    // ------------------------------------------------------------------ format de la configuration

    /** Identifiant de script valide (« Allumer la télé ! » → « allumer_la_tele »). */
    fun slug(texte: String): String = java.text.Normalizer.normalize(texte.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), "_").trim('_').take(60).trim('_')

    /** Version de Home Assistant au moins [annee].[mois] (« 2025.11.3 », « 2024.8.0b1 »). Inconnue : on suppose récente. */
    fun versionAuMoins(version: String, annee: Int, mois: Int): Boolean {
        val p = Regex("^(\\d{4})\\.(\\d{1,2})").find(version.trim()) ?: return true
        val a = p.groupValues[1].toInt(); val m = p.groupValues[2].toInt()
        return a > annee || (a == annee && m >= mois)
    }

    /** Copie profonde où chaque étape « action: domaine.service » devient « service: » (Home Assistant avant 2024.8). */
    private fun ancienneSyntaxe(v: Any?): Any? = when (v) {
        is JSONObject -> JSONObject().also { o ->
            v.keys().forEach { k ->
                val x = v.opt(k)
                val cle = if (k == "action" && x is String && x.contains('.')) "service" else k
                o.put(cle, ancienneSyntaxe(x))
            }
        }
        is JSONArray -> JSONArray().also { a -> for (i in 0 until v.length()) a.put(ancienneSyntaxe(v.opt(i))) }
        else -> v
    }

    private fun enListe(v: Any?): JSONArray = when (v) {
        is JSONArray -> v
        null, JSONObject.NULL -> JSONArray()
        else -> JSONArray().put(v)
    }

    /**
     * Script : la liste d'étapes s'appelle « sequence » (l'IA écrit souvent « actions », refusé par Home Assistant :
     * « extra keys not allowed @ data['actions'] ») ; les clés d'automatisation sont retirées.
     */
    fun normaliserScript(conf: JSONObject, version: String = ""): JSONObject {
        val c = JSONObject(conf.toString())
        if (!c.has("sequence")) {
            val etapes = listOf("actions", "action", "steps", "etapes").firstNotNullOfOrNull { k -> c.opt(k)?.takeIf { it !is String } }
            if (etapes != null) c.put("sequence", enListe(etapes))
        } else c.put("sequence", enListe(c.opt("sequence")))
        listOf("actions", "action", "steps", "etapes", "triggers", "trigger", "conditions", "condition", "id").forEach { c.remove(it) }
        if (!c.has("mode")) c.put("mode", "single")
        return if (versionAuMoins(version, 2024, 8)) c else ancienneSyntaxe(c) as JSONObject
    }

    /** Automatisation : « triggers / conditions / actions » (2024.10+) ou « trigger / condition / action » avant. */
    fun normaliserAutomatisation(conf: JSONObject, version: String = ""): JSONObject {
        val c = JSONObject(conf.toString())
        val recente = versionAuMoins(version, 2024, 10)
        val paires = listOf("triggers" to "trigger", "conditions" to "condition", "actions" to "action")
        for ((nouveau, ancien) in paires) {
            val v = c.opt(nouveau) ?: c.opt(ancien)?.takeIf { !(ancien == "action" && it is String) }
            c.remove(nouveau); c.remove(ancien)
            if (v != null) c.put(if (recente) nouveau else ancien, enListe(v))
        }
        if (c.has("sequence") && !c.has("actions") && !c.has("action")) { c.put(if (recente) "actions" else "action", enListe(c.opt("sequence"))); c.remove("sequence") }
        if (!recente) {
            // Déclencheurs : « trigger: state » → « platform: state » ; étapes : « action: x.y » → « service: x.y ».
            c.optJSONArray("trigger")?.let { t -> for (i in 0 until t.length()) t.optJSONObject(i)?.let { o -> o.opt("trigger")?.let { v -> o.remove("trigger"); o.put("platform", v) } } }
            return ancienneSyntaxe(c) as JSONObject
        }
        return c
    }

    /** Entités pilotées par une configuration : valeurs des champs « entity_id » (y compris dans target / data), pas les noms de services. */
    fun entitesCitees(conf: String): Set<String> {
        val out = linkedSetOf<String>()
        fun ajouter(v: Any?) {
            when (v) {
                is String -> v.split(',').map { it.trim() }.filter { Regex("^[a-z_]+\\.[a-z0-9_]+$").matches(it) }.forEach { out += it }
                is JSONArray -> for (i in 0 until v.length()) ajouter(v.opt(i))
            }
        }
        fun parcourir(v: Any?) {
            when (v) {
                is JSONObject -> v.keys().forEach { k -> val x = v.opt(k); if (k in setOf("entity_id", "media_player_entity_id", "entity_ids")) ajouter(x) else parcourir(x) }
                is JSONArray -> for (i in 0 until v.length()) parcourir(v.opt(i))
            }
        }
        val brut = conf.trim()
        runCatching { parcourir(if (brut.startsWith("[")) JSONArray(brut) else JSONObject(brut)) }
        return out
    }

    // ------------------------------------------------------------------ capacités d'un lecteur (télé, enceinte…)

    private val FONCTIONS_MEDIA = listOf(1 to "pause", 2 to "avance", 4 to "volume_set", 8 to "muet", 16 to "précédent", 32 to "suivant",
        128 to "allumer", 256 to "éteindre", 512 to "play_media", 1024 to "volume +/-", 2048 to "choisir la source", 4096 to "stop",
        16384 to "lecture", 65536 to "mode son", 131072 to "parcourir les médias")

    /** Fonctions d'un media_player d'après supported_features. */
    fun fonctionsMedia(features: Int): List<String> = FONCTIONS_MEDIA.filter { (bit, _) -> features and bit != 0 }.map { it.second }

    data class Methode(val titre: String, val etape: JSONObject?, val note: String)

    private fun etape(service: String, cible: String? = null, donnees: JSONObject? = null) = JSONObject().put("action", service).apply {
        if (cible != null) put("target", JSONObject().put("entity_id", cible))
        if (donnees != null) put("data", donnees)
    }

    /**
     * Façons d'ALLUMER l'appareil [entite] (de la plus sûre à la moins sûre), d'après ce que Home Assistant en dit :
     * fonctions prises en charge, intégration ([plateforme]), entités du même appareil ([freres]), services disponibles
     * ([services] : domaines présents), adresse MAC connue ([mac]).
     */
    fun methodesAllumage(entite: String, plateforme: String, features: Int, freres: List<String>, services: Set<String>, mac: String?): List<Methode> {
        val out = mutableListOf<Methode>()
        val domaine = entite.substringBefore('.')
        val p = plateforme.lowercase()
        val wol = "wake_on_lan" in services
        val remote = freres.firstOrNull { it.startsWith("remote.") }
        val prise = freres.firstOrNull { it.startsWith("switch.") }
        if (domaine != "media_player") {
            out += Methode("$domaine.turn_on", etape("$domaine.turn_on", entite), "service standard de l'entité")
            return out
        }
        val allumageDirect = features and 128 != 0
        when (p) {
            "webostv" -> {
                // LG webOS : l'intégration ne sait PAS allumer seule ; il faut le Wake-on-LAN (TV : « Allumer via Wi-Fi / Mobile TV On »).
                if (mac != null && wol) out += Methode("Wake-on-LAN (LG webOS)", etape("wake_on_lan.send_magic_packet", donnees = JSONObject().put("mac", mac)),
                    "sur la TV : Réglages › Général › Appareils › « Allumer via Wi-Fi » (ou « Mobile TV On ») activé")
                else out += Methode("Wake-on-LAN (LG webOS) — à préparer", null,
                    (if (!wol) "ajouter l'intégration « Wake on LAN » (ha_admin integration_ajouter domaine=wake_on_lan, ou « wake_on_lan: » dans configuration.yaml) ; " else "") +
                        (if (mac == null) "adresse MAC de la TV inconnue (dans la TV : Réglages › Réseau, ou la box) ; " else "") + "puis wake_on_lan.send_magic_packet avec la MAC")
            }
            "samsungtv" -> {
                if (allumageDirect) out += Methode("media_player.turn_on (Samsung)", etape("media_player.turn_on", entite), "l'intégration Samsung allume via Wake-on-LAN quand la MAC est connue")
                if (mac != null && wol) out += Methode("Wake-on-LAN (Samsung)", etape("wake_on_lan.send_magic_packet", donnees = JSONObject().put("mac", mac)), "TV : « Allumer avec mobile » / IP Remote activés")
            }
            "androidtv" -> out += Methode("Android TV par ADB : touche réveil", etape("androidtv.adb_command", entite, JSONObject().put("command", "WAKEUP")),
                "ou media_player.turn_on ; débogage ADB réseau activé sur la TV")
            "philips_js" -> out += Methode("Philips : allumage par Wake-on-LAN ou déclencheur « turn on »", mac?.takeIf { wol }?.let { etape("wake_on_lan.send_magic_packet", donnees = JSONObject().put("mac", it)) }, "les TV Philips récentes n'acceptent pas turn_on seul")
        }
        if (remote != null) out += Methode("Télécommande de l'appareil ($remote)", etape("remote.turn_on", remote), "fonctionne avec Android TV Remote, Apple TV, Harmony, Roku…")
        if (allumageDirect && out.none { it.etape?.optString("action") == "media_player.turn_on" })
            out += Methode("media_player.turn_on", etape("media_player.turn_on", entite), "l'entité annonce la fonction « allumer »")
        if ("hdmi_cec" in services) out += Methode("HDMI-CEC", etape("hdmi_cec.power_on"), "allume la TV par le câble HDMI (passerelle CEC)")
        if (mac != null && wol && out.none { it.etape?.optString("action") == "wake_on_lan.send_magic_packet" })
            out += Methode("Wake-on-LAN", etape("wake_on_lan.send_magic_packet", donnees = JSONObject().put("mac", mac)), "si la TV accepte le réveil par le réseau")
        if (prise != null) out += Methode("Prise connectée ($prise)", etape("switch.turn_on", prise), "remet le courant ; la TV peut rester en veille ensuite")
        if (out.isEmpty()) out += Methode("Aucune méthode sûre détectée", null,
            "l'entité n'annonce pas « allumer » (intégration « $plateforme ») : chercher la méthode propre à cette intégration (Wake-on-LAN, télécommande IR/Broadlink, CEC, SmartThings…)")
        return out
    }

    // ------------------------------------------------------------------ tout appareil, tout objectif (poêle, volet…)

    private val FONCTIONS_CLIMAT = listOf(1 to "consigne", 2 to "plage de consigne", 4 to "humidité", 8 to "modes ventilation/puissance",
        16 to "préréglages", 32 to "oscillation", 128 to "éteindre", 256 to "allumer")

    /** Fonctions d'un climate (poêle, clim, thermostat) d'après supported_features. */
    fun fonctionsClimat(features: Int): List<String> = FONCTIONS_CLIMAT.filter { (bit, _) -> features and bit != 0 }.map { it.second }

    data class Objectif(val type: String, val valeur: String = "")

    private fun sansAccents(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    /** « allumer », « éteins », « mode eco », « consigne 21 », « puissance 3 », « ouvrir à 50 % » → objectif. */
    fun objectif(texte: String): Objectif {
        val t = sansAccents(texte)
        val nombre = Regex("(\\d+(?:[.,]\\d+)?)").find(t)?.groupValues?.get(1)?.replace(',', '.').orEmpty()
        return when {
            Regex("\\b(eteindre|eteins|eteint|arret|arreter|stop|couper|coupe|off)\\b").containsMatchIn(t) -> Objectif("eteindre")
            Regex("\\b(consigne|temperature|degres?|°)").containsMatchIn(t) && nombre.isNotBlank() -> Objectif("consigne", nombre)
            Regex("\\b(puissance|niveau|vitesse|flamme|power|level)\\b").containsMatchIn(t) -> Objectif("puissance", nombre.ifBlank { Regex("\\b(?:puissance|niveau|vitesse|flamme)\\s+(\\w+)").find(t)?.groupValues?.get(1).orEmpty() })
            Regex("\\bmode\\s+(\\S+)").containsMatchIn(t) -> Objectif("mode", Regex("\\bmode\\s+(\\S+)").find(t)!!.groupValues[1])
            Regex("\\b(ouvr\\w*|monte\\w*)\\b").containsMatchIn(t) -> Objectif("ouvrir", nombre)
            Regex("\\b(ferm\\w*|descend\\w*|baisse\\w*)\\b").containsMatchIn(t) -> Objectif("fermer", nombre)
            Regex("\\b(allum\\w*|demarr\\w*|marche|lance\\w*|on)\\b").containsMatchIn(t) -> Objectif("allumer")
            else -> Objectif("autre", t)
        }
    }

    private fun liste(a: JSONArray?): List<String> = a?.let { x -> (0 until x.length()).map { x.optString(it) } }.orEmpty()

    /** L'option de [options] qui correspond le mieux à [voulu] (« eco » → « Eco », « ECO mode »). */
    internal fun option(options: List<String>, voulu: String): String? {
        val v = sansAccents(voulu)
        if (v.isBlank()) return null
        return options.firstOrNull { sansAccents(it) == v } ?: options.firstOrNull { sansAccents(it).contains(v) || v.contains(sansAccents(it)) && sansAccents(it).length >= 2 }
    }

    /**
     * Façons d'atteindre [obj] sur l'appareil [entite] (état [etat]), d'après ce qu'il annonce et les entités du même
     * appareil ([freres] : entité → état complet) — poêle à granulés (préréglages, « fan modes » utilisés comme puissance,
     * interrupteur d'alimentation, sélecteur de mode, nombre de consigne), volet, chauffe-eau, etc.
     */
    fun methodesPour(obj: Objectif, entite: String, etat: JSONObject, freres: Map<String, JSONObject>, services: Set<String>, mac: String?, plateforme: String): List<Methode> {
        val domaine = entite.substringBefore('.')
        val at = etat.optJSONObject("attributes") ?: JSONObject()
        val f = at.optInt("supported_features")
        if (domaine == "media_player" && obj.type == "allumer") return methodesAllumage(entite, plateforme, f, freres.keys.toList(), services, mac)
        val out = mutableListOf<Methode>()
        fun nomFrere(id: String) = sansAccents(id + " " + freres[id]?.optJSONObject("attributes")?.optString("friendly_name").orEmpty())
        val interrupteur = freres.keys.firstOrNull { it.startsWith("switch.") && Regex("power|alim|marche|on_off|allum|etat|state|poele|stove").containsMatchIn(nomFrere(it)) }
            ?: freres.keys.firstOrNull { it.startsWith("switch.") }
        val selecteurs = freres.keys.filter { it.startsWith("select.") || it.startsWith("input_select.") }
        val nombres = freres.keys.filter { it.startsWith("number.") || it.startsWith("input_number.") }
        val boutons = freres.keys.filter { it.startsWith("button.") }
        fun choixSelecteur(voulu: String): Methode? = selecteurs.firstNotNullOfOrNull { id ->
            option(liste(freres[id]?.optJSONObject("attributes")?.optJSONArray("options")), voulu)?.let { o ->
                Methode("Sélecteur $id → « $o »", etape(if (id.startsWith("input_select.")) "input_select.select_option" else "select.select_option", id, JSONObject().put("option", o)), "option proposée par l'appareil")
            }
        }
        when (domaine) {
            "climate", "water_heater" -> {
                val modes = liste(at.optJSONArray("hvac_modes")).ifEmpty { liste(at.optJSONArray("operation_list")) }
                val presets = liste(at.optJSONArray("preset_modes"))
                val ventil = liste(at.optJSONArray("fan_modes"))
                when (obj.type) {
                    "allumer" -> {
                        if (f and 256 != 0) out += Methode("$domaine.turn_on", etape("$domaine.turn_on", entite), "l'appareil annonce « allumer »")
                        listOf("heat", "auto", "heat_cool").firstOrNull { it in modes }?.let { m -> out += Methode("Mode « $m »", etape("climate.set_hvac_mode", entite, JSONObject().put("hvac_mode", m)), "modes proposés : ${modes.joinToString()}") }
                        interrupteur?.let { out += Methode("Interrupteur $it", etape("switch.turn_on", it), "alimentation / marche de l'appareil") }
                        boutons.firstOrNull { Regex("allum|on|start|demarr|marche").containsMatchIn(nomFrere(it)) }?.let { out += Methode("Bouton $it", etape("button.press", it), "bouton d'allumage de l'appareil") }
                        choixSelecteur("on")?.let { out += it }; choixSelecteur("marche")?.let { out += it }
                    }
                    "eteindre" -> {
                        if (f and 128 != 0) out += Methode("$domaine.turn_off", etape("$domaine.turn_off", entite), "l'appareil annonce « éteindre »")
                        if ("off" in modes) out += Methode("Mode « off »", etape("climate.set_hvac_mode", entite, JSONObject().put("hvac_mode", "off")), "un poêle à granulés peut mettre plusieurs minutes à s'arrêter (phase d'extinction)")
                        interrupteur?.let { out += Methode("Interrupteur $it", etape("switch.turn_off", it), "coupe la marche de l'appareil") }
                        boutons.firstOrNull { Regex("eteind|off|stop|arret").containsMatchIn(nomFrere(it)) }?.let { out += Methode("Bouton $it", etape("button.press", it), "bouton d'extinction de l'appareil") }
                        choixSelecteur("off")?.let { out += it }; choixSelecteur("arret")?.let { out += it }
                    }
                    "consigne" -> {
                        val v = obj.valeur.toDoubleOrNull()
                        if (v != null && f and 1 != 0) out += Methode("Consigne $v°", etape("climate.set_temperature", entite, JSONObject().put("temperature", v)),
                            "plage ${at.optDouble("min_temp", 7.0)}–${at.optDouble("max_temp", 35.0)}°, pas ${at.optDouble("target_temp_step", 0.5)}")
                        nombres.firstOrNull { Regex("consigne|setpoint|target|temp").containsMatchIn(nomFrere(it)) }?.let { id ->
                            if (v != null) out += Methode("Nombre $id = $v", etape(if (id.startsWith("input_number.")) "input_number.set_value" else "number.set_value", id, JSONObject().put("value", v)), "consigne exposée à part par l'intégration")
                        }
                    }
                    "mode", "puissance" -> {
                        val voulu = obj.valeur
                        option(presets, voulu)?.let { out += Methode("Préréglage « $it »", etape("climate.set_preset_mode", entite, JSONObject().put("preset_mode", it)), "préréglages : ${presets.joinToString()}") }
                        option(ventil, voulu)?.let { out += Methode("Mode ventilation/puissance « $it »", etape("climate.set_fan_mode", entite, JSONObject().put("fan_mode", it)), "beaucoup de poêles exposent leur puissance ainsi : ${ventil.joinToString()}") }
                        option(modes, voulu)?.let { out += Methode("Mode « $it »", etape("climate.set_hvac_mode", entite, JSONObject().put("hvac_mode", it)), "modes : ${modes.joinToString()}") }
                        choixSelecteur(voulu)?.let { out += it }
                        if (obj.type == "puissance") nombres.firstOrNull { Regex("puissance|power|niveau|level|flamme").containsMatchIn(nomFrere(it)) }?.let { id ->
                            voulu.toDoubleOrNull()?.let { v -> out += Methode("Nombre $id = $v", etape(if (id.startsWith("input_number.")) "input_number.set_value" else "number.set_value", id, JSONObject().put("value", v)),
                                "plage ${freres[id]?.optJSONObject("attributes")?.optDouble("min")}–${freres[id]?.optJSONObject("attributes")?.optDouble("max")}") }
                        }
                        if (out.isEmpty()) out += Methode("« $voulu » n'existe pas sur cet appareil", null,
                            "préréglages : ${presets.joinToString().ifBlank { "aucun" }} ; ventilation/puissance : ${ventil.joinToString().ifBlank { "aucune" }} ; modes : ${modes.joinToString().ifBlank { "aucun" }}" +
                                (if (selecteurs.isNotEmpty()) " ; sélecteurs : " + selecteurs.joinToString { s -> "$s (${liste(freres[s]?.optJSONObject("attributes")?.optJSONArray("options")).joinToString("/")})" } else ""))
                    }
                }
            }
            "cover" -> when (obj.type) {
                "ouvrir", "fermer" -> {
                    val pos = obj.valeur.toIntOrNull()
                    if (pos != null && f and 4 != 0) out += Methode("Position $pos %", etape("cover.set_cover_position", entite, JSONObject().put("position", pos)), "le volet accepte une position")
                    out += Methode(if (obj.type == "ouvrir") "cover.open_cover" else "cover.close_cover", etape(if (obj.type == "ouvrir") "cover.open_cover" else "cover.close_cover", entite), "service standard")
                }
                else -> {}
            }
            else -> {}
        }
        if (out.isEmpty()) when (obj.type) {
            "allumer", "eteindre" -> out += Methode("$domaine.${if (obj.type == "allumer") "turn_on" else "turn_off"}", etape("$domaine.${if (obj.type == "allumer") "turn_on" else "turn_off"}", entite), "service standard de l'entité")
            else -> out += Methode("Aucune méthode sûre détectée", null, "voir les services de l'intégration « $plateforme » et les entités du même appareil ; sinon chercher sur le web")
        }
        return out.distinctBy { it.etape?.toString() ?: it.titre }
    }
}
