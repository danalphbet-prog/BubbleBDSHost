package com.bubble.bdshost;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Background shared by the Bubble screens, never used for the splash. */
public final class BubbleThemeBackground extends Drawable {
    private static final int FROM = Color.rgb(90, 4, 15); // #2A1A26
    private static final int TO = Color.rgb(33, 2, 10);   // #1C1A33
    private static final float IMAGE_OPACITY = 1.0f;

    private final Bitmap forest;
    private final Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private int opacity = 255;

    public BubbleThemeBackground(Bitmap forest) {
        this.forest = forest;
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        int save = canvas.save();
        canvas.clipRect(b);

        // CSS-style linear-gradient(160deg, #2A1A26, #1C1A33).
        float w = b.width();
        float h = b.height();
        double a = Math.toRadians(160.0);
        float dx = (float) Math.sin(a);
        float dy = (float) -Math.cos(a);
        float half = (Math.abs(dx) * w + Math.abs(dy) * h) * 0.5f;
        float midX = b.centerX();
        float midY = b.centerY();
        gradientPaint.setAlpha(opacity);
        gradientPaint.setShader(new LinearGradient(
                midX - dx * half, midY - dy * half,
                midX + dx * half, midY + dy * half,
                FROM, TO, Shader.TileMode.CLAMP));
        if (forest == null || forest.isRecycled()) canvas.drawRect(b, gradientPaint);

        // The attached forest stays a subtle texture, not a bright wallpaper.
        if (forest != null && !forest.isRecycled()) {
            int iw = forest.getWidth();
            int ih = forest.getHeight();
            float scale = Math.max(w / iw, h / ih);
            int sw = Math.min(iw, Math.max(1, Math.round(w / scale)));
            int sh = Math.min(ih, Math.max(1, Math.round(h / scale)));
            int sx = (iw - sw) / 2;
            int sy = (ih - sh) / 2;
            source.set(sx, sy, sx + sw, sy + sh);
            imagePaint.setAlpha(Math.round(opacity * IMAGE_OPACITY));
            canvas.drawBitmap(forest, source, b, imagePaint);
        }
        canvas.restoreToCount(save);
    }

    @Override public void setAlpha(int alpha) {
        opacity = Math.max(0, Math.min(255, alpha));
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter filter) {
        gradientPaint.setColorFilter(filter);
        imagePaint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
