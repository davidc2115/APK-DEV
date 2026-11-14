package fr.dd.jarvis.societe

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Fichiers
import fr.dd.jarvis.societe.core.Doc
import fr.dd.jarvis.societe.core.FacturX
import fr.dd.jarvis.societe.core.SocieteData
import fr.dd.jarvis.societe.core.euro
import java.io.File

/**
 * Facturation électronique — DÉSACTIVÉE par défaut (Réglages › Société › Facturation électronique) tant que la société
 * n'est pas en activité. Activée : chaque facture émise existe aussi au format Factur-X (PDF + XML structuré attaché),
 * envoyée par mail à la place du PDF simple, copiée sur la Freebox (« Factures électroniques ») ; l'e-reporting des ventes
 * aux particuliers est préparé chaque mois. Désactivée : rien de tout cela n'est produit, et « Supprimer les fichiers »
 * efface ce qui a été généré (la copie Freebox suit au passage suivant). Tout ce module tient dans ce fichier et
 * societe/core/FacturX.kt : il peut être retiré sans toucher au reste de l'application.
 */
object FactureElectronique {
    fun active(app: JarvisApp) = app.store.factureElectronique.value

    /** Fichiers générés sur le téléphone : dans les fichiers de Jarvis, préfixés « FacturX- » / « e-reporting- ». */
    private fun dossier(app: JarvisApp) = Fichiers.dossier(app)
    private fun generes(app: JarvisApp) = dossier(app).listFiles()?.filter { it.isFile && (it.name.startsWith("FacturX-") || it.name.startsWith("e-reporting-")) }.orEmpty()
    private fun lien(nom: String) = nom.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Facture concernée : une facture émise (pas un devis, pas un brouillon, pas annulée). */
    fun concernee(doc: Doc) = doc.type == "facture" && doc.statut != "brouillon" && doc.statut != "annulee"

    /** PDF Factur-X d'une facture (PDF habituel + factur-x.xml attaché). */
    fun pdfFacturX(app: JarvisApp, d: SocieteData, doc: Doc): ByteArray {
        val pdf = app.societe.pdfBytes(doc)
        val xml = FacturX.xml(d, doc).toByteArray(Charsets.UTF_8)
        val client = d.client(doc.clientId)?.nom.orEmpty()
        return FacturX.pdfAvecXml(pdf, xml, "Facture ${doc.numero}" + (if (client.isNotBlank()) " — $client" else ""), d.societe.nom.ifBlank { "JARVIS" })
    }

    /** Pièce jointe d'un envoi par mail : Factur-X si activé et possible, sinon le PDF habituel. (octets, libellé) */
    fun pieceJointe(app: JarvisApp, doc: Doc): Pair<ByteArray, String> {
        val d = app.societe.data
        if (active(app) && concernee(doc)) runCatching { return pdfFacturX(app, d, doc) to "Factur-X" }
        return app.societe.pdfBytes(doc) to "PDF"
    }

    fun etat(app: JarvisApp): String {
        val d = app.societe.data
        val factures = d.docs.filter { concernee(it) }
        val aCorriger = factures.map { it to FacturX.controles(d, it) }.filter { it.second.isNotEmpty() }
        return buildString {
            append("🧾 **Facturation électronique** : ").append(if (active(app)) "✅ activée" else "⏸️ désactivée (société en test)").append("\n")
            append("📅 Calendrier : réception obligatoire depuis le 1er septembre 2026 ; émission par une plateforme agréée à partir du 1er septembre 2027 (TPE / micro-entreprise).\n")
            append("🏢 Plateforme agréée : ").append(app.store.facturePlateforme.value.ifBlank { "non choisie" }).append("\n")
            append("📄 Factures émises : ${factures.size}")
            if (aCorriger.isNotEmpty()) append(" — ⚠️ ${aCorriger.size} à compléter (dis « contrôle la facture … »)")
            append("\n").append(controleSociete(d).let { if (it.isEmpty()) "✅ Fiche société complète pour la facture électronique." else "⚠️ Fiche société : " + it.joinToString(" ; ") })
            append("\nℹ️ JARVIS prépare les fichiers (Factur-X, e-reporting) ; leur conformité doit être validée par la plateforme agréée avant tout usage réel.")
        }
    }

