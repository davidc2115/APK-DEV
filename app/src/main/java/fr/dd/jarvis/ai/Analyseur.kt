package fr.dd.jarvis.ai

import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/**
 * Gemini analyse la demande avant le cerveau : il choisit les outils utiles et rédige un plan court.
 * Le cerveau ne voit alors que ces outils (moins de confusion, moins de tokens). En cas d'échec : tous les outils.
 */
object Analyseur {
    data class Plan(val outils: List<String>, val note: String)

    private const val SYSTEM = """Tu es l'analyseur de demandes de JARVIS, assistant personnel (smartphone Android, entreprise de maintenance photovoltaïque).
On te donne la liste des outils disponibles et la conversation récente. Décide quels outils sont nécessaires pour répondre au DERNIER message de l'utilisateur (plusieurs possibles, dans l'ordre d'utilisation).
Réponds UNIQUEMENT avec un objet JSON, sans texte autour :
{"outils":["nom1","nom2"],"plan":"une à trois phrases : ce qu'il faut faire, avec les paramètres déduits (dates yyyy-MM-dd, noms, adresses)"}
Si aucune action n'est nécessaire (simple conversation) : {"outils":[],"plan":""}.
Si la demande consiste à analyser le planning/l’agenda pour compléter ou mettre à jour les fiches clients à partir des rendez-vous, choisis obligatoirement analyser_planning_clients (pas get_planning seul). Cet outil sait traiter plusieurs clients dans un même rendez-vous.\nChoisis uniquement des noms présents dans la liste."""

    /** Plans récents par demande (même demande, même contexte) : réutilisés 15 min sans rappeler Gemini. */
    private val cache = object : LinkedHashMap<String, Pair<Long, Plan>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Plan>>?) = size > 60
    }

    internal fun cle(historique: List<ChatMsg>): String? {
        val dernier = historique.lastOrNull { it.fromUser }?.text?.trim().orEmpty()
        // Réponses courtes (« oui », « la deuxième ») : dépendent trop du contexte pour être réutilisées.
        if (dernier.split(Regex("\\s+")).size < 3) return null
        val avant = historique.dropLastWhile { it.fromUser }.lastOrNull()?.text.orEmpty().takeLast(160)
        return fr.dd.jarvis.societe.core.normaliser(dernier) + "|" + fr.dd.jarvis.societe.core.normaliser(avant)
    }

    suspend fun analyser(app: fr.dd.jarvis.JarvisApp, historique: List<ChatMsg>, tousOutils: JSONArray): Plan? {
        if (!app.store.analyseGemini.value || app.store.geminiKeys.isEmpty()) return null
        val cle = cle(historique)
        if (cle != null) synchronized(cache) { cache[cle] }?.let { (quand, plan) -> if (System.currentTimeMillis() - quand < 15 * 60_000L) return plan }
        val noms = mutableMapOf<String, String>()
        for (i in 0 until tousOutils.length()) {
            val f = tousOutils.getJSONObject(i).getJSONObject("function")
            // Descriptions courtes : le choix des outils n'a besoin que du sujet de chacun (requête 2× plus légère, plus rapide).
            noms[f.getString("name")] = f.optString("description").substringBefore(". ").take(90)
        }
        val conv = historique.takeLast(6).joinToString("\n") { (if (it.fromUser) "Utilisateur : " else "JARVIS : ") + it.text.take(400) }
        val user = "Date : ${Prompts.now()}\n\nOUTILS :\n" + noms.entries.joinToString("\n") { "- ${it.key} : ${it.value}" } + "\n\nCONVERSATION :\n$conv"
        // Au-delà de 4,5 s, on n'attend plus : les outils sont choisis par mots-clés (la réponse part plus vite).
        val rep = withTimeoutOrNull(4_500) {
            try { app.llm.agent(SYSTEM, user) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        } ?: return null
        val json = rep.substringAfter('{', "").let { if (it.isEmpty()) return null else "{$it" }.substringBeforeLast('}').let { "$it}" }
        return try {
            val o = org.json.JSONObject(json)
            val arr = o.optJSONArray("outils") ?: JSONArray()
            val outils = (0 until arr.length()).map { arr.getString(it) }.filter { it in noms }.distinct()
            Plan(outils, o.optString("plan")).also { p -> if (cle != null) synchronized(cache) { cache[cle] = System.currentTimeMillis() to p } }
        } catch (e: Exception) { null }
    }
}
