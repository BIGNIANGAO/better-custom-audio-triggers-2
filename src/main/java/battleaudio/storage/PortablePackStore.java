package battleaudio.storage;

import battleaudio.model.Config;
import battleaudio.model.Config.Candidate;
import battleaudio.model.Config.Rule;
import battleaudio.model.MusicPack;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Local package manifests are authoritative; settings contain only personal switches. */
final class PortablePackStore {
    private static final String FORMAT = "battleaudio-personal-1";
    private static final String REMOVAL_DIRECTORY = ".removed-packages";
    private final AudioLibrary library;
    private final Path root, packs;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, byte[]> manifests = new LinkedHashMap<>();
    private final Map<String, String> projections = new LinkedHashMap<>();
    private final Map<String, String> loadedFolders = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private Personal personal = new Personal();
    private boolean readingLegacy;

    private static final class Personal {
        String format = FORMAT;
        float backgroundRatio = .2f;
        Map<String, Boolean> packSwitches = new LinkedHashMap<>();
        Map<String, Boolean> ruleSwitches = new LinkedHashMap<>();
        Map<String, String> ruleOwners = new LinkedHashMap<>();
        Set<String> hiddenFolders = new LinkedHashSet<>();
    }

    PortablePackStore(AudioLibrary library) throws IOException {
        this.library = library; root = library.root(); packs = root.resolve("music-packs");
        if (Files.isSymbolicLink(packs)) throw new IOException("本地音乐包目录不能是符号链接");
        Files.createDirectories(packs);
    }

    boolean readingLegacy() { return readingLegacy; }
    List<String> warnings() { return new ArrayList<>(warnings); }

