package battleaudio;

import com.evacipated.cardcrawl.modthespire.Loader;
import com.evacipated.cardcrawl.modthespire.ModInfo;
import java.io.File;
import java.lang.reflect.*;
import java.util.ArrayList;

public final class GameLaunchSmoke {
    public static void main(String[] args) throws Exception {
        Loader.STS_JAR = args[0];
        Loader.ARGS = new String[0];
        Loader.SKIP_INTRO = true;
        Loader.DEBUG = true;
        for (String name : new String[] {"loadMTSVersion", "findGameVersion"}) {
            Method method = Loader.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(null);
        }
        Field all = Loader.class.getDeclaredField("ALLMODINFOS");
        all.setAccessible(true);
        all.set(null, new ModInfo[] {ModInfo.ReadModInfo(new File(args[1])), ModInfo.ReadModInfo(new File(args[2])), ModInfo.ReadModInfo(new File(args[3]))});
        Field workshop = Loader.class.getDeclaredField("WORKSHOP_INFOS");
        workshop.setAccessible(true);
        workshop.set(null, new ArrayList<>());
        Loader.runMods(new File[] {new File(args[1]), new File(args[2]), new File(args[3])});
    }
}
