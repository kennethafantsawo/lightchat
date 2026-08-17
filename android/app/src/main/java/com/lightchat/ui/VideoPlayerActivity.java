package com.lightchat.ui;

import android.app.Activity;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.VideoView;

import com.lightchat.R;
import com.lightchat.SessionStore;
import com.lightchat.net.ApiClient;
import com.lightchat.util.Async;
import com.lightchat.util.MediaStore;

public class VideoPlayerActivity extends Activity {
    private VideoView video;
    private TextView error;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);
        video = findViewById(R.id.video);
        error = findViewById(R.id.txt_video_err);
        final String key = getIntent().getStringExtra("media_key");
        if (key == null || key.isEmpty()) {
            finish();
            return;
        }
        findViewById(R.id.btn_video_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        final SessionStore session = new SessionStore(this);
        Async.exec(this, new Async.Worker<String>() {
            @Override public String run() throws Exception {
                if (MediaStore.exists(VideoPlayerActivity.this, key)) {
                    return MediaStore.localFile(VideoPlayerActivity.this, key).getAbsolutePath();
                }
                byte[] b = ApiClient.download("/api/media?key=" + key, session.token());
                if (b == null) return null;
                MediaStore.save(VideoPlayerActivity.this, key, b);
                return MediaStore.localFile(VideoPlayerActivity.this, key).getAbsolutePath();
            }
        }, new Async.UI<String>() {
            @Override public void on(String path, Exception err) {
                if (path == null) {
                    error.setVisibility(View.VISIBLE);
                    error.setText(R.string.media_err);
                    return;
                }
                video.setVideoPath(path);
                video.setKeepScreenOn(true);
                video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                        error.setVisibility(View.VISIBLE);
                        error.setText(R.string.media_err);
                        return true;
                    }
                });
                video.start();
            }
        });
    }
}