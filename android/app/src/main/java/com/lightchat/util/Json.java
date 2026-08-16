package com.lightchat.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {}

    public static Map<String, Object> parseObject(String s) {
        Object r = parse(s);
        if (r instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) r;
            return m;
        }
        throw new IllegalArgumentException("Not a JSON object: " + s);
    }

    public static List<Object> parseArray(String s) {
        Object r = parse(s);
        if (r instanceof List) {
            @SuppressWarnings("unchecked") List<Object> l = (List<Object>) r;
            return l;
        }
        throw new IllegalArgumentException("Not a JSON array: " + s);
    }

    static Object parse(String s) { return new P(s).v(); }

    private static final class P {
        private final String t;
        private int i;
        private final int n;
        P(String s) { this.t = s; this.n = s.length(); this.i = 0; }

        Object v() {
            ws();
            if (i >= n) throw new IllegalArgumentException("Empty JSON");
            Object r = val();
            ws();
            if (i != n) throw new IllegalArgumentException("Trailing chars at " + i);
            return r;
        }

        private Object val() {
            ws();
            if (i >= n) throw new IllegalArgumentException("Unexpected end");
            char c = t.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (c == 't' || c == 'f') return lit();
            if (c == 'n') return nul();
            return num();
        }

        private void ws() { while (i < n) { char c = t.charAt(i); if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break; } }
        private boolean peek(char w) { return i < n && t.charAt(i) == w; }
        private IllegalArgumentException err(String m) { return new IllegalArgumentException(m); }

        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (peek('}')) { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws();
                if (!peek(':')) throw err("Expected ':' at " + i);
                i++;
                m.put(k, val());
                ws();
                if (peek(',')) { i++; continue; }
                if (peek('}')) { i++; break; }
                throw err("Expected ',' or '}' at " + i);
            }
            return m;
        }

        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (peek(']')) { i++; return l; }
            while (true) {
                l.add(val());
                ws();
                if (peek(',')) { i++; continue; }
                if (peek(']')) { i++; break; }
                throw err("Expected ',' or ']' at " + i);
            }
            return l;
        }

        private String str() {
            if (!peek('\"')) throw err("Expected '\"' at " + i);
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < n) {
                char c = t.charAt(i++);
                if (c == '\"') return sb.toString();
                if (c == '\\') {
                    if (i >= n) throw err("Unterminated escape");
                    char e = t.charAt(i++);
                    switch (e) {
                        case '\"': sb.append('\"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 > n) throw err("Bad \\u escape");
                            sb.append((char) Integer.parseInt(t.substring(i, i + 4), 16));
                            i += 4; break;
                        default: throw err("Bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw err("Unterminated string");
        }

        private Object lit() {
            if (t.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (t.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw err("Bad literal at " + i);
        }

        private Object nul() {
            if (t.startsWith("null", i)) { i += 4; return null; }
            throw err("Bad literal at " + i);
        }

        private Number num() {
            int start = i;
            boolean real = false;
            if (peek('-')) i++;
            while (i < n) {
                char c = t.charAt(i);
                if (c >= '0' && c <= '9') { i++; continue; }
                if (c == '.' || c == 'e' || c == 'E') { real = true; i++; continue; }
                if (c == '+' || c == '-') { i++; continue; }
                break;
            }
            String d = t.substring(start, i);
            if (real) return Double.parseDouble(d);
            return Long.parseLong(d);
        }
    }
}
