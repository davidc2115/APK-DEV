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
Choisis uniquement des noms présents dans la liste."""

    suspend fun analyser(app: fr.dd.jarvis.JarvisApp, historique: List<ChatMsg>, tousOutils: JSONArray): Plan? {
        if (!app.store.analyseGemini.value || app.store.geminiKeys.isEmpty()) return null
        val noms = mutableMapOf<String, String>()
        for (i in 0 until tousOutils.length()) {
            val f = tousOutils.getJSONObject(i).getJSONObject("function")
            noms[f.getString("name")] = f.optString("description").take(130)
        }
        val conv = historique.takeLast(6).joinToString("\n") { (if (it.fromUser) "Utilisateur : " else "JARVIS : ") + it.text.take(400) }
        val user = "Date : ${Prompts.now()}\n\nOUTILS :\n" + noms.entries.joinToString("\n") { "- ${it.key} : ${it.value}" } + "\n\nCONVERSATION :\n$conv"
        val rep = withTimeoutOrNull(9_000) {
            try { app.llm.agent(SYSTEM, user) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        } ?: return null
        val json = rep.substringAfter('{', "").let { if (it.isEmpty()) return null else "{$it" }.substringBeforeLast('}').let { "$it}" }
        return try {
            val o = org.json.JSONObject(json)
            val arr = o.optJSONArray("outils") ?: JSONArray()
            val outils = (0 until arr.length()).map { arr.getString(it) }.filter { it in noms }.distinct()
            Plan(outils, o.optString("plan"))
        } catch (e: Exception) { null }
    }
}
