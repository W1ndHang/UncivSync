// FsTurnSync.java - 同步回合推进器（服务端权威 · 回合语义层）
//
// 为什么不用 GameInfo.nextTurn()：
//
//   官方 nextTurn() 是【轮流制】的实现。它内部只对"轮到的那个玩家"调用
//   TurnManager.startTurn()，而"重置单位移动力、城市回合开始结算、生成伟人"等等
//   全都发生在 startTurn() 里。同步回合（所有人同时操作）如果直接复用它，会得到：
//
//     ① 只有被轮到的那一家拿到新移动力，其他玩家单位永远是 0 移动力
//        → 表现为"过了一回合单位还是不能动，往后都没法操控"；
//     ② currentPlayerCiv 每回合轮换，而客户端本地存档与服务端不是同一份时，
//        两边对"当前玩家"的认知不断变化 → 玩家之间互相看不到对方移动；
//     ③ 人类玩家的 TurnManager.endTurn() 从来没被执行过（那本来是玩家点"下一回合"
//        时在自己客户端跑的），于是生产/治疗/黄金时代等回合结算全部缺失。
//
// 同步回合的正确语义（一轮 = 所有真人玩家操作完毕）：
//
//   1. 对每个文明 endTurn()        —— 本回合结算（生产推进、单位治疗、黄金时代…）
//   2. turns += 1                  —— 回合数推进
//   3. 对每个文明 startTurn()      —— 新回合准备（关键：重置单位移动力）
//   4. AI / 城邦 / 蛮族 automateTurn() + endTurn()
//
//   真人单位的自动化（探索 / 多回合行军）**不在推进里跑**：官方语义是玩家自己回合末
//   的"统一移动"按钮（civ.runAutomation op，FsOpHandler 执行），此处抢跑会变成
//   "新回合一开始单位就自己动了"。
//
// 同步回合里没有"当前玩家"的概念，因此不轮换 currentPlayerCiv —— 固定在第一个
// 存活真人身上，让客户端与服务端对 currentPlayer 的认知保持稳定（避免官方那套
// "preview 与本地不一致 → 重新下载存档 → 重建界面"的连锁反应）。
package unciv.fs.local;

import com.unciv.logic.GameInfo;
import com.unciv.logic.civilization.Civilization;
import com.unciv.logic.civilization.managers.TurnManager;

public final class FsTurnSync {

    private FsTurnSync() {}

