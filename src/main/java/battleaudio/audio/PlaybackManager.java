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
    }
    private static final BackgroundControl NO_BACKGROUND=new BackgroundControl() { };
    private static final class Instance {
        final Rule rule;
        final Candidate audio;
        String scene;
        final Music music;
        int plays = 1;
        boolean paused;
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
        if (!preview && rule.retriggerMode == Config.RetriggerMode.IGNORE && instances.stream().anyMatch(i -> !i.scene.equals("preview") && i.rule.id.equals(rule.id))) return;
        List<Candidate> eligible = new ArrayList<>();
        if (rule.candidates != null) for (Candidate c : rule.candidates) if (c.playable() && (preview || c.participate) && !failedCaches.contains(c.cachePath)) eligible.add(c);
        if (eligible.isEmpty()) return;
        if (!preview && rule.retriggerMode == Config.RetriggerMode.REPLACE) stop(i -> !i.scene.equals("preview") && i.rule.id.equals(rule.id));
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
            music.setVolume(chosen.volume);
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
        Iterator<Instance> iterator = instances.iterator();
        while (iterator.hasNext()) {
            Instance instance = iterator.next();
            if (instance.paused) continue;
            try {
                boolean ended = !instance.music.isPlaying() || instance.music.getPosition() >= instance.audio.end;
                if (ended && canRepeat(instance)) {
                    instance.plays++;
                    instance.music.play();
                    instance.music.setPosition((float) instance.audio.start);
                } else if (ended) {
                    dispose(instance);
                    iterator.remove();
                }
            } catch (RuntimeException ex) {
                fail(instance.audio, instance.rule.name, ex);
                dispose(instance);
                iterator.remove();
            }
        }
        reconcilePauses();
    }

    @Override public void stopScene(String scene) { stop(instance -> instance.scene.equals(scene)); reconcilePauses(); }
    @Override public void stopRule(String ruleId) { stop(instance -> !instance.scene.equals("preview") && instance.rule.id.equals(ruleId)); reconcilePauses(); }
    @Override public void sceneChanged(String oldScene, String newScene) {
        Iterator<Instance> iterator = instances.iterator();
        while (iterator.hasNext()) {
            Instance instance = iterator.next();
            if (!instance.scene.equals(oldScene)) continue;
            switch (instance.rule.roomBehavior) {
                case CONTINUE_ACROSS_ROOMS:
                case NEVER_STOP:
                    instance.scene = newScene;
                    break;
                default:
                    dispose(instance); iterator.remove();
            }
        }
        reconcilePauses();
    }
    @Override public void roomEnded(String scene) {
        stop(instance -> instance.scene.equals(scene) && instance.rule.roomBehavior != Config.RoomBehavior.CONTINUE_ACROSS_ROOMS
                && instance.rule.roomBehavior != Config.RoomBehavior.NEVER_STOP);
        reconcilePauses();
    }
    @Override public void stopAll() { stop(instance -> true); }
    @Override public void menu() { stop(instance -> instance.rule.roomBehavior != Config.RoomBehavior.NEVER_STOP); reconcilePauses(); }
    public void stopPreview() { stopScene("preview"); }

    private void stop(java.util.function.Predicate<Instance> predicate) {
        Iterator<Instance> iterator = instances.iterator();
        while (iterator.hasNext()) {
            Instance instance = iterator.next();
            if (predicate.test(instance)) { dispose(instance); iterator.remove(); }
        }
    }

    private boolean canRepeat(Instance instance) {
        Config.PlaybackMode mode = instance.rule.playbackMode;
        if (mode == null) mode = instance.rule.loop ? Config.PlaybackMode.INFINITE : Config.PlaybackMode.ONCE;
        if (mode == Config.PlaybackMode.INFINITE) return true;
        return mode == Config.PlaybackMode.LIMITED && instance.plays < instance.rule.loopCount;
    }
    private void dispose(Instance instance) {
        try { instance.music.dispose(); } finally { background.finish(instance.backgroundToken); }
    }
    private void reconcilePauses() {
        boolean removed;
        do {
            removed = false;
            Set<String> active = new HashSet<>();
            for (Instance instance : instances) if (!instance.scene.equals("preview")) active.add(instance.rule.id);
            Iterator<Instance> iterator = instances.iterator();
            while (iterator.hasNext()) {
                Instance instance = iterator.next();
                if (instance.scene.equals("preview")) continue;
                boolean shouldPause = instance.rule.pauseWhenRuleIds.stream().anyMatch(active::contains);
                try {
                    if (shouldPause && !instance.paused && instance.music.isPlaying() && instance.music.getPosition() < instance.audio.end) {
                        instance.music.pause(); instance.paused = true; background.paused(instance.backgroundToken,true);
                    } else if (!shouldPause && instance.paused) {
                        instance.music.play();
                        if (!instance.music.isPlaying() && OpenALSources.grow()) instance.music.play();
                        if (!instance.music.isPlaying()) throw new IllegalStateException("音频设备无法恢复暂停的播放");
                        instance.paused = false;
                        background.paused(instance.backgroundToken,false);
                    }
                } catch (RuntimeException ex) {
                    fail(instance.audio, instance.rule.name, ex); dispose(instance); iterator.remove(); removed = true;
                }
            }
        } while (removed);
    }
    public Set<String> pausedRuleIds() {
        Set<String> paused = new HashSet<>();
        for (Instance instance : instances) if (instance.paused) paused.add(instance.rule.id);
        return paused;
    }
    @Override public boolean isRuleActive(String ruleId) {
        for (Instance instance : instances) {
            if (instance.scene.equals("preview") || !instance.rule.id.equals(ruleId)) continue;
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
