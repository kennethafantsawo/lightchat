package com.lightchat.ui;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.lightchat.R;
import com.lightchat.SessionStore;
import com.lightchat.net.ApiClient;
import com.lightchat.util.Async;
import com.lightchat.util.MediaStore;

public class VideoPlayerActivity extends Activity {
    private PlayerView playerView;
    private TextView error;
    private ExoPlayer exoPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);
        playerView = findViewById(R.id.video);
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
                exoPlayer = new ExoPlayer.Builder(VideoPlayerActivity.this).build();
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(
                        MediaStore.localFile(VideoPlayerActivity.this, key))));
                exoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
                exoPlayer.addListener(new Player.Listener() {
                    @Override public void onPlayerError(androidx.media3.common.PlaybackException e) {
                        error.setVisibility(View.VISIBLE);
                        error.setText(R.string.media_err);
                    }
                });
                exoPlayer.prepare();
                exoPlayer.play();
                playerView.setPlayer(exoPlayer);
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
    }
}
