package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Organisation des salons Discord, sans réseau (testable) : où placer un salon déplacé, et positions de tout le serveur
 * pour une réorganisation complète. Discord range les salons d'un même parent par position croissante : on renumérote
 * donc les voisins dans l'ordre voulu et on envoie le tout en une seule requête.
 */
object DiscordOrganisation {
    const val CATEGORIE = 4

    /** Où mettre un salon parmi ses nouveaux voisins. */
    sealed class Ancre {
        object Haut : Ancre()
        object Bas : Ancre()
        data class Index(val n: Int) : Ancre()
        data class Avant(val id: String) : Ancre()
        data class Apres(val id: String) : Ancre()
    }

    /** Type de salon dit en français → code Discord, ou null. */
    fun type(t: String): Int? = when (fr.dd.jarvis.societe.core.normaliser(t).trim()) {
        "", "texte", "textuel", "text", "ecrit", "salon textuel", "salon texte" -> 0
        "vocal", "voix", "voice", "salon vocal" -> 2
        "categorie", "category", "dossier", "groupe" -> CATEGORIE
        "annonce", "annonces", "news", "actualites" -> 5
        "scene", "stage", "conference" -> 13
        "forum" -> 15
        "media", "medias" -> 16
        else -> null
    }

    fun nomType(type: Int): String = when (type) { 0 -> "texte"; 2 -> "vocal"; 4 -> "catégorie"; 5 -> "annonces"; 13 -> "scène"; 15 -> "forum"; 16 -> "média"; else -> "type $type" }

    private fun parent(o: JSONObject): String? = o.optString("parent_id").takeIf { it.isNotBlank() && it != "null" }

    /** Voisins dans l'ordre affiché par Discord (position, puis identifiant). */
    private fun ordre(l: List<JSONObject>) = l.sortedWith(compareBy<JSONObject>({ it.optInt("position") }, { it.optString("id").padStart(20, '0') }))

    /**
     * Positions à envoyer pour placer [id] dans [parentId] (null = sans catégorie ; ignoré pour une catégorie), à
     * l'[ancre] voulue. [synchroniser] : le salon prend les permissions de sa nouvelle catégorie.
     */
    fun deplacement(tous: List<JSONObject>, id: String, parentId: String?, ancre: Ancre, synchroniser: Boolean = false): JSONArray {
        val moi = tous.first { it.optString("id") == id }
        val estCategorie = moi.optInt("type") == CATEGORIE
        val voisins = ordre(tous.filter { o ->
            o.optString("id") != id && if (estCategorie) o.optInt("type") == CATEGORIE else o.optInt("type") != CATEGORIE && parent(o) == parentId
        }).toMutableList()
        val i = when (ancre) {
            Ancre.Haut -> 0
            Ancre.Bas -> voisins.size
            is Ancre.Index -> ancre.n.coerceIn(0, voisins.size)
            is Ancre.Avant -> voisins.indexOfFirst { it.optString("id") == ancre.id }.let { if (it < 0) voisins.size else it }
            is Ancre.Apres -> voisins.indexOfFirst { it.optString("id") == ancre.id }.let { if (it < 0) voisins.size else it + 1 }
        }
        voisins.add(i, moi)
        return JSONArray().also { out ->
            voisins.forEachIndexed { pos, o ->
                val e = JSONObject().put("id", o.optString("id")).put("position", pos)
                if (o === moi && !estCategorie) {
                    e.put("parent_id", parentId ?: JSONObject.NULL)
                    if (synchroniser && parentId != null) e.put("lock_permissions", true)
                }
                out.put(e)
            }
        }
    }

    /** Une ligne du plan : la catégorie (null = sans catégorie) et ses salons dans l'ordre voulu, tels que dits. */
    data class Ligne(val categorie: String?, val salons: List<String>)

    private val SANS = Regex("^(?:sans categorie|aucune|aucune categorie|hors categorie|racine|en haut|\\(aucune\\)|-)$")

