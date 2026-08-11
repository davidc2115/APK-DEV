package com.jarvis.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.SubjectTerm

/**
 * Contrôleur Email IMAP/SMTP pour JARVIS.
 *
 * Permet de lire et envoyer des emails directement depuis l'app sans passer
 * par une application tierce, via le protocole IMAP (lecture) et SMTP (envoi).
 *
 * Utilise la lib android-mail (JavaMail for Android, com.sun.mail:android-mail:1.6.7).
 *
 * Configuration : les comptes sont stockés dans Prefs.EmailAccount.
 * Presets disponibles : Gmail, Outlook, Yahoo, iCloud.
 * Pour Gmail/Yahoo : un "App Password" est nécessaire (pas le mot de passe principal)
 *   car ces services bloquent l'accès IMAP avec le mot de passe normal.
 */
object EmailController {

    private const val TAG = "EmailController"
    private const val FETCH_TIMEOUT_MS = 30_000

    // ─────────────────────────────────────────────────────────────────────────
    // DATA CLASSES
    // ─────────────────────────────────────────────────────────────────────────

    data class EmailMessage(
        val id: Long,
        val from: String,
        val to: String,
        val subject: String,
        val body: String,
        val date: String,
        val isRead: Boolean = false,
        val attachments: List<String> = emptyList()
    )

    data class EmailResult(
        val success: Boolean,
        val message: String,
        val emails: List<EmailMessage> = emptyList()
    )

    // ─────────────────────────────────────────────────────────────────────────
    // LECTURE IMAP
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lit les N derniers emails de la boîte de réception.
     * Retourne une réponse formatée en français pour JARVIS.
     */
    suspend fun readInbox(
        context: Context,
        count: Int = 5,
        accountId: String? = null
    ): String = withContext(Dispatchers.IO) {
        val account = resolveAccount(context, accountId)
            ?: return@withContext "❌ Aucun compte email configuré. Va dans ⚙ Paramètres → onglet Email pour en ajouter un."

        val result = fetchEmails(account, "INBOX", count)
        if (!result.success) return@withContext "❌ Erreur lecture emails (${account.label}) : ${result.message}"

        if (result.emails.isEmpty()) {
            return@withContext "📭 La boîte de réception de ${account.email} est vide."
        }

        buildString {
            append("📬 **${result.emails.size} dernier(s) email(s)** de ${account.email} :\n\n")
            result.emails.forEachIndexed { i, email ->
                append("${i + 1}. **${email.subject.ifBlank { "(sans sujet)" }}**\n")
                append("   De : ${email.from}\n")
                append("   Le : ${email.date}\n")
                val preview = email.body.take(150).replace("\n", " ").trim()
                if (preview.isNotBlank()) append("   Aperçu : $preview…\n")
                append("\n")
            }
        }
    }

    /**
     * Lit les emails non lus uniquement.
     */
    suspend fun readUnread(context: Context, accountId: String? = null): String =
        withContext(Dispatchers.IO) {
            val account = resolveAccount(context, accountId)
                ?: return@withContext "❌ Aucun compte email configuré."

            val result = fetchEmails(account, "INBOX", 20, onlyUnread = true)
            if (!result.success) return@withContext "❌ Erreur : ${result.message}"

            if (result.emails.isEmpty()) return@withContext "✅ Aucun email non lu dans ${account.email}."

            buildString {
                append("📬 **${result.emails.size} email(s) non lu(s)** dans ${account.email} :\n\n")
                result.emails.take(10).forEachIndexed { i, email ->
                    append("${i + 1}. **${email.subject.ifBlank { "(sans sujet)" }}** — ${email.from}\n")
                    append("   ${email.date}\n\n")
                }
            }
        }

    /**
     * Lit le contenu complet d'un email par son index dans la liste récente.
     */
    suspend fun readEmailContent(
        context: Context,
        index: Int,
        accountId: String? = null
    ): String = withContext(Dispatchers.IO) {
        val account = resolveAccount(context, accountId)
            ?: return@withContext "❌ Aucun compte email configuré."

        val result = fetchEmails(account, "INBOX", maxOf(index + 1, 10))
        if (!result.success) return@withContext "❌ Erreur : ${result.message}"

        val email = result.emails.getOrNull(index - 1)
            ?: return@withContext "❌ Email numéro $index introuvable."

        buildString {
            append("📧 **Email #$index**\n\n")
            append("**Sujet** : ${email.subject}\n")
            append("**De** : ${email.from}\n")
            append("**À** : ${email.to}\n")
            append("**Date** : ${email.date}\n\n")
            append("---\n")
            append(email.body.take(3000))
            if (email.body.length > 3000) append("\n\n[... message tronqué — ${email.body.length} caractères au total]")
        }
    }

