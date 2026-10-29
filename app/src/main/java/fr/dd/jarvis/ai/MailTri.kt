package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.GmailApi
import fr.dd.jarvis.device.MailItem

/** Classement des mails par l'agent (Gemini) en catégories, et rangement dans Gmail par libellés. */
object MailTri {
    val CATEGORIES = listOf("Urgent", "Clients", "Factures et paiements", "Chantiers et planning", "Administratif", "Notifications", "Newsletters et promos", "Perso")
    private val ARCHIVABLES = setOf("Newsletters et promos", "Notifications")

    data class Classe(val mail: MailItem, val cat: String, val prio: Int, val action: String)

    private val SYSTEM = """
Tu es l'agent Tri de mails de JARVIS (entreprise de maintenance photovoltaïque). Classe chaque mail dans UNE catégorie parmi :
${CATEGORIES.joinToString(" | ")}.
Urgent = attend une action ou réponse rapide. Clients = échanges avec clients ou prospects. Factures et paiements = factures, devis, relances, banque. Chantiers et planning = rendez-vous, interventions, fournisseurs, matériel. Administratif = assurance, impôts, URSSAF, comptable. Notifications = alertes automatiques de services. Newsletters et promos = publicité et lettres d'information. Perso = le reste.
Réponds UNIQUEMENT par une ligne par mail, au format exact :
numéro|catégorie|priorité (1 urgente, 2 normale, 3 faible)|action suggérée en 8 mots maximum (vide si aucune)
N'invente rien et ne saute aucun numéro.
""".trimIndent()

    suspend fun classer(app: JarvisApp, mails: List<MailItem>): List<Classe> {
        val out = mutableListOf<Classe>()
        for (lot in mails.chunked(25)) {
            val user = "Aujourd'hui : ${Prompts.now()}\n\n" + lot.mapIndexed { i, m ->
                "#${i + 1} De : ${m.from} | Objet : ${m.subject} | Extrait : ${m.snippet.take(180)}"
            }.joinToString("\n")
            val rep = app.llm.agent(SYSTEM + app.memo.pour("mails", "tri"), user)
            val parsed = rep.lines().mapNotNull { l ->
                val p = l.trim().removePrefix("#").split("|").map { it.trim() }
                val n = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val m = lot.getOrNull(n - 1) ?: return@mapNotNull null
                val cat = CATEGORIES.firstOrNull { it.equals(p.getOrNull(1), true) } ?: "Perso"
                n to Classe(m, cat, p.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 3) ?: 2, p.getOrNull(3).orEmpty())
            }.toMap()
            lot.forEachIndexed { i, m -> out += parsed[i + 1] ?: Classe(m, "Perso", 2, "") }
        }
        return out
    }

    fun presenter(l: List<Classe>, applique: String = ""): String {
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRENCH)
        val icones = mapOf("Urgent" to "🔴", "Clients" to "👤", "Factures et paiements" to "💶", "Chantiers et planning" to "☀️",
            "Administratif" to "🗂️", "Notifications" to "🔔", "Newsletters et promos" to "📰", "Perso" to "🏠")
        return buildString {
            append("# 📧 Mails triés (${l.size})\n")
            CATEGORIES.forEach { cat ->
                val g = l.filter { it.cat == cat }.sortedBy { it.prio }
                if (g.isEmpty()) return@forEach
                append("\n## ${icones[cat]} $cat (${g.size})\n")
                g.forEach { c ->
                    append("- ${if (c.mail.unread) "🔵 " else ""}**${c.mail.from}** — ${c.mail.subject}")
                    c.mail.date?.let { append(" _(${fmt.format(it)})_") }
                    if (c.action.isNotBlank()) append(" → ${c.action}")
                    append("\n")
                }
            }
            if (applique.isNotBlank()) append("\n$applique")
        }.trim()
    }

    suspend fun ranger(app: JarvisApp, l: List<Classe>, archiver: Boolean): Int {
        val parCompte = l.filter { it.mail.id.isNotBlank() && it.mail.account.isNotBlank() }
            .groupBy({ it.mail.account }, { it.mail.id to it.cat })
        if (parCompte.isEmpty()) return 0
        return GmailApi.appliquerLibelles(app, parCompte, if (archiver) ARCHIVABLES else emptySet())
    }
}