    Config load() throws IOException {
        Path file = root.resolve("settings.json");
        if (Files.isRegularFile(file)) {
            JsonObject json;
            try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                json = new JsonParser().parse(reader).getAsJsonObject();
            } catch (RuntimeException ex) { throw new IOException("个人设置文件无法读取", ex); }
            if (!json.has("format") || !FORMAT.equals(json.get("format").getAsString())) return migrateLegacy();
            personal = gson.fromJson(json, Personal.class);
            if (personal == null || personal.packSwitches == null || personal.ruleSwitches == null || personal.hiddenFolders == null)
                throw new IOException("个人设置不完整，原文件已保留");
            if (personal.ruleOwners == null) personal.ruleOwners = new LinkedHashMap<>();
        }
        Config config = new Config(); config.backgroundRatio = personal.backgroundRatio;
        Path rootRules = root.resolve("root-rules.json");
        if (Files.isRegularFile(rootRules)) {
            try (java.io.Reader reader = Files.newBufferedReader(rootRules, StandardCharsets.UTF_8)) {
                List<Rule> rules = gson.fromJson(reader, new TypeToken<List<Rule>>() { }.getType());
                if (rules == null) throw new IOException("根列表规则文件无效");
                config.rules.addAll(rules);
            } catch (RuntimeException ex) { throw new IOException("根列表规则文件无法读取", ex); }
        }
        scanLocal(config);
        config.refreshPackMembership();
        try { config.validate(); } catch (RuntimeException ex) { throw new IOException("本地音乐包设置无效：" + ex.getMessage(), ex); }
        return config;
    }

    private static String packId(String folder) {
        if (folder.matches("[A-Za-z0-9._-]{1,120}") && !folder.contains("..") && !folder.startsWith("workshop-")) return folder;
        return MusicPackages.stableRuleId("local-folder", folder);
    }

    private Path item(String folder) throws IOException {
        if (folder == null || folder.isEmpty() || folder.equals(".") || folder.equals("..") || folder.contains("/") || folder.contains("\\"))
            throw new IOException("本地音乐包目录无效");
        Path path = packs.resolve(folder).normalize();
        if (!path.getParent().equals(packs) || Files.isSymbolicLink(path)) throw new IOException("本地音乐包目录不安全");
        return path;
    }

    private void scanLocal(Config config) throws IOException {
        List<Path> directories = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(packs)) {
            for (Path path : stream) if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) directories.add(path);
        }
        directories.sort(Comparator.comparing(path -> path.getFileName().toString()));
        for (Path path : directories) {
            String folder = path.getFileName().toString();
            if (folder.equals(REMOVAL_DIRECTORY) || !Files.isRegularFile(path.resolve(WorkshopPackageCatalog.MANIFEST))) continue;
            WorkshopPackageCatalog.Bundle bundle;
            try { bundle = WorkshopPackageCatalog.readBundle(path, null); }
            catch (IOException invalid) { warnings.add("本地音乐包无法读取：" + folder + "：" + invalid.getMessage()); continue; }
            if (!bundle.missingAssets.isEmpty()) warnings.add("本地音乐包 " + folder + " 缺少 " + bundle.missingAssets.size() + " 个音频文件");
            String id = packId(folder);
            if (config.pack(id) != null) throw new IOException("本地音乐包目录 ID 冲突：" + folder);
            MusicPack pack = new MusicPack(); pack.id = id; pack.contentId = bundle.packageId; pack.localFolder = folder;
            pack.name = bundle.name; pack.version = bundle.version; pack.source = MusicPack.Source.LOCAL;
            pack.enabled = personal.packSwitches.containsKey(id) ? personal.packSwitches.get(id) : bundle.enabled;
            config.packs.add(pack);
            Map<String, String> ids = new HashMap<>();
            for (Rule source : bundle.rules) ids.put(source.id, MusicPackages.stableRuleId(id, source.id));
            for (Rule source : bundle.rules) {
                Rule rule = gson.fromJson(gson.toJson(source), Rule.class);
                rule.upstreamRuleId = source.id; rule.id = ids.get(source.id); rule.packId = id;
                remap(rule.pauseWhenRuleIds, ids); remap(rule.prerequisiteRuleIds, ids);
                if (personal.ruleSwitches.containsKey(rule.id)) rule.enabled = personal.ruleSwitches.get(rule.id);
                for (Candidate candidate : rule.candidates) {
                    if (candidate.sourcePath != null) candidate.sourcePath = localPath(folder, candidate.sourcePath);
                    if (candidate.cachePath != null) {
                        String key = candidate.cachePath;
                        candidate.cachePath = localPath(folder, key);
                        if (bundle.missingAssets.contains(key)) candidate.error = "音乐文件不存在";
                        else if ("音乐文件不存在".equals(candidate.error)) candidate.error = null;
                    }
                }
                config.rules.add(rule);
            }
            manifests.put(folder, Files.readAllBytes(path.resolve(WorkshopPackageCatalog.MANIFEST)));
            loadedFolders.put(folder, id);
            projections.put(folder, projection(config, pack));
        }
    }

    void applySwitches(Config config, MusicPack pack) {
        if (personal.packSwitches.containsKey(pack.id)) pack.enabled = personal.packSwitches.get(pack.id);
        for (Rule rule : config.rules) if (pack.id.equals(rule.packId) && personal.ruleSwitches.containsKey(rule.id))
            rule.enabled = personal.ruleSwitches.get(rule.id);
    }

    void forgetSubscription(Config config, MusicPack pack) {
        personal.packSwitches.remove(pack.id);
        for (Rule rule : config.rules) if (pack.id.equals(rule.packId)) {
            personal.ruleSwitches.remove(rule.id); personal.ruleOwners.remove(rule.id);
        }
        personal.ruleOwners.entrySet().removeIf(entry -> {
            if (!pack.id.equals(entry.getValue())) return false;
            personal.ruleSwitches.remove(entry.getKey()); return true;
        });
    }

    void forgetMissingSubscriptions(Set<String> installed) {
        for (String id : new ArrayList<>(personal.packSwitches.keySet())) {
            if (!id.matches("workshop-[0-9]{1,20}") || installed.contains(id.substring("workshop-".length()))) continue;
            personal.packSwitches.remove(id);
            personal.ruleOwners.entrySet().removeIf(entry -> {
                if (!id.equals(entry.getValue())) return false;
                personal.ruleSwitches.remove(entry.getKey()); return true;
            });
        }
    }

    List<AudioLibrary.ManifestChange> prepare(Config config) throws IOException {
        List<AudioLibrary.ManifestChange> changes = new ArrayList<>();
        for (MusicPack pack : config.packs) {
            if (pack.source == MusicPack.Source.SUBSCRIBED) continue;
            if (pack.localFolder == null || pack.localFolder.isEmpty()) pack.localFolder = pack.id;
            Path directory = item(pack.localFolder), manifest = directory.resolve(WorkshopPackageCatalog.MANIFEST);
            if (projection(config, pack).equals(projections.get(pack.localFolder))) continue;
            if (Files.isSymbolicLink(manifest)) throw new IOException("本地音乐包规则文件不能是符号链接");
            byte[] before = Files.exists(manifest) ? Files.readAllBytes(manifest) : null;
            if (!Arrays.equals(before, manifests.get(pack.localFolder)))
                throw new IOException("本地音乐包已被其他程序修改，请重启后再编辑：" + pack.name);
            Files.createDirectories(directory);
            WorkshopPackageCatalog.Bundle bundle;
            if (before == null) {
                bundle = new WorkshopPackageCatalog.Bundle(); bundle.schemaVersion = 1; bundle.packageId = pack.contentId;
                bundle.workshopId = ""; bundle.enabled = true; bundle.rules = new ArrayList<>(); bundle.files = new ArrayList<>();
            } else bundle = WorkshopPackageCatalog.readBundle(directory, null);
            Map<String, Rule> old = new HashMap<>();
            for (Rule rule : bundle.rules) old.put(rule.id, rule);
            Map<String, String> ids = new HashMap<>();
            for (Rule rule : config.rules) if (pack.id.equals(rule.packId))
                ids.put(rule.id, rule.upstreamRuleId == null ? rule.id : rule.upstreamRuleId);
            Map<String, WorkshopPackageCatalog.MediaFile> recorded = new HashMap<>();
            for (WorkshopPackageCatalog.MediaFile file : bundle.files) if (file != null && file.path != null) recorded.put(file.path, file);
            Map<String, WorkshopPackageCatalog.MediaFile> used = new LinkedHashMap<>();
            List<Rule> rules = new ArrayList<>();
            for (Rule rule : config.rules) if (pack.id.equals(rule.packId)) {
                Rule saved = gson.fromJson(gson.toJson(rule), Rule.class);
                saved.id = ids.get(rule.id); saved.packId = null; saved.upstreamRuleId = null;
                if (old.containsKey(saved.id)) saved.enabled = old.get(saved.id).enabled;
                remapRequired(saved.pauseWhenRuleIds, ids); remapRequired(saved.prerequisiteRuleIds, ids);
                for (int i = 0; i < saved.candidates.size(); i++) {
                    Candidate out = saved.candidates.get(i), current = rule.candidates.get(i);
                    if ("音乐文件不存在".equals(out.error)) out.error = null;
                    out.sourcePath = placeAsset(current.sourcePath, pack.localFolder, "source");
                    out.cachePath = placeAsset(current.cachePath, pack.localFolder, "cache");
                    current.sourcePath = out.sourcePath == null ? null : localPath(pack.localFolder, out.sourcePath);
                    current.cachePath = out.cachePath == null ? null : localPath(pack.localFolder, out.cachePath);
                    for (String path : new String[] {out.sourcePath, out.cachePath}) if (path != null) {
                        WorkshopPackageCatalog.MediaFile record = recorded.get(path);
                        if (record == null) { record = new WorkshopPackageCatalog.MediaFile(); record.path = path; }
                        used.put(path, record);
                    }
                }
                rules.add(saved);
            }
            bundle.name = pack.name; bundle.version = pack.version; bundle.rules = rules; bundle.files = new ArrayList<>(used.values());
            byte[] after = gson.toJson(bundle).getBytes(StandardCharsets.UTF_8);
            AudioLibrary.ManifestChange change = new AudioLibrary.ManifestChange();
            change.id = pack.localFolder; change.path = manifest; change.before = before; change.after = after;
            change.projection = projection(config, pack); changes.add(change);
        }
        return changes;
    }

    void committed(List<AudioLibrary.ManifestChange> changes) {
        for (AudioLibrary.ManifestChange change : changes) {
            manifests.put(change.id, change.after); projections.put(change.id, change.projection);
            loadedFolders.put(change.id, packId(change.id));
        }
    }

    private String placeAsset(String path, String folder, String kind) throws IOException {
        if (path == null) return null;
        String normalized = path.replace('\\', '/'), prefix = "music-packs/" + folder + "/";
        if (normalized.startsWith(prefix)) {
            String relative = WorkshopPackageCatalog.safeRelative(normalized.substring(prefix.length()));
            library.managed(normalized); return relative;
        }
        Path source = library.managed(path);
        if (!Files.isRegularFile(source)) throw new IOException("音乐文件不存在，无法复制到本地音乐包");
        String extension = kind.equals("cache") ? ".wav" : ".source";
        String relative = "audio/" + kind + "/" + UUID.randomUUID() + extension;
        Path target = library.managed(localPath(folder, relative));
        Files.createDirectories(target.getParent()); Files.copy(source, target);
        return relative;
    }

    private static String localPath(String folder, String relative) { return "music-packs/" + folder + "/" + relative.replace('\\', '/'); }

    private String projection(Config config, MusicPack pack) {
        JsonObject value = new JsonObject(); value.addProperty("name", pack.name); value.addProperty("version", pack.version);
        com.google.gson.JsonArray rules = new com.google.gson.JsonArray();
        for (Rule rule : config.rules) if (pack.id.equals(rule.packId)) {
            JsonObject json = gson.toJsonTree(rule).getAsJsonObject(); json.remove("enabled"); json.remove("upstreamRuleId"); rules.add(json);
        }
        value.add("rules", rules); return value.toString();
    }

    private static void remap(List<String> values, Map<String, String> ids) {
        for (int i = 0; i < values.size(); i++) if (ids.containsKey(values.get(i))) values.set(i, ids.get(values.get(i)));
    }
    private static void remapRequired(List<String> values, Map<String, String> ids) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            String id = ids.get(values.get(i));
            if (id == null) throw new IOException("请先解除跨音乐包的规则关联");
            values.set(i, id);
        }
    }

    void saveSettings(Config config) throws IOException {
        Path removalRoot = packs.resolve(REMOVAL_DIRECTORY);
        if (Files.isSymbolicLink(removalRoot)) throw new IOException("已移除音乐包的保存位置不可用");
        Map<Path, Path> moved = new LinkedHashMap<>();
        List<Path> removals = new ArrayList<>();
        for (Map.Entry<String, String> entry : loadedFolders.entrySet()) {
            if (config.pack(entry.getValue()) != null) continue;
            Path source = item(entry.getKey()), manifest = source.resolve(WorkshopPackageCatalog.MANIFEST);
            if (Files.exists(source)) {
                if (!Files.isRegularFile(manifest) || Files.isSymbolicLink(manifest)
                        || !Arrays.equals(Files.readAllBytes(manifest), manifests.get(entry.getKey())))
                    throw new IOException("音乐包内容已改变，无法安全移除：" + entry.getKey());
                removals.add(source);
            }
            personal.hiddenFolders.remove(entry.getKey());
        }
        try {
            if (!removals.isEmpty()) Files.createDirectories(removalRoot);
            for (Path source : removals) {
                Path destination = removalRoot.resolve(UUID.randomUUID().toString());
                Files.move(source, destination); moved.put(source, destination);
            }
        } catch (IOException failure) {
            for (Map.Entry<Path, Path> entry : moved.entrySet()) try { Files.move(entry.getValue(), entry.getKey()); }
            catch (IOException recovery) { failure.addSuppressed(recovery); }
            throw failure;
        }
        personal.backgroundRatio = config.backgroundRatio;
        for (MusicPack pack : config.packs) personal.packSwitches.put(pack.id, pack.enabled);
        List<Rule> roots = new ArrayList<>();
        for (Rule rule : config.rules) {
            personal.ruleSwitches.put(rule.id, rule.enabled);
            if (rule.packId == null) personal.ruleOwners.remove(rule.id); else personal.ruleOwners.put(rule.id, rule.packId);
            if (rule.packId == null) roots.add(rule);
        }
        Path rulesFile = root.resolve("root-rules.json"), settingsFile = root.resolve("settings.json");
        byte[] oldRules = Files.exists(rulesFile) ? Files.readAllBytes(rulesFile) : null;
        byte[] newRules = gson.toJson(roots).getBytes(StandardCharsets.UTF_8);
        byte[] oldSettings = Files.exists(settingsFile) ? Files.readAllBytes(settingsFile) : null;
        byte[] newSettings = gson.toJson(personal).getBytes(StandardCharsets.UTF_8);
        boolean rulesWritten = false;
        try {
            library.replaceManifest(rulesFile, oldRules, newRules); rulesWritten = true;
            library.replaceManifest(settingsFile, oldSettings, newSettings);
        }
        catch (IOException | RuntimeException failure) {
            if (rulesWritten) try { library.replaceManifest(rulesFile, newRules, oldRules); } catch (Exception ex) { failure.addSuppressed(ex); }
            for (Map.Entry<Path, Path> entry : moved.entrySet()) try { Files.move(entry.getValue(), entry.getKey()); }
            catch (Exception ex) { failure.addSuppressed(ex); }
            throw failure;
        }
        for (Path source : moved.keySet()) {
            String folder = source.getFileName().toString();
            loadedFolders.remove(folder); manifests.remove(folder); projections.remove(folder);
        }
    }

    void deletePackage(Config config, String packId, String localFolder) throws IOException {
        if (localFolder == null || localFolder.isEmpty()) { saveSettings(config); return; }
        String loadedId = loadedFolders.get(localFolder);
        if (loadedId != null && !packId.equals(loadedId)) throw new IOException("本地音乐包归属校验失败");
        Path source = item(localFolder), manifest = source.resolve(WorkshopPackageCatalog.MANIFEST);
        if (Files.exists(source)) {
            if (!Files.isRegularFile(manifest) || Files.isSymbolicLink(manifest)
                    || loadedId != null && !Arrays.equals(Files.readAllBytes(manifest), manifests.get(localFolder)))
                throw new IOException("本地音乐包内容已被外部修改，无法直接删除");
            deleteTree(source);
        }
        loadedFolders.remove(localFolder); manifests.remove(localFolder); projections.remove(localFolder); personal.hiddenFolders.remove(localFolder);
        personal.packSwitches.remove(packId);
        personal.ruleOwners.entrySet().removeIf(entry -> {
            if (!packId.equals(entry.getValue())) return false;
            personal.ruleSwitches.remove(entry.getKey()); return true;
        });
        saveSettings(config);
    }

    private static void deleteTree(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("本地音乐包包含符号链接，拒绝删除");
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) deleteTree(child);
            }
        }
        Files.deleteIfExists(path);
    }

    Config copyPackage(Config current, String sourcePackId, String copySuffix) throws IOException {
        Config next;
        try { next = MusicPackages.copyAsLocal(current, sourcePackId, copySuffix); }
        catch (RuntimeException ex) { throw new IOException(ex.getMessage(), ex); }
        Set<String> ids = new HashSet<>(); for (MusicPack pack : current.packs) ids.add(pack.id);
        MusicPack copy = null;
        for (MusicPack pack : next.packs) if (!ids.contains(pack.id)) { copy = pack; break; }
        if (copy == null) throw new IOException("无法创建音乐包副本");
        copy.localFolder = copy.id;
        for (Rule source : current.rules) if (sourcePackId.equals(source.packId)) {
            String id = MusicPackages.stableRuleId(copy.id, source.id);
            for (Rule rule : next.rules) if (id.equals(rule.id)) rule.upstreamRuleId = source.id;
        }
        library.save(next);
        return next;
    }

    private Config migrateLegacy() throws IOException {
        readingLegacy = true;
        Config old;
        try { old = library.loadLegacy(); } finally { readingLegacy = false; }
        Map<String, String> replacement = new HashMap<>();
        for (MusicPack pack : old.packs) if (pack.source != MusicPack.Source.SUBSCRIBED) {
            if (!pack.id.equals(packId(pack.id))) throw new IOException("旧音乐包 ID 需要人工迁移；原文件已保留");
            if (Files.exists(item(pack.id))) throw new IOException("本地音乐包目标已存在；未覆盖：" + item(pack.id));
            for (Rule rule : old.rules) if (pack.id.equals(rule.packId)) {
                for (String id : rule.pauseWhenRuleIds) if (!samePackage(old, pack.id, id)) throw new IOException("旧规则存在跨音乐包关联；迁移已停止，原文件保留");
                for (String id : rule.prerequisiteRuleIds) if (!samePackage(old, pack.id, id)) throw new IOException("旧规则存在跨音乐包关联；迁移已停止，原文件保留");
                replacement.put(rule.id, MusicPackages.stableRuleId(pack.id, rule.id));
            }
        }
        if (Files.exists(root.resolve("root-rules.json"))) throw new IOException("根列表规则文件已存在；旧设置未覆盖");
        for (Rule rule : old.rules) if (rule.packId == null) {
            for (String id : rule.pauseWhenRuleIds) if (isSubscriptionRule(old, id)) throw new IOException("旧根列表规则依赖订阅包；迁移已停止，原文件保留");
            for (String id : rule.prerequisiteRuleIds) if (isSubscriptionRule(old, id)) throw new IOException("旧根列表规则依赖订阅包；迁移已停止，原文件保留");
        }
        Path backup = root.resolve("settings.pre-portable-" + UUID.randomUUID() + ".json");
        Files.copy(root.resolve("settings.json"), backup);
        Path staging = root.resolve(".portable-migration-" + UUID.randomUUID());
        Files.createDirectory(staging);
        for (MusicPack pack : old.packs) if (pack.source != MusicPack.Source.SUBSCRIBED) {
            Path destination = staging.resolve(pack.id).normalize();
            if (!destination.getParent().equals(staging)) throw new IOException("迁移目标无效，原文件保留");
            boolean hasRules = old.rules.stream().anyMatch(rule -> pack.id.equals(rule.packId));
            if (hasRules) library.exportPackage(old, pack.id, destination);
            else {
                Files.createDirectories(destination);
                WorkshopPackageCatalog.Bundle empty = new WorkshopPackageCatalog.Bundle(); empty.schemaVersion = 1;
                empty.packageId = pack.contentId; empty.workshopId = ""; empty.name = pack.name; empty.version = pack.version;
                empty.rules = new ArrayList<>(); empty.files = new ArrayList<>();
                Files.write(destination.resolve(WorkshopPackageCatalog.MANIFEST), gson.toJson(empty).getBytes(StandardCharsets.UTF_8));
            }
            WorkshopPackageCatalog.readBundle(destination, null);
            personal.packSwitches.put(pack.id, pack.enabled);
        }
        List<Rule> roots = new ArrayList<>();
        for (Rule rule : old.rules) {
            personal.ruleSwitches.put(replacement.getOrDefault(rule.id, rule.id), rule.enabled);
            if (rule.packId == null) {
                Rule copy = gson.fromJson(gson.toJson(rule), Rule.class);
                remap(copy.pauseWhenRuleIds, replacement); remap(copy.prerequisiteRuleIds, replacement); roots.add(copy);
            }
        }
        for (MusicPack pack : old.packs) if (pack.source == MusicPack.Source.SUBSCRIBED) {
            personal.packSwitches.put(pack.id, pack.enabled);
            for (Rule rule : old.rules) if (pack.id.equals(rule.packId)) personal.ruleOwners.put(rule.id, pack.id);
        }
        personal.backgroundRatio = old.backgroundRatio;
        List<Path> moved = new ArrayList<>();
        try {
            for (MusicPack pack : old.packs) if (pack.source != MusicPack.Source.SUBSCRIBED) {
                Path destination = item(pack.id);
                Files.move(staging.resolve(pack.id), destination);
                moved.add(destination);
            }
            Config next = new Config(); next.backgroundRatio = personal.backgroundRatio; next.rules.addAll(roots); scanLocal(next);
            next.refreshPackMembership(); next.validate();
            saveSettings(next);
            try { Files.deleteIfExists(staging); } catch (IOException ignored) { }
            return next;
        } catch (IOException | RuntimeException failure) {
            Collections.reverse(moved);
            for (Path path : moved) try { Files.move(path, staging.resolve(path.getFileName())); }
            catch (IOException recovery) { failure.addSuppressed(recovery); }
            throw failure;
        }
    }

    private static boolean samePackage(Config config, String packId, String id) {
        for (Rule rule : config.rules) if (id.equals(rule.id)) return packId.equals(rule.packId);
        return false;
    }

    private static boolean isSubscriptionRule(Config config, String id) {
        for (Rule rule : config.rules) if (id.equals(rule.id)) {
            MusicPack pack = config.pack(rule.packId);
            return pack != null && pack.source == MusicPack.Source.SUBSCRIBED;
        }
        return false;
    }
}
