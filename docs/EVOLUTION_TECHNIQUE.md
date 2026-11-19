# Évolution technique de JARVIS — plan sans régression

## Règle de base

Cette évolution est additive et progressive. Le fonctionnement existant reste la référence : le routage rapide de `Brain`, le relais vers Hermès, la mémoire partagée, les outils du téléphone, Android Auto, la voix, les médias, Obsidian et les intégrations existantes ne doivent pas être remplacés par une nouvelle pile.

Aucune mise à niveau majeure de Kotlin, AGP, Gradle, Compose ou des API audio/voiture ne doit être mélangée à une correction fonctionnelle. Chaque groupe de changements doit être isolé, compilé et testé avant le suivant.

## Mise à niveau AGP et WorkManager

- AGP passe de `8.5.2` à `8.6.1`.
- Le workflow utilise déjà Gradle `8.7`, version requise par AGP 8.6.
- `compileSdk 35`, `minSdk 26` et `targetSdk 34` restent inchangés.
- WorkManager passe de `2.9.0` à `2.12.0`. La version 2.12.0 requiert au minimum compileSdk 33 et minSdk 24 ; Jarvis compile avec API 35 et prend en charge API 26+.
- Aucun changement n'est apporté aux Workers, aux identifiants de tâches, aux contraintes, aux périodicités ni à la persistance de leur planification. La migration est limitée à la bibliothèque et à l'outillage de compilation.
- La validation CI doit confirmer les tests unitaires et la construction de l'APK avant toute autre mise à niveau.

## Mises à niveau volontairement différées

- **Compose BOM** : ne pas passer directement à la BOM 2026.09.00. Les notes officielles Compose 1.12 indiquent une compilation avec API 37 et un AGP minimum 9. Le projet reste en compileSdk 35 / AGP 8.6.1. Une mise à niveau Compose nécessite donc une migration séparée de la chaîne de build.
- **Media3** : la version stable 1.11.1 existe, mais l'application utilise Transformer pour assembler des vidéos. Avant de remplacer 1.4.1, auditer les appels Transformer et faire une compilation + tests vidéo dédiés.
- **Kotlin / AGP / Gradle** : garder les versions Kotlin actuelles dans cette étape. Leur évolution doit être coordonnée, pas faite au coup par coup.
- **Coroutines / OkHttp / Serialization** : pas de mise à niveau sans audit des usages et des versions transitives. OkHttp est déjà présent ; ne pas ajouter un second client HTTP (Ktor, Retrofit, etc.) sans besoin démontré.
- **Sécurité Android** : `androidx.security:security-crypto` est dépréciée. Jarvis l'utilise pour migrer et stocker les clés API. Ne pas retirer cette dépendance avant d'avoir conçu et testé une migration compatible avec les secrets déjà enregistrés dans le Keystore et les anciens fichiers de préférences.
- **IA locale** : LiteRT peut être évalué pour des tâches locales légères, mais ne pas ajouter de modèle ou de runtime tant qu'un cas d'usage, la taille du modèle, la compatibilité CPU/NPU et le budget mémoire/batterie ne sont pas mesurés.
- **Recherche mémoire** : exploiter d'abord Room et les fichiers Markdown comme sources de vérité. Évaluer FTS pour la recherche lexicale et les embeddings uniquement après mesure sur un jeu de requêtes réel.

## Architecture cible à préserver

1. **Chemin rapide** : commandes locales déterministes et conversation courte avant tout appel distant.
2. **Routage IA** : Groq / Gemini selon les capacités et la disponibilité, puis Hermès pour les tâches qui le justifient. Pas de relais coûteux en boucle.
3. **Mémoire unique** : une source logique partagée Jarvis/Hermès ; index et caches reconstruisibles, sans créer de coffre parallèle.
4. **Connecteurs isolés** : chaque service possède son adaptateur, ses délais d'attente, ses erreurs et ses autorisations.
5. **Home Assistant distant** : profil dédié à l'instance joignable par DuckDNS/API.
6. **Serveur Debian Jarvis** : profil distinct, accès système/SSH dédié. Il ne doit jamais être considéré comme l'hôte Docker de Home Assistant puisque les deux environnements sont séparés.
7. **Réparations sûres** : diagnostic avant action, liste blanche d'actions non destructives, sauvegarde et validation avant changement de configuration, vérification après action, journal d'audit et arrêt après échec répété. Suppression de données/conteneurs et redémarrage du serveur Debian jamais automatiques.

## Plan de validation avant toute fusion

- Build debug et tests unitaires GitHub Actions.
- Vérifier démarrage, réglages et restauration de l'état précédent.
- Tester micro, interruption de parole, TTS, bip d'écoute et reprise de conversation.
- Tester Android Auto : affichage, play/pause/stop, navigation et interruption par la voix.
- Tester lecture et assemblage vidéo, sans modifier les flux médias existants.
- Tester mémoire partagée Jarvis/Hermès et accès au coffre Obsidian configuré.
- Tester séparément le profil Home Assistant DuckDNS et le profil Debian ; vérifier qu'aucune donnée ni autorisation ne fuit d'un profil à l'autre.

## Références officielles consultées

- Android Gradle Plugin : https://developer.android.com/build/releases/about-agp
- AndroidX WorkManager : https://developer.android.com/jetpack/androidx/releases/work/
- AndroidX Compose BOM : https://developer.android.com/develop/ui/compose/bom
- AndroidX Compose UI : https://developer.android.com/jetpack/androidx/releases/compose-ui
- AndroidX Media3 : https://developer.android.com/jetpack/androidx/releases/media3
- Kotlin releases : https://kotlinlang.org/docs/releases.html
