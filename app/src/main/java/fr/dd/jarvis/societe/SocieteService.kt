package fr.dd.jarvis.societe

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import fr.dd.jarvis.device.ObsidianWriter
import fr.dd.jarvis.societe.core.*
import java.io.File
import java.time.LocalDate
import java.util.UUID

class SocieteService(
    private val ctx: Context,
    val repo: SocieteRepo,
    private val obsidian: ObsidianWriter
) {
    val data get() = repo.data

    fun newId() = UUID.randomUUID().toString().take(8)

    /** Supprime une intervention précise et ses pièces jointes, après confirmation demandée par SocieteTools. */
    fun supprimerIntervention(clientId: String, siteId: String, interventionId: String): String {
        val client = data.client(clientId) ?: return "Client introuvable."
        val site = client.sites.firstOrNull { it.id == siteId } ?: return "Installation introuvable."
        val cible = site.interventions.firstOrNull { it.id == interventionId } ?: return "Intervention introuvable."
        try {
            repo.update { current -> current.copy(clients = current.clients.map { c ->
                if (c.id != clientId) c else c.copy(sites = c.sites.map { s ->
                    if (s.id != siteId) s else s.copy(interventions = s.interventions.filterNot { it.id == interventionId })
                })
            }) }
        } catch (e: Exception) {
            return "❌ Suppression non confirmée : la base Société n'a pas pu être enregistrée (${e.message ?: e.javaClass.simpleName}). La fiche est conservée ; aucun succès n'est annoncé."
        }
        val encorePresente = data.client(clientId)?.sites?.firstOrNull { it.id == siteId }?.interventions?.any { it.id == interventionId } == true
        if (encorePresente) return "Échec : l’intervention $interventionId est toujours présente dans l’historique."
        val relu = repo.data.client(clientId)?.sites?.firstOrNull { it.id == siteId }?.interventions?.none { it.id == interventionId } == true
        if (!relu) return "❌ La suppression de $interventionId n'a pas été vérifiée dans la base Société ; vérifie l'historique avant toute nouvelle action."
        // Retrouve les pièces par l'ID de l'intervention : le chemin peut différer du nom normalisé
        // (accents, caractères spéciaux, renommage ou déplacement du dossier dans Obsidian).
        val (fichiersSupprimes, fichiersRestants) = obsidian.supprimerPiecesIntervention(cible.date, cible.id)
        val localPhotos = File(ctx.filesDir, "societe/interventions/$interventionId")
        val localSupprime = !localPhotos.exists() || runCatching { localPhotos.deleteRecursively() }.getOrDefault(false)
        return buildString {
            append("🗑️ Intervention $interventionId supprimée de l’historique de ${client.nom} — ${site.nom}.")
            if (fichiersSupprimes.isNotEmpty()) append("\n\n📁 Pièces supprimées du coffre :\n" + fichiersSupprimes.joinToString("\n") { "- $it" })
            if (fichiersRestants.isNotEmpty()) append("\n\n⚠️ Fichiers toujours présents ou non accessibles en écriture :\n" + fichiersRestants.joinToString("\n") { "- $it" })
            if (fichiersSupprimes.isEmpty() && fichiersRestants.isEmpty()) append("\nAucun PDF, rapport ou photo portant l'ID de cette intervention n'a été retrouvé dans le coffre.")
            if (!localSupprime) append("\n⚠️ Le dossier privé des photos n'a pas pu être entièrement supprimé.")
            append("\nLa fiche a bien été retirée de la base Société.")
        }
    }

    /** Modifie une intervention existante et régénère sa note Obsidian ainsi que son PDF, en conservant ses photos. */
    fun modifierIntervention(clientId: String, siteId: String, interventionId: String, maj: Intervention): String {
        val client = data.client(clientId) ?: return "Client introuvable."
        val site = client.sites.firstOrNull { it.id == siteId } ?: return "Installation introuvable."
        val ancienne = site.interventions.firstOrNull { it.id == interventionId } ?: return "Intervention introuvable."
        if (runCatching { LocalDate.parse(maj.date) }.isFailure) return "Date invalide : utilise yyyy-MM-dd."
        if (maj.prochaineMaintenance.isNotBlank() && runCatching { LocalDate.parse(maj.prochaineMaintenance) }.isFailure)
            return "Date de prochaine maintenance invalide : utilise yyyy-MM-dd."
        if (maj.statut !in setOf("terminee", "a_suivre", "en_attente")) return "Statut invalide : terminee, a_suivre ou en_attente."
        if (maj.description.isBlank()) return "Le résumé de l'intervention ne peut pas être vide."
        val finale = maj.copy(id = ancienne.id, photosAvant = ancienne.photosAvant, photosApres = ancienne.photosApres, signature = ancienne.signature)
        repo.update { current -> current.copy(clients = current.clients.map { c ->
            if (c.id != clientId) c else c.copy(sites = c.sites.map { s ->
                if (s.id != siteId) s else s.copy(interventions = s.interventions.map { i -> if (i.id == interventionId) finale else i }.sortedByDescending { it.date })
            })
        }) }
        val base = "${finale.date} ${finale.id}"
        val (note, pdf) = ecrireRapport(client, site, finale)
        val anciennesVersions = if (ancienne.date != finale.date) obsidian.supprimerAnciennesVersionsIntervention(finale.id, base) else emptyList<String>() to emptyList()
        return buildString {
            append("✅ Intervention ${finale.id} mise à jour pour ${client.nom} — ${site.nom}.\n📁 $note\n📄 $pdf")
            if (anciennesVersions.first.isNotEmpty()) append("\n🧹 Anciennes versions supprimées : " + anciennesVersions.first.joinToString(", "))
            if (anciennesVersions.second.isNotEmpty()) append("\n⚠️ Anciennes versions non supprimées : " + anciennesVersions.second.joinToString(", "))
        }
    }


    // ------------------------------------------------------------------ distance / déplacement

    private fun referenceGeo(adresse: String, lat: Double?, lon: Double?): String =
        if (adresse.isNotBlank()) normaliser(adresse)
        else if (lat != null && lon != null) "gps:${Geo.texteGps(Geo.Point(lat, lon))}" else ""

    private fun cle(hq: String, dest: String) = normaliser(hq) + "|" + normaliser(dest)

    /** Distance siège → adresse. Utilise le cache du site si l'adresse du siège et du site n'ont pas changé. */
    suspend fun distance(client: Client, site: Site?): Geo.Route? {
        val hq = data.societe
        val dest = site?.adresse?.ifBlank { null } ?: client.adresse
        val hqRef = referenceGeo(hq.adresse, hq.lat, hq.lon)
        val destRef = referenceGeo(dest, site?.lat, site?.lon)
        if (hqRef.isBlank() || destRef.isBlank()) return null
        val key = "$hqRef|$destRef"
        if (site != null && site.distanceKm != null && site.distanceCle == key)
            return Geo.Route(site.distanceKm, site.dureeMin ?: 0.0, false)

        val origin = localiserSiege()?.point ?: return null
        val to = localiser(client, site)?.point ?: return null
        val route = Geo.route(origin, to) ?: return null
        if (site != null && !route.estime) {
            repo.update { d ->
                d.copy(clients = d.clients.map { c ->
                    if (c.id != client.id) c else c.copy(sites = c.sites.map { s ->
                        if (s.id == site.id) s.copy(distanceKm = route.km, dureeMin = route.minutes, distanceCle = key) else s
                    })
                })
            }
        }
        return route
    }

    /**
     * Distance routière siège → client. Si l'adresse générale du client manque,
     * utilise la première adresse de site disponible. Le résultat routier réel est mémorisé.
     */
    suspend fun distanceClient(client: Client): Geo.Route? {
        val hq = data.societe
        val site = if (client.adresse.isBlank()) client.sites.firstOrNull { it.adresse.isNotBlank() || (it.lat != null && it.lon != null) } else null
        val destination = site?.adresse?.ifBlank { null } ?: client.adresse
        val hqRef = referenceGeo(hq.adresse, hq.lat, hq.lon)
        val destinationRef = referenceGeo(destination, site?.lat, site?.lon)
        if (hqRef.isBlank() || destinationRef.isBlank()) return null
        val key = "$hqRef|$destinationRef"
        if (client.distanceKm != null && client.distanceCle == key)
            return Geo.Route(client.distanceKm, client.distanceDureeMin ?: 0.0, false)
        val origin = localiserSiege()?.point ?: return null
        val target = localiser(client, site)?.point ?: return null
        val route = Geo.route(origin, target) ?: return null
        if (!route.estime) {
            repo.update { current ->
                current.copy(clients = current.clients.map { c ->
                    if (c.id == client.id) c.copy(
                        distanceKm = route.km,
                        distanceDureeMin = route.minutes,
                        distanceCle = key
                    ) else c
                })
            }
        }
        return route
    }

    // ------------------------------------------------------------------ géolocalisation (GPS)

    /** Position enregistrée d'une installation, avec sa précision ; null si aucune. */
    fun positionEnregistree(site: Site?): Geo.Localisation? {
        if (site?.lat == null || site.lon == null) return null
        return Geo.Localisation(Geo.Point(site.lat, site.lon), Geo.precisionDe(site.gpsPrecision) ?: Geo.Precision.INCONNUE, site.gpsTrouve)
    }

    /**
     * Position d'une installation (ou de l'adresse du client s'il n'en a pas). Les positions déjà vérifiées sont
     * réutilisées ; celles calculées avant le contrôle de précision (« non vérifiée ») sont recalculées une fois ;
     * [forcer] recalcule tout, sauf les coordonnées saisies à la main qui ne sont jamais écrasées.
     */
    suspend fun localiser(c: Client, site: Site?, forcer: Boolean = false): Geo.Localisation? {
        val adr = site?.adresse?.ifBlank { null } ?: c.adresse
        val connue = positionEnregistree(site)
        if (connue != null && (connue.precision == Geo.Precision.MANUELLE || (!forcer && connue.precision != Geo.Precision.INCONNUE))) return connue
        if (adr.isBlank()) return connue
        val nouvelle = Geo.localiser(adr) ?: return connue
        if (connue != null && connue.precision != Geo.Precision.INCONNUE && connue.precision.rang > nouvelle.precision.rang) return connue
        if (site != null) enregistrerPosition(c.id, site.id, nouvelle)
        return nouvelle
    }

    /** Enregistre la position d'une installation ; la distance au siège est recalculée si le point a bougé. */
    fun enregistrerPosition(clientId: String, siteId: String, loc: Geo.Localisation) {
        repo.update { d ->
            d.copy(clients = d.clients.map { x ->
                if (x.id != clientId) x else {
                    val ancienSite = x.sites.firstOrNull { it.id == siteId }
                    val bouge = ancienSite == null || ancienSite.lat == null || ancienSite.lon == null ||
                        Geo.haversine(Geo.Point(ancienSite.lat, ancienSite.lon), loc.point) > 0.03
                    x.copy(
                        distanceKm = if (bouge) null else x.distanceKm,
                        distanceDureeMin = if (bouge) null else x.distanceDureeMin,
                        distanceCle = if (bouge) "" else x.distanceCle,
                        sites = x.sites.map { y ->
                            if (y.id != siteId) y else y.copy(
                                lat = loc.point.lat, lon = loc.point.lon, gpsPrecision = loc.precision.name, gpsTrouve = loc.trouve,
                                distanceKm = if (bouge) null else y.distanceKm, dureeMin = if (bouge) null else y.dureeMin,
                                distanceCle = if (bouge) "" else y.distanceCle
                            )
                        }
                    )
                }
            })
        }
    }

    /** Position du siège (point de départ des déplacements), même logique que pour les installations. */
    suspend fun localiserSiege(forcer: Boolean = false): Geo.Localisation? {
        val s = data.societe
        val connue = if (s.lat != null && s.lon != null)
            Geo.Localisation(Geo.Point(s.lat, s.lon), Geo.precisionDe(s.gpsPrecision) ?: Geo.Precision.INCONNUE) else null
        if (connue != null && (connue.precision == Geo.Precision.MANUELLE || (!forcer && connue.precision != Geo.Precision.INCONNUE))) return connue
        if (s.adresse.isBlank()) return connue
        val nouvelle = Geo.localiser(s.adresse) ?: return connue
        if (connue != null && connue.precision != Geo.Precision.INCONNUE && connue.precision.rang > nouvelle.precision.rang) return connue
        repo.update { d ->
            d.copy(
                societe = d.societe.copy(lat = nouvelle.point.lat, lon = nouvelle.point.lon, gpsPrecision = nouvelle.precision.name),
                // Le point de départ a bougé : les distances en cache sont à recalculer.
                clients = if (connue != null && Geo.haversine(connue.point, nouvelle.point) <= 0.03) d.clients
                else d.clients.map { c -> c.copy(
                    distanceKm = null, distanceDureeMin = null, distanceCle = "",
                    sites = c.sites.map { it.copy(distanceKm = null, dureeMin = null, distanceCle = "") }
                ) }
            )
        }
        return nouvelle
    }

    /** Ligne lisible : « 47.541558, 4.459845 · adresse exacte ». */
    fun texteGps(loc: Geo.Localisation) = "${Geo.texteGps(loc.point)} · ${loc.precision.libelle}"

    private fun iconePrecision(p: Geo.Precision) = when {
        p.exacte -> "🎯"
        p.rang >= Geo.Precision.LIEU_DIT.rang -> "📍"
        else -> "⚠️"
    }

    /**
     * Rapport des coordonnées GPS de toutes les installations (format Google Maps), positions approximatives en tête.
     * [relocaliser] recalcule les positions non saisies à la main.
     */
    suspend fun rapportGps(recherche: String = "", relocaliser: Boolean = false): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val filtre = normaliser(recherche.trim())
        data class Ligne(val texte: String, val rang: Int)
        val lignes = mutableListOf<Ligne>()
        val introuvables = mutableListOf<String>()
        for (c in data.clients) {
            for (site in (if (c.sites.isEmpty()) listOf(null) else c.sites)) {
                val adr = site?.adresse?.ifBlank { null } ?: c.adresse
                val nom = "${c.nom} — ${site?.nom?.ifBlank { null } ?: "Adresse client"}"
                if (filtre.isNotBlank() && !normaliser("$nom $adr").contains(filtre)) continue
                val loc = localiser(c, site, forcer = relocaliser)
                if (loc == null) { introuvables += "$nom (${adr.ifBlank { "pas d'adresse" }})"; continue }
                val reconnu = if (loc.trouve.isNotBlank() && loc.precision != Geo.Precision.MANUELLE) " — reconnu : _${loc.trouve}_" else ""
                lignes += Ligne("- ${iconePrecision(loc.precision)} **$nom** : `${Geo.texteGps(loc.point)}` · ${loc.precision.libelle} · [Google Maps](${Geo.lienGoogleMaps(loc.point)})$reconnu", loc.precision.rang)
            }
        }
        val siege = localiserSiege(forcer = relocaliser)
        buildString {
            append("# 🛰️ Coordonnées GPS des installations\n\n")
            if (siege != null) append("🏭 **Siège** : `${Geo.texteGps(siege.point)}` · ${siege.precision.libelle}\n\n")
            val approx = lignes.filter { it.rang < Geo.Precision.NUMERO.rang }.sortedBy { it.rang }
            val exactes = lignes.filter { it.rang >= Geo.Precision.NUMERO.rang }
            if (approx.isNotEmpty()) {
                append("## ⚠️ À vérifier (${approx.size})\n")
                approx.forEach { append(it.texte).append('\n') }
                append("\n")
            }
            if (exactes.isNotEmpty()) {
                append("## 🎯 Positions précises (${exactes.size})\n")
                exactes.forEach { append(it.texte).append('\n') }
                append("\n")
            }
            if (introuvables.isNotEmpty()) append("## ❌ Introuvables\n" + introuvables.joinToString("\n") { "- $it" } + "\n\n")
            if (lignes.isEmpty() && introuvables.isEmpty()) append("Aucune installation.\n\n")
            append("_Format Google Maps (latitude, longitude). Pour corriger une position : dans Google Maps, appui long sur le toit de l'installation, ")
            append("touche les coordonnées pour les copier, puis dis « coordonnées GPS de [client] : 47.xxxxxx, 4.xxxxxx »._")
        }
    }

    // ------------------------------------------------------------------ documents

    data class Creation(val doc: Doc, val avertissements: List<String>, val emplacement: String)

    suspend fun creerDocument(
        type: String, client: Client, site: Site?,
        prestations: List<Triple<Tarif, Double, Double>>, libres: List<Ligne>,
        deplacement: Boolean?, remiseManuelle: Double?, notes: String, date: LocalDate = LocalDate.now()
    ): Creation {
        val d = data
        val warn = mutableListOf<String>()
        val remise = remiseManuelle?.let { Remise(it, "remise exceptionnelle") } ?: d.remiseClient(client, date)

        var km: Double? = null
        if (deplacement != false && Pricing.deplacementConfigure(d.regles)) {
            val r = distance(client, site)
            if (r == null) warn += "Distance introuvable (adresse du siège ou du client manquante/inconnue) : frais de déplacement non ajoutés."
            else {
                km = r.km
                if (r.estime) warn += "Distance estimée à vol d'oiseau (service d'itinéraire indisponible)."
            }
        }
        val (lignes, w) = Pricing.construireLignes(prestations, remise.pct, km, d.regles, d.societe.tvaDefaut, d.societe.franchiseTva)
        warn += w
        val toutes = lignes + libres.map {
            it.copy(remisePct = remise.pct, tva = if (d.societe.franchiseTva) 0.0 else it.tva)
        }
        require(toutes.isNotEmpty()) { "Aucune ligne à facturer : " + warn.joinToString(" ") }

        val jours = if (type == "devis") d.societe.validiteDevisJours else d.societe.delaiPaiementJours
        val doc = Doc(
            numero = d.numeroSuivant(type, date.year), type = type, clientId = client.id, siteId = site?.id ?: "",
            date = date.toString(), echeance = date.plusDays(jours.toLong()).toString(), lignes = toutes, notes = notes,
            statut = if (type == "devis") "brouillon" else "a_payer",
            remiseInfo = if (remise.pct > 0) "${nombre(remise.pct)} % — ${remise.detail}" else "", distanceKm = km
        )
        repo.update { it.copy(docs = it.docs + doc) }
        return Creation(doc, warn, exporter(doc))
    }

    fun convertirEnFacture(devis: Doc): Result<Creation> {
        val d = data
        if (devis.type != "devis") return Result.failure(IllegalArgumentException("${devis.numero} n'est pas un devis."))
        if (devis.statut == "facture") return Result.failure(IllegalArgumentException("Le devis ${devis.numero} a déjà été facturé."))
        if (devis.statut == "refuse") return Result.failure(IllegalArgumentException("Le devis ${devis.numero} est refusé."))
        val today = LocalDate.now()
        val f = devis.copy(
            numero = d.numeroSuivant("facture", today.year), type = "facture", date = today.toString(),
            echeance = today.plusDays(d.societe.delaiPaiementJours.toLong()).toString(),
            statut = "a_payer", devisRef = devis.numero, payeLe = "", modePaiement = ""
        )
        repo.update { it.copy(docs = it.docs.map { x -> if (x.numero == devis.numero) x.copy(statut = "facture") else x } + f) }
        return Result.success(Creation(f, emptyList(), exporter(f)))
    }

    fun changerStatut(numero: String, statut: String): Doc? {
        var out: Doc? = null
        repo.update { d -> d.copy(docs = d.docs.map { x -> if (x.numero == numero) x.copy(statut = statut).also { out = it } else x }) }
        out?.let { exporter(it) }
        return out
    }

    /** Facture encaissée : statut « payée » + mise de côté en réserve (catégorie dédiée). */
    fun marquerPayee(numero: String, mode: String, date: LocalDate = LocalDate.now()): Pair<Doc, Provision?>? {
        val f = data.doc(numero)?.takeIf { it.type == "facture" } ?: return null
        val payee = f.copy(statut = "payee", payeLe = date.toString(), modePaiement = mode)
        val prov = if (data.provisions.any { it.factureNumero == f.numero }) null
        else Reserve.provision(payee, data.totaux(payee), data.societe.reservePct, date.toString(), newId())
        repo.update { d ->
            d.copy(docs = d.docs.map { if (it.numero == numero) payee else it }, provisions = d.provisions + listOfNotNull(prov))
        }
        exporter(payee)
        return payee to prov
    }

    /** Une facture n'est jamais supprimée : elle est annulée (et sa mise en réserve retirée). */
    fun annuler(numero: String): Doc? {
        val d0 = data.doc(numero) ?: return null
        val nouveau = if (d0.type == "facture") "annulee" else "refuse"
        var out: Doc? = null
        repo.update { d ->
            d.copy(
                docs = d.docs.map { if (it.numero == numero) it.copy(statut = nouveau).also { x -> out = x } else it },
                provisions = d.provisions.filter { it.factureNumero != numero }
            )
        }
        out?.let { exporter(it) }
        return out
    }

    /**
     * Supprime définitivement un devis ou une facture (et les fichiers associés). Irréversible.
     * Le second élément dit si le fichier Obsidian a réellement disparu (sinon il faut le signaler :
     * avant, l'échec de suppression du fichier était avalé en silence et on répondait « supprimé » à tort).
     */
    fun supprimerDocument(numero: String): Pair<Doc, Boolean>? {
        val doc = data.doc(numero) ?: return null
        val dossier = "Société/" + if (doc.type == "devis") "Devis" else "Factures"
        val client = data.client(doc.clientId)?.nom.orEmpty()
        val base = "${doc.numero} ${client}".trim()
        repo.update { d ->
            d.copy(
                docs = d.docs.filterNot { it.numero == numero },
                provisions = d.provisions.filter { it.factureNumero != numero }
            )
        }
        val fichierSupprime = runCatching { obsidian.supprimer(dossier, base) }.getOrDefault(false)
        return doc to fichierSupprime
    }

    /**
     * Retire du JSON (index local) les devis/factures dont le fichier Obsidian n'existe plus — cas d'une
     * suppression faite directement dans Obsidian (ou sur le téléphone), en dehors de JARVIS : rien ne
     * prévenait alors JARVIS, et la liste Devis/Factures restait périmée (« le coffre sur l'application
     * ne se remet pas à jour »). Le JSON n'est qu'un index pour aller vite ; le coffre reste la vraie
     * source de vérité, donc on l'aligne dessus à chaque appel. Sans effet si rien n'a changé.
     */
    fun synchroniserAvecCoffre() {
        if (data.docs.isEmpty()) return
        val manquants = data.docs.filter { doc ->
            val dossier = "Société/" + if (doc.type == "devis") "Devis" else "Factures"
            val client = data.client(doc.clientId)?.nom.orEmpty()
            val base = "${doc.numero} ${client}".trim()
            obsidian.ficheExistante(dossier, base) == null
        }
        if (manquants.isEmpty()) return
        val numeros = manquants.map { it.numero }.toSet()
        repo.update { d ->
            d.copy(
                docs = d.docs.filterNot { it.numero in numeros },
                provisions = d.provisions.filter { it.factureNumero !in numeros }
            )
        }
    }

    // ------------------------------------------------------------------ Photos d'intervention

    /** Copie les photos dans le stockage privé, les rattache à l'intervention et les synchronise dans Obsidian. */
    private fun dossierIntervention(client: Client, site: Site) = "Société/Interventions/" +
        client.nom.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "/" + site.nom.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    /**
     * Rapport d'intervention (Markdown Obsidian : photos et signature intégrées par « ![[…]] »), selon le modèle
     * « rapport » (modifiable par simple demande : presentation_modifier, cible rapport).
     */
    fun markdownIntervention(client: Client, site: Site, i: Intervention): String =
        fr.dd.jarvis.societe.core.Rendu.rendreIntervention(data, client, site, i)

    /** Régénère les rapports (note + PDF) des interventions récentes, après un changement de présentation. */
    fun regenererRapports(jours: Long = 120): Int {
        val depuis = LocalDate.now().minusDays(jours).toString()
        var n = 0
        data.clients.forEach { c -> c.sites.forEach { s -> s.interventions.filter { it.date >= depuis }
            .forEach { i -> runCatching { ecrireRapport(c, s, i); n++ } } } }
        return n
    }

    /** Image d'un rapport (« idIntervention-nom ») depuis le stockage privé, réduite pour le PDF. */
    private fun imageRapport(nom: String): android.graphics.Bitmap? {
        val id = nom.substringBefore('-'); val fichier = nom.substringAfter('-')
        val f = File(ctx.filesDir, "societe/interventions/$id/$fichier").takeIf { it.isFile } ?: return null
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        var ech = 1
        while (o.outWidth / (ech * 2) >= 1200 || o.outHeight / (ech * 2) >= 1200) ech *= 2
        return android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = ech })
    }

    /** Écrit la note Obsidian et le PDF (avec les photos et la signature dedans) ; renvoie (note, pdf). */
    fun ecrireRapport(client: Client, site: Site, i: Intervention): Pair<String, String> {
        val dossier = dossierIntervention(client, site)
        val base = "${i.date} ${i.id}"
        val md = markdownIntervention(client, site, i)
        val note = obsidian.saveIn(dossier, base, md)
        val pdf = runCatching { obsidian.saveBytes(dossier, "$base.pdf", pdfSociete(md, rapport = true) { imageRapport(it) }, "application/pdf") }
            .getOrElse { "PDF non généré (${it.message})" }
        return note to pdf
    }

    /** Signature du client (PNG) jointe à l'intervention, rapport régénéré. */
    fun ajouterSignature(clientId: String, siteId: String, interventionId: String, png: ByteArray): String {
        val client = data.client(clientId) ?: return "Client introuvable."
        val site = client.sites.firstOrNull { it.id == siteId } ?: return "Installation introuvable."
        val inter = site.interventions.firstOrNull { it.id == interventionId } ?: return "Intervention introuvable."
        File(ctx.filesDir, "societe/interventions/$interventionId").apply { mkdirs() }.let { File(it, "signature.png").writeBytes(png) }
        runCatching { obsidian.saveBytes(dossierIntervention(client, site), "$interventionId-signature.png", png, "image/png") }
        val maj = inter.copy(signature = "interventions/$interventionId/signature.png")
        repo.update { d -> d.copy(clients = d.clients.map { c -> if (c.id != clientId) c else c.copy(sites = c.sites.map { s ->
            if (s.id != siteId) s else s.copy(interventions = s.interventions.map { if (it.id == interventionId) maj else it })
        }) }) }
        val (_, pdf) = ecrireRapport(client, site, maj)
        return "✍️ Signature du client ajoutée au rapport ($pdf)."
    }

    /** Une fois : régénère les rapports des interventions qui ont des photos (elles n'étaient pas dans les PDF). */
    fun regenererRapportsAvecPhotos(): Int {
        var n = 0
        data.clients.forEach { c -> c.sites.forEach { s -> s.interventions.filter { it.photosAvant.isNotEmpty() || it.photosApres.isNotEmpty() || it.signature.isNotBlank() }
            .forEach { i -> runCatching { ecrireRapport(c, s, i); n++ } } } }
        return n
    }

    fun ajouterPhotosIntervention(
        clientId: String,
        siteId: String,
        interventionId: String,
        avant: Boolean,
        uris: List<Uri>
    ): String {
        if (uris.isEmpty()) return "Aucune photo sélectionnée."
        val client = data.client(clientId) ?: return "Client introuvable."
        val site = client.sites.firstOrNull { it.id == siteId } ?: return "Installation introuvable."
        val intervention = site.interventions.firstOrNull { it.id == interventionId } ?: return "Intervention introuvable."
        val dossierLocal = File(ctx.filesDir, "societe/interventions/$interventionId").apply { mkdirs() }
        val dossierObsidian = "Société/Interventions/" +
            client.nom.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "/" +
            site.nom.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val existantes = if (avant) intervention.photosAvant else intervention.photosApres
        val ajoutees = mutableListOf<String>()
        var totalOctets = 0L
        uris.take(10).forEach { uri ->
            runCatching {
                val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
                if (!mime.startsWith("image/")) return@runCatching
                val extension = when (mime.lowercase()) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/heic", "image/heif" -> "heic"
                    else -> "jpg"
                }
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                if (bytes.isEmpty() || bytes.size > 15 * 1024 * 1024) return@runCatching
                totalOctets += bytes.size
                if (totalOctets > 40L * 1024 * 1024) return@runCatching
                val numero = existantes.size + ajoutees.size + 1
                val nom = (if (avant) "avant" else "apres") + "-%02d.%s".format(numero, extension)
                File(dossierLocal, nom).writeBytes(bytes)
                val relatif = "interventions/$interventionId/$nom"
                ajoutees += relatif
                obsidian.saveBytes(dossierObsidian, "$interventionId-$nom", bytes, mime)
            }
        }
        if (ajoutees.isEmpty()) return "Aucune image exploitable. Formats image uniquement, 15 Mo maximum par photo et 40 Mo par ajout."
        val maj = intervention.copy(
            photosAvant = if (avant) intervention.photosAvant + ajoutees else intervention.photosAvant,
            photosApres = if (avant) intervention.photosApres else intervention.photosApres + ajoutees
        )
        repo.update { d -> d.copy(clients = d.clients.map { c ->
            if (c.id != clientId) c else c.copy(sites = c.sites.map { s ->
                if (s.id != siteId) s else s.copy(interventions = s.interventions.map { i -> if (i.id == interventionId) maj else i })
            })
        }) }
        val (emplacement, _) = ecrireRapport(client, site, maj)
        return "${ajoutees.size} photo(s) ${if (avant) "avant" else "après"} ajoutée(s). Note mise à jour : $emplacement"
    }

    // ------------------------------------------------------------------ export (Obsidian + PDF)

    fun exporter(doc: Doc): String {
        val md = Rendu.rendreDoc(data, doc)
        val dossier = "Société/" + if (doc.type == "devis") "Devis" else "Factures"
        val client = data.client(doc.clientId)?.nom.orEmpty()
        val base = "${doc.numero} ${client}".trim()
        val loc = obsidian.saveIn(dossier, base, md)
        runCatching { obsidian.saveBytes(dossier, "$base.pdf", pdfSociete(md), "application/pdf") }
        return loc
    }

    /** PDF du document (devis/facture), avec le logo s'il y en a un — utilisé pour l'envoyer en pièce jointe par email. */
    fun pdfBytes(doc: Doc): ByteArray = pdfSociete(Rendu.rendreDoc(data, doc))

    /**
     * PDF d'un document DE LA SOCIÉTÉ : logo en tête et mentions légales (raison sociale, capital, siège, SIRET, RCS,
     * TVA) en pied de chaque page, avec la pagination. Réservé aux documents de la société — les autres fichiers créés
     * par JARVIS (coloriages, notes, documents personnels…) n'ont ni logo ni pied de page.
     */
    fun pdfSociete(md: String, rapport: Boolean = false, images: (String) -> android.graphics.Bitmap? = { null }): ByteArray =
        PdfExporter.toPdf(md, Logo.bitmap(ctx), rapport, images, pied = Rendu.identiteLegale(data).ifBlank { null })

    fun exporterClient(c: Client): Pair<String, String> {
        val md = Rendu.rendreClient(data, c)
        return md to obsidian.saveIn("Société/Clients", c.nom, md)
    }

    fun bulletin(e: Employe, mois: String, primes: Double, heuresEffectuees: Double? = null, netVerse: Double? = null): Pair<String, String> {
        val local = Paie.bulletin(e, data.paie, mois, primes, heuresEffectuees, netVerse)
        // Cotisations officielles de l'URSSAF (taux, plafonds et réduction générale à jour) ; hors ligne : calcul local.
        val b = if (Urssaf.horsRegimeGeneral(data, e)) local else runCatching {
            BaremesCalcul.bulletinOfficiel(local, Urssaf.montantsPaie(data, e, local.brut), data.baremes.pmss.takeIf { it > 0 } ?: data.paie.plafondSecu, LocalDate.now().toString())
        }.getOrElse { local.copy(source = Paie.AVERTISSEMENT + " (Calcul local : le service officiel de l'URSSAF n'a pas répondu — ${it.message?.take(80) ?: "hors ligne"}.)") }
        // Un bulletin refait pour le même mois remplace l'ancien (ses cumuls ne doivent pas le compter).
        data.bulletins.filter { it.employeId == e.id && it.mois == mois }.forEach { File(dossierPaie(), "${it.id}.pdf").delete() }
        val md = Rendu.rendreBulletin(data.copy(bulletins = data.bulletins.filterNot { it.employeId == e.id && it.mois == mois }), b)
        val ref = BulletinRef(newId(), e.id, mois, b.brut, b.netAPayer, b.coutEmployeur, nomEmploye = e.nom,
            netImposable = b.netImposable, heures = b.heuresEffectuees,
            creeLe = java.time.LocalDateTime.now().toString().take(16).replace('T', ' '), md = md)
        repo.update { d -> d.copy(bulletins = d.bulletins.filterNot { it.employeId == e.id && it.mois == mois } + ref) }
        // Fichier PDF imprimable : copie privée (aperçu, impression, partage depuis l'appli) + coffre « Société/Paie ».
        val pdf = pdfSociete(md)
        runCatching { File(dossierPaie(), "${ref.id}.pdf").writeBytes(pdf) }
        val loc = obsidian.saveIn("Société/Paie", "$mois ${e.nom}", md)
        runCatching { obsidian.saveBytes("Société/Paie", "$mois ${e.nom}.pdf", pdf, "application/pdf") }
        return md to loc
    }

    // ------------------------------------------------------------------ bulletins générés : fichiers, impression, suppression

    private fun dossierPaie() = File(ctx.filesDir, "societe/paie").apply { mkdirs() }

    /** « 2026-11 Jean Dupont » : nom des fichiers du bulletin (nom du salarié au moment du bulletin). */
    fun nomBulletin(r: BulletinRef) = "${r.mois} ${r.nomEmploye.ifBlank { data.employes.firstOrNull { it.id == r.employeId }?.nom ?: "Salarié" }}"

    /** Bulletins enregistrés, du plus récent au plus ancien. */
    fun bulletins(): List<BulletinRef> = data.bulletins.sortedWith(compareByDescending<BulletinRef> { it.mois }.thenBy { nomBulletin(it) })

    /** Fichier PDF du bulletin (régénéré depuis son contenu enregistré s'il manque). */
    fun fichierBulletin(r: BulletinRef): File? {
        val f = File(dossierPaie(), "${r.id}.pdf")
        if (!f.isFile && r.md.isNotBlank()) runCatching { f.writeBytes(pdfSociete(r.md)) }
        return f.takeIf { it.isFile }
    }

    private fun uri(f: File) = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

    /** Ouvre le PDF du bulletin dans le lecteur PDF du téléphone. */
    fun ouvrirBulletin(r: BulletinRef): String {
        val f = fichierBulletin(r) ?: return "PDF introuvable pour ${nomBulletin(r)} (bulletin trop ancien : refais-le pour le régénérer)."
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri(f), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(i); "" }.getOrElse { "Aucune application ne sait ouvrir les PDF : utilise « Partager » ou « Imprimer »." }
    }

    fun partagerBulletin(r: BulletinRef) {
        val f = fichierBulletin(r) ?: return
        val i = Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri(f)); putExtra(Intent.EXTRA_SUBJECT, "Bulletin de paie ${nomBulletin(r)}"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        ctx.startActivity(Intent.createChooser(i, "Partager le bulletin").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Impression (service d'impression d'Android : imprimante Wi-Fi, PDF…). [activite] : l'écran affiché. */
    fun imprimerBulletin(activite: android.content.Context, r: BulletinRef): String {
        val f = fichierBulletin(r) ?: return "PDF introuvable."
        return PdfExporter.imprimer(activite, f, "Bulletin de paie ${nomBulletin(r)}")
    }

    /**
     * Supprime des bulletins (erreur à refaire, ou suppression simple) : ils disparaissent de la base, de leurs fichiers
     * (PDF privé, note et PDF du coffre) ET des calculs — salaires versés, résultat, réserve, cumuls.
     */
    fun supprimerBulletins(cibles: List<BulletinRef>): String {
        if (cibles.isEmpty()) return "Aucun bulletin ne correspond."
        val ids = cibles.map { it.id }.toSet()
        repo.update { d -> d.copy(bulletins = d.bulletins.filterNot { it.id in ids }) }
        val restes = cibles.filter { c -> data.bulletins.any { it.id == c.id } }
        var fichiers = 0
        cibles.forEach { r ->
            File(dossierPaie(), "${r.id}.pdf").delete()
            val nom = nomBulletin(r)
            if (runCatching { obsidian.supprimer("Société/Paie", nom) }.getOrDefault(false)) fichiers++
            runCatching { obsidian.supprimerFichier("Société/Paie", "$nom.pdf") }
        }
        if (restes.isNotEmpty()) return "❌ ${restes.size} bulletin(s) toujours présents après la suppression."
        return "🗑️ ${cibles.size} bulletin(s) supprimé(s) : " + cibles.joinToString(", ") { nomBulletin(it) } +
            ". Les salaires versés, le résultat et la réserve sont recalculés sans eux."
    }

    /**
     * Bulletins dont la note a été effacée du coffre en dehors de JARVIS (ou depuis un autre appareil) : retirés aussi de
     * la base, donc des salaires versés et du résultat. Sans effet si le coffre n'est pas lisible (on ne retire rien).
     */
    fun synchroniserBulletins() {
        if (data.bulletins.isEmpty()) return
        val notes = runCatching { obsidian.listNotes("Société") }.getOrDefault(emptyList())
        if (notes.isEmpty()) return
        val presents = notes.filter { it.startsWith("Société/Paie/") }.map { normaliser(it.substringAfterLast('/').removeSuffix(".md")) }.toSet()
        val disparus = data.bulletins.filter { normaliser(nomBulletin(it)) !in presents }
        if (disparus.isEmpty()) return
        val ids = disparus.map { it.id }.toSet()
        repo.update { d -> d.copy(bulletins = d.bulletins.filterNot { it.id in ids }) }
        disparus.forEach { File(dossierPaie(), "${it.id}.pdf").delete() }
    }

    fun bilan(annee: Int) = Reserve.bilan(annee, data.provisions, data.employes, data.bulletins, data.societe.partDirigeantPct, data.societe.reserveSelonSalaires)

    // ------------------------------------------------------------------ barèmes officiels (URSSAF), mis à jour automatiquement

    /**
     * Relève les barèmes officiels (plafond de la Sécurité sociale, SMIC, IS, prélèvement forfaitaire, coût de chaque
     * salarié) et les applique si la mise à jour automatique est active. Renvoie le compte rendu (changements en tête).
     */
    fun actualiserBaremes(): String {
        val avant = data.baremes
        val nouveaux = try { Urssaf.baremes() } catch (e: Exception) {
            repo.update { d -> d.copy(baremes = d.baremes.copy(erreur = "${LocalDate.now()} : ${e.message ?: e.javaClass.simpleName}".take(200))) }
            return "❌ Mise à jour des barèmes impossible (service URSSAF injoignable ou réponse inattendue) : ${e.message}. Les valeurs actuelles sont conservées."
        }
        val couts = runCatching { Urssaf.couts(data) }.getOrDefault(avant.couts)
        val changements = BaremesCalcul.changements(avant, nouveaux)
        val b = nouveaux.copy(couts = couts, changements = changements.ifEmpty { avant.changements })
        repo.update { d -> BaremesCalcul.appliquer(d, b) }
        return baremesTexte(if (changements.isEmpty()) "✅ Barèmes vérifiés : aucune valeur n'a changé." else "📈 **Nouvelles valeurs officielles appliquées :**\n" + changements.joinToString("\n") { "- $it" })
    }

    fun baremesTexte(entete: String = ""): String {
        val b = data.baremes; val s = data.societe
        return buildString {
            if (entete.isNotBlank()) append(entete).append("\n\n")
            append("# 📡 Barèmes officiels (URSSAF)\n")
            if (!b.valide()) append("Pas encore relevés : ils le seront automatiquement dès que le téléphone aura Internet (ou dis « mets à jour les barèmes »).\n")
            else {
                append("Relevés le ${b.dateMaj} — ${b.source}. Vérification automatique chaque semaine${if (s.baremesAuto) ", valeurs appliquées automatiquement" else " (application automatique désactivée)"}.\n\n")
                append("- Plafond mensuel de la Sécurité sociale : ${euro(b.pmss)}\n")
                if (b.smicHoraire > 0) append("- SMIC horaire brut : ${euro(b.smicHoraire)}\n")
                append("- Impôt sur les sociétés : ${nombre(b.isTauxReduit)} % jusqu'à ${euro(b.isPlafondReduit)} de bénéfice, ${nombre(b.isTauxNormal)} % au-delà\n")
                append("- Prélèvement forfaitaire sur les dividendes : ${nombre(b.pfuPct)} % (dont ${nombre(b.prelevementsSociauxPct)} % de prélèvements sociaux)\n")
                if (b.couts.isNotEmpty()) {
                    append("- Coût mensuel officiel de chaque salarié (cotisations, réduction générale comprises) :\n")
                    b.couts.forEach { (id, c) -> append("  - ${data.employes.firstOrNull { it.id == id }?.nom ?: id} : brut ${euro(c.brut)} → net ${euro(c.netAvantImpot)}, coût ${euro(c.cout)}\n") }
                }
                if (b.changements.isNotEmpty()) append("\nDerniers changements : ${b.changements.joinToString(" ; ")}\n")
            }
            if (b.erreur.isNotBlank()) append("\n⚠️ Dernière tentative échouée : ${b.erreur}\n")
            append("\nLes fiches de paie utilisent le moteur de calcul officiel de l'URSSAF (cotisations, retraite, réduction générale…) dès que le téléphone a Internet.")
        }
    }

    // ------------------------------------------------------------------ résultat, bénéfice et dividende

    /** Résultat de l'année à date (+ projection au 31/12), en Markdown. */
    fun resultatTexte(annee: Int = LocalDate.now().year): String {
        val d = data
        val arrete = if (annee < LocalDate.now().year) LocalDate.of(annee, 12, 31) else LocalDate.now()
        return Resultat.texte(Resultat.bilan(d, annee, arrete), if (annee == LocalDate.now().year) Resultat.projection(d, annee, arrete) else null, d.societe)
    }

    /**
     * Facture encaissée : répartition de cette facture (salaires et charges couverts, impôt, réserve légale, dividende) et
     * mise à jour automatique de la note « Société/Finances/Résultat <année> » du coffre.
     */
    fun repartitionPaiement(numero: String): String {
        val d = data
        val f = d.doc(numero)?.takeIf { it.type == "facture" && it.statut == "payee" } ?: return ""
        val annee = runCatching { LocalDate.parse(f.payeLe.ifBlank { f.date }).year }.getOrDefault(LocalDate.now().year)
        val part = Resultat.partFacture(d, f)
        val b = Resultat.bilan(d, annee, if (annee < LocalDate.now().year) LocalDate.of(annee, 12, 31) else LocalDate.now())
        runCatching { obsidian.saveIn("Société/Finances", "Résultat $annee", resultatTexte(annee)) }
        return Resultat.texteFacture(part, d.societe) + "\n📊 **Cumul $annee** : reste à la société avant impôt ${euro(b.avantImpot)}" +
            (if (b.soumisIs) " · bénéfice net ${euro(b.net)} · dividende possible ${euro(b.dividendeBrut)} brut (${euro(b.dividendeNet)} net)" else "") +
            "\n_Détail et projection : « résultat de la société » (note « Société/Finances/Résultat $annee »)._"
    }

    fun bilanTexte(annee: Int): String {
        val b = bilan(annee)
        val s = data.societe
        if (s.reservePct <= 0.0) return "⚠️ Aucun pourcentage de réserve n'est défini (societe_info_set reserve_pct)."
        return buildString {
            append("# 💰 Réserve bénéfices $annee\n\n")
            append("Mise de côté : **${nombre(s.reservePct)} %** du HT de chaque facture encaissée.\n\n")
            append("- Factures encaissées concernées : ${b.nbFactures}\n")
            append("- **Total réservé : ${euro(b.total)}**\n")
            if (b.selonSalaires) {
                append("- Répartition automatique selon les salaires ${if (b.brutEstime) "prévus au contrat (aucun bulletin cette année)" else "bruts versés en $annee"} :\n")
                b.parPersonne.forEach { (e, brut, part) ->
                    append("  - ${e.nom}${if (e.statut == "dirigeant") " (dirigeant)" else ""} : **${euro(part)}** — ${nombre(if (b.parPersonne.sumOf { it.second } > 0) brut * 100 / b.parPersonne.sumOf { it.second } else 0.0)} % des salaires (${euro(brut)} brut)\n")
                }
                if (b.nonAffecte > 0.01) append("- Non affecté (arrondis) : ${euro(b.nonAffecte)}\n")
            } else append("- Part dirigeant (${nombre(s.partDirigeantPct)} %) : ${euro(b.dirigeant)}\n")
            if (!b.selonSalaires && b.salaries.isNotEmpty()) {
                append("- Part salariés (${nombre(100 - s.partDirigeantPct)} %, au prorata du brut) :\n")
                b.salaries.forEach { (e, m) -> append("  - ${e.nom} : ${euro(m)}\n") }
            } else if (b.nonAffecte > 0) append("- Part non affectée (aucun salarié) : ${euro(b.nonAffecte)}\n")
            append("\n_Provision comptable interne. Un dividende doit être voté en assemblée et dépend du bénéfice distribuable ; une participation salariés suit des règles légales. À valider avec votre expert-comptable._")
        }
    }

    // ------------------------------------------------------------------ partage PDF

    /** Partage le PDF d'un document de la société (logo et mentions légales compris). */
    fun partagerPdf(nom: String, markdown: String, avecLogo: Boolean = true) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, nom.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".pdf")
        f.writeBytes(if (avecLogo) pdfSociete(markdown) else PdfExporter.toPdf(markdown))
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Partager").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ------------------------------------------------------------------ factures impayées

    fun impayees(): String {
        val d = data
        val today = LocalDate.now()
        val l = d.docs.filter { it.type == "facture" && it.statut == "a_payer" }.sortedBy { it.echeance }
        if (l.isEmpty()) return ""
        val total = l.sumOf { d.totaux(it).ttc }
        return l.joinToString("\n") { f ->
            val retard = runCatching { LocalDate.parse(f.echeance).isBefore(today) }.getOrDefault(false)
            "- ${f.numero} · ${d.client(f.clientId)?.nom.orEmpty()} · ${euro(d.totaux(f).ttc)} TTC · échéance ${dateFr(f.echeance)}" +
                if (retard) " ⚠️ EN RETARD" else ""
        } + "\n**Total à encaisser : ${euro(total)} TTC**"
    }

    // ------------------------------------------------------------------ export KML (Google Earth / My Maps)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Convertit #RRGGBB en couleur KML aabbggrr. */
    private fun kmlColor(hex: String): String {
        val h = hex.removePrefix("#")
        if (!h.matches(Regex("[0-9A-Fa-f]{6}"))) return "ff20a450"
        return "ff" + h.substring(4, 6) + h.substring(2, 4) + h.substring(0, 2)
    }

    /** Bloc « Coordonnées GPS » d'une fiche KML : format Google Maps, degrés-minutes-secondes, précision, liens. */
    private fun blocGps(loc: Geo.Localisation): String = buildString {
        val p = loc.point
        val couleur = when { loc.precision.exacte -> "#188038"; loc.precision.rang >= Geo.Precision.LIEU_DIT.rang -> "#E37400"; else -> "#D93025" }
        append("<hr><h3>🛰️ Coordonnées GPS</h3>")
        append("<p style='font-size:15px;margin:4px 0'><b>${Geo.texteGps(p)}</b></p>")
        append("<p style='color:#5f6368;margin:2px 0'>${Geo.texteDms(p).replace("\"", "&quot;")}</p>")
        append("<p>${iconePrecision(loc.precision)} <b>Précision :</b> <span style='color:$couleur'>${loc.precision.libelle}</span>")
        if (loc.trouve.isNotBlank() && loc.precision != Geo.Precision.MANUELLE)
            append("<br><span style='color:#5f6368;font-size:12px'>Adresse reconnue : ${esc(loc.trouve)}</span>")
        append("</p>")
        if (!loc.precision.exacte)
            append("<p style='color:#D93025;font-size:12px'>Position approximative. Dans Google Maps, appui long sur le toit de l'installation, copie les coordonnées puis dis à Jarvis « coordonnées GPS de ce client : 47.xxxxxx, 4.xxxxxx ».</p>")
        append("<p><a href='${Geo.lienGoogleMaps(p).replace("&", "&amp;")}'>🗺️ Ouvrir dans Google Maps</a> &nbsp;·&nbsp; ")
        append("<a href='${Geo.lienItineraire(p).replace("&", "&amp;")}'>🚗 Itinéraire</a></p>")
    }

    /** Exporte les clients en KML : fiches HTML illustrées, styles par catégorie, coordonnées GPS et données indexées. */
    suspend fun exporterKml(recherche: String = ""): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // L'export peut être déclenché par Hermès avec un instantané ancien : relire la base persistée.
        val clientsSource = repo.refresh().clients
        val manquants = mutableListOf<String>()
        data class KmlPoint(
            val nom: String, val client: String, val type: String, val adresse: String, val description: String,
            val point: Geo.Point, val loc: Geo.Localisation
        )
        val pts = mutableListOf<KmlPoint>()
        val demandeNormalisee = normaliser(recherche.trim())
        // Un filtre n'est accepté que s'il correspond EXACTEMENT à un client, un site ou une adresse.
        // Toute phrase longue / consigne composite est ignorée pour éviter de tronquer la carte.
        val filtresConnus = clientsSource.flatMap { client ->
            listOf(client.nom, client.contact, client.adresse) +
                client.sites.flatMap { listOf(it.nom, it.adresse) }
        }.map(::normaliser).filter { it.isNotBlank() }.toSet()
        val filtre = demandeNormalisee.takeIf { it.isNotBlank() && it in filtresConnus }.orEmpty()
        for (c in clientsSource) {
            val cibles = if (c.sites.isEmpty()) listOf(null) else c.sites
            for (site in cibles) {
                val adr = site?.adresse?.ifBlank { null } ?: c.adresse
                val nomSite = site?.nom?.ifBlank { null } ?: "Adresse client"
                val searchable = normaliser(listOf(c.nom, c.contact, c.type, c.tel, c.adresse, c.email, site?.nom.orEmpty(), adr, site?.onduleur.orEmpty(), site?.notes.orEmpty()).joinToString(" "))
                if (filtre.isNotBlank() && !searchable.contains(filtre)) continue
                val particulier = c.type.contains("part", ignoreCase = true)
                val emoji = if (particulier) "🏠" else "🏢"
                val label = "$emoji ${c.nom} — $nomSite"
                // Position vérifiée (Base Adresse Nationale / OpenStreetMap) ou coordonnées saisies ; Geo espace lui-même
                // les requêtes OpenStreetMap (1 par seconde).
                val loc = localiser(c, site)
                if (loc == null) { manquants += label; continue }
                val couleur = if (particulier) data.societe.kmlCouleurParticulier else data.societe.kmlCouleurProfessionnel
                val description = buildString {
                    append("<div style='font-family:Arial,sans-serif;color:#202124'>")
                    append("<div style='padding:12px 14px;background:$couleur;color:white;border-radius:8px'>")
                    append("<h2 style='margin:0'>$emoji ${esc(c.nom)}</h2>")
                    append("<div style='font-size:13px;margin-top:4px'>${if (particulier) "Client particulier" else "Client professionnel"}</div></div>")
                    if (c.contact.isNotBlank()) append("<p>👤 <b>Contact :</b> ${esc(c.contact)}</p>")
                    if (c.tel.isNotBlank()) append("<p>📞 <b>Téléphone :</b> <a href='tel:${c.tel.filter { it.isDigit() || it == '+' }}'>${esc(c.tel)}</a></p>")
                    if (c.email.isNotBlank()) append("<p>✉️ <b>E-mail :</b> ${esc(c.email)}</p>")
                    if (c.adresse.isNotBlank()) append("<p>📍 <b>Adresse client :</b> ${esc(c.adresse)}</p>")
                    if (c.depuis.isNotBlank()) append("<p>🗓️ <b>Client depuis :</b> ${esc(c.depuis)}</p>")
                    if (c.notes.isNotBlank()) append("<p>📝 <b>Notes client :</b><br>${esc(c.notes).replace("\n", "<br>")}</p>")
                    append("<hr><h3>☀️ Installation : ${esc(nomSite)}</h3>")
                    if (adr.isNotBlank()) append("<p>📍 <b>Adresse :</b> ${esc(adr)}</p>")
                    if (site != null && site.kwc > 0) append("<p>⚡ <b>Puissance :</b> ${nombre(site.kwc)} kWc</p>")
                    if (site != null && site.miseEnService.isNotBlank()) append("<p>📅 <b>Mise en service :</b> ${esc(site.miseEnService)}</p>")
                    if (site != null && site.onduleur.isNotBlank()) append("<p>🔌 <b>Onduleur :</b> ${esc(site.onduleur)}</p>")
                    if (site != null && site.nbPanneaux > 0) append("<p>🔋 <b>Nombre de panneaux :</b> ${site.nbPanneaux}</p>")
                    if (site != null && site.notes.isNotBlank()) append("<p>📝 <b>Notes installation :</b><br>${esc(site.notes).replace("\n", "<br>")}</p>")
                    append(blocGps(loc))
                    append("</div>")
                }.replace("]]>", "]]]]><![CDATA[>")
                pts += KmlPoint(label, c.nom, if (particulier) "particulier" else "professionnel", adr, description, loc.point, loc)
            }
        }
        // Google Earth superpose les repères ayant exactement les mêmes coordonnées : seul l'AFFICHAGE est décalé de
        // quelques mètres ; les coordonnées GPS de la fiche et des données restent les vraies.
        val groupesMemeAdresse = pts.indices.groupBy { i -> Geo.texteGps(pts[i].point) }
        val pointsAffichage = pts.mapIndexed { index, item ->
            val groupe = groupesMemeAdresse.values.firstOrNull { index in it }.orEmpty()
            if (groupe.size <= 1) item else {
                val rang = groupe.indexOf(index)
                val angle = 2.0 * Math.PI * rang / groupe.size - Math.PI / 2.0
                val rayonMetres = 9.0 + groupe.size.coerceAtMost(8) * 1.2
                val latOffset = rayonMetres * kotlin.math.cos(angle) / 111320.0
                val lonScale = kotlin.math.cos(Math.toRadians(item.point.lat)).coerceAtLeast(0.15)
                val lonOffset = rayonMetres * kotlin.math.sin(angle) / (111320.0 * lonScale)
                item.copy(point = Geo.Point(item.point.lat + latOffset, item.point.lon + lonOffset))
            }
        }
        val siege = localiserSiege()
        val hq = data.societe
        val kml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
            append("<name>${esc(hq.nom.ifBlank { "Clients" })} — clients</name>\n")
            append("<Style id=\"particulier\"><IconStyle><color>${kmlColor(hq.kmlCouleurParticulier)}</color><scale>1.15</scale><Icon><href>https://maps.google.com/mapfiles/kml/pushpin/wht-pushpin.png</href></Icon></IconStyle></Style>\n")
            append("<Style id=\"professionnel\"><IconStyle><color>${kmlColor(hq.kmlCouleurProfessionnel)}</color><scale>1.15</scale><Icon><href>https://maps.google.com/mapfiles/kml/pushpin/wht-pushpin.png</href></Icon></IconStyle></Style>\n")
            // Punaise BLANCHE teintée, comme pour les clients : Google Earth MULTIPLIE la couleur par celle de l'icône,
            // une punaise bleue teintée en orange donnait un repère olive foncé au lieu de la couleur choisie.
            append("<Style id=\"siege\"><IconStyle><color>${kmlColor(hq.kmlCouleurSiege)}</color><scale>1.25</scale><Icon><href>https://maps.google.com/mapfiles/kml/pushpin/wht-pushpin.png</href></Icon></IconStyle></Style>\n")
            if (siege != null) {
                val desc = "<div style='font-family:Arial,sans-serif;color:#202124'><h2 style='margin:0'>🏭 ${esc(hq.nom.ifBlank { "Siège" })}</h2><p>📍 ${esc(hq.adresse)}</p>${blocGps(siege)}</div>"
                append("<Folder><name>Siège</name><Placemark><name>🏭 Siège ${esc(hq.nom)}</name><styleUrl>#siege</styleUrl><description><![CDATA[$desc]]></description>")
                append("<Point><coordinates>${Geo.texteKml(siege.point)}</coordinates></Point></Placemark></Folder>\n")
            }
            listOf("professionnel", "particulier").forEach { categorie ->
                val categoriePoints = pointsAffichage.filter { it.type == categorie }
                if (categoriePoints.isNotEmpty()) {
                    val titre = if (categorie == "professionnel") "Clients professionnels" else "Clients particuliers"
                    append("<Folder><name>$titre (${categoriePoints.size})</name>\n")
                    categoriePoints.forEach { item ->
                        append("<Placemark><name>${esc(item.nom)}</name><Snippet maxLines=\"2\">${esc(item.client)} — ${esc(item.adresse)}</Snippet><styleUrl>#${item.type}</styleUrl><description><![CDATA[${item.description}]]></description>")
                        append("<ExtendedData><Data name=\"client\"><value>${esc(item.client)}</value></Data><Data name=\"type_client\"><value>${item.type}</value></Data><Data name=\"adresse\"><value>${esc(item.adresse)}</value></Data>")
                        append("<Data name=\"gps\"><value>${Geo.texteGps(item.loc.point)}</value></Data><Data name=\"latitude\"><value>${"%.6f".format(java.util.Locale.US, item.loc.point.lat)}</value></Data>")
                        append("<Data name=\"longitude\"><value>${"%.6f".format(java.util.Locale.US, item.loc.point.lon)}</value></Data><Data name=\"precision_gps\"><value>${esc(item.loc.precision.libelle)}</value></Data></ExtendedData>")
                        append("<Point><coordinates>${Geo.texteKml(item.point)}</coordinates></Point></Placemark>\n")
                    }
                    append("</Folder>\n")
                }
            }
            append("</Document></kml>\n")
        }
        val loc = obsidian.saveBytes("Société/Cartes", "Clients.kml", kml.toByteArray(), "application/vnd.google-earth.kml+xml")
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        File(dir, "Clients.kml").writeBytes(kml.toByteArray())
        val parPrecision = pts.groupingBy { it.loc.precision }.eachCount()
        val approximatifs = pts.filter { !it.loc.precision.exacte }.sortedBy { it.loc.precision.rang }
        buildString {
            append("# 🗺️ Export KML\n\n${pts.size} point(s) GPS enregistrés : $loc\n")
            if (filtre.isNotBlank()) append("🔎 Filtre appliqué : **${esc(recherche)}**\n")
            else if (demandeNormalisee.isNotBlank()) append("ℹ️ Aucun filtre client/site fiable détecté : export complet de la base Société.\n")
            append("👥 Clients parcourus : ${clientsSource.size} · Repères GPS exportés : ${pts.size} · Installations sans GPS exploitable : ${manquants.size}\n")
            if (pts.isNotEmpty()) {
                append("\n🎯 Précision : ")
                append(Geo.Precision.values().filter { (parPrecision[it] ?: 0) > 0 }.joinToString(" · ") { "${parPrecision[it]} ${it.libelle}" })
                append("\n")
            }
            if (approximatifs.isNotEmpty()) {
                append("\n⚠️ Positions approximatives à vérifier :\n")
                approximatifs.forEach { append("- ${it.nom} : `${Geo.texteGps(it.loc.point)}` (${it.loc.precision.libelle})\n") }
                append("\n💡 Pour une position exacte : dans Google Maps, appui long sur le toit de l'installation, copie les coordonnées et dis « coordonnées GPS de [client] : 47.xxxxxx, 4.xxxxxx ».\n")
            }
            if (manquants.isNotEmpty()) append("\n❌ Coordonnées GPS non trouvées (adresse absente ou non reconnue) : ${manquants.joinToString(", ")}\n")
            append("\nChaque fiche affiche les coordonnées au format Google Maps (« latitude, longitude ») et Google Earth (degrés-minutes-secondes), la précision, et des liens « Ouvrir dans Google Maps » / « Itinéraire ». Les données étendues « client », « type_client », « adresse » et « gps » accompagnent chaque repère.")
        }
    }

    fun partagerKml() {
        val f = File(File(ctx.cacheDir, "share"), "Clients.kml")
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.google-earth.kml+xml"
            putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Ouvrir avec Google Earth…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
