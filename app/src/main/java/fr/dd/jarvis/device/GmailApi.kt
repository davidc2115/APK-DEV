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

    private fun b64(s: String, flags: Int = android.util.Base64.NO_WRAP): String =
        android.util.Base64.encodeToString(s.toByteArray(Charsets.UTF_8), flags)

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

    /** Une pièce jointe à envoyer avec un mail : nom de fichier, type MIME, contenu brut. */
    data class PieceJointe(val nom: String, val mime: String, val bytes: ByteArray)

    /** Envoie un email (texte, éventuellement avec pièces jointes) depuis [account]. Le scope gmail.modify déjà
     *  accordé couvre l'envoi (pas besoin de nouveau consentement).
     *  Retourne l'identifiant du message envoyé (preuve que Gmail l'a bien accepté), et échoue explicitement sinon. */
    suspend fun envoyer(ctx: Context, account: String, destinataire: String, sujet: String, corps: String, piecesJointes: List<PieceJointe> = emptyList()): String = withContext(Dispatchers.IO) {
        val auth = authorize(ctx, account)
        if (auth.hasResolution()) throw IllegalStateException("Autorisation Gmail à renouveler pour $account : ouvre Réglages › Mails et reconnecte ce compte (le consentement a expiré ou a changé).")
        val token = auth.accessToken ?: throw IllegalStateException("Connexion Gmail expirée pour $account : reconnecte le compte dans Réglages › Mails.")
        val raw = if (piecesJointes.isEmpty()) {
            "From: $account\r\n" +
                "To: $destinataire\r\n" +
                "Subject: =?UTF-8?B?${b64(sujet)}?=\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n" +
                "Content-Transfer-Encoding: 8bit\r\n\r\n" +
                corps
        } else {
            val boundary = "jarvis_${System.currentTimeMillis()}"
            buildString {
                append("From: $account\r\n")
                append("To: $destinataire\r\n")
                append("Subject: =?UTF-8?B?${b64(sujet)}?=\r\n")
                append("MIME-Version: 1.0\r\n")
                append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n")
                append("--$boundary\r\n")
                append("Content-Type: text/plain; charset=UTF-8\r\n")
                append("Content-Transfer-Encoding: 8bit\r\n\r\n")
                append(corps).append("\r\n\r\n")
                for (pj in piecesJointes) {
                    append("--$boundary\r\n")
                    append("Content-Type: ${pj.mime}; name=\"${pj.nom}\"\r\n")
                    append("Content-Disposition: attachment; filename=\"${pj.nom}\"\r\n")
                    append("Content-Transfer-Encoding: base64\r\n\r\n")
                    append(android.util.Base64.encodeToString(pj.bytes, android.util.Base64.DEFAULT))
                    append("\r\n\r\n")
                }
                append("--$boundary--")
            }
        }
        val enc = b64(raw, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        val reponse = post(token, "$BASE/messages/send", JSONObject().put("raw", enc))
        // Gmail répond 200 avec un objet message {id, threadId, labelIds} quand c'est vraiment accepté : sans "id", quelque
        // chose s'est mal passé silencieusement (ex. compte sans adresse d'envoi valide) — on le signale au lieu de dire « envoyé ».
        reponse.optString("id").ifBlank { throw IllegalStateException("Gmail n'a pas confirmé l'envoi (réponse inattendue : ${reponse.toString().take(200)}).") }
    }

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
                            fromAddr = Regex("<([^>]+)>").find(h("From"))?.groupValues?.get(1)?.lowercase() ?: h("From").trim().lowercase(),
                            labels = m.optJSONArray("labelIds")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                        )
                      }
                    }
                }.awaitAll()
            }
        }
    }


    /** Recherche Gmail précise par expéditeur, texte (objet/contenu) et dates inclusives. */
    suspend fun rechercher(ctx: Context, accounts: List<String>, sender: String, term: String, fromDate: String, toDate: String, count: Int): List<MailItem> {
        val all = mutableListOf<MailItem>()
        for (account in accounts) {
            val auth = authorize(ctx, account)
            if (auth.hasResolution()) throw IllegalStateException("Autorisation Gmail à renouveler pour $account (Réglages › Mails).")
            val token = auth.accessToken ?: throw IllegalStateException("Jeton Google indisponible pour $account.")
            val parts = mutableListOf<String>()
            if (sender.isNotBlank()) parts += "from:${sender.trim()}"
            if (term.isNotBlank()) parts += "\"${term.trim().replace("\"", "")}\""
            fun datePart(value: String, after: Boolean): String? {
                val d = runCatching { java.time.LocalDate.parse(value.take(10)) }.getOrNull() ?: return null
                val target = if (!after) d.plusDays(1) else d
                return (if (after) "after:" else "before:") + target.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))
            }
            datePart(fromDate, true)?.let { parts += it }
            datePart(toDate, false)?.let { parts += it }
            val q = java.net.URLEncoder.encode(parts.joinToString(" "), "UTF-8")
            withContext(Dispatchers.IO) {
                val ids = mutableListOf<String>()
                var page: String? = null
                while (ids.size < count) {
                    val url = "$BASE/messages?maxResults=${minOf(100, count - ids.size)}&q=$q" + (page?.let { "&pageToken=$it" } ?: "")
                    val result = get(token, url)
                    val arr = result.optJSONArray("messages")
                    for (i in 0 until (arr?.length() ?: 0)) ids += arr!!.getJSONObject(i).getString("id")
                    page = result.optString("nextPageToken").takeIf { it.isNotBlank() } ?: break
                }
                for (id in ids) {
                    val m = get(token, "$BASE/messages/$id?format=full")
                    val headers = m.optJSONObject("payload")?.optJSONArray("headers")
                    fun h(name: String): String = (0 until (headers?.length() ?: 0)).firstNotNullOfOrNull { i ->
                        headers!!.getJSONObject(i).takeIf { it.optString("name").equals(name, true) }?.optString("value")
                    }.orEmpty()
                    val from = h("From")
                    all += MailItem(
                        from = Regex("^\\s*\"?([^\"<]+?)\"?\\s*<").find(from)?.groupValues?.get(1)?.ifBlank { null } ?: from.trim('<', '>', ' ').ifBlank { "?" },
                        subject = h("Subject").ifBlank { "(sans objet)" },
                        date = m.optString("internalDate").toLongOrNull()?.let { Date(it) },
                        snippet = m.optString("snippet"),
                        unread = m.optJSONArray("labelIds")?.toString()?.contains("UNREAD") == true,
                        account = account,
                        id = id,
                        fromAddr = Regex("<([^>]+)>").find(from)?.groupValues?.get(1)?.lowercase() ?: from.trim().lowercase(),
                        body = texteMail(m.optJSONObject("payload")).take(5000),
                        threadId = m.optString("threadId")
                    )
                }
            }
        }
        return all.sortedByDescending { it.date }.take(count)
    }

    data class ReplyTarget(val to: String, val subject: String, val threadId: String, val messageId: String, val references: String)

    /** Récupère le destinataire et les en-têtes nécessaires à une vraie réponse dans le fil d'origine. */
    suspend fun cibleReponse(ctx: Context, account: String, messageId: String): ReplyTarget = withContext(Dispatchers.IO) {
        val auth = authorize(ctx, account)
        if (auth.hasResolution()) throw IllegalStateException("Autorisation Gmail à renouveler pour $account.")
        val token = auth.accessToken ?: throw IllegalStateException("Jeton Google indisponible.")
        val m = get(token, "$BASE/messages/$messageId?format=full")
        val headers = m.optJSONObject("payload")?.optJSONArray("headers")
        fun h(name: String): String = (0 until (headers?.length() ?: 0)).firstNotNullOfOrNull { i ->
            headers!!.getJSONObject(i).takeIf { it.optString("name").equals(name, true) }?.optString("value")
        }.orEmpty()
        val from = h("Reply-To").ifBlank { h("From") }
        val to = Regex("<([^>]+)>").find(from)?.groupValues?.get(1) ?: from.trim()
        if (!to.contains("@")) throw IllegalStateException("Adresse de réponse introuvable dans le mail original.")
        val originalSubject = h("Subject").ifBlank { "(sans objet)" }
        val subject = if (originalSubject.startsWith("Re:", true)) originalSubject else "Re: $originalSubject"
        ReplyTarget(to, subject, m.optString("threadId"), h("Message-ID"), h("References"))
    }

    /** Envoie une réponse rattachée au fil Gmail d'origine. */
    suspend fun repondre(ctx: Context, account: String, target: ReplyTarget, body: String): String = withContext(Dispatchers.IO) {
        val auth = authorize(ctx, account)
        if (auth.hasResolution()) throw IllegalStateException("Autorisation Gmail à renouveler pour $account.")
        val token = auth.accessToken ?: throw IllegalStateException("Jeton Google indisponible.")
        val raw = buildString {
            append("From: $account\r\nTo: ${target.to}\r\nSubject: =?UTF-8?B?${android.util.Base64.encodeToString(target.subject.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)}?=\r\n")
            if (target.messageId.isNotBlank()) append("In-Reply-To: ${target.messageId}\r\n")
            val refs = listOf(target.references, target.messageId).filter { it.isNotBlank() }.joinToString(" ")
            if (refs.isNotBlank()) append("References: $refs\r\n")
            append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n")
            append(body)
        }
        val payload = JSONObject().put("raw", b64(raw)).put("threadId", target.threadId)
        val response = post(token, "$BASE/messages/send", payload)
        response.optString("id").ifBlank { throw IllegalStateException("Gmail n'a pas confirmé l'envoi de la réponse.") }
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
                val existing = libelles(token)
                // Gmail ne distingue pas « Clients/Dupont » de « Clients/dupont » : on réutilise toujours le libellé existant.
                fun idDe(nom: String) = existing[nom] ?: existing.entries.firstOrNull { it.key.equals(nom, true) }?.value
                // Libellés de l'organisation de l'utilisateur, créés au besoin avec leur couleur.
                for (cat in items.map { it.second }.distinct()) libelleEnCouleur(token, existing, prefix + cat)
                val utilises = mutableSetOf<String>()
                for ((id, cat) in items) {
                    val target = idDe(prefix + cat) ?: continue
                    utilises += target
                    val body = JSONObject().put("addLabelIds", org.json.JSONArray().put(target))
                    // Re-tri : on retire les anciens libellés de catégorie de ce mail (il n'en garde qu'un, le bon).
                    val remove = org.json.JSONArray()
                    existing.forEach { (nm, lid) -> if (lid != target && estCategorie(nm, prefix)) remove.put(lid) }
                    if (cat in archiver || cat.substringBefore('/') in archiver) remove.put("INBOX")
                    if (remove.length() > 0) body.put("removeLabelIds", remove)
                    post(token, "$BASE/messages/$id/modify", body)
                    n++
                }
                runCatching { supprimerVides(token, prefix, existing, utilises) }
            }
        }
        return n
    }

    private fun delete(token: String, url: String) {
        appeler { Request.Builder().url(url).header("Authorization", "Bearer $token").delete().build() }
    }

    /** Libellés du compte : nom → id. */
    private fun libelles(token: String): MutableMap<String, String> {
        val existing = mutableMapOf<String, String>()
        val ls = get(token, "$BASE/labels").optJSONArray("labels")
        for (i in 0 until (ls?.length() ?: 0)) ls!!.getJSONObject(i).let { existing[it.getString("name")] = it.getString("id") }
        return existing
    }

    /** Libellé de catégorie géré par Jarvis (« Clients », « Clients/Dupont »…). */
    private fun estCategorie(nom: String, prefix: String) = nom.startsWith(prefix) && ReglesMail.estGere(nom.removePrefix(prefix))

    /** Libellé [nom] présent (créé au besoin), avec la couleur prévue dans l'organisation des mails. Renvoie son id. */
    internal fun libelleEnCouleur(token: String, existing: MutableMap<String, String>, nom: String): String {
        val lib = (ReglesMail.LIBELLES + ReglesMail.TRAITE).firstOrNull { nom.endsWith(it.nom) }
        val couleur = lib?.let { JSONObject().put("backgroundColor", it.fond).put("textColor", it.texte) }
        existing[nom]?.let { return it }
        existing.entries.firstOrNull { it.key.equals(nom, true) }?.let { return it.value }
        val corps = JSONObject().put("name", nom).put("labelListVisibility", "labelShow").put("messageListVisibility", "show")
        if (couleur != null) corps.put("color", couleur)
        val id = runCatching { post(token, "$BASE/labels", corps).getString("id") }
            .getOrElse { post(token, "$BASE/labels", JSONObject().put("name", nom)).getString("id") }
        existing[nom] = id
        return id
    }

    /** Applique la couleur prévue à un libellé existant. */
    internal fun colorer(token: String, id: String, fond: String, texte: String) {
        runCatching { appeler { Request.Builder().url("$BASE/labels/$id").header("Authorization", "Bearer $token")
            .patch(JSONObject().put("color", JSONObject().put("backgroundColor", fond).put("textColor", texte)).toString().toRequestBody("application/json".toMediaType())).build() } }
    }

    /** Identifiants des mails d'une recherche Gmail ([max] au plus). */
    internal fun idsRecherche(token: String, q: String, max: Int = 20_000): List<String> {
        val ids = mutableListOf<String>()
        var page: String? = null
        do {
            val r = get(token, "$BASE/messages?maxResults=500&q=" + java.net.URLEncoder.encode(q, "UTF-8") + (page?.let { "&pageToken=$it" } ?: ""))
            val l = r.optJSONArray("messages")
            for (i in 0 until (l?.length() ?: 0)) ids += l!!.getJSONObject(i).getString("id")
            page = r.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null && ids.size < max)
        return ids.take(max)
    }

    /** Modifie jusqu'à 1000 mails à la fois. */
    internal fun modifierLot(token: String, ids: List<String>, ajouter: Collection<String>, retirer: Collection<String>) {
        ids.chunked(1000).forEach { lot ->
            post(token, "$BASE/messages/batchModify", JSONObject().put("ids", org.json.JSONArray(lot))
                .put("addLabelIds", org.json.JSONArray(ajouter.toList())).put("removeLabelIds", org.json.JSONArray(retirer.filter { it !in ajouter })))
        }
    }

    /** En-têtes utiles d'un mail : (expéditeur, sujet, étiquettes). */
    internal fun entetes(token: String, id: String): Triple<String, String, List<String>> {
        val m = get(token, "$BASE/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject")
        val h = m.optJSONObject("payload")?.optJSONArray("headers")
        fun v(n: String) = (0 until (h?.length() ?: 0)).mapNotNull { h!!.optJSONObject(it) }.firstOrNull { it.optString("name").equals(n, true) }?.optString("value").orEmpty()
        val from = v("From")
        val adresse = Regex("<([^>]+)>").find(from)?.groupValues?.get(1)?.lowercase() ?: from.trim().lowercase()
        val l = m.optJSONArray("labelIds")
        return Triple(adresse, v("Subject"), (0 until (l?.length() ?: 0)).map { l!!.optString(it) })
    }

    internal fun supprimerLibelle(token: String, id: String) = runCatching { delete(token, "$BASE/labels/$id") }.isSuccess

    internal fun tousLesLibelles(token: String) = libelles(token)

    internal suspend fun jeton(ctx: Context, account: String): String {
        val auth = authorize(ctx, account)
        if (auth.hasResolution()) throw IllegalStateException("autorisation à renouveler pour $account (Mails › retire puis ajoute le compte Google).")
        return auth.accessToken ?: throw IllegalStateException("jeton Google indisponible pour $account.")
    }

    /** Identifiants de tous les mails portant un libellé (y compris archivés). */
    private fun idsAvecLibelle(token: String, labelId: String): List<String> {
        val ids = mutableListOf<String>()
        var page: String? = null
        do {
            val r = get(token, "$BASE/messages?labelIds=$labelId&maxResults=500&includeSpamTrash=true" + (page?.let { "&pageToken=$it" } ?: ""))
            val l = r.optJSONArray("messages")
            for (i in 0 until (l?.length() ?: 0)) ids += l!!.getJSONObject(i).getString("id")
            page = r.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null && ids.size < 20_000)
        return ids
    }

    /**
     * Pas de libellés vides : après un (re)tri, les libellés de catégorie qui ne contiennent plus aucun mail sont
     * supprimés (d'abord les sous-libellés, puis les catégories sans mail ni sous-libellé). Les libellés qui viennent
     * d'être utilisés sont épargnés, et un libellé n'est supprimé que si Gmail confirme qu'il est vide.
     */
    private suspend fun supprimerVides(token: String, prefix: String, existing: MutableMap<String, String>, utilises: Set<String>) {
        val candidats = existing.entries.filter { it.key.startsWith(prefix) && ReglesMail.estObsolete(it.key.removePrefix(prefix)) && it.value !in utilises }.map { it.key to it.value }
        if (candidats.isEmpty() || candidats.size > 400) return
        val gate = kotlinx.coroutines.sync.Semaphore(5)
        val vides = coroutineScope {
            candidats.map { (nom, id) ->
                async {
                    gate.withPermit {
                        runCatching {
                            val l = get(token, "$BASE/labels/$id")
                            nom.takeIf { l.optInt("messagesTotal", -1) == 0 && l.optInt("threadsTotal", -1) == 0 }
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull().toMutableSet()
        }
        // Les sous-libellés d'abord (du plus profond au moins profond), puis les parents restés sans enfant.
        for (nom in vides.sortedByDescending { it.count { c -> c == '/' } }) {
            if (existing.keys.any { it != nom && it.startsWith("$nom/", true) }) continue
            val id = existing[nom] ?: continue
            if (idsAvecLibelle(token, id).isNotEmpty()) continue
            runCatching { delete(token, "$BASE/labels/$id") }.onSuccess { existing.remove(nom) }
        }
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

    /** Marque des mails comme lus (+ libellé « Traité » si [traite]), et retirés de la boîte de réception si [archiver]. */
    suspend fun marquerTraites(ctx: Context, parCompte: Map<String, List<String>>, archiver: Boolean, traite: Boolean = true): Int {
        var n = 0
        for ((account, ids) in parCompte) {
            val auth = authorize(ctx, account)
            if (auth.hasResolution()) throw IllegalStateException("autorisation à renouveler pour $account (Réglages › Mails › retire puis ajoute le compte).")
            val token = auth.accessToken ?: continue
            val nomTraite = (ctx.applicationContext as fr.dd.jarvis.JarvisApp).store.mailLabelPrefix.value + "Traité"
            withContext(Dispatchers.IO) {
                var labelId: String? = null
                if (traite) {
                    labelId = libelles(token).entries.firstOrNull { it.key.equals(nomTraite, true) }?.value
                    if (labelId == null) labelId = post(token, "$BASE/labels", JSONObject().put("name", nomTraite)).getString("id")
                }
                for (id in ids) {
                    val rem = org.json.JSONArray().put("UNREAD").also { if (archiver) it.put("INBOX") }
                    val body = JSONObject().put("removeLabelIds", rem)
                    labelId?.let { body.put("addLabelIds", org.json.JSONArray().put(it)) }
                    post(token, "$BASE/messages/$id/modify", body)
                    n++
                }
            }
        }
        return n
    }
}
