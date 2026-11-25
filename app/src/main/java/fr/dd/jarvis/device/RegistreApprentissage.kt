package fr.dd.jarvis.device

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Registre versionné des apprentissages, habitudes, skills et outils proposés.
 *
 * Le coffre Obsidian configuré est la source durable. La copie privée ne sert que de cache/reprise
 * et n'est jamais utilisée lorsque la lecture du coffre configuré a échoué.
 * Aucune entrée ne peut installer ou exécuter du code : ce registre ne fait que gérer le cycle de vie.
 * Les identifiants sont stables entre Jarvis et Hermès ; les preuves gardent leur contexte et leur source.
 */
class RegistreApprentissage(
    private val fichierLocal: File,
    private val coffreConfigure: () -> Boolean,
    private val lireCoffre: () -> String?,
    private val ecrireCoffre: (String) -> Unit
) {
    companion object {
        const val VERSION_SCHEMA = 1
        const val ETAT_OBSERVED = "observed"
        const val ETAT_CANDIDATE = "candidate"
        const val ETAT_NEEDS_REVIEW = "needs_review"
        const val ETAT_APPROVED = "approved"
        const val ETAT_TESTING = "testing"
        const val ETAT_ACTIVE = "active"
        const val ETAT_PAUSED = "paused"
        const val ETAT_REJECTED = "rejected"
        const val ETAT_FAILED = "failed"
        const val ETAT_SUPERSEDED = "superseded"

        val ETATS = setOf(
            ETAT_OBSERVED, ETAT_CANDIDATE, ETAT_NEEDS_REVIEW, ETAT_APPROVED,
            ETAT_TESTING, ETAT_ACTIVE, ETAT_PAUSED, ETAT_REJECTED, ETAT_FAILED, ETAT_SUPERSEDED
        )
        private val TRANSITIONS = mapOf(
            ETAT_OBSERVED to setOf(ETAT_CANDIDATE, ETAT_NEEDS_REVIEW, ETAT_REJECTED),
            ETAT_CANDIDATE to setOf(ETAT_NEEDS_REVIEW, ETAT_APPROVED, ETAT_REJECTED),
            ETAT_NEEDS_REVIEW to setOf(ETAT_CANDIDATE, ETAT_APPROVED, ETAT_REJECTED),
            ETAT_APPROVED to setOf(ETAT_TESTING, ETAT_PAUSED, ETAT_REJECTED),
            ETAT_TESTING to setOf(ETAT_ACTIVE, ETAT_PAUSED, ETAT_FAILED, ETAT_REJECTED),
            ETAT_ACTIVE to setOf(ETAT_PAUSED, ETAT_FAILED, ETAT_SUPERSEDED),
            ETAT_PAUSED to setOf(ETAT_TESTING, ETAT_ACTIVE, ETAT_REJECTED, ETAT_SUPERSEDED),
            ETAT_FAILED to setOf(ETAT_TESTING, ETAT_PAUSED, ETAT_REJECTED),
            ETAT_REJECTED to setOf(ETAT_CANDIDATE),
            ETAT_SUPERSEDED to emptySet()
        )

        // Le registre est partagé avec Hermès : appliquer ici aussi le filtre de confidentialité,
        // sans dépendre du chemin historique Memo.apprendre().
        private val DONNEES_SENSIBLES = Regex(
            "\\b(mot de passe|password|passphrase|api[ _-]?key|cl[eé][ _-]?api|access[ _-]?token|refresh[ _-]?token|bearer|secret|carte bancaire|num[eé]ro de carte|cvv|cryptogramme|iban|rib|num[eé]ro de s[eé]curit[eé] sociale|s[eé]curit[eé] sociale|maladie|diagnostic m[eé]dical|m[eé]dicament|traitement m[eé]dical|orientation sexuelle|vie sexuelle|religion|appartenance politique|syndicat)\\b",
            RegexOption.IGNORE_CASE
        )

        private fun verifierConfidentialite(vararg champs: String) {
            if (champs.any { DONNEES_SENSIBLES.containsMatchIn(it) }) {
                throw SecurityException("Apprentissage refusé : le contenu semble contenir une information sensible ou un secret.")
            }
        }

        private fun empreinte(texte: String): String = MessageDigest.getInstance("SHA-256")
            .digest(texte.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private var donnees = JSONObject()
    private var charge = false
    private var miroirEnAttente = false

    private class EcritureLocaleException(cause: Exception) :
        IOException("Impossible de persister localement le registre d'apprentissage.", cause)

    private fun avantMutation() = JSONObject(donnees.toString())

    private fun persisterMutation(avant: JSONObject) {
        try { persister() } catch (e: EcritureLocaleException) {
            // Une panne du disque local annule la mutation ; une panne SAF après l'écriture locale
            // conserve au contraire la version durable et laisse miroirEnAttente actif.
            donnees = avant
            throw e
        }
    }

    @Synchronized
    private fun charger() {
        if (charge) return
        val distant = if (coffreConfigure()) lireCoffre() else null
        val local = fichierLocal.takeIf { it.isFile }?.readText(Charsets.UTF_8)
        val source = when {
            distant != null -> distant
            coffreConfigure() && local != null -> local // lecture distante confirmée et note absente : migration autorisée
            !coffreConfigure() -> local
            else -> null
        }
        donnees = if (source.isNullOrBlank()) nouveauRegistre() else parser(source)
        charge = true
        if (coffreConfigure() && distant == null) miroirEnAttente = true
        if (source == null || source.isBlank()) persister()
        else if (distant == null && coffreConfigure()) persister()
    }

    private fun nouveauRegistre() = JSONObject()
        .put("schemaVersion", VERSION_SCHEMA)
        .put("updatedAt", System.currentTimeMillis())
        .put("entries", JSONArray())
        .put("history", JSONArray())

    private fun parser(raw: String): JSONObject {
        if (raw.length > 1_000_000) throw IOException("Registre d'apprentissage trop volumineux.")
        val json = try { JSONObject(raw) } catch (e: JSONException) {
            throw IOException("Le registre d'apprentissage Obsidian contient un JSON invalide.", e)
        }
        val version = json.optInt("schemaVersion", -1)
        if (version != VERSION_SCHEMA) throw IOException("Version de registre non prise en charge : $version.")
        if (json.optJSONArray("entries") == null || json.optJSONArray("history") == null)
            throw IOException("Structure du registre d'apprentissage incomplète.")
        return json
    }

    private fun synchroniserAvantLecture() {
        charger()
        if (miroirEnAttente) persister()
    }

    private fun persister() {
        donnees.put("schemaVersion", VERSION_SCHEMA)
        donnees.put("updatedAt", System.currentTimeMillis())
        val raw = donnees.toString(2)
        if (raw.length > 1_000_000) throw IOException("Registre d'apprentissage trop volumineux.")
        try { ecrireLocalAtomique(raw) }
        catch (e: Exception) { throw EcritureLocaleException(e) }
        if (coffreConfigure()) {
            // Marquer la synchronisation en attente AVANT l'appel SAF : si l'écriture échoue,
            // la prochaine lecture ou mutation retentera le miroir depuis la copie locale durable.
            miroirEnAttente = true
            ecrireCoffre(raw)
            miroirEnAttente = false
        }
    }

    private fun ecrireLocalAtomique(raw: String) {
        val parent = fichierLocal.parentFile ?: throw IOException("Dossier local du registre introuvable.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Impossible de créer le dossier du registre.")
        val temporaire = File(parent, fichierLocal.name + ".tmp")
        try {
            FileOutputStream(temporaire).use {
                it.write(raw.toByteArray(Charsets.UTF_8))
                it.flush()
                it.fd.sync()
            }
            if (!temporaire.renameTo(fichierLocal))
                throw IOException("Impossible de valider le registre local d'apprentissage.")
        } finally {
            if (temporaire.exists()) temporaire.delete()
        }
    }

    private fun entree(id: String): JSONObject? {
        val entries = donnees.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            if (item.optString("id") == id) return item
        }
        return null
    }

    private fun journal(id: String, action: String, details: String, acteur: String) {
        val history = donnees.optJSONArray("history") ?: JSONArray()
        history.put(JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("entryId", id)
            .put("action", action)
            .put("details", details.take(500))
            .put("actor", acteur.take(80))
            .put("at", System.currentTimeMillis()))
        while (history.length() > 1000) history.remove(0)
    }

    /** Crée une observation ou une proposition. Les skills/outils restent inactifs par défaut. */
    @Synchronized
    fun creer(type: String, titre: String, description: String, provenance: String, acteur: String = "jarvis"): String {
        synchroniserAvantLecture()
        val kind = type.trim().lowercase()
        if (kind !in setOf("habit", "skill", "tool", "error"))
            throw IllegalArgumentException("Type attendu : habit, skill, tool ou error.")
        val nom = titre.trim().replace(Regex("\\s+"), " ").take(160)
        if (nom.length < 3) throw IllegalArgumentException("Titre trop court.")
        val details = description.trim().take(2000)
        val source = provenance.trim().take(500)
        verifierConfidentialite(nom, details, source)
        val entries = donnees.getJSONArray("entries")
        // Une même proposition ouverte ne doit pas se multiplier si Jarvis et Hermès la détectent ensemble.
        for (i in 0 until entries.length()) {
            val existante = entries.optJSONObject(i) ?: continue
            if (existante.optString("type") == kind &&
                existante.optString("title").equals(nom, ignoreCase = true) &&
                existante.optString("state") !in setOf(ETAT_REJECTED, ETAT_SUPERSEDED)) {
                return existante.optString("id")
            }
        }
        val avant = avantMutation()
        val id = UUID.randomUUID().toString()
        val item = JSONObject()
            .put("id", id)
            .put("type", kind)
            .put("title", nom)
            .put("description", details)
            .put("state", ETAT_OBSERVED)
            .put("provenance", source)
            .put("createdAt", System.currentTimeMillis())
            .put("updatedAt", System.currentTimeMillis())
            .put("evidence", JSONArray())
            .put("counterexamples", JSONArray())
            .put("permissions", JSONArray())
            .put("version", 1)
        donnees.getJSONArray("entries").put(item)
        journal(id, "created", "Création en état observed", acteur)
        persisterMutation(avant)
        return id
    }

    /**
     * Alimente le registre partagé depuis l'apprentissage historique de Memo.
     * Une habitude ne devient candidate qu'après trois contenus de preuve réellement distincts ;
     * changer le contexte ou la source d'une même phrase ne gonfle pas le compteur.
     */
    @Synchronized
    fun enregistrerPreuveHabitude(
        titre: String,
        description: String,
        provenance: String,
        contenu: String,
        contexte: String,
        source: String,
        acteur: String = "jarvis"
    ): String {
        val id = creer("habit", titre, description, provenance, acteur)
        ajouterPreuve(id, contenu, contexte, source, acteur)
        val item = entree(id) ?: throw IllegalStateException("Habitude introuvable après enregistrement.")
        val preuves = item.optJSONArray("evidence") ?: JSONArray()
        val contenusDistincts = mutableSetOf<String>()
        for (i in 0 until preuves.length()) {
            val texte = preuves.optJSONObject(i)?.optString("content").orEmpty()
            if (texte.isBlank()) continue
            val normalise = java.text.Normalizer.normalize(texte.lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
            if (normalise.isNotBlank()) contenusDistincts.add(empreinte(normalise))
        }
        if (item.optString("state") == ETAT_OBSERVED && contenusDistincts.size >= 3) {
            changerEtat(id, ETAT_CANDIDATE, false, acteur)
        }
        return id
    }

    /** Ajoute une preuve contextualisée ; son empreinte empêche les répétitions artificielles. */
    @Synchronized
    fun ajouterPreuve(id: String, contenu: String, contexte: String, source: String, acteur: String = "jarvis"): Boolean {
        synchroniserAvantLecture()
        val item = entree(id) ?: throw IllegalArgumentException("Entrée inconnue : $id.")
        val texte = contenu.trim().take(1000)
        if (texte.length < 8) throw IllegalArgumentException("Preuve trop courte.")
        val contexteNet = contexte.trim().take(500)
        val origine = source.trim().take(200)
        verifierConfidentialite(texte, contexteNet, origine)
        // La preuve est identique seulement si son contenu, son contexte et sa source le sont.
        val hash = empreinte(listOf(texte, contexteNet, origine)
            .joinToString("|") { it.lowercase().replace(Regex("\\s+"), " ").trim() })
        val preuves = item.optJSONArray("evidence") ?: JSONArray().also { item.put("evidence", it) }
        if ((0 until preuves.length()).any { preuves.optJSONObject(it)?.optString("hash") == hash }) return false
        val avant = avantMutation()
        preuves.put(JSONObject()
            .put("hash", hash)
            .put("content", texte)
            .put("context", contexteNet)
            .put("source", origine)
            .put("at", System.currentTimeMillis()))
        item.put("updatedAt", System.currentTimeMillis())
        journal(id, "evidence_added", "Preuve contextualisée ajoutée", acteur)
        persisterMutation(avant)
        return true
    }

    @Synchronized
    fun ajouterContreExemple(id: String, contenu: String, contexte: String, acteur: String = "jarvis") {
        synchroniserAvantLecture()
        val item = entree(id) ?: throw IllegalArgumentException("Entrée inconnue : $id.")
        val texte = contenu.trim().take(1000)
        if (texte.length < 8) throw IllegalArgumentException("Contre-exemple trop court.")
        verifierConfidentialite(texte, contexte)
        val exemples = item.optJSONArray("counterexamples") ?: JSONArray().also { item.put("counterexamples", it) }
        val hash = empreinte(texte.lowercase().replace(Regex("\\s+"), " "))
        if ((0 until exemples.length()).any { exemples.optJSONObject(it)?.optString("hash") == hash }) return
        val avant = avantMutation()
        exemples.put(JSONObject().put("hash", hash).put("content", texte)
            .put("context", contexte.trim().take(500)).put("at", System.currentTimeMillis()))
        item.put("updatedAt", System.currentTimeMillis())
        journal(id, "counterexample_added", "Contre-exemple enregistré", acteur)
        persisterMutation(avant)
    }

    /**
     * Enregistre les permissions demandées par un skill/outil sans les accorder.
     * Les scopes sont explicites et bornés ; wildcard, shell, root et exécution de code sont refusés.
     */
    @Synchronized
    fun demanderPermissions(id: String, permissions: List<String>, acteur: String = "jarvis") {
        synchroniserAvantLecture()
        val item = entree(id) ?: throw IllegalArgumentException("Entrée inconnue : $id.")
        if (item.optString("type") !in setOf("skill", "tool"))
            throw IllegalArgumentException("Les permissions ne concernent que les skills et outils.")
        if (permissions.isEmpty() || permissions.size > 12)
            throw IllegalArgumentException("Indique entre 1 et 12 permissions précises.")
        val autorisees = setOf(
            "memory:read", "memory:write",
            "calendar:read", "calendar:write",
            "files:read", "files:write",
            "homeassistant:read", "homeassistant:control",
            "network:read", "network:request",
            "phone:read", "phone:control",
            "obsidian:read", "obsidian:write"
        )
        val normalisees = permissions.map { it.trim().lowercase() }.distinct()
        val refusees = normalisees.filter { it !in autorisees }
        if (refusees.isNotEmpty())
            throw SecurityException("Permissions inconnues ou trop larges refusées : ${refusees.joinToString(", ")}.")
        val avant = avantMutation()
        item.put("requestedPermissions", JSONArray(normalisees.sorted()))
        item.put("updatedAt", System.currentTimeMillis())
        journal(id, "permissions_requested", "Permissions demandées : ${normalisees.sorted().joinToString(", ")}", acteur)
        persisterMutation(avant)
    }

    /**
     * Déclare le manifeste d'un skill/outil proposé : version, origine, dépendances, risque et plan de test.
     * Ce manifeste est descriptif uniquement ; il ne télécharge, n'installe ni n'exécute rien.
     */
    @Synchronized
    fun declarerManifest(
        id: String,
        version: String,
        source: String,
        dependances: List<String>,
        risque: String,
        planTest: String,
        acteur: String = "jarvis"
    ) {
        synchroniserAvantLecture()
        val item = entree(id) ?: throw IllegalArgumentException("Entrée inconnue : $id.")
        if (item.optString("type") !in setOf("skill", "tool"))
            throw IllegalArgumentException("Un manifeste ne concerne que les skills et outils.")
        if (item.optString("state") in setOf(ETAT_REJECTED, ETAT_FAILED, ETAT_SUPERSEDED))
            throw IllegalStateException("Impossible de documenter un skill refusé, échoué ou remplacé.")
        val versionNet = version.trim()
        if (!versionNet.matches(Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,39}")))
            throw IllegalArgumentException("Version invalide (40 caractères maximum).")
        val origine = source.trim()
        if (origine.length !in 3..500) throw IllegalArgumentException("Origine du skill obligatoire (500 caractères maximum).")
        val depsBrutes = dependances.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (depsBrutes.size > 20 || depsBrutes.any { it.length !in 2..100 })
            throw IllegalArgumentException("Indique au maximum 20 dépendances nommées de 2 à 100 caractères.")
        val deps = depsBrutes
        val niveauRisque = risque.trim().lowercase()
        if (niveauRisque !in setOf("low", "medium", "high"))
            throw IllegalArgumentException("Risque attendu : low, medium ou high.")
        val plan = planTest.trim()
        if (plan.length !in 10..1500) throw IllegalArgumentException("Plan de test invalide (10 à 1500 caractères).")
        verifierConfidentialite(versionNet, origine, deps.joinToString(" "), plan)
        val avant = avantMutation()
        item.put("manifest", JSONObject()
            .put("version", versionNet)
            .put("source", origine)
            .put("dependencies", JSONArray(deps))
            .put("risk", niveauRisque)
            .put("testPlan", plan)
            .put("updatedAt", System.currentTimeMillis()))
        item.put("updatedAt", System.currentTimeMillis())
        journal(id, "manifest_declared", "Manifeste descriptif mis à jour (risque $niveauRisque)", acteur)
        persisterMutation(avant)
    }

    /** Toute activation exige une validation explicite ; les transitions impossibles sont refusées. */
    @Synchronized
    fun changerEtat(id: String, nouvelEtat: String, confirmationExplicite: Boolean, acteur: String = "jarvis") {
        synchroniserAvantLecture()
        val cible = nouvelEtat.trim().lowercase()
        if (cible !in ETATS) throw IllegalArgumentException("État inconnu : $cible.")
        val item = entree(id) ?: throw IllegalArgumentException("Entrée inconnue : $id.")
        val actuel = item.optString("state")
        if (cible !in TRANSITIONS[actuel].orEmpty())
            throw IllegalStateException("Transition interdite : $actuel → $cible.")
        if (cible in setOf(ETAT_APPROVED, ETAT_TESTING, ETAT_ACTIVE) && !confirmationExplicite)
            throw SecurityException("Confirmation explicite requise pour passer à l'état $cible.")
        val avant = avantMutation()
        item.put("state", cible)
        item.put("updatedAt", System.currentTimeMillis())
        item.put("version", item.optInt("version", 1) + 1)
        journal(id, "state_changed", "$actuel → $cible", acteur)
        persisterMutation(avant)
    }

    @Synchronized
    fun snapshot(): String {
        synchroniserAvantLecture()
        return donnees.toString(2)
    }

    /** API structurée utilisée par le pont MCP Jarvis ↔ Hermès. */
    @Synchronized
    fun executer(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase()
        val id = args.optString("id").trim()
        val acteur = args.optString("acteur").ifBlank { "hermes" }
        return when (action) {
            "list", "read" -> snapshot()
            "create" -> JSONObject().put("id", creer(
                args.optString("type"), args.optString("titre"),
                args.optString("description"), args.optString("provenance"), acteur
            )).toString()
            "evidence" -> JSONObject().put("added", ajouterPreuve(
                id, args.optString("contenu"), args.optString("contexte"), args.optString("source"), acteur
            )).toString()
            "counterexample" -> {
                ajouterContreExemple(id, args.optString("contenu"), args.optString("contexte"), acteur)
                """{"ok":true}"""
            }
            "state" -> {
                changerEtat(id, args.optString("etat"), args.optBoolean("confirmation_explicite", false), acteur)
                """{"ok":true}"""
            }
            "permission_request" -> {
                val scopes = args.optJSONArray("permissions") ?: JSONArray()
                demanderPermissions(id, (0 until scopes.length()).mapNotNull { scopes.optString(it).takeIf(String::isNotBlank) }, acteur)
                """{"ok":true,"granted":false}"""
            }
            "manifest" -> {
                val deps = args.optJSONArray("dependances") ?: JSONArray()
                declarerManifest(
                    id, args.optString("version"), args.optString("source"),
                    (0 until deps.length()).mapNotNull { deps.optString(it).takeIf(String::isNotBlank) },
                    args.optString("risque"), args.optString("plan_test"), acteur
                )
                """{"ok":true,"installed":false,"executed":false}"""
            }
            else -> throw IllegalArgumentException("Action attendue : list, read, create, evidence, counterexample, manifest, permission_request ou state.")
        }
    }
}
