package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser

/**
 * Domaines des consignes (maison, Plex, serveur, téléphone, musique…) : sert à classer une consigne dans sa rubrique,
 * à savoir si une consigne enregistrée concerne la demande en cours, et à toujours la remettre dans le prompt.
 */
object DomainesConsignes {
    /** Domaine → mots qui le désignent (texte normalisé). L'ordre départage deux domaines au même endroit. */
    internal val DOMAINES: Map<String, Regex> = linkedMapOf(
        "musique" to Regex("\\b(musiques?|morceaux?|titres?|chansons?|artistes?|albums?|playlists?|radio|youtube music|yt music|morphe|spotify|deezer)\\b"),
        "localisation" to Regex("\\b(ou est|ou se trouve|positions?|localisations?|localiser|localise|adresse exacte|gps)\\b"),
        "maison" to Regex("\\b(poeles?|poiles?|poils?|granules?|chauffages?|radiateurs?|thermostats?|climatisation|clim|lumieres?|lampes?|ampoules?|volets?|stores?|prises?|" +
            "home assistant|domotique|alarmes?|capteurs?|portail|garage|aspirateur|vmc|chaudiere|chauffe eau|maison connectee)\\b"),
        "plex" to Regex("\\b(plex|films?|series?|episodes?|saisons?|tautulli|overseerr|radarr|sonarr|jellyfin)\\b"),
        "serveur" to Regex("\\b(serveurs?|ssh|vps|debian|docker|conteneurs?|disques?|cpu|uptime|systemd|nas|proxmox|termux)\\b"),
        "telephone" to Regex("\\b(telephone|batterie|reseau mobile|reseau|apn|signal|wi ?fi|bluetooth|luminosite|volume|sonnerie|vibreur|stockage|applications?|appels?)\\b"),
        "agenda" to Regex("\\b(agenda|planning|rendez vous|rdv|calendrier|evenements?|reunions?)\\b"),
        "briefing" to Regex("\\b(briefing|point du (jour|matin)|resume du matin)\\b"),
        "mails" to Regex("\\b(mails?|e mails?|courriels?|gmail)\\b"),
        "sms" to Regex("\\b(sms|textos?|whatsapp)\\b"),
        "contacts" to Regex("\\b(contacts?|fiches? contacts?)\\b"),
        "meteo" to Regex("\\b(meteo|previsions?|pluie)\\b"),
        "navigation" to Regex("\\b(navigation|itineraires?|trajets?|guidage|waze|google maps)\\b"),
        "notes" to Regex("\\b(obsidian|coffre|notes?)\\b"),
        "societe" to Regex("\\b(societe|devis|factures?|clients?|chantiers?|paie)\\b"),
    )

    /** Domaine principal d'un texte normalisé (le mot de domaine le plus tôt dans la phrase), ou null. */
    fun domaine(n: String): String? = DOMAINES.mapNotNull { (d, r) -> r.find(n)?.let { d to it.range.first } }.minByOrNull { it.second }?.first

    /** Domaine d'une rubrique de la mémoire (« maison », « poêles », « home assistant », « films »…), ou null. */
    fun deRubrique(rubrique: String): String? {
        val r = normaliser(rubrique)
        if (r in DOMAINES) return r
        if (r in setOf("ha", "domotique", "chauffage")) return "maison"
        if (r in setOf("phone", "tel", "mobile")) return "telephone"
        return domaine(r)
    }

    /** Mots de sujet présents (mis au singulier) : « températures des poêles » → {poele}. */
    internal fun mots(n: String): Set<String> = DOMAINES.values.flatMap { r -> r.findAll(n).map { singulier(it.value) }.toList() }.toSet()

    private fun singulier(m: String) = m.split(' ').joinToString(" ") { w -> if (w.length > 3 && (w.endsWith("s") || w.endsWith("x"))) w.dropLast(1) else w }
}

