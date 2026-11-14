package fr.dd.jarvis.hermes

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccesTest {
    @Test fun essentielGarde() {
        val sys = "Consignes ".repeat(3000) + "\n\n${ServeurInventaire.ESSENTIEL}ACCÈS DÉJÀ CONFIGURÉS DANS JARVIS — serveur SSH dd@192.168.1.10${ServeurInventaire.FIN_ESSENTIEL}\n\nNous sommes le lundi."
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", "SOUL Hermès ".repeat(2000))).put(JSONObject().put("role", "system").put("content", sys))
            .put(JSONObject().put("role", "user").put("content", "regarde le docker home assistant"))
        for (niveau in 0..3) {
            val c = RelaisGroq.compacterPourTest(msgs, niveau)
            val s = c.getJSONObject(0).getString("content")
            assertTrue("niveau $niveau", s.contains("ACCÈS DÉJÀ CONFIGURÉS DANS JARVIS"))
            assertFalse(s.contains(ServeurInventaire.ESSENTIEL))
        }
    }

    @Test fun reclame() {
        assertTrue(Hermes.reclameAcces("Je ne peux pas me connecter au serveur: les informations d'accès SSH (hôte, utilisateur, mot de passe ou clé) ne sont pas présentes dans la mémoire partagée."))
        assertTrue(Hermes.reclameAcces("Fournis-moi les paramètres de connexion SSH (hôte, utilisateur et mot de passe/clé) et je m'en occupe."))
        assertFalse(Hermes.reclameAcces("✅ Le conteneur homeassistant tourne ; le certificat TLS a expiré hier, je le renouvelle."))
    }
}
