package fr.dd.jarvis.car

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Bouton de départ de Waze / Maps lu À L'ÉCRAN (reconnaissance de texte sur le téléphone, Google Play services) :
 * Waze dessine une partie de son interface lui-même, sans la décrire à l'accessibilité — JARVIS ne « voyait » donc pas
 * le bouton « Y aller » pourtant affiché. On lit la capture, on repère le libellé et on touche son centre.
 */
object OcrDepart {
    /** Un mot lu, avec son cadre à l'écran (pixels). */
    data class Mot(val texte: String, val gauche: Int, val haut: Int, val droite: Int, val bas: Int)
    data class Cible(val libelle: String, val x: Float, val y: Float)
    data class Lecture(val cible: Cible?, val lignes: List<String>)

    private fun union(libelle: String, mots: List<Mot>) = Cible(libelle,
        (mots.minOf { it.gauche } + mots.maxOf { it.droite }) / 2f, (mots.minOf { it.haut } + mots.maxOf { it.bas }) / 2f)

    private fun norm(t: String) = fr.dd.jarvis.societe.core.normaliser(t)

    /**
     * Le bouton de départ parmi les lignes lues (texte de la ligne, mots de la ligne). D'abord une ligne qui n'est QUE le
     * libellé (« Y aller ») ; sinon deux ou trois mots consécutifs (« Partir plus tard   Y aller » lus comme une seule
     * ligne), jamais un mot suivi de « plus tard ».
     */
    fun choisir(lignes: List<Pair<String, List<Mot>>>): Cible? {
        for ((texte, mots) in lignes) if (mots.isNotEmpty() && GuidageVoiture.boutonDepart(texte)) return union(texte.trim(), mots)
        for ((_, mots) in lignes) for (n in 3 downTo 2) for (i in 0..mots.size - n) {
            val bout = mots.subList(i, i + n)
            val t = bout.joinToString(" ") { it.texte }
            val suivant = mots.getOrNull(i + n)?.texte?.let(::norm).orEmpty()
            if (suivant.startsWith("plus") || suivant.startsWith("tard") || suivant.startsWith("later")) continue
            if (GuidageVoiture.boutonDepart(t)) return union(t, bout)
        }
        return null
    }

    /** Lit [image] (à appeler hors du fil principal) : bouton trouvé et lignes lues (pour le journal). */
    suspend fun lire(image: Bitmap): Lecture? = withContext(Dispatchers.IO) {
        runCatching {
            val client = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val r = com.google.android.gms.tasks.Tasks.await(client.process(com.google.mlkit.vision.common.InputImage.fromBitmap(image, 0)), 8, TimeUnit.SECONDS)
                val lignes = r.textBlocks.flatMap { b ->
                    b.lines.map { l -> l.text to l.elements.mapNotNull { e -> e.boundingBox?.let { Mot(e.text, it.left, it.top, it.right, it.bottom) } } }
                }
                Lecture(choisir(lignes), lignes.map { it.first })
            } finally { runCatching { client.close() } }
        }.onFailure { fr.dd.jarvis.voice.JournalVoix.noter("voiture", "lecture de l'écran impossible : ${it.javaClass.simpleName} ${it.message.orEmpty().take(100)}") }
            .getOrNull()
    }
}
