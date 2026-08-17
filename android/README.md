# LightChat — App Android

Application Android native Java pour LightChat (messenger personnel). Shell UI minimal
conforme au cahier des charges (palette Material 3 clair/sombre, minSdk 21 / Android 5.0,
cible téléphone faible puissance : 1 cœur, 512 Mo RAM). 100 % framework Android — aucune
dépendance AndroidX.

## Prérequis

- JDK 21 (Temurin)
- Android SDK (platforms android-36, build-tools 36.1.0)
- Gradle est fourni via le wrapper (`gradlew`); aucune installation système n'est requise.

## Build

Depuis la racine Gradle `android/` :

```powershell
cd android
.\gradlew.bat :app:assembleRelease      # APK signée (release)
.\gradlew.bat :app:testDebugUnitTest    # tests JVM purs (Json, modèles, Endpoints, Eco)
```

L'APK release est produit à :

```
android/app/build/outputs/apk/release/app-release.apk
```

## Installer sur le téléphone (Android 5.0+)

1. Copier `app/build/outputs/apk/release/app-release.apk` sur le téléphone.
2. Activer "Sources inconnues" (Paramètres → Sécurité).
3. Ouvrir l'APK pour l'installer.

## Écrans (Phase 3)

- **LoginActivity** (launcher) : connexion **réelle** — `POST /api/auth/login`, ou création de
  compte via le lien « Créer un compte » (`POST /api/auth/register` : pseudo, mot de passe,
  prénom, nom, âge, sexe). La session (token) est persistée localement (`SessionStore`,
  `SharedPreferences`).
- **ConversationsActivity** : liste **réelle** des discussions (`GET /api/conversations`),
  libellés des DM résolus via `GET /api/friends` (nom prénom/nom, avatar couleur). Un appui
  ouvre la discussion. Bouton « Déconnexion » en en-tête.
- **DiscussionActivity** : conversation en direct — messages chargés par
  `GET /api/messages?conv_id=`, **polling 2 s** tant que l'écran est visible, envoi de texte
  par `POST /api/send`. Bulles : les miennes à droite (fond primaire), celles de l'autre à
  gauche, messages « system » centrés.
- Les onglets Amis / Recherche / Paramètres restent des coquilles (Phases 5–6).

## Réseau

```
Base URL : https://lightchat.kennethafantsawo.workers.dev
```

Définie dans `app/src/main/java/com/lightchat/net/Endpoints.java`. Appels réseau par
`ApiClient` (`HttpURLConnection` : aucune bibliothèque tierce), exécutés hors du thread UI via
`util/Async`. Écrans Phase 3 connectés en production dès le déploiement du Worker
(`server/`, voir `docs/`).

## Architecture (Phase 3)

- `util/Json` : parseur JSON pur JDK (objet/tableau/string+échappements/numériques/booleans/null)
  — testé en JVM pure.
- `models/` : `Conversation` et `Message` (lecture snake_case via `fromJson`).
- `net/` : `Endpoints`, `ApiClient` (HTTP), tests Endpoints + Eco.
- `ui/` : `LoginActivity`, `ConversationsActivity`, `DiscussionActivity` — 15 tests JVM.

## Signatures / keystore

- Keystore de release : `H:\duo app\keystore/lightchart.jks` — **à conserver précieusement**
  (il permet de rééditer l'application ; sans lui, les mises à jour exigent une nouvelle
  signature et perdront les données existantes).
- `android/keystore.properties` (contient `storeFile`, `storePassword`, `keyAlias`,
  `keyPassword`) et le `.jks` sont exclus du versionnage via `.gitignore` (racine + `android/`).
  Ne jamais commiter ces fichils.
