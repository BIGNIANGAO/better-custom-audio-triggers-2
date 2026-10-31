package battleaudio.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl.audio.OpenALAudio;
import com.badlogic.gdx.utils.IntArray;
import java.lang.reflect.Field;
import org.lwjgl.openal.AL10;

/** Expands libGDX's initial pool on demand, subject only to the audio device's capacity. */
final class OpenALSources {
    private OpenALSources() { }
    static boolean grow() {
        if (!(Gdx.audio instanceof OpenALAudio)) return false;
        try {
            AL10.alGetError();
            int source = AL10.alGenSources();
            if (source == 0 || AL10.alGetError() != AL10.AL_NO_ERROR) return false;
            Field idleField = OpenALAudio.class.getDeclaredField("idleSources");
            Field allField = OpenALAudio.class.getDeclaredField("allSources");
            idleField.setAccessible(true);
            allField.setAccessible(true);
            IntArray idle = (IntArray) idleField.get(Gdx.audio);
            IntArray all = (IntArray) allField.get(Gdx.audio);
            idle.add(source);
            all.add(source);
            return true;
        } catch (ReflectiveOperationException ex) { return false; }
    }
}
