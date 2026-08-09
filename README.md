# JARVIS — Assistant Android (voix + texte)

Application Android façon JARVIS : chat texte et vocal, connectée à une IA
(compatible avec une API locale type **Ollama** ou une API cloud compatible
OpenAI).

## 🚀 Générer l'APK via GitHub Actions (sans installer Android Studio)

1. Crée un nouveau dépôt sur GitHub (ex: `jarvis-assistant`).
2. Mets tous les fichiers de ce projet dans le dépôt et pousse-les :
   ```bash
   git init
   git add .
   git commit -m "Premier commit - JARVIS"
   git branch -M main
   git remote add origin https://github.com/TON-COMPTE/jarvis-assistant.git
   git push -u origin main
   ```
3. Va dans l'onglet **Actions** de ton dépôt GitHub : le workflow
   `Build JARVIS APK` se lance automatiquement.
4. Une fois le workflow terminé (icône verte ✅), clique dessus puis
   descends jusqu'à **Artifacts** → télécharge `jarvis-debug-apk`.
5. Décompresse le zip téléchargé : tu obtiens `app-debug.apk`.
6. Transfère cet APK sur ton téléphone Android et installe-le (autorise
   "sources inconnues" si demandé).

Tu peux aussi relancer le build manuellement depuis Actions →
`Build JARVIS APK` → **Run workflow**.

## 🧠 Connecter une IA locale (recommandé : Ollama)

1. Sur ton PC, installe [Ollama](https://ollama.com).
2. Lance un modèle, par exemple :
   ```bash
   ollama run llama3.1
   ```
   Ollama expose alors une API compatible OpenAI sur le port `11434`.
3. Trouve l'adresse IP locale de ton PC (ex: `192.168.1.50`) via
   `ipconfig` (Windows) ou `ifconfig` / `ip a` (Mac/Linux).
4. Assure-toi que ton PC et ton téléphone sont **sur le même réseau Wi-Fi**.
5. Dans l'app JARVIS, ouvre les paramètres (⚙) et renseigne :
   - **URL de base** : `http://192.168.1.50:11434/v1/chat/completions`
   - **Modèle** : `llama3.1` (ou le nom du modèle lancé)
   - **Clé API** : laisse vide (pas nécessaire en local)

> Si tu testes sur un émulateur Android Studio (et non un vrai téléphone),
> utilise `http://10.0.2.2:11434/v1/chat/completions` — c'est l'alias par
> défaut déjà préconfiguré dans l'app.

## ☁️ Ou connecter une IA dans le cloud

Renseigne simplement dans les paramètres :
- **URL de base** de l'API compatible OpenAI (ex: un fournisseur comme
  OpenRouter, Together AI, etc.)
- **Modèle** correspondant
- **Clé API** fournie par le service

## 📁 Structure du projet

```
JarvisAssistant/
├── .github/workflows/build.yml   → build automatique de l'APK
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/jarvis/assistant/
│       │   ├── MainActivity.kt      → écran chat + micro
│       │   ├── SettingsActivity.kt  → configuration API
│       │   ├── ApiClient.kt         → appels réseau vers l'IA
│       │   ├── ChatAdapter.kt       → affichage des bulles
│       │   ├── Message.kt
│       │   └── Prefs.kt
│       └── res/                     → thème sombre/cyan façon JARVIS
├── build.gradle
└── settings.gradle
```

## ✨ Fonctionnalités

- 💬 Chat texte avec historique de conversation
- 🎤 Reconnaissance vocale (parlez à JARVIS)
- 🔊 Synthèse vocale (JARVIS vous répond à voix haute)
- ⚙️ API entièrement configurable (locale ou cloud)
- 🎨 Interface sombre façon JARVIS (Iron Man)

## 🔧 Personnalisation

- Couleurs : `app/src/main/res/values/colors.xml`
- Icône : `app/src/main/res/drawable/ic_launcher.xml`
- Ton/personnalité de JARVIS : modifie le message "system" dans
  `ApiClient.kt`
