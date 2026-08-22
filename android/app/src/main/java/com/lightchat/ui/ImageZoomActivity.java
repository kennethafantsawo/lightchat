package com.lightchat.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.github.chrisbanes.photoview.PhotoView;
import com.lightchat.R;
import com.lightchat.SessionStore;
import com.lightchat.util.GlideAuth;

public class ImageZoomActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_zoom);
        final PhotoView pv = findViewById(R.id.zoom_image);
        final TextView err = findViewById(R.id.zoom_err);
        final String key = getIntent().getStringExtra("media_key");
        findViewById(R.id.zoom_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        if (key == null || key.isEmpty()) {
            finish();
            return;
        }
        final SessionStore session = new SessionStore(this);
        GlideAuth.load(pv, "/api/media?key=" + key, session.token());
    }
}
