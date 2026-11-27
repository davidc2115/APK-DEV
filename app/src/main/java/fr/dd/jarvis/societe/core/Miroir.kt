package fr.dd.jarvis.societe.core

import java.util.Locale

/**
 * Plan de la copie lisible des données de la société sur le disque de la Freebox (dossier « JARVIS Société ») :
 * quels fichiers doivent s'y trouver et une empreinte par fichier (un fichier n'est renvoyé que si son empreinte change).
 * Pur Kotlin (testable) : la production des PDF et l'envoi sont faits par societe/MiroirFreebox.
 */
object Miroir {
    /** Changer ce numéro force la régénération de tous les PDF (après une évolution de leur mise en page). */
    private const val VERSION_RENDU = "1"

    sealed class Source {
        data class Texte(val contenu: String) : Source()
        data class PdfDoc(val numero: String) : Source()
        data class PdfBulletin(val id: String) : Source()
        /** Fichier du téléphone : chemin relatif au dossier privé de l'appli (« societe/interventions/… », « societe_logo.png »). */
        data class Fichier(val rel: String) : Source()
        data class FacturX(val numero: String) : Source()
        data class FacturXml(val numero: String) : Source()
    }

    data class Entree(val chemin: String, val empreinte: String, val source: Source)

    /** Nom utilisable sur un disque partagé (Windows, macOS) : sans \ / : * ? " < > |, ni point final. */
    fun nom(t: String): String = t.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").replace(Regex("\\s+"), " ").trim()
        .trimEnd('.', ' ').take(120).ifBlank { "sans nom" }

