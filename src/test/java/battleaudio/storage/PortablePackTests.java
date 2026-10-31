package battleaudio.storage;

import battleaudio.model.Config;
import battleaudio.model.Config.Candidate;
import battleaudio.model.Config.Rule;
import battleaudio.model.Config.Trigger;
import battleaudio.model.MusicPack;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class PortablePackTests {
    private static final Gson GSON = new Gson();
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void write(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent()); Files.write(path, GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }
    private static WorkshopPackageCatalog.Bundle bundle(String name) {
        WorkshopPackageCatalog.Bundle result = new WorkshopPackageCatalog.Bundle();
        result.schemaVersion = 1; result.packageId = "portable-fixture"; result.workshopId = "";
        result.name = name; result.version = "1"; result.rules = new ArrayList<>(); result.files = new ArrayList<>();
        Rule rule = new Rule(); rule.id = "original-rule"; rule.name = "original"; rule.triggers = EnumSet.of(Trigger.PLAYER_DEATH);
        Candidate candidate = new Candidate(); candidate.id = "original-audio"; candidate.name = "tone.wav";
        candidate.sourcePath = "audio/tone.wav"; candidate.cachePath = "audio/tone.wav"; candidate.duration = 1; candidate.end = 1;
        rule.candidates.add(candidate); result.rules.add(rule); return result;
    }
    private static void item(Path directory, String name) throws Exception {
        Files.createDirectories(directory.resolve("audio")); Files.write(directory.resolve("audio/tone.wav"), new byte[] {1,2,3,4});
        write(directory.resolve("music-pack.json"), bundle(name));
    }
    private static Rule ruleFor(Config config, String packId) {
        for (Rule rule : config.rules) if (packId.equals(rule.packId)) return rule;
        throw new AssertionError("missing rule for " + packId);
    }
    public static void run() throws Exception {
        directFoldersAndCopies(); subscribedAndLocal(); legacyMigration(); directoryMigration();
        System.out.println("PASS: " + checks + " portable music-pack checks");
    }

    private static void state(Path content, String... ids) throws Exception {
        StringBuilder value = new StringBuilder("\"AppWorkshop\" { \"appid\" \"646570\" \"NeedsDownload\" \"0\" \"NeedsUpdate\" \"0\" \"WorkshopItemsInstalled\" {");
        for (String id : ids) value.append(" \"").append(id).append("\" { \"manifest\" \"1\" }");
        value.append(" } }");
        Path file = content.getParent().getParent().getParent().resolve("workshop/appworkshop_646570.acf");
        Files.write(file, value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void subscribedAndLocal() throws Exception {
        Path workspace = Files.createTempDirectory("battleaudio-portable-steam-");
        Path content = workspace.resolve("steamapps/workshop/content/646570"), item = content.resolve("12345");
        item(item, "Subscribed"); state(content, "12345");
        Path root = workspace.resolve("game/BetterCustomAudioTriggers");
        AudioLibrary library = new AudioLibrary(root, null, true); library.setWorkshopContentRoot(content);
        Config config = library.synchronizeWorkshopPackages(library.load(), content, Collections.emptySet()).config;
        MusicPack subscribed = config.pack("workshop-12345");
        check(subscribed != null && library.managed(ruleFor(config, subscribed.id).audio().cachePath).startsWith(item),
                "Portable settings still read subscribed audio directly from Steam");
        subscribed.enabled = false; ruleFor(config, subscribed.id).enabled = false; library.save(config);
        Config copy = library.copyPackage(config, subscribed.id, " Copie");
        MusicPack custom = null; for (MusicPack pack : copy.packs) if (pack.source == MusicPack.Source.LOCAL) custom = pack;
        check(custom != null && Files.isRegularFile(root.resolve("music-packs").resolve(custom.localFolder).resolve("music-pack.json")),
                "Subscribed package copies to a self-contained local folder");
        check("Subscribed Copie".equals(custom.name), "Copy uses the requested localized suffix");
        String customId = custom.id;
        AudioLibrary restart = new AudioLibrary(root, null, true); restart.setWorkshopContentRoot(content);
        Config loaded = restart.synchronizeWorkshopPackages(restart.load(), content, Collections.emptySet()).config;
        check("Subscribed Copie".equals(loaded.pack(customId).name), "Localized copy name survives restart");
        check(!loaded.pack("workshop-12345").enabled && !ruleFor(loaded, "workshop-12345").enabled,
                "Subscribed package and rule switches persist even when package rules are absent from global settings");
        WorkshopPackageCatalog.Bundle upstream = bundle("Subscribed updated"); upstream.rules.get(0).name = "upstream updated";
        write(item.resolve("music-pack.json"), upstream);
        Config updated = restart.synchronizeWorkshopPackages(loaded, content, Collections.emptySet()).config;
        check("upstream updated".equals(ruleFor(updated, "workshop-12345").name)
                && "original".equals(ruleFor(updated, customId).name), "Upstream changes never alter the independent custom package");
        state(content);
        AudioLibrary offlineRestart = new AudioLibrary(root, null, true); offlineRestart.setWorkshopContentRoot(content);
        Config cancelled = offlineRestart.synchronizeWorkshopPackages(offlineRestart.load(), content, Collections.emptySet()).config;
        check(cancelled.pack("workshop-12345") == null && cancelled.pack(customId) != null && Files.exists(item.resolve("audio/tone.wav")),
                "Cancelled subscriptions leave local copies intact and never delete Steam files");
        state(content, "12345");
        Config again = offlineRestart.synchronizeWorkshopPackages(cancelled, content, Collections.emptySet()).config;
        check(again.pack("workshop-12345").enabled && ruleFor(again, "workshop-12345").enabled,
                "Resubscription after a restart uses package defaults rather than stale personal switches");
    }

    private static void directFoldersAndCopies() throws Exception {
        Path root = Files.createTempDirectory("battleaudio-portable-");
        Path first = root.resolve("music-packs/loong"), second = root.resolve("music-packs/another");
        item(first, "loong"); item(second, "another");
        byte[] original = Files.readAllBytes(first.resolve("music-pack.json"));
        AudioLibrary library = new AudioLibrary(root, null, true);
        Config config = library.load();
        check(config.packs.size() == 2 && config.rules.size() == 2, "Manually copied complete folders load without an import action");
        MusicPack one = config.pack("loong"), two = config.pack("another");
        Rule rule = ruleFor(config, one.id), other = ruleFor(config, two.id);
        check(!rule.id.equals(other.id) && library.managed(rule.audio().cachePath).equals(first.resolve("audio/tone.wav")),
                "Matching source rule IDs in different folders are scoped independently and play from their own folder");
        Path untracked = first.resolve("audio/user-kept.wav"); Files.write(untracked, new byte[] {9, 8, 7});
        library.collect(config, Collections.singleton(rule.audio().cachePath));
        check(Files.isRegularFile(first.resolve("audio/tone.wav")) && Files.isRegularFile(untracked),
                "Routine collection never deletes portable-package media, including untracked user files");
        library.save(config);
        JsonObject preferences = new JsonParser().parse(new String(Files.readAllBytes(root.resolve("settings.json")), StandardCharsets.UTF_8)).getAsJsonObject();
        check(!preferences.has("rules") && !preferences.has("packs") && preferences.has("ruleSwitches"), "Global settings contain personal switches rather than package rules");
        check(Arrays.equals(original, Files.readAllBytes(first.resolve("music-pack.json"))), "Switch-only save does not rewrite package content");
        one.enabled = false; rule.enabled = false; rule.name = "edited locally";
        library.save(config);
        WorkshopPackageCatalog.Bundle edited = WorkshopPackageCatalog.readBundle(first, null);
        check("edited locally".equals(edited.rules.get(0).name) && edited.rules.get(0).enabled,
                "Local edits go to the pack file while the rule switch stays personal");
        AudioLibrary restarted = new AudioLibrary(root, null, true);
        Config loaded = restarted.load();
        check(!loaded.pack("loong").enabled && !ruleFor(loaded, "loong").enabled
                && "edited locally".equals(ruleFor(loaded, "loong").name), "Personal switches and edited rules survive restart");
        Rule added = new Rule(); added.upstreamRuleId = "new-source-id";
        added.id = MusicPackages.stableRuleId("loong", added.upstreamRuleId); added.packId = "loong";
        added.name = "linked rule"; added.prerequisiteRuleIds.add(ruleFor(loaded, "loong").id);
        loaded.rules.add(added); restarted.save(loaded);
        Path exported = root.resolve("portable-export");
        restarted.exportPackage(loaded, "loong", exported);
        WorkshopPackageCatalog.Bundle exportedBundle = WorkshopPackageCatalog.readBundle(exported, null);
        check("new-source-id".equals(exportedBundle.rules.get(1).id)
                && exportedBundle.rules.get(1).prerequisiteRuleIds.contains("original-rule"),
                "Portable export preserves source rule IDs and in-package associations");
        Config copy = restarted.copyPackage(loaded, "loong");
        MusicPack copied = null;
        for (MusicPack pack : copy.packs) if (loaded.pack(pack.id) == null) copied = pack;
        check(copied != null && copied.localFolder != null && Files.isRegularFile(root.resolve("music-packs").resolve(copied.localFolder).resolve("music-pack.json")),
                "Copy creates a complete independent package in the game data folder");
        Rule copiedRule = ruleFor(copy, copied.id);
        check(!restarted.managed(copiedRule.audio().cachePath).equals(first.resolve("audio/tone.wav"))
                && Files.exists(restarted.managed(copiedRule.audio().cachePath)), "Copy has independent physical audio");
        String copiedId = copied.id;
        AudioLibrary restartedAgain = new AudioLibrary(root, null, true);
        Config copyReload = restartedAgain.load();
        check(copyReload.pack(copiedId) != null && ruleFor(copyReload, copiedId).id.equals(copiedRule.id), "Copied package and scoped rule IDs survive restart");
        Rule moved = ruleFor(copyReload, copiedId);
        for (Rule linked : copyReload.rules) linked.prerequisiteRuleIds.remove(moved.id);
        moved.packId = null; moved.upstreamRuleId = null;
        restartedAgain.save(copyReload);
        check(restartedAgain.managed(moved.audio().cachePath).startsWith(root.resolve("cache")), "Moving a rule to root detaches audio from the package");
        AudioLibrary afterMove = new AudioLibrary(root, null, true); Config moveReload = afterMove.load();
        check(moveReload.rules.stream().anyMatch(r -> r.packId == null && r.id.equals(moved.id)), "Root rule is stored independently of global preferences");
        Path bad = root.resolve("music-packs/broken"); Files.createDirectories(bad);
        Files.write(bad.resolve("music-pack.json"), "{broken".getBytes(StandardCharsets.UTF_8));
        AudioLibrary withBad = new AudioLibrary(root, null, true); Config unaffected = withBad.load();
        check(unaffected.pack("loong") != null && !withBad.localPackageWarnings().isEmpty(), "An invalid local pack is reported without blocking other packs");
        MusicPack removed = unaffected.pack("another");
        for (Rule removedRule : unaffected.rules) if (removed.id.equals(removedRule.packId)) {
            removedRule.id = UUID.randomUUID().toString(); removedRule.packId = null; removedRule.upstreamRuleId = null;
        }
        unaffected.packs.remove(removed); withBad.save(unaffected);
        check(!Files.exists(second) && Files.isDirectory(root.resolve("music-packs/.removed-packages")),
                "Removing a local package moves its complete folder to a recoverable location");
        item(second, "another reinstalled");
        Config reinstalled = new AudioLibrary(root, null, true).load();
        check(reinstalled.pack("another") != null && "another reinstalled".equals(reinstalled.pack("another").name),
                "Copying a package back to the same folder is detected on restart");
        AudioLibrary deleteLibrary = new AudioLibrary(root, null, true); Config deleteLoaded = deleteLibrary.load();
        MusicPack deleteTarget = deleteLoaded.pack("another"); Config without = deleteLoaded.copy();
        without.rules.removeIf(candidate -> deleteTarget.id.equals(candidate.packId)); without.packs.removeIf(pack -> deleteTarget.id.equals(pack.id));
        deleteLibrary.deletePackage(without, deleteTarget.id, deleteTarget.localFolder);
        check(!Files.exists(second), "Permanent package deletion removes the local package folder");
    }

    private static void legacyMigration() throws Exception {
        Path root = Files.createTempDirectory("battleaudio-portable-legacy-");
        Files.createDirectories(root.resolve("library")); Files.createDirectories(root.resolve("cache"));
        Files.write(root.resolve("library/legacy.source"), new byte[] {2,3}); Files.write(root.resolve("cache/legacy.wav"), new byte[] {4,5});
        Config legacy = new Config(); legacy.schemaVersion = 3;
        Rule rule = new Rule(); rule.id = "legacy-rule"; rule.name = "legacy"; rule.enabled = false;
        rule.triggers = EnumSet.of(Trigger.PLAYER_DEATH);
        Candidate audio = new Candidate(); audio.id = "legacy-audio"; audio.name = "legacy.wav";
        audio.sourcePath = "library/legacy.source"; audio.cachePath = "cache/legacy.wav"; audio.duration = 1; audio.end = 1;
        rule.candidates.add(audio); legacy.rules.add(rule);
        JsonObject json = new JsonParser().parse(GSON.toJson(legacy)).getAsJsonObject();
        json.remove("packs"); json.addProperty("schemaVersion", 3);
        write(root.resolve("settings.json"), json);
        AudioLibrary library = new AudioLibrary(root, null, true); Config migrated = library.load();
        check(migrated.packs.size() == 1 && !migrated.rules.get(0).enabled && "legacy".equals(migrated.rules.get(0).name),
                "Legacy settings become a portable package without changing rule switches");
        Path pack = root.resolve("music-packs/legacy-migrated/music-pack.json");
        check(Files.isRegularFile(pack) && Files.exists(library.managed(migrated.rules.get(0).audio().cachePath)), "Migrated package includes its own rule manifest and audio");
        try (java.util.stream.Stream<Path> paths = Files.list(root)) {
            check(paths.anyMatch(p -> p.getFileName().toString().startsWith("settings.pre-portable-")), "A legacy settings backup remains available");
        }
        Config again = new AudioLibrary(root, null, true).load();
        check(again.rules.size() == 1 && !again.rules.get(0).enabled, "Migrated switches survive the next launch");
    }

    private static void directoryMigration() throws Exception {
        Path workspace = Files.createTempDirectory("battleaudio-game-storage-");
        Path game = workspace.resolve("game"), old = workspace.resolve("old");
        Files.createDirectories(game); Files.createDirectories(old.resolve("library"));
        Files.write(old.resolve("settings.json"), new byte[] {1,2,3}); Files.write(old.resolve("library/audio.source"), new byte[] {4,5,6});
        Path target = GameStorage.prepare(game, old);
        check(Files.exists(old.resolve("settings.json")) && Files.exists(old.resolve("library/audio.source"))
                && Arrays.equals(Files.readAllBytes(old.resolve("library/audio.source")), Files.readAllBytes(target.resolve("library/audio.source"))),
                "Old user data is copied and checked without deleting its source");
        Path clashGame = workspace.resolve("clash"); Files.createDirectories(clashGame.resolve("BetterCustomAudioTriggers/music-packs"));
        boolean refused = false;
        try { GameStorage.prepare(clashGame, old); } catch (java.io.IOException expected) { refused = true; }
        check(refused && Files.exists(old.resolve("settings.json")), "Existing game data blocks ambiguous migration without overwriting either side");
    }
}
