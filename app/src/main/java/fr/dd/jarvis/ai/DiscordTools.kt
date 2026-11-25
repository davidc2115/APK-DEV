package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Outils Discord : rôles, permissions de salons, attribution de rôles. Proposés seulement si la conversation les concerne. */
class DiscordTools(private val app: JarvisApp, private val confirm: suspend (String, Boolean) -> Boolean) {

    companion object {
        val KEYWORDS = Regex("discord|r[ôo]le\\b|r[ôo]les\\b|mod[ée]rateur|mod[ée]ration|serveur discord|salon\\b|salons\\b|canaux?\\b|channels?\\b|cat[ée]gorie discord|cat[ée]gories? (de|des|du) salons?|r[ée]organis\\w*.*(salons?|canaux?|channels?|cat[ée]gories?)|permissions? discord|lien d.invitation|invitations? (du|au) serveur", RegexOption.IGNORE_CASE)
        fun concerned(t: String) = KEYWORDS.containsMatchIn(t) || DiscordNoms.cite(t)
        val NAMES = setOf(
            "discord_diagnostic", "discord_guildes", "discord_serveur_info", "discord_conseils_serveur", "discord_roles", "discord_salons", "discord_audit_serveur", "discord_role_creer", "discord_role_modifier",
            "discord_role_supprimer", "discord_role_position", "discord_role_attribuer", "discord_salon_permission", "discord_message_envoyer", "discord_messages_lire", "discord_messages_rechercher", "discord_fil_lire", "discord_message_modifier", "discord_message_supprimer", "discord_message_epingler", "discord_reaction", "discord_fil_creer", "discord_membre_info", "discord_membre_timeout", "discord_membre_expulser", "discord_membre_bannir", "discord_membre_pseudo",
            "discord_salon_creer", "discord_salon_modifier", "discord_salon_supprimer", "discord_salons_reorganiser", "discord_salon_permissions_voir",
            "discord_serveur_modifier", "discord_invitations", "discord_messages_purger", "discord_journal_audit", "discord_fil_gerer"
        )
    }

