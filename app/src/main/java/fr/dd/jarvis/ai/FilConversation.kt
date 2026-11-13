package fr.dd.jarvis.ai

/**
 * Fil de la conversation. Une demande qui prolonge l'échange (« très bien, du coup regarde directement sur la Freebox »,
 * « vas-y », « corrige ça », « et pour Plex ? ») ne doit jamais être traitée comme une demande isolée :
 *  - les réflexes sans IA qui ne lisent que la dernière phrase la laissent passer ;
 *  - Jarvis comme Hermès la reçoivent complétée par le sujet en cours (demande précédente et dernières réponses,
 *    résultat 🔔 d'une tâche de fond compris), écrit DANS la demande : c'est le seul message que le relais IA ne
 *    raccourcit jamais, même quand il doit réduire l'historique pour tenir dans le quota d'un modèle.
 */
object FilConversation {
    private fun n(s: String) = fr.dd.jarvis.societe.core.normaliser(s)

    /** Mots de liaison en tête : « très bien alors du coup… », « ok donc… », « et… ». */
    private val LIAISON = Regex(
        "^(?:(?:tres bien|bien|ok|okay|d accord|dac|parfait|super|genial|top|nickel|impeccable|oui|ouais|bon|alors|du coup|donc|et|ben|bah|" +
            "eh bien|merci|ah|ah bon|tres bien merci|c est note|ca marche)\\s+)+"
    )

    /** Renvoi à ce qui vient d'être dit : « ça », « directement », « du coup », « vas-y », « corrige-le »… */
    private val RENVOI = Regex(
        "\\b(du coup|dans ce cas|en fait|justement|directement|aussi|egalement|plutot|ca|cela|ceci|celle ci|celui ci|celle la|celui la|" +
            "ce probleme|ce souci|cette erreur|ce point|cette piste|cette solution|la dessus|dessus|de ce cote|de ton cote|la meme chose|pareil|" +
            "le reste|la suite|continue|poursuis|vas y|fais le|fais la|fais les|corrige|repare|regle le|regle la|regle les|le corriger|le regler|" +
            "le reparer|le faire|les faire|applique|l appliquer|les appliquer|ta proposition|ta solution|tes solutions|ce que tu (as dit|proposes|disais|as trouve)|" +
            "comme tu (as dit|dis|disais|proposes)|le premier|la premiere|le deuxieme|la deuxieme|le second|la seconde|le dernier|la derniere)\\b"
    )

    /** La réponse précédente attend une réponse ou propose une suite. */
    private val ATTENTE = Regex("veux tu|souhaites tu|voulez vous|je peux (aussi|ensuite|maintenant)|dois je|on (peut|pourrait)|tu (veux|preferes)")

    /** Dernière demande de l'utilisateur, sa demande précédente et les réponses de l'assistant entre les deux. */
    data class Fil(val demande: String, val demandePrecedente: String?, val reponses: List<String>)

    fun fil(history: List<ChatMsg>): Fil? {
        val iUser = history.indexOfLast { it.fromUser }
        if (iUser <= 0) return null
        val avant = history.subList(0, iUser)
        val iPrec = avant.indexOfLast { it.fromUser }
        val reponses = avant.subList(iPrec + 1, avant.size).filter { !it.fromUser && it.text.isNotBlank() }.map { it.text.trim() }
        if (reponses.isEmpty()) return null
        return Fil(history[iUser].text.trim(), avant.getOrNull(iPrec)?.text?.trim(), reponses)
    }

    /** La demande renvoie explicitement à l'échange précédent (« ça », « du coup », « directement »…). */
    fun renvoie(texte: String): Boolean = RENVOI.containsMatchIn(n(texte))

    /** La demande est la suite de l'échange précédent (et il y a vraiment quelque chose à reprendre). */
    fun estSuite(f: Fil): Boolean {
        val t = n(f.demande)
        val mots = t.split(' ').filter { it.isNotBlank() }
        if (mots.isEmpty() || mots.size > 40) return false
        val precedent = f.reponses.last()
        if (precedent.length < 40) return false // « D'accord. », « ✅ Fait. » : rien à reprendre
        val attend = precedent.trimEnd().endsWith("?") || ATTENTE.containsMatchIn(n(precedent.takeLast(300)))
        return LIAISON.containsMatchIn(t) || RENVOI.containsMatchIn(t) || (mots.size <= 8 && attend)
    }

    private fun extrait(s: String, max: Int): String {
        val t = s.trim().removePrefix("🪽").trim()
        return if (t.length <= max) t else t.take(max * 3 / 4).trimEnd() + " […] " + t.takeLast(max / 4).trimStart()
    }

    /** Note de contexte ajoutée à la demande (vide si la demande n'est pas une suite). */
    fun note(history: List<ChatMsg>): String {
        val f = fil(history)?.takeIf { estSuite(it) } ?: return ""
        val reps = f.reponses.takeLast(2)
        val parReponse = 1800 / reps.size
        return "\n\n[Fil de la conversation — " +
            (f.demandePrecedente?.takeIf { it.isNotBlank() }?.let { "ma demande précédente : « ${extrait(it, 350)} » ; " } ?: "") +
            "ta dernière réponse : « ${reps.joinToString(" » puis « ") { extrait(it, parReponse) }} ». " +
            "Ce message en est la suite : comprends ce qu'il désigne (« ça », « directement », « du coup », « là-dessus »…) d'après ce contexte " +
            "et agis en conséquence, sans me redemander de quoi il s'agit. S'il change clairement de sujet, traite-le tel quel.]"
    }

    /** Demande complétée par le fil de la conversation (ou telle quelle si elle ne prolonge rien). */
    fun enrichir(history: List<ChatMsg>): String {
        val demande = history.lastOrNull { it.fromUser }?.text.orEmpty()
        return demande + note(history)
    }

    /** Sujet en cours (pour choisir les outils et les consignes utiles) : vide si la demande ne prolonge rien. */
    fun sujet(history: List<ChatMsg>): String {
        val f = fil(history)?.takeIf { estSuite(it) } ?: return ""
        return ((f.demandePrecedente ?: "") + " " + f.reponses.takeLast(2).joinToString(" ") { it.take(1500) }).trim()
    }

    /** Historique dont la dernière demande porte le fil de la conversation (pour les modèles qui ne verraient qu'elle). */
    fun historiqueEnrichi(history: List<ChatMsg>): List<ChatMsg> {
        val note = note(history)
        if (note.isEmpty()) return history
        val i = history.indexOfLast { it.fromUser }
        return history.toMutableList().also { it[i] = it[i].copy(text = it[i].text + note) }
    }
}
