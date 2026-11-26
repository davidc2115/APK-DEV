package fr.dd.jarvis.device

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

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
    @Volatile private var loadError: String? = null
    private var empreinteCoffreMemoire: String? = null
    private var empreinteEtatCharge: String? = null
    @Volatile private var conflitMemoireExterne = false
    @Volatile private var derniereRelectureCoffre = 0L

    /** Registre commun Jarvis/Hermès, rangé dans le coffre Obsidian configuré. */
    private val registreApprentissage by lazy {
        RegistreApprentissage(
            File(ctx.filesDir, "apprentissage-registre.json"),
            { obsidian.coffreConfigure() },
            { obsidian.lireAvecDateStrict("JARVIS/Apprentissage", "index.json")?.first },
            { raw -> obsidian.saveBytes("JARVIS/Apprentissage", "index.json", raw.toByteArray(Charsets.UTF_8), "application/json") }
        )
    }

    fun registreApprentissage(): RegistreApprentissage = registreApprentissage

    private val sensitivePattern = Regex(
        "\\b(sant[eé]|m[eé]dical|maladie|diagnostic|m[eé]dicament|banque|bancaire|carte bancaire|mot de passe|password|secret|cl[eé] api|token|religion|politique|orientation sexuelle|sexualit[eé]|syndicat)\\b",
        RegexOption.IGNORE_CASE
    )

    private fun loadCandidates() {
        candidates.clear()
        // Le coffre configuré est la source unique ; la copie privée ne sert qu'à une migration initiale.
        val source = obsidian.lireOuMigrer(FOLDER, "Apprentissage en cours.md", habitsFile.takeIf { it.isFile }?.readText())
        if (source.isNullOrBlank()) return
        runCatching {
            val json = JSONObject(source)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = json.optJSONArray(key) ?: JSONArray()
                val set = candidates.getOrPut(key) { linkedSetOf() }
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(set::add)
            }
        }
    }

    private fun persistCandidates() {
        val json = JSONObject()
        candidates.forEach { (key, values) -> json.put(key, JSONArray(values.toList())) }
        val raw = json.toString(2)
        obsidian.saveIn(FOLDER, "Apprentissage en cours", raw)
        runCatching { persisterCopieHermes(habitsFile, raw) }
    }
    /** Enregistre une préférence candidate après une correction ou observation pertinente. */
    /** Enregistre une preuve dans l'unique registre durable partagé Jarvis/Hermès. */
    @Synchronized
    fun apprendre(sujet: String, preference: String, preuve: String): String {
        ensure()
        val category = sujet.trim().lowercase().ifBlank { "preferences" }.take(40)
        val value = preference.trim().replace(Regex("\\s+"), " ").take(240)
        val evidence = preuve.trim().replace(Regex("\\s+"), " ").take(300)
        if (value.length < 5 || evidence.length < 8) return "Apprentissage ignoré : préférence ou preuve trop courte."
        if (sensitivePattern.containsMatchIn(value) || sensitivePattern.containsMatchIn(evidence))
            return "Apprentissage refusé : les informations sensibles ne sont pas mémorisées automatiquement."

        val id = registreApprentissage.enregistrerPreuveHabitude(
            "[$category] $value",
            "Préférence candidate de la rubrique $category",
            "Memo.apprendre",
            evidence,
            category,
            "memo.apprendre",
            "jarvis"
        )
        val entree = registreApprentissage.habitudeApprentissage(id)
            ?: throw IllegalStateException("L'habitude enregistrée est introuvable dans le registre partagé.")
        return when (entree.etat) {
            RegistreApprentissage.ETAT_CANDIDATE,
            RegistreApprentissage.ETAT_NEEDS_REVIEW ->
                "Habitude candidate prête pour validation explicite (${entree.preuves}/3 preuves) : $value"
            RegistreApprentissage.ETAT_APPROVED ->
                "Habitude approuvée, en attente de sa phase de test (${entree.preuves} preuves) : $value"
            RegistreApprentissage.ETAT_TESTING ->
                "Habitude en phase de test (${entree.preuves} preuves) : $value"
            RegistreApprentissage.ETAT_ACTIVE ->
                "Habitude déjà active dans la mémoire partagée ; nouvelle preuve enregistrée (${entree.preuves}) : $value"
            RegistreApprentissage.ETAT_PAUSED ->
                "Habitude en pause ; la preuve est conservée sans réactivation : $value"
            else ->
                "Préférence candidate conservée (${entree.preuves}/3 preuves distinctes). Elle ne sera pas appliquée avant validation explicite."
        }
    }

    @Synchronized
    fun apprentissagesEnCours(): String {
        ensure()
        val entries = registreApprentissage.habitudesEnApprentissage()
        if (entries.isEmpty()) return "Aucune préférence en cours d'apprentissage."
        return entries.joinToString("\n") { entree ->
            "- ${entree.titre} : ${entree.preuves}/3 preuves — ${entree.etat}"
        }
    }

    companion object {
        const val FOLDER = "JARVIS"
        const val FILE = "Mémoire JARVIS.md"

        /**
         * Écrit la copie locale de la mémoire Hermès avant de valider le cache.
         * Le fichier temporaire est synchronisé sur disque puis renommé dans le même dossier :
         * un arrêt du processus ne doit pas laisser une mémoire partiellement écrite.
         */
        internal fun persisterCopieHermes(fichier: File, texte: String) {
            val parent = fichier.parentFile ?: throw IOException("Dossier de mémoire introuvable.")
            if (!parent.exists() && !parent.mkdirs()) throw IOException("Impossible de créer le dossier de mémoire.")
            val temporaire = File(parent, fichier.name + ".tmp")
            try {
                FileOutputStream(temporaire).use { sortie ->
                    sortie.write(texte.toByteArray(Charsets.UTF_8))
                    sortie.flush()
                    sortie.fd.sync()
                }
                if (!temporaire.renameTo(fichier)) throw IOException("Impossible de valider la nouvelle mémoire Hermès.")
            } finally {
                if (temporaire.exists()) temporaire.delete()
            }
        }

        private val PIECES = Regex("\\b(etage|haut|mezzanine|salon|sejour|living|rez|chaussee|rdc|bas|cuisine|chambre|bureau|garage|veranda|cave|grenier|entree|couloir|buanderie)\\b")

        /** Les deux lignes nomment des pièces, et pas les mêmes (« … du poêle de l'étage » / « … du poêle du salon »). */
        internal fun piecesDifferentes(a: String, b: String): Boolean {
            val pa = PIECES.findAll(fr.dd.jarvis.societe.core.normaliser(a)).map { it.value }.toSet()
            val pb = PIECES.findAll(fr.dd.jarvis.societe.core.normaliser(b)).map { it.value }.toSet()
            return pa.isNotEmpty() && pb.isNotEmpty() && pa != pb
        }

        private val ENTITE = Regex("\\b(climate|switch|select|input_[a-z]+|light|cover|fan|button|script|scene|sensor|binary_sensor|media_player|number|automation|lock|vacuum|water_heater|humidifier|siren|alarm_control_panel)\\.[a-z0-9_]+")
        private val ARRET = Regex("\\b(retir\\w*|enlev\\w*|desactiv\\w*|eteins|eteindre|eteint|couper|coupe|arret\\w*|fin|ferm\\w*|sors|sortir|quitt\\w*|stop\\w*|normal|normale|off)\\b")
        private val MODES = Regex("\\b(nuit|night|silence|silencieux|eco|economie|confort|comfort|turbo|boost|natural|naturel|vacances|antigel|cinema|absence|jour|performance|equilibre)\\b")
        private val APPAREILS = Regex("\\b(poeles?|lumieres?|lampes?|volets?|stores?|chauffage|radiateurs?|clim|climatisation|teles?|television|enceintes?|prises?|portail|alarme|cameras?|aspirateur|telephone|portable|musique|agenda|planning|mails?|meteo)\\b")

        /**
         * Deux consignes parlent-elles de la même chose (la nouvelle peut alors remplacer l'ancienne) ? Mots proches —
         * comptés SANS les identifiants Home Assistant, qui faisaient se ressembler toutes les consignes d'appareils —, et
         * ni pièce, ni mode, ni appareil, ni sens (activer / retirer) différents.
         */
        internal fun memeSujet(a: String, b: String): Boolean {
            if (proximite(ENTITE.replace(a.lowercase(), " "), ENTITE.replace(b.lowercase(), " ")) < 0.6) return false
            val na = fr.dd.jarvis.societe.core.normaliser(a); val nb = fr.dd.jarvis.societe.core.normaliser(b)
            fun differents(r: Regex): Boolean {
                val x = r.findAll(na).map { it.value.trimEnd('s') }.toSet(); val y = r.findAll(nb).map { it.value.trimEnd('s') }.toSet()
                return x.isNotEmpty() && y.isNotEmpty() && x != y
            }
            if (piecesDifferentes(a, b) || differents(MODES) || differents(APPAREILS)) return false
            return ARRET.containsMatchIn(na) == ARRET.containsMatchIn(nb)
        }

        /** Mots communs (≥ 4 lettres) / mots de la plus courte des deux phrases. */
        internal fun proximite(a: String, b: String): Double {
            fun mots(s: String) = fr.dd.jarvis.societe.core.normaliser(s).split(' ').filter { it.length >= 4 }.toSet()
            val ma = mots(a); val mb = mots(b)
            if (ma.isEmpty() || mb.isEmpty()) return 0.0
            return ma.intersect(mb).size.toDouble() / minOf(ma.size, mb.size)
        }

        /** Lignes de consigne d'un texte de mémoire (« ## rubrique » + lignes), normalisées. */
        private fun lignesDe(md: String): Set<String> = md.lines().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("---") && !it.startsWith("_") && ':' !in it.take(6) }
            .map { fr.dd.jarvis.societe.core.normaliser(it.removePrefix("-")) }.filter { it.isNotBlank() }.toSet()

        /**
         * Texte à charger, et s'il faut réécrire la note : la note Obsidian, sauf si la copie locale est plus récente
         * (de plus de 2 s) ET contient des lignes absentes de la note.
         */
        internal fun choisirSource(note: Pair<String, Long>?, copie: Pair<String, Long>?): Pair<String, Boolean>? {
            if (note == null) return copie?.let { it.first to false }
            if (copie == null || note.first == copie.first) return note.first to false
            val manquantes = lignesDe(copie.first) - lignesDe(note.first)
            return if (note.second > 0 && copie.second > note.second + 2_000 && manquantes.isNotEmpty()) copie.first to true else note.first to false
        }

        private const val ENTETE = "\n\nCONSIGNES DE L'UTILISATEUR (mémoire durable) — règles de fonctionnement à appliquer à CHAQUE réponse " +
            "concernée (contenu, présentation, tableau, ordre, appareils…), sans qu'il ait à les répéter ; elles priment sur ta présentation par défaut :\n"

        /** Une ligne de la mémoire qui ressemble à une consigne (et pas à un simple fait). */
        private fun estConsigne(l: String): Boolean {
            val n = fr.dd.jarvis.societe.core.normaliser(l)
            return fr.dd.jarvis.ai.Restitution.FORME.containsMatchIn(n) ||
                Regex("\\b(toujours|jamais|systematiquement|quand|lorsque|chaque fois|prefere|veux|voudrais|aimerais|ne plus|plus jamais|par defaut|tous|toutes)\\b").containsMatchIn(n)
        }

        /**
         * Bloc mémoire du prompt : tout si elle tient en peu de place ; sinon en entier les rubriques générales et celles
         * d'un domaine (maison, plex, serveur, téléphone, musique…), les lignes « consigne » des autres rubriques, et le
         * nom des rubriques restantes. Avant, au-delà de 1800 caractères, une rubrique « maison » ou « poêles » n'était
         * plus que nommée : JARVIS oubliait la consigne « tableau avec température actuelle et consigne ».
         */
        internal fun blocPrompt(items0: Map<String, String>, max: Int = 3800, vocal: Boolean? = null): String {
            // Consignes propres à l'autre canal (vocal / écrit) : retirées.
            val items = if (vocal == null) items0 else items0.mapValues { fr.dd.jarvis.ai.CanalDemande.filtrer(it.value, vocal) }.filterValues { it.isNotBlank() }
            if (items.isEmpty()) return ""
            val complet = ENTETE + items.entries.joinToString("\n") { "- [${it.key}] ${it.value.take(900)}" }
            if (complet.length <= 2400) return complet
            val generales = fr.dd.jarvis.ai.Restitution.RUBRIQUES_PERMANENTES
            val entieres = items.filterKeys { fr.dd.jarvis.societe.core.normaliser(it) in generales || fr.dd.jarvis.ai.DomainesConsignes.deRubrique(it) != null }
            val autres = items - entieres.keys
            val lignesConsignes = autres.flatMap { (k, v) -> v.lines().map { it.trim().trimStart('-', '*', '•', ' ') }.filter { it.length >= 12 && estConsigne(it) }.map { "- [$k] $it" } }
            val sansConsigne = autres.keys.filter { k -> lignesConsignes.none { it.startsWith("- [$k]") } }
            return (ENTETE + entieres.entries.joinToString("\n") { "- [${it.key}] ${it.value.take(900)}" } +
                (if (lignesConsignes.isNotEmpty()) "\n" + lignesConsignes.joinToString("\n") else "")).take(max - 300) +
                (if (sansConsigne.isNotEmpty()) "\nAutres rubriques mémorisées (détails avec memoire_lire) : ${sansConsigne.joinToString(", ")}" else "")
        }

        /** Consignes qui concernent CETTE demande, rappelées en fin de prompt (là où le modèle les suit le mieux). */
        internal fun blocDemande(items: Map<String, String>, demande: String, vocal: Boolean? = null): String {
            if (items.isEmpty() || demande.isBlank()) return ""
            val l = fr.dd.jarvis.ai.Restitution.concernees(items, demande, "", formeSeulement = false, max = 10, vocal = vocal)
            if (l.isEmpty()) return ""
            return "\n\n⚠️ CONSIGNES DE L'UTILISATEUR POUR CETTE DEMANDE — obligatoires, applique-les exactement (présentation, colonnes, " +
                "contenu, appareils concernés) même s'il ne les répète pas :\n" + l.joinToString("\n") { "- $it" }
        }
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

    /**
     * Charge la mémoire : la note Obsidian (modifiable à la main) fait foi ; mais si la copie locale est PLUS RÉCENTE
     * et contient des consignes que la note n'a pas (note pas encore synchronisée, restaurée ou remplacée par une
     * ancienne version au démarrage / après une mise à jour), la copie locale est gardée et la note est réécrite —
     * les consignes ne se perdent plus au redémarrage.
     */
    @Synchronized
    fun load() {
        val ancienneCopie = local.takeIf { it.isFile }?.readText()
        val contenu = try {
            obsidian.lireOuMigrer(FOLDER, FILE, ancienneCopie)
        } catch (e: Exception) {
            loadError = e.message ?: e.javaClass.simpleName
            return
        }
        if (contenu != null) parse(contenu)
        loadCandidates()
        if (candidates.isNotEmpty()) {
            registreApprentissage.importerAnciennesHabitudes(candidates.mapValues { it.value.toSet() })
            candidates.clear()
            persistCandidates()
        }
        empreinteCoffreMemoire = if (obsidian.coffreConfigure()) {
            obsidian.readNoteStrict("$FOLDER/$FILE")?.let(obsidian::empreinteContenu)
        } else null
        empreinteEtatCharge = obsidian.empreinteContenu(render())
        conflitMemoireExterne = false
        loaded = true
        loadError = null
        if (contenu != null && ancienneCopie == null) runCatching { persisterCopieHermes(local, render()) }
    }
    @Synchronized
    private fun ensure() {
        if (!loaded) runCatching { load() }.onFailure { loadError = it.message ?: it.javaClass.simpleName }
        if (!loaded) throw IOException("Mémoire Obsidian indisponible : ${loadError ?: "lecture non confirmée"}. Aucune ancienne copie locale n'a été utilisée.")
    }

    /** Une panne d'écriture locale annule la mutation en mémoire ; une panne du miroir Obsidian
     * après écriture locale conserve au contraire l'état durable local pour permettre sa resynchronisation. */
    private class EcritureLocaleMemoireException(cause: Exception) :
        IOException("Échec de l'écriture locale de la mémoire Jarvis.", cause)

    private fun <T> avecRollbackSiEcritureLocaleEchoue(action: () -> T): T {
        val avant = LinkedHashMap(items)
        return try { action() } catch (e: EcritureLocaleMemoireException) {
            items.clear()
            items.putAll(avant)
            throw e
        }
    }

    @Synchronized
    private fun persist(): String {
        val md = render()
        // Le coffre configuré est la source canonique ; l'empreinte empêche d'écraser
        // une modification concurrente faite directement dans Obsidian.
        val emplacement = if (obsidian.coffreConfigure()) {
            val hash = obsidian.ecrireSiInchange("$FOLDER/$FILE", md, empreinteCoffreMemoire)
            empreinteCoffreMemoire = hash
            conflitMemoireExterne = false
            "coffre Obsidian › $FOLDER › $FILE"
        } else {
            obsidian.saveIn(FOLDER, FILE.removeSuffix(".md"), md)
        }
        empreinteEtatCharge = obsidian.empreinteContenu(md)
        runCatching { persisterCopieHermes(local, md) }
            .onFailure { fr.dd.jarvis.voice.JournalVoix.noter("diagnostic", "Cache privé de la mémoire non actualisé : " + (it.message ?: it.javaClass.simpleName)) }
        return emplacement
    }
    @Synchronized
    fun set(sujet: String, texte: String): String = avecRollbackSiEcritureLocaleEchoue {
        ensure(); items[sujet.trim().lowercase().ifBlank { "general" }] = texte.trim(); persist()
    }

    /**
     * AJOUTE une consigne à sa rubrique sans effacer les autres : avant, chaque nouvelle consigne remplaçait toute la
     * rubrique (« titre et artiste seulement » effaçait « toujours YT Music Morphe »), et JARVIS ne les réappliquait plus.
     * Une consigne très proche d'une ligne existante la remplace (mise à jour) ; déjà présente : rien ne change.
     */
    @Synchronized
    fun ajouterConsigne(sujet: String, texte: String): String = avecRollbackSiEcritureLocaleEchoue {
        ensure()
        val k = sujet.trim().lowercase().ifBlank { "general" }
        val nouvelle = texte.trim().removePrefix("-").trim()
        if (nouvelle.isBlank()) return@avecRollbackSiEcritureLocaleEchoue "rien à enregistrer"
        val lignes = items[k].orEmpty().lines().map { it.trim() }.filter { it.isNotBlank() }.map { it.removePrefix("-").trim() }.toMutableList()
        val n = fr.dd.jarvis.societe.core.normaliser(nouvelle)
        // Une consigne « (vocal) » et une « (écrit) » sur le même sujet coexistent : la comparaison se fait par canal.
        val canal = fr.dd.jarvis.ai.CanalDemande.de(nouvelle)
        fun memeCanal(l: String) = fr.dd.jarvis.ai.CanalDemande.de(l) == canal
        if (lignes.any { memeCanal(it) && (fr.dd.jarvis.societe.core.normaliser(it) == n || fr.dd.jarvis.societe.core.normaliser(it).contains(n)) }) return@avecRollbackSiEcritureLocaleEchoue "déjà enregistrée"
        // Une consigne voisine n'est mise à jour que si elle parle VRAIMENT de la même chose (voir [memeSujet]) : celle du
        // poêle de l'étage n'écrase plus celle du salon, ni « retirer le mode nuit » celle qui l'active.
        val proche = lignes.indexOfFirst { memeCanal(it) && memeSujet(it, nouvelle) }
        if (proche >= 0) lignes[proche] = nouvelle else lignes += nouvelle
        items[k] = lignes.joinToString("\n") { "- $it" }
        persist()
    }



    /**
     * Enregistre [texte] en remplaçant la ligne qui vérifie [meme] (sinon l'ajoute) — SANS la fusion « consigne voisine »
     * d'[ajouterConsigne] : deux consignes apprises presque identiques (mode nuit du poêle de l'étage / du salon) ne
     * s'écrasent plus l'une l'autre.
     */
    /**
     * « Remplace ma consigne sur… » : retire les consignes de la rubrique qui parlent de la même chose ([memeSujet]) et
     * enregistre la nouvelle — les autres consignes de la rubrique sont GARDÉES (avant, toute la rubrique « maison »
     * était effacée, consignes des poêles comprises).
     */
    @Synchronized
    fun remplacerSujet(sujet: String, texte: String): String = avecRollbackSiEcritureLocaleEchoue {
        ensure()
        val k = sujet.trim().lowercase().ifBlank { "general" }
        val nouvelle = texte.trim().removePrefix("-").trim()
        if (nouvelle.isBlank()) return@avecRollbackSiEcritureLocaleEchoue "rien à enregistrer"
        val lignes = items[k].orEmpty().lines().map { it.trim() }.filter { it.isNotBlank() }.map { it.removePrefix("-").trim() }
        val canal = fr.dd.jarvis.ai.CanalDemande.de(nouvelle)
        val n = fr.dd.jarvis.societe.core.normaliser(nouvelle)
        val gardees = lignes.filterNot { l -> fr.dd.jarvis.ai.CanalDemande.de(l) == canal && (fr.dd.jarvis.societe.core.normaliser(l) == n || memeSujet(l, nouvelle)) }
        items[k] = (gardees + nouvelle).joinToString("\n") { "- $it" }
        val r = persist()
        val retirees = lignes.size - gardees.size
        if (retirees > 0) "$r ; $retirees consigne(s) sur le même sujet remplacée(s), les ${gardees.size} autre(s) gardée(s)" else r
    }

    @Synchronized
    fun remplacerConsigne(sujet: String, texte: String, meme: (String) -> Boolean): String = avecRollbackSiEcritureLocaleEchoue {
        ensure()
        val k = sujet.trim().lowercase().ifBlank { "general" }
        val nouvelle = texte.trim().removePrefix("-").trim()
        if (nouvelle.isBlank()) return@avecRollbackSiEcritureLocaleEchoue "rien à enregistrer"
        val lignes = items[k].orEmpty().lines().map { it.trim() }.filter { it.isNotBlank() }.map { it.removePrefix("-").trim() }.toMutableList()
        if (lignes.any { it == nouvelle }) return@avecRollbackSiEcritureLocaleEchoue "déjà enregistrée"
        val i = lignes.indexOfFirst(meme)
        if (i >= 0) lignes[i] = nouvelle else lignes += nouvelle
        lignes.removeAll { it != nouvelle && meme(it) }
        items[k] = lignes.joinToString("\n") { "- $it" }
        persist()
    }

    @Synchronized
    fun remove(sujet: String): Boolean = avecRollbackSiEcritureLocaleEchoue {
        ensure(); val r = items.remove(sujet.trim().lowercase()) != null; if (r) persist(); r
    }

    @Synchronized
    fun clear() = avecRollbackSiEcritureLocaleEchoue { ensure(); items.clear(); persist(); Unit }

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
        val change = texte != hermes()
        if (change) {
            // Ne pas mettre à jour le cache avant la copie durable : sinon un échec disque serait
            // considéré comme synchronisé et le hook Hermès ne réessaierait plus.
            persisterCopieHermes(hermesLocal, texte)
            hermesCache = texte
        }
        val (notes, profil) = entreesHermes(texte)
        val md = "# Mémoire d'Hermès\n\n_Synchronisée automatiquement depuis Hermès (Termux). Pour corriger une information, demande-le à Jarvis ou à Hermès._\n\n" +
            "## Notes d'Hermès\n" + notes.joinToString("\n") { "- " + it.replace("\n", " ") }.ifBlank { "_(vide)_" } +
            "\n\n## Profil de l'utilisateur (vu par Hermès)\n" + profil.joinToString("\n") { "- " + it.replace("\n", " ") }.ifBlank { "_(vide)_" } + "\n"
        // Conserver la source brute dans le coffre : le résumé Markdown ne suffit pas à reconstruire
        // fidèlement la mémoire après une réinstallation de Jarvis.
        obsidian.saveIn(FOLDER, "Mémoire Hermès source", texte)
        // Même sans changement, retenter les deux miroirs : un échec temporaire ne doit pas laisser
        // une mémoire locale correcte mais des notes Obsidian durablement périmées.
        obsidian.saveIn(FOLDER, "Mémoire Hermès", md)
        return change
    }

    @Synchronized
    fun hermes(): String {
        hermesCache?.let { return it }
        val source = if (obsidian.coffreConfigure()) {
            // Une erreur d'accès SAF remonte : la copie privée n'est pas une source concurrente.
            val distante = obsidian.lireAvecDateStrict(FOLDER, "Mémoire Hermès source.md")?.first
            if (distante != null) distante else {
                // Migration unique d'une ancienne installation : le fichier local n'est utilisé
                // que si la note est réellement absente, jamais si le coffre est inaccessible.
                val ancienne = hermesLocal.takeIf { it.isFile }?.readText().orEmpty()
                if (ancienne.isNotBlank()) obsidian.saveIn(FOLDER, "Mémoire Hermès source", ancienne)
                ancienne
            }
        } else {
            hermesLocal.takeIf { it.isFile }?.readText().orEmpty()
        }
        hermesCache = source
        return source
    }

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
        require(json.length <= 60_000) { "Les capacités Hermès dépassent la taille maximale autorisée." }
        val j = org.json.JSONObject(json)
        // Même garantie que la mémoire : pas d'accusé de réception si le fichier durable
        // n'a pas été remplacé correctement, afin que le hook réessaie.
        persisterCopieHermes(capacitesLocal, json)
        obsidian.saveBytes(FOLDER, "Capacités Hermès.json", json.toByteArray(Charsets.UTF_8), "application/json")
        capacitesCache = j
    }

    @Synchronized
    private fun capacites(): org.json.JSONObject {
        capacitesCache?.let { return it }
        val brut = if (obsidian.coffreConfigure()) {
            val distante = obsidian.lireAvecDateStrict(FOLDER, "Capacités Hermès.json")?.first
            if (distante != null) distante else {
                val ancienne = capacitesLocal.takeIf { it.isFile }?.readText().orEmpty()
                if (ancienne.isNotBlank()) obsidian.saveBytes(FOLDER, "Capacités Hermès.json", ancienne.toByteArray(Charsets.UTF_8), "application/json")
                ancienne
            }
        } else capacitesLocal.takeIf { it.isFile }?.readText().orEmpty()
        val resultat = if (brut.isBlank()) org.json.JSONObject() else org.json.JSONObject(brut)
        capacitesCache = resultat
        return resultat
    }

    /** Automatismes (cron) d'Hermès tels que synchronisés par le hook : nom, quand, prochaine, derniere, statut… */
    fun automatismesHermes(): List<org.json.JSONObject> = capacites().optJSONArray("automatismes")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }.orEmpty()

    /** Heure de la dernière synchronisation des capacités d'Hermès (0 si jamais). */
    fun capacitesHermesDate(): Long = runCatching {
        if (obsidian.coffreConfigure()) obsidian.lireAvecDateStrict(FOLDER, "Capacités Hermès.json")?.second ?: 0L
        else capacitesLocal.takeIf { it.isFile }?.lastModified() ?: 0L
    }.getOrDefault(0L)

    /** Skills ajoutés à Hermès (nom → description). */
    fun skillsHermes(): List<Pair<String, String>> = capacites().optJSONArray("skills")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("nom") to it.optString("description") }.filter { it.first.isNotBlank() }
    }.orEmpty()

    /** Connecteurs MCP d'Hermès (noms). */
    fun connecteursHermes(): List<String> = capacites().optJSONArray("mcp")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()

    /** Plugins d'Hermès (noms). */
    fun pluginsHermes(): List<String> = capacites().optJSONArray("plugins")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()

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
        val identite = "## Identité\nDans cette installation, tu es Hermès, le cerveau principal de JARVIS, l'assistant personnel de l'utilisateur sur son téléphone : " +
            "tu réponds à toutes ses demandes, avec les outils du téléphone (Jarvis) et une seule mémoire partagée.\n\n"
        val habitudesActives = registreApprentissage.habitudesActives()
        val memoire = LinkedHashMap(items)
        if (habitudesActives.isNotEmpty()) {
            val lignes = memoire["habitudes"].orEmpty().lines().filter { it.isNotBlank() }
            memoire["habitudes"] = (lignes + habitudesActives.map { (titre, description) ->
                if (description.isBlank()) "- $titre" else "- $titre : $description"
            }).distinct().joinToString("\n")
        }
        if (memoire.isEmpty()) return identite.trim()
        return identite + "## Mémoire partagée avec JARVIS\n" +
            "Consignes durables de l'utilisateur enregistrées par JARVIS (synchronisées automatiquement ; pour les modifier, utilise mcp_jarvis_memoire_ajouter ou mcp_jarvis_memoire_oublier) :\n" +
            memoire.entries.joinToString("\n") { "- [${it.key}] ${it.value.replace("\n", " ").take(600)}" }.take(6000)
    }

    /**
     * Mémoire pour un prompt, en peu de jetons : tout si elle est courte ; sinon les rubriques générales (préférences,
     * habitudes, profil…) en entier et les autres par leur seul nom — les rubriques utiles à la demande arrivent
     * déjà, en entier, dans le bloc « notes utiles » (NotesPertinentes).
     */
    @Synchronized
    fun pourPrompt(): String = try {
        ensure()
        val memoireAvecHabitudesActives = LinkedHashMap(items)
        val actives = registreApprentissage.habitudesActives()
        if (actives.isNotEmpty()) {
            val anciennes = memoireAvecHabitudesActives["habitudes"].orEmpty().lines().filter { it.isNotBlank() }
            val partagees = actives.map { (titre, description) ->
                if (description.isBlank()) "- $titre" else "- $titre : $description"
            }
            memoireAvecHabitudesActives["habitudes"] = (anciennes + partagees).distinct().joinToString("\n")
        }
        blocPrompt(memoireAvecHabitudesActives, vocal = fr.dd.jarvis.ai.CanalDemande.vocal)
    } catch (e: Exception) {
        "\n\nMÉMOIRE DURABLE TEMPORAIREMENT INACCESSIBLE : le coffre Obsidian n'a pas pu être lu. N'affirme pas qu'aucune consigne n'existe et ne remplace pas la mémoire par une ancienne copie locale. Réessaie lorsque l'accès sera rétabli."
    }

    /** Consignes (de JARVIS et d'Hermès) qui concernent cette demande, à placer en fin de prompt ; vide sinon. */
    fun consignesPour(demande: String): String {
        val tout: Map<String, String> = synchronized(this) { ensure(); LinkedHashMap(items) }
        val hermes = runCatching { entreesHermesListe().joinToString("\n") }.getOrDefault("")
        return blocDemande(if (hermes.isBlank()) tout else tout + ("hermes" to hermes), demande, fr.dd.jarvis.ai.CanalDemande.vocal)
    }

    /** Toutes les consignes, une par ligne : (rubrique, consigne). */
    @Synchronized
    fun consignesListe(): List<Pair<String, String>> {
        ensure()
        return items.flatMap { (k, v) -> v.lines().map { it.trim().removePrefix("-").trim() }.filter { it.isNotBlank() }.map { k to it } }
    }

    /** Retire UNE consigne (une ligne) de sa rubrique ; la rubrique disparaît si elle est vide. */
    @Synchronized
    fun retirerConsigne(sujet: String, ligne: String): Boolean {
        ensure()
        val k = sujet.trim().lowercase()
        val lignes = items[k]?.lines()?.map { it.trim() }?.filter { it.isNotBlank() } ?: return false
        val reste = lignes.filterNot { it.removePrefix("-").trim() == ligne.trim() }
        if (reste.size == lignes.size) return false
        if (reste.isEmpty()) items.remove(k) else items[k] = reste.joinToString("\n")
        persist()
        return true
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
