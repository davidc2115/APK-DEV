package fr.dd.jarvis.societe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoCommuneMatchingTest {
    @Test fun rueEtCommuneSansVirguleNeDoiventPasEtreFusionnees() {
        val r = Geo.Requete("21 rue de Massingy Vitteaux")
        assertTrue(Geo.communeCorrespond("Vitteaux", r))
        assertFalse(Geo.communeCorrespond("Massingy-les-Vitteaux", r))
    }

    @Test fun communeExpliciteApresVirguleEstPrioritaire() {
        val r = Geo.Requete("21 rue de Massingy, Vitteaux")
        assertTrue(Geo.communeCorrespond("Vitteaux", r))
        assertFalse(Geo.communeCorrespond("Massingy-les-Vitteaux", r))
    }

    @Test fun communeComposeeResteValideQuandElleEstEntierementIndiquee() {
        val r = Geo.Requete("21 rue de Massingy, Massingy-les-Vitteaux")
        assertTrue(Geo.communeCorrespond("Massingy-les-Vitteaux", r))
    }
}
