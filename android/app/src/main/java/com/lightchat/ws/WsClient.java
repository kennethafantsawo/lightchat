package com.lightchat.ws;

import android.net.SSLCertificateSocketFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class WsClient {
    public interface Listener {
        void onOpen();
        void onText(String text);
        void onClose(int code, String reason);
    }

    private static final long KEEPALIVE_MS = 30000L;

    private final String host;
    private final int port;
    private final String path;
    private final Listener listener;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean running = true;

    public WsClient(String host, String path, Listener listener) {
        this.host = host;
        this.port = 443;
        this.path = path;
        this.listener = listener;
    }

    public void run(int timeoutMs) {
        try {
            connect(timeoutMs);
            String key = RawWs.newKey();
            RawWs.writeHandshake(out, host, path, key);
            RawWs.readUpgrade(in, key);
            startPinger();
            listener.onOpen();
            readLoop();
        } catch (Exception e) {
            if (running) {
                close();
                listener.onClose(-1, e.getMessage());
            }
        }
    }

    private void connect(int timeoutMs) throws Exception {
        Socket raw = new Socket();
        try {
            raw.setTcpNoDelay(true);
            raw.connect(new InetSocketAddress(host, port), timeoutMs);
            SSLSocketFactory f = (SSLSocketFactory) SSLCertificateSocketFactory.getDefault(timeoutMs);
            SSLSocket ssl = (SSLSocket) f.createSocket(raw, host, port, true);
            ssl.setSoTimeout(60000);
            ssl.startHandshake();
            socket = ssl;
            in = ssl.getInputStream();
            out = ssl.getOutputStream();
        } catch (Exception e) {
            try { raw.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    private void readLoop() throws IOException {
        while (running) {
            RawWs.FrameData f;
            try {
                f = RawWs.readFrameData(in);
            } catch (SocketTimeoutException e) {
                continue;
            }
            switch (f.op) {
                case RawWs.OP_TEXT:
                    listener.onText(new String(f.payload, StandardCharsets.UTF_8));
                    break;
                case RawWs.OP_PING:
                    sendFrame(RawWs.OP_PONG, f.payload);
                    break;
                case RawWs.OP_CLOSE:
                    int code = f.payload.length >= 2 ? (((f.payload[0] & 0xFF) << 8) | (f.payload[1] & 0xFF)) : -1;
                    String reason = f.payload.length > 2 ? new String(f.payload, 2, f.payload.length - 2, StandardCharsets.UTF_8) : "";
                    close();
                    listener.onClose(code, reason);
                    return;
                default:
                    break;
            }
        }
        close();
        listener.onClose(-1, null);
    }

    private void startPinger() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    try { Thread.sleep(KEEPALIVE_MS); } catch (InterruptedException e) { return; }
                    if (!running) return;
                    try { sendFrame(RawWs.OP_PING, new byte[0]); } catch (IOException e) { return; }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void sendFrame(int op, byte[] payload) throws IOException {
        synchronized (out) {
            RawWs.writeFrame(out, op, payload);
        }
    }

    public void sendText(String s) throws IOException {
        if (out == null || !running) throw new IOException("closed");
        sendFrame(RawWs.OP_TEXT, s.getBytes(StandardCharsets.UTF_8));
    }

    public void close() {
        running = false;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
            socket = null;
        }
    }
}