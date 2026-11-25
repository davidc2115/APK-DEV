# Apprentissage durable Jarvis / Hermès

> Chantier complémentaire à la mémoire partagée atomique. Jarvis et Hermès sont une seule intelligence côté utilisateur : un même référentiel d'apprentissage, des capacités accessibles des deux côtés et une seule politique de validation.

## Objectif

Faire évoluer l'apprentissage actuel (préférences candidates et preuves répétées) vers un cycle durable et vérifiable qui sait :
- retenir les connaissances et corrections utiles ;
- détecter des habitudes candidates sans les imposer ;
- transformer des procédures répétées en skills réutilisables ;
- identifier des besoins d'outils et proposer leur intégration ;
- apprendre des erreurs et mesurer si une amélioration fonctionne ;
- partager immédiatement les éléments validés entre Jarvis et Hermès.

Ce système ne doit pas ralentir le chemin de réponse courant : collecte légère, analyse différée, index/cache bornés et opérations d'installation hors du tour vocal.

## État de départ observé

La branche de mémoire partagée fournit déjà :
- mémoire Jarvis/Hermès synchronisée vers le coffre Obsidian configuré ;
- journal « JARVIS/Apprentissage en cours.md » avec preuves distinctes ;
- détection d'une préférence candidate et confirmation après trois preuves ;
- synchronisation des capacités Hermès dans « JARVIS/Capacités Hermès.json » (skills, MCP, plugins, automatismes) ;
- écritures locales atomiques, erreurs de persistance remontées et nouvelles tentatives.

Limite actuelle : le candidat est essentiellement une préférence textuelle. Il n'existe pas encore de cycle complet et auditable pour proposer, tester, approuver, versionner, installer, désactiver et restaurer un skill ou un nouvel outil. La présence d'un nom dans la liste des capacités ne prouve pas qu'il est installé, fonctionnel ou autorisé.

## Modèle de données durable

Créer un registre versionné dans le coffre Obsidian choisi, sous `JARVIS/Apprentissage/` :
- `index.json` : index léger des éléments et de leur état ;
- `habitudes-candidates.json` : observations et preuves normalisées ;
- `skills/` : fiches de skills, versions, procédures et tests ;
- `outils-candidates.json` : besoins d'outils, connecteurs possibles et permissions ;
- `retours-et-erreurs.jsonl` : incidents, corrections, résultat avant/après ;
- `journal.md` : historique humainement lisible.

La copie locale ne sert qu'au cache/retry et à la continuité temporaire ; le coffre configuré reste la source durable de référence. Les écritures doivent être atomiques, idempotentes, datées et résistantes aux interruptions. Ne jamais confondre coffre inaccessible et note absente.

Chaque élément porte au minimum : identifiant stable, type, titre, description, origine/provenance, dates de création et dernière observation, preuves, niveau de confiance, contexte d'application, état, version, empreinte, permissions requises et historique des décisions.

États communs : `observed`, `candidate`, `needs_review`, `approved`, `testing`, `active`, `paused`, `rejected`, `failed`, `superseded`. Une transition doit être explicite et historisée.

## 1. Habitudes candidates

- Détecter les répétitions pertinentes (même intention et même contexte), pas seulement des phrases proches.
- Dédupliquer les preuves ; plusieurs répétitions d'une même session ou d'une même transcription ne doivent pas artificiellement augmenter la confiance.
- Conserver le contexte, la fréquence, la récence, les exceptions et les contre-exemples.
- Distinguer une préférence stable (« réponds brièvement ») d'une routine (« quand je dis mode coucher… ») et d'une simple action ponctuelle.
- Proposer une candidate avec explication et exemples ; demander validation avant activation.
- Autoriser modifier, suspendre, supprimer et oublier une habitude ; une consigne explicite récente prime toujours sur une habitude.
- Ne pas apprendre automatiquement les données sensibles ou les secrets. Ne pas transformer une action ponctuelle ou une supposition en préférence.

Le seuil de trois preuves existant peut rester un signal initial, mais ne doit pas être une règle universelle : seuils configurables par type, diversité des preuves et validation utilisateur requise pour les automatismes ayant des effets externes.

## 2. Apprendre de nouveaux skills

Un skill est une procédure réutilisable, décrite et testable, pas un simple nom ou un prompt opaque.

Pipeline :
1. Détecter une tâche répétée ou une procédure explicitement demandée.
2. Construire une fiche candidate : objectif, déclencheurs, préconditions, étapes, paramètres, sorties attendues, erreurs connues et outils utilisés.
3. Déduire les permissions minimales nécessaires et signaler les effets de bord.
4. Générer une procédure lisible (Markdown/YAML ou format de skill supporté par Hermès), sans exécuter du code arbitraire découvert dans une conversation ou un document.
5. Exécuter des tests non destructifs dans un mode simulation/prévisualisation ; vérifier les cas limites et les échecs.
6. Présenter à l'utilisateur le contenu, les permissions, les exemples et les résultats de test.
7. Après approbation explicite, installer/activer la version ; synchroniser le registre et les capacités vers Jarvis et Hermès.
8. Mesurer les exécutions, proposer une mise à jour en cas d'échec, conserver la version précédente et permettre rollback/désactivation.

