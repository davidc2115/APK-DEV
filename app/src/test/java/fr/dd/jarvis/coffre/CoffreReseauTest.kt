package fr.dd.jarvis.coffre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoffreReseauTest {
    @Test fun erreursEnClair() {
        assertEquals("connexion coupée", CoffreClient.libelle(java.net.SocketException("Connection reset")))
        assertEquals("pas de réponse", CoffreClient.libelle(java.net.SocketTimeoutException("connect timed out")))
        assertEquals("refusée (port fermé)", CoffreClient.libelle(java.net.ConnectException("Failed to connect to /82.65.75.176:8443 (ECONNREFUSED)")))
        assertEquals("réseau inaccessible", CoffreClient.libelle(java.net.NoRouteToHostException("Network is unreachable")))
        assertEquals("192.168.1.133", CoffreClient.court("https://192.168.1.133:8443"))
        assertTrue(CoffreClient.passagere("connexion coupée"))
        assertFalse(CoffreClient.passagere("refusée (port fermé)"))
    }

    @Test fun chaqueAdresseEssayeeEtExpliquee() {
        // Aucune adresse joignable (ports fermés en local) : message adresse par adresse, sans attendre (erreur non passagère).
        val c = CoffreClient(listOf("https://127.0.0.1:1", "https://127.0.0.1:2"), "00")
        val e = runCatching { c.sante() }.exceptionOrNull() as CoffreException
        assertEquals(0, e.code)
        assertTrue(e.message!!, e.message!!.startsWith("Coffre injoignable — 127.0.0.1 : refusée"))
        assertEquals(2, c.erreurs.size)
    }
}