/**
 * Tes consignes de RESTITUTION (« quand tu mets une musique, donne-moi seulement le titre et l'artiste », « pour la
 * position d'une personne, seulement l'adresse exacte ») deviennent des règles de fonctionnement, y compris pour les
 * réponses DIRECTES — réflexes et résultats d'outils rendus sans passer par l'IA, qui jusque-là ne les voyaient jamais.
 *
 * Une réponse directe concernée par une consigne de forme enregistrée (mémoire JARVIS) est remise en forme par un appel
 * IA court et strict : retirer / reformuler seulement, ne rien ajouter. Réponse déjà brève, aucune consigne qui la
 * concerne, ou échec : la réponse est rendue telle quelle.
 */
object Restitution {
    /** Rubriques de la mémoire dont les consignes s'appliquent toujours (jamais seulement quand un mot les rappelle). */
    val RUBRIQUES_PERMANENTES = setOf("general", "preferences", "preference", "habitudes", "profil", "style", "identite", "ton", "langue",
        "reponses", "reponse", "restitution", "format", "presentation", "musique", "localisation", "position")

    /** Une consigne de FORME (ce qu'il faut dire, taire ou comment le présenter), pas un simple fait. */
    internal val FORME = Regex("\\b(seulement|uniquement|juste|rien d autre|pas d autres?|que (le|la|les|l|son|sa|ses)|ne (me )?(donne|dis|mets|affiche)s? (que|pas|plus|jamais)|" +
        "sans (le|la|les|l|aucune?|autres?|nom|details?|commentaires?)|ne (pas|jamais|plus) (donner|dire|afficher|preciser|ajouter|mentionner)|n ajoute|minimum|le plus court|bref|breve|concis|" +
        "tableaux?|colonnes?|sous (la )?forme|en liste|liste a puces|format|presente\\w*|presentation|en gras|emojis?|par ordre|trie\\w*|classe\\w*|unites?|une ligne|par ligne|" +
        "arrondi\\w*|en premier|d abord|avec (la|le|les|l|son|sa|ses|leur|leurs) )\\b")

    /** Consigne de MISE EN FORME (tableau, liste, colonnes…) : la réponse peut alors être réorganisée et plus longue. */
    internal val MISE_EN_FORME = Regex("\\b(tableaux?|colonnes?|sous (la )?forme|en liste|liste a puces|par ordre|trie\\w*|classe\\w*|par ligne|en gras)\\b")

    /** Domaines où une demande parle rarement de son sujet (« mets Johnny », « où est Paul ») : reconnus plus largement. */
    private val LARGES = mapOf(
        "musique" to Regex("\\b(musique|morceau|titre|chanson|artiste|album|playlist|lecture|ecoute|joue|mets|radio|youtube music|morphe)\\b"),
        "localisation" to Regex("\\b(ou est|ou se trouve|position|localisation|localise|adresse|gps|zone|person)\\b"),
    )

    /**
     * Lignes de la mémoire qui concernent cet échange (demande + réponse). [formeSeulement] : seulement les consignes de
     * forme (pour remettre en forme une réponse directe) ; sinon toutes les consignes du sujet (pour le prompt).
     */
    internal fun concernees(memoire: Map<String, String>, demande: String, reponse: String, formeSeulement: Boolean, max: Int, vocal: Boolean? = null): List<String> {
        val contexte = normaliser("$demande ${reponse.take(600)}")
        val motsContexte = DomainesConsignes.mots(contexte)
        val mots = fr.dd.jarvis.device.NotesPertinentes.motsCles("$demande ${reponse.take(300)}")
        val out = mutableListOf<String>()
        for ((rubrique, texte) in memoire) {
            val dr = DomainesConsignes.deRubrique(rubrique)
            for (ligne in texte.lines().map { it.trim().trimStart('-', '*', '•', ' ') }.filter { it.length >= 12 }) {
                if (vocal != null && !CanalDemande.pour(ligne, vocal)) continue
                val n = normaliser(ligne)
                if (formeSeulement && !FORME.containsMatchIn(n)) continue
                val ml = DomainesConsignes.mots(n)
                val domainesLigne = ml.mapNotNull { DomainesConsignes.domaine(it) }.toSet()
                val concerne = when {
                    // Même sujet précis (« poêles » dans la consigne et dans la demande).
                    ml.any { it in motsContexte } -> true
                    // Musique, localisation : la demande ne nomme pas toujours le sujet (« mets Johnny »).
                    dr in LARGES && LARGES.getValue(dr!!).containsMatchIn(contexte) && domainesLigne.all { it == dr } -> true
                    // Consigne générale du domaine (« maison : toujours en tableau ») et demande de ce domaine.
                    dr != null && ml.isEmpty() && DomainesConsignes.DOMAINES[dr]?.containsMatchIn(contexte) == true -> true
                    // Rubrique générale : la consigne doit parler du même sujet que l'échange.
                    dr == null && domainesLigne.any { d -> LARGES[d]?.containsMatchIn(contexte) == true } -> true
                    else -> mots.count { m -> m.length >= 5 && n.contains(m) } >= 2
                }
                if (concerne) out += "[$rubrique] $ligne"
            }
        }
        return out.distinct().take(max)
    }

