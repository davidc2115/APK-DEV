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
            "discord_guildes", "discord_roles", "discord_salons", "discord_audit_serveur", "discord_role_creer", "discord_role_modifier",
            "discord_role_supprimer", "discord_role_position", "discord_role_attribuer", "discord_salon_permission", "discord_message_envoyer", "discord_messages_lire"
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
        tool("discord_audit_serveur", "AUDIT DISCORD STRICTEMENT EN LECTURE SEULE : récupère l'inventaire complet des salons et catégories avec leurs identifiants, positions, rattachements et sujets. Ne modifie jamais le serveur.",
            mapOf("serveur" to prop("Nom ou ID du serveur Discord (optionnel si un seul)."))),
        tool("discord_messages_lire", "Discord : lit les derniers messages d’un salon ou uniquement ceux arrivés après un identifiant de message. Lecture seule ; limite maximale 100 messages par appel.",
            mapOf("serveur" to prop("Nom ou ID du serveur Discord (optionnel si un seul)."), "salon" to prop("Nom ou ID du salon textuel."), "limite" to prop("Nombre de messages à lire, de 1 à 100 (défaut 30)."), "apres_id" to prop("ID du dernier message déjà lu : ne renvoie que les messages plus récents (optionnel).")), listOf("salon")),
        tool("discord_message_envoyer", "Discord : envoie un message dans un salon textuel ou d'annonces. Les mentions automatiques (@everyone/@here et rôles) sont désactivées par sécurité.",
            mapOf("serveur" to prop("Nom ou ID du serveur Discord (optionnel si un seul)."),
                "salon" to prop("Nom ou ID du salon textuel cible."),
                "message" to prop("Texte exact à envoyer (2 000 caractères maximum).")), listOf("salon", "message")),
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
        tool("discord_role_position", "Discord : change la position d'un rôle dans la hiérarchie du serveur (plus un rôle est haut, plus il l'emporte sur les rôles en dessous — c'est ce qui permet à un rôle de gérer/dépasser les rôles inférieurs).",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."), "role" to prop("Nom (ou ID) du rôle à déplacer."),
                "position" to prop("« haut » ou « maximum » pour le placer le plus haut possible (Discord ne permet jamais de dépasser le rôle le plus haut du bot JARVIS lui-même), « bas » pour le mettre tout en bas (juste au-dessus de @everyone), ou un nombre pour une position précise.")), listOf("role", "position")),
        tool("discord_role_attribuer", "Discord : donne ou retire un rôle à un membre du serveur.",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."), "membre" to prop("Nom, pseudo ou ID du membre."),
                "role" to prop("Nom (ou ID) du rôle."), "action" to prop("donner (défaut) ou retirer.")), listOf("membre", "role")),
        tool("discord_salon_permission", "Discord : règle les permissions précises d'un salon pour un rôle ou un membre. Peut autoriser/refuser plusieurs droits individuellement, appliquer un profil d'accès, ou réinitialiser l'override.",
            mapOf("serveur" to prop("Nom ou ID du serveur (optionnel si un seul)."), "salon" to prop("Nom (ou ID) du salon. Inutile si « tous_les_salons » est à true."),
                "tous_les_salons" to prop("« true » pour appliquer la règle à TOUS les salons non-catégories du serveur."),
                "role" to prop("Nom (ou ID) du rôle concerné (ou utilise « membre » à la place pour un membre précis)."), "membre" to prop("Nom, pseudo ou ID d'un membre précis (optionnel, à la place de « role »)."),
                "autoriser" to prop("Liste facultative des droits à autoriser, séparés par des virgules. Noms exacts : " + Discord.Perm.NOMS.keys.filter { it != "administrateur" }.joinToString(", ") + "."),
                "refuser" to prop("Liste facultative des droits à refuser, séparés par des virgules. Même nomenclature que « autoriser »."),
                "acces" to prop("Profil facultatif : « voir_salon », « voir_salon_seulement », « toutes_permissions », « refuser_voir_salon » ou « defaut ». Utilise autoriser/refuser pour régler des droits individuels.")))
    )

    private fun guildeObj(a: JSONObject): JSONObject {
        val demande = a.optString("serveur").trim().ifBlank { app.store.discordGuildId.value.trim() }
        return Discord.trouverGuilde(app, demande) ?: throw IllegalStateException(
            if (demande.isBlank()) "Le bot est présent sur plusieurs serveurs : précise lequel (nom ou ID), ou enregistre un serveur par défaut dans Réglages › Comptes › Discord." else "Serveur « $demande » introuvable parmi ceux où le bot est présent."
        )
    }

    private fun guilde(a: JSONObject): String = guildeObj(a).getString("id")

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
                return "- [$kind] #${channel.optString("name")} · ID ${channel.optString("id")} · position ${channel.optInt("position")}" +
                    (if (topic.isNotBlank()) " · sujet : $topic" else "")
            }
            for (category in categories) {
                val id = category.optString("id")
                lines += ""
                lines += "📁 ${category.optString("name")} · ID $id · position ${category.optInt("position")}"
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
            ToolResult(lines.joinToString("\n"), direct = true)
        }
        "discord_messages_lire" -> {
            val g = guildeObj(a); val gid = g.getString("id")
            val nomSalon = a.optString("salon").trim()
            val salon = Discord.trouverSalon(app, gid, nomSalon) ?: return ToolResult("Salon « $nomSalon » introuvable.")
            if (salon.optInt("type") !in setOf(0, 5, 15, 16)) return ToolResult("Ce salon ne permet pas la lecture de messages par cet outil.")
            val limite = (a.optString("limite").toIntOrNull() ?: 30).coerceIn(1, 100)
            val apres = a.optString("apres_id").trim()
            val arr = Discord.messages(app, salon.getString("id"), limite, apres)
            if (arr.length() == 0) return ToolResult(if (apres.isBlank()) "Aucun message lisible dans #${salon.optString("name")}." else "Aucun nouveau message dans #${salon.optString("name")} depuis cet identifiant.", direct = true)
            val messages = (0 until arr.length()).map { arr.getJSONObject(it) }.sortedBy { it.optString("id") }
            val lignes = messages.map { m ->
                val auteur = m.optJSONObject("author")?.optString("global_name").orEmpty().ifBlank { m.optJSONObject("author")?.optString("username").orEmpty().ifBlank { "Utilisateur inconnu" } }
                val date = runCatching { java.time.Instant.parse(m.optString("timestamp")).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")) }.getOrDefault("")
                val contenu = m.optString("content").trim().ifBlank { if (m.optJSONArray("attachments")?.length()?.let { it > 0 } == true) "[pièce jointe]" else "[message sans texte — contenu éventuellement masqué par les intents Discord]" }
                "[$date] **$auteur** : $contenu" + (if (m.optJSONArray("attachments")?.length()?.let { it > 0 } == true) " 📎" else "") + "\n_ID ${m.optString("id")}_"
            }
            ToolResult("# Messages de #${salon.optString("name")} — ${messages.size} résultat(s)\n" + lignes.joinToString("\n"), direct = true)
        }
        "discord_message_envoyer" -> {
            val g = guildeObj(a)
            val gid = g.getString("id")
            val gnom = g.optString("name")
            val nomSalon = a.optString("salon").trim()
            val salon = Discord.trouverSalon(app, gid, nomSalon)
                ?: return ToolResult("Salon « $nomSalon » introuvable sur « $gnom ».")
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
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val nom = a.optString("nom").trim()
            if (nom.isBlank()) return ToolResult("Nom du rôle manquant.")
            val perms = a.optString("permissions").let { if (it.isBlank()) null else Discord.Perm.parse(it) }
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
            val role = Discord.trouverRole(app, gid, a.optString("role")) ?: return ToolResult("Rôle « ${a.optString("role")} » introuvable sur le serveur « $gnom ».")
            val perms = a.optString("permissions").let { if (it.isBlank()) null else Discord.Perm.parse(it) }
            val admin = perms != null && (perms and Discord.Perm.ADMINISTRATOR) != 0L
            if (!confirm("Modifier le rôle « ${role.optString("name")} » sur le serveur « $gnom »" + (if (admin) " (ajout d'Administrateur : ignorera alors toujours les restrictions de salon)" else "") + " ?", true))
                return ToolResult("Refusé par l'utilisateur.")
            val r = Discord.roleModifier(app, gid, role.getString("id"), a.optString("nouveau_nom").trim().ifBlank { null },
                a.optString("couleur").let { couleurHex(it) }, perms,
                a.optString("affiche_separement").lowercase().takeIf { it.isNotBlank() }?.let { it in setOf("true", "oui", "1") },
                a.optString("mentionnable").lowercase().takeIf { it.isNotBlank() }?.let { it in setOf("true", "oui", "1") })
            ToolResult("✅ Rôle « ${r.optString("name")} » modifié sur le serveur « $gnom ».", direct = true)
        }
        "discord_role_supprimer" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val role = Discord.trouverRole(app, gid, a.optString("role")) ?: return ToolResult("Rôle « ${a.optString("role")} » introuvable sur le serveur « $gnom ».")
            if (!confirm("Supprimer définitivement le rôle « ${role.optString("name")} » sur le serveur « $gnom » ?", true)) return ToolResult("Refusé par l'utilisateur.")
            Discord.roleSupprimer(app, gid, role.getString("id"))
            ToolResult("🗑️ Rôle « ${role.optString("name")} » supprimé sur le serveur « $gnom ».", direct = true)
        }
        "discord_role_position" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val role = Discord.trouverRole(app, gid, a.optString("role")) ?: return ToolResult("Rôle « ${a.optString("role")} » introuvable sur le serveur « $gnom ».")
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
        "discord_role_attribuer" -> {
            val g = guildeObj(a); val gid = g.getString("id"); val gnom = g.optString("name")
            val role = Discord.trouverRole(app, gid, a.optString("role")) ?: return ToolResult("Rôle « ${a.optString("role")} » introuvable sur le serveur « $gnom ».")
            val membres = Discord.chercherMembres(app, gid, a.optString("membre"))
            if (membres.length() == 0) return ToolResult("Membre « ${a.optString("membre")} » introuvable sur le serveur « $gnom ».")
            val m = membres.getJSONObject(0)
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
            val autoriserBrut = a.optString("autoriser").trim()
            val refuserBrut = a.optString("refuser").trim()
            if (acces.isBlank() && autoriserBrut.isBlank() && refuserBrut.isBlank())
                return ToolResult("Précise un profil dans acces, ou les droits à régler dans autoriser et/ou refuser.")
            fun masquePermissions(brut: String): Long {
                if (brut.isBlank()) return 0L
                val noms = brut.split(',', ';', '|').map { it.trim().lowercase().replace(' ', '_') }.filter { it.isNotBlank() }
                val inconnus = noms.filter { it !in Discord.Perm.NOMS || it == "administrateur" }
                if (inconnus.isNotEmpty()) throw IllegalArgumentException("Droits Discord inconnus/non configurables en salon : ${inconnus.joinToString(", ")}. Utilise les noms listés dans la description de l'outil.")
                return noms.fold(0L) { bits, nom -> bits or Discord.Perm.NOMS.getValue(nom) }
            }
            val allowBits = masquePermissions(autoriserBrut)
            val denyBits = masquePermissions(refuserBrut)
            // Autoriser/refuser sont des overrides ciblés : les droits non cités restent hérités du serveur.
            // L'administrateur ne peut pas être attribué par un override de salon dans Discord.
            val toutSaufVoir = Discord.Perm.TOUT_SAUF_ADMINISTRATEUR and Discord.Perm.VIEW_CHANNEL.inv()
            fun appliquer(channel: JSONObject) {
                val channelId = channel.getString("id")
                if (autoriserBrut.isNotBlank() || refuserBrut.isNotBlank()) {
                    // L'API PUT remplace tout l'override : on relit d'abord les bits existants pour préserver
                    // chaque droit qui n'a pas été explicitement cité par l'utilisateur.
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
                if (!confirm("Modifier l'accès des ${ids.size} salon(s) du serveur pour $cibleNom ($acces) ?", true)) return ToolResult("Refusé par l'utilisateur.")
                var erreurs = 0
                ids.forEach { c -> runCatching { appliquer(c) }.onFailure { erreurs++ } }
                ToolResult("✅ Accès mis à jour sur ${ids.size - erreurs}/${ids.size} salon(s) pour $cibleNom." + (if (erreurs > 0) " ($erreurs échec(s), probablement un salon où le bot n'a pas la main)." else ""), direct = true)
            } else {
                val salon = Discord.trouverSalon(app, gid, a.optString("salon")) ?: return ToolResult("Salon « ${a.optString("salon")} » introuvable. Précise un nom de salon, ou mets tous_les_salons=true pour les appliquer tous d'un coup.")
                if (!confirm("Modifier l'accès de « ${salon.optString("name")} » pour $cibleNom ($acces) ?", true)) return ToolResult("Refusé par l'utilisateur.")
                appliquer(salon)
                ToolResult("✅ Accès de « ${salon.optString("name")} » mis à jour pour $cibleNom.", direct = true)
            }
        }
        else -> ToolResult("Outil Discord inconnu.")
        }
    }
}
