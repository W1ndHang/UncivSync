// FsOpRegistry.java - op 元数据注册表（服务端权威架构 · 层 4）
//
// 每个 op 的"分类"集中在这里声明。
//
// 这样做的收益：
//   - 服务端与客户端对同一个 op 的语义有单一真相源；
//   - 新增 op 时只要在分类里声明一次，管线自动套用门控/回推规则。
//
// 第三十二批起**全面去乐观**（客户端本地永不预执行），所以这里不再有"执行模式"
// 与"退回载荷"的概念：失败一律只回一条不带载荷的 opRejected。
package unciv.fs.local;

public final class FsOpRegistry {

    private FsOpRegistry() {}

    public enum Category {
        MOVE, UNIT_STATE, COMBAT, CITY_STATE, CIV_STATE, TRADE, UNKNOWN,
    }

    public static Category categoryOf(String op) {
        if (op == null) return Category.UNKNOWN;
        if (op.startsWith("unit.")) {
            switch (op) {
                case "unit.move":
                case "unit.swap":
                    return Category.MOVE;
                case "unit.attack":
                    return Category.COMBAT;
                default:
                    return Category.UNIT_STATE;
            }
        }
        if (op.startsWith("city.")) return Category.CITY_STATE;
        if (op.startsWith("civ.")) return Category.CIV_STATE;
        if (op.startsWith("spy.")) return Category.CIV_STATE;   // 间谍状态挂在文明上
        if (op.startsWith("trade.")) return Category.TRADE;
        return Category.UNKNOWN;
    }

    /** 该 op 是否作用于单位（决定是否需要稳定的单位寻址）。 */
    public static boolean isUnitOp(String op) {
        return op != null && op.startsWith("unit.");
    }

    /** 该 op 是否作用于城市。 */
    public static boolean isCityOp(String op) {
        return op != null && op.startsWith("city.");
    }

    /**
     * "结束回合之后不该再改"的 op —— 与 UncivGC 的 TURN_LOCKED_OPS 一致。
     *
     * 客户端本地已有一道门（结束回合后这些 op 不再发出），但那只拦得住守规矩的客户端；
     * 服务端必须有第二道，否则改过的客户端 / 时序错乱都能在结算途中改状态。
     *
     * 语义：玩家**已经点过结束回合**（在 readyPlayers 里）之后，这些 op 一律拒绝。
     *
     * 与客户端 FsOpProtocol.kt 的 TURN_LOCKED_OPS 必须逐字一致 ——
     * FsPlayerActionTest 会反射读取客户端 jar 里的名单做双向比对，单边改动会红。
     */
    public static final java.util.Set<String> TURN_LOCKED_OPS = java.util.Set.of(
            // 城市：生产 / 队列 / 地块 / 专家 / 焦点 / 人口
            "city.setProduction", "city.addToQueue", "city.addToQueueWithTile",
            "city.removeFromQueue", "city.removeFromQueueByName", "city.moveQueueEntry",
            "city.moveEntryToEnd",
            "city.raisePriority", "city.lowerPriority", "city.disableConstruction",
            "city.workTile", "city.stopWorkTile", "city.lockTile", "city.unlockTile",
            "city.assignSpecialist", "city.reassignPopulation", "city.setFocus",
            "city.enableManualSpecialists", "city.disableManualSpecialists",
            "city.toggleAvoidGrowth",
            // 城市：领土 / 存续 / 命名 / 生产偏好 / 金币购买
            "city.annex", "city.setPuppet", "city.setRazing", "city.buyTile", "city.buy", "city.sellBuilding", "city.rename",
            "city.setUnitSavedPromotion", "city.saveUnitPromotions",
            // 文明：全局生产禁用 / 科技 / 政策 / 信条
            "civ.setConstructionDisabled", "civ.chooseTech", "civ.choosePolicy",
            "civ.chooseBeliefs", "civ.setGoldToScience", "civ.runAutomation", "civ.setCityStateProtection", "civ.cityStatePeace"
    );

    public static boolean isTurnLocked(String op) {
        return op != null && TURN_LOCKED_OPS.contains(op);
    }
}
