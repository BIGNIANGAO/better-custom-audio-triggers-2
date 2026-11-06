package battleaudio;

import basemod.BaseMod;
import basemod.interfaces.PostInitializeSubscriber;
import basemod.interfaces.PostUpdateSubscriber;
import basemod.interfaces.PostRenderSubscriber;
import com.badlogic.gdx.Gdx;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import java.nio.file.*;

@SpireInitializer
public final class InGameSmoke implements PostInitializeSubscriber, PostUpdateSubscriber, PostRenderSubscriber, battleaudio.core.RuleEngine.Playback {
    private boolean ready;
    private int frames;
    private int phase;
    private int phaseFrames;
    private boolean screenshot;
    private com.megacrit.cardcrawl.actions.AbstractGameAction pausedAction;
    private float pausedDuration;
    private boolean saved;
    private String originalName;
    private Object modsScreen;
    private int rulesBeforeImport;
    private int expectedImports;
    private com.megacrit.cardcrawl.helpers.Hitbox blockedButton;
    private int[] sharedTextureHandles;
    private float sharedFontScale;
    private final java.util.Map<String, Integer> actualEvents = new java.util.HashMap<>();
    private battleaudio.audio.PlaybackManager eventAudio;
    private final java.util.List<com.badlogic.gdx.audio.Music> eventTracks = new java.util.ArrayList<>();
    public void start(battleaudio.model.Config.Rule rule, String scene) {
        actualEvents.put(rule.id, actualEvents.getOrDefault(rule.id, 0) + 1);
        if (eventAudio != null && java.util.Arrays.asList("FOCUS_INCREASE", "DARK_ACCUMULATE", "ORB_PASSIVE", "SHOP_OPEN", "COMBAT_CARD_REWARD_OPEN", "CAMPFIRE_REST", "CAMPFIRE_SMITH", "CHEST_OPEN", "DAMAGE_THRESHOLD").contains(rule.id)) {
            int previous = eventAudio.count(); eventAudio.start(rule, scene);
            if ((rule.retriggerMode == battleaudio.model.Config.RetriggerMode.OVERLAP && eventAudio.count() != previous + 1) || !eventTracks.get(eventTracks.size() - 1).isPlaying())
                throw new AssertionError("Real event failed to start OpenAL audio: " + rule.id);
        }
    }
    public void stopScene(String scene) { }
    public void stopAll() { }
    public static void initialize() { BaseMod.subscribe(new InGameSmoke()); }
    public void receivePostInitialize() {
        // Fixture victories/deaths must not submit Steam stats, achievements or leaderboard writes.
        com.megacrit.cardcrawl.integrations.PublisherIntegration integration = CardCrawlGame.publisherIntegration;
        CardCrawlGame.publisherIntegration = (com.megacrit.cardcrawl.integrations.PublisherIntegration)java.lang.reflect.Proxy.newProxyInstance(
                integration.getClass().getClassLoader(), new Class<?>[] {com.megacrit.cardcrawl.integrations.PublisherIntegration.class}, (proxy, method, parameters) -> {
                    if (method.getName().equals("dispose")) { integration.dispose(); return null; }
                    if (method.getName().equals("getType")) return integration.getType();
                    if (method.getName().equals("isInitialized")) return integration.isInitialized();
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
        Settings.MASTER_VOLUME = 0;
        Settings.MUSIC_VOLUME = 0;
        org.lwjgl.openal.AL10.alListenerf(org.lwjgl.openal.AL10.AL_GAIN,0);
        ready = true;
    }
    public void receivePostUpdate() {
        if (!ready || CardCrawlGame.mainMenuScreen == null) return;
        frames++;
        phaseFrames++;
        if (phase == 0 && frames == 30) {
            if (BattleAudioMod.instance == null) throw new AssertionError("Mod initializer not called");
            if (BaseMod.getSaveFields().get("battleaudio:firstBattle") == null) throw new AssertionError("Save field missing");
            try {
                Object group = basemod.patches.com.megacrit.cardcrawl.helpers.TopPanel.TopPanelHelper.topPanelGroup;
                java.lang.reflect.Field items = group.getClass().getDeclaredField("topPanelItems"); items.setAccessible(true);
                boolean found = false;
                for (Object item : (java.util.List<?>)items.get(group)) {
                    basemod.TopPanelItem entry = (basemod.TopPanelItem)item;
                    if ("battleaudio:settings".equals(entry.getID())) {
                        if (entry.getImage() != battleaudio.ui.AudioIcon.texture()) throw new AssertionError("Top panel uses different icon");
                        found = true;
                    }
                }
                if (!found || battleaudio.ui.AudioIcon.texture() == com.megacrit.cardcrawl.helpers.ImageMaster.SETTINGS_ICON)
                    throw new AssertionError("New speaker icon missing");
                System.out.println("PASS: top panel registers shared speaker icon");
            } catch (Exception ex) { throw new RuntimeException(ex); }
            String expectedName = System.getProperty("battleaudio.expectName");
            if (expectedName != null && !((battleaudio.model.Config)modField("config")).rules.get(0).name.equals(expectedName))
                throw new AssertionError("Fresh game process did not read automatically saved configuration");
            if (expectedName != null) System.out.println("PASS: fresh game process loads automatically saved settings");
            if (expectedName != null) {
                battleaudio.storage.AudioLibrary library = (battleaudio.storage.AudioLibrary)modField("library");
                try {
                    battleaudio.model.Config disk = loadSettings(library);
                    String loaded = new com.google.gson.Gson().toJson(modField("config"));
                    if (!loaded.equals(new com.google.gson.Gson().toJson(disk))) throw new AssertionError("Restart did not preserve complete config");
                    battleaudio.model.Config.Rule last = disk.rules.get(disk.rules.size() - 1);
                    if (last.playbackMode != battleaudio.model.Config.PlaybackMode.ONCE || last.candidates.stream().anyMatch(audio -> audio.volume != 0.3f))
                        throw new AssertionError("Restart lost imported defaults");
                    System.out.println("PASS: complete saved config and imported once/30% defaults survive new process");
                } catch (Exception ex) { throw new RuntimeException(ex); }
            }
            if (Boolean.getBoolean("battleaudio.reproLoad")) {
                com.megacrit.cardcrawl.dungeons.AbstractDungeon.player = CardCrawlGame.characterManager.getCharacter(
                        com.megacrit.cardcrawl.characters.AbstractPlayer.PlayerClass.DEFECT);
                com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode = null;
                BaseMod.getSaveFields().get("battleaudio:firstBattle").onLoadRaw(new com.google.gson.JsonPrimitive(true));
                System.out.println("PASS: save callback before room initialization");
            }
            if (Boolean.getBoolean("battleaudio.bundledDecoderSmoke")) {
                try {
                    battleaudio.storage.AudioLibrary library = (battleaudio.storage.AudioLibrary)modField("library");
                    java.lang.reflect.Field field=library.getClass().getDeclaredField("ffmpeg"); field.setAccessible(true);
                    Path decoder=(Path)field.get(library), home=Paths.get(System.getProperty("battleaudio.home")).toAbsolutePath().normalize();
                    if(decoder==null || !decoder.startsWith(home.resolve("bin")) || !Files.isRegularFile(decoder))
                        throw new AssertionError("Bundled decoder was not extracted to the isolated data folder");
                    Path mp3=home.resolve("synthetic-import-test.mp3");
                    Process process=new ProcessBuilder(decoder.toString(),"-nostdin","-v","error","-y","-i",
                            System.getProperty("battleaudio.sample"),"-codec:a","libmp3lame",mp3.toString()).redirectErrorStream(true).start();
                    if(!process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS) || process.exitValue()!=0)
                        throw new AssertionError("Could not create synthetic MP3 fixture");
                    battleaudio.model.Config.Rule imported=library.importAudio(mp3);
                    if(!imported.audio().playable() || !Files.isRegularFile(library.managed(imported.audio().cachePath)))
                        throw new AssertionError("Bundled decoder could not import MP3 without a system decoder");
                    System.out.println("PASS: bundled FFmpeg extracts and imports a synthetic MP3 in isolated game data");
                    finish();
                } catch(Throwable failure) { failure.printStackTrace(); Gdx.app.exit(); }
            } else if (Boolean.getBoolean("battleaudio.loongPerformanceSmoke")) {
                try {
                    long start = System.nanoTime(); openEmbeddedPanel();
                    double openMs = (System.nanoTime() - start) / 1000000d;
                    battleaudio.ui.LoongPerformanceSmoke.run(getSettings(), openMs);
                    saveScreenshot("loong-layout.png");
                    closeEmbeddedPanel();
                    battleaudio.LoongTriggerPerformanceSmoke.run(BattleAudioMod.instance);
                    finish();
                } catch (Throwable failure) { failure.printStackTrace(); Gdx.app.exit(); }
            } else if (Boolean.getBoolean("battleaudio.privateWorkshopReadSmoke")) {
                try {
                    battleaudio.storage.AudioLibrary library = (battleaudio.storage.AudioLibrary)modField("library");
                    battleaudio.model.Config current = (battleaudio.model.Config)modField("config");
                    String privateItemId = System.getProperty("battleaudio.privateWorkshopItemId", "3830077724");
                    battleaudio.model.MusicPack pack = current.packs.stream().filter(p -> privateItemId.equals(p.workshopId)).findFirst()
                            .orElseThrow(() -> new AssertionError("Real private subscription not loaded"));
                    java.util.List<battleaudio.model.Config.Rule> rules = new java.util.ArrayList<>();
                    for (battleaudio.model.Config.Rule rule : current.rules) if (pack.id.equals(rule.packId)) rules.add(rule);
                    if (rules.size() != 12) throw new AssertionError("Expected 12 real private pack rules, got " + rules.size());
                    battleaudio.model.Config.Rule playable = rules.stream().filter(battleaudio.model.Config.Rule::playable).findFirst()
                            .orElseThrow(() -> new AssertionError("Real private pack has no playable rule"));
                    java.nio.file.Path audio = library.managed(playable.audio().cachePath);
                    if (!audio.startsWith(java.nio.file.Paths.get(System.getProperty("battleaudio.workshopContent"), privateItemId)))
                        throw new AssertionError("Private subscription is not playing directly from Steam");
                    battleaudio.audio.PlaybackManager player = new battleaudio.audio.PlaybackManager(library,
                            error -> { throw new AssertionError("Private subscription audio: " + error); });
                    player.start(playable, "preview"); if (player.count() != 1) throw new AssertionError("Private subscription audio did not play");
                    player.stopAll();
                    battleaudio.model.Config copied = library.copyPackage(current, pack.id);
                    battleaudio.model.MusicPack local = copied.packs.stream().filter(p -> p.source == battleaudio.model.MusicPack.Source.LOCAL).findFirst()
                            .orElseThrow(() -> new AssertionError("Independent copy missing"));
                    if (!Files.isRegularFile(library.root().resolve("music-packs").resolve(local.localFolder).resolve("music-pack.json")))
                        throw new AssertionError("Independent copy lacks a package rules file");
                    System.out.println("PASS: real Steam private pack loads 12 rules, plays directly from Steam, and copies independently");
                    finish();
                } catch (Throwable failure) { failure.printStackTrace(); Gdx.app.exit(); }
            } else if (Boolean.getBoolean("battleaudio.allLanguageFontSmoke")) {
                try { battleaudio.ui.AllLanguageFontSmoke.run((battleaudio.model.Config)modField("config"));finish(); }
                catch(Throwable failure){failure.printStackTrace();Gdx.app.exit();}
            } else if (Boolean.getBoolean("battleaudio.settingsPerformanceSmoke")) {
                try {
                    if(Boolean.getBoolean("battleaudio.settingsPerformanceMinimal")) {
                        battleaudio.model.Config tiny=new battleaudio.model.Config();tiny.schemaVersion=3;
                        battleaudio.model.Config.Rule rule=new battleaudio.model.Config.Rule();rule.name="Minimal rule";tiny.rules.add(rule);tiny.migrate();
                        java.lang.reflect.Field configField=BattleAudioMod.class.getDeclaredField("config");configField.setAccessible(true);configField.set(BattleAudioMod.instance,tiny);
                        ((battleaudio.storage.AudioLibrary)modField("library")).save(tiny);
                    }
                    openEmbeddedPanel();closeEmbeddedPanel(); // Warm ModTheSpire Mods screen before timing settings construction.
                    long started=System.nanoTime();openEmbeddedPanel();double openMs=(System.nanoTime()-started)/1000000d;
                    battleaudio.ui.SettingsPerformanceSmoke.run(getSettings(),openMs);
                    saveScreenshot("settings-performance.png");closeEmbeddedPanel();
                    started=System.nanoTime();openEmbeddedPanel();openMs=(System.nanoTime()-started)/1000000d;
                    battleaudio.ui.SettingsPerformanceSmoke.run(getSettings(),openMs);
                    closeEmbeddedPanel();finish();
                } catch(Throwable failure){failure.printStackTrace();Gdx.app.exit();}
            } else if (Boolean.getBoolean("battleaudio.localizationSmoke")) {
                try {
                    openEmbeddedPanel();
                    battleaudio.ui.LocalizationUiSmoke.run(getSettings());
                    saveScreenshot("settings-localized-"+battleaudio.localization.I18n.language()+".png");
                    closeEmbeddedPanel();finish();
                } catch(Throwable failure){failure.printStackTrace();Gdx.app.exit();}
            } else if (Boolean.getBoolean("battleaudio.backgroundMixSmoke")) {
                try { actualBackgroundMixChecks();finish(); }
                catch(Throwable failure){failure.printStackTrace();Gdx.app.exit();}
            } else if (Boolean.getBoolean("battleaudio.filterSmoke")) {
                try {
                    int[] viewport = viewportState();
                    int width = Gdx.graphics.getWidth(), height = Gdx.graphics.getHeight();
                    boolean fullscreen = Gdx.graphics.isFullscreen();
                    if (Boolean.getBoolean("battleaudio.expectedFullscreen") && !fullscreen)
                        throw new AssertionError("Fullscreen test did not enter fullscreen mode");
                    openEmbeddedPanel();
                    requireDisplayState(viewport, width, height, fullscreen, "Config open");
                    battleaudio.ui.FilterUiSmoke.run(getSettings());
                    getSettings().close();
                    requireDisplayState(viewport, width, height, fullscreen, "Config close");
                    BattleAudioMod.instance.openSettings();
                    requireDisplayState(viewport, width, height, fullscreen, "speaker open");
                    battleaudio.ui.FilterUiSmoke.run(getSettings());
                    getSettings().close();
                    requireDisplayState(viewport, width, height, fullscreen, "speaker close");
                    System.out.println("PASS: both settings entry points preserve viewport, display size and fullscreen state");
                    finish();
                } catch(Throwable failure) { failure.printStackTrace(); Gdx.app.exit(); }
            } else if (Boolean.getBoolean("battleaudio.styleSmoke")) {
                openEmbeddedPanel(); expandCandidate(); phase=50; phaseFrames=0;
            } else if (Boolean.getBoolean("battleaudio.flowSmoke")) {
                openEmbeddedPanel();
                if(visibleActor(getStage().getRoot(),"导入音频")!=null) throw new AssertionError("Separate import entry remains");
                rulesBeforeImport=draft().rules.size(); activate("新建规则"); getSettings().update();
                battleaudio.model.Config.Rule fresh=(battleaudio.model.Config.Rule)settingField("selected");
                originalName=fresh.name; assertEmptyRule(); activate("保存");
                if(draft().rules.size()!=rulesBeforeImport+1) throw new AssertionError("New rule not retained");
                activate("添加候选音频"); openSampleDirectory();
                activate(Paths.get(System.getProperty("battleaudio.sample")).getFileName().toString());
                com.megacrit.cardcrawl.helpers.input.InputHelper.pressedEscape=true; getSettings().update();
                assertEmptyRule(); activate("保存");
                System.out.println("PASS: new UI rule has zero candidates, saves, and cancelling selected file creates nothing");
                phase=40; phaseFrames=0;
            } else if (Boolean.getBoolean("battleaudio.sliderSmoke")) {
                openEmbeddedPanel(); expandCandidate(); phase = 20; phaseFrames = 0;
            } else if (Boolean.getBoolean("battleaudio.candidateSmoke")) {
                openEmbeddedPanel(); expandCandidate();
                rulesBeforeImport=draft().rules.size();
                originalName=((battleaudio.model.Config.Rule)settingField("selected")).id;
                activate("添加候选音频"); openSampleDirectory();
                activate(Paths.get(System.getProperty("battleaudio.sample")).getFileName().toString());
                activate(Paths.get(System.getProperty("battleaudio.sample2")).getFileName().toString());
                activate("导入所选文件"); phase=30; phaseFrames=0;
            } else if (Boolean.getBoolean("battleaudio.uiSmoke")) {
                BattleAudioMod.instance.openSettings();
                if (getStage().getRoot().findActor("audio-name") != null) {
                    originalName = ((com.badlogic.gdx.scenes.scene2d.ui.TextField)getStage().getRoot().findActor("audio-name")).getText();
                    enterText("游戏内保存测试");
                    activate("保存");
                    saved = true;
                    enterText("未保存的修改");
                }
                phase = 1; phaseFrames = 0;
            } else if (Boolean.getBoolean("battleaudio.configSmoke")) {
                openEmbeddedPanel();
                if (!BattleAudioMod.instance.settingsOpen()
                        || getStage().getRoot().findActor("audio-name") == null)
                    throw new AssertionError("Config panel did not embed the in-game editor");
                System.out.println("PASS: BaseMod Config opens shared full overlay");
                originalName = ((com.badlogic.gdx.scenes.scene2d.ui.TextField)getStage().getRoot().findActor("audio-name")).getText();
                if (getStage().getRoot().findActor("保存") != null)
                    throw new AssertionError("Manual Save button still present");
                enterText("Config 保存测试"); activate("保存"); enterText("Config 未保存修改");
                addScrollFixtures();
                phase = 8; phaseFrames = 0;
            } else if (Boolean.getBoolean("battleaudio.continueSave")) resume();
            else if (Boolean.getBoolean("battleaudio.freshEventsSmoke") || Boolean.getBoolean("battleaudio.encounterUiCombatSmoke")) startFreshRun();
            else finish();
        }
        if((phase==50 || phase==52) && phaseFrames==60) {
            candidateButtonLayoutChecks(); screenshot=true;
            if(Boolean.getBoolean("battleaudio.nativeStyle")) nativeStyleChecks();
        }
        if((phase==50 || phase==52) && phaseFrames==65) {
            sliderChecks();
            addScrollFixtures(); getSettings().render((com.badlogic.gdx.graphics.g2d.SpriteBatch)getStage().getBatch());
            libraryScroll().setScrollY(0); editorScroll().setScrollY(0);
            wheelOver(libraryScroll(),100); wheelOver(editorScroll(),100);
            if(libraryScroll().getScrollY()<=0 || editorScroll().getScrollY()<=0) throw new AssertionError("Style pane wheel regression");
            activate("添加候选音频"); openSampleDirectory();
            if(Boolean.getBoolean("battleaudio.nativeStyle") && ((com.badlogic.gdx.scenes.scene2d.ui.TextButton)settingField("importButton")).getStyle().disabled==null)
                throw new AssertionError("Missing disabled import style");
            activate(Paths.get(System.getProperty("battleaudio.sample")).getFileName().toString());
            activate(Paths.get(System.getProperty("battleaudio.sample2")).getFileName().toString());
            assertPicked(2); phase++; phaseFrames=0;
        }
        if((phase==51 || phase==53) && phaseFrames==15) screenshot=true;
        if((phase==51 || phase==53) && phaseFrames==20) {
            activate(Paths.get(System.getProperty("battleaudio.sample")).getFileName().toString()); assertPicked(1);
            activate("返回设置");
            if(phase==51) {
                closeEmbeddedPanel();
                if(Boolean.getBoolean("battleaudio.continueSave")) { resume(); return; }
                BattleAudioMod.instance.openSettings(); expandCandidate(); phase=52; phaseFrames=0;
            }
            else {
                closeSettings();
                if(Boolean.getBoolean("battleaudio.nativeStyle")) sharedResourceChecks();
                System.out.println("PASS: both style entry points click, wheel, file multiselect/deselect and slider drag"); finish();
            }
        }
        if(phase==40 && phaseFrames==5) screenshot=true;
        if(phase==40 && phaseFrames==10) {
            activate("添加候选音频"); openSampleDirectory();
            activate(Paths.get(System.getProperty("battleaudio.sample")).getFileName().toString());
            activate("导入所选文件"); phase=41; phaseFrames=0;
        }
        if(phase==41 && phaseFrames>30 && !getSettings().isBusy() && !((Boolean)settingField("browsing"))) {
            battleaudio.model.Config.Rule rule=(battleaudio.model.Config.Rule)settingField("selected");
            if(rule.candidates.size()!=1 || !rule.candidates.get(0).playable() || !rule.name.equals(originalName)
                    || draft().rules.size()!=rulesBeforeImport+1 || !rule.triggers.isEmpty())
                throw new AssertionError("First import created placeholder, new rule, or altered conditions");
            activate("保存"); candidateButtonLayoutChecks(); screenshot=true; phase=42; phaseFrames=0;
            System.out.println("PASS: first import adds exactly one playable real candidate to empty rule");
        }
        if(phase==42 && phaseFrames==10) {
            activate("试听"); activate("停止试听"); activate("删除音频"); assertEmptyRule(); activate("保存");
            closeEmbeddedPanel(); BattleAudioMod.instance.openSettings();
            activate(originalName); assertEmptyRule(); activate("添加候选音频"); openSampleDirectory();
            activate(Paths.get(System.getProperty("battleaudio.sample")).getFileName().toString());
            activate("返回设置");
            assertEmptyRule(); activate("添加候选音频"); openSampleDirectory();
            activate(Paths.get(System.getProperty("battleaudio.sample")).getFileName().toString());
            activate("导入所选文件"); phase=43; phaseFrames=0;
            System.out.println("PASS: deleting final candidate retains saved rule; overlay reopen and selected-file cancel keep zero candidates");
        }
        if(phase==43 && phaseFrames>30 && !getSettings().isBusy() && !((Boolean)settingField("browsing"))) {
            candidateButtonLayoutChecks(); screenshot=true; phase=44; phaseFrames=0;
        }
        if(phase==44 && phaseFrames==10) {
            activate("复制音频");
            battleaudio.model.Config.Rule rule=(battleaudio.model.Config.Rule)settingField("selected");
            if(rule.candidates.size()!=2) throw new AssertionError("Copy candidate control failed");
            activate("删除音频"); expandCandidate(); activate("删除音频"); assertEmptyRule(); activate("保存");
            closeSettings(); finish();
        }
        if (phase == 20 && phaseFrames == 20) {
            java.util.List<com.badlogic.gdx.scenes.scene2d.ui.Slider> sliders = new java.util.ArrayList<>();
            collectSliders(getStage().getRoot(), sliders);
            for (com.badlogic.gdx.scenes.scene2d.ui.Slider slider : sliders) {
                reveal(slider); getSettings().render((com.badlogic.gdx.graphics.g2d.SpriteBatch)getStage().getBatch());
                com.badlogic.gdx.math.Vector2 start = sliderPoint(slider, 0.25f), end = sliderPoint(slider, 0.7f);
                getStage().touchDown(Math.round(start.x), Math.round(start.y), 0, 0);
                getStage().act(0.05f);
                getStage().touchDragged(Math.round(end.x), Math.round(end.y), 0);
                getStage().act(0.05f);
                // A scroll gesture cancels focus with a synthetic touchUp; it must not map that to zero.
                getStage().cancelTouchFocus();
                if (slider.getValue() == slider.getMinValue()) throw new AssertionError("Scroll cancellation resets slider to zero");
                getStage().touchUp(Math.round(end.x), Math.round(end.y), 0, 0);
                float fraction = (slider.getValue() - slider.getMinValue()) / (slider.getMaxValue() - slider.getMinValue());
                if (Math.abs(fraction - 0.7f) > 0.04f) throw new AssertionError("Slider drag jumps to wrong value: " + fraction);
            }
            System.out.println("PASS: actual scrolled Config slider drag maps pointer to value");
            autoSaveChecks();
            closeEmbeddedPanel(); BattleAudioMod.instance.openSettings();
            phase = 21; phaseFrames = 0;
        }
        if ((phase == 20 || phase == 21) && phaseFrames == 15) screenshot = true;
        if(phase==30 && phaseFrames>30 && !getSettings().isBusy() && !((Boolean)settingField("browsing"))) {
            battleaudio.model.Config.Rule selected=(battleaudio.model.Config.Rule)settingField("selected");
            if(!selected.id.equals(originalName) || draft().rules.size()!=rulesBeforeImport || selected.candidates.size()!=3)
                throw new AssertionError("Adding candidates created/selected another rule or lost files");
            battleaudio.model.Config old;
            try { old=battleaudio.model.Config.fromJson(new com.google.gson.JsonParser().parse(new String(Files.readAllBytes(
                    ((battleaudio.storage.AudioLibrary)settingField("library")).root().resolve("settings.json.bak")),"UTF-8")).getAsJsonObject()); }
            catch(Exception ex) { throw new RuntimeException(ex); }
            if(!selected.triggers.equals(old.rules.get(0).triggers)) throw new AssertionError("Adding candidate changed rule triggers");
            for(int i=1;i<3;i++) if(!selected.candidates.get(i).playable() || selected.candidates.get(i).volume!=.3f || !selected.candidates.get(i).participate)
                throw new AssertionError("Added candidate defaults/decode incorrect");
            candidateUiChecks(); screenshot=true; phase=31; phaseFrames=0;
            System.out.println("PASS: game browser adds multiple candidates to existing rule, retains original shared triggers and selects last candidate inline");
        }
        if(phase==31 && phaseFrames==5) { closeEmbeddedPanel(); finish(); }
        if (phase == 21 && phaseFrames == 20) { expandCandidate(); sliderChecks(); autoSaveChecks(); candidateUiChecks(); closeSettings(); finish(); }
        if (phase == 1 && phaseFrames == 15) screenshot = true;
        if (phase == 1 && phaseFrames == 20) {
            closeSettings();
            if (saved) {
                BattleAudioMod.instance.openSettings();
                String actual = ((com.badlogic.gdx.scenes.scene2d.ui.TextField)getStage().getRoot().findActor("audio-name")).getText();
                if (!actual.equals("未保存的修改")) throw new AssertionError("Auto-saved settings lost on close: " + actual);
                System.out.println("PASS: valid game settings automatically apply and persist on close");
                enterText(originalName);
                activate("保存");
                activate("新建规则"); activate("添加候选音频");
                chooseSample();
                return;
            }
            if (Boolean.getBoolean("battleaudio.continueSave")) resume(); else finish();
        }
        if (phase == 6 && phaseFrames == 10) screenshot = true;
        if (phase == 6 && phaseFrames == 20) {
            if (Boolean.getBoolean("battleaudio.configSmoke")) {
                Path removed = Paths.get(System.getProperty("battleaudio.sample")).getParent().resolve("导入前移走.mp3");
                activate(removed.getFileName().toString());
                try { Files.delete(removed); } catch (Exception ex) { throw new RuntimeException(ex); }
            }
            activate("导入所选文件");
            phase = 5; phaseFrames = 0;
        }
        if (phase == 5 && phaseFrames > 30) {
            try {
                java.lang.reflect.Field busy = battleaudio.ui.SettingsScreen.class.getDeclaredField("busy"); busy.setAccessible(true);
                java.lang.reflect.Field browsing = battleaudio.ui.SettingsScreen.class.getDeclaredField("browsing"); browsing.setAccessible(true);
                if (!busy.getBoolean(getSettings()) && !browsing.getBoolean(getSettings())) {
                    String importedName = ((com.badlogic.gdx.scenes.scene2d.ui.TextField)getStage().getRoot().findActor("audio-name")).getText();
                    battleaudio.model.Config.Rule imported=(battleaudio.model.Config.Rule)settingField("selected");
                    if (draft().rules.size() != rulesBeforeImport || imported.candidates.size()!=expectedImports)
                        throw new AssertionError("Batch import did not add every success to the selected rule");
                    if (imported.candidates.stream().anyMatch(audio -> !audio.playable()))
                        throw new AssertionError("Batch imported audio did not decode");
                    if (imported.playbackMode != battleaudio.model.Config.PlaybackMode.ONCE || imported.candidates.stream().anyMatch(audio -> audio.volume != 0.3f))
                        throw new AssertionError("UI import does not use once/30% defaults");
                    if (!imported.triggers.isEmpty())
                        throw new AssertionError("New imported rule automatically selected triggers");
                    activate("保存");
                    System.out.println("PASS: in-game browser imports " + expectedImports + " decoded files, saves and retains successes after per-file failure");
                    if (Boolean.getBoolean("battleaudio.configSmoke")) closeEmbeddedPanel(); else closeSettings();
                    if (Boolean.getBoolean("battleaudio.continueSave")) resume(); else finish();
                }
            } catch (Exception ex) { throw new RuntimeException(ex); }
        }
        if (phase == 2 && CardCrawlGame.isInARun()
                && com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode != null
                && com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room != null) {
            if (phaseFrames > 120 && !com.megacrit.cardcrawl.dungeons.AbstractDungeon.isScreenUp
                    && com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room.phase == com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT
                    && com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room instanceof com.megacrit.cardcrawl.rooms.MonsterRoom) {
                System.out.println("PASS: continued saved run into real combat without crash");
                if(Boolean.getBoolean("battleaudio.styleSmoke")) {
                    BattleAudioMod.instance.openSettings(); expandCandidate(); phase=52; phaseFrames=0; return;
                }
                if (Boolean.getBoolean("battleaudio.deathSmoke")) { actualDeathChecks(); finish(); return; }
                if (Boolean.getBoolean("battleaudio.eventsSmoke")) { actualEventChecks(); finish(); return; }
                com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.currentAction =
                        new com.megacrit.cardcrawl.actions.utility.WaitAction(5);
                com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.phase =
                        com.megacrit.cardcrawl.actions.GameActionManager.Phase.EXECUTING_ACTIONS;
                BattleAudioMod.instance.openSettings();
                pausedAction = com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.currentAction;
                pausedDuration = duration(pausedAction);
                Object button = com.megacrit.cardcrawl.dungeons.AbstractDungeon.overlayMenu.endTurnButton;
                try {
                    java.lang.reflect.Field hitbox = button.getClass().getDeclaredField("hb"); hitbox.setAccessible(true);
                    blockedButton = (com.megacrit.cardcrawl.helpers.Hitbox)hitbox.get(button);
                } catch (Exception ex) { throw new RuntimeException(ex); }
                blockedButton.clicked = true;
                com.megacrit.cardcrawl.dungeons.AbstractDungeon.overlayMenu.endTurnButton.enable();
                com.megacrit.cardcrawl.helpers.input.InputHelper.justClickedLeft = true;
                com.megacrit.cardcrawl.helpers.input.InputHelper.isMouseDown = true;
                com.megacrit.cardcrawl.helpers.input.InputHelper.scrolledDown = true;
                getStage().touchDown((int)blockedButton.cX, Gdx.graphics.getHeight() - (int)blockedButton.cY, 0, 0);
                getStage().touchDragged((int)blockedButton.cX + 10, Gdx.graphics.getHeight() - (int)blockedButton.cY, 0);
                getStage().touchUp((int)blockedButton.cX + 10, Gdx.graphics.getHeight() - (int)blockedButton.cY, 0, 0);
                phase = 3; phaseFrames = 0;
            }
        }
        if (phase == 10 && phaseFrames > 30 && CardCrawlGame.isInARun()
                && com.megacrit.cardcrawl.dungeons.AbstractDungeon.player != null
                && com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode != null) {
            com.megacrit.cardcrawl.rooms.MonsterRoom room = new com.megacrit.cardcrawl.rooms.MonsterRoom();
            room.monsters = new com.megacrit.cardcrawl.monsters.MonsterGroup(
                    new com.megacrit.cardcrawl.monsters.exordium.JawWorm(0, 0));
            room.monsters.monsters.get(0).setMove((byte) 0,
                    com.megacrit.cardcrawl.monsters.AbstractMonster.Intent.ATTACK, 1);
            room.phase = com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT;
            room.cannotLose = false;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room = room;
            if (Boolean.getBoolean("battleaudio.killFilterSmoke")) {
                try { actualKillFilterChecks(); finish(); }
                catch (Throwable failure) { failure.printStackTrace(); Gdx.app.exit(); }
                return;
            }
            if (Boolean.getBoolean("battleaudio.encounterUiCombatSmoke")) {
                try {
                    int[] viewport=viewportState();int width=Gdx.graphics.getWidth(),height=Gdx.graphics.getHeight();
                    boolean fullscreen=Gdx.graphics.isFullscreen();
                    if (Boolean.getBoolean("battleaudio.expectedFullscreen") && !fullscreen)
                        throw new AssertionError("Fullscreen combat test did not enter fullscreen mode");
                    BattleAudioMod.instance.openSettings();
                    requireDisplayState(viewport,width,height,fullscreen,"combat speaker open");
                    battleaudio.ui.FilterUiSmoke.encounterCombatViewportChecks(getSettings());
                    getSettings().close();
                    requireDisplayState(viewport,width,height,fullscreen,"combat speaker close");
                    System.out.println("PASS: in-combat speaker opens and closes without changing display state");
                    finish();
                } catch(Throwable failure) { failure.printStackTrace(); Gdx.app.exit(); }
                return;
            }
            if (Boolean.getBoolean("battleaudio.encounterSmoke")) {
                try { actualEncounterSelectionChecks(); finish(); }
                catch (Throwable failure) { failure.printStackTrace(); Gdx.app.exit(); }
                return;
            }
            if (Boolean.getBoolean("battleaudio.gremlinSummonSmoke")) {
                try { actualGremlinSummonChecks(); finish(); }
                catch (Throwable failure) { failure.printStackTrace(); Gdx.app.exit(); }
                return;
            }
            try {
                if (Boolean.getBoolean("battleaudio.prerequisiteSmoke")) actualPrerequisiteChecks();
                else if (Boolean.getBoolean("battleaudio.stopProbabilitySmoke")) actualStopProbabilityChecks();
                else if (Boolean.getBoolean("battleaudio.deathSmoke")) actualDeathChecks(); else actualEventChecks();
                finish();
            } catch(Throwable failure) { failure.printStackTrace(); Gdx.app.exit(); }
        }
        if (phase == 3 && phaseFrames == 15) {
            if (com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.currentAction != pausedAction
                    || duration(pausedAction) != pausedDuration) throw new AssertionError("Combat action advanced while settings open");
            screenshot = true;
            if (!blockedButton.clicked || !com.megacrit.cardcrawl.dungeons.AbstractDungeon.overlayMenu.endTurnButton.enabled
                    || com.megacrit.cardcrawl.helpers.input.InputHelper.justClickedLeft
                    || com.megacrit.cardcrawl.helpers.input.InputHelper.isMouseDown
                    || com.megacrit.cardcrawl.helpers.input.InputHelper.scrolledDown)
                throw new AssertionError("Modal leaked click/drag/wheel into game overlay");
            System.out.println("PASS: game settings pause actual combat action");
            System.out.println("PASS: modal consumes click/drag/wheel and even queued end-turn clicks cannot execute beneath it");
        }
        if (phase == 3 && phaseFrames == 20) { blockedButton.clicked = blockedButton.clickStarted = false; closeSettings(); phase = 4; phaseFrames = 0; }
        if (phase == 4 && phaseFrames == 30) {
            if (BattleAudioMod.instance.settingsOpen() || basemod.BaseMod.modSettingsUp)
                throw new AssertionError("Settings state did not release after closing");
            if (com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.currentAction == pausedAction
                    && duration(pausedAction) == pausedDuration) throw new AssertionError("Combat did not resume after settings closed");
            System.out.println("PASS: actual combat action resumes after settings close");
            finish();
        }
        if (phase == 8 && phaseFrames == 30) {
            editorScroll().setScrollY(0);
            wheelOver(editorScroll(), 100);
            float rightScroll = editorScroll().getScrollY();
            wheelOver(libraryScroll(), 100);
            if (getStage().getScrollFocus() != libraryScroll())
                throw new AssertionError("Wheel still requires clicking to switch from right to left pane");
            if (libraryScroll().getScrollY() <= 0 || editorScroll().getScrollY() != rightScroll)
                throw new AssertionError("Wheel over left pane did not move only the left pane");
            float leftScroll = libraryScroll().getScrollY();
            wheelOver(editorScroll(), 100);
            if (getStage().getScrollFocus() != editorScroll() || libraryScroll().getScrollY() != leftScroll)
                throw new AssertionError("Wheel over right pane did not move only the right pane");
            System.out.println("PASS: pointer switches wheel between left and right panes without clicking");
        }
        if (phase == 8 && phaseFrames == 45) {
            if (editorScroll().getScrollY() <= 0) throw new AssertionError("Embedded editor did not scroll");
            screenshot = true;
            System.out.println("PASS: Config rules and playback scroll inside shared full overlay");
        }
        if (phase == 8 && phaseFrames == 50) editorScroll().setScrollY(0);
        if (phase == 8 && phaseFrames == 70) screenshot = true;
        if (phase == 8 && phaseFrames == 80) {
            closeEmbeddedPanel();
            if (BattleAudioMod.instance.settingsOpen()) throw new AssertionError("Config panel did not close embedded editor");
            openEmbeddedPanel();
            String actual = ((com.badlogic.gdx.scenes.scene2d.ui.TextField)getStage().getRoot().findActor("audio-name")).getText();
            if (!"Config 未保存修改".equals(actual)) throw new AssertionError("Embedded Config did not auto-save edits: " + actual);
            enterText(originalName); activate("保存");
            System.out.println("PASS: native Config Close keeps valid edits and restores input");
            activate("添加候选音频");
            com.megacrit.cardcrawl.helpers.input.InputHelper.pressedEscape = true;
            getSettings().update();
            if (!getEmbeddedPanel().isUp || (Boolean)settingField("browsing") || !((com.badlogic.gdx.scenes.scene2d.Actor)settingField("root")).isVisible())
                throw new AssertionError("Escape from browser closed Config");
            closeEmbeddedWithEscape(); openEmbeddedPanel();
            expandCandidate();
            activate("更换文件");
            openSampleDirectory();
            Path sample = Paths.get(System.getProperty("battleaudio.sample"));
            Path second = Paths.get(System.getProperty("battleaudio.sample2"));
            activate(sample.getFileName().toString()); activate(second.getFileName().toString());
            assertPicked(1);
            if (((com.badlogic.gdx.scenes.scene2d.ui.TextButton)visibleActor(getStage().getRoot(), sample.getFileName().toString())).isChecked())
                throw new AssertionError("Replacement still visually selects multiple files");
            activate("返回设置");
            System.out.println("PASS: replacement enforces single selection and matches visual state");
            activate("新建规则"); activate("添加候选音频"); chooseSample();
        }
    }
    private void resume() {
        new com.megacrit.cardcrawl.screens.mainMenu.MenuButton(
                com.megacrit.cardcrawl.screens.mainMenu.MenuButton.ClickResult.RESUME_GAME, 0).buttonEffect();
        phase = 2; phaseFrames = 0;
    }
    private void startFreshRun() {
        new com.megacrit.cardcrawl.screens.mainMenu.MenuButton(
                com.megacrit.cardcrawl.screens.mainMenu.MenuButton.ClickResult.PLAY, 0).buttonEffect();
        try {
            com.megacrit.cardcrawl.screens.mainMenu.MainMenuPanelButton panel =
                    new com.megacrit.cardcrawl.screens.mainMenu.MainMenuPanelButton(
                            com.megacrit.cardcrawl.screens.mainMenu.MainMenuPanelButton.PanelClickResult.PLAY_NORMAL,
                            com.megacrit.cardcrawl.screens.mainMenu.MainMenuPanelButton.PanelColor.RED, 0, 0);
            java.lang.reflect.Method effect = panel.getClass().getDeclaredMethod("buttonEffect");
            effect.setAccessible(true); effect.invoke(panel);
            com.megacrit.cardcrawl.screens.charSelect.CharacterSelectScreen select =
                    CardCrawlGame.mainMenuScreen.charSelectScreen;
            com.megacrit.cardcrawl.screens.charSelect.CharacterOption option = select.options.get(0);
            option.selected = true;
            CardCrawlGame.chosenCharacter = option.c.chosenClass;
            select.confirmButton.isDisabled = false;
            select.confirmButton.hb.clicked = true;
            select.updateButtons();
            phase = 10; phaseFrames = 0;
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private float duration(com.megacrit.cardcrawl.actions.AbstractGameAction action) {
        if (action == null) return 0;
        try {
            java.lang.reflect.Field field = com.megacrit.cardcrawl.actions.AbstractGameAction.class.getDeclaredField("duration");
            field.setAccessible(true); return field.getFloat(action);
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private void closeSettings() {
        getSettings().close();
    }
    private com.badlogic.gdx.scenes.scene2d.ui.ScrollPane editorScroll() {
        try {
            java.lang.reflect.Field field = battleaudio.ui.SettingsScreen.class.getDeclaredField("editorScroll"); field.setAccessible(true);
            return (com.badlogic.gdx.scenes.scene2d.ui.ScrollPane)field.get(getSettings());
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private com.badlogic.gdx.scenes.scene2d.ui.ScrollPane libraryScroll() {
        try {
            java.lang.reflect.Field field = battleaudio.ui.SettingsScreen.class.getDeclaredField("libraryList"); field.setAccessible(true);
            return (com.badlogic.gdx.scenes.scene2d.ui.ScrollPane)((com.badlogic.gdx.scenes.scene2d.Actor)field.get(getSettings())).getParent();
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private void closeEmbeddedWithEscape() {
        com.megacrit.cardcrawl.helpers.input.InputHelper.pressedEscape = true;
        getSettings().update();
        if (BattleAudioMod.instance.settingsOpen()) throw new AssertionError("Escape did not close Config");
        System.out.println("PASS: Escape returns from file browser and closes Config editor");
    }
    private void chooseSample() {
        try {
            rulesBeforeImport = draft().rules.size(); expectedImports = 1;
            Path sample = Paths.get(System.getProperty("battleaudio.sample"));
            if (Boolean.getBoolean("battleaudio.configSmoke")) {
                setPath("C:\\"); activate("打开路径");
                if (!Paths.get("C:\\").equals(settingField("directory"))) throw new AssertionError("Cannot browse C drive root");
                activate("上一级");
                if (settingField("directory") != null) throw new AssertionError("Up from C root did not open drive selection");
                activate("[磁盘] D:\\");
                if (!Paths.get("D:\\").equals(settingField("directory"))) throw new AssertionError("Drive selection did not open D root");
                System.out.println("PASS: Up from C root lists drives and opens D without typing its path");
            }
            openSampleDirectory(); activate(sample.getFileName().toString());
            if (Boolean.getBoolean("battleaudio.configSmoke")) {
                Path second = Paths.get(System.getProperty("battleaudio.sample2"));
                activate(second.getFileName().toString()); assertPicked(2);
                activate(sample.getFileName().toString()); assertPicked(1);
                activate(sample.getFileName().toString()); assertPicked(2);
                expectedImports = 2;
                System.out.println("PASS: multiple selection, deselection and checked file states agree");
            }
            phase = 6; phaseFrames = 0;
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private battleaudio.model.Config loadSettings(battleaudio.storage.AudioLibrary library) {
        try {
            battleaudio.ui.SettingsScreen screen=getSettings();
            if(screen!=null && !screen.flushPendingChanges()) throw new AssertionError("Debounced auto-save could not be flushed before reading settings");
            return library.load();
        } catch(Exception ex) { throw new RuntimeException(ex); }
    }
    private Object settingField(String name) {
        try {
            java.lang.reflect.Field field = battleaudio.ui.SettingsScreen.class.getDeclaredField(name); field.setAccessible(true);
            return field.get(getSettings());
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private battleaudio.model.Config draft() { return (battleaudio.model.Config)settingField("draft"); }
    private void setPath(String text) { ((com.badlogic.gdx.scenes.scene2d.ui.TextField)settingField("pathField")).setText(text); }
    private void openSampleDirectory() {
        setPath(Paths.get(System.getProperty("battleaudio.sample")).getParent().toString()); activate("打开路径");
    }
    private void assertPicked(int expected) {
        if (((java.util.List<?>)settingField("pickedFiles")).size() != expected) throw new AssertionError("Wrong number of selected files");
        int checked = 0;
        for (Object entry : ((java.util.Map<?, ?>)settingField("fileButtons")).values())
            if (((com.badlogic.gdx.scenes.scene2d.ui.TextButton)entry).isChecked()) checked++;
        if (checked != expected) throw new AssertionError("Visual file selection does not match actual selection");
    }
    private void addScrollFixtures() {
        for (int i = 0; i < 12; i++) {
            battleaudio.model.Config.Rule rule = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(draft().rules.get(0)), battleaudio.model.Config.Rule.class);
            rule.id = java.util.UUID.randomUUID().toString(); rule.name = "滚轮检查 " + i; draft().rules.add(rule);
        }
        try {
            java.lang.reflect.Method method = battleaudio.ui.SettingsScreen.class.getDeclaredMethod("list"); method.setAccessible(true); method.invoke(getSettings());
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private basemod.ModPanel getEmbeddedPanel() {
        try {
            java.lang.reflect.Field field = BattleAudioMod.class.getDeclaredField("settingsPanel");
            field.setAccessible(true);
            return (basemod.ModPanel) field.get(BattleAudioMod.instance);
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private void openEmbeddedPanel() {
        try {
            Class<?> menu = Class.forName("com.evacipated.cardcrawl.modthespire.patches.modsscreen.ModMenuButton");
            boolean opened = false;
            for (com.megacrit.cardcrawl.screens.mainMenu.MenuButton button : CardCrawlGame.mainMenuScreen.buttons) {
                if ("MODS".equals(button.result.name())) { button.buttonEffect(); opened = true; break; }
            }
            if (!opened) throw new AssertionError("Mods menu entry missing");
            java.lang.reflect.Field screenField = menu.getDeclaredField("modsScreen"); screenField.setAccessible(true);
            modsScreen = screenField.get(null);
            java.lang.reflect.Field selected = modsScreen.getClass().getDeclaredField("selectedMod"); selected.setAccessible(true);
            java.lang.reflect.Field badges = modsScreen.getClass().getDeclaredField("baseModBadges"); badges.setAccessible(true);
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) badges.get(null);
            java.lang.reflect.Method click = modsScreen.getClass().getDeclaredMethod("modBadge_onClick", Object.class); click.setAccessible(true);
            com.evacipated.cardcrawl.modthespire.ModInfo[] infos = com.evacipated.cardcrawl.modthespire.Loader.MODINFOS;
            for (int i = 0; i < infos.length; i++) if ("battleaudio".equals(infos[i].ID)) {
                selected.setInt(modsScreen, i);
                Object badge = map.get(infos[i].jarURL);
                java.lang.reflect.Field texture = badge.getClass().getDeclaredField("texture"); texture.setAccessible(true);
                if (texture.get(badge) != battleaudio.ui.AudioIcon.badgeTexture()) throw new AssertionError("Mods list uses different icon");
                com.badlogic.gdx.graphics.Texture badgeIcon=(com.badlogic.gdx.graphics.Texture)texture.get(badge);
                if(badgeIcon.getWidth()!=32 || badgeIcon.getHeight()!=32) throw new AssertionError("Mods badge exceeds row dimensions");
                com.badlogic.gdx.graphics.g2d.SpriteBatch batch=new com.badlogic.gdx.graphics.g2d.SpriteBatch();
                batch.setProjectionMatrix(new com.badlogic.gdx.math.Matrix4().setToOrtho2D(0,0,Settings.WIDTH,Settings.HEIGHT));
                try { batch.begin(); modsScreen.getClass().getMethod("render",com.badlogic.gdx.graphics.g2d.SpriteBatch.class).invoke(modsScreen,batch); batch.end(); saveScreenshot("mods-list.png"); }
                finally { batch.dispose(); }
                click.invoke(modsScreen, map.get(infos[i].jarURL));
                getEmbeddedPanel().update();
                com.badlogic.gdx.scenes.scene2d.ui.Image icon = getStage().getRoot().findActor("audio-icon");
                if (((com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable)icon.getDrawable()).getRegion().getTexture() != battleaudio.ui.AudioIcon.texture())
                    throw new AssertionError("Settings title uses different icon");
                System.out.println("PASS: Mods list, settings title and top panel share speaker texture");
                return;
            }
            throw new AssertionError("BattleAudio missing from Mods list");
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private void closeEmbeddedPanel() {
        try {
            java.lang.reflect.Field field = modsScreen.getClass().getDeclaredField("button"); field.setAccessible(true);
            com.megacrit.cardcrawl.screens.mainMenu.MenuCancelButton button =
                    (com.megacrit.cardcrawl.screens.mainMenu.MenuCancelButton) field.get(modsScreen);
            button.hb.clicked = true;
            modsScreen.getClass().getMethod("update").invoke(modsScreen);
            if (Gdx.input.getInputProcessor() != getEmbeddedPanel().oldInputProcessor)
                throw new AssertionError("Config close did not restore previous input processor");
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private battleaudio.ui.SettingsScreen getSettings() {
        try {
            java.lang.reflect.Field field = BattleAudioMod.class.getDeclaredField("settings"); field.setAccessible(true);
            return (battleaudio.ui.SettingsScreen) field.get(BattleAudioMod.instance);
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private com.badlogic.gdx.scenes.scene2d.Stage getStage() {
        try {
            java.lang.reflect.Field field = battleaudio.ui.SettingsScreen.class.getDeclaredField("stage"); field.setAccessible(true);
            return (com.badlogic.gdx.scenes.scene2d.Stage) field.get(getSettings());
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private void rightClickRule(battleaudio.model.Config.Rule rule) {
        com.badlogic.gdx.scenes.scene2d.Actor actor=visibleActor(getStage().getRoot(),"music-rule-name-"+rule.id);
        if(actor==null)throw new AssertionError("Rule row missing for context menu: "+rule.id);
        getSettings().render((com.badlogic.gdx.graphics.g2d.SpriteBatch)getStage().getBatch());
        com.badlogic.gdx.math.Vector2 point=actor.localToStageCoordinates(new com.badlogic.gdx.math.Vector2(actor.getWidth()*.35f,actor.getHeight()/2));
        com.badlogic.gdx.scenes.scene2d.InputEvent event=new com.badlogic.gdx.scenes.scene2d.InputEvent();
        event.setType(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown);event.setStageX(point.x);event.setStageY(point.y);
        event.setPointer(0);event.setButton(com.badlogic.gdx.Input.Buttons.RIGHT);actor.fire(event);getStage().act(0);
        if(getStage().getRoot().findActor("settings-context-menu")==null)throw new AssertionError("Right-click did not open the rule context menu");
    }

    private void activate(String name) {
        if (name.equals("保存")) {
            try {
                battleaudio.storage.AudioLibrary library = (battleaudio.storage.AudioLibrary)settingField("library");
                battleaudio.model.Config loaded = loadSettings(library);
                if (!new com.google.gson.Gson().toJson(loaded).equals(new com.google.gson.Gson().toJson(draft())))
                    throw new AssertionError("Auto-save did not persist configuration for restart");
                return;
            } catch (Exception ex) { throw new RuntimeException(ex); }
        }
        com.badlogic.gdx.scenes.scene2d.Actor actor = visibleActor(getStage().getRoot(), name);
        if (actor == null) throw new AssertionError("Missing in-game control: " + name);
        for (com.badlogic.gdx.scenes.scene2d.Actor layer : getStage().getRoot().getChildren())
            if (layer instanceof com.badlogic.gdx.scenes.scene2d.utils.Layout)
                ((com.badlogic.gdx.scenes.scene2d.utils.Layout)layer).validate();
        reveal(actor);
        // Refresh ScrollPane widget placement before a second click in the same game frame.
        getSettings().render((com.badlogic.gdx.graphics.g2d.SpriteBatch)getStage().getBatch());
        com.badlogic.gdx.math.Vector2 point = actor.localToStageCoordinates(
                new com.badlogic.gdx.math.Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        getStage().stageToScreenCoordinates(point);
        if (!getStage().touchDown(Math.round(point.x), Math.round(point.y), 0, 0)) {
            com.badlogic.gdx.math.Vector2 location = getStage().screenToStageCoordinates(new com.badlogic.gdx.math.Vector2(point));
            throw new AssertionError("In-game control did not receive click: " + name + " screen=" + point + " actor=" + actor.getX() + "," + actor.getY() + "," + actor.getWidth() + "," + actor.getHeight() + " hit=" + getStage().hit(location.x, location.y, true));
        }
        getStage().touchUp(Math.round(point.x), Math.round(point.y), 0, 0);
    }
    private com.badlogic.gdx.scenes.scene2d.Actor visibleActor(com.badlogic.gdx.scenes.scene2d.Actor actor, String name) {
        if (!actor.isVisible()) return null;
        if (name.equals(actor.getName())) return actor;
        if (actor instanceof com.badlogic.gdx.scenes.scene2d.Group)
            for (com.badlogic.gdx.scenes.scene2d.Actor child : ((com.badlogic.gdx.scenes.scene2d.Group)actor).getChildren()) {
                com.badlogic.gdx.scenes.scene2d.Actor match = visibleActor(child, name);
                if (match != null) return match;
            }
        return null;
    }
    private void reveal(com.badlogic.gdx.scenes.scene2d.Actor actor) {
        com.badlogic.gdx.scenes.scene2d.Actor ancestor = actor.getParent();
        while (ancestor != null && !(ancestor instanceof com.badlogic.gdx.scenes.scene2d.ui.ScrollPane)) ancestor = ancestor.getParent();
        if (ancestor == null) return;
        com.badlogic.gdx.scenes.scene2d.ui.ScrollPane pane = (com.badlogic.gdx.scenes.scene2d.ui.ScrollPane)ancestor;
        pane.validate();
        com.badlogic.gdx.math.Vector2 point = actor.localToAscendantCoordinates(pane.getWidget(), new com.badlogic.gdx.math.Vector2());
        pane.scrollTo(point.x, point.y, actor.getWidth(), actor.getHeight()); pane.updateVisualScroll();
        getStage().act(0);
    }
    private void wheelOver(com.badlogic.gdx.scenes.scene2d.Actor actor, int amount) {
        if (actor == null) throw new AssertionError("Missing scroll pane");
        com.badlogic.gdx.math.Vector2 point = actor.localToStageCoordinates(
                new com.badlogic.gdx.math.Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        getStage().stageToScreenCoordinates(point);
        getStage().mouseMoved(Math.round(point.x), Math.round(point.y));
        getStage().scrolled(amount);
    }
    private void collectSliders(com.badlogic.gdx.scenes.scene2d.Actor actor, java.util.List<com.badlogic.gdx.scenes.scene2d.ui.Slider> result) {
        if (actor instanceof com.badlogic.gdx.scenes.scene2d.ui.Slider) result.add((com.badlogic.gdx.scenes.scene2d.ui.Slider)actor);
        if (actor instanceof com.badlogic.gdx.scenes.scene2d.Group)
            for (com.badlogic.gdx.scenes.scene2d.Actor child : ((com.badlogic.gdx.scenes.scene2d.Group)actor).getChildren()) collectSliders(child, result);
    }
    private com.badlogic.gdx.math.Vector2 sliderPoint(com.badlogic.gdx.scenes.scene2d.ui.Slider slider, float fraction) {
        float left = slider.getStyle().background.getLeftWidth(), right = slider.getStyle().background.getRightWidth();
        float knob = slider.getStyle().knob.getMinWidth();
        com.badlogic.gdx.math.Vector2 point = slider.localToStageCoordinates(new com.badlogic.gdx.math.Vector2(
                left + knob / 2 + fraction * (slider.getWidth() - left - right - knob), slider.getHeight() / 2));
        return getStage().stageToScreenCoordinates(point);
    }
    private void sliderChecks() {
        java.util.List<com.badlogic.gdx.scenes.scene2d.ui.Slider> sliders = new java.util.ArrayList<>();
        collectSliders(getStage().getRoot(), sliders);
        if (sliders.size() != 4) throw new AssertionError("Expected segment, volume and background sliders");
        for (com.badlogic.gdx.scenes.scene2d.ui.Slider slider : sliders) {
            reveal(slider); getSettings().render((com.badlogic.gdx.graphics.g2d.SpriteBatch)getStage().getBatch());
            float fraction = slider.getName().equals("segment-end") ? 0.9f : 0.3f;
            com.badlogic.gdx.math.Vector2 start = sliderPoint(slider, fraction);
            getStage().touchDown(Math.round(start.x), Math.round(start.y), 0, 0);
            getStage().act(0.05f);
            com.badlogic.gdx.math.Vector2 end = sliderPoint(slider, fraction + 0.07f);
            getStage().mouseMoved(Math.round(end.x), Math.round(end.y));
            getStage().touchDragged(Math.round(end.x), Math.round(end.y), 0);
            getStage().act(0.05f); getStage().cancelTouchFocus();
            float actual = (slider.getValue() - slider.getMinValue()) / (slider.getMaxValue() - slider.getMinValue());
            if (Math.abs(actual - fraction - 0.07f) > 0.04f) throw new AssertionError("Scaled slider drag failed " + slider.getName() + "=" + actual);
        }
        System.out.println("PASS: all scrolled game sliders click/drag at " + Settings.WIDTH + "x" + Settings.HEIGHT + ", scale=" + Settings.scale);
    }
    private void expandCandidate() {
        if (visibleActor(getStage().getRoot(),"audio-volume") != null) return;
        battleaudio.model.Config.Rule rule = (battleaudio.model.Config.Rule)settingField("selected");
        if (rule == null || rule.candidates.isEmpty()) {
            battleaudio.model.Config.Rule playable=draft().rules.stream().filter(r -> r.playable()).findFirst().orElseThrow(() -> new AssertionError("No playable fixture"));
            activate(playable.name); getSettings().update(); rule=(battleaudio.model.Config.Rule)settingField("selected");
        }
        try {
            java.lang.reflect.Field expanded=battleaudio.ui.SettingsScreen.class.getDeclaredField("expandedCandidate"); expanded.setAccessible(true);
            expanded.set(getSettings(),rule.candidates.get(0).id);
            java.lang.reflect.Method bind=battleaudio.ui.SettingsScreen.class.getDeclaredMethod("bind",boolean.class); bind.setAccessible(true); bind.invoke(getSettings(),true);
            getSettings().update(); getSettings().render((com.badlogic.gdx.graphics.g2d.SpriteBatch)getStage().getBatch()); getStage().act(0.05f);
        } catch(Exception ex) { throw new RuntimeException(ex); }
        if (visibleActor(getStage().getRoot(),"audio-volume") == null)
            throw new AssertionError("Candidate editor did not expand for selected rule: "+rule.name);
    }
    private void assertEmptyRule() {
        battleaudio.model.Config.Rule rule=(battleaudio.model.Config.Rule)settingField("selected");
        if(rule==null || !rule.candidates.isEmpty() || rule.playable() || visibleActor(getStage().getRoot(),"empty-candidates")==null)
            throw new AssertionError("Rule not empty or missing empty-state label");
    }
    private void candidateButtonLayoutChecks() {
        String[] names={"试听","停止试听","复制音频","删除音频"};
        com.badlogic.gdx.math.Rectangle[] bounds=new com.badlogic.gdx.math.Rectangle[4];
        for(int i=0;i<names.length;i++) {
            com.badlogic.gdx.scenes.scene2d.ui.TextButton button=(com.badlogic.gdx.scenes.scene2d.ui.TextButton)visibleActor(getStage().getRoot(),names[i]);
            reveal(button); getSettings().render((com.badlogic.gdx.graphics.g2d.SpriteBatch)getStage().getBatch());
            com.badlogic.gdx.math.Vector2 p=button.localToAscendantCoordinates(button.getParent().getParent(),new com.badlogic.gdx.math.Vector2());
            bounds[i]=new com.badlogic.gdx.math.Rectangle(p.x,p.y,button.getWidth(),button.getHeight());
            if(button.getLabel().getPrefWidth()+30>button.getWidth()) throw new AssertionError("Candidate action lacks text padding: "+names[i]);
            if(p.x<0 || p.x+button.getWidth()>button.getParent().getParent().getWidth()+1) throw new AssertionError("Candidate action outside item: "+names[i]);
        }
        for(int i=0;i<4;i++) for(int j=i+1;j<4;j++) if(bounds[i].overlaps(bounds[j])) throw new AssertionError("Candidate action overlap");
        if(bounds[1].x-bounds[0].x-bounds[0].width<19 || bounds[3].x-bounds[2].x-bounds[2].width<39
                || bounds[0].y-bounds[2].y-bounds[2].height<13)
            throw new AssertionError("Candidate actions missing distinct row/gaps");
        reveal(visibleActor(getStage().getRoot(),"删除音频"));
        System.out.println("PASS: candidate controls fit with text padding, preview gap, second row and larger deletion gap");
    }
    private void nativeStyleChecks() {
        com.badlogic.gdx.scenes.scene2d.ui.Skin skin=(com.badlogic.gdx.scenes.scene2d.ui.Skin)settingField("skin");
        com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle normal=skin.get(com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle.class);
        com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle choice=skin.get("choice",com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle.class);
        if(normal.checked!=null || normal.up==normal.down || normal.over==normal.up || normal.disabled==null || choice.checked==null)
            throw new AssertionError("Command/hover/down/disabled/selected states not separated");
        activate("停止试听");
        if(((com.badlogic.gdx.scenes.scene2d.ui.TextButton)visibleActor(getStage().getRoot(),"停止试听")).isChecked())
            throw new AssertionError("Ordinary action retains selected state after click");
        if(editorScroll().getVariableSizeKnobs() || libraryScroll().getVariableSizeKnobs()) throw new AssertionError("Native scroll handle stretched");
        com.badlogic.gdx.scenes.scene2d.ui.Slider slider=visibleActor(getStage().getRoot(),"audio-volume") instanceof com.badlogic.gdx.scenes.scene2d.ui.Slider
                ? (com.badlogic.gdx.scenes.scene2d.ui.Slider)visibleActor(getStage().getRoot(),"audio-volume") : null;
        if(slider==null || !(slider.getStyle().knob instanceof com.badlogic.gdx.scenes.scene2d.utils.SpriteDrawable)
                || ((com.badlogic.gdx.scenes.scene2d.utils.SpriteDrawable)slider.getStyle().knob).getSprite().getTexture()!=com.megacrit.cardcrawl.helpers.ImageMaster.OPTION_SLIDER)
            throw new AssertionError("Slider does not reuse native texture");
        com.badlogic.gdx.graphics.Texture[] shared={com.megacrit.cardcrawl.helpers.ImageMaster.OPTION_SLIDER,com.megacrit.cardcrawl.helpers.ImageMaster.OPTION_TOGGLE,
                com.megacrit.cardcrawl.helpers.ImageMaster.OPTION_TOGGLE_ON,com.megacrit.cardcrawl.helpers.ImageMaster.INPUT_SETTINGS_ROW,
                com.megacrit.cardcrawl.helpers.ImageMaster.SCROLL_BAR_TRAIN,com.megacrit.cardcrawl.helpers.ImageMaster.SETTINGS_BACKGROUND};
        if(sharedTextureHandles==null) {
            sharedTextureHandles=new int[shared.length]; for(int i=0;i<shared.length;i++) sharedTextureHandles[i]=shared[i].getTextureObjectHandle();
            sharedFontScale=com.megacrit.cardcrawl.helpers.FontHelper.tipBodyFont.getData().scaleX;
        } else sharedResourceChecks();
        for(com.badlogic.gdx.graphics.Texture texture:shared) {
            if(skin.getAll(com.badlogic.gdx.graphics.Texture.class).containsValue(texture,true)) throw new AssertionError("Skin owns shared ImageMaster texture");
        }
        System.out.println("PASS: distinct action/choice/disabled states, no sticky command, native slider texture and fixed scroll knob");
    }
    private void sharedResourceChecks() {
        for(int handle:sharedTextureHandles) if(!Gdx.gl.glIsTexture(handle)) throw new AssertionError("Closing settings disposed game shared texture");
        if(com.megacrit.cardcrawl.helpers.FontHelper.tipBodyFont.getData().scaleX!=sharedFontScale) throw new AssertionError("Changed shared font scale");
        System.out.println("PASS: close/reopen leaves all shared native textures alive and shared font unchanged");
    }
    private void candidateUiChecks() {
        expandCandidate();
        battleaudio.model.Config.Rule original=(battleaudio.model.Config.Rule)settingField("selected");
        java.util.EnumSet<battleaudio.model.Config.Trigger> triggers=original.triggers.clone();
        if(visibleActor(getStage().getRoot(),"复制音频")==null) {
            battleaudio.model.Config.Rule diagnostic=(battleaudio.model.Config.Rule)settingField("selected");
            StringBuilder details=new StringBuilder("Candidate action missing selected=").append(diagnostic==null?"null":diagnostic.name)
                    .append(" candidates=").append(diagnostic==null?-1:diagnostic.candidates.size())
                    .append(" volume=").append(visibleActor(getStage().getRoot(),"audio-volume")!=null);
            if(diagnostic!=null) for(battleaudio.model.Config.Candidate c:diagnostic.candidates)
                details.append(" ").append(c.id).append("/").append(visibleActor(getStage().getRoot(),"edit-candidate-"+c.id)!=null);
            throw new AssertionError(details.toString());
        }
        activate("复制音频"); getSettings().update();
        int expected=Boolean.getBoolean("battleaudio.candidateSmoke") ? 4 : 2;
        if(original.candidates.size()!=expected || !original.triggers.equals(triggers) || original.candidates.get(0).id.equals(original.candidates.get(1).id))
            throw new AssertionError("Candidate copy changed shared conditions/identity");
        rightClickRule((battleaudio.model.Config.Rule)settingField("selected")); activate("menu-copy-rule"); getSettings().update();
        battleaudio.model.Config.Rule copy=(battleaudio.model.Config.Rule)settingField("selected");
        if(copy.id.equals(original.id) || !copy.triggers.equals(triggers) || copy.candidates.size()!=expected) throw new AssertionError("Rule copy lost shared config");
        activate("新建规则"); getSettings().update();
        battleaudio.model.Config.Rule fresh=(battleaudio.model.Config.Rule)settingField("selected");
        if(!fresh.triggers.isEmpty() || !fresh.candidates.isEmpty() || fresh.playbackMode!=battleaudio.model.Config.PlaybackMode.ONCE
                || fresh.randomMode!=battleaudio.model.Config.RandomMode.AVOID_REPEAT || fresh.retriggerMode!=battleaudio.model.Config.RetriggerMode.REPLACE)
            throw new AssertionError("New UI rule defaults incorrect");
        activate("删除"); getSettings().update();
        System.out.println("PASS: inline candidate expansion, independent copy identity, shared trigger preservation, new empty-trigger rule defaults");
    }
    private Object modField(String name) {
        try { java.lang.reflect.Field field = BattleAudioMod.class.getDeclaredField(name); field.setAccessible(true); return field.get(BattleAudioMod.instance); }
        catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private void setModField(String name, Object value) {
        try { java.lang.reflect.Field field = BattleAudioMod.class.getDeclaredField(name); field.setAccessible(true); field.set(BattleAudioMod.instance, value); }
        catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private void typeField(com.badlogic.gdx.scenes.scene2d.ui.TextField field, String text) {
        getStage().setKeyboardFocus(field); field.selectAll();
        getStage().keyTyped((char)8);
        for (char character : text.toCharArray()) getStage().keyTyped(character);
    }
    private void ensureTrigger(String trigger) {
        com.badlogic.gdx.scenes.scene2d.ui.CheckBox box=getStage().getRoot().findActor("trigger-"+trigger);
        if(box==null) throw new AssertionError("Condition absent from current category: "+trigger);
        if(!box.isChecked()) activate("trigger-"+trigger);
        getSettings().update();
        box=getStage().getRoot().findActor("trigger-"+trigger);
        if(box==null || !box.isChecked()) throw new AssertionError("Condition did not stay enabled: "+trigger);
    }
    private void enableFilter(String name) {
        com.badlogic.gdx.scenes.scene2d.ui.CheckBox box=getStage().getRoot().findActor(name);
        if(box==null) throw new AssertionError("Filter toggle absent: "+name);
        if(!box.isChecked()) activate(name);
        getSettings().update();
    }
    private void selectAdvanced(String name) {
        com.badlogic.gdx.scenes.scene2d.ui.SelectBox<?> select=getStage().getRoot().findActor(name);
        if(select==null) throw new AssertionError("Identifier mode absent: "+name);
        select.setSelectedIndex(select.getItems().size-1); getSettings().update();
    }
    private void autoSaveChecks() {
        expandCandidate();
        if (getStage().getRoot().findActor("保存") != null) throw new AssertionError("Manual Save still visible");
        com.badlogic.gdx.scenes.scene2d.ui.Label status = getStage().getRoot().findActor("settings-status");
        com.badlogic.gdx.scenes.scene2d.ui.Table root = (com.badlogic.gdx.scenes.scene2d.ui.Table)settingField("root");
        root.validate();
        if (status.isVisible() || status.getHeight() != 0) throw new AssertionError("Normal status reserves layout height");
        float availableHeight = ((com.badlogic.gdx.scenes.scene2d.Actor)settingField("body")).getHeight();
        if (((com.badlogic.gdx.scenes.scene2d.ui.Image)getStage().getRoot().findActor("audio-icon")).getDrawable() == null)
            throw new AssertionError("Shared speaker icon missing");
        com.badlogic.gdx.scenes.scene2d.ui.Slider volume = getStage().getRoot().findActor("audio-volume");
        reveal(volume); getSettings().render((com.badlogic.gdx.graphics.g2d.SpriteBatch)getStage().getBatch());
        com.badlogic.gdx.math.Vector2 point = sliderPoint(volume, 0.45f);
        getStage().touchDown(Math.round(point.x), Math.round(point.y), 0, 0); getStage().touchUp(Math.round(point.x), Math.round(point.y), 0, 0);
        battleaudio.audio.PlaybackManager playback = (battleaudio.audio.PlaybackManager)modField("playback");
        battleaudio.model.Config.Rule rule = (battleaudio.model.Config.Rule)settingField("selected");
        playback.start(rule, "preview"); playback.start(rule, "smoke-game");
        if (playback.count() != 2) throw new AssertionError("Preview and automatic audio not active before edit");
        enterText("自动保存与即时停止检查");
        if (playback.count() != 0) throw new AssertionError("Settings edit did not immediately stop all audio");
        if (battleaudio.audio.BackgroundMusic.factor()<1 && !battleaudio.audio.BackgroundMusic.recovering())
            throw new AssertionError("Settings edit left background music permanently attenuated");
        battleaudio.audio.BackgroundMusic.update(1);
        if (battleaudio.audio.BackgroundMusic.factor()!=1)throw new AssertionError("Settings edit background recovery did not complete");
        activate("保存");
        com.badlogic.gdx.scenes.scene2d.ui.CheckBox hp = getStage().getRoot().findActor("trigger-LOW_HEALTH");
        if (!hp.isChecked()) activate("trigger-LOW_HEALTH");
        getSettings().update();
        if (!((com.badlogic.gdx.scenes.scene2d.ui.CheckBox)getStage().getRoot().findActor("trigger-HP_LOSS")).isChecked())
            activate("trigger-HP_LOSS");
        getSettings().update();
        enableFilter("min-amount-enabled-HP_LOSS");
        com.badlogic.gdx.scenes.scene2d.ui.TextField amountFilter=getStage().getRoot().findActor("min-amount-HP_LOSS");
        if(amountFilter==null) throw new AssertionError("HP loss amount filter missing in game settings");
        typeField(amountFilter,"2");
        battleaudio.storage.AudioLibrary filterLibrary=(battleaudio.storage.AudioLibrary)settingField("library");
        try {
            if(filterLibrary.load().rules.get(0).filter(battleaudio.model.Config.Trigger.HP_LOSS).minAmount!=2)
                throw new AssertionError("HP loss amount filter did not persist from game UI");
            String validFilterConfig=new com.google.gson.Gson().toJson(filterLibrary.load());
            typeField(amountFilter,"");
            if(!validFilterConfig.equals(new com.google.gson.Gson().toJson(filterLibrary.load())))
                throw new AssertionError("Incomplete amount filter persisted");
            typeField(amountFilter,"2");
        } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
        com.badlogic.gdx.scenes.scene2d.ui.TextField threshold = getStage().getRoot().findActor("health-threshold");
        if (threshold == null) throw new AssertionError("Low-health parameter missing");
        battleaudio.storage.AudioLibrary library = (battleaudio.storage.AudioLibrary)settingField("library");
        try {
            String saved = new com.google.gson.Gson().toJson(loadSettings(library));
            typeField(threshold, "");
            root.validate();
            if (!status.isVisible() || status.getHeight() <= 0 || ((com.badlogic.gdx.scenes.scene2d.Actor)settingField("body")).getHeight() >= availableHeight)
                throw new AssertionError("Invalid edit did not expand dynamic error row");
            if (!saved.equals(new com.google.gson.Gson().toJson(loadSettings(library)))) throw new AssertionError("Empty numeric edit was persisted");
            typeField(threshold, "3");
            saved = new com.google.gson.Gson().toJson(loadSettings(library));
            getStage().keyTyped('.');
            if (!saved.equals(new com.google.gson.Gson().toJson(loadSettings(library)))) throw new AssertionError("Incomplete numeric edit was persisted");
            typeField(threshold, "35"); activate("保存");
            root.validate();
            if (status.isVisible() || status.getHeight() != 0 || Math.abs(((com.badlogic.gdx.scenes.scene2d.Actor)settingField("body")).getHeight() - availableHeight) > 0.1f)
                throw new AssertionError("Resolved error/save success still occupies space");
            Path backup = library.root().resolve("settings.json.bak"), held = library.root().resolve("smoke-held-backup.json");
            Files.move(backup, held); Files.createDirectory(backup); Files.write(backup.resolve("blocker"), new byte[] {1});
            String beforeFailure = new com.google.gson.Gson().toJson(loadSettings(library));
            try {
                enterText("保存失败测试"); root.validate();
                if (!status.isVisible() || status.getHeight() <= 0 || !beforeFailure.equals(new com.google.gson.Gson().toJson(loadSettings(library))))
                    throw new AssertionError("Save failure hidden or persisted an invalid partial write");
            } finally { Files.delete(backup.resolve("blocker")); Files.delete(backup); Files.move(held, backup); }
            enterText("自动保存与即时停止检查"); root.validate();
            if (status.isVisible() || status.getHeight() != 0) throw new AssertionError("Save recovery left status row");
            com.badlogic.gdx.scenes.scene2d.ui.SelectBox<?> mode = getStage().getRoot().findActor("playback-mode");
            mode.setSelectedIndex(1); getSettings().update();
            com.badlogic.gdx.scenes.scene2d.ui.TextField count = getStage().getRoot().findActor("loop-count");
            saved = new com.google.gson.Gson().toJson(loadSettings(library)); typeField(count, "0");
            if (!saved.equals(new com.google.gson.Gson().toJson(loadSettings(library)))) throw new AssertionError("Zero loop count persisted");
            typeField(count, "2"); saved = new com.google.gson.Gson().toJson(loadSettings(library));
            getStage().keyTyped('.'); getStage().keyTyped('5');
            if (!saved.equals(new com.google.gson.Gson().toJson(loadSettings(library)))) throw new AssertionError("Fractional loop count persisted");
            typeField(count, "3"); activate("保存");
            if (loadSettings(library).rules.get(0).loopCount != 3) throw new AssertionError("Valid finite total not auto-saved");
            activate("trigger-EXHAUST_CARD"); getSettings().update();
            boolean exhaust = ((com.badlogic.gdx.scenes.scene2d.ui.CheckBox)getStage().getRoot().findActor("trigger-EXHAUST_CARD")).isChecked();
            saved = new com.google.gson.Gson().toJson(loadSettings(library));
            playback.start(rule, "preview");
            com.badlogic.gdx.scenes.scene2d.ui.SelectBox<?> categories = getStage().getRoot().findActor("trigger-category");
            categories.setSelectedIndex(1); getSettings().update();
            if (((com.badlogic.gdx.scenes.scene2d.ui.CheckBox)getStage().getRoot().findActor("trigger-EXHAUST_CARD")).isChecked() != exhaust
                    || !saved.equals(new com.google.gson.Gson().toJson(loadSettings(library))) || playback.count() != 1)
                throw new AssertionError("Category navigation changes config, stops audio or duplicates shared condition");
            playback.stopAll();
            categories.setSelectedIndex(0); getSettings().update();
            for (String trigger : new String[] {"SHOP_OPEN", "COMBAT_CARD_REWARD_OPEN"}) {
                if (!((com.badlogic.gdx.scenes.scene2d.ui.CheckBox)getStage().getRoot().findActor("trigger-" + trigger)).isChecked()) activate("trigger-" + trigger);
                getSettings().update();
                if (!loadSettings(library).rules.get(0).triggers.contains(battleaudio.model.Config.Trigger.valueOf(trigger)))
                    throw new AssertionError("New general condition did not save independently: " + trigger);
            }
            ensureTrigger("SHOP_BUY_RELIC");
            if(getStage().getRoot().findActor("cadence-SHOP_BUY_RELIC")!=null)
                throw new AssertionError("Shop relic purchase exposes combat cadence options");
            categories = getStage().getRoot().findActor("trigger-category"); categories.setSelectedIndex(3); getSettings().update();
            for (String trigger : new String[] {"FOCUS_INCREASE", "DARK_ACCUMULATE", "ORB_PASSIVE"}) {
                if (!((com.badlogic.gdx.scenes.scene2d.ui.CheckBox)getStage().getRoot().findActor("trigger-" + trigger)).isChecked()) activate("trigger-" + trigger);
                getSettings().update();
                if (!loadSettings(library).rules.get(0).triggers.contains(battleaudio.model.Config.Trigger.valueOf(trigger)))
                    throw new AssertionError("New Defect condition did not save independently: " + trigger);
            }
            if(getStage().getRoot().findActor("cadence-ORB_PASSIVE")==null)
                throw new AssertionError("Orb passive condition lacks relevant combat cadence options");
            // This suite runs for Config and overlay in the same process. Do not toggle an already enabled event off.
            ensureTrigger("ORB_PASSIVE_DAMAGE");
            activate("trigger-ORB_PASSIVE_DAMAGE"); getSettings().update();
            if(getStage().getRoot().findActor("min-amount-ORB_PASSIVE_DAMAGE")!=null
                    || getStage().getRoot().findActor("target-id-ORB_PASSIVE_DAMAGE")!=null)
                throw new AssertionError("Disabled Lightning trigger still exposes filter controls");
            ensureTrigger("ORB_PASSIVE_DAMAGE");
            enableFilter("min-amount-enabled-ORB_PASSIVE_DAMAGE");
            selectAdvanced("target-id-ORB_PASSIVE_DAMAGE-mode");
            com.badlogic.gdx.scenes.scene2d.ui.TextField lightningAmount=getStage().getRoot().findActor("min-amount-ORB_PASSIVE_DAMAGE");
            com.badlogic.gdx.scenes.scene2d.ui.TextField lightningTarget=getStage().getRoot().findActor("target-id-ORB_PASSIVE_DAMAGE");
            if(lightningAmount==null || lightningTarget==null) throw new AssertionError("Lightning damage filter controls missing");
            typeField(lightningAmount,"4"); typeField(lightningTarget,"Cultist"); getSettings().update();
            battleaudio.model.Config.EventFilter savedLightning=loadSettings(library).rules.get(0).filter(
                    battleaudio.model.Config.Trigger.ORB_PASSIVE_DAMAGE);
            if(savedLightning.minAmount!=4 || !"Cultist".equals(savedLightning.targetId))
                throw new AssertionError("Lightning damage filter did not persist from game UI");
            rightClickRule((battleaudio.model.Config.Rule)settingField("selected")); activate("menu-copy-rule"); getSettings().update();
            battleaudio.model.Config.EventFilter copiedLightning=loadSettings(library).rules.get(loadSettings(library).rules.size()-1).filter(
                    battleaudio.model.Config.Trigger.ORB_PASSIVE_DAMAGE);
            if(copiedLightning.minAmount!=4 || !"Cultist".equals(copiedLightning.targetId))
                throw new AssertionError("Copied rule lost Lightning damage filter");
            typeField(getStage().getRoot().findActor("target-id-ORB_PASSIVE_DAMAGE"),"JawWorm"); getSettings().update();
            if(!"Cultist".equals(loadSettings(library).rules.get(0).filter(battleaudio.model.Config.Trigger.ORB_PASSIVE_DAMAGE).targetId))
                throw new AssertionError("Editing copied Lightning filter changed the source rule");
            categories=getStage().getRoot().findActor("trigger-category"); categories.setSelectedIndex(2); getSettings().update();
            ensureTrigger("SHIV_DAMAGE");
            enableFilter("min-amount-enabled-SHIV_DAMAGE");
            typeField(getStage().getRoot().findActor("min-amount-SHIV_DAMAGE"),"6"); getSettings().update();
            if(loadSettings(library).rules.get(loadSettings(library).rules.size()-1).filter(battleaudio.model.Config.Trigger.SHIV_DAMAGE).minAmount!=6)
                throw new AssertionError("Shiv actual damage filter did not save from game UI");
            categories=getStage().getRoot().findActor("trigger-category"); categories.setSelectedIndex(4); getSettings().update();
            ensureTrigger("SCRY_CARDS");
            typeField(getStage().getRoot().findActor("threshold-SCRY_CARDS"),"2");
            if(loadSettings(library).rules.get(loadSettings(library).rules.size()-1).parameter(battleaudio.model.Config.Trigger.SCRY_CARDS).value!=2)
                throw new AssertionError("Scry card count threshold did not save from game UI");
            ensureTrigger("SCRY_DISCARD_CARD");
            selectAdvanced("card-id-SCRY_DISCARD_CARD-mode");
            typeField(getStage().getRoot().findActor("card-id-SCRY_DISCARD_CARD"),"Strike_R");
            if(!"Strike_R".equals(loadSettings(library).rules.get(loadSettings(library).rules.size()-1)
                    .filter(battleaudio.model.Config.Trigger.SCRY_DISCARD_CARD).cardId))
                throw new AssertionError("Scry result card ID filter did not save from game UI");
            for(String trigger:new String[]{"DIVINITY_DAMAGE","DARK_EVOKE_DAMAGE","PLASMA_EVOKE_ENERGY"}) {
                categories=getStage().getRoot().findActor("trigger-category");
                categories.setSelectedIndex(trigger.equals("DIVINITY_DAMAGE")?4:3); getSettings().update();
                activate("trigger-"+trigger); getSettings().update();
                enableFilter("min-amount-enabled-"+trigger);
                typeField(getStage().getRoot().findActor("min-amount-"+trigger),"3");
                battleaudio.model.Config.Trigger value=battleaudio.model.Config.Trigger.valueOf(trigger);
                if(loadSettings(library).rules.get(loadSettings(library).rules.size()-1).filter(value).minAmount!=3)
                    throw new AssertionError("Actual effect filter did not save: "+trigger);
            }
            categories=getStage().getRoot().findActor("trigger-category"); categories.setSelectedIndex(4); getSettings().update();
            ensureTrigger("STANCE_CHANGE");
            ((com.badlogic.gdx.scenes.scene2d.ui.SelectBox<?>)getStage().getRoot().findActor("previous-state-STANCE_CHANGE")).setSelectedIndex(4);
            ((com.badlogic.gdx.scenes.scene2d.ui.SelectBox<?>)getStage().getRoot().findActor("next-state-STANCE_CHANGE")).setSelectedIndex(1);
            categories=getStage().getRoot().findActor("trigger-category"); categories.setSelectedIndex(0); getSettings().update();
            for(String trigger:new String[]{"EVENT_OPEN","EVENT_OPTION_COMPLETED","MAP_NODE_ENTER","ACT_ENTER","COMBAT_CARD_REWARD_OBTAIN","REMOVE_DECK_CARD","COPY_DECK_CARD","TRANSFORM_DECK_CARD","ATTACK_MULTI_KILL","LOW_HEALTH_FIRST_BLOCK"}) {
                com.badlogic.gdx.scenes.scene2d.ui.CheckBox box=getStage().getRoot().findActor("trigger-"+trigger);
                if(box==null) throw new AssertionError("New verified condition absent from game UI: "+trigger);
                if(!box.isChecked()) activate("trigger-"+trigger); getSettings().update();
            }
            selectAdvanced("scene-id-EVENT_OPEN-mode");
            selectAdvanced("event-option-mode");
            selectAdvanced("scene-id-MAP_NODE_ENTER-mode");
            selectAdvanced("scene-id-ACT_ENTER-mode");
            typeField(getStage().getRoot().findActor("scene-id-EVENT_OPEN"),"Big Fish");
            typeField(getStage().getRoot().findActor("event-option"),"0");
            typeField(getStage().getRoot().findActor("scene-id-MAP_NODE_ENTER"),"EventRoom");
            typeField(getStage().getRoot().findActor("scene-id-ACT_ENTER"),"TheCity");
            categories=getStage().getRoot().findActor("trigger-category"); categories.setSelectedIndex(3); getSettings().update();
            com.badlogic.gdx.scenes.scene2d.ui.CheckBox evokeBox=getStage().getRoot().findActor("trigger-EVOKE_ORB");
            if(!evokeBox.isChecked()) activate("trigger-EVOKE_ORB"); getSettings().update();
            ((com.badlogic.gdx.scenes.scene2d.ui.SelectBox<?>)getStage().getRoot().findActor("evoke-cause")).setSelectedIndex(2);
            typeField(getStage().getRoot().findActor("orb-position"),"1");
            categories=getStage().getRoot().findActor("trigger-category"); categories.setSelectedIndex(4); getSettings().update();
            com.badlogic.gdx.scenes.scene2d.ui.CheckBox mantraBox=getStage().getRoot().findActor("trigger-MANTRA_INCREASE");
            if(!mantraBox.isChecked()) activate("trigger-MANTRA_INCREASE"); getSettings().update();
            enableFilter("min-amount-enabled-MANTRA_INCREASE");
            typeField(getStage().getRoot().findActor("min-amount-MANTRA_INCREASE"),"2");
            rightClickRule((battleaudio.model.Config.Rule)settingField("selected")); activate("menu-copy-rule"); getSettings().update();
            categories=getStage().getRoot().findActor("trigger-category"); categories.setSelectedIndex(3); getSettings().update();
            enableFilter("min-amount-enabled-DARK_EVOKE_DAMAGE");
            typeField(getStage().getRoot().findActor("min-amount-DARK_EVOKE_DAMAGE"),"7");
            battleaudio.model.Config reload=loadSettings(library);
            if(reload.rules.get(reload.rules.size()-2).filter(battleaudio.model.Config.Trigger.DARK_EVOKE_DAMAGE).minAmount!=3)
                throw new AssertionError("Editing copied Dark filter changed source rule");
            battleaudio.model.Config.EventFilter stanceCopy=reload.rules.get(reload.rules.size()-1).filter(battleaudio.model.Config.Trigger.STANCE_CHANGE);
            if(!"Divinity".equals(stanceCopy.previousState) || !"Neutral".equals(stanceCopy.nextState))
                throw new AssertionError("Stance transition filters did not persist through copy/reload");
            battleaudio.model.Config.Rule verifiedCopy=reload.rules.get(reload.rules.size()-1);
            if(!"OVERFLOW".equals(verifiedCopy.filter(battleaudio.model.Config.Trigger.EVOKE_ORB).evokeCause)
                    || verifiedCopy.filter(battleaudio.model.Config.Trigger.EVOKE_ORB).orbPosition!=1
                    || !"Big Fish".equals(verifiedCopy.filter(battleaudio.model.Config.Trigger.EVENT_OPEN).sourceId)
                    || !"0".equals(verifiedCopy.filter(battleaudio.model.Config.Trigger.EVENT_OPTION_COMPLETED).previousState)
                    || verifiedCopy.filter(battleaudio.model.Config.Trigger.MANTRA_INCREASE).minAmount!=2)
                throw new AssertionError("Verified filters did not persist/copy from native UI");
            System.out.println("PASS: all eleven verified triggers visible and configurable; event entry, scene IDs, event option, orb cause/position, Mantra amount save/reload/copy");
        } catch (Exception ex) { throw new RuntimeException(ex); }
        System.out.println("PASS: no Save button; valid edits immediately stop all audio/apply/persist; empty and incomplete numbers never saved");
        System.out.println("PASS: finite count UI rejects zero/fractions; category aliases share state and navigation does not edit settings");
        System.out.println("PASS: status row takes zero normal height, expands on invalid input/save failure, and releases height on recovery");
        System.out.println("PASS: all five new condition checkboxes independently persist from game UI");
    }
    private int eventCount(battleaudio.model.Config.Trigger trigger) { return actualEvents.getOrDefault(trigger.name(), 0); }
    private void assertEvent(battleaudio.model.Config.Trigger trigger, int expected) {
        if (eventCount(trigger) != expected) throw new AssertionError("Actual patched event " + trigger + " expected=" + expected + " actual=" + eventCount(trigger));
        System.out.println("PASS: actual patched " + trigger + " x" + expected);
    }
    /** Real-game regression loop for the vanilla Gremlin Leader two-minion summon sequence. */
    private void actualGremlinSummonChecks() {
        com.megacrit.cardcrawl.rooms.AbstractRoom previous = com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room;
        com.megacrit.cardcrawl.actions.GameActionManager manager = com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager;
        try {
            com.megacrit.cardcrawl.rooms.MonsterRoomElite room = new com.megacrit.cardcrawl.rooms.MonsterRoomElite();
            com.megacrit.cardcrawl.monsters.city.GremlinLeader leader = new com.megacrit.cardcrawl.monsters.city.GremlinLeader();
            com.megacrit.cardcrawl.monsters.AbstractMonster first = new com.megacrit.cardcrawl.monsters.exordium.GremlinFat(0, 0);
            com.megacrit.cardcrawl.monsters.AbstractMonster second = new com.megacrit.cardcrawl.monsters.exordium.GremlinWarrior(0, 0);
            room.monsters = new com.megacrit.cardcrawl.monsters.MonsterGroup(
                    new com.megacrit.cardcrawl.monsters.AbstractMonster[]{leader, first, second});
            room.phase = com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room = room;
            leader.init(); first.init(); second.init();
            leader.gremlins[0]=leader; leader.gremlins[1]=first; leader.gremlins[2]=second;
            BattleAudioMod.instance.observeEvents();
            leader.gremlins[1].isDying = true; leader.gremlins[2].isDying = true;
            manager.clear();
            try {
                com.megacrit.cardcrawl.monsters.AbstractMonster one=new com.megacrit.cardcrawl.monsters.exordium.GremlinFat(
                        com.megacrit.cardcrawl.monsters.city.GremlinLeader.POSX[1],com.megacrit.cardcrawl.monsters.city.GremlinLeader.POSY[1]);
                com.megacrit.cardcrawl.monsters.AbstractMonster two=new com.megacrit.cardcrawl.monsters.exordium.GremlinWarrior(
                        com.megacrit.cardcrawl.monsters.city.GremlinLeader.POSX[2],com.megacrit.cardcrawl.monsters.city.GremlinLeader.POSY[2]);
                com.megacrit.cardcrawl.actions.unique.SummonGremlinAction firstSummon=new com.megacrit.cardcrawl.actions.unique.SummonGremlinAction(leader.gremlins);
                com.megacrit.cardcrawl.actions.unique.SummonGremlinAction secondSummon=new com.megacrit.cardcrawl.actions.unique.SummonGremlinAction(leader.gremlins);
                java.lang.reflect.Field monster=com.megacrit.cardcrawl.actions.unique.SummonGremlinAction.class.getDeclaredField("m"); monster.setAccessible(true);
                monster.set(firstSummon,one); monster.set(secondSummon,two);
                manager.addToBottom(new com.megacrit.cardcrawl.actions.common.ChangeStateAction(leader,"CALL"));
                manager.addToBottom(firstSummon); manager.addToBottom(secondSummon);
            } catch(Exception ex){throw new RuntimeException(ex);}

            int actions = 0;
            while (!manager.actions.isEmpty() && actions++ < 100) {
                com.megacrit.cardcrawl.actions.AbstractGameAction action=manager.actions.remove(0);
                if(action instanceof com.megacrit.cardcrawl.actions.common.ApplyPowerAction) continue;
                runAction(action);
                BattleAudioMod.instance.observeEvents();
            }
            if (!manager.actions.isEmpty()) throw new AssertionError("Gremlin Leader summon action queue did not drain");
            if (room.monsters.monsters.size() != 5)
                throw new AssertionError("Vanilla Gremlin Leader summon did not add two monsters; count="+room.monsters.monsters.size()+" drainedActions="+actions+" gremlinSlots="+java.util.Arrays.toString(leader.gremlins));
            int spawned=0; for(com.megacrit.cardcrawl.monsters.AbstractMonster monster:room.monsters.monsters)
                if(monster!=leader && monster!=first && monster!=second && !monster.isDying) spawned++;
            if(spawned!=2)throw new AssertionError("Gremlin Leader should add two living gremlins, got "+spawned);
            for(int frame=0;frame<120;frame++)BattleAudioMod.instance.observeEvents();
            System.out.println("PASS: Gremlin Leader pre-battle MinionPower and two-summon sequence; enemy observation remained stable for 120 updates");
        } finally {
            manager.clear();
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room=previous;
        }
    }

    private void runAction(com.megacrit.cardcrawl.actions.AbstractGameAction action) {
        for (int i = 0; i < 300 && !action.isDone; i++) action.update();
    }
    private void drainActions() {
        com.megacrit.cardcrawl.actions.GameActionManager manager = com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager;
        for (int i = 0; i < 1000 && !manager.actions.isEmpty(); i++) runAction(manager.actions.remove(0));
        if (!manager.actions.isEmpty()) throw new AssertionError("Game action queue did not drain");
    }
    private void vanillaEventOptionChecks(com.megacrit.cardcrawl.characters.AbstractPlayer player) {
        int originalHealth=player.currentHealth;
        Object originalEngine=modField("engine"), originalEvents=modField("events");
        boolean originalWait=com.megacrit.cardcrawl.events.GenericEventDialog.waitForInput;
        int originalOption=com.megacrit.cardcrawl.events.GenericEventDialog.selectedOption;
        try {
            setModField("engine",null); setModField("events",null);
            player.currentHealth=Math.max(1,player.maxHealth/2); player.healthBarUpdatedEvent();
            com.megacrit.cardcrawl.events.exordium.BigFish event=
                    new com.megacrit.cardcrawl.events.exordium.BigFish();
            event.waitTimer=0;
            int before=player.currentHealth;
            com.megacrit.cardcrawl.events.GenericEventDialog.selectedOption=0;
            com.megacrit.cardcrawl.events.GenericEventDialog.waitForInput=true;
            event.update();
            if(player.currentHealth!=before)
                throw new AssertionError("Big Fish event effect ran while the vanilla dialog was still waiting");
            com.megacrit.cardcrawl.events.GenericEventDialog.waitForInput=false;
            event.update();
            if(player.currentHealth<=before)
                throw new AssertionError("Completed Big Fish option did not execute its vanilla heal effect");
            System.out.println("PASS: vanilla BigFish option is inert while waiting and its completed selected option heals the player");
        } finally {
            com.megacrit.cardcrawl.events.GenericEventDialog.cleanUp();
            com.megacrit.cardcrawl.events.GenericEventDialog.waitForInput=originalWait;
            com.megacrit.cardcrawl.events.GenericEventDialog.selectedOption=originalOption;
            player.currentHealth=originalHealth; player.healthBarUpdatedEvent();
            setModField("engine",originalEngine); setModField("events",originalEvents);
        }
    }
    private void verifiedSceneOptionChecks(com.megacrit.cardcrawl.characters.AbstractPlayer player) {
        com.megacrit.cardcrawl.rooms.AbstractRoom previous=com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom();
        int opened=eventCount(battleaudio.model.Config.Trigger.EVENT_OPEN);
        try {
            com.megacrit.cardcrawl.rooms.EventRoom room=new com.megacrit.cardcrawl.rooms.EventRoom();
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room=room;
            room.onPlayerEntry();
            if(room.event==null) throw new AssertionError("EventRoom entry did not create an event");
            assertEvent(battleaudio.model.Config.Trigger.EVENT_OPEN,opened+1);
            room.event.waitTimer=0; room.event.hasFocus=true; room.update();
            assertEvent(battleaudio.model.Config.Trigger.EVENT_OPEN,opened+1);
            int completed=eventCount(battleaudio.model.Config.Trigger.EVENT_OPTION_COMPLETED);
            int health=player.currentHealth, maximum=player.maxHealth;
            try {
                for(int option=0;option<2;option++) {
                    com.megacrit.cardcrawl.events.exordium.BigFish fish=new com.megacrit.cardcrawl.events.exordium.BigFish(); room.event=fish;
                    fish.imageEventText.show("fixture","fixture"); fish.imageEventText.show("fixture","fixture");
                    assertEvent(battleaudio.model.Config.Trigger.EVENT_OPEN,opened+1);
                    fish.waitTimer=0; player.currentHealth=Math.max(1,player.maxHealth/2);
                    com.megacrit.cardcrawl.events.GenericEventDialog.selectedOption=option;
                    com.megacrit.cardcrawl.events.GenericEventDialog.waitForInput=true; fish.update();
                    assertEvent(battleaudio.model.Config.Trigger.EVENT_OPTION_COMPLETED,completed+option);
                    com.megacrit.cardcrawl.events.GenericEventDialog.waitForInput=false; fish.update();
                    assertEvent(battleaudio.model.Config.Trigger.EVENT_OPTION_COMPLETED,completed+option+1);
                    for(int i=0;i<3;i++)fish.update();
                    assertEvent(battleaudio.model.Config.Trigger.EVENT_OPTION_COMPLETED,completed+option+1);
                }
            } finally {
                player.maxHealth=maximum; player.currentHealth=health; player.healthBarUpdatedEvent();
                com.megacrit.cardcrawl.events.GenericEventDialog.cleanUp();
            }
            System.out.println("PASS: EventRoom entry emits once; dialog show never repeats it; both BigFish immediate options still complete once");
        } finally {
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room=previous;
        }
    }
    private void vanillaMultiKillChecks(com.megacrit.cardcrawl.characters.AbstractPlayer player) {
        com.megacrit.cardcrawl.rooms.AbstractRoom room=com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom();
        com.megacrit.cardcrawl.monsters.MonsterGroup previous=room.monsters;
        try {
            com.megacrit.cardcrawl.monsters.AbstractMonster first=
                    new com.megacrit.cardcrawl.monsters.exordium.JawWorm(0,0);
            com.megacrit.cardcrawl.monsters.AbstractMonster second=
                    new com.megacrit.cardcrawl.monsters.exordium.Cultist(0,0);
            first.currentHealth=1; first.currentBlock=0; first.isDying=false; first.isDead=false; first.escaped=false;
            second.currentHealth=1; second.currentBlock=0; second.isDying=false; second.isDead=false; second.escaped=false;
            room.monsters=new com.megacrit.cardcrawl.monsters.MonsterGroup(
                    new com.megacrit.cardcrawl.monsters.AbstractMonster[]{first,second});
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
            int multiEvents=eventCount(battleaudio.model.Config.Trigger.ATTACK_MULTI_KILL);
            com.megacrit.cardcrawl.actions.common.DamageAllEnemiesAction aoe=new com.megacrit.cardcrawl.actions.common.DamageAllEnemiesAction(player,new int[]{10,10},
                    com.megacrit.cardcrawl.cards.DamageInfo.DamageType.NORMAL,
                    com.megacrit.cardcrawl.actions.AbstractGameAction.AttackEffect.NONE);
            runAction(aoe);
            drainActions();
            if(first.currentHealth>0 || second.currentHealth>0
                    || !(first.isDying||first.isDead) || !(second.isDying||second.isDead))
                throw new AssertionError("One vanilla AoE did not kill both fresh one-HP enemies");
            assertEvent(battleaudio.model.Config.Trigger.ATTACK_MULTI_KILL,multiEvents+1);
            aoe.update(); assertEvent(battleaudio.model.Config.Trigger.ATTACK_MULTI_KILL,multiEvents+1);
            first.currentHealth=second.currentHealth=1; first.isDying=second.isDying=false; first.isDead=second.isDead=false;
            runAction(new com.megacrit.cardcrawl.actions.common.DamageAllEnemiesAction(player,new int[]{10,10},
                    com.megacrit.cardcrawl.cards.DamageInfo.DamageType.THORNS,com.megacrit.cardcrawl.actions.AbstractGameAction.AttackEffect.NONE));
            assertEvent(battleaudio.model.Config.Trigger.ATTACK_MULTI_KILL,multiEvents+1);
            System.out.println("PASS: one NORMAL player AoE attack emits multi-kill once; repeated update and THORNS multi-kill do not");
        } finally {
            room.monsters=previous;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
        }
    }    private void vanillaMapNodeTransitionChecks() {
        com.megacrit.cardcrawl.map.MapRoomNode current=com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrMapNode();
        java.util.ArrayList<java.util.ArrayList<com.megacrit.cardcrawl.map.MapRoomNode>> map=
                com.megacrit.cardcrawl.dungeons.AbstractDungeon.map;
        if(current==null || map==null || current.y+1>=map.size())
            throw new AssertionError("Continued-run fixture has no next map row");
        com.megacrit.cardcrawl.map.MapRoomNode next=null;
        for(com.megacrit.cardcrawl.map.MapRoomNode candidate:map.get(current.y+1))
            if(candidate!=null && candidate.getRoom()!=null && candidate.getParents().contains(current)) {
                next=candidate; break;
            }
        if(next==null) throw new AssertionError("Continued-run fixture has no connected next map node");
        int previousFloor=com.megacrit.cardcrawl.dungeons.AbstractDungeon.floorNum;
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.nextRoom=next;
        int mapEvents=eventCount(battleaudio.model.Config.Trigger.MAP_NODE_ENTER);
        com.megacrit.cardcrawl.core.CardCrawlGame.dungeon.nextRoomTransition();
        assertEvent(battleaudio.model.Config.Trigger.MAP_NODE_ENTER,mapEvents+1);
        if(com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrMapNode()!=next
                || com.megacrit.cardcrawl.dungeons.AbstractDungeon.floorNum!=previousFloor+1
                || com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom()!=next.getRoom())
            throw new AssertionError("Vanilla map transition did not enter the selected node and advance floor");
        System.out.println("PASS: vanilla connected MapRoomNode transition enters its room and advances the floor");
    }
    private void vanillaEventRoomChecks() {
        com.megacrit.cardcrawl.rooms.AbstractRoom previous=com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room;
        try {
            com.megacrit.cardcrawl.rooms.EventRoom eventRoom=new com.megacrit.cardcrawl.rooms.EventRoom();
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room=eventRoom;
            eventRoom.onPlayerEntry();
            if(eventRoom.event==null) throw new AssertionError("Vanilla EventRoom did not create an AbstractEvent on entry");
            eventRoom.event.waitTimer=0; eventRoom.event.hasFocus=true;
            eventRoom.update();
            System.out.println("PASS: vanilla EventRoom entry creates an event object and routes room updates to it");
        } finally {
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room=previous;
        }
    }    private void vanillaLowHealthAndMantraChecks(com.megacrit.cardcrawl.characters.AbstractPlayer player) {
        int oldHealth=player.currentHealth, oldBlock=player.currentBlock;
        String oldStance=player.stance.ID;
        try {
            player.currentHealth=1; player.currentBlock=0; player.healthBarUpdatedEvent();
            int lowEvents=eventCount(battleaudio.model.Config.Trigger.LOW_HEALTH_FIRST_BLOCK);
            player.addBlock(0); assertEvent(battleaudio.model.Config.Trigger.LOW_HEALTH_FIRST_BLOCK,lowEvents);
            player.addBlock(5); assertEvent(battleaudio.model.Config.Trigger.LOW_HEALTH_FIRST_BLOCK,lowEvents+1);
            player.addBlock(2); assertEvent(battleaudio.model.Config.Trigger.LOW_HEALTH_FIRST_BLOCK,lowEvents+1);
            if(player.currentBlock!=7) throw new AssertionError("Low-health vanilla player did not actually gain block");
            player.currentHealth=player.maxHealth; player.currentBlock=0; player.healthBarUpdatedEvent();
            player.currentHealth=1; player.healthBarUpdatedEvent(); player.addBlock(1);
            assertEvent(battleaudio.model.Config.Trigger.LOW_HEALTH_FIRST_BLOCK,lowEvents+2);
            player.currentHealth=oldHealth; player.currentBlock=0; player.healthBarUpdatedEvent();
            player.powers.removeIf(power -> "Mantra".equals(power.ID));
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
            int mantraEvents=eventCount(battleaudio.model.Config.Trigger.MANTRA_INCREASE), spentEvents=eventCount(battleaudio.model.Config.Trigger.MANTRA_SPENT_DIVINITY);
            runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(player,player,
                    new com.megacrit.cardcrawl.powers.watcher.MantraPower(player,10),10));
            assertEvent(battleaudio.model.Config.Trigger.MANTRA_INCREASE,mantraEvents+1);
            assertEvent(battleaudio.model.Config.Trigger.MANTRA_SPENT_DIVINITY,spentEvents);
            boolean queued=com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.actions.stream()
                    .anyMatch(action -> action instanceof com.megacrit.cardcrawl.actions.watcher.ChangeStanceAction);
            if(queued) throw new AssertionError("Initial vanilla Mantra application unexpectedly queued a Divinity stance change");
            player.powers.removeIf(power -> "Mantra".equals(power.ID));
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
            player.stance=new com.megacrit.cardcrawl.stances.NeutralStance();
            com.megacrit.cardcrawl.powers.watcher.MantraPower mantra=
                    new com.megacrit.cardcrawl.powers.watcher.MantraPower(player,9);
            player.powers.add(mantra);
            mantra.stackPower(0); assertEvent(battleaudio.model.Config.Trigger.MANTRA_INCREASE,mantraEvents+1);
            mantra.stackPower(1);
            assertEvent(battleaudio.model.Config.Trigger.MANTRA_INCREASE,mantraEvents+2);
            assertEvent(battleaudio.model.Config.Trigger.MANTRA_SPENT_DIVINITY,spentEvents);
            boolean stackQueued=com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.actions.stream()
                    .anyMatch(action -> action instanceof com.megacrit.cardcrawl.actions.watcher.ChangeStanceAction);
            if(!stackQueued) throw new AssertionError("Vanilla Mantra stackPower(1) at ten did not queue Divinity");
            drainActions();
            if(!"Divinity".equals(player.stance.ID) || player.getPower("Mantra")!=null)
                throw new AssertionError("Vanilla Mantra ten-stack path did not consume and enter Divinity");
            assertEvent(battleaudio.model.Config.Trigger.MANTRA_SPENT_DIVINITY,spentEvents+1);
            System.out.println("PASS: low-health first actual block gain; initial ten Mantra gain is not spend, stacking gain precedes actual Divinity spend");
        } finally {
            player.currentHealth=oldHealth; player.currentBlock=oldBlock; player.healthBarUpdatedEvent();
            player.powers.removeIf(power -> "Mantra".equals(power.ID));
            player.stance=new com.megacrit.cardcrawl.stances.NeutralStance();
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
        }
    }    private void vanillaCardOperationChecks(com.megacrit.cardcrawl.characters.AbstractPlayer player) {
        java.util.ArrayList<com.megacrit.cardcrawl.cards.AbstractCard> savedDeck=new java.util.ArrayList<>(player.masterDeck.group);
        com.megacrit.cardcrawl.cards.AbstractCard oldTransformed=com.megacrit.cardcrawl.dungeons.AbstractDungeon.transformedCard;
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectsQueue.clear();
        try {
            com.megacrit.cardcrawl.cards.AbstractCard removable=new com.megacrit.cardcrawl.cards.red.Anger();
            player.masterDeck.addToTop(removable);
            int removeEvents=eventCount(battleaudio.model.Config.Trigger.REMOVE_DECK_CARD);
            player.masterDeck.removeCard(removable);
            assertEvent(battleaudio.model.Config.Trigger.REMOVE_DECK_CARD,removeEvents+1);
            player.masterDeck.removeCard(removable);
            assertEvent(battleaudio.model.Config.Trigger.REMOVE_DECK_CARD,removeEvents+1);
            if(player.masterDeck.group.contains(removable))
                throw new AssertionError("Vanilla CardGroup.removeCard did not complete a master-deck removal");
            com.megacrit.cardcrawl.cards.AbstractCard source=new com.megacrit.cardcrawl.cards.red.Strike_Red();
            player.masterDeck.addToTop(source);
            int copyEvents=eventCount(battleaudio.model.Config.Trigger.COPY_DECK_CARD), transformEvents=eventCount(battleaudio.model.Config.Trigger.TRANSFORM_DECK_CARD);
            com.megacrit.cardcrawl.events.shrines.Duplicator duplicator=
                    new com.megacrit.cardcrawl.events.shrines.Duplicator();
            duplicator.use();
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen.selectedCards.clear();
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen.selectedCards.add(source);
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.isScreenUp=false;
            int beforeCopy=player.masterDeck.group.size();
            int beforeEffects=com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectList.size();
            duplicator.update();
            if(player.masterDeck.group.size()!=beforeCopy)
                throw new AssertionError("Vanilla Duplicator did not defer copy to its obtain effect");
            if(com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectList.size()<=beforeEffects)
                throw new AssertionError("Vanilla Duplicator did not queue ShowCardAndObtainEffect");
            assertEvent(battleaudio.model.Config.Trigger.COPY_DECK_CARD,copyEvents);
            com.megacrit.cardcrawl.vfx.AbstractGameEffect copyEffect=
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectList.get(beforeEffects);
            for(int i=0;i<360 && !copyEffect.isDone;i++) copyEffect.update();
            if(player.masterDeck.group.size()!=beforeCopy+1)
                throw new AssertionError("Settled Duplicator effect did not add the copied card to masterDeck");
            assertEvent(battleaudio.model.Config.Trigger.COPY_DECK_CARD,copyEvents+1);
            copyEffect.update(); assertEvent(battleaudio.model.Config.Trigger.COPY_DECK_CARD,copyEvents+1);
            System.out.println("PASS: Duplicator copy emits only after actual deck obtain, not selection/queue or repeated effect update");            String originalId=source.cardID;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.transformCard(source);
            if(com.megacrit.cardcrawl.dungeons.AbstractDungeon.transformedCard==null
                    || originalId.equals(com.megacrit.cardcrawl.dungeons.AbstractDungeon.transformedCard.cardID))
                throw new AssertionError("Vanilla transformCard did not select a replacement card");
            assertEvent(battleaudio.model.Config.Trigger.TRANSFORM_DECK_CARD,transformEvents);
            com.megacrit.cardcrawl.cards.AbstractCard replacement=com.megacrit.cardcrawl.dungeons.AbstractDungeon.getTransformedCard();
            player.masterDeck.removeCard(source);
            com.megacrit.cardcrawl.cards.Soul soul=new com.megacrit.cardcrawl.cards.Soul(); soul.obtain(replacement); soul.obtain(replacement);
            assertEvent(battleaudio.model.Config.Trigger.TRANSFORM_DECK_CARD,transformEvents+1);
            com.megacrit.cardcrawl.cards.AbstractCard unremoved=new com.megacrit.cardcrawl.cards.red.Strike_Red(); player.masterDeck.addToTop(unremoved);
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.transformCard(unremoved);
            soul.obtain(com.megacrit.cardcrawl.dungeons.AbstractDungeon.getTransformedCard());
            assertEvent(battleaudio.model.Config.Trigger.TRANSFORM_DECK_CARD,transformEvents+1);
            System.out.println("PASS: transform requires the exact original absent AND replacement actually in masterDeck; selection alone and unremoved original excluded");
        } catch(Exception ex) { throw new RuntimeException(ex); }
        finally {
            player.masterDeck.group.clear(); player.masterDeck.group.addAll(savedDeck);
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.transformedCard=oldTransformed;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectsQueue.clear();
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen.selectedCards.clear();
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.closeCurrentScreen();
        }
    }    private void vanillaCardRewardDeckChecks(com.megacrit.cardcrawl.characters.AbstractPlayer player) {
        java.util.ArrayList<com.megacrit.cardcrawl.cards.AbstractCard> saved=new java.util.ArrayList<>(player.masterDeck.group);
        com.megacrit.cardcrawl.rooms.AbstractRoom originalRoom=com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom();
        com.megacrit.cardcrawl.rewards.RewardItem originalReward=com.megacrit.cardcrawl.dungeons.AbstractDungeon.cardRewardScreen.rItem;
        com.megacrit.cardcrawl.rooms.MonsterRoom rewardRoom=new com.megacrit.cardcrawl.rooms.MonsterRoom(); rewardRoom.isBattleOver=true;
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room=rewardRoom;
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.cardRewardScreen.rItem=new com.megacrit.cardcrawl.rewards.RewardItem();
        int rewardEvents=eventCount(battleaudio.model.Config.Trigger.COMBAT_CARD_REWARD_OBTAIN);
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectsQueue.clear();
        try {
            com.megacrit.cardcrawl.cards.AbstractCard rewardCard=new com.megacrit.cardcrawl.cards.red.Anger();
            player.masterDeck.removeCard(rewardCard.cardID);
            java.lang.reflect.Method acquire=com.megacrit.cardcrawl.screens.CardRewardScreen.class
                    .getDeclaredMethod("acquireCard",com.megacrit.cardcrawl.cards.AbstractCard.class);
            acquire.setAccessible(true);
            acquire.invoke(com.megacrit.cardcrawl.dungeons.AbstractDungeon.cardRewardScreen,rewardCard);
            assertEvent(battleaudio.model.Config.Trigger.COMBAT_CARD_REWARD_OBTAIN,rewardEvents);
            if(player.masterDeck.group.contains(rewardCard))
                throw new AssertionError("Card reward entered masterDeck before FastCardObtainEffect settled");
            if(com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectsQueue.isEmpty()
                    || !(com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectsQueue.get(0)
                    instanceof com.megacrit.cardcrawl.vfx.FastCardObtainEffect))
                throw new AssertionError("Card reward did not queue FastCardObtainEffect");
            com.megacrit.cardcrawl.vfx.AbstractGameEffect effect=
                    (com.megacrit.cardcrawl.vfx.AbstractGameEffect)com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectsQueue.get(0);
            for(int i=0;i<120 && !effect.isDone;i++) effect.update();
            if(!player.masterDeck.group.contains(rewardCard))
                throw new AssertionError("Settled FastCardObtainEffect did not add reward to masterDeck");
            assertEvent(battleaudio.model.Config.Trigger.COMBAT_CARD_REWARD_OBTAIN,rewardEvents+1);
            effect.update(); assertEvent(battleaudio.model.Config.Trigger.COMBAT_CARD_REWARD_OBTAIN,rewardEvents+1);
            new com.megacrit.cardcrawl.cards.Soul().obtain(new com.megacrit.cardcrawl.cards.red.Anger());
            assertEvent(battleaudio.model.Config.Trigger.COMBAT_CARD_REWARD_OBTAIN,rewardEvents+1);
            System.out.println("PASS: combat reward acquire queues silently; exact card Soul.obtain triggers once; unrelated obtain is not a reward");
        } catch(Exception ex) { throw new RuntimeException(ex); }
        finally {
            player.masterDeck.group.clear(); player.masterDeck.group.addAll(saved);
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room=originalRoom;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.cardRewardScreen.rItem=originalReward;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectsQueue.clear();
        }
    }    private void vanillaOrbCauseChecks(com.megacrit.cardcrawl.characters.AbstractPlayer player) {
        java.util.ArrayList<com.megacrit.cardcrawl.orbs.AbstractOrb> oldOrbs=new java.util.ArrayList<>(player.orbs);
        int oldSlots=player.maxOrbs;
        try {
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
            battleaudio.model.Config cfg;
            // Use active test engine config, not persisted library defaults.
            java.lang.reflect.Field configField=battleaudio.core.RuleEngine.class.getDeclaredField("config"); configField.setAccessible(true);
            cfg=(battleaudio.model.Config)configField.get(modField("engine"));
            battleaudio.model.Config.Rule evoke=cfg.rules.stream().filter(r->r.id.equals("EVOKE_ORB")).findFirst().get();
            evoke.parameter(battleaudio.model.Config.Trigger.EVOKE_ORB).any=true;
            battleaudio.model.Config.EventFilter filter=evoke.filter(battleaudio.model.Config.Trigger.EVOKE_ORB); filter.evokeCause="OVERFLOW"; filter.orbPosition=1;
            evoke.filters.put(battleaudio.model.Config.Trigger.EVOKE_ORB,filter);
            int evoked=eventCount(battleaudio.model.Config.Trigger.EVOKE_ORB);
            player.orbs.clear(); player.maxOrbs=1; player.orbs.add(new com.megacrit.cardcrawl.orbs.EmptyOrbSlot());
            player.channelOrb(new com.megacrit.cardcrawl.orbs.Lightning());
            drainActions();
            player.channelOrb(new com.megacrit.cardcrawl.orbs.Frost());
            assertEvent(battleaudio.model.Config.Trigger.EVOKE_ORB,evoked);
            boolean displacement= com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.actions.stream()
                    .anyMatch(action -> action instanceof com.megacrit.cardcrawl.actions.defect.EvokeOrbAction);
            if(!displacement) throw new AssertionError("Full-slot vanilla channel did not queue EvokeOrbAction");
            drainActions();
            assertEvent(battleaudio.model.Config.Trigger.EVOKE_ORB,evoked+1);
            player.orbs.clear(); player.orbs.add(new com.megacrit.cardcrawl.orbs.Frost());
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
            player.evokeOrb();
            assertEvent(battleaudio.model.Config.Trigger.EVOKE_ORB,evoked+1);
            filter.evokeCause="ACTIVE"; player.orbs.clear(); player.orbs.add(new com.megacrit.cardcrawl.orbs.Frost());
            runAction(new com.megacrit.cardcrawl.actions.defect.EvokeOrbAction(1));
            assertEvent(battleaudio.model.Config.Trigger.EVOKE_ORB,evoked+2);
            player.orbs.clear(); player.orbs.add(new com.megacrit.cardcrawl.orbs.Frost()); filter.orbPosition=2; player.evokeOrb();
            assertEvent(battleaudio.model.Config.Trigger.EVOKE_ORB,evoked+2);
            evoke.filters.remove(battleaudio.model.Config.Trigger.EVOKE_ORB);
            boolean activeQueued=com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.actions.stream()
                    .anyMatch(action -> action instanceof com.megacrit.cardcrawl.actions.defect.EvokeOrbAction);
            if(activeQueued) throw new AssertionError("Direct vanilla evoke unexpectedly used displacement action");
            System.out.println("PASS: vanilla full-slot channel queues EvokeOrbAction; direct player evoke uses a separate immediate path");
        } catch(Exception ex) { throw new RuntimeException(ex); } finally {
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
            player.orbs.clear(); player.orbs.addAll(oldOrbs); player.maxOrbs=oldSlots;
        }
    }    private void vanillaActEntryChecks(com.megacrit.cardcrawl.characters.AbstractPlayer player) {
        player.isDead=false; player.isDying=false;
        player.currentHealth=Math.max(1,player.maxHealth/2); player.healthBarUpdatedEvent();
        int previousAct=com.megacrit.cardcrawl.dungeons.AbstractDungeon.actNum;
        int actEvents=eventCount(battleaudio.model.Config.Trigger.ACT_ENTER);
        com.megacrit.cardcrawl.core.CardCrawlGame game=(com.megacrit.cardcrawl.core.CardCrawlGame)
                ((com.badlogic.gdx.backends.lwjgl.LwjglApplication)Gdx.app).getApplicationListener();
        com.megacrit.cardcrawl.dungeons.AbstractDungeon next=game.getDungeon("TheCity",player);
        if(!(next instanceof com.megacrit.cardcrawl.dungeons.TheCity)
                || com.megacrit.cardcrawl.dungeons.AbstractDungeon.actNum!=previousAct+1
                || !"TheCity".equals(com.megacrit.cardcrawl.dungeons.AbstractDungeon.id))
            throw new AssertionError("Vanilla next-dungeon factory did not enter the next Act");
        assertEvent(battleaudio.model.Config.Trigger.ACT_ENTER,actEvents+1);
        System.out.println("PASS: vanilla nextDungeon factory constructs TheCity, increments actNum and emits ACT_ENTER once");
    }
    private void actualDeathChecks() {
        try {
            battleaudio.storage.AudioLibrary library = (battleaudio.storage.AudioLibrary)modField("library");
            battleaudio.model.Config.Rule rule = library.importAudio(Paths.get(System.getProperty("battleaudio.sample")));
            rule.triggers = java.util.EnumSet.of(battleaudio.model.Config.Trigger.PLAYER_DEATH);
            rule.roomBehavior = battleaudio.model.Config.RoomBehavior.STOP_ON_ROOM_END;
            rule.retriggerMode = battleaudio.model.Config.RetriggerMode.OVERLAP;
            rule.stopAboveHealth = false;
            rule.audio().end = Math.min(rule.audio().duration, 10);
            rule.audio().volume = 0;
            battleaudio.model.Config config = new battleaudio.model.Config(); config.rules.add(rule);
            battleaudio.audio.PlaybackManager playback = new battleaudio.audio.PlaybackManager(cache -> {
                com.badlogic.gdx.audio.Music track = Gdx.audio.newMusic(new com.badlogic.gdx.files.FileHandle(library.managed(cache).toFile()));
                eventTracks.add(track); return track;
            }, error -> { throw new AssertionError(error); });
            com.megacrit.cardcrawl.characters.AbstractPlayer p = com.megacrit.cardcrawl.dungeons.AbstractDungeon.player;
            p.powers.clear(); p.relics.clear(); p.currentBlock = 0; p.currentHealth = 2; p.isDead = false; p.isDying = false;
            battleaudio.core.RuleEngine engine = new battleaudio.core.RuleEngine(config, playback);
            engine.beginRun(false, p.currentHealth, p.maxHealth);
            setModField("config", config); setModField("engine", engine); setModField("playback", playback);
            battleaudio.core.GameEvents events = new battleaudio.core.GameEvents(engine); setModField("events", events);
            setModField("run", true); setModField("pendingRun", false);
            BattleAudioMod.instance.syncRoom();
            engine.battleStart("death-smoke", false, false, p.currentHealth, p.maxHealth); events.baseline();
            com.megacrit.cardcrawl.monsters.AbstractMonster enemy = com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters().monsters.get(0);
            p.relics.add(new com.megacrit.cardcrawl.relics.LizardTail());
            p.damage(new com.megacrit.cardcrawl.cards.DamageInfo(enemy, 100, com.megacrit.cardcrawl.cards.DamageInfo.DamageType.HP_LOSS));
            BattleAudioMod.instance.receivePostUpdate();
            if (p.isDead || playback.count() != 0) throw new AssertionError("Lizard Tail revival falsely triggers death");
            System.out.println("PASS: real lethal damage revived by Lizard Tail does not trigger death");
            p.relics.clear(); p.currentHealth = 2;
            p.damage(new com.megacrit.cardcrawl.cards.DamageInfo(enemy, 100, com.megacrit.cardcrawl.cards.DamageInfo.DamageType.HP_LOSS));
            if (!p.isDead || CardCrawlGame.isInARun()) throw new AssertionError("Fixture did not reach actual game death");
            for (int i = 0; i < 20; i++) BattleAudioMod.instance.receivePostUpdate();
            if (playback.count() != 1 || eventTracks.size() != 1 || !eventTracks.get(0).isPlaying())
                throw new AssertionError("Death audio must trigger once and survive death-screen updates: active=" + playback.count() + ", started=" + eventTracks.size());
            System.out.println("PASS: actual death triggers one playing OpenAL handle and survives 20 death-screen updates");
            CardCrawlGame.mode = CardCrawlGame.GameMode.CHAR_SELECT;
            BattleAudioMod.instance.receivePostUpdate();
            if (playback.count() != 0) throw new AssertionError("Returning to menu failed to stop death audio");
            System.out.println("PASS: leaving gameplay stops death audio according to its room policy");
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private void actualBackgroundMixChecks() {
        float listenerGain=org.lwjgl.openal.AL10.alGetListenerf(org.lwjgl.openal.AL10.AL_GAIN);
        org.lwjgl.openal.AL10.alListenerf(org.lwjgl.openal.AL10.AL_GAIN,0);
        com.badlogic.gdx.audio.Music wrapped=null,original=null;
        float nominalBefore=0,actualBefore=0;
        boolean playingBefore=false;
        battleaudio.audio.PlaybackManager manager=null;
        try {
            for(String group:new String[]{"mainTrack","tempTrack"}) {
                java.lang.reflect.Field field=CardCrawlGame.music.getClass().getDeclaredField(group);field.setAccessible(true);
                for(Object track:(java.util.List<?>)field.get(CardCrawlGame.music)) {
                    java.lang.reflect.Field musicField=track.getClass().getDeclaredField("music");musicField.setAccessible(true);
                    com.badlogic.gdx.audio.Music music=(com.badlogic.gdx.audio.Music)musicField.get(track);
                    if(music instanceof battleaudio.audio.BackgroundMusic) { wrapped=music;break; }
                }
                if(wrapped!=null)break;
            }
            if(wrapped==null)throw new AssertionError("Actual game background music was not wrapped by patches");
            java.lang.reflect.Field delegate=battleaudio.audio.BackgroundMusic.class.getDeclaredField("delegate");delegate.setAccessible(true);
            original=(com.badlogic.gdx.audio.Music)delegate.get(wrapped);nominalBefore=wrapped.getVolume();actualBefore=original.getVolume();playingBefore=wrapped.isPlaying();
            battleaudio.audio.BackgroundMusic.ratio(1);wrapped.setVolume(.8f);wrapped.play();
            battleaudio.storage.AudioLibrary library=(battleaudio.storage.AudioLibrary)modField("library");
            battleaudio.model.Config.Rule first=library.importAudio(Paths.get(System.getProperty("battleaudio.sample")));
            first.audio().volume=0;first.audio().backgroundRatio=.6f;first.playbackMode=battleaudio.model.Config.PlaybackMode.INFINITE;
            battleaudio.model.Config.Rule second=new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(first),battleaudio.model.Config.Rule.class);
            second.id="actual-background-second";second.audio().backgroundRatio=.2f;
            manager=new battleaudio.audio.PlaybackManager(library,error->{throw new AssertionError(error);});
            manager.start(first,"background-game");
            if(Math.abs(actualGain(original)-.48f)>.003f)throw new AssertionError("Actual game BGM did not use first candidate ratio");
            manager.start(second,"background-game");
            if(Math.abs(actualGain(original)-.16f)>.003f)throw new AssertionError("Actual game BGM did not use minimum candidate ratio");
            manager.stopRule(second.id);
            if(Math.abs(actualGain(original)-.16f)>.003f)throw new AssertionError("Actual game BGM restored instantly after one cue ended");
            battleaudio.audio.BackgroundMusic.update(.5f);
            if(Math.abs(actualGain(original)-.32f)>.003f)throw new AssertionError("Actual game BGM did not fade toward remaining ratio");
            battleaudio.audio.BackgroundMusic.update(.5f);manager.stopAll();battleaudio.audio.BackgroundMusic.update(.5f);
            if(Math.abs(actualGain(original)-.64f)>.003f)throw new AssertionError("Actual game BGM did not gradually restore captured original");
            first.audio().backgroundRatio=.1f;manager.start(first,"background-game");
            if(Math.abs(actualGain(original)-.08f)>.003f)throw new AssertionError("Actual recovery interruption changed original baseline");
            manager.stopAll();wrapped.setVolume(1);battleaudio.audio.BackgroundMusic.update(100);
            if(Math.abs(actualGain(original)-.8f)>.003f)throw new AssertionError("Actual game BGM recovery exceeded captured volume");
            System.out.println("PASS: actual patched game BGM candidate ratios, lowest overlap, fading remaining/final targets, recovery interruption and original gain ceiling (listener muted)");
        } catch(Exception ex){throw new RuntimeException(ex);}
        finally {
            if(manager!=null)manager.stopAll();battleaudio.audio.BackgroundMusic.ratio(1);
            if(wrapped!=null && original!=null) {wrapped.setVolume(nominalBefore);original.setVolume(actualBefore);if(!playingBefore)wrapped.pause();}
            org.lwjgl.openal.AL10.alListenerf(org.lwjgl.openal.AL10.AL_GAIN,listenerGain);
        }
    }
    private float actualGain(com.badlogic.gdx.audio.Music music) {
        int source=((com.badlogic.gdx.backends.lwjgl.audio.OpenALMusic)music).getSourceId();
        return org.lwjgl.openal.AL10.alGetSourcef(source,org.lwjgl.openal.AL10.AL_GAIN);
    }
    private void actualPrerequisiteChecks() {
        BattleAudioMod.instance.receiveOnBattleStart(com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom());
        Object previousConfig=modField("config"), previousEngine=modField("engine"), previousEvents=modField("events");
        com.megacrit.cardcrawl.characters.AbstractPlayer playerCharacter=com.megacrit.cardcrawl.dungeons.AbstractDungeon.player;
        int previousHealth=playerCharacter.currentHealth, previousMaximum=playerCharacter.maxHealth;
        int previousGold=playerCharacter.gold, previousBlock=playerCharacter.currentBlock;
        battleaudio.storage.AudioLibrary library=(battleaudio.storage.AudioLibrary)modField("library");
        java.util.List<com.badlogic.gdx.audio.Music> tracks=new java.util.ArrayList<>();
        battleaudio.audio.PlaybackManager manager=new battleaudio.audio.PlaybackManager(cache->{
            com.badlogic.gdx.audio.Music track=Gdx.audio.newMusic(new com.badlogic.gdx.files.FileHandle(library.managed(cache).toFile()));
            tracks.add(track); return track;
        },error->{throw new AssertionError(error);});
        try {
            battleaudio.model.Config.Rule bgm=library.importAudio(Paths.get(System.getProperty("battleaudio.sample")));
            bgm.audio().volume=0; bgm.playbackMode=battleaudio.model.Config.PlaybackMode.INFINITE;
            bgm.triggers=java.util.EnumSet.of(battleaudio.model.Config.Trigger.GAIN_GOLD);
            bgm.stopConditions.triggers.add(battleaudio.model.Config.Trigger.SPEND_GOLD);
            battleaudio.model.Config.Rule hurt=new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(bgm),battleaudio.model.Config.Rule.class);
            hurt.id="prerequisite-hurt-smoke"; hurt.playbackMode=battleaudio.model.Config.PlaybackMode.ONCE;
            hurt.triggers=java.util.EnumSet.of(battleaudio.model.Config.Trigger.HP_LOSS);
            hurt.stopConditions=new battleaudio.model.Config.StopConditions(); hurt.prerequisiteRuleIds.add(bgm.id);
            bgm.pauseWhenRuleIds.add(hurt.id);
            battleaudio.model.Config config=new battleaudio.model.Config(); config.rules.add(hurt); config.rules.add(bgm); config.validate();
            playerCharacter.maxHealth=100; playerCharacter.currentHealth=80; playerCharacter.currentBlock=0;
            battleaudio.core.RuleEngine engine=new battleaudio.core.RuleEngine(config,manager);
            engine.beginRun(false,80,100); engine.battleStart("prerequisite-game",false,false,80,100);
            setModField("config",config); setModField("engine",engine);
            battleaudio.core.GameEvents events=new battleaudio.core.GameEvents(engine); setModField("events",events); events.baseline();
            playerCharacter.damage(new com.megacrit.cardcrawl.cards.DamageInfo(playerCharacter,1,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.HP_LOSS));
            if(manager.count()!=0)throw new AssertionError("Actual injury triggered before BGM starts");
            bgm.triggerProbability=0; playerCharacter.gainGold(1);
            playerCharacter.damage(new com.megacrit.cardcrawl.cards.DamageInfo(playerCharacter,1,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.HP_LOSS));
            if(manager.count()!=0)throw new AssertionError("Failed BGM probability enabled hurt rule");
            bgm.triggerProbability=1; playerCharacter.gainGold(1);
            if(manager.count()!=1 || !manager.isRuleActive(bgm.id))throw new AssertionError("Actual BGM did not activate prerequisite");
            playerCharacter.damage(new com.megacrit.cardcrawl.cards.DamageInfo(playerCharacter,1,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.HP_LOSS));
            if(manager.count()!=2 || !manager.pausedRuleIds().contains(bgm.id))throw new AssertionError("Actual hurt rule did not trigger and pause prerequisite BGM");
            int before=tracks.size();
            playerCharacter.damage(new com.megacrit.cardcrawl.cards.DamageInfo(playerCharacter,1,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.HP_LOSS));
            if(tracks.size()!=before+1)throw new AssertionError("Paused BGM no longer permits actual hurt triggers");
            playerCharacter.loseGold(1);
            if(manager.isRuleActive(bgm.id) || !manager.isRuleActive(hurt.id))throw new AssertionError("Actual BGM stop did not close window or cut existing hurt audio");
            before=tracks.size();
            playerCharacter.damage(new com.megacrit.cardcrawl.cards.DamageInfo(playerCharacter,1,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.HP_LOSS));
            if(tracks.size()!=before)throw new AssertionError("Actual injury triggered after prerequisite BGM stopped");
            manager.start(bgm,"preview"); before=tracks.size();
            playerCharacter.damage(new com.megacrit.cardcrawl.cards.DamageInfo(playerCharacter,1,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.HP_LOSS));
            if(tracks.size()!=before || manager.isRuleActive(bgm.id))throw new AssertionError("BGM preview enabled actual hurt audio");
            System.out.println("PASS: actual hurt only triggers within automatic BGM window, paused BGM stays valid, failed probability/preview do not enable it, BGM stop closes window");
        } catch(Exception ex) { throw new RuntimeException(ex); }
        finally {
            manager.stopAll(); playerCharacter.currentHealth=previousHealth; playerCharacter.maxHealth=previousMaximum;
            playerCharacter.gold=previousGold; playerCharacter.currentBlock=previousBlock;
            setModField("config",previousConfig); setModField("engine",previousEngine); setModField("events",previousEvents);
        }
    }
    private void actualStopProbabilityChecks() {
        BattleAudioMod.instance.receiveOnBattleStart(com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom());
        Object previousConfig = modField("config"), previousEngine = modField("engine"), previousEvents = modField("events");
        com.megacrit.cardcrawl.characters.AbstractPlayer playerCharacter = com.megacrit.cardcrawl.dungeons.AbstractDungeon.player;
        int previousHealth = playerCharacter.currentHealth, previousMaximum = playerCharacter.maxHealth, previousGold = playerCharacter.gold;
        battleaudio.storage.AudioLibrary library = (battleaudio.storage.AudioLibrary)modField("library");
        java.util.List<com.badlogic.gdx.audio.Music> pauseTracks = new java.util.ArrayList<>();
        battleaudio.audio.PlaybackManager manager = new battleaudio.audio.PlaybackManager(cache -> {
            com.badlogic.gdx.audio.Music track = Gdx.audio.newMusic(new com.badlogic.gdx.files.FileHandle(library.managed(cache).toFile()));
            pauseTracks.add(track); return track;
        }, error -> { throw new AssertionError(error); });
        try {
            battleaudio.model.Config config = new battleaudio.model.Config();
            battleaudio.model.Config.Rule rain = library.importAudio(Paths.get(System.getProperty("battleaudio.sample")));
            rain.audio().volume = 0; rain.playbackMode = battleaudio.model.Config.PlaybackMode.INFINITE;
            rain.triggers.add(battleaudio.model.Config.Trigger.LOW_HEALTH); rain.low.percent = true; rain.low.value = 30;
            rain.stopConditions.triggers.add(battleaudio.model.Config.Trigger.HIGH_HEALTH);
            rain.stopConditions.high.percent = true; rain.stopConditions.high.value = 40;
            config.rules.add(rain);
            battleaudio.model.Config.Rule other = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(rain),battleaudio.model.Config.Rule.class);
            other.id = "stop-smoke-other"; other.triggers = java.util.EnumSet.of(battleaudio.model.Config.Trigger.BATTLE_START);
            other.stopConditions = new battleaudio.model.Config.StopConditions(); config.rules.add(other);
            other.pauseWhenRuleIds.add(rain.id);
            battleaudio.model.Config.Rule suppressed = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(other),battleaudio.model.Config.Rule.class);
            suppressed.id = "stop-smoke-zero"; suppressed.triggerProbability = 0; config.rules.add(suppressed);
            playerCharacter.maxHealth = 100; playerCharacter.currentHealth = 80; playerCharacter.currentBlock = 0;
            battleaudio.core.RuleEngine engine = new battleaudio.core.RuleEngine(config,manager);
            engine.beginRun(false,80,100); engine.battleStart("stop-probability-game",false,false,80,100);
            setModField("config",config); setModField("engine",engine);
            battleaudio.core.GameEvents events = new battleaudio.core.GameEvents(engine); setModField("events",events); events.baseline();
            if(manager.count()!=1)throw new AssertionError("Real playback ignored zero probability");
            playerCharacter.damage(new com.megacrit.cardcrawl.cards.DamageInfo(playerCharacter,50,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.HP_LOSS));
            if(manager.count()!=2)throw new AssertionError("Actual player damage did not start low health audio");
            if(!manager.pausedRuleIds().contains(other.id) || pauseTracks.get(0).isPlaying())
                throw new AssertionError("Actual low health rule did not pause linked automatic audio");
            float pausedProgress = pauseTracks.get(0).getPosition();
            playerCharacter.heal(9);
            if(manager.count()!=2)throw new AssertionError("Actual heal below stop threshold ended audio");
            playerCharacter.heal(1);
            if(manager.count()!=1)throw new AssertionError("Actual heal at exact threshold did not stop rule only");
            if(!pauseTracks.get(0).isPlaying() || manager.pausedRuleIds().contains(other.id)
                    || pauseTracks.get(0).getPosition() < pausedProgress - .08f)
                throw new AssertionError("Actual rule stop did not resume same OpenAL handle and position");
            rain.stopConditions.triggers.clear(); rain.stopConditions.triggers.add(battleaudio.model.Config.Trigger.GAIN_GOLD);
            rain.stopConditions.filter(battleaudio.model.Config.Trigger.GAIN_GOLD).minAmount = 5;
            playerCharacter.damage(new com.megacrit.cardcrawl.cards.DamageInfo(playerCharacter,10,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.HP_LOSS));
            if(manager.count()!=2)throw new AssertionError("Real low health rule did not restart");
            rain.triggerProbability = 0; playerCharacter.gainGold(4);
            if(manager.count()!=2)throw new AssertionError("Actual gold below stop amount stopped rule");
            playerCharacter.gainGold(5);
            if(manager.count()!=1)throw new AssertionError("Actual gold stop missed threshold or depended on playback probability");
            if(!pauseTracks.get(0).isPlaying() || !manager.pausedRuleIds().isEmpty())
                throw new AssertionError("Actual gold stop left linked audio paused");
            System.out.println("PASS: actual patched damage/heal/gold stop conditions, exact health equality, scoped OpenAL stop and zero probability");
            System.out.println("PASS: actual rule start pauses linked OpenAL audio and rule stop resumes the same handle and progress");
        } catch (Exception ex) { throw new RuntimeException(ex); }
        finally {
            manager.stopAll(); playerCharacter.currentHealth = previousHealth; playerCharacter.maxHealth = previousMaximum; playerCharacter.gold = previousGold;
            setModField("config",previousConfig); setModField("engine",previousEngine); setModField("events",previousEvents);
        }
    }
    private void actualEventChecks() {
        actualStopProbabilityChecks();
        vanillaEventOptionChecks(com.megacrit.cardcrawl.dungeons.AbstractDungeon.player);
        battleaudio.model.Config config = new battleaudio.model.Config();
        battleaudio.storage.AudioLibrary library = (battleaudio.storage.AudioLibrary)modField("library");
        battleaudio.model.Config.Rule sample;
        try { sample = library.importAudio(Paths.get(System.getProperty("battleaudio.sample"))); }
        catch (Exception ex) { throw new RuntimeException(ex); }
        for (battleaudio.model.Config.Trigger trigger : battleaudio.model.Config.Trigger.values()) {
            battleaudio.model.Config.Rule rule = new battleaudio.model.Config.Rule();
            rule.retriggerMode = battleaudio.model.Config.RetriggerMode.OVERLAP; rule.id = trigger.name(); rule.audio().duration = rule.audio().end = 10; rule.audio().cachePath = "smoke.wav"; rule.triggers = java.util.EnumSet.of(trigger);
            rule.audio().cachePath = sample.audio().cachePath; rule.audio().duration = sample.audio().duration; rule.audio().end = Math.min(sample.audio().duration, 5); rule.audio().volume = 0;
            rule.parameter(trigger).value = 2;
            if (battleaudio.model.Events.requiresId(trigger)) rule.parameter(trigger).id = trigger == battleaudio.model.Config.Trigger.APPLY_ENEMY_POWER ? "Poison" : trigger == battleaudio.model.Config.Trigger.EXIT_STANCE ? "Calm" : "Strength";
            config.rules.add(rule);
        }
        for(battleaudio.model.Config.Trigger trigger:new battleaudio.model.Config.Trigger[]{
                battleaudio.model.Config.Trigger.POWER_DECREASE,battleaudio.model.Config.Trigger.POWER_REMOVED})
            config.rules.stream().filter(rule->rule.id.equals(trigger.name())).findFirst().get().parameter(trigger).id="Metallicize";
        battleaudio.model.Config.Rule filteredPowerDecrease=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("POWER_DECREASE")).findFirst().get()),
                battleaudio.model.Config.Rule.class);
        filteredPowerDecrease.id="FILTERED_POWER_DECREASE";
        filteredPowerDecrease.filter(battleaudio.model.Config.Trigger.POWER_DECREASE).minAmount=3;
        filteredPowerDecrease.filter(battleaudio.model.Config.Trigger.POWER_DECREASE).sourceType="PLAYER";
        config.rules.add(filteredPowerDecrease);
        battleaudio.model.Config.Rule missedPowerDecrease=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(filteredPowerDecrease),battleaudio.model.Config.Rule.class);
        missedPowerDecrease.id="MISSED_POWER_DECREASE";
        missedPowerDecrease.parameter(battleaudio.model.Config.Trigger.POWER_DECREASE).id="NeverMatches";
        config.rules.add(missedPowerDecrease);
        battleaudio.model.Config.Rule missedPowerSource=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(filteredPowerDecrease),battleaudio.model.Config.Rule.class);
        missedPowerSource.id="MISSED_POWER_SOURCE";
        missedPowerSource.filter(battleaudio.model.Config.Trigger.POWER_DECREASE).sourceType="ENEMY";
        config.rules.add(missedPowerSource);
        battleaudio.model.Config.Rule filteredPowerRemoved=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("POWER_REMOVED")).findFirst().get()),
                battleaudio.model.Config.Rule.class);
        filteredPowerRemoved.id="FILTERED_POWER_REMOVED";
        filteredPowerRemoved.filter(battleaudio.model.Config.Trigger.POWER_REMOVED).minAmount=3;
        filteredPowerRemoved.filter(battleaudio.model.Config.Trigger.POWER_REMOVED).sourceType="PLAYER";
        config.rules.add(filteredPowerRemoved);
        battleaudio.model.Config.Rule missedPowerRemovedSource=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(filteredPowerRemoved),battleaudio.model.Config.Rule.class);
        missedPowerRemovedSource.id="MISSED_POWER_REMOVED_SOURCE";
        missedPowerRemovedSource.filter(battleaudio.model.Config.Trigger.POWER_REMOVED).sourceType="ENEMY";
        config.rules.add(missedPowerRemovedSource);
        for (battleaudio.model.Config.Trigger trigger : new battleaudio.model.Config.Trigger[]{
                battleaudio.model.Config.Trigger.GAIN_ENERGY,battleaudio.model.Config.Trigger.GAIN_BLOCK,
                battleaudio.model.Config.Trigger.BLOCK_LOSS,battleaudio.model.Config.Trigger.PLAY_ATTACK,
                battleaudio.model.Config.Trigger.APPLY_POISON}) {
            battleaudio.model.Config.Rule filtered=new com.google.gson.Gson().fromJson(
                    new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals(trigger.name())).findFirst().get()),
                    battleaudio.model.Config.Rule.class);
            filtered.id="FILTERED_"+trigger.name();
            battleaudio.model.Config.EventFilter filter=filtered.filter(trigger);
            if(trigger==battleaudio.model.Config.Trigger.PLAY_ATTACK) filter.cardId=new com.megacrit.cardcrawl.cards.red.Strike_Red().cardID;
            else if(trigger==battleaudio.model.Config.Trigger.APPLY_POISON) {
                filter.minAmount=2; filter.cadence=battleaudio.model.Config.Cadence.FIRST_OBJECT;
            }
            else filter.minAmount=2;
            config.rules.add(filtered);
        }
        for(String type:new String[]{"PLAYER","ENEMY"}) {
            battleaudio.model.Config.Rule filtered=new com.google.gson.Gson().fromJson(
                    new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("HP_LOSS")).findFirst().get()),
                    battleaudio.model.Config.Rule.class);
            filtered.id="FILTERED_HP_LOSS_"+type; filtered.filter(battleaudio.model.Config.Trigger.HP_LOSS).sourceType=type;
            config.rules.add(filtered);
        }
        for(String key:new String[]{"MATCH","MISS"}) {
            battleaudio.model.Config.Rule filtered=new com.google.gson.Gson().fromJson(
                    new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("KILL_ENEMY")).findFirst().get()),
                    battleaudio.model.Config.Rule.class);
            filtered.id="FILTERED_KILL_"+key;
            filtered.filter(battleaudio.model.Config.Trigger.KILL_ENEMY).targetId="NeverMatches";
            if("MATCH".equals(key)) {
                com.megacrit.cardcrawl.monsters.AbstractMonster firstEnemy=
                        com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters().monsters.get(0);
                filtered.filter(battleaudio.model.Config.Trigger.KILL_ENEMY).targetId=firstEnemy.id;
                filtered.filter(battleaudio.model.Config.Trigger.KILL_ENEMY).enemyType=firstEnemy.type.name();
                filtered.filter(battleaudio.model.Config.Trigger.KILL_ENEMY).lastEnemy=false;
            }
            config.rules.add(filtered);
        }
        battleaudio.model.Config.Rule finalKill=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("KILL_ENEMY")).findFirst().get()),
                battleaudio.model.Config.Rule.class);
        finalKill.id="FILTERED_KILL_LAST";
        finalKill.filter(battleaudio.model.Config.Trigger.KILL_ENEMY).lastEnemy=true;
        config.rules.add(finalKill);
        for(String key:new String[]{"MATCH","MISS"}) {
            battleaudio.model.Config.Rule lowEnemy=new com.google.gson.Gson().fromJson(
                    new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("ENEMY_LOW_HEALTH")).findFirst().get()),
                    battleaudio.model.Config.Rule.class);
            lowEnemy.id="FILTERED_LOW_ENEMY_"+key;
            com.megacrit.cardcrawl.monsters.AbstractMonster target=
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters().monsters.get(0);
            lowEnemy.filter(battleaudio.model.Config.Trigger.ENEMY_LOW_HEALTH).targetId=
                    "MATCH".equals(key)?target.id:"NeverMatches";
            lowEnemy.filter(battleaudio.model.Config.Trigger.ENEMY_LOW_HEALTH).enemyType=target.type.name();
            config.rules.add(lowEnemy);
        }
        battleaudio.model.Config.Rule stanceRule=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("ENTER_WRATH")).findFirst().get()),
                battleaudio.model.Config.Rule.class);
        stanceRule.id="FILTERED_CALM_TO_WRATH";
        stanceRule.filter(battleaudio.model.Config.Trigger.ENTER_WRATH).previousState="Calm";
        stanceRule.filter(battleaudio.model.Config.Trigger.ENTER_WRATH).nextState="Wrath";
        config.rules.add(stanceRule);
        battleaudio.model.Config.Rule neutralStanceRule=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("STANCE_CHANGE")).findFirst().get()),
                battleaudio.model.Config.Rule.class);
        neutralStanceRule.id="FILTERED_DIVINITY_TO_NEUTRAL";
        neutralStanceRule.filter(battleaudio.model.Config.Trigger.STANCE_CHANGE).previousState="Divinity";
        neutralStanceRule.filter(battleaudio.model.Config.Trigger.STANCE_CHANGE).nextState="Neutral";
        config.rules.add(neutralStanceRule);
        battleaudio.model.Config.Rule generatedHand=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("CREATE_CARD")).findFirst().get()),
                battleaudio.model.Config.Rule.class);
        generatedHand.id="FILTERED_CREATE_HAND";
        generatedHand.filter(battleaudio.model.Config.Trigger.CREATE_CARD).targetPile="HAND";
        generatedHand.filter(battleaudio.model.Config.Trigger.CREATE_CARD).cardId="Shiv";
        config.rules.add(generatedHand);
        for(battleaudio.model.Config.Trigger trigger:new battleaudio.model.Config.Trigger[]{
                battleaudio.model.Config.Trigger.CALM_GAIN_BLOCK,battleaudio.model.Config.Trigger.WRATH_DAMAGE}) {
            battleaudio.model.Config.Rule stanceEffect=new com.google.gson.Gson().fromJson(
                    new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals(trigger.name())).findFirst().get()),
                    battleaudio.model.Config.Rule.class);
            stanceEffect.id="FILTERED_"+trigger.name();
            stanceEffect.filter(trigger).minAmount=2;
            config.rules.add(stanceEffect);
        }
        battleaudio.model.Config.Rule regenMinimum=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("REGEN_HEAL")).findFirst().get()),
                battleaudio.model.Config.Rule.class);
        regenMinimum.id="FILTERED_REGEN_HEAL";
        regenMinimum.filter(battleaudio.model.Config.Trigger.REGEN_HEAL).minAmount=2;
        config.rules.add(regenMinimum);
        battleaudio.model.Config.Rule intangibleMinimum=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("INTANGIBLE_MITIGATE")).findFirst().get()),
                battleaudio.model.Config.Rule.class);
        intangibleMinimum.id="FILTERED_INTANGIBLE_MITIGATE";
        intangibleMinimum.filter(battleaudio.model.Config.Trigger.INTANGIBLE_MITIGATE).minAmount=3;
        config.rules.add(intangibleMinimum);
        battleaudio.model.Config.Rule boughtCard=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("SHOP_BUY_CARD")).findFirst().get()),
                battleaudio.model.Config.Rule.class);
        boughtCard.id="FILTERED_SHOP_BUY_CARD";
        boughtCard.filter(battleaudio.model.Config.Trigger.SHOP_BUY_CARD).cardId=new com.megacrit.cardcrawl.cards.red.Anger().cardID;
        config.rules.add(boughtCard);
        battleaudio.model.Config.Rule purgedCard=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("SHOP_REMOVE_CARD")).findFirst().get()),
                battleaudio.model.Config.Rule.class);
        purgedCard.id="FILTERED_SHOP_REMOVE_CARD";
        purgedCard.filter(battleaudio.model.Config.Trigger.SHOP_REMOVE_CARD).cardId=new com.megacrit.cardcrawl.cards.red.Anger().cardID;
        config.rules.add(purgedCard);
        battleaudio.model.Config.Rule gainedCurse=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("GAIN_CURSE")).findFirst().get()),
                battleaudio.model.Config.Rule.class);
        gainedCurse.id="FILTERED_GAIN_CURSE";
        gainedCurse.filter(battleaudio.model.Config.Trigger.GAIN_CURSE).cardId=new com.megacrit.cardcrawl.cards.curses.Injury().cardID;
        config.rules.add(gainedCurse);
        for(battleaudio.model.Config.Trigger trigger:new battleaudio.model.Config.Trigger[]{
                battleaudio.model.Config.Trigger.SHOP_BUY_RELIC,battleaudio.model.Config.Trigger.SHOP_BUY_POTION}) {
            battleaudio.model.Config.Rule itemRule=new com.google.gson.Gson().fromJson(
                    new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals(trigger.name())).findFirst().get()),
                    battleaudio.model.Config.Rule.class);
            itemRule.id="FILTERED_"+trigger.name();
            itemRule.filter(trigger).sourceId=trigger==battleaudio.model.Config.Trigger.SHOP_BUY_RELIC?
                    new com.megacrit.cardcrawl.relics.Anchor().relicId:new com.megacrit.cardcrawl.potions.FirePotion().ID;
            config.rules.add(itemRule);
        }
        battleaudio.model.Config.Rule relicMatch=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("GAIN_RELIC")).findFirst().get()),
                battleaudio.model.Config.Rule.class);
        relicMatch.id="FILTERED_RELIC_MATCH";
        relicMatch.filter(battleaudio.model.Config.Trigger.GAIN_RELIC).sourceId="Circlet";
        config.rules.add(relicMatch);
        battleaudio.model.Config.Rule relicMiss=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(relicMatch),battleaudio.model.Config.Rule.class);
        relicMiss.id="FILTERED_RELIC_MISS";
        relicMiss.filter(battleaudio.model.Config.Trigger.GAIN_RELIC).sourceId="NeverMatches";
        config.rules.add(relicMiss);
        battleaudio.model.Config.Rule lightningMatch=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("ORB_PASSIVE_DAMAGE")).findFirst().get()),
                battleaudio.model.Config.Rule.class);
        lightningMatch.id="FILTERED_LIGHTNING_DAMAGE";
        lightningMatch.filter(battleaudio.model.Config.Trigger.ORB_PASSIVE_DAMAGE).minAmount=10;
        config.rules.add(lightningMatch);
        battleaudio.model.Config.Rule lightningMiss=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(lightningMatch),battleaudio.model.Config.Rule.class);
        lightningMiss.id="FILTERED_LIGHTNING_MISS";
        lightningMiss.filter(battleaudio.model.Config.Trigger.ORB_PASSIVE_DAMAGE).targetId="NeverMatches";
        config.rules.add(lightningMiss);
        battleaudio.model.Config.Rule returnedCard=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("RETURN_DISCARD_CARD")).findFirst().get()),
                battleaudio.model.Config.Rule.class);
        returnedCard.id="FILTERED_RETURN_DISCARD_CARD";
        returnedCard.filter(battleaudio.model.Config.Trigger.RETURN_DISCARD_CARD).cardId="Anger";
        config.rules.add(returnedCard);
        battleaudio.model.Config.Rule shivDamage=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("SHIV_DAMAGE")).findFirst().get()),
                battleaudio.model.Config.Rule.class);
        shivDamage.id="FILTERED_SHIV_DAMAGE";
        shivDamage.filter(battleaudio.model.Config.Trigger.SHIV_DAMAGE).minAmount=6;
        config.rules.add(shivDamage);
        for(battleaudio.model.Config.Trigger trigger:new battleaudio.model.Config.Trigger[]{
                battleaudio.model.Config.Trigger.DARK_EVOKE_DAMAGE,battleaudio.model.Config.Trigger.PLASMA_EVOKE_ENERGY,
                battleaudio.model.Config.Trigger.DIVINITY_DAMAGE}) {
            battleaudio.model.Config.Rule filtered=new com.google.gson.Gson().fromJson(
                    new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals(trigger.name())).findFirst().get()),
                    battleaudio.model.Config.Rule.class);
            filtered.id="FILTERED_"+trigger.name(); filtered.filter(trigger).minAmount=3;
            config.rules.add(filtered);
            if(trigger!=battleaudio.model.Config.Trigger.PLASMA_EVOKE_ENERGY) {
                battleaudio.model.Config.Rule miss=new com.google.gson.Gson().fromJson(
                        new com.google.gson.Gson().toJson(filtered),battleaudio.model.Config.Rule.class);
                miss.id="MISS_"+trigger.name(); miss.filter(trigger).targetId="NeverMatches"; config.rules.add(miss);
            }
        }
        battleaudio.model.Config.Rule scryDiscard=new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(config.rules.stream().filter(rule->rule.id.equals("SCRY_DISCARD_CARD")).findFirst().get()),
                battleaudio.model.Config.Rule.class);
        scryDiscard.id="FILTERED_SCRY_DISCARD";
        scryDiscard.filter(battleaudio.model.Config.Trigger.SCRY_DISCARD_CARD).cardId=new com.megacrit.cardcrawl.cards.red.Strike_Red().cardID;
        config.rules.add(scryDiscard);
        config.rules.stream().filter(rule->rule.id.equals("SCRY_CARDS")).findFirst().get()
                .parameter(battleaudio.model.Config.Trigger.SCRY_CARDS).value=2;
        battleaudio.core.RuleEngine engine = new battleaudio.core.RuleEngine(config, this);
        com.megacrit.cardcrawl.characters.AbstractPlayer p = com.megacrit.cardcrawl.dungeons.AbstractDungeon.player;
        p.powers.clear(); p.relics.clear();
        engine.beginRun(false, p.currentHealth, p.maxHealth); engine.battleStart("events", false, false, p.currentHealth, p.maxHealth);
        assertEvent(battleaudio.model.Config.Trigger.BATTLE_START, 1);
        assertEvent(battleaudio.model.Config.Trigger.FIRST_BATTLE, 1);
        assertEvent(battleaudio.model.Config.Trigger.NORMAL_BATTLE_START, 1);
        eventAudio = new battleaudio.audio.PlaybackManager(cache -> {
            com.badlogic.gdx.audio.Music track = Gdx.audio.newMusic(new com.badlogic.gdx.files.FileHandle(library.managed(cache).toFile()));
            eventTracks.add(track); return track;
        }, error -> { throw new AssertionError(error); });
        setModField("config", config); setModField("engine", engine);
        battleaudio.core.GameEvents receiver = new battleaudio.core.GameEvents(engine); setModField("events", receiver); receiver.baseline();
        com.megacrit.cardcrawl.cards.AbstractCard recovered=new com.megacrit.cardcrawl.cards.red.Anger();
        p.discardPile.addToTop(recovered);
        p.discardPile.moveToHand(recovered,p.discardPile);
        assertEvent(battleaudio.model.Config.Trigger.RETURN_DISCARD_CARD,1);
        if(actualEvents.getOrDefault("FILTERED_RETURN_DISCARD_CARD",0)!=1)
            throw new AssertionError("Completed discard-to-hand card ID filter missed");
        com.megacrit.cardcrawl.cards.AbstractCard drawnDirectly=new com.megacrit.cardcrawl.cards.red.Anger();
        p.drawPile.addToTop(drawnDirectly);
        p.drawPile.moveToHand(drawnDirectly,p.drawPile);
        BattleAudioMod.instance.observeEvents();
        assertEvent(battleaudio.model.Config.Trigger.RETURN_DISCARD_CARD,1);
        p.hand.removeCard(recovered); p.hand.removeCard(drawnDirectly);
        com.megacrit.cardcrawl.cards.AbstractCard curse=new com.megacrit.cardcrawl.cards.curses.Injury();
        assertEvent(battleaudio.model.Config.Trigger.GAIN_CURSE,0);
        new com.megacrit.cardcrawl.cards.Soul().obtain(curse);
        assertEvent(battleaudio.model.Config.Trigger.GAIN_CURSE,1);
        if(actualEvents.getOrDefault("FILTERED_GAIN_CURSE",0)!=1)
            throw new AssertionError("Actual curse obtain missed card ID filter");
        com.megacrit.cardcrawl.cards.AbstractCard ordinary=new com.megacrit.cardcrawl.cards.red.Anger();
        new com.megacrit.cardcrawl.cards.Soul().obtain(ordinary);
        assertEvent(battleaudio.model.Config.Trigger.GAIN_CURSE,1);
        p.masterDeck.removeCard(curse); p.masterDeck.removeCard(ordinary);
        com.megacrit.cardcrawl.relics.AbstractRelic relic=new com.megacrit.cardcrawl.relics.Circlet();
        relic.obtain(); assertEvent(battleaudio.model.Config.Trigger.GAIN_RELIC,1);
        if(actualEvents.getOrDefault("FILTERED_RELIC_MATCH",0)!=1) throw new AssertionError("Actual Circlet obtain missed ID filter");
        relic.obtain(); assertEvent(battleaudio.model.Config.Trigger.GAIN_RELIC,2);
        new com.megacrit.cardcrawl.relics.Circlet().instantObtain();
        assertEvent(battleaudio.model.Config.Trigger.GAIN_RELIC,3);
        if(actualEvents.getOrDefault("FILTERED_RELIC_MATCH",0)!=3 || actualEvents.getOrDefault("FILTERED_RELIC_MISS",0)!=0)
            throw new AssertionError("Circlet repeat or relic ID filtering failed");
        new com.megacrit.cardcrawl.relics.Anchor().instantObtain(p,p.relics.size(),false);
        assertEvent(battleaudio.model.Config.Trigger.GAIN_RELIC,4);
        new com.megacrit.cardcrawl.relics.BagOfPreparation().reorganizeObtain(p,p.relics.size(),false,0);
        assertEvent(battleaudio.model.Config.Trigger.GAIN_RELIC,5);
        p.relics.clear();
        assertEvent(battleaudio.model.Config.Trigger.GAIN_RELIC,5);
        if(actualEvents.getOrDefault("FILTERED_RELIC_MATCH",0)!=3 || actualEvents.getOrDefault("FILTERED_RELIC_MISS",0)!=0)
            throw new AssertionError("Unrelated relic obtain matched Circlet or invalid ID");
        p.applyStartOfTurnRelics();
        assertEvent(battleaudio.model.Config.Trigger.TURN_START, 1);
        com.megacrit.cardcrawl.ui.panels.EnergyPanel.setEnergy(0); com.megacrit.cardcrawl.ui.panels.EnergyPanel.addEnergy(2);
        assertEvent(battleaudio.model.Config.Trigger.GAIN_ENERGY, 1); assertEvent(battleaudio.model.Config.Trigger.ENERGY_THRESHOLD, 1);
        if(actualEvents.getOrDefault("FILTERED_GAIN_ENERGY",0)!=1) throw new AssertionError("Actual single energy gain filter missed +2");
        battleaudio.model.Config.Trigger energyGain=battleaudio.model.Config.Trigger.ENERGY_GAIN_THRESHOLD;
        assertEvent(energyGain,1);
        com.megacrit.cardcrawl.ui.panels.EnergyPanel.useEnergy(2);
        com.megacrit.cardcrawl.ui.panels.EnergyPanel.addEnergy(1);
        com.megacrit.cardcrawl.ui.panels.EnergyPanel.addEnergy(1);
        if(actualEvents.getOrDefault("FILTERED_GAIN_ENERGY",0)!=1) throw new AssertionError("Two +1 gains passed single +2 filter");
        assertEvent(energyGain,1);
        com.megacrit.cardcrawl.ui.panels.EnergyPanel.setEnergy(2);
        BattleAudioMod.instance.observeEvents();
        assertEvent(energyGain,1);
        com.megacrit.cardcrawl.ui.panels.EnergyPanel.setEnergy(5);
        assertEvent(energyGain,2);
        p.gold=0; p.gainGold(3); p.gainGold(0);
        assertEvent(battleaudio.model.Config.Trigger.GAIN_GOLD,1);
        p.loseGold(10); p.loseGold(1);
        assertEvent(battleaudio.model.Config.Trigger.SPEND_GOLD,1);
        com.megacrit.cardcrawl.rooms.AbstractRoom potionRoom=com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom();
        com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase originalPhase=potionRoom.phase;
        boolean turnEnded=com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.turnHasEnded;
        try {
            potionRoom.phase=com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.turnHasEnded=false;
            com.megacrit.cardcrawl.monsters.AbstractMonster potionEnemy=com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters().monsters.get(0);
            potionEnemy.currentHealth=Math.max(5,potionEnemy.currentHealth);
            potionEnemy.isDead=false; potionEnemy.isDying=false;
            com.megacrit.cardcrawl.potions.AbstractPotion potion=new com.megacrit.cardcrawl.potions.EnergyPotion();
            p.potions.set(0,potion);
            com.megacrit.cardcrawl.ui.panels.PotionPopUp popup=com.megacrit.cardcrawl.dungeons.AbstractDungeon.topPanel.potionUi;
            popup.open(0,potion);
            popup.close();
            assertEvent(battleaudio.model.Config.Trigger.USE_POTION,0);
            popup.open(0,potion);
            java.lang.reflect.Field top=com.megacrit.cardcrawl.ui.panels.PotionPopUp.class.getDeclaredField("hbTop");
            top.setAccessible(true);
            com.megacrit.cardcrawl.helpers.Hitbox hitbox=(com.megacrit.cardcrawl.helpers.Hitbox)top.get(popup);
            hitbox.clicked=true; hitbox.hovered=true;
            if(!potion.canUse()) throw new AssertionError("Energy Potion unavailable in combat fixture");
            java.lang.reflect.Method updateInput=com.megacrit.cardcrawl.ui.panels.PotionPopUp.class.getDeclaredMethod("updateInput");
            updateInput.setAccessible(true); updateInput.invoke(popup);
            assertEvent(battleaudio.model.Config.Trigger.USE_POTION,1);
        } catch(Exception ex) { throw new RuntimeException(ex); }
        finally { potionRoom.phase=originalPhase; com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.turnHasEnded=turnEnded; }
        p.currentBlock = 0; BattleAudioMod.instance.observeEvents(); p.addBlock(3); p.addBlock(1);
        assertEvent(battleaudio.model.Config.Trigger.GAIN_BLOCK, 2); assertEvent(battleaudio.model.Config.Trigger.BLOCK_THRESHOLD, 1);
        if(actualEvents.getOrDefault("FILTERED_GAIN_BLOCK",0)!=1) throw new AssertionError("Actual +3/+1 block filter did not distinguish operations");
        p.loseBlock(1, false); assertEvent(battleaudio.model.Config.Trigger.BLOCK_LOSS, 1);
        if(actualEvents.getOrDefault("FILTERED_BLOCK_LOSS",0)!=0) throw new AssertionError("Actual -1 block passed -2 filter");
        battleaudio.patches.GamePatches.turnBlockReset++;
        try { p.loseBlock(1, false); } finally { battleaudio.patches.GamePatches.turnBlockReset--; }
        assertEvent(battleaudio.model.Config.Trigger.BLOCK_LOSS, 1);
        battleaudio.model.Config.Rule highRule = config.rules.stream().filter(rule -> rule.id.equals("HIGH_HEALTH")).findFirst().get();
        battleaudio.model.Config.Rule healRule = config.rules.stream().filter(rule -> rule.id.equals("HEAL_THRESHOLD")).findFirst().get();
        highRule.high.value = 70; healRule.heal.value = 50;
        p.currentHealth = 20; p.healthBarUpdatedEvent();
        assertEvent(battleaudio.model.Config.Trigger.HP_LOSS, 1);
        assertEvent(battleaudio.model.Config.Trigger.LOW_HEALTH, 1);
        p.heal(p.maxHealth, true);
        assertEvent(battleaudio.model.Config.Trigger.HIGH_HEALTH, 1);
        assertEvent(battleaudio.model.Config.Trigger.HEAL_THRESHOLD, 1);
        com.megacrit.cardcrawl.cards.AbstractCard upgradeCard = new com.megacrit.cardcrawl.cards.red.Strike_Red();
        p.masterDeck.addToTop(upgradeCard);
        try {
            java.lang.reflect.Method observeUpgrades = BattleAudioMod.class.getDeclaredMethod("observeUpgrades");
            observeUpgrades.setAccessible(true); observeUpgrades.invoke(BattleAudioMod.instance);
            upgradeCard.upgrade(); observeUpgrades.invoke(BattleAudioMod.instance);
        } catch (Exception ex) { throw new RuntimeException(ex); }
        assertEvent(battleaudio.model.Config.Trigger.CARD_UPGRADE, 1);
        p.hand.clear(); p.drawPile.addToTop(new com.megacrit.cardcrawl.cards.red.Strike_Red()); p.draw(1);
        assertEvent(battleaudio.model.Config.Trigger.DRAW_CARD, 1);
        com.megacrit.cardcrawl.monsters.AbstractMonster m = com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters().monsters.get(0);
        p.useCard(new com.megacrit.cardcrawl.cards.red.Strike_Red(), m, 1);
        p.useCard(new com.megacrit.cardcrawl.cards.red.Defend_Red(), null, 1);
        p.useCard(new com.megacrit.cardcrawl.cards.red.Inflame(), null, 1);
        p.useCard(new com.megacrit.cardcrawl.cards.tempCards.Shiv(), m, 0);
        assertEvent(battleaudio.model.Config.Trigger.PLAY_ATTACK, 2); assertEvent(battleaudio.model.Config.Trigger.PLAY_SKILL, 1);
        assertEvent(battleaudio.model.Config.Trigger.PLAY_POWER, 1); assertEvent(battleaudio.model.Config.Trigger.PLAY_SHIV, 1); assertEvent(battleaudio.model.Config.Trigger.CARDS_PLAYED_TURN, 1);
        assertEvent(battleaudio.model.Config.Trigger.SHIV_PLAYED_TURN,0);
        p.useCard(new com.megacrit.cardcrawl.cards.tempCards.Shiv(),m,0);
        assertEvent(battleaudio.model.Config.Trigger.SHIV_PLAYED_TURN,1);
        p.useCard(new com.megacrit.cardcrawl.cards.tempCards.Shiv(),m,0);
        assertEvent(battleaudio.model.Config.Trigger.SHIV_PLAYED_TURN,1);
        if(actualEvents.getOrDefault("FILTERED_PLAY_ATTACK",0)!=1) throw new AssertionError("Strike filter matched another attack or missed Strike");
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
        com.megacrit.cardcrawl.cards.AbstractCard discarded = new com.megacrit.cardcrawl.cards.red.Defend_Red(); p.hand.addToTop(discarded);
        runAction(new com.megacrit.cardcrawl.actions.common.DiscardAction(p, p, 1, true));
        assertEvent(battleaudio.model.Config.Trigger.DISCARD_CARD, 1);
        p.hand.addToTop(new com.megacrit.cardcrawl.cards.red.Defend_Red());
        runAction(new com.megacrit.cardcrawl.actions.common.DiscardAction(p, p, 1, true));
        assertEvent(battleaudio.model.Config.Trigger.DISCARDED_TURN, 1);
        p.hand.moveToExhaustPile(new com.megacrit.cardcrawl.cards.red.Strike_Red()); p.hand.moveToExhaustPile(new com.megacrit.cardcrawl.cards.red.Strike_Red());
        assertEvent(battleaudio.model.Config.Trigger.EXHAUST_CARD, 2); assertEvent(battleaudio.model.Config.Trigger.EXHAUSTED_TURN, 1);
        runAction(new com.megacrit.cardcrawl.actions.common.MakeTempCardInHandAction(new com.megacrit.cardcrawl.cards.tempCards.Shiv(), 2));
        assertEvent(battleaudio.model.Config.Trigger.CREATE_SHIV, 2); assertEvent(battleaudio.model.Config.Trigger.CREATE_CARD, 2);
        if(actualEvents.getOrDefault("FILTERED_CREATE_HAND",0)!=2)
            throw new AssertionError("Actual generated Shiv hand destination filter missed or duplicated cards");
        p.powers.clear(); BattleAudioMod.instance.observeEvents();
        runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(p, p, new com.megacrit.cardcrawl.powers.StrengthPower(p, 2), 2));
        assertEvent(battleaudio.model.Config.Trigger.GAIN_SPECIFIED_POWER, 1); assertEvent(battleaudio.model.Config.Trigger.POWER_THRESHOLD, 1); assertEvent(battleaudio.model.Config.Trigger.STRENGTH_THRESHOLD, 1);
        assertEvent(battleaudio.model.Config.Trigger.STRENGTH_INCREASE,1);
        assertEvent(battleaudio.model.Config.Trigger.POSITIVE_POWER,1);
        int strengthDecreaseBefore=eventCount(battleaudio.model.Config.Trigger.STRENGTH_DECREASE);
        int strengthNegativeBefore=eventCount(battleaudio.model.Config.Trigger.STRENGTH_NEGATIVE);
        runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(p,p,
                new com.megacrit.cardcrawl.powers.StrengthPower(p,-4),-4));
        assertEvent(battleaudio.model.Config.Trigger.STRENGTH_DECREASE,strengthDecreaseBefore+1);
        assertEvent(battleaudio.model.Config.Trigger.STRENGTH_NEGATIVE,strengthNegativeBefore+1);
        runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(p,p,
                new com.megacrit.cardcrawl.powers.DexterityPower(p,2),2));
        assertEvent(battleaudio.model.Config.Trigger.DEXTERITY_INCREASE,1);
        runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(p,p,
                new com.megacrit.cardcrawl.powers.DexterityPower(p,-1),-1));
        assertEvent(battleaudio.model.Config.Trigger.DEXTERITY_DECREASE,1);
        assertEvent(battleaudio.model.Config.Trigger.POSITIVE_POWER, 2);
        runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(p,p,
                new com.megacrit.cardcrawl.powers.MetallicizePower(p,5),5));
        int powerDecreaseBefore=eventCount(battleaudio.model.Config.Trigger.POWER_DECREASE);
        int powerRemovedBefore=eventCount(battleaudio.model.Config.Trigger.POWER_REMOVED);
        runAction(new com.megacrit.cardcrawl.actions.common.ReducePowerAction(p,p,"Metallicize",2));
        assertEvent(battleaudio.model.Config.Trigger.POWER_DECREASE,powerDecreaseBefore+1);
        assertEvent(battleaudio.model.Config.Trigger.POWER_REMOVED,powerRemovedBefore);
        if(actualEvents.getOrDefault("FILTERED_POWER_DECREASE",0)!=0
                || actualEvents.getOrDefault("MISSED_POWER_DECREASE",0)!=0
                || actualEvents.getOrDefault("MISSED_POWER_SOURCE",0)!=0)
            throw new AssertionError("Power decrease amount or ID filter accepted a two-layer/mismatched change");
        runAction(new com.megacrit.cardcrawl.actions.common.RemoveSpecificPowerAction(p,p,"Metallicize"));
        assertEvent(battleaudio.model.Config.Trigger.POWER_DECREASE,powerDecreaseBefore+2);
        assertEvent(battleaudio.model.Config.Trigger.POWER_REMOVED,powerRemovedBefore+1);
        if(actualEvents.getOrDefault("FILTERED_POWER_DECREASE",0)!=1
                || actualEvents.getOrDefault("FILTERED_POWER_REMOVED",0)!=1
                || actualEvents.getOrDefault("MISSED_POWER_DECREASE",0)!=0
                || actualEvents.getOrDefault("MISSED_POWER_SOURCE",0)!=0
                || actualEvents.getOrDefault("MISSED_POWER_REMOVED_SOURCE",0)!=0)
            throw new AssertionError("Actual power removal amount, source or ID filters failed");
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
        runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(p,p,
                new com.megacrit.cardcrawl.powers.MetallicizePower(p,2),2));
        com.megacrit.cardcrawl.actions.common.ReducePowerAction reduceToRemove=
                new com.megacrit.cardcrawl.actions.common.ReducePowerAction(p,p,"Metallicize",5);
        runAction(reduceToRemove); drainActions(); reduceToRemove.update();
        assertEvent(battleaudio.model.Config.Trigger.POWER_DECREASE,powerDecreaseBefore+3);
        assertEvent(battleaudio.model.Config.Trigger.POWER_REMOVED,powerRemovedBefore+2);
        if(actualEvents.getOrDefault("FILTERED_POWER_DECREASE",0)!=1
                || actualEvents.getOrDefault("FILTERED_POWER_REMOVED",0)!=1
                || actualEvents.getOrDefault("MISSED_POWER_REMOVED_SOURCE",0)!=0)
            throw new AssertionError("Nested remove-on-excess path did not distinguish actual removed stack count");
        runAction(new com.megacrit.cardcrawl.actions.common.ReducePowerAction(p,p,"Metallicize",1));
        for(int i=0;i<20;i++) BattleAudioMod.instance.observeEvents();
        assertEvent(battleaudio.model.Config.Trigger.POWER_DECREASE,powerDecreaseBefore+3);
        assertEvent(battleaudio.model.Config.Trigger.POWER_REMOVED,powerRemovedBefore+2);
        System.out.println("PASS: actual ReducePowerAction decrease, direct and nested RemoveSpecificPowerAction removal; no-op and repeated observations do not re-fire");
        runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(p, m, new com.megacrit.cardcrawl.powers.WeakPower(p, 1, true), 1));
        assertEvent(battleaudio.model.Config.Trigger.RECEIVE_DEBUFF, 1);
        runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(m, p, new com.megacrit.cardcrawl.powers.PoisonPower(m, p, 2), 2));
        assertEvent(battleaudio.model.Config.Trigger.APPLY_POISON, 1); assertEvent(battleaudio.model.Config.Trigger.APPLY_ENEMY_POWER, 1); assertEvent(battleaudio.model.Config.Trigger.POISON_THRESHOLD, 1);
        if(actualEvents.getOrDefault("FILTERED_APPLY_POISON",0)!=1) throw new AssertionError("Actual +2 poison missed first-target filter");
        runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(m, p, new com.megacrit.cardcrawl.powers.PoisonPower(m, p, 1), 1));
        if(actualEvents.getOrDefault("FILTERED_APPLY_POISON",0)!=1) throw new AssertionError("Poison +1 passed +2 first-target filter");
        m.currentHealth=Math.max(5,m.currentHealth); m.isDying=false; m.isDead=false;
        int poisonHealth=m.currentHealth;
        com.megacrit.cardcrawl.actions.unique.PoisonLoseHpAction poisonTick=new com.megacrit.cardcrawl.actions.unique.PoisonLoseHpAction(
                m,p,2,com.megacrit.cardcrawl.actions.AbstractGameAction.AttackEffect.POISON);
        runAction(poisonTick);
        if(m.currentHealth>=poisonHealth) throw new AssertionError("Poison action did not deal damage");
        assertEvent(battleaudio.model.Config.Trigger.POISON_DAMAGE,1);
        assertEvent(battleaudio.model.Config.Trigger.POISON_DECREASE,1);
        assertEvent(battleaudio.model.Config.Trigger.POISON_KILL,0);
        poisonTick.update();
        assertEvent(battleaudio.model.Config.Trigger.POISON_DAMAGE,1);
        assertEvent(battleaudio.model.Config.Trigger.POISON_DECREASE,1);
        runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(p, p, new com.megacrit.cardcrawl.powers.RegenPower(p, 2), 2));
        assertEvent(battleaudio.model.Config.Trigger.GAIN_REGEN, 1);
        assertEvent(battleaudio.model.Config.Trigger.REGEN_HEAL, 0);
        int beforeRegen=p.currentHealth;
        p.currentHealth=Math.max(1,p.maxHealth-3); p.healthBarUpdatedEvent();
        runAction(new com.megacrit.cardcrawl.actions.unique.RegenAction(p,2));
        assertEvent(battleaudio.model.Config.Trigger.REGEN_HEAL, 1);
        if(p.currentHealth!=p.maxHealth-1 || actualEvents.getOrDefault("FILTERED_REGEN_HEAL",0)!=1)
            throw new AssertionError("Actual regeneration heal or minimum amount filter failed");
        p.currentHealth=p.maxHealth; p.healthBarUpdatedEvent();
        runAction(new com.megacrit.cardcrawl.actions.unique.RegenAction(p,2));
        assertEvent(battleaudio.model.Config.Trigger.REGEN_HEAL, 1);
        p.currentHealth=beforeRegen; p.healthBarUpdatedEvent();
        runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(p, p, new com.megacrit.cardcrawl.powers.IntangiblePlayerPower(p, 1), 1));
        assertEvent(battleaudio.model.Config.Trigger.GAIN_INTANGIBLE, 1);
        p.currentBlock=10; BattleAudioMod.instance.observeEvents();
        p.damage(new com.megacrit.cardcrawl.cards.DamageInfo(m,5,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.NORMAL));
        assertEvent(battleaudio.model.Config.Trigger.INTANGIBLE_MITIGATE,1);
        if(p.currentBlock!=9 || actualEvents.getOrDefault("FILTERED_INTANGIBLE_MITIGATE",0)!=1)
            throw new AssertionError("Intangible did not cap five damage to one before block");
        p.damage(new com.megacrit.cardcrawl.cards.DamageInfo(m,1,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.NORMAL));
        assertEvent(battleaudio.model.Config.Trigger.INTANGIBLE_MITIGATE,1);
        p.powers.removeIf(power->"IntangiblePlayer".equals(power.ID));
        p.damage(new com.megacrit.cardcrawl.cards.DamageInfo(m,5,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.NORMAL));
        assertEvent(battleaudio.model.Config.Trigger.INTANGIBLE_MITIGATE,1);
        p.currentBlock=0; BattleAudioMod.instance.observeEvents();
        p.orbs.clear(); p.maxOrbs = 0; BattleAudioMod.instance.observeEvents(); p.increaseMaxOrbSlots(1, false);
        assertEvent(battleaudio.model.Config.Trigger.GAIN_ORB_SLOT, 1);
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
        p.channelOrb(new com.megacrit.cardcrawl.orbs.Lightning()); p.channelOrb(new com.megacrit.cardcrawl.orbs.Frost()); drainActions();
        assertEvent(battleaudio.model.Config.Trigger.CHANNEL_ORB, 2); assertEvent(battleaudio.model.Config.Trigger.EVOKE_ORB, 1);
        assertEvent(battleaudio.model.Config.Trigger.ORB_SLOTS_UNFULL, 1);
        assertEvent(battleaudio.model.Config.Trigger.CHANNEL_ORBS_TURN, 1);
        p.evokeOrb(); assertEvent(battleaudio.model.Config.Trigger.EVOKE_ORB, 2);
        assertEvent(battleaudio.model.Config.Trigger.ORB_SLOTS_UNFULL, 2);
        assertEvent(battleaudio.model.Config.Trigger.ORB_SLOTS_FULL, 2);
        p.channelOrb(new com.megacrit.cardcrawl.orbs.Dark());
        assertEvent(battleaudio.model.Config.Trigger.ORB_SLOTS_FULL, 3); assertEvent(battleaudio.model.Config.Trigger.DARK_DAMAGE_THRESHOLD, 1);
        BattleAudioMod.instance.observeEvents();
        assertEvent(battleaudio.model.Config.Trigger.ORB_SLOTS_UNFULL, 2);
        assertEvent(battleaudio.model.Config.Trigger.CHANNEL_ORBS_TURN, 1);
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
        p.currentBlock=0; BattleAudioMod.instance.observeEvents();
        com.megacrit.cardcrawl.orbs.Frost actualFrost=new com.megacrit.cardcrawl.orbs.Frost();
        p.orbs.clear(); p.orbs.add(actualFrost); actualFrost.onEndOfTurn();
        assertEvent(battleaudio.model.Config.Trigger.ORB_PASSIVE_BLOCK,0);
        drainActions(); assertEvent(battleaudio.model.Config.Trigger.ORB_PASSIVE_BLOCK,1);
        int frostBlock=p.currentBlock; actualFrost.passiveAmount=0; actualFrost.onEndOfTurn(); drainActions();
        if(p.currentBlock!=frostBlock) throw new AssertionError("Zero Frost passive changed block");
        assertEvent(battleaudio.model.Config.Trigger.ORB_PASSIVE_BLOCK,1);
        com.megacrit.cardcrawl.orbs.Plasma actualPlasma=new com.megacrit.cardcrawl.orbs.Plasma();
        p.orbs.clear(); p.orbs.add(actualPlasma);
        com.megacrit.cardcrawl.ui.panels.EnergyPanel.setEnergy(0);
        actualPlasma.onStartOfTurn();
        assertEvent(battleaudio.model.Config.Trigger.ORB_PASSIVE_ENERGY,0);
        drainActions(); assertEvent(battleaudio.model.Config.Trigger.ORB_PASSIVE_ENERGY,1);
        int plasmaEnergy=com.megacrit.cardcrawl.ui.panels.EnergyPanel.totalCount;
        actualPlasma.passiveAmount=0; actualPlasma.onStartOfTurn(); drainActions();
        if(com.megacrit.cardcrawl.ui.panels.EnergyPanel.totalCount!=plasmaEnergy)
            throw new AssertionError("Zero Plasma passive changed energy");
        assertEvent(battleaudio.model.Config.Trigger.ORB_PASSIVE_ENERGY,1);
        p.currentBlock=0; BattleAudioMod.instance.observeEvents();
        battleaudio.model.Config.Rule channelRule = config.rules.stream().filter(rule -> rule.id.equals("CHANNEL_ORB")).findFirst().get();
        battleaudio.model.Config.Rule evokeRule = config.rules.stream().filter(rule -> rule.id.equals("EVOKE_ORB")).findFirst().get();
        for (com.megacrit.cardcrawl.orbs.AbstractOrb orb : new com.megacrit.cardcrawl.orbs.AbstractOrb[] {
                new com.megacrit.cardcrawl.orbs.Lightning(), new com.megacrit.cardcrawl.orbs.Frost(), new com.megacrit.cardcrawl.orbs.Dark(), new com.megacrit.cardcrawl.orbs.Plasma()}) {
            channelRule.parameter(battleaudio.model.Config.Trigger.CHANNEL_ORB).any = false;
            channelRule.parameter(battleaudio.model.Config.Trigger.CHANNEL_ORB).id = orb.ID;
            evokeRule.parameter(battleaudio.model.Config.Trigger.EVOKE_ORB).any = false;
            evokeRule.parameter(battleaudio.model.Config.Trigger.EVOKE_ORB).id = orb.ID;
            p.orbs.clear(); p.orbs.add(new com.megacrit.cardcrawl.orbs.EmptyOrbSlot()); BattleAudioMod.instance.observeEvents();
            int channels = eventCount(battleaudio.model.Config.Trigger.CHANNEL_ORB), evokes = eventCount(battleaudio.model.Config.Trigger.EVOKE_ORB);
            p.channelOrb(orb); p.evokeWithoutLosingOrb();
            assertEvent(battleaudio.model.Config.Trigger.CHANNEL_ORB, channels + 1); assertEvent(battleaudio.model.Config.Trigger.EVOKE_ORB, evokes + 1);
            evokeRule.parameter(battleaudio.model.Config.Trigger.EVOKE_ORB).id = "NeverMatches";
            p.evokeOrb(); assertEvent(battleaudio.model.Config.Trigger.EVOKE_ORB, evokes + 1);
        }
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
        System.out.println("PASS: all four actual orb types match specified channel/evoke filters; unmatched types do not fire");
        runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(p, p, new com.megacrit.cardcrawl.powers.FocusPower(p, 2), 2));
        assertEvent(battleaudio.model.Config.Trigger.FOCUS_THRESHOLD, 1);
        assertEvent(battleaudio.model.Config.Trigger.FOCUS_INCREASE, 1);
        runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(p, p, new com.megacrit.cardcrawl.powers.FocusPower(p, -3), -3));
        assertEvent(battleaudio.model.Config.Trigger.FOCUS_DECREASE, 1); assertEvent(battleaudio.model.Config.Trigger.FOCUS_NEGATIVE, 1);
        for (int i = 0; i < 20; i++) BattleAudioMod.instance.observeEvents();
        assertEvent(battleaudio.model.Config.Trigger.FOCUS_DECREASE, 1); assertEvent(battleaudio.model.Config.Trigger.FOCUS_NEGATIVE, 1);
        assertEvent(battleaudio.model.Config.Trigger.ORB_SLOTS_FULL, 7); assertEvent(battleaudio.model.Config.Trigger.DARK_DAMAGE_THRESHOLD, 2);
        p.orbs.clear(); p.maxOrbs=3;
        for(int i=0;i<3;i++) p.orbs.add(new com.megacrit.cardcrawl.orbs.EmptyOrbSlot());
        BattleAudioMod.instance.observeEvents();
        p.applyStartOfTurnRelics();
        int channelThresholds=eventCount(battleaudio.model.Config.Trigger.CHANNEL_ORBS_TURN);
        int shivThresholds=eventCount(battleaudio.model.Config.Trigger.SHIV_PLAYED_TURN);
        p.useCard(new com.megacrit.cardcrawl.cards.tempCards.Shiv(),m,0);
        assertEvent(battleaudio.model.Config.Trigger.SHIV_PLAYED_TURN,shivThresholds);
        p.useCard(new com.megacrit.cardcrawl.cards.tempCards.Shiv(),m,0);
        assertEvent(battleaudio.model.Config.Trigger.SHIV_PLAYED_TURN,shivThresholds+1);
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
        p.channelOrb(new com.megacrit.cardcrawl.orbs.Lightning());
        assertEvent(battleaudio.model.Config.Trigger.CHANNEL_ORBS_TURN,channelThresholds);
        p.channelOrb(new com.megacrit.cardcrawl.orbs.Frost());
        assertEvent(battleaudio.model.Config.Trigger.CHANNEL_ORBS_TURN,channelThresholds+1);
        BattleAudioMod.instance.observeEvents();
        assertEvent(battleaudio.model.Config.Trigger.CHANNEL_ORBS_TURN,channelThresholds+1);
        orbPassiveChecks(config, p);
        damageChecks(config, p, m);
        runAction(new com.megacrit.cardcrawl.actions.watcher.ChangeStanceAction("Calm")); runAction(new com.megacrit.cardcrawl.actions.watcher.ChangeStanceAction("Wrath"));
        runAction(new com.megacrit.cardcrawl.actions.watcher.ChangeStanceAction("Divinity"));
        assertEvent(battleaudio.model.Config.Trigger.ENTER_CALM, 1); assertEvent(battleaudio.model.Config.Trigger.ENTER_WRATH, 1); assertEvent(battleaudio.model.Config.Trigger.ENTER_DIVINITY, 1);
        if(actualEvents.getOrDefault("FILTERED_CALM_TO_WRATH",0)!=1)
            throw new AssertionError("Actual Calm-to-Wrath transition filter missed or repeated");
        assertEvent(battleaudio.model.Config.Trigger.EXIT_STANCE, 1); assertEvent(battleaudio.model.Config.Trigger.STANCE_CHANGES_TURN, 1);
        assertEvent(battleaudio.model.Config.Trigger.CALM_EXIT_ENERGY,0);
        drainActions(); assertEvent(battleaudio.model.Config.Trigger.CALM_EXIT_ENERGY,1);
        runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(p, p, new com.megacrit.cardcrawl.powers.watcher.MantraPower(p, 2), 2));
        assertEvent(battleaudio.model.Config.Trigger.MANTRA_THRESHOLD, 1);
        assertEvent(battleaudio.model.Config.Trigger.MANTRA_SPENT_DIVINITY,0);
        battleaudio.model.Config.Rule mantraRule = config.rules.stream().filter(rule -> rule.id.equals("MANTRA_THRESHOLD")).findFirst().get();
        mantraRule.parameter(battleaudio.model.Config.Trigger.MANTRA_THRESHOLD).value = 10;
        p.stance=new com.megacrit.cardcrawl.stances.NeutralStance();
        runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(p, p, new com.megacrit.cardcrawl.powers.watcher.MantraPower(p, 8), 8));
        assertEvent(battleaudio.model.Config.Trigger.MANTRA_THRESHOLD, 2);
        assertEvent(battleaudio.model.Config.Trigger.MANTRA_SPENT_DIVINITY,0);
        drainActions();
        assertEvent(battleaudio.model.Config.Trigger.MANTRA_SPENT_DIVINITY,1);
        BattleAudioMod.instance.observeEvents();
        assertEvent(battleaudio.model.Config.Trigger.MANTRA_SPENT_DIVINITY,1);
        int stanceBefore=eventCount(battleaudio.model.Config.Trigger.STANCE_CHANGE);
        com.megacrit.cardcrawl.actions.watcher.ChangeStanceAction neutralChange=new com.megacrit.cardcrawl.actions.watcher.ChangeStanceAction("Neutral");
        runAction(neutralChange);
        assertEvent(battleaudio.model.Config.Trigger.STANCE_CHANGE,stanceBefore+1);
        if(actualEvents.getOrDefault("FILTERED_DIVINITY_TO_NEUTRAL",0)!=1)
            throw new AssertionError("Divinity to Neutral transition filter failed");
        neutralChange.update(); runAction(new com.megacrit.cardcrawl.actions.watcher.ChangeStanceAction("Neutral"));
        assertEvent(battleaudio.model.Config.Trigger.STANCE_CHANGE,stanceBefore+1);
        p.stance=new com.megacrit.cardcrawl.stances.CalmStance(); p.currentBlock=0; BattleAudioMod.instance.observeEvents();
        p.addBlock(3); assertEvent(battleaudio.model.Config.Trigger.CALM_GAIN_BLOCK,1);
        p.stance=new com.megacrit.cardcrawl.stances.WrathStance();
        m.currentHealth=m.maxHealth=50; m.currentBlock=0;
        m.damage(new com.megacrit.cardcrawl.cards.DamageInfo(p,4,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.NORMAL));
        assertEvent(battleaudio.model.Config.Trigger.WRATH_DAMAGE,1);
        if(actualEvents.getOrDefault("FILTERED_CALM_GAIN_BLOCK",0)!=1 || actualEvents.getOrDefault("FILTERED_WRATH_DAMAGE",0)!=1)
            throw new AssertionError("Calm block or Wrath actual damage filter missed");
        p.stance=new com.megacrit.cardcrawl.stances.NeutralStance(); p.currentBlock=0; BattleAudioMod.instance.observeEvents();
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
        com.megacrit.cardcrawl.cards.CardGroup held = new com.megacrit.cardcrawl.cards.CardGroup(com.megacrit.cardcrawl.cards.CardGroup.CardGroupType.UNSPECIFIED);
        com.megacrit.cardcrawl.cards.AbstractCard retained = new com.megacrit.cardcrawl.cards.red.Defend_Red(); retained.selfRetain = true; held.addToTop(retained);
        runAction(new com.megacrit.cardcrawl.actions.unique.RestoreRetainedCardsAction(held));
        assertEvent(battleaudio.model.Config.Trigger.RETAIN_CARD, 1); p.applyStartOfTurnRelics(); p.useCard(retained, null, 1);
        assertEvent(battleaudio.model.Config.Trigger.PLAY_RETAINED_CARD, 1);
        p.hand.clear(); com.megacrit.cardcrawl.cards.AbstractCard pyramidCard = new com.megacrit.cardcrawl.cards.red.Defend_Red(); p.hand.addToTop(pyramidCard);
        p.relics.add(new com.megacrit.cardcrawl.relics.RunicPyramid());
        runAction(new com.megacrit.cardcrawl.actions.common.DiscardAtEndOfTurnAction());
        assertEvent(battleaudio.model.Config.Trigger.RETAIN_CARD, 2); p.applyStartOfTurnRelics(); p.useCard(pyramidCard, null, 1);
        assertEvent(battleaudio.model.Config.Trigger.PLAY_RETAINED_CARD, 2); p.relics.clear();
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
        p.powers.clear(); p.currentBlock = 0; int before = p.currentHealth;
        p.damage(new com.megacrit.cardcrawl.cards.DamageInfo(p, 1, com.megacrit.cardcrawl.cards.DamageInfo.DamageType.HP_LOSS));
        if (p.currentHealth >= before) throw new AssertionError("Self injury did not execute"); assertEvent(battleaudio.model.Config.Trigger.SELF_DAMAGE, 1);
        if(actualEvents.getOrDefault("FILTERED_HP_LOSS_PLAYER",0)!=1 || actualEvents.getOrDefault("FILTERED_HP_LOSS_ENEMY",0)!=0)
            throw new AssertionError("Self damage source was not scoped to player");
        p.drawPile.clear();
        com.megacrit.cardcrawl.cards.AbstractCard scryKeep=new com.megacrit.cardcrawl.cards.red.Defend_Red();
        com.megacrit.cardcrawl.cards.AbstractCard scryDiscardCard=new com.megacrit.cardcrawl.cards.red.Strike_Red();
        p.drawPile.addToTop(scryKeep); p.drawPile.addToTop(scryDiscardCard);
        com.megacrit.cardcrawl.actions.utility.ScryAction scryAction=new com.megacrit.cardcrawl.actions.utility.ScryAction(2);
        scryAction.update(); assertEvent(battleaudio.model.Config.Trigger.SCRY, 1);
        assertEvent(battleaudio.model.Config.Trigger.SCRY_CARDS,0);
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen.selectedCards.add(scryDiscardCard);
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen.confirmButton.hb.clicked=true;
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen.update();
        runAction(scryAction);
        assertEvent(battleaudio.model.Config.Trigger.SCRY_CARDS,1);
        assertEvent(battleaudio.model.Config.Trigger.SCRY_DISCARD_CARD,1);
        assertEvent(battleaudio.model.Config.Trigger.SCRY_KEEP_CARD,1);
        if(actualEvents.getOrDefault("FILTERED_SCRY_DISCARD",0)!=1)
            throw new AssertionError("Scry final discard missed selected card ID");
        p.drawPile.clear(); com.megacrit.cardcrawl.cards.AbstractCard shortScryCard=new com.megacrit.cardcrawl.cards.red.Defend_Red();
        p.drawPile.addToTop(shortScryCard);
        com.megacrit.cardcrawl.actions.utility.ScryAction shortScry=new com.megacrit.cardcrawl.actions.utility.ScryAction(1);
        shortScry.update(); com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen.selectedCards.add(shortScryCard);
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen.confirmButton.hb.clicked=true;
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen.update(); runAction(shortScry);
        assertEvent(battleaudio.model.Config.Trigger.SCRY_CARDS,1);
        p.drawPile.clear(); p.drawPile.addToTop(new com.megacrit.cardcrawl.cards.red.Defend_Red());
        com.megacrit.cardcrawl.actions.utility.ScryAction canceledScry=new com.megacrit.cardcrawl.actions.utility.ScryAction(1);
        int keptBefore=eventCount(battleaudio.model.Config.Trigger.SCRY_KEEP_CARD);
        canceledScry.update(); com.megacrit.cardcrawl.dungeons.AbstractDungeon.closeCurrentScreen();
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen.update(); runAction(canceledScry);
        assertEvent(battleaudio.model.Config.Trigger.SCRY_KEEP_CARD,keptBefore);
        com.megacrit.cardcrawl.actions.utility.ScryAction keepAllScry=new com.megacrit.cardcrawl.actions.utility.ScryAction(1);
        keepAllScry.update(); com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen.confirmButton.hb.clicked=true;
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen.update(); runAction(keepAllScry);
        assertEvent(battleaudio.model.Config.Trigger.SCRY_KEEP_CARD,keptBefore+1);
        keepAllScry.update(); assertEvent(battleaudio.model.Config.Trigger.SCRY_KEEP_CARD,keptBefore+1);
        p.drawPile.clear(); p.discardPile.addToTop(new com.megacrit.cardcrawl.cards.red.Defend_Red());
        runAction(new com.megacrit.cardcrawl.actions.common.EmptyDeckShuffleAction()); assertEvent(battleaudio.model.Config.Trigger.RESHUFFLE, 1);
        BattleAudioMod.instance.observeEvents(); int lowBefore=eventCount(battleaudio.model.Config.Trigger.ENEMY_LOW_HEALTH);
        int matchingLowBefore=actualEvents.getOrDefault("FILTERED_LOW_ENEMY_MATCH",0);
        int missingLowBefore=actualEvents.getOrDefault("FILTERED_LOW_ENEMY_MISS",0);
        m.currentHealth = 1; m.healthBarUpdatedEvent();
        for (int i = 0; i < 20; i++) BattleAudioMod.instance.observeEvents();
        assertEvent(battleaudio.model.Config.Trigger.ENEMY_LOW_HEALTH, lowBefore+1);
        if(actualEvents.getOrDefault("FILTERED_LOW_ENEMY_MATCH",0)!=matchingLowBefore+1
                || actualEvents.getOrDefault("FILTERED_LOW_ENEMY_MISS",0)!=missingLowBefore)
            throw new AssertionError("Low-health enemy ID/type filter missed or matched wrong target");
        // Keep this fixture independent from enemies killed by earlier event checks.
        int killsBefore=eventCount(battleaudio.model.Config.Trigger.KILL_ENEMY);
        int poisonKillsBefore=eventCount(battleaudio.model.Config.Trigger.POISON_KILL);
        int matchingKillsBefore=actualEvents.getOrDefault("FILTERED_KILL_MATCH",0);
        int missingKillsBefore=actualEvents.getOrDefault("FILTERED_KILL_MISS",0);
        int lastKillsBefore=actualEvents.getOrDefault("FILTERED_KILL_LAST",0);
        com.megacrit.cardcrawl.monsters.MonsterGroup killGroup=com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters();
        killGroup.monsters.clear();
        m=new com.megacrit.cardcrawl.monsters.exordium.JawWorm(0,0);
        killGroup.monsters.add(m);
        killGroup.monsters.add(new com.megacrit.cardcrawl.monsters.exordium.JawWorm(300,0));
        battleaudio.model.Config.EventFilter killFilter=config.rules.stream().filter(rule->rule.id.equals("FILTERED_KILL_MATCH"))
                .findFirst().get().filter(battleaudio.model.Config.Trigger.KILL_ENEMY);
        killFilter.targetId=m.id; killFilter.enemyType=m.type.name();
        m.currentHealth = 1;
        runAction(new com.megacrit.cardcrawl.actions.unique.PoisonLoseHpAction(
                m,p,1,com.megacrit.cardcrawl.actions.AbstractGameAction.AttackEffect.POISON));
        assertEvent(battleaudio.model.Config.Trigger.POISON_KILL,poisonKillsBefore+1);
        assertEvent(battleaudio.model.Config.Trigger.KILL_ENEMY,killsBefore+1);
        if(actualEvents.getOrDefault("FILTERED_KILL_MATCH",0)!=matchingKillsBefore+1
                || actualEvents.getOrDefault("FILTERED_KILL_MISS",0)!=missingKillsBefore)
            throw new AssertionError("Actual final enemy kill filters missed or matched unrelated ID: match="+
                    actualEvents.getOrDefault("FILTERED_KILL_MATCH",0)+" miss="+actualEvents.getOrDefault("FILTERED_KILL_MISS",0)+
                    " target="+m.id+" type="+m.type);
        com.megacrit.cardcrawl.monsters.AbstractMonster finalTarget=null;
        for(com.megacrit.cardcrawl.monsters.AbstractMonster other:com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters().monsters)
            if(other!=m && !other.isDead && !other.isDying && !other.escaped && other.currentHealth>0) {
                if(finalTarget==null) finalTarget=other;
                else { other.currentHealth=0; other.isDead=true; }
            }
        if(finalTarget!=null) {
            finalTarget.currentHealth=0; finalTarget.die();
            if(actualEvents.getOrDefault("FILTERED_KILL_LAST",0)!=lastKillsBefore+1)
                throw new AssertionError("Final surviving enemy did not pass last-enemy filter");
        }
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom().endTurn();
        assertEvent(battleaudio.model.Config.Trigger.TURN_END, 1);
        for (com.megacrit.cardcrawl.monsters.AbstractMonster monster : com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters().monsters) {
            monster.currentHealth = 0; monster.die();
        }
        com.megacrit.cardcrawl.rooms.AbstractRoom victoryRoom = com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom();
        victoryRoom.endBattle();
        int previousVictory = eventCount(battleaudio.model.Config.Trigger.BATTLE_VICTORY);
        int previousNormalVictory = eventCount(battleaudio.model.Config.Trigger.NORMAL_BATTLE_VICTORY);
        engine.battleStart("normal-victory", false, false, p.currentHealth, p.maxHealth);
        BattleAudioMod.instance.battleResult(victoryRoom);
        assertEvent(battleaudio.model.Config.Trigger.BATTLE_VICTORY, previousVictory + 1);
        assertEvent(battleaudio.model.Config.Trigger.NORMAL_BATTLE_VICTORY, previousNormalVictory + 1);
        screenOpenChecks();
        explorationChecks(p);
        victoryChecks(engine, p, m);
        verifiedSceneOptionChecks(p);
        vanillaMultiKillChecks(p);
        vanillaMapNodeTransitionChecks();
        vanillaEventRoomChecks();
        vanillaLowHealthAndMantraChecks(p);
        vanillaCardOperationChecks(p);
        vanillaCardRewardDeckChecks(p);
        vanillaOrbCauseChecks(p);
        p.currentHealth = 2; p.isDead = false; p.isDying = false;
        p.powers.clear(); p.currentBlock = 0; BattleAudioMod.instance.observeEvents();
        p.damage(new com.megacrit.cardcrawl.cards.DamageInfo(m, 100, com.megacrit.cardcrawl.cards.DamageInfo.DamageType.HP_LOSS));
        if(actualEvents.getOrDefault("FILTERED_HP_LOSS_ENEMY",0)!=1) throw new AssertionError("Enemy damage source was not scoped to enemy");
        for (int i = 0; i < 20; i++) BattleAudioMod.instance.observeEvents();
        assertEvent(battleaudio.model.Config.Trigger.PLAYER_DEATH, 1);
        vanillaActEntryChecks(p);
        eventAudio.stopAll();
        System.out.println("PASS: all five new events create playing real OpenAL handles (muted), with independent overlapping instances");
        System.out.println("PASS: actual game event patches deliver audio triggers across common and all four character categories");
    }
    private void actualEncounterSelectionChecks() {
        com.megacrit.cardcrawl.map.MapRoomNode node=com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode;
        com.megacrit.cardcrawl.rooms.AbstractRoom previousRoom=node.room;
        String previousBoss=com.megacrit.cardcrawl.dungeons.AbstractDungeon.bossKey;
        String previousEncounter=com.megacrit.cardcrawl.dungeons.AbstractDungeon.lastCombatMetricKey;
        java.util.ArrayList<String> previousElites=com.megacrit.cardcrawl.dungeons.AbstractDungeon.eliteMonsterList;
        boolean previousAtBoss=com.megacrit.cardcrawl.dungeons.AbstractDungeon.dungeonMapScreen.map.atBoss;
        Object previousConfig=modField("config"), previousEngine=modField("engine"), previousEvents=modField("events");
        Object previousRun=modField("run"), previousPending=modField("pendingRun"), previousTrackedRoom=modField("room");
        Object previousSerial=modField("roomSerial");
        battleaudio.storage.AudioLibrary library=(battleaudio.storage.AudioLibrary)modField("library");
        battleaudio.audio.PlaybackManager manager=new battleaudio.audio.PlaybackManager(library,error->{throw new AssertionError(error);});
        try {
            battleaudio.model.Config.Rule sample=library.importAudio(Paths.get(System.getProperty("battleaudio.sample")));
            com.google.gson.Gson gson=new com.google.gson.Gson();
            battleaudio.model.Config.Rule any=gson.fromJson(gson.toJson(sample),battleaudio.model.Config.Rule.class);
            battleaudio.model.Config.Rule heart=gson.fromJson(gson.toJson(sample),battleaudio.model.Config.Rule.class);
            battleaudio.model.Config.Rule elite=gson.fromJson(gson.toJson(sample),battleaudio.model.Config.Rule.class);
            any.id="smoke-any-boss";heart.id="smoke-heart";elite.id="smoke-sentries";
            any.triggers=java.util.EnumSet.of(battleaudio.model.Config.Trigger.BOSS_START);
            heart.triggers=java.util.EnumSet.of(battleaudio.model.Config.Trigger.BOSS_START);
            elite.triggers=java.util.EnumSet.of(battleaudio.model.Config.Trigger.ELITE_START);
            heart.filter(battleaudio.model.Config.Trigger.BOSS_START).selectEncounters(true);
            heart.filter(battleaudio.model.Config.Trigger.BOSS_START).encounterIds.add("The Heart");
            elite.filter(battleaudio.model.Config.Trigger.ELITE_START).selectEncounters(true);
            elite.filter(battleaudio.model.Config.Trigger.ELITE_START).encounterIds.add("3 Sentries");
            battleaudio.model.Config config=new battleaudio.model.Config();config.rules.add(any);config.rules.add(heart);config.rules.add(elite);
            battleaudio.core.RuleEngine engine=new battleaudio.core.RuleEngine(config,manager);
            com.megacrit.cardcrawl.characters.AbstractPlayer player=com.megacrit.cardcrawl.dungeons.AbstractDungeon.player;
            engine.beginRun(false,player.currentHealth,player.maxHealth);
            setModField("config",config);setModField("engine",engine);
            setModField("events",new battleaudio.core.GameEvents(engine));setModField("run",true);setModField("pendingRun",false);
            String modBoss="battleaudio:test-boss",modElite="battleaudio:test-elite";
            basemod.BaseMod.addMonster(modBoss,"Test Boss",(basemod.BaseMod.GetMonsterGroup)()->
                    new com.megacrit.cardcrawl.monsters.MonsterGroup(new com.megacrit.cardcrawl.monsters.exordium.JawWorm(0,0)));
            basemod.BaseMod.addMonster(modElite,"Test Elite",(basemod.BaseMod.GetMonsterGroup)()->
                    new com.megacrit.cardcrawl.monsters.MonsterGroup(new com.megacrit.cardcrawl.monsters.exordium.JawWorm(0,0)));
            basemod.BaseMod.addBoss("Exordium",modBoss,"","");
            basemod.BaseMod.addEliteEncounter("Exordium",new com.megacrit.cardcrawl.monsters.MonsterInfo(modElite,1f));
            for(String id:new String[]{"The Guardian","Hexaghost","Slime Boss","Automaton","Collector","Champ",
                    "Awakened One","Time Eater","Donu and Deca","The Heart",modBoss}) {
                heart.filter(battleaudio.model.Config.Trigger.BOSS_START).encounterIds.clear();
                heart.filter(battleaudio.model.Config.Trigger.BOSS_START).encounterIds.add(id);
                com.megacrit.cardcrawl.rooms.MonsterRoomBoss room=new com.megacrit.cardcrawl.rooms.MonsterRoomBoss();
                node.room=room;com.megacrit.cardcrawl.dungeons.AbstractDungeon.bossKey=id;
                room.monsters=CardCrawlGame.dungeon.getBoss();
                if(!id.equals(com.megacrit.cardcrawl.dungeons.AbstractDungeon.lastCombatMetricKey))throw new AssertionError("Game boss identity not captured: "+id);
                engine.battleEnd();
                room.phase=com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT;
                com.megacrit.cardcrawl.rooms.AbstractRoom.waitTimer=.0001f;
                com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
                room.update();
                if(!engine.inBattle())throw new AssertionError("Actual boss room did not publish battle start: "+id);
                if(manager.count()!=2 || !manager.isRuleActive(heart.id) || !manager.isRuleActive(any.id))
                    throw new AssertionError("Boss selection did not match actual game encounter "+id+": "+manager.count());
                manager.stopAll();
            }
            for(String id:new String[]{"Gremlin Nob","Lagavulin","3 Sentries","Gremlin Leader","Slavers",
                    "Book of Stabbing","Giant Head","Nemesis","Reptomancer","Shield and Spear",modElite}) {
                elite.filter(battleaudio.model.Config.Trigger.ELITE_START).encounterIds.clear();
                elite.filter(battleaudio.model.Config.Trigger.ELITE_START).encounterIds.add(id);
                com.megacrit.cardcrawl.rooms.MonsterRoomElite room=new com.megacrit.cardcrawl.rooms.MonsterRoomElite();node.room=room;
                com.megacrit.cardcrawl.dungeons.AbstractDungeon.eliteMonsterList=new java.util.ArrayList<>(java.util.Collections.singletonList(id));
                room.monsters=CardCrawlGame.dungeon.getEliteMonsterForRoomCreation();
                engine.battleEnd();room.phase=com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT;
                com.megacrit.cardcrawl.rooms.AbstractRoom.waitTimer=.0001f;
                com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();room.update();
                if(!engine.inBattle())throw new AssertionError("Actual elite room did not publish battle start: "+id);
                if(manager.count()!=1 || !manager.isRuleActive(elite.id))throw new AssertionError("Actual elite encounter was not matched: "+id);
                manager.stopAll();
            }
            System.out.println("PASS: 20 vanilla and 2 generically registered boss/elite encounters drive room callbacks and muted OpenAL playback");
        } catch(Exception ex) { throw new RuntimeException(ex); }
        finally {
            manager.stopAll();battleaudio.audio.BackgroundMusic.update(1);
            node.room=previousRoom;com.megacrit.cardcrawl.dungeons.AbstractDungeon.bossKey=previousBoss;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.lastCombatMetricKey=previousEncounter;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.eliteMonsterList=previousElites;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.dungeonMapScreen.map.atBoss=previousAtBoss;
            setModField("config",previousConfig);setModField("engine",previousEngine);setModField("events",previousEvents);
            setModField("run",previousRun);setModField("pendingRun",previousPending);setModField("room",previousTrackedRoom);setModField("roomSerial",previousSerial);
        }
    }
    private void actualKillFilterChecks() {
        com.megacrit.cardcrawl.map.MapRoomNode node=com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode;
        com.megacrit.cardcrawl.rooms.AbstractRoom room=node.room;
        com.megacrit.cardcrawl.monsters.MonsterGroup previousMonsters=room.monsters;
        Object previousConfig=modField("config"),previousEngine=modField("engine"),previousEvents=modField("events");
        Object previousRun=modField("run"),previousPending=modField("pendingRun"),previousRoom=modField("room");
        battleaudio.storage.AudioLibrary library=(battleaudio.storage.AudioLibrary)modField("library");
        battleaudio.audio.PlaybackManager manager=new battleaudio.audio.PlaybackManager(library,error->{throw new AssertionError(error);});
        try {
            battleaudio.model.Config.Rule sample=library.importAudio(Paths.get(System.getProperty("battleaudio.sample")));
            sample.audio().volume=0;
            com.google.gson.Gson gson=new com.google.gson.Gson();
            battleaudio.model.Config config=new battleaudio.model.Config();
            for(String id:new String[]{"any","not-last","last","wrong-id","wrong-type"}) {
                battleaudio.model.Config.Rule rule=gson.fromJson(gson.toJson(sample),battleaudio.model.Config.Rule.class);
                rule.id=id;rule.retriggerMode=battleaudio.model.Config.RetriggerMode.OVERLAP;
                rule.triggers=java.util.EnumSet.of(battleaudio.model.Config.Trigger.KILL_ENEMY);
                if(!id.equals("any")) {
                    battleaudio.model.Config.EventFilter filter=rule.filter(battleaudio.model.Config.Trigger.KILL_ENEMY);
                    filter.targetId=id.equals("wrong-id")?"not-the-enemy":"JawWorm";
                    filter.enemyType=id.equals("wrong-type")?"ELITE":"NORMAL";
                    if(id.equals("not-last"))filter.lastEnemy=false;
                    if(id.equals("last"))filter.lastEnemy=true;
                }
                config.rules.add(rule);
            }
            battleaudio.core.RuleEngine engine=new battleaudio.core.RuleEngine(config,manager);
            battleaudio.core.GameEvents receiver=new battleaudio.core.GameEvents(engine);
            com.megacrit.cardcrawl.characters.AbstractPlayer player=com.megacrit.cardcrawl.dungeons.AbstractDungeon.player;
            setModField("config",config);setModField("engine",engine);setModField("events",receiver);
            setModField("run",true);setModField("pendingRun",false);setModField("room",room);
            for(boolean poison:new boolean[]{false,true}) {
                com.megacrit.cardcrawl.monsters.AbstractMonster victim=new com.megacrit.cardcrawl.monsters.exordium.JawWorm(0,0);
                com.megacrit.cardcrawl.monsters.AbstractMonster survivor=new com.megacrit.cardcrawl.monsters.exordium.JawWorm(300,0);
                victim.setMove((byte)0,com.megacrit.cardcrawl.monsters.AbstractMonster.Intent.ATTACK,1);
                survivor.setMove((byte)0,com.megacrit.cardcrawl.monsters.AbstractMonster.Intent.ATTACK,1);
                room.monsters=new com.megacrit.cardcrawl.monsters.MonsterGroup(new com.megacrit.cardcrawl.monsters.AbstractMonster[]{victim,survivor});
                victim.currentHealth=1;victim.currentBlock=0;survivor.currentHealth=1;survivor.currentBlock=0;
                engine.beginRun(false,player.currentHealth,player.maxHealth);
                engine.battleStart("kill-fixture-"+poison,false,false,player.currentHealth,player.maxHealth);
                receiver.baseline();
                if(poison)runAction(new com.megacrit.cardcrawl.actions.unique.PoisonLoseHpAction(
                        victim,player,1,com.megacrit.cardcrawl.actions.AbstractGameAction.AttackEffect.POISON));
                else victim.damage(new com.megacrit.cardcrawl.cards.DamageInfo(player,10,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.NORMAL));
                if(!manager.isRuleActive("any") || !manager.isRuleActive("not-last") || manager.isRuleActive("last")
                        || manager.isRuleActive("wrong-id") || manager.isRuleActive("wrong-type") || manager.count()!=2)
                    throw new AssertionError("Non-final kill filter mismatch, poison="+poison+", audio="+manager.count());
                victim.die();
                if(manager.count()!=2)throw new AssertionError("Repeated death started duplicate audio, poison="+poison);
                manager.stopAll();
                survivor.damage(new com.megacrit.cardcrawl.cards.DamageInfo(player,10,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.NORMAL));
                if(!manager.isRuleActive("any") || !manager.isRuleActive("last") || manager.isRuleActive("not-last")
                        || manager.isRuleActive("wrong-id") || manager.isRuleActive("wrong-type") || manager.count()!=2)
                    throw new AssertionError("Final kill filter mismatch, poison="+poison+", audio="+manager.count());
                manager.stopAll();
                System.out.println("PASS: "+(poison?"poison":"normal")+" kill: non-final/final, target/type negatives, no duplicate, real audio");
            }
        } catch(Exception ex) { throw new RuntimeException(ex); }
        finally {
            manager.stopAll();battleaudio.audio.BackgroundMusic.update(1);room.monsters=previousMonsters;
            setModField("config",previousConfig);setModField("engine",previousEngine);setModField("events",previousEvents);
            setModField("run",previousRun);setModField("pendingRun",previousPending);setModField("room",previousRoom);
        }
    }
    private void damageChecks(battleaudio.model.Config config, com.megacrit.cardcrawl.characters.AbstractPlayer p, com.megacrit.cardcrawl.monsters.AbstractMonster m) {
        battleaudio.model.Config.Trigger trigger=battleaudio.model.Config.Trigger.DAMAGE_THRESHOLD;
        config.rules.stream().filter(r -> r.id.equals(trigger.name())).findFirst().get().parameter(trigger).value=10;
        int initial=eventCount(trigger); m.powers.clear(); m.currentHealth=m.maxHealth=500; m.currentBlock=8;
        m.damage(new com.megacrit.cardcrawl.cards.DamageInfo(p,10,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.NORMAL));
        for(int i=0;i<3;i++) m.damage(new com.megacrit.cardcrawl.cards.DamageInfo(p,5,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.NORMAL));
        assertEvent(trigger,initial);
        m.damage(new com.megacrit.cardcrawl.cards.DamageInfo(p,10,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.NORMAL));
        m.damage(new com.megacrit.cardcrawl.cards.DamageInfo(p,11,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.THORNS));
        runAction(new com.megacrit.cardcrawl.actions.unique.PoisonLoseHpAction(m,p,10,com.megacrit.cardcrawl.actions.AbstractGameAction.AttackEffect.POISON));
        assertEvent(trigger,initial+3);
        m.damage(new com.megacrit.cardcrawl.cards.DamageInfo(m,30,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.HP_LOSS));
        m.damage(new com.megacrit.cardcrawl.cards.DamageInfo(null,30,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.HP_LOSS));
        assertEvent(trigger,initial+3);
        m.currentHealth=3; m.damage(new com.megacrit.cardcrawl.cards.DamageInfo(p,100,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.NORMAL));
        assertEvent(trigger,initial+3);
        m.isDead=m.isDying=false; m.currentHealth=m.maxHealth=500; m.currentBlock=0;
        com.megacrit.cardcrawl.monsters.MonsterGroup previous=com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom().monsters;
        try {
            com.megacrit.cardcrawl.monsters.AbstractMonster second=new com.megacrit.cardcrawl.monsters.exordium.Cultist(0,0);
            second.currentHealth=second.maxHealth=200;
            int shivBefore=eventCount(battleaudio.model.Config.Trigger.SHIV_DAMAGE);
            com.megacrit.cardcrawl.cards.tempCards.Shiv shiv=new com.megacrit.cardcrawl.cards.tempCards.Shiv();
            shiv.calculateCardDamage(m);
            m.currentBlock=50;
            shiv.use(p,m);
            assertEvent(battleaudio.model.Config.Trigger.SHIV_DAMAGE,shivBefore);
            drainActions(); assertEvent(battleaudio.model.Config.Trigger.SHIV_DAMAGE,shivBefore);
            m.currentBlock=0;
            shiv.use(p,m); drainActions();
            assertEvent(battleaudio.model.Config.Trigger.SHIV_DAMAGE,shivBefore+1);
            int ordinaryShivMatches=actualEvents.getOrDefault("FILTERED_SHIV_DAMAGE",0);
            shiv.upgrade(); shiv.calculateCardDamage(m); shiv.use(p,m); drainActions();
            assertEvent(battleaudio.model.Config.Trigger.SHIV_DAMAGE,shivBefore+2);
            if(actualEvents.getOrDefault("FILTERED_SHIV_DAMAGE",0)!=1)
                throw new AssertionError("Shiv damage filter ordinary="+ordinaryShivMatches+" upgraded="+
                        actualEvents.getOrDefault("FILTERED_SHIV_DAMAGE",0)+" cardDamage="+shiv.damage);
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom().monsters=new com.megacrit.cardcrawl.monsters.MonsterGroup(new com.megacrit.cardcrawl.monsters.AbstractMonster[]{m,second});
            int count=eventCount(trigger);
            runAction(new com.megacrit.cardcrawl.actions.common.DamageAllEnemiesAction(p,new int[]{10,10},com.megacrit.cardcrawl.cards.DamageInfo.DamageType.NORMAL,com.megacrit.cardcrawl.actions.AbstractGameAction.AttackEffect.NONE));
            assertEvent(trigger,count+2);

            m.currentHealth=m.maxHealth=500; m.currentBlock=0; m.isDying=false; m.isDead=false;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom().monsters=new com.megacrit.cardcrawl.monsters.MonsterGroup(m);
            int passiveDamage=eventCount(battleaudio.model.Config.Trigger.ORB_PASSIVE_DAMAGE);
            runAction(new com.megacrit.cardcrawl.actions.defect.LightningOrbPassiveAction(new com.megacrit.cardcrawl.cards.DamageInfo(p,10,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.THORNS),new com.megacrit.cardcrawl.orbs.Lightning(),false));
            assertEvent(battleaudio.model.Config.Trigger.ORB_PASSIVE_DAMAGE,passiveDamage);
            drainActions(); assertEvent(trigger,count+3);
            assertEvent(battleaudio.model.Config.Trigger.ORB_PASSIVE_DAMAGE,passiveDamage+1);
            if(actualEvents.getOrDefault("FILTERED_LIGHTNING_DAMAGE",0)!=1)
                throw new AssertionError("Actual Lightning damage missed source and amount filters");
            m.currentBlock=20;
            runAction(new com.megacrit.cardcrawl.actions.defect.LightningOrbPassiveAction(new com.megacrit.cardcrawl.cards.DamageInfo(p,10,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.THORNS),new com.megacrit.cardcrawl.orbs.Lightning(),false));
            drainActions(); assertEvent(battleaudio.model.Config.Trigger.ORB_PASSIVE_DAMAGE,passiveDamage+1);
            if(actualEvents.getOrDefault("FILTERED_LIGHTNING_DAMAGE",0)!=1)
                throw new AssertionError("Fully blocked Lightning passed actual damage filter");
            m.currentBlock=0;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom().monsters=new com.megacrit.cardcrawl.monsters.MonsterGroup(new com.megacrit.cardcrawl.monsters.AbstractMonster[]{m,second});
            runAction(new com.megacrit.cardcrawl.actions.defect.LightningOrbPassiveAction(new com.megacrit.cardcrawl.cards.DamageInfo(p,10,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.THORNS),new com.megacrit.cardcrawl.orbs.Lightning(),true));
            drainActions(); assertEvent(battleaudio.model.Config.Trigger.ORB_PASSIVE_DAMAGE,passiveDamage+3);
            if(actualEvents.getOrDefault("FILTERED_LIGHTNING_DAMAGE",0)!=3)
                throw new AssertionError("Lightning multi-target actual damage filter missed targets");
            if(actualEvents.getOrDefault("FILTERED_LIGHTNING_MISS",0)!=0)
                throw new AssertionError("Lightning damage matched a different enemy ID");
            m.currentHealth=m.maxHealth=500; m.currentBlock=0; m.isDying=false; m.isDead=false;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom().monsters=new com.megacrit.cardcrawl.monsters.MonsterGroup(m);
            int darkBefore=eventCount(battleaudio.model.Config.Trigger.DARK_EVOKE_DAMAGE);
            com.megacrit.cardcrawl.orbs.Dark dark=new com.megacrit.cardcrawl.orbs.Dark();
            dark.evokeAmount=10; m.currentBlock=50; dark.onEvoke();
            assertEvent(battleaudio.model.Config.Trigger.DARK_EVOKE_DAMAGE,darkBefore);
            drainActions(); assertEvent(battleaudio.model.Config.Trigger.DARK_EVOKE_DAMAGE,darkBefore);
            m.currentBlock=0; dark.onEvoke(); drainActions();
            assertEvent(battleaudio.model.Config.Trigger.DARK_EVOKE_DAMAGE,darkBefore+1);
            dark.evokeAmount=2; dark.onEvoke(); drainActions();
            assertEvent(battleaudio.model.Config.Trigger.DARK_EVOKE_DAMAGE,darkBefore+2);
            if(actualEvents.getOrDefault("FILTERED_DARK_EVOKE_DAMAGE",0)!=1
                    || actualEvents.getOrDefault("MISS_DARK_EVOKE_DAMAGE",0)!=0)
                throw new AssertionError("Dark evoke actual damage filters failed");
            com.megacrit.cardcrawl.stances.AbstractStance stance=p.stance;
            try {
                p.stance=new com.megacrit.cardcrawl.stances.DivinityStance();
                int divineBefore=eventCount(battleaudio.model.Config.Trigger.DIVINITY_DAMAGE);
                m.currentBlock=10; m.damage(new com.megacrit.cardcrawl.cards.DamageInfo(p,5,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.NORMAL));
                assertEvent(battleaudio.model.Config.Trigger.DIVINITY_DAMAGE,divineBefore);
                m.currentBlock=0; m.damage(new com.megacrit.cardcrawl.cards.DamageInfo(p,5,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.NORMAL));
                assertEvent(battleaudio.model.Config.Trigger.DIVINITY_DAMAGE,divineBefore+1);
                m.damage(new com.megacrit.cardcrawl.cards.DamageInfo(m,5,com.megacrit.cardcrawl.cards.DamageInfo.DamageType.NORMAL));
                assertEvent(battleaudio.model.Config.Trigger.DIVINITY_DAMAGE,divineBefore+1);
                if(actualEvents.getOrDefault("FILTERED_DIVINITY_DAMAGE",0)!=1 || actualEvents.getOrDefault("MISS_DIVINITY_DAMAGE",0)!=0)
                    throw new AssertionError("Divinity actual damage filters failed");
            } finally { p.stance=stance; }
            com.megacrit.cardcrawl.orbs.Plasma plasma=new com.megacrit.cardcrawl.orbs.Plasma();
            int plasmaBefore=eventCount(battleaudio.model.Config.Trigger.PLASMA_EVOKE_ENERGY);
            plasma.evokeAmount=3; plasma.onEvoke();
            assertEvent(battleaudio.model.Config.Trigger.PLASMA_EVOKE_ENERGY,plasmaBefore);
            drainActions(); assertEvent(battleaudio.model.Config.Trigger.PLASMA_EVOKE_ENERGY,plasmaBefore+1);
            plasma.evokeAmount=0; plasma.onEvoke(); drainActions();
            assertEvent(battleaudio.model.Config.Trigger.PLASMA_EVOKE_ENERGY,plasmaBefore+1);
            if(actualEvents.getOrDefault("FILTERED_PLASMA_EVOKE_ENERGY",0)!=1)
                throw new AssertionError("Plasma evoke actual energy filter failed");
        } finally { com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom().monsters=previous; }
        System.out.println("PASS: actual enemy damage excludes block/overkill/self/unknown source; includes player attack, relic-style THORNS and poison, each settlement separately");
        System.out.println("PASS: real AoE judges each target separately; actual lightning passive damage qualifies");
    }
    private void explorationChecks(com.megacrit.cardcrawl.characters.AbstractPlayer p) {
        com.megacrit.cardcrawl.rooms.AbstractRoom previous=com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room;
        try {
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.closeCurrentScreen();
            com.megacrit.cardcrawl.rooms.RestRoom room=new com.megacrit.cardcrawl.rooms.RestRoom();
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room=room; room.onPlayerEntry();
            assertEvent(battleaudio.model.Config.Trigger.CAMPFIRE_REST,0); assertEvent(battleaudio.model.Config.Trigger.CAMPFIRE_SMITH,0);
            com.megacrit.cardcrawl.vfx.campfire.CampfireSleepEffect rest=new com.megacrit.cardcrawl.vfx.campfire.CampfireSleepEffect();
            assertEvent(battleaudio.model.Config.Trigger.CAMPFIRE_REST,0);
            p.currentHealth=p.maxHealth; rest.duration=rest.startingDuration-.6f;
            rest.update(); for(int i=0;i<3;i++) rest.update(); assertEvent(battleaudio.model.Config.Trigger.CAMPFIRE_REST,1);
            com.megacrit.cardcrawl.vfx.campfire.CampfireSmithEffect smith=new com.megacrit.cardcrawl.vfx.campfire.CampfireSmithEffect();
            smith.duration=.9f; smith.update(); assertEvent(battleaudio.model.Config.Trigger.CAMPFIRE_SMITH,0);
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen.selectedCards.clear();
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.closeCurrentScreen(); smith.update(); assertEvent(battleaudio.model.Config.Trigger.CAMPFIRE_SMITH,0);
            com.megacrit.cardcrawl.cards.AbstractCard card=new com.megacrit.cardcrawl.cards.red.Strike_Red();
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen.forUpgrade=true;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.isScreenUp=false;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen.selectedCards.add(card); smith.update();
            if(card.timesUpgraded!=1) throw new AssertionError("Smith did not upgrade card");
            for(int i=0;i<3;i++) smith.update(); assertEvent(battleaudio.model.Config.Trigger.CAMPFIRE_SMITH,1);
            com.megacrit.cardcrawl.rewards.chests.SmallChest chest=new com.megacrit.cardcrawl.rewards.chests.SmallChest();
            assertEvent(battleaudio.model.Config.Trigger.CHEST_OPEN,0); chest.open(false); assertEvent(battleaudio.model.Config.Trigger.CHEST_OPEN,1);
            chest.update(); assertEvent(battleaudio.model.Config.Trigger.CHEST_OPEN,1);
            com.megacrit.cardcrawl.rewards.chests.BossChest boss=new com.megacrit.cardcrawl.rewards.chests.BossChest(); boss.open(false);
            assertEvent(battleaudio.model.Config.Trigger.CHEST_OPEN,2);
            System.out.println("PASS: real rest at full HP once, smith selection/cancel silent and completed upgrade once, normal/boss chest open events");
        } finally {
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.closeCurrentScreen(); com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room=previous;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectList.clear(); com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectsQueue.clear();
        }
    }
    private void orbPassiveChecks(battleaudio.model.Config config, com.megacrit.cardcrawl.characters.AbstractPlayer p) {
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
        runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(p, p, new com.megacrit.cardcrawl.powers.FocusPower(p, 2), 2));
        assertEvent(battleaudio.model.Config.Trigger.FOCUS_INCREASE, 2);
        for (int i = 0; i < 20; i++) BattleAudioMod.instance.observeEvents();
        assertEvent(battleaudio.model.Config.Trigger.FOCUS_INCREASE, 2);
        p.orbs.clear(); p.maxOrbs = 2;
        com.megacrit.cardcrawl.orbs.Dark first = new com.megacrit.cardcrawl.orbs.Dark(), second = new com.megacrit.cardcrawl.orbs.Dark();
        p.orbs.add(first); p.orbs.add(second); BattleAudioMod.instance.observeEvents();
        int accumulations = eventCount(battleaudio.model.Config.Trigger.DARK_ACCUMULATE), passives = eventCount(battleaudio.model.Config.Trigger.ORB_PASSIVE);
        int initial = first.evokeAmount;
        first.onEndOfTurn(); second.onEndOfTurn(); first.onEndOfTurn();
        if (first.evokeAmount <= initial + 2) throw new AssertionError("Dark fixture did not actually accumulate game-sized damage");
        assertEvent(battleaudio.model.Config.Trigger.DARK_ACCUMULATE, accumulations + 3);
        assertEvent(battleaudio.model.Config.Trigger.ORB_PASSIVE, passives + 3);
        for (int i = 0; i < 20; i++) { BattleAudioMod.instance.observeEvents(); first.updateDescription(); first.updateAnimation(); }
        assertEvent(battleaudio.model.Config.Trigger.DARK_ACCUMULATE, accumulations + 3);
        runAction(new com.megacrit.cardcrawl.actions.common.ApplyPowerAction(p, p, new com.megacrit.cardcrawl.powers.FocusPower(p, -20), -20));
        initial = first.evokeAmount; first.onEndOfTurn();
        if (first.evokeAmount != initial) throw new AssertionError("Zero passive dark should not increase damage");
        assertEvent(battleaudio.model.Config.Trigger.DARK_ACCUMULATE, accumulations + 3);
        assertEvent(battleaudio.model.Config.Trigger.ORB_PASSIVE, passives + 4);
        battleaudio.model.Config.Rule passive = config.rules.stream().filter(rule -> rule.id.equals("ORB_PASSIVE")).findFirst().get();
        com.megacrit.cardcrawl.orbs.AbstractOrb[] orbs = {new com.megacrit.cardcrawl.orbs.Lightning(), new com.megacrit.cardcrawl.orbs.Frost(), new com.megacrit.cardcrawl.orbs.Dark(), new com.megacrit.cardcrawl.orbs.Plasma()};
        p.powers.clear(); BattleAudioMod.instance.observeEvents();
        for (com.megacrit.cardcrawl.orbs.AbstractOrb orb : orbs) {
            p.orbs.clear(); p.orbs.add(orb); BattleAudioMod.instance.observeEvents();
            int before = eventCount(battleaudio.model.Config.Trigger.ORB_PASSIVE);
            passive.parameter(battleaudio.model.Config.Trigger.ORB_PASSIVE).any = false;
            passive.parameter(battleaudio.model.Config.Trigger.ORB_PASSIVE).id = "NeverMatches";
            if (orb instanceof com.megacrit.cardcrawl.orbs.Plasma) orb.onStartOfTurn(); else orb.onEndOfTurn();
            assertEvent(battleaudio.model.Config.Trigger.ORB_PASSIVE, before);
            passive.parameter(battleaudio.model.Config.Trigger.ORB_PASSIVE).id = orb.ID;
            if (orb instanceof com.megacrit.cardcrawl.orbs.Plasma) orb.onStartOfTurn(); else orb.onEndOfTurn();
            assertEvent(battleaudio.model.Config.Trigger.ORB_PASSIVE, before + 1);
            orb.updateDescription(); orb.updateAnimation();
            if (orb instanceof com.megacrit.cardcrawl.orbs.Plasma) orb.onEndOfTurn(); else orb.onStartOfTurn();
            assertEvent(battleaudio.model.Config.Trigger.ORB_PASSIVE, before + 1);
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
        }
        System.out.println("PASS: actual multi-dark accumulations, arbitrary gain, zero gain, stable observations and all four passive filters");
        passive.parameter(battleaudio.model.Config.Trigger.ORB_PASSIVE).any=true;
        passive.retriggerMode=battleaudio.model.Config.RetriggerMode.REPLACE;
        eventAudio.stopAll(); p.powers.clear(); p.orbs.clear();
        for(int i=0;i<3;i++) p.orbs.add(new com.megacrit.cardcrawl.orbs.Lightning());
        for(float gain:new float[]{.1f,0f,.6f}) {
            passive.audio().volume=gain;
            int count=eventCount(battleaudio.model.Config.Trigger.ORB_PASSIVE);
            runAction(new com.megacrit.cardcrawl.actions.defect.TriggerEndOfTurnOrbsAction());
            assertEvent(battleaudio.model.Config.Trigger.ORB_PASSIVE,count+3);
            if(eventAudio.count()!=1 || Math.abs(eventTracks.get(eventTracks.size()-1).getVolume()-gain)>.0001f) throw new AssertionError("End-turn passive ignores candidate gain or stacks despite replace");
            p.orbs.get(0).onEndOfTurn();
            if(eventAudio.count()!=1 || Math.abs(eventTracks.get(eventTracks.size()-1).getVolume()-gain)>.0001f) throw new AssertionError("Mid-turn passive has different gain");
        eventAudio.stopAll(); com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.clear();
        }
        passive.audio().volume=0; passive.retriggerMode=battleaudio.model.Config.RetriggerMode.OVERLAP;
        System.out.println("PASS: real end-turn 3-lightning action and mid-turn passive share gain; .1/0/.6 respected, replacement keeps one active instance");
    }
    private void screenOpenChecks() {
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.previousScreen = null;
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.closeCurrentScreen();
        com.megacrit.cardcrawl.shop.ShopScreen shop = com.megacrit.cardcrawl.dungeons.AbstractDungeon.shopScreen;
        java.util.ArrayList<com.megacrit.cardcrawl.cards.AbstractCard> stock = new java.util.ArrayList<>(), colorless = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) stock.add(new com.megacrit.cardcrawl.cards.red.Anger());
        for (int i = 0; i < 2; i++) colorless.add(new com.megacrit.cardcrawl.cards.colorless.Finesse());
        shop.init(stock, colorless);
        shop.open(); shop.open();
        assertEvent(battleaudio.model.Config.Trigger.SHOP_OPEN, 1);
        com.megacrit.cardcrawl.characters.AbstractPlayer shopper=com.megacrit.cardcrawl.dungeons.AbstractDungeon.player;
        int oldGold=shopper.gold;
        try {
            stock.get(0).price=50;
            java.lang.reflect.Method purchase=com.megacrit.cardcrawl.shop.ShopScreen.class.getDeclaredMethod(
                    "purchaseCard",com.megacrit.cardcrawl.cards.AbstractCard.class);
            purchase.setAccessible(true);
            shopper.gold=0; purchase.invoke(shop,stock.get(0));
            assertEvent(battleaudio.model.Config.Trigger.SHOP_BUY_CARD,0);
            shopper.gold=999; purchase.invoke(shop,stock.get(0));
            assertEvent(battleaudio.model.Config.Trigger.SHOP_BUY_CARD,1);
            if(actualEvents.getOrDefault("FILTERED_SHOP_BUY_CARD",0)!=1)
                throw new AssertionError("Completed shop card purchase missed card ID filter");
        } catch(Exception ex) { throw new RuntimeException(ex); }
        shopper.gold=oldGold;
        com.megacrit.cardcrawl.shop.StoreRelic storeRelic=new com.megacrit.cardcrawl.shop.StoreRelic(
                new com.megacrit.cardcrawl.relics.Anchor(),100,shop);
        shopper.gold=0; storeRelic.purchaseRelic();
        assertEvent(battleaudio.model.Config.Trigger.SHOP_BUY_RELIC,0);
        shopper.gold=999; storeRelic.purchaseRelic();
        assertEvent(battleaudio.model.Config.Trigger.SHOP_BUY_RELIC,1);
        if(actualEvents.getOrDefault("FILTERED_SHOP_BUY_RELIC",0)!=1)
            throw new AssertionError("Successful shop relic purchase missed ID filter");
        java.util.ArrayList<com.megacrit.cardcrawl.potions.AbstractPotion> oldPotions=new java.util.ArrayList<>(shopper.potions);
        int oldSlots=shopper.potionSlots;
        com.megacrit.cardcrawl.shop.StorePotion storePotion=new com.megacrit.cardcrawl.shop.StorePotion(
                new com.megacrit.cardcrawl.potions.FirePotion(),100,shop);
        shopper.potions.clear(); shopper.potions.add(new com.megacrit.cardcrawl.potions.PotionSlot(0)); shopper.potionSlots=1;
        shopper.gold=0; storePotion.purchasePotion();
        assertEvent(battleaudio.model.Config.Trigger.SHOP_BUY_POTION,0);
        shopper.gold=999; storePotion.purchasePotion();
        assertEvent(battleaudio.model.Config.Trigger.SHOP_BUY_POTION,1);
        if(actualEvents.getOrDefault("FILTERED_SHOP_BUY_POTION",0)!=1)
            throw new AssertionError("Successful shop potion purchase missed ID filter");
        shopper.potions.clear(); shopper.potions.addAll(oldPotions); shopper.potionSlots=oldSlots;
        shopper.gold=oldGold;
        com.megacrit.cardcrawl.cards.AbstractCard purgeTarget=new com.megacrit.cardcrawl.cards.red.Anger();
        shopper.masterDeck.addToTop(purgeTarget);
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen.selectedCards.clear();
        shop.updatePurge(); assertEvent(battleaudio.model.Config.Trigger.SHOP_REMOVE_CARD,0);
        com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen.selectedCards.add(purgeTarget);
        shop.updatePurge(); assertEvent(battleaudio.model.Config.Trigger.SHOP_REMOVE_CARD,1);
        if(shopper.masterDeck.group.contains(purgeTarget) || actualEvents.getOrDefault("FILTERED_SHOP_REMOVE_CARD",0)!=1)
            throw new AssertionError("Shop purge did not remove selected card or match its ID");
        shop.updatePurge(); assertEvent(battleaudio.model.Config.Trigger.SHOP_REMOVE_CARD,1);
        com.badlogic.gdx.graphics.g2d.SpriteBatch batch = new com.badlogic.gdx.graphics.g2d.SpriteBatch();
        try {
            for (int i = 0; i < 3; i++) { batch.begin(); shop.render(batch); batch.end(); }
            assertEvent(battleaudio.model.Config.Trigger.SHOP_OPEN, 1);
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.closeCurrentScreen(); shop.open();
            assertEvent(battleaudio.model.Config.Trigger.SHOP_OPEN, 2);
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.closeCurrentScreen();
            com.megacrit.cardcrawl.rooms.AbstractRoom room = com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom();
            com.megacrit.cardcrawl.rewards.RewardItem reward = new com.megacrit.cardcrawl.rewards.RewardItem(0);
            reward.type = com.megacrit.cardcrawl.rewards.RewardItem.RewardType.CARD;
            reward.cards = new java.util.ArrayList<>();
            reward.cards.add(new com.megacrit.cardcrawl.cards.red.Strike_Red()); reward.cards.add(new com.megacrit.cardcrawl.cards.red.Defend_Red());
            room.rewards.add(reward); com.megacrit.cardcrawl.dungeons.AbstractDungeon.combatRewardScreen.rewards.add(reward);
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.combatRewardScreen.open();
            assertEvent(battleaudio.model.Config.Trigger.COMBAT_CARD_REWARD_OPEN, 0);
            reward.claimReward();
            com.megacrit.cardcrawl.screens.CardRewardScreen cards = com.megacrit.cardcrawl.dungeons.AbstractDungeon.cardRewardScreen;
            cards.open(reward.cards, reward, "smoke"); cards.reopen();
            for (int i = 0; i < 3; i++) { batch.begin(); cards.render(batch); batch.end(); }
            assertEvent(battleaudio.model.Config.Trigger.COMBAT_CARD_REWARD_OPEN, 1);
            try {
                java.lang.reflect.Method acquire=com.megacrit.cardcrawl.screens.CardRewardScreen.class.getDeclaredMethod(
                        "acquireCard",com.megacrit.cardcrawl.cards.AbstractCard.class);
                acquire.setAccessible(true); acquire.invoke(cards,reward.cards.get(0)); acquire.invoke(cards,reward.cards.get(0));
            } catch(Exception ex) { throw new RuntimeException(ex); }
            assertEvent(battleaudio.model.Config.Trigger.COMBAT_CARD_REWARD_PICK,1);
            cards.skippedCards();
            assertEvent(battleaudio.model.Config.Trigger.COMBAT_CARD_REWARD_SKIP,0);
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.previousScreen = null;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.closeCurrentScreen(); cards.reopen();
            assertEvent(battleaudio.model.Config.Trigger.COMBAT_CARD_REWARD_OPEN, 2);
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.closeCurrentScreen();
            cards.chooseOneOpen(reward.cards);
            assertEvent(battleaudio.model.Config.Trigger.COMBAT_CARD_REWARD_OPEN, 2);
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.closeCurrentScreen();
            boolean ended = room.isBattleOver; room.isBattleOver = false;
            cards.open(reward.cards, reward, "not finished");
            assertEvent(battleaudio.model.Config.Trigger.COMBAT_CARD_REWARD_OPEN, 2);
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.closeCurrentScreen(); room.isBattleOver = ended;
            com.megacrit.cardcrawl.rooms.AbstractRoom previous = com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room;
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room = new com.megacrit.cardcrawl.rooms.EventRoom();
            cards.open(reward.cards, reward, "event reward");
            assertEvent(battleaudio.model.Config.Trigger.COMBAT_CARD_REWARD_OPEN, 2);
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.closeCurrentScreen();
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.currMapNode.room = previous;
            com.megacrit.cardcrawl.rewards.RewardItem skipReward=new com.megacrit.cardcrawl.rewards.RewardItem(0);
            skipReward.type=com.megacrit.cardcrawl.rewards.RewardItem.RewardType.CARD;
            skipReward.cards=new java.util.ArrayList<>(reward.cards);
            cards.open(skipReward.cards,skipReward,"skip smoke");
            cards.skippedCards(); cards.skippedCards();
            assertEvent(battleaudio.model.Config.Trigger.COMBAT_CARD_REWARD_SKIP,1);
        } finally { batch.dispose(); }
        System.out.println("PASS: actual shop/reward open, close/reopen, duplicate open/render suppression and non-combat selection exclusion");
    }
    private void victoryChecks(battleaudio.core.RuleEngine engine, com.megacrit.cardcrawl.characters.AbstractPlayer p,
            com.megacrit.cardcrawl.monsters.AbstractMonster monster) {
        com.megacrit.cardcrawl.rooms.AbstractRoom[] rooms = {new com.megacrit.cardcrawl.rooms.MonsterRoomElite(), new com.megacrit.cardcrawl.rooms.MonsterRoomBoss()};
        for (com.megacrit.cardcrawl.rooms.AbstractRoom room : rooms) {
            room.monsters = new com.megacrit.cardcrawl.monsters.MonsterGroup(monster); room.isBattleOver = true;
            engine.battleStart("victory" + room.getClass().getSimpleName(), room instanceof com.megacrit.cardcrawl.rooms.MonsterRoomBoss,
                    room instanceof com.megacrit.cardcrawl.rooms.MonsterRoomElite, p.currentHealth, p.maxHealth);
            assertEvent(room instanceof com.megacrit.cardcrawl.rooms.MonsterRoomBoss
                    ? battleaudio.model.Config.Trigger.BOSS_START : battleaudio.model.Config.Trigger.ELITE_START, 1);
            BattleAudioMod.instance.battleResult(room); BattleAudioMod.instance.battleResult(room);
        }
        assertEvent(battleaudio.model.Config.Trigger.ELITE_VICTORY, 1); assertEvent(battleaudio.model.Config.Trigger.BOSS_VICTORY, 1);
        int victories = eventCount(battleaudio.model.Config.Trigger.BATTLE_VICTORY);
        com.megacrit.cardcrawl.rooms.MonsterRoom room = new com.megacrit.cardcrawl.rooms.MonsterRoom();
        room.monsters = new com.megacrit.cardcrawl.monsters.MonsterGroup(monster); room.isBattleOver = true;
        for (int failure = 0; failure < 4; failure++) {
            engine.battleStart("loss" + failure, false, false, p.currentHealth, p.maxHealth);
            room.smoked = failure == 0; monster.escaped = failure == 1; p.isDead = failure == 2; room.isBattleOver = failure != 3;
            BattleAudioMod.instance.battleResult(room);
            if (eventCount(battleaudio.model.Config.Trigger.BATTLE_VICTORY) != victories) throw new AssertionError("Escape/death/unfinished room mistaken for victory");
        }
        room.smoked = false; monster.escaped = false; p.isDead = false;
        System.out.println("PASS: actual ordinary endBattle and elite/Boss result fixtures; escape, death and room departure never win; victory delivered once");
    }
    private void enterText(String text) {
        com.badlogic.gdx.scenes.scene2d.ui.TextField field = getStage().getRoot().findActor("audio-name");
        getStage().setKeyboardFocus(field);
        field.selectAll();
        for (char character : text.toCharArray()) getStage().keyTyped(character);
        if (!text.equals(field.getText())) throw new AssertionError("Game text input lost characters: " + field.getText());
    }
    private void finish() {
        try { Files.write(Paths.get("smoke-passed.txt"), "PASS: real game initialized, save callback, game settings and combat scenario\n".getBytes("UTF-8")); }
        catch (Exception ex) { throw new RuntimeException(ex); }
        Gdx.app.exit();
    }
    private static int[] viewportState() {
        java.nio.IntBuffer value=com.badlogic.gdx.utils.BufferUtils.newIntBuffer(16);
        Gdx.gl.glGetIntegerv(com.badlogic.gdx.graphics.GL20.GL_VIEWPORT,value);
        return new int[] {value.get(0),value.get(1),value.get(2),value.get(3)};
    }
    private static void requireDisplayState(int[] viewport,int width,int height,boolean fullscreen,String step) {
        int[] current=viewportState();
        if(!java.util.Arrays.equals(viewport,current) || Gdx.graphics.getWidth()!=width
                || Gdx.graphics.getHeight()!=height || Gdx.graphics.isFullscreen()!=fullscreen)
            throw new AssertionError(step+" changed display state: "+java.util.Arrays.toString(current));
    }
    public void receivePostRender(com.badlogic.gdx.graphics.g2d.SpriteBatch batch) {
        if (!screenshot) return;
        screenshot = false;
        saveScreenshot(phase==50 ? "style-config-editor.png" : phase==51 ? "style-config-files.png" : phase==52 ? "style-overlay-editor.png" : phase==53 ? "style-overlay-files.png" : phase==40 ? "settings-empty-rule.png" : phase==42 ? "settings-candidate-actions-config.png" : phase==44 ? "settings-candidate-actions-overlay.png" : phase==20 ? "settings-inline-config.png" : phase==21 ? "settings-inline-overlay.png" : phase == 3 ? "settings-combat.png" : phase == 6 ? "settings-browser.png" : phase == 8 ? (phaseFrames == 45 ? "settings-config-playback.png" : "settings-config.png") : "settings-menu.png");
    }
    private void saveScreenshot(String name) {
        com.badlogic.gdx.graphics.Pixmap pixels = com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixmap(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        com.badlogic.gdx.graphics.PixmapIO.PNG writer = new com.badlogic.gdx.graphics.PixmapIO.PNG();
        writer.setFlipY(true);
        try { writer.write(Gdx.files.local(name), pixels); }
        catch (Exception ex) { throw new RuntimeException(ex); }
        writer.dispose();
        pixels.dispose();
    }
}
