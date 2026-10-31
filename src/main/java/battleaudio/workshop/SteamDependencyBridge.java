package battleaudio.workshop;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Calls the dependency API missing from the game's Steamworks4j wrapper. */
final class SteamDependencyBridge {
    private static final int CALLBACK_ID=3412;
    private final Function add, completed, result;
    private final Pointer ugc, utils;
    private long call, parent, child, started;

    SteamDependencyBridge() throws IOException {
        try {
            NativeLibrary library=library();
            add=library.getFunction("SteamAPI_ISteamUGC_AddDependency");
            completed=library.getFunction("SteamAPI_ISteamUtils_IsAPICallCompleted");
            result=library.getFunction("SteamAPI_ISteamUtils_GetAPICallResult");
            ugc=library.getFunction("SteamAPI_SteamUGC_v016").invokePointer(new Object[0]);
            utils=library.getFunction("SteamAPI_SteamUtils_v010").invokePointer(new Object[0]);
            if(ugc==null || utils==null)throw new IOException("Steam dependency API is unavailable");
        } catch(LinkageError | RuntimeException ex) { throw new IOException("Steam dependency API is unavailable",ex); }
    }

    private static NativeLibrary library() {
        String name=Platform.isWindows()?(Native.POINTER_SIZE==8?"steam_api64":"steam_api"):"steam_api";
        try { return NativeLibrary.getInstance(name); }
        catch(UnsatisfiedLinkError original) {
            String file=Platform.isWindows()?name+".dll":Platform.isMac()?"libsteam_api.dylib":"libsteam_api.so";
            String custom=System.getProperty("com.codedisaster.steamworks.SharedLibraryExtractPath");
            Path folder=custom==null ? Paths.get(System.getProperty("java.io.tmpdir"),
                    System.getProperty("com.codedisaster.steamworks.SharedLibraryExtractDirectory","steamworks4j"),"1.9.0") : Paths.get(custom);
            Path exact=folder.resolve(file);
            if(!Files.isRegularFile(exact))throw original;
            return NativeLibrary.getInstance(exact.toAbsolutePath().toString());
        }
    }

    void begin(String itemId,String requiredItemId) throws IOException {
        if(call!=0)throw new IOException("A required-item operation is already running");
        parent=Long.parseUnsignedLong(itemId); child=Long.parseUnsignedLong(requiredItemId);
        call=add.invokeLong(new Object[]{ugc,parent,child});
        if(call==0)throw new IOException("Steam could not start adding the required item");
        started=System.nanoTime();
    }

    String poll() throws IOException {
        if(call==0)throw new IOException("No required-item operation is running");
        Memory failed=new Memory(1); failed.clear();
        if((completed.invokeInt(new Object[]{utils,call,failed}) & 255)==0) {
            if(System.nanoTime()-started>30_000_000_000L)throw new IOException("Steam required-item operation timed out");
            return null;
        }
        if(failed.getByte(0)!=0)throw new IOException("Steam required-item operation failed");
        boolean packed=Platform.isLinux() || Platform.isMac();
        int size=packed?20:24, offset=packed?4:8;
        Memory data=new Memory(size); data.clear(); failed.clear();
        if((result.invokeInt(new Object[]{utils,call,data,size,CALLBACK_ID,failed}) & 255)==0 || failed.getByte(0)!=0)
            throw new IOException("Steam did not return the required-item result");
        call=0;
        int code=data.getInt(0);
        // Reapplying an already-present dependency is idempotent for updates.
        if(code!=1 && code!=29)throw new IOException("Steam result: "+code);
        if(data.getLong(offset)!=parent || data.getLong(offset+8)!=child)
            throw new IOException("Steam returned a different required item");
        return Long.toUnsignedString(parent);
    }
}
