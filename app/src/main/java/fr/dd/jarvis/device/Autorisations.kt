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
    class OutilEnCours(val nom: String) : AbstractCoroutineContextElement(Cle) {
        companion object Cle : CoroutineContext.Key<OutilEnCours>
    }

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

    /** Confirmation enveloppée : déjà autorisée pour toujours → oui sans rien demander ; sinon question posée avec l'option. */
    suspend fun confirmer(app: JarvisApp, outil: String?, question: String, demander: suspend () -> Boolean): Boolean {
        val c = cle(outil, question)
        if (estPermanente(app, c)) return true
        if (c != null) enAttente[question] = c
        return try { demander() } finally { if (c != null) enAttente.remove(question) }
    }
}
