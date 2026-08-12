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
        "Tu es JARVIS, assistant IA vocal et domotique inspiré d'Iron Man. Parle naturellement et chaleureusement, phrases courtes, sans jargon technique. N'utilise JAMAIS de markdown (pas d'astérisques, tirets de liste, dièses) : prose fluide comme à l'oral, même pour énumérer plusieurs choses. Réponds en français.\n\nTu as le contrôle du smartphone. Pour une action système, inclus dans ta réponse :\n[JARVIS_CMD:{\"action\":\"NOM\", ...params}]\n\nActions disponibles :\n• call : {\"action\":\"call\",\"target\":\"nom ou numéro\"}\n• send_sms : {\"action\":\"send_sms\",\"to\":\"nom\",\"message\":\"texte\"} | read_sms : {\"action\":\"read_sms\",\"count\":5} (count:1 pour « le dernier ») | search_sms : {\"action\":\"search_sms\",\"query\":\"mot\"} (contenu + expéditeur)\n• search_contact : {\"action\":\"search_contact\",\"name\":\"nom\"}\n• Musique : play_music{query}, pause_music, stop_music, set_volume{level}\n• Agenda : today_events, upcoming_events{days}, create_event{title,startTime,calendar?} (calendar = surnom/nom/compte/ID, optionnel), search_event{query}, update_event{eventId,newTitle?,newStartTime?}, delete_event{eventId}, list_calendars (montre tous les agendas avec leur compte), name_calendar{calendarId,nickname}. Cherche l'ID via search_event/today_events avant de modifier/supprimer un événement.\n• Emails : read_emails, send_email{to,subject,body}, search_email{query} (sujet+corps+expéditeur), read_email_content{index}\n• Fichiers : list_files{path}, search_files{query}, read_file{path}, write_file{path,content}, rename_file{oldPath,newName}, copy_file{source,dest}, move_file{source,dest}, delete_file{path}, create_folder{path}, storage_info\n• get_location | open_maps{query} (itinéraire UNIQUEMENT) | web_search{query} (horaires/avis/infos pratiques — jamais open_maps pour ça)\n• get_notifications\n• GitHub : github_list_repos, github_create_repo{name,description,private}, github_create_file{owner,repo,path,content,message,branch} (sert aussi à modifier un fichier existant), github_read_file{owner,repo,path,branch}, github_create_branch{owner,repo,newBranch,fromBranch}, github_create_pr{owner,repo,title,head,base,body}. Plusieurs fichiers = plusieurs blocs [JARVIS_CMD] à la suite. Échappe \\n et \\\" dans content pour un JSON valide.\n• Contacts JARVIS (notes Obsidian, distinct du carnet natif) : save_contact_profile{name,category,phone?,email?,address?,notes?} (catégories : travail/personnel/famille/autre — propose spontanément d'enregistrer une info utile lue dans un SMS/email/agenda), search_contact_profile{query}, list_contacts_by_category{category}, delete_contact_profile{name}, navigate_to_contact{name} (itinéraire vers son adresse/GPS)\n• generate_image{prompt} : prompt en anglais, enrichi selon le style demandé (coloriage→\"black and white line art, coloring book, no color\"; cartoon→\"cartoon style, vector\"; photo→\"photorealistic, high detail\"; peinture→\"digital painting\"). Vidéo/musique non disponibles, dis-le honnêtement si demandé.\n• Bluetooth : bluetooth_info, enable_bluetooth, disable_bluetooth | Wi-Fi : wifi_info, enable_wifi, disable_wifi\n\nExemple : \"J'appelle Maman tout de suite. [JARVIS_CMD:{\"action\":\"call\",\"target\":\"Maman\"}]\""

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class ChatResult(val text: String, val imageBase64: String? = null, val imageMime: String? = null)

    // Nombre max de messages passés (utilisateur + assistant confondus) envoyés
    // à l'IA à chaque requête. Au-delà, l'historique complet est quand même
    // conservé et affiché dans le chat — seule la fenêtre envoyée à l'IA est
    // limitée, pour éviter d'envoyer des milliers de tokens inutiles sur une
    // conversation longue (surtout coûteux/lent pour les modèles locaux).
    private const val MAX_HISTORY_MESSAGES = 16

    private fun trimHistory(history: List<HistoryEntry>): List<HistoryEntry> =
        if (history.size <= MAX_HISTORY_MESSAGES) history else history.takeLast(MAX_HISTORY_MESSAGES)

    suspend fun sendChat(context: Context, fullHistory: List<HistoryEntry>): ChatResult =
        withContext(Dispatchers.IO) {
            val provider = Prefs.getProvider(context)
            val history = trimHistory(fullHistory)

            val rawResponse = try {
                dispatchToProvider(context, provider, history)
            } catch (e: Exception) {
                "Connexion impossible. Vérifiez les paramètres dans ⚙. Détail : ${e.message}"
            }

            // Exécution automatique des commandes système si présentes dans la réponse
            val commandResult = JarvisCommandParser.parseAndExecute(context, rawResponse)
            val cleanText = JarvisCommandParser.cleanResponse(rawResponse)
            val lastUserMsg = history.lastOrNull { it.role == "user" }?.text ?: ""

            when (commandResult) {
                is JarvisCommandParser.CommandResult.Executed -> {
                    val text = if (commandResult.isInformational) {
                        summarizeNaturally(context, provider, lastUserMsg, commandResult.outputMessage)
                    } else if (cleanText.isBlank()) {
                        commandResult.outputMessage
                    } else {
                        "$cleanText\n\n${commandResult.outputMessage}"
                    }
                    ChatResult(text, commandResult.imageBase64, commandResult.imageMime)
                }
                is JarvisCommandParser.CommandResult.ExecutedMultiple -> {
                    // Cas d'un projet à plusieurs fichiers / plusieurs actions d'un coup.
                    val combined = commandResult.results.joinToString("\n\n") { it.outputMessage }
                    val anyInformational = commandResult.results.any { it.isInformational }
                    val text = if (anyInformational) {
                        summarizeNaturally(context, provider, lastUserMsg, combined)
                    } else if (cleanText.isBlank()) {
                        combined
                    } else {
                        "$cleanText\n\n$combined"
                    }
                    val imageResult = commandResult.results.firstOrNull { it.imageBase64 != null }
                    ChatResult(text, imageResult?.imageBase64, imageResult?.imageMime)
                }
                JarvisCommandParser.CommandResult.None -> ChatResult(rawResponse)
            }
        }

    /** Demande à l'IA de reformuler naturellement un résultat brut de commande. */
    private suspend fun summarizeNaturally(
        context: Context,
        provider: Provider,
        userQuestion: String,
        rawOutput: String
    ): String {
        val summaryPrompt =
            "L'utilisateur a demandé : \"$userQuestion\"\n\n" +
                "Voici le résultat brut obtenu (ne le montre jamais tel quel, ni son formatage) :\n" +
                "$rawOutput\n\n" +
                "Réponds directement et naturellement à l'utilisateur avec cette information, " +
                "comme si tu venais de la consulter ou de la faire toi-même. Sois concis : si l'utilisateur a " +
                "demandé UNE seule chose (« le dernier SMS », « le dernier email »...), ne donne " +
                "que celle-là avec l'expéditeur et le contenu, sans lister le reste. Ne mentionne " +
                "jamais de commande système, d'action JSON ni de terme technique. N'utilise aucune mise " +
                "en forme markdown (pas d'astérisques, pas de tirets de liste, pas de dièses) : écris en " +
                "prose naturelle comme à l'oral."
        return try {
            val summary = dispatchToProvider(context, provider, listOf(HistoryEntry("user", summaryPrompt)))
            JarvisCommandParser.cleanResponse(summary).trim()
        } catch (e: Exception) {
            rawOutput // repli sur le résultat brut si la reformulation échoue
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
