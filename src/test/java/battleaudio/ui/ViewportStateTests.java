package battleaudio.ui;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.badlogic.gdx.utils.viewport.FitViewport;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/** Exercises the real settings lifecycle with a recorded OpenGL viewport. */
public final class ViewportStateTests {
    private static int width, height, displayChanges;
    private static int[] viewport;
    private static InputProcessor inputProcessor;

    private ViewportStateTests() { }

    public static final class EmptySpriteBatch extends SpriteBatch {
        @Override public boolean isDrawing() { return false; }
        @Override public void begin() { }
        @Override public void end() { }
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type);
    }

    private static Object zero(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == float.class) return 0f;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static void field(SettingsScreen target, String name, Object value) throws Exception {
        Field field = SettingsScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setup(int w, int h) {
        width = w; height = h; displayChanges = 0; inputProcessor = null;
        viewport = new int[] {0, 0, w, h};
        Gdx.app = proxy(Application.class, (p, m, a) ->
                m.getName().equals("getType") ? Application.ApplicationType.Desktop : zero(m.getReturnType()));
        Gdx.graphics = proxy(Graphics.class, (p, m, a) -> {
            switch (m.getName()) {
                case "getWidth": case "getBackBufferWidth": return width;
                case "getHeight": case "getBackBufferHeight": return height;
                case "getDeltaTime": return 1f / 60;
                case "setDisplayMode": case "setWindowedMode": case "setFullscreenMode":
                    displayChanges++; return zero(m.getReturnType());
                default: return zero(m.getReturnType());
            }
        });
        Gdx.input = proxy(Input.class, (p, m, a) -> {
            if (m.getName().equals("getInputProcessor")) return inputProcessor;
            if (m.getName().equals("setInputProcessor")) { inputProcessor = (InputProcessor) a[0]; return null; }
            return zero(m.getReturnType());
        });
        Gdx.gl = Gdx.gl20 = proxy(GL20.class, (p, m, a) -> {
            if (m.getName().equals("glViewport")) for (int i = 0; i < 4; i++) viewport[i] = (Integer) a[i];
            if (m.getName().equals("glGetIntegerv") && ((Integer) a[0]) == GL20.GL_VIEWPORT) {
                IntBuffer out = (IntBuffer) a[1];
                for (int i = 0; i < 4; i++) out.put(i, viewport[i]);
            }
            return zero(m.getReturnType());
        });
    }

    private static SettingsScreen screen() throws Exception {
        Batch batch = proxy(Batch.class, (p, m, a) -> {
            if (m.getReturnType() == Matrix4.class) return new Matrix4();
            if (m.getReturnType() == Color.class) return Color.WHITE;
            return zero(m.getReturnType());
        });
        Stage stage = new Stage(new FitViewport(1600, 1000), batch);
        SettingsScreen screen = (SettingsScreen) allocate(SettingsScreen.class);
        field(screen, "stage", stage);
        field(screen, "completions", new ConcurrentLinkedQueue<Runnable>());
        field(screen, "saveScheduler", new ScheduledThreadPoolExecutor(1));
        field(screen, "worker", Executors.newSingleThreadExecutor());
        field(screen, "host", proxy(SettingsScreen.Host.class, (p, m, a) -> zero(m.getReturnType())));
        Skin skin = new Skin(); skin.add("background", new BaseDrawable(), Drawable.class);
        field(screen, "skin", skin);
        field(screen, "renderViewport", BufferUtils.newIntBuffer(16));
        return screen;
    }

    private static void unchanged(int[] expected, String step) {
        if (!Arrays.equals(expected, viewport))
            throw new AssertionError(step + " changed game viewport from " + Arrays.toString(expected)
                    + " to " + Arrays.toString(viewport));
    }

    public static void main(String[] args) throws Exception { run(); }

    public static void run() throws Exception {
        Application app = Gdx.app; Graphics graphics = Gdx.graphics; Input input = Gdx.input;
        GL20 gl = Gdx.gl, gl20 = Gdx.gl20;
        try {
            GdxNativesLoader.load();
            for (int[] size : new int[][] {{1920, 1080}, {2560, 1440}, {1280, 720}, {1920, 1200}}) {
                setup(size[0], size[1]);
                SettingsScreen screen = screen();
                int[] expected = {7, 11, width - 20, height - 30};
                viewport = expected.clone();
                try {
                    screen.open(); unchanged(expected, "open");
                    screen.update(); unchanged(expected, "update");
                    screen.render((EmptySpriteBatch) allocate(EmptySpriteBatch.class)); unchanged(expected, "render");
                    screen.close(); unchanged(expected, "close");
                    if (displayChanges != 0) throw new AssertionError("Settings changed the display mode");
                } finally { screen.dispose(); }
            }
            System.out.println("PASS: settings preserve game viewport at four screen sizes");
        } finally { Gdx.app = app; Gdx.graphics = graphics; Gdx.input = input; Gdx.gl = gl; Gdx.gl20 = gl20; }
    }
}
