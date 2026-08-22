package com.lightchat.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
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
import com.lightchat.models.Conversation;
import com.lightchat.models.Message;
import com.lightchat.net.ApiClient;
import com.lightchat.net.Realtime;
import com.lightchat.util.Async;
import com.lightchat.util.AvatarLoader;
import com.lightchat.util.Json;
import com.lightchat.util.MediaStore;
import com.lightchat.util.Presence;
import com.lightchat.util.Skin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;

public class DiscussionActivity extends Activity {
    private static final long POLL_FAST_MS = 2000L;
    private static final long POLL_SLOW_MS = 10000L;
    private static final int REQ_PHOTO = 1001;
    private static final int REQ_VIDEO = 1002;
    private static final int REQ_MIC = 1003;
    private static final long MAX_MEDIA_BYTES = 50L * 1024L * 1024L;
    private static final int MAX_IMAGE_EDGE = 1200;
    private static final String PREFS_LC = "lc_prefs";
    private static final String PREFS_MUTED = "muted_convs";
    private static final String PREFS_TITLES = "lc_convs";

    private static final int MODE_NONE = 0;
    private static final int MODE_REPLY = 1;
    private static final int MODE_EDIT = 2;
    private static final int MODE_SEARCH = 3;

    private SessionStore session;
    private String convId;
    private ListView list;
    private TextView status;
    private TextView muteBtn;
    private EditText input;
    private LinearLayout editBar;
    private TextView editLabel;
    private MessageAdapter adapter;
    private final Map<String, Message> msgMap = new LinkedHashMap<>();
    private final Handler handler = new Handler();
    private boolean alive = false;
    private boolean fetching = false;
    private boolean atBottom = true;

    private int barMode = MODE_NONE;
    private Message replyMsg;
    private Message editMsg;
    private String searchQuery;

    private View pinnedBanner;
    private TextView pinnedText;
    private View pinnedClose;
    private TextView typingBanner;
    private boolean typingShown = false;
    private String draftText = null;

    private String peerId = null;
    private int ephemeralTtl = 0;
    private boolean selecting = false;
    private final Set<String> selectedIds = new HashSet<String>();
    private View avatarDisc;
    private View discOnlineDot;
    private TextView presenceText;
    private View ephemeralBanner;
    private View selectionBar;
    private TextView selectionCount;

    private MediaPlayer player;
    private WaveView activeWave;
    private String playingId;
    private float lastProgress = 0f;
    private MediaRecorder recorder;
    private File voiceFile;
    private long recStartMs;

