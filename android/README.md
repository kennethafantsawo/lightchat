# LightChat — App Android

Application Android native Java pour LightChat (messenger personnel). UI conforme au cahier
des charges (palette Material 3 clair/sombre, minSdk 21 / Android 5.0, cible téléphone faible
puissance : 1 cœur, 512 Mo RAM). **100 % framework Android — aucune dépendance AndroidX**
(pas d'okhttp, pas de lib JSON ni WebSocket : tout est pur JDK / framework).

## Prérequis

- JDK 21 (Temurin)
- Android SDK (platforms android-36, build-tools 36.1.0)
- Gradle fourni via le wrapper (`gradlew`) — aucune installation système requise.

## Build

Depuis la racine Gradle `android/` :

```powershell
.\gradlew.bat :app:assembleRelease      # APK signée (release)
.\gradlew.bat :app:testDebugUnitTest    # tests JVM purs (Json, modèles, Endpoints, Eco, RawWs)
.\gradlew.bat :app:lintDebug            # analyse statique
```

L'APK release est produite à :

```
android/app/build/outputs/apk/release/app-release.apk
```

## Installer sur le téléphone (Android 5.0+)

1. Copier `app/build/outputs/apk/release/app-release.apk` sur le téléphone.
2. Activer « Sources inconnues » (Paramètres → Sécurité).
3. Ouvrir l'APK pour l'installer.

## Fonctionnalités (Phases 3 → 8)

- **Auth réelle** (`LoginActivity`) : connexion `POST /api/auth/login`, création de compte
  `POST /api/auth/register` (pseudo, mot de passe, prénom, nom, âge, sexe). Session persistée
  localement (`SessionStore`).
- **Chats** (`ConversationsActivity`) : liste des discussions `GET /api/conversations`,
  libellés/avatars DM via `GET /api/friends`, mise à jour instantanée sur push du WebSocket.
- **Amis** : demandes en attente (Accepter/Refuser via `/api/friends/respond`), liste d'amis,
  bouton « Parler » (ouvre/creuse un DM).
- **Recherche** : `GET /api/users/search?q=` + invitation (`POST /api/friends/request`) avec
  états « Déjà amis » / « Envoyé ».
- **Discussion temps réel** (`DiscussionActivity`) : `GET /api/messages`, envoi `POST /api/send`,
  réception instantanée sur `Realtime` (WebSocket `/api/ws`), repli polling 10 s (2 s hors socket).
  **Médias** : photo & vidéo depuis la galerie (`/api/upload` + `/api/send`, cache local),
  messages vocaux (enregistrement `MediaRecorder` AMR/3gp, lecture `MediaPlayer`), stickers
  affichés, rangée d'émojis insérés au curseur.
- **Notifications & service** (`RealtimeService`) : service **foreground** qui garde la
  connexion temps réel active et affiche une notification par conversation quand l'app est en
  arrière-plan (canaux Android 8+ ; permission `POST_NOTIFICATIONS` demandée sur Android 13+).
- **Paramètres** : pseudo + ID, **sauvegarde des médias en cache vers Downloads/`LightChat`**
  (carte SD), vidage du cache.

## Réseau

```
Base URL : https://lightchat.kennethafantsawo.workers.dev
WebSocket: wss://lightchat.kennethafantsawo.workers.dev/api/ws
```

Définies dans `net/Endpoints.java` et `net/Realtime.java`. HTTP par `ApiClient`
(`HttpURLConnection`, aucune bibliothèque tierce), exécuté hors UI thread via `util/Async`.
WebSocket RFC 6455 implémenté en pur JDK (`ws/RawWs` + TLS/SNI `ws/WsClient`), reconnexion
automatique avec backoff 1 → 30 s, ping 30 s.

## Architecture

- `util/` : `Json` (parseur pur JDK), `Fmt` (dates), `MediaStore` (cache médias local),
  `Async` (threads + callback UI).
- `models/` : `Conversation`, `Message` (snake_case via `fromJson` ; `media_key`, `mime`,
  `duration_ms`).
- `net/` : `Endpoints`, `ApiClient` (HTTP + upload/download binaires), `Realtime` (websocket),
  `RealtimeService` (foreground + notifications).
- `ws/` : `RawWs` (RFC 6455), `WsClient` (TLS) — `RawWsTest` couvre les vecteurs RFC.
- `ui/` : `LoginActivity`, `ConversationsActivity` (onglets Chats/Amis/Recherche/Paramètres),
  `DiscussionActivity` (bulles texte/photo/vidéo/audio, enregistrement), `VideoPlayerActivity`.
- Tests JVM : 25 (Json, modèles, Endpoints, Eco, `ws/RawWsTest`).

## Signatures / keystore

- Keystore de release : `H:\duo app\keystore/lightchart.jks` — **à conserver précieusement**
  (sans lui, une mise à jour exigerait une nouvelle signature et perdrait les données).
- `android/keystore.properties` (storeFile/storePassword/keyAlias/keyPassword) et le `.jks`
  sont exclus du versionnage (`.gitignore`). Ne jamais committer ces fichiers.