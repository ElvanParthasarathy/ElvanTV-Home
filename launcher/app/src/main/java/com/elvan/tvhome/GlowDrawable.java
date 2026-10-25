package com.elvan.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The ambient glow: a wide wash stretched over the screen and a small halo that sits behind the focused icon. The halo is
 * drawn at its own position so it can follow the icon while the row scrolls, without repainting the wash.
 */
final class GlowDrawable extends Drawable {
    private final Bitmap wash;
    private final Bitmap halo;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private float haloX;
    private float haloY;
    private final float haloSize;

    GlowDrawable(Bitmap wash, Bitmap halo, float haloX, float haloY, float haloSize) {
        this.wash = wash;
        this.halo = halo;
        this.haloX = haloX;
        this.haloY = haloY;
        this.haloSize = haloSize;
    }

    float haloX() {
        return haloX;
    }

    float haloY() {
        return haloY;
    }

    /** Centres the halo on the icon; redraws only when it actually moved. */
    void setHaloCenter(float x, float y) {
        if (Math.abs(x - haloX) < 0.5f && Math.abs(y - haloY) < 0.5f) return;
        haloX = x;
        haloY = y;
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.drawBitmap(wash, null, getBounds(), paint);
        float half = haloSize / 2f;
        dst.set(haloX - half, haloY - half, haloX + half, haloY + half);
        canvas.drawBitmap(halo, null, dst, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