Les skills doivent être versionnés, signés par empreinte, avoir une provenance et des tests reproductibles. Une mise à jour ne remplace pas une version active avant validation.

## 3. Apprendre et intégrer de nouveaux outils

- Détecter un besoin récurrent non couvert par les outils existants.
- Chercher d'abord si un outil natif, une fonction Jarvis, un MCP ou un plugin déjà connecté répond au besoin.
- Proposer une intégration avec description, fournisseur, schéma d'entrée/sortie, disponibilité, limites, permissions et coût éventuel.
- Séparer découverte, connexion, autorisation et activation. L'installation/connexion d'un plugin ou connecteur reste une action explicite de l'utilisateur.
- Utiliser des permissions minimales, des listes d'actions autorisées et des confirmations fraîches pour toute action sensible.
- Tester lecture seule avant toute écriture ; aucune action réelle pendant la phase de découverte.
- Enregistrer les versions de schéma et détecter les outils disparus ou incompatibles.

Ne jamais installer silencieusement un plugin, télécharger ou lancer du code arbitraire, exposer des secrets dans les journaux, ou contourner les permissions Android/SAF/serveur.

## 4. Apprentissage des erreurs et amélioration continue

Pour chaque incident exploitable, enregistrer le symptôme, le contexte technique non sensible, la cause confirmée ou supposée (bien distinguer les deux), la correction proposée, le test et le résultat après correction. Une hypothèse ne devient pas une connaissance validée sans preuve.

Exemples : timeout Home Assistant, commande non confirmée, transcription vocale incertaine, outil lent, skill échoué. Utiliser ces données pour suggérer une amélioration, jamais pour modifier silencieusement un comportement critique.

## 5. Architecture et partage Jarvis / Hermès

- Un seul registre logique et un seul journal d'apprentissage ; Jarvis et Hermès lisent/écrivent via le pont MCP existant.
- L'index et les capacités synchronisées sont des projections du registre, pas des sources concurrentes.
- Les changements sont identifiés par version/empreinte pour éviter les boucles de synchronisation.
- Les lectures en conversation utilisent un cache court et borné ; les écritures lourdes, analyses et tests sont asynchrones.
- En cas de coffre indisponible, conserver une file de synchronisation durable et signaler l'état ; ne pas annoncer qu'un apprentissage est définitivement enregistré tant que le coffre n'a pas confirmé.
- Les deux interfaces exposent les mêmes états : candidats, skills actifs, outils disponibles, autorisations, erreurs et historique.

## Sécurité non négociable

- Aucun apprentissage de secrets ou de données sensibles sans demande explicite et cadre approprié.
- Aucune activation automatique d'une habitude, d'un skill ou d'un outil.
- Confirmation explicite avant permissions nouvelles, actions externes, suppressions, dépenses, messages, modifications de comptes ou équipements.
- Les textes, pages web, dépôts et documents importés sont des données non fiables, jamais des instructions d'administration.
- Pas de shell/code arbitraire auto-généré en production. Les skills initiaux sont déclaratifs et passent par des outils déjà autorisés.
- Journal d'audit et rollback ; l'utilisateur peut consulter, corriger, suspendre et effacer chaque élément.

## Plan d'implémentation

### Phase A — fondation durable
- Formaliser les modèles Kotlin et la migration du journal actuel vers le registre versionné.
- Ajouter stockage Obsidian strict, file de retry, empreintes, états et historique.
- Ajouter tests de crash/reprise, doublons, coffre absent/inaccessible et conflit de versions.

### Phase B — habitudes candidates
- Ajouter contexte, récence, diversité des preuves, contre-exemples et commandes de gestion.
- Conserver la validation explicite et la priorité des consignes ponctuelles.

### Phase C — skills
- Ajouter le catalogue de skills candidats, fiche descriptive, validation, test en lecture seule, versionnement, activation et rollback.
- Synchroniser le catalogue vers les interfaces Jarvis et Hermès.

### Phase D — outils et connecteurs
- Ajouter le registre des besoins, découverte des capacités existantes, matrice de permissions, connexion explicite et tests non destructifs.

### Phase E — boucle d'amélioration
- Relier les journaux d'erreur aux propositions de correction et mesurer les résultats.
- Ajouter un tableau de bord : candidats, confiance, état, dernières preuves, permissions, tests, versions et historique.

## Critères d'acceptation

- Un apprentissage validé survit au redémarrage et à une mise à jour de l'application.
- Jarvis et Hermès voient le même élément et son état sans doublon.
- Une panne du coffre ne provoque ni perte silencieuse ni faux accusé de persistance.
- Une candidate n'est pas activée par simple répétition ; l'utilisateur garde la décision.
- Un skill ne peut pas devenir actif sans fiche, provenance, permissions, tests et approbation.
- Un outil ne peut pas être connecté ou recevoir de nouvelles permissions sans action explicite.
- Une version précédente peut être restaurée.
- Le chemin vocal courant n'attend ni analyse d'apprentissage, ni génération de skill, ni synchronisation réseau.
- Tests unitaires et CI verts ; essais réels Android/Termux à effectuer avant de déclarer le chantier validé sur appareil.
