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
    )

    const val COLIS = "colis"; const val FACTURE = "facture"; const val DEVIS = "devis"; const val RDV = "rdv"

    // ------------------------------------------------------------------ analyse (pure)

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
        Transporteur("Amazon", Regex("\\bamazon\\b"), Regex("\\b(TBA\\d{9,12}|\\d{3}-\\d{7}-\\d{7})\\b")) { "https://www.amazon.fr/gp/your-account/order-history" },
    )
    private val MOTS_COLIS = Regex("\\b(colis|livraison|livre|livree|expedie|expediee|expedition|point relais|relais|consigne|locker|avis de passage|suivi|tracking|envoi|commande)\\b")
    private val NUMERO_GENERIQUE = Regex("\\b([A-Z]{2}\\d{9}[A-Z]{2}|\\d[A-Z]\\d{11}|1Z[0-9A-Z]{16}|\\d{13,15})\\b")
    private val CODE_RETRAIT = Regex("(?i)code (?:de retrait|de collecte|d'ouverture|d’ouverture|de deverrouillage|de déverrouillage|pin|de livraison|de recuperation|de récupération|a presenter|à présenter)\\s*[:=]?\\s*([A-Z0-9]{4,10})")

    private val MONTANT = Regex("(\\d{1,3}(?:[ .\\u202F\\u00A0]\\d{3})*(?:[,.]\\d{1,2})?)\\s?(?:€|eur\\b|euros?\\b)", RegexOption.IGNORE_CASE)
    private val FACTURE_RE = Regex("\\b(facture|factures|avis d echeance|echeancier|invoice|a regler|montant (a payer|du|preleve|de votre facture)|prelevement|votre releve|mise en demeure|relance de paiement|impaye)\\b")
    private val DEVIS_RE = Regex("\\b(devis|proposition commerciale|offre de prix|estimation)\\b")
    private val RDV_RE = Regex("\\b(rendez vous|rdv|convocation|votre visite|intervention (prevue|programmee|planifiee)|passage (de notre |du |d un )?technicien|technicien passera|creneau (confirme|reserve)|reservation confirmee|consultation)\\b")
    private val HEURE = Regex("\\b(\\d{1,2}) ?(?:h|:) ?(\\d{2})?\\b")
    private val ADRESSE = Regex("(\\d{1,4}(?: ?bis| ?ter)?,? (?:rue|avenue|av\\.?|bd|boulevard|chemin|place|allee|allée|impasse|route|quai|cours|faubourg|zone|za|zi|lieu[- ]dit)[^,\\n.]{2,60}(?:,? ?\\d{5} [A-Za-zÀ-ÿ' -]{2,40})?)", RegexOption.IGNORE_CASE)

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
        val numero = (t?.numero?.find(brut) ?: if (MOTS_COLIS.containsMatchIn(n)) NUMERO_GENERIQUE.find(brut) else null)?.groupValues?.get(1)
        if (MOTS_COLIS.containsMatchIn(n) && (numero != null || t != null) && !FACTURE_RE.containsMatchIn(n.replace(Regex("\\bfacture (jointe|disponible)\\b"), ""))) {
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
        // Rendez-vous (une date à venir est indispensable)
        if (RDV_RE.containsMatchIn(n)) {
            val nd = DatesFr.normaliser(brut)
            val jour = DatesFr.jour(nd, aujourdhui)?.date
            if (jour != null && !jour.isBefore(aujourdhui)) {
                val h = HEURE.findAll(nd).firstOrNull { (it.groupValues[1].toIntOrNull() ?: 99) in 6..21 }
                val heure = h?.groupValues?.get(1)?.toIntOrNull()
                val minute = h?.groupValues?.get(2)?.toIntOrNull() ?: 0
                val debut = jour.atTime(heure ?: 9, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val lieu = ADRESSE.find(brut)?.value?.trim().orEmpty()
                out += Element(id(RDV, emetteur, jour.toString(), "${heure ?: ""}"), RDV, source, emetteur, resume, ts, dateRdv = debut, lieu = lieu,
                    statut = if (heure == null) "heure non précisée" else "")
                return out
            }
        }
        // Facture / devis (un montant est indispensable)
        val m = montant(brut)
        if (m != null && (FACTURE_RE.containsMatchIn(n) || DEVIS_RE.containsMatchIn(n))) {
            val devis = DEVIS_RE.containsMatchIn(n) && !FACTURE_RE.containsMatchIn(n)
            val ech = Regex("\\b(?:avant le|au plus tard le|date limite(?: de paiement)?|echeance(?: le| du)?|preleve le|sera preleve le|a payer (?:avant )?le|valable jusqu au|le) ?:? ?(.{4,30})").find(DatesFr.normaliser(brut))
                ?.groupValues?.get(1)?.let { DatesFr.jour(DatesFr.normaliser(it), aujourdhui)?.date?.toString() }.orEmpty()
            out += Element(id(if (devis) DEVIS else FACTURE, emetteur, "%.2f".format(java.util.Locale.ROOT, m), ech), if (devis) DEVIS else FACTURE, source, emetteur, resume, ts,
                montant = m, echeance = ech)
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
                o.optString("lieu"), o.optBoolean("traite"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun sauver(c: Context, l: List<Element>) {
        val a = JSONArray()
        // Gardés 60 jours (traités : 15 jours).
        val limite = System.currentTimeMillis() - 60L * 86_400_000
        l.filter { it.ts > limite && !(it.traite && it.ts < System.currentTimeMillis() - 15L * 86_400_000) }.takeLast(300).forEach { e ->
            a.put(JSONObject().put("id", e.id).put("type", e.type).put("source", e.source).put("emetteur", e.emetteur).put("resume", e.resume).put("ts", e.ts)
                .put("transporteur", e.transporteur).put("numero", e.numero).put("lien", e.lien).put("statut", e.statut).put("code", e.code)
                .apply { e.montant?.let { put("montant", it) }; e.dateRdv?.let { put("dateRdv", it) } }
                .put("echeance", e.echeance).put("lieu", e.lieu).put("traite", e.traite))
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
                traite = if (e.statut == "livré") l[i].traite else false)
        }
        sauver(c, l)
        return vraimentNouveaux
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

    // ------------------------------------------------------------------ arrivée d'un message

    /** SMS / notification de mail reçus : analysés en arrière-plan, sans jamais bloquer. */
    fun recu(app: JarvisApp, source: String, titre: String, texte: String, ts: Long = System.currentTimeMillis()) {
        if (!app.store.courrierAnalyse.value) return
        Thread {
            runCatching {
                val nouveaux = enregistrer(app, analyser(titre, texte, source, ts))
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
            FACTURE -> "🧾 Facture ${e.emetteur} : ${euros(e.montant)}${if (e.echeance.isNotBlank()) " avant le ${dateFr(e.echeance)}" else ""}" to
                "Ajoute une note de frais de ${euros(e.montant)} chez ${e.emetteur}${if (e.echeance.isNotBlank()) " (facture à payer avant le ${dateFr(e.echeance)})" else ""}."
            DEVIS -> "📄 Devis reçu de ${e.emetteur} : ${euros(e.montant)}" to null
            else -> return
        }
        if (Build.VERSION.SDK_INT >= 26) app.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CANAL, "Messages importants (colis, factures, rendez-vous)", NotificationManager.IMPORTANCE_DEFAULT))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val code = 7900 + (e.id.hashCode() and 0x3FF)
        val b = NotificationCompat.Builder(app, CANAL).setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle(titre)
            .setContentText(e.resume.take(120)).setStyle(NotificationCompat.BigTextStyle().bigText(e.resume)).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(app, code, Intent(app, fr.dd.jarvis.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags))
        if (demande != null) b.addAction(0, if (e.type == RDV) "📅 Ajouter à l'agenda" else "💶 En note de frais", PendingIntent.getActivity(app, code + 1,
            Intent(app, fr.dd.jarvis.MainActivity::class.java).putExtra("demande", demande).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags))
        runCatching { NotificationManagerCompat.from(app).notify(code, b.build()) }
    }

    // ------------------------------------------------------------------ présentation

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
        RDV -> "📅 ${e.emetteur} — ${quand(e.dateRdv)}${if (e.lieu.isNotBlank()) " · ${e.lieu}" else ""}"
        else -> e.resume
    }

    /** Colis suivis (non traités ou livrés dans les 2 derniers jours). */
    fun resumeColis(c: Context): String = liste(c).filter { it.type == COLIS && (!it.traite && (it.statut != "livré" || System.currentTimeMillis() - it.ts < 2 * 86_400_000L)) }
        .sortedByDescending { it.ts }.joinToString("\n") { "- " + ligne(it) }

    /** Factures, devis et rendez-vous reçus non traités. */
    fun resumeATraiter(c: Context): String = liste(c).filter { !it.traite && it.type != COLIS && (it.type != RDV || (it.dateRdv ?: 0) > System.currentTimeMillis()) }
        .sortedByDescending { it.ts }.take(12).joinToString("\n") { "- " + ligne(it) }

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

    fun argsEvenement(e: Element): JSONObject {
        val debut = Instant.ofEpochMilli(e.dateRdv ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalDateTime()
        return JSONObject().put("title", "Rendez-vous ${e.emetteur}").put("start", debut.toString().take(16)).put("end", debut.plusHours(1).toString().take(16))
            .put("location", e.lieu).put("description", "Détecté par JARVIS dans un message reçu :\n${e.resume}")
    }
}
