package fr.dd.jarvis.coffre

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Fusion à trois voies des données de la société entre plusieurs téléphones : [base] = dernière version synchronisée,
 * [local] = ce téléphone, [distant] = le coffre. Ce qu'un seul côté a modifié est repris ; quand les deux ont modifié la
 * même fiche, chaque champ est fusionné, et pour un même champ modifié des deux côtés, ce téléphone l'emporte (l'autre
 * version reste dans l'historique du coffre). Les listes de fiches (clients, devis, salariés…) sont fusionnées fiche par
 * fiche grâce à leur identifiant : deux téléphones qui ajoutent chacun un client gardent les deux.
 */
object Fusion {
    /** Parties de la base Société, chacune chiffrée avec la clé de sa catégorie. */
    val PARTIES = linkedMapOf(
        "base" to listOf("societe", "tarifs", "regles", "modeles", "modelesPrec"),
        "clients" to listOf("clients"),
        "documents" to listOf("docs"),
        "paie" to listOf("employes", "paie", "provisions", "bulletins"),
    )

    /** Partie d'un champ de premier niveau (un champ inconnu, ajouté par une version future, va dans « base »). */
    fun partieDe(champ: String): String = PARTIES.entries.firstOrNull { champ in it.value }?.key ?: "base"

    fun extraire(tout: JsonObject, partie: String): JsonObject = JsonObject(tout.filterKeys { partieDe(it) == partie })

    fun remplacer(tout: JsonObject, partie: String, nouvelle: JsonObject): JsonObject =
        JsonObject(tout.filterKeys { partieDe(it) != partie } + nouvelle)

    private val CLES_ID = listOf("id", "numero", "code", "uid")

    fun fusionner(base: JsonElement?, local: JsonElement?, distant: JsonElement?): JsonElement? {
        if (local == distant) return local
        if (base == local) return distant
        if (base == distant) return local
        // Les deux côtés ont changé.
        if (local is JsonObject && distant is JsonObject) return objet(base as? JsonObject, local, distant)
        if (local is JsonArray && distant is JsonArray) cleId(local, distant)?.let { return liste(base as? JsonArray, local, distant, it) }
        // Modifié d'un côté, supprimé de l'autre : la modification gagne. Sinon ce téléphone gagne.
        return local ?: distant
    }

    private fun objet(base: JsonObject?, local: JsonObject, distant: JsonObject): JsonObject {
        val cles = LinkedHashSet<String>().apply { addAll(local.keys); addAll(distant.keys); base?.keys?.let { addAll(it) } }
        val out = LinkedHashMap<String, JsonElement>()
        for (k in cles) fusionner(base?.get(k), local[k], distant[k])?.let { out[k] = it }
        return JsonObject(out)
    }

    /** Clé d'identité commune à toutes les fiches des deux listes (id, numero…) ; null si ce n'est pas une liste de fiches. */
    private fun cleId(vararg listes: JsonArray): String? {
        val tous = listes.flatMap { it }
        if (tous.isEmpty() || tous.any { it !is JsonObject }) return null
        return CLES_ID.firstOrNull { k -> tous.all { o -> ((o as JsonObject)[k] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true } }
    }

    private fun id(o: JsonElement, cle: String) = ((o as JsonObject)[cle] as JsonPrimitive).content

    private fun liste(base: JsonArray?, local: JsonArray, distant: JsonArray, cle: String): JsonArray {
        val b = base?.filterIsInstance<JsonObject>()?.filter { (it[cle] as? JsonPrimitive)?.contentOrNull != null }?.associateBy { id(it, cle) }.orEmpty()
        val l = local.associateBy { id(it, cle) }
        val d = distant.associateBy { id(it, cle) }
        val ordre = LinkedHashSet<String>().apply { addAll(l.keys); addAll(d.keys) }
        return JsonArray(ordre.mapNotNull { k -> fusionner(b[k], l[k], d[k]) })
    }
}
