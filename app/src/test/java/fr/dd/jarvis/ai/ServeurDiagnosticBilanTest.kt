package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServeurDiagnosticBilanTest {
    private val sortie = """
        == Hôte Debian ==
        debian
        @DROITS ok
        @CHARGE 3.10 4.20 3.90 2
        @MEM 1890 1700 120
        @SWAP 0 0
        @DISQUE 96 / 2100000
        @DISQUE 40 /boot 300000
        @INODES 12 /
        @DEMARRAGE 2026-11-24 06:10:00
        @OOM 3
        @OOMDETAIL python3
        @OOMDETAIL Plex Media Serv
        @ECHEC fail2ban.service
        @TEMP x86_pkg_temp 88
        @TEMP acpitz 40
        @SMART sda PASSED
        @ERRDISQUE 0
        @NTP yes
        @DNS ok
        @INTERNET ok
        @MAJ 12 3
        @JOURNAUX 2.5G 2.7G
        @DOCKER active
        @CONTENEUR /homeassistant|running|0|false|7|-|2026-11-24T06:12:00Z
        @CONTENEUR /plex|exited|137|false|0|-|2026-11-23T06:12:00Z
        @DOCKER_RECUP 2.3GB
        @SSH_SERVICE active
        @SSH_TEST ok
        @SSH_PORT_CONF 2222
        @SSH_MOTDEPASSE yes
        @SSH_ROOT yes
        @ECOUTE 22 8123 32400
        @SSH_ECHECS_24H 420
        @SSH_IP_ECHECS 45.1.2.3×300
        @SSH_PERMS 755 775 600 1
        @FAIL2BAN absent
    """.trimIndent()

    @Test fun constatsLus() {
        val c = ServeurDiagnostic.analyser(sortie, java.time.LocalDateTime.of(2026, 11, 24, 14, 0))
        fun a(sujet: String, niveau: Int) = assertTrue("$sujet niveau $niveau : $c", c.any { it.sujet == sujet && it.niveau == niveau })
        a("charge", 1); a("memoire", 2); a("swap", 1); a("oom", 2); a("disque", 2); a("demarrage", 1); a("echec", 2)
        a("temperature", 2); a("maj", 1); a("journaux", 1); a("conteneur", 2); a("docker_place", 1); a("ssh", 2); a("ssh_securite", 1); a("ssh_cles", 1)
        assertTrue(c.first { it.sujet == "oom" }.texte.contains("python3"))
        assertTrue(c.first { it.sujet == "conteneur" && it.niveau == 2 }.texte.contains("plex") )
        assertTrue(c.any { it.sujet == "conteneur" && it.texte.contains("homeassistant") && it.texte.contains("7 fois") })
        assertTrue(c.first { it.sujet == "ssh" }.texte.contains("2222"))
        assertFalse(c.any { it.sujet == "smart" })
        val r = ServeurDiagnostic.rapport(c)
        assertTrue(r, r.startsWith("## 🩺 Bilan : ") && r.contains("🔴") && r.contains("## 🛠️ Dépannage proposé"))
    }

    @Test fun toutVaBien() {
        val ok = "@CHARGE 0.2 0.3 0.2 4\n@MEM 3900 1200 2500\n@SWAP 2047 10\n@DISQUE 41 / 9000000\n@DEMARRAGE 2026-11-01 08:00:00\n@OOM 0\n@SSH_SERVICE active\n@SSH_TEST ok\n@SSH_PORT_CONF 22\n@ECOUTE 22 8123\n@DOCKER active\n@CONTENEUR /homeassistant|running|0|false|0|healthy|x"
        val c = ServeurDiagnostic.analyser(ok, java.time.LocalDateTime.of(2026, 11, 24, 14, 0))
        assertTrue(c.toString(), c.all { it.niveau == 0 })
        assertTrue(ServeurDiagnostic.rapport(c).startsWith("## 🩺 Bilan : tout va bien"))
    }

    @Test fun valeursVidesNeSontPasDesPannes() {
        // Relevé réel sans systemd ni sudo : docker / ssh / heure inconnus — aucun faux problème.
        val c = ServeurDiagnostic.analyser("@DROITS ok\n@NTP \n@DOCKER \n@DOCKER_RECUP \n@SSH_SERVICE \n@ECOUTE \n@SSH_ECHECS_24H 0\n@SSH_PERMS 700 700  \n@FAIL2BAN absent\n@UFW ")
        assertTrue(c.toString(), c.none { it.niveau > 0 })
    }

    @Test fun scriptPropre() {
        val root = ServeurDiagnostic.commande("complet", "", 80, sudo = true, toutEnRoot = true)
        assertTrue(root.startsWith("sudo true\n"))
        assertFalse(root.contains("sudo -n")); assertFalse(root.contains("¤")); assertFalse(root.contains("SUDO "))
        val sansMdp = ServeurDiagnostic.commande("ssh", "", 80, sudo = true)
        assertTrue(sansMdp.contains("sudo -n journalctl")); assertTrue(sansMdp.contains("@SSH_PORT_CONF"))
        // Le bot Discord tourne sous l'utilisateur : jamais tout en root.
        assertFalse(ServeurDiagnostic.commande("discord", "", 80, sudo = true, toutEnRoot = true).startsWith("sudo true"))
        assertTrue(ServeurDiagnostic.commandeRemede(ServeurDiagnostic.Remede.NETTOYER_DISQUE).contains("journalctl --vacuum-size=200M"))
        assertFalse(ServeurDiagnostic.commandeRemede(ServeurDiagnostic.Remede.NETTOYER_DISQUE).contains("volume"))
    }

    @Test fun demandesComprises() {
        mapOf("diagnostique le serveur" to "complet", "fais un bilan de santé du serveur" to "complet", "lance un diagnostic du serveur" to "complet",
            "le serveur rame en ce moment" to "complet", "pourquoi je n'arrive pas à me connecter en SSH" to "ssh", "le ssh ne marche plus" to "ssh",
            "comment va le serveur ?" to "complet", "vérifie le serveur Plex" to "plex", "le bot discord sur le serveur ne répond plus" to "discord")
            .forEach { (t, c) -> assertEquals(t, c, ServeurDiagnostic.demande(t)) }
        listOf("redémarre le serveur", "envoie le fichier sur le serveur", "allume la lumière du salon", "quelle heure est-il").forEach { assertNull(it, ServeurDiagnostic.demande(it)) }
        assertEquals(ServeurDiagnostic.Remede.NETTOYER_DISQUE, ServeurDiagnostic.demandeRemede("nettoie le disque du serveur"))
        assertEquals(ServeurDiagnostic.Remede.NETTOYER_DISQUE, ServeurDiagnostic.demandeRemede("fais de la place sur le serveur"))
        assertEquals(ServeurDiagnostic.Remede.RELANCER_ECHECS, ServeurDiagnostic.demandeRemede("relance les services en échec du serveur"))
        assertNull(ServeurDiagnostic.demandeRemede("nettoie la cuisine"))
    }

    @Test fun nettoyageRendu() {
        assertEquals("🧹 Disque nettoyé : 850 Mo libérés, 3,0 Go libres sur /.", ServeurDiagnostic.resultatRemede(ServeurDiagnostic.Remede.NETTOYER_DISQUE, "Vacuuming done\n@LIBERE 850 3072"))
    }
    @Test fun sortieSansReleveNePeutPasDireQueToutVaBien() {
        val retour = ServeurDiagnostic.rapportExecution("permission denied", 1)
        assertTrue(retour.contains("Diagnostic incomplet"))
        assertFalse(retour.contains("tout va bien"))
    }

    @Test fun codeSshNonNulSignaleUnBilanPartiel() {
        val retour = ServeurDiagnostic.rapportExecution("== Hôte Debian ==\\n@CHARGE 0.2 0.3 0.2 4", 1)
        assertTrue(retour.contains("Diagnostic partiel"))
        assertTrue(retour.contains("code 1"))
    }

}

/** Les scripts envoyés au serveur sont du shell valide (bash et sh). */
class ServeurDiagnosticSyntaxeRootEtShTest {
    private fun valide(shell: String, script: String): String? {
        if (!java.io.File("/bin/$shell").exists()) return null
        val f = java.io.File.createTempFile("diag", ".sh").apply { writeText(script); deleteOnExit() }
        val p = ProcessBuilder("/bin/$shell", "-n", f.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return if (p.waitFor() == 0) null else out
    }

    @Test fun syntaxe() {
        val scripts = listOf("complet", "debian", "ssh", "plex", "home assistant", "discord").flatMap { c ->
            listOf(ServeurDiagnostic.commande(c, "jeton", 80, sudo = true), ServeurDiagnostic.commande(c, "", 80, sudo = true, toutEnRoot = true),
                ServeurDiagnostic.commande(c, "", 80, sudo = false))
        } + ServeurDiagnostic.Remede.values().map { ServeurDiagnostic.commandeRemede(it) }
        scripts.forEach { s -> listOf("bash", "sh").forEach { sh -> valide(sh, s)?.let { throw AssertionError("$sh : $it\n${s.take(400)}") } } }
    }
}
