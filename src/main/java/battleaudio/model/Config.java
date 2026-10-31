package battleaudio.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.EnumMap;
import java.util.Map;

public final class Config {
    public int schemaVersion = 4;
    public float backgroundRatio = 0.2f;
    public List<Rule> rules = new ArrayList<>();
    public List<MusicPack> packs = new ArrayList<>();

    public enum Trigger {
        BATTLE_START, NORMAL_BATTLE_START, FIRST_BATTLE, BOSS_START, ELITE_START,
        BATTLE_VICTORY, NORMAL_BATTLE_VICTORY, ELITE_VICTORY, BOSS_VICTORY, PLAYER_DEATH,
        LOW_HEALTH, HIGH_HEALTH, HEAL_THRESHOLD, HP_LOSS, BLOCK_LOSS,
        POSITIVE_POWER, CARD_UPGRADE,
        TURN_START, TURN_END, PLAY_ATTACK, PLAY_SKILL, PLAY_POWER, CARDS_PLAYED_TURN,
        DRAW_CARD, DISCARD_CARD, EXHAUST_CARD, RESHUFFLE, RETURN_DISCARD_CARD, CREATE_CARD, GAIN_CURSE,
        GAIN_ENERGY, ENERGY_THRESHOLD, ENERGY_GAIN_THRESHOLD, GAIN_BLOCK, BLOCK_THRESHOLD,
        GAIN_SPECIFIED_POWER, POWER_THRESHOLD, POWER_DECREASE, POWER_REMOVED, RECEIVE_DEBUFF, GAIN_GOLD, SPEND_GOLD, USE_POTION, GAIN_RELIC,
        KILL_ENEMY, ENEMY_LOW_HEALTH, APPLY_ENEMY_POWER,
        EXHAUSTED_TURN, SELF_DAMAGE, STRENGTH_THRESHOLD, STRENGTH_INCREASE, STRENGTH_DECREASE,
        STRENGTH_NEGATIVE, GAIN_REGEN, REGEN_HEAL,
        DISCARDED_TURN, CREATE_SHIV, PLAY_SHIV, SHIV_PLAYED_TURN, SHIV_DAMAGE, APPLY_POISON, POISON_THRESHOLD, POISON_DAMAGE,
        POISON_KILL, POISON_DECREASE, GAIN_INTANGIBLE, INTANGIBLE_MITIGATE,
        DEXTERITY_INCREASE, DEXTERITY_DECREASE,
        CHANNEL_ORB, EVOKE_ORB, ORB_PASSIVE, ORB_PASSIVE_BLOCK, ORB_PASSIVE_ENERGY, ORB_PASSIVE_DAMAGE,
        ORB_SLOTS_FULL, ORB_SLOTS_UNFULL, CHANNEL_ORBS_TURN, GAIN_ORB_SLOT, FOCUS_THRESHOLD, FOCUS_DECREASE,
        FOCUS_NEGATIVE, FOCUS_INCREASE, DARK_ACCUMULATE, DARK_DAMAGE_THRESHOLD, DARK_EVOKE_DAMAGE, PLASMA_EVOKE_ENERGY,
        SHOP_OPEN, SHOP_BUY_CARD, SHOP_BUY_RELIC, SHOP_BUY_POTION, SHOP_REMOVE_CARD,
        COMBAT_CARD_REWARD_OPEN, COMBAT_CARD_REWARD_PICK, COMBAT_CARD_REWARD_SKIP,
        CAMPFIRE_REST, CAMPFIRE_SMITH, CHEST_OPEN, DAMAGE_THRESHOLD,
        ENTER_WRATH, ENTER_CALM, ENTER_DIVINITY, EXIT_STANCE, STANCE_CHANGE, STANCE_CHANGES_TURN,
        CALM_GAIN_BLOCK, CALM_EXIT_ENERGY, WRATH_DAMAGE, DIVINITY_DAMAGE,
        MANTRA_THRESHOLD, MANTRA_SPENT_DIVINITY, SCRY, SCRY_CARDS, SCRY_DISCARD_CARD, SCRY_KEEP_CARD,
        RETAIN_CARD, PLAY_RETAINED_CARD,
        EVENT_OPEN, EVENT_OPTION_COMPLETED, MAP_NODE_ENTER, ACT_ENTER,
        COMBAT_CARD_REWARD_OBTAIN, REMOVE_DECK_CARD, COPY_DECK_CARD, TRANSFORM_DECK_CARD,
        MANTRA_INCREASE, ATTACK_MULTI_KILL, LOW_HEALTH_FIRST_BLOCK
    }

