package battleaudio.ui;

import battleaudio.localization.I18n;

import battleaudio.model.Config;
import battleaudio.model.Config.Rule;
import battleaudio.model.MusicPack;
import battleaudio.model.Config.Candidate;
import battleaudio.model.RuleCopies;
import battleaudio.model.Config.Threshold;
import battleaudio.model.Config.Trigger;
import battleaudio.model.Config.Parameter;
import battleaudio.model.Events;
import battleaudio.storage.AudioLibrary;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.FitViewport;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** One game-rendered layer shared by the main menu and the dungeon. */
public final class SettingsScreen {
    public interface Host {
        void preview(Rule rule, float backgroundRatio);
        void stopPreview();
        void stopAll();
        void save(Config config) throws Exception;
        default void apply(Config config) { }
        default void applyPackageToggle(Config config, String packId) { apply(config); }
        default void persist(Config config) throws Exception { save(config); }
        default void persistenceCompleted(Config config) { }
        default void savePackageToggle(Config config, String packId) throws Exception { save(config); }
        default void deletePackage(Config config, String packId, String localFolder) throws Exception { save(config); }
        default Config copyPackage(Config config, String packId) throws Exception {
            throw new UnsupportedOperationException("音乐包复制存储不可用");
        }
        default Config copyPackage(Config config, String packId, String copySuffix) throws Exception {
            return copyPackage(config, packId);
        }
        default Path exportPackage(Config config, String packId) throws Exception {
            throw new UnsupportedOperationException("\u97f3\u4e50\u5305\u5bfc\u51fa\u4e0d\u53ef\u7528");
        }
        void closed();
    }
    private final AudioLibrary library;
    private final Host host;
    private Config draft;
    private final Stage stage;
    private final Skin skin = new Skin();
    private final Table root = new Table();
    private final Table libraryList = new Table();
    private final Table editor = new Table();
    private final ScrollPane editorScroll;
    private final Label status;
    private final Cell<Label> statusCell;
    private Events.Category category = Events.Category.GENERAL;
    private Events.Category stopCategory = Events.Category.GENERAL;
    private boolean stopEditor;
    private final Set<String> expandedStops = new HashSet<>();
    private final Set<TextField> invalidInputs = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> advancedInputs = new HashSet<>();
    private final Set<String> listInputs = new HashSet<>();
    private boolean building;
    private String persisted;
    private final Queue<Runnable> completions = new ConcurrentLinkedQueue<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BattleAudio-io");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledThreadPoolExecutor saveScheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "BattleAudio-autosave-debounce");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> pendingSave;
    private long saveGeneration;
    private final java.util.List<Runnable> saveFailureRecovery = new ArrayList<>();
    private final Table browser = new Table();
    private final ScrollPane browserScroll;
    private final Table body = new Table();
    private final Table footer = new Table();
    private final TextField pathField;
    private final Table files = new Table();
    private final Map<Path, TextButton> fileButtons = new LinkedHashMap<>();
    private final Label selectionStatus;
    private final TextButton importButton;
    private InputProcessor previousInput;
    private boolean busy;
    private boolean browsing;
    private boolean replacing;
    private Candidate fileTarget;
    private String expandedCandidate;
    private boolean closed;
    private boolean disposed;
    private Rule selected;
    private String activePackId;
    private final Set<String> expandedPackages = new HashSet<>();
    private final Map<String, TextButton> packRows = new LinkedHashMap<>();
    private final Map<String, TextButton> ruleRows = new LinkedHashMap<>();
    private final Map<String, Table> packageRuleTables = new LinkedHashMap<>();
    private final Map<String, Table> packageBlocks = new LinkedHashMap<>();
    private final Map<String, TextButton> packageExpandButtons = new LinkedHashMap<>();
    private Table contextMenu;
    private Table contextMenuParent;
    private WorkshopPublishWizard workshopWizard;
    private Path directory;
    private final java.util.List<Path> pickedFiles = new ArrayList<>();
    private final java.util.List<TextField> numericFields = new ArrayList<>();
    private Rule activeStopOwner;
    private Rule activeStopView;
    private String error;
    private final boolean embedded;
    private final java.nio.IntBuffer renderViewport = com.badlogic.gdx.utils.BufferUtils.newIntBuffer(16);

    public SettingsScreen(AudioLibrary library, Config config, String error, Host host) {
        this(library, config, error, host, false);
    }

