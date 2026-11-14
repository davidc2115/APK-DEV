package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Carnet de notes de l'utilisateur (« Jarvis, prends note… ») : chaque note est AJOUTÉE à sa rubrique (jamais
 * remplacée), datée, rangée automatiquement par sujet (« Poêle à granulés », « Télé »…), gardée dans le téléphone et
 * recopiée dans Obsidian (JARVIS/Notes/<rubrique>.md). Prendre note n'agit jamais sur un appareil : ni Home Assistant
 * ni une autre application. Les notes sont relues automatiquement avant d'agir sur le sujet (NotesPertinentes) et les
 * entités Home Assistant qu'elles citent passent en tête de ha_entites.
 */
object Carnet {
    data class Note(val sujet: String, val texte: String, val ts: Long)

    sealed class Intention {
        data class Noter(val texte: String, val sujet: String?) : Intention()
        /** Relire une rubrique (sujet vide : tout le carnet). */
        data class Lire(val sujet: String) : Intention()
        /** « Prends note » sans contenu : la phrase suivante sera notée. */
        object Ecoute : Intention()
        /** « Mets ça dans mes notes » : noter la réponse précédente de JARVIS. */
        object NoterPrecedent : Intention()
        object SupprimerDerniere : Intention()
    }

    const val DOSSIER = "JARVIS/Notes"

    /** Sujets reconnus (texte normalisé) → nom de la rubrique. */
    private val SUJETS: List<Pair<Regex, String>> = listOf(
        Regex("\\b(poeles?|poiles?|poils?|pellets?|granules?|inserts?)\\b") to "Poêle à granulés",
        Regex("\\b(chaudieres?)\\b") to "Chaudière",
        Regex("\\b(chauffe eau|ballon d eau chaude|cumulus)\\b") to "Chauffe-eau",
        Regex("\\b(clim|climatisation|climatiseurs?|pompe a chaleur|pac)\\b") to "Climatisation",
        Regex("\\b(chauffage|radiateurs?|thermostats?)\\b") to "Chauffage",
        Regex("\\b(teles?|tv|television|televiseur)\\b") to "Télé",
        Regex("\\b(lumieres?|lampes?|eclairage|ampoules?)\\b") to "Lumières",
        Regex("\\b(volets?|stores?)\\b") to "Volets",
        Regex("\\b(portail|garage)\\b") to "Portail et garage",
        Regex("\\b(alarme|cameras?|videosurveillance)\\b") to "Alarme et caméras",
        Regex("\\b(piscine)\\b") to "Piscine",
        Regex("\\b(voiture|vehicule|camion|utilitaire)\\b") to "Voiture",
        Regex("\\b(freebox|box|wifi|routeur)\\b") to "Box et réseau",
        Regex("\\b(serveur|debian|ssh|docker)\\b") to "Serveur",
        Regex("\\b(home assistant|domotique)\\b") to "Home Assistant",
        Regex("\\b(onduleurs?|panneaux|photovoltaique|solaire)\\b") to "Photovoltaïque",
        Regex("\\b(courses)\\b") to "Courses",
    )

    /** Entité Home Assistant citée dans une note (climate.poele_salon…). */
    internal val ENTITE = Regex(
        "\\b(?:climate|switch|sensor|binary_sensor|number|select|input_(?:number|boolean|select|text|datetime|button)|light|media_player|fan|" +
            "water_heater|button|cover|scene|script|automation|lock|vacuum|person|device_tracker|humidifier|siren|valve|remote|text|time|date)\\.[a-z0-9_]+\\b"
    )

    // ------------------------------------------------------------------ compréhension de la demande

