package battleaudio;

import battleaudio.model.Config;
import battleaudio.model.Config.*;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import battleaudio.audio.PlaybackManager;
import battleaudio.core.RuleEngine;
import com.badlogic.gdx.audio.Music;
import java.util.*;

public final class RulePauseTests {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) { run(); }
    public static void run() {
        Config original = new Config(); Rule rule = new Rule(); original.rules.add(rule);
        rule.stopConditions.triggers.add(Trigger.HIGH_HEALTH); rule.stopConditions.high.percent = true; rule.stopConditions.high.value = 50;
        rule.stopConditions.triggers.add(Trigger.HEAL_THRESHOLD); rule.stopConditions.heal.percent = true; rule.stopConditions.heal.value = 40;
        Config restored = Config.fromJson(new JsonParser().parse(new Gson().toJson(original)).getAsJsonObject());
        check(restored.rules.get(0).stopConditions.triggers.size() == 1
                && restored.rules.get(0).stopConditions.triggers.contains(Trigger.HIGH_HEALTH), "Duplicate health stop conditions merge on load");
        check(restored.rules.get(0).stopConditions.high.value == 40, "Merged OR health stop preserves earlier threshold");
        pauseAndResume();
        invalidPauseRelations();
        overlappingAndMultipleBlockers();
        naturalCompletionAndLimitedLoops();
        previewsProbabilityAndStops();
        replacementsFailuresAndChains();
        healthCompatibility();
        System.out.println("PASS: " + checks + " health merge and rule pause checks");
    }
    private static Rule audio(String name) {
        Rule rule = new Rule(); rule.name = name; rule.audio().cachePath = "cache/" + name + ".wav";
        rule.audio().duration = 20; rule.audio().start = 2; rule.audio().end = 18; return rule;
    }
    private static final class Track implements Music {
        boolean playing, disposed; float position, volume; int plays, pauses;
        public void play() { playing = true; plays++; }
        public void pause() { playing = false; pauses++; }
        public void stop() { playing = false; }
        public boolean isPlaying() { return playing; }
        public void setLooping(boolean value) { }
        public boolean isLooping() { return false; }
        public void setVolume(float value) { volume = value; }
        public float getVolume() { return volume; }
        public void setPan(float pan, float value) { volume = value; }
        public void setPosition(float value) { position = value; }
        public float getPosition() { return position; }
        public void dispose() { disposed = true; playing = false; }
        public void setOnCompletionListener(OnCompletionListener listener) { }
    }
    private static final class Fixture {
        final List<Track> tracks = new ArrayList<>();
        final PlaybackManager manager = new PlaybackManager(cache -> {
            Track track = new Track(); tracks.add(track); return track;
        }, error -> { throw new AssertionError(error); });
    }
    private static void pauseAndResume() {
        Rule background = audio("background"); Rule interrupting = audio("interrupting");
        background.pauseWhenRuleIds.add(interrupting.id);
        List<Track> tracks = new ArrayList<>();
        PlaybackManager manager = new PlaybackManager(cache -> {
            Track track = new Track(); tracks.add(track); return track;
        }, error -> { throw new AssertionError(error); });
        manager.start(background, "battle"); Track paused = tracks.get(0); paused.position = 6.5f;
        manager.start(interrupting, "battle");
        check(!paused.playing && !paused.disposed && paused.position == 6.5f, "Selected rule pauses background without losing position");
        manager.tick(); check(!paused.playing && manager.count() == 2, "Paused audio is not treated as naturally ended");
        manager.stopRule(interrupting.id);
        check(paused.playing && paused.position == 6.5f && tracks.size() == 2, "Background resumes the same handle and position after blocker stops");
    }
    private static void invalidPauseRelations() {
        Config config = new Config(); Rule first = audio("first"); Rule second = audio("second");
        config.rules.add(first); config.rules.add(second);
        first.pauseWhenRuleIds.add(second.id); second.pauseWhenRuleIds.add(first.id);
        boolean rejected = false; try { config.validate(); } catch (IllegalArgumentException ex) { rejected = true; }
        check(rejected, "Mutual pause cannot deadlock");
        second.pauseWhenRuleIds.clear(); config.validate();
        first.pauseWhenRuleIds.clear(); first.pauseWhenRuleIds.add(first.id);
        rejected = false; try { config.validate(); } catch (IllegalArgumentException ex) { rejected = true; }
        check(rejected, "Self pause is rejected");
        first.pauseWhenRuleIds.clear(); first.pauseWhenRuleIds.add("missing-rule");
        rejected = false; try { config.validate(); } catch (IllegalArgumentException ex) { rejected = true; }
        check(rejected, "Deleted or missing rule cannot remain a pause dependency");
    }
    private static void overlappingAndMultipleBlockers() {
        Fixture fixture = new Fixture(); Rule background = audio("background"); Rule blocker = audio("blocker"); Rule second = audio("second");
        background.pauseWhenRuleIds.add(blocker.id); background.pauseWhenRuleIds.add(second.id);
        background.retriggerMode = RetriggerMode.OVERLAP; blocker.retriggerMode = RetriggerMode.OVERLAP;
        fixture.manager.start(background,"battle"); fixture.manager.start(background,"battle");
        fixture.manager.start(blocker,"battle"); fixture.manager.start(blocker,"battle");
        check(!fixture.tracks.get(0).playing && !fixture.tracks.get(1).playing, "All background overlaps are paused");
        fixture.tracks.get(2).position = 18; fixture.manager.tick();
        check(!fixture.tracks.get(0).playing, "One remaining blocker overlap prevents early resume");
        fixture.manager.start(second,"battle"); fixture.manager.stopRule(blocker.id);
        check(!fixture.tracks.get(0).playing, "Another selected blocker prevents resume");
        fixture.manager.stopRule(second.id);
        check(fixture.tracks.get(0).playing && fixture.tracks.get(1).playing, "All background overlaps resume after all blockers finish");
        fixture.manager.start(blocker,"battle"); fixture.manager.start(background,"battle");
        check(!fixture.tracks.get(fixture.tracks.size()-1).playing, "New background playback starts paused while blocker is active");
        fixture.manager.stopRule(background.id); fixture.manager.stopRule(blocker.id);
        check(fixture.manager.count()==0, "Stopping paused background prevents later resurrection");
    }
    private static void naturalCompletionAndLimitedLoops() {
        Fixture fixture = new Fixture(); Rule background = audio("limited"); Rule blocker = audio("short");
        background.pauseWhenRuleIds.add(blocker.id); background.playbackMode = PlaybackMode.LIMITED; background.loopCount = 2;
        fixture.manager.start(background,"battle"); Track original = fixture.tracks.get(0); original.position = 5;
        fixture.manager.start(blocker,"battle"); fixture.tracks.get(1).position = 18; fixture.manager.tick();
        check(original.playing && original.position==5 && fixture.manager.count()==1, "Natural blocker completion resumes original progress");
        original.position = 18; fixture.manager.tick();
        check(original.playing && original.position==2, "Pause does not consume a finite loop count");
        original.position = 18; fixture.manager.tick();
        check(original.disposed && fixture.manager.count()==0, "Finite playback still ends after two complete loops");
        background.roomBehavior = RoomBehavior.CONTINUE_ACROSS_ROOMS; fixture.manager.start(background,"one");
        Track carried = fixture.tracks.get(fixture.tracks.size()-1); carried.position = 7;
        fixture.manager.start(blocker,"one"); fixture.manager.sceneChanged("one","two");
        check(carried.playing && carried.position==7, "Room transition stops blocker and resumes carried background without restart");
        fixture.manager.start(blocker,"two"); fixture.manager.stopAll();
        check(fixture.manager.count()==0 && carried.disposed, "Stop all disposes paused playback without resuming");
    }
    private static void previewsProbabilityAndStops() {
        Fixture fixture = new Fixture(); Rule background = audio("automatic"); Rule blocker = audio("triggered");
        background.pauseWhenRuleIds.add(blocker.id); background.triggers.add(Trigger.BATTLE_START);
        blocker.triggers.add(Trigger.HP_LOSS); blocker.triggerProbability = 0;
        blocker.stopConditions.triggers.add(Trigger.GAIN_GOLD);
        Config config = new Config(); config.rules.add(background); config.rules.add(blocker); config.validate();
        RuleEngine engine = new RuleEngine(config,fixture.manager); engine.beginRun(false,80,100); engine.battleStart("battle",false,false,80,100);
        Track original = fixture.tracks.get(0); original.position = 8;
        engine.health(79,100,false); check(original.playing && fixture.manager.count()==1, "Probability failure never pauses other rules");
        fixture.manager.start(blocker,"preview");
        check(original.playing, "Previewing blocker does not pause automatic audio"); fixture.manager.stopPreview();
        blocker.triggerProbability = 1; engine.health(78,100,false);
        check(!original.playing, "Actual successful rule trigger pauses background");
        fixture.manager.start(background,"preview"); Track preview = fixture.tracks.get(fixture.tracks.size()-1);
        check(preview.playing, "Background preview is not paused by a blocker");
        engine.event(Trigger.GAIN_GOLD,null);
        check(original.playing && original.position==8 && preview.playing, "Stop condition resumes background and preserves preview");
        Config restored = Config.fromJson(new JsonParser().parse(new Gson().toJson(config)).getAsJsonObject());
        check(restored.rules.get(0).pauseWhenRuleIds.equals(background.pauseWhenRuleIds), "Pause selection survives save and reload");
    }
    private static void replacementsFailuresAndChains() {
        Fixture fixture = new Fixture(); Rule background = audio("replace-bg"); Rule blocker = audio("replace-blocker");
        background.pauseWhenRuleIds.add(blocker.id); fixture.manager.start(background,"battle"); Track original=fixture.tracks.get(0);
        fixture.manager.start(blocker,"battle"); int plays=original.plays;
        fixture.manager.start(blocker,"battle");
        check(!original.playing && original.plays==plays, "Replacing blocker does not briefly resume background");
        fixture.manager.stopRule(blocker.id); check(original.playing, "Replacement blocker stopping resumes background");
        List<Track> tracks = new ArrayList<>(); List<String> errors = new ArrayList<>();
        PlaybackManager manager = new PlaybackManager(cache -> {
            if (cache.contains("fail")) throw new IllegalArgumentException("unplayable");
            Track track = new Track(); tracks.add(track); return track;
        }, errors::add);
        manager.start(background,"battle"); manager.start(blocker,"battle"); blocker.audio().cachePath="cache/fail.wav";
        manager.start(blocker,"battle");
        check(tracks.get(0).playing && manager.count()==1 && errors.size()==1, "Failed replacement releases pause after old blocker is removed");
        Fixture chain = new Fixture(); Rule first=audio("chain-first"), second=audio("chain-second"), third=audio("chain-third");
        first.pauseWhenRuleIds.add(second.id); second.pauseWhenRuleIds.add(third.id);
        chain.manager.start(first,"battle"); chain.manager.start(second,"battle"); chain.manager.start(third,"battle");
        check(!chain.tracks.get(0).playing && !chain.tracks.get(1).playing && chain.tracks.get(2).playing, "Pause chains keep dependent playback suspended");
        chain.manager.stopRule(third.id);
        check(!chain.tracks.get(0).playing && chain.tracks.get(1).playing, "Chain resumes in dependency order");
        chain.manager.stopRule(second.id); check(chain.tracks.get(0).playing, "Last dependency ends and first background resumes");
    }
    private static void healthCompatibility() {
        Config config = new Config(); Rule rule=audio("legacy-health"); config.rules.add(rule);
        rule.triggers.add(Trigger.HEAL_THRESHOLD);
        rule.stopConditions.triggers.add(Trigger.HEAL_THRESHOLD); rule.stopConditions.heal.value=45;
        Config restored=Config.fromJson(new JsonParser().parse(new Gson().toJson(config)).getAsJsonObject());
        check(restored.rules.get(0).triggers.contains(Trigger.HEAL_THRESHOLD), "Playback heal condition remains distinct");
        check(restored.rules.get(0).stopConditions.high.value==45, "Heal-only stop migrates without losing threshold");
        rule.stopConditions.triggers.add(Trigger.HIGH_HEALTH); rule.stopConditions.high.percent=true; rule.stopConditions.high.value=30;
        restored=Config.fromJson(new JsonParser().parse(new Gson().toJson(config)).getAsJsonObject());
        check(restored.rules.get(0).stopConditions.triggers.size()==1 && restored.rules.get(0).stopConditions.additionalHigh.value==45,
                "Different legacy units retain both thresholds under one stop condition");
        check(restored.rules.get(0).pauseWhenRuleIds.isEmpty(), "Old configs default to no linked pause rules");
    }
}
