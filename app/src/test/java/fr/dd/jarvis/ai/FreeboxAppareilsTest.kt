package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FreeboxAppareilsTest {
    private fun h(id: String, nom: String, mac: String, actif: Boolean, ip: String = "") = JSONObject().put("id", id).put("primary_name", nom).put("active", actif)
        .put("l2ident", JSONObject().put("id", mac)).put("l3connectivities", JSONArray().apply { if (ip.isNotBlank()) put(JSONObject().put("af", "ipv4").put("addr", ip)) })

    private val hotes = listOf(
        h("ether-aa:bb:cc:dd:ee:01", "", "aa:bb:cc:dd:ee:01", false),
        h("ether-aa:bb:cc:dd:ee:02", "AA:BB:CC:DD:EE:02", "aa:bb:cc:dd:ee:02", false),
        h("ether-aa:bb:cc:dd:ee:03", "iPhone de Paul", "aa:bb:cc:dd:ee:03", true, "192.168.1.20"),
        h("ether-aa:bb:cc:dd:ee:04", "Imprimante Canon", "aa:bb:cc:dd:ee:04", false, "192.168.1.30"),
    )

    @Test fun nomsReels() {
        assertEquals("", FreeboxOs.nomReel("", "x"))
        assertEquals("", FreeboxOs.nomReel("AA:BB:CC:DD:EE:02", "aa:bb:cc:dd:ee:02"))
        assertEquals("iPhone", FreeboxOs.nomReel("iPhone", "x"))
    }

    @Test fun choix() {
        assertEquals(2, FreeboxOs.choisirAppareils(hotes, "", "sans_nom").size)
        assertEquals(3, FreeboxOs.choisirAppareils(hotes, "", "hors_ligne").size)
        assertEquals("ether-aa:bb:cc:dd:ee:04", FreeboxOs.choisirAppareils(hotes, "imprimante", "").single().getString("id"))
        assertEquals("ether-aa:bb:cc:dd:ee:03", FreeboxOs.choisirAppareils(hotes, "192.168.1.20", "").single().getString("id"))
        assertEquals(2, FreeboxOs.choisirAppareils(hotes, "aa:bb:cc:dd:ee:01 ; Imprimante Canon", "").size)
    }

    @Test fun reflexeVocal() {
        assertEquals("supprimer_appareil", Brain.intentionFreebox("supprime les appareils sans nom de la box")!!.first)
        assertEquals("sans_nom", Brain.intentionFreebox("supprime les appareils sans nom de la box")!!.second.getString("filtre"))
        assertEquals("hors_ligne", Brain.intentionFreebox("retire de la Freebox les appareils déconnectés")!!.second.getString("filtre"))
        assertEquals("appareils", Brain.intentionFreebox("liste les appareils sans nom de la freebox")!!.first)
    }
}
