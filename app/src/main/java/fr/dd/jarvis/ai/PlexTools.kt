package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Accès Plex Media Server via le tunnel SSH du serveur Debian (et plex.tv pour le compte). */
object Plex {
    private const val PORT = 32400
    private const val CLIENT_ID = "fr.dd.jarvis.plex"
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun token(app: JarvisApp): String = app.store.plexToken.value.trim().also {
        if (it.isBlank()) throw IllegalStateException("Jeton Plex manquant : Réglages › Maison › Serveur › Plex.")
    }

    private fun url(app: JarvisApp, path: String, query: Map<String, String> = emptyMap()): okhttp3.HttpUrl {
        val base = if (path.startsWith("https://")) path else "http://127.0.0.1:${Serveur.tunnel(app, PORT)}$path"
        val b = base.toHttpUrl().newBuilder()
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    /** Requête vers le serveur Plex (ou plex.tv si [path] est une adresse https complète). */
    fun request(app: JarvisApp, path: String, query: Map<String, String> = emptyMap(), targetClient: String? = null, methode: String = "GET"): String {
        val b = Request.Builder().url(url(app, path, query))
            .header("Accept", "application/json")
            .header("X-Plex-Token", token(app))
            .header("X-Plex-Client-Identifier", CLIENT_ID)
            .header("X-Plex-Product", "JARVIS")
            .header("X-Plex-Version", "1.0")
        targetClient?.let { b.header("X-Plex-Target-Client-Identifier", it) }
        when (methode.uppercase()) {
            "POST" -> b.post(ByteArray(0).toRequestBody(null))
            "PUT" -> b.put(ByteArray(0).toRequestBody(null))
            "DELETE" -> b.delete()
            else -> b.get()
        }
        http.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 401) throw IllegalStateException("Plex refuse le jeton (401). Vérifie le jeton dans les réglages Plex de JARVIS.")
            if (r.code == 403) throw IllegalStateException("Plex refuse l'accès (403) : droit manquant pour ce compte (ou suppression des médias non autorisée dans les réglages du serveur).")
            if (r.code == 404) throw IllegalStateException("Plex : élément introuvable (404).")
            if (!r.isSuccessful) throw IllegalStateException("Plex HTTP ${r.code} : ${body.take(250)}")
            return body
        }
    }

    /** MediaContainer d'une réponse (ou objet vide). */
    fun mc(app: JarvisApp, path: String, query: Map<String, String> = emptyMap()): JSONObject {
        val t = request(app, path, query)
        if (t.isBlank()) return JSONObject()
        val root = runCatching { JSONObject(t) }.getOrElse { return JSONObject().put("brut", t.take(2000)) }
        return root.optJSONObject("MediaContainer") ?: root
    }

    fun liste(o: JSONObject, cle: String = "Metadata"): List<JSONObject> = o.optJSONArray(cle)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

    fun serveur(app: JarvisApp): JSONObject = mc(app, "/")
    fun appareils(app: JarvisApp): JSONArray = mc(app, "/clients").let { it.optJSONArray("Device") ?: it.optJSONArray("Server") ?: JSONArray() }

    fun sections(app: JarvisApp): List<JSONObject> = liste(mc(app, "/library/sections"), "Directory")

    fun section(app: JarvisApp, nom: String): JSONObject? {
        val l = sections(app)
        if (nom.isBlank()) return null
        return l.firstOrNull { it.optString("title").equals(nom, true) || it.optString("key") == nom }
            ?: l.firstOrNull { it.optString("title").contains(nom, true) || nom.contains(it.optString("title"), true) }
            ?: when {
                Regex("film|movie", RegexOption.IGNORE_CASE).containsMatchIn(nom) -> l.firstOrNull { it.optString("type") == "movie" }
                Regex("s[ée]rie|show|t[ée]l[ée]", RegexOption.IGNORE_CASE).containsMatchIn(nom) -> l.firstOrNull { it.optString("type") == "show" }
                Regex("musique|music|artist", RegexOption.IGNORE_CASE).containsMatchIn(nom) -> l.firstOrNull { it.optString("type") == "artist" }
                else -> null
            }
    }

    fun rechercher(app: JarvisApp, texte: String, limite: Int = 15): List<JSONObject> {
        require(texte.isNotBlank()) { "Précise le titre à rechercher dans Plex." }
        val hubs = liste(mc(app, "/hubs/search", mapOf("query" to texte.trim(), "limit" to limite.coerceIn(1, 30).toString())), "Hub")
        val resultats = linkedMapOf<String, JSONObject>()
        for (h in hubs) for (item in liste(h)) {
            val key = item.optString("ratingKey").ifBlank { item.optString("key") }
            if (key.isNotBlank()) resultats.putIfAbsent(key, item)
        }
        return resultats.values.toList()
    }

    fun metadata(app: JarvisApp, id: String): JSONObject = liste(mc(app, "/library/metadata/$id")).firstOrNull()
        ?: throw IllegalStateException("Média Plex $id introuvable.")

    /** Média désigné par identifiant (ratingKey) ou par titre (le plus proche). */
    fun media(app: JarvisApp, titreOuId: String): JSONObject {
        val t = titreOuId.trim()
        if (t.matches(Regex("\\d+"))) return metadata(app, t)
        val items = rechercher(app, t).filter { it.optString("type") in setOf("movie", "show", "episode", "season", "artist", "album", "track", "collection") }
        return items.firstOrNull { it.optString("title").equals(t, true) } ?: items.firstOrNull()
            ?: throw IllegalStateException("Aucun média Plex trouvé pour « $t ».")
    }

    fun machineId(app: JarvisApp) = serveur(app).optString("machineIdentifier").ifBlank { throw IllegalStateException("Identifiant du serveur Plex introuvable.") }

    fun appareil(app: JarvisApp, nom: String): JSONObject {
        val devices = appareils(app)
        val l = (0 until devices.length()).map { devices.getJSONObject(it) }
        return l.firstOrNull { it.optString("name").equals(nom, true) }
            ?: l.firstOrNull { it.optString("name").contains(nom, true) || it.optString("product").contains(nom, true) }
            ?: (if (nom.isBlank()) l.singleOrNull() else null)
            ?: throw IllegalStateException("Lecteur Plex « $nom » introuvable parmi les appareils visibles" + (if (l.isNotEmpty()) " (${l.joinToString { it.optString("name") }})" else "") +
                ". L'application Plex doit être ouverte sur l'appareil.")
    }

    fun clientId(appareil: JSONObject) = appareil.optString("clientIdentifier").ifBlank { appareil.optString("machineIdentifier") }
        .ifBlank { throw IllegalStateException("Ce lecteur Plex ne fournit pas d'identifiant de contrôle (clientIdentifier).") }

    fun lancer(app: JarvisApp, media: JSONObject, appareil: JSONObject, offsetMs: Long = 0): String {
        val ratingKey = media.optString("ratingKey").ifBlank { throw IllegalStateException("Plex n'a pas fourni l'identifiant de ce média.") }
        val machineId = machineId(app)
        val adresse = Serveur.ipLocaleServeur(app) ?: throw IllegalStateException("Impossible de déterminer l'adresse locale du serveur Debian pour transmettre le flux au lecteur.")
        request(app, "/player/playback/playMedia", mapOf(
            "key" to "/library/metadata/$ratingKey",
            "machineIdentifier" to machineId,
            "address" to adresse,
            "port" to PORT.toString(),
            "protocol" to "http",
            "offset" to offsetMs.toString(),
            "commandID" to (System.currentTimeMillis() % Int.MAX_VALUE).toString(),
            "type" to if (media.optString("type") in setOf("track", "album", "artist")) "music" else "video",
            "path" to "http://$adresse:$PORT/library/metadata/$ratingKey"
        ), clientId(appareil))
        return "▶️ « ${titre(media)} » lancé sur ${appareil.optString("name").ifBlank { appareil.optString("product").ifBlank { "le lecteur Plex" } }}" +
            (if (offsetMs > 0) " (reprise à ${duree(offsetMs)})" else "") + "."
    }

    fun titre(m: JSONObject): String = when (m.optString("type")) {
        "episode" -> "${m.optString("grandparentTitle")} — S${m.optInt("parentIndex").toString().padStart(2, '0')}E${m.optInt("index").toString().padStart(2, '0')} « ${m.optString("title")} »"
        "season" -> "${m.optString("parentTitle")} — ${m.optString("title")}"
        "track" -> "${m.optString("grandparentTitle")} — ${m.optString("title")}"
        else -> m.optString("title") + (m.optString("year").takeIf { it.isNotBlank() && it != "0" }?.let { " ($it)" }.orEmpty())
    }

    fun duree(ms: Long): String { val m = ms / 60_000; return if (m >= 60) "${m / 60} h ${(m % 60).toString().padStart(2, '0')}" else "$m min" }
    fun taille(o: Long): String = if (o >= 1_073_741_824) String.format(java.util.Locale.FRENCH, "%.1f Go", o / 1_073_741_824.0) else "${o / 1_048_576} Mo"
}

