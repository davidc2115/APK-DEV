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
    /** Grandes étapes de la création, chacune avec son explication. */
    enum class Phase(val titre: String, val description: String) {
        PREPARER("🧭 1. Préparer le projet", "Avant toute démarche officielle : choisir le statut, vérifier que tu as le droit d'exercer, choisir le nom et l'adresse, t'assurer et connaître les aides. Rien n'est encore déclaré : tu peux comparer sans engagement."),
        CONSTITUER("📝 2. Constituer la société", "Seulement pour une société (EURL, SASU, SAS, SARL) : rédiger les statuts, déposer le capital et publier l'annonce légale. Ces trois pièces sont jointes au dossier d'immatriculation."),
        IMMATRICULER("🏛️ 3. Immatriculer l'entreprise", "La déclaration officielle, en ligne et unique, qui fait naître l'entreprise : elle est inscrite au Registre national des entreprises et reçoit son numéro SIRET."),
        DEMARRER("🚀 4. Démarrer l'activité", "Une fois le SIRET reçu : banque, aides, espaces en ligne, comptabilité, protection sociale, mentions des devis et factures, premières échéances (CFE, facturation électronique)."),
    }

    data class Option(val titre: String, val texte: String)

    data class Etape(
        val id: String, val titre: String, val detail: List<String>, val lien: String = "", val pour: Set<Declarations.Statut>? = null,
        val phase: Phase = Phase.PREPARER,
        /** Pourquoi cette étape compte. */
        val pourquoi: String = "",
        /** Comment faire, dans l'ordre. */
        val comment: List<String> = emptyList(),
        /** Pièces et informations à préparer. */
        val documents: List<String> = emptyList(),
        /** Chaque choix possible, expliqué. */
        val options: List<Option> = emptyList(),
        val cout: String = "",
        val delai: String = "",
        val pieges: List<String> = emptyList(),
    ) {
        /** Fiche complète de l'étape (résumé, pourquoi, comment, pièces, choix, coût, délai, pièges, lien officiel). */
        fun markdown(): String = buildString {
            append(detail.joinToString("\n") { "- $it" })
            if (pourquoi.isNotBlank()) append("\n\n### 💡 Pourquoi\n$pourquoi")
            if (comment.isNotEmpty()) append("\n\n### 🧭 Comment faire\n" + comment.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n"))
            if (documents.isNotEmpty()) append("\n\n### 📎 À préparer\n" + documents.joinToString("\n") { "- $it" })
            if (options.isNotEmpty()) append("\n\n### 🔀 Les choix possibles\n" + options.joinToString("\n") { "- **${it.titre}** — ${it.texte}" })
            if (cout.isNotBlank()) append("\n\n### 💶 Coût\n$cout")
            if (delai.isNotBlank()) append("\n\n### ⏱️ Délai\n$delai")
            if (pieges.isNotEmpty()) append("\n\n### ⚠️ Pièges à éviter\n" + pieges.joinToString("\n") { "- $it" })
            if (lien.isNotBlank()) append("\n\n[🌐 ${lien.removePrefix("https://")}]($lien)")
        }
    }

    private val SOCIETES = setOf(Declarations.Statut.EURL, Declarations.Statut.SASU, Declarations.Statut.SAS, Declarations.Statut.SARL)

    /** Comparatif court des statuts (pour choisir). */
    val COMPARATIF: List<Pair<Declarations.Statut, String>> = listOf(
        Declarations.Statut.MICRO to "Le plus simple : création gratuite, cotisations = % du chiffre d'affaires encaissé (21,2 % en prestations artisanales), pas de comptabilité complète, mais frais non déductibles et plafond de 83 600 € de chiffre d'affaires. Idéal pour démarrer seul.",
        Declarations.Statut.EI to "Entreprise individuelle au régime réel : frais réellement déduits, comptabilité complète, cotisations sur le bénéfice (≈ 40-45 %). Utile si beaucoup de charges.",
        Declarations.Statut.EURL to "Société à un associé, gérant non salarié (cotisations ≈ 40-45 % de la rémunération) ; impôt sur le revenu par défaut (associé personne physique), option pour l'IS ; capital libre (1 € minimum).",
        Declarations.Statut.SASU to "Société à un associé, président assimilé salarié (meilleure protection sociale, cotisations ≈ 75-80 % du net, pas de chômage) ou rémunéré en dividendes (flat tax 31,4 %) ; à l'IS.",
        Declarations.Statut.SAS to "Comme la SASU mais à plusieurs associés ; statuts très libres (pacte d'associés conseillé).",
        Declarations.Statut.SARL to "Société de 2 à 100 associés au cadre légal plus encadré ; gérant majoritaire non salarié, minoritaire assimilé salarié ; IS par défaut.",
    )

    /** Fiche détaillée d'un statut juridique. */
    data class FicheStatut(
        val resume: String, val pourQui: String, val creation: String, val patrimoine: String, val social: String, val cotisations: String,
        val impots: String, val tva: String, val compta: String, val remuneration: String, val avantages: List<String>, val inconvenients: List<String>, val pourToi: String,
    ) {
        fun markdown(st: Declarations.Statut): String = buildString {
            append("**${st.libelle}** — $resume")
            append("\n\n### 👤 Pour qui\n$pourQui")
            append("\n\n### 🏗️ Création et coût\n$creation")
            append("\n\n### 🛡️ Ton patrimoine personnel\n$patrimoine")
            append("\n\n### 🩺 Protection sociale\n$social")
            append("\n\n### 💶 Cotisations sociales\n$cotisations")
            append("\n\n### 🧾 Impôts\n$impots")
            append("\n\n### 🧮 TVA\n$tva")
            append("\n\n### 📚 Comptabilité et obligations\n$compta")
            append("\n\n### 💰 Te rémunérer\n$remuneration")
            append("\n\n### ✅ Avantages\n" + avantages.joinToString("\n") { "- $it" })
            append("\n\n### ❌ Inconvénients\n" + inconvenients.joinToString("\n") { "- $it" })
            append("\n\n### ⚡ Pour la maintenance photovoltaïque\n$pourToi")
        }
    }

    private const val TVA_FRANCHISE = "Franchise en base possible jusqu'à 37 500 € de chiffre d'affaires de prestations (seuil majoré 41 250 €) : tu ne factures pas de TVA (mention « TVA non applicable, art. 293 B du CGI ») mais tu ne récupères pas celle de tes achats. Au-delà, ou sur option, TVA à facturer et à déclarer (réel simplifié en 2026, supprimé au 1er janvier 2027 ; réel normal)."
    private const val PATRIMOINE_EI = "Depuis le 15 mai 2022, ton patrimoine personnel est séparé automatiquement du patrimoine professionnel : seuls les biens utiles à l'activité répondent des dettes professionnelles. Exceptions : fraude, manquements graves aux obligations fiscales ou sociales, et les cautions personnelles que tu signes (prêt, bail)."
    private const val PATRIMOINE_SOC = "Ta responsabilité est limitée à tes apports : tes biens personnels sont protégés, sauf faute de gestion grave ou caution personnelle que tu signes (prêt bancaire, bail)."

    val FICHES: Map<Declarations.Statut, FicheStatut> = mapOf(
        Declarations.Statut.MICRO to FicheStatut(
            resume = "entreprise individuelle au régime simplifié : tu factures, tu déclares ton chiffre d'affaires encaissé, tu paies un pourcentage. Le plus simple pour démarrer seul.",
            pourQui = "Démarrer seul, tester l'activité, avec peu d'achats et de frais, et un chiffre d'affaires de prestations inférieur à 83 600 € par an (plafond 2026-2028).",
            creation = "Déclaration en ligne gratuite sur le guichet unique (formalites.entreprises.gouv.fr). Pas de statuts, pas de capital, pas d'annonce légale. Inscription au Registre national des entreprises comme activité artisanale (électricité).",
            patrimoine = PATRIMOINE_EI,
            social = "Travailleur indépendant (URSSAF, Sécurité sociale des indépendants) : maladie, maternité, retraite de base et complémentaire, invalidité-décès. Pas d'assurance chômage. Les trimestres de retraite sont validés selon le chiffre d'affaires déclaré.",
            cotisations = "21,2 % du chiffre d'affaires encaissé (prestations artisanales BIC) + 0,3 % de formation professionnelle + 0,48 % pour la chambre de métiers. Pas de chiffre d'affaires = pas de cotisations. ACRE possible la 1re année (voir l'étape des aides).",
            impots = "Impôt sur le revenu : ton chiffre d'affaires moins un abattement forfaitaire de 50 % s'ajoute à tes autres revenus. Ou, sur option, versement libératoire de 1,7 % du chiffre d'affaires payé avec les cotisations (si ton revenu fiscal de référence de l'avant-dernière année ne dépasse pas environ 29 300 € par part).",
            tva = TVA_FRANCHISE,
            compta = "Livre des recettes (et registre des achats si tu vends du matériel), factures conformes et numérotées, compte bancaire dédié obligatoire si le chiffre d'affaires dépasse 10 000 € deux années de suite. Pas de bilan ni de liasse fiscale. Déclaration du chiffre d'affaires chaque mois ou chaque trimestre.",
            remuneration = "Tout ce qui reste après tes dépenses est ton revenu : pas de salaire à te verser, tu prélèves librement.",
            avantages = listOf("Création gratuite et rapide", "Cotisations proportionnelles : rien à payer sans chiffre d'affaires", "Comptabilité minimale (JARVIS suffit)", "Idéal pour tester l'activité"),
            inconvenients = listOf("Aucune charge réelle déductible (véhicule, outillage, matériel, assurance décennale…)", "Plafond de 83 600 € de chiffre d'affaires", "Franchise de TVA : TVA des achats non récupérée", "Pas de chômage, protection sociale faible si le chiffre d'affaires est faible"),
            pourToi = "Bon choix pour démarrer la maintenance seul avec peu d'achats. Si tu poses des installations et revends beaucoup de matériel (panneaux, onduleurs), les frais non déductibles et la TVA non récupérée pèsent vite : compare avec l'EI au réel ou une société.",
        ),
        Declarations.Statut.EI to FicheStatut(
            resume = "entreprise individuelle imposée sur son vrai bénéfice : recettes moins dépenses réelles.",
            pourQui = "Activité avec beaucoup de frais (véhicule, matériel, sous-traitance) ou un chiffre d'affaires au-dessus des plafonds de la micro, sans vouloir créer une société.",
            creation = "Déclaration gratuite sur le guichet unique, en choisissant le régime réel (BIC réel simplifié, ou réel normal). Pas de statuts, pas de capital, pas d'annonce légale.",
            patrimoine = PATRIMOINE_EI,
            social = "Travailleur indépendant (Sécurité sociale des indépendants) : maladie, retraite, invalidité-décès ; pas de chômage.",
            cotisations = "Environ 40 à 45 % du bénéfice, avec des cotisations minimales dues même sans bénéfice. Mutuelle et prévoyance « Madelin » déductibles.",
            impots = "Bénéfice imposé à l'impôt sur le revenu (BIC). Option possible pour l'impôt sur les sociétés : tu es alors traité comme une EURL à l'IS (rémunération déductible, bénéfice taxé à 15 % / 25 %).",
            tva = TVA_FRANCHISE,
            compta = "Comptabilité complète : bilan, compte de résultat et liasse fiscale chaque année. Un expert-comptable est conseillé.",
            remuneration = "Ton revenu est le bénéfice ; tu prélèves librement sur le compte (les prélèvements ne sont pas des charges).",
            avantages = listOf("Frais réels déductibles (véhicule, matériel, assurances)", "Déficit imputable sur tes autres revenus", "Pas de plafond de chiffre d'affaires", "Création gratuite"),
            inconvenients = listOf("Comptabilité plus lourde (expert-comptable ≈ 1 000 à 2 000 € par an)", "Cotisations minimales même sans bénéfice", "Bénéfice imposé même s'il reste dans l'entreprise (sauf option IS)"),
            pourToi = "Intéressant si tes achats de matériel, ton véhicule et tes assurances représentent une grosse part de ton chiffre d'affaires dès le départ.",
        ),
        Declarations.Statut.EURL to FicheStatut(
            resume = "société à un seul associé (toi), dirigée par un gérant non salarié.",
            pourQui = "Créer une société seul avec des cotisations modérées, et pouvoir accueillir des associés plus tard (elle devient alors une SARL).",
            creation = "Statuts, capital (1 € minimum, apports en argent ou en nature), dépôt du capital, annonce légale (124 € HT en 2026), immatriculation (33,83 € + 15 € pour l'activité artisanale + 19,33 € de déclaration des bénéficiaires effectifs). Compte environ 200 € sans aide extérieure.",
            patrimoine = PATRIMOINE_SOC,
            social = "Gérant associé unique = travailleur indépendant (Sécurité sociale des indépendants) ; pas de chômage.",
            cotisations = "Environ 40 à 45 % de ta rémunération de gérant. Les dividendes au-delà de 10 % du capital, des primes et des comptes courants sont aussi soumis aux cotisations.",
            impots = "Associé unique personne physique : impôt sur le revenu par défaut (le bénéfice est imposé chez toi, comme une EI). Option pour l'IS : 15 % jusqu'à 42 500 € de bénéfice, 25 % au-delà ; ta rémunération est alors déductible.",
            tva = TVA_FRANCHISE,
            compta = "Comptabilité complète, approbation des comptes chaque année et dépôt au greffe (confidentialité du compte de résultat possible). Registre des décisions de l'associé unique.",
            remuneration = "Rémunération de gérant (fixée par décision, déductible à l'IS) et/ou dividendes (à l'IS).",
            avantages = listOf("Cotisations plus faibles qu'en SASU", "Patrimoine protégé, image de société", "Évolution facile vers une SARL"),
            inconvenients = listOf("Coûts de création et de comptabilité", "Protection sociale d'indépendant (pas de chômage)", "Dividendes soumis aux cotisations au-delà de 10 %"),
            pourToi = "Bon compromis si tu veux une société tout en gardant des charges sociales modérées et une rémunération régulière.",
        ),
        Declarations.Statut.SASU to FicheStatut(
            resume = "société à un seul associé, dont le président est assimilé salarié.",
            pourQui = "Une protection sociale proche des salariés, des statuts souples, une rémunération surtout en dividendes, ou l'entrée d'associés plus tard (elle devient une SAS sans formalité lourde).",
            creation = "Statuts (très libres), capital (1 € minimum), dépôt du capital, annonce légale (142 € HT en 2026), immatriculation (33,83 € + 15 € pour l'activité artisanale + 19,33 € de bénéficiaires effectifs).",
            patrimoine = PATRIMOINE_SOC,
            social = "Président assimilé salarié : régime général (maladie, retraite de base et complémentaire Agirc-Arrco, prévoyance), mais PAS d'assurance chômage. Sans salaire : aucune cotisation, mais aucune protection ni trimestre de retraite.",
            cotisations = "Environ 75 à 82 % du salaire net (cotisations patronales et salariales). Aucune cotisation sociale sur les dividendes.",
            impots = "Impôt sur les sociétés par défaut : 15 % jusqu'à 42 500 € de bénéfice, 25 % au-delà. Option pour l'impôt sur le revenu possible 5 ans au plus pour une jeune société (conditions).",
            tva = TVA_FRANCHISE,
            compta = "Comptabilité complète, approbation des comptes chaque année et dépôt au greffe. Si tu te verses un salaire : fiche de paie et déclaration sociale nominative (DSN) chaque mois.",
            remuneration = "Salaire de président (fiche de paie, déductible) et/ou dividendes : flat tax de 31,4 % en 2026 (12,8 % d'impôt + 18,6 % de prélèvements sociaux), sans cotisations.",
            avantages = listOf("Protection sociale du régime général", "Dividendes sans cotisations sociales", "Statuts très souples, image solide"),
            inconvenients = listOf("Salaire très chargé", "Pas de chômage", "Coûts : expert-comptable, paie, formalisme"),
            pourToi = "Intéressant si ton bénéfice permet un salaire ou si tu veux te verser surtout des dividendes ; moins adapté pour démarrer avec un petit chiffre d'affaires.",
        ),
        Declarations.Statut.SAS to FicheStatut(
            resume = "société par actions à plusieurs associés, présidée par un dirigeant assimilé salarié.",
            pourQui = "Créer à plusieurs (associé, investisseur), avec des règles sur mesure (entrée et sortie des associés, pouvoirs).",
            creation = "Statuts (rédaction plus importante : clauses d'agrément, de sortie), capital (1 € minimum), dépôt du capital, annonce légale (199 € HT en 2026), immatriculation (33,83 € + 15 € artisanale + 19,33 € bénéficiaires effectifs). Pacte d'associés conseillé.",
            patrimoine = PATRIMOINE_SOC,
            social = "Président (et directeurs généraux rémunérés) assimilés salariés : régime général, pas de chômage au titre du mandat.",
            cotisations = "Environ 75 à 82 % du salaire net ; aucune sur les dividendes.",
            impots = "Impôt sur les sociétés (15 % / 25 %) ; dividendes à la flat tax de 31,4 % en 2026.",
            tva = TVA_FRANCHISE,
            compta = "Comptabilité complète, assemblée générale annuelle, dépôt des comptes ; commissaire aux comptes seulement au-delà de certains seuils.",
            remuneration = "Salaire et/ou dividendes, selon les décisions des associés.",
            avantages = listOf("Grande liberté des statuts", "Entrée d'investisseurs facile", "Protection sociale du régime général"),
            inconvenients = listOf("Rédaction des statuts plus délicate", "Salaire très chargé", "Formalisme (assemblées, registres)"),
            pourToi = "À choisir si tu t'associes (par exemple avec un autre électricien ou un apporteur de capital).",
        ),
        Declarations.Statut.SARL to FicheStatut(
            resume = "société à responsabilité limitée de 2 à 100 associés, au cadre légal encadré.",
            pourQui = "S'associer avec des règles déjà prévues par la loi (entreprise familiale, associés qui se connaissent).",
            creation = "Statuts, capital (1 € minimum), dépôt du capital, annonce légale (148 € HT en 2026), immatriculation (33,83 € + 15 € artisanale + 19,33 € bénéficiaires effectifs).",
            patrimoine = PATRIMOINE_SOC,
            social = "Gérant majoritaire : travailleur indépendant. Gérant minoritaire ou égalitaire : assimilé salarié. Pas de chômage pour le mandat.",
            cotisations = "Gérant majoritaire ≈ 40-45 % de sa rémunération ; minoritaire ≈ 75-82 % du net.",
            impots = "Impôt sur les sociétés par défaut ; option pour l'impôt sur le revenu possible pour une SARL de famille ou une jeune société (5 ans).",
            tva = TVA_FRANCHISE,
            compta = "Comptabilité complète, assemblée annuelle, dépôt des comptes. La vente de parts à un tiers demande l'accord des associés.",
            remuneration = "Rémunération de gérant et/ou dividendes (soumis aux cotisations au-delà de 10 % pour un gérant majoritaire).",
            avantages = listOf("Cadre légal rassurant", "Cotisations modérées pour un gérant majoritaire", "Adaptée aux entreprises familiales"),
            inconvenients = listOf("Moins de souplesse que la SAS", "Cession de parts encadrée", "Formalisme"),
            pourToi = "À considérer si tu crées avec un proche et souhaites un cadre simple et prévu par la loi.",
        ),
    )

    fun fiche(st: Declarations.Statut): FicheStatut = FICHES.getValue(st)

    /** Tableau de comparaison des statuts. */
    val TABLEAU = "| Statut | Création | Cotisations | Impôt | Chômage |\n|---|---|---|---|---|\n" +
        "| Micro | Gratuite | 21,2 % du CA | IR (abattement 50 %) ou 1,7 % | Non |\n" +
        "| EI réel | Gratuite | ≈ 45 % du bénéfice | IR (option IS) | Non |\n" +
        "| EURL | ≈ 200 € | ≈ 45 % de la rémunération | IR (option IS) | Non |\n" +
        "| SASU | ≈ 210 € | ≈ 80 % du salaire net | IS 15 % / 25 % | Non |\n" +
        "| SAS | ≈ 270 € | ≈ 80 % du salaire net | IS 15 % / 25 % | Non |\n" +
        "| SARL | ≈ 220 € | 45 % ou 80 % selon le gérant | IS (option IR) | Non |"

    fun etapes(st: Declarations.Statut): List<Etape> {
        val micro = st == Declarations.Statut.MICRO
        val societe = st in SOCIETES
        return listOf(
        // ------------------------------------------------------------------ 1. Préparer
        Etape("statut", "Choisir le statut juridique", listOf(
            "Compare micro-entreprise, EI, EURL, SASU, SAS, SARL (touche un statut dans « Quel statut choisir ? ») selon ton chiffre d'affaires prévu, tes charges et ta protection sociale.",
            "Simulateurs officiels de l'URSSAF (revenu net et cotisations selon le statut) : mon-entreprise.urssaf.fr ; accompagnement gratuit : CMA (chambre de métiers) de ton département."),
            "https://mon-entreprise.urssaf.fr", phase = Phase.PREPARER,
            pourquoi = "Le statut fixe ta protection sociale, tes cotisations, tes impôts, ta comptabilité et le coût de création. On peut en changer plus tard (micro → société), mais c'est une nouvelle démarche, avec un coût.",
            comment = listOf("Estime ton chiffre d'affaires de la 1re année et tes charges : véhicule, outillage, matériel acheté pour les clients, assurances, formation.",
                "Fais la simulation « revenu net selon le statut » sur mon-entreprise.urssaf.fr avec ces chiffres.",
                "Lis la fiche de chaque statut (création, cotisations, impôts, avantages, inconvénients).",
                "Si tu hésites : rendez-vous gratuit avec un conseiller de la chambre de métiers (CMA) ou un expert-comptable (premier rendez-vous souvent gratuit)."),
            options = Declarations.Statut.values().map { Option(it.libelle, COMPARATIF.first { c -> c.first == it }.second) },
            cout = "Gratuit (simulateurs, conseiller CMA).", delai = "Quelques jours de réflexion.",
            pieges = listOf("Choisir la micro sans mesurer le poids des achats de matériel : aucun frais n'est déductible.",
                "Choisir la SASU pour la protection sociale sans pouvoir te verser un salaire (très chargé).",
                "Oublier que le plafond de la micro porte sur le chiffre d'affaires, pas sur le bénéfice.")),
        Etape("qualification", "Vérifier ta qualification (électricité : activité artisanale réglementée)", listOf(
            "Les travaux d'électricité sont une activité réglementée : il faut un CAP/BEP (ou titre équivalent) du métier, ou 3 ans d'expérience professionnelle, toi ou une personne qualifiée sous ton contrôle.",
            "Garde les justificatifs (diplômes, certificats de travail) : ils peuvent être demandés à l'immatriculation au Registre national des entreprises (activité artisanale).",
            "Pour le photovoltaïque : habilitation électrique à jour et, pour que tes clients aient droit aux aides, la qualification RGE (QualiPV)."),
            "https://entreprendre.service-public.gouv.fr/vosdroits/F38552", phase = Phase.PREPARER,
            pourquoi = "La loi du 5 juillet 1996 (article 16) réserve les travaux d'électricité aux personnes qualifiées : sans qualification, l'exercice est illégal et les assureurs refusent de te couvrir.",
            comment = listOf("Rassemble ton diplôme (CAP, BEP, bac pro, BP ou titre homologué en électricité) ou la preuve de 3 ans d'expérience dans le métier.",
                "Sans l'un ni l'autre : l'activité doit être exercée sous le contrôle effectif et permanent d'une personne qualifiée (salarié ou associé).",
                "Vérifie ton habilitation électrique (B1V / B2V / BR, et BP pour le photovoltaïque) : en indépendant, c'est à toi d'organiser le recyclage (tous les 3 ans conseillé).",
                "Si tu vises la pose chez des particuliers avec aides : prépare la qualification RGE QualiPV (organisme Qualit'EnR)."),
            documents = listOf("Diplômes ou titres", "Certificats de travail, fiches de paie ou attestations d'employeurs (3 ans d'expérience)", "Attestations d'habilitation électrique et de formation photovoltaïque"),
            options = listOf(Option("Par diplôme", "CAP, BEP ou titre au moins équivalent dans l'électricité."),
                Option("Par l'expérience", "3 années d'exercice effectif du métier, comme salarié ou indépendant."),
                Option("Par une personne qualifiée", "Un salarié ou un associé qualifié exerce ou contrôle l'activité."),
                Option("RGE QualiPV (facultatif)", "Pas obligatoire pour exercer, mais sans elle tes clients particuliers n'ont pas droit aux aides publiques ; audits de chantier et frais annuels.")),
            cout = "Qualification : gratuit. Habilitation : quelques centaines d'euros de formation. RGE : frais d'instruction et d'audit (plusieurs centaines d'euros par an).",
            pieges = listOf("Déclarer l'activité « électricité » sans pouvoir justifier ta qualification.", "Proposer une pose « avec aides » sans être RGE.")),
        Etape("nom", "Choisir le nom et vérifier qu'il est libre", listOf(
            "Le nom figure sur tes devis, factures, véhicule et site : vérifie qu'aucune marque ni entreprise proche ne l'utilise déjà."),
            "https://data.inpi.fr", phase = Phase.PREPARER,
            pourquoi = "Si le nom est déjà protégé par une marque ou utilisé par une entreprise du même secteur, tu peux être obligé d'en changer (véhicule, site, documents à refaire).",
            comment = listOf("Cherche le nom sur l'annuaire des entreprises (annuaire-entreprises.data.gouv.fr) et dans la base des marques de l'INPI (data.inpi.fr).",
                "Vérifie qu'un nom de domaine (.fr) est libre pour ton site et ton adresse mail.",
                if (societe) "La dénomination de la société est inscrite dans les statuts ; tu peux aussi avoir un nom commercial différent."
                else "En entreprise individuelle, ton nom de famille est la dénomination légale ; tu peux ajouter un nom commercial. Tes documents doivent porter « EI » ou « entrepreneur individuel » à côté de ton nom.",
                "Option : déposer le nom comme marque à l'INPI pour le protéger 10 ans."),
            options = listOf(Option("Dénomination sociale", "Le nom juridique de la société (dans les statuts)."),
                Option("Nom commercial", "Le nom sous lequel les clients te connaissent, déclaré à l'immatriculation."),
                Option("Enseigne", "Le nom affiché sur ton local ou ton véhicule."),
                Option("Marque INPI", "Protection du nom pour 10 ans renouvelables, dans les classes d'activité choisies.")),
            cout = "Vérification gratuite ; dépôt de marque à l'INPI : 190 € pour une classe (en ligne), 40 € par classe en plus.",
            pieges = listOf("Un nom trop proche d'un concurrent local.", "Oublier la mention « EI » sur les documents d'une entreprise individuelle.")),
        Etape("siege", "Choisir l'adresse de l'entreprise (siège)", listOf(
            "Domicile, société de domiciliation ou local : l'adresse fixe le greffe, le service des impôts et la CFE, et figure sur tous tes documents."),
            "https://entreprendre.service-public.gouv.fr/vosdroits/F37412", phase = Phase.PREPARER,
            pourquoi = "L'adresse du siège rattache l'entreprise à un greffe et à un service des impôts ; elle sert au calcul de la CFE et apparaît sur chaque devis et facture.",
            options = listOf(
                Option("À ton domicile", if (societe) "Possible sans limite de durée si ton bail ou le règlement de copropriété ne l'interdit pas ; sinon pour 5 ans au plus, en prévenant le bailleur ou le syndic par écrit avant l'immatriculation."
                    else "Toujours possible pour une entreprise individuelle (adresse de l'activité), même si tu ne reçois pas de clients chez toi."),
                Option("Société de domiciliation", "Une adresse professionnelle contre un abonnement mensuel (contrat à joindre au dossier) ; sépare vie privée et professionnelle."),
                Option("Local professionnel", "Atelier ou dépôt loué pour stocker matériel et véhicule ; bail commercial ou professionnel, CFE plus élevée.")),
            documents = listOf("Justificatif de domicile de moins de 3 mois", "ou contrat de domiciliation, ou bail du local"),
            cout = "Domicile : gratuit. Domiciliation : abonnement mensuel. Local : loyer et charges.",
            pieges = listOf("Stocker du matériel ou recevoir des clients chez toi alors que le bail ou la copropriété l'interdit.", "Un local augmente la CFE (calculée sur la valeur locative).")),
        Etape("assurances", "Souscrire les assurances", listOf(
            "Assurance décennale : obligatoire pour les travaux de construction (pose, travaux sur l'ouvrage) — l'assureur et la zone couverte doivent figurer sur tes devis et factures.",
            "Responsabilité civile professionnelle (RC pro) : indispensable pour la maintenance et le dépannage.",
            "Demande plusieurs devis (assureurs spécialisés BTP) avant de commencer : l'attestation peut être exigée par tes clients."),
            "https://entreprendre.service-public.gouv.fr/vosdroits/F23634", phase = Phase.PREPARER,
            pourquoi = "Un dommage chez un client (incendie, infiltration en toiture, panne d'installation) peut coûter des dizaines de milliers d'euros. Certaines assurances sont obligatoires et doivent apparaître sur tes devis et factures.",
            comment = listOf("Liste précisément tes activités : maintenance, dépannage, nettoyage, pose, raccordement, travaux en toiture. Une activité non déclarée au contrat n'est pas couverte.",
                "Demande 2 ou 3 devis à des assureurs ou courtiers spécialisés BTP / photovoltaïque.",
                "Vérifie la date d'effet : aucun chantier avant.",
                "Garde l'attestation (clients, donneurs d'ordre et chambre de métiers peuvent la demander)."),
            options = listOf(
                Option("Responsabilité civile décennale", "Obligatoire dès que tu fais des travaux de construction : pose de panneaux, intervention sur la toiture ou l'installation électrique de l'ouvrage. Couvre 10 ans après la fin des travaux. Nom de l'assureur, coordonnées et zone couverte sur chaque devis et facture."),
                Option("Responsabilité civile professionnelle", "Couvre les dommages causés aux clients ou aux tiers pendant tes interventions (maintenance, dépannage, nettoyage). Pas imposée par la loi pour l'électricité, mais exigée par la plupart des clients professionnels."),
                Option("Véhicule professionnel", "Usage professionnel à déclarer, avec garantie du matériel et de l'outillage transportés."),
                Option("Multirisque professionnelle", "Vol et dommages de l'outillage, du stock et du local."),
                Option("Protection juridique", "Prise en charge des litiges : impayés, contestation d'une intervention."),
                Option("Prévoyance et mutuelle", "Revenus en cas d'arrêt de travail ou d'invalidité : la couverture de base des indépendants est faible.")),
            cout = "Très variable selon l'activité et le chiffre d'affaires : demande des devis. La décennale photovoltaïque coûte nettement plus qu'une RC pro seule.",
            pieges = listOf("Une décennale qui ne mentionne pas le photovoltaïque ne couvre pas la pose de panneaux.", "Commencer un chantier avant la date d'effet du contrat.", "Oublier les mentions d'assurance sur les devis et factures.")),
        // ------------------------------------------------------------------ 2. Constituer (sociétés)
        Etape("statuts", "Rédiger et signer les statuts", listOf(
            "Statuts de la société (modèles sur le guichet unique ou chez un professionnel), nomination du dirigeant, siège social, capital."),
            "https://entreprendre.service-public.gouv.fr/vosdroits/F31201", SOCIETES, Phase.CONSTITUER,
            pourquoi = "Les statuts sont le contrat de la société : nom, objet, siège, capital, pouvoirs du dirigeant, partage des bénéfices, entrée et sortie des associés.",
            comment = listOf("Rédige l'objet social assez large pour tes activités futures (ex. « travaux d'électricité ; installation, maintenance et entretien d'installations photovoltaïques ; vente de matériel »).",
                "Fixe le capital et les apports (argent, véhicule, outillage).",
                "Nomme le dirigeant (dans les statuts ou par un acte séparé) et prévois comment fixer sa rémunération.",
                "Signe (signature électronique acceptée). Pour une EURL ou une SASU, les statuts types du guichet unique conviennent à un cas simple."),
            options = listOf(
                Option("Montant du capital", "Libre, à partir de 1 €. Un capital de 1 000 à 5 000 € rassure banques et fournisseurs. Apports en argent versés à la création : au moins la moitié en SAS / SASU, au moins un cinquième en SARL / EURL ; le reste dans les 5 ans."),
                Option("Apports en nature", "Véhicule, outillage… évalués dans les statuts (commissaire aux apports sauf exceptions pour les petits apports)."),
                Option("Rédaction", "Toi-même avec un modèle (gratuit), une plateforme en ligne (quelques centaines d'euros), ou un expert-comptable / avocat (plus cher, conseil sur mesure).")),
            cout = "De 0 € à quelques centaines d'euros selon la rédaction.",
            pieges = listOf("Un objet social trop étroit (seulement « maintenance ») oblige à modifier les statuts pour la pose.", "Copier des statuts sans adapter les clauses d'agrément et de rémunération.")),
        Etape("capital", "Déposer le capital", listOf(
            "Dépose les apports en argent sur un compte bloqué (banque, notaire ou Caisse des dépôts) : tu reçois l'attestation de dépôt des fonds."),
            "https://entreprendre.service-public.gouv.fr/vosdroits/F31201", SOCIETES, Phase.CONSTITUER,
            pourquoi = "L'attestation de dépôt des fonds est exigée dans le dossier d'immatriculation ; elle prouve que le capital annoncé dans les statuts est bien versé.",
            comment = listOf("Ouvre le compte de la société « en formation » à la banque choisie (ou passe par un notaire).", "Verse la part du capital à libérer et récupère l'attestation de dépôt.", "Les fonds sont débloqués sur présentation du Kbis, après l'immatriculation."),
            documents = listOf("Projet de statuts", "Pièce d'identité", "Liste des associés et de leurs apports"),
            cout = "Souvent gratuit en banque ; frais si tu passes par un notaire.", delai = "1 à 5 jours."),
        Etape("annonce", "Publier l'annonce légale de constitution", listOf(
            "Sur un journal ou service de presse en ligne habilité dans le département du siège ; garde l'attestation de parution pour le dossier."),
            "https://entreprendre.service-public.gouv.fr/vosdroits/F35957", SOCIETES, Phase.CONSTITUER,
            pourquoi = "L'annonce informe le public de la naissance de la société ; elle est obligatoire avant l'immatriculation.",
            comment = listOf("Choisis un journal habilité du département du siège (papier ou en ligne).", "Rédige l'annonce : dénomination, forme, capital, siège, objet, durée, dirigeant, greffe d'immatriculation.", "Paie le forfait et télécharge l'attestation de parution."),
            cout = "Forfait 2026 (hors Mayotte et La Réunion) : EURL 124 € HT, SARL 148 € HT, SASU 142 € HT, SAS 199 € HT.", delai = "24 à 48 heures."),
        // ------------------------------------------------------------------ 3. Immatriculer
        Etape("guichet", "Déclarer la création sur le guichet unique", listOf(
            "Seul site officiel : formalites.entreprises.gouv.fr (méfie-toi des sites payants qui l'imitent).",
            "Prépare : pièce d'identité, justificatif de domicile / siège, justificatifs de qualification, déclaration de non-condamnation" +
                if (societe) ", statuts signés, attestation de dépôt du capital, attestation de l'annonce légale, déclaration des bénéficiaires effectifs." else ".",
            if (micro) "Choisis le régime micro-entrepreneur, l'activité artisanale (électricité), le versement libératoire si tu le souhaites et la déclaration trimestrielle (sinon mensuelle)."
            else "Choisis le régime fiscal (IS par défaut pour les SAS, SASU et SARL ; IR par défaut pour l'EURL et l'EI) et le régime de TVA.",
            if (societe) "Frais : immatriculation et inscription de l'activité artisanale (quelques dizaines d'euros) ; le guichet affiche le montant exact avant paiement." else "Coût : gratuit pour une entreprise individuelle."),
            "https://formalites.entreprises.gouv.fr", phase = Phase.IMMATRICULER,
            pourquoi = "C'est la déclaration officielle : elle inscrit l'entreprise au Registre national des entreprises (et au RCS pour une société), prévient l'INSEE, l'URSSAF, les impôts et la chambre de métiers.",
            comment = listOf("Crée ton compte sur formalites.entreprises.gouv.fr puis « Créer une entreprise ».",
                "Indique la forme (${st.libelle}), l'activité principale (électricité, activité artisanale), l'adresse et la date de début d'activité.",
                "Choisis tes options sociales et fiscales (voir les choix ci-dessous).",
                "Joins les pièces, signe électroniquement, paie s'il y a des frais.",
                "Suis le dossier dans ton espace : un service peut te demander une pièce manquante."),
            documents = listOf("Pièce d'identité en cours de validité", "Justificatif d'adresse du siège (ou contrat de domiciliation)", "Justificatifs de qualification en électricité",
                "Déclaration sur l'honneur de non-condamnation et de filiation") +
                (if (societe) listOf("Statuts signés", "Attestation de dépôt des fonds", "Attestation de parution de l'annonce légale", "Déclaration des bénéficiaires effectifs (à remplir en ligne)") else emptyList()),
            options = if (micro) listOf(
                Option("Versement libératoire de l'impôt", "Tu paies 1,7 % de ton chiffre d'affaires avec tes cotisations, et l'impôt sur ce revenu est réglé. Possible si ton revenu fiscal de référence de l'avant-dernière année ne dépasse pas environ 29 300 € par part. Intéressant si ton foyer est imposé à 11 % ou plus ; sinon le barème classique avec abattement de 50 % coûte souvent moins cher. À choisir à la création (ou avant la fin du 3e mois)."),
                Option("Déclaration mensuelle ou trimestrielle", "Mensuelle : petits montants réguliers. Trimestrielle : moins de démarches (30 avril, 31 juillet, 31 octobre, 31 janvier). Modifiable ensuite."),
                Option("Franchise de TVA", "Par défaut : pas de TVA facturée tant que tu restes sous 37 500 € (41 250 € majoré). Option pour la TVA possible si tu achètes beaucoup de matériel ou travailles surtout pour des professionnels."),
                Option("Date de début d'activité", "Elle déclenche les cotisations et le délai de l'ACRE : mets la date réelle de ton premier chantier ou démarchage."))
            else listOf(
                Option("Régime d'imposition", if (st == Declarations.Statut.EURL || st == Declarations.Statut.EI) "Impôt sur le revenu par défaut (bénéfice imposé chez toi) ou option pour l'impôt sur les sociétés (15 % jusqu'à 42 500 €, 25 % au-delà)." else "Impôt sur les sociétés par défaut (15 % jusqu'à 42 500 €, 25 % au-delà) ; option pour l'impôt sur le revenu possible pour une jeune société (5 ans au plus)."),
                Option("Régime de TVA", "Franchise (pas de TVA facturée, sous 37 500 €), réel simplifié (2026 : acomptes et déclaration annuelle ; supprimé au 1er janvier 2027) ou réel normal (déclaration mensuelle)."),
                Option("Date de clôture de l'exercice", "Souvent le 31 décembre ; le premier exercice peut durer jusqu'à 24 mois (pratique si tu crées en cours d'année)."),
                Option("Date de début d'activité", "Elle déclenche les obligations sociales et fiscales.")),
            cout = if (societe) "Tarifs 2026 : immatriculation d'une société commerciale 33,83 €, inscription de l'activité artisanale 15 €, déclaration des bénéficiaires effectifs 19,33 €." else "Gratuit.",
            delai = "Le dossier est en général traité en quelques jours à 3 semaines.",
            pieges = listOf("Les sites privés qui imitent le guichet et facturent la démarche.", "Une activité mal décrite : choisis bien l'électricité (artisanale) pour être rattaché à la chambre de métiers.", "Une date de début d'activité fantaisiste (cotisations, ACRE).")),
        Etape("siret", "Recevoir le SIREN / SIRET", listOf(
            "L'INSEE attribue le SIREN (9 chiffres) et le SIRET de ton établissement (14 chiffres) en général sous 1 à 4 semaines ; tu le reçois par courrier / mail et dans ton espace du guichet unique.",
            "Vérifie-le sur l'annuaire officiel (bouton « Vérifier le SIRET » ci-dessous) : JARVIS remplit alors l'identité de ta société (nom, adresse, code APE, date de création).",
            "Télécharge ton avis de situation au répertoire SIRENE (demandé par les banques et fournisseurs)."),
            "https://annuaire-entreprises.data.gouv.fr", phase = Phase.IMMATRICULER,
            pourquoi = "Le SIRET identifie ton entreprise partout : devis, factures, banque, assurances, comptes chez les grossistes, plateformes de facturation électronique.",
            comment = listOf("Surveille ton espace du guichet unique et ta boîte mail.",
                "Contrôle le code APE attribué (électricité : en général 43.21A « travaux d'installation électrique dans tous locaux » ; une activité surtout de réparation peut avoir un autre code).",
                if (societe) "Télécharge le Kbis (extrait d'immatriculation) sur le site des greffes ou le guichet." else "Télécharge l'extrait du Registre national des entreprises (équivalent du Kbis pour une entreprise individuelle).",
                "Vérifie la fiche dans JARVIS avec le bouton ci-dessous et remplis l'identité de la société."),
            delai = "1 à 4 semaines après la déclaration."),
        // ------------------------------------------------------------------ 4. Démarrer
        Etape("banque", "Ouvrir le compte bancaire professionnel", listOf(
            if (societe) "Obligatoire pour une société : le capital y est déposé, toutes les recettes et dépenses y passent."
            else "Entreprise individuelle : un compte dédié à l'activité est obligatoire dès que le chiffre d'affaires dépasse 10 000 € deux années de suite — mieux vaut l'ouvrir dès le départ.",
            "Renseigne l'IBAN dans JARVIS (Société › Identité) : il apparaît sur tes factures."),
            "https://entreprendre.service-public.gouv.fr/vosdroits/F37018", phase = Phase.DEMARRER,
            pourquoi = "Séparer les flux professionnels et personnels simplifie la comptabilité et les contrôles, et rassure les clients (virements, prélèvements).",
            options = listOf(Option("Banque traditionnelle", "Un conseiller, le crédit (véhicule, matériel), le dépôt d'espèces et de chèques, le terminal de paiement ; frais plus élevés."),
                Option("Banque en ligne pour les pros", "Moins chère, application pratique, cartes virtuelles ; dépôt de chèques ou d'espèces parfois limité, crédit plus rare."),
                Option(if (societe) "Compte de la société" else "Compte dédié", if (societe) "Toujours au nom de la société, ouvert dès le dépôt du capital." else "Il peut s'agir d'un compte personnel réservé à l'activité ; un compte « professionnel » n'est pas obligatoire.")),
            cout = "De 0 à environ 30 € par mois selon l'offre.",
            pieges = listOf("Mélanger dépenses personnelles et professionnelles sur le même compte.")),
        Etape("acre", "Demander les aides (ACRE, chômage, prêts d'honneur)", listOf(
            "ACRE : réduction de cotisations la 1re année — 50 % (25 % pour une micro-entreprise créée à partir du 1er juillet 2026), sous conditions.",
            if (micro) "Micro-entrepreneur : demande à faire dans les 60 jours après le début d'activité, auprès de l'URSSAF." else "Société / EI : accordée sur demande lors de la création, sous conditions (demandeur d'emploi, moins de 26 ans, etc.).",
            "Coche « ACRE » dans l'onglet Déclarations : les cotisations calculées en tiennent compte."),
            "https://entreprendre.service-public.gouv.fr/vosdroits/F11677", phase = Phase.DEMARRER,
            pourquoi = "Les premiers mois, les revenus sont faibles et les dépenses importantes : ces aides réduisent tes cotisations et sécurisent tes revenus.",
            options = listOf(
                Option("ACRE", "Réduction des cotisations jusqu'à la fin du 3e trimestre civil qui suit le début d'activité. Micro : 50 %, ou 25 % pour une création à partir du 1er juillet 2026. Société ou EI au réel : exonération partielle (totale jusqu'à 75 % du plafond de la Sécurité sociale, dégressive au-delà). Réservée notamment aux demandeurs d'emploi, bénéficiaires du RSA ou de l'ASS, moins de 26 ans, créateurs en quartier prioritaire."),
                Option("Maintien de l'allocation chômage (ARE)", "Si tu touches l'ARE : elle continue chaque mois, réduite selon tes revenus d'activité. Le plus sûr quand les débuts sont lents."),
                Option("ARCE", "À la place du maintien : une partie de tes droits restants (60 %) versée en capital en deux fois (au début, puis 6 mois plus tard). Utile pour financer du matériel, mais tu perds la sécurité mensuelle. À demander à France Travail avec ton justificatif d'immatriculation."),
                Option("Prêts d'honneur", "Prêt à taux zéro sans garantie (Initiative France, Réseau Entreprendre…) qui facilite le prêt bancaire, avec un accompagnement.")),
            cout = "Gratuit.", delai = if (micro) "ACRE : demande dans les 60 jours suivant le début d'activité." else "À demander dès la création.",
            pieges = listOf("Laisser passer le délai de demande de l'ACRE.", "Choisir l'ARCE sans réserve de trésorerie : plus de revenu mensuel garanti ensuite.")),
        Etape("espaces", "Créer tes espaces en ligne", listOf(
            if (micro) "URSSAF : autoentrepreneur.urssaf.fr (déclaration du chiffre d'affaires et paiement)." else "URSSAF : urssaf.fr (dirigeant non salarié) et net-entreprises.fr (DSN si salaires).",
            "Impôts : espace professionnel sur impots.gouv.fr (TVA, CFE, IS) — à créer avec ton SIREN."),
            "https://www.impots.gouv.fr/professionnel", phase = Phase.DEMARRER,
            pourquoi = "Toutes les déclarations et tous les paiements se font en ligne ; sans ces espaces, tu ne peux ni déclarer ni payer (et l'avis de CFE n'est plus envoyé par courrier).",
            comment = listOf(if (micro) "URSSAF : crée ton compte sur autoentrepreneur.urssaf.fr avec ton SIRET (ou l'appli « AutoEntrepreneur URSSAF »)." else "URSSAF : crée ton espace sur urssaf.fr ; net-entreprises.fr si tu verses des salaires (DSN).",
                "Impôts : crée l'espace professionnel sur impots.gouv.fr avec ton SIREN, puis adhère aux services (déclarer, payer, messagerie). Un code d'activation arrive par courrier au siège.",
                "Chambre de métiers : active ton espace pour tes attestations et formations.",
                "Ameli : la santé des indépendants est gérée par l'Assurance maladie (compte ameli habituel)."),
            delai = "Quelques jours (code d'activation par courrier pour les impôts)."),
        Etape("comptable", "Organiser ta comptabilité", listOf(
            if (micro) "Micro : livre des recettes (et registre des achats si tu vends du matériel) ; JARVIS tient tout depuis tes factures encaissées." else "Comptabilité complète : bilan, compte de résultat et déclarations fiscales chaque année."),
            phase = Phase.DEMARRER,
            pourquoi = "Les pièces comptables sont exigées en cas de contrôle (conservation 10 ans) et servent à calculer cotisations et impôts.",
            options = listOf(Option("Toi-même avec JARVIS", if (micro) "Suffisant en micro : factures, encaissements, notes de frais et déclarations calculées dans l'onglet Société." else "Possible avec un logiciel comptable, mais la liasse fiscale demande des connaissances."),
                Option("Expert-comptable", "Tenue, bilan, liasse, conseils, paie : abonnement mensuel. Fortement conseillé pour une société."),
                Option("Expert-comptable en ligne", "Moins cher, échanges par application ; moins de conseil personnalisé."),
                Option("Organisme de gestion agréé", "Facultatif : accompagnement et contrôle de cohérence des déclarations.")),
            cout = if (micro) "0 € avec JARVIS." else "Expert-comptable : de l'ordre de 1 000 à 2 500 € HT par an pour une petite société (à comparer).",
            pieges = listOf("Jeter les tickets et factures d'achat : conserve-les 10 ans (JARVIS garde les justificatifs des notes de frais).")),
        Etape("protection", "Compléter ta protection sociale", listOf(
            "Mutuelle santé, prévoyance (arrêt de travail, invalidité, décès) et retraite : la couverture de base d'un dirigeant est souvent faible."),
            phase = Phase.DEMARRER,
            pourquoi = "Un accident de chantier ou une maladie peut arrêter ton activité : les indemnités journalières de base des indépendants sont faibles, et aucun dirigeant n'a l'assurance chômage.",
            options = listOf(Option("Mutuelle santé", "Complète les remboursements ; " + (if (st == Declarations.Statut.SASU || st == Declarations.Statut.SAS) "en SAS/SASU, un contrat collectif peut être pris par la société." else "pour un indépendant au réel, contrat déductible du bénéfice (dans des limites).")),
                Option("Prévoyance", "Indemnités en cas d'arrêt de travail, rente d'invalidité, capital décès : la plus importante pour un métier à risques."),
                Option("Retraite (PER)", "Épargne retraite déductible des revenus imposables (dans des plafonds)."),
                Option("Assurance perte d'emploi du dirigeant", "Assurance privée facultative (aucune assurance chômage pour un dirigeant).")),
            cout = "Selon les garanties : demande plusieurs devis."),
        Etape("mentions", "Mettre à jour devis et factures", listOf(
            "SIRET, forme et capital (société), adresse, numéro de TVA intracommunautaire ou « TVA non applicable, art. 293 B du CGI » (franchise), assurance décennale (assureur, contrat, zone couverte).",
            "Dans JARVIS : Société › Identité (SIRET, RCS / RM, code APE, mentions) — elles sont reprises sur tous les PDF."),
            "https://entreprendre.service-public.gouv.fr/vosdroits/F31808", phase = Phase.DEMARRER,
            pourquoi = "Une facture incomplète expose à une amende (jusqu'à 15 € par mention manquante, plafonnée) et peut retarder le paiement ; certaines mentions protègent aussi le client (assurance).",
            comment = listOf("Complète l'identité de la société dans JARVIS : elle est reprise sur chaque devis et facture.", "Fais un devis avant tout dépannage, entretien ou réparation de plus de 150 € TTC (ou si le client le demande).", "Numérote les factures sans trou, dans l'ordre chronologique (JARVIS le fait)."),
            options = listOf(Option("Toujours", "Date, numéro unique, ton nom ou dénomination, adresse, SIREN/SIRET, nom et adresse du client, désignation précise, quantité, prix unitaire HT, total HT, date de paiement, pénalités de retard."),
                Option("Entreprise individuelle", "La mention « EI » ou « entrepreneur individuel » à côté de ton nom ; RNE en tant qu'artisan."),
                Option("Société", "Forme juridique, montant du capital, RCS et ville du greffe."),
                Option("TVA", "Numéro de TVA intracommunautaire, taux et montant par taux, total TTC ; ou « TVA non applicable, art. 293 B du CGI » en franchise."),
                Option("Clients professionnels", "Indemnité forfaitaire de 40 € pour frais de recouvrement ; depuis le 1er septembre 2026, le SIREN du client, la nature de l'opération (biens, services ou les deux) et, le cas échéant, l'option pour la TVA sur les débits."),
                Option("Travaux", "Assurance décennale : nom et coordonnées de l'assureur, couverture géographique du contrat.")),
            pieges = listOf("Oublier les mentions d'assurance sur un devis de pose.", "Modifier une facture déjà émise : fais un avoir.")),
        Etape("cfe", "Préparer la CFE (cotisation foncière des entreprises)", listOf(
            "Exonérée l'année de création si tu déposes la déclaration initiale 1447-C-SD avant le 31 décembre ; base réduite de moitié l'année suivante ; paiement en ligne au plus tard le 15 décembre."),
            "https://entreprendre.service-public.gouv.fr/vosdroits/F23547", phase = Phase.DEMARRER,
            pourquoi = "La CFE est due par presque toutes les entreprises, même sans local (elle est alors calculée sur une base minimale fixée par la commune).",
            comment = listOf("Dépose la déclaration initiale 1447-C-SD dans ton espace professionnel avant le 31 décembre de l'année de création.", "L'année suivante, la base est réduite de moitié.", "Paie avant le 15 décembre dans l'espace professionnel (l'avis n'est pas envoyé par courrier)."),
            options = listOf(Option("Exonérations", "Année de création exonérée (avec la déclaration 1447-C-SD) ; exonération quand le chiffre d'affaires HT de l'avant-dernière année ne dépasse pas 5 000 € ; exonérations locales possibles (zones aidées).")),
            pieges = listOf("Ne pas consulter l'espace professionnel en novembre-décembre : la majoration de 5 % s'applique au retard.")),
        Etape("efacture", "Préparer la facturation électronique", listOf(
            "Réception des factures électroniques obligatoire depuis le 1er septembre 2026 ; émission obligatoire au 1er septembre 2027 pour les petites entreprises."),
            "https://www.impots.gouv.fr/professionnel", phase = Phase.DEMARRER,
            pourquoi = "Entre entreprises, les factures ne circuleront plus en PDF par mail mais par des plateformes agréées par l'administration ; les ventes aux particuliers seront transmises (e-reporting).",
            comment = listOf("Choisis une plateforme agréée (liste officielle sur impots.gouv.fr) pour recevoir les factures de tes fournisseurs.", "Vérifie qu'elle sait émettre tes factures avant le 1er septembre 2027 (ou qu'elle accepte celles faites dans JARVIS).", "Ajoute les nouvelles mentions sur tes factures (SIREN du client, nature de l'opération)."),
            options = listOf(Option("Plateforme gratuite d'entrée de gamme", "Suffisante pour recevoir et émettre peu de factures."), Option("Plateforme de ton expert-comptable ou de ta banque", "Pratique si tu leur confies déjà ta gestion.")),
            pieges = listOf("Attendre l'été 2027 : les plateformes seront saturées.")),
        Etape("declarations", "Régler l'onglet Déclarations", listOf(
            "Choisis le statut, l'activité, la périodicité URSSAF, le régime de TVA, l'ACRE et la date de début d'activité : JARVIS calcule alors chaque échéance (montant, date, marche à suivre)."),
            phase = Phase.DEMARRER,
            pourquoi = "Avec ces réglages, JARVIS te prévient de chaque déclaration et calcule les montants à partir de tes factures encaissées, notes de frais et bulletins de paie.",
            comment = listOf("Onglet Société › Déclarations : choisis ton statut et ton régime.", "Indique la date de début d'activité.", "Coche chaque déclaration faite : elle disparaît des rappels.")),
        ).filter { it.pour == null || st in it.pour }
    }

    /** Étape désignée par son identifiant ou des mots de son titre (« assurances », « annonce légale »). */
    fun etape(st: Declarations.Statut, quoi: String): Etape? {
        val n = fr.dd.jarvis.societe.core.normaliser(quoi)
        val l = etapes(st)
        return l.firstOrNull { it.id == n } ?: l.firstOrNull { e ->
            val t = fr.dd.jarvis.societe.core.normaliser(e.titre)
            n.split(' ').filter { it.length >= 4 }.let { m -> m.isNotEmpty() && m.all { t.contains(it.take(6)) } }
        } ?: l.firstOrNull { e -> fr.dd.jarvis.societe.core.normaliser(e.titre).split(' ').any { it.length >= 5 && n.contains(it.take(6)) } }
    }

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
