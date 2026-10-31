package battleaudio.workshop;

import battleaudio.model.Config;
import battleaudio.storage.WorkshopCover;
import battleaudio.storage.WorkshopExport;
import battleaudio.storage.WorkshopPackageCatalog;
import battleaudio.storage.WorkshopPublicationRegistry;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public final class WorkshopPublishingTests {
    private static int checks;
    private static void check(boolean value,String message) { checks++; if(!value) throw new AssertionError(message); }
    private static final class Fake implements WorkshopPublisher.Gateway {
        int creates,submits,cancelled,dependencies; boolean deferVerification,dependencyUnavailable; String submittedId,dependencyParent,dependencyChild;
        WorkshopPublisher.Callback createCallback,submitCallback,dependencyCallback;
        WorkshopPublisher.ItemCallback verificationCallback;
        @Override public String accountId() { return "76561198000000001"; }
        @Override public void create(WorkshopPublisher.Callback callback) { creates++; createCallback=callback; }
        @Override public void submit(String id,WorkshopPublisher.Request request,WorkshopExport.Staged staged,WorkshopPublisher.Callback callback) {
            submits++; submittedId=id; submitCallback=callback;
            if(!Files.isRegularFile(staged.content.resolve("music-pack.json")) || !Files.isRegularFile(staged.cover)) throw new AssertionError("Staging missing");
        }
        @Override public void ensureDependencySupport() throws Exception { if(dependencyUnavailable)throw new java.io.IOException("unavailable"); }
        @Override public void addDependency(String id,String required,WorkshopPublisher.Callback callback) {
            dependencies++; dependencyParent=id; dependencyChild=required; dependencyCallback=callback;
        }
        @Override public void verifyOwner(String itemId,String accountId,WorkshopPublisher.ItemCallback callback) {
            if(deferVerification)verificationCallback=callback; else callback.complete(itemId,"Owned pack",null);
        }
        @Override public void cancelVerification() { cancelled++; }
        @Override public WorkshopPublisher.Progress progress() { return new WorkshopPublisher.Progress("UploadingContent",5,10); }
        @Override public void close() { }
    }
    public static void run() throws Exception {
        com.megacrit.cardcrawl.core.Settings.GameLanguage prior=com.megacrit.cardcrawl.core.Settings.language;
        try {
            com.megacrit.cardcrawl.core.Settings.language=com.megacrit.cardcrawl.core.Settings.GameLanguage.ZHS;
            check("发布音乐包".equals(battleaudio.localization.I18n.workshopText("Publish music pack")),"Wizard uses Simplified Chinese localization");
            com.megacrit.cardcrawl.core.Settings.language=com.megacrit.cardcrawl.core.Settings.GameLanguage.FRA;
            check(!"Publish music pack".equals(battleaudio.localization.I18n.workshopText("Publish music pack")),"Wizard uses the selected game's French localization");
        } finally { com.megacrit.cardcrawl.core.Settings.language=prior; }
        Path root=Files.createTempDirectory("battleaudio-publisher-test-");
        Path export=root.resolve("workshop-exports/pack-id/snapshot"); Files.createDirectories(export.resolve("audio"));
        byte[] audio={1,2,3,4,5}; Files.write(export.resolve("audio/song.wav"),audio);
        Config.Rule rule=new Config.Rule(); rule.id="fixture-rule"; rule.name="Victory";
        rule.triggers=EnumSet.of(Config.Trigger.PLAYER_DEATH);
        Config.Candidate candidate=new Config.Candidate(); candidate.name="song.wav"; candidate.id="fixture-audio";
        candidate.sourcePath="audio/song.wav"; candidate.cachePath="audio/song.wav"; candidate.duration=1; candidate.end=1;
        rule.candidates.add(candidate);
        WorkshopPackageCatalog.Bundle bundle=new WorkshopPackageCatalog.Bundle(); bundle.schemaVersion=1;
        bundle.packageId="pack-id"; bundle.workshopId=""; bundle.name="Fixture"; bundle.version="1";
        bundle.rules=new ArrayList<>(Collections.singletonList(rule));
        WorkshopPackageCatalog.MediaFile media=new WorkshopPackageCatalog.MediaFile(); media.path="audio/song.wav";
        media.bytes=audio.length; media.sha256=hash(audio); bundle.files=new ArrayList<>(Collections.singletonList(media));
        Files.write(export.resolve("music-pack.json"),new Gson().toJson(bundle).getBytes(StandardCharsets.UTF_8));
        check(WorkshopExport.findExports(root).contains(export),"Export discovery finds snapshots");
        WorkshopExport.Snapshot snapshot=WorkshopExport.inspect(export);
        check(snapshot.packageId.equals("pack-id") && snapshot.contents.contains("  song.wav") && snapshot.ruleNames.equals(Collections.singletonList("Victory"))
                && snapshot.bytes==audio.length,"Export distinguishes rule names from candidate names");
        Path cover=WorkshopCover.createDefault(); check(Files.size(cover)>0 && Files.size(cover)<1024*1024,"Default cover is a Steam-sized JPEG");
        try(java.io.InputStream resource=WorkshopCover.class.getResourceAsStream("/battleaudio/workshop-default-cover.jpg")) {
            if(resource!=null)check(Arrays.equals(Files.readAllBytes(cover),readAll(resource)),"Default cover matches the bundled player-provided image");
            else check(javax.imageio.ImageIO.read(cover.toFile())!=null,"Source-only builds generate a usable default cover");
        }
        Path custom=WorkshopCover.prepare(cover);
        check(Files.exists(cover) && Files.exists(custom) && !cover.equals(custom),"Custom cover preparation leaves the original image intact");
        String originalCoverHash=WorkshopExport.sha256(custom); Files.write(custom,new byte[]{1,2,3});
        try { WorkshopExport.stage(snapshot,custom,originalCoverHash); throw new AssertionError("Changed cover accepted"); }
        catch(java.io.IOException expected) { check(true,"Cover changes after preview are rejected"); }
        Files.deleteIfExists(custom);
        WorkshopPublisher.Request request=new WorkshopPublisher.Request("Custom full title","Victory\nExtra",WorkshopPublisher.Visibility.PRIVATE);
        check(WorkshopPublisher.Request.defaultTitle("My Music").equals("My Music - Better Custom Audio Triggers 2.0")
                && request.title.equals("Custom full title") && request.description.equals(WorkshopPublisher.Request.FIXED_DESCRIPTION+"\n\nVictory\nExtra")
                && request.description.contains("3830077500")
                && request.description.contains("\u9002\u7528\u4e8e\u66f4\u597d\u7684\u81ea\u5b9a\u4e49\u97f3\u9891\u89e6\u53d12.0 mod\u7684\u97f3\u4e50\u5305")
                && request.description.contains("Music pack for Better Custom Audio Triggers 2.0 mod")
                && !request.description.contains("3817408863"),"Full title and fixed plus editable description are exact");
        check(new WorkshopPublisher.Request("Title","",WorkshopPublisher.Visibility.PUBLIC).description.equals(WorkshopPublisher.Request.FIXED_DESCRIPTION),"Editable description is optional");
        try { new WorkshopPublisher.Request(String.join("",Collections.nCopies(60,"音")),"Song",WorkshopPublisher.Visibility.PRIVATE); throw new AssertionError("Oversized title accepted"); }
        catch(IllegalArgumentException expected) { check(true,"UTF-8 title limit is enforced"); }
        WorkshopPublicationRegistry registry=new WorkshopPublicationRegistry(root);
        Fake fake=new Fake(); WorkshopPublisher publisher=new WorkshopPublisher(fake,registry);
        fake.dependencyUnavailable=true;
        WorkshopExport.Staged unsupported=WorkshopExport.stage(snapshot,cover);
        try { publisher.start(request,snapshot,unsupported,false); throw new AssertionError("Missing dependency API accepted"); }
        catch(java.io.IOException expected) { unsupported.close(); check(fake.creates==0 && !registry.isCreating(fake.accountId(),"pack-id"),"Missing dependency support fails before creation"); }
        fake.dependencyUnavailable=false;
        WorkshopExport.Staged first=WorkshopExport.stage(snapshot,cover);
        check(fake.creates==0 && fake.submits==0,"Preparing preview never publishes");
        publisher.start(request,snapshot,first,false); check(fake.creates==1 && fake.submits==0,"Confirmed new item starts creation once");
        check(new WorkshopPublicationRegistry(root).isCreating(fake.accountId(),"pack-id"),"Pending creation survives a restart before Steam returns an ID");
        fake.createCallback.complete("987654321",true,null);
        check(publisher.state()==WorkshopPublisher.State.AGREEMENT && "987654321".equals(registry.find(fake.accountId(),"pack-id"))
                && !registry.isCreating(fake.accountId(),"pack-id"),"Created ID is saved before agreement or upload");
        check("987654321".equals(new WorkshopPublicationRegistry(root).find(fake.accountId(),"pack-id")),"Created ID survives a new registry instance");
        publisher.update(); check(fake.submits==0,"Agreement is not silently bypassed");
        publisher.continueAfterAgreement(); publisher.update();
        check(fake.submits==1 && "987654321".equals(fake.submittedId) && publisher.progress().total==10,"Upload uses saved ID and reports progress");
        fake.submitCallback.complete("987654321",false,"Network failure");
        check(publisher.state()==WorkshopPublisher.State.FAILED && !Files.exists(first.directory),"Failure is reported and temporary content is removed");
        check("987654321".equals(registry.find(fake.accountId(),"pack-id")),"Failure retains the created item association");
        WorkshopExport.Staged duplicate=WorkshopExport.stage(snapshot,cover);
        try { publisher.start(request,snapshot,duplicate,false); throw new AssertionError("Duplicate creation accepted"); }
        catch(Exception expected) { duplicate.close(); check(fake.creates==1,"Linked packages cannot accidentally create another item"); }
        WorkshopExport.Staged retry=WorkshopExport.stage(snapshot,cover);
        publisher.start(request,snapshot,retry,true); publisher.update();
        check(fake.submits==2 && fake.creates==1 && fake.submittedId.equals("987654321"),"Update reuses the existing item");
        fake.submitCallback.complete("987654321",true,null);
        check(publisher.state()==WorkshopPublisher.State.AGREEMENT && Files.exists(retry.directory),"An agreement required by submit retains the upload until confirmed");
        publisher.continueAfterAgreement(); publisher.update();
        check(fake.submits==3 && fake.creates==1 && fake.submittedId.equals("987654321"),"Agreement retry uses the same item");
        fake.submitCallback.complete("987654321",false,null);
        check(publisher.state()==WorkshopPublisher.State.ADDING_DEPENDENCY && Files.exists(retry.directory),"Upload waits for the required item before success");
        publisher.update();
        check(fake.dependencies==1 && "987654321".equals(fake.dependencyParent) && "3830077500".equals(fake.dependencyChild),"Update adds the required mod to the existing item");
        fake.dependencyCallback.complete("987654321",false,"Network failure");
        check(publisher.state()==WorkshopPublisher.State.FAILED && !Files.exists(retry.directory)
                && publisher.error().contains("required item") && "987654321".equals(registry.find(fake.accountId(),"pack-id")),"Dependency failure is visible and retains the uploaded item association");
        WorkshopExport.Staged finalRetry=WorkshopExport.stage(snapshot,cover);
        publisher.start(request,snapshot,finalRetry,true); publisher.update();
        fake.submitCallback.complete("987654321",false,null); publisher.update();
        fake.dependencyCallback.complete("987654321",false,null);
        check(publisher.state()==WorkshopPublisher.State.SUCCESS && !Files.exists(finalRetry.directory),"Success requires the dependency callback");
        check(registry.find("76561198000000002","pack-id")==null,"Associations are isolated by Steam account");
        String[] found=new String[2];
        publisher.verifyExisting("https://steamcommunity.com/sharedfiles/filedetails/?id=123456789",fake.accountId(),(id,title,error) -> { found[0]=id; found[1]=title; });
        check("123456789".equals(found[0]) && "Owned pack".equals(found[1]),"Existing item link is resolved and ownership checked");
        publisher.linkExisting("other-pack",found[0],fake.accountId());
        check("123456789".equals(registry.find(fake.accountId(),"other-pack")),"Verified existing item can be linked for updates");
        try { publisher.linkExisting("unverified-pack","999999999",fake.accountId()); throw new AssertionError("Unverified item linked"); }
        catch(java.io.IOException expected) { check(true,"An unverified item cannot be linked"); }
        WorkshopExport.Staged changedAccountStage=WorkshopExport.stage(snapshot,cover);
        try { publisher.start(request,snapshot,changedAccountStage,true,"76561198000000099","987654321"); throw new AssertionError("Changed account accepted"); }
        catch(java.io.IOException expected) { changedAccountStage.close(); check(fake.submits==4,"Account changes require another review before upload"); }
        try { WorkshopPublisher.normalizeItemId("https://steamcommunity.com.evil.example/sharedfiles/filedetails/?id=123"); throw new AssertionError("Unsafe link accepted"); }
        catch(java.io.IOException expected) { check(true,"Only Steam Workshop links are accepted"); }
        Fake pendingSteam=new Fake(); pendingSteam.deferVerification=true;
        WorkshopPublisher pendingPublisher=new WorkshopPublisher(pendingSteam,new WorkshopPublicationRegistry(root));
        boolean[] lateCalled={false}; pendingPublisher.verifyExisting("321",pendingSteam.accountId(),(id,title,error) -> lateCalled[0]=true);
        pendingPublisher.cancelVerification(); pendingSteam.verificationCallback.complete("321","Late result",null);
        check(pendingSteam.cancelled==1 && !lateCalled[0],"Cancelled verification ignores late callbacks");
        try { pendingPublisher.linkExisting("unverified-after-cancel","321",pendingSteam.accountId()); throw new AssertionError("Cancelled verification linked"); }
        catch(java.io.IOException expected) { check(true,"Cancelled verification cannot authorize a link"); }
        pendingPublisher.close();
        Path failureRoot=Files.createTempDirectory("battleaudio-publication-registry-failure-");
        WorkshopPublicationRegistry failureRegistry=new WorkshopPublicationRegistry(failureRoot);
        Fake failureSteam=new Fake(); WorkshopPublisher failurePublisher=new WorkshopPublisher(failureSteam,failureRegistry);
        WorkshopExport.Staged failureStage=WorkshopExport.stage(snapshot,cover);
        failurePublisher.start(request,snapshot,failureStage,false);
        Path blockedRegistry=failureRoot.resolve("workshop-publications.json"); byte[] pendingRecord=Files.readAllBytes(blockedRegistry);
        Files.delete(blockedRegistry); Files.createDirectory(blockedRegistry);
        failureSteam.createCallback.complete("555555555",false,null);
        check(failurePublisher.state()==WorkshopPublisher.State.FAILED && failurePublisher.hasUnsavedItem()
                && "555555555".equals(failurePublisher.itemId()),"A failed link save retains and reports the created ID");
        failurePublisher.reset(); check(failurePublisher.state()==WorkshopPublisher.State.FAILED,"An unsaved item cannot be silently reset into a new creation");
        Files.delete(blockedRegistry); Files.write(blockedRegistry,pendingRecord); failurePublisher.retryAssociation();
        check(!failurePublisher.hasUnsavedItem() && "555555555".equals(failureRegistry.find(failureSteam.accountId(),"pack-id")),"Saving a created item link can be retried");
        failurePublisher.reset(); failurePublisher.close();
        Path interruptedRoot=Files.createTempDirectory("battleaudio-publication-pending-");
        WorkshopPublicationRegistry interruptedRegistry=new WorkshopPublicationRegistry(interruptedRoot);
        Fake interruptedSteam=new Fake(); WorkshopPublisher interrupted=new WorkshopPublisher(interruptedSteam,interruptedRegistry);
        WorkshopExport.Staged interruptedStage=WorkshopExport.stage(snapshot,cover);
        interrupted.start(request,snapshot,interruptedStage,false);
        interruptedSteam.createCallback.complete(null,false,"Connection lost");
        WorkshopPublisher reopened=new WorkshopPublisher(new Fake(),new WorkshopPublicationRegistry(interruptedRoot));
        WorkshopExport.Staged duplicatePending=WorkshopExport.stage(snapshot,cover);
        try { reopened.start(request,snapshot,duplicatePending,false); throw new AssertionError("Pending creation duplicated"); }
        catch(java.io.IOException expected) { duplicatePending.close(); check(reopened.creationPending("pack-id"),"A pending creation blocks duplicates after restart"); }
        reopened.clearPendingCreation("pack-id",interruptedSteam.accountId());
        check(!reopened.creationPending("pack-id"),"A checked pending state can be cleared explicitly");
        interrupted.close(); reopened.close();
        Path invalidRoot=Files.createTempDirectory("battleaudio-publication-registry-invalid-");
        Path invalid=invalidRoot.resolve("workshop-publications.json"); byte[] invalidBytes="{broken".getBytes(StandardCharsets.UTF_8); Files.write(invalid,invalidBytes);
        try { new WorkshopPublicationRegistry(invalidRoot).find(fake.accountId(),"pack-id"); throw new AssertionError("Invalid registry accepted"); }
        catch(java.io.IOException expected) { check(Arrays.equals(invalidBytes,Files.readAllBytes(invalid)),"Invalid registry is not overwritten"); }
        Files.write(export.resolve("extra.txt"),audio);
        try { WorkshopExport.inspect(export); throw new AssertionError("Unexpected file accepted"); }
        catch(java.io.IOException expected) { check(true,"Unexpected files cannot be uploaded"); }
        Files.delete(export.resolve("extra.txt")); Files.write(export.resolve("audio/song.wav"),new byte[]{9,9,9,9,9});
        try { WorkshopExport.stage(snapshot,cover); throw new AssertionError("Changed audio accepted"); }
        catch(java.io.IOException expected) { check(true,"Changed export is rejected before upload"); }
        Files.deleteIfExists(cover); publisher.close();
        System.out.println("PASS: "+checks+" Workshop publication and recovery checks (no Steam upload)");
    }
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder out=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format(Locale.ROOT,"%02x",b&255)); return out.toString();
    }
    private static byte[] readAll(java.io.InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream(); byte[] buffer=new byte[4096]; int count;
        while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
        return output.toByteArray();
    }
}
