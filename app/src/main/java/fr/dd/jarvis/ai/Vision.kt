package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.Client
import fr.dd.jarvis.societe.core.Site
import org.json.JSONObject
import java.time.LocalDate

/**
 * Mode caméra « Regarder » : Jarvis lit une photo prise sur le terrain.
 *  - ÉQUIPEMENT : plaque signalétique (onduleur, panneau, compteur, batterie…) → fiche, enregistrable sur l'installation ;
 *  - CODE : code erreur affiché → appareil + code, puis explication et procédure demandées à Jarvis ;
 *  - DOCUMENT : facture, contrat, courrier → transcription, classement dans Obsidian, achats de la société ;
 *  - QUESTION : question libre sur la photo.
 */
object Vision {
    enum class Mode(val icone: String, val libelle: String) {
        EQUIPEMENT("🔌", "Équipement"), CODE("⚠️", "Code erreur"), DOCUMENT("📄", "Document"), QUESTION("❓", "Question")
    }

    private const val SYSTEME = "Tu es l'œil de JARVIS, assistant d'un électricien spécialisé en maintenance photovoltaïque. " +
        "Tu lis la photo avec précision : ne devine jamais un caractère illisible (mets « ? »), recopie les références exactement. " +
        "Réponds uniquement par l'objet JSON demandé, en français."

    private fun consigne(mode: Mode, question: String): String = when (mode) {
        Mode.EQUIPEMENT -> """Lis la plaque signalétique / l'étiquette de l'équipement. JSON :
{"type":"onduleur|micro-onduleur|panneau|batterie|compteur|coffret|optimiseur|autre","marque":"","modele":"","numero_serie":"",
"puissance":"(avec unité)","caracteristiques":["tension DC max …","courant …","date de fabrication …", "…"],"resume":"une phrase"}"""
        Mode.CODE -> """Lis le code ou message d'erreur affiché par l'appareil (écran, LED, application). JSON :
{"appareil":"onduleur|compteur|batterie|application|autre","marque":"","modele":"","code":"","message":"texte affiché exact","voyants":"état des voyants si visibles","resume":"une phrase"}"""
        Mode.DOCUMENT -> """Lis le document. JSON :
{"type":"facture_fournisseur|facture_client|devis|contrat|courrier|releve|attestation|autre","emetteur":"","destinataire":"","date":"yyyy-MM-dd ou vide",
"numero":"","objet":"court","montant_ht":"","tva":"","montant_ttc":"","echeance":"yyyy-MM-dd ou vide","resume":"2 phrases","texte":"transcription fidèle du texte utile"}
Une facture reçue d'un fournisseur (matériel, carburant, outillage, abonnement…) est « facture_fournisseur »."""
        Mode.QUESTION -> """Question de l'utilisateur : « ${question.ifBlank { "Qu'est-ce que c'est ?" }} ». Réponds précisément d'après la photo. JSON : {"reponse":"…"}"""
    }

    /** Analyse de la photo (JPEG) : objet JSON du mode demandé. */
    suspend fun analyser(app: JarvisApp, mode: Mode, jpeg: ByteArray, question: String = ""): JSONObject {
        val brut = app.llm.gemini.visionJson(SYSTEME, jpeg, "image/jpeg", consigne(mode, question))
        return json(brut) ?: throw IllegalStateException("réponse illisible")
    }

    internal fun json(brut: String): JSONObject? {
        val debut = brut.indexOf('{'); val fin = brut.lastIndexOf('}')
        if (debut < 0 || fin <= debut) return null
        return runCatching { JSONObject(brut.substring(debut, fin + 1)) }.getOrNull()
    }

    private fun JSONObject.t(k: String) = optString(k).trim().takeIf { it.isNotBlank() && it != "null" && it != "?" }.orEmpty()

