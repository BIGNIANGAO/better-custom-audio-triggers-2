package battleaudio.storage;

import battleaudio.model.Config;
import battleaudio.model.Config.Candidate;
import battleaudio.model.Config.Rule;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads music-pack manifests and resolves media inside installed Workshop items. */
public final class WorkshopPackageCatalog {
    public static final String MANIFEST = "music-pack.json";
    private static final int MAX_STATE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_FILES = 4096;
    private static final int MAX_RULES = 512;
    private static final Gson GSON = new Gson();
    private static final Pattern VDF_TOKEN = Pattern.compile("\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"|([{}])|//[^\\r\\n]*");

    private WorkshopPackageCatalog() { }

    public static final class Bundle {
        public int schemaVersion;
        public String packageId;
        public String workshopId;
        public String name;
        public String version;
        public boolean enabled = true;
        public List<Rule> rules;
        public List<MediaFile> files;
        public transient Map<String, Path> assets = new LinkedHashMap<>();
        public transient Set<String> missingAssets = new HashSet<>();
    }

    public static final class MediaFile {
        public String path;
        public String sha256;
        public long bytes;
    }

    public static final class ScanResult {
        public final boolean authoritative;
        public final boolean syncReady;
        public final Set<String> installedIds;
        public final Map<String, Bundle> validPacks;
        public final List<String> warnings;

        private ScanResult(boolean authoritative, boolean syncReady, Set<String> installedIds,
                Map<String, Bundle> validPacks, List<String> warnings) {
            this.authoritative = authoritative;
            this.syncReady = syncReady;
            this.installedIds = installedIds;
            this.validPacks = validPacks;
            this.warnings = warnings;
        }
    }

    public static ScanResult scan(Path contentRoot) throws IOException {
        Path content = contentRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(content) || Files.isSymbolicLink(content))
            return new ScanResult(false, false, new HashSet<>(), new LinkedHashMap<>(), new ArrayList<>());
        Path stateFile = content.getParent().getParent().getParent().resolve("workshop").resolve("appworkshop_646570.acf");
        if (!Files.isRegularFile(stateFile) || Files.isSymbolicLink(stateFile) || Files.size(stateFile) > MAX_STATE_BYTES)
            return new ScanResult(false, false, new HashSet<>(), new LinkedHashMap<>(), new ArrayList<>());
        Map<String, Object> state = parseVdf(new String(Files.readAllBytes(stateFile), StandardCharsets.UTF_8));
        Object appNode = state.get("AppWorkshop");
        if (!(appNode instanceof Map)) return new ScanResult(false, false, new HashSet<>(), new LinkedHashMap<>(), new ArrayList<>());
        @SuppressWarnings("unchecked") Map<String, Object> app = (Map<String, Object>) appNode;
        if (!"646570".equals(app.get("appid"))) return new ScanResult(false, false, new HashSet<>(), new LinkedHashMap<>(), new ArrayList<>());
        Object installedNode = app.get("WorkshopItemsInstalled");
        if (!(installedNode instanceof Map)) return new ScanResult(false, false, new HashSet<>(), new LinkedHashMap<>(), new ArrayList<>());
        @SuppressWarnings("unchecked") Map<String, Object> installed = (Map<String, Object>) installedNode;
        Set<String> ids = new HashSet<>();
        for (String id : installed.keySet()) if (id.matches("[0-9]{1,20}")) ids.add(id);
        boolean syncReady = "0".equals(app.get("NeedsDownload")) && "0".equals(app.get("NeedsUpdate"));
        Map<String, Bundle> packs = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (String id : ids) {
            Path item = content.resolve(id).normalize();
            if (!item.startsWith(content) || !Files.isDirectory(item) || Files.isSymbolicLink(item)) continue;
            Path manifest = item.resolve(MANIFEST);
            if (!Files.isRegularFile(manifest)) continue;
            try {
                Bundle bundle = readBundle(item, id);
                packs.put(id, bundle);
                if (!bundle.missingAssets.isEmpty()) warnings.add("工坊音乐包 " + id + " 缺少 " + bundle.missingAssets.size() + " 个音频文件");
            }
            catch (Exception ex) { warnings.add("工坊音乐包 " + id + " 校验失败：" + ex.getMessage()); }
        }
        return new ScanResult(true, syncReady, ids, packs, warnings);
    }

