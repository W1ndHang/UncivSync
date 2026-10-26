// FsLobby.java - Self-hosted lobby REST API (replaces the original project's 110.40.151.9:8125 service).
//
// Mirrors the client's com.unciv.logic.lobby.LobbyApi contract:
//   GET  /api/rooms                      -> [LobbyRoom]
//   POST /api/rooms                      -> LobbyRoom          (CreateRoomRequest)
//   GET  /api/rooms/{id}                 -> LobbyRoom
//   GET  /api/rooms/{id}/wait?since=&playerId= -> LobbyRoom  (long-poll)
//   POST /api/rooms/{id}/join|leave|exit|exitconfirm|ready|civ|team|kick|settings|chat|start|restart|spectate
// JSON field names match the Kotlin data classes (camelCase, ignoreUnknownKeys on client).
package unciv.fs.local;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class FsLobby {

    public static final class Member {
        public String nickname = "", playerId = "", civ = "";
        public boolean ready = false, owner = false;
        public double joinedAt = System.currentTimeMillis() / 1000.0;
        public List<String> missingMods = new ArrayList<>();
        public int team = 0;
    }

    public static final class Chat {
        public int seq; public String playerId = "", nickname = "", text = "", to = "";
        public long ts = System.currentTimeMillis();
    }

    public static final class Room {
        public String id = "";
        public String name = "";
        public String status = "waiting";     // waiting | starting | playing
        public String gameId = "";
        public int version = 1;
        public boolean hasPassword = false;
        public String ownerPlayerId = "";
        public String ownerName = "";
        public String password = "";
        public String baseRuleset = "Civ V - Vanilla";
        public List<String> mods = new CopyOnWriteArrayList<>();
        public String settingsJson = "{}";     // opaque, echoed back to clients
        public List<Member> members = new CopyOnWriteArrayList<>();
        public List<Chat> chat = new CopyOnWriteArrayList<>();
        public long lastTouch = System.currentTimeMillis();
        public final Object lock = new Object();

        public Member find(String playerId) {
            for (Member m : members) if (m.playerId.equals(playerId)) return m;
            return null;
        }
        public int playerCount() {
            int n = 0; for (Member m : members) n++; return n;
        }
        public void bump() {
            version++;
            lastTouch = System.currentTimeMillis();
            synchronized (lock) { lock.notifyAll(); }   // wake long-poll waiters
        }
    }

    public final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
    private final FsServer server;

    public FsLobby(FsServer server) { this.server = server; }

    public Room room(String id) { return rooms.get(id); }

    // ------------------------------------------------------------------ JSON

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    private static String memberJson(Member m) {
        return "{\"nickname\":\"" + esc(m.nickname) + "\",\"playerId\":\"" + esc(m.playerId)
            + "\",\"civ\":\"" + esc(m.civ) + "\",\"ready\":" + m.ready + ",\"isOwner\":" + m.owner
            + ",\"joinedAt\":" + m.joinedAt + ",\"missingMods\":" + strArray(m.missingMods)
            + ",\"team\":" + m.team + "}";
    }

    private static String strArray(List<String> xs) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < xs.size(); i++) { if (i > 0) b.append(','); b.append('"').append(esc(xs.get(i))).append('"'); }
        return b.append(']').toString();
    }

    private static String chatJson(Chat c) {
        return "{\"seq\":" + c.seq + ",\"playerId\":\"" + esc(c.playerId) + "\",\"nickname\":\"" + esc(c.nickname)
            + "\",\"text\":\"" + esc(c.text) + "\",\"to\":\"" + esc(c.to) + "\",\"ts\":" + c.ts + "}";
    }

    public static String roomJson(Room r) {
        StringBuilder b = new StringBuilder();
        b.append("{\"id\":\"").append(esc(r.id)).append('"');
        b.append(",\"name\":\"").append(esc(r.name)).append('"');
        b.append(",\"status\":\"").append(esc(r.status)).append('"');
        b.append(",\"gameId\":\"").append(esc(r.gameId)).append('"');
        b.append(",\"version\":").append(r.version);
        b.append(",\"hasPassword\":").append(r.hasPassword);
        b.append(",\"playerCount\":").append(r.playerCount());
        b.append(",\"owner\":\"").append(esc(r.ownerName)).append('"');
        b.append(",\"ownerId\":\"").append(esc(r.ownerPlayerId)).append('"');
        b.append(",\"baseRuleset\":\"").append(esc(r.baseRuleset)).append('"');
        b.append(",\"mods\":").append(strArray(r.mods));
        b.append(",\"settings\":").append(r.settingsJson == null || r.settingsJson.isEmpty() ? "{}" : r.settingsJson);
        b.append(",\"members\":[");
        for (int i = 0; i < r.members.size(); i++) { if (i > 0) b.append(','); b.append(memberJson(r.members.get(i))); }
        b.append("],\"memberIds\":[");
        for (int i = 0; i < r.members.size(); i++) { if (i > 0) b.append(','); b.append('"').append(esc(r.members.get(i).playerId)).append('"'); }
        b.append("],\"chat\":[");
        for (int i = 0; i < r.chat.size(); i++) { if (i > 0) b.append(','); b.append(chatJson(r.chat.get(i))); }
        b.append("]}");
        return b.toString();
    }

    public String roomsJson() {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Room r : rooms.values()) {
            if ("playing".equals(r.status) && r.members.isEmpty()) continue;
            if (!first) b.append(',');
            first = false;
            b.append(roomJson(r));
        }
        return b.append(']').toString();
    }

    public static String okJson(String msg, String gameId, Room r) {
        StringBuilder b = new StringBuilder("{\"ok\":true");
        b.append(",\"msg\":\"").append(esc(msg)).append('"');
        b.append(",\"gameId\":\"").append(esc(gameId == null ? "" : gameId)).append('"');
        b.append(",\"room\":").append(r == null ? "null" : roomJson(r));
        return b.append('}').toString();
    }

    public static String errJson(String msg) {
        return "{\"ok\":false,\"msg\":\"" + esc(msg) + "\",\"gameId\":\"\",\"room\":null}";
    }

    // -------------------------------------------------------------- request helpers

    public static Map<String, Object> body(String raw) {
        if (raw == null || raw.isEmpty()) return new HashMap<>();
        try { return FsJson.asObject(FsJson.parse(raw)); } catch (Exception e) { return new HashMap<>(); }
    }

    private static String s(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : String.valueOf(v);
    }
    private static boolean b(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return def;
    }
    private static int i(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
    }

    // -------------------------------------------------------------- operations

    public String createRoom(Map<String, Object> req) {
        Room r = new Room();
        r.id = UUID.randomUUID().toString();
        r.name = s(req, "name", "Room");
        r.password = s(req, "password", "");
        r.hasPassword = !r.password.isEmpty();
        r.ownerPlayerId = s(req, "playerId", "");
        r.ownerName = s(req, "nickname", "Player");
        Member m = new Member();
        m.nickname = r.ownerName;
        m.playerId = r.ownerPlayerId;
        m.civ = s(req, "civ", "");
        m.owner = true;
        r.members.add(m);
        rooms.put(r.id, r);
        return roomJson(r);
    }

    public String join(String roomId, Map<String, Object> req) {
        Room r = rooms.get(roomId);
        if (r == null) return errJson("room not found");
        synchronized (r.lock) {
            if (r.hasPassword && !r.password.equals(s(req, "password", ""))) return errJson("wrong password");
            String pid = s(req, "playerId", "");
            if (r.find(pid) == null) {
                Member m = new Member();
                m.nickname = s(req, "nickname", "Player");
                m.playerId = pid;
                m.civ = s(req, "civ", "");
                r.members.add(m);
            }
            r.bump();
        }
        return okJson("joined", "", r);
    }

    public String leave(String roomId, Map<String, Object> req) {
        Room r = rooms.get(roomId);
        if (r == null) return errJson("room not found");
        synchronized (r.lock) {
            Member m = r.find(s(req, "playerId", ""));
            if (m != null) r.members.remove(m);
            if (r.members.isEmpty()) { rooms.remove(roomId); return okJson("left", "", null); }
            if (r.ownerPlayerId.equals(s(req, "playerId", ""))) {
                Member n = r.members.get(0);
                n.owner = true; r.ownerPlayerId = n.playerId; r.ownerName = n.nickname;
            }
            r.bump();
        }
        return okJson("left", "", r);
    }

    public String ready(String roomId, Map<String, Object> req) {
        Room r = rooms.get(roomId);
        if (r == null) return errJson("room not found");
        synchronized (r.lock) {
            Member m = r.find(s(req, "playerId", ""));
            if (m == null) return errJson("not in room");
            m.ready = b(req, "ready", true);
            r.bump();
        }
        return okJson("ready", "", r);
    }

    public String setCiv(String roomId, Map<String, Object> req) {
        Room r = rooms.get(roomId);
        if (r == null) return errJson("room not found");
        synchronized (r.lock) {
            Member m = r.find(s(req, "playerId", ""));
            if (m == null) return errJson("not in room");
            m.civ = s(req, "civ", "");
            r.bump();
        }
        return okJson("civ", "", r);
    }

    public String setTeam(String roomId, Map<String, Object> req) {
        Room r = rooms.get(roomId);
        if (r == null) return errJson("room not found");
        synchronized (r.lock) {
            Member m = r.find(s(req, "playerId", ""));
            if (m == null) return errJson("not in room");
            m.team = i(req, "team", 0);
            r.bump();
        }
        return okJson("team", "", r);
    }

    public String kick(String roomId, Map<String, Object> req) {
        Room r = rooms.get(roomId);
        if (r == null) return errJson("room not found");
        String by = s(req, "playerId", "");
        if (!by.equals(r.ownerPlayerId)) return errJson("only owner can kick");
        String target = s(req, "target", s(req, "targetId", ""));
        synchronized (r.lock) {
            Member m = r.find(target);
            if (m != null) r.members.remove(m);
            r.bump();
        }
        return okJson("kicked", "", r);
    }

    public String updateSettings(String roomId, Map<String, Object> req) {
        Room r = rooms.get(roomId);
        if (r == null) return errJson("room not found");
        synchronized (r.lock) {
            Object st = req.get("settings");
            if (st != null) {
                try { r.settingsJson = FsJson.encode(st); } catch (Exception ignored) {}
                if (st instanceof Map) {
                    Object br = ((Map<?, ?>) st).get("baseRuleset");
                    if (br != null) r.baseRuleset = String.valueOf(br);
                }
            }
            r.bump();
        }
        return okJson("settings", "", r);
    }

    public String chat(String roomId, Map<String, Object> req) {
        Room r = rooms.get(roomId);
        if (r == null) return errJson("room not found");
        synchronized (r.lock) {
            Chat c = new Chat();
            c.seq = r.chat.size() + 1;
            c.playerId = s(req, "playerId", "");
            c.nickname = s(req, "nickname", "Player");
            c.text = s(req, "text", "");
            c.to = s(req, "to", "");
            r.chat.add(c);
            if (r.chat.size() > 200) r.chat.remove(0);
            r.bump();
        }
        return okJson("chat", "", r);
    }

    public String spectate(String roomId, Map<String, Object> req) {
        Room r = rooms.get(roomId);
        if (r == null) return errJson("room not found");
        return okJson("spectate", r.gameId, r);
    }

    /** owner starts the game: generate a GameInfo server-side, store it, flip room to playing. */
    public String start(String roomId, Map<String, Object> req) {
        Room r = rooms.get(roomId);
        if (r == null) return errJson("room not found");
        if (!"waiting".equals(r.status)) return okJson("already started", r.gameId, r);
        synchronized (r.lock) {
            r.status = "starting"; r.bump();
        }
        new Thread(() -> {
            try {
                String gid = server.generateLobbyGame(r);
                synchronized (r.lock) {
                    if (gid != null && !gid.isEmpty()) {
                        r.gameId = gid; r.status = "playing";
                    } else {
                        r.status = "waiting";
                    }
                    r.bump();
                }
            } catch (Throwable t) {
                System.err.println("[lobby] game generation failed: " + t);
                synchronized (r.lock) { r.status = "waiting"; r.bump(); }
            }
        }, "lobby-gen-" + roomId).start();
        synchronized (r.lock) { return okJson("starting", r.gameId, r); }
    }

    public String restart(String roomId, Map<String, Object> req) {
        Room r = rooms.get(roomId);
        if (r == null) return errJson("room not found");
        synchronized (r.lock) {
            r.status = "waiting"; r.gameId = "";
            for (Member m : r.members) m.ready = false;
            r.bump();
        }
        return okJson("restarted", "", r);
    }

    /** long-poll: wait until version > since, or up to timeoutMs. */
    public String waitRoom(String roomId, int since, long timeoutMs) {
        Room r = rooms.get(roomId);
        if (r == null) return null;
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (r.lock) {
            while (r.version <= since && System.currentTimeMillis() < deadline) {
                try { r.lock.wait(500); } catch (InterruptedException e) { break; }
            }
        }
        return roomJson(r);
    }
}
