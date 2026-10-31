package battleaudio.storage;

import battleaudio.core.RuleEngine;
import battleaudio.model.Config;
import battleaudio.model.Config.Candidate;
import battleaudio.model.Config.Rule;
import battleaudio.model.Config.Trigger;
import battleaudio.model.MusicPack;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MusicPackageTests {
    private static int checks;
    private static final Gson GSON = new Gson();
    private static Path decoder;

    private MusicPackageTests() { }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception { run(); }
    public static void run() throws Exception {
        migrateLegacyRulesAndAssets();
        subscriptionsUpdateCopyAndUnsubscribe();
        workshopCatalogAndAutomaticSync();
        packageToggleSemantics();
        System.out.println("PASS: " + checks + " music package checks");
    }
    public static void run(Path ffmpeg) throws Exception { decoder = ffmpeg; run(); }

    private static void migrateLegacyRulesAndAssets() throws Exception {
        Path root = Files.createTempDirectory("battleaudio-pack-migration-");
        Path source = root.resolve("library/legacy.source"), cache = root.resolve("cache/legacy.wav");
        Files.createDirectories(source.getParent()); Files.createDirectories(cache.getParent());
        byte[] sourceBytes = new byte[] {3, 1, 4, 1, 5};
        byte[] cacheBytes = new byte[] {9, 2, 6, 5};
        Files.write(source, sourceBytes); Files.write(cache, cacheBytes);
        Config legacy = new Config(); legacy.schemaVersion = 3;
        Rule first = rule("legacy-a", "残血规则", Trigger.LOW_HEALTH); first.enabled = false;
        first.audio().name = "legacy.mp3"; first.audio().sourcePath = "library/legacy.source"; first.audio().cachePath = "cache/legacy.wav";
        first.audio().duration = 8; first.audio().end = 8;
        Rule second = rule("legacy-b", "击杀规则", Trigger.KILL_ENEMY); second.enabled = true;
        Candidate shared = new Candidate(); shared.name = "legacy-copy.wav"; shared.sourcePath = "library/legacy.source";
        shared.cachePath = "cache/legacy.wav"; shared.duration = 8; shared.end = 8;
        second.candidates.clear(); second.candidates.add(shared);
        legacy.rules.add(first); legacy.rules.add(second);
        JsonObject oldJson = new JsonParser().parse(GSON.toJson(legacy)).getAsJsonObject();
        oldJson.remove("packs"); oldJson.addProperty("schemaVersion", 3);
        for (com.google.gson.JsonElement element : oldJson.getAsJsonArray("rules")) element.getAsJsonObject().remove("packId");
        Files.createDirectories(root);
        Files.write(root.resolve("settings.json"), GSON.toJson(oldJson).getBytes(StandardCharsets.UTF_8));

        AudioLibrary library = new AudioLibrary(root, null);
        Config migrated = library.load();
        check(migrated.schemaVersion == 4, "Legacy settings migrate to package-aware schema 4");
        check(migrated.rules.size() == 2 && migrated.rules.get(0).enabled == false && migrated.rules.get(1).enabled,
                "Migration preserves rule count and each per-rule enabled state");
        check(migrated.rules.get(0).triggers.contains(Trigger.LOW_HEALTH)
                && migrated.rules.get(1).triggers.contains(Trigger.KILL_ENEMY), "Migration preserves actual trigger rules");
        check(migrated.packs.size() == 1 && migrated.packs.get(0).source == MusicPack.Source.MIGRATED,
                "Legacy live rules become one separately identified migrated package");
        check(migrated.packs.get(0).ruleIds.size() == 2, "Package membership index includes every migrated rule");
        String migratedSource = migrated.rules.get(0).audio().sourcePath;
        String migratedCache = migrated.rules.get(0).audio().cachePath;
        check(migratedSource.startsWith("packs/legacy-migrated/versions/")
                && migratedCache.startsWith("packs/legacy-migrated/versions/"), "Legacy media are copied into package-owned storage");
        check(java.util.Arrays.equals(sourceBytes, Files.readAllBytes(library.managed(migratedSource)))
                && java.util.Arrays.equals(cacheBytes, Files.readAllBytes(library.managed(migratedCache))),
                "Package migration preserves exact source and playback bytes");
        check(Files.isRegularFile(root.resolve("settings.pre-pack-migration-" + findMigrationBackup(root) + ".json")),
                "Migration preserves a pre-change settings recovery copy");
        check(Files.exists(source) && Files.exists(cache), "Migration never deletes the old referenced files before commit");
        check(migrated.packs.get(0).ownedFiles.size() == 2, "Package ownership manifest lists unique package assets");

        Config cloned = library.copyPackage(migrated, migrated.packs.get(0).id);
        MusicPack clone = cloned.packs.get(1);
        Rule originalRule = cloned.rules.get(0), cloneRule = null;
        for (Rule candidate : cloned.rules) if (clone.id.equals(candidate.packId)) { cloneRule = candidate; break; }
        check(clone.source == MusicPack.Source.LOCAL && clone.workshopId.isEmpty(), "Package copy is an independent local package");
        check(cloneRule != null && !cloneRule.id.equals(originalRule.id) && !cloneRule.candidates.get(0).id.equals(originalRule.candidates.get(0).id),
                "Package copy has independent rule and audio identities");
        check(!cloneRule.audio().sourcePath.equals(originalRule.audio().sourcePath)
                && !cloneRule.audio().cachePath.equals(originalRule.audio().cachePath), "Copied audio files are physically isolated");
        check(!cloneRule.enabled && originalRule.enabled == false, "Copy preserves rule enable state without sharing rule objects");
        cloneRule.enabled = true;
        check(!originalRule.enabled, "Editing copied rule state does not affect source package");
        library.save(cloned);
        Path contentRoot = root.resolve("steamapps/workshop/content/646570");
        Files.createDirectories(contentRoot);
        Path exportedPath = contentRoot.resolve("3817409902");
        Path ready = library.exportPackage(cloned, clone.id, exportedPath);
        Path managedReady = library.exportPackageForWorkshop(cloned, clone.id);
        check(managedReady.startsWith(root.resolve("workshop-exports").resolve(clone.contentId)),
                "In-game export creates a uniquely named snapshot only under the app-managed export root");
        WorkshopPackageCatalog.Bundle managedBundle = WorkshopPackageCatalog.readBundle(managedReady, "3817409903");
        check(managedBundle.packageId.equals(clone.contentId) && managedBundle.rules.size() == 2,
                "Managed export is a valid complete package for the selected local copy");
        check(!Files.exists(managedReady.resolve("config.json")) && !Files.exists(managedReady.resolve("image.jpg")),
                "Managed export is a package bundle, not an uploader workspace or an upload request");
        WorkshopPackageCatalog.Bundle checkedBundle = WorkshopPackageCatalog.readBundle(ready, "3817409902");
        check(checkedBundle.packageId.equals(clone.contentId) && checkedBundle.rules.size() == 2,
                "Export creates a separately identified, re-importable package folder");
        check(checkedBundle.assets.size() == 2 && checkedBundle.workshopId.isEmpty(),
                "Export contains only package-owned media and no remote publishing identity");
        check(checkedBundle.rules.get(0).audio().sourcePath.toLowerCase(java.util.Locale.ROOT).endsWith(".mp3"),
                "Export restores the source codec extension from the visible candidate name");
        try { library.exportPackage(cloned, clone.id, exportedPath); throw new AssertionError("Export overwrote an existing folder"); }
        catch (java.io.IOException expected) { check(true, "Export refuses to overwrite an existing folder"); }

        Path unknown = library.root().resolve("library/not-program-managed.source");
        Files.write(unknown, new byte[] {8, 8, 8});
        library.collect(cloned, Collections.emptySet());
        check(Files.exists(unknown), "Cleanup leaves unregistered user files untouched");
        check(Files.exists(library.managed(migratedSource)), "Recovery settings retain pre-migration media until the backup is removed");
    }

    private static String findMigrationBackup(Path root) throws Exception {
        try (java.util.stream.Stream<Path> files = Files.list(root)) {
            Path backup = files.filter(path -> path.getFileName().toString().startsWith("settings.pre-pack-migration-")
                    && path.getFileName().toString().endsWith(".json")).findFirst().orElseThrow(() -> new AssertionError("Migration backup missing"));
            String file = backup.getFileName().toString();
            return file.substring("settings.pre-pack-migration-".length(), file.length() - ".json".length());
        }
    }

    private static void subscriptionsUpdateCopyAndUnsubscribe() throws Exception {
        Path steam = Files.createTempDirectory("battleaudio-direct-workshop-");
        Path content = steam.resolve("steamapps/workshop/content/646570"), item = content.resolve("3817409901");
        Files.createDirectories(item.resolve("media"));
        Files.write(item.resolve("media/v1.mp3"), new byte[] {1, 1, 1});
        Files.write(item.resolve("media/v1.wav"), new byte[] {2, 2, 2});
        WorkshopPackageCatalog.Bundle bundle = new WorkshopPackageCatalog.Bundle();
        bundle.schemaVersion = 1; bundle.packageId = "music-pack-fixture"; bundle.workshopId = "";
        bundle.name = "测试音乐包"; bundle.version = "1.0";
        bundle.rules = new ArrayList<>(Collections.singletonList(workshopRule("upstream-rule", "上游版本一", "media/v1.mp3", "media/v1.wav")));
        bundle.files = new ArrayList<>();
        bundle.files.add(mediaRecord(item, "media/v1.mp3", item.resolve("media/v1.mp3")));
        bundle.files.add(mediaRecord(item, "media/v1.wav", item.resolve("media/v1.wav")));
        Path manifest = item.resolve("music-pack.json");
        Files.write(manifest, GSON.toJson(bundle).getBytes(StandardCharsets.UTF_8));
        byte[] initialManifest = Files.readAllBytes(manifest);
        writeWorkshopState(content, "0", "0", "3817409901");
        Path root = Files.createTempDirectory("battleaudio-direct-profile-");
        AudioLibrary library = new AudioLibrary(root, null);
        Config installed = library.synchronizeWorkshopPackages(new Config(), content, Collections.emptySet()).config;
        MusicPack subscribed = findWorkshop(installed, "3817409901");
        boolean subscribedExportRejected = false;
        try { library.exportPackageForWorkshop(installed, subscribed.id); }
        catch (java.io.IOException expected) { subscribedExportRejected = true; }
        check(subscribedExportRejected, "Subscribed upstream package cannot be exported as the editable local publication source");
        Rule installedRule = findRule(installed, subscribed.id);
        String v1Source = installedRule.audio().sourcePath, v1Cache = installedRule.audio().cachePath;
        Path subscribedSource = library.managed(v1Source), subscribedCache = library.managed(v1Cache);
        check(subscribed.enabled && installedRule.enabled, "Newly subscribed package and rule default to enabled");
        check(subscribedSource.equals(item.resolve("media/v1.mp3")) && subscribedCache.equals(item.resolve("media/v1.wav"))
                && !Files.exists(root.resolve("packs").resolve(subscribed.id)), "Subscription reads Steam files directly without a local mirror");
        check(java.util.Arrays.equals(initialManifest, Files.readAllBytes(manifest)), "Loading never modifies Workshop content");
        String oldRelative = "packs/" + subscribed.id + "/versions/00000000-0000-0000-0000-000000000001/source/00000000-0000-0000-0000-000000000002.source";
        Path oldMirror = root.resolve(oldRelative);
        Files.createDirectories(oldMirror.getParent()); Files.write(oldMirror, new byte[] {9, 8, 7});
        MusicPack.OwnedFile oldFile = new MusicPack.OwnedFile(oldRelative, mediaRecord(root, oldRelative, oldMirror).sha256, 3);
        Map<String, Object> oldOwner = new HashMap<>(); oldOwner.put("id", subscribed.id); oldOwner.put("source", "SUBSCRIBED");
        oldOwner.put("workshopId", subscribed.workshopId); oldOwner.put("files", Collections.singletonList(oldFile));
        Files.write(root.resolve("packs").resolve(subscribed.id).resolve("package-owner.json"), GSON.toJson(oldOwner).getBytes(StandardCharsets.UTF_8));
        library.collect(installed, Collections.emptySet());
        check(Files.exists(oldMirror), "Legacy subscription mirror remains as recovery data and is not automatically deleted");
        if (decoder != null) {
            Path tone = root.resolve("tone.wav");
            byte[] pcm = new byte[88200];
            try (java.io.ByteArrayInputStream bytes = new java.io.ByteArrayInputStream(pcm);
                    javax.sound.sampled.AudioInputStream audio = new javax.sound.sampled.AudioInputStream(bytes,
                            new javax.sound.sampled.AudioFormat(44100, 16, 1, true, false), pcm.length / 2)) {
                javax.sound.sampled.AudioSystem.write(audio, javax.sound.sampled.AudioFileFormat.Type.WAVE, tone.toFile());
            }
            AudioLibrary importer = new AudioLibrary(root, decoder); importer.setWorkshopContentRoot(content);
            Candidate imported = importer.importAudio(tone, subscribed).audio();
            check(imported.playable() && importer.managed(imported.sourcePath).startsWith(item)
                    && importer.managed(imported.cachePath).startsWith(item), "Import writes source and decoded cache directly into the subscription");
        }

        Rule rootRule = new Rule(); rootRule.name = "root before subscription copy"; installed.rules.add(rootRule);
        Config cloned = library.copyPackage(installed, subscribed.id);
        library.save(cloned);
        MusicPack local = cloned.packs.get(cloned.packs.size() - 1);
        Rule localRule = findRule(cloned, local.id);
        check(local.source == MusicPack.Source.LOCAL && local.workshopId.isEmpty(), "Copy-to-new-package detaches the Workshop identity");
        check(!localRule.audio().cachePath.equals(v1Cache), "Custom copy owns a separate playable cache");
        String cloneCache = localRule.audio().cachePath;
        String cloneRuleName = localRule.name;

        subscribed = findWorkshop(cloned, "3817409901");
        subscribed.enabled = false;
        findRule(cloned, subscribed.id).enabled = false;
        library.save(cloned);
        check(java.util.Arrays.equals(initialManifest, Files.readAllBytes(manifest)), "Personal switches do not modify the Workshop manifest");
        Rule edited = findRule(cloned, subscribed.id);
        edited.name = "玩家修改";
        edited.audio().volume = .44f;
        library.save(cloned);
        WorkshopPackageCatalog.Bundle saved = WorkshopPackageCatalog.readBundle(item, "3817409901");
        check("玩家修改".equals(saved.rules.get(0).name) && saved.rules.get(0).enabled
                && saved.rules.get(0).audio().volume == .44f, "Rule edits write to the package while the personal switch stays local");
        check(!findRule(cloned, subscribed.id).enabled, "Saving edits keeps the local rule switch");
        Rule extra = new Rule(); extra.upstreamRuleId = "added-rule";
        extra.id = MusicPackages.stableRuleId(subscribed.id, extra.upstreamRuleId);
        extra.packId = subscribed.id; extra.name = "玩家新建";
        extra.triggers = EnumSet.of(Trigger.PLAYER_DEATH);
        extra.prerequisiteRuleIds.add(edited.id);
        cloned.rules.add(extra); library.save(cloned);
        WorkshopPackageCatalog.Bundle withExtra = WorkshopPackageCatalog.readBundle(item, "3817409901");
        check(withExtra.rules.size() == 2 && withExtra.rules.get(1).prerequisiteRuleIds.contains("upstream-rule"),
                "New subscription rules and their internal links are saved using source rule IDs");
        cloned.rules.remove(extra); library.save(cloned);
        check(WorkshopPackageCatalog.readBundle(item, "3817409901").rules.size() == 1,
                "Deleting a subscribed rule removes it from the item manifest");
        byte[] editedManifest = Files.readAllBytes(manifest);
        Files.write(item.resolve("media/v1.mp3"), new byte[] {8, 8, 8, 8});
        check(WorkshopPackageCatalog.readBundle(item, "3817409901").assets.size() == 2,
                "Directly edited audio is not rejected for stale hashes or sizes");
        Path addedSource = root.resolve("library/added.source"), addedCache = root.resolve("cache/added.wav");
        Files.write(addedSource, new byte[] {4, 4}); Files.write(addedCache, new byte[] {5, 5});
        Candidate added = new Candidate(); added.id = "added"; added.name = "added.wav";
        added.sourcePath = "library/added.source"; added.cachePath = "cache/added.wav"; added.duration = 5; added.end = 5;
        edited.candidates.add(added);
        library.save(cloned);
        check(library.managed(added.sourcePath).startsWith(item) && library.managed(added.cachePath).startsWith(item),
                "Added audio is stored in the subscribed item and referenced by its manifest");
        AudioLibrary reopened = new AudioLibrary(root, null); reopened.setWorkshopContentRoot(content);
        Config restored = reopened.synchronizeWorkshopPackages(reopened.load(), content, Collections.emptySet()).config;
        check("玩家修改".equals(findRule(restored, subscribed.id).name) && findRule(restored, subscribed.id).candidates.size() == 2,
                "Edited rules and audio survive a restart before upstream update");
        library = reopened; cloned = restored;
        Path missingCache = library.managed(findRule(cloned, subscribed.id).audio().cachePath);
        byte[] missingBytes = Files.readAllBytes(missingCache); Files.delete(missingCache);
        Config missing = library.synchronizeWorkshopPackages(cloned, content, Collections.emptySet()).config;
        check(!findRule(missing, subscribed.id).audio().playable(), "Missing subscription audio is disabled");
        findRule(missing, subscribed.id).name = "缺失期间编辑"; library.save(missing);
        Files.write(missingCache, missingBytes);
        Config repaired = library.synchronizeWorkshopPackages(missing, content, Collections.emptySet()).config;
        check(findRule(repaired, subscribed.id).audio().playable(), "Restored subscription audio becomes playable again after an edit and restart");
        cloned = repaired;
        findRule(cloned, subscribed.id).name = "玩家修改"; library.save(cloned);
        byte[] beforeFailure = Files.readAllBytes(manifest);
        Path blocker = root.resolve("settings.json.bak");
        byte[] existingBackup = Files.readAllBytes(blocker);
        Files.delete(blocker); Files.createDirectory(blocker); Files.write(blocker.resolve("block"), new byte[] {1});
        findRule(cloned, subscribed.id).name = "保存失败时回滚";
        boolean rolledBack = false;
        try { library.save(cloned); } catch (java.io.IOException expected) { rolledBack = true; }
        check(rolledBack && java.util.Arrays.equals(beforeFailure, Files.readAllBytes(manifest)),
                "A local settings write failure restores the subscription manifest");
        Files.delete(blocker.resolve("block")); Files.delete(blocker); Files.write(blocker, existingBackup);
        findRule(cloned, subscribed.id).name = "玩家修改";
        editedManifest = Files.readAllBytes(manifest);
        Files.write(manifest, (new String(editedManifest, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8));
        findRule(cloned, subscribed.id).name = "不可覆盖上游";
        boolean conflict = false;
        try { library.save(cloned); } catch (java.io.IOException expected) { conflict = true; }
        check(conflict && Files.readAllBytes(manifest).length == editedManifest.length + 1,
                "Editing does not overwrite a manifest changed outside the running game");
        Files.write(item.resolve("media/v2.mp3"), new byte[] {2, 2, 2});
        Files.write(item.resolve("media/v2.wav"), new byte[] {3, 3, 3});
        Rule v2 = workshopRule("upstream-rule", "上游版本二", "media/v2.mp3", "media/v2.wav");
        bundle.rules = new ArrayList<>(Collections.singletonList(v2)); bundle.files = new ArrayList<>();
        Files.write(manifest, GSON.toJson(bundle).getBytes(StandardCharsets.UTF_8));
        Config updated = library.synchronizeWorkshopPackages(cloned, content, Collections.emptySet()).config;
        MusicPack updatedPack = findWorkshop(updated, "3817409901");
        Rule updatedRule = findRule(updated, updatedPack.id);
        Rule untouchedCloneRule = findRule(updated, local.id);
        check("上游版本二".equals(updatedRule.name) && updatedRule.candidates.size() == 1,
                "Changed upstream content replaces local edits even if the version string did not change");
        check(!updatedPack.enabled && !updatedRule.enabled, "Update retains local package and per-rule switches");
        check(cloneRuleName.equals(untouchedCloneRule.name) && cloneCache.equals(untouchedCloneRule.audio().cachePath),
                "Upstream update never changes the independent custom package");
        check(Files.exists(subscribedSource) && Files.exists(subscribedCache), "The Mod does not delete Steam-owned files on update");
        check(Files.exists(library.managed(cloneCache)), "Update keeps files owned by the custom copy");
        check(Files.exists(library.managed(updatedRule.audio().cachePath)), "Updated cache exists at the new package revision");

        writeWorkshopState(content, "0", "0");
        Config unsubscribed = library.synchronizeWorkshopPackages(updated, content, Collections.emptySet()).config;
        check(findWorkshopOrNull(unsubscribed, "3817409901") == null, "Unsubscribe removes only the matching subscribed package record");
        check(findRule(unsubscribed, local.id) != null && Files.exists(library.managed(cloneCache)),
                "Unsubscribe preserves the custom package and its independent media");
        check(Files.exists(item.resolve("media/v2.wav")), "The Mod never deletes the Steam-owned item on unsubscribe");
        writeWorkshopState(content, "0", "0", "3817409901");
        Config resubscribed = library.synchronizeWorkshopPackages(unsubscribed, content, Collections.emptySet()).config;
        MusicPack freshSubscription = findWorkshop(resubscribed, "3817409901");
        check(freshSubscription.enabled && findRule(resubscribed, freshSubscription.id).enabled,
                "Resubscription starts clean with package and rule defaults");
        check(findRule(resubscribed, local.id) != null && Files.exists(library.managed(cloneCache)),
                "Resubscription does not merge into or replace the user's detached copy");
        Rule moved = findRule(resubscribed, freshSubscription.id);
        moved.packId = null; moved.upstreamRuleId = null;
        library.save(resubscribed);
        check(library.managed(moved.audio().sourcePath).startsWith(root)
                && library.managed(moved.audio().cachePath).startsWith(root), "Moving a subscription rule to root detaches its audio");
        check(WorkshopPackageCatalog.readBundle(item, "3817409901").rules.isEmpty(), "Moving the last rule updates the subscription manifest");
        Rule movedIn = findRule(resubscribed, local.id);
        movedIn.packId = freshSubscription.id;
        movedIn.upstreamRuleId = "moved-in";
        movedIn.id = MusicPackages.stableRuleId(freshSubscription.id, movedIn.upstreamRuleId);
        library.save(resubscribed);
        WorkshopPackageCatalog.Bundle afterMove = WorkshopPackageCatalog.readBundle(item, "3817409901");
        check(afterMove.rules.size() == 1 && library.managed(movedIn.audio().cachePath).startsWith(item)
                && Files.exists(library.managed(cloneCache)), "Moving a local rule into a subscription copies its audio without deleting the original");
        AudioLibrary movingRestart = new AudioLibrary(root, null); movingRestart.setWorkshopContentRoot(content);
        Config afterRestart = movingRestart.synchronizeWorkshopPackages(movingRestart.load(), content, Collections.emptySet()).config;
        check(findRule(afterRestart, freshSubscription.id).id.equals(movedIn.id), "Moved-in rule identity remains stable after restart");
    }

    private static void workshopCatalogAndAutomaticSync() throws Exception {
        Path steam = Files.createTempDirectory("battleaudio-steam-workshop-");
        Path content = steam.resolve("steamapps/workshop/content/646570");
        Files.createDirectories(content);
        Path item = content.resolve("3817409902");
        Path source = item.resolve("media/intro.mp3"), cache = item.resolve("media/intro.wav");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[] {4, 5, 6}); Files.write(cache, new byte[] {7, 8, 9});
        WorkshopPackageCatalog.Bundle manifest = new WorkshopPackageCatalog.Bundle();
        manifest.schemaVersion = 1; manifest.packageId = "published-pack-uuid"; manifest.workshopId = "";
        manifest.name = "可订阅音乐包"; manifest.version = "1.0.0"; manifest.rules = new ArrayList<>();
        manifest.rules.add(workshopRule("bundle-rule", "测试规则", "media/intro.mp3", "media/intro.wav"));
        manifest.files = new ArrayList<>();
        manifest.files.add(mediaRecord(item, "media/intro.mp3", source));
        manifest.files.add(mediaRecord(item, "media/intro.wav", cache));
        Files.write(item.resolve(WorkshopPackageCatalog.MANIFEST), GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        writeWorkshopState(content, "0", "0", "3817409902");
        WorkshopPackageCatalog.ScanResult scanned = WorkshopPackageCatalog.scan(content);
        check(scanned.authoritative && scanned.syncReady && scanned.installedIds.contains("3817409902"),
                "Installed Steam Workshop state is recognized as authoritative and ready");
        check(scanned.validPacks.containsKey("3817409902") && scanned.warnings.isEmpty(),
                "A package manifest with readable files is accepted");

        Path profile = Files.createTempDirectory("battleaudio-steam-profile-");
        AudioLibrary library = new AudioLibrary(profile, null);
        AudioLibrary.WorkshopSync first = library.synchronizeWorkshopPackages(new Config(), content, Collections.emptySet());
        MusicPack installed = findWorkshop(first.config, "3817409902");
        check(first.synchronizedState && first.installedOrUpdated == 1 && installed.version.equals("1.0.0"),
                "Workshop catalog makes subscribed packages available");
        String cachePath = findRule(first.config, installed.id).audio().cachePath;
        check(library.managed(cachePath).equals(cache), "Installed Workshop package references Steam content directly");

        writeWorkshopState(content, "1", "0", "3817409902");
        AudioLibrary.WorkshopSync partial = library.synchronizeWorkshopPackages(first.config, content, Collections.emptySet());
        check(!partial.synchronizedState && findWorkshop(partial.config, "3817409902") != null
                && !findRule(partial.config, installed.id).audio().playable(),
                "An incomplete Steam download never triggers package deletion");

        writeWorkshopState(content, "0", "0");
        AudioLibrary.WorkshopSync cancelled = library.synchronizeWorkshopPackages(first.config, content, Collections.emptySet());
        check(cancelled.synchronizedState && cancelled.removed == 1 && findWorkshopOrNull(cancelled.config, "3817409902") == null,
                "An authoritative Workshop removal cancels only that subscribed package");
        check(Files.exists(cache), "Automatic unsubscribe never deletes Steam files");

        writeWorkshopState(content, "0", "0", "3817409902");
        Files.delete(source);
        WorkshopPackageCatalog.ScanResult corrupt = WorkshopPackageCatalog.scan(content);
        check(corrupt.installedIds.contains("3817409902") && corrupt.validPacks.containsKey("3817409902")
                && !corrupt.warnings.isEmpty(), "Missing content is reported but not mistaken for an unsubscribe");
        Files.write(item.resolve(WorkshopPackageCatalog.MANIFEST), "{broken".getBytes(StandardCharsets.UTF_8));
        AudioLibrary.WorkshopSync malformed = library.synchronizeWorkshopPackages(first.config, content, Collections.emptySet());
        check(findWorkshopOrNull(malformed.config, "3817409902") != null && !malformed.warnings.isEmpty()
                && !findRule(malformed.config, installed.id).audio().playable(), "A malformed subscribed manifest disables stale playback and reports the issue");
        manifest.rules.get(0).audio().sourcePath = "../outside.mp3";
        Files.write(item.resolve(WorkshopPackageCatalog.MANIFEST), GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        check(!WorkshopPackageCatalog.scan(content).validPacks.containsKey("3817409902"),
                "Path traversal is rejected even though media hashes are not checked");
    }

    private static WorkshopPackageCatalog.MediaFile mediaRecord(Path root, String relative, Path file) throws Exception {
        WorkshopPackageCatalog.MediaFile record = new WorkshopPackageCatalog.MediaFile();
        record.path = relative; record.bytes = Files.size(file);
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(file)); StringBuilder hex = new StringBuilder();
        for (byte value : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        record.sha256 = hex.toString(); return record;
    }

    private static void writeWorkshopState(Path content, String needsDownload, String needsUpdate, String... ids) throws Exception {
        StringBuilder body = new StringBuilder("\"AppWorkshop\" {\n")
                .append("  \"appid\" \"646570\"\n")
                .append("  \"NeedsDownload\" \"").append(needsDownload).append("\"\n")
                .append("  \"NeedsUpdate\" \"").append(needsUpdate).append("\"\n")
                .append("  \"WorkshopItemsInstalled\" {\n");
        for (String id : ids) body.append("    \"").append(id).append("\" { \"manifest\" \"1\" }\n");
        body.append("  }\n}\n");
        Files.write(content.getParent().getParent().getParent().resolve("workshop").resolve("appworkshop_646570.acf"), body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void packageToggleSemantics() {
        Config config = new Config();
        MusicPack subscribed = new MusicPack(); subscribed.id = "workshop-1"; subscribed.source = MusicPack.Source.SUBSCRIBED; subscribed.workshopId = "1";
        MusicPack local = new MusicPack(); local.id = "local-1"; local.source = MusicPack.Source.LOCAL;
        config.packs.add(subscribed); config.packs.add(local);
        Rule first = rule("first", "订阅规则", Trigger.PLAYER_DEATH); first.packId = subscribed.id;
        Rule second = rule("second", "本地规则", Trigger.PLAYER_DEATH); second.packId = local.id;
        config.rules.add(first); config.rules.add(second);
        ToggleRecorder playback = new ToggleRecorder(); RuleEngine engine = new RuleEngine(config, playback);
        engine.scene("test"); engine.event(Trigger.PLAYER_DEATH, null);
        check(playback.starts.size() == 2, "Enabled packages both trigger normally");
        Config off = config.copy(); off.pack(subscribed.id).enabled = false;
        engine.configurePackageToggle(off, subscribed.id);
        check(off.rules.get(0).enabled, "Disabling a package does not alter its rules individual enabled state");
        engine.event(Trigger.PLAYER_DEATH, null);
        check(playback.starts.size() == 3 && playback.starts.get(2).equals("second"), "Disabled package rules do not fire while other packages remain active");
        Config on = off.copy(); on.pack(subscribed.id).enabled = true;
        engine.configurePackageToggle(on, subscribed.id);
        engine.event(Trigger.PLAYER_DEATH, null);
        check(playback.starts.size() == 5, "Re-enabling a package restores its prior rule states");
    }

    private static Rule rule(String id, String name, Trigger trigger) {
        Rule rule = new Rule(); rule.id = id; rule.name = name; rule.triggers = EnumSet.of(trigger);
        Candidate candidate = new Candidate(); candidate.id = "candidate-" + id; candidate.name = name + ".wav";
        candidate.cachePath = "cache/" + id + ".wav"; candidate.duration = 10; candidate.end = 10;
        rule.candidates.add(candidate); rule.playbackMode = Config.PlaybackMode.ONCE;
        return rule;
    }

    private static Rule workshopRule(String id, String name, String source, String cache) {
        Rule rule = rule(id, name, Trigger.PLAYER_DEATH);
        rule.audio().name = java.nio.file.Paths.get(source).getFileName().toString();
        rule.audio().sourcePath = source; rule.audio().cachePath = cache; rule.upstreamRuleId = id;
        return rule;
    }

    private static Map<String, Path> assets(Path root, String revision, byte[] payload) throws Exception {
        Path source = root.resolve("fixture-" + revision + ".mp3");
        Path cache = root.resolve("fixture-" + revision + ".wav");
        Files.write(source, payload); Files.write(cache, new byte[] {payload[0], payload[0], payload[0], 4});
        Map<String, Path> files = new HashMap<>();
        files.put("media/" + revision + ".mp3", source); files.put("media/" + revision + ".wav", cache);
        return files;
    }

    private static MusicPack findWorkshop(Config config, String workshopId) {
        MusicPack pack = findWorkshopOrNull(config, workshopId);
        if (pack == null) throw new AssertionError("Workshop package missing: " + workshopId);
        return pack;
    }
    private static MusicPack findWorkshopOrNull(Config config, String workshopId) {
        for (MusicPack pack : config.packs) if (pack.source == MusicPack.Source.SUBSCRIBED && workshopId.equals(pack.workshopId)) return pack;
        return null;
    }
    private static Rule findRule(Config config, String packId) {
        for (Rule rule : config.rules) if (packId.equals(rule.packId)) return rule;
        throw new AssertionError("Package rule missing: " + packId);
    }
    private static Rule findRuleOrNull(Config config, String packId) { for (Rule rule : config.rules) if (packId.equals(rule.packId)) return rule; return null; }

    private static final class ToggleRecorder implements RuleEngine.Playback {
        final List<String> starts = new ArrayList<>();
        public void start(Rule rule, String scene) { starts.add(rule.id); }
        public void stopScene(String scene) { }
        public void stopAll() { }
        @Override public void stopRule(String ruleId) { }
    }
}