    /** Consignes de forme qui concernent cet échange (demande + réponse), au plus 6 lignes. */
    internal fun consignes(memoire: Map<String, String>, demande: String, reponse: String): List<String> =
        concernees(memoire, demande, reponse, formeSeulement = true, max = 6, vocal = CanalDemande.vocal)

    private val AJOUT = Regex("\\b(aussi|en plus|egalement|ajoute\\w*|rajoute\\w*|inclu\\w*|complete\\w*|accompagne\\w*|avec (la|le|les) (meteo|temperature exterieure|previsions?))\\b")
    private val RETRAIT = Regex("\\b(n ajoute|ne rien ajouter|rien d autre|sans rien)\\b")

    /**
     * Une consigne qui concerne cette demande exige-t-elle d'AJOUTER quelque chose (« donne aussi la température
     * extérieure », « inclus la consigne ») ? Alors une réponse directe (réflexe, résultat d'outil brut) ne suffit pas :
     * la demande va au cerveau, qui applique la consigne avec ses outils.
     */
    fun exigeUneSuite(memoire: Map<String, String>, demande: String): Boolean =
        concernees(memoire, demande, "", formeSeulement = false, max = 10, vocal = CanalDemande.vocal).any { l -> normaliser(l).let { AJOUT.containsMatchIn(it) && !RETRAIT.containsMatchIn(it) } }

    private val TABLEAU_MD = Regex("(?m)^\\s*\\|.*\\|\\s*$\\n^\\s*\\|?\\s*:?-{2,}")

    /** Réponse remise aux consignes ; [incomplet] : une consigne exige une information que cette réponse n'a pas. */
    data class Resultat(val texte: String, val incomplet: Boolean = false)

