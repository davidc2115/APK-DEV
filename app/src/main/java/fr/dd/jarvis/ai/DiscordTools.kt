package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Outils Discord : rôles, permissions de salons, attribution de rôles. Proposés seulement si la conversation les concerne. */
class DiscordTools(private val app: JarvisApp, private val confirm: suspend (String, Boolean) -> Boolean) {

    companion object {
        val KEYWORDS = Regex("discord|r[ôo]le\\b|r[ôo]les\\b|mod[ée]rateur|mod[ée]ration|serveur discord|salon\\b|salons\\b|cat[ée]gorie discord", RegexOption.IGNORE_CASE)
        fun concerned(t: String) = KEYWORDS.containsMatchIn(t)
        val NAMES = setOf(
            "discord_guildes", "discord_roles", "discord_salons", "discord_role_creer", "discord_role_modifier",
            "discord_role_supprimer", "discord_role_attribuer", "discord_salon_permission"
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
        tool("discord_guildes", "Discord : liste les serveurs (guildes) où le bot JARVIS est présent.", emptyMap()),
        tool("discord_roles", "Discord : liste les rôles d'un serveur (nom, couleur, permissions, position).",
            mapOf("serveur" to prop("Nom ou ID du serveur Discord (optionnel si le bot n'est que sur un seul)."))),
        tool("discord_salons", "Discord : liste les salons (textuels, vocaux, catégories) d'un serveur.",
            mapOf("serveur" to prop("Nom ou ID du serveur Discord (optionnel si un seul)."))),
        tool("discord_role_creer", "Discord : crée un nouveau rôle sur le serveur.",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."), "nom" to prop("Nom du rôle."),
                "couleur" to prop("Couleur hexadécimale, ex. #ff5733 (optionnel)."), "permissions" to prop(permDesc),
                "affiche_separement" to prop("« true » pour afficher ce rôle séparément dans la liste des membres (optionnel)."),
                "mentionnable" to prop("« true » si n'importe qui peut mentionner ce rôle (optionnel).")), listOf("nom")),
        tool("discord_role_modifier", "Discord : modifie un rôle existant (nom, couleur, permissions…).",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."), "role" to prop("Nom (ou ID) du rôle à modifier."),
                "nouveau_nom" to prop("Nouveau nom (optionnel)."), "couleur" to prop("Nouvelle couleur hexadécimale, ex. #ff5733 (optionnel)."),
                "permissions" to prop(permDesc + " (optionnel : laisse vide pour ne pas toucher aux permissions actuelles)."),
                "affiche_separement" to prop("« true »/« false » (optionnel)."), "mentionnable" to prop("« true »/« false » (optionnel).")), listOf("role")),
        tool("discord_role_supprimer", "Discord : supprime définitivement un rôle du serveur.",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."), "role" to prop("Nom (ou ID) du rôle à supprimer.")), listOf("role")),
        tool("discord_role_attribuer", "Discord : donne ou retire un rôle à un membre du serveur.",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."), "membre" to prop("Nom, pseudo ou ID du membre."),
                "role" to prop("Nom (ou ID) du rôle."), "action" to prop("donner (défaut) ou retirer.")), listOf("membre", "role")),
        tool("discord_salon_permission", "Discord : modifie l'accès d'un salon pour un rôle (autoriser, refuser, ou remettre par défaut) — c'est ce qui permet de garder un salon inaccessible même à un rôle qui a par ailleurs beaucoup de permissions (tant qu'il n'a pas « administrateur »).",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."), "salon" to prop("Nom (ou ID) du salon."),
                "role" to prop("Nom (ou ID) du rôle concerné (ou utilise « membre » à la place pour un membre précis)."), "membre" to prop("Nom, pseudo ou ID d'un membre précis (optionnel, à la place de « role »)."),
                "acces" to prop("voir_salon pour autoriser à voir/utiliser le salon, ou « refuser_voir_salon » pour le rendre invisible et inaccessible à ce rôle/membre, ou « defaut » pour effacer la règle spécifique à ce salon.")), listOf("salon", "acces"))
    )

    private fun guilde(a: JSONObject): String {
        val demande = a.optString("serveur").trim().ifBlank { app.store.discordGuildId.value.trim() }
        val g = Discord.trouverGuilde(app, demande) ?: throw IllegalStateException(
            if (demande.isBlank()) "Le bot est présent sur plusieurs serveurs : précise lequel (nom ou ID), ou enregistre un serveur par défaut dans Réglages › Discord." else "Serveur « $demande » introuvable parmi ceux où le bot est présent."
        )
        return g.getString("id")
    }

    suspend fun execute(name: String, a: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        try { run(name, a) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { ToolResult("❌ ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun couleurHex(s: String): Int? {
        val h = s.trim().removePrefix("#")
        return if (h.isBlank()) null else h.toIntOrNull(16)
    }

    private suspend fun run(name: String, a: JSONObject): ToolResult {
        return when (name) {
        "discord_guildes" -> {
            val arr = Discord.guildes(app)
            ToolResult("# 💬 Serveurs Discord (${arr.length()})\n" + (0 until arr.length()).joinToString("\n") { i -> "- " + arr.getJSONObject(i).optString("name") }, direct = true)
        }
        "discord_salons" -> {
            val gid = guilde(a)
            val arr = Discord.salons(app, gid)
            val types = mapOf(0 to "💬 texte", 2 to "🔊 vocal", 4 to "📁 catégorie", 5 to "📢 annonces", 13 to "🎤 scène", 15 to "📋 forum")
            ToolResult("# 📋 Salons (${arr.length()})\n" + (0 until arr.length()).sortedBy { arr.getJSONObject(it).optInt("position") }
                .joinToString("\n") { i -> val c = arr.getJSONObject(i); "- ${types[c.optInt("type")] ?: "?"} ${c.optString("name")}" }, direct = true)
        }
        "discord_roles" -> {
            val gid = guilde(a)
            val arr = Discord.roles(app, gid)
            ToolResult("# 🎭 Rôles (${arr.length()})\n" + (0 until arr.length()).sortedByDescending { arr.getJSONObject(it).optInt("position") }
                .joinToString("\n") { i ->
                    val r = arr.getJSONObject(i)
                    val perms = Discord.Perm.noms(r.optString("permissions", "0").toLongOrNull() ?: 0L)
                    val admin = (r.optString("permissions", "0").toLongOrNull() ?: 0L) and Discord.Perm.ADMINISTRATOR != 0L
                    "- **${r.optString("name")}** · #%06X".format(r.optInt("color")) + (if (admin) " · 👑 administrateur" else if (perms.isNotEmpty()) " · ${perms.size} permission(s)" else " · aucune permission")
                }, direct = true)
        }
        "discord_role_creer" -> {
            val gid = guilde(a)
            val nom = a.optString("nom").trim()
            if (nom.isBlank()) return ToolResult("Nom du rôle manquant.")
            val perms = a.optString("permissions").let { if (it.isBlank()) null else Discord.Perm.parse(it) }
            val admin = perms != null && (perms and Discord.Perm.ADMINISTRATOR) != 0L
            if (!confirm("Créer le rôle « $nom »" + (if (admin) " avec TOUTES les permissions, y compris Administrateur (ignore toujours les restrictions de salon)" else "") + " ?", true))
                return ToolResult("Refusé par l'utilisateur.")
            val r = Discord.roleCreer(app, gid, nom, a.optString("couleur").let { couleurHex(it) }, perms,
                a.optString("affiche_separement").lowercase().takeIf { it.isNotBlank() }?.let { it in setOf("true", "oui", "1") },
                a.optString("mentionnable").lowercase().takeIf { it.isNotBlank() }?.let { it in setOf("true", "oui", "1") })
            ToolResult("✅ Rôle « ${r.optString("name")} » créé." + (perms?.let { " Permissions : ${Discord.Perm.noms(it).joinToString(", ").ifBlank { "aucune" }}." } ?: "") +
                "\n⚠️ N'oublie pas de placer ce rôle au-dessus, dans la hiérarchie du serveur, de tous les rôles qu'il doit dépasser (Discord ne le fait pas automatiquement).")
        }
        "discord_role_modifier" -> {
            val gid = guilde(a)
            val role = Discord.trouverRole(app, gid, a.optString("role")) ?: return ToolResult("Rôle « ${a.optString("role")} » introuvable.")
            val perms = a.optString("permissions").let { if (it.isBlank()) null else Discord.Perm.parse(it) }
            val admin = perms != null && (perms and Discord.Perm.ADMINISTRATOR) != 0L
            if (!confirm("Modifier le rôle « ${role.optString("name")} »" + (if (admin) " (ajout d'Administrateur : ignorera alors toujours les restrictions de salon)" else "") + " ?", true))
                return ToolResult("Refusé par l'utilisateur.")
            val r = Discord.roleModifier(app, gid, role.getString("id"), a.optString("nouveau_nom").trim().ifBlank { null },
                a.optString("couleur").let { couleurHex(it) }, perms,
                a.optString("affiche_separement").lowercase().takeIf { it.isNotBlank() }?.let { it in setOf("true", "oui", "1") },
                a.optString("mentionnable").lowercase().takeIf { it.isNotBlank() }?.let { it in setOf("true", "oui", "1") })
            ToolResult("✅ Rôle « ${r.optString("name")} » modifié.")
        }
        "discord_role_supprimer" -> {
            val gid = guilde(a)
            val role = Discord.trouverRole(app, gid, a.optString("role")) ?: return ToolResult("Rôle « ${a.optString("role")} » introuvable.")
            if (!confirm("Supprimer définitivement le rôle « ${role.optString("name")} » ?", true)) return ToolResult("Refusé par l'utilisateur.")
            Discord.roleSupprimer(app, gid, role.getString("id"))
            ToolResult("🗑️ Rôle « ${role.optString("name")} » supprimé.")
        }
        "discord_role_attribuer" -> {
            val gid = guilde(a)
            val role = Discord.trouverRole(app, gid, a.optString("role")) ?: return ToolResult("Rôle « ${a.optString("role")} » introuvable.")
            val membres = Discord.chercherMembres(app, gid, a.optString("membre"))
            if (membres.length() == 0) return ToolResult("Membre « ${a.optString("membre")} » introuvable.")
            val m = membres.getJSONObject(0)
            val userId = m.getJSONObject("user").getString("id")
            val pseudo = m.optString("nick").ifBlank { m.getJSONObject("user").optString("username") }
            val retirer = a.optString("action").lowercase().startsWith("retir")
            if (!confirm("${if (retirer) "Retirer" else "Donner"} le rôle « ${role.optString("name")} » à $pseudo ?", true)) return ToolResult("Refusé par l'utilisateur.")
            if (retirer) Discord.roleRetirer(app, gid, userId, role.getString("id")) else Discord.roleAttribuer(app, gid, userId, role.getString("id"))
            ToolResult(if (retirer) "✅ Rôle « ${role.optString("name")} » retiré à $pseudo." else "✅ Rôle « ${role.optString("name")} » donné à $pseudo.")
        }
        "discord_salon_permission" -> {
            val gid = guilde(a)
            val salon = Discord.trouverSalon(app, gid, a.optString("salon")) ?: return ToolResult("Salon « ${a.optString("salon")} » introuvable.")
            val roleArg = a.optString("role").trim(); val membreArg = a.optString("membre").trim()
            val (cibleId, cibleNom, type) = when {
                roleArg.isNotBlank() -> {
                    val r = Discord.trouverRole(app, gid, roleArg) ?: return ToolResult("Rôle « $roleArg » introuvable.")
                    Triple(r.getString("id"), r.optString("name"), 0)
                }
                membreArg.isNotBlank() -> {
                    val membres = Discord.chercherMembres(app, gid, membreArg)
                    if (membres.length() == 0) return ToolResult("Membre « $membreArg » introuvable.")
                    val m = membres.getJSONObject(0)
                    Triple(m.getJSONObject("user").getString("id"), m.optString("nick").ifBlank { m.getJSONObject("user").optString("username") }, 1)
                }
                else -> return ToolResult("Précise un rôle ou un membre.")
            }
            val acces = a.optString("acces").trim().lowercase()
            if (!confirm("Modifier l'accès de « ${salon.optString("name")} » pour $cibleNom ($acces) ?", true)) return ToolResult("Refusé par l'utilisateur.")
            when {
                acces.contains("refuser") -> Discord.salonPermission(app, salon.getString("id"), cibleId, type, 0L, Discord.Perm.VIEW_CHANNEL)
                acces.contains("defaut") || acces.contains("default") -> Discord.salonPermissionSupprimer(app, salon.getString("id"), cibleId)
                else -> Discord.salonPermission(app, salon.getString("id"), cibleId, type, Discord.Perm.VIEW_CHANNEL, 0L)
            }
            ToolResult("✅ Accès de « ${salon.optString("name")} » mis à jour pour $cibleNom.")
        }
        else -> ToolResult("Outil Discord inconnu.")
        }
    }
}
