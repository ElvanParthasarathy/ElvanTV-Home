package com.elvan.tvhome;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The ambient glow: a wide wash stretched over the screen and a small halo behind the focused icon. The pictures are
 * painted once, in white, and drawn tinted with the focused app's colour, so changing apps (or fading between two
 * colours) allocates and paints nothing: on a 1 GB stick, building them again on every colour change cost more than a
 * frame. The halo is a view of its own (HaloView), so following the icon moves a small picture instead of
 * redrawing the whole wash.
 */
final class GlowDrawable extends Drawable {
    private static final int WASH_W = 480;
    private static final int WASH_H = 270;
    private static final int APP_HALO = 96;
    private static final int TILE_HALO = 192;

    private static Bitmap wash;
    private static Bitmap appHalo;
    private static Bitmap tileHalo;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private int color = 0xFFFFFFFF;
    private boolean tile;

    GlowDrawable() {
        ensureBitmaps();
    }

    private static synchronized void ensureBitmaps() {
        if (wash != null) return;
        // 1. The wash, measured on the stock launcher: neutral on the left, rising to the app's colour toward the right
        // edge, a little stronger at the top of the screen than at the bottom.
        wash = Bitmap.createBitmap(WASH_W, WASH_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(wash);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        int maxA = 0x1E;
        float[] ramp = {0f, 0f, 0.45f, 0.80f, 1f, 1f};
        int[] cols = new int[ramp.length];
        for (int i = 0; i < cols.length; i++) cols[i] = (Math.round(maxA * ramp[i]) << 24) | 0xFFFFFF;
        p.setShader(new LinearGradient(0, 0, WASH_W, 0, cols,
                new float[]{0f, 0.15f, 0.40f, 0.70f, 0.86f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, WASH_W, WASH_H, p);
        Paint fade = new Paint();
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        fade.setShader(new LinearGradient(0, 0, 0, WASH_H, 0xFF000000, 0x99000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, WASH_W, WASH_H, fade);

        // 2. The halo of an app: vivid, framing the circular ring and blending into the wash.
        appHalo = Bitmap.createBitmap(APP_HALO, APP_HALO, Bitmap.Config.ARGB_8888);
        Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        float hc = APP_HALO / 2f;
        hp.setShader(new RadialGradient(hc, hc, 48f,
                new int[]{0x5CFFFFFF, 0x48FFFFFF, 0x34FFFFFF, 0x18FFFFFF, 0x06FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.33f, 0.52f, 0.72f, 0.88f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(appHalo).drawRect(0, 0, APP_HALO, APP_HALO, hp);

        // 3. The halo of the Reorder tile: a neutral white bloom that follows the tile's rounded square (measured on
        // the stock launcher: about +45 levels at its edge, still +10 some 70 px out).
        tileHalo = Bitmap.createBitmap(TILE_HALO, TILE_HALO, Bitmap.Config.ARGB_8888);
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        tp.setColor(0xFFFFFFFF);
        tp.setAlpha(0xA0);
        tp.setMaskFilter(new BlurMaskFilter(38f, BlurMaskFilter.Blur.NORMAL));
        float tc = TILE_HALO / 2f;
        new Canvas(tileHalo).drawRoundRect(tc - 20.6f, tc - 20.6f, tc + 20.6f, tc + 20.6f, 8f, 8f, tp);
    }

    /** The app's colour (alpha ignored), or, for the Reorder tile, no wash at all. */
    void setLook(int rgb, boolean reorderTile) {
        int c = 0xFF000000 | rgb;
        if (c == color && reorderTile == tile) return;
        color = c;
        tile = reorderTile;
        paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        invalidateSelf();
    }

    static Bitmap appHalo() {
        ensureBitmaps();
        return appHalo;
    }

    static Bitmap tileHalo() {
        ensureBitmaps();
        return tileHalo;
    }

    /** Size of a halo picture on a screen of this height, in pixels (the pictures are made for a 270 px high one). */
    static float haloSize(Bitmap halo, float screenH) {
        return halo.getWidth() * screenH / WASH_H;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (!tile) canvas.drawBitmap(wash, null, getBounds(), paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // The tint is the glow's own; a filter set from outside is not used.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
