package battleaudio;

import battleaudio.model.Config;
import battleaudio.model.Config.*;
import battleaudio.model.RuleCopies;
import battleaudio.core.RuleEngine;
import battleaudio.audio.PlaybackManager;
import battleaudio.storage.AudioLibrary;
import com.badlogic.gdx.audio.Music;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CandidateTests {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final class Track implements Music {
        boolean playing, disposed; float gain, position; int plays; String cache;
        Track(String cache) { this.cache=cache; }
        public void play() { playing=true; plays++; } public void pause() { playing=false; } public void stop() { playing=false; }
        public boolean isPlaying() { return playing; } public void setLooping(boolean value) { } public boolean isLooping() { return false; }
        public void setVolume(float value) { gain=value; } public float getVolume() { return gain; } public void setPan(float pan,float volume) { gain=volume; }
        public void setPosition(float value) { position=value; } public float getPosition() { return position; }
        public void dispose() { disposed=true; playing=false; } public void setOnCompletionListener(OnCompletionListener listener) { }
    }
    private static Candidate audio(String name,float volume) {
        Candidate c=new Candidate(); c.name=name; c.cachePath="cache/"+name+".wav"; c.duration=10; c.start=2; c.end=5; c.volume=volume; return c;
    }
    public static void run() throws Exception {
        Rule rule=new Rule(); rule.candidates.add(audio("A", .2f)); rule.candidates.add(audio("B", .7f));
        check(rule.triggers.isEmpty() && rule.randomMode==RandomMode.AVOID_REPEAT && rule.retriggerMode==RetriggerMode.REPLACE
                && rule.playbackMode==PlaybackMode.ONCE && new Candidate().volume==.3f, "New rule/candidate defaults");
        List<Track> tracks=new ArrayList<>();
        PlaybackManager manager=new PlaybackManager(cache -> { Track t=new Track(cache); tracks.add(t); return t; }, error -> { throw new AssertionError(error); }, new Random(71));
        String previous="";
        for(int i=0;i<40;i++) {
            manager.start(rule,"battle"); Track t=tracks.get(tracks.size()-1);
            check(!t.cache.equals(previous) && manager.count()==1, "Avoid-repeat and replace scope"); previous=t.cache;
            check(t.position==2 && t.gain==(t.cache.contains("A")?.2f:.7f), "Chosen segment and independent volume");
        }
        Rule other=RuleCopies.copy(rule,Collections.singletonList(rule)); manager.start(other,"battle");
        rule.retriggerMode=RetriggerMode.IGNORE; int opened=tracks.size(); manager.start(rule,"battle");
        check(manager.count()==2 && tracks.size()==opened,"Ignore only while this rule playing, other rules unaffected");
        rule.retriggerMode=RetriggerMode.REPLACE; manager.start(rule,"battle"); check(manager.count()==2,"Replace leaves other rule playing");
        rule.retriggerMode=RetriggerMode.OVERLAP; manager.start(rule,"battle"); check(manager.count()==3,"Overlap retains existing rule instances");
        manager.start(rule,"preview"); Track activePreview=tracks.get(tracks.size()-1);
        manager.stopRule(rule.id);
        check(manager.count()==2 && !activePreview.disposed,"Rule stop removes overlaps and preserves other rule and preview");
        manager.stopAll();
        rule.retriggerMode=RetriggerMode.REPLACE; rule.candidates.get(1).participate=false;
        for(int i=0;i<4;i++) manager.start(rule,"battle");
        check(tracks.get(tracks.size()-1).cache.contains("A") && manager.count()==1,"One participating candidate works");
        rule.audio().participate=false; opened=tracks.size(); manager.start(rule,"battle"); check(tracks.size()==opened,"No participating candidates do not open audio");
        manager.stopAll(); Rule preview=new Rule(); preview.candidates.add(rule.candidates.get(1)); preview.playbackMode=PlaybackMode.INFINITE;
        manager.start(preview,"preview"); Track t=tracks.get(tracks.size()-1); check(t.cache.contains("B") && t.gain==.7f,"Preview chosen excluded candidate");
        t.position=5; manager.tick(); check(t.disposed && t.plays==1,"Preview plays segment once");
        rule.audio().participate=true; rule.playbackMode=PlaybackMode.LIMITED; rule.loopCount=3;
        rule.roomBehavior=RoomBehavior.CONTINUE_ACROSS_ROOMS; manager.start(rule,"one"); t=tracks.get(tracks.size()-1); opened=tracks.size();
        t.position=3; manager.roomEnded("one"); manager.sceneChanged("one","two");
        check(t.position==3 && !t.disposed && tracks.size()==opened,"Carry keeps candidate, handle and progress");
        for(int i=0;i<3;i++) { t.position=5; manager.tick(); }
        check(t.plays==3 && t.disposed && tracks.size()==opened,"Loop repeats same candidate without redraw");
        rule.candidates.get(1).participate=true; rule.randomMode=RandomMode.RANDOM; boolean repeats=false; previous="";
        for(int i=0;i<40;i++) { manager.start(rule,"two"); String cache=tracks.get(tracks.size()-1).cache; repeats|=cache.equals(previous); previous=cache; }
        check(repeats,"Complete random permits consecutive repetition"); manager.stopAll();
        Config carryConfig=new Config(); Rule carryRule=new Rule(); carryRule.candidates.add(audio("nextA",.1f)); carryRule.candidates.add(audio("nextB",.2f));
        carryRule.roomBehavior=RoomBehavior.STOP_AND_RESTART_NEXT_BATTLE; carryRule.triggers=EnumSet.of(Trigger.HP_LOSS); carryConfig.rules.add(carryRule);
        RuleEngine carryEngine=new RuleEngine(carryConfig,manager); carryEngine.beginRun(false,80,100); carryEngine.battleStart("first",false,false,80,100);
        carryEngine.health(79,100,false); Track carried=tracks.get(tracks.size()-1); carryEngine.battleEnd();
        check(carried.disposed,"Restart behavior stops old candidate at room end"); carryEngine.battleStart("second",false,false,80,100);
        check(manager.count()==1 && !tracks.get(tracks.size()-1).cache.equals(carried.cache),"Next battle restart redraws candidate with avoid-repeat");
        carryEngine.stop();
        for(float volume:new float[]{.1f,0f,.6f}) { rule.audio().volume=volume; rule.candidates.get(1).participate=false; manager.start(rule,"two"); check(tracks.get(tracks.size()-1).gain==volume,"Lower, zero and restored independent volume"); }
        manager.stopAll();
        Candidate invalid=audio("bad",.3f); invalid.error="bad"; rule.candidates.add(invalid); rule.candidates.get(1).participate=true;
        for(int i=0;i<20;i++) { manager.start(rule,"two"); check(!tracks.get(tracks.size()-1).cache.contains("bad"),"Unplayable candidates excluded"); } manager.stopAll();
        List<String> errors=new ArrayList<>(); List<Track> recovered=new ArrayList<>();
        PlaybackManager fallback=new PlaybackManager(cache -> { if(cache.contains("bad")) throw new IllegalStateException("device"); Track good=new Track(cache); recovered.add(good); return good; }, errors::add, new Random(1));
        Rule faulty=new Rule(); invalid.error=null; faulty.candidates.add(invalid); faulty.candidates.add(audio("good",.4f)); faulty.randomMode=RandomMode.RANDOM;
        for(int i=0;i<10;i++) fallback.start(faulty,"battle");
        check(errors.size()==1 && fallback.count()==1 && !recovered.isEmpty(),"Failed cache quarantined with playable fallback");
        Rule copy=RuleCopies.copy(rule,Arrays.asList(rule,other)); Rule copy2=RuleCopies.copy(rule,Arrays.asList(rule,other,copy));
        check(!copy.id.equals(rule.id) && !copy.name.equals(copy2.name) && !copy.audio().id.equals(rule.audio().id),"Copies get distinct rule and candidate identity/name");
        copy.audio().volume=.9f; check(rule.audio().volume!=copy.audio().volume,"Copy settings detached");
        rule.filter(Trigger.HP_LOSS).minAmount=3;
        rule.filter(Trigger.HP_LOSS).sourceType="ENEMY";
        Config persistedFilters=new Config(); persistedFilters.rules.add(rule);
        Config loadedFilters=Config.fromJson(new JsonParser().parse(new Gson().toJson(persistedFilters)).getAsJsonObject());
        check(loadedFilters.rules.get(0).filter(Trigger.HP_LOSS).minAmount==3
                && "ENEMY".equals(loadedFilters.rules.get(0).filter(Trigger.HP_LOSS).sourceType),"Event filters survive JSON reload");
        JsonObject legacyFilterJson=new JsonParser().parse(new Gson().toJson(persistedFilters)).getAsJsonObject();
        JsonObject legacyHp=legacyFilterJson.getAsJsonArray("rules").get(0).getAsJsonObject()
                .getAsJsonObject("filters").getAsJsonObject("HP_LOSS");
        legacyHp.remove("minAmountEnabled"); legacyHp.remove("minTurnTotalEnabled"); legacyHp.remove("minCurrentValueEnabled");
        Config inferredFilters=Config.fromJson(legacyFilterJson);
        check(inferredFilters.rules.get(0).filter(Trigger.HP_LOSS).minAmountEnabled,
                "Legacy nonzero amount filters become enabled in the explicit UI model");
        Rule copiedFilters=RuleCopies.copy(loadedFilters.rules.get(0),loadedFilters.rules);
        check(copiedFilters.filter(Trigger.HP_LOSS).minAmount==3,"Rule copy retains event filters");
        copiedFilters.filter(Trigger.HP_LOSS).minAmount=7;
        check(loadedFilters.rules.get(0).filter(Trigger.HP_LOSS).minAmount==3,"Copied event filters edit independently");
        Candidate candidateCopy=RuleCopies.copy(rule.audio(),rule.candidates); check(!candidateCopy.id.equals(rule.audio().id),"Candidate copy distinct identity");
        Config config=new Config(); rule.triggers=EnumSet.of(Trigger.DAMAGE_THRESHOLD); rule.parameter(Trigger.DAMAGE_THRESHOLD).value=10; config.rules.add(rule);
        final int[] starts={0}; RuleEngine engine=new RuleEngine(config,new RuleEngine.Playback() {
            public void start(Rule r,String scene) { starts[0]++; } public void stopScene(String scene) { } public void stopAll() { }
        }); engine.scene("battle");
        engine.damage(5); engine.damage(5); engine.damage(5); check(starts[0]==0,"Multi-hit not accumulated");
        engine.damage(10); engine.damage(10); engine.damage(20); engine.damage(0); check(starts[0]==3,"Per-hit threshold inclusive and no rising edge suppression");
        Config cumulativeConfig=new Config(); Rule cumulative=new Rule();
        cumulative.triggers=EnumSet.of(Trigger.HP_LOSS); cumulative.candidates.add(audio("sum",.2f));
        cumulative.filter(Trigger.HP_LOSS).minAmount=3;
        cumulative.filter(Trigger.HP_LOSS).minTurnTotal=5;
        cumulativeConfig.rules.add(cumulative);
        RuleEngine cumulativeEngine=new RuleEngine(cumulativeConfig,new RuleEngine.Playback(){
            public void start(Rule r,String scene){ starts[0]++; }
            public void stopScene(String scene){} public void stopAll(){}
        });
        cumulativeEngine.scene("sum"); cumulativeEngine.turnStart();
        int beforeSum=starts[0];
        cumulativeEngine.event(Trigger.HP_LOSS,null,new battleaudio.model.EventContext().amount(2,20,18));
        check(starts[0]==beforeSum,"Below per-operation minimum does not trigger");
        cumulativeEngine.event(Trigger.HP_LOSS,null,new battleaudio.model.EventContext().amount(3,18,15));
        check(starts[0]==beforeSum+1,"Below-minimum operation still contributes to turn total");
        cumulativeEngine.event(Trigger.HP_LOSS,null,new battleaudio.model.EventContext().amount(3,15,12));
        check(starts[0]==beforeSum+2,"Every qualifying operation after turn total threshold can trigger");
        cumulative.filter(Trigger.HP_LOSS).cadence=Cadence.FIRST_TURN;
        cumulativeEngine.turnStart(); beforeSum=starts[0];
        cumulativeEngine.event(Trigger.HP_LOSS,null,new battleaudio.model.EventContext().amount(5,20,15));
        cumulativeEngine.event(Trigger.HP_LOSS,null,new battleaudio.model.EventContext().amount(3,15,12));
        check(starts[0]==beforeSum+1,"First-turn cadence limits qualifying cumulative events");
        migration();
        emptyRules();
        PassiveVolumeRepro.main(new String[0]);
        System.out.println("PASS: " + checks + " candidate, random, retrigger, gain, damage, copy and migration checks");
    }
    private static void emptyRules() throws Exception {
        Path root=Files.createTempDirectory("battleaudio-empty-"); AudioLibrary library=new AudioLibrary(root,null);
        Config config=new Config(); Rule empty=new Rule(); empty.triggers=EnumSet.of(Trigger.HP_LOSS);
        empty.roomBehavior=RoomBehavior.STOP_AND_RESTART_NEXT_BATTLE; config.rules.add(empty);
        library.save(config); Config reloaded=library.load();
        check(reloaded.rules.size()==1 && reloaded.rules.get(0).candidates.isEmpty(),"Empty rule saves/reloads without placeholder");
        check(!empty.playable() && !reloaded.rules.get(0).playable(),"Empty rule unplayable");
        Rule copy=RuleCopies.copy(empty,config.rules); check(copy.candidates.isEmpty(),"Copy empty rule stays empty");
        final int[] starts={0}; RuleEngine engine=new RuleEngine(config,new RuleEngine.Playback() {
            public void start(Rule rule,String scene) { starts[0]++; }
            public void stopScene(String scene) { } public void stopAll() { }
        });
        engine.beginRun(false,80,100); engine.battleStart("first",false,false,80,100); engine.health(79,100,false);
        engine.battleEnd(); empty.candidates.add(audio("later",.3f)); engine.battleStart("second",false,false,79,100);
        check(starts[0]==0,"Empty triggered rule does not play or queue next-battle restart");
        engine.health(78,100,false); check(starts[0]==1,"First real candidate plays on later event");
        empty.candidates.clear(); library.save(config); library.collect(config,Collections.emptySet());
        check(library.load().rules.get(0).candidates.isEmpty(),"Deleting last candidate retains empty saved rule");
        PlaybackManager manager=new PlaybackManager(cache -> { throw new AssertionError("Empty rule opened audio"); },error -> { throw new AssertionError(error); });
        manager.start(empty,"battle"); manager.start(empty,"preview");
        check(manager.count()==0 && empty.candidates.isEmpty(),"Empty auto/preview does not create handles or candidates");
    }
    private static void migration() throws Exception {
        Path root=Files.createTempDirectory("battleaudio-migration-"); AudioLibrary library=new AudioLibrary(root,null);
        Files.write(root.resolve("cache/old.wav"),new byte[]{1}); Files.write(root.resolve("library/old.source"),new byte[]{1});
        String old="{\"id\":\"one\",\"name\":\"同名\",\"sourcePath\":\"library/old.source\",\"cachePath\":\"cache/old.wav\",\"duration\":10,\"start\":2,\"end\":7,\"volume\":0.17,\"loop\":false,\"nextBattle\":\"IMMEDIATE\",\"triggers\":[\"ORB_PASSIVE\"],\"playbackMode\":\"LIMITED\",\"loopCount\":4,\"roomBehavior\":\"CONTINUE_ACROSS_ROOMS\"}";
        for(int version:new int[]{1,2}) {
            String json="{\"schemaVersion\":"+version+",\"backgroundRatio\":0.4,\"rules\":["+old+","+old.replace("\"one\"","\"two\"")+"]}";
            Files.write(root.resolve("settings.json"),json.getBytes(StandardCharsets.UTF_8)); Config migrated=library.load();
            check(migrated.schemaVersion==4 && migrated.rules.size()==2 && migrated.rules.get(0).candidates.size()==1,"Legacy rules migrate separately without merging");
            Rule r=migrated.rules.get(0); check(r.audio().volume==.17f && r.audio().start==2 && r.audio().end==7 && r.triggers.contains(Trigger.ORB_PASSIVE)
                    && r.retriggerMode==RetriggerMode.OVERLAP && migrated.backgroundRatio==.4f,"Legacy values and overlapping behavior preserved");
            check(version==1 ? r.playbackMode==PlaybackMode.ONCE && r.roomBehavior==RoomBehavior.STOP_AND_RESTART_NEXT_BATTLE
                    : r.playbackMode==PlaybackMode.LIMITED && r.loopCount==4 && r.roomBehavior==RoomBehavior.CONTINUE_ACROSS_ROOMS,"Version-specific playback migration");
            library.save(migrated);
            try (java.util.stream.Stream<Path> backups = Files.list(root)) {
                boolean found = backups.filter(path -> path.getFileName().toString().startsWith("settings.pre-pack-migration-")
                        && path.getFileName().toString().endsWith(".json"))
                        .anyMatch(path -> { try { return new String(Files.readAllBytes(path),StandardCharsets.UTF_8).equals(json); } catch (Exception ex) { return false; } });
                check(found,"Exact pre-migration config retained as a recovery copy");
            }
            JsonObject saved=new JsonParser().parse(new String(Files.readAllBytes(root.resolve("settings.json")),StandardCharsets.UTF_8)).getAsJsonObject();
            check(!saved.getAsJsonArray("rules").get(0).getAsJsonObject().has("cachePath"),"Schema4 no redundant flat audio settings");
            check(new Gson().toJson(migrated).equals(new Gson().toJson(library.load())),"Save/reload preserves full migrated config and identities");
            Candidate second=audio("extra",.3f); Files.write(root.resolve(second.cachePath),new byte[]{1}); r.candidates.add(second);
            library.collect(migrated,Collections.emptySet()); check(Files.exists(root.resolve(second.cachePath)) && Files.exists(root.resolve(r.audio().cachePath)),"Collection keeps every candidate cache");
        }
    }
}
