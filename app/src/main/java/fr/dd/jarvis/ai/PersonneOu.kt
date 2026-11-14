package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONArray

/**
 * « Où est Angeline ? », « où se trouve ma femme », « position de Paul » : réponse directe par Home Assistant (entités
 * person.* / device_tracker.*, par l'API), sans IA ni serveur — ces questions partaient en commandes SSH.
 */
object PersonneOu {
    private val QUESTION = Regex(
        "\\b(?:ou (?:est|se trouve|se trouvait|etait|se situe|sont|est ce que se trouve)|(?:donne|dis)(?: moi)? (?:la )?position (?:de|d)|" +
            "(?:quelle est |c est quoi )?la position (?:de|d)|position actuelle (?:de|d)|localise|geolocalise|retrouve)\\s+(.+)$"
    )
    private val BRUIT = Regex("\\b(actuellement|maintenant|en ce moment|aujourd hui|hier|ce matin|ce soir|cet apres midi|tout a l heure|stp|svp|s il te plait|s il vous plait|jarvis|exactement|precisement|la)$")
    /** Ce qui n'est pas une personne : objets, fichiers, services… (laissé aux autres outils). */
    private val OBJETS = Regex(
        "\\b(fichiers?|dossiers?|documents?|photos?|images?|videos?|films?|chansons?|musiques?|notes?|voitures?|vehicules?|cles?|clefs?|telephones?|portables?|" +
            "colis|commandes?|livraisons?|factures?|devis|serveurs?|bot|plex|discord|conteneurs?|docker|imprimantes?|camera|cameras|bus|trains?|avions?|vols?|" +
            "liens?|mots? de passe|adresses?|parking|pharmacie|boulangerie|restaurants?|magasins?|gares?|station|meteo|soleil|lune|il|elle|ils|elles|on|ca|cela|tout|je|tu)\\b"
    )
    private val ARTICLE = Regex("^(le|la|les|l|un|une|des|du)\\s+")
    private val PROCHE = Regex("^(ma|mon|mes|notre|nos)\\s+(femme|mari|epouse|epoux|compagne|compagnon|copine|copain|fils|fille|enfants?|fils|bebe|pere|mere|papa|maman|frere|soeur|grand mere|grand pere|belle mere|beau pere)\\b")

    /** Personne demandée (texte d'origine nettoyé), ou null si la phrase ne demande pas où se trouve quelqu'un. */
    fun personne(texte: String): String? {
        val n = normaliser(texte).replace(Regex("[?!.,;:]+"), " ").replace(Regex("\\s+"), " ").trim()
        val m = QUESTION.find(n) ?: return null
        var cible = m.groupValues[1].trim()
        repeat(3) { cible = cible.replace(BRUIT, "").trim() }
        if (cible.isBlank() || cible.split(' ').size > 4) return null
        if (OBJETS.containsMatchIn(cible)) return null
        if (ARTICLE.containsMatchIn(cible)) return null
        if (Regex("^(ma|mon|mes|notre|nos)\\s").containsMatchIn(cible) && !PROCHE.containsMatchIn(cible)) return null
        return cible
    }

    /** Nom à chercher dans Home Assistant : surnom mémorisé (« ma femme » → Angeline), sinon la personne telle quelle. */
    private fun resoudre(app: JarvisApp, cible: String): String {
        val c = cible.replace(Regex("^(ma|mon|mes|notre|nos)\\s+"), "")
        return fr.dd.jarvis.device.Faits.surnoms(app).firstOrNull { s ->
            val k = normaliser(s.surnom).replace(Regex("^(ma|mon|mes|notre|nos|le|la)\\s+"), "")
            k == c || k == cible
        }?.cible?.takeIf { it.isNotBlank() } ?: cible
    }

    /** Réponse directe (position Home Assistant), ou null si Home Assistant ne suit pas cette personne. */
    suspend fun repondre(app: JarvisApp, texte: String): String? {
        val cible = personne(texte) ?: return null
        if (!Serveur.haConfigure(app)) return null
        val nom = resoudre(app, cible)
        val arr = JSONArray(Serveur.ha(app, "GET", "/api/states"))
        val suivis = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.getString("entity_id").startsWith("person.") || it.getString("entity_id").startsWith("device_tracker.") }
        val paires = suivis.map { it.getString("entity_id") to it.optJSONObject("attributes")?.optString("friendly_name").orEmpty() }
        if (Serveur.filtrerEntites(paires, nom, "").isEmpty()) return null
        return Serveur.haPosition(app, nom)
    }
}
