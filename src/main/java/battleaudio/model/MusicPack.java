package battleaudio.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Metadata for one independent set of audio rules and assets. */
public final class MusicPack {
    public enum Source { MIGRATED, LOCAL, SUBSCRIBED }

    public String id = UUID.randomUUID().toString();
    /** Stable package identity authored into the distributable package manifest. */
    public String contentId = UUID.randomUUID().toString();
    public String name = "新音乐包";
    public Source source = Source.LOCAL;
    /** Steam Workshop published file ID; empty for local-only packages. */
    public String workshopId = "";
    /** Folder under the local music-packs directory; never a Steam Workshop path. */
    public String localFolder = "";
    public String version = "1";
    public boolean enabled = true;
    /** Rule IDs belonging to this package. Config refreshes this index before persistence. */
    public List<String> ruleIds = new ArrayList<>();
    /** Files copied into this package directory and therefore safe for package-scoped cleanup. */
    public List<OwnedFile> ownedFiles = new ArrayList<>();

    public static final class OwnedFile {
        public String path;
        public String sha256;
        public long bytes;

        public OwnedFile() { }
        public OwnedFile(String path, String sha256, long bytes) {
            this.path = path;
            this.sha256 = sha256;
            this.bytes = bytes;
        }
    }
}