    public SettingsScreen(AudioLibrary library, Config config, String error, Host host, boolean embedded) {
        this.embedded = embedded;
        this.library = library;
        this.host = host;
        this.error = error == null ? null : I18n.error(error);
        draft = config.copy();
        saveScheduler.setRemoveOnCancelPolicy(true);
        if (draft.packs == null) draft.packs = new ArrayList<>();
        if (!draft.rules.isEmpty() && draft.packs.isEmpty()) draft.migrate();
        if (!draft.packs.isEmpty()) activePackId = draft.packs.get(0).id;
        createSkin();
        stage = new Stage(new FitViewport(embedded ? com.megacrit.cardcrawl.core.Settings.WIDTH / com.megacrit.cardcrawl.core.Settings.scale : 1600,
                embedded ? com.megacrit.cardcrawl.core.Settings.HEIGHT / com.megacrit.cardcrawl.core.Settings.scale : 1000));
        stage.addCaptureListener(new UiClickSounds(() -> busy || closed));
        stage.addCaptureListener(new InputListener() {
            @Override public boolean mouseMoved(InputEvent event, float x, float y) {
                scrollUnder(event.getTarget());
                return false;
            }
            @Override public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                if (pointer == -1) scrollUnder(event.getTarget());
            }
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                Actor target = event.getTarget();
                if (contextMenu != null && !isWithin(target, contextMenu)
                        && (contextMenuParent == null || !isWithin(target, contextMenuParent))) closeContextMenu();
                if (button == Input.Buttons.RIGHT && !busy) {
                    for (Actor current = target; current != null; current = current.getParent()) {
                        String name = current.getName();
                        if (name == null) continue;
                        if (name.startsWith("pack-row-")) {
                            MusicPack pack = draft.pack(name.substring("pack-row-".length()));
                            if (pack != null) showPackageMenu(pack, event.getStageX(), event.getStageY());
                            event.stop(); return true;
                        }
                        if (name.startsWith("rule-row-")) {
                            String id = name.substring("rule-row-".length());
                            for (Rule rule : draft.rules) if (id.equals(rule.id)) {
                                showRuleMenu(rule, event.getStageX(), event.getStageY()); break;
                            }
                            event.stop(); return true;
                        }
                    }
                }
                return false;
            }
        });
        building = true;
        root.setFillParent(!embedded);
        if (!embedded) root.setBackground(skin.getDrawable("overlay-background"));
        root.pad(embedded ? 12 : 30);
        stage.addActor(root);
        Table header = new Table();
        Image icon = new Image(AudioIcon.texture()); icon.setName("audio-icon");
        header.add(icon).size(44).padRight(18);
        Label title = label(I18n.text("更好的自定义音频触发"));
        title.setWrap(true); title.setFontScale(1.35f); title.setColor(com.megacrit.cardcrawl.core.Settings.GOLD_COLOR);
        header.add(title).minWidth(0).growX().left();
        if (!embedded) header.add(button(I18n.text("关闭"), this::close)).width(150).height(48);
        root.add(header).growX().padBottom(22).row();
        status = label(""); status.setName("settings-status");
        status.setWrap(true);
        statusCell = root.add(status).growX().left().height(0);
        root.row();
        Table left = new Table();
        left.top();
        left.add(label(I18n.text("Music packs"))).left().padBottom(8).row();
        libraryList.padRight(24);
        ScrollPane listScroll = scroll(libraryList);
        left.add(listScroll).minWidth(0).grow().row();
        left.add(button(I18n.text("New music pack"), this::createLocalPackage)).growX().height(42).padTop(8).row();
        left.add(button(I18n.text("新建规则"), this::createRule)).growX().height(44).padTop(6).row();
        TextButton publish=button(I18n.workshopText("Publish music pack"), this::openWorkshopWizard);
        publish.setName("open-workshop-publisher");
        left.add(publish).growX().height(42).padTop(6).row();
        editor.top().left().padRight(22);
        editorScroll = scroll(editor);

        footer.add(button(I18n.text("停止全部音频"), host::stopAll)).colspan(2).left().height(44);
        body.add(left).width(embedded ? 280 : 380).growY().padRight(embedded ? 20 : 28);
        body.add(editorScroll).minWidth(0).grow();
        root.add(body).grow().row();
        browser.top().left();
        browser.setFillParent(!embedded);
        if (!embedded) browser.setBackground(skin.getDrawable("overlay-background"));
        browser.pad(embedded ? 12 : 35);
        Table browserHeader = new Table();
        browserHeader.add(label(I18n.text("选择音频文件"))).expandX().left();
        browserHeader.add(button(I18n.text("返回设置"), this::leaveBrowser)).width(180).height(48);
        browser.add(browserHeader).growX().colspan(2).padBottom(22).row();
        pathField = new TextField("", skin);
        pathField.setMessageText(I18n.text("文件或文件夹路径"));
        browser.add(pathField).growX().height(48).padRight(10);
        browser.add(button(I18n.text("打开路径"), this::openPath)).width(180).height(48).row();
        Table shortcuts = new Table();
        shortcuts.add(button(I18n.text("上一级"), () -> {
            if (directory != null && directory.getParent() != null) browse(directory.getParent());
            else browseDrives();
        })).width(150).height(44).padRight(10);
        shortcuts.add(button(I18n.text("选盘"), this::browseDrives)).width(150).height(44).padRight(10);
        for (String location : new String[] {"Desktop", "Downloads", "Music"}) {
            String titleText = location.equals("Desktop") ? I18n.text("桌面") : location.equals("Downloads") ? I18n.text("下载") : I18n.text("音乐");
            shortcuts.add(button(titleText, () -> browse(Paths.get(System.getProperty("user.home"), location)))).width(150).height(44).padRight(10);
        }
        browser.add(shortcuts).colspan(2).growX().left().padTop(12).padBottom(12).row();
        files.top().padRight(22);
        browserScroll = scroll(files);
        browser.add(browserScroll).colspan(2).grow().row();
        Table browserFooter = new Table();
        selectionStatus = label(I18n.text("点击文件可多选，再次点击取消"));
        selectionStatus.setWrap(true);
        browserFooter.add(selectionStatus).minWidth(0).growX().left().padRight(15);
        importButton = button(I18n.text("导入所选文件"), this::importPicked);
        browserFooter.add(importButton).width(300).height(50);
        browser.add(browserFooter).colspan(2).growX().right().padTop(15);
        stage.addActor(browser);
        browser.setVisible(false);
        if (activePackId == null) activePackId = draft.ensureLocalPack(I18n.text("My music pack")).id;
        selected = draft.rules.isEmpty() ? null : draft.rules.get(0);
        if (selected != null) activePackId = selected.packId;
        if (activePackId != null) expandedPackages.add(activePackId);
        list();
        bind(false);
        configureEmbeddedBounds();
        building = false;
        persisted = new com.google.gson.Gson().toJson(draft);
        message(error == null ? "" : I18n.error(error));
    }

    public void open() {
        previousInput = Gdx.input.getInputProcessor();
        stage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        configureEmbeddedBounds();
        Gdx.input.setInputProcessor(stage);
        stage.setScrollFocus(null);
    }

    private void configureEmbeddedBounds() {
        if (!embedded) return;
        // Match ModPanel.renderBg's native coordinates, inset from the blue frame.
        float bottom = com.megacrit.cardcrawl.core.Settings.OPTION_Y / com.megacrit.cardcrawl.core.Settings.scale - 295;
        root.setBounds(350, bottom, 1220, 580);
        browser.setBounds(350, bottom, 1220, 580);
    }

    public void update() {
        Runnable completion;
        while ((completion = completions.poll()) != null) completion.run();
        if (workshopWizard != null) workshopWizard.update();
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)
                || com.megacrit.cardcrawl.helpers.input.InputHelper.pressedEscape) {
            com.megacrit.cardcrawl.helpers.input.InputHelper.pressedEscape = false;
            if (workshopWizard != null) workshopWizard.close();
            else if (contextMenu != null) closeContextMenu();
            else if (!SearchMultiSelect.dismiss(stage)) {
                if (browsing) leaveBrowser();
                else if (!embedded) close();
            }
        }
        if (closed) return;
        stage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        stage.act(Math.min(0.05f, Gdx.graphics.getDeltaTime()));
        if (Gdx.input.getInputProcessor() != stage) Gdx.input.setInputProcessor(stage);
    }

    public boolean isBusy() { return busy; }
    public void report(String text) { message(text==null ? null : I18n.error(text)); }

    public void render(SpriteBatch batch) {
        if (closed) return;
        boolean drawing = batch.isDrawing();
        if (!drawing) batch.begin();
        skin.getDrawable("background").draw(batch,0,0,
                com.megacrit.cardcrawl.core.Settings.WIDTH,com.megacrit.cardcrawl.core.Settings.HEIGHT);
        batch.end();
        renderViewport.clear();
        Gdx.gl.glGetIntegerv(com.badlogic.gdx.graphics.GL20.GL_VIEWPORT, renderViewport);
        stage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        stage.draw();
        Gdx.gl.glViewport(renderViewport.get(0), renderViewport.get(1), renderViewport.get(2), renderViewport.get(3));
        if (drawing) batch.begin();
    }

    private void createSkin() { SettingsSkin.populate(skin,draft); }

    private Label label(String text) { return new Label(text, skin); }
    private TextButton button(String text, Runnable action) {
        return button(text, action, false);
    }
    private TextButton button(String text, Runnable action, boolean choice) {
        TextButton button=choice ? new TextButton(text,skin,"choice") : new FittedTextButton(text,skin,"default");
        button.setName(text);
        button.setProgrammaticChangeEvents(false);
        button.addListener(new ChangeListener() { public void changed(ChangeEvent event, Actor actor) {
            if (!choice) button.setChecked(false);
            if (!busy) action.run();
        } });
        return button;
    }
    private TextButton listChoiceButton(String text, Runnable action) {
        TextButton button = new TextButton(text, skin, "list-choice");
        button.setName(text); button.setProgrammaticChangeEvents(false);
        button.addListener(new ChangeListener() { public void changed(ChangeEvent event, Actor actor) {
            if (!busy) action.run();
        } });
        return button;
    }
    private TextButton disclosureButton(String text, Runnable action) {
        TextButton button = new TextButton(text, skin, "disclosure");
        button.setName(text); button.setProgrammaticChangeEvents(false);
        button.getLabel().setAlignment(Align.center); button.getLabel().setFontScale(1.18f);
        button.addListener(new ChangeListener() { public void changed(ChangeEvent event, Actor actor) {
            button.setChecked(false); if (!busy) action.run();
        } });
        return button;
    }
    private TextButton menuButton(String text, Runnable action) {
        TextButton button = new FittedTextButton(text, skin, "menu");
        button.setName(text); button.setProgrammaticChangeEvents(false);
        button.getLabel().setAlignment(Align.center);
        button.addListener(new ChangeListener() { public void changed(ChangeEvent event, Actor actor) {
            button.setChecked(false); if (!busy) action.run();
        } });
        return button;
    }
    private ScrollPane scroll(Actor content) {
        ScrollPane pane = new ScrollPane(content, skin);
        pane.setFadeScrollBars(false); pane.setScrollingDisabled(true, false); pane.setOverscroll(false, false);
        pane.setFlickScroll(false); pane.setCancelTouchFocus(false);
        pane.setVariableSizeKnobs(false);
        return pane;
    }
    private void scrollUnder(Actor actor) {
        while (actor != null && !(actor instanceof ScrollPane)) actor = actor.getParent();
        stage.setScrollFocus(actor);
    }
    private Slider slider(float minimum, float maximum, float value, Consumer<Float> change) {
        Slider slider = new Slider(minimum, maximum, maximum > 100 ? 1 : 0.1f, false, skin);
        // This game's old libGDX Slider maps cancelTouchFocus's sentinel coordinates to zero.
        InputListener nativeListener = (InputListener)slider.getListeners().get(0);
        slider.removeListener(nativeListener);
        slider.addListener(new InputListener() {
            private float lastX, lastY;
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                lastX = x; lastY = y; return nativeListener.touchDown(event, x, y, pointer, button);
            }
            @Override public void touchDragged(InputEvent event, float x, float y, int pointer) {
                lastX = x; lastY = y; nativeListener.touchDragged(event, x, y, pointer);
            }
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                nativeListener.touchUp(event, event.isTouchFocusCancel() ? lastX : x, event.isTouchFocusCancel() ? lastY : y, pointer, button);
            }
            @Override public void enter(InputEvent event, float x, float y, int pointer, Actor from) { nativeListener.enter(event, x, y, pointer, from); }
            @Override public void exit(InputEvent event, float x, float y, int pointer, Actor to) { nativeListener.exit(event, x, y, pointer, to); }
        });
        slider.setValue(value);
        slider.addListener(new ChangeListener() { public void changed(ChangeEvent event, Actor actor) { change.accept(slider.getValue()); modified(); } });
        return slider;
    }
    private CheckBox check(String title, boolean checked, Consumer<Boolean> change) {
        CheckBox checkbox = new CheckBox("  " + title, skin);
        float available=Math.max(320,stage.getViewport().getWorldWidth()*0.62f-96);
        if(checkbox.getLabel().getPrefWidth()>available) {
            checkbox.getLabel().setWrap(true);checkbox.getLabelCell().width(available);
        }
        checkbox.setChecked(checked);
        checkbox.addListener(new ChangeListener() { public void changed(ChangeEvent event, Actor actor) { change.accept(checkbox.isChecked()); modified(); } });
        return checkbox;
    }

    private void list() {
        libraryList.clear(); libraryList.top();
        packRows.clear(); ruleRows.clear(); packageRuleTables.clear(); packageBlocks.clear();
        if (draft.packs == null) draft.packs = new ArrayList<>();
        Map<String, java.util.List<Rule>> byPack = new LinkedHashMap<>();
        java.util.List<Rule> rootRules = new ArrayList<>();
        for (Rule rule : draft.rules) {
            if (rule.packId == null || draft.pack(rule.packId) == null) rootRules.add(rule);
            else byPack.computeIfAbsent(rule.packId, ignored -> new ArrayList<>()).add(rule);
        }
        if (!rootRules.isEmpty()) {
            libraryList.add(label(I18n.text("Root list"))).left().padBottom(4).row();
            for (Rule rule : rootRules) addRuleRow(libraryList, rule);
        }
        for (MusicPack pack : draft.packs) {
            Table header = new Table();
            header.setName("pack-row-" + pack.id);
            CheckBox enabled = new CheckBox("", skin);
            enabled.setName("pack-enabled-" + pack.id);
            enabled.setChecked(pack.enabled);
            enabled.addListener(new ChangeListener() { public void changed(ChangeEvent event, Actor actor) {
                savePackageToggle(pack, enabled.isChecked());
            }});
            header.add(enabled).width(34).height(40).left().padRight(4);
            TextButton choose = listChoiceButton(pack.name, () -> selectPackage(pack));
            choose.setName("music-pack-name-" + pack.id);
            choose.getLabel().setEllipsis(true); choose.getLabel().setAlignment(Align.center);
            choose.getLabelCell().minWidth(0).padLeft(5).padRight(5);
            choose.setChecked(pack.id.equals(activePackId));
            header.add(choose).growX().minWidth(0).height(40);
            packRows.put(pack.id, choose);
            boolean expanded = expandedPackages.contains(pack.id);
            TextButton expand = disclosureButton(expanded ? "−" : "+", () -> togglePackageExpanded(pack.id));
            expand.setName("pack-expand-" + pack.id); packageExpandButtons.put(pack.id, expand);
            header.add(expand).width(42).height(38).padLeft(4);
            addRightClick(header, (x, y) -> showPackageMenu(pack, x, y));
            Table packageBlock = new Table(); packageBlock.top();
            packageBlocks.put(pack.id, packageBlock);
            packageBlock.add(header).minWidth(0).growX().height(40).padBottom(3).row();
            Table packageRules = new Table(); packageRules.top();
            packageRuleTables.put(pack.id, packageRules);
            for (Rule rule : byPack.getOrDefault(pack.id, Collections.emptyList())) addRuleRow(packageRules, rule);
            packageRules.setVisible(expanded);
            if (expanded) packageBlock.add(packageRules).minWidth(0).growX().row();
            libraryList.add(packageBlock).minWidth(0).growX().row();
        }
        if (draft.packs.isEmpty() && draft.rules.isEmpty()) libraryList.add(label(I18n.text("No music packs yet"))).left().row();
        if (draft.rules.isEmpty() && !draft.packs.isEmpty()) libraryList.add(label(I18n.text("尚无规则"))).left();
    }

    private void addRuleRow(Table parent, Rule rule) {
        Table row = new Table();
        row.setName("rule-row-" + rule.id);
        TextButton item = listChoiceButton(rule.name, () -> selectRule(rule));
        item.setName("music-rule-name-" + rule.id);
        item.getLabel().setEllipsis(true); item.getLabel().setAlignment(Align.left);
        item.getLabelCell().minWidth(0).padLeft(18).padRight(8);
        item.setChecked(rule == selected);
        ruleRows.put(rule.id, item);
        row.add(item).minWidth(0).growX().height(38);
        addRightClick(item, (x, y) -> showRuleMenu(rule, x, y));
        addRightClick(row, (x, y) -> showRuleMenu(rule, x, y));
        parent.add(row).minWidth(0).growX().padBottom(3).row();
    }

    private void selectPackage(MusicPack pack) {
        activePackId = pack.id; selected = null; expandedCandidate = null;
        updateListSelection(); bind(false); message("");
    }

    private void selectRule(Rule rule) {
        selected = rule; activePackId = rule.packId; expandedCandidate = null;
        updateListSelection(); bind(false); message("");
    }

    private void updateListSelection() {
        for (Map.Entry<String, TextButton> entry : packRows.entrySet())
            entry.getValue().setChecked(entry.getKey().equals(activePackId));
        for (Map.Entry<String, TextButton> entry : ruleRows.entrySet())
            entry.getValue().setChecked(selected != null && entry.getKey().equals(selected.id));
    }

    private void togglePackageExpanded(String packId) {
        boolean expanded;
        if (expandedPackages.add(packId)) expanded = true;
        else { expandedPackages.remove(packId); expanded = false; }
        Table rules = packageRuleTables.get(packId);
        Table packageBlock = packageBlocks.get(packId);
        TextButton control = packageExpandButtons.get(packId);
        if (rules != null) {
            rules.setVisible(expanded);
            if (expanded) {
                if (packageBlock != null && rules.getParent() != packageBlock) packageBlock.add(rules).minWidth(0).growX().row();
            } else if (packageBlock != null) packageBlock.removeActor(rules);
        }
        if (control != null) control.setText(expanded ? "−" : "+");
        libraryList.invalidateHierarchy();
    }

    private interface ContextAction { void run(float x, float y); }

    private void addRightClick(Actor actor, ContextAction action) {
        actor.addListener(new InputListener() {
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                if (button != Input.Buttons.RIGHT || busy) return false;
                action.run(event.getStageX(), event.getStageY());
                return true;
            }
        });
    }

    private static boolean isWithin(Actor target, Actor ancestor) {
        for (Actor current = target; current != null; current = current.getParent()) if (current == ancestor) return true;
        return false;
    }

    private void closeContextMenu() {
        if (contextMenu != null) contextMenu.remove();
        if (contextMenuParent != null) contextMenuParent.remove();
        contextMenu = null; contextMenuParent = null;
    }

    private TextButton addContextAction(Table items, String text, String actorName, Runnable action) {
        return addContextAction(items,text,actorName,action,true);
    }

    private TextButton addContextAction(Table items, String text, String actorName, Runnable action, boolean closeBeforeAction) {
        TextButton item = menuButton(text, () -> { if (closeBeforeAction) closeContextMenu(); action.run(); });
        item.setName(actorName); items.add(item).minWidth(0).growX().height(38).pad(2).row();
        return item;
    }

    private void addContextHeading(Table items, String text) {
        Label heading = label(text); heading.setFontScale(.78f);
        items.add(heading).growX().left().pad(7, 8, 3, 8).row();
    }

    private void showContextMenu(Table items, float stageX, float stageY) {
        Table menu = createContextMenu(items,true);
        float x = Math.max(0, Math.min(stageX, stage.getViewport().getWorldWidth() - menu.getWidth()));
        float y = Math.max(0, Math.min(stageY - menu.getHeight(), stage.getViewport().getWorldHeight() - menu.getHeight()));
        menu.setPosition(x, y);
    }

    private Table createContextMenu(Table items, boolean closeExisting) {
        if (closeExisting) closeContextMenu();
        items.top(); items.pack();
        ScrollPane pane = scroll(items);
        pane.setName("context-menu-scroll");
        contextMenu = new Table(); contextMenu.setName("settings-context-menu");
        contextMenu.setBackground(skin.getDrawable("menu-background")); contextMenu.pad(6);
        float maxHeight = stage.getViewport().getWorldHeight() - 48 - 12;
        float height = Math.min(Math.max(40, items.getPrefHeight()), maxHeight);
        float maxWidth = stage.getViewport().getWorldWidth() - 32;
        float outerWidth = Math.min(maxWidth, Math.max(embedded ? 270 : 300, items.getPrefWidth() + 20));
        float contentWidth = Math.max(1, outerWidth - 12);
        items.setWidth(contentWidth); items.invalidateHierarchy(); items.validate();
        contextMenu.add(pane).width(contentWidth).height(height);
        stage.addActor(contextMenu); contextMenu.pack();
        stage.setScrollFocus(pane);
        return contextMenu;
    }

    private void showPackageMenu(MusicPack pack, float x, float y) {
        activePackId = pack.id; selected = null; updateListSelection();
        completions.add(() -> bind(false));
        Table items = new Table(); items.top();
        addContextAction(items, I18n.text("Copy as new music pack"), "menu-copy-package", () -> copyPackage(pack.id));
        if (pack.source == MusicPack.Source.SUBSCRIBED) {
            addContextAction(items, I18n.text("Rename music pack"), "menu-rename-package", () -> showRenamePrompt(pack));
        } else {
            addContextAction(items, I18n.text("Rename music pack"), "menu-rename-package", () -> showRenamePrompt(pack));
            addContextAction(items, I18n.text("Export for Workshop"), "menu-export-package", () -> exportPackage(pack.id));
            addContextAction(items, I18n.text("Remove music pack (move rules to root)"), "menu-remove-package", () -> confirmRemovePackage(pack));
            addContextAction(items, I18n.text("Delete music pack"), "menu-delete-package", () -> confirmDeletePackage(pack));
        }
        showContextMenu(items, x, y);
    }

    private void showRenamePrompt(MusicPack pack) {
        closeContextMenu();
        Table prompt = new Table(); prompt.top().pad(12);
        Label title = label(I18n.text("Rename music pack"));
        prompt.add(title).growX().left().padBottom(8).row();
        TextField field = new TextField(pack.name, skin); field.setName("rename-pack-input");
        prompt.add(field).growX().height(44).padBottom(8).row();
        Table actions = new Table();
        actions.add(button(I18n.text("Cancel"), this::closeContextMenu)).growX().height(40).padRight(6);
        actions.add(button(I18n.text("Rename"), () -> {
            String name = field.getText().trim(); if (name.isEmpty()) return;
            pack.name = name; TextButton row = packRows.get(pack.id); if (row != null) row.setText(name);
            closeContextMenu(); modified();
        })).growX().height(40);
        prompt.add(actions).growX().row();
        contextMenu = prompt; prompt.setName("rename-pack-prompt"); prompt.setBackground(skin.getDrawable("overlay-background"));
        stage.addActor(prompt); prompt.pack();
        prompt.setPosition((stage.getViewport().getWorldWidth()-prompt.getWidth())/2,
                (stage.getViewport().getWorldHeight()-prompt.getHeight())/2);
        stage.setKeyboardFocus(field); field.selectAll();
    }

    private void focusRuleName(Rule rule) {
        selectRule(rule);
        TextField field = stage.getRoot().findActor("audio-name");
        if (field != null) { stage.setKeyboardFocus(field); field.selectAll(); }
    }

    private void confirmRemovePackage(MusicPack pack) {
        long count = draft.rules.stream().filter(rule -> pack.id.equals(rule.packId)).count();
        showConfirmation(I18n.text("Remove music pack"), String.format(I18n.text("This removes the package grouping and moves %d rules to the root list. Rules and audio files are kept."), count),
                I18n.text("Remove package"), () -> removePackageToRoot(pack));
    }

    private void confirmDeletePackage(MusicPack pack) {
        long count = draft.rules.stream().filter(rule -> pack.id.equals(rule.packId)).count();
        showConfirmation(I18n.text("Delete music pack"),
                String.format(I18n.text("This permanently deletes the package and its %d rules. Package files cannot be recovered."), count),
                I18n.text("Delete package"), () -> deletePackage(pack));
    }

    private void confirmDeleteRule(Rule rule) {
        showConfirmation(I18n.text("Delete rule"), I18n.text("This permanently removes the rule. Audio is reclaimed only when no remaining rule references it and ownership checks pass."),
                I18n.text("Delete"), () -> deleteRule(rule));
    }

    private void showConfirmation(String titleText, String bodyText, String confirmText, Runnable confirmed) {
        closeContextMenu();
        Table prompt = new Table(); prompt.top().pad(14); prompt.setName("confirmation-prompt");
        Label title = label(titleText); prompt.add(title).growX().left().padBottom(8).row();
        Label bodyTextLabel = label(bodyText); bodyTextLabel.setWrap(true);
        prompt.add(bodyTextLabel).width(embedded ? 360 : 460).growX().left().padBottom(12).row();
        Table actions = new Table();
        actions.add(button(I18n.text("Cancel"), this::closeContextMenu)).growX().height(40).padRight(6);
        actions.add(button(confirmText, () -> { closeContextMenu(); confirmed.run(); })).growX().height(40);
        prompt.add(actions).growX().row();
        prompt.setBackground(skin.getDrawable("overlay-background")); contextMenu = prompt;
        stage.addActor(prompt); prompt.pack();
        prompt.setPosition((stage.getViewport().getWorldWidth()-prompt.getWidth())/2,
                (stage.getViewport().getWorldHeight()-prompt.getHeight())/2);
    }

    private void showMoveMenu(Rule rule, Actor moveItem) {
        Table items = new Table(); items.top(); items.setName("move-rule-submenu");
        if (rule.packId != null) addContextAction(items, I18n.text("Move to root list"), "menu-move-rule-root", () -> moveRule(rule, null));
        for (MusicPack target : draft.packs) if (!target.id.equals(rule.packId))
            addContextAction(items, target.name, "menu-move-rule-" + target.id, () -> moveRule(rule, target.id));
        if (items.getChildren().size == 0) addContextHeading(items, I18n.text("No other editable packages"));
        Table parent = contextMenu;
        if (parent == null || moveItem == null) {
            closeContextMenu();
            return;
        }
        contextMenuParent = parent;
        Table submenu = createContextMenu(items,false); submenu.setName("move-rule-submenu");
        com.badlogic.gdx.math.Vector2 topRight = moveItem.localToStageCoordinates(
                new com.badlogic.gdx.math.Vector2(moveItem.getWidth(), moveItem.getHeight()));
        com.badlogic.gdx.math.Vector2 topLeft = moveItem.localToStageCoordinates(
                new com.badlogic.gdx.math.Vector2(0, moveItem.getHeight()));
        float x = topRight.x + 2;
        if (x + submenu.getWidth() > stage.getViewport().getWorldWidth() - 8)
            x = topLeft.x - submenu.getWidth() - 2;
        x = Math.max(4, Math.min(x, stage.getViewport().getWorldWidth() - submenu.getWidth() - 4));
        float y = topRight.y - submenu.getHeight();
        y = Math.max(4, Math.min(y, stage.getViewport().getWorldHeight() - submenu.getHeight() - 4));
        submenu.setPosition(x,y);
    }

    private void showRuleMenu(Rule rule, float x, float y) {
        selected = rule; activePackId = rule.packId; updateListSelection();
        completions.add(() -> bind(false));
        Table items = new Table(); items.top();
        addContextAction(items, I18n.text("复制规则"), "menu-copy-rule", () -> copyRule(rule));
        addContextAction(items, I18n.text("Rename rule"), "menu-rename-rule", () -> focusRuleName(rule));
        addContextAction(items, rule.enabled ? I18n.text("Disable rule") : I18n.text("Enable rule"), "menu-toggle-rule", () -> {
            rule.enabled = !rule.enabled; selectRule(rule); modified();
        });
        final TextButton[] moveItem = new TextButton[1];
        moveItem[0] = addContextAction(items, I18n.text("Move to") + "  ›", "menu-move-rule",
                () -> showMoveMenu(rule, moveItem[0]), false);
        addContextAction(items, I18n.text("删除"), "menu-delete-rule", () -> confirmDeleteRule(rule));
        showContextMenu(items, x, y);
    }

    private void copyPackagePersistence(Config saved) {
        draft.schemaVersion = saved.schemaVersion;
        for (MusicPack current : draft.packs) {
            MusicPack stored = saved.pack(current.id);
            if (stored == null) continue;
            current.contentId = stored.contentId;
            current.localFolder = stored.localFolder;
            current.ruleIds = new ArrayList<>(stored.ruleIds);
            current.ownedFiles = new ArrayList<>();
            for (MusicPack.OwnedFile file : stored.ownedFiles)
                current.ownedFiles.add(new MusicPack.OwnedFile(file.path, file.sha256, file.bytes));
        }
        for (Rule current : draft.rules) for (Rule stored : saved.rules) if (current.id.equals(stored.id)) {
            current.upstreamRuleId = stored.upstreamRuleId;
            for (Candidate candidate : current.candidates) for (Candidate savedCandidate : stored.candidates)
                if (candidate.id.equals(savedCandidate.id)) {
                    candidate.sourcePath = savedCandidate.sourcePath;
                    candidate.cachePath = savedCandidate.cachePath;
                }
        }
    }

    private void packageDetails(MusicPack pack) {
        editor.add(label(I18n.text("Pack name"))).width(embedded ? 320 : 360).minWidth(embedded ? 240 : 300).left().padRight(12);
        Label packageName = label(pack.name); packageName.setName("music-pack-name-value");
        editor.add(packageName).minWidth(0).growX().left().height(44).row();
        if (pack.source == MusicPack.Source.SUBSCRIBED) {
            Label note = label(I18n.text("Subscribed pack edits may be replaced by Steam updates. Copy the pack to keep your changes."));
            note.setName("subscription-edit-warning");
            note.setWrap(true); note.setFontScale(.8f);
            editor.add(note).colspan(2).growX().left().padTop(8).row();
        }
    }

    private void createLocalPackage() {
        MusicPack pack = new MusicPack();
        pack.localFolder = pack.id;
        pack.name = RuleCopies.uniqueName(I18n.text("New music pack"), draft.packs.stream().map(item -> item.name).collect(Collectors.toList()));
        draft.packs.add(pack); activePackId = pack.id; selected = null; expandedPackages.add(pack.id);
        draft.refreshPackMembership(); list(); bind(false); modified();
    }

    private void createRule() {
        Rule rule = new Rule(); rule.name = uniqueRuleName(I18n.text("新规则"));
        MusicPack pack = draft.pack(activePackId);
        if (activePackId != null && pack == null) pack = draft.ensureLocalPack(I18n.text("My music pack"));
        rule.packId = pack == null ? null : pack.id;
        if (pack != null && (pack.source == MusicPack.Source.SUBSCRIBED || pack.localFolder != null && !pack.localFolder.isEmpty())) {
            rule.upstreamRuleId = rule.id;
            rule.id = battleaudio.storage.MusicPackages.stableRuleId(pack.id, rule.upstreamRuleId);
        }
        activePackId = rule.packId;
        if (activePackId != null) expandedPackages.add(activePackId);
        draft.rules.add(rule); selected = rule; expandedCandidate = null;
        list(); bind(false); modified();
    }

    private void copyPackage(String packId) {
        if (packId == null || busy) return;
        Config snapshot = draft.copy();
        String copySuffix = I18n.text(" 副本");
        busy = true; root.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.disabled);
        message(I18n.text("Copying music pack..."));
        worker.submit(() -> {
            Config copied = null; Exception failure = null;
            try { copied = host.copyPackage(snapshot, packId, copySuffix); host.persist(copied); }
            catch (Exception ex) { failure = ex; }
            final Config completed = copied; final Exception completedFailure = failure;
            completions.add(() -> {
                busy = false; root.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.childrenOnly);
                if (completedFailure != null) { message(I18n.error(completedFailure.getMessage())); return; }
                draft = completed; draft.refreshPackMembership(); draft.validate();
                host.apply(draft.copy()); host.persistenceCompleted(draft);
                MusicPack created = null;
                for (MusicPack item : draft.packs) if (!snapshot.packs.stream().anyMatch(old -> old.id.equals(item.id))) created = item;
                if (created == null) { message(I18n.text("Could not copy the music pack")); return; }
                activePackId = created.id; expandedPackages.add(created.id); selected = null;
                for (Rule rule : draft.rules) if (created.id.equals(rule.packId)) { selected = rule; break; }
                persisted = new com.google.gson.Gson().toJson(draft);
                list(); bind(false); message(I18n.text("Music pack copied as an independent copy"));
            });
        });
    }

    private void copyRule(Rule source) {
        if (source == null || busy) return;
        MusicPack owner = draft.pack(source.packId);
        Rule copy = RuleCopies.copy(source, draft.rules); copy.packId = source.packId;
        copy.upstreamRuleId = null;
        if (owner != null && (owner.source == MusicPack.Source.SUBSCRIBED || owner.localFolder != null && !owner.localFolder.isEmpty())) {
            copy.upstreamRuleId = copy.id;
            copy.id = battleaudio.storage.MusicPackages.stableRuleId(owner.id, copy.upstreamRuleId);
        }
        draft.rules.add(copy); selected = copy; activePackId = copy.packId;
        if (activePackId != null) expandedPackages.add(activePackId);
        expandedCandidate = null; list(); bind(false); modified();
    }

    private void deleteRule(Rule rule) {
        if (rule == null || busy) return;
        draft.rules.remove(rule);
        for (Rule other : draft.rules) {
            other.pauseWhenRuleIds.removeIf(id -> id.equals(rule.id));
            other.prerequisiteRuleIds.removeIf(id -> id.equals(rule.id));
        }
        if (selected == rule) selected = null;
        activePackId = rule.packId;
        list(); bind(false); modified();
    }

    private void moveRule(Rule rule, String destinationId) {
        if (rule == null || busy) return;
        MusicPack destination = destinationId == null ? null : draft.pack(destinationId);
        if (destinationId != null && destination == null) return;
        for (Rule other : draft.rules) {
            boolean linked = rule.pauseWhenRuleIds.contains(other.id) || rule.prerequisiteRuleIds.contains(other.id)
                    || other.pauseWhenRuleIds.contains(rule.id) || other.prerequisiteRuleIds.contains(rule.id);
            MusicPack otherPack = draft.pack(other.packId);
            if (linked && !java.util.Objects.equals(other.packId, destinationId)
                    && (destination != null && (destination.source == MusicPack.Source.SUBSCRIBED || destination.localFolder != null && !destination.localFolder.isEmpty())
                    || otherPack != null && (otherPack.source == MusicPack.Source.SUBSCRIBED || otherPack.localFolder != null && !otherPack.localFolder.isEmpty()))) {
                message(I18n.text("Remove cross-package rule links before moving this rule.")); return;
            }
        }
        if (destination != null && (destination.source == MusicPack.Source.SUBSCRIBED || destination.localFolder != null && !destination.localFolder.isEmpty())) {
            String before = rule.id;
            rule.upstreamRuleId = java.util.UUID.randomUUID().toString();
            rule.id = battleaudio.storage.MusicPackages.stableRuleId(destination.id, rule.upstreamRuleId);
            for (Rule other : draft.rules) {
                java.util.Collections.replaceAll(other.pauseWhenRuleIds, before, rule.id);
                java.util.Collections.replaceAll(other.prerequisiteRuleIds, before, rule.id);
            }
        } else {
            if (draft.pack(rule.packId) != null) {
                String before = rule.id;
                rule.id = java.util.UUID.randomUUID().toString();
                for (Rule other : draft.rules) {
                    java.util.Collections.replaceAll(other.pauseWhenRuleIds, before, rule.id);
                    java.util.Collections.replaceAll(other.prerequisiteRuleIds, before, rule.id);
                }
            }
            rule.upstreamRuleId = null;
        }
        rule.packId = destination == null ? null : destination.id;
        activePackId = rule.packId; selected = rule;
        if (activePackId != null) expandedPackages.add(activePackId);
        draft.refreshPackMembership(); list(); bind(false); modified();
    }

    private void removePackageToRoot(MusicPack pack) {
        if (pack == null || pack.source == MusicPack.Source.SUBSCRIBED || busy) return;
        Map<String, String> ids = new LinkedHashMap<>();
        for (Rule rule : draft.rules) if (pack.id.equals(rule.packId)) ids.put(rule.id, java.util.UUID.randomUUID().toString());
        for (Rule rule : draft.rules) {
            for (Map.Entry<String, String> entry : ids.entrySet()) {
                java.util.Collections.replaceAll(rule.pauseWhenRuleIds, entry.getKey(), entry.getValue());
                java.util.Collections.replaceAll(rule.prerequisiteRuleIds, entry.getKey(), entry.getValue());
            }
            if (pack.id.equals(rule.packId)) {
                rule.id = ids.get(rule.id); rule.packId = null; rule.upstreamRuleId = null;
            }
        }
        draft.packs.remove(pack); expandedPackages.remove(pack.id);
        if (pack.id.equals(activePackId)) activePackId = null;
        draft.refreshPackMembership(); list(); bind(false); modified();
    }

    private void deletePackage(MusicPack pack) {
        if (pack == null || pack.source == MusicPack.Source.SUBSCRIBED || busy) return;
        if (!flushPendingSave()) return;
        Config before = draft.copy();
        String packId = pack.id, localFolder = pack.localFolder;
        Set<String> removedIds = new HashSet<>();
        for (Rule rule : draft.rules) if (pack.id.equals(rule.packId)) removedIds.add(rule.id);
        draft.rules.removeIf(rule -> pack.id.equals(rule.packId));
        for (Rule rule : draft.rules) {
            rule.pauseWhenRuleIds.removeIf(removedIds::contains);
            rule.prerequisiteRuleIds.removeIf(removedIds::contains);
        }
        draft.packs.remove(pack); expandedPackages.remove(pack.id);
        if (pack.id.equals(activePackId)) activePackId = null;
        selected = null; expandedCandidate = null;
        try {
            draft.refreshPackMembership(); draft.validate();
            final Config snapshot = draft.copy();
            busy = true; root.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.disabled);
            message(I18n.text("Deleting music pack..."));
            worker.submit(() -> {
                Exception failure = null;
                try { host.deletePackage(snapshot, packId, localFolder); }
                catch (Exception ex) { failure = ex; }
                final Exception completedFailure = failure;
                completions.add(() -> {
                    busy = false; root.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.childrenOnly);
                    if (completedFailure != null) {
                        draft = before; activePackId = before.packs.isEmpty() ? null : before.packs.get(0).id;
                        selected = before.rules.isEmpty() ? null : before.rules.get(0);
                        draft.refreshPackMembership(); list(); bind(false);
                        message(I18n.error(completedFailure.getMessage()));
                        return;
                    }
                    host.apply(snapshot.copy()); host.persistenceCompleted(snapshot);
                    persisted = new com.google.gson.Gson().toJson(snapshot);
                    list(); bind(false); message("");
                });
            });
        } catch (Exception ex) {
            draft = before; activePackId = before.packs.isEmpty() ? null : before.packs.get(0).id;
            selected = before.rules.isEmpty() ? null : before.rules.get(0);
            draft.refreshPackMembership(); list(); bind(false); message(I18n.error(ex.getMessage()));
        }
    }

    private void savePackageToggle(MusicPack pack, boolean enabled) {
        boolean previous = pack.enabled;
        if (previous == enabled) return;
        pack.enabled = enabled;
        try {
            Config snapshot = draft.copy(); snapshot.refreshPackMembership(); snapshot.validate();
            Config rollback = snapshot.copy(); MusicPack old = rollback.pack(pack.id); if (old != null) old.enabled = previous;
            host.applyPackageToggle(snapshot.copy(), pack.id);
            scheduleSave(rollback);
            message("");
        } catch (Exception ex) {
            pack.enabled = previous;
            message(I18n.error(ex.getMessage()));
            list();
        }
    }

    private void exportPackage(String packId) {
        activePackId = packId;
        exportActivePackage();
    }

    private void exportActivePackage() {
        if (activePackId == null || busy) return;
        if (!invalidInputs.isEmpty()) { message(I18n.text("Please fix the highlighted fields before exporting the music pack.")); return; }
        final String packId = activePackId;
        final Config snapshot;
        try {
            draft.refreshPackMembership();
            draft.validate();
            snapshot = draft.copy();
        } catch (Exception ex) {
            message(I18n.error(ex.getMessage()));
            return;
        }
        if (!flushPendingSave()) return;
        busy = true;
        message(I18n.text("Preparing Workshop export..."));
        worker.submit(() -> {
            Path exported = null;
            Exception failure = null;
            try { exported = host.exportPackage(snapshot, packId); }
            catch (Exception ex) { failure = ex; }
            final Path completedPath = exported;
            final Exception completedFailure = failure;
            completions.add(() -> {
                busy = false;
                if (completedFailure != null) { message(I18n.error(completedFailure.getMessage())); return; }
                String result = I18n.text("Exported (not uploaded):") + completedPath.toAbsolutePath().normalize();
                try {
                    if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(completedPath.toFile());
                } catch (Exception openFailure) {
                    result += "\n" + I18n.text("Could not open the folder automatically; copy the path above.");
                }
                message(result);
            });
        });
    }

    private String uniqueRuleName(String base) {
        java.util.List<String> names = new ArrayList<>(); for (Rule rule : draft.rules) names.add(rule.name);
        return RuleCopies.uniqueName(base, names);
    }

    private void bind(boolean preserveScroll) {
        syncStopView(); activeStopOwner = null; activeStopView = null;
        float scrollY = preserveScroll ? editorScroll.getScrollY() : 0;
        boolean previousBuilding = building; building = true;
        SearchMultiSelect.dismiss(stage);
        editor.clear(); numericFields.clear(); invalidInputs.clear();
        if (selected == null) {
            MusicPack pack = draft.pack(activePackId);
            if (pack != null) packageDetails(pack);
            else editor.add(label(error == null ? I18n.text("新建或选择一条规则") : error)).colspan(2).left().row();
            globalSettings();
            building = previousBuilding;
            return;
        }
        Rule rule = selected;
        if (rule.packId != null) activePackId = rule.packId;
        MusicPack owningPack = draft.pack(rule.packId);
        if (owningPack != null) packageDetails(owningPack);
        TextField name = new TextField(rule.name, skin);
        name.setName("audio-name");
        name.addListener(new ChangeListener() { public void changed(ChangeEvent event, Actor actor) {
            rule.name = name.getText(); TextButton row = ruleRows.get(rule.id); if (row != null) row.setText(rule.name); modified();
        }});
        editor.add(label(I18n.text("名称"))).width(embedded ? 320 : 360).minWidth(embedded ? 240 : 300).left().padRight(12); editor.add(name).minWidth(0).growX().height(44).row();
        editor.add(check(I18n.text("启用"), rule.enabled, value -> rule.enabled = value)).colspan(2).left().padTop(12).row();
        candidateList(rule);
        TextField probability = number(Math.round(rule.triggerProbability * 1000000.0) / 10000.0, false, 100,
                next -> rule.triggerProbability = (float) (next / 100.0));
        probability.setName("trigger-probability"); row(I18n.text("触发概率 (%)"), probability);
        SelectBox<String> randomMode = select(new String[]{I18n.text("完全随机"), I18n.text("避免连续重复")}, rule.randomMode.ordinal(), index -> rule.randomMode = Config.RandomMode.values()[index]);
        randomMode.setName("random-mode"); row(I18n.text("随机方式"), randomMode);
        SelectBox<String> retriggerMode = select(new String[]{I18n.text("停止当前音频并重新抽选"), I18n.text("忽略本次触发"), I18n.text("保留当前音频并叠加")}, rule.retriggerMode.ordinal(), index -> rule.retriggerMode = Config.RetriggerMode.values()[index]);
        retriggerMode.setName("retrigger-mode"); row(I18n.text("再次触发"), retriggerMode);
        pauseRuleSelection(rule);
        prerequisiteRuleSelection(rule);
        conditionEditor(rule, I18n.text("触发条件"), "trigger-");
        section(I18n.text("播放"));
        SelectBox<String> playbackMode = select(new String[] {I18n.text("单次播放"), I18n.text("有限循环"), I18n.text("无限循环")}, rule.playbackMode.ordinal(), index -> {
            rule.playbackMode = Config.PlaybackMode.values()[index]; completions.add(() -> bind(true));
        });
        playbackMode.setName("playback-mode"); row(I18n.text("播放方式"), playbackMode);
        if (rule.playbackMode == Config.PlaybackMode.LIMITED) {
            TextField count = number(rule.loopCount, true, value -> rule.loopCount = (int)(double)value);
            count.setName("loop-count"); row(I18n.text("总播放次数（含首次）"), count);
        }
        stopConditions(rule);
        section(I18n.text("播放持续与跨房间"));
        String[] behaviorNames = {I18n.text("当前房间结束时停止"), I18n.text("跨房间连续播放（保持当前进度）"), I18n.text("房间结束停止；下一场战斗开始后重新播放"),
                I18n.text("房间结束停止；下一场战斗首次受伤后重新播放"), I18n.text("不自动停止（直到片段/循环次数结束或主动停止）")};
        SelectBox<String> behavior = select(behaviorNames, rule.roomBehavior.ordinal(), index -> rule.roomBehavior = Config.RoomBehavior.values()[index]);
        behavior.setName("room-behavior");
        editor.add(behavior).colspan(2).minWidth(0).growX().height(52).row();
        Label explanation = label(I18n.text("跨房间不改变播放次数。首次受伤指实际扣除生命值。胜利音频从胜利时开始，离开该房间才按此设置处理。"));
        explanation.setWrap(true); editor.add(explanation).colspan(2).minWidth(0).growX().padTop(10).row();
        globalSettings();
        editor.invalidateHierarchy(); editorScroll.layout(); editorScroll.setScrollY(scrollY);
        building = previousBuilding;
    }
    private void sceneFilter(Rule rule,Trigger trigger) {
        Config.EventFilter filter=rule.filter(trigger);
        boolean event=trigger==Trigger.EVENT_OPEN || trigger==Trigger.EVENT_OPTION_COMPLETED;
        java.util.List<FilterOptions.Option> options = event ? FilterOptions.events() : trigger==Trigger.ACT_ENTER
                ? FilterOptions.fixed(new String[][]{{"Exordium",I18n.text("第一章节")},{"TheCity",I18n.text("第二章节")},
                        {"TheBeyond",I18n.text("第三章节")},{"TheEnding",I18n.text("终幕")}})
                : FilterOptions.fixed(new String[][]{{"MonsterRoom",I18n.text("普通战斗房间")},{"MonsterRoomElite",I18n.text("精英战斗房间")},
                        {"MonsterRoomBoss",I18n.text("首领战斗房间")},{"EventRoom",I18n.text("事件房间")},{"ShopRoom",I18n.text("商店")},
                        {"RestRoom",I18n.text("休息处")},{"TreasureRoom",I18n.text("宝箱房间")}});
        String current=event?filter.sourceId:filter.targetId;
        idChoice(event?I18n.text("事件"):trigger==Trigger.ACT_ENTER?I18n.text("章节"):I18n.text("房间类型"), "scene-id-"+trigger.name(),
                current, true, options, next->{
                    rule.filters.put(trigger,filter); if(event) filter.sourceId=next; else filter.targetId=next;
                });
        if(trigger==Trigger.EVENT_OPTION_COMPLETED) {
            idChoice(I18n.text("已完成的事件选项"), "event-option", filter.previousState, true,
                    FilterOptions.fixed(new String[][]{{"0",I18n.text("恢复生命（大鱼）")},{"1",I18n.text("增加最大生命（大鱼）")}}), next->{
                        rule.filters.put(trigger,filter); filter.previousState=next;
                    });
        }
        if(trigger==Trigger.ACT_ENTER) {
            optionalLimit(rule, trigger, I18n.text("限制最低章节序号"), I18n.text("章节序号至少"), I18n.text("例如 2 表示从第二章节开始。"),
                    filter.minCurrentValueEnabled || filter.minCurrentValue>0, filter.minCurrentValue,
                    enabled->{filter.minCurrentValueEnabled=enabled;if(!enabled)filter.minCurrentValue=0;rule.filters.put(trigger,filter);},
                    value->{filter.minCurrentValueEnabled=true;filter.minCurrentValue=value;rule.filters.put(trigger,filter);});
        }
    }
    private void evokeFilter(Rule rule) {
        Trigger trigger=Trigger.EVOKE_ORB; Config.EventFilter filter=rule.filter(trigger);
        String[] causes={"","ACTIVE","OVERFLOW"};
        SelectBox<String> cause=select(new String[]{I18n.text("任意激发"),I18n.text("主动激发"),I18n.text("满槽生成挤出")},
                Math.max(0,Arrays.asList(causes).indexOf(filter.evokeCause)),next->{rule.filters.put(trigger,filter);filter.evokeCause=causes[next];});
        cause.setName("evoke-cause"); row(I18n.text("激发原因"),cause);
        TextField position=optionalInteger(filter.orbPosition,value->{rule.filters.put(trigger,filter);filter.orbPosition=value;});
        position.setName("orb-position"); row(I18n.text("激发前槽位（1 起，空为任意）"),position);
    }
    private void cardFilter(Rule rule, Trigger trigger) {
        Config.EventFilter filter = rule.filter(trigger);
        idChoice(I18n.text("卡牌"), "card-id-" + trigger.name(), filter.cardId, true, FilterOptions.cards(), next->{
            rule.filters.put(trigger,filter); filter.cardId=next;
        });
        String[] types={I18n.text("任意牌型"),I18n.text("攻击"),I18n.text("技能"),I18n.text("能力"),I18n.text("状态"),I18n.text("诅咒")};
        String[] typeIds={"","ATTACK","SKILL","POWER","STATUS","CURSE"};
        int selected=Math.max(0,Arrays.asList(typeIds).indexOf(filter.cardType));
        row(I18n.text("卡牌类型"), select(types,selected,index->{rule.filters.put(trigger,filter); filter.cardType=typeIds[index];}));
        if (trigger==Trigger.PLAY_ATTACK || trigger==Trigger.PLAY_SKILL || trigger==Trigger.PLAY_POWER) {
            String[] costs={I18n.text("任意费用"),I18n.text("X 费"),I18n.text("0 费"),I18n.text("1 费"),I18n.text("2 费"),I18n.text("3 费"),I18n.text("4 费"),I18n.text("5 费")};
            int[] values={Integer.MIN_VALUE,-1,0,1,2,3,4,5};
            int costIndex=0; for(int i=1;i<values.length;i++) if(filter.cardCost==values[i]) costIndex=i;
            row(I18n.text("实际费用"),select(costs,costIndex,index->{rule.filters.put(trigger,filter);filter.cardCost=values[index];}));
        }
        String[] states={I18n.text("任意"),I18n.text("是"),I18n.text("否")};
        int upgraded=filter.upgraded==null?0:filter.upgraded?1:2;
        row(I18n.text("已升级"),select(states,upgraded,index->{rule.filters.put(trigger,filter);filter.upgraded=index==0?null:index==1;}));
        if (trigger==Trigger.PLAY_ATTACK || trigger==Trigger.PLAY_SKILL || trigger==Trigger.PLAY_POWER) {
            int exhaust=filter.exhaust==null?0:filter.exhaust?1:2;
            row(I18n.text("会消耗"),select(states,exhaust,index->{rule.filters.put(trigger,filter);filter.exhaust=index==0?null:index==1;}));
        }
        if (trigger==Trigger.EXHAUST_CARD) {
            String[] piles={I18n.text("任意来源"),I18n.text("手牌"),I18n.text("抽牌堆"),I18n.text("弃牌堆"),I18n.text("暂存区")};
            String[] ids={"","HAND","DRAW","DISCARD","LIMBO"};
            int pileIndex=Math.max(0,Arrays.asList(ids).indexOf(filter.sourcePile));
            row(I18n.text("来源牌堆"),select(piles,pileIndex,index->{rule.filters.put(trigger,filter);filter.sourcePile=ids[index];}));
        }
        if(trigger==Trigger.CREATE_CARD) {
            String[] piles={I18n.text("任意目标"),I18n.text("手牌"),I18n.text("抽牌堆"),I18n.text("弃牌堆")};
            String[] ids={"","HAND","DRAW","DISCARD"};
            int pileIndex=Math.max(0,Arrays.asList(ids).indexOf(filter.targetPile));
            row(I18n.text("进入牌堆"),select(piles,pileIndex,index->{rule.filters.put(trigger,filter);filter.targetPile=ids[index];}));
        }
        if(trigger==Trigger.PLAY_ATTACK || trigger==Trigger.PLAY_SKILL || trigger==Trigger.PLAY_POWER
                || trigger==Trigger.DRAW_CARD || trigger==Trigger.DISCARD_CARD || trigger==Trigger.EXHAUST_CARD) {
            row(I18n.text("已观察到临时生成"),select(new String[]{I18n.text("任意"),I18n.text("是")},Boolean.TRUE.equals(filter.temporary)?1:0,
                    next->{rule.filters.put(trigger,filter);filter.temporary=next==1?Boolean.TRUE:null;}));
        }
        if (trigger==Trigger.PLAY_ATTACK || trigger==Trigger.PLAY_SKILL || trigger==Trigger.PLAY_POWER
                || trigger==Trigger.DRAW_CARD || trigger==Trigger.DISCARD_CARD || trigger==Trigger.EXHAUST_CARD
                || trigger==Trigger.RETURN_DISCARD_CARD || trigger==Trigger.CREATE_CARD)
            cadenceFilter(rule,trigger,true,true,I18n.text("每张牌最多触发一次"));
    }
    private void amountFilter(Rule rule, Trigger trigger) {
        Config.EventFilter filter=rule.filter(trigger);
        boolean amountUseful=trigger!=Trigger.ATTACK_MULTI_KILL && trigger!=Trigger.LOW_HEALTH_FIRST_BLOCK;
        if(amountUseful) optionalLimit(rule,trigger,I18n.text("限制单次变化"),amountLabel(trigger),
                I18n.text("只计算一次结算本身；未开启时不限制最低数值。"),
                filter.minAmountEnabled || filter.minAmount>0,filter.minAmount,
                enabled->{filter.minAmountEnabled=enabled;if(!enabled)filter.minAmount=0;rule.filters.put(trigger,filter);},
                value->{filter.minAmountEnabled=true;filter.minAmount=value;rule.filters.put(trigger,filter);});
        if(supportsTurnTotal(trigger)) optionalLimit(rule,trigger,I18n.text("限制本回合累计"),I18n.text("本回合累计至少"),
                I18n.text("同一玩家回合内的实际变化相加；它不等同于单次变化。"),
                filter.minTurnTotalEnabled || filter.minTurnTotal>0,filter.minTurnTotal,
                enabled->{filter.minTurnTotalEnabled=enabled;if(!enabled)filter.minTurnTotal=0;rule.filters.put(trigger,filter);},
                value->{filter.minTurnTotalEnabled=true;filter.minTurnTotal=value;rule.filters.put(trigger,filter);});
        if (trigger==Trigger.GAIN_ENERGY || trigger==Trigger.GAIN_BLOCK || trigger==Trigger.GAIN_GOLD) {
            optionalLimit(rule,trigger,I18n.text("限制当前数值"),I18n.text("当前值至少达到"),I18n.text("检查变化完成后的当前总值。"),
                    filter.minCurrentValueEnabled || filter.minCurrentValue>0,filter.minCurrentValue,
                    enabled->{filter.minCurrentValueEnabled=enabled;if(!enabled){filter.minCurrentValue=0;filter.crossingOnly=false;}
                        rule.filters.put(trigger,filter);},
                    value->{filter.minCurrentValueEnabled=true;filter.minCurrentValue=value;rule.filters.put(trigger,filter);});
            if(filter.minCurrentValueEnabled || filter.minCurrentValue>0) row(I18n.text("只在首次跨过数值时"),select(new String[]{I18n.text("否"),I18n.text("是")},filter.crossingOnly?1:0,
                    next->{rule.filters.put(trigger,filter);filter.crossingOnly=next==1;}));
        }
        if(supportsCombatCadence(trigger)) cadenceFilter(rule,trigger,true,true,null);
        if(trigger==Trigger.HP_LOSS || trigger==Trigger.SELF_DAMAGE
                || trigger==Trigger.GAIN_SPECIFIED_POWER || trigger==Trigger.RECEIVE_DEBUFF
                || trigger==Trigger.STRENGTH_INCREASE || trigger==Trigger.STRENGTH_DECREASE
                || trigger==Trigger.DEXTERITY_INCREASE || trigger==Trigger.DEXTERITY_DECREASE
                || trigger==Trigger.POWER_DECREASE || trigger==Trigger.POWER_REMOVED) {
            String[] types={"","PLAYER","ENEMY","UNKNOWN"};
            int selected=Math.max(0,Arrays.asList(types).indexOf(filter.sourceType));
            row(I18n.text("可确认来源"),select(new String[]{I18n.text("任意"),I18n.text("玩家"),I18n.text("敌人"),I18n.text("未知来源")},selected,
                    next->{rule.filters.put(trigger,filter);filter.sourceType=types[next];
                        if(next!=2) filter.sourceId=""; completions.add(()->bind(true));}));
            if("ENEMY".equals(filter.sourceType)) {
                idChoice(I18n.text("来源敌人"), "source-id-"+trigger.name(), filter.sourceId, true, FilterOptions.enemies(), next->{
                    rule.filters.put(trigger,filter); filter.sourceId=next;
                });
            }
        }
    }
    private void enemyFilter(Rule rule, Trigger trigger) {
        Config.EventFilter filter=rule.filter(trigger);
        idChoice(I18n.text("目标敌人"), "target-id-"+trigger.name(), filter.targetId, true, FilterOptions.enemies(), next->{
            rule.filters.put(trigger,filter); filter.targetId=next;
        });
        if (trigger!=Trigger.POISON_KILL) {
            optionalLimit(rule,trigger,I18n.text("限制单次数值"),trigger==Trigger.POISON_DAMAGE?I18n.text("单次实际伤害至少"):trigger==Trigger.POISON_DECREASE?
                            I18n.text("单次实际减少至少"):I18n.text("单次增加层数至少"),I18n.text("未开启时不限制最低数值。"),
                    filter.minAmountEnabled || filter.minAmount>0,filter.minAmount,
                    enabled->{filter.minAmountEnabled=enabled;if(!enabled)filter.minAmount=0;rule.filters.put(trigger,filter);},
                    value->{filter.minAmountEnabled=true;filter.minAmount=value;rule.filters.put(trigger,filter);});
        }
        if(trigger==Trigger.APPLY_POISON || trigger==Trigger.APPLY_ENEMY_POWER) {
            optionalLimit(rule,trigger,I18n.text("限制目标当前层数"),I18n.text("目标当前层数至少"),I18n.text("检查本次变化完成后的层数。"),
                    filter.minCurrentValueEnabled || filter.minCurrentValue>0,filter.minCurrentValue,
                    enabled->{filter.minCurrentValueEnabled=enabled;if(!enabled){filter.minCurrentValue=0;filter.crossingOnly=false;}
                        rule.filters.put(trigger,filter);},
                    value->{filter.minCurrentValueEnabled=true;filter.minCurrentValue=value;rule.filters.put(trigger,filter);});
            if(filter.minCurrentValueEnabled || filter.minCurrentValue>0) row(I18n.text("只在首次跨过层数时"),select(new String[]{I18n.text("否"),I18n.text("是")},filter.crossingOnly?1:0,
                    next->{rule.filters.put(trigger,filter);filter.crossingOnly=next==1;}));
        }
        cadenceFilter(rule,trigger,true,true,I18n.text("每个敌人最多触发一次"));
    }
    private void potionFilter(Rule rule,Trigger trigger) {
        Config.EventFilter filter=rule.filter(trigger);
        idChoice(I18n.text("药水"), "potion-id-"+trigger.name(), filter.sourceId, true, FilterOptions.potionLibrary(), next->{
            rule.filters.put(trigger,filter); filter.sourceId=next;
        });
    }
    private void relicFilter(Rule rule,Trigger trigger) {
        Config.EventFilter filter=rule.filter(trigger);
        idChoice(I18n.text("遗物"), "relic-id-"+trigger.name(), filter.sourceId, true, FilterOptions.relics(), next->{
            rule.filters.put(trigger,filter); filter.sourceId=next;
        });
    }
    private void killFilter(Rule rule) {
        Config.EventFilter filter=rule.filters.getOrDefault(Trigger.KILL_ENEMY,new Config.EventFilter());
        idChoice(I18n.text("敌人"), "kill-enemy-id", filter.targetId, true, FilterOptions.enemies(), next->{
            rule.filters.put(Trigger.KILL_ENEMY,filter); filter.targetId=next;
        });
        String[] types={"","NORMAL","ELITE","BOSS"};
        int selected=Math.max(0,Arrays.asList(types).indexOf(filter.enemyType));
        row(I18n.text("敌人类型"),select(new String[]{I18n.text("任意"),I18n.text("普通敌人"),I18n.text("精英"),I18n.text("首领")},selected,
                next->{rule.filters.put(Trigger.KILL_ENEMY,filter);filter.enemyType=types[next];}));
        row(I18n.text("本场最后一只"),select(new String[]{I18n.text("任意"),I18n.text("是"),I18n.text("否")},filter.lastEnemy==null?0:filter.lastEnemy?1:2,
                next->{rule.filters.put(Trigger.KILL_ENEMY,filter);filter.lastEnemy=next==0?null:next==1;}));
        cadenceFilter(rule,Trigger.KILL_ENEMY,true,true,I18n.text("每个敌人最多触发一次"));
    }
    private void lowEnemyFilter(Rule rule) {
        Config.EventFilter filter=rule.filters.getOrDefault(Trigger.ENEMY_LOW_HEALTH,new Config.EventFilter());
        idChoice(I18n.text("敌人"), "low-enemy-id", filter.targetId, true, FilterOptions.enemies(), next->{
            rule.filters.put(Trigger.ENEMY_LOW_HEALTH,filter); filter.targetId=next;
        });
        String[] types={"","NORMAL","ELITE","BOSS"};
        row(I18n.text("敌人类型"),select(new String[]{I18n.text("任意"),I18n.text("普通敌人"),I18n.text("精英"),I18n.text("首领")},
                Math.max(0,Arrays.asList(types).indexOf(filter.enemyType)),next->{
                    rule.filters.put(Trigger.ENEMY_LOW_HEALTH,filter);filter.enemyType=types[next];}));
        cadenceFilter(rule,Trigger.ENEMY_LOW_HEALTH,true,true,I18n.text("每个敌人最多触发一次"));
    }
    private void stanceFilter(Rule rule,Trigger trigger) {
        Config.EventFilter filter=rule.filter(trigger);
        String[] ids={"","Neutral","Calm","Wrath","Divinity"};
        String[] names={I18n.text("任意"),I18n.text("普通"),I18n.text("平静"),I18n.text("愤怒"),I18n.text("神格")};
        int from=Math.max(0,Arrays.asList(ids).indexOf(filter.previousState));
        int to=Math.max(0,Arrays.asList(ids).indexOf(filter.nextState));
        SelectBox<String> previous=select(names,from,next->{rule.filters.put(trigger,filter);filter.previousState=ids[next];});
        previous.setName("previous-state-"+trigger.name()); row(I18n.text("原姿态"),previous);
        SelectBox<String> nextState=select(names,to,next->{rule.filters.put(trigger,filter);filter.nextState=ids[next];});
        nextState.setName("next-state-"+trigger.name()); row(I18n.text("新姿态"),nextState);
        cadenceFilter(rule,trigger,true,true,null);
    }
    private void idChoice(String title,String baseName,String current,boolean allowAny,
            java.util.List<FilterOptions.Option> options,Consumer<String> change) {
        String name = scopedName(baseName);
        Set<String> ids = battleaudio.model.IdSelection.parse(current);
        boolean known = !ids.isEmpty() && ids.stream().allMatch(id -> optionIndex(options,id)>=0);
        int mode=advancedInputs.contains(name)?(allowAny?2:1):current.isEmpty()
                ?(listInputs.contains(name) && allowAny?1:0):known?(allowAny?1:0):(allowAny?2:1);
        String[] modes=allowAny
                ? new String[]{I18n.text("任意")+title,I18n.text("从列表选择"),I18n.text("手动输入内部编号（高级）")}
                : new String[]{I18n.text("从列表选择"),I18n.text("手动输入内部编号（高级）")};
        SelectBox<String> choice=select(modes,mode,next->{
            int listMode=allowAny?1:0;
            int manualMode=allowAny?2:1;
            if(next==manualMode)advancedInputs.add(name);else advancedInputs.remove(name);
            if(next==listMode)listInputs.add(name);else listInputs.remove(name);
            if(allowAny && next==0) change.accept("");

            completions.add(()->bind(true));
        });
        choice.setName(name+"-mode"); row(title,choice);
        int listMode=allowAny?1:0, manualMode=allowAny?2:1;
        if(mode==listMode) optionList(title,name,current,options,change);
        else if(mode==manualMode) {
            TextField manual=new TextField(current,skin); manual.setName(name);
            manual.setMessageText(I18n.text("仅在列表中没有该项目时填写"));
            manual.addListener(new ChangeListener(){public void changed(ChangeEvent event,Actor actor){
                change.accept(manual.getText().trim()); modified();
            }});
            row(I18n.text("内部编号（高级）"),manual);
        }
    }
    private void optionList(String title,String name,String current,java.util.List<FilterOptions.Option> options,Consumer<String> change) {
        SearchMultiSelect chooser=new SearchMultiSelect(skin,title,scopedName(name),options,
                battleaudio.model.IdSelection.parse(current),ids->{
                    change.accept(battleaudio.model.IdSelection.encode(ids)); modified();
                });
        row(I18n.text("可选")+title,chooser);
    }
    private void multiPowerChoice(Rule rule) {
        if(rule.manualPowerIds) {
            TextField ids=new TextField(String.join(", ",rule.powerIds),skin); ids.setName(scopedName("power-ids-manual"));
            ids.setMessageText(I18n.text("多个内部编号用英文逗号分隔"));
            ids.addListener(new ChangeListener(){public void changed(ChangeEvent event,Actor actor){
                rule.powerIds=Arrays.stream(ids.getText().split(",")).map(String::trim).filter(text->!text.isEmpty()).collect(Collectors.toList());
                modified();
            }}); row(I18n.text("内部编号（高级）"),ids);
            return;
        }
        SearchMultiSelect chooser=new SearchMultiSelect(skin,I18n.text("状态"),scopedName("power-ids"),FilterOptions.powers(),
                new LinkedHashSet<>(rule.powerIds),ids->{rule.powerIds=new ArrayList<>(ids);modified();});
        row(I18n.text("可选状态"),chooser);
    }
    private static int optionIndex(java.util.List<FilterOptions.Option> options,String id) {
        for(int i=0;i<options.size();i++)if(options.get(i).id.equals(id))return i;
        return -1;
    }
    private void optionalLimit(Rule rule,Trigger trigger,String toggle,String fieldLabel,String explanation,
            boolean enabled,int value,Consumer<Boolean> enabledChange,Consumer<Integer> valueChange) {
        CheckBox box=check(toggle,enabled,next->{enabledChange.accept(next);completions.add(()->bind(true));});
        box.setName(scopedName(limitToggleName(trigger,fieldLabel))); editor.add(box).colspan(2).left().height(42).row();
        if(!enabled)return;
        TextField field=positiveInteger(value<=0?1:value,valueChange); field.setName(limitFieldName(trigger,fieldLabel)); row(fieldLabel,field);
        note(explanation);
    }
    private static String limitFieldName(Trigger trigger,String label) {
        if(label.contains(I18n.text("单次")))return "min-amount-"+trigger.name();
        if(label.contains(I18n.text("累计")))return "turn-total-"+trigger.name();
        return "current-value-"+trigger.name();
    }
    private static String limitToggleName(Trigger trigger,String label) {
        if(label.contains(I18n.text("单次")))return "min-amount-enabled-"+trigger.name();
        if(label.contains(I18n.text("累计")))return "turn-total-enabled-"+trigger.name();
        return "current-value-enabled-"+trigger.name();
    }
    private TextField positiveInteger(int initial,Consumer<Integer> change) {
        TextField field=new TextField(Integer.toString(initial),skin); numericFields.add(field);
        field.addListener(new ChangeListener(){public void changed(ChangeEvent event,Actor actor){
            try { if(!field.getText().matches("[1-9][0-9]*"))throw new NumberFormatException();
                change.accept(Integer.parseInt(field.getText())); invalidInputs.remove(field); field.setColor(Color.WHITE); modified();
            } catch(NumberFormatException ex){invalidInputs.add(field);field.setColor(Color.SALMON);message(I18n.text("请输入大于 0 的整数"));}
        }}); return field;
    }
    private void cadenceFilter(Rule rule,Trigger trigger,boolean turn,boolean battle,String objectLabel) {
        Config.EventFilter filter=rule.filter(trigger); java.util.List<String> names=new ArrayList<>();
        java.util.List<Config.Cadence> values=new ArrayList<>(); names.add(I18n.text("每次满足都触发"));values.add(Config.Cadence.EVERY);
        if(turn){names.add(I18n.text("每回合最多触发一次"));values.add(Config.Cadence.FIRST_TURN);}
        if(battle){names.add(I18n.text("每场战斗最多触发一次"));values.add(Config.Cadence.FIRST_BATTLE);}
        if(objectLabel!=null){names.add(objectLabel);values.add(Config.Cadence.FIRST_OBJECT);}
        Config.Cadence saved=filter.maxOncePerBattle?Config.Cadence.FIRST_BATTLE
                :filter.maxOncePerTurn?Config.Cadence.FIRST_TURN:filter.cadence;
        int selected=values.indexOf(saved);if(selected<0)selected=0;
        SelectBox<String> cadence=select(names.toArray(new String[0]),selected,next->{
            rule.filters.put(trigger,filter);filter.cadence=values.get(next);filter.maxOncePerTurn=false;filter.maxOncePerBattle=false;
        }); cadence.setName("cadence-"+trigger.name()); row(I18n.text("重复触发"),cadence);
    }
    private static boolean supportsCombatCadence(Trigger trigger) {
        return trigger!=Trigger.GAIN_GOLD && trigger!=Trigger.SPEND_GOLD && trigger!=Trigger.LOW_HEALTH_FIRST_BLOCK;
    }
    private static boolean supportsTurnTotal(Trigger trigger) {
        return supportsCombatCadence(trigger) && trigger!=Trigger.ATTACK_MULTI_KILL;
    }
    private static String amountLabel(Trigger trigger) {
        if(trigger==Trigger.HP_LOSS || trigger==Trigger.SELF_DAMAGE)return I18n.text("单次实际失去生命至少");
        if(trigger==Trigger.BLOCK_LOSS)return I18n.text("单次实际失去格挡至少");
        if(trigger==Trigger.ORB_PASSIVE_DAMAGE || trigger==Trigger.SHIV_DAMAGE || trigger==Trigger.DARK_EVOKE_DAMAGE
                || trigger==Trigger.WRATH_DAMAGE || trigger==Trigger.DIVINITY_DAMAGE || trigger==Trigger.DAMAGE_THRESHOLD)return I18n.text("单次实际伤害至少");
        if(trigger==Trigger.ORB_PASSIVE_BLOCK || trigger==Trigger.GAIN_BLOCK || trigger==Trigger.CALM_GAIN_BLOCK)return I18n.text("单次实际获得格挡至少");
        if(trigger==Trigger.GAIN_ENERGY || trigger==Trigger.ORB_PASSIVE_ENERGY || trigger==Trigger.PLASMA_EVOKE_ENERGY
                || trigger==Trigger.CALM_EXIT_ENERGY)return I18n.text("单次实际获得能量至少");
        return I18n.text("单次实际变化至少");
    }
    private void note(String text) {
        Label note=label(text); note.setWrap(true); note.setFontScale(.78f);
        editor.add(note).colspan(2).minWidth(0).growX().left().padBottom(10).row();
    }
    private void candidateList(Rule rule) {
        section(I18n.text("候选音频"));
        if (rule.candidates.isEmpty()) {
            Label empty = label(I18n.text("尚未添加音频")); empty.setName("empty-candidates");
            editor.add(empty).colspan(2).left().padBottom(12).row();
        }
        for (Candidate audio : rule.candidates) {
            Table item = new Table(); item.top().left(); item.pad(8); item.setBackground(skin.getDrawable("candidate-background"));
            Table candidateHeader = new Table();
            Label title = label(audio.name + (audio.playable() ? "" : I18n.text(" · 无法播放"))); title.setEllipsis(true);
            candidateHeader.add(title).minWidth(0).growX().left().padRight(8);
            TextButton edit = button(audio.id.equals(expandedCandidate) ? I18n.text("收起") : I18n.text("编辑"), () -> {
                expandedCandidate = audio.id.equals(expandedCandidate) ? null : audio.id; bind(true); message("");
            }); edit.setName("edit-candidate-" + audio.id);
            candidateHeader.add(edit).width(Math.max(85,edit.getPrefWidth())).height(42);
            item.add(candidateHeader).colspan(2).minWidth(0).growX().row();
            CheckBox participating = check(I18n.text("参与随机"), audio.participate, value -> audio.participate = value);
            participating.setName("participate-" + audio.id); item.add(participating).colspan(2).left().height(42).row();
            if (audio.id.equals(expandedCandidate)) {
                if (audio.error != null) { Label failed = label(I18n.error(audio.error)); failed.setWrap(true); failed.setColor(Color.SALMON); item.add(failed).colspan(2).growX().row(); }
                Label file = label(audio.sourcePath == null ? I18n.text("尚未选择文件") : audio.sourcePath); file.setEllipsis(true);
                Table fileRow = new Table(); fileRow.add(file).minWidth(0).growX().left().padRight(12);
                fileRow.add(button(I18n.text("更换文件"), () -> { fileTarget = audio; choose(true); })).width(150).height(42);
                item.add(fileRow).colspan(2).minWidth(0).growX().row();
                if (audio.playable()) { position(item, I18n.text("开始"), audio, true); position(item, I18n.text("结束"), audio, false); }
                Table volume = new Table(); Label value = label(Math.round(audio.volume * 100) + "%");
                Slider control = slider(0,100,audio.volume * 100, next -> { audio.volume = next/100; value.setText(Math.round(next) + "%"); });
                control.setName("audio-volume"); volume.add(control).growX().padRight(12); volume.add(value).width(85);
                item.add(label(I18n.text("独立音量"))).width(embedded ? 320 : 360).minWidth(embedded ? 240 : 300).left().padRight(12); item.add(volume).minWidth(0).growX().height(44).row();
                Table background = new Table(); Label retained = label(Math.round(audio.backgroundRatio * 100) + "%");
                Slider retention = slider(0,100,audio.backgroundRatio * 100,next -> {
                    audio.backgroundRatio = next / 100f; retained.setText(Math.round(next) + "%");
                });
                retention.setName("background-ratio"); background.add(retention).growX().padRight(12); background.add(retained).width(85);
                item.add(label(I18n.text("播放时背景音乐保留"))).width(embedded ? 320 : 360).minWidth(embedded ? 240 : 300).left().padRight(12);
                item.add(background).minWidth(0).growX().height(44).row();
                Table previewActions = new Table(); previewActions.left();
                previewActions.add(candidateAction(I18n.text("试听"), () -> {
                    if (!audio.playable() || !valid()) return;
                    Rule preview = copy(rule); preview.candidates.clear(); preview.candidates.add(new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(audio), Candidate.class));
                    host.preview(preview, audio.backgroundRatio);
                })).height(46).padRight(20);
                previewActions.add(candidateAction(I18n.text("停止试听"), host::stopPreview)).height(46);
                item.add(previewActions).colspan(2).left().padTop(12).row();
                Table editActions = new Table(); editActions.left();
                editActions.add(candidateAction(I18n.text("复制音频"), () -> { rule.candidates.add(RuleCopies.copy(audio, rule.candidates)); bind(true); list(); modified(); })).height(46).padRight(40);
                editActions.add(candidateAction(I18n.text("删除音频"), () -> {
                    rule.candidates.remove(audio); expandedCandidate=null; bind(true); list(); modified();
                })).height(46);
                item.add(editActions).colspan(2).left().padTop(14).row();
            }
            editor.add(item).colspan(2).minWidth(0).growX().padBottom(8).row();
        }
        // Keep the candidate section visually separate from the rule-level playback controls.
        editor.add(button(I18n.text("添加候选音频"), () -> choose(false))).colspan(2).left().height(44).padBottom(28).row();
    }
    private TextButton candidateAction(String title, Runnable action) {
        TextButton control = button(title, action);
        control.getLabelCell().padLeft(16).padRight(16);
        return control;
    }
    private void globalSettings() {
        section(I18n.text("全局设置"));
        editor.add(footer).colspan(2).minWidth(0).growX().row();
    }

    private void section(String title) { Label label = label(title); label.setColor(0.84f, 0.76f, 0.52f, 1); editor.add(label).colspan(2).left().padTop(22).padBottom(12).row(); }
    private void row(String name, Actor value) {
        if (stopEditor) prefixStopControls(value);
        Label title=label(name); title.setWrap(true); title.setAlignment(Align.left);
        editor.add(title).width(embedded ? 320 : 360).minWidth(embedded ? 240 : 300).left().padRight(12).minHeight(60);
        editor.add(value).minWidth(0).growX().height(50).padBottom(10).row();
    }
    private void prefixStopControls(Actor actor) {
        if (actor.getName() != null && !actor.getName().startsWith("stop-")) actor.setName("stop-" + actor.getName());
        if (actor instanceof com.badlogic.gdx.scenes.scene2d.Group)
            for (Actor child : ((com.badlogic.gdx.scenes.scene2d.Group) actor).getChildren()) prefixStopControls(child);
    }
    private String scopedName(String name) { return stopEditor && !name.startsWith("stop-") ? "stop-" + name : name; }
    private SelectBox<String> select(String[] options, int selected, Consumer<Integer> change) {
        SelectBox<String> select = new SelectBox<String>(skin) {
            @Override public float getPrefWidth() { return Math.min(440,super.getPrefWidth()); }
        }; select.setItems(options); select.setSelectedIndex(selected);
        select.addListener(new ChangeListener() { public void changed(ChangeEvent event, Actor actor) { change.accept(select.getSelectedIndex()); modified(); } });
        return select;
    }
    private Table healthThreshold(Threshold threshold, String name) {
        Table line = new Table();
        TextField value = number(threshold.value, false, threshold.percent ? 100 : Double.POSITIVE_INFINITY, next -> {
            double previous = threshold.value; threshold.value = next;
            syncStopView();
            try { for (Rule rule : draft.rules) rule.validateHealthStops(); }
            catch (IllegalArgumentException ex) { threshold.value = previous; throw ex; }
        });
        value.setName(name);
        line.add(value).width(145).height(42).padRight(10);
        SelectBox<String> unit = select(new String[] {I18n.text("点"), "%"}, threshold.percent ? 1 : 0, index -> {
            boolean previous = threshold.percent; double previousValue = threshold.value;
            threshold.percent = index == 1;
            if (threshold.percent) threshold.value = Math.min(100, threshold.value);
            syncStopView();
            try { for (Rule rule : draft.rules) rule.validateHealthStops(); }
            catch (IllegalArgumentException ex) {
                threshold.percent = previous; threshold.value = previousValue;
                invalidInputs.add(value); value.setColor(Color.SALMON);
                message(I18n.error(ex.getMessage()) + I18n.text("；当前输入尚未保存")); return;
            }
            completions.add(() -> bind(true));
        });
        unit.setName(name + "-unit"); line.add(unit).width(110).height(42);
        return line;
    }
    private void position(Table target, String name, Candidate rule, boolean start) {
        Table line = new Table();
        Label number = label(String.format(java.util.Locale.ROOT, I18n.text("%.2f 秒"), start ? rule.start : rule.end));
        Slider slider = slider(0, 10000, (float) ((start ? rule.start : rule.end) / rule.duration * 10000), value -> {
            double time = value / 10000.0 * rule.duration;
            if (start) rule.start = Math.min(time, rule.end - rule.duration / 10000.0);
            else rule.end = Math.max(time, rule.start + rule.duration / 10000.0);
            number.setText(String.format(java.util.Locale.ROOT, I18n.text("%.2f 秒"), start ? rule.start : rule.end));
        });
        slider.setName(start ? "segment-start" : "segment-end");
        slider.addListener(new ChangeListener() { public void changed(ChangeEvent event, Actor actor) {
            float canonical = (float) ((start ? rule.start : rule.end) / rule.duration * 10000);
            if (Math.abs(slider.getValue() - canonical) > 0.1f) slider.setValue(canonical);
        }});
        line.add(slider).growX().padRight(15); line.add(number).width(145);
        target.add(label(name)).width(embedded ? 190 : 230).left().height(44); target.add(line).minWidth(0).growX().height(44).row();
    }
    private boolean valid() {
        try {
            if (!invalidInputs.isEmpty()) throw new NumberFormatException();
            draft.validate(); return true;
        } catch (Exception ex) { message(ex instanceof NumberFormatException ? I18n.text("请输入有效的血量阈值") : I18n.error(ex.getMessage())); return false; }
    }
    private TextField number(double initial, boolean positiveInteger, Consumer<Double> change) {
        return number(initial, positiveInteger, Double.POSITIVE_INFINITY, change);
    }
    private TextField optionalInteger(int initial, Consumer<Integer> change) {
        TextField field=new TextField(initial==0?"":Integer.toString(initial),skin);
        field.setMessageText(I18n.text("留空表示任意"));
        numericFields.add(field);
        field.addListener(new ChangeListener() { public void changed(ChangeEvent event,Actor actor) {
            try {
                if (!field.getText().isEmpty() && !field.getText().matches("[1-9][0-9]*")) throw new NumberFormatException();
                int value=field.getText().isEmpty()?0:Integer.parseInt(field.getText());
                change.accept(value); invalidInputs.remove(field); field.setColor(Color.WHITE); modified();
            } catch(NumberFormatException ex) {
                invalidInputs.add(field); field.setColor(Color.SALMON); message(I18n.text("请输入大于 0 的整数，或留空表示任意"));
            }
        }});
        return field;
    }
    private TextField number(double initial, boolean positiveInteger, double maximum, Consumer<Double> change) {
        TextField field = new TextField(positiveInteger ? Integer.toString((int)initial) : Double.toString(initial), skin);
        numericFields.add(field);
        field.addListener(new ChangeListener() { public void changed(ChangeEvent event, Actor actor) {
            try {
                String text = field.getText();
                if (!text.matches(positiveInteger ? "[0-9]+" : "[0-9]+(?:\\.[0-9]+)?")) throw new NumberFormatException();
                double value = Double.parseDouble(text);
                if (!Double.isFinite(value) || value < 0 || value > maximum || positiveInteger && (value < 1 || value > Integer.MAX_VALUE)) throw new NumberFormatException();
                change.accept(value); invalidInputs.remove(field); field.setColor(Color.WHITE); modified();
            } catch (NumberFormatException ex) {
                invalidInputs.add(field); field.setColor(Color.SALMON); message(positiveInteger ? I18n.text("请输入正整数；编辑中的内容尚未保存") : maximum == 100 ? I18n.text("请输入 0 到 100 的完整数值；编辑中的内容尚未保存") : I18n.text("请输入完整有效的非负数；编辑中的内容尚未保存"));
            } catch (IllegalArgumentException ex) {
                invalidInputs.add(field); field.setColor(Color.SALMON); message(I18n.error(ex.getMessage()) + I18n.text("；当前输入尚未保存"));
            }
        }});
        return field;
    }
    private void modified() {
        if (building || closed || status == null) return;
        syncStopView();
        if (error != null) return;
        try {
            draft.validate();
            scheduleSave();
        } catch (Exception ex) { message(I18n.error(ex.getMessage())); }
    }

    private void scheduleSave() { scheduleSave(null); }

    private void scheduleSave(Config rollbackOnFailure) {
        if (rollbackOnFailure != null && saveFailureRecovery.isEmpty()) saveFailureRecovery.add(() -> {
            draft = rollbackOnFailure.copy();
            host.apply(draft.copy());
            list(); bind(false);
        });
        long generation = ++saveGeneration;
        if (pendingSave != null) pendingSave.cancel(false);
        pendingSave = saveScheduler.schedule(() -> completions.add(() -> beginScheduledSave(generation)),
                180, TimeUnit.MILLISECONDS);
    }

    private void beginScheduledSave(long generation) {
        if (closed || generation != saveGeneration || error != null) return;
        try {
            Config snapshot = draft.copy();
            snapshot.refreshPackMembership(); snapshot.validate();
            worker.submit(() -> {
                Exception failure = null;
                try { host.persist(snapshot); } catch (Exception ex) { failure = ex; }
                final Exception completedFailure = failure;
                completions.add(() -> finishScheduledSave(snapshot, generation, completedFailure));
            });
        } catch (Exception ex) { message(I18n.error(ex.getMessage())); }
    }

    private void finishScheduledSave(Config snapshot, long generation, Exception failure) {
        if (generation != saveGeneration) return;
        if (failure != null) {
            for (Runnable recovery : new ArrayList<>(saveFailureRecovery)) recovery.run();
            saveFailureRecovery.clear(); pendingSave = null;
            message(I18n.error(failure.getMessage())); return;
        }
        saveFailureRecovery.clear();
        copyPackagePersistence(snapshot);
        host.apply(snapshot.copy()); host.persistenceCompleted(snapshot);
        persisted = new com.google.gson.Gson().toJson(draft);
        pendingSave = null;
        if (invalidInputs.isEmpty()) message("");
    }

    public boolean flushPendingChanges() { return flushPendingSave(); }

    private boolean flushPendingSave() {
        if (closed || draft == null) return true;
        String current = new com.google.gson.Gson().toJson(draft);
        if (pendingSave == null && current.equals(persisted)) return true;
        if (pendingSave != null) pendingSave.cancel(false);
        pendingSave = null; ++saveGeneration;
        try {
            draft.refreshPackMembership(); draft.validate();
            Config snapshot = draft.copy();
            Future<?> write = worker.submit(() -> {
                try { host.persist(snapshot); }
                catch (Exception ex) { throw new CompletionException(ex); }
            });
            write.get(30, TimeUnit.SECONDS);
            saveFailureRecovery.clear();
            copyPackagePersistence(snapshot); host.apply(snapshot.copy()); host.persistenceCompleted(snapshot);
            persisted = new com.google.gson.Gson().toJson(draft); pendingSave = null;
            return true;
        } catch (Exception ex) {
            Throwable cause = ex instanceof ExecutionException ? ex.getCause() : ex;
            if (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            message(I18n.error(cause.getMessage()));
            return false;
        }
    }
    private void message(String value) {
        String text = value == null ? I18n.text("操作失败") : value;
        status.setText(text); status.setVisible(!text.isEmpty());
        statusCell.height(text.isEmpty() ? 0 : status.getPrefHeight()).padBottom(text.isEmpty() ? 0 : 8);
        root.invalidateHierarchy();
    }

    private void choose(boolean replace) {
        if (library == null || (replace && selected == null)) return;
        closeContextMenu();
        replacing = replace; browsing = true; pickedFiles.clear();
        root.setVisible(false); browser.setVisible(true);
        stage.setKeyboardFocus(null); stage.setScrollFocus(null);
        if (directory == null) directory = Paths.get(System.getProperty("user.home"), "Music");
        if (!Files.isDirectory(directory)) directory = Paths.get(System.getProperty("user.home"));
        browse(directory);
    }
    private void openWorkshopWizard() {
        if (busy || workshopWizard!=null || library==null || !flushPendingSave()) return;
        closeContextMenu(); SearchMultiSelect.dismiss(stage);
        root.setVisible(false); stage.setKeyboardFocus(null); stage.setScrollFocus(null);
        try {
            workshopWizard=new WorkshopPublishWizard(stage,skin,library.root(),worker,completions::add,() -> {
                workshopWizard=null; root.setVisible(true); stage.setKeyboardFocus(null); stage.setScrollFocus(null);
            });
        } catch(RuntimeException ex) { root.setVisible(true); message(I18n.error(ex.getMessage())); }
    }
    private void browse(Path path) {
        try {
            path = path.toAbsolutePath().normalize();
            java.util.List<Path> entries;
            try (java.util.stream.Stream<Path> stream = Files.list(path)) {
                entries = stream.filter(file -> Files.isDirectory(file) || audioFile(file))
                        .sorted(Comparator.comparing((Path file) -> !Files.isDirectory(file)).thenComparing(file -> file.getFileName().toString().toLowerCase(java.util.Locale.ROOT)))
                        .collect(Collectors.toList());
            }
            directory = path.toAbsolutePath().normalize(); pathField.setText(directory.toString()); pickedFiles.clear();
            files.clear(); fileButtons.clear();
            for (Path file : entries) {
                boolean folder = Files.isDirectory(file);
                TextButton entry = button((folder ? I18n.text("[文件夹] ") : "[ ] ") + file.getFileName(), () -> {
                    if (Files.isDirectory(file)) browse(file);
                    else {
                        if (replacing) { pickedFiles.clear(); pickedFiles.add(file); }
                        else if (pickedFiles.contains(file)) pickedFiles.remove(file); else pickedFiles.add(file);
                        updatePicked();
                    }
                }, !folder);
                if (!folder) { entry.setName(file.getFileName().toString()); fileButtons.put(file, entry); }
                entry.getLabel().setEllipsis(true); entry.getLabel().setAlignment(Align.left);
                entry.getLabelCell().minWidth(0).padLeft(10).padRight(10);
                files.add(entry).growX().height(46).padBottom(5).row();
            }
            if (entries.isEmpty()) files.add(label(I18n.text("此文件夹没有音频文件"))).left();
            updatePicked();
            browserScroll.setScrollY(0);
        } catch (Exception ex) { browserError(I18n.text("无法打开文件夹：") + I18n.error(ex.getMessage())); }
    }
    private void browseDrives() {
        directory = null; pathField.setText(""); pathField.setMessageText(I18n.text("选择磁盘，或输入文件 / 文件夹路径"));
        pickedFiles.clear(); files.clear(); fileButtons.clear();
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            TextButton entry = button(I18n.text("[磁盘] ") + root, () -> browse(root));
            files.add(entry).growX().height(46).padBottom(5).row();
        }
        if (files.getChildren().size == 0) files.add(label(I18n.text("没有可用磁盘"))).left();
        updatePicked();
        browserScroll.setScrollY(0);
    }
    private void updatePicked() {
        for (Map.Entry<Path, TextButton> item : fileButtons.entrySet()) {
            boolean checked = pickedFiles.contains(item.getKey());
            item.getValue().setChecked(checked);
            item.getValue().setText((checked ? "[x] " : "[ ] ") + item.getKey().getFileName());
        }
        selectionStatus.setText(replacing ? I18n.text("更换文件仅可选择 1 个；已选 ") + pickedFiles.size() + I18n.text(" 个")
                : I18n.text("已选 ") + pickedFiles.size() + I18n.text(" 个；点击文件可多选或取消"));
        importButton.setText(replacing ? I18n.text("更换所选文件") : I18n.text("导入所选文件 (") + pickedFiles.size() + ")");
        importButton.setDisabled(pickedFiles.isEmpty());
    }
    private static boolean audioFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.matches(".*\\.(mp3|wav|ogg|m4a|flac|aac|wma|aiff|opus)$");
    }
    private void openPath() {
        try {
            if (pathField.getText().trim().isEmpty()) { browseDrives(); return; }
            Path path = Paths.get(pathField.getText()).toAbsolutePath();
            if (Files.isDirectory(path)) browse(path);
            else if (Files.isRegularFile(path)) { pickedFiles.clear(); pickedFiles.add(path); importPicked(); }
            else browserError(I18n.text("路径不存在"));
        } catch (Exception ex) { browserError(I18n.text("路径无效")); }
    }
    private void browserError(String message) {
        pickedFiles.clear(); fileButtons.clear(); updatePicked();
        files.clear(); Label label = label(message == null ? I18n.text("操作失败") : message); label.setWrap(true); files.add(label).growX();
    }
    private void importPicked() {
        if (pickedFiles.isEmpty() || busy) return;
        java.util.List<Path> filesToImport = replacing
                ? Collections.singletonList(pickedFiles.get(0)) : new ArrayList<>(pickedFiles);
        Rule old = selected;
        Candidate oldAudio = fileTarget;
        MusicPack targetPack = old == null ? null : draft.pack(old.packId);
        Candidate candidate = replacing ? new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(fileTarget), Candidate.class) : null;
        busy = true; message(I18n.text("正在导入…"));
        files.clear(); files.add(label(I18n.text("正在导入…")));
        worker.submit(() -> {
            java.util.List<Rule> importedRules = new ArrayList<>();
            java.util.List<String> failures = new ArrayList<>();
            boolean reset = false;
            for (Path file : filesToImport) {
                try {
                    Rule one;
                    if (candidate == null) one = library.importAudio(file, targetPack);
                    else { one = new Rule(); reset |= library.replace(candidate, file, targetPack); one.candidates.add(candidate); }
                    importedRules.add(one);
                } catch (Exception ex) { failures.add(file.getFileName() + "：" + I18n.error(ex.getMessage())); }
            }
            boolean intervalReset = reset;
            completions.add(() -> {
                busy = false;
                if (importedRules.isEmpty()) { browserError(String.join("\n", failures)); return; }
                if (candidate != null) {
                    old.candidates.set(old.candidates.indexOf(oldAudio), candidate); selected = old; expandedCandidate = candidate.id;
                } else {
                    for (Rule one : importedRules) {
                        Candidate audio = one.audio();
                        java.util.List<String> names = new ArrayList<>(); for (Candidate c : old.candidates) names.add(c.name);
                        audio.name = RuleCopies.uniqueName(audio.name, names); old.candidates.add(audio);
                    }
                    selected = old; expandedCandidate = importedRules.get(importedRules.size()-1).audio().id;
                }
                leaveBrowser(); list(); bind(false);
                modified();
                long unplayable = importedRules.stream().filter(rule -> !rule.playable()).count();
                if (unplayable > 0 || !failures.isEmpty()) message(unplayable + I18n.text(" 个无法播放；") + failures.size() + I18n.text(" 个导入失败，请查看候选音频"));
                else if (intervalReset) message(I18n.text("播放片段已重置为新音频全长"));
            });
        });
    }
    private static Rule copy(Rule rule) { return new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(rule), Rule.class); }
    private void leaveBrowser() {
        if (busy) return;
        closeContextMenu();
        browsing = false; browser.setVisible(false); root.setVisible(true);
        stage.setKeyboardFocus(null); stage.setScrollFocus(null);
    }
    public void close() {
        if (busy || closed) return;
        if (workshopWizard!=null) { workshopWizard.close(); if(workshopWizard!=null)return; }
        if (!flushPendingSave()) return;
        closeContextMenu();
        closed = true; host.stopPreview();
        Gdx.input.setInputProcessor(previousInput);
        host.closed();
        dispose();
    }
    public void dispose() {
        if (disposed) return;
        if (!closed) flushPendingSave();
        disposed = true;
        if (pendingSave != null) pendingSave.cancel(false);
        saveScheduler.shutdownNow();
        worker.shutdown();
        if (Gdx.input.getInputProcessor() == stage) Gdx.input.setInputProcessor(previousInput);
        stage.dispose(); skin.dispose();
    }
    private Rule stopView(Rule owner) { return owner.stopConditions.asRule(owner.id + ":stop"); }
    private java.util.List<FilterOptions.Option> ruleOptions(Rule rule) {
        java.util.List<FilterOptions.Option> options = new ArrayList<>();
        MusicPack own = draft.pack(rule.packId);
        for (Rule other : draft.rules) if (!other.id.equals(rule.id)
                && (java.util.Objects.equals(rule.packId, other.packId)
                || (own == null || own.source != MusicPack.Source.SUBSCRIBED && (own.localFolder == null || own.localFolder.isEmpty()))
                && (draft.pack(other.packId) == null || draft.pack(other.packId).source != MusicPack.Source.SUBSCRIBED
                && (draft.pack(other.packId).localFolder == null || draft.pack(other.packId).localFolder.isEmpty()))))
            options.add(new FilterOptions.Option(other.id, other.name + (other.enabled ? "" : I18n.text("（未启用）"))));
        return options;
    }
    private void prerequisiteRuleSelection(Rule rule) {
        section(I18n.text("前置触发条件"));
        java.util.List<FilterOptions.Option> options = ruleOptions(rule);
        if (options.isEmpty()) { note(I18n.text("没有其他规则可选，当前不限制触发。")); return; }
        SearchMultiSelect chooser = new SearchMultiSelect(skin,I18n.text("规则"),"prerequisite-rules",options,
                new LinkedHashSet<>(rule.prerequisiteRuleIds), ids -> {
            java.util.List<String> previous = rule.prerequisiteRuleIds;
            rule.prerequisiteRuleIds = new ArrayList<>(ids);
            try { draft.validatePrerequisites(); }
            catch (IllegalArgumentException ex) {
                rule.prerequisiteRuleIds = previous;
                completions.add(() -> { bind(true); message(I18n.error(ex.getMessage()) + I18n.text("；本次选择尚未保存")); }); return;
            }
            modified();
        });
        row(I18n.text("前置规则（任意一个）"),chooser);
        note(I18n.text("留空不限制；所选规则已开始自动播放且尚未结束时，下方触发条件才生效。暂停仍算未结束，试听不算。"));
    }
    private void pauseRuleSelection(Rule rule) {
        section(I18n.text("规则间暂停"));
        java.util.List<FilterOptions.Option> options = ruleOptions(rule);
        if (options.isEmpty()) { note(I18n.text("新建其他规则后，可选择它播放期间暂停本规则。")); return; }
        SearchMultiSelect chooser = new SearchMultiSelect(skin,I18n.text("规则"),"pause-rules",options,
                new LinkedHashSet<>(rule.pauseWhenRuleIds), ids -> {
            java.util.List<String> previous = rule.pauseWhenRuleIds;
            rule.pauseWhenRuleIds = new ArrayList<>(ids);
            try { draft.validatePauseRelations(); }
            catch (IllegalArgumentException ex) {
                rule.pauseWhenRuleIds = previous;
                completions.add(() -> { bind(true); message(I18n.error(ex.getMessage()) + I18n.text("；本次选择尚未保存")); }); return;
            }
            modified();
        });
        row(I18n.text("这些规则播放时暂停本规则"),chooser);
        note(I18n.text("所选规则的自动音频全部结束后，从原进度恢复；试听不触发暂停。"));
    }
    private void syncStopView() {
        if (activeStopOwner == null || activeStopView == null) return;
        Config.StopConditions stop = activeStopOwner.stopConditions;
        stop.triggers = activeStopView.triggers; stop.low = activeStopView.low; stop.high = activeStopView.high; stop.heal = activeStopView.heal;
        stop.anyPower = activeStopView.anyPower; stop.manualPowerIds = activeStopView.manualPowerIds; stop.powerIds = activeStopView.powerIds;
        stop.parameters = activeStopView.parameters; stop.filters = activeStopView.filters;
    }
    private void healthStopShortcut(Rule owner) {
        Config.StopConditions stop = owner.stopConditions;
        CheckBox health = check(I18n.text("血量达到停止阈值时停止播放"), stop.triggers.contains(Trigger.HIGH_HEALTH), value -> {
            if (value) {
                stop.triggers.add(Trigger.HIGH_HEALTH);
                stop.high.percent = owner.low.percent;
                if (stop.high.value <= owner.low.value) stop.high.value = Math.min(owner.low.percent ? 100 : Double.MAX_VALUE, owner.low.value + 10);
            } else { stop.triggers.remove(Trigger.HIGH_HEALTH); stop.additionalHigh = null; }
            completions.add(() -> bind(true));
        });
        health.setName("stop-health-enabled"); editor.add(health).colspan(2).left().height(46).row();
        if (stop.triggers.contains(Trigger.HIGH_HEALTH)) {
            Table line = healthThreshold(stop.high, "stop-health-threshold");
            row(I18n.text("停止血量 ≥"), line);
        }
    }
    private void stopConditions(Rule owner) {
        section(I18n.text("停止条件"));
        boolean expanded = owner.playbackMode == Config.PlaybackMode.INFINITE || expandedStops.contains(owner.id);
        if (owner.playbackMode != Config.PlaybackMode.INFINITE) {
            CheckBox expand = check(I18n.text("展开停止条件"), expanded, value -> {
                if (value) expandedStops.add(owner.id); else expandedStops.remove(owner.id);
                completions.add(() -> bind(true));
            });
            expand.setName("stop-conditions-expanded"); editor.add(expand).colspan(2).left().height(46).row();
        }
        if (!expanded) return;
        note(I18n.text("任意停止条件满足时，停止本规则全部自动音频，并取消待重播；试听不受影响。"));
        Rule view = stopView(owner); activeStopOwner = owner; activeStopView = view;
        conditionEditor(view, I18n.text("通用停止条件"), "stop-trigger-");
    }
    private void conditionEditor(Rule condition, String title, String namePrefix) {
        boolean stopping = namePrefix.startsWith("stop-");
        stopEditor = stopping;
        section(title);
        SelectBox<String> categories = new SelectBox<>(skin);
        Events.Category currentCategory = stopping ? stopCategory : category;
        categories.setItems(I18n.text(Events.CATEGORY_NAMES)); categories.setSelectedIndex(currentCategory.ordinal());
        categories.addListener(new ChangeListener() { public void changed(ChangeEvent event, Actor actor) {
            if (stopping) stopCategory = Events.Category.values()[categories.getSelectedIndex()];
            else category = Events.Category.values()[categories.getSelectedIndex()];
            completions.add(() -> bind(false));
        }});
        categories.setName(namePrefix + "category");
        editor.add(categories).colspan(2).growX().height(52).row();
        String group = "";
        for (Events.Definition definition : Events.definitions()) {
            if (!definition.categories.contains(currentCategory)) continue;
            if (!group.equals(definition.group)) { section(I18n.text(definition.group)); group = definition.group; }
            Trigger trigger = definition.trigger;
            if (stopping && trigger == Trigger.HEAL_THRESHOLD) continue;
            boolean checked = condition.triggers.contains(trigger);
            String titleText = stopping && trigger == Trigger.HIGH_HEALTH
                    ? I18n.text("血量 ≥ 停止阈值") : I18n.text(definition.title);
            CheckBox checkbox = check(titleText, checked, value -> {
                if (value) {
                    condition.triggers.add(trigger);
                    if (stopping && (trigger == Trigger.HIGH_HEALTH || trigger == Trigger.HEAL_THRESHOLD)
                            && selected.triggers.contains(Trigger.LOW_HEALTH)) {
                        Threshold stopThreshold = trigger == Trigger.HIGH_HEALTH ? condition.high : condition.heal;
                        stopThreshold.percent = selected.low.percent;
                        if (stopThreshold.value <= selected.low.value)
                            stopThreshold.value = Math.min(selected.low.percent ? 100 : Double.MAX_VALUE, selected.low.value + 10);
                    }
                    if (definition.threshold || Events.requiresId(trigger) || definition.filter.equals("orb")) condition.parameter(trigger);
                } else condition.triggers.remove(trigger);
                if (stopping && trigger == Trigger.HIGH_HEALTH && !value) selected.stopConditions.additionalHigh = null;
                completions.add(() -> bind(true));
            });
            checkbox.setName(namePrefix + trigger.name());
            editor.add(checkbox).colspan(2).left().minHeight(46).row();
            if (checked && (trigger == Trigger.LOW_HEALTH || trigger == Trigger.HIGH_HEALTH || trigger == Trigger.HEAL_THRESHOLD)) {
                Threshold selectedThreshold = trigger == Trigger.LOW_HEALTH ? condition.low : trigger == Trigger.HIGH_HEALTH ? condition.high : condition.heal;
                editor.add(healthThreshold(selectedThreshold, stopping ? "stop-threshold-" + trigger.name() : "health-threshold")).colspan(2).growX().row();
                if (!stopping && trigger == Trigger.LOW_HEALTH) healthStopShortcut(condition);
                if (stopping && trigger == Trigger.HIGH_HEALTH && selected.stopConditions.additionalHigh != null) {
                    row(I18n.text("另一个血量停止阈值 ≥"), healthThreshold(selected.stopConditions.additionalHigh, "stop-additional-health"));
                    note(I18n.text("任一血量阈值达到时停止播放。"));
                    editor.add(button(I18n.text("删除额外血量阈值"), () -> {
                        selected.stopConditions.additionalHigh = null; modified(); completions.add(() -> bind(true));
                    })).colspan(2).left().height(46).row();
                }
            }
            else if (checked && (definition.threshold || Events.requiresId(trigger) || definition.filter.equals("orb"))) {
                Parameter parameter = condition.parameter(trigger);
                if (definition.threshold) {
                    TextField value = number(parameter.value, Events.isCounter(trigger) || trigger == Trigger.ENERGY_GAIN_THRESHOLD
                            || trigger == Trigger.SCRY_CARDS,
                            parameter.percent ? 100 : Double.POSITIVE_INFINITY, next -> parameter.value = next);
                    value.setName(namePrefix + "threshold-" + trigger.name());
                    editor.add(label(trigger == Trigger.ENERGY_GAIN_THRESHOLD ? I18n.text("单次能量 X") : I18n.text("达到阈值"))).left(); editor.add(value).growX().height(44).row();
                    if (definition.filter.equals("health")) row(I18n.text("阈值单位"), select(new String[] {I18n.text("生命点数"), I18n.text("最大生命百分比")}, parameter.percent ? 1 : 0,
                            index -> { parameter.percent = index == 1; if (parameter.percent) parameter.value = Math.min(100, parameter.value); completions.add(() -> bind(true)); }));
                }
                if (Events.requiresId(trigger)) {
                    idChoice(definition.filter.equals("stance") ? I18n.text("姿态") : I18n.text("状态效果"), "filter-" + trigger.name(),
                            parameter.id, false,
                            definition.filter.equals("stance")
                                    ? FilterOptions.fixed(new String[][]{{"Neutral",I18n.text("普通")},{"Calm",I18n.text("平静")},{"Wrath",I18n.text("愤怒")},{"Divinity",I18n.text("神格")}})
                                    : FilterOptions.powers(),
                            next -> parameter.id = next);
                }
                if (definition.filter.equals("orb")) {
                    String[] ids = {"", "Lightning", "Frost", "Dark", "Plasma"};
                    int index = parameter.any ? 0 : Math.max(0, Arrays.asList(ids).indexOf(parameter.id));
                    row(I18n.text("球种"), select(new String[] {I18n.text("任意球种"), I18n.text("闪电"), I18n.text("冰霜"), I18n.text("黑暗"), I18n.text("等离子")}, index, next -> {
                        parameter.any = next == 0; parameter.id = ids[next];
                    }));
                }
            }
            if (checked && definition.filter.equals("scene")) sceneFilter(condition,trigger);
            if (checked && trigger==Trigger.EVOKE_ORB) evokeFilter(condition);
            if (checked && (trigger==Trigger.CHANNEL_ORB || trigger==Trigger.EVOKE_ORB || trigger==Trigger.ORB_PASSIVE))
                cadenceFilter(condition,trigger,true,true,null);
            if (checked && definition.filter.equals("card")) cardFilter(condition, trigger);
            if (checked && (trigger == Trigger.HP_LOSS || trigger == Trigger.BLOCK_LOSS
                    || trigger == Trigger.GAIN_ENERGY || trigger == Trigger.GAIN_BLOCK
                    || trigger == Trigger.GAIN_GOLD || trigger == Trigger.SPEND_GOLD
                    || trigger == Trigger.SELF_DAMAGE || trigger == Trigger.FOCUS_INCREASE
                    || trigger == Trigger.FOCUS_DECREASE || trigger == Trigger.DARK_ACCUMULATE
                    || trigger == Trigger.REGEN_HEAL || trigger == Trigger.INTANGIBLE_MITIGATE
                    || trigger == Trigger.ORB_PASSIVE_BLOCK || trigger == Trigger.ORB_PASSIVE_ENERGY
                    || trigger == Trigger.ORB_PASSIVE_DAMAGE || trigger == Trigger.SHIV_DAMAGE
                    || trigger == Trigger.DARK_EVOKE_DAMAGE || trigger == Trigger.PLASMA_EVOKE_ENERGY
                    || trigger == Trigger.DIVINITY_DAMAGE || trigger == Trigger.DAMAGE_THRESHOLD
                    || trigger == Trigger.CALM_GAIN_BLOCK || trigger == Trigger.CALM_EXIT_ENERGY
                    || trigger == Trigger.WRATH_DAMAGE || trigger == Trigger.STRENGTH_INCREASE
                    || trigger == Trigger.STRENGTH_DECREASE || trigger == Trigger.DEXTERITY_INCREASE
                    || trigger == Trigger.DEXTERITY_DECREASE || trigger == Trigger.GAIN_SPECIFIED_POWER
                    || trigger == Trigger.POWER_DECREASE || trigger == Trigger.POWER_REMOVED
                    || trigger == Trigger.RECEIVE_DEBUFF || trigger==Trigger.MANTRA_INCREASE
                    || trigger==Trigger.ATTACK_MULTI_KILL || trigger==Trigger.LOW_HEALTH_FIRST_BLOCK)) amountFilter(condition, trigger);
            if (checked && (trigger == Trigger.APPLY_POISON || trigger == Trigger.POISON_DAMAGE
                    || trigger == Trigger.POISON_KILL || trigger == Trigger.POISON_DECREASE
                    || trigger == Trigger.APPLY_ENEMY_POWER)) enemyFilter(condition, trigger);
            if (checked && (trigger==Trigger.ORB_PASSIVE_DAMAGE || trigger==Trigger.SHIV_DAMAGE
                    || trigger==Trigger.DARK_EVOKE_DAMAGE || trigger==Trigger.WRATH_DAMAGE
                    || trigger==Trigger.DIVINITY_DAMAGE || trigger==Trigger.DAMAGE_THRESHOLD)) {
                Config.EventFilter filter=condition.filter(trigger);
                idChoice(I18n.text("目标敌人"), "target-id-" + trigger.name(), filter.targetId, true, FilterOptions.enemies(), next -> {
                    condition.filters.put(trigger, filter); filter.targetId = next;
                });
            }
            if (checked && (trigger==Trigger.USE_POTION || trigger==Trigger.SHOP_BUY_POTION)) potionFilter(condition,trigger);
            if (checked && (trigger==Trigger.GAIN_RELIC || trigger==Trigger.SHOP_BUY_RELIC)) relicFilter(condition,trigger);
            if (checked && trigger==Trigger.KILL_ENEMY) killFilter(condition);
            if (checked && trigger==Trigger.ENEMY_LOW_HEALTH) lowEnemyFilter(condition);
            if (checked && (trigger==Trigger.ENTER_WRATH || trigger==Trigger.ENTER_CALM
                    || trigger==Trigger.ENTER_DIVINITY || trigger==Trigger.EXIT_STANCE
                    || trigger==Trigger.STANCE_CHANGE)) stanceFilter(condition,trigger);
        }
        if (condition.triggers.contains(Trigger.POSITIVE_POWER)) {
            SelectBox<String> scope = select(new String[] {I18n.text("任意正面状态"), I18n.text("从列表选择"), I18n.text("手动输入内部编号（高级）")},
                    condition.anyPower ? 0 : condition.manualPowerIds ? 2 : 1, index -> {
                condition.anyPower = index == 0; condition.manualPowerIds=index==2; completions.add(() -> bind(true));
            });
            row(I18n.text("状态筛选"), scope);
            if (!condition.anyPower) {
                multiPowerChoice(condition);
            }
        }
        stopEditor = false;
    }
}
