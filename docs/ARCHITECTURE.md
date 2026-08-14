# JARVIS Mobile — Architecture technique

Assistant personnel complet, 100% smartphone Android, inspiré d'Iron Man / des projets "Jarvis" communautaires (TechExClaire et équivalents), avec persistance Obsidian, chat texte/vocal, IA multi-fournisseurs (locale + cloud), domotique, contrôle réseau/téléphone et capacité de codage embarquée.

## 1. Principes directeurs

- **Tout est paramétrable** : aucune clé API, chemin de vault, endpoint Home Assistant ou Freebox n'est codé en dur. Tout passe par un écran Réglages + un DataStore chiffré.
- **Local-first pour les données** : la mémoire "de vérité" est le vault Obsidian (fichiers Markdown), pas une base propriétaire. La base Room locale sert de cache/index rapide, synchronisé vers Obsidian.
- **Dégradation gracieuse** : si aucune IA cloud n'est configurée ou hors-ligne, on retombe sur le modèle local embarqué (petit LLM quantisé) ou sur un serveur local (Ollama sur PC/NAS).
- **Sécurité par défaut** : toutes les clés API et tokens (Freebox, GitHub, IA) sont stockés via `EncryptedSharedPreferences` / Jetpack Security, jamais en clair, jamais commit dans le repo.
- **Architecture modulaire** : chaque grande fonction (IA, voix, Obsidian, documents, domotique, Freebox, contrôle téléphone, codage) est un module Kotlin indépendant avec une interface claire, activable/désactivable depuis les Réglages.

## 2. Stack technique

| Domaine | Choix | Raison |
|---|---|---|
| Langage | Kotlin | standard Android moderne |
| UI | Jetpack Compose | animations fluides pour l'orb, réactif |
| DI | Hilt | injection propre entre modules |
| Réseau | Retrofit + OkHttp + Moshi/kotlinx.serialization | appels IA cloud, Home Assistant, Freebox |
| Persistance locale | Room + DataStore (Preferences chiffrées) | cache conversations + réglages |
| Persistance durable | Fichiers Markdown Obsidian via Storage Access Framework (SAF) | vault accessible tel quel dans Obsidian mobile/desktop |
| Vocal | Whisper (whisper.cpp via JNI, ou API Whisper cloud en option) pour la transcription ; TTS Android natif ou ElevenLabs/Azure en option | STT/TTS |
| Wake word | openWakeWord (TFLite, gratuit, sans compte) en moteur principal + Vosk (léger, offline) en secours | détection "Jarvis" sans clé payante |
| IA locale embarquée | llama.cpp / MLC-LLM avec modèle quantisé (Phi-3-mini, Gemma-2B, Qwen2.5-1.5B) | fonctionne hors-ligne sur le téléphone |
| IA serveur local | Ollama tournant sur PC/NAS, accessible en Wi-Fi local ou VPN (Tailscale/WireGuard) | modèles plus puissants, machine dédiée |
| IA cloud | Claude (Anthropic), GPT (OpenAI), Gemini (Google), Groq (inference rapide), Perplexity + SerpAPI (recherche web) | qualité/rapidité selon la tâche |
| Documents | Apache POI (docx/xlsx), PdfBox-Android ou iText, java.util.zip | génération de fichiers à la demande |
| Domotique | API REST/WebSocket Home Assistant (Long-Lived Access Token) | contrôle local + à distance via Nabu Casa ou reverse proxy perso |
| Freebox | API Freebox OS (découverte mDNS locale + Freebox OS distant via `https://mafreebox.freebox.fr` ou domaine `.fbxos.fr`) | contrôle box/routeur |
| Codage embarqué | Appel à un modèle IA "codeur" (Claude/GPT) + gestion Git locale via JGit + push HTTPS token | équivalent Claude Code/Antigravity, mobile |

## 3. Modules fonctionnels

