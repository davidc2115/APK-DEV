package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.GmailApi
import fr.dd.jarvis.device.MailItem
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit

/** Classement des mails par l'agent (Gemini) en catégories, et rangement dans Gmail par libellés. */
object MailTri {
    val CATEGORIES = listOf(
        "Urgent", "Clients", "Chantiers et planning", "Fournisseurs et matériel", "Factures et paiements",
        "Administratif", "Banque et assurances", "Notifications", "Newsletters et promos", "Perso"
    )
    private val ARCHIVABLES = setOf("Newsletters et promos", "Notifications")

    data class Classe(val mail: MailItem, val cat: String, val prio: Int, val action: String, val sub: String = "") {
        /** Libellé Gmail : « Clients/Dupont » (sous-libellé) ou simplement la catégorie. */
        val label get() = if (sub.isBlank()) cat else "$cat/$sub"
    }

    private val SYSTEM = """
Tu es l'agent Tri de mails de JARVIS (entreprise de maintenance photovoltaïque). Tu ranges chaque mail comme le ferait un assistant humain soigneux, pour qu'il soit facile à retrouver : d'après l'EXPÉDITEUR (nom + adresse), l'OBJET et le CONTENU.
Catégories (une seule par mail) : ${CATEGORIES.joinToString(" | ")}.
Urgent = attend une action ou une réponse rapide (relance, délai, panne, problème). Clients = échanges avec clients et prospects. Chantiers et planning = rendez-vous, interventions, sites, organisation. Fournisseurs et matériel = devis/commandes/livraisons d'onduleurs, panneaux, outillage. Factures et paiements = factures, devis, relances, règlements. Administratif = impôts, URSSAF, comptable, démarches, juridique. Banque et assurances = banque, assurances, crédits. Notifications = alertes automatiques de services/sites. Newsletters et promos = publicité, lettres d'information. Perso = le reste.
IMPORTANT : un même expéditeur peut relever de PLUSIEURS catégories selon le contenu (ex. un magasin : ses publicités → Newsletters et promos, ses factures/tickets → Factures et paiements, une commande → Fournisseurs). Lis l'objet ET le texte du mail : classe d'après ce que le mail DIT, jamais d'après l'expéditeur seul. Les indications « déjà vu » ne sont que des repères.
Sous-catégorie = nom COURT (1 à 3 mots, sans « / ») de l'organisation, de la personne ou du sujet récurrent (ex. « Dupont », « Enedis », « Crédit Agricole », « Amazon », « Chantier Martin »). Un même expéditeur garde TOUJOURS la même sous-catégorie (son nom), même si la catégorie change ; réutilise celles déjà vues.
Réponds UNIQUEMENT par une ligne par mail, au format exact :
numéro|catégorie|sous-catégorie|priorité (1 urgente, 2 normale, 3 faible)|action suggérée en 8 mots maximum (vide si aucune)
N'invente rien et ne saute aucun numéro.
""".trimIndent()

    // Mémoire des expéditeurs déjà classés : garde le rangement cohérent d'un tri à l'autre.
    private fun fichier(app: JarvisApp) = java.io.File(app.filesDir, "mail_senders.json")
    private fun charge(app: JarvisApp): org.json.JSONObject = runCatching { org.json.JSONObject(fichier(app).readText()) }.getOrDefault(org.json.JSONObject())

    suspend fun classer(app: JarvisApp, mails: List<MailItem>, onProgress: (String) -> Unit = {}): List<Classe> {
        val memo = charge(app)
        val result = arrayOfNulls<Classe>(mails.size)
        val aLLM = mutableListOf<Int>()
        mails.forEachIndexed { i, m ->
            val k = m.fromAddr.ifBlank { m.from.lowercase() }
            aLLM += i
        }
        val sem = kotlinx.coroutines.sync.Semaphore(3)
        var fait = 0
        kotlinx.coroutines.coroutineScope {
            aLLM.chunked(20).map { idx ->
                async {
                    sem.withPermit {
                        val lot = idx.map { mails[it] }
                        val user = "Aujourd'hui : ${Prompts.now()}\n\n" + lot.mapIndexed { i, m ->
                            val k = m.fromAddr.ifBlank { m.from.lowercase() }
                            val prev = memo.optJSONObject(k)?.let { o -> o.keys().asSequence().filter { c -> c.contains('/') || c in CATEGORIES }.map { c -> "$c ×${o.optInt(c)}" }.toList().takeIf { it.isNotEmpty() }?.joinToString(" ; ", " (déjà vu : ", ")") }.orEmpty()
                            "#${i + 1} De : ${m.from} <${m.fromAddr}>$prev | Objet : ${m.subject} | Texte : ${m.body.ifBlank { m.snippet }.take(700)}"
                        }.joinToString("\n")
                        val rep = try { app.llm.agent(SYSTEM + app.memo.pour("mails", "tri"), user) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "" }
                        val parsed = rep.lines().mapNotNull { l ->
                            val p = l.trim().removePrefix("#").split("|").map { it.trim() }
                            val n = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                            val m = lot.getOrNull(n - 1) ?: return@mapNotNull null
                            val cat = CATEGORIES.firstOrNull { it.equals(p.getOrNull(1), true) } ?: "Perso"
                            val sub = p.getOrNull(2).orEmpty().replace("/", "-").replace(Regex("[^\\p{L}0-9 '’.&-]"), "").trim().take(30)
                            n to Classe(m, cat, p.getOrNull(3)?.toIntOrNull()?.coerceIn(1, 3) ?: 2, p.getOrNull(4).orEmpty(), sub)
                        }.toMap()
                        idx.forEachIndexed { j, gi -> result[gi] = parsed[j + 1] ?: Classe(mails[gi], "Perso", 2, "") }
                        synchronized(result) { fait += idx.size; onProgress("Tri en cours : $fait/${aLLM.size}") }
                    }
                }
            }.forEach { it.await() }
        }
        // Mémorise les expéditeurs pour garder le même rangement la prochaine fois.
        val out = result.mapIndexed { i, c -> c ?: Classe(mails[i], "Perso", 2, "") }
        runCatching {
            out.forEach { c ->
                val k = c.mail.fromAddr.ifBlank { c.mail.from.lowercase() }
                if (k.isBlank() || c.cat == "Urgent") return@forEach
                val o = memo.optJSONObject(k) ?: org.json.JSONObject().also { memo.put(k, it) }
                o.put(c.label, o.optInt(c.label) + 1)
            }
            fichier(app).writeText(memo.toString())
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
                g.groupBy { it.sub }.toSortedMap(compareBy { it.lowercase() }).forEach { (sub, items) ->
                  if (sub.isNotBlank()) append("\n**$sub** (${items.size})\n")
                  items.forEach { c ->
                    append("- ${if (c.mail.unread) "🔵 " else ""}**${c.mail.from}** — ${c.mail.subject}")
                    c.mail.date?.let { append(" _(${fmt.format(it)})_") }
                    if (c.action.isNotBlank()) append(" → ${c.action}")
                    append("\n")
                  }
                }
            }
            if (applique.isNotBlank()) append("\n$applique")
        }.trim()
    }

    suspend fun ranger(app: JarvisApp, l: List<Classe>, archiver: Boolean): Int {
        val parCompte = l.filter { it.mail.id.isNotBlank() && it.mail.account.isNotBlank() }
            .groupBy({ it.mail.account }, { it.mail.id to it.label })
        if (parCompte.isEmpty()) return 0
        return GmailApi.appliquerLibelles(app, parCompte, if (archiver) ARCHIVABLES else emptySet())
    }
}
