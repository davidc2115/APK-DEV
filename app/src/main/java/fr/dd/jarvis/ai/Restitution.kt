package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser

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
        "reponses", "reponse", "restitution", "format", "musique", "localisation", "position")

    /** Une consigne de FORME (ce qu'il faut dire ou taire), pas un simple fait. */
    private val FORME = Regex("\\b(seulement|uniquement|juste|rien d autre|pas d autres?|que (le|la|les|l|son|sa|ses)|ne (me )?(donne|dis|mets|affiche)s? (que|pas|plus|jamais)|sans (le|la|les|l|aucune?|autres?|nom|details?|commentaires?)|ne (pas|jamais|plus) (donner|dire|afficher|preciser|ajouter|mentionner)|n ajoute|minimum|le plus court|bref|breve|concis)\\b")

    private val SUJETS = mapOf(
        "musique" to Regex("\\b(musique|morceau|titre|chanson|artiste|album|playlist|lecture|ecoute|joue|mets|radio|youtube music|morphe)\\b"),
        "localisation" to Regex("\\b(ou est|ou se trouve|position|localisation|localise|adresse|gps|zone|person)\\b"),
    )

    /** Consignes de forme qui concernent cet échange (demande + réponse), au plus 6 lignes. */
    internal fun consignes(memoire: Map<String, String>, demande: String, reponse: String): List<String> {
        val contexte = normaliser("$demande ${reponse.take(600)}")
        val mots = fr.dd.jarvis.device.NotesPertinentes.motsCles("$demande ${reponse.take(300)}")
        val out = mutableListOf<String>()
        for ((rubrique, texte) in memoire) {
            val r = normaliser(rubrique)
            val sujetRubrique = SUJETS[r] ?: SUJETS.entries.firstOrNull { r.startsWith(it.key.take(5)) }?.value
            for (ligne in texte.lines().map { it.trim().trimStart('-', '*', '•', ' ') }.filter { it.length >= 12 }) {
                val n = normaliser(ligne)
                if (!FORME.containsMatchIn(n)) continue
                val concerne = when {
                    sujetRubrique != null -> sujetRubrique.containsMatchIn(contexte)
                    // Consigne d'une rubrique générale : elle doit parler du même sujet que l'échange.
                    else -> SUJETS.values.any { it.containsMatchIn(n) && it.containsMatchIn(contexte) } ||
                        mots.count { m -> m.length >= 5 && n.contains(m) } >= 2
                }
                if (concerne) out += "[$rubrique] $ligne"
            }
        }
        return out.distinct().take(6)
    }

    /** Réponse directe remise aux consignes de forme de l'utilisateur (ou telle quelle). */
    suspend fun appliquer(app: JarvisApp, demande: String, reponse: String): String {
        val r = reponse.trim()
        // Déjà minimale (« Titre — Artiste », une adresse) : rien à faire, aucun appel.
        if (r.isBlank() || (r.length <= 90 && '\n' !in r)) return reponse
        if (r.contains("jarvis-img:") || r.length > 6000) return reponse
        // Consignes de JARVIS et celles notées par Hermès (la conversation passe parfois par lui).
        val memoire = runCatching { app.memo.all() }.getOrDefault(emptyMap()) +
            ("hermes" to runCatching { app.memo.entreesHermesListe().joinToString("\n") }.getOrDefault(""))
        val regles = runCatching { consignes(memoire, demande, r) }.getOrDefault(emptyList())
        if (regles.isEmpty()) return reponse
        val systeme = "Tu remets en forme une réponse de l'assistant JARVIS selon les CONSIGNES de l'utilisateur. Règles absolues : " +
            "applique les consignes à la lettre ; tu peux seulement RETIRER ou reformuler — n'ajoute jamais d'information, de commentaire ni de " +
            "formule de politesse ; garde exactement les faits (noms, titres, adresses, nombres) ; si la réponse signale un échec ou une erreur, " +
            "garde-le. Réponds uniquement avec la réponse remise en forme, en français."
        val utilisateur = "CONSIGNES :\n" + regles.joinToString("\n") { "- $it" } + "\n\nDEMANDE : $demande\n\nRÉPONSE À REMETTRE EN FORME :\n$r"
        val sortie = kotlinx.coroutines.withTimeoutOrNull(4_000) {
            try { app.llm.groq.plain(systeme, utilisateur) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        }?.trim().orEmpty()
        // Garde-fous : une remise en forme ne rallonge pas la réponse et ne la vide pas.
        if (sortie.isBlank() || sortie.length > r.length + 40) return reponse
        fr.dd.jarvis.voice.JournalVoix.noter("brain", "réponse directe remise aux consignes : ${regles.size} consigne(s)")
        return sortie
    }
}

/**
 * Consigne durable dite EXPLICITEMENT (« à l'avenir… », « quand tu mets une musique, donne-moi seulement… », « pour la
 * localisation d'une personne, seulement l'adresse ») : enregistrée tout de suite dans la mémoire de JARVIS, quel que
 * soit le cerveau qui traite ensuite la phrase — elle ne dépend plus du modèle qui pense (ou non) à l'enregistrer.
 */
object ConsignesExplicites {
    private val MARQUEUR = Regex("\\b(a l avenir|desormais|dorenavant|a partir de maintenant|a partir d aujourd hui|(a )?chaque fois que|" +
        "(quand|lorsque|des que|si) (je (te )?(demande|dis|parle|cherche)|tu (mets|lances|joues|donnes|reponds|cherches|indiques|localises|trouves))|" +
        "je (veux|voudrais|prefere|aimerais|souhaite) que (tu|quand|lorsque|pour)|plus jamais|" +
        "pour (la|le|les) [a-z]+ (d une|d un|des) )\\b")
    private val FORME = Regex("\\b(seulement|uniquement|juste|rien d autre|pas d autres?|que (le|la|les|l|son|sa|ses)|ne (me )?(donne|dis|mets|affiche)s? (que|pas|plus|jamais)|sans (le|la|les|l|aucune?|autres?|nom|details?|commentaires?)|n ajoute|toujours|jamais)\\b")
    private val MUSIQUE = Regex("\\b(musique|morceau|titre|chanson|artiste|playlist|album)\\b")
    private val LOCALISATION = Regex("\\b(localisation|localise|position|ou est|ou se trouve|adresse exacte|gps)\\b")

    /** (rubrique, consigne) si la phrase est une consigne durable explicite, sinon null. */
    fun detecter(texte: String): Pair<String, String>? {
        val t = texte.trim()
        if (t.length !in 25..400) return null
        val n = normaliser(t)
        if (!MARQUEUR.containsMatchIn(n) || !FORME.containsMatchIn(n)) return null
        // Une question n'est pas une consigne (« est-ce que tu peux toujours… ? »).
        if (t.endsWith("?") && !Regex("\\b(peux tu|pourrais tu)\\b").containsMatchIn(n)) return null
        val rubrique = when {
            MUSIQUE.containsMatchIn(n) -> "musique"
            LOCALISATION.containsMatchIn(n) -> "localisation"
            else -> "reponses"
        }
        val consigne = t.replace(Regex("^(jarvis|ok|alors|et)[ ,]+", RegexOption.IGNORE_CASE), "").trim().trimEnd('.')
        return rubrique to consigne.replaceFirstChar { it.uppercase() }
    }
}
