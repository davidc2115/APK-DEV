package fr.dd.jarvis.ai

import fr.dd.jarvis.device.ReglesMail
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONObject

/**
 * Commandes mails reconnues sans IA : « trie mes mails » (règle enregistrée : non lus par défaut), « trie tous mes
 * mails » (cette fois seulement), « désormais trie aussi les lus » (règle), « marque tous mes mails comme lus »,
 * « marque les pubs comme traitées », « marque les mails d'Amazon comme lus ».
 */
object MailCommandes {
    private val MAILS = "(?:e ?)?mails?|courriels?|messages? (?:mail|gmail)|boite (?:mail|de reception)"
    private val R_MARQUER = Regex("\\b(marque[rsz]?|passe[rsz]?|mets?|mettre)\\b.{0,80}\\b($MAILS)\\b.{0,60}\\b(comme |en )?(lus?|lues?|non lus?|non lues?|traites?|traitees?)\\b|\\b($MAILS)\\b.{0,40}\\b(marques?|marquees?) (comme )?(lus?|lues?|traites?|traitees?)\\b")
    private val R_TRIER = Regex("^(trie[rsz]?|tri|range[rsz]?|classe[rsz]?|organise[rsz]?|fais le tri (de|dans))\\b(?: moi)?\\s+(?:(?:aussi|encore|a nouveau|de nouveau|maintenant|vite|rapidement|stp|s il te plait) )*(?:(tous|toutes|tout)\\s+)?(?:mes |les |ma |la )?($MAILS)\\b")
    private val R_REGLE = Regex("\\b(desormais|dorenavant|a partir de maintenant|a l avenir|par defaut|change[rsz]? la regle|regle du tri|regle de tri|modifie[rsz]? la regle)\\b")
    private val R_REGLE_VOIR = Regex("\\b(quelle est|c est quoi|montre|rappelle)\\b.{0,20}\\bregle\\b.{0,20}\\btri")

    private val R_TOUS = Regex("\\b(tous|toutes|l ensemble|toute la boite)\\b|\\btout\\b(?! de suite)")
    private val R_NON_LUS = Regex("\\b(non|pas|pas encore) (lus?|lues?)\\b|\\bnouveaux\\b")
    private val R_LUS = Regex("(?<!non )(?<!pas )(?<!encore )\\b(lus?|lues?)\\b")

    private fun statutDans(n: String): String? {
        val nonLus = R_NON_LUS.containsMatchIn(n); val lus = R_LUS.containsMatchIn(n)
        return when {
            nonLus && lus -> "tous"
            R_TOUS.containsMatchIn(n) && !Regex("\\bseulement les non lus\\b").containsMatchIn(n) -> "tous"
            nonLus -> "non_lus"
            lus -> "lus"
            else -> null
        }
    }