    public enum NextBattle { AFTER_DAMAGE, IMMEDIATE }

    public enum RoomBehavior {
        STOP_ON_ROOM_END, CONTINUE_ACROSS_ROOMS, STOP_AND_RESTART_NEXT_BATTLE,
        STOP_AND_RESTART_AFTER_DAMAGE, NEVER_STOP
    }

    public enum PlaybackMode { ONCE, LIMITED, INFINITE }
    public enum RandomMode { RANDOM, AVOID_REPEAT }
    public enum RetriggerMode { REPLACE, IGNORE, OVERLAP }
    public enum Cadence { EVERY, FIRST_TURN, FIRST_BATTLE, FIRST_OBJECT }

    /** All optional; missing fields in schema-3 files retain legacy trigger behavior. */
    public static final class EventFilter {
        public String sourceType = "", sourceId = "", targetType = "", targetId = "";
        public String enemyType = "";
        public Boolean lastEnemy;
        public String cardId = "", cardType = "", sourcePile = "", targetPile = "";
        public String previousState = "", nextState = "";
        public String evokeCause = "";
        public int orbPosition;
        public int cardCost = Integer.MIN_VALUE;
        public Boolean upgraded, exhaust, temporary;
        public int minAmount, minTurnTotal, minCurrentValue;
        public boolean minAmountEnabled, minTurnTotalEnabled, minCurrentValueEnabled;
        public boolean crossingOnly;
        public Cadence cadence = Cadence.EVERY;
        public boolean maxOncePerTurn, maxOncePerBattle;
    }

    public static final class Threshold {
        public boolean percent;
        public double value = 30;

        public double at(int maxHealth) { return percent ? maxHealth * value / 100.0 : value; }
    }

    public static final class StopConditions {
        public EnumSet<Trigger> triggers = EnumSet.noneOf(Trigger.class);
        public Threshold low = new Threshold();
        public Threshold high = new Threshold();
        public Threshold heal = new Threshold();
        public Threshold additionalHigh;
        public Map<Trigger, Parameter> parameters = new EnumMap<>(Trigger.class);
        public Map<Trigger, EventFilter> filters = new EnumMap<>(Trigger.class);
        public boolean anyPower = true;
        public boolean manualPowerIds;
        public List<String> powerIds = new ArrayList<>();
        public Parameter parameter(Trigger trigger) { return parameters.computeIfAbsent(trigger, key -> {
            Parameter parameter = new Parameter();
            if (key == Trigger.ENERGY_GAIN_THRESHOLD || key == Trigger.SCRY_CARDS) parameter.value = 1;
            return parameter;
        }); }
        public EventFilter filter(Trigger trigger) { return filters.computeIfAbsent(trigger, key -> new EventFilter()); }
        public void mergeHealthStop() {
            if (!triggers.remove(Trigger.HEAL_THRESHOLD)) return;
            if (!triggers.contains(Trigger.HIGH_HEALTH)) high = heal;
            else if (high.percent == heal.percent) {
                if (heal.value < high.value) high = heal;
            } else additionalHigh = heal;
            triggers.add(Trigger.HIGH_HEALTH);
            parameters.remove(Trigger.HEAL_THRESHOLD); filters.remove(Trigger.HEAL_THRESHOLD);
        }
        public Rule asRule(String id) {
            Rule view = new Rule(); view.id = id;
            view.triggers = triggers; view.low = low; view.high = high; view.heal = heal;
            view.parameters = parameters; view.filters = filters;
            view.anyPower = anyPower; view.manualPowerIds = manualPowerIds; view.powerIds = powerIds;
            return view;
        }
    }

