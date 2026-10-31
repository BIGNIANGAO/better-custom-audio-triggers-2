package battleaudio.workshop;

import com.codedisaster.steamworks.SteamAPI;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;

/** Read-only Steam call that exercises the same native call-result polling as dependency creation. */
public final class SteamDependencyNativeSmoke {
    private SteamDependencyNativeSmoke() { }
    public static void run() throws Exception {
        String name=Platform.isWindows()?(Native.POINTER_SIZE==8?"steam_api64":"steam_api"):"steam_api";
        NativeLibrary lib=NativeLibrary.getInstance(name);
        Pointer stats=lib.getFunction("SteamAPI_SteamUserStats_v012").invokePointer(new Object[0]);
        Pointer utils=lib.getFunction("SteamAPI_SteamUtils_v010").invokePointer(new Object[0]);
        if(stats==null || utils==null)throw new AssertionError("Steam read-only native interfaces unavailable");
        long call=lib.getFunction("SteamAPI_ISteamUserStats_GetNumberOfCurrentPlayers").invokeLong(new Object[]{stats});
        if(call==0)throw new AssertionError("Steam read-only native call was not started");
        Memory failed=new Memory(1); failed.clear(); long deadline=System.nanoTime()+15_000_000_000L;
        while((lib.getFunction("SteamAPI_ISteamUtils_IsAPICallCompleted").invokeInt(new Object[]{utils,call,failed}) & 255)==0
                && System.nanoTime()<deadline) { SteamAPI.runCallbacks(); Thread.sleep(20); }
        if(failed.getByte(0)!=0)throw new AssertionError("Steam read-only call failed");
        Memory result=new Memory(8); result.clear(); failed.clear();
        int ok=lib.getFunction("SteamAPI_ISteamUtils_GetAPICallResult").invokeInt(new Object[]{utils,call,result,8,1107,failed}) & 255;
        if(ok==0 || failed.getByte(0)!=0)throw new AssertionError("Steam native call result was not available");
        System.out.println("PASS: Steam native asynchronous call-result polling (read-only)");
    }
}