    public static Bundle readBundle(Path itemDirectory, String workshopId) throws IOException {
        Path item = itemDirectory.toAbsolutePath().normalize();
        if (workshopId != null && !workshopId.matches("[0-9]{1,20}") || !Files.isDirectory(item) || Files.isSymbolicLink(item))
            throw new IOException("工坊目录或项目 ID 无效");
        Path manifest = item.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest) || Files.isSymbolicLink(manifest) || Files.size(manifest) > MAX_MANIFEST_BYTES)
            throw new IOException("清单不存在、损坏或超过大小限制");
        Bundle bundle;
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            bundle = GSON.fromJson(new JsonParser().parse(reader), Bundle.class);
        } catch (RuntimeException ex) { throw new IOException("清单 JSON 无法解析", ex); }
        if (bundle != null) {
            if (bundle.assets == null) bundle.assets = new LinkedHashMap<>();
            if (bundle.missingAssets == null) bundle.missingAssets = new HashSet<>();
        }
        if (bundle == null || bundle.schemaVersion != 1 || bundle.packageId == null
                || bundle.workshopId != null && !bundle.workshopId.isEmpty() && !bundle.workshopId.equals(workshopId)
                || !bundle.packageId.matches("[A-Za-z0-9._-]{1,128}") || bundle.packageId.contains("..")
                || bundle.name == null || bundle.name.trim().isEmpty() || bundle.version == null || bundle.version.trim().isEmpty()
                || bundle.rules == null || bundle.rules.size() > MAX_RULES
                || bundle.files != null && bundle.files.size() > MAX_FILES)
            throw new IOException("音乐包清单字段不完整或版本不受支持");
        Config validation = new Config(); validation.rules.addAll(bundle.rules);
        try { validation.validate(); }
        catch (RuntimeException invalid) { throw new IOException("音乐包规则无效：" + invalid.getMessage(), invalid); }
        Set<String> ruleIds = new HashSet<>();
        for (Rule rule : bundle.rules) {
            if (rule == null || !ruleIds.add(rule.id)) throw new IOException("规则 ID 缺失或重复");
            for (Candidate candidate : rule.candidates) {
                for (String path : new String[] {candidate.sourcePath, candidate.cachePath}) {
                    if (path == null) continue;
                    String key = safeRelative(path);
                    Path resolved = item.resolve(key).normalize();
                    if (!resolved.startsWith(item) || containsSymlink(item, item.relativize(resolved)))
                        throw new IOException("音乐包路径不安全：" + key);
                    if (Files.isRegularFile(resolved)) bundle.assets.put(key, resolved);
                    else bundle.missingAssets.add(key);
                }
            }
        }
        if (bundle.files == null) bundle.files = new ArrayList<>();
        return bundle;
    }

    static String safeRelative(String value) throws IOException {
        if (value == null || value.trim().isEmpty() || value.startsWith("/") || value.matches("^[A-Za-z]:.*")
                || value.indexOf('\0') >= 0) throw new IOException("媒体路径不是相对路径");
        String normalizedSeparators = value.replace('\\', '/');
        Path relative = Paths.get(normalizedSeparators);
        if (relative.isAbsolute() || !relative.normalize().equals(relative) || normalizedSeparators.contains("../")
                || normalizedSeparators.equals("..") || normalizedSeparators.contains("/.."))
            throw new IOException("媒体路径包含目录跳转");
        return relative.toString().replace('\\', '/');
    }

    private static boolean containsSymlink(Path root, Path relative) {
        Path cursor = root;
        for (Path part : relative) { cursor = cursor.resolve(part); if (Files.isSymbolicLink(cursor)) return true; }
        return false;
    }

    private static Map<String, Object> parseVdf(String text) throws IOException {
        Matcher matcher = VDF_TOKEN.matcher(text);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            if (matcher.group(1) != null) tokens.add(unescape(matcher.group(1)));
            else if (matcher.group(2) != null) tokens.add(matcher.group(2));
        }
        int[] index = {0};
        Map<String, Object> result = parseObject(tokens, index, false);
        if (index[0] != tokens.size()) throw new IOException("Steam 工坊状态文件格式无效");
        return result;
    }

    private static Map<String, Object> parseObject(List<String> tokens, int[] index, boolean nested) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        while (index[0] < tokens.size()) {
            String key = tokens.get(index[0]++);
            if ("}".equals(key)) { if (!nested) throw new IOException("Steam 工坊状态文件括号不匹配"); return result; }
            if ("{".equals(key)) throw new IOException("Steam 工坊状态文件键缺失");
            if (index[0] >= tokens.size()) throw new IOException("Steam 工坊状态文件值缺失");
            String value = tokens.get(index[0]++);
            if ("{".equals(value)) result.put(key, parseObject(tokens, index, true));
            else if ("}".equals(value)) throw new IOException("Steam 工坊状态文件值无效");
            else result.put(key, value);
        }
        if (nested) throw new IOException("Steam 工坊状态文件括号未闭合");
        return result;
    }

    private static String unescape(String value) { return value.replace("\\\\", "\\").replace("\\\"", "\""); }
}
