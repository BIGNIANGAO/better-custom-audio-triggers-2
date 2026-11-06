package battleaudio.ui;

import battleaudio.model.Config;
import battleaudio.model.IdSelection;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.*;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import java.util.*;
import java.util.function.Consumer;

/** Real game UI checks, including contaminated registries, input, persistence and layout. */
public final class FilterUiSmoke {
    private static int runs;
    public static void run(SettingsScreen screen) {
        try {
            Stage stage=(Stage)field(screen,"stage");
            Table root=(Table)field(screen,"root"); root.validate();
            if(root.getWidth()<1500)throw new AssertionError("Config editor still confined to narrow ModPanel bounds");
            if(!battleaudio.BattleAudioMod.instance.pauseMenu())throw new AssertionError("Underlying menu input is not paused");
            vanillaChecks();
            Config config=(Config)field(screen,"draft");
            Config.Rule rule=new Config.Rule(); rule.name="多选测试";
            rule.triggers=EnumSet.of(Config.Trigger.DRAW_CARD);
            Config.EventFilter filter=new Config.EventFilter(); filter.cardId="Strike_R";
            rule.filters.put(Config.Trigger.DRAW_CARD,filter); config.rules.add(rule);
            java.lang.reflect.Field selected=SettingsScreen.class.getDeclaredField("selected");selected.setAccessible(true);selected.set(screen,rule);
            java.lang.reflect.Method list=SettingsScreen.class.getDeclaredMethod("list");list.setAccessible(true);list.invoke(screen);
            Table editor=(Table)field(screen,"editor"); editor.clear();
            java.lang.reflect.Method choice=SettingsScreen.class.getDeclaredMethod("idChoice",String.class,String.class,String.class,boolean.class,java.util.List.class,Consumer.class);
            choice.setAccessible(true);
            choice.invoke(screen,"卡牌","test-card",filter.cardId,true,FilterOptions.cards(),(Consumer<String>)value->filter.cardId=value);
            root.validate(); screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
            if(stage.getRoot().findActor("test-card-popup")!=null)throw new AssertionError("List starts expanded");
            TextField search=stage.getRoot().findActor("test-card-search");
            click(screen,search,.3f);
            if(stage.getRoot().findActor("test-card-popup")==null)throw new AssertionError("Search click did not open popup");
            search.setText("防御"); screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
            CheckBox defend=stage.getRoot().findActor("test-card-option-Defend_R");
            if(defend==null || stage.getRoot().findActor("test-card-option-Strike_R")!=null)throw new AssertionError("Chinese search did not filter choices");
            if(defend.getImage().getX()<=defend.getLabel().getX())throw new AssertionError("Check mark is not to the right of text");
            click(screen,defend,.25f);
            if(!defend.isChecked() || !IdSelection.contains(filter.cardId,"Strike_R") || !IdSelection.contains(filter.cardId,"Defend_R"))
                throw new AssertionError("Clicking label lost existing multi-selection");
            Config persisted=loadSaved(screen,(battleaudio.storage.AudioLibrary)field(screen,"library"));
            String saved=persisted.rules.stream().filter(item->item.id.equals(rule.id)).findFirst().get().filters.get(Config.Trigger.DRAW_CARD).cardId;
            if(!saved.equals(filter.cardId))throw new AssertionError("Multi-selection not auto-saved");
            matchingChecks(rule,config);
            search.setText("打击");
            CheckBox strike=stage.getRoot().findActor("test-card-option-Strike_R");
            if(strike==null || !strike.isChecked())throw new AssertionError("Search lost previous check mark");
            click(screen,strike,.25f);
            if(IdSelection.contains(filter.cardId,"Strike_R") || !IdSelection.contains(filter.cardId,"Defend_R"))throw new AssertionError("Deselect did not preserve other choices");
            click(screen,stage.getRoot().findActor("test-card-toggle"),.5f);
            if(stage.getRoot().findActor("test-card-popup")!=null)throw new AssertionError("Arrow did not collapse popup");
            if(stage.getKeyboardFocus()==search || !search.getMessageText().contains("防御"))throw new AssertionError("Collapsed selection summary is hidden");
            if(!search.getStyle().messageFontColor.equals(search.getStyle().fontColor))throw new AssertionError("Selected summary looks like disabled placeholder text");
            screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());screenshot("filter-collapsed-"+runs+".png");
            click(screen,search,.3f);
            CheckBox restored=stage.getRoot().findActor("test-card-option-Defend_R");
            if(restored==null || !restored.isChecked())throw new AssertionError("Reopening lost selection");
            Actor popup=stage.getRoot().findActor("test-card-popup");
            if(popup.getParent()!=stage.getRoot() || popup.getX()<0 || popup.getY()<0
                    || popup.getRight()>stage.getViewport().getWorldWidth() || popup.getTop()>stage.getViewport().getWorldHeight())
                throw new AssertionError("Popup clipped or outside viewport");
            screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
            screenshot("filter-popup-"+(runs++)+".png");
            com.megacrit.cardcrawl.helpers.input.InputHelper.pressedEscape=true; screen.update();
            if(stage.getRoot().findActor("test-card-popup")!=null || !battleaudio.BattleAudioMod.instance.settingsOpen())throw new AssertionError("Escape should dismiss list before settings");
            click(screen,search,.3f);
            Vector2 outside=stage.stageToScreenCoordinates(new Vector2(40,950));
            stage.touchDown(Math.round(outside.x),Math.round(outside.y),0,0);
            stage.touchUp(Math.round(outside.x),Math.round(outside.y),0,0);
            if(stage.getRoot().findActor("test-card-popup")!=null)throw new AssertionError("Outside click did not dismiss popup");
            editor.clear();
            choice.invoke(screen,"卡牌","test-any","",true,FilterOptions.cards(),(Consumer<String>)value->{});
            root.validate();
            if(textContains(editor,"无需填写") || textContains(editor,"用于兼容"))throw new AssertionError("Unwanted technical guidance remains");
            eventSelectionChecks(screen);
            stopProbabilityChecks(screen);
            pauseRuleChecks(screen);
            prerequisiteRuleChecks(screen);
            candidateBackgroundChecks(screen);
            layoutChecks(screen);
            boundaryChecks(screen);
            encounterAndFadeChecks(screen);
            System.out.println("PASS: full overlay, vanilla-only choices, Chinese search, right checkboxes, multi-select OR, auto-save, deselect, reopen, arrow/outside/Escape dismissal");
        } catch(Exception ex){throw new RuntimeException(ex);}
    }
    private static void encounterAndFadeChecks(SettingsScreen screen) throws Exception {
        Stage stage=(Stage)field(screen,"stage"); Table root=(Table)field(screen,"root");
        Config config=(Config)field(screen,"draft"); Config.Rule rule=new Config.Rule();
        rule.name="战斗自选验收-"+runs; rule.triggers=EnumSet.of(Config.Trigger.BOSS_START,Config.Trigger.ELITE_START);
        Config.Candidate audio=rule.audio(); config.rules.add(rule);
        java.lang.reflect.Field selected=SettingsScreen.class.getDeclaredField("selected");selected.setAccessible(true);selected.set(screen,rule);
        java.lang.reflect.Field category=SettingsScreen.class.getDeclaredField("category");category.setAccessible(true);category.set(screen,battleaudio.model.Events.Category.GENERAL);
        java.lang.reflect.Method list=SettingsScreen.class.getDeclaredMethod("list");list.setAccessible(true);list.invoke(screen);
        java.lang.reflect.Method bind=SettingsScreen.class.getDeclaredMethod("bind",boolean.class);bind.setAccessible(true);bind.invoke(screen,false);root.validate();
        SelectBox<?> boss=stage.getRoot().findActor("encounter-mode-BOSS_START");
        if(boss==null || boss.getSelectedIndex()!=0 || stage.getRoot().findActor("encounter-BOSS_START-search")!=null
                || rule.filters.containsKey(Config.Trigger.BOSS_START))throw new AssertionError("Legacy boss mode is not non-mutating Any: actor="+(boss==null?"null":boss.getSelectedIndex())+" filter="+rule.filters.containsKey(Config.Trigger.BOSS_START)+" search="+(stage.getRoot().findActor("encounter-BOSS_START-search")!=null));
        boss.setSelectedIndex(1);screen.update();root.validate();
        TextField search=stage.getRoot().findActor("encounter-BOSS_START-search");click(screen,search,.3f);search.setText("六火");
        click(screen,stage.getRoot().findActor("encounter-BOSS_START-option-Hexaghost"),.25f);
        search.setText("腐化");click(screen,stage.getRoot().findActor("encounter-BOSS_START-option-The Heart"),.25f);
        if(!rule.filter(Config.Trigger.BOSS_START).encounterIds.equals(Arrays.asList("Hexaghost","The Heart")))
            throw new AssertionError("Boss multi-selection did not retain both battles");
        search.setText("");screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
        Actor encounterPopup=stage.getRoot().findActor("encounter-BOSS_START-popup");
        if(encounterPopup==null || encounterPopup.getHeight()<180)
            throw new AssertionError("Boss selector collapsed near the bottom of the window");
        screenshot("encounter-boss-list-"+runs+".png");
        ScrollPane choices=stage.getRoot().findActor("encounter-BOSS_START-list");
        choices.setScrollY(choices.getMaxY());choices.updateVisualScroll();
        CheckBox heartBox=stage.getRoot().findActor("encounter-BOSS_START-option-The Heart");
        click(screen,heartBox,.25f);
        if(rule.filter(Config.Trigger.BOSS_START).encounterIds.contains("The Heart"))
            throw new AssertionError("Scrolled boss choice could not be deselected");
        if(Math.abs(choices.getScrollY()-choices.getMaxY())>1)
            throw new AssertionError("Boss choice reset the list scroll position");
        click(screen,heartBox,.25f);
        if(!rule.filter(Config.Trigger.BOSS_START).encounterIds.equals(Arrays.asList("Hexaghost","The Heart")))
            throw new AssertionError("Scrolled boss choice could not be selected again");
        SearchMultiSelect.dismiss(stage);boss=stage.getRoot().findActor("encounter-mode-BOSS_START");boss.setSelectedIndex(0);screen.update();
        if(rule.filter(Config.Trigger.BOSS_START).customEncounter || !rule.filter(Config.Trigger.BOSS_START).targetType.isEmpty())
            throw new AssertionError("Switching back to Any did not clear the restrictive marker");
        boss=stage.getRoot().findActor("encounter-mode-BOSS_START");boss.setSelectedIndex(1);screen.update();
        if(rule.filter(Config.Trigger.BOSS_START).encounterIds.size()!=2)throw new AssertionError("Custom selection was lost on mode toggle");
        SelectBox<?> elite=stage.getRoot().findActor("encounter-mode-ELITE_START");elite.setSelectedIndex(1);screen.update();root.validate();
        search=stage.getRoot().findActor("encounter-ELITE_START-search");click(screen,search,.3f);search.setText("乐加维林");
        click(screen,stage.getRoot().findActor("encounter-ELITE_START-option-Lagavulin"),.25f);SearchMultiSelect.dismiss(stage);
        if(!rule.filter(Config.Trigger.ELITE_START).encounterIds.equals(Collections.singletonList("Lagavulin")))
            throw new AssertionError("Elite selection changed the wrong condition");
        click(screen,stage.getRoot().findActor("edit-candidate-"+audio.id),.5f);screen.update();root.validate();
        TextField fadeIn=stage.getRoot().findActor("fade-in-seconds"),fadeOut=stage.getRoot().findActor("fade-out-seconds");
        if(fadeIn==null || fadeOut==null || audio.fadeInSeconds!=0 || audio.fadeOutSeconds!=0)
            throw new AssertionError("Candidate fade inputs or zero defaults missing");
        typeValue(screen,fadeIn,"1.5");typeValue(screen,fadeOut,"2.5");
        if(audio.fadeInSeconds!=1.5f || audio.fadeOutSeconds!=2.5f)throw new AssertionError("Candidate fade inputs do not update audio");
        Config saved=loadSaved(screen,(battleaudio.storage.AudioLibrary)field(screen,"library"));
        if(!((Label)field(screen,"status")).getText().toString().isEmpty())
            throw new AssertionError("Successful fade input save left a stale validation error");
        Config.Rule persisted=saved.rules.stream().filter(item->item.id.equals(rule.id)).findFirst().get();
        if(!persisted.filter(Config.Trigger.BOSS_START).encounterIds.equals(Arrays.asList("Hexaghost","The Heart"))
                || persisted.audio().fadeInSeconds!=1.5f || persisted.audio().fadeOutSeconds!=2.5f)
            throw new AssertionError("Encounter selection or fade inputs did not persist");
        screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());screenshot("encounter-fade-editor-"+runs+".png");
        System.out.println("PASS: boss and elite custom multiselect, Any compatibility, fade controls and persistence");
    }
    public static void encounterCombatViewportChecks(SettingsScreen screen) throws Exception {
        encounterAndFadeChecks(screen);
    }
    private static void stopProbabilityChecks(SettingsScreen screen) throws Exception {
        Config config=(Config)field(screen,"draft");
        Config.Rule rule=new Config.Rule(); rule.name="停止与概率验收";
        rule.low.percent=true; rule.low.value=30; rule.triggers.add(Config.Trigger.LOW_HEALTH);
        rule.playbackMode=Config.PlaybackMode.INFINITE;
        rule.stopConditions.triggers.add(Config.Trigger.HIGH_HEALTH); rule.stopConditions.high.percent=true; rule.stopConditions.high.value=40;
        config.rules.add(rule);
        java.lang.reflect.Field selected=SettingsScreen.class.getDeclaredField("selected"); selected.setAccessible(true); selected.set(screen,rule);
        java.lang.reflect.Method list=SettingsScreen.class.getDeclaredMethod("list"); list.setAccessible(true); list.invoke(screen);
        java.lang.reflect.Method bind=SettingsScreen.class.getDeclaredMethod("bind",boolean.class); bind.setAccessible(true); bind.invoke(screen,false);
        java.lang.reflect.Method modified=SettingsScreen.class.getDeclaredMethod("modified"); modified.setAccessible(true); modified.invoke(screen);
        Stage stage=(Stage)field(screen,"stage"); Table root=(Table)field(screen,"root"); root.validate();
        if(stage.getRoot().findActor("stop-trigger-HEAL_THRESHOLD")!=null)
            throw new AssertionError("Duplicate health stop remains in general stop editor");
        TextField probability=stage.getRoot().findActor("trigger-probability");
        SelectBox<?> random=stage.getRoot().findActor("random-mode");
        Vector2 probabilityPoint=probability.localToStageCoordinates(new Vector2());
        Vector2 randomPoint=random.localToStageCoordinates(new Vector2());
        if(probabilityPoint.y<=randomPoint.y)throw new AssertionError("Probability is not above random mode");
        typeValue(screen,probability,"25");
        battleaudio.storage.AudioLibrary library=(battleaudio.storage.AudioLibrary)field(screen,"library");
        Config.Rule saved=loadSaved(screen,library).rules.stream().filter(item->item.id.equals(rule.id)).findFirst().get();
        if(saved.triggerProbability!=.25f)throw new AssertionError("Probability did not auto-save");
        screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch()); screenshot("trigger-probability-"+runs+".png");
        stage.setKeyboardFocus(probability); probability.setCursorPosition(probability.getText().length()); stage.keyTyped('1');
        if(!probability.getColor().equals(com.badlogic.gdx.graphics.Color.SALMON) || rule.triggerProbability!=.25f)
            throw new AssertionError("Invalid probability not red or changed valid setting");
        typeValue(screen,probability,"50");
        TextField stop=stage.getRoot().findActor("stop-health-threshold");
        typeValue(screen,stop,"30");
        if(!stop.getColor().equals(com.badlogic.gdx.graphics.Color.SALMON) || rule.stopConditions.high.value!=40)
            throw new AssertionError("Equal threshold not red or changed last valid value");
        typeValue(screen,probability,"75");
        saved=loadSaved(screen,library).rules.stream().filter(item->item.id.equals(rule.id)).findFirst().get();
        if(saved.triggerProbability!=.75f || saved.stopConditions.high.value!=40)
            throw new AssertionError("Another edit persisted an invalid health threshold");
        click(screen,stop,.5f); screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
        screenshot("stop-invalid-threshold-"+runs+".png");
        typeValue(screen,stop,"20");
        if(rule.stopConditions.high.value!=40)throw new AssertionError("Lower stop threshold accepted");
        typeValue(screen,stop,"45");
        if(!stop.getColor().equals(com.badlogic.gdx.graphics.Color.WHITE))throw new AssertionError("Correcting stop threshold did not clear red input");
        TextField general=stage.getRoot().findActor("stop-threshold-HIGH_HEALTH");
        bind.invoke(screen,true); root.validate();
        general=stage.getRoot().findActor("stop-threshold-HIGH_HEALTH");
        if(Double.parseDouble(general.getText())!=45)throw new AssertionError("Shortcut and general stop threshold diverged");
        typeValue(screen,general,"50"); bind.invoke(screen,true); root.validate();
        stop=stage.getRoot().findActor("stop-health-threshold");
        if(Double.parseDouble(stop.getText())!=50)throw new AssertionError("General stop change did not update shortcut");
        CheckBox loss=stage.getRoot().findActor("stop-trigger-HP_LOSS");
        click(screen,loss,.25f); screen.update(); root.validate();
        CheckBox amount=stage.getRoot().findActor("stop-min-amount-enabled-HP_LOSS");
        click(screen,amount,.25f); screen.update(); root.validate();
        TextField minimum=stage.getRoot().findActor("stop-min-amount-HP_LOSS"); typeValue(screen,minimum,"10");
        saved=loadSaved(screen,library).rules.stream().filter(item->item.id.equals(rule.id)).findFirst().get();
        if(!saved.stopConditions.triggers.contains(Config.Trigger.HP_LOSS) || saved.stopConditions.filters.get(Config.Trigger.HP_LOSS).minAmount!=10)
            throw new AssertionError("Stop checkbox and independent filter did not auto-save");
        if(rule.filters.containsKey(Config.Trigger.HP_LOSS))throw new AssertionError("Stop filter changed playback filters");
        click(screen,minimum,.5f); screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
        screenshot("stop-conditions-"+runs+".png");
        rule.playbackMode=Config.PlaybackMode.ONCE; bind.invoke(screen,true); root.validate();
        CheckBox expand=stage.getRoot().findActor("stop-conditions-expanded");
        if(expand==null)throw new AssertionError("Single playback cannot expand stop conditions");
        click(screen,expand,.25f); screen.update(); root.validate();
        if(stage.getRoot().findActor("stop-trigger-HP_LOSS")==null)throw new AssertionError("Single playback stop editor did not expand");
        System.out.println("PASS: probability position and auto-save, red invalid inputs, threshold rollback, shared health stop, independent stop filters and single-play editor");
    }
    private static void pauseRuleChecks(SettingsScreen screen) throws Exception {
        Config config=(Config)field(screen,"draft");
        Config.Rule background=new Config.Rule(); background.name="暂停背景-"+runs;
        Config.Rule blocker=new Config.Rule(); blocker.name="关联音效-"+runs;
        config.rules.add(background); config.rules.add(blocker);
        java.lang.reflect.Field selected=SettingsScreen.class.getDeclaredField("selected"); selected.setAccessible(true); selected.set(screen,background);
        java.lang.reflect.Method list=SettingsScreen.class.getDeclaredMethod("list"); list.setAccessible(true); list.invoke(screen);
        java.lang.reflect.Method bind=SettingsScreen.class.getDeclaredMethod("bind",boolean.class); bind.setAccessible(true); bind.invoke(screen,false);
        Stage stage=(Stage)field(screen,"stage"); Table root=(Table)field(screen,"root"); root.validate();
        TextField search=stage.getRoot().findActor("pause-rules-search"); click(screen,search,.3f);
        if(stage.getRoot().findActor("pause-rules-option-"+background.id)!=null)throw new AssertionError("Rule can select itself for pause");
        search.setText(blocker.name);
        CheckBox choice=stage.getRoot().findActor("pause-rules-option-"+blocker.id); click(screen,choice,.25f);
        battleaudio.storage.AudioLibrary library=(battleaudio.storage.AudioLibrary)field(screen,"library");
        Config.Rule saved=loadSaved(screen,library).rules.stream().filter(item->item.id.equals(background.id)).findFirst().get();
        if(!saved.pauseWhenRuleIds.equals(Collections.singletonList(blocker.id)))throw new AssertionError("Pause rule selection did not auto-save");
        screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch()); screenshot("pause-rules-"+runs+".png");
        SearchMultiSelect.dismiss(stage); selected.set(screen,blocker); bind.invoke(screen,false); root.validate();
        search=stage.getRoot().findActor("pause-rules-search"); click(screen,search,.3f); search.setText(background.name);
        choice=stage.getRoot().findActor("pause-rules-option-"+background.id); click(screen,choice,.25f); screen.update(); root.validate();
        if(!blocker.pauseWhenRuleIds.isEmpty())throw new AssertionError("Cyclic pause choice was not rolled back");
        saved=loadSaved(screen,library).rules.stream().filter(item->item.id.equals(blocker.id)).findFirst().get();
        if(!saved.pauseWhenRuleIds.isEmpty())throw new AssertionError("Cyclic pause choice was persisted");
        if(!((Label)field(screen,"status")).getText().toString().contains("循环"))throw new AssertionError("Cyclic pause choice has no explanation");
        deleteSelectedRule(screen);
        if(!background.pauseWhenRuleIds.isEmpty())throw new AssertionError("Deleting selected blocker left dangling pause reference");
        saved=loadSaved(screen,library).rules.stream().filter(item->item.id.equals(background.id)).findFirst().get();
        if(!saved.pauseWhenRuleIds.isEmpty())throw new AssertionError("Deleted blocker reference was persisted");
        System.out.println("PASS: searchable pause rule selection auto-saves, excludes self, rejects cycles and cleans deleted references");
    }
    private static void prerequisiteRuleChecks(SettingsScreen screen) throws Exception {
        Config config=(Config)field(screen,"draft"); Config.Rule dependent=new Config.Rule(), bgm=new Config.Rule();
        dependent.name="前置受伤-"+runs; bgm.name="前置BGM-"+runs; config.rules.add(dependent); config.rules.add(bgm);
        java.lang.reflect.Field selected=SettingsScreen.class.getDeclaredField("selected"); selected.setAccessible(true); selected.set(screen,dependent);
        java.lang.reflect.Method list=SettingsScreen.class.getDeclaredMethod("list"); list.setAccessible(true); list.invoke(screen);
        java.lang.reflect.Method bind=SettingsScreen.class.getDeclaredMethod("bind",boolean.class); bind.setAccessible(true); bind.invoke(screen,false);
        Stage stage=(Stage)field(screen,"stage"); Table root=(Table)field(screen,"root"); root.validate();
        TextField search=stage.getRoot().findActor("prerequisite-rules-search"); click(screen,search,.3f);
        if(stage.getRoot().findActor("prerequisite-rules-option-"+dependent.id)!=null)throw new AssertionError("Prerequisite allows selecting self");
        search.setText(bgm.name); CheckBox option=stage.getRoot().findActor("prerequisite-rules-option-"+bgm.id); click(screen,option,.25f);
        battleaudio.storage.AudioLibrary library=(battleaudio.storage.AudioLibrary)field(screen,"library");
        Config.Rule saved=loadSaved(screen,library).rules.stream().filter(item->item.id.equals(dependent.id)).findFirst().get();
        if(!saved.prerequisiteRuleIds.equals(Collections.singletonList(bgm.id)))throw new AssertionError("Prerequisite selection did not auto-save");
        screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch()); screenshot("prerequisite-rules-"+runs+".png");
        SearchMultiSelect.dismiss(stage); bind.invoke(screen,true); root.validate();
        search=stage.getRoot().findActor("prerequisite-rules-search");
        if(!search.getMessageText().contains(bgm.name))throw new AssertionError("Reopening lost prerequisite selection summary");
        selected.set(screen,bgm); bind.invoke(screen,false); root.validate();
        search=stage.getRoot().findActor("prerequisite-rules-search"); click(screen,search,.3f); search.setText(dependent.name);
        option=stage.getRoot().findActor("prerequisite-rules-option-"+dependent.id); click(screen,option,.25f); screen.update(); root.validate();
        if(!bgm.prerequisiteRuleIds.isEmpty())throw new AssertionError("Unstartable prerequisite cycle was not rolled back");
        saved=loadSaved(screen,library).rules.stream().filter(item->item.id.equals(bgm.id)).findFirst().get();
        if(!saved.prerequisiteRuleIds.isEmpty())throw new AssertionError("Unstartable prerequisite cycle was saved");
        if(!((Label)field(screen,"status")).getText().toString().contains("无法自动启动"))throw new AssertionError("Missing prerequisite cycle explanation");
        deleteSelectedRule(screen);
        saved=loadSaved(screen,library).rules.stream().filter(item->item.id.equals(dependent.id)).findFirst().get();
        if(!saved.prerequisiteRuleIds.isEmpty())throw new AssertionError("Deleted prerequisite left a dangling saved reference");
        System.out.println("PASS: prerequisite rule search, auto-save, reopen, self exclusion, cycle rollback and deleted reference cleanup");
    }
    private static void candidateBackgroundChecks(SettingsScreen screen) throws Exception {
        battleaudio.storage.AudioLibrary library=(battleaudio.storage.AudioLibrary)field(screen,"library");
        Config.Rule rule=new Config.Rule();rule.name="候选背景音乐-"+runs;
        Config.Candidate first=library.importAudio(java.nio.file.Paths.get(System.getProperty("battleaudio.sample"))).audio();first.name="候选一";
        Config.Candidate second=library.importAudio(java.nio.file.Paths.get(System.getProperty("battleaudio.sample"))).audio();second.name="候选二";
        rule.candidates.add(first);rule.candidates.add(second);((Config)field(screen,"draft")).rules.add(rule);
        java.lang.reflect.Field selected=SettingsScreen.class.getDeclaredField("selected");selected.setAccessible(true);selected.set(screen,rule);
        java.lang.reflect.Field expanded=SettingsScreen.class.getDeclaredField("expandedCandidate");expanded.setAccessible(true);expanded.set(screen,first.id);
        java.lang.reflect.Method list=SettingsScreen.class.getDeclaredMethod("list");list.setAccessible(true);list.invoke(screen);
        java.lang.reflect.Method bind=SettingsScreen.class.getDeclaredMethod("bind",boolean.class);bind.setAccessible(true);bind.invoke(screen,false);
        Stage stage=(Stage)field(screen,"stage");Table root=(Table)field(screen,"root");root.validate();
        if(((Table)field(screen,"footer")).findActor("background-ratio")!=null)throw new AssertionError("Global background ratio control remains");
        Slider ratio=stage.getRoot().findActor("background-ratio");ratio.setValue(35);
        Config.Rule saved=loadSaved(screen,library).rules.stream().filter(item->item.id.equals(rule.id)).findFirst().get();
        if(Math.abs(saved.candidates.get(0).backgroundRatio-.35f)>.001f || saved.candidates.get(1).backgroundRatio!=.2f)
            throw new AssertionError("First candidate ratio did not auto-save independently");
        expanded.set(screen,second.id);bind.invoke(screen,true);root.validate();ratio=stage.getRoot().findActor("background-ratio");ratio.setValue(65);
        saved=loadSaved(screen,library).rules.stream().filter(item->item.id.equals(rule.id)).findFirst().get();
        if(Math.abs(saved.candidates.get(0).backgroundRatio-.35f)>.001f || Math.abs(saved.candidates.get(1).backgroundRatio-.65f)>.001f)
            throw new AssertionError("Second candidate ratio overwrote first");
        click(screen,ratio,.65f);Vector2 from=backgroundSliderPoint(stage,ratio,.65f),to=backgroundSliderPoint(stage,ratio,.75f);
        stage.touchDown(Math.round(from.x),Math.round(from.y),0,0);stage.touchDragged(Math.round(to.x),Math.round(to.y),0);
        stage.touchUp(Math.round(to.x),Math.round(to.y),0,0);
        if(Math.abs(ratio.getValue()-75)>3)throw new AssertionError("Candidate background slider drag maps pointer incorrectly");
        saved=loadSaved(screen,library).rules.stream().filter(item->item.id.equals(rule.id)).findFirst().get();
        if(Math.abs(saved.candidates.get(1).backgroundRatio*100-ratio.getValue())>.01f)throw new AssertionError("Dragged candidate background value was not saved");
        screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());screenshot("candidate-background-"+runs+".png");
        float selectedRatio=second.backgroundRatio;
        float gain=org.lwjgl.openal.AL10.alGetListenerf(org.lwjgl.openal.AL10.AL_GAIN);org.lwjgl.openal.AL10.alListenerf(org.lwjgl.openal.AL10.AL_GAIN,0);
        battleaudio.audio.PlaybackManager playback=(battleaudio.audio.PlaybackManager)field(battleaudio.BattleAudioMod.instance,"playback");
        try {
            click(screen,stage.getRoot().findActor("试听"),.5f);battleaudio.audio.BackgroundMusic.update(1);
            if(!playback.hasPreview() || Math.abs(battleaudio.audio.BackgroundMusic.factor()-selectedRatio)>.001f)
                throw new AssertionError("Preview did not use selected candidate background ratio");
        } finally {playback.stopAll();org.lwjgl.openal.AL10.alListenerf(org.lwjgl.openal.AL10.AL_GAIN,gain);battleaudio.audio.BackgroundMusic.update(1);}
        click(screen,stage.getRoot().findActor("复制音频"),.5f);screen.update();
        Config.Candidate copy=rule.candidates.get(rule.candidates.size()-1);
        if(copy.backgroundRatio!=second.backgroundRatio)throw new AssertionError("Candidate copy lost background ratio");
        expanded.set(screen,first.id);bind.invoke(screen,true);root.validate();
        ratio=stage.getRoot().findActor("background-ratio");
        if(Math.abs(ratio.getValue()-35)>.001f)throw new AssertionError("Reopening candidate lost its background ratio");
        System.out.println("PASS: candidate background ratios auto-save independently, actual slider drag, global control removed, preview and copies use chosen candidate");
    }
    private static Vector2 backgroundSliderPoint(Stage stage,Slider slider,float fraction){
        float left=slider.getStyle().background.getLeftWidth(),right=slider.getStyle().background.getRightWidth(),knob=slider.getStyle().knob.getMinWidth();
        return stage.stageToScreenCoordinates(slider.localToStageCoordinates(new Vector2(left+knob/2+fraction*(slider.getWidth()-left-right-knob),slider.getHeight()/2)));
    }
    private static void typeValue(SettingsScreen screen,TextField field,String value) {
        click(screen,field,.5f); Stage stage=field.getStage(); stage.setKeyboardFocus(field);
        field.selectAll(); stage.keyTyped((char)8);
        for(char character:value.toCharArray()) stage.keyTyped(character);
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static void vanillaChecks() throws ReflectiveOperationException {
        // Put fake mod entries into the very same registries used by the old UI.
        com.megacrit.cardcrawl.cards.AbstractCard card=new com.megacrit.cardcrawl.cards.red.Strike_Red();
        com.megacrit.cardcrawl.helpers.CardLibrary.cards.put("seermod:Test",card);
        com.megacrit.cardcrawl.helpers.PotionHelper.potions.add("seermod:Potion");
        String[] kinds={"powers","relics","events","monsters","potions"};
        for(String kind:kinds) {
            java.lang.reflect.Field f=com.megacrit.cardcrawl.localization.LocalizedStrings.class.getDeclaredField(kind);f.setAccessible(true);
            Map map=(Map)f.get(com.megacrit.cardcrawl.core.CardCrawlGame.languagePack);
            map.put("seermod:"+kind,map.values().iterator().next());
        }
        java.util.List<java.util.List<FilterOptions.Option>> groups=Arrays.asList(FilterOptions.cards(),FilterOptions.powers(),FilterOptions.relics(),FilterOptions.events(),FilterOptions.enemies(),FilterOptions.potionLibrary());
        for(java.util.List<FilterOptions.Option> options:groups) {
            if(options.isEmpty())throw new AssertionError("Missing vanilla choices");
            for(FilterOptions.Option option:options)if(option.id.startsWith("seermod:"))throw new AssertionError("Mod entry leaked into choices: "+option.id);
        }
        if(FilterOptions.cards().stream().noneMatch(o->o.id.equals("Alpha") && o.name.equals("阿尔法")))throw new AssertionError("Chinese vanilla card missing");
    }
    private static void matchingChecks(Config.Rule rule,Config config) {
        Config testConfig=config.copy();
        Config.Rule testRule=testConfig.rules.stream().filter(r->r.id.equals(rule.id)).findFirst().get();
        testRule.audio().cachePath="cache/test.wav"; testRule.audio().duration=10; testRule.audio().end=10;
        int[] count={0};
        battleaudio.core.RuleEngine engine=new battleaudio.core.RuleEngine(testConfig,new battleaudio.core.RuleEngine.Playback(){
            public void start(Config.Rule r,String s){if(r.id.equals(rule.id))count[0]++;}
            public void stopScene(String s){} public void stopAll(){}
        });
        engine.scene("test");
        engine.event(Config.Trigger.DRAW_CARD,null,new battleaudio.model.EventContext().card("Strike_R","ATTACK",1,false,false,false));
        engine.event(Config.Trigger.DRAW_CARD,null,new battleaudio.model.EventContext().card("Defend_R","SKILL",1,false,false,false));
        engine.event(Config.Trigger.DRAW_CARD,null,new battleaudio.model.EventContext().card("Strike_Red","ATTACK",1,false,false,false));
        if(count[0]!=2)throw new AssertionError("Both selected IDs must match exactly, starts="+count[0]);
    }
    private static void eventSelectionChecks(SettingsScreen screen) throws Exception {
        Stage stage=(Stage)field(screen,"stage");
        Config config=(Config)field(screen,"draft");
        Config.Rule rule=new Config.Rule();rule.name="事件界面回归";
        rule.triggers=EnumSet.of(Config.Trigger.EVENT_OPEN,Config.Trigger.EVENT_OPTION_COMPLETED);
        rule.filter(Config.Trigger.EVENT_OPEN).sourceId="Big Fish";
        rule.filter(Config.Trigger.EVENT_OPTION_COMPLETED).sourceId="Big Fish";
        rule.filter(Config.Trigger.EVENT_OPTION_COMPLETED).previousState="0";
        config.rules.add(rule);
        java.lang.reflect.Field selected=SettingsScreen.class.getDeclaredField("selected");selected.setAccessible(true);selected.set(screen,rule);
        java.lang.reflect.Field category=SettingsScreen.class.getDeclaredField("category");category.setAccessible(true);category.set(screen,battleaudio.model.Events.Category.GENERAL);
        java.lang.reflect.Method bind=SettingsScreen.class.getDeclaredMethod("bind",boolean.class);bind.setAccessible(true);bind.invoke(screen,false);
        Table editor=(Table)field(screen,"editor");
        ((Table)field(screen,"root")).validate();
        screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
        CheckBox entry=stage.getRoot().findActor("trigger-EVENT_OPEN");
        if(!entry.getText().toString().contains("进入？房间时"))throw new AssertionError("Event entry label is wrong");
        for(Actor actor:editor.getChildren())if(actor instanceof SearchMultiSelect) {
            SearchMultiSelect chooser=(SearchMultiSelect)actor;
            TextField search=chooser.findActor(chooser.getName().replace("-chooser","-search"));
            if(search.getMessageText().contains("中文"))throw new AssertionError("Search hint still contains Chinese qualifier");
            for(Actor sibling:new com.badlogic.gdx.utils.Array<Actor>(editor.getChildren()))if(sibling instanceof Label && Math.abs(sibling.getY()+sibling.getHeight()/2-chooser.getY()-chooser.getHeight()/2)<12) {
                Label label=(Label)sibling;
                if(label.getRight()>chooser.getX()+.5f || label.getPrefHeight()>label.getHeight()+1
                        || !(Boolean)field(label,"wrap") && label.getPrefWidth()>label.getWidth()+1)
                    throw new AssertionError("Event row label is obstructed: "+label.getText()+" width="+label.getWidth()+" prefHeight="+label.getPrefHeight()+" height="+label.getHeight()+" right="+label.getRight()+" inputLeft="+chooser.getX());
            }

        }
        for(Actor actor:editor.getChildren())if(actor instanceof SearchMultiSelect) {
            SearchMultiSelect chooser=(SearchMultiSelect)actor;
            Skin skin=(Skin)field(screen,"skin");
            if(chooser.getBackground()!=skin.get(SelectBox.SelectBoxStyle.class).background)
                throw new AssertionError("Search selector does not share the ordinary dropdown shell");
            Actor toggle=chooser.findActor(chooser.getName().replace("-chooser","-toggle"));
            if(toggle.getRight()<chooser.getWidth()-1)throw new AssertionError("Arrow hit area misses visible dropdown arrow");
        }
        if(textContains(editor,"当前已验证") || textContains(editor,"路径已经接入"))throw new AssertionError("Removed event explanation remains visible");
        ScrollPane pane=(ScrollPane)field(screen,"editorScroll");
        Vector2 point=entry.localToAscendantCoordinates(pane.getWidget(),new Vector2());
        pane.setScrollY(Math.max(0,pane.getWidget().getHeight()-point.y-100));pane.updateVisualScroll();
        screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());screenshot("event-selectors-"+runs+".png");
        SearchMultiSelect options=stage.getRoot().findActor("event-option-chooser");
        click(screen,stage.getRoot().findActor("event-option-toggle"),.65f);
        if(stage.getRoot().findActor("event-option-option-0")==null || stage.getRoot().findActor("event-option-option-1")==null)
            throw new AssertionError("BigFish immediate options were removed with the explanation");
        screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());screenshot("event-options-"+runs+".png");
        click(screen,stage.getRoot().findActor("event-option-toggle"),.65f);
        System.out.println("PASS: event entry wording, explanation removed, both BigFish choices retained, matching dropdown shell, readable labels and arrow hit area");
    }
    private static void layoutChecks(SettingsScreen screen) throws Exception {
        Config.Rule all=new Config.Rule();all.triggers=EnumSet.allOf(Config.Trigger.class);
        java.lang.reflect.Field selected=SettingsScreen.class.getDeclaredField("selected");selected.setAccessible(true);selected.set(screen,all);
        java.lang.reflect.Field category=SettingsScreen.class.getDeclaredField("category");category.setAccessible(true);
        java.lang.reflect.Method bind=SettingsScreen.class.getDeclaredMethod("bind",boolean.class);bind.setAccessible(true);
        Stage stage=(Stage)field(screen,"stage");
        for(battleaudio.model.Events.Category value:battleaudio.model.Events.Category.values()) {
            category.set(screen,value);bind.invoke(screen,false);
            screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
            ScrollPane pane=(ScrollPane)field(screen,"editorScroll");Table editor=(Table)field(screen,"editor");
            if(editor.getWidth()>pane.getWidth()+1)throw new AssertionError("Editor overflows in category "+value+": "+editor.getWidth()+" > "+pane.getWidth());
            for(Actor actor:editor.getChildren()) if(actor instanceof SelectBox || actor instanceof TextField || actor instanceof SearchMultiSelect) {
                if(actor.getX()<0 || actor.getRight()>editor.getWidth()+1)throw new AssertionError("Control overflows in category "+value);
            }
            pane.setScrollY(0);pane.updateVisualScroll();
            screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());screenshot("trigger-order-"+value.name()+"-"+runs+".png");
            if(value==battleaudio.model.Events.Category.DEFECT) {
                pane.setScrollY(Math.min(1700,pane.getMaxY()));pane.updateVisualScroll();
                screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());screenshot("filter-defect-"+runs+".png");
            }
        }
        System.out.println("PASS: every trigger category fits editor width with all its conditions enabled");
    }
    private static void boundaryChecks(SettingsScreen screen) throws Exception {
        Stage stage=(Stage)field(screen,"stage");
        Table editor=(Table)field(screen,"editor"); editor.clear();
        editor.add(new Table()).colspan(2).growX().height(2000).row();
        java.lang.reflect.Method choice=SettingsScreen.class.getDeclaredMethod("optionList",String.class,String.class,String.class,java.util.List.class,Consumer.class);
        choice.setAccessible(true);
        choice.invoke(screen,"Cards","edge-card","Defend_R",FilterOptions.cards(),(Consumer<String>)id->{});
        ((Table)field(screen,"root")).validate();
        ScrollPane pane=(ScrollPane)field(screen,"editorScroll");pane.validate();pane.setScrollY(pane.getMaxY());pane.updateVisualScroll();
        screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
        click(screen,stage.getRoot().findActor("edge-card-search"),.3f);
        if(stage.getRoot().findActor("edge-card-popup")==null)throw new AssertionError("Edge popup did not open");
        pane.setScrollY(0);pane.updateVisualScroll();stage.act(.05f);
        if(stage.getRoot().findActor("edge-card-popup")!=null)throw new AssertionError("Popup remains open after anchor is scrolled out of editor");
        Actor ruleRow=stage.getRoot().findActor("music-rule-name-" + ((Config.Rule)field(screen,"selected")).id);
        if(ruleRow!=null) fire(screen,ruleRow,com.badlogic.gdx.Input.Buttons.RIGHT);
        java.lang.reflect.Method choose=SettingsScreen.class.getDeclaredMethod("choose",boolean.class);choose.setAccessible(true);choose.invoke(screen,false);
        if(field(screen,"contextMenu")!=null || field(screen,"contextMenuParent")!=null)
            throw new AssertionError("Opening the file browser left a rule context menu attached to the stage");
        com.megacrit.cardcrawl.helpers.input.InputHelper.pressedEscape=true;screen.update();
        if((Boolean)field(screen,"browsing") || !battleaudio.BattleAudioMod.instance.settingsOpen())throw new AssertionError("Escape did not return from file browser to settings");
        if(com.megacrit.cardcrawl.helpers.input.InputHelper.pressedEscape)throw new AssertionError("File browser Escape remains unconsumed and would close settings on the next update");
        screen.update();
        if(!battleaudio.BattleAudioMod.instance.settingsOpen())throw new AssertionError("One browser Escape also closed settings");
        System.out.println("PASS: scrolling dismisses detached popup and one browser Escape returns to settings only");
    }
    private static void deleteSelectedRule(SettingsScreen screen) throws Exception {
        Config.Rule selected=(Config.Rule)field(screen,"selected"); Stage stage=(Stage)field(screen,"stage");
        Actor row=stage.getRoot().findActor("music-rule-name-"+selected.id); if(row==null)throw new AssertionError("Selected rule row missing");
        fire(screen,row,com.badlogic.gdx.Input.Buttons.RIGHT);
        TextButton delete=stage.getRoot().findActor("menu-delete-rule"); if(delete==null)throw new AssertionError("Rule delete action missing from context menu");
        fire(screen,delete,com.badlogic.gdx.Input.Buttons.LEFT);
        Table prompt=stage.getRoot().findActor("confirmation-prompt"); if(prompt==null)throw new AssertionError("Rule deletion confirmation missing");
        TextButton confirm=prompt.findActor("删除"); if(confirm==null)throw new AssertionError("Rule deletion confirmation action missing");
        fire(screen,confirm,com.badlogic.gdx.Input.Buttons.LEFT);screen.update();
    }
    private static void fire(SettingsScreen screen,Actor actor,int button) {
        Stage stage=actor.getStage();screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
        Vector2 point=actor.localToStageCoordinates(new Vector2(actor.getWidth()*.5f,actor.getHeight()*.5f));
        com.badlogic.gdx.scenes.scene2d.InputEvent event=new com.badlogic.gdx.scenes.scene2d.InputEvent();
        event.setType(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown);event.setStageX(point.x);event.setStageY(point.y);event.setPointer(0);event.setButton(button);
        actor.fire(event);
        com.badlogic.gdx.scenes.scene2d.InputEvent up=new com.badlogic.gdx.scenes.scene2d.InputEvent();
        up.setType(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp);up.setStageX(point.x);up.setStageY(point.y);up.setPointer(0);up.setButton(button);
        actor.fire(up);stage.act(0);
    }
    private static Config loadSaved(SettingsScreen screen,battleaudio.storage.AudioLibrary library) throws Exception {
        if(!screen.flushPendingChanges())throw new AssertionError("Debounced settings save failed before reload verification");
        return library.load();
    }
    private static Object field(Object o,String name) throws ReflectiveOperationException {
        java.lang.reflect.Field f=o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    private static boolean textContains(Actor actor,String text) {
        if(actor instanceof Label && ((Label)actor).getText().toString().contains(text))return true;
        if(actor instanceof Group)for(Actor child:((Group)actor).getChildren())if(textContains(child,text))return true;
        return false;
    }
    private static void click(SettingsScreen screen,Actor actor,float fraction) {
        if(actor==null)throw new AssertionError("Missing control");
        Stage stage=actor.getStage();
        Actor ancestor=actor.getParent();while(ancestor!=null && !(ancestor instanceof ScrollPane))ancestor=ancestor.getParent();
        if(ancestor!=null){ScrollPane pane=(ScrollPane)ancestor;pane.validate();Vector2 pt=actor.localToAscendantCoordinates(pane.getWidget(),new Vector2());pane.scrollTo(pt.x,pt.y,actor.getWidth(),actor.getHeight());pane.updateVisualScroll();}
        screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());stage.act(0);
        Vector2 point=actor.localToStageCoordinates(new Vector2(actor.getWidth()*fraction,actor.getHeight()/2));stage.stageToScreenCoordinates(point);
        if(!stage.touchDown(Math.round(point.x),Math.round(point.y),0,0))throw new AssertionError("Click not received: "+actor.getName());
        stage.touchUp(Math.round(point.x),Math.round(point.y),0,0);stage.act(0);
    }
    private static void screenshot(String name) {
        com.badlogic.gdx.graphics.Pixmap pixels=com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixmap(0,0,Gdx.graphics.getWidth(),Gdx.graphics.getHeight());
        com.badlogic.gdx.graphics.PixmapIO.PNG png=new com.badlogic.gdx.graphics.PixmapIO.PNG();png.setFlipY(true);
        try{png.write(Gdx.files.local(name),pixels);}catch(Exception ex){throw new RuntimeException(ex);}finally{png.dispose();pixels.dispose();}
    }
}
