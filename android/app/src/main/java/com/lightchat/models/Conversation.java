package com.lightchat.models;

import java.util.Map;

public final class Conversation {
    public final String convId;
    public final String kind;
    public final long createdAt;
    public final String lastBody;
    public final long lastAt;
    public final String lastType;
    public final String pinnedBody;
    public final int unread;
    public final int ephemeralTtl;

    public Conversation(String convId, String kind, long createdAt, String lastBody, long lastAt, String lastType, String pinnedBody, int unread, int ephemeralTtl) {
        this.convId = convId;
        this.kind = kind;
        this.createdAt = createdAt;
        this.lastBody = lastBody;
        this.lastAt = lastAt;
        this.lastType = lastType;
        this.pinnedBody = pinnedBody;
        this.unread = unread;
        this.ephemeralTtl = ephemeralTtl;
    }

    public static Conversation fromJson(Map<String, Object> m) {
        Object pin = m.get("pinned_body");
        return new Conversation(
            (String) m.get("conv_id"),
            (String) m.get("kind"),
            toLong(m.get("created_at")),
            (String) m.get("last_body"),
            toLong(m.get("last_at")),
            (String) m.get("last_type"),
            pin == null ? null : String.valueOf(pin),
            (int) toLong(m.get("unread")),
            (int) toLong(m.get("ephemeral_ttl"))
        );
    }

    private static long toLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        return 0L;
    }
}
