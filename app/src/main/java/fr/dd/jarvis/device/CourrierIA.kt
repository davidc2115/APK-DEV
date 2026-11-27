package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Lecture du message COMPLET par l'IA, après le premier tri sans IA de [Courrier.analyser] : est-ce vraiment un
 * rendez-vous fixé, une facture, un devis ou un colis (et pas une publicité, une newsletter, une invitation à prendre
 * rendez-vous) ? Avec la vraie date / heure / adresse du rendez-vous, le montant à payer et l'échéance, le numéro de
 * suivi. Rien n'est pris sans contrôle : un montant, un numéro, un code ou une adresse qui n'apparaissent pas dans le
 * message sont ignorés. Sans IA disponible, le résultat du tri par règles reste tel quel.
 */
object CourrierIA {
    private const val SYSTEME = """Tu lis UN message reçu (mail ou SMS) pour l'assistant personnel d'un artisan français. Dis s'il contient un document à traiter et extrais les informations EXACTES du texte, sans rien inventer.
Types :
- "rdv" : un rendez-vous réellement FIXÉ pour le destinataire (date convenue : garage, médecin, artisan, livraison sur rendez-vous, rencontre). Une invitation à prendre rendez-vous, un webinaire, un événement commercial ou une date de validité d'offre n'en sont PAS.
- "facture" : facture, avis d'échéance, relance ou confirmation de paiement d'une facture adressée au destinataire.
- "devis" : devis ou proposition chiffrée adressée au destinataire.
- "colis" : expédition, livraison ou retrait d'un colis.
- "aucun" : publicité, newsletter, programme de fidélité, bilan ou suivi de consommation, information générale, message transféré sans rendez-vous, conversation.
Une publicité ou une newsletter est TOUJOURS "aucun" (publicite=true), même avec des dates, des montants ou des adresses (siège social, mentions légales, RGPD).
Les dates relatives (« jeudi », « demain ») se comptent à partir de la date de réception indiquée.
Réponds uniquement par ce JSON :
{"type":"rdv|facture|devis|colis|aucun","publicite":true,"objet":"6 mots max (ex. Vidange Kadjar, Facture électricité, Colis Amazon)","date":"yyyy-MM-dd du rendez-vous ou vide","heure":"HH:mm ou vide","lieu":"adresse du rendez-vous recopiée du texte ou vide","montant":null,"echeance":"yyyy-MM-dd date limite de paiement ou vide","numero_suivi":"","statut_colis":"expédié|en livraison aujourd'hui|à retirer|livré|avis de passage|","code_retrait":"","resume":"une phrase : ce qu'il faut retenir ou faire, et quand"}"""

    private fun JSONObject.t(k: String) = optString(k).trim().takeIf { it.isNotBlank() && it != "null" && it != "vide" }.orEmpty()

