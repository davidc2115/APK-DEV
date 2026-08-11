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
    // Catalogue de modèles — uniquement des entrées vérifiées manuellement
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

        // ─── Gemma (Google) — .task MediaPipe, licence Google (gating réel) ───
        ModelEntry(
            label        = "🟢 Gemma 3 1B — Google, officiel (550 Mo)",
            url          = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task",
            pageUrl      = "https://huggingface.co/litert-community/Gemma3-1B-IT",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~550 Mo",
            needsHfToken = true,
            creator      = "Google",
            description  = "Léger et rapide. Nécessite un compte HuggingFace + acceptation de la licence Gemma."
        ),
        // Miroir communautaire — ne nécessite pas de jeton (non-officiel, peut disparaître)
        ModelEntry(
            label        = "🟢 Gemma 3 1B — Miroir libre (550 Mo)",
            url          = "https://huggingface.co/Instamath-works/Gemma3-1B-IT-task/resolve/main/gemma3-1B-it-int4.task",
            pageUrl      = "https://huggingface.co/Instamath-works/Gemma3-1B-IT-task",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~550 Mo",
            needsHfToken = false,
            creator      = "Communauté",
            description  = "Miroir communautaire de Gemma 3 1B. Aucun compte requis. Peut être retiré sans préavis."
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
