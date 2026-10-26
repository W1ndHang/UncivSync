// FsRoom.java - Per-game state: connections + GameInfo + broadcast
//
// Rewritten 2026-09-10: added reconnect detection, civ ownership persistence,
// async (non-simultaneous) turn mode, and a TURN_LOCKED op queue.
package unciv.fs.local;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

public final class FsRoom {
    public final String gameId;
    public final FsServer server;

    /** Active client sessions for this room. */
    public final CopyOnWriteArrayList<FsSession> sessions = new CopyOnWriteArrayList<>();

    /** Mutex protecting game state changes. ALL access to gameState/currentTurn/etc
     *  from outside this class should hold this lock. */
    public final ReentrantLock stateLock = new ReentrantLock();

    /** Optional authoritative game state. Populated when an admin loads a GameInfo JSON
     *  or a client uploads a save. */
    public FsGameState gameState = null;

    /** Room mode. "simultaneous" = both players must hit turnReady before nextTurn
     *  (default, what GC fork calls FrameSync). "async" = each player has their own
     *  GameInfo; on nextTurn one player's snapshot replaces the others. */
    public String mode = "simultaneous";

    /** Turn cycle state. */
    public int currentTurn = 0;
    /**
     * Monotonic authoritative-state revision for this server process.
     *
     * A turn number is not enough to order real-time operations because many
     * operations happen inside one turn.  Every successful mutation and every
     * turn advance gets a distinct revision, and the complete GameInfo shipped
     * with the matching sim carries that same revision.
     */
    public long stateRevision = 0L;
    public boolean paused = false;
    public String pauseNickname = null;
    public long turnDeadlineMs = 0;
    /** 这条动态倒计时是为哪一回合设的（防止它对已经推进过的回合再推一次）。 */
    public int deadlineTurn = -1;
    /**
     * 当前驻留的动态倒计时任务。
     *
     * 必须保存并在重新排程/回合推进时取消 —— 否则上一回合遗留的任务到期后会把
     * "当前回合"又强推一次，表现为回合时间远短于设定值、连跳好几个回合。
     */
    public java.util.concurrent.ScheduledFuture<?> deadlineFuture = null;
    /**
     * 暂停时冻结下来的"本回合剩余时间"（毫秒；-1 表示暂停时本来就没有倒计时）。
     *
     * 没有这个字段的话，暂停只能"把表停掉"，恢复后要么从头计时（偷玩家的时间）、
     * 要么干脆不再计时。存下剩余时间，恢复时接着走。
     */
    public long pausedRemainingMs = -1L;
    public boolean settling = false;
    public final Set<String> readyPlayers = ConcurrentHashMap.newKeySet();
    /**
     * 每个真人玩家"最后一次离线"的时间戳（playerId -> ms）。在线玩家不在表里。
     *
     * 用途：`knownHumanPlayers` 是**只增不减**的名册，一个人拔网线走了，回合推进的
     * "全员就绪"门槛就永远凑不齐 —— 整局卡死。所以掉线超过宽限期的人不再计入门槛。
     */
    public final java.util.Map<String, Long> offlineSince = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 曾经加入过这个房间的真人玩家（playerId）——不随断线/离开移除。
     *
     * 用途：判断"这一局到底有几个真人玩家"。回合推进必须等名册里所有人都 ready，
     * 而不是等"当前恰好在线的人"。用在线 session 数判断会在重连间隙误判成单人房，
     * 从而提前/重复推进回合。
     */
    public final Set<String> knownHumanPlayers = ConcurrentHashMap.newKeySet();

    /** Bookkeeping */
    public long lastSettlingStartMs = 0;
    /** First player to join is the "host" — only host may pause/kick. */
    public String hostPlayerId = null;
    /** Defer-mode buffer for ops marked TURN_LOCKED. They land in this list during
     *  the player's turn and are applied during nextTurn. */
    public final List<TurnLockedOp> turnLockedQueue =
        Collections.synchronizedList(new ArrayList<>());

