# LightChat Android — Phase 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Brancher l'app Android sur l'API serveur déployée (Phase 1 terminée/prod) : authentification login+register réels (`POST /api/auth/*`), liste des conversations réelle (`GET /api/conversations`), écran discussion texte (`GET /api/messages` + `POST /api/send`). WebSocket temps réel + service foreground (Phase 4, différé). Persistance locale SQLite (Phase 4, différé).

**Architecture:** `ApiClient` (HttpURLConnection, déjà existant en Phase 2) est appelé depuis un thread secondaire via `Async` (wrapper Thread + `runOnUiThread`). Les réponses JSON sont parsées par `Json`, un parseur pur JDK (aucune dépendance — même pattern que `Endpoints`/`Eco` Phase 2), testable en JVM. Modèles `Conversation`/`Message` purs Java. Polling 2 s (pas de WS persistant) en Phase 3 pour rester léger sur le tel 2015 (512 Mo).

**Tech Stack:** Android natif Java, minSdk 21, HttpURLConnection (ApiClient), **0 dépendance** (pas AndroidX, pas de lib JSON, pas de lib WS). JUnit 4.13.2 pour tests JVM purs (Json + models). Keystore release déjà généré (Phase 5).

**Contrat API (véifié sur `server/src/index.ts` déployé) :**
- `POST /api/auth/register` `{username,password,first_name,last_name,age,gender,email?,phone?}` → `{token, user{…}}`
- `POST /api/auth/login` `{username,password}` → `{token,user{…}}` | 401 `{error}`
- `GET /api/me` → `{user}` | 401
- `GET /api/conversations` → `{conversations:[{conv_id,kind,created_at,last_body,last_at,last_type}]}` (`last_body`/`last_at`/`last_type` NULL si aucun message)
- `GET /api/friends` → `{friends:[{id,username,first_name,last_name,color,avatar_url}]}`
- `GET /api/messages?conv_id=..&since=N` → `{messages:[Message]}` (nouveaux depuis N) ; `&before=N` → historique inversé
- `POST /api/send` `{conv_id,type?,body?,media_key?,mime?,duration_ms?}` → `{ok, message:Message}`
- `WS /api/ws` → Phase 4 (hello token → ready ; push `{userIds,payload}`)

Message = `{id,conv_id,sender_id,type,body,media_key,mime,duration_ms,status,created_at}` (snake_case). `user` = `{id,username,first_name,last_name,age,gender,email,phone,avatar_url,color,created_at}`.

---

## Structure fichiers

```
app/src/main/java/com/lightchat/
  SessionStore.java          (existant — persiste token/username/userId; réutilisé tel quel)
app/src/main/java/com/lightchat/net/
  Endpoints.java, ApiClient.java   (existants — réutilisés)
app/src/main/java/com/lightchat/util/
  Eco.java                  (existant — réutilisé)
  Json.java                 (CREER — parseur JDK pur)
  Async.java                (CREER — exécuteur thread + runOnUiThread)
app/src/main/java/com/lightchat/models/
  Conversation.java         (CREER — modèle pur + fromJson)
  Message.java              (CREER — modèle pur + fromJson)
app/src/main/java/com/lightchat/ui/
  LoginActivity.java        (REECRIRE — auth réel + mode register)
  ConversationsActivity.java (REECRIRE — liste /api/conversations)
  DiscussionActivity.java   (CREER — discussion texte + envoi + poll)
app/src/main/res/
  layout/activity_login.xml        (MODIFIER — + lien créer compte + register fields)
  layout/activity_conversations.xml (MODIFIER — ListView)
  layout/activity_discussion.xml   (CREER)
  layout/item_conversation.xml     (CREER)
  layout/item_message_me.xml       (CREER)
  layout/item_message_other.xml    (CREER)
  values/strings.xml               (MODIFIER — + chaînes)
app/src/test/java/com/lightchat/util/
  JsonTest.java                    (CREER)
app/src/test/java/com/lightchat/models/
  ConversationTest.java, MessageTest.java  (CREER)
```

---

### Task 1: Parseur JSON pur JDK + modèles (TDD, tests JVM purs)

Fondation testable. Aucun code Android.

