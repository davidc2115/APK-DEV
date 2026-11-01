# Projet Jarvis — Orbe Obsidienne
## Phase 2 — Architecture technique et modèle de données

**Statut :** conception pour guider l'implémentation. Aucun moteur 3D ni changement de stockage n'est ajouté dans cette phase.

## 1. Principes non négociables
- L'orbe représente les données réellement présentes sur l'appareil.
- Aucun nœud, dossier, lien, projet ou agent fictif n'est créé pour décorer le graphe.
- Les sources actuelles restent les sources de vérité : coffre Obsidian/Markdown, fichiers locaux, historique de conversations et journaux d'agents lorsqu'ils sont effectivement accessibles.
- Le graphe est un index dérivé et reconstructible. Sa perte ne doit jamais faire perdre les notes ou fichiers originaux.
- Fonctionnement local et hors-ligne en priorité ; aucune dépendance à un serveur ou service cloud pour afficher et parcourir le graphe.
- Les fonctions existantes de Jarvis, ses permissions Android et ses autres onglets sont conservés.

## 2. Architecture cible
GraphScreen devient un écran hôte. Il ne calcule plus lui-même la structure et la géométrie.

| Module | Responsabilité |
|---|---|
| KnowledgeRepository | Point d'entrée unique pour charger, rechercher et actualiser les données |
| KnowledgeSource | Adaptateur par source réelle : coffre Markdown, fichiers, conversations, mémoire, journaux d'agents |
| GraphIndexer | Parcourt les sources, extrait les métadonnées et construit les relations explicites |
| GraphDatabase | Index local Room : nœuds, relations, état d'indexation et dates |
| GraphRelationResolver | Résout liens, mentions et références ; distingue liens confirmés et suggestions |
| OrbScene | Rendu 3D indépendant des données, caméra, matériaux, éclairage et animations |
| GraphInteractionController | Sélection, rotation, zoom, navigation, filtres et focus |
| GraphDetailsPanel | Fiche contextuelle du nœud sélectionné et actions adaptées à son type |

Flux : Sources réelles → Indexeur → Repository / Room → Contrôleur d'interaction → OrbScene.
Le moteur 3D ne lit jamais directement les fichiers et ne contient aucune règle métier.

## 3. Modèle de données
### Nœud
Chaque élément réel indexé possède :
- id : identifiant stable, déterministe autant que possible à partir de la source et du chemin/identifiant d'origine ;
- type : NOTE, FILE, PROJECT, CONVERSATION, MEMORY ou AGENT ;
- title et description : libellé et résumé disponibles ;
- sourceId et sourcePath : origine exacte permettant de retrouver l'élément ;
- mimeType : si applicable ;
- createdAt, updatedAt, indexedAt : dates connues, sans inventer celles qui manquent ;
- metadataJson : métadonnées complémentaires propres à la source ;
- availability : PRESENT, MISSING ou UNRESOLVED.
Un type n'est affiché que lorsqu'un adaptateur sait réellement fournir ses données. Les types prévus ne signifient pas que toutes ces sources sont déjà intégrées.

### Relation
Chaque relation conserve son sens :
- id, fromNodeId, toNodeId ;
- type : WIKILINK, IN_FOLDER, ATTACHMENT_OF, BELONGS_TO_PROJECT, MENTION, CONVERSATION_REFERENCE, AGENT_ACTIVITY ;
- origin : EXPLICIT ou SUGGESTED ;
- confidence : facultatif, réservé aux relations proposées par analyse ;
- sourcePath et updatedAt.
Les liens [[wikilinks]] sont directionnels. Deux liens de sens opposés sont deux relations distinctes. Un lien vers une cible absente est enregistré comme référence non résolue ; il ne doit pas être présenté comme une note existante.
Les relations suggérées par une IA restent distinctes des liens explicites et ne deviennent jamais des faits sans validation.

## 4. Sources et ordre d'intégration
1. Notes Markdown / coffre Obsidian : première source, déjà lue par ObsidianWriter. Indexer chemin, titre, contenu utile et wikilinks.
2. Fichiers locaux : inventorier les emplacements réellement gérés par Jarvis et les pièces jointes accessibles. Ne pas scanner arbitrairement tout le stockage Android.
3. Mémoire Jarvis : connecter les fichiers Markdown réellement utilisés comme mémoire, sans les dupliquer ni modifier leur contenu.
4. Historique de conversations : utiliser les fichiers JSON et leur format réel après audit de leur schéma ; indexer uniquement les conversations et pièces jointes existantes.
5. Agents : relier les agents et leurs activités aux journaux WorkManager réellement enregistrés. Ne pas créer un nœud pour un agent simplement envisagé.
6. Projets : déduire un projet uniquement d'une source explicite (métadonnée, dossier configuré ou relation validée), pas d'une supposition basée sur le nom d'un fichier.
Chaque source expose un état : disponible, autorisation manquante, erreur de lecture ou non intégrée.