    public static final class Parameter {
        public double value = 3;
        public boolean percent;
        public boolean any = true;
        public String id = "";
        public double at(double maximum) { return percent ? maximum * value / 100.0 : value; }
    }

    /** One playable file belonging to a rule. */
    public static final class Candidate {
        public String id = UUID.randomUUID().toString();
        public String name = "待导入音频";
        public String sourcePath;
        public String cachePath;
        public String error;
        public double duration;
        public double start;
        public double end;
        public float volume = 0.3f;
        public float backgroundRatio = 0.2f;
        public boolean participate = true;
        public boolean playable() { return error == null && cachePath != null && duration > 0; }
    }

    public static final class Rule {
        /** Owning package ID. */
        public String packId;
        /** Stable source rule ID for package update switches. */
        public String upstreamRuleId;
        public String id = UUID.randomUUID().toString();
        public String name = "音频";
        public boolean enabled = true;
        public EnumSet<Trigger> triggers = EnumSet.noneOf(Trigger.class);
        public Threshold low = new Threshold();
        public Threshold high = new Threshold();
        public Threshold heal = new Threshold();
        public Threshold stop = new Threshold();
        /** Kept for loading schema 1 files; it is no longer exposed or applied. */
        public boolean stopAboveHealth = false;
        public NextBattle nextBattle = NextBattle.AFTER_DAMAGE;
        /** Kept for schema 1 compatibility. New files use playbackMode. */
        public boolean loop = true;
        public PlaybackMode playbackMode = PlaybackMode.ONCE;
        public int loopCount = 1;
        public RoomBehavior roomBehavior = RoomBehavior.STOP_ON_ROOM_END;
        public boolean anyPower = true;
        public boolean manualPowerIds;
        public List<String> powerIds = new ArrayList<>();
        public Map<Trigger, Parameter> parameters = new EnumMap<>(Trigger.class);
        public Map<Trigger, EventFilter> filters = new EnumMap<>(Trigger.class);
        public List<Candidate> candidates = new ArrayList<>();
        public RandomMode randomMode = RandomMode.AVOID_REPEAT;
        public RetriggerMode retriggerMode = RetriggerMode.REPLACE;
        public float triggerProbability = 1f;
        public StopConditions stopConditions = new StopConditions();
        public List<String> pauseWhenRuleIds = new ArrayList<>();
        public List<String> prerequisiteRuleIds = new ArrayList<>();
        public Candidate audio() {
            if (candidates == null) candidates = new ArrayList<>();
            if (candidates.isEmpty()) candidates.add(new Candidate());
            return candidates.get(0);
        }

        public Parameter parameter(Trigger trigger) { return parameters.computeIfAbsent(trigger, key -> {
            Parameter parameter = new Parameter();
            if (key == Trigger.ENERGY_GAIN_THRESHOLD || key == Trigger.SCRY_CARDS) parameter.value = 1;
            return parameter;
        }); }
        public EventFilter filter(Trigger trigger) { return filters.computeIfAbsent(trigger, key -> new EventFilter()); }

        public void validateHealthStops() {
            if (!triggers.contains(Trigger.LOW_HEALTH)) return;
            for (Trigger trigger : new Trigger[] {Trigger.HIGH_HEALTH, Trigger.HEAL_THRESHOLD}) {
                if (!stopConditions.triggers.contains(trigger)) continue;
                Threshold threshold = trigger == Trigger.HIGH_HEALTH ? stopConditions.high : stopConditions.heal;
                if (threshold.percent != low.percent)
                    throw new IllegalArgumentException("启动和停止血量阈值必须使用相同单位");
                if (threshold.value <= low.value)
                    throw new IllegalArgumentException("停止阈值必须大于低血量启动阈值");
            }
            Threshold additional = stopConditions.additionalHigh;
            if (additional != null && stopConditions.triggers.contains(Trigger.HIGH_HEALTH)) {
                if (additional.percent != low.percent)
                    throw new IllegalArgumentException("启动和停止血量阈值必须使用相同单位");
                if (additional.value <= low.value)
                    throw new IllegalArgumentException("停止阈值必须大于低血量启动阈值");
            }
        }

