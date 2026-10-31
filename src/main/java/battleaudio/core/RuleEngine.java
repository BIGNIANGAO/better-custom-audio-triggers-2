package battleaudio.core;

import battleaudio.model.Config;
import battleaudio.model.Config.Rule;
import battleaudio.model.MusicPack;
import battleaudio.model.Config.Trigger;
import battleaudio.model.Config.Parameter;
import battleaudio.model.Events;
import battleaudio.model.EventContext;
import java.util.*;
import java.util.function.Predicate;

/** Game-independent lifecycle and edge detection. Polls and real events share this engine. */
public final class RuleEngine {
    public interface Playback {
        void start(Rule rule, String scene);
        default void stopRule(String ruleId) { }
        default boolean isRuleActive(String ruleId) { return false; }
        void stopScene(String scene);
        void stopAll();
        default void roomEnded(String scene) { stopScene(scene); }
        default void sceneChanged(String previous, String next) { stopScene(previous); }
        default void menu() { stopAll(); }
    }
    private Config config;
    private final Playback playback;
    private final Random random;
    private final Set<String> stoppedInEvent = new HashSet<>();
    private int eventDepth;
    private int health, maxHealth;
    private boolean initialized, inBattle, hasBattled;
    private String scene;
    private final Set<String> fired = new HashSet<>(), carry = new HashSet<>(), pending = new HashSet<>();
    private final Map<Trigger, Integer> counters = new EnumMap<>(Trigger.class);
    private final Map<String, Integer> turnTotals = new HashMap<>(), battleTotals = new HashMap<>();
    private final Map<String, Set<Object>> objectFired = new HashMap<>();
    private final Set<String> turnFired = new HashSet<>(), battleFired = new HashSet<>();
    private final Object player = new Object();
    private static final class Value {
        final double value, maximum;
        Value(double value, double maximum) { this.value = value; this.maximum = maximum; }
    }
    private final Map<Object, Map<Trigger, Map<String, Value>>> samples = new IdentityHashMap<>();

    public RuleEngine(Config config, Playback playback) { this(config, playback, new Random()); }
    public RuleEngine(Config config, Playback playback, Random random) {
        this.config = config; this.playback = playback; this.random = random;
    }
    private void beginEvent() { if (eventDepth++ == 0) stoppedInEvent.clear(); }
    private void endEvent() { eventDepth--; }
    private void stopRule(Rule rule) {
        stoppedInEvent.add(rule.id);
        fired.remove(rule.id); carry.remove(rule.id); pending.remove(rule.id);
        playback.stopRule(rule.id);
    }
    private void stopLifecycle(Set<Trigger> triggers) {
        for (Rule rule : config.rules) if (config.effectivelyEnabled(rule)) {
            Rule stop = rule.stopConditions.asRule(rule.id + ":stop");
            for (Trigger trigger : triggers) if (eligible(stop, trigger, null, new EventContext(), condition -> true, 0)) {
                stopRule(rule); break;
            }
        }
    }
    private boolean healthStopped(Rule rule) {
        Config.StopConditions stop = rule.stopConditions;
        return initialized && (stop.triggers.contains(Trigger.HIGH_HEALTH) && health >= stop.high.at(maxHealth)
                || stop.triggers.contains(Trigger.HIGH_HEALTH) && stop.additionalHigh != null && health >= stop.additionalHigh.at(maxHealth)
                || stop.triggers.contains(Trigger.HEAL_THRESHOLD) && health >= stop.heal.at(maxHealth)
                || stop.triggers.contains(Trigger.LOW_HEALTH) && health <= stop.low.at(maxHealth));
    }
    private void dispatch(Trigger trigger, String id, EventContext context, Predicate<Rule> guard, int filterMode) {
        beginEvent();
        try {
            for (Rule rule : config.rules) {
                if (!config.effectivelyEnabled(rule)) continue;
                Rule stop = rule.stopConditions.asRule(rule.id + ":stop");
                if (eligible(stop, trigger, id, context, guard, filterMode)) {
                    if (trigger == Trigger.LOW_HEALTH_FIRST_BLOCK) lowBlockFired.add(stop.id + ":low-block");
                    stopRule(rule);
                }
            }
            playEligibleRules(rule -> {
                if (!eligible(rule, trigger, id, context, guard, filterMode)) return false;
                if (trigger == Trigger.LOW_HEALTH_FIRST_BLOCK) lowBlockFired.add(rule.id + ":low-block");
                return true;
            });
        } finally { endEvent(); }
    }
    private boolean eligible(Rule rule, Trigger trigger, String id, EventContext context, Predicate<Rule> guard, int filterMode) {
        boolean filtered = rule.filters.containsKey(trigger);
        return (filterMode == 0 || filtered == (filterMode == 1))
                && prerequisiteSatisfied(rule) && matches(rule, trigger, id) && guard.test(rule) && accepts(rule, trigger, context);
    }
    private void playEligibleRules(Predicate<Rule> guard) {
        List<Rule> remaining = new ArrayList<>(config.rules);
        boolean progressed;
        do {
            progressed = false;
            Iterator<Rule> iterator = remaining.iterator();
            while (iterator.hasNext()) {
                Rule rule = iterator.next();
                if (!prerequisiteSatisfied(rule)) continue;
                iterator.remove(); progressed = true;
                if (config.effectivelyEnabled(rule) && !stoppedInEvent.contains(rule.id) && guard.test(rule)) play(rule);
            }
        } while (progressed && !remaining.isEmpty());
    }
    public void configure(Config config) {
        this.config = config; stop();
        // Keep live measurements: editing a threshold is not a game event.
    }
    /** Applies a package-wide toggle without resetting rules or stopping unrelated packages. */
    public void configurePackageToggle(Config next, String packId) {
        MusicPack before = config.pack(packId);
        MusicPack after = next.pack(packId);
        this.config = next;
        if (before != null && before.enabled && after != null && !after.enabled) {
            for (Rule rule : next.rules) if (packId.equals(rule.packId)) stopRule(rule);
        }
    }
    public void beginRun(boolean hasBattled, int health, int maxHealth) {
        menu(); this.hasBattled = hasBattled; baseline(health, maxHealth);
    }
    public boolean hasBattled() { return hasBattled; }
    public boolean inBattle() { return inBattle; }
    public void baseline(int health, int maxHealth) { this.health = health; this.maxHealth = maxHealth; initialized = true; }

