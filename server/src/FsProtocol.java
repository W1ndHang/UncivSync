// FsProtocol.java - FrameSync protocol constants and message builders
package unciv.fs.local;

import java.util.*;

public final class FsProtocol {
    private FsProtocol() {}

    /** WS URL: /ws?gameId=...&playerId=...&nickname=...&playerUUID=... */
    public static final String WS_PATH = "/ws";

    /**
     * 服务端行为版本 —— 启动横幅会打印，用来确认"跑的是哪一版"。
     *
     * 每次改动了"服务端权威行为/协议"就 +1（不用跟 git 之类挂钩，够用即可）：
     *  .1  同步回合推进器 FsTurnSync（全员 endTurn/startTurn）
     *  .2  整档下发 + 大帧修复（64KB）
     *  .3  战斗服务端唯一结算 + 攻击/轰击次数校验 + 政策/科技随 sim 下发
     *  .4  对齐 UncivGC：① 常规回合**不再整档下发**（改为地块投影 + 世界签名判定）
     *      ② 移动改为服务端裁决（客户端不再本地预移动 / 不再弹回）
     *      ③ 服务端侧四道门（暂停 / 结算中 / 已结束回合的回合锁白名单）
     *  .5  回合体验：暂停改成"任意真人可暂停/恢复"（原限房主，客户端按不动）；结算锁时长改读
     *      GameParameters.fsSettleLockSeconds（原硬编码 1 秒）；客户端侧补四道门 + 就绪补发 + 结算遮罩
     *  .6  op 分发从"87 分支 switch"改成 **注册表**（FsOpTable，单一真相源）；
     *      新增 OpsCoverage 覆盖率自检工具；未知 op 不再悄悄吞掉而是显式记录。
     *      行为等价重构，无协议变更。
     *  .7  ① **通知投影**：civ 快照带 `notifications`（核心自带的事件通道），
     *         奇观被抢 / 遗迹奖励 / 城陷 / 伟人诞生 等全部自动送达，不再需要类型化事件消息；
     *      ② 客户端 sim **改为在 GL 线程上成批应用**（对齐 UncivGC 的 applyStateLoop），
     *         不再在网络协程里直接改 GameInfo。
     *  .8  第一批「把 stub 写成真实现」+ 接线（见 OpsCoverage 的 ② 清单）：
     *      unit.hurryResearch/Policy/Wonder/Building（原来**忽略 kind** 只调 doAction()）、
     *      unit.foundReligion / enhanceReligion、unit.setUp（原来也是 doAction()）；
     *      unit.transform 拆掉会破坏单位的 `setBaseUnit(null)`（改为明确拒绝）。
     *      客户端已接线这 6 个；覆盖率的 ② 从 22 降到 16。
     *  .9  原版收口 + 第二批真实现：
     *      ① **移除 tradeRoute.***（原版 Unciv 没有贸易路线玩法，那是 UncivGC fork 的东西）
     *         以及两个纯占位 op（civ.voteResultSeen / civ.nextTurnReady）；
     *      ② 新增 `unit.buildImprovement` / `unit.stopImprovement`（工人建造与取消，
     *         对齐核心 `startWorkingOnImprovement` / `stopWorkingOnImprovement`）；
     *      ③ 新增 `city.buy`（金币购买建筑/单位，对齐核心 `CityConstructions.purchaseConstruction`，
     *         并在我们这侧补上核心**缺少**的余额校验）；
     *      ④ `unit.repair` / `unit.createImprovement` 从空壳写成真实现；
     *      ⑤ 客户端接线上述 4 条。
     *      覆盖率：客户端会发 71 → 75，② 从 16 降到 9，① 始终 0。
     *  .10 把"原版 op"补齐到 0 缺口（除 Civ5/GC fork 专有玩法外，服务端能力 = 客户端能发）：
     *      ① 单位侧第二批：pillage / paradrop / airSweep / addInCapital / removeHeresy /
     *         triggerUnique / escort / stopEscort / stopMovement / connectRoad / skip
     *         —— 复杂的那几个**直接执行核心自己的 UnitAction lambda**（FsCoreActions，零逻辑重复）；
     *      ② 城邦外交：giftGold / diplomaticMarriage / cityStateImproveGift；
     *      ③ 事件选择：eventChoice（执行 EventChoice.triggerChoice）；
     *      ④ 第三批：spreadReligion / tradeMission / transform / createImprovement 从空壳/拒绝改成
     *         执行核心 lambda；cityConquerChoice（并吞/傀儡/烧毁/归还/摧毁）+ 新增 city.setPuppet；
     *         diplomaticVote（外交胜利投票）从空壳改成 diplomaticVoteForCiv；
     *      ⑤ 删除死别名 civ.research。
     *      覆盖率：客户端会发 75 → 97，服务端 100 → 99；① 始终 0；
     *      ② 只剩 civ.meet / civ.makePeace，两者都在 OpsCoverage 里写了"引擎驱动、无 UI"的解释。
     *  .11 间谍与免费伟人接线：
     *      ① 新增 `spy.move` / `spy.setAction`（对齐核心 `Spy.moveTo` / `Spy.setAction`），
     *         并把 `spies` 加进 civ 投影（全量名单，客户端按 name 对齐）；
     *      ② 新增 `civ.chooseFreeGreatPerson`（伟人选择屏），并把 `greatPeople`
     *         （freeGreatPeople / mayaLimitedFreeGP / longCountGPPool）加进 civ 投影 ——
     *         这一项是必需的，因为"选择屏该不该弹"的判据就是 freeGreatPeople > 0。
     *      覆盖率：客户端会发 97 → 100，服务端 99 → 102；① 始终 0，② 仍是那两个引擎驱动的。
     *  .12 联机体验修复（本批，无新增 op，覆盖率不变：客户端会发 100 / 服务端 102 / ①=0 / ②=2）：
     *      ① 科技进度跟随回合推进：civ 投影新增 `techsInProgress` / `overflowScience` / `freeTechs`
     *         （客户端只渲染不结算，服务端不推它，进度条就永远停在读档那一刻）；
     *      ② 暂停真正停表：`handlePause` 现在会取消已排程的倒计时任务（之前只"不排新的"，
     *         残留任务到期照样强推回合）；
     *      ③ 掉线不再卡死回合门槛：`knownHumanPlayers` 是只增不减的名册，掉线超过 2 分钟的
     *         真人不再计入"必须就绪"门槛（回来自动重新计入），避免整局推不动；
     *      ④ 政策按钮始终可见（联机里偶尔因两边不同步而凭空消失）；
     *      ⑤ "我是否已结束回合"以服务端 readyPlayers 为准（之前只在回合号前进时清，
     *         掉线/重连/整档后两边不一致 → 表现为"无法撤回 / 建造不了东西"）；
     *      ⑥ op 被拒时把服务端给的原因显示出来（之前城市生产之类被拒时玩家完全看不到）。
     *
     *   2026-09-12.13 —— 投影补全（消掉"完成之后客户端没收到"一类 bug，补上 GC 差距分析里的缺口）：
     *      ① 城市新增投影 builtBuildings + inProgressConstructions：奇观/建筑造完客户端才真正"装上去"，
     *         不再重复建造、不再卡下一步（与科技"只发数量不发集合"同源）；
     *      ② 科技新增投影 techsResearched 集合：科技树已研究项才正确高亮，避免重复选已完成的科技；
     *      ③ 黄金时代状态（gap #4）：isGoldenAge / 剩余回合 / 累计幸福；
     *      ④ 城邦影响力 + 任务（gap #5）：以城邦为单位投影 influence 与 assignedQuests；
     *      ⑤ 胜利进度（gap #6）：每个启用胜利类型的里程碑完成数/总数，对手面板/排行榜实时刷新。
     *         （宗教/信仰仍按计划走整档兜底，不在增量投影内。）
     *   2026-09-30.14 —— 健壮性批（无协议字段变更，客户端无需同步升级也可混跑，但建议同版）：
     *      ① op 失败但状态已被部分改动（apply 中途抛异常）时也构建 diff 并回推 sim +
     *         落盘（新增 FsOpResult.mutated 精确标记）—— 以前这类变更只在服务端内存里，
     *         客户端静默分叉到下回合整档才对齐；
     *      ② 回合推进链兜底：doAdvanceTurn 的 nextTurn 抛异常也走完全程（清 ready、落盘、
     *         广播 saveUpdated），readyPlayers.clear() 移入 finally —— 修"半结算回合被
     *         下一次就绪重推（生产/科研双倍结算）"；FsTurnSync 单位/文明清单物化快照防 CME；
     *      ③ FsRoom.ensureGameStateLoaded 的 synchronized(stateLock) 改为 lock()/unlock()
     *         （两种锁互不相斥，重连风暴下 gameState 会被并发覆盖）；
     *      ④ FsJson 解析加 256 层深度上限；FsSession 消息边界 catch Throwable；
     *      ⑤ 存档原子写（temp + rename）；gzip 解压 256MB 上限。
     *   2026-09-30.15 —— 对齐官方执行语义（三项）：
     *      ① sim 的单位/城市 obj 段改【全字段序列化】（usePrototypes=false）：官方序列化
     *         默认省略等于默认值的字段，行动力=0 这类有意义的默认状态在序列化层就丢了
     *         （升级后客户端还能动的根因）；客户端 splice 同步改为"按 JSON 在场与否"搬运；
     *      ② 回合推进不再替真人跑单位自动化（原第 5 步）：对齐官方"回合末统一移动按钮"
     *         语义（civ.runAutomation op）；服务端强制 automatedUnitsMoveOnTurnStart=false；
     *      ③ 客户端 sim 应用顺序修正：removedUnits 先于单位位置循环 —— 击杀后胜者踏格
     *         不再被本地未移除的阵亡者挡住（官方 moveToTile 拒绝是静默 return）。
     *   2026-09-30.16 —— 投影通道修复（无协议结构变更，客户端需同版升级）：
     *      ① 单位快照恢复 action / instanceName 显式通道（空串 = null）：可空字符串在官方
     *         序列化里被 null-省略，"停止探索"这类置 null 的状态以前永远同步不到客户端；
     *      ② （客户端侧）religionManager / questManager 接受字符串形态 —— 服务端发的本是
     *         序列化字符串，客户端旧代码 as? JsonObject 强转失败静默跳过，宗教状态从不更新
     *         （"建完万神殿/宗教按钮不消退、再点被拒、回合卡死"的根因）。
     *   2026-09-30.17 —— 动态回合基准时长调整：fsTurnTime 默认 60s -> 90s，服务端
     *      排程去掉 ×1.5 补偿 hack，参数值即真实基准（存档里已存旧值 60 的对局按 60s 跑，
     *      新对局 90s）。生产结算位置不变：只在回合推进（endTurn）时计算，op 仅改队列。
     *   2026-09-30.18 —— 新增【管理页】http://<host>:<port>/admin：
     *      双栏布局（左导航+总览+重启，右侧房间卡片网格→详情+每步日志）；
     *      每房间结构化事件环形日志（op/回合/玩家/系统，500 条上限）；
     *      房间独立运行时长；--admin-pass 开启远程管理（缺省仅本机回环可访问）；
     *      /admin/api/restart 优雅重启（先落盘再以同命令行拉起新进程）；
     *      房间日志改为【结构化事件】（事件码+参数，无自然语言），管理页按中/英
     *      语言切换渲染 —— 页面全部文案双语（左上角切换按钮，选择持久化）。
     */
    // 2026-09-30 起版本号改为纯数字递增（用户要求：不带日期），此前为 "日期.N" 形式
    // 2026-10-06 起改用三段发行版本号（用户要求）：1.0.00 起，最后一位十进制递增。
    // 1.0.00：视野修复（op 出口 + 回合推进统一刷新单位级/文明级视野）、大使馆 accept
    //         无首都 NPE 修复、攻城弹窗、政策弹窗 dismiss 同步、赠送单位修复、
    //         暂停/观战权限、回合时长 y=120+3x、掉线宽限 60s、QQ 群内容清理。
    // 1.0.01：创立宗教修复 —— 核心前置不满足时静默 return 导致 op 假成功（先知被白吃、
    //         宗教状态不推进、"创立宗教"选项挂到回合超时）；op 层逐项校验显式拒绝 + 状态
    //         推进确认；宗教 reject 带日志。
    // 1.0.02：同类排查 —— 单位换位(unit.swap)同样受核心静默 return 影响（目的地无可换
    //         单位时假成功），op 层加存在性校验 + 换位结果确认；拒绝带日志。
    public static final String SERVER_BUILD = "1.0.02";

