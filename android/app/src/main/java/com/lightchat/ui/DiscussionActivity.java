package com.lightchat.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.lightchat.R;
import com.lightchat.SessionStore;
import com.lightchat.models.Message;
import com.lightchat.net.ApiClient;
import com.lightchat.net.Realtime;
import com.lightchat.util.Async;
import com.lightchat.util.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DiscussionActivity extends Activity {
    private static final long POLL_FAST_MS = 2000L;
    private static final long POLL_SLOW_MS = 10000L;

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

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private class MessageAdapter extends BaseAdapter {
        private static final int TYPE_SYSTEM = 0;
        private static final int TYPE_ME = 1;
        private static final int TYPE_OTHER = 2;

        private List<Message> msgs = new ArrayList<Message>();

        void setList(List<Message> l) { msgs = l; }

        @Override public int getCount() { return msgs.size(); }
        @Override public Message getItem(int i) { return msgs.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getItemViewType(int i) { return "system".equals(getItem(i).type) ? TYPE_SYSTEM : isMine(getItem(i)) ? TYPE_ME : TYPE_OTHER; }
        @Override public int getViewTypeCount() { return 3; }

        private boolean isMine(Message m) {
            String me = session.userId();
            return me != null && me.equals(m.senderId);
        }

        @Override public View getView(int i, View convertView, ViewGroup parent) {
            Message m = getItem(i);
            int type = getItemViewType(i);
            int layout = type == TYPE_SYSTEM ? R.layout.item_message_system
                    : (type == TYPE_ME ? R.layout.item_message_me : R.layout.item_message_other);
            if (convertView == null) {
                convertView = LayoutInflater.from(DiscussionActivity.this).inflate(layout, parent, false);
            }
            TextView bubble = convertView.findViewById(R.id.msg_bubble);
            bubble.setText(display(m));
            return convertView;
        }
    }

    private String display(Message m) {
        if (m.body != null && !m.body.isEmpty()) return m.body;
        return "[" + m.type + "]";
    }
}