    private boolean restart(Rule rule) {
        return rule.roomBehavior == Config.RoomBehavior.STOP_AND_RESTART_NEXT_BATTLE
                || rule.roomBehavior == Config.RoomBehavior.STOP_AND_RESTART_AFTER_DAMAGE;
    }
    private void remember() {
        for (Rule rule : config.rules) if (fired.contains(rule.id) && config.effectivelyEnabled(rule) && restart(rule)) carry.add(rule.id);
        fired.clear();
    }
    public void scene(String next) {
        if (scene != null && !scene.equals(next)) {
            remember(); playback.sceneChanged(scene, next); inBattle = false; pending.clear();
            lowBlockFired.clear(); samples.clear(); counters.clear();
            turnTotals.clear(); battleTotals.clear(); turnFired.clear(); battleFired.clear(); objectFired.clear();
        }
        scene = next;
    }
    public void battleStart(String scene, boolean boss, boolean elite, int health, int maxHealth) {
        battleStart(scene, boss, elite, !boss && !elite, health, maxHealth);
    }
    public void battleStart(String scene, boolean boss, boolean elite, boolean normal, int health, int maxHealth) {
        beginEvent();
        try {
            scene(scene); baseline(health, maxHealth); inBattle = true; pending.clear();
            counters.clear(); samples.clear(); turnTotals.clear(); turnFired.clear();
            for (Trigger trigger : Trigger.values()) if (Events.isCounter(trigger)) seed(trigger,player,0,0,"");
            battleTotals.clear(); battleFired.clear(); objectFired.clear();
            boolean first = !hasBattled; hasBattled = true;
            Set<Trigger> due = EnumSet.of(Trigger.BATTLE_START);
            if (first) due.add(Trigger.FIRST_BATTLE);
            if (boss) due.add(Trigger.BOSS_START);
            if (elite) due.add(Trigger.ELITE_START);
            if (normal) due.add(Trigger.NORMAL_BATTLE_START);
            stopLifecycle(due);

            playEligibleRules(rule -> carry.contains(rule.id) && rule.roomBehavior == Config.RoomBehavior.STOP_AND_RESTART_NEXT_BATTLE);
            for (Rule rule : config.rules) if (carry.contains(rule.id) && config.effectivelyEnabled(rule)
                    && rule.roomBehavior == Config.RoomBehavior.STOP_AND_RESTART_AFTER_DAMAGE) pending.add(rule.id);
            carry.clear();
            event(Trigger.BATTLE_START, null);
            if (first) event(Trigger.FIRST_BATTLE, null);
            if (boss) event(Trigger.BOSS_START, null);
            if (elite) event(Trigger.ELITE_START, null);
            if (normal) event(Trigger.NORMAL_BATTLE_START, null);
        } finally { endEvent(); }
    }
    public void battleEnd() {
        if (!inBattle) return;
        remember(); playback.roomEnded(scene); inBattle = false; pending.clear();
    }
    public void victory(boolean boss, boolean elite, boolean normal) {
        beginEvent();
        try {
            if (!inBattle) return;
            // Stop old room instances BEFORE victory. New victory instances survive until room transition.
            battleEnd();
            Set<Trigger> due = EnumSet.of(Trigger.BATTLE_VICTORY);
            if (boss) due.add(Trigger.BOSS_VICTORY);
            if (elite) due.add(Trigger.ELITE_VICTORY);
            if (normal) due.add(Trigger.NORMAL_BATTLE_VICTORY);
            stopLifecycle(due);
            event(Trigger.BATTLE_VICTORY, null);
            if (boss) event(Trigger.BOSS_VICTORY, null);
            if (elite) event(Trigger.ELITE_VICTORY, null);
            if (normal) event(Trigger.NORMAL_BATTLE_VICTORY, null);
        } finally { endEvent(); }
    }
    public void death() { if (inBattle) battleEnd(); event(Trigger.PLAYER_DEATH, null); }
    public void health(int nextHealth, int nextMax, boolean actualHeal) {
        health(nextHealth,nextMax,actualHeal,new EventContext().source("UNKNOWN",""));
    }
    public void health(int nextHealth, int nextMax, boolean actualHeal, EventContext context) {
        beginEvent();
        try {
            if (!initialized) { baseline(nextHealth, nextMax); return; }
            int old = health, oldMax = maxHealth; baseline(nextHealth, nextMax);
            for (Rule rule : config.rules) if (config.effectivelyEnabled(rule) && healthStopped(rule)) stopRule(rule);
            lowHealthBlock(nextHealth,nextMax,0);
            if (nextHealth < old) {
                EventContext loss = context.amount(old-nextHealth, old, nextHealth).target("PLAYER", "", player);
                for (Rule rule : config.rules) if (config.effectivelyEnabled(rule)) {
                    Rule stop = rule.stopConditions.asRule(rule.id + ":stop");
                    if (eligible(stop, Trigger.HP_LOSS, null, loss, condition -> true, 0)) stopRule(rule);
                }
                if (inBattle && !pending.isEmpty()) {
                    Set<String> due = new HashSet<>(pending); pending.clear();
                    playEligibleRules(rule -> due.contains(rule.id));
                }
                playEligibleRules(rule -> eligible(rule, Trigger.HP_LOSS, null, loss, condition -> true, 0));
            }
            playEligibleRules(rule -> rule.triggers.contains(Trigger.LOW_HEALTH) && old > rule.low.at(oldMax) && nextHealth <= rule.low.at(nextMax));
            playEligibleRules(rule -> rule.triggers.contains(Trigger.HIGH_HEALTH) && old < rule.high.at(oldMax) && nextHealth >= rule.high.at(nextMax));
            playEligibleRules(rule -> actualHeal && nextHealth > old && rule.triggers.contains(Trigger.HEAL_THRESHOLD)
                    && old < rule.heal.at(oldMax) && nextHealth >= rule.heal.at(nextMax));
        } finally { endEvent(); }
    }
    public void blockLoss() { event(Trigger.BLOCK_LOSS, null); }
    public void blockLoss(int old, int next) {
        if (next < old) event(Trigger.BLOCK_LOSS, null, new EventContext().amount(old-next, old, next)
                .target("PLAYER", "", player));
    }
    /** Checks the actual positive delta from one energy operation. It is deliberately not a sample. */
    public void energyGained(int amount) {
        energyGained(amount,new EventContext().amount(amount,0,amount));
    }
    public void energyGained(int amount,EventContext context) {
        amountThreshold(Trigger.ENERGY_GAIN_THRESHOLD, amount, context);
    }
    public void amountThreshold(Trigger trigger,int amount,EventContext context) {
        if (amount <= 0) return;
        dispatch(trigger, null, context, rule -> amount >= parameter(rule, trigger).value, 0);
    }
    public void turnStart() {
        counters.clear();
        turnTotals.clear(); turnFired.clear();
        for (Trigger trigger : Trigger.values()) if (Events.isCounter(trigger)) seed(trigger, player, 0, 0, "");
        event(Trigger.TURN_START, null);
    }
    public void count(Trigger trigger) {
        int next = counters.getOrDefault(trigger, 0) + 1; counters.put(trigger, next);
        observe(trigger, player, next, 0, "", false);
    }
    private boolean matches(Rule rule, Trigger trigger, String id) {
        if (!config.effectivelyEnabled(rule) || !rule.triggers.contains(trigger)) return false;
        if (trigger == Trigger.POSITIVE_POWER) return rule.anyPower || rule.powerIds.contains(id);
        Parameter p = parameter(rule, trigger);
        if (Events.requiresId(trigger)) return battleaudio.model.IdSelection.contains(p.id, id);
        if (trigger == Trigger.CHANNEL_ORB || trigger == Trigger.EVOKE_ORB || trigger == Trigger.ORB_PASSIVE) return p.any || battleaudio.model.IdSelection.contains(p.id, id);
        return true;
    }
    public void event(Trigger trigger, String id) {
        event(trigger, id, new EventContext());
    }
    public void event(Trigger trigger, String id, EventContext context) {
        dispatch(trigger, id, context, rule -> true, 0);
    }
    /** Dispatches only rules that explicitly opted into a context filter. */
    public void filteredEvent(Trigger trigger, String id, EventContext context) {
        dispatch(trigger, id, context, rule -> true, 1);
    }
    public void unfilteredEvent(Trigger trigger, String id) {
        dispatch(trigger, id, new EventContext(), rule -> true, 2);
    }
    public void lowHealthBlock(int health,int maximum,int amount) {
        for (Rule rule : config.rules) {
            if (health > parameter(rule, Trigger.LOW_HEALTH_FIRST_BLOCK).at(maximum)) lowBlockFired.remove(rule.id + ":low-block");
            Rule stop = rule.stopConditions.asRule(rule.id + ":stop");
            if (health > parameter(stop, Trigger.LOW_HEALTH_FIRST_BLOCK).at(maximum)) lowBlockFired.remove(stop.id + ":low-block");
        }
        if (amount <= 0) return;
        dispatch(Trigger.LOW_HEALTH_FIRST_BLOCK, null, new EventContext().amount(amount,0,amount).target("PLAYER","",player),
                rule -> health <= parameter(rule, Trigger.LOW_HEALTH_FIRST_BLOCK).at(maximum)
                        && !lowBlockFired.contains(rule.id + ":low-block"), 0);
    }
    private final Set<String> lowBlockFired=new HashSet<>();
    private boolean accepts(Rule rule, Trigger trigger, EventContext c) {
        Config.EventFilter f=rule.filters.get(trigger);
        if (f==null) return true;
        if (!field(f.sourceType,c.sourceType) || !field(f.sourceId,c.sourceId)
                || !field(f.targetType,c.targetType) || !field(f.targetId,c.targetId)
                || !field(f.enemyType,c.enemyType) || f.lastEnemy!=null && !f.lastEnemy.equals(c.lastEnemy)
                || !field(f.cardId,c.cardId) || !field(f.cardType,c.cardType)
                || !field(f.sourcePile,c.sourcePile) || !field(f.targetPile,c.targetPile)
                || !field(f.evokeCause,c.evokeCause) || f.orbPosition>0 && f.orbPosition!=c.orbPosition
                || !field(f.previousState,c.previousState) || !field(f.nextState,c.nextState)
                || f.cardCost!=Integer.MIN_VALUE && f.cardCost!=c.cardCost
                || f.upgraded!=null && !f.upgraded.equals(c.upgraded)
                || f.exhaust!=null && !f.exhaust.equals(c.exhaust)
                || f.temporary!=null && !f.temporary.equals(c.temporary)) return false;
        String key=rule.id+":"+trigger.name();
        int oldTurn=turnTotals.getOrDefault(key,0);
        int turn=oldTurn+Math.max(1,c.amount);
        turnTotals.put(key,turn); battleTotals.put(key,battleTotals.getOrDefault(key,0)+1);
        boolean amountEnabled=f.minAmountEnabled || f.minAmount>0;
        boolean currentEnabled=f.minCurrentValueEnabled || f.minCurrentValue>0;
        boolean totalEnabled=f.minTurnTotalEnabled || f.minTurnTotal>0;
        if (amountEnabled && c.amount<f.minAmount || currentEnabled && c.after<f.minCurrentValue
                || currentEnabled && f.crossingOnly && !(c.before<f.minCurrentValue && c.after>=f.minCurrentValue)
                || totalEnabled && turn<f.minTurnTotal
                || f.maxOncePerTurn && turnFired.contains(key)
                || f.maxOncePerBattle && battleFired.contains(key)
                || f.cadence==Config.Cadence.FIRST_TURN && turnFired.contains(key)
                || f.cadence==Config.Cadence.FIRST_BATTLE && battleFired.contains(key)) return false;
        if (f.cadence==Config.Cadence.FIRST_OBJECT) {
            if (c.object==null) return false;
            Set<Object> seen=objectFired.computeIfAbsent(key,unused->Collections.newSetFromMap(new IdentityHashMap<>()));
            if (!seen.add(c.object)) return false;
        }
        turnFired.add(key); battleFired.add(key);
        return true;
    }
    private static boolean field(String expected,String actual) { return expected.isEmpty() || battleaudio.model.IdSelection.contains(expected, actual); }
    /** Damage is a settlement, not a rising edge or a whole card's accumulated damage. */
    public void damage(int amount) {
        damage(amount,new EventContext().amount(amount,0,amount));
    }
    public void damage(int amount,EventContext context) {
        amountThreshold(Trigger.DAMAGE_THRESHOLD, amount, context);
    }
    public void seed(Trigger trigger, Object object, double value, double maximum, String id) {
        observe(trigger, object, value, maximum, id, true);
    }
    public void observe(Trigger trigger, Object object, double value, double maximum, String id, boolean baseline) {
        observe(trigger,object,value,maximum,id,baseline,new EventContext());
    }
    public void observe(Trigger trigger, Object object, double value, double maximum, String id,
            boolean baseline,EventContext details) {
        if (object == null) object = player;
        String filter = id == null ? "" : id;
        Map<String, Value> history = samples.computeIfAbsent(object, key -> new EnumMap<>(Trigger.class))
                .computeIfAbsent(trigger, key -> new HashMap<>());
        Value old = history.put(filter, new Value(value, maximum));
        if (baseline) return;
        if (old == null) old = new Value(trigger == Trigger.ENEMY_LOW_HEALTH ? maximum : 0, maximum);
        EventContext context=details.amount((int)Math.abs(value-old.value),(int)old.value,(int)value);
        context.object=object;
        if (trigger == Trigger.FOCUS_INCREASE || trigger == Trigger.DARK_ACCUMULATE) {
            if (value > old.value) event(trigger, filter, context); return;
        }
        if (trigger == Trigger.FOCUS_DECREASE) { if (value < old.value) event(trigger, filter, context); return; }
        if (trigger == Trigger.FOCUS_NEGATIVE) { if (old.value >= 0 && value < 0) event(trigger, filter, context); return; }
        if (trigger == Trigger.ORB_SLOTS_FULL) { if (old.value < 1 && value >= 1) event(trigger, filter, context); return; }
        if (trigger == Trigger.ORB_SLOTS_UNFULL) { if (old.value >= 1 && value < 1) event(trigger, filter, context); return; }
        Value previous = old;
        dispatch(trigger, filter, context, rule -> {
            Parameter parameter = parameter(rule, trigger);
            boolean before = trigger == Trigger.ENEMY_LOW_HEALTH
                    ? previous.value <= parameter.at(previous.maximum) : previous.value >= parameter.at(previous.maximum);
            boolean after = trigger == Trigger.ENEMY_LOW_HEALTH
                    ? value <= parameter.at(maximum) : value >= parameter.at(maximum);
            return !before && after;
        }, 0);
    }
    public void forget(Object object) { samples.remove(object); }
    private Parameter parameter(Rule rule, Trigger trigger) {
        Parameter parameter = rule.parameters.get(trigger);
        if (parameter != null) return parameter;
        parameter = new Parameter();
        if (trigger == Trigger.ENERGY_GAIN_THRESHOLD || trigger == Trigger.SCRY_CARDS) parameter.value = 1;
        return parameter;
    }
    public void stop() { playback.stopAll(); fired.clear(); carry.clear(); pending.clear(); }
    private void play(Rule rule) {
        if (scene == null || !config.effectivelyEnabled(rule) || !rule.playable()) return;
        if (rule.candidates.stream().noneMatch(audio -> audio.participate && audio.playable())) return;
        if (!prerequisiteSatisfied(rule)) return;
        if (healthStopped(rule) || eventDepth > 0 && stoppedInEvent.contains(rule.id)) return;
        if (rule.triggerProbability <= 0 || rule.triggerProbability < 1f && random.nextDouble() >= rule.triggerProbability) return;
        fired.add(rule.id); playback.start(rule, scene);
    }
    private boolean prerequisiteSatisfied(Rule rule) {
        return rule.prerequisiteRuleIds.isEmpty() || rule.prerequisiteRuleIds.stream().anyMatch(playback::isRuleActive);
    }
    public void menu() {
        playback.menu(); fired.clear(); carry.clear(); pending.clear();
        samples.clear(); counters.clear(); scene = null; initialized = false; inBattle = false;
        turnTotals.clear(); battleTotals.clear(); turnFired.clear(); battleFired.clear(); objectFired.clear();
    }
}
