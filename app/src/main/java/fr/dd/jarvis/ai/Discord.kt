package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Discord (API REST v10) : rôles, permissions de salons, attribution de rôles — avec le token de ton bot. */
object Discord {
    private const val API = "https://discord.com/api/v10"
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    fun configure(app: JarvisApp) = app.store.discordToken.value.isNotBlank()

    /** Nettoie le jeton collé par l'utilisateur : espaces, guillemets, un éventuel préfixe « Bot » déjà présent (sinon on l'ajoute
     * deux fois → 401), ET tout caractère invisible/spécial que le clavier du téléphone peut coller sans qu'on le voie (espace
     * insécable, guillemets typographiques, espace de largeur nulle…) : un simple trim() ne les enlève pas. On ne garde que
     * l'alphabet d'un jeton Discord réel (lettres, chiffres, point, tiret, tiret bas). */
    private fun jeton(app: JarvisApp): String =
        app.store.discordToken.value.trim().trim('"', '\'').removePrefix("Bot ").removePrefix("bot ").trim()
            .filter { it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '-' || it == '_' }

    /** Diagnostic (sans révéler le jeton) : longueur, premiers/derniers caractères, pour vérifier qu'il n'est pas tronqué ou altéré. */
    private fun diagJeton(app: JarvisApp): String {
        val t = jeton(app)
        if (t.isBlank()) return "(aucun jeton enregistré)"
        val debut = t.take(6); val fin = t.takeLast(4)
        return "longueur ${t.length}, commence par « $debut… », finit par « …$fin »"
    }

    // ------------------------------------------------------------ permissions (bits officiels de l'API Discord v10)
    object Perm {
        const val CREATE_INSTANT_INVITE = 1L shl 0
        const val KICK_MEMBERS = 1L shl 1
        const val BAN_MEMBERS = 1L shl 2
        const val ADMINISTRATOR = 1L shl 3
        const val MANAGE_CHANNELS = 1L shl 4
        const val MANAGE_GUILD = 1L shl 5
        const val ADD_REACTIONS = 1L shl 6
        const val VIEW_AUDIT_LOG = 1L shl 7
        const val PRIORITY_SPEAKER = 1L shl 8
        const val STREAM = 1L shl 9
        const val VIEW_CHANNEL = 1L shl 10
        const val SEND_MESSAGES = 1L shl 11
        const val SEND_TTS_MESSAGES = 1L shl 12
        const val MANAGE_MESSAGES = 1L shl 13
        const val EMBED_LINKS = 1L shl 14
        const val ATTACH_FILES = 1L shl 15
        const val READ_MESSAGE_HISTORY = 1L shl 16
        const val MENTION_EVERYONE = 1L shl 17
        const val USE_EXTERNAL_EMOJIS = 1L shl 18
        const val VIEW_GUILD_INSIGHTS = 1L shl 19
        const val CONNECT = 1L shl 20
        const val SPEAK = 1L shl 21
        const val MUTE_MEMBERS = 1L shl 22
        const val DEAFEN_MEMBERS = 1L shl 23
        const val MOVE_MEMBERS = 1L shl 24
        const val USE_VAD = 1L shl 25
        const val CHANGE_NICKNAME = 1L shl 26
        const val MANAGE_NICKNAMES = 1L shl 27
        const val MANAGE_ROLES = 1L shl 28
        const val MANAGE_WEBHOOKS = 1L shl 29
        const val MANAGE_GUILD_EXPRESSIONS = 1L shl 30
        const val USE_APPLICATION_COMMANDS = 1L shl 31
        const val REQUEST_TO_SPEAK = 1L shl 32
        const val MANAGE_EVENTS = 1L shl 33
        const val MANAGE_THREADS = 1L shl 34
        const val CREATE_PUBLIC_THREADS = 1L shl 35
        const val CREATE_PRIVATE_THREADS = 1L shl 36
        const val USE_EXTERNAL_STICKERS = 1L shl 37
        const val SEND_MESSAGES_IN_THREADS = 1L shl 38
        const val USE_EMBEDDED_ACTIVITIES = 1L shl 39
        const val MODERATE_MEMBERS = 1L shl 40
        const val VIEW_CREATOR_MONETIZATION_ANALYTICS = 1L shl 41
        const val USE_SOUNDBOARD = 1L shl 42
        const val CREATE_GUILD_EXPRESSIONS = 1L shl 43
        const val CREATE_EVENTS = 1L shl 44
        const val USE_EXTERNAL_SOUNDS = 1L shl 45
        const val SEND_VOICE_MESSAGES = 1L shl 46
        const val SET_VOICE_CHANNEL_STATUS = 1L shl 48
        const val SEND_POLLS = 1L shl 49
        const val USE_EXTERNAL_APPS = 1L shl 50
        const val PIN_MESSAGES = 1L shl 51
        const val BYPASS_SLOWMODE = 1L shl 52

