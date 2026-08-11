package com.jarvis.assistant

/**
 * Interface vers le moteur d'inférence natif llama.cpp (compilé depuis les
 * sources officielles via CMake/NDK — voir app/src/main/cpp/).
 * Permet de faire tourner de vrais modèles .gguf directement sur l'appareil,
 * sans aucune licence propriétaire ni jeton d'accès (Qwen, Llama, Mistral,
 * Phi... tout modèle GGUF standard est compatible).
 */
object NativeLlama {

    private var libraryLoaded = false
    private var loadError: String? = null

    init {
        try {
            System.loadLibrary("jarvis_llama")
            libraryLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message
        }
    }

    fun isAvailable(): Boolean = libraryLoaded

    fun getLoadError(): String? = loadError

    external fun loadModel(modelPath: String): Boolean
    external fun generate(prompt: String, maxTokens: Int): String
    external fun unload()
}
