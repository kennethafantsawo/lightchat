package com.lightchat.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.lightchat.util.Skin;

/** Waveform vocal déterministe (hauteurs dérivées du seed) avec progression de lecture. */
public final class WaveView extends View {
    private long seed = 1;
    private float progress = 0f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WaveView(Context ctx, AttributeSet attrs) { super(ctx, attrs); }

    public void setSeed(long s) { this.seed = s == 0 ? 1 : s; invalidate(); }
    public void setProgress(float p) { this.progress = Math.max(0f, Math.min(1f, p)); invalidate(); }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        int n = 28;
        int gap = 2;
        int barW = (w - gap * (n - 1)) / n;
        if (barW < 1) barW = 1;
        int mid = h / 2;
        int played = (int) (progress * n);
        int primary = Skin.palette() != null ? Skin.palette().primary : 0xFF3525CD;
        int variant = Skin.palette() != null ? Skin.palette().onSurfaceVariant : 0xFF888888;
        for (int i = 0; i < n; i++) {
            long r = (seed * 2654435761L + i * 40503L) >>> 8;
            float f = ((r & 0xff) / 255f);
            int barH = (int) (Math.max(3f, (h - 4) * (0.25f + 0.75f * f)));
            int x = i * (barW + gap);
            paint.setColor(i < played ? primary : variant);
            c.drawRoundRect(x, mid - barH / 2, x + barW, mid + barH / 2, barW / 2f, barW / 2f, paint);
        }
    }
}
