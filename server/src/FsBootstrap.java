// FsBootstrap.java - Boot Unciv core in headless mode (libGDX stubbed out)
// This is the entry point for the simulation layer.
// Strategy:
//   1. Install FsGdx stubs so Gdx.files.internal() reads from extracted resources dir.
//   2. Extract jsons/ from the game jar if not already extracted.
//   3. Load RulesetCache, then a GameInfo from JSON, then call setTransients().
//   4. Provide an API to apply ops and broadcast snapshots.
package unciv.fs.local;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Json;
import com.unciv.json.UncivJsonKt;
import com.unciv.logic.GameInfo;
import com.unciv.models.ruleset.Ruleset;
import com.unciv.models.ruleset.RulesetCache;

import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class FsBootstrap {
    private FsBootstrap() {}

    public static File resourcesDir;
    public static File savesDir;
    public static File gameJar;

    public static Ruleset vanillaRuleset = null;

    /** Install stubs + extract resources + load ruleset. Returns true if ruleset loaded. */
    public static boolean init(File gameJarFile, File resourcesDirArg, File savesDirArg) {
        // 核心 jar 同时服务客户端和权威端；权威进程不得进入客户端的拦截分支。
        System.setProperty("unciv.fs.authority", "true");
        gameJar = gameJarFile;
        savesDir = savesDirArg;
        if (resourcesDirArg == null) {
            // Sibling of Saves/ (i.e. next to the jar) so the saves folder holds saves only.
            File parent = savesDirArg.getParentFile();
            resourcesDirArg = new File(parent != null ? parent : savesDirArg, "resources");
            resourcesDirArg.mkdirs();
        }
        resourcesDir = resourcesDirArg;

        // Install libGDX stubs
        FsGdx.install(resourcesDir, savesDir);

        // Install a stub UncivGame so GUI.clearUndoCheckpoints etc. don't blow up
        try {
            com.unciv.UncivGame.Current = new com.unciv.UncivGame(true /* consoleMode */);
            // Provide minimum required fields (files, settings) so other transient accesses don't NPE
            com.unciv.UncivGame.Current.files = new com.unciv.logic.files.UncivFiles(Gdx.files, "");
            com.unciv.UncivGame.Current.settings = new com.unciv.models.metadata.GameSettings();
            // 官方 TurnManager.startTurn 会读这个开关决定"回合一开始就自动跑单位"。
            // 服务端必须为 false：真人单位的自动化走官方"回合末统一移动按钮"语义
            // （civ.runAutomation op），FsTurnSync 不再抢跑（详见其文件头注释）。
            com.unciv.UncivGame.Current.settings.setAutomatedUnitsMoveOnTurnStart(false);
        } catch (Throwable t) {
            System.err.println("[bootstrap] UncivGame install failed (may still work): " + t.getMessage());
        }

        // Extract jsons/ from game jar to resources dir
        if (!hasJsonsExtracted(resourcesDir)) {
            extractJsons(gameJar, resourcesDir);
        }

        // Load vanilla ruleset (consoleMode=false so RulesetCache routes through Gdx.files.internal,
        // which our StubFiles resolves against resourcesDir)
        try {
            java.util.List<String> loaded = RulesetCache.INSTANCE.loadRulesets(false, true);
            vanillaRuleset = RulesetCache.INSTANCE.getVanillaRuleset();
            return vanillaRuleset != null && !vanillaRuleset.getNations().isEmpty();
        } catch (Throwable t) {
            System.err.println("[bootstrap] ruleset load FAILED:");
            t.printStackTrace();
            return false;
        }
    }

    public static boolean hasJsonsExtracted(File dir) {
        File d = new File(dir, "jsons/Civ V - Vanilla");
        return d.isDirectory() && new File(d, "Nations.json").exists();
    }

    public static void extractJsons(File jarFile, File targetDir) {
        targetDir.mkdirs();
        try (JarFile jf = new JarFile(jarFile)) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                String name = e.getName();
                if (!name.startsWith("jsons/")) continue;
                if (name.endsWith("/")) continue; // directory entry
                File out = new File(targetDir, name);
                out.getParentFile().mkdirs();
                try (InputStream in = jf.getInputStream(e)) {
                    Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception ex) {
            System.err.println("[bootstrap] extraction failed: " + ex.getMessage());
        }
    }

    /** Load a GameInfo from a JSON file and call setTransients(ruleset). */
    public static GameInfo loadGame(File jsonFile) {
        try {
            String text = new String(Files.readAllBytes(jsonFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            Json json = UncivJsonKt.json();
            GameInfo gi = json.fromJson(GameInfo.class, new StringReader(text));
            gi.setRuleset(vanillaRuleset);
            gi.setTransients();
            System.out.println("[bootstrap] loaded game: turns=" + gi.getTurns() + " civs=" + gi.getCivilizations().size());
            return gi;
        } catch (Throwable t) {
            System.err.println("[bootstrap] loadGame failed: " + t.getMessage());
            t.printStackTrace();
            return null;
        }
    }

    /** Save a GameInfo back to JSON. */
    public static void saveGame(GameInfo gi, File jsonFile) {
        try {
            Json json = UncivJsonKt.json();
            String text = json.toJson(gi);
            Files.write(jsonFile.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("[bootstrap] saved game: " + jsonFile);
        } catch (Exception e) {
            System.err.println("[bootstrap] saveGame failed: " + e.getMessage());
        }
    }
}