    // Server -> Client message types
    public static final String T_WELCOME = "welcome";
    public static final String T_STATE = "state";
    public static final String T_TURN_STATUS = "turnStatus";
    public static final String T_PAUSE_NOTICE = "pauseNotice";
    public static final String T_RESUME_NOTICE = "resumeNotice";
    public static final String T_ONLINE_PLAYERS = "onlinePlayers";
    public static final String T_PONG = "pong";
    public static final String T_ERROR = "error";
    public static final String T_EVENT = "event";
    public static final String T_SIM = "sim";
    public static final String T_PEER_OP = "peerOp";
    public static final String T_NEW_TURN = "newTurn";
    public static final String T_OP_REJECTED = "opRejected";
    public static final String T_REPLACED = "replaced";      // sent to a session that was replaced by a reconnect
    public static final String T_SAVE_AVAILABLE = "saveAvailable"; // async mode: other player uploaded a new save
    public static final String T_KICKED = "kicked";
    public static final String T_HEARTBEAT = "heartbeat";   // optional app-level ping with server time

    // Client -> Server message types
    public static final String C_OP = "op";
    public static final String C_NEXT_TURN = "nextTurn";
    public static final String C_UNCOMPLETE_TURN = "uncompleteTurn";
    public static final String C_PAUSE = "pause";
    public static final String C_RESUME = "resume";
    public static final String C_TURN_READY = "turnReady";
    public static final String C_PING = "ping";
    public static final String C_UPLOAD_SAVE = "uploadSave";     // {gameId, saveJson} — async mode or new game
    public static final String C_CLAIM_CIV = "claimCiv";         // {civId} — record civ ownership
    public static final String C_KICK = "kick";                 // host-only: {target, reason}
    public static final String C_PONG = "pong";