    /**
     * Réponse directe remise aux consignes de forme de l'utilisateur (ou telle quelle). Si une consigne demande une
     * information absente de la réponse (« avec la température extérieure »), [Resultat.incomplet] : le cerveau refait
     * la demande en appliquant la consigne avec ses outils.
     */
    suspend fun appliquer(app: JarvisApp, demande: String, reponse: String): Resultat {
        val r = reponse.trim()
        val telle = Resultat(reponse)
        // Déjà minimale (« Titre — Artiste », une adresse) : rien à faire, aucun appel.
        if (r.isBlank() || (r.length <= 90 && '\n' !in r)) return telle
        if (r.contains("jarvis-img:") || r.length > 6000) return telle
        // Consignes de JARVIS et celles notées par Hermès (la conversation passe parfois par lui).
        val memoire = runCatching { app.memo.all() }.getOrDefault(emptyMap()) +
            ("hermes" to runCatching { app.memo.entreesHermesListe().joinToString("\n") }.getOrDefault(""))
        val regles = runCatching { consignes(memoire, demande, r) }.getOrDefault(emptyList())
        if (regles.isEmpty()) return telle
        val miseEnForme = regles.any { MISE_EN_FORME.containsMatchIn(normaliser(it)) }
        // Déjà en tableau et les consignes ne demandent qu'un tableau : rien à refaire (pas d'appel, pas d'attente).
        if (TABLEAU_MD.containsMatchIn(r) && regles.all { Regex("\\btableaux?\\b").containsMatchIn(normaliser(it)) }) return telle
        val systeme = "Tu remets en forme une réponse de l'assistant JARVIS selon les CONSIGNES de l'utilisateur. Règles absolues : " +
            "applique à la lettre les consignes qui concernent cette réponse (ignore les autres) ; tu peux RETIRER, reformuler ou RÉORGANISER " +
            "(tableau Markdown avec une ligne d'en-tête et une ligne |---|, liste, ordre, colonnes demandées) — n'ajoute jamais d'information absente " +
            "de la réponse, ni commentaire ni formule de politesse ; garde exactement les faits (noms, titres, adresses, nombres, unités) ; si la réponse " +
            "signale un échec ou une erreur, garde-le. Si une consigne qui concerne cette réponse exige une information qui N'EST PAS dans la réponse " +
            "(tu ne dois pas l'inventer), réponds uniquement le mot INCOMPLET. Si aucune consigne ne s'applique, renvoie la réponse telle quelle. " +
            "Sinon, réponds uniquement avec la réponse remise en forme, en français."
        val utilisateur = "CONSIGNES :\n" + regles.joinToString("\n") { "- $it" } + "\n\nDEMANDE : $demande\n\nRÉPONSE À REMETTRE EN FORME :\n$r"
        val sortie = kotlinx.coroutines.withTimeoutOrNull(if (miseEnForme) 4_000L else 2_500L) {
            try { app.llm.groq.plain(systeme, utilisateur) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        }?.trim().orEmpty()
        if (Regex("^\\W*INCOMPLET\\W*$").matches(sortie)) {
            fr.dd.jarvis.voice.JournalVoix.noter("brain", "réponse directe incomplète pour les consignes : le cerveau la refait")
            return Resultat(reponse, incomplet = true)
        }
        // Garde-fous : une remise en forme ne vide pas la réponse ; elle ne la rallonge que pour une mise en forme (tableau…).
        if (sortie.isBlank() || sortie.length > (if (miseEnForme) r.length * 3 + 400 else r.length + 40)) return telle
        fr.dd.jarvis.voice.JournalVoix.noter("brain", "réponse directe remise aux consignes : ${regles.size} consigne(s)")
        return Resultat(sortie)
    }
}

/**
 * Consigne durable dite EXPLICITEMENT (« à l'avenir… », « quand tu mets une musique, donne-moi seulement… », « pour la
 * localisation d'une personne, seulement l'adresse », « je veux toujours les poêles en tableau ») — ou présentation
 * demandée dans une demande (« … sous forme de tableau avec la température actuelle et la consigne ») : enregistrée tout
 * de suite dans la mémoire de JARVIS, dans la rubrique de son domaine (maison, plex, serveur, téléphone…), quel que soit
 * le cerveau qui traite ensuite la phrase — elle ne dépend plus du modèle qui pense (ou non) à l'enregistrer.
 */
object ConsignesExplicites {
    data class Consigne(val rubrique: String, val texte: String, val ponctuelle: Boolean = false, val canal: String? = null)

