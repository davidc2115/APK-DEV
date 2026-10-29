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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Compte Google connecté dans l'application (lecture seule de la boîte de réception via l'API Gmail). */
object GmailApi {
    const val SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Si le résultat a une résolution (écran de consentement), l'interface doit la lancer. */
    suspend fun authorize(ctx: Context): AuthorizationResult = suspendCancellableCoroutine { c ->
        Identity.getAuthorizationClient(ctx).authorize(request())
            .addOnSuccessListener { c.resume(it) }
            .addOnFailureListener { c.resumeWithException(it) }
    }

    fun resultFromIntent(ctx: Context, data: android.content.Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data)

    private fun get(token: String, url: String): JSONObject {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        return Http.client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "Gmail ${r.code} : ${body.take(200)}")
            JSONObject(body)
        }
    }

    /** Adresse du compte connecté (vérifie aussi que le jeton fonctionne). */
    suspend fun profile(token: String): String = withContext(Dispatchers.IO) { get(token, "$BASE/profile").optString("emailAddress") }

    suspend fun fetchRecent(ctx: Context, count: Int, unreadOnly: Boolean): List<MailItem> {
        val auth = authorize(ctx)
        if (auth.hasResolution()) throw IllegalStateException("Autorisation Google à renouveler : onglet Mails › Connecter mon compte Google.")
        val token = auth.accessToken ?: throw IllegalStateException("Jeton Google indisponible.")
        return withContext(Dispatchers.IO) {
            val q = if (unreadOnly) "&q=is:unread" else ""
            val list = get(token, "$BASE/messages?maxResults=$count&labelIds=INBOX$q").optJSONArray("messages")
            val ids = (0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it).getString("id") }
            coroutineScope {
                ids.map { id ->
                    async {
                        val m = get(token, "$BASE/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject")
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
                            unread = m.optJSONArray("labelIds")?.toString()?.contains("UNREAD") == true
                        )
                    }
                }.awaitAll()
            }.sortedByDescending { it.date }
        }
    }
}
