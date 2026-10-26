// FsGdx.java - Minimal libGDX stubs for headless server
package unciv.fs.local;

import com.badlogic.gdx.*;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.io.File;

public final class FsGdx {
    private FsGdx() {}

    public static File resourcesDir;
    public static File savesDir;

    public static void install(File resourcesDir, File savesDir) {
        FsGdx.resourcesDir = resourcesDir;
        FsGdx.savesDir = savesDir;
        Gdx.app = new StubApp();
        Gdx.files = new StubFiles();
        Gdx.graphics = new StubGraphics();
        Gdx.audio = new StubAudio();
        Gdx.input = new StubInput();
    }

    static final class StubApp implements Application {
        @Override public ApplicationListener getApplicationListener() { return null; }
        @Override public Graphics getGraphics() { return Gdx.graphics; }
        @Override public Audio getAudio() { return Gdx.audio; }
        @Override public Input getInput() { return Gdx.input; }
        @Override public Files getFiles() { return Gdx.files; }
        @Override public Net getNet() { return null; }
        @Override public void log(String tag, String message) { System.out.println("[" + tag + "] " + message); }
        @Override public void log(String tag, String message, Throwable exception) { System.out.println("[" + tag + "] " + message); if (exception != null) exception.printStackTrace(); }
        @Override public void error(String tag, String message) { System.err.println("[" + tag + "] ERROR " + message); }
        @Override public void error(String tag, String message, Throwable exception) { System.err.println("[" + tag + "] ERROR " + message); if (exception != null) exception.printStackTrace(); }
        @Override public void debug(String tag, String message) { }
        @Override public void debug(String tag, String message, Throwable exception) { }
        @Override public void setLogLevel(int level) { }
        @Override public int getLogLevel() { return LOG_INFO; }
        @Override public void setApplicationLogger(ApplicationLogger logger) { }
        @Override public ApplicationLogger getApplicationLogger() { return null; }
        @Override public ApplicationType getType() { return ApplicationType.HeadlessDesktop; }
        @Override public int getVersion() { return 0; }
        @Override public long getJavaHeap() { return Runtime.getRuntime().totalMemory(); }
        @Override public long getNativeHeap() { return 0; }
        @Override public Preferences getPreferences(String name) { return null; }
        @Override public com.badlogic.gdx.utils.Clipboard getClipboard() { return null; }
        @Override public void postRunnable(Runnable runnable) { runnable.run(); }
        @Override public void exit() { System.exit(0); }
        @Override public void addLifecycleListener(LifecycleListener listener) { }
        @Override public void removeLifecycleListener(LifecycleListener listener) { }
    }

    static final class StubFiles implements Files {
        @Override public FileHandle getFileHandle(String path, FileType type) {
            File f = resolve(type, path);
            return new FileHandle(f);
        }
        @Override public FileHandle classpath(String path) { return getFileHandle(path, FileType.Classpath); }
        @Override public FileHandle internal(String path) { return getFileHandle(path, FileType.Internal); }
        @Override public FileHandle external(String path) { return getFileHandle(path, FileType.External); }
        @Override public FileHandle absolute(String path) { return getFileHandle(path, FileType.Absolute); }
        @Override public FileHandle local(String path) { return getFileHandle(path, FileType.Local); }
        @Override public String getExternalStoragePath() { return savesDir == null ? "." : savesDir.getAbsolutePath(); }
        @Override public boolean isExternalStorageAvailable() { return true; }
        @Override public String getLocalStoragePath() { return savesDir == null ? "." : savesDir.getAbsolutePath(); }
        @Override public boolean isLocalStorageAvailable() { return true; }

        private File resolve(FileType type, String path) {
            switch (type) {
                case Internal:
                case Classpath:
                    if (resourcesDir == null) throw new GdxRuntimeException("resourcesDir not configured");
                    return new File(resourcesDir, path);
                case External:
                case Local:
                case Absolute:
                default:
                    return new File(path);
            }
        }
    }