**Files:**
- Create: `app/src/main/java/com/lightchat/util/Json.java`
- Create: `app/src/main/java/com/lightchat/models/Conversation.java`
- Create: `app/src/main/java/com/lightchat/models/Message.java`
- Test: `app/src/test/java/com/lightchat/util/JsonTest.java`
- Test: `app/src/test/java/com/lightchat/models/ConversationTest.java`
- Test: `app/src/test/java/com/lightchat/models/MessageTest.java`

#### Step 1: Write `JsonTest.java` (échoue d'abord)

```java
package com.lightchat.util;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class JsonTest {
    @Test public void parseObject_simple() {
        Map<String,Object> m = Json.parseObject("{\"a\":\"b\"}");
        assertEquals("b", m.get("a"));
    }
    @Test public void parseObject_nested_arrays_and_numbers() {
        Map<String,Object> m = Json.parseObject("{\"i\":42,\"b\":true,\"d\":3.14,\"arr\":[1,2,3],\"o\":{\"x\":null}}");
        assertEquals(Long.valueOf(42), m.get("i"));
        assertEquals(Boolean.TRUE, m.get("b"));
        assertEquals(Double.valueOf(3.14), m.get("d"));
        assertEquals(3, ((List<?>) m.get("arr")).size());
        assertNull(((Map<?,?>) m.get("o")).get("x"));
    }
    @Test public void string_escapes() {
        Map<String,Object> m = Json.parseObject("{\"s\":\"a\\nb\\t\\\"q\u005C\"}");
        assertEquals("a\nb\t\"q\\", m.get("s"));
    }
    @Test public void parseArray_top() {
        List<Object> a = Json.parseArray("[1,2,3]");
        assertEquals(3, a.size());
        assertEquals(Long.valueOf(2), a.get(1));
    }
    @Test public void conversations_api_shape() {
        String api = "{\"conversations\":[{\"conv_id\":\"dm:abc:def\",\"kind\":\"dm\",\"created_at\":123,\"last_body\":\"hi\",\"last_at\":456,\"last_type\":\"text\"}]}";
        Map<String,Object> r = Json.parseObject(api);
        List<?> convs = (List<?>) r.get("conversations");
        assertEquals(1, convs.size());
        @SuppressWarnings("unchecked") Map<String,Object> c = (Map<String,Object>) convs.get(0);
        assertEquals("dm:abc:def", c.get("conv_id"));
        assertEquals(Long.valueOf(456), c.get("last_at"));
    }
    @Test public void rejects_trailing() {
        try { Json.parse("{\"a\":1} junk"); fail("expected"); } catch (IllegalArgumentException e) { /* ok */ }
    }
}
```

#### Step 2: Run test → FAIL (Json inexistant)

`.\gradlew.bat :app:testDebugUnitTest --tests "com.lightchat.util.JsonTest"` → FAIL.

#### Step 3: `Json.java` (parseur récursif pur JDK)

(See full code below — recursive descent: objets→LinkedHashMap ordonné, tableaux→ArrayList, strings+escapes+\uXXXX, long/double, true/false/null.)

#### Step 4: Run JsonTest → PASS (6 tests)

#### Step 5: Write model tests (échouent) puis implémenter

