# JARVIS v2 — Assistant Android IA (voix + texte)

> Application Android façon JARVIS (Iron Man) : chat texte et vocal,
> connectée à **10+ fournisseurs IA cloud** et capable d'exécuter des
> **modèles locaux hors-ligne** (GGUF, ONNX, MediaPipe .task).

---

## 🆕 Nouveautés v2

| Fonctionnalité | Détail |
|---|---|
| **Multi-clés API** | Chaque provider a sa propre clé (fini le partage d'une seule clé) |
| **10 providers cloud** | Groq, OpenAI, Claude, Gemini, Mistral, DeepSeek, Perplexity, Together, OpenRouter, SerpAPI |
| **Mode Automatique** | Essaie tous les providers configurés dans l'ordre jusqu'à obtenir une réponse |
| **GGUF local** | Modèles llama.cpp sur le téléphone : Phi-3, LLaMA 3.2, Gemma 2, Mistral… |
| **ONNX local** | Modèles ONNX Runtime GenAI (Phi-3 Mini, etc.) |
| **Catalogue modèles** | 6 modèles pré-listés téléchargeables directement depuis l'app |
| **UI 3 onglets** | ☁ Config / 🔑 Clés API / 🧠 Local |

---

## 🚀 Générer l'APK via GitHub Actions

1. Fork ce dépôt → onglet **Actions** → workflow `Build JARVIS APK` se lance automatiquement
2. ✅ Une fois terminé → **Artifacts** → télécharge `jarvis-debug-apk`
3. Décompresse → installe `app-debug.apk` sur ton Android (autorise "sources inconnues")

Ou relancer manuellement : Actions → `Build JARVIS APK` → **Run workflow**.

---

## ☁️ Fournisseurs cloud supportés

| Provider | Modèle par défaut | Clé API |
|---|---|---|
| **Groq** (gratuit, ultra-rapide) | `llama-3.3-70b-versatile` | [groq.com/keys](https://console.groq.com/keys) |
| **OpenAI / ChatGPT** | `gpt-4o-mini` | [platform.openai.com](https://platform.openai.com/api-keys) |
| **Claude (Anthropic)** | `claude-sonnet-4-5` | [console.anthropic.com](https://console.anthropic.com/) |
| **Google Gemini** | `gemini-2.0-flash-lite` | [aistudio.google.com](https://aistudio.google.com/apikey) |
| **Mistral AI** | `mistral-large-latest` | [console.mistral.ai](https://console.mistral.ai/) |
| **DeepSeek** | `deepseek-chat` | [platform.deepseek.com](https://platform.deepseek.com/) |
| **Perplexity AI** | `sonar` | [perplexity.ai/settings/api](https://www.perplexity.ai/settings/api) |
| **Together AI** | `Mixtral-8x7B-Instruct-v0.1` | [api.together.ai](https://api.together.ai/) |
| **OpenRouter** | `openai/gpt-4o-mini` | [openrouter.ai/keys](https://openrouter.ai/keys) |
| **SerpAPI** | (recherche web) | [serpapi.com/manage-api-key](https://serpapi.com/manage-api-key) |

### Configuration multi-clés

Dans l'app → ⚙ → onglet **🔑 Clés API** : remplis les clés des services
que tu possèdes. Le **Mode Automatique** utilisera automatiquement ceux
qui ont une clé configurée, dans l'ordre de priorité ci-dessus.

---

## 🧠 Modèles locaux (hors-ligne, sans internet)

### Option A : GGUF via llama.cpp (recommandé)

| Modèle | Taille | RAM minimale |
|---|---|---|
| Phi-3 Mini 4K Q4_K_M | ~2.2 Go | 4 Go |
| Llama 3.2 3B Q4_K_M | ~2.0 Go | 4 Go |
| Gemma 2 2B Q4_K_M | ~1.6 Go | 3 Go |
| Mistral 7B Q4_K_M | ~4.1 Go | 6 Go |

1. Dans l'app → ⚙ → onglet **🧠 Local**
2. Sélectionne le format **GGUF (.gguf — llama.cpp)**
3. Clique sur ⬇ ou colle l'URL HuggingFace d'un modèle .gguf
4. Sélectionne le provider **"Modèle GGUF sur téléphone"**

### Option B : MediaPipe .task (Gemma)

Modèle Gemma 3 1B officiel (~550 Mo). Téléchargeable directement
depuis l'app (jeton HuggingFace gratuit requis pour la version officielle).

### Option C : Ollama sur PC (réseau local)

```bash
ollama run llama3.1
```
Dans l'app : URL = `http://<IP_DE_TON_PC>:11434/v1/chat/completions`  
PC et téléphone doivent être sur le même Wi-Fi.

---

## 📁 Structure du projet

```
APK-DEV/
├── .github/workflows/build.yml     → CI/CD build APK
├── app/
│   ├── build.gradle                → dépendances (MediaPipe, llama-android, ONNX)
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/jarvis/assistant/
│           ├── Provider.kt         → 14 providers (cloud + local)
│           ├── Prefs.kt            → stockage multi-clés par provider
│           ├── ApiClient.kt        → routing IA (cloud + SerpAPI + local)
│           ├── LocalLlmManager.kt  → backend TASK/GGUF/ONNX
│           ├── ModelDownloader.kt  → catalogue + téléchargement multi-format
│           ├── SettingsActivity.kt → UI 3 onglets
│           ├── MainActivity.kt     → chat + micro
│           ├── VoiceModeActivity.kt
│           ├── ChatAdapter.kt
│           └── OrbView.kt
├── settings.gradle                 → + JitPack (llama-android)
└── README.md
```

---

## ✨ Toutes les fonctionnalités

- 💬 Chat texte avec historique de conversation
- 🎤 Reconnaissance vocale (parle à JARVIS)
- 🔊 Synthèse vocale (JARVIS répond à voix haute)
- ☁️ 10 providers IA cloud avec clés individuelles
- 🤖 Mode Automatique (failover multi-provider)
- 🔍 Recherche web temps réel (SerpAPI)
- 🧠 Modèles locaux hors-ligne : GGUF / ONNX / .task
- 📥 Téléchargement de modèles directement depuis l'app
- 🎨 Interface sombre façon Iron Man (thème JARVIS)
- 🎨 Couleur de l'orbe personnalisable (6 couleurs)
