package battleaudio;

import battleaudio.audio.PlaybackManager;
import battleaudio.core.RuleEngine;
import battleaudio.model.Config;
import battleaudio.model.Config.Candidate;
import battleaudio.model.Config.Rule;
import com.badlogic.gdx.audio.Music;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class FadePlaybackTests {
    private static int checks;
    private FadePlaybackTests() { }
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static void near(float actual, float expected, String message) {
        check(Math.abs(actual - expected) < .001f, message + ": " + actual + " != " + expected);
    }
    private static final class Track implements Music {
        boolean playing, disposed; float volume, position; int plays;
        public void play() { playing = true; plays++; } public void pause() { playing = false; }
        public void stop() { playing = false; } public boolean isPlaying() { return playing; }
        public void setLooping(boolean value) { } public boolean isLooping() { return false; }
        public void setVolume(float value) { volume = value; } public float getVolume() { return volume; }
        public void setPan(float pan, float value) { volume = value; }
        public void setPosition(float value) { position = value; } public float getPosition() { return position; }
        public void dispose() { disposed = true; playing = false; }
        public void setOnCompletionListener(OnCompletionListener listener) { }
    }
    private static final class Background implements PlaybackManager.BackgroundControl {
        int started, finished;
        float recoverySeconds;
        public Object start(float ratio) { started++; return new Object(); }
        public void finish(Object token) { finished++; }
        public void finish(Object token, float seconds) { finished++; recoverySeconds=seconds; }
    }
    private static final class Fixture {
        final List<Track> tracks = new ArrayList<>();
        final Background background = new Background();
        final PlaybackManager manager = new PlaybackManager(cache -> {
            Track track = new Track(); tracks.add(track); return track;
        }, message -> { throw new AssertionError(message); }, new Random(1), background);
    }
    private static Rule rule(String id, float fadeIn, float fadeOut) {
        Rule rule = new Rule(); rule.id = id; Candidate audio = rule.audio();
        audio.cachePath = "cache/" + id + ".wav"; audio.duration = 10; audio.end = 10;
        audio.volume = .8f; audio.fadeInSeconds = fadeIn; audio.fadeOutSeconds = fadeOut;
        return rule;
    }
    public static void main(String[] args) { run(); }
    public static void run() {
        zeroPreservesLegacy();
        fadesAndRetrigger();
        naturalEndAndLoop();
        fadeCannotLeaveSelectedSegment();
        roomAndManualStops();
        ruleLifecycle();
        validationAndCopy();
        System.out.println("PASS: " + checks + " audio fade checks");
    }
    private static void zeroPreservesLegacy() {
        Fixture f = new Fixture(); Rule rule = rule("legacy", 0, 0);
        f.manager.start(rule, "room"); Track track = f.tracks.get(0);
        near(track.volume, .8f, "Zero fade starts at configured volume");
        f.manager.stopRule(rule.id);
        check(track.disposed && f.manager.count() == 0 && f.background.finished == 1,
                "Zero fade stops immediately and releases background once");
    }
    private static void fadesAndRetrigger() {
        Fixture f = new Fixture(); Rule rule = rule("fade", 2, 2); rule.retriggerMode = Config.RetriggerMode.IGNORE;
        f.manager.start(rule, "room"); Track first = f.tracks.get(0);
        near(first.volume, 0, "Fade-in starts silent");
        f.manager.tick(.5f); near(first.volume, .2f, "Fade-in reaches one quarter");
        f.manager.stopRule(rule.id);
        near(first.volume, .2f, "Stopping during fade-in does not jump in volume");
        check(!f.manager.isRuleActive(rule.id) && !first.disposed && f.background.finished == 1,
                "Fading-out instance is logically stopped and releases its background request");
        near(f.background.recoverySeconds, 2, "Background recovery follows the custom fade duration");
        f.manager.tick(.5f); near(first.volume, .15f, "Fade-out starts from current gain");
        f.manager.start(rule, "room");
        check(f.tracks.size() == 2 && f.manager.isRuleActive(rule.id), "Fading tail does not block ignore-mode retrigger");
        f.manager.tick(1.5f); check(first.disposed && f.manager.count() == 1, "Old fading tail is released at its deadline");
        f.manager.stopAll(); check(f.tracks.get(1).disposed && f.background.finished == 2, "Manual stop is immediate");
    }
    private static void naturalEndAndLoop() {
        Fixture f = new Fixture(); Rule rule = rule("loop", 0, 2);
        rule.playbackMode = Config.PlaybackMode.LIMITED; rule.loopCount = 2;
        f.manager.start(rule, "room"); Track track = f.tracks.get(0);
        track.position = 9; f.manager.tick(.1f);
        near(track.volume, .8f, "Intermediate loop does not fade");
        track.position = 10; f.manager.tick(.1f);
        check(track.plays == 2 && track.position == 0, "Next loop restarts normally");
        track.position = 9; f.manager.tick(.1f);
        near(track.volume, .4f, "Final loop fades according to remaining segment time");
        check(f.background.finished == 1, "Natural fade begins background recovery");
        track.position = 10; f.manager.tick(.1f);
        check(track.disposed && f.background.finished == 1, "Natural ending disposes and releases once");
    }
    private static void roomAndManualStops() {
        Fixture f = new Fixture(); Rule rule = rule("room", 0, 1);
        f.manager.start(rule, "one"); Track track = f.tracks.get(0);
        f.manager.roomEnded("one");
        check(!track.disposed && !f.manager.isRuleActive(rule.id), "Room end starts a logical stop with an audible tail");
        f.manager.tick(.5f); near(track.volume, .4f, "Room-end tail fades");
        f.manager.stopAll(); check(track.disposed && f.manager.count() == 0, "Stop all immediately removes fading tail");
        check(f.background.finished == 1, "Fading then manual stop releases background only once");
    }
    private static void fadeCannotLeaveSelectedSegment() {
        Fixture f = new Fixture(); Rule rule = rule("short", 0, 10);
        rule.audio().start=3; rule.audio().end=5;
        f.manager.start(rule, "room"); Track track=f.tracks.get(0);
        f.manager.tick(0); near(track.volume,.8f,"Long natural fade is capped to selected segment");
        track.position=4; f.manager.tick(.1f); near(track.volume,.4f,"Long natural fade stays within segment");
        near(f.background.recoverySeconds,1,"Background recovery follows the remaining selected segment");
        f.manager.stopAll();
        Fixture stopped = new Fixture(); rule=rule("partial",0,2); rule.playbackMode=Config.PlaybackMode.INFINITE;
        stopped.manager.start(rule,"room"); track=stopped.tracks.get(0); track.position=9.5f;
        stopped.manager.stopRule(rule.id); near(stopped.background.recoverySeconds,.5f,"Stop recovery is capped to remaining segment");
        stopped.manager.tick(.25f); near(track.volume,.4f,"Truncated stop fade reaches halfway");
        track.position=10; stopped.manager.tick(.01f);
        check(track.disposed,"Fade cannot play beyond the selected end position");
    }
    private static void validationAndCopy() {
        Rule original = rule("persist", 1.25f, 2.5f);
        Rule copy = new Gson().fromJson(new Gson().toJson(original), Rule.class);
        near(copy.audio().fadeInSeconds, 1.25f, "Fade-in survives serialization");
        near(copy.audio().fadeOutSeconds, 2.5f, "Fade-out survives serialization");
        Config config = new Config(); config.rules.add(copy); config.validate();
        for (float invalid : new float[] {-1, Float.NaN, Float.POSITIVE_INFINITY}) {
            copy.audio().fadeOutSeconds = invalid; boolean rejected = false;
            try { config.validate(); } catch (IllegalArgumentException ex) { rejected = true; }
            check(rejected, "Invalid fade duration is rejected");
        }
    }
    private static void ruleLifecycle() {
        Fixture f = new Fixture(); Rule rule = rule("victory", 0, 1);
        rule.triggers.add(Config.Trigger.BOSS_START); rule.stopConditions.triggers.add(Config.Trigger.BOSS_VICTORY);
        Config config = new Config(); config.rules.add(rule);
        RuleEngine engine = new RuleEngine(config, f.manager);
        engine.beginRun(false, 80, 80); engine.battleStart("boss", true, false, 80, 80);
        Track track = f.tracks.get(0); engine.victory(true, false, false);
        check(!track.disposed && !f.manager.isRuleActive(rule.id), "Victory room end starts a single fade before stop condition");
        f.manager.tick(.5f); near(track.volume, .4f, "Victory fade is not restarted by the later stop condition");
        f.manager.tick(.5f); check(track.disposed, "Victory fade eventually disposes the audio");
    }
}
