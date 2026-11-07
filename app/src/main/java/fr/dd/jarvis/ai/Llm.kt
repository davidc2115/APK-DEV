package fr.dd.jarvis.ai

import fr.dd.jarvis.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Façade des deux fournisseurs :
 *  - Groq = cerveau principal (conversation + choix des outils), clés en rotation ;
 *  - Gemini = agents (analyse du planning, tri/résumé des mails, fiches contacts), clés en rotation.
 * Si aucune clé Gemini n'est configurée, les agents se rabattent sur Groq.
 */
class Llm(private val store: Store) {
    private val groqRing = KeyRing { store.groqKeys.toList() }
    private val geminiRing = KeyRing { store.geminiKeys.toList() }
    val groq = GroqClient(groqRing) { store.groqModel.value }
    val gemini = GeminiClient(geminiRing) { store.geminiModel.value }
    /** Dernier recours du cerveau principal (appel d'outils), seulement si TOUTES les clés Groq sont épuisées. */
    val cloudflare = CloudflareChatClient({ store.cfAccounts.toList() }) { store.cfChatModel.value }

    fun keyStatus(): String {
        val g = groqRing.count()
        val m = geminiRing.count()
        val c = store.cfAccounts.count { it.contains(':') }
        return "Groq $g clé(s)" + (if (groqRing.pausedCount() > 0) " (${groqRing.pausedCount()} en pause)" else "") +
            " · Gemini $m clé(s)" + (if (geminiRing.pausedCount() > 0) " (${geminiRing.pausedCount()} en pause)" else "") +
            " · Cloudflare $c compte(s) (dernier recours)"
    }

    /** Dernière erreur des agents (affichée quand un repli sans IA est utilisé). */
    @Volatile
    var lastError: String = ""

