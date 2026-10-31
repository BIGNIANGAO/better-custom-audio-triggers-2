package battleaudio.ui;

import battleaudio.localization.I18n;
import battleaudio.storage.WorkshopCover;
import battleaudio.storage.WorkshopExport;
import battleaudio.storage.WorkshopPackageCatalog;
import battleaudio.storage.WorkshopPublicationRegistry;
import battleaudio.workshop.SteamWorkshopGateway;
import battleaudio.workshop.WorkshopPublisher;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import java.awt.EventQueue;
import java.awt.Desktop;
import java.io.IOException;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Player-facing publication flow. Steam is contacted only after the final confirmation. */
final class WorkshopPublishWizard {
    private static final String AGREEMENT_URL="https://steamcommunity.com/sharedfiles/workshoplegalagreement";
    private final Stage stage;
    private final Skin skin;
    private final Path dataRoot;
    private final ExecutorService worker;
    private final Consumer<Runnable> post;
    private final Runnable onClose;
    private final WorkshopPublisher publisher;
    private final FolderOpener folderOpener;
    private final CoverChooser coverChooser;
    private final Table root=new Table(), content=new Table(), actions=new Table();
    private final Label heading, message;
    private final ScrollPane contentScroll;
    private final List<ExportChoice> exports=new ArrayList<>();
    private boolean active=true, working, updateMode, verifying, pendingCreation, confirmClearPending;
    private long verificationStarted, verificationGeneration;
    private int step;
    private Path selectedPath, cover;
    private Texture coverTexture;
    private WorkshopExport.Snapshot snapshot;
    private String titleText="", editableDescription="", accountId, linkedItem, coverHash, error="";
    private String existingInput="", verifiedItem, verifiedTitle;
    private TextButton existingLinkButton;
    private Label existingVerifiedLabel;
    private WorkshopPublisher.Visibility visibility=WorkshopPublisher.Visibility.PRIVATE;
    private WorkshopPublisher.Request request;
    private WorkshopPublisher.State shownState;
    private Label progressLabel;
    private ProgressBar progressBar;

    private static final class ExportChoice {
        final Path path; final String label;
        ExportChoice(Path path,String label) { this.path=path; this.label=label; }
    }
    interface FolderOpener { void open(Path path) throws Exception; }
    interface CoverChooser { Path choose(String title) throws Exception; }

    private static class SafeTextArea extends TextArea {
        SafeTextArea(String text,Skin skin) { super(text,skin); setFocusTraversal(false); }
        @Override protected int letterUnderCursor(float x) {
            // libGDX 1.9.5 reads index -1 when the first line is empty.
            if(getCursorLine()==0 && !text.isEmpty() && (text.charAt(0)=='\n' || text.charAt(0)=='\r'))return 0;
            return super.letterUnderCursor(x);
        }
    }
    private static final class GrowingTextArea extends SafeTextArea {
        private int measuredLines=1;
        private ScrollPane scrollPane;
        private boolean revealCaret;
        GrowingTextArea(String text,Skin skin) { super(text,skin); }
        void connectScroll(ScrollPane pane) {
            scrollPane=pane;
            addListener(new com.badlogic.gdx.scenes.scene2d.InputListener() {
                @Override public boolean keyDown(com.badlogic.gdx.scenes.scene2d.InputEvent event,int keycode) { revealCaret=true; return false; }
                @Override public boolean keyTyped(com.badlogic.gdx.scenes.scene2d.InputEvent event,char character) { revealCaret=true; return false; }
            });
        }
        @Override public float getPrefHeight() {
            com.badlogic.gdx.scenes.scene2d.utils.Drawable background=getStyle().background;
            float padding=background==null?0:background.getTopHeight()+background.getBottomHeight();
            return Math.max(180,padding+Math.max(1,measuredLines)*getStyle().font.getLineHeight()+2);
        }
        @Override protected void calculateOffsets() {
            super.calculateOffsets();
            int lines=Math.max(1,getLines());
            if(lines!=measuredLines) { measuredLines=lines; invalidateHierarchy(); }
            if(getFirstLineShowing()>0 && getHeight()+1>=getPrefHeight()) {
                int savedCursor=cursor, savedStart=selectionStart; boolean savedSelection=hasSelection;
                moveCursorLine(0);
                setCursorPosition(savedCursor); setSelection(savedCursor,savedCursor);
                selectionStart=savedStart; hasSelection=savedSelection;
            }
        }
        @Override public void draw(com.badlogic.gdx.graphics.g2d.Batch batch,float parentAlpha) {
            super.draw(batch,parentAlpha);
            if(revealCaret && scrollPane!=null && getHeight()+1>=getPrefHeight()) {
                com.badlogic.gdx.scenes.scene2d.utils.Drawable background=getStyle().background;
                float top=background==null?0:background.getTopHeight();
                float lineHeight=getStyle().font.getLineHeight();
                scrollPane.scrollTo(0,getHeight()-top-getCursorY(),1,lineHeight,false,false);
                scrollPane.updateVisualScroll(); revealCaret=false;
            }
        }
    }

