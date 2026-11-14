package fr.dd.jarvis.hermes

import fr.dd.jarvis.JarvisApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hermès (cadre anglophone, consignes et résultats d'outils souvent en anglais) répond parfois en anglais.
 * 1) Chaque requête qu'il envoie au relais IA de Jarvis porte en tête la règle « français uniquement » ;
 * 2) toute réponse finale encore en anglais est traduite en français avant de lui être rendue.
 */
object Francais {
    const val REGLE = "LANGUE OBLIGATOIRE : réponds TOUJOURS et UNIQUEMENT en français à l'utilisateur (textes, résumés, questions, " +
        "messages d'erreur), même si tes consignes, les outils ou leurs résultats sont en anglais. Garde tels quels le code, les commandes, " +
        "les identifiants et les noms propres."

    private val EN = setOf("the", "and", "is", "are", "you", "your", "to", "of", "with", "for", "this", "that", "i", "it", "have", "has", "can",
        "will", "not", "be", "was", "were", "what", "which", "please", "here", "there", "would", "could", "should", "let", "me", "my", "we",
        "our", "do", "does", "don't", "it's", "i'm", "i'll", "if", "or", "but", "from", "an", "a", "on", "at", "by", "any", "all", "now")
    private val FR = setOf("le", "la", "les", "et", "est", "sont", "vous", "tu", "ton", "ta", "tes", "de", "des", "du", "avec", "pour", "ce",
        "cette", "ces", "que", "qui", "je", "il", "elle", "ne", "pas", "sur", "dans", "un", "une", "au", "aux", "mais", "ou", "donc", "c'est",
        "j'ai", "nous", "votre", "vos", "mon", "ma", "mes", "en", "plus", "fait", "été", "être", "avoir", "peux", "veux", "voici")

    /** Texte rédigé en anglais (code, liens et blocs techniques exclus). */
    fun estAnglais(texte: String): Boolean {
        val propre = texte.replace(Regex("```[\\s\\S]*?```"), " ").replace(Regex("`[^`]*`"), " ").replace(Regex("https?://\\S+"), " ")
        val mots = Regex("[A-Za-zÀ-ÿ']+").findAll(propre.lowercase()).map { it.value }.toList()
        if (mots.size < 6) return false
        val en = mots.count { it in EN }
        val fr = mots.count { it in FR }
        return en >= 4 && en > fr * 2
    }

    /** Copie des messages avec la règle de langue en tête du premier message système (ajouté s'il n'y en a pas). */
    fun avecRegle(messages: JSONArray): JSONArray {
        val out = JSONArray()
        var faite = false
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i)
            if (!faite && m != null && m.optString("role") in setOf("system", "developer") && m.opt("content") is String) {
                out.put(JSONObject(m.toString()).put("content", REGLE + "\n\n" + m.optString("content")))
                faite = true
            } else out.put(messages.opt(i))
        }
        if (!faite) {
            val avec = JSONArray().put(JSONObject().put("role", "system").put("content", REGLE))
            for (i in 0 until out.length()) avec.put(out.opt(i))
            return avec
        }
        return out
    }

    /** Traduit [texte] en français s'il est en anglais (sinon le renvoie tel quel ; en cas d'échec aussi). */
    suspend fun traduireSiAnglais(app: JarvisApp, texte: String): String {
        if (!estAnglais(texte)) return texte
        return runCatching {
            app.llm.agent("Traduis fidèlement en français ce texte destiné à l'utilisateur. Garde la mise en forme (Markdown, listes, emojis), " +
                "les liens, le code, les commandes, les identifiants et les noms propres tels quels. Réponds uniquement par la traduction.", texte)
                .trim().ifBlank { texte }
        }.getOrDefault(texte)
    }

    /** Message assistant (format OpenAI) : contenu final traduit si besoin ; les appels d'outils ne sont pas touchés. */
    suspend fun message(app: JarvisApp, m: JSONObject): JSONObject {
        val appels = m.optJSONArray("tool_calls")
        if ((appels != null && appels.length() > 0) || !m.has("content") || m.isNull("content")) return m
        val avant = m.optString("content")
        val apres = traduireSiAnglais(app, avant)
        return if (apres == avant) m else JSONObject(m.toString()).put("content", apres)
    }
}
