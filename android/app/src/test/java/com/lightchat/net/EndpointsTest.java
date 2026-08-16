package com.lightchat.net;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class EndpointsTest {
    @Test
    public void url_prepend_slash_when_missing() {
        assertEquals(
            Endpoints.BASE_URL + "/api/me",
            Endpoints.url("api/me"));
    }

    @Test
    public void url_keeps_existing_slash() {
        assertEquals(
            Endpoints.BASE_URL + "/api/me",
            Endpoints.url("/api/me"));
    }

    @Test
    public void auth_header_uses_bearer() {
        assertEquals("Bearer abc123", Endpoints.authHeader("abc123"));
    }
}