package battleaudio.audio;

import battleaudio.core.RuleEngine;
import battleaudio.model.Config;
import battleaudio.model.Config.Rule;
import battleaudio.model.Config.Candidate;
import battleaudio.storage.AudioLibrary;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.google.gson.Gson;
import java.util.*;
import java.util.function.Consumer;

public final class PlaybackManager implements RuleEngine.Playback {
    public interface Factory { Music open(String cache); }
    public interface BackgroundControl {
        default Object start(float ratio) { return null; }
        default void paused(Object token,boolean paused) { }
        default void finish(Object token) { }
        default void finish(Object token,float seconds) { finish(token); }
    }
    private static final BackgroundControl NO_BACKGROUND=new BackgroundControl() { };
    private static final class Instance {
        final Rule rule;
        final Candidate audio;
        String scene;
        final Music music;
        int plays = 1;
        boolean paused;
        boolean stopping, backgroundReleased;
        float fadeInElapsed, fadeOutElapsed, fadeDuration, fadeStartVolume;
        Object backgroundToken;
        Instance(Rule rule, Candidate audio, String scene, Music music) { this.rule = rule; this.audio = audio; this.scene = scene; this.music = music; }
    }
    private final List<Instance> instances = new ArrayList<>();
    private final Factory factory;
    private final Consumer<String> errors;
    private final Set<String> failedCaches = new HashSet<>();
    private final Map<String, String> lastCandidate = new HashMap<>();
    private final Random random;
    private final BackgroundControl background;

    public Set<String> failedCaches() { return new HashSet<>(failedCaches); }

    public PlaybackManager(AudioLibrary library, Consumer<String> errors) {
        this(cache -> Gdx.audio.newMusic(new com.badlogic.gdx.files.FileHandle(library.managed(cache).toFile())), errors,new Random(),BackgroundMusic.control());
    }

    public PlaybackManager(Factory factory, Consumer<String> errors) { this(factory, errors, new Random()); }
    public PlaybackManager(Factory factory, Consumer<String> errors, Random random) { this(factory,errors,random,NO_BACKGROUND); }
    public PlaybackManager(Factory factory,Consumer<String> errors,Random random,BackgroundControl background) {
        this.factory=factory; this.errors=errors; this.random=random; this.background=background;
    }

    @Override public void start(Rule rule, String scene) {
        boolean preview = "preview".equals(scene);
        if (!preview && rule.retriggerMode == Config.RetriggerMode.IGNORE && instances.stream().anyMatch(i -> !i.stopping && !i.scene.equals("preview") && i.rule.id.equals(rule.id))) return;
        List<Candidate> eligible = new ArrayList<>();
        if (rule.candidates != null) for (Candidate c : rule.candidates) if (c.playable() && (preview || c.participate) && !failedCaches.contains(c.cachePath)) eligible.add(c);
        if (eligible.isEmpty()) return;
        if (!preview && rule.retriggerMode == Config.RetriggerMode.REPLACE) stop(i -> !i.scene.equals("preview") && i.rule.id.equals(rule.id), false);
        while (!eligible.isEmpty()) {
            List<Candidate> pool = new ArrayList<>(eligible);
            if (!preview && rule.randomMode == Config.RandomMode.AVOID_REPEAT && pool.size() > 1)
                pool.removeIf(c -> c.id.equals(lastCandidate.get(rule.id)));
            Candidate audio = pool.get(preview ? 0 : random.nextInt(pool.size()));
            if (startChosen(rule, audio, scene)) {
                if (!preview) lastCandidate.put(rule.id, audio.id);
                reconcilePauses(); return;
            }
            eligible.remove(audio);
        }
        reconcilePauses();
    }

    private boolean startChosen(Rule rule, Candidate audio, String scene) {
        Music music = null;
        try {
            Rule snapshot = new Gson().fromJson(new Gson().toJson(rule), Rule.class);
            Candidate chosen = new Gson().fromJson(new Gson().toJson(audio), Candidate.class);
            if ("preview".equals(scene)) snapshot.playbackMode = Config.PlaybackMode.ONCE;
            if (snapshot.playbackMode == null) snapshot.playbackMode = snapshot.loop ? Config.PlaybackMode.INFINITE : Config.PlaybackMode.ONCE;
            music = factory.open(chosen.cachePath);
            music.setVolume(chosen.fadeInSeconds > 0 ? 0 : chosen.volume);
            music.setLooping(false);
            music.play();
            if (!music.isPlaying() && OpenALSources.grow()) music.play();
            if (!music.isPlaying()) throw new IllegalStateException("音频设备无法创建播放实例");
            music.setPosition((float) chosen.start);
            Instance instance=new Instance(snapshot,chosen,scene,music);
            instance.backgroundToken=background.start(chosen.backgroundRatio);
            instances.add(instance);
            return true;
        } catch (RuntimeException ex) {
            if (music != null) music.dispose();
            fail(audio, rule.name, ex);
            return false;
        }
    }

