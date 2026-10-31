package battleaudio.storage;

import battleaudio.model.Config;
import battleaudio.model.Config.Candidate;
import battleaudio.model.Config.Rule;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EmbeddedPresets {
    private static final String RESOURCE_ROOT = "/battleaudio/presets/";

    interface Resources {
        InputStream open(String name) throws IOException;
    }

    public static boolean install(AudioLibrary library) throws IOException {
        return install(library, EmbeddedPresets.class::getResourceAsStream);
    }

    static boolean install(AudioLibrary library, Resources resources) throws IOException {
        Path settings = library.root().resolve("settings.json");
        if (Files.exists(settings)) return false;
        try (InputStream input = resources.open(RESOURCE_ROOT + "settings.json")) {
            if (input == null) return false;
            Path staging = Files.createTempDirectory(library.root(), "presets-");
            List<Path> temporaryFiles = new ArrayList<>();
            try {
                Path stagedSettings = staging.resolve("settings.json");
                temporaryFiles.add(stagedSettings);
                Files.copy(input, stagedSettings);
                Config config;
                try (Reader reader = Files.newBufferedReader(stagedSettings, StandardCharsets.UTF_8)) {
                    config = Config.fromJson(new JsonParser().parse(reader).getAsJsonObject());
                } catch (RuntimeException error) {
                    throw new IOException("内置音频预设配置无效", error);
                }
                Map<Path, Path> assets = new LinkedHashMap<>();
                for (Rule rule : config.rules) for (Candidate candidate : rule.candidates) {
                    stage(library, resources, candidate.sourcePath, staging, temporaryFiles, assets);
                    stage(library, resources, candidate.cachePath, staging, temporaryFiles, assets);
                }
                for (Map.Entry<Path, Path> asset : assets.entrySet()) {
                    if (Files.exists(asset.getKey())) {
                        if (!Files.isRegularFile(asset.getKey()) || !Arrays.equals(digest(asset.getKey()), digest(asset.getValue())))
                            throw new IOException("内置音频与现有文件冲突，原文件已保留：" + asset.getKey().getFileName());
                    }
                }
                if (Files.exists(settings)) return false;
                for (Map.Entry<Path, Path> asset : assets.entrySet()) {
                    if (!Files.exists(asset.getKey())) Files.move(asset.getValue(), asset.getKey());
                }
                Files.move(stagedSettings, settings);
                return true;
            } finally {
                for (Path temporary : temporaryFiles) Files.deleteIfExists(temporary);
                Files.deleteIfExists(staging);
            }
        }
    }

    private static void stage(AudioLibrary library, Resources resources, String relative, Path staging,
            List<Path> temporaryFiles, Map<Path, Path> assets) throws IOException {
        if (relative == null) throw new IOException("内置音频路径缺失");
        Path target;
        try {
            target = library.managed(relative);
        } catch (RuntimeException error) {
            throw new IOException("内置音频路径无效", error);
        }
        if (assets.containsKey(target)) return;
        try (InputStream input = resources.open(RESOURCE_ROOT + relative.replace('\\', '/'))) {
            if (input == null) throw new IOException("内置音频文件缺失：" + relative);
            Path temporary = Files.createTempFile(staging, "audio-", ".tmp");
            temporaryFiles.add(temporary);
            Files.copy(input, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(temporary) == 0) throw new IOException("内置音频文件为空：" + relative);
            assets.put(target, temporary);
        }
    }

    private static byte[] digest(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("无法校验内置音频", error);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        return digest.digest();
    }

    private EmbeddedPresets() { }
}
