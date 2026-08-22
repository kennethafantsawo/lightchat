# LightChat Android App — Coque (Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Créer l'app Android native Java "LightChat" (coque) : projet Gradle compilable, thème Material 3 clair/sombre conforme au cahier des charges, écrans Login et Liste des discussions (shell), couche réseau prête, et première APK release signée (arm64-v8a).

**Architecture:** Projet Gradle autonome dans `android/` (racine Gradle) + module unique `android/app`. App 100 % framework Android (aucune dépendance AndroidX — APK minimal pour téléphone 512 Mo/1 cœur). Réseau via `HttpURLConnection` (java.net) vers `https://lightchat.kennethafantsawo.workers.dev`. Aucun code natif — `ndk.abiFilters "arm64-v8a"` documente l'intention 64 bits (un APK Java pur n'embarque pas de libs). Stockage session via SharedPreferences.

**Tech Stack:** Java 21 (JVM) · Android SDK compileSdk 36 / build-tools 36.1.0 / minSdk 21 (Android 5.0) / targetSdk 36 · Gradle 8.11.1 (installé dans `C:\Users\kenneth\AppData\Local\Temp\opencode\gradle\gradle-8.11.1`) · AGP 8.10.1 · JUnit 4.13.2 (tests unitaires JVM purs) · keytool (Java 21).

**Environnement vérifié :** SDK à `C:\Users\kenneth\AppData\Local\Android\Sdk` (platforms android-36/36.1, build-tools 34/36/36.1/37, licences acceptées). PAS de cmdline-tools (inutile pour AGP). `java -version` → Temurin 21.

---

### Task 1: Outillage Gradle (wrapper + racine du projet)

**Files:**
- Create: `android/settings.gradle`
- Create: `android/build.gradle`
- Create: `android/gradle.properties`
- Create: `android/local.properties` (GENERATED, gitignored)
- Create: `android/.gitignore`
- Create: `android/app/.gitignore` (au Task 2)

- [ ] **Step 1: Vérifier que le SDK et Java sont détectables**

Run:
```powershell
java -version           # → openjdk 21.0.11
Test-Path "$env:LOCALAPPDATA\Android\Sdk\platforms\android-36"   # → True
```
Logger le résultat exact si échec.

- [ ] **Step 2: Créer `android/settings.gradle`**

```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "lightchat"
include(":app")
```

- [ ] **Step 3: Créer `android/build.gradle` (racine)**

```groovy
plugins {
    id "com.android.application" version "8.10.1" apply false
}

tasks.register("clean", Delete) {
    delete rootProject.layout.buildDirectory
}
```

- [ ] **Step 4: Créer `android/gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
org.gradle.daemon=true
```

- [ ] **Step 5: Générer `local.properties` + `.gitignore`**

Créer `android/local.properties` (PAS committé) :
```properties
sdk.dir=C\:\\Users\\kenneth\\AppData\\Local\\Android\\Sdk
```

Créer `android/.gitignore` :
```gitignore
.gradle/
build/
local.properties
keystore.properties
*.jks
*.keystore
.idea/
.DS_Store
```

- [ ] **Step 6: Générer le wrapper Gradle**

Run (depuis `H:\duo app\android`):
```powershell
& "C:\Users\kenneth\AppData\Local\Temp\opencode\gradle\gradle-8.11.1\bin\gradle.bat" wrapper --gradle-version 8.11.1
```
Attendu : crée `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`.

- [ ] **Step 7: Vérifier + commit**

Run: `.\gradlew.bat --version` → affiche "Gradle 8.11.1".
Commit :
```bash
git -C "H:\duo app" add android/settings.gradle android/build.gradle android/gradle.properties android/.gitignore android/gradlew android/gradlew.bat android/gradle
git -C "H:\duo app" commit -m "build(android): gradle wrapper and project root"
```
(`local.properties` ne doit PAS être dans le commit.)

---

### Task 2: Module `app` — manifest, thème, icône, ressource, première build

**Files:**
- Create: `android/app/build.gradle`
- Create: `android/app/proguard-rules.pro`
- Create: `android/app/.gitignore`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/values/colors.xml`
- Create: `android/app/src/main/res/values-night/colors.xml`
- Create: `android/app/src/main/res/values/strings.xml`
- Create: `android/app/src/main/res/values/themes.xml`
- Create: `android/app/src/main/res/values-night/themes.xml`
- Create: `android/app/src/main/res/drawable/ic_launcher.xml`
- Create: `android/app/src/main/res/values/dimens.xml`
- Create: `android/app/src/main/res/layout/activity_main.xml`
- Create: `android/app/src/main/java/com/lightchat/MainActivity.java`

- [ ] **Step 1: Créer `android/app/build.gradle`**

```groovy
plugins {
    id "com.android.application"
}

