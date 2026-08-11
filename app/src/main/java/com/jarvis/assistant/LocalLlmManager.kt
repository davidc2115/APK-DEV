package com.jarvis.assistant

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gestionnaire de modèles IA locaux — MediaPipe LLM Inference + ONNX Runtime.
 *
 * Formats supportés :
 *  - .task  → MediaPipe LLM Inference (Gemma 3 1B, Gemma 2B, LLaMA 3.2 converti)
 *  - .gguf  → Tenté via MediaPipe ; si échec : message d'aide clair
 *  - .onnx  → ONNX Runtime GenAI
 *
 * ⚠️ Note : La librairie llama.cpp native Android n'est pas disponible via
 * Maven public. Pour les fichiers .gguf, utilisez la version .task du modèle
 * (disponible sur Kaggle / Hugging Face via "MediaPipe LLM").
 */
object LocalLlmManager {

    private const val TAG = "LocalLlmManager"

    enum class LocalModelFormat { TASK, GGUF, ONNX }

    private var llmInference: LlmInference? = null
    private var loadedTaskPath: String? = null

    suspend fun generate(context: Context, modelPath: String, prompt: String): String =
        withContext(Dispatchers.Default) {
            val format = detectFormat(context, modelPath)
            Log.d(TAG, "Backend : $format — $modelPath")
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

    fun detectFormat(context: Context, modelPath: String): LocalModelFormat {
        return when {
            modelPath.endsWith(".gguf", ignoreCase = true) -> LocalModelFormat.GGUF
            modelPath.endsWith(".task", ignoreCase = true) -> LocalModelFormat.TASK
            modelPath.endsWith(".onnx", ignoreCase = true) -> LocalModelFormat.ONNX
            java.io.File(modelPath).isDirectory -> LocalModelFormat.ONNX
            else -> {
                val saved = Prefs.getLocalModelFormat(context)
                when (saved) {
                    "GGUF" -> LocalModelFormat.GGUF
                    "ONNX" -> LocalModelFormat.ONNX
                    else   -> LocalModelFormat.TASK
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Backend MediaPipe (.task) — Gemma, LLaMA 3.2, Phi-2
    // ─────────────────────────────────────────────────────────────────────────

    private fun generateTask(context: Context, modelPath: String, prompt: String): String {
        ensureTaskLoaded(context, modelPath)
        return llmInference?.generateResponse(prompt)
            ?: "❌ Erreur : modèle MediaPipe non chargé."
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
    // Backend GGUF — llama.cpp (Qwen2.5 et autres modèles open-source)
    // ─────────────────────────────────────────────────────────────────────────

    private var loadedGgufPath: String? = null

    private fun generateGguf(context: Context, modelPath: String, prompt: String): String {
        return try {
            if (loadedGgufPath != modelPath) {
                com.llamatik.LlamaBridge.initGenerateModel(modelPath)
                loadedGgufPath = modelPath
            }
            com.llamatik.LlamaBridge.generate(prompt)
        } catch (e: Exception) {
            """
❌ Erreur de chargement du modèle .gguf : ${e.message}

Vérifiez que le fichier est un modèle de langage quantifié compatible
llama.cpp (ex : Qwen2.5-*.gguf) et que le téléphone dispose d'assez
de mémoire libre.
""".trimIndent()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Backend ONNX Runtime GenAI (.onnx)
    // ─────────────────────────────────────────────────────────────────────────

    private fun generateOnnx(context: Context, modelPath: String, prompt: String): String {
        return try {
            val modelClass  = Class.forName("com.microsoft.onnxruntime.genai.Model")
            val tokClass    = Class.forName("com.microsoft.onnxruntime.genai.Tokenizer")
            val paramsClass = Class.forName("com.microsoft.onnxruntime.genai.GeneratorParams")
            val seqClass    = Class.forName("com.microsoft.onnxruntime.genai.Sequences")
            val genClass    = Class.forName("com.microsoft.onnxruntime.genai.Generator")

            val model     = modelClass.getConstructor(String::class.java).newInstance(modelPath)
            val tokenizer = tokClass.getConstructor(modelClass).newInstance(model)

            val inputSeqs = tokClass.getMethod("encode", String::class.java).invoke(tokenizer, prompt)
            val params    = paramsClass.getConstructor(modelClass).newInstance(model)
            paramsClass.getMethod("setInputSequences", seqClass).invoke(params, inputSeqs)
            paramsClass.getMethod("setSearchOption", String::class.java, Double::class.java)
                .invoke(params, "max_length", 512.0)

            val gen = genClass.getConstructor(modelClass, paramsClass).newInstance(model, params)
            val isDone  = genClass.getMethod("isDone")
            val logits  = genClass.getMethod("computeLogits")
            val nextTok = genClass.getMethod("generateNextToken")
            val getSeq  = genClass.getMethod("getSequence", Int::class.java)

            while (!(isDone.invoke(gen) as Boolean)) {
                logits.invoke(gen)
                nextTok.invoke(gen)
            }
            val outSeqs = getSeq.invoke(gen, 0)
            (tokClass.getMethod("decode", outSeqs!!.javaClass).invoke(tokenizer, outSeqs) as? String)?.trim()
                ?: "Réponse vide du modèle ONNX."
        } catch (e: ClassNotFoundException) {
            "⚠️ ONNX Runtime non trouvé dans cette version de l'app."
        } catch (e: Exception) {
            throw e
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    fun unload() {
        llmInference?.close()
        llmInference = null
        loadedTaskPath = null
        loadedGgufPath = null
    }

    private fun buildErrorMessage(format: LocalModelFormat, e: Exception): String {
        val name = when (format) {
            LocalModelFormat.TASK -> ".task (MediaPipe)"
            LocalModelFormat.GGUF -> ".gguf"
            LocalModelFormat.ONNX -> ".onnx (ONNX Runtime)"
        }
        return "❌ Erreur modèle local ($name) : ${e.message}\n\nVérifiez que le fichier est valide et que le téléphone dispose d'assez de RAM (min 3 Go)."
    }
}
