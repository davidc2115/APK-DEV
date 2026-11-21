package fr.dd.jarvis.societe

import fr.dd.jarvis.societe.core.Societe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Création de la société pas à pas (onglet Société › Création) : choix du statut, conditions d'exercice (électricien :
 * activité artisanale réglementée), assurances, déclaration sur le guichet unique, SIRET, banque, ACRE, espaces en ligne,
 * mentions obligatoires — avec les liens officiels. Chaque étape se coche ; le SIRET reçu se vérifie et remplit
 * l'identité de la société (annuaire officiel des entreprises).
 */
object Creation {
    data class Etape(val id: String, val titre: String, val detail: List<String>, val lien: String = "", val pour: Set<Declarations.Statut>? = null)

    private val SOCIETES = setOf(Declarations.Statut.EURL, Declarations.Statut.SASU, Declarations.Statut.SAS, Declarations.Statut.SARL)
    private val INDIV = setOf(Declarations.Statut.MICRO, Declarations.Statut.EI)

    /** Comparatif court des statuts (pour choisir). */
    val COMPARATIF: List<Pair<Declarations.Statut, String>> = listOf(
        Declarations.Statut.MICRO to "Le plus simple : création gratuite, cotisations = % du chiffre d'affaires encaissé (21,2 % en prestations), pas de comptabilité complète, mais frais non déductibles et plafond de 83 600 € de CA. Idéal pour démarrer seul.",
        Declarations.Statut.EI to "Entreprise individuelle au régime réel : frais réellement déduits, comptabilité complète, cotisations sur le bénéfice (≈ 40-45 %). Utile si beaucoup de charges.",
        Declarations.Statut.EURL to "Société à un associé (gérant TNS, cotisations plus faibles ≈ 45 % du revenu), à l'IS par défaut ; capital libre (1 € minimum).",
        Declarations.Statut.SASU to "Société à un associé, président assimilé salarié (meilleure protection sociale, cotisations ≈ 75-80 % du net) ou rémunéré en dividendes (PFU 31,4 %) ; à l'IS.",
        Declarations.Statut.SAS to "Comme la SASU mais à plusieurs associés ; statuts très libres.",
        Declarations.Statut.SARL to "Société à plusieurs associés au cadre légal plus encadré ; gérant majoritaire TNS.",
    )

