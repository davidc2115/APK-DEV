package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONArray
import org.json.JSONObject

/**
 * La maison en direct par Home Assistant, sans IA ni Hermès (en plus de [DomotiqueDirecte], qui allume / éteint /
 * ouvre / ferme) :
 *  - télé : chaîne par numéro ou par nom (« mets TF1 », « passe sur la 5 »), chaîne suivante / précédente, volume
 *    (plus fort, moins fort, « à 20 »), sourdine, pause / lecture, source ou application (« mets Netflix sur la télé ») ;
 *  - lumières : luminosité (« mets la lumière du salon à 40 % », « baisse la lumière ») ;
 *  - scènes et scripts (« active la scène cinéma »).
 * Repli quand Home Assistant ne sait pas faire : connexion directe à la télé (ADB), si elle est configurée.
 */
object MaisonDirecte {

    sealed class Media {
        data class Chaine(val numero: Int, val nom: String?) : Media()
        data class Zapping(val suivante: Boolean) : Media()
        data class Volume(val sens: Int, val fois: Int) : Media()
        data class VolumeA(val pct: Int) : Media()
        data class Muet(val muet: Boolean) : Media()
        data class Lecture(val pause: Boolean) : Media()
        data class Source(val nom: String) : Media()
    }

    data class Demande(val media: Media, val allumer: Boolean, val mots: List<String>)

    // ------------------------------------------------------------------ chaînes de la TNT (numérotation du 6 juin 2025)

    internal val CHAINES: Map<String, Int> = linkedMapOf(
        "tf1" to 1, "tf 1" to 1, "france 2" to 2, "france 3" to 3, "france 4" to 4, "france 5" to 5, "m6" to 6, "m 6" to 6,
        "arte" to 7, "lcp" to 8, "public senat" to 8, "lcp public senat" to 8, "w9" to 9, "w 9" to 9, "tmc" to 10, "tfx" to 11,
        "gulli" to 12, "bfm" to 13, "bfm tv" to 13, "bfmtv" to 13, "cnews" to 14, "c news" to 14, "lci" to 15,
        "franceinfo" to 16, "france info" to 16, "cstar" to 17, "c star" to 17, "t18" to 18, "t 18" to 18, "novo19" to 19,
        "novo 19" to 19, "novo" to 19, "tf1 series films" to 20, "tf1 series film" to 20, "tf 1 series films" to 20,
        "l equipe" to 21, "la chaine l equipe" to 21, "6ter" to 22, "6 ter" to 22, "sixter" to 22, "rmc story" to 23,
        "rmc decouverte" to 24, "cherie 25" to 25, "rmc life" to 25, "paris premiere" to 26,
    )
    private val NOMS_CHAINES = mapOf(1 to "TF1", 2 to "France 2", 3 to "France 3", 4 to "France 4", 5 to "France 5", 6 to "M6", 7 to "Arte",
        8 to "LCP-Public Sénat", 9 to "W9", 10 to "TMC", 11 to "TFX", 12 to "Gulli", 13 to "BFM TV", 14 to "CNews", 15 to "LCI", 16 to "franceinfo",
        17 to "CStar", 18 to "T18", 19 to "Novo19", 20 to "TF1 Séries Films", 21 to "L'Équipe", 22 to "6ter", 23 to "RMC Story",
        24 to "RMC Découverte", 25 to "RMC Life", 26 to "Paris Première")

    /** Chaîne nommée dans la phrase (le nom le plus long l'emporte : « tf1 series films » avant « tf1 »). */
    internal fun chaineNommee(n: String): Pair<Int, String>? =
        CHAINES.keys.sortedByDescending { it.length }.firstOrNull { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(n) }
            ?.let { CHAINES.getValue(it) to (NOMS_CHAINES[CHAINES.getValue(it)] ?: it) }

    // ------------------------------------------------------------------ nombres dits en lettres

