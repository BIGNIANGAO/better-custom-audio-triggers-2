package battleaudio.model;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Keeps existing single-ID saves valid while allowing an OR selection of multiple IDs. */
public final class IdSelection {
    private IdSelection() { }
    public static Set<String> parse(String value) {
        Set<String> ids = new LinkedHashSet<>();
        if (value != null) Arrays.stream(value.split(",")).map(String::trim)
                .filter(id -> !id.isEmpty()).forEach(ids::add);
        return ids;
    }
    public static boolean contains(String selection, String id) {
        return id != null && parse(selection).contains(id);
    }
    public static String encode(Set<String> ids) { return String.join(",", ids); }
}
