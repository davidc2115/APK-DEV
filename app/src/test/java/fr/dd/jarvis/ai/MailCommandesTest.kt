package fr.dd.jarvis.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailCommandesTest {
    @Test fun trier() {
        val (o, a) = MailCommandes.intention("Trie mes mails")!!
        assertEquals("trier_mails", o); assertFalse(a.has("statut"))           // règle enregistrée (non lus par défaut)
        val (_, b) = MailCommandes.intention("trie tous mes emails")!!
        assertEquals("tous", b.getString("statut")); assertFalse(b.has("enregistrer"))   // pour cette fois
        assertEquals("non_lus", MailCommandes.intention("trie les mails non lus")!!.second.getString("statut"))
    }

    @Test fun regle() {
        val (o, a) = MailCommandes.intention("désormais trie aussi les mails déjà lus et les non lus")!!
        assertEquals("regler_tri_mails", o); assertEquals("tous", a.getString("statut"))
        assertEquals("non_lus", MailCommandes.intention("à partir de maintenant le tri ne prend que les mails non lus")!!.second.getString("statut"))
        assertEquals("voir", MailCommandes.intention("quelle est la règle du tri des mails ?")!!.second.getString("action"))
    }

    @Test fun marquer() {
        val (o, a) = MailCommandes.intention("Marque tous mes mails comme lus")!!
        assertEquals("marquer_mails_traites", o); assertEquals("lu", a.getString("action")); assertEquals("true", a.getString("tous"))
        assertEquals("traite", MailCommandes.intention("marque tous les e-mails comme traités")!!.second.getString("action"))
        val p = MailCommandes.intention("marque les mails des Pubs comme lus")!!.second
        assertEquals("Pubs", p.getString("libelle"))
        val e = MailCommandes.intention("marque les mails d'amazon comme lus")!!.second
        assertEquals("amazon", e.getString("expediteur"))
        assertNull(MailCommandes.intention("marque ce mail comme lu"))
        assertNull(MailCommandes.intention("lis-moi mes mails"))
    }

    @Test fun requete() {
        val q = MailCommandes.requeteGmail("lu", JSONObject().put("libelle", "Super U/Pubs").put("expediteur", "magasins-u.com"), "")
        assertTrue(q, q.contains("in:inbox") && q.contains("is:unread") && q.contains("label:super-u-pubs") && q.contains("from:(magasins-u.com)"))
        assertTrue(MailCommandes.requeteGmail("traite", JSONObject(), "").contains("-label:traité"))
    }
}

class MailCommandesTest2 {
    @Test fun maintenant() {
        val (o, a) = MailCommandes.intention("trie maintenant tous mes mails")!!
        assertEquals("trier_mails", o); assertEquals("tous", a.getString("statut"))
        assertEquals("trier_mails", MailCommandes.intention("trie mes mails tout de suite")!!.first)
        assertFalse(MailCommandes.intention("trie mes mails tout de suite")!!.second.has("statut"))
    }
}
