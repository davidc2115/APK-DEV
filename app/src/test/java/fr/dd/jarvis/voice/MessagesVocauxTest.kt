package fr.dd.jarvis.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MessagesVocauxTest {
    private val MV = MessagesVocaux
    private val pereNoel = MV.parId("pere_noel")!!

    @Test fun personnagesCoherents() {
        val ids = MV.TOUS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        MV.FANTAISIE.forEach { p ->
            assertTrue(p.id + " : voix Gemini inconnue", p.voix in VoixNaturelle.CATALOGUE)
            assertTrue(p.id + " : pas d'exemple", MV.EXEMPLES.containsKey(p.id))
            assertTrue(p.id + " : pas d'effet", MV.EFFETS.containsKey(p.id))
        }
        assertTrue(MV.effet(MV.parId("lutin")!!).vitesse > 1.2)
        assertTrue(MV.effet(pereNoel).vitesse < 1.0)
        assertEquals(1.0, MV.effet(MV.parId("jarvis")!!).vitesse, 0.0)
    }

    @Test fun rolesParLeurNom() {
        assertEquals("pere_noel", MV.role("le Père Noël")?.id)
        assertEquals("pere_noel", MV.role("PÈRE NOËL")?.id)
        assertEquals("pere_noel", MV.role("la voix du père noël")?.id)
        assertEquals("lutin", MV.role("Lutin")?.id)
        assertEquals("lutin", MV.role("un petit lutin")?.id)
        assertEquals("lutine", MV.role("la lutine")?.id)
        assertEquals("fee", MV.role("une fée")?.id)
        assertEquals("mere_noel", MV.role("Mère Noël")?.id)
        assertEquals("bonhomme_neige", MV.role("Bonhomme de neige")?.id)
        assertEquals("jarvis", MV.role("Jarvis")?.id)
        assertNull(MV.role("Léo"))
        assertNull(MV.role("Attention"))
    }

    @Test fun dialogueEnRepliques() {
        val r = MV.scenario("""
            Père Noël : Ho ho ho ! Bonjour Léo !
            **Lutin :** Hi hi (en riant), on t'a vu ranger ta chambre !
            Il était temps…
            Père Noël : Attention : une surprise t'attend 🎁 sous le sapin.
        """.trimIndent(), MV.parId("jarvis")!!)
        assertEquals(listOf("pere_noel", "lutin", "pere_noel"), r.map { it.role.id })
        assertEquals("Hi hi, on t'a vu ranger ta chambre ! Il était temps…", r[1].texte)
        assertEquals("Attention : une surprise t'attend sous le sapin.", r[2].texte)
    }

    @Test fun texteSimpleUneSeuleVoix() {
        val r = MV.scenario("Bonjour Emma !\nTu as été très sage cette année.\n\nÀ bientôt !", pereNoel)
        assertEquals(1, r.size)
        assertEquals("pere_noel", r[0].role.id)
        assertEquals("Bonjour Emma ! Tu as été très sage cette année. À bientôt !", r[0].texte)
        // Un « Nom : » qui n'est pas un personnage reste dans le texte.
        assertEquals("Léo : tu es génial.", MV.scenario("Léo : tu es génial.", pereNoel).single().texte)
        assertTrue(MV.scenario("   \n  ", pereNoel).isEmpty())
    }

    @Test fun nettoyage() {
        assertEquals("Bonjour mon grand !", MV.nettoyer("[rire] Bonjour *chuchote* mon **grand** ! 🎅✨"))
        assertEquals("Père Noël : Ho ho ho\nLutin : Coucou", MV.nettoyerRedaction("# Message\n**Père Noël** : « Ho ho ho »\nLutin : \"Coucou\""))
    }

    @Test fun titres() {
        assertEquals("Message du Père Noël", MV.titre(listOf(pereNoel)))
        assertEquals("Message de la Fée", MV.titre(listOf(MV.parId("fee")!!)))
        assertEquals("Message du Lutin", MV.titre(listOf(MV.parId("lutin")!!)))
        assertEquals("Dialogue · Père Noël & Lutin", MV.titre(listOf(pereNoel, MV.parId("lutin")!!, pereNoel)))
    }

    @Test fun consigneDeRedaction() {
        val un = MV.consigneRedaction(listOf(pereNoel))
        assertTrue(un.contains("Père Noël") && un.contains("60 à 140 mots"))
        val deux = MV.consigneRedaction(listOf(pereNoel, MV.parId("lutin")!!))
        assertTrue(deux.contains("« Nom : texte »") && deux.contains("Père Noël / Lutin"))
    }

    private fun sinus(n: Int, f: Double = 440.0, a: Double = 0.5) =
        ShortArray(n) { (kotlin.math.sin(2 * Math.PI * f * it / AudioFx.FREQ) * a * 32767).toInt().toShort() }

    @Test fun effetsAudio() {
        val s = sinus(24_000)
        assertEquals(18_461, AudioFx.vitesse(s, 1.3).size)           // lutin : plus court (donc plus aigu)
        assertEquals(25_806, AudioFx.vitesse(s, 0.93).size)          // Père Noël : plus long (donc plus grave)
        assertEquals(48_000, AudioFx.reechantillonner(s, 12_000, 24_000).size)
        assertTrue(AudioFx.reverb(s, 0.4).size > s.size)            // traîne de l'écho
        assertEquals(s.size, AudioFx.robot(s).size)
        val n = AudioFx.normaliser(sinus(2400, a = 0.5))
        assertTrue(abs(n.maxOf { abs(it.toInt()) } - (0.89 * 32767)) < 400)
        // Un son très faible est remonté au plus ×4.
        assertTrue(AudioFx.normaliser(sinus(2400, a = 0.1)).maxOf { abs(it.toInt()) } < 14_000)
        assertArrayEquals(s, AudioFx.versShorts(AudioFx.versOctets(s)))
        assertEquals(1200, AudioFx.mono(ShortArray(2400), 2).size)
        assertEquals(1000L, AudioFx.dureeMs(s))
    }

    @Test fun clochettesEtWav() {
        val c = AudioFx.clochettes(1000)
        assertEquals(24_000, c.size)
        assertTrue(c.any { abs(it.toInt()) > 1000 })
        assertArrayEquals(c, AudioFx.clochettes(1000))              // déterministe
        assertFalse(c.takeLast(10).any { abs(it.toInt()) > 200 })    // s'éteint à la fin
        val w = AudioFx.wav(c)
        assertEquals(44 + c.size * 2, w.size)
        val a = VoixNaturelle.decoder(w)!!
        assertEquals(24_000, a.frequence)
        assertEquals(c.size * 2, a.pcm.size)
    }
}
