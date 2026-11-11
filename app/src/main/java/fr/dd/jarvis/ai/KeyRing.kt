package fr.dd.jarvis.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    /** Client pour les agents : on préfère échouer vite (20 s) et basculer sur l'autre fournisseur. */
    val fast: OkHttpClient = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    val JSON = "application/json; charset=utf-8".toMediaType()

    @Volatile private var dernierPrechauffage = 0L

    /**
     * Ouvre à l'avance la connexion sécurisée vers l'IA (pendant que l'utilisateur parle ou écrit) : la demande part
     * ensuite sur une connexion prête, sans les allers-retours de la poignée de main TLS. Au plus une fois par minute.
     */
    fun prechauffer(url: String = "https://api.groq.com/openai/v1/models") {
        val maintenant = System.currentTimeMillis()
        if (maintenant - dernierPrechauffage < 60_000L) return
        dernierPrechauffage = maintenant
        runCatching {
            client.newCall(okhttp3.Request.Builder().url(url).head().build()).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.close() }
            })
        }
    }
}

/**
 * Anneau de clés API avec rotation automatique :
 *  - chaque requête utilise la clé suivante (répartition de la charge sur les quotas gratuits) ;
 *  - une clé qui renvoie 429 / 401 / 403 / 5xx est mise en pause puis on passe à la suivante. La pause suit ce que
 *    dit le fournisseur (« try again in 7m30s », « retry in 41s », quota du jour) au lieu d'une minute fixe ;
 *  - si TOUTES les clés sont en pause, l'anneau échoue tout de suite (429) : l'appelant passe aussitôt au fournisseur
 *    suivant (Groq → Gemini → Cloudflare) au lieu de perdre du temps sur des clés épuisées.
 */
class KeyRing(private val keys: () -> List<String>, private val quotaPartagee: Boolean = false) {
    private var next = 0
    private val cooldown = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun count(): Int = keys().count { it.isNotBlank() }

    /** Refus pour quota (429) récents : horodatages, pour le tableau de santé. */
    private val quotas = java.util.concurrent.ConcurrentLinkedDeque<Long>()

    fun quotasDerniereHeure(): Int {
        val limite = System.currentTimeMillis() - 3_600_000L
        while (quotas.peekFirst()?.let { it < limite } == true) quotas.pollFirst()
        return quotas.size
    }

    /** Clés refusées par le fournisseur (401/403 : invalides, révoquées, compte suspendu) → quand. */
    private val refusees = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Clés refusées dans les 6 dernières heures, masquées (« gsk_…a1b2 »), pour l'écran de réglages. */
    fun refuseesMasquees(): List<String> {
        val limite = System.currentTimeMillis() - 6 * 3_600_000L
        val l = keys().filter { it.isNotBlank() }
        return l.filter { (refusees[it] ?: 0L) > limite }.map { k -> "n°${l.indexOf(k) + 1} (${k.take(4)}…${k.takeLast(4)})" }
    }

    fun pausedCount(): Int {
        val now = System.currentTimeMillis()
        return keys().count { (cooldown[it] ?: 0L) > now }
    }

    /** Fin de la pause la plus proche (0 si une clé est prête). */
    fun prochaineDisponibilite(): Long {
        val now = System.currentTimeMillis()
        val l = keys().filter { it.isNotBlank() }
        if (l.any { (cooldown[it] ?: 0L) <= now }) return 0L
        return l.minOfOrNull { cooldown[it] ?: 0L } ?: 0L
    }

    companion object {
        /** Clé OpenAI / compatible OpenAI : ne doit jamais être envoyée à Google Gemini. */
        fun estCleOpenAi(key: String): Boolean {
            val value = key.trim().lowercase()
            // OpenAI : anciennes clés sk-… et formats actuels sk-proj-/sk-svcacct-.
            // Exclure les préfixes d'autres fournisseurs qui commencent aussi par sk- (OpenRouter, Anthropic).
            return value.startsWith("sk-") && !value.startsWith("sk-or-") && !value.startsWith("sk-ant-")
        }

        private val ATTENTE = Regex("(?:try again|retry) in ((?:\\d+h)?(?:\\d+m(?!s))?(?:\\d+(?:\\.\\d+)?s)?(?:\\d+ms)?)", RegexOption.IGNORE_CASE)
        private val DELAI_JSON = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"")
        private val JOUR = Regex("per ?day|PerDay|\\(TPD\\)|\\(RPD\\)|daily", RegexOption.IGNORE_CASE)

