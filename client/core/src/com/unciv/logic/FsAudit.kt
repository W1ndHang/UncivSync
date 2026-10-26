package com.unciv.logic

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/** 只审计正在渲染的客户端对象；服务端和离线模拟不绑定。授权按线程和对象隔离。 */
object FsAudit {
    @Volatile private var clientGame = WeakReference<GameInfo>(null)
    private val applying = ThreadLocal.withInitial { mutableListOf<GameInfo>() }
    val violations = AtomicLong()

    fun bind(game: GameInfo) {
        clientGame = WeakReference(game)
    }

    fun unbind(game: GameInfo) {
        if (clientGame.get() === game) clientGame.clear()
    }

    fun <T> applyingServerState(game: GameInfo, body: () -> T): T {
        val stack = applying.get()
        stack.add(game)
        try { return body() } finally {
            stack.removeAt(stack.lastIndex)
            if (stack.isEmpty()) applying.remove()
        }
    }

    fun write(game: GameInfo?, entry: String) {
        if (game == null || clientGame.get() !== game || applying.get().any { it === game }) return
        violations.incrementAndGet()
        val error = IllegalStateException("[FsAudit] LOCAL_WRITE entry=$entry game=${game.gameId} turn=${game.turns}")
        // 保留完整调用栈，避免同一入口下不同调用者被去重后掩盖。
        System.err.println(error.stackTraceToString())
    }

    fun intent(game: GameInfo, op: String) {
        if (clientGame.get() === game) println("[FsAudit] OP_INTENT $op")
    }
}
