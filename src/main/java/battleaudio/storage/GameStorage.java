package battleaudio.storage;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

/** Copies existing user data once; never removes or overwrites the original directory. */
public final class GameStorage {
    private GameStorage() { }

    public static Path prepare(Path gameDirectory, Path legacy) throws IOException {
        Path game = gameDirectory.toAbsolutePath().normalize();
        Path target = game.resolve("BetterCustomAudioTriggers");
        Path old = legacy.toAbsolutePath().normalize();
        if (Files.exists(target)) {
            if (Files.isSymbolicLink(target) || !Files.isDirectory(target)) throw new IOException("游戏音乐数据目录不可用");
            if (!Files.exists(target.resolve("settings.json")) && Files.isRegularFile(old.resolve("settings.json")))
                throw new IOException("游戏目录已有音乐文件，但旧设置尚未迁移；两个目录均已保留，请先确认迁移方式");
            return target;
        }
        if (!Files.isRegularFile(old.resolve("settings.json"))) return target;
        if (Files.isSymbolicLink(old) || !Files.isDirectory(old)) throw new IOException("旧音乐数据目录不可用；未进行迁移");
        Path staging = game.resolve(".better-audio-migration-" + UUID.randomUUID());
        Files.createDirectory(staging);
        Files.walkFileTree(old, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(dir)) throw new IOException("旧音乐数据包含符号链接；原数据已保留");
                Path destination = staging.resolve(old.relativize(dir)).normalize();
                if (!destination.startsWith(staging)) throw new IOException("旧音乐数据路径无效");
                Files.createDirectories(destination); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path source, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile() || Files.isSymbolicLink(source)) throw new IOException("旧音乐数据包含特殊文件；原数据已保留");
                Path destination = staging.resolve(old.relativize(source)).normalize();
                if (!destination.startsWith(staging)) throw new IOException("旧音乐数据路径无效");
                Files.copy(source, destination);
                if (Files.size(source) != Files.size(destination) || !Arrays.equals(hash(source), hash(destination)))
                    throw new IOException("旧音乐数据复制校验失败；原数据已保留");
                return FileVisitResult.CONTINUE;
            }
        });
        try { Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(staging, target); }
        return target;
    }

    private static byte[] hash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
