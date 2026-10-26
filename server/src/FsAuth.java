// FsAuth.java - userId -> password binding table for our self-hosted server.
//
// This is NOT an account system: there is no sign-up, no profile, no session, no token.
// A player's identity is the userId UUID their own client generates once (Settings ->
// multiplayer.userId, copyable/re-importable from the Advanced options tab). The only thing
// this class stores is an optional password bound to that userId, so that somebody else who
// copies/guesses the userId cannot impersonate it ("窜台"). No password -> nothing to check.
//
// It mirrors exactly how the original project (yairm210/Unciv, server/src/com/unciv/app/server
// /UncivServer.kt) does it, minus the ktor plumbing:
//
//   authMap: MutableMap<Uuid, String>          // userId -> password, loaded from "server.auth"
//   validateAuth(authInfo) = authMap[userId] == null || authMap[userId] == password
//   GET  /auth   200 verified(with password) | 204 no binding yet | 401 wrong password
//   PUT  /auth   (existing == null || existing == current) && new.length >= 6  -> 200, else 401/400
//
// The client drives this automatically:
//   * Options -> Multiplayer -> "Check connection"  -> checkServerStatus() then authenticate()
//   * "Set password" button                          -> setPassword() -> PUT /auth
//   * LobbyRoomScreen.ensureSaveServerRegistered()   -> PUT /auth with a freshly generated
//     random 16-char password the first time a player enters a lobby game, then it is
//     remembered per server URL in Settings.multiplayer.passwords[serverUrl].
// The client also always sends `Authorization: Basic base64(userId:passwordOrEmpty)` on every
// /files/* request (GameSettingsMultiplayer.getAuthHeader), so an absent header only ever
// means a browser/curl, never the game.
package unciv.fs.local;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FsAuth {

    public enum Status { VERIFIED, UNREGISTERED, UNAUTHORIZED }

    /** Smallest password the original server accepts (PUT /auth). */
    public static final int MIN_PASSWORD_LENGTH = 6;

    /** Where the bindings live (hidden file next to the saves, never served by /files/). */
    public static final String FILE_NAME = ".auth";

    /** Name we used before 1.0.0; still read once so upgrading loses nothing. */
    public static final String LEGACY_FILE_NAME = ".players.json";

    private final File store;
    private final Map<String, String> passwords = new LinkedHashMap<>();
    private final Object lock = new Object();

    public FsAuth(File store) {
        this.store = store;
        load();
    }

    /** Build the default binding-table location for a saves directory. */
    public static File defaultStore(File savesDir) {
        File base = (savesDir != null) ? savesDir : new File(".");
        return new File(base, FILE_NAME);
    }

    /**
     * Decode "Basic base64(userId:password)".
     * Returns {userId, password}, or null when the header is missing/undecodable.
     */
    public static String[] parseBasic(String header) {
        if (header == null) return null;
        String h = header.trim();
        if (h.length() < 6 || !h.regionMatches(true, 0, "Basic", 0, 5)) return null;
        String b64 = h.substring(5).trim();
        if (b64.isEmpty()) return null;
        String raw;
        try {
            raw = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (Exception e) {
            try {
                raw = new String(Base64.getMimeDecoder().decode(b64), StandardCharsets.UTF_8);
            } catch (Exception e2) {
                return null;
            }
        }
        int idx = raw.indexOf(':');
        if (idx < 0) return new String[]{ raw, "" };
        return new String[]{ raw.substring(0, idx), raw.substring(idx + 1) };
    }

    /**
     * GET /auth — same three-way answer as the original server.
     *   VERIFIED      this userId has a password and it matches
     *   UNREGISTERED  no password bound yet (the client shows "set password to secure your userId")
     *   UNAUTHORIZED  a password is bound and the one supplied is wrong
     */
    public Status check(String userId, String password) {
        if (userId == null || userId.isEmpty()) return Status.UNAUTHORIZED;
        synchronized (lock) {
            String stored = passwords.get(userId);
            if (stored == null || stored.isEmpty()) return Status.UNREGISTERED;
            if (stored.equals(password == null ? "" : password)) return Status.VERIFIED;
            return Status.UNAUTHORIZED;
        }
    }

    /** true when this userId already has a password here. */
    public boolean isBound(String userId) {
        if (userId == null || userId.isEmpty()) return false;
        synchronized (lock) {
            String stored = passwords.get(userId);
            return stored != null && !stored.isEmpty();
        }
    }

    /**
     * true when the supplied password may act for this userId.
     * This is the original's `validateAuth`: no binding -> allowed, binding -> must match.
     */
    public boolean allows(String userId, String password) {
        return check(userId, password) != Status.UNAUTHORIZED;
    }

    /**
     * PUT /auth — bind a password to a userId for the first time, or change an existing one.
     * Returns false when an existing password is present and the supplied one does not match.
     */
    public boolean setPassword(String userId, String currentPassword, String newPassword) {
        if (userId == null || userId.isEmpty()) return false;
        if (newPassword == null || newPassword.isEmpty()) return false;
        synchronized (lock) {
            String stored = passwords.get(userId);
            if (stored != null && !stored.isEmpty()
                    && !stored.equals(currentPassword == null ? "" : currentPassword)) {
                return false;
            }
            passwords.put(userId, newPassword);
            save();
            return true;
        }
    }

    /** How many userIds currently have a password bound (metrics/debug only). */
    public int boundCount() {
        int n = 0;
        synchronized (lock) {
            for (String v : passwords.values()) if (v != null && !v.isEmpty()) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ persistence
    // Format follows the original server.auth: one "userId:password" per line. We keep it as
    // one line per player so admins can read/edit it with any text editor.

    private void load() {
        if (store == null) return;
        if (!store.isFile()) {
            File legacy = new File(store.getParentFile(), LEGACY_FILE_NAME);
            if (legacy.isFile()) {
                System.out.println("[auth] migrating legacy " + LEGACY_FILE_NAME + " -> " + FILE_NAME);
                if (readStore(legacy, false)) save();
            }
            return;
        }
        readStore(store, true);
    }

    private boolean readStore(File f, boolean announce) {
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            int n = 0;
            if (text.startsWith("{")) {
                // Very old versions stored a JSON map; keep accepting it.
                Object parsed = FsJson.parse(text);
                if (parsed instanceof Map) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) parsed).entrySet()) {
                        Object k = e.getKey(), v = e.getValue();
                        if (k != null && v != null) { passwords.put(String.valueOf(k), String.valueOf(v)); n++; }
                    }
                }
            } else {
                for (String line : text.split("\n")) {
                    String s = line.trim();
                    if (s.isEmpty()) continue;
                    // userId never contains ':' (it is a UUID), so split on the FIRST colon and
                    // keep everything after it as the password.
                    int idx = s.indexOf(':');
                    if (idx <= 0) continue;
                    passwords.put(s.substring(0, idx), s.substring(idx + 1));
                    n++;
                }
            }
            if (announce) {
                System.out.println("[auth] loaded " + n + " password binding(s) from " + f.getAbsolutePath());
            }
            return n > 0;
        } catch (Exception e) {
            System.err.println("[auth] failed to load " + f + ": " + e.getMessage());
            return false;
        }
    }

    private void save() {
        if (store == null) return;
        try {
            File dir = store.getParentFile();
            if (dir != null) dir.mkdirs();
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : passwords.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                sb.append(e.getKey()).append(':').append(e.getValue()).append('\n');
            }
            Files.write(store.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[auth] failed to save " + store + ": " + e.getMessage());
        }
    }
}
