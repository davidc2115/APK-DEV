package fr.dd.jarvis.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.CommandesDirectes
import fr.dd.jarvis.ai.DatesFr
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Analyse des SMS et des mails reçus (sans IA, instantané) : COLIS (transporteur, numéro, état, code de retrait, lien de
 * suivi), FACTURES (émetteur, montant, échéance), DEVIS, RENDEZ-VOUS (date, heure, lieu). Tout est rangé dans une liste
 * « à traiter » : rappelée dans les points du matin et du soir, consultable à la voix (« mes colis », « qu'est-ce que
 * j'ai reçu d'important ? »), avec une notification et une action pour les rendez-vous (« ajouter à l'agenda ») et les
 * factures (« en note de frais »). Les codes de connexion / de vérification ne sont JAMAIS gardés.
 */
object Courrier {
    data class Element(
        val id: String, val type: String, val source: String, val emetteur: String, val resume: String, val ts: Long,
        val transporteur: String = "", val numero: String = "", val lien: String = "", val statut: String = "", val code: String = "",
        val montant: Double? = null, val echeance: String = "", val dateRdv: Long? = null, val lieu: String = "", val traite: Boolean = false,
        /** « societe », « perso » ou "" (à classer). */
        val domaine: String = "",
        /** Classé par JARVIS (client de la société, boîte mail de la société, règle apprise) plutôt qu'à la main. */
        val auto: Boolean = false,
        /** Objet court donné par l'IA (« Vidange Kadjar », « Facture électricité »). */
        val objet: String = "",
        /** Ce qu'il faut retenir, en une phrase (IA, d'après le message complet). */
        val synthese: String = "",
        /** Vérifié par l'IA sur le message complet : la revalidation par règles ne le retire plus. */
        val verifie: Boolean = false,
    )

    const val COLIS = "colis"; const val FACTURE = "facture"; const val DEVIS = "devis"; const val RDV = "rdv"
    const val SOCIETE = "societe"; const val PERSO = "perso"

    // ------------------------------------------------------------------ analyse (pure)

    /** Pour les notifications de messagerie, le dernier message réel prime sur le résumé tronqué de la notification. */
    internal fun contenuNotification(texteNotification: String, dernierMessage: String): String =
        dernierMessage.trim().takeIf { it.isNotBlank() } ?: texteNotification.trim()

    /** Messages de sécurité (code de connexion, double authentification) : jamais analysés ni gardés. */
    private val SECURITE = Regex("\\b(code de (verification|confirmation|securite|connexion|validation|authentification)|mot de passe|ne (le )?communiquez|ne partagez|one[- ]time|otp|3d ?secure|double authentification)\\b")

    private data class Transporteur(val nom: String, val mots: Regex, val numero: Regex, val lien: (String) -> String)
    private val TRANSPORTEURS = listOf(
        Transporteur("Colissimo", Regex("\\b(colissimo|la poste|laposte)\\b"), Regex("\\b(\\d[A-Z]\\d{11}|[A-Z]{2}\\d{9}FR|\\d{13}|\\d{15})\\b")) { "https://www.laposte.fr/outils/suivre-vos-envois?code=$it" },
        Transporteur("Chronopost", Regex("\\bchronopost\\b"), Regex("\\b([A-Z]{2}\\d{9}[A-Z]{2}|\\d{13,14})\\b")) { "https://www.chronopost.fr/tracking-no-cms/suivi-page?listeNumerosLT=$it" },
        Transporteur("UPS", Regex("\\bups\\b"), Regex("\\b(1Z[0-9A-Z]{16})\\b")) { "https://www.ups.com/track?tracknum=$it&loc=fr_FR" },
        Transporteur("DHL", Regex("\\bdhl\\b"), Regex("\\b(JJD\\d{10,20}|\\d{10})\\b")) { "https://www.dhl.com/fr-fr/home/suivi.html?tracking-id=$it" },
        Transporteur("DPD", Regex("\\bdpd\\b"), Regex("\\b(\\d{14})\\b")) { "https://trace.dpd.fr/fr/trace/$it" },
        Transporteur("GLS", Regex("\\bgls\\b"), Regex("\\b(\\d{11,12}|[A-Z0-9]{8})\\b")) { "https://gls-group.com/FR/fr/suivi-colis?match=$it" },
        Transporteur("Mondial Relay", Regex("\\bmondial ?relay\\b"), Regex("\\b(\\d{8}|\\d{10,12})\\b")) { "https://www.mondialrelay.fr/suivi-de-colis/?NumeroExpedition=$it" },
        Transporteur("Relais Colis", Regex("\\brelais ?colis\\b"), Regex("\\b(\\d{10,16})\\b")) { "https://www.relaiscolis.com/suivi-de-colis/?reference=$it" },
        Transporteur("Amazon", Regex("\\bamazon\\b"), Regex("\\b(TBA\\d{9,12})\\b")) { "https://www.amazon.fr/gp/your-account/order-history" },
    )
    private val MOTS_COLIS = Regex("\\b(colis|livraison|livre|livree|expedie|expediee|expedition|point relais|relais|consigne|locker|avis de passage|suivi|tracking|envoi|transporteur)\\b")
    /** Une commande ou un numéro de commande Amazon ne prouvent jamais qu'un colis est expédié. */
    private val INDICES_EXPEDITION = Regex("\\b(colis (?:a ete |est |sera )?(?:expedie|livre|en route|en transit|disponible|arrive)|commande (?:a ete |est )?expediee?|vient d etre expedie|a ete expedie|en cours d acheminement|pris en charge par|numero de suivi|suivre votre colis|suivi de (?:votre )?(?:colis|commande)|livraison (?:est |sera )?(?:prevue|confirmee|en cours|effectuee)|sera livre|est en transit|est en cours de livraison|avis de passage|disponible au point relais|a ete remis au transporteur|en cours d expedition)\\b")
    private val NUMERO_GENERIQUE = Regex("\\b([A-Z]{2}\\d{9}[A-Z]{2}|\\d[A-Z]\\d{11}|1Z[0-9A-Z]{16}|\\d{13,15})\\b")
    private val CODE_RETRAIT = Regex("(?i)code (?:de retrait|de collecte|d'ouverture|d’ouverture|de deverrouillage|de déverrouillage|pin|de livraison|de recuperation|de récupération|a presenter|à présenter)\\s*[:=]?\\s*([A-Z0-9]{4,10})")

    private val MONTANT = Regex("(\\d{1,3}(?:[ .\\u202F\\u00A0]\\d{3})*(?:[,.]\\d{1,2})?)\\s?(?:€|eur\\b|euros?\\b)", RegexOption.IGNORE_CASE)
    // Les mots génériques (« garage », « réservation », « intervention », « prélèvement ») seuls génèrent trop de faux positifs.
    private val FACTURE_RE = Regex("\\b(facture|factures|invoice|avis d echeance|echeancier|facture jointe|facture disponible|facture a payer|facture a regler|relance de paiement|impaye|paiement en attente|justificatif de paiement|recu de paiement|montant de votre facture|prelevement de votre facture)\\b")
    private val DEVIS_RE = Regex("\\b(devis|proposition de devis|offre de prix|estimation de travaux|chiffrage|bon pour accord)\\b")
    private val RDV_RE = Regex("\\b(rendez vous|rdv|convocation|rappel de votre rendez vous|confirmation de rendez vous|confirmation du rendez vous|votre rendez vous|rdv confirme|visite prevue|visite est prevue|votre visite|visite technique|rappel de visite|visite a domicile|votre consultation|consultation prevue|creneau confirme|creneau reserve|reservation confirmee|confirmation de reservation|votre reservation est confirmee|intervention prevue|intervention programmee|intervention planifiee|intervention confirmee|votre intervention|votre venue|vous etes attendu|nous vous attendons|passage de notre technicien|passage du technicien|technicien passera|confirmation de visite)\\b")
    private val RDV_AUTO_RE = Regex("\\b(vidange|controle technique|ct|revision|entretien du vehicule|pneumatiques?|changement de pneus)\\b")
    private val RENCONTRE_RE = Regex("\\b(on se voit|on se retrouve|on se rejoint|je passe te voir|je passerai te voir|je viens te voir|je viendrai te voir|je serai chez toi|je serai chez vous|je t attends|je vous attends|je passe chez toi|je passerai chez toi|je viens chez toi|je viendrai chez toi)\\b")
    private val PROMOTION_RE = Regex("\\b(option budget|code promo|promotion|promotions|offre speciale|offre exclusive|profitez de|decouvrez notre offre|remise exceptionnelle|vente privee|abonnez vous|souscrivez|jusqu a -?\\d{1,2} ?%|plus que \\d+ h|votre code expire|code expire bientot|profitez de vos|soldes|nouvelle collection)\\b")
    private val PUB_RDV_RE = Regex("\\b(plus que \\d+ h|code expire|votre code expire|profitez de vos|\\d{1,2} ?%|reduction|remise|offre exclusive|offre speciale|soldes|nouvelle collection|simulation est sauvegardee|relevez votre compteur|releve de compteur|index de compteur|transmettez votre index|confirmation de votre commande|abonnement .* (prolonge|renouvele) et facture)\\b")
    private val RELEVE_COMPTEUR_RE = Regex("\\b(relevez? votre compteur|releve de compteur|index de compteur|transmettez votre index|auto releve)\\b")
    private val FINANCE_HORS_FACTURE_RE = Regex("\\b(simulation est sauvegardee|confirmation de votre commande|abonnement .* (prolonge|renouvele) et facture|achat confirme|merci pour votre achat)\\b")
    private val HEURE = Regex("\\b(\\d{1,2}) ?(?:h|:) ?(\\d{2})?\\b")
    // Le type de voie est un mot entier : « 27 avril 2016 » n'est pas « 27 av(enue) ».
    private val ADRESSE = Regex("(\\d{1,4}(?: ?bis| ?ter)?,? (?:rue|avenue|av|bd|boulevard|chemin|place|allee|allée|impasse|route|quai|cours|faubourg|zone|za|zi|lieu[- ]dit)(?![A-Za-zÀ-ÿ])\\.?[^,\\n.<>]{2,60}(?:,? ?\\d{5} [A-Za-zÀ-ÿ' -]{2,40})?)", RegexOption.IGNORE_CASE)
    private val GARAGE_RDV = Regex("\\bgarage\\s+([A-ZÀ-ÿ][A-Za-zÀ-ÿ'’-]*(?:\\s+[A-ZÀ-ÿ][A-Za-zÀ-ÿ'’-]*){0,2})", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------ nettoyage du texte reçu

    private val ENTITES = mapOf("nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "zwnj" to "", "zwj" to "",
        "shy" to "", "lrm" to "", "rlm" to "", "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "euml" to "ë", "agrave" to "à", "acirc" to "â",
        "ccedil" to "ç", "ocirc" to "ô", "ouml" to "ö", "ucirc" to "û", "ugrave" to "ù", "uuml" to "ü", "icirc" to "î", "iuml" to "ï", "oelig" to "œ",
        "Eacute" to "É", "Egrave" to "È", "Agrave" to "À", "Ccedil" to "Ç", "euro" to "€", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”",
        "ldquo" to "“", "laquo" to "«", "raquo" to "»", "hellip" to "…", "ndash" to "–", "mdash" to "—", "deg" to "°", "copy" to "©", "reg" to "®",
        "trade" to "™", "bull" to "•", "middot" to "·", "times" to "×")

    /**
     * Texte lisible d'un message : entités HTML décodées (« &#xE9; » → é, « &zwnj; » retiré), caractères invisibles des
     * newsletters retirés, « \n » littéraux remis en retours à la ligne, espaces resserrés.
     */
    fun nettoyerTexte(t: String): String {
        var s = t.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\t", " ")
        repeat(2) { // « &amp;eacute; » : deux passes
            s = Regex("&(#[xX][0-9a-fA-F]{1,6}|#\\d{1,7}|[a-zA-Z]{2,8});").replace(s) { m ->
                val k = m.groupValues[1]
                when {
                    k.startsWith("#x") || k.startsWith("#X") -> k.substring(2).toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
                    k.startsWith("#") -> k.substring(1).toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
                    else -> ENTITES[k] ?: ENTITES[k.lowercase()] ?: m.value
                }
            }
        }
        s = s.replace(Regex("[\\u200B-\\u200F\\u2060-\\u2064\\uFEFF\\u034F\\u00AD\\u180E\\u061C]"), "")
        return s.lines().map { it.replace(Regex("[ \\t\\u00A0\\u202F]+"), " ").trim() }.joinToString("\n")
            .replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private val URL = Regex("<?\\bhttps?://\\S+>?|<?\\bwww\\.[^\\s>]+>?", RegexOption.IGNORE_CASE)

    /** Aperçu affiché : texte nettoyé, sans les liens de suivi interminables. */
    internal fun resumeDe(texte: String): String = nettoyerTexte(texte).replace(URL, " ").replace(Regex("\\s+"), " ")
        .replace(Regex("\\s+([,.;:!?])"), "$1").replace(Regex("^[,.;:!? -]+"), "").trim().take(220)

    // ------------------------------------------------------------------ publicités et newsletters

    private val LABELS_PUB = setOf("CATEGORY_PROMOTIONS", "CATEGORY_SOCIAL", "CATEGORY_FORUMS", "SPAM")
    /** Pseudo-étiquette : adresse d'expédition d'un service d'envoi de masse (news@, newsletter@, marketing@…). */
    const val EXPEDITEUR_MARKETING = "JARVIS_EXPEDITEUR_MARKETING"
    private val ADRESSE_MARKETING = Regex("(?i)^(news|newsletters?|marketing|promo(tions?)?|offres?|bons?-?plans?|actus?|actualites?|communication|evenements?|club|fidelite|mailing|emailing|hello|bonjour)[._-]?[a-z0-9]*@|@(news|newsletters?|e-?mail(s|ing)?|mailing|mail|em|e|m|edm|crm|campaign|campaigns|marketing|promo|info|infos|communication|offres?|actu)[0-9]*\\.")

    /** Adresse d'expéditeur typique d'un envoi de masse (« news@okaidi.fr », « noreply@e.engie.fr », « contact@mailing.x.fr »). */
    internal fun expediteurMarketing(adresse: String): Boolean = ADRESSE_MARKETING.containsMatchIn(adresse.trim())
    /** Pied de page d'un envoi de masse (newsletter, publicité) : désinscription, « version en ligne »… */
    private val DESABONNEMENT = Regex("\\b(se desinscrire|desinscri\\w*|desabonn\\w*|ne (plus|souhaitez plus|voulez plus) recevoir|pour ne plus recevoir|unsubscribe|gerer (vos|mes) (preferences|abonnements)|preferences de communication|si vous ne visualisez pas|ne s affiche pas correctement|version en ligne|voir la version|voir en ligne|afficher (ce message |cet e ?mail )?dans (votre|le) navigateur|view (it |this email )?in (your )?browser|newsletter|lettre d information|vous recevez (cet|ce) (e ?mail|courriel|message)|cet e ?mail vous a ete envoye|ce message vous a ete envoye|vous avez recu (cet|ce) (e ?mail|message)|stop (au |sms |par sms au )?\\d{5}|stop sms|repondez stop|desinscription sms)\\b")
    private val COMMERCIAL = Regex("\\b(black friday|cyber monday|french days|soldes|vente privee|ventes privees|code promo|bons? plans?|promos?|promotions?|offres?|cagnotte|decouvrez|nouveautes?|nouvelle collection|jusqu a|livraison offerte|profitez|ne manquez pas|derniers jours|dernier jour|exclusivites?|exclusif|exclusive|cadeaux?|remises?|reductions?|economisez|petits prix|meilleures ventes|notre selection|carte fidelite|fidelite|parrainage|parrainez|jeu concours|concours|gagnez|abonnez vous|inscrivez vous|webinaire|webinar|ateliers? gratuits?|catalogue|collection|vente flash|ventes flash|bons? d achat|black week|achetez|commandez|en magasin|boutique|inedit|gratuite?s?|offerte?s?)\\b")
    private val POURCENT = Regex("-\\s?\\d{1,2}\\s?%|\\b\\d{1,2}\\s?%\\s?(de remise|de reduction|de réduction|offerts?|sur|off)\\b", RegexOption.IGNORE_CASE)
    /** Ce qu'une publicité n'écrit pas : un document ou un rendez-vous qui concerne vraiment le destinataire. */
    private val TRANSACTIONNEL = Regex("\\b(votre facture|facture n|facture numero|numero de facture|nouvelle facture|montant de votre facture|avis d echeance|echeancier|a payer avant|a regler avant|sera preleve|sera prelevee|prelevement (de|du|le)|relance de paiement|impaye|votre devis|devis n|devis numero|bon pour accord|votre rendez vous (est|du|le|a|avec|de)|confirmation de (votre |du )?rendez vous|rappel de (votre )?rendez vous|rendez vous (est )?confirme|convocation|numero de suivi|suivre votre colis|votre colis|votre commande (a ete |est )?expediee?|avis de passage|votre livraison)\\b")

    /** Publicité, newsletter ou mailing (Gmail « Promotions », pied de désinscription, vocabulaire commercial). */
    internal fun publicite(sujet: String, corps: String, labels: List<String> = emptyList()): Boolean {
        if (labels.any { it in LABELS_PUB }) return true
        val ns = normaliser(sujet); val nc = normaliser(corps)
        if (TRANSACTIONNEL.containsMatchIn(ns)) return false
        // Envoi de masse sans objet transactionnel : publicité, sauf s'il cite vraiment un document ou un rendez-vous.
        if (EXPEDITEUR_MARKETING in labels && !TRANSACTIONNEL.containsMatchIn(nc)) return true
        val commerce = COMMERCIAL.findAll("$ns $nc").map { it.value }.toSet().size + (if (POURCENT.containsMatchIn("$sujet $corps")) 2 else 0)
        if (TRANSACTIONNEL.containsMatchIn(nc)) return commerce >= 5
        return DESABONNEMENT.containsMatchIn("$ns $nc") || commerce >= 2
    }

    /** L'objet (première ligne) parle clairement d'une facture, d'un devis, d'un rendez-vous ou d'un colis. */
    internal fun objetTransactionnel(texte: String) =
        TRANSACTIONNEL.containsMatchIn(normaliser(nettoyerTexte(texte).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()))

    /** « Prenez rendez-vous », « réservez votre rdv » : une invitation, pas un rendez-vous fixé. */
    private val INVITATION_RDV = Regex("\\b(prenez|prendre|reservez|reserver|planifiez|planifier|fixez|fixer|programmez|programmer|demandez|demander|convenir d|prise de|prenons|choisissez|choisir) (un |votre |le |vos |d un )?(nouveau )?(rendez vous|rdv)\\b")
    /** Simple bilan / suivi de consommation : informatif, ce n'est pas une facture. */
    private val CONSO_INFO = Regex("\\b(bilan (mensuel |annuel |hebdomadaire )?de (votre )?consommation|suivi de (votre )?consommation|votre conso(mmation)? (du mois|d electricite|de gaz|d eau|en kwh)|estimation de (votre )?consommation|releve de consommation)\\b")

    /**
     * Date (et passage) du rendez-vous : cherchée PRÈS de la formulation du rendez-vous, pas n'importe où dans le
     * message (dates de validité d'une offre, mentions légales « 27 avril 2016 »…). Texte court : tout le message.
     */
    private fun dateRdv(nd: String, ref: LocalDate): Pair<LocalDate, String>? {
        val cles = (RDV_RE.findAll(nd) + RDV_AUTO_RE.findAll(nd) + RENCONTRE_RE.findAll(nd)).map { it.range.first }.toSortedSet()
        // Pour chaque formulation, la date la plus proche (de préférence juste après) ; on garde le couple le plus serré.
        var meilleur: Triple<Int, LocalDate, String>? = null
        for (p in cles) {
            val debut = maxOf(0, p - 70)
            val fen = nd.substring(debut, minOf(nd.length, p + 260))
            val t = DatesFr.jour(fen, ref) ?: continue
            val ou = debut + fen.indexOf(t.texte).coerceAtLeast(0)
            val ecart = if (ou >= p) ou - p else (p - ou) * 2
            if (meilleur == null || ecart < meilleur.first) meilleur = Triple(ecart, t.date, fen)
        }
        meilleur?.let { return it.second to it.third }
        return if (nd.length <= 500) DatesFr.jour(nd, ref)?.let { it.date to nd } else null
    }

    /** Lieu du rendez-vous : une adresse du corps du message, jamais celle du pied de page (siège, mentions légales). */
    private fun lieuRdv(texte: String): String {
        val l = texte.length
        return ADRESSE.findAll(texte).firstOrNull { m -> l < 700 || m.range.first < l * 0.7 }?.value?.trim()?.trimEnd(',', ';', ' ', '<', '(').orEmpty()
    }

    /** Lieu déjà enregistré encore valable avec les règles actuelles (une date prise pour une adresse est retirée). */
    internal fun lieuValide(lieu: String): String = if (lieu.isBlank() || lieu.startsWith("Garage ") || ADRESSE.matches(lieu.trim())) lieu
        else ADRESSE.find(lieu)?.value?.trim().orEmpty()

    /** Montant d'une facture / d'un devis : celui qui suit « montant », « total », « à payer »… ; sinon le plus élevé. */
    private val CONTEXTE_MONTANT = Regex("(montant|total|ttc|ht|a payer|a regler|net a payer|solde|reste a payer|preleve|prelevement|facture|devis|s eleve|d un montant|somme)\\W*(de|du|:|=)?\\W*$")
    private fun montantDocument(t: String): Double? {
        val contextuels = MONTANT.findAll(t).filter { m -> CONTEXTE_MONTANT.containsMatchIn(normaliser(t.substring(maxOf(0, m.range.first - 45), m.range.first))) }
            .mapNotNull { m -> m.groupValues[1].replace(Regex("[ .\\u202F\\u00A0](?=\\d{3}\\b)"), "").replace(',', '.').toDoubleOrNull() }.filter { it > 0 }.toList()
        return contextuels.maxOrNull() ?: montant(t)
    }

    private fun titreRdv(e: Element): String {
        val n = normaliser(e.resume)
        val vehicule = Regex("\\b(kadjar|clio|captur|megane|scenic|twingo|zoe|arkana|austral|espace|308|208|3008|c3|c4|c5|berlingo|corsa|astra|golf|polo|tiguan|yaris|corolla|qashqai)\\b", RegexOption.IGNORE_CASE).find(n)?.value
        val motif = when {
            Regex("\\bvidange\\b").containsMatchIn(n) -> "Vidange"
            Regex("\\b(controle technique|ct)\\b").containsMatchIn(n) -> "Contrôle technique"
            Regex("\\brevision\\b").containsMatchIn(n) -> "Révision"
            Regex("\\b(pneus?|pneumatiques?)\\b").containsMatchIn(n) -> "Pneumatiques"
            else -> "Rendez-vous"
        }
        return if (vehicule != null) vehicule.replaceFirstChar { it.uppercase() } + " — " + motif else if (motif != "Rendez-vous") motif else "Rendez-vous " + e.emetteur
    }

    private fun montant(t: String): Double? = MONTANT.findAll(t).mapNotNull { m ->
        m.groupValues[1].replace(Regex("[ .\\u202F\\u00A0](?=\\d{3}\\b)"), "").replace(',', '.').toDoubleOrNull()
    }.filter { it > 0 }.maxOrNull()

    private fun id(vararg p: String) = p.joinToString("|").hashCode().toUInt().toString(36)

    /**
     * Éléments trouvés dans un message (titre = expéditeur ; texte = objet puis corps pour un mail). Pur : testable.
     * [labels] : étiquettes Gmail (« Promotions » → ignoré).
     */
    fun analyser(titre: String, texte: String, source: String, ts: Long, aujourdhui: LocalDate = LocalDate.now(), labels: List<String> = emptyList()): List<Element> {
        // Identifiant calculé sur le texte brut, comme avant : un message déjà enregistré garde le même identifiant.
        val resumeBrut = texte.replace(Regex("\\s+"), " ").trim().take(220)
        val texte = nettoyerTexte(texte)
        val titre = nettoyerTexte(titre).replace(Regex("\\s+"), " ")
        val brut = "$titre\n$texte".trim()
        if (brut.length < 12) return emptyList()
        val n = normaliser(brut)
        if (SECURITE.containsMatchIn(n)) return emptyList()
        val sujet = texte.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        if (publicite(sujet, "$titre\n$texte", labels)) return emptyList()
        // Référence des dates relatives (« jeudi », « demain ») : le jour où le message a été reçu.
        val ref = if (ts > 0) Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().takeIf { !it.isAfter(aujourdhui) } ?: aujourdhui else aujourdhui
        val emetteur = titre.trim().take(60).ifBlank { source }
        val out = mutableListOf<Element>()
        val resume = resumeDe(texte)

        // Colis
        val t = TRANSPORTEURS.firstOrNull { it.mots.containsMatchIn(n) }
        val indicateurColis = MOTS_COLIS.containsMatchIn(n)
        val numero = (t?.numero?.find(brut) ?: if (indicateurColis && INDICES_EXPEDITION.containsMatchIn(n)) NUMERO_GENERIQUE.find(brut) else null)?.groupValues?.get(1)
        // Un expéditeur marchand (notamment Amazon) ou le mot « commande » ne suffisent pas :
        // il faut un numéro de suivi réel ou une formulation explicite d'expédition/livraison.
        val preuveExpedition = (numero != null && (t != null || INDICES_EXPEDITION.containsMatchIn(n))) || INDICES_EXPEDITION.containsMatchIn(n)
        if (indicateurColis && preuveExpedition && !FACTURE_RE.containsMatchIn(n.replace(Regex("\\bfacture (jointe|disponible)\\b"), ""))) {
            val statut = when {
                Regex("\\b(a ete livre|a ete livree|est livre|est livree|livre aujourd hui a|depose dans (votre|la) boite|remis en main propre|livraison effectuee)\\b").containsMatchIn(n) -> "livré"
                Regex("\\b(disponible (dans|en|au)|a retirer|pret a etre retire|vous attend|en attente de retrait)\\b").containsMatchIn(n) -> "à retirer"
                Regex("\\b(avis de passage|absent|n avons pas pu (vous )?livrer)\\b").containsMatchIn(n) -> "avis de passage"
                Regex("\\b(en cours de livraison|sera livre aujourd hui|livraison aujourd hui|arrive aujourd hui|en tournee)\\b").containsMatchIn(n) -> "en livraison aujourd'hui"
                Regex("\\b(expedie|expediee|pris en charge|en transit|en route|en cours d acheminement|a quitte)\\b").containsMatchIn(n) -> "expédié"
                else -> "suivi"
            }
            val code = CODE_RETRAIT.find(brut)?.groupValues?.get(1).orEmpty()
            val nom = t?.nom ?: "Colis"
            out += Element(id(COLIS, numero ?: "$nom$emetteur"), COLIS, source, emetteur, resume, ts, nom, numero.orEmpty(),
                if (numero != null && t != null) t.lien(numero) else "", statut, code)
            return out
        }
        // Un rendez-vous est reconnu par une formulation explicite, ou par un motif automobile + date + heure.
        // « Prenez rendez-vous », « réservez votre rdv » sont des invitations, pas des rendez-vous.
        val nd = DatesFr.normaliser(brut).replace(INVITATION_RDV, " ")
        val trouve = dateRdv(nd, ref)
        val jourTrouve = trouve?.first
        val heurePresente = HEURE.containsMatchIn(nd)
        val corpsNormalise = normaliser(texte).replace(INVITATION_RDV, " ")
        val contenuPromotionnel = PUB_RDV_RE.containsMatchIn(corpsNormalise)
        val rdvExpliciteDansCorps = RDV_RE.containsMatchIn(corpsNormalise)
        val rdvExplicite = (RDV_RE.containsMatchIn(n.replace(INVITATION_RDV, " ")) && jourTrouve != null) || (rdvExpliciteDansCorps && !contenuPromotionnel)
        val rdvAutomobile = RDV_AUTO_RE.containsMatchIn(corpsNormalise) && jourTrouve != null && heurePresente
        val rdvPersonnel = RENCONTRE_RE.containsMatchIn(corpsNormalise) && jourTrouve != null
        // Long message sans date de rendez-vous : seulement si son objet parle d'un rendez-vous (sinon : document transféré,
        // compte rendu, signature avec adresse…).
        val sansDateAdmis = jourTrouve != null || texte.length <= 600 || RDV_RE.containsMatchIn(normaliser(sujet).replace(INVITATION_RDV, " "))
        if ((rdvExplicite || rdvAutomobile || rdvPersonnel) && sansDateAdmis && !contenuPromotionnel && !FACTURE_RE.containsMatchIn(n) && !DEVIS_RE.containsMatchIn(n)) {
            val jour = jourTrouve?.takeIf { !it.isBefore(ref) }
            val fenetre = trouve?.second ?: nd
            val h = if (jour != null) (HEURE.findAll(fenetre) + HEURE.findAll(nd)).firstOrNull { (it.groupValues[1].toIntOrNull() ?: 99) in 6..21 } else null
            val heure = h?.groupValues?.get(1)?.toIntOrNull()
            val minute = h?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val debut = jour?.atTime(heure ?: 9, minute)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            val lieu = lieuRdv(texte).ifBlank { lieuRdv(titre) }.ifBlank { GARAGE_RDV.find(texte)?.groupValues?.get(1)?.let { "Garage " + it.trim() }.orEmpty() }
            out += Element(id(RDV, source, emetteur, ts.toString(), normaliser(resumeBrut)), RDV, source, emetteur, resume, ts, dateRdv = debut, lieu = lieu,
                statut = when { jour == null -> "date non précisée"; heure == null -> "heure non précisée"; else -> "" })
            return out
        }
        // Facture / devis : le montant est facultatif, car souvent absent de l'aperçu d'un SMS ou d'un mail.
        // Dans un long mail, le mot doit figurer dans l'objet ou le début (pas seulement « vos factures » en pied de page).
        val debutMessage = normaliser(brut.take(450))
        val documentFinancier = (FACTURE_RE.containsMatchIn(n) || DEVIS_RE.containsMatchIn(n)) &&
            (brut.length <= 700 || FACTURE_RE.containsMatchIn(debutMessage) || DEVIS_RE.containsMatchIn(debutMessage)) &&
            !RELEVE_COMPTEUR_RE.containsMatchIn(n) && !FINANCE_HORS_FACTURE_RE.containsMatchIn(n) &&
            !(CONSO_INFO.containsMatchIn(n) && !FACTURE_RE.containsMatchIn(normaliser(sujet)))
        val documentPromotionnel = PROMOTION_RE.containsMatchIn(n) || PUB_RDV_RE.containsMatchIn(n)
        if (documentFinancier && !documentPromotionnel) {
            val m = montantDocument(brut)
            val devis = DEVIS_RE.containsMatchIn(n) && !FACTURE_RE.containsMatchIn(n)
            val ech = Regex("\\b(?:avant le|au plus tard le|date limite(?: de paiement)?|echeance(?: le| du)?|preleve le|sera preleve le|a payer (?:avant )?le|valable jusqu au|le) ?:? ?(.{4,30})").find(DatesFr.normaliser(brut))
                ?.groupValues?.get(1)?.let { DatesFr.jour(DatesFr.normaliser(it), ref)?.date?.toString() }.orEmpty()
            val montantCle = m?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "inconnu"
            out += Element(id(if (devis) DEVIS else FACTURE, source, emetteur, ts.toString(), montantCle, normaliser(resumeBrut)),
                if (devis) DEVIS else FACTURE, source, emetteur, resume, ts, montant = m, echeance = ech)
        }
        return out
    }

    // ------------------------------------------------------------------ stockage

    private fun fichier(c: Context) = File(c.filesDir, "courrier.json")

    @Synchronized
    fun liste(c: Context): List<Element> = runCatching {
        val a = JSONArray(fichier(c).takeIf { it.exists() }?.readText() ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            Element(o.optString("id"), o.optString("type"), o.optString("source"), o.optString("emetteur"), o.optString("resume"), o.optLong("ts"),
                o.optString("transporteur"), o.optString("numero"), o.optString("lien"), o.optString("statut"), o.optString("code"),
                if (o.has("montant")) o.optDouble("montant") else null, o.optString("echeance"), if (o.has("dateRdv")) o.optLong("dateRdv") else null,
                o.optString("lieu"), o.optBoolean("traite"), o.optString("domaine"), o.optBoolean("auto"),
                o.optString("objet"), o.optString("synthese"), o.optBoolean("verifie"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun sauver(c: Context, l: List<Element>) {
        val a = JSONArray()
        // Historique conservé, y compris les éléments traités ; limite technique large.
        l.sortedBy { it.ts }.takeLast(1000).forEach { e ->
            a.put(JSONObject().put("id", e.id).put("type", e.type).put("source", e.source).put("emetteur", e.emetteur).put("resume", e.resume).put("ts", e.ts)
                .put("transporteur", e.transporteur).put("numero", e.numero).put("lien", e.lien).put("statut", e.statut).put("code", e.code)
                .apply { e.montant?.let { put("montant", it) }; e.dateRdv?.let { put("dateRdv", it) } }
                .put("echeance", e.echeance).put("lieu", e.lieu).put("traite", e.traite).put("domaine", e.domaine).put("auto", e.auto)
                .put("objet", e.objet).put("synthese", e.synthese).put("verifie", e.verifie))
        }
        fr.dd.jarvis.data.Ecritures.ecrire(fichier(c)) { a.toString() }
    }

    /**
     * Même message vu deux fois (notification Gmail puis import du mail complet, ou deux imports) : même type, même
     * expéditeur, reçus à moins de 3 jours d'écart, et même numéro de suivi / même début de texte.
     */
    internal fun memeMessage(a: Element, b: Element): Boolean {
        if (a.type != b.type || kotlin.math.abs(a.ts - b.ts) > 3 * 86_400_000L) return false
        // SMS vu par la réception Android (numéro) puis par sa notification (nom du contact) : même texte, même moment.
        if (cleEmetteur(a.emetteur) != cleEmetteur(b.emetteur)) {
            val ra = normaliser(resumeDe(a.resume)); val rb = normaliser(resumeDe(b.resume))
            return kotlin.math.abs(a.ts - b.ts) < 10 * 60_000L && ra.length >= 25 && ra.take(60) == rb.take(60)
        }
        if (a.type == COLIS) return a.numero.isNotBlank() && a.numero == b.numero
        val ra = normaliser(resumeDe(a.resume)); val rb = normaliser(resumeDe(b.resume))
        return ra.length >= 25 && rb.length >= 25 && ra.take(60) == rb.take(60)
    }

    /** Deux lectures du même message : on garde l'élément connu (classement, « traité ») complété par la plus riche. */
    private fun fusion(connu: Element, autre: Element): Element = connu.copy(
        montant = connu.montant ?: autre.montant, echeance = connu.echeance.ifBlank { autre.echeance },
        dateRdv = connu.dateRdv ?: autre.dateRdv, lieu = connu.lieu.ifBlank { autre.lieu },
        statut = if (connu.dateRdv == null && autre.dateRdv != null) autre.statut else connu.statut,
        resume = if (autre.resume.length > connu.resume.length) autre.resume else connu.resume,
        source = if (autre.source.length > connu.source.length) autre.source else connu.source,
        objet = connu.objet.ifBlank { autre.objet }, synthese = connu.synthese.ifBlank { autre.synthese }, verifie = connu.verifie || autre.verifie)

    /** Ajoute / met à jour (même colis : nouvel état) ; renvoie les éléments NOUVEAUX. */
    @Synchronized
    fun enregistrer(c: Context, nouveaux: List<Element>): List<Element> {
        if (nouveaux.isEmpty()) return emptyList()
        val l = liste(c).toMutableList()
        val vraimentNouveaux = mutableListOf<Element>()
        for (e in nouveaux) {
            val i = l.indexOfFirst { it.id == e.id }
            if (i < 0) {
                val j = l.indexOfFirst { memeMessage(it, e) }
                if (j >= 0) { if (e.type != COLIS || e.statut == l[j].statut) l[j] = fusion(l[j], e) else l[j] = l[j].copy(statut = e.statut, ts = maxOf(e.ts, l[j].ts)); continue }
                l += e; vraimentNouveaux += e
            }
            else if (e.type == COLIS && e.statut != l[i].statut) l[i] = l[i].copy(statut = e.statut, ts = e.ts, code = e.code.ifBlank { l[i].code }, resume = e.resume,
                traite = if (e.statut == "livré") l[i].traite else false, domaine = l[i].domaine.ifBlank { e.domaine }, auto = if (l[i].domaine.isBlank()) e.auto else l[i].auto)
            else if (e.type != COLIS && !l[i].verifie) l[i] = e.copy(traite = l[i].traite, domaine = l[i].domaine.ifBlank { e.domaine }, auto = if (l[i].domaine.isBlank()) e.auto else l[i].auto)
        }
        sauver(c, l)
        return vraimentNouveaux
    }

    /** Remplace un élément (vérification par l'IA) ; null le retire. */
    @Synchronized
    fun remplacer(c: Context, id: String, e: Element?): Boolean {
        val l = liste(c)
        if (l.none { it.id == id }) return false
        sauver(c, if (e == null) l.filter { it.id != id } else l.map { if (it.id == id) e.copy(id = id, traite = it.traite, domaine = it.domaine.ifBlank { e.domaine }, auto = if (it.domaine.isBlank()) e.auto else it.auto) else it })
        return true
    }

    /**
     * Réévalue les anciens éléments déjà enregistrés avec les critères actuels.
     * Cela retire les anciennes publicités qui avaient été classées à tort, sans effacer les éléments valides.
     */
    /** Un élément déjà enregistré, revu avec les règles actuelles : corrigé, gardé tel quel, ou null (à retirer). Pur. */
    internal fun revalider(brut: Element): Element? {
        // Texte et lieu remis au propre (entités HTML, liens ; « 27 avril 2016 » pris pour une adresse).
        val ancien = brut.copy(resume = resumeDe(brut.resume), lieu = lieuValide(brut.lieu))
        if (ancien.verifie) return ancien
        val tout = ancien.emetteur + " " + ancien.resume
        if (publicite(ancien.resume, tout)) return null
        val actuel = analyser(ancien.emetteur, ancien.resume, ancien.source, ancien.ts)
            .firstOrNull { it.type == ancien.type }
        val n = normaliser(tout)
        return when {
            actuel != null -> actuel.copy(id = ancien.id, traite = ancien.traite, domaine = ancien.domaine, auto = ancien.auto,
                dateRdv = actuel.dateRdv ?: ancien.dateRdv, lieu = actuel.lieu.ifBlank { ancien.lieu },
                statut = if (actuel.dateRdv == null && ancien.dateRdv != null) ancien.statut else actuel.statut)
            PROMOTION_RE.containsMatchIn(n) -> null
            ancien.type == FACTURE && (RELEVE_COMPTEUR_RE.containsMatchIn(n) || CONSO_INFO.containsMatchIn(n)) -> null
            (ancien.type == FACTURE || ancien.type == DEVIS) && FINANCE_HORS_FACTURE_RE.containsMatchIn(n) -> null
            ancien.type == RDV && PUB_RDV_RE.containsMatchIn(n) -> null
            // Rendez-vous sans date dont l'aperçu ne parle même pas de rendez-vous : document transféré, signature avec
            // adresse… retiré.
            ancien.type == RDV && ancien.dateRdv == null && ancien.resume.length >= 120 && !RDV_RE.containsMatchIn(normaliser(ancien.resume.take(120))) -> null
            // Le résumé stocké est tronqué à 220 caractères : on conserve donc tout élément qu'on ne peut pas
            // revalider avec certitude, plutôt que de supprimer un vrai rendez-vous dont le détail est plus loin.
            else -> ancien
        }
    }

    @Synchronized
    fun revaliderHistorique(c: Context): Int {
        val anciens = liste(c)
        val valides = anciens.mapNotNull { revalider(it) }
        // Doublons (même message vu par la notification puis par l'import) fusionnés.
        val uniques = mutableListOf<Element>()
        valides.sortedBy { it.ts }.forEach { e -> val j = uniques.indexOfFirst { memeMessage(it, e) }; if (j >= 0) uniques[j] = fusion(uniques[j], e) else uniques += e }
        val retires = anciens.size - uniques.size
        if (retires > 0 || uniques != anciens.sortedBy { it.ts }) sauver(c, uniques)
        return retires
    }

    @Synchronized
    fun marquer(c: Context, id: String, traite: Boolean = true): Boolean {
        val l = liste(c)
        if (l.none { it.id == id }) return false
        sauver(c, l.map { if (it.id == id) it.copy(traite = traite) else it }); return true
    }

    @Synchronized
    fun supprimer(c: Context, id: String): Boolean {
        val l = liste(c)
        if (l.none { it.id == id }) return false
        sauver(c, l.filter { it.id != id }); return true
    }

    // ------------------------------------------------------------------ société ou perso

    private fun fichierRegles(c: Context) = File(c.filesDir, "courrier_regles.json")
    /** Classements appris : expéditeur (normalisé) → société / perso. */
    fun regles(c: Context): Map<String, String> = runCatching {
        val o = JSONObject(fichierRegles(c).takeIf { it.exists() }?.readText() ?: "{}")
        o.keys().asSequence().associateWith { o.optString(it) }
    }.getOrDefault(emptyMap())

    internal fun cleEmetteur(e: String) = normaliser(e).replace(Regex("\\b(noreply|no reply|ne pas repondre|info|contact|service client|notification|notifications)\\b"), " ")
        .replace(Regex("\\s+"), " ").trim().take(40)

    /**
     * Société ou perso, sans IA : un client de la Société (nom, mail ou téléphone dans le message), un numéro de devis /
     * facture de la Société cité (réponse à un document envoyé), la boîte mail de la Société, ou un classement déjà fait
     * pour cet expéditeur. Sinon : à classer.
     */
    internal fun domaine(e: Element, compte: String, data: fr.dd.jarvis.societe.core.SocieteData?, regles: Map<String, String>): Pair<String, Boolean> {
        regles[cleEmetteur(e.emetteur)]?.takeIf { it == SOCIETE || it == PERSO }?.let { return it to true }
        if (data == null) return "" to false
        val mailSociete = data.societe.email.trim().lowercase()
        if (mailSociete.isNotBlank() && compte.trim().lowercase() == mailSociete) return SOCIETE to true
        val texte = "${e.emetteur} ${e.resume}"
        val n = " " + normaliser(texte) + " "
        val chiffres = texte.filter(Char::isDigit)
        val client = data.clients.any { c ->
            val nom = normaliser(c.nom)
            (nom.length >= 4 && n.contains(" $nom ")) ||
                (c.email.isNotBlank() && texte.contains(c.email.trim(), true)) ||
                (c.tel.filter(Char::isDigit).takeLast(9).let { it.length == 9 && chiffres.contains(it) })
        }
        if (client) return SOCIETE to true
        if (data.docs.any { d -> d.numero.length >= 5 && texte.contains(d.numero, true) }) return SOCIETE to true
        return "" to false
    }

    /** Classe un élément (et retient le choix pour les prochains messages du même expéditeur). */
    @Synchronized
    fun classer(c: Context, id: String, domaine: String, retenir: Boolean = true): Boolean {
        val l = liste(c)
        val e = l.firstOrNull { it.id == id } ?: return false
        val cle = cleEmetteur(e.emetteur)
        sauver(c, l.map {
            when {
                it.id == id -> it.copy(domaine = domaine, auto = false)
                retenir && domaine.isNotBlank() && it.domaine.isBlank() && cleEmetteur(it.emetteur) == cle -> it.copy(domaine = domaine, auto = true)
                else -> it
            }
        })
        if (retenir && cle.length >= 3) runCatching {
            val o = JSONObject(fichierRegles(c).takeIf { it.exists() }?.readText() ?: "{}")
            if (domaine.isBlank()) o.remove(cle) else o.put(cle, domaine)
            fr.dd.jarvis.data.Ecritures.ecrire(fichierRegles(c)) { o.toString() }
        }
        return true
    }

    // ------------------------------------------------------------------ arrivée d'un message

    /** Au plus autant de lectures par l'IA à chaque rattrapage (les suivantes se font au fil des messages). */
    private const val IA_PAR_IMPORT = 15

    /**
     * Éléments relus par l'IA sur leur message complet : les nouveaux, puis ceux déjà enregistrés mais jamais vérifiés
     * (à traiter) dont le message vient d'être relu. Renvoie le nombre de NOUVEAUX finalement gardés.
     */
    private suspend fun verifierNouveaux(app: JarvisApp, nouveaux: List<Element>, trouves: List<Element>, textes: Map<String, Pair<String, String>>): Int {
        val idsNouveaux = nouveaux.map { it.id }.toSet()
        val stock = liste(app).associateBy { it.id }
        val anciens = trouves.mapNotNull { stock[it.id] }.filter { it.id !in idsNouveaux && !it.verifie && !it.traite }.distinctBy { it.id }
        var gardes = nouveaux.size
        (nouveaux.sortedByDescending { it.ts } + anciens.sortedByDescending { it.ts }).take(IA_PAR_IMPORT).forEach { e ->
            val (titre, texte) = textes[e.id] ?: return@forEach
            if (runCatching { CourrierIA.verifier(app, e, titre, texte) }.getOrDefault(e) == null && e.id in idsNouveaux) gardes--
        }
        return gardes
    }

    /** Bilan d'un rattrapage des SMS : lus, repérés, nouveaux, et ce qui empêche de tout lire. */
    data class ImportSms(val lus: Int, val reperes: Int, val nouveaux: Int, val probleme: String = "") {
        fun texte(): String = if (probleme.isNotBlank() && lus == 0) "SMS : $probleme"
            else "$lus SMS lus (90 jours), $reperes repéré(s) dont $nouveaux nouveau(x)" + (if (probleme.isNotBlank()) " — $probleme" else "")
    }

    /** Ce qui empêche JARVIS de lire les SMS (autorisation Android, « SMS de notification » d'HyperOS), ou "". */
    fun problemeSms(c: Context): String = when {
        c.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED ->
            "lecture des SMS non autorisée (Réglages › Téléphone › Contrôle › Autoriser les SMS)"
        Xiaomi.smsNotification(c) == false ->
            "HyperOS cache les SMS de service (colis, rendez-vous, factures) : autorise « SMS de notification » dans Autres autorisations de JARVIS"
        else -> ""
    }

    /** Rattrapage des SMS déjà présents dans la boîte de réception. Seuls les messages reconnus sont conservés. */
    suspend fun importerSmsRecents(app: JarvisApp): ImportSms {
        if (!app.store.courrierAnalyse.value) return ImportSms(0, 0, 0, "analyse des SMS et mails désactivée (Réglages › Analyse des SMS et mails)")
        val probleme = problemeSms(app)
        if (app.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return ImportSms(0, 0, 0, probleme)
        val depuis = System.currentTimeMillis() - 90L * 86_400_000L
        val trouves = mutableListOf<Element>()
        val textes = HashMap<String, Pair<String, String>>()
        val noms = HashMap<String, String>()
        var lus = 0
        runCatching {
            app.contentResolver.query(android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(android.provider.Telephony.Sms.ADDRESS, android.provider.Telephony.Sms.BODY, android.provider.Telephony.Sms.DATE),
                "${android.provider.Telephony.Sms.DATE} >= ?", arrayOf(depuis.toString()), "${android.provider.Telephony.Sms.DATE} DESC")?.use { cursor ->
                val ia = cursor.getColumnIndex(android.provider.Telephony.Sms.ADDRESS)
                val ib = cursor.getColumnIndex(android.provider.Telephony.Sms.BODY)
                val idate = cursor.getColumnIndex(android.provider.Telephony.Sms.DATE)
                while (cursor.moveToNext() && lus < 1000) {
                    lus++
                    val corps = if (ib >= 0) cursor.getString(ib).orEmpty() else ""
                    val numero = if (ia >= 0) cursor.getString(ia).orEmpty() else "SMS"
                    // Le nom du contact plutôt que son numéro (comme dans la notification) : même expéditeur reconnu.
                    val adresse = noms.getOrPut(numero) { runCatching { Telephony.nomDe(app, numero) }.getOrNull() ?: numero }
                    val date = if (idate >= 0) cursor.getLong(idate) else System.currentTimeMillis()
                    if (corps.isNotBlank()) trouves += analyser(adresse, corps, "SMS", date).map { e ->
                        textes[e.id] = adresse to corps
                        domaine(e, "", runCatching { app.societe.data }.getOrNull(), regles(app)).let { (d, auto) -> e.copy(domaine = d, auto = auto) }
                    }
                }
            }
        }
        val nouveaux = verifierNouveaux(app, enregistrer(app, trouves), trouves, textes)
        return ImportSms(lus, trouves.size, nouveaux, probleme)
    }

    /**
     * Éléments déjà enregistrés (à traiter) jamais relus par l'IA, hors colis : relus sur leur aperçu, au plus [max]
     * par ouverture. Retire les publicités que les règles laissaient passer. Renvoie le nombre d'éléments retirés.
     */
    suspend fun reverifierAnciens(app: JarvisApp, max: Int = 10): Int {
        if (!app.store.courrierAnalyse.value || !app.store.courrierIA.value) return 0
        var retires = 0
        liste(app).filter { !it.verifie && !it.traite && it.type != COLIS }.sortedByDescending { it.ts }.take(max).forEach { e ->
            if (runCatching { CourrierIA.verifier(app, e, e.emetteur, e.resume) }.getOrDefault(e) == null) retires++
        }
        return retires
    }

    /**
     * Rattrapage des courriels des comptes actifs (Gmail ou IMAP), même si Jarvis n'a pas vu leur notification.
     * On analyse le sujet et le corps, pas seulement l'aperçu tronqué de la notification ; les mails classés
     * « Promotions » / « Réseaux sociaux » par Gmail sont ignorés.
     */
    suspend fun importerMailsRecents(app: JarvisApp, nombre: Int = 200): Int {
        if (!app.store.courrierAnalyse.value) return 0
        return run {
            val mails = app.mail.fetchRecent(nombre, unreadOnly = false, full = true, everywhere = true)
            val data = runCatching { app.societe.data }.getOrNull()
            val regles = regles(app)
            val textes = HashMap<String, Pair<String, String>>()
            val trouves = mails.flatMap { mail ->
                val emetteur = mail.from.ifBlank { mail.fromAddr }.ifBlank { "Expéditeur inconnu" }
                val contenu = listOf(mail.subject, mail.body.ifBlank { mail.snippet }).filter { it.isNotBlank() }.joinToString("\n")
                val labels = if (expediteurMarketing(mail.fromAddr)) mail.labels + EXPEDITEUR_MARKETING else mail.labels
                analyser(emetteur, contenu, "Mail", mail.date?.time ?: 0L, labels = labels).map { e ->
                    textes[e.id] = emetteur to contenu
                    domaine(e, mail.account, data, regles).let { (d, auto) -> e.copy(domaine = d, auto = auto) }
                }
            }
            verifierNouveaux(app, enregistrer(app, trouves), trouves, textes)
        }
    }

    /** SMS / notification de mail reçus : analysés en arrière-plan, sans jamais bloquer ; relus par l'IA avant d'avertir. */
    fun recu(app: JarvisApp, source: String, titre: String, texte: String, ts: Long = System.currentTimeMillis(), compte: String = "", labels: List<String> = emptyList()) {
        if (!app.store.courrierAnalyse.value) return
        Thread {
            runCatching {
                val data = runCatching { app.societe.data }.getOrNull()
                val regles = regles(app)
                val trouves = analyser(titre, texte, source, ts, labels = labels).map { e -> domaine(e, compte, data, regles).let { (d, a) -> e.copy(domaine = d, auto = a) } }
                val nouveaux = enregistrer(app, trouves)
                val finaux = nouveaux.mapNotNull { e -> kotlinx.coroutines.runBlocking { runCatching { CourrierIA.verifier(app, e, titre, texte) }.getOrDefault(e) } }
                if (app.store.courrierNotif.value) finaux.forEach { notifier(app, it) }
            }
        }.apply { isDaemon = true }.start()
    }

    private const val CANAL = "courrier_jarvis"

    private fun notifier(app: JarvisApp, e: Element) {
        val (titre, demande) = when (e.type) {
            RDV -> {
                // Déjà dans l'agenda (même jour, même heure) : rien à proposer.
                val deja = runCatching { app.calendar.hasPermission() && e.dateRdv != null &&
                    app.calendar.events(Instant.ofEpochMilli(e.dateRdv).atZone(ZoneId.systemDefault()).toLocalDate(), 1).any { kotlin.math.abs(it.begin - e.dateRdv) < 31 * 60_000L } }.getOrDefault(false)
                if (deja) return
                "📅 ${e.objet.ifBlank { "Rendez-vous" }} : ${quand(e.dateRdv)}" to "Ajoute à mon agenda : rendez-vous « ${e.objet.ifBlank { e.emetteur }} » ${quand(e.dateRdv)}" + (if (e.lieu.isNotBlank()) " à ${e.lieu}" else "") +
                    agendaRetenu(app, e).substringAfter('\t').let { if (it.isNotBlank()) " dans l'agenda « $it »" else "" } + " (détecté dans un message reçu)."
            }
            FACTURE -> "🧾 Facture ${e.emetteur} : ${euros(e.montant)}${if (e.echeance.isNotBlank()) " avant le ${dateFr(e.echeance)}" else ""}${etiquette(e)}" to
                if (e.domaine == PERSO) null else "Ajoute une note de frais de ${euros(e.montant)} chez ${e.emetteur}${if (e.echeance.isNotBlank()) " (facture à payer avant le ${dateFr(e.echeance)})" else ""}."
            DEVIS -> "📄 Devis reçu de ${e.emetteur} : ${euros(e.montant)}${etiquette(e)}" to null
            else -> return
        }
        if (Build.VERSION.SDK_INT >= 26) app.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CANAL, "Messages importants (colis, factures, rendez-vous)", NotificationManager.IMPORTANCE_DEFAULT))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val code = 7900 + (e.id.hashCode() and 0x3FF)
        val b = NotificationCompat.Builder(app, CANAL).setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle(titre)
            .setContentText(e.synthese.ifBlank { e.resume }.take(120)).setStyle(NotificationCompat.BigTextStyle().bigText(e.synthese.ifBlank { e.resume })).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(app, code, Intent(app, fr.dd.jarvis.MainActivity::class.java).putExtra("terrain", "courrier").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags))
        // À classer : Société ou Perso directement depuis la notification.
        if (e.domaine.isBlank()) listOf(SOCIETE to "🏢 Société", PERSO to "🏠 Perso").forEachIndexed { i, (d, libelle) ->
            b.addAction(0, libelle, PendingIntent.getBroadcast(app, code + 2 + i, Intent(app, CourrierReceiver::class.java).setAction(ACT_CLASSER)
                .putExtra("id", e.id).putExtra("domaine", d).putExtra("notif", code), flags))
        }
        if (demande != null) b.addAction(0, if (e.type == RDV) "📅 Ajouter à l'agenda" else "💶 En note de frais", PendingIntent.getActivity(app, code + 1,
            Intent(app, fr.dd.jarvis.MainActivity::class.java).putExtra("demande", demande).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags))
        runCatching { NotificationManagerCompat.from(app).notify(code, b.build()) }
    }

    const val ACT_CLASSER = "fr.dd.jarvis.COURRIER_CLASSER"

    // ------------------------------------------------------------------ présentation

    fun etiquette(e: Element) = when (e.domaine) { SOCIETE -> " · 🏢 Société"; PERSO -> " · 🏠 Perso"; else -> "" }

    private fun euros(m: Double?) = m?.let { String.format(java.util.Locale.FRANCE, "%,.2f €", it).replace(' ', ' ') } ?: "montant inconnu"
    private fun dateFr(iso: String) = runCatching { DatesFr.libelle(LocalDate.parse(iso), LocalDate.now()) }.getOrDefault(iso)
    private fun quand(ms: Long?): String = ms?.let {
        val z = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
        DatesFr.libelle(z.toLocalDate(), LocalDate.now()) + " à ${z.hour} h${if (z.minute > 0) "%02d".format(z.minute) else ""}"
    } ?: "date inconnue"

    fun ligne(e: Element): String = when (e.type) {
        COLIS -> "📦 ${e.transporteur}${if (e.numero.isNotBlank()) " ${e.numero}" else ""} — ${e.statut}${if (e.code.isNotBlank()) " · code de retrait ${e.code}" else ""} (${e.emetteur})" +
            if (e.lien.isNotBlank()) " · [suivi](${e.lien})" else ""
        FACTURE -> "🧾 ${e.emetteur} — ${euros(e.montant)}${if (e.echeance.isNotBlank()) " · avant le ${dateFr(e.echeance)}" else ""}"
        DEVIS -> "📄 Devis ${e.emetteur} — ${euros(e.montant)}"
        RDV -> "📅 ${e.objet.ifBlank { titreRdv(e) }} — ${quand(e.dateRdv)}${if (e.lieu.isNotBlank()) " · ${e.lieu}" else ""}"
        else -> e.resume
    }

    /** Colis suivis (non traités ou livrés dans les 2 derniers jours). */
    fun resumeColis(c: Context): String = liste(c).filter { it.type == COLIS && (!it.traite && (it.statut != "livré" || System.currentTimeMillis() - it.ts < 2 * 86_400_000L)) }
        .sortedByDescending { it.ts }.joinToString("\n") { "- " + ligne(it) + etiquette(it) }

    /** Factures, devis et rendez-vous reçus non traités. */
    fun resumeATraiter(c: Context): String = liste(c).filter { !it.traite && it.type != COLIS && (it.type != RDV || it.dateRdv == null || it.dateRdv > System.currentTimeMillis()) }
        .sortedByDescending { it.ts }.take(12).joinToString("\n") { "- " + ligne(it) + etiquette(it) }

    // ------------------------------------------------------------------ demandes

    sealed class Intention { object Colis : Intention(); object ATraiter : Intention(); object AjouterRdv : Intention() }

    private fun esp(m: String) = m.replace(" ?", "\\s*").replace(" ", "\\s+")
    private val R_COLIS = Regex(esp("^\\s*(?:(?:jarvis|dis moi|stp) )*(?:(?:ou en (?:est|sont)|ou sont|ou est|suivi(?: de)?|statut de|montre(?: moi)?|liste|quels sont|j ai) )?(?:mes |mon |le |les |des )?(?:colis|livraisons?|commandes? en cours)(?: (?:en cours|a venir|attendus?|en attente))?(?: stp)?\\s*$"))
    private val R_A_TRAITER = Regex(esp("^\\s*(?:(?:jarvis|dis moi) )*(?:qu est ce que j ai recu d important|qu ai je recu d important|qu est ce qui est arrive d important|(?:mes |les )?(?:documents|messages|courriers?) (?:recus|importants|a traiter)|(?:mes |les )?factures (?:recues|a payer|fournisseurs)|qu est ce que j ai a traiter|ce que j ai a traiter)(?: stp)?\\s*$"))
    private val R_AJOUTER_RDV = Regex(esp("^\\s*(?:(?:jarvis|oui|ok) )*(?:ajoute|mets|enregistre|inscris)(?: le| ce)? (?:rendez vous|rdv)(?: (?:recu|du sms|du message|du mail|detecte))? (?:a|dans) (?:mon |l |mes )?(?:agenda|calendrier|planning)\\s*$"))

    fun intention(texte: String): Intention? {
        val p = CommandesDirectes.plat(texte.trim())
        return when {
            R_COLIS.matches(p) -> Intention.Colis
            R_A_TRAITER.matches(p) -> Intention.ATraiter
            R_AJOUTER_RDV.matches(p) -> Intention.AjouterRdv
            else -> null
        }
    }

    /** Réponse à « mes colis » / « qu'ai-je reçu d'important » ; « ajoute le rendez-vous reçu » → (demande pour create_event) via [ajouterRdv]. */
    fun texte(c: Context, i: Intention): String = when (i) {
        Intention.Colis -> resumeColis(c).ifBlank { "📦 Aucun colis en cours repéré dans tes SMS et mails." }.let { if (it.startsWith("📦 Aucun")) it else "📦 Tes colis :\n$it" }
        Intention.ATraiter -> resumeATraiter(c).ifBlank { "📬 Rien d'important à traiter dans les messages reçus (factures, devis, rendez-vous)." }.let { if (it.startsWith("📬 Rien")) it else "📬 Reçu à traiter :\n$it\n\n(Onglet Société › Reçus pour les marquer traités.)" }
        Intention.AjouterRdv -> ""
    }

    /** Dernier rendez-vous détecté, à venir et non traité. */
    fun dernierRdv(c: Context): Element? = liste(c).filter { it.type == RDV && !it.traite && (it.dateRdv ?: 0) > System.currentTimeMillis() }.maxByOrNull { it.ts }

    /** Agenda retenu (« clé⇥nom », voir AgendasCourrier) pour un élément selon son classement (Société / Perso / à classer), "" sinon. */
    fun agendaRetenu(app: JarvisApp, e: Element): String = when (e.domaine) {
        SOCIETE -> app.store.courrierAgendaSociete.value; PERSO -> app.store.courrierAgendaPerso.value; else -> app.store.courrierAgendaAutre.value
    }

    /** Retient l'agenda choisi pour les prochains rendez-vous du même classement. */
    fun retenirAgenda(app: JarvisApp, e: Element, agenda: String) = when (e.domaine) {
        SOCIETE -> app.store.courrierAgendaSociete.set(agenda); PERSO -> app.store.courrierAgendaPerso.set(agenda); else -> app.store.courrierAgendaAutre.set(agenda)
    }

    fun argsEvenement(e: Element, debutChoisi: java.time.LocalDateTime? = null, agenda: String = ""): JSONObject {
        val debut = debutChoisi ?: Instant.ofEpochMilli(e.dateRdv ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalDateTime()
        return JSONObject().put("title", e.objet.ifBlank { titreRdv(e) }).apply { if (agenda.isNotBlank()) put("calendar", agenda) }.put("start", debut.toString().take(16)).put("end", debut.plusHours(1).toString().take(16))
            .put("location", e.lieu).put("description", "Détecté par JARVIS dans un message reçu (${e.emetteur}) :\n${e.synthese.ifBlank { e.resume }}")
    }
}

/** « 🏢 Société » / « 🏠 Perso » touché dans la notification d'un élément reçu. */
class CourrierReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Courrier.ACT_CLASSER) return
        val id = intent.getStringExtra("id") ?: return
        runCatching { Courrier.classer(context, id, intent.getStringExtra("domaine").orEmpty()) }
        runCatching { NotificationManagerCompat.from(context).cancel(intent.getIntExtra("notif", 0)) }
    }
}
