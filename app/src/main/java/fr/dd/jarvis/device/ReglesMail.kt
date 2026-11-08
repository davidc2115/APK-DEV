package fr.dd.jarvis.device

/**
 * Organisation des mails de l'utilisateur : SES libellés en couleur (Facebook, Super U/…, Impôts, Pubs, Réseaux
 * sociaux, Comptes et sécurité, Banque et crédit, Maison, Enfants, Administratif, Travail) complétés par Santé,
 * Achats et livraisons et Personnel ; toutes les newsletters et publicités dans « Pubs ». Les règles par expéditeur
 * servent au grand ménage (recherches Gmail) ET au tri de tous les jours (avant l'IA, qui ne s'occupe que du reste).
 */
object ReglesMail {
    data class Libelle(val nom: String, val fond: String, val texte: String, val description: String)

    val LIBELLES = listOf(
        Libelle("Super U/Tickets de caisse", "#fb4c2f", "#ffffff", "tickets de caisse des magasins U"),
        Libelle("Super U/Commandes", "#822111", "#ffffff", "commandes Courses U, drive, livraisons U"),
        Libelle("Super U/Pubs", "#ffad47", "#000000", "promotions, jeux et offres des magasins U"),
        Libelle("Facebook", "#4a86e8", "#ffffff", "tout ce qui vient de Facebook (notifications, amis, groupes)"),
        Libelle("Réseaux sociaux", "#a479e2", "#ffffff", "Instagram, TikTok, LinkedIn, X, YouTube, Snapchat, WhatsApp…"),
        Libelle("Impôts", "#076239", "#ffffff", "impôts, taxe foncière, taxe d'habitation, prélèvement à la source"),
        Libelle("Banque et crédit", "#2da2bb", "#ffffff", "banques, crédits, cartes, PayPal, Klarna, assurances"),
        Libelle("Santé", "#83334c", "#ffffff", "Ameli, Mon espace santé, mutuelle, médecins, optique, pharmacie"),
        Libelle("Enfants", "#f691b2", "#000000", "école, cantine, périscolaire, photos de classe, activités des enfants"),
        Libelle("Maison", "#7a4706", "#ffffff", "énergie, box internet, téléphone, logement, travaux, MaPrimeRénov', commune, animaux"),
        Libelle("Achats et livraisons", "#43d692", "#000000", "commandes, colis, suivis de livraison (Amazon, Temu, Cdiscount, transporteurs)"),
        Libelle("Comptes et sécurité", "#666666", "#ffffff", "comptes en ligne, connexions, codes, alertes de sécurité, abonnements et services numériques, IA"),
        Libelle("Administratif", "#1c4587", "#ffffff", "démarches et papiers officiels (CAF, service-public, France Travail…), hors impôts"),
        Libelle("Travail", "#16a765", "#ffffff", "travail, société photovoltaïque, clients, fournisseurs, chantiers"),
        Libelle("Personnel", "#41236d", "#ffffff", "mails écrits par de vraies personnes (famille, amis)"),
        Libelle("Pubs", "#fad165", "#000000", "TOUTES les newsletters et publicités, dans un seul libellé"),
    )
    val TRAITE = Libelle("Traité", "#000000", "#ffffff", "mail lu et traité")
    val NOMS = LIBELLES.map { it.nom }

    /** Anciennes catégories créées par Jarvis (remplacées par les libellés ci-dessus). */
    private val ANCIENNES = setOf("Urgent", "Clients", "Chantiers et planning", "Fournisseurs et matériel", "Factures et paiements",
        "Banque et assurances", "Notifications", "Newsletters et promos", "Perso")

    /** Libellé géré par Jarvis (à retirer d'un mail quand il change de libellé) : actuel, ou ancienne catégorie / sous-libellé. */
    fun estGere(nom: String): Boolean = nom in NOMS || nom.substringBefore('/') in ANCIENNES ||
        (nom.contains('/') && nom.substringBefore('/') in setOf("Administratif", "Banque et crédit", "Maison", "Travail", "Impôts"))

    /** Libellé obsolète : ancienne catégorie de Jarvis ou sous-libellé par expéditeur (à vider puis supprimer). */
    fun estObsolete(nom: String): Boolean = estGere(nom) && nom !in NOMS

    /**
     * Règle : expéditeurs (adresse ou domaine), mots du sujet facultatifs, [sansPromo] = les publicités de cet
     * expéditeur vont dans « Pubs », [categorie] = catégorie Gmail (promotions, social, primary) pour les règles de repli.
     */
    data class Regle(val libelle: String, val expediteurs: List<String> = emptyList(), val sujet: List<String> = emptyList(),
                     val sansPromo: Boolean = false, val categorie: String = "", val exclure: List<String> = emptyList())

