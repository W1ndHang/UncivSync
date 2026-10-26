// FsOpResult.java - return type for FsOpHandler.apply that carries diff metadata
// so the server can build a `sim` state-delta message and broadcast it to all
// clients right after the op lands in the authoritative GameInfo.
//
// Without this, the client that did NOT issue the op would only see the effect
// on the next `newTurn` broadcast (i.e. after both players hit turnReady).
// That is what produces the "I killed his unit but he can still attack" symptom.
package unciv.fs.local;

import java.util.LinkedHashSet;
import java.util.Set;

public final class FsOpResult {
    public boolean ok;
    public String reason = "";
    public String op = "";
    public String playerId = "";

    /** Units whose server-side state may have changed and should be re-snapshotted. */
    public final Set<Integer> affectedUnitIds = new LinkedHashSet<>();
    /** Units that no longer exist (e.g. died in an attack, disbanded). */
    public final Set<Integer> removedUnitIds = new LinkedHashSet<>();
    /** City IDs whose server-side state may have changed. */
    public final Set<String> affectedCityIds = new LinkedHashSet<>();
    /** City IDs that no longer exist (e.g. razed / captured-and-deleted). */
    public final Set<String> removedCityIds = new LinkedHashSet<>();
    /** Civs whose state (gold, tech, policies, diplomacy, ...) may have changed. */
    public final Set<String> affectedCivIds = new LinkedHashSet<>();

    /**
     * 单位"变化前的位置"（id -> "x,y"）。
     *
     * 只写进 sim 消息的 fromX/fromY，供客户端在本地还没同步过该单位时按旧位置定位。
     * 场景：客户端本地 gameInfo 与服务端权威存档不是同一份（阶段 1 尚未统一），
     * 收到 sim 时本地单位仍在旧格，只按新坐标找会 miss，导致"看不到别人移动"。
     */
    public final java.util.Map<Integer, String> unitPrevPos = new java.util.LinkedHashMap<>();

    // ========================================================================
    // 战斗结果（仅 unit.attack 填充）
    //
    // Unciv 的战斗伤害来自 GameContext.stateBasedRandom()，其种子是**对象身份 hashCode**
    // 的组合 —— 同一个对象在不同进程里的 hashCode 不同，所以客户端本地算出来的伤害
    // 永远不可能等于服务端的伤害。也就是说战斗**不存在"本地预演"的可能**，只能由
    // 服务端结算，然后把结果（双方伤害 + 最终位置 + 是否阵亡）广播给所有客户端。
    // 客户端据此播放动画并校正状态，不再自己掷骰子。
    // ========================================================================
    public boolean hasBattle = false;
    /** applyAndDiff 由前后哈希对比算出的"状态真的被改过"标记（排除发起者文明恒被标记的噪音）。
     *  失败（ok=false）但中途已改动状态的 op 靠它判定要不要回推 sim —— 否则权威状态与客户端静默分叉。 */
    public boolean mutated = false;
    public int battleAttackerId = -1;
    public int battleDefenderId = -1;      // -1 表示目标是城市
    public String battleDefenderCityId = null;
    public int battleDamageToDefender = 0;
    public int battleDamageToAttacker = 0;
    // 第十九批：战斗可见性判定数据 —— 广播时按接收者视野过滤 battle 段（迷雾里的战斗
    // 不该给视野外的玩家弹扣血提示，对齐核心 MapVisualization.isAttackVisible 语义）。
    public String battleAttackerCivName = null;
    public int battleFromX = -1, battleFromY = -1;   // 攻击发起格
    public int battleToX = -1, battleToY = -1;       // 目标格

    public FsOpResult recordBattle(int attackerId, int defenderId, String defenderCityId,
                                   int dmgToDefender, int dmgToAttacker,
                                   String attackerCivName, int fromX, int fromY, int toX, int toY) {
        this.hasBattle = true;
        this.battleAttackerId = attackerId;
        this.battleDefenderId = defenderId;
        this.battleDefenderCityId = defenderCityId;
        this.battleDamageToDefender = dmgToDefender;
        this.battleDamageToAttacker = dmgToAttacker;
        this.battleAttackerCivName = attackerCivName;
        this.battleFromX = fromX; this.battleFromY = fromY;
        this.battleToX = toX; this.battleToY = toY;
        return this;
    }

    public FsOpResult reset(String op, String playerId) {
        this.op = op;
        this.playerId = playerId;
        this.ok = false;
        this.reason = "";
        this.affectedUnitIds.clear();
        this.removedUnitIds.clear();
        this.affectedCityIds.clear();
        this.removedCityIds.clear();
        this.affectedCivIds.clear();
        this.unitPrevPos.clear();
        this.hasBattle = false;
        this.mutated = false;
        this.battleAttackerId = -1;
        this.battleDefenderId = -1;
        this.battleDefenderCityId = null;
        this.battleDamageToDefender = 0;
        this.battleDamageToAttacker = 0;
        return this;
    }

    public FsOpResult markOk() { this.ok = true; return this; }
    public FsOpResult markFail(String why) { this.ok = false; this.reason = why; return this; }
    public FsOpResult unitAffected(int id) { if (id > 0) affectedUnitIds.add(id); return this; }
    public FsOpResult unitRemoved(int id)  { if (id > 0) { affectedUnitIds.add(id); removedUnitIds.add(id); } return this; }
    public FsOpResult cityAffected(String id) { if (id != null && !id.isEmpty()) affectedCityIds.add(id); return this; }
    public FsOpResult cityRemoved(String id)  { if (id != null && !id.isEmpty()) { affectedCityIds.add(id); removedCityIds.add(id); } return this; }
    public FsOpResult civAffected(String id)  { if (id != null && !id.isEmpty()) affectedCivIds.add(id); return this; }

    /** True if there's anything to broadcast at all. */
    public boolean hasDiff() {
        return !affectedUnitIds.isEmpty() || !affectedCityIds.isEmpty() || !affectedCivIds.isEmpty();
    }
}
