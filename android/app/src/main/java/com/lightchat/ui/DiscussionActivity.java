package com.lightchat.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.lightchat.R;
import com.lightchat.SessionStore;
import com.lightchat.models.Message;
import com.lightchat.net.ApiClient;
import com.lightchat.net.Realtime;
import com.lightchat.util.Async;
import com.lightchat.util.Json;
import com.lightchat.util.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DiscussionActivity extends Activity {
    private static final long POLL_FAST_MS = 2000L;
    private static final long POLL_SLOW_MS = 10000L;
    private static final int REQ_PHOTO = 1001;
    private static final int REQ_VIDEO = 1002;
    private static final int REQ_MIC = 1003;
    private static final long MAX_MEDIA_BYTES = 50L * 1024L * 1024L;
    private static final int MAX_IMAGE_EDGE = 1200;

    private SessionStore session;
    private String convId;
    private ListView list;
    private TextView status;
    private EditText input;
    private MessageAdapter adapter;
    private final Map<String, Message> msgMap = new LinkedHashMap<>();
    private final Handler handler = new Handler();
    private boolean alive = false;
    private boolean fetching = false;
    private boolean atBottom = true;

    private MediaPlayer player;
    private TextView playerBtn;
    private String playerBase;
    private MediaRecorder recorder;
    private File voiceFile;
    private long recStartMs;

    private final Realtime.Listener rt = new Realtime.Listener() {
        @Override public void onMessage(String json) { handlePush(json); }
        @Override public void onState(boolean open) { }
    };

    private final Runnable pollLoop = new Runnable() {
        @Override public void run() {
            if (!alive) return;
            fetchNew();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = new SessionStore(this);
        convId = getIntent().getStringExtra("conv_id");
        if (convId == null || convId.isEmpty()) {
            finish();
            return;
        }
        setContentView(R.layout.activity_discussion);

        TextView title = findViewById(R.id.txt_title);
        String passed = getIntent().getStringExtra("title");
        title.setText(passed != null ? passed : convId);

        status = findViewById(R.id.txt_disc_status);
        list = findViewById(R.id.msg_list);
        input = findViewById(R.id.input_msg);
        Button send = findViewById(R.id.btn_send);
        adapter = new MessageAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.txt_empty));
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int s) {}
            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                atBottom = first + visible >= total;
            }
        });

        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendMessage(); }
        });
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btn_plus).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseMedia(); }
        });
        findViewById(R.id.btn_emoji).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleEmojiRow(); }
        });
        findViewById(R.id.btn_mic).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleMic(); }
        });

        LinearLayout emojiRow = findViewById(R.id.emoji_container);
        String[] emojis = getResources().getStringArray(R.array.emoji_list);
        for (final String e : emojis) {
            TextView t = new TextView(this);
            t.setText(e);
            t.setTextSize(20);
            t.setPadding(dp(8), dp(4), dp(8), dp(4));
            t.setTextColor(getResources().getColor(R.color.on_surface));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { insertEmoji(e); }
            });
            emojiRow.addView(t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        alive = true;
        Realtime.get().addListener(rt);
        handler.post(pollLoop);
    }

    @Override
    protected void onPause() {
        super.onPause();
        alive = false;
        Realtime.get().removeListener(rt);
        handler.removeCallbacks(pollLoop);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayer();
        if (recorder != null) {
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final boolean isVideo = requestCode == REQ_VIDEO;
        status.setVisibility(View.VISIBLE);
        Async.exec(this, new Async.Worker<MediaPayload>() {
            @Override public MediaPayload run() throws Exception {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new IOException("Impossible d'ouvrir le média");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                long size = 0;
                int n;
                try {
                    while ((n = in.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                        size += n;
                        if (size > MAX_MEDIA_BYTES) throw new IOException("Média trop volumineux");
                    }
                } finally {
                    in.close();
                }
                byte[] dataBytes = bos.toByteArray();
                String mime = getContentResolver().getType(uri);
                if (mime == null) mime = isVideo ? "video/mp4" : "image/jpeg";
                String key = "send_" + System.currentTimeMillis() + "." + extFor(mime);
                MediaStore.save(DiscussionActivity.this, key, dataBytes);
                return new MediaPayload(dataBytes, mime);
            }
        }, new Async.UI<MediaPayload>() {
            @Override public void on(MediaPayload p, Exception err) {
                if (err != null || p == null || p.data.length == 0) {
                    status.setVisibility(View.VISIBLE);
                    status.setText(R.string.send_photo_error);
                    return;
                }
                uploadAndSend(p.data, p.mime, isVideo ? "video" : "photo", 0);
            }
        });
    }

    private static final class MediaPayload {
        final byte[] data;
        final String mime;
        MediaPayload(byte[] data, String mime) {
            this.data = data;
            this.mime = mime;
        }
    }

    private void chooseMedia() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.media_choose)
            .setItems(new String[]{getString(R.string.media_photo), getString(R.string.media_video)},
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PHOTO);
                        } else {
                            startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("video/*"), REQ_VIDEO);
                        }
                    }
                })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void toggleEmojiRow() {
        View row = findViewById(R.id.emoji_row);
        row.setVisibility(row.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void insertEmoji(String e) {
        int start = input.getSelectionStart();
        int end = input.getSelectionEnd();
        if (start < 0) start = input.getText().length();
        if (end < 0) end = start;
        input.getText().replace(Math.min(start, end), Math.max(start, end), e);
        input.setSelection(Math.min(start, end) + e.length());
        input.requestFocus();
    }

    private void toggleMic() {
        if (recorder != null) {
            stopRecording();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startRecording();
    }

    @SuppressLint("MissingPermission")
    private void startRecording() {
        try {
            File out = new File(getCacheDir(), "voice_" + System.currentTimeMillis() + ".3gp");
            MediaRecorder r = new MediaRecorder();
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            r.setOutputFile(out.getAbsolutePath());
            r.setMaxDuration(60000);
            r.prepare();
            r.start();
            recorder = r;
            voiceFile = out;
            recStartMs = System.currentTimeMillis();
            ((TextView) findViewById(R.id.btn_mic)).setText(R.string.rec_stop);
        } catch (Exception e) {
            recorder = null;
            voiceFile = null;
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.send_error);
        }
    }

    private void stopRecording() {
        final File out = voiceFile;
        final long started = recStartMs;
        MediaRecorder r = recorder;
        recorder = null;
        voiceFile = null;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
            try { r.release(); } catch (Exception ignored) {}
        }
        ((TextView) findViewById(R.id.btn_mic)).setText(R.string.btn_mic);
        if (out == null || !out.exists()) {
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.send_error);
            return;
        }
        final long durationMs = System.currentTimeMillis() - started;
        Async.exec(this, new Async.Worker<byte[]>() {
            @Override public byte[] run() throws Exception {
                byte[] data = new byte[(int) out.length()];
                FileInputStream fis = new FileInputStream(out);
                try {
                    int off = 0;
                    while (off < data.length) {
                        int n = fis.read(data, off, data.length - off);
                        if (n < 0) break;
                        off += n;
                    }
                } finally {
                    fis.close();
                }
                out.delete();
                return data;
            }
        }, new Async.UI<byte[]>() {
            @Override public void on(byte[] data, Exception err) {
                if (err != null || data == null || data.length == 0) {
                    status.setVisibility(View.VISIBLE);
                    status.setText(R.string.send_error);
                    return;
                }
                uploadAndSend(data, "audio/3gpp", "audio", durationMs);
            }
        });
    }

    private void uploadAndSend(final byte[] data, final String mime, final String type, final long durationMs) {
        final String token = session.token();
        final String ext = extFor(mime);
        Async.exec(this, new Async.Worker<Boolean>() {
            @Override public Boolean run() throws Exception {
                ApiClient.ApiResponse up = ApiClient.upload("/api/upload?filename=x." + ext, data, mime, token);
                if (up.status != 200) return false;
                Map<String, Object> u = Json.parseObject(up.body);
                String key = (String) u.get("key");
                if (key == null || key.isEmpty()) return false;
                StringBuilder body = new StringBuilder();
                body.append("{\"conv_id\":\"").append(esc(convId))
                    .append("\",\"type\":\"").append(type)
                    .append("\",\"media_key\":\"").append(esc(key))
                    .append("\",\"mime\":\"").append(esc(mime)).append("\"");
                if (durationMs > 0) body.append(",\"duration_ms\":").append(durationMs);
                body.append("}");
                ApiClient.ApiResponse s = ApiClient.call("POST", "/api/send", body.toString(), token);
                return s.status == 200;
            }
        }, new Async.UI<Boolean>() {
            @Override public void on(Boolean ok, Exception err) {
                if (ok != null && ok) {
                    status.setVisibility(View.GONE);
                    handler.post(pollLoop);
                } else {
                    status.setVisibility(View.VISIBLE);
                    status.setText("audio".equals(type) ? R.string.send_error : R.string.send_photo_error);
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording();
            } else {
                status.setVisibility(View.VISIBLE);
                status.setText(R.string.send_error);
            }
        }
    }

    private void sendMessage() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        final String json = "{\"conv_id\":\"" + esc(convId) + "\",\"type\":\"text\",\"body\":\"" + esc(text) + "\"}";
        Async.exec(this, new Async.Worker<ApiClient.ApiResponse>() {
            @Override public ApiClient.ApiResponse run() throws Exception {
                return ApiClient.call("POST", "/api/send", json, session.token());
            }
        }, new Async.UI<ApiClient.ApiResponse>() {
            @Override public void on(ApiClient.ApiResponse resp, Exception err) {
                if (err != null || resp == null || resp.status != 200) {
                    status.setVisibility(View.VISIBLE);
                    status.setText(R.string.send_error);
                    input.setText(text);
                    return;
                }
                status.setVisibility(View.GONE);
                handler.post(pollLoop);
            }
        });
    }

    private void handlePush(String jsonText) {
        try {
            Map<String, Object> m = Json.parseObject(jsonText);
            if (!"message".equals(m.get("type"))) return;
            Object mo = m.get("message");
            if (!(mo instanceof Map)) return;
            @SuppressWarnings("unchecked") Map<String, Object> mm = (Map<String, Object>) mo;
            Message msg = Message.fromJson(mm);
            if (msg.id == null || !convId.equals(msg.convId)) return;
            msgMap.put(msg.id, msg);
            List<Message> sorted = new ArrayList<Message>(msgMap.values());
            Collections.sort(sorted, new Comparator<Message>() {
                @Override public int compare(Message a, Message b) {
                    if (a.createdAt != b.createdAt) return Long.compare(a.createdAt, b.createdAt);
                    String ai = a.id == null ? "" : a.id;
                    String bi = b.id == null ? "" : b.id;
                    return ai.compareTo(bi);
                }
            });
            adapter.setList(sorted);
            adapter.notifyDataSetChanged();
            if (atBottom && sorted.size() > 0) list.smoothScrollToPosition(sorted.size() - 1);
        } catch (Exception ignored) {
        }
    }

    private void fetchNew() {
        if (fetching) return;
        fetching = true;
        final String token = session.token();
        final String url = "/api/messages?conv_id=" + convId;
        Async.exec(this, new Async.Worker<ApiClient.ApiResponse>() {
            @Override public ApiClient.ApiResponse run() throws Exception {
                return ApiClient.call("GET", url, null, token);
            }
        }, new Async.UI<ApiClient.ApiResponse>() {
            @Override public void on(ApiClient.ApiResponse resp, Exception err) {
                fetching = false;
                if (!alive) return;
                if (err == null && resp != null && resp.status == 200) {
                    try {
                        Map<String, Object> map = Json.parseObject(resp.body);
                        List<Object> arr = (List<Object>) map.get("messages");
                        if (arr != null) {
                            for (Object o : arr) {
                                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                                Message msg = Message.fromJson(m);
                                if (msg.id != null) msgMap.put(msg.id, msg);
                            }
                        }
                        List<Message> sorted = new ArrayList<Message>(msgMap.values());
                        Collections.sort(sorted, new Comparator<Message>() {
                            @Override public int compare(Message a, Message b) {
                                if (a.createdAt != b.createdAt) return Long.compare(a.createdAt, b.createdAt);
                                String ai = a.id == null ? "" : a.id;
                                String bi = b.id == null ? "" : b.id;
                                return ai.compareTo(bi);
                            }
                        });
                        adapter.setList(sorted);
                        adapter.notifyDataSetChanged();
                        if (atBottom && sorted.size() > 0) list.smoothScrollToPosition(sorted.size() - 1);
                        status.setVisibility(View.GONE);
                    } catch (Exception e) {
                        status.setVisibility(View.VISIBLE);
                        status.setText(R.string.msg_error);
                    }
                } else {
                    status.setVisibility(View.VISIBLE);
                    status.setText(R.string.msg_error);
                }
                handler.postDelayed(pollLoop, Realtime.get().isOpen() ? POLL_SLOW_MS : POLL_FAST_MS);
            }
        });
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String extFor(String mime) {
        if (mime == null) return "bin";
        if (mime.contains("jpeg")) return "jpg";
        if (mime.contains("png")) return "png";
        if (mime.contains("webp")) return "webp";
        if (mime.contains("gif")) return "gif";
        if (mime.contains("mp4")) return "mp4";
        if (mime.contains("3gpp") || mime.contains("3gp")) return "3gp";
        return "bin";
    }

    private static String fmtDuration(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000L;
        long m = s / 60L;
        long r = s % 60L;
        return m + ":" + (r < 10 ? "0" : "") + r;
    }

    private void stopPlayer() {
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try { p.stop(); } catch (Exception ignored) {}
            try { p.release(); } catch (Exception ignored) {}
        }
        if (playerBtn != null && playerBase != null) playerBtn.setText(playerBase);
        playerBtn = null;
        playerBase = null;
    }

    private void bindAudio(final TextView btn, final Message m) {
        final String base = "\u25B6 " + fmtDuration(m.durationMs);
        btn.setText(base);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { playAudio(btn, m, base); }
        });
    }

    private void playAudio(final TextView btn, final Message m, final String base) {
        if (player != null && player.isPlaying()) {
            stopPlayer();
            return;
        }
        final String key = m.mediaKey;
        if (key == null || key.isEmpty()) {
            btn.setText(R.string.media_err);
            return;
        }
        playerBtn = btn;
        playerBase = base;
        Async.exec(this, new Async.Worker<String>() {
            @Override public String run() throws Exception {
                if (!MediaStore.exists(DiscussionActivity.this, key)) {
                    byte[] b = ApiClient.download("/api/media?key=" + key, session.token());
                    if (b == null) return null;
                    MediaStore.save(DiscussionActivity.this, key, b);
                }
                return MediaStore.localFile(DiscussionActivity.this, key).getAbsolutePath();
            }
        }, new Async.UI<String>() {
            @Override public void on(String path, Exception err) {
                if (path == null) {
                    btn.setText(R.string.media_err);
                    return;
                }
                try {
                    MediaPlayer p = new MediaPlayer();
                    p.setDataSource(path);
                    p.prepare();
                    p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        @Override public void onCompletion(MediaPlayer mp) { stopPlayer(); }
                    });
                    p.start();
                    player = p;
                    btn.setText("\u25A0 " + fmtDuration(m.durationMs));
                } catch (Exception e) {
                    btn.setText(R.string.media_err);
                    playerBtn = null;
                    playerBase = null;
                }
            }
        });
    }

    private void showPhotoErr(ImageView img, TextView err) {
        img.setImageDrawable(null);
        err.setVisibility(View.VISIBLE);
        err.setText(R.string.media_err);
    }

    private void bindPhoto(final View root, final Message m) {
        final ImageView img = root.findViewById(R.id.photo);
        final TextView err = root.findViewById(R.id.photo_err);
        final String key = m.mediaKey;
        img.setTag(key);
        if (key == null || key.isEmpty()) {
            showPhotoErr(img, err);
            return;
        }
        if (MediaStore.exists(this, key)) {
            Bitmap b = frameFor(MediaStore.localFile(this, key), m);
            if (b != null) {
                img.setImageBitmap(b);
                err.setVisibility(View.GONE);
                return;
            }
            showPhotoErr(img, err);
            return;
        }
        Async.exec(this, new Async.Worker<Bitmap>() {
            @Override public Bitmap run() throws Exception {
                byte[] b = ApiClient.download("/api/media?key=" + key, session.token());
                if (b == null) return null;
                MediaStore.save(DiscussionActivity.this, key, b);
                return frameFor(MediaStore.localFile(DiscussionActivity.this, key), m);
            }
        }, new Async.UI<Bitmap>() {
            @Override public void on(Bitmap bmp, Exception e) {
                if (img.getTag() == null || !key.equals(img.getTag())) return;
                if (bmp != null) {
                    img.setImageBitmap(bmp);
                    err.setVisibility(View.GONE);
                } else {
                    showPhotoErr(img, err);
                }
            }
        });
    }

    private Bitmap frameFor(File f, Message m) {
        if ("video".equals(m.type)) {
            Bitmap b = videoFrame(f);
            if (b != null) return b;
        }
        return decodeSampled(f);
    }

    private Bitmap decodeSampled(File f) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        int inSampleSize = 1;
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        while (w > 0 && h > 0 && (w / inSampleSize > MAX_IMAGE_EDGE || h / inSampleSize > MAX_IMAGE_EDGE)) {
            inSampleSize *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        if (inSampleSize > 1) opts.inSampleSize = inSampleSize;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
    }

    private Bitmap videoFrame(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            return r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Exception ignored) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private void openVideo(String key) {
        if (key == null || key.isEmpty()) return;
        Intent i = new Intent(this, VideoPlayerActivity.class);
        i.putExtra("media_key", key);
        i.putExtra("conv_id", convId);
        startActivity(i);
    }

    private String display(Message m) {
        if (m.body != null && !m.body.isEmpty()) return m.body;
        return "[" + m.type + "]";
    }

    private class MessageAdapter extends BaseAdapter {
        private static final int TYPE_SYSTEM = 0;
        private static final int TYPE_ME_TEXT = 1;
        private static final int TYPE_OTHER_TEXT = 2;
        private static final int TYPE_ME_PHOTO = 3;
        private static final int TYPE_OTHER_PHOTO = 4;
        private static final int TYPE_ME_AUDIO = 5;
        private static final int TYPE_OTHER_AUDIO = 6;

        private List<Message> msgs = new ArrayList<Message>();

        void setList(List<Message> l) { msgs = l; }

        @Override public int getCount() { return msgs.size(); }
        @Override public Message getItem(int i) { return msgs.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 7; }

        @Override public int getItemViewType(int i) {
            Message m = getItem(i);
            if ("system".equals(m.type)) return TYPE_SYSTEM;
            boolean mine = isMine(m);
            if (m.mediaKey != null && "audio".equals(m.type)) {
                return mine ? TYPE_ME_AUDIO : TYPE_OTHER_AUDIO;
            }
            if (m.mediaKey != null) {
                return mine ? TYPE_ME_PHOTO : TYPE_OTHER_PHOTO;
            }
            return mine ? TYPE_ME_TEXT : TYPE_OTHER_TEXT;
        }

        private boolean isMine(Message m) {
            String me = session.userId();
            return me != null && me.equals(m.senderId);
        }

        @Override public View getView(int i, View convertView, ViewGroup parent) {
            Message m = getItem(i);
            int type = getItemViewType(i);
            int layout;
            switch (type) {
                case TYPE_SYSTEM: layout = R.layout.item_message_system; break;
                case TYPE_ME_TEXT: layout = R.layout.item_message_me; break;
                case TYPE_OTHER_TEXT: layout = R.layout.item_message_other; break;
                case TYPE_ME_PHOTO: layout = R.layout.item_message_photo_me; break;
                case TYPE_OTHER_PHOTO: layout = R.layout.item_message_photo_other; break;
                case TYPE_ME_AUDIO: layout = R.layout.item_message_audio_me; break;
                default: layout = R.layout.item_message_audio_other; break;
            }
            if (convertView == null) {
                convertView = LayoutInflater.from(DiscussionActivity.this).inflate(layout, parent, false);
            }
            switch (type) {
                case TYPE_ME_PHOTO:
                case TYPE_OTHER_PHOTO: {
                    final boolean isVideo = "video".equals(m.type);
                    ImageView photo = convertView.findViewById(R.id.photo);
                    if (isVideo) {
                        photo.setClickable(true);
                        photo.setFocusable(true);
                        photo.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) { openVideo(m.mediaKey); }
                        });
                    } else {
                        photo.setClickable(false);
                        photo.setOnClickListener(null);
                    }
                    bindPhoto(convertView, m);
                    break;
                }
                case TYPE_ME_AUDIO:
                case TYPE_OTHER_AUDIO: {
                    bindAudio(convertView.findViewById(R.id.btn_audio), m);
                    break;
                }
                default: {
                    TextView bubble = convertView.findViewById(R.id.msg_bubble);
                    bubble.setText(display(m));
                    break;
                }
            }
            return convertView;
        }
    }
}