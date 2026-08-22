package com.lightchat.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.LruCache;

import com.lightchat.SessionStore;
import com.lightchat.net.ApiClient;

/**
 * Charge et met en cache les avatars de profil (R2). Le bitmap est découpé en
 * cercle et appliqué comme fond d'un TextView d'avatar (initiales en repli).
 */
public final class AvatarLoader {
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    public interface Callback {
        void on(Bitmap b);
    }

    public static Bitmap cached(String userId) {
        return cache.get(userId);
    }

    public static void load(final String userId, final String token, final Callback cb) {
        Bitmap b = cache.get(userId);
        if (b != null) { cb.on(b); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] data = ApiClient.download("/api/avatar?user_id=" + userId, token);
                    if (data == null || data.length == 0) return;
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 1;
                    final Bitmap raw = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    if (raw == null) return;
                    final Bitmap circ = cropCircle(raw);
                    cache.put(userId, circ);
                    if (cb != null) cb.on(circ);
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    /** Applique l'avatar (photo ou initiales) à une vue d'avatar circulaire. */
    public static void apply(final android.widget.TextView avatarView, final String userId,
                             final String token, final String initial, final int fallbackColor) {
        avatarView.setText(initial);
        avatarView.setTextColor(0xFFFFFFFF);
        avatarView.setBackground(Skin.circle(fallbackColor));
        if (userId == null || token == null) return;
        Bitmap b = cached(userId);
        if (b != null) { avatarView.setBackgroundDrawable(new BitmapDrawable(avatarView.getResources(), b)); avatarView.setText(""); return; }
        load(userId, token, new Callback() {
            @Override public void on(final Bitmap bmp) {
                if (bmp == null) return;
                avatarView.post(new Runnable() {
                    @Override public void run() {
                        Drawable d = new BitmapDrawable(avatarView.getResources(), bmp);
                        if (Build.VERSION.SDK_INT >= 16) avatarView.setBackground(d);
                        else avatarView.setBackgroundDrawable(d);
                        avatarView.setText("");
                    }
                });
            }
        });
    }

    public static Bitmap cropCircle(Bitmap src) {
        int s = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        android.graphics.Shader shader = new android.graphics.BitmapShader(src,
                android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
        p.setShader(shader);
        float r = s / 2f;
        c.drawCircle(r, r, r, p);
        return out;
    }
}
