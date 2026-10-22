package com.bubble.bdshost;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Pixel-stepped speech balloon with a discreet little tail. No anti-aliasing. */
public final class BubblePixelBalloonDrawable extends Drawable {
    private final Paint paint = new Paint();
    private final int unit;
    private int alpha = 255;
    private final int outline = Color.rgb(173, 139, 210);
    private final int fill = Color.rgb(37, 28, 51);

    public BubblePixelBalloonDrawable(Context context) {
        unit = Math.max(2, Math.round(3f * context.getResources().getDisplayMetrics().density));
        paint.setAntiAlias(false);
    }

    private void rect(Canvas c, float l, float t, float r, float b, int color) {
        paint.setColor(color);
        paint.setAlpha(alpha);
        c.drawRect(l,t,r,b,paint);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        final int x = bounds.left, y = bounds.top;
        final int r = bounds.right, b = bounds.bottom;
        final int u = unit;
        final int end = b - 3*u;
        // Steps, not rounded corners.
        rect(canvas,x+u,y,r-u,end,outline);
        rect(canvas,x,y+u,r,end-u,outline);
        rect(canvas,x+u+u,y+u,r-u-u,end-u,fill);
        rect(canvas,x+u,y+u+u,r-u,end-u-u,fill);
        // Pixelated arrow emerging near the left edge.
        rect(canvas,x+5*u,end-u,x+10*u,end+u,outline);
        rect(canvas,x+6*u,end+u,x+9*u,end+2*u,outline);
        rect(canvas,x+7*u,end+2*u,x+8*u,b,outline);
        rect(canvas,x+6*u,end-u,x+9*u,end+u,fill);
        rect(canvas,x+7*u,end+u,x+8*u,end+2*u,fill);
    }

    @Override public void setAlpha(int value) {
        alpha=Math.max(0,Math.min(255,value));
        invalidateSelf();
    }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
