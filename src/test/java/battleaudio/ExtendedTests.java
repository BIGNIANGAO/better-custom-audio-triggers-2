package battleaudio;

import battleaudio.audio.PlaybackManager;
import battleaudio.core.RuleEngine;
import battleaudio.model.Config;
import battleaudio.model.Config.*;
import battleaudio.model.Events;
import com.badlogic.gdx.audio.Music;
import java.util.*;

public final class ExtendedTests {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static Rule rule(Trigger... triggers) {
        Rule rule = new Rule(); rule.retriggerMode = Config.RetriggerMode.OVERLAP; rule.audio().duration = rule.audio().end = 10; rule.audio().cachePath = "cache.wav"; rule.triggers = EnumSet.noneOf(Trigger.class);
        Collections.addAll(rule.triggers, triggers); return rule;
    }
    private static final class Recording implements RuleEngine.Playback {
        int starts, stops;
        public void start(Rule rule, String scene) { starts++; }
        public void stopScene(String scene) { stops++; }
        public void stopAll() { stops++; }
    }
    private static final class Track implements Music {
        boolean playing, disposed; float position; int plays;
        public void play() { playing = true; plays++; }
        public void pause() { playing = false; }
        public void stop() { playing = false; }
        public boolean isPlaying() { return playing; }
        public void setLooping(boolean value) { }
        public boolean isLooping() { return false; }
        public void setVolume(float volume) { }
        public float getVolume() { return 0; }
        public void setPan(float pan, float volume) { }
        public void setPosition(float value) { position = value; }
        public float getPosition() { return position; }
        public void dispose() { disposed = true; playing = false; }
        public void setOnCompletionListener(OnCompletionListener listener) { }
    }
    private static void verifiedFilters() {
        try {
            java.nio.file.Path directory=java.nio.file.Files.createTempDirectory("battleaudio-path-test");
            java.nio.file.Path decoder=directory.resolve("ffmpeg.exe"); java.nio.file.Files.write(decoder,new byte[]{1});
            try {
                check(decoder.equals(BattleAudioMod.decoderOnPath("bad\"entry"+java.io.File.pathSeparator+directory)),"Malformed PATH entry does not disable decoder discovery");
                check(decoder.equals(BattleAudioMod.decoderOnPath("\""+directory+"\"")),"Quoted PATH directory is supported");
                check(BattleAudioMod.decoderOnPath(null)==null,"Missing PATH is safe");
            } finally { java.nio.file.Files.delete(decoder); java.nio.file.Files.delete(directory); }
        } catch(java.io.IOException ex) { throw new RuntimeException(ex); }

        Rule evoke=rule(Trigger.EVOKE_ORB); Config cfg=new Config(); cfg.rules.add(evoke);
        EventFilter f=evoke.filter(Trigger.EVOKE_ORB); f.evokeCause="OVERFLOW"; f.orbPosition=1; evoke.filters.put(Trigger.EVOKE_ORB,f);
        Recording rec=new Recording(); RuleEngine engine=new RuleEngine(cfg,rec); engine.scene("one");
        battleaudio.model.EventContext context=new battleaudio.model.EventContext(); context.evokeCause="ACTIVE"; context.orbPosition=1;
        engine.event(Trigger.EVOKE_ORB,"Frost",context); check(rec.starts==0,"Active is not full-slot overflow");
        context.evokeCause="OVERFLOW"; context.orbPosition=2; engine.event(Trigger.EVOKE_ORB,"Frost",context);
        check(rec.starts==0,"Evoke slot position must match"); context.orbPosition=1; engine.event(Trigger.EVOKE_ORB,"Frost",context);
        check(rec.starts==1,"Exact cause and slot match");
        Config restored=new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(cfg),Config.class);
        check(restored.rules.get(0).filter(Trigger.EVOKE_ORB).orbPosition==1,"Orb position saves/reloads");
        Rule copy=battleaudio.model.RuleCopies.copy(evoke,cfg.rules); copy.filter(Trigger.EVOKE_ORB).evokeCause="ACTIVE";
        check("OVERFLOW".equals(evoke.filter(Trigger.EVOKE_ORB).evokeCause),"Copied cause edits independently");
        Rule low=rule(Trigger.LOW_HEALTH_FIRST_BLOCK); low.parameter(Trigger.LOW_HEALTH_FIRST_BLOCK).value=30;
        cfg=new Config(); cfg.rules.add(low); rec=new Recording(); engine=new RuleEngine(cfg,rec); engine.beginRun(false,80,100); engine.scene("one");
        engine.lowHealthBlock(80,100,2); engine.lowHealthBlock(20,100,0); check(rec.starts==0,"High HP and zero block gain excluded");
        engine.lowHealthBlock(20,100,2); engine.lowHealthBlock(20,100,3); check(rec.starts==1,"Only first block in same low interval");
        engine.health(80,100,true); engine.lowHealthBlock(20,100,1); check(rec.starts==2,"Heal out of interval resets low first block");
        engine.scene("two"); engine.lowHealthBlock(20,100,1); check(rec.starts==3,"Room boundary resets low block intent");
        for(Trigger trigger:new Trigger[]{Trigger.EVENT_OPEN,Trigger.EVENT_OPTION_COMPLETED,Trigger.MAP_NODE_ENTER,Trigger.ACT_ENTER,
                Trigger.COMBAT_CARD_REWARD_OBTAIN,Trigger.REMOVE_DECK_CARD,Trigger.COPY_DECK_CARD,Trigger.TRANSFORM_DECK_CARD,Trigger.MANTRA_INCREASE}) {
            Rule rule=rule(trigger); cfg=new Config(); cfg.rules.add(rule); rule.filter(trigger).targetId="Exact"; rule.filters.put(trigger,rule.filter(trigger));
            rec=new Recording(); engine=new RuleEngine(cfg,rec); engine.scene("room");
            engine.event(trigger,null,new battleaudio.model.EventContext().target("","Wrong",new Object()));
            engine.event(trigger,null,new battleaudio.model.EventContext().target("","Exact",new Object()));
            check(rec.starts==1,trigger+" uses exact confirmed context, not nearby events");
        }
    }
    public static void run() {
        verifiedFilters();
        check(new Rule().playbackMode == PlaybackMode.ONCE && new Rule().audio().volume == 0.3f, "New rule defaults");
        Rule energyRule = rule(Trigger.ENERGY_GAIN_THRESHOLD);
        check(energyRule.parameter(Trigger.ENERGY_GAIN_THRESHOLD).value == 1, "Single energy gain defaults to one");
        Config energyConfig = new Config(); energyConfig.rules.add(energyRule);
        Recording energyRecord = new Recording(); RuleEngine energyEngine = new RuleEngine(energyConfig, energyRecord);
        energyEngine.scene("battle"); energyEngine.energyGained(1); energyEngine.energyGained(0);
        check(energyRecord.starts == 1, "Positive operation fires once; same-value update is silent");
        energyRule.parameter(Trigger.ENERGY_GAIN_THRESHOLD).value = 3;
        energyEngine.energyGained(2); energyEngine.energyGained(2);
        check(energyRecord.starts == 1, "Separate gains never accumulate toward threshold");
        energyEngine.energyGained(3); energyEngine.energyGained(-3);
        check(energyRecord.starts == 2, "Exact gain matches; consumption is silent");
        check(energyConfig.copy().rules.get(0).parameter(Trigger.ENERGY_GAIN_THRESHOLD).value == 3, "Energy threshold persists in config copy");
        energyRule.parameter(Trigger.ENERGY_GAIN_THRESHOLD).value = 0;
        try { energyConfig.validate(); throw new AssertionError("Zero energy threshold accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        for (Trigger increase : new Trigger[] {Trigger.FOCUS_INCREASE, Trigger.DARK_ACCUMULATE}) {
            Config config = new Config(); config.rules.add(rule(increase)); Recording record = new Recording();
            RuleEngine engine = new RuleEngine(config, record); engine.scene("one"); Object first = new Object(), second = new Object();
            engine.seed(increase, first, -2, 0, ""); engine.observe(increase, first, -1, 0, "", false);
            for (int i = 0; i < 20; i++) engine.observe(increase, first, -1, 0, "", false);
            check(record.starts == 1, increase + " negative increase once and stable value silent");
            engine.observe(increase, first, 7, 0, "", false);
            engine.seed(increase, second, 6, 0, ""); engine.observe(increase, second, 14, 0, "", false);
            check(record.starts == 3, increase + " arbitrary delta and separate objects");
            Trigger threshold = increase == Trigger.FOCUS_INCREASE ? Trigger.FOCUS_THRESHOLD : Trigger.DARK_DAMAGE_THRESHOLD;
            config.rules.get(0).triggers.add(threshold); config.rules.get(0).parameter(threshold).value = 10;
            engine.seed(increase, first, 7, 0, ""); engine.seed(threshold, first, 7, 0, "");
            engine.observe(increase, first, 14, 0, "", false); engine.observe(threshold, first, 14, 0, "", false);
            check(record.starts == 5, "Same rule increase and threshold create separate instances");
        }
        for (Events.Definition definition : Events.definitions()) if (definition.threshold && !definition.filter.startsWith("legacy")) {
            Config config = new Config(); Rule rule = rule(definition.trigger); rule.parameter(definition.trigger).value = 3;
            if (Events.requiresId(definition.trigger)) rule.parameter(definition.trigger).id = "Strength";
            config.rules.add(rule); Recording record = new Recording(); RuleEngine engine = new RuleEngine(config, record);
            engine.beginRun(false, 80, 100); engine.scene("one"); Object target = new Object(), other = new Object();
            String id = Events.requiresId(definition.trigger) ? "Strength" : "";
            boolean low = definition.trigger == Trigger.ENEMY_LOW_HEALTH;
            double outside = low ? 5 : 2, inside = low ? 2 : 5;
            engine.seed(definition.trigger, target, outside, 10, id);
            engine.observe(definition.trigger, target, inside, 10, id, false);
            for (int i = 0; i < 20; i++) engine.observe(definition.trigger, target, inside, 10, id, false);
            check(record.starts == 1, definition.trigger + " crosses once, no repeated frame trigger");
            engine.observe(definition.trigger, target, outside, 10, id, false);
            engine.observe(definition.trigger, target, inside, 10, id, false);
            check(record.starts == 2, definition.trigger + " rearms outside satisfied range");
            engine.seed(definition.trigger, other, outside, 10, id);
            engine.observe(definition.trigger, other, inside, 10, id, false);
            check(record.starts == 3, definition.trigger + " uses separate object history");
        }
        for (Trigger trigger : Trigger.values()) if (Events.isCounter(trigger)) {
            Config config = new Config(); Rule rule = rule(trigger); rule.parameter(trigger).value = 3; config.rules.add(rule);
            Recording record = new Recording(); RuleEngine engine = new RuleEngine(config, record); engine.scene("battle");
            for (int turn = 0; turn < 3; turn++) { engine.turnStart(); for (int i = 0; i < 8; i++) engine.count(trigger); check(record.starts == turn + 1, trigger + " resets once each player turn"); }
        }
        Config battleResetConfig = new Config(); Rule battleResetRule = rule(Trigger.DISCARDED_TURN);
        battleResetRule.parameter(Trigger.DISCARDED_TURN).value = 2; battleResetConfig.rules.add(battleResetRule);
        Recording battleResetRecord = new Recording(); RuleEngine battleResetEngine = new RuleEngine(battleResetConfig,battleResetRecord);
        battleResetEngine.battleStart("same",false,false,80,80);
        battleResetEngine.count(Trigger.DISCARDED_TURN); battleResetEngine.count(Trigger.DISCARDED_TURN);
        check(battleResetRecord.starts==1,"Counter fires in first battle");
        battleResetEngine.battleEnd(); battleResetEngine.battleStart("same",false,false,80,80);
        battleResetEngine.count(Trigger.DISCARDED_TURN); battleResetEngine.count(Trigger.DISCARDED_TURN);
        check(battleResetRecord.starts==2,"Same-scene battle resets counter and first trigger");
        Config config = new Config(); Rule rule = rule(Trigger.FOCUS_DECREASE, Trigger.FOCUS_NEGATIVE, Trigger.ORB_SLOTS_FULL); config.rules.add(rule);
        Recording record = new Recording(); RuleEngine engine = new RuleEngine(config, record); engine.scene("one"); Object p = new Object();
        engine.seed(Trigger.FOCUS_DECREASE, p, 2, 0, ""); engine.observe(Trigger.FOCUS_DECREASE, p, 1, 0, "", false);
        engine.observe(Trigger.FOCUS_DECREASE, p, 1, 0, "", false); check(record.starts == 1, "Actual focus loss once");
        engine.seed(Trigger.FOCUS_NEGATIVE, p, 0, 0, ""); engine.observe(Trigger.FOCUS_NEGATIVE, p, -1, 0, "", false);
        engine.observe(Trigger.FOCUS_NEGATIVE, p, -2, 0, "", false); check(record.starts == 2, "Focus negative only on nonnegative crossing");
        engine.seed(Trigger.ORB_SLOTS_FULL, p, 0, 0, ""); engine.observe(Trigger.ORB_SLOTS_FULL, p, 1, 0, "", false);
        engine.observe(Trigger.ORB_SLOTS_FULL, p, 1, 0, "", false); check(record.starts == 3, "Full slots does not repeat while full");
        List<Track> tracks = new ArrayList<>(); PlaybackManager manager = new PlaybackManager(cache -> { Track t = new Track(); tracks.add(t); return t; }, error -> { throw new AssertionError(error); });
        rule = rule(Trigger.BATTLE_START, Trigger.BATTLE_VICTORY, Trigger.NORMAL_BATTLE_VICTORY);
        rule.playbackMode = PlaybackMode.LIMITED; rule.loopCount = 3; rule.audio().start = 2; rule.audio().end = 5;
        manager.start(rule, "room"); Track t = tracks.get(0);
        for (int i = 1; i <= 3; i++) { t.position = 5; manager.tick(); check(t.plays == Math.min(i + 1, 3), "Finite repeats include first play " + i); }
        check(manager.count() == 0 && t.disposed, "Three plays finish naturally");
        rule.roomBehavior = RoomBehavior.CONTINUE_ACROSS_ROOMS;
        manager.start(rule, "room"); t = tracks.get(tracks.size() - 1); t.position = 3;
        manager.roomEnded("room"); manager.sceneChanged("room", "next"); check(manager.count() == 1 && t.position == 3, "Cross room retains same handle and position");
        for (int i = 0; i < 3; i++) { t.position = 5; manager.tick(); }
        check(manager.count() == 0, "Cross-room finite mode still ends");
        rule.roomBehavior = RoomBehavior.NEVER_STOP; manager.start(rule, "run");
        t = tracks.get(tracks.size() - 1); t.position = 3; manager.menu();
        check(manager.count() == 1 && t.position == 3, "Never-stop survives run exit without restarting");
        for (int i = 0; i < 3; i++) { t.position = 5; manager.tick(); }
        check(manager.count() == 0, "Never-stop still respects finite total plays");
        rule.roomBehavior = RoomBehavior.STOP_ON_ROOM_END; rule.playbackMode = PlaybackMode.INFINITE;
        config = new Config(); config.rules.add(rule); engine = new RuleEngine(config, manager); engine.beginRun(false, 80, 100);
        engine.battleStart("one", false, false, 80, 100); check(manager.count() == 1, "Battle start plays");
        engine.victory(false, false, true); check(manager.count() == 2, "Victory independent conditions survive old battle cleanup");
        engine.battleEnd(); check(manager.count() == 2, "Repeated battle end cannot clear victory sounds");
        engine.scene("two"); check(manager.count() == 0, "Victory stops when actually leaving room");
        for (RoomBehavior behavior : new RoomBehavior[] {RoomBehavior.STOP_AND_RESTART_NEXT_BATTLE, RoomBehavior.STOP_AND_RESTART_AFTER_DAMAGE}) {
            manager.stopAll(); rule.roomBehavior = behavior; rule.triggers = EnumSet.of(Trigger.PLAY_ATTACK);
            engine = new RuleEngine(config, manager); engine.beginRun(false, 80, 100); engine.battleStart("a", false, false, 80, 100);
            engine.event(Trigger.PLAY_ATTACK, null); engine.battleEnd(); engine.scene("campfire"); engine.battleStart("b", false, false, 80, 100);
            if (behavior == RoomBehavior.STOP_AND_RESTART_AFTER_DAMAGE) { check(manager.count() == 0, "Injury restart waits"); engine.blockLoss(); check(manager.count() == 0, "Block not HP injury"); engine.health(79, 100, false); }
            check(manager.count() == 1, "Restart is independent of low health and original condition");
            engine.health(78, 100, false); check(manager.count() == 1, "Restart fires once");
        }
        engine.configure(config); check(manager.count() == 0, "Config applies without replay and stops all instances");
        Set<Trigger> unique = EnumSet.noneOf(Trigger.class); for (Events.Definition definition : Events.definitions()) check(unique.add(definition.trigger), "One definition per condition");
        check(unique.size() == Trigger.values().length, "All configured conditions have a definition");
        Config legacy = new Config(); legacy.schemaVersion = 1; Rule old = rule(); old.loop = false; old.nextBattle = NextBattle.IMMEDIATE; legacy.rules.add(old); legacy.migrate();
        check(legacy.schemaVersion == 4 && old.playbackMode == PlaybackMode.ONCE && old.roomBehavior == RoomBehavior.STOP_AND_RESTART_NEXT_BATTLE, "Schema 1 migrates loop and restart behavior");
        old.loopCount = 0; try { legacy.validate(); throw new AssertionError("Invalid count accepted"); } catch (IllegalArgumentException expected) { check(true, "Reject nonpositive loop count"); }
        old.loopCount = 1; old.triggers = EnumSet.of(Trigger.POWER_THRESHOLD); old.parameters.clear();
        try { legacy.validate(); throw new AssertionError("Missing ID accepted"); } catch (IllegalArgumentException expected) { check(true, "Enabled filter requires ID even without parameter object"); }
        System.out.println("PASS: " + checks + " extended lifecycle, threshold, counter, looping and migration checks");
    }
}
