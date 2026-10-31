package battleaudio;

import battleaudio.audio.PlaybackManager;
import battleaudio.core.RuleEngine;
import battleaudio.model.Config;
import battleaudio.model.Config.Rule;
import battleaudio.model.Config.Trigger;
import battleaudio.storage.AudioLibrary;
import com.badlogic.gdx.audio.Music;
import java.nio.file.*;
import java.util.*;

public final class AllTests {
    private static int checks;
    public static void main(String[] args) throws Exception {
        LocalizationTests.run();
        rules();
        playback();
        library(Paths.get(args[0]), Paths.get(args[1]));
        background();
        ExtendedTests.run();
        CandidateTests.run();
        StopConditionTests.run();
        RulePauseTests.run();
        PrerequisiteTests.run();
        BackgroundMixTests.run();
        battleaudio.storage.EmbeddedPresetTests.run();
        battleaudio.storage.MusicPackageTests.run(Paths.get(args[0]));
        battleaudio.storage.PortablePackTests.run();
        battleaudio.storage.WorkshopCoverTests.run();
        battleaudio.workshop.WorkshopPublishingTests.run();
        System.out.println("PASS: " + checks + " behavioral checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static Rule rule() {
        Rule rule = new Rule(); rule.retriggerMode = Config.RetriggerMode.OVERLAP;
        rule.audio().duration = 10;
        rule.audio().end = 10;
        rule.audio().cachePath = "cache/test.wav";
        rule.stopAboveHealth = false;
        return rule;
    }
    private static final class Recorder implements RuleEngine.Playback {
        final List<String> starts = new ArrayList<>();
        final List<String> stops = new ArrayList<>();
        public void start(Rule rule, String scene) { starts.add(rule.id + ":" + scene); }
        public void stopScene(String scene) { stops.add(scene); }
        public void stopAbove(int hp, int max) { }
        public void stopAll() { stops.add("all"); }
    }

    private static void rules() {
        Config config = new Config();
        Rule rule = rule();
        rule.roomBehavior = Config.RoomBehavior.STOP_AND_RESTART_AFTER_DAMAGE;
        rule.triggers = EnumSet.of(Trigger.HP_LOSS, Trigger.LOW_HEALTH, Trigger.BLOCK_LOSS,
                Trigger.BATTLE_START, Trigger.BOSS_START, Trigger.FIRST_BATTLE, Trigger.HIGH_HEALTH, Trigger.HEAL_THRESHOLD);
        rule.high.percent = true;
        rule.high.value = 70;
        rule.heal.value = 40;
        config.rules.add(rule);
        Recorder recorder = new Recorder();
        RuleEngine engine = new RuleEngine(config, recorder);
        engine.beginRun(false, 80, 100);
        engine.battleStart("one", true, false, 80, 100);
        check(recorder.starts.size() == 3, "Boss, battle, first battle each create an instance");
        recorder.starts.clear();
        engine.blockLoss();
        engine.health(30, 100, false);
        check(recorder.starts.size() == 3, "Block loss then HP loss and low threshold overlap");
        engine.health(29, 100, false);
        check(recorder.starts.size() == 4, "Staying low only fires HP loss");
        engine.health(40, 100, true);
        check(recorder.starts.size() == 5, "Heal to exact threshold fires once");
        engine.health(50, 100, true);
        check(recorder.starts.size() == 5, "Already above heal threshold does not fire again");
        engine.health(70, 100, true);
        check(recorder.starts.size() == 6, "High threshold includes equality");
        engine.health(30, 100, false);
        check(recorder.starts.size() == 8, "Re-entering low interval retriggers");
        engine.battleEnd();
        check(recorder.stops.contains("one"), "Battle end stops battle scene");
        recorder.starts.clear();
        engine.battleStart("two", false, false, 30, 100);
        check(recorder.starts.size() == 1, "Already low does not retrigger on battle start");
        engine.blockLoss();
        check(recorder.starts.size() == 2, "Losing block does not count as first HP injury");
        engine.blockLoss();
        check(recorder.starts.size() == 3, "Block loss remains an independent event");
        engine.health(29, 100, false);
        check(recorder.starts.size() == 5, "First actual injury restarts once plus independent HP loss");
        engine.battleEnd();
        rule.roomBehavior = Config.RoomBehavior.STOP_AND_RESTART_NEXT_BATTLE;
        recorder.starts.clear();
        engine.battleStart("three", false, false, 30, 100);
        check(recorder.starts.size() == 2, "Immediate continuation and explicit battle condition are independent");
        engine.battleEnd();
        engine.scene("campfire");
        engine.health(40, 100, true);
        check(recorder.starts.get(recorder.starts.size() - 1).endsWith(":campfire"), "Outside battle heal uses scene lifecycle");
        engine.scene("event");
        check(recorder.stops.contains("campfire"), "Leaving scene stops its audio");
        engine.menu();
        engine.beginRun(true, 80, 100);
        recorder.starts.clear();
        engine.battleStart("loaded", false, false, 80, 100);
        check(recorder.starts.size() == 1, "Loaded run does not repeat first battle");
        engine.beginRun(true, 30, 100);
        recorder.starts.clear();
        engine.battleStart("loaded-low", false, false, 30, 100);
        check(recorder.starts.size() == 1, "Loading already low HP does not invent a previous playback");
        rule.triggers = EnumSet.of(Trigger.POSITIVE_POWER);
        rule.anyPower = false;
        rule.powerIds.add("Strength");
        recorder.starts.clear();
        engine.event(Trigger.POSITIVE_POWER, "Dexterity");
        engine.event(Trigger.POSITIVE_POWER, "Strength");
        check(recorder.starts.size() == 1, "Power filtering uses requested IDs");
        rule.triggers = EnumSet.of(Trigger.LOW_HEALTH);
        rule.low.percent = true;
        rule.low.value = 50;
        engine.baseline(40, 60);
        recorder.starts.clear();
        engine.health(40, 100, false);
        check(recorder.starts.size() == 1, "Max-health changes compare old and new percentages");
        config.validate();
    }

    private static final class FakeMusic implements Music {
        boolean playing;
        boolean disposed;
        float position;
        float volume;
        public void play() { playing = true; }
        public void pause() { playing = false; }
        public void stop() { playing = false; }
        public boolean isPlaying() { return playing; }
        public void setLooping(boolean value) { }
        public boolean isLooping() { return false; }
        public void setVolume(float value) { volume = value; }
        public float getVolume() { return volume; }
        public void setPan(float pan, float volume) { this.volume = volume; }
        public void setPosition(float position) { this.position = position; }
        public float getPosition() { return position; }
        public void dispose() { disposed = true; playing = false; }
        public void setOnCompletionListener(OnCompletionListener listener) { }
    }
    private static final class Track { private Music music; Track(Music music) { this.music = music; } }
    private static void background() {
        FakeMusic original = new FakeMusic();
        original.volume = 0.5f;
        Track track = new Track(original);
        battleaudio.audio.BackgroundMusic.wrap(track);
        battleaudio.audio.BackgroundMusic.ratio(0.2f);
        check(Math.abs(original.volume - 0.1f) < 0.0001f, "BGM ratio is relative to original volume");
        check(track.music.getVolume() == 0.5f, "Game reads nominal BGM volume during ducking");
        track.music.setVolume(0.4f);
        check(Math.abs(original.volume - 0.08f) < 0.0001f, "Game fade updates remain proportional");
        battleaudio.audio.BackgroundMusic.ratio(1);
        check(original.volume == 0.4f, "Last instance restores current game music volume");
        track.music.dispose();
    }
    private static void playback() {
        List<FakeMusic> music = new ArrayList<>();
        PlaybackManager manager = new PlaybackManager(cache -> { FakeMusic item = new FakeMusic(); music.add(item); return item; }, error -> {});
        Rule rule = rule();
        rule.audio().start = 2;
        rule.playbackMode = Config.PlaybackMode.INFINITE;
        rule.audio().end = 5;
        manager.start(rule, "battle");
        manager.start(rule, "battle");
        check(manager.count() == 2 && music.get(0).position == 2, "Overlapping independent instances seek to start");
        rule.audio().start = 3;
        rule.audio().cachePath = "cache/replaced.wav";
        music.get(0).position = 5;
        manager.tick();
        check(music.get(0).position == 2, "Old instance keeps original interval after edit");
        check(manager.activeCaches().contains("cache/test.wav"), "Old revision remains referenced during replacement");
        manager.start(rule, "battle");
        check(music.get(2).position == 3, "New instance uses saved replacement");
        manager.stopAll();
        check(manager.count() == 0 && music.get(0).disposed, "Stop disposes all handles");
        rule.roomBehavior = Config.RoomBehavior.CONTINUE_ACROSS_ROOMS;
        manager.start(rule, "battle");
        manager.roomEnded("battle"); manager.sceneChanged("battle", "next-room");
        check(manager.count() == 1, "Cross-room mode preserves active instance");
        manager.stopAll();
        rule.loop = true;
        manager.start(rule, "preview");
        check(manager.count() == 1, "Preview ignores game health stop condition");
        music.get(music.size() - 1).position = 5;
        manager.tick();
        check(manager.count() == 0, "Preview of looping rule ends at selected endpoint after one play");
        check(rule.loop, "Preview preserves saved loop setting");
        PlaybackManager broken = new PlaybackManager(cache -> { throw new IllegalStateException("bad"); }, error -> {});
        broken.start(rule(), "scene");
        check(broken.count() == 0, "Playback failure is isolated");
    }

    private static void library(Path ffmpeg, Path project) throws Exception {
        Path root = Files.createTempDirectory("battleaudio-test-");
        AudioLibrary library = new AudioLibrary(root, ffmpeg);
        Path sample = root.resolve("测试音频.mp3");
        Process generatedSample = new ProcessBuilder(ffmpeg.toString(), "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=1", sample.toString()).inheritIO().start();
        check(generatedSample.waitFor() == 0, "Synthetic Chinese path MP3 fixture generated");
        Rule imported = library.importAudio(sample);
        check(imported.playable(), "Chinese path MP3 decodes");
        check(imported.playbackMode == Config.PlaybackMode.ONCE && imported.audio().volume == 0.3f, "Imported rules default to once and 30 percent");
        check(Files.exists(library.managed(imported.audio().sourcePath)), "Original is copied to independent library");
        Rule sameName = library.importAudio(sample);
        check(!sameName.id.equals(imported.id) && !sameName.audio().cachePath.equals(imported.audio().cachePath), "Same names never merge");
        Config config = new Config();
        config.rules.add(imported);
        imported.audio().start = imported.audio().duration / 2;
        imported.audio().end = imported.audio().duration;
        library.save(config);
        Config loaded = library.load();
        check(loaded.rules.get(0).playbackMode == Config.PlaybackMode.ONCE && loaded.rules.get(0).audio().volume == 0.3f, "New defaults survive save/reload");
        imported.playbackMode = Config.PlaybackMode.LIMITED; imported.loopCount = 4; imported.audio().volume = 0.72f;
        library.save(config); loaded = library.load();
        check(loaded.rules.get(0).playbackMode == Config.PlaybackMode.LIMITED && loaded.rules.get(0).audio().volume == 0.72f && loaded.rules.get(0).loopCount == 4,
                "Existing saved playback and volume remain unchanged");
        check(loaded.rules.size() == 1 && loaded.rules.get(0).audio().start == imported.audio().start, "Settings and intervals persist");
        String oldCache = imported.audio().cachePath;
        Path tiny = root.resolve("更短音频.wav");
        Process small = new ProcessBuilder(ffmpeg.toString(), "-v", "error", "-f", "lavfi", "-i", "sine=duration=0.05", tiny.toString()).inheritIO().start();
        check(small.waitFor() == 0, "Tiny replacement fixture generated");
        boolean reset = library.replace(imported, tiny);
        check(reset && imported.audio().start == 0 && imported.audio().end == imported.audio().duration, "Short replacement resets interval");
        check(imported.id.equals(loaded.rules.get(0).id), "Replacement preserves item identity");
        library.collect(config, Collections.singleton(oldCache));
        check(Files.exists(library.managed(oldCache)), "Active previous cache is preserved");
        library.collect(config, Collections.emptySet());
        check(!Files.exists(library.managed(oldCache)), "Unused previous cache is collected");
        check(Files.exists(sample), "Original file is never removed");
        Path broken = root.resolve("坏文件.mp3");
        Files.write(broken, new byte[] {1, 2, 3});
        Rule invalid = library.importAudio(broken);
        check(!invalid.playable() && invalid.audio().error != null && Files.exists(library.managed(invalid.audio().sourcePath)), "Invalid audio retained with error");
        for (String format : new String[] {"wav", "ogg", "m4a", "flac"}) {
            Path fixture = root.resolve("样本." + format);
            Process conversion = new ProcessBuilder(ffmpeg.toString(), "-v", "error", "-i", sample.toString(), fixture.toString()).inheritIO().start();
            check(conversion.waitFor() == 0, format + " fixture generated");
            check(library.importAudio(fixture).playable(), format + " imports through decoder");
        }
        try { library.managed("../../outside.wav"); throw new AssertionError("Path traversal accepted"); }
        catch (IllegalArgumentException expected) { check(true, "Managed library rejects traversal"); }
        Rule duplicated=battleaudio.model.RuleCopies.copy(imported,config.rules);
        Path sharedSource=library.managed(imported.audio().sourcePath), sharedCache=library.managed(imported.audio().cachePath);
        config.rules.add(duplicated);library.save(config);
        check(!duplicated.id.equals(imported.id) && !duplicated.audio().id.equals(imported.audio().id)
                && duplicated.audio().cachePath.equals(imported.audio().cachePath), "Copies have new identities and share managed audio files");
        config.rules.remove(imported);library.save(config);library.collect(config,Collections.emptySet());
        check(library.load().rules.size()==1 && library.load().rules.get(0).id.equals(duplicated.id), "Deleted source rule is absent after save and reload");
        check(Files.exists(sharedSource) && Files.exists(sharedCache), "Deleting original preserves files still used by its copy");
        config.rules.clear();library.save(config);library.collect(config,Collections.emptySet());
        check(library.load().rules.isEmpty() && !Files.exists(sharedSource) && !Files.exists(sharedCache), "Deleting last reference removes config and reclaims managed files");
        check(Files.exists(sample) && Files.exists(tiny), "Deletion never removes original audio files");
        System.out.println("Audio fixtures verified in " + root);
    }
}
