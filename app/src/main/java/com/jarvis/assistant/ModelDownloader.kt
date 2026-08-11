package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Télécharge des modèles IA locaux pour JARVIS.
 *
 * ## Pourquoi certains modèles nécessitent-ils un compte ?
 * Les modèles LLM (Gemma, LLaMA, Phi, Mistral) sont soumis à des licences
 * spécifiques par leurs créateurs (Google, Meta, Microsoft, Mistral AI).
 * HuggingFace et Kaggle imposent l'acceptation de ces licences via un compte.
 *
 * ## Comment télécharger sans compte (méthode recommandée) :
 * 1. Cliquez sur "Ouvrir la page de téléchargement" dans JARVIS.
 * 2. Le navigateur s'ouvre sur la page du modèle (HuggingFace ou Kaggle).
 * 3. Téléchargez le fichier .task manuellement (le navigateur gère la session).
 * 4. Revenez dans JARVIS → Modèles Locaux → "Importer un fichier .task".
 *
 * ## Téléchargement automatique (méthode avancée) :
 * Générez un jeton gratuit sur huggingface.co/settings/tokens
 * et collez-le dans le champ "Jeton HuggingFace" ci-dessus.
 */
object ModelDownloader {

    sealed class Progress {
        data class Percent(val value: Int) : Progress()
        data class Done(val file: File) : Progress()
        data class Error(val message: String) : Progress()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Catalogue de modèles
    // ─────────────────────────────────────────────────────────────────────────

    data class ModelEntry(
        val label: String,
        val url: String,                   // URL de téléchargement direct (HF API)
        val pageUrl: String,               // Page web à ouvrir dans le navigateur
        val format: LocalLlmManager.LocalModelFormat,
        val sizeHint: String,
        val needsHfToken: Boolean = false,
        val description: String = "",
        val creator: String = ""
    )

    val MODEL_CATALOG: List<ModelEntry> = listOf(

        // ─── Gemma (Google) ─────────────────────────────────────────────────
        // Licence Google Gemma — compte HuggingFace ou Kaggle requis
        ModelEntry(
            label        = "🟢 Gemma 3 1B — Google (550 Mo)",
            url          = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task",
            pageUrl      = "https://huggingface.co/litert-community/Gemma3-1B-IT",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~550 Mo",
            needsHfToken = true,
            creator      = "Google",
            description  = "Léger et rapide. Meilleur rapport taille/qualité. Licence Google Gemma."
        ),
        ModelEntry(
            label        = "🟢 Gemma 2 2B — Google (1.1 Go)",
            url          = "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/gemma2-2b-it-cpu-int4.task",
            pageUrl      = "https://huggingface.co/litert-community/Gemma2-2B-IT",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~1.1 Go",
            needsHfToken = true,
            creator      = "Google",
            description  = "Plus précis que 1B. Requiert 4 Go RAM. Licence Google Gemma."
        ),
        // Miroir communautaire — ne nécessite pas de jeton (non-officiel)
        ModelEntry(
            label        = "🟢 Gemma 3 1B — Miroir libre (550 Mo)",
            url          = "https://huggingface.co/Instamath-works/Gemma3-1B-IT-task/resolve/main/gemma3-1B-it-int4.task",
            pageUrl      = "https://huggingface.co/Instamath-works/Gemma3-1B-IT-task",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~550 Mo",
            needsHfToken = false,
            creator      = "Communauté",
            description  = "Miroir communautaire de Gemma 3 1B. Aucun compte requis. Peut être retiré sans préavis."
        ),

        // ─── LLaMA (Meta) ───────────────────────────────────────────────────
        // Licence Meta LLaMA — compte HuggingFace requis + acceptation licence
        ModelEntry(
            label        = "🦙 LLaMA 3.2 1B — Meta (700 Mo)",
            url          = "https://huggingface.co/litert-community/Llama-3.2-1B-Instruct/resolve/main/llama-3.2-1b-it-int4.task",
            pageUrl      = "https://huggingface.co/litert-community/Llama-3.2-1B-Instruct",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~700 Mo",
            needsHfToken = true,
            creator      = "Meta",
            description  = "LLaMA 3.2 1B Instruct — performant en français. Licence Meta."
        ),
        ModelEntry(
            label        = "🦙 LLaMA 3.2 3B — Meta (2.0 Go)",
            url          = "https://huggingface.co/litert-community/Llama-3.2-3B-Instruct/resolve/main/llama-3.2-3b-it-int4.task",
            pageUrl      = "https://huggingface.co/litert-community/Llama-3.2-3B-Instruct",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~2.0 Go",
            needsHfToken = true,
            creator      = "Meta",
            description  = "Meilleure qualité. 4 Go RAM requis. Licence Meta."
        ),

        // ─── Phi (Microsoft) ────────────────────────────────────────────────
        // Phi-2 et Phi-3 Mini sont sous licence MIT — libres !
        ModelEntry(
            label        = "🔷 Phi-2 — Microsoft LIBRE (800 Mo)",
            url          = "https://huggingface.co/litert-community/phi-2/resolve/main/phi-2-int4.task",
            pageUrl      = "https://huggingface.co/litert-community/phi-2",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~800 Mo",
            needsHfToken = false,
            creator      = "Microsoft",
            description  = "Phi-2 — licence MIT ouverte. Bon pour raisonnement logique. 3 Go RAM min."
        ),
        ModelEntry(
            label        = "🔷 Phi-3 Mini 4K — Microsoft (2.2 Go)",
            url          = "https://huggingface.co/litert-community/Phi-3-mini-4k-instruct/resolve/main/phi-3-mini-4k-it-int4.task",
            pageUrl      = "https://huggingface.co/litert-community/Phi-3-mini-4k-instruct",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~2.2 Go",
            needsHfToken = false,
            creator      = "Microsoft",
            description  = "Phi-3 Mini — licence MIT. Excellent raisonnement. 4 Go RAM requis."
        ),

        // ─── Mistral AI ─────────────────────────────────────────────────────
        // Apache 2.0 — licence libre !
        ModelEntry(
            label        = "⚡ Mistral 7B Instruct — LIBRE (4.1 Go)",
            url          = "https://huggingface.co/litert-community/Mistral-7B-Instruct-v0.3/resolve/main/mistral-7b-it-int4.task",
            pageUrl      = "https://huggingface.co/litert-community/Mistral-7B-Instruct-v0.3",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~4.1 Go",
            needsHfToken = false,
            creator      = "Mistral AI",
            description  = "Licence Apache 2.0. Puissant et multilingue. 6+ Go RAM requis."
        ),

        // ─── Falcon (TII) ───────────────────────────────────────────────────
        // Apache 2.0 — licence libre !
        ModelEntry(
            label        = "🦅 Falcon 1B — TII LIBRE (600 Mo)",
            url          = "https://huggingface.co/litert-community/falcon-1b/resolve/main/falcon-1b-int4.task",
            pageUrl      = "https://huggingface.co/litert-community/falcon-1b",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~600 Mo",
            needsHfToken = false,
            creator      = "TII",
            description  = "Falcon 1B — licence Apache 2.0. Ultra léger. Idéal pour appareils modestes."
        )
    )

