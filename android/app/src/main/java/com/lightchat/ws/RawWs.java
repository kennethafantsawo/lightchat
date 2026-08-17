package com.lightchat.ws;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

public final class RawWs {
    public static final int OP_CONT = 0x0;
    public static final int OP_TEXT = 0x1;
    public static final int OP_BINARY = 0x2;
    public static final int OP_CLOSE = 0x8;
    public static final int OP_PING = 0x9;
    public static final int OP_PONG = 0xA;

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_FRAME = 1 << 20;
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private RawWs() {}

    public static final class FrameData {
        public final int op;
        public final byte[] payload;
        public FrameData(int op, byte[] payload) {
            this.op = op;
            this.payload = payload;
        }
    }

    public static String newKey() {
        byte[] r = new byte[16];
        RNG.nextBytes(r);
        return base64Encode(r);
    }

    public static String computeAccept(String key) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((key + WS_GUID).getBytes(StandardCharsets.UTF_8));
            return base64Encode(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    public static void writeHandshake(OutputStream out, String host, String path, String key) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append("\r\n");
        sb.append("Upgrade: websocket\r\n");
        sb.append("Connection: Upgrade\r\n");
        sb.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        sb.append("Sec-WebSocket-Version: 13\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    public static String readUpgrade(InputStream in, String key) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null || statusLine.indexOf("101") < 0) {
            throw new IOException("Handshake failed: " + statusLine);
        }
        String accept = null;
        for (;;) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) break;
            int c = line.indexOf(':');
            if (c > 0 && "Sec-WebSocket-Accept".equalsIgnoreCase(line.substring(0, c).trim())) {
                accept = line.substring(c + 1).trim();
            }
        }
        if (accept == null) throw new IOException("Missing Sec-WebSocket-Accept");
        String expected = computeAccept(key);
        if (!expected.equals(accept)) throw new IOException("Bad accept token");
        return accept;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b = in.read();
        if (b < 0) return null;
        while (b >= 0 && b != '\r') {
            sb.append((char) b);
            b = in.read();
        }
        if (b == '\r') in.read();
        return sb.toString();
    }

    public static void writeFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        if (payload == null) payload = new byte[0];
        int n = payload.length;
        int headerLen;
        if (n < 126) headerLen = 2;
        else if (n <= 0xFFFF) headerLen = 4;
        else headerLen = 10;
        byte[] mask = new byte[4];
        RNG.nextBytes(mask);
        byte[] buf = new byte[headerLen + 4];
        buf[0] = (byte) (0x80 | (opcode & 0x0F));
        if (n < 126) {
            buf[1] = (byte) (0x80 | n);
        } else if (n <= 0xFFFF) {
            buf[1] = (byte) (0x80 | 126);
            buf[2] = (byte) (n >> 8);
            buf[3] = (byte) n;
        } else {
            buf[1] = (byte) (0x80 | 127);
            long l = n;
            for (int i = 9; i >= 2; i--) {
                buf[i] = (byte) (l & 0xFF);
                l >>>= 8;
            }
        }
        System.arraycopy(mask, 0, buf, headerLen, 4);
        if (n > 0) {
            byte[] mp = new byte[n];
            for (int i = 0; i < n; i++) {
                mp[i] = (byte) (payload[i] ^ mask[i & 3]);
            }
            out.write(buf);
            out.write(mp);
        } else {
            out.write(buf);
        }
        out.flush();
    }

    public static FrameData readFrameData(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) throw new EOFException();
        int b1 = in.read();
        if (b1 < 0) throw new EOFException();
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            long v = 0;
            for (int i = 0; i < 2; i++) {
                int b = in.read();
                if (b < 0) throw new EOFException();
                v = (v << 8) | b;
            }
            len = v;
        } else if (len == 127) {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                int b = in.read();
                if (b < 0) throw new EOFException();
                v = (v << 8) | b;
            }
            len = v;
        }
        if (masked) {
            for (int i = 0; i < 4; i++) {
                if (in.read() < 0) throw new EOFException();
            }
        }
        if (len < 0 || len > MAX_FRAME) throw new IOException("Frame too large: " + len);
        byte[] payload = new byte[(int) len];
        int off = 0;
        while (off < payload.length) {
            int r = in.read(payload, off, payload.length - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
        return new FrameData(opcode, payload);
    }

    private static String base64Encode(byte[] data) {
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = (i + 1 < data.length) ? (data[i + 1] & 0xFF) : 0;
            int b2 = (i + 2 < data.length) ? (data[i + 2] & 0xFF) : 0;
            sb.append(B64[b0 >> 2]);
            sb.append(B64[((b0 << 4) | (b1 >> 4)) & 0x3F]);
            sb.append(i + 1 < data.length ? B64[((b1 << 2) | (b2 >> 6)) & 0x3F] : '=');
            sb.append(i + 2 < data.length ? B64[b2 & 0x3F] : '=');
        }
        return sb.toString();
    }
}