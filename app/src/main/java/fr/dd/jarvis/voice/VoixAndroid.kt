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

    /** Genre d'une voix Android d'après son nom (moteur Google : fra/frc/vlf femmes, frb/frd hommes ; Samsung : f/m). */
    internal fun genre(nom: String): String? {
        val n = nom.lowercase()
        return when {
            Regex("x-fr[bd]\\b|x-fr[bd]-").containsMatchIn(n) -> "homme"
            Regex("x-(fr[ac]|vlf)\\b|x-(fr[ac]|vlf)-").containsMatchIn(n) -> "femme"
            Regex("smtm\\d").containsMatchIn(n) || (n.contains("male") && !n.contains("female")) -> "homme"
            Regex("smtf\\d").containsMatchIn(n) || n.contains("female") -> "femme"
            else -> null
        }
    }

    private val appliques = java.util.WeakHashMap<TextToSpeech, String>()

    private fun signature(ctx: Context): String {
        val app = ctx.applicationContext as? fr.dd.jarvis.JarvisApp ?: return ""
        return app.store.voixAndroid.value + "|" + VoixNaturelle.personnage(app).let { it.id + it.voix }
    }

    /** Réapplique la voix seulement si le choix (voix Android ou personnage) a changé depuis la dernière fois. */
    fun appliquerSiChange(ctx: Context, t: TextToSpeech?) {
        t ?: return
        if (synchronized(appliques) { appliques[t] } != signature(ctx)) appliquer(ctx, t)
    }

    fun appliquer(ctx: Context, t: TextToSpeech?) {
        t ?: return
        val app = ctx.applicationContext as? fr.dd.jarvis.JarvisApp
        val choix = app?.store?.voixAndroid?.value.orEmpty()
        val p = app?.let { runCatching { VoixNaturelle.personnage(it) }.getOrNull() }
        val fr = francaises(t)
        // Sans voix imposée : la meilleure voix du même genre que le personnage (s'il y en a une), sinon la meilleure.
        val candidates = p?.let { pp -> fr.filter { genre(it.name) == pp.genre }.ifEmpty { fr } } ?: fr
        val v = fr.firstOrNull { it.name == choix }
            ?: meilleure(candidates.map { Triple(it.name, it.quality, it.isNetworkConnectionRequired) }) { n -> candidates.first { it.name == n }.locale.country }
                ?.let { n -> candidates.first { it.name == n } }
        if (v != null) runCatching { t.voice = v }
        // Hauteur et débit du personnage (plus grave et plus lent pour une voix âgée, plus vif pour une voix jeune).
        runCatching { t.setPitch(p?.hauteur ?: 1f); t.setSpeechRate(p?.debit ?: 1f) }
        synchronized(appliques) { appliques[t] = signature(ctx) }
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
                    v.name to "Voix ${i + 1}" + (genre(v.name)?.let { " · $it" } ?: "") + " · ${v.locale.country}" + (if (v.isNetworkConnectionRequired) " · réseau" else " · hors ligne") +
                        when { v.quality >= Voice.QUALITY_VERY_HIGH -> " · très naturelle"; v.quality >= Voice.QUALITY_HIGH -> " · naturelle"; else -> "" }
                }.take(16)
        } finally { runCatching { t?.shutdown() } }
    }
}