    /** Build the welcome message. reconnect=true signals the client that this
     *  is a reconnection (no need to re-pick civ). */
    public static String welcome(FsSession s, FsRoom room, boolean reconnect) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", T_WELCOME);
        msg.put("gameId", room.gameId);
        msg.put("playerId", s.playerId);
        msg.put("nickname", s.nickname);
        msg.put("uuid", s.playerUUID);
        msg.put("turn", room.currentTurn);
        msg.put("paused", room.paused);
        msg.put("mode", room.mode);
        msg.put("host", s.playerId.equals(room.hostPlayerId));
        msg.put("reconnect", reconnect);
        msg.put("serverVersion", "fs-local-0.2.0");
        // Pre-existing civ assignment for reconnection
        String myCiv = room.getCivFor(s.playerId, s.playerUUID);
        if (myCiv != null) msg.put("myCiv", myCiv);
        return FsJson.encode(msg);
    }

    public static String error(String reason) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", T_ERROR);
        msg.put("reason", reason);
        return FsJson.encode(msg);
    }

    /** Standard turnStatus broadcast payload. */
    public static String turnStatusJson(FsRoom room) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", T_TURN_STATUS);
        msg.put("turn", room.currentTurn);
        msg.put("settling", room.settling);
        msg.put("paused", room.paused);
        if (room.pauseNickname != null) msg.put("pauseNickname", room.pauseNickname);
        // 就绪名单投影：只含实玩玩家（观战不计入"x/n players have ended their turn"的 n，
        // 观战 playerId 也绝不能出现 —— 否则观战退出后名单里留下永远"未就绪"的幽灵条目）。
        // 权威的 room.readyPlayers 本身不动，回合推进逻辑不受影响。
        msg.put("readyPlayers", playingReadyIds(room));
        msg.put("mode", room.mode);
        msg.put("host", room.hostPlayerId);
        List<Object> online = new ArrayList<>();
        for (FsSession s : room.sessions) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("playerId", s.playerId);
            p.put("nickname", s.nickname);
            p.put("uuid", s.playerUUID);
            p.put("spectator", s.spectator);
            p.put("host", s.playerId.equals(room.hostPlayerId));
            p.put("lastSeen", s.lastSeenMs);
            online.add(p);
        }
        msg.put("onlinePlayers", online);
        return FsJson.encode(msg);
    }

    /** turnStatus message addressed to a single client (e.g. "Reconnected — still ready"). */
    public static String turnStatus(FsRoom room, String notice) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", T_TURN_STATUS);
        msg.put("turn", room.currentTurn);
        msg.put("settling", room.settling);
        msg.put("paused", room.paused);
        if (room.pauseNickname != null) msg.put("pauseNickname", room.pauseNickname);
        msg.put("readyPlayers", playingReadyIds(room));   // 只投影实玩玩家（见 turnStatusJson 注释）
        msg.put("mode", room.mode);
        msg.put("notice", notice);
        return FsJson.encode(msg);
    }

    /** 就绪名单的"实玩玩家"投影：观战 playerId 一律不出现。 */
    private static List<String> playingReadyIds(FsRoom room) {
        List<String> out = new ArrayList<>();
        for (String pid : room.readyPlayers) {
            boolean spectator = false;
            for (FsSession s : room.sessions) {
                if (s.playerId.equals(pid) && s.spectator) { spectator = true; break; }
            }
            if (!spectator) out.add(pid);
        }
        return out;
    }

    public static String replaced(String byNickname) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", T_REPLACED);
        msg.put("by", byNickname);
        return FsJson.encode(msg);
    }

    public static String saveAvailable(String gameId, int turn, String uploadedBy, long sizeBytes) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", T_SAVE_AVAILABLE);
        msg.put("gameId", gameId);
        msg.put("turn", turn);
        msg.put("uploadedBy", uploadedBy);
        msg.put("sizeBytes", sizeBytes);
        return FsJson.encode(msg);
    }

    public static String kicked(String by, String reason) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", T_KICKED);
        msg.put("by", by);
        msg.put("reason", reason == null ? "" : reason);
        return FsJson.encode(msg);
    }
}
