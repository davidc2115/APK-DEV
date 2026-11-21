package fr.dd.jarvis.societe

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.CommandesDirectes
import fr.dd.jarvis.ai.DatesFr
import fr.dd.jarvis.societe.core.NoteFrais
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Notes de frais de la Société : dépense avec justificatif (photo du ticket, PDF), montant TTC, TVA, date, fournisseur,
 * catégorie, moyen de paiement ; statut à traiter / traitée / remboursée ; suppression. Le ticket photographié est lu par
 * l'IA (montant, TVA, date, fournisseur, catégorie) ; à la voix : « ajoute une note de frais de 45 € chez Total pour du
 * carburant », « mes notes de frais à traiter », « total des frais de novembre ».
 */
object Frais {
    val CATEGORIES = linkedMapOf(
        "carburant" to "⛽ Carburant", "peage" to "🛣️ Péage", "parking" to "🅿️ Parking", "repas" to "🍽️ Repas", "hebergement" to "🏨 Hébergement",
        "transport" to "🚆 Transport", "materiel" to "🔧 Matériel / fournitures", "outillage" to "🛠️ Outillage", "vehicule" to "🚐 Véhicule (entretien)",
        "telephone" to "📱 Téléphone / internet", "logiciel" to "💻 Logiciel / abonnement", "assurance" to "🛡️ Assurance", "formation" to "🎓 Formation",
        "banque" to "🏦 Frais bancaires", "kilometrique" to "🚗 Indemnités kilométriques", "autre" to "🧾 Autre",
    )
    val PAIEMENTS = linkedMapOf("cb_pro" to "CB pro", "cb_perso" to "CB perso (à rembourser)", "especes" to "Espèces", "virement" to "Virement", "prelevement" to "Prélèvement")
    val STATUTS = linkedMapOf("a_traiter" to "⏳ À traiter", "traite" to "✅ Traitée", "rembourse" to "💶 Remboursée")

    fun libelle(cat: String) = CATEGORIES[cat] ?: CATEGORIES.getValue("autre")

    /** Catégorie devinée d'après le fournisseur / la description. */
    fun categorieDe(texte: String): String {
        val n = normaliser(texte)
        return when {
            Regex("\\b(carburant|essence|gasoil|gazole|diesel|sp95|sp98|total|totalenergies|esso|shell|bp|avia|station)\\b").containsMatchIn(n) -> "carburant"
            Regex("\\b(peage|autoroute|aprr|sanef|vinci autoroutes|cofiroute|ulys|bip&go)\\b").containsMatchIn(n) -> "peage"
            Regex("\\b(parking|stationnement|horodateur|indigo|effia)\\b").containsMatchIn(n) -> "parking"
            Regex("\\b(restaurant|repas|dejeuner|diner|sandwich|boulangerie|brasserie|mcdo|burger|pizza|cafe|traiteur)\\b").containsMatchIn(n) -> "repas"
            Regex("\\b(hotel|hebergement|nuitee|ibis|airbnb|campanile|b&b)\\b").containsMatchIn(n) -> "hebergement"
            Regex("\\b(sncf|train|billet|bus|tram|taxi|uber|avion|vol)\\b").containsMatchIn(n) -> "transport"
            Regex("\\b(perceuse|visseuse|outil|outillage|multimetre|pince|echelle|harnais)\\b").containsMatchIn(n) -> "outillage"
            Regex("\\b(cable|disjoncteur|connecteur|mc4|fusible|gaine|materiel|fourniture|rexel|sonepar|cged|leroy merlin|castorama|brico|point p)\\b").containsMatchIn(n) -> "materiel"
            Regex("\\b(garage|vidange|pneu|pneus|norauto|feu vert|controle technique|entretien)\\b").containsMatchIn(n) -> "vehicule"
            Regex("\\b(orange|sfr|free|bouygues|forfait|telephone|internet|box)\\b").containsMatchIn(n) -> "telephone"
            Regex("\\b(abonnement|logiciel|licence|google workspace|microsoft|adobe|saas)\\b").containsMatchIn(n) -> "logiciel"
            Regex("\\b(assurance|axa|maaf|macif|allianz|decennale|rc pro)\\b").containsMatchIn(n) -> "assurance"
            Regex("\\b(formation|habilitation|stage|qualipv)\\b").containsMatchIn(n) -> "formation"
            Regex("\\b(banque|frais bancaires|commission|agios)\\b").containsMatchIn(n) -> "banque"
            Regex("\\b(km|kilometres|kilometrique)\\b").containsMatchIn(n) -> "kilometrique"
            else -> "autre"
        }
    }

