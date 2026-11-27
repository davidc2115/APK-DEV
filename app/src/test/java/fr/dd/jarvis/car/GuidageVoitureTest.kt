package fr.dd.jarvis.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import fr.dd.jarvis.societe.Geo.Precision as P

class GuidageVoitureTest {
    @Test fun adresseAuFormatAndroidAuto() {
        assertEquals("12+rue+de+la+Paix,+Dijon", GuidageVoiture.requete("12 rue de la Paix,  Dijon"))
        assertEquals("%C3%89glise+Saint-B%C3%A9nigne", GuidageVoiture.requete("Église Saint-Bénigne"))
        assertEquals("geo:0,0?q=8+place+d%27Arcy,+21000+Dijon", GuidageVoiture.geo("8 place d'Arcy, 21000 Dijon", null))
        // Point GPS vérifié : avec l'action NAVIGATE, « geo:lat,lon » démarre la navigation (l'épingle seule, c'est VIEW).
        assertEquals("geo:47.322047,5.041480", GuidageVoiture.geo("8 place d'Arcy, Dijon", 47.322047 to 5.04148))
        assertEquals("geo:47.322047,5.041480", GuidageVoiture.geo("", 47.322047 to 5.04148))
    }

    @Test fun seuleUneNotificationExplicitementNavigationConfirmeLeDepart() {
        GuidageVoiture.reinitialiserPourTests()
        val t = System.currentTimeMillis() - 100
        GuidageVoiture.noter("com.spotify.music", t + 10, "navigation", true) // mauvais paquet
        GuidageVoiture.noter("com.waze", t + 20, null, true) // notification persistante, mais pas classée navigation
        assertTrue(GuidageVoiture.demarreDepuis(t) != true)
        GuidageVoiture.noter("com.waze", t + 30, android.app.Notification.CATEGORY_NAVIGATION, false)
        assertTrue(GuidageVoiture.demarreDepuis(t) == true)
        GuidageVoiture.reinitialiserPourTests()
    }

    @Test fun ouvertureDeWazeSeuleNeConfirmePasLeGuidage() {
        GuidageVoiture.reinitialiserPourTests()
        val t = System.currentTimeMillis() - 100
        GuidageVoiture.noterFenetre("com.waze")
        assertTrue(GuidageVoiture.fenetreDepuis(t))
        assertTrue(GuidageVoiture.demarreDepuis(t) != true) // l'ouverture ne vaut jamais départ d'itinéraire
        assertTrue(!GuidageVoiture.fenetreDepuis(System.currentTimeMillis() + 1))
        GuidageVoiture.reinitialiserPourTests()
    }
}

class CoupureVoitureTest {
    private fun mots(vararg m: Triple<String, Double, Double>) = org.json.JSONArray().apply {
        m.forEach { (w, conf, d) -> put(org.json.JSONObject().put("word", w).put("conf", conf).put("start", 1.0).put("end", 1.0 + d)) }
    }

    @Test fun jarvisAuMilieuDeLaVoixDeJarvis() {
        val phrase = mots(Triple("le", 0.9, 0.2), Triple("rendez", 0.9, 0.3), Triple("jarvis", 0.92, 0.5), Triple("vous", 0.9, 0.2))
        // Mot d'éveil : seulement en tête ; coupure en voiture : n'importe où.
        assertTrue(!fr.dd.jarvis.voice.VoskWake.estReveil(phrase, 0.8))
        assertTrue(fr.dd.jarvis.voice.VoskWake.contientMot(phrase, 0.8))
        assertTrue(!fr.dd.jarvis.voice.VoskWake.contientMot(mots(Triple("jarvis", 0.5, 0.5)), 0.8))   // trop incertain
        assertTrue(!fr.dd.jarvis.voice.VoskWake.contientMot(mots(Triple("jarvis", 0.95, 0.1)), 0.8))  // trop bref
    }
}

class MicroVoitureSensibleTest {
    @Test fun porteSensibleEnVoiture() {
        val normale = fr.dd.jarvis.voice.PorteVocale(); val voiture = fr.dd.jarvis.voice.PorteVocale().apply { sensible = true }
        repeat(20) { normale.trame(60.0); voiture.trame(60.0) }               // habitacle calme
        // Voix basse (≈ 110) : ignorée d'habitude, entendue en voiture.
        assertTrue(normale.trame(110.0) == fr.dd.jarvis.voice.PorteVocale.Decision.IGNORER)
        assertTrue(voiture.trame(110.0) == fr.dd.jarvis.voice.PorteVocale.Decision.OUVRIR)
    }

