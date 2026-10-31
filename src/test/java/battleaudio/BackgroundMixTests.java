package battleaudio;

import battleaudio.model.Config;
import com.google.gson.*;
import battleaudio.audio.BackgroundDucking;
import battleaudio.audio.BackgroundMusic;
import battleaudio.audio.PlaybackManager;
import battleaudio.model.Config.*;
import com.badlogic.gdx.audio.Music;
import java.util.*;

public final class BackgroundMixTests {
    private static int checks;
    private static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
    public static void main(String[] args) { run(); }
    public static void run() {
        String legacy="{\"schemaVersion\":3,\"backgroundRatio\":0.4,\"rules\":[{\"id\":\"legacy\",\"candidates\":[{\"id\":\"one\",\"name\":\"one\"},{\"id\":\"two\",\"name\":\"two\"}]}]}";
        Config config=Config.fromJson(new JsonParser().parse(legacy).getAsJsonObject());
        JsonObject candidate=new Gson().toJsonTree(config.rules.get(0).candidates.get(0)).getAsJsonObject();
        check(candidate.has("backgroundRatio") && candidate.get("backgroundRatio").getAsFloat()==.4f,
                "Legacy global BGM ratio migrates into every candidate");
        overlapAndRecovery();
        actualSnapshotAndCeiling();
        playbackMixAndInterruptedRecovery();
        pausePreviewAndNaturalEnd();
        migratedValuesAndValidation();
        recoveryBoundariesAndFailures();
        System.out.println("PASS: "+checks+" background mix checks");
    }
    private static void overlapAndRecovery() {
        BackgroundDucking mix=new BackgroundDucking(1); Object first=new Object(), second=new Object();
        mix.start(first,.5f); check(mix.factor()==.5f,"First candidate immediately reduces BGM to its ratio");
        mix.start(second,.2f); check(mix.factor()==.2f,"Overlapping candidates use lowest ratio rather than multiplying");
        mix.finish(second); check(mix.factor()==.2f && mix.recovering(),"Ending stronger candidate does not immediately restore");
        mix.update(.5f); check(Math.abs(mix.factor()-.35f)<.0001f,"Intermediate recovery respects remaining candidate target");
        mix.update(.5f); check(mix.factor()==.5f && mix.hasSession(),"Remaining candidate keeps its configured retention");
        mix.finish(first); mix.update(.5f); check(Math.abs(mix.factor()-.75f)<.0001f,"Final recovery is gradual");
        check(mix.update(.5f) && mix.factor()==1 && !mix.hasSession(),"All audio ending restores original without overshoot");
    }
    private static void near(float actual,float expected,String message) { check(Math.abs(actual-expected)<.0002f,message+": "+actual); }
    private static final class Track implements Music {
        float volume,position; boolean playing,disposed;
        Track(float volume) { this.volume=volume; }
        public void play(){playing=true;} public void pause(){playing=false;} public void stop(){playing=false;}
        public boolean isPlaying(){return playing;} public void setLooping(boolean value){} public boolean isLooping(){return false;}
        public void setVolume(float value){volume=value;} public float getVolume(){return volume;} public void setPan(float pan,float value){volume=value;}
        public void setPosition(float value){position=value;} public float getPosition(){return position;}
        public void dispose(){disposed=true;playing=false;} public void setOnCompletionListener(OnCompletionListener listener){}
    }
    private static final class Owner { private Music music; Owner(Music music){this.music=music;} }
    private static final class Fixture implements AutoCloseable {
        final Track original=new Track(.8f); final Owner owner=new Owner(original); final List<Track> voices=new ArrayList<>();
        final PlaybackManager manager;
        Fixture(){
            BackgroundMusic.ratio(1); original.play(); BackgroundMusic.wrap(owner);
            manager=new PlaybackManager(cache->{Track track=new Track(0);voices.add(track);return track;},error->{throw new AssertionError(error);},new Random(7),BackgroundMusic.control());
        }
        public void close(){manager.stopAll();owner.music.dispose();BackgroundMusic.ratio(1);}
    }
    private static Rule audio(String name,float ratio){
        Rule rule=new Rule();rule.name=name;rule.audio().cachePath="cache/"+name+".wav";rule.audio().duration=20;rule.audio().end=18;rule.audio().backgroundRatio=ratio;return rule;
    }
    private static void actualSnapshotAndCeiling(){
        try(Fixture fixture=new Fixture()){
            BackgroundMusic.ratio(.5f); near(fixture.original.volume,.4f,"Actual BGM is already below nominal before trigger");
            Rule rule=audio("snapshot",.2f);fixture.manager.start(rule,"room");
            near(fixture.original.volume,.08f,"Capture actual BGM volume rather than nominal setting");
            near(fixture.owner.music.getVolume(),.8f,"Game can still read nominal volume");
            fixture.owner.music.setVolume(.9f);near(fixture.original.volume,.08f,"Game fade cannot raise ducked BGM beyond captured target");
            fixture.manager.stopAll();near(fixture.original.volume,.08f,"Stop does not restore instantly");
            BackgroundMusic.update(.5f);near(fixture.original.volume,.24f,"Restore interpolates toward captured actual volume");
            BackgroundMusic.update(100);near(fixture.original.volume,.4f,"Large frame delta never exceeds captured volume");
            BackgroundMusic.update(1);near(fixture.original.volume,.4f,"Idle updates do not overshoot restored volume");
        }
    }
    private static void playbackMixAndInterruptedRecovery(){
        try(Fixture fixture=new Fixture()){
            Rule first=audio("half",.5f),second=audio("low",.2f);
            fixture.manager.start(first,"room");near(fixture.original.volume,.4f,"Candidate one owns its background ratio");
            fixture.manager.start(second,"room");near(fixture.original.volume,.16f,"Second candidate uses common original and minimum ratio");
            fixture.manager.stopRule(second.id);near(fixture.original.volume,.16f,"Ending lower ratio starts bounded recovery");
            BackgroundMusic.update(.5f);near(fixture.original.volume,.28f,"Recovery with other audio remains below remaining target");
            BackgroundMusic.update(.5f);near(fixture.original.volume,.4f,"Remaining audio preserves its lower target");
            fixture.manager.stopRule(first.id);BackgroundMusic.update(.5f);near(fixture.original.volume,.6f,"All ending restores original gradually");
            Rule interruption=audio("interruption",.1f);fixture.manager.start(interruption,"room");
            near(fixture.original.volume,.08f,"New trigger during recovery retains first original baseline");
            fixture.manager.stopAll();BackgroundMusic.update(1);near(fixture.original.volume,.8f,"Interrupted recovery still returns to first original");
            for(int index=0;index<6;index++)fixture.manager.start(second,"room");
            near(fixture.original.volume,.16f,"Repeated replacement does not compound attenuation");
            fixture.manager.stopAll();BackgroundMusic.update(1);near(fixture.original.volume,.8f,"Repeated replacement restores original correctly");
        }
    }
    private static void pausePreviewAndNaturalEnd(){
        try(Fixture fixture=new Fixture()){
            Rule strong=audio("strong",.1f),weak=audio("weak",.7f);strong.pauseWhenRuleIds.add(weak.id);
            fixture.manager.start(strong,"room");fixture.manager.start(weak,"preview");
            near(fixture.original.volume,.08f,"Preview participates but cannot override stronger automatic requirement");
            fixture.manager.stopPreview();fixture.manager.start(weak,"room");
            check(fixture.manager.pausedRuleIds().contains(strong.id),"Strong audio is paused by selected weak rule");
            BackgroundMusic.update(1);near(fixture.original.volume,.56f,"Paused audio temporarily releases attenuation requirement");
            fixture.voices.get(fixture.voices.size()-1).position=18;fixture.manager.tick();
            near(fixture.original.volume,.08f,"Resuming strong audio reacquires its lower ratio");
            fixture.voices.get(0).position=18;fixture.manager.tick();
            check(fixture.manager.count()==0,"Natural endings release all candidate requirements");
            near(fixture.original.volume,.08f,"Natural ending also fades instead of jumping");
            BackgroundMusic.update(1);near(fixture.original.volume,.8f,"Natural ending finishes recovery");
        }
        try(Fixture fixture=new Fixture()){
            Rule cue=audio("new-track",.2f);fixture.manager.start(cue,"room");
            Track replacement=new Track(0);Owner replacementOwner=new Owner(replacement);BackgroundMusic.wrap(replacementOwner);replacementOwner.music.play();replacementOwner.music.setVolume(.6f);
            near(replacement.volume,.16f,"New game BGM during active audio inherits episode attenuation baseline");
            fixture.manager.stopAll();BackgroundMusic.update(1);near(replacement.volume,.6f,"New game track recovery respects game volume cap");replacementOwner.music.dispose();
        }
    }
    private static void migratedValuesAndValidation(){
        Config config=Config.fromJson(new JsonParser().parse("{\"schemaVersion\":3,\"backgroundRatio\":0.4,\"rules\":[{\"id\":\"one\",\"candidates\":[{\"id\":\"first\",\"name\":\"first\"},{\"id\":\"second\",\"name\":\"second\",\"backgroundRatio\":0.7}]}]}").getAsJsonObject());
        near(config.rules.get(0).candidates.get(0).backgroundRatio,.4f,"Missing ratio inherits old global value");
        near(config.rules.get(0).candidates.get(1).backgroundRatio,.7f,"Existing per-candidate ratio is not overwritten");
        near(new Candidate().backgroundRatio,.2f,"New candidates default to twenty percent retention");
        for(float invalid:new float[]{-1,1.1f,Float.NaN}){
            config.rules.get(0).audio().backgroundRatio=invalid;boolean rejected=false;
            try{config.validate();}catch(IllegalArgumentException ex){rejected=true;}check(rejected,"Invalid candidate background ratio is rejected");
        }
    }
    private static void recoveryBoundariesAndFailures(){
        BackgroundDucking mix=new BackgroundDucking(1);Object muted=new Object();mix.start(muted,0);
        check(mix.factor()==0,"Zero ratio fully suppresses BGM");mix.finish(muted);
        for(float invalid:new float[]{-1,Float.NaN,Float.POSITIVE_INFINITY})mix.update(invalid);
        check(mix.factor()==0,"Invalid frame time cannot overshoot recovery");mix.update(100);check(mix.factor()==1,"Large recovery delta clamps to original");
        Object full=new Object();mix.start(full,1);check(mix.factor()==1,"Full retention does not lower BGM");mix.finish(full);check(mix.update(0),"Full retention session closes without extra attenuation");
        try(Fixture fixture=new Fixture()){
            Rule rule=audio("lower-setting",.2f);fixture.manager.start(rule,"room");fixture.owner.music.setVolume(.1f);
            fixture.manager.stopAll();BackgroundMusic.update(1);near(fixture.original.volume,.1f,"User lowering game volume caps later recovery");
        }
        try(Fixture fixture=new Fixture()){
            List<String> errors=new ArrayList<>();PlaybackManager failed=new PlaybackManager(cache->{throw new IllegalArgumentException("unplayable");},errors::add,new Random(1),BackgroundMusic.control());
            failed.start(audio("failed",0),"room");near(fixture.original.volume,.8f,"Failed candidate never lowers game BGM");check(errors.size()==1,"Failed candidate is reported once");
        }
        try(Fixture fixture=new Fixture()){
            Rule rule=audio("overlap",.5f);rule.retriggerMode=RetriggerMode.OVERLAP;fixture.manager.start(rule,"room");fixture.manager.start(rule,"room");
            fixture.voices.get(0).position=18;fixture.manager.tick();near(fixture.original.volume,.4f,"Ending one overlap keeps remaining requirement");
            fixture.voices.get(1).position=18;fixture.manager.tick();BackgroundMusic.update(1);near(fixture.original.volume,.8f,"Final overlap ending restores captured original");
        }
    }
}
