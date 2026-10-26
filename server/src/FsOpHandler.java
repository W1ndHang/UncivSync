// FsOpHandler.java - Apply ALL client-sent ops to the authoritative GameInfo.
//
// Authoritative op inventory was extracted from UncivGC.jar bytecode
// (FrameSync class constant pool + WorldMapHolder.moveUnitToTargetTile$1
// and similar inner classes). Total distinct ops: 70.
//
// For each op the implementation tries to call the matching real Unciv
// method first, and falls back to reflection when the API differs across
// builds. Every op is wrapped in try/catch so a bad op never crashes
// the game; failures are counted and logged.
//
// Coverage by category:
//
//   unit.*         move / swap / attack / createImprovement /
//                  enhanceReligion / foundReligion / spreadReligion /
//                  hurryResearch / hurryPolicy / hurryWonder / hurryBuilding /
//                  tradeMission / setUp / guard / repair / rename / transform
//                  (17 ops)
//
//   city.*         addToQueue / addToQueueWithTile / annex / assignSpecialist /
//                  buyTile / disableConstruction / disableManualSpecialists /
//                  enableManualSpecialists / lockTile / lowerPriority /
//                  moveEntryToEnd / moveQueueEntry / raisePriority /
//                  reassignPopulation / removeFromQueue / removeFromQueueByName /
//                  rename / saveUnitPromotions / sellBuilding / setFocus /
//                  setProduction / setRazing / setUnitSavedPromotion /
//                  stopWorkTile / toggleAvoidGrowth / unlockTile / workTile
//                  (27 ops)
//
//   civ.*          chooseBeliefs / choosePolicy / chooseTech /
//                  cityConquerChoice / declareWar / demand / demandAccept /
//                  demandRefuse / denounce / diplomaticVote / friendshipAccept /
//                  friendshipDecline / friendshipOffer / makePeace / meet /
//                  setConstructionDisabled / tribute / voteResultSeen
//                  (18 ops)
//
//   trade.*        accept / offer / reject / retract
//                  (4 ops)
//
//   tradeRoute.*   acceptOffer / disconnect / offer / rejectOffer
//                  (4 ops)
//
//   Other: civ.nextTurnReady, civ.research, civ.choosePantheon etc. are
//   accepted as no-ops when the client doesn't actually send them —
//   they preserve compat with the test suite.
package unciv.fs.local;

import com.unciv.Constants;
import com.unciv.logic.GameInfo;
import com.unciv.logic.civilization.Civilization;
import com.unciv.logic.civilization.diplomacy.DiplomacyFunctions;
import com.unciv.logic.civilization.diplomacy.DiplomacyManager;
import com.unciv.logic.city.City;
import com.unciv.logic.city.CityConstructions;
import com.unciv.logic.map.tile.Tile;
import com.unciv.logic.map.mapunit.MapUnit;
import com.unciv.logic.map.mapunit.movement.UnitMovement;
import com.unciv.logic.map.HexCoord;
import com.unciv.models.ruleset.Ruleset;
import com.unciv.models.ruleset.INonPerpetualConstruction;
import com.unciv.models.ruleset.tile.TileImprovement;
import com.unciv.models.stats.Stat;
import com.unciv.logic.battle.Battle;
import com.unciv.logic.battle.MapUnitCombatant;
import com.unciv.logic.battle.CityCombatant;
import com.unciv.logic.battle.ICombatant;
import com.unciv.logic.battle.AttackableTile;
import com.unciv.logic.battle.TargetHelper;

import java.util.List;
import java.util.Map;

public final class FsOpHandler {

    public static final java.util.concurrent.atomic.AtomicLong APPLIED = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong SKIPPED = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong FAILED  = new java.util.concurrent.atomic.AtomicLong();

    private FsOpHandler() {}