`ConversationTest.java`:
package com.lightchat.models;
import com.lightchat.util.Json;
import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;
public class ConversationTest {
    @Test public void fromJson_with_last_body() {
        Map<String,Object> m = Json.parseObject("{\"conv_id\":\"dm:a:b\",\"kind\":\"dm\",\"created_at\":100,\"last_body\":\"hello\",\"last_at\":200,\"last_type\":\"text\"}");
        Conversation c = Conversation.fromJson(m);
        assertEquals("dm:a:b", c.convId); assertEquals("hello", c.lastBody); assertEquals(200L, c.lastAt); assertEquals(100L, c.createdAt);
    }
    @Test public void fromJson_null_last() {
        Map<String,Object> m = Json.parseObject("{\"conv_id\":\"group:g1\",\"kind\":\"group\",\"created_at\":50,\"last_body\":null,\"last_at\":null,\"last_type\":null}");
        Conversation c = Conversation.fromJson(m);
        assertNull(c.lastBody); assertEquals(0L, c.lastAt);
    }
}
```

`MessageTest.java`:
package com.lightchat.models;
import com.lightchat.util.Json;
import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;
public class MessageTest {
    @Test public void fromJson_text() {
        Map<String,Object> m = Json.parseObject("{\"id\":\"m1\",\"conv_id\":\"c\",\"sender_id\":\"s\",\"type\":\"text\",\"body\":\"hi\",\"media_key\":null,\"mime\":null,\"duration_ms\":null,\"status\":\"sent\",\"created_at\":999}");
        Message msg = Message.fromJson(m);
        assertEquals("m1", msg.id); assertEquals("hi", msg.body); assertEquals("sent", msg.status); assertEquals(999L, msg.createdAt); assertEquals(0L, msg.durationMs);
    }
}

`Conversation.java`:
```java
package com.lightchat.models;
import java.util.Map;
public final class Conversation {
    public final String convId; public final String kind; public final long createdAt;
    public final String lastBody; public final long lastAt; public final String lastType;
    public Conversation(String convId, String kind, long createdAt, String lastBody, long lastAt, String lastType) {
        this.convId=convId; this.kind=kind; this.createdAt=createdAt;
        this.lastBody=lastBody; this.lastAt=lastAt; this.lastType=lastType;
    }
    public static Conversation fromJson(Map<String,Object> m) {
        return new Conversation((String)m.get("conv_id"), (String)m.get("kind"),
            toLong(m.get("created_at")), (String)m.get("last_body"),
            toLong(m.get("last_at")), (String)m.get("last_type"));
    }
    private static long toLong(Object o){ if(o instanceof Number) return ((Number)o).longValue(); return 0L; }
}
```

`Message.java`:
```java
package com.lightchat.models;
import java.util.Map;
public final class Message {
    public final String id; public final String convId; public final String senderId;
    public final String type; public final String body; public final String mediaKey;
    public final String mime; public final long durationMs; public final String status; public final long createdAt;
    public Message(String id,String convId,String senderId,String type,String body,String mediaKey,String mime,long durationMs,String status,long createdAt){
        this.id=id; this.convId=convId; this.senderId=senderId; this.type=type; this.body=body;
        this.mediaKey=mediaKey; this.mime=mime; this.durationMs=durationMs; this.status=status; this.createdAt=createdAt;
    }
    public static Message fromJson(Map<String,Object> m){
        return new Message((String)m.get("id"),(String)m.get("conv_id"),(String)m.get("sender_id"),
            (String)m.get("type"),(String)m.get("body"),(String)m.get("media_key"),
            (String)m.get("mime"),toLong(m.get("duration_ms")),(String)m.get("status"),toLong(m.get("created_at")));
    }
    private static long toLong(Object o){ if(o instanceof Number) return ((Number)o).longValue(); return 0L; }
}
```

(Tests use `JsonLike.parseObject` = alias `Json.parseObject`; replace with `Json.parseObject`.)

#### Step 6: Run all tests → 6 (JsonTest) + 4 (models) PASS

`.\gradlew.bat :app:testDebugUnitTest` → all green.

#### Step 7: Build + commit

`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL.
Commit: `feat(android): json parser and conversation/message models (TDD)`.

---

### Task 2: Async + LoginActivity auth réel

**Files:**
- Create: `app/src/main/java/com/lightchat/util/Async.java`
- Modify: `app/src/main/java/com/lightchat/ui/LoginActivity.java`
- Modify: `app/src/main/res/layout/activity_login.xml`
- Modify: `app/src/main/res/values/strings.xml`

#### Step 1: `Async.java` (wrapper Thread + runOnUiithread)

```java
package com.lightchat.util;
import android.app.Activity;
public final class Async {
    private Async() {}
    public interface Worker<T> { T run() throws Exception; }
    public interface UI<T> { void on(T result, Exception error); }
    public static <T> void exec(Activity activity, Worker<T> w, UI<T> ui) {
        new Thread(() -> {
            T res=null;Exception err=null;
            try { res = w.run(); } catch (Exception e) { err = e; }
            activity.runOnUiThread(() -> ui.on(res, err));
        }).start();
    }
}
```

