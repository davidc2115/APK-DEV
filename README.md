# JARVIS — assistant personnel Android (interface HUD)

Application Android (Kotlin + Jetpack Compose) refaite de zéro.

## Ce que fait l'application

- **Chat texte + vocal** : micro, réponse vocale, mode conversation (JARVIS réécoute après avoir répondu).
- **Interface HUD** : tuiles transparentes à coins coupés, fond quadrillé, orbe animé. Couleur au choix (cyan, bleu, vert, rouge, violet, orange, rose) et transparence réglable dans *Réglages*.
- **Cerveau = Groq** : plusieurs clés, rotation automatique (une clé par requête, pause automatique d'une clé saturée).
- **Agents = Gemini** : plusieurs clés, rotation automatique ; repli sur Groq s'il n'y a pas de clé Gemini.
  - *Planning* : plusieurs agendas, renommables, couleur par agenda et par rendez-vous, création / suppression de RDV.
  - *Mails* (IMAP) : tri, résumé, point du matin.
  - *Contacts* : liste, création, modification, **fiche Markdown pour Obsidian** générée par l'IA.
- **Point du matin** : notification quotidienne (agenda du jour + mails importants), heure réglable.
- **Contrôle du téléphone** : lampe, apps, volume, Wi-Fi, Bluetooth, luminosité, appels, lecture de l'écran, appuis, saisie, défilement (Accessibilité + Shizuku/adb).
  Les actions sensibles et toute commande shell demandent une confirmation dans l'app.

## Premier lancement

1. *Réglages* → coller les clés Groq et Gemini → **Tester**.
2. *Réglages › Mails* : serveur IMAP (Gmail : `imap.gmail.com`, mot de passe d'application).
3. *Réglages › Obsidian* : choisir le coffre (les fiches vont dans `Contacts/`).
4. *Réglages › Contrôle du téléphone* : activer l'accessibilité ; installer et démarrer Shizuku, puis l'autoriser.
5. Xiaomi / HyperOS : autoriser le démarrage automatique et désactiver l'économie de batterie pour JARVIS.

## Build

GitHub Actions (`.github/workflows/build.yml`) compile l'APK à chaque push ; il se télécharge dans l'onglet *Actions › Artifacts*.
Le keystore `app/debug.keystore` est versionné pour permettre les mises à jour par-dessus l'APK précédent.
En cas de plantage, l'app affiche la trace au lancement suivant (bouton Copier).

## Structure

```
app/src/main/java/fr/dd/jarvis/
  ai/       Groq, Gemini, rotation des clés, cerveau (Brain), outils (ToolBox), prompts des agents
  data/     réglages (clés chiffrées)
  device/   agenda, mails IMAP, contacts, Obsidian, Shizuku, contrôle du téléphone
  service/  service d'accessibilité
  voice/    reconnaissance et synthèse vocales
  work/     point du matin (WorkManager)
  ui/       thème HUD, composants, écrans
```
