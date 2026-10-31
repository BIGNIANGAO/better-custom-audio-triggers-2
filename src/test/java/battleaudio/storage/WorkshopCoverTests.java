package battleaudio.storage;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public final class WorkshopCoverTests {
    private WorkshopCoverTests() { }
    public static void run() throws Exception {
        Path cover=WorkshopCover.createFallback();
        try {
            BufferedImage image=ImageIO.read(cover.toFile());
            if(image==null || image.getWidth()!=512 || image.getHeight()!=512 || Files.size(cover)>1024*1024)
                throw new AssertionError("Source-only fallback cover is not a Steam-sized JPEG");
            if(image.getRGB(10,10)==image.getRGB(256,256))throw new AssertionError("Source-only fallback cover is blank");
        } finally { Files.deleteIfExists(cover); }
        System.out.println("PASS: source-only default Workshop cover is generated without private images");
    }
}