    static final class StubGraphics implements Graphics {
        @Override public boolean isGL30Available() { return false; }
        @Override public boolean isGL31Available() { return false; }
        @Override public boolean isGL32Available() { return false; }
        @Override public com.badlogic.gdx.graphics.GL20 getGL20() { return null; }
        @Override public com.badlogic.gdx.graphics.GL30 getGL30() { return null; }
        @Override public com.badlogic.gdx.graphics.GL31 getGL31() { return null; }
        @Override public com.badlogic.gdx.graphics.GL32 getGL32() { return null; }
        @Override public void setGL20(com.badlogic.gdx.graphics.GL20 gl20) { }
        @Override public void setGL30(com.badlogic.gdx.graphics.GL30 gl30) { }
        @Override public void setGL31(com.badlogic.gdx.graphics.GL31 gl31) { }
        @Override public void setGL32(com.badlogic.gdx.graphics.GL32 gl32) { }
        @Override public int getWidth() { return 0; }
        @Override public int getHeight() { return 0; }
        @Override public int getBackBufferWidth() { return 0; }
        @Override public int getBackBufferHeight() { return 0; }
        @Override public int getSafeInsetLeft() { return 0; }
        @Override public int getSafeInsetTop() { return 0; }
        @Override public int getSafeInsetBottom() { return 0; }
        @Override public int getSafeInsetRight() { return 0; }
        @Override public long getFrameId() { return 0; }
        @Override public float getDeltaTime() { return 0f; }
        @Override public float getRawDeltaTime() { return 0f; }
        @Override public int getFramesPerSecond() { return 0; }
        @Override public GraphicsType getType() { return GraphicsType.LWJGL3; }
        @Override public com.badlogic.gdx.graphics.glutils.GLVersion getGLVersion() { return null; }
        @Override public float getPpiX() { return 96f; }
        @Override public float getPpiY() { return 96f; }
        @Override public float getPpcX() { return 1f; }
        @Override public float getPpcY() { return 1f; }
        @Override public float getDensity() { return 1f; }
        @Override public boolean supportsDisplayModeChange() { return false; }
        @Override public Monitor getPrimaryMonitor() { return null; }
        @Override public Monitor getMonitor() { return null; }
        @Override public Monitor[] getMonitors() { return new Monitor[0]; }
        @Override public DisplayMode[] getDisplayModes() { return new DisplayMode[0]; }
        @Override public DisplayMode[] getDisplayModes(Monitor monitor) { return new DisplayMode[0]; }
        @Override public DisplayMode getDisplayMode() { return null; }
        @Override public DisplayMode getDisplayMode(Monitor monitor) { return null; }
        @Override public boolean setFullscreenMode(DisplayMode displayMode) { return false; }
        @Override public boolean setWindowedMode(int width, int height) { return false; }
        @Override public void setTitle(String title) { }
        @Override public void setUndecorated(boolean undecorated) { }
        @Override public void setResizable(boolean resizable) { }
        @Override public void setVSync(boolean vsync) { }
        @Override public void setForegroundFPS(int fps) { }
        @Override public BufferFormat getBufferFormat() { return null; }
        @Override public boolean supportsExtension(String extension) { return false; }
        @Override public void setContinuousRendering(boolean isContinuous) { }
        @Override public boolean isContinuousRendering() { return false; }
        @Override public void requestRendering() { }
        @Override public boolean isFullscreen() { return false; }
        @Override public com.badlogic.gdx.graphics.Cursor newCursor(com.badlogic.gdx.graphics.Pixmap pixmap, int xHotspot, int yHotspot) { return null; }
        @Override public void setCursor(com.badlogic.gdx.graphics.Cursor cursor) { }
        @Override public void setSystemCursor(com.badlogic.gdx.graphics.Cursor.SystemCursor systemCursor) { }
        @Override public float getBackBufferScale() { return 1f; }
    }

