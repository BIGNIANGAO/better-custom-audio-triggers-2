package battleaudio;

import battleaudio.audio.PlaybackManager;
import battleaudio.core.RuleEngine;
import battleaudio.model.Config;
import battleaudio.model.Config.*;
import battleaudio.model.EventContext;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.badlogic.gdx.audio.Music;
import java.util.*;

public final class PrerequisiteTests {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) { run(); }
    private static Rule audio(String name) {
        Rule rule=new Rule(); rule.name=name; rule.audio().cachePath="cache/"+name+".wav";
        rule.audio().duration=20; rule.audio().end=18; return rule;
    }
    private static final class Track implements Music {
        boolean playing, disposed; float position, volume;
        public void play() { playing=true; } public void pause() { playing=false; } public void stop() { playing=false; }
        public boolean isPlaying() { return playing; } public void setLooping(boolean value) { } public boolean isLooping() { return false; }
        public void setVolume(float value) { volume=value; } public float getVolume() { return volume; } public void setPan(float pan,float value) { volume=value; }
        public void setPosition(float value) { position=value; } public float getPosition() { return position; }
        public void dispose() { disposed=true; playing=false; } public void setOnCompletionListener(OnCompletionListener listener) { }
    }
    private static final class Fixture {
        final List<Track> tracks=new ArrayList<>();
        final PlaybackManager manager=new PlaybackManager(cache->{Track track=new Track(); tracks.add(track); return track;},error->{throw new AssertionError(error);});
    }
    public static void run() {
        Fixture fixture=new Fixture(); Config config=new Config(); Rule bgm=audio("BGM"), hurt=audio("hurt");
        bgm.triggers.add(Trigger.TURN_START); hurt.triggers.add(Trigger.HP_LOSS); hurt.prerequisiteRuleIds.add(bgm.id);
        config.rules.add(bgm); config.rules.add(hurt); RuleEngine engine=new RuleEngine(config,fixture.manager);
        engine.beginRun(false,80,100); engine.battleStart("battle",false,false,80,100);
        engine.health(79,100,false);
        check(fixture.manager.count()==0,"Hurt audio cannot trigger before BGM starts");
        engine.turnStart(); engine.health(78,100,false);
        check(fixture.manager.count()==2,"Hurt audio triggers while BGM is active");
        fixture.manager.stopRule(bgm.id); int opened=fixture.tracks.size(); engine.health(77,100,false);
        check(fixture.tracks.size()==opened,"BGM stop closes the prerequisite window");
        check(fixture.tracks.get(opened-1).playing,"Prerequisite stopping does not cut already started hurt audio");
        invalidReferences();
        sameEventOrder();
        previewsFailureAndNaturalEnd();
        overlapAndMultiplePrerequisites();
        pausedPrerequisiteAndStopConditions();
        cadenceAndPersistence();
        healthAndRestartPaths();
        System.out.println("PASS: "+checks+" rule prerequisite checks");
    }
    private static void invalidReferences() {
        Config config=new Config(); Rule first=audio("first"), second=audio("second"); config.rules.add(first); config.rules.add(second);
        first.prerequisiteRuleIds.add("missing");
        boolean rejected=false; try { config.validate(); } catch(IllegalArgumentException ex) { rejected=true; }
        check(rejected,"Missing prerequisite cannot be saved");
        first.prerequisiteRuleIds.clear(); first.prerequisiteRuleIds.add(first.id);
        rejected=false; try { config.validate(); } catch(IllegalArgumentException ex) { rejected=true; }
        check(rejected,"Rule cannot select itself as prerequisite");
        first.prerequisiteRuleIds.clear(); first.prerequisiteRuleIds.add(second.id); second.prerequisiteRuleIds.add(first.id);
        rejected=false; try { config.validate(); } catch(IllegalArgumentException ex) { rejected=true; }
        check(rejected,"Prerequisite cycle with no starting rule cannot be saved");
        Rule alternative=audio("alternative"); config.rules.add(alternative); first.prerequisiteRuleIds.add(alternative.id);
        config.validate(); check(true,"OR prerequisite cycle with independent alternative remains usable");
    }
    private static void sameEventOrder() {
        Fixture fixture=new Fixture(); Config config=new Config(); Rule bgm=audio("same-BGM"), voice=audio("same-voice");
        bgm.triggers.add(Trigger.BATTLE_START); voice.triggers.add(Trigger.BATTLE_START); voice.prerequisiteRuleIds.add(bgm.id);
        config.rules.add(voice); config.rules.add(bgm); config.validate();
        RuleEngine engine=new RuleEngine(config,fixture.manager); engine.beginRun(false,80,100); engine.battleStart("same",false,false,80,100);
        check(fixture.manager.count()==2,"Same event starts prerequisite first regardless of rule list order");
        Fixture healthFixture=new Fixture(); Config healthConfig=new Config(); Rule healthBgm=audio("health-BGM"), healthVoice=audio("health-voice");
        healthBgm.triggers.add(Trigger.LOW_HEALTH); healthVoice.triggers.add(Trigger.LOW_HEALTH); healthVoice.prerequisiteRuleIds.add(healthBgm.id);
        healthBgm.roomBehavior=RoomBehavior.STOP_AND_RESTART_NEXT_BATTLE; healthVoice.roomBehavior=RoomBehavior.STOP_AND_RESTART_NEXT_BATTLE;
        healthConfig.rules.add(healthVoice); healthConfig.rules.add(healthBgm);
        RuleEngine healthEngine=new RuleEngine(healthConfig,healthFixture.manager); healthEngine.beginRun(false,80,100);
        healthEngine.battleStart("health-one",false,false,80,100); healthEngine.health(30,100,false);
        check(healthFixture.manager.count()==2,"Health threshold starts prerequisite first regardless of rule order");
        healthEngine.battleEnd(); healthEngine.battleStart("health-two",false,false,30,100);
        check(healthFixture.manager.count()==2,"Carried rules replay in prerequisite order");
        healthBgm.roomBehavior=RoomBehavior.STOP_AND_RESTART_AFTER_DAMAGE; healthVoice.roomBehavior=RoomBehavior.STOP_AND_RESTART_AFTER_DAMAGE;
        healthEngine.battleEnd(); healthEngine.battleStart("health-three",false,false,30,100); healthEngine.health(29,100,false);
        check(healthFixture.manager.count()==2,"First-damage replay respects prerequisite order");
    }
    private static void previewsFailureAndNaturalEnd() {
        Fixture fixture=new Fixture(); Config config=new Config(); Rule bgm=audio("window"), hurt=audio("window-hurt");
        bgm.triggers.add(Trigger.TURN_START); bgm.triggerProbability=0;
        hurt.triggers.add(Trigger.HP_LOSS); hurt.prerequisiteRuleIds.add(bgm.id); config.rules.add(bgm); config.rules.add(hurt);
        RuleEngine engine=new RuleEngine(config,fixture.manager); engine.beginRun(false,80,100); engine.battleStart("window",false,false,80,100);
        engine.turnStart(); engine.health(79,100,false);
        check(fixture.manager.count()==0,"Failed prerequisite probability does not open window");
        fixture.manager.start(bgm,"preview"); int before=fixture.tracks.size(); engine.health(78,100,false);
        check(fixture.tracks.size()==before && !fixture.manager.isRuleActive(bgm.id),"Preview is not an automatic prerequisite");
        fixture.manager.stopPreview(); bgm.triggerProbability=1; engine.turnStart();
        check(fixture.manager.count()==1,"Starting prerequisite alone does not trigger hurt rule");
        Track background=fixture.tracks.get(fixture.tracks.size()-1); background.position=18;
        before=fixture.tracks.size(); engine.health(77,100,false);
        check(fixture.tracks.size()==before && !fixture.manager.isRuleActive(bgm.id),"Naturally ended prerequisite is already inactive before cleanup tick");
        fixture.manager.tick(); fixture.manager.start(hurt,"preview");
        check(fixture.tracks.get(fixture.tracks.size()-1).playing,"Dependent preview bypasses prerequisite");
        List<String> failures=new ArrayList<>(); PlaybackManager failed=new PlaybackManager(cache->{throw new IllegalArgumentException("failed");},failures::add);
        Rule failedBgm=audio("failed-BGM"), dependent=audio("failed-dependent"); failedBgm.triggers.add(Trigger.BATTLE_START);
        dependent.triggers.add(Trigger.HP_LOSS); dependent.prerequisiteRuleIds.add(failedBgm.id);
        Config failedConfig=new Config(); failedConfig.rules.add(failedBgm); failedConfig.rules.add(dependent);
        RuleEngine failedEngine=new RuleEngine(failedConfig,failed); failedEngine.beginRun(false,80,100); failedEngine.battleStart("failed",false,false,80,100);
        failedEngine.health(79,100,false);
        check(failures.size()==1 && failed.count()==0,"Audio creation failure never activates prerequisite or attempts dependent");
    }
    private static void overlapAndMultiplePrerequisites() {
        Fixture fixture=new Fixture(); Config config=new Config(); Rule first=audio("first-BGM"), second=audio("second-BGM"), hurt=audio("multi-hurt");
        first.retriggerMode=RetriggerMode.OVERLAP; hurt.retriggerMode=RetriggerMode.OVERLAP;
        hurt.triggers.add(Trigger.HP_LOSS); hurt.prerequisiteRuleIds.add(first.id); hurt.prerequisiteRuleIds.add(second.id);
        config.rules.add(first); config.rules.add(second); config.rules.add(hurt);
        RuleEngine engine=new RuleEngine(config,fixture.manager); engine.beginRun(false,80,100); engine.battleStart("multi",false,false,80,100);
        fixture.manager.start(first,"multi"); fixture.manager.start(first,"multi"); fixture.tracks.get(0).position=18;
        fixture.manager.tick(); int before=fixture.tracks.size(); engine.health(79,100,false);
        check(fixture.tracks.size()==before+1,"One remaining prerequisite overlap keeps window open");
        fixture.manager.stopRule(first.id); fixture.manager.start(second,"multi"); before=fixture.tracks.size(); engine.health(78,100,false);
        check(fixture.tracks.size()==before+1,"Any selected prerequisite is sufficient");
        fixture.manager.stopRule(second.id); before=fixture.tracks.size(); engine.health(77,100,false);
        check(fixture.tracks.size()==before,"All selected prerequisites ending closes window");
        first.playbackMode=PlaybackMode.LIMITED; first.loopCount=2; fixture.manager.start(first,"multi");
        Track looping=fixture.tracks.get(fixture.tracks.size()-1); looping.position=18;
        check(fixture.manager.isRuleActive(first.id),"Prerequisite remains active between finite loop iterations");
        fixture.manager.tick(); looping.position=18;
        check(!fixture.manager.isRuleActive(first.id),"Last finite loop ending closes prerequisite");
        fixture.manager.stopAll(); before=fixture.tracks.size(); engine.health(76,100,false);
        check(fixture.tracks.size()==before,"Stop all clears prerequisite state");
    }
    private static void pausedPrerequisiteAndStopConditions() {
        Fixture fixture=new Fixture(); Config config=new Config(); Rule bgm=audio("paused-BGM"), hurt=audio("paused-hurt");
        bgm.triggers.add(Trigger.TURN_START); bgm.playbackMode=PlaybackMode.INFINITE; bgm.pauseWhenRuleIds.add(hurt.id);
        hurt.triggers.add(Trigger.HP_LOSS); hurt.prerequisiteRuleIds.add(bgm.id); hurt.stopConditions.triggers.add(Trigger.GAIN_GOLD);
        config.rules.add(hurt); config.rules.add(bgm); config.validate();
        RuleEngine engine=new RuleEngine(config,fixture.manager); engine.beginRun(false,80,100); engine.battleStart("paused",false,false,80,100);
        engine.turnStart(); engine.health(79,100,false);
        check(fixture.manager.pausedRuleIds().contains(bgm.id) && fixture.manager.isRuleActive(bgm.id),"Paused BGM is still an active prerequisite");
        int before=fixture.tracks.size(); engine.health(78,100,false);
        check(fixture.tracks.size()==before+1,"Hurt rule can retrigger while it temporarily pauses its own prerequisite");
        fixture.manager.stopRule(bgm.id); engine.event(Trigger.GAIN_GOLD,null);
        check(fixture.manager.count()==0,"Stop conditions remain effective after prerequisite ends");
    }
    private static void cadenceAndPersistence() {
        Fixture fixture=new Fixture(); Config config=new Config(); Rule bgm=audio("cadence-BGM"), hurt=audio("cadence-hurt");
        hurt.triggers.add(Trigger.RECEIVE_DEBUFF); hurt.prerequisiteRuleIds.add(bgm.id);
        hurt.filter(Trigger.RECEIVE_DEBUFF).cadence=Cadence.FIRST_TURN;
        config.rules.add(bgm); config.rules.add(hurt); RuleEngine engine=new RuleEngine(config,fixture.manager);
        engine.beginRun(false,80,100); engine.battleStart("cadence",false,false,80,100);
        engine.event(Trigger.RECEIVE_DEBUFF,null); fixture.manager.start(bgm,"cadence"); int before=fixture.tracks.size();
        engine.event(Trigger.RECEIVE_DEBUFF,null);
        check(fixture.tracks.size()==before+1,"Blocked prerequisite does not consume first-per-turn filter");
        before=fixture.tracks.size(); engine.event(Trigger.RECEIVE_DEBUFF,null);
        check(fixture.tracks.size()==before,"Existing cadence still limits accepted events");
        Config restored=Config.fromJson(new JsonParser().parse(new Gson().toJson(config)).getAsJsonObject());
        check(restored.rules.get(1).prerequisiteRuleIds.equals(hurt.prerequisiteRuleIds),"Prerequisite selection persists across save and reload");
        com.google.gson.JsonObject legacy=new JsonParser().parse(new Gson().toJson(config)).getAsJsonObject();
        legacy.getAsJsonArray("rules").get(1).getAsJsonObject().remove("prerequisiteRuleIds");
        restored=Config.fromJson(legacy);
        check(restored.rules.get(1).prerequisiteRuleIds.isEmpty(),"Old configurations default to no prerequisite restriction");
    }
    private static void healthAndRestartPaths() {
        Fixture fixture=new Fixture(); Config config=new Config(); Rule bgm=audio("carry-BGM"), low=audio("carry-low");
        low.triggers.add(Trigger.LOW_HEALTH); low.low.value=30; low.prerequisiteRuleIds.add(bgm.id);
        low.roomBehavior=RoomBehavior.STOP_AND_RESTART_NEXT_BATTLE; config.rules.add(bgm); config.rules.add(low);
        RuleEngine engine=new RuleEngine(config,fixture.manager); engine.beginRun(false,80,100); engine.battleStart("one",false,false,80,100);
        engine.health(30,100,false); check(fixture.manager.count()==0,"Direct health threshold path checks prerequisites");
        fixture.manager.start(bgm,"one"); engine.health(50,100,false); engine.health(30,100,false);
        check(fixture.manager.count()==2,"New health crossing with active prerequisite triggers");
        engine.battleEnd(); int before=fixture.tracks.size(); engine.battleStart("two",false,false,30,100);
        check(fixture.tracks.size()==before,"Next-battle replay also requires active prerequisite");
        bgm.enabled=false; bgm.triggers.add(Trigger.TURN_START); engine.turnStart(); engine.health(50,100,false); engine.health(30,100,false);
        check(fixture.tracks.size()==before,"Disabled prerequisite cannot enable dependent audio");
    }
}
