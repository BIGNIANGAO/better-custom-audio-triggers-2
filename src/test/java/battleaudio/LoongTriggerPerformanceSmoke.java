package battleaudio;

import battleaudio.audio.PlaybackManager;
import battleaudio.core.GameEvents;
import battleaudio.core.RuleEngine;
import battleaudio.model.Config;
import battleaudio.model.Config.Rule;
import battleaudio.model.Config.Trigger;
import battleaudio.model.EventContext;
import battleaudio.model.MusicPack;
import battleaudio.storage.AudioLibrary;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Measures actual rule dispatch and OpenAL playback on a disposable copy of loong. */
public final class LoongTriggerPerformanceSmoke {
    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static Map<String, Object> stats(List<Double> values) {
        if (values.isEmpty()) return Collections.emptyMap();
        List<Double> sorted = new ArrayList<>(values); Collections.sort(sorted);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", sorted.size()); result.put("medianMs", sorted.get(sorted.size()/2));
        result.put("p95Ms", sorted.get((int)Math.ceil(sorted.size()*.95)-1)); result.put("maxMs", sorted.get(sorted.size()-1));
        return result;
    }
    private static boolean targets(Rule rule, String scenario) {
        switch (scenario) {
            case "FIRST_BATTLE": return rule.triggers.contains(Trigger.FIRST_BATTLE);
            case "BOSS_START": return rule.triggers.contains(Trigger.BOSS_START);
            case "HP_LOSS": return rule.triggers.contains(Trigger.HP_LOSS);
            case "LOW_HEALTH": return rule.triggers.contains(Trigger.LOW_HEALTH);
            case "CHANNEL_FROST": return rule.triggers.contains(Trigger.CHANNEL_ORB);
            case "GAIN_GOLD": return rule.triggers.contains(Trigger.GAIN_GOLD);
            case "PLAYER_DEATH": return rule.triggers.contains(Trigger.PLAYER_DEATH);
            case "ELITE_VICTORY": return rule.triggers.contains(Trigger.ELITE_VICTORY);
            default: return false;
        }
    }
    public static void run(BattleAudioMod mod) throws Exception {
        Config config = (Config)field(mod, "config");
        MusicPack pack = config.packs.stream().filter(p -> "loong".equals(p.name)).findFirst()
                .orElseThrow(() -> new AssertionError("loong fixture not loaded"));
        if (!pack.enabled) throw new AssertionError("loong pack disabled");
        RuleEngine engine = (RuleEngine)field(mod, "engine");
        PlaybackManager playback = (PlaybackManager)field(mod, "playback");
        GameEvents events = (GameEvents)field(mod, "events");
        AudioLibrary library = (AudioLibrary)field(mod, "library");
        ((Random)field(engine, "random")).setSeed(2026102801L);
        ((Random)field(playback, "random")).setSeed(2026102802L);
        List<String> pool = new ArrayList<>(Arrays.asList("FIRST_BATTLE", "BOSS_START", "LOW_HEALTH", "CHANNEL_FROST", "GAIN_GOLD", "PLAYER_DEATH", "ELITE_VICTORY"));
        pool.removeIf(scenario -> config.rules.stream().noneMatch(rule -> pack.id.equals(rule.packId)
                && rule.enabled && targets(rule, scenario)
                && rule.candidates.stream().anyMatch(candidate -> candidate.participate && candidate.playable())));
        Collections.shuffle(pool, new Random(20261028L));
        if (pool.size() < 4) throw new AssertionError("Not enough enabled loong scenarios to sample");
        List<String> scenarios = new ArrayList<>(); scenarios.add("HP_LOSS"); scenarios.addAll(pool.subList(0, 4));
        Map<String, Object> report = new LinkedHashMap<>();
        for (String scenario : scenarios) {
            List<Double> all = new ArrayList<>(), successful = new ArrayList<>(), postUpdates = new ArrayList<>();
            List<Double> startedTotals = new ArrayList<>(), collections = new ArrayList<>();
            Set<String> selected = new HashSet<>(); int attemptsStarted = 0;
            Set<String> possible = new HashSet<>();
            for (Rule rule : config.rules) if (pack.id.equals(rule.packId) && targets(rule, scenario))
                for (Config.Candidate candidate : rule.candidates) if (candidate.participate && candidate.playable()) possible.add(candidate.cachePath);
            if (possible.isEmpty()) throw new AssertionError("No eligible audio for " + scenario);
            for (int i = 0; i < 12; i++) {
                playback.stopAll(); engine.beginRun(!scenario.equals("FIRST_BATTLE"), 100, 100);
                if (!scenario.equals("FIRST_BATTLE") && !scenario.equals("BOSS_START")) {
                    engine.battleStart("perf-setup-" + scenario + i, scenario.equals("HP_LOSS") || scenario.equals("LOW_HEALTH")
                            || scenario.equals("PLAYER_DEATH"), scenario.equals("ELITE_VICTORY"), 100, 100);
                    mod.receivePostUpdate();
                }
                Set<String> before = playback.activeCaches();
                long began = System.nanoTime();
                switch (scenario) {
                    case "FIRST_BATTLE": engine.battleStart("perf-first-" + i, false, false, 100, 100); break;
                    case "BOSS_START": engine.battleStart("perf-boss-" + i, true, false, 100, 100); break;
                    case "HP_LOSS": engine.health(80, 100, false, new EventContext().source("ENEMY", "test")); break;
                    case "LOW_HEALTH": engine.health(20, 100, false, new EventContext().source("ENEMY", "test")); break;
                    case "CHANNEL_FROST": engine.event(Trigger.CHANNEL_ORB, "Frost"); break;
                    case "GAIN_GOLD": events.goldMutation(100, 110); break;
                    case "PLAYER_DEATH": engine.death(); break;
                    case "ELITE_VICTORY": engine.victory(false, true, false); break;
                    default: throw new AssertionError(scenario);
                }
                double dispatch = (System.nanoTime()-began)/1000000d;
                Set<String> after = playback.activeCaches();
                Set<String> fresh = new HashSet<>(after); fresh.removeAll(before); fresh.retainAll(possible);
                if (!fresh.isEmpty()) { selected.addAll(fresh); attemptsStarted++; successful.add(dispatch); }
                all.add(dispatch);
                long updateStart = System.nanoTime(); mod.receivePostUpdate();
                double update = (System.nanoTime()-updateStart)/1000000d; postUpdates.add(update);
                if (!fresh.isEmpty()) startedTotals.add(dispatch + update);
                long collectStart = System.nanoTime(); library.collect(config, playback.activeCaches());
                collections.add((System.nanoTime()-collectStart)/1000000d);
            }
            if (attemptsStarted == 0) throw new AssertionError("No audio started for " + scenario);
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("attempts", all.size()); sample.put("starts", attemptsStarted); sample.put("distinctFilesPlayed", selected.size());
            sample.put("allDispatch", stats(all)); sample.put("successfulDispatch", stats(successful)); sample.put("postUpdate", stats(postUpdates));
            sample.put("successfulDispatchPlusUpdate", stats(startedTotals)); sample.put("directFileCollection", stats(collections));
            report.put(scenario, sample);
        }
        playback.stopAll(); engine.menu();
        Files.write(Paths.get("loong-trigger-performance.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report).getBytes(StandardCharsets.UTF_8));
        System.out.println("LOONG_TRIGGER_PERF " + new com.google.gson.Gson().toJson(report));
    }
}