### 3.1 Cœur conversationnel (chat texte + vocal + Orb)
- Écran de chat Compose (bulle utilisateur/assistant).
- Vue `OrbView` : sphère animée (glow, pulsation liée à l'état — idle/écoute/réflexion/réponse), façon Jarvis/Obsidian.
- `AIRouter` : sélectionne le fournisseur actif selon les réglages (règles : tâche de code → fournisseur "codeur" ; recherche web → Perplexity/SerpAPI ; hors-ligne → LLM local).
- Historique persistant en Room + miroir Markdown dans `Vault/Jarvis/Conversations/`.

### 3.2 Détection du mot-clé ("Jarvis")
- Service au premier plan (`ForegroundService`) avec notification persistante (obligatoire Android 13+).
- `WakeWordDetectionManager` : pipeline audio → openWakeWord (modèle TFLite entraînable pour "Jarvis") → fallback Vosk si le modèle personnalisé n'est pas encore entraîné.
- Aucune clé, aucun compte : tout tourne en local sur l'appareil.
- Option "bouton uniquement" pour économiser la batterie (activable dans les réglages).

### 3.3 Transcription et synthèse vocale
- `WhisperTranscriber` : whisper.cpp compilé en `.so` (JNI) pour transcription 100% locale, avec option bascule vers l'API Whisper cloud si configurée (meilleure précision, nécessite clé).
- `TextToSpeechEngine` : TTS Android natif par défaut, branchement optionnel vers des voix cloud (ElevenLabs, Azure) si clé fournie.

### 3.4 Persistance Obsidian
- `ObsidianVaultManager` : accès au vault via SAF (`ACTION_OPEN_DOCUMENT_TREE`), lecture/écriture de fichiers `.md`.
- Templates paramétrables (`ContactCardTemplate`, `ProjectNoteTemplate`, `MeetingNoteTemplate`...) avec frontmatter YAML (tags, catégories, propriétés) modifiable par l'utilisateur.
- Fiches contact générées à la demande ("crée une fiche contact pour Marie, tel 06.., catégorie Famille") → fichier `.md` structuré dans `Vault/Contacts/`.
- Toute modification (ajout de catégorie, changement de présentation, nouveau projet GitHub suivi) est un simple patch du Markdown → versionnable nativement si le vault est aussi un repo Git.
- `ObsidianSyncWorker` (WorkManager) : synchronise en tâche de fond le cache Room vers les fichiers Markdown.

### 3.5 Génération de documents à la demande
- `DocxGenerator`, `XlsxGenerator`, `PdfGenerator`, `ZipGenerator` : interfaces communes `DocumentGenerator`, appelées depuis le chat ("génère-moi un PDF de ce compte-rendu", "exporte ce projet en zip").
- Sortie enregistrée dans un dossier configurable (Téléchargements ou sous-dossier du vault) puis partageable via `Intent.ACTION_SEND`.

### 3.6 Domotique — Home Assistant embarqué
- `HomeAssistantClient` : REST (`/api/services/...`) + WebSocket (état temps réel des entités).
- Configuration : URL locale (découverte mDNS `_home-assistant._tcp`) + URL distante (Nabu Casa ou reverse proxy) + Long-Lived Access Token, tout dans les réglages.
- Commandes vocales/texte mappées vers des `service_call` (lumières, prises, scènes, capteurs).

### 3.7 Contrôle Freebox (local + distant)
- `FreeboxClient` : découverte locale (`http://mafreebox.freebox.fr/api_version`), authentification par app_token (validé une fois manuellement sur l'écran de la Freebox), puis appels signés (HMAC session).
- Accès distant via l'URL `.fbxos.fr` fournie par la Freebox (si activé côté box).
- Fonctions cibles : Wi-Fi on/off, redémarrage box, liste des appareils connectés, contrôle du profil parental, VPN.

### 3.8 Contrôle total du smartphone
- `PhoneControlManager` regroupant, chacun derrière une permission runtime explicite :
  - Agenda (CalendarContract), SMS (SmsManager + permission `SEND_SMS`/`READ_SMS`), notifications (`NotificationListenerService`), lampe torche (`CameraManager.setTorchMode`), Bluetooth (`BluetoothAdapter`), Wi-Fi (`WifiManager`, limité par les restrictions Android 10+), ouverture d'application (`PackageManager` + `Intent(ACTION_MAIN)`), volume (`AudioManager`).
- `PermissionsManager` centralise les demandes et l'état (rien n'est activé sans consentement explicite, affiché dans les réglages).

### 3.9 Recherche web résumée dans le chat
- `WebSearchProvider` : SerpAPI et/ou Perplexity API → récupère les résultats, les résume en texte directement dans la conversation, sans ouvrir de navigateur (option "ouvrir la source" en lien secondaire).

### 3.10 Génération d'images
- `ImageGenProvider` : interface commune vers un fournisseur cloud configurable (Stability AI, DALL·E, Gemini Images...), image renvoyée dans le chat et exportable dans le vault ou en pièce jointe.

### 3.11 Génération de site web
- `WebsiteGenerator` : à partir d'une description, génère un site statique (HTML/CSS/JS) dans un dossier zippable, prévisualisable, exportable/déployable (GitHub Pages via le module codage).

### 3.12 Module codage embarqué (type Claude Code / Antigravity, sur mobile)
- `CodeAgentModule` : envoie la demande de code à un fournisseur IA "codeur" (Claude/GPT), reçoit les fichiers générés, les écrit dans un dossier de projet local.
- Dépôt Git local géré via JGit (init, add, commit) sans dépendre d'un terminal.
- `GitHubPublisher` : création de repo distant (API GitHub, token personnel stocké chiffré) + push HTTPS.

## 4. Sécurité et confidentialité

- Toutes les clés (IA, Freebox, Home Assistant, GitHub) chiffrées via Jetpack Security (`EncryptedSharedPreferences`/DataStore + Tink).
- Permissions Android sensibles (SMS, notifications, localisation si ajoutée plus tard) demandées à l'usage, jamais au démarrage.
- Aucune donnée envoyée à un fournisseur cloud sans que ce fournisseur soit explicitement activé par l'utilisateur.
- Le vault Obsidian reste la propriété de l'utilisateur (dossier local ou synchronisé par lui via Syncthing/Obsidian Sync/Git — au choix, hors périmètre de l'app).

