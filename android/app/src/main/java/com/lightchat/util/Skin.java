package com.lightchat.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;

import com.lightchat.R;

import java.util.HashMap;
import java.util.Map;

/**
 * Moteur de thèmes fluides : 5 ambiances (light/dark, verre liquide, aurore…).
 * Toutes les couleurs/les formes sont résolues depuis la palette active et
 * basculées à chaud. Les drawables sont mis en cache par (thème,type) pour un
 * coût d'infiation nul pendant le défilement.
 */
public final class Skin {
    public enum ThemeId {
        INDIGO(0),
        MIDNIGHT(1),
        LIQUID(2),
        NEBULA(3),
        AURORA(4);

        public final int code;

        ThemeId(int code) {
            this.code = code;
        }

        public int labelRes() {
            switch (this) {
                case MIDNIGHT: return R.string.theme_midnight;
                case LIQUID: return R.string.theme_liquid;
                case NEBULA: return R.string.theme_nebula;
                case AURORA: return R.string.theme_aurora;
                default: return R.string.theme_indigo;
            }
        }

        public static ThemeId byCode(int code) {
            for (ThemeId t : values()) {
                if (t.code == code) return t;
            }
            return INDIGO;
        }
    }

    public static final class Palette {
        public final boolean dark;
        public final int primary;
        public final int onPrimary;
        public final int primaryContainer;
        public final int onPrimaryContainer;
        public final int onSurface;
        public final int onSurfaceVariant;
        public final int surface;
        public final int bubbleOther;
        public final int bubbleMine;
        public final int glassFill;
        public final int glassFillStrong;
        public final int glassStroke;
        public final int inputFill;
        public final int secondary;
        public final int statusBar;
        public final int navBar;
        public final int ambientTop;
        public final int ambientMid;
        public final int ambientBottom;
        public final int iconTint;

        Palette(boolean dark, int primary, int onPrimary, int primaryContainer, int onPrimaryContainer,
                int onSurface, int onSurfaceVariant, int surface, int bubbleOther, int bubbleMine,
                int glassFill, int glassFillStrong, int glassStroke, int inputFill, int secondary,
                int statusBar, int navBar, int ambientTop, int ambientMid, int ambientBottom, int iconTint) {
            this.dark = dark;
            this.primary = primary;
            this.onPrimary = onPrimary;
            this.primaryContainer = primaryContainer;
            this.onPrimaryContainer = onPrimaryContainer;
            this.onSurface = onSurface;
            this.onSurfaceVariant = onSurfaceVariant;
            this.surface = surface;
            this.bubbleOther = bubbleOther;
            this.bubbleMine = bubbleMine;
            this.glassFill = glassFill;
            this.glassFillStrong = glassFillStrong;
            this.glassStroke = glassStroke;
            this.inputFill = inputFill;
            this.secondary = secondary;
            this.statusBar = statusBar;
            this.navBar = navBar;
            this.ambientTop = ambientTop;
            this.ambientMid = ambientMid;
            this.ambientBottom = ambientBottom;
            this.iconTint = iconTint;
        }
    }

    private static final String PREFS_LC = "lc_prefs";
    private static final String KEY_THEME = "theme";

    private static ThemeId currentTheme = ThemeId.INDIGO;
    private static Palette palette;
    private static android.graphics.drawable.Drawable ambientBg;
    private static final Map<String, android.graphics.drawable.Drawable> pool = new HashMap<String, android.graphics.drawable.Drawable>();
    private static boolean loaded = false;

    private Skin() {}

    public static synchronized ThemeId current() {
        return currentTheme;
    }

