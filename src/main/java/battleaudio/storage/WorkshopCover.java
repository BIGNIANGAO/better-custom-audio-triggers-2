package battleaudio.storage;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Produces a bounded JPEG preview without changing the player's original image. */
public final class WorkshopCover {
    private static final int SIZE=512;
    private WorkshopCover() { }

    public static Path createDefault() throws IOException {
        try (InputStream input=WorkshopCover.class.getResourceAsStream("/battleaudio/workshop-default-cover.jpg")) {
            if(input==null)return createFallback();
            Path target=Files.createTempFile("battleaudio-cover-",".jpg");
            try {
                Files.copy(input,target,StandardCopyOption.REPLACE_EXISTING);
                if(Files.size(target)==0 || Files.size(target)>1024*1024)throw new IOException("Cannot prepare cover image");
                target.toFile().deleteOnExit(); return target;
            } catch(IOException ex) { Files.deleteIfExists(target); throw ex; }
        }
    }

    static Path createFallback() throws IOException {
        BufferedImage image=new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(20,33,36)); graphics.fillRect(0,0,SIZE,SIZE);
            graphics.setColor(new Color(37,58,59)); graphics.fillPolygon(new int[]{0,365,512,512,0},new int[]{390,25,25,512,512},5);
            graphics.setColor(new Color(85,190,185));
            int[] heights={54,112,172,244,160,202,124,64};
            for(int i=0;i<heights.length;i++) graphics.fillRoundRect(74+i*47,225-heights[i]/2,18,heights[i],8,8);
            graphics.setColor(new Color(240,204,117)); graphics.fillRect(70,373,372,3);
            graphics.setFont(new Font("SansSerif",Font.BOLD,34)); graphics.drawString("MUSIC PACK",70,427);
        } finally { graphics.dispose(); }
        return save(image);
    }

    public static Path prepare(Path source) throws IOException {
        Path file=source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>20L*1024*1024)
            throw new IOException("Cover must be an image file smaller than 20 MB");
        BufferedImage input;
        try (ImageInputStream stream=ImageIO.createImageInputStream(file.toFile())) {
            if (stream==null) throw new IOException("Cannot open cover image");
            Iterator<ImageReader> readers=ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Unsupported cover image");
            ImageReader reader=readers.next();
            try {
                reader.setInput(stream,true,true);
                int width=reader.getWidth(0), height=reader.getHeight(0);
                if (width<=0 || height<=0 || width>8192 || height>8192 || (long)width*height>32000000L)
                    throw new IOException("Cover image dimensions are too large");
                input=reader.read(0);
            } finally { reader.dispose(); }
        }
        if (input==null) throw new IOException("Cannot decode cover image");
        BufferedImage output=new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=output.createGraphics();
        try {
            g.setColor(new Color(20,33,36)); g.fillRect(0,0,SIZE,SIZE);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            double scale=Math.min((double)SIZE/input.getWidth(),(double)SIZE/input.getHeight());
            int w=Math.max(1,(int)Math.round(input.getWidth()*scale)), h=Math.max(1,(int)Math.round(input.getHeight()*scale));
            g.drawImage(input,(SIZE-w)/2,(SIZE-h)/2,w,h,null);
        } finally { g.dispose(); input.flush(); }
        return save(output);
    }

    private static Path save(BufferedImage image) throws IOException {
        Path target=Files.createTempFile("battleaudio-cover-",".jpg");
        try {
            if (!ImageIO.write(image,"jpg",target.toFile()) || Files.size(target)>1024*1024) throw new IOException("Cannot prepare cover image");
            target.toFile().deleteOnExit(); return target;
        } catch (IOException ex) { Files.deleteIfExists(target); throw ex; }
        finally { image.flush(); }
    }
}
