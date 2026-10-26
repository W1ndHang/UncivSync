package com.unciv.ui.screens.worldscreen

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.quests.AssignedQuest
import com.unciv.logic.civilization.Notification
import com.unciv.logic.civilization.PopupAlert
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.ui.components.UnitMovementMemoryType
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeRequest
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.worldscreen.bottombar.BattleTableHelpers.battleAnimationDeferred
import com.unciv.utils.Concurrency
import com.unciv.view.CombatantView
import com.unciv.view.CityCombatantView
import com.unciv.view.MapUnitCombatantView
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.lang.ref.WeakReference
import java.net.URLEncoder

/**
 * UncivSync real-time simultaneous-turns client.
 *
 * Talks to the self-hosted FrameSync server over a plain-text JSON WebSocket:
 *
 *   - connect:  /ws?gameId=&playerId=&nickname=&playerUUID=
 *   - send:     {"type":"op","op":"unit.move","data":{...}} / nextTurn / turnReady / ping
 *   - receive:  welcome / sim / turnStatus / saveUpdated / pong / error / opRejected
 *
 * The server is authoritative: local actions are forwarded as ops, and the server pushes
 * `sim` state snapshots which we apply onto the local GameInfo (incremental, by id).
 */
object FrameSync {
    var running = false
        private set

    /** 心跳：应用层 ping 的发送间隔（服务端 handleMessage 对 ping 回 pong）。 */
    private const val HEARTBEAT_INTERVAL_MS = 20_000L
    /** 心跳：超过此时长没有任何入站帧（pong/任意推送）就判定连接已死（半开检测）。 */
    private const val HEARTBEAT_TIMEOUT_MS = 60_000L
    /** 接收循环的轮询片大小：receive 挂这么久没消息就回来检查一次心跳。 */
    private const val RECEIVE_POLL_MS = 1_000L

    // 这些标志在网络协程写、GL 线程读（canSendOp / remainingTurnMs / 各种 UI），
    // 不加 @Volatile 的话 32 位 Android 上 long 撕裂读/可见性滞后都是真实风险。
    @Volatile var connected = false
        private set

    private var gameId = ""
    private var playerId = ""
    private var nickname = ""
    private var playerUUID = ""

    /** 最近一次收到服务端任何帧的时刻；0 表示尚无。心跳超时判死的基准。 */
    @Volatile private var lastServerMsgMs = 0L
    /** 上次发出应用层 ping 的时刻（只在连接协程里读写）。 */
    private var lastPingMs = 0L

    private var worldScreenRef: WeakReference<WorldScreen>? = null

    private var session: WebSocketSession? = null
    private var job: Job? = null

    private var lastTurn = -1
    /** 正在应用整档（防止重入：整档会导致 WorldScreen 重建，期间可能再收到一条）。 */
    private var applyingFullSave = false
    private data class AuthoritativeSnapshot(
        val revision: Long,
        val turn: Int,
        val checksum: String,
        val saveJson: String,
        val force: Boolean = false,
    )
    /**
     * Complete-state reconciliation queue. Only the newest revision matters:
     * every snapshot is a full GameInfo, so a newer one strictly supersedes an
     * older one. Access is guarded because network, GL and loader threads meet here.
     */
    private val authoritativeLock = Any()
    private var pendingAuthoritative: AuthoritativeSnapshot? = null
    private var authoritativeWorkerRunning = false
    private var lastAuthoritativeRevision = -1L
    /**
     * 本会话已展示过的外交通知键（civ|type|value）。
     * 服务端权威存档里的 popupAlerts 是持久的，sim/整档会反复带回同一条；
     * 官方 AlertPopup 消费时只从**本地**列表移除，服务端并不知道。
     * 因此这里做"首次见才加入"去重，避免同一条宣友/索求通知被反复弹窗。
     */
    private val seenAlertKeys = LinkedHashSet<String>()
    /** 上次打印过的服务器地址（只在变化时打日志，避免重连时刷屏）。 */
    private var lastLoggedBase: String? = null

    /**
     * 出站消息队列（单消费者串行发送）。
     *
     * 以前 sendJson 每调用一次就 Concurrency.run 新开一个协程去写 WebSocket ——
     * 多个协程并发写同一个 session，**发送顺序无法保证**。典型后果：
     * 玩家"先选政策、再点结束回合"时，nextTurn 可能先到服务端 → 回合先推进并下发整档，
     * 之后政策 op 才到达 → 玩家看到自己的操作"回退"了（下回合整档才补回来）。
     * 改成 Channel + 单一发送协程后，所有 op / nextTurn 严格按调用顺序到达服务端。
     */
    private val sendQueue = Channel<String>(Channel.UNLIMITED)

    /** 连接未就绪时被丢弃的发送数（重连后提示一次，方便排查"操作没生效"）。 */
    @Volatile private var droppedWhileOffline = 0

    /**
     * 本回合截止时刻，换算成**本地时钟**的毫秒值；0 表示当前没有倒计时。
     *
     * 服务端只在每个回合开始时告诉我们"还剩多少毫秒"，客户端加上本地当前时间即可 ——
     * 之后完全由客户端本地刷新，服务端不再推送（也不受两端时钟差影响）。
     */
    @Volatile private var turnDeadlineLocalMs = 0L

    /** 同步回合剩余时间（毫秒）；没有倒计时返回 null。供右上角计时器使用。 */
    fun remainingTurnMs(): Long? {
        // 暂停期间不显示倒计时（服务端也停止排程了；这里双保险，防止上一条 deadline 的残值还在跳）
        if (serverPaused) return null
        val deadline = turnDeadlineLocalMs
        if (deadline <= 0) return null
        val left = deadline - System.currentTimeMillis()
        return if (left > 0) left else 0L
    }
    @Volatile var myTurnFinished = false
        private set
    @Volatile var serverSettling = false
        private set

    /** 服务端暂停中（turnStatus.paused / pauseNotice）。对齐 UncivGC 的 lastPaused。 */
    @Volatile var serverPaused = false
        private set

    /**
     * 结算 / 暂停遮罩 —— 全屏吞点击 + 一行原因说明。
     * 对齐 UncivGC 的 settleBlocker / pauseBlocker / showSettlingHint：
     * 光靠"服务端拒绝"玩家只会觉得"点了没反应"，得明确告诉他此刻不能操作。
     */
    private var stateBlocker: Table? = null

    /**
     * 已点「结束回合」但当时连接未就绪 —— 记下来，连上后补发。
     * 对齐 UncivGC 的 pendingTurnReady：否则掉线那一刻点结束回合会石沉大海，
     * 回合一直卡到倒计时结束才走。
     */
    @Volatile private var pendingTurnReady = false

    /** 提示节流：同一句话 3 秒内只弹一次，避免结算期间被刷屏。 */
    private var lastNoticeText = ""
    private var lastNoticeAt = 0L

    /** Number of human (non-spectator) players that have ended their turn, per the latest turnStatus. */
    var readyCount = 0
        private set
    /** Total number of human (non-spectator) online players, per the latest turnStatus. */
    var onlinePlayerCount = 0
        private set

    private val json = Json { ignoreUnknownKeys = true }

    /** Whether this game should run through FrameSync (real-time simultaneous turns). */
    fun isFsMode(gameInfo: GameInfo?): Boolean {
        // 服务端复用同一个核心 jar，核心动作必须真正执行，不能再次发包。
        if (java.lang.Boolean.getBoolean("unciv.fs.authority")) return false
        if (gameInfo == null) return false
        val p = gameInfo.gameParameters
        if (!p.simultaneousTurns) return false
        if (!p.isOnlineMultiplayer) return false
        // Real-time sync only makes sense with at least two real (non-barbarian, non-spectator)
        // civilizations. A solo game falls back to plain local play.
        val humans = gameInfo.civilizations.count { !it.isBarbarian && !it.isSpectator() }
        return humans > 1
    }

    /** Called from WorldScreen once the game screen is shown and isFsMode is true. */
    fun startIfEnabled(worldScreen: WorldScreen) {
        if (isFsMode(worldScreen.gameInfo)) com.unciv.logic.FsAudit.bind(worldScreen.gameInfo)
        val gameInfo = worldScreen.gameInfo
        if (!isFsMode(gameInfo)) return
        worldScreenRef = WeakReference(worldScreen)
        // 屏幕被重建（整档下发会重建 WorldScreen）：旧 stage 连同遮罩一起没了，
        // 这里必须把引用清掉，否则会拿着一个已死的 Actor 一直复用。
        stateBlocker = null

        // 同一局里屏幕被重建（例如整档下发后重建世界）：复用现有连接，绝不重连；
        // 也**不重置** myTurnFinished/serverSettling/serverPaused —— 它们以服务端
        // turnStatus 为准，重置会让"已结束回合/结算中"短暂解锁，窗口内发出的 op
        // 只会换来一次 opRejected。下一条 turnStatus 到达时自然会校正。
        if (running && gameId == gameInfo.gameId) return

        lastTurn = gameInfo.turns
        myTurnFinished = false
        serverSettling = false
        serverPaused = false

        // 首次进入：把已经在存档里的"文明介绍"类通知预先记为已见。
        // 它们这次会正常弹出（只在首次进入时），但此后每次整档重建都不该再弹一遍。
        if (!running) markPreexistingIntroAlerts(gameInfo)

        gameId = gameInfo.gameId
        playerId = UncivGame.Current.settings.multiplayer.getUserId()
        nickname = UncivGame.Current.settings.multiplayer.userName
        playerUUID = playerId
        connectLoop()
    }

    /**
     * WorldScreen 被销毁时调用 —— **不立刻断开连接**。
     *
     * 「每回合整档下发」会重建 WorldScreen，如果照旧在 dispose 里断开，就会产生
     * "重建 → 断线 → 重连"的连锁。这里延迟确认：这段时间内有同 gameId 的新屏幕注册就保持连接；
     * 否则说明玩家真的离开了这一局（回主菜单/换存档），再断开。
     */
    fun onScreenDisposed(screen: WorldScreen) {
        com.unciv.logic.FsAudit.unbind(screen.gameInfo)
        if (worldScreenRef?.get() === screen) worldScreenRef = null
        if (!running) return
        Concurrency.run("FrameSyncDetachCheck") {
            delay(1500)
            val g = try { UncivGame.Current.gameInfo } catch (_: Throwable) { null }
            if (g != null && g.gameId == gameId && isFsMode(g) && worldScreenRef?.get() != null) return@run
            println("[FrameSync] 离开对局，断开同步连接")
            stop()
        }
    }

    /**
     * 通知被玩家消费（弹窗关闭 / 被丢弃）。
     *
     * 不但记入本地"已见"集合，还会**上报服务端从权威存档里删掉它** ——
     * 否则每次整档下发（loadGame 重建世界）都会把它重新加载回来，
     * 表现就是用户反馈的"每次接受存档包都弹一次文明介绍"。
     */
    fun notifyAlertSeen(civName: String, alert: PopupAlert) {
        try {
            val typeName = alert.type?.name ?: return
            val value = alert.value
            seenAlertKeys.add("$civName|$typeName|$value")
            if (!connected) return
            sendCivOp("civ.alertSeen", mapOf("type" to typeName, "value" to value))
        } catch (_: Throwable) {
        }
    }

