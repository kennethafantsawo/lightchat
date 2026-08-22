package com.lightchat.util;

import java.util.HashMap;
import java.util.Map;

/** Présence en ligne partagée entre les écrans (alimentée par les pushes WS). */
public final class Presence {
    private static final Map<String, Boolean> online = new HashMap<String, Boolean>();

    private Presence() {}

    public static synchronized void set(String userId, boolean isOnline) {
        online.put(userId, isOnline);
    }

    public static synchronized boolean isOnline(String userId) {
        Boolean b = online.get(userId);
        return b != null && b;
    }
}