android {
    namespace "com.lightchat"
    compileSdk 36
    buildToolsVersion "36.1.0"

    defaultConfig {
        applicationId "com.lightchat"
        minSdk 21
        targetSdk 36
        versionCode 1
        versionName "0.1.0"
        ndk { abiFilters "arm64-v8a" }
    }

    signingConfigs {
        release {
            def props = new Properties()
            def f = rootProject.file("keystore.properties")
            if (f.exists()) {
                f.withInputStream { props.load(it) }
                storeFile rootProject.file(props["storeFile"])
                storePassword props["storePassword"]
                keyAlias props["keyAlias"]
                keyPassword props["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            minifyEnabled false
            signingConfig signingConfigs.release
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation "junit:junit:4.13.2"
}
```

- [ ] **Step 2: Créer `android/app/proguard-rules.pro`** (vide — minify désactivé)

- [ ] **Step 3: Créer `android/app/.gitignore`**

```gitignore
/build
```

- [ ] **Step 4: Créer `android/app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:label="@string/app_name"
        android:icon="@drawable/ic_launcher"
        android:theme="@style/Theme.LightChat"
        android:allowBackup="false">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 5: Créer `res/values/colors.xml` (palette claire du cahier des charges)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="background">#f8f9ff</color>
    <color name="primary">#3525cd</color>
    <color name="on_primary">#ffffff</color>
    <color name="primary_container">#4f46e5</color>
    <color name="on_primary_container">#dad7ff</color>
    <color name="secondary">#006c49</color>
    <color name="secondary_container">#6cf8bb</color>
    <color name="on_secondary_container">#00714d</color>
    <color name="error">#ba1a1a</color>
    <color name="on_surface">#0b1c30</color>
    <color name="on_surface_variant">#464555</color>
    <color name="surface">#f8f9ff</color>
    <color name="surface_variant">#d3e4fe</color>
    <color name="surface_container_low">#eff4ff</color>
    <color name="surface_container">#e5eeff</color>
    <color name="surface_container_high">#dce9ff</color>
    <color name="surface_dim">#cbdbf5</color>
    <color name="outline">#777587</color>
    <color name="outline_variant">#c7c4d8</color>
    <color name="inverse_surface">#213145</color>
    <color name="inverse_primary">#c3c0ff</color>
    <color name="tertiary">#684000</color>
    <color name="surface_tint">#4d44e3</color>
    <color name="ic_launcher_bg">#3525cd</color>
    <color name="ic_launcher_fg">#ffffff</color>
</resources>
```

- [ ] **Step 6: Créer `res/values-night/colors.xml` (palette sombre)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="background">#13121b</color>
    <color name="primary">#c3c0ff</color>
    <color name="on_primary">#1d00a5</color>
    <color name="primary_container">#4f46e5</color>
    <color name="on_primary_container">#dad7ff</color>
    <color name="secondary">#4edea3</color>
    <color name="secondary_container">#00b47d</color>
    <color name="on_secondary_container">#003e28</color>
    <color name="error">#ffb4ab</color>
    <color name="on_surface">#f8f9ff</color>
    <color name="on_surface_variant">#c7c4d8</color>
    <color name="surface">#0b111a</color>
    <color name="surface_variant">#35343e</color>
    <color name="surface_container_low">#1b1b24</color>
    <color name="surface_container">#1f1f28</color>
    <color name="surface_container_high">#23293b</color>
    <color name="surface_dim">#0b111a</color>
    <color name="outline">#334155</color>
    <color name="outline_variant">#464555</color>
    <color name="inverse_surface">#e4e1ee</color>
    <color name="inverse_primary">#4d44e3</color>
    <color name="tertiary">#ffb95f</color>
    <color name="surface_tint">#c3c0ff</color>
    <color name="ic_launcher_bg">#c3c0ff</color>
    <color name="ic_launcher_fg">#1d00a5</color>
</resources>
```

- [ ] **Step 7: Créer `res/values/strings.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">LightChat</string>
</resources>
```

- [ ] **Step 8: Créer `res/values/dimens.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <dimen name="screen_padding">16dp</dimen>
    <dimen name="radius_card">16dp</dimen>
    <dimen name="text_title">20sp</dimen>
    <dimen name="text_body">16sp</dimen>
</resources>
```

- [ ] **Step 9: Créer les thèmes (clair + sombre)**

`res/values/themes.xml` (frame `android:Theme.Material.Light.NoActionBar`) :
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.LightChat" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:colorPrimary">@color/primary</item>
        <item name="android:colorPrimaryDark">@color/primary_container</item>
        <item name="android:colorAccent">@color/secondary</item>
        <item name="android:windowBackground">@color/background</item>
        <item name="android:statusBarColor">@color/primary_container</item>
        <item name="android:navigationBarColor">@color/background</item>
        <item name="android:textColorPrimary">@color/on_surface</item>
        <item name="android:textColorSecondary">@color/on_surface_variant</item>
    </style>
</resources>
```

`res/values-night/themes.xml` (parent `android:Theme.Material.NoActionBar`) :
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.LightChat" parent="android:Theme.Material.NoActionBar">
        <item name="android:colorPrimary">@color/primary</item>
        <item name="android:colorPrimaryDark">@color/primary_container</item>
        <item name="android:colorAccent">@color/secondary</item>
        <item name="android:windowBackground">@color/background</item>
        <item name="android:statusBarColor">@color/surface_container</item>
        <item name="android:navigationBarColor">@color/background</item>
        <item name="android:textColorPrimary">@color/on_surface</item>
        <item name="android:textColorSecondary">@color/on_surface_variant</item>
    </style>
</resources>
```

- [ ] **Step 10: Créer l'icône launcher vectorielle**

`res/drawable/ic_launcher.xml` (bulle de chat, viewport 24) :
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@color/ic_launcher_bg"
        android:pathData="M0,0h24v24h-24z" />
    <path
        android:fillColor="@color/ic_launcher_fg"
        android:pathData="M20,2H4C2.9,2 2,2.9 2,4v18l4,-4h14c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2z" />
</vector>
```

- [ ] **Step 11: Créer le layout de test `res/layout/activity_main.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center"
    android:background="@color/background">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/app_name"
        android:textColor="@color/primary"
        android:textSize="@dimen/text_title"
        android:textStyle="bold" />
</LinearLayout>
```

- [ ] **Step 12: Créer `MainActivity.java`**

`src/main/java/com/lightchat/MainActivity.java` :
```java
package com.lightchat;

import android.app.Activity;
import android.os.Bundle;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}
```

- [ ] **Step 13: Build debug — vérifier la chaîne complète**

Run (depuis `H:\duo app\android`):
```powershell
.\gradlew.bat :app:assembleDebug
```
Attendu : BUILD SUCCESSFUL, APK dans `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 14: Commit**

```bash
git -C "H:\duo app" add android/app
git -C "H:\duo app" commit -m "build(android): app module manifest theme resources first build"
```

---

### Task 3: Couche réseau + utilitaires (purs JVM, testables)

**Files:**
- Create: `android/app/src/main/java/com/lightchat/net/Endpoints.java`
- Create: `android/app/src/main/java/com/lightchat/net/ApiClient.java`
- Create: `android/app/src/main/java/com/lightchat/util/Eco.java`
- Test: `android/app/src/test/java/com/lightchat/net/EndpointsTest.java`
- Test: `android/app/src/test/java/com/lightchat/util/EcoTest.java`

- [ ] **Step 1: Écrire le test `EndpointsTest.java`**

```java
package com.lightchat.net;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class EndpointsTest {
    @Test
    public void url_prepend_slash_when_missing() {
        assertEquals(
            Endpoints.BASE_URL + "/api/me",
            Endpoints.url("api/me"));
    }

    @Test
    public void url_keeps_existing_slash() {
        assertEquals(
            Endpoints.BASE_URL + "/api/me",
            Endpoints.url("/api/me"));
    }

    @Test
    public void auth_header_uses_bearer() {
        assertEquals("Bearer abc123", Endpoints.authHeader("abc123"));
    }
}
```

- [ ] **Step 2: Lancer le test → vérifier l'échec**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.lightchat.net.EndpointsTest"`
Attendu : FAIL (Endpoints.class introuvable).

- [ ] **Step 3: Créer `Endpoints.java` (pur JVM)**

```java
package com.lightchat.net;

public final class Endpoints {
    public static final String BASE_URL = "https://lightchat.kennethafantsawo.workers.dev";

    private Endpoints() {}

    public static String url(String path) {
        return BASE_URL + (path.startsWith("/") ? path : "/" + path);
    }

    public static String authHeader(String token) {
        return "Bearer " + token;
    }
}
```

- [ ] **Step 4: Relancer le test → PASS**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.lightchat.net.EndpointsTest"`
Attendu : 3 tests passent.

- [ ] **Step 5: Écrire le test `EcoTest.java` (détection tel faible)**

```java
package com.lightchat.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class EcoTest {
    @Test
    public void old_phone_is_eco() {
        assertEquals(Eco.MODE_ECO, Eco.mode(1, 512));
    }

    @Test
    public void modern_phone_is_standard() {
        assertEquals(Eco.MODE_STANDARD, Eco.mode(8, 6144));
    }

    @Test
    public void two_cores_falls_back_to_eco() {
        assertEquals(Eco.MODE_ECO, Eco.mode(2, 4096));
    }
}
```

- [ ] **Step 6: Lancer → échec attendu puis créer `Eco.java`**

Run d'abord (échec attendu : classe absente), puis créer :
```java
package com.lightchat.util;

public final class Eco {
    public static final String MODE_ECO = "ECO";
    public static final String MODE_STANDARD = "STANDARD";

    private Eco() {}

    /** cores < 4 ou RAM < 1 Go → mode Économique (tel 2015). */
    public static String mode(int cores, long ramMb) {
        return (cores < 4 || ramMb < 1024) ? MODE_ECO : MODE_STANDARD;
    }
}
```

- [ ] **Step 7: Relancer les deux tests → PASS**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Attendu : 6 tests passent (3 Endpoints + 3 Eco).

- [ ] **Step 8: Créer `ApiClient.java` (HttpURLConnection, à brancher en Phase 3)**

```java
package com.lightchat.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class ApiClient {
    private static final int TIMEOUT_MS = 15_000;

    private ApiClient() {}

    public static class ApiResponse {
        public final int status;
        public final String body;
        public ApiResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    public static ApiResponse call(String method, String path, String jsonBody, String token)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(Endpoints.url(path)).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json");
            if (token != null) {
                conn.setRequestProperty("Authorization", Endpoints.authHeader(token));
            }
            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = is == null ? "" : readAll(is);
            return new ApiResponse(status, body);
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
```

- [ ] **Step 9: Build + commit**

Run: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL.
Commit :
```bash
git -C "H:\duo app" add android/app/src/main/java/com/lightchat/net android/app/src/main/java/com/lightchat/util android/app/src/test
git -C "H:\duo app" commit -m "feat(android): networking client and eco-mode detection with tests"
```

---

### Task 4: Écrans shell — Login + Liste des discussions + BottomNav

**Files:**
- Create: `android/app/src/main/java/com/lightchat/SessionStore.java` (SharedPreferences)
- Create: `android/app/src/main/java/com/lightchat/ui/LoginActivity.java`
- Create: `android/app/src/main/java/com/lightchat/ui/ConversationsActivity.java`
- Modify: `android/app/src/main/AndroidManifest.xml` (deux activités, Login launcher)
- Modify: `android/app/src/main/java/com/lightchat/MainActivity.java` → SUPPRIMÉ (remplacé par LoginActivity)
- Create: `android/app/src/main/res/layout/activity_login.xml`
- Create: `android/app/src/main/res/layout/activity_conversations.xml`
- Create: `android/app/src/main/res/drawable/bg_input.xml`
- Create: `android/app/src/main/res/drawable/bg_btn_primary.xml`

- [ ] **Step 1: Créer `SessionStore.java`**

```java
package com.lightchat;

import android.content.Context;
import android.content.SharedPreferences;

public final class SessionStore {
    private static final String PREFS = "lightchat_session";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_USER_ID = "user_id";

    private final SharedPreferences sp;

    public SessionStore(Context ctx) {
        sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void save(String token, String username, String userId) {
        sp.edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_USERNAME, username)
                .putString(KEY_USER_ID, userId)
                .apply();
    }

    public String token() { return sp.getString(KEY_TOKEN, null); }
    public String username() { return sp.getString(KEY_USERNAME, null); }
    public String userId() { return sp.getString(KEY_USER_ID, null); }
    public boolean hasSession() { return token() != null; }
    public void clear() { sp.edit().clear().apply(); }
}
```

- [ ] **Step 2: Créer les drawables**

`res/drawable/bg_input.xml` :
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/surface_container" />
    <corners android:radius="@dimen/radius_card" />
</shape>
```

`res/drawable/bg_btn_primary.xml` :
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/primary" />
    <corners android:radius="@dimen/radius_card" />
</shape>
```

- [ ] **Step 3: Créer `activity_login.xml` (écran Accueil — connexion/création directe, pas de bienvenue)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/background"
    android:fillViewport="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="@dimen/screen_padding"
        android:gravity="center_horizontal">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="48dp"
            android:layout_marginBottom="32dp"
            android:text="@string/app_name"
            android:textColor="@color/primary"
            android:textSize="32sp"
            android:textStyle="bold" />

        <EditText
            android:id="@+id/input_username"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="Pseudo"
            android:padding="14dp"
            android:background="@drawable/bg_input"
            android:textColor="@color/on_surface"
            android:textColorHint="@color/on_surface_variant"
            android:inputType="text" />

        <EditText
            android:id="@+id/input_password"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:hint="Mot de passe"
            android:padding="14dp"
            android:background="@drawable/bg_input"
            android:textColor="@color/on_surface"
            android:textColorHint="@color/on_surface_variant"
            android:inputType="textPassword" />

        <Button
            android:id="@+id/btn_login"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="20dp"
            android:text="Se connecter"
            android:background="@drawable/bg_btn_primary"
            android:textColor="@color/on_primary" />

        <TextView
            android:id="@+id/txt_status"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:textColor="@color/error" />
    </LinearLayout>
</ScrollView>
```

- [ ] **Step 4: Créer `LoginActivity.java` (le login réel arrive en Phase 3 — ici : shell qui persiste un état de test)**

```java
package com.lightchat.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.content.Intent;

import com.lightchat.R;
import com.lightchat.SessionStore;

public class LoginActivity extends Activity {
    private SessionStore session;
    private EditText username;
    private EditText password;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = new SessionStore(this);
        if (session.hasSession()) {
            goConversations();
            return;
        }
        setContentView(R.layout.activity_login);

        username = findViewById(R.id.input_username);
        password = findViewById(R.id.input_password);
        status = findViewById(R.id.txt_status);
        Button login = findViewById(R.id.btn_login);

        login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Phase 3 : appel réel à POST /api/auth/login.
                // Shell actuel : on simule une connexion locale.
                if (username.length() == 0 || password.length() == 0) {
                    status.setText("Renseigne pseudo et mot de passe.");
                    return;
                }
                session.save("dev-token", username.getText().toString().trim(), "dev-id");
                goConversations();
            }
        });
    }

    private void goConversations() {
        startActivity(new Intent(this, ConversationsActivity.class));
        finish();
    }
}
```

- [ ] **Step 5: Créer `activity_conversations.xml` (liste + BottomNav inline)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="@color/background">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="12dp"
        android:background="@color/surface_container_low">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/app_name"
            android:textColor="@color/on_surface"
            android:textSize="@dimen/text_title"
            android:textStyle="bold" />
    </LinearLayout>

    <FrameLayout
        android:id="@+id/content"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

    <LinearLayout
        android:id="@+id/bottom_nav"
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:orientation="horizontal"
        android:background="@color/surface_container">

        <TextView
            android:id="@+id/tab_chats"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center"
            android:text="Chats"
            android:textColor="@color/primary"
            android:textSize="12sp"
            android:textStyle="bold"
            android:background="@drawable/bg_input" />

        <TextView
            android:id="@+id/tab_friends"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center"
            android:text="Amis"
            android:textColor="@color/on_surface_variant"
            android:textSize="12sp" />

        <TextView
            android:id="@+id/tab_search"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center"
            android:text="Recherche"
            android:textColor="@color/on_surface_variant"
            android:textSize="12sp" />

        <TextView
            android:id="@+id/tab_settings"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center"
            android:text="Paramètres"
            android:textColor="@color/on_surface_variant"
            android:textSize="12sp" />
    </LinearLayout>
</LinearLayout>
```

