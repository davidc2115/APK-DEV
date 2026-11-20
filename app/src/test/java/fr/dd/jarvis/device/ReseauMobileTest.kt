package fr.dd.jarvis.device

import fr.dd.jarvis.device.ReseauMobile.Qualite
import fr.dd.jarvis.device.ReseauMobile.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReseauMobileTest {
    @Test fun qualiteDuSignal() {
        assertEquals(Qualite.EXCELLENTE, ReseauMobile.qualite(Signal("4G", -75)))
        assertEquals(Qualite.BONNE, ReseauMobile.qualite(Signal("4G", -88)))
        assertEquals(Qualite.MOYENNE, ReseauMobile.qualite(Signal("5G", -97)))
        assertEquals(Qualite.FAIBLE, ReseauMobile.qualite(Signal("4G", -105)))
        assertEquals(Qualite.TRES_FAIBLE, ReseauMobile.qualite(Signal("4G", -118)))
        assertEquals(Qualite.BONNE, ReseauMobile.qualite(Signal("3G", -80)))
        assertEquals(Qualite.AUCUNE, ReseauMobile.qualite(null))
        // Fort brouillage : un cran plus bas.
        assertEquals(Qualite.MOYENNE, ReseauMobile.qualite(Signal("4G", -88, sinr = -3)))
    }

    @Test fun recommandations() {
        val faible = ReseauMobile.Etat(signal = Signal("5G", -112), wifi = true, smsOptimiseBatterie = true, smsParDefaut = "Messages")
        val r = ReseauMobile.recommandations(faible).joinToString("\n")
        assertTrue(r, r.contains("4G") && r.contains("appels Wi-Fi") && r.contains("optimisation de la batterie") && r.contains("relance"))
        assertTrue(ReseauMobile.recommandations(ReseauMobile.Etat(modeAvion = true)).first().contains("mode avion"))
        assertTrue(ReseauMobile.recommandations(ReseauMobile.Etat(signal = Signal("4G", -85), donnees = true)).isEmpty())
        assertTrue(ReseauMobile.recommandations(ReseauMobile.Etat(signal = Signal("4G", -85), nePasDeranger = true)).single().contains("Ne pas déranger"))
    }

    @Test fun apn() {
        assertEquals("free", ReseauMobile.apnRecommande("20815", "Free")!!.apn)
        assertEquals("orange.acte", ReseauMobile.apnRecommande("20801", "Sosh")!!.mmsApn)
        assertEquals("sl2sfr", ReseauMobile.apnRecommande("20810", "RED")!!.apn)
        assertEquals("mmsbouygtel.com", ReseauMobile.apnRecommande("20820", "")!!.apn)
        // Opérateur virtuel sur le réseau d'un autre : ses APN sont différents → pas de valeurs inventées.
        assertNull(ReseauMobile.apnRecommande("20810", "NRJ Mobile"))
        val lu = ReseauMobile.interpreterApn("Row: 0 _id=2745, name=Free, numeric=20815, apn=free, user=NULL, mmsc=http://mms.free.fr, type=default,supl,mms")!!
        assertEquals("free", lu["apn"]); assertEquals("default,supl,mms", lu["type"]); assertEquals("", lu["user"])
        assertNull(ReseauMobile.interpreterApn("Error while accessing provider:telephony java.lang.SecurityException: No permission to access APN settings"))
    }

    @Test fun phrases() {
        val attendus = mapOf(
            "comment est mon réseau" to "diagnostic", "est-ce que je capte bien ici" to "diagnostic", "j'ai la 5G ?" to "diagnostic",
            "améliore ma réception" to "optimiser", "relance le réseau" to "reenregistrer", "montre-moi l'APN" to "apn",
            "je ne reçois pas mes SMS" to "messages", "mes MMS n'arrivent plus" to "messages", "mes appels coupent" to "appels",
            "je tombe directement sur la messagerie quand on m'appelle" to "appels",
        )
        attendus.forEach { (p, a) -> assertEquals(p, a, ReseauMobile.intention(p)) }
        for (p in listOf("envoie un SMS à Martin pour lui dire que je serai en retard à notre rendez-vous", "comment est le wifi", "le réseau de la Freebox est lent",
                "mes réseaux sociaux", "le réseau de neurones", "appelle maman", "lis mes SMS", "active le mode nuit", "quel temps fait-il"))
            assertNull(p, ReseauMobile.intention(p))
    }
}