    fun etapes(st: Declarations.Statut): List<Etape> = listOf(
        Etape("statut", "Choisir le statut juridique", listOf(
            "Compare micro-entreprise, EI, EURL, SASU, SAS, SARL (comparatif ci-dessus) selon ton chiffre d'affaires prévu, tes charges et ta protection sociale.",
            "Simulateurs officiels de l'URSSAF (revenu net et cotisations selon le statut) : mon-entreprise.urssaf.fr ; accompagnement gratuit : CMA (chambre de métiers) de ton département."),
            "https://mon-entreprise.urssaf.fr"),
        Etape("qualification", "Vérifier la qualification (électricien : activité artisanale réglementée)", listOf(
            "Les travaux d'électricité sont une activité réglementée : il faut un CAP/BEP (ou titre équivalent) du métier, ou 3 ans d'expérience professionnelle, toi ou une personne qualifiée sous ton contrôle.",
            "Garde les justificatifs (diplômes, certificats de travail) : ils sont demandés à l'immatriculation au Registre national des entreprises (activité artisanale).",
            "Pour le photovoltaïque : habilitation électrique (B1V/B2V/BR, BP photovoltaïque) et, si tu veux faire bénéficier tes clients des aides, la qualification RGE (QualiPV)."),
            "https://entreprendre.service-public.gouv.fr/vosdroits/F38552"),
        Etape("assurances", "Souscrire les assurances", listOf(
            "Assurance décennale : obligatoire pour les travaux de construction (pose, travaux sur l'ouvrage) — son attestation et la zone couverte doivent figurer sur tes devis et factures.",
            "Responsabilité civile professionnelle (RC pro) : indispensable pour la maintenance / le dépannage.",
            "Demande plusieurs devis (assureurs spécialisés BTP) avant l'immatriculation : l'attestation peut être exigée par tes clients."),
            "https://entreprendre.service-public.gouv.fr/vosdroits/F23634"),
        Etape("statuts", "Rédiger et signer les statuts", listOf(
            "Statuts de la société (modèles sur le guichet unique ou chez un juriste), nomination du dirigeant, siège social (domicile possible), capital.",
            "Dépose le capital social sur un compte bancaire bloqué (banque, notaire) : attestation de dépôt des fonds."),
            "https://entreprendre.service-public.gouv.fr/vosdroits/F31201", SOCIETES),
        Etape("annonce", "Publier l'annonce légale de constitution", listOf(
            "Sur un journal d'annonces légales (JAL) du département du siège : tarif forfaitaire selon la forme (de l'ordre de 120 à 200 €).",
            "Garde l'attestation de parution : elle est jointe au dossier."),
            "https://entreprendre.service-public.gouv.fr/vosdroits/F31972", SOCIETES),
        Etape("guichet", "Déclarer la création sur le guichet unique", listOf(
            "Unique site officiel et gratuit pour une micro-entreprise : formalites.entreprises.gouv.fr (méfie-toi des sites payants qui l'imitent).",
            "Prépare : pièce d'identité, justificatif de domicile / siège, justificatifs de qualification, attestation de non-condamnation (déclaration sur l'honneur)" +
                if (st in SOCIETES) ", statuts signés, attestation de dépôt du capital, attestation de l'annonce légale, déclaration des bénéficiaires effectifs." else ".",
            if (st == Declarations.Statut.MICRO) "Choisis le régime micro-entrepreneur, l'activité artisanale (électricité), et coche si tu le souhaites le versement libératoire de l'impôt et la déclaration trimestrielle (sinon mensuelle)."
            else "Choisis le régime fiscal (IS par défaut pour les sociétés) et le régime de TVA (franchise, réel simplifié en 2026, réel normal).",
            if (st in SOCIETES) "Frais : greffe et inscription au registre (quelques dizaines d'euros) ; le guichet affiche le montant exact avant paiement." else "Coût : gratuit pour une micro-entreprise."),
            "https://formalites.entreprises.gouv.fr"),
        Etape("siret", "Recevoir le SIREN / SIRET", listOf(
            "L'INSEE attribue le SIREN (9 chiffres) et le SIRET de ton établissement (14 chiffres) en général sous 1 à 4 semaines ; tu le reçois par courrier / mail et dans ton espace du guichet unique.",
            "Vérifie-le sur l'annuaire officiel (bouton « Vérifier le SIRET » ci-dessous) : JARVIS remplit alors l'identité de ta société (nom, adresse, code APE, date de création).",
            "Télécharge ton avis de situation au répertoire SIRENE (demandé par les banques et fournisseurs)."),
            "https://annuaire-entreprises.data.gouv.fr"),
        Etape("banque", "Ouvrir le compte bancaire professionnel", listOf(
            if (st in SOCIETES) "Obligatoire pour une société : le capital y est déposé, toutes les recettes et dépenses y passent."
            else "Micro-entreprise : un compte dédié à l'activité est obligatoire dès que le chiffre d'affaires dépasse 10 000 € deux années de suite — mieux vaut l'ouvrir dès le départ.",
            "Renseigne l'IBAN dans JARVIS (Société › Identité) : il apparaît sur tes factures."),
            "https://entreprendre.service-public.gouv.fr/vosdroits/F37018"),
        Etape("acre", "Demander l'ACRE (aide à la création)", listOf(
            "Réduction de cotisations la 1re année : 50 % (25 % pour une micro-entreprise créée à partir du 1er juillet 2026).",
            if (st == Declarations.Statut.MICRO) "Micro-entrepreneur : demande à faire au plus tard 60 jours après le début d'activité, auprès de l'URSSAF." else "Société / EI : en principe accordée sur demande lors de la création, sous conditions (demandeur d'emploi, moins de 26 ans, etc.).",
            "Coche « ACRE » dans l'onglet Déclarations : les cotisations calculées en tiennent compte."),
            "https://entreprendre.service-public.gouv.fr/vosdroits/F11677"),
        Etape("espaces", "Créer tes espaces en ligne", listOf(
            if (st == Declarations.Statut.MICRO) "URSSAF : autoentrepreneur.urssaf.fr (déclaration du chiffre d'affaires et paiement)." else "URSSAF : urssaf.fr (dirigeant TNS) et net-entreprises.fr (DSN si salaires).",
            "Impôts : espace professionnel sur impots.gouv.fr (TVA, CFE, IS) — à créer avec ton SIREN.",
            "Facturation électronique : choisis une plateforme agréée (réception obligatoire depuis le 1er septembre 2026, émission au 1er septembre 2027)."),
            "https://www.impots.gouv.fr/professionnel"),
        Etape("mentions", "Mettre à jour devis et factures", listOf(
            "SIRET, forme et capital (société), adresse, numéro de TVA intracommunautaire ou « TVA non applicable, art. 293 B du CGI » (franchise), assurance décennale (assureur, n° de contrat, zone).",
            "Dans JARVIS : Société › Identité (SIRET, RCS / RM, code APE, mentions) — elles sont reprises sur tous les PDF."),
            "https://entreprendre.service-public.gouv.fr/vosdroits/F31808"),
        Etape("declarations", "Régler l'onglet Déclarations", listOf(
            "Choisis le statut, l'activité, la périodicité URSSAF, le régime de TVA, l'ACRE et la date de début d'activité : JARVIS calcule alors chaque échéance (montant, date, marche à suivre)."), ""),
    ).filter { it.pour == null || st in it.pour }

