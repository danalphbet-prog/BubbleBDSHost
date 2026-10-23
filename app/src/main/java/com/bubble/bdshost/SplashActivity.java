package com.bubble.bdshost;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

public class SplashActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(215,0,27));
        getWindow().setNavigationBarColor(Color.rgb(120,0,17));
        FrameLayout frame=new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(240,0,33));
        TextView title=new TextView(this);
        title.setText("BUBBLE");
        title.setTextColor(Color.WHITE);
        title.setTextSize(48);
        title.setGravity(Gravity.CENTER);
        try{title.setTypeface(getResources().getFont(R.font.minecraft_bold));}
        catch(Throwable ignored){title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);}
        frame.addView(title,new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.WRAP_CONTENT,Gravity.CENTER));
        setContentView(frame);
        handler.postDelayed(()->{
            startActivity(new Intent(this,MainActivity.class));finish();
            overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);
        },1100);
    }
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
