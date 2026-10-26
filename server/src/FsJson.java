// FsJson.java - Minimal JSON utilities (just enough for FS protocol)
// We avoid external deps to keep this self-contained. Supports: objects, arrays,
// strings, numbers, booleans, null. Decodes into java.util.Map/List tree.
package unciv.fs.local;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FsJson {
    private FsJson() {}

    /** Decode JSON text into Java Object (Map/List/String/Number/Boolean/null). */
    public static Object parse(String text) {
        return new Parser(text).parseValue();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) { return (List<Object>) o; }

    public static String str(Object o) { return o == null ? null : (String) o; }
    public static String str(Object o, String def) { return o == null ? def : (String) o; }
    public static Integer integer(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) try { return Integer.parseInt((String) o); } catch (Exception e) { return null; }
        return null;
    }
    public static Long longg(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) try { return Long.parseLong((String) o); } catch (Exception e) { return null; }
        return null;
    }
    public static Double dbl(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) try { return Double.parseDouble((String) o); } catch (Exception e) { return null; }
        return null;
    }
    public static Boolean bool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean) return (Boolean) o;
        return null;
    }

    /** Encode a tree (Map/List/String/Number/Boolean/null) to JSON text. */
    public static String encode(Object v) {
        StringBuilder b = new StringBuilder();
        encodeValue(b, v);
        return b.toString();
    }

    private static void encodeValue(StringBuilder b, Object v) {
        if (v == null) { b.append("null"); return; }
        if (v instanceof String) { encodeString(b, (String) v); return; }
        if (v instanceof Boolean) { b.append(v.toString()); return; }
        if (v instanceof Number) { b.append(v.toString()); return; }
        if (v instanceof Map) { encodeObject(b, (Map<?, ?>) v); return; }
        if (v instanceof List) { encodeArray(b, (List<?>) v); return; }
        encodeString(b, v.toString());
    }

    private static void encodeObject(StringBuilder b, Map<?, ?> m) {
        b.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) b.append(',');
            first = false;
            encodeString(b, e.getKey().toString());
            b.append(':');
            encodeValue(b, e.getValue());
        }
        b.append('}');
    }

    private static void encodeArray(StringBuilder b, List<?> l) {
        b.append('[');
        boolean first = true;
        for (Object e : l) {
            if (!first) b.append(',');
            first = false;
            encodeValue(b, e);
        }
        b.append(']');
    }

    private static void encodeString(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    private static final class Parser {
        /** 嵌套深度上限。解析器是递归下降，超深嵌套（畸形/损坏的帧）会 StackOverflowError
         *  —— 它是 Error，穿透消息边界所有 catch (Exception)，直接杀死会话线程。
         *  正常 GameInfo 的嵌套远低于此；超限抛 IllegalArgumentException（会被正常捕获）。 */
        private static final int MAX_DEPTH = 256;
        private final String s; private int p;
        private int depth = 0;
        Parser(String s) { this.s = s; this.p = 0; }
        Object parseValue() {
            if (depth >= MAX_DEPTH)
                throw new IllegalArgumentException("JSON nesting exceeds " + MAX_DEPTH);
            depth++;
            try {
                skipWs();
                char c = peek();
                if (c == '{') return parseObject();
                if (c == '[') return parseArray();
                if (c == '"') return parseString();
                if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
                if (s.startsWith("true", p)) { p += 4; return Boolean.TRUE; }
                if (s.startsWith("false", p)) { p += 5; return Boolean.FALSE; }
                if (s.startsWith("null", p)) { p += 4; return null; }
                throw new IllegalArgumentException("Bad JSON at " + p + ": " + s.substring(p, Math.min(p + 20, s.length())));
            } finally {
                depth--;
            }
        }
        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { p++; return m; }
            while (true) {
                skipWs();
                String k = parseString();
                skipWs();
                expect(':');
                Object v = parseValue();
                m.put(k, v);
                skipWs();
                char c = peek();
                if (c == ',') { p++; continue; }
                if (c == '}') { p++; return m; }
                throw new IllegalArgumentException("Bad object at " + p);
            }
        }
        List<Object> parseArray() {
            expect('[');
            List<Object> l = new ArrayList<>();
            skipWs();
            if (peek() == ']') { p++; return l; }
            while (true) {
                Object v = parseValue();
                l.add(v);
                skipWs();
                char c = peek();
                if (c == ',') { p++; continue; }
                if (c == ']') { p++; return l; }
                throw new IllegalArgumentException("Bad array at " + p);
            }
        }
        String parseString() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(p++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(p++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'u':
                            int cp = Integer.parseInt(s.substring(p, p + 4), 16);
                            p += 4;
                            b.append((char) cp);
                            break;
                        default: b.append(e);
                    }
                } else b.append(c);
            }
        }
        Number parseNumber() {
            int start = p;
            if (peek() == '-') p++;
            while (p < s.length() && "0123456789".indexOf(s.charAt(p)) >= 0) p++;
            boolean isFloat = false;
            if (p < s.length() && s.charAt(p) == '.') {
                isFloat = true; p++;
                while (p < s.length() && "0123456789".indexOf(s.charAt(p)) >= 0) p++;
            }
            if (p < s.length() && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) {
                isFloat = true; p++;
                if (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')) p++;
                while (p < s.length() && "0123456789".indexOf(s.charAt(p)) >= 0) p++;
            }
            String num = s.substring(start, p);
            return isFloat ? (Number) Double.valueOf(num) : (Number) Long.valueOf(num);
        }
        void skipWs() {
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        }
        char peek() {
            if (p >= s.length()) throw new IllegalArgumentException("Unexpected EOF");
            return s.charAt(p);
        }
        void expect(char c) {
            if (p >= s.length() || s.charAt(p) != c)
                throw new IllegalArgumentException("Expected '" + c + "' at " + p);
            p++;
        }
    }
}