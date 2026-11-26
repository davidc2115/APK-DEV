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
    fun listeHabitudesEnApprentissageEstLueDepuisLeRegistreUnique() {
        val dir = Files.createTempDirectory("registre-habitudes-en-cours").toFile()
        try {
            val r = registre(dir)
            val id = r.enregistrerPreuveHabitude(
                "Réponses concises", "Préférence", "Memo.apprendre",
                "Je préfère les réponses courtes", "conversation 1", "memo.apprendre"
            )
            val premier = r.habitudeApprentissage(id)
            assertEquals(RegistreApprentissage.ETAT_OBSERVED, premier?.etat)
            assertEquals(1, premier?.preuves)
            assertEquals(1, r.habitudesEnApprentissage().size)

            r.enregistrerPreuveHabitude("Réponses concises", "Préférence", "Memo.apprendre",
                "Va droit au but dans les explications", "conversation 2", "memo.apprendre")
            r.enregistrerPreuveHabitude("Réponses concises", "Préférence", "Memo.apprendre",
                "Privilégie les formulations synthétiques", "conversation 3", "memo.apprendre")
            val candidate = r.habitudeApprentissage(id)
            assertEquals(RegistreApprentissage.ETAT_CANDIDATE, candidate?.etat)
            assertEquals(3, candidate?.preuves)

            r.changerEtat(id, RegistreApprentissage.ETAT_APPROVED, true)
            r.changerEtat(id, RegistreApprentissage.ETAT_TESTING, true)
            r.changerEtat(id, RegistreApprentissage.ETAT_ACTIVE, true)
            assertTrue(r.habitudesEnApprentissage().isEmpty())
            assertEquals(RegistreApprentissage.ETAT_ACTIVE, r.habitudeApprentissage(id)?.etat)
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
    fun lectureDuCoffreEnEchecNeRemplacePasLeRegistreParLeCacheLocal() {
        val dir = Files.createTempDirectory("registre-lecture-indisponible").toFile()
        try {
            val local = File(dir, "index.json")
            val ancien = """{"schemaVersion":1,"updatedAt":1,"entries":[],"history":[]}"""
            local.writeText(ancien)
            val r = RegistreApprentissage(
                local,
                { true },
                { throw java.io.IOException("coffre temporairement inaccessible") },
                { throw AssertionError("aucune écriture ne doit être tentée après un échec de lecture") }
            )
            assertThrows(java.io.IOException::class.java) { r.snapshot() }
            assertEquals(ancien, local.readText())
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
    fun seulesLesHabitudesExplicitementActivesSontExposeesAuxPrompts() {
        val dir = Files.createTempDirectory("registre-habitudes-actives").toFile()
        try {
            val r = registre(dir)
            val candidate = r.creer("habit", "Préférence candidate", "Ne doit pas être appliquée", "conversation")
            r.changerEtat(candidate, RegistreApprentissage.ETAT_CANDIDATE, false)
            val approved = r.creer("habit", "Préférence approuvée", "Pas encore active", "conversation")
            r.changerEtat(approved, RegistreApprentissage.ETAT_CANDIDATE, false)
            r.changerEtat(approved, RegistreApprentissage.ETAT_APPROVED, true)
            val active = r.creer("habit", "Préférence active", "À appliquer dans les réponses", "conversation")
            r.changerEtat(active, RegistreApprentissage.ETAT_CANDIDATE, false)
            r.changerEtat(active, RegistreApprentissage.ETAT_APPROVED, true)
            r.changerEtat(active, RegistreApprentissage.ETAT_TESTING, true)
            r.changerEtat(active, RegistreApprentissage.ETAT_ACTIVE, true)

            assertEquals(listOf("Préférence active" to "À appliquer dans les réponses"), r.habitudesActives())
            r.changerEtat(active, RegistreApprentissage.ETAT_PAUSED, false)
            assertTrue(r.habitudesActives().isEmpty())
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

}
