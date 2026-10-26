// FsCoreActions.java - 直接执行 Unciv 核心自己的 UnitAction lambda。
//
// 为什么需要这东西：
//   Unciv 把每个单位动作的实现写在"返回 UnitAction 的工厂方法"里（`action` 是个 lambda）。
//   其中三个的实现在 Java 里重抄一遍既长又危险：
//     · 掠夺 —— 战利品随机结算、按 uniques 加倍、通知、按掠夺 healing、建筑被毁 uniques
//     · 清除异端 —— 圣城通知 + isBlockedHolyCity 状态机
//     · 触发 unique —— UniqueTriggerActivation + 多倍率 + side effects
//   抄一遍 = 以后核心改了、我们不跟。所以这里**把那个 lambda 反射拿出来直接执行**，
//   语义与核心永远一致。
//
// 两个技术点：
//   1. 那些工厂方法是 Kotlin `internal`，JVM 名会带模块后缀（如
//      `getPillageAction$Unciv_4_21_19_core`）。模块名跟着构建变，所以**按前缀找**，不写死后缀。
//   2. 核心用 `action == null` 表达"这个动作此刻条件不满足"（源码里全是 `.takeIf { ... }`），
//      所以 invoke() 返回 false 就等于"核心自己判定不可用" —— 天然的校验。
//
// 代价是反射，所以只用在"手写成本高"的动作上；其余（护卫/跳过/停止移动…）都是
// 一行公开 API，直接调用即可。
package unciv.fs.local;

import com.unciv.logic.map.mapunit.MapUnit;
import com.unciv.logic.map.tile.Tile;
import com.unciv.models.UnitAction;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class FsCoreActions {
    private FsCoreActions() {}

    /** 在 owner 上按名字前缀找 (MapUnit, Tile) -> UnitAction | Sequence<UnitAction> 的方法。 */
    private static Method findFactory(Class<?> owner, String namePrefix) {
        for (Method m : owner.getMethods()) {
            String n = m.getName();
            if (!n.equals(namePrefix) && !n.startsWith(namePrefix + "$")) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 2) continue;
            if (!p[0].isAssignableFrom(MapUnit.class)) continue;
            if (!p[1].isAssignableFrom(Tile.class)) continue;
            Class<?> r = m.getReturnType();
            if (!UnitAction.class.isAssignableFrom(r)
                    && !kotlin.sequences.Sequence.class.isAssignableFrom(r)) continue;
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    private static Object ownerInstance(Class<?> cls, Method m) throws Exception {
        if (Modifier.isStatic(m.getModifiers())) return null;
        Field inst = cls.getField("INSTANCE");   // Kotlin object
        return inst.get(null);
    }

    /** 取核心工厂给出的动作列表（单个或序列都摊平成 List）。 */
    static List<UnitAction> actions(String ownerClassName, String namePrefix, MapUnit u, Tile t) {
        List<UnitAction> out = new ArrayList<>();
        try {
            Class<?> owner = Class.forName(ownerClassName);
            Method m = findFactory(owner, namePrefix);
            if (m == null) {
                System.out.println("[core-action] 找不到工厂 " + ownerClassName + "#" + namePrefix + "*");
                return out;
            }
            Object res = m.invoke(ownerInstance(owner, m), u, t);
            if (res instanceof UnitAction) {
                out.add((UnitAction) res);
            } else if (res instanceof kotlin.sequences.Sequence) {
                Iterator<?> it = ((kotlin.sequences.Sequence<?>) res).iterator();
                while (it.hasNext()) {
                    Object o = it.next();
                    if (o instanceof UnitAction) out.add((UnitAction) o);
                }
            }
        } catch (Throwable e) {
            System.err.println("[core-action] " + namePrefix + " 调用失败: " + e);
        }
        return out;
    }

    /** 执行核心的 lambda。返回 false 表示核心判定该动作不可用（action == null）或执行出错。 */
    static boolean invoke(UnitAction a) {
        if (a == null) return false;
        try {
            kotlin.jvm.functions.Function0<?> fn = a.getAction();
            if (fn == null) return false;
            fn.invoke();
            return true;
        } catch (Throwable e) {
            System.err.println("[core-action] 执行失败: " + e);
            return false;
        }
    }

    /** 跑第 index 个动作（0 表示"唯一的那个"）。 */
    static boolean run(String ownerClassName, String namePrefix, MapUnit u, Tile t, int index) {
        List<UnitAction> list = actions(ownerClassName, namePrefix, u, t);
        if (index < 0 || index >= list.size()) return false;
        return invoke(list.get(index));
    }

    /** 在动作列表里按 `associatedUnique.text` 挑一个执行（TriggerUnique 用）。 */
    static boolean runForUnique(String ownerClassName, String namePrefix, MapUnit u, Tile t, String uniqueText) {
        for (UnitAction a : actions(ownerClassName, namePrefix, u, t)) {
            try {
                com.unciv.models.ruleset.unique.Unique uniq = a.getAssociatedUnique();
                if (uniq == null) continue;
                if (uniqueText == null || uniqueText.isEmpty() || uniqueText.equals(uniq.getText())) {
                    return invoke(a);
                }
            } catch (Throwable ignore) {}
        }
        return false;
    }

    /**
     * 按自定义条件挑一个动作执行（例如"标题里带 [Farm]、且来自这条 unique"）。
     *
     * 用在"一个工厂能产出好几个动作、而客户端只点了其中一个"的场景
     * （`CreateImprovement`：水改良 + 每条 ConstructImprovementInstantly 各一个）。
     */
    static boolean runMatching(String ownerClassName, String namePrefix, MapUnit u, Tile t,
                               java.util.function.Predicate<UnitAction> filter) {
        for (UnitAction a : actions(ownerClassName, namePrefix, u, t)) {
            try {
                if (filter.test(a)) return invoke(a);
            } catch (Throwable ignore) {}
        }
        return false;
    }
}
