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

## Suivi automatisé des dépendances

Le dépôt utilise désormais Dependabot pour surveiller chaque semaine les dépendances Gradle et les actions GitHub. Les mises à jour mineures et correctives sont regroupées pour limiter le bruit ; les mises à jour majeures restent séparées. Les PR générées ne sont jamais fusionnées automatiquement : elles doivent passer la CI et être examinées, en particulier pour les bibliothèques touchant à la voix, Android Auto, la sécurité, la mémoire ou le montage vidéo.

## Mises à niveau volontairement différées

- **Compose BOM** : ne pas passer directement à la BOM 2026.09.00. Les notes officielles Compose 1.12 indiquent une compilation avec API 37 et un AGP minimum 9. Le projet reste en compileSdk 35 / AGP 8.6.1. Une mise à niveau Compose nécessite donc une migration séparée de la chaîne de build.
- **Media3** : la version stable 1.11.1 existe, mais l'application utilise Transformer pour assembler des vidéos. Avant de remplacer 1.4.1, auditer les appels Transformer et faire une compilation + tests vidéo dédiés.
- **Kotlin / AGP / Gradle** : garder les versions Kotlin actuelles dans cette étape. Leur évolution doit être coordonnée, pas faite au coup par coup.
- **Coroutines / OkHttp / Serialization** : pas de mise à niveau sans audit des usages et des versions transitives. OkHttp est déjà présent ; ne pas ajouter un second client HTTP (Ktor, Retrofit, etc.) sans besoin démontré.
- **Sécurité Android** : `androidx.security:security-crypto` est dépréciée. Jarvis l'utilise pour migrer et stocker les clés API. Ne pas retirer cette dépendance avant d'avoir conçu et testé une migration compatible avec les secrets déjà enregistrés dans le Keystore et les anciens fichiers de préférences.
- **IA locale** : LiteRT peut être évalué pour des tâches locales légères, mais ne pas ajouter de modèle ou de runtime tant qu'un cas d'usage, la taille du modèle, la compatibilité CPU/NPU et le budget mémoire/batterie ne sont pas mesurés.
- **Recherche mémoire** : exploiter d'abord Room et les fichiers Markdown comme sources de vérité. Évaluer FTS pour la recherche lexicale et les embeddings uniquement après mesure sur un jeu de requêtes réel.

## Renforcement immédiat du stockage des secrets

La migration hors de `androidx.security:security-crypto` reste différée : il ne faut pas remplacer le chiffrement existant tant qu'une migration compatible avec le Keystore n'est pas testée. En revanche, les secrets qui étaient encore écrits dans les préférences ordinaires sont désormais déplacés vers le coffre chiffré existant (`EncryptedSharedPreferences`) :

- jeton Home Assistant, mot de passe et clé privée SSH, jeton GitHub et mot de passe du partage réseau ;
- clés Picovoice et TomTom, jeton Discord, identifiants OAuth TikTok, jeton Hugging Face, jeton du worker vidéo et mot de passe IMAP ;
- code de secours antivol et jetons Cloudflare (ID de compte + jeton).

La migration copie d'abord la valeur dans le stockage chiffré avec validation synchrone ; l'ancienne valeur en clair n'est supprimée qu'après confirmation de l'écriture. Si le coffre chiffré est indisponible, les opérations d'écriture échouent explicitement et les anciennes valeurs ne sont pas effacées. Les clés Groq, Gemini et OpenAI conservent leur stockage chiffré dédié existant.

Cette étape ne remplace pas encore la bibliothèque dépréciée : elle réduit l'exposition des secrets sans changer le format cryptographique ni imposer une réinitialisation des clés. La migration de bibliothèque devra être un chantier distinct, avec tests de reprise après mise à jour, perte/invalidation du Keystore, et vérification que chaque secret reste lisible après migration.

## Architecture cible à préserver

1. **Chemin rapide** : commandes locales déterministes et conversation courte avant tout appel distant.
2. **Routage IA** : Groq / Gemini selon les capacités et la disponibilité, puis Hermès pour les tâches qui le justifient. Pas de relais coûteux en boucle.
3. **Mémoire unique** : une source logique partagée Jarvis/Hermès ; index et caches reconstruisibles, sans créer de coffre parallèle.
4. **Connecteurs isolés** : chaque service possède son adaptateur, ses délais d'attente, ses erreurs et ses autorisations.
5. **Home Assistant distant** : profil dédié à l'instance joignable par DuckDNS/API.
6. **Serveur Debian Jarvis** : profil distinct, accès système/SSH dédié. Il ne doit jamais être considéré comme l'hôte Docker de Home Assistant puisque les deux environnements sont séparés.
7. **Réparations sûres** : diagnostic avant action, liste blanche d'actions non destructives, sauvegarde et validation avant changement de configuration, vérification après action, journal d'audit et arrêt après échec répété. Suppression de données/conteneurs et redémarrage du serveur Debian jamais automatiques.

## Séparation effective Home Assistant / Debian

Tant qu'aucun profil SSH HA indépendant n'existe, l'application fonctionne en mode API-only pour Home Assistant :

