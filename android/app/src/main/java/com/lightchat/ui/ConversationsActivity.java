package com.lightchat.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.lightchat.R;
import com.lightchat.SessionStore;
import com.lightchat.net.Realtime;
import com.lightchat.net.RealtimeService;
import com.lightchat.models.Conversation;
import com.lightchat.net.ApiClient;
import com.lightchat.util.Async;
import com.lightchat.util.Fmt;
import com.lightchat.util.Json;
import com.lightchat.util.MediaStore;
import com.lightchat.util.AvatarLoader;
import com.lightchat.util.Presence;
import com.lightchat.util.Skin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class ConversationsActivity extends Activity {
    private SessionStore session;
    private FrameLayout content;
    private View chatFrame;
    private ListView list;
    private TextView status;
    private ConversationAdapter adapter;
    private final List<Conversation> items = new ArrayList<Conversation>();
    private final Map<String, String> friendNames = new HashMap<String, String>();
    private final Map<String, String> friendColors = new HashMap<String, String>();
    private final Set<String> friendIds = new HashSet<String>();

    private View friendsView;
    private final List<Map<String, Object>> pendingItems = new ArrayList<Map<String, Object>>();
    private final List<Map<String, Object>> friendItems = new ArrayList<Map<String, Object>>();
    private PendingAdapter pendingAdapter;
    private FriendAdapter friendAdapter;

    private View searchView;
    private final List<Map<String, Object>> resultItems = new ArrayList<Map<String, Object>>();
    private final Set<String> invitedUsernames = new HashSet<String>();
    private SearchAdapter searchAdapter;
    private TextView searchStatus;

    private View placeholder;

    private static final int REQ_NOTIF = 2001;
    private static final int REQ_STORAGE = 2002;
    private static final int REQ_AVATAR = 2003;
    private static boolean notifAsked = false;

    private View settingsView;
    private TextView settingsStatus;

    private List<Conversation> pendingConvs;
    private Map<String, String> pendingNames;
    private Map<String, String> pendingColors;
    private Set<String> pendingIds;

    private final Map<String, Long> typingUntil = new HashMap<String, Long>();
    private final Handler listHandler = new Handler();

    private final Realtime.Listener rt = new Realtime.Listener() {
        @Override public void onMessage(String json) { handleRealtime(json); }
        @Override public void onState(boolean open) { }
    };

    private void handleRealtime(String json) {
        try {
            Map<String, Object> m = Json.parseObject(json);
            String type = (String) m.get("type");
            String convId = (String) m.get("conv_id");
            if ("presence".equals(type)) {
                String uid = (String) m.get("user_id");
                Boolean on = Boolean.valueOf(String.valueOf(m.get("online")));
                if (uid != null) {
                    Presence.set(uid, on);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            adapter.notifyDataSetChanged();
                            if (friendAdapter != null) friendAdapter.notifyDataSetChanged();
                            if (searchAdapter != null) searchAdapter.notifyDataSetChanged();
                            if (pendingAdapter != null) pendingAdapter.notifyDataSetChanged();
                        }
                    });
                }
                return;
            }
            if ("typing".equals(type) && convId != null) {
                String who = (String) m.get("user_id");
                if (who != null && !who.equals(session.userId())) {
                    typingUntil.put(convId, System.currentTimeMillis() + 4000L);
                    scheduleTypingClear(convId);
                    runOnUiThread(new Runnable() {
                        @Override public void run() { adapter.notifyDataSetChanged(); }
                    });
                }
                return;
            }
            if ("message".equals(type)) {
                Object mo = m.get("message");
                if (mo instanceof Map) {
                    Object cid = ((Map<String, Object>) mo).get("conv_id");
                    if (cid != null) typingUntil.remove(String.valueOf(cid));
                }
            }
        } catch (Exception ignored) {
        }
        reload();
    }

    private void scheduleTypingClear(final String convId) {
        listHandler.postDelayed(new Runnable() {
            @Override public void run() {
                Long until = typingUntil.get(convId);
                if (until != null && until <= System.currentTimeMillis()) {
                    typingUntil.remove(convId);
                    adapter.notifyDataSetChanged();
                }
            }
        }, 4200L);
    }

    private boolean isTyping(String convId) {
        Long until = typingUntil.get(convId);
        return until != null && until > System.currentTimeMillis();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = new SessionStore(this);
        if (!session.hasSession()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_conversations);
        Skin.apply(this);

        content = findViewById(R.id.content);
        chatFrame = findViewById(R.id.chat_frame);
        status = findViewById(R.id.txt_status);
        list = findViewById(R.id.list_conversations);
        adapter = new ConversationAdapter();
        list.setAdapter(adapter);
        list.setLayoutAnimation(AnimationUtils.loadLayoutAnimation(this, R.anim.list_layout));
        list.setEmptyView(findViewById(R.id.txt_empty));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Conversation c = items.get(pos);
                Intent i = new Intent(ConversationsActivity.this, DiscussionActivity.class);
                i.putExtra("conv_id", c.convId);
                i.putExtra("title", titleFor(c));
                i.putExtra("ephemeral", c.ephemeralTtl);
                startActivity(i);
                overridePendingTransition(R.anim.act_fwd_in, R.anim.act_fwd_out);
            }
        });

        applyShellStyle();

        findViewById(R.id.btn_new_chat).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickNewChat(); }
        });
        findViewById(R.id.tab_chats).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showTab(chatFrame);
                setNavSelection((TextView) v);
                reload();
            }
        });
        findViewById(R.id.tab_friends).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showFriendsTab(v); }
        });
        findViewById(R.id.tab_search).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSearchTab(v); }
        });
        findViewById(R.id.tab_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettingsTab(v); }
        });

        findViewById(R.id.txt_logout).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                RealtimeService.stop(ConversationsActivity.this);
                Realtime.get().stop();
                session.clear();
                Intent i = new Intent(ConversationsActivity.this, LoginActivity.class);
                startActivity(i);
                overridePendingTransition(R.anim.act_back_in, R.anim.act_back_out);
                finish();
            }
        });

        showTab(chatFrame);
        setNavSelection(findViewById(R.id.tab_chats));

        RealtimeService.start(this);
        maybeRequestNotifyPermission();
    }

    private void applyShellStyle() {
        findViewById(R.id.header).setBackground(Skin.glassHeader(dp(24)));
        findViewById(R.id.bottom_nav).setBackground(Skin.glassPill(dp(30)));
        TextView fab = findViewById(R.id.btn_new_chat);
        fab.setBackground(Skin.pill_primary(dp(29)));
        fab.setTextColor(Skin.palette().onPrimary);
        TextView logout = findViewById(R.id.txt_logout);
        logout.setTextColor(Skin.palette().secondary);
    }

    private void pickNewChat() {
        if (friendItems.isEmpty()) {
            showStatus(getString(R.string.search_prompt));
            return;
        }
        final String[] labels = new String[friendItems.size()];
        for (int i = 0; i < friendItems.size(); i++) labels[i] = fullName(friendItems.get(i));
        new AlertDialog.Builder(this)
            .setTitle(R.string.msg_forward_to)
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    Map<String, Object> m = friendItems.get(which);
                    String friendId = (String) m.get("id");
                    String friendName = fullName(m);
                    if (friendId != null) openWithFriend(friendId, friendName);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void maybeRequestNotifyPermission() {
        if (notifAsked) return;
        notifAsked = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    private void cacheTitles() {
        SharedPreferences sp = getSharedPreferences("lc_convs", MODE_PRIVATE);
        SharedPreferences.Editor e = sp.edit();
        for (Conversation c : items) {
            e.putString(c.convId, titleFor(c));
        }
        e.apply();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                doSaveMedia();
            } else {
                showStatus(getString(R.string.settings_status_perm));
            }
        }
    }

    private void showTab(View target) {
        content.removeAllViews();
        content.addView(target);
        target.startAnimation(AnimationUtils.loadAnimation(this, R.anim.act_fwd_in));
    }

    private void showSettingsTab(View tab) {
        if (settingsView == null) {
            settingsView = LayoutInflater.from(this).inflate(R.layout.tab_settings, content, false);
            String uname = session.username();
            ((TextView) settingsView.findViewById(R.id.set_username))
                    .setText(getString(R.string.settings_user_line, uname != null ? uname : "?"));
            String uid = session.userId();
            ((TextView) settingsView.findViewById(R.id.set_userid))
                    .setText(getString(R.string.settings_id_line, uid != null ? uid : "?"));
            settingsStatus = settingsView.findViewById(R.id.set_status);
            settingsView.findViewById(R.id.btn_save_media).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { saveMedia(); }
            });
            settingsView.findViewById(R.id.btn_clear_cache).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { clearCache(); }
            });
            settingsView.findViewById(R.id.btn_avatar).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pickAvatar(); }
            });
            styleSettings(settingsView);
            buildThemePicker();
        }
        showTab(settingsView);
        setNavSelection((TextView) tab);
    }

    private void styleSettings(View root) {
        root.findViewById(R.id.user_card).setBackground(Skin.glassCard(dp(22)));
        ((TextView) root.findViewById(R.id.theme_header)).setTextColor(Skin.palette().onSurface);
        Button save = root.findViewById(R.id.btn_save_media);
        save.setBackground(Skin.pill_primary(dp(24)));
        save.setTextColor(Skin.palette().onPrimary);
        Button clear = root.findViewById(R.id.btn_clear_cache);
        clear.setBackground(Skin.outlinePill(dp(24), Skin.palette().onSurfaceVariant));
        clear.setTextColor(Skin.palette().onSurface);
        Button avatar = root.findViewById(R.id.btn_avatar);
        avatar.setBackground(Skin.pill_primary(dp(24)));
        avatar.setTextColor(Skin.palette().onPrimary);
        TextView user = root.findViewById(R.id.set_username);
        user.setTextColor(Skin.palette().onSurface);
        TextView uid = root.findViewById(R.id.set_userid);
        uid.setTextColor(Skin.palette().onSurfaceVariant);
    }

    private void buildThemePicker() {
        LinearLayout host = settingsView.findViewById(R.id.theme_list);
        host.removeAllViews();
        for (final Skin.ThemeId t : Skin.ThemeId.values()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int pad = dp(12);
            row.setPadding(pad, pad, pad, pad);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setBackground(t == Skin.current()
                    ? Skin.pill_container(dp(20))
                    : Skin.glassCard(dp(20)));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (t == Skin.current()) return;
                    Skin.setTheme(ConversationsActivity.this, t);
                    recreate();
                }
            });
            TextView swatch = new TextView(this);
            swatch.setWidth(dp(34));
            swatch.setHeight(dp(34));
            swatch.setBackground(Skin.circle(Skin.palette().primary));
            row.addView(swatch);
            TextView label = new TextView(this);
            label.setText(t.labelRes());
            label.setTextSize(15);
            label.setTextColor(Skin.palette().onSurface);
            label.setPadding(dp(12), 0, 0, 0);
            label.setTypeface(Typeface.DEFAULT, t == Skin.current() ? Typeface.BOLD : Typeface.NORMAL);
            row.addView(label);
            host.addView(row);
            ViewGroup.MarginLayoutParams ml = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
            ml.bottomMargin = dp(8);
            row.setLayoutParams(ml);
        }
    }

    private void showStatus(String s) {
        if (settingsStatus != null) {
            settingsStatus.setVisibility(View.VISIBLE);
            settingsStatus.setText(s);
        }
    }

    private void saveMedia() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        doSaveMedia();
    }

    private void doSaveMedia() {
        final File target = mediaTargetDir();
        showStatus(getString(R.string.loading));
        Async.exec(this, new Async.Worker<Integer>() {
            @Override public Integer run() {
                int n = 0;
                if (!target.exists()) target.mkdirs();
                File[] files = MediaStore.dir(ConversationsActivity.this).listFiles();
                if (files != null) {
                    for (File f : files) {
                        try (FileInputStream in = new FileInputStream(f);
                             FileOutputStream out = new FileOutputStream(new File(target, f.getName()))) {
                            byte[] buf = new byte[16384];
                            int r;
                            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                            n++;
                        } catch (Exception ignored) {
                        }
                    }
                }
                return n;
            }
        }, new Async.UI<Integer>() {
            @Override public void on(Integer n, Exception err) {
                if (n != null && n > 0) {
                    showStatus(getString(R.string.settings_status_saved, n, target.getAbsolutePath()));
                } else {
                    showStatus(getString(R.string.settings_status_no_media));
                }
            }
        });
    }

    private File mediaTargetDir() {
        if (Build.VERSION.SDK_INT >= 29) {
            File base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            return base == null ? new File(getCacheDir(), "LightChat") : new File(base, "LightChat");
        }
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "LightChat");
    }

    private void clearCache() {
        Async.exec(this, new Async.Worker<Integer>() {
            @Override public Integer run() {
                return MediaStore.clearCached(ConversationsActivity.this);
            }
        }, new Async.UI<Integer>() {
            @Override public void on(Integer n, Exception err) {
                showStatus(getString(R.string.settings_status_cleared, n == null ? 0 : n));
            }
        });
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void setNavSelection(final TextView active) {
        try {
            int onVariant = Skin.palette().onSurfaceVariant;
            int primary = Skin.palette().primary;
            for (int id : new int[]{R.id.tab_chats, R.id.tab_friends, R.id.tab_search, R.id.tab_settings}) {
                TextView t = findViewById(id);
                boolean isActive = t == active;
                t.setTextColor(isActive ? primary : onVariant);
                t.setTextSize(12);
                t.setTypeface(t.getTypeface(), isActive ? Typeface.BOLD : Typeface.NORMAL);
                t.setBackground(isActive ? Skin.pill_container(dp(20))
                        : new android.graphics.drawable.ColorDrawable(0x00000000));
            }
        } catch (Exception ignored) {
            int onVariant = getResources().getColor(R.color.on_surface_variant);
            int primary = getResources().getColor(R.color.primary);
            for (int id : new int[]{R.id.tab_chats, R.id.tab_friends, R.id.tab_search, R.id.tab_settings}) {
                TextView t = findViewById(id);
                boolean isActive = t == active;
                t.setTextColor(isActive ? primary : onVariant);
                t.setTextSize(12);
                t.setTypeface(t.getTypeface(), isActive ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                t.setBackgroundResource(isActive ? R.drawable.bg_input : 0);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session.hasSession()) {
            Realtime.get().start(session.token());
            Realtime.get().addListener(rt);
            reload();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Realtime.get().removeListener(rt);
    }

    // ---------- Chats ----------

    private void reload() {
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.loading);
        final String token = session.token();
        pendingConvs = null;
        pendingNames = null;
        pendingColors = null;
        pendingIds = null;
        final boolean[] gotConvs = {false};
        final AtomicInteger gate = new AtomicInteger(0);

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
                            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                            convs.add(Conversation.fromJson(m));
                        }
                    }
                    return convs;
                } catch (Exception e) {
                    return null;
                }
            }
        }, new Async.UI<List<Conversation>>() {
            @Override public void on(List<Conversation> convs, Exception err) {
                if (convs != null) {
                    gotConvs[0] = true;
                    pendingConvs = convs;
                }
                if (gate.incrementAndGet() == 2) applyReload(gotConvs[0]);
            }
        });

        Async.exec(this, new Async.Worker<Map<String, Object>>() {
            @Override public Map<String, Object> run() {
                Map<String, String> names = new HashMap<String, String>();
                Map<String, String> colors = new HashMap<String, String>();
                Set<String> ids = new HashSet<String>();
                try {
                    ApiClient.ApiResponse f = ApiClient.call("GET", "/api/friends", null, token);
                    if (f.status == 200) {
                        Map<String, Object> fm = Json.parseObject(f.body);
                        List<Object> fl = (List<Object>) fm.get("friends");
                        if (fl != null) {
                            for (Object o : fl) {
                                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                                String id = (String) m.get("id");
                                if (id == null) continue;
                                ids.add(id);
                                String name = fullName(m);
                                names.put(id, name);
                                String col = (String) m.get("color");
                                if (col != null) colors.put(id, col);
                            }
                        }
                    }
                } catch (Exception e) { /* friends is optional */ }
                Map<String, Object> out = new HashMap<String, Object>();
                out.put("names", names);
                out.put("colors", colors);
                out.put("ids", ids);
                return out;
            }
        }, new Async.UI<Map<String, Object>>() {
            @Override public void on(Map<String, Object> r, Exception err) {
                if (r != null) {
                    pendingNames = (Map<String, String>) r.get("names");
                    pendingColors = (Map<String, String>) r.get("colors");
                    pendingIds = (Set<String>) r.get("ids");
                }
                if (gate.incrementAndGet() == 2) applyReload(gotConvs[0]);
            }
        });
    }

    private void applyReload(boolean ok) {
        final String unknown = getString(R.string.unknown_contact);
        items.clear();
        if (pendingConvs != null) items.addAll(pendingConvs);
        friendNames.clear();
        if (pendingNames != null) friendNames.putAll(pendingNames);
        friendColors.clear();
        if (pendingColors != null) friendColors.putAll(pendingColors);
        friendIds.clear();
        if (pendingIds != null) friendIds.addAll(pendingIds);
        for (Conversation c : items) {
            if ("dm".equals(c.kind) && !friendNames.containsKey(otherId(c.convId))) {
                friendNames.put(otherId(c.convId), unknown);
            }
        }
        if (ok) {
            status.setVisibility(View.GONE);
            adapter.notifyDataSetChanged();
            cacheTitles();
            if (searchAdapter != null) searchAdapter.notifyDataSetChanged();
        } else {
            status.setText(R.string.conv_error);
        }
    }

    private static String join(String a, String b) {
        if (a == null || a.isEmpty()) return b;
        if (b == null || b.isEmpty()) return a;
        return a + " " + b;
    }

    private String otherId(String convId) {
        if (convId == null || !convId.startsWith("dm:")) return null;
        String[] parts = convId.substring(3).split(":");
        if (parts.length != 2) return null;
        String me = session.userId();
        return (me != null && me.equals(parts[0])) ? parts[1] : parts[0];
    }

    private String titleFor(Conversation c) {
        if ("dm".equals(c.kind)) {
            String other = otherId(c.convId);
            if (other == null) return getString(R.string.unknown_contact);
            String n = friendNames.get(other);
            return n != null ? n : getString(R.string.unknown_contact);
        }
        return getString(R.string.group);
    }

    private int avatarColorFor(Conversation c) {
        int fallback = Skin.palette().primaryContainer;
        String other = otherId(c.convId);
        String hex = other != null ? friendColors.get(other) : null;
        if (hex == null) return fallback;
        try { return Color.parseColor(hex); } catch (Exception e) { return fallback; }
    }

    private String previewFor(Conversation c) {
        if (c.lastType == null) return "";
        if ("text".equals(c.lastType)) return c.lastBody == null ? "" : c.lastBody;
        return "[" + c.lastType + "]";
    }

    private boolean isMuted(String convId) {
        if (convId == null) return false;
        SharedPreferences sp = getSharedPreferences("lc_prefs", MODE_PRIVATE);
        Set<String> muted = sp.getStringSet("muted_convs", null);
        return muted != null && muted.contains(convId);
    }

    private class ConversationAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Conversation getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(ConversationsActivity.this).inflate(R.layout.item_conversation, parent, false);
            }
            Conversation c = getItem(i);
            TextView avatar = convertView.findViewById(R.id.avatar);
            TextView name = convertView.findViewById(R.id.row_name);
            TextView preview = convertView.findViewById(R.id.row_preview);
            TextView time = convertView.findViewById(R.id.row_time);
            TextView mute = convertView.findViewById(R.id.row_mute);
            TextView unread = convertView.findViewById(R.id.row_unread);
            String title = titleFor(c);
            boolean hasUnread = c.unread > 0;
            name.setText(title);
            name.setTextColor(Skin.palette().onSurface);
            name.setTypeface(name.getTypeface(), hasUnread ? Typeface.BOLD : Typeface.NORMAL);
            String pv;
            boolean typing = isTyping(c.convId);
            if (typing) {
                pv = "✏️ " + getString(R.string.typing_now);
                preview.setTextColor(Skin.palette().primary);
                preview.setTypeface(preview.getTypeface(), Typeface.BOLD);
            } else {
                pv = (c.pinnedBody != null && !c.pinnedBody.isEmpty())
                        ? "📌 " + c.pinnedBody : previewFor(c);
                preview.setTextColor(c.pinnedBody != null && !c.pinnedBody.isEmpty()
                        ? Skin.palette().onSurface : Skin.palette().onSurfaceVariant);
                preview.setTypeface(preview.getTypeface(), hasUnread ? Typeface.BOLD : Typeface.NORMAL);
            }
            preview.setText(pv);
            time.setText(Fmt.listTime(c.lastAt));
            time.setTextColor(hasUnread ? Skin.palette().primary : Skin.palette().onSurfaceVariant);
            boolean muted = isMuted(c.convId);
            mute.setVisibility(muted ? View.VISIBLE : View.GONE);
            if (hasUnread) {
                unread.setText(c.unread > 99 ? "99+" : String.valueOf(c.unread));
                unread.setBackground(Skin.circle(Skin.palette().primary));
                unread.setVisibility(View.VISIBLE);
            } else {
                unread.setVisibility(View.GONE);
            }
            String other = ("dm".equals(c.kind)) ? otherId(c.convId) : null;
            AvatarLoader.apply(avatar, other, session.token(),
                    title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase(), avatarColorFor(c));
            View dot = convertView.findViewById(R.id.online_dot);
            if (dot != null) dot.setVisibility((other != null && Presence.isOnline(other)) ? View.VISIBLE : View.GONE);
            return convertView;
        }
    }

    // ---------- Friends tab ----------

    private void showFriendsTab(View tab) {
        if (friendsView == null) {
            friendsView = LayoutInflater.from(this).inflate(R.layout.tab_friends, content, false);
            ((TextView) friendsView.findViewById(R.id.hdr_pending)).setTextColor(Skin.palette().onSurfaceVariant);
            ((TextView) friendsView.findViewById(R.id.hdr_friends)).setTextColor(Skin.palette().onSurfaceVariant);
            ((TextView) friendsView.findViewById(R.id.pending_empty)).setTextColor(Skin.palette().onSurfaceVariant);
            ((TextView) friendsView.findViewById(R.id.friends_empty)).setTextColor(Skin.palette().onSurfaceVariant);
            pendingAdapter = new PendingAdapter();
            friendAdapter = new FriendAdapter();
            ListView p = friendsView.findViewById(R.id.pending_list);
            ListView f = friendsView.findViewById(R.id.friends_list);
            p.setAdapter(pendingAdapter);
            p.setEmptyView(friendsView.findViewById(R.id.pending_empty));
            f.setAdapter(friendAdapter);
            f.setEmptyView(friendsView.findViewById(R.id.friends_empty));
        }
        showTab(friendsView);
        setNavSelection((TextView) tab);
        refreshFriends();
    }

    private void refreshFriends() {
        final String token = session.token();
        Async.exec(this, new Async.Worker<Boolean>() {
            @Override public Boolean run() {
                try {
                    List<Map<String, Object>> pend = new ArrayList<Map<String, Object>>();
                    ApiClient.ApiResponse pr = ApiClient.call("GET", "/api/friends/pending", null, token);
                    if (pr.status == 200) {
                        Map<String, Object> pm = Json.parseObject(pr.body);
                        List<Object> arr = (List<Object>) pm.get("pending");
                        if (arr != null) {
                            for (Object o : arr) {
                                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                                pend.add(m);
                            }
                        }
                    }
                    List<Map<String, Object>> frs = new ArrayList<Map<String, Object>>();
                    ApiClient.ApiResponse fr = ApiClient.call("GET", "/api/friends", null, token);
                    if (fr.status == 200) {
                        Map<String, Object> fm = Json.parseObject(fr.body);
                        List<Object> arr = (List<Object>) fm.get("friends");
                        if (arr != null) {
                            for (Object o : arr) {
                                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                                frs.add(m);
                            }
                        }
                    }
                    pendingItems.clear();
                    pendingItems.addAll(pend);
                    friendItems.clear();
                    friendItems.addAll(frs);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        }, new Async.UI<Boolean>() {
            @Override public void on(Boolean ok, Exception err) {
                if (ok == null || !ok) return;
                pendingAdapter.notifyDataSetChanged();
                friendAdapter.notifyDataSetChanged();
            }
        });
    }

    private void respond(final String userId, final boolean accept) {
        final String token = session.token();
        final String json = "{\"user_id\":\"" + esc(userId) + "\",\"accept\":" + accept + "}";
        Async.exec(this, new Async.Worker<ApiClient.ApiResponse>() {
            @Override public ApiClient.ApiResponse run() throws Exception {
                return ApiClient.call("POST", "/api/friends/respond", json, token);
            }
        }, new Async.UI<ApiClient.ApiResponse>() {
            @Override public void on(ApiClient.ApiResponse resp, Exception err) {
                refreshFriends();
            }
        });
    }

    private class PendingAdapter extends BaseAdapter {
        @Override public int getCount() { return pendingItems.size(); }
        @Override public Map<String, Object> getItem(int i) { return pendingItems.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(final int i, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(ConversationsActivity.this).inflate(R.layout.item_pending, parent, false);
            }
            final Map<String, Object> m = getItem(i);
            styleAvatar(convertView.findViewById(R.id.p_avatar), m);
            TextView pn = (TextView) convertView.findViewById(R.id.p_name);
            pn.setText(fullName(m));
            pn.setTextColor(Skin.palette().onSurface);
            TextView pu = (TextView) convertView.findViewById(R.id.p_username);
            pu.setText("@" + String.valueOf(m.get("username")));
            pu.setTextColor(Skin.palette().onSurfaceVariant);
            final String requesterId = (String) m.get("id");
            Button accept = convertView.findViewById(R.id.btn_accept);
            accept.setBackground(Skin.pill_primary(dp(20)));
            accept.setTextColor(Skin.palette().onPrimary);
            accept.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { respond(requesterId, true); }
            });
            Button decline = convertView.findViewById(R.id.btn_decline);
            decline.setBackground(Skin.outlinePill(dp(20), Skin.palette().onSurfaceVariant));
            decline.setTextColor(Skin.palette().onSurface);
            decline.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { respond(requesterId, false); }
            });
            return convertView;
        }
    }

    private class FriendAdapter extends BaseAdapter {
        @Override public int getCount() { return friendItems.size(); }
        @Override public Map<String, Object> getItem(int i) { return friendItems.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(final int i, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(ConversationsActivity.this).inflate(R.layout.item_friend, parent, false);
            }
            final Map<String, Object> m = getItem(i);
            styleAvatar(convertView.findViewById(R.id.f_avatar), m);
            TextView fn = (TextView) convertView.findViewById(R.id.f_name);
            fn.setText(fullName(m));
            fn.setTextColor(Skin.palette().onSurface);
            TextView fu = (TextView) convertView.findViewById(R.id.f_username);
            fu.setText("@" + String.valueOf(m.get("username")));
            fu.setTextColor(Skin.palette().onSurfaceVariant);
            final String friendId = (String) m.get("id");
            final String friendName = fullName(m);
            Button talk = convertView.findViewById(R.id.btn_talk);
            talk.setBackground(Skin.pill_container(dp(20)));
            talk.setTextColor(Skin.palette().primary);
            talk.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openWithFriend(friendId, friendName); }
            });
            return convertView;
        }
    }

    private void openWithFriend(String friendId, String friendName) {
        String me = session.userId();
        if (me == null || friendId == null) return;
        String[] ids = { me, friendId };
        Arrays.sort(ids);
        Intent i = new Intent(this, DiscussionActivity.class);
        i.putExtra("conv_id", "dm:" + ids[0] + ":" + ids[1]);
        i.putExtra("title", friendName);
        startActivity(i);
        overridePendingTransition(R.anim.act_fwd_in, R.anim.act_fwd_out);
    }

    // ---------- Search tab ----------

    private void showSearchTab(View tab) {
        if (searchView == null) {
            searchView = LayoutInflater.from(this).inflate(R.layout.tab_search, content, false);
            searchStatus = searchView.findViewById(R.id.search_status);
            searchAdapter = new SearchAdapter();
            ListView lv = searchView.findViewById(R.id.result_list);
            lv.setAdapter(searchAdapter);
            lv.setEmptyView(searchView.findViewById(R.id.result_empty));
            ((TextView) searchView.findViewById(R.id.result_empty)).setTextColor(Skin.palette().onSurfaceVariant);
            final EditText input = searchView.findViewById(R.id.search_input);
            input.setBackground(Skin.pill_input(20));
            Button btn = searchView.findViewById(R.id.btn_search);
            btn.setBackground(Skin.pill_primary(20));
            btn.setTextColor(Skin.palette().onPrimary);
            searchView.findViewById(R.id.btn_search).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { doSearch(input.getText().toString().trim()); }
            });
        }
        showTab(searchView);
        setNavSelection((TextView) tab);
    }

    private void doSearch(final String q) {
        if (q.isEmpty()) return;
        searchStatus.setVisibility(View.VISIBLE);
        searchStatus.setText(R.string.status_searching);
        final String token = session.token();
        final String path;
        try {
            path = "/api/users/search?q=" + URLEncoder.encode(q, "UTF-8");
        } catch (Exception e) {
            return;
        }
        Async.exec(this, new Async.Worker<Boolean>() {
            @Override public Boolean run() {
                try {
                    ApiClient.ApiResponse r = ApiClient.call("GET", path, null, token);
                    if (r.status != 200) return false;
                    Map<String, Object> m = Json.parseObject(r.body);
                    List<Object> arr = (List<Object>) m.get("results");
                    resultItems.clear();
                    if (arr != null) {
                        for (Object o : arr) {
                            @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) o;
                            resultItems.add(row);
                        }
                    }
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        }, new Async.UI<Boolean>() {
            @Override public void on(Boolean ok, Exception err) {
                if (ok != null && ok) {
                    searchStatus.setVisibility(View.GONE);
                    searchAdapter.notifyDataSetChanged();
                } else {
                    searchStatus.setText(R.string.status_search_err);
                }
            }
        });
    }

    private void sendInvite(final String username) {
        final String token = session.token();
        final String json = "{\"username\":\"" + esc(username) + "\"}";
        Async.exec(this, new Async.Worker<ApiClient.ApiResponse>() {
            @Override public ApiClient.ApiResponse run() throws Exception {
                return ApiClient.call("POST", "/api/friends/request", json, token);
            }
        }, new Async.UI<ApiClient.ApiResponse>() {
            @Override public void on(ApiClient.ApiResponse resp, Exception err) {
                if (err == null && resp != null && resp.status == 200) {
                    invitedUsernames.add(username);
                    searchAdapter.notifyDataSetChanged();
                    refreshFriends();
                } else {
                    searchStatus.setVisibility(View.VISIBLE);
                    searchStatus.setText(R.string.status_invite_err);
                }
            }
        });
    }

    private class SearchAdapter extends BaseAdapter {
        @Override public int getCount() { return resultItems.size(); }
        @Override public Map<String, Object> getItem(int i) { return resultItems.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(final int i, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(ConversationsActivity.this).inflate(R.layout.item_result, parent, false);
            }
            final Map<String, Object> m = getItem(i);
            styleAvatar(convertView.findViewById(R.id.r_avatar), m);
            TextView rn = (TextView) convertView.findViewById(R.id.r_name);
            rn.setText(fullName(m));
            rn.setTextColor(Skin.palette().onSurface);
            TextView ru = (TextView) convertView.findViewById(R.id.r_username);
            ru.setText("@" + String.valueOf(m.get("username")));
            ru.setTextColor(Skin.palette().onSurfaceVariant);
            final Button invite = convertView.findViewById(R.id.btn_invite);
            final String username = (String) m.get("username");
            final String id = (String) m.get("id");
            if (id != null && friendIds.contains(id)) {
                invite.setEnabled(false);
                invite.setBackground(Skin.pill_container(dp(20)));
                invite.setTextColor(Skin.palette().primary);
                invite.setText(R.string.already_friends);
            } else if (invitedUsernames.contains(username)) {
                invite.setEnabled(false);
                invite.setBackground(Skin.outlinePill(dp(20), Skin.palette().onSurfaceVariant));
                invite.setTextColor(Skin.palette().onSurfaceVariant);
                invite.setText(R.string.btn_invited);
            } else {
                invite.setEnabled(true);
                invite.setBackground(Skin.pill_primary(dp(20)));
                invite.setTextColor(Skin.palette().onPrimary);
                invite.setText(R.string.btn_invite);
                invite.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { sendInvite(username); }
                });
            }
            return convertView;
        }
    }

    // ---------- Shared helpers ----------

    private static String fullName(Map<String, Object> m) {
        String fn = (String) m.get("first_name");
        String ln = (String) m.get("last_name");
        String n = join(fn, ln);
        if (n == null || n.isEmpty()) n = (String) m.get("username");
        return (n == null || n.isEmpty()) ? "?" : n;
    }

    private void styleAvatar(TextView tv, Map<String, Object> m) {
        int fallback = Skin.palette().primaryContainer;
        String title = fullName(m);
        String id = (String) m.get("id");
        AvatarLoader.apply(tv, id, session.token(),
                title.substring(0, 1).toUpperCase(), colorFor(m, fallback));
    }

    private static int colorFor(Map<String, Object> m, int fallback) {
        String hex = (String) m.get("color");
        if (hex == null) return fallback;
        try { return Color.parseColor(hex); } catch (Exception e) { return fallback; }
    }

    private void pickAvatar() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(i, REQ_AVATAR);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_AVATAR) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        Async.exec(this, new Async.Worker<byte[]>() {
            @Override public byte[] run() throws Exception {
                return avatarBytes(uri);
            }
        }, new Async.UI<byte[]>() {
            @Override public void on(byte[] bytes, Exception err) {
                if (err != null || bytes == null || bytes.length == 0) {
                    showStatus(getString(R.string.settings_avatar_error));
                    return;
                }
                try {
                    ApiClient.ApiResponse r = ApiClient.uploadAvatar(session.token(), bytes);
                    showStatus(r.status == 200 ? getString(R.string.settings_avatar_status)
                            : getString(R.string.settings_avatar_error));
                } catch (Exception e) {
                    showStatus(getString(R.string.settings_avatar_error));
                }
            }
        });
    }

    private byte[] avatarBytes(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("Impossible d'ouvrir l'image");
        final int maxEdge = 512;
        Bitmap bmp;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, bounds);
            in.close();
            int w = bounds.outWidth, h = bounds.outHeight;
            int scale = 1;
            while (Math.max(w, h) / scale > maxEdge) scale *= 2;
            InputStream in2 = getContentResolver().openInputStream(uri);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = scale;
            bmp = BitmapFactory.decodeStream(in2, null, o);
            if (in2 != null) in2.close();
        } finally {
            // no-op
        }
        if (bmp == null) throw new IOException("Décodage impossible");
        int outW = bmp.getWidth(), outH = bmp.getHeight();
        float ratio = (float) maxEdge / Math.max(outW, outH);
        if (ratio < 1f) {
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, (int) (outW * ratio), (int) (outH * ratio), true);
            bmp.recycle();
            bmp = scaled;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int q = 90;
        do {
            bos.reset();
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos);
            q -= 10;
        } while (bos.size() > 2 * 1024 * 1024 && q > 30);
        bmp.recycle();
        return bos.toByteArray();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}