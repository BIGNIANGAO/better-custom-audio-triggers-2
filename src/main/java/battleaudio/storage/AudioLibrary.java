package battleaudio.storage;

import battleaudio.model.Config;
import battleaudio.model.Config.Rule;
import battleaudio.model.Config.Candidate;
import battleaudio.model.MusicPack;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

public final class AudioLibrary {
    public static final String DECODE_ERROR = "音频格式不支持或文件已损坏，请更换文件";
    private final Path root;
    private final Path ffmpeg;
    private final Path ownershipFile;
    private final PortablePackStore portable;
    private Path workshopContentRoot;
    private final Map<String, byte[]> workshopManifests = new LinkedHashMap<>();
    private final Map<String, String> workshopProjections = new LinkedHashMap<>();
    static final class ManifestChange {
        String id, projection;
        Path path;
        byte[] before, after;
    }
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private static final class PackageOwner {
        String id;
        MusicPack.Source source;
        String workshopId;
        List<MusicPack.OwnedFile> files = new java.util.ArrayList<>();
        List<String> protectedFiles = new java.util.ArrayList<>();
    }

    public AudioLibrary(Path root, Path ffmpeg) throws IOException {
        this(root, ffmpeg, false);
    }

    public AudioLibrary(Path root, Path ffmpeg, boolean portablePacks) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.ffmpeg = ffmpeg;
        this.ownershipFile = this.root.resolve("managed-files.json");
        Files.createDirectories(this.root.resolve("library"));
        Files.createDirectories(this.root.resolve("cache"));
        Files.createDirectories(this.root.resolve("packs"));
        this.portable = portablePacks ? new PortablePackStore(this) : null;
    }

    public Path root() { return root; }

    public List<String> localPackageWarnings() {
        return portable == null ? java.util.Collections.emptyList() : portable.warnings();
    }

    public void setWorkshopContentRoot(Path contentRoot) {
        workshopContentRoot = contentRoot == null ? null : contentRoot.toAbsolutePath().normalize();
    }

    public Config load() throws IOException {
        return portable == null ? loadLegacy() : portable.load();
    }

    Config loadLegacy() throws IOException {
        Path file = root.resolve("settings.json");
        if (!Files.exists(file)) EmbeddedPresets.install(this);
        if (!Files.exists(file)) return new Config();
        try {
            com.google.gson.JsonObject json;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                json = new com.google.gson.JsonParser().parse(reader).getAsJsonObject();
            }
            int originalSchema = json.has("schemaVersion") ? json.get("schemaVersion").getAsInt() : 1;
            Config config = Config.fromJson(json);
            if (config == null) throw new IllegalArgumentException("音频设置为空");
            config.migrate();
            config.validate();
            for (Rule rule : config.rules) for (Candidate candidate : rule.candidates) {
                try {
                    if (candidate.cachePath != null && !Files.isRegularFile(managed(candidate.cachePath)))
                        candidate.error = "音乐文件不存在";
                } catch (IllegalArgumentException unavailable) {
                    candidate.error = "音乐文件不可用";
                }
            }
            registerReferencedFiles(config);
            if (originalSchema < 4) {
                preserveMigrationBackup(file);
                materializeMigratedPackage(config);
            }
            refreshPackageOwnership(config);
            if (originalSchema < 4 || !Files.exists(ownershipFile)) saveSettings(config);
            else writeOwnership(readOwnership());
            return config;
        } catch (RuntimeException ex) {
            throw new IOException("无法读取音频设置：" + ex.getMessage(), ex);
        }
    }

    public void save(Config config) throws IOException {
        config.refreshPackMembership();
        config.validate();
        detachMovedWorkshopAssets(config);
        List<ManifestChange> changes = prepareSubscribedManifests(config);
        List<ManifestChange> localChanges = portable == null ? java.util.Collections.emptyList() : portable.prepare(config);
        List<ManifestChange> written = new java.util.ArrayList<>();
        try {
            List<ManifestChange> combined = new java.util.ArrayList<>(changes); combined.addAll(localChanges);
            for (ManifestChange change : combined) {
                if (!java.util.Arrays.equals(change.before, change.after)) {
                    replaceManifest(change.path, change.before, change.after);
                    written.add(change);
                }
            }
            saveSettings(config);
        } catch (IOException | RuntimeException failure) {
            java.util.Collections.reverse(written);
            for (ManifestChange change : written) {
                try { replaceManifest(change.path, change.after, change.before); }
                catch (Exception recovery) { failure.addSuppressed(recovery); }
            }
            throw failure;
        }
        for (ManifestChange change : changes) {
            workshopManifests.put(change.id, change.after);
            workshopProjections.put(change.id, change.projection);
        }
        if (portable != null) portable.committed(localChanges);
    }

    /** Permanently deletes one local package folder, then saves the remaining settings. */
    public void deletePackage(Config config, String packId, String localFolder) throws IOException {
        config.refreshPackMembership(); config.validate();
        if (config.pack(packId) != null) throw new IOException("音乐包删除状态无效");
        if (portable != null) { portable.deletePackage(config, packId, localFolder); return; }
        save(config);
    }

    void replaceManifest(Path manifest, byte[] expected, byte[] updated) throws IOException {
        if (Files.isSymbolicLink(manifest) || !java.util.Arrays.equals(Files.exists(manifest) ? Files.readAllBytes(manifest) : null, expected))
            throw new IOException("工坊音乐包在保存期间发生变化，请重启游戏后重试");
        if (updated == null) {
            if (expected != null) Files.deleteIfExists(manifest);
            return;
        }
        Path temp = Files.createTempFile(manifest.getParent(), ".battleaudio-", ".tmp");
        try {
            Files.write(temp, updated);
            if (Files.isSymbolicLink(manifest) || !java.util.Arrays.equals(Files.exists(manifest) ? Files.readAllBytes(manifest) : null, expected))
                throw new IOException("工坊音乐包在保存期间发生变化，请重启游戏后重试");
            try { Files.move(temp, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, manifest, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private void detachMovedWorkshopAssets(Config config) throws IOException {
        for (Rule rule : config.rules) {
            MusicPack pack = config.pack(rule.packId);
            if (pack != null && (pack.source == MusicPack.Source.SUBSCRIBED || portable != null)) continue;
            for (Candidate candidate : rule.candidates) {
                candidate.sourcePath = detachWorkshopAsset(candidate.sourcePath, "library", ".source");
                candidate.cachePath = detachWorkshopAsset(candidate.cachePath, "cache", ".wav");
            }
        }
    }

    private String detachWorkshopAsset(String path, String directory, String suffix) throws IOException {
        if (path == null) return null;
        String normalized = path.replace('\\', '/');
        if (!normalized.startsWith("workshop/") && (portable == null || !normalized.startsWith("music-packs/"))) return path;
        Path source = managed(path);
        if (!Files.isRegularFile(source)) throw new IOException("音乐文件不存在，无法移动规则");
        Path target = root.resolve(directory).resolve(UUID.randomUUID() + suffix);
        Files.copy(source, target);
        registerOwned(target);
        return root.relativize(target).toString().replace('\\', '/');
    }

    private List<ManifestChange> prepareSubscribedManifests(Config config) throws IOException {
        List<ManifestChange> changes = new java.util.ArrayList<>();
        for (MusicPack pack : config.packs) {
            if (pack.source != MusicPack.Source.SUBSCRIBED) continue;
            if (workshopProjection(config, pack).equals(workshopProjections.get(pack.workshopId))) continue;
            if (!workshopManifests.containsKey(pack.workshopId))
                throw new IOException("工坊音乐包不可用，暂时不能保存编辑：" + pack.name);
            Path item = workshopItem(pack.workshopId);
            Path manifest = item.resolve(WorkshopPackageCatalog.MANIFEST);
            byte[] original = Files.readAllBytes(manifest);
            if (!java.util.Arrays.equals(original, workshopManifests.get(pack.workshopId)))
                throw new IOException("工坊音乐包内容已改变，请重启游戏后再编辑：" + pack.name);
            WorkshopPackageCatalog.Bundle bundle = WorkshopPackageCatalog.readBundle(item, pack.workshopId);
            Map<String, Rule> oldRules = new LinkedHashMap<>();
            for (Rule rule : bundle.rules) oldRules.put(rule.id, rule);
            Map<String, String> ids = new LinkedHashMap<>();
            for (Rule rule : config.rules) if (pack.id.equals(rule.packId)) {
                String sourceId = rule.upstreamRuleId == null ? rule.id : rule.upstreamRuleId;
                ids.put(rule.id, sourceId);
            }
            List<Rule> rules = new java.util.ArrayList<>();
            Map<String, WorkshopPackageCatalog.MediaFile> records = new LinkedHashMap<>();
            for (WorkshopPackageCatalog.MediaFile record : bundle.files)
                if (record != null && record.path != null) records.put(record.path.replace('\\', '/'), record);
            Map<String, WorkshopPackageCatalog.MediaFile> used = new LinkedHashMap<>();
            for (Rule rule : config.rules) if (pack.id.equals(rule.packId)) {
                Rule saved = new Gson().fromJson(new Gson().toJson(rule), Rule.class);
                String sourceId = ids.get(rule.id);
                saved.id = sourceId; saved.packId = null; saved.upstreamRuleId = null;
                Rule old = oldRules.get(sourceId);
                if (old != null) saved.enabled = old.enabled;
                remapWorkshopRelations(saved.pauseWhenRuleIds, ids);
                remapWorkshopRelations(saved.prerequisiteRuleIds, ids);
                for (int i = 0; i < saved.candidates.size(); i++) {
                    Candidate candidate = saved.candidates.get(i), current = rule.candidates.get(i);
                    if (isWorkshopAvailabilityError(candidate.error)) {
                        candidate.error = null;
                        if (old != null) for (Candidate prior : old.candidates)
                            if (candidate.id.equals(prior.id) && !isWorkshopAvailabilityError(prior.error)) candidate.error = prior.error;
                    }
                    candidate.sourcePath = placeWorkshopAsset(current.sourcePath, pack.workshopId, item, "source");
                    candidate.cachePath = placeWorkshopAsset(current.cachePath, pack.workshopId, item, "cache");
                    current.sourcePath = candidate.sourcePath == null ? null : workshopPath(pack.workshopId, candidate.sourcePath);
                    current.cachePath = candidate.cachePath == null ? null : workshopPath(pack.workshopId, candidate.cachePath);
                    for (String path : new String[] {candidate.sourcePath, candidate.cachePath}) if (path != null) {
                        WorkshopPackageCatalog.MediaFile record = records.get(path);
                        if (record == null) { record = new WorkshopPackageCatalog.MediaFile(); record.path = path; }
                        used.put(path, record);
                    }
                }
                rules.add(saved);
            }
            bundle.name = pack.name; bundle.version = pack.version; bundle.rules = rules;
            bundle.files = new java.util.ArrayList<>(used.values());
            byte[] updated = gson.toJson(bundle).getBytes(StandardCharsets.UTF_8);
            com.google.gson.JsonElement before = new com.google.gson.JsonParser().parse(new String(original, StandardCharsets.UTF_8));
            com.google.gson.JsonElement after = new com.google.gson.JsonParser().parse(new String(updated, StandardCharsets.UTF_8));
            ManifestChange change = new ManifestChange();
            change.id = pack.workshopId; change.path = manifest; change.before = original;
            change.after = before.equals(after) ? original : updated;
            change.projection = workshopProjection(config, pack);
            changes.add(change);
        }
        return changes;
    }

    private String workshopProjection(Config config, MusicPack pack) {
        com.google.gson.JsonObject state = new com.google.gson.JsonObject();
        state.addProperty("name", pack.name); state.addProperty("version", pack.version);
        com.google.gson.JsonArray rules = new com.google.gson.JsonArray();
        for (Rule rule : config.rules) if (pack.id.equals(rule.packId)) {
            com.google.gson.JsonObject entry = gson.toJsonTree(rule).getAsJsonObject();
            entry.remove("enabled"); entry.remove("upstreamRuleId");
            rules.add(entry);
        }
        state.add("rules", rules);
        return state.toString();
    }

    private static boolean isWorkshopAvailabilityError(String error) {
        return "音乐文件不存在".equals(error) || "音乐文件不可用".equals(error) || "工坊音乐包暂时不可用".equals(error);
    }

    private static void remapWorkshopRelations(List<String> relations, Map<String, String> ids) throws IOException {
        for (int i = 0; i < relations.size(); i++) {
            String id = ids.get(relations.get(i));
            if (id == null) throw new IOException("订阅包的规则不能关联包外规则，请先解除关联");
            relations.set(i, id);
        }
    }

    private Path workshopItem(String id) throws IOException {
        if (workshopContentRoot == null || id == null || !id.matches("[0-9]{1,20}")) throw new IOException("工坊音乐目录不可用");
        Path item = workshopContentRoot.resolve(id);
        if (Files.isSymbolicLink(workshopContentRoot) || Files.isSymbolicLink(item) || !Files.isDirectory(item))
            throw new IOException("工坊音乐目录不可用");
        return item;
    }

    private static String workshopPath(String id, String relative) { return "workshop/" + id + "/" + relative.replace('\\', '/'); }

    private String placeWorkshopAsset(String path, String id, Path item, String kind) throws IOException {
        if (path == null) return null;
        String prefix = "workshop/" + id + "/";
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith(prefix)) {
            String relative = WorkshopPackageCatalog.safeRelative(normalized.substring(prefix.length()));
            managed(normalized);
            return relative;
        }
        Path source = managed(path);
        if (!Files.isRegularFile(source)) throw new IOException("音乐文件不存在，无法添加到订阅包");
        String relative = "audio/user/" + kind + "/" + UUID.randomUUID() + extension(source.getFileName().toString());
        Path destination = managed(workshopPath(id, relative));
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination);
        return relative;
    }

    private void saveSettings(Config config) throws IOException {
        if (portable != null && !portable.readingLegacy()) { portable.saveSettings(config); return; }
        config.refreshPackMembership();
        config.validate();
        for (Rule rule : config.rules) for (Candidate candidate : rule.candidates) {
            if (candidate.sourcePath != null && (workshopContentRoot != null || !candidate.sourcePath.replace('\\', '/').startsWith("workshop/"))) managed(candidate.sourcePath);
            if (candidate.cachePath != null && (workshopContentRoot != null || !candidate.cachePath.replace('\\', '/').startsWith("workshop/"))) managed(candidate.cachePath);
        }
        registerReferencedFiles(config);
        refreshPackageOwnership(config);
        writeOwnership(readOwnership());
        Path target = root.resolve("settings.json");
        Path temporary = Files.createTempFile(root, "settings-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { gson.toJson(config, writer); }
            if (Files.exists(target)) Files.copy(target, root.resolve("settings.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    /** Creates an isolated on-disk copy; subscriptions are never modified. */
    public Config copyPackage(Config current, String sourcePackId) throws IOException {
        return copyPackage(current, sourcePackId, " 副本");
    }

    public Config copyPackage(Config current, String sourcePackId, String copySuffix) throws IOException {
        if (portable != null) return portable.copyPackage(current, sourcePackId, copySuffix);
        Config next = battleaudio.storage.MusicPackages.copyAsLocal(current, sourcePackId, copySuffix);
        MusicPack destination = null;
        Set<String> oldIds = new HashSet<>();
        for (MusicPack pack : current.packs) oldIds.add(pack.id);
        for (MusicPack pack : next.packs) if (!oldIds.contains(pack.id)) { destination = pack; break; }
        if (destination == null) throw new IOException("无法创建新音乐包");
        final String destinationId = destination.id;
        MusicPack source = current.pack(sourcePackId);
        if (source == null) throw new IOException("源音乐包不存在");
        Path packageRoot = root.resolve("packs").resolve(destination.id);
        if (Files.exists(packageRoot)) throw new IOException("新音乐包目录已存在，未覆盖任何文件");
        String revision = UUID.randomUUID().toString();
        Path staging = root.resolve("packs").resolve(".staging-" + destination.id + "-" + revision);
        Path stagingVersion = staging.resolve("versions").resolve(revision);
        Path versionRoot = packageRoot.resolve("versions").resolve(revision);
        Map<String, String> copiedPaths = new LinkedHashMap<>();
        Map<Path, String> stagedFiles = new LinkedHashMap<>();
        try {
            Files.createDirectories(stagingVersion.resolve("source"));
            Files.createDirectories(stagingVersion.resolve("cache"));
            for (Rule rule : current.rules) if (source.id.equals(rule.packId)) {
                Rule cloned = next.rules.stream().filter(item -> destinationId.equals(item.packId)
                        && item.id.equals(MusicPackages.stableRuleId(destinationId, rule.id))).findFirst()
                        .orElseThrow(() -> new IOException("复制后的规则索引不完整"));
                if (rule.candidates.size() != cloned.candidates.size()) throw new IOException("复制后的候选数量不一致");
                for (int index = 0; index < rule.candidates.size(); index++) {
                    Candidate from = rule.candidates.get(index), to = cloned.candidates.get(index);
                    to.sourcePath = copyPackageAsset(from.sourcePath, stagingVersion.resolve("source"), versionRoot.resolve("source"),
                            destination.id, revision, "source", copiedPaths, stagedFiles, destination);
                    to.cachePath = copyPackageAsset(from.cachePath, stagingVersion.resolve("cache"), versionRoot.resolve("cache"),
                            destination.id, revision, "cache", copiedPaths, stagedFiles, destination);
                }
            }
            PackageOwner owner = new PackageOwner();
            owner.id = destination.id; owner.source = destination.source; owner.workshopId = "";
            owner.files.addAll(destination.ownedFiles);
            writeJson(staging.resolve("package-owner.json"), owner);
            stagedFiles.put(staging.resolve("package-owner.json"), sha256(staging.resolve("package-owner.json")));
            Files.move(staging, packageRoot, StandardCopyOption.ATOMIC_MOVE);
            destination.ownedFiles = owner.files;
            writeJson(packageRoot.resolve("package-owner.json"), owner);
            return next;
        } catch (Exception ex) {
            discardStaging(stagedFiles);
            try { deleteEmptyTree(staging); } catch (IOException ignored) { }
            if (ex instanceof IOException) throw (IOException) ex;
            throw new IOException("复制音乐包失败；原订阅包未更改：" + ex.getMessage(), ex);
        }
    }

    private void preserveMigrationBackup(Path settings) throws IOException {
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path backup = root.resolve("settings.pre-pack-migration-" + stamp + ".json");
        int suffix = 1;
        while (Files.exists(backup)) backup = root.resolve("settings.pre-pack-migration-" + stamp + "-" + suffix++ + ".json");
        Files.copy(settings, backup);
        Path previousBackup = root.resolve("settings.json.bak");
        if (Files.isRegularFile(previousBackup)) {
            Path preserved = root.resolve("settings.pre-pack-migration-" + stamp + ".previous-backup.json");
            Files.copy(previousBackup, preserved, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void materializeMigratedPackage(Config config) throws IOException {
        MusicPack pack = null;
        for (MusicPack candidate : config.packs) if (candidate.source == MusicPack.Source.MIGRATED) { pack = candidate; break; }
        if (pack == null) return;
        boolean hasRules = false;
        for (Rule rule : config.rules) if (pack.id.equals(rule.packId)) { hasRules = true; break; }
        if (!hasRules) return;
        Path packageRoot = root.resolve("packs").resolve(pack.id);
        Path staging = root.resolve("packs").resolve(".staging-" + pack.id + "-" + UUID.randomUUID());
        String revision = UUID.randomUUID().toString();
        Path stagingVersion = staging.resolve("versions").resolve(revision);
        Path finalVersion = packageRoot.resolve("versions").resolve(revision);
        Map<String, String> copied = new LinkedHashMap<>();
        Map<Path, String> stagedFiles = new LinkedHashMap<>();
        PackageOwner owner = new PackageOwner(); owner.id = pack.id; owner.source = pack.source; owner.workshopId = "";
        if (Files.exists(packageRoot)) validatePackageOwner(packageRoot, pack);
        try {
            Files.createDirectories(stagingVersion.resolve("source"));
            Files.createDirectories(stagingVersion.resolve("cache"));
            for (Rule rule : config.rules) if (pack.id.equals(rule.packId)) for (Candidate candidate : rule.candidates) {
                if (candidate.sourcePath != null && Files.isRegularFile(managed(candidate.sourcePath)))
                    candidate.sourcePath = copyPackageAsset(candidate.sourcePath, stagingVersion.resolve("source"), finalVersion.resolve("source"),
                            pack.id, revision, "source", copied, stagedFiles, pack);
                if (candidate.cachePath != null && Files.isRegularFile(managed(candidate.cachePath)))
                    candidate.cachePath = copyPackageAsset(candidate.cachePath, stagingVersion.resolve("cache"), finalVersion.resolve("cache"),
                            pack.id, revision, "cache", copied, stagedFiles, pack);
            }
            owner.files = new java.util.ArrayList<>(pack.ownedFiles);
            Files.createDirectories(staging);
            writeJson(staging.resolve("package-owner.json"), owner);
            stagedFiles.put(staging.resolve("package-owner.json"), sha256(staging.resolve("package-owner.json")));
            if (!Files.exists(packageRoot)) Files.move(staging, packageRoot, StandardCopyOption.ATOMIC_MOVE);
            else {
                Files.move(stagingVersion, finalVersion, StandardCopyOption.ATOMIC_MOVE);
                mergePackageOwner(packageRoot, owner);
                discardStaging(stagedFiles);
                deleteEmptyTree(staging);
            }
        } catch (Exception ex) {
            discardStaging(stagedFiles);
            try { deleteEmptyTree(staging); } catch (IOException ignored) { }
            if (ex instanceof IOException) throw (IOException) ex;
            throw new IOException("旧版音频迁移失败；旧设置与音频均已保留：" + ex.getMessage(), ex);
        }
    }

    private void validatePackageOwner(Path packageRoot, MusicPack pack) throws IOException {
        Path marker = packageRoot.resolve("package-owner.json");
        if (!Files.isRegularFile(marker) || Files.isSymbolicLink(marker)) throw new IOException("音乐包目录缺少有效归属标记，拒绝覆盖");
        try (Reader reader = Files.newBufferedReader(marker, StandardCharsets.UTF_8)) {
            PackageOwner owner = gson.fromJson(reader, PackageOwner.class);
            if (owner == null || !pack.id.equals(owner.id) || owner.source != pack.source
                    || pack.source == MusicPack.Source.SUBSCRIBED && !pack.workshopId.equals(owner.workshopId))
                throw new IOException("音乐包归属不匹配，拒绝覆盖");
        } catch (RuntimeException malformed) { throw new IOException("音乐包归属标记损坏，拒绝覆盖", malformed); }
    }

    private void mergePackageOwner(Path packageRoot, PackageOwner addition) throws IOException {
        Path marker = packageRoot.resolve("package-owner.json");
        PackageOwner owner = null;
        if (Files.isRegularFile(marker) && !Files.isSymbolicLink(marker)) {
            try (Reader reader = Files.newBufferedReader(marker, StandardCharsets.UTF_8)) { owner = gson.fromJson(reader, PackageOwner.class); }
        }
        if (owner == null || !addition.id.equals(owner.id) || owner.source != addition.source)
            throw new IOException("音乐包文件归属记录冲突，旧内容未覆盖");
        Map<String, MusicPack.OwnedFile> byPath = new LinkedHashMap<>();
        if (owner.files != null) for (MusicPack.OwnedFile file : owner.files) byPath.put(file.path, file);
        for (MusicPack.OwnedFile file : addition.files) byPath.put(file.path, file);
        owner.files = new java.util.ArrayList<>(byPath.values());
        writeJson(marker, owner);
    }

    private void discardStaging(Map<Path, String> stagedFiles) throws IOException {
        for (Map.Entry<Path, String> entry : stagedFiles.entrySet()) {
            Path path = entry.getKey();
            if (Files.isRegularFile(path) && !Files.isSymbolicLink(path) && entry.getValue().equals(sha256(path))) Files.delete(path);
        }
    }

    /** Creates a new app-managed export snapshot for local review and optional Workshop publishing. */
    public Path exportPackageForWorkshop(Config current, String packId) throws IOException {
        if (current == null) throw new IOException("\u97f3\u4e50\u5305\u914d\u7f6e\u7f3a\u5931");
        try { current.validate(); }
        catch (RuntimeException invalid) { throw new IOException("\u97f3\u4e50\u5305\u8bbe\u7f6e\u65e0\u6548\uff0c\u65e0\u6cd5\u5bfc\u51fa\uff1a" + invalid.getMessage(), invalid); }
        MusicPack pack = current.pack(packId);
        if (pack == null) throw new IOException("\u97f3\u4e50\u5305\u4e0d\u5b58\u5728");
        if (pack.source == MusicPack.Source.SUBSCRIBED)
            throw new IOException("\u8ba2\u9605\u97f3\u4e50\u5305\u4e3a\u53ea\u8bfb\uff1b\u8bf7\u5148\u590d\u5236\u4e3a\u65b0\u97f3\u4e50\u5305\uff0c\u518d\u5bfc\u51fa\u53d1\u5e03");
        boolean hasPlayableRule = false;
        for (Rule rule : current.rules) if (pack.id.equals(rule.packId)) {
            if (rule.playable()) hasPlayableRule = true;
        }
        if (!hasPlayableRule) throw new IOException("\u97f3\u4e50\u5305\u6ca1\u6709\u53ef\u64ad\u653e\u89c4\u5219\uff0c\u8bf7\u5148\u914d\u7f6e\u97f3\u9891\u518d\u5bfc\u51fa");
        Path exportRoot = root.resolve("workshop-exports");
        if (Files.isSymbolicLink(exportRoot)) throw new IOException("\u97f3\u4e50\u5305\u5bfc\u51fa\u76ee\u5f55\u4e0d\u80fd\u662f\u7b26\u53f7\u94fe\u63a5");
        Path contentParent = exportRoot.resolve(pack.contentId);
        if (Files.isSymbolicLink(contentParent)) throw new IOException("\u97f3\u4e50\u5305\u5bfc\u51fa\u76ee\u5f55\u4e0d\u80fd\u662f\u7b26\u53f7\u94fe\u63a5");
        Files.createDirectories(contentParent);
        String safeName = pack.name.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]+", "-").trim();
        safeName = safeName.replaceAll("[ .]+$", "");
        if (safeName.isEmpty()) safeName = "music-pack";
        if (safeName.length() > 48) safeName = safeName.substring(0, 48);
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path destination = contentParent.resolve(safeName + "-" + stamp + "-" + UUID.randomUUID().toString().substring(0, 8));
        return exportPackage(current, packId, destination);
    }

    /** Builds a new, independent Workshop-ready folder. It never uploads or overwrites an existing destination. */
    public Path exportPackage(Config current, String packId, Path destination) throws IOException {
        MusicPack pack = current.pack(packId);
        if (pack == null) throw new IOException("音乐包不存在");
        if (destination == null) throw new IOException("导出目录未指定");
        Path target = destination.toAbsolutePath().normalize();
        if (Files.exists(target)) throw new IOException("导出目标已存在；请选择一个空闲的新目录");
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("导出位置的父文件夹不存在");
        Path staging = parent.resolve(".battleaudio-export-" + UUID.randomUUID());
        Map<Path, String> stagedFiles = new LinkedHashMap<>();
        try {
            Path audioRoot = staging.resolve("audio");
            Files.createDirectories(audioRoot.resolve("source"));
            Files.createDirectories(audioRoot.resolve("cache"));
            WorkshopPackageCatalog.Bundle bundle = new WorkshopPackageCatalog.Bundle();
            bundle.schemaVersion = 1;
            bundle.packageId = pack.contentId;
            bundle.workshopId = "";
            bundle.name = pack.name;
            bundle.version = pack.version;
            bundle.enabled = true;
            bundle.rules = new java.util.ArrayList<>();
            bundle.files = new java.util.ArrayList<>();
            Map<String, String> copied = new LinkedHashMap<>();
            Map<String, String> ruleIds = new LinkedHashMap<>();
            Set<String> outputIds = new HashSet<>();
            for (Rule rule : current.rules) if (pack.id.equals(rule.packId)) {
                String id = portable != null && rule.upstreamRuleId != null ? rule.upstreamRuleId : rule.id;
                if (!outputIds.add(id)) throw new IOException("音乐包规则 ID 重复");
                ruleIds.put(rule.id, id);
            }
            for (Rule rule : current.rules) if (pack.id.equals(rule.packId)) {
                Rule exported = new Gson().fromJson(new Gson().toJson(rule), Rule.class);
                exported.id = ruleIds.get(rule.id);
                exported.packId = null; exported.upstreamRuleId = null;
                remapWorkshopRelations(exported.pauseWhenRuleIds, ruleIds);
                remapWorkshopRelations(exported.prerequisiteRuleIds, ruleIds);
                for (Candidate candidate : exported.candidates) {
                    candidate.sourcePath = exportMedia(candidate, staging, "source", candidate.sourcePath, copied, bundle, stagedFiles);
                    candidate.cachePath = exportMedia(candidate, staging, "cache", candidate.cachePath, copied, bundle, stagedFiles);
                }
                bundle.rules.add(exported);
            }
            if (bundle.rules.isEmpty()) throw new IOException("空音乐包不能导出");
            bundle.enabled = true;
            writeJson(staging.resolve(WorkshopPackageCatalog.MANIFEST), bundle);
            stagedFiles.put(staging.resolve(WorkshopPackageCatalog.MANIFEST), sha256(staging.resolve(WorkshopPackageCatalog.MANIFEST)));
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (Exception ex) {
            discardStaging(stagedFiles);
            try { deleteEmptyTree(staging); } catch (IOException ignored) { }
            if (ex instanceof IOException) throw (IOException) ex;
            throw new IOException("音乐包导出失败；没有创建上传或覆盖远端项目：" + ex.getMessage(), ex);
        }
    }

    private String exportMedia(Candidate candidate, Path staging, String kind,
            String path, Map<String, String> copied, WorkshopPackageCatalog.Bundle bundle,
            Map<Path, String> stagedFiles) throws IOException {
        if (path == null) return null;
        String prior = copied.get(path);
        if (prior != null) return prior;
        Path source = managed(path);
        if (!Files.isRegularFile(source) || Files.isSymbolicLink(source)) throw new IOException("音乐包引用的媒体缺失：" + path);
        String suffix = extension(source.getFileName().toString());
        if ("source".equals(kind)) {
            String named = extension(candidate.name);
            if (java.util.Arrays.asList(".mp3", ".wav", ".ogg", ".flac", ".m4a", ".aac", ".aiff", ".aif", ".wma").contains(named.toLowerCase(java.util.Locale.ROOT))) suffix = named;
        }
        String relative = "audio/" + kind + "/" + UUID.randomUUID() + suffix;
        Path output = staging.resolve(relative);
        Files.createDirectories(output.getParent());
        Files.copy(source, output);
        String hash = sha256(output);
        stagedFiles.put(output, hash);
        WorkshopPackageCatalog.MediaFile record = new WorkshopPackageCatalog.MediaFile();
        record.path = relative; record.sha256 = hash; record.bytes = Files.size(output);
        bundle.files.add(record);
        copied.put(path, relative);
        return relative;
    }

    /** Removes subscription metadata without touching Steam-owned content or independent copies. */
    public Config unsubscribe(Config current, String workshopId, Set<String> activeCaches) throws IOException {
        if (workshopId == null || !workshopId.matches("[0-9]{1,20}")) throw new IOException("工坊项目 ID 无效");
        MusicPack subscribed = null;
        for (MusicPack pack : current.packs) if (pack.source == MusicPack.Source.SUBSCRIBED && workshopId.equals(pack.workshopId)) {
            subscribed = pack; break;
        }
        if (subscribed == null) return current.copy();
        Config next;
        try { next = MusicPackages.unsubscribe(current, workshopId); }
        catch (RuntimeException invalid) { throw new IOException("无法取消订阅音乐包的本地配置：" + invalid.getMessage(), invalid); }
        if (portable != null) portable.forgetSubscription(current, subscribed);
        workshopManifests.remove(workshopId);
        workshopProjections.remove(workshopId);
        saveSettings(next);
        return next;
    }

    public static final class WorkshopSync {
        public final Config config;
        public final boolean synchronizedState;
        public final List<String> warnings;
        public final int installedOrUpdated;
        public final int removed;
        private WorkshopSync(Config config, boolean synchronizedState, List<String> warnings, int installedOrUpdated, int removed) {
            this.config = config; this.synchronizedState = synchronizedState; this.warnings = warnings;
            this.installedOrUpdated = installedOrUpdated; this.removed = removed;
        }
    }

    /** Uses Steam's installed-item inventory as the authority; never treats a missing/partial state file as unsubscribe. */
    public WorkshopSync synchronizeWorkshopPackages(Config current, Path contentRoot, Set<String> activeCaches) throws IOException {
        setWorkshopContentRoot(contentRoot);
        workshopManifests.clear(); workshopProjections.clear();
        WorkshopPackageCatalog.ScanResult scan;
        try { scan = WorkshopPackageCatalog.scan(contentRoot); }
        catch (IOException malformedState) {
            List<String> warnings = new java.util.ArrayList<>();
            warnings.add("Steam 创意工坊安装状态无法读取：" + malformedState.getMessage());
            return new WorkshopSync(markSubscriptionsUnavailable(current, null), false, warnings, 0, 0);
        }
        List<String> warnings = new java.util.ArrayList<>(scan.warnings);
        if (!scan.authoritative) {
            warnings.add("Steam 工坊安装状态不可用；订阅包保持不变");
            return new WorkshopSync(markSubscriptionsUnavailable(current, null), false, warnings, 0, 0);
        }
        if (!scan.syncReady) {
            warnings.add("Steam 工坊仍在下载或更新；本次仅保留现有音乐包");
            return new WorkshopSync(markSubscriptionsUnavailable(current, null), false, warnings, 0, 0);
        }
        if (portable != null) portable.forgetMissingSubscriptions(scan.installedIds);
        Config next = current;
        int installedOrUpdated = 0, removed = 0;
        for (Map.Entry<String, WorkshopPackageCatalog.Bundle> entry : scan.validPacks.entrySet()) {
            WorkshopPackageCatalog.Bundle bundle = entry.getValue();
            MusicPack existing = null;
            for (MusicPack pack : next.packs) if (pack.source == MusicPack.Source.SUBSCRIBED && entry.getKey().equals(pack.workshopId)) { existing = pack; break; }
            try {
                String previous = existing == null ? null : workshopProjection(next, existing);
                Config updated = MusicPackages.applySubscription(next, entry.getKey(), bundle.packageId, bundle.name, bundle.version, bundle.rules);
                MusicPack target = null;
                for (MusicPack pack : updated.packs) if (pack.source == MusicPack.Source.SUBSCRIBED && entry.getKey().equals(pack.workshopId)) { target = pack; break; }
                if (target == null) throw new IOException("订阅音乐包索引缺失");
                for (Rule rule : updated.rules) if (target.id.equals(rule.packId)) for (Candidate candidate : rule.candidates) {
                    if (candidate.sourcePath != null) candidate.sourcePath = workshopPath(entry.getKey(), WorkshopPackageCatalog.safeRelative(candidate.sourcePath));
                    if (candidate.cachePath != null) {
                        String relative = WorkshopPackageCatalog.safeRelative(candidate.cachePath);
                        candidate.cachePath = workshopPath(entry.getKey(), relative);
                        if (bundle.missingAssets.contains(relative)) candidate.error = "音乐文件不存在";
                        else if (isWorkshopAvailabilityError(candidate.error)) candidate.error = null;
                    }
                }
                if (portable != null) portable.applySwitches(updated, target);
                next = updated;
                workshopManifests.put(entry.getKey(), Files.readAllBytes(workshopItem(entry.getKey()).resolve(WorkshopPackageCatalog.MANIFEST)));
                String projection = workshopProjection(next, target);
                workshopProjections.put(entry.getKey(), projection);
                if (!projection.equals(previous)) installedOrUpdated++;
            } catch (IOException | RuntimeException ex) {
                warnings.add("工坊音乐包 " + entry.getKey() + " 未更新：" + ex.getMessage());
            }
        }
        List<String> removedIds = new java.util.ArrayList<>();
        for (MusicPack pack : next.packs) if (pack.source == MusicPack.Source.SUBSCRIBED
                && !scan.installedIds.contains(pack.workshopId)) removedIds.add(pack.workshopId);
        for (String id : removedIds) {
            try { next = unsubscribe(next, id, activeCaches); removed++; }
            catch (IOException ex) { warnings.add("已取消订阅的音乐包 " + id + " 未能移除设置：" + ex.getMessage()); }
        }
        Set<String> unavailable = new HashSet<>(scan.installedIds);
        unavailable.removeAll(workshopManifests.keySet());
        next = markSubscriptionsUnavailable(next, unavailable);
        saveSettings(next);
        return new WorkshopSync(next, true, warnings, installedOrUpdated, removed);
    }

    public Config markSubscriptionsUnavailable(Config current, Set<String> ids) {
        Config next = current.copy();
        for (MusicPack pack : next.packs) if (pack.source == MusicPack.Source.SUBSCRIBED
                && (ids == null || ids.contains(pack.workshopId))) {
            for (Rule rule : next.rules) if (pack.id.equals(rule.packId)) for (Candidate candidate : rule.candidates)
                candidate.error = "工坊音乐包暂时不可用";
            workshopManifests.remove(pack.workshopId);
            workshopProjections.put(pack.workshopId, workshopProjection(next, pack));
        }
        return next;
    }

    private String copyPackageAsset(String oldPath, Path stagingDirectory, Path finalDirectory,
            String packId, String revision, String kind, Map<String, String> copiedPaths,
            Map<Path, String> stagedFiles, MusicPack owner) throws IOException {
        if (oldPath == null) return null;
        String prior = copiedPaths.get(oldPath);
        if (prior != null) return prior;
        Path source = managed(oldPath);
        if (!Files.isRegularFile(source) || Files.isSymbolicLink(source))
            throw new IOException("音乐包引用的文件缺失或不是普通文件：" + oldPath);
        String extension = extension(source.getFileName().toString());
        String fileName = UUID.randomUUID().toString() + extension;
        Path target = stagingDirectory.resolve(fileName);
        Files.copy(source, target);
        String hash = sha256(target);
        stagedFiles.put(target, hash);
        String relative = root.relativize(finalDirectory.resolve(fileName)).toString().replace('\\', '/');
        owner.ownedFiles.add(new MusicPack.OwnedFile(relative, hash, Files.size(target)));
        copiedPaths.put(oldPath, relative);
        return relative;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && name.length() - dot <= 12 ? name.substring(dot) : ".audio";
    }

    private void writeJson(Path target, Object value) throws IOException {
        Files.createDirectories(target.getParent());
        try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) { gson.toJson(value, writer); }
    }

    private static void deleteEmptyTree(Path directory) throws IOException {
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) return;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) if (Files.isDirectory(child) && !Files.isSymbolicLink(child)) deleteEmptyTree(child);
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            if (!children.iterator().hasNext()) Files.deleteIfExists(directory);
        }
    }

    public Rule importAudio(Path original) throws IOException {
        return importAudio(original, null);
    }

    public Rule importAudio(Path original, MusicPack pack) throws IOException {
        Rule rule = new Rule();
        rule.name = original.getFileName().toString();
        Candidate candidate = new Candidate();
        replace(candidate, original, pack);
        rule.candidates.add(candidate);
        return rule;
    }

    public boolean replace(Rule rule, Path original) throws IOException {
        Candidate candidate = rule.audio();
        boolean reset = replace(candidate, original);
        return reset;
    }

    public boolean replace(Candidate candidate, Path original) throws IOException {
        return replace(candidate, original, null);
    }

    public boolean replace(Candidate candidate, Path original, MusicPack pack) throws IOException {
        candidate.name = original.getFileName().toString();
        String revision = UUID.randomUUID().toString();
        boolean subscribed = pack != null && pack.source == MusicPack.Source.SUBSCRIBED;
        boolean localPack = portable != null && pack != null && pack.source != MusicPack.Source.SUBSCRIBED;
        String localPrefix = localPack ? "music-packs/" + (pack.localFolder == null || pack.localFolder.isEmpty() ? pack.id : pack.localFolder) + "/" : "";
        String sourceRelative = subscribed ? workshopPath(pack.workshopId, "audio/user/source/" + revision + ".source") : localPack ? localPrefix + "audio/user/source/" + revision + ".source" : "library/" + revision + ".source";
        String cacheRelative = subscribed ? workshopPath(pack.workshopId, "audio/user/cache/" + revision + ".wav") : localPack ? localPrefix + "audio/user/cache/" + revision + ".wav" : "cache/" + revision + ".wav";
        if (subscribed) workshopItem(pack.workshopId);
        Path source = managed(sourceRelative), cache = managed(cacheRelative);
        Files.createDirectories(source.getParent()); Files.createDirectories(cache.getParent());
        Files.copy(original, source);
        candidate.sourcePath = sourceRelative;
        candidate.cachePath = null;
        candidate.error = null;
        boolean reset = false;
        try {
            decode(source, cache);
            double duration = duration(cache);
            if (duration <= 0) throw new IOException(DECODE_ERROR);
            reset = candidate.end <= candidate.start || candidate.end > duration || candidate.start >= duration;
            candidate.duration = duration;
            candidate.cachePath = cacheRelative;
            if (reset) { candidate.start = 0; candidate.end = duration; }
        } catch (Exception ex) {
            Files.deleteIfExists(cache);
            candidate.duration = 0;
            candidate.error = DECODE_ERROR + "（" + ex.getMessage() + "）";
        }
        if (!subscribed && !localPack) {
            registerOwned(source);
            if (Files.isRegularFile(cache)) registerOwned(cache);
        }
        return reset;
    }


    private void decode(Path source, Path target) throws IOException, InterruptedException {
        if (ffmpeg == null || !Files.isRegularFile(ffmpeg)) throw new IOException("未找到本地 FFmpeg 解码程序");
        Path log = Files.createTempFile(root, "decode-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(ffmpeg.toString(), "-nostdin", "-v", "error", "-y",
                    "-protocol_whitelist", "file,pipe", "-i", source.toString(),
                    "-vn", "-map", "0:a:0", "-ac", "2", "-ar", "44100", "-c:a", "pcm_s16le", target.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("音频解码超时");
            }
            if (process.exitValue() != 0) throw new IOException(DECODE_ERROR);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }

    public static double duration(Path wav) throws Exception {
        try (java.io.InputStream input = new java.io.BufferedInputStream(Files.newInputStream(wav));
                AudioInputStream stream = AudioSystem.getAudioInputStream(input)) {
            return stream.getFrameLength() / (double) stream.getFormat().getFrameRate();
        }
    }

    public Path managed(String relative) {
        Path relativePath = Paths.get(relative);
        if (relativePath.getNameCount() >= 3 && "workshop".equals(relativePath.getName(0).toString())) {
            if (workshopContentRoot == null || relativePath.isAbsolute() || !relativePath.normalize().equals(relativePath))
                throw new IllegalArgumentException("工坊音乐目录不可用");
            String id = relativePath.getName(1).toString();
            if (!id.matches("[0-9]{1,20}")) throw new IllegalArgumentException("工坊音乐路径无效");
            Path result = workshopContentRoot.resolve(relativePath.subpath(1, relativePath.getNameCount())).normalize();
            if (!result.startsWith(workshopContentRoot.resolve(id))) throw new IllegalArgumentException("工坊音乐路径无效");
            Path cursor = workshopContentRoot;
            if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException("工坊目录包含符号链接");
            for (int i = 1; i < relativePath.getNameCount(); i++) {
                cursor = cursor.resolve(relativePath.getName(i));
                if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException("工坊音乐路径包含符号链接");
            }
            return result;
        }
        Path result = root.resolve(relativePath).normalize();
        if (relativePath.isAbsolute() || relativePath.getNameCount() < 2 || !result.startsWith(root)
                || !relativePath.normalize().equals(relativePath))
            throw new IllegalArgumentException("音频库路径无效");
        String namespace = relativePath.getName(0).toString();
        if (!("library".equals(namespace) || "cache".equals(namespace) || "packs".equals(namespace) || "music-packs".equals(namespace)))
            throw new IllegalArgumentException("音频库路径无效");
        Path cursor = root;
        for (Path component : relativePath) {
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException("音频文件路径包含符号链接");
        }
        return result;
    }

    public void collect(Config config, Set<String> activeCaches) throws IOException {
        Set<Path> keep = new HashSet<>();
        if (config != null) {
            registerReferencedFiles(config);
            for (Rule rule : config.rules) for (Candidate candidate : rule.candidates) {
                if (candidate.sourcePath != null && !outsideManagedCleanup(candidate.sourcePath)) keep.add(managed(candidate.sourcePath));
                if (candidate.cachePath != null && !outsideManagedCleanup(candidate.cachePath)) keep.add(managed(candidate.cachePath));
            }
        }
        if (activeCaches != null) for (String cache : activeCaches) if (!outsideManagedCleanup(cache)) keep.add(managed(cache));
        keepRecoveryReferencedFiles(keep);
        Map<String, String> owned = readOwnership();
        boolean changed = false;
        for (Map.Entry<String, String> entry : new HashSet<>(owned.entrySet())) {
            Path path;
            if (!isLegacyManagedFile(entry.getKey())) continue;
            try { path = managed(entry.getKey()); }
            catch (RuntimeException unsafe) { owned.remove(entry.getKey()); changed = true; continue; }
            if (Files.isSymbolicLink(path)) { owned.put(entry.getKey(), "protected"); changed = true; continue; }
            if (!Files.exists(path)) { owned.remove(entry.getKey()); changed = true; continue; }
            String currentHash = sha256(path);
            if ("protected".equals(entry.getValue()) || !entry.getValue().equals(currentHash)) {
                // Modified managed files are treated as user-owned and never deleted automatically.
                if (!"protected".equals(entry.getValue())) { owned.put(entry.getKey(), "protected"); changed = true; }
                continue;
            }
            if (!keep.contains(path)) {
                Files.delete(path);
                owned.remove(entry.getKey());
                changed = true;
            }
        }
        if (changed) writeOwnership(owned);
        collectPackageAssets(config, keep);
    }

    private static boolean outsideManagedCleanup(String path) {
        String normalized = path.replace('\\', '/');
        // The collector only removes legacy library/cache and owned packs, never portable or Steam media.
        return normalized.startsWith("music-packs/") || normalized.startsWith("workshop/");
    }

    private void collectPackageAssets(Config config, Set<Path> keep) throws IOException {
        Path packsRoot = root.resolve("packs");
        if (!Files.isDirectory(packsRoot) || Files.isSymbolicLink(packsRoot)) return;
        Set<String> active = new HashSet<>();
        if (config != null) for (MusicPack pack : config.packs) active.add(pack.id);
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(packsRoot)) {
            for (Path packageRoot : directories) {
                if (!Files.isDirectory(packageRoot) || Files.isSymbolicLink(packageRoot)) continue;
                String directoryId = packageRoot.getFileName().toString();
                if (directoryId.startsWith(".staging-")) continue;
                Path marker = packageRoot.resolve("package-owner.json");
                if (!Files.isRegularFile(marker) || Files.isSymbolicLink(marker)) continue;
                PackageOwner owner;
                try (Reader reader = Files.newBufferedReader(marker, StandardCharsets.UTF_8)) {
                    owner = gson.fromJson(reader, PackageOwner.class);
                } catch (RuntimeException malformed) { continue; }
                if (owner == null || !directoryId.equals(owner.id) || owner.source == null || owner.files == null) continue;
                // Legacy subscription mirrors remain untouched as recovery data after switching to direct access.
                if (owner.source == MusicPack.Source.SUBSCRIBED) continue;
                if (owner.source == MusicPack.Source.SUBSCRIBED
                        && (owner.workshopId == null || !owner.workshopId.matches("[0-9]{1,20}"))) continue;
                if (owner.protectedFiles == null) owner.protectedFiles = new java.util.ArrayList<>();
                Set<String> protectedSet = new HashSet<>(owner.protectedFiles);
                Map<String, MusicPack.OwnedFile> remaining = new LinkedHashMap<>();
                for (MusicPack.OwnedFile file : owner.files) {
                    if (file == null || file.path == null || !file.path.matches("packs/" + java.util.regex.Pattern.quote(owner.id)
                            + "/versions/[0-9a-fA-F-]{36}/(source|cache)/[0-9a-fA-F-]{36}(\\.[A-Za-z0-9]{1,10})?")) continue;
                    Path path;
                    try { path = managed(file.path); } catch (RuntimeException unsafe) { continue; }
                    if (!path.startsWith(packageRoot.resolve("versions"))) continue;
                    if (protectedSet.contains(file.path)) { remaining.put(file.path, file); continue; }
                    if (!Files.exists(path)) continue;
                    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
                        protectedSet.add(file.path); remaining.put(file.path, file); continue;
                    }
                    String hash = sha256(path);
                    if (!file.sha256.equals(hash) || file.bytes != Files.size(path)) {
                        protectedSet.add(file.path); remaining.put(file.path, file); continue;
                    }
                    if (keep.contains(path)) { remaining.put(file.path, file); continue; }
                    Files.delete(path);
                }
                owner.files = new java.util.ArrayList<>(remaining.values());
                owner.protectedFiles = new java.util.ArrayList<>(protectedSet);
                if (!active.contains(owner.id) && owner.files.isEmpty() && owner.protectedFiles.isEmpty()) {
                    Files.delete(marker);
                    removeEmptyManagedPackageStructure(packageRoot);
                } else writeJson(marker, owner);
            }
        }
    }

    /** Removes only the known, empty directories created by this package layout; unknown user directories remain. */
    private static void removeEmptyManagedPackageStructure(Path packageRoot) throws IOException {
        Path versions = packageRoot.resolve("versions");
        if (Files.isDirectory(versions) && !Files.isSymbolicLink(versions)) {
            try (DirectoryStream<Path> revisions = Files.newDirectoryStream(versions)) {
                for (Path revision : revisions) {
                    if (!revision.getFileName().toString().matches("[0-9a-fA-F-]{36}")
                            || !Files.isDirectory(revision) || Files.isSymbolicLink(revision)) continue;
                    for (String kind : new String[] {"source", "cache"}) {
                        Path directory = revision.resolve(kind);
                        if (Files.isDirectory(directory) && !Files.isSymbolicLink(directory) && isEmpty(directory)) Files.delete(directory);
                    }
                    if (isEmpty(revision)) Files.delete(revision);
                }
            }
            if (isEmpty(versions)) Files.delete(versions);
        }
        if (Files.isDirectory(packageRoot) && !Files.isSymbolicLink(packageRoot) && isEmpty(packageRoot)) Files.delete(packageRoot);
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) { return !entries.iterator().hasNext(); }
    }

    private void keepRecoveryReferencedFiles(Set<Path> keep) {
        try (DirectoryStream<Path> backups = Files.newDirectoryStream(root, "settings.pre-pack-migration*.json")) {
            for (Path backup : backups) {
                if (backup.getFileName().toString().equals("settings.json")) continue;
                try (Reader reader = Files.newBufferedReader(backup, StandardCharsets.UTF_8)) {
                    com.google.gson.JsonObject json = new com.google.gson.JsonParser().parse(reader).getAsJsonObject();
                    Config saved = Config.fromJson(json);
                    for (Rule rule : saved.rules) {
                        MusicPack pack = saved.pack(rule.packId);
                        if (pack != null && pack.source == MusicPack.Source.SUBSCRIBED) continue;
                        for (Candidate candidate : rule.candidates) for (String relative : new String[] {candidate.sourcePath, candidate.cachePath}) {
                            if (relative == null || outsideManagedCleanup(relative)) continue;
                            try { keep.add(managed(relative)); } catch (RuntimeException ignored) { }
                        }
                    }
                } catch (Exception ignored) { }
            }
        } catch (IOException ignored) { }
    }

    private void registerReferencedFiles(Config config) throws IOException {
        Map<String, String> owned = readOwnership();
        boolean changed = false;
        for (Rule rule : config.rules) for (Candidate candidate : rule.candidates) {
            for (String relative : new String[] {candidate.sourcePath, candidate.cachePath}) {
                if (relative == null || !isLegacyManagedFile(relative.replace('\\', '/'))) continue;
                Path path = managed(relative);
                if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) continue;
                if (!path.startsWith(root)) continue;
                String key = root.relativize(path).toString().replace('\\', '/');
                if (!isLegacyManagedFile(key)) continue;
                String recorded = owned.get(key);
                if ("protected".equals(recorded)) continue;
                String hash = sha256(path);
                if (recorded != null && !recorded.equals(hash)) { owned.put(key, "protected"); changed = true; }
                else if (recorded == null) { owned.put(key, hash); changed = true; }
            }
        }
        if (changed) writeOwnership(owned);
    }

    private void registerOwned(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) return;
        Map<String, String> owned = readOwnership();
        String key = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        if (!isLegacyManagedFile(key)) return;
        if (!"protected".equals(owned.get(key))) { owned.put(key, sha256(path)); writeOwnership(owned); }
    }

    private void refreshPackageOwnership(Config config) throws IOException {
        for (MusicPack pack : config.packs) {
            if (pack.source == MusicPack.Source.SUBSCRIBED) continue;
            Path packRoot = root.resolve("packs").resolve(pack.id).normalize();
            if (!packRoot.startsWith(root.resolve("packs")) || Files.isSymbolicLink(packRoot)
                    || !Files.isRegularFile(packRoot.resolve("package-owner.json")) || Files.isSymbolicLink(packRoot.resolve("package-owner.json"))) continue;
            PackageOwner owner;
            try (Reader reader = Files.newBufferedReader(packRoot.resolve("package-owner.json"), StandardCharsets.UTF_8)) {
                owner = gson.fromJson(reader, PackageOwner.class);
            } catch (RuntimeException malformed) { continue; }
            if (owner == null || !pack.id.equals(owner.id) || owner.source != pack.source
                    || pack.source == MusicPack.Source.SUBSCRIBED && !pack.workshopId.equals(owner.workshopId)) continue;
            Map<String, MusicPack.OwnedFile> recorded = new LinkedHashMap<>();
            if (owner.files != null) for (MusicPack.OwnedFile file : owner.files) if (file != null && file.path != null) recorded.put(file.path, file);
            Map<String, MusicPack.OwnedFile> referenced = new LinkedHashMap<>();
            for (Rule rule : config.rules) if (pack.id.equals(rule.packId)) for (Candidate candidate : rule.candidates) {
                for (String relative : new String[] {candidate.sourcePath, candidate.cachePath}) {
                    if (relative == null) continue;
                    Path path;
                    try { path = managed(relative); } catch (RuntimeException unsafe) { continue; }
                    if (!path.startsWith(packRoot) || !Files.isRegularFile(path) || Files.isSymbolicLink(path)) continue;
                    String key = root.relativize(path).toString().replace('\\', '/');
                    long size = Files.size(path);
                    MusicPack.OwnedFile prior = recorded.get(key);
                    if (prior != null && prior.bytes == size) {
                        // Reuse the recorded digest on routine settings saves. Cleanup still re-hashes
                        // immediately before deletion, so same-size user edits are protected.
                        referenced.put(key, prior);
                        continue;
                    }
                    String hash = sha256(path);
                    if (prior != null && !prior.sha256.equals(hash)) continue;
                    referenced.put(key, new MusicPack.OwnedFile(key, hash, size));
                }
            }
            pack.ownedFiles = new java.util.ArrayList<>(referenced.values());
            Map<String, MusicPack.OwnedFile> allOwned = new LinkedHashMap<>();
            if (owner.files != null) for (MusicPack.OwnedFile file : owner.files) {
                if (file == null || file.path == null) continue;
                Path path;
                try { path = managed(file.path); } catch (RuntimeException unsafe) { continue; }
                if (!path.startsWith(packRoot.resolve("versions"))) continue;
                if (Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
                    // Avoid reading potentially large audio files on each UI save. collectPackageAssets
                    // performs the full SHA-256 ownership check before it can reclaim any file.
                    if (file.bytes != Files.size(path)) {
                        if (owner.protectedFiles == null) owner.protectedFiles = new java.util.ArrayList<>();
                        if (!owner.protectedFiles.contains(file.path)) owner.protectedFiles.add(file.path);
                    }
                    allOwned.put(file.path, file);
                }
            }
            allOwned.putAll(referenced);
            owner.files = new java.util.ArrayList<>(allOwned.values());
            writeJson(packRoot.resolve("package-owner.json"), owner);
        }
        config.refreshPackMembership();
    }

    private static boolean isLegacyManagedFile(String relative) {
        return relative != null && (relative.matches("library/[0-9a-fA-F-]{36}\\.source")
                || relative.matches("cache/[0-9a-fA-F-]{36}\\.wav"));
    }

    private Map<String, String> readOwnership() throws IOException {
        if (!Files.isRegularFile(ownershipFile)) return new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(ownershipFile, StandardCharsets.UTF_8)) {
            Map<String, String> parsed = new Gson().fromJson(reader, new TypeToken<Map<String, String>>() { }.getType());
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (RuntimeException ex) { throw new IOException("音频文件归属索引损坏，已停止自动清理", ex); }
    }

    private void writeOwnership(Map<String, String> owned) throws IOException {
        Path temporary = Files.createTempFile(root, "managed-files-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { gson.toJson(owned, writer); }
            try { Files.move(temporary, ownershipFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, ownershipFile, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