    public static void load(Context ctx) {
        if (loaded && ctx == null) return;
        loaded = true;
        density(ctx.getResources().getDisplayMetrics().density);
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_LC, Context.MODE_PRIVATE);
        ThemeId t = ThemeId.byCode(sp.getInt(KEY_THEME, ThemeId.INDIGO.code));
        setTheme(t);
    }

    public static synchronized void setTheme(ThemeId t) {
        if (palette != null && t == currentTheme) return;
        currentTheme = t;
        palette = build(t);
        ambientBg = null;
        pool.clear();
    }

    public static void setTheme(Context ctx, ThemeId t) {
        ctx.getSharedPreferences(PREFS_LC, Context.MODE_PRIVATE)
                .edit().putInt(KEY_THEME, t.code).apply();
        setTheme(t);
    }

    public static Palette palette() {
        return palette;
    }

    public static void apply(android.app.Activity a) {
        applyWindow(a);
        applyBars(a);
    }

    private static void applyWindow(android.app.Activity a) {
        Window w = a.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(ambient());
    }

    private static void applyBars(android.app.Activity a) {
        Window w = a.getWindow();
        if (w == null || palette == null) return;
        w.setStatusBarColor(palette.statusBar);
        w.setNavigationBarColor(palette.navBar);
        int vis = w.getDecorView().getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= 23) {
            if (palette.dark) {
                vis &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                vis |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            if (palette.dark) {
                vis &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                vis |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        w.getDecorView().setSystemUiVisibility(vis);
    }

    // ---------- Ambiance ----------

    /** Dégradé d'ambiance « liquide » animé pour la fenêtre (aura / aurora). */
    public static synchronized android.graphics.drawable.Drawable ambient() {
        if (ambientBg != null) return ambientBg;
        int[] blobs = new int[]{palette.ambientTop, palette.ambientMid, palette.ambientBottom, palette.secondary};
        ambientBg = new AmbientWallpaper(palette.ambientTop, palette.ambientMid, palette.ambientBottom, blobs);
        return ambientBg;
    }

    // ---------- Formes en cache ----------

    private static synchronized GradientDrawable get(String key, GradientDrawable.Orientation orientation,
                                                     int[] colors, float[] radii, int strokeW, int strokeColor) {
        GradientDrawable g = (GradientDrawable) pool.get(key);
        if (g != null) return g;
        g = new GradientDrawable(orientation, colors);
        if (radii != null) g.setCornerRadii(radii);
        if (strokeW > 0) g.setStroke(strokeW, strokeColor);
        pool.put(key, g);
        return g;
    }

    /** Construit une forme verre « liquide » : couche de base translucide +
     *  reflet glossy en haut + liseré réfractif fin. */
    private static synchronized android.graphics.drawable.Drawable glass(String key,
            GradientDrawable.Orientation orientation, int[] colors, float[] radii,
            int strokeW, int strokeColor) {
        android.graphics.drawable.Drawable d = pool.get(key);
        if (d != null) return d;
        GradientDrawable base = new GradientDrawable(orientation, colors);
        if (radii != null) base.setCornerRadii(radii);
        if (strokeW > 0) base.setStroke(strokeW, strokeColor);

        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{argb(54, 0xffffff), argb(10, 0xffffff), argb(0, 0xffffff)});
        if (radii != null) sheen.setCornerRadii(radii);

        android.graphics.drawable.LayerDrawable layered = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{base, sheen});
        layered.setLayerInset(1, (int) dp(1.5f), (int) dp(1.5f), (int) dp(1.5f), (int) dp(8f));
        pool.put(key, layered);
        return layered;
    }

    private static float[] corners(float r, float r2, float r3, float r4) {
        return new float[]{r, r, r2, r2, r3, r3, r4, r4};
    }

    private static float[] pillPx(float d) {
        return corners(d, d, d, d);
    }

    /** Barre « verre » (translucide, arêtes arrondies en bas pour l'en-tête). */
    public static android.graphics.drawable.Drawable glassHeader(float radiusBottom) {
        return glass("hdr:" + radiusBottom, GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{palette.glassFillStrong, palette.glassFill},
                corners(0, 0, radiusBottom, radiusBottom), 1, palette.glassStroke);
    }

    /** Barre du bas / conteneurs : pilule verre flottante. */
    public static android.graphics.drawable.Drawable glassPill(float radius) {
        return glass("gp:" + radius, GradientDrawable.Orientation.TL_BR,
                new int[]{palette.glassFillStrong, palette.glassFill}, pillPx(radius), 1, palette.glassStroke);
    }

    /** Carte verre (panneaux, sélecteur de thème). */
    public static android.graphics.drawable.Drawable glassCard(float radius) {
        return glass("gc:" + radius, GradientDrawable.Orientation.TL_BR,
                new int[]{palette.glassFillStrong, palette.glassFill}, pillPx(radius), 1, palette.glassStroke);
    }

    /** Champ de saisie : pilule très translucide. */
    public static android.graphics.drawable.Drawable pill_input(float radius) {
        return glass("pi:" + radius, GradientDrawable.Orientation.TL_BR,
                new int[]{palette.inputFill, palette.inputFill}, pillPx(radius), 1, palette.glassStroke);
    }

    /** Bouton principal (pilule pleine). */
    public static GradientDrawable pill_primary(float radius) {
        return get("pp:" + radius, GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{lighten(palette.primary, 0.06f), palette.primary}, pillPx(radius), 0, 0);
    }

    /** Pastille d'onglet actif / FAB. */
    public static GradientDrawable pill_container(float radius) {
        return get("pc:" + radius, GradientDrawable.Orientation.TL_BR,
                new int[]{palette.primaryContainer, palette.primaryContainer}, pillPx(radius), 0, 0);
    }

    /** Bulle de message. mine=true → bord tranchant en bas à droite ; inverse en bas à gauche. */
    public static GradientDrawable bubble(boolean mine) {
        String key = mine ? "bm" : "bo";
        GradientDrawable g = (GradientDrawable) pool.get(key);
        if (g != null) return g;
        float r = dp(20);
        float c = dp(6);
        float[] radii = mine
                ? new float[]{r, r, r, r, c, c, r, r}
                : new float[]{r, r, c, c, r, r, r, r};
        int fill = mine ? palette.bubbleMine : palette.bubbleOther;
        g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                mine ? new int[]{lighten(fill, 0.08f), fill} : new int[]{fill, fill});
        g.setCornerRadii(radii);
        if (!mine && !palette.dark) g.setStroke(1, palette.glassStroke);
        pool.put(key, g);
        return g;
    }

    /** Coin photo/vidéo (léger). */
    public static GradientDrawable mediaRounded(int fill) {
        String key = "mr:" + Integer.toHexString(fill);
        GradientDrawable g = (GradientDrawable) pool.get(key);
        if (g != null) return g;
        g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        g.setColor(fill);
        pool.put(key, g);
        return g;
    }

    /** Pastille avatar circulaire. */
    public static GradientDrawable circle(int fill) {
        String key = "av:" + Integer.toHexString(fill);
        GradientDrawable g = (GradientDrawable) pool.get(key);
        if (g != null) return g;
        g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        pool.put(key, g);
        return g;
    }

    /** Point de présence (en ligne). */
    public static GradientDrawable presence(float radius) {
        return get("pres:" + radius, GradientDrawable.Orientation.TL_BR,
                new int[]{lighten(palette.secondary, 0.12f), palette.secondary}, pillPx(radius), 1, 0x2FFFFFFF);
    }

    public static GradientDrawable outlinePill(float radius, int strokeColor) {
        return get("op:" + radius + ":" + Integer.toHexString(strokeColor), GradientDrawable.Orientation.TL_BR,
                new int[]{0x00FFFFFF, 0x00FFFFFF}, pillPx(radius), 1, strokeColor);
    }

    // ---------- Utils couleurs ----------

    public static int argb(int alpha, int color) {
        int a = alpha & 0xFF;
        if (a == 255) return color;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int lighten(int color, float f) {
        int r = Math.min(255, Math.round(((color >> 16) & 0xFF) + 255 * f));
        int g = Math.min(255, Math.round(((color >> 8) & 0xFF) + 255 * f));
        int b = Math.min(255, Math.round((color & 0xFF) + 255 * f));
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static float density;

    public static void density(float d) {
        density = d;
    }

    public static float dp(float v) {
        return (density <= 0 ? 3f : density) * v;
    }

    // ---------- Palettes ----------

    private static int c(String hex) {
        return android.graphics.Color.parseColor(hex);
    }

    private static Palette build(ThemeId t) {
        switch (t) {
            case MIDNIGHT:
                return new Palette(true,
                        c("#c3c0ff"), c("#0f0069"), c("#4f46e5"), c("#dad7ff"),
                        c("#f8f9ff"), c("#94a3b8"), c("#0b111a"),
                        c("#26213d"), c("#4f46e5"),
                        argb(216, c("#191832")), argb(150, c("#23213f")), argb(46, c("#ffffff")),
                        argb(110, c("#ffffff")), c("#4edea3"),
                        c("#0b111a"), c("#0b111a"),
                        c("#141330"), c("#0d0c1e"), c("#080814"),
                        c("#c3c0ff"));
            case LIQUID:
                return new Palette(false,
                        c("#0078a8"), c("#ffffff"), c("#32b5d8"), c("#e3f8ff"),
                        c("#04222e"), c("#335a6b"), c("#eaf8ff"),
                        c("#f0fbff"), c("#0097c9"),
                        argb(190, c("#eefaff")), argb(140, c("#ffffff")), argb(150, c("#ffffff")),
                        argb(120, c("#ffffff")), c("#00a98f"),
                        c("#bfefff"), c("#dff6ff"),
                        c("#dff4ff"), c("#bfeaff"), c("#8fd8ff"),
                        c("#0a6c8f"));
            case NEBULA:
                return new Palette(true,
                        c("#b9a8ff"), c("#1a1338"), c("#5642d9"), c("#dad3ff"),
                        c("#f0eeff"), c("#a7a0d4"), c("#0e0d1c"),
                        c("#2a2447"), c("#6d5cff"),
                        argb(214, c("#1b1834")), argb(150, c("#262043")), argb(40, c("#ffffff")),
                        argb(96, c("#ffffff")), c("#5ff0d0"),
                        c("#0e0d1c"), c("#0e0d1c"),
                        c("#1c1840"), c("#120f2c"), c("#080816"),
                        c("#b9a8ff"));
            case AURORA:
                return new Palette(false,
                        c("#7c3aed"), c("#ffffff"), c("#a78bfa"), c("#f1e8ff"),
                        c("#231b3c"), c("#5f5578"), c("#fbf9ff"),
                        c("#ffffff"), c("#7c3aed"),
                        argb(196, c("#fff6ff")), argb(140, c("#ffffff")), argb(110, c("#ffffff")),
                        argb(120, c("#ffffff")), c("#0aa06f"),
                        c("#ffd1e8"), c("#f4ecff"),
                        c("#ffd1e8"), c("#cdb7ff"), c("#9fd4ff"),
                        c("#7c3aed"));
            case INDIGO:
            default:
                return new Palette(false,
                        c("#3525cd"), c("#ffffff"), c("#4f46e5"), c("#dad7ff"),
                        c("#0b1c30"), c("#464555"), c("#f8f9ff"),
                        c("#e5eeff"), c("#3525cd"),
                        argb(222, c("#eef2ff")), argb(150, c("#ffffff")), argb(120, c("#ffffff")),
                        argb(150, c("#ffffff")), c("#006c49"),
                        c("#e7ebff"), c("#eef1ff"),
                        c("#ffffff"), c("#eaeeff"), c("#d3d8ff"),
                        c("#3525cd"));
        }
    }
}