# Installation et build

## Prérequis
- Android Studio (dernière version stable) avec SDK 34+.
- JDK 17.
- Un appareil Android réel recommandé (émulateur limité pour micro/capteurs).

## Ouvrir le projet
1. `File > Open` dans Android Studio, sélectionner le dossier `JarvisAI/`.
2. Laisser Gradle synchroniser (première fois : peut échouer si des dépendances lourdes — whisper.cpp/llama.cpp — ne sont pas encore ajoutées ; voir `docs/ARCHITECTURE.md` section 5).
3. Renseigner les clés API dans l'écran **Réglages** de l'app une fois lancée (rien en dur dans le code).

## Config minimale pour un premier build qui tourne
- Commenter/retirer temporairement les appels aux modules non encore implémentés (wake word, LLM local) si le SDK correspondant n'est pas encore intégré — chaque module est isolé pour ça.
- Renseigner au moins UNE clé IA cloud (Claude, OpenAI, Gemini ou Groq) pour que le chat réponde.

## Pousser le projet sur GitHub
```bash
cd JarvisAI
git init
git add .
git commit -m "Initial scaffold: JARVIS mobile assistant"
git branch -M main
git remote add origin https://github.com/<ton-user>/<ton-repo>.git
git push -u origin main
```

## Prochaines étapes techniques concrètes
1. Ajouter les dépendances Retrofit/Moshi réelles (déjà déclarées dans `libs.versions.toml`), lancer un premier appel Claude.
2. Suivre `docs/ROADMAP.md` phase par phase.
