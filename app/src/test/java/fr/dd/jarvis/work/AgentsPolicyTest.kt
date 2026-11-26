package fr.dd.jarvis.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentsPolicyTest {
    @Test
    fun rangementGmailPeutFonctionnerSansAgentDeLectureDesNouveauxMails() {
        assertTrue(gmailOrganizerEnabled(agentRanger = true, only = null))
        assertTrue(gmailOrganizerEnabled(agentRanger = true, only = "mails"))
        assertFalse(gmailOrganizerEnabled(agentRanger = false, only = null))
        assertFalse(gmailOrganizerEnabled(agentRanger = true, only = "notifs"))
    }
}
