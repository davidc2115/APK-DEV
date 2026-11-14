package fr.dd.jarvis.hermes

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServeurOutilsTest {
    private fun outil(nom: String, desc: String) = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", nom).put("description", desc)
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))

    private fun catalogue(): JSONArray {
        val o = JSONArray()
        listOf("terminal", "web_search", "cronjob", "delegate_task", "memory", "skill_view").forEach { o.put(outil(it, "Native Hermes tool")) }
        o.put(outil("mcp_jarvis_agent", "Agent téléphone Jarvis"))
        o.put(outil("mcp_jarvis_jarvis_rechercher_outils", "Recherche un outil Jarvis"))
        o.put(outil("mcp_jarvis_jarvis_appeler_outil", "Appelle un outil Jarvis"))
        // 150 outils du téléphone dont les descriptions parlent aussi de « service » ou « redémarrer ».
        repeat(150) { i -> o.put(outil("mcp_jarvis_tel_$i", "Outil téléphone $i : service, redémarrer, état")) }
        listOf("ssh_commande", "ssh_fichier", "ssh_script", "serveur_diagnostic", "serveur_scan_ports").forEach { o.put(outil("mcp_jarvis_$it", "Commande sur la machine distante")) }
        listOf("ha_entites", "ha_etat", "ha_service").forEach { o.put(outil("mcp_jarvis_$it", "Domotique")) }
        listOf("plex_rechercher", "plex_appareils", "plex_lancer").forEach { o.put(outil("mcp_jarvis_$it", "Médiathèque")) }
        listOf("discord_salons", "discord_messages_lire").forEach { o.put(outil("mcp_jarvis_$it", "Communauté")) }
        return o
    }

    private fun noms(demande: String): List<String> {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "Consignes"))
            .put(JSONObject().put("role", "user").put("content", demande))
        val tls = RelaisGroq.pourGemini(messages, catalogue()).second!!
        return (0 until tls.length()).map { tls.getJSONObject(it).getJSONObject("function").getString("name") }
    }

    @Test fun lesOutilsDuServeurArriventPourUneDemandeServeur() {
        val n = noms("redémarre le bot discord sur mon serveur debian")
        assertTrue(n.toString(), n.containsAll(listOf("mcp_jarvis_ssh_commande", "mcp_jarvis_ssh_script", "mcp_jarvis_serveur_diagnostic", "mcp_jarvis_discord_salons")))
        assertTrue("outils de découverte gardés", n.containsAll(listOf("mcp_jarvis_jarvis_rechercher_outils", "mcp_jarvis_jarvis_appeler_outil")))
    }

    @Test fun plexEtHomeAssistant() {
        // SSH seulement quand l'utilisateur parle du serveur.
        assertTrue(noms("Plex ne répond plus").contains("mcp_jarvis_plex_rechercher"))
        assertFalse(noms("Plex ne répond plus").contains("mcp_jarvis_ssh_commande"))
        assertTrue(noms("Plex ne répond plus, regarde sur le serveur").containsAll(listOf("mcp_jarvis_plex_rechercher", "mcp_jarvis_ssh_commande")))
        assertFalse(noms("où se trouve Angeline ?").contains("mcp_jarvis_ssh_commande"))
        assertTrue(noms("allume la lumière du salon").contains("mcp_jarvis_ha_service"))
    }

    @Test fun domaines() {
        assertEquals(setOf("ssh_", "serveur_", "ha_", "jarvis_reglages"), RelaisGroq.domaines("les logs de Home Assistant sur le serveur"))
        assertTrue(RelaisGroq.domaines("quelle heure est-il ?").isEmpty())
        assertTrue(RelaisGroq.domaines("mon bot discord est planté").containsAll(listOf("discord", "ssh_")))
    }

    @Test fun releveNettoye() {
        val brut = "## Docker (nom | image | état | ports)\nhomeassistant | ghcr.io/home-assistant/home-assistant:stable | Up 3 days | \n\n## pm2\n## Services systemd actifs (hors système)\nplexmediaserver.service\n## Ports en écoute\n"
        val r = ServeurInventaire.nettoyer(brut)
        assertFalse("sections vides retirées", r.contains("## pm2") || r.contains("## Ports"))
        assertTrue(r.contains("homeassistant |") && r.contains("plexmediaserver.service"))
        assertTrue(ServeurInventaire.concerne("redémarre plex") && !ServeurInventaire.concerne("raconte-moi une blague"))
    }
}
