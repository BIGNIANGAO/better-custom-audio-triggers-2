package battleaudio;

import com.google.gson.Gson;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exercises the game's real uploader config reader without contacting Steam. */
public final class UploaderEncodingSmoke {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected fixture root");
        String original="\u97f3\u4e50\u5305\u6d4b\u8bd5 \u2192 \u00e9 \u20ac \u4e2d\u6587";
        Map<String,Object> config=new LinkedHashMap<>(); config.put("title","UTF-8 test");
        config.put("description",original); config.put("visibility","private"); config.put("changeNote",""); config.put("tags",new String[0]);
        Path root=Paths.get(args[0]); Files.createDirectories(root);
        Path file=Files.createTempFile(root,"uploader-utf8-",".json");
        try {
            Files.write(file,new Gson().toJson(config).getBytes(StandardCharsets.UTF_8));
            Class<?> type=Class.forName("com.megacrit.moduploader.WorkshopUploadConfiguration");
            Method load=type.getDeclaredMethod("load",java.io.File.class); load.setAccessible(true);
            Object parsed=load.invoke(null,file.toFile());
            Field description=type.getDeclaredField("description"); description.setAccessible(true);
            String actual=(String)description.get(parsed);
            if(!original.equals(actual))throw new AssertionError("Uploader did not preserve Unicode text");
            System.out.println("PASS: bundled uploader reads UTF-8 CJK and extended characters exactly");
        } finally { Files.deleteIfExists(file); }
    }
}
