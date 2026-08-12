package com.jarvis.assistant

/**
 * Interface vers le moteur d'inférence natif stable-diffusion.cpp (compilé
 * depuis les sources officielles via CMake/NDK — voir sdcpp/src/main/cpp/).
 * Génère de vraies images directement sur l'appareil, sans aucun réseau,
 * avec un modèle Stable Diffusion (.safetensors, .ckpt ou .gguf) que
 * l'utilisateur télécharge lui-même.
 *
 * ⚠️ Réalité technique : sans GPU dédié, la génération sur CPU de téléphone
 * prend plusieurs minutes par image (contre quelques secondes en ligne).
 */
object NativeStableDiffusion {

    private var libraryLoaded = false
    private var loadError: String? = null

    init {
        try {
            System.loadLibrary("jarvis_sd")
            libraryLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message
        }
    }

    fun isAvailable(): Boolean = libraryLoaded

    fun getLoadError(): String? = loadError

    external fun loadModel(modelPath: String): Boolean
    external fun generate(prompt: String, width: Int, height: Int, steps: Int): ByteArray?
    external fun getChannelCount(): Int
    external fun unload()
}
