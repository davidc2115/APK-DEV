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
    private val ADRESSE = Regex("(\\d{1,4}(?: ?bis| ?ter)?,? (?:rue|avenue|av\\.?|bd|boulevard|chemin|place|allee|allée|impasse|route|quai|cours|faubourg|zone|za|zi|lieu[- ]dit)[^,\\n.]{2,60}(?:,? ?\\d{5} [A-Za-zÀ-ÿ' -]{2,40})?)", RegexOption.IGNORE_CASE)
    private val GARAGE_RDV = Regex("\\bgarage\\s+([A-ZÀ-ÿ][A-Za-zÀ-ÿ'’-]*(?:\\s+[A-ZÀ-ÿ][A-Za-zÀ-ÿ'’-]*){0,2})", RegexOption.IGNORE_CASE)

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

    /** Éléments trouvés dans un message (titre = expéditeur ou objet). Pur : testable. */
    fun analyser(titre: String, texte: String, source: String, ts: Long, aujourdhui: LocalDate = LocalDate.now()): List<Element> {
        val brut = "$titre\n$texte".trim()
        if (brut.length < 12) return emptyList()
        val n = normaliser(brut)
        if (SECURITE.containsMatchIn(n)) return emptyList()
        val emetteur = titre.trim().take(60).ifBlank { source }
        val out = mutableListOf<Element>()
        val resume = texte.replace(Regex("\\s+"), " ").trim().take(220)

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
        val nd = DatesFr.normaliser(brut)
        val jourTrouve = DatesFr.jour(nd, aujourdhui)?.date
        val heurePresente = HEURE.containsMatchIn(nd)
        val corpsNormalise = normaliser(texte)
        val contenuPromotionnel = PUB_RDV_RE.containsMatchIn(corpsNormalise)
        val rdvExpliciteDansCorps = RDV_RE.containsMatchIn(corpsNormalise)
        val rdvExplicite = rdvExpliciteDansCorps && (jourTrouve != null || !contenuPromotionnel)
        val rdvAutomobile = RDV_AUTO_RE.containsMatchIn(corpsNormalise) && jourTrouve != null && heurePresente
        val rdvPersonnel = RENCONTRE_RE.containsMatchIn(corpsNormalise) && jourTrouve != null
        if ((rdvExplicite || rdvAutomobile || rdvPersonnel) && !contenuPromotionnel && !FACTURE_RE.containsMatchIn(n) && !DEVIS_RE.containsMatchIn(n)) {
            val jourTrouve = DatesFr.jour(nd, aujourdhui)?.date
            val jour = jourTrouve?.takeIf { !it.isBefore(aujourdhui) }
            val h = if (jour != null) HEURE.findAll(nd).firstOrNull { (it.groupValues[1].toIntOrNull() ?: 99) in 6..21 } else null
            val heure = h?.groupValues?.get(1)?.toIntOrNull()
            val minute = h?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val debut = jour?.atTime(heure ?: 9, minute)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            val lieu = ADRESSE.find(brut)?.value?.trim().orEmpty().ifBlank { GARAGE_RDV.find(texte)?.groupValues?.get(1)?.let { "Garage " + it.trim() }.orEmpty() }
            out += Element(id(RDV, source, emetteur, ts.toString(), normaliser(resume)), RDV, source, emetteur, resume, ts, dateRdv = debut, lieu = lieu,
                statut = when { jour == null -> "date non précisée"; heure == null -> "heure non précisée"; else -> "" })
            return out
        }
        // Facture / devis : le montant est facultatif, car souvent absent de l'aperçu d'un SMS ou d'un mail.
        val m = montant(brut)
        val documentFinancier = (FACTURE_RE.containsMatchIn(n) || DEVIS_RE.containsMatchIn(n)) &&
            !RELEVE_COMPTEUR_RE.containsMatchIn(n) && !FINANCE_HORS_FACTURE_RE.containsMatchIn(n)
        val contenuPromotionnel = PROMOTION_RE.containsMatchIn(n) || PUB_RDV_RE.containsMatchIn(n)
        if (documentFinancier && !contenuPromotionnel) {
            val devis = DEVIS_RE.containsMatchIn(n) && !FACTURE_RE.containsMatchIn(n)
            val ech = Regex("\\b(?:avant le|au plus tard le|date limite(?: de paiement)?|echeance(?: le| du)?|preleve le|sera preleve le|a payer (?:avant )?le|valable jusqu au|le) ?:? ?(.{4,30})").find(DatesFr.normaliser(brut))
                ?.groupValues?.get(1)?.let { DatesFr.jour(DatesFr.normaliser(it), aujourdhui)?.date?.toString() }.orEmpty()
            val montantCle = m?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "inconnu"
            out += Element(id(if (devis) DEVIS else FACTURE, source, emetteur, ts.toString(), montantCle, normaliser(resume)),
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
                o.optString("lieu"), o.optBoolean("traite"), o.optString("domaine"), o.optBoolean("auto"))
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
                .put("echeance", e.echeance).put("lieu", e.lieu).put("traite", e.traite).put("domaine", e.domaine).put("auto", e.auto))
        }
        fr.dd.jarvis.data.Ecritures.ecrire(fichier(c)) { a.toString() }
    }

    /** Ajoute / met à jour (même colis : nouvel état) ; renvoie les éléments NOUVEAUX. */
    @Synchronized
    fun enregistrer(c: Context, nouveaux: List<Element>): List<Element> {
        if (nouveaux.isEmpty()) return emptyList()
        val l = liste(c).toMutableList()
        val vraimentNouveaux = mutableListOf<Element>()
        for (e in nouveaux) {
            val i = l.indexOfFirst { it.id == e.id }
            if (i < 0) { l += e; vraimentNouveaux += e }
            else if (e.type == COLIS && e.statut != l[i].statut) l[i] = l[i].copy(statut = e.statut, ts = e.ts, code = e.code.ifBlank { l[i].code }, resume = e.resume,
                traite = if (e.statut == "livré") l[i].traite else false, domaine = l[i].domaine.ifBlank { e.domaine }, auto = if (l[i].domaine.isBlank()) e.auto else l[i].auto)
            else if (e.type != COLIS) l[i] = e.copy(traite = l[i].traite, domaine = l[i].domaine.ifBlank { e.domaine }, auto = if (l[i].domaine.isBlank()) e.auto else l[i].auto)
        }
        sauver(c, l)
        return vraimentNouveaux
    }

    /**
     * Réévalue les anciens éléments déjà enregistrés avec les critères actuels.
     * Cela retire les anciennes publicités qui avaient été classées à tort, sans effacer les éléments valides.
     */
    @Synchronized
    fun revaliderHistorique(c: Context): Int {
        val anciens = liste(c)
        val valides = anciens.mapNotNull { ancien ->
            val actuel = analyser(ancien.emetteur, ancien.resume, ancien.source, ancien.ts)
                .firstOrNull { it.type == ancien.type }
            when {
                actuel != null -> actuel.copy(id = ancien.id, traite = ancien.traite, domaine = ancien.domaine, auto = ancien.auto,
                    dateRdv = actuel.dateRdv ?: ancien.dateRdv, lieu = actuel.lieu.ifBlank { ancien.lieu },
                    statut = if (actuel.dateRdv == null && ancien.dateRdv != null) ancien.statut else actuel.statut)
                PROMOTION_RE.containsMatchIn(normaliser(ancien.emetteur + " " + ancien.resume)) -> null
                // Le résumé stocké est tronqué à 220 caractères : on conserve donc tout élément qu'on ne peut pas
                // revalider avec certitude, plutôt que de supprimer un vrai rendez-vous dont le détail est plus loin.
                else -> ancien
            }
        }
        val retires = anciens.size - valides.size
        if (retires > 0 || valides.size != anciens.size) sauver(c, valides)
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

    /** Rattrapage des SMS déjà présents dans la boîte de réception. Seuls les messages reconnus sont conservés. */
    fun importerSmsRecents(app: JarvisApp): Int {
        if (!app.store.courrierAnalyse.value || app.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return 0
        val depuis = System.currentTimeMillis() - 90L * 86_400_000L
        val trouves = mutableListOf<Element>()
        runCatching {
            app.contentResolver.query(android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(android.provider.Telephony.Sms.ADDRESS, android.provider.Telephony.Sms.BODY, android.provider.Telephony.Sms.DATE),
                "${android.provider.Telephony.Sms.DATE} >= ?", arrayOf(depuis.toString()), "${android.provider.Telephony.Sms.DATE} DESC")?.use { cursor ->
                val ia = cursor.getColumnIndex(android.provider.Telephony.Sms.ADDRESS)
                val ib = cursor.getColumnIndex(android.provider.Telephony.Sms.BODY)
                val idate = cursor.getColumnIndex(android.provider.Telephony.Sms.DATE)
                var lus = 0
                while (cursor.moveToNext() && lus++ < 1000) {
                    val corps = if (ib >= 0) cursor.getString(ib).orEmpty() else ""
                    val adresse = if (ia >= 0) cursor.getString(ia).orEmpty() else "SMS"
                    val date = if (idate >= 0) cursor.getLong(idate) else System.currentTimeMillis()
                    if (corps.isNotBlank()) trouves += analyser(adresse, corps, "SMS", date).map { e ->
                        domaine(e, "", runCatching { app.societe.data }.getOrNull(), regles(app)).let { (d, auto) -> e.copy(domaine = d, auto = auto) }
                    }
                }
            }
        }
        return enregistrer(app, trouves).size
    }
    /**
     * Rattrapage des courriels des comptes actifs (Gmail ou IMAP), même si Jarvis n'a pas vu leur notification.
     * On analyse le sujet et le corps, pas seulement l'aperçu tronqué de la notification.
     */
    suspend fun importerMailsRecents(app: JarvisApp, nombre: Int = 200): Int {
        if (!app.store.courrierAnalyse.value) return 0
        return run {
            val mails = app.mail.fetchRecent(nombre, unreadOnly = false, full = true, everywhere = true)
            val data = runCatching { app.societe.data }.getOrNull()
            val regles = regles(app)
            val trouves = mails.flatMap { mail ->
                val emetteur = mail.from.ifBlank { mail.fromAddr }.ifBlank { "Expéditeur inconnu" }
                val contenu = listOf(mail.subject, mail.body.ifBlank { mail.snippet }).filter { it.isNotBlank() }.joinToString("\n")
                analyser(emetteur, contenu, "Mail", mail.date?.time ?: 0L).map { e ->
                    domaine(e, mail.account, data, regles).let { (d, auto) -> e.copy(domaine = d, auto = auto) }
                }
            }
            enregistrer(app, trouves).size
        }
    }

    /** SMS / notification de mail reçus : analysés en arrière-plan, sans jamais bloquer. */
    fun recu(app: JarvisApp, source: String, titre: String, texte: String, ts: Long = System.currentTimeMillis(), compte: String = "") {
        if (!app.store.courrierAnalyse.value) return
        Thread {
            runCatching {
                val data = runCatching { app.societe.data }.getOrNull()
                val regles = regles(app)
                val trouves = analyser(titre, texte, source, ts).map { e -> domaine(e, compte, data, regles).let { (d, a) -> e.copy(domaine = d, auto = a) } }
                val nouveaux = enregistrer(app, trouves)
                if (app.store.courrierNotif.value) nouveaux.forEach { notifier(app, it) }
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
                "📅 Rendez-vous détecté : ${quand(e.dateRdv)}" to "Ajoute à mon agenda : rendez-vous « ${e.emetteur} » ${quand(e.dateRdv)}" + (if (e.lieu.isNotBlank()) " à ${e.lieu}" else "") + " (détecté dans un message reçu)."
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
            .setContentText(e.resume.take(120)).setStyle(NotificationCompat.BigTextStyle().bigText(e.resume)).setAutoCancel(true)
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
        RDV -> "📅 ${titreRdv(e)} — ${quand(e.dateRdv)}${if (e.lieu.isNotBlank()) " · ${e.lieu}" else ""}"
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

    fun argsEvenement(e: Element, debutChoisi: java.time.LocalDateTime? = null): JSONObject {
        val debut = debutChoisi ?: Instant.ofEpochMilli(e.dateRdv ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalDateTime()
        return JSONObject().put("title", titreRdv(e)).put("start", debut.toString().take(16)).put("end", debut.plusHours(1).toString().take(16))
            .put("location", e.lieu).put("description", "Détecté par JARVIS dans un message reçu :\n${e.resume}")
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