    private val COMMANDE = listOf("commande", "expédié", "expédiée", "expedie", "expédition", "livraison", "livré", "livrée", "colis", "suivi", "retrait", "remboursement", "en route", "votre achat", "facture")
    private val AUTOMATIQUES = listOf("noreply", "no-reply", "ne-pas-repondre", "nepasrepondre", "notification", "notifications", "newsletter", "info", "infos", "news", "contact", "mailer", "bounce", "support", "service")

    val REGLES = listOf(
        Regle("Super U/Tickets de caisse", listOf("ticket-caisse@eticket.cooperative-u.fr", "eticket.cooperative-u.fr")),
        Regle("Super U/Commandes", listOf("systeme-u.fr", "coursesu.com", "coursesu.fr", "cooperative-u.fr", "magasins-u.com"), sujet = COMMANDE, sansPromo = true),
        Regle("Super U/Pubs", listOf("cooperative-u.fr", "systeme-u.fr", "magasins-u.com", "coursesu.com", "coursesu.fr")),
        Regle("Facebook", listOf("facebookmail.com", "facebook.com", "fb.com")),
        Regle("Réseaux sociaux", listOf("instagram.com", "tiktok.com", "linkedin.com", "snapchat.com", "twitter.com", "x.com", "pinterest.com",
            "youtube.com", "threads.net", "whatsapp.com", "messenger.com", "discord.com", "reddit.com", "twitch.tv", "meta.com")),
        Regle("Impôts", listOf("impots.gouv.fr", "dgfip.finances.gouv.fr")),
        Regle("Impôts", sujet = listOf("taxe foncière", "taxe d'habitation", "avis d'impôt", "impôt sur le revenu", "prélèvement à la source", "déclaration de revenus")),
        Regle("Santé", listOf("monespacesante.fr", "ameli.fr", "assurance-maladie.fr", "doctolib.fr", "optic2000.com", "afflelou", "harmonie-mutuelle.fr",
            "mgen.fr", "mutuelle", "maiia.com", "qare.fr", "pharmacie", "laboratoire", "cerballiance", "biogroup"), sansPromo = true),
        Regle("Enfants", listOf("bl-citoyen.fr", "gestionenfance.com", "mjc-venarey-les-laumes.fr", "maclasse.photos", "ecoledirecte.com",
            "index-education.net", "pronote", "klassroom", "toutemonannee", "kidizz", "e-lyco", "beneylu")),
        Regle("Banque et crédit", listOf("ca-cb.fr", "credit-agricole.fr", "credit-agricole.com", "cetelem.fr", "cofidis.fr", "paypal.com", "paypal.fr",
            "klarna.com", "allianz.fr", "bpifrance.fr", "boursorama", "lcl.fr", "labanquepostale.fr", "societegenerale", "bnpparibas", "caisse-epargne",
            "revolut", "lydia-app", "floa.fr", "oney.fr", "systempay", "sumup", "younited", "sofinco", "franfinance", "carrefour-banque", "hellobank")),
        Regle("Achats et livraisons", listOf("mondialrelay", "colissimo", "laposte.fr", "chronopost", "dpd.fr", "dpd.com", "ups.com", "gls-france",
            "relaiscolis", "notico.com", "dhl", "colisprive", "shop2shop")),
        Regle("Achats et livraisons", listOf("amazon.fr", "amazon.com", "cdiscount", "temuemail.com", "temu.com", "shein", "aliexpress", "vinted",
            "leboncoin.fr", "micromania", "fnac", "darty", "ebay", "rakuten", "decathlon", "ikea", "leroymerlin", "castorama", "manomano",
            "action.com", "okaidi", "kiabi", "zalando", "veepee", "showroomprive", "lahalle", "chaussea", "gemo.fr", "boulanger.com"), sujet = COMMANDE,
            sansPromo = true, exclure = listOf("news.", "newsletter", "crm.", "marketing")),
        Regle("Maison", listOf("engie", "edf.fr", "totalenergies", "free-energie", "freeenergie", "clef-energies", "clefenergies", "enedis", "grdf",
            "veolia", "suez", "saur.fr", "freetelecom.fr", "free-mobile.fr", "anah.gouv.fr", "anah.fr", "maprimerenov", "erpstyx.com",
            "terres-auxois", "vetorino.com", "orange-business", "bouyguestelecom", "sosh.fr"), sansPromo = true),
        Regle("Comptes et sécurité", listOf("accounts.google.com", "google.com", "openai.com", "anthropic.com", "jenji.io", "base44.com", "railway.app",
            "render.com", "github.com", "huggingface.co", "openrouter.ai", "x.ai", "microsoft.com", "apple.com", "samsung.com", "xiaomi.com", "mi.com",
            "dropbox.com", "notion.so", "replit.com", "netlify", "vercel", "cloudflare", "crunchyroll.com", "netflix.com", "spotify.com", "deezer.com",
            "disneyplus", "primevideo", "canva.com", "adobe.com", "tomtom.com", "picovoice.ai", "groq.com", "fal.ai", "bitrise", "midjourney", "spacex"), sansPromo = true),
        Regle("Administratif", listOf("service-public.fr", "caf.fr", "franceconnect", "ants.gouv.fr", "francetravail", "pole-emploi", "urssaf", "msa.fr",
            "digiposte.fr", "gouv.fr", "mairie", "notaires", "legalstart", "infogreffe", "inpi.fr"), sansPromo = true),
        Regle("Travail", listOf("fclh.fr")),
        Regle("Pubs", categorie = "promotions"),
        Regle("Pubs", listOf("newsletter", "news.", "crm.", "galaxy.ai", "marketing", "mailing", "emailing", "promo")),
        Regle("Réseaux sociaux", categorie = "social"),
        Regle("Personnel", categorie = "primary", exclure = AUTOMATIQUES),
    )

