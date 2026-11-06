package battleaudio.ui;

import battleaudio.localization.I18n;
import battleaudio.model.Config.Trigger;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Choices come from the game's packaged strings in the current language, never mod-mutated libraries. */
final class FilterOptions {
    static final class Option {
        final String id;
        final String name;
        Option(String id, String name) {
            this.id = id;
            this.name = name == null || name.trim().isEmpty() ? id : name.trim();
        }
        boolean matches(String query) {
            String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            return normalized.isEmpty() || name.toLowerCase(Locale.ROOT).contains(normalized)
                    || id.toLowerCase(Locale.ROOT).contains(normalized);
        }
    }
    private static final Map<String, List<Option>> CACHE = new HashMap<>();
    private FilterOptions() { }
    static List<Option> powers() { return vanilla("powers"); }
    static List<Option> relics() { return vanilla("relics"); }
    static List<Option> events() { return vanilla("events"); }
    static List<Option> enemies() { return vanilla("monsters"); }
    static List<Option> potions() { return vanilla("potions"); }
    static List<Option> encounters(Trigger trigger, Collection<String> selected) {
        return encounters(trigger, selected, null);
    }
    static List<Option> encounters(Trigger trigger, Collection<String> selected, String current) {
        boolean boss = trigger == Trigger.BOSS_START;
        String[][] entries = boss ? new String[][] {
            {"The Guardian", "TheGuardian", "第一层"}, {"Hexaghost", "Hexaghost", "第一层"},
            {"Slime Boss", "SlimeBoss", "第一层"}, {"Automaton", "BronzeAutomaton", "第二层"},
            {"Collector", "TheCollector", "第二层"}, {"Champ", "Champ", "第二层"},
            {"Awakened One", "AwakenedOne", "第三层"}, {"Time Eater", "TimeEater", "第三层"},
            {"Donu and Deca", "Donu|Deca", "第三层"}, {"The Heart", "CorruptHeart", "终幕"}
        } : new String[][] {
            {"Gremlin Nob", "GremlinNob", ""}, {"Lagavulin", "Lagavulin", ""},
            {"3 Sentries", "Sentry", ""}, {"Gremlin Leader", "GremlinLeader", ""},
            {"Slavers", "SlaverBoss|SlaverRed", ""}, {"Book of Stabbing", "BookOfStabbing", ""},
            {"Giant Head", "GiantHead", ""}, {"Nemesis", "Nemesis", ""},
            {"Reptomancer", "Reptomancer", ""}, {"Shield and Spear", "SpireShield|SpireSpear", ""}
        };
        Map<String, String> monsterNames = new HashMap<>();
        for (Option monster : enemies()) monsterNames.put(monster.id, monster.name);
        Map<String, Option> result = new LinkedHashMap<>();
        for (String[] entry : entries) {
            List<String> names = new ArrayList<>();
            for (String id : entry[1].split("\\|")) names.add(monsterNames.getOrDefault(id, id));
            String name = String.join(" + ", names);
            if (entry[0].equals("3 Sentries")) name += " x3";
            if (!entry[2].isEmpty()) name += " (" + I18n.text(entry[2]) + ")";
            result.put(entry[0], new Option(entry[0], name));
        }
        String[] acts = {"Exordium", "TheCity", "TheBeyond", "TheEnding"};
        String[] layers = {"第一层", "第二层", "第三层", "终幕"};
        for (int index = 0; index < acts.length; index++) addRegistered(result, acts[index], boss, boss ? layers[index] : "");
        if (current != null && !Arrays.asList(acts).contains(current)) addRegistered(result, current, boss, "");
        if (selected == null) selected = Collections.emptyList();
        for (String id : selected) if (!result.containsKey(id))
            result.put(id, new Option(id, id + " (" + I18n.text("不可用") + ")"));
        return new ArrayList<>(result.values());
    }
    private static void addRegistered(Map<String, Option> result, String act, boolean boss, String layer) {
        List<String> ids = new ArrayList<>();
        try {
            if (boss) {
                List<String> registered = basemod.BaseMod.getBossIDs(act);
                if (registered != null) ids.addAll(registered);
            } else {
                List<com.megacrit.cardcrawl.monsters.MonsterInfo> registered = basemod.BaseMod.getEliteEncounters(act);
                if (registered != null) for (com.megacrit.cardcrawl.monsters.MonsterInfo info : registered)
                    if (info != null) ids.add(info.name);
            }
        } catch (RuntimeException unavailable) { return; }
        for (String id : ids) {
            if (id == null || id.trim().isEmpty() || result.containsKey(id)) continue;
            String name = basemod.BaseMod.getMonsterName(id);
            if (name == null || name.trim().isEmpty()) name = id;
            if (!layer.isEmpty()) name += " (" + I18n.text(layer) + ")";
            result.put(id, new Option(id, name));
        }
    }
    static List<Option> cards() {
        List<Option> choices=new ArrayList<>();
        for(Option option:vanilla("cards")) {
            com.megacrit.cardcrawl.cards.AbstractCard card=com.megacrit.cardcrawl.helpers.CardLibrary.cards.get(option.id);
            if(card!=null && card.getClass().getName().startsWith("com.megacrit.cardcrawl.cards.")) choices.add(option);
        }
        Map<String,Integer> counts=new HashMap<>();
        for(Option option:choices)counts.put(option.name,counts.getOrDefault(option.name,0)+1);
        List<Option> result=new ArrayList<>();
        for(Option option:choices) {
            String name=option.name;
            if(counts.get(name)>1) {
                String color=com.megacrit.cardcrawl.helpers.CardLibrary.cards.get(option.id).color.name();
                String character=color.equals("RED")?I18n.text("铁甲战士"):color.equals("GREEN")?I18n.text("静默猎手"):color.equals("BLUE")?I18n.text("故障机器人"):color.equals("PURPLE")?I18n.text("观者"):I18n.text("无色");
                name+="（"+character+"）";
            }
            result.add(new Option(option.id,name));
        }
        return result;
    }
    static List<Option> potionLibrary() { return potions(); }
    static List<Option> fixed(String[][] values) {
        List<Option> options = new ArrayList<>();
        for (String[] value : values) options.add(new Option(value[0], value[1]));
        return options;
    }
    private static InputStream vanillaResource(String language,String kind) {
        InputStream input=CardCrawlGame.class.getResourceAsStream("/localization/"+language+"/"+kind+".json");
        return input==null ? CardCrawlGame.class.getResourceAsStream("/localization/eng/"+kind+".json") : input;
    }
    private static synchronized List<Option> vanilla(String kind) {
        String language=I18n.language();
        String cacheKey=language+":"+kind;
        if (CACHE.containsKey(cacheKey)) return CACHE.get(cacheKey);
        List<Option> options = new ArrayList<>();
        // Localization injection changes LocalizedStrings maps; the original JSON stays intact.
        try (InputStream input = vanillaResource(language,kind)) {
            if (input == null) throw new IllegalStateException("Missing vanilla localization: " + kind);
            JsonObject entries = new Gson().fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), JsonObject.class);
            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                JsonElement name = entry.getValue().getAsJsonObject().get("NAME");
                if (name != null && name.isJsonPrimitive()) options.add(new Option(entry.getKey(), name.getAsString()));
            }
        } catch (Exception ex) { throw new IllegalStateException("Cannot load vanilla choices: " + kind, ex); }
        options.sort(Comparator.comparing(option -> option.name, java.text.Collator.getInstance(I18n.locale())));
        List<Option> result = Collections.unmodifiableList(options);
        CACHE.put(cacheKey, result);
        return result;
    }
}