    /**
     * 首次进入时，把存档里已存在的"文明介绍"通知记为已见。
     * 不做移除（它们这次要正常弹出），只是让后续整档重建不再重复弹出。
     */
    private fun markPreexistingIntroAlerts(gameInfo: GameInfo) {
        try {
            for (civ in gameInfo.civilizations) {
                for (pa in civ.popupAlerts) {
                    try {
                        if (pa.type == AlertType.StartIntro)
                            seenAlertKeys.add("${civ.civName}|StartIntro|${pa.value}")
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        seenAlertKeys.clear()
        running = false
        connected = false
        serverSettling = false
        serverPaused = false
        pendingTurnReady = false
        // 清空发送队列里残留的未发出消息：离开 A 局时没发出的 op 不能留给 B 局 ——
        // 新一局的发送协程会把它们读出来、写进 B 局的 WebSocket（跨局污染）。
        while (sendQueue.tryReceive().isSuccess) { /* drain */ }
        droppedWhileOffline = 0
        // 遮罩必须在断开前摘掉，否则它会一直挂在新/旧屏幕上吞点击
        hideStateBlocker()
        job?.cancel(null)
        job = null
        session = null
        worldScreenRef = null
        synchronized(authoritativeLock) {
            pendingAuthoritative = null
            authoritativeWorkerRunning = false
            lastAuthoritativeRevision = -1L
        }
    }

    // ===== connection =====

    private fun fsUrl(): String {
        // UncivSync: 同步连接的地址以「Options → Multiplayer → Server address」为准，
        // 这样本机(127.0.0.1)、局域网、内网穿透/公网地址都能用；留空才回落到内置默认。
        //
        // 注意：这里以前直接硬编码 Constants.uncivXyzServer，导致玩家在设置里改地址**完全无效**
        // —— 同步连接永远连 127.0.0.1，也就只有"服务端在同一台机器上"时才能连上。
        val configured = try {
            UncivGame.Current.settings.multiplayer.getServer()
        } catch (_: Throwable) { null }
        var base = configured?.trim().orEmpty().ifEmpty { Constants.uncivXyzServer }
        // 允许玩家只填 host:port（自动补 http://）
        if (!base.startsWith("http://") && !base.startsWith("https://") &&
            !base.startsWith("ws://") && !base.startsWith("wss://")) {
            base = "http://$base"
        }
        if (base != lastLoggedBase) {
            println("[FrameSync] 服务器地址: $base")
            lastLoggedBase = base
        }
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base   // 已经是 ws:// / wss://
        }
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "$wsBase/ws?gameId=${enc(gameId)}&playerId=${enc(playerId)}" +
                "&nickname=${enc(nickname)}&playerUUID=${enc(playerUUID)}"
    }

    private fun connectLoop() {
        running = true
        job?.cancel(null)
        job = Concurrency.run("FrameSync") {
            // 单一发送协程：从队列里顺序取出、写入当前 session。
            // 这样 op 与 nextTurn 的先后关系与调用顺序一致（见 sendQueue 注释）。
            launch {
                for (text in sendQueue) {
                    val s = session
                    if (s == null) {
                        // 会话空窗（连接断开/正被重连）：消费掉了但没发出去，
                        // 必须与 sendJson 的"未连接"同一口径计数，否则恢复提示少报。
                        droppedWhileOffline++
                        continue
                    }
                    try {
                        s.send(Frame.Text(text))
                    } catch (t: Throwable) {
                        // 发送失败 = 连接实际已死（典型：半开 TCP）但接收循环还没判定。
                        // 静默吞掉玩家的操作是 worst case —— 计数 + 主动关掉这条会话，
                        // 让接收循环立刻结束并走外层重连。只关本次拿到的 s，绝不置空
                        // session（此刻可能已经换上了重连后的新会话）。
                        droppedWhileOffline++
                        println("[FrameSync] 发送失败，丢弃一条消息并触发重连: ${t.message}")
                        try { s.close() } catch (_: Throwable) {}
                    }
                }
            }
            while (running) {
                try {
                    connectOnce()
                } catch (t: Throwable) {
                    // 排障线索不能完全不给：完全静默的话"连不上"只剩转圈
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    println("[FrameSync] 连接中断: ${t.javaClass.simpleName}: ${t.message}")
                }
                delay(2000)
            }
        }
    }

    private suspend fun connectOnce() {
        val client = HttpClient(CIO) {
            install(WebSockets) {
                // Ktor 的 WebSocket 单帧默认上限是 64KB（65536），超过会直接抛错并断开连接 ——
                // 服务端推送的全量 sim / 整档存档（几十万字节）会撞上它。
                // 这里放开到 32MB，与自研服务端 FsWsFrame 的读取上限保持一致。
                maxFrameSize = 32L * 1024 * 1024
            }
        }
        try {
            val ws = client.webSocketSession {
                url(fsUrl())
            }
            session = ws
            connected = true
            lastServerMsgMs = System.currentTimeMillis()
            lastPingMs = System.currentTimeMillis()
            ws.send(Frame.Text("{\"type\":\"ping\"}"))
            // 断线期间点过「结束回合」→ 现在补发（对齐 UncivGC 的 pendingTurnReady）。
            // 走 sendQueue 而不是直接 ws.send：补发与队列里残留 op 的先后关系
            // 必须有保证（sendQueue 存在的意义），绕过队列会破坏 op→nextTurn 顺序。
            if (pendingTurnReady && myTurnFinished) {
                pendingTurnReady = false
                sendJson("{\"type\":\"nextTurn\"}")
                println("[FrameSync] 连上后补发「结束回合」")
            }
            while (ws.isActive) {
                // ---- 心跳 / 半开检测 ----
                // 网络切换、休眠恢复等场景下 TCP 半开：send 进本地缓冲不报错，
                // receive 无限期阻塞 —— 表现为"看起来在线，操作全部无声丢失"。
                // 对策：定期发应用层 ping（服务端回 pong），任何入站帧都刷新
                // lastServerMsgMs；超时没有入站帧就判死，break 后由 finally →
                // 外层 while 重连。
                val now = System.currentTimeMillis()
                if (now - lastServerMsgMs > HEARTBEAT_TIMEOUT_MS) {
                    println("[FrameSync] 心跳超时（${HEARTBEAT_TIMEOUT_MS / 1000}s 无入站帧），判定连接已死，重连")
                    break
                }
                if (now - lastPingMs >= HEARTBEAT_INTERVAL_MS) {
                    lastPingMs = now
                    try { ws.send(Frame.Text("{\"type\":\"ping\"}")) } catch (_: Throwable) { break }
                }
                val frame = try {
                    withTimeout(RECEIVE_POLL_MS) { ws.incoming.receive() }
                } catch (e: TimeoutCancellationException) {
                    continue   // 这一片没消息：回到循环头检查心跳
                }
                lastServerMsgMs = System.currentTimeMillis()
                when (frame) {
                    is Frame.Text -> handleMessage(frame.readText())
                    // 二进制帧 = gzip 后的文本消息（整档下发），见 handleCompressedMessage
                    is Frame.Binary -> handleCompressedMessage(frame.readBytes())
                    else -> {}
                }
            }
        } finally {
            connected = false
            session = null
            client.close()
        }
    }

    /**
     * 出站门 —— 对齐 UncivGC 的 `sendOpChecked` 四道门。
     *
     * 顺序：暂停 → 结算中 → 已结束回合的回合锁 → 断线。
     *
     * 服务端**也会独立拦一遍**（FsOpPipeline），那道是权威；这里这道是体验：
     * 不拦的话玩家照点，本地状态改了又被 sim 纠正，看起来就是"点了没反应"。
     * 返回 false 时调用方**也不要**本地执行。
     */
    fun canSendOp(op: String?): Boolean {
        if (serverPaused) {
            notice("对局已暂停，操作未发送")
            return false
        }
        if (serverSettling) {
            notice("服务端正在结算本回合，操作未发送")
            return false
        }
        if (myTurnFinished && lastTurn > 0 && op != null && op in TURN_LOCKED_OPS
            && op != "civ.choosePolicy" && op != "civ.chooseBeliefs") {
            // UncivSync：civ.choosePolicy / civ.chooseBeliefs 是"文明级抉择"，服务端回合模拟不会自愈，
            // 若结束后被静默丢弃就永久丢失（"政策/信条选了不记录"）。所以这两条即便已结束回合也放行，
            // 由服务端并入 turnLockedQueue、下一回合统一应用（补操作）。
            notice("你已经结束回合了，本回合不能再改这个")
            return false
        }
        if (!connected) {
            notice("连接已断开，操作未发送")
            return false
        }
        return true
    }

    /** 节流提示（走 ToastPopup，3 秒内同一句只弹一次）。 */
    private fun notice(text: String) {
        if (worldScreenRef?.get() == null) return
        val now = System.currentTimeMillis()
        if (text == lastNoticeText && now - lastNoticeAt < 3000L) return
        lastNoticeText = text
        lastNoticeAt = now
        Concurrency.runOnGLThread {
            try {
                worldScreenRef?.get()?.let { ToastPopup(text, it) }
            } catch (_: Throwable) {
            }
        }
    }

    fun sendOp(op: String, data: Map<String, Any>): Boolean {
        if (!canSendOp(op)) return false
        val dataJson = data.entries.joinToString(",") { (k, v) ->
            "\"$k\":${valueToJson(v)}"
        }
        sendJson("{\"type\":\"op\",\"op\":\"$op\",\"data\":{$dataJson}}")
        return true
    }

    fun sendNextTurn() {
        myTurnFinished = true
        worldScreenRef?.get()?.shouldUpdate = true
        // 连接没就绪：记下来，连上后补发（否则这一下就丢了，回合要卡到倒计时）
        if (!connected) {
            pendingTurnReady = true
            worldScreenRef?.get()?.let { ToastPopup("连接未就绪：你的「结束回合」会在连上后自动补发", it) }
            return
        }
        pendingTurnReady = false
        sendJson("{\"type\":\"nextTurn\"}")
    }

    fun sendUncompleteTurn() {
        myTurnFinished = false
        sendJson("{\"type\":\"uncompleteTurn\"}")
        worldScreenRef?.get()?.shouldUpdate = true
    }

    fun sendTurnReady() {
        sendJson("{\"type\":\"turnReady\"}")
    }

    fun sendPause() = sendJson("{\"type\":\"pause\"}")
    fun sendResume() = sendJson("{\"type\":\"resume\"}")

    /**
     * Forward a unit action as a server op. Returns true when the op was sent (and the
     * caller should NOT run the action locally — the server's sim push applies it), false
     * when this game is not in FrameSync mode (caller proceeds with a normal local action).
     */
    fun sendUnitActionOp(unit: MapUnit, op: String, extra: Map<String, Any> = emptyMap()) {
        sendUnitOp(unit, op, extra = extra)
    }

    /**
     * 移动（服务端裁决）。
     *
     * 与 UncivGC 同构：只上报「本回合走到哪 + 最终想去哪」，**本地不做任何位移**。
     * 服务端用同一套 UnitMovement 算路径并落地，权威坐标随 sim 推回，
     * 由 [handleSim] 应用并带动画 —— 结构上不存在"移过去又被弹回"。
     *
     * @param toX/toY     本回合实际能走到的那格（调用方用 getTileToMoveToThisTurn 算好）
     * @param destX/destY 玩家点的最终目的地（服务端据此续走多回合行军）
     */
    fun sendUnitMove(unit: MapUnit, toX: Int, toY: Int, destX: Int, destY: Int): Boolean {
        if (!canSendOp("unit.move")) return false
        val data = FsUnitRef.of(unit).toData()
        data["toX"] = toX
        data["toY"] = toY
        data["destX"] = destX
        data["destY"] = destY
        sendOp("unit.move", data)
        return true
    }

    /** 换位（服务端裁决）：同样只上报，本地不动。 */
    fun sendUnitSwap(unit: MapUnit, toX: Int, toY: Int): Boolean {
        if (!canSendOp("unit.swap")) return false
        val data = FsUnitRef.of(unit).toData()
        data["toX"] = toX
        data["toY"] = toY
        sendOp("unit.swap", data)
        return true
    }

    /** Stable identity of the defender selected by the player. Coordinates are only presentation data. */
    fun attackTargetData(target: CombatantView?): Map<String, Any> = when (target) {
        is MapUnitCombatantView -> mapOf("targetUnitId" to target.getUnitView().getUnit().id)
        is CityCombatantView -> mapOf("targetCityId" to target.getCityView().getCity().id)
        else -> emptyMap()
    }

    /**
     * 统一 op 入口（单位）。
     *
     * 只发 op，本地状态一律等服务端 sim 回推（fortify/sleep/foundCity/... 全部如此）。
     * data 里始终带稳定 unitId 主键；文明/类型/坐标只用于诊断和旧协议兼容。
     */
    fun sendUnitOp(
        unit: MapUnit,
        op: String,
        extra: Map<String, Any> = emptyMap(),
    ): Boolean {
        com.unciv.logic.FsAudit.intent(unit.civ.gameInfo, op)
        if (!canSendOp(op)) return false
        val ref = FsUnitRef.of(unit)
        val data = ref.toData()
        data.putAll(extra)
        sendOp(op, data)
        return true
    }

    /** 统一 op 入口（城市）：生产/买地/专家/工作地块等。 */
    fun sendCityOp(
        city: City,
        op: String,
        extra: Map<String, Any> = emptyMap(),
    ): Boolean {
        if (!canSendOp(op)) return false
        val ref = FsCityRef.of(city)
        val data = ref.toData()
        data.putAll(extra)
        sendOp(op, data)
        return true
    }

    /** 统一 op 入口（文明级：政策/科技/宗教/外交）。data 由调用方给出目标文明等参数。 */
    fun sendCivOp(op: String, data: Map<String, Any>): Boolean {
        if (!canSendOp(op)) return false
        sendOp(op, data)
        return true
    }

    // ---- 玩家→玩家交易（实时）----
    // 官方机制：先把 Trade 写进对方的 tradeRequests，对方客户端会自动弹 TradePopup；
    // 这里把"发起 / 撤回 / 接受 / 拒绝"都上报服务端，由服务端写进权威存档后再广播。

    /** 发起交易。[tradeJson] 是 Trade 对象的 JSON（com.unciv.json.json().toJson(trade)）。 */
    fun sendTradeOffer(toCivName: String, tradeJson: String): Boolean =
        sendCivOp("trade.offer", mapOf("toCiv" to toCivName, "tradeJson" to tradeJson))

    /** 撤回自己发出的交易请求。 */
    fun sendTradeRetract(toCivName: String): Boolean =
        sendCivOp("trade.retract", mapOf("toCiv" to toCivName))

    /** 回应对方的交易请求：[op] = "trade.accept" 或 "trade.reject"。 */
    fun sendTradeAnswer(op: String, fromCivName: String): Boolean =
        sendCivOp(op, mapOf("fromCiv" to fromCivName))

    /** Dismiss an incoming offer while opening the counter-offer screen. */
    fun sendTradeCounter(fromCivName: String): Boolean =
        sendCivOp("trade.counter", mapOf("fromCiv" to fromCivName))

    /**
     * 统一拦截一个"通用 op"（对齐 GC 的 `tryInterceptOp`）。
     *
     * `data` 由调用方拼好（含 unit/city 寻址等字段）。命中 FS 模式就发送并返回 true ——
     * 调用方应 `return` 跳过本地执行；否则返回 false，调用方走原版本地逻辑。
     *
     * 这是 UI 侧所有"发一个现成 op"场景的单一入口，替代各处手写 `if (isFsMode) { sendXxxOp }` 的散落写法。
     */
    fun tryInterceptOp(worldScreen: WorldScreen?, op: String, data: Map<String, Any>): Boolean {
        if (worldScreen == null) return false
        if (!isFsMode(worldScreen.gameInfo)) return false
        sendOp(op, data)
        return true
    }

    /**
     * 统一拦截"单位动作"（对齐 GC 的 `tryInterceptGenericAction`）。
     *
     * 把 `UnitActionType` 映射成对应 op 上报，并做收尾（刷新 UI、关掉动作浮层、跳过则切到下一单位），
     * 返回 true —— 调用方不要再本地执行该动作；否则返回 false，调用方走原版本地逻辑。
     *
     * 探索（explore）走专门协议、不在这里处理；其余纯状态动作（fortify/sleep/foundCity/
     * 建造加速/宗教/变形…）全部收敛到这一个入口，映射表见 [FsActionCoverage.operations]。
     */
    fun tryInterceptGenericAction(worldScreen: WorldScreen?, unit: MapUnit, action: UnitAction): Boolean {
        if (worldScreen == null) return false
        if (!isFsMode(worldScreen.gameInfo)) return false
        // GiftUnit 走专用通道（getGiftActions 的闭包带 recipient 上报）——
        // 通用通道的 extra 为空、没有 recipient，发出去服务端必然失败，还会与专用通道重复。
        if (action.type == UnitActionType.GiftUnit) return false
        val mapped = FsActionCoverage.opFor(action)
        if (mapped == null) {
            if (action.type in FsActionCoverage.localRoutes) return false
            // 未登记动作不得静默落回本地。护栏测试会在新增枚举时先失败。
            notice("此动作尚未接入帧同步：${action.type}")
            return true
        }
        val (op, extra) = mapped
        sendUnitOp(unit, op, extra)
        worldScreen.shouldUpdate = true
        worldScreen.mapHolder.removeUnitActionOverlay()
        if (action.type == UnitActionType.Skip && !UncivGame.Current.settings.autoUnitCycle)
            worldScreen.switchToNextUnit()
        return true
    }

    /** 当前 WorldScreen（弱引用），拿不到时返回 null（对齐 GC 的 currentWorldScreenOrNull）。 */
    fun currentWorldScreenOrNull(): WorldScreen? = worldScreenRef?.get()

    private fun sendJson(text: String) {
        // 交给单发送协程按 FIFO 顺序发出（不能在这里并发写 session，否则 op/nextTurn 会乱序）
        if (!connected) {
            // 连接未就绪：丢弃本次消息（避免重连后重放过期操作）。
            // 计数并在恢复后提示一次 —— 否则玩家只会看到"我明明操作了却没生效"而无从排查。
            droppedWhileOffline++
            return
        }
        if (droppedWhileOffline > 0) {
            println("[FrameSync] 连接已恢复：离线期间丢弃了 $droppedWhileOffline 条操作（未重放）")
            droppedWhileOffline = 0
        }
        sendQueue.trySend(text)
    }

    private fun valueToJson(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + jsonEscape(v) + "\""
        is Number -> v.toString()
        is Boolean -> v.toString()
        // 数组/集合参数（如研究队列 techs、信条列表 beliefs）必须序列化成真正的 JSON 数组，
        // 否则服务端拿到的是一个字符串，数组类 op 全部失效。
        is Iterable<*> -> v.joinToString(",", "[", "]") { valueToJson(it) }
        is Array<*> -> v.joinToString(",", "[", "]") { valueToJson(it) }
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") {
            "${valueToJson(it.key?.toString())}:${valueToJson(it.value)}"
        }
        else -> "\"" + jsonEscape(v.toString()) + "\""
    }

    /**
     * JSON 字符串字面量转义。以前只处理 `\` 和 `"` —— 值里含控制字符
     * （城市改名粘进换行、含 \t 的文本…）时产出**非法 JSON**，服务端解析失败，
     * 这条 op 就静默丢了。控制字符必须全部转义（\u00XX）。
     */
    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    // ===== incoming messages =====

    /**
     * 二进制帧：内容是 gzip 压缩后的文本消息。
     * 整档 JSON 明文有几十万字节（含中文时更大），压缩后通常只剩 1/10 左右，
     * 且走二进制帧不做 base64，避免再膨胀 33%。
     */
    private fun handleCompressedMessage(bytes: ByteArray) {
        val text = try {
            java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes))
                .use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (t: Throwable) {
            println("[FrameSync] 二进制消息解压失败: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        handleMessage(text)
    }

    private fun handleMessage(text: String) {
        val msg = try { json.parseToJsonElement(text).jsonObject } catch (_: Throwable) { return }
        // Guard every handler so an exception applying one message can't bubble up to
        // connectOnce()'s receive loop and tear down the WebSocket (which forced a reconnect).
        try {
            when ((msg["type"] as? JsonPrimitive)?.contentOrNull ?: "sim") {
                "sim" -> handleSim(msg)
                "turnStatus" -> handleTurnStatus(msg)
                "turnDeadline" -> handleTurnDeadline(msg)
                "saveUpdated" -> {
                    val turn = (msg["turn"] as? JsonPrimitive)?.intOrNull ?: -1
                    reloadGame(turn)
                }
                "pong" -> {}
                "error" -> {
                    // 以前直接忽略 —— 于是"只有房主能暂停"这类服务端拒绝玩家永远看不到
                    (msg["reason"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { notice("服务端拒绝：$it") }
                }
                "opRejected" -> handleOpRejected(msg)
                "saveFull" -> handleFullSave(msg)
                // ---- 以前这几类服务端在发、客户端直接丢。暂停通知丢了就等于"暂停功能不存在"。----
                "pauseNotice" -> {
                    serverPaused = true
                    val who = (msg["nickname"] as? JsonPrimitive)?.contentOrNull ?: ""
                    updateStateBlocker()
                    if (who.isNotEmpty()) notice("$who 暂停了对局")
                }
                "resumeNotice" -> {
                    serverPaused = false
                    updateStateBlocker()
                }
                "kicked" -> {
                    val reason = (msg["reason"] as? JsonPrimitive)?.contentOrNull ?: ""
                    notice(if (reason.isEmpty()) "你已被移出对局" else "你已被移出对局：$reason")
                }
                // welcome / onlinePlayers / peerOp：信息已经由 turnStatus / sim 覆盖，无需单独处理
                "welcome" -> {
                    // Server process restarts reset its revision counter. A fresh
                    // WebSocket welcome defines a new ordering epoch, so an old
                    // in-memory revision must never suppress the new server state.
                    pendingSims.clear()
                    // 重启前若正处于暂停/结算/倒计时，这些本地状态全部过期：
                    // 不重置的话，过期的遮罩和倒计时会一直挂到下一条 turnStatus。
                    serverPaused = false
                    serverSettling = false
                    turnDeadlineLocalMs = 0L
                    synchronized(authoritativeLock) {
                        pendingAuthoritative = null
                        lastAuthoritativeRevision = -1L
                    }
                }
                "onlinePlayers", "peerOp" -> {}
            }
        } catch (_: Throwable) {
            // swallow — never let a message-handling error drop the connection
        }
    }

    /**
     * 「每回合整档下发」：服务端把权威存档整档发来，客户端反序列化后**重建本地世界**。
     *
     * 这是层 1「单一权威源」的落地：重建之后本地 GameInfo 就是服务端那一份，
     * 单位 id / 地图 / 城市 / 文明不再有任何漂移，定位类兜底代码也就不用再兜底了。
     * WebSocket 连接在重建期间保持不变（见 onScreenDisposed）。
     */
    private fun handleFullSave(msg: JsonObject) {
        val turn = (msg["turn"] as? JsonPrimitive)?.intOrNull ?: -1
        val saveJson = (msg["saveJson"] as? JsonPrimitive)?.contentOrNull ?: return
        if (saveJson.length < 50) return
        val revision = (msg["revision"] as? JsonPrimitive)?.longOrNull
            ?: 0L   // 与 sim-full 路径同一个回退量纲。绝不能用 turn shl 32 这类"大数"：
                    // 一旦真用过它，后续所有真实 revision（小整数）都满足 <= 它而被
                    // queueAuthoritativeRebase 永久丢弃 —— 客户端再也收不到整档更新。
        queueAuthoritativeRebase(AuthoritativeSnapshot(revision, turn, "", saveJson, force = true))
    }

    /** Remove already-consumed one-shot alerts before a complete-state rebuild. */
    private fun removeSeenAlerts(gameInfo: GameInfo) {
        try {
            for (civ in gameInfo.civilizations) {
                val it = civ.popupAlerts.iterator()
                while (it.hasNext()) {
                    val pa = it.next()
                    val type = try { pa.type?.name } catch (_: Throwable) { null } ?: continue
                    if (seenAlertKeys.contains("${civ.civName}|$type|${pa.value}")) it.remove()
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Queue a complete GameInfo rebase. The worker coalesces bursts and always
     * rechecks the revision after deserialization, so an older snapshot can
     * never overwrite a newer operation that arrived while it was loading.
     */
    private fun queueAuthoritativeRebase(snapshot: AuthoritativeSnapshot) {
        synchronized(authoritativeLock) {
            if (snapshot.revision <= lastAuthoritativeRevision) return
            val old = pendingAuthoritative
            if (old == null || snapshot.revision >= old.revision) pendingAuthoritative = snapshot
            if (authoritativeWorkerRunning) return
            authoritativeWorkerRunning = true
        }
        Concurrency.run("FrameSyncAuthoritativeRebase") {
            while (running) {
                // Coalesce rapid movement/automation operations into the newest full snapshot.
                delay(120)
                val current = synchronized(authoritativeLock) {
                    val value = pendingAuthoritative
                    if (value == null) authoritativeWorkerRunning = false
                    value
                } ?: return@run

                val newGameInfo = try {
                    UncivFiles.gameInfoFromString(current.saveJson)
                } catch (t: Throwable) {
                    println("[FrameSync] 完整状态解析失败: ${t.javaClass.simpleName}: ${t.message}")
                    synchronized(authoritativeLock) {
                        if (pendingAuthoritative?.revision == current.revision) pendingAuthoritative = null
                    }
                    continue
                }

                // A newer full state arrived while this one was parsing: discard the old one.
                val stillLatest = synchronized(authoritativeLock) {
                    pendingAuthoritative?.revision == current.revision
                }
                if (!stillLatest) continue

                applyingFullSave = true
                try {
                    // 第三十一批（对齐 GC saveUpdated / Freeciv）：权威整档只在**回合边界/重连**
                    // 到达（full 快照），此处恢复官方读档式重建；常规 op 增量已改为
                    // per-entity 全字段包（obj），不再触发本路径，也无影分身/闪屏。
                    pendingSims.clear()
                    removeSeenAlerts(newGameInfo)
                    println("[FrameSync] 权威整档到达，按 revision=${current.revision} 重建第 ${newGameInfo.turns} 回合（回合边界重建）")
                    UncivGame.Current.loadGame(newGameInfo)
                    synchronized(authoritativeLock) {
                        if (pendingAuthoritative?.revision == current.revision) pendingAuthoritative = null
                        lastAuthoritativeRevision = maxOf(lastAuthoritativeRevision, current.revision)
                    }
                } catch (t: Throwable) {
                    println("[FrameSync] 完整状态应用失败: ${t.javaClass.simpleName}: ${t.message}")
                    synchronized(authoritativeLock) {
                        if (pendingAuthoritative?.revision == current.revision) pendingAuthoritative = null
                    }
                } finally {
                    applyingFullSave = false
                }
            }
            synchronized(authoritativeLock) { authoritativeWorkerRunning = false }
        }
    }

    /**
     * 服务端在每个回合开始时下发一次"本回合剩余毫秒"。
     * 客户端换算成本地截止时刻后**自行每秒刷新**，服务端不再推送。
     */
    private fun handleTurnDeadline(msg: JsonObject) {
        val remaining = (msg["remainingMs"] as? JsonPrimitive)?.longOrNull ?: return
        turnDeadlineLocalMs = if (remaining < 0) 0L else System.currentTimeMillis() + remaining
        worldScreenRef?.get()?.shouldUpdate = true
    }

    private fun handleTurnStatus(msg: JsonObject) {
        serverSettling = (msg["settling"] as? JsonPrimitive)?.booleanOrNull ?: false
        (msg["paused"] as? JsonPrimitive)?.booleanOrNull?.let { serverPaused = it }
        updateStateBlocker()
        val turn = (msg["turn"] as? JsonPrimitive)?.intOrNull ?: -1
        if (turn > lastTurn) {
            lastTurn = turn
            // A new turn has rolled over — clear our "ended my turn" flag so the
            // NextTurnButton flips back from "x/n ended" to the normal action.
            myTurnFinished = false
            // 回合已经走过了，说明之前那次补发要么成功了要么没必要；不能再补
            pendingTurnReady = false
        }

        // Track how many human players have ended their turn (readyPlayers) vs how many are
        // online, so the UI can show "x/n players have ended their turn".
        msg["readyPlayers"]?.jsonArray?.let { arr ->
            readyCount = arr.size
            // UncivSync: **"我是否已结束回合"以服务端为准**，不只是本地标记。
            //
            // 以前它只在"回合号前进"时才清，于是任何让两边不同步的路径都会卡住玩家：
            //   · 掉线被服务端移出 ready / 重连 / 整档后 WorldScreen 重建 → 本地以为"没结束"，
            //     服务端却还把我算作 ready → 城市建造、政策、科技等 TURN_LOCKED_OPS
            //     全被服务端拒（表现："没办法在城市建造东西"）；
            //   · 反过来本地锁着、服务端已经放开 → 表现成"点了没反应"，也点不动「撤回」。
            // 两边念同一份 readyPlayers，这两个症状就一起消失了。
            if (playerId.isNotEmpty()) {
                val mine = arr.any { (it as? JsonPrimitive)?.contentOrNull == playerId }
                if (myTurnFinished != mine) {
                    myTurnFinished = mine
                    worldScreenRef?.get()?.shouldUpdate = true
                }
            }
        }
        msg["onlinePlayers"]?.jsonArray?.let { arr ->
            onlinePlayerCount = arr.count { p ->
                (p.jsonObject["spectator"] as? JsonPrimitive)?.booleanOrNull != true
            }
        }

        worldScreenRef?.get()?.shouldUpdate = true
    }

    /**
     * 结算 / 暂停遮罩的显隐（对齐 UncivGC 的 settleBlocker / pauseBlocker + showSettlingHint）。
     *
     * 一层铺满 stage 的 Table：吃掉所有点击，并在顶部显示原因。
     * 光靠服务端拒绝、玩家只会觉得"点了没反应"；这里明确告诉他此刻不能操作。
     *
     * 所有 stage 操作都走 GL 线程；任何异常都吞掉 —— 遮罩出问题绝不能让对局不可玩。
     */
    private fun updateStateBlocker() {
        val ws = worldScreenRef?.get() ?: return
        val reason = when {
            serverPaused -> "对局已暂停"
            serverSettling -> "服务端结算中…"
            else -> null
        }
        Concurrency.runOnGLThread {
            try {
                if (reason == null) {
                    stateBlocker?.remove()
                    stateBlocker = null
                    return@runOnGLThread
                }
                val existing = stateBlocker
                if (existing != null && existing.stage != null) {
                    (existing.getChild(0) as? com.badlogic.gdx.scenes.scene2d.ui.Label)?.setText(reason)
                    return@runOnGLThread
                }
                existing?.remove()
                val table = Table()
                table.setFillParent(true)
                table.touchable = Touchable.enabled
                // 吞掉点击（吞一次就够，这里不需要做任何事）
                table.addListener(object : InputListener() {
                    override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) = true
                })
                table.top().padTop(80f)
                table.add(reason.toLabel()).row()
                // 暂停时遮罩会盖住顶栏，所以"恢复"必须做在遮罩里 —— 否则一旦暂停就出不来。
                if (serverPaused) {
                    val resume = "Resume".toTextButton()
                    resume.onClick { sendResume() }
                    table.add(resume).padTop(12f)
                }
                ws.stage.addActor(table)
                stateBlocker = table
            } catch (t: Throwable) {
                println("[FrameSync] 结算遮罩更新失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun hideStateBlocker() {
        try {
            Concurrency.runOnGLThread {
                try {
                    stateBlocker?.remove()
                } catch (_: Throwable) {
                } finally {
                    stateBlocker = null
                }
            }
        } catch (_: Throwable) {
            stateBlocker = null
        }
    }

    /** 把服务端的拒绝原因翻成人话（只挑最常撞上的几条，其余原样显示）。 */
    private fun explainReject(reason: String): String = when (reason) {
        "turn already ended" -> "你已经结束回合了，这项操作没生效（点「结束回合」可以撤回）"
        "game paused" -> "对局已暂停，这项操作没生效"
        "server settling" -> "服务端正在结算本回合，这项操作没生效"
        "not allowed to operate" -> "现在轮不到你操作"
        else -> "服务端拒绝：$reason"
    }

    private fun handleOpRejected(msg: JsonObject) {
        val ws = worldScreenRef?.get() ?: return
        // UncivSync: 把拒绝原因**显示出来** —— 以前只管单位回滚，城市生产之类被拒时玩家
        // 什么都看不到，表现就是"点了没反应 / 建造不了东西"。
        val reason = (msg["reason"] as? JsonPrimitive)?.contentOrNull
        if (!reason.isNullOrEmpty()) notice(explainReject(reason))
        // 第三十二批起无乐观执行：本地从未动过状态，拒绝即回执，无需回滚。
        ws.shouldUpdate = true
    }

    private fun reloadGame(turn: Int) {
        // The server advanced the turn and pushes the full post-turn sim (which handleSim applies
        // onto the local GameInfo). We must NOT run gameInfo.nextTurn() locally: that would
        // re-run AI automation / rotate currentPlayerCiv and fight the server's authoritative
        // state, and in the past it triggered a WorldScreen rebuild → WebSocket drop → reconnect.
        // Just flag a refresh; handleSim does the actual state sync.
        worldScreenRef?.get()?.let { ws -> ws.shouldUpdate = true }
    }

    // ===== sim state application =====

    /**
     * 收到的 sim 快照队列。
     *
     * 对齐 UncivGC 的 `StateSnapshot` + `stateMergeLock` + `applyStateLoop`，但做法更保守：
     * 它做的是"按字段合并成一份再应用"，我们做的是"**把这一批 sim 在同一个 GL 线程 pass 里
     * 顺序应用完**"。两者得到的效果一样（一次 GL 应用、不逐条重排 UI、不并发改对象），
     * 但顺序语义完全保留 —— 每条 sim 都是"当前真相的投影"，按到达顺序落地最不容易出错。
     */
    private val pendingSims = java.util.concurrent.ConcurrentLinkedQueue<JsonObject>()
    private val simApplyScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * sim 入口：**只入队**，不在网络协程里改 GameInfo。
     *
     * 以前这里直接在当前协程里改 GameInfo + 碰 worldScreen —— 也就是在渲染线程之外
     * 改正在被渲染的对象（GC 那边是显式放到 GL 线程应用的）。现在一律排到 GL 线程，
     * 且同一批到达的多条 sim 只触发一次应用。
     */
    private fun handleSim(msg: JsonObject) {
        val state = msg["state"]?.jsonObject ?: return
        // 整档 sim（full=true）已经包含全部状态 —— 丢掉队列里更旧的增量，别白算
        if ((state["full"] as? JsonPrimitive)?.booleanOrNull == true) pendingSims.clear()
        pendingSims.add(state)
        scheduleSimApply()
    }

    private fun scheduleSimApply() {
        // CAS 防重复排程：已经在排/在跑就不再插一条
        if (!simApplyScheduled.compareAndSet(false, true)) return
        Concurrency.runOnGLThread {
            // 先释放标志再消费：这样"poll 到空之后又有新 sim 入队"一定能再排一次，
            // 不会出现"那条 sim 永远躺在队列里"。多排一次是安全的（drain 会一直 poll 到空）。
            simApplyScheduled.set(false)
            while (true) {
                val state = pendingSims.poll() ?: break
                try {
                    val game = worldScreenRef?.get()?.gameInfo
                    if (game != null) {
                        com.unciv.logic.FsAudit.applyingServerState(game) { applySim(state) }
                    }
                    // 第三十一批：常规 op 不再做整档对账（增量已是 per-entity 全字段包）。
                    // full 快照（回合边界）自带权威整档 → 强制重建一次，天然消除回合内漂移。
                    // 整档重建不依赖当前屏幕（loadGame 会重建整个 WorldScreen）—— 屏幕
                    // 引用为空的重建窗口内也必须排队，否则这条权威整档被消费后无人补发，
                    // 窗口内到达的增量全部丢失。
                    if ((state["full"] as? JsonPrimitive)?.booleanOrNull == true &&
                        state["authoritativeGame"] != null) {
                        val rev = (state["authoritativeRevision"] as? JsonPrimitive)?.longOrNull ?: 0L
                        val turn = (state["turn"] as? JsonPrimitive)?.intOrNull
                            ?: game?.turns ?: -1
                        queueAuthoritativeRebase(AuthoritativeSnapshot(rev, turn, "",
                            state["authoritativeGame"].toString(), force = true))
                    }
                } catch (t: Throwable) {
                    println("[FrameSync] 应用 sim 失败: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
            // 一批 sim 应用完之后，统一把"纯本地 UI 状态"贴回去（对齐 GC 的 reapplyLocalDueSeen）
            try {
                worldScreenRef?.get()?.let { reapplyLocalState(it) }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Prove that the lightweight in-place patch produced the exact same
     * recursively serialized GameInfo as the server. If even one nested key or
     * value differs, queue the complete official serialization for a rebase.
     */
    /**
     * 把"纯本地、服务端不知道"的 UI 状态贴回 GameInfo。
     *
     * GC 那边有 `localDueSeen`（单位"已跳过/已提醒"标记），sim 应用后必须重新贴回，
     * 否则会被服务端数据冲掉。我们这边这类状态本来就少（单位 action / 移动力都是服务端权威），
     * 目前只在这里集中处理，避免以后到处散落。
     */
    private fun reapplyLocalState(worldScreen: WorldScreen) {
        worldScreen.shouldUpdate = true
    }

    private fun applySim(state: JsonObject) {
        val worldScreen = worldScreenRef?.get() ?: return
        val gameInfo = worldScreen.gameInfo
        // #12 可见性/见面差分（对齐 GC 的 unitVisibilityPos / meetUnitPos）：
        // 记录"位置相关状态"是否变了，末尾据此决定是否刷新视野，避免每个 sim 都全量重算。
        var viewChanged = false

        // 战斗结果（服务端权威）：必须在**任何状态改动之前**取到双方单位视图 ——
        // 阵亡单位马上会被 removedUnits 从本地移除，之后就定位不到了。
        val battleJson = state["battle"]?.jsonObject
        val battleDmgToAttacker = (battleJson?.get("dmgToAttacker") as? JsonPrimitive)?.intOrNull ?: 0
        val battleDmgToDefender = (battleJson?.get("dmgToDefender") as? JsonPrimitive)?.intOrNull ?: 0
        val battleAttackerView: CombatantView? =
            (battleJson?.get("attackerId") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
                ?.let { id -> findUnitById(gameInfo, id) }
                ?.let { worldScreen.selectedGameView.getForeignMapUnitView(it).asCombatant() }
        val battleDefenderView: CombatantView? =
            (battleJson?.get("defenderId") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
                ?.let { id -> findUnitById(gameInfo, id) }
                ?.let { worldScreen.selectedGameView.getForeignMapUnitView(it).asCombatant() }

        val turn = (state["turn"] as? JsonPrimitive)?.intOrNull ?: gameInfo.turns
        if (turn > gameInfo.turns) {
            gameInfo.turns = turn
            // A new turn rolled over: clear our "ended my turn" flag so the NextTurnButton
            // flips back from "x/n ended" to the normal action.
            myTurnFinished = false
            worldScreen.shouldUpdate = true
        }

        // Keep the authoritative currentPlayer (the server rotates it in nextTurn) aligned
        // locally. Without this the official 3s poller sees a mismatch with the server
        // preview (hasLatestGameState checks turns AND currentPlayer) and used to trigger a
        // full save re-download + screen rebuild every turn.
        (state["currentPlayer"] as? JsonPrimitive)?.contentOrNull?.let { player ->
            if (player.isNotEmpty() && player != gameInfo.currentPlayer) {
                val civ = gameInfo.civilizations.firstOrNull { it.civID == player || it.civName == player }
                if (civ != null) {
                    gameInfo.currentPlayer = player
                    gameInfo.currentPlayerCiv = civ
                    worldScreen.shouldUpdate = true
                }
            }
        }

        // ---- UncivSync: 服务端已销毁的单位（建城消耗的 settler、阵亡、解散…）----
        // 必须即时从本地移除，否则会留下"幽灵单位"：UI 仍显示它、玩家还能继续下达指令，
        // 而后端每条 op 都回 UNIT NOT FOUND（正是"点建城没反应、反复点"的根因之一）。
        // 【顺序】必须在下面的单位位置循环**之前**应用：战斗击杀后胜者要踏上阵亡者的格子，
        // 这条权威快照里"移除 + 胜者新坐标"是同一次状态。若先处理胜者，阵亡者还占着
        // 目标格，官方 moveToTile 的 canMoveTo 会拒绝（而且是**静默 return**，不抛异常，
        // forcePlace 兜底也不会触发）—— 表现就是"打赢了却不站上去，下回合整档才纠正"。
        (state["removedUnits"] as? JsonArray)?.let { arr ->
            for (e in arr) {
                val removedId = (e as? JsonPrimitive)?.intOrNull ?: continue
                val unit = findUnitById(gameInfo, removedId) ?: continue
                try {
                    unit.currentTile?.removeUnit(unit)
                    unit.civ.units.removeUnit(unit, false)
                    unit.civ.cache.updateViewableTiles()
                    worldScreen.shouldUpdate = true
                    viewChanged = true
                } catch (t: Throwable) {
                    println("[FrameSync] 移除单位失败 id=$removedId: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }

        val units = state["units"]?.jsonArray
            ?: kotlinx.serialization.json.JsonArray(emptyList())
        var hit = 0
        var missed = 0
        // A tile can legitimately contain more than one unit over the lifetime of a
        // multiplayer client (especially while a city is producing units). Never let two
        // server entries consume the same local object during one pass. Once ids have been
        // aligned, locateUnit prefers that stable identity; before that, this set provides a
        // deterministic one-to-one fallback for equal units.
        val matchedLocalUnits = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<MapUnit, Boolean>()
        )
        for (unitJson in units) {
            // Each unit is applied in isolation: one bad entry (unit gone, tile not passable,
            // movement refused) must never abort the whole sim — that used to leave every
            // later unit in the list unserialized, which showed up as "his unit never moved"
            // and "my unit has no movement left next turn".
            try {
                val u = unitJson.jsonObject
                val x = (u["x"] as? JsonPrimitive)?.intOrNull ?: continue
                val y = (u["y"] as? JsonPrimitive)?.intOrNull ?: continue
                val civName = (u["civ"] as? JsonPrimitive)?.contentOrNull
                val name = (u["name"] as? JsonPrimitive)?.contentOrNull
                val serverId = (u["id"] as? JsonPrimitive)?.intOrNull
                val prevX = (u["fromX"] as? JsonPrimitive)?.intOrNull
                val prevY = (u["fromY"] as? JsonPrimitive)?.intOrNull

                val located = locateUnit(gameInfo, x, y, civName, name, serverId, prevX, prevY, matchedLocalUnits)
                val unit = located
                    ?: createUnitFromState(gameInfo, u, x, y, civName, name, serverId, worldScreen)
                if (unit == null) {
                    missed++
                    continue
                }
                // 新建单位（本地此前没有）或发生位移都会改变视野 —— 置位差分标记
                if (located == null) viewChanged = true
                matchedLocalUnits.add(unit)
                hit++

                // The server has applied our op for this unit → drop the optimistic pending entry
                // so a later sim/reject can't roll back state we already agree on.
                // Realign id so the client and server agree on this unit's identity.
                if (serverId != null && unit.id != serverId) {
                    unit.id = serverId
                    worldScreen.shouldUpdate = true
                }
                // 第三十一批：单位 = 官方序列化整对象。服务端发全部序列化字段，这里按
                // 反射搬进**现有实例**（currentTile 等 transient 保留 → 视图引用稳定），
                // 然后重链单位瞬态。替代原手写字段清单（曾反复漏：轨迹/晋升/xp…）。
                (u["obj"] as? JsonPrimitive)?.contentOrNull?.let { o ->
                    if (o.length > 20) {
                        try {
                            val srcU = com.unciv.json.json().fromJson(MapUnit::class.java, o)
                            // 按"obj JSON 里实际在场的字段"搬运：服务端快照是全字段写出，
                            // 在场（包括值为默认值，如升级后行动力=0）一律照搬。
                            val objElement = json.parseToJsonElement(o).jsonObject
                            com.unciv.logic.FsStateSplice.copyJsonFields(
                                unit, srcU, objElement, MapUnit::class.java)
                            unit.setTransients(gameInfo.ruleset)
                            worldScreen.shouldUpdate = true
                        } catch (t: Throwable) {
                            println("[FrameSync] 单位整对象应用失败: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }

                val tile = gameInfo.tileMap[x, y]
                if (tile != null && unit.currentTile != tile) {
                    try {
                        unit.movement.moveToTile(tile, false)
                    } catch (t: Throwable) {
                        // Teleport-sync fallback: the authoritative position wins even when the
                        // local movement rules would refuse the step (enemy zone, no movement…).
                        println("[FrameSync] moveToTile 失败(${unit.baseUnit.name} ${unit.civ.civName} " +
                                "-> $x,$y): ${t.javaClass.simpleName}")
                        forcePlaceUnitForSync(unit, tile)
                    }
                    viewChanged = true   // 位移即视野变化
                    worldScreen.shouldUpdate = true
                    // 官方探索路径（与官方客户端自己移动单位后的调用一致）：updateVisibleTiles
                    // 会更新该单位的可见集、触发"发现地块"类 unique，并级联
                    // civ.cache.updateViewableTiles —— 后者把新见格子标进 exploredBy，
                    // 这是【重迷雾 → 轻迷雾】揭开的唯一入口。缺了它：可见集（轻迷雾内
                    // 的内容）能刷新，但从未探索过的格子永远黑着，直到下回合整档重建。
                    try { unit.updateVisibleTiles() } catch (_: Throwable) {}
                }
                (u["hp"] as? JsonPrimitive)?.intOrNull?.let { hp ->
                    if (unit.health != hp) unit.health = hp
                }
                (u["actions"] as? JsonPrimitive)?.contentOrNull?.let { actions ->
                    actions.toFloatOrNull()?.let { mv ->
                        if (unit.currentMovement != mv) unit.currentMovement = mv
                    }
                }
                // Sync the unit's action state (explore / fortify / sleep / ...) so the client's
                // per-turn doAction() actually runs the automation the player requested. The server
                // is authoritative for this flag; without it, "explore"/"fortify" ops succeed on the
                // server but the client's unit never moves because its local action is still null.
                (u["action"] as? JsonPrimitive)?.contentOrNull?.let { action ->
                    val normalized = action.takeIf { it.isNotEmpty() && it != "null" }
                    if (unit.action != normalized) {
                        unit.action = normalized
                        worldScreen.shouldUpdate = true
                    }
                }
                (u["automated"] as? JsonPrimitive)?.contentOrNull?.let { automated ->
                    val isAutomated = automated == "true"
                    if (unit.automated != isAutomated) {
                        unit.automated = isAutomated
                        worldScreen.shouldUpdate = true
                    }
                }
                // UncivSync: 单位自定义名（改名）—— 服务端权威。空串表示"无自定义名"。
                (u["instanceName"] as? JsonPrimitive)?.contentOrNull?.let { instName ->
                    val normalized = instName.takeIf { it.isNotEmpty() && it != "null" }
                    if (unit.instanceName != normalized) {
                        unit.instanceName = normalized
                        worldScreen.shouldUpdate = true
                    }
                }
                // UncivSync: 本回合已攻击次数 —— 战斗由服务端结算，本地也要拿到这个计数，
                // 否则"这回合还能不能再打"的判断会和服端不一致（本地显示可攻击、服务端已拒绝）。
                (u["attacksThisTurn"] as? JsonPrimitive)?.intOrNull?.let { atk ->
                    val cur = unit.attacksSinceTurnStart.size
                    if (atk in 0 until cur) {
                        // 服务端更少：本地多记了 → 截断
                        while (unit.attacksSinceTurnStart.size > atk) unit.attacksSinceTurnStart.removeAt(0)
                    } else if (atk > cur) {
                        // 服务端更多：补占位坐标（该列表只用来计数 + 画攻击箭头）
                        repeat(atk - cur) {
                            unit.attacksSinceTurnStart.add(unit.currentTile?.position ?: HexCoord(0, 0))
                        }
                    }
                }
                // UncivSync: 攻击目标坐标（服务端权威）——上面按计数补的占位坐标画箭头会指错方向，
                // 有精确坐标时整体替换，远程攻击箭头才指向真实目标。
                (u["atk"] as? JsonArray)?.let { atkList ->
                    val targets = atkList.mapNotNull { p ->
                        val o = p as? JsonObject ?: return@mapNotNull null
                        val ax = (o["x"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                        val ay = (o["y"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                        HexCoord(ax, ay)
                    }
                    if (targets != unit.attacksSinceTurnStart) {
                        unit.attacksSinceTurnStart = targets.toCollection(ArrayList())
                        worldScreen.shouldUpdate = true
                    }
                }
                // UncivSync: 移动轨迹（上一回合→现在）。movementMemories / mostRecentMoveType 由
                // 服务端回合钩子滚动维护（最多 2 条），FS 客户端不跑本地回合、本地无人更新——
                // 不同步的话 updateMovementOverlay 画的箭头会一直停在多回合前的旧轨迹上。
                (u["mm"] as? JsonArray)?.let { mmList ->
                    val memories = mmList.mapNotNull { entry ->
                        val o = entry as? JsonObject ?: return@mapNotNull null
                        val mx = (o["x"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                        val my = (o["y"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                        val type = (o["t"] as? JsonPrimitive)?.contentOrNull
                            ?.let { runCatching { UnitMovementMemoryType.valueOf(it) }.getOrNull() }
                            ?: UnitMovementMemoryType.UnitMoved
                        MapUnit.UnitMovementMemory(HexCoord(mx, my), type)
                    }
                    if (memories.isNotEmpty() && memories != unit.movementMemories) {
                        unit.movementMemories = memories.toCollection(ArrayList())
                        worldScreen.shouldUpdate = true
                    }
                }
                (u["mrmt"] as? JsonPrimitive)?.contentOrNull?.let { t ->
                    runCatching { UnitMovementMemoryType.valueOf(t) }.getOrNull()?.let { type ->
                        if (unit.mostRecentMoveType != type) {
                            unit.mostRecentMoveType = type
                            worldScreen.shouldUpdate = true
                        }
                    }
                }
                // UncivSync: 晋升状态（第二十批）。战斗在服务端结算、XP 在服务端涨，
                // 这里不同步的话客户端 XP 永远是旧值 —— 晋升按钮永远不出现。
                // 直接对齐最终状态（无副作用），不能调 addPromotion（它会扣 XP/触发事件）。
                (u["xp"] as? JsonPrimitive)?.intOrNull?.let { xp ->
                    if (unit.promotions.XP != xp) {
                        unit.promotions.XP = xp
                        worldScreen.shouldUpdate = true
                    }
                }
                (u["promotions"] as? JsonArray)?.let { plist ->
                    val remote = plist.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toHashSet()
                    if (remote.isNotEmpty() && unit.promotions.promotions != remote) {
                        unit.promotions.promotions.clear()
                        unit.promotions.promotions.addAll(remote)
                        worldScreen.shouldUpdate = true
                    }
                }
                (u["promoCount"] as? JsonPrimitive)?.intOrNull?.let { pc ->
                    if (unit.promotions.numberOfPromotions != pc) {
                        unit.promotions.numberOfPromotions = pc
                        worldScreen.shouldUpdate = true
                    }
                }
                // UncivSync: 单位变身（升级换兵种）——服务端的 name 是升级后的新名。
                // 不应用的话对手看你永远是旧兵种贴图，且后续按 name 定位会 miss。
                (u["name"] as? JsonPrimitive)?.contentOrNull?.let { n ->
                    if (n.isNotEmpty() && unit.baseUnit.name != n) {
                        try {
                            unit.name = n
                            unit.setTransients(gameInfo.ruleset)
                            worldScreen.shouldUpdate = true
                        } catch (t: Throwable) {
                            println("[FrameSync] 单位变身应用失败: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }
                // UncivSync: 本回合是否已行动（due）——空闲单位循环靠它，不同步会出现
                // "已操作过的单位还反复出现在空闲列表里"。
                (u["due"] as? JsonPrimitive)?.contentOrNull?.let { v ->
                    val d = v == "true"
                    if (unit.due != d) {
                        unit.due = d
                        worldScreen.shouldUpdate = true
                    }
                }
                // 驻守回合数（防御加成输入）
                (u["fortifyTurns"] as? JsonPrimitive)?.intOrNull?.let { ft ->
                    if (unit.turnsFortified != ft) {
                        unit.turnsFortified = ft
                        worldScreen.shouldUpdate = true
                    }
                }
            } catch (t: Throwable) {
                missed++
                println("[FrameSync] 应用单位 sim 失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        if (missed > 0) {
            println("[FrameSync] sim turn=$turn 单位条目=${units.size} 命中=$hit 未命中=$missed " +
                "(未命中通常是本地这份存档里没有该单位：阶段1'统一下载服务端存档'尚未接入)")
        }

        // ---- UncivSync: 服务端已移除的单位已在上方（单位位置循环之前）应用 ----

        // ---- UncivSync: 服务端已移除的城市（被摧毁 / 焚毁）----
        (state["removedCities"] as? JsonArray)?.let { arr ->
            for (e in arr) {
                val removedCityId = (e as? JsonPrimitive)?.contentOrNull ?: continue
                val city = gameInfo.civilizations.asSequence()
                    .flatMap { it.cities.asSequence() }
                    .firstOrNull { it.id == removedCityId } ?: continue
                try {
                    val owner = city.civ
                    owner.cities = owner.cities.filter { it.id != removedCityId }
                    worldScreen.shouldUpdate = true
                } catch (t: Throwable) {
                    println("[FrameSync] 移除城市失败 id=$removedCityId: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }

        // ---- 城市状态（生产队列 / 工作地块 / 专家 / 焦点 / 避免增长 …）----
        // 以前这里完全缺失：服务端一直在发城市数据，客户端直接丢弃，所以"别人改了生产/
        // 专家/工作地块"永远看不到。城市写操作现在统一由 CityView 上报 op，服务端回推后
        // 由这里落到本地，所有客户端（含操作者自己）看到同一份状态。
        state["cities"]?.jsonArray?.let { cityArr ->
            val applied = applyCities(gameInfo, cityArr)
            if (applied > 0) worldScreen.shouldUpdate = true
        }

        // ---- 文明状态（交易请求 / 外交通知 / 政策 / 科技）----
        state["civs"]?.jsonArray?.let { civArr ->
            val applied = applyCivs(gameInfo, civArr)
            if (applied > 0) worldScreen.shouldUpdate = true
        }

        // ---- 地块（改良 / 道路 / 掠夺 / 工人施工进度）----
        // 这条通道以前不存在 —— 地块只能靠"每回合整档"纠正，而整档会重建整个 WorldScreen，
        // 那正是卡顿的主因。有了它，常规回合服务端不再需要整档（见 maybeBroadcastFullSave）。
        state["tiles"]?.jsonArray?.let { tileArr ->
            val applied = applyTiles(gameInfo, tileArr)
            if (applied > 0) { viewChanged = true; worldScreen.shouldUpdate = true }
        }

        // ---- 宗教全量（官方序列化器整对象，与存档同格式）----
        state["religions"]?.jsonArray?.let { relArr ->
            try {
                val parsed = com.unciv.json.json().fromJson(
                    Array<com.unciv.models.Religion>::class.java, relArr.toString())
                val map = HashMap<String, com.unciv.models.Religion>()
                for (r in parsed) { r.setTransients(gameInfo); map[r.name] = r }
                if (map != gameInfo.religions) {
                    gameInfo.religions = map
                    worldScreen.shouldUpdate = true
                }
            } catch (t: Throwable) {
                println("[FrameSync] 宗教应用失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        // ---- 蛮族营地（坐标增量，对齐 GC 的 encampments）----
        // 服务端只发"营地坐标列表"，这里把对应地块的改良设为蛮族营地；营地消失由
        // applyTiles（improvement=""）负责清掉，这里只管加上，不抢着清。
        state["encampments"]?.jsonArray?.let { arr ->
            val applied = applyEncampments(gameInfo, arr)
            if (applied > 0) { viewChanged = true; worldScreen.shouldUpdate = true }
        }

        // ---- 地形突变（核弹改地形等，增量就地改，对齐 GC 的 terrainChanges）----
        // worldSignature 的整档兜底仍在（地形签名一变就整档），所以即便这里就地改失败也能纠正。
        state["terrainChanges"]?.jsonArray?.let { arr ->
            val applied = applyTerrainChanges(gameInfo, arr)
            if (applied > 0) worldScreen.shouldUpdate = true
        }

        // ---- 战斗动画（服务端权威结算）----
        // 本地不再掷骰子，战斗结果随 sim 一起发来；这里按服务端的伤害数字播放动画。
        // 放在最后：此时单位位置/血量/阵亡都已应用，动画依赖的 sprite 位置也更新好了。
        if (battleAttackerView != null && battleDefenderView != null) {
            try {
                worldScreen.battleAnimationDeferred(
                    battleAttackerView, battleDmgToAttacker,
                    battleDefenderView, battleDmgToDefender
                )
            } catch (t: Throwable) {
                println("[FrameSync] 播放战斗动画失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        // ---- #12 可见性/见面差分（对齐 GC 的 unitVisibilityPos / meetUnitPos）----
        // 不每个 sim 都全量重算视野：只有"位置相关状态变了"（单位移动/增删、地块/营地/地形突变）
        // 才刷新本文明的可见地块。其余 sim 不动视野，省掉昂贵的 updateViewableTiles。
        if (viewChanged) {
            // 刷新的是【渲染视角的文明】（selectedGameView 即 MapVisualization 的基准）。
            // 以前用 ownCiv（按 civ.playerId 匹配本地玩家）—— 我们架构里"玩家→文明"归属
            // 记在服务端边车、存档 playerId 并不可靠，ownCiv 常年返回 null，这里静默空转。
            try {
                worldScreen.selectedGameView.civView.getCiv().cache.updateViewableTiles()
            } catch (_: Throwable) {}
        }

        worldScreen.shouldUpdate = true
    }

    /**
     * 应用文明级快照。目前是**交易请求**——玩家→玩家实时交易的"送达"通道：
     * 服务端把请求写进权威存档 → sim 携带 `civ.tradeRequests` → 这里写回本地，
     * 官方的 WorldScreen 会发现 tradeRequests 非空并自动弹出交易窗口（TradePopup）。
     */
    /**
     * 应用间谍投影（服务端发的是**全量**名单，所以这里做一次按 name 的对齐）。
     *
     * 顺序很重要：**先定位、再设行动**。因为 `Spy.moveTo(city)` 内部会把行动改成
     * `Moving(1)` —— 必须让投影里的 action/turns 最后落地，否则会把服务端的真实行动盖成 Moving。
     *
     * 另外：`Spy` 的 civInfo / espionageManager / city 都是 @Transient，新建的间谍要
     * `setTransients(civ)`；city 不用手动 relink —— `getCityOrNull()` 会按 location 自动解析。
     */
    private fun applySpies(civ: Civilization, arr: JsonArray, gameInfo: GameInfo): Int {
        var applied = 0
        try {
            val em = civ.espionageManager ?: return 0
            val byName = em.spyList.associateBy { it.name }.toMutableMap()
            val seen = HashSet<String>()
            for (elem in arr) {
                val o = elem as? JsonObject ?: continue
                val spyName = (o["name"] as? JsonPrimitive)?.contentOrNull ?: continue
                seen.add(spyName)
                val rank = (o["rank"] as? JsonPrimitive)?.intOrNull ?: 1

                val spy = byName[spyName] ?: run {
                    val created = Spy(spyName, rank)
                    created.setTransients(civ)
                    em.spyList.add(created)
                    applied++
                    created
                }

                // ① 位置（cityId 为空 = 在老巢）
                val cityId = (o["cityId"] as? JsonPrimitive)?.contentOrNull
                val targetCity = if (cityId.isNullOrEmpty()) null
                    else gameInfo.getCities().firstOrNull { it.id == cityId }
                if (spy.getCityOrNull() != targetCity) {
                    try { spy.moveTo(targetCity) } catch (_: Throwable) {}
                }

                // ② 行动 + 剩余回合（必须放在位置之后，见函数注释）
                val actionName = (o["action"] as? JsonPrimitive)?.contentOrNull
                val turns = (o["turns"] as? JsonPrimitive)?.intOrNull ?: 0
                if (actionName != null) {
                    try {
                        val action = SpyAction.valueOf(actionName)
                        if (spy.action != action || spy.turnsRemainingForAction != turns) {
                            spy.setAction(action, turns)
                            applied++
                        }
                    } catch (_: Throwable) {}
                }
            }
            // 服务端已经没有的间谍（阵亡 / 被清理）本地也清掉
            if (em.spyList.removeAll { it.name !in seen }) applied++
        } catch (t: Throwable) {
            println("[FrameSync] 应用间谍 sim 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
        return applied
    }

    /** 反序列化并整体替换 religionManager（服务端权威），失败只打日志不中断本条 sim。 */
    private fun applyReligionManager(civ: Civilization, jsonText: String) {
        try {
            civ.religionManager = com.unciv.json.json().fromJson(
                com.unciv.logic.civilization.managers.ReligionManager::class.java, jsonText)
            civ.religionManager.setTransients(civ)
        } catch (t: Throwable) {
            println("[FrameSync] 宗教管理器应用失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 反序列化并整体替换 questManager（城邦任务），失败只打日志。 */
    private fun applyQuestManager(civ: Civilization, jsonText: String) {
        try {
            civ.questManager = com.unciv.json.json().fromJson(
                com.unciv.logic.civilization.managers.quests.QuestManager::class.java, jsonText)
            civ.questManager.setTransients(civ)
        } catch (t: Throwable) {
            println("[FrameSync] 任务管理器应用失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun applyCivs(gameInfo: GameInfo, civs: JsonArray): Int {
        var applied = 0
        for (entry in civs) {
            try {
                val o = entry.jsonObject
                val name = (o["civ"] as? JsonPrimitive)?.contentOrNull ?: continue
                val civ = gameInfo.civilizations.firstOrNull { it.civName == name } ?: continue

                // 城邦任务 / 宗教管理器（含信仰存量）—— 官方序列化器整对象，替换后重链瞬态
                // 服务端把 religionManager 放进来的是**序列化后的 JSON 字符串**（FsJson 里
                // 嵌套字符串），不是 JSON 对象 —— 以前 `as? JsonObject` 强转失败整块静默
                // 跳过，宗教状态（religionState/storedFaith）永远不更新：建完万神殿/宗教
                // 后"Found Pantheon/Religion"按钮不消退，再点被服务端拒绝 → 回合按钮
                // 卡死。两种形态都接受。
                when (val rmElement = o["religionManager"]) {
                    is JsonObject -> { applyReligionManager(civ, rmElement.toString()); applied++ }
                    is JsonPrimitive -> rmElement.contentOrNull?.let { applyReligionManager(civ, it); applied++ }
                    else -> {}
                }
                // questManager 同病：服务端发的也是字符串。
                when (val qmElement = o["questManager"]) {
                    is JsonObject -> { applyQuestManager(civ, qmElement.toString()); applied++ }
                    is JsonPrimitive -> qmElement.contentOrNull?.let { applyQuestManager(civ, it); applied++ }
                    else -> {}
                }
                // 生效中的交易协议（停战 / 开放边界 / 研究协议，剩余回合在 Trade 对象内）
                (o["trades"] as? JsonObject)?.let { tm ->
                    try {
                        for ((otherName, listJson) in tm) {
                            val arr = listJson as? JsonArray ?: continue
                            val dm = civ.diplomacy[otherName] ?: continue
                            val parsed = com.unciv.json.json().fromJson(
                                Array<com.unciv.logic.trade.Trade>::class.java, arr.toString())
                            dm.trades = parsed.toCollection(ArrayList())
                            applied++
                        }
                    } catch (t: Throwable) {
                        println("[FrameSync] 交易协议应用失败: ${t.javaClass.simpleName}")
                    }
                }
                // UncivSync: 金币（服务端权威结算购买/收入后的余量）。gold 是 private set，
                // 走 addGold 差值 —— 不同步的话"金币购买后数字不变"。
                (o["gold"] as? JsonPrimitive)?.intOrNull?.let { g ->
                    val delta = g - civ.gold
                    if (delta != 0) {
                        try { civ.addGold(delta) } catch (_: Throwable) {}
                        applied++
                    }
                }

                // UncivSync: 间谍 —— 服务端发的是**全量**名单，所以按 name 对齐：
                // 本地有的更新字段、本地缺的新建、服务端没有的移除。
                // （以前完全没有这个通道，于是"派间谍 / 政变"只改了本地，服务端那份原样不动）
                (o["spies"] as? JsonArray)?.let { arr ->
                    applied += applySpies(civ, arr, gameInfo)
                }

                // UncivSync: 免费伟人名额 —— 伟人选择屏的弹出条件就是 freeGreatPeople > 0，
                // 所以这个数字必须是权威的，否则"该不该弹"两边判断不一致。
                (o["greatPeople"] as? JsonObject)?.let { g ->
                    val free = (g["freeGreatPeople"] as? JsonPrimitive)?.intOrNull
                    if (free != null && civ.greatPeople.freeGreatPeople != free) {
                        civ.greatPeople.freeGreatPeople = free
                        applied++
                    }
                    val maya = (g["mayaLimitedFreeGP"] as? JsonPrimitive)?.intOrNull
                    if (maya != null && civ.greatPeople.mayaLimitedFreeGP != maya) {
                        civ.greatPeople.mayaLimitedFreeGP = maya
                        applied++
                    }
                    (g["longCountGPPool"] as? JsonArray)?.let { poolArr ->
                        val remote = poolArr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                        if (civ.greatPeople.longCountGPPool != remote) {
                            civ.greatPeople.longCountGPPool.clear()
                            civ.greatPeople.longCountGPPool.addAll(remote)
                            applied++
                        }
                    }
                }

                (o["tradeRequests"] as? JsonArray)?.let { arr ->
                    val rebuilt = ArrayList<TradeRequest>()
                    for (e in arr) {
                        try {
                            val eo = e.jsonObject
                            val from = (eo["from"] as? JsonPrimitive)?.contentOrNull ?: continue
                            val tradeJson = (eo["tradeJson"] as? JsonPrimitive)?.contentOrNull ?: continue
                            val trade = json().fromJson(Trade::class.java, tradeJson) ?: continue
                            val tr = TradeRequest()
                            tr.requestingCiv = from
                            tr.trade = trade
                            rebuilt.add(tr)
                        } catch (_: Throwable) {
                        }
                    }
                    if (tradeRequestsSignature(civ.tradeRequests) != tradeRequestsSignature(rebuilt)) {
                        civ.tradeRequests.clear()
                        civ.tradeRequests.addAll(rebuilt)
                        applied++
                    }
                }
                // UncivSync: 外交通知（宣友提议 / 索求）—— 写回本地 popupAlerts，
                // 官方 WorldScreen.update 会发现非空并自动弹 AlertPopup。
                (o["alerts"] as? JsonArray)?.let { arr ->
                    val rebuilt = ArrayList<PopupAlert>()
                    for (e in arr) {
                        try {
                            val eo = e.jsonObject
                            val typeName = (eo["type"] as? JsonPrimitive)?.contentOrNull ?: continue
                            val value = (eo["value"] as? JsonPrimitive)?.contentOrNull ?: ""
                            val at = AlertType.entries.firstOrNull { it.name == typeName } ?: continue
                            // 首次见才加入（防重复弹窗）
                            if (!seenAlertKeys.add("${civ.civName}|$typeName|$value")) continue
                            val pa = PopupAlert()
                            pa.type = at
                            pa.value = value
                            rebuilt.add(pa)
                        } catch (_: Throwable) {
                        }
                    }
                    if (rebuilt.isNotEmpty()) {
                        civ.popupAlerts.addAll(rebuilt)
                        applied++
                    }
                }

                // UncivSync: 外交关系（状态 + 谴责/宣友/承诺标记）—— 否则对方谴责你后界面看不出变化
                (o["diplomacy"] as? JsonArray)?.let { arr ->
                    for (e in arr) {
                        try {
                            val eo = e.jsonObject
                            val with = (eo["with"] as? JsonPrimitive)?.contentOrNull ?: continue
                            val other = gameInfo.civilizations.firstOrNull { it.civID == with } ?: continue
                            // diplomacy 映射本就以 civID 为 key（getDiplomacyManager(civID) 同理），
                            // 服务端投影的 with 就是 civID —— 直接命中，miss 才走 Meet。
                            val dm = civ.diplomacy[with] ?: civ.getDiplomacyManagerOrMeet(other)
                            (eo["status"] as? JsonPrimitive)?.contentOrNull?.let { st ->
                                DiplomaticStatus.entries.firstOrNull { it.name == st }?.let { dm.diplomaticStatus = it }
                            }
                            // Opinion is the persisted diplomatic modifier map, not a
                            // derivation of status/flags. Apply it before the UI reads the
                            // relationship level, otherwise a fresh sim can show zero opinion.
                            // (key 是修改器名字符串 —— DiplomacyManager.diplomaticModifiers
                            //  本身就是 HashMap<String, Float>，hasModifier 用 enum.name 查)
                            (eo["modifiers"] as? JsonObject)?.let { mo ->
                                val remote = HashMap<String, Float>()
                                for ((modifier, value) in mo)
                                    (value as? JsonPrimitive)?.floatOrNull?.let { remote[modifier] = it }
                                if (dm.diplomaticModifiers != remote) {
                                    dm.diplomaticModifiers.clear()
                                    dm.diplomaticModifiers.putAll(remote)
                                    applied++
                                }
                            }
                            (eo["smoothedOpinion"] as? JsonPrimitive)?.floatOrNull?.let { opinion ->
                                try {
                                    val field = dm.javaClass.getDeclaredField("smoothedOpinionOfOtherCiv")
                                    field.isAccessible = true
                                    if (field.getFloat(dm) != opinion) {
                                        field.setFloat(dm, opinion)
                                        applied++
                                    }
                                } catch (_: Throwable) {}
                            }
                            (eo["flags"] as? JsonObject)?.let { fo ->
                                for ((flagName, flagVal) in fo) {
                                    val f = DiplomacyFlags.entries.firstOrNull { it.name == flagName } ?: continue
                                    val n = (flagVal as? JsonPrimitive)?.intOrNull ?: continue
                                    if (dm.getFlag(f) != n) dm.setFlag(f, n)
                                }
                            }
                            applied++
                        } catch (_: Throwable) {
                        }
                    }
                }
                // UncivSync: 政策 / 科技 / 文化 —— 文明级长周期状态。
                // 以前 sim 里没有这些字段，civ.choosePolicy / civ.chooseTech 的效果只落在
                // 服务端，客户端要等下一回合整档才看得到；如果政策操作恰好与"回合推进+整档下发"
                // 并发，重建出来的世界就缺这条政策 —— 表现就是"政策选择回退了"。
                // 现在以服务端为准写回，op 生效那一刻所有客户端立即一致。
                (o["adoptedPolicies"] as? JsonArray)?.let { arr ->
                    val remote = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                    val local = civ.policies.adoptedPolicies
                    if (local != remote) {
                        // ⚠️ 这是**破坏性**更新（先清空再写入），所以必须能回滚：
                        // 一旦中途抛异常又没恢复，玩家的政策会全部消失 —— 表现就是
                        // "左上角政策按钮没了 / 政策加成全失效"。先备份，失败就还原。
                        val backup = ArrayList(local)
                        try {
                            local.clear()
                            local.addAll(remote)
                            // 重建政策加成（transients）：按权威集合重算，等价于官方读档时的做法
                            civ.policies.policyUniques.clear()
                            civ.policies.setTransients(civ)
                            applied++
                        } catch (t: Throwable) {
                            local.clear()
                            local.addAll(backup)
                            println("[FrameSync] 应用政策失败，已回滚到原集合: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }
                (o["freePolicies"] as? JsonPrimitive)?.intOrNull?.let { fp ->
                    if (civ.policies.freePolicies != fp) civ.policies.freePolicies = fp
                }
                (o["storedCulture"] as? JsonPrimitive)?.intOrNull?.let { sc ->
                    if (civ.policies.storedCulture != sc) civ.policies.storedCulture = sc
                }
                // 政策选择旗 + 已采纳计数（都是权威状态）：sopp 是 addCulture 跨门槛事件
                // 只有服务端知道（客户端不跑本地回合）；noap 决定下一政策成本（私有字段反射写）
                (o["sopp"] as? JsonPrimitive)?.contentOrNull?.let { v ->
                    val b = v == "true"
                    if (civ.policies.shouldOpenPolicyPicker != b) {
                        civ.policies.shouldOpenPolicyPicker = b
                        applied++
                    }
                }
                (o["noap"] as? JsonPrimitive)?.intOrNull?.let { n ->
                    try {
                        val f = civ.policies.javaClass.getDeclaredField("numberOfAdoptedPolicies")
                        f.isAccessible = true
                        if (f.getInt(civ.policies) != n) {
                            f.setInt(civ.policies, n)
                            applied++
                        }
                    } catch (_: Throwable) {}
                }
                (o["techsToResearch"] as? JsonArray)?.let { arr ->
                    val remote = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    if (civ.tech.techsToResearch != remote) {
                        civ.tech.techsToResearch.clear()
                        civ.tech.techsToResearch.addAll(remote)
                        applied++
                    }
                }
                (o["goldPercentConvertedToScience"] as? JsonPrimitive)?.floatOrNull?.let {
                    civ.tech.goldPercentConvertedToScience = it
                }
                // 溢出科研（科技完成时的结转，下一科技起始进度）——核心私有字段，反射写
                (o["overflowScience"] as? JsonPrimitive)?.intOrNull?.let { ov ->
                    try {
                        val f = civ.tech.javaClass.getDeclaredField("overflowScience")
                        f.isAccessible = true
                        if (f.getInt(civ.tech) != ov) {
                            f.setInt(civ.tech, ov)
                            applied++
                        }
                    } catch (_: Throwable) {}
                }
                (o["hasMovedAutomatedUnits"] as? JsonPrimitive)?.booleanOrNull?.let {
                    civ.hasMovedAutomatedUnits = it
                }
                (o["disabledCityConstructions"] as? JsonArray)?.let { arr ->
                    val remote = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                    civ.disabledCityConstructions.clear()
                    civ.disabledCityConstructions.addAll(remote)
                }
                // UncivSync: 科技进度（每个科技已投入的科研点）—— 这是科技树进度条/回合数的**唯一**数据源。
                // 客户端不做结算，服务端不推它，进度条就永远停在读档那一刻（玩家反馈："科技进度不跟随回合推进"）。
                (o["techsInProgress"] as? JsonObject)?.let { prog ->
                    val remote = HashMap<String, Int>()
                    for ((techName, v) in prog) {
                        (v as? JsonPrimitive)?.intOrNull?.let { remote[techName] = it }
                    }
                    if (civ.tech.techsInProgress != remote) {
                        civ.tech.techsInProgress.clear()
                        civ.tech.techsInProgress.putAll(remote)
                        applied++
                    }
                }
                (o["freeTechs"] as? JsonPrimitive)?.intOrNull?.let { ft ->
                    if (civ.tech.freeTechs != ft) {
                        civ.tech.freeTechs = ft
                        applied++
                    }
                }
                // UncivSync: 科技「已研究」集合 —— 与 techsToResearch 互补。没有它，客户端
                // techsResearched 集合 ≠ 服务端，科技树已研究项仍显示未研究，玩家会重复选已完成科技。
                (o["techsResearched"] as? JsonArray)?.let { arr ->
                    val remote = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                    if (civ.tech.techsResearched != remote) {
                        try {
                            civ.tech.techsResearched.clear()
                            civ.tech.techsResearched.addAll(remote)
                            civ.tech.setTransients(civ)  // 重建 researchedTechnologies / techUniques / era
                            applied++
                        } catch (t: Throwable) {
                            println("[FrameSync] 应用已研究科技失败: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }
                // UncivSync: 黄金时代状态（gap #4）
                (o["goldenAge"] as? JsonObject)?.let { ga ->
                    try {
                        val mgr = civ.goldenAges
                        (ga["storedHappiness"] as? JsonPrimitive)?.intOrNull?.let {
                            if (mgr.storedHappiness != it) { mgr.storedHappiness = it; applied++ }
                        }
                        (ga["turnsLeft"] as? JsonPrimitive)?.intOrNull?.let {
                            if (mgr.turnsLeftForCurrentGoldenAge != it) { mgr.turnsLeftForCurrentGoldenAge = it; applied++ }
                        }
                        worldScreenRef?.get()?.shouldUpdate = true
                    } catch (t: Throwable) {
                        println("[FrameSync] 应用黄金时代失败: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
                // UncivSync: 通知（服务端权威）。这是核心自带的事件通道 —— 奇观被抢 / 遗迹奖励 /
                // 城陷 / 伟人诞生 / 单位被攻击 都在这，所以不需要为每种事件单独造一类消息。
                // 生命周期两端一致：TurnManager.endTurn 每回合会 clear()，服务端跑的就是它。
                (o["notifications"] as? JsonArray)?.let { arr ->
                    val want = arr.mapNotNull { e ->
                        val n = (e as? JsonObject) ?: return@mapNotNull null
                        val text = (n["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        val category = (n["category"] as? JsonPrimitive)?.contentOrNull
                            ?.let { name -> Notification.NotificationCategory.entries.firstOrNull { it.name == name } }
                            ?: Notification.NotificationCategory.General
                        val icons = (n["icons"] as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                            ?.toTypedArray() ?: emptyArray()
                        Notification(text, icons, null, category)
                    }
                    val same = civ.notifications.size == want.size &&
                        civ.notifications.indices.all { civ.notifications[it].text == want[it].text }
                    if (!same) {
                        civ.notifications.clear()
                        civ.notifications.addAll(want)
                        worldScreenRef?.get()?.shouldUpdate = true
                        applied++
                    }
                }
                // UncivSync: 城邦影响力 + 任务（gap #5）。影响力存在「城邦→主流」的外交对象里，
                // 这里以城邦为单位写回同一个城邦的 DiplomacyManager.influence 与 questManager。
                if (civ.isCityState) {
                    (o["cityStateInfluence"] as? JsonObject)?.let { inf ->
                        for ((majorId, v) in inf) {
                            val dm = civ.diplomacy[majorId] ?: continue
                            val value = (v as? JsonPrimitive)?.intOrNull ?: continue
                            try {
                                if (dm.influence.toInt() != value) {
                                    dm.influence = value.toFloat()
                                    applied++
                                }
                            } catch (t: Throwable) {
                                println("[FrameSync] 应用城邦影响力失败: ${t.javaClass.simpleName}: ${t.message}")
                            }
                        }
                        worldScreenRef?.get()?.shouldUpdate = true
                    }
                    (o["quests"] as? JsonArray)?.let { arr ->
                        try {
                            val rebuilt = ArrayList<AssignedQuest>()
                            for (e in arr) {
                                val eo = e as? JsonObject ?: continue
                                val questName = (eo["questName"] as? JsonPrimitive)?.contentOrNull ?: continue
                                rebuilt.add(AssignedQuest(
                                    questName = questName,
                                    assigner = (eo["assigner"] as? JsonPrimitive)?.contentOrNull ?: "",
                                    assignee = (eo["assignee"] as? JsonPrimitive)?.contentOrNull ?: "",
                                    assignedOnTurn = (eo["assignedOnTurn"] as? JsonPrimitive)?.intOrNull ?: 0,
                                    data1 = (eo["data1"] as? JsonPrimitive)?.contentOrNull ?: "",
                                    data2 = (eo["data2"] as? JsonPrimitive)?.contentOrNull ?: "",
                                ))
                            }
                            val qm = civ.questManager
                            val f = qm.javaClass.getDeclaredField("assignedQuests")
                            f.isAccessible = true
                            f.set(qm, rebuilt)
                            qm.setTransients(civ)
                            applied++
                            worldScreenRef?.get()?.shouldUpdate = true
                        } catch (t: Throwable) {
                            println("[FrameSync] 应用城邦任务失败: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }
            } catch (t: Throwable) {
                println("[FrameSync] 应用文明 sim 失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return applied
    }

    /** 交易请求签名：必须包含具体条件，否则反提议会被误判成旧请求。 */
    private fun tradeRequestsSignature(list: List<TradeRequest>): String =
        list.joinToString(";") { request ->
            val offers = (request.trade.ourOffers + request.trade.theirOffers)
                .joinToString(",") { "${it.name}|${it.type}|${it.amount}|${it.duration}" }
            "${request.requestingCiv}#$offers"
        }

    /** 把服务端城市快照应用到本地。逐城市隔离，单个失败不影响其他城市。返回应用数。 */
    /**
     * UncivSync: 按服务端 sim 在本地**即时**创建一座城市（用户要求建城必须实时，不能等回合）。
     *
     * 走官方入口 `Civilization.addCity`，因此城市中心、生产队列容器、人口/扩张管理器的
     * transient 都由官方初始化；随后用服务端的权威 id/名称/人口覆盖，保证后续城市 op
     * （按 cityId 定位）能命中。失败返回 null，由每回合整档兜底。
     */
    private fun createCityFromSim(gameInfo: GameInfo, o: JsonObject, id: String): City? {
        val civName = (o["civ"] as? JsonPrimitive)?.contentOrNull ?: return null
        val x = (o["x"] as? JsonPrimitive)?.intOrNull ?: return null
        val y = (o["y"] as? JsonPrimitive)?.intOrNull ?: return null
        val civ = gameInfo.civilizations.firstOrNull { it.civName == civName } ?: return null
        val tile = gameInfo.tileMap[x, y] ?: return null
        return try {
            val created = civ.addCity(tile.position)
            // 用服务端权威 id 覆盖本地生成的 id（否则后续按 cityId 的城市 op 会定位失败）
            created.id = id
            (o["name"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotEmpty() }?.let { created.name = it }
            (o["pop"] as? JsonPrimitive)?.intOrNull?.let { p ->
                if (p > 0 && created.population.population != p) created.population.setPopulation(p)
            }
            println("[FrameSync] 即时创建城市 ${created.name}($id) @ $x,$y civ=$civName")
            created
        } catch (t: Throwable) {
            println("[FrameSync] 创建城市失败: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun applyCities(gameInfo: GameInfo, cities: JsonArray): Int {
        var applied = 0
        // 记录本条 sim 实际改动的城市,循环结束后让正打开的 CityScreen 即时重建
        // (FS 下队列/专家/购买结果不再本地直写,官方城市界面不知道要重绘 ——
        // 表现就是"点建造要两下":第一下 sim 已应用但界面没刷新,第二下才看到)
        val touchedCities = HashSet<com.unciv.logic.city.City>()
        for (entry in cities) {
            try {
                val o = entry.jsonObject
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                // 本地已有 → 更新；本地没有 → 说明是服务端刚建（或 AI 刚建）的城市，即时创建。
                // 以前这里 `?: continue` 把新城市直接丢弃，表现就是"建城要等到下一个回合才生效"。
                val city: City = gameInfo.civilizations.asSequence()
                    .flatMap { it.cities.asSequence() }
                    .firstOrNull { it.id == id }
                    ?: createCityFromSim(gameInfo, o, id)
                    ?: continue

                touchedCities.add(city)
                // 第三十一批：城市 = 官方序列化整对象,搬进现有实例(引用稳定)
                (o["obj"] as? JsonPrimitive)?.contentOrNull?.let { co ->
                    if (co.length > 20) {
                        try {
                            val srcC = com.unciv.json.json().fromJson(com.unciv.logic.city.City::class.java, co)
                            // 按"obj JSON 里实际在场的字段"搬运（在场即权威值，含默认值）。
                            val cityElement = json.parseToJsonElement(co).jsonObject
                            com.unciv.logic.FsStateSplice.copyJsonFields(
                                city, srcC, cityElement, com.unciv.logic.city.City::class.java)
                            // 官方全量重链（City.setTransients 接 population.city /
                            // expansion.city / cityConstructions.city / religion 并重建缓存）。
                            // 以前只调 CityConstructions.setTransients() —— 它不接反向引用、
                            // 第一行就用 city，在"整引用替换"时代必炸（生产队列加入/移除
                            // 即崩的根因）；splice 改递归搬运后这里同样必须走官方全链。
                            city.setTransients(city.civ)
                            worldScreenRef?.get()?.shouldUpdate = true
                        } catch (t: Throwable) {
                            println("[FrameSync] 城市整对象应用失败: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }
                // 生产队列
                (o["disabledConstructions"] as? JsonArray)?.let { arr ->
                    city.disabledConstructions.clear()
                    city.disabledConstructions.addAll(arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
                }
                (o["queue"] as? JsonArray)?.let { q ->
                    val names = q.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    if (city.cityConstructions.constructionQueue.toList() != names) {
                        city.cityConstructions.constructionQueue.clear()
                        city.cityConstructions.constructionQueue.addAll(names)
                    }
                }
                // 工作地块（"x,y" 列表）
                (o["workedTiles"] as? JsonArray)?.let { wt ->
                    val coords = wt.mapNotNull { e ->
                        val s = (e as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        val parts = s.split(",")
                        if (parts.size != 2) return@mapNotNull null
                        val x = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                        val y = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                        HexCoord(x, y)
                    }.toSet()
                    if (city.workedTiles != coords) {
                        city.workedTiles.clear()
                        city.workedTiles.addAll(coords)
                    }
                }
                (o["lockedTiles"] as? JsonArray)?.let { locked ->
                    city.lockedTiles = locked.mapNotNull { value ->
                        val parts = (value as? JsonPrimitive)?.contentOrNull?.split(",")
                            ?: return@mapNotNull null
                        if (parts.size != 2) return@mapNotNull null
                        val x = parts[0].toIntOrNull() ?: return@mapNotNull null
                        val y = parts[1].toIntOrNull() ?: return@mapNotNull null
                        HexCoord(x, y)
                    }.toHashSet()
                }
                (o["unitShouldUseSavedPromotion"] as? JsonObject)?.let { preferences ->
                    city.unitShouldUseSavedPromotion.clear()
                    for ((unit, value) in preferences) {
                        val enabled = (value as? JsonPrimitive)?.booleanOrNull ?: continue
                        city.unitShouldUseSavedPromotion[unit] = enabled
                    }
                }
                // UncivSync: 人口数（第二十批）。服务端回合结算的人口增长原来从不更新到
                // 已有城市（只有新建城时应用过一次）——城市界面人口一直是旧值。
                (o["pop"] as? JsonPrimitive)?.intOrNull?.let { p ->
                    if (p > 0 && city.population.population != p) {
                        try {
                            city.population.setPopulation(p)
                            worldScreenRef?.get()?.shouldUpdate = true
                        } catch (t: Throwable) {
                            println("[FrameSync] 应用人口失败: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }
                // UncivSync: 城市血量（攻城伤害）/ 焦土 / 傀儡 —— 攻城与征服状态。
                (o["hp"] as? JsonPrimitive)?.intOrNull?.let { h ->
                    if (city.health != h) {
                        city.health = h
                        worldScreenRef?.get()?.shouldUpdate = true
                    }
                }
                (o["razing"] as? JsonPrimitive)?.contentOrNull?.let { v ->
                    val b = v == "true"
                    if (city.isBeingRazed != b) {
                        city.isBeingRazed = b
                        worldScreenRef?.get()?.shouldUpdate = true
                    }
                }
                (o["puppet"] as? JsonPrimitive)?.contentOrNull?.let { v ->
                    val b = v == "true"
                    if (city.isPuppet != b) {
                        city.isPuppet = b
                        worldScreenRef?.get()?.shouldUpdate = true
                    }
                }
                // 城市宗教（压力 / 多数宗教）—— 官方序列化器整对象
                (o["religion"] as? JsonObject)?.let { rj ->
                    try {
                        city.religion = com.unciv.json.json().fromJson(
                            com.unciv.logic.city.managers.CityReligionManager::class.java, rj.toString())
                        city.religion.setTransients(city)
                        worldScreenRef?.get()?.shouldUpdate = true
                    } catch (t: Throwable) {
                        println("[FrameSync] 城市宗教应用失败: ${t.javaClass.simpleName}")
                    }
                }
                // 城市倒计时状态（抵抗 / 好日…）整体替换
                (o["flags"] as? JsonObject)?.let { fl ->
                    val remote = fl.entries.mapNotNull { (k, v) ->
                        val n = (v as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                        k to n
                    }.toMap()
                    if (city.flagsCountdown.toMap() != remote) {
                        city.flagsCountdown.clear()
                        for ((k, v) in remote) city.flagsCountdown[k] = v
                        worldScreenRef?.get()?.shouldUpdate = true
                    }
                }
                // 城市换主（征服）：把城市迁到新文明的列表里，否则还挂在旧文明名下
                (o["civ"] as? JsonPrimitive)?.contentOrNull?.let { newCivName ->
                    if (newCivName.isNotEmpty() && city.civ.civName != newCivName) {
                        val newOwner = gameInfo.civilizations.firstOrNull { it.civName == newCivName }
                        if (newOwner != null) {
                            val oldOwner = city.civ
                            oldOwner.cities = oldOwner.cities.filter { it != city }
                            newOwner.cities = newOwner.cities + city
                            city.civ = newOwner
                            worldScreenRef?.get()?.shouldUpdate = true
                        }
                    }
                }
                // 专家分配
                (o["specialists"] as? JsonObject)?.let { sp ->
                    city.population.specialistAllocations.clear()
                    for ((name, countJson) in sp) {
                        val n = (countJson as? JsonPrimitive)?.intOrNull ?: continue
                        if (n != 0) city.population.specialistAllocations.add(name, n)
                    }
                }
                // 焦点
                (o["focus"] as? JsonPrimitive)?.contentOrNull?.let { f ->
                    CityFocus.entries.firstOrNull { it.name == f }?.let { city.setCityFocus(it) }
                }
                (o["avoidGrowth"] as? JsonPrimitive)?.contentOrNull?.let {
                    val v = it == "true"
                    if (city.avoidGrowth != v) city.avoidGrowth = v
                }
                (o["manualSpecialists"] as? JsonPrimitive)?.contentOrNull?.let {
                    val v = it == "true"
                    if (city.manualSpecialists != v) city.manualSpecialists = v
                }
                // UncivSync: 本回合是否已轰击过 —— City.canBombard() 靠它判断，
                // 不同步的话"轰击"按钮永远可点（视觉上就是"能无限轰击"）。
                (o["attackedThisTurn"] as? JsonPrimitive)?.contentOrNull?.let {
                    val v = it == "true"
                    if (city.attackedThisTurn != v) {
                        city.attackedThisTurn = v
                        worldScreenRef?.get()?.shouldUpdate = true
                    }
                }
                // UncivSync: 已建成建筑/奇观（gap #1 核心）。没有它，客户端本地 builtBuildings 永远空，
                // 造完的建筑/奇观不"装上去"，玩家看到城市还在造 → 重复建造、卡住无法进入下一步。
                (o["buildings"] as? JsonArray)?.let { arr ->
                    val remote = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .filter { it.isNotEmpty() }.toSet()
                    val cc = city.cityConstructions
                    if (cc.builtBuildings != remote) {
                        try {
                            cc.builtBuildings.clear()
                            cc.builtBuildings.addAll(remote)
                            cc.setTransients()  // 重建 builtBuildingObjects + builtBuildingUniqueMap
                            worldScreenRef?.get()?.shouldUpdate = true
                            applied++
                        } catch (t: Throwable) {
                            println("[FrameSync] 应用建筑失败: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }
                // UncivSync: 当前建造项的累计锤数（进度条百分比的派生输入）
                (o["constructionProgress"] as? JsonObject)?.let { prog ->
                    val remote = HashMap<String, Int>()
                    for ((name, v) in prog) {
                        (v as? JsonPrimitive)?.intOrNull?.let { remote[name] = it }
                    }
                    val cc = city.cityConstructions
                    if (cc.inProgressConstructions != remote) {
                        try {
                            cc.inProgressConstructions.clear()
                            cc.inProgressConstructions.putAll(remote)
                            worldScreenRef?.get()?.shouldUpdate = true
                            applied++
                        } catch (t: Throwable) {
                            println("[FrameSync] 应用建造进度失败: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }
                applied++
            } catch (t: Throwable) {
                println("[FrameSync] 应用城市 sim 失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        // 触发正打开的该城 CityScreen 重建(点一下就能看到结果的关键)
        if (touchedCities.isNotEmpty()) {
            try {
                val screen = com.unciv.UncivGame.Current.screen
                if (screen is com.unciv.ui.screens.cityscreen.CityScreen
                    && touchedCities.contains(screen.cityView.city)) {
                    screen.updateAsync()
                }
            } catch (_: Throwable) {}
        }
        return applied
    }

    /**
     * 把服务端的地块快照应用到本地（改良 / 道路 / 掠夺 / 工人施工进度）。
     *
     * 为什么要这条通道：这几类状态以前完全没有同步途径，只能靠"每回合整档下发"纠正，
     * 而整档 = `UncivGame.loadGame()` = 客户端重建整个 WorldScreen（体感卡顿的主因）。
     * 纳入 sim 投影后，常规回合服务端只推增量，整档退化成稀有事件。
     */
    private fun applyTiles(gameInfo: GameInfo, tiles: JsonArray): Int {
        var applied = 0
        for (entry in tiles) {
            try {
                val o = entry.jsonObject
                val x = (o["x"] as? JsonPrimitive)?.intOrNull ?: continue
                val y = (o["y"] as? JsonPrimitive)?.intOrNull ?: continue
                val tile = gameInfo.tileMap[x, y] ?: continue
                var changed = false

                // 基础地形（核弹改地形等）—— 官方快照直赋 + 重建瞬态缓存
                (o["b"] as? JsonPrimitive)?.contentOrNull?.let { bt ->
                    if (bt.isNotEmpty() && tile.baseTerrain != bt) {
                        try {
                            tile.baseTerrain = bt
                            tile.setTransients()
                            changed = true
                        } catch (t: Throwable) {
                            println("[FrameSync] 地形应用失败: ${t.javaClass.simpleName}")
                        }
                    }
                }
                // 领土归属（买地 / 文化扩张 / 征服）：所属城市 id，空串=无主
                (o["owningCity"] as? JsonPrimitive)?.contentOrNull?.let { ocId ->
                    val newOwnerCity = if (ocId.isEmpty()) null
                        else gameInfo.civilizations.asSequence().flatMap { it.cities.asSequence() }
                            .firstOrNull { it.id == ocId }
                    if (tile.owningCity != newOwnerCity) {
                        try {
                            tile.setOwningCity(newOwnerCity)
                            changed = true
                        } catch (t: Throwable) {
                            println("[FrameSync] 领土归属应用失败: ${t.javaClass.simpleName}")
                        }
                    }
                }
                // 地形特征（遗迹被踩后消失 / 森林被砍…）：服务端发全量列表，空列表=清除
                (o["features"] as? JsonArray)?.let { fl ->
                    val remote = fl.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    if (tile.terrainFeatures != remote) {
                        tile.setTerrainFeatures(remote)
                        changed = true
                    }
                }
                // 改良名（空串 = 无改良）
                (o["improvement"] as? JsonPrimitive)?.contentOrNull?.let { raw ->
                    val v = raw.ifEmpty { null }
                    if (tile.improvement != v) {
                        tile.improvement = v
                        // Tile.tileImprovement 是 @Transient 缓存，setImprovement 不会清它，
                        // 不清就会出现"改良被拆了但地块产出还是老的"。
                        tile.tileImprovement = null
                        changed = true
                    }
                }
                (o["improvementPillaged"] as? JsonPrimitive)?.contentOrNull?.let {
                    val v = it == "true"
                    if (tile.improvementIsPillaged != v) { tile.improvementIsPillaged = v; changed = true }
                }
                (o["road"] as? JsonPrimitive)?.contentOrNull?.let { name ->
                    val v = RoadStatus.entries.firstOrNull { it.name == name }
                    if (v != null && tile.roadStatus != v) { tile.roadStatus = v; changed = true }
                }
                (o["roadPillaged"] as? JsonPrimitive)?.contentOrNull?.let {
                    val v = it == "true"
                    if (tile.roadIsPillaged != v) { tile.roadIsPillaged = v; changed = true }
                }
                // 在建队列（工人施工进度）：字段是 "改良名:剩余回合" 的列表
                val queueJson = o["improvementQueue"] as? JsonArray
                if (queueJson != null) {
                    val want = queueJson.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    val cur = tile.improvementQueue.map { "${it.improvement}:${it.turnsToImprovement}" }
                    if (cur != want) {
                        tile.improvementQueue.clear()
                        for (e in want) {
                            val idx = e.lastIndexOf(':')
                            if (idx <= 0) continue
                            val name = e.substring(0, idx)
                            val turns = e.substring(idx + 1).toIntOrNull() ?: 0
                            tile.improvementQueue.add(Tile.ImprovementQueueEntry(name, turns))
                        }
                        changed = true
                    }
                }
                // 字段缺失时**不能**清本地队列。
                // 工人建造改良目前还没有上报通道（ImprovementPickerScreen 是纯本地调用，
                // server 端根本没有这个队列），一清就会把玩家刚下的施工指令抹掉。
                // 等服务端接上 unit.buildImprovement / unit.stopImprovement 之后，
                // 服务端会总是带这个字段（空数组 = 已清空），那时才由服务端说了算。

                if (changed) applied++
            } catch (t: Throwable) {
                println("[FrameSync] 应用地块 sim 失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return applied
    }

    /**
     * 蛮族营地（对齐 GC 的 encampments 通道）：服务端只发"营地坐标列表"，
     * 客户端把这些地块的改良设为蛮族营地。营地消失由 applyTiles（improvement=""）负责清掉，
     * 这里只管"加上"，避免和 applyTiles 抢着清。
     */
    private fun applyEncampments(gameInfo: GameInfo, arr: JsonArray): Int {
        var applied = 0
        for (e in arr) {
            try {
                val o = e as? JsonObject ?: continue
                val x = (o["x"] as? JsonPrimitive)?.intOrNull ?: continue
                val y = (o["y"] as? JsonPrimitive)?.intOrNull ?: continue
                val tile = gameInfo.tileMap[x, y] ?: continue
                if (tile.improvement != Constants.barbarianEncampment) {
                    tile.improvement = Constants.barbarianEncampment
                    tile.tileImprovement = null
                    applied++
                }
            } catch (t: Throwable) {
                println("[FrameSync] 应用蛮族营地失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return applied
    }

    /**
     * 地形突变（对齐 GC 的 terrainChanges 通道）：核弹改地形等就地改，不再靠整档。
     * 每条带 x/y/baseTerrain/features/...，逐字段设置，全程守护——
     * 一旦某字段设置抛错，worldSignature 的整档兜底（地形签名一变就整档）会纠正。
     */
    /**
     * 地形突变（对齐 GC 的 terrainChanges 通道）：核弹改地形等就地改，不再靠整档。
     * 每条带 x/y/baseTerrain/features/naturalWonder/resource/resourceAmount，逐字段设置，全程守护。
     * 改完调用 [com.unciv.logic.map.tile.Tile.setTerrainTransients] 刷新 baseTerrainObject /
     * isWater / isLand / resourceAmount 等 transient 缓存——否则 getBaseTerrain() 仍返回旧地形，
     * 渲染与寻路都会出错。worldSignature 的整档兜底（地形签名一变就整档）会纠正极端异常。
     */
    private fun applyTerrainChanges(gameInfo: GameInfo, arr: JsonArray): Int {
        var applied = 0
        for (e in arr) {
            try {
                val o = e as? JsonObject ?: continue
                val x = (o["x"] as? JsonPrimitive)?.intOrNull ?: continue
                val y = (o["y"] as? JsonPrimitive)?.intOrNull ?: continue
                val tile = gameInfo.tileMap[x, y] ?: continue
                var changed = false
                (o["baseTerrain"] as? JsonPrimitive)?.contentOrNull?.let { bt ->
                    if (bt.isNotEmpty() && tile.baseTerrain != bt) { tile.baseTerrain = bt; changed = true }
                }
                (o["features"] as? JsonArray)?.let { feats ->
                    val want = feats.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                    val cur = tile.terrainFeatures.toSet()
                    if (cur != want) { tile.setTerrainFeatures(want.toList()); changed = true }
                }
                (o["naturalWonder"] as? JsonPrimitive)?.contentOrNull?.let { nw ->
                    if (nw.isNotEmpty() && tile.naturalWonder != nw) { tile.naturalWonder = nw; changed = true }
                }
                (o["resource"] as? JsonPrimitive)?.contentOrNull?.let { res ->
                    if (res.isEmpty()) {
                        if (tile.resource != null) { tile.setTileResource(null); changed = true }
                    } else if (tile.resource != res) {
                        tile.setTileResource(res)  // 同时刷新 transient 的 tileResource 缓存
                        (o["resourceAmount"] as? JsonPrimitive)?.intOrNull?.let { tile.resourceAmount = it }
                        changed = true
                    }
                }
                if (changed) {
                    try { tile.setTerrainTransients() } catch (_: Throwable) {}
                    applied++
                }
            } catch (t: Throwable) {
                println("[FrameSync] 应用地形突变失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return applied
    }

    /**
     * 在本地 gameInfo 里定位服务端 sim 描述的那个单位。
     *
     * 当前协议必须以服务端 id 为身份；只有旧协议完全不带 id 时才允许坐标兼容：
     *
     *   1. 有权威 unitId：只按 id 定位；不存在就由 createUnitFromState 创建
     *   2. 无 id 的旧协议：按新/旧坐标定位，同类型全图唯一时才作最后兼容
     */
    private fun locateUnit(
        gameInfo: GameInfo, x: Int, y: Int, civName: String?, name: String?,
        serverId: Int?, prevX: Int?, prevY: Int?, matched: Set<MapUnit>,
    ): MapUnit? {
        // An explicit server id is the identity, not a hint. Never replace a missing id with
        // another equal-type unit — that is how duplicate catapults became one object.
        if (serverId != null) return findUnitById(gameInfo, serverId, matched)
        findUnitByLoc(gameInfo, x, y, civName, name, matched)?.let { return it }
        if (prevX != null && prevY != null) {
            findUnitByLoc(gameInfo, prevX, prevY, civName, name, matched)?.let { return it }
        }
        if (civName != null && name != null) {
            val candidates = unitsOfType(gameInfo, civName, name).filter { it !in matched }
            if (candidates.size == 1) return candidates.first()
        }
        return null
    }

    /**
     * UncivSync: 城市刚造出来 / 刚花钱买 / 刚被赠予的单位，第一次出现在 sim 的 units 数组里时，
     * 服务端权威存档已经有它了，但本地 GameInfo 还没有这个对象。以前 applySim 只"按坐标/id 定位
     * 已存在的单位再改字段"，定位不到就 missed++ 跳过 —— 于是新单位要等整档 saveFull 才出现
     * （表现就是"城市造完单位过了几个回合才出来"）。这里就地从 sim 字段把它造出来，贴合增量同步，
     * 避免触发整档重建 WorldScreen。
     *
     * 仅当 sim 带了明确的 serverId 且该 id 在本地还不存在时才造，避免对已有单位的偶发 miss 造出重复单位。
     * 造好之后交回 applySim 的共享字段同步块继续补 hp / 移动力 / action / automated / 自定义名 / 攻击计数。
     */
    private fun createUnitFromState(
        gameInfo: GameInfo, src: JsonObject, x: Int, y: Int,
        civName: String?, name: String?, serverId: Int?, worldScreen: WorldScreen,
    ): MapUnit? {
        if (civName == null || name == null || serverId == null) return null
        // 防御：id 已存在说明是偶发 miss（不是真新单位），不要重复造
        if (findUnitById(gameInfo, serverId) != null) return null
        val civ = gameInfo.civilizations.firstOrNull { it.civName == civName } ?: return null
        val baseUnit = gameInfo.ruleset.units[name] ?: return null
        val tile = gameInfo.tileMap[x, y] ?: return null
        val unit = civ.units.placeUnitNearTile(tile.position, baseUnit, serverId) ?: return null
        // placeUnitNearTile 在目标格被占时会落附近；共享同步块随后会把单位按服务端权威位置贴近。
        worldScreen.shouldUpdate = true
        return unit
    }

    /** 该文明名下所有指定类型的单位。 */
    private fun unitsOfType(gameInfo: GameInfo, civName: String, unitType: String): List<MapUnit> {
        val civ = gameInfo.civilizations.firstOrNull { it.civName == civName } ?: return emptyList()
        return civ.units.getCivUnits().filter { it.baseUnit.name == unitType }.toList()
    }

    /** Match a local unit by its stable identity: tile + owning civ name + base unit name. */
    private fun findUnitByLoc(
        gameInfo: GameInfo, x: Int, y: Int, civName: String?, name: String?,
        excluded: Set<MapUnit> = emptySet(),
    ): MapUnit? {
        val tile = gameInfo.tileMap[x, y] ?: return null
        for (unit in tile.getUnits()) {
            if (unit in excluded) continue
            if (civName != null && unit.civ.civName != civName) continue
            if (name != null && unit.baseUnit.name != name) continue
            return unit
        }
        return null
    }

    private fun findUnitById(gameInfo: GameInfo, id: Int?, excluded: Set<MapUnit> = emptySet()): MapUnit? {
        if (id == null) return null
        for (civ in gameInfo.civilizations) {
            for (unit in civ.units.getCivUnits()) {
                if (unit in excluded) continue
                if (unit.id == id) return unit
            }
        }
        return null
    }

    /** Apply an authoritative position even when local movement rules are stricter/stale. */
    private fun forcePlaceUnitForSync(unit: MapUnit, destination: Tile) {
        try {
            if (unit.currentTile == destination) return
            unit.removeFromTile()
            unit.currentTile = destination
            when {
                unit.baseUnit.isAirUnit() -> destination.airUnits.add(unit)
                unit.isCivilian() -> destination.civilianUnit = unit
                else -> destination.militaryUnit = unit
            }
            unit.civ.cache.updateViewableTiles()
        } catch (t: Throwable) {
            println("[FrameSync] 强制应用单位位置失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