    private void fail(Candidate audio, String name, RuntimeException ex) {
        failedCaches.add(audio.cachePath);
        audio.error = AudioLibrary.DECODE_ERROR;
        errors.accept(name + "：" + ex.getMessage());
    }

    public void tick() {
        tick(Gdx.graphics == null ? 0 : Gdx.graphics.getDeltaTime());
    }
    public void tick(float seconds) {
        float elapsed = Float.isFinite(seconds) && seconds > 0 ? seconds : 0;
        Iterator<Instance> iterator = instances.iterator();
        while (iterator.hasNext()) {
            Instance instance = iterator.next();
            if (instance.paused) continue;
            try {
                boolean playing = instance.music.isPlaying();
                if (instance.stopping) {
                    instance.fadeOutElapsed = Math.min(instance.fadeDuration, instance.fadeOutElapsed + elapsed);
                    float progress = instance.fadeOutElapsed / instance.fadeDuration;
                    instance.music.setVolume(instance.fadeStartVolume * Math.max(0, 1 - progress));
                    if (!playing || progress >= 1 || instance.music.getPosition() >= instance.audio.end) { dispose(instance); iterator.remove(); }
                    continue;
                }
                float position = playing ? instance.music.getPosition() : 0;
                boolean ended = !playing || position >= instance.audio.end;
                if (ended && canRepeat(instance)) {
                    instance.plays++;
                    instance.music.play();
                    instance.music.setPosition((float) instance.audio.start);
                } else if (ended) {
                    dispose(instance);
                    iterator.remove();
                } else {
                    instance.fadeInElapsed += elapsed;
                    float gain = instance.audio.volume;
                    if (instance.audio.fadeInSeconds > 0)
                        gain *= Math.min(1, instance.fadeInElapsed / instance.audio.fadeInSeconds);
                    if (!canRepeat(instance) && instance.audio.fadeOutSeconds > 0) {
                        float remaining = Math.max(0, (float) (instance.audio.end - position));
                        float duration = Math.min(instance.audio.fadeOutSeconds, (float) (instance.audio.end - instance.audio.start));
                        float tail = Math.min(1, remaining / duration);
                        if (tail < 1) { releaseBackground(instance, remaining); gain *= tail; }
                    }
                    if (instance.audio.fadeInSeconds > 0 || instance.audio.fadeOutSeconds > 0)
                        instance.music.setVolume(gain);
                }
            } catch (RuntimeException ex) {
                fail(instance.audio, instance.rule.name, ex);
                dispose(instance);
                iterator.remove();
            }
        }
        reconcilePauses();
    }

    @Override public void stopScene(String scene) { stop(instance -> instance.scene.equals(scene), !scene.equals("preview")); reconcilePauses(); }
    @Override public void stopRule(String ruleId) { stop(instance -> !instance.scene.equals("preview") && instance.rule.id.equals(ruleId), true); reconcilePauses(); }
    @Override public void sceneChanged(String oldScene, String newScene) {
        for (Instance instance : instances) if (!instance.stopping && instance.scene.equals(oldScene)
                && (instance.rule.roomBehavior == Config.RoomBehavior.CONTINUE_ACROSS_ROOMS
                || instance.rule.roomBehavior == Config.RoomBehavior.NEVER_STOP)) instance.scene = newScene;
        stop(instance -> instance.scene.equals(oldScene), true);
        reconcilePauses();
    }
    @Override public void roomEnded(String scene) {
        stop(instance -> instance.scene.equals(scene) && instance.rule.roomBehavior != Config.RoomBehavior.CONTINUE_ACROSS_ROOMS
                && instance.rule.roomBehavior != Config.RoomBehavior.NEVER_STOP, true);
        reconcilePauses();
    }
    @Override public void stopAll() { stop(instance -> true, false); }
    @Override public void menu() { stop(instance -> instance.rule.roomBehavior != Config.RoomBehavior.NEVER_STOP, true); reconcilePauses(); }
    public void stopPreview() { stopScene("preview"); }

    private void stop(java.util.function.Predicate<Instance> predicate, boolean fade) {
        Iterator<Instance> iterator = instances.iterator();
        while (iterator.hasNext()) {
            Instance instance = iterator.next();
            if (!predicate.test(instance)) continue;
            try {
                if (fade && beginFadeOut(instance)) continue;
            } catch (RuntimeException ex) { fail(instance.audio, instance.rule.name, ex); }
            dispose(instance); iterator.remove();
        }
    }