/** Outils conversationnels Plex : recherche, bibliothèques, lecture et télécommande, suivi, listes de lecture, serveur… */
class PlexTools(private val app: JarvisApp, private val confirm: suspend (String, Boolean) -> Boolean) {
    companion object {
        val NAMES = setOf("plex_rechercher", "plex_appareils", "plex_lancer", "plex_bibliotheques", "plex_parcourir", "plex_recents", "plex_en_cours",
            "plex_details", "plex_marquer", "plex_sessions", "plex_controle", "plex_playlists", "plex_serveur", "plex_supprimer", "plex_api")
        val KEYWORDS = Regex("\\bplex\\b|biblioth[eè]que plex|lecteur plex|\\bplaylists?\\b|liste de lecture|on deck|films? (non vus|r[ée]cents)|reprendre (le|mon) film", RegexOption.IGNORE_CASE)
        fun concerned(text: String) = KEYWORDS.containsMatchIn(text)
    }

    private fun prop(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun tool(name: String, description: String, props: Map<String, JSONObject>, required: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
                .put("required", JSONArray(required))))

    val schema = listOf(
        tool("plex_rechercher", "Plex (INSTANCE Plex Media Server, par son API — pas le serveur Debian) : recherche un film, une série, un épisode, un artiste… dans les bibliothèques du serveur (lecture seule).",
            mapOf("titre" to prop("Titre ou partie du titre à rechercher.")), listOf("titre")),
        tool("plex_appareils", "Plex : liste les lecteurs Plex visibles par le serveur (TV, téléphone, boîtier…).", emptyMap()),
        tool("plex_lancer", "Plex : lance un film, une série (épisode précis, ou le prochain non vu) ou de la musique sur un lecteur Plex visible ; reprend là où la lecture s'était arrêtée. Demande confirmation.",
            mapOf("titre" to prop("Titre du film, de la série ou de la musique (ou identifiant)."), "appareil" to prop("Nom du lecteur Plex (TV salon, téléphone…)."),
                "saison" to prop("Série : numéro de saison (optionnel)."), "episode" to prop("Série : numéro d'épisode (optionnel ; sans saison ni épisode = prochain épisode non vu)."),
                "depuis_le_debut" to prop("« oui » pour ne pas reprendre la lecture en cours.")), listOf("titre", "appareil")),
        tool("plex_bibliotheques", "Plex : bibliothèques du serveur — lister (avec nombre d'éléments) | scanner (rechercher les nouveaux fichiers, une bibliothèque ou toutes) | vider_corbeille | analyser.",
            mapOf("action" to prop("lister | scanner | vider_corbeille | analyser"), "bibliotheque" to prop("Nom de la bibliothèque (Films, Séries…) ; vide = toutes.")), listOf("action")),
        tool("plex_parcourir", "Plex : parcourt une bibliothèque avec filtres — non vus, genre, année, acteur, réalisateur, résolution ; tri (récents, titre, note, année, aléatoire).",
            mapOf("bibliotheque" to prop("Films, Séries, Musique… (nom de la bibliothèque)."), "non_vus" to prop("« oui » : seulement les non vus."),
                "genre" to prop("Genre (Action, Comédie…)."), "annee" to prop("Année ou décennie (ex. 1990s)."), "acteur" to prop("Acteur."), "realisateur" to prop("Réalisateur."),
                "tri" to prop("recents | titre | note | annee | aleatoire."), "nombre" to prop("Nombre de résultats (défaut 20).")), listOf("bibliotheque")),
        tool("plex_recents", "Plex : derniers éléments ajoutés (toutes les bibliothèques ou une seule).", mapOf("bibliotheque" to prop("Optionnel."), "nombre" to prop("Défaut 15."))),
        tool("plex_en_cours", "Plex : « En cours » / à reprendre (On Deck) — films commencés et prochains épisodes des séries suivies.", mapOf("nombre" to prop("Défaut 15."))),
        tool("plex_details", "Plex : fiche complète d'un média — résumé, année, durée, note, genres, acteurs, réalisateur, qualité vidéo/audio, sous-titres, fichier et taille, statut vu ; pour une série : saisons et épisodes (vus / non vus).",
            mapOf("titre" to prop("Titre ou identifiant.")), listOf("titre")),
        tool("plex_marquer", "Plex : marque un film, une série entière, une saison ou un épisode comme vu ou non vu.",
            mapOf("titre" to prop("Titre ou identifiant."), "etat" to prop("vu | non_vu"), "saison" to prop("Optionnel."), "episode" to prop("Optionnel.")), listOf("titre", "etat")),
        tool("plex_sessions", "Plex : lectures en cours sur le serveur (qui regarde quoi, sur quel appareil, transcodage, progression) ; arreter une lecture avec un message.",
            mapOf("action" to prop("lister | arreter"), "session" to prop("arreter : identifiant de session, nom d'utilisateur ou d'appareil."), "message" to prop("Message affiché au spectateur.")), listOf("action")),
        tool("plex_controle", "Plex : télécommande d'un lecteur — pause, lecture, stop, suivant, precedent, avancer, reculer, volume (0-100), aller_a (minutes).",
            mapOf("commande" to prop("pause | lecture | stop | suivant | precedent | avancer | reculer | volume | aller_a"), "appareil" to prop("Nom du lecteur Plex."),
                "valeur" to prop("volume : 0-100 ; aller_a : minutes.")), listOf("commande")),
        tool("plex_playlists", "Plex : listes de lecture — lister | voir | creer (titre + éléments) | ajouter | retirer | supprimer.",
            mapOf("action" to prop("lister | voir | creer | ajouter | retirer | supprimer"), "playlist" to prop("Nom de la liste de lecture."),
                "elements" to prop("Titres ou identifiants séparés par « ; ».")), listOf("action")),
        tool("plex_serveur", "Plex Media Server (INSTANCE, par son API ; pour démarrer / arrêter / mettre à jour le SERVICE sur le serveur Debian, utilise serveur_service) : etat (version, mise à jour disponible, activités en cours, transcodages, bibliothèques) | maintenance (optimiser la base, nettoyer les anciens fichiers, vider les corbeilles) | reglages (lire, filtre optionnel) | modifier_reglage (cle + valeur) | verifier_mise_a_jour.",
            mapOf("action" to prop("etat | maintenance | reglages | modifier_reglage | verifier_mise_a_jour"), "filtre" to prop("reglages : mot à chercher dans les réglages."),
                "cle" to prop("modifier_reglage : identifiant du réglage."), "valeur" to prop("modifier_reglage : nouvelle valeur.")), listOf("action")),
        tool("plex_supprimer", "Plex : SUPPRIME définitivement un média (fichier compris) — confirmation obligatoire ; nécessite « Autoriser la suppression des médias » dans les réglages du serveur.",
            mapOf("titre" to prop("Titre ou identifiant."), "saison" to prop("Optionnel."), "episode" to prop("Optionnel.")), listOf("titre")),
        tool("plex_api", "Plex : accès direct à toute l'API du serveur Plex (ou de plex.tv avec une adresse https://plex.tv/… : comptes, partages, amis) pour tout ce que les autres outils ne couvrent pas. GET libre ; POST/PUT/DELETE avec confirmation.",
            mapOf("methode" to prop("GET | POST | PUT | DELETE"), "chemin" to prop("Chemin de l'API (ex. /library/sections/1/all, /status/sessions/history/all) ou adresse https://plex.tv/…"),
                "parametres" to prop("Paramètres de requête en JSON (optionnel).")), listOf("chemin")),
    )

