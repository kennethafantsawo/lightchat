package com.lightchat.net;

import android.os.Handler;
import android.os.Looper;

import com.lightchat.util.Json;
import com.lightchat.ws.WsClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Realtime {
    public interface Listener {
        void onMessage(String json);
        void onState(boolean open);
    }

    private static final String WS_HOST = "lightchat.kennethafantsawo.workers.dev";
    private static final String WS_PATH = "/api/ws";
    private static final long RECONNECT_MAX_MS = 30000L;

    private static Realtime instance;

    private final List<Listener> listeners = new ArrayList<Listener>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile boolean open;
    private volatile String token;
    private volatile WsClient client;
    private Thread thread;

    private Realtime() {}

    public static synchronized Realtime get() {
        if (instance == null) instance = new Realtime();
        return instance;
    }

    public synchronized void start(String token) {
        if (token == null || token.isEmpty()) return;
        this.token = token;
        if (thread != null && thread.isAlive()) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "lc-ws");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        open = false;
        WsClient c = client;
        if (c != null) c.close();
        notifyState();
    }

    public boolean isOpen() {
        return open;
    }

    public void send(String text) {
        WsClient c = client;
        if (c != null) {
            try { c.sendText(text); } catch (Exception ignored) {}
        }
    }

    public void addListener(Listener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    private void loop() {
        long delay = 1000L;
        while (running) {
            final String t = token;
            final WsClient[] holder = new WsClient[1];
            final WsClient ws = new WsClient(WS_HOST, WS_PATH, new WsClient.Listener() {
                @Override public void onOpen() {
                    try {
                        holder[0].sendText("{\"type\":\"hello\",\"token\":\"" + esc(t) + "\"}");
                    } catch (Exception ignored) {}
                }
                @Override public void onText(String text) { dispatch(text); }
                @Override public void onClose(int code, String reason) { open = false; notifyState(); }
            });
            holder[0] = ws;
            client = ws;
            try {
                ws.run(10000);
            } catch (Exception ignored) {
            }
            if (!running) break;
            try { Thread.sleep(delay); } catch (InterruptedException e) { break; }
            delay = Math.min(delay * 2, RECONNECT_MAX_MS);
        }
    }

    private void dispatch(final String text) {
        handler.post(new Runnable() {
            @Override public void run() {
                try {
                    Map<String, Object> m = Json.parseObject(text);
                    if ("ready".equals(m.get("type"))) {
                        open = true;
                        notifyState();
                        return;
                    }
                    notifyMessage(text);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void notifyState() {
        handler.post(new Runnable() {
            @Override public void run() {
                List<Listener> copy;
                synchronized (listeners) { copy = new ArrayList<Listener>(listeners); }
                for (Listener l : copy) l.onState(open);
            }
        });
    }

    private void notifyMessage(String text) {
        List<Listener> copy;
        synchronized (listeners) { copy = new ArrayList<Listener>(listeners); }
        for (Listener l : copy) l.onMessage(text);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}