    /** Persistent civ ownership: playerId -> civId. Loaded from
     *  savesDir/<gameId>.civs.json on first connect, saved on change. */
    public Map<String, String> civOwnership = new HashMap<>();
    /** UUID-based ownership for reconnection. */
    public Map<String, String> uuidToCiv = new HashMap<>();
    public Map<String, String> uuidToPlayer = new HashMap<>();
    public long ownershipDirtyMs = 0;
    /** 权威存档待落盘标记：任何改变权威状态的 op 应用后后置位，由 1s 定时冲刷或即时落盘清零。 */
    public long saveDirtyMs = 0;
    /** 上次整档落盘时间，用于 op 落盘的 debounce。 */
    public long lastPersistMs = 0;
    /** 房间进入"无人在线"冻结态的时间戳；>0 表示当前冻结中（最后一名真人离开的时刻）。0 = 有人在线。 */
    public long emptySinceMs = 0;
    /** 房间创建时刻 —— 管理页的"房间运行时长"基准。 */
    public final long createdAtMs = System.currentTimeMillis();

    /** 当前回合开始时刻（回合推进时刷新，用于计算玩家的结束时长）。 */
    public volatile long currentTurnStartMs = System.currentTimeMillis();
    /** 每个玩家最近 N 次的"结束回合耗时"（毫秒，从回合开始到该玩家点结束）。保留最近 10 条。 */
    public final java.util.Map<String, java.util.List<Long>> playerTurnDurations = new java.util.concurrent.ConcurrentHashMap<>();