        public boolean playable() {
            if (candidates != null && !candidates.isEmpty()) for (Candidate c : candidates) if (c != null && c.playable()) return true;
            return false;
        }
    }

    public Config copy() { return new Gson().fromJson(new Gson().toJson(this), Config.class); }

    public void refreshPackMembership() {
        if (packs == null) packs = new ArrayList<>();
        for (MusicPack pack : packs) pack.ruleIds.clear();
        for (Rule rule : rules) if (rule != null && rule.packId != null)
            for (MusicPack pack : packs) if (pack.id.equals(rule.packId)) { pack.ruleIds.add(rule.id); break; }
    }

    public MusicPack pack(String id) {
        if (id == null || packs == null) return null;
        for (MusicPack pack : packs) if (pack.id.equals(id)) return pack;
        return null;
    }

    public boolean effectivelyEnabled(Rule rule) {
        if (rule == null || !rule.enabled) return false;
        MusicPack pack = pack(rule.packId);
        return pack == null || pack.enabled;
    }

    public MusicPack ensureLocalPack() {
        return ensureLocalPack("我的音乐包");
    }

    public MusicPack ensureLocalPack(String defaultName) {
        if (packs == null) packs = new ArrayList<>();
        for (MusicPack existing : packs) if (existing.source == MusicPack.Source.LOCAL) return existing;
        MusicPack pack = new MusicPack();
        pack.name = defaultName == null || defaultName.trim().isEmpty() ? "我的音乐包" : defaultName;
        packs.add(pack);
        return pack;
    }

    public MusicPack ensurePackFor(Rule rule) {
        if (rule.packId == null || pack(rule.packId) == null) rule.packId = ensureLocalPack().id;
        return pack(rule.packId);
    }

    public static Config fromJson(JsonObject json) {
        int version = json.has("schemaVersion") ? json.get("schemaVersion").getAsInt() : 1;
        if (version < 1 || version > 4) throw new IllegalArgumentException("不支持此版本的音频配置");
        if (!json.has("rules") || !json.get("rules").isJsonArray()) throw new IllegalArgumentException("规则列表缺失");
        if (version <= 2) for (JsonElement element : json.getAsJsonArray("rules")) {
            JsonObject rule = element.getAsJsonObject();
            JsonObject candidate = new JsonObject();
            if (rule.has("name")) candidate.addProperty("name", rule.get("name").getAsString());
            for (String key : new String[]{"sourcePath","cachePath","error","duration","start","end","volume"}) {
                if (rule.has(key)) candidate.add(key, rule.remove(key));
            }
            JsonArray candidates = new JsonArray(); candidates.add(candidate); rule.add("candidates", candidates);
            rule.addProperty("retriggerMode", "OVERLAP");
            // Older configurations omitted the default low-health condition in some exporters.
            if (!rule.has("triggers")) { JsonArray triggers = new JsonArray(); triggers.add(new com.google.gson.JsonPrimitive("LOW_HEALTH")); rule.add("triggers", triggers); }
        }
        float legacyBackgroundRatio = json.has("backgroundRatio") ? json.get("backgroundRatio").getAsFloat() : .2f;
        for (JsonElement element : json.getAsJsonArray("rules")) {
            JsonObject rule = element.getAsJsonObject();
            if (rule.has("candidates") && rule.get("candidates").isJsonArray()) for (JsonElement candidate : rule.getAsJsonArray("candidates")) {
                if (candidate.isJsonObject() && !candidate.getAsJsonObject().has("backgroundRatio"))
                    candidate.getAsJsonObject().addProperty("backgroundRatio", legacyBackgroundRatio);
            }
            if (!rule.has("filters") || !rule.get("filters").isJsonObject()) continue;
            for (Map.Entry<String, JsonElement> entry : rule.getAsJsonObject("filters").entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject filter = entry.getValue().getAsJsonObject();
                inferEnabled(filter, "minAmount", "minAmountEnabled");
                inferEnabled(filter, "minTurnTotal", "minTurnTotalEnabled");
                inferEnabled(filter, "minCurrentValue", "minCurrentValueEnabled");
            }
        }
        json.addProperty("schemaVersion", version);
        Config config = new Gson().fromJson(json, Config.class); config.migrate(); config.validate(); return config;
    }

