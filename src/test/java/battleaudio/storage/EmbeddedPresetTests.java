package battleaudio.storage;

import battleaudio.model.Config;
import battleaudio.model.Config.Candidate;
import battleaudio.model.Config.Rule;
import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

public final class EmbeddedPresetTests {
    private static int checks;
    private static final String PREFIX = "/battleaudio/presets/";

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception { run(); }

    public static void run() throws Exception {
        fixtureInstallation();
        existingSettings();
        failedInstallation();
        conflictingFiles();
        packagedPresets();
        System.out.println("PASS: " + checks + " embedded preset checks");
    }

    private static final class Fixture {
        final Map<String, byte[]> files = new HashMap<>();
        final Config config = new Config();
        final AudioLibrary library;
        int opens;

        Fixture() throws IOException {
            library = new AudioLibrary(Files.createTempDirectory("battleaudio-preset-fixture-"), null);
            Rule rule = new Rule();
            rule.name = "fixture";
            rule.triggers.add(Config.Trigger.PLAYER_DEATH);
            Candidate candidate = new Candidate();
            candidate.name = "fixture.wav";
            candidate.sourcePath = "library/fixture.source";
            candidate.cachePath = "cache/fixture.wav";
            candidate.duration = 4;
            candidate.end = 2;
            candidate.volume = .3f;
            candidate.backgroundRatio = 1f;
            rule.candidates.add(candidate);
            Candidate shared = new Gson().fromJson(new Gson().toJson(candidate), Candidate.class);
            shared.id = "shared";
            shared.participate = false;
            rule.candidates.add(shared);
            config.rules.add(rule);
            files.put(PREFIX + "library/fixture.source", new byte[]{1, 2, 3});
            files.put(PREFIX + "cache/fixture.wav", new byte[]{4, 5, 6});
            refresh();
        }

        void refresh() {
            files.put(PREFIX + "settings.json", new Gson().toJson(config).getBytes(StandardCharsets.UTF_8));
        }

        InputStream open(String name) {
            opens++;
            byte[] bytes = files.get(name);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }
    }

    private static void noPublishedFiles(Fixture fixture) throws IOException {
        check(!Files.exists(fixture.library.root().resolve("settings.json")), "Failed install never publishes settings");
        check(!Files.exists(fixture.library.managed("library/fixture.source")), "Failed install never publishes partial source");
        check(!Files.exists(fixture.library.managed("cache/fixture.wav")), "Failed install never publishes partial cache");
        try (Stream<Path> files = Files.list(fixture.library.root())) {
            check(files.noneMatch(file -> file.getFileName().toString().startsWith("presets-")), "Failure cleans staging files");
        }
    }

    private static void expectFailure(Fixture fixture) throws IOException {
        boolean failed = false;
        try { EmbeddedPresets.install(fixture.library, fixture::open); }
        catch (IOException expected) { failed = true; }
        check(failed, "Invalid preset installation fails explicitly");
    }

    private static void fixtureInstallation() throws Exception {
        Fixture fixture = new Fixture();
        check(EmbeddedPresets.install(fixture.library, fixture::open), "Fresh home installs bundled defaults");
        check(fixture.opens == 3, "Shared candidates extract each resource only once");
        check(Arrays.equals(Files.readAllBytes(fixture.library.root().resolve("settings.json")), fixture.files.get(PREFIX + "settings.json")), "Installed configuration keeps exact original bytes");
        Config loaded = fixture.library.load();
        check(loaded.rules.size() == 1 && loaded.rules.get(0).candidates.size() == 2, "All candidates survive installation");
        check(!loaded.rules.get(0).candidates.get(1).participate, "Excluded candidates stay excluded");
        check(loaded.rules.get(0).candidates.get(0).backgroundRatio == 1f, "Candidate background ratios survive installation");
        int opened = fixture.opens;
        check(!EmbeddedPresets.install(fixture.library, fixture::open) && fixture.opens == opened, "Repeated load never reinstalls presets");
        fixture.library.save(new Config());
        check(!EmbeddedPresets.install(fixture.library, fixture::open) && fixture.library.load().rules.isEmpty(), "User can remove every preset without automatic reimport");
    }

    private static void existingSettings() throws Exception {
        Fixture fixture = new Fixture();
        fixture.library.save(fixture.config);
        byte[] original = Files.readAllBytes(fixture.library.root().resolve("settings.json"));
        check(!EmbeddedPresets.install(fixture.library, fixture::open) && fixture.opens == 0, "Existing user settings bypass bundled resource access");
        check(Arrays.equals(original, Files.readAllBytes(fixture.library.root().resolve("settings.json"))), "Existing settings remain untouched");
        Files.write(fixture.library.root().resolve("settings.json"), "broken settings".getBytes(StandardCharsets.UTF_8));
        check(!EmbeddedPresets.install(fixture.library, fixture::open), "Invalid existing settings are never replaced by defaults");
        check(new String(Files.readAllBytes(fixture.library.root().resolve("settings.json")), StandardCharsets.UTF_8).equals("broken settings"), "Invalid existing settings are preserved for recovery");
        Fixture absent = new Fixture();
        absent.files.clear();
        check(!EmbeddedPresets.install(absent.library, absent::open), "Missing optional preset resource preserves empty-home behavior");
        noPublishedFiles(absent);
    }

    private static void failedInstallation() throws Exception {
        Fixture missing = new Fixture();
        missing.files.remove(PREFIX + "cache/fixture.wav");
        expectFailure(missing);
        noPublishedFiles(missing);
        Fixture empty = new Fixture();
        empty.files.put(PREFIX + "cache/fixture.wav", new byte[0]);
        expectFailure(empty);
        noPublishedFiles(empty);
        Fixture invalid = new Fixture();
        invalid.config.rules.get(0).candidates.get(0).backgroundRatio = 2f;
        invalid.refresh();
        expectFailure(invalid);
        noPublishedFiles(invalid);
        Fixture unsafe = new Fixture();
        unsafe.config.rules.get(0).candidates.get(0).sourcePath = "../escaped.source";
        unsafe.refresh();
        expectFailure(unsafe);
        noPublishedFiles(unsafe);
    }

    private static void conflictingFiles() throws Exception {
        Fixture conflict = new Fixture();
        Path original = conflict.library.managed("library/fixture.source");
        Files.write(original, new byte[]{99});
        expectFailure(conflict);
        check(Arrays.equals(Files.readAllBytes(original), new byte[]{99}), "Conflicting existing audio is never overwritten");
        check(!Files.exists(conflict.library.root().resolve("settings.json")), "Conflict cannot activate partially installed defaults");
        check(!Files.exists(conflict.library.managed("cache/fixture.wav")), "Conflict check precedes all asset publication");
        Fixture matching = new Fixture();
        Files.write(matching.library.managed("library/fixture.source"), matching.files.get(PREFIX + "library/fixture.source"));
        check(EmbeddedPresets.install(matching.library, matching::open), "Interrupted installation can reuse matching assets");
    }

    private static void packagedPresets() throws Exception {
        AudioLibrary library = new AudioLibrary(Files.createTempDirectory("battleaudio-public-package-"), null);
        Config loaded = library.load();
        check(loaded.rules.isEmpty(), "Public package starts without bundled audio");
        check(!Files.exists(library.root().resolve("settings.json")), "Fresh public package does not create personal settings");
    }

}
