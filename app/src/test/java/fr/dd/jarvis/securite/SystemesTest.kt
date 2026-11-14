package fr.dd.jarvis.securite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemesTest {
    @Test fun pduMms() {
        val jpeg = ByteArray(300) { it.toByte() }
        val p = Mms.pdu(listOf("06 12 34 56 78", "+33698765432"), "🚨 Alerte", jpeg, transaction = "T1")
        // En-têtes obligatoires dans l'ordre : type, transaction, version.
        assertEquals(0x8C, p[0].toInt() and 0xFF); assertEquals(0x80, p[1].toInt() and 0xFF)
        assertEquals(0x98, p[2].toInt() and 0xFF)
        val s = String(p, Charsets.ISO_8859_1)
        assertTrue(s.contains("0612345678/TYPE=PLMN\u0000") && s.contains("+33698765432/TYPE=PLMN\u0000"))
        // Content-Type multipart.mixed puis 2 parties.
        val i = s.indexOf("\u0084£")
        assertTrue(i > 0)
        assertEquals(2, p[i + 2].toInt())
        // La photo est recopiée telle quelle à la fin.
        assertTrue(p.copyOfRange(p.size - 300, p.size).contentEquals(jpeg))
        assertEquals("0612345678/TYPE=PLMN", Mms.adresse("06.12.34.56.78"))
    }

    @Test fun demandeDeverrouillage() {
        assertTrue(TelephoneSecurise.estDemande("déverrouille"))
        assertTrue(TelephoneSecurise.estDemande("Jarvis, déverrouille le téléphone"))
        assertTrue(TelephoneSecurise.estDemande("débloque mon portable s'il te plaît"))
        assertFalse(TelephoneSecurise.estDemande("déverrouille la porte d'entrée"))
        assertTrue(TelephoneSecurise.estDemande("Jarvis, déverrouiller le téléphone"))
        assertTrue(TelephoneSecurise.estDemande("déverrouille-toi"))
        assertTrue(TelephoneSecurise.estDemande("dévérouille mon téléphone stp"))
        assertFalse(TelephoneSecurise.estDemande("déverrouille la voiture"))
        assertEquals(listOf(1, 5, 9, 6), TelephoneSecurise.schema("schema:1596"))
        assertEquals(null, TelephoneSecurise.schema("schema:11"))
        assertTrue(TelephoneSecurise.codeValide("schema:1596"))
        val trace = TelephoneSecurise.traceSchema(listOf(1, 2, 3), intArrayOf(0, 0, 300, 300))
        assertTrue(trace.startsWith("input motionevent DOWN 50 50") && trace.endsWith("input motionevent UP 250 50"))
        assertTrue(TelephoneSecurise.codeValide("1234") && !TelephoneSecurise.codeValide("12 34") && !TelephoneSecurise.codeValide("12"))
    }

    @Test fun jsonEtPresentation() {
        val s = Systemes.Systeme(id = "maison_securisee", nom = "Maison sécurisée", modele = "maison_securisee",
            phrasesActiver = listOf("securise la maison"), activation = listOf(Systemes.Etape("ha", "lock.lock", listOf("lock.porte"))),
            capteurs = listOf("binary_sensor.entree_person"), cameras = listOf("camera.entree"), destinataires = listOf("0612345678"), actif = true)
        val l = Systemes.depuisJson(Systemes.versJson(listOf(s)))
        assertEquals(s, l.single())
        val t = Systemes.presenter(s)
        assertTrue(t.contains("🔒 Verrouiller lock.porte") && t.contains("camera.entree") && t.contains("0612345678"))
    }

    @Test fun adresseWebSocket() {
        assertEquals("ws://192.168.1.10:8123/api/websocket", SurveillanceMaison.urlWs("192.168.1.10:8123/"))
        assertEquals("wss://maison.example.fr/api/websocket", SurveillanceMaison.urlWs("https://maison.example.fr"))
    }
}