    // ------------------------------------------------------------------ justificatifs

    fun dossier(app: JarvisApp) = File(app.filesDir, "societe/frais").apply { mkdirs() }
    fun fichier(app: JarvisApp, nf: NoteFrais): File? = nf.justificatif.takeIf { it.isNotBlank() }?.let { File(app.filesDir, it) }?.takeIf { it.exists() }

    /** Copie le justificatif dans le dossier privé (et dans le coffre Obsidian) ; chemin relatif. */
    fun garderJustificatif(app: JarvisApp, octets: ByteArray, extension: String, date: String, fournisseur: String): String {
        val nom = "${date}_${fournisseur.replace(Regex("[^A-Za-z0-9À-ÿ_-]+"), "_").take(40).ifBlank { "frais" }}_${UUID.randomUUID().toString().take(4)}.$extension"
        File(dossier(app), nom).writeBytes(octets)
        runCatching { app.obsidian.saveBytes("Société/Notes de frais/${date.take(4)}", nom, octets, if (extension == "pdf") "application/pdf" else "image/jpeg") }
        return "societe/frais/$nom"
    }

    // ------------------------------------------------------------------ données

    private fun service(app: JarvisApp) = app.societe

    fun liste(app: JarvisApp, statut: String? = null): List<NoteFrais> =
        app.societe.data.frais.filter { statut == null || it.statut == statut }.sortedWith(compareByDescending<NoteFrais> { it.date }.thenByDescending { it.creeLe })

    fun ajouter(app: JarvisApp, nf0: NoteFrais): NoteFrais {
        val nf = nf0.copy(id = nf0.id.ifBlank { UUID.randomUUID().toString().take(8) }, creeLe = nf0.creeLe.ifBlank { LocalDateTime.now().withNano(0).toString().replace('T', ' ').take(16) },
            categorie = nf0.categorie.takeIf { it in CATEGORIES } ?: categorieDe("${nf0.fournisseur} ${nf0.description}"))
        service(app).repo.update { d -> d.copy(frais = d.frais.filterNot { it.id == nf.id } + nf) }
        journaliser(app, nf)
        return nf
    }

    fun modifier(app: JarvisApp, nf: NoteFrais) { service(app).repo.update { d -> d.copy(frais = d.frais.map { if (it.id == nf.id) nf else it }) } }

    fun marquer(app: JarvisApp, id: String, statut: String): Boolean {
        if (app.societe.data.frais.none { it.id == id } || statut !in STATUTS) return false
        service(app).repo.update { d -> d.copy(frais = d.frais.map { if (it.id == id) it.copy(statut = statut) else it }) }
        return true
    }

    fun supprimer(app: JarvisApp, id: String): Boolean {
        val nf = app.societe.data.frais.firstOrNull { it.id == id } ?: return false
        service(app).repo.update { d -> d.copy(frais = d.frais.filterNot { it.id == id }) }
        fichier(app, nf)?.delete()
        return true
    }

    /** Ligne ajoutée au tableau des frais de l'année dans Obsidian (Société › Comptabilité). */
    private fun journaliser(app: JarvisApp, nf: NoteFrais) = runCatching {
        val chemin = "Société/Comptabilité/Notes de frais ${nf.date.take(4)}"
        val existant = runCatching { app.obsidian.readNote(chemin) }.getOrNull()
        val ligne = "| ${nf.date} | ${nf.fournisseur.replace("|", "/")} | ${libelle(nf.categorie)} | ${nf.description.replace("|", "/")} | ${euros(nf.ttc - nf.tva)} | ${euros(nf.tva)} | ${euros(nf.ttc)} | ${PAIEMENTS[nf.paiement] ?: ""} |\n"
        val contenu = if (existant.isNullOrBlank()) "# Notes de frais ${nf.date.take(4)}\n\n| Date | Fournisseur | Catégorie | Objet | HT | TVA | TTC | Paiement |\n|---|---|---|---|---|---|---|---|\n$ligne" else ligne
        app.obsidian.writeNote(chemin, contenu, append = !existant.isNullOrBlank())
    }

