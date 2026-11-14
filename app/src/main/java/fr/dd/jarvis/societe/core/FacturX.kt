package fr.dd.jarvis.societe.core

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Facturation électronique (réforme française 2026-2027) : facture au format Factur-X (profil EN 16931) = le PDF lisible
 * + le fichier XML structuré « factur-x.xml » (norme CII) attaché à l'intérieur, et données d'e-reporting des ventes
 * aux particuliers. Pur Kotlin (testable hors Android).
 *
 * Rappel du calendrier (micro-entreprise / TPE) : réception obligatoire depuis le 1er septembre 2026, émission par une
 * plateforme agréée à partir du 1er septembre 2027. JARVIS PRÉPARE les fichiers ; la transmission officielle passe par la
 * plateforme agréée choisie, et la conformité doit être validée par elle (ou un validateur Factur-X) avant usage réel.
 */
object FacturX {
    const val NOM_XML = "factur-x.xml"

    // ------------------------------------------------------------------ outils de mise en forme

    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\n' || it == '\t' || it >= ' ' }
    private fun montant(v: Double) = String.format(Locale.US, "%.2f", round2(v))
    /** Prix et quantités : jusqu'à 4 décimales, au moins 2 pour les prix. */
    private fun decimal(v: Double, min: Int = 0): String {
        var t = String.format(Locale.US, "%.4f", v).trimEnd('0')
        val dec = t.substringAfter('.', "").length
        if (dec < min) t += "0".repeat(min - dec)
        return t.trimEnd('.')
    }
    private fun date102(iso: String): String? = runCatching { LocalDate.parse(iso.trim().take(10)).format(DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
    private fun chiffres(t: String) = t.filter { it.isDigit() }
    fun siren(siretOuSiren: String): String = chiffres(siretOuSiren).take(9).takeIf { it.length == 9 } ?: ""

    data class Adresse(val ligne: String, val codePostal: String, val ville: String, val pays: String = "FR")

    /** « 12 rue des Lilas, 21000 Dijon » → ligne, code postal, ville. */
    fun adresse(a: String): Adresse {
        val propre = a.replace('\n', ',').trim().trim(',')
        val m = Regex("(.*?)[,\\s]*\\b(\\d{5})\\s+([^,\\d][^,]*)\\s*(?:,\\s*(france))?\\s*$", RegexOption.IGNORE_CASE).find(propre)
            ?: return Adresse(propre, "", "")
        return Adresse(m.groupValues[1].trim().trim(','), m.groupValues[2], m.groupValues[3].trim())
    }

    private fun unite(u: String) = when (normaliser(u)) {
        "h", "heure", "heures" -> "HUR"; "kwc", "kw" -> "KWT"; "km", "kilometre", "kilometres" -> "KMT"; "jour", "jours", "j" -> "DAY"
        "m", "metre", "metres" -> "MTR"; "m2" -> "MTK"; else -> "C62"
    }

    // ------------------------------------------------------------------ contrôles des mentions obligatoires

    /** Ce qui manque pour une facture conforme (mentions légales + données exigées par la facture électronique). */
    fun controles(d: SocieteData, doc: Doc): List<String> {
        val s = d.societe
        val c = d.client(doc.clientId)
        val l = mutableListOf<String>()
        if (s.nom.isBlank()) l += "Nom de la société manquant (Réglages › Société)."
        if (adresse(s.adresse).codePostal.isBlank()) l += "Adresse du siège incomplète (rue, code postal et ville)."
        if (chiffres(s.siret).length != 14) l += "SIRET de la société manquant ou invalide (14 chiffres)."
        if (s.forme.isBlank()) l += "Forme juridique manquante (micro-entreprise, EURL, SASU…)."
        if (!s.franchiseTva && s.tvaNum.isBlank()) l += "N° de TVA intracommunautaire de la société manquant (obligatoire hors franchise en base)."
        if (s.franchiseTva && s.tvaNum.isBlank() && chiffres(s.siret).length != 14) l += "En franchise de TVA, le SIRET sert d'identifiant fiscal : renseigne-le."
        if (s.iban.isBlank()) l += "IBAN absent : conseillé pour le paiement par virement."
        if (c == null) l += "Client introuvable." else {
            if (c.nom.isBlank()) l += "Nom du client manquant."
            if (adresse(c.adresse).codePostal.isBlank()) l += "Adresse du client incomplète (code postal et ville)."
            if (c.type == "professionnel" && siren(c.siren).isBlank()) l += "SIREN du client professionnel manquant (nouvelle mention obligatoire)."
        }
        if (doc.lignes.isEmpty()) l += "Aucune ligne sur la facture."
        if (doc.lignes.any { it.designation.isBlank() }) l += "Une ligne n'a pas de désignation."
        if (doc.lignes.any { it.puHt < 0 || it.qte <= 0 }) l += "Quantité ou prix invalide sur une ligne (les avoirs ne sont pas encore gérés en Factur-X)."
        if (!s.franchiseTva && doc.lignes.any { it.tva <= 0.0 }) l += "Ligne à 0 % de TVA hors franchise : précise le motif d'exonération (non géré automatiquement)."
        if (date102(doc.date) == null) l += "Date de facture invalide."
        if (doc.echeance.isNotBlank() && date102(doc.echeance) == null) l += "Date d'échéance invalide."
        return l
    }

    // ------------------------------------------------------------------ XML CII (profil EN 16931)

    fun xml(d: SocieteData, doc: Doc): String {
        val s = d.societe
        val c = d.client(doc.clientId)
        val t = d.totaux(doc)
        val franchise = s.franchiseTva
        val sb = StringBuilder()
        fun el(nom: String, v: String?, attrs: String = "") { if (!v.isNullOrBlank()) sb.append("<$nom$attrs>").append(esc(v)).append("</$nom>") }
        fun dateEl(nom: String, iso: String) { date102(iso)?.let { sb.append("<$nom><udt:DateTimeString format=\"102\">$it</udt:DateTimeString></$nom>") } }
        fun categorie(taux: Double) = if (franchise || taux <= 0.0) "E" else "S"
        fun adresseEl(a: String) {
            val ad = adresse(a)
            sb.append("<ram:PostalTradeAddress>")
            el("ram:PostcodeCode", ad.codePostal); el("ram:LineOne", ad.ligne.ifBlank { null }); el("ram:CityName", ad.ville)
            sb.append("<ram:CountryID>${ad.pays}</ram:CountryID></ram:PostalTradeAddress>")
        }

        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<rsm:CrossIndustryInvoice xmlns:rsm=\"urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100\" ")
        sb.append("xmlns:qdt=\"urn:un:unece:uncefact:data:standard:QualifiedDataType:100\" ")
        sb.append("xmlns:ram=\"urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100\" ")
        sb.append("xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" xmlns:udt=\"urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100\">")
        // Contexte : cadre « S1 » = dépôt d'une facture de prestations de services ; profil EN 16931 (= « COMFORT »).
        sb.append("<rsm:ExchangedDocumentContext><ram:BusinessProcessSpecifiedDocumentContextParameter><ram:ID>S1</ram:ID></ram:BusinessProcessSpecifiedDocumentContextParameter>")
        sb.append("<ram:GuidelineSpecifiedDocumentContextParameter><ram:ID>urn:cen.eu:en16931:2017</ram:ID></ram:GuidelineSpecifiedDocumentContextParameter></rsm:ExchangedDocumentContext>")

        sb.append("<rsm:ExchangedDocument>"); el("ram:ID", doc.numero); sb.append("<ram:TypeCode>380</ram:TypeCode>")
        dateEl("ram:IssueDateTime", doc.date)
        fun note(texte: String, code: String) { if (texte.isNotBlank()) { sb.append("<ram:IncludedNote>"); el("ram:Content", texte); el("ram:SubjectCode", code); sb.append("</ram:IncludedNote>") } }
        note("Nature de l'opération : prestations de services.", "AAI")
        note(Rendu.identiteLegale(d), "REG")
        val pro = c?.type == "professionnel"
        note(if (pro) "En cas de retard de paiement : pénalités au taux de trois fois le taux d'intérêt légal, exigibles dès le lendemain de l'échéance (art. L441-10 du Code de commerce)."
            else "En cas de retard de paiement : pénalités au taux d'intérêt légal en vigueur.", "PMD")
        if (pro) note("Indemnité forfaitaire pour frais de recouvrement en cas de retard de paiement : 40 € (art. D441-5 du Code de commerce).", "PMT")
        note("Pas d'escompte pour paiement anticipé.", "AAB")
        if (doc.notes.isNotBlank()) note(doc.notes, "AAI")
        sb.append("</rsm:ExchangedDocument>")

        sb.append("<rsm:SupplyChainTradeTransaction>")
        doc.lignes.forEachIndexed { i, l ->
            val net = Pricing.totalLigne(l)
            val taux = if (franchise) 0.0 else l.tva
            sb.append("<ram:IncludedSupplyChainTradeLineItem>")
            sb.append("<ram:AssociatedDocumentLineDocument><ram:LineID>${i + 1}</ram:LineID></ram:AssociatedDocumentLineDocument>")
            sb.append("<ram:SpecifiedTradeProduct>"); el("ram:Name", l.designation.ifBlank { "Prestation" }); sb.append("</ram:SpecifiedTradeProduct>")
            sb.append("<ram:SpecifiedLineTradeAgreement>")
            val pu = l.puHt
            val remiseUnitaire = round4(pu * l.remisePct / 100.0)
            if (l.remisePct > 0.0) {
                sb.append("<ram:GrossPriceProductTradePrice><ram:ChargeAmount>${decimal(pu, 2)}</ram:ChargeAmount>")
                sb.append("<ram:AppliedTradeAllowanceCharge><ram:ChargeIndicator><udt:Indicator>false</udt:Indicator></ram:ChargeIndicator>")
                sb.append("<ram:ActualAmount>${decimal(remiseUnitaire, 2)}</ram:ActualAmount></ram:AppliedTradeAllowanceCharge></ram:GrossPriceProductTradePrice>")
            }
            sb.append("<ram:NetPriceProductTradePrice><ram:ChargeAmount>${decimal(pu - remiseUnitaire, 2)}</ram:ChargeAmount></ram:NetPriceProductTradePrice>")
            sb.append("</ram:SpecifiedLineTradeAgreement>")
            sb.append("<ram:SpecifiedLineTradeDelivery><ram:BilledQuantity unitCode=\"${unite(l.unite)}\">${decimal(l.qte)}</ram:BilledQuantity></ram:SpecifiedLineTradeDelivery>")
            sb.append("<ram:SpecifiedLineTradeSettlement><ram:ApplicableTradeTax><ram:TypeCode>VAT</ram:TypeCode>")
            sb.append("<ram:CategoryCode>${categorie(taux)}</ram:CategoryCode><ram:RateApplicablePercent>${decimal(taux)}</ram:RateApplicablePercent></ram:ApplicableTradeTax>")
            sb.append("<ram:SpecifiedTradeSettlementLineMonetarySummation><ram:LineTotalAmount>${montant(net)}</ram:LineTotalAmount></ram:SpecifiedTradeSettlementLineMonetarySummation>")
            sb.append("</ram:SpecifiedLineTradeSettlement></ram:IncludedSupplyChainTradeLineItem>")
        }

        sb.append("<ram:ApplicableHeaderTradeAgreement>")
        // Vendeur
        sb.append("<ram:SellerTradeParty>"); el("ram:Name", s.nom)
        siren(s.siret).takeIf { it.isNotBlank() }?.let { sb.append("<ram:SpecifiedLegalOrganization><ram:ID schemeID=\"0002\">$it</ram:ID>"); el("ram:TradingBusinessName", null); sb.append("</ram:SpecifiedLegalOrganization>") }
        adresseEl(s.adresse)
        if (s.email.isNotBlank()) { sb.append("<ram:URIUniversalCommunication>"); el("ram:URIID", s.email, " schemeID=\"EM\""); sb.append("</ram:URIUniversalCommunication>") }
        when {
            s.tvaNum.isNotBlank() -> { sb.append("<ram:SpecifiedTaxRegistration>"); el("ram:ID", s.tvaNum.replace(" ", ""), " schemeID=\"VA\""); sb.append("</ram:SpecifiedTaxRegistration>") }
            chiffres(s.siret).isNotBlank() -> { sb.append("<ram:SpecifiedTaxRegistration>"); el("ram:ID", chiffres(s.siret), " schemeID=\"FC\""); sb.append("</ram:SpecifiedTaxRegistration>") }
        }
        sb.append("</ram:SellerTradeParty>")
        // Acheteur
        sb.append("<ram:BuyerTradeParty>"); el("ram:Name", c?.nom ?: "Client")
        if (pro && c != null) siren(c.siren).takeIf { it.isNotBlank() }?.let { sb.append("<ram:SpecifiedLegalOrganization><ram:ID schemeID=\"0002\">$it</ram:ID></ram:SpecifiedLegalOrganization>") }
        adresseEl(c?.adresse.orEmpty())
        if (!c?.email.isNullOrBlank()) { sb.append("<ram:URIUniversalCommunication>"); el("ram:URIID", c!!.email, " schemeID=\"EM\""); sb.append("</ram:URIUniversalCommunication>") }
        if (!c?.tvaNum.isNullOrBlank()) { sb.append("<ram:SpecifiedTaxRegistration>"); el("ram:ID", c!!.tvaNum.replace(" ", ""), " schemeID=\"VA\""); sb.append("</ram:SpecifiedTaxRegistration>") }
        sb.append("</ram:BuyerTradeParty>")
        if (doc.devisRef.isNotBlank()) { sb.append("<ram:SellerOrderReferencedDocument>"); el("ram:IssuerAssignedID", doc.devisRef); sb.append("</ram:SellerOrderReferencedDocument>") }
        sb.append("</ram:ApplicableHeaderTradeAgreement>")

        sb.append("<ram:ApplicableHeaderTradeDelivery>")
        val livraison = doc.datePrestation.takeIf { date102(it) != null } ?: doc.date
        sb.append("<ram:ActualDeliverySupplyChainEvent>"); dateEl("ram:OccurrenceDateTime", livraison); sb.append("</ram:ActualDeliverySupplyChainEvent>")
        sb.append("</ram:ApplicableHeaderTradeDelivery>")

        sb.append("<ram:ApplicableHeaderTradeSettlement>")
        el("ram:PaymentReference", doc.numero)
        sb.append("<ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>")
        val iban = s.iban.replace(" ", "").uppercase()
        sb.append("<ram:SpecifiedTradeSettlementPaymentMeans><ram:TypeCode>${if (iban.isNotBlank()) "58" else "30"}</ram:TypeCode>")
        if (iban.isNotBlank()) {
            sb.append("<ram:PayeePartyCreditorFinancialAccount>"); el("ram:IBANID", iban); sb.append("</ram:PayeePartyCreditorFinancialAccount>")
            s.bic.replace(" ", "").uppercase().takeIf { it.isNotBlank() }?.let { sb.append("<ram:PayeeSpecifiedCreditorFinancialInstitution><ram:BICID>${esc(it)}</ram:BICID></ram:PayeeSpecifiedCreditorFinancialInstitution>") }
        }
        sb.append("</ram:SpecifiedTradeSettlementPaymentMeans>")
        for ((taux, base, tva) in t.tvaParTaux) {
            sb.append("<ram:ApplicableTradeTax><ram:CalculatedAmount>${montant(tva)}</ram:CalculatedAmount><ram:TypeCode>VAT</ram:TypeCode>")
            if (categorie(taux) == "E") el("ram:ExemptionReason", if (franchise) "TVA non applicable, art. 293 B du CGI" else "Exonération de TVA")
            sb.append("<ram:BasisAmount>${montant(base)}</ram:BasisAmount><ram:CategoryCode>${categorie(taux)}</ram:CategoryCode>")
            if (franchise) sb.append("<ram:ExemptionReasonCode>VATEX-FR-FRANCHISE</ram:ExemptionReasonCode>")
            sb.append("<ram:RateApplicablePercent>${decimal(taux)}</ram:RateApplicablePercent></ram:ApplicableTradeTax>")
        }
        sb.append("<ram:SpecifiedTradePaymentTerms>"); el("ram:Description", Rendu.conditions(d, doc)); dateEl("ram:DueDateDateTime", doc.echeance); sb.append("</ram:SpecifiedTradePaymentTerms>")
        sb.append("<ram:SpecifiedTradeSettlementHeaderMonetarySummation>")
        sb.append("<ram:LineTotalAmount>${montant(t.ht)}</ram:LineTotalAmount>")
        sb.append("<ram:TaxBasisTotalAmount>${montant(t.ht)}</ram:TaxBasisTotalAmount>")
        sb.append("<ram:TaxTotalAmount currencyID=\"EUR\">${montant(t.tva)}</ram:TaxTotalAmount>")
        sb.append("<ram:GrandTotalAmount>${montant(t.ttc)}</ram:GrandTotalAmount>")
        sb.append("<ram:TotalPrepaidAmount>0.00</ram:TotalPrepaidAmount>")
        sb.append("<ram:DuePayableAmount>${montant(t.ttc)}</ram:DuePayableAmount>")
        sb.append("</ram:SpecifiedTradeSettlementHeaderMonetarySummation>")
        sb.append("</ram:ApplicableHeaderTradeSettlement>")
        sb.append("</rsm:SupplyChainTradeTransaction></rsm:CrossIndustryInvoice>\n")
        return sb.toString()
    }

    private fun round4(x: Double) = Math.round(x * 10000.0) / 10000.0

    // ------------------------------------------------------------------ e-reporting (ventes aux particuliers)

    /**
     * Données d'e-reporting du mois [mois] (« 2026-11 ») : ventes aux particuliers (B2C) facturées dans le mois, par taux de
     * TVA, et encaissements reçus dans le mois (prestations de services : TVA exigible à l'encaissement). À transmettre par
     * la plateforme agréée à partir de son entrée en vigueur pour la société.
     */
    fun eReporting(d: SocieteData, mois: String): String {
        val factures = d.docs.filter { it.type == "facture" && it.statut != "annulee" && it.statut != "brouillon" && d.client(it.clientId)?.type != "professionnel" }
        val lignes = mutableListOf<List<Any?>>(listOf("Type", "Date", "Facture", "Client", "Nature", "Taux TVA (%)", "Base HT", "TVA", "Total TTC", "Mode de paiement"))
        factures.filter { it.date.startsWith(mois) }.sortedBy { it.date }.forEach { f ->
            d.totaux(f).tvaParTaux.forEach { (taux, base, tva) ->
                lignes += listOf("Transaction", f.date, f.numero, d.client(f.clientId)?.nom.orEmpty(), "Prestations de services", taux, base, tva, round2(base + tva), "")
            }
        }
        factures.filter { it.statut == "payee" && it.payeLe.startsWith(mois) }.sortedBy { it.payeLe }.forEach { f ->
            d.totaux(f).tvaParTaux.forEach { (taux, base, tva) ->
                lignes += listOf("Paiement", f.payeLe, f.numero, d.client(f.clientId)?.nom.orEmpty(), "Prestations de services", taux, base, tva, round2(base + tva), f.modePaiement)
            }
        }
        return Miroir.csv(lignes)
    }

    // ------------------------------------------------------------------ PDF Factur-X : XML attaché au PDF (mise à jour incrémentale)

    /**
     * Ajoute [xml] au [pdf] (PDF produit par le téléphone, table xref classique) : fichier attaché « factur-x.xml »
     * (/AF, /AFRelationship /Data), métadonnées XMP PDF/A-3 + extension Factur-X, profil de couleur sRGB (OutputIntent),
     * dictionnaire /Info cohérent et /ID. Le PDF d'origine reste intact (ajout à la fin du fichier).
     */
    fun pdfAvecXml(pdf: ByteArray, xml: ByteArray, titre: String, auteur: String, quand: ZonedDateTime = ZonedDateTime.now()): ByteArray {
        val txt = String(pdf, Charsets.ISO_8859_1)
        val sx = txt.lastIndexOf("startxref")
        require(sx > 0) { "PDF sans table de références (startxref)." }
        val prev = Regex("startxref\\s+(\\d+)").find(txt, sx)?.groupValues?.get(1)?.toLong() ?: error("startxref illisible")
        val ti = txt.lastIndexOf("trailer", sx)
        require(ti > 0) { "PDF à références compressées : Factur-X non pris en charge pour ce fichier." }
        val trailer = txt.substring(ti, sx)
        val taille = Regex("/Size\\s+(\\d+)").find(trailer)?.groupValues?.get(1)?.toInt() ?: error("/Size absent")
        val root = Regex("/Root\\s+(\\d+)\\s+(\\d+)\\s+R").find(trailer) ?: error("/Root absent")
        val rootNum = root.groupValues[1].toInt(); val rootGen = root.groupValues[2].toInt()
        val idExistant = Regex("/ID\\s*\\[\\s*<([0-9A-Fa-f]+)>").find(trailer)?.groupValues?.get(1)

        // Catalogue actuel (dernière définition de l'objet racine).
        val defs = Regex("(?<![0-9])$rootNum\\s+$rootGen\\s+obj\\b").findAll(txt).toList()
        val defCat = defs.lastOrNull() ?: error("catalogue introuvable")
        val fin = txt.indexOf("endobj", defCat.range.last)
        var cat = txt.substring(defCat.range.last + 1, fin).trim()
        require(cat.startsWith("<<") && cat.endsWith(">>")) { "catalogue illisible" }
        cat = cat.substring(2, cat.length - 2)
        require(!Regex("/Names\\s*<<").containsMatchIn(cat)) { "catalogue avec noms intégrés : non pris en charge" }
        for (cle in listOf("Metadata", "AF", "Names", "OutputIntents", "Version"))
            cat = cat.replace(Regex("/$cle\\s*(\\d+\\s+\\d+\\s+R|\\[[^\\]]*\\]|/[A-Za-z0-9.]+)"), " ")

        val out = java.io.ByteArrayOutputStream()
        out.write(pdf)
        if (!txt.endsWith("\n") && !txt.endsWith("\r")) out.write('\n'.code)
        val offsets = sortedMapOf<Int, Long>()
        fun objet(num: Int, dict: String, flux: ByteArray? = null, gen: Int = 0) {
            offsets[num] = out.size().toLong()
            out.write("$num $gen obj\n$dict\n".toByteArray(Charsets.ISO_8859_1))
            if (flux != null) { out.write("stream\n".toByteArray()); out.write(flux); out.write("\nendstream\n".toByteArray()) }
            out.write("endobj\n".toByteArray())
        }
        val dPdf = quand.format(DateTimeFormatter.ofPattern("'D:'yyyyMMddHHmmss")) + quand.offset.id.let { o -> if (o == "Z") "Z" else o.substring(0, 3) + "'" + o.substring(4, 6) + "'" }
        val dXmp = quand.withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val md5 = java.security.MessageDigest.getInstance("MD5")
        val sommeXml = md5.digest(xml).joinToString("") { "%02x".format(it) }
        val nouvelId = java.security.MessageDigest.getInstance("MD5").digest(pdf + xml + dPdf.toByteArray()).joinToString("") { "%02X".format(it) }

        val nEmb = taille; val nSpec = taille + 1; val nXmp = taille + 2; val nIcc = taille + 3; val nOi = taille + 4; val nInfo = taille + 5
        objet(nEmb, "<< /Type /EmbeddedFile /Subtype /text#2Fxml /Params << /ModDate ($dPdf) /Size ${xml.size} /CheckSum <$sommeXml> >> /Length ${xml.size} >>", xml)
        objet(nSpec, "<< /Type /Filespec /F ($NOM_XML) /UF ($NOM_XML) /Desc (Factur-X) /AFRelationship /Data /EF << /F $nEmb 0 R /UF $nEmb 0 R >> >>")
        val xmp = xmp(titre, auteur, dXmp).toByteArray(Charsets.UTF_8)
        objet(nXmp, "<< /Type /Metadata /Subtype /XML /Length ${xmp.size} >>", xmp)
        val icc = iccSrgb()
        objet(nIcc, "<< /N 3 /Length ${icc.size} >>", icc)
        objet(nOi, "<< /Type /OutputIntent /S /GTS_PDFA1 /OutputConditionIdentifier (sRGB IEC61966-2.1) /Info (sRGB IEC61966-2.1) /DestOutputProfile $nIcc 0 R >>")
        objet(nInfo, "<< /Title ${texteUtf16(titre)} /Author ${texteUtf16(auteur)} /Creator (JARVIS) /Producer (JARVIS Factur-X) /CreationDate ($dPdf) /ModDate ($dPdf) >>")
        objet(rootNum, "<<${cat.trimEnd()} /Version /1.7 /Metadata $nXmp 0 R /AF [$nSpec 0 R] /Names << /EmbeddedFiles << /Names [($NOM_XML) $nSpec 0 R] >> >> /OutputIntents [$nOi 0 R] >>", gen = rootGen)

        val xref = out.size().toLong()
        val sbx = StringBuilder("xref\n")
        // Sous-sections contiguës.
        val nums = offsets.keys.toList()
        var i = 0
        while (i < nums.size) {
            var j = i
            while (j + 1 < nums.size && nums[j + 1] == nums[j] + 1) j++
            sbx.append("${nums[i]} ${j - i + 1}\n")
            for (k in i..j) sbx.append(String.format(Locale.US, "%010d %05d n\r\n", offsets[nums[k]], if (nums[k] == rootNum) rootGen else 0))
            i = j + 1
        }
        sbx.append("trailer\n<< /Size ${taille + 6} /Root $rootNum $rootGen R /Info $nInfo 0 R /Prev $prev /ID [<${idExistant ?: nouvelId}> <$nouvelId>] >>\n")
        sbx.append("startxref\n$xref\n%%EOF\n")
        out.write(sbx.toString().toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    /** Chaîne PDF : littérale si ASCII simple, sinon UTF-16BE en hexadécimal. */
    private fun texteUtf16(t: String): String = if (t.all { it in ' '..'~' && it !in "()\\" }) "($t)"
        else "<FEFF" + t.toByteArray(Charsets.UTF_16BE).joinToString("") { "%02X".format(it) } + ">"

    private fun xmp(titre: String, auteur: String, date: String): String {
        fun p(nom: String, desc: String) = "<rdf:li rdf:parseType=\"Resource\"><pdfaProperty:name>$nom</pdfaProperty:name><pdfaProperty:valueType>Text</pdfaProperty:valueType>" +
            "<pdfaProperty:category>external</pdfaProperty:category><pdfaProperty:description>$desc</pdfaProperty:description></rdf:li>"
        return "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\"><pdfaid:part>3</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance></rdf:Description>" +
            "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:format>application/pdf</dc:format>" +
            "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(titre)}</rdf:li></rdf:Alt></dc:title>" +
            "<dc:creator><rdf:Seq><rdf:li>${esc(auteur)}</rdf:li></rdf:Seq></dc:creator></rdf:Description>" +
            "<rdf:Description rdf:about=\"\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\"><pdf:Producer>JARVIS Factur-X</pdf:Producer></rdf:Description>" +
            "<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"><xmp:CreatorTool>JARVIS</xmp:CreatorTool>" +
            "<xmp:CreateDate>$date</xmp:CreateDate><xmp:ModifyDate>$date</xmp:ModifyDate></rdf:Description>" +
            "<rdf:Description rdf:about=\"\" xmlns:pdfaExtension=\"http://www.aiim.org/pdfa/ns/extension/\" xmlns:pdfaSchema=\"http://www.aiim.org/pdfa/ns/schema#\" xmlns:pdfaProperty=\"http://www.aiim.org/pdfa/ns/property#\">" +
            "<pdfaExtension:schemas><rdf:Bag><rdf:li rdf:parseType=\"Resource\"><pdfaSchema:schema>Factur-X PDFA Extension Schema</pdfaSchema:schema>" +
            "<pdfaSchema:namespaceURI>urn:factur-x:pdfa:CrossIndustryDocument:invoice:1p0#</pdfaSchema:namespaceURI><pdfaSchema:prefix>fx</pdfaSchema:prefix>" +
            "<pdfaSchema:property><rdf:Seq>" +
            p("DocumentFileName", "The name of the embedded XML document") + p("DocumentType", "The type of the hybrid document in capital letters, e.g. INVOICE or ORDER") +
            p("Version", "The actual version of the standard applying to the embedded XML document") + p("ConformanceLevel", "The conformance level of the embedded XML document") +
            "</rdf:Seq></pdfaSchema:property></rdf:li></rdf:Bag></pdfaExtension:schemas></rdf:Description>" +
            "<rdf:Description rdf:about=\"\" xmlns:fx=\"urn:factur-x:pdfa:CrossIndustryDocument:invoice:1p0#\"><fx:DocumentType>INVOICE</fx:DocumentType>" +
            "<fx:DocumentFileName>$NOM_XML</fx:DocumentFileName><fx:Version>1.0</fx:Version><fx:ConformanceLevel>EN 16931</fx:ConformanceLevel></rdf:Description>" +
            "</rdf:RDF></x:xmpmeta>\n<?xpacket end=\"w\"?>"
    }

    /** Profil ICC sRGB minimal (v2, écran, primaires adaptées D50, gamma 2,2) pour l'OutputIntent PDF/A. */
    fun iccSrgb(): ByteArray {
        val tags = linkedMapOf<String, ByteArray>()
        fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        fun s15(v: Double) = be32(Math.round(v * 65536.0).toInt())
        fun xyz(x: Double, y: Double, z: Double) = "XYZ ".toByteArray() + ByteArray(4) + s15(x) + s15(y) + s15(z)
        val desc = "sRGB IEC61966-2.1".toByteArray(Charsets.US_ASCII) + 0
        tags["desc"] = "desc".toByteArray() + ByteArray(4) + be32(desc.size) + desc + ByteArray(4) + ByteArray(4) + ByteArray(2) + ByteArray(1) + ByteArray(67)
        tags["cprt"] = "text".toByteArray() + ByteArray(4) + "No copyright, use freely".toByteArray(Charsets.US_ASCII) + 0
        tags["wtpt"] = xyz(0.9642, 1.0, 0.8249)
        tags["rXYZ"] = xyz(0.4361, 0.2225, 0.0139)
        tags["gXYZ"] = xyz(0.3851, 0.7169, 0.0971)
        tags["bXYZ"] = xyz(0.1431, 0.0606, 0.7141)
        val courbe = "curv".toByteArray() + ByteArray(4) + be32(1) + byteArrayOf(0x02, 0x33.toByte()) + ByteArray(2)   // gamma 2,2 (u8Fixed8)
        tags["rTRC"] = courbe; tags["gTRC"] = courbe; tags["bTRC"] = courbe
        val table = 4 + tags.size * 12
        var pos = 128 + table
        val entrees = java.io.ByteArrayOutputStream(); val donnees = java.io.ByteArrayOutputStream()
        for ((sig, data) in tags) {
            entrees.write(sig.toByteArray()); entrees.write(be32(pos)); entrees.write(be32(data.size))
            donnees.write(data); var n = data.size
            while (n % 4 != 0) { donnees.write(0); n++ }
            pos += n
        }
        val total = pos
        val h = java.io.ByteArrayOutputStream()
        h.write(be32(total)); h.write(ByteArray(4)); h.write(be32(0x02100000)); h.write("mntr".toByteArray()); h.write("RGB ".toByteArray()); h.write("XYZ ".toByteArray())
        h.write(byteArrayOf(0x07, 0xEA.toByte(), 0, 1, 0, 1, 0, 0, 0, 0, 0, 0))   // 2026-01-01 00:00:00
        h.write("acsp".toByteArray()); h.write(ByteArray(4)); h.write(ByteArray(4)); h.write(ByteArray(4)); h.write(ByteArray(4)); h.write(ByteArray(8))
        h.write(be32(0)); h.write(s15(0.9642)); h.write(s15(1.0)); h.write(s15(0.8249)); h.write(ByteArray(4))
        while (h.size() < 128) h.write(0)
        return h.toByteArray() + be32(tags.size) + entrees.toByteArray() + donnees.toByteArray()
    }
}
