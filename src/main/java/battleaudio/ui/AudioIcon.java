package battleaudio.ui;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

/** Shared, antialiased speaker badge with thick strokes for the game's small icons. */
public final class AudioIcon {
    private static Texture texture;
    private static Texture badge;
    private AudioIcon() { }
    public static Texture texture() {
        if (texture != null) return texture;
        return texture = create(64);
    }
    /** ModsScreen draws native texture dimensions in 45 px rows, at a fixed 55,-27 offset. */
    public static Texture badgeTexture() { if (badge == null) badge = create(32); return badge; }
    private static Texture create(int size) {
        Pixmap pixels = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            int coverage = 0;
            for (int sy = 0; sy < 4; sy++) for (int sx = 0; sx < 4; sx++)
                if (inside((x + (sx + 0.5) / 4) * 64 / size, (y + (sy + 0.5) / 4) * 64 / size)) coverage++;
            if (coverage > 0) pixels.drawPixel(x, y, (0xB9EEF2 << 8) | Math.round(coverage * 255f / 16));
        }
        Texture result = new Texture(pixels);
        result.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pixels.dispose();
        return result;
    }
    private static boolean inside(double x, double y) {
        double dy = Math.abs(y - 32);
        if (x >= 7 && x <= 20 && dy <= 10) return true;
        if (x >= 19 && x <= 34 && dy <= 10 + (x - 19) * 0.8) return true;
        double radius = Math.hypot(x - 32, y - 32);
        return x >= 40 && dy <= (x - 32) * 1.2
                && (Math.abs(radius - 15) <= 2.5 || Math.abs(radius - 26) <= 2.5);
    }
}