    private static void inferEnabled(JsonObject filter, String value, String enabled) {
        if (!filter.has(enabled)) filter.addProperty(enabled, filter.has(value) && filter.get(value).getAsInt() > 0);
    }

    public void migrate() {
        if (rules == null) throw new IllegalArgumentException("规则列表缺失");
        if (schemaVersion <= 2) {
            for (Rule rule : rules) {
                if (rule == null) continue;
                if (schemaVersion == 1) {
                    rule.playbackMode = rule.loop ? PlaybackMode.INFINITE : PlaybackMode.ONCE;
                    rule.roomBehavior = rule.nextBattle == NextBattle.IMMEDIATE
                            ? RoomBehavior.STOP_AND_RESTART_NEXT_BATTLE : RoomBehavior.STOP_AND_RESTART_AFTER_DAMAGE;
                }
                rule.retriggerMode = RetriggerMode.OVERLAP;
                if (rule.randomMode == null) rule.randomMode = RandomMode.AVOID_REPEAT;
                rule.stopAboveHealth = false;
            }
            schemaVersion = 3;
        }
        if (packs == null) packs = new ArrayList<>();
        if (schemaVersion <= 3 && packs.isEmpty() && !rules.isEmpty()) {
            MusicPack migrated = new MusicPack();
            migrated.id = "legacy-migrated";
            migrated.name = "从旧版设置迁移";
            migrated.source = MusicPack.Source.MIGRATED;
            migrated.version = "legacy-schema-3";
            for (Rule rule : rules) if (rule != null) { rule.packId = migrated.id; migrated.ruleIds.add(rule.id); }
            packs.add(migrated);
        }
        schemaVersion = 4;
        for (Rule rule : rules) if (rule != null && rule.stopConditions != null) rule.stopConditions.mergeHealthStop();
    }

