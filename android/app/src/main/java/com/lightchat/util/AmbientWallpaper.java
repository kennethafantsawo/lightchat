package com.lightchat.util;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Fond d'ambiance « liquide » : dégradé vertical doux surmonté de plusieurs
 * taches radiales qui dérivent lentement (effet aurora / verre en fusion).
 * S'anime tant que le drawable est visible.
 */
public final class AmbientWallpaper extends Drawable {
    private final int top;
    private final int mid;
    private final int bottom;
    private final int[] blobColors;
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long seed;
    private boolean running = false;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            invalidateSelf();
            scheduleSelf(tick, System.currentTimeMillis() + 40L);
        }
    };

    public AmbientWallpaper(int top, int mid, int bottom, int[] blobColors) {
        this.top = top;
        this.mid = mid;
        this.bottom = bottom;
        this.blobColors = blobColors;
        this.seed = System.currentTimeMillis() % 100000L;
    }

    @Override
    public void setAlpha(int alpha) { base.setAlpha(alpha); }

    @Override
    public void setColorFilter(android.graphics.ColorFilter cf) { base.setColorFilter(cf); }

    @Override
    public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (visible) start(); else stop();
        return changed;
    }

    private void start() {
        if (running) return;
        running = true;
        scheduleSelf(tick, System.currentTimeMillis() + 40L);
    }

    private void stop() {
        running = false;
        unscheduleSelf(tick);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) return;

        int[] vert = {top, mid, bottom};
        float[] pos = {0f, 0.55f, 1f};
        base.setShader(new android.graphics.LinearGradient(0, 0, 0, h, vert, pos, Shader.TileMode.CLAMP));
        canvas.drawRect(b.left, b.top, b.right, b.bottom, base);

        long t = System.currentTimeMillis() + seed;
        int n = blobColors.length;
        for (int i = 0; i < n; i++) {
            float phase = (float) (Math.sin(t * 0.00018 + i * 1.7) * 0.5 + 0.5);
            float phase2 = (float) (Math.cos(t * 0.00021 + i * 2.3) * 0.5 + 0.5);
            float cx = b.left + w * (0.15f + 0.7f * frac(i * 0.37f + phase * 0.9f));
            float cy = b.top + h * (0.12f + 0.7f * frac(phase2 + i * 0.19f));
            float radius = Math.max(w, h) * (0.42f + 0.12f * (float) Math.sin(t * 0.0003 + i));
            int col = blobColors[i];
            blob.setShader(new RadialGradient(cx, cy, radius,
                    colorAlpha(col, 0.55f), colorAlpha(col, 0.0f), Shader.TileMode.CLAMP));
            canvas.drawRect(b.left, b.top, b.right, b.bottom, blob);
        }
    }

    private static float frac(float v) {
        float f = v - (float) Math.floor(v);
        return f < 0 ? f + 1f : f;
    }

    private static int colorAlpha(int color, float a) {
        int alpha = Math.round(255f * a);
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
}
