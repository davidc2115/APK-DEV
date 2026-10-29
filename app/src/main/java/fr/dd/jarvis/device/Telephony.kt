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
                val nom = if (app.contacts.hasPermission()) app.contacts.search(addr.takeLast(9), 1).firstOrNull()?.name else null
                val envoye = c.getInt(3) == 2
                out.append("- ${if (envoye) "➡️ à" else "⬅️ de"} **${nom ?: addr}** _(${fmt.format(java.util.Date(c.getLong(2)))})_ : ${c.getString(1)?.take(300)}\n")
                n++
            }
        }
        return if (n == 0) "Aucun SMS trouvé." else "# 💬 SMS ($n)\n\n$out"
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