    /** 记录一次玩家的结束回合耗时（自动截断到最近 10 条）。 */
    public void recordTurnDuration(String playerId) {
        long elapsed = System.currentTimeMillis() - currentTurnStartMs;
        if (elapsed < 0) return;
        java.util.List<Long> list = playerTurnDurations.computeIfAbsent(playerId, k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()));
        synchronized (list) {
            list.add(elapsed);
            while (list.size() > 10) list.remove(0);
        }
    }

    /** 该玩家最近 N 次的平均结束时长（毫秒）；-1 = 没有数据。 */
    public long averageTurnDuration(String playerId) {
        java.util.List<Long> list = playerTurnDurations.get(playerId);
        if (list == null || list.isEmpty()) return -1;
        synchronized (list) {
            return (long) list.stream().mapToLong(Long::longValue).average().orElse(-1);
        }
    }

    /**
     * 上一次【回合推进】的时刻（2026-10-03 定稿：闲置判定只看这个）。
     * 每次回合推进（doAdvanceTurn 成功）刷新一次；恢复房间时取存档文件最后
     * 修改时间（回合推进必落盘，mtime 就是上一次回合的近似）。连接与 op 不再计入。
     */
    public volatile long lastTurnMs = System.currentTimeMillis();

    /**
     * 房间事件环形日志（管理页"每一步日志"的数据源）。
     * 条目 = 时间戳 + 类别 + **事件码 + 参数**（不含任何自然语言文案）——
     * 管理页按用户选的语言（中/英）把事件码渲染成句子。服务端不掺和语言切换。
     * 容量上界 [EVENT_LOG_CAP]，满则丢最旧。
     * 类别：op（操作）/ turn（回合）/ player（玩家进出）/ system（系统：暂停/倒计时/冻结…）。
     * 事件码清单见管理页 FsAdminPage 的 LOG_T 模板表（两端唯一对表处）。
     */
    private final java.util.ArrayDeque<Map<String, Object>> eventLog = new java.util.ArrayDeque<>();
    static final int EVENT_LOG_CAP = 500;

    /** 记一条结构化房间事件：code = 事件码，args = 渲染参数（所有线程可调，内部同步）。 */
    public void logEvent(String category, String code, Object... args) {
        Map<String, Object> e = new java.util.LinkedHashMap<>();
        e.put("ts", System.currentTimeMillis());
        e.put("cat", category == null ? "system" : category);
        e.put("code", code);
        e.put("args", java.util.Arrays.asList(args));
        synchronized (eventLog) {
            while (eventLog.size() >= EVENT_LOG_CAP) eventLog.pollFirst();
            eventLog.addLast(e);
        }
    }

    /** 取最近 [count] 条事件（可按类别过滤）。返回顺序：新 → 旧。 */
    public java.util.List<Map<String, Object>> recentEvents(String category, int count) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        synchronized (eventLog) {
            java.util.Iterator<Map<String, Object>> it = eventLog.descendingIterator();
            while (it.hasNext() && out.size() < count) {
                Map<String, Object> e = it.next();
                if (category == null || category.isEmpty() || category.equals(e.get("cat"))) out.add(e);
            }
        }
        return out;
    }

    /** 房间运行时长（毫秒）。 */
    public long uptimeMs() { return System.currentTimeMillis() - createdAtMs; }


    public FsRoom(String gameId, FsServer server) {
        this.gameId = gameId;
        this.server = server;
        loadCivOwnership();
        logEvent("system", "room_create", gameId);
    }

    /** Add or reconnect a session. If a session with the same playerId AND
     *  matching playerUUID is already present, treat this as a reconnect:
     *  close the old session and add the new one. If UUIDs don't match, treat
     *  as a new player with the same nickname (collision). */
    public void addSession(FsSession s) {
        FsSession existing = null;
        for (FsSession x : sessions) {
            if (x.playerId.equals(s.playerId)) {
                // Only consider it a reconnect if UUIDs match (or either side has none)
                if (x.playerUUID.isEmpty() || s.playerUUID.isEmpty() ||
                    x.playerUUID.equals(s.playerUUID)) {
                    existing = x;
                    break;
                }
            }
        }
        boolean isReconnect = (existing != null);
        if (isReconnect) {
            // Drop the old socket; the client will see "replaced" if it tries to send.
            System.out.println("[房间] 玩家[" + s.nickname + "] 重连 (playerId=" + s.playerId + ")");
            logEvent("player", "reconnect", s.nickname, s.playerId);
            existing.markReplaced();  // tell old session to send "replaced" notice and stop
        } else {
            System.out.println("[房间] 玩家[" + s.nickname + "] 加入 (playerId=" + s.playerId
                    + (s.spectator ? ", 观战" : "") + ")");
            logEvent("player", "join", s.nickname, s.playerId, s.spectator);
        }
        // First player becomes host
        if (hostPlayerId == null) hostPlayerId = s.playerId;

        // 登记真人玩家名册（不随断线移除）。
        // 回合推进的"全员 ready"判断必须基于这份名册，而不是当前在线 session 数：
        // 否则任何一次断线重连都会让 totalPlayers 从 2 掉到 1，触发"单机立即推进"分支，
        // 导致回合被多推一次（表现为"连续推进同一个回合"）。
        if (!s.spectator && s.playerId != null && !s.playerId.isEmpty()) {
            knownHumanPlayers.add(s.playerId);
            // 注意：连接不再刷新闲置基准（用户要求按"最后操作/落盘"判定，纯挂机不算活动）。
        }

        sessions.add(s);
        offlineSince.remove(s.playerId);   // 回来了：重新计入"必须就绪"
        s.sendSafe(FsProtocol.welcome(s, this, isReconnect));
        broadcastOnline();
        ensureGameStateLoaded();
        sendInitialState(s);
        // Broadcast turnStatus to everyone so the new player (and existing players) learn the
        // current ready count — the client shows "x/n players have ended their turn" from it.
        broadcastTurnStatus();
        // Re-apply the player's existing ready flag (if they disconnected mid-ready)
        if (readyPlayers.contains(s.playerId)) {
            s.sendSafe(FsProtocol.turnStatus(this, "Reconnected — still ready"));
        }
    }

    /** Lazy-load GameInfo from Saves/<gameId>.json on first client connect. */
    public void ensureGameStateLoaded() {
        if (gameState != null && gameState.gameInfo != null) return;
        // 必须用 stateLock.lock() 而不是 synchronized(stateLock)：后者锁的是
        // ReentrantLock 对象的监视器，与别处的 lock()/unlock() **互不相斥**，
        // 并发首次加载（重连风暴）会互相覆盖 gameState —— 双重检查形同虚设。
        stateLock.lock();
        try {
            if (gameState != null && gameState.gameInfo != null) return;
            if (FsBootstrap.savesDir == null) return;
            File saveFile = new File(FsBootstrap.savesDir, gameId + ".json");
            if (!saveFile.exists()) {
                System.out.println("[room] no save file at " + saveFile + " - clients get empty state");
                gameState = new FsGameState();
                return;
            }
            gameState = new FsGameState();
            boolean ok = gameState.loadFromFile(saveFile);
            if (ok) {
                currentTurn = gameState.gameInfo.getTurns();
                System.out.println("[room] game loaded into room " + gameId + " (turn " + currentTurn + ")");
            } else {
                System.out.println("[room] game load failed for " + gameId);
            }
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Persist current GameInfo back to disk.
     *
     * UncivSync：原本只有回合推进(doAdvanceTurn)后才会落盘。实时/单人模式下，一轮里玩家可能
     * 完成很多"建好即完成"的动作（金币买建筑、采纳政策、完成科研…），这些只改内存 + 广播 sim，
     * 不碰磁盘。若期间发生重连 / WorldScreen 重建 / 服务端重启，客户端读到的是上一次整档的陈旧
     * 存档 → 已完成的项消失、被要求重做（"重复消耗回合"）。所以现在 handleOp 也会触发落盘。
     *
     * 落盘期间持有 stateLock，避免与正在跑的 nextTurn / 其他 op 的并发改动竞争序列化
     * （FsOpPipeline 与 doAdvanceTurn 都在 stateLock 内改 gameInfo）。ReentrantLock，
     * doAdvanceTurn/stop 已持锁再调本方法时不会死锁。
     */
    public void persist() {
        if (gameState == null || gameState.gameInfo == null || FsBootstrap.savesDir == null) return;
        stateLock.lock();
        try {
            gameState.saveToFile(new File(FsBootstrap.savesDir, gameId + ".json"));
            saveDirtyMs = 0;
            lastPersistMs = System.currentTimeMillis();
        } finally {
            stateLock.unlock();
        }
    }

    public void removeSession(FsSession s) {
        sessions.remove(s);
        readyPlayers.remove(s.playerId);
        offlineSince.put(s.playerId, System.currentTimeMillis());
        System.out.println("[房间] 玩家[" + s.nickname + "] 离开 (playerId=" + s.playerId + ")");
        logEvent("player", "leave", s.nickname);
        // In async mode, a player leaving doesn't reset ready state — their save
        // is still on disk waiting for them.
        broadcastOnline();
        broadcastTurnStatus();
    }

    /** 是否还有"真人"（非观战）玩家持有活着的 WebSocket 会话。空房冻结闸门的唯一判据。 */
    public boolean hasOnlineHuman() {
        for (FsSession s : sessions) if (!s.spectator) return true;
        return false;
    }

    public void broadcast(String text) {
        for (FsSession s : sessions) s.sendSafe(text);
    }

    public void broadcastExcept(String text, FsSession except) {
        for (FsSession s : sessions) if (s != except) s.sendSafe(text);
    }

    public void broadcastOnline() {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "onlinePlayers");
        List<Object> players = new ArrayList<>();
        for (FsSession s : sessions) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("playerId", s.playerId);
            p.put("nickname", s.nickname);
            p.put("uuid", s.playerUUID);
            p.put("spectator", s.spectator);
            p.put("host", s.playerId.equals(hostPlayerId));
            players.add(p);
        }
        msg.put("players", players);
        broadcast(FsJson.encode(msg));
    }

    public void sendInitialState(FsSession s) {
        long t0 = System.currentTimeMillis();
        try {
            if (gameState == null || gameState.gameInfo == null) {
                s.sendSafe("{\"type\":\"sim\",\"ok\":\"true\",\"turn\":0,\"state\":{\"turn\":0,\"paused\":false,\"full\":true,\"units\":[],\"cities\":[],\"civs\":[]}}");
                return;
            }
            String encoded;
            stateLock.lock();
            try {
                encoded = gameState.buildFullAuthoritativeSimMessage(stateRevision);
            } finally {
                stateLock.unlock();
            }
            if (encoded == null) {
                s.sendSafe("{\"type\":\"sim\",\"ok\":\"true\",\"turn\":0,\"state\":{\"turn\":0,\"paused\":false,\"full\":true,\"units\":[],\"cities\":[],\"civs\":[]}}");
                return;
            }
            System.out.println("[room] sending initial sim to " + s.nickname + " (" + encoded.length() + " chars, took " + (System.currentTimeMillis() - t0) + "ms)");
            server.sendStateMessage(s, encoded);
            // 顺带把当前回合的剩余时间发给它 —— 新加入/重连的玩家也能立刻看到倒计时
            sendTurnDeadline(s);
        } catch (Throwable t) {
            System.err.println("[room] sendInitialState outer FAILED: " + t.getMessage());
            t.printStackTrace();
        }
    }

    public void broadcastTurnStatus() {
        broadcast(FsProtocol.turnStatusJson(this));
    }

    /**
     * 广播本回合的剩余时间（同步回合倒计时，客户端右上角显示 mm:ss）。
     * remainingMs &lt; 0 表示当前没有倒计时（非同步回合 / 未开动态时长），客户端据此隐藏计时器。
     */
    public void broadcastTurnDeadline() {
        try {
            long remaining = turnDeadlineMs <= 0
                    ? -1L
                    : Math.max(0L, turnDeadlineMs - System.currentTimeMillis());
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "turnDeadline");
            msg.put("turn", currentTurn);
            msg.put("remainingMs", remaining);
            broadcast(FsJson.encode(msg));
        } catch (Throwable t) {
            System.err.println("[turn] broadcastTurnDeadline failed: " + t.getMessage());
        }
    }

    /** 单发回合剩余时间（玩家刚连上/重连时，让它立刻看到倒计时）。 */
    public void sendTurnDeadline(FsSession s) {
        try {
            long remaining = turnDeadlineMs <= 0
                    ? -1L
                    : Math.max(0L, turnDeadlineMs - System.currentTimeMillis());
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "turnDeadline");
            msg.put("turn", currentTurn);
            msg.put("remainingMs", remaining);
            s.sendSafe(FsJson.encode(msg));
        } catch (Throwable t) { }
    }

    public void broadcastPauseNotice(String nickname) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "pauseNotice");
        msg.put("nickname", nickname);
        broadcast(FsJson.encode(msg));
    }

    public void broadcastResumeNotice(String nickname) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "resumeNotice");
        msg.put("nickname", nickname);
        broadcast(FsJson.encode(msg));
    }

    // ===== civ ownership =====

    public String getCivFor(String playerId, String playerUUID) {
        // UUID first (most reliable for reconnection), then playerId
        if (playerUUID != null && !playerUUID.isEmpty()) {
            String c = uuidToCiv.get(playerUUID);
            if (c != null) return c;
            String p = uuidToPlayer.get(playerUUID);
            if (p != null && p.equals(playerId)) {
                c = civOwnership.get(playerId);
                if (c != null) return c;
            }
        }
        return civOwnership.get(playerId);
    }

    public void setCivFor(String playerId, String playerUUID, String civId) {
        if (playerId == null || civId == null) return;
        civOwnership.put(playerId, civId);
        if (playerUUID != null && !playerUUID.isEmpty()) {
            uuidToCiv.put(playerUUID, civId);
            uuidToPlayer.put(playerUUID, playerId);
        }
        ownershipDirtyMs = System.currentTimeMillis();
        // Debounced write
        server.scheduleOwnershipSave(this);
    }

    /** 从存档回填缺失的 civ ownership（真人文明的 playerId -> civName）。
     *  只补缺：不覆盖已注册项，也不给已被认领的文明重复绑人——让老存档的玩家
     *  重连时立即绑回原文明，而不是被当成新人重新分配。 */
    public void backfillOwnershipFromSave() {
        if (gameState == null || gameState.gameInfo == null) return;
        boolean changed = false;
        for (Object c : gameState.gameInfo.getCivilizations()) {
            com.unciv.logic.civilization.Civilization civ = (com.unciv.logic.civilization.Civilization) c;
            if (!civ.isHuman() || civ.isSpectator() || civ.isBarbarian()) continue;
            String pid = civ.getPlayerId();
            if (pid == null || pid.isEmpty()) continue;
            String civName = civ.getCivName();
            if (!civOwnership.containsKey(pid) && !civOwnership.containsValue(civName)) {
                civOwnership.put(pid, civName);
                changed = true;
            }
        }
        if (changed) {
            System.out.println("[room] 从存档回填文明归属: " + gameId);
            saveCivOwnership();
        }
    }

    private void loadCivOwnership() {
        if (FsBootstrap.savesDir == null) return;
        File f = new File(FsBootstrap.savesDir, gameId + ".civs.json");
        if (!f.exists()) return;
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Object parsed = FsJson.parse(text);
            if (parsed instanceof Map) {
                Map<?,?> m = (Map<?,?>) parsed;
                Object civsObj = m.get("civs");
                if (civsObj instanceof Map) {
                    for (Map.Entry<?,?> e : ((Map<?,?>) civsObj).entrySet()) {
                        civOwnership.put(e.getKey().toString(), e.getValue().toString());
                    }
                }
                Object uuidsObj = m.get("uuids");
                if (uuidsObj instanceof Map) {
                    for (Map.Entry<?,?> e : ((Map<?,?>) uuidsObj).entrySet()) {
                        uuidToCiv.put(e.getKey().toString(), e.getValue().toString());
                    }
                }
                Object uuidPlayersObj = m.get("uuidPlayers");
                if (uuidPlayersObj instanceof Map) {
                    for (Map.Entry<?,?> e : ((Map<?,?>) uuidPlayersObj).entrySet()) {
                        uuidToPlayer.put(e.getKey().toString(), e.getValue().toString());
                    }
                }
                System.out.println("[room] loaded civ-ownership for " + gameId +
                    " (" + civOwnership.size() + " players, " + uuidToCiv.size() + " uuids)");
            }
        } catch (Throwable t) {
            System.err.println("[room] civ-ownership load failed: " + t.getMessage());
        }
    }

    public synchronized void saveCivOwnership() {
        if (FsBootstrap.savesDir == null) return;
        File f = new File(FsBootstrap.savesDir, gameId + ".civs.json");
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("civs", civOwnership);
            out.put("uuids", uuidToCiv);
            out.put("uuidPlayers", uuidToPlayer);
            Files.write(f.toPath(), FsJson.encode(out).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[room] civ-ownership save failed: " + e.getMessage());
        }
    }

    // ===== turn-locked op queue =====

    public void enqueueTurnLocked(TurnLockedOp op) {
        turnLockedQueue.add(op);
    }

    public List<TurnLockedOp> drainTurnLocked() {
        synchronized (turnLockedQueue) {
            List<TurnLockedOp> out = new ArrayList<>(turnLockedQueue);
            turnLockedQueue.clear();
            return out;
        }
    }

    public static final class TurnLockedOp {
        public final String playerId;
        public final String op;
        public final Map<String, Object> data;
        public TurnLockedOp(String playerId, String op, Map<String, Object> data) {
            this.playerId = playerId; this.op = op; this.data = data;
        }
    }
}
