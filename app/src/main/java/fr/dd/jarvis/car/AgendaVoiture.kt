package fr.dd.jarvis.car

import android.os.Handler
import android.os.Looper
import fr.dd.jarvis.device.EventInfo
import java.time.Instant
import java.time.ZoneId

/**
 * Ce que fait l'écoute vocale de JARVIS (service « Jarvis ») : l'appli média d'Android Auto l'affiche sur l'écran de
 * lecture de la voiture (j'écoute, ta phrase en direct, je réfléchis, la réponse).
 */
object ActiviteVoiture {
    enum class Etape { ECOUTE, PARTIEL, REFLEXION, REPONSE, FIN, ECHEC }
    class Evt(val etape: Etape, val question: String = "", val texte: String = "")

    @Volatile var abonne: ((Evt) -> Unit)? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun signaler(etape: Etape, question: String = "", texte: String = "") {
        val a = abonne ?: return
        main.post { runCatching { a(Evt(etape, question, texte)) } }
    }
}

/** Agenda dans la voiture : texte lisible d'un rendez-vous et adresses où aller (lieu, description, fiche client). */
object AgendaVoiture {
    /** Une destination : [libelle] affiché, [adresse] envoyée à Waze. */
    data class Destination(val libelle: String, val adresse: String)

    private val ADRESSE = Regex(
        """(?i)\b\d{1,4}\s?(?:bis|ter)?,?\s+(?:rue|avenue|av\.|boulevard|bd|chemin|route|rte|place|all[ée]e|impasse|quai|cours|lotissement|lieu[- ]dit|hameau|zone|za|zi|zac|parc|faubourg|square|r[ée]sidence|voie|sentier|mont[ée]e)\b[^\n;|]{2,90}?\b\d{5}\s+[A-Za-zÀ-ÿ'’][A-Za-zÀ-ÿ'’ -]{1,40}"""
    )
    private val QUEUE = Regex("""(?i)[\s,.-]+(t[ée]l[ée]?phone|t[ée]l\.?|tel|portable|mobile|contact|mail|e-mail|client|code|digicode|interphone|n°|rdv|horaires?|france)\b.*$""")
    private val PAS_UN_LIEU = Regex("""(?i)https?://|\b(visio|teams|zoom|meet|t[ée]l[ée]phone|appel|en ligne|distanciel)\b""")

    /** Texte brut d'un champ d'agenda (souvent du HTML de Google Agenda). */
    fun texteBrut(s: String): String = s
        .replace(Regex("(?i)<\\s*br\\s*/?>|</\\s*(p|div|li|tr|h\\d)\\s*>"), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("[ \\t\\u00A0]+"), " ").replace(Regex(" *\\n *"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()

    /** Retire ce qui suit la ville (« Tél… », « Contact… ») sans toucher au nom de la voie. */
    private fun nettoyer(a: String): String {
        val cp = Regex("\\b\\d{5}\\s+").findAll(a).lastOrNull()
        val propre = if (cp == null) a else a.substring(0, cp.range.last + 1) + a.substring(cp.range.last + 1).replace(QUEUE, "")
        return propre.replace(Regex("\\s+"), " ").trim(' ', ',', '.', '-', ':')
    }

    /** Adresses postales (numéro, voie, code postal, ville) trouvées dans un texte. */
    fun adressesDans(texte: String): List<String> = ADRESSE.findAll(texte).map { nettoyer(it.value) }.filter { it.length >= 10 }.toList()

    private fun cle(a: String) = a.lowercase().replace(Regex("[^a-z0-9à-ÿ]"), "")

    /**
     * Où aller pour [e] : le lieu du rendez-vous (ou chacune des adresses qu'il contient), les adresses écrites dans la
     * description, et à défaut celles de la fiche client [client] (sites de la Société).
     */
    fun destinations(e: EventInfo, client: List<Destination> = emptyList()): List<Destination> {
        val out = LinkedHashMap<String, Destination>()
        fun ajout(d: Destination) {
            val k = cle(d.adresse)
            if (k.length >= 6 && out.keys.none { it.contains(k) || k.contains(it) }) out[k] = d
        }
        val lieu = texteBrut(e.location).replace("\n", ", ").trim(' ', ',')
        if (lieu.isNotBlank() && !PAS_UN_LIEU.containsMatchIn(lieu)) {
            val dedans = adressesDans(lieu)
            if (dedans.size >= 2) dedans.forEach { ajout(Destination(it, it)) } else ajout(Destination(lieu, lieu))
        }
        adressesDans(texteBrut(e.description)).forEach { ajout(Destination(it, it)) }
        if (out.isEmpty()) client.forEach { ajout(it) }
        return out.values.toList()
    }

    /** « 9 h », « 14 h 30 » (lu correctement par la synthèse vocale). */
    fun heureParlee(ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        return if (t.minute == 0) "${t.hour} h" else "${t.hour} h ${"%02d".format(t.minute)}"
    }

    /** Ville d'une adresse (après le code postal), sinon l'adresse raccourcie. */
    fun ville(adresse: String): String =
        Regex("\\b\\d{5}\\s+([^,\\n]+)").find(adresse)?.groupValues?.get(1)?.trim() ?: adresse.substringAfterLast(',').trim().take(40)

    /** Résumé parlé d'une journée : « Aujourd'hui, 3 rendez-vous. À 9 h, Clairotte, à Dijon. … » */
    fun resumeParle(evts: List<EventInfo>, jour: String, lieux: (EventInfo) -> List<Destination>): String {
        if (evts.isEmpty()) return "$jour, aucun rendez-vous."
        val n = evts.size
        return "$jour, $n rendez-vous. " + evts.joinToString(" ") { e ->
            val quand = if (e.allDay) "Toute la journée" else "À ${heureParlee(e.begin)}"
            val ou = lieux(e).firstOrNull()?.let { ", à ${ville(it.adresse)}" }.orEmpty()
            "$quand, ${texteBrut(e.title).ifBlank { "sans titre" }}$ou."
        }
    }

    /** Détails parlés d'un rendez-vous (heure, titre, notes). */
    fun detailsParles(e: EventInfo): String {
        val quand = if (e.allDay) "Toute la journée" else "De ${heureParlee(e.begin)} à ${heureParlee(e.end)}"
        val notes = texteBrut(e.description).lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString(". ").take(400)
        return "$quand : ${texteBrut(e.title).ifBlank { "sans titre" }}." + (if (notes.isNotBlank()) " $notes" else " Pas d'adresse ni de notes.")
    }
}