    private fun ligne(m: JSONObject): String {
        val vu = when {
            m.optString("type") == "show" -> m.optInt("leafCount").let { t -> " · ${m.optInt("viewedLeafCount")}/$t épisodes vus" }
            m.optInt("viewCount") > 0 -> " · ✅ vu"
            m.optLong("viewOffset") > 0 -> " · ⏸️ en cours (${Plex.duree(m.optLong("viewOffset"))})"
            else -> ""
        }
        return "- **${Plex.titre(m)}** · ${m.optString("type")}" + (m.optLong("duration").takeIf { it > 0 }?.let { " · ${Plex.duree(it)}" }.orEmpty()) + vu + " · ID ${m.optString("ratingKey")}"
    }

    /** Épisode d'une série : précis (saison / épisode) ou le prochain non vu. */
    private fun episode(serie: JSONObject, saison: Int?, ep: Int?): JSONObject {
        val tous = Plex.liste(Plex.mc(app, "/library/metadata/${serie.optString("ratingKey")}/allLeaves"))
        if (tous.isEmpty()) throw IllegalStateException("Aucun épisode pour « ${serie.optString("title")} ».")
        return when {
            saison != null && ep != null -> tous.firstOrNull { it.optInt("parentIndex") == saison && it.optInt("index") == ep }
            saison != null -> tous.firstOrNull { it.optInt("parentIndex") == saison && it.optInt("viewCount") == 0 } ?: tous.firstOrNull { it.optInt("parentIndex") == saison }
            ep != null -> tous.firstOrNull { it.optInt("index") == ep && it.optInt("viewCount") == 0 }
            else -> tous.firstOrNull { it.optLong("viewOffset") > 0 } ?: tous.firstOrNull { it.optInt("viewCount") == 0 } ?: tous.first()
        } ?: throw IllegalStateException("Épisode introuvable (saison ${saison ?: "?"}, épisode ${ep ?: "?"}).")
    }

