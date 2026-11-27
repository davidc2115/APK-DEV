package fr.dd.jarvis.ai

import org.json.JSONArray

/** Diagnostic lisible et déterministe des intégrations et entités Home Assistant. */
internal object HomeAssistantDiagnosticAvance {
    fun analyser(entrees: JSONArray, etats: JSONArray, registre: JSONArray, journal: String, config: String): String {
        val erreursIntegration = mutableListOf<String>()
        val integrationsParDomaine = linkedMapOf<String, Int>()
        for (i in 0 until entrees.length()) {
            val e = entrees.optJSONObject(i) ?: continue
            val domaine = e.optString("domain", "inconnu")
            integrationsParDomaine[domaine] = (integrationsParDomaine[domaine] ?: 0) + 1
            val etat = e.optString("state")
            if (etat.isNotBlank() && etat !in setOf("loaded", "not_loaded")) {
                erreursIntegration += "- ${e.optString("title", domaine)} ($domaine) : $etat · entry_id ${e.optString("entry_id")}"
            }
        }

        val plateformeParEntite = mutableMapOf<String, String>()
        for (i in 0 until registre.length()) {
            val e = registre.optJSONObject(i) ?: continue
            val id = e.optString("entity_id")
            if (id.isNotBlank()) plateformeParEntite[id] = e.optString("platform", "inconnue")
        }
        val indisponibles = linkedMapOf<String, MutableList<String>>()
        var inconnues = 0
        for (i in 0 until etats.length()) {
            val e = etats.optJSONObject(i) ?: continue
            val state = e.optString("state")
            if (state == "unknown") { inconnues++; continue }
            if (state != "unavailable") continue
            val id = e.optString("entity_id", "entité inconnue")
            val plateforme = plateformeParEntite[id] ?: id.substringBefore('.', "inconnue")
            val nom = e.optJSONObject("attributes")?.optString("friendly_name").orEmpty()
            val detail = if (nom.isBlank() || nom == id) id else "$id — $nom"
            indisponibles.getOrPut(plateforme) { mutableListOf() }.add(detail)
        }

        val lignesJournal = journal.lineSequence().map { it.trim() }
            .filter { it.isNotBlank() && Regex("(?i)error|exception|failed|setup failed|retry|timeout|timed out|auth|unauthorized|refused|unavailable|invalid config").containsMatchIn(it) }
            .toList().takeLast(18)

        return buildString {
            appendLine("# Diagnostic Home Assistant")
            appendLine("Intégrations : ${entrees.length()} entrée(s) · entités : ${etats.length()} · indisponibles : ${indisponibles.values.sumOf { it.size }} · inconnues : $inconnues.")
            if (config.isNotBlank()) appendLine("Validation YAML : $config")
            if (integrationsParDomaine.isNotEmpty()) appendLine("Domaines présents : " + integrationsParDomaine.entries.sortedByDescending { it.value }.take(15).joinToString { "${it.key} (${it.value})" })
            if (erreursIntegration.isNotEmpty()) {
                appendLine("\n## Intégrations à vérifier")
                erreursIntegration.take(30).forEach { appendLine(it) }
            } else appendLine("\nAucune intégration en état d'erreur explicite dans les entrées de configuration.")
            if (indisponibles.isNotEmpty()) {
                appendLine("\n## Entités indisponibles par intégration")
                indisponibles.entries.sortedByDescending { it.value.size }.take(15).forEach { (plateforme, ids) ->
                    appendLine("- $plateforme : ${ids.size} entité(s)")
                    ids.take(8).forEach { appendLine("  - $it") }
                    if (ids.size > 8) appendLine("  - … et ${ids.size - 8} autre(s)")
                }
                appendLine("Piste : vérifier d'abord l'intégration qui fournit ces entités, son appareil, son réseau et ses identifiants. Ne pas supprimer les entités indisponibles pour tenter de réparer la connexion.")
            }
            if (lignesJournal.isNotEmpty()) {
                appendLine("\n## Erreurs récentes du journal")
                lignesJournal.forEach { appendLine("- ${it.take(350)}") }
            } else appendLine("\nAucune ligne d'erreur reconnue dans l'extrait du journal récupéré.")
            appendLine("\n## Actions de dépannage possibles")
            appendLine("- Relancer uniquement l'intégration en erreur : integration_recharger (après confirmation).")
            appendLine("- Lire les détails : journal, puis vérifier l'état de l'intégration et des entités concernées.")
            appendLine("- Corriger les YAML : lire le fichier, modifier, valider la configuration, puis recharger uniquement le domaine concerné.")
            appendLine("- Garder le redémarrage complet en dernier recours, après validation de la configuration.")
        }
    }
}
