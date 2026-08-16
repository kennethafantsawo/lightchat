package com.lightchat.models;

import java.util.Map;

public final class Message {
    public final String id;
    public final String convId;
    public final String senderId;
    public final String type;
    public final String body;
    public final String mediaKey;
    public final String mime;
    public final long durationMs;
    public final String status;
    public final long createdAt;

    public Message(String id, String convId, String senderId, String type, String body,
                   String mediaKey, String mime, long durationMs, String status, long createdAt) {
        this.id = id;
        this.convId = convId;
        this.senderId = senderId;
        this.type = type;
        this.body = body;
        this.mediaKey = mediaKey;
        this.mime = mime;
        this.durationMs = durationMs;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static Message fromJson(Map<String, Object> m) {
        return new Message(
            (String) m.get("id"),
            (String) m.get("conv_id"),
            (String) m.get("sender_id"),
            (String) m.get("type"),
            (String) m.get("body"),
            (String) m.get("media_key"),
            (String) m.get("mime"),
            toLong(m.get("duration_ms")),
            (String) m.get("status"),
            toLong(m.get("created_at"))
        );
    }

    private static long toLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        return 0L;
    }
}
