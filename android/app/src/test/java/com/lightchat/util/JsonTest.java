package com.lightchat.util;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class JsonTest {
    @Test public void parseObject_simple() {
        Map<String,Object> m = Json.parseObject("{\"a\":\"b\"}");
        assertEquals("b", m.get("a"));
    }
    @Test public void parseObject_nested_arrays_and_numbers() {
        Map<String,Object> m = Json.parseObject("{\"i\":42,\"b\":true,\"d\":3.14,\"arr\":[1,2,3],\"o\":{\"x\":null}}");
        assertEquals(Long.valueOf(42), m.get("i"));
        assertEquals(Boolean.TRUE, m.get("b"));
        assertEquals(Double.valueOf(3.14), m.get("d"));
        assertEquals(3, ((List<?>) m.get("arr")).size());
        assertNull(((Map<?,?>) m.get("o")).get("x"));
    }
    @Test public void string_escapes() {
        Map<String,Object> m = Json.parseObject("{\"s\":\"a\\nb\\t\\\"q\\\\\"}");
        assertEquals("a\nb\t\"q\\", m.get("s"));
    }
    @Test public void parseArray_top() {
        List<Object> a = Json.parseArray("[1,2,3]");
        assertEquals(3, a.size());
        assertEquals(Long.valueOf(2), a.get(1));
    }
    @Test public void conversations_api_shape() {
        String api = "{\"conversations\":[{\"conv_id\":\"dm:abc:def\",\"kind\":\"dm\",\"created_at\":123,\"last_body\":\"hi\",\"last_at\":456,\"last_type\":\"text\"}]}";
        Map<String,Object> r = Json.parseObject(api);
        List<?> convs = (List<?>) r.get("conversations");
        assertEquals(1, convs.size());
        @SuppressWarnings("unchecked") Map<String,Object> c = (Map<String,Object>) convs.get(0);
        assertEquals("dm:abc:def", c.get("conv_id"));
        assertEquals(Long.valueOf(456), c.get("last_at"));
    }
    @Test public void rejects_trailing() {
        try { Json.parse("{\"a\":1} junk"); fail("expected exception"); } catch (IllegalArgumentException e) { /* ok */ }
    }
}