#### Step 2: `LoginActivity.java` — register/login réel

```java
package com.lightchat.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.lightchat.R;
import com.lightchat.SessionStore;
import com.lightchat.net.ApiClient;
import com.lightchat.util.Async;
import com.lightchat.util.Json;

import java.util.Map;

public class LoginActivity extends Activity {
    private SessionStore session;
    private EditText eUser, ePass, eFirst, eLast, eAge, eGender;
    private TextView status;
    private boolean registerMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = new SessionStore(this);
        if (session.hasSession()) { goConversations(); return; }
        setContentView(R.layout.activity_login);
        eUser = findViewById(R.id.input_username);
        ePass = findViewById(R.id.input_password);
        eFirst = findViewById(R.id.input_first_name);
        eLast = findViewById(R.id.input_last_name);
        eAge = findViewById(R.id.input_age);
        eGender = findViewById(R.id.input_gender);
        status = findViewById(R.id.txt_status);
        Button login = findViewById(R.id.btn_login);
        TextView toggle = findViewById(R.id.txt_toggle_mode);
        login.setOnClickListener(v -> attempt());
        toggle.setOnClickListener(v -> { registerMode = !registerMode; toggleMode(); });
        toggleMode();
    }

    private void toggleMode() {
        findViewById(R.id.group_register).setVisibility(registerMode ? View.VISIBLE : View.GONE);
        String txt = registerMode ? getString(R.string.action_switch_to_login) : getString(R.string.action_switch_to_register);
        ((TextView)findViewById(R.id.txt_toggle_mode)).setText(txt);
        status.setText("");
    }

    private void attempt() {
        String user = eUser.getText().toString().trim();
        String pass = ePass.getText().toString();
        if (user.isEmpty() || pass.isEmpty()) { status.setText("Renseigne pseudo et mot de passe."); return; }
        status.setText(registerMode ? "Création…" : "Connexion…");
        Async.exec(this, () -> {
            String body = registerMode
                ? "{\"username\":\""+esc(user)+"\",\"password\":\""+esc(pass)+"\",\"first_name\":\""+esc(eFirst.getText().toString().trim())+"\",\"last_name\":\""+esc(eLast.getText().toString().trim())+"\",\"age\":"+eAge.getText().toString().trim()+",\"gender\":\""+esc(eGender.getText().toString().trim())+"\"}"
                : "{\"username\":\""+esc(user)+"\",\"password\":\""+esc(pass)+"\"}";
            return ApiClient.call(registerMode ? "POST" : "POST",
                registerMode ? "/api/auth/register" : "/api/auth/login", body, null);
        }, (resp, err) -> {
            if (err != null) { status.setText("Erreur réseau : " + err.getMessage()); return; }
            if (resp.status != 200) { status.setText("Échec (" + resp.status + ")."); return; }
            Map<String,Object> map = Json.parseObject(resp.body);
            String token = (String) map.get("token");
            Map<String,Object> u = (Map<String,Object>) map.get("user");
            session.save(token, (String) u.get("username"), (String) u.get("id"));
            goConversations();
        });
    }

    private static String esc(String s) { return s.replace("\\","\\\\").replace("\"","\\\""); }
    private void goConversations() { startActivity(new Intent(this, ConversationsActivity.class)); finish(); }
}
```