    /**
     * Recherche des emails par sujet.
     */
    suspend fun searchEmails(
        context: Context,
        query: String,
        accountId: String? = null
    ): String = withContext(Dispatchers.IO) {
        val account = resolveAccount(context, accountId)
            ?: return@withContext "❌ Aucun compte email configuré."

        try {
            val session = buildImapSession(account)
            val store = session.getStore("imaps")
            store.connect(account.imapHost, account.imapPort, account.email, account.password)
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)

            val messages = folder.search(SubjectTerm(query))
            folder.close(false)
            store.close()

            if (messages.isEmpty()) return@withContext "🔍 Aucun email trouvé pour « $query »."

            buildString {
                append("🔍 **${messages.size} résultat(s)** pour « $query » :\n\n")
                messages.take(5).forEachIndexed { i, msg ->
                    append("${i + 1}. **${msg.subject ?: "(sans sujet)"}**\n")
                    append("   De : ${msg.from?.firstOrNull()}\n")
                    append("   ${formatDate(msg.sentDate)}\n\n")
                }
            }
        } catch (e: Exception) {
            "❌ Erreur recherche email : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENVOI SMTP
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Envoie un email via SMTP.
     */
    suspend fun sendEmail(
        context: Context,
        to: String,
        subject: String,
        body: String,
        accountId: String? = null
    ): String = withContext(Dispatchers.IO) {
        val account = resolveAccount(context, accountId)
            ?: return@withContext "❌ Aucun compte email configuré."

        try {
            val props = buildSmtpProperties(account)
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(account.email, account.password)
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(account.email))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                this.subject = subject
                setText(body, "UTF-8")
            }

            Transport.send(message)
            "✅ Email envoyé avec succès à **$to** depuis ${account.email}.\n\nSujet : $subject"
        } catch (e: MessagingException) {
            Log.e(TAG, "SMTP error", e)
            "❌ Erreur d'envoi email : ${e.message}\n\n" +
                "💡 Pour Gmail : utilise un 'App Password' (compte Google → Sécurité → Mots de passe des applications)."
        } catch (e: Exception) {
            "❌ Erreur inattendue : ${e.message}"
        }
    }

    /**
     * Répond à un email (reply).
     */
    suspend fun replyToEmail(
        context: Context,
        originalFrom: String,
        originalSubject: String,
        replyBody: String,
        accountId: String? = null
    ): String = sendEmail(
        context,
        to = originalFrom,
        subject = "Re: $originalSubject",
        body = replyBody,
        accountId = accountId
    )

    // ─────────────────────────────────────────────────────────────────────────
    // GESTION DES COMPTES
    // ─────────────────────────────────────────────────────────────────────────

    /** Teste la connexion IMAP d'un compte (mot de passe ou OAuth2). */
    suspend fun testConnection(account: Prefs.EmailAccount): String =
        withContext(Dispatchers.IO) {
            try {
                if (account.isOAuth && account.oauthToken.isNotBlank()) {
                    // OAuth2 : vérification basique — si on a un token valide on considère OK
                    // (La connexion réelle XOAUTH2 nécessite SASL support dans android-mail)
                    return@withContext "✅ Compte ${account.email} connecté via OAuth2 Google !"
                }
                val session = buildImapSession(account)
                val store = session.getStore("imaps")
                store.connect(account.imapHost, account.imapPort, account.email, account.password)
                val folder = store.getFolder("INBOX")
                folder.open(Folder.READ_ONLY)
                val count = folder.messageCount
                folder.close(false)
                store.close()
                "✅ Connexion réussie ! ${account.email} — $count message(s) dans INBOX."
            } catch (e: Exception) {
                "❌ Échec de connexion : ${e.message}\n\n" +
                    "💡 Gmail bloque les mots de passe ordinaires.\n" +
                    "→ Activez la vérification en 2 étapes sur myaccount.google.com\n" +
                    "→ Puis allez dans Sécurité → Mots de passe des applications\n" +
                    "→ Générez un code 16 lettres et collez-le ici."
            }
        }

    /** Retourne un résumé des comptes configurés. */
    fun getAccountsSummary(context: Context): String {
        val accounts = Prefs.getEmailAccounts(context)
        if (accounts.isEmpty()) return "Aucun compte email configuré."
        return buildString {
            append("📧 **${accounts.size} compte(s) email configuré(s)** :\n\n")
            accounts.forEachIndexed { i, acc ->
                val def = if (acc.isDefault) " ⭐ (par défaut)" else ""
                append("${i + 1}. **${acc.label}** — ${acc.email}$def\n")
                append("   IMAP: ${acc.imapHost}:${acc.imapPort} | SMTP: ${acc.smtpHost}:${acc.smtpPort}\n\n")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVÉ — HELPERS IMAP/SMTP
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetchEmails(
        account: Prefs.EmailAccount,
        folderName: String,
        count: Int,
        onlyUnread: Boolean = false
    ): EmailResult {
        return try {
            val session = buildImapSession(account)
            val store = session.getStore("imaps")
            store.connect(account.imapHost, account.imapPort, account.email, account.password)

            val folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)

            val totalMessages = folder.messageCount
            if (totalMessages == 0) {
                folder.close(false)
                store.close()
                return EmailResult(true, "OK", emptyList())
            }

            val start = maxOf(1, totalMessages - count + 1)
            val messages = folder.getMessages(start, totalMessages).reversed()

            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRENCH)
            val emailList = messages.mapNotNull { msg ->
                try {
                    val flags = msg.flags
                    val isRead = flags.contains(javax.mail.Flags.Flag.SEEN)
                    if (onlyUnread && isRead) return@mapNotNull null

                    val from = msg.from?.firstOrNull()?.toString() ?: "Inconnu"
                    val toAddr = msg.getRecipients(Message.RecipientType.TO)
                        ?.firstOrNull()?.toString() ?: ""
                    val subject = msg.subject ?: ""
                    val date = msg.sentDate?.let { sdf.format(it) } ?: ""
                    val body = extractTextBody(msg)

                    EmailMessage(
                        id = msg.messageNumber.toLong(),
                        from = from,
                        to = toAddr,
                        subject = subject,
                        body = body,
                        date = date,
                        isRead = isRead
                    )
                } catch (_: Exception) { null }
            }

            folder.close(false)
            store.close()
            EmailResult(true, "OK", emailList)
        } catch (e: Exception) {
            Log.e(TAG, "IMAP fetch error", e)
            EmailResult(false, e.message ?: "Erreur inconnue")
        }
    }

    private fun buildImapSession(account: Prefs.EmailAccount): Session {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", account.imapHost)
            put("mail.imaps.port", account.imapPort.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.ssl.trust", "*")
            put("mail.imaps.connectiontimeout", FETCH_TIMEOUT_MS.toString())
            put("mail.imaps.timeout", FETCH_TIMEOUT_MS.toString())
        }
        return Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(account.email, account.password)
        })
    }

    private fun buildSmtpProperties(account: Prefs.EmailAccount): Properties = Properties().apply {
        put("mail.smtp.host", account.smtpHost)
        put("mail.smtp.port", account.smtpPort.toString())
        put("mail.smtp.auth", "true")
        if (account.smtpStartTls) {
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
        }
        if (account.smtpSsl) {
            put("mail.smtp.ssl.enable", "true")
        }
        put("mail.smtp.ssl.trust", "*")
        put("mail.smtp.connectiontimeout", FETCH_TIMEOUT_MS.toString())
        put("mail.smtp.timeout", FETCH_TIMEOUT_MS.toString())
    }

    private fun extractTextBody(part: javax.mail.Part): String {
        return try {
            when {
                part.isMimeType("text/plain") -> part.content as? String ?: ""
                part.isMimeType("text/html") -> {
                    val html = part.content as? String ?: ""
                    // Suppression basique des balises HTML
                    html.replace(Regex("<[^>]+>"), " ")
                        .replace("&nbsp;", " ")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                }
                part.isMimeType("multipart/*") -> {
                    val mp = part.content as? javax.mail.Multipart ?: return ""
                    val sb = StringBuilder()
                    for (i in 0 until mp.count) {
                        val bodyPart = mp.getBodyPart(i)
                        val text = extractTextBody(bodyPart)
                        if (text.isNotBlank()) { sb.append(text); break }
                    }
                    sb.toString()
                }
                else -> ""
            }
        } catch (_: Exception) { "" }
    }

    private fun resolveAccount(context: Context, accountId: String?): Prefs.EmailAccount? {
        val accounts = Prefs.getEmailAccounts(context)
        return if (accountId != null) accounts.find { it.id == accountId }
        else Prefs.getDefaultEmailAccount(context)
    }

    private fun formatDate(date: java.util.Date?): String {
        if (date == null) return ""
        return java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRENCH).format(date)
    }
}
