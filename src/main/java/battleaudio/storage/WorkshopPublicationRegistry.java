package battleaudio.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Local account-specific links between exported pack identities and Workshop items. */
public final class WorkshopPublicationRegistry {
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final class Data { int schemaVersion = 1; List<Link> links = new ArrayList<>(); }
    private static final class Link { String accountId, packageId, itemId; boolean creating; }

    public WorkshopPublicationRegistry(Path root) { file = root.toAbsolutePath().normalize().resolve("workshop-publications.json"); }

    public synchronized String find(String accountId, String packageId) throws IOException {
        validateAccount(accountId); validatePackage(packageId);
        for (Link link : read().links) if (accountId.equals(link.accountId) && packageId.equals(link.packageId)) return link.itemId;
        return null;
    }

    public synchronized boolean isCreating(String accountId,String packageId) throws IOException {
        validateAccount(accountId); validatePackage(packageId);
        for(Link link:read().links) if(accountId.equals(link.accountId) && packageId.equals(link.packageId))return link.creating;
        return false;
    }

    public synchronized void beginCreation(String accountId,String packageId) throws IOException {
        validateAccount(accountId); validatePackage(packageId);
        Data data=read();
        for(Link link:data.links) if(accountId.equals(link.accountId) && packageId.equals(link.packageId))
            throw new IOException("This package already has a linked or pending Workshop item");
        Link link=new Link(); link.accountId=accountId; link.packageId=packageId; link.creating=true; data.links.add(link);
        write(data);
    }

    public synchronized void clearPendingCreation(String accountId,String packageId) throws IOException {
        validateAccount(accountId); validatePackage(packageId);
        Data data=read();
        boolean removed=data.links.removeIf(link -> accountId.equals(link.accountId) && packageId.equals(link.packageId) && link.creating);
        if(removed)write(data);
    }

    public synchronized void remember(String accountId, String packageId, String itemId) throws IOException {
        validateAccount(accountId); validatePackage(packageId); validateId(itemId);
        Data data = read();
        for (Link link : data.links) if (accountId.equals(link.accountId) && packageId.equals(link.packageId)) {
            if (link.creating) { link.itemId=itemId; link.creating=false; write(data); return; }
            if (!itemId.equals(link.itemId)) throw new IOException("This package is already linked to another Workshop item");
            return;
        }
        Link link = new Link(); link.accountId=accountId; link.packageId=packageId; link.itemId=itemId; data.links.add(link);
        write(data);
    }

    private void write(Data data) throws IOException {
        Path root = file.getParent();
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(file)) throw new IOException("Publication registry path is unsafe");
        Files.createDirectories(root);
        Path temp = Files.createTempFile(root,"workshop-publications-",".tmp");
        try {
            Files.write(temp,gson.toJson(data).getBytes(StandardCharsets.UTF_8));
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private Data read() throws IOException {
        if (!Files.exists(file,LinkOption.NOFOLLOW_LINKS)) return new Data();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>1024*1024)
            throw new IOException("Publication registry is unsafe or too large");
        Data data;
        try (Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) { data=gson.fromJson(reader,Data.class); }
        catch (RuntimeException ex) { throw new IOException("Publication registry is invalid",ex); }
        if (data==null || data.schemaVersion!=1 || data.links==null) throw new IOException("Publication registry is invalid");
        java.util.Set<String> seen=new java.util.HashSet<>();
        for (Link link : data.links) {
            if (link==null) throw new IOException("Publication registry is invalid");
            validateAccount(link.accountId); validatePackage(link.packageId);
            if(link.creating) { if(link.itemId!=null)throw new IOException("Publication registry has an invalid pending item"); }
            else validateId(link.itemId);
            if (!seen.add(link.accountId+"/"+link.packageId)) throw new IOException("Publication registry has duplicate links");
        }
        return data;
    }
    private static void validateAccount(String id) throws IOException { validateId(id); }
    private static void validateId(String id) throws IOException {
        try { if (id==null || !id.matches("[0-9]{1,20}") || Long.parseUnsignedLong(id)==0) throw new NumberFormatException(); }
        catch (NumberFormatException ex) { throw new IOException("Invalid Steam identifier",ex); }
    }
    private static void validatePackage(String id) throws IOException {
        if (id==null || !id.matches("[A-Za-z0-9._-]{1,128}") || id.contains("..")) throw new IOException("Invalid package identifier");
    }
}
