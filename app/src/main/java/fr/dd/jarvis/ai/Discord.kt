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
        const val SEND_POLLS = 1L shl 49

        val NOMS = linkedMapOf(
            "creer_invitation" to CREATE_INSTANT_INVITE, "expulser_membres" to KICK_MEMBERS, "bannir_membres" to BAN_MEMBERS,
            "administrateur" to ADMINISTRATOR, "gerer_salons" to MANAGE_CHANNELS, "gerer_serveur" to MANAGE_GUILD,
            "ajouter_reactions" to ADD_REACTIONS, "voir_journal_audit" to VIEW_AUDIT_LOG, "voir_salon" to VIEW_CHANNEL,
            "envoyer_messages" to SEND_MESSAGES, "gerer_messages" to MANAGE_MESSAGES, "integrer_liens" to EMBED_LINKS,
            "joindre_fichiers" to ATTACH_FILES, "historique_messages" to READ_MESSAGE_HISTORY, "mentionner_tout_le_monde" to MENTION_EVERYONE,
            "emojis_externes" to USE_EXTERNAL_EMOJIS, "se_connecter_vocal" to CONNECT, "parler_vocal" to SPEAK,
            "rendre_muet" to MUTE_MEMBERS, "rendre_sourd" to DEAFEN_MEMBERS, "deplacer_membres" to MOVE_MEMBERS,
            "changer_pseudo" to CHANGE_NICKNAME, "gerer_pseudos" to MANAGE_NICKNAMES, "gerer_roles" to MANAGE_ROLES,
            "gerer_webhooks" to MANAGE_WEBHOOKS, "gerer_emojis_stickers" to MANAGE_GUILD_EXPRESSIONS, "utiliser_commandes" to USE_APPLICATION_COMMANDS,
            "gerer_evenements" to MANAGE_EVENTS, "gerer_fils" to MANAGE_THREADS, "creer_fils_publics" to CREATE_PUBLIC_THREADS,
            "creer_fils_prives" to CREATE_PRIVATE_THREADS, "stickers_externes" to USE_EXTERNAL_STICKERS, "moderer_membres" to MODERATE_MEMBERS,
            "utiliser_soundboard" to USE_SOUNDBOARD, "sons_externes" to USE_EXTERNAL_SOUNDS, "messages_vocaux" to SEND_VOICE_MESSAGES,
            "sondages" to SEND_POLLS
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
        if (!configure(app)) throw IllegalStateException("Discord non configuré (Réglages › Discord : jeton du bot).")
        val b = Request.Builder().url(API + path).header("Authorization", "Bot ${app.store.discordToken.value.trim()}")
        when (method) {
            "POST" -> b.post((corps?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
            "PATCH" -> b.patch((corps?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
            "PUT" -> b.put((corps?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
            "DELETE" -> b.delete()
            else -> b.get()
        }
        http.newCall(b.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (r.code == 401) throw IllegalStateException("Discord 401 : jeton de bot refusé.")
            if (r.code == 403) throw IllegalStateException("Discord 403 : le bot n'a pas la permission, ou son rôle n'est pas placé au-dessus du rôle concerné dans la hiérarchie du serveur.")
            if (r.code == 429) throw IllegalStateException("Discord : trop de requêtes, réessaie dans un instant.")
            if (!r.isSuccessful) throw IllegalStateException("Discord ${r.code} : ${t.take(300)}")
            return if (t.isBlank()) JSONObject() else runCatching { JSONObject(t) }.getOrDefault(JSONObject().put("brut", t))
        }
    }

    private fun reqArr(app: JarvisApp, method: String, path: String, corps: JSONObject? = null): JSONArray {
        if (!configure(app)) throw IllegalStateException("Discord non configuré (Réglages › Discord : jeton du bot).")
        val b = Request.Builder().url(API + path).header("Authorization", "Bot ${app.store.discordToken.value.trim()}")
        if (method == "GET") b.get() else b.post((corps?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (r.code == 401) throw IllegalStateException("Discord 401 : jeton de bot refusé.")
            if (r.code == 403) throw IllegalStateException("Discord 403 : le bot n'a pas la permission nécessaire.")
            if (!r.isSuccessful) throw IllegalStateException("Discord ${r.code} : ${t.take(300)}")
            return JSONArray(t)
        }
    }

    fun guildes(app: JarvisApp): JSONArray = reqArr(app, "GET", "/users/@me/guilds")
    fun roles(app: JarvisApp, guildId: String): JSONArray = reqArr(app, "GET", "/guilds/$guildId/roles")
    fun salons(app: JarvisApp, guildId: String): JSONArray = reqArr(app, "GET", "/guilds/$guildId/channels")
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
        val n = nom.trim().removePrefix("#").lowercase()
        val arr = salons(app, guildId)
        for (i in 0 until arr.length()) { val c = arr.getJSONObject(i); if (c.optString("name").lowercase() == n) return c }
        for (i in 0 until arr.length()) { val c = arr.getJSONObject(i); if (c.optString("name").lowercase().contains(n)) return c }
        return null
    }

    fun trouverGuilde(app: JarvisApp, nomOuId: String): JSONObject? {
        val arr = guildes(app)
        if (nomOuId.isBlank()) return if (arr.length() == 1) arr.getJSONObject(0) else null
        val n = nomOuId.trim().lowercase()
        for (i in 0 until arr.length()) { val g = arr.getJSONObject(i); if (g.optString("id") == nomOuId.trim() || g.optString("name").lowercase() == n) return g }
        for (i in 0 until arr.length()) { val g = arr.getJSONObject(i); if (g.optString("name").lowercase().contains(n)) return g }
        return null
    }
}
