// FsServer.java - Main TCP/WebSocket server
//
// Rewritten 2026-09-10: heartbeats, save upload, async mode, metrics, TURN_LOCKED
// queue, civ ownership, host-only pause, gameState lock hygiene, rate limiting.
package unciv.fs.local;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public final class FsServer implements Runnable {
    public final int port;
    public final File savesDir;

    private volatile boolean running = true;
    private ServerSocket serverSocket = null;
    private final ExecutorService acceptor = Executors.newSingleThreadExecutor();
    private final ExecutorService sessions = Executors.newCachedThreadPool();
    // 线程池别太小：回合结算（doAdvanceTurn 里要写盘 + 序列化整档 + 给每个客户端发全量）
    // 和倒计时任务共用这个池，2 个线程时容易互相排队，导致"倒计时到了却没按点推"。
    private final ScheduledExecutorService scheduled = Executors.newScheduledThreadPool(2);

    /** gameId -> FsRoom */
    public final ConcurrentHashMap<String, FsRoom> rooms = new ConcurrentHashMap<>();

    /** Self-hosted lobby (rooms/members/chat) — replaces the original 110.40.151.9:8125 service. */
    public final FsLobby lobby = new FsLobby(this);

    /**
     * Optional userId -> password bindings backing GET/PUT /auth.
     * There is deliberately no account system: a player is just the userId UUID their client
     * generates. A password only exists to stop somebody else from re-using that id ("窜台").
     */
    public final FsAuth auth;

    /** 管理页口令（--admin-pass）。null = 仅允许本机(127.0.0.1)访问管理接口。 */
    public volatile String adminPass = null;
    /** 管理页独立端口（--admin-port）。0 = 与游戏端口共用（默认单端口模式）。 */
    public volatile int adminPort = 0;
    private ServerSocket adminServerSocket = null;

    /** 全局错误日志（管理页"错误日志"页数据源）：最近 200 条，满则丢最旧。 */
    private final java.util.ArrayDeque<Map<String, Object>> errorLog = new java.util.ArrayDeque<>();
    private static final int ERROR_LOG_CAP = 200;

    public void logError(String source, Throwable t) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("ts", System.currentTimeMillis());
        e.put("source", source);
        e.put("msg", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        e.put("type", t.getClass().getSimpleName());
        synchronized (errorLog) {
            while (errorLog.size() >= ERROR_LOG_CAP) errorLog.pollFirst();
            errorLog.addLast(e);
        }
    }

    public void logError(String source, String msg) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("ts", System.currentTimeMillis());
        e.put("source", source);
        e.put("msg", msg);
        e.put("type", "Error");
        synchronized (errorLog) {
            while (errorLog.size() >= ERROR_LOG_CAP) errorLog.pollFirst();
            errorLog.addLast(e);
        }
    }

    private List<Map<String, Object>> recentErrors(int count) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        synchronized (errorLog) {
            java.util.Iterator<Map<String, Object>> it = errorLog.descendingIterator();
            while (it.hasNext() && out.size() < count) out.add(it.next());
        }
        return out;
    }

    /**
     * 管理页设置：闲置自动清理阈值（小时；0 = 关闭，允许范围 12..168）。
     * 含义：一个房间"最后一次有真人在线"距现在超过该时长 → 自动删除其存档
     * （含 civs 边车与 _Preview）。有人在线的房间绝不会删。持久化在
     * savesDir 上级的 admin-settings.json（不放 Saves 里面，避免被当成存档预加载成房间）。
     */
    private volatile int autoDeleteHours = 0;

    private File adminSettingsFile() {
        return savesDir == null || savesDir.getParentFile() == null
                ? new File("admin-settings.json")
                : new File(savesDir.getParentFile(), "admin-settings.json");
    }

    private void loadAdminSettings() {
        try {
            File f = adminSettingsFile();
            if (!f.isFile()) return;
            // 极简解析：找到 autoDeleteHours 键名后取第一段数字（文件就是我们自己写的一行 JSON）
            String text = java.nio.file.Files.readString(f.toPath());
            int idx = text.indexOf("autoDeleteHours");
            if (idx >= 0) {
                int i = idx + "autoDeleteHours".length();
                while (i < text.length() && !Character.isDigit(text.charAt(i))) i++;
                StringBuilder num = new StringBuilder();
                while (i < text.length() && Character.isDigit(text.charAt(i))) {
                    num.append(text.charAt(i));
                    i++;
                }
                if (num.length() > 0) {
                    int v = Integer.parseInt(num.toString());
                    if (v == 0 || (v >= 12 && v <= 168)) autoDeleteHours = v;
                }
            }
            System.out.println("  admin     : autoDeleteHours=" + autoDeleteHours
                    + (autoDeleteHours > 0 ? "（闲置超时的存档将自动删除）" : "（自动清理关闭）"));
        } catch (Throwable t) {
            System.err.println("[admin] 读取设置失败: " + t.getMessage());
        }
    }

    private void saveAdminSettings() {
        try {
            java.nio.file.Files.writeString(adminSettingsFile().toPath(),
                    "{\"autoDeleteHours\":" + autoDeleteHours + "}");
        } catch (Throwable t) {
            System.err.println("[admin] 写设置失败: " + t.getMessage());
        }
    }

    /** 周期任务：删除闲置超期的房间存档（编号是列表请求时按创建顺序现算的，删后自然接续）。 */
    private void cleanupInactiveRooms() {
        int hours = autoDeleteHours;
        if (hours <= 0) return;
        long thresholdMs = hours * 3600_000L;
        long now = System.currentTimeMillis();
        for (FsRoom r : rooms.values()) {
            try {
                if (!r.sessions.isEmpty()) continue;                 // 有人在线绝不删
                if (now - r.lastTurnMs < thresholdMs) continue;
                rooms.remove(r.gameId);
                if (savesDir != null) {
                    new File(savesDir, r.gameId + ".json").delete();
                    new File(savesDir, r.gameId + ".civs.json").delete();
                    new File(savesDir, r.gameId + "_Preview.json").delete();
                }
                System.out.println("[admin] 自动清理：删除闲置房间存档 " + r.gameId
                        + "（上一次回合 " + ((now - r.lastTurnMs) / 3600000L) + " 小时前，阈值 " + hours + "h）");
            } catch (Throwable t) {
                System.err.println("[admin] 清理 " + r.gameId + " 失败: " + t.getMessage());
            }
        }
    }

    /** 存档里的人类文明数（= 真正参局的玩家总数）；-1 = 存档未加载。 */
    private static int saveHumanCivs(FsRoom r) {
        if (r.gameState == null || r.gameState.gameInfo == null) return -1;
        int n = 0;
        for (Object c : r.gameState.gameInfo.getCivilizations()) {
            com.unciv.logic.civilization.Civilization civ = (com.unciv.logic.civilization.Civilization) c;
            if (civ.isHuman() && !civ.isSpectator()) n++;
        }
        return n;
    }

    /** Server start time, for /metrics. */
    public final long startMs = System.currentTimeMillis();

    /** Whether to print human-readable Chinese op logs (player actions). Default on. */
    public boolean logOps = true;
    /** 每回合整档下发（客户端据此重建本地 GameInfo）。可 --no-full-save 关闭。 */
    /** 整档明细文发送（调试用）；默认走 gzip + 二进制帧。 */
    public boolean plainFullSave = false;

    public FsServer(int port, File savesDir) {
        this.port = port;
        this.savesDir = savesDir;
        this.auth = new FsAuth(FsAuth.defaultStore(savesDir));
    }

    public FsRoom roomOrCreate(String gameId) {
        FsRoom r = rooms.computeIfAbsent(gameId, k -> new FsRoom(k, this));
        // Lazy-load a server-generated save (e.g. created by the lobby /start flow) so that
        // clients connecting over WebSocket immediately receive the initial state.
        if (r.gameState == null && savesDir != null) {
            File f = new File(savesDir, gameId + ".json");
            if (f.isFile()) {
                // 无人在线的恢复房："最后在线"以存档最后落盘时间为准（自动清理的判定基准）
                r.lastTurnMs = f.lastModified();   // 存档 mtime ≈ 上一次回合（回合推进必落盘）
                r.stateLock.lock();
                try {
                    if (r.gameState == null) {
                        FsGameState gs = new FsGameState();
                        if (gs.loadFromFile(f)) {
                            r.gameState = gs;
                            if (gs.gameInfo != null) r.currentTurn = gs.gameInfo.getTurns();
                        }
                    }
                } catch (Throwable ignored) {
                } finally { r.stateLock.unlock(); }
            }
        }
        return r;
    }

    public void run() {
        try {
            serverSocket = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
        } catch (IOException e) {
            System.err.println("[fs-server] bind failed: " + e.getMessage());
            return;
        }
        System.out.println("[fs-server] listening on ws://0.0.0.0:" + port + FsProtocol.WS_PATH);
        // --admin-port 分离模式：管理页/管理API 走独立端口，游戏端口只保留联机相关端点
        if (adminPort > 0 && adminPort != port) {
            Thread adminThread = new Thread(this::runAdminPort, "fs-admin-port");
            adminThread.setDaemon(true);
            adminThread.start();
        }

        // Pre-load any saved GameInfo JSONs from savesDir so admin can quickly test.
        if (savesDir != null && savesDir.isDirectory()) {
            Thread preload = new Thread(this::preloadSaves, "fs-preload");
            preload.setDaemon(true);
            preload.start();
        }

        // 心跳已移除：客户端断线会自己重连（2s 退避），服务端靠读写失败/EOF 清理会话，
        // 不需要协议层 ping。省掉它也顺带消除了"心跳与业务消息并发写同一 socket"的风险。
        // Background civ-ownership save flush — every 60s, write any dirty rooms.
        scheduled.scheduleAtFixedRate(this::idleSessionSweep, 60, 60, TimeUnit.SECONDS);
        scheduled.scheduleAtFixedRate(this::civOwnershipFlush, 60, 60, TimeUnit.SECONDS);
        loadAdminSettings();
        // 每 5 分钟扫一次闲置房间（首扫延迟 60s）
        scheduled.scheduleAtFixedRate(this::cleanupInactiveRooms, 60, 300, TimeUnit.SECONDS);
        // UncivSync：每 1s 冲刷"op 改变权威状态后"待落盘的房间，避免"完成后没保存→重复消耗回合"。
        scheduled.scheduleAtFixedRate(this::gameSaveFlush, 1, 1, TimeUnit.SECONDS);

        while (running) {
            try {
                Socket sock = serverSocket.accept();
                sock.setTcpNoDelay(true);
                sessions.submit(() -> handleConnection(sock));
            } catch (IOException e) {
                if (!running) break;
                System.err.println("[fs-server] accept error: " + e.getMessage());
            }
        }
    }

    /** 独立管理端口的监听循环（--admin-port 分离模式）。 */
    private void runAdminPort() {
        try {
            adminServerSocket = new ServerSocket(adminPort, 25, InetAddress.getByName("0.0.0.0"));
        } catch (IOException e) {
            System.err.println("[fs-server] admin port bind failed on " + adminPort + ": " + e.getMessage());
            return;
        }
        System.out.println("[fs-server] admin listening on http://0.0.0.0:" + adminPort + "/admin");
        while (running) {
            try {
                Socket sock = adminServerSocket.accept();
                sock.setTcpNoDelay(true);
                sessions.submit(() -> handleAdminConnection(sock));
            } catch (IOException e) {
                if (!running) break;
                System.err.println("[fs-server] admin accept error: " + e.getMessage());
            }
        }
    }

    /** 管理端口连接：keep-alive 循环，全部交给 handleAdmin（"/" 跳转到 /admin）。 */
    private void handleAdminConnection(Socket sock) {
        try {
            java.io.InputStream in = sock.getInputStream();
            while (running && !sock.isClosed()) {
                sock.setSoTimeout(5000);
                RequestHead head = readRequestHead(in);
                if (head == null) break;
                if ("/".equals(head.rawUrl)) {
                    sock.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: /admin\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    sock.getOutputStream().flush();
                    sock.close();
                    return;
                }
                handleAdmin(sock, head.method, head.rawUrl);
                if (sock.isClosed()) return;
                drainRequestBody(in, head.headers);
                if (!clientWantsKeepAlive(head)) break;
            }
        } catch (java.net.SocketTimeoutException ignored) {
        } catch (Exception ignored) {
        } finally {
            try { if (!sock.isClosed()) sock.close(); } catch (IOException ignored) {}
        }
    }

    private void preloadSaves() {
        File[] files = savesDir.listFiles((d, n) -> n.endsWith(".json")
                && !n.endsWith(".civs.json")       // per-game civ ownership sidecar
                && !n.endsWith("_Preview.json")    // client-side GameInfoPreview, not a playable save
                && !n.startsWith("."));            // ".auth" (password bindings) / ".civs.json" are not saves
        if (files == null) return;
        for (File f : files) {
            try {
                String id = f.getName().replaceAll("\\.json$", "");
                FsRoom r = roomOrCreate(id);
                r.stateLock.lock();
                try {
                    r.gameState = new FsGameState();
                    boolean ok = r.gameState.loadFromFile(f);
                    if (ok && r.gameState.gameInfo != null) {
                        r.currentTurn = r.gameState.gameInfo.getTurns();
                        System.out.println("[fs-server] loaded save: " + id + " (turn " + r.currentTurn + ")");
                        r.backfillOwnershipFromSave();
                    } else {
                        String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                        int turn = 0;
                        try {
                            Object parsed = FsJson.parse(json);
                            if (parsed instanceof Map) {
                                Object t = ((Map<?, ?>) parsed).get("turns");
                                if (t instanceof Number) turn = ((Number) t).intValue();
                            }
                        } catch (Exception ignored) {}
                        r.gameState.lastSnapshotJson = json;
                        r.currentTurn = turn;
                        System.out.println("[fs-server] loaded raw save: " + id + " (turn " + turn + ")");
                    }
                } finally { r.stateLock.unlock(); }
            } catch (Exception e) {
                System.err.println("[fs-server] failed to load " + f.getName() + ": " + e.getMessage());
            }
        }
    }

    /** 读取一行 ASCII(请求行/头部)，逐字节读取避免缓冲区吞掉同段的 POST body。 */
    private static class RequestHead {
        final String method; final String rawUrl; final String version; final Map<String, String> headers;
        RequestHead(String m, String u, String v, Map<String, String> h) { method = m; rawUrl = u; version = v; headers = h; }
    }
    private RequestHead readRequestHead(java.io.InputStream in) throws IOException {
        String requestLine = readAsciiLine(in);
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return null;
        String method = parts[0];
        String rawUrl = parts[1];
        String version = parts.length > 2 ? parts[2] : "HTTP/1.0";
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readAsciiLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
        }
        return new RequestHead(method, rawUrl, version, headers);
    }

    /** 客户端是否希望复用连接：HTTP/1.1 默认 keep-alive，除非显式 Connection: close。 */
    private static boolean clientWantsKeepAlive(RequestHead h) {
        String conn = h.headers.get("connection");
        if (conn != null && conn.toLowerCase().contains("close")) return false;
        if (conn != null && conn.toLowerCase().contains("keep-alive")) return true;
        return h.version.contains("1.1");
    }

    /** 清空连接上未读的请求体（按 Content-Length），防止污染 keep-alive 的下一个请求。 */
    private static void drainRequestBody(java.io.InputStream in, Map<String, String> headers) {
        String cl = headers.get("content-length");
        if (cl == null) return;
        try {
            long n = Long.parseLong(cl.trim());
            while (n > 0) {
                long skipped = in.skip(n);
                if (skipped > 0) { n -= skipped; continue; }
                if (in.read() < 0) break;
                n -= 1;
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 连接级处理：支持 HTTP keep-alive——同一 TCP 连接上循环处理多个请求，
     * 空闲 5s 后关闭。浏览器不再每个请求都重建 TCP（云端省一个 RTT/请求），
     * 也避免"浏览器复用已死连接导致重试"的隐性延迟。
     */
    private void handleConnection(Socket sock) {
        boolean handedOff = false;   // WebSocket 会话接管 socket 后，finally 不得关闭它
        try {
            java.io.InputStream in = sock.getInputStream();
            while (running && !sock.isClosed()) {
                sock.setSoTimeout(5000);   // 等待下一个请求的空闲上限
                RequestHead head = readRequestHead(in);
                if (head == null) break;   // 对端关闭或空闲超时
                handedOff = dispatchRequest(sock, in, head.method, head.rawUrl, head.headers);
                if (handedOff) return;     // socket 已交给 WebSocket 会话，finally 跳过关闭
                if (sock.isClosed()) return; // 处理器主动关闭（错误响应/流式下载）
                // 这些路径的处理器自己按 Content-Length 读完 body，不能再排（会误吞下一请求）
                if (!head.rawUrl.startsWith("/upload/") && !head.rawUrl.startsWith("/api/")
                        && !head.rawUrl.startsWith("/files/") && !head.rawUrl.equals("/auth"))
                    drainRequestBody(in, head.headers);
                if (!clientWantsKeepAlive(head)) break;
            }
        } catch (java.net.SocketTimeoutException ignored) {
        } catch (IOException ignored) {
        } finally {
            if (!handedOff) {
                try { if (!sock.isClosed()) sock.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** 单个 HTTP 请求分发。返回 true 表示 socket 已交给 WebSocket 会话。 */
    private boolean dispatchRequest(Socket sock, java.io.InputStream in, String method, String rawUrl, Map<String, String> headers) throws IOException {
        try {
            if ("GET".equalsIgnoreCase(method) && rawUrl.startsWith(FsProtocol.WS_PATH)) {
                String key = headers.get("sec-websocket-key");
                if (key == null) { sendHttpError(sock, 400, "Missing Sec-WebSocket-Key"); return false; }
                String accept = computeSecWebSocketAccept(key);
                String url = rawUrl;
                int q = url.indexOf('?');
                Map<String, String> params = new HashMap<>();
                if (q > 0) {
                    for (String kv : url.substring(q + 1).split("&")) {
                        int eq = kv.indexOf('=');
                        if (eq > 0) params.put(urlDecode(kv.substring(0, eq)), urlDecode(kv.substring(eq + 1)));
                    }
                }
                String gameId = params.getOrDefault("gameId", "");
                String playerId = params.getOrDefault("playerId", "");
                String nickname = params.getOrDefault("nickname", "Player");
                String playerUUID = params.getOrDefault("playerUUID", "");
                String spec = params.getOrDefault("spectator", "false");
                String mode = params.getOrDefault("mode", "");
                if (gameId.isEmpty() || playerId.isEmpty()) {
                    sendHttpError(sock, 400, "gameId and playerId required");
                    return false;
                }

                OutputStream os = sock.getOutputStream();
                String upgrade =
                    "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
                os.write(upgrade.getBytes(StandardCharsets.UTF_8));
                os.flush();
                sock.setSoTimeout(0);

                FsSession sess = new FsSession(sock, this);
                sess.gameId = gameId;
                sess.playerId = playerId;
                sess.nickname = nickname;
                sess.playerUUID = playerUUID;
                sess.spectator = "true".equalsIgnoreCase(spec);
                FsRoom r = roomOrCreate(gameId);
                sess.room = r;
                if (!mode.isEmpty()) r.mode = mode;
                r.addSession(sess);
                // 空房恢复：真人（重新）接入让房间从"无人在线"转回 → 重新起一整条回合倒计时
                // （"回来起满表"；非动态 / 手动暂停时 scheduleTurnDeadline 内部会自行 no-op）。
                if (!sess.spectator && r.hasOnlineHuman() && !r.paused) {
                    r.emptySinceMs = 0;
                    scheduleTurnDeadline(r);
                }
                sess.start();
                return true;   // socket 已交给 WebSocket 会话
            } else if ("POST".equalsIgnoreCase(method) && rawUrl.startsWith("/upload/")) {
                String gameId = rawUrl.substring("/upload/".length());
                handleHttpUpload(sock, headers, gameId);
            } else if ("GET".equalsIgnoreCase(method) && rawUrl.startsWith("/download/")) {
                String gameId = rawUrl.substring("/download/".length());
                handleHttpDownload(sock, gameId);
            } else if ("GET".equalsIgnoreCase(method) && (rawUrl.equals("/isalive") || rawUrl.startsWith("/isalive?"))) {
                // 原版客户端"检测连接"按钮调用的端点（MultiplayerServer.checkServerStatus）
                // 响应体按 ServerFeatureSet 解析：{authVersion, chatVersion}
                serveIsAlive(sock);
            } else if (rawUrl.startsWith("/api/")) {
                handleLobbyHttp(sock, method, rawUrl, headers);
            } else if (rawUrl.startsWith("/files/") || rawUrl.equals("/auth")) {
                handleFileStorage(sock, method, rawUrl, headers);
            } else if (rawUrl.equals("/admin") || rawUrl.startsWith("/admin/")) {
                // --admin-port 分离模式：管理页已迁到独立端口，游戏端口不再提供
                if (adminPort > 0 && adminPort != port) {
                    sendHttpError(sock, 404, "Not Found: admin page is on port " + adminPort);
                    return false;
                }
                handleAdmin(sock, method, rawUrl);
            } else {
                // Minimal admin HTTP (debug only; not advertised in user docs)
                if (rawUrl.startsWith("/load/")) {
                    String gameId = rawUrl.substring("/load/".length());
                    handleAdminLoad(sock, gameId);
                } else if ("/metrics".equals(rawUrl) || rawUrl.startsWith("/metrics?")) {
                    serveMetrics(sock);
                } else {
                    sendHttpError(sock, 404, "Not found");
                }
            }
            return false;
        } catch (Exception e) {
            try { sock.close(); } catch (IOException ignored) {}
            return false;
        }
    }

    // ================================================================ Lobby REST API
    // Mirrors the client's LobbyApi (com.unciv.logic.lobby) so the built-in lobby UI
    // works against this self-hosted server instead of the original 110.40.151.9:8125.

    private void handleLobbyHttp(Socket sock, String method, String rawUrl, Map<String, String> headers) throws IOException {
        try {
            String url = rawUrl;
            String query = "";
            int q = url.indexOf('?');
            if (q >= 0) { query = url.substring(q + 1); url = url.substring(0, q); }
            Map<String, String> qp = parseQuery(query);

            // /api/version (the client's update check) has been removed on purpose: the client
            // side of that check is gone too (客户端源码里 UpdateChecker.checkAndPrompt 是空实现),
            // so there is no version validation anywhere any more. Requests for
            // it now simply fall through to the 404 below, which LobbyApi.checkUpdate treats as
            // "no update info" and silently ignores.
            if (url.startsWith("/api/mods") || url.startsWith("/api/download")) {
                // Mod-mirror endpoints belong to the original project and were removed from the UI.
                sendJson(sock, 200, "[]");
                return;
            }

            if (url.equals("/api/rooms")) {
                if ("POST".equalsIgnoreCase(method)) {
                    Map<String, Object> req = FsLobby.body(readBody(sock, headers));
                    sendJson(sock, 200, lobby.createRoom(req));
                } else {
                    sendJson(sock, 200, lobby.roomsJson());
                }
                return;
            }

            if (url.startsWith("/api/rooms/")) {
                String rest = url.substring("/api/rooms/".length());
                String roomId, action;
                int slash = rest.indexOf('/');
                if (slash < 0) { roomId = rest; action = ""; }
                else { roomId = rest.substring(0, slash); action = rest.substring(slash + 1); }
                roomId = urlDecode(roomId);

                if (action.isEmpty()) {
                    FsLobby.Room r = lobby.room(roomId);
                    if (r == null) { sendJson(sock, 404, FsLobby.errJson("room not found")); return; }
                    sendJson(sock, 200, FsLobby.roomJson(r));
                    return;
                }
                if (action.equals("wait")) {
                    int since = 0;
                    try { since = Integer.parseInt(qp.getOrDefault("since", "0")); } catch (Exception ignored) {}
                    String body = lobby.waitRoom(roomId, since, 25000);
                    if (body == null) { sendJson(sock, 404, FsLobby.errJson("room not found")); return; }
                    sendJson(sock, 200, body);
                    return;
                }

                Map<String, Object> req = FsLobby.body(readBody(sock, headers));
                String out;
                switch (action) {
                    case "join":        out = lobby.join(roomId, req); break;
                    case "leave":       out = lobby.leave(roomId, req); break;
                    case "exit":        out = lobby.leave(roomId, req); break;
                    case "exitconfirm": out = lobby.leave(roomId, req); break;
                    case "ready":       out = lobby.ready(roomId, req); break;
                    case "civ":         out = lobby.setCiv(roomId, req); break;
                    case "team":        out = lobby.setTeam(roomId, req); break;
                    case "kick":        out = lobby.kick(roomId, req); break;
                    case "settings":    out = lobby.updateSettings(roomId, req); break;
                    case "chat":        out = lobby.chat(roomId, req); break;
                    case "start":       out = lobby.start(roomId, req); break;
                    case "restart":     out = lobby.restart(roomId, req); break;
                    case "spectate":    out = lobby.spectate(roomId, req); break;
                    default:            sendJson(sock, 404, FsLobby.errJson("unknown action")); return;
                }
                sendJson(sock, 200, out);
                return;
            }

            sendHttpError(sock, 404, "Not found");
        } catch (Exception e) {
            sendHttpError(sock, 500, String.valueOf(e.getMessage()));
        } finally {
            try { sock.close(); } catch (IOException ignored) {}
        }
    }

    // ================================================================ Unciv file storage
    // UncivServerFileStorage protocol (identical to the original server):
    //     GET|PUT|DELETE  <server>/files/<name>
    //     GET  <server>/auth   Authorization: Basic base64(userId:password)
    //     PUT  <server>/auth   Authorization: Basic base64(userId:currentPassword), body=newPassword
    // GET /auth answers 200 = bound password verified, 204 = no password bound, 401 = wrong password
    // There is no account/login layer — the userId comes from the client, the password is optional
    // (see FsAuth for the exact semantics the client maps onto AuthStatus).

    /**
     * Decide whether a /files/ request may proceed — same rules as the original server, where
     * the realm is "Optional for /files and /auth, Mandatory for /chat":
     *
     *   no Basic header      -> allow (a browser/curl, or a client that has no password yet;
     *                           the game client itself always sends userId:passwordOrEmpty)
     *   password bound to id -> the supplied password must match, else 401
     *   no password bound    -> allow (nothing to check, the id simply has no password)
     *
     * There is no server-wide "you must log in" gate: a password only ever protects the single
     * userId it is bound to. That is exactly how yairm210/Unciv's UncivServer.kt validateAuth works.
     */
    private boolean fileAccessAllowed(Socket sock, Map<String, String> headers) throws IOException {
        String[] creds = FsAuth.parseBasic(headers.get("authorization"));
        if (creds == null || creds[0].isEmpty()) return true;
        if (auth.check(creds[0], creds[1]) == FsAuth.Status.UNAUTHORIZED) {
            sendStatus(sock, 401, "{\"error\":\"invalid password for this userId\"}");
            return false;
        }
        return true;
    }

    private void handleFileStorage(Socket sock, String method, String rawUrl, Map<String, String> headers) throws IOException {
        try {
            if (rawUrl.equals("/auth") || rawUrl.startsWith("/auth?")) {
                handleAuth(sock, method, headers);
                return;
            }
            String name = urlDecode(rawUrl.substring("/files/".length()));
            int q = name.indexOf('?');
            if (q >= 0) name = name.substring(0, q);
            if (name.isEmpty() || name.contains("..") || name.startsWith(".")) {
                sendHttpError(sock, 400, "bad name");
                return;
            }
            if (savesDir == null) { sendHttpError(sock, 503, "saves dir not configured"); return; }
            if (!fileAccessAllowed(sock, headers)) return;

            File f = new File(savesDir, name);
            // Client (UncivServerFileStorage) asks for "/files/<gameId>" while we store "<gameId>.json".
            if (!f.isFile() && !name.endsWith(".json")) {
                File alt = new File(savesDir, name + ".json");
                if (alt.isFile()) f = alt;
            }

            if ("GET".equalsIgnoreCase(method)) {
                if (!f.isFile()) { sendHttpError(sock, 404, "Not found"); return; }
                byte[] data = Files.readAllBytes(f.toPath());
                byte[] head = ("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                        + data.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                OutputStream os = sock.getOutputStream();
                os.write(head); os.write(data); os.flush();
                return;
            }
            if ("PUT".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method)) {
                byte[] body = readBodyBytes(sock, headers);
                if (body == null) { sendHttpError(sock, 400, "Bad Content-Length"); return; }
                File target = f;
                if (!name.endsWith(".json")) target = new File(savesDir, name + ".json");
                Files.write(target.toPath(), body);
                sendJson(sock, 200, "{\"ok\":true}");
                return;
            }
            if ("DELETE".equalsIgnoreCase(method)) {
                Files.deleteIfExists(f.toPath());
                sendJson(sock, 200, "{\"ok\":true}");
                return;
            }
            sendHttpError(sock, 405, "Method not allowed");
        } catch (Exception e) {
            sendHttpError(sock, 500, String.valueOf(e.getMessage()));
        } finally {
            try { sock.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * /auth — the optional "bind a password to my userId" endpoint (no accounts involved).
     *
     *   GET  -> 200 password verified | 204 no password bound to this userId | 401 wrong password
     *   PUT  -> 200 bound/changed | 401 current password rejected | 400 no header or password < 6 chars
     *
     * A player's identity is the userId UUID their own client generates (copyable/re-importable
     * from Options -> Advanced). The password is entirely optional: with none bound, every
     * request for that userId is accepted. LobbyRoomScreen.ensureSaveServerRegistered() binds a
     * random one automatically the first time the player enters a lobby game.
     * Semantics match the original project's UncivServer.kt get/put("/auth") exactly.
     */
    private void handleAuth(Socket sock, String method, Map<String, String> headers) throws IOException {
        String[] creds = FsAuth.parseBasic(headers.get("authorization"));

        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            if (creds == null || creds[0].isEmpty()) {
                // 400 = "Possibly malformed authentication header!" in the original server
                sendStatus(sock, 400, "{\"error\":\"missing userId\"}");
                return;
            }
            FsAuth.Status st = auth.check(creds[0], creds[1]);
            switch (st) {
                case VERIFIED:
                    sendStatus(sock, 200, "{\"ok\":true,\"status\":\"verified\"}");
                    return;
                case UNREGISTERED:
                    // 204 No Content: client shows "your userId is unregistered! Set password to secure your userId"
                    sendStatus(sock, 204, null);
                    return;
                default:
                    sendStatus(sock, 401, "{\"error\":\"invalid password\"}");
                    return;
            }
        }

        if ("PUT".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method)) {
            if (creds == null || creds[0].isEmpty()) {
                sendStatus(sock, 400, "{\"error\":\"missing userId\"}");
                return;
            }
            String newPassword = readBody(sock, headers).trim();
            // The original server refuses anything shorter than 6 characters.
            if (newPassword.length() < FsAuth.MIN_PASSWORD_LENGTH) {
                sendStatus(sock, 400, "{\"error\":\"password should be at least "
                        + FsAuth.MIN_PASSWORD_LENGTH + " characters long\"}");
                return;
            }
            boolean first = !auth.isBound(creds[0]);
            if (!auth.setPassword(creds[0], creds[1], newPassword)) {
                System.out.println("[auth] rejected password change for userId " + creds[0]);
                sendStatus(sock, 401, "{\"error\":\"current password rejected\"}");
                return;
            }
            System.out.println("[auth] " + (first ? "bound" : "updated") + " password for userId " + creds[0]
                    + " (bindings: " + auth.boundCount() + ")");
            sendStatus(sock, 200, "{\"ok\":true,\"status\":\"verified\"}");
            return;
        }

        sendStatus(sock, 405, "{\"error\":\"method not allowed\"}");
    }

    /** Generate a new GameInfo from a lobby room's members + settings; store it; return its gameId. */
    public String generateLobbyGame(FsLobby.Room room) {
        try {
            if (FsBootstrap.vanillaRuleset == null) {
                System.err.println("[lobby] cannot generate game: ruleset not loaded");
                return null;
            }
            com.unciv.models.metadata.GameSetupInfo info = new com.unciv.models.metadata.GameSetupInfo();
            com.unciv.models.metadata.GameParameters gp = info.getGameParameters();
            gp.setBaseRuleset(room.baseRuleset == null || room.baseRuleset.isEmpty() ? "Civ V - Vanilla" : room.baseRuleset);
            gp.setOnlineMultiplayer(true);
            gp.setEspionageEnabled(true);
            java.util.ArrayList<com.unciv.models.metadata.Player> players = new java.util.ArrayList<>();
            for (FsLobby.Member m : room.members) {
                com.unciv.models.metadata.Player p = new com.unciv.models.metadata.Player(
                        m.nickname == null || m.nickname.isEmpty() ? "Player" : m.nickname,
                        com.unciv.logic.civilization.PlayerType.Human,
                        m.playerId);
                if (m.civ != null && !m.civ.isEmpty()) p.setChosenCiv(m.civ);
                players.add(p);
            }
            if (players.isEmpty()) return null;
            // at least 2 civs for a meaningful game
            gp.setPlayers(players);

            com.unciv.logic.GameInfo gi = com.unciv.logic.GameStarter.Companion.startNewGame(info);
            String gid = gi.getGameId();
            File out = new File(savesDir, gid + ".json");
            try {
                // stamp version + checksum so the client accepts the file it downloads back
                gi.setVersion(com.unciv.logic.CompatibilityVersion.Companion.getCURRENT_COMPATIBILITY_VERSION());
                gi.setChecksum(gi.calculateChecksum());
            } catch (Throwable ignored) {}
            String json = com.unciv.json.UncivJsonKt.json().toJson(gi);
            Files.write(out.toPath(), json.getBytes(StandardCharsets.UTF_8));
            System.out.println("[lobby] generated game " + gid + " for room " + room.id
                    + " (" + players.size() + " players) -> " + out.getName());
            // pre-register the FS room so a WebSocket client gets the state immediately
            roomOrCreate(gid);
            return gid;
        } catch (Throwable t) {
            System.err.println("[lobby] generateLobbyGame failed: " + t);
            return null;
        }
    }

    private Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) m.put(urlDecode(kv.substring(0, eq)), urlDecode(kv.substring(eq + 1)));
        }
        return m;
    }

    private String readBody(Socket sock, Map<String, String> headers) {
        byte[] b = readBodyBytes(sock, headers);
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }

    private byte[] readBodyBytes(Socket sock, Map<String, String> headers) {
        int len;
        try { len = Integer.parseInt(headers.getOrDefault("content-length", "0")); } catch (Exception e) { return null; }
        if (len <= 0) return new byte[0];
        if (len > 64 * 1024 * 1024) return null;
        byte[] buf = new byte[len];
        int read = 0;
        try {
            java.io.InputStream in = sock.getInputStream();
            while (read < len) {
                int n = in.read(buf, read, len - read);
                if (n < 0) break;
                read += n;
            }
        } catch (IOException e) { return null; }
        return java.util.Arrays.copyOf(buf, read);
    }

    private void sendJson(Socket sock, int code, String json) throws IOException {
        sendStatus(sock, code, json);
    }

    /** Generic response writer; body == null means "no content" (204). */
    private void sendStatus(Socket sock, int code, String json) throws IOException {
        String status;
        switch (code) {
            case 200: status = "200 OK"; break;
            case 201: status = "201 Created"; break;
            case 204: status = "204 No Content"; break;
            case 400: status = "400 Bad Request"; break;
            case 401: status = "401 Unauthorized"; break;
            case 403: status = "403 Forbidden"; break;
            case 404: status = "404 Not Found"; break;
            case 405: status = "405 Method Not Allowed"; break;
            case 409: status = "409 Conflict"; break;
            case 503: status = "503 Service Unavailable"; break;
            default:  status = code + " Error"; break;
        }
        byte[] body = (json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8));
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append("\r\n");
        head.append("Connection: keep-alive\r\n");
        head.append("Content-Type: application/json; charset=utf-8\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n");
        head.append("Access-Control-Allow-Headers: Authorization, Content-Type\r\n");
        if (code == 401) head.append("WWW-Authenticate: Basic realm=\"unciv\"\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n\r\n");
        OutputStream os = sock.getOutputStream();
        os.write(head.toString().getBytes(StandardCharsets.UTF_8));
        if (body.length > 0) os.write(body);
        os.flush();
    }

    /** Read one CRLF-terminated header line straight from the socket (ASCII-safe, no read-ahead). */
    private static String readAsciiLine(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private void serveIsAlive(Socket sock) throws IOException {
        // Body is parsed by the client as ServerFeatureSet{kotlinx field names}.
        //   authVersion > 0  -> Options -> Multiplayer shows the (optional) "Set password" box and
        //                       the client authenticates with Basic userId:password against /auth
        //   chatVersion      -> in-game chat websocket; we keep our own lobby chat instead.
        // No version/update information is reported here — the version check is gone entirely.
        String body = "{\"authVersion\":1,\"chatVersion\":0}";
        String resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
            + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
        sock.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
        sock.getOutputStream().flush();
    }

    private void serveMetrics(Socket sock) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uptime_sec", (System.currentTimeMillis() - startMs) / 1000);
        m.put("rooms", rooms.size());
        m.put("lobby_rooms", lobby.rooms.size());
        m.put("password_bindings", auth.boundCount());
        int totalSessions = 0;
        for (FsRoom r : rooms.values()) totalSessions += r.sessions.size();
        m.put("sessions", totalSessions);
        m.put("ops_applied", FsOpHandler.APPLIED.get());
        m.put("ops_skipped", FsOpHandler.SKIPPED.get());
        m.put("ops_failed", FsOpHandler.FAILED.get());
        m.put("rooms_detail", new ArrayList<>());
        for (Map.Entry<String, FsRoom> e : rooms.entrySet()) {
            FsRoom r = e.getValue();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", e.getKey());
            d.put("turn", r.currentTurn);
            d.put("mode", r.mode);
            d.put("paused", r.paused);
            d.put("settling", r.settling);
            d.put("sessions", r.sessions.size());
            d.put("ready", r.readyPlayers.size());
            d.put("host", r.hostPlayerId);
            d.put("state_loaded", r.gameState != null && r.gameState.gameInfo != null);
            d.put("turn_locked_q", r.turnLockedQueue.size());
            ((List<Object>) m.get("rooms_detail")).add(d);
        }
        String json = FsJson.encode(m);
        String resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Access-Control-Allow-Origin: *\r\nContent-Length: " +
            json.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + json;
        sock.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
        sock.getOutputStream().flush();
        sock.close();
    }

    private void handleAdminLoad(Socket sock, String gameId) throws IOException {
        if (savesDir == null) {
            sendHttpError(sock, 503, "saves dir not configured");
            return;
        }
        File f = new File(savesDir, gameId + ".json");
        if (!f.exists()) {
            sendHttpError(sock, 404, "save not found");
            return;
        }
        try {
            FsRoom r = roomOrCreate(gameId);
            r.stateLock.lock();
            try {
                r.gameState = new FsGameState();
                boolean ok = r.gameState.loadFromFile(f);
                if (ok && r.gameState.gameInfo != null) {
                    r.currentTurn = r.gameState.gameInfo.getTurns();
                    r.backfillOwnershipFromSave();
                } else {
                    String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    r.gameState.lastSnapshotJson = json;
                }
                r.stateRevision++;
            } finally { r.stateLock.unlock(); }
            for (FsSession s : r.sessions) r.sendInitialState(s);
            String resp = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK";
            sock.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
            sock.getOutputStream().flush();
        } catch (Exception e) {
            sendHttpError(sock, 500, e.getMessage());
        } finally {
            sock.close();
        }
    }

    private void handleHttpUpload(Socket sock, Map<String, String> headers, String gameId) throws IOException {
        if (savesDir == null) { sendHttpError(sock, 503, "saves dir not configured"); return; }
        int contentLength = 0;
        try { contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0")); } catch (Exception ignored) {}
        if (contentLength <= 0 || contentLength > 64 * 1024 * 1024) {
            sendHttpError(sock, 400, "Bad Content-Length"); return;
        }
        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = sock.getInputStream().read(body, read, contentLength - read);
            if (n < 0) { sendHttpError(sock, 400, "Short body"); return; }
            read += n;
        }
        File target = new File(savesDir, gameId + ".json");
        try {
            Files.write(target.toPath(), body);
            FsRoom r = roomOrCreate(gameId);
            r.stateLock.lock();
            try {
                r.gameState = new FsGameState();
                if (!r.gameState.loadFromFile(target)) {
                    r.gameState = new FsGameState();
                    r.gameState.lastSnapshotJson = new String(body, StandardCharsets.UTF_8);
                } else {
                    r.currentTurn = r.gameState.gameInfo.getTurns();
                    r.backfillOwnershipFromSave();
                }
                r.stateRevision++;
            } finally { r.stateLock.unlock(); }
            for (FsSession s : r.sessions) r.sendInitialState(s);
            // Broadcast saveAvailable to other sessions (async mode primarily)
            r.broadcast(FsProtocol.saveAvailable(gameId, r.currentTurn, "(http)", body.length));
            String resp = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK";
            sock.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
            sock.getOutputStream().flush();
        } catch (Exception e) {
            sendHttpError(sock, 500, e.getMessage());
        } finally {
            sock.close();
        }
    }

    private void handleHttpDownload(Socket sock, String gameId) throws IOException {
        if (savesDir == null) { sendHttpError(sock, 503, "saves dir not configured"); return; }
        File f = new File(savesDir, gameId + ".json");
        if (!f.exists()) { sendHttpError(sock, 404, "save not found"); return; }
        byte[] body = Files.readAllBytes(f.toPath());
        String resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Access-Control-Allow-Origin: *\r\nContent-Length: " + body.length + "\r\n\r\n";
        sock.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
        sock.getOutputStream().write(body);
        sock.getOutputStream().flush();
        sock.close();
    }

    // ===== 管理页（/admin）：房间列表 / 详情 / 每步日志 / 重启 =====

    private void sendHttpBody(Socket sock, String contentType, byte[] body) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 200 OK\r\n");
        head.append("Connection: keep-alive\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Cache-Control: no-store\r\n\r\n");
        OutputStream os = sock.getOutputStream();
        os.write(head.toString().getBytes(StandardCharsets.UTF_8));
        os.write(body);
        os.flush();
    }

    private void sendAdminJson(Socket sock, Object o) throws IOException {
        sendHttpBody(sock, "application/json; charset=utf-8",
                FsJson.encode(o).getBytes(StandardCharsets.UTF_8));
    }

    private void handleAdmin(Socket sock, String method, String rawUrl) throws IOException {
        String path = rawUrl;
        String query = "";
        int q = rawUrl.indexOf('?');
        if (q >= 0) { path = rawUrl.substring(0, q); query = rawUrl.substring(q + 1); }
        Map<String, String> params = new LinkedHashMap<>();
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) params.put(urlDecode(kv.substring(0, eq)), urlDecode(kv.substring(eq + 1)));
        }
        // 鉴权：设了 --admin-pass 必须匹配；没设则完全开放（零配置即用，暂不设防）。
        if (adminPass != null && !adminPass.equals(params.getOrDefault("pw", ""))) {
            sendHttpError(sock, 401, "admin password required (?pw=...)"); return;
        }

        if (path.equals("/admin") || path.equals("/admin/")) {
            sendHttpBody(sock, "text/html; charset=utf-8", FsAdminPage.HTML.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (path.equals("/admin/api/overview")) { sendAdminJson(sock, adminOverview()); return; }
        if (path.equals("/admin/api/rooms")) { sendAdminJson(sock, adminRooms()); return; }
        if (path.equals("/admin/api/room")) {
            FsRoom r = rooms.get(params.getOrDefault("gameId", ""));
            if (r == null) { sendHttpError(sock, 404, "room not found"); return; }
            sendAdminJson(sock, adminRoomDetail(r));
            return;
        }
        if (path.equals("/admin/api/logs")) {
            FsRoom r = rooms.get(params.getOrDefault("gameId", ""));
            if (r == null) { sendHttpError(sock, 404, "room not found"); return; }
            int count = 200;
            try { count = Math.max(1, Math.min(FsRoom.EVENT_LOG_CAP, Integer.parseInt(params.getOrDefault("count", "200")))); } catch (Exception ignore) {}
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("gameId", r.gameId);
            out.put("events", r.recentEvents(params.getOrDefault("cat", ""), count));
            sendAdminJson(sock, out);
            return;
        }
        if (path.equals("/admin/api/settings")) {
            if ("POST".equalsIgnoreCase(method)) {
                int hours;
                try { hours = Integer.parseInt(params.getOrDefault("hours", "-1")); }
                catch (Exception e) { hours = -1; }
                if (!(hours == 0 || (hours >= 12 && hours <= 168))) {
                    sendHttpError(sock, 400, "hours must be 0 (off) or 12..168"); return;
                }
                autoDeleteHours = hours;
                saveAdminSettings();
                System.out.println("[admin] 自动清理阈值设为 " + (hours == 0 ? "关闭" : hours + " 小时"));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("autoDeleteHours", autoDeleteHours);
            sendAdminJson(sock, out);
            return;
        }
        if (path.equals("/admin/api/room/pause") || path.equals("/admin/api/room/resume")) {
            if (!"POST".equalsIgnoreCase(method)) { sendHttpError(sock, 405, "POST required"); return; }
            FsRoom r = rooms.get(params.getOrDefault("gameId", ""));
            if (r == null) { sendHttpError(sock, 404, "room not found"); return; }
            boolean wantPause = path.endsWith("/pause");
            if (wantPause) {
                pauseRoom(r, "admin");
                r.broadcastPauseNotice("admin");
                r.broadcastTurnDeadline();
            } else {
                resumeRoom(r);
                r.broadcastResumeNotice("admin");
            }
            r.broadcastTurnStatus();
            r.logEvent("system", wantPause ? "pause" : "resume", "admin");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("paused", r.paused);
            sendAdminJson(sock, out);
            return;
        }
        if (path.equals("/admin/api/room/delete")) {
            if (!"POST".equalsIgnoreCase(method)) { sendHttpError(sock, 405, "POST required"); return; }
            FsRoom r = rooms.get(params.getOrDefault("gameId", ""));
            if (r == null) { sendHttpError(sock, 404, "room not found"); return; }
            String gid = r.gameId;
            rooms.remove(gid);
            if (savesDir != null) {
                new File(savesDir, gid + ".json").delete();
                new File(savesDir, gid + ".civs.json").delete();
                new File(savesDir, gid + "_Preview.json").delete();
            }
            System.out.println("[admin] 手动删除房间: " + gid);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            sendAdminJson(sock, out);
            return;
        }
        if (path.equals("/admin/api/room/download")) {
            FsRoom r = rooms.get(params.getOrDefault("gameId", ""));
            if (r == null) { sendHttpError(sock, 404, "room not found"); return; }
            File save = savesDir == null ? null : new File(savesDir, r.gameId + ".json");
            if (save == null || !save.isFile()) { sendHttpError(sock, 404, "save not found"); return; }
            byte[] data = Files.readAllBytes(save.toPath());
            sendHttpBody(sock, "application/octet-stream", data);
            return;
        }
        if (path.equals("/admin/api/errors")) {
            int count = 100;
            try { count = Math.max(1, Math.min(ERROR_LOG_CAP, Integer.parseInt(params.getOrDefault("count", "100")))); } catch (Exception ignore) {}
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("errors", recentErrors(count));
            sendAdminJson(sock, out);
            return;
        }
        sendHttpError(sock, 404, "Not found");
    }

    private Map<String, Object> adminOverview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uptimeMs", System.currentTimeMillis() - startMs);
        m.put("version", FsProtocol.SERVER_BUILD);
        m.put("rooms", rooms.size());
        return m;
    }

    /** 房间状态码（管理页按语言渲染：run / settle / paused / frozen）。 */
    private static String roomState(FsRoom r) {
        if (r.paused) return "paused";
        if (r.settling) return "settle";
        if (r.emptySinceMs > 0) return "frozen";
        return "run";
    }

    /** 房间列表卡片/导航实际消费的字段（2026-10-03 审计裁剪：其余 11 个字段全部只有详情页用，
     *  由 adminRoomDetail() 提供；每 2 秒的列表轮询不再白送它们）。 */
    private List<Map<String, Object>> adminRooms() {
        List<FsRoom> sorted = new java.util.ArrayList<>(rooms.values());
        sorted.sort(Comparator.comparingLong(x -> x.createdAtMs));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        int idx = 1;
        for (FsRoom r : sorted) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("idx", idx++);
            d.put("gameId", r.gameId);
            d.put("turn", r.currentTurn);
            d.put("state", roomState(r));
            out.add(d);
        }
        return out;
    }

    private Map<String, Object> adminRoomDetail(FsRoom r) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("gameId", r.gameId);
        d.put("saveName", r.gameId + ".json");
        d.put("createdAtMs", r.createdAtMs);
        d.put("uptimeMs", r.uptimeMs());
        d.put("turn", r.currentTurn);
        d.put("mode", r.mode);
        d.put("state", roomState(r));
        d.put("deadlineRemainMs", r.turnDeadlineMs <= 0 ? -1 : Math.max(0, r.turnDeadlineMs - System.currentTimeMillis()));
        // 绝对时间戳 + 服务端时钟：页面本地推算倒计时/时长，不依赖轮询节奏（后台节流下数值也不会冻结）
        d.put("deadlineAtMs", r.turnDeadlineMs);
        d.put("serverNowMs", System.currentTimeMillis());
        d.put("lastTurnAbsMs", r.lastTurnMs);
        d.put("lastPersistAbsMs", r.lastPersistMs);
        d.put("revision", r.stateRevision);
        d.put("pausedBy", r.pauseNickname);
        d.put("emptySinceAgoMs", r.emptySinceMs <= 0 ? -1 : System.currentTimeMillis() - r.emptySinceMs);
        File save = savesDir == null ? null : new File(savesDir, r.gameId + ".json");
        d.put("saveExists", save != null && save.isFile());
        d.put("saveBytes", save != null && save.isFile() ? save.length() : 0);
        d.put("lastPersistAgoMs", r.lastPersistMs <= 0 ? -1 : System.currentTimeMillis() - r.lastPersistMs);
        // ── 玩家列表：以存档为权威（真人文明自带 playerId+civName），会话/注册表只补状态 ──
        // 存档可解析时严格按存档出名单：连接会话的 playerId 与存档对不上时不再多出
        // 幽灵玩家行；无存档解析（raw/被动模式）才退回 会话+注册表。
        Map<String, Map<String, Object>> live = new java.util.LinkedHashMap<>();
        // ① 在线会话
        for (FsSession x : r.sessions) {
            if (x.spectator) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("playerId", x.playerId);
            p.put("nickname", x.nickname);
            p.put("civ", r.getCivFor(x.playerId, x.playerUUID));
            p.put("online", true);
            p.put("host", x.playerId.equals(r.hostPlayerId));
            p.put("ready", r.readyPlayers.contains(x.playerId));
            p.put("avgTurnMs", r.averageTurnDuration(x.playerId));
            p.put("lastSeenAgoMs", Math.max(0, System.currentTimeMillis() - x.lastSeenMs));
            p.put("lastSeenAbsMs", x.lastSeenMs);
            live.put(x.playerId, p);
        }
        // ② civOwnership 注册过（可能离线）
        for (Map.Entry<String, String> en : r.civOwnership.entrySet()) {
            if (live.containsKey(en.getKey())) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("playerId", en.getKey());
            p.put("nickname", en.getKey());
            p.put("civ", en.getValue());
            p.put("online", false);
            p.put("host", en.getKey().equals(r.hostPlayerId));
            p.put("ready", r.readyPlayers.contains(en.getKey()));
            Long off = r.offlineSince.get(en.getKey());
            p.put("avgTurnMs", r.averageTurnDuration(en.getKey()));
            p.put("lastSeenAgoMs", off != null ? System.currentTimeMillis() - off : -1);
            p.put("lastSeenAbsMs", off != null ? off : -1);
            live.put(en.getKey(), p);
        }
        Map<String, Map<String, Object>> players = new java.util.LinkedHashMap<>();
        com.unciv.logic.GameInfo gi = (r.gameState != null) ? r.gameState.gameInfo : null;
        if (gi != null) {
            // ③ 存档权威名单：playerId / 文明直接取自存档里的真人文明
            for (Object c : gi.getCivilizations()) {
                com.unciv.logic.civilization.Civilization civ = (com.unciv.logic.civilization.Civilization) c;
                if (!civ.isHuman() || civ.isSpectator() || civ.isBarbarian()) continue;
                String civName = civ.getCivName();
                String pid = civ.getPlayerId();
                Map<String, Object> p = !pid.isEmpty() ? live.get(pid) : null;
                if (p == null) {
                    p = new LinkedHashMap<>();
                    p.put("playerId", pid);
                    p.put("nickname", civName);
                    p.put("online", false);
                    p.put("host", !pid.isEmpty() && pid.equals(r.hostPlayerId));
                    p.put("ready", !pid.isEmpty() && r.readyPlayers.contains(pid));
                    p.put("avgTurnMs", !pid.isEmpty() ? r.averageTurnDuration(pid) : -1);
                    p.put("lastSeenAgoMs", -1);
                    p.put("lastSeenAbsMs", -1);
                }
                p.put("civ", civName);   // 文明以存档为准（注册表可能是旧的）
                players.put(pid.isEmpty() ? "civ:" + civName : pid, p);
            }
        } else {
            players.putAll(live);
        }
        d.put("players", new java.util.ArrayList<>(players.values()));
        d.put("lastTurnAgoMs", Math.max(0, System.currentTimeMillis() - r.lastTurnMs));
        return d;
    }

    private void sendHttpError(Socket sock, int code, String msg) throws IOException {
        String body = code + " " + msg + "\n";
        String resp = "HTTP/1.1 " + code + " " + msg + "\r\nConnection: close\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
        sock.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
        sock.getOutputStream().flush();
        sock.close();
    }

    public void onSessionClosed(FsSession s) {
        if (s.room == null) return;
        FsRoom r = s.room;
        r.removeSession(s);
        // 空房冻结：最后一个真人离开 → 取消动态倒计时，停止时间流逝（避免无人时仍无限推进回合）。
        // 完成态已由 .14 的 persist 落盘兜底，回来直接从内存/磁盘接着玩，进度零丢失。
        if (!r.hasOnlineHuman()) {
            cancelTurnDeadline(r);
            r.turnDeadlineMs = 0;
            r.broadcastTurnDeadline();
            r.emptySinceMs = System.currentTimeMillis();
            System.out.println("[房间] 无人在线，冻结回合推进（房间 " + r.gameId + "）");
            r.logEvent("system", "empty_freeze");
        }
    }

    public void handleMessage(FsSession s, String text) {
        Object parsed;
        try { parsed = FsJson.parse(text); } catch (Exception e) {
            s.sendSafe(FsProtocol.error("Bad JSON: " + e.getMessage()));
            return;
        }
        if (!(parsed instanceof Map)) {
            s.sendSafe(FsProtocol.error("Expected object"));
            return;
        }
        @SuppressWarnings("unchecked") Map<String, Object> msg = (Map<String, Object>) parsed;
        Object typeObj = msg.get("type");
        if (!(typeObj instanceof String)) {
            s.sendSafe(FsProtocol.error("Missing type"));
            return;
        }
        String type = (String) typeObj;
        FsRoom r = s.room;
        if (r == null) {
            s.sendSafe(FsProtocol.error("Not in room"));
            return;
        }
        switch (type) {
            case FsProtocol.C_OP: handleOp(r, s, msg); break;
            case FsProtocol.C_NEXT_TURN: handleNextTurn(r, s); break;
            case FsProtocol.C_TURN_READY: handleTurnReady(r, s); break;
            case FsProtocol.C_UNCOMPLETE_TURN: handleUncompleteTurn(r, s); break;
            case FsProtocol.C_PAUSE: handlePause(r, s); break;
            case FsProtocol.C_RESUME: handleResume(r, s); break;
            case FsProtocol.C_PING: s.sendSafe("{\"type\":\"pong\"}"); break;
            case FsProtocol.C_PONG: break;
            case FsProtocol.C_UPLOAD_SAVE: handleUploadSave(r, s, msg); break;
            case FsProtocol.C_CLAIM_CIV: handleClaimCiv(r, s, msg); break;
            case FsProtocol.C_KICK: handleKick(r, s, msg); break;
            default:
                s.sendSafe(FsProtocol.error("Unknown message type: " + type));
        }
    }

    // ===== op handler =====

    private void handleOp(FsRoom r, FsSession s, Map<String, Object> msg) {
        Object opObj = msg.get("op");
        Object dataObj = msg.get("data");
        if (!(opObj instanceof String)) { s.sendSafe(FsProtocol.error("Missing op")); return; }
        @SuppressWarnings("unchecked") Map<String, Object> data = dataObj instanceof Map ? (Map<String, Object>) dataObj : new HashMap<>();
        String op = (String) opObj;

        // 中文操作日志：玩家昵称 做了什么（含参数、结果）
        if (logOps) {
            System.out.println("[操作] 玩家[" + s.nickname + "] pid=" + s.playerId + " uuid=" + s.playerUUID
                    + " " + FsLog.describeOp(op) + FsLog.briefData(data));
        }

        // 统一 op 管线（见 FsOpPipeline）：门控校验 → 权威执行 → 计算 diff → 回推 sim / 单发退回。
        // op 语义集中在 FsOpRegistry（分类），这里只负责发送与日志。
        FsOpPipeline.Outcome out = FsOpPipeline.process(r, s, op, data);

        // 1) 回显给其他玩家（门控未放行时不广播，避免"幽灵操作"出现在别人屏幕上）
        if (out.peerEchoJson != null && out.allowed) r.broadcastExcept(out.peerEchoJson, s);

        // 2) 权威状态增量广播给所有人。带战斗结果时不共享同一份字符串：
        //    battle 段只追加给"按视野能看到这场战斗"的接收者，迷雾外的玩家
        //    不弹扣血提示（对齐核心 MapVisualization.isAttackVisible 的语义）。
        if (out.simJson != null) {
            if (out.battleDiff != null && out.battleDiff.hasBattle) {
                com.unciv.logic.GameInfo bgi = r.gameState != null ? r.gameState.gameInfo : null;
                String battleJson = null;
                for (FsSession x : r.sessions) {
                    String j = out.simJson;
                    if (bgi != null && battleVisibleTo(bgi, x.playerId, out.battleDiff)) {
                        if (battleJson == null) battleJson = buildBattleJson(out.battleDiff);
                        j = appendBattleToState(out.simJson, battleJson);
                    }
                    sendStateMessage(x, j);
                }
            } else {
                broadcastStateMessage(r, out.simJson);
            }
        }

        // 3) 失败 → 单发发起者一条 opRejected（不带坐标载荷：乐观回滚机制已整体移除）
        if (out.rejectJson != null) s.sendSafe(out.rejectJson);

        if (logOps) {
            if (!out.allowed) {
                System.out.println("         └─ 拒绝 ✗ " + out.reason);
            } else if (out.applied) {
                System.out.println("         └─ 成功 ✓" + (out.noOp ? "（仅记录）" : ""));
            } else {
                System.out.println("         └─ 失败 ✗ " + out.reason);
            }
        }

        // 管理页房间日志：每条 op 一个事件（谁、做了什么、结果）
        if (!out.allowed) {
            r.logEvent("op", "op_reject", s.nickname, out.op, out.reason == null ? "" : out.reason);
        } else if (out.applied && !out.noOp) {
            r.logEvent("op", "op_ok", s.nickname, out.op, out.revision);
        } else if (out.applied) {
            r.logEvent("op", "op_deferred", s.nickname, out.op);
        } else {
            r.logEvent("op", "op_fail", s.nickname, out.op, out.reason == null ? "" : out.reason);
        }

        // 4) 每个成功 op 的 sim 已经自带官方完整 GameInfo。
        //    不再维护"哪些 op 需要额外整档"的脆弱名单；结构、宗教、地形
        //    与今后新增的存档字段都走同一条可验证通道。

        // 5) UncivSync：op 改变了权威状态后尽快落盘。
        //    以前只有回合推进(doAdvanceTurn)才 persist，实时模式下"完成后没保存"会让重连 /
        //    WorldScreen 重建 / 服务端重启读到陈旧存档 → 已完成的科技/政策/建筑被打回、玩家
        //    重复消耗回合。这里置脏标记，距上次落盘不足 1s 的交给 1s 定时冲刷，否则立即整档，
        //    保证权威存档最多落后约 1~2s。落盘由 FsRoom.persist() 在 stateLock 内完成，
        //    不会与并发的 nextTurn / 其他 op 竞争序列化。
        // simJson 非空也置脏：失败（applied=false）但中途已改动状态的 op 会回推 sim，
        // 同样的改动也必须尽快落盘，否则服务端重启后这部分状态消失。
        if ((out.applied || out.simJson != null) && out.allowed) {
            long now = System.currentTimeMillis();
            r.saveDirtyMs = now;
            if (now - r.lastPersistMs >= 1000) r.persist();
        }
    }

    // ===== turn flow =====

    private void handleNextTurn(FsRoom r, FsSession s) {
        if (logOps) System.out.println("[回合] 玩家[" + s.nickname + "] 点击「结束回合」");
        // Single-player: there is no "everyone ready" to wait for. Advance the
        // turn immediately so the solo player can actually play. Never settling.
        int totalPlayers = humanPlayerCount(r);
        r.recordTurnDuration(s.playerId);
        if (totalPlayers <= 1) {
            // 兜底：推进链里的异常在 doAdvanceTurn 内部已被吸收，这里再拦一层
            // Error（OOM/NoClassDefFoundError…），保证会话线程不因一次推进失败而死。
            try {
                doAdvanceTurn(r);
            } catch (Throwable t) {
                System.err.println("[nextturn] single-player advance crash: " + t);
                t.printStackTrace();
            }
            return;
        }
        if ("async".equals(r.mode)) {
            // In async mode each player finishes their own turn and uploads their
            // save. nextTurn from a player just marks them done + tells others to
            // download the new save (handled in handleUploadSave).
            r.stateLock.lock();
            try {
                r.readyPlayers.add(s.playerId);
                r.broadcastTurnStatus();
            } finally { r.stateLock.unlock(); }
            return;
        }
        r.stateLock.lock();
        try {
            r.readyPlayers.add(s.playerId);
            checkTurnAdvance(r);
        } finally { r.stateLock.unlock(); }
        r.broadcastTurnStatus();
    }

    private void handleTurnReady(FsRoom r, FsSession s) {
        if (s.spectator) {
            // 观战没有"自己的回合"——不进就绪表、不参与计数、更不能触发推进
            if (logOps) System.out.println("[回合] 观战[" + s.nickname + "] turnReady 忽略");
            return;
        }
        if (logOps) System.out.println("[回合] 玩家[" + s.nickname + "] 已就绪");
        r.recordTurnDuration(s.playerId);
        r.stateLock.lock();
        try {
            r.readyPlayers.add(s.playerId);
            checkTurnAdvance(r);
        } finally { r.stateLock.unlock(); }
        r.broadcastTurnStatus();
    }

    private void handleUncompleteTurn(FsRoom r, FsSession s) {
        if (logOps) System.out.println("[回合] 玩家[" + s.nickname + "] 撤销「结束回合」");
        boolean rejected = false;
        r.stateLock.lock();
        try {
            if (r.settling) {
                // 已经开始结算了：这时少一个人，会让"全员就绪"的判定和已排程的推进对不上。
                // 明确拒绝并告诉他原因 —— 以前是静默移除，表现出来就是"点了没反应"。
                rejected = true;
            } else {
                r.readyPlayers.remove(s.playerId);
            }
        } finally { r.stateLock.unlock(); }
        if (rejected) {
            s.sendSafe(FsProtocol.error("正在结算本回合，稍后再试"));
            return;
        }
        r.broadcastTurnStatus();
    }

    private void checkTurnAdvance(FsRoom r) {
        // 空房冻结：无任何真人在线时不推进（否则全离线时 humanPlayerCount 返回 0，
        // 会让 readyPlayers.size() >= 0 恒真而误推进）。有人在线才走"全员就绪"链路。
        if (!r.hasOnlineHuman()) return;
        int totalPlayers = humanPlayerCount(r);
        // Single-player (or zero-player) rooms must NEVER enter the settling lock.
        // The settling lock is a "all players ready -> lock UI -> roll turn" flow for
        // real-time multiplayer; with only one player it would fire immediately
        // (1 ready >= 1 total) and lock the whole UI (settleBlocker) so nothing works.
        // 注意这里看的是**名册**（knownHumanPlayers）而不是"当前有效人数" ——
        // 多人房里另一个人掉线超时后会只剩 1 个有效玩家，那种情况必须照常走结算流程。
        if (knownHumanCount(r) <= 1) return;
        if (r.readyPlayers.size() >= totalPlayers && !r.settling) {
            r.settling = true;
            r.lastSettlingStartMs = System.currentTimeMillis();
            final int turnAtSchedule = r.currentTurn;
            // 结算锁时长随存档走（GameParameters.fsSettleLockSeconds，客户端读同一个数），
            // 不再硬编码 1 秒 —— 对齐 UncivGC 的做法。夹在 0~10 秒防脏数据。
            int lockSec = 1;
            try {
                com.unciv.models.metadata.GameParameters gp = r.gameState == null || r.gameState.gameInfo == null
                        ? null : r.gameState.gameInfo.getGameParameters();
                if (gp != null) lockSec = Math.max(0, Math.min(10, gp.getFsSettleLockSeconds()));
            } catch (Throwable ignore) {
            }
            // Schedule the actual turn advance after the settling lock.
            scheduled.schedule(() -> {
                try {
                    // 空房冻结：等锁期间最后一个人走了，不再推进（进度已落盘，不丢）；只解开结算遮罩
                    if (!r.hasOnlineHuman()) {
                        r.stateLock.lock();
                        try { r.settling = false; } finally { r.stateLock.unlock(); }
                        return;
                    }
                    doAdvanceTurn(r, turnAtSchedule);
                } catch (Throwable t) {
                    System.err.println("[nextturn] crash: " + t.getMessage());
                    t.printStackTrace();
                } finally {
                    r.stateLock.lock();
                    try { r.settling = false; } finally { r.stateLock.unlock(); }
                    // 必须再广播一次 turnStatus：doAdvanceTurn 里那次广播发生时 settling 还是 true，
                    // 客户端拿到的是"正在结算"。少了这一条，客户端的结算遮罩永远解不开
                    // （以前客户端不看 settling，所以这个洞一直没露出来）。
                    r.broadcastTurnStatus();
                }
            }, lockSec, TimeUnit.SECONDS);
        }
    }

    /**
     * How many real (non-spectator) players this game has.
     *
     * Uses the room's persistent player roster ([FsRoom.knownHumanPlayers]) rather than the
     * live session list: a reconnect gap must not shrink the count from 2 to 1, because that
     * would send the turn through the "solo player, advance immediately" branch and roll the
     * turn an extra time. Falls back to the online count when the roster is empty (e.g. the
     * server was restarted and nobody has reconnected yet).
     */
    /**
     * "必须就绪"的真人玩家数。
     *
     * ⚠️ 不能直接用 `knownHumanPlayers.size()`：那份名册**只增不减**，一个人拔网线走了，
     * 门槛就永远凑不齐 → 整局卡死（表现：回合不推进、科技进度不动、谁也动不了）。
     * 所以掉线超过 [OFFLINE_GRACE_MS] 的人不再计入门槛 —— 他回来时会自动重新计入。
     */
    private static final long OFFLINE_GRACE_MS = 60_000L;

    private int humanPlayerCount(FsRoom r) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (String pid : r.knownHumanPlayers) {
            Long off = r.offlineSince.get(pid);
            if (off != null && now - off > OFFLINE_GRACE_MS) continue;   // 离线太久：不等他
            n++;
        }
        if (n > 0) return n;
        int online = 0;
        for (FsSession ss : r.sessions) if (!ss.spectator) online++;
        return online;
    }

    /** 名册里到底登记过几个真人 —— 用来区分"真正的单人房"和"多人房里暂时只剩一个人"。 */
    private int knownHumanCount(FsRoom r) {
        int known = r.knownHumanPlayers.size();
        if (known > 0) return known;
        int online = 0;
        for (FsSession ss : r.sessions) if (!ss.spectator) online++;
        return online;
    }

    private void doAdvanceTurn(FsRoom r) {
        doAdvanceTurn(r, -1);
    }

    /**
     * @param expectedTurn 调度这条推进时记录的回合号；>=0 时若当前回合已不是它，
     *                     说明这一回合已经被别的路径（另一个玩家的 ready / 动态倒计时）
     *                     推进过了 —— 直接跳过，避免"连续推进 / 多推一个回合"。
     *                     传 -1 表示不校验（单机路径）。
     */
    private boolean doAdvanceTurn(FsRoom r, int expectedTurn) {
        boolean advanced = false;
        r.stateLock.lock();
        boolean skipped = false;
        try {
            if (expectedTurn >= 0 && r.currentTurn != expectedTurn) {
                if (logOps) System.out.println("[回合] 跳过重复推进（调度时第 " + expectedTurn
                        + " 回合，当前已经是第 " + r.currentTurn + " 回合）");
                skipped = true;
                r.logEvent("turn", "turn_skip_dup", expectedTurn, r.currentTurn);
            } else {
            // Apply queued TURN_LOCKED ops first
            List<FsRoom.TurnLockedOp> queued = r.drainTurnLocked();
            if (!queued.isEmpty() && r.gameState != null && r.gameState.gameInfo != null) {
                System.out.println("[nextturn] applying " + queued.size() + " turn-locked ops");
                FsOpHandler.setCurrentGameInfo(r.gameState.gameInfo);
                try {
                    for (FsRoom.TurnLockedOp op : queued) {
                        try {
                            FsOpHandler.applyAndDiff(r.gameState.gameInfo, op.playerId, op.op, op.data);
                        } catch (Throwable t) {
                            System.err.println("[nextturn] locked op " + op.op + " failed: " + t.getMessage());
                        }
                    }
                } finally { FsOpHandler.setCurrentGameInfo(null); }
            }
            if (r.gameState != null && r.gameState.gameInfo != null) {
                // 推进抛异常也必须继续走完本方法：清 ready、落盘、广播 saveUpdated，
                // 让客户端对齐服务端现状。若在这里中断（异常带出锁外），readyPlayers
                // 残留 + settling 已被外层 finally 复位 → 下一次就绪检查立即再次触发
                // 推进，半结算的回合会被重跑一遍（生产/科研双倍结算）。
                try {
                    r.gameState.nextTurn();
                } catch (Throwable t) {
                    System.err.println("[nextturn] advance crashed (state partially settled): " + t);
                    t.printStackTrace();
                }
                r.persist();
                r.currentTurn = r.gameState.gameInfo.getTurns();
                r.stateRevision++;
                advanced = true;
                if (logOps) System.out.println("[回合] ✅ 推进到第 " + r.currentTurn + " 回合（房间 " + r.gameId + "）");
                r.logEvent("turn", "turn_advance", r.currentTurn - 1, r.currentTurn, r.stateRevision);
                r.lastTurnMs = System.currentTimeMillis();   // 闲置判定基准：每回合刷新
                r.currentTurnStartMs = System.currentTimeMillis();
            } else {
                r.currentTurn++;
                if (r.gameState != null) r.gameState.lastSnapshotJson = null;
                advanced = true;
            }
            }
        } finally {
            // 本轮已离开"等待 ready"状态：无论上面是否抛异常都必须清掉 ready 名单，
            // 否则残留的 ready 会让下一次就绪检查（readyPlayers.size() >= totalPlayers
            // && !settling）立刻再次触发推进。跳过路径（这回合已被别的路径推进过、
            // 那条路径自己清过）不清 —— 新回合的就绪标志可能已经进来，清了会丢。
            if (!skipped) r.readyPlayers.clear();
            r.stateLock.unlock();
        }

        if (!advanced) return false;

        // Notify clients the authoritative save changed so they reload (saveUpdated ->
        // FrameSync.reloadGame). Also push the full sim snapshot directly so clients get
        // the new state without waiting for a separate download round-trip.
        Map<String, Object> su = new LinkedHashMap<>();
        su.put("type", "saveUpdated");
        su.put("turn", r.currentTurn);
        r.broadcast(FsJson.encode(su));
        r.broadcastTurnStatus();
        // 回合快照也走和 op 相同的"轻量刷新 + 完整 GameInfo + revision"单通道。
        // UncivSync: arm the dynamic-turn countdown for the new turn (no-op when not dynamic).
        scheduleTurnDeadline(r);
        r.stateLock.lock();
        try {
            for (FsSession s : r.sessions) r.sendInitialState(s);
        } finally { r.stateLock.unlock(); }
        return true;
    }

    /** gzip 压缩完整权威状态消息。 */
    private static byte[] gzip(byte[] data) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(data.length / 8 + 64);
            try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos, 8192)) {
                gz.write(data);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            System.err.println("[存档] gzip failed: " + t.getMessage());
            return null;
        }
    }

    /** Complete-state messages are gzip binary frames; text is a safe fallback only if gzip fails. */
    void sendStateMessage(FsSession session, String text) {
        if (session == null || text == null) return;
        byte[] zipped = gzip(text.getBytes(StandardCharsets.UTF_8));
        if (zipped != null) session.sendBinary(zipped);
        else session.sendSafe(text);
    }

    private void broadcastStateMessage(FsRoom room, String text) {
        if (room == null || text == null) return;
        byte[] zipped = gzip(text.getBytes(StandardCharsets.UTF_8));
        if (zipped == null) {
            room.broadcast(text);
            return;
        }
        for (FsSession session : room.sessions) session.sendBinary(zipped);
    }

    /**
     * Arm the turn countdown if this room uses dynamic simultaneous turns. When dynamic the
     * turn advances automatically once the (sqrt-curve) countdown expires, even if a player
     * hasn't ended their turn. When not dynamic there is no countdown — the turn only
     * advances once every human player is ready (see checkTurnAdvance).
     */
    private void scheduleTurnDeadline(FsRoom r) {
        scheduleTurnDeadline(r, -1L);
    }

    /**
     * @param explicitDelayMs > 0 时用它替代按回合算出来的时长
     *                        （用在"暂停恢复"——把暂停时冻下来的剩余时间接上，不偷玩家的时间）
     */
    private void scheduleTurnDeadline(FsRoom r, long explicitDelayMs) {
        try {
            // 空房冻结：无真人在线则不起表（并清掉客户端倒计时显示）。有人时由 addSession / 回合推进
            // 触发本方法重新起表；空房时由 onSessionClosed 负责取消。
            if (!r.hasOnlineHuman()) {
                r.turnDeadlineMs = 0;
                r.broadcastTurnDeadline();
                return;
            }
            if (r.gameState == null || r.gameState.gameInfo == null) return;
            com.unciv.models.metadata.GameParameters gp = r.gameState.gameInfo.getGameParameters();
            if (gp == null || !gp.getSimultaneousTurns()) { r.turnDeadlineMs = 0; r.broadcastTurnDeadline(); return; }
            if (!gp.getFsDynamicTurns()) { r.turnDeadlineMs = 0; r.broadcastTurnDeadline(); return; }
            // 暂停中不排程（恢复时再排）—— 而且要**把已有的任务取消掉**：
            // 只"不排新的"是不够的，之前排好的那个还在跑，到期照样强推回合。
            if (r.paused) {
                cancelTurnDeadline(r);
                r.turnDeadlineMs = 0;
                r.broadcastTurnDeadline();
                return;
            }
            // UncivSync: 第一个回合不设倒计时，给刚进房/还在布阵的玩家充分时间。
            // 本房间载入后的第 1 个回合 currentTurn==0 即"所有玩家进来的第一个回合"；
            // 第 2 个回合（currentTurn>=1）起才开始限时（用户要求）。
            if (r.currentTurn <= 0) {
                r.turnDeadlineMs = 0;
                r.broadcastTurnDeadline();
                System.out.println("[turn] 第 1 回合（turn " + r.currentTurn + "）不限时");
                return;
            }
            int turn = r.currentTurn;
            // UncivSync: 动态回合时长 = 一次函数 y = 120 + 5x（x=当前回合数，单位秒）。
            // 2026-10-05 用户要求替换此前的 sqrt 曲线；第 1 回合（turn 0）仍不限时。
            int seconds = 120 + 3 * turn;
            if (seconds < 1) seconds = 1;
            long delayMs = seconds * 1000L;
            if (explicitDelayMs > 0) {
                delayMs = explicitDelayMs;
                seconds = (int) Math.max(1L, Math.round(delayMs / 1000.0));
            }
            // 取消上一回合遗留的倒计时任务：否则它到期时会把"当前回合"又强推一次
            // （表现：回合实际时长远短于设定值、连续跳回合）。
            cancelTurnDeadline(r);
            final int armedTurn = turn;
            r.turnDeadlineMs = System.currentTimeMillis() + delayMs;
            r.deadlineTurn = turn;
            System.out.println("[turn] dynamic deadline armed for turn " + turn + ": " + seconds + "s");
            r.logEvent("system", "deadline_armed", turn, seconds);
            r.deadlineFuture = scheduled.schedule(() -> {
                // 双保险：暂停期间到期的任务一律不推（暂停时已经 cancel 过，这里是兜底）
                if (r.paused) {
                    if (logOps) System.out.println("[turn] 倒计时到期但房间处于暂停 → 不强推（第 " + armedTurn + " 回合）");
                    return;
                }
                // 空房冻结兜底：到期时若已无真人在线，不推（避免无人时仍强推回合）
                if (!r.hasOnlineHuman()) {
                    if (logOps) System.out.println("[turn] 倒计时到期但无人在线 → 不强推（第 " + armedTurn + " 回合）");
                    return;
                }
                int humans = knownHumanCount(r);
                if (humans <= 1) {
                    if (logOps) System.out.println("[turn] 倒计时到期，但房间只有 " + humans
                            + " 名真人玩家 → 不强推（第 " + armedTurn + " 回合）");
                    return;
                }
                forceTurnAdvance(r, armedTurn);
            }, delayMs, TimeUnit.MILLISECONDS);
            // 把本回合剩余时间告诉客户端（右上角显示 mm:ss 倒计时）
            r.broadcastTurnDeadline();
        } catch (Throwable t) {
            System.err.println("[turn] scheduleTurnDeadline failed: " + t.getMessage());
        }
    }

    /** 取消当前驻留的动态倒计时任务（回合推进 / 重新排程前必须调用）。 */
    private void cancelTurnDeadline(FsRoom r) {
        try {
            if (r.deadlineFuture != null) {
                r.deadlineFuture.cancel(false);
                r.deadlineFuture = null;
            }
        } catch (Throwable ignored) { }
    }

    /**
     * 倒计时到期 → 强制结束本回合。
     *
     * 做法：**不另开一条推进路径**，而是把所有真人玩家都标记为"已结束回合"，
     * 然后交给正常的"全员就绪"链路（checkTurnAdvance）去推进 ——
     * 链路单一，后面（整档下发 / 倒计时重排 / ready 清理）全都自动走通，少出边角 bug。
     */
    private void forceTurnAdvance(FsRoom r, int armedTurn) {
        r.stateLock.lock();
        try {
            // 过期任务（该回合早已被推进过）直接丢弃，绝不能让旧任务误推当前回合
            if (armedTurn >= 0 && r.currentTurn != armedTurn) {
                if (logOps) System.out.println("[turn] 倒计时任务已过期（调度第 " + armedTurn
                        + " 回合，当前第 " + r.currentTurn + " 回合）→ 忽略");
                return;
            }
            if (r.settling) {
                if (logOps) System.out.println("[turn] 倒计时到期，但正在结算中，跳过（第 " + armedTurn + " 回合）");
                return;
            }
            if (logOps) System.out.println("[turn] ⏰ 倒计时到期（第 " + r.currentTurn
                    + " 回合）→ 强制所有玩家结束回合");
            r.logEvent("turn", "deadline_forced", r.currentTurn);
            // 关键：把所有真人玩家都置为 ready，随后交给标准链路
            for (String pid : r.knownHumanPlayers) r.readyPlayers.add(pid);
            if (r.knownHumanPlayers.isEmpty()) {
                for (FsSession ss : r.sessions) if (!ss.spectator) r.readyPlayers.add(ss.playerId);
            }
            checkTurnAdvance(r);
        } catch (Throwable t) {
            System.err.println("[turn] forceTurnAdvance failed: " + t.getMessage());
        } finally {
            r.stateLock.unlock();
        }
    }

    // ===== pause / resume =====

    /**
     * 暂停 / 恢复：**任意真人玩家**都可以（对齐 UncivGC 的 togglePause）。
     *
     * 以前限房主，但客户端现在有了暂停按钮 —— 非房主点了只会拿到一句拒绝，
     * 等于给了个按不动的按钮。小圈子联机里"谁都能暂停"更实用。
     * 观战者仍然不给动。
     */
    private void handlePause(FsRoom r, FsSession s) {
        if (s.spectator) {
            s.sendSafe(FsProtocol.error("观测者不能暂停"));
            return;
        }
        pauseRoom(r, s.nickname);
        r.broadcastPauseNotice(s.nickname);
        // remainingMs = -1 → 客户端收起右上角的 mm:ss
        r.broadcastTurnDeadline();
        r.broadcastTurnStatus();
        r.logEvent("system", "pause", s.nickname);
    }

    /** 暂停核心（玩家 handlePause 与管理页 API 共用）。
     *  暂停必须把动态倒计时**也停掉**：否则表照走，到期 forceTurnAdvance 会把
     *  全员置 ready 直接把回合推过去 —— 暂停等于无效（玩家会看到"暂停了但回合还是跳了"）。
     *  把剩余时间冻起来，恢复时接着走，不偷玩家的时间。 */
    private boolean pauseRoom(FsRoom r, String who) {
        r.stateLock.lock();
        try {
            if (r.paused) return false;
            r.paused = true;
            r.pauseNickname = who;
            r.pausedRemainingMs = r.turnDeadlineMs <= 0
                    ? -1L
                    : Math.max(0L, r.turnDeadlineMs - System.currentTimeMillis());
            cancelTurnDeadline(r);
            r.turnDeadlineMs = 0;
            return true;
        } finally { r.stateLock.unlock(); }
    }

    /** 恢复核心（玩家 handleResume 与管理页 API 共用）：用暂停时冻下来的剩余时间重新排程。 */
    private boolean resumeRoom(FsRoom r) {
        long remainingToRestore = -1L;
        r.stateLock.lock();
        try {
            if (!r.paused) return false;
            r.paused = false;
            r.pauseNickname = null;
            remainingToRestore = r.pausedRemainingMs;
            r.pausedRemainingMs = -1L;
        } finally { r.stateLock.unlock(); }
        if (remainingToRestore > 0) scheduleTurnDeadline(r, remainingToRestore);
        else scheduleTurnDeadline(r);
        return true;
    }

    private void handleResume(FsRoom r, FsSession s) {
        if (s.spectator) {
            s.sendSafe(FsProtocol.error("观测者不能恢复对局"));
            return;
        }
        resumeRoom(r);
        r.broadcastResumeNotice(s.nickname);
        r.broadcastTurnStatus();
        r.logEvent("system", "resume", s.nickname);
    }


    // ===== save upload / download =====

    private void handleUploadSave(FsRoom r, FsSession s, Map<String, Object> msg) {
        Object jsonObj = msg.get("saveJson");
        if (!(jsonObj instanceof String)) {
            s.sendSafe(FsProtocol.error("Missing saveJson")); return;
        }
        String saveJson = (String) jsonObj;
        if (savesDir == null) { s.sendSafe(FsProtocol.error("saves dir not configured")); return; }
        File target = new File(savesDir, r.gameId + ".json");
        try {
            Files.write(target.toPath(), saveJson.getBytes(StandardCharsets.UTF_8));
            r.stateLock.lock();
            try {
                r.gameState = new FsGameState();
                if (!r.gameState.loadFromFile(target)) {
                    r.gameState = new FsGameState();
                    r.gameState.lastSnapshotJson = saveJson;
                } else {
                    r.currentTurn = r.gameState.gameInfo.getTurns();
                    r.backfillOwnershipFromSave();
                }
                r.stateRevision++;
            } finally { r.stateLock.unlock(); }
            // Mark this player ready in async mode and tell others to download
            if ("async".equals(r.mode)) {
                r.stateLock.lock();
                try { r.readyPlayers.add(s.playerId); } finally { r.stateLock.unlock(); }
            }
            for (FsSession ss : r.sessions) if (ss != s) r.sendInitialState(ss);
            r.broadcast(FsProtocol.saveAvailable(r.gameId, r.currentTurn, s.nickname, saveJson.length()));
            r.broadcastTurnStatus();
            s.sendSafe("{\"type\":\"uploadOk\"}");
        } catch (Exception e) {
            s.sendSafe(FsProtocol.error("uploadSave failed: " + e.getMessage()));
        }
    }

    // ===== civ ownership =====

    private void handleClaimCiv(FsRoom r, FsSession s, Map<String, Object> msg) {
        Object civObj = msg.get("civId");
        if (!(civObj instanceof String)) { s.sendSafe(FsProtocol.error("Missing civId")); return; }
        String civId = (String) civObj;
        r.stateLock.lock();
        try {
            r.setCivFor(s.playerId, s.playerUUID, civId);
        } finally { r.stateLock.unlock(); }
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("type", "claimOk");
        reply.put("civId", civId);
        s.sendSafe(FsJson.encode(reply));
    }

    // ===== kick =====

    private void handleKick(FsRoom r, FsSession s, Map<String, Object> msg) {
        if (r.hostPlayerId == null || !r.hostPlayerId.equals(s.playerId)) {
            s.sendSafe(FsProtocol.error("Only host may kick")); return;
        }
        Object targetObj = msg.get("target");
        if (!(targetObj instanceof String)) { s.sendSafe(FsProtocol.error("Missing target")); return; }
        String target = (String) targetObj;
        if (target.equals(s.playerId)) { s.sendSafe(FsProtocol.error("Cannot kick self")); return; }
        String reason = (String) msg.getOrDefault("reason", "");
        FsSession victim = null;
        for (FsSession ss : r.sessions) {
            if (ss.playerId.equals(target)) { victim = ss; break; }
        }
        if (victim == null) { s.sendSafe(FsProtocol.error("Target not in room")); return; }
        victim.sendSafe(FsProtocol.kicked(s.nickname, reason));
        victim.close();
        s.sendSafe("{\"type\":\"kickOk\"}");
    }

    // ===== 空闲会话清理（不再发协议层心跳）=====
    // 只做一件事：把长时间(>5min)没有任何上行数据的会话判死并关闭，避免僵尸连接占着 ready 名额。
    // 正常玩家一直在发 ping/op，不会触发；真正掉线的客户端重连后会以新会话出现。
    private void idleSessionSweep() {
        long now = System.currentTimeMillis();
        for (FsRoom r : rooms.values()) {
            for (FsSession s : r.sessions) {
                if (s.isClosed()) continue;
                if (now - s.lastSeenMs > 300_000) {
                    System.out.println("[房间] 会话空闲超时，关闭 (playerId=" + s.playerId + ")");
                    s.close();
                }
            }
        }
    }

    private void civOwnershipFlush() {
        long now = System.currentTimeMillis();
        for (FsRoom r : rooms.values()) {
            if (r.ownershipDirtyMs == 0) continue;
            if (now - r.ownershipDirtyMs < 5_000) continue;  // debounce
            r.stateLock.lock();
            try {
                r.saveCivOwnership();
                r.ownershipDirtyMs = 0;
            } finally { r.stateLock.unlock(); }
        }
    }

    /**
     * 把"op 改变权威状态后"标记的房间落盘（debounce）。
     *
     * 每个应用的 op 在 handleOp 里会把 r.saveDirtyMs 置为当前时间；这里每 1s 跑一次，
     * 把"脏且距上次置脏已 ≥300ms"的房间整档写盘。整档由 FsRoom.persist() 在 stateLock 内完成，
     * 不会与 nextTurn / 其他 op 的并发改动竞争序列化。配合 handleOp 里的"距上次落盘≥1s 立即落盘"，
     * 权威存档最多落后约 1~2s，从根本上消除"完成后没保存→重连被打回→重复消耗回合"。
     */
    private void gameSaveFlush() {
        long now = System.currentTimeMillis();
        for (FsRoom r : rooms.values()) {
            if (r.saveDirtyMs == 0) continue;
            if (now - r.saveDirtyMs < 300) continue;  // debounce：合并同回合的多次 op
            r.persist();  // 内部取 stateLock 并清零 saveDirtyMs / 更新 lastPersistMs
        }
    }

    /** Debounced save hook for FsRoom.setCivFor. */
    public void scheduleOwnershipSave(FsRoom r) {
        // For now we set ownershipDirtyMs and the 60s timer flushes it.
        // Could schedule a 5s delayed flush here for snappier persistence.
    }

    public static String computeSecWebSocketAccept(String key) {
        try {
            String magic = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] sha = md.digest(magic.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sha);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String urlDecode(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        try { if (adminServerSocket != null) adminServerSocket.close(); } catch (IOException ignored) {}
        // Persist any pending civ-ownership before exit, and flush the authoritative GameInfo of
        // every room. Turn-mid progress lives in memory only (ops are not written to disk), so
        // this shutdown flush is what keeps a Ctrl+C / window-close from losing up to one turn.
        for (FsRoom r : rooms.values()) {
            boolean locked = false;
            try {
                locked = r.stateLock.tryLock();
                if (locked && r.gameState != null && r.gameState.gameInfo != null) {
                    r.persist();
                    if (logOps) System.out.println("[存档] 关闭前保存房间 " + r.gameId
                            + "（第 " + r.gameState.gameInfo.getTurns() + " 回合）");
                }
                if (locked) r.saveCivOwnership();
            } catch (Exception ignored) {
            } finally {
                if (locked) r.stateLock.unlock();
            }
        }
        acceptor.shutdownNow();
        sessions.shutdownNow();
        scheduled.shutdownNow();
    }
    /** 战斗可见性（对齐核心 MapVisualization.isAttackVisible）：接收者参战，或攻击格/目标格在其视野内。 */
    static boolean battleVisibleTo(com.unciv.logic.GameInfo gi, String playerId, FsOpResult b) {
        try {
            com.unciv.logic.civilization.Civilization viewer = FsTargeting.findCivByPlayerId(gi, playerId);
            if (viewer == null) return false; // 找不到对应文明（含观战）不投喂战斗细节
            if (viewer.getCivName().equals(b.battleAttackerCivName)) return true; // 自己发起的攻击
            com.unciv.logic.map.tile.Tile from = gi.getTileMap().get(b.battleFromX, b.battleFromY);
            com.unciv.logic.map.tile.Tile to = gi.getTileMap().get(b.battleToX, b.battleToY);
            java.util.Set<com.unciv.logic.map.tile.Tile> view = viewer.getViewableTiles();
            return (from != null && view.contains(from)) || (to != null && view.contains(to));
        } catch (Throwable t) {
            return false; // 判定异常宁可漏看也不泄漏
        }
    }

    /** battle 段的独立 JSON（客户端 applySim 读 state["battle"] 的同一套键）。 */
    static String buildBattleJson(FsOpResult b) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("attackerId", b.battleAttackerId);
        m.put("defenderId", b.battleDefenderId);
        if (b.battleDefenderCityId != null) m.put("defenderCityId", b.battleDefenderCityId);
        m.put("dmgToDefender", b.battleDamageToDefender);
        m.put("dmgToAttacker", b.battleDamageToAttacker);
        return FsJson.encode(m);
    }

    /**
     * 把 battle 段追加进 sim 消息的 state 末尾。buildSimMessage 产出的紧凑 JSON 里
     * "state" 是最后一个顶层键、其值以 }} 结束（state 闭合 + msg 闭合），
     * 因此手术 = 去掉末尾两个 } → 追加 ,"battle":{...} → 补回 }}。
     */
    static String appendBattleToState(String simJson, String battleJson) {
        if (simJson == null || !simJson.endsWith("}}")) return simJson; // 结构不符时宁可不发战斗段
        return simJson.substring(0, simJson.length() - 2) + ",\"battle\":" + battleJson + "}}";
    }

}
