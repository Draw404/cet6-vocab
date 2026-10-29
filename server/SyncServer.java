import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小枭背词 · 云同步小服务器（自托管版）
 * 只做两件事：1）GET/POST /api/sync/store 云同步（多槽位：每个口令一份独立数据）；
 *            2）/app/ 托管背词网页版（浏览器里打开就能用）。
 * 合法口令＝环境变量 VOCAB_PASS（站长本人）＋ data/passes.txt 逐行登记的口令（发给同学用）。
 * 数据在 DATA_DIR/sync-slots/<口令SHA-256>.json，覆盖前自动备份到 sync-slot-backups/（每槽留 10 份）。
 */
public class SyncServer {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final Path DATA_DIR = Paths.get(System.getenv().getOrDefault("DATA_DIR", "data"));
    static final String PASS = System.getenv("VOCAB_PASS");
    static final Path SYNC_SLOTS_DIR = DATA_DIR.resolve("sync-slots");
    static final Path SYNC_SLOT_BACKUP_DIR = DATA_DIR.resolve("sync-slot-backups");
    static final Path PASSES_FILE = DATA_DIR.resolve("passes.txt");
    static final Object LOCK = new Object();

    static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static void sendJson(HttpExchange ex, int code, Map<String, Object> m) throws IOException {
        send(ex, code, "application/json; charset=utf-8", Json.write(m));
    }

