package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Garde-fou commun aux fournisseurs IA, PAR PALIERS :
 *  - palier 0 (envoi normal) : seules les limites dures des fournisseurs sont appliquées (128 outils au plus chez Groq,
 *    contenus démesurés bornés). Le prompt système, les descriptions d'outils et les résultats d'outils restent complets ;
 *  - paliers 1 à 3 : seulement quand le fournisseur refuse la requête (413, limite de jetons par minute) ou quand la
 *    limite apprise du compte l'impose d'avance (voir Llm). Compactage de plus en plus fort.
 *
 * Avant (19/11), le palier « 1 » était appliqué à CHAQUE envoi : prompt système coupé à 6 000 caractères (règles Home
 * Assistant, notes utiles, mémoire et date perdues), résultats d'outils à 1 100, descriptions d'outils à 180, et au-delà
 * de 128 outils ceux du milieu étaient retirés à l'aveugle — souvent les outils Home Assistant.
 *
 * Le prompt système est raccourci en gardant son DÉBUT (règles) et sa FIN (notes utiles, contexte, date et heure).
 */
internal object BudgetRequeteIA {
    const val MAX_OUTILS = 128

    private data class Palier(
        val systemeTete: Int, val systemeQueue: Int, val outil: Int, val dernierUser: Int, val autres: Int,
        val anciensGardes: Int, val total: Int, val description: Int, val propriete: Int,
    )

    private val PALIERS = listOf(
        Palier(48_000, 12_000, 12_000, 12_000, 12_000, 10, 180_000, Int.MAX_VALUE, Int.MAX_VALUE),
        Palier(9_000, 3_500, 3_000, 4_000, 900, 6, 40_000, 400, 150),
        Palier(5_000, 2_000, 1_500, 2_500, 450, 3, 24_000, 180, 80),
        Palier(2_800, 1_200, 700, 1_800, 300, 2, 14_000, 90, 0),
    )

    private fun palier(niveau: Int) = PALIERS[niveau.coerceIn(0, PALIERS.lastIndex)]

    /** Raccourcit en gardant le début et la fin (la fin du prompt système porte les notes utiles et la date). */
    internal fun teteEtQueue(texte: String, tete: Int, queue: Int): String {
        if (texte.length <= tete + queue + 40) return texte
        return texte.take(tete).trimEnd() + "\n…\n" + texte.takeLast(queue).trimStart()
    }

    fun messages(source: JSONArray, niveau: Int = 0): JSONArray {
        val p = palier(niveau)
        val items = (0 until source.length()).mapNotNull { source.optJSONObject(it) }
        val dernierUser = items.indexOfLast { it.optString("role") == "user" }
        val anciens = items.withIndex()
            .filter { (i, m) -> i < dernierUser && m.optString("role") in setOf("user", "assistant") && !m.has("tool_calls") }
            .map { it.index }
        val aGarder = anciens.takeLast(p.anciensGardes).toSet()
        val out = JSONArray()
        items.forEachIndexed { i, original ->
            val role = original.optString("role")
            if (i < dernierUser && role in setOf("user", "assistant") && !original.has("tool_calls") && i !in aGarder) return@forEachIndexed
            val copy = JSONObject(original.toString())
            if (copy.opt("content") is String) {
                val value = copy.optString("content")
                val nouveau = when (role) {
                    "system" -> teteEtQueue(value, p.systemeTete, p.systemeQueue)
                    "tool" -> if (value.length > p.outil) value.take(p.outil) + "…" else value
                    "user" -> { val l = if (i == dernierUser) p.dernierUser else p.autres; if (value.length > l) value.take(l) + "…" else value }
                    else -> if (value.length > p.autres) value.take(p.autres) + "…" else value
                }
                copy.put("content", nouveau)
            }
            out.put(copy)
        }

        // Si une succession d'outils dépasse encore le budget, réduire les anciens résultats en premier
        // (jamais le prompt système, ni la dernière demande, ni le dernier résultat d'outil).
        var total = taille(out)
        if (total > p.total) {
            val dernierUserSortie = (0 until out.length()).lastOrNull { out.optJSONObject(it)?.optString("role") == "user" } ?: -1
            val dernierOutil = (0 until out.length()).lastOrNull { out.optJSONObject(it)?.optString("role") == "tool" } ?: -1
            val minimumOutil = if (niveau == 0) 2_000 else if (niveau >= 2) 80 else 160
            val minimumTexte = if (niveau == 0) 600 else if (niveau >= 2) 120 else 180
            for (i in 0 until out.length()) {
                if (total <= p.total) break
                val item = out.optJSONObject(i) ?: continue
                val role = item.optString("role")
                if (role == "system" || i == dernierUserSortie || i == dernierOutil) continue
                val value = item.optString("content")
                if (value.isBlank()) continue
                val minimum = if (role == "tool") minimumOutil else minimumTexte
                if (value.length <= minimum) continue
                item.put("content", value.take(minimum) + "…")
                total = taille(out)
            }
        }
        return out
    }

