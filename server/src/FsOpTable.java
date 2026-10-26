// FsOpTable.java - op 注册表（服务端"能力集合"的单一真相源）
//
// 为什么要有这张表：
//
//   以前 op 的分发是一个 87 分支的 switch，藏在 FsOpHandler.dispatch 里。它有两个问题：
//     ① **能力集合不可枚举** —— 没人能一眼说出"服务端到底支持哪些 op"，
//        于是"客户端发了一个服务端没实现的 op"只能等玩家踩到才发现；
//     ② 新增动作要在 switch 里插一行，改起来容易和别的分支冲突。
//
//   现在 op 名 → 作用目标 → 处理体 全部登记在这里：
//     - FsOpTable.ops() 就是服务端的能力集合，可以直接和客户端 jar 里的 op 字面量比对
//       （见 OpsCoverage 工具），漏一个立刻可见；
//     - 未知 op 会被显式记录（unknownOps()），而不是落进 default 悄悄吞掉；
//     - 新增动作 = 加一行 register(...)。
//
// 注意：op 名是**人造命名约定**（`目标.动词`），不是 Unciv 的方法名
// （例如 city.buyTile 的真实入口是 city.expansion.buyTile(tile)，civ.choosePolicy
//  走的是政策管理器）。所以"op → 核心 API"的映射是语义的，必须人工写一次 ——
// 这张表的价值是让覆盖可枚举/可自检，不是自动推导。
package unciv.fs.local;

import com.unciv.logic.GameInfo;
import com.unciv.logic.civilization.Civilization;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class FsOpTable {

    private FsOpTable() {}

    /** op 的作用目标（决定走哪套统一寻址）。 */
    public enum Target {
        UNIT, CITY, CIV, NONE
    }

    /** 处理体。统一签名，内部自己从 data 里取参数。 */
    @FunctionalInterface
    public interface OpBody {
        boolean run(Civilization civ, GameInfo gi, Map<String, Object> data);
    }

    public static final class Entry {
        public final String op;
        public final Target target;
        public final OpBody body;

        Entry(String op, Target target, OpBody body) {
            this.op = op;
            this.target = target;
            this.body = body;
        }
    }

    private static final LinkedHashMap<String, Entry> TABLE = new LinkedHashMap<>();

    /** 启动以来收到过的、表里没有的 op（run 时收集，供 OpsCoverage / 日志用）。 */
    private static final Set<String> UNKNOWN = Collections.synchronizedSet(new TreeSet<String>());

    /** 注册一个 op。重复注册直接抛 —— 免得两个分支互相覆盖还看不出来。 */
    public static void register(String op, Target target, OpBody body) {
        if (op == null || op.isEmpty()) throw new IllegalArgumentException("empty op");
        if (body == null) throw new IllegalArgumentException("null body for " + op);
        if (TABLE.containsKey(op)) throw new IllegalStateException("duplicate op registration: " + op);
        TABLE.put(op, new Entry(op, target, body));
    }

    private static volatile boolean loaded = false;

    /**
     * 触发注册表的填充。
     *
     * 注册内容写在 FsOpHandler 的静态块里（那些处理体是它的静态方法），而 JVM 的类初始化是懒的——
     * 所以任何"不碰 FsOpHandler、只读表"的调用方（例如 OpsCoverage 工具）必须先调这个，
     * 否则会读到一张空表（踩过：覆盖率工具报"服务端支持 0 个"）。
     */
    public static void ensureLoaded() {
        if (loaded) return;
        synchronized (FsOpTable.class) {
            if (loaded) return;
            try {
                Class.forName("unciv.fs.local.FsOpHandler");
            } catch (Throwable t) {
                System.err.println("[op-table] 加载 FsOpHandler 失败: " + t.getMessage());
            }
            loaded = true;
        }
    }

    public static Entry get(String op) {
        ensureLoaded();
        return op == null ? null : TABLE.get(op);
    }

    public static boolean supports(String op) {
        if (op == null) return false;
        ensureLoaded();
        return TABLE.containsKey(op);
    }

    /** 服务端能力集合（不可变）。 */
    public static Set<String> ops() {
        ensureLoaded();
        return Collections.unmodifiableSet(TABLE.keySet());
    }

    public static int size() {
        ensureLoaded();
        return TABLE.size();
    }

    public static Target targetOf(String op) {
        Entry e = get(op);
        return e == null ? Target.NONE : e.target;
    }

    /** 记录一个未知 op（供运行期排查"客户端发了服务端不认识的动作"）。 */
    public static void recordUnknown(String op) {
        if (op != null && !op.isEmpty()) UNKNOWN.add(op);
    }

    public static Set<String> unknownOps() {
        synchronized (UNKNOWN) {
            return new TreeSet<>(UNKNOWN);
        }
    }

    /** 自检：表里每个 op 都应能被 FsOpRegistry 归类（否则多半是 op 名写错了）。 */
    public static String selfCheck() {
        StringBuilder sb = new StringBuilder();
        int bad = 0;
        for (String op : TABLE.keySet()) {
            if (FsOpRegistry.categoryOf(op) == FsOpRegistry.Category.UNKNOWN) {
                sb.append("  [!] 未归类 op: ").append(op).append('\n');
                bad++;
            }
        }
        sb.insert(0, "[op-table] 注册 " + TABLE.size() + " 个 op，未归类 " + bad + " 个\n");
        return sb.toString();
    }
}