    public static <T> java.util.ArrayList<T> toList(kotlin.sequences.Sequence<T> seq) {
        java.util.ArrayList<T> out = new java.util.ArrayList<>();
        if (seq == null) return out;
        for (java.util.Iterator<T> it = seq.iterator(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    /** 刷新所有存活文明（含城邦）的视野缓存。op 应用后统一调用（见 applyAndDiff 内注释）。
     *  顺序对齐官方 GameInfo.updateCivilizationState：先逐单位 updateVisibleTiles(false)
     *  填 unit.viewableTiles，再 updateOurTiles()（ourTilesAndNeighboringTiles 是视野基础集），
     *  最后 updateSightAndResources()。跳过单位级刷新会导致 setNewViewableTiles 用旧的
     *  unit.viewableTiles —— 表现为"玩家看得见的目标服务端判定不可见"，攻击候选被滤掉。 */
    public static void refreshAllVision(GameInfo gi) {
        try {
            for (Civilization civ0 : gi.getCivilizations()) {
                if (!civ0.isAlive()) continue;
                try {
                    for (java.util.Iterator<MapUnit> it = civ0.getUnits().getCivUnits().iterator(); it.hasNext(); ) {
                        MapUnit u0 = it.next();
                        try { u0.updateVisibleTiles(false, null); } catch (Throwable ignored) {}
                    }
                    try { civ0.getCache().updateOurTiles(); } catch (Throwable ignored) {}
                    try { civ0.getCache().updateSightAndResources(); } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean apply(GameInfo gi, String playerId, String op, Map<String, Object> data) {
        if (gi == null || op == null) { SKIPPED.incrementAndGet(); return false; }
        try {
            boolean ok = dispatch(gi, playerId, op, data == null ? java.util.Collections.emptyMap() : data);
            if (ok) APPLIED.incrementAndGet(); else SKIPPED.incrementAndGet();
            return ok;
        } catch (Throwable t) {
            FAILED.incrementAndGet();
            System.err.println("[op] FAIL op=" + op + " player=" + playerId + " : " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    // ====================================================================
    // applyAndDiff: same as apply() but returns an FsOpResult populated with
    // the actual set of units / cities / civs whose state changed during the
    // op. The server uses this to construct a `sim` delta message so the
    // remote client sees the effect WITHOUT waiting for nextTurn.
    //
    // Mechanism: we take two snapshots (before + after) of every alive
    // unit and city in GameInfo, hashed on a stable field set. Anything
    // whose hash changed (or that disappeared or appeared) goes into the
    // affected/removed sets. Civs are added based on the data fields of
    // the op (otherCivId, toCivId, requestingCiv, ...) plus the issuing
    // player.
    // ====================================================================

    public static FsOpResult applyAndDiff(GameInfo gi, String playerId, String op, Map<String, Object> data) {
        FsOpResult r = new FsOpResult().reset(op, playerId);
        if (gi == null || op == null) return r.markFail("null gi/op");

        // Civ-level heuristic from the op payload (works for diplomacy, tech, religion, ...).
        if (data != null) {
            // 注意：toCiv / fromCiv 是交易 op 用的目标文明字段，必须在这里登记，
            // 否则 diff 不会把该文明算作"受影响"，sim 里就不会带它的 tradeRequests，
            // 交易请求也就送不到对方屏幕上。
            String[] civFields = {"otherCivId", "civ", "civId", "targetCivId", "toCivId", "toCiv",
                "to", "requestingCiv", "fromCiv", "chosenCiv"};
            for (String k : civFields) {
                Object v = data.get(k);
                if (v != null) r.civAffected(v.toString());
            }
            // Unit / city payloads
            Object u = data.get("unitId");
            if (u != null) {
                try { r.unitAffected(Integer.parseInt(u.toString())); } catch (Throwable ignore) {}
            }
            Object c = data.get("cityId");
            if (c != null) r.cityAffected(c.toString());
        }
        // Issuing player civ is always affected.
        Civilization self = findCivByPlayerId(gi, playerId);
        if (self != null) r.civAffected(self.getCivID());

        // Hash snapshot before.
        java.util.Map<Integer, String> uBefore = new java.util.HashMap<>();
        java.util.Map<String,  String> cBefore = new java.util.HashMap<>();
        java.util.Map<Integer, String> uBeforePos = new java.util.HashMap<>();
        for (Civilization civ0 : gi.getCivilizations()) {
            for (java.util.Iterator<MapUnit> it = civ0.getUnits().getCivUnits().iterator(); it.hasNext(); ) {
                MapUnit u0 = it.next();
                uBefore.put(u0.getId(), unitHash(u0));
                String p0 = posOf(u0);
                if (p0 != null) uBeforePos.put(u0.getId(), p0);
            }
            for (Object cityObj : civ0.getCities()) {
                City city0 = (City) cityObj;
                cBefore.put(city0.getId(), cityHash(city0));
            }
        }

        boolean ok;
        setCurrentResult(r);
        try {
            ok = apply(gi, playerId, op, data);
        } finally {
            setCurrentResult(null);
        }
        r.ok = ok;
        // UncivSync: op 可能移动/新增/摧毁单位与城市 —— 所有文明的 viewableTiles 缓存
        // 必须刷新，否则攻击候选生成里 `tile !in unit.civ.viewableTiles` 会用**过期视野**
        // 把玩家明明看得见的目标（敌方城市/单位）滤掉，表现为"远程单位打不了城/打不了人"。
        // 官方客户端每次本地移动都会 updateVisibleTiles()；服务端此前从没刷过（只有测试文件刷），
        // 这是"之前版本能打、新版本打不了"的根因。放在 applyAndDiff 统一出口：任何 op 都不漏。
        refreshAllVision(gi);
        // 注意：失败也不能提前返回。apply() 中途抛异常被捕获返回 false 时，状态可能
        // 已经被部分改动（典型：unit.attack 先移动了单位、攻击结算才失败）—— 必须照常
        // 做后置 diff 把变更标记出来，管线据此回推 sim，否则权威状态与客户端静默分叉，
        // 直到下回合整档才对齐。干净失败（没改任何东西）的 diff 本来就是空的，无副作用。

        // Hash snapshot after, diff.
        java.util.Map<Integer, String> uAfter = new java.util.HashMap<>();
        java.util.Map<String,  String> cAfter = new java.util.HashMap<>();
        java.util.Set<Integer> uSeen = new java.util.HashSet<>();
        java.util.Set<String>  cSeen = new java.util.HashSet<>();
        for (Civilization civ0 : gi.getCivilizations()) {
            for (java.util.Iterator<MapUnit> it = civ0.getUnits().getCivUnits().iterator(); it.hasNext(); ) {
                MapUnit u0 = it.next();
                uAfter.put(u0.getId(), unitHash(u0));
                uSeen.add(u0.getId());
            }
            for (Object cityObj : civ0.getCities()) {
                City city0 = (City) cityObj;
                cAfter.put(city0.getId(), cityHash(city0));
                cSeen.add(city0.getId());
            }
        }
        // Changed or new units.
        boolean stateChanged = false;
        for (java.util.Map.Entry<Integer, String> e : uAfter.entrySet()) {
            String prev = uBefore.get(e.getKey());
            if (prev == null || !prev.equals(e.getValue())) { r.unitAffected(e.getKey()); stateChanged = true; }
        }
        // Removed units.
        for (Integer id : uBefore.keySet()) {
            if (!uSeen.contains(id)) { r.unitRemoved(id); stateChanged = true; }
        }
        // Changed or new cities.
        for (java.util.Map.Entry<String, String> e : cAfter.entrySet()) {
            String prev = cBefore.get(e.getKey());
            if (prev == null || !prev.equals(e.getValue())) { r.cityAffected(e.getKey()); stateChanged = true; }
        }
        // Removed cities.
        for (String id : cBefore.keySet()) {
            if (!cSeen.contains(id)) { r.cityRemoved(id); stateChanged = true; }
        }
        // 记录每个受影响单位"变化前"的位置，供 sim 携带 fromX/fromY（客户端按旧位置定位）。
        for (Integer id : r.affectedUnitIds) {
            String prev = uBeforePos.get(id);
            if (prev != null) r.unitPrevPos.put(id, prev);
        }
        // 文明级变更没有哈希对比（金/科技/外交…由各 op 手工标记），用"除发起者外
        // 还有别的文明被标记"作为代理：单标记=恒有的发起者噪音，多标记=确有跨文明改动。
        r.mutated = stateChanged || r.affectedCivIds.size() > 1;
        return r;
    }

    /** "x,y" of a unit's current tile, or null. */
    private static String posOf(MapUnit u) {
        try {
            Tile t = u.getTile();
            if (t == null) return null;
            return t.getPosition().getX() + "," + t.getPosition().getY();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Compact content-hash for a unit — used purely to detect changes. */
    private static String unitHash(MapUnit u) {
        StringBuilder sb = new StringBuilder();
        sb.append(u.getCiv().getCivID()).append('|');
        sb.append(u.getBaseUnit().getName()).append('|');
        Tile t = u.getTile();
        if (t != null) sb.append(t.getPosition().getX()).append(',').append(t.getPosition().getY());
        sb.append('|').append(u.getMovement());
        sb.append('|').append(u.getHealth());
        sb.append('|').append(u.getAction());
        sb.append('|').append(u.getName());
        sb.append('|').append(u.getPromotions().getNumberOfPromotions());
        sb.append('|').append(u.getPromotions().getXP());
        sb.append('|').append(u.getPromotions().getPromotions());
        sb.append('|').append(u.getInstanceName());
        // 攻击次数：客户端用它判断"这回合还能不能再打"，必须纳入变化检测，
        // 否则攻击后若位置/血量恰好没变（例如某些不扣移动力的攻击）就不会同步。
        try { sb.append('|').append(u.getAttacksThisTurn()); } catch (Throwable ignore) {}
        return sb.toString();
    }

    /** Compact content-hash for a city — used purely to detect changes. */
    private static String cityHash(City c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.getCiv().getCivID()).append('|');
        sb.append(c.getName()).append('|');
        sb.append(c.getPopulation().getPopulation()).append('|');
        sb.append(c.getCityConstructions().getCurrentConstruction()).append('|');
        sb.append(c.getHealth()).append('|');
        sb.append(new java.util.TreeSet<>(c.getDisabledConstructions())).append('|');
        // 轰击会改 attackedThisTurn（而它不在上面任何一项里）—— 不算进来的话
        // 城市轰炸之后城市不会进 diff，客户端拿不到"本回合已轰击"标记，按钮就一直可点。
        try { sb.append(c.getAttackedThisTurn()); } catch (Throwable ignore) {}
        return sb.toString();
    }

    // ====================================================================
    // Dispatch — one case per client-emitted op (70 total).
    // ====================================================================

    private static boolean dispatch(GameInfo gi, String playerId, String op, Map<String, Object> data) {
        Civilization civ = findCivByPlayerId(gi, playerId);

        // 查表分发（单一真相源见 FsOpTable）。以前这里是一个 87 分支的 switch：
        // 能力集合不可枚举，"客户端发了一个服务端没实现的 op"只能等玩家踩到才发现。
        FsOpTable.Entry entry = FsOpTable.get(op);
        if (entry == null) {
            // 未知 op：显式记录（OpsCoverage / metrics 会读），不再悄悄吞掉。
            System.out.println("[op] unknown op '" + op + "' from " + playerId + " — 服务端未实现，已记录");
            FsOpTable.recordUnknown(op);
            return false;
        }
        // 第二道门：不能信任客户端门面，所有城市操作只能作用于发起者自己的城市。
        if (civ == null) return false;
        if (entry.target == FsOpTable.Target.CITY
                && findCityById(civ, asStr(data.get("cityId"))) == null) return false;
        return entry.body.run(civ, gi, data);
    }

    // ====================================================================
    // op 注册表内容 —— 从旧的 switch 逐行搬过来，处理体一字未改。
    //
    // 每个 op 一行：op 名 → 作用目标 → 处理体。
    // FsOpTable.ops() 就是服务端的能力集合，OpsCoverage 拿它和客户端 jar 里的
    // op 字面量比对；新增动作只需要在这里加一行。
    // ====================================================================

    static {
        // ===== unit.* =====
        FsOpTable.register("unit.move", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitMove(civ, gi, data));
        FsOpTable.register("unit.swap", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitSwap(civ, gi, data));
        FsOpTable.register("unit.attack", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitAttack(civ, gi, data));
        FsOpTable.register("unit.fortify", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitSetAction(civ, data, "Fortify"));
        FsOpTable.register("unit.fortifyUntilHealed", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitSetAction(civ, data, "Fortify until healed"));
        FsOpTable.register("unit.sleep", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitSetAction(civ, data, "Sleep"));
        FsOpTable.register("unit.sleepUntilHealed", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitSetAction(civ, data, "Sleep until healed"));
        FsOpTable.register("unit.explore", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitExplore(civ, data));
        FsOpTable.register("unit.automate", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitAutomate(civ, data));
        FsOpTable.register("unit.stopAutomation", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitStopAutomation(civ, data));
        FsOpTable.register("unit.disband", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitDisband(civ, data));
        FsOpTable.register("unit.foundCity", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitFoundCity(civ, gi, data));
        FsOpTable.register("unit.createImprovement", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitCreateImprovement(civ, gi, data));
        FsOpTable.register("unit.buildImprovement", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitBuildImprovement(civ, gi, data));
        FsOpTable.register("unit.stopImprovement", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitStopImprovement(civ, data));
        FsOpTable.register("unit.enhanceReligion", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitReligion(civ, "enhanceReligion", data));
        FsOpTable.register("unit.foundReligion", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitReligion(civ, "foundReligion", data));
        FsOpTable.register("unit.spreadReligion", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitSpreadReligion(civ, data));
        FsOpTable.register("unit.hurryResearch", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitHurry(civ, gi, "research", data));
        FsOpTable.register("unit.hurryPolicy", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitHurry(civ, gi, "policy", data));
        FsOpTable.register("unit.hurryWonder", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitHurry(civ, gi, "wonder", data));
        FsOpTable.register("unit.hurryBuilding", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitHurry(civ, gi, "building", data));
        FsOpTable.register("unit.tradeMission", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitTradeMission(civ, data));
        FsOpTable.register("unit.setUp", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitSetUp(civ, data));
        FsOpTable.register("unit.guard", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitGuard(civ, data));
        FsOpTable.register("unit.repair", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitRepair(civ, data));
        FsOpTable.register("unit.rename", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitRename(civ, data));
        FsOpTable.register("unit.transform", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitTransform(civ, gi, data));
        FsOpTable.register("unit.promote", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitPromote(civ, data));
        FsOpTable.register("unit.upgrade", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitUpgrade(civ, data));
        FsOpTable.register("unit.gift", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitGift(civ, gi, data));

        // ===== 第二批：其余原版单位动作（对齐核心的 UnitAction 工厂）=====
        // pillage / removeHeresy / triggerUnique 直接执行核心的 lambda（见 FsCoreActions）；
        // 其余是"设 action / 设 due / 设护卫"的一行公开 API。
        FsOpTable.register("unit.pillage", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitPillage(civ, data));
        FsOpTable.register("unit.paradrop", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitCoreAction(civ, data, "getParadropActions", "unit.paradrop"));
        FsOpTable.register("unit.airSweep", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitCoreAction(civ, data, "getAirSweepActions", "unit.airSweep"));
        FsOpTable.register("unit.addInCapital", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitCoreAction(civ, data, "getAddInCapitalActions", "unit.addInCapital"));
        FsOpTable.register("unit.removeHeresy", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitRemoveHeresy(civ, data));
        FsOpTable.register("unit.triggerUnique", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitTriggerUnique(civ, data));
        FsOpTable.register("unit.escort", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitEscort(civ, data, true));
        FsOpTable.register("unit.stopEscort", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitEscort(civ, data, false));
        FsOpTable.register("unit.stopMovement", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitStopMovement(civ, data));
        FsOpTable.register("unit.connectRoad", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitConnectRoad(civ, gi, data));
        FsOpTable.register("unit.skip", FsOpTable.Target.UNIT, (civ, gi, data) -> opUnitSkip(civ, data));

        // ===== city.* =====
        FsOpTable.register("city.addToQueue", FsOpTable.Target.CITY, (civ, gi, data) -> opCityAddToQueue(civ, data, false));
        FsOpTable.register("city.addToQueueWithTile", FsOpTable.Target.CITY, (civ, gi, data) -> opCityAddToQueueWithTile(civ, gi, data));
        FsOpTable.register("city.annex", FsOpTable.Target.CITY, (civ, gi, data) -> opCityAnnex(civ, data));
        FsOpTable.register("city.assignSpecialist", FsOpTable.Target.CITY, (civ, gi, data) -> opCityAssignSpecialist(civ, data));
        FsOpTable.register("city.buyTile", FsOpTable.Target.CITY, (civ, gi, data) -> opCityBuyTile(civ, gi, data));
        FsOpTable.register("city.buy", FsOpTable.Target.CITY, (civ, gi, data) -> opCityBuy(civ, gi, data));
        FsOpTable.register("city.setPuppet", FsOpTable.Target.CITY, (civ, gi, data) -> opCitySetPuppet(civ, gi, data));
        FsOpTable.register("city.disableConstruction", FsOpTable.Target.CITY, (civ, gi, data) -> opCityDisableConstruction(civ, data));
        FsOpTable.register("city.disableManualSpecialists", FsOpTable.Target.CITY, (civ, gi, data) -> opCityDisableManualSpecialists(civ, data));
        FsOpTable.register("city.enableManualSpecialists", FsOpTable.Target.CITY, (civ, gi, data) -> opCityEnableManualSpecialists(civ, data));
        FsOpTable.register("city.lockTile", FsOpTable.Target.CITY, (civ, gi, data) -> opCityLockTile(civ, gi, data, true));
        FsOpTable.register("city.unlockTile", FsOpTable.Target.CITY, (civ, gi, data) -> opCityLockTile(civ, gi, data, false));
        FsOpTable.register("city.lowerPriority", FsOpTable.Target.CITY, (civ, gi, data) -> opCityChangePriority(civ, data, -1));
        FsOpTable.register("city.raisePriority", FsOpTable.Target.CITY, (civ, gi, data) -> opCityChangePriority(civ, data, +1));
        FsOpTable.register("city.moveEntryToEnd", FsOpTable.Target.CITY, (civ, gi, data) -> opCityQueueMoveEnd(civ, data));
        FsOpTable.register("city.moveQueueEntry", FsOpTable.Target.CITY, (civ, gi, data) -> opCityQueueMoveEntry(civ, data));
        FsOpTable.register("city.reassignPopulation", FsOpTable.Target.CITY, (civ, gi, data) -> opCityReassignPopulation(civ, data));
        FsOpTable.register("city.removeFromQueue", FsOpTable.Target.CITY, (civ, gi, data) -> opCityRemoveFromQueueByIndex(civ, data));
        FsOpTable.register("city.removeFromQueueByName", FsOpTable.Target.CITY, (civ, gi, data) -> opCityRemoveFromQueueByName(civ, data));
        FsOpTable.register("city.rename", FsOpTable.Target.CITY, (civ, gi, data) -> opCityRename(civ, data));
        FsOpTable.register("city.saveUnitPromotions", FsOpTable.Target.CITY, (civ, gi, data) -> opCitySaveUnitPromotions(civ, data));
        FsOpTable.register("city.sellBuilding", FsOpTable.Target.CITY, (civ, gi, data) -> opCitySellBuilding(civ, data));
        FsOpTable.register("city.setFocus", FsOpTable.Target.CITY, (civ, gi, data) -> opCitySetFocus(civ, data));
        FsOpTable.register("city.setProduction", FsOpTable.Target.CITY, (civ, gi, data) -> opCitySetProduction(civ, data));
        FsOpTable.register("city.setRazing", FsOpTable.Target.CITY, (civ, gi, data) -> opCitySetRazing(civ, data));
        FsOpTable.register("city.setUnitSavedPromotion", FsOpTable.Target.CITY, (civ, gi, data) -> opCitySetUnitSavedPromotion(civ, data));
        FsOpTable.register("city.stopWorkTile", FsOpTable.Target.CITY, (civ, gi, data) -> opCityStopWorkTile(civ, gi, data));
        FsOpTable.register("city.toggleAvoidGrowth", FsOpTable.Target.CITY, (civ, gi, data) -> opCityToggleAvoidGrowth(civ, data));
        FsOpTable.register("city.workTile", FsOpTable.Target.CITY, (civ, gi, data) -> opCityWorkTile(civ, gi, data));

        // ===== civ.* =====
        FsOpTable.register("civ.chooseBeliefs", FsOpTable.Target.CIV, (civ, gi, data) -> FsReligionActions.choose(civ, data));
        FsOpTable.register("civ.foundReligion", FsOpTable.Target.CIV, (civ, gi, data) -> FsReligionActions.choose(civ, data));
        FsOpTable.register("civ.choosePolicy", FsOpTable.Target.CIV, (civ, gi, data) -> opCivChoosePolicy(civ, data));
        FsOpTable.register("civ.chooseTech", FsOpTable.Target.CIV, (civ, gi, data) -> opCivChooseTech(civ, data));
        FsOpTable.register("civ.cityConquerChoice", FsOpTable.Target.CIV, (civ, gi, data) -> opCivCityConquerChoice(civ, gi, data));
        // 政策选择器关闭：官方 CivView.tryDismissPolicyPicker 只改本地；不同步回服务端的话，
        // 服务端权威值仍为 true，下一条 sim 又把它写回 true —— 弹窗"每操作一次弹一次"。
        FsOpTable.register("civ.policyPickerDismissed", FsOpTable.Target.CIV, (civ, gi, data) -> {
            if (civ == null) return false;
            civ.getPolicies().setShouldOpenPolicyPicker(false);
            return true;
        });
        FsOpTable.register("civ.declareWar", FsOpTable.Target.CIV, (civ, gi, data) -> opCivDeclareWar(civ, gi, data));
        FsOpTable.register("civ.demand", FsOpTable.Target.CIV, (civ, gi, data) -> opCivDemand(civ, gi, data));
        FsOpTable.register("civ.demandAccept", FsOpTable.Target.CIV, (civ, gi, data) -> opCivDemandAccept(civ, gi, data));
        FsOpTable.register("civ.demandRefuse", FsOpTable.Target.CIV, (civ, gi, data) -> opCivDemandRefuse(civ, gi, data));
        FsOpTable.register("civ.denounce", FsOpTable.Target.CIV, (civ, gi, data) -> opCivDenounce(civ, gi, data));
        FsOpTable.register("civ.diplomaticVote", FsOpTable.Target.CIV, (civ, gi, data) -> opCivDiplomaticVote(civ, gi, data));
        FsOpTable.register("civ.alertSeen", FsOpTable.Target.CIV, (civ, gi, data) -> opCivAlertSeen(civ, data));
        FsOpTable.register("civ.friendshipAccept", FsOpTable.Target.CIV, (civ, gi, data) -> opCivFriendship(civ, gi, "accept", data));
        FsOpTable.register("civ.friendshipDecline", FsOpTable.Target.CIV, (civ, gi, data) -> opCivFriendship(civ, gi, "decline", data));
        FsOpTable.register("civ.friendshipOffer", FsOpTable.Target.CIV, (civ, gi, data) -> opCivFriendship(civ, gi, "offer", data));
        FsOpTable.register("civ.makePeace", FsOpTable.Target.CIV, (civ, gi, data) -> opCivMakePeace(civ, gi, data));
        FsOpTable.register("civ.meet", FsOpTable.Target.CIV, (civ, gi, data) -> opCivMeet(civ, gi, data));
        FsOpTable.register("civ.setConstructionDisabled", FsOpTable.Target.CIV, (civ, gi, data) -> opCivSetConstructionDisabled(civ, data));
        FsOpTable.register("civ.setGoldToScience", FsOpTable.Target.CIV, (civ, gi, data) -> {
            Object value = data.get("value");
            if (!(value instanceof Number)) return false;
            float ratio = ((Number) value).floatValue();
            if (!Float.isFinite(ratio) || ratio < 0 || ratio > 1) return false;
            if (gi.getRuleset().getModOptions().getUniqueObjects().stream().noneMatch(
                    u -> u.getType() == com.unciv.models.ruleset.unique.UniqueType.ConvertGoldToScience)) return false;
            civ.getTech().setGoldPercentConvertedToScience(ratio);
            for (City city : civ.getCities()) city.getCityStats().update(city.getCityConstructions().getCurrentConstruction(), true, true, true);
            return true;
        });
        FsOpTable.register("civ.runAutomation", FsOpTable.Target.CIV, (civ, gi, data) -> {
            if (civ.getHasMovedAutomatedUnits()) return false;
            civ.setHasMovedAutomatedUnits(true);
            // 复制单位列表，核心动作可能消耗单位。
            for (MapUnit unit : toList(civ.getUnits().getCivUnits())) unit.doAction();
            return true;
        });
        FsOpTable.register("civ.setCityStateProtection", FsOpTable.Target.CIV, (civ, gi, data) -> {
            Civilization cityState = FsTargeting.findCivByNameOrId(gi, asStr(data.get("cityState")));
            Boolean protect = asBool(data.get("protect"));
            if (cityState == null || !cityState.isCityState() || protect == null) return false;
            var functions = cityState.getCityStateFunctions();
            if (protect) {
                if (!functions.otherCivCanPledgeProtection(civ)) return false;
                functions.addProtectorCiv(civ);
            } else {
                if (!functions.otherCivCanWithdrawProtection(civ)) return false;
                functions.removeProtectorCiv(civ, false);
            }
            return true;
        });
        FsOpTable.register("civ.cityStatePeace", FsOpTable.Target.CIV, (civ, gi, data) -> {
            Civilization cs = FsTargeting.findCivByNameOrId(gi, asStr(data.get("cityState")));
            if (cs == null || !cs.isCityState() || !civ.isMajorCiv() || !cs.isAtWarWith(civ)) return false;
            var diplomacy = cs.getDiplomacyManager(civ);
            if (diplomacy == null || diplomacy.hasFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.DeclaredWar)) return false;
            if (cs.getAllyCiv() != null && cs.getAllyCiv().isAtWarWith(civ)) return false;
            var trade = new com.unciv.logic.trade.TradeLogic(civ, cs);
            trade.getCurrentTrade().getOurOffers().add(new com.unciv.logic.trade.TradeOffer(
                    "Peace Treaty", com.unciv.logic.trade.TradeOfferType.Treaty, 1, gi.getSpeed()));
            trade.getCurrentTrade().getTheirOffers().add(new com.unciv.logic.trade.TradeOffer(
                    "Peace Treaty", com.unciv.logic.trade.TradeOfferType.Treaty, 1, gi.getSpeed()));
            trade.acceptTrade(true);
            return true;
        });
        FsOpTable.register("civ.tribute", FsOpTable.Target.CIV, (civ, gi, data) -> opCivTribute(civ, gi, data));
        // 城邦外交三件套 + 事件选择（对齐核心 CityStateDiplomacyTable / EventChoice）
        FsOpTable.register("civ.giftGold", FsOpTable.Target.CIV, (civ, gi, data) -> opCivGiftGold(civ, gi, data));
        FsOpTable.register("civ.diplomaticMarriage", FsOpTable.Target.CIV, (civ, gi, data) -> opCivDiplomaticMarriage(civ, gi, data));
        FsOpTable.register("civ.cityStateImproveGift", FsOpTable.Target.CIV, (civ, gi, data) -> opCivCityStateImproveGift(civ, gi, data));
        FsOpTable.register("civ.eventChoice", FsOpTable.Target.CIV, (civ, gi, data) -> opCivEventChoice(civ, gi, data));
        // 免费伟人（伟人选择屏）
        FsOpTable.register("civ.chooseFreeGreatPerson", FsOpTable.Target.CIV, (civ, gi, data) -> opCivChooseFreeGreatPerson(civ, data));

        // ===== spy.* =====
        FsOpTable.register("spy.move", FsOpTable.Target.CIV, (civ, gi, data) -> opSpyMove(civ, gi, data));
        FsOpTable.register("spy.setAction", FsOpTable.Target.CIV, (civ, gi, data) -> opSpySetAction(civ, data));
        FsOpTable.register("civ.choosePantheon", FsOpTable.Target.CIV, (civ, gi, data) -> FsReligionActions.choose(civ, data));

        // ===== trade.* =====
        FsOpTable.register("trade.accept", FsOpTable.Target.CIV, (civ, gi, data) -> opTradeAccept(civ, gi, data));
        FsOpTable.register("trade.offer", FsOpTable.Target.CIV, (civ, gi, data) -> opTradeOffer(civ, data));
        FsOpTable.register("trade.reject", FsOpTable.Target.CIV, (civ, gi, data) -> opTradeReject(civ, gi, data));
        FsOpTable.register("trade.retract", FsOpTable.Target.CIV, (civ, gi, data) -> opTradeRetract(civ, gi, data));
        FsOpTable.register("trade.counter", FsOpTable.Target.CIV, (civ, gi, data) -> opTradeCounter(civ, data));

        // 注：原版 Unciv 没有"贸易路线"玩法（tradeRoute.* 是 UncivGC 这个 fork 自己加的）。
        // 按"只做原版"的要求，这四个 op 已整体移除 —— 留着就是永远没人发的死代码。
        System.out.print(FsOpTable.selfCheck());
    }

    // ====================================================================
    // Finders / cast
    // ====================================================================

    public static Civilization findCivByPlayerId(GameInfo gi, String playerId) {
        return FsTargeting.findCivByPlayerId(gi, playerId);
    }

    public static MapUnit findUnitById(GameInfo gi, int unitId) {
        return FsTargeting.findUnitById(gi, unitId);
    }

    public static MapUnit findUnitById(Civilization civ, int unitId) {
        return FsTargeting.findUnitById(civ, unitId);
    }

    /**
     * Stable, cross-client unit lookup. The client's local integer unit ids depend on each
     * client's own GameStarter rng and therefore do NOT match the server's authoritative ids
     * when two clients each start their own game. So we locate the unit by its stable identity
     * — owning civilization (civ name) + current tile (fromX/fromY) + base unit type — and only
     * fall back to the raw integer id when no location identity was supplied.
     *
     * This is the systemic fix for the "non-host foundCity targets the wrong unit" class of bug:
     * every op that acts on a unit goes through this locator, so it is immune to id drift.
     */
    public static MapUnit findUnitByLoc(Civilization civ, Map<String, Object> data) {
        // 统一寻址实现迁移到 FsTargeting.resolveUnit（阶段 3 · 层 2）：
        //   优先级 = 位置(fromX/fromY)+类型 → unitId 主键（必须属于本人文明）→ 仅类型。
        // 这里保留方法名作为兼容入口（18 处 unit op 直接调用）。
        return FsTargeting.resolveUnit(civ, data);
    }

    public static City findCityById(GameInfo gi, String cityId) {
        return FsTargeting.findCityById(gi, cityId);
    }

    public static City findCityById(Civilization civ, String cityId) {
        if (civ == null || cityId == null) return null;
        for (City city : civ.getCities()) {
            if (cityId.equals(city.getId())) return city;
        }
        return null;
    }

    public static Civilization findCivByCivId(GameInfo gi, String civId) {
        if (gi == null || civId == null) return null;
        for (Civilization c : gi.getCivilizations()) {
            if (civId.equals(c.getCivID())) return c;
        }
        return null;
    }

    public static Tile resolveTile(GameInfo gi, Map<String, Object> data) {
        return resolveTile(gi, data, "tileX", "tileY");
    }

    /** WorldMapHolder.moveUnitToTargetTile uses (toX,toY) and (destX,destY). */
    public static Tile resolveMoveTile(GameInfo gi, Map<String, Object> data) {
        Tile t = resolveTile(gi, data, "toX", "toY");
        if (t != null) return t;
        return resolveTile(gi, data, "destX", "destY");
    }

    public static Tile resolveTile(GameInfo gi, Map<String, Object> data, String xKey, String yKey) {
        if (gi == null || gi.getTileMap() == null) return null;
        Integer x = asInt(data.get(xKey));
        Integer y = asInt(data.get(yKey));
        if (x == null || y == null) return null;
        return gi.getTileMap().get(x, y);
    }

    public static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try { return Integer.parseInt((String) o); } catch (Exception e) { return null; }
        }
        return null;
    }

    public static Boolean asBool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof String) return Boolean.parseBoolean((String) o);
        return null;
    }

    public static String asStr(Object o) { return o == null ? null : o.toString(); }

    public static String[] asCsv(Object o) {
        if (o == null) return new String[0];
        if (o instanceof List) {
            java.util.List<?> l = (List<?>) o;
            String[] out = new String[l.size()];
            for (int i = 0; i < l.size(); i++) out[i] = l.get(i) == null ? "" : l.get(i).toString();
            return out;
        }
        if (o instanceof String) {
            String s = (String) o;
            if (s.isEmpty()) return new String[0];
            return s.split(",");
        }
        return new String[] { o.toString() };
    }

    // ====================================================================
    // unit.* ops
    // ====================================================================

    private static boolean opUnitMove(Civilization civ, GameInfo gi, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        Tile target = resolveMoveTile(gi, data);
        if (target == null) return false;
        // Authority check: the destination occupancy follows the core's civilian/military
        // co-location rules (UnitMovement.getCannotMoveToReason): a CIVILIAN unit (worker /
        // settler / great person) may share a tile with an OWN military unit, and a military
        // unit may enter a tile with an enemy civilian (capture). The original hand-rolled
        // "any other unit on target -> reject" loop was stricter than the core and rejected
        // these legal moves. We still check explicitly (not silently swallowing moveToTile's
        // exception) so an occupancy rejection is reported cleanly.
        try {
            UnitMovement.CannotMoveToReason reason =
                    u.getMovement().getCannotMoveToReason(target, false, false, true);
            if (reason == UnitMovement.CannotMoveToReason.TileIsNotEmpty) {
                return false; // genuinely no room under core rules — reject this move
            }
        } catch (Throwable ignore) {
            // 判定失败不拦，交给下面 moveToTile 的全套校验兜底
        }
        try {
            // moveToTile enforces movement cost + reachability + ZOC internally and
            // throws on failure; catch below turns that into a rejected op.
            u.getMovement().moveToTile(target, true /* considerZoneOfControl */);

            // UncivSync: 多回合行军。
            // 客户端上报两个坐标（与 UncivGC 同构）：
            //   toX/toY   = 本回合实际能走到的那格（客户端用 getTileToMoveToThisTurn 算好）
            //   destX/destY = 玩家点的最终目的地
            // 两者不同时，把最终目的地写进单位 action —— 服务端每回合 startTurn 里
            // unit.doAction() 会自动继续走，不需要客户端再发 op 也不需要任何本地预测。
            Integer destX = asInt(data.get("destX"));
            Integer destY = asInt(data.get("destY"));
            if (destX != null && destY != null) {
                boolean arrived = destX == target.getPosition().getX() && destY == target.getPosition().getY();
                String cur = u.getAction();
                if (!arrived) {
                    u.setAction("moveTo " + destX + "," + destY);
                } else if (cur != null && cur.startsWith("moveTo ")) {
                    u.setAction(null);
                }
            }
            return true;
        } catch (Throwable t) {
            System.err.println("[op] unit.move failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean opUnitSwap(Civilization civ, GameInfo gi, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        Tile target = resolveMoveTile(gi, data);
        if (target == null) return false;
        // 核心 swapMoveToTile 在"目的地没有可换的同类型单位"时**静默 return**
        // （与宗教 foundReligion 同一批静默陷阱）。这里先做与核心一致的存在性校验，
        // 换位后再确认位置真的变了 —— 失败显式拒绝并记日志，不再假成功。
        var targetUnit = u.isCivilian() ? target.getCivilianUnit() : target.getMilitaryUnit();
        if (targetUnit == null || targetUnit.getOwner() == u.getOwner()) {
            System.out.println("[op] unit.swap rejected: no swappable unit at "
                    + target.getPosition().getX() + "," + target.getPosition().getY());
            return false;
        }
        try {
            var from = u.getTile();
            u.getMovement().swapMoveToTile(target, true);
            if (u.getTile() != target) {
                System.out.println("[op] unit.swap noop: unit did not move to " + target.getPosition().getX()
                        + "," + target.getPosition().getY());
                return false;
            }
            return true;
        } catch (Throwable t) {
            System.err.println("[op] unit.swap failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean opUnitAttack(Civilization civ, GameInfo gi, Map<String, Object> data) {
        // 战斗由服务端**唯一结算**：客户端不再本地掷骰。
        // 原因：Unciv 的伤害来自 GameContext.stateBasedRandom()，种子是对象身份 hashCode 的组合，
        // 同一份数据在不同进程里算出的伤害必然不同 —— 客户端预演永远对不上，只能服务端算完再广播。
        if (civ == null || gi == null) return rejectCurrent("invalid_actor");
        String cityId = asStr(data.get("cityId"));
        MapUnit attackerUnit = cityId == null ? findUnitByLoc(civ, data) : null;
        City attackerCity = null;
        if (cityId != null) {
            attackerCity = findCityAny(gi, civ, data);
            if (attackerCity == null) return rejectCurrent("attacker_not_found");
        } else if (attackerUnit == null) {
            return rejectCurrent("attacker_not_found");
        }

        try {
            Tile target = resolveMoveTile(gi, data);
            if (target == null) return rejectCurrent("target_not_found");
            Integer targetUnitId = asInt(data.get("targetUnitId"));
            String targetCityId = asStr(data.get("targetCityId"));

            // 城市轰击：目标身份由客户端选择，服务端只用核心敌对判定和城市射程确认。
            if (attackerCity != null) {
                if (!attackerCity.canBombard()) return rejectCurrent("core_cannot_attack");
                ICombatant attackerC = new CityCombatant(attackerCity);
                ICombatant defenderC = resolveRequestedDefender(gi, target, targetUnitId, targetCityId);
                if (defenderC == null) return rejectCurrent("stale_target");
                Tile realTarget = defenderC.getTile();
                if (realTarget == null
                        || !TargetHelper.INSTANCE.containsAttackableEnemy(realTarget, attackerC)
                        || attackerCity.getCenterTile().aerialDistanceTo(realTarget) > attackerCity.getBombardRange())
                    return rejectCurrent("core_target_not_attackable");
                AttackableTile attackable = new AttackableTile(
                    attackerCity.getCenterTile(), realTarget, 0f, defenderC);
                Battle.DamageDealt dd = Battle.INSTANCE.attackOrNuke(attackerC, attackable);
                recordBattleResult(attackerC, defenderC, -1, dd, attackerCity.getCenterTile(), realTarget);
                return true;
            }

            // 单位攻击完全交给核心 TargetHelper + Battle：服务端不再手写射程、架设、
            // 近战贴位或“相邻格漂移后改打别人”的兜底规则。
            if (!attackerUnit.canAttack()) return rejectCurrent("core_cannot_attack");
            var distances = attackerUnit.getMovement().getDistanceToTiles(true, null, null, true);
            java.util.List<AttackableTile> candidates = TargetHelper.INSTANCE.getAttackableEnemies(
                attackerUnit, distances, null, false);
            AttackableTile selected = null;
            for (AttackableTile candidate : candidates) {
                if (attackTargetMatches(candidate, target, targetUnitId, targetCityId)) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) return rejectCurrent("stale_or_unreachable_target");
            ICombatant attackerC = new MapUnitCombatant(attackerUnit);
            Tile attackOriginTile = attackerUnit.getTile(); // 击杀移进格前先记发起位置
            if (!Battle.INSTANCE.movePreparingAttack((MapUnitCombatant) attackerC, selected, false)) {
                // 核心可能因隐藏地形只移动到半路；这是一次已执行的权威结果，不伪装成 reject。
                return true;
            }
            ICombatant defenderC = selected.getCombatant();
            if (defenderC == null) return rejectCurrent("target_disappeared");
            Battle.DamageDealt dd = Battle.INSTANCE.attackOrNuke(attackerC, selected);
            recordBattleResult(attackerC, defenderC, attackerUnit.getId(), dd,
                attackOriginTile, selected.getTileToAttack());
            return true;
        } catch (Throwable t) {
            System.err.println("[op] unit.attack failed: " + t.getMessage());
            return rejectCurrent("core_attack_failed");
        }
    }

    private static boolean rejectCurrent(String reason) {
        FsOpResult result = currentResult();
        if (result != null) result.markFail(reason);
        return false;
    }

    /** Resolve the exact defender selected by the client. Coordinates are legacy-only. */
    private static ICombatant resolveRequestedDefender(GameInfo gi, Tile legacyTarget,
                                                        Integer targetUnitId, String targetCityId) {
        if (targetUnitId != null) {
            MapUnit unit = FsTargeting.findUnitById(gi, targetUnitId);
            return unit == null ? null : new MapUnitCombatant(unit);
        }
        if (targetCityId != null && !targetCityId.isEmpty()) {
            City city = FsTargeting.findCityById(gi, targetCityId);
            return city == null ? null : new CityCombatant(city);
        }
        return findDefenderExact(gi, legacyTarget, null);
    }

    private static boolean attackTargetMatches(AttackableTile candidate, Tile legacyTarget,
                                               Integer targetUnitId, String targetCityId) {
        ICombatant combatant = candidate.getCombatant();
        if (targetUnitId != null)
            return combatant instanceof MapUnitCombatant
                && ((MapUnitCombatant) combatant).getUnit().getId() == targetUnitId;
        if (targetCityId != null && !targetCityId.isEmpty())
            return combatant instanceof CityCombatant
                && targetCityId.equals(((CityCombatant) combatant).getCity().getId());
        return candidate.getTileToAttack() == legacyTarget;
    }

    /** 目标格上的防守方：优先单位（不含攻击者自己），否则城市。只查"精确命中"那格。 */
    private static ICombatant findDefenderExact(GameInfo gi, Tile target, MapUnit attackerUnit) {
        for (Civilization oc : gi.getCivilizations()) {
            for (MapUnit u : toList(oc.getUnits().getCivUnits())) {
                if (u == attackerUnit) continue;
                if (u.getTile() == target) return new MapUnitCombatant(u);
            }
        }
        City city = target.getCity();
        if (city != null) return new CityCombatant(city);
        return null;
    }

    /** 把服务端结算出的战斗结果写进当前 FsOpResult —— sim 会携带给所有客户端（动画 + 校正）。 */
    private static void recordBattleResult(ICombatant attacker, ICombatant defender, int attackerUnitId,
                                           Battle.DamageDealt dd, Tile originTile, Tile targetTile) {
        FsOpResult r = currentResult();
        if (r == null) return;
        int defenderUnitId = -1;
        String defenderCityId = null;
        try {
            if (defender instanceof MapUnitCombatant)
                defenderUnitId = ((MapUnitCombatant) defender).getUnit().getId();
            else if (defender instanceof CityCombatant)
                defenderCityId = ((CityCombatant) defender).getCity().getId();
        } catch (Throwable ignore) {}
        // 战斗坐标 + 攻击方文明：广播层按接收者视野过滤 battle 段用（迷雾外不该看到扣血提示）
        String atkCiv = null; int fx = -1, fy = -1, tx = -1, ty = -1;
        try { atkCiv = attacker.getCivInfo().getCivName(); } catch (Throwable ignore) {}
        try { if (originTile != null) { fx = originTile.getPosition().getX(); fy = originTile.getPosition().getY(); } } catch (Throwable ignore) {}
        try { if (targetTile != null) { tx = targetTile.getPosition().getX(); ty = targetTile.getPosition().getY(); } } catch (Throwable ignore) {}
        // DamageDealt: attackerDealt = 打给防守方的伤害, defenderDealt = 打给攻击方的伤害
        r.recordBattle(attackerUnitId, defenderUnitId, defenderCityId,
            dd.getAttackerDealt(), dd.getDefenderDealt(), atkCiv, fx, fy, tx, ty);
    }

    /** 两格是否六边形相邻。 */
    private static boolean isAdjacent(Tile a, Tile b) {
        try {
            for (Tile n : toList(a.getNeighbors())) {
                if (n == b) return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }


    /**
     * 即时建造改良（大工程师 / `ConstructImprovementInstantly` / 造水改良）——
     * 改为执行核心 `getImprovementCreationActions` 里的 lambda：
     *   `tile.setImprovement(imp, civ, unit)` + 更新可见地块 + `activateSideEffects`（含按 unique 消耗单位）。
     *
     * 以前这里是"1 参 `setImprovement(String)` + 不校验不消耗"—— 只改了一个字符串，
     * 副作用（消耗单位、通知、unique 效果）全丢了。
     *
     * 一个工厂可能产出多个动作（水改良一个 + 每条 unique 各若干），客户端点的是其中一个，
     * 所以按"标题里带 [改良名]" +（给了的话）`associatedUnique.text` 精确匹配。
     */
    private static boolean opUnitCreateImprovement(Civilization civ, GameInfo gi, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        String name = asStr(data.get("improvement"));
        if (name == null || name.isEmpty()) return false;
        Tile t = u.getTile();
        if (t == null) return false;
        final String uniqueText = asStr(data.get("unique"));
        final String needle = "[" + name + "]";
        boolean ok = FsCoreActions.runMatching(CLS_ACTIONS_FROM_UNIQUES, "getImprovementCreationActions", u, t,
                a -> {
                    try {
                        if (uniqueText != null && !uniqueText.isEmpty()) {
                            com.unciv.models.ruleset.unique.Unique uq = a.getAssociatedUnique();
                            if (uq == null || !uniqueText.equals(uq.getText())) return false;
                        }
                        String title = a.getTitle();
                        return title != null && title.contains(needle);
                    } catch (Throwable e) {
                        return false;
                    }
                });
        if (!ok) System.out.println("[op] unit.createImprovement: 核心没给出 [" + name + "]（或条件不满足）");
        return ok;
    }

    /**
     * 工人建造改良（**开工**）—— 对齐核心 `ImprovementPickerScreen.accept`：
     *   `tile.startWorkingOnImprovement(improvement, civ, unit)`（内部先清空队列再入队）
     *   可选 `queue` = 第二项改良（核心 "Pick now!" 那条：先拆地形、再建这个）
     *   最后 `unit.setAction(null)` —— "唤醒"睡着的工人，否则它不会开工
     *
     * 施工进度**不需要服务端额外做什么**：每回合 `TurnManager.endTurn`
     * → `UnitTurnManager.endTurn` → `tile.doWorkerTurn(unit)` 自动倒数并落成
     * （前提：工人还在该格、有移动力、且 `canBuildImprovement` 仍然成立）。
     */
    private static boolean opUnitBuildImprovement(Civilization civ, GameInfo gi, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null || civ == null) return false;
        String name = asStr(data.get("improvement"));
        if (name == null) return false;
        try {
            Tile tile = u.getTile();
            if (tile == null) return false;
            if (!u.hasMovement()) return false;
            Ruleset ruleset = rulesetOf(tile, gi);
            if (ruleset == null) return false;
            TileImprovement imp = ruleset.getTileImprovements().get(name);
            if (imp == null) return false;

            // 与客户端 `getBuildingImprovementsActions.couldConstruct` 同一套判定：
            // 单位侧 `canBuildImprovement`（是不是工人 / OnlyAvailable / 军旗等）
            // + 地块侧 `getImprovementBuildingProblems` 里没有"无法报告"的硬性问题。
            if (!u.canBuildImprovement(imp, tile)) return false;
            if (!canBuildOnTile(tile, imp, u)) return false;

            tile.startWorkingOnImprovement(imp, civ, u);

            String queued = asStr(data.get("queue"));
            if (queued != null && !queued.isEmpty()) {
                TileImprovement second = ruleset.getTileImprovements().get(queued);
                if (second != null && u.canBuildImprovement(second, tile)) {
                    tile.queueImprovement(second, civ, u);
                }
            }
            u.setAction(null);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] unit.buildImprovement failed: " + t.getMessage());
            return false;
        }
    }

    /** 取消施工 —— 对齐核心 `ImprovementPickerScreen.accept` 的 cancel 分支：`tile.stopWorkingOnImprovement()`。 */
    private static boolean opUnitStopImprovement(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        try {
            Tile tile = u.getTile();
            if (tile == null) return false;
            tile.stopWorkingOnImprovement();
            return true;
        } catch (Throwable t) { return false; }
    }

    /** 地块侧能否建该改良 —— 等价于核心的 `ImprovementPickerScreen.canReport(problems)`（`problem.reportable`）。 */
    private static boolean canBuildOnTile(Tile tile, TileImprovement imp, MapUnit u) {
        try {
            kotlin.sequences.Sequence<com.unciv.logic.map.tile.ImprovementBuildingProblem> problems =
                    tile.getImprovementFunctions().getImprovementBuildingProblems(imp, u.getCache().getState());
            java.util.Iterator<com.unciv.logic.map.tile.ImprovementBuildingProblem> it = problems.iterator();
            while (it.hasNext()) {
                if (!it.next().getReportable()) return false;
            }
            return true;
        } catch (Throwable t) {
            // 拿不到问题列表时放行（与核心"没有 uniques/条件时的默认可行"一致）
            return true;
        }
    }

    /** 从 data 里取地块：先试 tileX/tileY（城市 op 的惯例），再试 x/y（单位 op 的惯例）。 */
    private static Tile tileFromAnyKey(GameInfo gi, Map<String, Object> data) {
        Tile t = resolveTile(gi, data);
        if (t != null) return t;
        return resolveTile(gi, data, "x", "y");
    }

    /** 地块的规则集，退化到 GameInfo 的规则集（两处都拿不到时返回 null）。 */
    private static Ruleset rulesetOf(Tile tile, GameInfo gi) {
        try {
            Ruleset r = tile.getRuleset();
            if (r != null) return r;
        } catch (Throwable ignore) {}
        return gi == null ? null : gi.getRuleset();
    }

    /**
     * 宗教单位动作 —— 对齐核心 `UnitActionsReligion`：
     *   foundReligion   -> `religionManager.foundReligion(unit)`                  + `unit.consume()`
     *   enhanceReligion -> `religionManager.useProphetForEnhancingReligion(unit)` + `unit.consume()`
     *
     * 以前三个分支都只调 `u.doAction()` —— 那执行的是"单位当前的 action 字符串"。
     * 在服务端权威模型下客户端不会先设 action（它只发 op），所以那等于什么都没做。
     */
    private static boolean opUnitReligion(Civilization civ, String kind, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null || civ == null) return false;
        try {
            if ("foundReligion".equals(kind)) {
                // 核心的 foundReligion() 在前置不满足(不在城中心/圣城重复/宗教已建)时**静默 return**,
                // 先不消耗先知也不置状态 —— 此前 op 层无条件 consume+return true,客户端于是看到
                // "创立宗教"选项一直挂着(状态从未推进),要等回合超时。这里逐项校验并显式失败,
                // 让客户端拿到 reject 而不是假成功。
                var mgr = civ.getReligionManager();
                if (!mgr.mayFoundReligionAtAll()) return rejectCurrent("core_cannot_found_religion");
                if (!u.getTile().isCityCenter()) return rejectCurrent("prophet_not_in_city_center");
                if (!mgr.mayFoundReligionHere(u.getTile())) return rejectCurrent("core_cannot_found_here");
                mgr.foundReligion(u);
                if (mgr.getReligionState() != com.unciv.logic.civilization.managers.ReligionState.FoundingReligion)
                    return rejectCurrent("core_found_religion_noop");
                u.consume();
                return true;
            }
            if ("enhanceReligion".equals(kind)) {
                var mgr = civ.getReligionManager();
                if (!mgr.mayEnhanceReligionAtAll()) return rejectCurrent("core_cannot_enhance");
                if (!u.getTile().isCityCenter()) return rejectCurrent("prophet_not_in_city_center");
                mgr.useProphetForEnhancingReligion(u);
                if (mgr.getReligionState() != com.unciv.logic.civilization.managers.ReligionState.EnhancingReligion)
                    return rejectCurrent("core_enhance_religion_noop");
                u.consume();
                return true;
            }
            // spreadReligion：下一轮做 —— 要给城市加压 + 一堆 uniques 修正，
            // 还需要一个 "X%"/"+X%" 的百分比解析器；先不放半成品进来。
            return false;
        } catch (Throwable t) {
            System.err.println("[op] unit." + kind + " failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * 单位身上是否有某个 unique（对齐核心 `unit.getMatchingUniques(type).any()`）。
     *
     * 注意不能用 Kotlin 的 `getMatchingUniques$default` —— 那是编译器生成的**合成方法**，
     * javac 解析时会直接忽略（报"找不到符号"）。所以这里按核心的默认值自己补上下文：
     * `unit.cache.state`，拿不到就退回 `GameContext.Companion.emptyState`。
     */
    private static boolean unitHasUnique(MapUnit u, com.unciv.models.ruleset.unique.UniqueType type) {
        try {
            com.unciv.models.ruleset.unique.GameContext ctx = null;
            try {
                if (u.getCache() != null) ctx = u.getCache().getState();
            } catch (Throwable ignore) {
            }
            if (ctx == null) ctx = com.unciv.models.ruleset.unique.GameContext.Companion.getEmptyState();
            kotlin.sequences.Sequence<?> seq = u.getMatchingUniques(type, ctx, false);
            return seq != null && seq.iterator().hasNext();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 伟人"加速"四件事 —— 逐条对齐核心 `UnitActionsGreatPerson`。
     *
     * 以前这里**完全忽略 kind**、只调 `u.doAction()`（等于没做，甚至可能执行到别的残留动作）。
     * 核心的真实语义：
     *   research -> `tech.addScience(tech.getScienceFromGreatScientist())`
     *   policy   -> `policies.addCulture(policies.getCultureFromGreatWriter())`
     *   wonder   -> 所在城市 `addProductionPoints((300 + 30*人口) * 生产力修正)`；`constructIfEnough()`
     *   building -> 同上，但取 `min(加成, 剩余产能 - 1)`（对齐核心，避免产能溢出）
     * 四个分支最后都要 `unit.consume()` —— 伟人被消耗掉。
     *
     * 每个分支开头都校验"这个单位确实有对应的能力 unique"（核心是靠 takeIf 条件），
     * 否则一个改过的客户端可以让普通单位"加速"然后把自己消耗掉。
     */
    private static boolean opUnitHurry(Civilization civ, GameInfo gi, String kind, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null || civ == null) return false;
        try {
            if (!u.hasMovement()) return false;

            if ("research".equals(kind)) {
                if (!unitHasUnique(u, com.unciv.models.ruleset.unique.UniqueType.CanHurryResearch)) return false;
                // 没有在研究的科技 / 该科技不可被加速 → 拒绝（对齐核心的 takeIf 条件）
                if (civ.getTech().currentTechnologyName() == null) return false;
                com.unciv.models.ruleset.tech.Technology cur = civ.getTech().currentTechnology();
                if (cur != null && !canHurryTech(cur)) return false;
                civ.getTech().addScience(civ.getTech().getScienceFromGreatScientist());
            } else if ("policy".equals(kind)) {
                if (!unitHasUnique(u, com.unciv.models.ruleset.unique.UniqueType.CanHurryPolicy)) return false;
                civ.getPolicies().addCulture(civ.getPolicies().getCultureFromGreatWriter());
            } else if ("wonder".equals(kind) || "building".equals(kind)) {
                com.unciv.models.ruleset.unique.UniqueType cap = "wonder".equals(kind)
                        ? com.unciv.models.ruleset.unique.UniqueType.CanSpeedupWonderConstruction
                        : com.unciv.models.ruleset.unique.UniqueType.CanSpeedupConstruction;
                if (!unitHasUnique(u, cap)) return false;

                com.unciv.logic.map.tile.Tile tile = u.getTile();
                if (tile == null || !tile.isCityCenter()) return false;
                com.unciv.logic.city.City city = tile.getCity();
                if (city == null) return false;
                com.unciv.logic.city.CityConstructions cc = city.getCityConstructions();
                if (cc == null || !cc.canBeHurried()) return false;
                if ("building".equals(kind)
                        && !(cc.getCurrentConstruction() instanceof com.unciv.models.ruleset.Building))
                    return false;

                float speedMod = 1f;
                try { speedMod = gi.getSpeed().getProductionCostModifier(); } catch (Throwable ignore) {}
                int boosted = (int) ((300 + 30 * city.getPopulation().getPopulation()) * speedMod);

                int toAdd = boosted;
                if ("building".equals(kind)) {
                    int remaining = cc.getRemainingWork(cc.currentConstructionName(), true);
                    toAdd = (int) Math.min((double) boosted, (double) remaining - 1.0);
                    if (toAdd <= 0) return false;
                }
                cc.addProductionPoints(toAdd);
                cc.constructIfEnough();
            } else {
                return false;
            }

            u.consume();
            return true;
        } catch (Throwable t) {
            System.err.println("[op] unit.hurry" + kind + " failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * 该科技是否允许被伟人加速（对应核心的 `!hasUnique(UniqueType.CannotBeHurried)`）。
     *
     * 注意：4.21.19 的 `Technology` 上并没有公开的 `hasUnique` 成员方法（核心那行能编译过
     * 是因为它是 Kotlin 侧的接口/扩展），所以这里用反射找同名单参方法；
     * 找不到就按"可以加速"处理 —— 与核心没标 CannotBeHurried 时的默认行为一致。
     */
    private static boolean canHurryTech(com.unciv.models.ruleset.tech.Technology tech) {
        try {
            for (java.lang.reflect.Method m : tech.getClass().getMethods()) {
                if (!"hasUnique".equals(m.getName()) || m.getParameterCount() != 1) continue;
                Object r = m.invoke(tech, com.unciv.models.ruleset.unique.UniqueType.CannotBeHurried);
                if (r instanceof Boolean) return !((Boolean) r);
                break;
            }
        } catch (Throwable ignore) {
        }
        return true;
    }

    /**
     * 大商人"贸易任务" —— 执行核心 `UnitActionsGreatPerson.getConductTradeMissionActions` 的 lambda
     * （金币 + 城邦影响力 + 通知 + 消耗单位），我们不在 Java 里重抄。
     *
     * 以前这里只调 `u.doAction()` —— 执行的是"单位当前的 action 字符串"，
     * 在权威模型下客户端不会先设 action，所以等于什么都没做。
     */
    private static boolean opUnitTradeMission(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        Tile t = u.getTile();
        if (t == null) return false;
        return FsCoreActions.run(CLS_ACTIONS_GREAT_PERSON, "getConductTradeMissionActions", u, t, 0);
    }

    /** 传教 —— 执行核心 `UnitActionsReligion.getSpreadReligionActions` 的 lambda
     *  （按 religiousStrength × uniques 加宗教压力 / SpreadReligion 附带统计 / 移除其它宗教 / 换多数派）。 */
    private static boolean opUnitSpreadReligion(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        Tile t = u.getTile();
        if (t == null) return false;
        return FsCoreActions.run(CLS_ACTIONS_RELIGION, "getSpreadReligionActions", u, t, 0);
    }

    /**
     * 架设（攻城单位）—— 对齐核心 `getSetupActions`：
     *   `unit.action = UnitActionType.SetUp.value` + `unit.useMovementPoints(1f)`
     * 以前是 `u.doAction()` —— 执行的是"当前 action 字符串"，在权威模型下等于没做。
     */
    private static boolean opUnitSetUp(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        try {
            if (!u.hasMovement()) return false;
            // 对齐核心的 takeIf：只有"必须架设"的单位（攻城武器）才能架设
            if (!unitHasUnique(u, com.unciv.models.ruleset.unique.UniqueType.MustSetUp)) return false;
            u.setAction(com.unciv.models.UnitActionType.SetUp.getValue());
            u.useMovementPoints(1f);
            return true;
        } catch (Throwable t) { return false; }
    }

    private static boolean opUnitGuard(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        try {
            u.setAction("Fortify"); // "Guard" in modern builds is Fortify with due
            return true;
        } catch (Throwable t) { return false; }
    }

    /** Generic "set the unit's action string" op (fortify / sleep / explore / ...). */
    private static boolean opUnitSetAction(Civilization civ, Map<String, Object> data, String action) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        try { u.setAction(action); return true; } catch (Throwable t) { return false; }
    }

    private static boolean opUnitAutomate(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        try { u.setAutomated(true); return true; } catch (Throwable t) { return false; }
    }

    private static boolean opUnitExplore(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        try {
            // Only flag the unit as exploring here. The actual stepping is driven by the turn
            // simulation (FsTurnSync.runHumanAutomation -> UnitAutomation.automatedExplore for
            // every unit whose action == "Explore"), so it stays in-band with the authoritative sim
            // instead of running out-of-band at op time. Calling automatedExplore() here also fired
            // the spurious "[unit] finished exploring." notification the moment tryExplore() found
            // nothing, which is what made the client pop "exploration complete" immediately.
            u.setAction("Explore");
            return true;
        } catch (Throwable t) {
            System.err.println("[op] unit.explore failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean opUnitStopAutomation(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        try { u.setAction(null); u.setAutomated(false); return true; } catch (Throwable t) { return false; }
    }

    private static boolean opUnitDisband(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        try { u.destroy(false); return true; } catch (Throwable t) { return false; }
    }

    private static boolean opUnitFoundCity(Civilization civ, GameInfo gi, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) {
            System.out.println("[op] unit.foundCity DIAG unitId=" + data.get("unitId")
                    + " fromX=" + data.get("fromX") + " fromY=" + data.get("fromY")
                    + " civ=" + data.get("civ") + " unitType=" + data.get("unitType")
                    + " -> UNIT NOT FOUND (civ=" + (civ == null ? "null" : civ.getCivName()) + ")");
            return false;
        }
        Tile target = resolveMoveTile(gi, data);
        if (target == null) target = u.getTile();
        // Defensive: if the target is already a city center (e.g. a stale state from a previous
        // op that founded a city but didn't consume the settler), reject cleanly instead of
        // throwing "Trying to found a city in a tile that already has one".
        if (target != null && target.isCityCenter()) {
            System.out.println("[op] unit.foundCity rejected: tile " + target.getPosition().getX() + "," + target.getPosition().getY()
                    + " is already a city center (city=" + target.getCity() + ")");
            return false;
        }
        try {
            civ.addCity(target.getPosition(), u);
            // The client's own foundCity action destroys the settler after addCity. We must do
            // the same on the authoritative GameInfo, otherwise the settler lingers on the new
            // city-center tile and a later op will try to found another city on top of it.
            try { u.destroy(false); } catch (Throwable ignore) {}
            return true;
        } catch (Throwable t) {
            System.err.println("[op] unit.foundCity failed: " + t);
            t.printStackTrace();
            return false;
        }
    }

    /**
     * 维修被掠夺的改良 —— 对齐核心 `UnitActionsFromUniques.getRepairAction`：
     *   `tile.queueImprovement(Constants.repair, 回合数)` + `unit.setAction(null)`
     *   `回合数 = min(维修基础回合, 原改良回合)`（核心就是这么夹的，见 `getRepairTurns`）；
     *   已经在维修中时沿用 `tile.turnsToImprovement`。
     *
     * 以前这里只 `setAction("Repair")` —— 在权威模型下客户端不会先设 action，
     * 所以那等于给单位贴了个标签、什么都没修。
     */
    private static boolean opUnitRepair(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null || civ == null) return false;
        try {
            Tile tile = u.getTile();
            if (tile == null) return false;
            Ruleset ruleset = rulesetOf(tile, null);
            if (ruleset == null) return false;
            TileImprovement repairImp = ruleset.getTileImprovements().get(Constants.repair);
            if (repairImp == null) return false;
            if (!u.hasMovement()) return false;
            if (u.isEmbarked()) return false;
            if (tile.isCityCenter()) return false;
            if (!tile.isPillaged()) return false;
            if (Constants.repair.equals(tile.getImprovementInProgress())) return false;
            // 必须真的是"能修"的单位（工人），否则任何军队都能修
            if (!u.canBuildImprovement(repairImp, tile)) return false;

            int repairTurns;
            TileImprovement pillaged = tile.getImprovementToRepair();
            if (pillaged != null) {
                int base = repairImp.getTurnsToBuild(civ, u);
                int original = pillaged.getTurnsToBuild(civ, u);
                repairTurns = Math.min(base, original);
            } else {
                repairTurns = repairImp.getTurnsToBuild(civ, u);
            }
            tile.queueImprovement(Constants.repair, repairTurns);
            u.setAction(null);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] unit.repair failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean opUnitRename(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        String name = asStr(data.get("name"));
        if (name == null) return false;
        try {
            // 空字符串 = 清除自定义名，回退到基础单位名（对齐官方 instanceName = null 的语义）
            u.setInstanceName(name.isEmpty() ? null : name);
            return true;
        } catch (Throwable t) { return false; }
    }

    /**
     * 单位变形 —— 执行核心 `UnitActionsFromUniques.getTransformActions` 的 lambda：
     *   `destroy(false)` → `units.placeUnitNearTile(pos, 目标单位, 原id, copiedFrom = 原单位)`
     *   → 搬运运输单位 / 恢复移动力 / 触发 side effects（保留经验与晋升）。
     *
     * 一个单位可能有**多条** CanTransform。客户端知道自己点了哪一条，所以把该 unique 的
     * 文本一起发上来，我们按 `associatedUnique.text` 找回同一条；拿不到文本时退回第一条。
     *
     * ⚠️ 历史记录：这里曾经是 `u.setBaseUnit(null)`（注释说"让下次 setTransients 重新解析"），
     * 那样会把单位直接弄坏。后来改成明确拒绝，现在才是真实现。
     */
    private static boolean opUnitTransform(Civilization civ, GameInfo gi, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        Tile t = u.getTile();
        if (t == null) return false;
        String uniqueText = asStr(data.get("unique"));
        if (uniqueText != null && !uniqueText.isEmpty()) {
            return FsCoreActions.runForUnique(CLS_ACTIONS_FROM_UNIQUES, "getTransformActions", u, t, uniqueText);
        }
        return FsCoreActions.run(CLS_ACTIONS_FROM_UNIQUES, "getTransformActions", u, t, 0);
    }

    // ====================================================================
    // city.* ops
    // ====================================================================

    private static City findCityAny(GameInfo gi, Civilization civ, Map<String, Object> data) {
        String cityId = asStr(data.get("cityId"));
        return findCityById(civ, cityId);
    }

    private static boolean opCityAddToQueue(Civilization civ, Map<String, Object> data, boolean withTile) {
        GameInfo gi = civ != null && civ.getGameInfo() != null ? civ.getGameInfo() : null;
        // civ.getGameInfo() isn't always available; we pass via the dispatcher's gi
        // by accessing our helper field instead.
        return opCityAddToQueueImpl(null, civ, data, withTile);
    }

    // dispatcher variant that gets gi from outer scope via static thread-local
    private static final ThreadLocal<GameInfo> GI_TL = new ThreadLocal<>();
    public static void setCurrentGameInfo(GameInfo gi) { GI_TL.set(gi); }

    /**
     * 当前正在处理的 op 结果（ThreadLocal）。
     *
     * 战斗类 op 需要把"服务端权威结算出的伤害"回传给 FsOpResult，好让 sim 携带给所有客户端
     * （客户端不再自己掷骰子 —— 见 FsOpResult.hasBattle 的说明）。
     */
    private static final ThreadLocal<FsOpResult> RESULT_TL = new ThreadLocal<>();
    private static void setCurrentResult(FsOpResult r) { RESULT_TL.set(r); }
    private static FsOpResult currentResult() { return RESULT_TL.get(); }

    private static boolean opCityAddToQueueImpl(GameInfo giUnused, Civilization civ, Map<String, Object> data, boolean withTile) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        String item = asStr(data.get("newConstruction"));
        if (item == null) return false;
        try {
            Tile t = withTile ? resolveTile(gi, data) : null;
            // 保留门面的插队参数，不能因去掉客户端乐观写入而丢掉行为。
            CityConstructions constructions = city.getCityConstructions();
            java.lang.reflect.Method getter = java.util.Arrays.stream(constructions.getClass().getMethods())
                    .filter(m -> m.getName().startsWith("getConstruction$") && m.getParameterCount() == 1)
                    .findFirst().orElseThrow();
            var construction = (com.unciv.models.ruleset.IConstruction) getter.invoke(constructions, item);
            if (!constructions.canAddToQueue(construction)) return false;
            constructions.addToQueue(construction, Boolean.TRUE.equals(asBool(data.get("addToTop"))), t);
            // 对齐单机 UI（CityConstructionsTable.addConstructionToQueue）：新项成为队首
            // （城市从无生产到有生产）时重分配市民。FS 模式下客户端队列不本地写入，
            // 原本由客户端读本地队列触发的这个动作必须由服务端在权威状态上补做。
            java.util.List<String> queueNow = constructions.getConstructionQueue();
            if (!queueNow.isEmpty() && item.equals(queueNow.get(0))) {
                city.reassignPopulation(false);
            }
            return true;
        } catch (Throwable ex) { return false; }
    }

    private static boolean opCityAddToQueueWithTile(Civilization civ, GameInfo gi, Map<String, Object> data) {
        return opCityAddToQueueImpl(gi, civ, data, true);
    }

    private static boolean opCityAnnex(Civilization civ, Map<String, Object> data) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        try { city.annexCity(); return true; } catch (Throwable t) { return false; }
    }

    private static boolean opCityAssignSpecialist(Civilization civ, Map<String, Object> data) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        String name = asStr(data.get("specialistName"));
        if (name == null) return false;
        Integer deltaObj = asInt(data.get("delta"));
        int delta = deltaObj == null ? 1 : deltaObj;
        if (!gi.getRuleset().getSpecialists().containsKey(name) || (delta != 1 && delta != -1)) return false;
        int allocated = city.getPopulation().getSpecialistAllocations().get(name);
        if (delta < 0 && allocated <= 0 || delta > 0
                && (allocated >= city.getPopulation().getMaxSpecialists().get(name)
                || city.getPopulation().getFreePopulation() <= 0)) return false;
        try {
            city.getPopulation().getSpecialistAllocations().add(name, delta);
            city.setManualSpecialists(true);
            city.getCityStats().update(city.getCityConstructions().getCurrentConstruction(), true, true, true);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] assignSpecialist failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean opCityBuyTile(Civilization civ, GameInfo gi, Map<String, Object> data) {
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        Tile tile = resolveTile(gi, data);
        if (tile == null) return false;
        try {
            if (!city.getExpansion().canBuyTile(tile)) return false;
            city.getExpansion().buyTile(tile);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] city.buyTile failed: " + t);
            return false;
        }
    }

    /**
     * 金币购买建筑 / 单位 —— 对齐核心 `BuyButtonFactory.purchaseConstruction`：
     *   校验（傀儡 / 抵抗 / 可购买 / 可放置 / 该 stat 能买 / 余额够）
     *   → `CityConstructions.purchaseConstruction(...)`（内部负责建造、扣款、维护队列、消费囤积资源）
     *
     * data：`construction`(名字) / 可选 `stat`(默认 Gold) / 可选 `queuePosition`(默认 -1 = 不在队列里买)
     *
     * ⚠️ 必须自己做 `isConstructionPurchaseAllowed` 校验：`purchaseConstruction` 内部**只扣款不验余额**，
     * 直接调会把城市金币扣成负数。
     */
    private static boolean opCityBuy(Civilization civ, GameInfo gi, Map<String, Object> data) {
        City city = findCityAny(gi, civ, data);
        if (city == null) return rejectCurrent("city_not_found");
        String name = asStr(data.get("construction"));
        if (name == null || name.isEmpty()) return rejectCurrent("construction_missing");
        try {
            CityConstructions cc = city.getCityConstructions();
            INonPerpetualConstruction construction = resolvePurchasable(gi, name);
            if (construction == null) return rejectCurrent("construction_unknown");

            Stat stat = Stat.Gold;
            String statName = asStr(data.get("stat"));
            if (statName != null && !statName.isEmpty()) {
                try { stat = Stat.valueOf(statName); }
                catch (IllegalArgumentException invalid) { return rejectCurrent("purchase_stat_invalid"); }
            }

            Integer cost = construction.getStatBuyCost(city, stat);
            if (cost == null) return rejectCurrent("purchase_cost_unavailable");
            if (!cc.isConstructionPurchaseAllowed(construction, stat, cost))
                return rejectCurrent("core_purchase_not_allowed");

            Integer pos = asInt(data.get("queuePosition"));
            int queuePosition = pos == null ? -1 : pos;
            // 只校验范围。核心语义：queuePosition = "买完后从队列移除该位置的条目"（purchaseConstruction
            // 尾部 removeFromQueue），并不要求该位置的名字与所买一致 —— 客户端 UI 传的是
            // selectedQueueEntry，玩家完全可能"选中着队列 A 项、去买列表里的 B"。此前自加的
            // 名字匹配校验比核心严，造成"金币足够却被拒"。
            // 不做范围校验——核心对越界位置本就优雅处理（purchaseConstruction 内部
            // `if (queuePosition in 0 until size)` 才移队）。客户端传的是界面选中项
            // selectedQueueEntry，与权威队列长度瞬时不一致（空/缩短）时，范围拒绝
            // 就是"还是买不了"的根因。只比核心严的地方才拦。
            if (queuePosition < -1) return rejectCurrent("queue_position_invalid");
            Tile tile = resolveTile(gi, data);
            // x/y are the city identity coordinates present in every sendCityOp.
            // Only tileX/tileY describe the optional placement tile used by
            // CreatesOneImprovement constructions.
            if ((data.containsKey("tileX") || data.containsKey("tileY")) && tile == null)
                return rejectCurrent("purchase_tile_invalid");
            // automatic = false（这是玩家主动购买，不是自动化的"补建"）
            if (!cc.purchaseConstruction(construction, queuePosition, false, stat, tile))
                return rejectCurrent("core_purchase_failed");
            return true;
        } catch (Throwable t) {
            System.err.println("[op] city.buy failed: " + t.getMessage());
            return rejectCurrent("core_purchase_exception");
        }
    }

    /** 按名字解析"可购买"的建筑或单位（核心 `CityConstructions.getConstruction` 的 buildings → units 分支）。 */
    private static INonPerpetualConstruction resolvePurchasable(GameInfo gi, String name) {
        Ruleset ruleset = gi.getRuleset();
        Object building = ruleset.getBuildings().get(name);
        if (building instanceof INonPerpetualConstruction) return (INonPerpetualConstruction) building;
        Object unit = ruleset.getUnits().get(name);
        if (unit instanceof INonPerpetualConstruction) return (INonPerpetualConstruction) unit;
        return null;
    }

    private static boolean opCityDisableConstruction(Civilization civ, Map<String, Object> data) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        String name = asStr(data.get("newConstruction"));
        Boolean disable = asBool(data.get("disable"));
        if (name == null || disable == null || resolvePurchasable(gi, name) == null) return false;
        if (disable) city.getDisabledConstructions().add(name);
        else city.getDisabledConstructions().remove(name);
        return true;
    }

    private static boolean opCityDisableManualSpecialists(Civilization civ, Map<String, Object> data) {
        return opCityManualSpecialistsImpl(civ, data, false);
    }

    private static boolean opCityEnableManualSpecialists(Civilization civ, Map<String, Object> data) {
        return opCityManualSpecialistsImpl(civ, data, true);
    }

    private static boolean opCityManualSpecialistsImpl(Civilization civ, Map<String, Object> data, boolean enable) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        try {
            city.setManualSpecialists(enable);
            if (!enable) city.reassignPopulation(false);
            return true;
        } catch (Throwable t) { return false; }
    }

    private static boolean opCityLockTile(Civilization civ, GameInfo gi, Map<String, Object> data, boolean lock) {
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        Tile tile = resolveTile(gi, data);
        if (tile == null) return false;
        try {
            return lock ? city.lockTile(tile) : city.unlockTile(tile);
        } catch (Throwable t) { return false; }
    }

    private static boolean opCityChangePriority(Civilization civ, Map<String, Object> data, int direction) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        String name = asStr(data.get("constructionName"));
        if (name == null) name = asStr(data.get("newConstruction"));
        try {
            java.util.ArrayList<String> q = city.getCityConstructions().getConstructionQueue();
            Integer suppliedIndex = asInt(data.get("index"));
            int idx = suppliedIndex == null ? q.indexOf(name) : suppliedIndex;
            if (idx < 0 || idx >= q.size() || (name != null && !name.equals(q.get(idx)))) return false;
            if (direction > 0 && idx == 0 || direction < 0 && idx == q.size() - 1) return false;
            if (direction > 0) city.getCityConstructions().raisePriority(idx);
            else              city.getCityConstructions().lowerPriority(idx);
            return true;
        } catch (Throwable t) { return false; }
    }

    private static boolean opCityQueueMoveEnd(Civilization civ, Map<String, Object> data) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        Integer idx = asInt(data.get("index"));
        try {
            if (idx != null) {
                city.getCityConstructions().moveEntryToEnd(idx);
                return true;
            }
            String name = asStr(data.get("newConstruction"));
            if (name == null) return false;
            java.util.ArrayList<String> q = city.getCityConstructions().getConstructionQueue();
            int i = q.indexOf(name);
            if (i < 0) return false;
            city.getCityConstructions().moveEntryToEnd(i);
            return true;
        } catch (Throwable t) { return false; }
    }

    private static boolean opCityQueueMoveEntry(Civilization civ, Map<String, Object> data) {
        City city = findCityAny(civ.getGameInfo(), civ, data);
        if (city == null) return false;
        Integer index = asInt(data.get("index"));
        java.util.ArrayList<String> queue = city.getCityConstructions().getConstructionQueue();
        String expected = asStr(data.get("newConstruction"));
        if (index == null || index < 0 || index >= queue.size()
                || expected != null && !expected.equals(queue.get(index))) return false;
        // 客户端此入口明确表示置顶，不能复用置底操作。
        city.getCityConstructions().moveEntryToTop(index);
        return true;
    }

    private static boolean opCityReassignPopulation(Civilization civ, Map<String, Object> data) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        try {
            city.reassignPopulation(Boolean.TRUE.equals(asBool(data.get("resetLocked"))));
            return true;
        } catch (Throwable t) { return false; }
    }

    private static boolean opCityRemoveFromQueueByIndex(Civilization civ, Map<String, Object> data) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        Integer idx = asInt(data.get("index"));
        try {
            if (idx != null) city.getCityConstructions().removeFromQueue(idx, true);
            return true;
        } catch (Throwable t) { return false; }
    }

    private static boolean opCityRemoveFromQueueByName(Civilization civ, Map<String, Object> data) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        String name = asStr(data.get("newConstruction"));
        if (name == null) return false;
        try { city.getCityConstructions().removeAllByName(name); return true; }
        catch (Throwable t) { return false; }
    }

    private static boolean opCityRename(Civilization civ, Map<String, Object> data) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        String newName = asStr(data.get("name"));
        if (newName == null) return false;
        // Unciv core renames cities via City.name (kotlin field); use setName-ish via reflection.
        try {
            java.lang.reflect.Field f = city.getClass().getDeclaredField("name");
            f.setAccessible(true);
            f.set(city, newName);
            return true;
        } catch (Throwable t) { return false; }
    }

    private static boolean opCitySaveUnitPromotions(Civilization civ, Map<String, Object> data) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        Object promotions = data.get("promotions");
        if (promotions == null) return false;
        try {
            for (java.lang.reflect.Method m : city.getClass().getMethods()) {
                if (!m.getName().equals("saveUnitPromotions")) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                m.invoke(city, promotions.toString());
                return true;
            }
            return false;
        } catch (Throwable t) { return false; }
    }

    private static boolean opCitySellBuilding(Civilization civ, Map<String, Object> data) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        String building = asStr(data.get("buildingName"));
        if (building == null) return false;
        try { city.sellBuilding(building); return true; } catch (Throwable t) { return false; }
    }

    private static boolean opCitySetFocus(Civilization civ, Map<String, Object> data) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        String focusName = asStr(data.get("newFocus"));
        if (focusName == null) return false;
        try {
            Class<?> focusCls = Class.forName("com.unciv.logic.city.CityFocus");
            Object focus = null;
            for (Object e : focusCls.getEnumConstants())
                if (e.toString().equals(focusName)) { focus = e; break; }
            if (focus == null) return false;
            java.lang.reflect.Method m = city.getClass().getMethod("setCityFocus", focusCls);
            m.invoke(city, focus);
            city.reassignPopulation(false);
            return true;
        } catch (Throwable t) { return false; }
    }

    private static boolean opCitySetProduction(Civilization civ, Map<String, Object> data) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        String item = asStr(data.get("newConstruction"));
        if (item == null) return false;
        try { city.getCityConstructions().setCurrentConstruction(item); return true; }
        catch (Throwable t) { return false; }
    }

    private static boolean opCitySetRazing(Civilization civ, Map<String, Object> data) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        Boolean raze = asBool(data.get("razing"));
        try { city.setBeingRazed(raze != null && raze); return true; } catch (Throwable t) { return false; }
    }

