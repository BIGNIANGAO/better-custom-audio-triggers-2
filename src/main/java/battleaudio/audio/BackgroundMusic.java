package battleaudio.audio;

import com.badlogic.gdx.audio.Music;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class BackgroundMusic implements Music {
    private static final Set<BackgroundMusic> tracks=Collections.newSetFromMap(new WeakHashMap<BackgroundMusic,Boolean>());
    private static final Set<Request> requests=Collections.newSetFromMap(new WeakHashMap<Request,Boolean>());
    private static final BackgroundDucking duck=new BackgroundDucking(1);
    private static float ratio=1, episodeBaseline;
    private static final class Request {
        final Map<BackgroundMusic,Float> triggeredVolumes=new WeakHashMap<>();
    }
    private final Music delegate;
    private float nominal, original;
    private boolean captured;
    private BackgroundMusic(Music delegate) {
        this.delegate=delegate; nominal=delegate.getVolume(); tracks.add(this);
        if(duck.hasSession()) { original=episodeBaseline>0?episodeBaseline:nominal; captured=true; apply(); }
    }
    public static void wrap(Object track) {
        try {
            Field field=track.getClass().getDeclaredField("music"); field.setAccessible(true);
            Music music=(Music)field.get(track);
            if(music!=null && !(music instanceof BackgroundMusic)) field.set(track,new BackgroundMusic(music));
        } catch(ReflectiveOperationException ex) { throw new IllegalStateException("无法接入背景音乐",ex); }
    }
    public static PlaybackManager.BackgroundControl control() {
        return new PlaybackManager.BackgroundControl() {
            public Object start(float value) { return begin(value); }
            public void paused(Object token,boolean paused) { if(token instanceof Request) { duck.pause(token,paused); applyTracks(); } }
            public void finish(Object token) { if(token instanceof Request) { requests.remove(token); duck.finish(token); applyTracks(); } }
        };
    }
    private static Request begin(float value) {
        Request request=new Request(); boolean first=!duck.hasSession();
        if(first) episodeBaseline=0;
        for(BackgroundMusic track:new ArrayList<>(tracks)) {
            try {
                float actual=track.delegate.getVolume(); request.triggeredVolumes.put(track,actual);
                if(first) {
                    track.original=actual; track.captured=true;
                    if(track.delegate.isPlaying()) episodeBaseline=Math.max(episodeBaseline,actual);
                }
            } catch(RuntimeException ex) { tracks.remove(track); }
        }
        requests.add(request); duck.start(request,value); applyTracks(); return request;
    }
    private float effective() { return captured?Math.max(0,Math.min(nominal,original*duck.factor())):nominal*ratio; }
    private void apply() { delegate.setVolume(effective()); }
    private static void applyTracks() {
        for(BackgroundMusic track:new ArrayList<>(tracks)) {
            try { track.apply(); } catch(RuntimeException ex) { tracks.remove(track); }
        }
    }
    public static void update(float seconds) {
        if(!duck.hasSession()) return;
        boolean finished=duck.update(seconds); applyTracks();
        if(finished) for(BackgroundMusic track:tracks) track.captured=false;
    }
    public static float factor() { return duck.factor(); }
    public static boolean recovering() { return duck.recovering(); }
    public static void ratio(float value) {
        if(!Float.isFinite(value) || value<0 || value>1) throw new IllegalArgumentException("背景音乐比例无效");
        duck.reset(); requests.clear(); ratio=value; episodeBaseline=0;
        for(BackgroundMusic track:tracks) track.captured=false;
        applyTracks();
    }
    @Override public void setVolume(float volume) { nominal=volume; apply(); }
    @Override public float getVolume() { return nominal; }
    @Override public void setPan(float pan,float volume) { nominal=volume; delegate.setPan(pan,effective()); }
    @Override public void play() {
        if(captured && original==0 && episodeBaseline>0 && !delegate.isPlaying()) { original=episodeBaseline; apply(); }
        delegate.play();
    }
    @Override public void pause() { delegate.pause(); }
    @Override public void stop() { delegate.stop(); }
    @Override public boolean isPlaying() { return delegate.isPlaying(); }
    @Override public void setLooping(boolean looping) { delegate.setLooping(looping); }
    @Override public boolean isLooping() { return delegate.isLooping(); }
    @Override public void setPosition(float position) { delegate.setPosition(position); }
    @Override public float getPosition() { return delegate.getPosition(); }
    @Override public void dispose() { tracks.remove(this); delegate.dispose(); }
    @Override public void setOnCompletionListener(OnCompletionListener listener) {
        delegate.setOnCompletionListener(listener==null?null:music->listener.onCompletion(this));
    }
}
