package com.elvan.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/**
 * A profile's picture: one of the vector characters (res/drawable/avatar_N.xml, made from avatars_svg/ by svg2vd.py) on a
 * gradient circle. The characters are drawn without their own background, so the owner can choose the gradient behind them.
 * A drawn picture is kept in a small cache, so drawing it again (while the focus animation scales it) costs a bitmap draw.
 */
final class ProfileAvatar extends Drawable {
    private static final int[] CHARACTER_DRAWABLES = {
            R.drawable.avatar_1, R.drawable.avatar_2, R.drawable.avatar_3, R.drawable.avatar_4, R.drawable.avatar_5,
            R.drawable.avatar_6, R.drawable.avatar_7, R.drawable.avatar_8, R.drawable.avatar_9};
    static final int CHARACTERS = CHARACTER_DRAWABLES.length;
    /** Gradient backgrounds: top-left colour, bottom-right colour. The last two are greys. */
    static final int[][] GRADIENTS = {
            {0xFFFF8A65, 0xFFE53935}, {0xFFFFD54F, 0xFFFF8F00}, {0xFF81C784, 0xFF2E7D32}, {0xFF4DD0E1, 0xFF00838F},
            {0xFF64B5F6, 0xFF1565C0}, {0xFFBA68C8, 0xFF6A1B9A}, {0xFFF48FB1, 0xFFC2185B}, {0xFFDCE775, 0xFF9E9D24},
            {0xFFCFD8DC, 0xFF78909C}, {0xFF90A4AE, 0xFF37474F}};

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(48);

    private final Context context;
    private final int character, gradient, size;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    ProfileAvatar(Context context, int character, int gradient, int sizePx) {
        this.context = context.getApplicationContext();
        this.character = Math.floorMod(character, CHARACTERS);
        this.gradient = Math.floorMod(gradient, GRADIENTS.length);
        this.size = sizePx;
    }

    /** A gradient as a shader over a box, for the colour choices. */
    static Shader gradientShader(int gradient, RectF b) {
        int[] g = GRADIENTS[Math.floorMod(gradient, GRADIENTS.length)];
        return new LinearGradient(b.left, b.top, b.right, b.bottom, g[0], g[1], Shader.TileMode.CLAMP);
    }

    @Override
    public void draw(Canvas canvas) {
        RectF b = new RectF(getBounds());
        int px = Math.max(8, Math.round(Math.min(b.width(), b.height())));
        // Drawn at the next size up in steps, so a focus scale does not make a new picture every frame.
        int bucket = ((px + 31) / 32) * 32;
        String key = character + ":" + gradient + ":" + bucket;
        Bitmap bmp = CACHE.get(key);
        if (bmp == null) {
            bmp = Bitmap.createBitmap(bucket, bucket, Bitmap.Config.ARGB_8888);
            render(new Canvas(bmp), bucket);
            CACHE.put(key, bmp);
        }
        canvas.drawBitmap(bmp, null, new RectF(b.centerX() - px / 2f, b.centerY() - px / 2f, b.centerX() + px / 2f, b.centerY() + px / 2f),
                bitmapPaint);
    }

    private void render(Canvas c, int d) {
        Path circle = new Path();
        circle.addCircle(d / 2f, d / 2f, d / 2f, Path.Direction.CW);
        c.save();
        c.clipPath(circle);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF all = new RectF(0, 0, d, d);
        p.setShader(gradientShader(gradient, all));
        c.drawRect(all, p);
        // A soft light from the top left over the gradient.
        p.setShader(new RadialGradient(d * 0.3f, d * 0.25f, d * 0.7f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(all, p);
        // The character: cleanly clipped to the circular disc so no hair, hat, or shoulders spill outside.
        Drawable vector = context.getDrawable(CHARACTER_DRAWABLES[character]);
        if (vector != null) {
            vector = vector.mutate();
            vector.setBounds(0, 0, d, d);
            vector.draw(c);
        }
        c.restore();
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void setAlpha(int alpha) {
        bitmapPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        bitmapPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
