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

    enum class LocalModelFormat { TASK, GGUF, ONNX, STABLE_DIFFUSION }

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
                    LocalModelFormat.STABLE_DIFFUSION ->
                        "❌ Erreur interne : un modèle Stable Diffusion ne peut pas générer de texte. " +
                            "Utilise generate_image pour la génération d'image."
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
    // Backend GGUF — llama.cpp compilé nativement depuis les sources officielles
    // (voir app/src/main/cpp/). Fonctionne avec tout modèle .gguf standard :
    // Qwen, Llama, Mistral, Phi... aucune licence propriétaire, aucun jeton.
    // ─────────────────────────────────────────────────────────────────────────

    private var loadedGgufPath: String? = null

    private fun generateGguf(context: Context, modelPath: String, prompt: String): String {
        if (!NativeLlama.isAvailable()) {
            return """
❌ Le moteur IA local (llama.cpp) n'a pas pu être chargé sur cet appareil.

Détail technique : ${NativeLlama.getLoadError() ?: "bibliothèque native introuvable"}

Cela peut arriver si l'APK installé ne correspond pas à l'architecture de
ton téléphone. Réinstalle la dernière version depuis GitHub Actions.
""".trimIndent()
        }

        return try {
            if (loadedGgufPath != modelPath) {
                val ok = NativeLlama.loadModel(modelPath)
                if (!ok) {
                    return "❌ Échec du chargement du modèle .gguf. Vérifie qu'il s'agit bien " +
                        "d'un fichier GGUF valide et que le téléphone a assez de mémoire libre."
                }
                loadedGgufPath = modelPath
            }
            val result = NativeLlama.generate(prompt, 512)
            if (result.startsWith("[ERREUR]")) result.removePrefix("[ERREUR]").trim() else result
        } catch (e: Exception) {
            "❌ Erreur du moteur local : ${e.message}"
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
        if (loadedGgufPath != null) {
            NativeLlama.unload()
            loadedGgufPath = null
        }
    }

    private fun buildErrorMessage(format: LocalModelFormat, e: Exception): String {
        val name = when (format) {
            LocalModelFormat.TASK -> ".task (MediaPipe)"
            LocalModelFormat.GGUF -> ".gguf"
            LocalModelFormat.ONNX -> ".onnx (ONNX Runtime)"
            LocalModelFormat.STABLE_DIFFUSION -> ".gguf/.safetensors (Stable Diffusion)"
        }
        return "❌ Erreur modèle local ($name) : ${e.message}\n\nVérifiez que le fichier est valide et que le téléphone dispose d'assez de RAM (min 3 Go)."
    }
}