    private fun controleSociete(d: SocieteData): List<String> =
        FacturX.controles(d, Doc("—", "facture", "", date = java.time.LocalDate.now().toString(), lignes = listOf(fr.dd.jarvis.societe.core.Ligne("x", 1.0, puHt = 1.0))))
            .filterNot { it.startsWith("Client") || it.contains("client") }

    fun controler(app: JarvisApp, numero: String): String {
        val d = app.societe.data
        val doc = d.doc(numero) ?: return "Document $numero introuvable."
        if (doc.type != "facture") return "${doc.numero} est un devis : la facture électronique concerne les factures."
        val l = FacturX.controles(d, doc)
        val t = d.totaux(doc)
        return "🧾 ${doc.numero} — ${d.client(doc.clientId)?.nom.orEmpty()} — ${euro(t.ttc)} TTC\n" +
            if (l.isEmpty()) "✅ Toutes les mentions nécessaires sont présentes." else l.joinToString("\n") { "⚠️ $it" }
    }

    /** Génère le Factur-X d'une facture dans les fichiers de Jarvis (lien cliquable). */
    fun generer(app: JarvisApp, numero: String): String {
        val d = app.societe.data
        val doc = d.doc(numero) ?: return "Document $numero introuvable."
        require(doc.type == "facture") { "${doc.numero} est un devis." }
        val dos = dossier(app)
        val nom = lien("FacturX-" + doc.numero)
        File(dos, "$nom.pdf").writeBytes(pdfFacturX(app, d, doc))
        File(dos, "$nom.xml").writeText(FacturX.xml(d, doc))
        val avert = FacturX.controles(d, doc)
        return "[📎 $nom.pdf](jarvis-file:$nom.pdf)\n[📎 $nom.xml](jarvis-file:$nom.xml)\n✅ Facture électronique ${doc.numero} générée (PDF lisible + XML structuré attaché à l'intérieur)." +
            (if (avert.isNotEmpty()) "\n" + avert.joinToString("\n") { "⚠️ $it" } else "")
    }

    /** CSV d'e-reporting du mois (« 2026-11 »). */
    fun eReporting(app: JarvisApp, mois: String): String {
        val m = mois.trim().ifBlank { java.time.YearMonth.now().minusMonths(1).toString() }
        require(Regex("\\d{4}-\\d{2}").matches(m)) { "mois attendu au format AAAA-MM." }
        val nom = "e-reporting-$m.csv"
        File(dossier(app), nom).writeText(FacturX.eReporting(app.societe.data, m))
        return "[📎 $nom](jarvis-file:$nom)\n✅ Données d'e-reporting de $m (ventes aux particuliers et encaissements)."
    }

    /** Efface tout ce qui a été généré (téléphone) ; la copie sur la Freebox suit au prochain passage. */
    fun supprimerFichiers(app: JarvisApp): String {
        val l = generes(app)
        val n = l.count { it.delete() }
        MiroirFreebox.signalerChangement(app, 5)
        return "🗑️ $n fichier(s) de facturation électronique supprimé(s) du téléphone" + (if (MiroirFreebox.actif(app) && !active(app)) " ; ceux de la Freebox seront retirés dans quelques secondes." else ".")
    }

    fun activer(app: JarvisApp, oui: Boolean): String {
        app.store.factureElectronique.set(oui)
        MiroirFreebox.signalerChangement(app, 5)
        return if (oui) "✅ Facturation électronique activée : les factures émises seront aussi produites au format Factur-X.\n" + etat(app)
        else "⏸️ Facturation électronique désactivée : plus aucun fichier Factur-X ni e-reporting n'est produit" +
            (if (MiroirFreebox.actif(app)) " et ceux de la Freebox seront retirés." else ".")
    }
}