    private boolean beginFadeOut(Instance instance) {
        if (instance.stopping) return true;
        if (instance.paused || instance.audio.fadeOutSeconds <= 0 || !instance.music.isPlaying()) return false;
        float remaining = Math.max(0, (float) (instance.audio.end - instance.music.getPosition()));
        if (remaining <= 0) return false;
        instance.stopping = true;
        instance.fadeOutElapsed = 0;
        instance.fadeDuration = Math.min(instance.audio.fadeOutSeconds, remaining);
        instance.fadeStartVolume = instance.music.getVolume();
        releaseBackground(instance, instance.fadeDuration);
        return true;
    }

    private boolean canRepeat(Instance instance) {
        Config.PlaybackMode mode = instance.rule.playbackMode;
        if (mode == null) mode = instance.rule.loop ? Config.PlaybackMode.INFINITE : Config.PlaybackMode.ONCE;
        if (mode == Config.PlaybackMode.INFINITE) return true;
        return mode == Config.PlaybackMode.LIMITED && instance.plays < instance.rule.loopCount;
    }
    private void dispose(Instance instance) {
        try { instance.music.dispose(); } finally { releaseBackground(instance); }
    }
    private void releaseBackground(Instance instance) {
        releaseBackground(instance, 0);
    }
    private void releaseBackground(Instance instance, float seconds) {
        if (!instance.backgroundReleased) {
            instance.backgroundReleased = true;
            if (seconds > 0) background.finish(instance.backgroundToken, seconds);
            else background.finish(instance.backgroundToken);
        }
    }
    private void reconcilePauses() {
        boolean removed;
        do {
            removed = false;
            Set<String> active = new HashSet<>();
            for (Instance instance : instances) if (!instance.stopping && !instance.scene.equals("preview")) active.add(instance.rule.id);
            Iterator<Instance> iterator = instances.iterator();
            while (iterator.hasNext()) {
                Instance instance = iterator.next();
                if (instance.stopping || instance.scene.equals("preview")) continue;
                boolean shouldPause = instance.rule.pauseWhenRuleIds.stream().anyMatch(active::contains);
                try {
                    if (shouldPause && !instance.paused && instance.music.isPlaying() && instance.music.getPosition() < instance.audio.end) {
                        instance.music.pause(); instance.paused = true; background.paused(instance.backgroundToken,true);
                    } else if (!shouldPause && instance.paused) {
                        instance.music.play();
                        if (!instance.music.isPlaying() && OpenALSources.grow()) instance.music.play();
                        if (!instance.music.isPlaying()) throw new IllegalStateException("音频设备无法恢复暂停的播放");
                        instance.paused = false;
                        resumeBackground(instance);
                    }
                } catch (RuntimeException ex) {
                    fail(instance.audio, instance.rule.name, ex); dispose(instance); iterator.remove(); removed = true;
                }
            }
        } while (removed);
    }
    private void resumeBackground(Instance instance) {
        if (!instance.backgroundReleased) {
            background.paused(instance.backgroundToken,false);
            return;
        }
        float duration = Math.min(instance.audio.fadeOutSeconds, (float) (instance.audio.end - instance.audio.start));
        float remaining = Math.max(0, (float) (instance.audio.end - instance.music.getPosition()));
        float audible = duration > 0 ? Math.min(1, remaining / duration) : 0;
        float ratio = 1 - (1 - instance.audio.backgroundRatio) * audible;
        instance.backgroundToken = background.start(ratio);
        instance.backgroundReleased = false;
    }
    public Set<String> pausedRuleIds() {
        Set<String> paused = new HashSet<>();
        for (Instance instance : instances) if (instance.paused) paused.add(instance.rule.id);
        return paused;
    }
    @Override public boolean isRuleActive(String ruleId) {
        for (Instance instance : instances) {
            if (instance.stopping || instance.scene.equals("preview") || !instance.rule.id.equals(ruleId)) continue;
            if (instance.paused) return true;
            try {
                if (instance.music.isPlaying() && instance.music.getPosition() < instance.audio.end || canRepeat(instance)) return true;
            } catch (RuntimeException ignored) { }
        }
        return false;
    }

    public int count() { return instances.size(); }
    public boolean hasPreview() {
        for (Instance instance : instances) if (instance.scene.equals("preview")) return true;
        return false;
    }
    public Set<String> activeCaches() {
        Set<String> result = new HashSet<>();
        for (Instance instance : instances) result.add(instance.audio.cachePath);
        return result;
    }
}