    fun euros(x: Double) = String.format(java.util.Locale.FRANCE, "%,.2f €", x).replace(' ', ' ').replace(' ', ' ')

    data class Totaux(val nombre: Int, val ttc: Double, val tva: Double) { val ht get() = ttc - tva }

    fun totaux(l: List<NoteFrais>) = Totaux(l.size, l.sumOf { it.ttc }, l.sumOf { it.tva })

    fun duMois(app: JarvisApp, mois: String) = app.societe.data.frais.filter { it.date.startsWith(mois) }

    fun ligne(nf: NoteFrais) = "${libelle(nf.categorie).substringBefore(' ')} ${nf.date.drop(5).split('-').reversed().joinToString("/")} · ${nf.fournisseur.ifBlank { libelle(nf.categorie).substringAfter(' ') }}" +
        (if (nf.description.isNotBlank()) " — ${nf.description}" else "") + " · **${euros(nf.ttc)}**" + (if (nf.tva > 0) " (TVA ${euros(nf.tva)})" else "") +
        " · ${STATUTS[nf.statut]}" + (if (nf.justificatif.isBlank()) " · ⚠️ sans justificatif" else " · 📎")

    fun texte(app: JarvisApp, statut: String? = null, mois: String? = null): String {
        val l = liste(app, statut).filter { mois == null || it.date.startsWith(mois) }
        if (l.isEmpty()) return "🧾 Aucune note de frais${if (statut == "a_traiter") " à traiter" else ""}${mois?.let { " en ${moisFr(it)}" }.orEmpty()}."
        val t = totaux(l)
        return "🧾 Notes de frais${if (statut == "a_traiter") " à traiter" else ""}${mois?.let { " — ${moisFr(it)}" }.orEmpty()} : ${t.nombre}, ${euros(t.ttc)} TTC (TVA ${euros(t.tva)})\n" +
            l.take(25).joinToString("\n") { "- " + ligne(it) } + (if (l.size > 25) "\n…" else "") + "\n\n(Onglet Société › Frais pour les justificatifs, les marquer traitées ou les supprimer.)"
    }

    fun moisFr(m: String): String = runCatching {
        val d = LocalDate.parse("$m-01")
        listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")[d.monthValue - 1] + " ${d.year}"
    }.getOrDefault(m)

    // ------------------------------------------------------------------ lecture du ticket (IA)

    private const val CONSIGNE_TICKET = """Lis ce ticket de caisse / cette facture d'une dépense professionnelle. JSON :
{"fournisseur":"nom du commerce","date":"yyyy-MM-dd ou vide","ttc":"montant total TTC (nombre)","tva":"montant total de TVA indiqué (nombre, 0 si absent)",
"taux_tva":"taux principal (20, 10, 5.5…)","categorie":"carburant|peage|parking|repas|hebergement|transport|materiel|outillage|vehicule|telephone|logiciel|assurance|formation|banque|autre",
"description":"objet court (ex. « gazole 45 L », « déjeuner chantier »)","paiement":"cb|especes|virement|prelevement|inconnu"}
Ne devine jamais un montant illisible : mets une chaîne vide."""

    private fun nombre(s: String): Double? = s.replace(Regex("[^0-9,.-]"), "").replace(',', '.').let { t ->
        // « 1.234.56 » : seul le dernier point est décimal.
        val i = t.lastIndexOf('.'); if (i < 0) t else t.substring(0, i).replace(".", "") + t.substring(i)
    }.toDoubleOrNull()

