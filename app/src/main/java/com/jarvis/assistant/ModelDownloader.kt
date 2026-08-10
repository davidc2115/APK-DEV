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
 * Télécharge des modèles IA locaux depuis HuggingFace ou toute URL personnalisée.
 *
 * Formats supportés :
 *  - .task  — MediaPipe LLM Inference (Gemma 3 1B, Gemma 2B…)
 *  - .gguf  — llama.cpp (Phi-3, Mistral 7B, LLaMA 3.2, Gemma 2B…)
 *  - .onnx  — ONNX Runtime GenAI (Phi-3 Mini…)
 *
 * Les modèles Gemma sont soumis à une licence Google : un jeton HuggingFace
 * gratuit est nécessaire après acceptation de la licence sur huggingface.co.
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
        val url: String,
        val format: LocalLlmManager.LocalModelFormat,
        val sizeHint: String,
        val needsHfToken: Boolean = false,
        val description: String = ""
    )

    val MODEL_CATALOG: List<ModelEntry> = listOf(
        // ── Format .task (MediaPipe) ──────────────────────────────────────────
        ModelEntry(
            label = "Gemma 3 1B INT4 — officiel Google (≈550 Mo)",
            url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task?download=true",
            format = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint = "~550 Mo",
            needsHfToken = true,
            description = "Modèle officiel Google, léger et rapide. Jeton HF requis (licence Gemma)."
        ),
        ModelEntry(
            label = "Gemma 3 1B (miroir communautaire, sans compte)",
            url = "https://huggingface.co/Instamath-works/Gemma3-1B-IT-task/resolve/main/gemma3-1B-it-int4.task?download=true",
            format = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint = "~550 Mo",
            needsHfToken = false,
            description = "Non officiel. Peut disparaître sans préavis."
        ),

        // ── Format .gguf (llama.cpp) ──────────────────────────────────────────
        ModelEntry(
            label = "Phi-3 Mini 4K Q4_K_M GGUF (≈2.2 Go)",
            url = "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf?download=true",
            format = LocalLlmManager.LocalModelFormat.GGUF,
            sizeHint = "~2.2 Go",
            needsHfToken = false,
            description = "Microsoft Phi-3 Mini — excellent pour le raisonnement, Q4_K_M."
        ),
        ModelEntry(
            label = "Llama 3.2 3B Q4_K_M GGUF (≈2.0 Go)",
            url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf?download=true",
            format = LocalLlmManager.LocalModelFormat.GGUF,
            sizeHint = "~2.0 Go",
            needsHfToken = false,
            description = "Meta LLaMA 3.2 3B — très bon rapport qualité/taille."
        ),
        ModelEntry(
            label = "Gemma 2 2B Q4_K_M GGUF (≈1.6 Go)",
            url = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf?download=true",
            format = LocalLlmManager.LocalModelFormat.GGUF,
            sizeHint = "~1.6 Go",
            needsHfToken = false,
            description = "Google Gemma 2 2B — compact et performant."
        ),
        ModelEntry(
            label = "Mistral 7B Instruct Q4_K_M GGUF (≈4.1 Go)",
            url = "https://huggingface.co/TheBloke/Mistral-7B-Instruct-v0.2-GGUF/resolve/main/mistral-7b-instruct-v0.2.Q4_K_M.gguf?download=true",
            format = LocalLlmManager.LocalModelFormat.GGUF,
            sizeHint = "~4.1 Go",
            needsHfToken = false,
            description = "Mistral 7B — puissant, nécessite 6+ Go de RAM disponible."
        )
    )

    // Rétrocompatibilité avec l'ancienne API
    val RECOMMENDED_MODEL_URL = MODEL_CATALOG[0].url
    val RECOMMENDED_MODEL_LABEL = MODEL_CATALOG[0].label
    val NO_KEY_MODEL_URL = MODEL_CATALOG[1].url
    val NO_KEY_MODEL_LABEL = MODEL_CATALOG[1].label

    // ─────────────────────────────────────────────────────────────────────────
    // Téléchargement
    // ─────────────────────────────────────────────────────────────────────────

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Pas de timeout pour les gros modèles
        .build()

    /**
     * Télécharge un modèle depuis [url].
     * Le fichier est sauvegardé dans le répertoire privé de l'app
     * sous le nom déduit du format : local_model.task / local_model.gguf / local_model.onnx.
     */
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
                if (!response.isSuccessful) {
                    onProgress(
                        Progress.Error(
                            "Échec (${response.code}). Vérifie ton jeton Hugging Face et que tu as " +
                                "accepté la licence du modèle sur sa page huggingface.co."
                        )
                    )
                    return@withContext
                }

                val body = response.body
                if (body == null) {
                    onProgress(Progress.Error("Réponse vide du serveur."))
                    return@withContext
                }

                val totalBytes = body.contentLength()
                val extension = when (format) {
                    LocalLlmManager.LocalModelFormat.GGUF -> "gguf"
                    LocalLlmManager.LocalModelFormat.ONNX -> "onnx"
                    LocalLlmManager.LocalModelFormat.TASK -> "task"
                }
                val destFile = File(context.filesDir, "local_model.$extension")
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

    /** Surcharge rétrocompatible (sans format explicite → TASK). */
    suspend fun download(
        context: Context,
        url: String,
        hfToken: String,
        onProgress: (Progress) -> Unit
    ) = download(context, url, hfToken, LocalLlmManager.LocalModelFormat.TASK, onProgress)
}
