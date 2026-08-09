package com.jarvis.assistant

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exécute un modèle de langage directement sur l'appareil, sans aucun réseau.
 * Le modèle doit être un fichier .task compatible MediaPipe LLM Inference
 * (ex: Gemma 2B/3 1B au format .task, téléchargé manuellement par l'utilisateur).
 */
object LocalLlmManager {

    private var llmInference: LlmInference? = null
    private var loadedModelPath: String? = null

    suspend fun generate(context: Context, modelPath: String, prompt: String): String =
        withContext(Dispatchers.Default) {
            try {
                ensureLoaded(context, modelPath)
                llmInference?.generateResponse(prompt)
                    ?: "Erreur : le modèle local n'a pas pu être chargé."
            } catch (e: Exception) {
                "Erreur du modèle local : ${e.message}\n\n" +
                    "Vérifiez que le fichier .task est bien un modèle compatible " +
                    "MediaPipe LLM Inference (ex: Gemma converti au format .task) " +
                    "et que le téléphone dispose d'assez de mémoire libre."
            }
        }

    private fun ensureLoaded(context: Context, modelPath: String) {
        if (llmInference != null && loadedModelPath == modelPath) return

        llmInference?.close()
        llmInference = null

        val options = LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(1024)
            .build()

        llmInference = LlmInference.createFromOptions(context, options)
        loadedModelPath = modelPath
    }

    fun unload() {
        llmInference?.close()
        llmInference = null
        loadedModelPath = null
    }
}
