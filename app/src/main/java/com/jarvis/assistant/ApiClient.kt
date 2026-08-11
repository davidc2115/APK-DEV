package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val SYSTEM_PROMPT =
        "Tu es JARVIS, un assistant IA vocal et domotique/mobile inspiré d'Iron Man. " +
            "Tu parles de façon naturelle et chaleureuse, comme un véritable assistant personnel " +
            "qui connaît bien son interlocuteur — pas comme un robot ou une notice technique. " +
            "Sois concis mais humain : des phrases courtes, un ton légèrement complice, jamais " +
            "de jargon technique, jamais de listes à puces inutiles pour une réponse simple. " +
            "Réponds en français.\n\n" +
            "TU AS LE CONTRÔLE COMPLET DU SMARTPHONE DE L'UTILISATEUR. Quand l'utilisateur te demande d'effectuer une action système sur son téléphone, tu peux inclure un bloc de commande sous la forme exacte suivante dans ta réponse :\n" +
            "[JARVIS_CMD:{\"action\":\"NOM_ACTION\", ...params}]\n\n" +
            "Actions système disponibles :\n" +
            "• Call : {\"action\":\"call\", \"target\":\"Maman ou 0612345678\"}\n" +
            "• SMS : {\"action\":\"send_sms\", \"to\":\"Pierre\", \"message\":\"Coucou\"}\n" +
            "• Lire SMS : {\"action\":\"read_sms\", \"count\":5} (utilise count:1 si l'utilisateur demande seulement « le dernier »)\n" +
            "• Contacts : {\"action\":\"search_contact\", \"name\":\"Jean\"}\n" +
            "• Musique : {\"action\":\"play_music\", \"query\":\"Jazz\"}, {\"action\":\"pause_music\"}, {\"action\":\"stop_music\"}, {\"action\":\"set_volume\", \"level\":8}\n" +
            "• Agenda : {\"action\":\"today_events\"}, {\"action\":\"upcoming_events\", \"days\":7}, {\"action\":\"create_event\", \"title\":\"Rendez-vous docteur\", \"startTime\":1700000000000}, {\"action\":\"search_event\", \"query\":\"docteur\"}, {\"action\":\"update_event\", \"eventId\":42, \"newTitle\":\"nouveau titre\", \"newStartTime\":1700000000000}, {\"action\":\"delete_event\", \"eventId\":42}\n" +
            "  (IMPORTANT : pour modifier/supprimer un événement, cherche-le d'abord avec search_event ou today_events/upcoming_events pour obtenir son ID, visible entre parenthèses après chaque événement listé)\n" +
            "• Emails : {\"action\":\"read_emails\"}, {\"action\":\"send_email\", \"to\":\"contact@mail.com\", \"subject\":\"Projet\", \"body\":\"Bonjour\"}\n" +
            "• Fichiers : {\"action\":\"list_files\", \"path\":\"/sdcard/Downloads\"}, {\"action\":\"search_files\", \"query\":\"rapport\"}, {\"action\":\"read_file\", \"path\":\"/sdcard/notes.txt\"}, {\"action\":\"write_file\", \"path\":\"/sdcard/notes.txt\", \"content\":\"texte à écrire\"}, {\"action\":\"rename_file\", \"oldPath\":\"/sdcard/a.txt\", \"newName\":\"b.txt\"}, {\"action\":\"copy_file\", \"source\":\"/sdcard/a.txt\", \"dest\":\"/sdcard/Documents/a.txt\"}, {\"action\":\"move_file\", \"source\":\"/sdcard/a.txt\", \"dest\":\"/sdcard/Documents/a.txt\"}, {\"action\":\"delete_file\", \"path\":\"/sdcard/a.txt\"}, {\"action\":\"create_folder\", \"path\":\"/sdcard/NouveauDossier\"}, {\"action\":\"storage_info\"}\n" +
            "• GPS / Itinéraire : {\"action\":\"get_location\"}, {\"action\":\"open_maps\", \"query\":\"Tour Eiffel\"}\n" +
            "  (open_maps sert UNIQUEMENT à afficher un itinéraire routier. Ne JAMAIS l'utiliser pour des horaires, avis, infos pratiques sur un lieu.)\n" +
            "• Recherche web : {\"action\":\"web_search\", \"query\":\"horaires ouverture pharmacie Rue de Paris\"}\n" +
            "  (à utiliser pour TOUTE question factuelle sur un lieu ou un sujet : horaires, avis, adresse, infos pratiques, actualité, etc. — jamais open_maps pour ça)\n" +
            "• Notifications : {\"action\":\"get_notifications\"}\n" +
            "• Bluetooth : {\"action\":\"bluetooth_info\"}, {\"action\":\"enable_bluetooth\"}, {\"action\":\"disable_bluetooth\"}\n" +
            "• Wi-Fi : {\"action\":\"wifi_info\"}, {\"action\":\"enable_wifi\"}, {\"action\":\"disable_wifi\"}\n\n" +
            "Exemple de réponse : \"Très bien Monsieur, j'appelle Maman tout de suite. [JARVIS_CMD:{\"action\":\"call\",\"target\":\"Maman\"}]\""

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun sendChat(context: Context, history: List<HistoryEntry>): String =
        withContext(Dispatchers.IO) {
            val provider = Prefs.getProvider(context)

            val rawResponse = try {
                dispatchToProvider(context, provider, history)
            } catch (e: Exception) {
                "Connexion impossible. Vérifiez les paramètres dans ⚙. Détail : ${e.message}"
            }

            // Exécution automatique des commandes système si présentes dans la réponse
            val commandResult = JarvisCommandParser.parseAndExecute(context, rawResponse)
            val cleanText = JarvisCommandParser.cleanResponse(rawResponse)

            when {
                commandResult is JarvisCommandParser.CommandResult.Executed && commandResult.isInformational -> {
                    // Deuxième passage : demande à l'IA de reformuler le résultat brut
                    // naturellement, au lieu de l'afficher tel quel (listes, JSON, etc.)
                    val lastUserMsg = history.lastOrNull { it.role == "user" }?.text ?: ""
                    val summaryPrompt =
                        "L'utilisateur a demandé : \"$lastUserMsg\"\n\n" +
                            "Voici le résultat brut obtenu (ne le montre jamais tel quel, ni son formatage) :\n" +
                            "${commandResult.outputMessage}\n\n" +
                            "Réponds directement et naturellement à l'utilisateur avec cette information, " +
                            "comme si tu venais de la consulter toi-même. Sois concis : si l'utilisateur a " +
                            "demandé UNE seule chose (« le dernier SMS », « le dernier email »...), ne donne " +
                            "que celle-là avec l'expéditeur et le contenu, sans lister le reste. Ne mentionne " +
                            "jamais de commande système, d'action JSON ni de terme technique."
                    try {
                        val summary = dispatchToProvider(context, provider, listOf(HistoryEntry("user", summaryPrompt)))
                        JarvisCommandParser.cleanResponse(summary).trim()
                    } catch (e: Exception) {
                        commandResult.outputMessage // repli sur le résultat brut si la reformulation échoue
                    }
                }
                commandResult is JarvisCommandParser.CommandResult.Executed -> {
                    if (cleanText.isBlank()) commandResult.outputMessage
                    else "$cleanText\n\n${commandResult.outputMessage}"
                }
                else -> rawResponse
            }
        }

    private suspend fun dispatchToProvider(context: Context, provider: Provider, history: List<HistoryEntry>): String {
        return when {
            provider.isAuto -> sendAuto(context, history)
            provider.isLocal -> sendLocal(context, history)
            provider == Provider.CLAUDE -> sendClaudeWithRotation(context, history)
            provider == Provider.GEMINI -> sendGeminiWithRotation(context, history)
            provider == Provider.SERPAPI -> sendSerpApiWithRotation(context, history)
            else -> sendOpenAiWithRotation(context, history, provider)
        }
    }

    // ─── Mode Automatique avec multi-clés + sélection intelligente ────────────

    private fun sendAuto(context: Context, history: List<HistoryEntry>): String {
        val candidates = Provider.AUTO_FALLBACK_ORDER.filter {
            Prefs.getApiKeysFor(context, it).isNotEmpty()
        }

        if (candidates.isEmpty()) {
            return "Aucune IA configurée pour le mode Automatique. " +
                "Ouvre ⚙ Paramètres → onglet « Clés API » et ajoute au moins une clé."
        }

        // Ordonne les candidats selon la nature de la demande de l'utilisateur,
        // avant de retomber sur l'ordre de repli standard si rien ne correspond.
        val lastUserEntry = history.lastOrNull { it.role == "user" }
        val orderedCandidates = rankProvidersForRequest(candidates, lastUserEntry)

        var lastError = ""
        for (provider in orderedCandidates) {
            val result = try {
                when (provider) {
                    Provider.CLAUDE -> sendClaudeWithRotation(context, history)
                    Provider.GEMINI -> sendGeminiWithRotation(context, history)
                    else -> sendOpenAiWithRotation(context, history, provider)
                }
            } catch (e: Exception) {
                "Erreur : ${e.message}"
            }

            if (!result.startsWith("Erreur") &&
                !result.startsWith("Connexion impossible") &&
                !result.startsWith("Format de réponse inattendu") &&
                !result.startsWith("Clé API")
            ) {
                return result
            }
            lastError = "[${provider.displayName}] $result"
        }

        return "Toutes les IA configurées ont échoué. Dernière erreur : $lastError"
    }

    /**
     * Classement heuristique (mots-clés) des fournisseurs disponibles selon
     * la nature de la demande. Ce n'est pas une IA de routage à proprement
     * parler — juste des règles simples pour prioriser un fournisseur mieux
     * adapté avant de retomber sur l'ordre de repli standard.
     */
    private fun rankProvidersForRequest(candidates: List<Provider>, lastUserEntry: HistoryEntry?): List<Provider> {
        if (lastUserEntry == null) return candidates

        // Une photo jointe exige un fournisseur capable de vision.
        if (lastUserEntry.imageBase64 != null) {
            val visionCapable = listOf(Provider.CLAUDE, Provider.OPENAI, Provider.GEMINI)
            val preferred = candidates.filter { it in visionCapable }
            if (preferred.isNotEmpty()) {
                return preferred + candidates.filterNot { it in preferred }
            }
        }

        val text = lastUserEntry.text.lowercase()

        val codeKeywords = listOf(
            "code", "fonction", "bug", "python", "kotlin", "java", "script",
            "programme", "compile", "erreur de", "debug", "sql", "regex", "api"
        )
        val creativeKeywords = listOf(
            "histoire", "poème", "poeme", "écris", "ecris", "raconte", "imagine", "rédige", "redige"
        )
        val quickKeywords = listOf(
            "rapide", "vite", "en bref", "résume", "resume", "en une phrase"
        )

        val preferredOrder: List<Provider> = when {
            codeKeywords.any { text.contains(it) } -> listOf(Provider.CLAUDE, Provider.OPENAI, Provider.DEEPSEEK)
            creativeKeywords.any { text.contains(it) } -> listOf(Provider.CLAUDE, Provider.OPENAI)
            quickKeywords.any { text.contains(it) } -> listOf(Provider.GROQ, Provider.GEMINI)
            else -> emptyList()
        }

        if (preferredOrder.isEmpty()) return candidates

        val preferred = preferredOrder.filter { it in candidates }
        return preferred + candidates.filterNot { it in preferred }
    }

    // ─── Modèle local sur l'appareil ──────────────────────────────────────────

    private suspend fun sendLocal(context: Context, history: List<HistoryEntry>): String {
        val modelPath = Prefs.getLocalModelPath(context)
        if (modelPath.isBlank()) {
            return "Aucun modèle local configuré. Ouvre ⚙ Paramètres → onglet « Local » et télécharge un modèle."
        }
        val prompt = buildPromptFromHistory(history)
        return LocalLlmManager.generate(context, modelPath, prompt)
    }

    private fun buildPromptFromHistory(history: List<HistoryEntry>): String {
        val recent = history.takeLast(8)
        val sb = StringBuilder(SYSTEM_PROMPT).append("\n\n")
        for (entry in recent) {
            val label = if (entry.role == "user") "Utilisateur" else "JARVIS"
            val suffix = if (entry.imageBase64 != null) " [photo jointe]" else ""
            sb.append(label).append(": ").append(entry.text).append(suffix).append("\n")
        }
        sb.append("JARVIS: ")
        return sb.toString()
    }

    // ─── OpenAI-compatible avec rotation de clés ──────────────────────────────

    private fun sendOpenAiWithRotation(
        context: Context,
        history: List<HistoryEntry>,
        provider: Provider
    ): String {
        val keys = Prefs.getApiKeysFor(context, provider)
        val baseUrl = if (!provider.isAuto && !provider.isLocal && provider != Provider.CUSTOM) provider.defaultBaseUrl else Prefs.getBaseUrl(context)
        val model = if (!provider.isAuto && !provider.isLocal && provider != Provider.CUSTOM) provider.defaultModel else Prefs.getModel(context)

        if (keys.isEmpty() && provider.needsApiKey) {
            return "Aucune clé API configurée pour ${provider.displayName}. Ajoute-en dans ⚙ Paramètres → Clés API."
        }

        val maxAttempts = maxOf(1, keys.size)
        var lastErr = ""

        for (attempt in 0 until maxAttempts) {
            val apiKey = if (keys.isNotEmpty()) Prefs.getNextApiKey(context, provider) else ""
            val result = sendOpenAiCompatible(baseUrl, model, apiKey, history, provider)

            if (!result.startsWith("Erreur API (429)") && !result.startsWith("Erreur API (401)")) {
                return result
            }

            if (apiKey.isNotBlank()) Prefs.markKeyFailed(context, provider, apiKey)
            lastErr = result
        }

        return lastErr
    }

    private fun sendOpenAiCompatible(
        baseUrl: String,
        model: String,
        apiKey: String,
        history: List<HistoryEntry>,
        provider: Provider
    ): String {
        val messagesArray = JSONArray()
        messagesArray.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        for (entry in history) {
            if (entry.imageBase64 != null) {
                val contentArray = JSONArray()
                contentArray.put(JSONObject().put("type", "text").put("text", entry.text))
                contentArray.put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:${entry.imageMime ?: "image/jpeg"};base64,${entry.imageBase64}")
                    )
                )
                messagesArray.put(JSONObject().put("role", entry.role).put("content", contentArray))
            } else {
                messagesArray.put(JSONObject().put("role", entry.role).put("content", entry.text))
            }
        }

        val bodyObj = JSONObject()
            .put("model", model)
            .put("messages", messagesArray)
            .put("temperature", 0.7)

        val requestBuilder = Request.Builder()
            .url(baseUrl)
            .post(bodyObj.toString().toRequestBody(JSON))
            .addHeader("Content-Type", "application/json")

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        if (provider == Provider.OPENROUTER) {
            requestBuilder
                .addHeader("HTTP-Referer", "https://github.com/davidc2115/APK-DEV")
                .addHeader("X-Title", "JARVIS Android")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) return "Erreur API (${response.code}) : $bodyStr"
            val json = JSONObject(bodyStr)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.getJSONObject(0).optJSONObject("message")
                return message?.optString("content") ?: "Réponse vide reçue du serveur."
            }
            return "Format de réponse inattendu : $bodyStr"
        }
    }

    // ─── Claude (Anthropic) avec rotation ──────────────────────────────────────

    private fun sendClaudeWithRotation(context: Context, history: List<HistoryEntry>): String {
        val keys = Prefs.getApiKeysFor(context, Provider.CLAUDE)
        if (keys.isEmpty()) return "Aucune clé API Claude configurée."

        for (apiKey in keys) {
            val res = sendClaude(Provider.CLAUDE.defaultBaseUrl, Provider.CLAUDE.defaultModel, apiKey, history)
            if (!res.startsWith("Erreur API Claude (429)") && !res.startsWith("Erreur API Claude (401)")) return res
            Prefs.markKeyFailed(context, Provider.CLAUDE, apiKey)
        }
        return "Toutes les clés API Claude ont échoué."
    }

    private fun sendClaude(baseUrl: String, model: String, apiKey: String, history: List<HistoryEntry>): String {
        val messagesArray = JSONArray()
        for (entry in history) {
            messagesArray.put(JSONObject().put("role", entry.role).put("content", entry.text))
        }

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", SYSTEM_PROMPT)
            .put("messages", messagesArray)
            .toString()
            .toRequestBody(JSON)

        val request = Request.Builder()
            .url(baseUrl)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) return "Erreur API Claude (${response.code}) : $bodyStr"
            val json = JSONObject(bodyStr)
            val content = json.optJSONArray("content")
            if (content != null && content.length() > 0) {
                return content.getJSONObject(0).optString("text", "Réponse vide.")
            }
            return "Format de réponse inattendu : $bodyStr"
        }
    }

    // ─── Google Gemini avec rotation ──────────────────────────────────────────

    private fun sendGeminiWithRotation(context: Context, history: List<HistoryEntry>): String {
        val keys = Prefs.getApiKeysFor(context, Provider.GEMINI)
        if (keys.isEmpty()) return "Aucune clé API Gemini configurée."

        for (apiKey in keys) {
            val res = sendGemini(Provider.GEMINI.defaultBaseUrl, apiKey, history)
            if (!res.startsWith("Erreur API Gemini (429)") && !res.startsWith("Erreur API Gemini (401)")) return res
            Prefs.markKeyFailed(context, Provider.GEMINI, apiKey)
        }
        return "Toutes les clés API Gemini ont échoué."
    }

    private fun sendGemini(baseUrl: String, apiKey: String, history: List<HistoryEntry>): String {
        val separator = if (baseUrl.contains("?")) "&" else "?"
        val url = "$baseUrl${separator}key=$apiKey"

        val contentsArray = JSONArray()
        for (entry in history) {
            val geminiRole = if (entry.role == "assistant") "model" else "user"
            val partsArray = JSONArray().put(JSONObject().put("text", entry.text))
            contentsArray.put(JSONObject().put("role", geminiRole).put("parts", partsArray))
        }

        val body = JSONObject()
            .put("contents", contentsArray)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .toString()
            .toRequestBody(JSON)

        val request = Request.Builder().url(url).post(body).addHeader("Content-Type", "application/json").build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) return "Erreur API Gemini (${response.code}) : $bodyStr"
            val json = JSONObject(bodyStr)
            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val content = candidates.getJSONObject(0).optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    return parts.getJSONObject(0).optString("text", "Réponse vide.")
                }
            }
            return "Format de réponse inattendu : $bodyStr"
        }
    }

    // ─── SerpAPI avec rotation ────────────────────────────────────────────────

    private fun sendSerpApiWithRotation(context: Context, history: List<HistoryEntry>): String {
        val keys = Prefs.getApiKeysFor(context, Provider.SERPAPI)
        if (keys.isEmpty()) return "Aucune clé API SerpAPI configurée."

        val query = history.lastOrNull { it.role == "user" }?.text ?: return "Aucune question à rechercher."

        for (apiKey in keys) {
            val url = "https://serpapi.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&api_key=$apiKey&engine=google&hl=fr&gl=fr&num=5"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(bodyStr)
                    val organic = json.optJSONArray("organic_results")
                    if (organic != null && organic.length() > 0) {
                        val sb = StringBuilder("🔍 Résultats web pour « $query » :\n\n")
                        for (i in 0 until minOf(3, organic.length())) {
                            val item = organic.getJSONObject(i)
                            sb.append("${i + 1}. **${item.optString("title")}**\n${item.optString("snippet")}\n🔗 ${item.optString("link")}\n\n")
                        }
                        return sb.toString().trimEnd()
                    }
                } else if (response.code == 429 || response.code == 401) {
                    Prefs.markKeyFailed(context, Provider.SERPAPI, apiKey)
                }
            }
        }
        return "Toutes les clés SerpAPI ont échoué."
    }
}
