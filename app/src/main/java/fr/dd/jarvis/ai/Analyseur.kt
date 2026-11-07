package fr.dd.jarvis.ai

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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

    /**
     * L'analyse préalable coûte un appel complet. Elle n'est utile que pour les demandes réellement orchestrées ;
     * les questions, commandes directes et actions simples ne doivent pas payer un second appel IA.
     */
    private val DEMANDE_COMPLEXE = Regex(
        "\\b(analyse|analyser|compare|comparatif|planifie|planifier|organise|synchronise|synchroniser|" +
            "plusieurs|toutes les|chaque|ensemble|workflow|plusieurs etapes|en plusieurs etapes|" +
            "d'abord.{0,50}(puis|ensuite)|puis.{0,50}(ensuite|apres)|recherche approfondie|audit|diagnostic complet|" +
            "mets? a jour.{0,80}(a partir|selon|depuis)|a partir de.{0,80}(tous|plusieurs))\\b",
        RegexOption.IGNORE_CASE
    )

    suspend fun analyser(app: fr.dd.jarvis.JarvisApp, historique: List<ChatMsg>, tousOutils: JSONArray): Plan? {
        if (!app.store.analyseGemini.value) return null
        val dernier = historique.lastOrNull { it.fromUser }?.text.orEmpty()
        if (!DEMANDE_COMPLEXE.containsMatchIn(fr.dd.jarvis.societe.core.normaliser(dernier))) return null
        if (tousOutils.length() < 4) return null
        val noms = mutableMapOf<String, String>()
        for (i in 0 until tousOutils.length()) {
            val f = tousOutils.getJSONObject(i).getJSONObject("function")
            noms[f.getString("name")] = f.optString("description").take(130)
        }
        val conv = historique.takeLast(6).joinToString("\n") { (if (it.fromUser) "Utilisateur : " else "JARVIS : ") + it.text.take(400) }
        val user = "Date : ${Prompts.now()}\n\nOUTILS :\n" + noms.entries.joinToString("\n") { "- ${it.key} : ${it.value}" } + "\n\nCONVERSATION :\n$conv"
        // Agent local gratuit (Ollama) en premier si déjà configuré : aucun jeton Groq/Gemini consommé.
        // En cas d'absence/échec, on revient au fournisseur configuré par l'utilisateur.
        val local = if (fr.dd.jarvis.ai.Ollama.configure(app)) withTimeoutOrNull(7_000) {
            try { withContext(Dispatchers.IO) { Ollama.generer(app, SYSTEM, user) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        } else null
        val rep = local?.takeIf { it.isNotBlank() } ?: withTimeoutOrNull(6_000) {
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
