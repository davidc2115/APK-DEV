package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.GmailApi
import fr.dd.jarvis.device.MailItem
import fr.dd.jarvis.device.ReglesMail
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit

/**
 * Tri des mails dans l'organisation de l'utilisateur (ses libellés en couleur, voir [ReglesMail]) : d'abord les règles
 * par expéditeur (instantané, sans IA), puis l'agent Gemini pour les mails qu'aucune règle ne reconnaît.
 * Toutes les newsletters et publicités vont dans « Pubs » ; aucun sous-libellé par expéditeur.
 */
object MailTri {
    val CATEGORIES get() = ReglesMail.NOMS

    /** [prio] 1 = urgent (alerte), 2 normal, 3 faible ; [action] = ce qu'il reste à faire. */
    data class Classe(val mail: MailItem, val cat: String, val prio: Int, val action: String, val sub: String = "") {
        val label get() = cat
    }

    private fun systeme() = """
Tu es l'agent Tri de mails de JARVIS. Range chaque mail dans UN des libellés de l'utilisateur, d'après l'EXPÉDITEUR, l'OBJET et le CONTENU :
${ReglesMail.consigneIa()}
Règles : toute newsletter, publicité, offre ou promotion → « Pubs » (sauf celles des magasins U → « Super U/Pubs »). Un même expéditeur peut aller dans des libellés différents selon le contenu (ex. magasin : ticket → Super U/Tickets de caisse, commande → Super U/Commandes ou Achats et livraisons, pub → Pubs). Impôts, taxe foncière et taxe d'habitation → « Impôts ». Un mail écrit par une vraie personne de l'entourage → « Personnel ». Travail, clients, fournisseurs, chantiers → « Travail ».
Priorité : 1 si le mail attend une action rapide (délai, relance, dossier incomplet, panne, paiement refusé), 2 normal, 3 faible (pub, notification).
Réponds UNIQUEMENT par une ligne par mail, au format exact :
numéro|libellé exact|priorité|action suggérée en 8 mots maximum (vide si aucune)
N'invente rien et ne saute aucun numéro.
""".trimIndent()

    private fun libelleValide(s: String?): String? {
        val t = s?.trim().orEmpty()
        return ReglesMail.NOMS.firstOrNull { it.equals(t, true) }
            ?: ReglesMail.NOMS.firstOrNull { fr.dd.jarvis.societe.core.normaliser(it) == fr.dd.jarvis.societe.core.normaliser(t) }
    }

    /** Adresses mail des clients connus (fiche Société) : leurs mails vont dans « Travail ». */
    fun adressesClients(app: JarvisApp): List<String> = runCatching { app.societe.data.clients.mapNotNull { it.email.trim().lowercase().takeIf { e -> e.contains('@') } } }.getOrDefault(emptyList())

    suspend fun classer(app: JarvisApp, mails: List<MailItem>, onProgress: (String) -> Unit = {}): List<Classe> {
        val clients = adressesClients(app)
        val result = arrayOfNulls<Classe>(mails.size)
        val aLLM = mutableListOf<Int>()
        mails.forEachIndexed { i, m ->
            val r = ReglesMail.classer(m.fromAddr.ifBlank { m.from }, m.subject, m.labels, clients)
            if (r != null) result[i] = Classe(m, r, if (r == "Pubs" || r == "Super U/Pubs" || r == "Facebook" || r == "Réseaux sociaux") 3 else 2, "")
            else aLLM += i
        }
        val sem = kotlinx.coroutines.sync.Semaphore(3)
        var fait = 0
        val sys = systeme()
        kotlinx.coroutines.coroutineScope {
            aLLM.chunked(20).map { idx ->
                async {
                    sem.withPermit {
                        val lot = idx.map { mails[it] }
                        val user = "Aujourd'hui : ${Prompts.now()}\n\n" + lot.mapIndexed { i, m ->
                            "#${i + 1} De : ${m.from} <${m.fromAddr}> | Objet : ${m.subject} | Texte : ${m.body.ifBlank { m.snippet }.take(600)}"
                        }.joinToString("\n")
                        val rep = try { app.llm.agent(sys + app.memo.pour("mails", "tri"), user) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "" }
                        val parsed = rep.lines().mapNotNull { l ->
                            val p = l.trim().removePrefix("#").split("|").map { it.trim() }
                            val n = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                            val m = lot.getOrNull(n - 1) ?: return@mapNotNull null
                            val cat = libelleValide(p.getOrNull(1)) ?: return@mapNotNull null
                            n to Classe(m, cat, p.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 3) ?: 2, p.getOrNull(3).orEmpty())
                        }.toMap()
                        idx.forEachIndexed { j, gi -> result[gi] = parsed[j + 1] ?: replis(mails[gi]) }
                        synchronized(result) { fait += idx.size; onProgress("Tri en cours : $fait/${aLLM.size}") }
                    }
                }
            }.forEach { it.await() }
        }
        return result.mapIndexed { i, c -> c ?: replis(mails[i]) }
    }

    /** Sans réponse de l'IA : d'après la catégorie Gmail. */
    internal fun replis(m: MailItem): Classe = Classe(m, when {
        "CATEGORY_PROMOTIONS" in m.labels || "CATEGORY_FORUMS" in m.labels -> "Pubs"
        "CATEGORY_SOCIAL" in m.labels -> "Réseaux sociaux"
        "CATEGORY_PERSONAL" in m.labels -> "Personnel"
        else -> "Comptes et sécurité"
    }, 2, "")

    fun presenter(l: List<Classe>, applique: String = ""): String {
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRENCH)
        return buildString {
            append("# 📧 Mails triés (${l.size})\n")
            CATEGORIES.forEach { cat ->
                val g = l.filter { it.cat == cat }.sortedBy { it.prio }
                if (g.isEmpty()) return@forEach
                append("\n## $cat (${g.size})\n")
                g.forEach { c ->
                    append("- ${if (c.prio == 1) "🔴 " else if (c.mail.unread) "🔵 " else ""}**${c.mail.from}** — ${c.mail.subject}")
                    c.mail.date?.let { append(" _(${fmt.format(it)})_") }
                    if (c.action.isNotBlank()) append(" → ${c.action}")
                    append("\n")
                }
            }
            if (applique.isNotBlank()) append("\n$applique")
        }.trim()
    }

    /** Range dans Gmail ; [archiver] = « dans son libellé » (retiré de la boîte de réception), sauf les mails urgents. */
    suspend fun ranger(app: JarvisApp, l: List<Classe>, archiver: Boolean): Int {
        val parCompte = l.filter { it.mail.id.isNotBlank() && it.mail.account.isNotBlank() }
            .groupBy({ it.mail.account }, { it.mail.id to it.label })
        if (parCompte.isEmpty()) return 0
        val urgents = l.filter { it.prio == 1 }.map { it.mail.id }.toSet()
        val archivables = if (archiver) CATEGORIES.toSet() else emptySet()
        val (normaux, garder) = parCompte.mapValues { (_, v) -> v.partition { it.first !in urgents } }.let { m -> m.mapValues { it.value.first } to m.mapValues { it.value.second } }
        return GmailApi.appliquerLibelles(app, normaux.filterValues { it.isNotEmpty() }, archivables) +
            GmailApi.appliquerLibelles(app, garder.filterValues { it.isNotEmpty() }, emptySet())
    }
}
