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
.\gradlew.bat :app:testDebugUnitTest    # tests JVM purs (Endpoints, Eco)
```

L'APK release est produit à :

```
android/app/build/outputs/apk/release/app-release.apk
```

## Installer sur le téléphone (Android 5.0+)

1. Copier `app/build/outputs/apk/release/app-release.apk` sur le téléphone.
2. Activer "Sources inconnues" (Paramètres → Sécurité).
3. Ouvrir l'APK pour l'installer.

## Écrans (Phase 2)

- **LoginActivity** (launcher) : écran de connexion avec pseudo + mot de passe. Shell actuel
  : une session localisée est simulée (`SessionStore`) puis la liste des discussions s'ouvre.
  Le vrai appel `POST /api/auth/login` arrive en Phase 3.
- **ConversationsActivity** : liste des discussions + barre de navigation en bas
  (Chats / Amis / Recherche / Paramètres). Contenu factice tant qu'il n'y a pas de discussions.

## Réseau

```
Base URL : https://lightchat.kennethafantsawo.workers.dev
```

Définie dans `app/src/main/java/com/lightchat/net/Endpoints.java`. Les appels réseau
(`ApiClient`, `HttpURLConnection` : aucune bibliothèque tierce) sont branchés à partir de la
Phase 3.

## Signatures / keystore

- Keystore de release : `H:\duo app\keystore/lightchart.jks` — **à conserver précieusement**
  (il permet de rééditer l'application ; sans lui, les mises à jour exigent une nouvelle
  signature et perdront les données existantes).
- `android/keystore.properties` (contient `storeFile`, `storePassword`, `keyAlias`,
  `keyPassword`) et le `.jks` sont exclus du versionnage via `.gitignore` (racine + `android/`).
  Ne jamais commiter ces fichils.
