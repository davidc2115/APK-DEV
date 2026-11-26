package fr.dd.jarvis.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordApiDiagnosticsTest {
    @Test
    fun rateLimitUsesRetryAfterHeader() {
        val message = Discord.messageLimiteDiscord("2.5", """{"retry_after":9.0}""")
        assertTrue(message.contains("2,5 seconde(s)"))
        assertTrue(message.contains("HTTP 429"))
    }

    @Test
    fun rateLimitUsesBodyWhenHeaderIsMissing() {
        val message = Discord.messageLimiteDiscord(null, """{"retry_after":3,"global":true}""")
        assertTrue(message.contains("3 seconde(s)"))
        assertTrue(message.contains("Limite globale"))
    }

    @Test
    fun rateLimitWithoutDelayRemainsActionable() {
        val message = Discord.messageLimiteDiscord(null, "{}")
        assertTrue(message.contains("relance la demande"))
        assertTrue(message.contains("n'attend pas automatiquement"))
    }
}
