package com.lightchat.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.lightchat.R;
import com.lightchat.SessionStore;
import com.lightchat.net.Realtime;
import com.lightchat.models.Conversation;
import com.lightchat.net.ApiClient;
import com.lightchat.util.Async;
import com.lightchat.util.Fmt;
import com.lightchat.util.Json;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private final Realtime.Listener rt = new Realtime.Listener() {
        @Override public void onMessage(String json) { reload(); }
        @Override public void onState(boolean open) { }
    };

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

        content = findViewById(R.id.content);
        chatFrame = findViewById(R.id.chat_frame);
        status = findViewById(R.id.txt_status);
        list = findViewById(R.id.list_conversations);
        adapter = new ConversationAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.txt_empty));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Conversation c = items.get(pos);
                Intent i = new Intent(ConversationsActivity.this, DiscussionActivity.class);
                i.putExtra("conv_id", c.convId);
                i.putExtra("title", titleFor(c));
                startActivity(i);
            }
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
            @Override public void onClick(View v) {
                showTab(placeholder(getString(R.string.settings_placeholder)));
                setNavSelection((TextView) v);
            }
        });

        findViewById(R.id.txt_logout).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Realtime.get().stop();
                session.clear();
                startActivity(new Intent(ConversationsActivity.this, LoginActivity.class));
                finish();
            }
        });

        showTab(chatFrame);
        setNavSelection(findViewById(R.id.tab_chats));
    }

    private void showTab(View target) {
        content.removeAllViews();
        content.addView(target);
    }

    private View placeholder(String text) {
        if (placeholder == null) {
            placeholder = new TextView(this);
        }
        ((TextView) placeholder).setText(text);
        ((TextView) placeholder).setTextColor(getResources().getColor(R.color.on_surface_variant));
        ((TextView) placeholder).setPadding(dp(16), dp(24), dp(16), dp(24));
        return placeholder;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void setNavSelection(final TextView active) {
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
        Async.exec(this, new Async.Worker<Boolean>() {
            @Override public Boolean run() {
                try {
                    ApiClient.ApiResponse c = ApiClient.call("GET", "/api/conversations", null, token);
                    if (c.status != 200) return false;
                    Map<String, Object> cm = Json.parseObject(c.body);
                    List<Object> arr = (List<Object>) cm.get("conversations");
                    List<Conversation> convs = new ArrayList<Conversation>();
                    if (arr != null) {
                        for (Object o : arr) {
                            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                            convs.add(Conversation.fromJson(m));
                        }
                    }
                    items.clear();
                    items.addAll(convs);
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
                                    String fn = (String) m.get("first_name");
                                    String ln = (String) m.get("last_name");
                                    String name = join(fn, ln);
                                    if (name == null || name.isEmpty()) name = (String) m.get("username");
                                    if (name == null) name = getString(R.string.unknown_contact);
                                    names.put(id, name);
                                    String col = (String) m.get("color");
                                    if (col != null) colors.put(id, col);
                                }
                            }
                        }
                    } catch (Exception e) { /* friends is optional */ }
                    friendNames.clear();
                    friendNames.putAll(names);
                    friendColors.clear();
                    friendColors.putAll(colors);
                    friendIds.clear();
                    friendIds.addAll(ids);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        }, new Async.UI<Boolean>() {
            @Override public void on(Boolean ok, Exception err) {
                if (ok != null && ok) {
                    status.setVisibility(View.GONE);
                    adapter.notifyDataSetChanged();
                    if (searchAdapter != null) searchAdapter.notifyDataSetChanged();
                } else {
                    status.setText(R.string.conv_error);
                }
            }
        });
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
        int fallback = getResources().getColor(R.color.primary_container);
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
            String title = titleFor(c);
            name.setText(title);
            preview.setText(previewFor(c));
            time.setText(Fmt.listTime(c.lastAt));
            avatar.setText(title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase());
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(avatarColorFor(c));
            avatar.setBackground(d);
            return convertView;
        }
    }

    // ---------- Friends tab ----------

    private void showFriendsTab(View tab) {
        if (friendsView == null) {
            friendsView = LayoutInflater.from(this).inflate(R.layout.tab_friends, content, false);
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
            ((TextView) convertView.findViewById(R.id.p_name)).setText(fullName(m));
            ((TextView) convertView.findViewById(R.id.p_username)).setText("@" + String.valueOf(m.get("username")));
            final String requesterId = (String) m.get("id");
            convertView.findViewById(R.id.btn_accept).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { respond(requesterId, true); }
            });
            convertView.findViewById(R.id.btn_decline).setOnClickListener(new View.OnClickListener() {
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
            ((TextView) convertView.findViewById(R.id.f_name)).setText(fullName(m));
            ((TextView) convertView.findViewById(R.id.f_username)).setText("@" + String.valueOf(m.get("username")));
            final String friendId = (String) m.get("id");
            final String friendName = fullName(m);
            convertView.findViewById(R.id.btn_talk).setOnClickListener(new View.OnClickListener() {
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
            final EditText input = searchView.findViewById(R.id.search_input);
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
            ((TextView) convertView.findViewById(R.id.r_name)).setText(fullName(m));
            ((TextView) convertView.findViewById(R.id.r_username)).setText("@" + String.valueOf(m.get("username")));
            final Button invite = convertView.findViewById(R.id.btn_invite);
            final String username = (String) m.get("username");
            final String id = (String) m.get("id");
            if (id != null && friendIds.contains(id)) {
                invite.setEnabled(false);
                invite.setBackgroundResource(R.drawable.bg_btn_primary);
                invite.setTextColor(getResources().getColor(R.color.on_primary));
                invite.setText(R.string.already_friends);
            } else if (invitedUsernames.contains(username)) {
                invite.setEnabled(false);
                invite.setBackgroundResource(0);
                invite.setTextColor(getResources().getColor(R.color.on_surface_variant));
                invite.setText(R.string.btn_invited);
            } else {
                invite.setEnabled(true);
                invite.setBackgroundResource(R.drawable.bg_btn_primary);
                invite.setTextColor(getResources().getColor(R.color.on_primary));
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
        int fallback = getResources().getColor(R.color.primary_container);
        String title = fullName(m);
        tv.setText(title.substring(0, 1).toUpperCase());
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(colorFor(m, fallback));
        tv.setBackground(d);
    }

    private static int colorFor(Map<String, Object> m, int fallback) {
        String hex = (String) m.get("color");
        if (hex == null) return fallback;
        try { return Color.parseColor(hex); } catch (Exception e) { return fallback; }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}