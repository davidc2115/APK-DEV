package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestitutionTest {
    @Test fun consignesExplicitesDetectees() {
        assertEquals("musique", ConsignesExplicites.detecter("Quand tu mets une musique, donne-moi seulement le titre et l'artiste")?.rubrique)
        assertEquals("musique", ConsignesExplicites.detecter("Quand je lui demande de lancer une musique, donne-moi seulement le titre et l'artiste")?.rubrique)
        assertEquals("localisation", ConsignesExplicites.detecter("Lorsque je lui demande une localisation, donne-moi uniquement l'adresse")?.rubrique)
        assertEquals("localisation", ConsignesExplicites.detecter("Pour la localisation d'une personne, donne-moi seulement l'adresse exacte, pas d'autres informations")?.rubrique)
        assertEquals("reponses", ConsignesExplicites.detecter("À l'avenir, réponds-moi uniquement en deux phrases")?.rubrique)
        assertNull(ConsignesExplicites.detecter("mets du Johnny Hallyday s'il te plaît"))
        assertNull(ConsignesExplicites.detecter("donne-moi seulement l'adresse de Paul"))
        assertNull(ConsignesExplicites.detecter("est-ce que tu mets toujours la musique trop fort ?"))
    }

    @Test fun consignesDeFormeQuiConcernentLEchange() {
        val memoire = mapOf(
            "musique" to "- Toujours passer par YT Music Morphe\n- Donne seulement le titre et l'artiste du morceau lancé",
            "localisation" to "- Pour la position d'une personne, seulement l'adresse exacte",
            "contacts" to "- Fiches au format Obsidian")
        val musique = Restitution.consignes(memoire, "mets Johnny Hallyday", "▶️ « Johnny » est en lecture dans ta bibliothèque en aléatoire depuis ta bibliothèque.")
        assertEquals(1, musique.size)
        assertTrue(musique.first().contains("titre et l'artiste"))
        val position = Restitution.consignes(memoire, "où est Angeline", "📍 Angeline — zone maison\n- GPS 47.1, 5.2\n- précision 12 m")
        assertTrue(position.single().contains("adresse exacte"))
        assertTrue(Restitution.consignes(memoire, "quel temps fait-il", "☀️ 18° à Vesoul, vent faible, ciel dégagé toute la journée.").isEmpty())
    }

    @Test fun consigneAjouteeSansEffacerLesAutres() {
        assertTrue(fr.dd.jarvis.device.Memo.proximite("Donne seulement le titre et l'artiste", "Donne-moi seulement le titre et l'artiste du morceau") >= 0.6)
        assertTrue(fr.dd.jarvis.device.Memo.proximite("Toujours passer par YT Music Morphe", "Donne seulement le titre et l'artiste") < 0.6)
    }

    @Test fun consignesDeTousLesDomainesDetectees() {
        val poeles = ConsignesExplicites.detecter("Donne-moi la température de mes poêles sous forme de tableau avec la température actuelle et la température de consigne")!!
        assertEquals("maison", poeles.rubrique)
        assertTrue(poeles.ponctuelle)
        assertTrue(poeles.texte, poeles.texte.contains("sous forme de tableau"))
        val durable = ConsignesExplicites.detecter("À l'avenir, quand je te demande la température des poêles, présente-la en tableau avec la température actuelle et la consigne")!!
        assertEquals("maison", durable.rubrique)
        assertTrue(!durable.ponctuelle)
        assertEquals("serveur", ConsignesExplicites.detecter("Quand je te demande l'état du serveur, donne-moi seulement le CPU, la RAM et les disques")?.rubrique)
        assertEquals("plex", ConsignesExplicites.detecter("Pour Plex, présente toujours les films avec l'année et la durée")?.rubrique)
        assertEquals("telephone", ConsignesExplicites.detecter("Je veux que tu me donnes toujours la batterie en pourcentage seulement")?.rubrique)
        assertEquals("agenda", ConsignesExplicites.detecter("Désormais, présente mon planning sous forme de tableau avec l'heure et le client")?.rubrique)
        assertNull(ConsignesExplicites.detecter("Il fait toujours froid dans le salon le matin"))
        assertNull(ConsignesExplicites.detecter("Crée un tableau Excel des factures de mars"))
        assertNull(ConsignesExplicites.detecter("Retiens que mon fils s'appelle Léo"))
        assertNull(ConsignesExplicites.detecter("quelle est la température de mes poêles ?"))
    }

    @Test fun consignesDUnDomaineRetrouveesPourLaDemande() {
        val memoire = mapOf(
            "maison" to "- Présentation voulue : « Donne-moi la température de mes poêles sous forme de tableau avec la température actuelle et la température de consigne »",
            "plex" to "- Pour Plex, présente toujours les films avec l'année et la durée",
            "serveur" to "- Quand je te demande l'état du serveur, donne-moi seulement le CPU, la RAM et les disques")
        val poele = Restitution.concernees(memoire, "c'est quoi la température des poêles", "", formeSeulement = false, max = 10)
        assertEquals(1, poele.size)
        assertTrue(poele.single().startsWith("[maison]"))
        assertTrue(Restitution.concernees(memoire, "allume la lumière du salon", "", false, 10).isEmpty())
        assertTrue(Restitution.concernees(memoire, "mets Johnny Hallyday", "", false, 10).isEmpty())
        assertTrue(Restitution.concernees(memoire, "quels films récents sur Plex ?", "", false, 10).single().startsWith("[plex]"))
        assertTrue(Restitution.concernees(memoire, "comment va le serveur ?", "", false, 10).single().startsWith("[serveur]"))
        assertEquals("maison", DomainesConsignes.deRubrique("Poêles"))
        assertEquals("maison", DomainesConsignes.deRubrique("home assistant"))
        assertEquals("plex", DomainesConsignes.deRubrique("films"))
    }

    @Test fun memoireLongueGardeLesConsignesDesDomaines() {
        val items = linkedMapOf(
            "maison" to "- Toujours un tableau pour les poêles : température actuelle et consigne",
            "divers" to "- Je préfère que tu me tutoies\n- La voiture est une Clio grise",
            "faits" to "- Le garage ferme à 19 h",
        )
        (1..12).forEach { items["projet$it"] = "- " + "information sans importance particulière numéro $it ".repeat(4) }
        val bloc = fr.dd.jarvis.device.Memo.blocPrompt(items)
        assertTrue(bloc.contains("[maison] - Toujours un tableau pour les poêles"))
        assertTrue(bloc.contains("[divers] Je préfère que tu me tutoies"))
        assertTrue(!bloc.contains("Clio grise"))
        assertTrue(bloc.contains("CHAQUE réponse"))
        val demande = fr.dd.jarvis.device.Memo.blocDemande(items, "la température de mes poêles")
        assertTrue(demande, demande.contains("POUR CETTE DEMANDE") && demande.contains("température actuelle et consigne"))
        assertEquals("", fr.dd.jarvis.device.Memo.blocDemande(items, "quelle heure est-il"))
    }

    @Test fun gestionDesConsignes() {
        assertEquals(GestionConsignes.Action.Lister(null), GestionConsignes.demande("Quelles sont mes consignes ?"))
        assertEquals(GestionConsignes.Action.Lister("maison"), GestionConsignes.demande("mes consignes pour la maison"))
        assertEquals(GestionConsignes.Action.Lister(null), GestionConsignes.demande("montre-moi les consignes enregistrées"))
        assertEquals(GestionConsignes.Action.Oublier(null), GestionConsignes.demande("oublie cette consigne"))
        assertEquals(GestionConsignes.Action.Oublier("poeles"), GestionConsignes.demande("Oublie la consigne sur les poêles"))
        assertNull(GestionConsignes.demande("oublie ça"))
        assertNull(GestionConsignes.demande("mets la musique"))
        val liste = listOf("musique" to "Donne seulement le titre et l'artiste", "maison" to "Présentation voulue : « température des poêles en tableau »")
        assertEquals("maison", GestionConsignes.choisir(liste, "poeles")?.first)
        assertEquals("musique", GestionConsignes.choisir(liste, "la musique")?.first)
    }

    @Test fun lesConsignesNeSePerdentPlusAuDemarrage() {
        val ancienne = "---\ntype: memoire-jarvis\n---\n\n## musique\n- Donne seulement le titre et l'artiste\n"
        val recente = ancienne + "\n## maison\n- Toujours un tableau pour les poêles\n"
        // Note Obsidian restée ancienne (écriture échouée / pas encore synchronisée) : la copie locale plus récente gagne.
        assertEquals(recente to true, fr.dd.jarvis.device.Memo.choisirSource(ancienne to 1_000L, recente to 60_000L))
        // Note modifiée à la main APRÈS la copie locale : elle fait foi (une consigne retirée reste retirée).
        assertEquals(ancienne to false, fr.dd.jarvis.device.Memo.choisirSource(ancienne to 90_000L, recente to 60_000L))
        assertEquals(recente to false, fr.dd.jarvis.device.Memo.choisirSource(null, recente to 60_000L))
        assertEquals(ancienne to false, fr.dd.jarvis.device.Memo.choisirSource(ancienne to 1_000L, null))
    }

    @Test fun consigneDAjoutEnvoieAuCerveau() {
        val memoire = mapOf("maison" to "- Quand je demande la température des poêles, donne aussi la température extérieure",
            "musique" to "- Donne seulement le titre et l'artiste, n'ajoute rien")
        assertTrue(Restitution.exigeUneSuite(memoire, "quelle est la température de mes poêles"))
        assertTrue(!Restitution.exigeUneSuite(memoire, "mets Johnny Hallyday"))
        assertTrue(!Restitution.exigeUneSuite(memoire, "allume la lumière du salon"))
    }

    @Test fun consignesParCanal() {
        val v = ConsignesExplicites.detecter("Quand je te parle à l'oral, réponds-moi toujours en une phrase courte")!!
        assertEquals(CanalDemande.VOCAL, v.canal)
        assertTrue(v.texte, v.texte.startsWith("(vocal) "))
        val e = ConsignesExplicites.detecter("Par écrit, présente toujours les températures des poêles en tableau")!!
        assertEquals(CanalDemande.ECRIT, e.canal)
        assertEquals("maison", e.rubrique)
        assertNull(ConsignesExplicites.detecter("À l'avenir, présente les poêles en tableau")!!.canal)
        val items = mapOf("maison" to "- (écrit) Présente toujours les poêles en tableau avec la consigne\n- (vocal) Pour les poêles, dis seulement la température du salon en une phrase")
        val ecrit = Restitution.concernees(items, "température des poêles", "", false, 10, vocal = false)
        val oral = Restitution.concernees(items, "température des poêles", "", false, 10, vocal = true)
        assertTrue(ecrit.single().contains("tableau"))
        assertTrue(oral.single().contains("une phrase"))
        assertTrue(fr.dd.jarvis.device.Memo.blocPrompt(items, vocal = true).let { !it.contains("en tableau avec la consigne") && it.contains("une phrase") })
        assertTrue(CanalDemande.ligne(true).contains("VOCAL") && CanalDemande.ligne(false).contains("ÉCRIT"))
        assertEquals("(vocal) Réponds court", ConsignesIA.lire("{\"consigne\":true,\"domaine\":\"reponses\",\"canal\":\"vocal\",\"regle\":\"Réponds court à l'oral\"}")?.texte?.substringBefore(" à"))
    }
}
