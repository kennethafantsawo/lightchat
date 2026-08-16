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
