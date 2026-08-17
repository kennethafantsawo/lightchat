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