    private final LruCache<String, Bitmap> bmpCache = new LruCache<String, Bitmap>(64 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

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
        Skin.apply(this);

        TextView title = findViewById(R.id.txt_title);
        String passed = getIntent().getStringExtra("title");
        title.setText(passed != null ? passed : convId);

        status = findViewById(R.id.txt_disc_status);
        list = findViewById(R.id.msg_list);
        input = findViewById(R.id.input_msg);
        editBar = findViewById(R.id.edit_bar);
        editLabel = findViewById(R.id.edit_label);
        muteBtn = findViewById(R.id.btn_mute);
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

        applyGlass();

        pinnedBanner = findViewById(R.id.pinned_banner);
        pinnedText = (TextView) pinnedBanner.findViewById(R.id.pinned_text);
        pinnedClose = pinnedBanner.findViewById(R.id.pinned_close);
        typingBanner = (TextView) findViewById(R.id.typing_banner);
        pinnedClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { unpinAll(); }
        });
        loadDraft();
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {
                draftText = s.toString();
                ApiClient.typing(session.token(), convId, s.length() > 0);
                if (s.length() == 0) saveDraftAsync("");
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        });

        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendMessage(); }
        });
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        });
        findViewById(R.id.btn_plus).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseMedia(); }
        });
        findViewById(R.id.btn_emoji).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleEmojiRow(); }
        });
        findViewById(R.id.btn_sticker).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleStickerRow(); }
        });
        findViewById(R.id.btn_mic).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleMic(); }
        });
        findViewById(R.id.edit_cancel).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cancelBar(); }
        });
        findViewById(R.id.btn_search_disc).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSearchDialog(); }
        });
        muteBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleMute(); }
        });

        LinearLayout emojiRow = findViewById(R.id.emoji_container);
        String[] emojis = getResources().getStringArray(R.array.emoji_list);
        for (final String e : emojis) {
            TextView t = new TextView(this);
            t.setText(e);
            t.setTextSize(20);
            t.setPadding(dp(8), dp(4), dp(8), dp(4));
            t.setTextColor(Skin.palette().onSurface);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { insertEmoji(e); }
            });
            emojiRow.addView(t);
        }
        buildStickerRow();
        refreshMuteButton();
        setupSocial();
    }

    private void setupSocial() {
        avatarDisc = findViewById(R.id.avatar_disc);
        discOnlineDot = findViewById(R.id.disc_online_dot);
        presenceText = findViewById(R.id.txt_presence);
        ephemeralBanner = findViewById(R.id.ephemeral_banner);
        selectionBar = findViewById(R.id.selection_bar);
        selectionCount = findViewById(R.id.selection_count);

        if (convId != null && convId.startsWith("dm:")) {
            peerId = otherId(convId);
        }
        String passedTitle = getIntent().getStringExtra("title");
        String peerName = passedTitle != null ? passedTitle : (peerId != null ? peerId : convId);

        if (peerId != null && avatarDisc != null) {
            AvatarLoader.apply((TextView) avatarDisc, peerId, session.token(),
                    initialOf(peerName), Skin.palette().primary);
            refreshPresenceUi();
        }

        findViewById(R.id.btn_ephemeral).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseEphemeral(); }
        });

        selectionBar.findViewById(R.id.sel_copy).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copySelected(); }
        });
        selectionBar.findViewById(R.id.sel_forward).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { forwardSelected(); }
        });
        selectionBar.findViewById(R.id.sel_delete).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { deleteSelected(); }
        });
        selectionBar.findViewById(R.id.sel_close).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clearSelection(); }
        });

        int ttl = getIntent().getIntExtra("ephemeral", 0);
        if (ttl > 0) {
            ephemeralTtl = ttl;
            showEphemeralBanner();
        }
        if (peerId != null) loadLastSeen();
    }

    private String initialOf(String s) {
        if (s == null || s.isEmpty()) return "?";
        return s.substring(0, 1).toUpperCase(Locale.getDefault());
    }

    private void refreshPresenceUi() {
        if (discOnlineDot != null) discOnlineDot.setVisibility(View.GONE);
        if (presenceText != null) presenceText.setVisibility(View.GONE);
        if (peerId == null) return;
        if (Presence.isOnline(peerId)) {
            if (discOnlineDot != null) discOnlineDot.setVisibility(View.VISIBLE);
            if (presenceText != null) {
                presenceText.setText(R.string.online_now);
                presenceText.setVisibility(View.VISIBLE);
            }
        }
    }

    private void loadLastSeen() {
        if (peerId == null) return;
        Async.exec(this, new Async.Worker<Long>() {
            @Override public Long run() throws Exception {
                return ApiClient.lastSeen(session.token(), peerId);
            }
        }, new Async.UI<Long>() {
            @Override public void on(Long ts, Exception err) {
                refreshPresenceUi();
                if (ts == null || ts <= 0 || Presence.isOnline(peerId)) return;
                if (presenceText != null) {
                    presenceText.setText(getString(R.string.last_seen_fmt, fmtTime(ts)));
                    presenceText.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void showEphemeralBanner() {
        if (ephemeralBanner == null) return;
        ephemeralBanner.setVisibility(View.VISIBLE);
    }

    private void chooseEphemeral() {
        final int[] opts = {0, 1, 60, 3600, 86400, 604800};
        final String[] labels = {
                getString(R.string.ephemeral_off),
                getString(R.string.ephemeral_set, fmtDuration(1000)),
                getString(R.string.ephemeral_set, fmtDuration(60 * 1000L)),
                getString(R.string.ephemeral_set, fmtDuration(3600 * 1000L)),
                getString(R.string.ephemeral_set, fmtDuration(86400 * 1000L)),
                getString(R.string.ephemeral_set, fmtDuration(7L * 86400 * 1000L))
        };
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(R.string.ephemeral_prompt);
        b.setItems(labels, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                setEphemeral(opts[which]);
            }
        });
        b.show();
    }

    private void setEphemeral(final int ttl) {
        Async.exec(this, new Async.Worker<Boolean>() {
            @Override public Boolean run() throws Exception {
                return ApiClient.setEphemeral(session.token(), convId, ttl);
            }
        }, new Async.UI<Boolean>() {
            @Override public void on(Boolean ok, Exception err) {
                ephemeralTtl = ttl;
                if (ttl > 0) showEphemeralBanner();
                else if (ephemeralBanner != null) ephemeralBanner.setVisibility(View.GONE);
                if (peerId != null) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("type", "ephemeral");
                        o.put("conv_id", convId);
                        o.put("ttl", ttl);
                        Realtime.get().send(o.toString());
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private void applyGlass() {
        findViewById(R.id.header).setBackground(Skin.glassHeader(dp(24)));
        findViewById(R.id.input_bar).setBackground(Skin.glassCard(dp(24)));
        TextView title = findViewById(R.id.txt_title);
        title.setTextColor(Skin.palette().onSurface);
        muteBtn.setTextColor(Skin.palette().onSurface);
        findViewById(R.id.btn_search_disc).setBackground(Skin.pill_container(dp(18)));
        muteBtn.setBackground(Skin.pill_container(dp(18)));
        EditText in = findViewById(R.id.input_msg);
        in.setBackground(Skin.pill_input(20));
        Button send = findViewById(R.id.btn_send);
        send.setBackground(Skin.pill_primary(20));
        send.setTextColor(Skin.palette().onPrimary);
        LinearLayout editLabelL = (LinearLayout) editBar;
        editLabelL.setBackground(Skin.glassHeader(dp(14)));
        editLabel.setTextColor(Skin.palette().onSurface);
        findViewById(R.id.edit_cancel).setBackground(Skin.pill_container(dp(18)));
        ((TextView) findViewById(R.id.edit_cancel)).setTextColor(Skin.palette().onSurface);
        ((TextView) findViewById(R.id.txt_empty)).setTextColor(Skin.palette().onSurfaceVariant);
        int chipColor = Skin.palette().onSurface;
        ((TextView) findViewById(R.id.btn_plus)).setTextColor(chipColor);
        ((TextView) findViewById(R.id.btn_emoji)).setTextColor(chipColor);
        ((TextView) findViewById(R.id.btn_sticker)).setTextColor(chipColor);
        ((TextView) findViewById(R.id.btn_mic)).setTextColor(chipColor);
        findViewById(R.id.btn_plus).setBackground(Skin.pill_container(dp(18)));
        findViewById(R.id.btn_emoji).setBackground(Skin.pill_container(dp(18)));
        findViewById(R.id.btn_sticker).setBackground(Skin.pill_container(dp(18)));
        findViewById(R.id.btn_mic).setBackground(Skin.pill_container(dp(18)));
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.act_back_in, R.anim.act_back_out);
    }

    private void buildStickerRow() {
        try {
            String[] names = getAssets().list("stickers");
            if (names == null || names.length == 0) {
                findViewById(R.id.btn_sticker).setVisibility(View.GONE);
                return;
            }
            LinearLayout row = findViewById(R.id.sticker_container);
            int thumb = dp(64);
            for (int i = 0; i < names.length; i++) {
                final String name = names[i];
                Bitmap bmp = decodeStickerThumb(name);
                if (bmp == null) continue;
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(bmp);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setPadding(dp(4), dp(4), dp(4), dp(4));
                iv.setLayoutParams(new LinearLayout.LayoutParams(thumb, thumb));
                iv.setContentDescription(name);
                iv.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { sendSticker(name); }
                });
                row.addView(iv);
            }
        } catch (IOException e) {
            findViewById(R.id.btn_sticker).setVisibility(View.GONE);
        }
    }

    private Bitmap decodeStickerThumb(String name) {
        try {
            java.io.InputStream in = getAssets().open("stickers/" + name);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, bounds);
            in.close();
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 160) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            in = getAssets().open("stickers/" + name);
            try {
                return BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private void sendSticker(final String name) {
        final String token = session.token();
        Async.exec(this, new Async.Worker<Boolean>() {
            @Override public Boolean run() {
                try {
                    java.io.InputStream in = getAssets().open("stickers/" + name);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    try {
                        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                    } finally {
                        in.close();
                    }
                    ApiClient.ApiResponse up = ApiClient.upload("/api/upload?filename=" + name, bos.toByteArray(), "image/png", token);
                    if (up.status != 200) return false;
                    Map<String, Object> u = Json.parseObject(up.body);
                    String key = (String) u.get("key");
                    if (key == null || key.isEmpty()) return false;
                    String json = "{\"conv_id\":\"" + esc(convId) + "\",\"type\":\"sticker\",\"media_key\":\""
                            + esc(key) + "\",\"mime\":\"image/png\"}";
                    ApiClient.ApiResponse s = ApiClient.call("POST", "/api/send", json, token);
                    return s.status == 200;
                } catch (Exception e) {
                    return false;
                }
            }
        }, new Async.UI<Boolean>() {
            @Override public void on(Boolean ok, Exception err) {
                if (ok != null && ok) {
                    status.setVisibility(View.GONE);
                    findViewById(R.id.sticker_row).setVisibility(View.GONE);
                    handler.post(pollLoop);
                } else {
                    status.setVisibility(View.VISIBLE);
                    status.setText(R.string.sticker_send_err);
                }
            }
        });
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

    // ---------- Mute ----------

    private void toggleMute() {
        SharedPreferences sp = getSharedPreferences(PREFS_LC, MODE_PRIVATE);
        Set<String> muted = new HashSet<String>(sp.getStringSet(PREFS_MUTED, Collections.<String>emptySet()));
        if (muted.contains(convId)) {
            muted.remove(convId);
            sp.edit().putStringSet(PREFS_MUTED, muted).apply();
            refreshMuteButton();
            showStatus(getString(R.string.unmute_status));
        } else {
            muted.add(convId);
            sp.edit().putStringSet(PREFS_MUTED, muted).apply();
            refreshMuteButton();
            showStatus(getString(R.string.mute_status));
        }
    }

    public static boolean isMuted(android.content.Context ctx, String convId) {
        if (convId == null) return false;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_LC, ctx.MODE_PRIVATE);
        Set<String> muted = sp.getStringSet(PREFS_MUTED, null);
        return muted != null && muted.contains(convId);
    }

    private void refreshMuteButton() {
        if (muteBtn != null) {
            muteBtn.setText(isMuted(this, convId) ? getString(R.string.bell_off) : getString(R.string.bell_on));
        }
    }

    // ---------- Reply / Edit / Forward / Delete ----------

    private void showMessageActions(final Message m) {
        List<String> opts = new ArrayList<String>();
        final List<Integer> action = new ArrayList<Integer>();
        opts.add(getString(R.string.msg_reply));
        action.add(0);
        boolean mine = isMine(m);
        if (mine && !m.isDeleted() && ("text".equals(m.type) || "emoji".equals(m.type))) {
            opts.add(getString(R.string.msg_edit));
            action.add(1);
        }
        opts.add(getString(R.string.msg_delete));
        action.add(2);
        if (!m.isDeleted() && !"system".equals(m.type)) {
            opts.add(getString(R.string.msg_forward));
            action.add(3);
        }
        if (!m.isDeleted() && !"system".equals(m.type)) {
            opts.add(getString(R.string.msg_react));
            action.add(4);
        }
        if (!m.isDeleted() && !"system".equals(m.type)) {
            opts.add(getString(m.isPinned() ? R.string.msg_unpin : R.string.msg_pin));
            action.add(5);
        }
        new AlertDialog.Builder(this)
            .setTitle(null)
            .setItems(opts.toArray(new String[0]), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    int a = action.get(which);
                    if (a == 0) startReply(m);
                    else if (a == 1) startEdit(m);
                    else if (a == 2) confirmDelete(m);
                    else if (a == 3) forward(m);
                    else if (a == 4) showReactionPicker(m);
                    else doPin(m);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showReactionPicker(final Message m) {
        final String[] emojis = {"\uD83D\uDC4D", "\uD83D\uDE04", "\uD83D\uDE02", "\u2764\uFE0F", "\uD83D\uDE42", "\uD83D\uDE10", "\uD83D\uDE22", "\uD83D\uDE20"};
        final int[] idx = new int[1];
        final String[] labels = new String[emojis.length + 1];
        for (int i = 0; i < emojis.length; i++) labels[i] = emojis[i];
        labels[emojis.length] = getString(R.string.msg_clear_reaction);
        new AlertDialog.Builder(this)
            .setTitle(R.string.msg_react)
            .setSingleChoiceItems(labels, -1, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) { idx[0] = which; }
            })
            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    if (idx[0] < 0) return;
                    final String emoji = idx[0] < emojis.length ? emojis[idx[0]] : null;
                    final String token = session.token();
                    final String mid = m.id;
                    final String conv = convId;
                    Async.exec(DiscussionActivity.this, new Async.Worker<Boolean>() {
                        @Override public Boolean run() throws Exception {
                            ApiClient.ApiResponse r = ApiClient.reaction(token, mid, emoji == null ? "" : emoji);
                            return r.status == 200;
                        }
                    }, new Async.UI<Boolean>() {
                        @Override public void on(Boolean ok, Exception err) {
                            if (ok == null || !ok) showStatus(getString(R.string.send_error));
                        }
                    });
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void doPin(final Message m) {
        final String token = session.token();
        final String conv = convId;
        final boolean target = !m.isPinned();
        Async.exec(this, new Async.Worker<Boolean>() {
            @Override public Boolean run() throws Exception {
                ApiClient.ApiResponse r = ApiClient.pin(token, conv, m.id, target);
                return r.status == 200;
            }
        }, new Async.UI<Boolean>() {
            @Override public void on(Boolean ok, Exception err) {
                if (ok != null && ok) {
                    if (target) showPinnedBanner(m);
                    else hidePinnedBanner();
                } else {
                    showStatus(getString(R.string.send_error));
                }
            }
        });
    }

    // ---------- Draft / Pinned / Typing ----------

    private void loadDraft() {
        final String token = session.token();
        final String conv = convId;
        Async.exec(this, new Async.Worker<String>() {
            @Override public String run() {
                try {
                    ApiClient.ApiResponse r = ApiClient.getDraft(token, conv);
                    if (r.status != 200) return null;
                    Map<String, Object> m = Json.parseObject(r.body);
                    Object o = m.get("body");
                    return o == null ? null : String.valueOf(o);
                } catch (Exception e) {
                    return null;
                }
            }
        }, new Async.UI<String>() {
            @Override public void on(String body, Exception err) {
                if (body != null && !body.isEmpty()) {
                    draftText = body;
                    input.setText(body);
                    input.setSelection(input.getText().length());
                }
            }
        });
    }

    private void saveDraftAsync(final String body) {
        final String token = session.token();
        final String conv = convId;
        Async.exec(this, new Async.Worker<Boolean>() {
            @Override public Boolean run() {
                try {
                    ApiClient.ApiResponse r = ApiClient.saveDraft(token, conv, body);
                    return r.status == 200;
                } catch (Exception e) {
                    return false;
                }
            }
        }, null);
    }

    private void showPinnedBanner(Message m) {
        if (pinnedBanner == null) return;
        String preview = preview(m);
        pinnedText.setText(getString(R.string.pinned_message, preview));
        pinnedBanner.setVisibility(View.VISIBLE);
    }

    private void hidePinnedBanner() {
        if (pinnedBanner != null) pinnedBanner.setVisibility(View.GONE);
    }

    private void unpinAll() {
        final String token = session.token();
        final String conv = convId;
        Async.exec(this, new Async.Worker<Boolean>() {
            @Override public Boolean run() throws Exception {
                ApiClient.ApiResponse r = ApiClient.pin(token, conv, "", false);
                return r.status == 200;
            }
        }, new Async.UI<Boolean>() {
            @Override public void on(Boolean ok, Exception err) {
                if (ok != null && ok) hidePinnedBanner();
            }
        });
    }

    private void showTypingBanner(boolean show) {
        if (typingBanner == null) return;
        if (show) {
            typingShown = true;
            typingBanner.setVisibility(View.VISIBLE);
        }
        handler.removeCallbacks(hideTyping);
        handler.postDelayed(hideTyping, 4000L);
    }

    private final Runnable hideTyping = new Runnable() {
        @Override public void run() {
            typingShown = false;
            if (typingBanner != null) typingBanner.setVisibility(View.GONE);
        }
    };

    private void startReply(Message m) {
        cancelBar();
        barMode = MODE_REPLY;
        replyMsg = m;
        editBar.setVisibility(View.VISIBLE);
        editLabel.setText(getString(R.string.msg_reply_to, preview(m)));
    }

    private void startEdit(Message m) {
        cancelBar();
        barMode = MODE_EDIT;
        editMsg = m;
        editBar.setVisibility(View.VISIBLE);
        editLabel.setText(R.string.msg_editing);
        input.setText(m.body != null ? m.body : "");
        input.setSelection(input.getText().length());
        input.requestFocus();
    }

    private void cancelBar() {
        barMode = MODE_NONE;
        replyMsg = null;
        editMsg = null;
        searchQuery = null;
        editBar.setVisibility(View.GONE);
        if (barHadSearch) refresh();
        barHadSearch = false;
    }

    private boolean barHadSearch = false;

    private void confirmDelete(final Message m) {
        new AlertDialog.Builder(this)
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.msg_delete, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { doDelete(m); }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void doDelete(final Message m) {
        final String token = session.token();
        Async.exec(this, new Async.Worker<Boolean>() {
            @Override public Boolean run() throws Exception {
                String json = "{\"message_id\":\"" + esc(m.id) + "\"}";
                ApiClient.ApiResponse r = ApiClient.call("POST", "/api/messages/delete", json, token);
                return r.status == 200;
            }
        }, new Async.UI<Boolean>() {
            @Override public void on(Boolean ok, Exception err) {
                if (ok != null && ok) {
                    msgMap.remove(m.id);
                    refresh();
                } else {
                    showStatus(getString(R.string.send_error));
                }
            }
        });
    }

    private void forward(final Message m) {
        final String token = session.token();
        Async.exec(this, new Async.Worker<List<Conversation>>() {
            @Override public List<Conversation> run() {
                try {
                    ApiClient.ApiResponse c = ApiClient.call("GET", "/api/conversations", null, token);
                    if (c.status != 200) return null;
                    Map<String, Object> cm = Json.parseObject(c.body);
                    List<Object> arr = (List<Object>) cm.get("conversations");
                    List<Conversation> convs = new ArrayList<Conversation>();
                    if (arr != null) {
                        for (Object o : arr) {
                            @SuppressWarnings("unchecked") Map<String, Object> x = (Map<String, Object>) o;
                            Conversation cv = Conversation.fromJson(x);
                            if (!convId.equals(cv.convId)) convs.add(cv);
                        }
                    }
                    return convs;
                } catch (Exception e) {
                    return null;
                }
            }
        }, new Async.UI<List<Conversation>>() {
            @Override public void on(List<Conversation> convs, Exception err) {
                if (convs == null || convs.isEmpty()) {
                    showStatus(getString(R.string.msg_no_convs));
                    return;
                }
                final String[] labels = new String[convs.size()];
                for (int i = 0; i < convs.size(); i++) {
                    Conversation c = convs.get(i);
                    labels[i] = titleFor(c);
                }
                new AlertDialog.Builder(DiscussionActivity.this)
                    .setTitle(R.string.msg_forward_to)
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) {
                            doForward(m, convs.get(which));
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            }
        });
    }

    private String titleFor(Conversation c) {
        SharedPreferences sp = getSharedPreferences(PREFS_TITLES, MODE_PRIVATE);
        String t = sp.getString(c.convId, null);
        return (t == null || t.isEmpty()) ? getString(R.string.group) : t;
    }

    private void doForward(final Message m, final Conversation target) {
        final String token = session.token();
        Async.exec(this, new Async.Worker<Boolean>() {
            @Override public Boolean run() throws Exception {
                StringBuilder body = new StringBuilder();
                body.append("{\"conv_id\":\"").append(esc(target.convId))
                    .append("\",\"type\":\"").append(esc(m.type)).append("\"");
                if (m.mediaKey != null && !m.mediaKey.isEmpty()) {
                    body.append(",\"media_key\":\"").append(esc(m.mediaKey))
                        .append("\",\"mime\":\"").append(esc(m.mime != null ? m.mime : "application/octet-stream")).append("\"");
                    if (m.durationMs > 0) body.append(",\"duration_ms\":").append(m.durationMs);
                } else {
                    body.append(",\"body\":\"").append(esc(m.body != null ? m.body : "")).append("\"");
                }
                body.append("}");
                ApiClient.ApiResponse r = ApiClient.call("POST", "/api/send", body.toString(), token);
                return r.status == 200;
            }
        }, new Async.UI<Boolean>() {
            @Override public void on(Boolean ok, Exception err) {
                showStatus(ok != null && ok ? getString(R.string.msg_forwarded) : getString(R.string.send_error));
            }
        });
    }

    // ---------- Search ----------

    private void showSearchDialog() {
        final EditText q = new EditText(this);
        q.setSingleLine();
        q.setHint(R.string.search_disc_hint);
        q.setTextColor(getResources().getColor(R.color.on_surface));
        q.setHintTextColor(getResources().getColor(R.color.on_surface_variant));
        q.setPadding(dp(12), dp(8), dp(12), dp(8));
        new AlertDialog.Builder(this)
            .setTitle(R.string.disc_search)
            .setView(q)
            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { applySearch(q.getText().toString().trim()); }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void applySearch(String qtext) {
        if (qtext.isEmpty()) {
            if (barMode == MODE_SEARCH) {
                cancelBar();
                return;
            }
            return;
        }
        barHadSearch = barMode == MODE_SEARCH;
        barMode = MODE_SEARCH;
        replyMsg = null;
        editMsg = null;
        searchQuery = qtext.toLowerCase(Locale.ROOT);
        editBar.setVisibility(View.VISIBLE);
        editLabel.setText(getString(R.string.disc_search) + "  " + qtext);
        refresh();
        if (adapter.getCount() == 0) showStatus(getString(R.string.search_disc_empty));
    }

    // ---------- Media ----------

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
        boolean show = row.getVisibility() != View.VISIBLE;
        row.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) findViewById(R.id.sticker_row).setVisibility(View.GONE);
    }

    private void toggleStickerRow() {
        View row = findViewById(R.id.sticker_row);
        boolean show = row.getVisibility() != View.VISIBLE;
        row.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) findViewById(R.id.emoji_row).setVisibility(View.GONE);
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

    // ---------- Send text / edit ----------

    private void sendMessage() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (barMode == MODE_EDIT && editMsg != null) {
            editMessage(editMsg, text);
            return;
        }
        input.setText("");
        final String replyId = (barMode == MODE_REPLY && replyMsg != null) ? replyMsg.id : null;
        if (replyId != null) cancelBar();
        StringBuilder json = new StringBuilder();
        json.append("{\"conv_id\":\"").append(esc(convId))
            .append("\",\"type\":\"text\",\"body\":\"").append(esc(text)).append("\"");
        if (replyId != null) {
            json.append(",\"reply_to_id\":\"").append(esc(replyId)).append("\"");
        }
        json.append("}");
        Async.exec(this, new Async.Worker<ApiClient.ApiResponse>() {
            @Override public ApiClient.ApiResponse run() throws Exception {
                return ApiClient.call("POST", "/api/send", json.toString(), session.token());
            }
        }, new Async.UI<ApiClient.ApiResponse>() {
            @Override public void on(ApiClient.ApiResponse resp, Exception err) {
                if (err != null || resp == null || resp.status != 200) {
                    status.setVisibility(View.VISIBLE);
                    status.setText(R.string.send_error);
                    input.setText(text);
                    return;
                }
                mergeSendPayload(resp.body);
                status.setVisibility(View.GONE);
                if (draftText != null && draftText.length() > 0) {
                    draftText = null;
                    saveDraftAsync("");
                }
                handler.post(pollLoop);
            }
        });
    }

    private void mergeSendPayload(String body) {
        try {
            Map<String, Object> map = Json.parseObject(body);
            Object mo = map.get("message");
            if (!(mo instanceof Map)) return;
            @SuppressWarnings("unchecked") Map<String, Object> mm = (Map<String, Object>) mo;
            Message msg = Message.fromJson(mm);
            if (msg.id != null) {
                msgMap.put(msg.id, msg);
                refresh();
            }
        } catch (Exception ignored) {
        }
    }

    private void editMessage(final Message m, final String text) {
        final String token = session.token();
        final String json = "{\"message_id\":\"" + esc(m.id) + "\",\"body\":\"" + esc(text) + "\"}";
        Async.exec(this, new Async.Worker<ApiClient.ApiResponse>() {
            @Override public ApiClient.ApiResponse run() throws Exception {
                return ApiClient.call("POST", "/api/messages/edit", json, token);
            }
        }, new Async.UI<ApiClient.ApiResponse>() {
            @Override public void on(ApiClient.ApiResponse resp, Exception err) {
                if (err != null || resp == null || resp.status != 200) {
                    status.setVisibility(View.VISIBLE);
                    status.setText(R.string.send_error);
                    return;
                }
                try {
                    Map<String, Object> map = Json.parseObject(resp.body);
                    Object mo = map.get("message");
                    if (mo instanceof Map) {
                        @SuppressWarnings("unchecked") Map<String, Object> mm = (Map<String, Object>) mo;
                        Message updated = Message.fromJson(mm);
                        if (updated.id != null) msgMap.put(updated.id, updated);
                    }
                } catch (Exception ignored) {
                }
                input.setText("");
                cancelBar();
                status.setVisibility(View.GONE);
                refresh();
            }
        });
    }

    // ---------- Push ----------

    private void handlePush(String jsonText) {
        try {
            Map<String, Object> m = Json.parseObject(jsonText);
            String type = (String) m.get("type");
            if ("message".equals(type)) {
                Object mo = m.get("message");
                if (!(mo instanceof Map)) return;
                @SuppressWarnings("unchecked") Map<String, Object> mm = (Map<String, Object>) mo;
                Message msg = Message.fromJson(mm);
                if (msg.id == null || !convId.equals(msg.convId)) return;
                if (msg.isDeleted()) {
                    msgMap.remove(msg.id);
                } else {
                    msgMap.put(msg.id, msg);
                }
                refresh();
            } else if ("message_edit".equals(type)) {
                Object mo = m.get("message");
                if (!(mo instanceof Map)) return;
                @SuppressWarnings("unchecked") Map<String, Object> mm = (Map<String, Object>) mo;
                Message msg = Message.fromJson(mm);
                if (msg.id == null || !convId.equals(msg.convId)) return;
                msgMap.put(msg.id, msg);
                refresh();
            } else if ("message_delete".equals(type)) {
                String mid = (String) m.get("message_id");
                if (mid != null && convId.equals(m.get("conv_id"))) {
                    msgMap.remove(mid);
                    refresh();
                }
            } else if ("message_pin".equals(type)) {
                Object mo = m.get("message");
                if (!(mo instanceof Map)) return;
                @SuppressWarnings("unchecked") Map<String, Object> mm = (Map<String, Object>) mo;
                Message msg = Message.fromJson(mm);
                if (msg.id == null || !convId.equals(msg.convId)) return;
                msgMap.put(msg.id, msg);
                refresh();
                if (msg.isPinned()) showPinnedBanner(msg);
            } else if ("message_reaction".equals(type)) {
                String mid = (String) m.get("message_id");
                if (mid == null || !convId.equals(m.get("conv_id"))) return;
                Message cur = msgMap.get(mid);
                if (cur == null) return;
                @SuppressWarnings("unchecked") List<Object> rl = (List<Object>) m.get("reactions");
                List<String> rx = new ArrayList<String>();
                if (rl != null) {
                    for (Object o : rl) {
                        if (o instanceof Map) {
                            Object e = ((Map<String, Object>) o).get("emoji");
                            if (e != null) rx.add(String.valueOf(e));
                        }
                    }
                }
                msgMap.put(mid, cur.withReactions(rx));
                refresh();
            } else if ("typing".equals(type)) {
                if (!convId.equals(m.get("conv_id"))) return;
                String who = (String) m.get("user_id");
                if (who != null && !who.equals(session.userId())) showTypingBanner(true);
            } else if ("read".equals(type)) {
                if (!convId.equals(m.get("conv_id"))) return;
                long upTo = toLong(m.get("up_to"));
                String reader = (String) m.get("user_id");
                boolean changed = false;
                List<Message> vals = new ArrayList<Message>(msgMap.values());
                for (Message cur : vals) {
                    if (cur.mine(session.userId())
                            && cur.createdAt <= upTo
                            && !"read".equals(cur.status)
                            && reader != null) {
                        msgMap.put(cur.id, cur.withStatus("read"));
                        changed = true;
                    }
                }
                if (changed) refresh();
            } else if ("presence".equals(type)) {
                String who = (String) m.get("user_id");
                if (who != null) {
                    boolean online = Boolean.TRUE.equals(m.get("online"));
                    Presence.set(who, online);
                    if (who.equals(peerId)) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() { refreshPresenceUi(); }
                        });
                    }
                }
            } else if ("ephemeral".equals(type)) {
                if (convId.equals(m.get("conv_id"))) {
                    int ttl = toInt(m.get("ttl"));
                    ephemeralTtl = ttl;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (ttl > 0) showEphemeralBanner();
                            else if (ephemeralBanner != null) ephemeralBanner.setVisibility(View.GONE);
                        }
                    });
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static int toInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        return 0;
    }

    private static long toLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        return 0L;
    }

    // ---------- Fetch ----------

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
                                if (msg.id != null) {
                                    if (msg.isDeleted()) {
                                        msgMap.remove(msg.id);
                                    } else {
                                        msgMap.put(msg.id, msg);
                                    }
                                }
                            }
                        }
                        refresh();
                        status.setVisibility(View.GONE);
                        if (atBottom) markRead();
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

    private void markRead() {
        final String token = session.token();
        final String json = "{\"conv_id\":\"" + esc(convId) + "\"}";
        Async.exec(this, new Async.Worker<Void>() {
            @Override public Void run() throws Exception {
                ApiClient.call("POST", "/api/messages/read", json, token);
                return null;
            }
        }, new Async.UI<Void>() {
            @Override public void on(Void v, Exception err) {
            }
        });
    }

    // ---------- List ----------

    private void jumpToMessage(String id) {
        int pos = adapter.indexOf(id);
        if (pos >= 0) {
            list.setSelection(pos);
            Message target = adapter.getItem(pos);
            if (target != null) flash(target.id);
        }
    }

    private void flash(String id) {
        View v = list.getChildAt(adapter.indexOf(id) - list.getFirstVisiblePosition());
        if (v != null) v.startAnimation(AnimationUtils.loadAnimation(this, R.anim.scale_in));
    }

    private void refresh() {
        List<Message> all = new ArrayList<Message>(msgMap.values());
        Collections.sort(all, new Comparator<Message>() {
            @Override public int compare(Message a, Message b) {
                if (a.createdAt != b.createdAt) return Long.compare(a.createdAt, b.createdAt);
                String ai = a.id == null ? "" : a.id;
                String bi = b.id == null ? "" : b.id;
                return ai.compareTo(bi);
            }
        });
        List<Message> shown = all;
        if (barMode == MODE_SEARCH && searchQuery != null && !searchQuery.isEmpty()) {
            shown = new ArrayList<Message>();
            for (Message cur : all) {
                if (matchesSearch(cur)) shown.add(cur);
            }
        }
        adapter.setList(shown);
        adapter.notifyDataSetChanged();
        if (atBottom && shown.size() > 0) list.smoothScrollToPosition(shown.size() - 1);
    }

    private boolean matchesSearch(Message m) {
        String d = display(m).toLowerCase(Locale.ROOT);
        return d.contains(searchQuery);
    }

    private void showStatus(String s) {
        status.setVisibility(View.VISIBLE);
        status.setText(s);
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

    private static String fmtTime(long ts) {
        java.text.DateFormat df = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT);
        return df.format(new java.util.Date(ts));
    }

    private String otherId(String convId) {
        if (convId == null || !convId.startsWith("dm:")) return null;
        String[] parts = convId.split(":", 2);
        if (parts.length < 2) return null;
        String[] ids = parts[1].split("\\+");
        String me = session.userId();
        for (String id : ids) {
            if (!id.equals(me)) return id;
        }
        return ids.length > 0 ? ids[0] : null;
    }

    private void onRowTap(Message m) {
        if (!selecting) return;
        if (selectedIds.contains(m.id)) selectedIds.remove(m.id);
        else selectedIds.add(m.id);
        adapter.notifyDataSetChanged();
        updateSelectionBar();
    }

    private void onRowLongPress(Message m) {
        if (!selecting) {
            selecting = true;
            selectedIds.clear();
        }
        if (selectedIds.contains(m.id)) selectedIds.remove(m.id);
        else selectedIds.add(m.id);
        adapter.notifyDataSetChanged();
        updateSelectionBar();
    }

    private void updateSelectionBar() {
        if (selectionBar == null) return;
        if (!selecting || selectedIds.isEmpty()) {
            selectionBar.setVisibility(View.GONE);
            return;
        }
        selectionBar.setVisibility(View.VISIBLE);
        if (selectionCount != null) {
            selectionCount.setText(getString(R.string.selection_count, selectedIds.size()));
        }
    }

    private void clearSelection() {
        selecting = false;
        selectedIds.clear();
        adapter.notifyDataSetChanged();
        updateSelectionBar();
    }

    private void copySelected() {
        StringBuilder sb = new StringBuilder();
        for (Message m : msgMap.values()) {
            if (selectedIds.contains(m.id)) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(m.body != null ? m.body : "");
            }
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setText(sb.toString());
        clearSelection();
    }

    private void forwardSelected() {
        final List<Message> msgs = new ArrayList<Message>();
        for (Message m : msgMap.values()) {
            if (selectedIds.contains(m.id) && !"system".equals(m.type)) msgs.add(m);
        }
        if (msgs.isEmpty()) {
            clearSelection();
            return;
        }
        final String token = session.token();
        Async.exec(this, new Async.Worker<List<Conversation>>() {
            @Override public List<Conversation> run() {
                try {
                    ApiClient.ApiResponse c = ApiClient.call("GET", "/api/conversations", null, token);
                    if (c.status != 200) return null;
                    Map<String, Object> cm = Json.parseObject(c.body);
                    List<Object> arr = (List<Object>) cm.get("conversations");
                    List<Conversation> convs = new ArrayList<Conversation>();
                    if (arr != null) {
                        for (Object o : arr) {
                            @SuppressWarnings("unchecked") Map<String, Object> x = (Map<String, Object>) o;
                            Conversation cv = Conversation.fromJson(x);
                            if (!convId.equals(cv.convId)) convs.add(cv);
                        }
                    }
                    return convs;
                } catch (Exception e) {
                    return null;
                }
            }
        }, new Async.UI<List<Conversation>>() {
            @Override public void on(List<Conversation> convs, Exception err) {
                if (convs == null || convs.isEmpty()) {
                    showStatus(getString(R.string.msg_no_convs));
                    clearSelection();
                    return;
                }
                final String[] labels = new String[convs.size()];
                for (int i = 0; i < convs.size(); i++) labels[i] = titleFor(convs.get(i));
                new AlertDialog.Builder(DiscussionActivity.this)
                        .setTitle(R.string.msg_forward_to)
                        .setItems(labels, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                for (Message m : msgs) doForward(m, convs.get(which));
                                clearSelection();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { clearSelection(); }
                        })
                        .show();
            }
        });
    }

    private void deleteSelected() {
        final List<String> ids = new ArrayList<String>();
        for (Message m : msgMap.values()) if (selectedIds.contains(m.id)) ids.add(m.id);
        if (ids.isEmpty()) return;
        Async.exec(this, new Async.Worker<Boolean>() {
            @Override public Boolean run() throws Exception {
                boolean ok = true;
                for (String id : ids) {
                    if (!ApiClient.deleteMessage(session.token(), id)) ok = false;
                }
                return ok;
            }
        }, new Async.UI<Boolean>() {
            @Override public void on(Boolean ok, Exception err) {
                for (String id : ids) msgMap.remove(id);
                clearSelection();
                runOnUiThread(new Runnable() {
                    @Override public void run() { refresh(); }
                });
            }
        });
    }

    private void stopPlayer() {
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try { p.stop(); } catch (Exception ignored) {}
            try { p.release(); } catch (Exception ignored) {}
        }
        handler.removeCallbacks(waveTicker);
        if (activeWave != null) activeWave.setProgress(0f);
        activeWave = null;
        playingId = null;
        lastProgress = 0f;
    }

    private void bindAudio(final View root, final Message m) {
        final TextView btn = root.findViewById(R.id.btn_audio);
        final WaveView wave = root.findViewById(R.id.wave);
        final TextView time = root.findViewById(R.id.audio_time);
        if (wave != null) wave.setSeed(hashSeed(m.id));
        if (time != null) time.setText(fmtDuration(m.durationMs));
        if (wave != null) wave.setProgress(m.id.equals(playingId) ? lastProgress : 0f);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { playAudio(m, wave); }
        });
    }

    private void playAudio(final Message m, final WaveView wave) {
        if (player != null && player.isPlaying() && m.id.equals(playingId)) {
            stopPlayer();
            return;
        }
        if (player != null && player.isPlaying()) {
            stopPlayer();
        }
        final String key = m.mediaKey;
        if (key == null || key.isEmpty()) return;
        activeWave = wave;
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
                if (path == null) return;
                try {
                    MediaPlayer p = new MediaPlayer();
                    p.setDataSource(path);
                    p.prepare();
                    final long dur = p.getDuration();
                    playingId = m.id;
                    p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        @Override public void onCompletion(MediaPlayer mp) { stopPlayer(); }
                    });
                    p.start();
                    player = p;
                    handler.removeCallbacks(waveTicker);
                    handler.post(waveTicker);
                } catch (Exception e) {
                }
            }
        });
    }

    private final Runnable waveTicker = new Runnable() {
        @Override public void run() {
            if (player != null && player.isPlaying() && activeWave != null) {
                try {
                    long d = player.getDuration();
                    long c = player.getCurrentPosition();
                    float pr = d > 0 ? (float) c / d : 0f;
                    lastProgress = pr;
                    activeWave.setProgress(pr);
                } catch (Exception ignored) {}
                handler.postDelayed(this, 200L);
            }
        }
    };

    private long hashSeed(String id) {
        long h = 0;
        if (id != null) for (int i = 0; i < id.length(); i++) h = 31 * h + id.charAt(i);
        return h == 0 ? 1 : h;
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
        errorInvalidate(img, err);
        if (key == null || key.isEmpty()) {
            showPhotoErr(img, err);
            return;
        }
        Bitmap cached = bmpCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            img.setImageBitmap(cached);
            err.setVisibility(View.GONE);
            return;
        }
        Async.exec(this, new Async.Worker<Bitmap>() {
            @Override public Bitmap run() throws Exception {
                File f = MediaStore.localFile(DiscussionActivity.this, key);
                if (!f.exists()) {
                    byte[] b = ApiClient.download("/api/media?key=" + key, session.token());
                    if (b == null) return null;
                    MediaStore.save(DiscussionActivity.this, key, b);
                }
                return frameFor(f, m);
            }
        }, new Async.UI<Bitmap>() {
            @Override public void on(Bitmap bmp, Exception e) {
                if (img.getTag() == null || !key.equals(img.getTag())) return;
                if (bmp != null && !bmp.isRecycled()) {
                    bmpCache.put(key, bmp);
                    img.setImageBitmap(bmp);
                    err.setVisibility(View.GONE);
                } else {
                    showPhotoErr(img, err);
                }
            }
        });
    }

    private void errorInvalidate(ImageView img, TextView err) {
        img.setImageDrawable(null);
        err.setVisibility(View.GONE);
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

    private String preview(Message m) {
        if (m == null) return "";
        if ("photo".equals(m.type)) return getString(R.string.notif_photo);
        if ("video".equals(m.type)) return getString(R.string.notif_video);
        if ("audio".equals(m.type)) return getString(R.string.notif_audio);
        return display(m);
    }

    private boolean isMine(Message m) {
        return m.mine(session.userId());
    }

    // ---------- Adapter ----------

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

        int indexOf(String id) {
            for (int i = 0; i < msgs.size(); i++) {
                if (id.equals(msgs.get(i).id)) return i;
            }
            return -1;
        }

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

        @Override public View getView(int i, View convertView, ViewGroup parent) {
            final Message m = getItem(i);
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
                convertView.startAnimation(AnimationUtils.loadAnimation(DiscussionActivity.this, R.anim.scale_in));
            }
            convertView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (!"system".equals(m.type)) {
                        if (selecting) onRowLongPress(m);
                        else showMessageActions(m);
                    }
                    return true;
                }
            });
            convertView.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (selecting) onRowTap(m);
                }
            });
            switch (type) {
                case TYPE_ME_PHOTO:
                case TYPE_OTHER_PHOTO: {
                    final boolean isVideo = "video".equals(m.type);
                    ImageView photo = convertView.findViewById(R.id.photo);
                    photo.setBackground(Skin.mediaRounded(isMine(m) ? Skin.palette().bubbleMine : Skin.palette().bubbleOther));
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
                    TextView ab = convertView.findViewById(R.id.btn_audio);
                    boolean mineA = type == TYPE_ME_AUDIO;
                    ab.setBackground(Skin.bubble(mineA));
                    ab.setTextColor(mineA ? Skin.palette().onPrimary : Skin.palette().onSurface);
                    bindAudio(convertView, m);
                    break;
                }
                default: {
                    TextView bubble = convertView.findViewById(R.id.bubble_text);
                    bubble.setText(display(m));
                    bubble.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
                    bubble.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                    break;
                }
            }
            applyBubble(convertView, type);
            bindCommon(convertView, m);
            return convertView;
        }
    }

    private void applyBubble(View root, int type) {
        if (type == MessageAdapter.TYPE_SYSTEM) return;
        View plate = root.findViewById(R.id.msg_bubble);
        if (plate != null) {
            boolean mine = type == MessageAdapter.TYPE_ME_TEXT
                    || type == MessageAdapter.TYPE_ME_PHOTO
                    || type == MessageAdapter.TYPE_ME_AUDIO;
            plate.setBackground(Skin.bubble(mine));
        }
        TextView text = root.findViewById(R.id.bubble_text);
        if (text != null) {
            boolean mine = type == MessageAdapter.TYPE_ME_TEXT;
            text.setTextColor(mine ? Skin.palette().onPrimary : Skin.palette().onSurface);
        }
    }

    private void bindCommon(View root, Message m) {
        if (selecting && selectedIds.contains(m.id)) {
            int c = Skin.palette().primary;
            root.setBackgroundColor(android.graphics.Color.argb(0x33,
                    android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c)));
        } else {
            root.setBackgroundResource(android.R.color.transparent);
        }
        TextView rt = root.findViewById(R.id.msg_reactions);
        if (rt != null) {
            if (m.reactions != null && !m.reactions.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                Map<String, Integer> counts = new java.util.LinkedHashMap<>();
                for (String e : m.reactions) {
                    Integer c = counts.get(e);
                    counts.put(e, c == null ? 1 : c + 1);
                }
                for (Map.Entry<String, Integer> en : counts.entrySet()) {
                    if (sb.length() > 0) sb.append("  ");
                    sb.append(en.getKey());
                    if (en.getValue() > 1) sb.append(" ").append(en.getValue());
                }
                rt.setText(sb.toString());
                rt.setTextColor(Skin.palette().onSurface);
                rt.setVisibility(View.VISIBLE);
            } else {
                rt.setVisibility(View.GONE);
            }
        }
        TextView reply = root.findViewById(R.id.reply_preview);
        if (reply != null) {
            Message quoted = m.replyToId != null ? msgMap.get(m.replyToId) : null;
            if (quoted != null && !quoted.isDeleted()) {
                reply.setText(preview(quoted));
                reply.setVisibility(View.VISIBLE);
                reply.setTextColor(isMine(m) ? Skin.palette().onSurfaceVariant : Skin.palette().onSurface);
                final String jumpId = quoted.id;
                reply.setClickable(true);
                reply.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { jumpToMessage(jumpId); }
                });
            } else {
                reply.setVisibility(View.GONE);
                reply.setClickable(false);
                reply.setOnClickListener(null);
            }
        }
        TextView meta = root.findViewById(R.id.msg_meta);
        if (meta != null) {
            meta.setTextColor(Skin.palette().onSurfaceVariant);
            if (m.edited > 0L && !m.isDeleted()) {
                meta.setText(R.string.edited_marker);
                meta.setVisibility(View.VISIBLE);
            } else {
                meta.setVisibility(View.GONE);
            }
        }
        TextView st = root.findViewById(R.id.msg_status);
        if (st != null && isMine(m)) {
            String stTxt;
            if ("read".equals(m.status)) {
                stTxt = "\u2713\u2713";
                st.setTextColor(Skin.palette().secondary);
            } else if ("delivered".equals(m.status)) {
                stTxt = "\u2713\u2713";
                st.setTextColor(Skin.palette().onSurfaceVariant);
            } else {
                stTxt = "\u2713";
                st.setTextColor(Skin.palette().onSurfaceVariant);
            }
            st.setText(stTxt);
            st.setVisibility(View.VISIBLE);
        } else if (st != null) {
            st.setVisibility(View.GONE);
        }
    }
}