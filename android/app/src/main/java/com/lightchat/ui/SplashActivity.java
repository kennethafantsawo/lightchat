package com.lightchat.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.animation.AnimationUtils;

import com.lightchat.R;
import com.lightchat.SessionStore;

public class SplashActivity extends Activity {

    private static final int SPLASH_DURATION = 1600;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        View logo = findViewById(R.id.splash_logo);
        if (logo != null) {
            logo.startAnimation(AnimationUtils.loadAnimation(this, R.anim.splash_logo_anim));
        }

        new Handler().postDelayed(() -> {
            Class<?> target;
            if (new SessionStore(this).token() != null) {
                target = ConversationsActivity.class;
            } else {
                target = LoginActivity.class;
            }
            Intent intent = new Intent(SplashActivity.this, target);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, SPLASH_DURATION);
    }
}