    /**
     * Plan dit ou écrit, une catégorie par ligne : « Accueil : règles, bienvenue, annonces ». L'ordre des lignes est
     * l'ordre des catégories. « Sans catégorie : … » = salons hors catégorie. Une ligne sans « : » = une catégorie seule.
     */
    fun plan(texte: String): List<Ligne> = texte.split('\n', ';').map { it.trim().removePrefix("-").removePrefix("•").trim() }
        .filter { it.isNotBlank() }.map { l ->
            val (cat, reste) = if (l.contains(':') || l.contains("=>")) {
                val k = if (l.contains("=>")) l.indexOf("=>") else l.indexOf(':')
                l.substring(0, k).trim() to l.substring(k + (if (l.contains("=>")) 2 else 1)).trim()
            } else l to ""
            val sans = SANS.matches(fr.dd.jarvis.societe.core.normaliser(cat))
            Ligne(if (sans) null else cat.removePrefix("📁").trim(), reste.split(',').map { it.trim().removePrefix("#").trim() }.filter { it.isNotBlank() })
        }

    /**
     * Positions de toute une réorganisation. [lignes] : (id de catégorie ou null, ids des salons dans l'ordre).
     * Catégories citées d'abord dans l'ordre du plan, puis les autres dans leur ordre actuel ; dans chaque catégorie,
     * les salons cités d'abord, puis ceux qui y étaient déjà et ne sont pas cités. Seuls les salons cités changent de
     * catégorie.
     */
    fun reorganisation(tous: List<JSONObject>, lignes: List<Pair<String?, List<String>>>, synchroniser: Boolean = false): JSONArray {
        val parId = tous.associateBy { it.optString("id") }
        val cites = lignes.flatMap { it.second }.toSet()
        val out = JSONArray()
        val categoriesCitees = lignes.mapNotNull { it.first }.distinct()
        val categories = categoriesCitees + ordre(tous.filter { it.optInt("type") == CATEGORIE && it.optString("id") !in categoriesCitees }).map { it.optString("id") }
        categories.forEachIndexed { i, c -> out.put(JSONObject().put("id", c).put("position", i)) }
        val parents = (listOf<String?>(null) + categories).distinct()
        for (p in parents) {
            val demandes = lignes.filter { it.first == p }.flatMap { it.second }.distinct()
            val restants = ordre(tous.filter { it.optInt("type") != CATEGORIE && parent(it) == p && it.optString("id") !in cites }).map { it.optString("id") }
            (demandes + restants).forEachIndexed { pos, id ->
                val e = JSONObject().put("id", id).put("position", pos)
                if (id in demandes && parId[id]?.let { parent(it) } != p) {
                    e.put("parent_id", p ?: JSONObject.NULL)
                    if (synchroniser && p != null) e.put("lock_permissions", true)
                }
                out.put(e)
            }
        }
        return out
    }

    /** Aperçu lisible d'une organisation (catégories et salons dans l'ordre), à partir des positions prévues. */
    fun apercu(tous: List<JSONObject>, positions: JSONArray): String {
        val nouv = (0 until positions.length()).map { positions.getJSONObject(it) }.associateBy { it.optString("id") }
        fun pos(o: JSONObject) = nouv[o.optString("id")]?.optInt("position") ?: o.optInt("position")
        fun par(o: JSONObject): String? {
            val e = nouv[o.optString("id")]
            return if (e != null && e.has("parent_id")) (if (e.isNull("parent_id")) null else e.optString("parent_id")) else parent(o)
        }
        val sb = StringBuilder()
        val hors = tous.filter { it.optInt("type") != CATEGORIE && par(it) == null }.sortedBy { pos(it) }
        if (hors.isNotEmpty()) sb.append("(sans catégorie) : ").append(hors.joinToString(", ") { "#" + it.optString("name") }).append("\n")
        tous.filter { it.optInt("type") == CATEGORIE }.sortedBy { pos(it) }.forEach { c ->
            val enfants = tous.filter { it.optInt("type") != CATEGORIE && par(it) == c.optString("id") }.sortedBy { pos(it) }
            sb.append("📁 ").append(c.optString("name")).append(" : ").append(enfants.joinToString(", ") { "#" + it.optString("name") }.ifBlank { "(vide)" }).append("\n")
        }
        return sb.toString().trimEnd()
    }
}