- Les appels REST/WebSocket vers l'URL HA configurée (DuckDNS/API) restent actifs.
- La détection de conteneur, les commandes Docker, le diagnostic local `127.0.0.1:8123`, le démarrage, la mise à jour et les scripts d'installation HACS par SSH sont désactivés.
- La lecture, l'écriture et la restauration de fichiers YAML sont suspendues ; le chemin historique `haDir` n'est pas réutilisé car il pouvait désigner le système de fichiers Debian.
- Un échec API ne déclenche jamais de repli sur `Serveur.exec`, `Serveur.execComplet` ou SFTP du serveur Debian.

- Les chemins génériques ont aussi été verrouillés : `ssh_commande`, `ssh_script` et `serveur_tache` refusent les commandes/scripts Docker ou CLI visant explicitement Home Assistant ; `ssh_fichier` refuse le conteneur HA ; `serveur_service` bloque un service identifié comme HA.
- Les sauvegardes disque et restaurations HA via `ha_sauvegarde` sont suspendues : aucune recherche d'archive, inspection Docker ou restauration ne doit être effectuée sur Debian. La sauvegarde officielle par API HA reste le chemin autorisé.
- `serveur_diagnostic` ne teste plus `localhost:8123` et ne lit plus les journaux HA : il décrit uniquement Debian/Jarvis. Le diagnostic HA passe par son API distante.

C'est une mesure de sécurité transitoire : elle peut temporairement retirer l'administration des fichiers YAML et les opérations Docker, mais elle préserve les commandes HA accessibles par API et empêche qu'une action HA modifie le mauvais hôte. La réactivation nécessitera un profil séparé avec hôte, port, utilisateur, clé/mot de passe, chemin de configuration et consentement explicite, ainsi que des tests prouvant que les appels SSH sont routés vers ce profil uniquement.

## Audit réseau local et clients Freebox

- Le scan Wi-Fi reste limité au sous-réseau privé (maximum /22), mais son exécution bloquante est maintenant plafonnée à 64 threads au lieu de 160 ; les résolutions DNS sont plafonnées à 24 threads au lieu d'un pool extensible sans borne.
- Les appels HTTP ont un délai total (`callTimeout`) en plus des délais de connexion/lecture, pour éviter qu'une réponse lente garde une opération bloquée indéfiniment.
- Le client TLS permissif reste réservé aux équipements locaux ; son gestionnaire de confiance est initialisé une seule fois.
- Les URL de description UPnP sont acceptées uniquement en HTTP(S), sans identifiants intégrés, et si tous les résultats DNS sont des adresses IPv4 privées/link-local. Leur lecture ne suit pas les redirections.
- Les uploads WebSocket Freebox réutilisent désormais un client OkHttp et le même gestionnaire TLS, au lieu de recréer un pool et un dispatcher à chaque transfert. Les retries automatiques sont désactivés pour éviter de rejouer implicitement un upload.
- Les clients dédiés de fournisseurs IA n'ont pas été fusionnés dans cet audit : leurs durées longues, mécanismes de rotation et comportements de reprise doivent être évalués par fournisseur pour ne pas casser Groq, Gemini, OpenAI ou Hermès.

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


### Rotation concurrent des fournisseurs IA

- Les anneaux Groq/Gemini/OpenAI utilisent désormais un index de départ atomique : plusieurs agents qui sollicitent le même fournisseur en parallèle ne se marchent plus dessus lors de la sélection de la prochaine clé.
- Le relais Cloudflare utilise également un index atomique et une table de temporisation concurrente pour ses comptes.
- La sélection reste en rotation entre les clés disponibles ; les temporisations et les règles de bascule fournisseur restent inchangées. Aucun secret ni contenu de conversation n'est journalisé par cette évolution.


## Fiabilisation Android Auto : micro, demandes et musique

- Le bouton micro ne déclare plus un échec après seulement trois secondes lorsque le service vocal traite encore une demande. L'appui reste en attente et ouvre le micro dès que le traitement précédent libère le service.
- Le parcours utilisant directement le microphone Android Auto applique désormais le routage déterministe des commandes musicales avant d'appeler le modèle IA. Une demande de lecture ne doit pas se limiter à ouvrir une application.
- Les formulations « lance une musique » et « lance la musique » sont reconnues par le routeur média ; un test unitaire couvre explicitement la formulation avec « une ».
- La transcription voiture est bornée à 45 secondes et la réponse IA à 90 secondes afin qu'une requête distante lente ne garde pas indéfiniment la session de conversation.
- Les commandes de lecture continuent de cibler une session média externe et n'envoient pas de touche média générique susceptible d'être captée par la session JARVIS.
- Le lecteur `CarAudioRecord` reçoit maintenant des blocs de 512 octets maximum, conformément au contrat de l'API AndroidX Car ; les fragments sont réassemblés ensuite en trames PCM de 640 octets. Le focus audio doit être accordé avant d'ouvrir le micro voiture, et une perte de focus arrête l'enregistrement proprement avant le repli micro téléphone.
- À valider sur le véhicule : microphone intégré, commande vocale « lance une musique », reprise de lecture, bouton Play/Pause, puis une seconde demande après une réponse longue.
