package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import java.util.concurrent.ConcurrentHashMap

/**
 * Plusieurs comptes Hugging Face (jeton principal + jetons supplémentaires des Réglages) utilisés à tour de rôle : le
 * quota gratuit ZeroGPU d'un compte est petit (≈ 3 utilisations et 5 min de GPU par jour). Un compte refusé pour quota
 * est mis de côté quelques heures et le suivant prend le relais ; une autre erreur (Space en panne) n'est pas retentée
 * sur les autres comptes, car elle ne dépend pas du compte (et chaque essai, même raté, consomme du quota).
 */
object HfJetons {
    private val pauses = ConcurrentHashMap<String, Long>()

    /** Tous les jetons enregistrés, le principal d'abord. */
    fun tous(app: JarvisApp): List<String> =
        (listOf(app.store.huggingFaceToken.value) + app.store.hfTokensSupp.toList()).map { it.trim() }.filter { it.isNotBlank() }.distinct()

    fun configure(app: JarvisApp) = tous(app).isNotEmpty()

    /** Jetons pas en pause en ce moment. */
    fun disponibles(app: JarvisApp): List<String> = tous(app).filter { (pauses[it] ?: 0L) <= System.currentTimeMillis() }

    /** Refus lié au compte (quota épuisé, jeton refusé) : changer de compte peut marcher. */
    internal fun refusDuCompte(message: String): Boolean =
        Regex("(?i)quota|épuisé|exceeded|runs limit|jeton hugging face refusé|token hugging face refusé").containsMatchIn(message)

    private fun mettreDeCote(jeton: String, heures: Int) { pauses[jeton] = System.currentTimeMillis() + heures * 3_600_000L }

    /**
     * Exécute [bloc] avec chaque compte disponible tour à tour jusqu'à un succès. Un refus pour quota ou jeton passe au
     * compte suivant (le compte refusé est mis de côté) ; toute autre erreur est remontée telle quelle.
     */
    suspend fun <T> avec(app: JarvisApp, bloc: suspend (String) -> T): T {
        val tous = tous(app)
        if (tous.isEmpty()) throw ApiException(401, "aucun jeton Hugging Face : ajoute-le dans Réglages › IA › Images & vidéo (gratuit, huggingface.co/settings/tokens)")
        val liste = disponibles(app)
        if (liste.isEmpty()) throw ApiException(429, "quota gratuit épuisé sur ${if (tous.size > 1) "tes ${tous.size} comptes" else "ton compte"} Hugging Face " +
            "(≈ 3 utilisations par jour et par compte, de retour 24 h après la première)")
        var derniere: Exception? = null
        for (j in liste) {
            try { return bloc(j) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                derniere = e
                val m = e.message.orEmpty()
                when {
                    e is ApiException && (e.code == 401 || e.code == 403) -> mettreDeCote(j, 24)
                    e is HfImage.QuotaEpuise || refusDuCompte(m) -> mettreDeCote(j, 6)
                    else -> throw e
                }
            }
        }
        val n = liste.size
        throw ApiException(429, (derniere?.message ?: "refus de Hugging Face") + if (n > 1) " (essayé sur tes $n comptes)" else "")
    }
}
