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

        // ─── Gemma ──────────────────────────────────────────────────────────
        ModelEntry(
            label        = "🟢 Gemma 3 1B — Google (recommandé)",
            url          = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task?download=true",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~550 Mo",
            needsHfToken = true,
            description  = "Léger, rapide, multilingue. Jeton HuggingFace requis (licence Google Gemma)."
        ),
        ModelEntry(
            label        = "🟢 Gemma 3 1B — miroir sans compte",
            url          = "https://huggingface.co/Instamath-works/Gemma3-1B-IT-task/resolve/main/gemma3-1B-it-int4.task?download=true",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~550 Mo",
            needsHfToken = false,
            description  = "Miroir communautaire. Aucun compte requis."
        ),
        ModelEntry(
            label        = "🟢 Gemma 2 2B — Google",
            url          = "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/gemma2-2b-it-cpu-int4.task?download=true",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~1.1 Go",
            needsHfToken = true,
            description  = "Plus précis que 1B. Jeton HF requis."
        ),

        // ─── LLaMA ──────────────────────────────────────────────────────────
        ModelEntry(
            label        = "🦙 LLaMA 3.2 1B — Meta",
            url          = "https://huggingface.co/litert-community/Llama-3.2-1B-Instruct/resolve/main/llama-3.2-1b-it-int4.task?download=true",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~700 Mo",
            needsHfToken = false,
            description  = "Meta LLaMA 3.2 1B Instruct — performant en français."
        ),
        ModelEntry(
            label        = "🦙 LLaMA 3.2 3B — Meta",
            url          = "https://huggingface.co/litert-community/Llama-3.2-3B-Instruct/resolve/main/llama-3.2-3b-it-int4.task?download=true",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~2.0 Go",
            needsHfToken = false,
            description  = "Meilleure qualité. Nécessite 4 Go de RAM disponible."
        ),

        // ─── Phi ────────────────────────────────────────────────────────────
        ModelEntry(
            label        = "🔷 Phi-3 Mini 4K — Microsoft",
            url          = "https://huggingface.co/litert-community/Phi-3-mini-4k-instruct/resolve/main/phi-3-mini-4k-it-int4.task?download=true",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~2.2 Go",
            needsHfToken = false,
            description  = "Microsoft Phi-3 Mini — excellent raisonnement logique."
        ),
        ModelEntry(
            label        = "🔷 Phi-2 — Microsoft (léger)",
            url          = "https://huggingface.co/litert-community/phi-2/resolve/main/phi-2-int4.task?download=true",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~800 Mo",
            needsHfToken = false,
            description  = "Phi-2 compact — bon pour appareils avec 3 Go de RAM."
        ),

        // ─── Mistral ────────────────────────────────────────────────────────
        ModelEntry(
            label        = "⚡ Mistral 7B Instruct — Mistral AI",
            url          = "https://huggingface.co/litert-community/Mistral-7B-Instruct-v0.3/resolve/main/mistral-7b-it-int4.task?download=true",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~4.1 Go",
            needsHfToken = false,
            description  = "Puissant, multilingue. Nécessite 6+ Go de RAM disponible."
        ),

        // ─── Falcon ─────────────────────────────────────────────────────────
        ModelEntry(
            label        = "🦅 Falcon 1B — TII (ultra léger)",
            url          = "https://huggingface.co/litert-community/falcon-1b/resolve/main/falcon-1b-int4.task?download=true",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~600 Mo",
            needsHfToken = false,
            description  = "Falcon 1B — ultra léger, idéal pour appareils modestes."
        )
    )

    // Rétrocompatibilité
    val RECOMMENDED_MODEL_URL   = MODEL_CATALOG[0].url
    val RECOMMENDED_MODEL_LABEL = MODEL_CATALOG[0].label
    val NO_KEY_MODEL_URL        = MODEL_CATALOG[1].url
    val NO_KEY_MODEL_LABEL      = MODEL_CATALOG[1].label


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
