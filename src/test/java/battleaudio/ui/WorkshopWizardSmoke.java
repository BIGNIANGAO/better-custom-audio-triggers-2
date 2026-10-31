package battleaudio.ui;

import battleaudio.model.Config;
import battleaudio.storage.WorkshopCover;
import battleaudio.storage.WorkshopExport;
import battleaudio.storage.WorkshopPackageCatalog;
import battleaudio.storage.WorkshopPublicationRegistry;
import battleaudio.workshop.WorkshopPublisher;
import battleaudio.workshop.SteamWorkshopGateway;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.ProgressBar;
import com.badlogic.gdx.scenes.scene2d.ui.TextArea;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.ScreenUtils;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Exercises the player wizard against a fake Steam gateway; no Workshop calls are made. */
final class WorkshopWizardSmoke {
    private static boolean steamReadDone,nativeReadDone;
    private WorkshopWizardSmoke() { }
    private static final class Fake implements WorkshopPublisher.Gateway {
        int creates,submits,verifies,dependencies; String lastId,lastCoverHash,dependencyParent,dependencyChild;
        WorkshopPublisher.Request lastRequest; WorkshopPublisher.Callback created,submitted,dependency;
        @Override public String accountId() { return "76561198000000001"; }
        @Override public void create(WorkshopPublisher.Callback callback) { creates++; created=callback; }
        @Override public void submit(String id,WorkshopPublisher.Request request,WorkshopExport.Staged staged,WorkshopPublisher.Callback callback) {
            submits++; lastId=id; lastRequest=request; submitted=callback;
            if(!Files.isRegularFile(staged.cover) || !Files.isRegularFile(staged.content.resolve("music-pack.json"))) throw new AssertionError("Missing staged files");
            try { lastCoverHash=WorkshopExport.sha256(staged.cover); }
            catch(java.io.IOException ex) { throw new AssertionError("Cannot read staged cover",ex); }
        }
        @Override public void verifyOwner(String itemId,String accountId,WorkshopPublisher.ItemCallback callback) {
            verifies++; callback.complete(itemId,"Previously published pack",null);
        }
        @Override public void ensureDependencySupport() { }
        @Override public void addDependency(String itemId,String required,WorkshopPublisher.Callback callback) {
            dependencies++; dependencyParent=itemId; dependencyChild=required; dependency=callback;
        }
        @Override public WorkshopPublisher.Progress progress() { return new WorkshopPublisher.Progress("UploadingContent",3,10); }
        @Override public void close() { }
    }
    static void run(SettingsScreen screen,Stage stage,Skin skin) throws Exception {
        if(stage.getRoot().findActor("open-workshop-publisher")==null)throw new AssertionError("Publish entry missing");
        click(screen,stage,stage.getRoot().findActor("open-workshop-publisher"));
        WorkshopPublishWizard real=(WorkshopPublishWizard)field(screen,"workshopWizard");
        if(real==null || ((Table)field(screen,"root")).isVisible())throw new AssertionError("Publish entry did not open the wizard");
        long readyDeadline=System.currentTimeMillis()+10000;
        while((Boolean)field(real,"working") && System.currentTimeMillis()<readyDeadline) { Thread.sleep(10); screen.update(); }
        if((Boolean)field(real,"working"))throw new AssertionError("Publish entry did not finish scanning exports");
        click(screen,stage,stage.getRoot().findActor("workshop-close"));
        if(field(screen,"workshopWizard")!=null || !((Table)field(screen,"root")).isVisible())
            throw new AssertionError("Closing the wizard did not return to settings");
        if(com.megacrit.cardcrawl.core.CardCrawlGame.publisherIntegration!=null
                && com.megacrit.cardcrawl.core.CardCrawlGame.publisherIntegration.getType()==com.megacrit.cardcrawl.integrations.DistributorFactory.Distributor.STEAM
                && com.megacrit.cardcrawl.core.CardCrawlGame.publisherIntegration.isInitialized()
                && com.codedisaster.steamworks.SteamAPI.isSteamRunning()) {
            SteamWorkshopGateway actual=new SteamWorkshopGateway();
            try {
                String account=actual.accountId();
                if(!account.matches("[0-9]{1,20}"))throw new AssertionError("Steam account unavailable");
                actual.ensureDependencySupport();
                if(!nativeReadDone) { battleaudio.workshop.SteamDependencyNativeSmoke.run(); nativeReadDone=true; }
                if(Boolean.getBoolean("battleaudio.inspectRequiredItem") && !steamReadDone) {
                    CountDownLatch done=new CountDownLatch(1); String[] required=new String[2];
                    actual.verifyOwner(WorkshopPublisher.Request.REQUIRED_ITEM_ID,account,(id,title,error) -> {
                        required[0]=title; required[1]=error; done.countDown();
                    });
                    if(!done.await(15,TimeUnit.SECONDS))throw new AssertionError("Required-item read-only query timed out");
                    System.out.println("READ-ONLY required item: "+(required[1]==null?"owned and visible":required[1]));
                    steamReadDone=true;
                }
                if(Boolean.getBoolean("battleaudio.verifySteamRead") && !steamReadDone) {
                    CountDownLatch complete=new CountDownLatch(1); String[] result=new String[2];
                    actual.verifyOwner("1605833019",account,(id,title,error) -> { result[0]=title; result[1]=error; complete.countDown(); });
                    if(!complete.await(15,TimeUnit.SECONDS))throw new AssertionError("Steam read-only item query timed out");
                    if(result[1]!=null && !result[1].equals("This Workshop item is not owned by the current Steam account"))
                        throw new AssertionError("Steam read-only item query failed: "+result[1]);
                    steamReadDone=true;
                    System.out.println("PASS: Steam returned a read-only Workshop ownership result without publishing");
                }
            }
            finally { actual.close(); }
            System.out.println("PASS: Workshop gateway connects to the existing game Steam session without publishing");
        } else System.out.println("SKIP: live Steam gateway availability check (Steam not connected)");
        Path root=Files.createTempDirectory("battleaudio-wizard-ui-"); createExport(root);
        ExecutorService worker=Executors.newSingleThreadExecutor(); Queue<Runnable> queue=new ConcurrentLinkedQueue<>();
        Fake steam=new Fake();
        try {
            runFlow(screen,stage,skin,root,worker,queue,steam,false,false,false,false);
            runFlow(screen,stage,skin,root,worker,queue,steam,true,false,false,false);
            Path existingRoot=Files.createTempDirectory("battleaudio-wizard-existing-"); createExport(existingRoot);
            runFlow(screen,stage,skin,existingRoot,worker,queue,steam,true,true,false,false);
            Path pendingRoot=Files.createTempDirectory("battleaudio-wizard-pending-"); createExport(pendingRoot);
            new WorkshopPublicationRegistry(pendingRoot).beginCreation(steam.accountId(),"ui-pack");
            runFlow(screen,stage,skin,pendingRoot,worker,queue,steam,true,true,true,false);
            Path clearedRoot=Files.createTempDirectory("battleaudio-wizard-cleared-"); createExport(clearedRoot);
            new WorkshopPublicationRegistry(clearedRoot).beginCreation(steam.accountId(),"ui-pack");
            runFlow(screen,stage,skin,clearedRoot,worker,queue,steam,false,false,true,true);
            if(steam.creates!=2 || steam.submits!=5 || steam.verifies!=2 || steam.dependencies!=5 || !"765432109".equals(steam.lastId))
                throw new AssertionError("Wizard update created a duplicate or used a different item");
            System.out.println("PASS: in-game Workshop wizard preview, agreement, create and update using fake Steam");
        } finally { worker.shutdownNow(); }
    }
    private static void runFlow(SettingsScreen screen,Stage stage,Skin skin,Path root,ExecutorService worker,
            Queue<Runnable> queue,Fake steam,boolean update,boolean linkExisting,boolean pending,boolean clearPending) throws Exception {
        final boolean[] closed={false};
        final Path[] opened={null};
        final Path[] coverSource={null};
        WorkshopPublishWizard wizard=new WorkshopPublishWizard(stage,skin,root,worker,queue::add,() -> closed[0]=true,steam,
                path -> opened[0]=path,title -> coverSource[0]);
        try {
            waitFor(screen,stage,wizard,queue,() -> stage.getRoot().findActor("workshop-export-0")!=null);
            click(screen,stage,stage.getRoot().findActor("workshop-export-0"));
            click(screen,stage,stage.getRoot().findActor("workshop-open-folder"));
            waitFor(screen,stage,wizard,queue,() -> { try { return opened[0]!=null && !(Boolean)field(wizard,"working"); } catch(Exception ex) { return false; } });
            if(!opened[0].equals(root.resolve("workshop-exports/ui-pack/export").toAbsolutePath().normalize()))
                throw new AssertionError("Open folder used the wrong export");
            click(screen,stage,stage.getRoot().findActor("workshop-next"));
            waitFor(screen,stage,wizard,queue,() -> { try { return stage.getRoot().findActor("workshop-title")!=null
                    && field(wizard,"cover")!=null && !(Boolean)field(wizard,"working"); } catch(Exception ex) { return false; } });
            TextField title=stage.getRoot().findActor("workshop-title");
            if(!title.getText().equals("UI pack - Better Custom Audio Triggers 2.0"))throw new AssertionError("Default title missing");
            title.setText("My custom Workshop title");
            TextArea music=stage.getRoot().findActor("workshop-editable-description");
            if(!music.getText().contains("Battle rule") || music.getText().contains("Track 0"))
                throw new AssertionError("Automatic music list must contain rule names only");
            if(stage.getRoot().findActor("workshop-fixed-description")!=null
                    || stage.getRoot().findActor("workshop-fixed-scroll")!=null)
                throw new AssertionError("Fixed description is still displayed separately");
            if(stage.getRoot().findActor("workshop-editable-description-scroll")==null)
                throw new AssertionError("Editable description scroll area is missing");
            screen.render((SpriteBatch)stage.getBatch());
            TextButton defaultCover=stage.getRoot().findActor("workshop-default-cover");
            TextButton customCover=stage.getRoot().findActor("workshop-custom-cover");
            if(defaultCover==null || customCover==null)throw new AssertionError("Cover controls are missing");
            assertButtonTextFits(defaultCover); assertButtonTextFits(customCover);
            if(customCover.getLabel().getFontScaleX()<.65f)throw new AssertionError("Localized cover selection text is too small");
            Vector2 defaultRight=defaultCover.localToStageCoordinates(new Vector2(defaultCover.getWidth(),0));
            Vector2 customLeft=customCover.localToStageCoordinates(new Vector2(0,0));
            Vector2 customRight=customCover.localToStageCoordinates(new Vector2(customCover.getWidth(),0));
            if(defaultRight.x>customLeft.x || customRight.x>stage.getViewport().getWorldWidth())
                throw new AssertionError("Cover controls overlap or leave the viewport");
            screenshot("workshop-edit.png");
            StringBuilder longList=new StringBuilder();
            for(int i=0;i<80;i++)longList.append("Rule ").append(i).append('\n');
            music.setText(longList.toString());
            com.badlogic.gdx.scenes.scene2d.ui.ScrollPane descriptionScroll=stage.getRoot().findActor("workshop-editable-description-scroll");
            for(int frame=0;frame<3;frame++){screen.render((SpriteBatch)stage.getBatch()); stage.act(0);}
            if(!descriptionScroll.isScrollY() || descriptionScroll.getMaxY()<=0)
                throw new AssertionError("Long editable description has no usable vertical scrollbar");
            descriptionScroll.setScrollPercentY(1); descriptionScroll.updateVisualScroll();
            if(descriptionScroll.getScrollY()<descriptionScroll.getMaxY()-1)
                throw new AssertionError("Editable description cannot scroll to the end");
            descriptionScroll.setScrollPercentY(.5f); descriptionScroll.updateVisualScroll();
            com.badlogic.gdx.scenes.scene2d.ui.ScrollPane outer=(com.badlogic.gdx.scenes.scene2d.ui.ScrollPane)field(wizard,"contentScroll");
            float outerBefore=outer.getScrollY(), innerBefore=descriptionScroll.getScrollY();
            stage.setScrollFocus(descriptionScroll); stage.scrolled(1);
            if(descriptionScroll.getScrollY()<=innerBefore || outer.getScrollY()!=outerBefore)
                throw new AssertionError("Mouse wheel did not scroll only the editable description");
            descriptionScroll.setScrollPercentY(0); descriptionScroll.updateVisualScroll();
            click(screen,stage,music);
            if(stage.getKeyboardFocus()!=music)throw new AssertionError("Description did not receive focus");
            String originalMusic=music.getText(); int insertion=music.getCursorPosition();
            stage.keyTyped('X'); stage.keyTyped('\n');
            String editedMusic=originalMusic.substring(0,insertion)+"X\n"+originalMusic.substring(insertion);
            if(!music.getText().equals(editedMusic) || stage.getKeyboardFocus()!=music)
                throw new AssertionError("Description did not retain typed text and newline");
            music.selectAll(); screen.render((SpriteBatch)stage.getBatch());
            if(!music.getSelection().equals(editedMusic))throw new AssertionError("Description selection lost content");
            click(screen,stage,music);
            stage.keyDown(com.badlogic.gdx.Input.Keys.UP); stage.keyUp(com.badlogic.gdx.Input.Keys.UP);
            stage.keyDown(com.badlogic.gdx.Input.Keys.DOWN); stage.keyUp(com.badlogic.gdx.Input.Keys.DOWN);
            music.setCursorPosition(music.getText().length()); music.setSelection(music.getText().length(),music.getText().length());
            stage.keyTyped('Q');
            for(int frame=0;frame<3;frame++){screen.render((SpriteBatch)stage.getBatch()); stage.act(0);}
            float cursorY=music.getHeight()-music.getStyle().background.getTopHeight()-music.getCursorY();
            Vector2 visibleCursor=music.localToAscendantCoordinates(descriptionScroll,new Vector2(10,cursorY));
            if(visibleCursor.y<0 || visibleCursor.y>descriptionScroll.getHeight())
                throw new AssertionError("Typing at the end did not reveal the cursor");
            screenshot("workshop-description-scroll.png");
            music.setText(longList.toString()+"A");
            for(int frame=0;frame<3;frame++){screen.render((SpriteBatch)stage.getBatch()); stage.act(0);}
            clickFirstLine(screen,stage,music); stage.keyTyped('Z');
            for(int frame=0;frame<3;frame++){screen.render((SpriteBatch)stage.getBatch()); stage.act(0);}
            if(!music.getText().startsWith("Rule 0") || music.getText().indexOf('Z')<0 || music.getFirstLineShowing()!=0 || descriptionScroll.getScrollY()>1)
                throw new AssertionError("Editing the first line after scrolling did not keep its position: prefix="
                        +music.getText().substring(0,Math.min(15,music.getText().length()))+", first="+music.getFirstLineShowing()+", scroll="+descriptionScroll.getScrollY());
            music.setText("\nTrack"); clickFirstLine(screen,stage,music);
            if(music.getCursorPosition()!=0)throw new AssertionError("Blank first description line did not place the cursor");
            stage.keyTyped('X');
            if(!music.getText().equals("X\nTrack"))throw new AssertionError("Blank first description line could not be edited");
            music.setText("\rB"); clickFirstLine(screen,stage,music);
            if(music.getCursorPosition()!=0)throw new AssertionError("CR first description line did not place the cursor");
            StringBuilder wrapped=new StringBuilder(); for(int i=0;i<200;i++)wrapped.append('W');
            wrapped.append("\nLast track"); music.setText(wrapped.toString()); click(screen,stage,music);
            music.selectAll(); screen.render((SpriteBatch)stage.getBatch());
            if(!music.getSelection().equals(wrapped.toString()))throw new AssertionError("Wrapped description selection lost content");
            music.setText("Battle rule"); click(screen,stage,music); music.setCursorPosition(music.getText().length());
            stage.keyTyped('\r'); type(stage,"Personal introduction"); stage.keyTyped('\n'); type(stage,"Second line");
            String edited="Battle rule\nPersonal introduction\nSecond line";
            if(!music.getText().equals(edited))throw new AssertionError("Description lost edited lines");
            com.badlogic.gdx.scenes.scene2d.ui.Label live=stage.getRoot().findActor("workshop-description-preview");
            if(!live.getText().toString().equals(WorkshopPublisher.Request.description(edited)))
                throw new AssertionError("Live preview differs from the fixed and editable description");
            if(update && !linkExisting) {
                if(stage.getRoot().findActor("workshop-custom-cover")==null)throw new AssertionError("Custom cover entry missing");
                Path original=root.resolve("custom-cover.png");
                BufferedImage image=new BufferedImage(160,100,BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D graphics=image.createGraphics();
                try { graphics.setColor(new java.awt.Color(44,135,125)); graphics.fillRect(0,0,160,100); }
                finally { graphics.dispose(); }
                ImageIO.write(image,"png",original.toFile()); image.flush();
                coverSource[0]=original;
                String oldCoverHash=(String)field(wizard,"coverHash");
                click(screen,stage,stage.getRoot().findActor("workshop-custom-cover"));
                waitFor(screen,stage,wizard,queue,() -> { try { return !(Boolean)field(wizard,"working")
                        && !oldCoverHash.equals(field(wizard,"coverHash")); } catch(Exception ex) { return false; } });
                Path custom=(Path)field(wizard,"cover");
                if(custom==null || custom.equals(original) || !Files.exists(original))throw new AssertionError("Custom cover chooser did not preserve the original image");
                if(!((TextField)stage.getRoot().findActor("workshop-title")).getText().equals("My custom Workshop title")
                        || !((TextArea)stage.getRoot().findActor("workshop-editable-description")).getText().equals(edited))
                    throw new AssertionError("Changing the cover discarded edited publication details");
            }
            @SuppressWarnings("unchecked") SelectBox<String> visibility=(SelectBox<String>)stage.getRoot().findActor("workshop-visibility");
            visibility.setSelectedIndex(update?2:1);
            int creates=steam.creates,submits=steam.submits;
            click(screen,stage,stage.getRoot().findActor("workshop-next"));
            WorkshopPublisher.Request preview=(WorkshopPublisher.Request)field(wizard,"request");
            if(!preview.title.equals("My custom Workshop title") || !preview.description.equals(WorkshopPublisher.Request.description(edited))
                    || !preview.description.contains("3830077500") || preview.description.contains("Track 0"))
                throw new AssertionError("Preview omitted or altered publication details");
            click(screen,stage,stage.getRoot().findActor("workshop-back"));
            if(!((TextField)stage.getRoot().findActor("workshop-title")).getText().equals(preview.title)
                    || !((TextArea)stage.getRoot().findActor("workshop-editable-description")).getText().equals(edited))
                throw new AssertionError("Going back discarded publication details");
            click(screen,stage,stage.getRoot().findActor("workshop-next"));
            if(pending) {
                TextButton pendingCreate=stage.getRoot().findActor("workshop-create"), pendingNext=stage.getRoot().findActor("workshop-next");
                if(pendingCreate==null || !pendingCreate.isDisabled() || pendingNext==null || !pendingNext.isDisabled())
                    throw new AssertionError("Pending creation did not block another creation");
                if(clearPending) {
                    click(screen,stage,stage.getRoot().findActor("workshop-check-pending"));
                    if(!new WorkshopPublicationRegistry(root).isCreating(steam.accountId(),"ui-pack"))
                        throw new AssertionError("Pending creation cleared without final confirmation");
                    click(screen,stage,stage.getRoot().findActor("workshop-confirm-clear-pending"));
                    if(new WorkshopPublicationRegistry(root).isCreating(steam.accountId(),"ui-pack"))
                        throw new AssertionError("Confirmed pending state was not cleared");
                }
            }
            if(linkExisting) {
                TextField item=stage.getRoot().findActor("workshop-existing-id");
                if(item==null)throw new AssertionError("Existing item link input missing");
                screen.render((SpriteBatch)stage.getBatch()); screenshot("workshop-action-unlinked.png");
                item.setText("https://steamcommunity.com/sharedfiles/filedetails/?id=123456789");
                click(screen,stage,stage.getRoot().findActor("workshop-verify-item"));
                waitFor(screen,stage,wizard,queue,() -> stage.getRoot().findActor("workshop-link-item")!=null);
                click(screen,stage,stage.getRoot().findActor("workshop-link-item"));
            }
            TextButton create=stage.getRoot().findActor("workshop-create"), change=stage.getRoot().findActor("workshop-update");
            if(create==null || change==null || create.isDisabled()!=update || change.isDisabled()==update)
                throw new AssertionError("Create/update selection did not match the saved association");
            screen.render((SpriteBatch)stage.getBatch());
            assertButtonTextFits(create); assertButtonTextFits(change);
            screenshot(linkExisting?"workshop-action-linked.png":"workshop-action.png");
            click(screen,stage,stage.getRoot().findActor("workshop-next"));
            screen.render((SpriteBatch)stage.getBatch()); screenshot("workshop-confirmation.png");
            com.badlogic.gdx.scenes.scene2d.ui.Label confirmed=stage.getRoot().findActor("workshop-confirm-description");
            if(confirmed==null || !confirmed.getText().toString().equals(preview.description))throw new AssertionError("Confirmation description differs from preview");
            if(steam.creates!=creates || steam.submits!=submits)throw new AssertionError("Preview contacted Steam before confirmation");
            click(screen,stage,stage.getRoot().findActor("workshop-submit"));
            waitFor(screen,stage,wizard,queue,() -> update ? steam.submits>submits : steam.creates>creates);
            if(!update) {
                steam.created.complete("765432109",true,null); wizard.update();
                if(stage.getRoot().findActor("workshop-open-agreement")==null || steam.submits!=submits)
                    throw new AssertionError("Steam agreement was not shown before upload");
                click(screen,stage,stage.getRoot().findActor("workshop-continue")); wizard.update();
            }
            String expectedId=linkExisting?"123456789":"765432109";
            if(steam.submits!=submits+1 || !expectedId.equals(steam.lastId))throw new AssertionError("Upload used the wrong item");
            if(!Objects.equals(field(wizard,"coverHash"),steam.lastCoverHash))throw new AssertionError("Uploaded cover differs from preview");
            wizard.update(); ProgressBar progress=stage.getRoot().findActor("workshop-progress-bar");
            if(progress==null || !progress.isVisible() || Math.abs(progress.getValue()-30)>1)
                throw new AssertionError("Upload progress is not displayed");
            screen.render((SpriteBatch)stage.getBatch()); screenshot("workshop-progress.png");
            if(steam.lastRequest.visibility!=(update?WorkshopPublisher.Visibility.PUBLIC:WorkshopPublisher.Visibility.FRIENDS)
                    || !steam.lastRequest.title.equals(preview.title) || !steam.lastRequest.description.equals(preview.description))
                throw new AssertionError("Upload did not match the previewed title, description or selected visibility");
            steam.submitted.complete(expectedId,false,null); wizard.update();
            if(stage.getRoot().findActor("workshop-finish")!=null || !expectedId.equals(steam.dependencyParent)
                    || !"3830077500".equals(steam.dependencyChild))throw new AssertionError("Upload did not wait for the required mod");
            steam.dependency.complete(expectedId,false,null); wizard.update();
            if(stage.getRoot().findActor("workshop-finish")==null)throw new AssertionError("Success state missing");
            click(screen,stage,stage.getRoot().findActor("workshop-finish"));
            if(!closed[0])throw new AssertionError("Wizard did not close");
        } finally { wizard.close(); }
    }
    private static Object field(Object target,String name) throws Exception {
        java.lang.reflect.Field field=target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void assertButtonTextFits(TextButton button) {
        button.validate();
        com.badlogic.gdx.graphics.g2d.BitmapFont font=button.getStyle().font;
        float x=font.getData().scaleX,y=font.getData().scaleY;
        try {
            font.getData().setScale(x*button.getLabel().getFontScaleX(),y*button.getLabel().getFontScaleY());
            float width=new com.badlogic.gdx.graphics.g2d.GlyphLayout(font,button.getText()).width;
            if(width>button.getWidth()-button.getPadLeft()-button.getPadRight()+1)
                throw new AssertionError("Wizard action text overflows: "+button.getName());
        } finally { font.getData().setScale(x,y); }
    }
    private static void waitFor(SettingsScreen screen,Stage stage,WorkshopPublishWizard wizard,Queue<Runnable> queue,BooleanSupplier condition) throws Exception {
        long deadline=System.currentTimeMillis()+10000;
        while(!condition.getAsBoolean() && System.currentTimeMillis()<deadline) {
            Runnable task; while((task=queue.poll())!=null)task.run(); wizard.update(); stage.act(.01f);
            screen.render((SpriteBatch)stage.getBatch()); Thread.sleep(10);
        }
        if(!condition.getAsBoolean())throw new AssertionError("Wizard step timed out");
    }
    private static void click(SettingsScreen screen,Stage stage,Actor actor) {
        if(actor==null)throw new AssertionError("Wizard control missing");
        for(int frame=0;frame<3;frame++){screen.render((SpriteBatch)stage.getBatch()); stage.act(0);}
        clickAt(screen,stage,actor,actor.getWidth()/2,actor.getHeight()/2);
    }
    private static void clickAt(SettingsScreen screen,Stage stage,Actor actor,float localX,float localY) {
        if(actor==null)throw new AssertionError("Wizard control missing"); screen.render((SpriteBatch)stage.getBatch()); stage.act(0);
        for(Actor parent=actor.getParent();parent!=null;parent=parent.getParent()) {
            if(parent instanceof com.badlogic.gdx.scenes.scene2d.ui.ScrollPane) {
                com.badlogic.gdx.scenes.scene2d.ui.ScrollPane pane=(com.badlogic.gdx.scenes.scene2d.ui.ScrollPane)parent;
                Vector2 location=actor==pane.getWidget()?new Vector2(localX,localY)
                        :actor.localToAscendantCoordinates(pane.getWidget(),new Vector2(localX,localY));
                pane.scrollTo(location.x,location.y,1,1,true,true); pane.updateVisualScroll();
                screen.render((SpriteBatch)stage.getBatch()); stage.act(0);
            }
        }
        if(actor instanceof TextField)
            ((com.badlogic.gdx.scenes.scene2d.utils.ClickListener)((TextField)actor).getDefaultInputListener()).setTapCount(0);
        Vector2 point=stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(localX,localY)));
        point.y+=Gdx.graphics.getHeight()-stage.getViewport().getScreenHeight();
        int x=Math.round(point.x),y=Math.round(point.y);
        if(!stage.touchDown(x,y,0,0))throw new AssertionError("Wizard click not received: "+actor.getName());
        stage.touchUp(x,y,0,0); stage.act(0);
    }
    private static void type(Stage stage,String text) {
        for(int i=0;i<text.length();i++)stage.keyTyped(text.charAt(i));
    }
    private static void clickFirstLine(SettingsScreen screen,Stage stage,TextArea area) {
        for(int frame=0;frame<3;frame++){screen.render((SpriteBatch)stage.getBatch()); stage.act(0);}
        float y=area.getHeight()-area.getStyle().font.getLineHeight()/2;
        clickAt(screen,stage,area,area.getWidth()/2,y);
    }
    private static void screenshot(String name) {
        Pixmap pixels=ScreenUtils.getFrameBufferPixmap(0,0,Gdx.graphics.getWidth(),Gdx.graphics.getHeight());
        PixmapIO.PNG writer=new PixmapIO.PNG(); writer.setFlipY(true);
        try { writer.write(Gdx.files.local(name),pixels); }
        catch(Exception ex) { throw new RuntimeException(ex); }
        finally { writer.dispose(); pixels.dispose(); }
    }
    private static void createExport(Path root) throws Exception {
        Path dir=root.resolve("workshop-exports/ui-pack/export"); Files.createDirectories(dir.resolve("audio"));
        byte[] audio={1,2,3}; Files.write(dir.resolve("audio/song.wav"),audio);
        Config.Rule rule=new Config.Rule(); rule.id="ui-rule"; rule.name="Battle rule"; rule.triggers=EnumSet.of(Config.Trigger.PLAYER_DEATH);
        for(int i=0;i<80;i++) {
            Config.Candidate candidate=new Config.Candidate(); candidate.id="ui-audio-"+i; candidate.name="Track "+i+" - \u6d4b\u8bd5\u97f3\u4e50.wav";
            candidate.sourcePath="audio/song.wav"; candidate.cachePath="audio/song.wav"; candidate.duration=1; candidate.end=1; rule.candidates.add(candidate);
        }
        WorkshopPackageCatalog.MediaFile file=new WorkshopPackageCatalog.MediaFile(); file.path="audio/song.wav"; file.bytes=audio.length;
        StringBuilder hash=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(audio))hash.append(String.format(Locale.ROOT,"%02x",b&255)); file.sha256=hash.toString();
        WorkshopPackageCatalog.Bundle bundle=new WorkshopPackageCatalog.Bundle(); bundle.schemaVersion=1; bundle.packageId="ui-pack";
        bundle.workshopId=""; bundle.name="UI pack"; bundle.version="1"; bundle.rules=new ArrayList<>(Collections.singletonList(rule));
        bundle.files=new ArrayList<>(Collections.singletonList(file));
        Files.write(dir.resolve("music-pack.json"),new Gson().toJson(bundle).getBytes(StandardCharsets.UTF_8));
    }
}
