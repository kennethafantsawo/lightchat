package com.lightchat.models;

import com.lightchat.util.Json;
import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;

public class ConversationTest {
    @Test public void fromJson_with_last_body() {
        Map<String,Object> m = Json.parseObject("{\"conv_id\":\"dm:a:b\",\"kind\":\"dm\",\"created_at\":100,\"last_body\":\"hello\",\"last_at\":200,\"last_type\":\"text\"}");
        Conversation c = Conversation.fromJson(m);
        assertEquals("dm:a:b", c.convId);
        assertEquals("hello", c.lastBody);
        assertEquals(200L, c.lastAt);
        assertEquals(100L, c.createdAt);
    }
    @Test public void fromJson_null_last() {
        Map<String,Object> m = Json.parseObject("{\"conv_id\":\"group:g1\",\"kind\":\"group\",\"created_at\":50,\"last_body\":null,\"last_at\":null,\"last_type\":null}");
        Conversation c = Conversation.fromJson(m);
        assertNull(c.lastBody);
        assertEquals(0L, c.lastAt);
    }
}