    /** Agent : Gemini d'abord (clés en rotation), puis Groq en secours. Échoue avec un message lisible. */
    suspend fun agent(system: String, user: String): String {
        val errors = mutableListOf<String>()
        if (geminiRing.count() > 0) {
            try {
                return try {
                    gemini.generate(system, user)
                } catch (e: ApiException) {
                    if (e.code == 404 && autoFixGemini()) gemini.generate(system, user) else throw e
                }.ifBlank { throw IllegalStateException("réponse vide") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "Gemini : " + (e.message ?: e.javaClass.simpleName).take(160)
            }
        }
        if (groqRing.count() > 0) {
            try {
                return plainGroq(system, user).ifBlank { throw IllegalStateException("réponse vide") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "Groq : " + (e.message ?: e.javaClass.simpleName).take(160)
            }
        }
        lastError = errors.joinToString(" | ").ifBlank { "aucune clé configurée" }
        // Dernier recours : Ollama du serveur de la maison.
        return secoursOllama(IllegalStateException(lastError)) { app -> Ollama.generer(app, system, user).ifBlank { throw IllegalStateException("réponse vide") } }
    }

    private suspend fun plainGroq(system: String, user: String): String = try {
        groq.plain(system, user)
    } catch (e: ApiException) {
        if (e.code == 404 && autoFixGroq()) groq.plain(system, user) else throw e
    }

    /** Appel du cerveau ; si le modèle n'existe plus (404), choisit automatiquement un modèle disponible.
     *  [forceText] = interdit explicitement un appel d'outil ce tour-ci (reprise après un échec), en gardant les outils
     *  visibles pour le modèle plutôt que de les retirer (ce qui provoquait une erreur Groq, cf. Clients.kt). */
    suspend fun groqChat(messages: org.json.JSONArray, tools: org.json.JSONArray?, forceText: Boolean = false): org.json.JSONObject = try {
        groqChatEnLigne(messages, tools, forceText)
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        // Groq, puis Cloudflare, ont échoué (quotas, panne, pas d'internet) : Ollama du serveur de la maison en tout dernier
        // recours, avec la requête compactée à sa mesure. S'il échoue aussi, on remonte l'erreur d'origine (plus parlante).
        secoursOllama(e) { app ->
            val (msgs, outils) = fr.dd.jarvis.hermes.RelaisGroq.reduire(messages, if (forceText) null else tools, Ollama.NIVEAU)
            Ollama.chat(app, msgs, outils)
        }
    }

    /** Exécute [f] sur Ollama s'il est configuré ; sinon (ou en cas d'échec) relance [origine]. */
    private suspend fun <T> secoursOllama(origine: Exception, f: (fr.dd.jarvis.JarvisApp) -> T): T {
        val app = Ollama.app
        if (app == null || !Ollama.configure(app)) throw origine
        return try { withContext(Dispatchers.IO) { f(app) } }
        catch (e: CancellationException) { throw e } catch (e: Exception) { throw origine }
    }

    private suspend fun groqChatEnLigne(messages: org.json.JSONArray, tools: org.json.JSONArray?, forceText: Boolean): org.json.JSONObject = try {
        groq.chat(messages, tools, 1800, forceText)
    } catch (e: ApiException) {
        when {
            e.code == 404 && autoFixGroq() -> groq.chat(messages, tools, 1800, forceText)
            // 413 : requête trop grosse pour la limite de jetons/minute du modèle → on compacte (historique, résultats d'outils, schéma) et on réessaie.
            e.code == 413 || (e.code == 429 && (e.message ?: "").contains("tokens per minute", true) && (e.message ?: "").contains("Requested")) -> {
                try { groq.chat(compacter(messages, 1), compacterSchema(tools, 1), 1000, forceText) }
                catch (e2: ApiException) { if (e2.code == 413) groq.chat(compacter(messages, 2), compacterSchema(tools, 2), 700, forceText) else throw e2 }
            }
            // Toutes les clés Groq ont échoué (quota épuisé, clés en pause…) : Cloudflare en tout dernier recours, avec les
            // comptes déjà configurés pour les images. S'il échoue aussi, on remonte l'erreur Groq d'origine (plus parlante).
            else -> try { cloudflare.chat(messages, tools) } catch (e2: Exception) { throw e }
        }
    }

    private fun compacter(messages: org.json.JSONArray, niveau: Int): org.json.JSONArray {
        val sys = if (niveau == 1) 5000 else 2800
        val txt = if (niveau == 1) 700 else 350
        val outil = if (niveau == 1) 1800 else 800
        val src = (0 until messages.length()).map { messages.getJSONObject(it) }
        val dernierUser = src.indexOfLast { it.optString("role") == "user" }
        // Messages simples (sans appel d'outil) trop anciens : on ne garde que les plus récents.
        val anciens = src.withIndex().filter { (i, m) -> i < dernierUser && m.optString("role") in setOf("user", "assistant") && !m.has("tool_calls") }.map { it.index }
        val aGarder = anciens.takeLast(if (niveau == 1) 6 else 2).toSet()
        val out = org.json.JSONArray()
        src.forEachIndexed { i, m ->
            val role = m.optString("role")
            if (i < dernierUser && role in setOf("user", "assistant") && !m.has("tool_calls") && i !in aGarder) return@forEachIndexed
            val c = org.json.JSONObject(m.toString())
            val limite = when (role) { "system" -> sys; "tool" -> outil; else -> txt }
            if (c.opt("content") is String) c.put("content", c.getString("content").let { if (it.length > limite) it.take(limite) + "…" else it })
            out.put(c)
        }
        return out
    }

    private fun compacterSchema(tools: org.json.JSONArray?, niveau: Int): org.json.JSONArray? {
        if (tools == null) return null
        val out = org.json.JSONArray()
        for (i in 0 until tools.length()) {
            val t = org.json.JSONObject(tools.getJSONObject(i).toString())
            val f = t.getJSONObject("function")
            f.put("description", f.optString("description").take(if (niveau == 1) 160 else 90))
            f.optJSONObject("parameters")?.optJSONObject("properties")?.let { p ->
                p.keys().forEach { k -> p.optJSONObject(k)?.let { o -> if (niveau == 2) o.remove("description") else o.put("description", o.optString("description").take(70)) } }
            }
            out.put(t)
        }
        return out
    }

    suspend fun listGroqModels(): List<String> = firstKey(store.groqKeys.toList()) { groq.listModels(it) }
    suspend fun listGeminiModels(): List<String> = firstKey(store.geminiKeys.toList()) { gemini.listModels(it) }

    private suspend fun <T> firstKey(keys: List<String>, f: suspend (String) -> T): T {
        var last: Exception = IllegalStateException("Aucune clé configurée")
        for (k in keys) try { return f(k) } catch (e: Exception) { last = e }
        throw last
    }

    fun bestGroq(models: List<String>): String? {
        val pref = listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.1-8b-instant")
        return pref.firstOrNull { it in models } ?: models.firstOrNull { it.startsWith("llama") && !it.contains("guard") }
    }

    fun bestGemini(models: List<String>): String? {
        val pref = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-flash-latest", "gemini-3.5-flash-lite", "gemini-2.5-flash")
        pref.firstOrNull { it in models }?.let { return it }
        return models.filter { it.contains("flash") && !it.contains("preview") && !it.contains("exp") }.maxOrNull()
    }

    suspend fun autoFixGroq(): Boolean = try {
        val m = bestGroq(listGroqModels())
        if (m != null && m != store.groqModel.value) { store.groqModel.set(m); true } else false
    } catch (e: Exception) { false }

    suspend fun autoFixGemini(): Boolean = try {
        val m = bestGemini(listGeminiModels())
        if (m != null && m != store.geminiModel.value) { store.geminiModel.set(m); true } else false
    } catch (e: Exception) { false }

    suspend fun testGroqKeys(): List<String> = store.groqKeys.toList().map { k ->
        mask(k) + " : " + try {
            val t0 = System.currentTimeMillis(); groq.ping(k); "✅ OK (${System.currentTimeMillis() - t0} ms)"
        } catch (e: Exception) {
            "❌ " + (e.message ?: "erreur").take(90)
        }
    }

    suspend fun testGeminiKeys(): List<String> = store.geminiKeys.toList().map { k ->
        mask(k) + " : " + try {
            val t0 = System.currentTimeMillis(); gemini.ping(k); "✅ OK (${System.currentTimeMillis() - t0} ms)"
        } catch (e: Exception) {
            "❌ " + (e.message ?: "erreur").take(90)
        }
    }


    /**
     * Diagnostic des clés IA enregistrées dans JARVIS.
     * Les secrets ne sont jamais inclus dans les requêtes de sortie ni dans les messages d'erreur.
     * Les tests d'inférence utilisent au maximum un jeton lorsque le modèle est configuré.
     */
    suspend fun testerClesApi(fournisseurRaw: String = "tous"): String {
        val alias = mapOf("all" to "tous", "tout" to "tous", "toutes" to "tous", "google" to "gemini", "hf" to "huggingface", "hugging face" to "huggingface", "cloudflare workers ai" to "cloudflare", "workers ai" to "cloudflare", "z.ai" to "zai")
        val fournisseur = alias[fournisseurRaw.trim().lowercase()] ?: fournisseurRaw.trim().lowercase().ifBlank { "tous" }
        val connus = setOf("tous", "groq", "gemini", "cloudflare", "zai", "openrouter", "nvidia", "mistral", "huggingface", "ollama")
        if (fournisseur !in connus) return "Fournisseur inconnu. Choix : tous, Groq, Gemini, Cloudflare, Z.ai, OpenRouter, NVIDIA, Mistral, Hugging Face ou Ollama."

        val resultats = mutableListOf<Pair<Boolean, String>>()
        suspend fun verifier(label: String, action: suspend () -> String) {
            val debut = System.currentTimeMillis()
            val resultat = try {
                true to action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                false to statutErreurApi(e.code)
            } catch (e: IllegalArgumentException) {
                false to "CONFIGURATION INVALIDE"
            } catch (e: Exception) {
                false to "ERREUR RÉSEAU (${e.javaClass.simpleName})"
            }
            resultats.add(resultat.first to "$label — ${resultat.second} (${System.currentTimeMillis() - debut} ms)")
        }

        if (fournisseur == "tous" || fournisseur == "groq") {
            for ((i, key) in store.groqKeys.toList().withIndex()) {
                verifier("Groq #${i + 1} (${mask(key)})") {
                    val modele = store.groqModel.value
                    try {
                        groq.ping(key)
                        "✅ VALIDÉ — clé, modèle « $modele » et appel de test acceptés"
                    } catch (e: ApiException) {
                        if (e.code != 400 && e.code != 404) throw e
                        val modeles = groq.listModels(key)
                        "⚠️ CLÉ ACCEPTÉE — ${modeles.size} modèles accessibles, mais le modèle configuré « $modele » est refusé"
                    }
                }
            }
        }

        if (fournisseur == "tous" || fournisseur == "gemini") {
            for ((i, key) in store.geminiKeys.toList().withIndex()) {
                verifier("Gemini #${i + 1} (${mask(key)})") {
                    val modele = store.geminiModel.value
                    try {
                        gemini.ping(key)
                        "✅ VALIDÉ — clé, modèle « $modele » et appel de test acceptés"
                    } catch (e: ApiException) {
                        if (e.code != 400 && e.code != 404) throw e
                        val modeles = gemini.listModels(key)
                        "⚠️ CLÉ ACCEPTÉE — ${modeles.size} modèles accessibles, mais le modèle configuré « $modele » est refusé"
                    }
                }
            }
        }

        if (fournisseur == "tous" || fournisseur == "cloudflare") {
            for ((i, entry) in store.cfAccounts.toList().withIndex()) {
                val parts = entry.split(":", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    verifier("Cloudflare #${i + 1} (compte masqué)") {
                        testerCloudflare(parts[0], parts[1])
                        "✅ VALIDÉ — accès Workers AI et appel de test acceptés"
                    }
                }
            }
        }

        val externes = listOf(
            "zai" to "Z.ai",
            "openrouter" to "OpenRouter",
            "nvidia" to "NVIDIA",
            "mistral" to "Mistral",
            "huggingface" to "Hugging Face"
        )
        for ((id, nom) in externes) {
            if (fournisseur == "tous" || fournisseur == id) {
                val key = store.externalProviderKey(id)
                if (key.isNotBlank()) verifier("$nom (${mask(key)})") {
                    testerFournisseurCompatible(id, key)
                    "✅ VALIDÉ — clé et accès API acceptés"
                }
            }
        }

        // Ollama du serveur de la maison (secours local) : testé s'il est activé et qu'une adresse est connue.
        val app = Ollama.app
        if ((fournisseur == "tous" || fournisseur == "ollama") && app != null && Ollama.configure(app)) {
            val debut = System.currentTimeMillis()
            val t = withContext(Dispatchers.IO) { runCatching { Ollama.tester(app) }.getOrElse { "❌ Ollama : ${it.message}" } }
            resultats.add(t.startsWith("✅") to "$t (${System.currentTimeMillis() - debut} ms)")
        }

        if (resultats.isEmpty()) {
            return if (fournisseur == "tous") "Aucune clé IA configurée dans les réglages de JARVIS."
            else "Aucune clé $fournisseur configurée dans les réglages de JARVIS."
        }
        val valides = resultats.count { it.first }
        val echecs = resultats.size - valides
        return "Diagnostic des clés API terminé : $valides validée(s), $echecs à vérifier.\n" +
            resultats.joinToString("\n") { it.second } +
            "\n\nLes clés complètes ne sont jamais affichées. Les appels de test d'inférence sont limités à un jeton lorsque le modèle est configuré."
    }

    private fun statutErreurApi(code: Int): String = when (code) {
        401 -> "❌ CLÉ REFUSÉE (HTTP 401)"
        403 -> "⚠️ ACCÈS REFUSÉ (HTTP 403) — vérifier les permissions du jeton"
        402 -> "⚠️ FACTURATION OU CRÉDITS INSUFFISANTS (HTTP 402)"
        429 -> "⏳ QUOTA ÉPUISÉ OU LIMITE DE DÉBIT (HTTP 429)"
        400, 404 -> "⚠️ REQUÊTE, MODÈLE OU ENDPOINT À VÉRIFIER (HTTP $code) — la clé n'est pas nécessairement invalide"
        in 500..599 -> "⚠️ FOURNISSEUR MOMENTANÉMENT INDISPONIBLE (HTTP $code)"
        else -> "❌ REFUS DU FOURNISSEUR (HTTP $code)"
    }

    private suspend fun testerFournisseurCompatible(provider: String, key: String) = withContext(Dispatchers.IO) {
        val defaults = mapOf(
            "zai" to "https://api.z.ai/api/paas/v4",
            "openrouter" to "https://openrouter.ai/api/v1",
            "nvidia" to "https://integrate.api.nvidia.com/v1",
            "mistral" to "https://api.mistral.ai/v1",
            "huggingface" to "https://router.huggingface.co/v1"
        )
        val base = store.externalProviderEndpoint(provider).ifBlank { defaults[provider].orEmpty() }.trimEnd('/')
        require(base.startsWith("https://")) { "Endpoint HTTPS requis" }
        val model = store.externalProviderModel(provider).trim()
        val endpoint = when {
            model.isNotBlank() -> "$base/chat/completions"
            provider == "openrouter" && store.externalProviderEndpoint(provider).isBlank() -> "https://openrouter.ai/api/v1/auth/key"
            provider == "huggingface" && store.externalProviderEndpoint(provider).isBlank() -> "https://huggingface.co/api/whoami-v2"
            else -> "$base/models"
        }
        val request = if (model.isBlank()) {
            Request.Builder().url(endpoint).header("Authorization", "Bearer $key").get().build()
        } else {
            val body = JSONObject()
                .put("model", model)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
                .put("max_tokens", 1)
            Request.Builder().url(endpoint)
                .header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody(Http.JSON)).build()
        }
        Http.fast.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "HTTP ${response.code}")
        }
    }

    private suspend fun testerCloudflare(accountId: String, token: String) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", store.cfChatModel.value.ifBlank { "@cf/meta/llama-3.3-70b-instruct-fp8-fast" })
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
            .put("max_tokens", 1)
        val request = Request.Builder()
            .url("https://api.cloudflare.com/client/v4/accounts/$accountId/ai/v1/chat/completions")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(Http.JSON)).build()
        Http.fast.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "HTTP ${response.code}")
        }
    }

    companion object {
        fun mask(k: String) = if (k.length > 10) k.take(4) + "…" + k.takeLast(4) else "••••"
    }
}
