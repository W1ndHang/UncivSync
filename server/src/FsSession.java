// FsSession.java - Per-client WebSocket session
//
// Rewritten 2026-09-10: added lastSeen timestamp for heartbeat, recv rate limit
// counter, replaced flag for graceful reconnection handoff, idempotent close.
package unciv.fs.local;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.locks.ReentrantLock;

public final class FsSession implements Runnable {
    public final Socket socket;
    public final FsServer server;
    public FsRoom room = null;

    // Identity (from URL params)
    public String gameId = "";
    public String playerId = "";
    public String nickname = "";
    public String playerUUID = "";
    public boolean spectator = false;

    // IO
    public final OutputStream out;
    public final InputStream in;
    /** Package-private so FsServer.heartbeatTick can grab it directly to send WS
     *  ping frames without the rate limit / replacement semantics. */
    final ReentrantLock outLock = new ReentrantLock();
    private volatile boolean closed = false;
    /** Set by FsRoom.addSession when a reconnect supersedes this session. */
    private volatile boolean replaced = false;
    private Thread thread = null;

    // Heartbeat / rate-limit bookkeeping
    public volatile long lastSeenMs = System.currentTimeMillis();
    public volatile long lastPingSentMs = 0;
    public volatile long msgsThisSecond = 0;
    public volatile long lastSecondResetMs = System.currentTimeMillis();

    /** Default rate limit: 200 ops/sec/session. */
    public static final int MAX_MSG_PER_SEC = 200;

    public FsSession(Socket socket, FsServer server) throws IOException {
        this.socket = socket;
        this.server = server;
        this.out = socket.getOutputStream();
        this.in = new BufferedInputStream(socket.getInputStream());
    }

    public void start() {
        thread = new Thread(this, "fs-session-" + socket.getRemoteSocketAddress());
        thread.setDaemon(true);
        thread.start();
    }

    /** Mark this session as replaced (by a reconnect with matching playerId+UUID).
     *  Sends a "replaced" notice then closes cleanly. */
    public void markReplaced() {
        if (closed) return;
        replaced = true;
        sendSafe(FsProtocol.replaced(nickname));
        close();
    }

    /** Returns true if the message should be rejected for rate-limiting. */
    public boolean rateLimitCheck() {
        long now = System.currentTimeMillis();
        if (now - lastSecondResetMs >= 1000) {
            lastSecondResetMs = now;
            msgsThisSecond = 0;
        }
        msgsThisSecond++;
        return msgsThisSecond > MAX_MSG_PER_SEC;
    }

    /** Thread-safe send. Drops on IO failure and closes. */
    /**
     * 心跳（OP_PING）。**必须和普通消息共用同一把写锁** —— 直接写 out 会与 sendSafe
     * 并发，撕裂帧边界（大帧尤其容易撞上，客户端会读到 "Bad frame length" 并断开重连）。
     */
    public void sendPing() {
        if (closed) return;
        outLock.lock();
        try {
            if (closed) return;
            FsWsFrame.write(out, FsWsFrame.OP_PING, new byte[0]);
        } catch (IOException e) {
            close();
        } finally {
            outLock.unlock();
        }
    }

    /** 发送二进制帧（整档压缩后走这里）。与文本消息共用写锁，避免帧交错。 */
    public void sendBinary(byte[] payload) {
        if (closed) return;
        outLock.lock();
        try {
            if (closed) return;
            FsWsFrame.write(out, FsWsFrame.OP_BINARY, payload);
        } catch (IOException e) {
            close();
        } finally {
            outLock.unlock();
        }
    }

    public void sendSafe(String text) {
        if (closed) return;
        outLock.lock();
        try {
            if (closed) return;
            FsWsFrame.writeText(out, text);
        } catch (IOException e) {
            close();
        } finally {
            outLock.unlock();
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        try { socket.close(); } catch (IOException ignored) {}
        server.onSessionClosed(this);
    }

    public boolean isClosed() { return closed; }
    public boolean wasReplaced() { return replaced; }

    @Override
    public void run() {
        try {
            while (!closed) {
                FsWsFrame.Frame f = FsWsFrame.read(in);
                if (f == null) break;
                lastSeenMs = System.currentTimeMillis();
                if (f.opcode == FsWsFrame.OP_CLOSE) break;
                if (f.opcode == FsWsFrame.OP_PING) {
                    outLock.lock();
                    try { FsWsFrame.write(out, FsWsFrame.OP_PONG, f.payload); }
                    catch (IOException e) { break; }
                    finally { outLock.unlock(); }
                    continue;
                }
                if (f.opcode == FsWsFrame.OP_PONG) continue;
                if (f.opcode != FsWsFrame.OP_TEXT) continue;
                if (rateLimitCheck()) {
                    sendSafe(FsProtocol.error("Rate limit exceeded: >" + MAX_MSG_PER_SEC + " msg/s"));
                    close();
                    break;
                }
                try {
                    server.handleMessage(this, f.text());
                } catch (Throwable e) {
                    // Throwable 而非 Exception：op 处理链触发的 Error（NoClassDefFoundError、
                    // 深递归 SOE 兜底等）不能穿透到这里杀掉整个会话读循环 —— 一个坏
                    // 消息最多损失自己，会话还得活着继续收后续消息。
                    System.err.println("[session] message handling error from " + nickname + ": " + e);
                    sendSafe(FsProtocol.error("Bad message: " + e));
                }
            }
        } catch (Throwable e) {
            // 读循环退出（连接断开或意外错误）—— close() 在 finally 里统一清理。
            if (!(e instanceof java.io.IOException)) {
                System.err.println("[session] read loop crashed for " + nickname + ": " + e);
            }
        } finally {
            close();
        }
    }
}
