package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/** Conversations archivées : liste, recherche, reprise (partagé entre la page Conversations, le chat et l'outil de Jarvis). */
object Conversations {
    data class Info(val id: String, val titre: String, val count: Int, val ts: Long, val apercu: String, val emojis: Int, val texte: String)

    fun dossier(app: JarvisApp) = File(app.filesDir, "convs").apply { mkdirs() }

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun emojis(t: String) = t.codePoints().filter { it >= 0x1F300 || Character.getType(it) == Character.OTHER_SYMBOL.toInt() }.count().toInt()

    fun serialiser(list: List<ChatMsg>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("u", it.fromUser).put("t", it.text).put("ts", it.ts)) }
        return arr.toString()
    }

    fun lire(f: File): List<ChatMsg> = runCatching {
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> ChatMsg(o.getBoolean("u"), o.getString("t"), o.optLong("ts")) } }
    }.getOrDefault(emptyList())

    fun liste(app: JarvisApp): List<Info> = (dossier(app).listFiles() ?: emptyArray()).mapNotNull { f ->
        runCatching {
            val l = lire(f)
            if (l.isEmpty()) return@runCatching null
            val titre = l.firstOrNull { it.fromUser }?.text?.lineSequence()?.firstOrNull()?.take(70) ?: "Conversation"
            val dernier = l.lastOrNull { !it.fromUser }?.text?.replace(Regex("[#*`>|_]"), "")?.replace(Regex("\\s+"), " ")?.trim()?.take(110).orEmpty()
            val tout = l.joinToString("\n") { it.text }
            Info(f.nameWithoutExtension, titre, l.size, l.first().ts.takeIf { it > 0 } ?: f.lastModified(), dernier, emojis(l.filter { !it.fromUser }.joinToString("") { it.text }), norm(tout))
        }.getOrNull()
    }.sortedByDescending { it.ts }

    /** Classe les conversations selon des mots-clés (titre, contenu ; « emoji » favorise celles qui en contiennent beaucoup). */
    fun chercher(app: JarvisApp, requete: String): List<Info> {
        val tous = liste(app)
        val q = norm(requete).trim()
        if (q.isBlank()) return tous
        val veutEmoji = q.contains("emoji")
        val mots = q.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && !it.startsWith("emoji") && it !in setOf("conversation", "celle", "avec", "les", "des", "une", "reprendre", "remonte", "dans", "pour") }
        return tous.map { c ->
            var sc = mots.sumOf { m -> (if (norm(c.titre).contains(m)) 5 else 0) + minOf(c.texte.split(m).size - 1, 4) }
            if (veutEmoji) sc += minOf(c.emojis, 40) / 2
            c to sc
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Info, Int>> { it.second }.thenByDescending { it.first.ts }).map { it.first }
    }

    /** Range la conversation en cours dans l'historique. */
    fun archiverCourante(app: JarvisApp) {
        val l = app.chat.toList()
        if (l.none { it.fromUser }) return
        if (app.convId.isBlank()) app.convId = (l.first().ts.takeIf { it > 0 } ?: System.currentTimeMillis()).toString()
        runCatching { File(dossier(app), app.convId + ".json").writeText(serialiser(l)) }
    }

    /** Remplace la conversation affichée par l'archive [id] (à appeler sur le thread principal). */
    fun ouvrir(app: JarvisApp, id: String): Boolean {
        val f = File(dossier(app), "$id.json")
        if (!f.exists()) return false
        val l = lire(f)
        if (id != app.convId) archiverCourante(app)
        app.chat.clear(); app.chat.addAll(l)
        app.convId = id
        app.chatSauver()
        return true
    }
}
