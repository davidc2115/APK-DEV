package fr.dd.jarvis.device

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RegistreApprentissageTest {
    private fun registre(dossier: File, coffre: Boolean = false, distant: MutableList<String?> = mutableListOf(null)): RegistreApprentissage {
        return RegistreApprentissage(
            File(dossier, "index.json"),
            { coffre },
            { distant.last() },
            { raw -> distant.add(raw) }
        )
    }

    @Test
    fun preuveDupliqueeNeComptePasDeuxFois() {
        val dir = Files.createTempDirectory("registre-apprentissage").toFile()
        try {
            val r = registre(dir)
            val id = r.creer("habit", "Préférence de présentation", "Préférence observée", "conversation")
            assertTrue(r.ajouterPreuve(id, "Toujours présenter les températures", "demande maison", "conversation"))
            assertFalse(r.ajouterPreuve(id, "Toujours   présenter les températures", "demande maison", "conversation"))
            assertTrue(r.ajouterPreuve(id, "Toujours présenter les températures", "demande cuisine", "conversation"))
            val item = JSONObject(r.snapshot()).getJSONArray("entries").getJSONObject(0)
            assertEquals(2, item.getJSONArray("evidence").length())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun activationNecessiteValidationEtTransitionsAutorisees() {
        val dir = Files.createTempDirectory("registre-etats").toFile()
        try {
            val r = registre(dir)
            val id = r.creer("skill", "Diagnostic réseau", "Proposition de skill", "tâche répétée")
            r.changerEtat(id, RegistreApprentissage.ETAT_CANDIDATE, false)
            r.changerEtat(id, RegistreApprentissage.ETAT_NEEDS_REVIEW, false)
            assertThrows(SecurityException::class.java) {
                r.changerEtat(id, RegistreApprentissage.ETAT_APPROVED, false)
            }
            r.changerEtat(id, RegistreApprentissage.ETAT_APPROVED, true)
            r.changerEtat(id, RegistreApprentissage.ETAT_TESTING, true)
            r.changerEtat(id, RegistreApprentissage.ETAT_ACTIVE, true)
            assertEquals(RegistreApprentissage.ETAT_ACTIVE,
                JSONObject(r.snapshot()).getJSONArray("entries").getJSONObject(0).getString("state"))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun transitionDirecteVersActiveEstRefusee() {
        val dir = Files.createTempDirectory("registre-transition").toFile()
        try {
            val r = registre(dir)
            val id = r.creer("tool", "Connecteur calendrier", "Outil proposé", "demande utilisateur")
            assertThrows(IllegalStateException::class.java) {
                r.changerEtat(id, RegistreApprentissage.ETAT_ACTIVE, true)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun echecDuMiroirEstRetenteAuProchainAcces() {
        val dir = Files.createTempDirectory("registre-retry").toFile()
        try {
            var distant: String? = null
            var indisponible = false
            val r = RegistreApprentissage(
                File(dir, "index.json"),
                { true },
                { distant },
                { raw ->
                    if (indisponible) throw java.io.IOException("coffre indisponible")
                    distant = raw
                }
            )
            val id = r.creer("habit", "Préférence vocale", "Réponse vocale concise", "conversation")
            indisponible = true
            assertThrows(java.io.IOException::class.java) {
                r.ajouterPreuve(id, "Réponse vocale courte et directe", "demande vocale", "conversation")
            }
            indisponible = false
            val snapshot = JSONObject(r.snapshot())
            val item = snapshot.getJSONArray("entries").getJSONObject(0)
            assertEquals(1, item.getJSONArray("evidence").length())
            assertNotNull(distant)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun registreEstMiroireDansLeCoffreEtRecharge() {
        val dir = Files.createTempDirectory("registre-coffre").toFile()
        try {
            val distant = mutableListOf<String?>(null)
            val r = registre(dir, coffre = true, distant = distant)
            r.creer("error", "Timeout Home Assistant", "Timeout récurrent", "journal technique")
            assertNotNull(distant.last())
            val r2 = RegistreApprentissage(File(dir, "autre-cache.json"), { true }, { distant.last() }, { distant.add(it) })
            assertEquals(1, JSONObject(r2.snapshot()).getJSONArray("entries").length())
        } finally { dir.deleteRecursively() }
    }
    @Test
    fun registreRefuseSecretsEtDonneesSensiblesAvantToutePersistance() {
        val dir = Files.createTempDirectory("registre-confidentialite").toFile()
        try {
            val r = registre(dir)
            assertThrows(SecurityException::class.java) {
                r.creer("tool", "Connecteur privé", "Utilise cette API key pour se connecter", "conversation")
            }
            val id = r.creer("habit", "Préférence de réponse", "Réponse concise", "conversation")
            assertThrows(SecurityException::class.java) {
                r.ajouterPreuve(id, "Mon mot de passe est azerty123", "configuration", "conversation")
            }
            assertThrows(SecurityException::class.java) {
                r.ajouterContreExemple(id, "Mon IBAN personnel est FR761234567890", "conversation")
            }
            val snapshot = JSONObject(r.snapshot()).getJSONArray("entries")
            assertEquals(1, snapshot.length())
            assertEquals(0, snapshot.getJSONObject(0).getJSONArray("evidence").length())
            assertEquals(0, snapshot.getJSONObject(0).getJSONArray("counterexamples").length())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun habitudeDevientCandidateApresTroisPreuvesDistinctes() {
        val dir = Files.createTempDirectory("registre-habitude-candidate").toFile()
        try {
            val r = registre(dir)
            val id = r.enregistrerPreuveHabitude(
                "Réponses concises", "Préférence de présentation", "Memo.apprendre",
                "Je préfère les réponses courtes", "conversation 1", "memo.apprendre"
            )
            assertEquals(RegistreApprentissage.ETAT_OBSERVED,
                JSONObject(r.snapshot()).getJSONArray("entries").getJSONObject(0).getString("state"))
            r.enregistrerPreuveHabitude("Réponses concises", "Préférence de présentation", "Memo.apprendre",
                "Va droit au but dans les explications", "conversation 2", "memo.apprendre")
            r.enregistrerPreuveHabitude("Réponses concises", "Préférence de présentation", "Memo.apprendre",
                "Privilégie les formulations synthétiques", "conversation 3", "memo.apprendre")
            val item = JSONObject(r.snapshot()).getJSONArray("entries").getJSONObject(0)
            assertEquals(id, item.getString("id"))
            assertEquals(RegistreApprentissage.ETAT_CANDIDATE, item.getString("state"))
            assertEquals(3, item.getJSONArray("evidence").length())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun memePreuveDansDesContextesDifferentsNeFaitPasProgresserUneHabitude() {
        val dir = Files.createTempDirectory("registre-habitude-doublons").toFile()
        try {
            val r = registre(dir)
            val id = r.enregistrerPreuveHabitude("Réponses concises", "Préférence", "Memo.apprendre",
                "Je préfère les réponses courtes", "conversation 1", "memo.apprendre")
            r.enregistrerPreuveHabitude("Réponses concises", "Préférence", "Memo.apprendre",
                "Je préfère les réponses courtes", "conversation 2", "memo.apprendre")
            r.enregistrerPreuveHabitude("Réponses concises", "Préférence", "Memo.apprendre",
                "Je préfère les réponses courtes", "conversation 3", "memo.apprendre")
            val item = JSONObject(r.snapshot()).getJSONArray("entries").getJSONObject(0)
            assertEquals(id, item.getString("id"))
            assertEquals(RegistreApprentissage.ETAT_OBSERVED, item.getString("state"))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun habitudesDeRubriquesDifferentesRestentIndependantes() {
        val dir = Files.createTempDirectory("registre-habitudes-rubriques").toFile()
        try {
            val r = registre(dir)
            val idTexte = r.enregistrerPreuveHabitude("[texte] Réponses concises", "Préférence de texte", "Memo.apprendre",
                "Je préfère les réponses courtes", "texte", "memo.apprendre")
            val idVoix = r.enregistrerPreuveHabitude("[voix] Réponses concises", "Préférence vocale", "Memo.apprendre",
                "Je préfère les réponses courtes", "voix", "memo.apprendre")
            assertNotEquals(idTexte, idVoix)
            val entries = JSONObject(r.snapshot()).getJSONArray("entries")
            assertEquals(2, entries.length())
            assertEquals(RegistreApprentissage.ETAT_OBSERVED, entries.getJSONObject(0).getString("state"))
            assertEquals(RegistreApprentissage.ETAT_OBSERVED, entries.getJSONObject(1).getString("state"))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun permissionsSkillSontDesDemandesEtNeSontJamaisAccordeesParLeRegistre() {
        val dir = Files.createTempDirectory("registre-permissions").toFile()
        try {
            val r = registre(dir)
            val id = r.creer("skill", "Briefing agenda", "Prépare un briefing", "usage répété")
            r.demanderPermissions(id, listOf("calendar:read", "memory:read", "calendar:read"))
            val item = JSONObject(r.snapshot()).getJSONArray("entries").getJSONObject(0)
            assertEquals(2, item.getJSONArray("requestedPermissions").length())
            assertEquals(0, item.getJSONArray("permissions").length())
            assertEquals(RegistreApprentissage.ETAT_OBSERVED, item.getString("state"))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun permissionsTropLargesOuInconnuesSontRefuseesSansEcriture() {
        val dir = Files.createTempDirectory("registre-permissions-refus").toFile()
        try {
            val r = registre(dir)
            val id = r.creer("tool", "Outil agenda", "Recherche agenda", "usage répété")
            assertThrows(SecurityException::class.java) { r.demanderPermissions(id, listOf("*")) }
            assertThrows(SecurityException::class.java) { r.demanderPermissions(id, listOf("shell:execute")) }
            assertThrows(IllegalArgumentException::class.java) { r.demanderPermissions(id, emptyList()) }
            val item = JSONObject(r.snapshot()).getJSONArray("entries").getJSONObject(0)
            assertFalse(item.has("requestedPermissions"))
            assertEquals(0, item.getJSONArray("permissions").length())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun permissionsNePeuventPasEtreDemandeesPourUneHabitude() {
        val dir = Files.createTempDirectory("registre-permissions-habitude").toFile()
        try {
            val r = registre(dir)
            val id = r.creer("habit", "Préférence de réponse", "Réponse concise", "conversation")
            assertThrows(IllegalArgumentException::class.java) {
                r.demanderPermissions(id, listOf("memory:read"))
            }
        } finally { dir.deleteRecursively() }
    }


    @Test
    fun permissionsSontRefuseesPourUnSkillRejeteOuRemplace() {
        val dir = Files.createTempDirectory("registre-permissions-etat-terminal").toFile()
        try {
            val r = registre(dir)
            val refuse = r.creer("skill", "Skill refusé", "Proposition refusée", "revue")
            r.changerEtat(refuse, RegistreApprentissage.ETAT_REJECTED, false)
            assertThrows(IllegalStateException::class.java) {
                r.demanderPermissions(refuse, listOf("calendar:read"))
            }

            val remplace = r.creer("tool", "Outil remplacé", "Ancienne proposition", "revue")
            r.changerEtat(remplace, RegistreApprentissage.ETAT_SUPERSEDED, false)
            assertThrows(IllegalStateException::class.java) {
                r.demanderPermissions(remplace, listOf("files:read"))
            }
            val entries = JSONObject(r.snapshot()).getJSONArray("entries")
            assertFalse(entries.getJSONObject(0).has("requestedPermissions"))
            assertFalse(entries.getJSONObject(1).has("requestedPermissions"))
        } finally { dir.deleteRecursively() }
    }
}
