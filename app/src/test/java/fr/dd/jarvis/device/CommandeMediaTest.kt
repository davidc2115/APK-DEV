package fr.dd.jarvis.device

import fr.dd.jarvis.device.CommandeMedia.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandeMediaTest {
    private fun i(t: String, ouverte: Boolean = true) = CommandeMedia.intention(t) { ouverte }

    @Test fun reconnues() {
        val cas = mapOf(
            "Reprends la musique" to Action.PLAY, "Jarvis, peux-tu reprendre la musique s'il te plaît" to Action.PLAY,
            "remets la musique" to Action.PLAY, "reprends la lecture" to Action.PLAY, "relance la musique" to Action.PLAY,
            "mets de la musique" to Action.PLAY, "lance la musique" to Action.PLAY, "enlève la pause" to Action.PLAY,
            "mets en pause" to Action.PAUSE, "mets pause" to Action.PAUSE, "mets la musique en pause" to Action.PAUSE,
            "pause la musique" to Action.PAUSE, "arrête la musique" to Action.PAUSE, "stop la musique" to Action.PAUSE,
            "chanson suivante" to Action.NEXT, "passe à la chanson suivante" to Action.NEXT, "musique suivante" to Action.NEXT,
            "change de chanson" to Action.NEXT, "zappe cette chanson" to Action.NEXT, "morceau d'après" to Action.NEXT,
            "chanson précédente" to Action.PREVIOUS, "reviens à la chanson précédente" to Action.PREVIOUS, "remets la chanson d'avant" to Action.PREVIOUS,
            "recommence la chanson" to Action.RESTART, "reprends le morceau du début" to Action.RESTART,
            "monte le son" to Action.VOLUME_UP, "plus fort" to Action.VOLUME_UP, "baisse un peu le son".replace("un peu ", "") to Action.VOLUME_DOWN,
            "moins fort" to Action.VOLUME_DOWN, "c'est quoi cette chanson ?" to Action.INFO, "qu'est-ce qu'on écoute ?" to Action.INFO,
            "Jarvis suivant" to Action.NEXT, "pause" to Action.PAUSE, "lecture" to Action.PLAY,
        )
        cas.forEach { (t, a) -> assertEquals(t, a, i(t)) }
    }

    @Test fun ignorees() {
        listOf("continue", "suivant" to false, "reprends depuis le début" to false, "mets la musique de Daft Punk", "mets le volume à 50",
            "baisse le son de la télé", "mets la vidéo en pause", "coupe le son", "envoie un SMS à Marie", "stop",
            "reprends" to false, "fais-moi le point de ma journée", "pause" to false).forEach { c ->
            val (t, ouverte) = if (c is Pair<*, *>) c.first as String to c.second as Boolean else c as String to true
            assertEquals(t, null, i(t, ouverte))
        }
        assertEquals(Action.RESTART, i("reprends depuis le début", true))
    }
}
