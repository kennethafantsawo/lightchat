package com.lightchat.net;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

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

    public static final class SearchResult {
        public final String id;
        public final String convId;
        public final String senderId;
        public final String body;
        public final long createdAt;
        SearchResult(String id, String convId, String senderId, String body, long createdAt) {
            this.id = id;
            this.convId = convId;
            this.senderId = senderId;
            this.body = body;
            this.createdAt = createdAt;
        }
    }

    public interface SearchCallback {
        void on(List<SearchResult> results, Exception err);
    }

    public static final class PrivacySettings {
        public boolean hideOnline;
        public boolean hideLastSeen;
        public boolean readReceipts = true;
        public int ephemeralTtl;
        PrivacySettings() {}
    }

    public interface PrivacyCallback {
        void on(PrivacySettings settings, Exception err);
    }

    public static void getPrivacy(String token, PrivacyCallback cb) {
        PrivacySettings defaults = new PrivacySettings();
        try {
            ApiResponse r = call("GET", "/api/privacy", null, token);
            PrivacySettings s = new PrivacySettings();
            s.hideOnline = false;
            s.hideLastSeen = false;
            s.readReceipts = true;
            s.ephemeralTtl = 0;
            if (r.status == 200 && r.body != null && !r.body.isEmpty()) {
                Map<String, Object> o = Json.parseObject(r.body);
                Object setObj = o.get("settings");
                if (setObj instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) setObj;
                    if (m.get("hide_online") instanceof Boolean) s.hideOnline = (Boolean) m.get("hide_online");
                    if (m.get("hide_last_seen") instanceof Boolean) s.hideLastSeen = (Boolean) m.get("hide_last_seen");
                    if (m.get("read_receipts") instanceof Boolean) s.readReceipts = (Boolean) m.get("read_receipts");
                    if (m.get("ephemeral_default_ttl") instanceof Number)
                        s.ephemeralTtl = ((Number) m.get("ephemeral_default_ttl")).intValue();
                }
            }
            cb.on(s, null);
        } catch (Exception e) {
            cb.on(defaults, e);
        }
    }

    public interface StatusCallback {
        void on(boolean ok, Exception err);
    }

    public static void putPrivacy(String token, JSONObject settings, StatusCallback cb) {
        try {
            ApiResponse r = call("PUT", "/api/privacy", settings.toString(), token);
            cb.on(r.status >= 200 && r.status < 300, null);
        } catch (Exception e) {
            cb.on(false, e);
        }
    }

    public static final class BlockedUser {
        public final String blockedId;
        public final String username;
        public final String displayName;
        public final String avatarUrl;
        BlockedUser(String blockedId, String username, String displayName, String avatarUrl) {
            this.blockedId = blockedId;
            this.username = username;
            this.displayName = displayName;
            this.avatarUrl = avatarUrl;
        }
    }

    public interface BlocksCallback {
        void on(List<BlockedUser> blocks, Exception err);
    }

    public static void blockUser(String token, String userId, StatusCallback cb) {
        try {
            String json = "{\"user_id\":\"" + esc(userId) + "\"}";
            ApiResponse r = call("POST", "/api/block", json, token);
            cb.on(r.status >= 200 && r.status < 300, null);
        } catch (Exception e) {
            cb.on(false, e);
        }
    }

    public static void unblockUser(String token, String userId, StatusCallback cb) {
        try {
            ApiResponse r = call("DELETE", "/api/block/" + esc(userId), null, token);
            cb.on(r.status >= 200 && r.status < 300, null);
        } catch (Exception e) {
            cb.on(false, e);
        }
    }

    public static void getBlocks(String token, BlocksCallback cb) {
        try {
            ApiResponse r = call("GET", "/api/blocks", null, token);
            List<BlockedUser> out = new ArrayList<BlockedUser>();
            if (r.status == 200 && r.body != null && !r.body.isEmpty()) {
                Map<String, Object> o = Json.parseObject(r.body);
                Object arr = o.get("blocks");
                if (arr instanceof List) {
                    for (Object x : (List<Object>) arr) {
                        if (!(x instanceof Map)) continue;
                        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) x;
                        String id = m.get("blocked_id") == null ? "" : String.valueOf(m.get("blocked_id"));
                        String un = m.get("username") == null ? "" : String.valueOf(m.get("username"));
                        String dn = m.get("display_name") == null ? "" : String.valueOf(m.get("display_name"));
                        String av = m.get("avatar_url") == null ? null : String.valueOf(m.get("avatar_url"));
                        out.add(new BlockedUser(id, un, dn, av));
                    }
                }
            }
            cb.on(out, null);
        } catch (Exception e) {
            cb.on(new ArrayList<BlockedUser>(), e);
        }
    }

    public static void reportMessage(String token, String messageId, String convId, String reason, StatusCallback cb) {
        try {
            String json = "{\"message_id\":\"" + esc(messageId) + "\",\"conv_id\":\""
                    + esc(convId) + "\",\"reason\":\"" + esc(reason) + "\"}";
            ApiResponse r = call("POST", "/api/report", json, token);
            cb.on(r.status >= 200 && r.status < 300, null);
        } catch (Exception e) {
            cb.on(false, e);
        }
    }

    public static void searchMessages(String token, String query, String convId, int limit, SearchCallback cb) {
        try {
            StringBuilder url = new StringBuilder("/api/messages/search?q=");
            url.append(URLEncoder.encode(query, "UTF-8"));
            url.append("&limit=").append(limit);
            if (convId != null && !convId.isEmpty()) {
                url.append("&conv_id=").append(URLEncoder.encode(convId, "UTF-8"));
            }
            ApiResponse r = call("GET", url.toString(), null, token);
            List<SearchResult> out = new ArrayList<SearchResult>();
            if (r.status == 200 && r.body != null) {
                Map<String, Object> o = Json.parseObject(r.body);
                Object res = o.get("results");
                if (res instanceof List) {
                    for (Object x : (List<Object>) res) {
                        if (!(x instanceof Map)) continue;
                        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) x;
                        String id = m.get("id") == null ? "" : String.valueOf(m.get("id"));
                        String cid = m.get("conv_id") == null ? null : String.valueOf(m.get("conv_id"));
                        String sid = m.get("sender_id") == null ? null : String.valueOf(m.get("sender_id"));
                        String body = m.get("body") == null ? "" : String.valueOf(m.get("body"));
                        long ts = (m.get("created_at") instanceof Number)
                                ? ((Number) m.get("created_at")).longValue() : 0L;
                        out.add(new SearchResult(id, cid, sid, body, ts));
                    }
                }
            }
            cb.on(out, null);
        } catch (Exception e) {
            cb.on(new ArrayList<SearchResult>(), e);
        }
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

    public static final class PresenceRow {
        public final String userId;
        public final boolean online;
        public final long lastSeen;
        PresenceRow(String userId, boolean online, long lastSeen) {
            this.userId = userId;
            this.online = online;
            this.lastSeen = lastSeen;
        }
    }

    public static List<PresenceRow> presence(String token, String idsCsv) {
        List<PresenceRow> out = new ArrayList<PresenceRow>();
        if (idsCsv == null || idsCsv.isEmpty()) return out;
        try {
            ApiResponse r = call("GET", "/api/users/presence?ids=" + idsCsv, null, token);
            if (r.status != 200) return out;
            Map<String, Object> o = Json.parseObject(r.body);
            List<Object> arr = (List<Object>) o.get("presence");
            if (arr != null) {
                for (Object x : arr) {
                    @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) x;
                    String uid = String.valueOf(m.get("user_id"));
                    boolean on = Boolean.TRUE.equals(m.get("online"));
                    long ls = (m.get("last_seen") instanceof Number)
                            ? ((Number) m.get("last_seen")).longValue() : 0L;
                    out.add(new PresenceRow(uid, on, ls));
                }
            }
        } catch (Exception e) {
        }
        return out;
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