        val NOMS = linkedMapOf(
            "creer_invitation" to CREATE_INSTANT_INVITE, "expulser_membres" to KICK_MEMBERS, "bannir_membres" to BAN_MEMBERS,
            "administrateur" to ADMINISTRATOR, "gerer_salons" to MANAGE_CHANNELS, "gerer_serveur" to MANAGE_GUILD,
            "ajouter_reactions" to ADD_REACTIONS, "voir_journal_audit" to VIEW_AUDIT_LOG, "voix_prioritaire" to PRIORITY_SPEAKER,
            "video" to STREAM, "voir_salon" to VIEW_CHANNEL,
            "envoyer_messages" to SEND_MESSAGES, "messages_synthese_vocale" to SEND_TTS_MESSAGES, "gerer_messages" to MANAGE_MESSAGES, "integrer_liens" to EMBED_LINKS,
            "joindre_fichiers" to ATTACH_FILES, "historique_messages" to READ_MESSAGE_HISTORY, "mentionner_tout_le_monde" to MENTION_EVERYONE,
            "emojis_externes" to USE_EXTERNAL_EMOJIS, "voir_statistiques_serveur" to VIEW_GUILD_INSIGHTS, "se_connecter_vocal" to CONNECT, "parler_vocal" to SPEAK,
            "rendre_muet" to MUTE_MEMBERS, "rendre_sourd" to DEAFEN_MEMBERS, "deplacer_membres" to MOVE_MEMBERS, "detection_voix" to USE_VAD,
            "changer_pseudo" to CHANGE_NICKNAME, "gerer_pseudos" to MANAGE_NICKNAMES, "gerer_roles" to MANAGE_ROLES,
            "gerer_webhooks" to MANAGE_WEBHOOKS, "gerer_emojis_stickers" to MANAGE_GUILD_EXPRESSIONS, "utiliser_commandes" to USE_APPLICATION_COMMANDS,
            "gerer_evenements" to MANAGE_EVENTS, "creer_evenements" to CREATE_EVENTS, "gerer_fils" to MANAGE_THREADS, "creer_fils_publics" to CREATE_PUBLIC_THREADS,
            "creer_fils_prives" to CREATE_PRIVATE_THREADS, "messages_fils" to SEND_MESSAGES_IN_THREADS, "stickers_externes" to USE_EXTERNAL_STICKERS,
            "activites" to USE_EMBEDDED_ACTIVITIES, "moderer_membres" to MODERATE_MEMBERS, "statistiques_monetisation" to VIEW_CREATOR_MONETIZATION_ANALYTICS,
            "utiliser_soundboard" to USE_SOUNDBOARD, "creer_emojis_stickers" to CREATE_GUILD_EXPRESSIONS, "sons_externes" to USE_EXTERNAL_SOUNDS,
            "messages_vocaux" to SEND_VOICE_MESSAGES, "statut_salon_vocal" to SET_VOICE_CHANNEL_STATUS, "sondages" to SEND_POLLS,
            "applications_externes" to USE_EXTERNAL_APPS, "epingler_messages" to PIN_MESSAGES, "ignorer_mode_lent" to BYPASS_SLOWMODE
        )

        /** Toutes les permissions connues SAUF « Administrateur » : donne un contrôle quasi total tout en respectant les
         * restrictions de salon (contrairement à « Administrateur », qui ignore toujours les accès refusés d'un salon). */
        val TOUT_SAUF_ADMINISTRATEUR: Long = NOMS.values.fold(0L) { acc, v -> acc or v } and ADMINISTRATOR.inv()