    static void cors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "X-Vocab-Pass, Content-Type");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    static Map<String, Object> readJsonBody(HttpExchange ex) throws IOException {
        String s = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Object parsed = Json.parse(s);
        if (parsed instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) parsed;
            return m;
        }
        return new LinkedHashMap<>();
    }

    static String passOf(HttpExchange ex) {
        return ex.getRequestHeaders().getFirst("X-Vocab-Pass");
    }

    static boolean checkSyncPass(String h) {
        if (h == null || h.isBlank()) return false;
        if (PASS != null && !PASS.isBlank() && PASS.equals(h)) return true;
        try {
            if (Files.exists(PASSES_FILE)) {
                for (String line : Files.readAllLines(PASSES_FILE, StandardCharsets.UTF_8)) {
                    String p = line.trim();
                    if (!p.isEmpty() && !p.startsWith("#") && p.equals(h)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    static String slotKey(String pass) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(pass.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static Path slotFile(String pass) {
        return SYNC_SLOTS_DIR.resolve(slotKey(pass) + ".json");
    }

    static Map<String, Object> readSyncEnvelope(String pass) throws IOException {
        Path f = slotFile(pass);
        if (!Files.exists(f)) return null;
        Object parsed = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
        if (parsed instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) parsed;
            return m;
        }
        return null;
    }

    static void writeSyncEnvelope(String pass, Map<String, Object> envelope) throws IOException {
        Files.createDirectories(SYNC_SLOTS_DIR);
        Path target = slotFile(pass);
        String key = slotKey(pass);
        if (Files.exists(target)) {
            Files.createDirectories(SYNC_SLOT_BACKUP_DIR);
            String stamp = java.time.LocalDateTime.now(ZONE)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Files.copy(target, SYNC_SLOT_BACKUP_DIR.resolve(key + "-" + stamp + ".json"),
                    StandardCopyOption.REPLACE_EXISTING);
            List<Path> backups = new ArrayList<>();
            try (java.util.stream.Stream<Path> st = Files.list(SYNC_SLOT_BACKUP_DIR)) {
                st.filter(p -> p.getFileName().toString().startsWith(key + "-")).forEach(backups::add);
            }
            backups.sort(null);
            while (backups.size() > 10) {
                Files.deleteIfExists(backups.remove(0));
            }
        }
        Path tmp = SYNC_SLOTS_DIR.resolve(key + ".json.tmp");
        Files.writeString(tmp, Json.write(envelope), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static class Json {
        static Object parse(String s) {
            int[] p = {0};
            Object v = value(s, p);
            return v;
        }

        static void ws(String s, int[] p) {
            while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++;
        }

        static Object value(String s, int[] p) {
            ws(s, p);
            if (p[0] >= s.length()) return null;
            char c = s.charAt(p[0]);
            if (c == '{') {
                p[0]++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws(s, p);
                if (p[0] < s.length() && s.charAt(p[0]) == '}') { p[0]++; return m; }
                while (true) {
                    ws(s, p);
                    String k = (String) value(s, p);
                    ws(s, p);
                    if (p[0] < s.length() && s.charAt(p[0]) == ':') p[0]++;
                    Object v = value(s, p);
                    m.put(k, v);
                    ws(s, p);
                    if (p[0] < s.length() && s.charAt(p[0]) == ',') { p[0]++; continue; }
                    if (p[0] < s.length() && s.charAt(p[0]) == '}') p[0]++;
                    break;
                }
                return m;
            }
            if (c == '[') {
                p[0]++;
                List<Object> l = new ArrayList<>();
                ws(s, p);
                if (p[0] < s.length() && s.charAt(p[0]) == ']') { p[0]++; return l; }
                while (true) {
                    l.add(value(s, p));
                    ws(s, p);
                    if (p[0] < s.length() && s.charAt(p[0]) == ',') { p[0]++; continue; }
                    if (p[0] < s.length() && s.charAt(p[0]) == ']') p[0]++;
                    break;
                }
                return l;
            }
            if (c == '"') {
                p[0]++;
                StringBuilder sb = new StringBuilder();
                while (p[0] < s.length()) {
                    char ch = s.charAt(p[0]++);
                    if (ch == '"') break;
                    if (ch == '\\' && p[0] < s.length()) {
                        char e = s.charAt(p[0]++);
                        switch (e) {
                            case 'n': sb.append('\n'); break;
                            case 't': sb.append('\t'); break;
                            case 'r': sb.append('\r'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'u':
                                sb.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16));
                                p[0] += 4;
                                break;
                            default: sb.append(e);
                        }
                    } else {
                        sb.append(ch);
                    }
                }
                return sb.toString();
            }
            if (s.startsWith("true", p[0])) { p[0] += 4; return Boolean.TRUE; }
            if (s.startsWith("false", p[0])) { p[0] += 5; return Boolean.FALSE; }
            if (s.startsWith("null", p[0])) { p[0] += 4; return null; }
            int start = p[0];
            while (p[0] < s.length() && "-+0123456789.eE".indexOf(s.charAt(p[0])) >= 0) p[0]++;
            String n = s.substring(start, p[0]);
            try { return Double.parseDouble(n); } catch (Exception e) { return 0.0; }
        }

        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            write(o, sb);
            return sb.toString();
        }

        static void write(Object o, StringBuilder sb) {
            if (o == null) { sb.append("null"); return; }
            if (o instanceof String) {
                sb.append('"');
                for (char ch : ((String) o).toCharArray()) {
                    switch (ch) {
                        case '"': sb.append("\\\""); break;
                        case '\\': sb.append("\\\\"); break;
                        case '\n': sb.append("\\n"); break;
                        case '\r': sb.append("\\r"); break;
                        case '\t': sb.append("\\t"); break;
                        default:
                            if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                            else sb.append(ch);
                    }
                }
                sb.append('"');
                return;
            }
            if (o instanceof Number || o instanceof Boolean) { sb.append(o.toString()); return; }
            if (o instanceof Map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    write(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    write(e.getValue(), sb);
                }
                sb.append('}');
                return;
            }
            if (o instanceof List) {
                sb.append('[');
                boolean first = true;
                for (Object v : (List<?>) o) {
                    if (!first) sb.append(',');
                    first = false;
                    write(v, sb);
                }
                sb.append(']');
                return;
            }
            write(o.toString(), sb);
        }
    }

    // ---------------- 数据加载与保存 ----------------

    static String contentTypeOf(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        return "application/octet-stream";
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Path appRoot = Paths.get("static", "app").toAbsolutePath().normalize();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            try {
                if (method.equals("OPTIONS") && path.startsWith("/api/sync/")) {
                    cors(ex);
                    ex.sendResponseHeaders(204, -1);
                    ex.close();
                    return;
                }
                switch (path) {
                    case "/api/sync/store": {
                        cors(ex);
                        String syncPass = passOf(ex);
                        if (!checkSyncPass(syncPass)) { sendJson(ex, 401, error("口令不对或未在服务器登记")); break; }
                        if (method.equals("GET")) {
                            synchronized (LOCK) {
                                Map<String, Object> env = readSyncEnvelope(syncPass);
                                Map<String, Object> m = new LinkedHashMap<>();
                                if (env == null) {
                                    m.put("exists", false);
                                } else {
                                    m.put("exists", true);
                                    m.putAll(env);
                                }
                                sendJson(ex, 200, m);
                            }
                        } else if (method.equals("POST")) {
                            Map<String, Object> body = readJsonBody(ex);
                            Object storeObj = body.get("store");
                            if (!(storeObj instanceof Map) || !(((Map<?, ?>) storeObj).get("words") instanceof List)) {
                                sendJson(ex, 400, error("数据格式不对：缺少 store.words"));
                                break;
                            }
                            @SuppressWarnings("unchecked")
                            Map<String, Object> storeMap = (Map<String, Object>) storeObj;
                            long rev = 0;
                            Object revObj = body.get("rev");
                            if (revObj instanceof Number) rev = ((Number) revObj).longValue();
                            synchronized (LOCK) {
                                Map<String, Object> env = new LinkedHashMap<>();
                                env.put("rev", rev);
                                env.put("savedAt", java.time.LocalDateTime.now(ZONE).toString());
                                env.put("store", storeMap);
                                writeSyncEnvelope(syncPass, env);
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("ok", true);
                                m.put("rev", rev);
                                m.put("words", ((List<?>) storeMap.get("words")).size());
                                sendJson(ex, 200, m);
                            }
                        } else {
                            sendJson(ex, 405, error("只支持 GET / POST"));
                        }
                        break;
                    }
                    case "/app": {
                        ex.getResponseHeaders().set("Location", "/app/");
                        ex.sendResponseHeaders(302, -1);
                        ex.close();
                        break;
                    }
                    case "/": {
                        send(ex, 200, "text/plain; charset=utf-8",
                                "小枭背词云同步服务器正在运行。\n背词网页版：/app/\n同步接口：/api/sync/store\n");
                        break;
                    }
                    default: {
                        if (path.startsWith("/app/")) {
                            String rel = path.substring("/app/".length());
                            if (rel.isEmpty()) rel = "index.html";
                            Path f = appRoot.resolve(rel).normalize();
                            if (!f.startsWith(appRoot) || !Files.isRegularFile(f)) {
                                send(ex, 404, "text/plain; charset=utf-8", "Not Found");
                            } else {
                                byte[] b = Files.readAllBytes(f);
                                ex.getResponseHeaders().set("Content-Type", contentTypeOf(f.getFileName().toString()));
                                ex.sendResponseHeaders(200, b.length);
                                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
                            }
                        } else {
                            send(ex, 404, "text/plain; charset=utf-8", "Not Found");
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                try { sendJson(ex, 500, error("服务器内部错误：" + e.getMessage())); } catch (Exception ignored) {}
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("SyncServer 已启动，监听 " + port + " 端口");
    }
}