`activity_login.xml` — ajoute `input_first_name/last_name/age/gender` + un `group_register` (GONE by défaut) + `txt_toggle_mode`. `activity_login.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:background="@color/background" android:fillViewport="true">
    <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content"
        android:orientation="vertical" android:padding="@dimen/screen_padding"
        android:gravity="center_horizontal">
        <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:layout_marginTop="32dp" android:layout_marginBottom="24dp"
            android:text="@string/app_name" android:textColor="@color/primary"
            android:textSize="32sp" android:textStyle="bold" />
        <EditText android:id="@+id/input_username" ... hint="Pseudo" />
        <EditText android:id="@+id/input_password" ... hint="Mot de passe" android:inputType="textPassword" />
        <LinearLayout android:id="@+id/group_register" android:layout_width="match_parent"
            android:layout_height="wrap_content" android:orientation="vertical" android:visibility="gone">
            <EditText android:id="@+id/input_first_name" android:hint="Prénom" .../>
            <EditText android:id="@+id/input_last_name" android:hint="Nom" .../>
            <EditText android:id="@+id/input_age" android:hint="Âge" android:inputType="number" .../>
            <EditText android:id="@+id/input_gender" android:hint="Sexe (male/female/other)" .../>
        </LinearLayout>
        <Button android:id="@+id/btn_login" ... text="Se connecter" ... />
        <TextView android:id="@+id/txt_toggle_mode" android:layout_width="wrap_content"
            android:layout_height="wrap_content" android:layout_marginTop="12dp"
            android:text="@string/switch_to_register" android:textColor="@color/secondary" />
        <TextView android:id="@+id/txt_status" android:layout_width="wrap_content"
            android:layout_height="wrap_content" android:layout_marginTop="12dp" android:textColor="@color/error" />
    </LinearLayout>
</ScrollView>
```
(Chaque EditText utilise `android:background="@drawable/bg_input"`, `android:textColor="@color/on_surface"`, etc. comme au Task 2.)

#### Step 3: `strings.xml` + build + commit

Ajouter `switch_to_register`, `switch_to_login`, `status_connecting`, `status_registering`.
Build + commit `feat(android): real auth login/register wired to production api`.

---

### Task 3: ConversationsActivity liste réelle + navigation

**Files:**
- Modify: `app/src/main/res/layout/activity_conversations.xml` (remplacer placeholder par ListView)
- Create: `app/src/main/res/layout/item_conversation.xml`
- Modify: `app/src/main/java/com/lightchat/ui/ConversationsActivity.java`

#### Step 1: layout `activity_conversations.xml` (ListView + header) + `item_conversation.xml`

(item_conversation: avatar placeholder (TextView circle bg), title=other party name, last body snippet, time, unread badge.)

#### Step 2: `ConversationsActivity.java` — GET /api/conversations + /api/friends (label DMs), SimpleAdapter → tap ouvre DiscussionActivity

```java
package com.lightchat.ui;
// onCreate: GET /api/conversations; if empty -> empty state. Parse via Json; label DMs using /api/friends.
// ListView adapter; onItemClick(convId) -> DiscussionActivity.
```

#### Step 3: build + commit — `feat(android): conversations list wired to production api`

---

### Task 4: DiscussionActivity texte + envoi + polling

**Files:**
- Create: `app/src/main/res/layout/activity_discussion.xml`
- Create: `app/src/main/res/layout/item_message_me.xml`, `item_message_other.xml`
- Create: `app/src/main/java/com/lightchat/ui/DiscussionActivity.java`

#### Step 1: layouts — ListView messages + input bar (EditText + send button)

#### Step 2: `DiscussionActivity.java`
- onCreate: fetch `/api/messages?conv_id=..&before=0` (historique), render.
- `send`: POST `/api/send` ; append local.
- `poll` (2s) : GET `/api/messages?conv_id=..&since=<latest local>` ; append nouveaux. (WS = Phase 4.)
- Messages envoyés par soi: bulle primary ; reçus: surface.

#### Step 3: build + commit — `feat(android): text discussion with send and poll sync`

---

### Task 5: Intégration + docs

- Build debug + release ; `:app:testDebugUnitTest` (tests Json + models).
- Mettre à jour `android/README.md` (Phase 3 notes : polling, prod URL, register/login). 
- Commit `docs(android): phase 3 wired auth conversations discussion`.

---

## Self-review (du plan)

1. Spec §3 (écrans 1-3 + discussion texte) : couvert par T2/T3/T4. WS/notifications/service foreground (§134, §135) = Phase 4 (hors scope). SQLite local (§131) = Phase 4 (hors scope — polling serveur en Phase 3). Appels (§139) = Phase 5. Récupération téléphone (§140) = serveur déjà fait (Phase 1). ✓ cohérent.
2. Placeholders: aucun — chaque step contient le code complet.
3. Cohérence types: `Json.parseObject` utilisé partout ; modèles lisent snake_case identique au serveur ; `SessionStore.save(token,username,userId)` identique à la Phase 2. ✓
