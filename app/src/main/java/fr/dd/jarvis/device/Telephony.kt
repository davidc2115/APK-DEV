package fr.dd.jarvis.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager

/** Lecture / envoi de SMS et appels directs (permissions SMS et Téléphone à accorder dans Réglages). */
object Telephony {
    private fun ok(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun smsGranted(ctx: Context) = ok(ctx, Manifest.permission.READ_SMS) && ok(ctx, Manifest.permission.SEND_SMS)
    fun callGranted(ctx: Context) = ok(ctx, Manifest.permission.CALL_PHONE)

    /** Résout un nom ou un numéro en (nom affiché, numéro). */
    fun resolve(app: fr.dd.jarvis.JarvisApp, quoi: String): Pair<String, String>? {
        val q = quoi.trim()
        if (q.isBlank()) return null
        if (q.matches(Regex("[+0-9 .()-]{6,}"))) return q to q.filter { it.isDigit() || it == '+' }
        if (!app.contacts.hasPermission()) return null
        val c = app.contacts.search(q, 5).firstOrNull { it.phones.isNotEmpty() } ?: return null
        return c.name to c.phones.first().filter { it.isDigit() || it == '+' }
    }

    /** Identifie un numéro dans les contacts Android, les clients de la société et les fiches Obsidian. */
    fun identifierAppel(app: fr.dd.jarvis.JarvisApp, numero: String): String? {
        val digits = numero.filter { it.isDigit() }
        if (digits.length < 8) return null
        val key = digits.takeLast(8)
        val matches = linkedSetOf<String>()

        // 1) Carnet d'adresses natif Android.
        if (app.contacts.hasPermission()) {
            runCatching {
                app.contacts.search(key, 500).forEach { contact ->
                    if (contact.phones.any { it.filter(Char::isDigit).takeLast(8) == key }) {
                        matches += "${contact.name} (contact du téléphone)"
                    }
                }
            }
        }

        // 2) Répertoire clients intégré à JARVIS.
        runCatching {
            app.societe.data.clients.forEach { client ->
                val nums = listOf(client.tel, client.contact)
                if (nums.any { it.filter(Char::isDigit).let { d -> d.length >= 8 && d.takeLast(8) == key } }) {
                    matches += "${client.nom} (client)"
                }
            }
        }

        // 3) Fiches du coffre Obsidian : on confirme le numéro dans le contenu, pas seulement le nom du fichier.
        runCatching {
            app.obsidian.searchNotes(key, 30).forEach { (path, _) ->
                val note = app.obsidian.readNote(path).orEmpty()
                val numbers = Regex("""(?:\+?\d[\d .()/-]{6,}\d)""").findAll(note)
                    .map { it.value.filter(Char::isDigit) }
                if (numbers.any { it.length >= 8 && it.takeLast(8) == key }) {
                    matches += "${path.substringAfterLast('/').removeSuffix(".md")} (Obsidian)"
                }
            }
        }
        return matches.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun lire(app: fr.dd.jarvis.JarvisApp, count: Int, de: String, nonLus: Boolean): String {
        if (!ok(app, Manifest.permission.READ_SMS)) return "Permission SMS non accordée (Réglages › Contrôle du téléphone › Autoriser SMS et appels)."
        val num = if (de.isBlank()) null else (resolve(app, de)?.second ?: de)
        val sel = mutableListOf<String>(); val args = mutableListOf<String>()
        if (nonLus) sel += "read=0"
        val out = StringBuilder()
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRENCH)
        var n = 0
        app.contentResolver.query(Uri.parse("content://sms"), arrayOf("address", "body", "date", "type", "read"),
            sel.joinToString(" AND ").ifBlank { null }, null, "date DESC")?.use { c ->
            while (c.moveToNext() && n < count) {
                val addr = c.getString(0) ?: ""
                if (num != null && !addr.filter { it.isDigit() }.endsWith(num.filter { it.isDigit() }.takeLast(9))) continue
                val nom = nomDe(app, addr)
                val envoye = c.getInt(3) == 2
                out.append("- ${if (envoye) "➡️ à" else "⬅️ de"} **${nom ?: addr}** _(${fmt.format(java.util.Date(c.getLong(2)))})_ : ${c.getString(1)?.let { fr.dd.jarvis.securite.AntiVol.masquer(it) }?.take(300)}\n")
                n++
            }
        }
        // Les messages RCS / WhatsApp… ne sont pas dans la base SMS : on ajoute ceux vus en direct dans les notifications.
        val vus = StringBuilder(); var m = 0
        if (num == null) {
            fr.dd.jarvis.service.JarvisNotificationListener.recharger(app)
            fr.dd.jarvis.service.JarvisNotificationListener.snapshot().filter { fr.dd.jarvis.service.JarvisNotificationListener.estMessage(it.pkg) }
                .distinctBy { it.titre + it.texte }.take(count).forEach {
                    vus.append("- ⬅️ **${it.titre.ifBlank { it.app }}** _(${it.app}, ${fmt.format(java.util.Date(it.time))})_ : ${it.texte.take(300)}\n"); m++
                }
        }
        if (n == 0 && m == 0) return "Aucun SMS trouvé." + (if (!fr.dd.jarvis.service.JarvisNotificationListener.active) " (Active aussi l'accès aux notifications pour voir les messages RCS / WhatsApp en direct.)" else "")
        return buildString {
            if (n > 0) append("# 💬 SMS ($n)\n\n$out\n")
            if (m > 0) append("# 🔔 Messages reçus en direct ($m)\n\n$vus")
        }
    }


    /** Recherche SMS sur l'historique local par contact, texte et plage de dates inclusives. */
    fun rechercher(app: fr.dd.jarvis.JarvisApp, contact: String, terme: String, du: String, au: String, count: Int): String {
        if (!ok(app, Manifest.permission.READ_SMS)) return "Permission SMS non accordée (Réglages › Contrôle du téléphone)."
        val numero = if (contact.isBlank()) null else resolve(app, contact)?.second ?: contact
        val zone = java.time.ZoneId.systemDefault()
        fun borne(value: String, fin: Boolean): Long? = runCatching {
            val d = java.time.LocalDate.parse(value.take(10))
            (if (fin) d.plusDays(1).atStartOfDay(zone).toInstant() else d.atStartOfDay(zone).toInstant()).toEpochMilli()
        }.getOrNull()
        val min = borne(du, false); val max = borne(au, true)
        val clauses = mutableListOf<String>(); val args = mutableListOf<String>()
        min?.let { clauses += "date >= ?"; args += it.toString() }
        max?.let { clauses += "date < ?"; args += it.toString() }
        val out = mutableListOf<String>()
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy 'à' HH:mm:ss", java.util.Locale.FRENCH)
        app.contentResolver.query(Uri.parse("content://sms"), arrayOf("address", "body", "date", "type"), clauses.joinToString(" AND ").ifBlank { null }, args.toTypedArray().takeIf { it.isNotEmpty() }, "date DESC")?.use { c ->
            while (c.moveToNext() && out.size < 2000) {
                val addr = c.getString(0).orEmpty()
                if (numero != null && !addr.filter(Char::isDigit).endsWith(numero.filter(Char::isDigit).takeLast(9))) continue
                val body = fr.dd.jarvis.securite.AntiVol.masquer(c.getString(1).orEmpty())
                if (terme.isNotBlank() && !body.contains(terme, true) && !addr.contains(terme, true)) continue
                val sent = c.getInt(3) == 2
                out += "• ${if (sent) "Envoyé à" else "Reçu de"} ${nomDe(app, addr) ?: addr} — ${fmt.format(java.util.Date(c.getLong(2)))}\n$body"
            }
        }
        if (out.isEmpty()) return "Aucun SMS ne correspond à ces critères."
        return "# Recherche SMS (${out.size})\n\n" + out.take(count.coerceIn(1, 30)).joinToString("\n\n")
    }

    /** Nom du contact pour un numéro (tolère les espaces, +33, 0…). */
    private fun nomDe(app: fr.dd.jarvis.JarvisApp, addr: String): String? {
        if (!app.contacts.hasPermission() || addr.none { it.isDigit() }) return null
        return runCatching {
            app.contentResolver.query(Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(addr)),
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    fun envoyer(ctx: Context, numero: String, texte: String): String {
        if (!ok(ctx, Manifest.permission.SEND_SMS)) return "Permission d'envoi de SMS non accordée."
        return try {
            val sm = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            sm.sendMultipartTextMessage(numero, null, sm.divideMessage(texte), null, null)
            "✅ SMS envoyé à $numero."
        } catch (e: Exception) { "❌ Envoi impossible : ${e.message}" }
    }

    fun appeler(ctx: Context, numero: String): String {
        if (!callGranted(ctx)) return "Permission d'appel non accordée (Réglages › Contrôle du téléphone)."
        return try {
            ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(numero))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "📞 Appel de $numero lancé."
        } catch (e: Exception) { "❌ Appel impossible : ${e.message}" }
    }
}