    private fun q(s: String) = "\"" + s.replace("\"", "").removePrefix("@").trimEnd('.') + "\""

    /** Recherche Gmail d'une règle (sans l'exclusion des mails déjà traités). */
    fun requete(r: Regle): String = listOfNotNull(
        r.expediteurs.takeIf { it.isNotEmpty() }?.joinToString(" OR ", "from:(", ")") { q(it) },
        r.sujet.takeIf { it.isNotEmpty() }?.joinToString(" OR ", "subject:(", ")") { q(it) },
        r.categorie.takeIf { it.isNotBlank() }?.let { "category:$it" },
        if (r.sansPromo) "-category:promotions" else null,
        r.exclure.takeIf { it.isNotEmpty() }?.joinToString(" OR ", "-from:(", ")") { q(it) },
    ).joinToString(" ")

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /**
     * L'expéditeur [de] correspond-il à [motif] ? Adresse complète, domaine (et ses sous-domaines, jamais un autre
     * domaine qui finit pareil : « x.com » ≠ « dropbox.com »), début de sous-domaine (« news. ») ou simple mot (« engie »).
     */
    internal fun correspondExp(de: String, motif: String): Boolean {
        val t = motif.lowercase().trim().removePrefix("@")
        if (t.isBlank()) return false
        val domaine = de.substringAfter('@', de)
        return when {
            t.contains('@') -> de == t || de.endsWith(t)
            t.endsWith('.') -> de.contains("@$t") || domaine.contains(".$t")
            t.contains('.') -> domaine == t || domaine.endsWith(".$t")
            else -> de.contains(t)
        }
    }

    /**
     * Même règles, en mémoire (tri de tous les jours) : [labels] = étiquettes Gmail du mail (CATEGORY_PROMOTIONS…),
     * [clients] = adresses des clients connus (fiche Société) → « Travail ». Null = à confier à l'IA.
     */
    fun classer(expediteur: String, sujet: String, labels: Collection<String> = emptyList(), clients: Collection<String> = emptyList()): String? {
        val de = expediteur.lowercase().trim()
        val s = norm(sujet)
        if (de.isNotBlank() && clients.any { it.isNotBlank() && de.contains(it.lowercase()) }) return "Travail"
        val promo = "CATEGORY_PROMOTIONS" in labels
        for (r in REGLES) {
            if (r.expediteurs.isNotEmpty() && r.expediteurs.none { correspondExp(de, it) }) continue
            if (r.sujet.isNotEmpty() && r.sujet.none { s.contains(norm(it)) }) continue
            if (r.sansPromo && promo) continue
            if (r.categorie.isNotBlank()) {
                val cat = "CATEGORY_" + (if (r.categorie == "primary") "PERSONAL" else r.categorie.uppercase())
                if (cat !in labels) continue
            }
            if (r.exclure.any { correspondExp(de, it) || de.substringBefore('@').contains(it.trimEnd('.')) }) continue
            if (r.expediteurs.isEmpty() && r.sujet.isEmpty() && r.categorie.isBlank()) continue
            return r.libelle
        }
        return null
    }

    /** Consigne de tri pour l'IA (mails qu'aucune règle n'a reconnus). */
    fun consigneIa(): String = LIBELLES.joinToString("\n") { "- ${it.nom} : ${it.description}" }
}
