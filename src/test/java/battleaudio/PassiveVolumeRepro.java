package battleaudio;

import battleaudio.audio.PlaybackManager;
import battleaudio.core.RuleEngine;
import battleaudio.model.Config;
import com.badlogic.gdx.audio.Music;
import java.lang.reflect.Proxy;
import java.util.*;

/** Run against 0.2.1 as well: detects simultaneous passive instances while checking their actual gain. */
public final class PassiveVolumeRepro {
    public static void main(String[] args) {
        List<Float> gains = new ArrayList<>();
        PlaybackManager manager = new PlaybackManager(cache -> (Music) Proxy.newProxyInstance(
                Music.class.getClassLoader(), new Class<?>[]{Music.class}, (proxy, method, values) -> {
                    if (method.getName().equals("setVolume")) gains.add((Float)values[0]);
                    if (method.getName().equals("isPlaying")) return true;
                    if (method.getReturnType() == float.class) return 0f;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                }), error -> { throw new AssertionError(error); });
        Config config = new Config(); Config.Rule rule = new Config.Rule();
        // Reflection lets the same reproduction run before and after schema migration.
        Object audio = rule;
        try { audio = Config.Rule.class.getMethod("audio").invoke(rule); } catch (Exception ignored) { }
        try {
            audio.getClass().getField("cachePath").set(audio, "cache/test.wav");
            audio.getClass().getField("duration").setDouble(audio, 10);
            audio.getClass().getField("end").setDouble(audio, 10);
            audio.getClass().getField("volume").setFloat(audio, .199f);
        } catch (Exception ex) { throw new AssertionError(ex); }
        rule.triggers = EnumSet.of(Config.Trigger.ORB_PASSIVE); config.rules.add(rule);
        RuleEngine engine = new RuleEngine(config, manager); engine.scene("battle");
        for (int i = 0; i < 3; i++) engine.event(Config.Trigger.ORB_PASSIVE, "Lightning");
        System.out.println("3 lightning passives: handles=" + manager.count() + ", gains=" + gains);
        for (float gain : gains) if (Math.abs(gain - .199f) > .0001f) throw new AssertionError("Independent volume ignored");
        if (manager.count() != 1) throw new AssertionError("Same rule stacks passive audio; expected replace policy to retain one instance");
    }
}
