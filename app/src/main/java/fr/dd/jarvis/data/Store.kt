package fr.dd.jarvis.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Stockage des réglages. Les clés API sont chiffrées (EncryptedSharedPreferences).
 * Tous les champs sont observables par Compose.
 */
class Store(context: Context) {

    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "jarvis_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Throwable) {
        // Keystore indisponible sur certains appareils : repli non chiffré plutôt que de planter.
        context.getSharedPreferences("jarvis_plain", Context.MODE_PRIVATE)
    }

    inner class SPref(private val key: String, def: String) {
        var value by mutableStateOf(prefs.getString(key, def) ?: def)
            private set

        fun set(v: String) {
            value = v
            prefs.edit().putString(key, v).apply()
        }
    }

    inner class BPref(private val key: String, def: Boolean) {
        var value by mutableStateOf(prefs.getBoolean(key, def))
            private set

        fun set(v: Boolean) {
            value = v
            prefs.edit().putBoolean(key, v).apply()
        }
    }

    inner class IPref(private val key: String, def: Int) {
        var value by mutableStateOf(prefs.getInt(key, def))
            private set

        fun set(v: Int) {
            value = v
            prefs.edit().putInt(key, v).apply()
        }
    }

    inner class FPref(private val key: String, def: Float) {
        var value by mutableStateOf(prefs.getFloat(key, def))
            private set

        fun set(v: Float) {
            value = v
            prefs.edit().putFloat(key, v).apply()
        }
    }

    // ---- Profil / apparence
    val userName = SPref("userName", "")
    val accent = SPref("accent", "cyan")
    val tileAlpha = FPref("tileAlpha", 0.16f)

    // ---- Cerveau (Groq) et agents (Gemini)
    val groqModel = SPref("groqModel", "llama-3.3-70b-versatile")
    val geminiModel = SPref("geminiModel", "gemini-3.8-flash")
    val groqKeys: SnapshotStateList<String> = mutableStateListOf<String>().also { it.addAll(readList("groqKeys")) }
    val geminiKeys: SnapshotStateList<String> = mutableStateListOf<String>().also { it.addAll(readList("geminiKeys")) }

    // ---- Génération d'images (Cloudflare Workers AI) : lignes « idCompte:jeton », rotation automatique
    val cfModel = SPref("cfModel", "@cf/black-forest-labs/flux-1-schnell")
    val cfAccounts: SnapshotStateList<String> = mutableStateListOf<String>().also { it.addAll(readList("cfAccounts")) }
    /** Ajoute un compte (ID + clé API). Retourne un message d'erreur, ou null si c'est bon. */
    fun addCfAccount(idRaw: String, keyRaw: String): String? {
        var id = idRaw.trim(); var key = keyRaw.trim()
        if (id.contains(':') && key.isEmpty()) { key = id.substringAfter(':').trim(); id = id.substringBefore(':').trim() }
        else if (key.contains(':') && id.isEmpty()) { id = key.substringBefore(':').trim(); key = key.substringAfter(':').trim() }
        id = id.replace(Regex("^.*/accounts/"), "").substringBefore('/').trim()
        if (id.isEmpty()) return "ID de compte manquant."
        if (key.isEmpty()) return "Clé API manquante."
        if (!Regex("[0-9a-fA-F]{32}").matches(id)) return "L'ID de compte doit faire 32 caractères hexadécimaux (visible dans l'URL dash.cloudflare.com/<ID>/…)."
        if (key.length < 20 || key.any { it.isWhitespace() }) return "Clé API invalide (jeton Workers AI, sans espace)."
        val entry = "$id:$key"
        if (entry in cfAccounts) return "Ce compte est déjà enregistré."
        cfAccounts.add(entry); writeList("cfAccounts", cfAccounts)
        prefs.edit().commit()
        return null
    }
    fun removeCfAccount(i: Int) { if (i in cfAccounts.indices) cfAccounts.removeAt(i); writeList("cfAccounts", cfAccounts) }

    fun addGroqKeys(raw: String) = addKeys(groqKeys, "groqKeys", raw)
    fun addGeminiKeys(raw: String) = addKeys(geminiKeys, "geminiKeys", raw)
    fun removeGroqKey(i: Int) {
        if (i in groqKeys.indices) groqKeys.removeAt(i)
        writeList("groqKeys", groqKeys)
    }

    fun removeGeminiKey(i: Int) {
        if (i in geminiKeys.indices) geminiKeys.removeAt(i)
        writeList("geminiKeys", geminiKeys)
    }

    private fun addKeys(list: SnapshotStateList<String>, k: String, raw: String) {
        val parts = raw.split(Regex("[\\s,;]+")).map { it.trim() }
            .filter { it.length >= 8 && it !in list }.distinct()
        list.addAll(parts)
        writeList(k, list)
    }

    private fun readList(k: String): List<String> =
        (prefs.getString(k, "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    private fun writeList(k: String, l: List<String>) {
        prefs.edit().putString(k, l.joinToString("\n")).apply()
    }

    // ---- Voix
    val speakReplies = BPref("speakReplies", true)
    val conversationMode = BPref("conversationMode", false)

    // ---- Sécurité
    val confirmSensitive = BPref("confirmSensitive", true)
    val analyseGemini = BPref("analyseGemini", true)

    // ---- Arrière-plan : écoute « Jarvis » et agents autonomes
    val wakeOn = BPref("wakeOn", false)
    val convSeconds = IPref("convSeconds", 25)
    val muteBeeps = BPref("muteBeeps", true)
    val agentsOn = BPref("agentsOn", false)
    val agentMails = BPref("agentMails", true)
    val agentFactures = BPref("agentFactures", true)
    val agentPlanning = BPref("agentPlanning", true)
    val agentNotifs = BPref("agentNotifs", true)
    val agentRanger = BPref("agentRanger", false)
    val agentMinutes = IPref("agentMinutes", 15)
    val agentLastFact = SPref("agentLastFact", "")
    val agentLastNotif = SPref("agentLastNotif", "0")

    // ---- Mails (IMAP)
    val mailHost = SPref("mailHost", "imap.gmail.com")
    val mailPort = SPref("mailPort", "993")
    val mailUser = SPref("mailUser", "")
    val mailPass = SPref("mailPass", "")
    val gmailAccounts: SnapshotStateList<String> = mutableStateListOf<String>().also { it.addAll(readList("gmailAccounts")) }

    fun addGmailAccount(email: String) {
        if (email.isNotBlank() && gmailAccounts.none { it.equals(email, true) }) gmailAccounts.add(email)
        writeList("gmailAccounts", gmailAccounts)
    }

    fun removeGmailAccount(email: String) {
        gmailAccounts.removeAll { it == email }
        writeList("gmailAccounts", gmailAccounts)
    }

    /** Comptes mis de côté : conservés, mais ignorés par JARVIS sauf demande explicite (« IMAP » désigne le compte IMAP). */
    val disabledMail: SnapshotStateList<String> = mutableStateListOf<String>().also { it.addAll(readList("disabledMail")) }
    fun mailActive(account: String) = disabledMail.none { it.equals(account, true) }
    fun setMailActive(account: String, active: Boolean) {
        disabledMail.removeAll { it.equals(account, true) }
        if (!active) disabledMail.add(account)
        writeList("disabledMail", disabledMail)
    }

    /** Préfixe des libellés Gmail créés par JARVIS (vide = « Urgent », « Clients »… sans « JARVIS/ » devant). */
    val mailLabelPrefix = SPref("mailLabelPrefix", "")

    fun mailConfigured() = gmailAccounts.isNotEmpty() || mailHost.value.isNotBlank() && mailUser.value.isNotBlank() && mailPass.value.isNotBlank()

    // ---- Obsidian (dossier choisi via le sélecteur Android)
    val obsidianUri = SPref("obsidianUri", "")

    // ---- Briefing du matin
    val briefingOn = BPref("briefingOn", false)
    val briefingHour = IPref("briefingHour", 7)
    val briefingMinute = IPref("briefingMinute", 30)
    val lastBriefing = SPref("lastBriefing", "")

    // ---- Agendas : alias, couleurs, visibilité (réglages locaux, n'altèrent pas les agendas du téléphone)
    val calAlias = mutableStateMapOf<Long, String>().also { it.putAll(readMapStr("calAlias")) }
    val calColor = mutableStateMapOf<Long, Int>().also { it.putAll(readMapInt("calColor")) }
    val eventColor = mutableStateMapOf<Long, Int>().also { it.putAll(readMapInt("eventColor")) }
    val hiddenCals = mutableStateListOf<Long>().also { it.addAll(readList("hiddenCals").mapNotNull { s -> s.toLongOrNull() }) }

    fun setCalAlias(id: Long, name: String) {
        if (name.isBlank()) calAlias.remove(id) else calAlias[id] = name.trim()
        writeMapStr("calAlias", calAlias)
    }

    fun setCalColor(id: Long, color: Int?) {
        if (color == null) calColor.remove(id) else calColor[id] = color
        writeMapInt("calColor", calColor)
    }

    fun setEventColor(eventId: Long, color: Int?) {
        if (color == null) eventColor.remove(eventId) else eventColor[eventId] = color
        writeMapInt("eventColor", eventColor)
    }

    fun toggleCalendar(id: Long) {
        if (id in hiddenCals) hiddenCals.remove(id) else hiddenCals.add(id)
        writeList("hiddenCals", hiddenCals.map { it.toString() })
    }

    private fun readMapStr(k: String): Map<Long, String> = try {
        val o = JSONObject(prefs.getString(k, "{}") ?: "{}")
        o.keys().asSequence().mapNotNull { key -> key.toLongOrNull()?.let { it to o.getString(key) } }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    private fun readMapInt(k: String): Map<Long, Int> = try {
        val o = JSONObject(prefs.getString(k, "{}") ?: "{}")
        o.keys().asSequence().mapNotNull { key -> key.toLongOrNull()?.let { it to o.getInt(key) } }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    private fun writeMapStr(k: String, m: Map<Long, String>) {
        val o = JSONObject()
        m.forEach { (key, v) -> o.put(key.toString(), v) }
        prefs.edit().putString(k, o.toString()).apply()
    }

    private fun writeMapInt(k: String, m: Map<Long, Int>) {
        val o = JSONObject()
        m.forEach { (key, v) -> o.put(key.toString(), v) }
        prefs.edit().putString(k, o.toString()).apply()
    }
}
