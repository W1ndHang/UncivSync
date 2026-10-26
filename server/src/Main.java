// Main.java - Entry point. Boots Unciv core (libGDX stubs + Ruleset + vanilla) before starting the server.
package unciv.fs.local;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

public final class Main {

    /** Save files (and the .auth password table / .civs.json sidecars) end with one of these. */
    private static final String[] SAVE_SUFFIXES = { ".json", ".auth" };

    public static void main(String[] args) throws Exception {
        int port = 30127;
        int adminPort = 30217;   // 管理页独立端口默认开启；--admin-port 0 = 与游戏端口共用
        File savesDir = null;
        String adminPass = null;
        File gameJar = null;
        File resourcesDir = null;
        boolean logOps = true;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--port".equals(a) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--saves".equals(a) && i + 1 < args.length) {
                savesDir = new File(args[++i]);
            } else if ("--gamejar".equals(a) && i + 1 < args.length) {
                gameJar = new File(args[++i]);
            } else if ("--resources".equals(a) && i + 1 < args.length) {
                resourcesDir = new File(args[++i]);
            } else if ("--log-ops".equals(a)) {
                logOps = true;
            } else if ("--no-log-ops".equals(a)) {
                logOps = false;
            } else if ("--admin-pass".equals(a) && i + 1 < args.length) {
                adminPass = args[++i];
            } else if ("--admin-port".equals(a) && i + 1 < args.length) {
                adminPort = Integer.parseInt(args[++i]);
            } else if ("--no-full-save".equals(a)) {
                System.out.println("[config] --no-full-save is obsolete and ignored: complete GameInfo transport is required");
            } else if ("-h".equals(a) || "--help".equals(a)) {
                System.out.println("UncivSync Server - Unciv self-hosted simultaneous-turn server");
                System.out.println();
                System.out.println("Usage: java -jar UncivSync-Server.jar [--port N] [--admin-port N] [--saves DIR] [--gamejar FILE] [--resources DIR] [--no-log-ops] [--admin-pass PW]");
                System.out.println();
                System.out.println("  --port N       WebSocket port (default 30127)");
                System.out.println("  --admin-port N HTTP port for the admin page (default 30217; 0 = share game port)");
                System.out.println("  --saves DIR    Directory containing <gameId>.json save files");
                System.out.println("                 (default: Saves/ next to this jar)");
                System.out.println("  --gamejar FILE Optional ruleset resource JAR override (default: this standalone JAR)");
                System.out.println("  --resources DIR Directory with jsons/ extracted (auto-extracts from --gamejar if omitted)");
                System.out.println();
                System.out.println("Clients connect to ws://<host>:" + port + "/ws?gameId=<id>&playerId=<id>&nickname=<name>&playerUUID=<uuid>");
                System.out.println();
                System.out.println("Admin endpoints (HTTP):");
                System.out.println("  GET /metrics       - JSON status");
                System.out.println("  GET /load/<gameId> - force-reload game state from the saves dir");
                System.exit(0);
            }
        }

        // Prefer the bundled core and resources; retain legacy deployment fallbacks.
        if (gameJar == null) gameJar = autoDetectGameJar();
        // No --saves: use Saves/ next to this jar, so the whole thing stays portable.
        if (savesDir == null) savesDir = defaultSavesDir();

        // Boot Unciv core if --gamejar provided. This unlocks real GameInfo loading + turn advancement.
        boolean rulesetOk = false;
        if (gameJar != null) {
            File res = resourcesDir;
            if (res == null) {
                // Keep the extracted jsons OUT of Saves/ so the saves folder stays human-readable.
                File base = jarDirectory();
                res = (base != null) ? new File(base, "resources")
                                     : new File(System.getProperty("user.home"), ".uncivsync/resources");
            }
            rulesetOk = FsBootstrap.init(gameJar, res, savesDir);
            if (!rulesetOk) {
                System.err.println("warning: ruleset load failed - server runs in passive mode (no live simulation)");
            }
        } else {
            System.err.println("warning: no --gamejar provided - server runs in passive mode (no live simulation)");
        }

        // 启动横幅
        System.out.println();
        System.out.println("=== UncivSync Server ===");
        System.out.println("  build     : " + FsProtocol.SERVER_BUILD
                + "   (完整 GameInfo 权威传输 / revision+checksum / 核心战斗与贸易结算)");
        System.out.println("  port      : " + port + (adminPort > 0 ? "（游戏/联机）" : ""));
        if (adminPort > 0) System.out.println("  admin port: " + adminPort + "（管理页独立端口）");
        System.out.println("  saves dir : " + (savesDir == null ? "(none)" : savesDir.getAbsolutePath()));
        System.out.println("  admin page: http://localhost:" + (adminPort > 0 ? adminPort : port) + "/admin"
                + (adminPass != null ? "（已设口令，访问需输入）" : "（未设口令，开放访问）"));
        // Diagnostic: confirm kotlin-stdlib is on the runtime classpath (java -jar uses only
        // MANIFEST Class-Path; -cp is ignored).
        System.out.println("  classpath : " + System.getProperty("java.class.path"));
        for (String cn : new String[]{"kotlin.sequences.DistinctSequence", "kotlin.random.RandomKt"}) {
            try { Class.forName(cn); System.out.println("  [diag] OK   " + cn); }
            catch (Throwable t) { System.out.println("  [diag] FAIL " + cn + " -> " + t); }
        }
        if (gameJar != null) System.out.println("  game jar  : " + gameJar.getAbsolutePath());
        if (rulesetOk && FsBootstrap.vanillaRuleset != null) {
            com.unciv.models.ruleset.Ruleset r = FsBootstrap.vanillaRuleset;
            System.out.println("  ruleset   : " + r.getName() + " (" + r.getNations().size() + " nations / "
                    + r.getUnits().size() + " units / " + r.getBuildings().size() + " buildings)");
        }
        System.out.println();

        FsServer server = new FsServer(port, savesDir);
        server.adminPass = adminPass;
        server.adminPort = adminPort;
        server.logOps = logOps;
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.run();
    }

    /**
     * Default saves directory = <code>Saves/</code> next to this jar, so the server stays portable
     * (copy the folder anywhere and the games come along).
     * On the first run it also copies over the pre-1.0.0 location
     * (<code>~/Documents/UncivMP/Saves</code>) so nothing is lost by the move.
     */
    private static File defaultSavesDir() {
        File base = jarDirectory();
        if (base == null) base = new File(".").getAbsoluteFile();
        File saves = new File(base, "Saves");
        boolean wasMissing = !saves.isDirectory();
        if (wasMissing) saves.mkdirs();
        migrateLegacySaves(saves);
        return saves;
    }

    /** Directory that contains this jar (or the classes dir when running from a build output). */
    private static File jarDirectory() {
        try {
            java.net.URL self = Main.class.getProtectionDomain().getCodeSource().getLocation();
            File selfJar = new File(self.toURI());
            return selfJar.isFile() ? selfJar.getParentFile() : selfJar;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * One-shot migration of the old default saves location. Only runs when the new Saves/ is empty,
     * and only copies top-level save files (<code>*.json</code>, <code>.auth</code>) - the
     * resources/ subfolder is re-extracted from the game jar automatically.
     */
    private static void migrateLegacySaves(File newDir) {
        File marker = new File(newDir, ".migrated-from-Documents-UncivMP");
        if (marker.isFile()) return;
        File legacy = new File(System.getProperty("user.home"), "Documents/UncivMP/Saves");
        if (!legacy.isDirectory()) return;
        File[] existing = newDir.listFiles();
        if (existing != null && existing.length > 0) return;   // never overwrite an active saves dir

        int copied = 0;
        try (Stream<Path> stream = Files.list(legacy.toPath())) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String name = p.getFileName().toString();
                if (!Files.isRegularFile(p)) continue;
                if (!looksLikeSaveName(name)) continue;
                Files.copy(p, newDir.toPath().resolve(name), StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        } catch (IOException e) {
            System.err.println("[migrate] could not read " + legacy + ": " + e.getMessage());
        }
        try {
            Files.write(marker.toPath(),
                    ("migrated " + copied + " file(s) from " + legacy + System.lineSeparator())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
        if (copied > 0) {
            System.out.println("[migrate] copied " + copied + " save file(s) from " + legacy
                    + " -> " + newDir.getAbsolutePath());
        }
    }

    private static boolean looksLikeSaveName(String name) {
        for (String s : SAVE_SUFFIXES) if (name.endsWith(s)) return true;
        return false;
    }

    /** Prefer the standalone JAR itself, then support older development layouts. */
    private static File autoDetectGameJar() {
        try {
            File self = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (self.isFile()) {
                try (java.util.jar.JarFile archive = new java.util.jar.JarFile(self)) {
                    if (archive.getJarEntry("com/unciv/logic/GameInfo.class") != null
                            && archive.getJarEntry("jsons/Civ V - Vanilla/Nations.json") != null) {
                        return self.getCanonicalFile();
                    }
                }
            }
        } catch (Exception ignored) { }

        // Candidate 0（现行）: Server/UncivSync.jar —— 与 server.jar 同目录
        File dir = jarDirectory();
        if (dir != null) {
            File sameDir = new File(dir, "UncivSync.jar");
            if (sameDir.isFile()) {
                try { return sameDir.getCanonicalFile(); } catch (IOException ignored) { return sameDir; }
            }
        }

        // Candidate 1（旧布局后备）: ../Client/UncivSync.jar next to our own jar
        if (dir != null) {
            File c = new File(dir, "../Client/UncivSync.jar");
            if (c.isFile()) {
                try { return c.getCanonicalFile(); } catch (IOException ignored) { return c; }
            }
        }

        // Candidate 2: current working directory
        File cwd = new File("UncivSync.jar");
        if (cwd.isFile()) return cwd;

        // Candidate 3: cwd/../Client/UncivSync.jar
        File rel = new File("../Client/UncivSync.jar");
        if (rel.isFile()) return rel;

        return null;
    }
}