    /** Note de frais pré-remplie depuis la photo du ticket (à vérifier avant d'enregistrer). */
    suspend fun lireTicket(app: JarvisApp, octets: ByteArray, mime: String = "image/jpeg"): NoteFrais {
        val brut = app.llm.gemini.visionJson("Tu lis des justificatifs de dépenses avec précision. Réponds uniquement par l'objet JSON demandé, en français.", octets, mime, CONSIGNE_TICKET)
        val o = fr.dd.jarvis.ai.Vision.json(brut) ?: throw IllegalStateException("ticket illisible")
        fun t(k: String) = o.optString(k).trim().takeIf { it.isNotBlank() && it != "null" && it != "?" }.orEmpty()
        val date = t("date").takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: LocalDate.now().toString()
        val paiement = when (t("paiement").lowercase()) { "cb", "carte" -> "cb_pro"; "especes" -> "especes"; "virement" -> "virement"; "prelevement" -> "prelevement"; else -> "" }
        return NoteFrais("", date, t("fournisseur"), t("description"), t("categorie").takeIf { it in CATEGORIES } ?: categorieDe(t("fournisseur") + " " + t("description")),
            nombre(t("ttc")) ?: 0.0, nombre(t("tva")) ?: 0.0, nombre(t("taux_tva")) ?: 20.0, paiement, source = "photo")
    }

    // ------------------------------------------------------------------ demandes (voix / chat)

    sealed class Intention {
        data class Ajouter(val nf: NoteFrais) : Intention()
        data class Lister(val statut: String?, val mois: String?) : Intention()
    }

    private fun esp(m: String) = m.replace(" ?", "\\s*").replace(" ", "\\s+")
    private val R_AJOUT = Regex(esp("^\\s*(?:(?:jarvis|ok|stp) )*(?:(?:ajoute|enregistre|note|mets|cree|saisis)(?: moi)? (?:une |la |un )?(?:nouvelle )?(?:note de frais|frais|depense)|(?:nouvelle )?note de frais) (.+)$"))
    /** Montant lu dans le texte d'origine (« 45,50 € », « 12 euros »). */
    private val MONTANT_O = Regex("(\\d{1,6}(?:[.,]\\d{1,2})?)\\s*(?:€|eur\\b|euros?\\b)", RegexOption.IGNORE_CASE)
    private val R_LISTE = Regex(esp("^\\s*(?:(?:jarvis|dis moi|montre moi|affiche|liste|donne moi|quelles sont|quel est le total de|total des|combien (?:j ai|de)) )*(?:mes |les )?(?:notes? de frais|frais|depenses)(?: (a traiter|non traitees|en attente|traitees|remboursees))?(?: (?:du mois|de ce mois|ce mois ci|du mois dernier|de|d|en|pour) ?(.*?))?\\s*$"))

