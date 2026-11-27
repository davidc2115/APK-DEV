package fr.dd.jarvis.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationVocaleTest {
    @Test fun commandesSimplesSansQuestion() {
        // Questions réelles posées par les outils.
        assertFalse(ConfirmationVocale.necessaire("Exécuter l'action « call » (Paul) sur le téléphone ?", false))
        assertFalse(ConfirmationVocale.necessaire("Exécuter l'action « wifi_off » sur le téléphone ?", false))
        assertFalse(ConfirmationVocale.necessaire("Exécuter l'action « click_text » (Y aller) sur le téléphone ?", false))
        assertFalse(ConfirmationVocale.necessaire("Je prends la main sur l'écran pour : « lance la lecture de Daft Punk ». Continuer ?", false))
        assertFalse(ConfirmationVocale.necessaire("Appeler Paul Martin (06 12 34 56 78) ?", true))
        assertFalse(ConfirmationVocale.necessaire("Appeler Garage Dupont (03 80 12 34 56) ?", true))
        assertFalse(ConfirmationVocale.necessaire("J'active les appels Wi-Fi (appels et SMS par ta box quand l'antenne est faible) ?", true))
        // Commandes simples dictées (téléphone ou Android Auto) qui posaient encore la question.
        assertFalse(ConfirmationVocale.necessaire("Lancer « Inception » sur « Salon » via Plex (reprise à 1 h 02) ?", true))
        assertFalse(ConfirmationVocale.necessaire("Appuyer sur « Lecture » ?", true))
        assertFalse(ConfirmationVocale.necessaire("Je prends la main sur l'écran pour : « désactive le Bluetooth et ouvre les réglages du son ». Continuer ?", false))
        assertFalse(ConfirmationVocale.necessaire("Exécuter l'action « bluetooth_off » sur le téléphone ?", false))
    }

    @Test fun irreversibleToujoursConfirme() {
        assertTrue(ConfirmationVocale.necessaire("Envoyer ce SMS à Paul (06 12 34 56 78) ?\n\n« J'arrive »", true))
        assertTrue(ConfirmationVocale.necessaire("Envoyer ce mail depuis pro ?\n\nÀ : x@y.fr", true))
        assertTrue(ConfirmationVocale.necessaire("Supprimer l'événement #42 de l'agenda ?", false))
        assertTrue(ConfirmationVocale.necessaire("Créer la facture pour Martin ? (numéro définitif, non supprimable)", false))
        assertTrue(ConfirmationVocale.necessaire("Supprimer la routine « mode coucher » ?", false))
        assertTrue(ConfirmationVocale.necessaire("Home Assistant : exécuter lock.unlock lock.porte_entree?", true))
        assertTrue(ConfirmationVocale.necessaire("Redémarrer Home Assistant ? (configuration vérifiée avant)", true))
        assertTrue(ConfirmationVocale.necessaire("Je prends la main sur l'écran pour : « envoie un message à Paul ». Continuer ?", false))
        assertTrue(ConfirmationVocale.necessaire("Home Assistant : installer l'intégration Météo-France ?", true))
        assertTrue(ConfirmationVocale.necessaire("Plex : PUT /library/sections/1/refresh ?", true))
        assertTrue(ConfirmationVocale.necessaire("Rendre « Caméra » (192.168.1.20, http) accessible depuis Internet ?", true))
        assertTrue(ConfirmationVocale.necessaire("Envoyer le devis DEV-2026-0003 par email depuis pro ?", true))
        assertTrue(ConfirmationVocale.necessaire("Appuyer sur « Payer 30 € » ?", true))
        assertTrue(ConfirmationVocale.necessaire("🛡️ Désactiver le système de sécurité « Maison » ?", true))
        // Une demande forcée inconnue reste confirmée.
        assertTrue(ConfirmationVocale.necessaire("Autoriser Hermès à exécuter « x » pour « y » dans Termux ?", true))
    }
}
