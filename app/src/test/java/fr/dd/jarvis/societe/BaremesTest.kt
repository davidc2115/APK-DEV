package fr.dd.jarvis.societe

import fr.dd.jarvis.societe.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaremesTest {
    private val b2026 = Baremes(dateMaj = "2026-11-13", source = "test", pmss = 4005.0, smicHoraire = 12.31, isTauxReduit = 15.0, isPlafondReduit = 42500.0, isTauxNormal = 25.0, pfuPct = 31.4, prelevementsSociauxPct = 18.6)

    @Test fun plafondEtChangements() {
        assertEquals(42500.0, BaremesCalcul.plafondIs(100_000.0, 20_750.0, 15.0, 25.0), 0.01)
        val avant = b2026.copy(pmss = 3925.0, pfuPct = 30.0)
        val ch = BaremesCalcul.changements(avant, b2026)
        assertEquals(2, ch.size)
        assertTrue(ch[0], ch[0].contains("Sécurité sociale") && ch[0].contains("4 005"))
        assertTrue(BaremesCalcul.changements(Baremes(), b2026).isEmpty())
    }

    @Test fun application() {
        val d = SocieteData(societe = Societe(isTauxReduit = 10.0, pfuPct = 30.0), paie = ConfigPaie(plafondSecu = 3925.0))
        val a = BaremesCalcul.appliquer(d, b2026)
        assertEquals(31.4, a.societe.pfuPct, 0.0); assertEquals(15.0, a.societe.isTauxReduit, 0.0); assertEquals(4005.0, a.paie.plafondSecu, 0.0)
        val manuel = BaremesCalcul.appliquer(d.copy(societe = d.societe.copy(baremesAuto = false)), b2026)
        assertEquals(30.0, manuel.societe.pfuPct, 0.0); assertEquals(4005.0, manuel.baremes.pmss, 0.0)
        // Sérialisation (sauvegarde de la base Société)
        val json = jsonSociete.encodeToString(SocieteData.serializer(), a.copy(baremes = b2026.copy(couts = mapOf("e1" to CoutMensuel(1900.0, 2011.12, 1482.1, "2026-11-13")))))
        val relu = jsonSociete.decodeFromString(SocieteData.serializer(), json)
        assertEquals(2011.12, relu.baremes.couts["e1"]!!.cout, 0.0)
        assertTrue(jsonSociete.decodeFromString(SocieteData.serializer(), "{}").societe.baremesAuto)
    }

    @Test fun bulletinOfficiel() {
        // Montants renvoyés par le moteur URSSAF (modele-social 11.1.0) pour 1 900 € brut.
        val m = BaremesCalcul.MontantsPaie(mapOf(
            "maladie.employeur" to 247.0, "sante.employeur" to 20.0, "sante.salarie" to 20.0, "atmp" to 39.52,
            "vieillesse.plafonnee.employeur" to 162.45, "vieillesse.plafonnee.salarie" to 131.1, "vieillesse.deplafonnee.employeur" to 40.09, "vieillesse.deplafonnee.salarie" to 7.6,
            "retraite.employeur" to 114.19, "retraite.salarie" to 76.19, "famille" to 99.75, "chomage" to 80.75, "autres" to 31.27,
            "csg.deductible" to 128.3, "csg.non_deductible" to 54.72, "exo.employeur" to 723.9,
            "total.employeur" to 111.12, "total.salarie" to 417.9, "cout" to 2011.12, "net.avant_impot" to 1482.1, "net.imposable" to 1556.81))
        val e = Employe("e1", "Marie", brutMensuel = 1900.0, tauxPas = 2.0)
        val local = Paie.bulletin(e, ConfigPaie(), "2026-11").copy(brut = 1900.0) // montants URSSAF ci-dessus calculés pour 1 900 €
        val b = BaremesCalcul.bulletinOfficiel(local, m, 4005.0, "2026-11-13")
        assertEquals(1900.0, b.brut, 0.01)
        assertEquals(2011.12, b.coutEmployeur, 0.01)
        assertEquals(1482.1, b.netAvantImpot, 0.01)
        assertEquals(31.14, b.pas, 0.01)
        assertEquals(1450.96, b.netAPayer, 0.01)
        assertTrue(b.lignes.any { it.libelle.startsWith("Réduction générale") && it.montantPatronal < 0 })
        assertTrue(b.source.contains("URSSAF"))
        val md = Rendu.rendreBulletin(SocieteData(), b)
        assertTrue(md, md.contains("moteur officiel de l'URSSAF"))
    }
}
