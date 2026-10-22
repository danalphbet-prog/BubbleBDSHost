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

public class SplashActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(20,6,11));
        getWindow().setNavigationBarColor(Color.rgb(20,6,11));
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(20,6,11));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.bubble_pixel_splash);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int size = (int)(232 * getResources().getDisplayMetrics().density + 0.5f);
        frame.addView(icon, new FrameLayout.LayoutParams(size,size,Gravity.CENTER));
        setContentView(frame);
        handler.postDelayed(() -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);
        }, 1100);
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