    public void validate() {
        if (schemaVersion < 1 || schemaVersion > 4) throw new IllegalArgumentException("不支持此版本的音频配置");
        if (!Float.isFinite(backgroundRatio) || backgroundRatio < 0 || backgroundRatio > 1)
            throw new IllegalArgumentException("背景音乐保留比例必须在 0% 到 100% 之间");
        if (rules == null) throw new IllegalArgumentException("音频列表缺失");
        if (packs == null) throw new IllegalArgumentException("音乐包列表缺失");
        java.util.Set<String> packIds = new java.util.HashSet<>();
        for (MusicPack pack : packs) {
            if (pack == null || pack.id == null || !pack.id.matches("[A-Za-z0-9._-]{1,128}") || pack.id.contains("..") || !packIds.add(pack.id)
                    || pack.contentId == null || !pack.contentId.matches("[A-Za-z0-9._-]{1,128}") || pack.contentId.contains("..")
                    || pack.name == null || pack.name.trim().isEmpty() || pack.source == null || pack.version == null || pack.version.trim().isEmpty()
                    || pack.ruleIds == null || pack.ownedFiles == null)
                throw new IllegalArgumentException("音乐包无效或重复");
            if (pack.source == MusicPack.Source.SUBSCRIBED && (pack.workshopId == null || pack.workshopId.trim().isEmpty()))
                throw new IllegalArgumentException("订阅音乐包缺少创意工坊 ID");
            for (MusicPack.OwnedFile file : pack.ownedFiles)
                if (file == null || file.path == null || file.sha256 == null || !file.sha256.matches("[0-9a-f]{64}") || file.bytes < 0)
                    throw new IllegalArgumentException("音乐包文件归属记录无效");
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Rule rule : rules) {
            if (rule == null || rule.id == null || !ids.add(rule.id) || rule.triggers == null
                    || rule.nextBattle == null || rule.powerIds == null || rule.parameters == null || rule.playbackMode == null || rule.roomBehavior == null
                    || rule.candidates == null || rule.name == null || rule.randomMode == null || rule.retriggerMode == null || rule.filters == null
                    || !Float.isFinite(rule.triggerProbability) || rule.triggerProbability < 0 || rule.triggerProbability > 1
                    || rule.stopConditions == null || rule.stopConditions.triggers == null || rule.stopConditions.parameters == null
                    || rule.stopConditions.filters == null || rule.stopConditions.powerIds == null || rule.pauseWhenRuleIds == null
                    || rule.prerequisiteRuleIds == null || rule.packId != null && !packIds.contains(rule.packId))
                throw new IllegalArgumentException("音频规则无效或重复");
            validateConditions(rule);
            validateConditions(rule.stopConditions.asRule(rule.id + ":stop"));
            if (rule.stopConditions.additionalHigh != null) {
                Threshold additional = rule.stopConditions.additionalHigh;
                if (!Double.isFinite(additional.value) || additional.value < 0 || additional.percent && additional.value > 100)
                    throw new IllegalArgumentException("血量停止阈值无效");
            }
            rule.validateHealthStops();
            java.util.Set<String> candidateIds = new java.util.HashSet<>();
            for (Candidate candidate : rule.candidates) {
                if (candidate != null && (!Float.isFinite(candidate.backgroundRatio) || candidate.backgroundRatio < 0 || candidate.backgroundRatio > 1))
                    throw new IllegalArgumentException("候选音频的背景音乐保留比例必须在 0% 到 100% 之间");
                if (candidate == null || candidate.id == null || candidate.name == null || !candidateIds.add(candidate.id) || !Float.isFinite(candidate.volume) || candidate.volume < 0 || candidate.volume > 1
                        || !Double.isFinite(candidate.duration) || !Double.isFinite(candidate.start) || !Double.isFinite(candidate.end)
                        || candidate.duration < 0 || candidate.start < 0 || candidate.end < 0)
                    throw new IllegalArgumentException("候选音频无效");
                if (candidate.playable() && (candidate.start < 0 || candidate.end <= candidate.start || candidate.end > candidate.duration + .001))
                    throw new IllegalArgumentException("候选音频片段无效");
            }
            if (rule.loopCount < 1) throw new IllegalArgumentException("循环次数必须为正整数");

        }
        validatePauseRelations();
        validatePrerequisites();
    }
    public void validatePrerequisites() {
        java.util.Set<String> known = new java.util.HashSet<>(), reachable = new java.util.HashSet<>();
        for (Rule rule : rules) known.add(rule.id);
        for (Rule rule : rules) {
            if (rule.prerequisiteRuleIds == null) throw new IllegalArgumentException("前置规则选择无效");
            if (rule.prerequisiteRuleIds.isEmpty()) reachable.add(rule.id);
            for (String id : rule.prerequisiteRuleIds) {
                if (id == null || id.equals(rule.id) || !known.contains(id))
                    throw new IllegalArgumentException("前置条件只能选择其他存在的音频规则");
            }
        }
        boolean changed;
        do {
            changed = false;
            for (Rule rule : rules) if (!reachable.contains(rule.id) && rule.prerequisiteRuleIds.stream().anyMatch(reachable::contains)) {
                reachable.add(rule.id); changed = true;
            }
        } while (changed);
        if (reachable.size() != known.size()) throw new IllegalArgumentException("前置规则循环依赖，无法自动启动");
    }
    public void validatePauseRelations() {
        Map<String, Rule> byId = new java.util.HashMap<>();
        for (Rule rule : rules) byId.put(rule.id, rule);
        for (Rule rule : rules) {
            if (rule.pauseWhenRuleIds == null) throw new IllegalArgumentException("暂停规则选择无效");
            for (String id : rule.pauseWhenRuleIds) {
                if (id == null || id.equals(rule.id) || !byId.containsKey(id))
                    throw new IllegalArgumentException("请选择其他存在的音频规则作为暂停条件");
            }
        }
        java.util.Set<String> visiting = new java.util.HashSet<>(), completed = new java.util.HashSet<>();
        for (Rule rule : rules) validatePausePath(rule, byId, visiting, completed);
    }
    private static void validatePausePath(Rule rule, Map<String, Rule> byId, java.util.Set<String> visiting, java.util.Set<String> completed) {
        if (completed.contains(rule.id)) return;
        if (!visiting.add(rule.id)) throw new IllegalArgumentException("规则之间的暂停关系不能形成循环");
        for (String id : rule.pauseWhenRuleIds) validatePausePath(byId.get(id), byId, visiting, completed);
        visiting.remove(rule.id); completed.add(rule.id);
    }

