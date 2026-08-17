package com.lightchat.ws;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RawWsTest {

    @Test public void handshakeKeyIsValid() {
        for (int i = 0; i < 50; i++) {
            String k = RawWs.newKey();
            assertEquals(24, k.length());
            assertTrue(k.matches("[A-Za-z0-9+/=]+"));
        }
    }

    @Test public void acceptRfcExample() throws Exception {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", RawWs.computeAccept("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test public void writeFrameSmallText() throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        RawWs.writeFrame(o, RawWs.OP_TEXT, "Hi".getBytes(StandardCharsets.UTF_8));
        byte[] b = o.toByteArray();
        assertEquals(8, b.length);
        assertEquals(0x81, b[0] & 0xFF);
        assertEquals(0x82, b[1] & 0xFF);
        byte[] mask = { b[2], b[3], b[4], b[5] };
        byte[] decoded = { (byte) (b[6] ^ mask[0]), (byte) (b[7] ^ mask[1]) };
        assertEquals("Hi", new String(decoded, StandardCharsets.UTF_8));
    }

    @Test public void writeFrameMediumLength() throws Exception {
        byte[] payload = new byte[300];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) 'a';
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        RawWs.writeFrame(o, RawWs.OP_TEXT, payload);
        byte[] b = o.toByteArray();
        assertEquals(0x80 | 126, b[1] & 0xFF);
        assertEquals(300 >> 8, b[2] & 0xFF);
        assertEquals(300 & 0xFF, b[3] & 0xFF);
        assertEquals(8 + 300, b.length);
        byte[] mask = { b[4], b[5], b[6], b[7] };
        for (int i = 0; i < payload.length; i++) {
            assertEquals(payload[i], (byte) (b[8 + i] ^ mask[i & 3]));
        }
    }

    @Test public void readServerTextFrame() throws Exception {
        byte[] frame = { (byte) 0x81, 0x02, 'h', 'i' };
        RawWs.FrameData f = RawWs.readFrameData(new ByteArrayInputStream(frame));
        assertEquals(RawWs.OP_TEXT, f.op);
        assertEquals("hi", new String(f.payload, StandardCharsets.UTF_8));
    }

    @Test public void readLength126Frame() throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x81);
        o.write(0x7E);
        o.write(0x01);
        o.write(0x2C);
        for (int i = 0; i < 300; i++) o.write('a');
        RawWs.FrameData f = RawWs.readFrameData(new ByteArrayInputStream(o.toByteArray()));
        assertEquals(300, f.payload.length);
        assertEquals('a', f.payload[299] & 0xFF);
    }

    @Test public void readPingEvenIfMasked() throws Exception {
        byte[] mask = { 0x00, 0x00, 0x00, 0x01 };
        byte[] frame = { (byte) 0x89, (byte) 0x81, mask[0], mask[1], mask[2], mask[3], (byte) ('x' ^ mask[0]) };
        RawWs.FrameData f = RawWs.readFrameData(new ByteArrayInputStream(frame));
        assertEquals(RawWs.OP_PING, f.op);
        assertEquals(1, f.payload.length);
        assertEquals('x', f.payload[0] & 0xFF ^ mask[0]);
    }

    @Test public void writeHandshakeHasFields() throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        RawWs.writeHandshake(o, "example.com", "/api/ws", "dGhlIHNhbXBsZSBub25jZQ==");
        String s = o.toString("ISO-8859-1");
        assertTrue(s.contains("GET /api/ws HTTP/1.1"));
        assertTrue(s.contains("Host: example.com"));
        assertTrue(s.contains("Upgrade: websocket"));
        assertTrue(s.contains("Sec-WebSocket-Version: 13"));
        assertTrue(s.contains("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=="));
        assertTrue(s.endsWith("\r\n\r\n"));
    }

    @Test public void readUpgradeValid() throws Exception {
        String key = "dGhlIHNhbXBsZSBub25jZQ==";
        String accept = RawWs.computeAccept(key);
        String resp = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n";
        assertEquals(accept, RawWs.readUpgrade(new ByteArrayInputStream(resp.getBytes(StandardCharsets.ISO_8859_1)), key));
    }

    @Test(expected = IOException.class) public void readUpgradeRejected() throws Exception {
        RawWs.readUpgrade(new ByteArrayInputStream("HTTP/1.1 500 oops\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)), "x");
    }
}