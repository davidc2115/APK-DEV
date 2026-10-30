package fr.dd.jarvis.device

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import fr.dd.jarvis.ai.ApiException
import fr.dd.jarvis.ai.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Compte Google connecté dans l'application (lecture et classement par libellés via l'API Gmail). */
object GmailApi {
    const val SCOPE = "https://www.googleapis.com/auth/gmail.modify"
    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

    const val CAL_SCOPE = "https://www.googleapis.com/auth/calendar"

    private fun request(email: String?) = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE), Scope(CAL_SCOPE))).apply {
        if (!email.isNullOrBlank()) setAccount(android.accounts.Account(email, "com.google"))
    }.build()

    /** Si le résultat a une résolution (écran de consentement), l'interface doit la lancer. */
    suspend fun authorize(ctx: Context, email: String? = null): AuthorizationResult = suspendCancellableCoroutine { c ->
        Identity.getAuthorizationClient(ctx).authorize(request(email))
            .addOnSuccessListener { c.resume(it) }
            .addOnFailureListener { c.resumeWithException(it) }
    }

    fun resultFromIntent(ctx: Context, data: android.content.Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data)

    /** Exécute une requête Gmail ; en cas de quota dépassé (403/429) ou d'erreur serveur, attend puis réessaie (2, 4, 8, 16, 30 s). */
    private fun appeler(build: () -> Request): String {
        var essai = 0
        while (true) {
            val r = Http.client.newCall(build()).execute()
            val body = r.use { it.body?.string().orEmpty() }
            if (r.isSuccessful) return body
            val limite = r.code == 429 || r.code >= 500 || (r.code == 403 && (body.contains("uota", true) || body.contains("ateLimit", true) || body.contains("rate limit", true)))
            if (limite && essai < 5) {
                Thread.sleep(minOf(30_000L, 2_000L shl essai)); essai++; continue
            }
            val msg = if (limite) "Quota Gmail atteint (trop de mails lus d'un coup) : réessaie dans une minute, ou demande moins de mails." else "Gmail ${r.code} : ${body.take(200)}"
            throw ApiException(r.code, msg)
        }
    }

    private fun get(token: String, url: String): JSONObject =
        JSONObject(appeler { Request.Builder().url(url).header("Authorization", "Bearer $token").build() })

    /** Adresse du compte connecté (vérifie aussi que le jeton fonctionne). */
    suspend fun profile(token: String): String = withContext(Dispatchers.IO) { get(token, "$BASE/profile").optString("emailAddress") }

    suspend fun fetchRecent(ctx: Context, accounts: List<String>, count: Int, unreadOnly: Boolean, full: Boolean = false, everywhere: Boolean = false): List<MailItem> {
        val all = mutableListOf<MailItem>()
        val errors = mutableListOf<String>()
        for (acc in accounts) {
            try {
                all += fetchOne(ctx, acc, count, unreadOnly, full, everywhere)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "$acc : ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (all.isEmpty() && errors.isNotEmpty()) throw IllegalStateException(errors.joinToString("\n"))
        return all.sortedByDescending { it.date }.take(count)
    }

    /** Texte brut d'un message Gmail (partie text/plain, sinon HTML nettoyé). */
    private fun texteMail(p: JSONObject?): String {
        if (p == null) return ""
        fun dec(d: String): String = try { String(android.util.Base64.decode(d, android.util.Base64.URL_SAFE), Charsets.UTF_8) } catch (e: Exception) { "" }
        var plain = ""; var html = ""
        fun walk(n: JSONObject) {
            val mt = n.optString("mimeType")
            val data = n.optJSONObject("body")?.optString("data").orEmpty()
            if (data.isNotBlank()) { if (mt == "text/plain" && plain.isBlank()) plain = dec(data) else if (mt == "text/html" && html.isBlank()) html = dec(data) }
            val parts = n.optJSONArray("parts")
            for (i in 0 until (parts?.length() ?: 0)) walk(parts!!.getJSONObject(i))
        }
        walk(p)
        val t = plain.ifBlank { html.replace(Regex("(?is)<(style|script)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]+>"), " ") }
        return t.replace("&nbsp;", " ").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()
    }

    private suspend fun fetchOne(ctx: Context, account: String, count: Int, unreadOnly: Boolean, full: Boolean = false, everywhere: Boolean = false): List<MailItem> {
        val auth = authorize(ctx, account)
        if (auth.hasResolution()) throw IllegalStateException("autorisation à renouveler (Réglages › Mails › Ajouter un compte Google).")
        val token = auth.accessToken ?: throw IllegalStateException("jeton Google indisponible.")
        return withContext(Dispatchers.IO) {
            val qs = listOfNotNull(if (unreadOnly) "is:unread" else null, if (everywhere) "-in:spam -in:trash -in:drafts" else null).joinToString(" ")
            val q = if (qs.isNotBlank()) "&q=" + java.net.URLEncoder.encode(qs, "UTF-8") else ""
            val inbox = if (everywhere) "" else "&labelIds=INBOX"
            // Pagination : l'API Gmail renvoie au plus 100 mails par page.
            val ids = mutableListOf<String>()
            var page: String? = null
            while (ids.size < count) {
                val r = get(token, "$BASE/messages?maxResults=${minOf(100, count - ids.size)}$inbox$q" + (page?.let { "&pageToken=$it" } ?: ""))
                val list = r.optJSONArray("messages")
                for (i in 0 until (list?.length() ?: 0)) ids.add(list!!.getJSONObject(i).getString("id"))
                page = r.optString("nextPageToken").takeIf { it.isNotBlank() } ?: break
            }
            val gate = kotlinx.coroutines.sync.Semaphore(if (full) 3 else 6)
            coroutineScope {
                ids.map { id ->
                    async {
                      gate.withPermit {
                        val m = if (full) get(token, "$BASE/messages/$id?format=full") else get(token, "$BASE/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject")
                        val headers = m.optJSONObject("payload")?.optJSONArray("headers")
                        fun h(n: String): String {
                            for (i in 0 until (headers?.length() ?: 0)) {
                                val o = headers!!.getJSONObject(i)
                                if (o.getString("name").equals(n, true)) return o.getString("value")
                            }
                            return ""
                        }
                        val from = h("From").let { f -> Regex("^\\s*\"?([^\"<]+?)\"?\\s*<").find(f)?.groupValues?.get(1)?.ifBlank { null } ?: f.trim('<', '>', ' ') }
                        MailItem(
                            from = from.ifBlank { "?" },
                            subject = h("Subject").ifBlank { "(sans objet)" },
                            date = m.optString("internalDate").toLongOrNull()?.let { Date(it) },
                            snippet = m.optString("snippet").replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&"),
                            unread = m.optJSONArray("labelIds")?.toString()?.contains("UNREAD") == true,
                            account = account,
                            id = id,
                            body = if (full) texteMail(m.optJSONObject("payload")).take(1500) else "",
                            fromAddr = Regex("<([^>]+)>").find(h("From"))?.groupValues?.get(1)?.lowercase() ?: h("From").trim().lowercase()
                        )
                      }
                    }
                }.awaitAll()
            }
        }
    }

    private fun post(token: String, url: String, json: JSONObject): JSONObject {
        val body = appeler { Request.Builder().url(url).header("Authorization", "Bearer $token").post(json.toString().toRequestBody("application/json".toMediaType())).build() }
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }

    /**
     * Range les mails : un libellé « JARVIS/<catégorie> » par mail (créé au besoin) ; [archiver] retire aussi le mail de la boîte de réception.
     * [classement] : compte → liste (id du mail, catégorie). Retourne le nombre de mails rangés.
     */
    suspend fun appliquerLibelles(ctx: Context, classement: Map<String, List<Pair<String, String>>>, archiver: Set<String>): Int {
        var n = 0
        for ((account, items) in classement) {
            val auth = authorize(ctx, account)
            if (auth.hasResolution()) throw IllegalStateException("autorisation à renouveler pour $account (Réglages › Mails › Ajouter un compte Google : le classement demande la permission « modifier »).")
            val token = auth.accessToken ?: continue
            val prefix = (ctx.applicationContext as fr.dd.jarvis.JarvisApp).store.mailLabelPrefix.value
            withContext(Dispatchers.IO) {
                val existing = mutableMapOf<String, String>()
                val ls = get(token, "$BASE/labels").optJSONArray("labels")
                for (i in 0 until (ls?.length() ?: 0)) ls!!.getJSONObject(i).let { existing[it.getString("name")] = it.getString("id") }
                for (cat in items.map { it.second }.distinct()) {
                    // « Clients/Dupont » : on crée aussi le libellé parent « Clients » pour une arborescence propre.
                    val parts = cat.split("/")
                    for (k in 1..parts.size) {
                        val name = prefix + parts.take(k).joinToString("/")
                        if (name !in existing) existing[name] = post(token, "$BASE/labels", JSONObject().put("name", name)).getString("id")
                    }
                }
                for ((id, cat) in items) {
                    val target = existing[prefix + cat]
                    val body = JSONObject().put("addLabelIds", org.json.JSONArray().put(target))
                    // Re-tri : on retire les anciens libellés de catégorie de ce mail (il n'en garde qu'un, le bon).
                    val remove = org.json.JSONArray()
                    existing.forEach { (nm, lid) -> if (lid != target && nm.startsWith(prefix) && nm.removePrefix(prefix).substringBefore('/') in fr.dd.jarvis.ai.MailTri.CATEGORIES) remove.put(lid) }
                    if (cat.substringBefore('/') in archiver) remove.put("INBOX")
                    if (remove.length() > 0) body.put("removeLabelIds", remove)
                    post(token, "$BASE/messages/$id/modify", body)
                    n++
                }
            }
        }
        return n
    }

    /** Change la couleur d'un agenda côté Google (liste d'agendas) : visible ensuite sur tous les appareils. Retourne null si OK, sinon l'erreur. */
    suspend fun setCalendarColor(ctx: Context, account: String, googleCalId: String, rgb: Int): String? = try {
        val auth = authorize(ctx, account)
        if (auth.hasResolution()) "Autorisation Google Agenda à donner : Réglages › Mails › retire puis ajoute le compte Google."
        else if (auth.accessToken == null) "Jeton Google indisponible."
        else {
            val token = auth.accessToken
            withContext(Dispatchers.IO) {
                val hex = "#%06x".format(rgb and 0xFFFFFF)
                val body = JSONObject().put("backgroundColor", hex).put("foregroundColor", "#000000").toString()
                val url = "https://www.googleapis.com/calendar/v3/users/me/calendarList/${java.net.URLEncoder.encode(googleCalId, "UTF-8")}?colorRgbFormat=true"
                val req = Request.Builder().url(url).header("Authorization", "Bearer $token")
                    .patch(body.toRequestBody("application/json".toMediaType())).build()
                Http.client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) null else "Google Agenda ${r.code} : " + (r.body?.string().orEmpty().take(160)) +
                        (if (r.code == 403) " (active « Google Calendar API » dans ton projet Google Cloud)" else "")
                }
            }
        }
    } catch (e: Exception) { e.message ?: e.javaClass.simpleName }

    /** Marque des mails comme traités : lus + libellé « Traité », et retirés de la boîte de réception si [archiver]. */
    suspend fun marquerTraites(ctx: Context, parCompte: Map<String, List<String>>, archiver: Boolean): Int {
        var n = 0
        for ((account, ids) in parCompte) {
            val auth = authorize(ctx, account)
            if (auth.hasResolution()) throw IllegalStateException("autorisation à renouveler pour $account (Réglages › Mails › retire puis ajoute le compte).")
            val token = auth.accessToken ?: continue
            val traite = (ctx.applicationContext as fr.dd.jarvis.JarvisApp).store.mailLabelPrefix.value + "Traité"
            withContext(Dispatchers.IO) {
                var labelId: String? = null
                val ls = get(token, "$BASE/labels").optJSONArray("labels")
                for (i in 0 until (ls?.length() ?: 0)) ls!!.getJSONObject(i).let { if (it.getString("name") == traite) labelId = it.getString("id") }
                if (labelId == null) labelId = post(token, "$BASE/labels", JSONObject().put("name", traite)).getString("id")
                for (id in ids) {
                    val rem = org.json.JSONArray().put("UNREAD").also { if (archiver) it.put("INBOX") }
                    post(token, "$BASE/messages/$id/modify", JSONObject().put("addLabelIds", org.json.JSONArray().put(labelId)).put("removeLabelIds", rem))
                    n++
                }
            }
        }
        return n
    }
}
