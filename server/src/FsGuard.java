// FsGuard.java - 统一操作门控（服务端权威架构 · 层 3）
//
// 对应客户端的 Civilization.canOperate()：一处定义"这个文明能不能操作"，
// 服务端在执行任何 op 之前统一校验，不再让各 op 自己判断。
//
// 规则：
//   - 观战者（spectator）/ barbarian / 已灭亡文明 → 不允许
//   - op 自带的 civ 归属必须与发起者一致 → 防越权操作别人的单位/城市
//
// 注意：这里不做"轮流制"判断。同步回合模式下所有真人玩家同时操作，
// 服务器永远不会因为 currentPlayerCiv 不是某人而拒绝他的 op。
package unciv.fs.local;

import com.unciv.logic.civilization.Civilization;

import java.util.Map;

public final class FsGuard {

    private FsGuard() {}

    /** 该文明是否被允许发起操作。 */
    public static boolean canOperate(Civilization civ) {
        if (civ == null) return false;
        if (civ.isBarbarian()) return false;
        if (civ.isSpectator()) return false;
        if (!civ.isAlive()) return false;
        return true;
    }

    /**
     * op 里声明的目标 civ 是否属于发起者本人。
     * data 里没带 civ 时视为通过（历史客户端兼容）。
     */
    public static boolean ownsTarget(Civilization civ, Map<String, Object> data) {
        if (civ == null) return false;
        if (data == null) return true;
        String claimed = FsTargeting.asStr(data.get("civ"));
        if (claimed == null || claimed.isEmpty()) return true;
        return claimed.equals(civ.getCivName()) || claimed.equals(civ.getCivID());
    }

    /** 目标文明不能是自己（外交类 op 如宣战/和谈）。 */
    public static boolean isOtherCiv(Civilization self, Civilization target) {
        return self != null && target != null && target != self;
    }
}
