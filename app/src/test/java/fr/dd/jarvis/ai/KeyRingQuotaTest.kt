package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyRingQuotaTest {
    @Test fun creditsEpuisesMettentLeFournisseurEnPauseLongue() {
        assertEquals(24 * 60 * 60_000L, KeyRing.pause(429, "You have no credits remaining. Add credits to continue using the API."))
        assertEquals(24 * 60 * 60_000L, KeyRing.pause(402, "insufficient_quota: billing hard limit reached"))
    }

    @Test fun limiteDeDebitConserveLeDelaiAnnonceParLeFournisseur() {
        assertEquals(31_000L, KeyRing.pause(429, "Rate limit exceeded. Please retry in 30s"))
    }
}