    private static boolean opCitySetUnitSavedPromotion(Civilization civ, Map<String, Object> data) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        String baseUnit = asStr(data.get("baseUnit"));
        Boolean value = asBool(data.get("value"));
        if (baseUnit == null || value == null) return false;
        try {
            city.getUnitShouldUseSavedPromotion().put(baseUnit, value);
            return true;
        } catch (Throwable t) { return false; }
    }

    private static boolean opCityStopWorkTile(Civilization civ, GameInfo gi, Map<String, Object> data) {
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        Tile tile = resolveTile(gi, data);
        if (tile == null) return false;
        try { return city.stopWorkingTile(tile); } catch (Throwable t) { return false; }
    }

    private static boolean opCityToggleAvoidGrowth(Civilization civ, Map<String, Object> data) {
        GameInfo gi = GI_TL.get();
        if (gi == null && civ != null) gi = civ.getGameInfo();
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        try {
            city.setAvoidGrowth(!city.getAvoidGrowth());
            city.reassignPopulation(false);
            return true;
        }
        catch (Throwable t) { return false; }
    }

    private static boolean opCityWorkTile(Civilization civ, GameInfo gi, Map<String, Object> data) {
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        Tile tile = resolveTile(gi, data);
        if (tile == null) return false;
        try { return city.workTile(tile); } catch (Throwable t) { return false; }
    }

    // ====================================================================
    // civ.* ops
    // ====================================================================

    private static void ensureMeet(Civilization civ, Civilization other) {
        try {
            DiplomacyFunctions df = civ.getDiplomacyFunctions();
            df.makeCivilizationsMeet(other, true);
        } catch (Throwable t) { /* may already know each other */ }
    }

    private static boolean opCivMeet(Civilization civ, GameInfo gi, Map<String, Object> data) {
        if (civ == null) return false;
        String otherId = asStr(data.get("civ"));
        if (otherId == null) return false;
        Civilization other = findCivByCivId(gi, otherId);
        if (other == null || other == civ) return false;
        try { civ.getDiplomacyFunctions().makeCivilizationsMeet(other, true); return true; }
        catch (Throwable t) { return false; }
    }

    private static boolean opCivDeclareWar(Civilization civ, GameInfo gi, Map<String, Object> data) {
        if (civ == null) return false;
        String otherId = asStr(data.get("otherCivId"));
        if (otherId == null) return false;
        Civilization other = findCivByCivId(gi, otherId);
        if (other == null || other == civ) return false;
        try {
            ensureMeet(civ, other);
            DiplomacyManager dm = civ.getDiplomacyManager(other);
            if (dm == null) return false;
            try {
                Class<?> warTypeCls = Class.forName("com.unciv.logic.civilization.diplomacy.WarType");
                Object war = null;
                for (Object o : warTypeCls.getEnumConstants())
                    if (o.toString().equals("DirectWar") || o.toString().equals("War")) { war = o; break; }
                if (war == null) war = warTypeCls.getEnumConstants()[0];
                Class<?> dcrCls = Class.forName("com.unciv.logic.civilization.diplomacy.DeclareWarReason");
                Object reason = dcrCls.getConstructor(warTypeCls, Civilization.class).newInstance(war, other);
                dm.getClass().getMethod("declareWar", reason.getClass()).invoke(dm, reason);
            } catch (NoSuchMethodException nsme) {
                Class<?> sCls = Class.forName("com.unciv.logic.civilization.diplomacy.DiplomaticStatus");
                Object war = null;
                for (Object o : sCls.getEnumConstants()) if (o.toString().equals("War")) { war = o; break; }
                dm.getClass().getMethod("setDiplomaticStatus", sCls).invoke(dm, war);
            }
            return true;
        } catch (Throwable t) {
            System.err.println("[op] civ.declareWar failed: " + t);
            return false;
        }
    }

    private static boolean opCivMakePeace(Civilization civ, GameInfo gi, Map<String, Object> data) {
        if (civ == null) return false;
        String otherId = asStr(data.get("otherCivId"));
        if (otherId == null) return false;
        Civilization other = findCivByCivId(gi, otherId);
        if (other == null || other == civ) return false;
        try {
            ensureMeet(civ, other);
            DiplomacyManager dm = civ.getDiplomacyManager(other);
            if (dm == null) return false;
            try { dm.getClass().getMethod("makePeace").invoke(dm); }
            catch (NoSuchMethodException nsme) {
                Class<?> sCls = Class.forName("com.unciv.logic.civilization.diplomacy.DiplomaticStatus");
                Object peace = null;
                for (Object o : sCls.getEnumConstants()) if (o.toString().equals("Peace")) { peace = o; break; }
                dm.getClass().getMethod("setDiplomaticStatus", sCls).invoke(dm, peace);
            }
            return true;
        } catch (Throwable t) { return false; }
    }

    private static boolean opCivDenounce(Civilization civ, GameInfo gi, Map<String, Object> data) {
        if (civ == null) return false;
        String otherId = asStr(data.get("otherCivId"));
        if (otherId == null) return false;
        Civilization other = findCivByCivId(gi, otherId);
        if (other == null || other == civ) return false;
        try {
            ensureMeet(civ, other);
            DiplomacyManager dm = civ.getDiplomacyManager(other);
            if (dm == null) return false;
            try { dm.getClass().getMethod("denounce").invoke(dm); }
            catch (NoSuchMethodException nsme) {
                dm.getClass().getMethod("setDiplomaticModifier",
                    Class.forName("java.lang.String"), float.class)
                    .invoke(dm, "Denounced", -10f);
            }
            return true;
        } catch (Throwable t) { return false; }
    }

    private static boolean opCivFriendship(Civilization civ, GameInfo gi, String action, Map<String, Object> data) {
        if (civ == null) return false;
        // offer -> 我是提议方（字段 to）；accept/decline -> 我是被提议方（字段 requestingCiv）
        String otherId = asStr(data.get(action.equals("offer") ? "to" : "requestingCiv"));
        if (otherId == null) return false;
        Civilization other = findCivByCivId(gi, otherId);
        if (other == null || other == civ) return false;
        try {
            ensureMeet(civ, other);
            DiplomacyManager dm = civ.getDiplomacyManager(other);
            if (dm == null) return false;
            if ("offer".equals(action)) {
                // A 向 B 提议宣友 —— 官方机制：写进 B 的 popupAlerts，由 B 客户端弹窗回应。
                // （不能直接 signDeclarationOfFriendship：那是"签署"，不是"提议"。）
                addAlert(other, "DeclarationOfFriendship", civ.getCivID());
                return true;
            }
            if ("accept".equals(action)) {
                dm.signDeclarationOfFriendship();
                removeAlert(civ, "DeclarationOfFriendship", other.getCivID());
                return true;
            }
            if ("decline".equals(action)) {
                dm.setFlag(
                    com.unciv.logic.civilization.diplomacy.DiplomacyFlags.DeclinedDeclarationOfFriendship, 20, false);
                removeAlert(civ, "DeclarationOfFriendship", other.getCivID());
                return true;
            }
            return false;
        } catch (Throwable t) { return false; }
    }

    /**
     * 外交胜利投票 —— 核心 `Civilization.diplomaticVoteForCiv(chosenCivID)`：
     * 记进 `gameInfo.diplomaticVictoryVotesCast[civID]`（null = 弃权）。
     * 只在"该我投票了"的时候允许（`mayVoteForDiplomaticVictory`），其余时间明确拒绝。
     *
     * 以前这里是个空壳：只调了一下 `getDiplomacyFunctions()` 就 return true，等于什么都没记。
     */
    private static boolean opCivDiplomaticVote(Civilization civ, GameInfo gi, Map<String, Object> data) {
        if (civ == null) return false;
        try {
            if (!civ.mayVoteForDiplomaticVictory()) return false;
            String chosen = asStr(data.get("chosenCiv"));
            String chosenId = null;
            if (chosen != null && !chosen.isEmpty()) {
                Civilization target = FsTargeting.findCivByNameOrId(gi, chosen);
                if (target == null) return false;
                chosenId = target.getCivID();
            }
            civ.diplomaticVoteForCiv(chosenId);   // null = 弃权
            return true;
        } catch (Throwable t) {
            System.err.println("[op] civ.diplomaticVote failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * 傀儡状态开关 —— 用在"联姻拿到城市"之后的选择（占领流程走 `civ.cityConquerChoice`）。
     * 对齐核心 `AlertPopup.addDiplomaticMarriage` 里那句 `city.isPuppet = true; city.cityStats.update()`。
     */
    private static boolean opCitySetPuppet(Civilization civ, GameInfo gi, Map<String, Object> data) {
        City city = findCityAny(gi, civ, data);
        if (city == null) return false;
        Boolean puppet = asBool(data.get("puppet"));
        if (puppet == null) puppet = Boolean.TRUE;
        try {
            city.setPuppet(puppet);
            // Kotlin 默认参数在 Java 侧必须传全参：update(currentConstruction, updateTileStats, updateCivStats, calculateGrowthModifiers)
            city.getCityStats().update(city.getCityConstructions().getCurrentConstruction(), true, true, true);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] city.setPuppet failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * 占领城市后的四选一 —— 对齐核心 `AlertPopup.addCityConquered` 里四个按钮的动作：
     *   annex    -> `puppetCity(civ)` + `annexCity()`
     *   puppet   -> `puppetCity(civ)`
     *   raze     -> `puppetCity(civ)` +（可并吞时）`annexCity()` + `setBeingRazed(true)`
     *   liberate -> `liberateCity(原主)`（把城市还给原主，换来关系大幅提升）
     *
     * 以前这里直接 `return true` —— 占领后的选择纯客户端执行，服务端不知道，
     * 于是下个 sim 就把城市纠正回"傀儡"或者干脆没转手。
     */
    private static boolean opCivCityConquerChoice(Civilization civ, GameInfo gi, Map<String, Object> data) {
        City city = findCityAny(gi, civ, data);
        String choice = asStr(data.get("choice"));
        if (city == null || choice == null || civ == null) return false;
        // 消费掉对应的 CityConquered 弹窗（对齐官方：选择后不再出现，投影也不再携带）
        try {
            final String cityId = city.getId();
            civ.getPopupAlerts().removeIf(pa -> pa != null
                    && pa.getType() == com.unciv.logic.civilization.AlertType.CityConquered
                    && cityId.equals(pa.getValue()));
        } catch (Throwable ignored) {
        }
        try {
            switch (choice) {
                case "annex":
                    city.puppetCity(civ);
                    city.annexCity();
                    return true;
                case "puppet":
                    city.puppetCity(civ);
                    return true;
                case "raze":
                    city.puppetCity(civ);
                    if (!civHasUnique(civ, com.unciv.models.ruleset.unique.UniqueType.MayNotAnnexCities)) {
                        city.annexCity();
                    }
                    city.setBeingRazed(true);
                    return true;
                case "liberate":
                    // 参数是"解放者"（对齐核心 UI：`city.liberateCity(conqueringCiv)`），
                    // 城市会回到它的原主手里，并给解放者一笔关系加成。
                    city.liberateCity(civ);
                    return true;
                case "destroy":
                    // 只有"单城挑战"才有的选项：先傀儡再抹掉（对齐核心 AlertPopup）
                    city.puppetCity(civ);
                    city.destroyCity(false);   // Kotlin 默认 overrideSafeties = false
                    return true;
                default:
                    System.out.println("[op] civ.cityConquerChoice: 未知选择 " + choice);
                    return false;
            }
        } catch (Throwable t) {
            System.err.println("[op] civ.cityConquerChoice failed: " + t.getMessage());
            return false;
        }
    }

    /** 文明身上是否有某个 unique（对齐核心 `civ.hasUnique(type)`）。 */
    private static boolean civHasUnique(Civilization civ, com.unciv.models.ruleset.unique.UniqueType type) {
        try {
            return civ.hasUnique(type, civ.getState());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean opCivChooseTech(Civilization civ, Map<String, Object> data) {
        if (civ == null) return false;
        try {
            // 免费科技是消费次数并完成研究，不是把科技放进研究队列。
            if (Boolean.TRUE.equals(asBool(data.get("free")))) {
                String name = asStr(data.get("tech"));
                if (name == null || !civ.getGameInfo().getRuleset().getTechnologies().containsKey(name)
                        || civ.getTech().getFreeTechs() <= 0 || !civ.getTech().canBeResearched(name)) return false;
                civ.getTech().getFreeTechnology(name);
                return true;
            }
            Object techMgr = civ.getClass().getMethod("getTech").invoke(civ);
            if (techMgr == null) return false;

            // 客户端"确认后"上传的是【完整研究队列】（可能排了多个科技）—— 整体替换，
            // 保证服务端队列与客户端一致（只发队首会导致两边队列不同步）。
            Object many = data.get("techs");
            if (many instanceof java.util.List) {
                java.util.ArrayList<String> list = new java.util.ArrayList<>();
                for (Object t : (java.util.List<?>) many) if (t != null) list.add(t.toString());
                if (list.stream().anyMatch(name -> !civ.getGameInfo().getRuleset().getTechnologies().containsKey(name))) return false;
                techMgr.getClass().getMethod("setTechsToResearch", java.util.ArrayList.class).invoke(techMgr, list);
                return true;
            }

            // 兼容：单个 tech，插到队首
            String tech = asStr(data.get("tech"));
            if (tech == null || !civ.getGameInfo().getRuleset().getTechnologies().containsKey(tech)) return false;
            java.util.ArrayList<String> seq = (java.util.ArrayList<String>)
                techMgr.getClass().getMethod("getTechsToResearch").invoke(techMgr);
            if (seq == null) seq = new java.util.ArrayList<>();
            seq.remove(tech);
            seq.add(0, tech);
            techMgr.getClass().getMethod("setTechsToResearch", java.util.ArrayList.class).invoke(techMgr, seq);
            return true;
        } catch (Throwable t) { return false; }
    }

    private static boolean opCivChoosePolicy(Civilization civ, Map<String, Object> data) {
        if (civ == null) return false;
        String policy = asStr(data.get("policy"));
        if (policy == null) return false;
        try {
            Object policyMgr = civ.getClass().getMethod("getPolicies").invoke(civ);
            if (policyMgr == null) return false;

            // 找 Policy：直接按名取；失败再在 ruleset 里宽松匹配（政策/分支混在一起）
            Object polObj = null;
            try {
                polObj = policyMgr.getClass().getMethod("getPolicyByName", String.class)
                    .invoke(policyMgr, policy);
            } catch (Throwable ignore) {
                try {
                    Object rs = civ.getGameInfo().getRuleset();
                    Object rulesetPolicies = rs.getClass().getMethod("getPolicies").invoke(rs);
                    if (rulesetPolicies instanceof java.util.Map)
                        polObj = ((java.util.Map<?, ?>) rulesetPolicies).get(policy);
                    if (polObj == null) {
                        // 有的分支（PolicyBranch）不在 policies 里，只在 policyBranches 里
                        Object branches = rs.getClass().getMethod("getPolicyBranches").invoke(rs);
                        if (branches instanceof java.util.Map)
                            polObj = ((java.util.Map<?, ?>) branches).get(policy);
                    }
                } catch (Throwable ignore2) {}
            }
            if (polObj == null) {
                System.err.println("[op] civ.choosePolicy: 找不到政策 '" + policy + "'");
                return false;
            }

            // 关键：adopt 的形参类型是 Policy（不是运行时的具体子类 PolicyBranch 等）。
            // 以前用 polObj.getClass() 查方法，遇到子类就 NoSuchMethodException →
            // 被 catch 吞掉 → 返回 true 却什么都没做 —— 表现就是"选完政策，下回合回退"。
            java.lang.reflect.Method adopt = null;
            try {
                adopt = policyMgr.getClass().getMethod("adopt",
                    com.unciv.models.ruleset.Policy.class, boolean.class);
            } catch (NoSuchMethodException nsme) {
                for (java.lang.reflect.Method mm : policyMgr.getClass().getMethods()) {
                    if (!mm.getName().equals("adopt")) continue;
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length == 2 && ps[1] == boolean.class) { adopt = mm; break; }
                }
            }
            if (adopt == null) return false;
            // 核心 adopt 本身不承担 UI 的可选性检查，服务端必须重复校验。
            if (!civ.getPolicies().canAdoptPolicy()
                    || !civ.getPolicies().isAdoptable((com.unciv.models.ruleset.Policy) polObj, true)) return false;
            adopt.invoke(policyMgr, polObj, false);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] civ.choosePolicy('" + policy + "') failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean opCivChooseReligion(Civilization civ, Map<String, Object> data) {
        if (civ == null) return false;
        // UncivSync: 客户端可能一次选多个信条（chooseBeliefs(list)），逐个应用。
        Object many = data.get("beliefs");
        if (many instanceof java.util.List) {
            boolean allOk = true;
            for (Object b : (java.util.List<?>) many) {
                if (b == null) continue;
                java.util.Map<String, Object> one = new java.util.HashMap<>();
                one.put("belief", b.toString());
                allOk &= applySingleBelief(civ, one);
            }
            return allOk;
        }
        return applySingleBelief(civ, data);
    }

    private static boolean applySingleBelief(Civilization civ, Map<String, Object> data) {
        String belief = asStr(data.get("belief"));
        if (belief == null) return false;
        try {
            Object relMgr = civ.getClass().getMethod("getReligionManager").invoke(civ);
            if (relMgr == null) return false;
            // 4.21.19 没有 setPantheonBelief / addPantheonBelief / choosePantheonBelief 这些 String 版入口；
            // 公开入口是 chooseBeliefs(List<Belief>, useFreeBeliefs)，其内部 religionState==None 时会
            // 调 foundPantheon(beliefName, useFreeBelief)，并完成 religionState→Pantheon 的转移与触发器。
            // 这里把 belief 名从 ruleset 解析成 Belief 对象再调，与客户端 PantheonPickerScreen 的调用一致。
            Object gameInfo = civ.getClass().getMethod("getGameInfo").invoke(civ);
            Object ruleset = gameInfo.getClass().getMethod("getRuleset").invoke(gameInfo);
            Object beliefsMap = ruleset.getClass().getMethod("getBeliefs").invoke(ruleset); // LinkedHashMap<String,Belief>
            Object beliefObj = beliefsMap.getClass().getMethod("get", Object.class).invoke(beliefsMap, belief);
            if (beliefObj == null) {
                System.err.println("[op] civ.choosePantheon belief not found in ruleset: " + belief);
                return false;
            }
            boolean useFree = (Boolean) relMgr.getClass().getMethod("usingFreeBeliefs").invoke(relMgr);
            java.util.List<Object> list = new java.util.ArrayList<>();
            list.add(beliefObj);
            relMgr.getClass().getMethod("chooseBeliefs", java.util.List.class, boolean.class)
                .invoke(relMgr, list, useFree);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] civ.choosePantheon('" + belief + "') failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    /** UncivSync: 创立宗教（客户端 ReligiousBeliefsPickerScreen 上报）。 */
    private static boolean opCivFoundReligion(Civilization civ, Map<String, Object> data) {
        if (civ == null) return false;
        String displayName = asStr(data.get("displayName"));
        String religionName = asStr(data.get("religionName"));
        if (displayName == null || religionName == null) return false;
        try {
            Object relMgr = civ.getClass().getMethod("getReligionManager").invoke(civ);
            if (relMgr == null) return false;
            relMgr.getClass().getMethod("foundReligion", String.class, String.class)
                .invoke(relMgr, displayName, religionName);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] foundReligion failed: " + t.getMessage());
            return false;
        }
    }

    /** UncivSync: 单位晋升（客户端 PromotionPickerScreen 上报）。 */
    private static boolean opUnitPromote(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        String promo = asStr(data.get("promotion"));
        if (promo == null) return false;
        try {
            u.getPromotions().addPromotion(promo, false);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] promote failed: " + t.getMessage());
            return false;
        }
    }

    /** UncivSync: 单位升级（客户端 UnitActionsUpgrade 上报）。 */
    private static boolean opUnitUpgrade(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        String toType = asStr(data.get("unitToUpgradeTo"));
        Boolean isFree = asBool(data.get("isFree"));
        Integer goldCost = asInt(data.get("goldCostOfUpgrade"));
        if (toType == null) return false;
        try {
            Object baseUnit = civ.getGameInfo().getRuleset().getUnits().get(toType);
            if (baseUnit == null) return false;
            u.getUpgrade().performUpgrade(
                (com.unciv.models.ruleset.unit.BaseUnit) baseUnit,
                isFree != null && isFree,
                goldCost);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] upgrade failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean opCivDemand(Civilization civ, GameInfo gi, Map<String, Object> data) {
        if (civ == null) return false;
        String otherId = asStr(data.get("otherCivId"));
        String demandName = asStr(data.get("demand"));
        Civilization other = findCivByCivId(gi, otherId);
        if (other == null || other == civ || demandName == null) return false;
        try {
            ensureMeet(civ, other);
            com.unciv.logic.civilization.diplomacy.Demand d =
                com.unciv.logic.civilization.diplomacy.Demand.valueOf(demandName);
            // A 向 B 提出索求 —— 写进 B 的 popupAlerts，由 B 弹窗接受/拒绝（官方机制）
            addAlert(other, d.getDemandAlert().name(), civ.getCivID());
            return true;
        } catch (Throwable t) { return false; }
    }

    private static boolean opCivDemandAccept(Civilization civ, GameInfo gi, Map<String, Object> data) {
        if (civ == null) return false;
        String fromId = asStr(data.get("requestingCiv"));
        String demandName = asStr(data.get("demand"));
        Civilization other = findCivByCivId(gi, fromId);
        if (other == null || demandName == null) return false;
        try {
            DiplomacyManager dm = civ.getDiplomacyManager(other);
            if (dm == null) return false;
            com.unciv.logic.civilization.diplomacy.Demand d =
                com.unciv.logic.civilization.diplomacy.Demand.valueOf(demandName);
            dm.agreeToDemand(d);
            removeAlert(civ, d.getDemandAlert().name(), other.getCivID());
            return true;
        } catch (Throwable t) { return false; }
    }

    private static boolean opCivDemandRefuse(Civilization civ, GameInfo gi, Map<String, Object> data) {
        if (civ == null) return false;
        String fromId = asStr(data.get("requestingCiv"));
        String demandName = asStr(data.get("demand"));
        Civilization other = findCivByCivId(gi, fromId);
        if (other == null || demandName == null) return false;
        try {
            DiplomacyManager dm = civ.getDiplomacyManager(other);
            if (dm == null) return false;
            com.unciv.logic.civilization.diplomacy.Demand d =
                com.unciv.logic.civilization.diplomacy.Demand.valueOf(demandName);
            dm.refuseDemand(d);
            removeAlert(civ, d.getDemandAlert().name(), other.getCivID());
            return true;
        } catch (Throwable t) { return false; }
    }

    private static boolean opCivTribute(Civilization civ, GameInfo gi, Map<String, Object> data) {
        if (civ == null) return false;
        String otherId = asStr(data.get("otherCivId"));
        String kind = asStr(data.get("kind"));
        Civilization other = findCivByCivId(gi, otherId);
        if (other == null || other == civ || !other.isCityState()) return false;
        try {
            if ("worker".equals(kind)) other.getCityStateFunctions().tributeWorker(civ);
            else other.getCityStateFunctions().tributeGold(civ);
            return true;
        } catch (Throwable t) { return false; }
    }

    // ====================================================================
    // unit.gift —— 单位赠予（转移给别的文明/城邦）
    // ====================================================================
    private static boolean opUnitGift(Civilization civ, GameInfo gi, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        String recipientId = asStr(data.get("recipient"));
        Civilization recipient = findCivByCivId(gi, recipientId);
        if (recipient == null || recipient == civ) return false;
        try {
            if (recipient.isCityState()) {
                // 城邦：加影响力；伟人直接销毁（城邦不留伟人）—— 与官方 UnitActions.getGiftActions 一致
                try { recipient.getDiplomacyManager(civ).addInfluence(5f); } catch (Throwable t2) {}
                if (u.isGreatPerson()) u.destroy(true);
                else u.gift(recipient);
            } else {
                try {
                    recipient.getDiplomacyManager(civ).addModifier(
                        com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.GaveUsUnits, 5f, null);
                } catch (Throwable t2) {}
                u.gift(recipient);
            }
            return true;
        } catch (Throwable t) { return false; }
    }

    // ====================================================================
    // 外交通知 helper —— popupAlerts 的增删（宣友提议 / 索求的送达通道）
    // ====================================================================
    /** 客户端已消费某条通知（弹窗关闭 / 被丢弃）—— 从权威存档移除。 */
    private static boolean opCivAlertSeen(Civilization civ, Map<String, Object> data) {
        if (civ == null) return false;
        String type = asStr(data.get("type"));
        String value = asStr(data.get("value"));
        if (type == null) return false;
        removeAlert(civ, type, value);
        return true;
    }

    /** 往目标文明的 popupAlerts 加一条通知（同类型同来源去重）。 */
    private static void addAlert(Civilization target, String typeName, String value) {
        if (target == null || typeName == null) return;
        try {
            com.unciv.logic.civilization.AlertType at =
                com.unciv.logic.civilization.AlertType.valueOf(typeName);
            for (com.unciv.logic.civilization.PopupAlert pa : target.getPopupAlerts()) {
                try {
                    if (pa.getType() == at && value != null && value.equals(pa.getValue())) return;
                } catch (Throwable t2) {}
            }
            target.getPopupAlerts().add(new com.unciv.logic.civilization.PopupAlert(at, value));
        } catch (Throwable t) {}
    }

    /** 从目标文明的 popupAlerts 移除一条通知（回应后清理，避免重复弹窗）。 */
    private static void removeAlert(Civilization target, String typeName, String value) {
        if (target == null || typeName == null) return;
        try {
            final com.unciv.logic.civilization.AlertType at =
                com.unciv.logic.civilization.AlertType.valueOf(typeName);
            target.getPopupAlerts().removeIf(pa -> {
                try { return pa.getType() == at && (value == null || value.equals(pa.getValue())); }
                catch (Throwable t2) { return false; }
            });
        } catch (Throwable t) {}
    }

    private static boolean opCivSetConstructionDisabled(Civilization civ, Map<String, Object> data) {
        if (civ == null) return false;
        String name = asStr(data.get("newConstruction"));
        Boolean disable = asBool(data.get("disable"));
        if (name == null || disable == null || !civ.getGameInfo().getRuleset().getBuildings().containsKey(name)
                && !civ.getGameInfo().getRuleset().getUnits().containsKey(name)) return false;
        try {
            for (City city : civ.getCities()) {
                if (disable) city.getDisabledConstructions().add(name);
                else city.getDisabledConstructions().remove(name);
            }
            if (disable) civ.getDisabledCityConstructions().add(name);
            else civ.getDisabledCityConstructions().remove(name);
            return true;
        } catch (Throwable t) { return false; }
    }


    // ====================================================================
    // trade.* ops — 玩家之间的实时交易（UncivSync 真实现）
    //
    // 官方机制：发起方把 Trade 写进对方的 civ.tradeRequests（TradeRequest(fromCiv, trade)）；
    // 接收方客户端在 WorldScreen.update 里发现 tradeRequests 非空 → 弹 TradePopup
    // （"Sounds good!" / "Not this time."）。所以服务端只要把请求写进权威存档并广播，
    // 交易就会"实时送达"；接受/拒绝由服务端权威执行（资源转移是服务端算的）。
    // ====================================================================

    private static com.unciv.logic.trade.TradeRequest findTradeRequest(Civilization receiver, String fromCivId) {
        if (receiver == null || fromCivId == null) return null;
        for (com.unciv.logic.trade.TradeRequest tr : receiver.getTradeRequests()) {
            if (fromCivId.equals(tr.getRequestingCiv())) return tr;
        }
        return null;
    }

    /**
     * 确保两个文明之间存在外交关系。
     *
     * TradeLogic / TradeRequest.decline 内部都用 `getDiplomacyManager(other)!!`，若两个文明
     * 还没"相遇"（存档里 diplomacy map 缺条目）就会 NPE —— 交易本身就是接触，所以这里用官方的
     * `getDiplomacyManagerOrMeet`（缺失时建立外交关系，幂等）。
     */
    private static void ensureDiplomacy(Civilization a, Civilization b) {
        if (a == null || b == null) return;
        try { a.getDiplomacyManagerOrMeet(b); } catch (Throwable ignore) {}
        try { b.getDiplomacyManagerOrMeet(a); } catch (Throwable ignore) {}
    }

    /** 玩家→玩家：发起交易（客户端把 Trade 序列化成 JSON 一起送来）。 */
    private static boolean opTradeOffer(Civilization civ, Map<String, Object> data) {
        if (civ == null) return false;
        String toCivId = asStr(data.get("toCiv"));
        String tradeJson = asStr(data.get("tradeJson"));
        if (toCivId == null || tradeJson == null) return false;
        GameInfo gi = GI_TL.get();
        if (gi == null) gi = civ.getGameInfo();
        if (gi == null) return false;
        Civilization to = FsTargeting.findCivByNameOrId(gi, toCivId);
        if (to == null) return false;
        try {
            com.unciv.logic.trade.Trade trade = com.unciv.json.UncivJsonKt.json()
                .fromJson(com.unciv.logic.trade.Trade.class, tradeJson);
            if (trade == null) return false;
            // 同一来源只保留一条待处理请求（重复点"offer"就是替换）
            to.getTradeRequests().removeIf(r -> civ.getCivID().equals(r.getRequestingCiv()));
            com.unciv.logic.trade.TradeRequest req = new com.unciv.logic.trade.TradeRequest();
            req.setRequestingCiv(civ.getCivID());
            req.setTrade(trade);
            to.getTradeRequests().add(req);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] trade.offer failed: " + t.getMessage());
            return false;
        }
    }

    /** 玩家→玩家：接受交易（服务端权威执行资源转移）。 */
    private static boolean opTradeAccept(Civilization civ, GameInfo gi, Map<String, Object> data) {
        if (civ == null) return false;
        String fromCivId = asStr(data.get("fromCiv"));
        com.unciv.logic.trade.TradeRequest req = findTradeRequest(civ, fromCivId);
        if (req == null) return false;
        if (gi == null) gi = civ.getGameInfo();
        Civilization from = FsTargeting.findCivByNameOrId(gi, fromCivId);
        if (from == null) return false;
        ensureDiplomacy(civ, from);
        // 大使馆交易的 transferTrade 会 `from.getCapital()!!` —— 任一方无首都（开局未建都/
        // 都城被占）时整个 accept 直接 NPE，此前是静默失败：申请留着、大使馆没建，
        // AI 下回合照旧再发（"AI 反复发大使馆申请"）。这里先把申请清掉并明确拒绝，
        // AI 侧看到 DeclinedEmbassy 冷却，不再重发。
        boolean embassyInvolved =
                req.getTrade().getOurOffers().stream().anyMatch(o -> o != null && o.getType() == com.unciv.logic.trade.TradeOfferType.Embassy)
             || req.getTrade().getTheirOffers().stream().anyMatch(o -> o != null && o.getType() == com.unciv.logic.trade.TradeOfferType.Embassy);
        if (embassyInvolved && (civ.getCities().isEmpty() || from.getCities().isEmpty())) {
            System.out.println("[op] trade.accept: 大使馆交易被拒 —— " + civ.getCivName() + "/"
                    + from.getCivName() + " 有一方无首都（未建都或都城已失）");
            try {
                req.decline(civ);   // 官方 decline：设置 DeclinedEmbassy 冷却，AI 不会立刻重发
            } catch (Throwable ignored) {}
            civ.getTradeRequests().remove(req);
            return false;
        }
        try {
            com.unciv.logic.trade.TradeLogic tl = new com.unciv.logic.trade.TradeLogic(civ, from);
            tl.getCurrentTrade().set(req.getTrade());
            tl.acceptTrade(true);   // Kotlin 默认参数 applyGifts=true → Java 必须显式传
            civ.getTradeRequests().remove(req);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] trade.accept failed: " + t);
            t.printStackTrace();
            return false;
        }
    }

    /** 玩家→玩家：拒绝交易（走后官方的 decline，会设置外交冷却 flag）。 */
    private static boolean opTradeReject(Civilization civ, GameInfo gi, Map<String, Object> data) {
        if (civ == null) return false;
        String fromCivId = asStr(data.get("fromCiv"));
        com.unciv.logic.trade.TradeRequest req = findTradeRequest(civ, fromCivId);
        if (req == null) return false;
        if (gi == null) gi = civ.getGameInfo();
        ensureDiplomacy(civ, FsTargeting.findCivByNameOrId(gi, fromCivId));
        try {
            req.decline(civ);
            civ.getTradeRequests().remove(req);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] trade.reject failed: " + t);
            t.printStackTrace();
            return false;
        }
    }

    /** 玩家→玩家：撤回自己发出的请求。 */
    private static boolean opTradeRetract(Civilization civ, GameInfo gi, Map<String, Object> data) {
        if (civ == null) return false;
        String toCivId = asStr(data.get("toCiv"));
        if (gi == null) gi = civ.getGameInfo();
        Civilization to = FsTargeting.findCivByNameOrId(gi, toCivId);
        if (to == null) return false;
        try {
            to.getTradeRequests().removeIf(r -> civ.getCivID().equals(r.getRequestingCiv()));
            return true;
        } catch (Throwable t) { return false; }
    }

    /** Receiver chose negotiation: dismiss the current incoming request without declining it. */
    private static boolean opTradeCounter(Civilization civ, Map<String, Object> data) {
        if (civ == null) return false;
        String fromCivId = asStr(data.get("fromCiv"));
        if (fromCivId == null) return false;
        try {
            civ.getTradeRequests().removeIf(r -> fromCivId.equals(r.getRequestingCiv()));
            return true;
        } catch (Throwable t) { return false; }
    }

    // ====================================================================
    // 第二批原版单位动作
    //
    // 原则：**能在核心里跑的就不要在 Java 里抄**。
    //   · 掠夺 / 清除异端 / 触发 unique —— 实现很长（战利品随机结算、圣城状态机、
    //     UniqueTriggerActivation），直接反射执行核心那个 lambda（见 FsCoreActions），
    //     语义与核心永远一致，且"条件不满足"由核心的 takeIf 自己表达。
    //   · 空降 / 空中扫荡 / 在首都加单位 —— 同一套路，单动作工厂。
    //   · 护卫 / 停止移动 / 跳过 / 修路 —— 核心就是一行公开 API，直接调。
    // ====================================================================

    private static final String CLS_ACTIONS_FROM_UNIQUES =
            "com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques";
    private static final String CLS_ACTIONS_RELIGION =
            "com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsReligion";
    private static final String CLS_ACTIONS_PILLAGE =
            "com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsPillage";
    private static final String CLS_ACTIONS_GREAT_PERSON =
            "com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsGreatPerson";

    /**
     * 掠夺 —— 执行核心 `UnitActionsPillage.getPillageAction(unit, tile)` 的 lambda
     * （战利品随机结算 / 按 uniques 加倍 / 双向通知 / 按掠夺治疗 / 建筑被毁 uniques）。
     * 可用性由核心自己的 `.takeIf { unit.hasMovement() && canPillage(...) }` 判定：
     * lambda 为 null 就等于"不可掠夺"，我们返回 false。
     */
    private static boolean opUnitPillage(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        Tile t = u.getTile();
        if (t == null) return false;
        return FsCoreActions.run(CLS_ACTIONS_PILLAGE, "getPillageAction", u, t, 0);
    }

    /**
     * 单动作工厂类：空降 / 空中扫荡 / 在首都加单位。
     * 空降与空中扫荡是**开关**（再点一次取消），这个核心的 lambda 自己处理；
     * 条件（本回合没移动过 / canAttack / 站在己方首都）也由核心的 takeIf 把关。
     */
    private static boolean opUnitCoreAction(Civilization civ, Map<String, Object> data,
                                            String factory, String opName) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        Tile t = u.getTile();
        if (t == null) return false;
        boolean ok = FsCoreActions.run(CLS_ACTIONS_FROM_UNIQUES, factory, u, t, 0);
        if (!ok) System.out.println("[op] " + opName + " 被核心判定不可用（条件不满足 / 没有对应 unique）");
        return ok;
    }

    /** 清除异端 —— 执行核心 lambda（清宗教压力 + 圣城通知 + isBlockedHolyCity 状态机）。 */
    private static boolean opUnitRemoveHeresy(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        Tile t = u.getTile();
        if (t == null) return false;
        return FsCoreActions.run(CLS_ACTIONS_RELIGION, "getRemoveHeresyActions", u, t, 0);
    }

    /**
     * 触发一次性 unique —— 客户端把该 unique 的**文本**一起发上来，
     * 我们在核心的动作列表里按 `associatedUnique.text` 找到同一个再执行它的 lambda。
     * 这样"到底是哪个 unique"不需要我们解析参数，也不会张冠李戴。
     */
    private static boolean opUnitTriggerUnique(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        Tile t = u.getTile();
        if (t == null) return false;
        String uniqueText = asStr(data.get("unique"));
        if (uniqueText == null || uniqueText.isEmpty()) return false;
        return FsCoreActions.runForUnique(CLS_ACTIONS_FROM_UNIQUES, "getTriggerUniqueActions", u, t, uniqueText);
    }

    /** 护卫 / 取消护卫 —— 核心 `UnitActions.addEscortAction` 就是调这两个公开方法。 */
    private static boolean opUnitEscort(Civilization civ, Map<String, Object> data, boolean start) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        try {
            if (start) {
                if (u.getBaseUnit().isAirUnit()) return false;     // 空军不能护卫（对齐核心）
                if (u.getOtherEscortUnit() == null) return false;  // 同格没有可护卫的对象
                if (u.isEscorting()) return false;
                u.startEscorting();
            } else {
                if (!u.isEscorting()) return false;
                u.stopEscorting();
            }
            return true;
        } catch (Throwable t) {
            System.err.println("[op] unit.escort failed: " + t.getMessage());
            return false;
        }
    }

    /** 停止移动 —— 核心就是 `unit.action = null`（单位停在当前格，不再续走 moveTo）。 */
    private static boolean opUnitStopMovement(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        try { u.setAction(null); return true; } catch (Throwable t) { return false; }
    }

    /**
     * 修路到目标格 —— 对齐核心 `OverlayButtonData.connectRoadToTargetTile`，四个字段一次设全：
     *   目的地 / 路径置空 / `action = "Connect road"` / `automated = true`
     * 之后每回合由核心的自动化续走，我们不需要自己算路。
     */
    private static boolean opUnitConnectRoad(Civilization civ, GameInfo gi, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        Tile target = tileFromAnyKey(gi, data);
        if (target == null) return false;
        try {
            u.setAutomatedRoadConnectionDestination(target.getPosition());
            u.setAutomatedRoadConnectionPath(null);
            u.setAction(com.unciv.models.UnitActionType.ConnectRoad.getValue());
            u.setAutomated(true);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] unit.connectRoad failed: " + t.getMessage());
            return false;
        }
    }

    /** 跳过 —— 核心就是翻转 `unit.due`（"这单位本轮不问了"）。 */
    private static boolean opUnitSkip(Civilization civ, Map<String, Object> data) {
        MapUnit u = findUnitByLoc(civ, data);
        if (u == null) return false;
        try {
            if (!u.hasMovement()) return false;
            u.setDue(!u.getDue());
            return true;
        } catch (Throwable t) { return false; }
    }

    // ====================================================================
    // 城邦外交 + 事件选择
    // ====================================================================

    /** 给城邦送金 —— 对齐核心 `CityStateDiplomacyTable`：`cityStateFunctions.receiveGoldGift(civ, amount)`。 */
    private static boolean opCivGiftGold(Civilization civ, GameInfo gi, Map<String, Object> data) {
        if (civ == null) return false;
        Civilization cs = FsTargeting.findCivByNameOrId(gi, asStr(data.get("cityState")));
        Integer amount = asInt(data.get("amount"));
        if (cs == null || amount == null || amount <= 0) return false;
        try {
            if (!cs.isCityState()) return false;
            if (civ.getGold() < amount) return false;   // 先自己挡住，别让核心把金币扣成负数
            cs.getCityStateFunctions().receiveGoldGift(civ, amount);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] civ.giftGold failed: " + t.getMessage());
            return false;
        }
    }

    /** 联姻（买下城邦）—— 核心 `cityStateFunctions.diplomaticMarriage(civ)`（内部扣金 + 转手城市）。 */
    private static boolean opCivDiplomaticMarriage(Civilization civ, GameInfo gi, Map<String, Object> data) {
        if (civ == null) return false;
        Civilization cs = FsTargeting.findCivByNameOrId(gi, asStr(data.get("cityState")));
        if (cs == null) return false;
        try {
            if (!cs.isCityState()) return false;
            int cost = cs.getCityStateFunctions().getDiplomaticMarriageCost();
            if (civ.getGold() < cost) return false;
            cs.getCityStateFunctions().diplomaticMarriage(civ);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] civ.diplomaticMarriage failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * 改善赠礼 —— 固定 200 金（对齐核心 UI 里写死的价），在城邦领地上直接建成
     * "该地块资源对应的改良"。
     */
    private static boolean opCivCityStateImproveGift(Civilization civ, GameInfo gi, Map<String, Object> data) {
        if (civ == null) return false;
        Civilization cs = FsTargeting.findCivByNameOrId(gi, asStr(data.get("cityState")));
        Tile tile = tileFromAnyKey(gi, data);
        String improvementName = asStr(data.get("improvement"));
        if (cs == null || tile == null || improvementName == null) return false;
        final int COST = 200;
        try {
            if (!cs.isCityState()) return false;
            if (civ.getGold() < COST) return false;
            // 只能改"这个城邦自己的"地块
            if (tile.getOwner() == null || !cs.getCivID().equals(tile.getOwner().getCivID())) return false;
            TileImprovement imp = gi.getRuleset().getTileImprovements().get(improvementName);
            if (imp == null) return false;
            // 必须是"该地块资源能被这个改良改善"（对齐核心 getImprovableResourceTiles 的筛选）
            if (tile.getTileResource() == null || !tile.getTileResource().isImprovedBy(improvementName)) return false;

            civ.addGold(-COST);
            tile.stopWorkingOnImprovement();
            // 3 参版本（Kotlin 的默认参数在 Java 侧必须显式补 null）：走 uniques + 通知 + 重算产出
            tile.setImprovement(imp, null, null);
            cs.getCache().updateCivResources();
            return true;
        } catch (Throwable t) {
            System.err.println("[op] civ.cityStateImproveGift failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * 免费伟人选择（伟人选择屏）—— 核心 `GreatPersonManager.chooseFreeGreatPerson(name)`：
     * 从"当前可选的伟人"里挑一个，在首都放出来，并扣掉一次 `freeGreatPeople` 名额。
     *
     * 以前这个屏是纯本地的：单位在本地出现，但服务端根本不知道它存在 ——
     * 增量 sim 不会删它（服务端从没登记过它，它永远不会出现在 removedUnits 里），
     * 于是留下一个"能选中但下任何命令都被拒"的幽灵单位，直到下一次整档才消失。
     */
    private static boolean opCivChooseFreeGreatPerson(Civilization civ, Map<String, Object> data) {
        if (civ == null) return false;
        String unitName = asStr(data.get("unit"));
        if (unitName == null || unitName.isEmpty()) return false;
        try {
            com.unciv.logic.civilization.managers.GreatPersonManager gp = civ.getGreatPeople();
            if (gp == null) return false;
            // 核心内部会再校验一次"这个选项现在可用"，并在放不下单位时返回 null（不扣名额）
            com.unciv.logic.map.mapunit.MapUnit placed = gp.chooseFreeGreatPerson(unitName);
            if (placed == null) {
                System.out.println("[op] civ.chooseFreeGreatPerson: " + unitName + " 当前不可选 / 没地方放");
                return false;
            }
            return true;
        } catch (Throwable t) {
            System.err.println("[op] civ.chooseFreeGreatPerson failed: " + t.getMessage());
            return false;
        }
    }

    // ====================================================================
    // spy.* —— 间谍（派往城市 / 设置行动）
    // ====================================================================

    /** 按名字在**自己**的间谍名单里找一个（`Spy.name` 是稳定主键，"Spy 1"/"Spy 2"…）。 */
    private static com.unciv.models.Spy findSpy(Civilization civ, Map<String, Object> data) {
        if (civ == null) return null;
        String name = asStr(data.get("spy"));
        if (name == null || name.isEmpty()) return null;
        try {
            com.unciv.logic.civilization.managers.EspionageManager em = civ.getEspionageManager();
            if (em == null) return null;
            for (com.unciv.models.Spy s : em.getSpyList()) {
                if (name.equals(s.getName())) return s;
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /**
     * 派间谍 —— 核心 `Spy.moveTo(city)`：设置目的地并把状态改成 `Moving`（1 回合后抵达）。
     * `cityId` 为空表示"回老巢"（`moveTo(null)`）。
     */
    private static boolean opSpyMove(Civilization civ, GameInfo gi, Map<String, Object> data) {
        com.unciv.models.Spy spy = findSpy(civ, data);
        if (spy == null) return false;
        try {
            String cityId = asStr(data.get("cityId"));
            if (cityId == null || cityId.isEmpty()) {
                spy.moveTo((City) null);   // 回老巢
                return true;
            }
            City city = FsTargeting.findCityById(gi, cityId);
            if (city == null) return false;
            // 目标城必须是"已知的"（核心 canMoveTo 会校验：不能派到自己已经有间谍的城/已死的城…）
            if (!spy.canMoveTo(city)) return false;
            spy.moveTo(city);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] spy.move failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * 设置间谍行动 —— 核心 `Spy.setAction(action, turns)`：
     * 政变 `Coup`(1) / 反谍 `CounterIntelligence`(10) / 监视 / 窃取科技 / 操纵选举 …
     * 核心对"需要倒计时的动作"有断言（turns 必须 > 0），我们在这里先挡一道。
     */
    private static boolean opSpySetAction(Civilization civ, Map<String, Object> data) {
        com.unciv.models.Spy spy = findSpy(civ, data);
        if (spy == null) return false;
        String actionName = asStr(data.get("action"));
        if (actionName == null || actionName.isEmpty()) return false;
        try {
            com.unciv.models.SpyAction action;
            try {
                action = com.unciv.models.SpyAction.valueOf(actionName);
            } catch (Throwable bad) {
                System.out.println("[op] spy.setAction: 未知行动 " + actionName);
                return false;
            }
            Integer turnsObj = asInt(data.get("turns"));
            int turns = turnsObj == null ? 0 : turnsObj;
            if (action.getHasCountdownTurns() && turns <= 0) return false;
            spy.setAction(action, turns);
            return true;
        } catch (Throwable t) {
            System.err.println("[op] spy.setAction failed: " + t.getMessage());
            return false;
        }
    }

    /** 事件选择 —— 核心 `EventChoice.triggerChoice(civ, unit)`（原版的 Events 机制，靠 mod 的 Events.json 触发）。 */
    private static boolean opCivEventChoice(Civilization civ, GameInfo gi, Map<String, Object> data) {
        if (civ == null || gi == null) return false;
        String eventName = asStr(data.get("event"));
        String choiceText = asStr(data.get("choice"));
        if (eventName == null || choiceText == null) return false;
        try {
            com.unciv.models.ruleset.Event event = gi.getRuleset().getEvents().get(eventName);
            if (event == null) return false;
            MapUnit u = null;
            com.unciv.logic.civilization.PopupAlert pending = null;
            if (event.getPresentation() == com.unciv.models.ruleset.Event.Presentation.Alert) {
                String expected = eventName;
                Integer unitId = asInt(data.get("unitId"));
                if (unitId != null) {
                    u = civ.getUnits().getUnitById(unitId);
                    if (u == null) return false;
                    expected += "␟unitId=" + unitId;
                }
                for (var alert : civ.getPopupAlerts()) {
                    if (alert.getType() == com.unciv.logic.civilization.AlertType.Event && expected.equals(alert.getValue())) {
                        pending = alert;
                        break;
                    }
                }
                if (pending == null) return false;
            } else if (event.getPresentation() == com.unciv.models.ruleset.Event.Presentation.Floating) {
                var first = gi.getRuleset().getEvents().values().stream()
                        .filter(e -> e.getPresentation() == com.unciv.models.ruleset.Event.Presentation.Floating && e.isAvailable(civ.getState()))
                        .findFirst().orElse(null);
                if (first != event || data.containsKey("unitId")) return false;
            } else return false;
            var context = u == null ? civ.getState() : new com.unciv.models.ruleset.unique.GameContext(u);
            var choices = event.getMatchingChoices(context);
            if (choices == null) return false;
            for (com.unciv.models.ruleset.EventChoice choice : choices) {
                if (!choiceText.equals(choice.getText())) continue;
                // 消费服务端待办后执行，防止同一个弹窗重复领取。
                if (pending != null) civ.getPopupAlerts().remove(pending);
                choice.triggerChoice(civ, u);
                return true;
            }
            System.out.println("[op] civ.eventChoice: 事件 " + eventName + " 里找不到选项 " + choiceText);
            return false;
        } catch (Throwable t) {
            System.err.println("[op] civ.eventChoice failed: " + t.getMessage());
            return false;
        }
    }
}
