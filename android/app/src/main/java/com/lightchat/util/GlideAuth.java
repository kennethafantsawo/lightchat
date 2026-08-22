package com.lightchat.util;

import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.lightchat.net.Endpoints;

/** Charge des images authentifiées (médias du serveur) via Glide, avec le header de session. */
public final class GlideAuth {
    private GlideAuth() {}

    private static GlideUrl authUrl(String path, String token) {
        return new GlideUrl(Endpoints.url(path), new LazyHeaders.Builder()
                .addHeader("Authorization", Endpoints.authHeader(token))
                .build());
    }

    public static void load(ImageView iv, String path, String token) {
        Glide.with(iv).load(authUrl(path, token)).into(iv);
    }

    public static void loadCircle(ImageView iv, String path, String token) {
        Glide.with(iv).load(authUrl(path, token)).circleCrop().into(iv);
    }
}
