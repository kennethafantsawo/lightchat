package com.lightchat.net;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.lightchat.util.Json;

public final class ApiClient {
    private static final int TIMEOUT_MS = 15_000;

    private ApiClient() {}

    public static class ApiResponse {
        public final int status;
        public final String body;
        public ApiResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    public static ApiResponse call(String method, String path, String jsonBody, String token)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(Endpoints.url(path)).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json");
            if (token != null) {
                conn.setRequestProperty("Authorization", Endpoints.authHeader(token));
            }
            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = is == null ? "" : readAll(is);
            return new ApiResponse(status, body);
        } finally {
            conn.disconnect();
        }
    }

    public static ApiResponse upload(String path, byte[] data, String mime, String token)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(Endpoints.url(path)).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", mime);
            if (token != null) {
                conn.setRequestProperty("Authorization", Endpoints.authHeader(token));
            }
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
            }
            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = is == null ? "" : readAll(is);
            return new ApiResponse(status, body);
        } finally {
            conn.disconnect();
        }
    }

    public static ApiResponse uploadAvatar(String token, byte[] data) throws IOException {
        return upload("/api/avatar", data, "image/jpeg", token);
    }

    public static byte[] download(String path, String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(Endpoints.url(path)).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/octet-stream");
            if (token != null) {
                conn.setRequestProperty("Authorization", Endpoints.authHeader(token));
            }
            if (conn.getResponseCode() != 200) return null;
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
                return bos.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }

    public static ApiResponse reaction(String token, String messageId, String emoji) throws IOException {
        String json = "{\"message_id\":\"" + esc(messageId) + "\",\"emoji\":\"" + esc(emoji) + "\"}";
        return call("POST", "/api/messages/reaction", json, token);
    }

    public static ApiResponse pin(String token, String convId, String messageId, boolean pinned) throws IOException {
        String json = "{\"conv_id\":\"" + esc(convId) + "\",\"message_id\":\"" + esc(messageId) + "\",\"pinned\":" + pinned + "}";
        return call("POST", "/api/messages/pin", json, token);
    }

    public static ApiResponse saveDraft(String token, String convId, String body) throws IOException {
        String json = "{\"conv_id\":\"" + esc(convId) + "\",\"body\":\"" + esc(body) + "\"}";
        return call("POST", "/api/drafts", json, token);
    }

    public static ApiResponse getDraft(String token, String convId) throws IOException {
        return call("GET", "/api/drafts?conv_id=" + esc(convId), null, token);
    }

    public static void typing(String token, String convId, boolean typing) {
        try {
            String json = "{\"conv_id\":\"" + esc(convId) + "\",\"typing\":" + typing + "}";
            call("POST", "/api/typing", json, token);
        } catch (Exception ignored) {}
    }

    public static Long lastSeen(String token, String userId) {
        try {
            ApiResponse r = call("GET", "/api/users/lastseen?user_id=" + esc(userId), null, token);
            if (r.status != 200 || r.body == null) return 0L;
            Map<String, Object> o = Json.parseObject(r.body);
            Object t = o.get("last_seen");
            if (t instanceof Number) return ((Number) t).longValue();
            if (o.get("error") != null) return 0L;
            return 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    public static boolean setEphemeral(String token, String convId, int ttl) {
        try {
            String json = "{\"conv_id\":\"" + esc(convId) + "\",\"ttl\":" + ttl + "}";
            ApiResponse r = call("POST", "/api/conversations/ephemeral", json, token);
            return r.status == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean deleteMessage(String token, String messageId) {
        try {
            String json = "{\"message_id\":\"" + esc(messageId) + "\"}";
            ApiResponse r = call("POST", "/api/messages/delete", json, token);
            return r.status == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String readAll(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}