package fr.dd.jarvis.ai

import android.net.Uri
import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalDate

/**
 * Compte-rendu d'intervention dicté sur place : la dictée est structurée par l'IA (sans rien inventer), enregistrée
 * avec le même outil que Jarvis (anti-doublon, rapport Obsidian + PDF), puis les photos et la signature sont jointes.
 */
object CompteRendu {
    data class Resultat(val texte: String, val date: String?, val description: String?)

    private const val SYSTEME = """Tu structures le compte-rendu d'une intervention de maintenance photovoltaïque dicté par le technicien.
N'invente RIEN : chaque champ reprend uniquement ce qui a été dit (laisse vide sinon). Style factuel et professionnel, phrases courtes.
Réponds uniquement par ce JSON :
{"categorie":"maintenance|panne|diagnostic|nettoyage|remplacement|contrôle|autre","description":"résumé factuel de l'intervention (1 à 3 phrases)",
"diagnostic":"constat (distingue constat et hypothèse)","actions":"actions réellement réalisées","pieces":"pièces remplacées avec références et quantités",
"mesures":"mesures relevées avec unités","statut":"terminee|a_suivre|en_attente","prochaine_maintenance":"yyyy-MM-dd si une date est dite, sinon vide",
"notes":"autres informations utiles (suite à donner, remarques du client)"}"""

    internal fun champs(brut: String?, dictee: String, categorie: String): JSONObject {
        val j = brut?.let { Vision.json(it) } ?: JSONObject()
        fun t(k: String) = j.optString(k).trim().takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        val statut = t("statut").lowercase().takeIf { it in setOf("terminee", "a_suivre", "en_attente") } ?: "terminee"
        val prochaine = t("prochaine_maintenance").takeIf { runCatching { LocalDate.parse(it) }.isSuccess }.orEmpty()
        return JSONObject()
            .put("categorie", t("categorie").ifBlank { categorie })
            .put("description", t("description").ifBlank { dictee.trim().take(500) })
            .put("diagnostic", t("diagnostic")).put("actions", t("actions")).put("pieces", t("pieces")).put("mesures", t("mesures"))
            .put("statut", statut).put("prochaine_maintenance", prochaine).put("notes", t("notes"))
    }

    suspend fun creer(app: JarvisApp, clientId: String, siteId: String, categorie: String, dictee: String,
                      avant: List<Uri>, apres: List<Uri>, signature: ByteArray?): Resultat {
        val svc = app.societe
        val client = svc.data.client(clientId) ?: return Resultat("❌ Client introuvable.", null, null)
        val site = client.sites.firstOrNull { it.id == siteId } ?: return Resultat("❌ Installation introuvable.", null, null)
        val date = LocalDate.now().toString()
        val contexte = "Installation : ${client.nom} — ${site.nom}" + (if (site.kwc > 0) " (${site.kwc} kWc)" else "") +
            (if (site.onduleur.isNotBlank()) ", onduleur ${site.onduleur}" else "") + ". Catégorie choisie : $categorie. Date : $date.\n\nDictée du technicien :\n$dictee"
        val brut = withTimeoutOrNull(45_000) { runCatching { app.llm.agent(SYSTEME, contexte) }.getOrNull() }
        val args = champs(brut, dictee, categorie).put("client", client.nom).put("installation", site.nom).put("date", date)
        val res = ToolBox(app) { _, _ -> true }.execute("intervention_enregistrer", args)
        if (!res.text.startsWith("✅")) return Resultat(res.text, null, null)

        // Fiche créée : photos avant/après et signature jointes (le rapport est régénéré avec elles).
        val description = args.optString("description")
        val inter = svc.data.client(clientId)?.sites?.firstOrNull { it.id == siteId }?.interventions
            ?.firstOrNull { it.date == date && it.description == description }
        val pieces = StringBuilder()
        if (inter != null) withContext(Dispatchers.IO) {
            if (avant.isNotEmpty()) pieces.append("\n📷 " + svc.ajouterPhotosIntervention(clientId, siteId, inter.id, true, avant))
            if (apres.isNotEmpty()) pieces.append("\n📷 " + svc.ajouterPhotosIntervention(clientId, siteId, inter.id, false, apres))
            // La signature a sa propre section, en fin de rapport (pas mêlée aux photos).
            if (signature != null) pieces.append("\n" + svc.ajouterSignature(clientId, siteId, inter.id, signature))
        }
        return Resultat(res.text + pieces, date, description)
    }
}
