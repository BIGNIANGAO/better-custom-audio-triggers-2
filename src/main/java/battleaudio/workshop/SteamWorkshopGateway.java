package battleaudio.workshop;

import battleaudio.storage.WorkshopExport;
import com.codedisaster.steamworks.*;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.integrations.steam.SUCallback;
import com.megacrit.cardcrawl.integrations.DistributorFactory;
import java.io.IOException;

/** Uses the game's already initialized Steam client and its callback ticker. */
public final class SteamWorkshopGateway implements WorkshopPublisher.Gateway, SteamUGCCallback {
    private static final int APP_ID=646570;
    private SteamUGC ugc;
    private SteamUser user;
    private volatile WorkshopPublisher.Callback createCallback, submitCallback;
    private volatile WorkshopPublisher.ItemCallback itemCallback;
    private volatile SteamUGCQuery itemQuery;
    private volatile String queriedItemId, queriedAccountId;
    private final Object queryLock=new Object();
    private volatile SteamUGCUpdateHandle update;
    private SteamDependencyBridge dependency;
    private WorkshopPublisher.Callback dependencyCallback;
    private String dependencyItem;

    private void ensureReady() throws IOException {
        if (CardCrawlGame.publisherIntegration==null
                || CardCrawlGame.publisherIntegration.getType()!=DistributorFactory.Distributor.STEAM
                || !CardCrawlGame.publisherIntegration.isInitialized()) throw new IOException("Steam is not connected to the game");
        try {
            if (!SteamAPI.isSteamRunning()) throw new IOException("Steam is not running");
            if (ugc==null) ugc=new SteamUGC(this);
            if (user==null) user=new SteamUser(new SUCallback());
        } catch (LinkageError | RuntimeException ex) { throw new IOException("Steam Workshop is unavailable",ex); }
    }

    @Override public String accountId() throws Exception {
        ensureReady(); SteamID id=user.getSteamID();
        if (id==null || !id.isValid()) throw new IOException("No Steam account is signed in");
        return Long.toUnsignedString(SteamNativeHandle.getNativeHandle(id));
    }

    @Override public void create(WorkshopPublisher.Callback callback) throws Exception {
        ensureReady(); createCallback=callback;
        SteamAPICall call=ugc.createItem(APP_ID,SteamRemoteStorage.WorkshopFileType.Community);
        if (call==null || !call.isValid()) { createCallback=null; throw new IOException("Steam could not start item creation"); }
    }

    @Override public void verifyOwner(String itemId,String accountId,WorkshopPublisher.ItemCallback callback) throws Exception {
        ensureReady();
        synchronized(queryLock) { if(itemCallback!=null)throw new IOException("A Steam item verification is already in progress"); }
        SteamUGCQuery query=ugc.createQueryUGCDetailsRequest(new SteamPublishedFileID(Long.parseUnsignedLong(itemId)));
        if(query==null || !query.isValid())throw new IOException("Steam could not inspect that Workshop item");
        synchronized(queryLock) { itemQuery=query; queriedItemId=itemId; queriedAccountId=accountId; itemCallback=callback; }
        if(!ugc.setAllowCachedResponse(query,0)) {
            cancelVerification();
            throw new IOException("Steam could not inspect that Workshop item");
        }
        SteamAPICall call=ugc.sendQueryUGCRequest(query);
        if(call==null || !call.isValid()) {
            cancelVerification();
            throw new IOException("Steam could not inspect that Workshop item");
        }
    }
    @Override public void cancelVerification() {
        SteamUGCQuery query;
        synchronized(queryLock) { query=itemQuery; itemQuery=null; itemCallback=null; }
        if(query!=null && ugc!=null)ugc.releaseQueryUserUGCRequest(query);
    }

    @Override public void submit(String itemId,WorkshopPublisher.Request request,WorkshopExport.Staged staged,
            WorkshopPublisher.Callback callback) throws Exception {
        ensureReady();
        SteamPublishedFileID id=new SteamPublishedFileID(Long.parseUnsignedLong(itemId));
        SteamUGCUpdateHandle handle=ugc.startItemUpdate(APP_ID,id);
        if (handle==null || !handle.isValid()) throw new IOException("Steam could not start the item update");
        SteamRemoteStorage.PublishedFileVisibility visibility=request.visibility==WorkshopPublisher.Visibility.PUBLIC
                ? SteamRemoteStorage.PublishedFileVisibility.Public : request.visibility==WorkshopPublisher.Visibility.FRIENDS
                ? SteamRemoteStorage.PublishedFileVisibility.FriendsOnly : SteamRemoteStorage.PublishedFileVisibility.Private;
        if (!ugc.setItemTitle(handle,request.title) || !ugc.setItemDescription(handle,request.description)
                || !ugc.setItemVisibility(handle,visibility)
                || !ugc.setItemContent(handle,staged.content.toAbsolutePath().toString())
                || !ugc.setItemPreview(handle,staged.cover.toAbsolutePath().toString()))
            throw new IOException("Steam rejected the item details or upload files");
        update=handle; submitCallback=callback;
        SteamAPICall call=ugc.submitItemUpdate(handle,"Better Custom Audio Triggers music pack");
        if (call==null || !call.isValid()) { submitCallback=null; update=null; throw new IOException("Steam could not start the upload"); }
    }

