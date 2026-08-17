package com.lightchat;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

public final class App extends Application {
    private static int active = 0;

    public static boolean isForeground() {
        return active > 0;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { active++; }
            @Override public void onActivityStopped(Activity a) { if (active > 0) active--; }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
}