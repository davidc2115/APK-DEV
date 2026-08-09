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
 * Télécharge un modèle .task directement depuis l'application (aucun PC requis).
 * Les modèles Gemma sont soumis à une licence Google : un jeton Hugging Face
 * gratuit (après acceptation de la licence sur huggingface.co) est nécessaire —
 * c'est une contrainte légale de Google, pas une limitation technique de l'app.
 */
object ModelDownloader {

    sealed class Progress {
        data class Percent(val value: Int) : Progress()
        data class Done(val file: File) : Progress()
        data class Error(val message: String) : Progress()
    }

    // Modèle officiel Google, vérifié fonctionnel — nécessite un jeton HF gratuit
    // car Gemma est sous licence Google (obligatoire, pas une limite de l'app).
    const val RECOMMENDED_MODEL_URL =
        "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task?download=true"
    const val RECOMMENDED_MODEL_LABEL = "Gemma 3 1B officiel — léger et rapide (≈550 Mo)"

    // Miroir communautaire non officiel, sans jeton requis. Peut être retiré ou
    // changer à tout moment car il n'est pas maintenu par Google — à utiliser
    // en connaissance de cause si tu ne veux vraiment pas créer de compte HF.
    const val NO_KEY_MODEL_URL =
        "https://huggingface.co/Instamath-works/Gemma3-1B-IT-task/resolve/main/gemma3-1B-it-int4.task?download=true"
    const val NO_KEY_MODEL_LABEL = "Gemma 3 1B (miroir communautaire, sans compte)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun download(
        context: Context,
        url: String,
        hfToken: String,
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
                                "bien accepté la licence du modèle sur sa page huggingface.co."
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
                val destFile = File(context.filesDir, "local_model.task")
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
                LocalLlmManager.unload()
                onProgress(Progress.Done(destFile))
            }
        } catch (e: Exception) {
            onProgress(Progress.Error("Erreur réseau : ${e.message}"))
        }
    }
}
