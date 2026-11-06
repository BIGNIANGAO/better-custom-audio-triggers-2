package battleaudio;

import battleaudio.core.RuleEngine;
import battleaudio.model.Config;
import battleaudio.model.Config.Rule;
import battleaudio.model.Config.Trigger;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class EncounterSelectionTests {
    private static int checks;
    private EncounterSelectionTests() { }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static Rule rule(String id, Trigger... triggers) {
        Rule rule = new Rule(); rule.id = id; rule.triggers.addAll(Arrays.asList(triggers));
        rule.audio().cachePath = "cache/" + id + ".wav";
        rule.audio().duration = 10; rule.audio().end = 10;
        return rule;
    }

    private static final class Recorder implements RuleEngine.Playback {
        final List<String> starts = new ArrayList<>();
        public void start(Rule rule, String scene) { starts.add(rule.id); }
        public void stopScene(String scene) { }
        public void stopAll() { }
    }

    public static void main(String[] args) { run(); }

    public static void run() {
        Config config = new Config();
        Rule legacy = rule("legacy", Trigger.BOSS_START, Trigger.ELITE_START);
        Rule boss = rule("boss", Trigger.BOSS_START, Trigger.ELITE_START);
        boss.filter(Trigger.BOSS_START).selectEncounters(true);
        boss.filter(Trigger.BOSS_START).encounterIds.addAll(Arrays.asList("Hexaghost", "The Heart"));
        Rule elite = rule("elite", Trigger.ELITE_START);
        elite.filter(Trigger.ELITE_START).selectEncounters(true);
        elite.filter(Trigger.ELITE_START).encounterIds.add("3 Sentries");
        Rule empty = rule("empty", Trigger.BOSS_START);
        empty.filter(Trigger.BOSS_START).selectEncounters(true);
        config.rules.addAll(Arrays.asList(legacy, boss, elite, empty));
        Recorder recorder = new Recorder(); RuleEngine engine = new RuleEngine(config, recorder);
        engine.beginRun(false, 80, 80);
        engine.battleStart("boss-a", true, false, false, 80, 80, "Hexaghost");
        check(recorder.starts.equals(Arrays.asList("legacy", "boss")), "Any and selected boss both trigger");
        recorder.starts.clear();
        engine.battleStart("boss-b", true, false, false, 80, 80, "The Guardian");
        check(recorder.starts.equals(Arrays.asList("legacy")), "Unselected boss and empty custom selection do not trigger");
        recorder.starts.clear();
        engine.battleStart("elite-a", false, true, false, 80, 80, "3 Sentries");
        check(recorder.starts.equals(Arrays.asList("legacy", "boss", "elite")), "Boss selection does not restrict elite trigger");
        recorder.starts.clear();
        engine.battleStart("elite-b", false, true, false, 80, 80, "Gremlin Nob");
        check(recorder.starts.equals(Arrays.asList("legacy", "boss")), "Unselected elite does not trigger its rule");
        recorder.starts.clear();
        engine.battleStart("heart", true, false, false, 80, 80, "The Heart");
        check(recorder.starts.equals(Arrays.asList("legacy", "boss")), "Ending boss matches selected encounter");
        recorder.starts.clear();
        engine.battleStart("unknown", true, false, false, 80, 80, "");
        check(recorder.starts.equals(Arrays.asList("legacy")), "Unknown encounter is not treated as a selected boss");
        Config roundtrip = new Gson().fromJson(new Gson().toJson(config), Config.class);
        check(roundtrip.rules.get(1).filter(Trigger.BOSS_START).encounterIds.equals(Arrays.asList("Hexaghost", "The Heart")),
                "Multiple encounter selections survive serialization");
        com.google.gson.JsonObject oldJson = new Gson().toJsonTree(rule("old", Trigger.BOSS_START)).getAsJsonObject();
        oldJson.remove("filters");
        Rule old = new Gson().fromJson(oldJson, Rule.class);
        check(!old.filter(Trigger.BOSS_START).customEncounter, "Legacy boss rules default to any encounter");
        Rule downlevel = new Gson().fromJson(new Gson().toJson(boss), Rule.class);
        downlevel.filter(Trigger.BOSS_START).customEncounter = false;
        Config oldReaderConfig = new Config(); oldReaderConfig.rules.add(downlevel);
        Recorder oldReader = new Recorder(); RuleEngine oldReaderEngine = new RuleEngine(oldReaderConfig, oldReader);
        oldReaderEngine.beginRun(false, 80, 80); oldReaderEngine.scene("legacy"); oldReaderEngine.event(Trigger.BOSS_START, null);
        check(oldReader.starts.isEmpty(), "A reader without encounter context cannot misread a custom selection as any boss");
        downlevel.filter(Trigger.BOSS_START).selectEncounters(false);
        oldReaderEngine.event(Trigger.BOSS_START, null);
        check(oldReader.starts.size() == 1, "Switching back to any clears the compatibility marker");
        Config stopConfig = new Config(); Rule stopped = rule("stopped", Trigger.HP_LOSS);
        stopped.stopConditions.triggers.add(Trigger.BOSS_START);
        stopped.stopConditions.filter(Trigger.BOSS_START).selectEncounters(true);
        stopped.stopConditions.filter(Trigger.BOSS_START).encounterIds.add("Hexaghost");
        stopConfig.rules.add(stopped);
        final int[] stops = {0};
        RuleEngine stopEngine = new RuleEngine(stopConfig, new RuleEngine.Playback() {
            public void start(Rule rule, String scene) { }
            public void stopRule(String id) { stops[0]++; }
            public void stopScene(String scene) { }
            public void stopAll() { }
        });
        stopEngine.beginRun(false, 80, 80);
        stopEngine.battleStart("other", true, false, false, 80, 80, "The Guardian");
        check(stops[0] == 0, "A different boss does not satisfy the custom stop condition");
        stopEngine.battleStart("match", true, false, false, 80, 80, "Hexaghost");
        check(stops[0] > 0, "The selected boss satisfies the custom stop condition");
        System.out.println("PASS: " + checks + " encounter selection checks");
    }
}
