# JARVIS Mobile

Assistant personnel Android complet : chat texte/vocal avec orb animée, IA locale + serveur local (Ollama) + cloud (Claude, GPT, Gemini, Groq, Perplexity/SerpAPI), persistance Obsidian (fiches contact, notes, projets à la demande), génération de documents (docx/xlsx/pdf/zip) et d'images, domotique Home Assistant embarquée, contrôle Freebox local/distant, contrôle total du téléphone (agenda, SMS, notifications, lampe, Bluetooth, Wi-Fi, apps, volume), recherche web résumée dans le chat, module de codage embarqué avec publication GitHub, et détection de mot-clé "Jarvis" gratuite (openWakeWord + Vosk).

Inspiré des projets communautaires type Iron Man Jarvis / TechExClaire, repensé pour tourner entièrement sur smartphone.

## Statut

Squelette de projet Phase 0 : structure Gradle/Compose/Hilt complète, UI de base (orb + chat + réglages), interfaces et implémentations de départ pour chaque module. Voir `docs/ARCHITECTURE.md` (section 5) pour le détail précis de ce qui fonctionne déjà vs. ce qui reste à implémenter, et `docs/ROADMAP.md` pour l'ordre de développement recommandé.

## Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — architecture technique complète, tous les modules.
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — plan de développement par phases.
- [`docs/SETUP.md`](docs/SETUP.md) — build, configuration, push GitHub.

## Structure

```
JarvisAI/
  app/src/main/java/com/jarvis/ai/
    ui/          orb, chat, réglages, navigation
    core/ai/     routeur multi-IA (cloud + local + serveur)
    core/voice/  wake word, Whisper, TTS
    core/obsidian/   vault, templates de notes/fiches
    core/documents/  docx, xlsx, pdf, zip
    core/homeassistant/  client Home Assistant
    core/freebox/        client Freebox OS
    core/phonecontrol/   agenda, SMS, notifications, capteurs, apps
    core/coding/         agent de codage + publication GitHub
    core/imagegen/       génération d'images
    core/websearch/      recherche web résumée
    core/websitegen/     génération de site statique
    data/        Room + DataStore (réglages chiffrés)
```

## Tout est paramétrable

Aucune clé, aucun chemin, aucun endpoint n'est en dur : tout se configure dans l'écran Réglages de l'app (clés IA, vault Obsidian, Home Assistant, Freebox, GitHub).