## 5. Ce que ce squelette contient déjà vs. ce qui reste à faire

**Fait (structure + code de base fonctionnel ou quasi) :**
- Projet Gradle complet, Compose, Hilt, navigation.
- Orb animée, écran de chat, écran de réglages.
- Interfaces + implémentations Retrofit pour Claude/OpenAI/Gemini/Groq/Perplexity/Ollama.
- `AIRouter` avec logique de sélection.
- Squelettes fonctionnels Home Assistant / Freebox (auth + appels REST de base).
- `PhoneControlManager` avec vraies API Android pour chaque fonction.
- `ObsidianVaultManager` avec vrai accès SAF + templates Markdown.
- DataStore de réglages chiffré, tout paramétrable.

**À compléter (nécessite des choix/tests sur appareil réel) :**
- Entraînement du modèle openWakeWord pour "Jarvis" (outil externe, fichier `.tflite` à générer puis déposer dans `assets/`).
- Intégration binaire whisper.cpp (compilation NDK) et d'un LLM local quantisé (poids du modèle à télécharger, non inclus — trop volumineux pour ce squelette).
- Génération réelle docx/xlsx/pdf (dépendances Apache POI/PdfBox à ajouter, testées sur device car certaines libs desktop ne sont pas 100% compatibles Android — alternatives listées dans le code).
- Tests d'intégration Home Assistant/Freebox avec de vrais tokens.
- Durcissement des permissions Android 13+/14 (notifications, foreground service type, restrictions Play Store si publication).

## 6. Distribution

- Publication Play Store impossible telle quelle (permissions trop larges : lecture SMS, notifications, accessibilité). Distribution prévue en **APK signé, installation manuelle (sideload)**, ou dépôt privé (F-Droid perso / GitHub Releases).
