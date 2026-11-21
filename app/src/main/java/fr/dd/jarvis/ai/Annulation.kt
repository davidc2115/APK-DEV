package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONObject

/**
 * « Annule », « annule ça », « non, annule ce que tu viens de faire » : la DERNIÈRE création de JARVIS (réveil, rappel,
 * note, rendez-vous, articles ajoutés à une liste) est défaite, dans les 15 minutes qui suivent — sans IA. Une action
 * qui ne se défait pas (SMS envoyé, appel…) n'est jamais enregistrée ici.
 */
object Annulation {
    data class Action(val outil: String, val args: JSONObject, val resultat: String, val ts: Long = System.currentTimeMillis())

    private const val FENETRE_MS = 15 * 60_000L
    private val REVERSIBLES = setOf("alarme_creer", "rappel_creer", "note_prendre", "create_event")

    @Volatile var derniere: Action? = null
        private set

    /** Appelé après chaque outil exécuté : garde la dernière création réussie qui se défait. */
    fun noter(outil: String, args: JSONObject, resultat: String) {
        val nom = outil.removePrefix("mcp_jarvis_")
        if (nom !in REVERSIBLES || !reussi(nom, resultat)) return
        derniere = Action(nom, JSONObject(args.toString()), resultat)
    }

    fun noterRappelLieu(id: Int, texte: String) {
        derniere = Action("rappel_lieu", JSONObject().put("id", id).put("texte", texte), "")
    }

    fun noterRdvTermine(cles: List<String>) {
        derniere = Action("rdv_termine", JSONObject().put("cles", org.json.JSONArray(cles)), "")
    }

    fun noterListe(liste: String, elements: List<String>) {
        derniere = Action("liste_ajouter", JSONObject().put("liste", liste).put("elements", org.json.JSONArray(elements)), "")
    }

    internal fun reussi(outil: String, r: String): Boolean {
        val t = r.trim()
        if (t.startsWith("❌") || t.startsWith("⚠️") || Regex("^(échec|impossible|aucun|permission|date de début invalide|durée|heure invalide)", RegexOption.IGNORE_CASE).containsMatchIn(t)) return false
        return when (outil) {
            "create_event" -> Regex("\\(#\\d+\\)").containsMatchIn(t)
            "rappel_creer" -> t.startsWith("⏰")
            else -> true
        }
    }

    private val DEMANDE = Regex("^(?:(?:non|oups|zut|mince|attends|ah non|non non|jarvis|stop)\\s+)*" +
        "(?:annule|annuler|annules|defais|defaire|reviens en arriere|retour en arriere|enleve ca|supprime ca|efface ca)" +
        "(?:\\s+(?:ca|cela|tout ca|ce que tu viens de faire|ce que tu as fait|ta derniere action|la derniere action|la derniere chose|le dernier|la derniere|" +
        "le dernier ajout|ce dernier ajout|ce que je viens de (?:dire|demander)|stp|s il te plait|jarvis|vite))*\\s*$")

    /** « Annule (ça) » sans autre précision (« annule le rendez-vous de demain » va ailleurs). */
    fun demande(texte: String): Boolean = DEMANDE.matches(normaliser(texte).trim())

    /** Ce qui sera défait, en clair. */
    internal fun libelle(a: Action): String = when (a.outil) {
        "alarme_creer" -> "le réveil de ${a.args.optString("heure")}"
        "rappel_creer" -> "le rappel « ${a.args.optString("texte").take(60)} »"
        "note_prendre" -> "la note « ${a.args.optString("texte").take(60)} »"
        "create_event" -> "le rendez-vous « ${a.args.optString("title").take(60)} »"
        "liste_ajouter" -> "l'ajout à la liste ${a.args.optString("liste")}"
        "rappel_lieu" -> "le rappel à l'arrivée « ${a.args.optString("texte").take(60)} »"
        "rdv_termine" -> "le rendez-vous marqué terminé"
        else -> "la dernière action"
    }

    /** Outil inverse et ses arguments, ou null. */
    internal fun inverse(a: Action): Pair<String, JSONObject>? = when (a.outil) {
        "alarme_creer" -> "alarme_supprimer" to JSONObject().put("heure", a.args.optString("heure")).put("libelle", a.args.optString("libelle"))
        "rappel_creer" -> {
            val texte = Regex("« (.+?) »").find(a.resultat)?.groupValues?.get(1) ?: a.args.optString("texte")
            "rappel_supprimer" to JSONObject().put("texte", texte)
        }
        "note_prendre" -> "note_supprimer" to JSONObject().put("sujet", "derniere")
        "create_event" -> Regex("\\(#(\\d+)\\)").find(a.resultat)?.groupValues?.get(1)?.let { "delete_event" to JSONObject().put("event_id", it) }
        else -> null
    }

    /** Défait la dernière action ; null s'il n'y a rien de récent à annuler (la phrase suit alors son cours). */
    suspend fun annuler(app: JarvisApp, outils: ToolBox, maintenant: Long = System.currentTimeMillis()): String? {
        val a = derniere?.takeIf { maintenant - it.ts <= FENETRE_MS } ?: return null
        derniere = null
        if (a.outil == "liste_ajouter") {
            val el = a.args.optJSONArray("elements")?.let { j -> (0 until j.length()).map { j.optString(it) } }.orEmpty()
            val n = fr.dd.jarvis.device.Listes.retirerAjout(app, a.args.optString("liste"), el)
            return if (n > 0) "↩️ Annulé : ${el.joinToString(", ") { it.lowercase() }} retiré${if (n > 1) "s" else ""} de ta liste ${a.args.optString("liste")}."
                else "↩️ Ces articles n'étaient plus sur ta liste ${a.args.optString("liste")}."
        }
        if (a.outil == "rdv_termine") {
            val cles = a.args.optJSONArray("cles")?.let { j -> (0 until j.length()).map { j.optString(it) } }.orEmpty()
            val n = fr.dd.jarvis.device.RdvTermines.annuler(app, cles)
            return if (n > 0) "↩️ Annulé : ${if (n > 1) "$n rendez-vous ne sont plus marqués terminés" else "le rendez-vous n'est plus marqué terminé"} (couleur d'avant rétablie)." else "Ce rendez-vous n'était plus marqué terminé."
        }
        if (a.outil == "rappel_lieu") return if (fr.dd.jarvis.device.RappelsLieu.supprimer(app, a.args.optInt("id"))) "↩️ Annulé : ${libelle(a)}." else "Ce rappel n'existe plus."
        val (outil, args) = inverse(a) ?: return "Je ne sais pas défaire ${libelle(a)} automatiquement."
        val r = outils.execute(outil, args).text
        return "↩️ Annulé : ${libelle(a)}.\n$r"
    }
}
