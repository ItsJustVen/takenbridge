package net.takensmp.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON reader/writer so the plugin has no bundled dependencies. */
public final class Json {
    private Json() {}

    // ---------------- writing ----------------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String s) {
            quote(sb, s);
        } else if (o instanceof Boolean || o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte) {
            sb.append(o);
        } else if (o instanceof Number n) {
            double d = n.doubleValue();
            sb.append(Double.isFinite(d) ? String.valueOf(n) : "null");
        } else if (o instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object x : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, x);
            }
            sb.append(']');
        } else {
            quote(sb, o.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case ' ' -> sb.append("\\u2028");
                case ' ' -> sb.append("\\u2029");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------------- reading ----------------

    /** Parse JSON text into Map / List / String / Long / Double / Boolean / null. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) throw new IllegalArgumentException("Trailing data at " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("Not a JSON object");
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        char peek() {
            if (pos >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            return s.charAt(pos);
        }

        void expect(char c) {
            if (peek() != c) throw new IllegalArgumentException("Expected '" + c + "' at " + pos);
            pos++;
        }

        Object value() {
            char c = peek();
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Object literal(String word, Object result) {
            if (!s.startsWith(word, pos)) throw new IllegalArgumentException("Bad literal at " + pos);
            pos += word.length();
            return result;
        }

        Map<String, Object> object() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                expect(':');
                skipWs();
                map.put(key, value());
                skipWs();
                if (peek() == ',') { pos++; continue; }
                expect('}');
                return map;
            }
        }

        List<Object> array() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                skipWs();
                list.add(value());
                skipWs();
                if (peek() == ',') { pos++; continue; }
                expect(']');
                return list;
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = peek();
                pos++;
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) throw new IllegalArgumentException("Bad unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("Bad escape at " + pos);
                }
            }
        }

        Object number() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            String n = s.substring(start, pos);
            if (n.isEmpty()) throw new IllegalArgumentException("Unexpected character at " + start);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            try { return Long.parseLong(n); } catch (NumberFormatException ex) { return Double.parseDouble(n); }
        }
    }
}