    WorkshopPublishWizard(Stage stage,Skin skin,Path dataRoot,ExecutorService worker,Consumer<Runnable> post,Runnable onClose) {
        this(stage,skin,dataRoot,worker,post,onClose,new SteamWorkshopGateway());
    }
    WorkshopPublishWizard(Stage stage,Skin skin,Path dataRoot,ExecutorService worker,Consumer<Runnable> post,Runnable onClose,
            WorkshopPublisher.Gateway gateway) {
        this(stage,skin,dataRoot,worker,post,onClose,gateway,path -> {
            if(!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
                throw new IOException("Opening folders is unavailable");
            Desktop.getDesktop().open(path.toFile());
        }, WorkshopPublishWizard::chooseLocalCover);
    }
    WorkshopPublishWizard(Stage stage,Skin skin,Path dataRoot,ExecutorService worker,Consumer<Runnable> post,Runnable onClose,
            WorkshopPublisher.Gateway gateway,FolderOpener folderOpener) {
        this(stage,skin,dataRoot,worker,post,onClose,gateway,folderOpener,WorkshopPublishWizard::chooseLocalCover);
    }
    WorkshopPublishWizard(Stage stage,Skin skin,Path dataRoot,ExecutorService worker,Consumer<Runnable> post,Runnable onClose,
            WorkshopPublisher.Gateway gateway,FolderOpener folderOpener,CoverChooser coverChooser) {
        this.stage=stage; this.skin=skin; this.dataRoot=dataRoot; this.worker=worker; this.post=post; this.onClose=onClose;
        this.folderOpener=folderOpener;
        this.coverChooser=coverChooser;
        publisher=new WorkshopPublisher(gateway,new WorkshopPublicationRegistry(dataRoot));
        root.setName("workshop-publish-wizard"); root.setFillParent(true); root.pad(30);
        root.setBackground(skin.getDrawable("overlay-background"));
        Table top=new Table(); heading=label(""); heading.setWrap(true); heading.setFontScale(1.2f); heading.setColor(com.megacrit.cardcrawl.core.Settings.GOLD_COLOR);
        top.add(heading).minWidth(0).growX().left();
        top.add(button("Cancel","workshop-close",this::close)).width(150).height(44);
        root.add(top).growX().padBottom(16).row();
        message=label(""); message.setWrap(true); message.setColor(Color.SALMON);
        root.add(message).growX().left().padBottom(8).row();
        content.top().left(); contentScroll=new ScrollPane(content,skin);
        contentScroll.setFadeScrollBars(false); contentScroll.setScrollingDisabled(true,false); contentScroll.setOverscroll(false,false);
        root.add(contentScroll).minWidth(0).grow().row();
        root.add(actions).growX().padTop(14).row();
        scanExports(); stage.addActor(root);
    }
    private static String t(String key) { return I18n.workshopText(key); }
    private Label label(String text) { return new Label(text,skin); }
    private TextButton button(String key,String actorName,Runnable action) {
        TextButton button=new FittedTextButton(t(key),skin,"default"); button.setName(actorName); button.setProgrammaticChangeEvents(false);
        button.addListener(new ChangeListener() { @Override public void changed(ChangeEvent event,Actor actor) {
            button.setChecked(false); if (!working || "workshop-close".equals(actorName)) action.run();
        }});
        return button;
    }
    private Label wrapped(String value) { Label result=label(value); result.setWrap(true); result.setAlignment(Align.topLeft); return result; }
    private void setError(String value) { error=value==null ? "" : value; message.setText(error.isEmpty()?"":t(error)); }
    private void begin() { working=true; setError(""); }
    private void finish() { working=false; }

    private void scanExports() {
        begin(); render();
        worker.submit(() -> {
            List<ExportChoice> found=new ArrayList<>(); Exception failure=null;
            try {
                for (Path path:WorkshopExport.findExports(dataRoot)) {
                    try {
                        WorkshopPackageCatalog.Bundle bundle=WorkshopPackageCatalog.readBundle(path,null);
                        found.add(new ExportChoice(path,bundle.name+"  ·  "+bundle.version+"  ·  "+path.getFileName()));
                    } catch (Exception ignored) { }
                }
            } catch (Exception ex) { failure=ex; }
            final Exception result=failure;
            post.accept(() -> { if(!active)return; finish(); exports.clear(); exports.addAll(found);
                if(result!=null)setError(result.getMessage()); render(); });
        });
    }
    private void selectExport(Path path) {
        if(!path.equals(selectedPath)) {
            snapshot=null;
            if(coverTexture!=null)coverTexture.dispose(); coverTexture=null;
            deleteCover(cover); cover=null; coverHash=null;
        }
        selectedPath=path; render();
    }
    private void openSelectedFolder() {
        if(selectedPath==null)return;
        Path chosen=selectedPath.toAbsolutePath().normalize(), exportsRoot=dataRoot.toAbsolutePath().normalize().resolve("workshop-exports");
        if(!chosen.startsWith(exportsRoot) || Files.isSymbolicLink(chosen) || !Files.isDirectory(chosen,LinkOption.NOFOLLOW_LINKS)) {
            setError("Export folder is unavailable"); return;
        }
        begin(); renderActions();
        worker.submit(() -> { Exception failure=null; try { folderOpener.open(chosen); } catch(Exception ex) { failure=ex; }
            final Exception problem=failure;
            post.accept(() -> { if(!active)return; finish(); if(problem!=null)setError("Cannot open the export folder"); renderActions(); });
        });
    }
    private void inspectExport() {
        if(selectedPath==null) { setError("Choose an export to continue"); return; }
        Path chosen=selectedPath; begin(); message.setText(t("Checking export...")); renderActions();
        worker.submit(() -> {
            WorkshopExport.Snapshot result=null; Exception failure=null;
            try { result=WorkshopExport.inspect(chosen); } catch(Exception ex) { failure=ex; }
            final WorkshopExport.Snapshot inspected=result; final Exception problem=failure;
            post.accept(() -> {
                if(!active)return; finish();
                if(problem!=null) { setError(problem.getMessage()); renderActions(); return; }
                boolean changed=snapshot==null || !snapshot.directory.equals(inspected.directory) || !snapshot.manifestHash.equals(inspected.manifestHash);
                snapshot=inspected;
                if(changed) { titleText=WorkshopPublisher.Request.defaultTitle(snapshot.name); editableDescription=defaultMusicList(snapshot); }
                step=1; setError(""); render(); if(cover==null)defaultCover();
            });
        });
    }
    private static String defaultMusicList(WorkshopExport.Snapshot snapshot) {
        return "Music List / \u97f3\u4e50\u5217\u8868\n"+String.join("\n",snapshot.ruleNames);
    }
    private void render() {
        if(!active)return;
        String[] titles={"Select an exported music pack","Edit and preview publication","Publication action","Confirm publication"};
        heading.setText(t("Workshop publishing")+"   "+(step+1)+" / 4   \u00b7   "+t(titles[step]));
        message.setText(error.isEmpty()?"":t(error));
        content.clear(); content.top().left(); contentScroll.setScrollY(0);
        switch(step) {
            case 0: renderExports(); break;
            case 1: renderDetails(); break;
            case 2: renderAction(); break;
            default: renderConfirmation(); break;
        }
        content.invalidateHierarchy(); renderActions();
    }
    private void renderExports() {
        if(working) { content.add(label(t("Checking export..."))).left().row(); return; }
        if(exports.isEmpty()) content.add(wrapped(t("No exports found. Export a local music pack first."))).minWidth(0).growX().left().row();
        for(ExportChoice choice:exports) {
            TextButton item=new TextButton(choice.label,skin,"choice"); item.setName("workshop-export-"+exports.indexOf(choice));
            item.setProgrammaticChangeEvents(false); item.setChecked(choice.path.equals(selectedPath));
            item.getLabel().setEllipsis(true); item.getLabel().setAlignment(Align.left); item.getLabelCell().minWidth(0).padLeft(12).padRight(12);
            item.addListener(new ChangeListener(){ @Override public void changed(ChangeEvent event,Actor actor) { if(!working)selectExport(choice.path); }});
            content.add(item).minWidth(0).growX().height(44).padBottom(5).row();
        }
        Table commands=new Table(); commands.left();
        commands.add(button("Refresh exports","workshop-refresh",this::scanExports)).width(220).height(42).padRight(10);
        TextButton open=button("Open containing folder","workshop-open-folder",this::openSelectedFolder); open.setDisabled(selectedPath==null);
        commands.add(open).width(240).height(42);
        content.add(commands).left().padTop(10).row();
    }
    private void renderDetails() {
        Table overview=new Table(); overview.top().left();
        if(coverTexture!=null)overview.add(new Image(coverTexture)).size(180).padRight(18).top();
        else overview.add(label(t("No cover selected"))).width(180).height(180).padRight(18).top();
        Table details=new Table(); details.top().left();
        details.add(label(t("Workshop title"))).left().padBottom(5).row();
        TextField title=new TextField(titleText,skin); title.setName("workshop-title"); title.setProgrammaticChangeEvents(true);
        title.addListener(new ChangeListener(){ @Override public void changed(ChangeEvent event,Actor actor) { titleText=title.getText(); }});
        details.add(title).minWidth(0).growX().height(44).padBottom(10).row();
        details.add(label(t("Visibility"))).left().padBottom(5).row();
        SelectBox<String> visibilityChoice=new SelectBox<>(skin); visibilityChoice.setName("workshop-visibility");
        visibilityChoice.setItems(t("Private"),t("Friends only"),t("Public")); visibilityChoice.setSelectedIndex(visibility.ordinal());
        visibilityChoice.addListener(new ChangeListener(){ @Override public void changed(ChangeEvent event,Actor actor) {
            visibility=WorkshopPublisher.Visibility.values()[visibilityChoice.getSelectedIndex()];
        }});
        details.add(visibilityChoice).width(360).height(44).left().padBottom(10).row();
        Table covers=new Table(); covers.left();
        covers.add(button("Default cover","workshop-default-cover",this::defaultCover)).width(250).height(42).padRight(8);
        covers.add(button("Choose local image","workshop-custom-cover",this::chooseCover)).width(320).height(42);
        details.add(covers).left().row();
        overview.add(details).minWidth(0).growX().top();
        content.add(overview).minWidth(0).growX().left().padBottom(16).row();
        content.add(label(t("Editable description"))).left().padBottom(5).row();
        Label preview=wrapped(WorkshopPublisher.Request.description(editableDescription)); preview.setName("workshop-description-preview");
        GrowingTextArea description=new GrowingTextArea(editableDescription,skin); description.setName("workshop-editable-description");
        description.setProgrammaticChangeEvents(true);
        description.addListener(new ChangeListener(){ @Override public void changed(ChangeEvent event,Actor actor) {
            editableDescription=description.getText(); preview.setText(WorkshopPublisher.Request.description(editableDescription));
            preview.invalidateHierarchy();
        }});
        ScrollPane editableScroll=new ScrollPane(description,skin); editableScroll.setName("workshop-editable-description-scroll");
        editableScroll.setScrollingDisabled(true,false); editableScroll.setFadeScrollBars(false); editableScroll.setOverscroll(false,false);
        editableScroll.setFlickScroll(false); editableScroll.setCancelTouchFocus(false); editableScroll.setSmoothScrolling(false);
        editableScroll.addListener(new com.badlogic.gdx.scenes.scene2d.InputListener() {
            @Override public boolean scrolled(com.badlogic.gdx.scenes.scene2d.InputEvent event,float x,float y,int amount) {
                if(!editableScroll.isScrollY())return false;
                event.stop(); return true;
            }
        });
        description.connectScroll(editableScroll);
        content.add(editableScroll).minWidth(0).growX().height(180).padBottom(14).row();
        content.add(label(t("Full description preview"))).left().padBottom(5).row();
        content.add(preview).minWidth(0).growX().left().row();
    }
    private void defaultCover() {
        begin(); message.setText(t("Preparing cover...")); renderActions();
        worker.submit(() -> { Path result=null; Exception failure=null;
            try { result=WorkshopCover.createDefault(); } catch(Exception ex) { failure=ex; }
            final Path image=result; final Exception problem=failure;
            post.accept(() -> completeCover(image,problem));
        });
    }
    private void chooseCover() {
        begin(); renderActions(); String title=t("Choose local image");
        worker.submit(() -> { Path result=null; Exception failure=null;
            try {
                Path picked=coverChooser.choose(title);
                if(picked!=null) result=WorkshopCover.prepare(picked);
            } catch(Exception ex) { failure=ex; }
            final Path image=result; final Exception problem=failure;
            post.accept(() -> completeCover(image,problem));
        });
    }
    private static Path chooseLocalCover(String title) throws Exception {
        final Path[] selected={null};
        EventQueue.invokeAndWait(() -> {
            JFileChooser chooser=new JFileChooser() {
                @Override protected JDialog createDialog(java.awt.Component parent) {
                    JDialog dialog=super.createDialog(parent);
                    dialog.setAlwaysOnTop(true); dialog.setLocationByPlatform(true);
                    return dialog;
                }
            };
            chooser.setDialogTitle(title);
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setAcceptAllFileFilterUsed(true);
            if(chooser.showOpenDialog(null)==JFileChooser.APPROVE_OPTION && chooser.getSelectedFile()!=null)
                selected[0]=chooser.getSelectedFile().toPath();
        });
        return selected[0];
    }
    private void completeCover(Path image,Exception failure) {
        if(!active) { deleteCover(image); return; }
        finish(); if(failure!=null) { setError(failure.getMessage()); render(); return; }
        if(image!=null) {
            try {
                String nextHash=WorkshopExport.sha256(image);
                Texture next=new Texture(Gdx.files.absolute(image.toString()));
                if(coverTexture!=null)coverTexture.dispose(); deleteCover(cover);
                cover=image; coverHash=nextHash; coverTexture=next;
            } catch(Exception ex) { deleteCover(image); setError(ex.getMessage()); }
        }
        render();
    }
    private void loadLink() {
        try { accountId=publisher.accountId(); linkedItem=publisher.linkedItem(snapshot.packageId);
            pendingCreation=publisher.creationPending(snapshot.packageId); confirmClearPending=false;
            updateMode=linkedItem!=null; verifiedItem=null; verifiedTitle=null; setError(""); }
        catch(Exception ex) { accountId=null; linkedItem=null; setError(ex.getMessage()); }
    }
    private void renderAction() {
        if(accountId==null) {
            content.add(wrapped(t("Steam account unavailable"))).minWidth(0).growX().left().row();
            content.add(button("Retry","workshop-retry-steam",() -> { loadLink(); render(); })).width(180).height(44).left().padTop(10).row();
            return;
        }
        TextButton create=new FittedTextButton(t("Create new music pack"),skin,"choice"); create.setName("workshop-create");
        TextButton update=new FittedTextButton(t("Update published music pack"),skin,"choice"); update.setName("workshop-update");
        ButtonGroup<TextButton> group=new ButtonGroup<>(); group.setMinCheckCount(0); group.setMaxCheckCount(1); group.add(create); group.add(update);
        create.setProgrammaticChangeEvents(false); update.setProgrammaticChangeEvents(false);
        create.setDisabled(linkedItem!=null || pendingCreation); update.setDisabled(linkedItem==null);
        create.setChecked(!updateMode && !pendingCreation); update.setChecked(updateMode);
        create.addListener(new ChangeListener(){ @Override public void changed(ChangeEvent e,Actor a) { if(create.isChecked())updateMode=false; }});
        update.addListener(new ChangeListener(){ @Override public void changed(ChangeEvent e,Actor a) { if(update.isChecked())updateMode=true; }});
        content.add(create).width(440).height(48).left().padBottom(8).row();
        content.add(update).width(440).height(48).left().padBottom(12).row();
        content.add(wrapped(pendingCreation?t("A previous creation may have succeeded. Check your Workshop items or link the item below.")
                :linkedItem==null?t("No linked Workshop item for this Steam account."):t("Linked Workshop item:")+" "+linkedItem)).minWidth(0).growX().left().row();
        if(pendingCreation) {
            if(confirmClearPending) {
                content.add(wrapped(t("Confirm that Steam did not create an item before allowing another creation."))).minWidth(0).growX().left().padTop(8).row();
                content.add(button("Confirm no item was created","workshop-confirm-clear-pending",() -> {
                    try { publisher.clearPendingCreation(snapshot.packageId,accountId); loadLink(); render(); }
                    catch(Exception ex) { setError(ex.getMessage()); }
                })).width(400).height(44).left().padTop(8).row();
            } else content.add(button("I checked my Workshop items","workshop-check-pending",() -> { confirmClearPending=true; render(); })).width(400).height(44).left().padTop(8).row();
        }
        if(linkedItem==null) {
            content.add(label(t("Existing Workshop item ID or link"))).left().padTop(16).padBottom(5).row();
            Table verifyRow=new Table();
            TextField input=new TextField(existingInput,skin); input.setName("workshop-existing-id"); input.setProgrammaticChangeEvents(true);
            input.addListener(new ChangeListener(){ @Override public void changed(ChangeEvent event,Actor actor) {
                existingInput=input.getText(); verifiedItem=null; verifiedTitle=null;
                if(existingLinkButton!=null)existingLinkButton.setDisabled(true);
                if(existingVerifiedLabel!=null)existingVerifiedLabel.setText("");
            }});
            verifyRow.add(input).minWidth(0).growX().height(44).padRight(10);
            verifyRow.add(button("Verify ownership","workshop-verify-item",this::verifyExisting)).width(200).height(44);
            content.add(verifyRow).minWidth(0).growX().left().row();
            if(verifiedItem!=null) {
                existingVerifiedLabel=wrapped(t("Verified item:")+" "+verifiedTitle+"  #"+verifiedItem);
                content.add(existingVerifiedLabel).minWidth(0).growX().left().padTop(12).row();
                content.add(wrapped(t("Confirm this is the matching music pack; updating replaces its content."))).minWidth(0).growX().left().padTop(6).row();
                existingLinkButton=button("Link this item","workshop-link-item",this::linkExisting);
                content.add(existingLinkButton).width(240).height(44).left().padTop(8).row();
            }
        }
    }
    private void verifyExisting() {
        if(working || accountId==null)return;
        try {
            final String expected=accountId, entered=existingInput; final long generation=++verificationGeneration;
            begin(); verifying=true; verificationStarted=System.nanoTime(); message.setText(t("Verifying Workshop item...")); renderActions();
            publisher.verifyExisting(entered,expected,(id,title,failure) -> post.accept(() -> {
                if(!active || !verifying || generation!=verificationGeneration)return;
                verifying=false; finish();
                if(failure!=null) { verifiedItem=null; verifiedTitle=null; setError(failure); }
                else if(!entered.equals(existingInput)) { verifiedItem=null; verifiedTitle=null; setError("Enter a valid Workshop item ID or link"); }
                else { verifiedItem=id; verifiedTitle=title==null?"":title; setError(""); }
                render();
            }));
        } catch(Exception ex) { verifying=false; finish(); setError(ex.getMessage()); render(); }
    }
    private void linkExisting() {
        if(verifiedItem==null || accountId==null)return;
        try { publisher.linkExisting(snapshot.packageId,verifiedItem,accountId); linkedItem=verifiedItem; pendingCreation=false; updateMode=true; setError(""); render(); }
        catch(Exception ex) { setError(ex.getMessage()); }
    }
    private void renderConfirmation() {
        WorkshopPublisher.State state=publisher.state();
        if(coverTexture!=null)content.add(new Image(coverTexture)).size(150).left().padBottom(10).row();
        content.add(label(t("Workshop title"))).left().row(); content.add(wrapped(request.title)).minWidth(0).growX().left().padBottom(10).row();
        content.add(wrapped(t("Visibility")+": "+visibilityText()+"\n"+(updateMode?t("Update published music pack"):t("Create new music pack"))
                +(linkedItem==null?"":"  #"+linkedItem))).minWidth(0).growX().left().padBottom(12).row();
        content.add(wrapped(t("Use only music and images you have permission to publish."))).minWidth(0).growX().left().padBottom(14).row();
        progressLabel=wrapped(""); progressLabel.setName("workshop-progress"); content.add(progressLabel).minWidth(0).growX().left().padBottom(12).row();
        ProgressBar.ProgressBarStyle progressStyle=new ProgressBar.ProgressBarStyle();
        progressStyle.background=skin.getDrawable("candidate-background");
        progressStyle.knobBefore=skin.get("list-choice",TextButton.TextButtonStyle.class).checked;
        progressBar=new ProgressBar(0,100,1,false,progressStyle); progressBar.setName("workshop-progress-bar");
        content.add(progressBar).width(360).height(16).left().padBottom(12).row();
        if(state==WorkshopPublisher.State.AGREEMENT) {
            content.add(wrapped(t("Steam requires you to accept the Workshop agreement."))).minWidth(0).growX().left().padBottom(8).row();
            content.add(button("Open Workshop agreement","workshop-open-agreement",() -> Gdx.net.openURI(AGREEMENT_URL))).width(300).height(44).left().row();
            content.add(button("After accepting, continue upload","workshop-continue",() -> { publisher.continueAfterAgreement(); render(); })).width(360).height(44).left().padTop(8).row();
        } else if(state==WorkshopPublisher.State.SUCCESS) {
            content.add(button("Open Workshop item","workshop-open-item",() -> Gdx.net.openURI("https://steamcommunity.com/sharedfiles/filedetails/?id="+publisher.itemId()))).width(280).height(44).left().row();
        } else if(state==WorkshopPublisher.State.FAILED && publisher.hasUnsavedItem()) {
            content.add(wrapped(t("Keep the created item ID. Retry saving its link before creating another item."))).minWidth(0).growX().left().padBottom(8).row();
            content.add(button("Retry saving item link","workshop-retry-link",() -> {
                try { publisher.retryAssociation(); setError(""); render(); }
                catch(Exception ex) { setError(ex.getMessage()); }
            })).width(300).height(44).left().row();
        }
        content.add(label(t("Full description"))).left().padTop(12).padBottom(5).row();
        Label finalDescription=wrapped(request.description); finalDescription.setName("workshop-confirm-description");
        content.add(finalDescription).minWidth(0).growX().left().row();
        updateProgress();
    }
    private String visibilityText() { return t(visibility==WorkshopPublisher.Visibility.PUBLIC?"Public":visibility==WorkshopPublisher.Visibility.FRIENDS?"Friends only":"Private"); }
    private void renderActions() {
        actions.clear();
        if(step>0 && publisher.state()!=WorkshopPublisher.State.SUCCESS) {
            TextButton previous=button("Back","workshop-back",this::back); previous.setDisabled(working || publisher.active());
            actions.add(previous).width(150).height(44).left().padRight(10);
        }
        actions.add().growX();
        if(step<3) {
            TextButton next=button("Next","workshop-next",this::next); next.setDisabled(working || step==2 && (accountId==null || pendingCreation));
            actions.add(next).width(180).height(44).right();
        } else if(publisher.state()==WorkshopPublisher.State.IDLE) {
            TextButton submit=button("Confirm and upload","workshop-submit",this::submit); submit.setDisabled(working);
            actions.add(submit).width(260).height(44).right();
        } else if(publisher.state()==WorkshopPublisher.State.SUCCESS) actions.add(button("Finish","workshop-finish",this::close)).width(180).height(44);
    }
    private void next() {
        if(working)return;
        try {
            if(step==0) { inspectExport(); return; }
            if(step==1) {
                if(cover==null) { setError("No cover selected"); return; }
                request=new WorkshopPublisher.Request(titleText,editableDescription,visibility);
                loadLink(); step=2; if(accountId!=null)setError(""); render(); return;
            }
            step++; setError(""); shownState=null; render();
        } catch(Exception ex) { setError(ex.getMessage()); }
    }
    private void back() {
        if(working || publisher.active())return;
        if(publisher.hasUnsavedItem()) { setError("Keep the created item ID. Retry saving its link before creating another item."); return; }
        if(step>0) {
            if(step==3 && publisher.state()!=WorkshopPublisher.State.IDLE) { publisher.reset(); loadLink(); }
            step--; if(accountId!=null || step!=2)setError(""); render();
        }
    }
    private void submit() {
        if(working || snapshot==null || cover==null || accountId==null)return;
        final WorkshopPublisher.Request current=new WorkshopPublisher.Request(titleText,editableDescription,visibility);
        final WorkshopExport.Snapshot chosen=snapshot; final Path image=cover; final String hash=coverHash, account=accountId, linked=linkedItem;
        final boolean isUpdate=updateMode; begin(); message.setText(t("Preparing upload...")); renderActions();
        worker.submit(() -> { WorkshopExport.Staged prepared=null; Exception failure=null;
            try { prepared=WorkshopExport.stage(chosen,image,hash); } catch(Exception ex) { failure=ex; }
            final WorkshopExport.Staged staged=prepared; final Exception problem=failure;
            post.accept(() -> {
                if(!active) { closeStage(staged); return; }
                finish();
                if(problem!=null) { setError(problem.getMessage()); render(); return; }
                try { publisher.start(current,chosen,staged,isUpdate,account,linked); }
                catch(Exception ex) { closeStage(staged); setError(ex.getMessage()); }
                shownState=null; render();
            });
        });
    }
    void update() {
        if(!active)return;
        if(verifying && System.nanoTime()-verificationStarted>15_000_000_000L) {
            verifying=false; verificationGeneration++; publisher.cancelVerification(); finish();
            setError("Steam item verification timed out; try again."); render();
        }
        publisher.update();
        if(step==3 && shownState!=publisher.state()) { shownState=publisher.state(); render(); }
        if(step==3)updateProgress();
    }
    private void updateProgress() {
        if(progressLabel==null)return;
        WorkshopPublisher.State state=publisher.state(); String text="";
        if(working) text=t("Preparing upload...");
        else if(state==WorkshopPublisher.State.CREATING) text=t("Creating Workshop item...");
        else if(state==WorkshopPublisher.State.ADDING_DEPENDENCY) text=t("Adding the required mod...");
        else if(state==WorkshopPublisher.State.UPLOADING || state==WorkshopPublisher.State.READY) {
            WorkshopPublisher.Progress p=publisher.progress();
            text=t("Upload progress")+": "+t(p.phase)+(p.total>0 ? "  "+Math.min(100,Math.round(100.0*p.processed/p.total))+"%" : "");
        } else if(state==WorkshopPublisher.State.SUCCESS) text=t("Workshop upload completed")+"  #"+publisher.itemId();
        else if(state==WorkshopPublisher.State.FAILED) text=t("Workshop upload failed")+": "+t(publisher.error())+(publisher.itemId()==null?"":"  #"+publisher.itemId());
        if(!progressLabel.getText().toString().equals(text)) { progressLabel.setText(text); progressLabel.invalidateHierarchy(); }
        if(progressBar!=null) {
            boolean visible=state==WorkshopPublisher.State.UPLOADING || state==WorkshopPublisher.State.READY;
            progressBar.setVisible(visible);
            WorkshopPublisher.Progress p=publisher.progress();
            progressBar.setValue(visible && p.total>0 ? (float)Math.min(100,100.0*p.processed/p.total) : 0);
        }
    }
    void close() {
        if(verifying) { verifying=false; verificationGeneration++; publisher.cancelVerification(); finish(); }
        if(working || publisher.active()) { setError("Please wait until the Steam operation finishes."); return; }
        active=false; publisher.close(); root.remove();
        if(coverTexture!=null)coverTexture.dispose(); deleteCover(cover); onClose.run();
    }
    private static void deleteCover(Path path) { if(path!=null)try { Files.deleteIfExists(path); } catch(Exception ignored) { } }
    private static void closeStage(WorkshopExport.Staged stage) { if(stage!=null)try { stage.close(); } catch(Exception ignored) { } }
}
