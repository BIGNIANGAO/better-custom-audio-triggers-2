package battleaudio.storage;

import battleaudio.model.Config.Candidate;
import battleaudio.model.Config.Rule;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Validates exported snapshots and prepares an isolated upload directory. */
public final class WorkshopExport {
    private WorkshopExport() { }

    public static final class Snapshot {
        public final Path directory;
        public final String packageId, name, version, manifestHash;
        public final List<String> contents, ruleNames;
        public final long bytes;
        private final Map<String, WorkshopPackageCatalog.MediaFile> files;

        private Snapshot(Path directory, WorkshopPackageCatalog.Bundle bundle, String manifestHash,
                List<String> contents, List<String> ruleNames, long bytes, Map<String, WorkshopPackageCatalog.MediaFile> files) {
            this.directory = directory; this.packageId = bundle.packageId; this.name = bundle.name;
            this.version = bundle.version; this.manifestHash = manifestHash;
            this.contents = Collections.unmodifiableList(contents); this.ruleNames = Collections.unmodifiableList(ruleNames); this.bytes = bytes;
            this.files = Collections.unmodifiableMap(files);
        }
    }

    public static List<Path> findExports(Path root) throws IOException {
        Path exports = root.toAbsolutePath().normalize().resolve("workshop-exports");
        if (!Files.exists(exports)) return Collections.emptyList();
        if (Files.isSymbolicLink(exports) || !Files.isDirectory(exports)) throw new IOException("Unsafe export directory");
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> packages = Files.newDirectoryStream(exports)) {
            for (Path group : packages) {
                if (Files.isSymbolicLink(group) || !Files.isDirectory(group)) continue;
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(group)) {
                    for (Path entry : entries) if (!Files.isSymbolicLink(entry) && Files.isDirectory(entry)
                            && Files.isRegularFile(entry.resolve(WorkshopPackageCatalog.MANIFEST), LinkOption.NOFOLLOW_LINKS)) result.add(entry);
                }
            }
        }
        result.sort(Comparator.comparing(Path::toString).reversed());
        return result;
    }

    public static Snapshot inspect(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        WorkshopPackageCatalog.Bundle bundle = WorkshopPackageCatalog.readBundle(root, null);
        if (bundle.rules.isEmpty() || !bundle.missingAssets.isEmpty()) throw new IOException("Export has missing audio or no rules");
        Map<String, WorkshopPackageCatalog.MediaFile> files = new LinkedHashMap<>();
        long total = 0;
        for (WorkshopPackageCatalog.MediaFile record : bundle.files) {
            if (record == null || record.path == null || record.sha256 == null || !record.sha256.matches("(?i)[0-9a-f]{64}") || record.bytes < 0)
                throw new IOException("Export has an invalid file record");
            String name = WorkshopPackageCatalog.safeRelative(record.path);
            if (name.equals(WorkshopPackageCatalog.MANIFEST) || files.put(name, record) != null)
                throw new IOException("Export has duplicate file records");
            Path path = checkedFile(root, name);
            if (Files.size(path) != record.bytes || !sha256(path).equalsIgnoreCase(record.sha256))
                throw new IOException("Export file changed: " + name);
            total = Math.addExact(total, record.bytes);
        }
        List<String> contents = new ArrayList<>(), ruleNames = new ArrayList<>();
        boolean playable=false;
        for (Rule rule : bundle.rules) {
            if(rule.playable())playable=true;
            contents.add(rule.name);
            ruleNames.add(rule.name);
            for (Candidate candidate : rule.candidates) {
                contents.add("  " + candidate.name);
                for (String path : new String[] {candidate.sourcePath, candidate.cachePath})
                    if (path != null && !files.containsKey(WorkshopPackageCatalog.safeRelative(path)))
                        throw new IOException("Export audio is not included in the file list");
            }
        }
        if(!playable)throw new IOException("Export has no playable rules");
        Set<String> actual = new HashSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir)) throw new IOException("Export contains a linked directory");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) throw new IOException("Export contains an unsafe file");
                actual.add(root.relativize(file).toString().replace('\\','/'));
                return FileVisitResult.CONTINUE;
            }
        });
        Set<String> expected = new HashSet<>(files.keySet()); expected.add(WorkshopPackageCatalog.MANIFEST);
        if (!actual.equals(expected)) throw new IOException("Export contains unexpected or missing files");
        return new Snapshot(root,bundle,sha256(root.resolve(WorkshopPackageCatalog.MANIFEST)),contents,ruleNames,total,files);
    }

    public static final class Staged implements AutoCloseable {
        public final Path directory, content, cover;
        private Staged(Path directory) { this.directory = directory; content = directory.resolve("content"); cover = directory.resolve("cover.jpg"); }
        @Override public void close() throws IOException {
            if (!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)) return;
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    if (failure != null) throw failure; Files.delete(dir); return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    public static Staged stage(Snapshot snapshot, Path cover) throws IOException {
        return stage(snapshot,cover,null);
    }

    public static Staged stage(Snapshot snapshot, Path cover, String expectedCoverHash) throws IOException {
        Snapshot current = inspect(snapshot.directory);
        if (!current.manifestHash.equals(snapshot.manifestHash)) throw new IOException("Export changed after preview; select it again");
        if (Files.isSymbolicLink(cover) || !Files.isRegularFile(cover,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Cover is unavailable");
        Staged staged = new Staged(Files.createTempDirectory("battleaudio-workshop-"));
        staged.directory.toFile().deleteOnExit();
        try {
            Files.createDirectory(staged.content);
            staged.content.toFile().deleteOnExit();
            Files.copy(current.directory.resolve(WorkshopPackageCatalog.MANIFEST),staged.content.resolve(WorkshopPackageCatalog.MANIFEST));
            staged.content.resolve(WorkshopPackageCatalog.MANIFEST).toFile().deleteOnExit();
            if (!sha256(staged.content.resolve(WorkshopPackageCatalog.MANIFEST)).equals(current.manifestHash)) throw new IOException("Export changed while preparing upload");
            for (Map.Entry<String, WorkshopPackageCatalog.MediaFile> entry : current.files.entrySet()) {
                Path source = checkedFile(current.directory,entry.getKey());
                Path target = staged.content.resolve(entry.getKey()).normalize();
                if (!target.startsWith(staged.content)) throw new IOException("Unsafe staged file path");
                Files.createDirectories(target.getParent());
                Path cursor=staged.content;
                for(Path part:staged.content.relativize(target.getParent())) { cursor=cursor.resolve(part); cursor.toFile().deleteOnExit(); }
                Files.copy(source,target); target.toFile().deleteOnExit();
                if (Files.size(target) != entry.getValue().bytes || !sha256(target).equalsIgnoreCase(entry.getValue().sha256))
                    throw new IOException("Export changed while preparing upload: " + entry.getKey());
            }
            Files.copy(cover,staged.cover); staged.cover.toFile().deleteOnExit();
            if (Files.size(staged.cover) == 0 || Files.size(staged.cover) > 1024 * 1024) throw new IOException("Cover must be smaller than 1 MB");
            if (expectedCoverHash!=null && !sha256(staged.cover).equals(expectedCoverHash))
                throw new IOException("Cover changed after preview; select it again");
            return staged;
        } catch (Exception ex) {
            try { staged.close(); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            if (ex instanceof IOException) throw (IOException)ex;
            throw new IOException("Could not prepare upload",ex);
        }
    }

    private static Path checkedFile(Path root, String name) throws IOException {
        Path relative = Paths.get(name); Path cursor = root;
        for (Path part : relative) { cursor = cursor.resolve(part); if (Files.isSymbolicLink(cursor)) throw new IOException("Export contains a linked path"); }
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Export file is missing: " + name);
        return path;
    }

    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] bytes = new byte[65536]; int read;
                while ((read = in.read(bytes)) != -1) digest.update(bytes,0,read);
            }
            StringBuilder out = new StringBuilder();
            for (byte value : digest.digest()) out.append(String.format(Locale.ROOT,"%02x",value & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IOException(ex); }
    }
}
