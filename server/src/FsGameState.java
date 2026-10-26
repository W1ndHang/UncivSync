// FsGameState.java - Authoritative game state for a room
// Embeds Unciv GameInfo for real simulation: load/save/nextTurn.
// Op application is recorded but deferred - FrameSync sends ~40 distinct ops,
// each touching deeply nested game logic. Phase 1: just lifecycle; full op
// dispatch is Phase 2.
package unciv.fs.local;

import com.unciv.logic.GameInfo;
import com.unciv.logic.CompatibilityVersion;
import com.unciv.logic.civilization.Civilization;
import com.unciv.logic.city.City;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

public final class FsGameState {
    /** Authoritative game state. May be null until loaded. */
    public GameInfo gameInfo = null;

    /** Last JSON form (for full snapshot fallback). */
    public String lastSnapshotJson = null;

    /** Op log for replay/debug. */
    public final List<MapOp> opLog = Collections.synchronizedList(new ArrayList<>());

    public FsGameState() {}

    /** Try to load GameInfo from a save file. Returns true on success. */
    public boolean loadFromFile(File saveFile) {
        try {
            String text = new String(java.nio.file.Files.readAllBytes(saveFile.toPath()),
                StandardCharsets.UTF_8);
            String jsonText = decodeSaveText(text);
            com.badlogic.gdx.utils.Json json = com.unciv.json.UncivJsonKt.json();
            GameInfo gi = json.fromJson(GameInfo.class, new StringReader(jsonText));
            gi.setRuleset(FsBootstrap.vanillaRuleset);
            gi.setTransients();
            this.gameInfo = gi;
            this.lastSnapshotJson = jsonText;
            System.out.println("[gamestate] loaded " + saveFile.getName() + " turns=" + gi.getTurns() + " civs=" + gi.getCivilizations().size());
            return true;
        } catch (Throwable t) {
            System.err.println("[gamestate] loadFromFile failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Unciv has two on-disk save encodings and we must accept both:
     *
     *   - plain JSON            -> what the game writes for local saves ("Autosave" etc.)
     *   - Base64(GZIP(JSON))    -> what the online client uploads via PUT /files/&lt;id&gt;
     *                              (see com.unciv.ui.screens.savescreens.Gzip.zip / UncivFiles.gameInfoToString)
     *
     * The uploaded form starts with "H4sI" (gzip magic 1f 8b 08 base64-encoded). Feeding that
     * straight to the JSON parser is what produced
     * "Unable to convert value to required type: H4sIAAA... (com.unciv.logic.GameInfo)".
     */
    private static String decodeSaveText(String text) {
        if (text == null) return "";
        String compact = text.trim().replace("\r", "").replace("\n", "");
        if (compact.startsWith("{")) return compact;          // plain JSON fast path
        try {
            byte[] gz = Base64.getMimeDecoder().decode(compact);
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, gz.length * 4));
                byte[] buf = new byte[65536];
                int n;
                long total = 0;
                // 解压上限：损坏数据/意外膨胀（gzip 炸弹）不该把堆打爆 —— 正常存档
                // 远小于此；超限当作解码失败走下面的兜底（JSON 解析器报真正的错）。
                final long MAX_DECOMPRESSED = 256L * 1024 * 1024;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_DECOMPRESSED)
                        throw new IllegalArgumentException("decompressed save exceeds " + MAX_DECOMPRESSED + " bytes");
                    out.write(buf, 0, n);
                }
                String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
                if (json.startsWith("{")) return json;
            }
        } catch (Throwable ignored) {
            // not compressed - fall through and let the parser report the real problem
        }
        return compact;
    }

    /** Serialise GameInfo to JSON for snapshot. */
    public String toJson() {
        if (gameInfo == null) return "{}";
        com.badlogic.gdx.utils.Json json = com.unciv.json.UncivJsonKt.json();
        return json.toJson(gameInfo);
    }

    /** Build snapshot for client. The client expects the GameInfo JSON shape directly. */
    public Object toSnapshotJson() {
        if (gameInfo == null) {
            Map<String, Object> empty = new LinkedHashMap();
            empty.put("turn", 0);
            empty.put("paused", false);
            empty.put("empty", true);
            return empty;
        }
        return FsJson.parse(toJson());
    }

    /** Record an op. Application deferred to Phase 2 (op-by-op Unciv method mapping). */
    public void applyOp(String playerId, String opName, Map<String, Object> data) {
        MapOp o = new MapOp();
        o.playerId = playerId;
        o.op = opName;
        o.data = data;
        o.ts = System.currentTimeMillis();
        opLog.add(o);
        System.out.println("[gamestate] op recorded: " + opName + " from " + playerId + " (total " + opLog.size() + ")");
    }

    /**
     * Build a `sim`-message JSON containing a partial state delta covering only
     * the units / cities / civs that the FsOpResult says changed.
     *
     * Client side (FrameSync.handleSimMessage) calls applyState which iterates
     * over the units / cities / civs arrays in the state and patches the local
     * GameInfo in place. Units in `removedUnits` are deleted locally; cities
     * in `removedCities` likewise. Anything not in the message is left
     * untouched on the client.
     *
     * The format of each unit / city entry is the same shape that
     * FrameSync.StateSnapshot expects (positional arrays). We build them
     * by extracting fields from the live Unciv objects.
     */
    public String buildSimMessage(FsOpResult diff) {
        if (gameInfo == null || diff == null) return null;
        try {
            com.badlogic.gdx.utils.Json json = com.unciv.json.UncivJsonKt.json();
            java.util.LinkedHashMap<String, Object> msg = new java.util.LinkedHashMap<>();
            msg.put("type", "sim");
            msg.put("ok", "true");
            msg.put("turn", gameInfo.getTurns());
            msg.put("playerId", diff.playerId);
            msg.put("op", diff.op);
            // The actual delta lives in "state". Client's handleSimMessage reads
            // "state" then iterates units/cities/civs arrays.
            java.util.LinkedHashMap<String, Object> state = new java.util.LinkedHashMap<>();
            state.put("turn", gameInfo.getTurns());
            state.put("currentPlayer", gameInfo.getCurrentPlayer());
            state.put("paused", false);
            state.put("full", diff.op == null || !diff.op.startsWith("unit."));

            // Build per-affected unit entries. De-dup by id — a MapUnit
            // can only belong to one civ, but let's be defensive in case the
            // simulation ever leaves a stale reference around.
            java.util.Map<Integer, Object> unitSeen = new java.util.HashMap<>();
            java.util.List<Object> unitEntries = new java.util.ArrayList<>();
            for (Civilization civ : gameInfo.getCivilizations()) {
                for (java.util.Iterator<com.unciv.logic.map.mapunit.MapUnit> it = civ.getUnits().getCivUnits().iterator(); it.hasNext(); ) {
                    com.unciv.logic.map.mapunit.MapUnit u = it.next();
                    if (!diff.affectedUnitIds.contains(u.getId())) continue;
                    if (unitSeen.containsKey(u.getId())) continue;
                    unitSeen.put(u.getId(), Boolean.TRUE);
                    unitEntries.add(unitToSnapshot(u, diff.unitPrevPos.get(u.getId())));
                }
            }
            state.put("units", unitEntries);

            java.util.List<Object> removedUnits = new java.util.ArrayList<>();
            for (Integer id : diff.removedUnitIds) removedUnits.add(id);
            state.put("removedUnits", removedUnits);

            java.util.Map<String, Object> citySeen = new java.util.HashMap<>();
            java.util.List<Object> cityEntries = new java.util.ArrayList<>();
            for (Civilization civ : gameInfo.getCivilizations()) {
                for (Object cityObj : civ.getCities()) {
                    com.unciv.logic.city.City c = (com.unciv.logic.city.City) cityObj;
                    if (!diff.affectedCityIds.contains(c.getId())) continue;
                    if (citySeen.containsKey(c.getId())) continue;
                    citySeen.put(c.getId(), Boolean.TRUE);
                    cityEntries.add(cityToSnapshot(c));
                }
            }
            state.put("cities", cityEntries);

            java.util.List<Object> removedCities = new java.util.ArrayList<>();
            for (String id : diff.removedCityIds) removedCities.add(id);
            state.put("removedCities", removedCities);

            // Civ-level summaries (gold, science, culture, etc.) so the
            // player's UI updates without waiting for nextTurn.
            java.util.List<Object> civEntries = new java.util.ArrayList<>();
            for (Civilization civ : gameInfo.getCivilizations()) {
                if (civ.isBarbarian() || civ.isSpectator()) continue;
                if (!diff.affectedCivIds.contains(civ.getCivID())) continue;
                civEntries.add(civToSnapshot(civ));
            }
            state.put("civs", civEntries);

            // ---- UncivSync: 地块投影（改良 / 道路 / 掠夺 / 在建进度）----
            // 以前这几类完全没有通道，只能靠"每回合整档下发"兜底；而整档会让客户端
            // loadGame() 重建整个 WorldScreen（卡顿主因）。纳入投影后，常规回合不用再整档。
            try {
                state.put("tiles", collectChangedTiles());
            } catch (Throwable t) {
                System.err.println("[gamestate] collectChangedTiles failed: " + t.getMessage());
                state.put("tiles", new java.util.ArrayList<>());
            }

            // ---- UncivSync: 蛮族营地 + 地形突变（对齐 GC 的 encampments / terrainChanges）----
            try {
                state.put("encampments", collectEncampments());
            } catch (Throwable t) {
                state.put("encampments", new java.util.ArrayList<>());
            }
            try {
                state.put("terrainChanges", collectTerrainChanges());
            } catch (Throwable t) {
                state.put("terrainChanges", new java.util.ArrayList<>());
            }

            // UncivSync: 宗教全量（第二十三批）——官方序列化器整对象下发，与存档同格式，
            // 两端结构天然一致（不再手写字段清单）。数量少（≤文明的宗教数），全量替换成本可忽略。
            try {
                com.badlogic.gdx.utils.Json rjson = com.unciv.json.UncivJsonKt.json();
                java.util.List<Object> rels = new java.util.ArrayList<>();
                for (Object r : gameInfo.getReligions().values()) rels.add(rjson.toJson(r));
                state.put("religions", rels);
            } catch (Throwable ignore) {}

            // UncivSync: 战斗结果（服务端唯一结算）不再放进共享 sim —— 迷雾里的战斗
            // 不能给视野外的玩家弹扣血提示（信息泄漏）。FsServer 发送层按接收者视野，
            // 对可见者把 battle 段追加到 state 末尾（对齐 MapVisualization.isAttackVisible）。
            msg.put("state", state);
            return FsJson.encode(msg);
        } catch (Throwable t) {
            System.err.println("[gamestate] buildSimMessage failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Build a FULL sim message (isFull=true) containing every unit / city / civ.
     * Used for the initial state pushed to a client on connect, and for re-sync
     * after a reload. Mirrors buildSimMessage but without a diff filter.
     */
    public String buildFullSimMessage() {
        if (gameInfo == null) return null;
        try {
            java.util.LinkedHashMap<String, Object> msg = new java.util.LinkedHashMap<>();
            msg.put("type", "sim");
            msg.put("ok", "true");
            msg.put("turn", gameInfo.getTurns());
            java.util.LinkedHashMap<String, Object> state = new java.util.LinkedHashMap<>();
            state.put("turn", gameInfo.getTurns());
            state.put("currentPlayer", gameInfo.getCurrentPlayer());
            state.put("paused", false);
            state.put("full", true);

            java.util.List<Object> unitEntries = new java.util.ArrayList<>();
            for (Civilization civ : gameInfo.getCivilizations()) {
                for (java.util.Iterator<com.unciv.logic.map.mapunit.MapUnit> it = civ.getUnits().getCivUnits().iterator(); it.hasNext(); ) {
                    unitEntries.add(unitToSnapshot(it.next()));
                }
            }
            state.put("units", unitEntries);
            state.put("removedUnits", new java.util.ArrayList<>());

            java.util.List<Object> cityEntries = new java.util.ArrayList<>();
            for (Civilization civ : gameInfo.getCivilizations()) {
                for (Object cityObj : civ.getCities()) {
                    com.unciv.logic.city.City c = (com.unciv.logic.city.City) cityObj;
                    cityEntries.add(cityToSnapshot(c));
                }
            }
            state.put("cities", cityEntries);
            state.put("removedCities", new java.util.ArrayList<>());

            java.util.List<Object> civEntries = new java.util.ArrayList<>();
            for (Civilization civ : gameInfo.getCivilizations()) {
                if (civ.isBarbarian() || civ.isSpectator()) continue;
                civEntries.add(civToSnapshot(civ));
            }
            state.put("civs", civEntries);

            // 全量地块（有内容的那些）—— 与 buildSimMessage 同一套字段。
            try {
                state.put("tiles", collectAllTiles());
            } catch (Throwable t) {
                System.err.println("[gamestate] collectAllTiles failed: " + t.getMessage());
                state.put("tiles", new java.util.ArrayList<>());
            }

            // 整档自带全部地形，增量 terrainChanges 留空；仅刷新地形指纹缓存，
            // 避免下一帧把整张地图当成"地形变化"全量下发。
            collectTerrainChanges();
            try {
                state.put("encampments", collectEncampments());
            } catch (Throwable t) {
                state.put("encampments", new java.util.ArrayList<>());
            }
            state.put("terrainChanges", new java.util.ArrayList<>());

            msg.put("state", state);
            return FsJson.encode(msg);
        } catch (Throwable t) {
            System.err.println("[gamestate] buildFullSimMessage failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Attach the official, recursively complete GameInfo serialization to a
     * lightweight sim message.
     *
     * The hand-written units/cities/civs arrays are UI deltas only.  They are
     * intentionally not a data contract and can never prove completeness when
     * Unciv adds a field.  authoritativeGame is produced by UncivJson itself,
     * so every serialized class and every serialized key at every nesting level
     * travels through the client/server boundary without a second field list.
     */
    @SuppressWarnings("unchecked")
    private String attachAuthoritativeGame(String simJson, long revision) {
        if (simJson == null || gameInfo == null) return simJson;
        try {
            String saveJson = toSaveJson();
            if (saveJson == null) return null;
            Map<String, Object> message = FsJson.asObject(FsJson.parse(simJson));
            Map<String, Object> state = FsJson.asObject(message.get("state"));
            state.put("authoritativeFormat", "GameInfo");
            state.put("authoritativeRevision", revision);
            state.put("authoritativeChecksum", gameInfo.getChecksum());
            state.put("authoritativeGame", FsJson.parse(saveJson));
            return FsJson.encode(message);
        } catch (Throwable t) {
            System.err.println("[gamestate] attachAuthoritativeGame failed: " + t.getMessage());
            return null;
        }
    }

    /** One-op transport: immediate UI delta plus the complete authoritative state. */
    public String buildAuthoritativeSimMessage(FsOpResult diff, long revision) {
        return attachAuthoritativeGame(buildSimMessage(diff), revision);
    }

    /** Initial/turn transport: full UI delta plus the complete authoritative state. */
    public String buildFullAuthoritativeSimMessage(long revision) {
        return attachAuthoritativeGame(buildFullSimMessage(), revision);
    }

    // ========================================================================
    // UncivSync: 地块投影（tiles）
    //
    // 改良 / 道路 / 掠夺 / 施工进度 —— 工人一动就会变，是"每回合都在变"的状态。
    // 以前没有投影通道，只能靠整档兜底；整档 = 客户端重建 WorldScreen = 卡顿。
    //
    // 这里做两件事：
    //   ① collectAllTiles  —— 整档 sim（回合开始 / 新客户端接入）用，给全部有内容的地块
    //   ② collectChangedTiles —— 增量 sim（每次 op）用，只给"自上次广播以来变了"的地块
    // 两者都会刷新指纹缓存，所以增量只发变化，常规 op 基本是空数组。
    // ========================================================================

    /** 上次广播出去的地块指纹（"x,y" -> 指纹），用于算增量。 */
    private java.util.HashMap<String, String> lastTileSignatures = new java.util.HashMap<>();

    /** 蛮族营地改良名（与 Unciv Constants.barbarianEncampment 完全一致）。 */
    private static final String BARBARIAN_ENCAMPMENT = "Barbarian encampment";

    /** 上次广播出去的地形指纹（"x,y" -> 指纹），用于算地形突变增量（核弹改地形等）。 */
    private java.util.HashMap<String, String> lastTerrainSignatures = new java.util.HashMap<>();

    private static String tileKey(com.unciv.logic.map.tile.Tile t) {
        return t.getPosition().getX() + "," + t.getPosition().getY();
    }

    /**
     * 地块指纹：只覆盖"客户端看得见的内容"。
     * 施工进度也计入 —— 工人每回合的进度变化正是要推给客户端的东西。
     */
    private static String tileSignature(com.unciv.logic.map.tile.Tile t) {
        StringBuilder sb = new StringBuilder(48);
        sb.append(t.getImprovement() == null ? "" : t.getImprovement());
        if (t.getImprovementIsPillaged()) sb.append("|p");
        sb.append("|r=").append(t.getRoadStatus());
        if (t.getRoadIsPillaged()) sb.append("|rp");
        for (String entry : improvementQueueEntries(t)) sb.append("|q=").append(entry);
        // 地形特征（遗迹 / 森林 / 沼泽…）必须进签名：踩遗迹=特征消失，不进签名就不会推送
        sb.append("|f=").append(t.getTerrainFeatures());
        // 领土归属（所属城市 id）：买地 / 文化扩张 / 征服都会改归属，不进签名就不推送
        sb.append("|oc=").append(t.getOwningCity() == null ? "" : t.getOwningCity().getId());
        // 基础地形（核弹改地形等）
        sb.append("|b=").append(readField(t, "baseTerrain"));
        return sb.toString();
    }

    /** 这个地块有没有值得同步的内容。 */
    public static boolean isTileInteresting(com.unciv.logic.map.tile.Tile t) {
        return t.getImprovement() != null
                || t.getImprovementIsPillaged()
                || t.getRoadStatus() != com.unciv.logic.map.tile.RoadStatus.None
                || t.getRoadIsPillaged()
                || !t.getTerrainFeatures().isEmpty()
                || !improvementQueueEntries(t).isEmpty();
    }

    /**
     * 读 Tile 的在建队列（"改良名:剩余回合"）。
     *
     * 用反射是因为 `improvementQueue` 在 Kotlin 里是 `internal`，JVM 上被改名成
     * `getImprovementQueue$<module>`，直接写死名字会在换分支/换版本时炸。
     */
    private static java.util.ArrayList<String> improvementQueueEntries(com.unciv.logic.map.tile.Tile t) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        try {
            for (java.lang.reflect.Method m : t.getClass().getMethods()) {
                if (!m.getName().startsWith("getImprovementQueue")) continue;
                if (m.getParameterCount() != 0) continue;
                Object v = m.invoke(t);
                if (!(v instanceof java.util.List)) continue;
                for (Object e : (java.util.List<?>) v) {
                    if (e == null) continue;
                    String name = null;
                    int turns = 0;
                    try { name = (String) e.getClass().getMethod("getImprovement").invoke(e); } catch (Throwable ignore) {}
                    try { turns = ((Number) e.getClass().getMethod("getTurnsToImprovement").invoke(e)).intValue(); } catch (Throwable ignore) {}
                    if (name != null) out.add(name + ":" + turns);
                }
                break;
            }
        } catch (Throwable ignore) {}
        return out;
    }

    private static java.util.LinkedHashMap<String, Object> tileToSnapshot(com.unciv.logic.map.tile.Tile t) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("x", t.getPosition().getX());
        m.put("y", t.getPosition().getY());
        // 空串 = 无改良 / 无道路（客户端据此清除旧值）
        m.put("improvement", t.getImprovement() == null ? "" : t.getImprovement());
        m.put("improvementPillaged", t.getImprovementIsPillaged() ? "true" : "false");
        m.put("road", String.valueOf(t.getRoadStatus()));
        m.put("roadPillaged", t.getRoadIsPillaged() ? "true" : "false");
        // 地形特征（遗迹被踩后消失、森林被砍…）—— 空列表代表清除
        m.put("features", new java.util.ArrayList<>(t.getTerrainFeatures()));
        // 领土归属：所属城市 id（空串 = 无主）。客户端 setOwningCity 回放，领土颜色随之更新
        m.put("owningCity", t.getOwningCity() == null ? "" : t.getOwningCity().getId());
        m.put("b", String.valueOf(readField(t, "baseTerrain")));
        java.util.ArrayList<String> q = improvementQueueEntries(t);
        if (!q.isEmpty()) m.put("improvementQueue", q);
        return m;
    }

    /** 全部有内容的地块（整档 sim 用），同时刷新指纹缓存。 */
    public java.util.List<Object> collectAllTiles() {
        java.util.List<Object> out = new java.util.ArrayList<>();
        java.util.HashMap<String, String> now = new java.util.HashMap<>();
        for (com.unciv.logic.map.tile.Tile t : gameInfo.getTileMap().getTileList()) {
            if (t == null) continue;
            now.put(tileKey(t), tileSignature(t));
            if (isTileInteresting(t)) out.add(tileToSnapshot(t));
        }
        lastTileSignatures = now;
        return out;
    }

    /** 自上次广播以来变化的地块（增量 sim 用），同时刷新指纹缓存。 */
    public java.util.List<Object> collectChangedTiles() {
        java.util.List<Object> out = new java.util.ArrayList<>();
        java.util.HashMap<String, String> now = new java.util.HashMap<>();
        for (com.unciv.logic.map.tile.Tile t : gameInfo.getTileMap().getTileList()) {
            if (t == null) continue;
            String key = tileKey(t);
            String sig = tileSignature(t);
            now.put(key, sig);
            if (!sig.equals(lastTileSignatures.get(key))) out.add(tileToSnapshot(t));
        }
        lastTileSignatures = now;
        return out;
    }

    // ========================================================================
    // UncivSync: 蛮族营地 + 地形突变投影（对齐 GC 的 encampments / terrainChanges）
    //
    // - encampments：蛮族营的坐标是"会突然出现/消失"的状态（工人清营、被占领消灭），
    //   且出现频率低；单独成通道让客户端按坐标增量增删，不必为它整档。
    // - terrainChanges：核弹改地形等"地形被改写"事件。沿 GC 的 terrainChanges 通道增量下发，
    //   客户端就地改地形；worldSignature 的整档兜底仍在（地形签名一变就整档），
    //   所以即便客户端就地改地形失败，也能由整档纠正 —— 这是兜底，不是依赖。
    // ========================================================================

    /** 地形指纹：baseTerrain + 地形特征 + 自然奇观 + 资源（与 worldSignature 地块部分同源）。 */
    private static String terrainSignature(com.unciv.logic.map.tile.Tile t) {
        StringBuilder sb = new StringBuilder(48);
        sb.append(t.baseTerrain);
        java.util.List<String> tf = t.getTerrainFeatures();
        if (tf != null && !tf.isEmpty()) {
            java.util.ArrayList<String> sorted = new java.util.ArrayList<>(tf);
            java.util.Collections.sort(sorted);
            sb.append('|').append(sorted);
        }
        try { if (t.getNaturalWonder() != null) sb.append("|nw=").append(t.getNaturalWonder()); } catch (Throwable ignore) {}
        try {
            if (t.getResource() != null)
                sb.append("|res=").append(t.getResource()).append('x').append(t.getResourceAmount());
        } catch (Throwable ignore) {}
        return sb.toString();
    }

    /** 该地块是否蛮族营地（改良名匹配）。 */
    private static boolean isEncampment(com.unciv.logic.map.tile.Tile t) {
        try { return BARBARIAN_ENCAMPMENT.equals(t.getImprovement()); } catch (Throwable ignore) { return false; }
    }

    private static java.util.LinkedHashMap<String, Object> terrainToSnapshot(com.unciv.logic.map.tile.Tile t) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("x", t.getPosition().getX());
        m.put("y", t.getPosition().getY());
        m.put("baseTerrain", t.baseTerrain == null ? "" : t.baseTerrain);
        java.util.List<String> tf = t.getTerrainFeatures();
        if (tf != null && !tf.isEmpty()) m.put("features", new java.util.ArrayList<>(tf));
        try { if (t.getNaturalWonder() != null) m.put("naturalWonder", t.getNaturalWonder()); } catch (Throwable ignore) {}
        try {
            if (t.getResource() != null) {
                m.put("resource", t.getResource());
                m.put("resourceAmount", t.getResourceAmount());
            }
        } catch (Throwable ignore) {}
        return m;
    }

    /** 当前所有蛮族营地坐标（全量小列表，full / incremental 都下发）。 */
    public java.util.List<Object> collectEncampments() {
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (gameInfo == null) return out;
        for (com.unciv.logic.map.tile.Tile t : gameInfo.getTileMap().getTileList()) {
            if (t == null) continue;
            if (isEncampment(t)) {
                java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("x", t.getPosition().getX());
                m.put("y", t.getPosition().getY());
                out.add(m);
            }
        }
        return out;
    }

    /**
     * 自上次广播以来地形变化的地块（增量 sim 用），同时刷新地形指纹缓存。
     * 对齐 GC 的 terrainChanges 通道：核弹改地形等不再触发整档，客户端就地改地形。
     * worldSignature 的整档兜底仍在，客户端就地改地形失败也可由整档纠正。
     */
    public java.util.List<Object> collectTerrainChanges() {
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (gameInfo == null) return out;
        java.util.HashMap<String, String> now = new java.util.HashMap<>();
        for (com.unciv.logic.map.tile.Tile t : gameInfo.getTileMap().getTileList()) {
            if (t == null) continue;
            String key = tileKey(t);
            String sig = terrainSignature(t);
            now.put(key, sig);
            if (!sig.equals(lastTerrainSignatures.get(key))) out.add(terrainToSnapshot(t));
        }
        lastTerrainSignatures = now;
        return out;
    }

    /**
     * "投影覆盖不到的世界内容"签名 —— 用来决定**是否还需要整档下发**。
     *
     * 单位 / 城市 / 文明 / 地块（改良·道路·掠夺·施工）都已经被 sim 投影覆盖，
     * 剩下只有地形本身（核弹改地形）和宗教地图（创立/传播/信条）不在这条通道上。
     * 它们的变化很稀少，所以整档也就变成稀有事件 —— 这正是"不再每回合重建世界"的前提。
     *
     * 返回 null 表示算不出来（保守起见调用方应当仍然整档）。
     */
    public static String worldSignature(GameInfo gi) {
        if (gi == null) return null;
        try {
            StringBuilder sb = new StringBuilder(1 << 16);
            java.util.ArrayList<String> rows = new java.util.ArrayList<>();
            for (com.unciv.logic.map.tile.Tile t : gi.getTileMap().getTileList()) {
                if (t == null) continue;
                StringBuilder r = new StringBuilder(48);
                r.append(t.getPosition().getX()).append(',').append(t.getPosition().getY())
                 .append('|').append(t.baseTerrain);
                java.util.List<String> tf = t.getTerrainFeatures();
                if (tf != null && !tf.isEmpty()) {
                    java.util.ArrayList<String> sorted = new java.util.ArrayList<>(tf);
                    java.util.Collections.sort(sorted);
                    r.append('|').append(sorted);
                }
                try { if (t.getNaturalWonder() != null) r.append("|nw=").append(t.getNaturalWonder()); } catch (Throwable ignore) {}
                try {
                    if (t.getResource() != null)
                        r.append("|res=").append(t.getResource()).append('x').append(t.getResourceAmount());
                } catch (Throwable ignore) {}
                rows.add(r.toString());
            }
            java.util.Collections.sort(rows);
            for (String r : rows) sb.append(r).append('\n');
            sb.append("#religions#");
            try {
                sb.append(com.unciv.json.UncivJsonKt.json().toJson(gi.getReligions()));
            } catch (Throwable t2) {
                sb.append(gi.getReligions().keySet());
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 反射读出 City 的 workedTiles 属性（HashSet&lt;HexCoord&gt;）。
     * 之所以用反射：City 同时有 属性 workedTiles 与 函数 getWorkedTiles(): Sequence&lt;Tile&gt;，
     * 在 JVM 上产生两个同名不同返回类型的方法，Java 源码调用会报 "引用不明确"。
     */
    private static java.util.Set<?> readWorkedTilesRaw(com.unciv.logic.city.City c) {
        try {
            for (java.lang.reflect.Method m : c.getClass().getMethods()) {
                if (!"getWorkedTiles".equals(m.getName())) continue;
                if (!java.util.Set.class.isAssignableFrom(m.getReturnType())) continue;
                Object v = m.invoke(c);
                if (v instanceof java.util.Set) return (java.util.Set<?>) v;
            }
        } catch (Throwable ignore) {}
        return null;
    }

    private static java.util.LinkedHashMap<String, Object> unitToSnapshot(com.unciv.logic.map.mapunit.MapUnit u) {
        return unitToSnapshot(u, null);
    }

    /**
     * sim 快照专用的全字段序列化器：关闭 libgdx 的"原型省略"（默认会跳过等于
     * 新实例默认值的字段 —— currentMovement=0、health=100、isPuppet=false 这类
     * **有意义的默认状态**会被整个省掉，客户端永远收不到"行动力被清零"这类信息，
     * 正是"升级后客户端还能动"的根因）。存档文件（toSaveJson）仍用默认省略的
     * 官方格式，只有 sim 的 obj 段用这个实例写出全部字段。
     *
     * libgdx Json 非线程安全：本实例只在 stateLock 内的快照构建路径使用。
     */
    private static final com.badlogic.gdx.utils.Json SNAPSHOT_JSON = com.unciv.json.UncivJsonKt.json();

    static {
        SNAPSHOT_JSON.setUsePrototypes(false);
    }

    /**
     * @param prevPos 该单位在本条 diff 之前的位置（"x,y"），null 表示未知/新单位。
     *                sim 会以 fromX/fromY 携带，供客户端在"本地还没同步过该单位"时按旧位置定位。
     */
    private static java.util.LinkedHashMap<String, Object> unitToSnapshot(com.unciv.logic.map.mapunit.MapUnit u, String prevPos) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        // Client FrameSync.createUnitFromState / applyState key fields. NOTE the field
        // names are the client's OWN snapshot schema (NOT Unciv's save serialization):
        //   civ  = civName (matched via it.getCivName())
        m.put("id", u.getId());
        try { m.put("civ", u.getCiv().getCivName()); }
        catch (Throwable t) { m.put("civ", u.getCiv().getCivID()); }
        try { m.put("name", u.getBaseUnit().getName()); } catch (Throwable t) {}
        // action / instanceName 是**可空字符串**：官方序列化对 null 直接省略字段，而
        // "停止探索/休眠解除"恰恰是把 action 置 null —— 只靠 obj 段客户端永远收不到
        // "动作已清除"（按钮一直显示"停止探索"的根因）。显式通道空串 = null。
        try { m.put("action", u.getAction() == null ? "" : u.getAction()); } catch (Throwable t) {}
        try { m.put("instanceName", u.getInstanceName() == null ? "" : u.getInstanceName()); } catch (Throwable t) {}
        com.unciv.logic.map.tile.Tile t = u.getTile();
        if (t != null) {
            com.unciv.logic.map.HexCoord pos = t.getPosition();
            m.put("x", pos.getX()); m.put("y", pos.getY());
        }
        // 单位快照 = 官方序列化器整对象（**全字段**：见 SNAPSHOT_JSON —— 必须关掉
        // 默认值省略，否则 0 行动力/满血/非傀儡这类默认状态在序列化层就丢了）。
        // 客户端按 id 找到现有实例后，按"obj JSON 里实际在场的字段"反射搬入。
        try { m.put("obj", SNAPSHOT_JSON.toJson(u)); } catch (Throwable t2) {}
        if (prevPos != null) {
            int comma = prevPos.indexOf(',');
            if (comma > 0) {
                try {
                    m.put("fromX", Integer.parseInt(prevPos.substring(0, comma).trim()));
                    m.put("fromY", Integer.parseInt(prevPos.substring(comma + 1).trim()));
                } catch (Throwable ignore) {}
            }
        }
        return m;
    }

    private static java.util.LinkedHashMap<String, Object> cityToSnapshot(com.unciv.logic.city.City c) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("disabledConstructions", new java.util.ArrayList<>(c.getDisabledConstructions()));
        m.put("unitShouldUseSavedPromotion", new java.util.LinkedHashMap<>(c.getUnitShouldUseSavedPromotion()));
        m.put("lockedTiles", c.getLockedTiles().stream()
                .map(p -> p.getX() + "," + p.getY()).toList());
        m.put("id", c.getId());
        try { m.put("civ", c.getCiv().getCivName()); }
        catch (Throwable e) { m.put("civ", c.getCiv().getCivID()); }
        try { m.put("name", c.getName()); } catch (Throwable e) {}
        com.unciv.logic.map.tile.Tile tile = c.getCenterTile();
        if (tile != null) {
            com.unciv.logic.map.HexCoord pos = tile.getPosition();
            m.put("x", pos.getX()); m.put("y", pos.getY());
        }
        // 城市快照 = 官方序列化器整对象（**全字段**：SNAPSHOT_JSON 关闭默认值省略 ——
        // isPuppet=false / health=100 这类默认状态同样是有意义的权威值，省略即丢失）。
        try { m.put("obj", SNAPSHOT_JSON.toJson(c)); } catch (Throwable e) {}
        return m;
    }

    private static java.util.LinkedHashMap<String, Object> civToSnapshot(Civilization civ) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("goldPercentConvertedToScience", civ.getTech().getGoldPercentConvertedToScience());
        m.put("disabledCityConstructions", new java.util.ArrayList<>(civ.getDisabledCityConstructions()));
        m.put("hasMovedAutomatedUnits", civ.getHasMovedAutomatedUnits());
        // Client syncCivInfo matches the outer "civ" field via getCivName().
        try { m.put("civ", civ.getCivName()); }
        catch (Throwable t) { m.put("civ", civ.getCivID()); }
        try { m.put("gold", civ.getGold()); } catch (Throwable e) {}
        // culture 等派生统计不发：客户端核心本地可算（单一状态源原则）
        // UncivSync: 待处理的交易请求 —— 玩家→玩家实时交易的"送达"通道。
        // 客户端拿到后写回本地 civ.tradeRequests，官方的 WorldScreen 会自动弹 TradePopup。
        // 放在这里而不是单独消息类型，是为了让重连/整档下发时也天然一致。
        try {
            java.util.List<Object> reqs = new java.util.ArrayList<>();
            com.badlogic.gdx.utils.Json json = com.unciv.json.UncivJsonKt.json();
            for (com.unciv.logic.trade.TradeRequest tr : civ.getTradeRequests()) {
                java.util.LinkedHashMap<String, Object> e = new java.util.LinkedHashMap<>();
                e.put("from", tr.getRequestingCiv());
                e.put("tradeJson", json.toJson(tr.getTrade()));
                reqs.add(e);
            }
            m.put("tradeRequests", reqs);
        } catch (Throwable ignore) {}

        // 城邦任务 / 宗教管理器（含信仰存量）/ 生效中的交易协议 —— 官方序列化器整对象
        try { m.put("questManager", com.unciv.json.UncivJsonKt.json().toJson(civ.getQuestManager())); } catch (Throwable ignore) {}
        try { m.put("religionManager", com.unciv.json.UncivJsonKt.json().toJson(civ.getReligionManager())); } catch (Throwable ignore) {}
        try {
            com.badlogic.gdx.utils.Json tjson = com.unciv.json.UncivJsonKt.json();
            java.util.LinkedHashMap<String, Object> tradesMap = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, ? extends com.unciv.logic.civilization.diplomacy.DiplomacyManager> en
                    : civ.getDiplomacy().entrySet()) {
                java.util.List<Object> list = new java.util.ArrayList<>();
                for (Object tr : en.getValue().getTrades()) list.add(tjson.toJson(tr));
                if (!list.isEmpty()) tradesMap.put(en.getKey(), list);
            }
            m.put("trades", tradesMap);
        } catch (Throwable ignore) {}

        // UncivSync: 外交通知队列（popupAlerts）—— "宣友提议 / 索求(Demand)" 的送达通道。
        // 服务端把请求写进对方权威存档的 popupAlerts，客户端 applyCivs 写回本地后，
        // 官方的 WorldScreen.update 会发现 viewingCiv.popupAlerts 非空并自动弹 AlertPopup。
        try {
            java.util.List<Object> alerts = new java.util.ArrayList<>();
            for (com.unciv.logic.civilization.PopupAlert pa : civ.getPopupAlerts()) {
                if (pa == null) continue;
                java.util.LinkedHashMap<String, Object> e = new java.util.LinkedHashMap<>();
                try { e.put("type", pa.getType() == null ? null : pa.getType().name()); } catch (Throwable t2) {}
                try { e.put("value", pa.getValue()); } catch (Throwable t2) {}
                alerts.add(e);
            }
            m.put("alerts", alerts);
        } catch (Throwable ignore) {}

        // UncivSync: 通知（Notification）—— **核心自带的事件通道**。
        //
        // 奇观被抢 / 遗迹奖励 / 城陷 / 伟人诞生 / 单位被攻击 / 政策可用 … 全都往这个列表里塞，
        // 所以不需要像 UncivGC 那样为每种事件写一条类型化消息（wonderLost / ruinReward /
        // cityConquered / victory …）：把它投影过去，官方那套通知 UI 自己就会显示，而且天然覆盖
        // 我们没预料到的新通知类型。
        //
        // 生命周期天然一致：`TurnManager.endTurn()` 每回合会把 notifications 收进 notificationsLog
        // 再 clear()，而服务端推进回合跑的就是它 —— 两端同一份 clear 逻辑，不会出现"消费过又被补回来"。
        try {
            java.util.List<Object> notifs = new java.util.ArrayList<>();
            java.util.ArrayList<com.unciv.logic.civilization.Notification> src = civ.getNotifications();
            if (src != null) {
                for (com.unciv.logic.civilization.Notification n : src) {
                    if (n == null) continue;
                    java.util.LinkedHashMap<String, Object> e = new java.util.LinkedHashMap<>();
                    try { e.put("text", n.getText()); } catch (Throwable t2) { continue; }
                    try { e.put("category", n.getCategory() == null ? null : n.getCategory().name()); } catch (Throwable t2) {}
                    try {
                        java.util.ArrayList<String> icons = new java.util.ArrayList<>();
                        if (n.getIcons() != null) for (String ic : n.getIcons()) if (ic != null) icons.add(ic);
                        e.put("icons", icons);
                    } catch (Throwable t2) {}
                    notifs.add(e);
                }
            }
            m.put("notifications", notifs);
        } catch (Throwable ignore) {}

        // UncivSync: 外交关系摘要 —— 关系状态 + 关键标记（谴责/宣友/承诺）。
        // 客户端据此更新关系颜色/文字与"承诺"列表；否则 A 谴责 B 后 B 的界面看不出变化。
        try {
            java.util.List<Object> diplo = new java.util.ArrayList<>();
            for (com.unciv.logic.civilization.diplomacy.DiplomacyManager dm : civ.getDiplomacy().values()) {
                if (dm == null) continue;
                String otherId;
                try { otherId = dm.getOtherCiv().getCivID(); } catch (Throwable t) { continue; }
                if (otherId == null) continue;
                java.util.LinkedHashMap<String, Object> e = new java.util.LinkedHashMap<>();
                e.put("with", otherId);
                try { e.put("status", dm.getDiplomaticStatus().name()); } catch (Throwable t) {}
                // Opinion is not derived from status/flags: it is the persisted modifier map.
                // Omitting it made a client keep its old value (or a newly-created zero map),
                // which looked like an embassy/friendship suddenly reset to zero after a sim.
                try { e.put("modifiers", new java.util.LinkedHashMap<>(dm.getDiplomaticModifiers())); }
                catch (Throwable t) {}
                try { e.put("smoothedOpinion", dm.getSmoothedOpinionOfOtherCiv()); }
                catch (Throwable t) {}
                java.util.LinkedHashMap<String, Object> flags = new java.util.LinkedHashMap<>();
                // 关键标记：谴责 / 宣友 / 拒绝宣友
                for (String fn : new String[]{"Denunciation", "DeclarationOfFriendship", "DeclinedDeclarationOfFriendship"}) {
                    try {
                        com.unciv.logic.civilization.diplomacy.DiplomacyFlags f =
                            com.unciv.logic.civilization.diplomacy.DiplomacyFlags.valueOf(fn);
                        if (f != null && dm.hasFlag(f)) flags.put(fn, dm.getFlag(f));
                    } catch (Throwable t) {}
                }
                // 承诺类标记（每种 Demand 的 agreedToDemand）
                try {
                    for (com.unciv.logic.civilization.diplomacy.Demand d :
                            com.unciv.logic.civilization.diplomacy.Demand.values()) {
                        try {
                            com.unciv.logic.civilization.diplomacy.DiplomacyFlags af = d.getAgreedToDemand();
                            if (af != null && dm.hasFlag(af)) flags.put(af.name(), dm.getFlag(af));
                        } catch (Throwable t) {}
                    }
                } catch (Throwable t) {}
                e.put("flags", flags);
                diplo.add(e);
            }
            m.put("diplomacy", diplo);
        } catch (Throwable ignore) {}

        // ====================================================================
        // UncivSync: 文明级"长周期状态"（政策 / 科技 / 文化）。
        //
        // 这些以前不在 sim 里，导致 civ.choosePolicy / civ.chooseTech 这类 op 的效果
        // **只落在服务端**，客户端要等下一回合整档下发才看得到。如果玩家的政策操作
        // 恰好与"回合推进 + 整档下发"并发，重建出来的世界会缺这条政策 —— 表现就是
        // "政策选择回退了"。把政策/科技放进 sim，op 生效的那一刻所有客户端立即同步，
        // 不再依赖整档，也就不会再回退。
        // ====================================================================
        try {
            Object pm = civ.getClass().getMethod("getPolicies").invoke(civ);
            if (pm != null) {
                java.util.List<Object> adopted = new java.util.ArrayList<>();
                Object list = readProp(pm, "adoptedPolicies");
                if (list instanceof Iterable) {
                    for (Object o : (Iterable<?>) list) adopted.add(o.toString());
                }
                m.put("adoptedPolicies", adopted);

                Object free = readProp(pm, "freePolicies");
                if (free instanceof Number) m.put("freePolicies", ((Number) free).intValue());
                Object stored = readProp(pm, "storedCulture");
                if (stored instanceof Number) m.put("storedCulture", ((Number) stored).intValue());
                // shouldOpenPolicyPicker 是权威状态不是派生数据（第二十一批误删）：客户端不跑
                // 本地回合，addCulture 置位的那次"跨过门槛"事件只有服务端知道——不发它，
                // 客户端政策按钮不亮（表现为"要等两个回合"）。
                Object sopp = readProp(pm, "shouldOpenPolicyPicker");
                if (sopp instanceof Boolean) m.put("sopp", sopp.toString());
                // numberOfAdoptedPolicies 是私有计数器，直接决定下一政策成本；不同步则
                // 客户端算出的成本与权威脱节（第二十一批清"派生键"时漏判，它是真状态）
                try {
                    java.lang.reflect.Field noapF = pm.getClass().getDeclaredField("numberOfAdoptedPolicies");
                    noapF.setAccessible(true);
                    m.put("noap", noapF.getInt(pm));
                } catch (Throwable ignore) {}

            }
        } catch (Throwable ignore) {}

        try {
            Object tm = civ.getClass().getMethod("getTech").invoke(civ);
            if (tm != null) {
                java.util.List<Object> research = new java.util.ArrayList<>();
                Object list = readProp(tm, "techsToResearch");
                if (list instanceof Iterable) {
                    for (Object o : (Iterable<?>) list) research.add(o.toString());
                }
                m.put("techsToResearch", research);
                // overflowScience 是真状态（科技完成时的溢出，决定下一科技起始进度），
                // 第二十一批误判为派生删除，第二十六批复检恢复——客户端进度显示需与权威一致
                try {
                    Object ov2 = readProp(tm, "overflowScience");
                    if (ov2 instanceof Number) m.put("overflowScience", ((Number) ov2).intValue());
                } catch (Throwable ignore) {}
                // 每个科技已投入的科研点数 —— **缺这个，客户端的科技进度条永远不动**
                //（客户端只做渲染、不做结算，服务端不推它就没有任何东西能让进度变）。
                Object inProgress = readProp(tm, "techsInProgress");
                if (inProgress instanceof java.util.Map) {
                    Map<String, Object> prog = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : ((java.util.Map<?, ?>) inProgress).entrySet()) {
                        prog.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    m.put("techsInProgress", prog);
                }
                Object ft = readProp(tm, "freeTechs");
                if (ft instanceof Number) m.put("freeTechs", ((Number) ft).intValue());
            }
        } catch (Throwable ignore) {}

        // ---- 间谍（spies）：以前完全没有投影，导致"派间谍 / 政变"只改了本地 ----
        try {
            Object em = civ.getEspionageManager();
            if (em != null) {
                List<Object> spies = new ArrayList<>();
                Object list = readProp(em, "spyList");
                if (list instanceof Iterable) {
                    for (Object o : (Iterable<?>) list) {
                        if (!(o instanceof com.unciv.models.Spy)) continue;
                        com.unciv.models.Spy spy = (com.unciv.models.Spy) o;
                        Map<String, Object> so = new LinkedHashMap<>();
                        so.put("name", spy.getName());
                        so.put("rank", spy.getRank());
                        so.put("action", spy.getAction() == null ? "None" : spy.getAction().name());
                        so.put("turns", spy.getTurnsRemainingForAction());
                        City spyCity = null;
                        try { spyCity = spy.getCityOrNull(); } catch (Throwable ignore) {}
                        // cityId 为空表示"在老巢"；客户端据此 relink，不用自己算坐标
                        so.put("cityId", spyCity == null ? "" : spyCity.getId());
                        if (spyCity != null) {
                            so.put("cityName", spyCity.getName());
                            so.put("x", spyCity.getLocation().getX());
                            so.put("y", spyCity.getLocation().getY());
                        }
                        spies.add(so);
                    }
                }
                m.put("spies", spies);
            }
        } catch (Throwable ignore) {}

        // ---- 免费伟人名额（伟大人物选择屏的弹出条件就是 freeGreatPeople > 0）----
        try {
            com.unciv.logic.civilization.managers.GreatPersonManager gp = civ.getGreatPeople();
            if (gp != null) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("freeGreatPeople", gp.getFreeGreatPeople());
                g.put("mayaLimitedFreeGP", gp.getMayaLimitedFreeGP());
                List<Object> pool = new ArrayList<>();
                Set<String> longCountPool = gp.getLongCountGPPool();
                if (longCountPool != null) for (String n : longCountPool) pool.add(n);
                g.put("longCountGPPool", pool);
                m.put("greatPeople", g);
            }
        } catch (Throwable ignore) {}

        // ---- UncivSync: 科技「已研究」集合（不只是数量）----
        // 只发数量会让客户端 techsResearched 集合 ≠ 服务端，科技树里已研究项仍显示未研究，
        // 玩家于是能重复选已完成的科技（与「政策回退」「建筑重复」同源：完成之后客户端没收到）。
        try {
            Object tm = civ.getClass().getMethod("getTech").invoke(civ);
            if (tm != null) {
                Object researched = readProp(tm, "techsResearched");
                if (researched instanceof java.util.Collection) {
                    java.util.List<Object> list = new java.util.ArrayList<>();
                    for (Object o : (java.util.Collection<?>) researched) list.add(o.toString());
                    m.put("techsResearched", list);
                }
            }
        } catch (Throwable ignore) {}

        // ---- UncivSync: 黄金时代状态（gap #4）----
        try {
            Object ga = readProp(civ, "goldenAges");
            if (ga != null) {
                java.util.LinkedHashMap<String, Object> g = new java.util.LinkedHashMap<>();
                g.put("active", Boolean.TRUE.equals(callNoArg(ga, "isGoldenAge")));
                Object left = readProp(ga, "turnsLeftForCurrentGoldenAge");
                if (left instanceof Number) g.put("turnsLeft", ((Number) left).intValue());
                Object stored = readProp(ga, "storedHappiness");
                if (stored instanceof Number) g.put("storedHappiness", ((Number) stored).intValue());
                m.put("goldenAge", g);
            }
        } catch (Throwable ignore) {}

        // ---- UncivSync: 胜利进度（gap #6 上）----
        // 每个已启用胜利类型的里程碑完成数/总数，对手面板/排行榜实时刷新。
        try {
            Object vm = readProp(civ, "victoryManager");
            Object gi = readProp(civ, "gameInfo");
            Object gp = readProp(gi, "gameParameters");
            Object victoryTypes = readProp(gp, "victoryTypes");
            Object ruleset = readProp(gi, "ruleset");
            Object victories = readProp(ruleset, "victories");
            if (vm != null && victoryTypes instanceof java.util.Collection
                    && victories instanceof java.util.Map) {
                java.util.List<Object> prog = new java.util.ArrayList<>();
                for (Object vt : (java.util.Collection<?>) victoryTypes) {
                    String vname = vt.toString();
                    Object victory = ((java.util.Map<?, ?>) victories).get(vname);
                    if (victory == null) continue;
                    Object milestones = readProp(victory, "milestoneObjects");
                    int total = (milestones instanceof java.util.Collection)
                            ? ((java.util.Collection<?>) milestones).size() : 0;
                    int completed = 0;
                    try {
                        java.lang.reflect.Method am = vm.getClass().getMethod(
                                "amountMilestonesCompleted", victory.getClass());
                        Object r = am.invoke(vm, victory);
                        if (r instanceof Number) completed = ((Number) r).intValue();
                    } catch (Throwable ignore2) { }
                    java.util.LinkedHashMap<String, Object> e = new java.util.LinkedHashMap<>();
                    e.put("type", vname);
                    e.put("completed", completed);
                    e.put("total", total);
                    prog.add(e);
                }
            }
        } catch (Throwable ignore) {}

        // ---- UncivSync: 城邦影响力 + 任务（gap #5）----
        // 影响力存在「城邦→主流文明」的外交对象里（不是主流→城邦），所以这里以城邦为单位投影，
        // 客户端再写回同一个城邦的外交对象，CityStateDiplomacyTable 才能正确显示。
        if (isCityState(civ)) {
            try {
                // 影响力：对每个主流文明的 DiplomacyManager 取原始 influence 字段
                java.util.LinkedHashMap<String, Object> influence = new java.util.LinkedHashMap<>();
                Object diplos = readProp(civ, "diplomacy");
                if (diplos instanceof java.util.Map) {
                    for (Object key : ((java.util.Map<?, ?>) diplos).keySet()) {
                        Object dm = ((java.util.Map<?, ?>) diplos).get(key);
                        if (dm == null) continue;
                        Object other = readProp(dm, "otherCiv");
                        String otherId = (other instanceof com.unciv.logic.civilization.Civilization)
                                ? ((com.unciv.logic.civilization.Civilization) other).getCivID()
                                : (other == null ? null : other.toString());
                        if (otherId == null) continue;
                        Object inf = readField(dm, "influence");
                        if (inf instanceof Number) influence.put(otherId, ((Number) inf).intValue());
                    }
                }
                if (!influence.isEmpty()) m.put("cityStateInfluence", influence);

                // 任务：assignedQuests（私有字段），逐条发关键字段
                Object qm = readProp(civ, "questManager");
                if (qm != null) {
                    Object aq = readField(qm, "assignedQuests");
                    if (aq instanceof java.util.Collection) {
                        java.util.List<Object> quests = new java.util.ArrayList<>();
                        for (Object q : (java.util.Collection<?>) aq) {
                            if (!(q instanceof com.unciv.logic.civilization.managers.quests.AssignedQuest)) continue;
                            java.util.LinkedHashMap<String, Object> e = new java.util.LinkedHashMap<>();
                            Object v;
                            v = readProp(q, "questName"); if (v != null) e.put("questName", v.toString());
                            v = readProp(q, "assigner"); if (v != null) e.put("assigner", v.toString());
                            v = readProp(q, "assignee"); if (v != null) e.put("assignee", v.toString());
                            v = readProp(q, "assignedOnTurn"); if (v instanceof Number) e.put("assignedOnTurn", ((Number) v).intValue());
                            v = readProp(q, "data1"); if (v != null) e.put("data1", v.toString());
                            v = readProp(q, "data2"); if (v != null) e.put("data2", v.toString());
                            v = callNoArg(q, "getRemainingTurns"); if (v instanceof Number) e.put("remainingTurns", ((Number) v).intValue());
                            quests.add(e);
                        }
                        if (!quests.isEmpty()) m.put("quests", quests);
                    }
                }
            } catch (Throwable ignore) {}
        }

        return m;
    }

    /**
     * 读取一个属性的值（宽松版）：优先 `getX()`，找不到就按前缀匹配 ——
     * Kotlin 的 `internal` 属性访问器带模块名后缀（如 `getAdoptedPolicies$core`），
     * 直接 getMethod("getAdoptedPolicies") 会漏掉。
     */
    private static Object readProp(Object target, String prop) {
        if (target == null) return null;
        String exact = "get" + Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
        String prefix = exact;
        for (java.lang.reflect.Method mm : target.getClass().getMethods()) {
            if (mm.getParameterCount() != 0) continue;
            if (mm.getName().equals(exact) || mm.getName().startsWith(prefix + "$")) {
                try { return mm.invoke(target); } catch (Throwable ignore) {}
            }
        }
        return null;
    }

    /** 调一个无参方法，依次尝试 name / getName / isName（兼容 Kotlin 的 isXxx 属性）。 */
    private static Object callNoArg(Object target, String name) {
        if (target == null) return null;
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        String[] candidates = new String[]{ name, "get" + cap, "is" + cap };
        for (java.lang.reflect.Method mm : target.getClass().getMethods()) {
            if (mm.getParameterCount() != 0) continue;
            for (String c : candidates) {
                if (mm.getName().equals(c)) {
                    try { return mm.invoke(target); } catch (Throwable ignore) {}
                }
            }
        }
        return null;
    }

    /** 取对象上的私有/任意字段值（readProp 找不到私有字段时用，例如 DiplomacyManager.influence）。 */
    private static Object readField(Object target, String field) {
        if (target == null) return null;
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** 一个 civilization 是否是城邦（civ.isCityState）。 */
    private static boolean isCityState(Object civ) {
        if (civ == null) return false;
        Object r = callNoArg(civ, "isCityState");
        return Boolean.TRUE.equals(r);
    }

    /** Advance to next turn. UI-bound progress widget passed as null - works in headless mode. */
    public void nextTurn() {
        if (gameInfo == null) return;
        // UncivSync: 同步回合推进必须走 FsTurnSync，不能用 GameInfo.nextTurn()。
        // 后者是轮流制实现（只对轮到的玩家 startTurn → 重置移动力），在"所有人同时操作"
        // 的模式下会让其他玩家的单位永远拿不到新移动力，并且从不执行他们的 endTurn 结算。
        FsTurnSync.advance(gameInfo);
    }

    private static java.util.List<com.unciv.logic.map.mapunit.MapUnit> toList(kotlin.sequences.Sequence<com.unciv.logic.map.mapunit.MapUnit> s) {
        java.util.List<com.unciv.logic.map.mapunit.MapUnit> out = new java.util.ArrayList<>();
        for (java.util.Iterator<com.unciv.logic.map.mapunit.MapUnit> it = s.iterator(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    /**
     * 整档 JSON（带版本号 + 校验和，与写盘格式完全一致）。
     * 用于「每回合整档下发」：客户端拿到后反序列化并重建本地 GameInfo，
     * 从此客户端状态 = 服务端权威状态（地图 / 单位 id / 城市 / 文明全部一致）。
     */
    public String toSaveJson() {
        if (gameInfo == null) return null;
        try {
            gameInfo.setVersion(CompatibilityVersion.Companion.getCURRENT_COMPATIBILITY_VERSION());
            gameInfo.setChecksum(gameInfo.calculateChecksum());
            com.badlogic.gdx.utils.Json json = com.unciv.json.UncivJsonKt.json();
            return json.toJson(gameInfo);
        } catch (Throwable t) {
            System.err.println("[gamestate] toSaveJson failed: " + t.getMessage());
            return null;
        }
    }

    public void saveToFile(File f) {
        if (gameInfo == null) return;
        long t0 = System.currentTimeMillis();
        try {
            // Mirror UncivFiles.gameInfoToString(game, forceZip = false, updateChecksum = true):
            // stamp the compatibility version + checksum, so the client accepts the save when it
            // downloads it back over GET /files/<id>. We write plain JSON on purpose (that is also
            // the format of the client's own local saves) - decodeSaveText() reads both.
            String text = toSaveJson();
            if (text == null) return;
            // 原子写：先写同目录临时文件再 rename 替换。直接写目标文件的话，写到一半
            // 崩溃/断电会留下截断的 JSON —— 而这是唯一权威存档，损坏 = 整局进度丢失。
            // rename 在同一卷上是原子的；不支持原子移动的文件系统退化为普通替换。
            java.nio.file.Path target = f.toPath();
            java.nio.file.Path tmp = target.resolveSibling(f.getName() + ".tmp-" + System.nanoTime());
            java.nio.file.Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            try {
                java.nio.file.Files.move(tmp, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException noAtomic) {
                java.nio.file.Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println("[timing] persist=" + (System.currentTimeMillis() - t0) + "ms (" + text.length() + " chars)");
        } catch (Exception e) {
            System.err.println("[gamestate] saveToFile failed: " + e.getMessage());
        }
    }

    public static final class MapOp {
        public String playerId;
        public String op;
        public Map<String, Object> data;
        public long ts;
    }
}
