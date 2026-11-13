package fr.dd.jarvis.hermes

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.ChatMsg
import org.json.JSONObject
import java.io.File

/**
 * Demande interrompue (délai dépassé, connexion au moteur coupée, erreur du fournisseur) : gardée pour qu'un
 * « recommence », « reprends là où tu en étais » la relance telle quelle — au lieu de répondre qu'aucun suivi n'existe.
 */
object Reprise {
    data class Demande(val texte: String, val raison: String, val le: Long)

    private const val VALIDITE_MS = 6 * 3_600_000L
    private fun fichier(app: JarvisApp) = File(app.filesDir, "reprise.json")

    /** La réponse du moteur montre que la demande n'a pas abouti (et peut être relancée). */
    fun echec(r: String?): Boolean = r == null || r.startsWith("⚠️ Connexion au moteur Hermès interrompue") ||
        r.contains("La demande n'a pas été déclarée terminée") || r.startsWith("⚠️ Hermès n'a renvoyé aucun résultat") ||
        r.startsWith("⚠️ Je n'ai pas réussi à terminer")

    @Synchronized fun noter(app: JarvisApp, texte: String, raison: String) {
        val t = texte.substringBefore("\n\n[REPRISE —").trim()
        if (t.isBlank() || estReprise(t)) return
        runCatching { fichier(app).writeText(JSONObject().put("texte", t.take(8000)).put("raison", raison.take(300)).put("le", System.currentTimeMillis()).toString()) }
    }

    @Synchronized fun effacer(app: JarvisApp) { fichier(app).delete() }

    @Synchronized fun derniere(app: JarvisApp): Demande? = runCatching {
        val o = JSONObject(fichier(app).readText())
        Demande(o.getString("texte"), o.optString("raison"), o.optLong("le")).takeIf { System.currentTimeMillis() - it.le < VALIDITE_MS }
    }.getOrNull()

    private val VERBE = Regex(
        "^(?:(?:ok|bon|alors|vas y|allez|jarvis|est ce que|tu peux|peux tu|pourrais tu|tu pourrais|s il te plait|stp|du coup)\\s+)*" +
            "(recommence|recommencer|reprends|reprendre|reprend|reessaie|reessayer|reessaye|relance|relancer|refais|refaire|continue|continuer|termine|terminer|poursuis|poursuivre|finis|finir)\\b"
    )

    /** Mots permis autour du verbe : rien d'autre (« reprends la musique », « continue l'histoire » ne sont pas des reprises). */
    private val PERMIS = setOf("la", "ou", "tu", "en", "etais", "etait", "ma", "ta", "la", "demande", "ce", "que", "faisais", "depuis", "le", "debut",
        "stp", "s", "il", "te", "plait", "ca", "tout", "a", "zero", "et", "l", "precedente", "derniere", "dernier", "s", "est", "arrete", "arretee",
        "interrompue", "interrompu", "travail", "tache", "creation", "du", "site", "web", "de", "ton", "mon", "c", "etais", "t", "les", "ce", "qui",
        "ok", "bon", "alors", "vas", "y", "allez", "jarvis", "est", "pourrais", "peux", "pourrais", "du", "coup", "s", "il", "vous", "plait", "maintenant")
    private val SEULS_AVEC_CONTEXTE = setOf("continue", "continuer", "termine", "terminer", "poursuis", "poursuivre", "finis", "finir", "refais", "refaire")

    /** « Recommence », « reprends là où tu en étais », « tu peux relancer la demande ? » (phrase courte, sans autre sujet). */
    fun estReprise(texte: String): Boolean {
        val n = fr.dd.jarvis.societe.core.normaliser(texte)
        val m = VERBE.find(n) ?: return false
        val mots = n.split(' ')
        if (mots.size > 16) return false
        val autres = mots.filter { it !in PERMIS && it != m.groupValues[1] && !VERBE.matches(it) && it !in SEULS_AVEC_CONTEXTE &&
            it !in setOf("recommence", "recommencer", "reprends", "reprendre", "reprend", "reessaie", "reessayer", "reessaye", "relance", "relancer") }
        if (autres.isNotEmpty()) return false
        // « continue », « termine »… seuls sont ambigus : il faut parler de la demande (« là où tu en étais », « la demande »).
        if (m.groupValues[1] in SEULS_AVEC_CONTEXTE) return Regex("ou tu en etai|la demande|ma demande|ta demande|ce que tu faisais|ou ca s est arrete|la creation").containsMatchIn(n)
        return true
    }

    /**
     * Si le dernier message demande de reprendre une demande interrompue : historique où ce message est remplacé par la
     * demande d'origine (avec une consigne de reprise) ; sinon null.
     */
    fun reecrire(app: JarvisApp, history: List<ChatMsg>): List<ChatMsg>? {
        val i = history.indexOfLast { it.fromUser }
        if (i < 0 || !estReprise(history[i].text)) return null
        val d = derniere(app) ?: return null
        val heure = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date(d.le))
        val texte = d.texte + "\n\n[REPRISE — cette demande a été interrompue à $heure (${d.raison.ifBlank { "délai dépassé" }}). " +
            "Reprends-la et termine-la : vérifie d'abord ce qui a déjà été fait (fichiers, sites, tâches, résultats d'outils) pour ne pas le refaire, puis continue là où ça s'est arrêté.]"
        return history.toMutableList().also { it[i] = it[i].copy(text = texte) }
    }
}
