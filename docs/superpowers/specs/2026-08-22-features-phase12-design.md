# LightChat — Spécification Fonctionnelle Phases 1 & 2

**Date** : 2026-08-22
**Branche** : `server-phase1`
**Objectif** : Ajouter la recherche de messages, les paramètres de confidentialité, le blocage/signalement (Phase 1), puis les GIF/stickers et la synchronisation cloud (Phase 2), dans une logique proche de Telegram.

**Infrastructure disponible** (réutilisée, aucune dépendance externe) :
- Cloudflare Workers + Durable Object `ChatRoom` (realtime WebSocket)
- D1 (SQLite) avec système de migrations
- R2 (`MEDIA`) pour les fichiers, purge auto 7 jours
- Client Android zéro-dépendance (minSdk 21, targetSdk 36)
- Limite de taille relâchée : 20-30 Mo acceptés (pas d'optimisation agressive)

---

## PHASE 1 — Recherche + Confidentialité + Modération

### 1.1 Recherche full-text des messages

**Base de données**
- Table virtuelle FTS5 : `messages_fts(title, body, conv_id, sender_id)`.
- Synchronisation via triggers sur `messages` :
  - `AFTER INSERT` → `INSERT INTO messages_fts(rowid, title, body, conv_id, sender_id) VALUES (...)`
  - `AFTER DELETE` → `DELETE FROM messages_fts WHERE rowid = old.id`
  - `AFTER UPDATE` → delete + insert.
- Migration `0006_search_privacy_blocks.sql` (regroupe Phase 1).

**API**
- `GET /api/messages/search?q=<terme>&conv_id=<opt>&limit=20`
  - Requête FTS5 : `SELECT m.* FROM messages_fts f JOIN messages m ON m.id = f.rowid WHERE messages_fts MATCH ? [AND conv_id=?] LIMIT ?`.
  - Retour : `{results:[{id, conv_id, sender_id, body, created_at}]}`.
  - Auth requise ; résultats limités aux conversations accessibles à l'utilisateur.

**Client Android**
- `DiscussionActivity` : ajout d'une `SearchView` / barre dans `activity_discussion.xml` (déjà présent `btn_search_disc` → réutiliser).
- Nouvel écran `SearchResultsActivity` (ou dialog liste) : appelle `ApiClient.searchMessages(token, q, convId)`, affiche résultats, tap → ouvre `DiscussionActivity` et saute au message (`jumpToMessage`).
- `ApiClient.searchMessages(...)` + parse.

**Gestion d'erreurs**
- Requête vide → `400`. Pas de résultat → liste vide (pas d'erreur).
- FTS5 MATCH échoue si syntaxe invalide → fallback `LIKE '%q%'` sur `body`.

---

### 1.2 Paramètres de confidentialité

**Base de données**
- Ajout colonne `privacy_settings TEXT` (JSON) dans `users`.
- Schéma JSON par défaut :
  ```json
  {"hide_online":false,"hide_last_seen":false,"read_receipts":true,"ephemeral_default_ttl":0}
  ```

**API**
- `GET /api/privacy` → renvoie le JSON courant.
- `PUT /api/privacy` (body JSON) → valide les clés, met à jour `users.privacy_settings`.
- Impact côté serveur :
  - `presence` broadcast : si `hide_online`=true pour l'utilisateur, on n'envoie pas `online:true` à ses pairs (on envoie `online:false` ou on omet).
  - `GET /api/users/lastseen` et `/api/users/presence` : si `hide_last_seen`=true → retourne `last_seen:0`.
  - `read` receipts : si `read_receipts`=false → n'envoie pas le push `read`.

**Client Android**
- `tab_settings.xml` : section "Confidentialité" avec 4 toggles (Switch).
- `ConversationsActivity` : `loadPrivacy()` au chargement settings, `savePrivacy(json)` sur changement.
- `ApiClient.getPrivacy()` / `putPrivacy(token, json)`.

---

### 1.3 Blocage / Signalement

**Base de données**
- `user_blocks (id, blocker_id, blocked_id, created_at, PRIMARY(blocker_id, blocked_id))`.
- `message_reports (id, reporter_id, message_id, reason, created_at)`.

**API**
- `POST /api/block` body `{blocked_id}` → insert (idempotent).
- `DELETE /api/block/:blocked_id` → delete.
- `GET /api/blocks` → liste des bloqués.
- `POST /api/report` body `{message_id, reason}` → insert.
- **Filtrage** : dans `fetchMessages` (messages.ts) et dans `pushToUser` (index.ts), exclure les messages dont `sender_id` est bloqué par le destinataire (et vice-versa). Also exclure les conversations avec un bloqué de `myConversations`.

**Client Android**
- Profil/DM : bouton "Bloquer" (dialog confirm). `ApiClient.block/unblock`.
- Long-press message → "Signaler" (choix raison) → `ApiClient.report`.
- Respect : si `peerId` est bloqué, ne plus afficher la conversation / ne plus recevoir de push.

---

## PHASE 2 — Rich Media + Synchronisation Cloud

### 2.1 GIF / Stickers

**Base de données**
- `sticker_packs (id, owner_id, title, created_at)`.
- `sticker_pack_items (pack_id, media_key, emoji, position)`.

**API**
- `POST /api/upload` existant : autoriser `type=gif|sticker` (déjà type-driven).
- `GET /api/stickers` → packs + items (media_key signés pour R2).
- `POST /api/stickers/pack` body `{title, items:[{media_key, emoji}]}`.
- `GET /api/sticker/:pack_id` détail.

**Client Android**
- `DiscussionActivity` : panneau stickers (réutiliser `emoji_row` / nouveau `sticker_row`).
- Sélecteur GIF : `Intent.ACTION_GET_CONTENT` image/* → upload type `gif` → envoie message type `sticker`/`gif`.
- `item_message_sticker.xml` + binding dans l'adapter (type `TYPE_STICKER`).
- `ApiClient.getStickers()` + `ApiClient.createStickerPack()`.

---

### 2.2 Synchronisation cloud / Sauvegarde automatique

**API**
- `GET /api/export` → JSON complet : `{messages, settings, blocks, stickers_owned}` (limité à l'utilisateur courant, paginé si > 10 Mo).
- `POST /api/import` body JSON → merge idempotent (upsert par id).
- `device_sync (user_id, last_sync)` pour horodatage de dernière synchro.
- Option auto : `GET /api/sync/delta?since=<ts>` → seulements les changements.

**Client Android**
- `tab_settings.xml` : section "Sauvegarde" avec "Exporter" (sauvegarde fichier local + upload R2 `backups/<user_id>`) et "Restaurer".
- `ApiClient.exportData()` / `importData(json)`.
- Auto-sync : au démarrage, `GET /api/sync/delta` si `last_sync` local existe.

**Gestion d'erreurs**
- Export volumineux → stream JSON, pas de timeout court.
- Import conflit → dernière écriture gagne (pas de résolution fine en MVP).

---

## Plan de test & validation

| Couche | Vérification |
|---|---|
| Serveur | `wrangler dev` local : tester chaque endpoint (auth 401 sans token, 200 avec). FTS5 requête. Block filtre. Privacy impact. Sticker upload. Export/import. |
| Migration | `wrangler d1 execute --file migrations/0006_...sql --remote` (apply, pas `migrations apply` qui échoue). |
| Client | `assembleDebug` + `assembleRelease` (lintVitalRelease OK). Tests manuels : recherche → saut, toggle privacy → pastille disparaît, block → plus de messages, sticker send, export/import round-trip. |
| Intégration | Deux comptes de test : vérifier blocage bidirectionnel, présence masquée. |

---

## Hors-scope (YAGNI)
- Modération automatisée / ML.
- Chiffrement de bout en bout (MVP).
- Stickers animés personnalisés complexes (GIF simple OK).
- Résolution de conflits fine lors de l'import.

---

## Ordre d'implémentation suggéré
1. Migration `0006` (FTS5 + privacy + blocks + reports + sticker tables + device_sync).
2. Phase 1 APIs (search, privacy, block/report) + filtres serveur.
3. Phase 1 client (search UI, privacy toggles, block/report).
4. Phase 2 APIs (stickers, export/import/sync).
5. Phase 2 client (sticker panel, GIF picker, backup UI).
6. Build + lint + tests manuels + commit par phase.
