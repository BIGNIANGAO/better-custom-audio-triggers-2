package battleaudio;

import battleaudio.core.RuleEngine;
import battleaudio.model.Config;
import battleaudio.model.Config.*;
import java.util.*;

public final class StopConditionTests {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static Rule rule() {
        Rule rule = new Rule(); rule.audio().cachePath = "cache/test.wav"; rule.audio().duration = 10; rule.audio().end = 10;
        return rule;
    }
    private static final class Recorder implements RuleEngine.Playback {
        final List<String> playing = new ArrayList<>();
        int starts;
        public void start(Rule rule, String scene) { playing.add(rule.id); starts++; }
        public void stopRule(String id) { playing.removeIf(value -> value.equals(id)); }
        public void stopScene(String scene) { playing.clear(); }
        public void stopAll() { playing.clear(); }
    }
    public static void main(String[] args) { run(); }
    public static void run() {
        Config config = new Config(); Rule rain = rule(); config.rules.add(rain);
        rain.triggers.add(Trigger.LOW_HEALTH); rain.low.percent = true; rain.low.value = 30;
        rain.stopConditions.triggers.add(Trigger.HIGH_HEALTH); rain.stopConditions.high.percent = true; rain.stopConditions.high.value = 40;
        rain.playbackMode = PlaybackMode.INFINITE; rain.roomBehavior = RoomBehavior.STOP_AND_RESTART_NEXT_BATTLE;
        Recorder recorder = new Recorder(); RuleEngine engine = new RuleEngine(config, recorder);
        engine.beginRun(false, 80, 100); engine.battleStart("first", false, false, 80, 100);
        engine.health(30, 100, false); check(recorder.playing.size() == 1, "Low health starts rain");
        engine.health(39, 100, false); check(recorder.playing.size() == 1, "Below stop threshold keeps rain");
        engine.health(40, 100, false); check(recorder.playing.isEmpty(), "Exact stop health stops without actual heal");
        engine.battleEnd(); engine.battleStart("second", false, false, 40, 100);
        check(recorder.playing.isEmpty(), "Health stop cancels next battle restart");
        engine.health(30, 100, false); check(recorder.playing.size() == 1, "A new crossing can restart rain");
        Rule probability = rule(); probability.triggers.add(Trigger.BATTLE_START); probability.triggerProbability = 0;
        Config probabilityConfig = new Config(); probabilityConfig.rules.add(probability);
        Recorder probabilityRecorder = new Recorder(); RuleEngine probabilityEngine = new RuleEngine(probabilityConfig, probabilityRecorder, new Random(7));
        probabilityEngine.beginRun(false, 80, 100); probabilityEngine.battleStart("probability", false, false, 80, 100);
        check(probabilityRecorder.starts == 0, "Zero trigger probability suppresses playback");
        probability.triggerProbability = 1; probabilityEngine.battleStart("probability-two", false, false, 80, 100);
        check(probabilityRecorder.starts == 1, "Full trigger probability allows playback");
        eventStops();
        sampledStops();
        probabilityAndPersistence();
        System.out.println("PASS: " + checks + " stop/probability checks");
    }
    private static void eventStops() {
        Config config = new Config(); Rule stopped = rule(); Rule other = rule();
        stopped.triggers.add(Trigger.HP_LOSS); stopped.stopConditions.triggers.add(Trigger.CHANNEL_ORB);
        stopped.stopConditions.parameter(Trigger.CHANNEL_ORB).any = false;
        stopped.stopConditions.parameter(Trigger.CHANNEL_ORB).id = "Frost";
        other.triggers.add(Trigger.HP_LOSS); config.rules.add(stopped); config.rules.add(other);
        Recorder recorder = new Recorder(); RuleEngine engine = new RuleEngine(config, recorder);
        engine.beginRun(false,80,100); engine.battleStart("events",false,false,80,100);
        engine.health(79,100,false); engine.health(78,100,false);
        check(recorder.playing.size() == 4, "Rules may each hold two overlapping instances");
        engine.event(Trigger.CHANNEL_ORB,"Lightning");
        check(recorder.playing.size() == 4, "Different orb does not stop rule");
        engine.event(Trigger.CHANNEL_ORB,"Frost");
        check(recorder.playing.size() == 2 && !recorder.playing.contains(stopped.id), "Stop removes all matching instances only");
        stopped.stopConditions.triggers.clear(); stopped.stopConditions.triggers.add(Trigger.HP_LOSS);
        stopped.stopConditions.filter(Trigger.HP_LOSS).minAmount = 10;
        engine.health(77,100,false); check(recorder.playing.contains(stopped.id), "Stop amount below threshold allows play");
        engine.health(67,100,false); check(!recorder.playing.contains(stopped.id), "Same event stop takes priority over play");
        stopped.stopConditions.triggers.clear(); stopped.stopConditions.triggers.add(Trigger.GAIN_GOLD);
        Config.EventFilter filter = stopped.stopConditions.filter(Trigger.GAIN_GOLD);
        filter.minTurnTotal = 5; filter.cadence = Config.Cadence.FIRST_TURN;
        engine.health(66,100,false); engine.turnStart();
        engine.event(Trigger.GAIN_GOLD,null,new battleaudio.model.EventContext().amount(2,0,2));
        check(recorder.playing.contains(stopped.id), "Stop turn total below threshold does not stop");
        engine.event(Trigger.GAIN_GOLD,null,new battleaudio.model.EventContext().amount(3,2,5));
        check(!recorder.playing.contains(stopped.id), "Stop turn total reaches threshold");
        engine.health(65,100,false); engine.event(Trigger.GAIN_GOLD,null,new battleaudio.model.EventContext().amount(5,5,10));
        check(recorder.playing.contains(stopped.id), "Stop cadence is limited per turn");
        engine.turnStart(); engine.event(Trigger.GAIN_GOLD,null,new battleaudio.model.EventContext().amount(5,10,15));
        check(!recorder.playing.contains(stopped.id), "Stop cadence resets next turn");
        stopped.stopConditions.triggers.clear(); stopped.stopConditions.triggers.add(Trigger.GAIN_BLOCK);
        Config.EventFilter block = stopped.stopConditions.filter(Trigger.GAIN_BLOCK); block.minAmount = 8;
        engine.health(64,100,false);
        engine.unfilteredEvent(Trigger.GAIN_BLOCK,null);
        check(recorder.playing.contains(stopped.id), "Unfiltered sampling does not satisfy filtered stop");
        engine.filteredEvent(Trigger.GAIN_BLOCK,null,new battleaudio.model.EventContext().amount(8,0,8));
        check(!recorder.playing.contains(stopped.id), "Filtered stop works without matching play condition");
    }
    private static void sampledStops() {
        Config config = new Config(); Rule stopped = rule(); config.rules.add(stopped);
        stopped.triggers.add(Trigger.HP_LOSS); stopped.stopConditions.triggers.add(Trigger.ENERGY_THRESHOLD);
        stopped.stopConditions.parameter(Trigger.ENERGY_THRESHOLD).value = 3;
        Recorder recorder = new Recorder(); RuleEngine engine = new RuleEngine(config,recorder);
        engine.beginRun(false,80,100); engine.battleStart("sample",false,false,80,100);
        Object player = new Object(); engine.seed(Trigger.ENERGY_THRESHOLD,player,0,0,"");
        engine.health(79,100,false); engine.observe(Trigger.ENERGY_THRESHOLD,player,2,0,"",false);
        check(recorder.playing.size()==1, "Sample stop checks configured current threshold");
        engine.observe(Trigger.ENERGY_THRESHOLD,player,3,0,"",false);
        check(recorder.playing.isEmpty(), "Sample threshold crossing stops rule");
        engine.health(78,100,false); engine.observe(Trigger.ENERGY_THRESHOLD,player,4,0,"",false);
        check(recorder.playing.size()==1, "Staying above sampled threshold does not retrigger");
        stopped.stopConditions.triggers.clear(); stopped.stopConditions.triggers.add(Trigger.ENERGY_GAIN_THRESHOLD);
        stopped.stopConditions.parameter(Trigger.ENERGY_GAIN_THRESHOLD).value = 4;
        engine.energyGained(3); check(recorder.playing.size()==1, "Energy gain stop uses actual delta");
        engine.energyGained(4); check(recorder.playing.isEmpty(), "Energy gain equality stops");
        stopped.stopConditions.triggers.clear(); stopped.stopConditions.triggers.add(Trigger.LOW_HEALTH_FIRST_BLOCK);
        stopped.stopConditions.parameter(Trigger.LOW_HEALTH_FIRST_BLOCK).value = 30;
        engine.health(29,100,false); engine.lowHealthBlock(29,100,0);
        check(recorder.playing.size()==1, "Health sampling with zero block does not stop");
        engine.lowHealthBlock(29,100,1); check(recorder.playing.isEmpty(), "First actual low health block stops");
        stopped.stopConditions.triggers.clear(); stopped.stopConditions.triggers.add(Trigger.BOSS_START);
        stopped.triggers.clear(); stopped.triggers.add(Trigger.BATTLE_START);
        stopped.roomBehavior = RoomBehavior.STOP_AND_RESTART_NEXT_BATTLE;
        engine.battleEnd(); int starts = recorder.starts;
        engine.battleStart("boss",true,false,80,100);
        check(recorder.starts==starts && recorder.playing.isEmpty(), "Boss stop wins over battle start and restart");
    }
    private static void probabilityAndPersistence() {
        Config config = new Config(); Rule selected = rule(); config.rules.add(selected); selected.triggers.add(Trigger.HP_LOSS);
        selected.stopConditions.triggers.add(Trigger.PLAYER_DEATH);
        Recorder recorder = new Recorder(); RuleEngine engine = new RuleEngine(config,recorder,new Random(19));
        engine.beginRun(false,80,100); engine.battleStart("probability",false,false,80,100);
        engine.health(79,100,false); selected.triggerProbability = 0;
        engine.health(78,100,false); check(recorder.playing.size()==1, "Probability failure preserves current audio");
        engine.event(Trigger.PLAYER_DEATH,null); check(recorder.playing.isEmpty(), "Stop ignores zero probability");
        selected.triggerProbability = .5f;
        int before = recorder.starts;
        for(int index=0;index<100;index++) engine.event(Trigger.HP_LOSS,null);
        check(recorder.starts>before && recorder.starts<before+100, "Partial probability produces both outcomes");
        selected.stopConditions.triggers.clear(); selected.triggers.clear(); selected.triggers.add(Trigger.LOW_HEALTH);
        selected.low.percent=true; selected.low.value=30;
        selected.stopConditions.triggers.add(Trigger.HIGH_HEALTH); selected.stopConditions.high.percent=true;
        for(double value:new double[]{30,20}) {
            selected.stopConditions.high.value=value;
            boolean rejected=false; try { config.validate(); } catch(IllegalArgumentException ex) { rejected=true; }
            check(rejected,"Equal or lower health stop is rejected");
        }
        selected.stopConditions.high.value=40; config.validate();
        Config restored = Config.fromJson(new com.google.gson.JsonParser().parse(new com.google.gson.Gson().toJson(config)).getAsJsonObject());
        check(restored.rules.get(0).triggerProbability==.5f && restored.rules.get(0).stopConditions.high.value==40,
                "Save/reload retains probability and stop settings");
        com.google.gson.JsonObject legacy = new com.google.gson.JsonParser().parse(new com.google.gson.Gson().toJson(config)).getAsJsonObject();
        com.google.gson.JsonObject legacyRule=legacy.getAsJsonArray("rules").get(0).getAsJsonObject();
        legacyRule.remove("triggerProbability"); legacyRule.remove("stopConditions");
        Config migrated=Config.fromJson(legacy);
        check(migrated.rules.get(0).triggerProbability==1 && migrated.rules.get(0).stopConditions.triggers.isEmpty(),
                "Old files default to full probability and no stops");
    }
}
