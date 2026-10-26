// FsOpPipeline.java - 统一 op 管线（服务端权威架构 · 层 4）
//
// 所有客户端 op 都经过这一条管线：
//
//   ① 事件回显（peerOp 广播给其他玩家）
//   ② 门控校验（FsGuard：谁能操作 / 目标归属）
//   ③ 权威执行（FsOpHandler.applyAndDiff → 落到唯一权威 GameInfo）
//   ④ 计算 diff（哪些单位/城市/文明变了）
//   ⑤ 回推 sim（成功）或 单发退回（失败，只带 op 与原因）
//
// FsServer.handleOp 只负责"解析 + 调用本管线 + 发送结果 + 打日志"，
// 不再自己判断 op 语义 —— 语义集中在 FsOpRegistry，执行集中在管线。
package unciv.fs.local;

import com.unciv.logic.GameInfo;
import com.unciv.logic.civilization.Civilization;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FsOpPipeline {

    private FsOpPipeline() {}

    /** 一条 op 走完管线后的完整结果（JSON 已编码，FsServer 直接发送）。 */
    public static final class Outcome {
        public final String op;
        public final String playerId;
        /** 门控是否放行。 */
        public boolean allowed = true;
        /** 权威执行是否成功。 */
        public boolean applied = false;
        /** 纯记录类 op（无 GameInfo 时的降级路径）。 */
        public boolean noOp = false;
        public String reason = "";
        /** Revision of the complete authoritative state carried by simJson. */
        public long revision = -1L;

        public String peerEchoJson = null;   // 广播给除发起者外的人
        public String simJson = null;        // 广播给所有人（权威状态增量）
        // 第十九批：战斗结果不进共享 sim（迷雾过滤），FsServer 按接收者视野追加 battle 段
        public FsOpResult battleDiff = null;
        public String rejectJson = null;     // 单发给发起者（失败/冲突退回）

        Outcome(String op, String playerId) {
            this.op = op;
            this.playerId = playerId;
        }

        void reject(String why) {
            this.allowed = false;
            this.applied = false;
            this.reason = why;
        }
    }

    /**
     * 处理一条 op。调用方（FsServer）负责把 Outcome 里的 JSON 真正发出去。
     * 本方法内部会自己获取 r.stateLock。
     */
    public static Outcome process(FsRoom r, FsSession s, String op, Map<String, Object> data) {
        Outcome out = new Outcome(op, s.playerId);
        Map<String, Object> payload = data == null ? new LinkedHashMap<>() : data;

        // ① 回显给其他玩家：让他们知道有人操作了（UI 即时反馈 / 调试）
        Map<String, Object> echo = new LinkedHashMap<>();
        echo.put("type", FsProtocol.T_PEER_OP);
        echo.put("playerId", s.playerId);
        echo.put("nickname", s.nickname);
        echo.put("op", op);
        echo.put("data", payload);
        out.peerEchoJson = FsJson.encode(echo);

        GameInfo gi = r.gameState != null ? r.gameState.gameInfo : null;

        // ② 门控校验（服务端自己的一道 —— 客户端 sendOpChecked 那道只是 UX，
        //    改过的客户端 / 时序错乱都能绕过，所以服务端必须独立拦一遍）
        if (r.paused) {
            out.reject("game paused");
            return out;
        }
        if (r.settling) {
            // 结算中：本回合已经全员结束、服务端正在跑完整一轮，此刻任何改状态的 op 都会
            // 和结算结果打架（典型：结算里刚重置的移动力被 op 又消耗掉）。
            out.reject("server settling");
            return out;
        }
        if (r.readyPlayers.contains(s.playerId) && FsOpRegistry.isTurnLocked(op)) {
            // 已点结束回合之后再发回合锁 op —— 与客户端 TURN_LOCKED_OPS 同一份白名单。
            // 但 civ.choosePolicy / civ.chooseBeliefs 是"文明级抉择"，服务端回合模拟不会自愈
            // （科技会被 server-sim 自动补完，这两条不会），一旦直接拒绝就永久丢失，
            // 表现成"政策/信条选了却不记录"。所以这两条不拒绝，而是入队到 turnLockedQueue，
            // 在下一回合开始（doAdvanceTurn 的 drainTurnLocked）统一应用 —— 既保留
            // "结束回合期间不改状态"的语义，又实现"补操作"（结束后再选也能记上）。
            if ("civ.choosePolicy".equals(op) || "civ.chooseBeliefs".equals(op)) {
                r.enqueueTurnLocked(new FsRoom.TurnLockedOp(s.playerId, op, payload));
                out.allowed = true;
                out.applied = true;
                out.noOp = true;
                out.reason = "deferred to next turn (turn-locked backfill)";
                return out;
            }
            out.reject("turn already ended");
            return out;
        }

        // 只有权威 GameInfo 已加载时，下面这些归属校验才有意义
        if (gi != null) {
            Civilization civ = FsTargeting.findCivByPlayerId(gi, s.playerId);
            if (!FsGuard.canOperate(civ)) {
                out.reject("not allowed to operate");
                return out;
            }
            // 单位/城市类 op 必须作用于自己的东西（防越权 + 防 id 漂移错位）
            if (FsOpRegistry.isUnitOp(op) || FsOpRegistry.isCityOp(op)) {
                if (!FsGuard.ownsTarget(civ, payload)) {
                    out.reject("target civ mismatch");
                    return out;
                }
            }
        }

        // ③ 权威执行 + ④ diff
        FsOpResult diff = null;
        r.stateLock.lock();
        try {
            if (gi != null) {
                FsOpHandler.setCurrentGameInfo(gi);
                try {
                    diff = FsOpHandler.applyAndDiff(gi, s.playerId, op, payload);
                } finally {
                    FsOpHandler.setCurrentGameInfo(null);
                }
            } else if (r.gameState != null) {
                // 还没有权威存档时的降级路径：只记录，不做状态变更
                r.gameState.applyOp(s.playerId, op, payload);
                out.noOp = true;
                out.applied = true;
                return out;
            }

            if (diff != null) {
                out.applied = diff.ok;
                out.reason = diff.reason == null ? "" : diff.reason;
                if (diff.ok) {
                    out.revision = ++r.stateRevision;
                    if (r.gameState != null) {
                        out.simJson = r.gameState.buildSimMessage(diff);
                        if (diff.hasBattle) out.battleDiff = diff;
                    }
                } else {
                    out.rejectJson = buildReject(s, op, diff);
                    // 失败但状态已被部分改动（apply 中途抛异常）：必须照常回推 sim，
                    // 让客户端对齐服务端现状；否则变更只在服务端内存里，静默分叉。
                    // 干净失败（mutated=false）不回推，避免每次拒绝都广播一次快照。
                    if (diff.mutated) {
                        out.revision = ++r.stateRevision;
                        if (r.gameState != null) {
                            out.simJson = r.gameState.buildSimMessage(diff);
                            if (diff.hasBattle) out.battleDiff = diff;
                        }
                    }
                }
            }
        } finally {
            r.stateLock.unlock();
        }

        return out;
    }

    /** 失败时给发起者的单发消息（只带 op 与原因；乐观回滚机制已整体移除，不再带坐标载荷）。 */
    private static String buildReject(FsSession s, String op, FsOpResult diff) {
        Map<String, Object> reject = new LinkedHashMap<>();
        reject.put("type", FsProtocol.T_OP_REJECTED);
        reject.put("playerId", s.playerId);
        reject.put("op", op);
        reject.put("reason", diff.reason == null || diff.reason.isEmpty() ? "rejected" : diff.reason);
        return FsJson.encode(reject);
    }
}
