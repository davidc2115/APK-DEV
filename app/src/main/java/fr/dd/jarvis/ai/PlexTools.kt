package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Accès Plex Media Server : DIRECTEMENT par le réseau Wi-Fi de la maison (serveur relevé dans l'inventaire Wi-Fi, port
 * 32400), sinon par la connexion distante que le serveur annonce sur plex.tv ; le tunnel SSH par le serveur Debian n'est
 * plus qu'un dernier recours. Ni Home Assistant ni Hermès ne sont nécessaires.
 */
object Plex {
    private const val PORT = 32400
    private const val CLIENT_ID = "fr.dd.jarvis.plex"
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun token(app: JarvisApp): String = app.store.plexToken.value.trim().also {
        if (it.isBlank()) throw IllegalStateException("Jeton Plex manquant : Réglages › Maison › Serveur › Plex.")
    }

    private val rapide = OkHttpClient.Builder().connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(3, TimeUnit.SECONDS).build()
    @Volatile private var baseCache: Pair<String, Long>? = null

    /** Le serveur Plex répond-il à cette adresse ? (/identity ne demande pas de jeton.) */
    private fun repond(base: String) = runCatching {
        rapide.newCall(Request.Builder().url("$base/identity").header("Accept", "application/json").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /** Adresses locales possibles du serveur Plex, SANS SSH : inventaire Wi-Fi (port 32400), adresse du serveur relevée, réglages. */
    fun ipsLocales(app: JarvisApp): List<String> = buildList {
        runCatching {
            Registre.tous(app).filter { e -> e.optJSONArray("ports")?.let { a -> (0 until a.length()).any { a.optInt(it) == PORT } } == true }
                .sortedByDescending { it.optLong("vu") }.forEach { add(it.optString("ip")) }
        }
        app.store.serveurIpLocale.value.trim().takeIf { Serveur.ipPrivee(it) }?.let { add(it) }
        app.store.sshHost.value.trim().takeIf { Serveur.ipPrivee(it) }?.let { add(it) }
    }.filter { it.isNotBlank() }.distinct()

    /** Connexions distantes du serveur annoncées sur plex.tv (adresse publique / plex.direct, puis relais). */
    private fun distantes(app: JarvisApp): List<String> = runCatching {
        val a = JSONArray(request(app, "https://plex.tv/api/v2/resources", mapOf("includeHttps" to "1", "includeRelay" to "1", "includeIPv6" to "0")))
        val serveurs = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("provides").contains("server") }
            .sortedByDescending { it.optBoolean("owned") }
        serveurs.flatMap { s ->
            val cx = s.optJSONArray("connections")?.let { c -> (0 until c.length()).mapNotNull { c.optJSONObject(it) } }.orEmpty()
            cx.filter { !it.optBoolean("local") && !it.optBoolean("relay") }.map { it.optString("uri") } + cx.filter { it.optBoolean("relay") }.map { it.optString("uri") }
        }.filter { it.startsWith("http") }
    }.getOrDefault(emptyList())

    /** Adresse du serveur Plex à utiliser (gardée 5 minutes) : maison d'abord, distante ensuite, tunnel SSH en dernier recours. */
    private fun base(app: JarvisApp): String {
        baseCache?.takeIf { System.currentTimeMillis() - it.second < 5 * 60_000L }?.let { return it.first }
        val loc = locales(app)
        val b = loc.filter { it.second > 0 }.maxByOrNull { it.second }?.first
            ?: distantes(app).firstOrNull { repond(it.trimEnd('/')) }?.trimEnd('/')
            ?: loc.firstOrNull { it.second >= 0 }?.first ?: loc.firstOrNull()?.first
            ?: "http://127.0.0.1:${Serveur.tunnel(app, PORT)}"
        baseCache = b to System.currentTimeMillis()
        return b
    }

    /** Nombre de bibliothèques que le jeton voit sur ce serveur (-1 s'il ne répond pas ou refuse le jeton). */
    private fun nbBibliotheques(app: JarvisApp, base: String): Int = runCatching {
        rapide.newCall(Request.Builder().url("$base/library/sections").header("Accept", "application/json").header("X-Plex-Token", token(app)).build()).execute().use { r ->
            if (!r.isSuccessful) -1 else JSONObject(r.body?.string().orEmpty()).let { (it.optJSONObject("MediaContainer") ?: it).optJSONArray("Directory")?.length() ?: 0 }
        }
    }.getOrDefault(-1)

    /**
     * Serveur Plex de la maison. Plusieurs appareils peuvent répondre sur le port 32400 (box, NAS, télé avec un serveur
     * Plex vide…) : on garde celui dont le jeton voit le plus de bibliothèques, pas simplement le premier qui répond.
     */
    private fun locales(app: JarvisApp): List<Pair<String, Int>> =
        ipsLocales(app).filter { Serveur.telephoneSurReseau(app, it, PORT) }.map { "http://$it:$PORT" }.asSequence().filter { repond(it) }.take(4)
            .map { it to nbBibliotheques(app, it) }.toList()

    /** Comment JARVIS joint le serveur Plex en ce moment (pour l'utilisateur). */
    fun chemin(app: JarvisApp): String = base(app).let { b ->
        when { b.startsWith("http://127.0.0.1") -> "tunnel SSH par le serveur (aucune adresse directe joignable)"; Serveur.ipPrivee(b.removePrefix("http://").substringBefore(':')) -> "réseau Wi-Fi de la maison ($b)"; else -> "connexion distante du serveur Plex" }
    }

    private fun url(app: JarvisApp, path: String, query: Map<String, String> = emptyMap()): okhttp3.HttpUrl {
        val base = if (path.startsWith("https://")) path else base(app) + path
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
        val appel = try { http.newCall(b.build()).execute() } catch (e: java.io.IOException) {
            // Adresse du serveur devenue injoignable (changement de réseau) : on en cherche une autre, une fois.
            if (path.startsWith("https://") || baseCache == null) throw e
            baseCache = null
            http.newCall(b.url(url(app, path, query)).build()).execute()
        }
        appel.use { r ->
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

    /** Éléments d'un conteneur, quelle que soit la clé employée par la version du serveur (Metadata, Directory, Video, Track). */
    fun elements(o: JSONObject): List<JSONObject> = listOf("Metadata", "Directory", "Video", "Track").flatMap { liste(o, it) }

    val TYPES_MEDIA = setOf("movie", "show", "episode", "season", "artist", "album", "track", "collection")

    fun rechercher(app: JarvisApp, texte: String, limite: Int = 15): List<JSONObject> {
        require(texte.isNotBlank()) { "Précise le titre à rechercher dans Plex." }
        val q = texte.trim()
        val resultats = linkedMapOf<String, JSONObject>()
        fun garder(item: JSONObject) {
            val key = item.optString("ratingKey").ifBlank { item.optString("key") }
            if (key.isNotBlank()) resultats.putIfAbsent(key, item)
        }
        for (h in liste(mc(app, "/hubs/search", mapOf("query" to q, "limit" to limite.coerceIn(1, 30).toString())), "Hub")) elements(h).forEach(::garder)
        // Nouvelle recherche des serveurs récents (résultats dans SearchResult) quand /hubs/search ne donne rien.
        if (resultats.isEmpty()) runCatching {
            liste(mc(app, "/library/search", mapOf("query" to q, "limit" to limite.coerceIn(1, 30).toString(), "searchTypes" to "movies,tv,music")), "SearchResult")
                .forEach { r -> (r.optJSONObject("Metadata") ?: r.optJSONObject("Directory"))?.let(::garder); elements(r).forEach(::garder) }
        }
        return resultats.values.toList()
    }

    /** Recherche « le titre contient » dans chaque bibliothèque : fiable sur toutes les versions du serveur. */
    fun parBibliotheques(app: JarvisApp, texte: String, limite: Int = 10): List<JSONObject> =
        runCatching { sections(app) }.getOrDefault(emptyList()).filter { it.optString("type") in setOf("movie", "show", "artist") }.flatMap { s ->
            runCatching { elements(mc(app, "/library/sections/${s.optString("key")}/all", mapOf("title" to texte, "X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to limite.toString()))) }
                .getOrDefault(emptyList())
        }

    fun metadata(app: JarvisApp, id: String): JSONObject = liste(mc(app, "/library/metadata/$id")).firstOrNull()
        ?: throw IllegalStateException("Média Plex $id introuvable.")

    private val CHIFFRES = mapOf("un" to "1", "une" to "1", "deux" to "2", "trois" to "3", "quatre" to "4", "cinq" to "5", "six" to "6", "sept" to "7", "huit" to "8", "neuf" to "9", "dix" to "10")

    /** Titre comparable : minuscules, sans accents ni ponctuation, sans article de tête ni année. */
    internal fun comparable(t: String): String = fr.dd.jarvis.societe.core.normaliser(t.replace(Regex("\\((?:19|20)\\d{2}\\)"), " ").replace('’', '\''))
        .replace(Regex("^(le|la|les|l|un|une|des|the|a|an) "), "").split(' ').joinToString(" ") { CHIFFRES[it] ?: it }.trim()

    /** Façons de chercher un titre dicté : tel quel, sans article, sans ponctuation, sans accents, sans année. */
    internal fun variantes(titre: String): List<String> {
        val base = titre.trim().replace(Regex("[«»\"“”]"), "").replace(Regex("\\s+"), " ").trim()
        val sansAnnee = base.replace(Regex("\\s*\\(?(?:19|20)\\d{2}\\)?\\s*$"), "").trim()
        val sansArticle = sansAnnee.replace(Regex("(?i)^(?:le|la|les|un|une|des|the)\\s+|^(?i)l['’]\\s*"), "").trim()
        val sansPonct = sansArticle.replace(Regex("[:;,.!?–—-]"), " ").replace(Regex("\\s+"), " ").trim()
        val ascii = java.text.Normalizer.normalize(sansPonct, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val chiffres = sansPonct.split(' ').joinToString(" ") { CHIFFRES[it.lowercase()] ?: it }
        return listOf(base, sansAnnee, sansArticle, sansPonct, ascii, chiffres).filter { it.length >= 2 }.distinct()
    }

    private fun levenshtein(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prec = d[0]; d[0] = i
            for (j in 1..b.length) { val t = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, prec + if (a[i - 1] == b[j - 1]) 0 else 1); prec = t }
        }
        return d[b.length]
    }

    /** Ressemblance entre un média et le titre demandé (0 à 100). */
    internal fun score(item: JSONObject, demande: String): Int {
        val d = comparable(demande)
        if (d.isBlank()) return 0
        val mots = d.split(' ').filter { it.length >= 3 }.toSet()
        val titres = listOf("title", "originalTitle", "titleSort", "grandparentTitle").map { comparable(item.optString(it)) }.filter { it.isNotBlank() }
        val brut = titres.maxOfOrNull { x ->
            when {
                x == d -> 100
                x.startsWith("$d ") || (d.startsWith("$x ") && x.length >= 4) -> 85
                levenshtein(x, d) <= maxOf(1, minOf(x.length, d.length) / 6) -> 82
                d.length >= 4 && x.contains(d) -> 72
                x.length >= 5 && d.contains(x) -> 62
                else -> {
                    val mx = x.split(' ').filter { it.length >= 3 }.toSet()
                    if (mots.isEmpty() || mx.isEmpty()) 0 else (50 * (mots intersect mx).size / maxOf(mots.size, mx.size))
                }
            }
        } ?: 0
        val annee = Regex("(?:19|20)\\d{2}").find(demande)?.value
        return brut + (if (annee != null && item.optString("year") == annee) 5 else 0) +
            when (item.optString("type")) { "movie", "show" -> 3; "episode", "track" -> -5; else -> 0 }
    }

    data class Recherche(val meilleur: JSONObject?, val proches: List<JSONObject>)

    /**
     * Média le plus proche du titre demandé : recherche Plex avec plusieurs variantes du titre (article, accents, ponctuation,
     * année), puis recherche par bibliothèque. [strict] : seulement une vraie correspondance (pour une phrase ambiguë).
     */
    fun trouver(app: JarvisApp, titre: String, strict: Boolean = false, types: Set<String> = TYPES_MEDIA): Recherche {
        val t = titre.trim()
        if (t.isBlank()) return Recherche(null, emptyList())
        if (t.matches(Regex("\\d+"))) return Recherche(runCatching { metadata(app, t) }.getOrNull(), emptyList())
        val vus = linkedMapOf<String, JSONObject>()
        var premierPlex: JSONObject? = null
        fun ajouter(l: List<JSONObject>) = l.filter { it.optString("type") in types }.forEach { vus.putIfAbsent(it.optString("ratingKey").ifBlank { it.optString("key") }, it) }
        fun classement() = vus.values.map { it to score(it, t) }.sortedByDescending { it.second }
        val seuil = if (strict) 70 else 45
        val vars = variantes(t)
        for ((i, v) in vars.withIndex()) {
            val r = if (i == 0) rechercher(app, v) else runCatching { rechercher(app, v) }.getOrDefault(emptyList())
            if (i == 0) premierPlex = r.firstOrNull { it.optString("type") in types && it.optString("type") in setOf("movie", "show") }
            ajouter(r)
            classement().firstOrNull()?.takeIf { it.second >= 80 }?.let { return Recherche(it.first, emptyList()) }
        }
        classement().firstOrNull()?.takeIf { it.second >= seuil }?.let { return Recherche(it.first, emptyList()) }
        for (v in vars.drop(1).take(3).ifEmpty { vars }) ajouter(parBibliotheques(app, v))
        val c = classement()
        c.firstOrNull()?.takeIf { it.second >= seuil }?.let { return Recherche(it.first, emptyList()) }
        // Mots parasites en fin de titre (« les 101 dalmatiens depuis au assistant ») : on retire les derniers mots un à un.
        val mots = t.split(Regex("\\s+"))
        for (n in (mots.size - 1) downTo maxOf(1, mots.size - 5)) {
            val p = mots.take(n).joinToString(" ")
            if (comparable(p).length < 3) break
            val r = runCatching { rechercher(app, p) }.getOrDefault(emptyList()).filter { it.optString("type") in types }
            r.map { it to score(it, p) }.filter { it.second >= 80 }.maxByOrNull { it.second }?.let { return Recherche(it.first, emptyList()) }
            ajouter(r)
        }
        // Titre demandé explicitement : la recherche floue de Plex (fautes de dictée) a le dernier mot.
        if (!strict && premierPlex != null) return Recherche(premierPlex, emptyList())
        return Recherche(null, c.filter { it.second >= 20 }.take(4).map { it.first })
    }

    /** Média désigné par identifiant (ratingKey) ou par titre (le plus proche). */
    fun media(app: JarvisApp, titreOuId: String): JSONObject {
        val t = titreOuId.trim()
        if (t.isBlank()) throw IllegalStateException("Précise le titre du film ou de la série.")
        if (t.matches(Regex("\\d+"))) return metadata(app, t)
        val r = trouver(app, t)
        return r.meilleur ?: throw IllegalStateException(introuvable(app, t, r.proches))
    }

    /** Message clair quand un titre n'est pas trouvé : titres proches, ou bibliothèques du serveur joint. */
    fun introuvable(app: JarvisApp, t: String, proches: List<JSONObject>): String {
        if (proches.isNotEmpty()) return "Je ne trouve pas « $t » dans Plex. Le plus proche : ${proches.joinToString(", ") { "« ${titre(it)} »" }}."
        val nom = runCatching { serveur(app).optString("friendlyName") }.getOrDefault("")
        val biblio = runCatching { sections(app) }.getOrDefault(emptyList())
        return if (biblio.isEmpty()) "Je ne trouve pas « $t » : le serveur Plex joint${if (nom.isNotBlank()) " (« $nom »)" else ""} n'a aucune bibliothèque visible — ce n'est sans doute pas le bon serveur, ou le jeton n'y donne pas accès (${runCatching { chemin(app) }.getOrDefault("adresse inconnue")})."
            else "Je ne trouve pas « $t » dans Plex${if (nom.isNotBlank()) " (serveur « $nom »)" else ""}. Bibliothèques : ${biblio.joinToString(", ") { it.optString("title") }}."
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

    // ------------------------------------------------------------------ lecteurs (tous les appareils), lecture partout

    /** Un lecteur Plex : vu par le serveur (réseau de la maison), en lecture, ou déclaré sur le compte plex.tv. */
    data class Lecteur(val id: String, val nom: String, val produit: String, val plateforme: String, val sources: Set<String>,
                       val enLigne: Boolean, val maison: Boolean?, val adresse: String) {
        val libelle get() = nom.ifBlank { produit.ifBlank { "lecteur Plex" } }
    }

    /** « ce téléphone » : la lecture s'ouvre dans l'appli Plex de CE téléphone (pas besoin qu'il soit déclaré comme lecteur). */
    const val CE_TELEPHONE = "ce_telephone"
    private val R_CE_TELEPHONE = Regex("^(ce|mon|le) (telephone|portable|smartphone|tel)$|^ici$|^(sur )?moi$")

    private fun ressources(app: JarvisApp): List<JSONObject> = runCatching {
        val a = JSONArray(request(app, "https://plex.tv/api/v2/resources", mapOf("includeHttps" to "1", "includeRelay" to "1", "includeIPv6" to "0")))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }.getOrDefault(emptyList())

    /**
     * Tous les lecteurs : ceux que le serveur voit sur le réseau de la maison (/clients), ceux qui lisent en ce moment
     * (/status/sessions) et ceux du compte plex.tv (télé, téléphones, ordinateurs, même hors de la maison).
     */
    fun lecteurs(app: JarvisApp): List<Lecteur> {
        val out = linkedMapOf<String, Lecteur>()
        fun ajouter(l: Lecteur) {
            if (l.id.isBlank()) return
            val ex = out[l.id]
            out[l.id] = if (ex == null) l else ex.copy(nom = ex.nom.ifBlank { l.nom }, produit = ex.produit.ifBlank { l.produit }, plateforme = ex.plateforme.ifBlank { l.plateforme },
                sources = ex.sources + l.sources, enLigne = ex.enLigne || l.enLigne, maison = ex.maison ?: l.maison, adresse = ex.adresse.ifBlank { l.adresse })
        }
        runCatching { appareils(app) }.getOrNull()?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { d ->
            ajouter(Lecteur(d.optString("clientIdentifier").ifBlank { d.optString("machineIdentifier") }, d.optString("name"), d.optString("product"),
                d.optString("platform"), setOf("réseau de la maison"), true, true, d.optString("address")))
        } }
        runCatching { liste(mc(app, "/status/sessions")) }.getOrDefault(emptyList()).forEach { s ->
            val p = s.optJSONObject("Player") ?: return@forEach
            ajouter(Lecteur(p.optString("machineIdentifier"), p.optString("title"), p.optString("product"), p.optString("platform"),
                setOf("en lecture"), true, if (p.has("local")) p.optBoolean("local") else null, p.optString("address")))
        }
        val res = ressources(app)
        val ipServeur = res.firstOrNull { it.optString("provides").contains("server") && it.optString("clientIdentifier") == runCatching { machineId(app) }.getOrNull() }?.optString("publicAddress").orEmpty()
        res.filter { r -> r.optString("provides").split(',').any { it.trim() in setOf("player", "client") } && !r.optString("provides").contains("server") }.forEach { r ->
            ajouter(Lecteur(r.optString("clientIdentifier"), r.optString("name"), r.optString("product"), r.optString("platform").ifBlank { r.optString("device") },
                setOf("compte plex.tv"), r.optBoolean("presence", false), if (ipServeur.isNotBlank() && r.optString("publicAddress").isNotBlank()) r.optString("publicAddress") == ipServeur else null, r.optString("publicAddress")))
        }
        return out.values.toList()
    }

    /** Le lecteur qui lit en ce moment (s'il n'y en a qu'un) : « mets le film en pause » sans dire où. */
    fun lecteurEnLecture(app: JarvisApp): Lecteur? = runCatching { liste(mc(app, "/status/sessions")).mapNotNull { it.optJSONObject("Player") } }.getOrDefault(emptyList())
        .distinctBy { it.optString("machineIdentifier") }.singleOrNull()?.let { p ->
            Lecteur(p.optString("machineIdentifier"), p.optString("title"), p.optString("product"), p.optString("platform"), setOf("en lecture"), true, null, p.optString("address"))
        }

    private fun aliasMap(app: JarvisApp) = runCatching { JSONObject(app.store.plexAlias.value) }.getOrDefault(JSONObject())

    /** Donne un nom à un lecteur (« télé du salon », « téléphone de Marie »…). */
    fun nommer(app: JarvisApp, lecteur: Lecteur, alias: String) {
        val m = aliasMap(app)
        m.put(fr.dd.jarvis.societe.core.normaliser(alias), lecteur.id)
        app.store.plexAlias.set(m.toString())
    }

    private val R_TELE = Regex("\\b(tele|tv|televiseur|television|salon)\\b")
    private val R_TEL = Regex("\\b(telephone|portable|smartphone|mobile|tel)\\b")
    private val R_PC = Regex("\\b(ordinateur|ordi|pc|mac|laptop|portable pc|navigateur|web)\\b")

    /** Lecteur désigné par son nom, un nom donné, ou son genre (télé, téléphone, ordinateur) ; « ce téléphone » = ce téléphone. */
    fun lecteur(app: JarvisApp, nom: String): Lecteur {
        val n = fr.dd.jarvis.societe.core.normaliser(nom)
        if (R_CE_TELEPHONE.containsMatchIn(n)) return Lecteur(CE_TELEPHONE, "ce téléphone", "Plex", "Android", setOf("ce téléphone"), true, null, "")
        val tous = lecteurs(app)
        aliasMap(app).let { m -> m.keys().asSequence().firstOrNull { k -> n == k || n.contains(k) }?.let { k -> tous.firstOrNull { it.id == m.optString(k) }?.let { return it } } }
        fun norm(l: Lecteur) = fr.dd.jarvis.societe.core.normaliser("${l.nom} ${l.produit} ${l.plateforme}")
        tous.firstOrNull { fr.dd.jarvis.societe.core.normaliser(it.nom) == n }?.let { return it }
        tous.filter { n.isNotBlank() && (fr.dd.jarvis.societe.core.normaliser(it.nom).contains(n) || n.contains(fr.dd.jarvis.societe.core.normaliser(it.nom).ifBlank { "\u0000" })) }
            .let { if (it.size == 1) return it[0] }
        val genre = when {
            R_TELE.containsMatchIn(n) -> tous.filter { Regex("tv|tvos|webos|tizen|roku|shield|bravia|fire|android tv|smart", RegexOption.IGNORE_CASE).containsMatchIn(norm(it)) }
            R_PC.containsMatchIn(n) -> tous.filter { Regex("windows|macos|linux|web|htpc|desktop|chrome|firefox|safari", RegexOption.IGNORE_CASE).containsMatchIn(norm(it)) }
            R_TEL.containsMatchIn(n) -> tous.filter { Regex("mobile|ios|iphone|android", RegexOption.IGNORE_CASE).containsMatchIn(norm(it)) && !Regex("tv", RegexOption.IGNORE_CASE).containsMatchIn(norm(it)) }
            else -> emptyList()
        }.sortedByDescending { it.enLigne }
        if (genre.size == 1 || (genre.isNotEmpty() && genre.count { it.enLigne } == 1)) return genre.first()
        if (n.isBlank()) {
            // Aucun lecteur précisé : le seul en ligne, sinon celui qui lit déjà, sinon la seule télé en ligne.
            tous.filter { it.enLigne }.singleOrNull()?.let { return it }
            lecteurEnLecture(app)?.let { p -> return tous.firstOrNull { it.id == p.id } ?: p }
            tous.filter { it.enLigne && Regex("tv|tvos|webos|tizen|roku|shield|bravia|fire|android tv|smart", RegexOption.IGNORE_CASE).containsMatchIn(norm(it)) }.singleOrNull()?.let { return it }
        }
        throw IllegalStateException(
            (if (genre.size > 1) "Plusieurs lecteurs correspondent à « $nom » : ${genre.joinToString { it.libelle }}. Précise lequel (ou donne-lui un nom avec plex_appareils action=nommer)."
            else if (n.isBlank()) "Sur quel lecteur Plex ? (ex. « lance … sur la télé du salon »)."
            else "Lecteur Plex « $nom » introuvable.") +
                (if (tous.isNotEmpty()) " Lecteurs connus : ${tous.joinToString { it.libelle + if (it.enLigne) "" else " (hors ligne)" }}." else " Aucun lecteur visible.") +
                " Sur la télé / l'autre téléphone, l'appli Plex doit être ouverte, connectée au même compte, avec « Annoncer comme lecteur » (Réglages Plex › Avancé) activé.")
    }

    /** Adresse IP locale du serveur Plex joint en ce moment (null s'il est joint à distance). */
    fun ipLan(app: JarvisApp): String? = runCatching { java.net.URI(base(app)).host }.getOrNull()?.takeIf { Serveur.ipPrivee(it) }

    /**
     * Pourquoi un lecteur de la maison serait vu « à distance » par Plex (et l'abonnement demandé) : adresse locale
     * annoncée par le serveur différente de son adresse réelle (Plex en Docker « bridge »), réseau de la maison absent des
     * « réseaux locaux » du serveur. Liste vide = rien de suspect.
     */
    fun problemesReseauLocal(app: JarvisApp): List<String> {
        val lan = ipLan(app) ?: return listOf("Le téléphone ne joint pas le serveur Plex sur le réseau de la maison (connexion distante) : vérifie qu'il est sur le même Wi-Fi.")
        val reseau = lan.substringBeforeLast('.') + "."
        val out = mutableListOf<String>()
        val machine = runCatching { machineId(app) }.getOrNull()
        val srv = ressources(app).firstOrNull { it.optString("clientIdentifier") == machine }
        val locales = srv?.optJSONArray("connections")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty().filter { it.optBoolean("local") }.map { it.optString("address") }
        if (locales.isNotEmpty() && locales.none { it == lan })
            out += "Plex annonce l'adresse locale ${locales.joinToString()} au lieu de $lan : les applis ne le trouvent pas sur le réseau de la maison et passent par internet (typique d'un Plex en Docker en mode « bridge »)."
        val prefs = runCatching { liste(mc(app, "/:/prefs"), "Setting").associate { it.optString("id") to it.optString("value") } }.getOrDefault(emptyMap())
        if (prefs.isNotEmpty()) {
            if (!prefs["customConnections"].orEmpty().contains(lan)) out += "L'adresse locale du serveur (http://$lan:$PORT) n'est pas déclarée dans « URL personnalisées d'accès au serveur »."
            if (!prefs["LanNetworksBandwidth"].orEmpty().contains(reseau)) out += "Le réseau de la maison (${reseau}0) n'est pas déclaré dans « Réseaux LAN » du serveur."
        }
        return out
    }

    /** Déclare l'adresse locale du serveur et le réseau de la maison dans les réglages réseau de Plex (ajout, rien n'est retiré). */
    fun reparerReseauLocal(app: JarvisApp): String {
        val lan = ipLan(app) ?: throw IllegalStateException("Le téléphone doit être sur le Wi-Fi de la maison pour réparer la lecture locale.")
        val reseau = lan.substringBeforeLast('.') + ".0/255.255.255.0"
        val prefs = liste(mc(app, "/:/prefs"), "Setting").associate { it.optString("id") to it.optString("value") }
        val url = "http://$lan:$PORT"
        val q = linkedMapOf<String, String>()
        prefs["customConnections"].orEmpty().let { v -> if (!v.contains(lan)) q["customConnections"] = listOf(v, url).filter { it.isNotBlank() }.joinToString(",") }
        prefs["LanNetworksBandwidth"].orEmpty().let { v -> if (!v.contains(lan.substringBeforeLast('.') + ".")) q["LanNetworksBandwidth"] = listOf(v, reseau).filter { it.isNotBlank() }.joinToString(",") }
        if (q.isEmpty()) return "✅ L'adresse locale ($url) et le réseau de la maison sont déjà déclarés dans Plex."
        request(app, "/:/prefs", q, methode = "PUT")
        return "✅ Plex : " + q.keys.joinToString(" et ") { if (it == "customConnections") "adresse locale $url déclarée" else "réseau de la maison ${reseau.substringBefore('/')} déclaré comme réseau local" } +
            ". Ferme puis rouvre l'appli Plex sur la télé et relance le film."
    }

    /** Adresse du serveur que le lecteur doit utiliser : réseau de la maison, sinon connexion distante de plex.tv (ou relais). */
    private fun connexionPour(app: JarvisApp, l: Lecteur): Triple<String, Int, String> {
        // Adresse du serveur que JARVIS joint vraiment (celui qui a les bibliothèques), pas le premier appareil du Wi-Fi
        // qui répond sur 32400 : une mauvaise adresse fait passer la télé par la connexion distante (abonnement demandé).
        val lan = ipLan(app) ?: ipsLocales(app).firstOrNull() ?: Serveur.ipLocaleServeur(app)?.also { app.store.serveurIpLocale.set(it) }
        if (l.maison != false && lan != null) return Triple(lan, PORT, "http")
        val machine = machineId(app)
        val srv = ressources(app).firstOrNull { it.optString("clientIdentifier") == machine }
        val cx = srv?.optJSONArray("connections")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        val c = cx.firstOrNull { !it.optBoolean("local") && !it.optBoolean("relay") } ?: cx.firstOrNull { it.optBoolean("relay") }
        if (c != null) {
            val u = runCatching { c.optString("uri").toHttpUrl() }.getOrNull()
            if (u != null) return Triple(u.host, u.port, u.scheme)
        }
        return Triple(lan ?: throw IllegalStateException("Adresse du serveur Plex introuvable (ni locale ni distante)."), PORT, "http")
    }

    /** File de lecture (exigée par les applis Plex récentes pour démarrer une lecture à distance). */
    private fun fileLecture(app: JarvisApp, machine: String, media: JSONObject): String? = runCatching {
        val type = if (media.optString("type") in setOf("track", "album", "artist")) "audio" else "video"
        val o = JSONObject(request(app, "/playQueues", mapOf("type" to type, "shuffle" to "0", "repeat" to "0", "continuous" to "1", "own" to "1",
            "uri" to "server://$machine/com.plexapp.plugins.library/library/metadata/${media.optString("ratingKey")}"), methode = "POST"))
        (o.optJSONObject("MediaContainer") ?: o).optString("playQueueID").ifBlank { null }
    }.getOrNull()

    /** Lance [media] sur [l] (n'importe quel lecteur, à la maison ou à distance ; « ce téléphone » : appli Plex locale). */
    fun lancerSur(app: JarvisApp, media: JSONObject, l: Lecteur, offsetMs: Long = 0): String {
        val ratingKey = media.optString("ratingKey").ifBlank { throw IllegalStateException("Plex n'a pas fourni l'identifiant de ce média.") }
        val machine = machineId(app)
        if (l.id == CE_TELEPHONE) {
            val plexApp = "com.plexapp.android"
            val installee = runCatching { app.packageManager.getPackageInfo(plexApp, 0) }.isSuccess
            val nt = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            if (installee) {
                // Lien de l'appli Plex (lecture directe) : l'adresse app.plex.tv n'est pas gérée par l'appli et ouvrait le site.
                val suite = if (media.optString("type") in setOf("show", "season")) "/children" else ""
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("plex://server://$machine/com.plexapp.plugins.library/library/metadata/$ratingKey$suite"))
                    .setPackage(plexApp).putExtra("android.intent.extra.START_PLAYBACK", true).addFlags(nt)
                if (runCatching { app.startActivity(i) }.isSuccess) return "📱 « ${titre(media)} » lancé dans l'appli Plex de ce téléphone."
                app.packageManager.getLaunchIntentForPackage(plexApp)?.let { runCatching { app.startActivity(it.addFlags(nt)) } }
                return "📱 Appli Plex ouverte : cherche « ${titre(media)} » (elle n'a pas accepté l'ouverture directe du film)."
            }
            val lien = "https://app.plex.tv/desktop/#!/server/$machine/details?key=" + java.net.URLEncoder.encode("/library/metadata/$ratingKey", "UTF-8")
            app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(lien)).addFlags(nt))
            return "📱 L'appli Plex n'est pas installée : « ${titre(media)} » est ouvert sur le site Plex."
        }
        val (adresse, port, proto) = connexionPour(app, l)
        val jeton = runCatching { mc(app, "/security/token", mapOf("type" to "delegation", "scope" to "all")).optString("token") }.getOrNull().orEmpty().ifBlank { token(app) }
        val q = linkedMapOf(
            "key" to "/library/metadata/$ratingKey", "machineIdentifier" to machine, "address" to adresse, "port" to port.toString(),
            "protocol" to proto, "token" to jeton, "offset" to offsetMs.toString(),
            "commandID" to (System.currentTimeMillis() % Int.MAX_VALUE).toString(),
            "type" to if (media.optString("type") in setOf("track", "album", "artist")) "music" else "video")
        fileLecture(app, machine, media)?.let { q["containerKey"] = "/playQueues/$it?window=100&own=1" }
        try { request(app, "/player/playback/playMedia", q, l.id) }
        catch (e: Exception) {
            throw IllegalStateException("${l.libelle} n'a pas reçu la commande (${e.message?.take(120)}). L'appli Plex doit y être ouverte, connectée au même compte, avec « Annoncer comme lecteur » activé" +
                (if (!l.enLigne) " — ce lecteur est actuellement hors ligne." else "."))
        }
        val alerte = if (l.maison != false) runCatching { problemesReseauLocal(app) }.getOrDefault(emptyList()) else emptyList()
        // Vérification réelle : la lecture doit apparaître dans les sessions du serveur Plex sur CE lecteur.
        val session = attendreSession(app, l.id, 15_000) { it != null && it.optString("ratingKey") == ratingKey }
        val ailleurs = if (session == null) sessionSur(app, l.id) else null
        val suite = (if (offsetMs > 0) " (reprise à ${duree(offsetMs)})" else "") + (if (proto == "https") " — flux par la connexion distante du serveur." else ".")
        val conseil = if (alerte.isNotEmpty()) "\n⚠️ Si la télé demande un abonnement (« lecture à distance ») : " + alerte.joinToString(" ") + " Dis « répare la lecture locale de Plex »." else ""
        return when {
            session != null -> "▶️ « ${titre(media)} » en lecture sur ${l.libelle}$suite (confirmé par Plex : ${etatLecture(session)})$conseil"
            ailleurs != null -> "⚠️ Commande reçue par ${l.libelle}, mais il lit autre chose (« ${titre(ailleurs)} ») : « ${titre(media)} » n'a pas démarré.$conseil"
            else -> "⏳ « ${titre(media)} » envoyé à ${l.libelle}$suite Plex ne montre pas encore la lecture au bout de 15 s : l'appli Plex de la télé doit être ouverte et active " +
                "(un écran « Reprendre / Depuis le début » peut attendre une touche). Ne dis pas que la lecture a démarré.$conseil"
        }
    }

    /** Session de lecture en cours sur le lecteur [idLecteur] (identifiant de machine), ou null. */
    fun sessionSur(app: JarvisApp, idLecteur: String): JSONObject? = runCatching { liste(mc(app, "/status/sessions")) }.getOrDefault(emptyList())
        .firstOrNull { it.optJSONObject("Player")?.optString("machineIdentifier") == idLecteur }

    /** Relit les sessions Plex (toutes les 1,5 s, jusqu'à [ms]) jusqu'à ce que [ok] soit vrai ; la session trouvée, ou null. */
    fun attendreSession(app: JarvisApp, idLecteur: String, ms: Long, ok: (JSONObject?) -> Boolean): JSONObject? {
        val fin = System.currentTimeMillis() + ms
        while (true) {
            val s = sessionSur(app, idLecteur)
            if (ok(s)) return s ?: JSONObject()
            if (System.currentTimeMillis() >= fin) return null
            Thread.sleep(1_500)
        }
    }

    /** « lecture en cours », « en pause », « chargement ». */
    fun etatLecture(session: JSONObject): String = when (session.optJSONObject("Player")?.optString("state")) {
        "playing" -> "lecture en cours"; "paused" -> "en pause"; "buffering" -> "chargement"; else -> "session ouverte"
    }

    /**
     * Commande de lecture (pause, lecture, stop, suivant…) bien appliquée ? [avant] / [apres] : sessions du lecteur avant
     * et après. null = pas vérifiable par les sessions (volume).
     */
    internal fun commandeAppliquee(commande: String, avant: JSONObject?, apres: JSONObject?): Boolean? {
        val etat = apres?.optJSONObject("Player")?.optString("state")
        return when (commande) {
            "pause" -> etat == "paused"
            "lecture", "play", "reprendre" -> etat == "playing" || etat == "buffering"
            "stop", "arreter" -> apres == null
            "suivant", "precedent" -> apres != null && avant != null && apres.optString("ratingKey") != avant.optString("ratingKey")
            "avancer", "reculer", "aller_a" -> apres != null && avant != null && apres.optLong("viewOffset") != avant.optLong("viewOffset")
            else -> null
        }
    }

    fun lancer(app: JarvisApp, media: JSONObject, appareil: JSONObject, offsetMs: Long = 0): String {
        val ratingKey = media.optString("ratingKey").ifBlank { throw IllegalStateException("Plex n'a pas fourni l'identifiant de ce média.") }
        val machineId = machineId(app)
        val adresse = ipsLocales(app).firstOrNull() ?: Serveur.ipLocaleServeur(app) ?: throw IllegalStateException("Impossible de déterminer l'adresse locale du serveur Debian pour transmettre le flux au lecteur.")
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
        val KEYWORDS = Regex("\\bplex\\b|biblioth[eè]que plex|lecteur plex|\\bplaylists?\\b|liste de lecture|on deck|\\bfilms?\\b|\\bs[ée]ries?\\b|[ée]pisode|reprendre (le|mon) film|sur (la |le )?(t[ée]l[ée]|tv)\\b", RegexOption.IGNORE_CASE)
        fun concerned(text: String) = KEYWORDS.containsMatchIn(text)

        private val PAS_PLEX = Regex("\\b(musique|chanson|album|playlist|radio|spotify|deezer|youtube|netflix|prime video|disney|canal|molotov|tf1|france [2-5]|m6|arte|chaine)\\b")
        private val MOT_PLEX = Regex("\\b(plex|film|films|serie|series|episode|saison)\\b")
        private val ECRAN = Regex("\\bsur (la |le |l |mon |ma )?(tele|tv|television|televiseur|salon|chromecast|shield|ordi|ordinateur|pc|tablette|telephone|portable)\\b")
        private val POLI = Regex("^(?:(?:jarvis|alors|ok|bon|est ce que|tu peux|peux tu|pourrais tu|tu pourrais|s il te plait|stp)\\s+)+")

        /**
         * Commandes Plex reconnues sans IA (directement par l'API de Plex, sans Home Assistant ni Hermès) :
         * « lance Inception sur la télé », « mets la série Lupin sur le téléphone de Marie », « mets le film en pause »,
         * « reprends le film sur la télé », « arrête le film ». Renvoie (outil, arguments) ou null.
         */
        fun intention(texte: String): Pair<String, JSONObject>? {
            val brut = fr.dd.jarvis.societe.core.normaliser(texte).trim()
            // « Répare la lecture locale de Plex », « pourquoi Plex me demande un abonnement sur la télé ? ».
            if (Regex("\\bplex\\b").containsMatchIn(brut) && Regex("\\b(abonnement|plex pass|remote watch|a distance|acces distant|lecture locale|pass)\\b").containsMatchIn(brut))
                return "plex_serveur" to JSONObject().put("action",
                    if (Regex("\\b(repare|reparer|corrige|corriger|regle|regler|resous|resoudre|arrange|arranger)\\b").containsMatchIn(brut)) "reparer_local" else "reseau_local")
            // « Regarde l'état du serveur Plex sur le serveur SSH », « regarde pour des problèmes de Plex » : un DIAGNOSTIC,
            // pas un film à lancer (« regarde » voulait dire « regarder un film »). Problème, panne, SSH, logs : diagnostic
            // complet sur le serveur (conteneur, ressources, journaux, API) ; simple état / version : l'API de Plex.
            val sansPolitesse = brut.replace(POLI, "").trim()
            if (Regex("\\bplex\\b").containsMatchIn(brut) && DIAG.containsMatchIn(brut) && !LANCEMENT.containsMatchIn(sansPolitesse))
                return if (HOTE.containsMatchIn(brut)) "serveur_diagnostic" to JSONObject().put("cible", "plex") else "plex_serveur" to JSONObject().put("action", "etat")
            if (Regex("^(?:jarvis )?(qu est ce qui|qu est ce qu il y a|qu y a t il|quoi|quels?|quelles?|qui|combien|y a t il|liste|montre|est ce qu il y a|est ce que (?!tu |vous ))\\b").containsMatchIn(brut)) return null
            val n = sansPolitesse
            if (n.isBlank() || n.split(' ').size > 16 || PAS_PLEX.containsMatchIn(n)) return null
            val plex = MOT_PLEX.containsMatchIn(n)
            val appareil = Regex("\\bsur (.+)$").find(n)?.groupValues?.get(1)?.trim().orEmpty().removePrefix("plex").trim()
            // Télécommande.
            val cmd = when {
                Regex("\\b(mets?|met|fais) (le film |la serie |l episode |plex |la lecture |ca )?(en )?pause\\b|^pause (le film|la serie|l episode|plex)\\b").containsMatchIn(n) -> "pause"
                Regex("^(reprends?|reprendre|relance|continue|remets?) (le film|la serie|l episode|la lecture|plex)\\b").containsMatchIn(n) -> "lecture"
                Regex("^(arrete|stoppe|stop|coupe|ferme) (le film|la serie|l episode|la lecture|plex)\\b").containsMatchIn(n) -> "stop"
                Regex("^(episode suivant|passe a l episode suivant|l episode suivant)\\b").containsMatchIn(n) -> "suivant"
                Regex("^(avance|avancer) (le film|la serie|l episode)\\b").containsMatchIn(n) -> "avancer"
                Regex("^(recule|reculer) (le film|la serie|l episode)\\b").containsMatchIn(n) -> "reculer"
                else -> null
            }
            if (cmd != null && (plex || ECRAN.containsMatchIn(n))) return "plex_controle" to JSONObject().put("commande", cmd).put("appareil", if (ECRAN.containsMatchIn(n) || appareil.isNotBlank()) appareil else "")
            // Lancement : le verbe ouvre la phrase (« je regarde un film ce soir, mets le mode cinéma » n'est pas une commande Plex).
            // Le titre est pris dans le texte d'origine (accents, majuscules) pour la recherche Plex.
            val ecran = ECRAN.containsMatchIn(n)
            if (!plex && !ecran) return null
            if (Regex("\\b(si|est ce que|existe|disponible|dispo|dans plex|dans ma bibliotheque|ai je|j ai|combien|quels?|quelles?|liste|cherche|trouve|recherche)\\b").containsMatchIn(n)) return null
            if (!VERBE.containsMatchIn(n)) return null
            val o = texte.trim().replace(Regex("[?!.]+$"), "").replace(Regex("(?i)[,\\s]+(?:s['’ ]il te pla[iî]t|stp|merci|jarvis|please)\\s*$"), "")
            val m = Regex("(?i)(?:^|\\s)(?:lance[rz]?|mets?|mettre|joue[rz]?|regarde[rz]?|d[ée]marre[rz]?|passe[rz]?)(?:[- ](?:moi|nous))?\\s+" +
                "((?:le|un) film\\s+|la s[ée]rie\\s+|le dessin anim[ée]\\s+|le documentaire\\s+|sur plex\\s+)?(.+?)(?:\\s+sur\\s+(?:plex\\s+sur\\s+)?(.+))?$").find(o) ?: return null
            val c = cible(m.groupValues[2].trim())
            val titre = c.titre
            if (titre.length < 2 || Regex("(?i)^(un film|le film|la s[ée]rie|une s[ée]rie|plex|quelque chose|sur|en|au|à|a|dans)(\\s|$)").containsMatchIn(titre)) return null
            val marque = m.groupValues[1].isNotBlank() && !m.groupValues[1].trim().equals("sur plex", true)
            val titreNorm = fr.dd.jarvis.societe.core.normaliser(titre)
            // « Regarde pour le partage Freebox avec le serveur Plex », « regarde si… » : une question ou une vérification,
            // pas un titre. « Regarde » (vérifier / regarder un film) ne lance que « regarde le film X / la série X ».
            if (!marque && (NON_TITRE.containsMatchIn(titreNorm) || SUJET_TECHNIQUE.containsMatchIn(titreNorm))) return null
            if (!marque && Regex("^(?:(?:je (?:veux|voudrais)|tu peux|peux tu|on)\\s+)?regarde[rz]?\\b").containsMatchIn(n)) return null
            val explicite = marque || c.saison != null || c.episode != null || Regex("\\bplex\\b").containsMatchIn(n)
            if (!explicite && PAS_TITRE.containsMatchIn(titreNorm)) return null
            val lecteur = m.groupValues[3].trim().let { if (fr.dd.jarvis.societe.core.normaliser(it) == "plex") "" else it }
            return "plex_lancer" to JSONObject().put("titre", titre).put("appareil", lecteur).apply {
                c.saison?.let { put("saison", it.toString()) }
                c.episode?.let { put("episode", it) }
                if (!explicite) put("verifier", "oui")
            }
        }

        /** Début de « titre » qui trahit une question (« pour le partage… », « si le film… », « comment… »). */
        private val NON_TITRE = Regex("^(pour|si|comment|pourquoi|quand|est ce|ce qui|ce que|les? problemes?|l etat|la raison|qu il|qu elle)\\b")
        /** Sujets techniques : jamais le titre d'un film à lancer sans « le film / la série » devant. */
        private val SUJET_TECHNIQUE = Regex("\\b(freebox|box|serveur|partage|partager|reseau|wi ?fi|config\\w*|reglages?|ssh|docker|conteneur|disque|dossiers?|bibliotheques?|droits|acces|connexion|jeton|token|mise a jour)\\b")

        /** État, problème ou diagnostic du serveur Plex (jamais un titre à lancer). */
        private val DIAG = Regex("\\b(etat|statut|status|probleme|problemes|soucis?|pannes?|bugs?|erreurs?|plante|plantage|lent|lenteurs?|marche pas|marche plus|fonctionne pas|fonctionne plus|" +
            "diagnosti\\w*|ssh|debian|docker|conteneur|logs?|journal|journaux|redemarr\\w*|mise a jour|mettre a jour|version|transcod\\w*|espace disque|en ligne|joignable|accessible)\\b")
        /** Ce qui demande de regarder le serveur lui-même (SSH : conteneur, ressources, journaux), pas seulement l'API de Plex. */
        private val HOTE = Regex("\\b(probleme|problemes|soucis?|pannes?|bugs?|erreurs?|plante|plantage|lent|lenteurs?|marche pas|marche plus|fonctionne pas|fonctionne plus|" +
            "diagnosti\\w*|ssh|debian|docker|conteneur|logs?|journal|journaux|joignable|accessible|en ligne)\\b")
        /** Lancer un média (« mets / lance / joue … ») : jamais un diagnostic, même si le titre contient « erreur ». */
        private val LANCEMENT = Regex("^(?:(?:je (?:veux|voudrais)|tu peux|peux tu|on)\\s+)?(?:lance[rz]?|mets?|mettre|joue[rz]?|demarre[rz]?|passe[rz]?)\\b")

        private val VERBE = Regex("^(?:(?:je (?:veux|voudrais|souhaite|aimerais)|j aimerais|on (?:va|peut|pourrait)|on|tu peux|peux tu|pourrais tu|veux tu|voudrais tu)\\s+)?" +
            "(?:(?:me|nous)\\s+)?(?:lance[rz]?|mets?|mettre|joue[rz]?|regarde[rz]?|demarre[rz]?|passe[rz]?)\\b")
        /** Mots qui montrent qu'une phrase « mets … » vise autre chose qu'un titre (rappel, mode, lumière, heure…). */
        private val PAS_TITRE = Regex("\\b(rappels?|rappelle|alarmes?|reveils?|minuteurs?|timer|mode|lumieres?|lampes?|chauffage|poeles?|volume|musique|meteo|rendez vous|rdv|liste|notes?|messages?|sms|mails?|appels?|demain|ce soir|tout a l heure|apres midi|ce matin|heures?|minutes?|\\d{1,2} ?h(?: ?\\d{2})?)\\b")

        private const val NB = "(\\d+|une?|premier|premiere|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|treize|quatorze|quinze|seize|vingt)"
        private val NOMBRES = mapOf("un" to 1, "une" to 1, "premier" to 1, "premiere" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7,
            "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16, "vingt" to 20)
        private fun nb(s: String): Int? = s.trim().toIntOrNull() ?: NOMBRES[fr.dd.jarvis.societe.core.normaliser(s)]

        /** Titre demandé et épisode visé : « Lupin saison 2 épisode 3 », « l'épisode 3 de la saison 2 de Lupin », « le dernier épisode de X »… */
        internal data class Cible(val titre: String, val saison: Int?, val episode: String?)

        internal fun cible(brut: String): Cible {
            // « … depuis Plex », « … via l'appli », « … avec Google Assistant » : pas dans le titre.
            val t = brut.trim().replace('’', '\'')
                .replace(Regex("(?i)\\s+(?:depuis|via)\\s+.*$"), "")
                .replace(Regex("(?i)\\s+(?:avec|dans|par|sur|à partir de|a partir de)\\s+(?:l'|le |la |mon |ma )?(?:plex|appli(?:cation)?|assistant|google|jarvis|au assistant|l assistant)\\b.*$"), "")
                .trim()
            val DE = "\\s+(?:de\\s+(?:la\\s+s[ée]rie\\s+)?|d'\\s*)"
            val EP = "(?:l'\\s*)?[ée]pisodes?"
            fun nettoie(x: String) = x.trim().removePrefix("la série ").removePrefix("la serie ").removePrefix("le film ").trim().trim(',').trim()
            fun r(p: String) = Regex("(?i)$p").find(t)?.groupValues
            r("^$EP\\s+$NB\\s+(?:de\\s+la\\s+)?saison\\s+$NB$DE(.+)$")?.let { return Cible(nettoie(it[3]), nb(it[2]), nb(it[1])?.toString()) }
            r("^(?:la\\s+)?saison\\s+$NB(?:\\s*,?\\s*$EP\\s+$NB)?$DE(.+)$")?.let { return Cible(nettoie(it[3]), nb(it[1]), it[2].takeIf { e -> e.isNotBlank() }?.let { e -> nb(e)?.toString() }) }
            r("^(?:le\\s+)?dernier\\s+[ée]pisode(?:\\s+de\\s+la\\s+saison\\s+$NB)?$DE(.+)$")?.let { return Cible(nettoie(it[2]), it[1].takeIf { x -> x.isNotBlank() }?.let { x -> nb(x) }, "dernier") }
            r("^(?:le\\s+)?premier\\s+[ée]pisode$DE(.+)$")?.let { return Cible(nettoie(it[1]), 1, "1") }
            r("^(?:un\\s+[ée]pisode|le\\s+prochain\\s+[ée]pisode|l'\\s*[ée]pisode\\s+suivant|la\\s+suite|un\\s+nouvel\\s+[ée]pisode)$DE(.+)$")?.let { return Cible(nettoie(it[1]), null, "") }
            r("^$EP\\s+$NB$DE(.+)$")?.let { return Cible(nettoie(it[2]), null, nb(it[1])?.toString()) }
            r("^(.+?)\\s*,?\\s+saison\\s+$NB(?:\\s*,?\\s*$EP\\s+$NB)?$")?.let { return Cible(nettoie(it[1]), nb(it[2]), it[3].takeIf { e -> e.isNotBlank() }?.let { e -> nb(e)?.toString() }) }
            r("^(.+?)\\s+s(\\d{1,2})\\s*e(\\d{1,3})$")?.let { return Cible(nettoie(it[1]), it[2].toInt(), it[3].toInt().toString()) }
            r("^(.+?)\\s*,?\\s+$EP\\s+$NB$")?.let { return Cible(nettoie(it[1]), null, nb(it[2])?.toString()) }
            r("^(.+?)\\s*,?\\s+(?:le\\s+)?dernier\\s+[ée]pisode$")?.let { return Cible(nettoie(it[1]), null, "dernier") }
            return Cible(nettoie(t), null, null)
        }
    }

    private fun prop(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun tool(name: String, description: String, props: Map<String, JSONObject>, required: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
                .put("required", JSONArray(required))))

    val schema = listOf(
        tool("plex_rechercher", "Plex (INSTANCE Plex Media Server, par son API — pas le serveur Debian) : recherche un film, une série, un épisode, un artiste… dans les bibliothèques du serveur (lecture seule).",
            mapOf("titre" to prop("Titre ou partie du titre à rechercher.")), listOf("titre")),
        tool("plex_appareils", "Plex : TOUS les lecteurs — vus par le serveur à la maison, en lecture, et ceux du compte plex.tv (télé, téléphones, ordinateurs, même hors de la maison) avec leur état ; action=nommer pour donner un nom à un lecteur (« télé du salon », « téléphone de Marie »).",
            mapOf("action" to prop("lister (défaut) | nommer"), "appareil" to prop("nommer : lecteur actuel (nom, produit…)."), "nom" to prop("nommer : nom à lui donner."))),
        tool("plex_lancer", "Plex : lance n'importe quel film, série (épisode précis, ou le prochain non vu) ou musique de la bibliothèque sur N'IMPORTE QUEL lecteur — télé, téléphone (« ce téléphone » = celui-ci), téléphone d'un proche, ordinateur… — à la maison comme à distance ; reprend là où la lecture s'était arrêtée. Demande confirmation.",
            mapOf("titre" to prop("Titre du film, de la série ou de la musique (ou identifiant)."), "appareil" to prop("Lecteur : son nom, un nom donné (« télé du salon »), un genre (télé, téléphone, ordinateur) ou « ce téléphone »."),
                "saison" to prop("Série : numéro de saison (optionnel)."), "episode" to prop("Série : numéro d'épisode, ou « dernier » pour le plus récent (optionnel ; sans saison ni épisode = prochain épisode non vu)."),
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
        tool("plex_serveur", "Plex Media Server (INSTANCE, par son API ; pour démarrer / arrêter / mettre à jour le SERVICE sur le serveur Debian, utilise serveur_service) : etat (version, mise à jour disponible, activités en cours, transcodages, bibliothèques) | maintenance (optimiser la base, nettoyer les anciens fichiers, vider les corbeilles) | reglages (lire, filtre optionnel) | modifier_reglage (cle + valeur) | verifier_mise_a_jour | reseau_local (pourquoi la télé de la maison est vue « à distance » et l'abonnement demandé) | reparer_local (déclare l'adresse locale et le réseau de la maison).",
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

    /** Épisode d'une série : précis (saison / épisode), le dernier sorti, ou le prochain non vu. */
    private fun episode(serie: JSONObject, saison: Int?, ep: Int?, dernier: Boolean = false): JSONObject {
        val tous = Plex.liste(Plex.mc(app, "/library/metadata/${serie.optString("ratingKey")}/allLeaves"))
        if (tous.isEmpty()) throw IllegalStateException("Aucun épisode pour « ${serie.optString("title")} ».")
        return when {
            dernier -> tous.filter { saison == null || it.optInt("parentIndex") == saison }.maxWithOrNull(compareBy({ it.optInt("parentIndex") }, { it.optInt("index") }))
            saison != null && ep != null -> tous.firstOrNull { it.optInt("parentIndex") == saison && it.optInt("index") == ep }
            saison != null -> tous.firstOrNull { it.optInt("parentIndex") == saison && it.optInt("viewCount") == 0 } ?: tous.firstOrNull { it.optInt("parentIndex") == saison }
            ep != null -> tous.firstOrNull { it.optInt("index") == ep && it.optInt("viewCount") == 0 } ?: tous.firstOrNull { it.optInt("index") == ep }
            else -> tous.firstOrNull { it.optLong("viewOffset") > 0 } ?: tous.firstOrNull { it.optInt("viewCount") == 0 } ?: tous.first()
        } ?: throw IllegalStateException("Épisode introuvable pour « ${serie.optString("title")} » (saison ${saison ?: "?"}, épisode ${ep ?: "?"}) : " +
            "${tous.map { it.optInt("parentIndex") }.distinct().size} saison(s), ${tous.size} épisodes dans Plex.")
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
                    if (a.optString("action").lowercase() == "nommer") {
                        val l = Plex.lecteur(app, a.optString("appareil"))
                        val nom = a.optString("nom").trim().ifBlank { return@withContextIo ToolResult("Indique « nom » (ex. télé du salon).") }
                        Plex.nommer(app, l, nom)
                        return@withContextIo ToolResult("✅ « ${l.libelle} » s'appelle maintenant « $nom » pour Plex.", direct = true)
                    }
                    val l = Plex.lecteurs(app)
                    val alias = runCatching { JSONObject(app.store.plexAlias.value) }.getOrDefault(JSONObject())
                    fun nomsDonnes(id: String) = alias.keys().asSequence().filter { alias.optString(it) == id }.toList()
                    if (l.isEmpty()) ToolResult("Aucun lecteur Plex connu. Ouvre l'appli Plex sur la télé / l'autre téléphone, connectée au même compte, avec « Annoncer comme lecteur » (Réglages Plex › Avancé). « ce téléphone » fonctionne toujours.", direct = true)
                    else ToolResult("# Lecteurs Plex\n" + l.sortedByDescending { it.enLigne }.joinToString("\n") { x ->
                        "- ${if (x.enLigne) "🟢" else "⚪"} **${x.libelle}** · ${x.produit} · ${x.plateforme}" +
                            (when (x.maison) { true -> " · à la maison"; false -> " · hors de la maison"; null -> "" }) +
                            nomsDonnes(x.id).let { if (it.isEmpty()) "" else " · appelé « ${it.joinToString("», «")} »" } +
                            " · ${x.sources.joinToString(", ")}"
                    } + "\n- 📱 **ce téléphone** · l'appli Plex de ce téléphone", direct = true)
                }
                "plex_lancer" -> {
                    var media = Plex.media(app, a.optString("titre"))
                    if (media.optString("type") in setOf("show", "season")) {
                        val serie = if (media.optString("type") == "season") Plex.metadata(app, media.optString("parentRatingKey")) else media
                        media = episode(serie, a.optString("saison").toIntOrNull() ?: (if (media.optString("type") == "season") media.optInt("index") else null), a.optString("episode").toIntOrNull(),
                            dernier = a.optString("episode").trim().lowercase().startsWith("dernier"))
                    } else if (media.optString("type") == "movie" || media.optString("type") == "episode") media = Plex.metadata(app, media.optString("ratingKey"))
                    val l = Plex.lecteur(app, a.optString("appareil").trim())
                    val offset = if (a.optString("depuis_le_debut").lowercase() in setOf("oui", "true", "1")) 0L else media.optLong("viewOffset")
                    if (!confirm("Lancer « ${Plex.titre(media)} » sur « ${l.libelle} » via Plex" + (if (offset > 0) " (reprise à ${Plex.duree(offset)})" else "") + " ?", true))
                        return@withContextIo ToolResult("Lecture annulée.")
                    ToolResult(Plex.lancerSur(app, media, l, offset), direct = true)
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
        val d0 = (if (a.optString("appareil").isBlank()) Plex.lecteurEnLecture(app) else null) ?: Plex.lecteur(app, a.optString("appareil").trim())
        if (d0.id == Plex.CE_TELEPHONE) return ToolResult("Pour l'appli Plex de ce téléphone, utilise les commandes de musique / média de JARVIS (pause, lecture…).")
        val d = JSONObject().put("name", d0.libelle)
        val id = d0.id
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
        val avant = Plex.sessionSur(app, id)
        Plex.request(app, chemin, q + ("commandID" to cmd), id)
        // Vérification par les sessions du serveur Plex (sauf le volume, que Plex ne renvoie pas).
        if (Plex.commandeAppliquee(c, avant, avant) == null) return ToolResult("✅ ${a.optString("commande")} envoyé à ${d.optString("name")} (Plex ne permet pas de relire ce réglage).", direct = true)
        if (avant == null && c !in setOf("stop", "arreter")) return ToolResult("⚠️ ${a.optString("commande")} envoyé à ${d.optString("name")}, mais aucune lecture Plex n'y est en cours : rien à commander.", direct = true)
        val apres = Plex.attendreSession(app, id, 8_000) { Plex.commandeAppliquee(c, avant, it) == true }
        return ToolResult(
            if (apres != null) "✅ ${a.optString("commande")} : confirmé sur ${d.optString("name")}" + (Plex.sessionSur(app, id)?.let { " (${Plex.etatLecture(it)})" } ?: " (lecture arrêtée)") + "."
            else "⚠️ ${a.optString("commande")} envoyé à ${d.optString("name")}, mais Plex ne le confirme pas au bout de 8 s" +
                (Plex.sessionSur(app, id)?.let { " (toujours : ${Plex.etatLecture(it)})" } ?: "") + ". Ne dis pas que c'est fait.", direct = true)
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
                val debut = System.nanoTime()
                val s = Plex.serveur(app)
                val delaiApiMs = (System.nanoTime() - debut) / 1_000_000
                // Une erreur ou une réponse vide de /updater/status ne prouve PAS que le serveur est à jour.
                val maj = runCatching { Plex.liste(Plex.mc(app, "/updater/status"), "Release").firstOrNull() }.getOrNull()
                val act = runCatching { Plex.liste(Plex.mc(app, "/activities"), "Activity") }.getOrDefault(emptyList())
                val sess = runCatching { Plex.mc(app, "/status/sessions").optInt("size") }.getOrDefault(0)
                val bibliotheques = Plex.sections(app)
                val majTexte = maj?.let { "✅ disponible — version ${it.optString("version")}" }
                    ?: "⚠️ vérification indisponible (aucune conclusion sur les mises à jour)"
                ToolResult("# 🖥️ Plex Media Server\n- ${s.optString("friendlyName")} · version ${s.optString("version")} · ${s.optString("platform")} ${s.optString("platformVersion")}\n" +
                    "- Connexion API / jeton : ✅ OK · réponse en ${delaiApiMs} ms\n" +
                    "- Chemin utilisé : ${Plex.chemin(app)}\n" +
                    "- Mise à jour : $majTexte\n" +
                    "- Lectures en cours : $sess · transcodages max ${s.optString("transcoderActiveVideoSessions", "?")}\n" +
                    "- Activités : " + act.joinToString { "${it.optString("title")} ${it.optInt("progress")} %" }.ifBlank { "aucune" } + "\n" +
                    "- Bibliothèques (${bibliotheques.size}) : " + bibliotheques.joinToString { it.optString("title") }.ifBlank { "aucune détectée" }, direct = true)
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
            "reseau_local" -> {
                val l = Plex.problemesReseauLocal(app)
                ToolResult(if (l.isEmpty()) "✅ Réseau local de Plex correct : serveur joint à ${Plex.ipLan(app)}, adresse et réseau déclarés. Si la télé demande quand même un abonnement : pas de VPN sur la télé ni sur le serveur, même Wi-Fi / même box, et redémarre l'appli Plex de la télé."
                    else "# 📡 Lecture locale Plex\n" + l.joinToString("\n") { "- $it" } + "\n\nDis « répare la lecture locale de Plex » pour déclarer l'adresse et le réseau de la maison dans Plex." +
                        (if (l.any { it.contains("Docker") }) "\nPlex en Docker : le plus sûr est aussi de le lancer en réseau « host » (network_mode: host)." else ""), direct = true)
            }
            "reparer_local" -> {
                if (!confirm("Plex : déclarer l'adresse locale du serveur et le réseau de la maison dans ses réglages réseau (pour que la télé ne soit plus vue « à distance ») ?", true)) return ToolResult("Annulé.")
                ToolResult(Plex.reparerReseauLocal(app), direct = true)
            }
            else -> ToolResult("Action inconnue (etat, maintenance, reglages, modifier_reglage, verifier_mise_a_jour, reseau_local, reparer_local).")
        }
    }

    private suspend fun <T> withContextIo(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
}