        /** Convertit "toutes_sauf_administrateur", une liste de noms séparés par des virgules, ou un nombre déjà en bits. */
        fun parse(s: String): Long {
            val t = s.trim()
            if (t.isBlank()) return 0L
            if (t.equals("toutes_sauf_administrateur", true) || t.equals("tout_sauf_administrateur", true) || t.equals("admin_sans_administrateur", true)) return TOUT_SAUF_ADMINISTRATEUR
            if (t.equals("toutes", true) || t.equals("administrateur", true) || t.equals("admin", true)) return ADMINISTRATOR or TOUT_SAUF_ADMINISTRATEUR
            t.toLongOrNull()?.let { return it }
            return t.split(",", ";", " ").map { it.trim().lowercase().replace(" ", "_") }.filter { it.isNotBlank() }
                .fold(0L) { acc, n -> acc or (NOMS[n] ?: 0L) }
        }

        fun noms(bits: Long): List<String> = NOMS.filter { (bits and it.value) != 0L }.keys.toList()
    }

    // ------------------------------------------------------------ appels HTTP

    private fun req(app: JarvisApp, method: String, path: String, corps: JSONObject? = null): JSONObject {
        if (!configure(app)) throw IllegalStateException("Discord non configuré (Réglages › Comptes › Discord : jeton du bot).")
        val b = Request.Builder().url(API + path).header("Authorization", "Bot ${jeton(app)}").header("User-Agent", "DiscordBot (https://github.com/davidc2115/APK-DEV, 1.0)")
        when (method) {
            "POST" -> b.post((corps?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
            "PATCH" -> b.patch((corps?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
            "PUT" -> b.put((corps?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
            "DELETE" -> b.delete()
            else -> b.get()
        }
        http.newCall(b.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (r.code == 401) throw IllegalStateException("Discord 401 : jeton refusé (${diagJeton(app)}). Réponse de Discord : ${t.take(200).ifBlank { "(vide)" }}. Vérifie que c'est bien le jeton du BOT (onglet Bot du developer portal \u203a \u00ab Reset Token \u00bb), pas le Client Secret/ID. Si ce jeton fonctionne par ailleurs (ex. un bot lancé sur le serveur Debian) mais pas ici, c'est probablement un copier-coller différent : régénère un jeton (\u00ab Reset Token \u00bb), colle-le UNE SEULE fois dans Réglages \u203a Discord sans le retaper, et utilise ce même nouveau jeton partout.")
            if (r.code == 403) throw IllegalStateException("Discord 403 : le bot n'a pas la permission, ou son rôle n'est pas placé au-dessus du rôle concerné dans la hiérarchie du serveur.")
            if (r.code == 429) throw IllegalStateException("Discord : trop de requêtes, réessaie dans un instant.")
            if (!r.isSuccessful) throw IllegalStateException("Discord ${r.code} : ${t.take(300)}")
            return if (t.isBlank()) JSONObject() else runCatching { JSONObject(t) }.getOrDefault(JSONObject().put("brut", t))
        }
    }

    private fun reqArr(app: JarvisApp, method: String, path: String, corps: JSONObject? = null): JSONArray {
        if (!configure(app)) throw IllegalStateException("Discord non configuré (Réglages › Comptes › Discord : jeton du bot).")
        val b = Request.Builder().url(API + path).header("Authorization", "Bot ${jeton(app)}").header("User-Agent", "DiscordBot (https://github.com/davidc2115/APK-DEV, 1.0)")
        if (method == "GET") b.get() else b.post((corps?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (r.code == 401) throw IllegalStateException("Discord 401 : jeton refusé (${diagJeton(app)}). Réponse de Discord : ${t.take(200).ifBlank { "(vide)" }}. Vérifie que c'est bien le jeton du BOT (onglet Bot du developer portal \u203a \u00ab Reset Token \u00bb), pas le Client Secret/ID. Si ce jeton fonctionne par ailleurs (ex. un bot lancé sur le serveur Debian) mais pas ici, c'est probablement un copier-coller différent : régénère un jeton (\u00ab Reset Token \u00bb), colle-le UNE SEULE fois dans Réglages \u203a Discord sans le retaper, et utilise ce même nouveau jeton partout.")
            if (r.code == 403) throw IllegalStateException("Discord 403 : accès refusé à ce salon ou à ce fil. Vérifie les permissions VIEW_CHANNEL (Voir le salon) et READ_MESSAGE_HISTORY (Lire l’historique des messages), ainsi que l’accès du bot au fil. Si elles sont accordées et que le texte reste vide, vérifie l’intent privilégié MESSAGE_CONTENT dans le Discord Developer Portal.")
            if (!r.isSuccessful) throw IllegalStateException("Discord ${r.code} : ${t.take(300)}")
            return JSONArray(t)
        }
    }

    fun guildes(app: JarvisApp): JSONArray = reqArr(app, "GET", "/users/@me/guilds").also { DiscordNoms.retenir(app, it) }
    fun roles(app: JarvisApp, guildId: String): JSONArray = reqArr(app, "GET", "/guilds/$guildId/roles")
    fun salons(app: JarvisApp, guildId: String): JSONArray = reqArr(app, "GET", "/guilds/$guildId/channels").also { DiscordNoms.retenir(app, it) }
    /** Lit les derniers messages d’un salon. afterId permet de ne récupérer que les messages plus récents. */
    fun messages(app: JarvisApp, channelId: String, limite: Int = 30, afterId: String = "", beforeId: String = ""): JSONArray {
        val n = limite.coerceIn(1, 100)
        val after = afterId.trim().takeIf { it.matches(Regex("\\d{17,20}")) }
        val before = beforeId.trim().takeIf { it.matches(Regex("\\d{17,20}")) }
        require(after == null || before == null) { "Utilise soit apres_id, soit avant_id, pas les deux." }
        val query = "limit=$n" + (after?.let { "&after=$it" } ?: "") + (before?.let { "&before=$it" } ?: "")
        return reqArr(app, "GET", "/channels/$channelId/messages?$query")
    }
    /** Identifiant Discord (snowflake) correspondant à un instant : sert de curseur « après » / « avant ». */
    internal fun snowflake(ms: Long): String = ((ms - 1_420_070_400_000L) shl 22).toString()

    /** « hier », « aujourd'hui », « avant-hier », « AAAA-MM-JJ » (+ fin incluse) → (début, fin) en ms, heure locale. */
    internal fun periode(jour: String, jusquAu: String = "", aujourdhui: java.time.LocalDate = java.time.LocalDate.now(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Pair<Long, Long>? {
        val j = fr.dd.jarvis.societe.core.normaliser(jour)
        val debut = when {
            j.isBlank() -> return null
            j.contains("avant hier") -> aujourdhui.minusDays(2)
            j.contains("hier") -> aujourdhui.minusDays(1)
            j.contains("aujourd") || j == "ce jour" -> aujourdhui
            else -> runCatching { java.time.LocalDate.parse(jour.trim().take(10)) }.getOrNull() ?: return null
        }
        val fin = runCatching { java.time.LocalDate.parse(jusquAu.trim().take(10)) }.getOrNull()?.takeIf { !it.isBefore(debut) } ?: debut
        return debut.atStartOfDay(zone).toInstant().toEpochMilli() to fin.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Tous les messages d'une période (500 au plus), du plus ancien au plus récent. */
    fun messagesPeriode(app: JarvisApp, channelId: String, debut: Long, fin: Long, max: Int = 500): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var apres = snowflake(debut - 1)
        val limite = fin
        while (out.size < max) {
            val page = messages(app, channelId, 100, apres, "")
            val l = (0 until page.length()).map { page.getJSONObject(it) }.sortedBy { it.optString("id").toBigInteger() }
            if (l.isEmpty()) break
            val dansPeriode = l.filter { m -> runCatching { java.time.Instant.parse(m.optString("timestamp")).toEpochMilli() < limite }.getOrDefault(true) }
            out += dansPeriode
            if (dansPeriode.size < l.size || l.size < 100) break
            apres = l.last().optString("id")
        }
        return out.take(max)
    }

    /** Envoie un message ordinaire dans un salon texte. Les mentions globales sont désactivées par défaut. */
    fun envoyerMessage(app: JarvisApp, channelId: String, contenu: String): JSONObject {
        val texte = contenu.trim()
        require(texte.isNotBlank()) { "Le message Discord est vide." }
        require(texte.length <= 2000) { "Un message Discord ne peut pas dépasser 2 000 caractères." }
        val body = JSONObject().put("content", texte)
            .put("allowed_mentions", JSONObject().put("parse", JSONArray()))
        return req(app, "POST", "/channels/$channelId/messages", body)
    }
    fun membre(app: JarvisApp, guildId: String, userId: String): JSONObject = req(app, "GET", "/guilds/$guildId/members/$userId")
    fun chercherMembres(app: JarvisApp, guildId: String, query: String): JSONArray = reqArr(app, "GET", "/guilds/$guildId/members/search?query=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=10")

    fun roleCreer(app: JarvisApp, guildId: String, nom: String, couleur: Int?, permissions: Long?, hoist: Boolean?, mentionnable: Boolean?): JSONObject {
        val c = JSONObject().put("name", nom)
        couleur?.let { c.put("color", it) }
        permissions?.let { c.put("permissions", it.toString()) }
        hoist?.let { c.put("hoist", it) }
        mentionnable?.let { c.put("mentionable", it) }
        return req(app, "POST", "/guilds/$guildId/roles", c)
    }

    fun roleModifier(app: JarvisApp, guildId: String, roleId: String, nom: String?, couleur: Int?, permissions: Long?, hoist: Boolean?, mentionnable: Boolean?): JSONObject {
        val c = JSONObject()
        nom?.let { c.put("name", it) }
        couleur?.let { c.put("color", it) }
        permissions?.let { c.put("permissions", it.toString()) }
        hoist?.let { c.put("hoist", it) }
        mentionnable?.let { c.put("mentionable", it) }
        return req(app, "PATCH", "/guilds/$guildId/roles/$roleId", c)
    }

    fun roleSupprimer(app: JarvisApp, guildId: String, roleId: String) { req(app, "DELETE", "/guilds/$guildId/roles/$roleId") }

    /** Identité du bot JARVIS lui-même (utile pour connaître ses propres rôles, donc jusqu'où il peut monter un rôle dans la hiérarchie). */
    fun moi(app: JarvisApp): JSONObject = req(app, "GET", "/users/@me")

    /** Position la plus haute parmi les rôles que le bot possède lui-même sur ce serveur (0 si aucun rôle au-delà de @everyone) :
     *  Discord refuse (403) de placer un rôle à une position supérieure ou égale au rôle le plus haut du bot. */
    fun rolePositionMaxBot(app: JarvisApp, guildId: String): Int {
        val monId = moi(app).getString("id")
        val mesRoles = (membre(app, guildId, monId).optJSONArray("roles") ?: JSONArray()).let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
        val arr = roles(app, guildId)
        var max = 0
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (mesRoles.contains(r.getString("id"))) max = maxOf(max, r.optInt("position"))
        }
        return max
    }

    /** Déplace un rôle à une position donnée dans la hiérarchie du serveur (1 = juste au-dessus de @everyone). */
    fun roleDeplacer(app: JarvisApp, guildId: String, roleId: String, position: Int) {
        if (!configure(app)) throw IllegalStateException("Discord non configuré (Réglages › Comptes › Discord : jeton du bot).")
        val corps = JSONArray().put(JSONObject().put("id", roleId).put("position", position))
        val b = Request.Builder().url("$API/guilds/$guildId/roles").header("Authorization", "Bot ${jeton(app)}")
            .header("User-Agent", "DiscordBot (https://github.com/davidc2115/APK-DEV, 1.0)")
            .patch(corps.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (r.code == 401) throw IllegalStateException("Discord 401 : jeton refusé (${diagJeton(app)}).")
            if (r.code == 403) throw IllegalStateException("Discord 403 : le bot n'a pas la permission « Gérer les rôles », ou son propre rôle n'est pas assez haut dans la hiérarchie pour déplacer celui-ci à cette position.")
            if (r.code == 429) throw IllegalStateException("Discord : trop de requêtes, réessaie dans un instant.")
            if (!r.isSuccessful) throw IllegalStateException("Discord ${r.code} : ${t.take(300)}")
        }
    }

    fun roleAttribuer(app: JarvisApp, guildId: String, userId: String, roleId: String) { req(app, "PUT", "/guilds/$guildId/members/$userId/roles/$roleId") }
    fun roleRetirer(app: JarvisApp, guildId: String, userId: String, roleId: String) { req(app, "DELETE", "/guilds/$guildId/members/$userId/roles/$roleId") }

    /** Droit d'accès d'un salon pour un rôle (type=0) ou un membre (type=1) : allow/deny en bits de permissions. */
    fun salonPermission(app: JarvisApp, channelId: String, cibleId: String, type: Int, allow: Long, deny: Long) {
        req(app, "PUT", "/channels/$channelId/permissions/$cibleId", JSONObject().put("type", type).put("allow", allow.toString()).put("deny", deny.toString()))
    }
    fun salonPermissionSupprimer(app: JarvisApp, channelId: String, cibleId: String) { req(app, "DELETE", "/channels/$channelId/permissions/$cibleId") }

    // ------------------------------------------------------------ recherche par nom (pratique pour le langage naturel)

    fun trouverRole(app: JarvisApp, guildId: String, nom: String): JSONObject? {
        val n = nom.trim().removePrefix("@").lowercase()
        val arr = roles(app, guildId)
        for (i in 0 until arr.length()) { val r = arr.getJSONObject(i); if (r.optString("name").lowercase() == n) return r }
        for (i in 0 until arr.length()) { val r = arr.getJSONObject(i); if (r.optString("name").lowercase().contains(n)) return r }
        return null
    }

    fun trouverSalon(app: JarvisApp, guildId: String, nom: String): JSONObject? {
        val arr = salons(app, guildId)
        return choisir((0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optInt("type") != 4 }, nom.trim().removePrefix("#"), textesAlias(app))
    }

    fun trouverGuilde(app: JarvisApp, nomOuId: String): JSONObject? {
        val arr = guildes(app)
        val l = (0 until arr.length()).map { arr.getJSONObject(it) }
        if (nomOuId.isBlank()) return l.singleOrNull()
        return choisir(l, nomOuId, textesAlias(app))
    }

    /** Noms de tous les serveurs (ou salons) disponibles, pour un message d'erreur utile. */
    fun noms(arr: JSONArray, sansCategories: Boolean = false): String =
        (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !sansCategories || it.optInt("type") != 4 }.joinToString(", ") { "« ${it.optString("name")} »" }

    /** Textes où l'utilisateur a pu donner un surnom : faits mémorisés, carnet de notes, mémoire de Jarvis et d'Hermès. */
    private fun textesAlias(app: JarvisApp): List<String> = buildList {
        runCatching { fr.dd.jarvis.device.Faits.surnoms(app).forEach { add("${it.surnom} = ${it.cible}") } }
        runCatching { fr.dd.jarvis.device.Carnet.toutes(app).forEach { add(it.sujet + " : " + it.texte) } }
        runCatching { app.memo.all().forEach { (k, v) -> add("$k : $v") } }
        runCatching { app.memo.hermes().lines().filter { it.isNotBlank() }.forEach { add(it) } }
    }

    private fun compact(s: String) = s.replace(Regex("\\bet\\b"), " ").replace(" ", "")

    /** Ressemblance d'une demande avec un nom (normalisés) : sigle (« bag » ↔ « B&G »), lettres, fautes de frappe ou d'écoute. */
    internal fun ressemblance(demande: String, nom: String): Double {
        val a = compact(demande); val b = compact(nom)
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        val sigle = nom.split(' ').filter { it.isNotBlank() && it != "et" }.joinToString("") { it.take(1) }
        if (sigle.length >= 2 && (a == sigle || a.filter { it !in "aeiouy" } == sigle)) return 0.9
        return Serveur.similarite(a, b)
    }

    /**
     * Serveur ou salon désigné par [demande] : identifiant, nom exact (sans accents, emojis ni séparateurs ; « & » = « et »),
     * SURNOM donné par l'utilisateur dans ses notes ou sa mémoire (un texte qui cite la demande et un seul des noms),
     * nom qui contient la demande, puis nom ressemblant (sigle, fautes). Null si rien ou si c'est ambigu.
     */
    internal fun choisir(objets: List<JSONObject>, demande: String, alias: List<String>): JSONObject? {
        val d = demande.trim()
        if (d.isBlank()) return null
        objets.firstOrNull { it.optString("id") == d }?.let { return it }
        val k = DiscordNoms.cle(d)
        if (k.isBlank()) return null
        fun nom(o: JSONObject) = DiscordNoms.cle(o.optString("name"))
        objets.filter { nom(it) == k }.singleOrNull()?.let { return it }
        objets.filter { compact(nom(it)) == compact(k) }.singleOrNull()?.let { return it }
        // Surnom noté par l'utilisateur (« le serveur bag c'est … », « B&G = … »).
        val citent = alias.map { " " + DiscordNoms.cle(it) + " " }.filter { it.contains(" $k ") }
        objets.filter { o -> val n = nom(o); n.length >= 2 && n != k && citent.any { it.contains(" $n ") } }.singleOrNull()?.let { return it }
        objets.filter { nom(it).contains(k) || (nom(it).length >= 3 && k.contains(nom(it))) }.singleOrNull()?.let { return it }
        val proches = objets.map { it to ressemblance(k, nom(it)) }.filter { it.second >= 0.6 }.sortedByDescending { it.second }
        if (proches.size == 1 || (proches.size > 1 && proches[0].second - proches[1].second >= 0.2)) return proches[0].first
        return null
    }
}


/**
 * Noms des serveurs (guildes) et salons Discord où le bot est présent, gardés sur le téléphone : une demande qui les cite
 * (« résume le salon discussion du serveur B&G ») est reconnue comme une demande DISCORD — outils Discord proposés,
 * jamais le serveur Debian ni SSH — même sans le mot « Discord ».
 */
object DiscordNoms {
    @Volatile var noms: Set<String> = emptySet()
        private set
    private fun fichier(app: JarvisApp) = java.io.File(app.filesDir, "discord_noms.json")

    internal fun cle(s: String) = fr.dd.jarvis.societe.core.normaliser(s.replace("&", " et ").replace(Regex("[-_]"), " "))

    /** À appeler au démarrage : noms relevés lors des visites précédentes. */
    fun initialiser(app: JarvisApp) {
        noms = runCatching { JSONArray(fichier(app).readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())
    }

    /** Relevé en arrière-plan (au plus une fois par jour) des serveurs et salons où le bot est présent. */
    fun rafraichirSiBesoin(app: JarvisApp) {
        if (!Discord.configure(app)) return
        val f = fichier(app)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 24 * 3_600_000L) return
        Thread({
            runCatching {
                val g = Discord.guildes(app)
                for (i in 0 until minOf(g.length(), 10)) runCatching { Discord.salons(app, g.getJSONObject(i).getString("id")) }
                if (f.exists()) f.setLastModified(System.currentTimeMillis())
            }
        }, "jarvis-discord-noms").apply { isDaemon = true }.start()
    }

    @Synchronized fun retenir(app: JarvisApp, arr: JSONArray) {
        val nouveaux = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }.map { cle(it) }.filter { it.length >= 2 }
        if (nouveaux.all { it in noms }) return
        noms = (noms + nouveaux).take(400).toSet()
        runCatching { fichier(app).writeText(JSONArray(noms.toList()).toString()) }
    }

    /** La phrase cite-t-elle un serveur ou un salon Discord connu ? (noms courants du quotidien exclus) */
    fun cite(texte: String, connus: Set<String> = noms): Boolean {
        if (connus.isEmpty()) return false
        val t = " " + cle(texte) + " "
        return connus.any { n ->
            n !in COURANTS && ((n.length >= 3 && t.contains(" $n ")) || Regex("\\b(serveur|salon|channel|canal) ${Regex.escape(n)}\\b").containsMatchIn(t))
        }
    }

    /** Noms de salons trop courants pour désigner Discord à eux seuls (« le salon », « général »…). */
    private val COURANTS = setOf("general", "salon", "accueil", "bienvenue", "annonces", "regles", "musique", "jeux", "photos", "videos", "vocal", "aide", "discussion", "chat", "infos", "news")
}
