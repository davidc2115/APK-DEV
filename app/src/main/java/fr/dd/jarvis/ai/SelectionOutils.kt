package fr.dd.jarvis.ai

import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONArray
import org.json.JSONObject

/**
 * Liste d'outils envoyée au modèle à chaque tour : les ~113 outils généraux pesaient à eux seuls ~17 000 jetons par
 * appel (plus de la moitié de la requête). Les comptes Groq limités en jetons par minute faisaient alors compacter
 * toute la requête jusqu'à des descriptions d'outils de 90 caractères — choix d'outils moins fiables, et réponses plus
 * lentes partout.
 *
 * Ici : le NOYAU des outils du quotidien + les familles reconnues pour la demande (Home Assistant, Société…) + les
 * outils généraux les plus proches des mots de la demande, avec leurs descriptions complètes. Si le modèle a besoin
 * d'un autre outil, il l'ajoute lui-même avec [META] (recherche dans tout le catalogue), comme Hermès le fait déjà.
 */
internal object SelectionOutils {
    const val META = "outils_rechercher"
    /** En dessous, la liste est envoyée telle quelle. */
    const val SEUIL = 70
    private const val MAX_GENERAUX = 52

    /** Outils généraux du quotidien, toujours proposés. */
    val NOYAU = setOf(
        "get_planning", "create_event", "update_event", "delete_event", "agenda_afficher",
        "summarize_mails", "rechercher_mails", "mail_envoyer",
        "lire_sms", "rechercher_sms", "envoyer_sms", "repondre_sms", "appeler_contact", "search_contacts",
        "meteo", "itineraire", "naviguer", "heure_depart", "ma_position",
        "alarme_creer", "alarmes_liste", "minuteur_creer", "rappel_creer", "rappels_liste",
        "jouer_musique", "controle_media", "mode_sonore", "phone_action", "lire_notifications",
        "memoire_lire", "memoire_ajouter", "note_prendre", "notes_lire", "tache_ajouter", "taches_lister",
        "obsidian_chercher", "obsidian_lire", "stockage_telephone", "reglage_jarvis", "tache_arriere_plan", "hermes_deleguer",
    )

    private val VIDES = setOf("pour", "avec", "dans", "sans", "plus", "moins", "tout", "tous", "toute", "toutes", "faire", "fais",
        "peux", "veux", "voudrais", "jarvis", "merci", "alors", "aussi", "encore", "est-ce", "quoi", "quel", "quelle", "comment",
        "cette", "celui", "celle", "leur", "leurs", "mais", "donc", "bien", "tres", "vous", "nous", "elle", "elles", "ils")

    internal fun mots(texte: String): Set<String> =
        normaliser(texte).split(' ').filter { it.length >= 4 && it !in VIDES }.map { it.take(6) }.toSet()

    /** Pertinence d'un outil pour des mots (racines de 6 lettres) : nom compté triple. */
    internal fun score(fn: JSONObject, mots: Set<String>): Int {
        if (mots.isEmpty()) return 0
        val nom = " " + normaliser(fn.optString("name").replace('_', ' ')) + " "
        val desc = " " + normaliser(fn.optString("description").take(600)) + " "
        return mots.sumOf { m -> (if (nom.contains(" $m")) 3 else 0) + (if (desc.contains(" $m")) 1 else 0) }
    }

    private fun nom(o: JSONObject?) = o?.optJSONObject("function")?.optString("name").orEmpty()

    /**
     * Liste allégée : outils non généraux (familles choisies pour la demande), noyau, [prioritaires], puis les outils
     * généraux les plus pertinents, et l'outil [META]. Ordre d'origine conservé.
     */
    fun compacte(schema: JSONArray, generaux: Set<String>, prioritaires: Set<String>, requete: String): JSONArray {
        if (schema.length() <= SEUIL) return schema
        val m = mots(requete)
        val gardes = LinkedHashSet<Int>()
        val candidats = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until schema.length()) {
            val n = nom(schema.optJSONObject(i))
            when {
                n.isBlank() -> {}
                n !in generaux || n in NOYAU || n in prioritaires -> gardes += i
                else -> candidats += i to score(schema.getJSONObject(i).getJSONObject("function"), m)
            }
        }
        val dejaGeneraux = gardes.count { nom(schema.optJSONObject(it)) in generaux }
        candidats.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
            .take((MAX_GENERAUX - dejaGeneraux).coerceAtLeast(6)).forEach { gardes += it.first }
        val out = JSONArray()
        gardes.sorted().forEach { out.put(schema.get(it)) }
        out.put(meta())
        return out
    }

    fun meta(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", META)
        .put("description", "Ta liste d'outils est réduite à ceux qui sont probables pour cette demande. S'il te manque l'outil qu'il faut " +
            "(une action, une recherche, un réglage…), appelle CET outil avec le besoin en quelques mots : les outils correspondants de tout le " +
            "catalogue JARVIS sont ajoutés à ta liste, puis appelle celui qui convient. Ne dis jamais qu'un outil n'existe pas sans l'avoir cherché ici.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("besoin", JSONObject().put("type", "string").put("description", "Ce que l'outil doit faire, ex. « supprimer une alarme », « analyser une vidéo ».")))
            .put("required", JSONArray().put("besoin"))))

    /** Outils du [catalogue] absents de [presents] les plus proches du [besoin] (au plus [n]). */
    fun rechercher(catalogue: JSONArray, besoin: String, presents: Set<String>, n: Int = 8): List<JSONObject> {
        val m = mots(besoin)
        return (0 until catalogue.length()).mapNotNull { catalogue.optJSONObject(it) }
            .filter { nom(it).isNotBlank() && nom(it) !in presents && nom(it) != META }
            .map { it to score(it.getJSONObject("function"), m) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(n).map { it.first }
    }
}
