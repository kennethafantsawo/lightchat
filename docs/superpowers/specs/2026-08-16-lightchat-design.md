# LightChat — Cahier des charges (Design)

**Date :** 16 août 2026
**Version :** 1.0 (approuvé par l'utilisateur, section par section)

## Contexte

Application de messagerie personnelle pour 2 personnes (l'utilisateur et sa copine), pouvant accueillir plus de membres via invitations. L'application doit fonctionner parfaitement sur un téléphone **Android 5.0 (Lollipop), 1 cœur, 512 Mo de RAM** (modèle 2015), tout en profitant d'un téléphone plus puissant le jour où elle change d'appareil.

## Objectifs clés

- Fluide et léger sur matériel très faible (512 Mo RAM, 1 cœur).
- APK en **natif Java**, 64 bits (arm64-v8a) uniquement.
- Fonctionnalités similaires à Telegram : messages texte, émojis, stickers (format WhatsApp), photos, vidéos, messages vocaux, appels audio et vidéo.
- Preservation serveur : stocker le moins possible côté serveur ; la majorité des données reste en local.
- Récupération du compte et de l'historique après changement de téléphone.

## Décisions validées

| Sujet | Décision |
|---|---|
| Hébergement | **Cloudflare** — plan gratuit **sans carte bancaire** (Workers + Durable Objects + D1 + R2) |
| Langage serveur | **TypeScript/JavaScript** (Cloudflare Workers, serverless, 24/7) |
| Base serveur | **Cloudflare D1** (SQLite, 5 Go) — messages, comptes, amis, groupes |
| Médias | **Cloudflare R2** (10 Go) — photos/vidéos/audios, purge auto après 7 jours |
| Connexion temps réel | **Cloudflare WebSocket / Durable Objects** — push instantané et notifications |
| App Android | **Natif Java**, minSdk **21** (Android 5.0), cible arm64-v8a |
| Architecture réseau | **WebSocket** permanent entre chaque téléphone et le serveur |
| Appels | **WebRTC** — liaison directe téléphone ↔ téléphone (le serveur ne relaie pas l'audio/vidéo) |
| Conservation médias serveur | **7 jours**, suppression automatique quotidienne (nuit) |
| Conservation textes serveur | **Illimitée** (petits, permet la récupération après changement de téléphone) |
| Historique local | **Illimité**, jamais effacé |
| Récupération média après changement de tel | Auto si < 7 jours ; sinon fonction **« Sauvegarde »** manuelle (export vers carte SD/câble) |
| Notifications app fermée | Service **foreground** persistant + WebSocket, notifications écran verrouillé |
| Groupes | Oui — texte/médias émojis ; **appel uniquement 2 personnes** |
| Évolutivité matérielle | Détection des capacités du téléphone au démarrage → qualité vidéo et animations auto-ajustées |

## Section 1 — Architecture globale

```
   [Téléphone de ta copine]                        [Ton téléphone]
   Android 5.0, 1 cœur, 512 Mo                     Android quelconque
   Application « LightChat »                       Application « LightChat »
         │  connexion permanente (WebSocket)            │
         └──────────────┬───────────────────────────────┘
                        ▼
        ┌───────────────────────────────────────────┐
        │  SERVEUR (Cloudflare, gratuit, 24/7)      │
        │  - crée les comptes / amis / groupes      │
        │  - relaie les messages (texte, émojis,    │
        │    stickers, photos, vidéos, audios)      │
        │  - stocke les médias 7 jours puis jette   │
        │  - met en relation les appels (signalisation, pas de relais audio/vidéo) │
        └───────────────────────────────────────────┘
```

- Chaque message existe en 2 exemplaires : transitoire sur le serveur, **définitif en local** sur chaque téléphone.
- Les médias restent **7 jours max** sur le serveur puis sont effacés, sauf copie locale déjà téléchargée.
- Le WebSocket permanent permet la réception des messages et notifications même app fermée.

## Section 2 — Le serveur (Cloudflare Workers, TypeScript)

### Plateforme
- **Cloudflare Workers** (serverless) : le serveur est toujours allumé, gratuit, **sans carte bancaire**.
- **D1** : base SQLite gérée (comptes, amis, groupes, messages texte), 5 Go.
- **R2** : stockage objet (photos/vidéos/audios/stickers), 10 Go.
- **Durable Objects + WebSocket** : connexions temps réel, push instantané, notifications.
- **Cron** : tâche quotidienne qui supprime les médias ayant plus de 7 jours (R2 + D1).

### Comptes & identité
- Création : nom, prénom, âge, sexe, téléphone(s), email, pseudo.
- ID unique invisible généré par le serveur (fichier de données + identifiant chiffré).
- Seul le **pseudo** est public ; téléphone/email/âge/sexe jamais exposés aux autres.
- **Pseudo : unique et réservé** (pas de doublon).
- Mot de passe : haché (jamais en clair).

### Amis
- Recherche par pseudo → envoi d'invitation → notification à l'autre → accepter/refuser.
- Discussion possible **uniquement entre amis acceptés des deux côtés**.
- Etats d'invitation : envoyée / en attente / acceptée / refusée.

### Messages
- Table `messages` SQLite.
- Envoi : stockage transitoire + push instantané via WebSocket.
- Hors-ligne : le message reste en attente sur le serveur jusqu'à reconnexion.

### Médias
- Photos / vidéos / audios / stickers stockés dans un dossier séparé.
- **Suppression automatique après 7 jours** (tâche quotidienne nocturne).
- Pendant les 7 jours, le destinataire télécharge et garde en local.

### Groupes
- Création (nom + amis choisis), ajout de membres, retrait, droits admin pour l'auteur.
- Messages distribués à tous les membres.
- Appel vidéo de groupe **non** prévu à ce stade (limite matérielle).

### Appels
- Le serveur ne transporte pas l'audio/vidéo : il sert d'annuaire de signalisation (trouver les adresses des deux téléphones).
- Prévu : **mode relay de secours** si la connexion directe est bloquée (ex. 4G opérateur).

### Sécurité de base
- HTTPS/WSS (chiffrement du transport).
- Mots de passe hachés (bcrypt/argon2 côté serveur).
- Un pseudo = un compte.

## Section 3 — Application Android (native Java)

- Min SDK 21 (Android 5.0), cible arm64-v8a (64 bits).
- Interface épurée : pas d'animations superflues, pas de flous, pas d'effets lourds → fluidité.
- RAM maîtrisée : une seule connexion réseau, aucune tâche de fond gourmande.

### Écrans
1. **Accueil / connexion** — Se connecter / Créer un compte
2. **Liste des discussions** — badge non-lus, aperçu, heures (style Telegram/WhatsApp)
3. **Discussion** — bulles texte, émojis, stickers, photos, vidéos, messages vocaux ; indicateur "en train d'écrire" ; accusés de lecture
4. **Contacts / Amis** — recherche par pseudo, inviter, demandes en attente (accepter/refuser)
5. **Mon profil** — pseudo, avatar, infos privées modifiables
6. **Paramètres** — notifications, qualité vidéo, nettoyage de stockage, langue, déconnexion
7. **Appel audio** — haut-parleur, mains libres, micro coupé, raccrocher
8. **Appel vidéo** — grande image = interlocuteur, petit carré = moi ; boutons caméra inv./flip, vidéo on/off, micro, raccrocher

### Émojis & stickers
- Picker d'émojis intégré au clavier de discussion (comme Telegram).
- Support du **format sticker WhatsApp** (`*.webp` + métadonnées de paquet) pour installer des packs.

### Messages vocaux
- Enregistrement natif léger.
- Transcription texte : optionnel, à valider selon fluidité (consomme de la RAM).

### Stockage local
- Base locale SQLite pour les discussions → ouverture/scroll instantanés, y compris hors-ligne.
- Médias reçus → carte SD / mémoire (pas RAM éphémère).

### Notifications & arrière-plan
- Service foreground persistant (WebSocket) → messages & notifications écran verrouillé, faible empreinte mémoire.
- Notification d'invitation d'ami, de nouveau message, d'appel entrant.

## Section 3 bis — Récupération après changement de téléphone

- Textes / émojis / stickers / amis / groupes / profil : **stockés sur le serveur → restauration automatique** à la reconnexion sur le nouveau téléphone.
- Médias : gardés 7 jours sur le serveur → auto-récupérables pendant cette fenêtre ; au-delà, **fonction Sauvegarde** (export des médias vers carte SD / câble / disque, puis import sur le nouveau téléphone).

## Section 5 — Conception visuelle (maquettes Stitch)

Reproduire à l'identique dans l'app Android native.

### Palette de couleurs (Material 3 personnalisé)

**Clair**
| Rôle | Couleur |
|---|---|
| background | `#f8f9ff` |
| primary | `#3525cd` |
| on-primary | `#ffffff` |
| primary-container | `#4f46e5` |
| on-primary-container | `#dad7ff` |
| secondary | `#006c49` |
| secondary-container | `#6cf8bb` |
| on-secondary-container | `#00714d` (texte), `#005236` (variant) |
| error | `#ba1a1a` |
| on-surface | `#0b1c30` |
| on-surface-variant | `#464555` |
| surface | `#f8f9ff` |
| surface-variant | `#d3e4fe` |
| surface-container-low | `#eff4ff` |
| surface-container | `#e5eeff` |
| surface-container-high | `#dce9ff` |
| surface-container-highest | `#d3e4fe` |
| surface-container-lowest | `#ffffff` |
| surface-dim | `#cbdbf5` |
| outline | `#777587` |
| outline-variant | `#c7c4d8` |
| inverse-surface | `#213145` |
| inverse-primary | `#c3c0ff` |
| primary-fixed | `#e2dfff` |
| primary-fixed-dim | `#c3c0ff` |
| tertiary | `#684000` / `#653e00` |
| tertiary-container | `#885500` / `#ffb95f` |
| surface-tint | `#4d44e3` |

**Sombre**
| Rôle | Couleur |
|---|---|
| background | `#13121b` |
| primary | `#c3c0ff` |
| on-primary | `#1d00a5` |
| primary-container | `#4f46e5` |
| on-primary-container | `#dad7ff` |
| secondary | `#4edea3` |
| secondary-container | `#00b47d` |
| on-secondary-container | `#003e28` |
| surface | `#0b111a` |
| surface-container | `#1f1f28` |
| surface-container-low | `#1b1b24` |
| surface-container-high | `#23293b` |
| surface-container-highest | `#35343e` |
| surface-container-lowest | `#0e0d16` |
| on-surface | `#f8f9ff` |
| on-surface-variant | `#c7c4d8` |
| surface-variant | `#35343e` |
| inverse-surface | `#e4e1ee` |
| outline | `#334155` |
| outline-variant | `#464555` |
| error | `#ffb4ab` |
| error-container | `#93000a` |
| on-error-container | `#ffdad6` |

### Typographie — Inter
| Style | Taille | Poids | Usage |
|---|---|---|---|
| display-lg | 32 px | 700 | Titres page principale |
| headline-md | 20 px | 600 | Titres d'écran |
| headline-md-mobile | 18 px | 600 | Titres mobile / top bar |
| body-lg | 16 px | 400 | Corps de texte / bulles |
| body-sm | 14 px | 400 | Sous-textes |
| label-caps | 12 px | 600, letterspacing 0,05em | Libellés de navigation |
| timestamp | 11 px | 400 | Horodatage |

### Composants / patterns des maquettes (à reproduire)
- **TopAppBar** : menu (gauche), titre « Messenger/LightChat », recherche (droite). Fond surface avec léger blur (allégé sur tel faible).
- **BottomNav** : onglets Chats / Friends / Search / Settings ; onglet actif rempli (primary-container clair / primary sombre) avec pastille circulaire.
- **FAB** : bouton flottant rondeau (icône `chat_bubble`) pour nouvelle discussion.
- **Liste des discussions** : avatar rond 48 px, pastille en ligne verte 12 px, titre, aperçu tronqué, heure, badge non-lus primaire 20 px, indicateurs "en train d'écrire" (3 points animés).
- **Bulles de discussion** : reçues = surface, envoyées = primary (clair) ; coins arrondis (rounded 16, coin dédié légèrement aplati : `rounded-bl-sm` reçu / `rounded-br-sm` envoyé) ; avatar visible ; séparateur de date centré (pastille surface-variant) ; image avec gradient sombre en bas + heure ; indicateur de frappe 3 points.
- **Barre de saisie** : bouton « + » (add_circle) gauche, clipboard émojis (sentiment_satisfied), textarea, bouton sticky de stickers (optionnel), mini microphone qui devient « envoyer » (send) quand du texte est saisi.
- **Écran compte/paramètres** : avatar 64 px, nom, ID (ex. `8492-AXN-11`), sections « Personal Info » (privé, icône cadenas) et « General » (Privacy, Notifications, Data & Storage, Language, Help), bouton « Log Out » en cartouche erreur.
- **Recherche d'amis** : champ recherche rond, cartes de résultats (avatar + pseudo + nom, bouton « Inviter »), section « Invitations en attente » avec cases Accepter (vert) / Refuser (gris).
- **Écran d'appel audio** : avatar circulaire 160 px avec anneaux de pulsation, nom, durée, contrôles (haut-parleur, micro coupé, clavier, raccrocher rouge).
- **Écran d'appel vidéo** : flux plein écran (interlocuteur), ruban d'infos en haut (nom, durée), petit carré en haut à droite (aperçu caméra moi), barre de contrôles en bas (flip caméra, vidéo on/off, micro, raccrocher).

### Thème sombre
- Détecté automatiquement selon le téléphone ; les écrans d'appel et de discussion s'affichent correctement dans les deux modes.
- Maquettes fournies en version claire ET sombre → les deux seront implémentées.

### Adaptations matériel faible (tel 2015)
- Suppression/aération des `backdrop-blur`, animations et gradients superflus en mode « Économique ».
- Mode « Standard » (téléphone récent) : animations + dégradés rétablis.
- Qualité vidéo d'appel : auto (Économique / Standard) selon CPU cœurs/RAM.

## Écrans de droite (détails finaux)
1. Accueil/connexion : après cette décision, pas d'écran de bienvenue supplémentaire — connexion ou création de compte directement.

## Erreurs & cas limites
- Hors-ligne : le message est mis en file d'attente localement et envoyé à la reconnexion.
- Médias expirés (> 7 j) : l'app affiche « média expiré » → proposer « Sauvegarde » si conservé localement.
- Appel entrant pendant un appel déjà en cours : nouvelle sonnerie ignorée (occupé).
- Connexion serveur perdue : reconnexion automatique avec backoff.

## Tests (canaux de validation)
- Tests serveur : `wrangler` + tests Cloudflare (création compte, ami, message, groupe, expiration médias).
- Tests app : émulateur Android 5.0 (armeabi+arm64) + installation manuelle sur le téléphone 2015 réel.
- Version d'essai sur le téléphone réel pour valider la fluidité (RAM, CPU).
- Vérification du passage du flux réseau sur Wi-Fi puis 4G.

## Étapes de construction (ordre recommandé)
1. Serveur Cloudflare (comptes, amis, WebSocket, groupes, D1, R2) + tests.
2. App Android native : squelette, connection, listes, discussion texte.
3. Médias (photo/vidéo/audio), stickers, émojis, messages vocaux.
4. Notifications & service foreground.
5. Appels WebRTC (audio puis vidéo) + relay de secours.
6. Récupération après changement de téléphone + fonction Sauvegarde.
7. Peaufinage visuel (thème clair/sombre), performance, déploiement.

## Contraintes & limites assumées
- Qualité vidéo en appel dépend du matériel (basse sur tel 2015 ; bonne sur tel récent).
- Appel vidéo uniquement 2 personnes.
- Stickers : format WhatsApp pris en charge ; les packs réels nécessitent des fichiers `.webp` légaux/obtenus par l'utilisateur.
- Le compte Cloudflare Gratuit ne demande **aucune carte bancaire** (répond à l'absence de carte de l'utilisateur).
- Les limites du plan gratuit Cloudflare : D1 5 Go, R2 10 Go, suffisant pour 2 personnes.