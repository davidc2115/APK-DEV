package fr.dd.jarvis.ai

import fr.dd.jarvis.societe.core.normaliser

/**
 * À la voix (téléphone ou Android Auto), une commande simple que tu viens de dicter s'exécute sans « oui ou non » :
 * ouvrir une appli, appeler quelqu'un, couper le Wi-Fi, ouvrir les volets, laisser JARVIS terminer dans une appli…
 * La question reste posée pour ce qui est irréversible ou qui part chez quelqu'un d'autre : suppression, facture,
 * envoi de SMS / mail (le texte dicté peut être mal compris), administration du serveur, sécurité.
 */
object ConfirmationVocale {
    /** Ce qui se confirme toujours, même à la voix. */
    private val IRREVERSIBLE = Regex(
        "\\b(supprim\\w*|definitiv\\w*|effac\\w*|factur\\w*|payee|annuler|remplacer le contenu|reformater|vider|corbeilles?|archive|" +
            "restaurer|remettre|sms|mail|message|reponse|cle api|termux|shell|script|github|imprimer|redemarrer|mettre a jour|" +
            "configuration|reglage|desactiv\\w*|systeme|empreinte|ssh|transformer|valider|relier|serveur|discord|role|salon|" +
            "bannir|expulser|purger|unlock|deverrouill\\w*|alarm disarm|alarme de la maison|portail|garage)\\b")

    /** Demandes « forcées » qui restent pourtant des commandes simples quand on les dicte : un appel, un réglage réseau. */
    private fun simple(question: String): Boolean {
        val t = question.trim()
        if (t.contains('\n')) return false
        return Regex("^Appeler .{1,90}\\?$").matches(t) || normaliser(t).startsWith("j active les appels wi fi")
    }

    /**
     * Faut-il demander « oui ou non » à la voix pour cette [question] ? [force] : l'outil l'exige même quand les
     * confirmations sont désactivées dans les réglages.
     */
    fun necessaire(question: String, force: Boolean): Boolean {
        if (simple(question)) return false
        if (force) return true
        return IRREVERSIBLE.containsMatchIn(normaliser(question))
    }
}
