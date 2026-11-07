package fr.dd.jarvis.device

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Mémoire des consignes de l'utilisateur (présentation des fiches, contenu du point du matin…).
 * Source : la note Obsidian « JARVIS/Mémoire JARVIS.md » (modifiable à la main), copie locale en secours.
 * Une consigne par sujet : contacts, briefing, planning, mails, societe, general…
 */
class Memo(private val ctx: Context, private val obsidian: ObsidianWriter) {
    private val local get() = File(ctx.filesDir, "memo.md")
    private val items = linkedMapOf<String, String>()
    private val habitsFile get() = File(ctx.filesDir, "habits-learning.json")
    private val candidates = linkedMapOf<String, MutableSet<String>>()
    @Volatile private var loaded = false

    private val sensitivePattern = Regex(
        "\\b(sant[eé]|m[eé]dical|maladie|diagnostic|m[eé]dicament|banque|bancaire|carte bancaire|mot de passe|password|secret|cl[eé] api|token|religion|politique|orientation sexuelle|sexualit[eé]|syndicat)\\b",
        RegexOption.IGNORE_CASE
    )

    private fun loadCandidates() {
        candidates.clear()
        // Obsidian et la copie locale peuvent être momentanément désynchronisés :
        // fusionner les preuves évite de perdre un apprentissage récent au redémarrage.
        val sources = listOfNotNull(
            runCatching { obsidian.read(FOLDER, "Apprentissage en cours.md") }.getOrNull(),
            runCatching { habitsFile.takeIf { it.exists() }?.readText() }.getOrNull()
        ).distinct()
        sources.forEach { raw ->
            runCatching {
                val json = JSONObject(raw)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = json.optJSONArray(key) ?: JSONArray()
                    val set = candidates.getOrPut(key) { linkedSetOf() }
                    for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(set::add)
                }
            }
        }
    }

    private fun persistCandidates() {
        val json = JSONObject()
        candidates.forEach { (key, values) -> json.put(key, JSONArray(values.toList())) }
        val raw = json.toString(2)
        runCatching { habitsFile.writeText(raw) }
        runCatching { obsidian.saveIn(FOLDER, "Apprentissage en cours", raw) }
    }

    /** Enregistre une préférence candidate après une correction ou observation pertinente. */
    @Synchronized
    fun apprendre(sujet: String, preference: String, preuve: String): String {
        ensure()
        val category = sujet.trim().lowercase().ifBlank { "preferences" }.take(40)
        val value = preference.trim().replace(Regex("\\s+"), " ").take(240)
        val evidence = preuve.trim().replace(Regex("\\s+"), " ").take(300)
        if (value.length < 5 || evidence.length < 8) return "Apprentissage ignoré : préférence ou preuve trop courte."
        if (sensitivePattern.containsMatchIn(value) || sensitivePattern.containsMatchIn(evidence))
            return "Apprentissage refusé : les informations sensibles ne sont pas mémorisées automatiquement."
        val key = "$category|${value.lowercase()}"
        // Une même preuve ne doit pas compter plusieurs fois à cause des accents, de la casse ou de la ponctuation.
        val preuveNormalisee = java.text.Normalizer.normalize(evidence.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        val hash = MessageDigest.getInstance("SHA-256").digest(preuveNormalisee.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)
        val proofs = candidates.getOrPut(key) { linkedSetOf() }
        if (!proofs.add(hash)) return "Cette preuve a déjà été prise en compte (${proofs.size}/3)."
        if (proofs.size < 3) {
            persistCandidates()
            return "Préférence candidate conservée (${proofs.size}/3 preuves distinctes). Elle ne sera pas appliquée comme habitude avant confirmation par répétition."
        }
        val habitLine = "- $value"
        val current = items["habitudes"].orEmpty()
        val updated = if (current.lines().any { it.equals(habitLine, true) }) current
            else listOf(current.trim(), habitLine).filter { it.isNotBlank() }.joinToString("\n")
        items["habitudes"] = updated
        candidates.remove(key)
        persistCandidates()
        persist()
        return "Habitude confirmée après trois preuves distinctes et ajoutée à la mémoire durable : $value"
    }

    @Synchronized
    fun apprentissagesEnCours(): String {
        ensure()
        if (candidates.isEmpty()) return "Aucune préférence en cours d'apprentissage."
        return candidates.entries.joinToString("\n") { (key, proofs) ->
            "- ${key.substringAfter('|')} : ${proofs.size}/3 preuves"
        }
    }

    companion object {
        const val FOLDER = "JARVIS"
        const val FILE = "Mémoire JARVIS.md"
    }

    private fun parse(md: String) {
        items.clear()
        var cur: String? = null
        val buf = StringBuilder()
        fun flush() { cur?.let { k -> buf.toString().trim().takeIf { it.isNotBlank() }?.let { items[k] = it } }; buf.clear() }
        for (line in md.lines()) {
            if (line.startsWith("## ")) { flush(); cur = line.removePrefix("## ").trim().lowercase() }
            else if (cur != null) buf.appendLine(line)
        }
        flush()
    }

    private fun render(): String = buildString {
        append("---\ntype: memoire-jarvis\ntags: [jarvis]\n---\n\n# 🧠 Mémoire JARVIS\n\n")
        append("_Consignes réutilisées par JARVIS. Une rubrique par sujet : tu peux les modifier ici._\n\n")
        items.forEach { (k, v) -> append("## $k\n$v\n\n") }
    }

    @Synchronized
    fun load() {
        val md = obsidian.read(FOLDER, FILE) ?: local.takeIf { it.exists() }?.readText()
        if (md != null) parse(md)
        loadCandidates()
        loaded = true
    }

    @Synchronized
    private fun ensure() { if (!loaded) load() }

    @Synchronized
    private fun persist(): String {
        val md = render()
        runCatching { local.writeText(md) }
        return obsidian.saveIn(FOLDER, FILE.removeSuffix(".md"), md)
    }

    @Synchronized
    fun set(sujet: String, texte: String): String {
        ensure(); items[sujet.trim().lowercase().ifBlank { "general" }] = texte.trim(); return persist()
    }

    @Synchronized
    fun remove(sujet: String): Boolean {
        ensure(); val r = items.remove(sujet.trim().lowercase()) != null; if (r) persist(); return r
    }

    @Synchronized
    fun clear() { ensure(); items.clear(); persist() }

    @Synchronized
    fun all(): Map<String, String> { ensure(); return LinkedHashMap(items) }

    /** Consignes des sujets demandés (+ « general »), prêtes à ajouter à un prompt. Vide s'il n'y en a pas. */
    @Synchronized
    fun pour(vararg sujets: String): String {
        ensure()
        val keys = sujets.map { it.lowercase() } + "general"
        val sel = items.filterKeys { k -> keys.any { k == it } }
        if (sel.isEmpty()) return ""
        return "\n\nConsignes enregistrées par l'utilisateur (à respecter, elles priment sur la mise en forme par défaut) :\n" +
            sel.entries.joinToString("\n") { "- [${it.key}] ${it.value}" }
    }

    // ------------------------------------------------------------------ mémoire partagée avec Hermès

    private val hermesLocal get() = File(ctx.filesDir, "memoire-hermes.md")
    @Volatile private var hermesCache: String? = null

    /** Entrées de la mémoire d'Hermès (notes d'Hermès, profil utilisateur) à partir de ses fichiers MEMORY.md / USER.md. */
    private fun entreesHermes(brut: String): Pair<List<String>, List<String>> {
        fun bloc(nom: String) = brut.substringAfter("### $nom", "").substringBefore("\n### ").split(Regex("\\n§\\n|^§\\n|\\n§$"))
            .map { it.trim() }.filter { it.isNotBlank() && it != "§" }
        return bloc("MEMORY.md") to bloc("USER.md")
    }

    /** Reçoit la mémoire d'Hermès (synchronisation automatique) ; renvoie vrai si elle a changé. */
    @Synchronized
    fun majHermes(brut: String): Boolean {
        val texte = brut.take(20_000)
        if (texte == hermes()) return false
        hermesCache = texte
        runCatching { hermesLocal.writeText(texte) }
        val (notes, profil) = entreesHermes(texte)
        val md = "# Mémoire d'Hermès\n\n_Synchronisée automatiquement depuis Hermès (Termux). Pour corriger une information, demande-le à Jarvis ou à Hermès._\n\n" +
            "## Notes d'Hermès\n" + notes.joinToString("\n") { "- " + it.replace("\n", " ") }.ifBlank { "_(vide)_" } +
            "\n\n## Profil de l'utilisateur (vu par Hermès)\n" + profil.joinToString("\n") { "- " + it.replace("\n", " ") }.ifBlank { "_(vide)_" } + "\n"
        runCatching { obsidian.saveIn(FOLDER, "Mémoire Hermès", md) }
        return true
    }

    fun hermes(): String = hermesCache ?: runCatching { hermesLocal.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty().also { hermesCache = it }

    /** Toutes les entrées de la mémoire d'Hermès (notes puis profil). */
    fun entreesHermesListe(): List<String> = entreesHermes(hermes()).let { (n, p) -> n + p }

    /** Section « mémoire d'Hermès » pour le cerveau de Jarvis : en entier si elle est courte, sinon le profil seul. */
    fun hermesPourPrompt(): String {
        val (notes, profil) = entreesHermes(hermes())
        if (notes.isEmpty() && profil.isEmpty()) return ""
        val tout = (notes + profil).joinToString("\n") { "- " + it.replace("\n", " ").take(500) }
        return if (tout.length <= 1500) "\n\nMémoire partagée avec Hermès (notes et préférences qu'il a retenues, à appliquer aussi) :\n$tout"
        else "\n\nProfil de l'utilisateur retenu par Hermès :\n" + profil.joinToString("\n") { "- " + it.replace("\n", " ").take(300) }.take(1200) +
            "\n(${notes.size} notes d'Hermès : celles utiles à cette demande sont dans les notes ci-dessus.)"
    }

    // ------------------------------------------------------------------ capacités propres à Hermès

    private val capacitesLocal get() = File(ctx.filesDir, "capacites-hermes.json")
    @Volatile private var capacitesCache: org.json.JSONObject? = null

    @Synchronized
    fun majCapacitesHermes(json: String) {
        val j = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
        capacitesCache = j
        runCatching { capacitesLocal.writeText(json.take(60_000)) }
    }

    private fun capacites(): org.json.JSONObject = capacitesCache
        ?: runCatching { org.json.JSONObject(capacitesLocal.readText()) }.getOrNull()?.also { capacitesCache = it }
        ?: org.json.JSONObject()

    /** Skills ajoutés à Hermès (nom → description). */
    fun skillsHermes(): List<Pair<String, String>> = capacites().optJSONArray("skills")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("nom") to it.optString("description") }.filter { it.first.isNotBlank() }
    }.orEmpty()

    /** Ce qu'Hermès sait faire en plus, pour le cerveau rapide (qui lui délègue ces demandes). */
    fun capacitesPourPrompt(): String {
        val c = capacites()
        val skills = skillsHermes()
        val mcp = c.optJSONArray("mcp")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val plugins = c.optJSONArray("plugins")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val auto = c.optJSONArray("automatismes")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        if (skills.isEmpty() && mcp.isEmpty() && plugins.isEmpty() && auto.isEmpty()) return ""
        return ("\n\nCe que ton moteur approfondi (Hermès) sait faire EN PLUS de tes outils — pour toute demande qui en relève, utilise hermes_deleguer " +
            "(il a les notes, procédures et accès correspondants) :\n" +
            skills.joinToString("\n") { "- compétence « ${it.first} » : ${it.second.take(110)}" } +
            (if (mcp.isNotEmpty()) "\n- connecteurs MCP : ${mcp.joinToString()}" else "") +
            (if (plugins.isNotEmpty()) "\n- plugins : ${plugins.joinToString()}" else "") +
            (if (auto.isNotEmpty()) "\n- automatismes en place : " + auto.joinToString("; ") { "${it.optString("nom")} (${it.optString("quand")}${if (it.optBoolean("actif", true)) "" else ", en pause"})" } +
                " — pour les lister, modifier ou supprimer, délègue à Hermès." else "")).take(1800)
    }

    /** Mémoire de Jarvis telle qu'Hermès la recopie dans son SOUL.md (bloc géré, chargé à chaque session). */
    fun pourHermes(): String {
        ensure()
        val identite = "## Identité\nDans cette installation, tu ES JARVIS, l'assistant personnel de l'utilisateur sur son téléphone : " +
            "réponds toujours en tant que JARVIS (jamais « Hermès », qui n'est que le nom de ton moteur), avec une seule mémoire et une seule conversation.\n\n"
        if (items.isEmpty()) return identite.trim()
        return identite + "## Mémoire partagée avec JARVIS\n" +
            "Consignes durables de l'utilisateur enregistrées par JARVIS (synchronisées automatiquement ; pour les modifier, utilise mcp_jarvis_memoire_ajouter ou mcp_jarvis_memoire_oublier) :\n" +
            items.entries.joinToString("\n") { "- [${it.key}] ${it.value.replace("\n", " ").take(600)}" }.take(6000)
    }

    /**
     * Mémoire pour un prompt, en peu de jetons : tout si elle est courte ; sinon les rubriques générales (préférences,
     * habitudes, profil…) en entier et les autres par leur seul nom — les rubriques utiles à la demande arrivent
     * déjà, en entier, dans le bloc « notes utiles » (NotesPertinentes).
     */
    @Synchronized
    fun pourPrompt(): String {
        ensure()
        if (items.isEmpty()) return ""
        val complet = toutes()
        if (complet.length <= 1800) return complet
        val generales = setOf("general", "preferences", "preference", "habitudes", "profil", "style", "identite", "ton", "langue")
        val toujours = items.filterKeys { fr.dd.jarvis.societe.core.normaliser(it) in generales }
        val autres = items.keys - toujours.keys
        return "\n\nMémoire de l'utilisateur (consignes durables, à appliquer) :\n" +
            toujours.entries.joinToString("\n") { "- [${it.key}] ${it.value.take(500)}" }.take(1800) +
            (if (autres.isNotEmpty()) "\nAutres rubriques mémorisées (celles utiles à cette demande sont dans les notes ci-dessus ; détails avec memoire_lire) : ${autres.joinToString(", ")}" else "")
    }

    @Synchronized
    fun toutes(): String {
        ensure()
        if (items.isEmpty()) return ""
        return "\n\nMémoire de l'utilisateur (consignes durables, à appliquer) :\n" +
            items.entries.joinToString("\n") { "- [${it.key}] ${it.value.take(600)}" }.take(3500)
    }

    private val exclRegex = Regex(
        "(?:\\bsans\\b|\\bsauf\\b|\\bpas de\\b|\\bne pas (?:afficher|inclure|montrer|prendre|mettre|compter)|\\bne plus (?:afficher|inclure|montrer|prendre)|\\bexclure|\\bexclus|\\bignorer?|\\bmasquer|\\bcacher|\\benlever|\\bretirer)" +
            "\\s+(?:le |la |l'|l’|les |un |du |des )?(?:planning |agenda |calendrier |rdv |rendez-vous )?(?:de |d'|d’|du |des )?([\\p{L}0-9'’. -]{2,40}?)(?=[,.;:!?\\n()]| et | ou | mais | quand | sauf |$)",
        RegexOption.IGNORE_CASE
    )

    /** Mots (noms d'agendas) que les consignes demandent d'exclure : « sans le planning de Thomas » → [thomas]. */
    @Synchronized
    fun agendaExclus(): List<String> {
        ensure()
        val out = linkedSetOf<String>()
        items.forEach { (k, v) ->
            if (k in setOf("contacts", "mails", "societe")) return@forEach
            v.lines().forEach { line ->
                exclRegex.findAll(line).forEach { m ->
                    m.groupValues[1].lowercase().split(' ', '-', '\'', '’').map { it.trim('.') }
                        .filter { it.length >= 3 && it !in setOf("les", "des", "mon", "ton", "son", "tout", "plus", "ses", "mes") }
                        .forEach { out.add(it) }
                }
            }
        }
        return out.toList()
    }
}