    private fun prop(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun tool(name: String, desc: String, props: Map<String, JSONObject>, req: List<String> = emptyList()): JSONObject {
        val p = JSONObject(); props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(req))))
    }

    private val permDesc = "Permissions : « toutes_sauf_administrateur » (quasi tout pouvoir, MAIS respecte les salons restreints — contrairement à « administrateur » qui ignore toujours les restrictions de salon), « administrateur » (pouvoir total, y compris sur les salons restreints), ou une liste de droits séparés par des virgules parmi : " +
        Discord.Perm.NOMS.keys.joinToString(", ") + ". Vide = aucune permission (rôle purement cosmétique)."

    val schema: List<JSONObject> = listOf(
        tool("discord_diagnostic", "Discord : diagnostic en lecture seule du bot JARVIS. Vérifie le jeton, l'identité du bot, les serveurs accessibles, son membre et ses rôles sur chaque serveur, la hiérarchie de ses rôles, les permissions cumulées et l'inventaire des salons. Signale les permissions Discord utiles manquantes. Les intents privilégiés du portail développeur ne sont pas lisibles par l'API : ils ne sont pas déclarés comme vérifiés.", mapOf("serveur" to prop("Nom ou ID d'un serveur précis ; vide pour examiner tous les serveurs accessibles au bot."))),
        tool("discord_guildes", "Discord : liste les serveurs (guildes) où le bot JARVIS est présent.", emptyMap()),
        tool("discord_serveur_info", "Discord : affiche la fiche détaillée d’un serveur (description, nombre approximatif de membres et de membres en ligne, vérification, boosts, langue et fonctionnalités). Lecture seule.", mapOf("serveur" to prop("Nom, surnom ou ID du serveur Discord (optionnel si un seul)."))),
        tool("discord_conseils_serveur", "AUDIT ET CONSEILS DISCORD EN LECTURE SEULE : analyse les catégories, salons, permissions, synchronisations et rôles ; détecte les points à vérifier et propose des améliorations concrètes avec les outils Jarvis correspondants. Ne modifie rien sans demande et confirmation distinctes.",
            mapOf("serveur" to prop("Nom, surnom ou ID du serveur Discord (optionnel si un seul)."))),
        tool("discord_roles", "Discord : liste les rôles d'un serveur (nom, couleur, permissions, position).",
            mapOf("serveur" to prop("Nom ou ID du serveur Discord (optionnel si le bot n'est que sur un seul)."))),
        tool("discord_salons", "Discord : liste les salons (textuels, vocaux, catégories) d'un serveur.",
            mapOf("serveur" to prop("Nom, surnom ou ID du serveur Discord (optionnel si un seul)."))),
        tool("discord_audit_serveur", "AUDIT DISCORD STRICTEMENT EN LECTURE SEULE : récupère l'inventaire complet des salons et catégories avec leurs identifiants, positions, rattachements et sujets. Ne modifie jamais le serveur.",
            mapOf("serveur" to prop("Nom, surnom ou ID du serveur Discord (optionnel si un seul)."))),
        tool("discord_messages_lire", "Discord : lit l’historique d’un salon — les derniers messages (1 à 100, pagination avant/après), ou TOUS ceux d'une journée avec « jour » (hier, aujourd'hui, AAAA-MM-JJ) pour un résumé. Serveur et salon : nom, surnom noté par l'utilisateur ou ID. Lecture seule. Peut aussi lire un fil si son ID est fourni.",
            mapOf("serveur" to prop("Nom, surnom ou ID du serveur Discord (optionnel si un seul)."), "salon" to prop("Nom du salon tel que l'utilisateur le dit (emojis, séparateurs et polices décoratives inutiles), ou ID du salon ou du fil."),
                "jour" to prop("Journée à lire en entier : hier, aujourd'hui, avant-hier, ou AAAA-MM-JJ (optionnel)."), "jusqu_au" to prop("Fin de période AAAA-MM-JJ incluse, avec « jour » comme début (optionnel)."), "limite" to prop("Nombre de messages à lire, de 1 à 100 (défaut 30)."), "apres_id" to prop("ID du dernier message déjà lu : ne renvoie que les messages plus récents (optionnel)."), "avant_id" to prop("ID du message curseur : récupère les messages plus anciens (optionnel ; ne pas utiliser avec apres_id).")), listOf("salon")),
        tool("discord_messages_rechercher", "Discord : recherche un mot ou une expression dans les 100 messages les plus récents d’un salon, ou dans une période complète avec « jour » et « jusqu_au » (jusqu’à 500 messages). Filtre facultatif par auteur. Lecture seule.",
            mapOf("serveur" to prop("Nom, surnom ou ID du serveur Discord (optionnel si un seul)."), "salon" to prop("Nom simple du salon (emojis et décorations inutiles) ou ID."), "recherche" to prop("Mot ou expression à retrouver dans le contenu (facultatif, mais recommandé)."), "auteur" to prop("Nom ou pseudo à filtrer (facultatif)."), "jour" to prop("Début de la période : hier, aujourd’hui, avant-hier ou AAAA-MM-JJ (facultatif)."), "jusqu_au" to prop("Fin de période AAAA-MM-JJ incluse (facultatif, avec jour comme début).")), listOf("salon")),
        tool("discord_fil_lire", "Discord : lit les messages d’un fil/thread Discord à partir de son ID (lecture seule). Le bot doit avoir accès au fil.",
            mapOf("serveur" to prop("Nom, surnom ou ID du serveur Discord (optionnel si un seul)."), "fil_id" to prop("ID numérique du fil Discord."), "limite" to prop("Nombre de messages à lire, de 1 à 100 (défaut 30)."), "avant_id" to prop("ID curseur pour remonter dans l’historique (optionnel).")), listOf("fil_id")),
        tool("discord_message_envoyer", "Discord : envoie un message dans un salon textuel ou d'annonces. Les mentions automatiques (@everyone/@here et rôles) sont désactivées par sécurité.",
            mapOf("serveur" to prop("Nom, surnom ou ID du serveur Discord (optionnel si un seul)."),
                "salon" to prop("Nom simple du salon textuel cible (emojis et décorations inutiles) ou ID."),
                "message" to prop("Texte exact à envoyer (2 000 caractères maximum).")), listOf("salon", "message")),
        tool("discord_message_modifier", "Discord : modifie le texte d'un message existant. Nécessite le salon et l'identifiant du message.", mapOf("serveur" to prop("Nom ou ID du serveur."), "salon" to prop("Nom ou ID du salon."), "message_id" to prop("ID du message à modifier."), "message" to prop("Nouveau texte (2 000 caractères maximum).")), listOf("salon", "message_id", "message")),
        tool("discord_message_supprimer", "Discord : supprime définitivement un message. Demande confirmation.", mapOf("serveur" to prop("Nom ou ID du serveur."), "salon" to prop("Nom ou ID du salon."), "message_id" to prop("ID du message à supprimer.")), listOf("salon", "message_id")),
        tool("discord_message_epingler", "Discord : épingle ou désépingle un message.", mapOf("serveur" to prop("Nom ou ID du serveur."), "salon" to prop("Nom ou ID du salon."), "message_id" to prop("ID du message."), "action" to prop("epingler (défaut) ou desepingler.")), listOf("salon", "message_id")),
        tool("discord_reaction", "Discord : ajoute ou retire la réaction du bot sur un message.", mapOf("serveur" to prop("Nom ou ID du serveur."), "salon" to prop("Nom ou ID du salon."), "message_id" to prop("ID du message."), "emoji" to prop("Emoji Unicode ou nom:id d'un emoji personnalisé."), "action" to prop("ajouter (défaut) ou retirer.")), listOf("salon", "message_id", "emoji")),
        tool("discord_fil_creer", "Discord : crée un fil de discussion à partir d'un message existant.", mapOf("serveur" to prop("Nom ou ID du serveur."), "salon" to prop("Nom ou ID du salon parent."), "message_id" to prop("ID du message de départ."), "nom" to prop("Nom du nouveau fil (1 à 100 caractères)."), "archivage_minutes" to prop("60, 1440, 4320 ou 10080 minutes (défaut 1440).")), listOf("salon", "message_id", "nom")),
        tool("discord_membre_info", "Discord : affiche les informations disponibles d'un membre (pseudo, compte, date d'arrivée, rôles, timeout). Lecture seule.", mapOf("serveur" to prop("Nom ou ID du serveur."), "membre" to prop("Pseudo, nom ou ID du membre.")), listOf("membre")),
        tool("discord_membre_timeout", "Discord : applique un timeout temporaire ou retire le timeout d'un membre. Confirmation obligatoire. Maximum 28 jours.", mapOf("serveur" to prop("Nom ou ID du serveur."), "membre" to prop("Pseudo, nom ou ID du membre."), "duree_minutes" to prop("Durée en minutes (1 à 40320). Omettre ou mettre 0 avec action=retirer pour lever le timeout."), "raison" to prop("Raison de modération (optionnelle)."), "action" to prop("appliquer (défaut) ou retirer.")), listOf("membre")),
        tool("discord_membre_expulser", "Discord : expulse un membre du serveur (il pourra revenir avec une invitation). Confirmation obligatoire.", mapOf("serveur" to prop("Nom ou ID du serveur."), "membre" to prop("Pseudo, nom ou ID du membre."), "raison" to prop("Raison de l'expulsion (optionnelle).")), listOf("membre")),
        tool("discord_membre_bannir", "Discord : bannit un membre du serveur. Peut supprimer ses messages récents (maximum 7 jours). Confirmation obligatoire.", mapOf("serveur" to prop("Nom ou ID du serveur."), "membre" to prop("Pseudo, nom ou ID du membre."), "raison" to prop("Raison du bannissement (optionnelle)."), "supprimer_messages_heures" to prop("0 à 168 heures de messages à supprimer (défaut 0).")), listOf("membre")),
        tool("discord_membre_pseudo", "Discord : change le pseudo d'un membre sur ce serveur (vide pour réinitialiser). Confirmation obligatoire.", mapOf("serveur" to prop("Nom ou ID du serveur."), "membre" to prop("Pseudo, nom ou ID du membre."), "pseudo" to prop("Nouveau pseudo (32 caractères maximum), vide pour réinitialiser.")), listOf("membre", "pseudo")),
        tool("discord_role_creer", "Discord : crée un nouveau rôle sur le serveur.",
            mapOf("serveur" to prop("Nom, surnom ou ID du serveur (optionnel si un seul)."), "nom" to prop("Nom du rôle."),
                "couleur" to prop("Couleur hexadécimale, ex. #ff5733 (optionnel)."), "permissions" to prop(permDesc),
                "affiche_separement" to prop("« true » pour afficher ce rôle séparément dans la liste des membres (optionnel)."),
                "mentionnable" to prop("« true » si n'importe qui peut mentionner ce rôle (optionnel).")), listOf("nom")),
        tool("discord_role_modifier", "Discord : modifie un rôle existant (nom, couleur, permissions…).",
            mapOf("serveur" to prop("Nom, surnom ou ID du serveur (optionnel si un seul)."), "role" to prop("Nom simple (ou ID) du rôle à modifier. Emojis et décorations inutiles."),
                "nouveau_nom" to prop("Nouveau nom (optionnel)."), "couleur" to prop("Nouvelle couleur hexadécimale, ex. #ff5733 (optionnel)."),
                "permissions" to prop(permDesc + " (optionnel : REMPLACE toutes les permissions du rôle ; laisse vide pour ne pas y toucher)."),
                "ajouter_permissions" to prop("Droits à AJOUTER au rôle en gardant les autres (même nomenclature, séparés par des virgules ; optionnel)."),
                "retirer_permissions" to prop("Droits à RETIRER du rôle en gardant les autres (optionnel)."),
                "affiche_separement" to prop("« true »/« false » (optionnel)."), "mentionnable" to prop("« true »/« false » (optionnel).")), listOf("role")),
        tool("discord_role_supprimer", "Discord : supprime définitivement un rôle du serveur.",
            mapOf("serveur" to prop("Nom, surnom ou ID du serveur (optionnel si un seul)."), "role" to prop("Nom simple (ou ID) du rôle à supprimer. Emojis et décorations inutiles.")), listOf("role")),
        tool("discord_role_position", "Discord : change la position d'un rôle dans la hiérarchie du serveur (plus un rôle est haut, plus il l'emporte sur les rôles en dessous — c'est ce qui permet à un rôle de gérer/dépasser les rôles inférieurs).",
            mapOf("serveur" to prop("Nom, surnom ou ID du serveur (optionnel si un seul)."), "role" to prop("Nom simple (ou ID) du rôle à déplacer. Emojis et décorations inutiles."),
                "position" to prop("« haut » ou « maximum » pour le placer le plus haut possible (Discord ne permet jamais de dépasser le rôle le plus haut du bot JARVIS lui-même), « bas » pour le mettre tout en bas (juste au-dessus de @everyone), ou un nombre pour une position précise.")), listOf("role", "position")),
        tool("discord_role_attribuer", "Discord : donne ou retire un rôle à un membre du serveur.",
            mapOf("serveur" to prop("Nom, surnom ou ID du serveur (optionnel si un seul)."), "membre" to prop("Nom, pseudo ou ID du membre."),
                "role" to prop("Nom simple (ou ID) du rôle. Emojis et décorations inutiles."), "action" to prop("donner (défaut) ou retirer.")), listOf("membre", "role")),
        tool("discord_salon_permission", "Discord : règle les permissions précises d'un salon pour un rôle ou un membre. Peut autoriser/refuser plusieurs droits individuellement, appliquer un profil d'accès, ou réinitialiser l'override.",
            mapOf("serveur" to prop("Nom, surnom ou ID du serveur (optionnel si un seul)."), "salon" to prop("Nom simple (emojis et décorations inutiles) ou ID du salon. Inutile si « tous_les_salons » est à true."),
                "categorie" to prop("Nom ou ID d'une CATÉGORIE dont régler les permissions (à la place de « salon », optionnel)."),
                "synchroniser_salons" to prop("Avec « categorie » : « true » pour que tous les salons de la catégorie reprennent ensuite ses permissions (optionnel)."),
                "tous_les_salons" to prop("« true » pour appliquer la règle à TOUS les salons non-catégories du serveur."),
                "role" to prop("Nom simple (ou ID) du rôle concerné (ou utilise « membre » à la place pour un membre précis). Emojis et décorations inutiles."), "membre" to prop("Nom, pseudo ou ID d'un membre précis (optionnel, à la place de « role »)."),
                "autoriser" to prop("Liste facultative des droits à autoriser, séparés par des virgules (formulation libre acceptée : « voir le salon », « envoyer des messages »…). Noms : " + Discord.Perm.NOMS.keys.filter { it != "administrateur" }.joinToString(", ") + "."),
                "refuser" to prop("Liste facultative des droits à refuser, séparés par des virgules. Même nomenclature que « autoriser »."),
                "acces" to prop("Profil facultatif : « voir_salon », « voir_salon_seulement », « toutes_permissions », « refuser_voir_salon » ou « defaut ». Utilise autoriser/refuser pour régler des droits individuels."))),
        tool("discord_salon_creer", "Discord : crée un salon (texte, vocal, annonces, forum, scène) ou une CATÉGORIE, éventuellement dans une catégorie, à une position, privé pour certains rôles. Confirmation demandée.",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."), "nom" to prop("Nom du salon ou de la catégorie (emojis permis)."),
                "type" to prop("texte (défaut) | vocal | categorie | annonces | forum | scene."), "categorie" to prop("Catégorie où le créer (nom ou ID, optionnel)."),
                "position" to prop("haut | bas | un nombre | avant:<salon> | apres:<salon> (optionnel, défaut : en bas)."), "sujet" to prop("Sujet / description du salon (optionnel)."),
                "prive" to prop("« true » : caché à @everyone, visible seulement par « roles_acces » (optionnel)."), "roles_acces" to prop("Rôles qui voient le salon privé, séparés par des virgules."),
                "synchroniser" to prop("« true » : reprend les permissions de sa catégorie (défaut si ni privé ni rôles)."),
                "lent_secondes" to prop("Mode lent en secondes (0 à 21600, optionnel)."), "nsfw" to prop("« true »/« false » (optionnel)."),
                "limite_utilisateurs" to prop("Vocal : nombre maximum de personnes (0 = illimité)."), "debit" to prop("Vocal : débit en kbit/s (8 à 96, plus si boosté).")), listOf("nom")),
        tool("discord_salon_modifier", "Discord : RENOMME, DÉPLACE (vers une autre catégorie et/ou une position) ou règle un salon ou une catégorie (sujet, mode lent, NSFW, limite et débit vocal, synchronisation des permissions avec la catégorie). Confirmation demandée.",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."), "salon" to prop("Salon à modifier (nom ou ID)."), "categorie_cible" to prop("Catégorie à modifier elle-même (nom ou ID), à la place de « salon »."),
                "nouveau_nom" to prop("Nouveau nom (optionnel)."), "deplacer_vers" to prop("Catégorie de destination (nom ou ID), ou « aucune » pour la sortir de toute catégorie (optionnel)."),
                "position" to prop("haut | bas | un nombre | avant:<salon> | apres:<salon> — parmi ses voisins (optionnel)."),
                "synchroniser" to prop("« true » : reprend les permissions de sa catégorie (optionnel)."), "sujet" to prop("Nouveau sujet (optionnel ; « - » pour l'effacer)."),
                "lent_secondes" to prop("Mode lent en secondes (0 à 21600)."), "nsfw" to prop("« true »/« false »."), "limite_utilisateurs" to prop("Vocal : maximum de personnes (0 = illimité)."), "debit" to prop("Vocal : débit en kbit/s.")), emptyList()),
        tool("discord_salon_supprimer", "Discord : supprime DÉFINITIVEMENT un salon, ou une catégorie (ses salons restent, hors catégorie, sauf avec_contenu=true). Confirmation obligatoire.",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."), "salon" to prop("Salon à supprimer (nom ou ID)."), "categorie" to prop("Catégorie à supprimer (nom ou ID), à la place de « salon »."),
                "avec_contenu" to prop("Catégorie : « true » pour supprimer aussi tous ses salons (défaut false).")), emptyList()),
        tool("discord_salons_reorganiser", "Discord : RÉORGANISE tout le serveur en une fois — ordre des catégories, salons rangés dans chaque catégorie, dans l'ordre donné. Montre l'aperçu avant/après et demande confirmation ; les salons non cités restent où ils sont (après les salons cités). Peut créer les catégories manquantes.",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."),
                "plan" to prop("Une catégorie par ligne, dans l'ordre voulu : « Catégorie : salon1, salon2, … » (« Sans catégorie : … » pour les salons hors catégorie). Noms tels qu'ils se disent (emojis inutiles)."),
                "creer_categories" to prop("« true » pour créer les catégories du plan qui n'existent pas encore."), "synchroniser" to prop("« true » : les salons déplacés reprennent les permissions de leur nouvelle catégorie.")), listOf("plan")),
        tool("discord_salon_permissions_voir", "Discord : montre les permissions particulières d'un salon ou d'une catégorie (pour chaque rôle / membre : droits autorisés et refusés) et s'il est synchronisé avec sa catégorie. Lecture seule.",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."), "salon" to prop("Salon (nom ou ID)."), "categorie" to prop("Catégorie (nom ou ID), à la place de « salon ».")), emptyList()),
        tool("discord_serveur_modifier", "Discord : modifie les réglages du serveur — nom, description, niveau de vérification, notifications par défaut, filtre de contenu, salon système, salon et délai AFK. Confirmation demandée.",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."), "nom" to prop("Nouveau nom (optionnel)."), "description" to prop("Description (serveur Communauté, optionnel)."),
                "verification" to prop("aucune | faible | moyenne | elevee | maximale (optionnel)."), "notifications" to prop("tous_les_messages | mentions (optionnel)."),
                "filtre_contenu" to prop("desactive | membres_sans_role | tous (optionnel)."), "salon_systeme" to prop("Salon des messages de bienvenue (nom/ID, ou « aucun »)."),
                "salon_afk" to prop("Salon vocal AFK (nom/ID, ou « aucun »)."), "delai_afk_minutes" to prop("1, 5, 15, 30 ou 60.")), emptyList()),
        tool("discord_invitations", "Discord : liste, crée ou supprime les invitations du serveur.",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."), "action" to prop("lister (défaut) | creer | supprimer."), "salon" to prop("creer : salon de l'invitation (défaut : le premier salon textuel)."),
                "duree_heures" to prop("creer : validité en heures (0 = permanente, défaut 24)."), "utilisations_max" to prop("creer : 0 = illimité (défaut)."), "code" to prop("supprimer : code de l'invitation.")), emptyList()),
        tool("discord_messages_purger", "Discord : supprime d'un coup les derniers messages d'un salon (2 à 100, de moins de 14 jours), avec filtre facultatif par auteur ou par texte. Confirmation obligatoire.",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."), "salon" to prop("Salon (nom ou ID)."), "nombre" to prop("Nombre de messages récents à examiner (1 à 100)."),
                "auteur" to prop("Ne supprimer que ceux de cet auteur (optionnel)."), "contenant" to prop("Ne supprimer que ceux qui contiennent ce texte (optionnel).")), listOf("salon", "nombre")),
        tool("discord_journal_audit", "Discord : journal d'audit du serveur — qui a créé, modifié, supprimé quoi (salons, rôles, membres, permissions), et quand. Lecture seule.",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."), "limite" to prop("Nombre d'entrées (1 à 100, défaut 25).")), emptyList()),
        tool("discord_fil_gerer", "Discord : gère un fil (thread) ou une publication de forum — archiver, désarchiver, verrouiller, déverrouiller, renommer, supprimer. Confirmation demandée.",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."), "fil" to prop("ID du fil (ou son nom)."),
                "action" to prop("archiver | desarchiver | verrouiller | deverrouiller | renommer | supprimer."), "nom" to prop("renommer : nouveau nom.")), listOf("fil", "action"))
    )

    private fun guildeObj(a: JSONObject): JSONObject {
        val demande = a.optString("serveur").trim().ifBlank { app.store.discordGuildId.value.trim() }
        return Discord.trouverGuilde(app, demande) ?: throw IllegalStateException(
            (if (demande.isBlank()) "Le bot est présent sur plusieurs serveurs : précise lequel (nom ou ID), ou enregistre un serveur par défaut dans Réglages › Comptes › Discord."
            else "Aucun serveur Discord ne correspond à « $demande » (ni par son nom, ni par un surnom noté).") +
                " Serveurs où le bot est présent : " + Discord.noms(Discord.guildes(app)) + ". Utilise le nom exact de l'un d'eux ; si l'utilisateur lui donne un surnom, enregistre-le avec memoire_faits (action=surnom, domaine=discord_serveur)."
        )
    }

    private fun guilde(a: JSONObject): String = guildeObj(a).getString("id")

    suspend fun execute(name: String, a: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        try { run(name, a) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { ToolResult("❌ ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun vrai(s: String) = s.trim().lowercase() in setOf("true", "oui", "1", "yes", "vrai")

    private fun tous(gid: String): List<JSONObject> = Discord.salons(app, gid).let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }

    /** Salon ou catégorie visé : « salon » (puis, à défaut, une catégorie du même nom) ou « categorie » ; ID accepté. */
    private fun cible(gid: String, salon: String, categorie: String): JSONObject? {
        val l = tous(gid)
        if (salon.isNotBlank()) return Discord.trouverSalon(app, gid, salon) ?: Discord.trouverCategorie(app, gid, salon)
            ?: l.firstOrNull { it.optString("id") == salon.trim() }
        if (categorie.isNotBlank()) return Discord.trouverCategorie(app, gid, categorie) ?: l.firstOrNull { it.optString("id") == categorie.trim() }
        return null
    }

    /** « haut », « bas », « 3 », « avant:général », « après règles » → ancre parmi les voisins ; null si rien de dit. */
    private fun ancre(gid: String, t: String): DiscordOrganisation.Ancre? {
        val n = fr.dd.jarvis.societe.core.normaliser(t).trim()
        if (n.isBlank()) return null
        return when {
            n.startsWith("haut") || n.startsWith("premier") || n == "debut" || n == "tout en haut" -> DiscordOrganisation.Ancre.Haut
            n.startsWith("bas") || n.startsWith("dernier") || n == "fin" || n == "tout en bas" -> DiscordOrganisation.Ancre.Bas
            n.toIntOrNull() != null -> DiscordOrganisation.Ancre.Index(maxOf(0, n.toInt() - 1))
            n.startsWith("avant") || n.startsWith("apres") -> {
                val nom = t.trim().replace(Regex("^(?i)(avant|apr[eè]s)\\s*(de|du|le|la|:)?\\s*"), "").trim()
                val ref = cible(gid, nom, "") ?: throw IllegalArgumentException("Salon de référence « $nom » introuvable pour la position.")
                if (n.startsWith("avant")) DiscordOrganisation.Ancre.Avant(ref.getString("id")) else DiscordOrganisation.Ancre.Apres(ref.getString("id"))
            }
            else -> throw IllegalArgumentException("Position « $t » non comprise (haut, bas, un nombre, avant:<salon> ou après:<salon>).")
        }
    }

    private fun entier(s: String) = s.trim().toIntOrNull()

    /** Réglages communs création / modification (sujet, mode lent, NSFW, vocal). */
    private fun reglages(a: JSONObject, corps: JSONObject, type: Int) {
        a.optString("sujet").trim().takeIf { it.isNotBlank() }?.let { corps.put("topic", if (it == "-") "" else it.take(1024)) }
        entier(a.optString("lent_secondes"))?.let { corps.put("rate_limit_per_user", it.coerceIn(0, 21600)) }
        a.optString("nsfw").trim().takeIf { it.isNotBlank() }?.let { corps.put("nsfw", vrai(it)) }
        if (type == 2 || type == 13) {
            entier(a.optString("limite_utilisateurs"))?.let { corps.put("user_limit", it.coerceIn(0, 99)) }
            entier(a.optString("debit"))?.let { corps.put("bitrate", (if (it < 1000) it * 1000 else it).coerceIn(8000, 384000)) }
        }
    }

    private fun couleurHex(s: String): Int? {
        val h = s.trim().removePrefix("#")
        return if (h.isBlank()) null else h.toIntOrNull(16)
    }

    private suspend fun run(name: String, a: JSONObject): ToolResult {
        return when (name) {
        "discord_diagnostic" -> {
            val bot = Discord.moi(app)
            val botId = bot.optString("id")
            val botNom = bot.optString("global_name").ifBlank { bot.optString("username").ifBlank { "Bot inconnu" } }
            val toutes = Discord.guildes(app)
            val demande = a.optString("serveur").trim()
            val guildes = if (demande.isBlank()) {
                (0 until toutes.length()).map { toutes.getJSONObject(it) }
            } else {
                val choisie = Discord.trouverGuilde(app, demande)
                    ?: return ToolResult("Serveur « $demande » introuvable parmi ceux accessibles au bot : " + Discord.noms(toutes) + ".")
                listOf(choisie)
            }
            if (guildes.isEmpty()) return ToolResult("Le jeton est accepté pour l'identité du bot « $botNom », mais aucun serveur accessible n'a été retourné par l'API.")
            val lignes = mutableListOf("# Diagnostic du bot Discord — lecture seule", "Bot : **$botNom** · ID $botId", "Serveurs inspectés : ${guildes.size}", "Aucune modification effectuée.")
            val droitsUtiles = listOf(
                Discord.Perm.VIEW_CHANNEL to "voir les salons",
                Discord.Perm.SEND_MESSAGES to "envoyer des messages",
                Discord.Perm.READ_MESSAGE_HISTORY to "lire l'historique",
                Discord.Perm.ADD_REACTIONS to "ajouter des réactions",
                Discord.Perm.MANAGE_MESSAGES to "gérer les messages",
                Discord.Perm.MANAGE_CHANNELS to "gérer les salons",
                Discord.Perm.MANAGE_ROLES to "gérer les rôles",
                Discord.Perm.MANAGE_GUILD to "gérer le serveur",
                Discord.Perm.KICK_MEMBERS to "expulser des membres",
                Discord.Perm.BAN_MEMBERS to "bannir des membres",
                Discord.Perm.MODERATE_MEMBERS to "modérer les membres",
                Discord.Perm.MANAGE_THREADS to "gérer les fils",
                Discord.Perm.VIEW_AUDIT_LOG to "voir les logs du serveur",
                Discord.Perm.CREATE_INSTANT_INVITE to "créer des invitations"
            )
            for (g in guildes) {
                val gid = g.optString("id")
                val nom = g.optString("name")
                val roles = Discord.roles(app, gid)
                val membre = runCatching { Discord.membre(app, gid, botId) }.getOrNull()
                val rolesBot = (membre?.optJSONArray("roles") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
                var permissions = 0L
                val rolesAttribues = mutableListOf<JSONObject>()
                for (i in 0 until roles.length()) {
                    val role = roles.getJSONObject(i)
                    val id = role.optString("id")
                    if (id == gid || id in rolesBot) {
                        permissions = permissions or (role.optString("permissions", "0").toLongOrNull() ?: 0L)
                        if (id != gid) rolesAttribues += role
                    }
                }
                val admin = permissions and Discord.Perm.ADMINISTRATOR != 0L
                val plusHaut = rolesAttribues.maxByOrNull { it.optInt("position") }
                val salons = runCatching { Discord.salons(app, gid) }.getOrNull()
                lignes += ""
                lignes += "## $nom"
                lignes += "Membre bot : " + if (membre == null) "non récupérable (permissions ou accès membre à vérifier)" else "présent"
                lignes += "Salons retournés par l'API : ${salons?.length() ?: "non vérifiables"} · rôles du serveur : ${roles.length()}"
                lignes += "Rôle le plus haut du bot : " + (plusHaut?.optString("name") ?: "@everyone") + (plusHaut?.let { " (position ${it.optInt("position")})" } ?: "")
                lignes += "Permissions cumulées des rôles : " + if (admin) "ADMINISTRATEUR (toutes permissions)" else Discord.Perm.noms(permissions).joinToString(", ").ifBlank { "aucune permission détectée" }
                if (!admin) {
                    val manquantes = droitsUtiles.filter { (bit, _) -> permissions and bit == 0L }.map { it.second }
                    lignes += "Permissions utiles absentes des rôles : " + manquantes.joinToString(", ").ifBlank { "aucune parmi celles contrôlées" }
                }
                if (rolesAttribues.isEmpty()) lignes += "Attention : aucun rôle attribué au bot au-delà de @everyone."
            }
            lignes += ""
            lignes += "Limite du diagnostic : les permissions affichées sont l'union des permissions des rôles, avant les restrictions/overrides propres à chaque salon. Les intents MESSAGE_CONTENT et GUILD_MEMBERS doivent être vérifiés dans le portail développeur ou les journaux PM2."
            ToolResult(lignes.joinToString("\n"), direct = true)
        }
        "discord_guildes" -> {
            val arr = Discord.guildes(app)
            ToolResult("# 💬 Serveurs Discord (${arr.length()})\n" + (0 until arr.length()).joinToString("\n") { i -> "- " + Discord.affiche(arr.getJSONObject(i).optString("name")) }, direct = true)
        }
        "discord_serveur_info" -> {
            val guilde = guildeObj(a)
            val info = Discord.infosGuilde(app, guilde.getString("id"))
            val verification = when (info.optInt("verification_level", -1)) {
                0 -> "aucun"
                1 -> "faible"
                2 -> "moyen"
                3 -> "élevé"
                4 -> "très élevé"
                else -> "non communiqué"
            }
            val fonctions = info.optJSONArray("features")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
            val lignes = listOf(
                "# 💬 ${info.optString("name", guilde.optString("name"))}",
                "Description : ${info.optString("description").ifBlank { "aucune" }}",
                "Identifiant : ${info.optString("id")}",
                "Propriétaire (ID) : ${info.optString("owner_id").ifBlank { "non communiqué" }}",
                "Membres : environ ${info.optInt("approximate_member_count", -1).takeIf { it >= 0 } ?: "non communiqué"}",
                "En ligne : environ ${info.optInt("approximate_presence_count", -1).takeIf { it >= 0 } ?: "non communiqué"}",
                "Niveau de vérification : $verification",
                "Boosts : niveau ${info.optInt("premium_tier")} · ${info.optInt("premium_subscription_count")} abonnement(s)",
                "Langue préférée : ${info.optString("preferred_locale").ifBlank { "non communiquée" }}",
                "Fonctionnalités : ${fonctions.joinToString(", ").ifBlank { "non communiquées par API" }}"
            )
            ToolResult(lignes.joinToString("\n"), direct = true)
        }
        "discord_salons", "discord_audit_serveur" -> {
            val gid = guilde(a)
            val arr = Discord.salons(app, gid)
            val types = mapOf(0 to "texte", 2 to "vocal", 4 to "catégorie", 5 to "annonces", 13 to "scène", 14 to "répertoire", 15 to "forum", 16 to "média")
            val all = (0 until arr.length()).map { arr.getJSONObject(it) }
            val categories = all.filter { it.optInt("type") == 4 }.sortedBy { it.optInt("position") }
            val channels = all.filter { it.optInt("type") != 4 }
            val categoryNames = categories.associate { it.optString("id") to it.optString("name") }
            val lines = mutableListOf(
                "# Inventaire Discord — lecture seule",
                "Total reçu par l'API : ${all.size} objets ; catégories : ${categories.size} ; salons : ${channels.size}.",
                "Aucune modification n'a été effectuée."
            )
            fun line(channel: JSONObject): String {
                val kind = types[channel.optInt("type")] ?: "type ${channel.optInt("type")}"
                val topic = channel.optString("topic").trim().take(140)
                return "- [$kind] #${Discord.affiche(channel.optString("name"))} · ID ${channel.optString("id")} · position ${channel.optInt("position")}" +
                    (if (topic.isNotBlank()) " · sujet : $topic" else "")
            }
            for (category in categories) {
                val id = category.optString("id")
                lines += ""
                lines += "📁 ${Discord.affiche(category.optString("name"))} · ID $id · position ${category.optInt("position")}"
                channels.filter { it.optString("parent_id") == id }.sortedBy { it.optInt("position") }.forEach { lines += line(it) }
            }
            val uncategorized = channels.filter { it.optString("parent_id").isBlank() || !categoryNames.containsKey(it.optString("parent_id")) }
                .sortedBy { it.optInt("position") }
            if (uncategorized.isNotEmpty()) {
                lines += ""
                lines += "📂 Sans catégorie / rattachement inconnu"
                uncategorized.forEach { lines += line(it) }
            }
            val byType = all.groupingBy { types[it.optInt("type")] ?: "type ${it.optInt("type")}" }.eachCount().toSortedMap()
            lines += ""
            lines += "Contrôle des types : " + byType.entries.joinToString(", ") { "${it.key}=${it.value}" }
            lines += ""
            lines += "Pour lire, écrire ou régler un salon, donne simplement son nom (entre parenthèses ci-dessus) : emojis, séparateurs et polices décoratives sont ignorés."
            ToolResult(lines.joinToString("\n"), direct = true)
        }
        "discord_conseils_serveur" -> {
            val g = guildeObj(a)
            val gid = g.getString("id")
            val salons = Discord.salons(app, gid)
            val roles = Discord.roles(app, gid)
            ToolResult(DiscordConseils.analyser(g.optString("name"), gid, salons, roles), direct = true)
        }
        "discord_messages_lire", "discord_messages_rechercher", "discord_fil_lire" -> {
            val g = guildeObj(a); val gid = g.getString("id")
            val estFil = name == "discord_fil_lire"
            val cible = if (estFil) a.optString("fil_id").trim() else a.optString("salon").trim()
            if (cible.isBlank()) return ToolResult(if (estFil) "ID du fil manquant." else "Nom ou ID du salon manquant.")
            val salon = Discord.trouverSalon(app, gid, cible)
                ?: if (cible.matches(Regex("\\d{17,20}"))) JSONObject().put("id", cible).put("name", if (estFil) "fil $cible" else "salon $cible").put("type", if (estFil) 11 else 0)
                   else return ToolResult(Discord.introuvable("salon", cible, g.optString("name"), Discord.salons(app, gid)))
            if (salon.optInt("type") !in setOf(0, 5, 10, 11, 12, 15, 16)) return ToolResult("Ce type de salon/fil ne permet pas la lecture de messages.")
            val limite = (a.optString("limite").toIntOrNull() ?: 30).coerceIn(1, 100)
            val apres = a.optString("apres_id").trim()
            val avant = a.optString("avant_id").trim()
            // « Ce qui a été dit hier » : TOUS les messages de la journée (ou de la période), page par page.
            val periode = Discord.periode(a.optString("jour").ifBlank { a.optString("date") }, a.optString("jusqu_au"))
            var messages = if (periode != null) Discord.messagesPeriode(app, salon.getString("id"), periode.first, periode.second)
                else Discord.messages(app, salon.getString("id"), if (name == "discord_messages_rechercher") 100 else limite, apres, avant).let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
            if (name == "discord_messages_rechercher") {
                val mot = a.optString("recherche").trim()
                val auteurFiltre = a.optString("auteur").trim()
                messages = messages.filter { m ->
                    val contenu = m.optString("content")
                    val auteur = m.optJSONObject("author")?.optString("global_name").orEmpty() + " " + m.optJSONObject("author")?.optString("username").orEmpty()
                    (mot.isBlank() || contenu.contains(mot, ignoreCase = true)) && (auteurFiltre.isBlank() || auteur.contains(auteurFiltre, ignoreCase = true))
                }
            }
            messages = messages.sortedBy { runCatching { it.optString("id").toBigInteger() }.getOrDefault(java.math.BigInteger.ZERO) }
            if (messages.isEmpty()) return ToolResult(when {
                periode != null && name == "discord_messages_rechercher" -> "Aucun résultat correspondant dans #${salon.optString("name")} (${g.optString("name")}) sur cette période."
                periode != null -> "Aucun message dans #${salon.optString("name")} (${g.optString("name")}) sur cette période."
                name == "discord_messages_rechercher" -> "Aucun résultat dans les 100 messages récents de #${salon.optString("name")}."
                else -> "Aucun message trouvé dans #${salon.optString("name")} / le fil demandé."
            }, direct = periode == null)
            val lignes = messages.map { m ->
                val auteur = m.optJSONObject("author")?.optString("global_name").orEmpty().ifBlank { m.optJSONObject("author")?.optString("username").orEmpty().ifBlank { "Utilisateur inconnu" } }
                val date = runCatching { java.time.Instant.parse(m.optString("timestamp")).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")) }.getOrDefault("")
                val contenu = m.optString("content").trim().ifBlank { if (m.optJSONArray("attachments")?.length()?.let { it > 0 } == true) "[pièce jointe]" else "[message sans texte — contenu éventuellement masqué par les intents Discord]" }
                "[$date] **$auteur** : $contenu" + (if (m.optJSONArray("attachments")?.length()?.let { it > 0 } == true) " 📎" else "") + "\n_ID ${m.optString("id")}_"
            }
            // Une période (« hier ») sert à résumer : le texte revient au modèle au lieu d'être affiché brut.
            ToolResult("# Messages de #${salon.optString("name")} (${g.optString("name")}) — ${messages.size} résultat(s)\n" + lignes.joinToString("\n"), direct = periode == null)
        }
        "discord_message_envoyer" -> {
            val g = guildeObj(a)
            val gid = g.getString("id")
            val gnom = g.optString("name")
            val nomSalon = a.optString("salon").trim()
            val salon = Discord.trouverSalon(app, gid, nomSalon)
                ?: return ToolResult(Discord.introuvable("salon", nomSalon, gnom, Discord.salons(app, gid)))
            if (salon.optInt("type") !in setOf(0, 5))
                return ToolResult("Le salon « ${salon.optString("name")} » n'est pas un salon textuel classique ou d'annonces. Les salons vocaux, forums et catégories ne sont pas pris en charge par cet outil.")
            val message = a.optString("message").trim()
            if (message.isBlank()) return ToolResult("Le message à envoyer est vide.")
            if (message.length > 2000) return ToolResult("Le message dépasse la limite Discord de 2 000 caractères.")
            if (!confirm("Envoyer ce message dans #${salon.optString("name")} sur « $gnom » ?\n\n$message", true))
                return ToolResult("Envoi annulé.")
            val sent = Discord.envoyerMessage(app, salon.getString("id"), message)
            ToolResult("✅ Message envoyé dans #${salon.optString("name")} sur « $gnom » (ID ${sent.optString("id")}). Les mentions globales ont été neutralisées.", direct = true)
        }
        "discord_message_modifier", "discord_message_supprimer", "discord_message_epingler", "discord_reaction", "discord_fil_creer" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val nomSalon = a.optString("salon").trim()
            val salon = Discord.trouverSalon(app, gid, nomSalon)
                ?: if (nomSalon.matches(Regex("\\d{17,20}"))) JSONObject().put("id", nomSalon).put("name", "salon $nomSalon").put("type", 0)
                   else return ToolResult(Discord.introuvable("salon", nomSalon, gnom, Discord.salons(app, gid)))
            val messageId = a.optString("message_id").trim()
            if (!messageId.matches(Regex("\\d{17,20}"))) return ToolResult("Identifiant de message Discord invalide.")
            val salonId = salon.getString("id")
            when (name) {
                "discord_message_modifier" -> {
                    val nouveau = a.optString("message").trim()
                    if (nouveau.isBlank() || nouveau.length > 2000) return ToolResult("Le nouveau texte doit contenir de 1 à 2 000 caractères.")
                    if (!confirm("Modifier le message $messageId dans #${salon.optString("name")} sur « $gnom » ?\n\n$nouveau", true)) return ToolResult("Modification annulée.")
                    Discord.modifierMessage(app, salonId, messageId, nouveau)
                    ToolResult("Message $messageId modifié dans #${salon.optString("name")}.", direct = true)
                }
                "discord_message_supprimer" -> {
                    if (!confirm("Supprimer définitivement le message $messageId dans #${salon.optString("name")} sur « $gnom » ? Cette action est irréversible.", true)) return ToolResult("Suppression annulée.")
                    Discord.supprimerMessage(app, salonId, messageId)
                    ToolResult("Message $messageId supprimé de #${salon.optString("name")}.", direct = true)
                }
                "discord_message_epingler" -> {
                    val retirer = a.optString("action").lowercase().contains("des") || a.optString("action").lowercase().contains("retir")
                    if (!confirm("${if (retirer) "Désépingler" else "Épingler"} le message $messageId dans #${salon.optString("name")} sur « $gnom » ?", true)) return ToolResult("Action annulée.")
                    Discord.epinglerMessage(app, salonId, messageId, !retirer)
                    ToolResult("Message ${if (retirer) "désépinglé" else "épinglé"} dans #${salon.optString("name")}.", direct = true)
                }
                "discord_reaction" -> {
                    val emoji = a.optString("emoji").trim()
                    if (emoji.isBlank()) return ToolResult("Emoji manquant.")
                    val retirer = a.optString("action").lowercase().startsWith("retir")
                    if (!confirm("${if (retirer) "Retirer" else "Ajouter"} la réaction $emoji au message $messageId dans #${salon.optString("name")} ?", true)) return ToolResult("Action annulée.")
                    Discord.reaction(app, salonId, messageId, emoji, !retirer)
                    ToolResult("Réaction $emoji ${if (retirer) "retirée" else "ajoutée"} au message.", direct = true)
                }
                else -> {
                    if (salon.optInt("type") !in setOf(0, 5)) return ToolResult("La création d'un fil à partir d'un message est disponible dans les salons textuels et d'annonces. Les forums utilisent un flux de création de publication distinct.")
                    val nom = a.optString("nom").trim()
                    if (nom.isBlank()) return ToolResult("Nom du fil manquant.")
                    val demandeArchive = a.optString("archivage_minutes").toIntOrNull() ?: 1440
                    val archive = listOf(60, 1440, 4320, 10080).minBy { kotlin.math.abs(it - demandeArchive) }
                    if (!confirm("Créer le fil « $nom » à partir du message $messageId dans #${salon.optString("name")} sur « $gnom » (archivage après $archive minutes) ?", true)) return ToolResult("Création du fil annulée.")
                    val fil = Discord.creerFilDepuisMessage(app, salonId, messageId, nom, archive)
                    ToolResult("Fil « ${fil.optString("name", nom)} » créé (ID ${fil.optString("id")}).", direct = true)
                }
            }
        }
        "discord_membre_info" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val m = Discord.trouverMembre(app, gid, a.optString("membre")) ?: return ToolResult("Membre « ${a.optString("membre")} » introuvable sur « $gnom ».")
            val u = m.optJSONObject("user") ?: JSONObject()
            val uid = u.optString("id")
            val rolesIds = m.optJSONArray("roles") ?: JSONArray()
            val roles = Discord.roles(app, gid)
            val nomsRoles = (0 until rolesIds.length()).mapNotNull { index ->
                (0 until roles.length()).map { roles.getJSONObject(it) }.firstOrNull { it.optString("id") == rolesIds.optString(index) }?.optString("name")
            }
            val pseudo = m.optString("nick").ifBlank { u.optString("global_name").ifBlank { u.optString("username") } }
            val lignes = listOf("# Membre Discord — $pseudo", "Serveur : $gnom", "ID : $uid",
                "Nom du compte : ${u.optString("username")}", "Compte bot : ${u.optBoolean("bot")}",
                "Arrivée : ${m.optString("joined_at").ifBlank { "non disponible" }}",
                "Rôles : ${nomsRoles.joinToString(", ").ifBlank { "aucun rôle attribué" }}",
                "Timeout jusqu'au : ${m.optString("communication_disabled_until").ifBlank { "aucun" }}")
            ToolResult(lignes.joinToString("\n"), direct = true)
        }

        "discord_roles" -> {
            val gid = guilde(a)
            val arr = Discord.roles(app, gid)
            ToolResult("# 🎭 Rôles (${arr.length()})\n" + (0 until arr.length()).sortedByDescending { arr.getJSONObject(it).optInt("position") }
                .joinToString("\n") { i ->
                    val r = arr.getJSONObject(i)
                    val perms = Discord.Perm.noms(r.optString("permissions", "0").toLongOrNull() ?: 0L)
                    val admin = (r.optString("permissions", "0").toLongOrNull() ?: 0L) and Discord.Perm.ADMINISTRATOR != 0L
                    "- **${Discord.affiche(r.optString("name"))}** · #%06X".format(r.optInt("color")) + (if (admin) " · 👑 administrateur" else if (perms.isNotEmpty()) " · ${perms.size} permission(s)" else " · aucune permission")
                }, direct = true)
        }
        "discord_role_creer" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val nom = a.optString("nom").trim()
            if (nom.isBlank()) return ToolResult("Nom du rôle manquant.")
            val perms = a.optString("permissions").let { if (it.isBlank()) null else Discord.Perm.parseStrict(it) }
            val admin = perms != null && (perms and Discord.Perm.ADMINISTRATOR) != 0L
            if (!confirm("Créer le rôle « $nom » sur le serveur « $gnom »" + (if (admin) " avec TOUTES les permissions, y compris Administrateur (ignore toujours les restrictions de salon)" else "") + " ?", true))
                return ToolResult("Refusé par l'utilisateur.")
            val r = Discord.roleCreer(app, gid, nom, a.optString("couleur").let { couleurHex(it) }, perms,
                a.optString("affiche_separement").lowercase().takeIf { it.isNotBlank() }?.let { it in setOf("true", "oui", "1") },
                a.optString("mentionnable").lowercase().takeIf { it.isNotBlank() }?.let { it in setOf("true", "oui", "1") })
            ToolResult("✅ Rôle « ${r.optString("name")} » créé sur le serveur « $gnom »." + (perms?.let { " Permissions : ${Discord.Perm.noms(it).joinToString(", ").ifBlank { "aucune" }}." } ?: "") +
                "\n⚠️ N'oublie pas de placer ce rôle au-dessus, dans la hiérarchie du serveur, de tous les rôles qu'il doit dépasser (Discord ne le fait pas automatiquement)." +
                "\nℹ️ S'il n'a encore aucun membre, il n'apparaîtra pas dans la liste des membres à droite (même avec « afficher séparément ») — il est bien visible dans Paramètres du serveur › Rôles.", direct = true)
        }
        "discord_role_modifier" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val role = Discord.trouverRole(app, gid, a.optString("role")) ?: return ToolResult(Discord.introuvable("rôle", a.optString("role"), gnom, Discord.roles(app, gid)))
            // « ajoute / retire tel droit » : les autres permissions du rôle sont gardées.
            val plus = a.optString("ajouter_permissions").trim(); val moins = a.optString("retirer_permissions").trim()
            val perms = a.optString("permissions").let { if (it.isBlank()) null else Discord.Perm.parseStrict(it) }
                ?: if (plus.isNotBlank() || moins.isNotBlank()) {
                    val actuel = role.optString("permissions", "0").toLongOrNull() ?: 0L
                    (actuel or Discord.Perm.parseStrict(plus)) and Discord.Perm.parseStrict(moins).inv()
                } else null
            val admin = perms != null && (perms and Discord.Perm.ADMINISTRATOR) != 0L
            val detail = listOfNotNull(plus.takeIf { it.isNotBlank() }?.let { "ajout : $it" }, moins.takeIf { it.isNotBlank() }?.let { "retrait : $it" }).joinToString(" ; ")
            if (!confirm("Modifier le rôle « ${role.optString("name")} » sur le serveur « $gnom »" + (if (detail.isNotBlank()) " ($detail)" else "") + (if (admin) " (ajout d'Administrateur : ignorera alors toujours les restrictions de salon)" else "") + " ?", true))
                return ToolResult("Refusé par l'utilisateur.")
            val r = Discord.roleModifier(app, gid, role.getString("id"), a.optString("nouveau_nom").trim().ifBlank { null },
                a.optString("couleur").let { couleurHex(it) }, perms,
                a.optString("affiche_separement").lowercase().takeIf { it.isNotBlank() }?.let { it in setOf("true", "oui", "1") },
                a.optString("mentionnable").lowercase().takeIf { it.isNotBlank() }?.let { it in setOf("true", "oui", "1") })
            ToolResult("✅ Rôle « ${r.optString("name")} » modifié sur le serveur « $gnom ».", direct = true)
        }
        "discord_role_supprimer" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val role = Discord.trouverRole(app, gid, a.optString("role")) ?: return ToolResult(Discord.introuvable("rôle", a.optString("role"), gnom, Discord.roles(app, gid)))
            if (!confirm("Supprimer définitivement le rôle « ${role.optString("name")} » sur le serveur « $gnom » ?", true)) return ToolResult("Refusé par l'utilisateur.")
            Discord.roleSupprimer(app, gid, role.getString("id"))
            ToolResult("🗑️ Rôle « ${role.optString("name")} » supprimé sur le serveur « $gnom ».", direct = true)
        }
        "discord_role_position" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val role = Discord.trouverRole(app, gid, a.optString("role")) ?: return ToolResult(Discord.introuvable("rôle", a.optString("role"), gnom, Discord.roles(app, gid)))
            val posArg = a.optString("position").trim().lowercase()
            val maxBot = Discord.rolePositionMaxBot(app, gid)
            val auMaximum = posArg.contains("haut") || posArg.contains("maximum") || posArg.contains("max")
            val position = when {
                auMaximum -> maxOf(1, maxBot - 1)
                posArg.contains("bas") || posArg.contains("minimum") || posArg.contains("min") -> 1
                else -> posArg.toIntOrNull() ?: return ToolResult("Position invalide : « ${a.optString("position")} » (attendu : « haut », « bas », ou un nombre).")
            }
            if (!confirm("Déplacer le rôle « ${role.optString("name")} » en position $position sur le serveur « $gnom » ?", true)) return ToolResult("Refusé par l'utilisateur.")
            Discord.roleDeplacer(app, gid, role.getString("id"), position)
            ToolResult("✅ Rôle « ${role.optString("name")} » déplacé en position $position sur « $gnom »." +
                (if (auMaximum) " C'est le plus haut possible : Discord ne permet jamais de dépasser le rôle le plus haut que JARVIS possède lui-même sur ce serveur." else ""), direct = true)
        }
        "discord_membre_timeout", "discord_membre_expulser", "discord_membre_bannir", "discord_membre_pseudo" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val m = Discord.trouverMembre(app, gid, a.optString("membre")) ?: return ToolResult("Membre « ${a.optString("membre")} » introuvable sur « $gnom ».")
            val u = m.optJSONObject("user") ?: JSONObject()
            val uid = u.optString("id")
            val pseudo = m.optString("nick").ifBlank { u.optString("global_name").ifBlank { u.optString("username").ifBlank { uid } } }
            val infosGuilde = Discord.infosGuilde(app, gid)
            if (uid == infosGuilde.optString("owner_id")) return ToolResult("Action refusée : le propriétaire du serveur ne peut pas être modéré par ce bot.")
            if (uid == Discord.moi(app).optString("id")) return ToolResult("Action refusée : le bot ne peut pas se modérer lui-même.")
            val raison = a.optString("raison").trim().ifBlank { "Action demandée depuis Jarvis" }
            when (name) {
                "discord_membre_timeout" -> {
                    val retirer = a.optString("action").lowercase().startsWith("retir") || a.optString("duree_minutes").toIntOrNull() == 0
                    if (retirer) {
                        if (!confirm("Retirer le timeout de $pseudo sur « $gnom » ?", true)) return ToolResult("Action annulée.")
                        Discord.timeoutMembre(app, gid, uid, null, raison)
                        ToolResult("Timeout retiré pour $pseudo sur « $gnom ».", direct = true)
                    } else {
                        val minutes = a.optString("duree_minutes").toIntOrNull() ?: return ToolResult("Précise une durée en minutes (1 à 40 320, soit 28 jours maximum).")
                        if (minutes !in 1..40320) return ToolResult("Durée invalide : un timeout Discord est limité à 28 jours (40 320 minutes).")
                        val fin = java.time.Instant.now().plusSeconds(minutes * 60L).toString()
                        if (!confirm("Appliquer un timeout de $minutes minute(s) à $pseudo sur « $gnom » ?\nRaison : $raison", true)) return ToolResult("Action annulée.")
                        Discord.timeoutMembre(app, gid, uid, fin, raison)
                        ToolResult("Timeout appliqué à $pseudo jusqu'au $fin.", direct = true)
                    }
                }
                "discord_membre_expulser" -> {
                    if (!confirm("Expulser $pseudo du serveur « $gnom » ? La personne pourra revenir avec une invitation.\nRaison : $raison", true)) return ToolResult("Expulsion annulée.")
                    Discord.expulserMembre(app, gid, uid, raison)
                    ToolResult("$pseudo a été expulsé de « $gnom ».", direct = true)
                }
                "discord_membre_bannir" -> {
                    val heures = a.optString("supprimer_messages_heures").toIntOrNull() ?: 0
                    if (heures !in 0..168) return ToolResult("La suppression de messages doit être comprise entre 0 et 168 heures.")
                    if (!confirm("Bannir définitivement $pseudo du serveur « $gnom » ?\nSuppression des messages des $heures dernière(s) heure(s).\nRaison : $raison", true)) return ToolResult("Bannissement annulé.")
                    Discord.bannirMembre(app, gid, uid, raison, heures * 3600)
                    ToolResult("$pseudo a été banni de « $gnom ».", direct = true)
                }
                else -> {
                    val nouveau = a.optString("pseudo").trim()
                    if (nouveau.length > 32) return ToolResult("Le pseudo ne peut pas dépasser 32 caractères.")
                    if (!confirm("Changer le pseudo de $pseudo en « ${nouveau.ifBlank { "(réinitialiser)" }} » sur « $gnom » ?", true)) return ToolResult("Modification annulée.")
                    Discord.modifierPseudoMembre(app, gid, uid, nouveau)
                    ToolResult("Pseudo de $pseudo mis à jour sur « $gnom ».", direct = true)
                }
            }
        }

        "discord_membre_timeout", "discord_membre_expulser", "discord_membre_bannir", "discord_membre_pseudo" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val m = Discord.trouverMembre(app, gid, a.optString("membre")) ?: return ToolResult("Membre « ${a.optString("membre")} » introuvable sur « $gnom ».")
            val u = m.optJSONObject("user") ?: JSONObject()
            val uid = u.optString("id")
            val pseudo = m.optString("nick").ifBlank { u.optString("global_name").ifBlank { u.optString("username").ifBlank { uid } } }
            if (uid == g.optString("owner_id")) return ToolResult("Action refusée : le propriétaire du serveur ne peut pas être modéré par ce bot.")
            if (uid == Discord.moi(app).optString("id")) return ToolResult("Action refusée : le bot ne peut pas se modérer lui-même.")
            val raison = a.optString("raison").trim().ifBlank { "Action demandée depuis Jarvis" }
            when (name) {
                "discord_membre_timeout" -> {
                    val retirer = a.optString("action").lowercase().startsWith("retir") || a.optString("duree_minutes").toIntOrNull() == 0
                    if (retirer) {
                        if (!confirm("Retirer le timeout de $pseudo sur « $gnom » ?", true)) return ToolResult("Action annulée.")
                        Discord.timeoutMembre(app, gid, uid, null, raison)
                        ToolResult("Timeout retiré pour $pseudo sur « $gnom ».", direct = true)
                    } else {
                        val minutes = a.optString("duree_minutes").toIntOrNull() ?: return ToolResult("Précise une durée en minutes (1 à 40 320, soit 28 jours maximum).")
                        if (minutes !in 1..40320) return ToolResult("Durée invalide : un timeout Discord est limité à 28 jours (40 320 minutes).")
                        val fin = java.time.Instant.now().plusSeconds(minutes * 60L).toString()
                        if (!confirm("Appliquer un timeout de $minutes minute(s) à $pseudo sur « $gnom » ?\nRaison : $raison", true)) return ToolResult("Action annulée.")
                        Discord.timeoutMembre(app, gid, uid, fin, raison)
                        ToolResult("Timeout appliqué à $pseudo jusqu'au $fin.", direct = true)
                    }
                }
                "discord_membre_expulser" -> {
                    if (!confirm("Expulser $pseudo du serveur « $gnom » ? La personne pourra revenir avec une invitation.\nRaison : $raison", true)) return ToolResult("Expulsion annulée.")
                    Discord.expulserMembre(app, gid, uid, raison)
                    ToolResult("$pseudo a été expulsé de « $gnom ».", direct = true)
                }
                "discord_membre_bannir" -> {
                    val heures = a.optString("supprimer_messages_heures").toIntOrNull() ?: 0
                    if (heures !in 0..168) return ToolResult("La suppression de messages doit être comprise entre 0 et 168 heures.")
                    if (!confirm("Bannir définitivement $pseudo du serveur « $gnom » ?\nSuppression des messages des $heures dernière(s) heure(s).\nRaison : $raison", true)) return ToolResult("Bannissement annulé.")
                    Discord.bannirMembre(app, gid, uid, raison, heures * 3600)
                    ToolResult("$pseudo a été banni de « $gnom ».", direct = true)
                }
                else -> {
                    val nouveau = a.optString("pseudo").trim()
                    if (nouveau.length > 32) return ToolResult("Le pseudo ne peut pas dépasser 32 caractères.")
                    if (!confirm("Changer le pseudo de $pseudo en « ${nouveau.ifBlank { "(réinitialiser)" }} » sur « $gnom » ?", true)) return ToolResult("Modification annulée.")
                    Discord.modifierPseudoMembre(app, gid, uid, nouveau)
                    ToolResult("Pseudo de $pseudo mis à jour sur « $gnom ».", direct = true)
                }
            }
        }

        "discord_role_attribuer" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val role = Discord.trouverRole(app, gid, a.optString("role")) ?: return ToolResult(Discord.introuvable("rôle", a.optString("role"), gnom, Discord.roles(app, gid)))
            val m = Discord.trouverMembre(app, gid, a.optString("membre")) ?: return ToolResult("Membre « ${a.optString("membre")} » introuvable sur le serveur « $gnom ».")
            val userId = m.getJSONObject("user").getString("id")
            val pseudo = m.optString("nick").ifBlank { m.getJSONObject("user").optString("username") }
            val retirer = a.optString("action").lowercase().startsWith("retir")
            if (!confirm("${if (retirer) "Retirer" else "Donner"} le rôle « ${role.optString("name")} » à $pseudo sur le serveur « $gnom » ?", true)) return ToolResult("Refusé par l'utilisateur.")
            if (retirer) Discord.roleRetirer(app, gid, userId, role.getString("id")) else Discord.roleAttribuer(app, gid, userId, role.getString("id"))
            ToolResult(if (retirer) "✅ Rôle « ${role.optString("name")} » retiré à $pseudo sur « $gnom »." else "✅ Rôle « ${role.optString("name")} » donné à $pseudo sur « $gnom ».", direct = true)
        }
        "discord_salon_permission" -> {
            val gid = guilde(a)
            val roleArg = a.optString("role").trim(); val membreArg = a.optString("membre").trim()
            val (cibleId, cibleNom, type) = when {
                roleArg.isNotBlank() -> {
                    val r = Discord.trouverRole(app, gid, roleArg) ?: return ToolResult("Rôle « $roleArg » introuvable. Rôles : " + Discord.noms(Discord.roles(app, gid)) + ".")
                    Triple(r.getString("id"), r.optString("name"), 0)
                }
                membreArg.isNotBlank() -> {
                    val m = Discord.trouverMembre(app, gid, membreArg) ?: return ToolResult("Membre « $membreArg » introuvable.")
                    Triple(m.getJSONObject("user").getString("id"), m.optString("nick").ifBlank { m.getJSONObject("user").optString("username") }, 1)
                }
                else -> return ToolResult("Précise un rôle ou un membre.")
            }
            val acces = a.optString("acces").trim().lowercase()
            val autoriserBrut = a.optString("autoriser").trim()
            val refuserBrut = a.optString("refuser").trim()
            if (acces.isBlank() && autoriserBrut.isBlank() && refuserBrut.isBlank())
                return ToolResult("Précise un profil dans acces, ou les droits à régler dans autoriser et/ou refuser.")
            fun masquePermissions(brut: String): Long {
                if (brut.isBlank()) return 0L
                val dits = brut.split(',', ';', '|').map { it.trim() }.filter { it.isNotBlank() }
                // Permission comprise même formulée librement (« envoyer des messages », « voir le salon »…).
                val noms = dits.map { it to Discord.Perm.trouver(it) }
                val inconnus = noms.filter { it.second == null || it.second == "administrateur" }.map { it.first }
                if (inconnus.isNotEmpty()) throw IllegalArgumentException("Droits Discord inconnus/non configurables en salon : ${inconnus.joinToString(", ")}. Noms possibles : " + Discord.Perm.NOMS.keys.filter { it != "administrateur" }.joinToString(", ") + ".")
                return noms.fold(0L) { bits, (_, nom) -> bits or Discord.Perm.NOMS.getValue(nom!!) }
            }
            val allowBits = masquePermissions(autoriserBrut)
            val denyBits = masquePermissions(refuserBrut)
            if ((allowBits and denyBits) != 0L)
                return ToolResult("Un même droit ne peut pas être autorisé et refusé simultanément dans le même salon.")
            val resumeAcces = if (autoriserBrut.isNotBlank() || refuserBrut.isNotBlank())
                "autoriser=$autoriserBrut ; refuser=$refuserBrut" else acces
            // Autoriser/refuser sont des overrides ciblés : les droits non cités restent hérités du serveur.
            // L'administrateur ne peut pas être attribué par un override de salon dans Discord.
            val toutSaufVoir = Discord.Perm.TOUT_SAUF_ADMINISTRATEUR and Discord.Perm.VIEW_CHANNEL.inv()
            fun appliquer(channel: JSONObject) {
                val channelId = channel.getString("id")
                if (autoriserBrut.isNotBlank() || refuserBrut.isNotBlank()) {
                    // L'API PUT remplace tout l'override : on reprend les bits existants de l'inventaire
                    // du salon pour préserver chaque droit qui n'a pas été explicitement cité.
                    val overwrites = channel.optJSONArray("permission_overwrites") ?: JSONArray()
                    val actuel = (0 until overwrites.length()).mapNotNull { overwrites.optJSONObject(it) }
                        .firstOrNull { it.optString("id") == cibleId && it.optInt("type", -1) == type }
                    val ancienAllow = actuel?.optString("allow")?.toLongOrNull() ?: 0L
                    val ancienDeny = actuel?.optString("deny")?.toLongOrNull() ?: 0L
                    val nouveauAllow = (ancienAllow and denyBits.inv()) or allowBits
                    val nouveauDeny = (ancienDeny and allowBits.inv()) or denyBits
                    Discord.salonPermission(app, channelId, cibleId, type, nouveauAllow, nouveauDeny)
                } else when {
                    acces.contains("seulement") || acces.contains("uniquement") ->
                        Discord.salonPermission(app, channelId, cibleId, type, Discord.Perm.VIEW_CHANNEL, toutSaufVoir)
                    acces.contains("toutes") || acces.contains("tout") ->
                        Discord.salonPermission(app, channelId, cibleId, type, Discord.Perm.TOUT_SAUF_ADMINISTRATEUR, 0L)
                    acces.contains("refuser") -> Discord.salonPermission(app, channelId, cibleId, type, 0L, Discord.Perm.VIEW_CHANNEL)
                    acces.contains("defaut") || acces.contains("default") -> Discord.salonPermissionSupprimer(app, channelId, cibleId)
                    else -> Discord.salonPermission(app, channelId, cibleId, type, Discord.Perm.VIEW_CHANNEL, 0L)
                }
            }
            if (a.optString("tous_les_salons").equals("true", true)) {
                val arr = Discord.salons(app, gid)
                val ids = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optInt("type") != 4 } // on exclut les catégories, inutiles ici
                if (!confirm("Modifier l'accès des ${ids.size} salon(s) du serveur pour $cibleNom ($resumeAcces) ?", true)) return ToolResult("Refusé par l'utilisateur.")
                var erreurs = 0
                ids.forEach { c -> runCatching { appliquer(c) }.onFailure { erreurs++ } }
                ToolResult("✅ Accès mis à jour sur ${ids.size - erreurs}/${ids.size} salon(s) pour $cibleNom." + (if (erreurs > 0) " ($erreurs échec(s), probablement un salon où le bot n'a pas la main)." else ""), direct = true)
            } else if (a.optString("salon").isBlank() && a.optString("categorie").isNotBlank()) {
                // Permissions d'une CATÉGORIE, puis (si demandé) reprises par tous ses salons.
                val cat = Discord.trouverCategorie(app, gid, a.optString("categorie")) ?: return ToolResult("Catégorie « ${a.optString("categorie")} » introuvable.")
                val sync = vrai(a.optString("synchroniser_salons"))
                val enfants = if (sync) Discord.salons(app, gid).let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }.filter { it.optString("parent_id") == cat.optString("id") } else emptyList()
                if (!confirm("Modifier l'accès de la catégorie « ${cat.optString("name")} » pour $cibleNom ($resumeAcces)" + (if (sync) " puis synchroniser ses ${enfants.size} salon(s) sur elle" else "") + " ?", true)) return ToolResult("Refusé par l'utilisateur.")
                appliquer(cat)
                var echecs = 0
                if (sync) {
                    val ow = Discord.salons(app, gid).let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }.firstOrNull { it.optString("id") == cat.optString("id") }
                        ?.optJSONArray("permission_overwrites") ?: JSONArray()
                    enfants.forEach { c -> runCatching { Discord.salonModifier(app, c.getString("id"), JSONObject().put("permission_overwrites", ow), "Synchronisation avec la catégorie") }.onFailure { echecs++ } }
                }
                ToolResult("✅ Accès de la catégorie « ${cat.optString("name")} » mis à jour pour $cibleNom." +
                    (if (sync) " ${enfants.size - echecs}/${enfants.size} salon(s) synchronisé(s) sur elle." else " Ses salons synchronisés suivent ; les autres gardent leurs propres réglages."), direct = true)
            } else {
                val salon = Discord.trouverSalon(app, gid, a.optString("salon")) ?: return ToolResult("Salon « ${a.optString("salon")} » introuvable. Précise un nom de salon, ou mets tous_les_salons=true pour les appliquer tous d'un coup.")
                if (!confirm("Modifier l'accès de « ${salon.optString("name")} » pour $cibleNom ($resumeAcces) ?", true)) return ToolResult("Refusé par l'utilisateur.")
                appliquer(salon)
                ToolResult("✅ Accès de « ${salon.optString("name")} » mis à jour pour $cibleNom.", direct = true)
            }
        }
        "discord_salon_creer" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val nom = a.optString("nom").trim().take(100)
            if (nom.isBlank()) return ToolResult("Nom du salon manquant.")
            val type = DiscordOrganisation.type(a.optString("type")) ?: return ToolResult("Type « ${a.optString("type")} » inconnu : texte, vocal, categorie, annonces, forum ou scene.")
            val corps = JSONObject().put("name", nom).put("type", type)
            val cat = if (type != DiscordOrganisation.CATEGORIE && a.optString("categorie").isNotBlank())
                Discord.trouverCategorie(app, gid, a.optString("categorie")) ?: return ToolResult("Catégorie « ${a.optString("categorie")} » introuvable sur « $gnom ».") else null
            cat?.let { corps.put("parent_id", it.getString("id")) }
            reglages(a, corps, type)
            // Salon privé : caché à @everyone, visible par les rôles cités et par le bot lui-même.
            val prive = vrai(a.optString("prive"))
            val rolesAcces = a.optString("roles_acces").split(',').map { it.trim() }.filter { it.isNotBlank() }
            val nomsRoles = mutableListOf<String>()
            if (prive || rolesAcces.isNotEmpty()) {
                val ow = JSONArray()
                if (prive) ow.put(JSONObject().put("id", gid).put("type", 0).put("allow", "0").put("deny", Discord.Perm.VIEW_CHANNEL.toString()))
                rolesAcces.forEach { r ->
                    val role = Discord.trouverRole(app, gid, r) ?: return ToolResult(Discord.introuvable("rôle", r, gnom, Discord.roles(app, gid)))
                    nomsRoles += role.optString("name")
                    ow.put(JSONObject().put("id", role.getString("id")).put("type", 0).put("allow", Discord.Perm.VIEW_CHANNEL.toString()).put("deny", "0"))
                }
                if (prive) ow.put(JSONObject().put("id", Discord.moi(app).getString("id")).put("type", 1).put("allow", Discord.Perm.VIEW_CHANNEL.toString()).put("deny", "0"))
                corps.put("permission_overwrites", ow)
            } else cat?.optJSONArray("permission_overwrites")?.let { corps.put("permission_overwrites", it) }   // synchronisé avec sa catégorie
            val quoi = (if (type == DiscordOrganisation.CATEGORIE) "la catégorie" else "le salon ${DiscordOrganisation.nomType(type)}") + " « $nom »"
            if (!confirm("Créer $quoi sur « $gnom »" + (cat?.let { " dans « ${it.optString("name")} »" } ?: "") +
                    (if (prive) " — privé" + (if (nomsRoles.isNotEmpty()) ", visible par ${nomsRoles.joinToString(", ")}" else ", visible seulement par les administrateurs") else "") + " ?", true))
                return ToolResult("Création annulée.")
            val cree = Discord.salonCreer(app, gid, corps, "Créé depuis Jarvis")
            val pos = runCatching { ancre(gid, a.optString("position")) }.getOrElse { return ToolResult("✅ Créé (ID ${cree.optString("id")}), mais ${it.message}") }
            if (pos != null) runCatching {
                Discord.salonsPositions(app, gid, DiscordOrganisation.deplacement(tous(gid), cree.getString("id"), cat?.getString("id"), pos), "Position choisie depuis Jarvis")
            }.onFailure { return ToolResult("✅ ${quoi.replaceFirstChar { it.uppercase() }} créé (ID ${cree.optString("id")}), mais sa position n'a pas pu être réglée : ${it.message}") }
            ToolResult("✅ ${quoi.replaceFirstChar { it.uppercase() }} créé${if (type == DiscordOrganisation.CATEGORIE) "e" else ""} sur « $gnom »" + (cat?.let { " dans « ${it.optString("name")} »" } ?: "") + " (ID ${cree.optString("id")}).", direct = true)
        }
        "discord_salon_modifier" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val salonArg = a.optString("salon").trim(); val catArg = a.optString("categorie_cible").trim()
            if (salonArg.isBlank() && catArg.isBlank()) return ToolResult("Précise le salon (ou la catégorie) à modifier.")
            val c = cible(gid, salonArg, catArg) ?: return ToolResult(Discord.introuvable(if (salonArg.isNotBlank()) "salon" else "catégorie", salonArg.ifBlank { catArg }, gnom, Discord.salons(app, gid)))
            val id = c.getString("id"); val type = c.optInt("type"); val estCat = type == DiscordOrganisation.CATEGORIE
            val corps = JSONObject()
            a.optString("nouveau_nom").trim().takeIf { it.isNotBlank() }?.let { corps.put("name", it.take(100)) }
            if (!estCat) reglages(a, corps, type)
            val versArg = a.optString("deplacer_vers").trim()
            val versAucune = Regex("^(aucune?|sans( categorie)?|hors categorie|racine|-)$").matches(fr.dd.jarvis.societe.core.normaliser(versArg))
            val vers = if (estCat || versArg.isBlank() || versAucune) null else Discord.trouverCategorie(app, gid, versArg)
                ?: return ToolResult("Catégorie de destination « $versArg » introuvable sur « $gnom ».")
            val parentActuel = c.optString("parent_id").takeIf { it.isNotBlank() && it != "null" }
            val nouveauParent = when { estCat -> null; versAucune -> null; vers != null -> vers.getString("id"); else -> parentActuel }
            val sync = vrai(a.optString("synchroniser"))
            val pos = ancre(gid, a.optString("position"))
            val bouge = !estCat && nouveauParent != parentActuel
            if (corps.length() == 0 && !bouge && pos == null && !sync) return ToolResult("Rien à modifier : précise un nouveau nom, une destination, une position ou un réglage.")
            val resume = listOfNotNull(
                corps.optString("name").takeIf { it.isNotBlank() }?.let { "renommer en « $it »" },
                if (bouge) "déplacer vers " + (vers?.let { "« ${it.optString("name")} »" } ?: "hors catégorie") else null,
                pos?.let { "position : ${a.optString("position")}" }, if (sync) "synchroniser ses permissions sur la catégorie" else null,
                corps.keys().asSequence().filter { it != "name" }.toList().takeIf { it.isNotEmpty() }?.let { "réglages : ${it.joinToString(", ")}" }).joinToString(" ; ")
            if (!confirm("Modifier ${if (estCat) "la catégorie" else "le salon"} « ${c.optString("name")} » sur « $gnom » : $resume ?", true)) return ToolResult("Modification annulée.")
            if (corps.length() > 0) Discord.salonModifier(app, id, corps, "Modifié depuis Jarvis")
            // Déplacement / position : sans position dite, le salon déplacé va en bas de sa nouvelle catégorie.
            if (bouge || pos != null) Discord.salonsPositions(app, gid,
                DiscordOrganisation.deplacement(tous(gid), id, nouveauParent, pos ?: DiscordOrganisation.Ancre.Bas, synchroniser = sync && bouge), "Déplacé depuis Jarvis")
            // Synchroniser sans changer de catégorie : il reprend les permissions de sa catégorie actuelle.
            if (sync && !bouge && !estCat) {
                val parent = nouveauParent?.let { p -> tous(gid).firstOrNull { it.optString("id") == p } }
                    ?: return ToolResult("Le salon n'est dans aucune catégorie : rien avec quoi le synchroniser.")
                Discord.salonModifier(app, id, JSONObject().put("permission_overwrites", parent.optJSONArray("permission_overwrites") ?: JSONArray()), "Synchronisé depuis Jarvis")
            }
            ToolResult("✅ ${if (estCat) "Catégorie" else "Salon"} « ${corps.optString("name").ifBlank { c.optString("name") }} » : $resume.", direct = true)
        }
        "discord_salon_supprimer" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val salonArg = a.optString("salon").trim(); val catArg = a.optString("categorie").trim()
            if (salonArg.isBlank() && catArg.isBlank()) return ToolResult("Précise le salon (ou la catégorie) à supprimer.")
            val c = cible(gid, salonArg, catArg) ?: return ToolResult(Discord.introuvable(if (salonArg.isNotBlank()) "salon" else "catégorie", salonArg.ifBlank { catArg }, gnom, Discord.salons(app, gid)))
            val estCat = c.optInt("type") == DiscordOrganisation.CATEGORIE
            val enfants = if (estCat) tous(gid).filter { it.optString("parent_id") == c.optString("id") } else emptyList()
            val avecContenu = estCat && vrai(a.optString("avec_contenu"))
            val question = if (!estCat) "Supprimer DÉFINITIVEMENT le salon « ${c.optString("name")} » (${DiscordOrganisation.nomType(c.optInt("type"))}) sur « $gnom », avec tous ses messages ?"
                else if (avecContenu) "Supprimer DÉFINITIVEMENT la catégorie « ${c.optString("name")} » ET ses ${enfants.size} salon(s) (${enfants.joinToString(", ") { "#" + it.optString("name") }}) sur « $gnom », avec tous leurs messages ?"
                else "Supprimer la catégorie « ${c.optString("name")} » sur « $gnom » ? Ses ${enfants.size} salon(s) sont gardés, hors catégorie."
            if (!confirm(question, true)) return ToolResult("Suppression annulée.")
            var echecs = 0
            if (avecContenu) enfants.forEach { e -> runCatching { Discord.salonSupprimer(app, e.getString("id"), "Supprimé depuis Jarvis") }.onFailure { echecs++ } }
            Discord.salonSupprimer(app, c.getString("id"), "Supprimé depuis Jarvis")
            ToolResult("🗑️ ${if (estCat) "Catégorie" else "Salon"} « ${c.optString("name")} » supprimé${if (estCat) "e" else ""} sur « $gnom »." +
                (if (avecContenu) " ${enfants.size - echecs}/${enfants.size} salon(s) supprimé(s) avec elle." else if (estCat && enfants.isNotEmpty()) " Ses ${enfants.size} salon(s) sont maintenant hors catégorie." else ""), direct = true)
        }
        "discord_salons_reorganiser" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val plan = DiscordOrganisation.plan(a.optString("plan"))
            if (plan.isEmpty()) return ToolResult("Plan vide : une catégorie par ligne, « Catégorie : salon1, salon2 ».")
            val creer = vrai(a.optString("creer_categories"))
            var l = tous(gid)
            // Tout est résolu AVANT de toucher au serveur : un nom introuvable = rien n'est modifié.
            val manquantes = plan.mapNotNull { it.categorie }.distinct().filter { Discord.trouverCategorie(app, gid, it) == null }
            if (manquantes.isNotEmpty() && !creer) return ToolResult("Catégories introuvables : ${manquantes.joinToString(", ") { "« $it »" }}. Rien n'a été modifié. Corrige leur nom, ou relance avec creer_categories=true pour les créer.")
            val introuvables = mutableListOf<String>()
            val salonsIds = plan.map { ligne -> ligne.salons.mapNotNull { n -> Discord.trouverSalon(app, gid, n)?.getString("id") ?: run { introuvables += n; null } } }
            if (introuvables.isNotEmpty()) return ToolResult("Salons introuvables : ${introuvables.joinToString(", ") { "« $it »" }}. Rien n'a été modifié. Salons existants : " + Discord.noms(Discord.salons(app, gid), sansCategories = true) + ".")
            val doublons = salonsIds.flatten().groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            if (doublons.isNotEmpty()) return ToolResult("Un même salon est cité plusieurs fois dans le plan (${doublons.mapNotNull { d -> l.firstOrNull { it.optString("id") == d }?.optString("name") }.joinToString(", ")}). Rien n'a été modifié.")
            // Aperçu et confirmation (catégories à créer comprises).
            val provisoires = manquantes.mapIndexed { i, n -> JSONObject().put("id", "nouvelle$i").put("name", n).put("type", DiscordOrganisation.CATEGORIE).put("position", 999 + i) }
            fun idCat(nom: String?, l: List<JSONObject>, map: Map<String, String>): String? = nom?.let { n -> map[n] ?: Discord.trouverCategorie(app, gid, n)?.getString("id") }
            val provisoireMap = manquantes.mapIndexed { i, n -> n to "nouvelle$i" }.toMap()
            val lignesApercu = plan.mapIndexed { i, ligne -> idCat(ligne.categorie, l, provisoireMap) to salonsIds[i] }
            val apercu = DiscordOrganisation.apercu(l + provisoires, DiscordOrganisation.reorganisation(l + provisoires, lignesApercu))
            val avant = DiscordOrganisation.apercu(l, JSONArray())
            if (!confirm("Réorganiser « $gnom »" + (if (manquantes.isNotEmpty()) " (création de ${manquantes.size} catégorie(s) : ${manquantes.joinToString(", ")})" else "") +
                    " ?\n\nAVANT :\n${avant.take(1500)}\n\nAPRÈS :\n${apercu.take(1500)}", true)) return ToolResult("Réorganisation annulée : rien n'a été modifié.")
            val creees = mutableMapOf<String, String>()
            var appliquee = false
            try {
                manquantes.forEach { n ->
                    creees[n] = Discord.salonCreer(app, gid, JSONObject().put("name", n.take(100)).put("type", DiscordOrganisation.CATEGORIE), "Réorganisation depuis Jarvis").getString("id")
                }
                l = tous(gid)
                val lignes = plan.mapIndexed { i, ligne -> idCat(ligne.categorie, l, creees) to salonsIds[i] }
                Discord.salonsPositions(app, gid, DiscordOrganisation.reorganisation(l, lignes, vrai(a.optString("synchroniser"))), "Réorganisation depuis Jarvis")
                appliquee = true
                val final = tous(gid)
                ToolResult("✅ Serveur « $gnom » réorganisé.\n\n" + DiscordOrganisation.apercu(final, JSONArray()), direct = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Une annulation de coroutine doit rester une annulation : ne pas la convertir
                // en échec métier ni lancer le nettoyage comme si l'opération avait échoué normalement.
                throw e
            } catch (e: Throwable) {
                if (appliquee) {
                    ToolResult("⚠️ Discord a accepté la réorganisation de « $gnom », mais Jarvis n’a pas pu relire la structure pour la vérifier : ${e.message ?: e.javaClass.simpleName}. Aucune catégorie n’a été supprimée.", direct = true)
                } else {
                    var nettoyees = 0
                    val conservees = mutableListOf<String>()
                    val etatActuel = runCatching { tous(gid) }.getOrNull()
                    creees.values.toList().asReversed().forEach { id ->
                        // Ne jamais supprimer une catégorie si Discord a malgré tout déplacé des salons dedans.
                        val occupee = etatActuel?.any { it.optInt("type") != DiscordOrganisation.CATEGORIE && it.optString("parent_id") == id }
                        if (etatActuel == null || occupee == true) conservees += id
                        else runCatching { Discord.salonSupprimer(app, id, "Nettoyage après échec de réorganisation Jarvis") }.onSuccess { nettoyees++ }.onFailure { conservees += id }
                    }
                    ToolResult("❌ Réorganisation non terminée sur « $gnom » : ${e.message ?: e.javaClass.simpleName}. Catégories provisoires vides supprimées : $nettoyees/${creees.size}." +
                        (if (conservees.isNotEmpty()) " Catégories conservées par sécurité (salons déplacés ou état non vérifiable) : ${conservees.joinToString(", ")}." else ""), direct = true)
                }
            }
        }
        "discord_salon_permissions_voir" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val c = cible(gid, a.optString("salon").trim(), a.optString("categorie").trim()) ?: return ToolResult("Salon ou catégorie introuvable : précise « salon » ou « categorie ».")
            val roles = Discord.roles(app, gid).let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }.associateBy { it.optString("id") }
            val ow = c.optJSONArray("permission_overwrites") ?: JSONArray()
            val lignes = mutableListOf("# 🔐 Permissions de « ${Discord.affiche(c.optString("name"))} » (${DiscordOrganisation.nomType(c.optInt("type"))}, $gnom)")
            val parentId = c.optString("parent_id").takeIf { it.isNotBlank() && it != "null" }
            if (parentId != null) {
                val parent = tous(gid).firstOrNull { it.optString("id") == parentId }
                val synchro = parent != null && (parent.optJSONArray("permission_overwrites") ?: JSONArray()).let { po ->
                    fun cle(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("id") + ":" + it.optString("allow") + ":" + it.optString("deny") }.toSet()
                    cle(po) == cle(ow) }
                lignes += "Catégorie : « ${parent?.optString("name") ?: parentId} » — " + if (synchro) "permissions synchronisées avec elle." else "permissions PROPRES (non synchronisées)."
            }
            if (ow.length() == 0) lignes += "Aucune permission particulière : il suit les rôles du serveur."
            for (i in 0 until ow.length()) {
                val o = ow.getJSONObject(i)
                val qui = if (o.optInt("type") == 0) (if (o.optString("id") == gid) "@everyone" else "rôle « ${roles[o.optString("id")]?.optString("name") ?: o.optString("id")} »")
                    else "membre " + (runCatching { Discord.membre(app, gid, o.optString("id")).let { m -> m.optString("nick").ifBlank { m.optJSONObject("user")?.optString("username").orEmpty() } } }.getOrNull() ?: o.optString("id"))
                val oui = Discord.Perm.noms(o.optString("allow").toLongOrNull() ?: 0L); val non = Discord.Perm.noms(o.optString("deny").toLongOrNull() ?: 0L)
                lignes += "- $qui : " + listOfNotNull(oui.takeIf { it.isNotEmpty() }?.let { "✅ " + it.joinToString(", ") }, non.takeIf { it.isNotEmpty() }?.let { "⛔ " + it.joinToString(", ") }).joinToString(" · ").ifBlank { "(rien)" }
            }
            ToolResult(lignes.joinToString("\n"), direct = true)
        }
        "discord_serveur_modifier" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val corps = JSONObject()
            a.optString("nom").trim().takeIf { it.isNotBlank() }?.let { corps.put("name", it.take(100)) }
            a.optString("description").trim().takeIf { it.isNotBlank() }?.let { corps.put("description", if (it == "-") JSONObject.NULL else it.take(120)) }
            a.optString("verification").trim().takeIf { it.isNotBlank() }?.let { v ->
                corps.put("verification_level", when (fr.dd.jarvis.societe.core.normaliser(v)) { "aucune", "aucun", "0" -> 0; "faible", "basse", "1" -> 1; "moyenne", "moyen", "2" -> 2; "elevee", "haute", "3" -> 3; "maximale", "tres elevee", "max", "4" -> 4
                    else -> return ToolResult("Niveau de vérification « $v » inconnu : aucune, faible, moyenne, elevee, maximale.") })
            }
            a.optString("notifications").trim().takeIf { it.isNotBlank() }?.let { corps.put("default_message_notifications", if (fr.dd.jarvis.societe.core.normaliser(it).contains("mention")) 1 else 0) }
            a.optString("filtre_contenu").trim().takeIf { it.isNotBlank() }?.let { f ->
                val n = fr.dd.jarvis.societe.core.normaliser(f)
                corps.put("explicit_content_filter", when { n.startsWith("desactiv") || n == "aucun" || n == "0" -> 0; n.contains("sans role") || n == "1" -> 1; else -> 2 })
            }
            fun salonOuAucun(cle: String, champ: String): ToolResult? {
                val v = a.optString(cle).trim(); if (v.isBlank()) return null
                if (Regex("^(aucun|aucune|-|rien)$").matches(fr.dd.jarvis.societe.core.normaliser(v))) { corps.put(champ, JSONObject.NULL); return null }
                val c = Discord.trouverSalon(app, gid, v) ?: return ToolResult("Salon « $v » introuvable sur « $gnom ».")
                corps.put(champ, c.getString("id")); return null
            }
            salonOuAucun("salon_systeme", "system_channel_id")?.let { return it }
            salonOuAucun("salon_afk", "afk_channel_id")?.let { return it }
            entier(a.optString("delai_afk_minutes"))?.let { m -> corps.put("afk_timeout", listOf(60, 300, 900, 1800, 3600).minBy { kotlin.math.abs(it - m * 60) }) }
            if (corps.length() == 0) return ToolResult("Rien à modifier : précise le nom, la description, la vérification, les notifications, le filtre, le salon système ou AFK.")
            if (!confirm("Modifier le serveur « $gnom » : ${corps.keys().asSequence().joinToString(", ")} ?", true)) return ToolResult("Modification annulée.")
            val r = Discord.guildeModifier(app, gid, corps, "Modifié depuis Jarvis")
            ToolResult("✅ Serveur « ${r.optString("name", gnom)} » mis à jour (${corps.keys().asSequence().joinToString(", ")}).", direct = true)
        }
        "discord_invitations" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            when (fr.dd.jarvis.societe.core.normaliser(a.optString("action")).ifBlank { "lister" }) {
                "creer", "cree", "nouvelle" -> {
                    val salon = a.optString("salon").trim().takeIf { it.isNotBlank() }?.let { Discord.trouverSalon(app, gid, it) ?: return ToolResult("Salon « $it » introuvable sur « $gnom ».") }
                        ?: tous(gid).filter { it.optInt("type") == 0 }.minByOrNull { it.optInt("position") } ?: return ToolResult("Aucun salon textuel pour l'invitation.")
                    val heures = (entier(a.optString("duree_heures")) ?: 24).coerceIn(0, 168)
                    val usages = (entier(a.optString("utilisations_max")) ?: 0).coerceIn(0, 100)
                    if (!confirm("Créer une invitation vers #${salon.optString("name")} sur « $gnom » (${if (heures == 0) "permanente" else "$heures h"}, ${if (usages == 0) "utilisations illimitées" else "$usages utilisation(s)"}) ?", true)) return ToolResult("Annulé.")
                    val inv = Discord.invitationCreer(app, salon.getString("id"), heures * 3600, usages)
                    ToolResult("✅ Invitation créée : https://discord.gg/${inv.optString("code")}", direct = true)
                }
                "supprimer", "revoquer", "annuler" -> {
                    val code = a.optString("code").trim().substringAfterLast('/')
                    if (code.isBlank()) return ToolResult("Code de l'invitation manquant (liste-les d'abord).")
                    if (!confirm("Supprimer l'invitation $code sur « $gnom » ?", true)) return ToolResult("Annulé.")
                    Discord.invitationSupprimer(app, code)
                    ToolResult("🗑️ Invitation $code supprimée.", direct = true)
                }
                else -> {
                    val arr = Discord.invitations(app, gid)
                    if (arr.length() == 0) return ToolResult("Aucune invitation active sur « $gnom ».", direct = true)
                    ToolResult("# 🔗 Invitations de « $gnom » (${arr.length()})\n" + (0 until arr.length()).joinToString("\n") { i ->
                        val v = arr.getJSONObject(i)
                        "- ${v.optString("code")} → #${v.optJSONObject("channel")?.optString("name").orEmpty()} · par ${v.optJSONObject("inviter")?.optString("username") ?: "?"} · ${v.optInt("uses")}/${v.optInt("max_uses").let { if (it == 0) "∞" else it.toString() }} utilisation(s) · " +
                            (if (v.optInt("max_age") == 0) "permanente" else "expire après ${v.optInt("max_age") / 3600} h")
                    }, direct = true)
                }
            }
        }
        "discord_messages_purger" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val salon = Discord.trouverSalon(app, gid, a.optString("salon")) ?: return ToolResult(Discord.introuvable("salon", a.optString("salon"), gnom, Discord.salons(app, gid)))
            val n = (entier(a.optString("nombre")) ?: return ToolResult("Précise le nombre de messages (1 à 100).")).coerceIn(1, 100)
            val auteur = a.optString("auteur").trim(); val contenant = a.optString("contenant").trim()
            val limite14j = java.time.Instant.now().minus(java.time.Duration.ofDays(14)).plusSeconds(3600)
            val msgs = Discord.messages(app, salon.getString("id"), n).let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }.filter { m ->
                val au = m.optJSONObject("author")?.let { it.optString("global_name") + " " + it.optString("username") }.orEmpty()
                (auteur.isBlank() || au.contains(auteur, true)) && (contenant.isBlank() || m.optString("content").contains(contenant, true)) &&
                    runCatching { java.time.Instant.parse(m.optString("timestamp")).isAfter(limite14j) }.getOrDefault(false)
            }
            if (msgs.isEmpty()) return ToolResult("Aucun message à supprimer (filtres, ou messages de plus de 14 jours que Discord ne laisse pas supprimer en lot).")
            if (!confirm("Supprimer DÉFINITIVEMENT ${msgs.size} message(s) de #${salon.optString("name")} sur « $gnom »" + (if (auteur.isNotBlank()) " (auteur : $auteur)" else "") + (if (contenant.isNotBlank()) " (contenant « $contenant »)" else "") + " ?", true)) return ToolResult("Suppression annulée.")
            Discord.messagesSupprimerLot(app, salon.getString("id"), msgs.map { it.getString("id") })
            ToolResult("🧹 ${msgs.size} message(s) supprimé(s) de #${salon.optString("name")}.", direct = true)
        }
        "discord_journal_audit" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val r = Discord.journalAudit(app, gid, entier(a.optString("limite")) ?: 25)
            val users = (r.optJSONArray("users") ?: JSONArray()).let { u -> (0 until u.length()).map { u.getJSONObject(it) } }.associate { it.optString("id") to it.optString("global_name").ifBlank { it.optString("username") } }
            val entrees = r.optJSONArray("audit_log_entries") ?: JSONArray()
            if (entrees.length() == 0) return ToolResult("Journal d'audit vide sur « $gnom ».", direct = true)
            val actions = mapOf(1 to "a modifié le serveur", 10 to "a créé un salon", 11 to "a modifié un salon", 12 to "a supprimé un salon", 13 to "a ajouté une permission de salon",
                14 to "a modifié une permission de salon", 15 to "a retiré une permission de salon", 20 to "a expulsé", 22 to "a banni", 23 to "a débanni", 24 to "a modifié un membre",
                25 to "a changé les rôles d'un membre", 26 to "a déplacé un membre en vocal", 27 to "a déconnecté un membre du vocal", 28 to "a ajouté un bot", 30 to "a créé un rôle", 31 to "a modifié un rôle",
                32 to "a supprimé un rôle", 40 to "a créé une invitation", 42 to "a supprimé une invitation", 72 to "a supprimé un message", 73 to "a supprimé des messages en lot", 74 to "a épinglé un message",
                75 to "a désépinglé un message", 110 to "a créé un fil", 111 to "a modifié un fil", 112 to "a supprimé un fil")
            val salonsNoms = tous(gid).associate { it.optString("id") to it.optString("name") }
            val rolesNoms = Discord.roles(app, gid).let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }.associate { it.optString("id") to it.optString("name") }
            ToolResult("# 📜 Journal d'audit de « $gnom »\n" + (0 until entrees.length()).joinToString("\n") { i ->
                val e = entrees.getJSONObject(i)
                val quand = runCatching { java.time.Instant.ofEpochMilli((e.optString("id").toLong() shr 22) + 1_420_070_400_000L).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")) }.getOrDefault("")
                val cibleId = e.optString("target_id")
                val cible = salonsNoms[cibleId]?.let { "#$it" } ?: rolesNoms[cibleId]?.let { "@$it" } ?: users[cibleId] ?: (e.optJSONArray("changes")?.let { ch -> (0 until ch.length()).map { ch.getJSONObject(it) }.firstOrNull { it.optString("key") == "name" }?.let { it.optString("old_value").ifBlank { it.optString("new_value") } } }) ?: ""
                "- [$quand] ${users[e.optString("user_id")] ?: "?"} ${actions[e.optInt("action_type")] ?: "action ${e.optInt("action_type")}"}" + (if (cible.isNotBlank()) " : $cible" else "") +
                    (e.optString("reason").takeIf { it.isNotBlank() }?.let { " — « ${java.net.URLDecoder.decode(it, "UTF-8").take(80)} »" } ?: "")
            }, direct = true)
        }
        "discord_fil_gerer" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val filArg = a.optString("fil").trim()
            val fil = if (filArg.matches(Regex("\\d{17,20}"))) JSONObject().put("id", filArg).put("name", "fil $filArg") else Discord.trouverSalon(app, gid, filArg)
                ?: return ToolResult("Fil « $filArg » introuvable : donne son ID (clic droit › Copier l'identifiant).")
            val action = fr.dd.jarvis.societe.core.normaliser(a.optString("action"))
            val corps = JSONObject()
            val libelle = when {
                action.startsWith("desarchiv") -> { corps.put("archived", false); "désarchiver" }
                action.startsWith("archiv") -> { corps.put("archived", true); "archiver" }
                action.startsWith("deverrouill") -> { corps.put("locked", false); "déverrouiller" }
                action.startsWith("verrouill") -> { corps.put("locked", true); "verrouiller" }
                action.startsWith("renomm") -> { val n = a.optString("nom").trim(); if (n.isBlank()) return ToolResult("Nouveau nom manquant."); corps.put("name", n.take(100)); "renommer en « $n »" }
                action.startsWith("supprim") -> "supprimer définitivement"
                else -> return ToolResult("Action « ${a.optString("action")} » inconnue : archiver, desarchiver, verrouiller, deverrouiller, renommer, supprimer.")
            }
            if (!confirm("${libelle.replaceFirstChar { it.uppercase() }} le fil « ${fil.optString("name")} » sur « $gnom » ?", true)) return ToolResult("Annulé.")
            if (corps.length() == 0) Discord.salonSupprimer(app, fil.getString("id"), "Supprimé depuis Jarvis") else Discord.salonModifier(app, fil.getString("id"), corps, "Modifié depuis Jarvis")
            ToolResult("✅ Fil « ${fil.optString("name")} » : $libelle — fait.", direct = true)
        }
        else -> ToolResult("Outil Discord inconnu.")
        }
    }
}
