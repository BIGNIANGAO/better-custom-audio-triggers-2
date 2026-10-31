package battleaudio.workshop;

import battleaudio.storage.WorkshopExport;
import battleaudio.storage.WorkshopPublicationRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Coordinates one explicitly confirmed Workshop submission and its durable item ID. */
public final class WorkshopPublisher {
    public enum State { IDLE, CREATING, READY, AGREEMENT, UPLOADING, ADDING_DEPENDENCY, SUCCESS, FAILED }
    public enum Visibility { PRIVATE, FRIENDS, PUBLIC }
    public interface Callback { void complete(String itemId, boolean agreement, String error); }
    public interface ItemCallback { void complete(String itemId, String title, String error); }
    public interface Gateway {
        String accountId() throws Exception;
        void create(Callback callback) throws Exception;
        void submit(String itemId, Request request, WorkshopExport.Staged staged, Callback callback) throws Exception;
        void ensureDependencySupport() throws Exception;
        void addDependency(String itemId,String requiredItemId,Callback callback) throws Exception;
        default void update() { }
        default void verifyOwner(String itemId,String accountId,ItemCallback callback) throws Exception { throw new IOException("Steam item verification is unavailable"); }
        default void cancelVerification() { }
        Progress progress();
        void close();
    }
    public static final class Progress {
        public final String phase; public final long processed, total;
        public Progress(String phase,long processed,long total) { this.phase=phase; this.processed=processed; this.total=total; }
    }
    public static final class Request {
        public static final String REQUIRED_ITEM_ID="3830077500";
        public static final String FIXED_DESCRIPTION="\u9002\u7528\u4e8e\u66f4\u597d\u7684\u81ea\u5b9a\u4e49\u97f3\u9891\u89e6\u53d12.0 mod\u7684\u97f3\u4e50\u5305:\n"
                +"https://steamcommunity.com/sharedfiles/filedetails/?id="+REQUIRED_ITEM_ID+"\n"
                +"\u8ba2\u9605\u97f3\u4e50\u5305\u548c\u672c\u4f53\u540e\uff0c\u542f\u7528\u672c\u4f53\u5e76\u91cd\u542f\u6e38\u620f\uff0c\u5373\u53ef\u5728\u8bbe\u7f6e\u4e2d\u67e5\u770b\u97f3\u4e50\u5305\u548c\u89c4\u5219\u3002\n\n"
                +"Music pack for Better Custom Audio Triggers 2.0 mod:\n"
                +"Subscribe to this music pack and the mod, then restart the game with the mod enabled. The music pack and its rules will appear in the mod settings.";
        public static String defaultTitle(String name) { return name.trim()+" - Better Custom Audio Triggers 2.0"; }
        public static String description(String editable) { return FIXED_DESCRIPTION+(editable==null || editable.isEmpty()?"":"\n\n"+editable); }
        public final String title, description;
        public final Visibility visibility;
        public Request(String title,String editable,Visibility visibility) {
            if (title==null || title.trim().isEmpty()) throw new IllegalArgumentException("Enter a Workshop title");
            if (visibility==null) throw new IllegalArgumentException("Choose a visibility");
            this.title=title.trim();
            this.description=description(editable);
            if (this.title.getBytes(StandardCharsets.UTF_8).length>128) throw new IllegalArgumentException("Workshop title exceeds 128 UTF-8 bytes");
            if (this.description.getBytes(StandardCharsets.UTF_8).length>8000) throw new IllegalArgumentException("Workshop description exceeds 8000 UTF-8 bytes");
            this.visibility=visibility;
        }
    }
    private final Gateway gateway;
    private final WorkshopPublicationRegistry registry;
    private State state=State.IDLE;
    private String itemId, accountId, error, pendingPackageId;
    private String verifiedItemId, verifiedAccountId;
    private long verificationGeneration;
    private Request request;
    private WorkshopExport.Staged staged;
    private boolean submitting, addingDependency, associationFailed;
    private Progress progress=new Progress("",0,0);
    private long nextProgressNanos;

