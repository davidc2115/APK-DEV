package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilEtFichiersTest {
    private val tls = "🔔 **Diagnostic Home Assistant**\n\nLe certificat TLS de Home Assistant ne correspond pas au nom de domaine ; la Freebox doit rediriger les ports 80 et 443 vers le serveur pour que Let's Encrypt se renouvelle."

    @Test fun suiteFreebox() {
        val h = listOf(ChatMsg(true, "Regarde pourquoi Home Assistant a une erreur TLS"), ChatMsg(false, "⏳ Je continue en arrière-plan."), ChatMsg(false, tls),
            ChatMsg(true, "très bien alors du coup est-ce que tu peux regarder directement sur la Freebox"))
        val f = FilConversation.fil(h)!!
        assertEquals(2, f.reponses.size)
        assertTrue(FilConversation.estSuite(f))
        assertTrue(FilConversation.renvoie(f.demande))
        val e = FilConversation.enrichir(h)
        assertTrue(e, e.startsWith("très bien alors du coup") && e.contains("certificat TLS") && e.contains("Regarde pourquoi Home Assistant"))
        assertTrue(FilConversation.sujet(h).contains("Freebox doit rediriger"))
        val hh = FilConversation.historiqueEnrichi(h)
        assertEquals(h.size, hh.size); assertTrue(hh.last().text.contains("[Fil de la conversation"))
    }

    @Test fun pasDeSuite() {
        // Nouvelle demande sans renvoi, après une réponse courte : telle quelle.
        val h = listOf(ChatMsg(true, "allume la lampe"), ChatMsg(false, "✅ Lampe allumée."), ChatMsg(true, "quel temps fera-t-il demain à Lyon"))
        assertEquals("quel temps fera-t-il demain à Lyon", FilConversation.enrichir(h))
        val h2 = listOf(ChatMsg(true, "Explique"), ChatMsg(false, tls), ChatMsg(true, "Donne-moi la liste des films ajoutés cette semaine sur Plex"))
        assertEquals(h2.last().text, FilConversation.enrichir(h2))
        // Réponse brève à une question.
        val h3 = listOf(ChatMsg(true, "nettoie le serveur"), ChatMsg(false, "J'ai trouvé 4 Go d'images Docker inutilisées. Veux-tu que je les supprime ?"), ChatMsg(true, "oui"))
        assertTrue(FilConversation.enrichir(h3).contains("[Fil de la conversation"))
    }

    @Test fun remplacer() {
        val src = "homeassistant:\n  name: Maison\nhttp:\n  ssl_certificate: /ssl/old.pem\n  server_port: 8123\n"
        val e = FichiersServeur.remplacer(src, "  ssl_certificate: /ssl/old.pem", "  ssl_certificate: /ssl/fullchain.pem")
        assertEquals(src.replace("old.pem", "fullchain.pem"), e.texte)
        // Avec numéros recopiés et indentation fausse.
        val e2 = FichiersServeur.remplacer(src, "   4| ssl_certificate: /ssl/old.pem", "  ssl_certificate: /ssl/x.pem")
        assertTrue(e2.texte, e2.texte.contains("  ssl_certificate: /ssl/x.pem\n  server_port"))
        val e3 = FichiersServeur.remplacer(src, "ssl_certificate:   /ssl/old.pem", "  ssl_certificate: /ssl/y.pem")
        assertTrue(e3.resume, e3.texte.contains("/ssl/y.pem") && !e3.texte.contains("old.pem"))
        try { FichiersServeur.remplacer(src, "introuvable", "x"); assertTrue(false) } catch (ex: IllegalArgumentException) { assertTrue(ex.message!!.contains("introuvable")) }
        try { FichiersServeur.remplacer("a\na\n", "a", "b"); assertTrue(false) } catch (ex: IllegalArgumentException) { assertTrue(ex.message!!.contains("2 fois")) }
        assertEquals("b\nb\n", FichiersServeur.remplacer("a\na\n", "a", "b", tout = true).texte)
        // CRLF conservé.
        assertEquals("x: 1\r\ny: 3\r\n", FichiersServeur.remplacer("x: 1\r\ny: 2\r\n", "y: 2", "y: 3").texte)
    }

    @Test fun lignes() {
        val src = "a\nb\nc\n"
        assertEquals("a\nb\nX\nc\n", FichiersServeur.inserer(src, "X", apres = "b").texte)
        assertEquals("X\na\nb\nc\n", FichiersServeur.inserer(src, "X", avant = "a").texte)
        assertEquals("a\nX\nY\nb\nc\n", FichiersServeur.inserer(src, "X\nY", ligne = 2).texte)
        assertEquals("a\nb\nc\nZ\n", FichiersServeur.ajouter(src, "Z").texte)
        assertEquals("Z\n", FichiersServeur.ajouter("", "Z").texte)
        assertEquals("a\nB1\nB2\n", FichiersServeur.remplacerLignes(src, 2, 3, "B1\nB2").texte)
        assertEquals("a\n", FichiersServeur.supprimerLignes(src, 2, 3).texte)
        assertEquals("a\nb", FichiersServeur.remplacerLignes("a\nc", 2, 2, "b").texte)
        val n = FichiersServeur.numeroter(src, 2, 3)
        assertTrue(n, n.startsWith("(3 lignes, lignes 2 à 3 affichées)") && n.contains("   2| b"))
        assertTrue(FichiersServeur.chercher("Foo\nbar\nfoo2\n", "FOO").contains("   3| foo2"))
        val d = FichiersServeur.difference("a\nb\nc\n", "a\nB\nc\n")
        assertTrue(d, d.contains("-    2| b") && d.contains("+    2| B"))
    }
}
