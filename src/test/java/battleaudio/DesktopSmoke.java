package battleaudio;

import battleaudio.model.Config;
import battleaudio.model.Config.Rule;
import battleaudio.storage.AudioLibrary;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.backends.lwjgl.audio.OpenALAudio;
import com.badlogic.gdx.backends.lwjgl.LwjglNativesLoader;
import java.nio.file.*;

public final class DesktopSmoke {
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[1]);
        Files.createDirectories(output);
        AudioLibrary library = new AudioLibrary(output.resolve("audio-library"), Paths.get(args[0]));
        Rule rule = library.importAudio(Paths.get(args[2]));
        if (!rule.playable()) throw new AssertionError(rule.audio().error);
        Config config = new Config();
        rule.triggers = java.util.EnumSet.allOf(Config.Trigger.class);
        config.rules.add(rule);
        LwjglNativesLoader.load();
        OpenALAudio audio = new OpenALAudio();
        com.badlogic.gdx.Gdx.audio = audio;
        Music first = null;
        Music second = null;
        try {
            first = audio.newMusic(new com.badlogic.gdx.files.FileHandle(library.managed(rule.audio().cachePath).toFile()));
            second = audio.newMusic(new com.badlogic.gdx.files.FileHandle(library.managed(rule.audio().cachePath).toFile()));
            first.setVolume(0);
            second.setVolume(0);
            first.play();
            second.play();
            first.setPosition(0.2f);
            second.setPosition(0.4f);
            for (int i = 0; i < 20; i++) { audio.update(); Thread.sleep(10); }
            if (!first.isPlaying() || !second.isPlaying() || first.getPosition() <= 0.2f || second.getPosition() <= 0.4f)
                throw new AssertionError("Real OpenAL instances failed to play or seek");
            first.setPosition(0.1f);
            if (Math.abs(first.getPosition() - 0.1f) > 0.1f) throw new AssertionError("Segment loop seek failed");
            second.stop();
            if (!first.isPlaying()) throw new AssertionError("Stopping one instance stopped the other");
            battleaudio.audio.PlaybackManager manager = new battleaudio.audio.PlaybackManager(library, error -> { throw new AssertionError(error); });
            rule.audio().volume = 0; rule.retriggerMode = Config.RetriggerMode.OVERLAP;
            for (int i = 0; i < 24; i++) manager.start(rule, "scene");
            if (manager.count() != 24) throw new AssertionError("Initial libGDX source pool imposed a playback cap");
            manager.stopAll();
            rule.loop = true;
            rule.playbackMode = Config.PlaybackMode.INFINITE;
            rule.audio().start = 0.1;
            rule.audio().end = 0.2;
            manager.start(rule, "preview");
            long deadline = System.nanoTime() + 2000000000L;
            while (manager.hasPreview() && System.nanoTime() < deadline) {
                audio.update(); manager.tick(); Thread.sleep(10);
            }
            if (manager.hasPreview() || !rule.loop) throw new AssertionError("Real preview did not stop once while preserving looping rule");
            int[] playCalls = {0}; java.util.List<Music> realTracks = new java.util.ArrayList<>();
            battleaudio.audio.PlaybackManager measured = new battleaudio.audio.PlaybackManager(cache -> {
                Music track = audio.newMusic(new com.badlogic.gdx.files.FileHandle(library.managed(cache).toFile())); realTracks.add(track);
                return (Music)java.lang.reflect.Proxy.newProxyInstance(Music.class.getClassLoader(), new Class<?>[] {Music.class}, (proxy, method, parameters) -> {
                    if (method.getName().equals("play")) playCalls[0]++;
                    return method.invoke(track, parameters);
                });
            }, error -> { throw new AssertionError(error); });
            rule.playbackMode = Config.PlaybackMode.LIMITED; rule.loopCount = 3;
            rule.roomBehavior = Config.RoomBehavior.CONTINUE_ACROSS_ROOMS; measured.start(rule, "one");
            float position = realTracks.get(0).getPosition();
            measured.roomEnded("one"); measured.sceneChanged("one", "two");
            if (measured.count() != 1 || playCalls[0] != 1 || Math.abs(realTracks.get(0).getPosition() - position) > 0.03)
                throw new AssertionError("Actual cross-room playback restarted or lost its handle");
            deadline = System.nanoTime() + 3000000000L;
            while (measured.count() > 0 && System.nanoTime() < deadline) { audio.update(); measured.tick(); Thread.sleep(10); }
            if (measured.count() != 0 || playCalls[0] != 3) throw new AssertionError("Actual finite loop total was " + playCalls[0]);
            rule.triggers = java.util.EnumSet.of(Config.Trigger.BATTLE_VICTORY);
            rule.playbackMode = Config.PlaybackMode.INFINITE; rule.roomBehavior = Config.RoomBehavior.STOP_ON_ROOM_END;
            battleaudio.core.RuleEngine engine = new battleaudio.core.RuleEngine(config, measured);
            engine.beginRun(false, 80, 100); engine.battleStart("victory", false, false, 80, 100);
            engine.victory(false, false, true); engine.battleEnd();
            audio.update(); measured.tick();
            if (measured.count() != 1 || !realTracks.get(realTracks.size() - 1).isPlaying()) throw new AssertionError("Actual victory audio stopped in cleanup");
            engine.scene("next");
            if (measured.count() != 0) throw new AssertionError("Victory audio did not honor actual room departure");
            Config.Rule background = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(rule), Config.Rule.class);
            background.id = "pause-background"; background.audio().start = .1; background.audio().end = Math.min(.95, background.audio().duration);
            Config.Rule blocker = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(background), Config.Rule.class);
            blocker.id = "pause-blocker"; blocker.playbackMode = Config.PlaybackMode.ONCE; blocker.audio().start = 0; blocker.audio().end = .4;
            background.pauseWhenRuleIds.add(blocker.id); measured.start(background,"pause-room");
            Music continued = realTracks.get(realTracks.size()-1);
            for (int index=0;index<10;index++) { audio.update(); measured.tick(); Thread.sleep(10); }
            measured.start(blocker,"pause-room"); float pausedPosition = continued.getPosition();
            for (int index=0;index<10;index++) { audio.update(); measured.tick(); Thread.sleep(10); }
            if (continued.isPlaying() || !measured.pausedRuleIds().contains(background.id)
                    || Math.abs(continued.getPosition()-pausedPosition) > .08)
                throw new AssertionError("Actual OpenAL linked pause lost progress or kept playing");
            deadline = System.nanoTime() + 2000000000L;
            while (measured.pausedRuleIds().contains(background.id) && System.nanoTime()<deadline) {
                audio.update(); measured.tick(); Thread.sleep(10);
            }
            if (!continued.isPlaying() || measured.count()!=1 || continued.getPosition()<pausedPosition-.08)
                throw new AssertionError("Actual natural blocker completion did not resume the same OpenAL handle");
            for (int index=0;index<10;index++) { audio.update(); measured.tick(); Thread.sleep(10); }
            if (continued.getPosition()<=pausedPosition) throw new AssertionError("Actual resumed audio did not advance from paused position");
            measured.stopAll();
            backgroundMixChecks(audio,library,rule);
            System.out.println("PASS: real OpenAL linked pause holds position and naturally resumes the same handle");
            System.out.println("PASS: real OpenAL finite total=3, cross-room progress preserved and victory audio survives cleanup");
            System.out.println("PASS: real OpenAL preview plays selected segment once with loop configured");
            System.out.println("PASS: real libGDX/OpenAL overlapping playback, independent stop and seek (muted)");
        } finally {
            if (first != null) first.dispose();
            if (second != null) second.dispose();
            audio.dispose();
        }
    }
    private static final class BackgroundTrack {
        private Music music;
        BackgroundTrack(Music music){this.music=music;}
    }
    private static float sourceGain(Music music){
        return org.lwjgl.openal.AL10.alGetSourcef(((com.badlogic.gdx.backends.lwjgl.audio.OpenALMusic)music).getSourceId(),org.lwjgl.openal.AL10.AL_GAIN);
    }
    private static void backgroundMixChecks(OpenALAudio audio,AudioLibrary library,Rule template) {
        float listenerGain=org.lwjgl.openal.AL10.alGetListenerf(org.lwjgl.openal.AL10.AL_GAIN);
        org.lwjgl.openal.AL10.alListenerf(org.lwjgl.openal.AL10.AL_GAIN,0);
        Music original=audio.newMusic(new com.badlogic.gdx.files.FileHandle(library.managed(template.audio().cachePath).toFile()));
        BackgroundTrack owner=new BackgroundTrack(original);
        battleaudio.audio.PlaybackManager manager=new battleaudio.audio.PlaybackManager(library,error->{throw new AssertionError(error);});
        try {
            battleaudio.audio.BackgroundMusic.ratio(1);original.setVolume(.8f);original.setLooping(true);original.play();
            battleaudio.audio.BackgroundMusic.wrap(owner);battleaudio.audio.BackgroundMusic.ratio(.5f);
            Rule first=new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(template),Rule.class);
            first.id="background-first";first.audio().volume=0;first.audio().backgroundRatio=.5f;
            Rule second=new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(first),Rule.class);
            second.id="background-second";second.audio().backgroundRatio=.2f;
            manager.start(first,"background");
            if(Math.abs(sourceGain(original)-.2f)>.003f)throw new AssertionError("Actual BGM snapshot did not use current gain");
            manager.start(second,"background");
            if(Math.abs(sourceGain(original)-.08f)>.003f)throw new AssertionError("Actual overlapping candidate gains did not take minimum");
            manager.stopRule(second.id);
            if(Math.abs(sourceGain(original)-.08f)>.003f)throw new AssertionError("Actual BGM gain restored instantly");
            battleaudio.audio.BackgroundMusic.update(.5f);
            if(Math.abs(sourceGain(original)-.14f)>.003f)throw new AssertionError("Actual BGM gain did not fade toward remaining requirement");
            battleaudio.audio.BackgroundMusic.update(.5f);manager.stopAll();owner.music.setVolume(.9f);
            battleaudio.audio.BackgroundMusic.update(.5f);
            if(Math.abs(sourceGain(original)-.3f)>.003f)throw new AssertionError("Actual final BGM recovery did not interpolate");
            battleaudio.audio.BackgroundMusic.update(100);
            if(Math.abs(sourceGain(original)-.4f)>.003f)throw new AssertionError("Actual recovery exceeded original captured gain");
            System.out.println("PASS: actual OpenAL candidate BGM ratios, minimum overlap, gradual recovery and captured-volume ceiling (listener muted)");
        } finally {
            manager.stopAll();owner.music.dispose();battleaudio.audio.BackgroundMusic.ratio(1);
            org.lwjgl.openal.AL10.alListenerf(org.lwjgl.openal.AL10.AL_GAIN,listenerGain);
        }
    }
}