    private val MARQUEUR = Regex("\\b(a l avenir|desormais|dorenavant|a partir de maintenant|a partir d aujourd hui|(a )?chaque fois( que| qu)?|la prochaine fois|les prochaines fois|" +
        "par defaut|tu dois|il faut que tu|" +
        "(quand|lorsque|des que|si) (je (te )?(demande|dis|parle|cherche|veux|lance)|tu (me )?(mets|lances|joues|donnes|reponds|cherches|indiques|localises|trouves|affiches|presentes|lis|envoies))|" +
        "je (veux|voudrais|prefere|aimerais|souhaite) (que (tu|quand|lorsque|pour|les|le|la)|toujours|systematiquement)|j aimerais (que|toujours)|plus jamais|" +
        "(donne|dis|mets|affiche|presente|reponds|indique|precise|ajoute|envoie|lance|joue|passe|utilise|montre|fais|lis)\\w*( moi| me| nous| les| la| le)? (toujours|systematiquement)|" +
        "(toujours|systematiquement) (me |m |les |la |le )?(donner|dire|mettre|afficher|presenter|repondre|indiquer|preciser|ajouter|utiliser|montrer|passer|faire|lire|en|sous|avec|par)|" +
        "pour (la|le|les) [a-z]+ (d une|d un|des) )\\b")
    /** « Retiens / n'oublie pas » : seulement suivi d'une vraie consigne de forme (sinon c'est un fait → memoire_faits). */
    private val MARQUEUR_RETIENS = Regex("\\b(retiens|n oublie (pas|jamais)|souviens toi|rappelle toi que|note bien)\\b")
    private val FORME = Regex("\\b(seulement|uniquement|juste|rien d autre|pas d autres?|ne (me )?(donne|dis|mets|affiche)s? (que|pas|plus|jamais)|" +
        "sans (le|la|les|l|aucune?|autres?|nom|details?|commentaires?)|n ajoute|tableaux?|colonnes?|sous (la )?forme|en liste|liste a puces|format|presente\\w*|presentation|" +
        "en gras|emojis?|par ordre|trie\\w*|classe\\w*|unites?|une ligne|par ligne|arrondi\\w*|en premier|d abord|avec (la|le|les|l|son|sa|ses|leur|leurs) |" +
        "(en |une |deux |trois |\\d )phrases?|courte?s?|plus court|detaille\\w*|plus long|longue|concis\\w*|brefs?|breve|resume\\w*|tutoie|vouvoie|langage|mots simples)\\b")
    private val FORME_FAIBLE = Regex("\\b(toujours|jamais|systematiquement|que (le|la|les|l|son|sa|ses))\\b")
    /** Présentation demandée pour une demande (« … sous forme de tableau », « en tableau avec… ») : retenue pour la suite. */
    private val FORMAT_PONCTUEL = Regex("\\b(sous (la )?forme (d un |de |d )?(tableau|liste|colonnes?)|en (un )?tableau|dans un tableau|en colonnes?|en liste a puces|tableau (avec|des|de|recapitulatif))\\b")
    /** Création d'un document (tableau Excel, PDF…) : une demande ponctuelle, pas une façon de répondre. */
    private val CREATION = Regex("\\b(cree|creer|genere|generer|fais|faire|fabrique|redige|rediger|exporte|exporter|excel|xlsx|csv|pdf|docx|word|fichier|document|feuille|tableur|sheets?)\\b")

    /** Dernière consigne enregistrée (pour « oublie cette consigne »). */
    @Volatile var derniere: Pair<String, String>? = null

    /** Consigne durable explicite (ou présentation demandée) de la phrase, sinon null. */
    fun detecter(texte: String): Consigne? {
        val t = texte.trim()
        if (t.length !in 20..400) return null
        val n = normaliser(t)
        // Une question n'est pas une consigne (« est-ce que tu peux toujours… ? »).
        val question = t.endsWith("?") && !Regex("\\b(peux tu|pourrais tu|tu peux|tu pourrais)\\b").containsMatchIn(n)
        val durable = !question && (MARQUEUR.containsMatchIn(n) && (FORME.containsMatchIn(n) || FORME_FAIBLE.containsMatchIn(n)) ||
            MARQUEUR_RETIENS.containsMatchIn(n) && FORME.containsMatchIn(n))
        val ponctuelle = !durable && FORMAT_PONCTUEL.containsMatchIn(n) && !CREATION.containsMatchIn(n) && DomainesConsignes.domaine(n) != null
        if (!durable && !ponctuelle) return null
        val rubrique = DomainesConsignes.domaine(n) ?: "reponses"
        val consigne = t.replace(Regex("^(jarvis|ok|alors|et|bon|du coup|ecoute|écoute)[ ,]+", RegexOption.IGNORE_CASE), "").trim().trimEnd('.', '!').replaceFirstChar { it.uppercase() }
        // « Quand je te parle… » / « par écrit… » : la consigne ne vaut que pour ce canal.
        val canal = CanalDemande.detecter(n)
        return Consigne(rubrique, CanalDemande.marque(canal) + if (ponctuelle) "Présentation voulue : « $consigne »" else consigne, ponctuelle, canal)
    }
}

/**
 * « Quelles sont mes consignes ? », « mes consignes pour la maison », « oublie cette consigne », « oublie la consigne sur
 * les poêles » : les consignes enregistrées se consultent et se retirent sans IA.
 */
object GestionConsignes {
    sealed class Action { data class Lister(val domaine: String?) : Action(); data class Oublier(val cible: String?) : Action() }