- [ ] **Step 6: Créer `ConversationsActivity.java` (shell — contenu stub + déconnexion)**

```java
package com.lightchat.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.lightchat.R;
import com.lightchat.SessionStore;

public class ConversationsActivity extends Activity {
    private SessionStore session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = new SessionStore(this);
        setContentView(R.layout.activity_conversations);

        FrameLayout content = findViewById(R.id.content);
        content.addView(placeholder("Aucune discussion pour l'instant."));

        setNavSelection(findViewById(R.id.tab_chats));
        bindNav(findViewById(R.id.tab_chats), "Aucune discussion pour l'instant.");
        bindNav(findViewById(R.id.tab_friends), "Tes amis apparaîtront ici.");
        bindNav(findViewById(R.id.tab_search), "Recherche d'amis par pseudo.");
        bindNav(findViewById(R.id.tab_settings), "Paramètres (disponible en Phase 6).");
    }

    private TextView placeholder(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getResources().getColor(R.color.on_surface_variant));
        tv.setPadding(24, 24, 24, 24);
        return tv;
    }

    private void bindNav(final TextView tab, final String text) {
        tab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FrameLayout content = findViewById(R.id.content);
                content.removeAllViews();
                content.addView(placeholder(text));
                setNavSelection(tab);
            }
        });
    }

    private void setNavSelection(TextView active) {
        int onVariant = getResources().getColor(R.color.on_surface_variant);
        int primary = getResources().getColor(R.color.primary);
        for (int id : new int[]{R.id.tab_chats, R.id.tab_friends, R.id.tab_search, R.id.tab_settings}) {
            TextView t = findViewById(id);
            boolean isActive = t == active;
            t.setTextColor(isActive ? primary : onVariant);
            t.setTextSize(12);
            t.setTypeface(t.getTypeface(), isActive ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            t.setBackgroundResource(isActive ? R.drawable.bg_input : 0);
        }
    }
}
```

