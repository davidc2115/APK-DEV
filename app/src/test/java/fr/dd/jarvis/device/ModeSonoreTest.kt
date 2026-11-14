package fr.dd.jarvis.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModeSonoreTest {
    @Test fun phrases() {
        assertEquals("npd", ModeSonore.intention("Mets le téléphone en ne pas déranger"))
        assertEquals("npd", ModeSonore.intention("passe en NPD"))
        assertEquals("npd", ModeSonore.intention("mets-toi en silencieux"))
        assertEquals("npd_off", ModeSonore.intention("désactive le ne pas déranger"))
        assertEquals("vibreur", ModeSonore.intention("mode vibreur"))
        assertEquals("vibreur", ModeSonore.intention("Jarvis, passe le téléphone en vibreur"))
        assertEquals("sonnerie", ModeSonore.intention("remets la sonnerie"))
        assertEquals("sonnerie", ModeSonore.intention("mode sonnerie"))
        assertEquals("sonnerie", ModeSonore.intention("passe en sonnerie et vibreur"))
        assertEquals("sonnerie", ModeSonore.intention("enlève le vibreur"))
        assertEquals("etat", ModeSonore.intention("est-ce que je suis en silencieux ?"))
        assertNull(ModeSonore.intention("mets la sonnerie à fond"))
        assertNull(ModeSonore.intention("mets la télé en silencieux"))
        assertNull(ModeSonore.intention("mets l'alarme en mode silencieux"))
        assertNull(ModeSonore.intention("raconte-moi une histoire"))
    }
}
