// FsTargeting.java - 统一寻址层（服务端权威架构 · 层 2）
//
// 所有 op 对"目标是哪个单位/城市/文明"的解析都走这里，不再各自手写 findXxx。
//
// 寻址优先级（稳定身份）：
//   单位：有 unitId 时只认主键；旧协议完全没有 unitId 时才按位置+类型解析
//   城市：cityId 主键 → 城市名 → 坐标
//   文明：playerId → civName/civID 名
//
// 阶段 1（所有客户端下载同一份服务端 gameInfo）之后 unitId 天生一致，主键即命中；
// 不能在 unitId 无效时按类型找“第一个”单位，否则两个同类型单位会被串成同一个对象。
package unciv.fs.local;

import com.unciv.logic.GameInfo;
import com.unciv.logic.civilization.Civilization;
import com.unciv.logic.city.City;
import com.unciv.logic.map.mapunit.MapUnit;
import com.unciv.logic.map.tile.Tile;

import java.util.Map;

public final class FsTargeting {

    private FsTargeting() {}

    // ===== 基础类型转换 =====

    public static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString().trim()); } catch (Throwable t) { return null; }
    }

    public static String asStr(Object o) {
        return o == null ? null : o.toString();
    }

    /** Kotlin Sequence 不是 java.lang.Iterable，统一转成 List 再遍历。 */
    private static <T> java.util.List<T> list(kotlin.sequences.Sequence<T> seq) {
        java.util.ArrayList<T> out = new java.util.ArrayList<>();
        if (seq == null) return out;
        for (java.util.Iterator<T> it = seq.iterator(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    // ===== 文明 =====

    /** 按 playerId 找真人文明（AI/城邦/barbarian 不参与）。 */
    public static Civilization findCivByPlayerId(GameInfo gi, String playerId) {
        if (gi == null || playerId == null) return null;
        for (Civilization c : gi.getCivilizations()) {
            if (c.isBarbarian() || c.isSpectator()) continue;
            if (playerId.equals(c.getPlayerId())) return c;
        }
        // Fallback: 客户端 playerId 可能等于 civID（离线/单机路径）
        for (Civilization c : gi.getCivilizations()) {
            if (c.isBarbarian() || c.isSpectator()) continue;
            if (playerId.equals(c.getCivID()) || playerId.equals(c.getCivName())) return c;
        }
        return null;
    }

    /** 按文明名 / 文明 ID 找文明（外交类 op 的目标文明）。 */
    public static Civilization findCivByNameOrId(GameInfo gi, String nameOrId) {
        if (gi == null || nameOrId == null) return null;
        for (Civilization c : gi.getCivilizations()) {
            if (nameOrId.equals(c.getCivName()) || nameOrId.equals(c.getCivID())) return c;
        }
        return null;
    }

    // ===== 单位 =====

    public static MapUnit findUnitById(GameInfo gi, int unitId) {
        if (gi == null) return null;
        for (Civilization c : gi.getCivilizations()) {
            MapUnit u = findUnitById(c, unitId);
            if (u != null) return u;
        }
        return null;
    }

    public static MapUnit findUnitById(Civilization civ, int unitId) {
        if (civ == null) return null;
        for (java.util.Iterator<MapUnit> it = civ.getUnits().getCivUnits().iterator(); it.hasNext(); ) {
            MapUnit u = it.next();
            if (u.getId() == unitId) return u;
        }
        return null;
    }

    /** 在某格上找属于 [civ] 的单位，可按类型过滤。 */
    public static MapUnit findUnitOnTile(Civilization civ, int x, int y, String unitType) {
        if (civ == null) return null;
        Tile t = civ.getGameInfo().getTileMap().get(x, y);
        if (t == null) return null;
        for (MapUnit u : list(t.getUnits())) {
            if (u.getCiv() != civ) continue;
            if (unitType != null && !unitType.equals(u.getBaseUnit().getName())) continue;
            return u;
        }
        return null;
    }

    /**
     * 统一单位寻址。所有 unit.* op 都必须经此解析。
     * 返回 null 表示找不到（服务端将拒绝该 op）。
     */
    public static MapUnit resolveUnit(Civilization civ, Map<String, Object> data) {
        if (civ == null || data == null) return null;

        Integer fx = asInt(data.get("fromX"));
        Integer fy = asInt(data.get("fromY"));
        String unitType = asStr(data.get("unitType"));
        Integer uid = asInt(data.get("unitId"));

        // Current protocol: an explicit id is authoritative. Coordinates may be stale because
        // another authoritative sim moved the unit between click and execution.
        if (uid != null) {
            return findUnitById(civ, uid);
        }

        // Legacy protocol only: no id was supplied at all, so position+type is the only identity.
        if (fx != null && fy != null) {
            return findUnitOnTile(civ, fx, fy, unitType);
        }
        return null;
    }

    // ===== 城市 =====

    public static City findCityById(GameInfo gi, String cityId) {
        if (gi == null || cityId == null) return null;
        for (Civilization c : gi.getCivilizations()) {
            for (City city : c.getCities()) {
                if (cityId.equals(city.getId())) return city;
            }
        }
        return null;
    }

    public static City findCityOnTile(Civilization civ, int x, int y) {
        if (civ == null) return null;
        Tile t = civ.getGameInfo().getTileMap().get(x, y);
        if (t == null || !t.isCityCenter()) return null;
        City city = t.getCity();
        if (city == null || city.getCiv() != civ) return null;
        return city;
    }

    /** 统一城市寻址：cityId 主键 → 坐标。 */
    public static City resolveCity(Civilization civ, Map<String, Object> data) {
        if (civ == null || data == null) return null;
        String cityId = asStr(data.get("cityId"));
        if (cityId != null && !cityId.isEmpty()) {
            City c = findCityById(civ.getGameInfo(), cityId);
            if (c != null && c.getCiv() == civ) return c;
        }
        Integer x = asInt(data.get("x"));
        Integer y = asInt(data.get("y"));
        if (x == null) x = asInt(data.get("tileX"));
        if (y == null) y = asInt(data.get("tileY"));
        if (x != null && y != null) return findCityOnTile(civ, x, y);
        return null;
    }
}