    private const val POLI = "(?:(?:ok|okay|hey|dis|bon|alors|eh|oui)[ ,]+)?(?:jarvis|herm[eè]s)?[ ,!.]*" +
        "(?:(?:est[- ]ce que )?tu (?:peux|pourrais|veux bien|peux bien)|peux[- ]tu|pourrais[- ]tu|merci de|s'il te pla[iî]t,?|stp,?)?\\s*"
    private val NOTER = Regex(
        "^\\s*$POLI(?:(?:me |nous )?prend(?:s|re|ez)? (?:bien |vite |juste )?(?:en )?(?:une |des |la |bonne )?notes?|" +
            "not(?:e|es|er|ez)\\b(?! de frais)(?: (?:bien|vite|juste))?|" +
            // « retiens que… », « souviens-toi que… », « mémorise… » : même carnet (et faits durables extraits).
            "(?:retiens|retenez|souviens[- ]toi|m[ée]morise[rz]?|rappelle[- ]toi|n'oublie pas que|garde (?:bien )?en (?:t[eê]te|m[ée]moire))(?: bien)?|" +
            "(?:ajoute|rajoute|mets?|mettre|[ée]cri(?:s|re)|inscri(?:s|re)|enregistre|garde)\\b(.{0,300}?)\\b(?:dans|à|a|aux|sur|en) " +
            "(?:mes |les |tes |ton |mon |le |la |ma )?(?:notes?|carnet|bloc[- ]notes|pense[- ]b[êe]te))(?=[\\s,:;.!-]|$)",
        RegexOption.IGNORE_CASE
    )
    private val LIRE = listOf(
        Regex("^\\s*$POLI(?:(?:re)?lis|lire|relire|montre|affiche|donne|r[ée]p[èe]te|rappelle|dis)[- ]?(?:moi|nous)? (?:un peu |encore )?(?:toutes )?(?:mes|les|tes|la|ma|ta) notes?\\b(.*)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*$POLI(?:qu'?est[- ]ce que|que) (?:j'ai|je t'ai (?:fait |demand[ée] de )?|tu as)\\s*(?:not[ée]|pris en note|pris note)(.*)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*$POLI(?:qu'?est[- ]ce qu'il y a|qu'y a[- ]t[- ]il|qu'as[- ]tu|qu'est ce qu'il y a) (?:dans|sur) (?:mes|les|tes|ton|le|mon) (?:notes?|carnet)\\b(.*)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*$POLI(?:quelles sont|c'est quoi|ce sont quoi) (?:mes|les|tes) notes?\\b(.*)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*$POLI(?:mes|tes) notes (sur|du|de la|de l'|des|pour|concernant|à propos d.*)$", RegexOption.IGNORE_CASE),
    )
    private val SUPPRIMER_DERNIERE = Regex("^\\s*$POLI(?:supprime|efface|retire|annule|enl[èe]ve)[- ]?(?:moi)? (?:la|ma|cette) (?:derni[èe]re )?note(?: (?:que tu viens|pr[ée]c[ée]dente|d'avant))?.{0,25}$", RegexOption.IGNORE_CASE)
    /** Ce qui relève de l'agenda, des tâches ou d'Obsidian, pas du carnet. */
    private val EXCLU = Regex(
        "^(?:un |une |le |la |l'|mon |ma )?(?:rendez[- ]vous|rdv|rappel|t[âa]che|[ée]v[ée]nement|alarme|r[ée]veil)\\b|" +
            "\\b(?:dans|sur|à|a) (?:l'|mon |ma |mes |le |la )?(?:agenda|calendrier|planning|t[âa]ches|liste de t[âa]ches|obsidian|fiche)",
        RegexOption.IGNORE_CASE
    )
    private val LIANTS = Regex("^(?:(?:de |d'|du |que |qu'|(?:ça|ca|ceci|cela)\\s*[:–—-]|bien |ce qui suit|s'il te pla[iî]t|stp|pour moi|[:,;.\\-–—])\\s*)+", RegexOption.IGNORE_CASE)
    private val SUJET_EXPLICITE = Regex(
        "^(?:pour|sur|concernant|à propos d[eu']?|a propos d[eu']?|au sujet d[eu']?)\\s*(?:le |la |les |l'|du |de la |des |mon |ma |mes )?([^:,.;\\n]{2,50}?)\\s*[:–—-]\\s*(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** Ce que la phrase demande au carnet (null : ce n'est pas une demande de note). */
    internal fun intention(texte: String): Intention? {
        val t = texte.trim()
        if (t.isBlank() || t.length > 4000) return null
        if (SUPPRIMER_DERNIERE.matches(t)) return Intention.SupprimerDerniere
        for (r in LIRE) r.find(t)?.let { m -> if (t.length <= 160) return Intention.Lire(sujetDemande(m.groupValues.getOrNull(1).orEmpty())) }
        val m = NOTER.find(t) ?: return null
        // « Ajoute le code du portail dans mes notes » : le contenu est AVANT « dans mes notes ».
        val milieu = m.groups[1]?.value.orEmpty().trim()
        val apres = t.substring(m.range.last + 1).trim().replace(LIANTS, "").trim()
        val reste = listOf(milieu.replace(LIANTS, "").trim(), apres).filter { it.isNotBlank() }.joinToString(" : ")
        if (reste.isBlank() || Regex("^(?:s'il te pla[iî]t|stp|merci|vas[- ]y|pour moi)[ .!]*$", RegexOption.IGNORE_CASE).matches(reste)) return Intention.Ecoute
        if (REFERENCE.matches(reste)) return Intention.NoterPrecedent
        if (EXCLU.containsMatchIn(reste.take(60))) return null
        // « pour le réglage du poêle : … » → rubrique tirée du sujet annoncé, note complète conservée.
        SUJET_EXPLICITE.find(reste)?.let { e -> return Intention.Noter(reste, e.groupValues[1].trim()) }
        return Intention.Noter(reste, null)
    }

    private val REFERENCE = Regex("^(?:ça|ca|ceci|cela|tout ça|tout ca|le tout|cette r[ée]ponse|ta r[ée]ponse|ce que tu (?:viens de (?:dire|[ée]crire)|as dit))[ .!]*$", RegexOption.IGNORE_CASE)

    private fun sujetDemande(s: String): String = s.trim()
        .replace(Regex("^(?:sur|du|de la|de l'|des|de|d'|pour|concernant|à propos d[eu']?|a propos d[eu']?|au sujet d[eu']?)\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(?:le |la |les |l'|mon |ma |mes )", RegexOption.IGNORE_CASE), "")
        .trim().trimEnd('?', '!', '.', ' ')

    /** Rubrique d'une note : sujet reconnu le plus tôt dans le texte (ou le sujet donné), sinon le début, sinon « Divers ». */
    internal fun sujetDe(texte: String, explicite: String? = null): String {
        reconnu(explicite.orEmpty())?.let { return it }
        if (!explicite.isNullOrBlank()) return propre(explicite)
        reconnu(texte.take(200))?.let { return it }
        val tete = texte.substringBefore(':', "").trim()
        if (tete.isNotBlank() && tete.split(Regex("\\s+")).size <= 6) return propre(tete)
        return "Divers"
    }

    private fun reconnu(texte: String): String? {
        val n = normaliser(texte)
        if (n.isBlank()) return null
        return SUJETS.mapNotNull { (r, nom) -> r.find(n)?.let { it.range.first to nom } }.minByOrNull { it.first }?.second
    }

    private fun propre(s: String): String = s.trim()
        .replace(Regex("^(?:pour|sur|concernant|le |la |les |l'|du |de la |des |mon |ma |mes )\\s*", RegexOption.IGNORE_CASE), "")
        .trim().take(40).replaceFirstChar { it.uppercase() }.ifBlank { "Divers" }

    // ------------------------------------------------------------------ stockage

    private fun fichier(app: JarvisApp) = File(app.filesDir, "carnet.json")

    @Synchronized fun toutes(app: JarvisApp): List<Note> {
        val f = fichier(app)
        if (!f.exists()) return emptyList()
        return runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Note(it.optString("s"), it.optString("t"), it.optLong("ts")) }
        }.getOrDefault(emptyList())
    }

    @Synchronized private fun enregistrer(app: JarvisApp, l: List<Note>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("s", it.sujet).put("t", it.texte).put("ts", it.ts)) }
        fichier(app).writeText(a.toString())
    }

    /** Ajoute une note ; renvoie (rubrique, nombre de notes de la rubrique). */
    @Synchronized fun ajouter(app: JarvisApp, texte: String, sujet: String? = null): Pair<String, Int> {
        val l = toutes(app).toMutableList()
        val voulu = sujetDe(texte, sujet)
        // Rubrique existante de même nom (accents/majuscules ignorés) : on la réutilise telle quelle.
        val rubrique = l.map { it.sujet }.distinct().firstOrNull { normaliser(it) == normaliser(voulu) } ?: voulu
        l += Note(rubrique, texte.trim(), System.currentTimeMillis())
        enregistrer(app, l)
        copierDansObsidian(app, rubrique, l)
        return rubrique to l.count { it.sujet == rubrique }
    }

    @Synchronized fun supprimerDerniere(app: JarvisApp): Note? {
        val l = toutes(app).toMutableList()
        val n = l.removeLastOrNull() ?: return null
        enregistrer(app, l)
        copierDansObsidian(app, n.sujet, l)
        return n
    }

    /** Supprime les notes d'une rubrique (toutes, ou celles qui contiennent [contenant]) ; renvoie leur nombre. */
    @Synchronized fun supprimer(app: JarvisApp, sujet: String, contenant: String = ""): Int {
        val l = toutes(app)
        val r = rubrique(l, sujet) ?: return 0
        val garde = l.filterNot { it.sujet == r && (contenant.isBlank() || normaliser(it.texte).contains(normaliser(contenant))) }
        enregistrer(app, garde)
        copierDansObsidian(app, r, garde)
        return l.size - garde.size
    }

    private fun rubrique(l: List<Note>, sujet: String): String? {
        val rubriques = l.map { it.sujet }.distinct()
        val n = normaliser(sujet)
        if (n.isBlank()) return null
        val canon = reconnu(sujet)
        return rubriques.firstOrNull { normaliser(it) == n } ?: canon?.let { c -> rubriques.firstOrNull { it == c } }
            ?: rubriques.firstOrNull { normaliser(it).contains(n) || n.contains(normaliser(it)) }
    }

    private fun copierDansObsidian(app: JarvisApp, rubrique: String, l: List<Note>) {
        runCatching { app.obsidian.saveIn(DOSSIER, rubrique, markdown(rubrique, l.filter { it.sujet == rubrique })) }
    }

    private fun date(ts: Long) = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(ts))

    internal fun markdown(rubrique: String, notes: List<Note>): String = buildString {
        append("---\ntype: notes-jarvis\nsujet: \"$rubrique\"\ntags: [jarvis, notes]\n---\n\n# 📝 $rubrique\n\n")
        append("_Notes prises par JARVIS à ta demande (« prends note… »). Elles sont relues avant chaque action sur ce sujet._\n\n")
        if (notes.isEmpty()) append("_(aucune note)_\n")
        notes.forEach { append("- **${date(it.ts)}** — ${it.texte.replace("\n", "\n  ")}\n") }
    }

    // ------------------------------------------------------------------ relecture

    /** Notes qui parlent de la demande (mots-clés + synonymes), les plus pertinentes d'abord. */
    fun pertinentes(app: JarvisApp, demande: String): List<Pair<Note, Int>> {
        val mots = NotesPertinentes.motsCles(demande)
        if (mots.isEmpty()) return emptyList()
        return toutes(app).map { it to NotesPertinentes.score(it.sujet + " " + it.texte, mots) }.filter { it.second > 0 }.sortedByDescending { it.second }
    }

    /** Entités Home Assistant citées dans les notes qui parlent de [recherche] : (entité, extrait de la note). */
    fun entitesNotees(app: JarvisApp, recherche: String): List<Pair<String, String>> {
        val mots = NotesPertinentes.motsCles(recherche)
        if (mots.isEmpty()) return emptyList()
        val sources = toutes(app).map { it.sujet + " : " + it.texte } + runCatching { app.memo.all().map { (k, v) -> "$k : $v" } }.getOrDefault(emptyList())
        return sources.filter { NotesPertinentes.score(it, mots) > 0 }
            .flatMap { s -> ENTITE.findAll(s).map { it.value to s.replace('\n', ' ').take(220) }.toList() }
            .distinctBy { it.first }.take(6)
    }

    fun lire(app: JarvisApp, sujet: String): String {
        val l = toutes(app)
        if (l.isEmpty()) return "📝 Ton carnet de notes est vide : dis « Jarvis, prends note… » pour y ajouter quelque chose."
        val rubriques = l.groupBy { it.sujet }
        if (sujet.isBlank()) return "# 📝 Carnet de notes\n\n" + rubriques.entries.sortedByDescending { e -> e.value.maxOf { it.ts } }.joinToString("\n\n") { (r, n) ->
            "**$r** (${n.size})\n" + n.takeLast(3).joinToString("\n") { "- ${date(it.ts)} — ${it.texte.take(300)}" }
        }
        val r = rubrique(l, sujet)
        if (r != null) {
            val n = rubriques.getValue(r)
            return "# 📝 $r (${n.size} note${if (n.size > 1) "s" else ""})\n\n" + n.joinToString("\n") { "- ${date(it.ts)} — ${it.texte}" }
        }
        val trouvees = pertinentes(app, sujet).take(10)
        if (trouvees.isNotEmpty()) return "# 📝 Notes qui parlent de « $sujet »\n\n" + trouvees.joinToString("\n") { (n, _) -> "- [${n.sujet}] ${date(n.ts)} — ${n.texte}" }
        return "Je n'ai aucune note sur « $sujet ». Rubriques de ton carnet : " + rubriques.keys.joinToString(", ") + "."
    }

    // ------------------------------------------------------------------ réflexe (sans IA)

    @Volatile private var ecouteJusqua = 0L
    private val ANNULE = Regex("^\\s*(?:non|annule|laisse tomber|rien|oublie|stop|c'est bon)[ .!]*$", RegexOption.IGNORE_CASE)

    /** Réponse immédiate à une demande de note (noter, relire, supprimer la dernière), ou null si ce n'en est pas une. */
    fun reflexe(app: JarvisApp, texte: String, multiEtapes: Boolean, precedent: String? = null): String? {
        val i = intention(texte)
        if (System.currentTimeMillis() < ecouteJusqua && i == null) {
            ecouteJusqua = 0L
            if (ANNULE.matches(texte)) return "D'accord, je ne note rien."
            return noter(app, texte.trim(), null)
        }
        if (multiEtapes && i !is Intention.Lire) return null
        return when (i) {
            is Intention.Noter -> noter(app, i.texte, i.sujet)
            is Intention.Lire -> lire(app, i.sujet)
            Intention.Ecoute -> { ecouteJusqua = System.currentTimeMillis() + 3 * 60_000L; "📝 Je t'écoute : dis-moi ce que je dois noter." }
            Intention.NoterPrecedent -> precedent?.trim()?.takeIf { it.isNotBlank() }?.let { noter(app, it.take(3000), null) }
                ?: run { ecouteJusqua = System.currentTimeMillis() + 3 * 60_000L; "📝 Je t'écoute : dis-moi ce que je dois noter." }
            Intention.SupprimerDerniere -> supprimerDerniere(app)?.let { "🗑️ Note supprimée de « ${it.sujet} » : ${it.texte.take(200)}" } ?: "Ton carnet de notes est déjà vide."
            null -> null
        }
    }

    fun noter(app: JarvisApp, texte: String, sujet: String?): String {
        val (rubrique, nb) = ajouter(app, texte, sujet)
        // Faits durables (adresse du domicile, surnoms…) rangés à part : toujours connus de Jarvis et d'Hermès, et
        // appliqués par les outils (Discord, itinéraires, contacts…). L'IA complète en arrière-plan.
        val retenus = runCatching { Faits.apprendre(app, texte) }.getOrDefault(emptyList())
        val entites = ENTITE.findAll(texte).map { it.value }.distinct().toList()
        val obsidian = app.store.obsidianUri.value.isNotBlank()
        return "📝 Noté dans ton carnet, rubrique « $rubrique » ($nb note${if (nb > 1) "s" else ""}) :\n> ${texte.take(600).replace("\n", "\n> ")}" +
            (if (retenus.isNotEmpty()) "\n\nRetenu dans ma mémoire fiable :\n" + retenus.joinToString("\n") { "- $it" } else "") +
            (if (entites.isNotEmpty()) "\n\nJe m'en servirai pour piloter cet appareil (${entites.joinToString(", ") { "`$it`" }})." else "") +
            (if (obsidian) "\n_Copie dans Obsidian : $DOSSIER/$rubrique.md_" else "")
    }
}