    /** (outil, arguments) ou null. */
    fun intention(texte: String): Pair<String, JSONObject>? {
        val n = normaliser(texte).replace(Regex("[?!.,;:]+"), " ").replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^(jarvis |ok |alors |est ce que tu peux |tu peux |peux tu |pourrais tu |s il te plait |stp )+"), "")
        if (n.isBlank() || n.split(' ').size > 25) return null
        if (R_REGLE_VOIR.containsMatchIn(n)) return "regler_tri_mails" to JSONObject().put("action", "voir")
        // Règle durable : « désormais trie aussi les mails lus », « change la règle du tri : seulement les non lus ».
        if (R_REGLE.containsMatchIn(n) && Regex("\\btri(e[rsz]?)?\\b").containsMatchIn(n))
            statutDans(n)?.let { st -> return "regler_tri_mails" to JSONObject().put("action", "definir").put("statut", st) }
        R_MARQUER.find(n)?.let {
            if (Regex("\\b(ce mail|cet e ?mail|celui|celui ci|ceux ci|ces mails|ce message)\\b").containsMatchIn(n)) return null   // lequel ? → l'IA, qui a le fil
            val action = when {
                Regex("\\bnon (lus?|lues?)\\b").containsMatchIn(n) -> "non_lu"
                Regex("\\btraite").containsMatchIn(n) -> "traite"
                else -> "lu"
            }
            val a = JSONObject().put("action", action)
            Regex("\\b(?:mails?|e mails?|courriels?|messages?) (?:de|d|venant de|envoyes? par|recus? de) ([a-z0-9@._ -]{2,40}?)(?= comme| en (?:lu|non|trait)| \\d|$)").find(n)
                ?.groupValues?.get(1)?.trim()?.removePrefix("la ")?.removePrefix("le ")?.takeIf { it !in setOf("la boite", "ma boite", "boite") }?.let { a.put("expediteur", it) }
            ReglesMail.NOMS.sortedByDescending { it.length }.firstOrNull { nom -> Regex("\\b${Regex.escape(normaliser(nom))}\\b").containsMatchIn(n) }?.let { a.put("libelle", it) }
            Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+").find(texte)?.let { m -> if (!a.has("expediteur")) a.put("compte", m.value) }
            if (Regex("\\b(archive|archiver|archives|sors les de la boite|retire les de la boite)\\b").containsMatchIn(n)) a.put("archiver", "true")
            if (!a.has("expediteur") && !a.has("libelle")) a.put("tous", "true")
            return "marquer_mails_traites" to a
        }
        R_TRIER.find(n)?.let { m ->
            val a = JSONObject().put("ranger", "true")
            (statutDans(n) ?: if (m.groupValues[2].isNotBlank()) "tous" else null)?.let { a.put("statut", it) }
            if (a.optString("statut") == "tous") a.put("count", "500")
            if (Regex("\\b(toute la boite|archives?|partout|tout le compte)\\b").containsMatchIn(n)) a.put("toute_la_boite", "true")
            Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+").find(texte)?.let { a.put("compte", it.value) }
            return "trier_mails" to a
        }
        return null
    }

    /** Requête Gmail du marquage : boîte de réception (ou partout), état, expéditeur, mots, libellé, période. */
    fun requeteGmail(action: String, a: JSONObject, prefixe: String): String {
        val l = mutableListOf<String>()
        if (!a.optString("partout").equals("true", true)) l += "in:inbox"
        l += when (action) { "non_lu" -> "is:read"; "lu" -> "is:unread"; else -> "-label:" + gmailLabel(prefixe + "Traité") }
        a.optString("expediteur").trim().takeIf { it.isNotBlank() }?.let { l += "from:(${it.replace(Regex("[()\"]"), "")})" }
        a.optString("recherche").trim().takeIf { it.isNotBlank() && it.lowercase() !in setOf("tous", "tout", "toutes") }?.let { r ->
            l += if (Regex("\\b(from|subject|to|older_than|newer_than|label|has|is|in|after|before):").containsMatchIn(r)) r
            else "{from:(${r.replace(Regex("[(){}\"]"), "")}) subject:(${r.replace(Regex("[(){}\"]"), "")})}"
        }
        a.optString("libelle").trim().takeIf { it.isNotBlank() }?.let { lib ->
            val nom = ReglesMail.NOMS.firstOrNull { normaliser(it) == normaliser(lib) } ?: lib
            l += "label:" + gmailLabel(prefixe + nom)
        }
        a.optString("depuis").trim().takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(it) }?.let { l += "after:" + it.take(10).replace('-', '/') }
        a.optString("avant").trim().takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(it) }?.let { l += "before:" + it.take(10).replace('-', '/') }
        return l.joinToString(" ")
    }

    /** Nom de libellé tel que la recherche Gmail l'attend (espaces et « / » → « - »). */
    fun gmailLabel(nom: String) = nom.trim().lowercase().replace(Regex("[\\s/]+"), "-")
}
