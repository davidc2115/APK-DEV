# Worker Cloudflare — animation d'image Wan 2.2

Ce Worker permet à Jarvis d'envoyer une image à un Space Hugging Face public Wan 2.2, puis de récupérer le MP4. Il ne contient aucune clé Hugging Face : le calcul est effectué sur le GPU partagé du Space communautaire.

## Déploiement

Prérequis : un compte Cloudflare et Node.js installé sur l'ordinateur utilisé pour le déploiement.

Depuis ce dossier :

```bash
npx wrangler@latest login
npx wrangler@latest secret put JARVIS_TOKEN
npx wrangler@latest deploy
```

Lors de la commande `secret put`, colle un jeton aléatoire long (au moins 32 octets aléatoires). Garde-le privé : ne le place jamais dans GitHub. Cloudflare le conserve comme secret chiffré.

Après le déploiement, Cloudflare affiche une URL du type :

`https://jarvis-image-to-video.<ton-sous-domaine>.workers.dev`

Dans Jarvis, ouvre **Réglages → Vidéo IA**, puis renseigne :
- **URL du Worker** : l'URL ci-dessus, sans `/generate`.
- **Jeton privé du Worker** : exactement le même jeton que celui saisi dans `wrangler secret put`.

Le jeton est conservé dans les préférences chiffrées de Jarvis (EncryptedSharedPreferences).

## Utilisation

Dans Jarvis, demande par exemple :

`Anime la dernière image : le phénix déploie lentement ses ailes, les plumes bougent avec le vent, caméra cinématique, sans texte.`

Ou précise le nom de fichier exact d'une image déjà générée.

Le clip est limité à environ 4,5 secondes et 8 Mo pour l'image source. Le GPU du Space Hugging Face est public et partagé : file d'attente, démarrage à froid, quota ou indisponibilité possibles. « Gratuit » ne signifie donc ni illimité ni garanti.

## Sécurité et limites

- Le Worker exige le secret `JARVIS_TOKEN` dans l'en-tête Authorization.
- Ne publie jamais le jeton dans le dépôt ou dans une capture d'écran.
- Le jeton intégré aux réglages de l'APK peut être extrait par une personne ayant accès à l'appareil ou à l'APK : c'est une protection pratique contre les appels opportunistes, pas un secret inviolable.
- Ajoute une règle de limitation de débit Cloudflare sur la route `/generate` si le Worker est exposé à d'autres personnes.
- Le service amont est un Space communautaire Hugging Face, pas un service garanti. Il peut changer, être suspendu ou fermer.
