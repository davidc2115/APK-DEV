package fr.dd.jarvis.societe

import fr.dd.jarvis.societe.core.Societe
import fr.dd.jarvis.societe.core.SocieteData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DeclarationEnLigneTest {
    @Test fun sitesEtSecurite() {
        assertEquals("urssaf_ae", DeclarationEnLigne.site("https://www.autoentrepreneur.urssaf.fr/portail/accueil.html")?.cle)
        assertEquals("impots_pro", DeclarationEnLigne.site("https://cfspro.impots.gouv.fr/LoginAccess")?.cle)
        assertEquals("impots_part", DeclarationEnLigne.site("https://cfspart-idp.impots.gouv.fr/oauth2/authorize")?.cle)
        // Jamais d'identifiants sur une page en http ni sur un faux domaine.
        assertNull(DeclarationEnLigne.site("http://www.autoentrepreneur.urssaf.fr"))
        assertNull(DeclarationEnLigne.site("https://urssaf.fr.faux-site.com/login"))
        assertNull(DeclarationEnLigne.site("https://autoentrepreneur-urssaf.fr/"))
        assertTrue(DeclarationEnLigne.officiel("https://www.impots.gouv.fr/professionnel"))
        assertFalse(DeclarationEnLigne.officiel("https://www.declaration-urssaf-en-ligne.com"))
    }

    @Test fun lectureDePageEtAction() {
        val json = """{"u":"https://www.autoentrepreneur.urssaf.fr/x","ti":"Déclarer","x":"Déclaration d'octobre","e":[{"i":1,"k":"champ","l":"Identifiant","t":"text","r":true},{"i":2,"k":"champ","l":"Mot de passe","t":"password","mdp":1},{"i":3,"k":"champ","l":"Prestations BIC","t":"text","r":true,"v":"1234"}]}"""
        val p = DeclarationEnLigne.page(json)
        assertTrue(p.contains("[2] champ « Mot de passe » (mot de passe)"))
        assertTrue(p.contains("valeur : 1234"))
        assertTrue(DeclarationEnLigne.demandeMotDePasse(json))
        val a = DeclarationEnLigne.action("Voici : ```json\n{\"pensee\":\"ok\",\"action\":\"Saisir\",\"id\":3,\"texte\":\"1234\"}\n```")!!
        assertEquals("saisir", a.action); assertEquals(3, a.id); assertEquals("1234", a.texte)
        // Le mot de passe passe par une chaîne JSON échappée (aucune injection possible dans le script).
        assertTrue(DeclarationEnLigne.jsConnexion("moi@x.fr", "a'b\"c</script>").contains("\"a'b\\\"c<\\/script>\""))
    }

    @Test fun demandesAlaVoix() {
        val data = SocieteData(societe = Societe(forme = "Micro-entreprise", dateCreation = "2026-01-01"))
        val auj = LocalDate.of(2026, 11, 21)
        assertTrue(DeclarationEnLigne.intention("fais ma déclaration URSSAF", data, auj)!!.id.startsWith("urssaf"))
        assertTrue(DeclarationEnLigne.intention("déclare mon chiffre d'affaires", data, auj)!!.id.startsWith("urssaf"))
        assertNull(DeclarationEnLigne.intention("qu'est-ce que je dois déclarer ?", data, auj))
        assertNull(DeclarationEnLigne.intention("fais-moi un café", data, auj))
    }
}
