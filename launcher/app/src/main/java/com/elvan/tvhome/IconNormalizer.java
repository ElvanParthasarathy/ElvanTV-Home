package com.elvan.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * Industry-standard launcher icon normalizer following Google AOSP Launcher3 / Pixel Launcher standards.
 *
 * 1. Legacy / Transparent Icons (e.g. Google Play Store): Detects transparent border alpha,
 *    draws an official clean white circular badge (#FFFFFF), and centers the glyph within the
 *    AOSP safe-zone ratio (~62% size) so corners are never clipped.
 * 2. Full-bleed Solid Icons (e.g. Netflix, Prime Video, YouTube): Clips the full solid icon to the circle.
 * 3. Filled shapes smaller than the circle (a rounded square such as Alexa's, a smaller disc): scaled up until the
 *    shape covers the whole circle, so every tile is a full circle like on Google TV, not a square with flat sides.
 *
 * Which kind an icon is comes from its outline: how far the opaque pixels reach from the centre in every direction.
 */
public final class IconNormalizer {

    private IconNormalizer() {}

    public static Drawable normalize(Context ctx, Drawable rawIcon) {
        if (rawIcon == null) return null;

        float density = ctx.getResources().getDisplayMetrics().density;
        int targetSize = Math.max(160, Math.round(96 * density));

        Bitmap srcBitmap = drawableToBitmap(rawIcon, targetSize, targetSize);
        if (srcBitmap == null) return rawIcon;

        Bitmap output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        // Anti-aliased circular clip path
        Path circlePath = new Path();
        circlePath.addCircle(targetSize / 2f, targetSize / 2f, targetSize / 2f, Path.Direction.CW);

        // Opaque reach from the centre, smallest and largest over all directions, as a share of the radius.
        float[] reach = radialReach(srcBitmap);
        float minReach = reach[0];
        float maxReach = reach[1];
        float maxJump = reach[2];
        boolean centreOpaque = (srcBitmap.getPixel(targetSize / 2, targetSize / 2) >>> 24) > 200;
        // A filled shape: opaque all the way round to most of the radius, roughly round or square (a square's
        // corners reach 1.41 times its sides) and with a smooth outline. A glyph such as a gear has teeth, whose
        // edges make the reach jump from one direction to the next.
        boolean filledShape = centreOpaque && minReach >= 0.7f && maxReach / Math.max(0.01f, minReach) <= 1.5f
                && maxJump <= 0.06f;
        boolean fullBleed = minReach >= 0.97f;
        boolean isTransparent = !fullBleed && !filledShape;

        if (filledShape && !fullBleed) {
            // Scale the shape up about the centre until its narrowest reach touches the circle's edge.
            float scale = Math.min(1.8f, 1.02f / minReach);
            float size = targetSize * scale;
            float inset = (targetSize - size) / 2f;
            canvas.save();
            canvas.clipPath(circlePath);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(srcBitmap, null, new android.graphics.RectF(inset, inset, inset + size, inset + size), paint);
            canvas.restore();
        } else if (isTransparent) {
            // Draw clean white circular base (industry standard for Google Play & floating glyphs)
            Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            whitePaint.setColor(Color.WHITE);
            canvas.drawCircle(targetSize / 2f, targetSize / 2f, targetSize / 2f, whitePaint);

            // AOSP safe zone ratio (~62% of diameter)
            int glyphSize = Math.round(targetSize * 0.62f);
            int offset = (targetSize - glyphSize) / 2;
            Rect dest = new Rect(offset, offset, offset + glyphSize, offset + glyphSize);

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(srcBitmap, null, dest, paint);
        } else {
            // Full-bleed icon: clip to circle
            canvas.save();
            canvas.clipPath(circlePath);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(srcBitmap, null, new Rect(0, 0, targetSize, targetSize), paint);
            canvas.restore();
        }

        return new BitmapDrawable(ctx.getResources(), output);
    }

    /**
     * How far the icon's opaque pixels reach from the centre, walking out along 180 directions: the smallest and the
     * largest reach, as a share of the radius (1 = the edge of the inscribed circle; a full square's corners reach
     * about 1.41), and the largest change in reach between two neighbouring directions.
     */
    private static float[] radialReach(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) / 2f;
        float maxDist = radius * 1.42f;
        float min = Float.MAX_VALUE;
        float max = 0f;
        float jump = 0f;
        float first = -1f;
        float prev = -1f;
        for (int a = 0; a < 180; a++) {
            double t = Math.PI * 2 * a / 180;
            float dx = (float) Math.cos(t);
            float dy = (float) Math.sin(t);
            float r = 0f;
            for (float d = 0f; d <= maxDist; d += 1f) {
                int x = Math.round(cx + dx * d);
                int y = Math.round(cy + dy * d);
                if (x < 0 || y < 0 || x >= w || y >= h) break;
                if ((bitmap.getPixel(x, y) >>> 24) < 128) break;
                r = d;
            }
            float share = r / radius;
            min = Math.min(min, share);
            max = Math.max(max, share);
            if (prev >= 0f) jump = Math.max(jump, Math.abs(share - prev));
            else first = share;
            prev = share;
        }
        jump = Math.max(jump, Math.abs(first - prev));
        return new float[]{min, max, jump};
    }

    private static Bitmap drawableToBitmap(Drawable drawable, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, width), Math.max(1, height), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Rect old = drawable.copyBounds();
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        drawable.setBounds(old);
        return bitmap;
    }
}