    fun intention(texte: String, aujourdhui: LocalDate = LocalDate.now()): Intention? {
        val o = texte.trim()
        if (o.isBlank() || o.length > 240) return null
        val p = CommandesDirectes.plat(o)
        fun brut(m: MatchResult, i: Int) = m.groups[i]?.range?.let { o.substring(it.first, it.last + 1).trim() }.orEmpty()
        R_AJOUT.matchEntire(p)?.let { m ->
            val corps = brut(m, 1)
            val mt = MONTANT_O.find(corps) ?: return null
            val ttc = mt.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val reste = (corps.substring(0, mt.range.first) + " " + corps.substring(mt.range.last + 1)).replace(Regex("^\\s*(?:de|d'|d’|:)\\s*", RegexOption.IGNORE_CASE), "").trim()
            val n = DatesFr.normaliser(reste)
            val date = DatesFr.jour(n, aujourdhui, futur = false)?.date ?: aujourdhui
            val fournisseur = Regex("(?i)\\b(?:chez|à|au|a) ([A-Za-zÀ-ÿ0-9'&. -]{2,40}?)(?= pour | de | du | le | hier| aujourd| avant|,|$)").find(reste)?.groupValues?.get(1)?.trim().orEmpty()
            val objet = Regex("(?i)\\bpour (?:du |de la |des |de l'|le |la |les |un |une )?(.{2,60}?)(?= chez | à | au | le | hier|,|$)").find(reste)?.groupValues?.get(1)?.trim().orEmpty()
            val perso = Regex("\\b(carte perso|cb perso|ma carte|de ma poche|perso)\\b").containsMatchIn(normaliser(reste))
            return Intention.Ajouter(NoteFrais("", date.toString(), fournisseur.replaceFirstChar { it.uppercase() }, objet, categorieDe("$fournisseur $objet $reste"), ttc,
                paiement = if (perso) "cb_perso" else ""))
        }
        R_LISTE.matchEntire(p)?.let { m ->
            if (!Regex("note|frais|depense").containsMatchIn(p)) return null
            val statut = when { m.groupValues[1].startsWith("rembours") -> "rembourse"; m.groupValues[1].startsWith("traitee") -> "traite"; m.groupValues[1].isNotBlank() -> "a_traiter"; else -> null }
            val per = m.groupValues[2].trim()
            val mois = when {
                Regex("mois dernier").containsMatchIn(p) -> aujourdhui.minusMonths(1).toString().take(7)
                Regex("du mois|de ce mois|ce mois ci").containsMatchIn(p) -> aujourdhui.toString().take(7)
                per.isNotBlank() -> Regex("(janvier|fevrier|mars|avril|mai|juin|juillet|aout|septembre|octobre|novembre|decembre)(?: (\\d{4}))?").find(per)?.let { mm ->
                    val idx = listOf("janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre", "decembre").indexOf(mm.groupValues[1]) + 1
                    val an = mm.groupValues[2].toIntOrNull() ?: if (idx > aujourdhui.monthValue) aujourdhui.year - 1 else aujourdhui.year
                    "%04d-%02d".format(an, idx)
                } ?: return null
                else -> null
            }
            return Intention.Lister(statut, mois)
        }
        return null
    }

    fun executer(app: JarvisApp, i: Intention): String = when (i) {
        is Intention.Ajouter -> {
            val nf = ajouter(app, i.nf)
            "🧾 Note de frais enregistrée : ${ligne(nf)}\nAjoute le justificatif dans Société › Frais (📷 photo du ticket)." +
                (if (!app.societe.data.societe.franchiseTva && nf.tva == 0.0) "\nTVA non renseignée : précise-la (ou photographie le ticket) pour la récupérer." else "")
        }
        is Intention.Lister -> texte(app, i.statut, i.mois)
    }

    /** Outil « note_frais » (IA, Hermès). */
    fun outil(app: JarvisApp, a: JSONObject): String {
        val id = a.optString("id").trim()
        return when (a.optString("action").trim().lowercase()) {
            "ajouter" -> {
                val ttc = a.optString("ttc").replace(',', '.').toDoubleOrNull() ?: return "Indique le montant TTC (ttc)."
                val date = a.optString("date").takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: LocalDate.now().toString()
                executer(app, Intention.Ajouter(NoteFrais("", date, a.optString("fournisseur").trim(), a.optString("description").trim(), a.optString("categorie").trim(), ttc,
                    a.optString("tva").replace(',', '.').toDoubleOrNull() ?: 0.0, a.optString("taux_tva").replace(',', '.').toDoubleOrNull() ?: 20.0, a.optString("paiement").trim())))
            }
            "traiter", "traite" -> if (marquer(app, id, "traite")) "✅ Note de frais marquée traitée." else "Note de frais « $id » introuvable (action lister)."
            "rembourser", "rembourse" -> if (marquer(app, id, "rembourse")) "💶 Note de frais marquée remboursée." else "Note de frais « $id » introuvable."
            "supprimer" -> if (supprimer(app, id)) "🗑️ Note de frais supprimée (et son justificatif)." else "Note de frais « $id » introuvable."
            else -> {
                val statut = a.optString("statut").trim().takeIf { it in STATUTS }
                val mois = a.optString("mois").trim().takeIf { Regex("\\d{4}-\\d{2}").matches(it) }
                val l = liste(app, statut).filter { mois == null || it.date.startsWith(mois) }
                if (l.isEmpty()) texte(app, statut, mois) else texte(app, statut, mois) + "\n\nIdentifiants : " + l.take(25).joinToString(", ") { "${it.id} (${it.fournisseur.ifBlank { it.categorie }} ${euros(it.ttc)})" }
            }
        }
    }
}