    private val LISTER = Regex("^(quelles? (sont|est) )?(mes|tes|les) consignes( (enregistrees|memorisees|retenues))?( (pour|sur|de|du|des|concernant) (la |le |les |l )?(.+))?$|" +
        "^(montre|affiche|liste|donne|dis|rappelle|lis)( moi)? (mes|tes|les) consignes( (enregistrees|memorisees|retenues))?( (pour|sur|de|du|des|concernant) (la |le |les |l )?(.+))?$|" +
        "^qu est ce que tu (as retenu|retiens) comme consignes?.*$")
    private val OUBLIER = Regex("^(oublie|supprime|efface|retire|annule|enleve)( moi)? (cette|la|ta|ma|les|tes|mes|derniere)( derniere)? consignes?( (sur|pour|de|du|des|concernant|qui parle de|ou) (la |le |les |l )?(.+))?$")

    fun demande(texte: String): Action? {
        val n = normaliser(texte).replace(Regex("[?!.,;:]+"), " ").replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^(jarvis|dis moi|stp|s il te plait) "), "").replace(Regex(" (stp|s il te plait|jarvis)$"), "")
        if (n.split(' ').size > 14) return null
        if (LISTER.matches(n)) {
            val sujet = Regex("\\bconsignes?( enregistrees| memorisees| retenues)? (pour|sur|concernant|de|du|des) (la |le |les |l )?(.+)$").find(n)?.groupValues?.get(4)
            return Action.Lister(sujet?.let { DomainesConsignes.deRubrique(it) ?: it })
        }
        OUBLIER.matchEntire(n)?.let { m ->
            val cible = m.groupValues[8].takeIf { it.isNotBlank() }
            return Action.Oublier(cible)
        }
        return null
    }

    /** Lignes de la mémoire à retirer pour cette cible (meilleure correspondance), parmi (rubrique, ligne). */
    internal fun choisir(consignes: List<Pair<String, String>>, cible: String): Pair<String, String>? {
        val c = normaliser(cible)
        val dom = DomainesConsignes.deRubrique(c)
        val mots = c.split(' ').filter { it.length >= 4 }.map { it.removeSuffix("s") }
        return consignes.map { (r, l) ->
            val nl = normaliser("$r $l")
            val score = mots.count { nl.contains(it) } * 2 + (if (dom != null && DomainesConsignes.deRubrique(r) == dom) 1 else 0)
            (r to l) to score
        }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    fun executer(app: JarvisApp, a: Action): String = when (a) {
        is Action.Lister -> {
            val toutes = app.memo.consignesListe().filter { (r, l) -> a.domaine == null || DomainesConsignes.deRubrique(r) == a.domaine || normaliser(l).contains(normaliser(a.domaine)) }
            if (toutes.isEmpty()) (if (a.domaine != null) "📌 Aucune consigne enregistrée pour « ${a.domaine} »." else "📌 Aucune consigne enregistrée pour l'instant.") +
                " Dis-moi par exemple « à l'avenir, présente les températures des poêles en tableau »."
            else "📌 Tes consignes" + (a.domaine?.let { " ($it)" } ?: "") + " :\n" + toutes.groupBy({ it.first }, { it.second }).entries
                .joinToString("\n") { (r, l) -> "**${r.replaceFirstChar { it.uppercase() }}**\n" + l.joinToString("\n") { "- $it" } } +
                "\n\n(« oublie la consigne sur … » pour en retirer une.)"
        }
        is Action.Oublier -> {
            val cible = if (a.cible == null) ConsignesExplicites.derniere else choisir(app.memo.consignesListe(), a.cible)
            when {
                cible == null && a.cible == null -> "Je n'ai pas enregistré de consigne récemment. « mes consignes » pour la liste, puis « oublie la consigne sur … »."
                cible == null -> "Je ne trouve pas de consigne sur « ${a.cible} ». « mes consignes » pour la liste."
                app.memo.retirerConsigne(cible.first, cible.second) -> { if (ConsignesExplicites.derniere == cible) ConsignesExplicites.derniere = null; "🗑️ Consigne oubliée : « ${cible.second} »." }
                else -> "Cette consigne n'est déjà plus enregistrée."
            }
        }
    }
}

/**
 * Canal de la demande en cours : VOCAL (dictée, réponse lue à voix haute — micro, mot de réveil, voiture) ou ÉCRIT (tapée
 * dans le chat). Une consigne peut ne valoir que pour l'un (« (vocal) réponds en une phrase », « (écrit) fais des
 * tableaux ») : elle n'est alors rappelée et appliquée que sur ce canal.
 */
object CanalDemande {
    @Volatile var vocal = false
    const val VOCAL = "vocal"
    const val ECRIT = "écrit"

