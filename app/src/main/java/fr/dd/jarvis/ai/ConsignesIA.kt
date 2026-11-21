package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONObject

/**
 * Filet de sécurité des consignes : ce que les règles fixes de [ConsignesExplicites] ne reconnaissent pas (une
 * formulation libre, tapée ou dictée, une CORRECTION comme « non, je t'avais demandé un tableau ») est soumis à une
 * analyse IA courte, EN ARRIÈRE-PLAN (la réponse n'attend pas). Une consigne trouvée est reformulée en règle générale
 * complète (« Pour la température des poêles : toujours un tableau avec… ») et enregistrée dans la rubrique de son
 * domaine, comme les autres : elle est ensuite rappelée à chaque demande concernée.
 */
object ConsignesIA {
    /** Indices bon marché qu'un message PEUT contenir une consigne ; sans eux, aucun appel IA. */
    private val INDICE = Regex("\\b(tableaux?|colonnes?|sous (la )?forme|en liste|liste a puces|format|presentation|en gras|emojis?|par ordre|trie|classe|arrondi\\w*|unites?|" +
        "toujours|jamais|systematiquement|a l avenir|desormais|dorenavant|la prochaine fois|les prochaines fois|par defaut|chaque fois|" +
        "retiens|n oublie|souviens toi|rappelle toi|je (veux|voudrais|prefere|aimerais|souhaite)|j aimerais|j avais (dit|demande)|je t (avais|ai) (dit|demande)|" +
        "je te l (avais|ai)|deja (dit|demande)|combien de fois|comme je (t ai|te l ai|l ai|t avais|te l avais) (dit|demande)|au lieu de|plutot|pas comme ca|" +
        "seulement|uniquement|rien d autre|n ajoute|ne (me )?(donne|dis|mets|affiche)s? (que|pas|plus|jamais)|" +
        "quand je (te )?(demande|dis|parle)|lorsque je|si je (te )?(demande|dis)|tutoie|vouvoie|appelle moi)\\b")

    data class Extraite(val rubrique: String, val texte: String)

    /** Le message mérite-t-il une analyse (indice de consigne, longueur raisonnable, pas une commande de gestion) ? */
    fun candidat(texte: String): Boolean {
        val t = texte.trim()
        if (t.length !in 15..600) return false
        val n = normaliser(t)
        return INDICE.containsMatchIn(n) && GestionConsignes.demande(t) == null
    }

    private const val SYSTEME = "Tu analyses UN message de l'utilisateur de l'assistant JARVIS pour repérer une CONSIGNE DURABLE : une règle que " +
        "JARVIS devra appliquer AUX PROCHAINES demandes du même type. Comptent : la présentation (tableau et ses colonnes, liste, ordre, longueur, " +
        "unités, emojis), le contenu à inclure ou à exclure, la façon d'agir (application, appareil, pièce, réglage ou paramètre par défaut), le ton ; " +
        "une présentation précise demandée pour un type d'information (« donne-moi X sous forme de tableau avec A et B ») ; une CORRECTION d'une " +
        "réponse précédente (« non, je t'avais demandé un tableau », « tu as oublié la consigne », « pas comme ça, je veux… ») — formule alors la règle " +
        "à partir de l'échange précédent. Ne comptent PAS : une demande ponctuelle sans règle (« allume la lumière », « mets Johnny »), une simple " +
        "question, un fait sur l'utilisateur (adresse, nom, surnom), une donnée sensible (mot de passe, santé, banque). " +
        "Réponds UNIQUEMENT en JSON, sans texte autour : {\"consigne\":false} ou {\"consigne\":true,\"domaine\":\"maison|plex|serveur|telephone|" +
        "musique|localisation|agenda|briefing|mails|sms|contacts|meteo|navigation|notes|societe|reponses\",\"canal\":\"vocal|ecrit|tous\",\"regle\":\"la règle, générale et complète, " +
        "en français, avec les mots précis de l'utilisateur : le sujet concerné puis ce qu'il faut faire (ex. « Pour la température des poêles : " +
        "toujours un tableau avec la température actuelle et la température de consigne, pour tous les poêles. »)\"}. canal = vocal si la règle ne vaut " +
        "que quand il parle à JARVIS à la voix (« à l'oral », « quand je te parle », « en voiture »), ecrit si elle ne vaut que par écrit (« par écrit », " +
        "« quand je t'écris », « dans le chat »), sinon tous."

    /** Analyse IA (≈ 1 s) ; null si aucune consigne, réponse invalide ou IA indisponible. */
    suspend fun extraire(app: JarvisApp, texte: String, demandePrecedente: String?, reponsePrecedente: String?): Extraite? {
        val contexte = if (demandePrecedente.isNullOrBlank() && reponsePrecedente.isNullOrBlank()) "" else
            "ÉCHANGE PRÉCÉDENT (contexte) :\nDemande : ${demandePrecedente.orEmpty().take(400)}\nRéponse de JARVIS : ${reponsePrecedente.orEmpty().take(700)}\n\n"
        val brut = kotlinx.coroutines.withTimeoutOrNull(8_000) {
            try { app.llm.groq.plain(SYSTEME, contexte + "MESSAGE À ANALYSER : " + texte.trim()) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        } ?: return null
        return lire(brut)
    }

    /** Réponse JSON de l'IA → consigne validée (domaine connu, règle de longueur raisonnable), ou null. */
    internal fun lire(brut: String): Extraite? {
        val json = brut.substringAfter('{', "").substringBeforeLast('}', "").takeIf { it.isNotBlank() }?.let { "{$it}" } ?: return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!o.optBoolean("consigne")) return null
        val regle = o.optString("regle").trim().trim('"', '«', '»', ' ').trimEnd('.').takeIf { it.length in 12..350 } ?: return null
        val n = normaliser(regle)
        if (Regex("\\b(mot de passe|password|code pin|carte bancaire|iban)\\b").containsMatchIn(n)) return null
        val d = normaliser(o.optString("domaine"))
        val rubrique = when {
            d in DomainesConsignes.DOMAINES -> d
            d == "reponses" -> DomainesConsignes.domaine(n) ?: "reponses"
            else -> DomainesConsignes.domaine(n) ?: "reponses"
        }
        val canal = when (normaliser(o.optString("canal"))) { "vocal", "oral", "voix" -> CanalDemande.VOCAL; "ecrit", "texte" -> CanalDemande.ECRIT; else -> CanalDemande.detecter(n) }
        return Extraite(rubrique, CanalDemande.marque(canal) + regle.replaceFirstChar { it.uppercase() })
    }
}