        /** Durée (ms) d'une expression « 1h2m3.5s », « 7m30s », « 41.2s », « 450ms ». */
        internal fun duree(t: String): Long {
            var ms = 0.0
            Regex("(\\d+(?:\\.\\d+)?)(ms|h|m|s)").findAll(t).forEach { m ->
                val v = m.groupValues[1].toDouble()
                ms += when (m.groupValues[2]) { "h" -> v * 3_600_000; "m" -> v * 60_000; "s" -> v * 1000; else -> v }
            }
            return ms.toLong()
        }

        /** Pause d'une clé après une erreur (testable) : ce que demande le fournisseur, bornée entre 5 s et 24 h. */
        internal fun pause(code: Int, message: String, maintenant: Long = System.currentTimeMillis()): Long? {
            if (code == 401 || code == 403) return 10 * 60_000L
            if (code == 400 && Regex("api.?key.*(not valid|invalid)|invalid.*api.?key|API_KEY_INVALID", RegexOption.IGNORE_CASE).containsMatchIn(message)) return 6 * 60 * 60_000L
            if (code >= 500) return 15_000L
            if (code != 429) return null
            val demande = ATTENTE.find(message)?.groupValues?.get(1)?.let { duree(it) }?.takeIf { it > 0 }
                ?: DELAI_JSON.find(message)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            if (demande != null) return (demande + 1000).coerceIn(5_000L, 24 * 3_600_000L)
            // Quota du jour sans délai indiqué : jusqu'à minuit (heure du Pacifique, remise à zéro de Google), 1 h au moins.
            if (JOUR.containsMatchIn(message)) {
                val pt = java.time.ZoneId.of("America/Los_Angeles")
                val minuit = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(maintenant), pt).toLocalDate().plusDays(1).atStartOfDay(pt).toInstant().toEpochMilli()
                return (minuit - maintenant).coerceIn(3_600_000L, 24 * 3_600_000L)
            }
            return 60_000L
        }
    }

    suspend fun <T> run(block: suspend (String) -> T): T {
        val list = keys().filter { it.isNotBlank() }
        if (list.isEmpty()) throw IllegalStateException("Aucune clé configurée (Réglages)")
        val now = System.currentTimeMillis()
        val order = list.indices.map { (next + it) % list.size }
        val (ready, cooling) = order.partition { (cooldown[list[it]] ?: 0L) <= now }
        // Toutes en pause : on ne retente que la clé qui se libère dans moins de 90 s ; sinon échec immédiat (relais suivant).
        val essais = ready.ifEmpty {
            val bientot = cooling.minByOrNull { cooldown[list[it]] ?: 0L }?.takeIf { (cooldown[list[it]] ?: 0L) - now < 90_000L }
            if (bientot == null) {
                val min = ((cooling.minOf { cooldown[list[it]] ?: 0L } - now) / 60_000L).coerceAtLeast(1)
                throw ApiException(429, "toutes les clés (${list.size}) sont en pause après leur quota — reprise dans ~$min min")
            }
            listOf(bientot)
        }
        var last: ApiException? = null
        for (i in essais) {
            val key = list[i]
            try {
                val result = block(key)
                next = (i + 1) % list.size
                cooldown.remove(key); refusees.remove(key)
                return result
            } catch (e: ApiException) {
                last = e
                if (e.code == 429) quotas.addLast(System.currentTimeMillis())
                if (e.code == 401 || e.code == 403 || (e.code == 400 && Regex("api.?key.*(not valid|invalid)|invalid.*api.?key|API_KEY_INVALID", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty()))) refusees[key] = System.currentTimeMillis()
                val p = pause(e.code, e.message.orEmpty()) ?: throw e
                cooldown[key] = System.currentTimeMillis() + p
            }
        }
        throw last ?: IllegalStateException("Toutes les clés ont échoué")
    }
}
