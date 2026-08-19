package com.lightchat.models;

import java.util.ArrayList;
import java.util.List;
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
    public final String replyToId;
    public final long edited;
    public final long deleted;
    public final long pinned;
    public final List<String> reactions;

    public Message(String id, String convId, String senderId, String type, String body,
                   String mediaKey, String mime, long durationMs, String status, long createdAt,
                   String replyToId, long edited, long deleted, long pinned, List<String> reactions) {
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
        this.replyToId = replyToId;
        this.edited = edited;
        this.deleted = deleted;
        this.pinned = pinned;
        this.reactions = reactions == null ? new ArrayList<String>() : reactions;
    }

    public static Message fromJson(Map<String, Object> m) {
        List<String> rx = new ArrayList<String>();
        Object r = m.get("reactions");
        if (r instanceof List) {
            for (Object o : (List<Object>) r) {
                if (o instanceof Map) {
                    Object e = ((Map<String, Object>) o).get("emoji");
                    if (e != null) rx.add(String.valueOf(e));
                }
            }
        }
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
            toLong(m.get("created_at")),
            (String) m.get("reply_to_id"),
            toLong(m.get("edited")),
            toLong(m.get("deleted")),
            toLong(m.get("pinned")),
            rx
        );
    }

    public Message withStatus(String s) {
        String st = (s == null || s.isEmpty()) ? "sent" : s;
        return new Message(id, convId, senderId, type, body, mediaKey, mime, durationMs, st, createdAt,
                replyToId, edited, deleted, pinned, reactions);
    }

    public Message withReactions(List<String> rx) {
        return new Message(id, convId, senderId, type, body, mediaKey, mime, durationMs, status, createdAt,
                replyToId, edited, deleted, pinned, rx);
    }

    public boolean mine(String me) {
        return me != null && me.equals(senderId);
    }

    public boolean isDeleted() {
        return deleted != 0L;
    }

    public boolean isPinned() {
        return pinned != 0L;
    }

    private static long toLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        return 0L;
    }
}