- [ ] **Step 7: Mettre à jour `AndroidManifest.xml`**

Remplacer la déclaration d'activité existante par :
```xml
        <activity
            android:name=".ui.LoginActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".ui.ConversationsActivity"
            android:exported="false"
            android:windowSoftInputMode="adjustResize" />
```
Et SUPPRIMER le fichier `MainActivity.java` + `res/layout/activity_main.xml`.

- [ ] **Step 8: Build + test + commit**

Run: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL (7 tests).
Commit :
```bash
git -C "H:\duo app" add android/app
git -C "H:\duo app" commit -m "feat(android): login and conversations shell with bottom nav"
```

---

### Task 5: Première APK release signée (arm64-v8a)

**Files:**
- Create: `android/keystore.properties` (GENERATED, gitignored)
- Create: `H:\duo app\keystore\lightchat.jks` (GENERATED, gitignored)
- Modify: `android/build.gradle` (aucun — le signingConfig release est déjà câblé au Task 2)

- [ ] **Step 1: Générer le keystore (mot de passe aléatoire, jamais committé)**

Générer un mot de passe aléatoire, créer le keystore (validité 25 ans) et écrire `keystore.properties` :
```powershell
New-Item -ItemType Directory -Path "H:\duo app\keystore" -Force | Out-Null
$pass = -join ((48..57)+(65..90)+(97..122) | Get-Random -Count 24 | ForEach-Object {[char]$_})
keytool -genkeypair -v -keystore "H:\duo app\keystore\lightchat.jks" -alias lightchat -keyalg RSA -keysize 2048 -validity 9125 -storepass $pass -keypass $pass -dname "CN=LightChat, OU=Personal, O=Kenneth, C=FR"
@"
storeFile=..\keystore\lightchat.jks
storePassword=$pass
keyAlias=lightchat
keyPassword=$pass
"@ | Set-Content "H:\duo app\android\keystore.properties"
```
⚠️ Le producteur ne doit JAMAIS afficher `$pass` dans le log ni le commit. Le fichier `keystore.properties` et le `.jks` sont déjà dans `.gitignore`.

