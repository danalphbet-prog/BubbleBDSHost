package com.bubble.bdshost;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Only branding changes: original wordmark PNG + user's new cube + aesthetic log. */
public class SplashActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        int red = Color.rgb(251,1,2);
        getWindow().setStatusBarColor(red);
        getWindow().setNavigationBarColor(red);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(red);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView cube = new ImageView(this);
        cube.setImageResource(R.drawable.bubble_splash_cube);
        cube.setScaleType(ImageView.ScaleType.FIT_CENTER);
        brand.addView(cube,new LinearLayout.LayoutParams(dp(190),dp(190)));
        ImageView wordmark = new ImageView(this);
        wordmark.setImageResource(R.drawable.bubble_wordmark);
        wordmark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(dp(275),dp(76));
        wp.topMargin=dp(6);
        brand.addView(wordmark,wp);
        frame.addView(brand,new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,FrameLayout.LayoutParams.WRAP_CONTENT,Gravity.CENTER));
        TextView log=new TextView(this);
        log.setText("~root# linux : ready\n[bds] runtime : initialized\n[link] connection : validated");
        log.setTextColor(Color.BLACK);
        log.setTextSize(9);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        log.setLineSpacing(dp(2),1f);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM|Gravity.START);
        lp.leftMargin=dp(17); lp.bottomMargin=dp(24);
        frame.addView(log,lp);
        setContentView(frame);
        handler.postDelayed(()->{
            startActivity(new Intent(this,MainActivity.class));
            finish();
            overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);
        },1100);
    }
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