    private val UNITES = mapOf("zero" to 0, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9,
        "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16)
    private val DIZAINES = mapOf("vingt" to 20, "vingts" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50, "soixante" to 60)

    /** « mets la chaîne vingt trois », « france deux », « mets la une », « volume à cinquante » → chiffres. */
    internal fun chiffres(n: String): String {
        val t = n.split(' ').filter { it.isNotBlank() }
        val out = ArrayList<String>()
        var i = 0
        fun mot(k: Int) = t.getOrNull(k)
        fun unite(k: Int, avecUn: Boolean): Int? = when (val m = mot(k)) { "un", "une" -> if (avecUn) 1 else null; null -> null; else -> UNITES[m] }
        while (i < t.size) {
            val m = t[i]
            var v: Int? = null
            var lus = 1
            when {
                m == "cent" -> { v = 100 }
                m == "quatre" && (mot(i + 1) == "vingt" || mot(i + 1) == "vingts") -> {
                    v = 80; lus = 2
                    val u = unite(i + 2, true)
                    if (u != null && u in 1..16) {
                        v = 80 + u; lus = 3
                        if (u == 10 && mot(i + 3) in setOf("sept", "huit", "neuf")) { v = 90 + UNITES.getValue(mot(i + 3)!!); lus = 4 }
                    }
                }
                m in DIZAINES -> {
                    v = DIZAINES.getValue(m)
                    if (mot(i + 1) == "et" && mot(i + 2) in setOf("un", "une", "onze")) { v += if (mot(i + 2) == "onze") 11 else 1; lus = 3 }
                    else unite(i + 1, false)?.let { u ->
                        if (v == 60 && u in 10..16) {
                            v = 60 + u; lus = 2
                            if (u == 10 && mot(i + 2) in setOf("sept", "huit", "neuf")) { v = 70 + UNITES.getValue(mot(i + 2)!!); lus = 3 }
                        } else if (u in 1..9) { v += u; lus = 2 }
                    }
                }
                m == "dix" && mot(i + 1) in setOf("sept", "huit", "neuf") -> { v = 10 + UNITES.getValue(mot(i + 1)!!); lus = 2 }
                m in UNITES -> v = UNITES.getValue(m)
                // « un » / « une » : seulement comme numéro (« mets la une », « chaîne un »), pas comme article.
                (m == "un" || m == "une") && (out.lastOrNull() in setOf("la", "chaine", "numero", "a", "sur", "le") && (i + 1 >= t.size || mot(i + 1) in setOf("sur", "de", "stp", "jarvis", "merci", "pourcent", "pour"))) -> v = 1
            }
            if (v != null) { out += v.toString(); i += lus } else { out += m; i++ }
        }
        return out.joinToString(" ")
    }

    // ------------------------------------------------------------------ compréhension de la phrase

    private val PREFIXE = Regex("^(?:jarvis|ok|alors|bon|tu peux|peux tu|est ce que tu peux|tu pourrais|pourrais tu|s il te plait|stp|svp|vas y|vas y et)\\s+")
    private val FIN = Regex("\\s+(?:s il te plait|stp|svp|merci|jarvis|maintenant|tout de suite)$")
    private val TELE = Regex("\\b(tele|tv|television|televiseur|chaine|chaines)\\b")
    /** Ce qui n'est pas un ordre immédiat à la télé : à laisser au cerveau. */
    private val EXCLU = Regex("\\b(enregistre|programme|programmes|replay|ce soir|demain|minutes?|heures?|quand|quel|quelle|quels|quoi|combien|qu est|cherche|trouve|" +
        "film|serie|episode|musique|chanson|playlist|youtube music|ma chaine|chaine youtube|chaine tiktok|chaine twitch|chaine discord|telephone|portable|smartphone|" +
        "reglages?|parametres?|image|luminosite|contraste|capture|pourquoi|comment)\\b")
    private val VERBE_CHAINE = Regex("\\b(mets|met|remets|passe|repasse|change|zappe|va|bascule|allume|lance|regarde|regarder|affiche|ouvre|branche)\\b")
    private val CHAINE_NUM = Regex("\\bchaine (?:numero |n )?(\\d{1,3})\\b")
    private val LA_N = Regex("\\b(?:mets|met|remets|passe|repasse|va|bascule|zappe)(?: sur)? la (\\d{1,3})\\b")
    private val ZAP_PLUS = Regex("\\bchaine (?:suivante|d apres|plus|superieure)\\b|\\bzappe\\b|\\bzapper\\b|\\bchange de chaine\\b|\\bmonte d une chaine\\b")
    private val ZAP_MOINS = Regex("\\bchaine (?:precedente|d avant|moins|inferieure)\\b|\\b(?:reviens|retourne|repasse) (?:a|sur) la chaine (?:precedente|d avant)\\b|\\bbaisse d une chaine\\b")
    private val VOLUME_A = Regex("\\b(?:volume|son)\\b.{0,25}?\\b(?:a|sur) (\\d{1,3})\\b")
    private val VOL_PLUS = Regex("\\b(?:monte|augmente|hausse|remonte|pousse)\\b.{0,16}\\b(?:son|volume)\\b|\\bplus fort\\b")
    private val VOL_MOINS = Regex("\\b(?:baisse|diminue|descends|reduis|rabaisse)\\b.{0,16}\\b(?:son|volume)\\b|\\bmoins fort\\b")
    private val DEMUET = Regex("\\b(?:remets|reactive|rallume|retablis|remet) (?:le )?son\\b|\\b(?:enleve|retire|desactive|coupe|arrete) (?:la )?sourdine\\b|\\bdemute\\b")
    private val MUET = Regex("\\b(?:coupe|couper|enleve|retire) (?:le )?son\\b|\\bsourdine\\b|\\bmute\\b|\\bmuet\\b")
    private val PAUSE = Regex("\\bpause\\b")
    private val PLAY = Regex("\\b(?:reprends|reprend|relance la lecture|remets en lecture|lecture|play|continue la lecture)\\b")
    private val HDMI = Regex("\\bhdmi ?(\\d)\\b")
    private val APPAREIL_SOURCE = Regex("\\b(freebox|box|decodeur|console|playstation|ps5|ps4|xbox|switch|chromecast|apple tv|fire tv|shield)\\b")
    private val APPLI = Regex("\\b(netflix|youtube|prime video|amazon prime|disney plus|disney|mycanal|canal plus|plex|spotify|molotov|france tv|twitch|deezer|arte tv)\\b")
    private val VERBE_SOURCE = Regex("\\b(mets|met|remets|lance|ouvre|passe|bascule|affiche|va)\\b")
    private val ALLUME = Regex("\\b(allume|rallume|demarre)\\b")

    /** Mots qui ne désignent pas l'appareil (verbes, articles, chaînes, nombres…) : le reste sert à choisir la télé (« salon »). */
    private val PAS_CIBLE = setOf("mets", "met", "remets", "passe", "repasse", "change", "zappe", "zapper", "va", "bascule", "allume", "rallume", "lance", "regarde",
        "regarder", "affiche", "ouvre", "branche", "monte", "augmente", "hausse", "remonte", "pousse", "baisse", "diminue", "descends", "reduis", "coupe", "couper",
        "enleve", "retire", "reactive", "retablis", "desactive", "arrete", "reprends", "reprend", "relance", "continue", "le", "la", "les", "l", "de", "du", "des",
        "d", "un", "une", "sur", "a", "au", "aux", "en", "dans", "et", "son", "volume", "chaine", "chaines", "tele", "tv", "television", "televiseur", "suivante",
        "precedente", "apres", "avant", "plus", "moins", "fort", "peu", "beaucoup", "sourdine", "muet", "mute", "pause", "lecture", "play", "source", "entree",
        "numero", "pourcent", "pour", "cent", "crans", "points", "mon", "ma", "mes", "ton", "ta", "sa", "jarvis", "stp", "svp", "merci", "demute", "fond", "cran")

    internal fun analyser(texte: String): Demande? {
        if (texte.trim().endsWith("?")) return null
        var n = chiffres(normaliser(texte))
        repeat(2) { n = n.replace(PREFIXE, "").replace(FIN, "").trim() }
        if (n.isBlank() || n.split(' ').size > 14 || EXCLU.containsMatchIn(n)) return null
        val tele = TELE.containsMatchIn(n)
        val nommee = chaineNommee(n)
        val media: Media = when {
            CHAINE_NUM.find(n) != null -> CHAINE_NUM.find(n)!!.groupValues[1].toInt().let { Media.Chaine(it, NOMS_CHAINES[it]) }
            LA_N.find(n) != null && !Regex("\\b(?:volume|son)\\b").containsMatchIn(n) -> LA_N.find(n)!!.groupValues[1].toInt().let { Media.Chaine(it, NOMS_CHAINES[it]) }
            nommee != null && (VERBE_CHAINE.containsMatchIn(n) || CHAINES.containsKey(n)) ->
                Media.Chaine(nommee.first, nommee.second)
            ZAP_MOINS.containsMatchIn(n) -> Media.Zapping(false)
            ZAP_PLUS.containsMatchIn(n) -> Media.Zapping(true)
            !tele -> return analyserSource(n)
            VOLUME_A.find(n) != null -> Media.VolumeA(VOLUME_A.find(n)!!.groupValues[1].toInt().coerceIn(0, 100))
            VOL_PLUS.containsMatchIn(n) -> Media.Volume(1, fois(n))
            VOL_MOINS.containsMatchIn(n) -> Media.Volume(-1, fois(n))
            DEMUET.containsMatchIn(n) -> Media.Muet(false)
            MUET.containsMatchIn(n) -> Media.Muet(true)
            PAUSE.containsMatchIn(n) -> Media.Lecture(true)
            PLAY.containsMatchIn(n) -> Media.Lecture(false)
            else -> return analyserSource(n)
        }
        return Demande(media, ALLUME.containsMatchIn(n) && media is Media.Chaine, mots(n))
    }

    /** « mets HDMI 2 », « passe sur la Freebox sur la télé », « lance Netflix sur la télé ». */
    private fun analyserSource(n: String): Demande? {
        if (!VERBE_SOURCE.containsMatchIn(n)) return null
        HDMI.find(n)?.let { return Demande(Media.Source("hdmi ${it.groupValues[1]}"), false, mots(n)) }
        if (!TELE.containsMatchIn(n)) return null
        APPLI.find(n)?.let { return Demande(Media.Source(it.groupValues[1]), false, mots(n)) }
        APPAREIL_SOURCE.find(n)?.let { return Demande(Media.Source(it.groupValues[1]), false, mots(n)) }
        return null
    }

    private fun fois(n: String): Int {
        Regex("\\bde (\\d{1,2})\\b").find(n)?.let { return it.groupValues[1].toInt().coerceIn(1, 30) }
        return when {
            Regex("\\b(un peu|legerement|un cran|d un cran)\\b").containsMatchIn(n) -> 2
            Regex("\\b(beaucoup|a fond|bien|nettement)\\b").containsMatchIn(n) -> 8
            else -> 4
        }
    }

    private fun mots(n: String): List<String> {
        val chaines = CHAINES.keys.flatMap { it.split(' ') }.toSet()
        return n.split(' ').filter { it.length >= 3 && it !in PAS_CIBLE && it !in chaines && !it.all(Char::isDigit) && APPLI.find(it) == null }
    }

    // ------------------------------------------------------------------ appareils de Home Assistant

    private class Ent(val id: String, val nom: String, val etat: String, val attrs: JSONObject) {
        val texte = " " + normaliser(id.substringAfter('.').replace('_', ' ') + " " + nom) + " "
        val features = attrs.optLong("supported_features", 0)
        fun gere(bit: Long) = features and bit != 0L
        val actif: Boolean get() = etat !in setOf("off", "unavailable", "unknown", "standby")
        val cast: Boolean get() = Regex("cast|chromecast|nest|google home").containsMatchIn(texte)
    }

    private const val PAUSE_F = 1L; private const val VOLUME_SET = 4L; private const val VOLUME_MUTE = 8L; private const val PREV = 16L; private const val NEXT = 32L
    private const val TURN_ON = 128L; private const val PLAY_MEDIA = 512L; private const val VOLUME_STEP = 1024L; private const val SELECT_SOURCE = 2048L; private const val PLAY_F = 16384L

    private val TELE_NOM = Regex("\\b(tele|tv|television|televiseur|bravia|sony|samsung|lg|webos|philips|android|google tv|freebox|player|shield|decodeur|oled|qled|tcl|hisense|xiaomi|mi box|chromecast)\\b|tv\\b|tele")
    private val PAS_TELE = Regex("spotify|plex|sonos|speaker|enceinte|echo|alexa|homepod|music|musique|radio|nest mini|home mini")

    /** Télés (lecteurs multimédia) de Home Assistant, de la plus adaptée à la moins adaptée pour cette demande. */
    private fun teles(etats: List<JSONObject>, d: Demande): List<Ent> {
        val tous = etats.filter { it.optString("entity_id").startsWith("media_player.") }
            .map { Ent(it.optString("entity_id"), it.optJSONObject("attributes")?.optString("friendly_name").orEmpty(), it.optString("state"), it.optJSONObject("attributes") ?: JSONObject()) }
            .filter { it.etat != "unavailable" }
        var tv = tous.filter { (TELE_NOM.containsMatchIn(it.texte) || it.attrs.optString("device_class") == "tv") && !PAS_TELE.containsMatchIn(it.texte) }
        if (tv.isEmpty()) tv = tous.filter { !PAS_TELE.containsMatchIn(it.texte) }
        // Pièce ou nom dit dans la phrase (« de la chambre », « du salon ») : seulement les télés qui le portent.
        if (d.mots.isNotEmpty()) tv.filter { e -> d.mots.any { m -> e.texte.contains(" $m") || e.texte.contains(" ${m.removeSuffix("s")}") } }.takeIf { it.isNotEmpty() }?.let { tv = it }
        val bit = when (val m = d.media) {
            is Media.Chaine -> PLAY_MEDIA; is Media.Zapping -> if (m.suivante) NEXT else PREV; is Media.Volume -> VOLUME_STEP or VOLUME_SET
            is Media.VolumeA -> VOLUME_SET; is Media.Muet -> VOLUME_MUTE; is Media.Lecture -> if (m.pause) PAUSE_F else PLAY_F; is Media.Source -> SELECT_SOURCE or PLAY_MEDIA
        }
        return tv.sortedByDescending { e ->
            (if (e.features and bit != 0L) 10 else 0) + (if (e.actif) 4 else 0) + (if (e.attrs.has("source_list")) 2 else 0) - (if (e.cast && d.media !is Media.Volume && d.media !is Media.Lecture) 6 else 0)
        }
    }

    /** Télécommande de la même télé dans Home Assistant (remote.*), si elle existe. */
    private fun telecommande(etats: List<JSONObject>, e: Ent): String? {
        val objet = e.id.substringAfter('.')
        val remotes = etats.filter { it.optString("entity_id").startsWith("remote.") }
        remotes.firstOrNull { it.optString("entity_id").substringAfter('.') == objet }?.let { return it.optString("entity_id") }
        val nom = normaliser(e.nom)
        return remotes.firstOrNull { r -> val rn = normaliser(r.optJSONObject("attributes")?.optString("friendly_name").orEmpty()); rn.isNotBlank() && nom.isNotBlank() && (rn == nom || rn.contains(nom) || nom.contains(rn)) }
            ?.optString("entity_id")
    }

    /** Appel d'un service Home Assistant : null si réussi, sinon le message d'erreur. */
    private fun appel(app: JarvisApp, domaine: String, service: String, data: JSONObject): String? =
        try { Serveur.ha(app, "POST", "/api/services/$domaine/$service", data); null } catch (e: Exception) { e.message ?: e.javaClass.simpleName }

    private fun teleAdb(app: JarvisApp) = runCatching { Tele.configuree(app) && Tele.type(app) == "androidtv" }.getOrDefault(false)

    /** Exécute la demande télé ; null si rien n'a marché (le cerveau de JARVIS reprend, jamais Hermès). */
    suspend fun executer(app: JarvisApp, texte: String): String? {
        val d = analyser(texte) ?: return null
        val haOk = Serveur.haConfigure(app)
        val etats = if (!haOk) emptyList() else runCatching {
            val arr = JSONArray(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Serveur.ha(app, "GET", "/api/states") })
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }.getOrDefault(emptyList())
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val candidats = teles(etats, d).take(3)
            for (e in candidats) faire(app, etats, e, d)?.let { return@withContext "🏠 📺 ${e.nom.ifBlank { e.id }} : $it" }
            // Home Assistant n'a pas su (ou n'est pas joignable) : connexion directe à la télé.
            if (teleAdb(app)) adb(app, d)?.let { return@withContext "🏠 📺 Télé (connexion directe) : $it" }
            null
        }
    }

    private fun faire(app: JarvisApp, etats: List<JSONObject>, e: Ent, d: Demande): String? {
        val id = JSONObject().put("entity_id", e.id)
        fun data(vararg p: Pair<String, Any>) = JSONObject(id.toString()).also { o -> p.forEach { o.put(it.first, it.second) } }
        val remote = telecommande(etats, e)
        // Télé éteinte et une chaîne demandée : on l'allume d'abord, le temps qu'elle démarre.
        if ((d.allumer || (d.media is Media.Chaine && !e.actif)) && e.etat in setOf("off", "standby") && (e.gere(TURN_ON) || remote != null)) {
            val ok = (if (e.gere(TURN_ON)) appel(app, "media_player", "turn_on", data()) else appel(app, "remote", "turn_on", JSONObject().put("entity_id", remote))) == null
            if (ok) Thread.sleep(6000)
        }
        return when (val m = d.media) {
            is Media.Chaine -> {
                val libelle = "chaîne ${m.numero}" + (m.nom?.let { " ($it)" } ?: "") + "."
                if (e.gere(PLAY_MEDIA) && appel(app, "media_player", "play_media", data("media_content_type" to "channel", "media_content_id" to m.numero.toString())) == null) libelle
                else if (remote != null && appel(app, "remote", "send_command", JSONObject().put("entity_id", remote).put("command", JSONArray(m.numero.toString().map { it.toString() }))) == null) libelle
                else null
            }
            is Media.Zapping -> {
                val libelle = if (m.suivante) "chaîne suivante." else "chaîne précédente."
                when {
                    remote != null && listOf(if (m.suivante) "CHANNEL_UP" else "CHANNEL_DOWN", if (m.suivante) "ChannelUp" else "ChannelDown")
                        .any { c -> appel(app, "remote", "send_command", JSONObject().put("entity_id", remote).put("command", c)) == null } -> libelle
                    e.gere(if (m.suivante) NEXT else PREV) && appel(app, "media_player", if (m.suivante) "media_next_track" else "media_previous_track", data()) == null -> libelle
                    else -> null
                }
            }
            is Media.Volume -> {
                val libelle = (if (m.sens > 0) "volume monté" else "volume baissé") + " (${m.fois} cran${if (m.fois > 1) "s" else ""})."
                val service = if (m.sens > 0) "volume_up" else "volume_down"
                val niveau = e.attrs.optDouble("volume_level", Double.NaN)
                when {
                    e.gere(VOLUME_STEP) && (1..m.fois).all { appel(app, "media_player", service, data()) == null } -> libelle
                    // Sans « pas » propre à la télé, Home Assistant monterait de 10 % par appui : niveau réglé directement (2 % par cran).
                    e.gere(VOLUME_SET) && !niveau.isNaN() && appel(app, "media_player", "volume_set", data("volume_level" to (niveau + m.sens * m.fois * 0.02).coerceIn(0.0, 1.0))) == null -> libelle
                    remote != null && appel(app, "remote", "send_command", JSONObject().put("entity_id", remote).put("command", if (m.sens > 0) "VOLUME_UP" else "VOLUME_DOWN").put("num_repeats", m.fois)) == null -> libelle
                    else -> null
                }
            }
            is Media.VolumeA -> if (e.gere(VOLUME_SET) && appel(app, "media_player", "volume_set", data("volume_level" to m.pct / 100.0)) == null) "volume à ${m.pct} %." else null
            is Media.Muet -> when {
                e.gere(VOLUME_MUTE) && appel(app, "media_player", "volume_mute", data("is_volume_muted" to m.muet)) == null -> if (m.muet) "son coupé." else "son remis."
                remote != null && appel(app, "remote", "send_command", JSONObject().put("entity_id", remote).put("command", "MUTE")) == null -> if (m.muet) "son coupé." else "son remis."
                else -> null
            }
            is Media.Lecture -> {
                val service = if (m.pause) "media_pause" else "media_play"
                if (e.gere(if (m.pause) PAUSE_F else PLAY_F) && appel(app, "media_player", service, data()) == null) (if (m.pause) "en pause." else "lecture reprise.") else null
            }
            is Media.Source -> {
                val voulu = normaliser(m.nom).replace(" ", "")
                val liste = e.attrs.optJSONArray("source_list")
                val source = liste?.let { l -> (0 until l.length()).map { l.optString(it) }.firstOrNull { s -> normaliser(s).replace(" ", "").let { it.contains(voulu) || (voulu.length >= 4 && voulu.contains(it) && it.length >= 4) } } }
                val paquet = Tele.APPLIS_CONNUES.entries.firstOrNull { normaliser(m.nom).contains(it.key) || it.key.contains(normaliser(m.nom)) }?.value
                when {
                    source != null && e.gere(SELECT_SOURCE) && appel(app, "media_player", "select_source", data("source" to source)) == null -> "$source."
                    paquet != null && e.gere(PLAY_MEDIA) && !e.cast && appel(app, "media_player", "play_media", data("media_content_type" to "app", "media_content_id" to paquet)) == null -> "${m.nom} lancé."
                    else -> null
                }
            }
        }
    }

    /** Repli : la télé Android pilotée directement (ADB). */
    private fun adb(app: JarvisApp, d: Demande): String? = runCatching {
        when (val m = d.media) {
            is Media.Chaine -> Tele.chiffres(app, m.numero).let { "chaîne ${m.numero}" + (m.nom?.let { n -> " ($n)" } ?: "") + "." }
            is Media.Zapping -> Tele.touche(app, if (m.suivante) "chaine_plus" else "chaine_moins").let { if (m.suivante) "chaîne suivante." else "chaîne précédente." }
            is Media.Volume -> Tele.touche(app, if (m.sens > 0) "volume_plus" else "volume_moins", m.fois).let { if (m.sens > 0) "volume monté." else "volume baissé." }
            is Media.Muet -> Tele.touche(app, "muet").let { if (m.muet) "son coupé." else "son remis." }
            is Media.Lecture -> Tele.touche(app, "lecture_pause").let { if (m.pause) "en pause." else "lecture reprise." }
            is Media.Source -> Tele.ouvrirAppli(app, m.nom).takeIf { !it.startsWith("Application") }?.let { "${m.nom} lancé." }
            is Media.VolumeA -> null
        }
    }.getOrNull()

    // ------------------------------------------------------------------ lumières (luminosité) et scènes

    private const val LUM = "(lumieres?|lampes?|eclairages?|plafonniers?|spots?|leds?|lustres?|guirlandes?|appliques?|veilleuses?|ampoules?)"
    private val LUM_PCT = Regex("^(?:mets|met|regle|passe|baisse|monte|augmente|diminue|tamise)\\s+(?:la |les |l |le )?$LUM(.*?)\\s+(?:a|sur) (\\d{1,3})(?: pourcent| pour cent)?$")
    private val LUM_VAR = Regex("^(baisse|diminue|tamise|monte|augmente|remonte)\\s+(?:la |les |l |le )?$LUM(.*)$")
    private val SCENE = Regex("^(?:active|lance|mets|met|declenche|demarre|joue)\\s+(?:la |le |l )?(scene|ambiance|script|scenario)\\s+(.{2,40})$")

    internal sealed class Lumiere {
        data class Pourcent(val cible: String, val pct: Int) : Lumiere()
        data class Pas(val cible: String, val pas: Int) : Lumiere()
        data class Scene(val type: String, val nom: String) : Lumiere()
    }

    internal fun analyserLumiere(texte: String): Lumiere? {
        if (texte.trim().endsWith("?")) return null
        var n = chiffres(normaliser(texte))
        repeat(2) { n = n.replace(PREFIXE, "").replace(FIN, "").trim() }
        if (n.split(' ').size > 12 || Regex("\\b(demain|ce soir|minutes?|heures?|quand|si|apres|tous les|chaque)\\b").containsMatchIn(n)) return null
        LUM_PCT.matchEntire(n)?.let { return Lumiere.Pourcent((it.groupValues[1] + it.groupValues[2]).trim(), it.groupValues[3].toInt().coerceIn(1, 100)) }
        LUM_VAR.matchEntire(n)?.let { m ->
            if (Regex("\\d").containsMatchIn(m.groupValues[3])) return null
            return Lumiere.Pas((m.groupValues[2] + m.groupValues[3]).trim(), if (m.groupValues[1] in setOf("baisse", "diminue", "tamise")) -25 else 25)
        }
        SCENE.matchEntire(n)?.let { return Lumiere.Scene(if (it.groupValues[1] == "script") "script" else "scene", it.groupValues[2].trim()) }
        return null
    }

    suspend fun executerLumiere(app: JarvisApp, texte: String): String? {
        val l = analyserLumiere(texte) ?: return null
        if (!Serveur.haConfigure(app)) return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val arr = JSONArray(Serveur.ha(app, "GET", "/api/states"))
            val etats = (0 until arr.length()).map { arr.getJSONObject(it) }
            val paires = etats.map { it.optString("entity_id") to it.optJSONObject("attributes")?.optString("friendly_name").orEmpty() }
            when (l) {
                is Lumiere.Scene -> {
                    val doms = if (l.type == "script") listOf("script") else listOf("scene", "script")
                    val mots = l.nom.split(' ').filter { it.length >= 2 && it !in setOf("le", "la", "les", "de", "du", "des", "d", "l") }
                    val trouves = doms.firstNotNullOfOrNull { d ->
                        paires.filter { it.first.startsWith("$d.") }.filter { (id, nom) ->
                            val t = " " + normaliser(id.substringAfter('.').replace('_', ' ') + " " + nom) + " "
                            mots.all { m -> t.contains(" $m") || t.contains(" ${m.removeSuffix("s")}") }
                        }.takeIf { it.isNotEmpty() }
                    } ?: return@withContext null
                    if (trouves.size != 1) return@withContext null
                    val (id, nom) = trouves.first()
                    appel(app, id.substringBefore('.'), "turn_on", JSONObject().put("entity_id", id))?.let { return@withContext "🏠 ${nom.ifBlank { id }} : $it" }
                    "🏠 ${if (id.startsWith("script.")) "Script" else "Scène"} « ${nom.ifBlank { id }} » lancé${if (id.startsWith("scene.")) "e" else ""}."
                }
                is Lumiere.Pourcent, is Lumiere.Pas -> {
                    val cible = if (l is Lumiere.Pourcent) l.cible else (l as Lumiere.Pas).cible
                    val c = DomotiqueDirecte.Commande("on", cible.replace(Regex("^(le|la|les|l|mon|ma|mes)\\s+"), ""), listOf("light"),
                        Regex("^(les|tous les|toutes les)\\b|\\b(lumieres|lampes|spots|leds)\\b").containsMatchIn(cible))
                    val ids = DomotiqueDirecte.resoudre(app, c, paires) ?: return@withContext null
                    val data = if (l is Lumiere.Pourcent) JSONObject().put("brightness_pct", l.pct) else JSONObject().put("brightness_step_pct", (l as Lumiere.Pas).pas)
                    val res = ids.map { id -> id to appel(app, "light", "turn_on", JSONObject(data.toString()).put("entity_id", id)) }
                    val noms = res.filter { it.second == null }.joinToString(", ") { (id, _) -> paires.first { it.first == id }.second.ifBlank { id } }
                    if (noms.isBlank()) return@withContext "🏠 " + res.joinToString("\n") { "${it.first} : ${it.second}" }
                    "🏠 $noms : " + (if (l is Lumiere.Pourcent) "${l.pct} %." else if ((l as Lumiere.Pas).pas > 0) "plus lumineux." else "plus tamisé.")
                }
            }
        }
    }

    // ------------------------------------------------------------------ aiguillage : la maison ne passe jamais par Hermès

    private val MAISON = Regex(
        "t[ée]l[ée]\\b|\\btv\\b|t[ée]l[ée]vis|cha[iî]ne|lumi[èe]re|lampe|[ée]clairage|plafonnier|\\bspots?\\b|\\bvolets?\\b|\\bstores?\\b|rideaux?|" +
            "\\bprises?\\b(?! de)|interrupteur|chauffage|po[eê]le|\\bpoils?\\b|granul[ée]s|thermostat|radiateur|\\bclim|ventilateur|\\bvmc\\b|portail|" +
            "porte (du |de )?garage|aspirateur|enceinte|barre de son|sc[èe]ne|ambiance|home ?assistant|domotique|capteur|d[ée]tecteur|" +
            "temp[ée]rature (du|de la|dans|int[ée]rieure)|humidit[ée]|arrosage|piscine|hdmi|netflix sur|sur la t[ée]l[ée]",
        RegexOption.IGNORE_CASE
    )
    private val PAS_MAISON = Regex(
        "cha[iî]ne (youtube|tiktok|twitch|discord)|ma cha[iî]ne|serveur|\\bssh\\b|docker|conteneur|configuration|yaml|mise [àa] jour|install|red[ée]marr|" +
            "pourquoi|expliqu|comment (faire|fonctionne|marche)|conseil|compar|recherche sur|internet|acheter|prix",
        RegexOption.IGNORE_CASE
    )

    /** Demande qui concerne un appareil de la maison : traitée par JARVIS et Home Assistant, jamais confiée à Hermès. */
    fun concerne(texte: String): Boolean = MAISON.containsMatchIn(texte) && !PAS_MAISON.containsMatchIn(texte)
}
