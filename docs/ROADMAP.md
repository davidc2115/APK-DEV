# Roadmap par phases

## Phase 0 — Fondations (ce livrable)
- Projet Android buildable (Gradle/Compose/Hilt).
- Orb + chat + réglages.
- Routage multi-IA (cloud + local + serveur), paramétrable.
- Squelettes Obsidian, documents, Home Assistant, Freebox, contrôle téléphone, codage.

## Phase 1 — Assistant conversationnel utilisable
- Brancher une vraie clé Claude/GPT/Gemini/Groq et valider le chat de bout en bout.
- Intégrer whisper.cpp (STT local) + TTS natif.
- Activation par bouton (wake word pas encore actif).

## Phase 2 — Réveil vocal et persistance Obsidian
- Entraîner/intégrer le modèle openWakeWord "Jarvis" (+ fallback Vosk).
- Connecter un vault Obsidian réel (SAF), activer génération de fiches contact et notes de projet.
- Historique conversations miroité en Markdown.

## Phase 3 — Domotique et réseau
- Connexion Home Assistant (local + distant) avec vraies entités.
- Connexion Freebox (local + distant), premières commandes (Wi-Fi, redémarrage, liste appareils).

## Phase 4 — Contrôle téléphone
- Activer un par un : lampe torche, Bluetooth, Wi-Fi, ouverture d'app, volume, agenda, SMS, notifications — chacun avec écran de consentement dédié.

## Phase 5 — Documents, images, site web
- Génération docx/xlsx/pdf/zip réelle et testée.
- Génération d'images (fournisseur cloud au choix).
- Générateur de site web statique + export zip.

## Phase 6 — Codage embarqué et GitHub
- Module codeur (appel IA + écriture fichiers + JGit).
- Publication GitHub (création de repo + push).

## Phase 7 — IA locale avancée
- Intégration llama.cpp/MLC avec modèle quantisé embarqué.
- Connexion à un serveur Ollama local (PC/NAS) en option plus puissante.

## Phase 8 — Durcissement et distribution
- Revue sécurité complète (clés, permissions).
- Build APK signé, procédure de sideload, documentation utilisateur finale.

Chaque phase est indépendante : on peut s'arrêter, tester sur appareil réel, puis reprendre.