    // ------------------------------------------------------------------ SIRET (annuaire officiel des entreprises)

    data class Fiche(val nom: String, val siren: String, val siret: String, val adresse: String, val ape: String, val nature: String, val dateCreation: String, val etat: String)

    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    fun siretValide(s: String): Boolean {
        val d = s.filter { it.isDigit() }
        if (d.length != 14) return false
        // Clé de Luhn (sauf La Poste, SIREN 356000000, qui suit une autre règle).
        if (d.startsWith("356000000")) return d.sumOf { it - '0' } % 5 == 0
        return d.reversed().mapIndexed { i, c -> val v = (c - '0') * (if (i % 2 == 1) 2 else 1); if (v > 9) v - 9 else v }.sum() % 10 == 0
    }

    /** Recherche officielle (recherche-entreprises.api.gouv.fr) par SIRET, SIREN ou nom. */
    suspend fun rechercher(q: String): Fiche? = withContext(Dispatchers.IO) {
        val chiffres = q.filter { it.isDigit() }
        val requete = if (chiffres.length == 14 || chiffres.length == 9) chiffres else q.trim()
        val url = "https://recherche-entreprises.api.gouv.fr/search?q=" + java.net.URLEncoder.encode(requete, "UTF-8") + "&per_page=1"
        val corps = runCatching { http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { if (it.isSuccessful) it.body?.string() else null } }.getOrNull() ?: return@withContext null
        val r = runCatching { JSONObject(corps).optJSONArray("results")?.optJSONObject(0) }.getOrNull() ?: return@withContext null
        val siege = r.optJSONObject("siege") ?: JSONObject()
        val siretTrouve = (r.optJSONArray("matching_etablissements")?.optJSONObject(0)?.optString("siret")?.takeIf { chiffres.length == 14 && it == chiffres }) ?: siege.optString("siret")
        Fiche(r.optString("nom_complet"), r.optString("siren"), siretTrouve, siege.optString("adresse"), r.optString("activite_principale"),
            r.optString("nature_juridique"), r.optString("date_creation"), if (r.optString("etat_administratif") == "A") "active" else "fermée")
    }

    /** Identité de la société complétée depuis la fiche officielle (sans effacer ce qui est déjà saisi). */
    fun appliquer(s: Societe, f: Fiche): Societe = s.copy(
        nom = s.nom.ifBlank { f.nom }, siret = f.siret.ifBlank { s.siret }, adresse = s.adresse.ifBlank { f.adresse },
        codeApe = f.ape.ifBlank { s.codeApe }, dateCreation = s.dateCreation.ifBlank { f.dateCreation })
}