    /**
     * Schéma d'outils au palier [niveau]. Au-delà de 128 outils (limite Groq), garde d'abord [prioritaires], puis les
     * outils les plus proches de [requete] ; sans ces indications (appel générique), le début et la fin de la liste.
     */
    fun outils(source: JSONArray?, niveau: Int = 0, prioritaires: Set<String> = emptySet(), requete: String = ""): JSONArray? {
        if (source == null) return null
        val p = palier(niveau)
        val taille = source.length()
        val indices = if (taille <= MAX_OUTILS) (0 until taille).toList()
            else if (prioritaires.isEmpty() && requete.isBlank()) (0 until 96).toList() + (taille - 32 until taille).toList()
            else selection(source, prioritaires, requete)
        val out = JSONArray()
        indices.forEach { index ->
            val item = source.optJSONObject(index) ?: return@forEach
            if (p.description == Int.MAX_VALUE) { out.put(item); return@forEach }
            val copy = JSONObject(item.toString())
            val fn = copy.optJSONObject("function") ?: return@forEach
            fn.put("description", fn.optString("description").take(p.description))
            fn.optJSONObject("parameters")?.optJSONObject("properties")?.let { properties ->
                val keys = properties.keys()
                while (keys.hasNext()) {
                    val prop = properties.optJSONObject(keys.next()) ?: continue
                    if (!prop.has("description")) continue
                    if (p.propriete <= 0) prop.remove("description") else prop.put("description", prop.optString("description").take(p.propriete))
                }
            }
            out.put(copy)
        }
        return out
    }

    /** Indices des 128 outils gardés : prioritaires, puis par pertinence pour la demande, dans l'ordre d'origine. */
    private fun selection(source: JSONArray, prioritaires: Set<String>, requete: String): List<Int> {
        val mots = fr.dd.jarvis.societe.core.normaliser(requete).split(' ').filter { it.length >= 4 }.toSet()
        fun nom(i: Int) = source.optJSONObject(i)?.optJSONObject("function")?.optString("name").orEmpty()
        val gardes = LinkedHashSet<Int>()
        for (i in 0 until source.length()) if (nom(i) in prioritaires) gardes += i
        val restants = (0 until source.length()).filter { it !in gardes }
        val scores = restants.associateWith { i ->
            val f = source.optJSONObject(i)?.optJSONObject("function")
            val texte = " " + fr.dd.jarvis.societe.core.normaliser((f?.optString("name").orEmpty()).replace('_', ' ') + " " + f?.optString("description").orEmpty().take(400)) + " "
            mots.count { texte.contains(" ${it.take(6)}") }
        }
        restants.sortedWith(compareByDescending<Int> { scores.getValue(it) }.thenBy { it })
            .take((MAX_OUTILS - gardes.size).coerceAtLeast(0)).forEach { gardes += it }
        return gardes.take(MAX_OUTILS).sorted()
    }

    /** Taille approximative en jetons (texte français + JSON : ~3,3 caractères par jeton, arrondi par excès). */
    fun jetons(messages: JSONArray, outils: JSONArray?): Int =
        ((taille(messages) + (outils?.toString()?.length ?: 0)) / 3.3).toInt() + 1

    private fun taille(messages: JSONArray): Int =
        (0 until messages.length()).sumOf { messages.optJSONObject(it)?.toString()?.length ?: 0 }
}
