package battleaudio;

import com.evacipated.cardcrawl.modthespire.Loader;
import com.evacipated.cardcrawl.modthespire.ModInfo;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.ArrayList;

/** Applies real BaseMod and game patches without launching or changing the installed game. */
public final class PatchSmoke {
    public static void main(String[] args) throws Exception {
        Loader.STS_JAR = args[0];
        Loader.STS_PATCHED_JAR = Paths.get(args[3], "patched-game.jar").toString();
        Loader.OUT_JAR = true;
        Loader.ARGS = new String[0];
        Method version = Loader.class.getDeclaredMethod("loadMTSVersion");
        version.setAccessible(true);
        version.invoke(null);
        Method gameVersion = Loader.class.getDeclaredMethod("findGameVersion");
        gameVersion.setAccessible(true);
        gameVersion.invoke(null);
        Field all = Loader.class.getDeclaredField("ALLMODINFOS");
        all.setAccessible(true);
        all.set(null, new ModInfo[] {ModInfo.ReadModInfo(new File(args[1])), ModInfo.ReadModInfo(new File(args[2]))});
        Field workshop = Loader.class.getDeclaredField("WORKSHOP_INFOS");
        workshop.setAccessible(true);
        workshop.set(null, new ArrayList<>());
        Files.deleteIfExists(Paths.get(Loader.STS_PATCHED_JAR));
        Loader.runMods(new File[] {new File(args[1]), new File(args[2])});
        if (!Files.isRegularFile(Paths.get(Loader.STS_PATCHED_JAR))) throw new AssertionError("Patch compilation did not produce an output");
        System.out.println("PASS: real ModTheSpire + BaseMod patch application");
    }
}
