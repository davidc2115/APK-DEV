package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * « Toujours autoriser » : une action confirmée une fois avec ce choix (par type d'outil : commande SSH, envoi de SMS,
 * appel…) ne redemande plus de confirmation. Jamais pour une suppression, un effacement définitif, un déverrouillage ou
 * un paiement : ceux-là restent confirmés à chaque fois. Révocable dans Réglages › Contrôle du téléphone.
 */
object Autorisations {
    /** Outil en cours d'exécution (porté par la coroutine de l'appel), pour savoir QUOI autoriser. */
    class OutilEnCours(val nom: String, val args: String = "") : AbstractCoroutineContextElement(Cle) {
        companion object Cle : CoroutineContext.Key<OutilEnCours>
    }

    /** Arguments mis en forme stable (clés triées), pour reconnaître exactement la même commande. */
    internal fun canonique(v: Any?): String = when (v) {
        is org.json.JSONObject -> v.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { k -> org.json.JSONObject.quote(k) + ":" + canonique(v.opt(k)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonique(v.opt(it)) }
        is String -> org.json.JSONObject.quote(v.trim())
        null, org.json.JSONObject.NULL -> "null"
        else -> v.toString()
    }

    /** Empreinte d'une commande : outil + arguments exacts. */
    internal fun empreinte(outil: String, args: String): String {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest((outil + "|" + args).toByteArray()).joinToString("") { "%02x".format(it) }
        return outil + "#" + h.take(20)
    }

    /** Jamais autorisé pour toujours, même pour une seule commande précise : déverrouillage, paiement, secrets, sécurité. */
    private val CRITIQUE = Regex(
        "d[ée]verrouill|d[ée]sactiv\\w* (le |la |l.)?(syst[èe]me|alarme|s[ée]curit|anti.?vol)|paiement|virement|payer|mot de passe|cl[ée] api|jeton|token|r[ée]initialis|formater",
        RegexOption.IGNORE_CASE
    )

    /** Question affichée → (empreinte, description) de la commande en cours (« Toujours pour cette commande »). */
    private val commandeEnAttente = ConcurrentHashMap<String, Pair<String, String>>()

    private val JAMAIS = Regex(
        "supprim|d[ée]finitiv|irr[ée]versibl|effac|r[ée]initialis|formater|d[ée]verrouill|d[ée]sactiv\\w* (le |la |l.)?(syst[èe]me|alarme|s[ée]curit)|" +
            "paiement|virement|payer|mot de passe|cl[ée] api|grand m[ée]nage|tous les contacts", RegexOption.IGNORE_CASE
    )

    /** Question affichée → clé d'autorisation (pour le bouton « Toujours autoriser » de l'écran de confirmation). */
    private val enAttente = ConcurrentHashMap<String, String>()

    private val LIBELLES = mapOf(
        "ssh_commande" to "Commandes sur le serveur (SSH)", "ssh_script" to "Scripts sur le serveur (SSH)", "ssh_fichier" to "Fichiers du serveur (SSH)",
        "sms_envoyer" to "Envoi de SMS", "sms_repondre" to "Réponses aux SMS", "appeler" to "Appels téléphoniques", "mail_envoyer" to "Envoi d'emails",
        "document_email" to "Envoi des devis et factures par email", "ha_service" to "Pilotage de Home Assistant", "phone_action" to "Actions sur le téléphone",
        "gerer_extension_hermes" to "Extensions d'Hermès", "freebox" to "Réglages de la Freebox", "appareil_acces_distant" to "Accès à distance des appareils"
    )

    fun libelle(cle: String) = LIBELLES[cle] ?: cle.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** Clé pour cet outil et cette question, ou null si l'action ne peut pas être autorisée une fois pour toutes. */
    fun cle(outil: String?, question: String): String? =
        outil?.takeIf { it.isNotBlank() && !JAMAIS.containsMatchIn(question) && !JAMAIS.containsMatchIn(it) }

    fun permanentes(app: JarvisApp): List<String> = runCatching {
        JSONArray(app.store.autorisationsPermanentes.value).let { a -> (0 until a.length()).map { a.getString(it) } }
    }.getOrDefault(emptyList())

    fun estPermanente(app: JarvisApp, cle: String?) = cle != null && cle in permanentes(app)

    fun autoriser(app: JarvisApp, cle: String) {
        if (cle in permanentes(app)) return
        app.store.autorisationsPermanentes.set(JSONArray(permanentes(app) + cle).toString())
    }

    fun retirer(app: JarvisApp, cle: String) = app.store.autorisationsPermanentes.set(JSONArray(permanentes(app) - cle).toString())

    /** Clé en attente pour la question affichée (null : pas de « Toujours autoriser » pour celle-ci). */
    fun clePour(question: String): String? = enAttente[question]

    /** L'utilisateur a choisi « Toujours autoriser » pour la question affichée. */
    fun toujours(app: JarvisApp, question: String): Boolean {
        val c = enAttente[question] ?: return false
        autoriser(app, c)
        return true
    }

    // ------------------------------------------------------------------ « Toujours pour cette commande »

    /** Commandes précises autorisées : (empreinte, description, date). */
    fun commandes(app: JarvisApp): List<Triple<String, String, Long>> = runCatching {
        JSONArray(app.store.autorisationsCommandes.value).let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Triple(it.optString("cle"), it.optString("libelle"), it.optLong("le")) }
        }
    }.getOrDefault(emptyList())

    private fun commandeAutorisee(app: JarvisApp, empreinte: String?) = empreinte != null && commandes(app).any { it.first == empreinte }

    fun retirerCommande(app: JarvisApp, empreinte: String) {
        app.store.autorisationsCommandes.set(JSONArray(commandes(app).filter { it.first != empreinte }.map {
            org.json.JSONObject().put("cle", it.first).put("libelle", it.second).put("le", it.third) }).toString())
    }

    /** La question affichée peut-elle recevoir « Toujours pour cette commande » ? */
    fun commandePossible(question: String): Boolean = commandeEnAttente.containsKey(question)

    /** L'utilisateur a choisi « Toujours pour cette commande » : la même commande (même outil, mêmes arguments) ne redemandera plus. */
    fun toujoursCommande(app: JarvisApp, question: String): Boolean {
        val (e, libelle) = commandeEnAttente[question] ?: return false
        if (commandeAutorisee(app, e)) return true
        val l = commandes(app).map { org.json.JSONObject().put("cle", it.first).put("libelle", it.second).put("le", it.third) } +
            org.json.JSONObject().put("cle", e).put("libelle", libelle).put("le", System.currentTimeMillis())
        app.store.autorisationsCommandes.set(JSONArray(l.takeLast(200)).toString())
        return true
    }

    /** Description courte d'une commande pour la liste des autorisations (outil + question, sans secret). */
    private fun decrire(outil: String, question: String): String =
        libelle(outil) + " — " + question.replace(Regex("\\s+"), " ").take(140)

    /**
     * Confirmation enveloppée : type d'action ou commande précise déjà autorisés pour toujours → oui sans rien demander ;
     * sinon la question est posée avec les options « Toujours » (type d'action) et « Toujours pour cette commande ».
     */
    suspend fun confirmer(app: JarvisApp, outil: OutilEnCours?, question: String, demander: suspend () -> Boolean): Boolean {
        val c = cle(outil?.nom, question)
        if (estPermanente(app, c)) return true
        val e = outil?.takeIf { it.nom.isNotBlank() && !CRITIQUE.containsMatchIn(question) && !CRITIQUE.containsMatchIn(it.nom) }?.let { empreinte(it.nom, it.args) }
        if (commandeAutorisee(app, e)) return true
        if (c != null) enAttente[question] = c
        if (e != null) commandeEnAttente[question] = e to decrire(outil!!.nom, question)
        return try { demander() } finally { if (c != null) enAttente.remove(question); if (e != null) commandeEnAttente.remove(question) }
    }

    /** Ancienne signature (nom d'outil seul). */
    suspend fun confirmer(app: JarvisApp, outil: String?, question: String, demander: suspend () -> Boolean): Boolean =
        confirmer(app, outil?.let { OutilEnCours(it) }, question, demander)
}
