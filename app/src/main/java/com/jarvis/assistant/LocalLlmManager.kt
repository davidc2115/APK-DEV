package com.jarvis.assistant

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gestionnaire de modèles IA locaux — multi-backend.
 *
 * Supporte 3 formats :
 *  - TASK (.task)  → MediaPipe LLM Inference (Gemma 2B/3 1B, etc.)
 *  - GGUF (.gguf)  → llama.cpp via llama-android (Mistral, Phi-3, LLaMA, etc.)
 *  - ONNX (.onnx / dossier ONNX) → ONNX Runtime GenAI (Phi-3, etc.)
 *
 * Le backend est sélectionné automatiquement selon le chemin du fichier
 * (extension ou provider) ou peut être forcé via [LocalModelFormat].
 */
object LocalLlmManager {

    private const val TAG = "LocalLlmManager"

    /** Format / backend pour le modèle embarqué. */
    enum class LocalModelFormat { TASK, GGUF, ONNX }

    // ── State MediaPipe (.task) ───────────────────────────────────────────────
    private var llmInference: LlmInference? = null
    private var loadedTaskPath: String? = null

    // ── State llama.cpp (.gguf) ───────────────────────────────────────────────
    // La classe LlamaContext sera chargée via réflexion si la lib est présente.
    private var llamaContext: Any? = null
    private var loadedGgufPath: String? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Point d'entrée principal
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun generate(context: Context, modelPath: String, prompt: String): String =
        withContext(Dispatchers.Default) {
            val format = detectFormat(context, modelPath)
            Log.d(TAG, "Backend détecté : $format pour $modelPath")
            try {
                when (format) {
                    LocalModelFormat.TASK -> generateTask(context, modelPath, prompt)
                    LocalModelFormat.GGUF -> generateGguf(context, modelPath, prompt)
                    LocalModelFormat.ONNX -> generateOnnx(context, modelPath, prompt)
                }
            } catch (e: Exception) {
                buildErrorMessage(format, e)
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Détection automatique du format
    // ─────────────────────────────────────────────────────────────────────────

    fun detectFormat(context: Context, modelPath: String): LocalModelFormat {
        // 1. Vérifier le provider choisi dans les prefs (priorité)
        val savedFormat = Prefs.getLocalModelFormat(context)
        return when (savedFormat) {
            "GGUF" -> LocalModelFormat.GGUF
            "ONNX" -> LocalModelFormat.ONNX
            "TASK" -> LocalModelFormat.TASK
            else -> {
                // 2. Déduction depuis l'extension du fichier
                when {
                    modelPath.endsWith(".gguf", ignoreCase = true) -> LocalModelFormat.GGUF
                    modelPath.endsWith(".onnx", ignoreCase = true) -> LocalModelFormat.ONNX
                    modelPath.endsWith(".task", ignoreCase = true) -> LocalModelFormat.TASK
                    // 3. Fallback : dossier ONNX ou MediaPipe
                    java.io.File(modelPath).isDirectory -> LocalModelFormat.ONNX
                    else -> LocalModelFormat.TASK
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Backend MediaPipe (.task)
    // ─────────────────────────────────────────────────────────────────────────

    private fun generateTask(context: Context, modelPath: String, prompt: String): String {
        ensureTaskLoaded(context, modelPath)
        return llmInference?.generateResponse(prompt)
            ?: "Erreur : le modèle .task n'a pas pu être chargé."
    }

    private fun ensureTaskLoaded(context: Context, modelPath: String) {
        if (llmInference != null && loadedTaskPath == modelPath) return
        llmInference?.close()
        llmInference = null

        val options = LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(1024)
            .build()

        llmInference = LlmInference.createFromOptions(context, options)
        loadedTaskPath = modelPath
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Backend llama.cpp (.gguf) — via llama-android
    // ─────────────────────────────────────────────────────────────────────────

    private fun generateGguf(context: Context, modelPath: String, prompt: String): String {
        // Utilisation de la librairie llama-android (com.github.shubham0204:llama.android)
        // Chargement via réflexion pour éviter une erreur de compilation si la lib est absente.
        return try {
            ensureGgufLoaded(modelPath)
            val ctx = llamaContext
                ?: return "Erreur : le contexte GGUF n'a pas pu être initialisé."

            // Appel de la méthode generate() de LlamaContext
            val generateMethod = ctx.javaClass.getMethod(
                "generate", String::class.java, Int::class.java, Boolean::class.java
            )
            val result = generateMethod.invoke(ctx, prompt, 512, false) as? String
            result?.trim() ?: "Réponse vide du modèle GGUF."
        } catch (e: ClassNotFoundException) {
            "⚠️ Librairie llama-android non trouvée. Vérifie les dépendances du build."
        } catch (e: Exception) {
            throw e // re-lancé pour être capturé par generate()
        }
    }

    private fun ensureGgufLoaded(modelPath: String) {
        if (llamaContext != null && loadedGgufPath == modelPath) return

        // Décharge le contexte précédent si nécessaire
        unloadGguf()

        // Chargement dynamique de LlamaContext
        val llamaClass = Class.forName("com.shubham0204.ml.llama_android.LlamaContext")
        val createMethod = llamaClass.getMethod(
            "create",
            String::class.java,
            Int::class.java, // nThreads
            Int::class.java, // nCtx
            Int::class.java  // nBatch
        )
        llamaContext = createMethod.invoke(null, modelPath, 4, 2048, 512)
        loadedGgufPath = modelPath
    }

    private fun unloadGguf() {
        try {
            val ctx = llamaContext ?: return
            val closeMethod = ctx.javaClass.getMethod("close")
            closeMethod.invoke(ctx)
        } catch (_: Exception) {}
        llamaContext = null
        loadedGgufPath = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Backend ONNX Runtime GenAI (.onnx)
    // ─────────────────────────────────────────────────────────────────────────

    private fun generateOnnx(context: Context, modelPath: String, prompt: String): String {
        return try {
            // ONNX Runtime GenAI — API reflective pour éviter dépendance obligatoire
            val modelClass = Class.forName("com.microsoft.onnxruntime.genai.Model")
            val tokenizerClass = Class.forName("com.microsoft.onnxruntime.genai.Tokenizer")
            val generatorParamsClass = Class.forName("com.microsoft.onnxruntime.genai.GeneratorParams")
            val sequencesClass = Class.forName("com.microsoft.onnxruntime.genai.Sequences")
            val generatorClass = Class.forName("com.microsoft.onnxruntime.genai.Generator")

            val model = modelClass.getConstructor(String::class.java).newInstance(modelPath)
            val tokenizer = tokenizerClass.getConstructor(modelClass).newInstance(model)

            // Encode le prompt
            val encodeMethod = tokenizerClass.getMethod("encode", String::class.java)
            val inputSeqs = encodeMethod.invoke(tokenizer, prompt)

            // Params de génération
            val params = generatorParamsClass.getConstructor(modelClass).newInstance(model)
            val setInputSeqsMethod = generatorParamsClass.getMethod(
                "setInputSequences", sequencesClass
            )
            setInputSeqsMethod.invoke(params, inputSeqs)
            generatorParamsClass.getMethod("setSearchOption", String::class.java, Double::class.java)
                .invoke(params, "max_length", 512.0)

            // Génération
            val generator = generatorClass.getConstructor(modelClass, generatorParamsClass)
                .newInstance(model, params)
            val isDoneMethod = generatorClass.getMethod("isDone")
            val computeLogitsMethod = generatorClass.getMethod("computeLogits")
            val generateNextTokenMethod = generatorClass.getMethod("generateNextToken")
            val getSequenceMethod = generatorClass.getMethod("getSequence", Int::class.java)

            while (!(isDoneMethod.invoke(generator) as Boolean)) {
                computeLogitsMethod.invoke(generator)
                generateNextTokenMethod.invoke(generator)
            }

            val outputSeqs = getSequenceMethod.invoke(generator, 0)
            val decodeMethod = tokenizerClass.getMethod("decode", outputSeqs!!.javaClass)
            (decodeMethod.invoke(tokenizer, outputSeqs) as? String)?.trim()
                ?: "Réponse vide du modèle ONNX."
        } catch (e: ClassNotFoundException) {
            "⚠️ ONNX Runtime GenAI non trouvé. Vérifie les dépendances du build."
        } catch (e: Exception) {
            throw e
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilitaires
    // ─────────────────────────────────────────────────────────────────────────

    fun unload() {
        llmInference?.close()
        llmInference = null
        loadedTaskPath = null
        unloadGguf()
    }

    private fun buildErrorMessage(format: LocalModelFormat, e: Exception): String {
        val formatName = when (format) {
            LocalModelFormat.TASK -> ".task (MediaPipe)"
            LocalModelFormat.GGUF -> ".gguf (llama.cpp)"
            LocalModelFormat.ONNX -> ".onnx (ONNX Runtime)"
        }
        return "Erreur du modèle local ($formatName) : ${e.message}\n\n" +
            when (format) {
                LocalModelFormat.TASK ->
                    "Vérifiez que le fichier est un modèle MediaPipe LLM Inference " +
                        "(ex: Gemma 3 1B converti au format .task) et que l'appareil a assez de mémoire."
                LocalModelFormat.GGUF ->
                    "Vérifiez que le fichier .gguf est valide (format llama.cpp Q4_K_M recommandé) " +
                        "et que l'appareil dispose d'au moins 3 Go de RAM disponible."
                LocalModelFormat.ONNX ->
                    "Vérifiez que le dossier contient bien un modèle ONNX Runtime GenAI " +
                        "(fichiers model.onnx, tokenizer.json, etc.)."
            }
    }
}
