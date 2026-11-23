package fr.dd.jarvis.ai

import fr.dd.jarvis.device.Memo
import fr.dd.jarvis.device.Routines
import fr.dd.jarvis.securite.Systemes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Poêles de Dd : interrupteurs « Natural Mode » de l'étage et du rez-de-chaussée (salon). */
class PoeleLieuTest {
    private val ETAGE = "switch.poele_etage_natural_mode"
    private val RDC = "switch.poele_rez_de_chaussee_natural_mode"

    private fun etat(id: String, nom: String, state: String = "off") =
        JSONObject().put("entity_id", id).put("state", state).put("attributes", JSONObject().put("friendly_name", nom))

    private val toutes = listOf(
        etat("climate.poele_etage", "Poêle étage", "heat"), etat("climate.poele_rez_de_chaussee", "Poêle rez de chaussée", "heat"),
        etat(ETAGE, "-poele etage Natural Mode"), etat(RDC, "-poele rez de chaussée Natural Mode"))

    @Test fun pieceContredite() {
        assertTrue(PoeleDirect.lieuContredit(" poele etage natural mode ", listOf("salon")))
        assertFalse(PoeleDirect.lieuContredit(" poele rez de chaussee natural mode ", listOf("salon")))
        assertTrue(PoeleDirect.lieuContredit(" poele rez de chaussee natural mode ", listOf("etage")))
        assertFalse(PoeleDirect.lieuContredit(" poele etage natural mode ", listOf("etage")))
        // Pas de pièce demandée, ou un mot qui n'en est pas une : aucune contradiction.
        assertFalse(PoeleDirect.lieuContredit(" poele etage natural mode ", emptyList()))
        assertFalse(PoeleDirect.lieuContredit(" poele etage natural mode ", listOf("granules")))
    }

    @Test fun consigneAppriseDeTraversIgnoree() {
        // Ce que l'IA avait appris de travers (journal du 23/11) : salon → interrupteur de l'étage.
        val fausse = "maison : pour activer le mode nuit du poêle salon : service turn_on sur $ETAGE"
        val juste = "maison : pour activer le mode nuit du poêle salon : service turn_on sur $RDC"
        val salon = PoeleDirect.modeDemande("passe le poele du salon en mode nuit")!!
        val deux = listOf(ETAGE to fausse, RDC to juste)
        assertEquals(listOf(RDC), PoeleDirect.commandesMode(toutes, salon, deux.map { it.first }, deux)!!.map { it.id })
        // Seule la fausse : jamais l'étage pour le salon (le cerveau reprend).
        val seule = listOf(ETAGE to fausse)
        assertTrue(PoeleDirect.commandesMode(toutes, salon, seule.map { it.first }, seule).orEmpty().none { it.id == ETAGE })
    }

    @Test fun lignesFaussesRepérees() {
        val lignes = listOf(
            "pour activer le mode nuit du poêle salon : service turn_on sur $ETAGE",
            "pour activer le mode nuit du poêle etage : service turn_on sur $RDC",
            "pour activer le mode nuit du poêle etage : service turn_on sur $ETAGE",
            "pour retirer le mode nuit du poêle salon : service turn_off sur $RDC",
            "pour activer le mode nuit des poêles : service turn_on sur $RDC",
            "les poêles se coupent à 23 h")
        val noms = mapOf(ETAGE to "-poele etage Natural Mode", RDC to "-poele rez de chaussée Natural Mode")
        assertEquals(lignes.take(2), PoeleDirect.appriseFausses(lignes, noms))
    }

    @Test fun modeJarvisCommeConsigne() {
        val s = Systemes.Systeme("m1", "Mode nuit des poêles",
            activation = listOf(Systemes.Etape("ha", "switch.turn_on", listOf(ETAGE, RDC))),
            desactivation = listOf(Systemes.Etape("ha", "switch.turn_off", listOf(ETAGE, RDC))))
        val autre = Systemes.Systeme("m2", "Maison sécurisée", activation = listOf(Systemes.Etape("ha", "switch.turn_on", listOf("switch.alarme"))))
        val retirerEtage = PoeleDirect.modeDemande("retire le mode nuit du poêle de l'étage")!!
        val notes = PoeleDirect.notesDesModes(listOf(s, autre), retirerEtage)
        assertEquals(listOf(ETAGE, RDC), notes.map { it.first })
        PoeleDirect.commandesMode(toutes, retirerEtage, notes.map { it.first }, notes)!!.single().let {
            assertEquals(ETAGE, it.id); assertEquals("turn_off", it.service)
        }
        val salon = PoeleDirect.modeDemande("passe le poêle du salon en mode nuit")!!
        PoeleDirect.commandesMode(toutes, salon, notes.map { it.first }, notes)!!.single().let {
            assertEquals(RDC, it.id); assertEquals("turn_on", it.service)
        }
        val tous = PoeleDirect.modeDemande("passe les poêles en mode nuit")!!
        assertEquals(listOf(ETAGE, RDC), PoeleDirect.commandesMode(toutes, tous, notes.map { it.first }, notes)!!.map { it.id }.sorted())
    }

    @Test fun consignesDeDeuxPiecesNeSeRemplacentPas() {
        val a = "pour activer le mode nuit du poêle etage : service turn_on sur $ETAGE"
        val b = "pour activer le mode nuit du poêle salon : service turn_on sur $RDC"
        assertTrue(Memo.proximite(a, b) >= 0.6)
        assertTrue(Memo.piecesDifferentes(a, b))
        assertFalse(Memo.piecesDifferentes(a, "pour activer le mode nuit du poêle etage : service turn_on sur switch.autre_etage"))
    }

    @Test fun echecLongReconnu() {
        val r = "L'ordre d'extinction a été envoyé à Home Assistant pour le mode nuit du poêle du salon ($RDC), mais l'état de l'appareil ne confirme pas encore le changement."
        val l = Routines.ligne("retire le mode nuit du poêle du salon", r)
        assertTrue(l, l.startsWith("❌"))
        assertTrue(l, l.length <= 145 && l.endsWith("…"))
    }

    @Test fun reseauPassager() {
        assertTrue(Serveur.passager(java.net.UnknownHostException("ha.local"), "POST"))
        assertTrue(Serveur.passager(java.net.ConnectException("refusé"), "GET"))
        assertTrue(Serveur.passager(java.net.SocketTimeoutException(), "GET"))
        assertFalse(Serveur.passager(java.net.SocketTimeoutException(), "POST"))
        assertFalse(Serveur.passager(IllegalArgumentException(), "GET"))
    }

    @Test fun consigneDAppareilNeCoupePasLesReflexes() {
        val demande = "passe le poele de l'étage en mode nuit"
        assertFalse(Restitution.exigeUneSuite(mapOf("maison" to "- pour le mode nuit des poêles, utilise aussi $ETAGE et $RDC"), demande))
        // Témoin : une vraie consigne d'ajout pour cette demande garde l'effet attendu.
        assertTrue(Restitution.exigeUneSuite(mapOf("maison" to "- quand tu passes les poêles en mode nuit, donne aussi la température du poêle"), demande))
    }
}