## 5. Stockage et synchronisation
Room sert d'index local rapide, pas de remplacement immédiat du coffre Markdown ou des fichiers JSON.
- Indexation initiale en tâche de fond, avec progression et reprise.
- Actualisation incrémentale quand une source change ; réindexation complète possible à la demande.
- Conservation de l'identifiant et du chemin source pour ouvrir le vrai élément.
- La suppression d'un élément de la source ne doit jamais supprimer le fichier source depuis le graphe sans action explicite.
- Si le coffre externe n'est pas autorisé ou indisponible, afficher son état et conserver l'index local déjà connu sans prétendre qu'il est à jour.
- La base peut être reconstruite depuis les sources ; aucune donnée utilisateur unique ne doit exister uniquement dans Room.

## 6. Rendu de l'Orbe Obsidienne
Le rendu 3D est un module remplaçable. La piste retenue est SceneView / Filament pour l'intégration Android et Compose, sous réserve de vérifier la version et les API exactes avant ajout Gradle.
- Sphère d'obsidienne noire, matériau minéral brillant, veines et reflets violets.
- Noyau lumineux central représentant l'activité de Jarvis.
- Nœuds et connexions disposés en profondeur autour de la sphère, avec hiérarchie de tailles basée sur des données réelles.
- Particules et pulsations sobres, limitées pour préserver batterie et fluidité.
- Caméra tactile : rotation au doigt, zoom pincement, recentrage.
- Sélection par hit-test : nœud sélectionné net, relations proches mises en évidence, reste atténué.
- Panneau contextuel Compose indépendant de la scène 3D.
Les étiquettes ne sont pas toutes affichées en permanence. Elles apparaissent au zoom utile, à la sélection, ou dans le panneau contextuel.

## 7. Expérience utilisateur
L'écran reste centré sur la sphère, sans faux menu de catégories ni tableau de bord inventé. Les commandes visibles restent minimales : recherche, filtres réels, recentrage et accès à la fiche sélectionnée.
Les filtres sont construits à partir des types et sources réellement présents. Le graphe n'impose pas de catégories fixes comme Société, Jarvis ou Racine si elles ne correspondent pas aux données réelles sélectionnées.
La navigation principale actuelle de Jarvis est conservée dans un premier temps. Le mode immersif (masquage éventuel de la barre inférieure) sera traité lors de l'intégration UI, sans supprimer les autres onglets.

## 8. Performance et sécurité
- Pas de scan massif sur le thread UI.
- Limites de lecture configurables et indexation paginée.
- Réduction du nombre de labels, particules et liens dessinés selon le niveau de zoom.
- Pause des animations quand l'écran est masqué ou l'application en arrière-plan.
- Mode qualité réduite si économie d'énergie ou performances faibles.
- Aucune permission Android élargie sans nécessité et explication.
- Les chemins sensibles et contenus de fichiers ne sont pas envoyés à un service distant.

## 9. Écart constaté dans l'implémentation actuelle
- GraphScreen.kt dessine aujourd'hui un graphe 2D avec Compose Canvas.
- ObsidianWriter.graph() construit les nœuds depuis les notes Markdown et extrait les [[wikilinks]].
- Le graphe actuel déduplique les relations sans conserver leur direction et crée des nœuds fantômes pour les cibles absentes. Le nouveau modèle conservera le sens et distinguera les références non résolues des éléments existants.
- Le graphe actuel est limité à 400 notes par défaut et 60 000 octets lus par note.
- ObsidianWriter utilise le Storage Access Framework via DocumentFile pour le coffre choisi, avec un repli dans le stockage propre à l'application.
- Le projet est en Kotlin / Jetpack Compose, compileSdk 34, minSdk 26, Java 17. Aucune dépendance Room ou moteur 3D n'est présente dans le fichier Gradle examiné.
- La navigation générale est gérée dans MainActivity.kt, indépendamment de GraphScreen.

## 10. Phases d'implémentation
| Phase | Livrable | Validation attendue |
|---|---|---|
| 1 — Audit | Inventaire des sources, écrans, stockage et limites | Réalisée |
| 2 — Architecture | Présent document, modèle et responsabilités des modules | Réalisée |
| 3 — Index local | Entités, DAO, migration et indexeur initial Markdown | Aucune perte ; index reproductible |
| 4 — Graphe métier | Relations directionnelles, références non résolues, recherche et sélection | Vérification sur vraies notes |
| 5 — Prototype 3D | Dépendance et scène sphérique isolée | Rotation, zoom, rendu stable |
| 6 — Connexion données / 3D | Nœuds réels, liens, sélection et fiche | Aucune donnée décorative |
| 7 — Sources complémentaires | Fichiers, mémoire, conversations, agents | Chaque source testée séparément |
| 8 — Optimisation | Batterie, mémoire, fluidité, reprise et erreurs | Tests sur appareil Android |

**Règle de livraison :** une phase est annoncée terminée uniquement lorsque ses fichiers, ses changements et son résultat de compilation ou de test sont vérifiés. Aucune compilation n'est déclarée réussie sur la seule base d'un commit GitHub.