package battleaudio;

import battleaudio.localization.I18n;

import basemod.BaseMod;
import basemod.ModPanel;
import basemod.TopPanelItem;
import basemod.abstracts.CustomSavable;
import basemod.interfaces.*;
import battleaudio.audio.BackgroundMusic;
import battleaudio.audio.PlaybackManager;
import battleaudio.core.RuleEngine;
import battleaudio.core.GameEvents;
import battleaudio.model.Config;
import battleaudio.model.Config.Rule;
import battleaudio.storage.AudioLibrary;
import battleaudio.storage.GameStorage;
import battleaudio.ui.SettingsScreen;
import battleaudio.ui.AudioIcon;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@SpireInitializer
public final class BattleAudioMod implements PostInitializeSubscriber, PreUpdateSubscriber,
        PostUpdateSubscriber, PostRenderSubscriber, StartGameSubscriber, OnStartBattleSubscriber, PostBattleSubscriber,
        PostPotionUseSubscriber {
    public static BattleAudioMod instance;
    private static final Logger LOG = LogManager.getLogger("BattleAudio");
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private AudioLibrary library;
    private Config config;
    private PlaybackManager playback;
    private RuleEngine engine;
    private GameEvents events;
    private volatile boolean settingsOpen;
    private boolean run;
    private boolean restoreBattled;

    private String startupError;
    private String startupWarning;
    private SettingsScreen settings;
    private ModPanel settingsPanel;
    private boolean embeddedSettings;
    private boolean pendingRun;
    private Set<String> lastCaches = new HashSet<>();
    private int roomSerial;
    private AbstractRoom room;
    private final Map<com.megacrit.cardcrawl.cards.AbstractCard, Integer> upgrades = new IdentityHashMap<>();

    public static void initialize() { instance = new BattleAudioMod(); }

    public BattleAudioMod() {
        BaseMod.subscribe(this);
        try {
            String customHome = System.getProperty("battleaudio.home");
            Path root = customHome != null ? Paths.get(customHome) : GameStorage.prepare(gameDirectory(),
                    Paths.get(System.getProperty("user.home"), ".battleaudio"));
            library = new AudioLibrary(root, findFfmpeg(root), true);
            Path workshopContent = workshopContentRoot();
            library.setWorkshopContentRoot(workshopContent);
            config = library.load();
            if (workshopContent != null) {
                AudioLibrary.WorkshopSync sync = library.synchronizeWorkshopPackages(config, workshopContent, Collections.emptySet());
                config = sync.config;
                for (String warning : sync.warnings) LOG.warn(warning);
                if (!sync.warnings.isEmpty()) startupWarning = String.join("\n", sync.warnings);
                else if (sync.installedOrUpdated > 0 || sync.removed > 0)
                    startupWarning = String.format(I18n.locale(), I18n.text("Workshop package sync completed: installed/updated %d, removed %d."), sync.installedOrUpdated, sync.removed);
            } else {
                config = library.markSubscriptionsUnavailable(config, null);
            }
            if (!library.localPackageWarnings().isEmpty()) {
                String warning = String.join("\n", library.localPackageWarnings());
                startupWarning = startupWarning == null ? warning : startupWarning + "\n" + warning;
            }
            playback = new PlaybackManager(library, error -> LOG.warn(error));
            engine = new RuleEngine(config, playback);
            events = new GameEvents(engine);
        } catch (Exception ex) { startupError = ex.getMessage(); LOG.error("音频规则初始化失败", ex); }
        BaseMod.addSaveField("battleaudio:firstBattle", new CustomSavable<Boolean>() {
            @Override public Boolean onSave() { return engine != null && engine.hasBattled(); }
            @Override public void onLoad(Boolean value) {
                restoreBattled = Boolean.TRUE.equals(value) || (value == null && AbstractDungeon.floorNum > 1);
                pendingRun = true;
            }
        });
    }

    private Path gameDirectory() {
        String gameJar = com.evacipated.cardcrawl.modthespire.Loader.STS_JAR;
        if (gameJar != null && !gameJar.trim().isEmpty()) {
            Path parent = Paths.get(gameJar).toAbsolutePath().normalize().getParent();
            if (parent != null) return parent;
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private Path workshopContentRoot() {
        String override = System.getProperty("battleaudio.workshopContent");
        if (override != null && !override.trim().isEmpty()) return Paths.get(override).toAbsolutePath().normalize();
        List<Path> starts = new ArrayList<>();
        try { starts.add(Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()); } catch (Exception ignored) { }
        try {
            Path code = Paths.get(CardCrawlGame.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            starts.add(Files.isDirectory(code) ? code : code.getParent());
        } catch (Exception ignored) { }
        for (Path start : starts) {
            Path cursor = start;
            while (cursor != null) {
                if ("common".equalsIgnoreCase(String.valueOf(cursor.getFileName()))
                        && cursor.getParent() != null && "steamapps".equalsIgnoreCase(String.valueOf(cursor.getParent().getFileName())))
                    return cursor.getParent().resolve("workshop").resolve("content").resolve("646570");
                cursor = cursor.getParent();
            }
        }
        return null;
    }

    private Path findFfmpeg(Path root) throws Exception {
        String override = System.getProperty("battleaudio.ffmpeg");
        if (override != null) return Paths.get(override);
        try (java.io.InputStream input = getClass().getResourceAsStream("/battleaudio/bin/ffmpeg.exe")) {
            if (input != null) {
                Path bin = root.resolve("bin");
                Files.createDirectories(bin);
                Path executable = bin.resolve("ffmpeg-0.1.0.exe");
                if (!Files.exists(executable)) Files.copy(input, executable);
                return executable;
            }
        }
        String path = System.getenv("PATH");
        return decoderOnPath(path);
    }

    static Path decoderOnPath(String path) {
        if(path!=null) for(String entry:path.split(java.io.File.pathSeparator)) {
            String directory=entry.trim();
            if(directory.isEmpty()) continue;
            if(directory.startsWith("\"") && directory.endsWith("\"") && directory.length()>1)
                directory=directory.substring(1,directory.length()-1);
            try {
                Path candidate=Paths.get(directory).resolve("ffmpeg.exe");
                if(Files.isRegularFile(candidate)) return candidate;
            } catch(InvalidPathException ignored) {
                // A malformed unrelated PATH entry must not disable the whole mod.
            }
        }
        return null;
    }

    @Override public void receivePostInitialize() {
        settingsPanel = new ModPanel() {
            @Override public void update() {
                if (!isUp) return;
                if (!BaseMod.modSettingsUp) {
                    super.update();
                    if (settingsOpen && embeddedSettings) settings.close();
                    return;
                }
                if (!settingsOpen) openSettings(true);
            }
            // The shared overlay renders last, above the Mods screen's native cancel button.
            @Override public void render(com.badlogic.gdx.graphics.g2d.SpriteBatch batch) { }
        };
        for (com.evacipated.cardcrawl.modthespire.ModInfo info : com.evacipated.cardcrawl.modthespire.Loader.MODINFOS) {
            if ("battleaudio".equals(info.ID)) {
                info.Name=I18n.text("更好的自定义音频触发");
                info.Description=I18n.text("内置战斗音频预设，并支持自定义血量、战斗与强化触发规则。");
            }
        }
        BaseMod.registerModBadge(AudioIcon.badgeTexture(), I18n.text("更好的自定义音频触发"), "Battle Audio", "", settingsPanel);
        BaseMod.addTopPanelItem(new TopPanelItem(AudioIcon.texture(), "battleaudio:settings") {
            @Override protected void onClick() { openSettings(); }
        });
    }

    public boolean settingsOpen() { return settingsOpen; }
    public boolean pauseMenu() { return settingsOpen; }

    public void openSettings() {
        openSettings(false);
    }

    private void openSettings(boolean embedded) {
        if (settingsOpen) return;
        embeddedSettings = embedded;
        settings = new SettingsScreen(library, config == null ? new Config() : config, startupError, new SettingsScreen.Host() {
            public void preview(Rule rule, float ratio) {
                playback.stopPreview(); rule.audio().backgroundRatio = ratio; playback.start(rule, "preview");
            }
            public void stopPreview() { if (playback != null) playback.stopPreview(); }
            public void stopAll() { stopAudio(); }
            public void save(Config next) throws Exception {
                library.save(next); config = next; engine.configure(next);
            }
            public void apply(Config next) { config = next; engine.configure(next); }
            public void applyPackageToggle(Config next, String packId) {
                config = next; engine.configurePackageToggle(next, packId);
            }
            public void savePackageToggle(Config next, String packId) throws Exception {
                library.save(next); config = next; engine.configurePackageToggle(next, packId);
            }
            public void deletePackage(Config next, String packId, String localFolder) throws Exception {
                library.deletePackage(next, packId, localFolder); config = next; engine.configure(next);
            }
            public void persist(Config next) throws Exception { library.save(next); }
            public void persistenceCompleted(Config next) { config = next; }
            public Config copyPackage(Config current, String packId) throws Exception { return library.copyPackage(current, packId); }
            public Config copyPackage(Config current, String packId, String suffix) throws Exception { return library.copyPackage(current, packId, suffix); }
            public java.nio.file.Path exportPackage(Config current, String packId) throws Exception {
                return library.exportPackageForWorkshop(current, packId);
            }
            public void closed() { settingsOpen = false; if (embeddedSettings) { BaseMod.modSettingsUp = false; settingsPanel.update(); } battleaudio.patches.GamePatches.clearClicks(); if (library != null) collect(); }
        }, false);
        settingsOpen = true;
        settings.open();
        if (startupWarning != null) { settings.report(startupWarning); startupWarning = null; }
        battleaudio.patches.GamePatches.clearClicks();
    }

    @Override public void receivePostRender(com.badlogic.gdx.graphics.g2d.SpriteBatch batch) {
        if (settingsOpen && settings != null) {
            settings.render(batch);
            if (CardCrawlGame.cursor != null) CardCrawlGame.cursor.render(batch);
        }
    }

    @Override public void receivePreUpdate() {
        Runnable command;
        while ((command = queue.poll()) != null) command.run();
        if (!BaseMod.modSettingsUp && Gdx.input.isKeyJustPressed(Input.Keys.F10)) openSettings();
    }

    @Override public void receivePostUpdate() {
        if (settingsOpen && settings != null) settings.update();
        if (engine == null) return;
        // isInARun() becomes false on death, while the death screen still belongs to gameplay.
        // Keep observing death and preserve its audio until gameplay actually ends.
        boolean nowRun = CardCrawlGame.mode == CardCrawlGame.GameMode.GAMEPLAY && AbstractDungeon.player != null;
        boolean roomReady = currentRoom() != null;
        if (nowRun && roomReady && (!run || pendingRun)) startRun(restoreBattled);
        if (!nowRun && run) { engine.menu(); run = false; room = null; upgrades.clear(); }
        if (nowRun && run && roomReady) {
            syncRoom();
            engine.health(AbstractDungeon.player.currentHealth, AbstractDungeon.player.maxHealth, false);
            observeUpgrades();
            if (!settingsOpen) events.observe(false);
            if (engine.inBattle() && room != null && room.isBattleOver) battleResult(room);
        }
        playback.tick();
        Set<String> currentCaches = playback.activeCaches();
        if (!settingsOpen && !lastCaches.equals(currentCaches)) collect();
        lastCaches = currentCaches;
        Set<String> failed = playback.failedCaches();
        for (Rule rule : config.rules) for (Config.Candidate candidate : rule.candidates)
            if (failed.contains(candidate.cachePath)) candidate.error = AudioLibrary.DECODE_ERROR;

        BackgroundMusic.update(Gdx.graphics.getDeltaTime());
    }

    @Override public void receiveStartGame() {
        if (!CardCrawlGame.loadingSave) restoreBattled = false;
        pendingRun = true;
    }

    private void startRun(boolean hasBattled) {
        battleaudio.patches.VerifiedEventPatches.reset();
        pendingRun = false;
        run = true;
        room = null;
        upgrades.clear();
        engine.beginRun(hasBattled, AbstractDungeon.player.currentHealth, AbstractDungeon.player.maxHealth);
        syncRoom();
        baselineUpgrades();
        events.baseline();
    }

    public void syncRoom() {
        if (!run) return;
        AbstractRoom next = currentRoom();
        if (next == null) return;
        if (next != room) {
            room = next;
            engine.scene("room-" + (++roomSerial));
            if (events != null) events.baseline();
        }
    }

    private AbstractRoom currentRoom() {
        return AbstractDungeon.currMapNode == null ? null : AbstractDungeon.currMapNode.room;
    }

    @Override public void receiveOnBattleStart(AbstractRoom room) {
        if (engine == null) return;
        if (AbstractDungeon.player == null) return;
        if (!run || pendingRun) startRun(restoreBattled);
        syncRoom();
        engine.battleStart("room-" + roomSerial, room instanceof MonsterRoomBoss, room instanceof MonsterRoomElite, normalRoom(room),
                AbstractDungeon.player.currentHealth, AbstractDungeon.player.maxHealth);
        events.baseline();
    }

    private boolean normalRoom(AbstractRoom room) {
        return room instanceof MonsterRoom && !(room instanceof MonsterRoomBoss) && !(room instanceof MonsterRoomElite);
    }
    public void battleResult(AbstractRoom room) {
        if (engine == null || !engine.inBattle() || room == null || AbstractDungeon.player == null) return;
        boolean won = room.isBattleOver && !room.smoked && !room.cannotLose
                && !AbstractDungeon.player.isDead && !AbstractDungeon.player.isDying && AbstractDungeon.player.currentHealth > 0
                && room.monsters != null && !room.monsters.monsters.isEmpty()
                && room.monsters.monsters.stream().allMatch(monster -> !monster.halfDead
                    && (monster.escaped || monster.isDead || monster.isDying || monster.currentHealth <= 0))
                && room.monsters.monsters.stream().anyMatch(monster -> !monster.escaped
                    && (monster.isDead || monster.isDying || monster.currentHealth <= 0));
        if (won) engine.victory(room instanceof MonsterRoomBoss, room instanceof MonsterRoomElite, normalRoom(room));
        else engine.battleEnd();
    }
    @Override public void receivePostBattle(AbstractRoom room) { battleResult(room); }
    @Override public void receivePostPotionUse(com.megacrit.cardcrawl.potions.AbstractPotion potion) {
        gameEvent(receiver -> receiver.potionUsed(potion));
    }
    public void stopAudio() {
        if (engine != null) engine.stop(); else if (playback != null) playback.stopAll();

    }
    public void event(Config.Trigger trigger, String id) {
        if (engine != null && run && !settingsOpen) { syncRoom(); engine.event(trigger, id); }
    }
    public void gameEvent(java.util.function.Consumer<GameEvents> callback) {
        if (events != null && run && !settingsOpen && AbstractDungeon.player != null) { syncRoom(); callback.accept(events); }
    }
    public void observeEvents() { gameEvent(receiver -> receiver.observe(false)); }

    public void healthChanged(boolean heal) {
        if (engine == null || !run || AbstractDungeon.player == null) return;
        syncRoom();
        engine.health(AbstractDungeon.player.currentHealth, AbstractDungeon.player.maxHealth, heal,
                battleaudio.patches.EventPatches.playerDamageContext());
    }

    public void blockLost() { if (engine != null && run) { syncRoom(); engine.blockLoss(); } }
    public void blockLost(int old, int next) { if (engine != null && run) { syncRoom(); engine.blockLoss(old, next); } }
    public void positivePower(String id) { if (engine != null && run) { syncRoom(); engine.event(Config.Trigger.POSITIVE_POWER, id); } }

    private List<com.megacrit.cardcrawl.cards.AbstractCard> ownedCards() {
        List<com.megacrit.cardcrawl.cards.AbstractCard> result = new ArrayList<>();
        result.addAll(AbstractDungeon.player.masterDeck.group);
        result.addAll(AbstractDungeon.player.hand.group);
        result.addAll(AbstractDungeon.player.drawPile.group);
        result.addAll(AbstractDungeon.player.discardPile.group);
        result.addAll(AbstractDungeon.player.exhaustPile.group);
        result.addAll(AbstractDungeon.player.limbo.group);
        return result;
    }

    private void baselineUpgrades() {
        for (com.megacrit.cardcrawl.cards.AbstractCard card : ownedCards()) upgrades.put(card, card.timesUpgraded);
    }

    private void observeUpgrades() {
        Set<com.megacrit.cardcrawl.cards.AbstractCard> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (com.megacrit.cardcrawl.cards.AbstractCard card : ownedCards()) {
            if (!seen.add(card)) continue;
            Integer before = upgrades.put(card, card.timesUpgraded);
            if (before != null) for (int i = before; i < card.timesUpgraded; i++) engine.event(Config.Trigger.CARD_UPGRADE, null);
        }
        upgrades.keySet().retainAll(seen);
    }

    private void collect() {
        try { library.collect(config, playback.activeCaches()); }
        catch (Exception ex) { LOG.warn("音频缓存清理失败", ex); }
    }

    public void shutdown() {
        if (settings != null) settings.dispose();
        if (playback != null) playback.stopAll();
        BackgroundMusic.ratio(1);
    }
}