    private fun chiffres(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()

    /** Le montant proposé figure-t-il dans le message (« 128,45 € », « 128.45 », « 1 250,00 ») ? */
    internal fun montantPresent(m: Double, texte: String): Boolean {
        val entier = String.format(java.util.Locale.ROOT, "%.0f", kotlin.math.floor(m))
        val cents = String.format(java.util.Locale.ROOT, "%02d", kotlin.math.round((m - kotlin.math.floor(m)) * 100).toInt())
        val propre = texte.replace(Regex("(?<=\\d)[ .  ](?=\\d{3}\\b)"), "")
        val motif = if (cents == "00") Regex("(?<![\\d,.])$entier(?:[,.]00?)?(?![\\d])") else Regex("(?<![\\d,.])$entier[,.]$cents(?!\\d)")
        return motif.containsMatchIn(propre)
    }

    /** Une adresse proposée est-elle recopiée du message (l'essentiel de ses mots y figure) ? */
    internal fun lieuPresent(lieu: String, texte: String): Boolean {
        val mots = normaliser(lieu).split(' ').filter { it.length >= 3 }
        if (mots.isEmpty()) return false
        val n = " " + normaliser(texte) + " "
        return mots.count { n.contains(" $it ") } * 3 >= mots.size * 2
    }

    /**
     * Réponse de l'IA appliquée à [e] (rien sans contrôle). null : à retirer (publicité / rien à traiter) ; [e] inchangé
     * si la réponse est inutilisable. [recu] : jour de réception du message.
     */
    internal fun appliquer(e: Courrier.Element, j: JSONObject, texte: String, recu: LocalDate): Courrier.Element? {
        val type = when (j.t("type").lowercase()) {
            "rdv", "rendez-vous", "rendez vous" -> Courrier.RDV; "facture" -> Courrier.FACTURE; "devis" -> Courrier.DEVIS
            "colis" -> Courrier.COLIS; "aucun", "none", "autre" -> ""; else -> return e
        }
        if (j.optBoolean("publicite", false) || type.isEmpty()) return null
        val objet = j.t("objet").take(50)
        val synthese = j.t("resume").take(220)
        var r = e.copy(type = type, objet = objet, synthese = synthese, verifie = true)
        when (type) {
            Courrier.RDV -> {
                val jour = runCatching { LocalDate.parse(j.t("date")) }.getOrNull()?.takeIf { !it.isBefore(recu.minusDays(1)) && it.isBefore(recu.plusYears(2)) }
                val heure = runCatching { LocalTime.parse(j.t("heure")) }.getOrNull()
                if (jour != null) r = r.copy(dateRdv = jour.atTime(heure ?: LocalTime.of(9, 0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    statut = if (heure == null) "heure non précisée" else "")
                else if (e.type != Courrier.RDV) r = r.copy(dateRdv = null, statut = "date non précisée")
                val lieu = j.t("lieu")
                r = r.copy(lieu = if (lieu.isNotBlank() && lieuPresent(lieu, texte)) lieu.take(120) else if (e.type == Courrier.RDV) e.lieu else "")
            }
            Courrier.FACTURE, Courrier.DEVIS -> {
                val m = j.optDouble("montant").takeIf { !it.isNaN() && it > 0 && montantPresent(it, texte) }
                val ech = runCatching { LocalDate.parse(j.t("echeance")) }.getOrNull()?.takeIf { !it.isBefore(recu.minusDays(60)) && it.isBefore(recu.plusDays(400)) }
                r = r.copy(montant = m ?: e.montant.takeIf { e.type == Courrier.FACTURE || e.type == Courrier.DEVIS }, echeance = ech?.toString() ?: e.echeance, dateRdv = null, lieu = "")
            }
            Courrier.COLIS -> {
                val num = j.t("numero_suivi").takeIf { it.length >= 6 && chiffres(texte).contains(chiffres(it)) }
                val code = j.t("code_retrait").takeIf { it.isNotBlank() && texte.contains(it, true) }
                val statut = j.t("statut_colis").takeIf { it in setOf("expédié", "en livraison aujourd'hui", "à retirer", "livré", "avis de passage") }
                r = r.copy(numero = num ?: e.numero, code = code ?: e.code, statut = statut ?: e.statut.ifBlank { "suivi" },
                    transporteur = e.transporteur.ifBlank { "Colis" }, dateRdv = null)
            }
        }
        return r
    }

    /**
     * Vérifie [e] (nouvellement repéré) sur le texte complet du message et enregistre le résultat. Renvoie l'élément
     * final, ou null s'il a été retiré (publicité, rien à traiter). Sans IA (pas de clé, pas de réseau) : [e].
     */
    suspend fun verifier(app: JarvisApp, e: Courrier.Element, titre: String, texte: String): Courrier.Element? {
        if (!app.store.courrierIA.value) return e
        val recu = if (e.ts > 0) Instant.ofEpochMilli(e.ts).atZone(ZoneId.systemDefault()).toLocalDate() else LocalDate.now()
        val propre = Courrier.nettoyerTexte(texte).take(6000)
        val brut = withTimeoutOrNull(30_000) {
            runCatching { app.llm.agent(SYSTEME, "Reçu le $recu · de : $titre\n\n$propre") }.getOrNull()
        } ?: return e
        val j = fr.dd.jarvis.ai.Vision.json(brut) ?: return e
        val r = appliquer(e, j, "$titre\n$propre", recu)
        // Retrait demandé par l'IA alors que l'objet parle clairement d'un document : on garde le tri par règles.
        if (r == null && !j.optBoolean("publicite", false) && Courrier.objetTransactionnel(texte)) return e
        fr.dd.jarvis.voice.JournalVoix.noter("courrier", "IA : ${e.type} « ${e.emetteur.take(40)} » → ${r?.type ?: "retiré"}${r?.objet?.let { " · $it" }.orEmpty()}")
        Courrier.remplacer(app, e.id, r)
        return r
    }
}
