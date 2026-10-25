package com.elvan.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.view.View;

/**
 * The glow right behind the focused icon. A small view that is only moved (translation), never redrawn, while the row
 * scrolls; drawn tinted with the app's colour, or neutral white for the Reorder tile.
 */
final class HaloView extends View {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private final float screenH;
    private Bitmap bitmap;

    HaloView(Context context, float screenH) {
        super(context);
        this.screenH = screenH;
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setLook(0xFFFFFF, false);
    }

    /** The app's colour, or the neutral bloom of the Reorder tile. Resizes the view only when the picture changes. */
    void setLook(int rgb, boolean reorderTile) {
        Bitmap next = reorderTile ? GlowDrawable.tileHalo() : GlowDrawable.appHalo();
        paint.setColorFilter(reorderTile ? null : new PorterDuffColorFilter(0xFF000000 | rgb, PorterDuff.Mode.SRC_IN));
        bitmap = next;
        fitSize();
        invalidate();
    }

    /** Sizes the view to its picture. Called again once it has layout params (it is created before it is added). */
    void fitSize() {
        int size = Math.round(GlowDrawable.haloSize(bitmap, screenH));
        android.view.ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null && (lp.width != size || lp.height != size)) {
            lp.width = size;
            lp.height = size;
            setLayoutParams(lp);
        }
    }

    int size() {
        return Math.round(GlowDrawable.haloSize(bitmap, screenH));
    }

    /** Centres the view on (x, y) in the ambient layer's coordinates. */
    void centreOn(float x, float y) {
        int half = size() / 2;
        setTranslationX(x - half);
        setTranslationY(y - half);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap == null) return;
        src.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(bitmap, null, src, paint);
    }
}
