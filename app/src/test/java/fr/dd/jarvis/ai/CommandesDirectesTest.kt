package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CommandesDirectesTest {
    private val maintenant = LocalDateTime.of(2026, 11, 14, 9, 30)   // samedi
    private fun i(t: String) = CommandesDirectes.intention(t, maintenant)

    @Test fun platGardeLesPositions() {
        val o = "Envoie un SMS à Élodie : j'arrive !"
        assertEquals(o.length, CommandesDirectes.plat(o).length)
        assertTrue(CommandesDirectes.plat(o).startsWith("envoie un sms a elodie"))
    }

    @Test fun heureDateBatterie() {
        assertEquals("heure", i("Quelle heure est-il ?")?.local)
        assertEquals("heure", i("il est quelle heure stp")?.local)
        assertEquals("date", i("on est quel jour ?")?.local)
        assertEquals("batterie", i("combien de batterie ?")?.local)
        assertEquals("batterie", i("niveau de batterie du téléphone")?.local)
    }

    @Test fun durees() {
        assertEquals(600, CommandesDirectes.duree("10 minutes"))
        assertEquals(5400, CommandesDirectes.duree("1h30"))
        assertEquals(5400, CommandesDirectes.duree("une heure et demie"))
        assertEquals(90, CommandesDirectes.duree("1 minute 30"))
        assertEquals(45, CommandesDirectes.duree("45 secondes"))
        assertEquals(900, CommandesDirectes.duree("un quart d heure"))
        assertNull(CommandesDirectes.duree("les pates"))
    }

    @Test fun heures() {
        assertEquals(7 to 0, CommandesDirectes.heureDe("7h"))
        assertEquals(6 to 30, CommandesDirectes.heureDe("a 6h30"))
        assertEquals(19 to 15, CommandesDirectes.heureDe("7 heures et quart du soir"))
        assertEquals(7 to 45, CommandesDirectes.heureDe("8h moins le quart"))
        assertEquals(12 to 0, CommandesDirectes.heureDe("midi"))
        assertEquals(7 to 30, CommandesDirectes.heureDe("7 30"))
        assertNull(CommandesDirectes.heureDe("25h"))
    }

    @Test fun alarmesMinuteursChrono() {
        i("Mets une alarme à 7h")!!.let { assertEquals("alarme_creer", it.outil); assertEquals("07:00", it.args.getString("heure")) }
        i("réveille-moi demain à 6h30")!!.let { assertEquals("06:30", it.args.getString("heure")) }
        i("mets un réveil à 6h tous les jours")!!.let { assertEquals("06:00", it.args.getString("heure")); assertEquals("tous les jours", it.args.getString("jours")) }
        i("alarme à 7h en semaine pour le travail")!!.let { assertEquals("semaine", it.args.getString("jours")); assertEquals("le travail", it.args.getString("libelle")) }
        i("alarme dans 20 minutes")!!.let { assertEquals("09:50", it.args.getString("heure")) }
        assertNull(i("supprime l'alarme de 7h"))
        assertNull(i("quelles sont mes alarmes"))
        i("Mets un minuteur de 10 minutes pour les pâtes")!!.let { assertEquals("minuteur_creer", it.outil); assertEquals("600", it.args.getString("secondes")) }
        i("minuteur 1h30")!!.let { assertEquals("5400", it.args.getString("secondes")) }
        assertEquals("demarrer", i("lance le chrono")!!.args.getString("action"))
        assertEquals("arreter", i("arrête le chronomètre")!!.args.getString("action"))
    }

    @Test fun rappels() {
        i("Rappelle-moi d'appeler Martin demain à 9h")!!.let {
            assertEquals("rappel_creer", it.outil); assertEquals("Appeler Martin", it.args.getString("texte")); assertEquals("demain a 9h", it.args.getString("quand"))
            assertTrue(it.texteLibre)
        }
        i("rappelle-moi dans 20 minutes de sortir le gâteau")!!.let { assertEquals("Sortir le gâteau", it.args.getString("texte")); assertEquals("dans 20 minutes", it.args.getString("quand")) }
        i("rappelle-moi dans deux heures de rappeler Paul")!!.let { assertEquals("dans 2 heures", it.args.getString("quand")) }
        i("rappelle-moi tous les jours à 8h de prendre mes médicaments")!!.let { assertEquals("Prendre mes médicaments", it.args.getString("texte")) }
        assertNull(i("rappelle-moi le code du portail"))
        assertNull(i("rappelle-moi"))
    }

    @Test fun appelsEtSms() {
        i("Appelle Angeline")!!.let { assertEquals("appeler_contact", it.outil); assertEquals("Angeline", it.args.getString("contact")) }
        i("appelle le 06 12 34 56 78")!!.let { assertEquals("06 12 34 56 78", it.args.getString("contact")) }
        assertNull(i("appelle Paul pour lui dire que j'arrive"))
        assertNull(i("appelle Paul sur WhatsApp"))
        assertNull(i("comment s'appelle ce film"))
        i("Envoie un SMS à Élodie : j'arrive dans 10 minutes")!!.let {
            assertEquals("envoyer_sms", it.outil); assertEquals("Élodie", it.args.getString("destinataire")); assertEquals("J'arrive dans 10 minutes", it.args.getString("texte"))
        }
        i("envoie un texto à Paul pour lui dire que je serai en retard")!!.let { assertEquals("Paul", it.args.getString("destinataire")); assertEquals("Je serai en retard", it.args.getString("texte")) }
        i("dis à maman par SMS que je rentre ce soir")!!.let { assertEquals("maman", it.args.getString("destinataire")); assertEquals("Je rentre ce soir", it.args.getString("texte")) }
        assertNull(i("envoie un SMS à Paul"))
    }

    @Test fun meteoPlanning() {
        i("Quel temps fait-il ?")!!.let { assertEquals("meteo", it.outil); assertEquals("", it.args.getString("lieu")) }
        i("la météo de demain à Dijon")!!.let { assertEquals("Dijon", it.args.getString("lieu")); assertEquals("2", it.args.getString("jours")) }
        i("va-t-il pleuvoir cet après-midi ?")!!.let { assertEquals("1", it.args.getString("jours")) }
        assertNull(i("quelle température fait-il dans le salon"))
        i("mon planning de demain")!!.let { assertEquals("get_planning", it.outil); assertEquals("2026-11-15", it.args.getString("date")) }
        i("qu'est-ce que j'ai cette semaine ?")!!.let { assertEquals("2026-11-14", it.args.getString("date")); assertEquals("2", it.args.getString("days")) }
        i("mon agenda de lundi")!!.let { assertEquals("2026-11-16", it.args.getString("date")) }
        i("est-ce que j'ai des rendez-vous demain")!!.let { assertEquals("2026-11-15", it.args.getString("date")) }
    }

    @Test fun autres() {
        assertEquals("lire_sms", i("lis mes derniers SMS")?.outil)
        assertEquals("lire_notifications", i("lis mes notifications")?.outil)
        assertEquals("morning_briefing", i("fais-moi le point du jour")?.outil)
        assertEquals("taches_lister", i("mes tâches")?.outil)
        i("emmène-moi à la gare de Dijon")!!.let { assertEquals("naviguer", it.outil); assertEquals("la gare de Dijon", it.args.getString("destination")) }
        i("lance Waze vers Beaune")!!.let { assertEquals("Waze", it.args.getString("appli")); assertEquals("Beaune", it.args.getString("destination")) }
        assertNull(i("emmène-moi chez Martin"))
        // « Lance l'itinéraire jusqu'à … » : le guidage est LANCÉ (avant, seulement calculé).
        i("Lance l'itinéraire jusqu'à Dijon")!!.let { assertEquals("naviguer", it.outil); assertEquals("Dijon", it.args.getString("destination")) }
        i("démarre le trajet vers Lyon")!!.let { assertEquals("naviguer", it.outil); assertEquals("Lyon", it.args.getString("destination")) }
        i("itinéraire vers Beaune avec Waze")!!.let { assertEquals("Beaune", it.args.getString("destination")); assertEquals("Waze", it.args.getString("appli")) }
        i("lance un itinéraire jusqu'à Chalon-sur-Saône")!!.let { assertEquals("Chalon-sur-Saône", it.args.getString("destination")) }
        assertNull(i("itinéraire de ma tournée de demain"))
        assertNull(i("combien de temps de trajet jusqu'à Dijon"))
        // Tournures parlées : guidage lancé directement (avant : cerveau IA, qui demandait « oui ou non »).
        fun dest(t: String) = i(t)?.takeIf { it.outil == "naviguer" }?.args?.getString("destination")
        assertEquals("Dijon", dest("est-ce que tu peux lancer la navigation vers Dijon"))
        assertEquals("Beaune", dest("tu peux me lancer l'itinéraire jusqu'à Beaune"))
        assertEquals("Semur", dest("lance-moi la navigation vers Semur"))
        assertEquals("la gare de Dijon", dest("tu peux m'emmener à la gare de Dijon"))
        assertEquals("Dijon", dest("je veux aller à Dijon"))
        assertEquals("Dijon", dest("lance la navigation pour aller à Dijon"))
        assertEquals("Dijon", dest("fais-moi l'itinéraire pour Dijon"))
        assertEquals("Dijon", dest("trajet pour Dijon"))
        assertEquals("Dijon", dest("direction Dijon"))
        assertEquals("Dijon", dest("Jarvis lance la navigation vers Dijon s'il te plaît"))
        assertEquals("21 rue de Massingy, 21350 Vitteaux", dest("lance la navigation vers 21 rue de Massingy, 21350 Vitteaux"))
        assertEquals("Saint-Martin-et-Puisaye", dest("lance la navigation vers Saint-Martin-et-Puisaye"))
        assertEquals("maison", dest("je voudrais rentrer à la maison"))
        assertEquals("maison", dest("navigation vers chez moi"))
        i("lance la navigation avec Waze vers Dijon")!!.let { assertEquals("Dijon", it.args.getString("destination")); assertEquals("Waze", it.args.getString("appli")) }
        assertNull(dest("je veux aller au lit"))
        assertNull(dest("je veux aller dormir"))
        assertNull(dest("itinéraire vers Dijon et Beaune"))
        i("joue Daft Punk sur Spotify")!!.let { assertEquals("jouer_musique", it.outil); assertEquals("Daft Punk", it.args.getString("requete")); assertEquals("Spotify", it.args.getString("appli")) }
        i("mets la playlist sport")!!.let { assertEquals("playlist sport", it.args.getString("requete")) }
        assertNull(i("mets du chauffage"))
        assertNull(i("joue un film"))
        i("Ouvre WhatsApp")!!.let { assertEquals("phone_action", it.outil); assertEquals("WhatsApp", it.appli) }
        assertNull(i("Hermès, quelle heure est-il ?"))
    }
}