    fun sha(t: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(t.toByteArray()).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ tableaux CSV (Excel / LibreOffice en français)

    private fun montant(v: Double) = String.format(Locale.FRANCE, "%.2f", v)
    private fun nombre(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.FRANCE, "%.2f", v)

    /** CSV « ; » avec BOM UTF-8 (accents corrects à l'ouverture dans Excel) et fins de ligne Windows. */
    fun csv(lignes: List<List<Any?>>): String = "﻿" + lignes.joinToString("\r\n") { l ->
        l.joinToString(";") { v ->
            val t = when (v) { null -> ""; is Double -> montant(v); else -> v.toString() }
            if (t.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
        }
    } + "\r\n"

    private val STATUTS = mapOf("brouillon" to "Brouillon", "envoye" to "Envoyé", "accepte" to "Accepté", "refuse" to "Refusé", "facture" to "Facturé",
        "a_payer" to "À payer", "payee" to "Payée", "annulee" to "Annulée")

    fun tableClients(d: SocieteData): String = csv(listOf(listOf("Nom", "Type", "Contact", "Téléphone", "E-mail", "Adresse", "SIREN", "N° TVA", "Client depuis", "Installations", "CA facturé HT")) +
        d.clients.sortedBy { it.nom.lowercase() }.map { c ->
            listOf(c.nom, if (c.type == "professionnel") "Professionnel" else "Particulier", c.contact, c.tel, c.email, c.adresse, c.siren, c.tvaNum, c.depuis,
                c.sites.size.toString(), d.caClient(c))
        })

    private fun docs(d: SocieteData, type: String) = d.docs.filter { it.type == type }.sortedWith(compareBy({ it.date }, { it.numero }))

    fun tableFactures(d: SocieteData): String = csv(listOf(listOf("Numéro", "Date", "Échéance", "Client", "Statut", "Total HT", "TVA", "Total TTC", "Payée le", "Mode de paiement", "Devis d'origine", "Prestation")) +
        docs(d, "facture").map { f ->
            val t = d.totaux(f)
            listOf(f.numero, f.date, f.echeance, d.client(f.clientId)?.nom.orEmpty(), STATUTS[f.statut] ?: f.statut, t.ht, t.tva, t.ttc, f.payeLe, f.modePaiement, f.devisRef, f.datePrestation)
        })

    fun tableDevis(d: SocieteData): String = csv(listOf(listOf("Numéro", "Date", "Valable jusqu'au", "Client", "Statut", "Total HT", "TVA", "Total TTC")) +
        docs(d, "devis").map { f ->
            val t = d.totaux(f)
            listOf(f.numero, f.date, f.echeance, d.client(f.clientId)?.nom.orEmpty(), STATUTS[f.statut] ?: f.statut, t.ht, t.tva, t.ttc)
        })

    fun tableBulletins(d: SocieteData): String = csv(listOf(listOf("Mois", "Salarié", "Heures", "Brut", "Net imposable", "Net à payer", "Coût employeur")) +
        d.bulletins.sortedWith(compareBy({ it.mois }, { it.nomEmploye })).map { b ->
            listOf(b.mois, b.nomEmploye.ifBlank { d.employes.firstOrNull { it.id == b.employeId }?.nom.orEmpty() }, nombre(b.heures), b.brut, b.netImposable, b.netAPayer, b.coutEmployeur)
        })

    fun tableInterventions(d: SocieteData): String = csv(listOf(listOf("Date", "Client", "Installation", "Catégorie", "Description", "Actions", "Statut", "Prochaine maintenance")) +
        d.clients.flatMap { c -> c.sites.flatMap { s -> s.interventions.map { i -> Triple(c, s, i) } } }.sortedBy { it.third.date }.map { (c, s, i) ->
            listOf(i.date, c.nom, s.nom.ifBlank { s.adresse }, i.categorie, i.description, i.actions, i.statut, i.prochaineEffective())
        })

    // ------------------------------------------------------------------ plan complet

    /** Dossier lisible d'une intervention : « 2026-11-03 Dupont - Toiture (a1b2c3d4) ». */
    fun dossiersInterventions(d: SocieteData): Map<String, String> = d.clients.flatMap { c ->
        c.sites.flatMap { s -> s.interventions.map { i -> i.id to nom("${i.date} ${c.nom}" + (s.nom.takeIf { it.isNotBlank() }?.let { " - $it" } ?: "") + " (${i.id})") } }
    }.toMap()

    /** Emplacement dans la copie d'un fichier du dossier Société du téléphone (null = non copié). */
    fun cheminFichier(rel: String, interventions: Map<String, String>): String? {
        val r = rel.removePrefix("societe/")
        if (rel == "societe_logo.png") return "Fichiers/Logo.png"
        if (r.startsWith("paie/") || r.startsWith("coffre/") || r.matches(Regex("^data\\.json(\\..*)?$")) || r.startsWith("data.corrompu.") ||
            r.endsWith(".tmp") || r.split('/').any { it.startsWith(".") }) return null
        if (r.startsWith("interventions/")) {
            val morceaux = r.removePrefix("interventions/").split('/')
            if (morceaux.size < 2) return null
            return "Interventions/" + (interventions[morceaux[0]] ?: nom(morceaux[0])) + "/" + morceaux.drop(1).joinToString("/") { nom(it) }
        }
        return "Fichiers/" + r.split('/').joinToString("/") { nom(it) }
    }

    /**
     * Tous les fichiers de la copie. [fichiers] : fichiers du téléphone (chemin relatif → « taille:date »).
     * [identite] : ce qui change l'aspect de TOUS les PDF (logo, identité légale) ; [facturX] : factures électroniques.
     */
    fun plan(d: SocieteData, fichiers: Map<String, String>, identite: String, lisezMoi: String, base: String, facturX: Boolean = false): List<Entree> {
        val l = mutableListOf<Entree>()
        fun texte(chemin: String, t: String) { l += Entree(chemin, sha(t), Source.Texte(t)) }
        texte("LISEZ-MOI.txt", lisezMoi)
        texte("Base/societe.json", base)
        texte("Tableaux/Clients.csv", tableClients(d))
        texte("Tableaux/Factures.csv", tableFactures(d))
        texte("Tableaux/Devis.csv", tableDevis(d))
        if (d.bulletins.isNotEmpty()) texte("Tableaux/Bulletins de paie.csv", tableBulletins(d))
        if (d.clients.any { c -> c.sites.any { it.interventions.isNotEmpty() } }) texte("Tableaux/Interventions.csv", tableInterventions(d))

        val socJson = jsonSociete.encodeToString(Societe.serializer(), d.societe)
        val vus = HashSet<String>()
        for (doc in d.docs.sortedBy { it.numero }) {
            val client = d.client(doc.clientId)
            val dossier = (if (doc.type == "devis") "Devis" else "Factures") + "/" + doc.date.take(4).ifBlank { "Sans date" }
            var chemin = "$dossier/" + nom("${doc.numero} ${client?.nom.orEmpty()}".trim()) + ".pdf"
            if (!vus.add(chemin.lowercase())) chemin = chemin.removeSuffix(".pdf") + " (${vus.size}).pdf"
            val empreinte = sha(VERSION_RENDU + "|" + identite + "|" + socJson + "|" + jsonSociete.encodeToString(Doc.serializer(), doc) + "|" +
                (client?.let { jsonSociete.encodeToString(Client.serializer(), it) } ?: "") + "|" + d.modele(doc.type) + "|" + d.regles.hashCode())
            l += Entree(chemin, empreinte, Source.PdfDoc(doc.numero))
            if (facturX && doc.type == "facture" && doc.statut != "brouillon") {
                val fx = "Factures électroniques/" + doc.date.take(4).ifBlank { "Sans date" } + "/" + nom(doc.numero)
                l += Entree("$fx.pdf", sha("fx|" + empreinte), Source.FacturX(doc.numero))
                l += Entree("$fx.xml", sha("xml|" + empreinte), Source.FacturXml(doc.numero))
            }
        }
        for (b in d.bulletins) {
            val n = nom("${b.mois} ${b.nomEmploye.ifBlank { d.employes.firstOrNull { it.id == b.employeId }?.nom ?: "Salarié" }}")
            l += Entree("Paie/${b.mois.take(4)}/$n.pdf", sha(VERSION_RENDU + "|" + identite + "|" + b.id + "|" + b.md.hashCode() + "|" + b.creeLe), Source.PdfBulletin(b.id))
        }
        val inter = dossiersInterventions(d)
        for ((rel, empreinte) in fichiers.toSortedMap()) {
            val chemin = cheminFichier(rel, inter) ?: continue
            if (vus.add(chemin.lowercase())) l += Entree(chemin, sha("f|$empreinte"), Source.Fichier(rel))
        }
        return l
    }
}
