package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Analyse statique, en lecture seule, de la structure Discord.
 * Les propositions sont des pistes à valider par l'administrateur, jamais des modifications automatiques.
 */
internal object DiscordConseils {
    private fun objets(a: JSONArray): List<JSONObject> =
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun cle(s: String): String = DiscordNoms.cle(s)

    private fun permissions(o: JSONObject): Long = o.optString("permissions").toLongOrNull() ?: 0L

    private fun overrides(a: JSONArray?): Set<String> = objets(a ?: JSONArray()).map {
        "${it.optString("id")}:${it.optString("type")}:${it.optString("allow")}:${it.optString("deny")}"
    }.toSet()

    fun analyser(serveur: String, guildId: String, salonsJson: JSONArray, rolesJson: JSONArray): String {
        val salons = objets(salonsJson)
        val roles = objets(rolesJson)
        val categories = salons.filter { it.optInt("type") == 4 }.sortedBy { it.optInt("position") }
        val canaux = salons.filter { it.optInt("type") != 4 }
        val conseils = mutableListOf<String>()

        val categoriesVides = categories.filter { cat -> canaux.none { it.optString("parent_id") == cat.optString("id") } }
        categories.forEach { cat ->
            val count = canaux.count { it.optString("parent_id") == cat.optString("id") }
            if (count >= 45) conseils += "🟡 Catégorie « ${cat.optString("name")} » proche de la limite Discord de 50 salons ($count actuellement). Prévoir une sous-organisation en plusieurs catégories. Action : discord_salons_reorganiser."
        }
        categoriesVides.forEach {
            conseils += "🟡 Catégorie vide « ${it.optString("name")} » : la supprimer si elle n'a plus d'utilité, ou y déplacer les salons concernés. Action : discord_salon_supprimer / discord_salon_modifier."
        }

        val sansCategorie = canaux.filter { it.optString("parent_id").isBlank() || categories.none { c -> c.optString("id") == it.optString("parent_id") } }
        if (sansCategorie.isNotEmpty()) {
            conseils += "🟡 ${sansCategorie.size} salon(s) hors catégorie : ${sansCategorie.take(8).joinToString { "#" + it.optString("name") }}. Les regrouper par thème si cela rend le serveur plus lisible. Action : discord_salons_reorganiser."
        }

        val doublons = canaux.groupBy { cle(it.optString("name")) }.filter { it.key.isNotBlank() && it.value.size > 1 }
        doublons.values.forEach { groupe ->
            conseils += "🟡 Noms de salons similaires ou identiques : ${groupe.joinToString { "#" + it.optString("name") }}. Renommer pour éviter les confusions. Action : discord_salon_modifier."
        }

        val categoriesById = categories.associateBy { it.optString("id") }
        canaux.filter { it.optString("parent_id").isNotBlank() }.forEach { canal ->
            val cat = categoriesById[canal.optString("parent_id")] ?: return@forEach
            val a = overrides(canal.optJSONArray("permission_overwrites"))
            val b = overrides(cat.optJSONArray("permission_overwrites"))
            if (a != b) {
                conseils += "🔵 #${canal.optString("name")} a des permissions différentes de sa catégorie « ${cat.optString("name")} ». Vérifier si cette exception est voulue ; sinon synchroniser. Action : discord_salon_permissions_voir puis discord_salon_modifier avec synchroniser=true."
            }
        }

        val roleEveryone = roles.firstOrNull { it.optString("id") == guildId }
        if (roleEveryone != null) {
            val p = permissions(roleEveryone)
            val sensibles = listOf(
                Discord.Perm.ADMINISTRATOR to "Administrateur",
                Discord.Perm.MANAGE_GUILD to "Gérer le serveur",
                Discord.Perm.MANAGE_ROLES to "Gérer les rôles",
                Discord.Perm.MANAGE_CHANNELS to "Gérer les salons",
                Discord.Perm.MENTION_EVERYONE to "Mentionner @everyone"
            ).filter { (bit, _) -> p and bit != 0L }
            if (sensibles.isNotEmpty()) {
                conseils += "🔴 @everyone possède des droits étendus : ${sensibles.joinToString { it.second }}. Vérifier si ces droits doivent être réservés à un rôle d'administration. Action : discord_role_modifier."
            }
        }

        val rolesDupliques = roles.filter { it.optString("id") != guildId }.groupBy { cle(it.optString("name")) }.filter { it.key.isNotBlank() && it.value.size > 1 }
        rolesDupliques.values.forEach { groupe ->
            conseils += "🟡 Noms de rôles identiques ou proches : ${groupe.joinToString { "@" + it.optString("name") }}. Renommer pour faciliter l’attribution et l’audit. Action : discord_role_modifier."
        }

        val rolesAdmin = roles.filter { it.optString("id") != guildId && (permissions(it) and Discord.Perm.ADMINISTRATOR) != 0L }
        if (rolesAdmin.isNotEmpty()) {
            conseils += "🔴 ${rolesAdmin.size} rôle(s) disposent de la permission Administrateur : ${rolesAdmin.joinToString { "@" + it.optString("name") }}. Cette permission contourne les restrictions de salons ; réduire les droits si elle n'est pas indispensable. Action : discord_role_modifier."
        }

        val rolesGestion = roles.filter { it.optString("id") != guildId &&
            (permissions(it) and (Discord.Perm.MANAGE_GUILD or Discord.Perm.MANAGE_ROLES or Discord.Perm.MANAGE_CHANNELS or Discord.Perm.BAN_MEMBERS or Discord.Perm.KICK_MEMBERS)) != 0L &&
            (permissions(it) and Discord.Perm.ADMINISTRATOR) == 0L
        }
        if (rolesGestion.isNotEmpty()) {
            conseils += "🔵 Vérifier la portée des rôles de gestion : ${rolesGestion.joinToString { "@" + it.optString("name") }}. Garder uniquement les droits nécessaires à chaque fonction. Action : discord_roles puis discord_role_modifier."
        }

        val sansDescription = canaux.filter { it.optInt("type") in setOf(0, 5, 15, 16) && it.optString("topic").isBlank() }
        if (sansDescription.isNotEmpty()) {
            conseils += "🟢 ${sansDescription.size} salon(s) textuel(s), annonces, forum ou média sans sujet/description. Ajouter une courte consigne d'utilisation pour guider les membres. Action : discord_salon_modifier."
        }

        val noms = canaux.map { cle(it.optString("name")) }.toSet()
        if (canaux.isNotEmpty() && noms.none { it.contains("regle") || it.contains("rules") }) {
            conseils += "🟢 Aucun salon de règles clairement identifiable. Envisager un #regles ou #bienvenue pour présenter le serveur. Action : discord_salon_creer."
        }
        if (canaux.isNotEmpty() && noms.none { it.contains("annonce") || it.contains("announcement") || it.contains("news") }) {
            conseils += "🟢 Aucun salon d'annonces clairement identifiable. Envisager un salon d'annonces si les informations importantes doivent être centralisées. Action : discord_salon_creer."
        }

        val entete = "# Conseils d'organisation — ${serveur}\nAudit en lecture seule : aucune modification n'a été effectuée. Les recommandations sont indicatives et doivent être validées selon l'usage du serveur."
        if (conseils.isEmpty()) return "$entete\n\n✅ Aucun point évident détecté dans les données accessibles au bot. Cela ne remplace pas un audit humain des membres, des intents ou des réglages du portail Discord."
        return entete + "\n\n" + conseils.mapIndexed { i, c -> "${i + 1}. $c" }.joinToString("\n\n") +
            "\n\nPour appliquer une proposition, Jarvis devra demander l'action précise et confirmer avant toute modification."
    }
}