    private val TAG = Regex("^\\s*[-*•]?\\s*\\((vocal|écrit|ecrit)\\)\\s*", RegexOption.IGNORE_CASE)
    private val VOCAL_RE = Regex("\\b(a l oral|oralement|en vocal|vocalement|par la voix|a voix haute|en audio|quand je (te )?parle|quand tu (me )?parles|" +
        "quand je (te )?(dis|demande) (quelque chose |un truc )?(a l oral|a voix haute|en vocal|par la voix)|reponses? vocales?|en conversation vocale|" +
        "quand tu reponds (a l oral|a voix haute|en vocal)|dans la voiture|en voiture|quand je conduis|messages? vocaux)\\b")
    private val ECRIT_RE = Regex("\\b(par ecrit|a l ecrit|en texte|par texte|en ecrivant|quand je (t )?ecris|quand je tape|quand j ecris|dans le chat|par messages? texte|" +
        "messages? (texte|ecrits?)|reponses? ecrites?|quand tu (m )?ecris|a l ecran|sur l ecran|quand je t envoie un message|quand je tape au clavier)\\b")

    /** Canal visé par une consigne (texte normalisé), ou null s'il vaut pour les deux. */
    fun detecter(n: String): String? {
        val v = VOCAL_RE.containsMatchIn(n); val e = ECRIT_RE.containsMatchIn(n)
        return when { v && !e -> VOCAL; e && !v -> ECRIT; else -> null }
    }

    /** Canal d'une ligne enregistrée (« (vocal) … »), ou null. */
    fun de(ligne: String): String? = TAG.find(ligne)?.groupValues?.get(1)?.lowercase()?.let { if (it == "ecrit") ECRIT else it }

    /** La ligne s'applique-t-elle à ce canal ? (sans marque : aux deux) */
    fun pour(ligne: String, vocal: Boolean): Boolean = when (de(ligne)) { null -> true; VOCAL -> vocal; else -> !vocal }

    /** Lignes d'une rubrique valables pour ce canal. */
    fun filtrer(texte: String, vocal: Boolean): String = texte.lines().filter { pour(it, vocal) }.joinToString("\n").trim()

    fun marque(canal: String?) = when (canal) { VOCAL -> "(vocal) "; ECRIT -> "(écrit) "; else -> "" }

    /** Rappel du canal pour le modèle (fin du prompt). */
    fun ligne(v: Boolean = vocal): String = "\n\nCANAL DE CETTE DEMANDE : " + if (v)
        "VOCAL (dictée à la voix ; ta réponse est lue à voix haute) — applique les consignes générales et celles marquées (vocal), jamais celles marquées (écrit)."
        else "ÉCRIT (tapée au clavier ; ta réponse est lue à l'écran) — applique les consignes générales et celles marquées (écrit), jamais celles marquées (vocal)."
}