    /** Texte court d'une fiche équipement (pour l'installation, l'affichage et Jarvis). */
    fun texteEquipement(o: JSONObject): String {
        val car = o.optJSONArray("caracteristiques")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { s -> s.isNotBlank() } } }.orEmpty()
        return listOf(o.t("type").replaceFirstChar { it.uppercase() }, listOf(o.t("marque"), o.t("modele")).filter { it.isNotBlank() }.joinToString(" "),
            o.t("numero_serie").takeIf { it.isNotBlank() }?.let { "N° série $it" }, o.t("puissance").takeIf { it.isNotBlank() })
            .filter { !it.isNullOrBlank() }.joinToString(" · ") + if (car.isNotEmpty()) "\n" + car.joinToString(" · ") else ""
    }

    fun texteCode(o: JSONObject): String =
        listOf(o.t("appareil"), listOf(o.t("marque"), o.t("modele")).filter { it.isNotBlank() }.joinToString(" "),
            o.t("code").takeIf { it.isNotBlank() }?.let { "code $it" }, o.t("message").takeIf { it.isNotBlank() }?.let { "« $it »" },
            o.t("voyants").takeIf { it.isNotBlank() }?.let { "voyants : $it" }).filter { !it.isNullOrBlank() }.joinToString(" · ")

    fun texteDocument(o: JSONObject): String =
        listOf(o.t("type").replace('_', ' ').replaceFirstChar { it.uppercase() }, o.t("emetteur"), o.t("date"), o.t("numero").takeIf { it.isNotBlank() }?.let { "n° $it" },
            o.t("montant_ttc").takeIf { it.isNotBlank() }?.let { "$it TTC" }).filter { !it.isNullOrBlank() }.joinToString(" · ") +
            (o.t("objet").takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")

    // ------------------------------------------------------------------ installations proches

    data class SiteProche(val client: Client, val site: Site, val km: Double?)

    /** Installations des clients, les plus proches de la position actuelle d'abord (puis par nom). */
    fun sitesProches(app: JarvisApp): List<SiteProche> {
        val ici = fr.dd.jarvis.device.Locator.point(app)
        return app.societe.data.clients.flatMap { c -> c.sites.map { s ->
            val km = if (ici != null && s.lat != null && s.lon != null) fr.dd.jarvis.societe.Geo.haversine(ici, fr.dd.jarvis.societe.Geo.Point(s.lat, s.lon)) else null
            SiteProche(c, s, km)
        } }.sortedWith(compareBy<SiteProche> { it.km ?: Double.MAX_VALUE }.thenBy { it.client.nom.lowercase() })
    }

    private fun sur(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80)

    /** Enregistre la plaque relevée sur l'installation (onduleur renseigné, note datée) + la photo dans le coffre. */
    fun enregistrerEquipement(app: JarvisApp, clientId: String, siteId: String, o: JSONObject, photo: ByteArray): String {
        val svc = app.societe
        val client = svc.data.client(clientId) ?: return "❌ Client introuvable."
        val site = client.sites.firstOrNull { it.id == siteId } ?: return "❌ Installation introuvable."
        val date = LocalDate.now().toString()
        val fiche = texteEquipement(o).replace("\n", " · ")
        val estOnduleur = o.t("type").contains("onduleur", true)
        val onduleur = listOf(o.t("marque"), o.t("modele")).filter { it.isNotBlank() }.joinToString(" ") +
            (o.t("numero_serie").takeIf { it.isNotBlank() }?.let { " (SN $it)" } ?: "")
        svc.repo.update { d -> d.copy(clients = d.clients.map { c -> if (c.id != clientId) c else c.copy(sites = c.sites.map { s ->
            if (s.id != siteId) s else s.copy(
                onduleur = if (estOnduleur && onduleur.isNotBlank()) onduleur else s.onduleur,
                notes = (s.notes.trimEnd() + "\n[$date] Plaque relevée : $fiche").trim())
        }) }) }
        val dossier = "Société/Installations/${sur(client.nom)}/${sur(site.nom.ifBlank { "Installation" })}"
        val image = runCatching { app.obsidian.saveBytes(dossier, "plaque-${sur(o.t("type").ifBlank { "equipement" })}-$date.jpg", photo, "image/jpeg") }.getOrNull()
        runCatching { svc.repo.data.client(clientId)?.let { svc.exporterClient(it) } }
        return "✅ Enregistré sur ${client.nom} — ${site.nom.ifBlank { "installation" }} : $fiche" +
            (if (estOnduleur && onduleur.isNotBlank()) "\n🔌 Onduleur de l'installation : $onduleur" else "") +
            (if (image != null) "\n📷 Photo rangée dans le coffre ($dossier)." else "")
    }

    /**
     * Classe un document scanné : note Obsidian (champs, résumé, transcription, image) dans « Documents scannés/<type> »,
     * et, pour une facture fournisseur, une ligne dans « Société/Comptabilité/Achats <année> ».
     */
    fun classerDocument(app: JarvisApp, o: JSONObject, photo: ByteArray): String {
        val type = o.t("type").ifBlank { "autre" }
        val date = o.t("date").takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: LocalDate.now().toString()
        val dossierType = when (type) {
            "facture_fournisseur" -> "Factures fournisseurs"; "facture_client" -> "Factures"; "devis" -> "Devis"; "contrat" -> "Contrats"
            "courrier" -> "Courriers"; "releve" -> "Relevés"; "attestation" -> "Attestations"; else -> "Divers"
        }
        val dossier = "Documents scannés/$dossierType"
        val base = sur("$date - ${o.t("emetteur").ifBlank { "Document" }}" + (o.t("objet").takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""))
        val image = "$base.jpg"
        app.obsidian.saveBytes(dossier, image, photo, "image/jpeg")
        val md = buildString {
            append("# ${o.t("objet").ifBlank { dossierType.removeSuffix("s") }}\n\n")
            append("- Type : ${type.replace('_', ' ')}\n- Émetteur : ${o.t("emetteur")}\n")
            if (o.t("destinataire").isNotBlank()) append("- Destinataire : ${o.t("destinataire")}\n")
            append("- Date : $date\n")
            if (o.t("numero").isNotBlank()) append("- Numéro : ${o.t("numero")}\n")
            if (o.t("montant_ht").isNotBlank()) append("- Montant HT : ${o.t("montant_ht")}\n")
            if (o.t("tva").isNotBlank()) append("- TVA : ${o.t("tva")}\n")
            if (o.t("montant_ttc").isNotBlank()) append("- Montant TTC : ${o.t("montant_ttc")}\n")
            if (o.t("echeance").isNotBlank()) append("- Échéance : ${o.t("echeance")}\n")
            append("\n## Résumé\n${o.t("resume")}\n\n![[$image]]\n")
            if (o.t("texte").isNotBlank()) append("\n## Transcription\n${o.t("texte")}\n")
        }
        app.obsidian.saveIn(dossier, base, md)
        var compta = ""
        if (type == "facture_fournisseur") {
            val annee = date.take(4)
            val chemin = "Société/Comptabilité/Achats $annee"
            val existant = runCatching { app.obsidian.readNote(chemin) }.getOrNull()
            val ligne = "| $date | ${o.t("emetteur").replace("|", "/")} | ${o.t("objet").replace("|", "/")} | ${o.t("montant_ht")} | ${o.t("tva")} | ${o.t("montant_ttc")} | [[$base]] |\n"
            val contenu = if (existant.isNullOrBlank()) "# Achats $annee\n\n| Date | Fournisseur | Objet | HT | TVA | TTC | Pièce |\n|---|---|---|---|---|---|---|\n$ligne" else ligne
            runCatching { app.obsidian.writeNote(chemin, contenu, append = !existant.isNullOrBlank()) }
                .onSuccess { compta = "\n🧾 Ajouté aux achats $annee (Société › Comptabilité)." }
        }
        return "✅ Classé dans le coffre : $dossier/$base$compta"
    }
}
