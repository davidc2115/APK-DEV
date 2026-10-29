package fr.dd.jarvis.device

import android.content.Context
import fr.dd.jarvis.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress

data class MailItem(
    val from: String,
    val subject: String,
    val date: Date?,
    val snippet: String,
    val unread: Boolean,
    val account: String = ""
)

/** Lecture des mails en IMAP (Gmail : activer l'IMAP et utiliser un « mot de passe d'application »). */
class MailRepo(private val ctx: Context, private val store: Store) {

    suspend fun fetchRecent(count: Int, unreadOnly: Boolean): List<MailItem> =
        if (store.gmailAccounts.isNotEmpty()) GmailApi.fetchRecent(ctx, store.gmailAccounts.toList(), count, unreadOnly) else fetchImap(count, unreadOnly)

    private suspend fun fetchImap(count: Int, unreadOnly: Boolean): List<MailItem> = withContext(Dispatchers.IO) {
        val host = store.mailHost.value.trim()
        val port = store.mailPort.value.trim().toIntOrNull() ?: 993
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", host)
            put("mail.imaps.port", port.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "25000")
            put("mail.imaps.peek", "true")
        }
        val session = Session.getInstance(props)
        val imap = session.getStore("imaps")
        imap.connect(host, port, store.mailUser.value.trim(), store.mailPass.value)
        try {
            val inbox = imap.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            val total = inbox.messageCount
            if (total == 0) return@withContext emptyList()
            val window = if (unreadOnly) count * 4 else count
            val start = maxOf(1, total - window + 1)
            val msgs = inbox.getMessages(start, total)
            val fp = FetchProfile()
            fp.add(FetchProfile.Item.ENVELOPE)
            fp.add(FetchProfile.Item.FLAGS)
            inbox.fetch(msgs, fp)
            val result = msgs.reversed()
                .filter { !unreadOnly || !it.isSet(Flags.Flag.SEEN) }
                .take(count)
                .map { toItem(it) }
            inbox.close(false)
            result
        } finally {
            try {
                imap.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun toItem(m: Message): MailItem {
        val addr = m.from?.firstOrNull()
        val from = (addr as? InternetAddress)?.let { it.personal ?: it.address } ?: addr?.toString() ?: "?"
        val body = try {
            textOf(m)
        } catch (e: Exception) {
            ""
        }
        return MailItem(
            from = from,
            subject = m.subject ?: "(sans objet)",
            date = m.receivedDate ?: m.sentDate,
            snippet = body.replace(Regex("\\s+"), " ").trim().take(400),
            unread = !m.isSet(Flags.Flag.SEEN)
        )
    }

    private fun textOf(p: Part): String {
        return try {
            when {
                p.isMimeType("text/plain") -> (p.content as? String).orEmpty()
                p.isMimeType("text/html") -> stripHtml((p.content as? String).orEmpty())
                p.isMimeType("multipart/*") -> {
                    val mp = p.content as Multipart
                    var plain = ""
                    var html = ""
                    for (i in 0 until mp.count) {
                        val bp = mp.getBodyPart(i)
                        when {
                            bp.isMimeType("text/plain") && plain.isEmpty() -> plain = textOf(bp)
                            bp.isMimeType("text/html") && html.isEmpty() -> html = textOf(bp)
                            bp.isMimeType("multipart/*") && plain.isEmpty() -> plain = textOf(bp)
                        }
                    }
                    if (plain.isNotBlank()) plain else html
                }
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("(?is)<(script|style).*?</\\1>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

    /** Liste lisible sans IA (non lus d'abord). */
    fun localList(items: List<MailItem>): String {
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRENCH)
        fun l(m: MailItem) = "- ${if (m.unread) "🔵 " else ""}**${m.from}** — ${m.subject}" + (m.date?.let { " _(${fmt.format(it)})_" } ?: "") +
            (m.account.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        val non = items.filter { it.unread }
        val lus = items.filter { !it.unread }
        return buildString {
            append("# 📧 Boîte de réception\n\n")
            if (non.isNotEmpty()) append("## 🔵 Non lus (${non.size})\n").append(non.joinToString("\n") { l(it) }).append("\n\n")
            if (lus.isNotEmpty()) append("## Lus (${lus.size})\n").append(lus.joinToString("\n") { l(it) })
        }.trim()
    }

    /** Texte compact destiné aux agents. */
    fun toPrompt(items: List<MailItem>): String = items.joinToString("\n\n") { m ->
        "De : ${m.from}\nObjet : ${m.subject}\nDate : ${m.date ?: ""}\nStatut : ${if (m.unread) "non lu" else "lu"}\nExtrait : ${m.snippet}"
    }
}