    public WorkshopPublisher(Gateway gateway,WorkshopPublicationRegistry registry) { this.gateway=gateway; this.registry=registry; }
    public synchronized State state() { return state; }
    public synchronized String itemId() { return itemId; }
    public synchronized String error() { return error; }
    public synchronized boolean hasUnsavedItem() { return associationFailed; }
    public synchronized Progress progress() { return progress; }
    public synchronized boolean active() { return state==State.CREATING || state==State.READY || state==State.UPLOADING || state==State.ADDING_DEPENDENCY; }
    public String accountId() throws Exception { return gateway.accountId(); }
    public String linkedItem(String packageId) throws Exception { return registry.find(gateway.accountId(),packageId); }
    public boolean creationPending(String packageId) throws Exception { return registry.isCreating(gateway.accountId(),packageId); }
    public void clearPendingCreation(String packageId,String expectedAccount) throws Exception {
        String account=gateway.accountId();
        if(!account.equals(expectedAccount))throw new IOException("Steam account changed; review the publication again");
        registry.clearPendingCreation(account,packageId);
    }
    public void verifyExisting(String value,String expectedAccount,ItemCallback callback) throws Exception {
        String account=gateway.accountId();
        if (!account.equals(expectedAccount)) throw new IOException("Steam account changed; review the publication again");
        String requested=normalizeItemId(value);
        final long generation;
        synchronized(this) { verifiedItemId=null; verifiedAccountId=null; generation=++verificationGeneration; }
        gateway.verifyOwner(requested,account,(id,title,failure) -> {
            String problem=failure;
            if(problem==null && !requested.equals(id)) problem="Steam returned a different Workshop item";
            synchronized(this) {
                if(generation!=verificationGeneration)return;
                if(problem==null) { verifiedItemId=id; verifiedAccountId=account; }
            }
            callback.complete(id,title,problem);
        });
    }
    public void cancelVerification() {
        synchronized(this) { verificationGeneration++; verifiedItemId=null; verifiedAccountId=null; }
        gateway.cancelVerification();
    }
    public void linkExisting(String packageId,String itemId,String expectedAccount) throws Exception {
        String account=gateway.accountId();
        if (!account.equals(expectedAccount)) throw new IOException("Steam account changed; review the publication again");
        String normalized=normalizeItemId(itemId);
        synchronized(this) {
            if (!normalized.equals(verifiedItemId) || !account.equals(verifiedAccountId))
                throw new IOException("Verify the Workshop item owner before linking it");
        }
        registry.remember(account,packageId,normalized);
        synchronized(this) { verifiedItemId=null; verifiedAccountId=null; }
    }
    public static String normalizeItemId(String value) throws IOException {
        String text=value==null ? "" : value.trim();
        if (text.length()>1024) throw new IOException("Enter a valid Workshop item ID or link");
        if (!text.matches("[0-9]{1,20}")) {
            try {
                java.net.URI uri=new java.net.URI(text);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || !"steamcommunity.com".equalsIgnoreCase(uri.getHost())
                        || uri.getUserInfo()!=null || uri.getPort()!=-1 || uri.getFragment()!=null
                        || !("/sharedfiles/filedetails/".equals(uri.getPath()) || "/sharedfiles/filedetails".equals(uri.getPath())))
                    throw new IllegalArgumentException();
                String found=null;
                if (uri.getRawQuery()!=null) for(String part:uri.getRawQuery().split("&")) {
                    String[] pair=part.split("=",2);
                    if (pair.length==2 && "id".equals(pair[0])) { if(found!=null)throw new IllegalArgumentException(); found=pair[1]; }
                }
                text=found;
            } catch(Exception ex) { throw new IOException("Enter a valid Workshop item ID or link",ex); }
        }
        try { if (text==null || !text.matches("[0-9]{1,20}") || Long.parseUnsignedLong(text)==0) throw new NumberFormatException(); }
        catch(NumberFormatException ex) { throw new IOException("Enter a valid Workshop item ID or link",ex); }
        return Long.toUnsignedString(Long.parseUnsignedLong(text));
    }

    public void start(Request request,WorkshopExport.Snapshot snapshot,WorkshopExport.Staged staged,boolean update) throws Exception {
        start(request,snapshot,staged,update,null,null);
    }
    public void start(Request request,WorkshopExport.Snapshot snapshot,WorkshopExport.Staged staged,boolean update,
            String expectedAccount,String expectedItemId) throws Exception {
        String account=gateway.accountId(); String linked=registry.find(account,snapshot.packageId);
        if (expectedAccount!=null && !expectedAccount.equals(account)) throw new IOException("Steam account changed; review the publication again");
        if (update && expectedItemId!=null && !expectedItemId.equals(linked)) throw new IOException("Workshop item changed; review the publication again");
        if (update && linked==null) throw new IOException("No published item is linked to this package and Steam account");
        if (!update && linked!=null) throw new IOException("This package is already linked; choose Update");
        gateway.ensureDependencySupport();
        synchronized(this) {
            if (associationFailed) throw new IOException("Save the created item link before trying again");
            if (state!=State.IDLE && state!=State.FAILED && state!=State.SUCCESS) throw new IOException("A publication is already in progress");
            if (!update) registry.beginCreation(account,snapshot.packageId);
            this.accountId=account; this.request=request; this.staged=staged; this.itemId=linked; this.error=null;
            this.pendingPackageId=snapshot.packageId; this.associationFailed=false;
            this.state=update ? State.READY : State.CREATING; this.submitting=false; this.addingDependency=false; this.progress=new Progress("",0,0); this.nextProgressNanos=0;
        }
        if (!update) {
            try { gateway.create((id,agreement,failure) -> created(snapshot.packageId,id,agreement,failure)); }
            catch (Exception ex) { fail(ex.getMessage()); }
        }
    }

    private void created(String packageId,String id,boolean agreement,String failure) {
        synchronized(this) { if (state!=State.CREATING) return; }
        if (failure!=null || id==null) { fail(failure==null ? "Steam did not return an item ID" : failure); return; }
        synchronized(this) { itemId=id; }
        try { registry.remember(accountId,packageId,id); }
        catch (Exception ex) {
            synchronized(this) { associationFailed=true; }
            fail("Item was created, but its local association could not be saved: " + id + ": " + ex.getMessage()); return;
        }
        synchronized(this) { if (state==State.CREATING) state=agreement ? State.AGREEMENT : State.READY; }
    }

    /** Call on the game thread; Steam's callback ticker may run on another thread. */
    public void update() {
        gateway.update();
        String id=null; Request current=null; WorkshopExport.Staged upload=null;
        synchronized(this) {
            if (state==State.READY && !submitting) {
                submitting=true; state=State.UPLOADING; id=itemId; current=request; upload=staged;
            }
        }
        if (id!=null) {
            try { gateway.submit(id,current,upload,this::submitted); }
            catch (Exception ex) { fail(ex.getMessage()); }
        }
        String dependencyItem=null;
        synchronized(this) {
            if(state==State.ADDING_DEPENDENCY && !addingDependency) { addingDependency=true; dependencyItem=itemId; }
        }
        if(dependencyItem!=null) {
            try { gateway.addDependency(dependencyItem,Request.REQUIRED_ITEM_ID,this::dependencyAdded); }
            catch(Exception ex) { fail("Upload completed, but the required item could not be added: "+ex.getMessage()); }
        }
        boolean poll;
        synchronized(this) {
            long now=System.nanoTime(); poll=state==State.UPLOADING && now>=nextProgressNanos;
            if(poll)nextProgressNanos=now+200_000_000L;
        }
        if(poll) {
            Progress currentProgress=gateway.progress();
            synchronized(this) { if(state==State.UPLOADING)progress=currentProgress; }
        }
    }

    public synchronized void continueAfterAgreement() {
        if (state==State.AGREEMENT) { state=State.READY; submitting=false; }
    }
    private void submitted(String id,boolean agreement,String failure) {
        boolean mismatch;
        synchronized(this) {
            if (state!=State.UPLOADING) return;
            mismatch=!itemId.equals(id);
            if (!mismatch && failure==null && agreement) { state=State.AGREEMENT; submitting=false; return; }
        }
        if (failure!=null) { fail(failure); return; }
        if (mismatch) { fail("Steam returned a different Workshop item"); return; }
        synchronized(this) { state=State.ADDING_DEPENDENCY; submitting=false; addingDependency=false; }
    }
    private void dependencyAdded(String id,boolean ignored,String failure) {
        synchronized(this) { if(state!=State.ADDING_DEPENDENCY)return; }
        if(failure!=null || !itemId.equals(id)) {
            fail("Upload completed, but the required item could not be added: "+(failure==null?"Steam returned a different Workshop item":failure));
            return;
        }
        synchronized(this) { state=State.SUCCESS; addingDependency=false; }
        cleanup();
    }
    private void fail(String message) {
        synchronized(this) { error=message==null ? "Workshop operation failed" : message; state=State.FAILED; submitting=false; addingDependency=false; }
        cleanup();
    }
    private void cleanup() {
        WorkshopExport.Staged current;
        synchronized(this) { current=staged; staged=null; }
        if (current!=null) try { current.close(); } catch (IOException ignored) { }
    }
    public void retryAssociation() throws Exception {
        String account,id,packageId;
        synchronized(this) {
            if(!associationFailed || itemId==null)throw new IOException("No unsaved Workshop item");
            account=accountId; id=itemId; packageId=pendingPackageId;
        }
        if(!account.equals(gateway.accountId()))throw new IOException("Steam account changed; review the publication again");
        registry.remember(account,packageId,id);
        synchronized(this) { associationFailed=false; }
    }
    public void reset() {
        synchronized(this) { if (active() || associationFailed) return; state=State.IDLE; error=null; itemId=null; submitting=false; }
        cleanup();
    }
    public void close() {
        synchronized(this) { if (active()) return; }
        cleanup(); gateway.close();
    }
}
