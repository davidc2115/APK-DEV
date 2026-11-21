package fr.dd.jarvis.audit

import fr.dd.jarvis.ai.Brain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit des fonctions : chaque phrase type doit être prise par le BON réflexe (ou laissée à l'IA), et un résultat
 * « direct » ne doit jamais couper une demande qui attend une action ou une seconde action.
 */
class AuditFonctionnelTest {
    private fun r(p: String) = RoutageAudit.route(p).substringBefore(" [")

    @Test fun lesReflexesPrennentLesBonnesPhrases() {
        val attendus = mapOf(
            "montre-moi mon planning de demain" to "CD:get_planning",
            "lance la navigation vers mon prochain rendez-vous" to "NavRdv",
            "emmène-moi à la gare de Dijon" to "CD:naviguer",
            "ramène-moi à la maison" to "CD:naviguer",
            "réveille-moi à 7h" to "CD:alarme_creer",
            "mets un minuteur de 10 minutes" to "CD:minuteur_creer",
            "quel temps fera-t-il demain à Dijon" to "CD:meteo",
            "appelle maman" to "CD:appeler_contact",
            "lis mes SMS" to "CD:lire_sms",
            "trie mes mails" to "Mail:trier_mails",
            "pause" to "Media:PAUSE",
            "chanson suivante" to "Media:NEXT",
            "allume la lampe torche" to "Immediat:phone_action/torch_on",
            "Lumos" to "Immediat:phone_action/torch_on",
            "Nox" to "Immediat:phone_action/torch_off",
            "mets le son à 50" to "Immediat:phone_action/volume",
            "mets le téléphone en silencieux" to "ModeSonore:npd",
            "mets le téléphone en vibreur" to "ModeSonore:vibreur",
            "active le mode nuit" to "ModeJarvis:Mode nuit/true",
            "où est Marie" to "PersonneOu:marie",
            "mets France 2" to "MaisonTele",
            "rallume mon poêle à l'étage" to "PoeleAction",
            "quelle est la température de mes poêles" to "PoeleLecture",
            "allume la lumière du salon" to "Domo",
            "ouvre WhatsApp" to "CD:phone_action",
            "lance Inception sur la télé" to "Plex:plex_lancer",
            "Donne-moi la température de mes poêles sous forme de tableau avec la température actuelle et la température de consigne" to "PoeleLecture",
            "quelles sont mes consignes" to "Consignes:Lister",
            "oublie cette consigne" to "Consignes:Oublier",
            "combien font 15 % de 240" to "Calcul",
            "convertis 30 miles en km" to "Calcul",
            "combien de jours avant Noël" to "Calcul",
            "ajoute du lait et des œufs à la liste de courses" to "Liste:Ajouter",
            "qu'est-ce qu'il y a sur ma liste" to "Liste:Lire",
            "où est ma voiture" to "Parking:OuEst",
            "je suis garé ici" to "Parking:Noter",
            "annule" to "Annuler",
            "ajoute une note de frais de 45 € chez Total pour du carburant" to "Frais:Ajouter",
            "mes notes de frais à traiter" to "Frais:Lister",
            "rappelle-moi d'acheter du pain quand j'arrive à la maison" to "RappelLieu",
            "où en sont mes colis" to "Courrier:Colis",
            "fais le point du soir" to "CD:morning_briefing",
        )
        val erreurs = attendus.filter { (p, a) -> r(p) != a }.map { (p, a) -> "« $p » → ${r(p)} (attendu $a)" }
        assertTrue(erreurs.joinToString("\n"), erreurs.isEmpty())
        // Une commande ordinaire n'est jamais prise pour une consigne durable (ni pour sa gestion).
        val consignes = attendus.keys.filter { fr.dd.jarvis.ai.ConsignesExplicites.detecter(it)?.ponctuelle == false }
        assertTrue(consignes.joinToString("\n"), consignes.isEmpty())
    }

    @Test fun aucunReflexeNeDetourneCesPhrases() {
        // Chacune doit aller à l'IA (ou à un réflexe de la maison), jamais au mauvais réflexe.
        val interdits = mapOf(
            "combien de temps pour aller à mon prochain rendez-vous" to listOf("NavRdv"),
            "mets les coordonnées GPS dans mon rendez-vous de demain" to listOf("NavRdv"),
            "remplace l'adresse de mon rendez-vous par le point GPS" to listOf("NavRdv"),
            "qu'est-ce qui passe sur Plex en ce moment" to listOf("Plex:plex_lancer"),
            "éteins tout et active le mode nuit" to listOf("ModeJarvis"),
            "il fait combien dans la maison" to listOf("CD:meteo"),
            "ouvre le portail" to listOf("CD:phone_action"),
            "lance la machine à laver" to listOf("CD:phone_action"),
            "lance l'aspirateur" to listOf("CD:phone_action"),
            "ouvre le volet de la chambre" to listOf("CD:phone_action"),
            "mets le poêle en mode silence" to listOf("ModeSonore", "ModeJarvis"),
            "passe le poêle en mode nuit" to listOf("ModeSonore", "ModeJarvis"),
            "mets le son de la télé à 20" to listOf("Immediat", "ModeSonore"),
        )
        val erreurs = interdits.filter { (p, l) -> l.any { r(p).startsWith(it) } }.map { (p, _) -> "« $p » → ${r(p)}" }
        assertTrue(erreurs.joinToString("\n"), erreurs.isEmpty())
    }

    @Test fun unResultatDirectNeCoupePlusUneDemandeQuiAttendUneAction() {
        // La lecture ne suffit pas : le modèle doit continuer.
        assertTrue(Brain.suiteAttendue("lis mes SMS et réponds à Paul que j'arrive", "lire_sms"))
        assertTrue(Brain.suiteAttendue("lance la navigation vers mon rendez-vous", "get_planning"))
        assertTrue(Brain.suiteAttendue("appelle le client de mon prochain rendez-vous", "get_planning"))
        assertTrue(Brain.suiteAttendue("supprime le réveil de 7h", "alarmes_liste"))
        assertTrue(Brain.suiteAttendue("cherche le mail de la banque et transfère-le à Marie", "rechercher_mails"))
        assertTrue(Brain.suiteAttendue("envoie-moi par SMS mon planning de demain", "get_planning"))
        assertTrue(Brain.suiteAttendue("ajoute un rendez-vous demain à 14h chez Dupont", "get_planning"))
        // Deux actions : la première ne termine pas la demande.
        assertTrue(Brain.suiteAttendue("mets un réveil à 7h et lance la musique", "alarme_creer"))
        assertTrue(Brain.suiteAttendue("lis mes mails et dis-moi s'il y a quelque chose d'urgent", "summarize_mails"))
        // La lecture suffit : rendue telle quelle.
        assertFalse(Brain.suiteAttendue("lis mes SMS", "lire_sms"))
        assertFalse(Brain.suiteAttendue("montre-moi mon planning de lundi et mardi", "get_planning"))
        assertFalse(Brain.suiteAttendue("qu'est-ce que j'ai au programme demain", "get_planning"))
        assertFalse(Brain.suiteAttendue("quels sont mes réveils", "alarmes_liste"))
        assertFalse(Brain.suiteAttendue("résume mes mails", "summarize_mails"))
        assertFalse(Brain.suiteAttendue("mets un réveil à 7h", "alarme_creer"))
        assertFalse(Brain.suiteAttendue("donne-moi le titre et l'artiste", "jouer_musique"))
    }

    @Test fun laMaisonResteAJarvis() {
        // Tout ce qui passe par Home Assistant : JARVIS, jamais Hermès (consigne de l'utilisateur).
        val maison = listOf("quelle température fait-il dans le salon", "il fait combien dans la maison", "quels appareils sont indisponibles",
            "liste les entités du salon", "éteins tout et active le mode nuit", "allume le chauffage", "ferme la porte du garage",
            "est-ce que la porte du garage est fermée", "mets le poêle à 21 degrés", "les lumières de la chambre sont allumées ?")
        val erreurs = maison.map { it to fr.dd.jarvis.hermes.Routeur.decider("auto", false, false, it, null) }
            .filter { it.second.cible != fr.dd.jarvis.hermes.Routeur.Cible.JARVIS }.map { "« ${it.first} » → Hermès (${it.second.raison})" }
        assertTrue(erreurs.joinToString("\n"), erreurs.isEmpty())
        // Et ce qui n'est pas la maison n'y est pas pris.
        // « télé » ne doit plus être trouvé dans « téléphone », « télécharger », « télétravail ».
        for (p in listOf("où est mon téléphone", "baisse le volume du téléphone", "télécharge la facture", "je suis en télétravail demain"))
            assertFalse(p, fr.dd.jarvis.ai.MaisonDirecte.concerne(p))
        assertTrue(fr.dd.jarvis.ai.MaisonDirecte.concerne("allume la télé"))
        for (p in listOf("quel temps fait-il à Dijon", "quels appareils sont connectés à la Freebox", "fais un devis pour Dupont", "explique-moi la photosynthèse"))
            assertFalse(p, fr.dd.jarvis.ai.MaisonDirecte.concerne(p))
    }

    @Test fun argumentsDesCommandesDirectes() {
        val cd = fr.dd.jarvis.ai.CommandesDirectes
        assertEquals(null, cd.intention("rappelle le dernier numéro"))
        assertEquals(null, cd.intention("mets un rappel pour le rendez-vous de demain"))
        assertEquals("Sortir les poubelles", cd.intention("rappelle-moi de sortir les poubelles ce soir")!!.args.getString("texte"))
        assertEquals("Je serai en retard à notre rendez-vous",
            cd.intention("envoie un SMS à Martin pour lui dire que je serai en retard à notre rendez-vous")!!.args.getString("texte"))
        assertEquals("Dijon", cd.intention("quel temps fera-t-il demain à Dijon")!!.args.getString("lieu"))
        assertEquals("maman", cd.intention("appelle maman")!!.args.getString("contact"))
    }
}