    suspend fun execute(name: String, a: JSONObject): ToolResult = withContextIo {
        try {
            when (name) {
                "plex_rechercher" -> {
                    val items = Plex.rechercher(app, a.optString("titre"))
                    if (items.isEmpty()) ToolResult("Aucun résultat Plex pour « ${a.optString("titre")} ».", direct = true)
                    else ToolResult("# Résultats Plex\n" + items.take(15).joinToString("\n") { item -> ligne(item) +
                        item.optString("summary").takeIf { it.isNotBlank() }?.let { "\n  ${it.take(180)}" }.orEmpty() }, direct = true)
                }
                "plex_appareils" -> {
                    val devices = Plex.appareils(app)
                    if (devices.length() == 0) ToolResult("Aucun lecteur Plex n'est actuellement visible. Vérifie que l'application Plex est ouverte, connectée au même compte et que le contrôle à distance est autorisé.", direct = true)
                    else ToolResult("# Lecteurs Plex visibles\n" + (0 until devices.length()).joinToString("\n") { i ->
                        val d = devices.getJSONObject(i)
                        "- **${d.optString("name").ifBlank { d.optString("product") }}** · ${d.optString("product")} · ${d.optString("platform")} · ${d.optString("address")}"
                    }, direct = true)
                }
                "plex_lancer" -> {
                    var media = Plex.media(app, a.optString("titre"))
                    if (media.optString("type") in setOf("show", "season")) {
                        val serie = if (media.optString("type") == "season") Plex.metadata(app, media.optString("parentRatingKey")) else media
                        media = episode(serie, a.optString("saison").toIntOrNull() ?: (if (media.optString("type") == "season") media.optInt("index") else null), a.optString("episode").toIntOrNull())
                    } else if (media.optString("type") == "movie" || media.optString("type") == "episode") media = Plex.metadata(app, media.optString("ratingKey"))
                    val device = Plex.appareil(app, a.optString("appareil").trim())
                    val offset = if (a.optString("depuis_le_debut").lowercase() in setOf("oui", "true", "1")) 0L else media.optLong("viewOffset")
                    if (!confirm("Lancer « ${Plex.titre(media)} » sur « ${device.optString("name")} » via Plex" + (if (offset > 0) " (reprise à ${Plex.duree(offset)})" else "") + " ?", true))
                        return@withContextIo ToolResult("Lecture annulée.")
                    ToolResult(Plex.lancer(app, media, device, offset), direct = true)
                }
                "plex_bibliotheques" -> bibliotheques(a)
                "plex_parcourir" -> {
                    val s = Plex.section(app, a.optString("bibliotheque")) ?: return@withContextIo ToolResult("Bibliothèque « ${a.optString("bibliotheque")} » introuvable : " + Plex.sections(app).joinToString { it.optString("title") })
                    val q = linkedMapOf("X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to ((a.optString("nombre").toIntOrNull() ?: 20).coerceIn(1, 100)).toString())
                    if (a.optString("non_vus").lowercase() in setOf("oui", "true", "1")) q["unwatched"] = "1"
                    a.optString("genre").takeIf { it.isNotBlank() }?.let { g -> filtre(s, "genre", g)?.let { q["genre"] = it } }
                    a.optString("acteur").takeIf { it.isNotBlank() }?.let { g -> filtre(s, "actor", g)?.let { q["actor"] = it } }
                    a.optString("realisateur").takeIf { it.isNotBlank() }?.let { g -> filtre(s, "director", g)?.let { q["director"] = it } }
                    a.optString("annee").takeIf { it.isNotBlank() }?.let { y -> if (y.endsWith("s")) q["decade"] = y.dropLast(1) else q["year"] = y }
                    q["sort"] = when (a.optString("tri").lowercase()) { "titre" -> "titleSort"; "note" -> "rating:desc"; "annee" -> "year:desc"; "aleatoire" -> "random"; else -> "addedAt:desc" }
                    val o = Plex.mc(app, "/library/sections/${s.optString("key")}/all", q)
                    val l = Plex.liste(o)
                    ToolResult("# ${s.optString("title")} — ${l.size} sur ${o.optInt("totalSize", o.optInt("size"))}\n" + l.joinToString("\n") { ligne(it) }.ifBlank { "(rien ne correspond)" }, direct = true)
                }
                "plex_recents" -> {
                    val n = (a.optString("nombre").toIntOrNull() ?: 15).coerceIn(1, 50).toString()
                    val s = a.optString("bibliotheque").takeIf { it.isNotBlank() }?.let { Plex.section(app, it) }
                    val o = Plex.mc(app, if (s != null) "/library/sections/${s.optString("key")}/recentlyAdded" else "/library/recentlyAdded", mapOf("X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to n))
                    ToolResult("# 🆕 Ajouts récents\n" + Plex.liste(o).take(n.toInt()).joinToString("\n") { ligne(it) }.ifBlank { "(rien)" }, direct = true)
                }
                "plex_en_cours" -> {
                    val n = (a.optString("nombre").toIntOrNull() ?: 15).coerceIn(1, 50)
                    ToolResult("# ⏯️ À reprendre\n" + Plex.liste(Plex.mc(app, "/library/onDeck")).take(n).joinToString("\n") { ligne(it) }.ifBlank { "(rien en cours)" }, direct = true)
                }
                "plex_details" -> ToolResult(details(Plex.media(app, a.optString("titre"))), direct = true)
                "plex_marquer" -> {
                    var m = Plex.media(app, a.optString("titre"))
                    val s = a.optString("saison").toIntOrNull(); val e = a.optString("episode").toIntOrNull()
                    if (m.optString("type") == "show" && (s != null || e != null)) {
                        m = if (e != null) episode(m, s, e) else Plex.liste(Plex.mc(app, "/library/metadata/${m.optString("ratingKey")}/children")).firstOrNull { it.optInt("index") == s } ?: throw IllegalStateException("Saison $s introuvable.")
                    }
                    val vu = !a.optString("etat").lowercase().contains("non")
                    Plex.request(app, if (vu) "/:/scrobble" else "/:/unscrobble", mapOf("key" to m.optString("ratingKey"), "identifier" to "com.plexapp.plugins.library"))
                    ToolResult("✅ « ${Plex.titre(m)} » marqué comme ${if (vu) "vu" else "non vu"}.", direct = true)
                }
                "plex_sessions" -> sessions(a)
                "plex_controle" -> controle(a)
                "plex_playlists" -> playlists(a)
                "plex_serveur" -> serveur(a)
                "plex_supprimer" -> {
                    var m = Plex.media(app, a.optString("titre"))
                    val s = a.optString("saison").toIntOrNull(); val e = a.optString("episode").toIntOrNull()
                    if (m.optString("type") == "show" && (s != null || e != null)) m = if (e != null) episode(m, s, e)
                        else Plex.liste(Plex.mc(app, "/library/metadata/${m.optString("ratingKey")}/children")).firstOrNull { it.optInt("index") == s } ?: throw IllegalStateException("Saison $s introuvable.")
                    if (!confirm("SUPPRIMER définitivement « ${Plex.titre(m)} » de Plex (fichier compris) ?", true)) return@withContextIo ToolResult("Suppression annulée.")
                    Plex.request(app, "/library/metadata/${m.optString("ratingKey")}", methode = "DELETE")
                    ToolResult("🗑️ « ${Plex.titre(m)} » supprimé.", direct = true)
                }
                "plex_api" -> {
                    val methode = a.optString("methode").ifBlank { "GET" }.uppercase()
                    val chemin = a.optString("chemin").trim().let { if (it.startsWith("/") || it.startsWith("https://")) it else "/$it" }
                    if (chemin.startsWith("https://") && !Regex("^https://([a-z]+\\.)?plex\\.tv/").containsMatchIn(chemin)) return@withContextIo ToolResult("Seules les adresses plex.tv sont autorisées.")
                    val params = runCatching { JSONObject(a.optString("parametres").ifBlank { "{}" }) }.getOrDefault(JSONObject())
                    val q = params.keys().asSequence().associateWith { params.optString(it) }
                    if (methode != "GET" && !confirm("Plex : $methode $chemin ${if (q.isNotEmpty()) q.toString() else ""} ?", true)) return@withContextIo ToolResult("Refusé par l'utilisateur.")
                    val t = Plex.request(app, chemin, q, methode = methode)
                    ToolResult("```json\n" + (runCatching { JSONObject(t).toString(1) }.getOrDefault(t)).take(7000) + "\n```")
                }
                else -> ToolResult("Outil Plex inconnu.")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { ToolResult("❌ Plex : ${e.message ?: e.javaClass.simpleName}") }
    }

    /** Valeur de filtre Plex (genre, acteur…) correspondant au texte demandé. */
    private fun filtre(section: JSONObject, champ: String, texte: String): String? {
        val l = Plex.liste(Plex.mc(app, "/library/sections/${section.optString("key")}/$champ"), "Directory")
        val n = fr.dd.jarvis.societe.core.normaliser(texte)
        return (l.firstOrNull { fr.dd.jarvis.societe.core.normaliser(it.optString("title")) == n } ?: l.firstOrNull { fr.dd.jarvis.societe.core.normaliser(it.optString("title")).contains(n) })?.optString("key")
    }

    private fun bibliotheques(a: JSONObject): ToolResult {
        val action = fr.dd.jarvis.societe.core.normaliser(a.optString("action")).replace(' ', '_')
        val cible = a.optString("bibliotheque").takeIf { it.isNotBlank() }?.let { Plex.section(app, it) ?: throw IllegalStateException("Bibliothèque « $it » introuvable.") }
        val toutes = Plex.sections(app)
        return when (action) {
            "lister" -> ToolResult("# 📚 Bibliothèques Plex\n" + toutes.joinToString("\n") { s ->
                val total = runCatching { Plex.mc(app, "/library/sections/${s.optString("key")}/all", mapOf("X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to "0")).optInt("totalSize") }.getOrDefault(0)
                val dossiers = Plex.liste(s, "Location").joinToString { it.optString("path") }
                "- **${s.optString("title")}** (${s.optString("type")}) · $total éléments · $dossiers" + (if (s.optBoolean("refreshing")) " · 🔄 analyse en cours" else "")
            }, direct = true)
            "scanner" -> { (cible?.let { listOf(it) } ?: toutes).forEach { Plex.request(app, "/library/sections/${it.optString("key")}/refresh") }
                ToolResult("🔄 Recherche de nouveaux fichiers lancée : ${(cible?.let { listOf(it) } ?: toutes).joinToString { it.optString("title") }}.", direct = true) }
            "vider_corbeille" -> { (cible?.let { listOf(it) } ?: toutes).forEach { Plex.request(app, "/library/sections/${it.optString("key")}/emptyTrash", methode = "PUT") }
                ToolResult("🗑️ Corbeille vidée : ${(cible?.let { listOf(it) } ?: toutes).joinToString { it.optString("title") }}.", direct = true) }
            "analyser" -> { (cible?.let { listOf(it) } ?: toutes).forEach { Plex.request(app, "/library/sections/${it.optString("key")}/analyze", methode = "PUT") }
                ToolResult("🔍 Analyse lancée.", direct = true) }
            else -> ToolResult("Action inconnue (lister, scanner, vider_corbeille, analyser).")
        }
    }

    private fun details(m0: JSONObject): String {
        val m = Plex.metadata(app, m0.optString("ratingKey"))
        fun noms(k: String) = Plex.liste(m, k).joinToString { it.optString("tag") }
        val sb = StringBuilder("# 🎬 ${Plex.titre(m)}\n")
        listOfNotNull(
            m.optString("type").let { "Type : $it" },
            m.optLong("duration").takeIf { it > 0 }?.let { "Durée : ${Plex.duree(it)}" },
            (m.optDouble("audienceRating").takeIf { !it.isNaN() && it > 0 } ?: m.optDouble("rating").takeIf { !it.isNaN() && it > 0 })?.let { "Note : $it/10" },
            noms("Genre").takeIf { it.isNotBlank() }?.let { "Genres : $it" },
            noms("Director").takeIf { it.isNotBlank() }?.let { "Réalisation : $it" },
            Plex.liste(m, "Role").take(8).joinToString { it.optString("tag") }.takeIf { it.isNotBlank() }?.let { "Avec : $it" },
            m.optString("contentRating").takeIf { it.isNotBlank() }?.let { "Public : $it" },
            when {
                m.optString("type") == "show" -> "Épisodes : ${m.optInt("viewedLeafCount")}/${m.optInt("leafCount")} vus · ${m.optInt("childCount")} saison(s)"
                m.optInt("viewCount") > 0 -> "Statut : ✅ vu"
                m.optLong("viewOffset") > 0 -> "Statut : ⏸️ en cours (${Plex.duree(m.optLong("viewOffset"))})"
                else -> "Statut : non vu"
            },
        ).forEach { sb.append("- ").append(it).append('\n') }
        Plex.liste(m, "Media").firstOrNull()?.let { md ->
            sb.append("- Qualité : ${md.optString("videoResolution")}p ${md.optString("videoCodec")} · audio ${md.optString("audioCodec")} ${md.optString("audioChannels")} canaux · ${md.optString("container")}\n")
            Plex.liste(md, "Part").firstOrNull()?.let { p ->
                sb.append("- Fichier : ${p.optString("file")} (${Plex.taille(p.optLong("size"))})\n")
                val st = Plex.liste(p, "Stream")
                st.filter { it.optInt("streamType") == 3 }.joinToString { it.optString("displayTitle") }.takeIf { it.isNotBlank() }?.let { sb.append("- Sous-titres : $it\n") }
                st.filter { it.optInt("streamType") == 2 }.joinToString { it.optString("displayTitle") }.takeIf { it.isNotBlank() }?.let { sb.append("- Pistes audio : $it\n") }
            }
        }
        if (m.optString("type") == "show") Plex.liste(Plex.mc(app, "/library/metadata/${m.optString("ratingKey")}/children")).forEach { s ->
            sb.append("  - ${s.optString("title")} : ${s.optInt("viewedLeafCount")}/${s.optInt("leafCount")} vus\n")
        }
        m.optString("summary").takeIf { it.isNotBlank() }?.let { sb.append("\n").append(it.take(900)) }
        sb.append("\n\nID ${m.optString("ratingKey")}")
        return sb.toString()
    }

    private fun sessions(a: JSONObject): ToolResult {
        val l = Plex.liste(Plex.mc(app, "/status/sessions"))
        if (fr.dd.jarvis.societe.core.normaliser(a.optString("action")) == "arreter") {
            val q = a.optString("session").trim()
            val s = l.firstOrNull { it.optJSONObject("Session")?.optString("id") == q } ?: l.firstOrNull { x ->
                x.optJSONObject("User")?.optString("title")?.contains(q, true) == true || x.optJSONObject("Player")?.optString("title")?.contains(q, true) == true
            } ?: (if (q.isBlank()) l.singleOrNull() else null) ?: return ToolResult("Lecture « $q » introuvable parmi ${l.size} lecture(s) en cours.")
            Plex.request(app, "/status/sessions/terminate", mapOf("sessionId" to (s.optJSONObject("Session")?.optString("id") ?: ""), "reason" to a.optString("message").ifBlank { "Lecture arrêtée par JARVIS." }))
            return ToolResult("⏹️ Lecture de « ${Plex.titre(s)} » arrêtée.", direct = true)
        }
        if (l.isEmpty()) return ToolResult("Personne ne regarde Plex en ce moment.", direct = true)
        return ToolResult("# 📺 Lectures en cours\n" + l.joinToString("\n") { s ->
            val p = s.optJSONObject("Player"); val u = s.optJSONObject("User")
            val prog = if (s.optLong("duration") > 0) " · ${s.optLong("viewOffset") * 100 / s.optLong("duration")} %" else ""
            val tc = s.optJSONObject("TranscodeSession")?.let { " · transcodage ${it.optString("videoDecision")}" } ?: " · lecture directe"
            "- **${Plex.titre(s)}** · ${u?.optString("title").orEmpty()} sur ${p?.optString("title").orEmpty()} (${p?.optString("state").orEmpty()})$prog$tc · session ${s.optJSONObject("Session")?.optString("id").orEmpty()}"
        }, direct = true)
    }

    private fun controle(a: JSONObject): ToolResult {
        val d = Plex.appareil(app, a.optString("appareil").trim())
        val id = Plex.clientId(d)
        val c = fr.dd.jarvis.societe.core.normaliser(a.optString("commande")).replace(' ', '_')
        val cmd = (System.currentTimeMillis() % Int.MAX_VALUE).toString()
        val (chemin, q) = when (c) {
            "pause" -> "/player/playback/pause" to mapOf("type" to "video")
            "lecture", "play", "reprendre" -> "/player/playback/play" to mapOf("type" to "video")
            "stop", "arreter" -> "/player/playback/stop" to mapOf("type" to "video")
            "suivant" -> "/player/playback/skipNext" to mapOf("type" to "video")
            "precedent" -> "/player/playback/skipPrevious" to mapOf("type" to "video")
            "avancer" -> "/player/playback/stepForward" to mapOf("type" to "video")
            "reculer" -> "/player/playback/stepBack" to mapOf("type" to "video")
            "volume" -> "/player/playback/setParameters" to mapOf("volume" to (a.optString("valeur").filter { it.isDigit() }.ifBlank { "50" }), "type" to "video")
            "aller_a" -> "/player/playback/seekTo" to mapOf("offset" to ((a.optString("valeur").replace(',', '.').toDoubleOrNull() ?: 0.0) * 60_000).toLong().toString(), "type" to "video")
            else -> return ToolResult("Commande inconnue (pause, lecture, stop, suivant, precedent, avancer, reculer, volume, aller_a).")
        }
        Plex.request(app, chemin, q + ("commandID" to cmd), id)
        return ToolResult("✅ ${a.optString("commande")} envoyé à ${d.optString("name")}.", direct = true)
    }

    private fun playlists(a: JSONObject): ToolResult {
        val action = fr.dd.jarvis.societe.core.normaliser(a.optString("action"))
        val toutes = Plex.liste(Plex.mc(app, "/playlists"))
        fun trouver(nom: String) = toutes.firstOrNull { it.optString("title").equals(nom, true) } ?: toutes.firstOrNull { it.optString("title").contains(nom, true) }
        val elements = a.optString("elements").split(';').map { it.trim() }.filter { it.isNotBlank() }.map { Plex.media(app, it) }
        val uri = { ids: List<String> -> "server://${Plex.machineId(app)}/com.plexapp.plugins.library/library/metadata/${ids.joinToString(",")}" }
        return when (action) {
            "lister" -> ToolResult("# 🎞️ Listes de lecture\n" + toutes.joinToString("\n") { "- **${it.optString("title")}** · ${it.optString("playlistType")} · ${it.optInt("leafCount")} éléments" + (it.optLong("duration").takeIf { d -> d > 0 }?.let { d -> " · ${Plex.duree(d)}" }.orEmpty()) }.ifBlank { "(aucune)" }, direct = true)
            "voir" -> { val p = trouver(a.optString("playlist")) ?: return ToolResult("Liste « ${a.optString("playlist")} » introuvable.")
                ToolResult("# ${p.optString("title")}\n" + Plex.liste(Plex.mc(app, "/playlists/${p.optString("ratingKey")}/items")).joinToString("\n") { ligne(it) }, direct = true) }
            "creer" -> {
                if (elements.isEmpty()) return ToolResult("Indique au moins un élément à mettre dans la liste.")
                val type = if (elements.all { it.optString("type") in setOf("track", "album", "artist") }) "audio" else "video"
                Plex.request(app, "/playlists", mapOf("type" to type, "title" to a.optString("playlist").ifBlank { "Liste JARVIS" }, "smart" to "0", "uri" to uri(elements.map { it.optString("ratingKey") })), methode = "POST")
                ToolResult("✅ Liste « ${a.optString("playlist")} » créée avec ${elements.size} élément(s).", direct = true)
            }
            "ajouter" -> { val p = trouver(a.optString("playlist")) ?: return ToolResult("Liste « ${a.optString("playlist")} » introuvable.")
                Plex.request(app, "/playlists/${p.optString("ratingKey")}/items", mapOf("uri" to uri(elements.map { it.optString("ratingKey") })), methode = "PUT")
                ToolResult("✅ ${elements.size} élément(s) ajouté(s) à « ${p.optString("title")} ».", direct = true) }
            "retirer" -> { val p = trouver(a.optString("playlist")) ?: return ToolResult("Liste « ${a.optString("playlist")} » introuvable.")
                val items = Plex.liste(Plex.mc(app, "/playlists/${p.optString("ratingKey")}/items"))
                val ids = elements.map { it.optString("ratingKey") }.toSet()
                items.filter { it.optString("ratingKey") in ids }.forEach { Plex.request(app, "/playlists/${p.optString("ratingKey")}/items/${it.optString("playlistItemID")}", methode = "DELETE") }
                ToolResult("✅ Élément(s) retiré(s) de « ${p.optString("title")} ».", direct = true) }
            "supprimer" -> { val p = trouver(a.optString("playlist")) ?: return ToolResult("Liste « ${a.optString("playlist")} » introuvable.")
                Plex.request(app, "/playlists/${p.optString("ratingKey")}", methode = "DELETE"); ToolResult("🗑️ Liste « ${p.optString("title")} » supprimée.", direct = true) }
            else -> ToolResult("Action inconnue (lister, voir, creer, ajouter, retirer, supprimer).")
        }
    }

    private suspend fun serveur(a: JSONObject): ToolResult {
        return when (fr.dd.jarvis.societe.core.normaliser(a.optString("action")).replace(' ', '_')) {
            "etat" -> {
                val s = Plex.serveur(app)
                val maj = runCatching { Plex.liste(Plex.mc(app, "/updater/status"), "Release").firstOrNull() }.getOrNull()
                val act = runCatching { Plex.liste(Plex.mc(app, "/activities"), "Activity") }.getOrDefault(emptyList())
                val sess = runCatching { Plex.mc(app, "/status/sessions").optInt("size") }.getOrDefault(0)
                ToolResult("# 🖥️ Plex Media Server\n- ${s.optString("friendlyName")} · version ${s.optString("version")} · ${s.optString("platform")} ${s.optString("platformVersion")}\n" +
                    "- Mise à jour : " + (maj?.let { "✅ disponible — ${it.optString("version")}" } ?: "à jour (ou non vérifiée)") + "\n" +
                    "- Lectures en cours : $sess · transcodages max ${s.optString("transcoderActiveVideoSessions", "?")}\n" +
                    "- Activités : " + act.joinToString { "${it.optString("title")} ${it.optInt("progress")} %" }.ifBlank { "aucune" } + "\n" +
                    "- Bibliothèques : " + Plex.sections(app).joinToString { it.optString("title") }, direct = true)
            }
            "maintenance" -> {
                if (!confirm("Plex : optimiser la base, nettoyer les anciens fichiers et vider les corbeilles ?", false)) return ToolResult("Annulé.")
                val r = listOf(
                    runCatching { Plex.request(app, "/library/optimize", mapOf("async" to "1"), methode = "PUT"); "✅ base optimisée" }.getOrElse { "⚠️ optimisation : ${it.message}" },
                    runCatching { Plex.request(app, "/library/clean/bundles", methode = "PUT"); "✅ anciens fichiers nettoyés" }.getOrElse { "⚠️ nettoyage : ${it.message}" },
                    runCatching { Plex.sections(app).forEach { Plex.request(app, "/library/sections/${it.optString("key")}/emptyTrash", methode = "PUT") }; "✅ corbeilles vidées" }.getOrElse { "⚠️ corbeilles : ${it.message}" },
                )
                ToolResult(r.joinToString("\n"), direct = true)
            }
            "reglages" -> {
                val f = a.optString("filtre").trim()
                val l = Plex.liste(Plex.mc(app, "/:/prefs"), "Setting").filter { f.isBlank() || it.optString("id").contains(f, true) || it.optString("label").contains(f, true) || it.optString("summary").contains(f, true) }
                ToolResult("# ⚙️ Réglages Plex" + (if (f.isNotBlank()) " (« $f »)" else "") + "\n" + l.take(60).joinToString("\n") { "- `${it.optString("id")}` = ${it.optString("value")} · ${it.optString("label")}" }, direct = true)
            }
            "modifier_reglage" -> {
                val cle = a.optString("cle").trim(); val valeur = a.optString("valeur")
                if (cle.isBlank()) return ToolResult("Précise la clé du réglage (plex_serveur reglages pour la trouver).")
                if (!confirm("Plex : changer le réglage « $cle » en « $valeur » ?", true)) return ToolResult("Refusé par l'utilisateur.")
                Plex.request(app, "/:/prefs", mapOf(cle to valeur), methode = "PUT")
                ToolResult("✅ Réglage Plex « $cle » = « $valeur ».", direct = true)
            }
            "verifier_mise_a_jour" -> {
                runCatching { Plex.request(app, "/updater/check", mapOf("download" to "0"), methode = "PUT") }
                val maj = runCatching { Plex.liste(Plex.mc(app, "/updater/status"), "Release").firstOrNull() }.getOrNull()
                ToolResult(maj?.let { "⬆️ Mise à jour Plex disponible : ${it.optString("version")}. Sur Debian elle s'installe par le paquet : dis « mets à jour Plex » (serveur_service mettre_a_jour)." }
                    ?: "✅ Plex est à jour.", direct = true)
            }
            else -> ToolResult("Action inconnue (etat, maintenance, reglages, modifier_reglage, verifier_mise_a_jour).")
        }
    }

    private suspend fun <T> withContextIo(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
}
