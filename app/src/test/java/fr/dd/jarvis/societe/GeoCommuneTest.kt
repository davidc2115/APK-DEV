package fr.dd.jarvis.societe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoCommuneTest {
    @Test fun leNomDeRueNePeutPasCompleterUnNomDeCommuneCompose() {
        // « Massingy » appartient ici au nom de rue ; la commune annoncée est Vitteaux.
        val r = Geo.Requete("21 rue de Massingy Vitteaux")
        assertTrue(Geo.communePresente("Vitteaux", r))
        assertFalse(Geo.communePresente("Massingy-lès-Vitteaux", r))
    }

    @Test fun laCommuneComposeeResteAccepteeQuandElleEstExplicitementIndiquee() {
        val r = Geo.Requete("21 rue de Massingy, Massingy-lès-Vitteaux")
        assertTrue(Geo.communePresente("Massingy-lès-Vitteaux", r))
    }

    @Test fun unNomDeCommuneAbrégéEstAccepteDansLeSegmentDeLocalite() {
        val r = Geo.Requete("21 rue des Lilas, 21150 Venarey")
        assertTrue(Geo.communePresente("Venarey-les-Laumes", r))
    }

    @Test fun unNomDeCommuneNePeutPasEtreDevineDepuisLaRueSeule() {
        val r = Geo.Requete("21 rue de Massingy")
        assertFalse(Geo.communePresente("Massingy-lès-Vitteaux", r))
    }
}
