package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalculsTest {
    private val ven = LocalDate.of(2026, 11, 20)
    private fun r(t: String) = Calculs.repondre(t, ven)
    private fun ok(t: String, attendu: String) { val x = r(t); assertTrue("« $t » → $x", x != null && x.contains(attendu)) }

    @Test fun operationsEtPourcentages() {
        ok("combien font 15 % de 240", "= 36")
        ok("Combien font 12 fois 45 ?", "= 540")
        ok("calcule 1250 divisé par 12", "= 104,166667")
        ok("12*45", "= 540")
        ok("combien font (3 + 4) x 2", "= 14")
        ok("racine carrée de 144", "= 12")
        ok("combien fait 2 puissance 10", "= 1 024")
        ok("80 plus 20 %", "= 96")
        ok("remise de 15 % sur 80", "= 68")
        ok("100 HT en TTC", "120")
        ok("120 TTC en HT", "100")
        ok("ajoute 20 % à 150", "= 180")
        ok("combien font 10 divisé par 0", "division par zéro")
        ok("combien font 3,5 plus 1,25", "= 4,75")
    }

    @Test fun conversions() {
        ok("convertis 30 miles en km", "48,28032 km")
        ok("100 degrés fahrenheit en celsius", "37,777778 °C")
        ok("combien de cl dans 1,5 litre", "150 cL")
        ok("convertis 5 pieds en mètres", "1,524 m")
        ok("2 heures en minutes", "120 min")
        ok("50 km/h en mph", "mph")
        ok("1 go en mo", "1 000 Mo")
    }

    @Test fun calendrier() {
        ok("combien de jours avant Noël", "dans 35 jours")
        ok("combien de jours avant le 24 novembre", "dans 4 jours")
        ok("quel jour tombe le 3 décembre", "jeudi 3 décembre 2026")
        ok("quelle date serons-nous dans 10 jours", "lundi 30 novembre 2026")
        ok("combien de jours entre le 3 et le 24 novembre", "21 jours")
        ok("on est quelle semaine", "semaine 47")
        ok("dans combien de jours est Pâques", "dimanche 28 mars 2027")
    }

    @Test fun pasUnCalcul() {
        for (t in listOf("combien de temps pour aller à Dijon", "combien de temps jusqu'à mon rendez-vous de lundi", "il fait combien dehors",
            "combien de mails non lus", "quel jour sommes-nous", "2 fois par semaine rappelle-moi de boire", "mets le son à 50", "24/11",
            "combien font 12", "réveille-moi dans 3 jours", "combien j'ai de rendez-vous demain", "appelle le 06 12 34 56 78"))
            assertNull(t, r(t))
    }
}
