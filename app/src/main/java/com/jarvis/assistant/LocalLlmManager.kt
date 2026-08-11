package com.jarvis.assistant

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gestionnaire de modèles IA locaux — multi-backend (llama.cpp, MediaPipe, ONNX).
 *
 * Supporte :
 *  - GGUF (.gguf)  → LLaMA 3.2, Mistral 7B, Phi-3, Gemma (llama.cpp)
 *  - TASK (.task)  → MediaPipe LLM Inference (Gemma 3 1B, Gemma 2B)
 *  - ONNX (.onnx)  → ONNX Runtime GenAI
 */
object LocalLlmManager {

    private const val TAG = "LocalLlmManager"

    enum class LocalModelFormat { TASK, GGUF, ONNX }

    private var llmInference: LlmInference? = null
    private var loadedTaskPath: String? = null

    private var llamaContext: Any? = null
    private var loadedGgufPath: String? = null

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
                // Fallback sur MediaPipe si la lib native GGUF spécifique n'est pas chargée
                if (format == LocalModelFormat.GGUF) {
                    try {
                        return@withContext generateTask(context, modelPath, prompt)
                    } catch (_: Exception) {}
                }
                buildErrorMessage(format, e)
            }
        }

    fun detectFormat(context: Context, modelPath: String): LocalModelFormat {
        val savedFormat = Prefs.getLocalModelFormat(context)
        return when {
            modelPath.endsWith(".gguf", ignoreCase = true) -> LocalModelFormat.GGUF
            modelPath.endsWith(".task", ignoreCase = true) -> LocalModelFormat.TASK
            modelPath.endsWith(".onnx", ignoreCase = true) -> LocalModelFormat.ONNX
            savedFormat == "GGUF" -> LocalModelFormat.GGUF
            savedFormat == "ONNX" -> LocalModelFormat.ONNX
            else -> LocalModelFormat.TASK
        }
    }

    private fun generateTask(context: Context, modelPath: String, prompt: String): String {
        ensureTaskLoaded(context, modelPath)
        return llmInference?.generateResponse(prompt)
            ?: "Erreur : le modèle local MediaPipe n'a pas pu être chargé."
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

    private fun generateGguf(context: Context, modelPath: String, prompt: String): String {
        return try {
            ensureGgufLoaded(modelPath)
            val ctx = llamaContext
                ?: return generateTask(context, modelPath, prompt)

            val generateMethod = ctx.javaClass.getMethod(
                "generate", String::class.java, Int::class.java, Boolean::class.java
            )
            val result = generateMethod.invoke(ctx, prompt, 512, false) as? String
            result?.trim() ?: "Réponse vide du modèle Llama GGUF."
        } catch (e: Exception) {
            // Fallback fluide sur le moteur MediaPipe si la réflexion échoue
            generateTask(context, modelPath, prompt)
        }
    }

    private fun ensureGgufLoaded(modelPath: String) {
        if (llamaContext != null && loadedGgufPath == modelPath) return
        unloadGguf()

        val possibleClassNames = listOf(
            "com.shubham0204.ml.llama_android.LlamaContext",
            "com.shubham0204.llama_android.LlamaContext",
            "com.llama.cpp.LlamaContext"
        )

        for (className in possibleClassNames) {
            try {
                val llamaClass = Class.forName(className)
                val createMethod = llamaClass.getMethod(
                    "create",
                    String::class.java,
                    Int::class.java,
                    Int::class.java,
                    Int::class.java
                )
                llamaContext = createMethod.invoke(null, modelPath, 4, 2048, 512)
                loadedGgufPath = modelPath
                return
            } catch (_: Exception) {}
        }
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

    private fun generateOnnx(context: Context, modelPath: String, prompt: String): String {
        return try {
            val modelClass = Class.forName("com.microsoft.onnxruntime.genai.Model")
            val tokenizerClass = Class.forName("com.microsoft.onnxruntime.genai.Tokenizer")
            val generatorParamsClass = Class.forName("com.microsoft.onnxruntime.genai.GeneratorParams")
            val sequencesClass = Class.forName("com.microsoft.onnxruntime.genai.Sequences")
            val generatorClass = Class.forName("com.microsoft.onnxruntime.genai.Generator")

            val model = modelClass.getConstructor(String::class.java).newInstance(modelPath)
            val tokenizer = tokenizerClass.getConstructor(modelClass).newInstance(model)

            val encodeMethod = tokenizerClass.getMethod("encode", String::class.java)
            val inputSeqs = encodeMethod.invoke(tokenizer, prompt)

            val params = generatorParamsClass.getConstructor(modelClass).newInstance(model)
            val setInputSeqsMethod = generatorParamsClass.getMethod("setInputSequences", sequencesClass)
            setInputSeqsMethod.invoke(params, inputSeqs)
            generatorParamsClass.getMethod("setSearchOption", String::class.java, Double::class.java)
                .invoke(params, "max_length", 512.0)

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
        } catch (e: Exception) {
            throw e
        }
    }

    fun unload() {
        llmInference?.close()
        llmInference = null
        loadedTaskPath = null
        unloadGguf()
    }

    private fun buildErrorMessage(format: LocalModelFormat, e: Exception): String {
        val formatName = when (format) {
            LocalModelFormat.TASK -> ".task (MediaPipe)"
            LocalModelFormat.GGUF -> ".gguf (LLaMA / llama.cpp)"
            LocalModelFormat.ONNX -> ".onnx (ONNX Runtime)"
        }
        return "Erreur du modèle local ($formatName) : ${e.message}\n\n" +
            "Vérifiez que le fichier est présent sur l'appareil et que le téléphone dispose de suffisamment de RAM (min 3 Go)."
    }
}
