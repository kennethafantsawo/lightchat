package com.lightchat.models;

import com.lightchat.util.Json;
import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;

public class MessageTest {
    @Test public void fromJson_text() {
        Map<String,Object> m = Json.parseObject("{\"id\":\"m1\",\"conv_id\":\"c\",\"sender_id\":\"s\",\"type\":\"text\",\"body\":\"hi\",\"media_key\":null,\"mime\":null,\"duration_ms\":null,\"status\":\"sent\",\"created_at\":999}");
        Message msg = Message.fromJson(m);
        assertEquals("m1", msg.id);
        assertEquals("hi", msg.body);
        assertEquals("sent", msg.status);
        assertEquals(999L, msg.createdAt);
        assertEquals(0L, msg.durationMs);
    }
}
