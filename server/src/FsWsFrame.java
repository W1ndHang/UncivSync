// FsWsFrame.java - Minimal RFC 6455 WebSocket frame codec
// Server-side only: encodes unmasked frames, decodes masked client frames
package unciv.fs.local;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class FsWsFrame {
    private FsWsFrame() {}

    public static final int OP_CONTINUATION = 0x0;
    public static final int OP_TEXT = 0x1;
    public static final int OP_BINARY = 0x2;
    public static final int OP_CLOSE = 0x8;
    public static final int OP_PING = 0x9;
    public static final int OP_PONG = 0xA;

    /** Read one WebSocket frame from the masked client stream. Returns null on orderly close. */
    public static Frame read(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) throw new EOFException("EOF before frame header");
        int b1 = in.read();
        if (b1 < 0) throw new EOFException("EOF before length byte");

        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;

        if (len == 126) {
            len = (in.read() << 8) | in.read();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
        }
        if (len < 0 || len > 16 * 1024 * 1024) throw new IOException("Bad frame length: " + len);

        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            int read = 0;
            while (read < 4) {
                int n = in.read(mask, read, 4 - read);
                if (n < 0) throw new EOFException("EOF in mask");
                read += n;
            }
        }

        byte[] payload = new byte[(int) len];
        int off = 0;
        while (off < len) {
            int n = in.read(payload, off, (int) (len - off));
            if (n < 0) throw new EOFException("EOF in payload");
            off += n;
        }
        if (mask != null) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        }
        return new Frame(fin, opcode, payload);
    }

    /** Write a server-to-client frame (unmasked). */
    public static void write(OutputStream out, int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(payload.length + 16);
        b.write(0x80 | (opcode & 0x0F));
        // 注意：长度必须用 long 位移！int 的移位量在 Java 里是按 32 取模的，
        // 用 int 写 64 位长度会让高 4 字节重复低 4 字节的内容 —— 任何 >64KB 的帧
        // （整档下发、大地图的全量 sim）长度字段都是错的，客户端会读到天文数字并断开。
        long n = payload.length;
        if (n < 126) {
            b.write((int) n);
        } else if (n <= 0xFFFF) {
            b.write(126); b.write((int) ((n >> 8) & 0xFF)); b.write((int) (n & 0xFF));
        } else {
            b.write(127);
            for (int i = 7; i >= 0; i--) b.write((int) ((n >>> (i * 8)) & 0xFF));
        }
        b.write(payload);
        out.write(b.toByteArray());
        out.flush();
    }

    public static void writeText(OutputStream out, String text) throws IOException {
        write(out, OP_TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    public static String closePayload(int code, String reason) {
        return "" + (char) ((code >> 8) & 0xFF) + (char) (code & 0xFF) + reason;
    }

    public static final class Frame {
        public final boolean fin;
        public final int opcode;
        public final byte[] payload;
        public Frame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin; this.opcode = opcode; this.payload = payload;
        }
        public String text() { return new String(payload, StandardCharsets.UTF_8); }
    }
}