package fr.dd.jarvis.ai

import org.json.JSONArray

/** Résumé déterministe des états Home Assistant, indépendant du réseau et facile à tester. */
internal object HomeAssistantDiagnosticResume {
    fun formater(states: JSONArray, version: String): String {
        var indisponibles = 0
        var inconnus = 0
        val exemplesIndisponibles = mutableListOf<String>()
        val exemplesInconnus = mutableListOf<String>()
        val domaines = linkedMapOf<String, Int>()
        for (i in 0 until states.length()) {
            val entity = states.optJSONObject(i) ?: continue
            val state = entity.optString("state")
            if (state != "unavailable" && state != "unknown") continue
            val id = entity.optString("entity_id").ifBlank { "entité inconnue" }
            val nom = entity.optJSONObject("attributes")?.optString("friendly_name").orEmpty()
            val detail = if (nom.isBlank() || nom == id) id else id + " — " + nom
            if (state == "unavailable") {
                indisponibles++
                if (exemplesIndisponibles.size < 12) exemplesIndisponibles += detail
            } else {
                inconnus++
                if (exemplesInconnus.size < 12) exemplesInconnus += detail
            }
            val domaine = id.substringBefore('.', "autre")
            domaines[domaine] = (domaines[domaine] ?: 0) + 1
        }
        val versionAffichee = version.ifBlank { "non fournie" }
        val resume = "API Home Assistant : OK · version " + versionAffichee + " · " + states.length() +
            " entités · " + indisponibles + " indisponible(s) · " + inconnus + " état(s) inconnu(s)."
        val details = buildList {
            if (exemplesIndisponibles.isNotEmpty()) {
                add("Entités indisponibles :")
                exemplesIndisponibles.forEach { add("- " + it) }
            }
            if (exemplesInconnus.isNotEmpty()) {
                add("Entités à l'état inconnu :")
                exemplesInconnus.forEach { add("- " + it) }
            }
            if (domaines.isNotEmpty()) add("Domaines concernés : " + domaines.entries.sortedByDescending { it.value }.take(8).joinToString(", ") { it.key + " (" + it.value + ")" })
            if (indisponibles + inconnus > 24) add("Liste limitée aux 12 premiers éléments par état.")
        }
        return resume + if (details.isEmpty()) "" else "\n" + details.joinToString("\n")
    }
}
