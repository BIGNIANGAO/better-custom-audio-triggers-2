package battleaudio.ui;

import battleaudio.localization.I18n;
import battleaudio.model.Config.Trigger;
import com.megacrit.cardcrawl.core.Settings;
import java.util.*;

public final class LocalizationChoiceTests {
    public static void main(String[] args) { run(); }
    public static void run() {
        Settings.GameLanguage saved=Settings.language;
        try {
            Set<String> ids=null;String english=null;int checks=0;
            for(String language:I18n.languages()) {
                Settings.language=Settings.GameLanguage.valueOf(language.toUpperCase(Locale.ROOT));
                List<FilterOptions.Option> powers=FilterOptions.powers();Set<String> current=new HashSet<>();
                for(FilterOptions.Option option:powers){current.add(option.id);if(option.name.isEmpty())throw new AssertionError(language+" empty vanilla name");}
                if(ids==null)ids=current;
                if(!current.contains("Focus") || !current.contains("Strength"))throw new AssertionError(language+" loses vanilla filter IDs");
                FilterOptions.Option strength=powers.stream().filter(option->option.id.equals("Strength")).findFirst().get();
                if(language.equals("eng"))english=strength.name;
                if(language.equals("zhs") && strength.name.equals(english))throw new AssertionError("Chinese filters reused English cache");
                if(!strength.matches(strength.name) || !strength.matches("Strength"))throw new AssertionError(language+" localized and internal-ID search");
                if(FilterOptions.relics().isEmpty() || FilterOptions.events().isEmpty() || FilterOptions.potions().isEmpty())throw new AssertionError(language+" empty vanilla choices");
                List<FilterOptions.Option> bosses=FilterOptions.encounters(Trigger.BOSS_START,Collections.emptyList());
                List<FilterOptions.Option> elites=FilterOptions.encounters(Trigger.ELITE_START,Collections.emptyList());
                Set<String> bossIds=new HashSet<>(), eliteIds=new HashSet<>();
                for(FilterOptions.Option option:bosses)bossIds.add(option.id);
                for(FilterOptions.Option option:elites)eliteIds.add(option.id);
                if(!bossIds.containsAll(Arrays.asList("The Guardian","Hexaghost","Slime Boss","Automaton","Collector","Champ",
                        "Awakened One","Time Eater","Donu and Deca","The Heart")))throw new AssertionError(language+" missing vanilla boss encounter");
                if(!eliteIds.containsAll(Arrays.asList("Gremlin Nob","Lagavulin","3 Sentries","Gremlin Leader","Slavers",
                        "Book of Stabbing","Giant Head","Nemesis","Reptomancer","Shield and Spear")))throw new AssertionError(language+" missing vanilla elite encounter");
                FilterOptions.Option hex=bosses.stream().filter(option->option.id.equals("Hexaghost")).findFirst().get();
                if(!hex.name.contains(I18n.text("第一层")))throw new AssertionError(language+" missing first-layer suffix");
                if(language.equals("zhs") && !hex.name.contains("六火亡魂"))throw new AssertionError("Chinese boss name is not localized");
                if(!hex.matches("Hexaghost") || !hex.matches(hex.name))throw new AssertionError(language+" boss search does not match");
                checks++;
            }
            basemod.BaseMod.addBoss("Exordium","battleaudio:test-boss","","");
            basemod.BaseMod.addEliteEncounter("TheCity",new com.megacrit.cardcrawl.monsters.MonsterInfo("battleaudio:test-elite",1f));
            if(FilterOptions.encounters(Trigger.BOSS_START,Collections.emptyList()).stream().noneMatch(option->option.id.equals("battleaudio:test-boss")))
                throw new AssertionError("Registered third-party boss not listed");
            if(FilterOptions.encounters(Trigger.ELITE_START,Collections.emptyList()).stream().noneMatch(option->option.id.equals("battleaudio:test-elite")))
                throw new AssertionError("Registered third-party elite not listed");
            if(FilterOptions.encounters(Trigger.BOSS_START,Collections.singleton("missing:boss")).stream().noneMatch(option->option.id.equals("missing:boss")))
                throw new AssertionError("Unavailable selected encounter was removed from the list");
            java.lang.reflect.Field eliteRegistry=basemod.BaseMod.class.getDeclaredField("customEliteEncounters");eliteRegistry.setAccessible(true);
            java.lang.reflect.Field bossRegistry=basemod.BaseMod.class.getDeclaredField("customBosses");bossRegistry.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String,Object> elites=(Map<String,Object>)eliteRegistry.get(null);
            @SuppressWarnings("unchecked") Map<String,Object> bosses=(Map<String,Object>)bossRegistry.get(null);
            Object oldElite=elites.put("TheEnding",null),oldBoss=bosses.put("TheEnding",null);
            try {
                if(FilterOptions.encounters(Trigger.BOSS_START,null).stream().noneMatch(option->option.id.equals("The Heart"))
                        || FilterOptions.encounters(Trigger.ELITE_START,null).stream().noneMatch(option->option.id.equals("Shield and Spear")))
                    throw new AssertionError("Invalid third-party registry entry hid vanilla battles");
            } finally {
                if(oldElite==null)elites.remove("TheEnding");else elites.put("TheEnding",oldElite);
                if(oldBoss==null)bosses.remove("TheEnding");else bosses.put("TheEnding",oldBoss);
            }
            System.out.println("PASS: "+checks+" localized vanilla choice lists, stable IDs and language-specific caches");
        } catch(ReflectiveOperationException ex) { throw new AssertionError(ex); }
        finally {Settings.language=saved;}
    }
}
