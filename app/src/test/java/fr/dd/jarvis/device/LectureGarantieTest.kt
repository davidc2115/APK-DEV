package fr.dd.jarvis.device

import fr.dd.jarvis.car.GuidageVoiture
import fr.dd.jarvis.service.JarvisAccessibilityService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LectureGarantieTest {
    private var t = 1_000L

    @After fun fin() { LectureGarantie.oublier() }

    @Test fun uneSeuleVerificationParLancement() {
        LectureGarantie.horloge = { t }
        LectureGarantie.noter("app.morphe.android.apps.youtube.music")
        assertTrue(LectureGarantie.recente())
        t += 10_000
        assertEquals("app.morphe.android.apps.youtube.music", LectureGarantie.aVerifier())
        // Consommée : la fin de conversation et la surveillance ne relancent pas deux fois.
        assertNull(LectureGarantie.aVerifier())
        assertFalse(LectureGarantie.recente())
    }

    @Test fun pauseDemandeeOuTropAncienne() {
        LectureGarantie.horloge = { t }
        LectureGarantie.noter("com.spotify.music")
        LectureGarantie.oublier()                 // « pause » : plus de relance
        assertNull(LectureGarantie.aVerifier())
        LectureGarantie.noter("com.spotify.music")
        t += 60_000
        assertFalse(LectureGarantie.recente())    // la voix ne baisse plus la musique au bout de 45 s
        t += 70_000
        assertNull(LectureGarantie.aVerifier())   // plus de 2 minutes : on ne touche plus à rien
    }

    @Test fun boutonLectureSeulement() {
        val r = JarvisAccessibilityService.BOUTON_LECTURE
        listOf("Lire", "Lecture", "Play", "Reprendre", "Lire la vidéo", "Play video").forEach { assertTrue(it, r.matches(it)) }
        listOf("Pause", "Lecture aléatoire", "Lire tout", "Mettre en pause", "Playlist").forEach { assertFalse(it, r.matches(it)) }
    }

    @Test fun boutonDepartDuGuidage() {
        listOf("Y aller", "Go", "Démarrer", "Partir", "C'est parti !").forEach { assertTrue(it, GuidageVoiture.boutonDepart(it)) }
        listOf("Envoyer", "Appeler Paul", "Supprimer", "Y aller plus tard").forEach { assertFalse(it, GuidageVoiture.boutonDepart(it)) }
    }
}
