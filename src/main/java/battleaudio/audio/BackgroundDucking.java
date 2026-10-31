package battleaudio.audio;

import java.util.HashMap;
import java.util.Map;

public final class BackgroundDucking {
    private static final class Request {
        final float ratio; boolean paused;
        Request(float ratio) { this.ratio=ratio; }
    }
    private final Map<Object,Request> requests=new HashMap<>();
    private final float recoverySeconds;
    private float factor=1, target=1, recoveryStart=1, elapsed;
    private boolean session;
    public BackgroundDucking(float recoverySeconds) {
        if(!Float.isFinite(recoverySeconds) || recoverySeconds<=0) throw new IllegalArgumentException("恢复时间必须为正数");
        this.recoverySeconds=recoverySeconds;
    }
    public void start(Object token,float ratio) {
        if(token==null || !Float.isFinite(ratio) || ratio<0 || ratio>1) throw new IllegalArgumentException("背景音乐保留比例无效");
        session=true; requests.put(token,new Request(ratio)); recalculate();
    }
    public void finish(Object token) { if(requests.remove(token)!=null) recalculate(); }
    public void pause(Object token,boolean paused) {
        Request request=requests.get(token);
        if(request!=null && request.paused!=paused) { request.paused=paused; recalculate(); }
    }
    private void recalculate() {
        float next=1;
        for(Request request:requests.values()) if(!request.paused) next=Math.min(next,request.ratio);
        if(next==target) return;
        target=next;
        if(target<factor) factor=target;
        recoveryStart=factor; elapsed=0;
    }
    public boolean update(float seconds) {
        if(!session) return false;
        if(Float.isFinite(seconds) && seconds>0 && factor<target) {
            elapsed=Math.min(recoverySeconds,elapsed+seconds);
            factor=Math.min(target,recoveryStart+(target-recoveryStart)*(elapsed/recoverySeconds));
        }
        factor=Math.max(0,Math.min(1,factor));
        if(requests.isEmpty() && factor==1) { session=false; return true; }
        return false;
    }
    public float factor() { return factor; }
    public boolean hasSession() { return session; }
    public boolean recovering() { return session && factor<target; }
    public void reset() { requests.clear(); factor=target=recoveryStart=1; elapsed=0; session=false; }
}
