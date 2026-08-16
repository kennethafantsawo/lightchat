package com.lightchat.models;

import java.util.Map;

public final class Conversation {
    public final String convId;
    public final String kind;
    public final long createdAt;
    public final String lastBody;
    public final long lastAt;
    public final String lastType;

    public Conversation(String convId, String kind, long createdAt, String lastBody, long lastAt, String lastType) {
        this.convId = convId;
        this.kind = kind;
        this.createdAt = createdAt;
        this.lastBody = lastBody;
        this.lastAt = lastAt;
        this.lastType = lastType;
    }

    public static Conversation fromJson(Map<String, Object> m) {
        return new Conversation(
            (String) m.get("conv_id"),
            (String) m.get("kind"),
            toLong(m.get("created_at")),
            (String) m.get("last_body"),
            toLong(m.get("last_at")),
            (String) m.get("last_type")
        );
    }

    private static long toLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        return 0L;
    }
}
