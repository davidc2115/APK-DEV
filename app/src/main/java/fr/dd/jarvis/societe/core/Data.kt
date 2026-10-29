package fr.dd.jarvis.societe.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Serializable
data class SocieteData(
    val societe: Societe = Societe(),
    val tarifs: List<Tarif> = emptyList(),
    val regles: Regles = Regles(),
    val clients: List<Client> = emptyList(),
    val docs: List<Doc> = emptyList(),
    val employes: List<Employe> = emptyList(),
    val paie: ConfigPaie = ConfigPaie(),
    val provisions: List<Provision> = emptyList(),
    val bulletins: List<BulletinRef> = emptyList(),
    val modeles: Map<String, String> = emptyMap(),        // présentations personnalisées (devis, facture, client, paie, planning)
    val modelesPrec: Map<String, String> = emptyMap()     // version précédente (pour annuler)
) {
    fun modele(type: String): String = modeles[type] ?: Templates.defaut(type)

    fun chercherClients(q: String): List<Client> {
        val n = normaliser(q)
        if (n.isBlank()) return emptyList()
        clients.filter { normaliser(it.nom) == n }.takeIf { it.isNotEmpty() }?.let { return it }
        val c = clients.filter { normaliser(it.nom).contains(n) || n.contains(normaliser(it.nom)) }
        if (c.isNotEmpty()) return c
        val mots = n.split(" ").filter { it.length > 2 }
        return clients.filter { cl -> mots.isNotEmpty() && mots.all { normaliser(cl.nom).contains(it) } }
    }

    fun client(id: String) = clients.firstOrNull { it.id == id }
    fun doc(numero: String) = docs.firstOrNull { it.numero.equals(numero.trim(), true) }

    fun numeroSuivant(type: String, annee: Int): String {
        val pre = if (type == "devis") "DEV" else "FAC"
        val max = docs.filter { it.numero.startsWith("$pre-$annee-") }
            .mapNotNull { it.numero.substringAfterLast('-').toIntOrNull() }.maxOrNull() ?: 0
        return "%s-%d-%04d".format(pre, annee, max + 1)
    }

    fun totaux(doc: Doc) = Pricing.totaux(doc.lignes, societe.franchiseTva)

    fun caClient(c: Client): Double = round2(
        docs.filter { it.clientId == c.id && it.type == "facture" && it.statut != "annulee" }.sumOf { totaux(it).ht }
    )

    fun remiseClient(c: Client, aujourdhui: LocalDate = LocalDate.now()): Remise =
        Pricing.remiseAuto(regles, Pricing.anciennete(c.depuis, aujourdhui), c.sites.size)
}

val jsonSociete = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