    static final class StubAudio implements Audio {
        @Override public com.badlogic.gdx.audio.AudioDevice newAudioDevice(int samplingRate, boolean isMono) { return null; }
        @Override public com.badlogic.gdx.audio.AudioRecorder newAudioRecorder(int samplingRate, boolean isMono) { return null; }
        @Override public com.badlogic.gdx.audio.Sound newSound(FileHandle fileHandle) { return null; }
        @Override public com.badlogic.gdx.audio.Music newMusic(FileHandle fileHandle) { return null; }
        @Override public boolean switchOutputDevice(String outputDevice) { return false; }
        @Override public String[] getAvailableOutputDevices() { return new String[0]; }
        @Override public void dispose() { }
    }

    static final class StubInput implements Input {
        @Override public float getAccelerometerX() { return 0; }
        @Override public float getAccelerometerY() { return 0; }
        @Override public float getAccelerometerZ() { return 0; }
        @Override public float getGyroscopeX() { return 0; }
        @Override public float getGyroscopeY() { return 0; }
        @Override public float getGyroscopeZ() { return 0; }
        @Override public int getMaxPointers() { return 0; }
        @Override public int getX() { return 0; }
        @Override public int getX(int pointer) { return 0; }
        @Override public int getDeltaX() { return 0; }
        @Override public int getDeltaX(int pointer) { return 0; }
        @Override public int getY() { return 0; }
        @Override public int getY(int pointer) { return 0; }
        @Override public int getDeltaY() { return 0; }
        @Override public int getDeltaY(int pointer) { return 0; }
        @Override public boolean isTouched() { return false; }
        @Override public boolean justTouched() { return false; }
        @Override public boolean isTouched(int pointer) { return false; }
        @Override public float getPressure() { return 0; }
        @Override public float getPressure(int pointer) { return 0; }
        @Override public boolean isButtonPressed(int button) { return false; }
        @Override public boolean isButtonJustPressed(int button) { return false; }
        @Override public boolean isKeyPressed(int keycode) { return false; }
        @Override public boolean isKeyJustPressed(int keycode) { return false; }
        @Override public void getTextInput(TextInputListener listener, String title, String text, String hint) { }
        @Override public void getTextInput(TextInputListener listener, String title, String text, String hint, OnscreenKeyboardType type) { }
        @Override public void setOnscreenKeyboardVisible(boolean visible) { }
        @Override public void setOnscreenKeyboardVisible(boolean visible, OnscreenKeyboardType type) { }
        @Override public void openTextInputField(com.badlogic.gdx.input.NativeInputConfiguration configuration) { }
        @Override public void closeTextInputField(boolean sendCloseEvent) { }
        @Override public void setKeyboardHeightObserver(KeyboardHeightObserver observer) { }
        @Override public void vibrate(int milliseconds) { }
        @Override public void vibrate(int milliseconds, boolean fallback) { }
        @Override public void vibrate(int milliseconds, int amplitude, boolean fallback) { }
        @Override public void vibrate(VibrationType vibrationType) { }
        @Override public float getAzimuth() { return 0; }
        @Override public float getPitch() { return 0; }
        @Override public float getRoll() { return 0; }
        @Override public void getRotationMatrix(float[] matrix) { }
        @Override public long getCurrentEventTime() { return 0; }
        @Override public void setCatchKey(int keycode, boolean catchKey) { }
        @Override public boolean isCatchKey(int keycode) { return false; }
        @Override public void setInputProcessor(com.badlogic.gdx.InputProcessor processor) { }
        @Override public com.badlogic.gdx.InputProcessor getInputProcessor() { return null; }
        @Override public boolean isPeripheralAvailable(Peripheral peripheral) { return false; }
        @Override public int getRotation() { return 0; }
        @Override public Orientation getNativeOrientation() { return Orientation.Landscape; }
        @Override public void setCursorCatched(boolean caught) { }
        @Override public boolean isCursorCatched() { return false; }
        @Override public void setCursorPosition(int x, int y) { }
    }
}