- [ ] **Step 2: Vérifier que le signingConfig release pointe le bon storeFile**

Le `android/app/build.gradle` (Task 2) lit `rootProject.file("keystore.properties")` puis `rootProject.file(props["storeFile"])`. Depuis la racine `android/`, `..\keystore\lightchat.jks` → `H:\duo app\keystore\lightchat.jks`. Vérifier que les deux chemins existent :
```powershell
Test-Path "H:\duo app\android\keystore.properties"
Test-Path "H:\duo app\keystore\lightchat.jks"
```

- [ ] **Step 3: Build release signé**

Run (depuis `H:\duo app\android`):
```powershell
.\gradlew.bat :app:assembleRelease
```
Attendu : BUILD SUCCESSFUL, `app/build/outputs/apk/release/app-release.apk`.

- [ ] **Step 4: Vérifier la signature avec apksigner**

Run:
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0\apksigner.bat" verify --print-certs "H:\duo app\android\app\build\outputs\apk\release\app-release.apk"
```
Attendu : affiche `Signer #1 certificate DN: CN=LightChat...` et `verified using v1 scheme: true` (minSdk 21 → v1 requis).

- [ ] **Step 5: Vérifier que l'APK ne contient que arm64-v8a (pas de libs — Java pur)**

Run:
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0\aapt.exe" dump badging "H:\duo app\android\app\build\outputs\apk\release\app-release.apk" | Select-String "package|native-code|sdkVersion|targetSdkVersion"
```
Attendu : `package: name='com.lightchat'`, `sdkVersion:'21'`, `targetSdkVersion:'36'`, pas de ligne `native-code` (aucune lib native).

- [ ] **Step 6: Commit**

```bash
git -C "H:\duo app" add android
git -C "H:\duo app" commit -m "chore(android): release keystore signing config"
```
(Ne pas committer `keystore.properties` ni `lightchat.jks` — vérifier `git status` que les deux sont bien ignorés.)

---

### Task 6: Doc + état final

**Files:**
- Modify: `docs/superpowers/plans/2026-08-16-lightchat-server-phase1.md` (aucun — laisser tel quel)
- Create: `android/README.md`

- [ ] **Step 1: Créer `android/README.md`**

```markdown
# LightChat — App Android

