package com.bubble.bdshost;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** White pixel balloon, heavy black outline and right-pointing arrow. */
public final class BubblePixelBalloonDrawable extends Drawable {
    private final Paint brush=new Paint();
    private final int step;
    private int alpha=255;
    public BubblePixelBalloonDrawable(Context context){
        step=Math.max(3,Math.round(3.5f*context.getResources().getDisplayMetrics().density));
        brush.setAntiAlias(false);
    }
    private void box(Canvas c,float x,float y,float xx,float yy,int color){
        brush.setColor(color);brush.setAlpha(alpha);
        c.drawRect(x,y,xx,yy,brush);
    }
    @Override public void draw(Canvas c){
        Rect b=getBounds();int u=step,l=b.left,t=b.top,r=b.right,bt=b.bottom;
        int bodyBottom=bt-u*3, ink=Color.BLACK,paper=Color.WHITE;
        box(c,l+u,t,r-u,bodyBottom,ink);
        box(c,l,t+u,r,bodyBottom-u,ink);
        box(c,l+u*2,t+u,r-u*2,bodyBottom-u,paper);
        box(c,l+u,t+u*2,r-u,bodyBottom-u*2,paper);
        // Tail on the bottom-right: steps visibly point toward the Bubble.
        box(c,r-12*u,bodyBottom-u,r-5*u,bodyBottom+u,ink);
        box(c,r-9*u,bodyBottom+u,r-3*u,bodyBottom+2*u,ink);
        box(c,r-5*u,bodyBottom+2*u,r-u,bt,ink);
        box(c,r-11*u,bodyBottom-u*2,r-5*u,bodyBottom,paper);
        box(c,r-8*u,bodyBottom,r-3*u,bodyBottom+u,paper);
        box(c,r-4*u,bodyBottom+u,r-2*u,bodyBottom+2*u,paper);
    }
    @Override public void setAlpha(int a){alpha=Math.max(0,Math.min(255,a));invalidateSelf();}
    @Override public void setColorFilter(ColorFilter f){brush.setColorFilter(f);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