    // Rétrocompatibilité
    val RECOMMENDED_MODEL_URL   = MODEL_CATALOG[0].url
    val RECOMMENDED_MODEL_LABEL = MODEL_CATALOG[0].label
    val NO_KEY_MODEL_URL        = MODEL_CATALOG[2].url   // miroir Gemma sans token
    val NO_KEY_MODEL_LABEL      = MODEL_CATALOG[2].label

    // ─────────────────────────────────────────────────────────────────────────
    // Téléchargement automatique (avec ou sans jeton HF)
    // ─────────────────────────────────────────────────────────────────────────

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun download(
        context: Context,
        url: String,
        hfToken: String,
        format: LocalLlmManager.LocalModelFormat = LocalLlmManager.LocalModelFormat.TASK,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = Request.Builder().url(url)
            if (hfToken.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $hfToken")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> {
                        onProgress(Progress.Error(
                            "🔒 Accès refusé (${response.code}).\n\n" +
                            "Ce modèle nécessite un compte HuggingFace.\n" +
                            "→ Appuyez sur \"Ouvrir dans le navigateur\" pour télécharger manuellement.\n" +
                            "→ Ou générez un jeton gratuit sur huggingface.co/settings/tokens"
                        ))
                        return@withContext
                    }
                    !response.isSuccessful -> {
                        onProgress(Progress.Error("Échec (${response.code}) : ${response.message}"))
                        return@withContext
                    }
                }

                val body = response.body ?: run {
                    onProgress(Progress.Error("Réponse vide du serveur."))
                    return@withContext
                }

                val extension = when (format) {
                    LocalLlmManager.LocalModelFormat.GGUF -> "gguf"
                    LocalLlmManager.LocalModelFormat.ONNX -> "onnx"
                    LocalLlmManager.LocalModelFormat.TASK -> "task"
                }
                val destFile = File(context.filesDir, "local_model.$extension")
                val totalBytes = body.contentLength()
                var downloaded = 0L
                var lastPercent = -1

                body.byteStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(1024 * 256)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val percent = ((downloaded * 100) / totalBytes).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(Progress.Percent(percent))
                                }
                            }
                        }
                    }
                }

                Prefs.saveLocalModelPath(context, destFile.absolutePath)
                Prefs.saveLocalModelFormat(context, format.name)
                LocalLlmManager.unload()
                onProgress(Progress.Done(destFile))
            }
        } catch (e: Exception) {
            onProgress(Progress.Error("Erreur réseau : ${e.message}"))
        }
    }

    /** Surcharge rétrocompatible. */
    suspend fun download(
        context: Context,
        url: String,
        hfToken: String,
        onProgress: (Progress) -> Unit
    ) = download(context, url, hfToken, LocalLlmManager.LocalModelFormat.TASK, onProgress)
}
