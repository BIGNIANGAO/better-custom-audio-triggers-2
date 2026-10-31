package battleaudio.model;

import battleaudio.localization.I18n;

import com.google.gson.Gson;
import java.util.*;
import battleaudio.model.Config.*;

public final class RuleCopies {
    private RuleCopies() { }
    public static String uniqueName(String base, Collection<String> names) {
        if (!names.contains(base)) return base;
        for (int i=2;;i++) { String name=base + " (" + i + ")"; if (!names.contains(name)) return name; }
    }
    public static Rule copy(Rule source, List<Rule> rules) {
        Rule result = new Gson().fromJson(new Gson().toJson(source), Rule.class);
        result.id = UUID.randomUUID().toString();
        List<String> names = new ArrayList<>(); for (Rule rule : rules) names.add(rule.name);
        result.name = uniqueName(source.name + I18n.text(" 副本"), names);
        for (Candidate audio : result.candidates) audio.id = UUID.randomUUID().toString();
        return result;
    }
    public static Candidate copy(Candidate source, List<Candidate> candidates) {
        Candidate result = new Gson().fromJson(new Gson().toJson(source), Candidate.class);
        result.id = UUID.randomUUID().toString();
        List<String> names = new ArrayList<>(); for (Candidate audio : candidates) names.add(audio.name);
        result.name = uniqueName(source.name + I18n.text(" 副本"), names); return result;
    }
}
