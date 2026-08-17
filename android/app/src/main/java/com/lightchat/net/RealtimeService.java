package com.lightchat.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import com.lightchat.App;
import com.lightchat.R;
import com.lightchat.SessionStore;
import com.lightchat.ui.ConversationsActivity;
import com.lightchat.util.Json;

import java.util.Map;

public final class RealtimeService extends Service implements Realtime.Listener {
    private static final String CHANNEL_MSGS = "messages";
    private static final String CHANNEL_SVC = "service";
    private static final int NOTIF_SERVICE = 1;
    private static final String PREFS_TITLES = "lc_convs";
    private static final String TITLE_DEFAULT = "LightChat";

    public static void start(Context ctx) {
        ctx.startService(new Intent(ctx, RealtimeService.class));
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, RealtimeService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannels();
        Realtime.get().addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_SERVICE, buildServiceNotif());
        SessionStore session = new SessionStore(this);
        if (session.hasSession()) {
            Realtime.get().start(session.token());
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Realtime.get().removeListener(this);
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onMessage(String json) {
        if (App.isForeground()) return;
        try {
            Map<String, Object> m = Json.parseObject(json);
            if (!"message".equals(m.get("type"))) return;
            Object mo = m.get("message");
            if (!(mo instanceof Map)) return;
            @SuppressWarnings("unchecked") Map<String, Object> msg = (Map<String, Object>) mo;
            String convId = (String) msg.get("conv_id");
            String senderId = (String) msg.get("sender_id");
            String type = (String) msg.get("type");
            String body = (String) msg.get("body");
            if (convId == null) return;
            notifyMessage(convId, senderId, type, body);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onState(boolean open) {
    }

    private String titleFor(String convId) {
        if (convId == null) return TITLE_DEFAULT;
        SharedPreferences sp = getSharedPreferences(PREFS_TITLES, Context.MODE_PRIVATE);
        String t = sp.getString(convId, null);
        return (t == null || t.isEmpty()) ? TITLE_DEFAULT : t;
    }

    private String contentFor(String type, String body) {
        if ("text".equals(type)) return (body == null || body.isEmpty()) ? getString(R.string.notif_new) : body;
        if ("photo".equals(type)) return getString(R.string.notif_photo);
        if ("video".equals(type)) return getString(R.string.notif_video);
        if ("audio".equals(type)) return getString(R.string.notif_audio);
        if ("sticker".equals(type)) return getString(R.string.notif_sticker);
        return getString(R.string.notif_new);
    }

    private void notifyMessage(String convId, String senderId, String type, String body) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent i = new Intent(this, ConversationsActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, pFlags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_MSGS);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(titleFor(convId))
                .setContentText(contentFor(type, body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(Notification.PRIORITY_HIGH);
        nm.notify(convId.hashCode(), b.build());
    }

    private Notification buildServiceNotif() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_SVC);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(R.string.notif_service))
                .setContentText(getString(R.string.notif_service_text))
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN);
        return b.build();
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel msgs = new NotificationChannel(CHANNEL_MSGS,
                getString(R.string.notif_channel_messages), NotificationManager.IMPORTANCE_HIGH);
        NotificationChannel svc = new NotificationChannel(CHANNEL_SVC,
                getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_MIN);
        nm.createNotificationChannel(msgs);
        nm.createNotificationChannel(svc);
    }
}