## Build
```powershell
cd android
.\gradlew.bat :app:assembleRelease      # APK signé
.\gradlew.bat :app:testDebugUnitTest    # tests JVM
```

## Installer sur téléphone (Android 5.0+)
- Copier `app/build/outputs/apk/release/app-release.apk` vers le téléphone
- Activer « Sources inconnues » puis ouvrir l'APK

## Où est l'APK ?
`android/app/build/outputs/apk/release/app-release.apk`

## Réseau
Base URL : `https://lightchat.kennethafantsawo.workers.dev` (voir `Endpoints.java`)

## Signatures
- Keystore : `H:\duo app\keystore\lightchat.jks` (GARDER SÛR — permet de rééditer l'app)
- `keystore.properties` : caché via `.gitignore`
```

- [ ] **Step 2: Commit final**

```bash
git -C "H:\duo app" add android/README.md
git -C "H:\duo app" commit -m "docs(android): build and install instructions"
```

- [ ] **Step 3: État de la branche**

Run: `git -C "H:\duo app" log --oneline -5` → liste les commits Phase 2.
Run: `git -C "H:\duo app" status --short` → propre (les artefacts de build/keystore sont gitignorés).

---

## Self-review

**1. Couverture spec (cahier des charges §3, §5) :**
- minSdk 21 / arm64-v8a / Java natif → Tasks 1-2 ✓
- Écran Accueil connexion/création directe → Task 4 (LoginActivity) ✓
- Liste des discussions + BottomNav Chats/Amis/Recherche/Paramètres → Task 4 ✓
- Palette claire + sombre Material 3 (couleurs exactes du §5) → Task 2 Steps 5-6, 9 ✓
- Détection téléphone faible (mode Économique) → Task 3 Eco ✓
- Première APK signée → Task 5 ✓

**2. Placeholder scan :** aucun TBD/TODO — chaque étape a son code complet. Le login réseau est explicitement reporté en Phase 3 avec commentaire (pas de placeholder silencieux).

**3. Cohérence types/noms :**
- `Endpoints.BASE_URL` défini au Task 3, utilisé du même Task jusqu'à la Phase 3 ✓
- `Eco.setMode -> mode(int,long)` → `Eco.MODE_ECO/MODE_STANDARD` utilisés dans les tests du même Task ✓
- Ids de layout (`input_username`, `tab_chats`, `content`...) cohérents entre layout XML (Task 4) et `findViewById` Java (Task 4) ✓
- Package `com.lightchat`, namespace `com.lightchat`, applicationId `com.lightchat` alignés ✓
- `background` n'est PAS utilisé comme nom d'attribut réservé (nom de couleur = `@color/background`, OK) ✓

**Gaps acceptés (phases suivantes) :** vrai login/register réseau (P3), polling/WebSocket (P4-P5), groupes & dialogues (P6), médias/appels/stickers/notifications (phases ultérieures).