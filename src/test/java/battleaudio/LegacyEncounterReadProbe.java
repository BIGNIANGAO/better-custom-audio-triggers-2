package battleaudio;

import battleaudio.core.RuleEngine;
import battleaudio.model.Config;
import battleaudio.model.Config.Rule;
import battleaudio.model.Config.Trigger;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Uses only the old public API so the same probe can be run against an older Mod JAR. */
public final class LegacyEncounterReadProbe {
    private LegacyEncounterReadProbe() { }
    private static final class Recorder implements RuleEngine.Playback {
        final List<String> started = new ArrayList<>();
        public void start(Rule rule, String scene) { started.add(rule.id); }
        public void stopScene(String scene) { }
        public void stopAll() { }
    }
    private static Rule rule(String id) {
        Rule rule = new Rule(); rule.id = id; rule.triggers.add(Trigger.BOSS_START);
        rule.audio().cachePath = "cache/" + id + ".wav"; rule.audio().duration = 10; rule.audio().end = 10;
        return rule;
    }
    public static void main(String[] args) { run(); }
    public static void run() {
        Config source = new Config(); source.rules.add(rule("custom")); source.rules.add(rule("any"));
        JsonObject json = new Gson().toJsonTree(source).getAsJsonObject();
        JsonObject filter = new JsonObject(); filter.addProperty("targetType", "BATTLE_ENCOUNTER");
        filter.addProperty("customEncounter", true);
        JsonArray selected = new JsonArray(); selected.add("Hexaghost"); filter.add("encounterIds", selected);
        json.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("filters").add("BOSS_START", filter);
        Config decoded = Config.fromJson(json); Recorder recorder = new Recorder();
        RuleEngine engine = new RuleEngine(decoded, recorder);
        engine.beginRun(false, 80, 80); engine.battleStart("old-reader", true, false, 80, 80);
        if (!recorder.started.equals(Arrays.asList("any")))
            throw new AssertionError("Old reader broadened a custom boss selection: " + recorder.started);
        System.out.println("PASS: old reader keeps custom boss selection from matching any boss");
    }
}