    @Test fun gainAutomatique() {
        val g = fr.dd.jarvis.voice.GainAuto()
        val faible = ShortArray(1600) { if (it % 2 == 0) 150 else -150 }
        var sortie = faible
        repeat(80) { sortie = g.appliquer(faible, faible.size) }
        assertTrue(g.gain > 4.0)                                              // voix faible remontée
        assertTrue(sortie.maxOf { kotlin.math.abs(it.toInt()) } in 700..1000)
        val fort = ShortArray(1600) { if (it % 2 == 0) 30000 else -30000 }
        val g2 = fr.dd.jarvis.voice.GainAuto()
        repeat(80) { g2.appliquer(fort, fort.size) }
        assertTrue(g2.gain < 1.05)                                            // jamais d'amplification d'un son fort
        assertTrue(fr.dd.jarvis.voice.VoskWake.FENETRE_MUSIQUE == 30)
    }
}

/** Le guidage part tout seul : coordonnées vérifiées, lieux « une école primaire », appui sur « Y aller ». */
class GuidageDirectTest {
    private fun loc(p: fr.dd.jarvis.societe.Geo.Precision, trouve: String = "") = fr.dd.jarvis.societe.Geo.Localisation(fr.dd.jarvis.societe.Geo.Point(47.39, 4.54), p, trouve)

    @Test fun androidAutoGuideSurLesCoordonnees() {
        // Action NAVIGATE : geo:lat,lon = navigation directe (pas de recherche à refaire dans Waze / Maps).
        assertEquals("geo:47.397712,4.546730", GuidageVoiture.geo("21 rue de Massingy, Vitteaux", 47.397712 to 4.54673))
        assertEquals("geo:0,0?q=Vitteaux", GuidageVoiture.geo("Vitteaux", null))
    }

    @Test fun pointAssezPrecis() {
        assertTrue(GuidageVoiture.pointFiable("21 rue de Massingy à Vitteaux", loc(P.NUMERO)))
        assertTrue(!GuidageVoiture.pointFiable("21 rue de Massingy à Vitteaux", loc(P.RUE)))       // numéro demandé, rue seule trouvée
        assertTrue(GuidageVoiture.pointFiable("rue de Massingy, Vitteaux", loc(P.RUE)))
        assertTrue(GuidageVoiture.pointFiable("Vitteaux", loc(P.COMMUNE, "21350 Vitteaux")))
        assertTrue(!GuidageVoiture.pointFiable("Leclerc Montbard", loc(P.COMMUNE, "21500 Montbard")))  // le magasin, pas le centre-ville
        assertTrue(GuidageVoiture.pointFiable("ferme de la Grange", loc(P.LIEU_DIT)))
        assertTrue(!GuidageVoiture.pointFiable("Dijon", null))
    }

    @Test fun boutonsDeDepart() {
        listOf("Y aller", "Démarrer", "Go", "Démarrer la navigation", "C'est parti !").forEach { assertTrue(it, GuidageVoiture.boutonDepart(it)) }
        listOf("Partir plus tard", "Itinéraires", "Annuler", "Enregistrer").forEach { assertTrue(it, !GuidageVoiture.boutonDepart(it)) }
    }
}

class LieuProcheTest {
    @Test fun sorteDeLieu() {
        val g = fr.dd.jarvis.societe.Geo
        assertEquals("ecole primaire", g.categorieProche("une école primaire"))
        assertEquals("pharmacie", g.categorieProche("la pharmacie la plus proche"))
        assertEquals("station essence", g.categorieProche("une station essence près d'ici"))
        assertEquals("boulangerie", g.categorieProche("la boulangerie du coin"))
        assertNull(g.categorieProche("21 rue de Massingy à Vitteaux"))
        assertNull(g.categorieProche("Dijon"))
        assertNull(g.categorieProche("la gare de Dijon"))
        assertNull(g.categorieProche("une rue de Dijon"))
    }
}