    @Override public void ensureDependencySupport() throws Exception {
        ensureReady();
        if(dependency==null)dependency=new SteamDependencyBridge();
    }
    @Override public void addDependency(String itemId,String requiredItemId,WorkshopPublisher.Callback callback) throws Exception {
        ensureDependencySupport();
        if(dependencyCallback!=null)throw new IOException("A required-item operation is already running");
        dependency.begin(itemId,requiredItemId); dependencyItem=itemId; dependencyCallback=callback;
    }
    @Override public void update() {
        if(dependencyCallback==null)return;
        try {
            String id=dependency.poll();
            if(id==null)return;
            WorkshopPublisher.Callback callback=dependencyCallback; dependencyCallback=null; dependencyItem=null;
            callback.complete(id,false,null);
        } catch(Exception | LinkageError ex) {
            WorkshopPublisher.Callback callback=dependencyCallback; String id=dependencyItem;
            dependencyCallback=null; dependencyItem=null; dependency=null;
            callback.complete(id,false,ex.getMessage());
        }
    }

    @Override public WorkshopPublisher.Progress progress() {
        SteamUGCUpdateHandle handle=update;
        if (ugc==null || handle==null) return new WorkshopPublisher.Progress("Preparing",0,0);
        try {
            SteamUGC.ItemUpdateInfo info=new SteamUGC.ItemUpdateInfo();
            SteamUGC.ItemUpdateStatus status=ugc.getItemUpdateProgress(handle,info);
            return new WorkshopPublisher.Progress(status.name(),info.getBytesProcessed(),info.getBytesTotal());
        } catch (RuntimeException ex) { return new WorkshopPublisher.Progress("Waiting for Steam",0,0); }
    }
    @Override public void close() {
        cancelVerification();
        if (ugc!=null) { ugc.dispose(); ugc=null; }
        if (user!=null) { user.dispose(); user=null; }
    }
    private static String failure(SteamResult result) { return result==SteamResult.OK ? null : "Steam: " + result; }
    private static String id(SteamPublishedFileID id) { return id==null ? null : Long.toUnsignedString(SteamNativeHandle.getNativeHandle(id)); }
    @Override public void onCreateItem(SteamPublishedFileID id,boolean agreement,SteamResult result) {
        WorkshopPublisher.Callback callback=createCallback; createCallback=null;
        if (callback!=null) callback.complete(id(id),agreement,failure(result));
    }
    @Override public void onSubmitItemUpdate(SteamPublishedFileID id,boolean agreement,SteamResult result) {
        WorkshopPublisher.Callback callback=submitCallback; submitCallback=null; update=null;
        if (callback!=null) callback.complete(id(id),agreement,failure(result));
    }
    @Override public void onUGCQueryCompleted(SteamUGCQuery query,int returned,int total,boolean cached,SteamResult result) {
        WorkshopPublisher.ItemCallback callback; String expectedId,expectedAccount,title=null,error=null;
        synchronized(queryLock) {
            callback=itemCallback;
            if(callback==null || itemQuery==null || !itemQuery.equals(query))return;
            expectedId=queriedItemId; expectedAccount=queriedAccountId;
            try {
                SteamUGCDetails details=new SteamUGCDetails();
                if(result!=SteamResult.OK || returned!=1 || !ugc.getQueryUGCResult(query,0,details) || details.getResult()!=SteamResult.OK)
                    error="Steam could not inspect that Workshop item";
                else if(!expectedId.equals(id(details.getPublishedFileID())) || details.getOwnerID()==null
                        || !expectedAccount.equals(Long.toUnsignedString(SteamNativeHandle.getNativeHandle(details.getOwnerID()))))
                    error="This Workshop item is not owned by the current Steam account";
                else title=details.getTitle();
            } catch(RuntimeException ex) { error="Steam could not inspect that Workshop item"; }
            finally { ugc.releaseQueryUserUGCRequest(query); itemCallback=null; itemQuery=null; }
        }
        callback.complete(expectedId,title,error);
    }
    @Override public void onSubscribeItem(SteamPublishedFileID id,SteamResult result) { }
    @Override public void onUnsubscribeItem(SteamPublishedFileID id,SteamResult result) { }
    @Override public void onRequestUGCDetails(SteamUGCDetails details,SteamResult result) { }
    @Override public void onDownloadItemResult(int appId,SteamPublishedFileID id,SteamResult result) { }
    @Override public void onUserFavoriteItemsListChanged(SteamPublishedFileID id,boolean added,SteamResult result) { }
    @Override public void onSetUserItemVote(SteamPublishedFileID id,boolean voteUp,SteamResult result) { }
    @Override public void onGetUserItemVote(SteamPublishedFileID id,boolean votedUp,boolean votedDown,boolean skipped,SteamResult result) { }
    @Override public void onStartPlaytimeTracking(SteamResult result) { }
    @Override public void onStopPlaytimeTracking(SteamResult result) { }
    @Override public void onStopPlaytimeTrackingForAllItems(SteamResult result) { }
    @Override public void onDeleteItem(SteamPublishedFileID id,SteamResult result) { }
}
