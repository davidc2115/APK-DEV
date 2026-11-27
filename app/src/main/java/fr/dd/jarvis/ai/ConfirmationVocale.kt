package fr.dd.jarvis.ai

import fr.dd.jarvis.societe.core.normaliser

/**
 * À la voix (téléphone ou Android Auto), une commande simple que tu viens de dicter s'exécute sans « oui ou non » :
 * ouvrir une appli, appeler quelqu'un, couper le Wi-Fi, ouvrir les volets, lancer un film, laisser JARVIS terminer dans
 * une appli, régler quelque chose sur le téléphone…
 * La question reste posée pour ce qui est irréversible ou qui part chez quelqu'un d'autre : suppression, facture,
 * paiement, envoi de SMS / mail / message (le texte dicté peut être mal compris), administration du serveur, de la box
 * ou de Home Assistant, sécurité.
 */
object ConfirmationVocale {
    /** Ce qui se confirme toujours, même à la voix. */
    private val IRREVERSIBLE = Regex(
        "\\b(supprim\\w*|definitiv\\w*|effac\\w*|factur\\w*|payer|paye|payee|paiement|acheter|achat|commander|annuler|remplacer le contenu|" +
            "reformater|vider|corbeilles?|archiv\\w*|restaurer|remettre|sms|e ?mails?|messages?|reponses?|envoy\\w*|envoi|publi\\w*|partag\\w*|" +
            "cle api|termux|shell|script|github|imprim\\w*|redemarr\\w*|mettre a jour|valider|relier|serveur|freebox|internet|acces a distance|" +
            "empreinte|ssh|transformer|discord|roles?|salons?|bannir|expulser|purger|unlock|deverrouill\\w*|securite|alarm\\w*|portail|garage)\\b")

    /** Administration (Home Assistant, Plex, box, serveur, fichiers) : toujours confirmée, quels que soient les mots. */
    private val ADMINISTRATION = Regex("^(Home Assistant :|Plex :|Freebox|Box :|GitHub|Exécuter sur |Exécuter cette commande|Exécuter ce script|Modifier \\S*/)")

    /**
     * Demandes « forcées » qui restent pourtant des commandes simples quand on les dicte : un appel, un réglage réseau,
     * lancer un film, appuyer sur un bouton anodin de l'écran.
     */
    private fun simple(question: String): Boolean {
        val t = question.trim()
        if (t.contains('\n')) return false
        if (Regex("^Appeler .{1,90}\\?$").matches(t) || normaliser(t).startsWith("j active les appels wi fi")) return true
        // Plex : lancer la lecture d'un film / d'un épisode sur un lecteur (le nom du lecteur, « Salon », n'est pas un salon Discord).
        if (Regex("^Lancer « .{1,160} » sur « .{1,80} » via Plex").containsMatchIn(t)) return true
        // Étape anodine du pilotage de l'écran (« Appuyer sur Lecture ? », « Ouvrir Spotify ? ») : sans mot sensible.
        return Regex("^(Lancer|Lire|Jouer|Reprendre|Ouvrir|Appuyer sur|Toucher|Afficher|Monter|Baisser|Allumer|Éteindre|Mettre en pause|Passer|Continuer)\\b").containsMatchIn(t) &&
            !IRREVERSIBLE.containsMatchIn(normaliser(t))
    }

    /**
     * Faut-il demander « oui ou non » à la voix pour cette [question] ? [force] : l'outil l'exige même quand les
     * confirmations sont désactivées dans les réglages (une demande forcée inconnue reste confirmée).
     */
    fun necessaire(question: String, force: Boolean): Boolean {
        val t = question.trim()
        if (ADMINISTRATION.containsMatchIn(t)) return true
        if (simple(t)) return false
        if (force) return true
        return IRREVERSIBLE.containsMatchIn(normaliser(t))
    }
}
