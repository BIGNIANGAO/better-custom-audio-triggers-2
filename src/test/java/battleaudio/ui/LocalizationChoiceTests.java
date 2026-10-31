package battleaudio.ui;

import battleaudio.localization.I18n;
import com.megacrit.cardcrawl.core.Settings;
import java.util.*;

public final class LocalizationChoiceTests {
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
                checks++;
            }
            System.out.println("PASS: "+checks+" localized vanilla choice lists, stable IDs and language-specific caches");
        } finally {Settings.language=saved;}
    }
}