    private static void validateConditions(Rule rule) {
        if (rule.triggers == null || rule.powerIds == null || rule.parameters == null || rule.filters == null)
            throw new IllegalArgumentException("条件设置无效");
        for (Threshold threshold : new Threshold[] {rule.low, rule.high, rule.heal}) {
            if (threshold == null || !Double.isFinite(threshold.value) || threshold.value < 0
                    || threshold.percent && threshold.value > 100)
                throw new IllegalArgumentException("血量阈值无效");
        }
        for (Trigger trigger : rule.triggers) {
            if (Events.requiresId(trigger) && (rule.parameters.get(trigger) == null
                    || rule.parameters.get(trigger).id == null || rule.parameters.get(trigger).id.trim().isEmpty()))
                throw new IllegalArgumentException("请选择指定的状态效果或姿态");
        }
        for (Map.Entry<Trigger, Parameter> entry : rule.parameters.entrySet()) {
            Parameter parameter = entry.getValue();
            if (entry.getKey() == null || parameter == null || parameter.id == null
                    || !Double.isFinite(parameter.value) || parameter.value < 0 || (parameter.percent && parameter.value > 100))
                throw new IllegalArgumentException("条件参数无效");
            if ((Events.isCounter(entry.getKey()) || entry.getKey() == Trigger.ENERGY_GAIN_THRESHOLD)
                    && (parameter.value < 1 || parameter.value != Math.floor(parameter.value)))
                throw new IllegalArgumentException("次数或单次能量阈值必须为正整数");
            if (rule.triggers.contains(entry.getKey()) && Events.requiresId(entry.getKey()) && parameter.id.trim().isEmpty())
                throw new IllegalArgumentException("请选择指定的状态效果或姿态");
        }
        for (Map.Entry<Trigger, EventFilter> entry : rule.filters.entrySet()) {
            EventFilter filter = entry.getValue();
            if (entry.getKey() == null || filter == null || filter.cadence == null
                    || filter.sourceType == null || filter.sourceId == null || filter.targetType == null || filter.targetId == null
                    || filter.cardId == null || filter.cardType == null || filter.sourcePile == null || filter.targetPile == null
                    || filter.enemyType == null || filter.evokeCause==null || filter.orbPosition<0
                    || filter.previousState == null || filter.nextState == null || filter.minAmount < 0
                    || filter.minTurnTotal < 0 || filter.minCurrentValue < 0
                    || filter.crossingOnly && filter.minCurrentValue == 0
                    || filter.cardCost != Integer.MIN_VALUE && filter.cardCost < -1)
                throw new IllegalArgumentException("事件筛选无效");
        }
    }
}
