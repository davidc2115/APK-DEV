package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp

/**
 * Corrections apprises dans la conversation, sans passer par « prends note » :
 *  - « quand je dis bag je parle du serveur B&G », « "les potes" c'est le serveur Les Copains » → surnom retenu ;
 *  - juste après « Aucun serveur « bag » … » : « non, c'est B&G » / « je parlais du salon discussion » → « bag » = B&G,
 *    puis la demande précédente est refaite avec le bon nom.
 * Les surnoms vont dans la mémoire fiable (Faits) : tous les outils les appliquent ensuite d'eux-mêmes.
 */
object Corrections {
    /** Ce que Jarvis n'a pas trouvé dans sa réponse précédente : (genre, nom demandé). */
    private val R_INTROUVABLE = listOf(
        Regex("Aucun (serveur|salon|r[ôo]le|membre|contact|appareil)\\s*(?:Discord\\s*)?«\\s*([^»]{1,60}?)\\s*»", RegexOption.IGNORE_CASE),
        Regex("Plusieurs (serveurs?|salons?|r[ôo]les?|membres?|contacts?|appareils?) correspondent [àa] «\\s*([^»]{1,60}?)\\s*»", RegexOption.IGNORE_CASE),
        Regex("(serveur|salon|contact|appareil)\\s*(?:Discord\\s*)?«\\s*([^»]{1,60}?)\\s*»\\s*(?:est\\s*)?introuvable", RegexOption.IGNORE_CASE),
        Regex("(Contact|Destinataire) introuvable[^:]*:\\s*([^.\\n]{1,60})", RegexOption.IGNORE_CASE),
    )
    /** « non, c'est B&G », « je parlais du serveur B&G », « je voulais dire le salon discussion ». */
    private val R_REPONSE = Regex("^(?:non|nan|mais non|pas du tout)?[ ,.!]*(?:je (?:parle|parlais) (?:du|de la|de l'|des|de)|c'est|c’est|cest|il s'agit (?:du|de la|de l'|de)|je voulais dire|je veux dire)\\s+" +
        "(?:le |la |l'|les )?(serveur|salon|canal|contact|r[ôo]le|membre|appareil)?\\s*(?:discord\\s*)?[«\"“]?\\s*(.{2,60}?)\\s*[»\"”]?\\s*[.!]*$", RegexOption.IGNORE_CASE)

    internal fun introuvable(precedent: String?): Pair<String, String>? {
        val p = precedent ?: return null
        for (r in R_INTROUVABLE) r.find(p)?.let { m -> return m.groupValues[1].lowercase().removeSuffix("s") to m.groupValues[2].trim() }
        return null
    }

    internal fun domaine(genre: String): String = when (fr.dd.jarvis.societe.core.normaliser(genre)) {
        "serveur" -> "discord_serveur"; "salon", "canal" -> "discord_salon"; "contact", "destinataire" -> "contact"; "appareil" -> "appareil"; else -> "autre"
    }

    /** Correction juste après un « introuvable » : (surnom, cible, domaine) ou null. */
    internal fun correction(texte: String, precedent: String?): Triple<String, String, String>? {
        val (genre, demande) = introuvable(precedent) ?: return null
        val m = R_REPONSE.find(texte.trim()) ?: return null
        val cible = m.groupValues[2].trim().trim('«', '»', '"', ' ')
        if (cible.isBlank() || fr.dd.jarvis.societe.core.normaliser(cible) == fr.dd.jarvis.societe.core.normaliser(demande)) return null
        return Triple(demande, cible, domaine(m.groupValues[1].ifBlank { genre }))
    }

    internal val GARDE = Regex("quand je dis|surnom|(c'est|c’est|=|d[ée]signe|correspond [àa])\\s+(le |la |l'|les )?(serveur|salon|canal|groupe|contact)\\b|" +
        "(mon|notre|ma) (adresse|domicile)\\s*(est|c'est|:)|\\bj'?\\s*habite\\b", RegexOption.IGNORE_CASE)

    /** Définition explicite seule dans le message (pas d'autre demande derrière). */
    internal fun definitionSeule(texte: String): Boolean {
        val t = texte.trim()
        return Regex("^(quand je dis|[«\"“][^»\"”]{2,40}[»\"”]\\s*,?\\s*(c'est|c’est|=|d[ée]signe)|(le )?surnom (du|de la|de l'|des|de))", RegexOption.IGNORE_CASE).containsMatchIn(t) &&
            !Regex("[,;]\\s*(puis|et|ensuite|alors|maintenant)\\b|\\b(puis|ensuite)\\b|\\?$", RegexOption.IGNORE_CASE).containsMatchIn(t)
    }

    /**
     * Réflexe du cerveau : [refaire] relance la demande précédente avec le nom corrigé. Null si le message n'est pas une
     * correction (une définition suivie d'une autre demande est retenue, puis la demande suit son cours normal).
     */
    suspend fun reflexe(app: JarvisApp, texte: String, precedent: String?, demandePrecedente: String?, refaire: suspend (String) -> String): String? {
        correction(texte, precedent)?.let { (surnom, cible, dom) ->
            val r = Faits.definirSurnom(app, surnom, cible, dom)
            if (r.isBlank()) return null
            val suite = demandePrecedente?.takeIf { it.isNotBlank() }?.let { runCatching { refaire(it) }.getOrNull() }
            return "🏷️ Compris, je retiens : « $surnom » = « $cible »." + (suite?.let { "\n\n$it" } ?: "")
        }
        // Seulement les vraies définitions (évite d'apprendre « "salut" c'est tout » comme un surnom).
        if (!GARDE.containsMatchIn(texte)) return null
        val appris = runCatching { Faits.extraireRapide(app, texte) }.getOrDefault(emptyList())
        if (appris.isNotEmpty() && definitionSeule(texte)) return "C'est noté, je m'en servirai partout (Discord, contacts, appareils…) :\n" + appris.joinToString("\n")
        return null
    }
}