    /** 测试观测：一次 advance 里 AI/城邦/蛮族 endTurn 的执行次数（应为 AI 文明数 —— 恰一次/家）。 */
    public static final java.util.concurrent.atomic.AtomicInteger AI_ENDTURN_CALLS =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 推进一个同步回合。返回本轮实际处理的文明数（0 表示没有权威存档）。 */
    public static int advance(GameInfo gi) {
        if (gi == null) return 0;
        AI_ENDTURN_CALLS.set(0);
        long t0 = System.currentTimeMillis();
        int humans = 0, ais = 0;
        // 迭代前物化文明清单：处理过程中文明列表可能被结构性修改（玩家退出/消灭时
        // 官方代码会移除条目），直接 for-each 可变列表会 CME 带崩整轮推进。
        java.util.List<Civilization> civs = new java.util.ArrayList<>(gi.getCivilizations());

        // ---- 1) 本回合结算：只对【人类】文明 endTurn() ----
        // AI/城邦/蛮族的 endTurn 由第 4 步在 automateTurn 之后自行执行。此前这里对
        // 所有文明都跑，AI 每个同步回合被 endTurn 两次 —— 科研/文化/金币/信仰/伟人
        // 点数/单位治疗全部双倍结算，表现为"联机 AI 离谱地强"（人类只有一次，不对等）。
        long e0 = System.currentTimeMillis();
        for (Civilization civ : civs) {
            if (civ.isSpectator()) continue;
            if (!civ.isAlive()) continue;
            if (!civ.isHuman()) continue;
            try {
                new TurnManager(civ).endTurn(null);
            } catch (Throwable t) {
                System.err.println("[turnsync] endTurn " + safeCiv(civ) + " failed: " + t.getMessage());
            }
        }
        long e1 = System.currentTimeMillis();

        // ---- 2) 回合数推进 ----
        try {
            gi.setTurns(gi.getTurns() + 1);
            gi.setCurrentTurnStartTime(System.currentTimeMillis());
        } catch (Throwable t) {
            System.err.println("[turnsync] turn++ failed: " + t.getMessage());
        }

        // ---- 3~4) 新回合准备 + AI 自动化 ----
        long s0 = System.currentTimeMillis();
        for (Civilization civ : civs) {
            if (civ.isSpectator()) continue;
            if (!civ.isAlive()) continue;
            TurnManager tm = new TurnManager(civ);
            boolean isHuman = civ.isHuman();

            // 3) 新回合开始（重置移动力、城市回合开始结算，…）
            try {
                tm.startTurn(null);
            } catch (Throwable t) {
                System.err.println("[turnsync] startTurn " + safeCiv(civ) + " failed: " + t.getMessage());
            }

            if (isHuman) {
                humans++;
                // 真人单位的自动化（探索 / 多回合行军）**不在这里跑**：
                // 官方语义是玩家回合末的"统一移动"按钮（NextTurnAction.canMoveAutomatedUnits
                // → civ.runAutomation op → 本类外 FsOpHandler 执行 doAction），服务端在第 5 步
                // 抢跑会变成"新回合一开始单位就自己动了"。官方 startTurn 里那段
                // automatedUnitsMoveOnTurnStart 分支在服务端被强制为 false（见 FsBootstrap）。
            } else {
                ais++;
                // 4) AI / 城邦 / 蛮族
                try { tm.automateTurn(); } catch (Throwable t) {
                    System.err.println("[turnsync] automateTurn " + safeCiv(civ) + " failed: " + t.getMessage());
                }
                try { tm.endTurn(null); AI_ENDTURN_CALLS.incrementAndGet(); } catch (Throwable t) {
                    System.err.println("[turnsync] ai endTurn " + safeCiv(civ) + " failed: " + t.getMessage());
                }
            }
        }
        long s1 = System.currentTimeMillis();

        // AI 交易节流：每个 AI 对每个玩家每回合最多 1 条交易请求（玩家之间不受限）。
        try {
            int throttled = throttleAiTradeRequests(gi);
            if (throttled > 0) System.out.println("[turnsync] AI 交易节流：移除 " + throttled + " 条重复请求");
        } catch (Throwable t) {
            System.err.println("[turnsync] ai trade throttle failed: " + t.getMessage());
        }

        // 保持 currentPlayer 稳定（第一个存活真人）—— 同步回合没有"轮到谁"。
        try {
            for (Civilization civ : civs) {
                if (civ.isHuman() && civ.isAlive() && !civ.isSpectator()) {
                    if (!civ.getCivID().equals(gi.getCurrentPlayer())) {
                        gi.setCurrentPlayer(civ.getCivID());
                        gi.setCurrentPlayerCiv(civ);
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            System.err.println("[turnsync] currentPlayer pin failed: " + t.getMessage());
        }

        long t1 = System.currentTimeMillis();
        // UncivSync: 回合推进不走 op 管道（AI 移动/自动探索/攻城换防都发生在推回合内），
        // 而 applyAndDiff 的视野刷新覆盖不到这里。不刷的话：玩家单位明明贴着城，
        // 推完一回合后服务端视野又是旧的 → 攻击候选再次滤掉目标 → "待在射程里过了
        // 几个回合还是打不了"。单位级视野服务端从未刷过，文明级 updateViewableTiles
        // 只是消费这个过期输入，永远不自愈 —— 必须按官方顺序整体刷新。
        try { FsOpHandler.refreshAllVision(gi); } catch (Throwable ignored) {}
        System.out.println("[timing] syncTurn total=" + (t1 - t0) + "ms (endTurns=" + (e1 - e0)
                + "ms, starts+auto=" + (s1 - s0) + "ms) humans=" + humans + " ai=" + ais);
        return humans + ais;
    }

    /**
     * AI 交易节流：同一个 AI 对同一个玩家，**每种交易类型每回合最多保留 1 条**请求。
     *
     * 官方 AI 有多条自动化路径（宣战 / 和平 / 使馆 / 开放边境 / 资源交换…）都会往对方的
     * `civ.tradeRequests` 里塞请求，一回合可能堆好几条 —— 多人游戏里玩家的交易面板会被刷屏。
     * 种类 = 双方 offer 的外交/资源语义（见 {@link #tradeKindKey}）：同种只留一条，不同种各留一条。
     * 玩家自己发出的请求**不受限制**（按用户要求："限制 AI，玩家不限制"）。
     *
     * @return 被移除的重复请求条数
     */
    public static int throttleAiTradeRequests(GameInfo gi) {
        if (gi == null) return 0;
        int removed = 0;
        for (Civilization receiver : gi.getCivilizations()) {
            if (receiver.isBarbarian() || receiver.isSpectator()) continue;
            java.util.List<com.unciv.logic.trade.TradeRequest> requests = receiver.getTradeRequests();
            if (requests == null || requests.size() < 2) continue;

            java.util.LinkedHashMap<String, com.unciv.logic.trade.TradeRequest> aiKeep = new java.util.LinkedHashMap<>();
            java.util.List<com.unciv.logic.trade.TradeRequest> playerKeep = new java.util.ArrayList<>();
            for (com.unciv.logic.trade.TradeRequest tr : requests) {
                String fromCivId = null;
                com.unciv.logic.trade.Trade trade = null;
                try {
                    fromCivId = tr.getRequestingCiv();
                    trade = tr.getTrade();
                } catch (Throwable ignore) {}
                Civilization from = fromCivId == null ? null : FsTargeting.findCivByNameOrId(gi, fromCivId);
                boolean fromAi = from != null && !from.isHuman();
                if (fromAi) {
                    // 按【种类】去重：同一个 AI 对同一个玩家，同一种交易（资源/开放边境/使馆/和平…）
                    // 只保留最后一条；不同类型可以各留一条。
                    aiKeep.put(fromCivId + "#" + tradeKindKey(trade), tr);
                } else {
                    playerKeep.add(tr);   // 玩家发来的请求不受任何限制
                }
            }
            int newSize = playerKeep.size() + aiKeep.size();
            if (newSize < requests.size()) {
                removed += requests.size() - newSize;
                requests.clear();
                requests.addAll(playerKeep);
                requests.addAll(aiKeep.values());
            }
        }
        return removed;
    }

    /**
     * 交易"种类"键：按 offer 的语义归类，而不是把付款方式也算进 key。
     * 例如"互换使馆"与"付金币换使馆"都属于 Embassy；旧实现把它们当成两种，
     * 于是同一个 AI 仍能在一回合留下两条使馆申请。
     */
    private static String tradeKindKey(com.unciv.logic.trade.Trade t) {
        if (t == null) return "unknown";
        // 付款（金币、资源）只是外交请求的附加条件，不应把同一请求拆成不同种类。
        // 例如“互换使馆”与“付金币换使馆”都必须归为 Embassy。
        java.util.List<com.unciv.logic.trade.TradeOffer> offers = new java.util.ArrayList<>();
        try {
            offers.addAll(t.getOurOffers());
            offers.addAll(t.getTheirOffers());
            for (com.unciv.logic.trade.TradeOfferType preferred : new com.unciv.logic.trade.TradeOfferType[]{
                    com.unciv.logic.trade.TradeOfferType.Embassy,
                    com.unciv.logic.trade.TradeOfferType.PeaceProposal,
                    com.unciv.logic.trade.TradeOfferType.WarDeclaration,
                    com.unciv.logic.trade.TradeOfferType.Treaty,
                    com.unciv.logic.trade.TradeOfferType.Agreement,
                    com.unciv.logic.trade.TradeOfferType.City}) {
                for (com.unciv.logic.trade.TradeOffer o : offers) {
                    if (o != null && o.getType() == preferred) return tradeOfferKind(o);
                }
            }
        } catch (Throwable ignore) {}
        java.util.TreeSet<String> kinds = new java.util.TreeSet<>();
        try {
            for (com.unciv.logic.trade.TradeOffer o : offers) kinds.add(tradeOfferKind(o));
        } catch (Throwable ignore) {}
        return kinds.isEmpty() ? "unknown" : String.join(",", kinds);
    }

    private static String tradeOfferKind(com.unciv.logic.trade.TradeOffer o) {
        if (o == null) return "unknown";
        com.unciv.logic.trade.TradeOfferType type = o.getType();
        if (type == com.unciv.logic.trade.TradeOfferType.Embassy) return "Embassy";
        if (type == com.unciv.logic.trade.TradeOfferType.PeaceProposal) return "Peace";
        if (type == com.unciv.logic.trade.TradeOfferType.WarDeclaration) return "War:" + o.getName();
        if (type == com.unciv.logic.trade.TradeOfferType.Treaty
                || type == com.unciv.logic.trade.TradeOfferType.Agreement)
            return type + ":" + o.getName();
        if (type == com.unciv.logic.trade.TradeOfferType.City) return "City";
        // Resource/gold trades keep their name so different resources do not collapse into one.
        return type + ":" + o.getName();
    }

    private static String safeCiv(Civilization civ) {
        try { return civ.getCivName(); } catch (Throwable t) { return "?"; }
    }
}
