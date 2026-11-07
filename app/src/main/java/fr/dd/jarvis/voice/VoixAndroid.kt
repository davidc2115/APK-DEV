package fr.dd.jarvis.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Voix Android de repli : moteur de synthèse de Google s'il est installé (les moteurs de constructeur sont souvent
 * robotiques) et meilleure voix française (qualité la plus haute, voix « réseau » de préférence), ou celle choisie.
 */
object VoixAndroid {
    const val MOTEUR_GOOGLE = "com.google.android.tts"

    fun googleInstalle(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(MOTEUR_GOOGLE, 0); true }.getOrDefault(false)

    private fun francaises(t: TextToSpeech): List<Voice> = runCatching { t.voices }.getOrNull().orEmpty()
        .filter { it.locale.language == "fr" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }

    /** Classement : France d'abord, qualité, voix réseau (plus naturelles), latence. */
    internal fun meilleure(voix: List<Triple<String, Int, Boolean>>, pays: (String) -> String): String? =
        voix.sortedWith(compareByDescending<Triple<String, Int, Boolean>> { pays(it.first) == "FR" }
            .thenByDescending { it.second }.thenByDescending { it.third }).firstOrNull()?.first

    fun appliquer(ctx: Context, t: TextToSpeech?) {
        t ?: return
        val choix = (ctx.applicationContext as? fr.dd.jarvis.JarvisApp)?.store?.voixAndroid?.value.orEmpty()
        val fr = francaises(t)
        val v = fr.firstOrNull { it.name == choix }
            ?: meilleure(fr.map { Triple(it.name, it.quality, it.isNetworkConnectionRequired) }) { n -> fr.first { it.name == n }.locale.country }
                ?.let { n -> fr.first { it.name == n } }
        if (v != null) runCatching { t.voice = v }
    }

    /** Voix françaises proposées dans les réglages : (nom technique, libellé). */
    suspend fun liste(ctx: Context): List<Pair<String, String>> {
        val pret = CompletableDeferred<Boolean>()
        var t: TextToSpeech? = null
        val l = TextToSpeech.OnInitListener { pret.complete(it == TextToSpeech.SUCCESS) }
        t = if (googleInstalle(ctx)) TextToSpeech(ctx, l, MOTEUR_GOOGLE) else TextToSpeech(ctx, l)
        return try {
            if (withTimeoutOrNull(5000) { pret.await() } != true) emptyList()
            else francaises(t).sortedWith(compareByDescending<Voice> { it.locale.country == "FR" }.thenByDescending { it.quality }.thenByDescending { it.isNetworkConnectionRequired })
                .mapIndexed { i, v ->
                    v.name to "Voix ${i + 1} · ${v.locale.country}" + (if (v.isNetworkConnectionRequired) " · réseau" else " · hors ligne") +
                        when { v.quality >= Voice.QUALITY_VERY_HIGH -> " · très naturelle"; v.quality >= Voice.QUALITY_HIGH -> " · naturelle"; else -> "" }
                }.take(16)
        } finally { runCatching { t?.shutdown() } }
    }
}
