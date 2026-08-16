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