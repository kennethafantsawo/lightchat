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
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.lightchat.R;
import com.lightchat.SessionStore;
import com.lightchat.models.Conversation;
import com.lightchat.net.ApiClient;
import com.lightchat.util.Async;
import com.lightchat.util.Fmt;
import com.lightchat.util.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConversationsActivity extends Activity {
    private SessionStore session;
    private ListView list;
    private TextView status;
    private ConversationAdapter adapter;
    private final List<Conversation> items = new ArrayList<>();
    private final Map<String, String> friendNames = new HashMap<>();
    private final Map<String, String> friendColors = new HashMap<>();
    private View placeholder;

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
                showTab(findViewById(R.id.content), findViewById(R.id.chat_frame));
                setNavSelection((TextView) v);
                reload();
            }
        });
        bindPlaceholderTab(R.id.tab_friends, R.string.friend_placeholder);
        bindPlaceholderTab(R.id.tab_search, R.string.search_placeholder);
        bindPlaceholderTab(R.id.tab_settings, R.string.settings_placeholder);

        findViewById(R.id.txt_logout).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                session.clear();
                startActivity(new Intent(ConversationsActivity.this, LoginActivity.class));
                finish();
            }
        });

        showTab(findViewById(R.id.content), findViewById(R.id.chat_frame));
        setNavSelection(findViewById(R.id.tab_chats));
    }

    private void bindPlaceholderTab(int tabId, final int strId) {
        final TextView tab = findViewById(tabId);
        tab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showTab(findViewById(R.id.content), placeholderView());
                setNavSelection(tab);
            }
        });
    }

    private View placeholderView() {
        if (placeholder == null) {
            placeholder = new TextView(this);
            ((TextView) placeholder).setTextColor(getResources().getColor(R.color.on_surface_variant));
            ((TextView) placeholder).setPadding(dp(16), dp(24), dp(16), dp(24));
        }
        return placeholder;
    }

    private void showTab(FrameLayout content, View target) {
        content.removeAllViews();
        content.addView(target);
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
        if (session.hasSession()) reload();
    }

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
                    List<Conversation> convs = new ArrayList<>();
                    if (arr != null) {
                        for (Object o : arr) {
                            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                            convs.add(Conversation.fromJson(m));
                        }
                    }
                    items.clear();
                    items.addAll(convs);
                    Map<String, String> names = new HashMap<>();
                    Map<String, String> colors = new HashMap<>();
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
        try {
            return Color.parseColor(hex);
        } catch (Exception e) {
            return fallback;
        }
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
}