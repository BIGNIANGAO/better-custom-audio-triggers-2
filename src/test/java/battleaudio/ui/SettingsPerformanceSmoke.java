package battleaudio.ui;

import battleaudio.localization.I18n;
import battleaudio.model.Config;
import battleaudio.model.MusicPack;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Drives the real Config panel with unchanged input and checks the reported symptoms. */
public final class SettingsPerformanceSmoke {
    private static Object field(Object target,String name)throws Exception{java.lang.reflect.Field value=target.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(target);}
    private static boolean hasLabel(Actor actor,String text) {
        if(actor instanceof Label && ((Label)actor).getText().toString().contains(text)) return true;
        if(actor instanceof com.badlogic.gdx.scenes.scene2d.Group)
            for(Actor child:((com.badlogic.gdx.scenes.scene2d.Group)actor).getChildren()) if(hasLabel(child,text)) return true;
        return false;
    }
    public static void run(SettingsScreen screen,double openMilliseconds)throws Exception {
        Stage stage=(Stage)field(screen,"stage"); Table root=(Table)field(screen,"root"); root.validate();
        UiClickSoundsSmoke.run(screen,stage,(Skin)field(screen,"skin"));
        WorkshopWizardSmoke.run(screen,stage,(Skin)field(screen,"skin"));
        Config draft=(Config)field(screen,"draft");
        if(root.getWidth()<1500)throw new AssertionError("Config editor still confined to narrow ModPanel bounds");
        if(stage.getRoot().findActor(I18n.text("New music pack"))==null
                || stage.getRoot().findActor(I18n.text("\u65b0\u5efa\u89c4\u5219"))==null)
            throw new AssertionError("Missing the two direct create actions");
        if(stage.getRoot().findActor(I18n.text("Copy as new music pack"))!=null
                || stage.getRoot().findActor(I18n.text("\u590d\u5236\u89c4\u5219"))!=null)
            throw new AssertionError("Removed copy actions are still directly visible");

        MusicPack subscription = draft.packs.stream().filter(p -> "999999991".equals(p.workshopId)).findFirst().orElse(null);
        if (subscription != null) {
            Config.Rule subscribedRule = draft.rules.stream().filter(r -> subscription.id.equals(r.packId)).findFirst()
                    .orElseThrow(() -> new AssertionError("Subscribed fixture rule missing"));
            battleaudio.storage.AudioLibrary library = (battleaudio.storage.AudioLibrary) field(screen, "library");
            Path audio = Paths.get(System.getProperty("battleaudio.workshopContent"), "999999991", "audio", "tone.wav");
            if (!Files.isSameFile(audio, library.managed(subscribedRule.audio().cachePath)))
                throw new AssertionError("Subscribed playback did not use the original item file");
            battleaudio.audio.PlaybackManager player = new battleaudio.audio.PlaybackManager(library,
                    problem -> { throw new AssertionError("Subscribed audio failed: " + problem); });
            player.start(subscribedRule, "preview");
            if (player.count() != 1) throw new AssertionError("Subscribed audio did not start playback");
            player.stopAll();
            java.lang.reflect.Method selectPack = SettingsScreen.class.getDeclaredMethod("selectPackage", MusicPack.class);
            selectPack.setAccessible(true); selectPack.invoke(screen, subscription);
            Table editor=(Table)field(screen,"editor");
            String warning=I18n.text("Subscribed pack edits may be replaced by Steam updates. Copy the pack to keep your changes.");
            if(!hasLabel(editor,warning) || hasLabel(editor,I18n.text("Pack version") + ":"))
                throw new AssertionError("Subscribed package should show the overwrite warning without a package version");
            java.lang.reflect.Method select = SettingsScreen.class.getDeclaredMethod("selectRule", Config.Rule.class);
            select.setAccessible(true); select.invoke(screen, subscribedRule);
            editor=(Table)field(screen,"editor");
            if(!hasLabel(editor,warning) || hasLabel(editor,I18n.text("Pack version") + ":"))
                throw new AssertionError("Subscribed rule should show the overwrite warning without a package version");
            if(!I18n.text("Subscribed pack edits may be replaced by Steam updates. Copy the pack to keep your changes.","zhs")
                    .equals("闂傚倸鍊峰ù鍥х暦閸偅鍙忛柟鎯板Г閳锋梻鈧箍鍎遍ˇ顖炲垂閸岀偞鐓㈡俊顖滃皑缁辨岸鏌ㄥ┑鍡╂Ц缂佲偓鐎ｎ偁浜滈柡宥冨妺缁惰鲸淇婇銏″殗婵﹨娅ｇ划娆戞崉閵娧屽敹闂備胶顢婂▍鏇㈠礉濞嗘挶鈧礁鈻庨幘宕囧€炲銈嗗笂閼冲爼宕㈤柆宥嗏拺闁告繂瀚惂渚€鏌涢弴銊ヤ簼妞ゅ繈鍊栫换婵嬫偨闂堟稐绮堕梺缁橆殔閿曨亜鐣疯ぐ鎺戦敜婵°倐鍋撶紒鐘靛У缁绘盯骞嬪▎蹇曞姶闂佽桨绀侀崐鍧楀蓟濞戙垹鍗抽柕濞垮劚缁犲搫鈹戦鍡欏埌閻庢稈鏅濋幑銏犫槈閵忕姷顓洪梺缁樺姇椤曨參宕ｉ崱妤婃富闁靛牆绻愰惁婊堟煕閵娿劍纭鹃柣锝囧厴椤㈡宕掑Δ鈧禍楣冩煟閵忋垺鏆╅柕鍡楋躬閺屾稓鈧綆鍋呭畷宀€鈧娲滈崗姗€銆佸鈧幃銏ゆ煥鐎ｎ剚娈梻浣藉吹閸犳劗鍒掓惔銏℃珷婵°倕鎳忛崑鍌炴煥閻斿搫孝缂佺姷濮垫穱濠囧Χ閸涱喖娅ｇ紒鐐劤缂嶅﹪寮婚敐澶婂嵆婵ê宕俊浠嬫⒑閼恒儱鐏ユい锔藉閹广垹鈽夊▎鎰Ф闂佸憡鎸嗛崥閿嬪灩缁辨挻绗熼崶褎鐏堢紓浣虹帛閿曘垹鐣峰ú顏勭劦妞ゆ帊闄嶆禍婊堟煙閸濆嫭顥滃ù婊堢畺濮婃椽鎮烽弶鎸庢瘣缂備胶绮崝妤呭矗閸涘瓨鈷戠紒澶婃鐎氬嘲鈻撻弮鈧穱濠囶敃閿濆繗鈧潡鏌″畝鈧崰搴ㄦ偩閿熺姴绠ユい鏃€鍎虫慨鍏肩節閻㈤潧浠滄俊顖氾躬瀹曟粓鎮㈤悡搴ゆ憰闂佹寧绻傞ˇ顖炴偂濞戙垺鐓曢悘鐐插⒔閻﹥銇勯妷銉Ш缂佽鲸鎸婚幏鍛鐎ｎ亝鎳欑紓鍌欐祰椤曆囧磹閸фぜ鈧礁顫濋鑺ョ€婚梺瑙勫閺呮稒绂嶉弶搴撴斀闁绘劕寮堕ˉ鐐烘煕鎼淬垹鈻曠€殿噮鍋婂畷濂稿即閻斿弶瀚肩紓鍌欑椤戝牆鈻旈弴鐘典笉濞寸厧鐡ㄩ悡鐔镐繆閵堝倸浜惧┑鈽嗗亝缁诲棗螞閻斿吋鈷戦柛婵嗗椤箓鏌涙惔銈勫惈缂侇喖鐗撳畷鍗炩槈濞嗗繋鍖栭梻浣瑰劤缁绘帡骞婇幘璇茬閻庯綆鍠楅悡鏇炩攽閻樻彃顏柡鍡╁墴閺岋紕浠︾拠娴嬪亾閺嶎偆绱﹂柤鎭掑劚缁剁偛顭跨捄铏圭伇婵炲娼″缁樻媴妞嬪簼瑕嗙紓浣瑰絻濞尖€崇暦閹达箑绠婚柛鎾茶兌閻撴挻绻濋悽闈浶㈤柛鐕佸亞濞嗐垽鎮欓悜妯煎幈濡炪倖鍔х紞鈧瑙劽湁?"))
                throw new AssertionError("Simplified Chinese overwrite warning does not match the requested text");
            TextField name = stage.getRoot().findActor("audio-name");
            if (name == null) throw new AssertionError("Subscribed rule editor is not available");
            click(screen, name, .4f);
            name.setText("Edited subscription rule");
            name.fire(new com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent());
            if (!screen.flushPendingChanges()) throw new AssertionError("Subscribed rule edit failed to save");
            Path manifest = Paths.get(System.getProperty("battleaudio.workshopContent"), "999999991", "music-pack.json");
            com.google.gson.JsonObject json = new com.google.gson.JsonParser().parse(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!"Edited subscription rule".equals(json.getAsJsonArray("rules").get(0).getAsJsonObject().get("name").getAsString()))
                throw new AssertionError("Subscribed rule editor did not write the package manifest");
            rightClick(screen, stage.getRoot().findActor("music-rule-name-" + subscribedRule.id));
            if (stage.getRoot().findActor("menu-delete-rule") == null || stage.getRoot().findActor("menu-move-rule") == null)
                throw new AssertionError("Subscribed rule context actions are missing");
            java.lang.reflect.Method dismiss = SettingsScreen.class.getDeclaredMethod("closeContextMenu");
            dismiss.setAccessible(true); dismiss.invoke(screen);
        }

        // Exercise the actual Scene2D rows and right-click handlers with isolated temporary objects.
        MusicPack pack=new MusicPack(); pack.name="UI context fixture"; pack.version="legacy-schema-3.1";
        draft.packs.add(pack);
        Config.Rule rule=new Config.Rule(); rule.name="UI movable rule"; rule.packId=pack.id;
        Config.Rule secondRule=new Config.Rule(); secondRule.name="Second package rule"; secondRule.packId=pack.id;
        draft.rules.add(rule); draft.rules.add(secondRule); draft.refreshPackMembership();
        @SuppressWarnings("unchecked") Set<String> expanded=(Set<String>)field(screen,"expandedPackages"); expanded.add(pack.id);
        java.lang.reflect.Method list=SettingsScreen.class.getDeclaredMethod("list"); list.setAccessible(true); list.invoke(screen);
        root.validate(); screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());

        CheckBox toggle=stage.getRoot().findActor("pack-enabled-"+pack.id);
        TextButton packName=stage.getRoot().findActor("music-pack-name-"+pack.id);
        TextButton expand=stage.getRoot().findActor("pack-expand-"+pack.id);
        if(toggle==null||packName==null||expand==null)throw new AssertionError("Music pack row is missing switch/name/disclosure controls");
        if(packName.getLabel().getLabelAlign()!=com.badlogic.gdx.utils.Align.center
                || toggle.getX()>=packName.getX() || packName.getRight()>expand.getX())
            throw new AssertionError("Music pack row columns are not switch-left, name-center, disclosure-right");
        if(toggle.getText().length()!=0)throw new AssertionError("Package switch unexpectedly displays a label");
        if(expand.getLabel().getFontScaleX()<1.05f)throw new AssertionError("Package disclosure text is still too small");
        if(expand.getWidth()<40 || packName.getHeight()<36)throw new AssertionError("Package list controls are too compressed");
        @SuppressWarnings("unchecked") Map<String,Table> packageRules=(Map<String,Table>)field(screen,"packageRuleTables");
        @SuppressWarnings("unchecked") Map<String,Table> packageBlocks=(Map<String,Table>)field(screen,"packageBlocks");
        java.lang.reflect.Method toggleExpanded=SettingsScreen.class.getDeclaredMethod("togglePackageExpanded",String.class);
        toggleExpanded.setAccessible(true);
        long expandStarted=System.nanoTime();
        for(int i=0;i<81;i++)toggleExpanded.invoke(screen,pack.id);
        double expandAverageMs=(System.nanoTime()-expandStarted)/1_000_000d/81d;
        Table ruleTable=packageRules.get(pack.id);
        if(ruleTable==null || ruleTable.isVisible() || packageBlocks.get(pack.id).getChildren().size!=1)throw new AssertionError("Collapse did not remove the package rule list from layout");
        toggleExpanded.invoke(screen,pack.id);
        if(!ruleTable.isVisible() || packageBlocks.get(pack.id).getChildren().size!=2 || stage.getRoot().findActor("music-rule-name-"+rule.id)==null
                || stage.getRoot().findActor("music-rule-name-"+secondRule.id)==null || ruleTable.getChildren().size!=2)
            throw new AssertionError("Expand did not show every package rule");
        for(Config.Rule item:Arrays.asList(rule,secondRule)) {
            Table row=stage.getRoot().findActor("rule-row-"+item.id);
            if(row==null || row.getChildren().size!=1 || row.getChildren().first()!=stage.getRoot().findActor("music-rule-name-"+item.id))
                throw new AssertionError("Rule rows must show the rule name without a candidate count");
        }
        java.lang.reflect.Method selectLocalPack=SettingsScreen.class.getDeclaredMethod("selectPackage",MusicPack.class);
        selectLocalPack.setAccessible(true);selectLocalPack.invoke(screen,pack);
        Table localEditor=(Table)field(screen,"editor");
        if(hasLabel(localEditor,I18n.text("Pack version") + ":") || hasLabel(localEditor,I18n.text("Subscribed pack edits may be replaced by Steam updates. Copy the pack to keep your changes.")))
            throw new AssertionError("Local package should not show a version or subscription warning");
        java.lang.reflect.Method selectLocal=SettingsScreen.class.getDeclaredMethod("selectRule",Config.Rule.class);
        selectLocal.setAccessible(true);selectLocal.invoke(screen,rule);
        Table editor=(Table)field(screen,"editor");
        if(hasLabel(editor,I18n.text("Pack version") + ":") || hasLabel(editor,I18n.text("Subscribed pack edits may be replaced by Steam updates. Copy the pack to keep your changes.")))
            throw new AssertionError("Local package should not show a version or subscription warning");
        if(stage.getRoot().findActor("empty-candidates")==null)
            throw new AssertionError("Removing the count must not hide missing audio in rule details");

        long menuStarted=System.nanoTime(); rightClick(screen,stage.getRoot().findActor("pack-row-"+pack.id));
        double packageMenuMs=(System.nanoTime()-menuStarted)/1_000_000d;
        TextButton export=stage.getRoot().findActor("menu-export-package");
        TextButton bump=stage.getRoot().findActor("menu-bump-package-version");
        TextButton copyPackage=stage.getRoot().findActor("menu-copy-package");
        TextButton deletePackage=stage.getRoot().findActor("menu-delete-package");
        if(export==null||bump!=null||copyPackage==null||deletePackage==null)
            throw new AssertionError("Package context menu must expose copy/export/delete but hide the version action");
        Table menu=stage.getRoot().findActor("settings-context-menu");
        if(menu.getWidth()<300 || menu.getRight()>stage.getViewport().getWorldWidth()+.5f)
            throw new AssertionError("Package context menu width or screen clamping is invalid");
        if(!pack.version.equals("legacy-schema-3.1"))
            throw new AssertionError("Viewing the package must not alter its compatibility version");
        @SuppressWarnings("unchecked") Set<TextField> invalid=(Set<TextField>)field(screen,"invalidInputs");
        TextField invalidFixture=new TextField("",(Skin)field(screen,"skin"));invalid.add(invalidFixture);
        click(screen,export,.5f);
        Label status=stage.getRoot().findActor("settings-status");
        if(status==null || !status.getText().toString().equals(I18n.text("Please fix the highlighted fields before exporting the music pack.")))
            throw new AssertionError("Invalid export hint is not localized");
        invalid.remove(invalidFixture);
        rightClick(screen,stage.getRoot().findActor("pack-row-"+pack.id));
        click(screen,stage.getRoot().findActor("menu-remove-package"),.5f);
        Table confirmation=stage.getRoot().findActor("confirmation-prompt");
        if(confirmation==null)throw new AssertionError("Removing a package did not ask for confirmation");
        TextButton confirmRemove=confirmation.findActor(I18n.text("Remove package"));
        click(screen,confirmRemove,.75f);
        if(draft.pack(pack.id)!=null)throw new AssertionError("Confirmed removal left the package in settings");
        if(rule.packId!=null || secondRule.packId!=null)throw new AssertionError("Confirmed removal did not move every rule to root");
        if(stage.getRoot().findActor("rule-row-"+rule.id)==null || stage.getRoot().findActor("rule-row-"+secondRule.id)==null)
            throw new AssertionError("Removed package rules are not visible in the root list");
        // Recreate an isolated pack so the subsequent move-to checks exercise multiple destinations.
        draft.packs.add(pack); rule.packId=pack.id; secondRule.packId=pack.id; draft.refreshPackMembership();
        expanded.add(pack.id); list.invoke(screen); root.validate(); screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());

        rightClick(screen,stage.getRoot().findActor("pack-row-"+pack.id));
        click(screen,stage.getRoot().findActor("menu-delete-package"),.5f);
        confirmation=stage.getRoot().findActor("confirmation-prompt");
        if(confirmation==null)throw new AssertionError("Deleting a package did not ask for confirmation");
        click(screen,confirmation.findActor(I18n.text("Delete package")),.75f);
        waitForIdle(screen);
        if(draft.pack(pack.id)!=null || draft.rules.contains(rule) || draft.rules.contains(secondRule))
            throw new AssertionError("Confirmed package deletion retained its package or rules");
        draft.packs.add(pack); draft.rules.add(rule); draft.rules.add(secondRule); draft.refreshPackMembership();
        expanded.add(pack.id); list.invoke(screen); root.validate(); screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());

        rightClick(screen,stage.getRoot().findActor("music-rule-name-"+rule.id));
        if(stage.getRoot().findActor("menu-move-rule")==null
                || stage.getRoot().findActor("menu-delete-rule")==null
                || stage.getRoot().findActor("menu-copy-rule")==null)
            throw new AssertionError("Rule context menu mismatch: menu="+(stage.getRoot().findActor("settings-context-menu")!=null)
                    +" move="+(stage.getRoot().findActor("menu-move-rule")!=null)
                    +" delete="+(stage.getRoot().findActor("menu-delete-rule")!=null)
                    +" copy="+(stage.getRoot().findActor("menu-copy-rule")!=null));
        TextButton moveItem=stage.getRoot().findActor("menu-move-rule");
        Vector2 moveTop=moveItem.localToStageCoordinates(new Vector2(0,moveItem.getHeight()));
        Vector2 moveLeft=moveItem.localToStageCoordinates(new Vector2(0,0));
        Vector2 moveRight=moveItem.localToStageCoordinates(new Vector2(moveItem.getWidth(),0));
        click(screen,moveItem,.5f);
        Table moveSubmenu=stage.getRoot().findActor("move-rule-submenu");
        if(moveSubmenu==null || Math.abs((moveSubmenu.getY()+moveSubmenu.getHeight())-moveTop.y)>3
                || Math.abs(moveSubmenu.getX()-moveRight.x)>12 && Math.abs(moveSubmenu.getRight()-moveLeft.x)>12)
            throw new AssertionError("Move-to submenu is not cascaded from the Move to row");
        if(stage.getRoot().findActor("menu-move-rule-root")==null)
            throw new AssertionError("Move-to submenu omitted the root list destination");
        click(screen,stage.getRoot().findActor("menu-move-rule-root"),.5f);
        if(rule.packId!=null)throw new AssertionError("Move-to-root did not detach the rule from its package");
        java.lang.reflect.Method move=SettingsScreen.class.getDeclaredMethod("moveRule",Config.Rule.class,String.class); move.setAccessible(true);
        move.invoke(screen,rule,pack.id);
        if(!pack.id.equals(rule.packId))throw new AssertionError("Rule fixture could not be restored to its package");
        list.invoke(screen); root.validate(); screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
        toggle=stage.getRoot().findActor("pack-enabled-"+pack.id);
        if(toggle==null)throw new AssertionError("Package switch row missing after moving rule back to package");
        boolean ruleEnabledBefore=rule.enabled;
        long switchStarted=System.nanoTime(); click(screen,toggle,.5f);
        double switchMs=(System.nanoTime()-switchStarted)/1_000_000d;
        if(pack.enabled)throw new AssertionError("Package switch did not update immediately");
        if(rule.enabled!=ruleEnabledBefore)throw new AssertionError("Package switch rewrote an individual rule switch");
        toggle=stage.getRoot().findActor("pack-enabled-"+pack.id); click(screen,toggle,.5f);
        if(!pack.enabled)throw new AssertionError("Re-enabling package failed");

        // Cancel all fixture edits before the real autosave can run, then verify durable state on close.
        draft.rules.remove(rule); draft.rules.remove(secondRule); draft.packs.remove(pack); draft.refreshPackMembership();
        java.lang.reflect.Field active=SettingsScreen.class.getDeclaredField("activePackId"); active.setAccessible(true); active.set(screen,draft.packs.isEmpty()?null:draft.packs.get(0).id);
        java.lang.reflect.Field selected=SettingsScreen.class.getDeclaredField("selected"); selected.setAccessible(true);
        selected.set(screen,draft.rules.isEmpty()?null:draft.rules.get(0));
        list.invoke(screen);
        java.lang.reflect.Method bind=SettingsScreen.class.getDeclaredMethod("bind",boolean.class); bind.setAccessible(true); bind.invoke(screen,false);
        java.lang.reflect.Method flush=SettingsScreen.class.getMethod("flushPendingChanges");
        if(!(Boolean)flush.invoke(screen))throw new AssertionError("Debounced auto-save did not flush at the explicit verification boundary");

        Path settingsFile=Paths.get(System.getProperty("battleaudio.home"),"settings.json");
        long lastSaveBefore=Files.getLastModifiedTime(settingsFile).toMillis();
        java.util.List<Map<String,Object>> samples=new ArrayList<>();
        TextButton add=stage.getRoot().findActor(I18n.text("\u6DFB\u52A0\u5019\u9009\u97F3\u9891"));
        if(add==null)throw new AssertionError("Missing real add-audio control");
        for(int frame=0;frame<36;frame++) {
            screen.update();stage.act(1f/60);screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
            Vector2 position=add.localToStageCoordinates(new Vector2());
            Map<String,Object> sample=new LinkedHashMap<>();sample.put("frame",frame);sample.put("x",position.x);sample.put("y",position.y);
            sample.put("width",add.getWidth());sample.put("height",add.getHeight());sample.put("fontScale",add.getLabel().getFontScaleX());sample.put("preferredWidth",add.getPrefWidth());sample.put("rootNeedsLayout",root.needsLayout());samples.add(sample);
        }
        Map<String,Object> report=new LinkedHashMap<>(); report.put("configOpenMs",openMilliseconds); report.put("samples",samples);
        report.put("packageExpandAverageMs",expandAverageMs); report.put("packageContextOpenMs",packageMenuMs); report.put("packageSwitchMs",switchMs);
        double widthMinimum=Double.POSITIVE_INFINITY,widthMaximum=0,scaleMinimum=Double.POSITIVE_INFINITY,scaleMaximum=0;int pending=0;
        for(int index=5;index<samples.size();index++){Map<String,Object> sample=samples.get(index);double width=((Number)sample.get("width")).doubleValue(),scale=((Number)sample.get("fontScale")).doubleValue();widthMinimum=Math.min(widthMinimum,width);widthMaximum=Math.max(widthMaximum,width);scaleMinimum=Math.min(scaleMinimum,scale);scaleMaximum=Math.max(scaleMaximum,scale);if((Boolean)sample.get("rootNeedsLayout"))pending++;}
        report.put("idleConfigWrites",Files.getLastModifiedTime(settingsFile).toMillis()!=lastSaveBefore);
        report.put("addButtonWidthRange",widthMaximum-widthMinimum);report.put("addButtonFontScaleRange",scaleMaximum-scaleMinimum);report.put("pendingLayoutFrames",pending);
        Files.write(Paths.get("settings-performance.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report).getBytes(StandardCharsets.UTF_8));
        System.out.println("MEASURE: Config open="+Math.round(openMilliseconds)+" ms; expand avg="+String.format(java.util.Locale.ROOT,"%.3f",expandAverageMs)
                +" ms; context menu="+String.format(java.util.Locale.ROOT,"%.3f",packageMenuMs)+" ms; package toggle="+String.format(java.util.Locale.ROOT,"%.3f",switchMs)+" ms");
        Skin skin=(Skin)field(screen,"skin"); com.badlogic.gdx.graphics.g2d.BitmapFont font=skin.getFont("settings-font");
        String newUserText="\u9F98\u66CC\u9B31\uAC02\uD7E3\u3042\u0E27\u0E30";
        for(char character:newUserText.toCharArray())if(!font.getData().hasGlyph(character))throw new AssertionError("Lazy font cannot add new user character U+"+Integer.toHexString(character));
        com.badlogic.gdx.scenes.scene2d.ui.Label newName=new com.badlogic.gdx.scenes.scene2d.ui.Label(newUserText,skin);
        newName.setPosition(20,80);stage.addActor(newName);screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());newName.remove();
        java.util.List<String> failures=new ArrayList<>();
        if(openMilliseconds>650)failures.add("Config initialization blocks for "+Math.round(openMilliseconds)+" ms (limit 650 ms)");
        if(widthMaximum-widthMinimum>.5 || scaleMaximum-scaleMinimum>.005)failures.add("Add-audio button oscillates with unchanged input");
        if(pending>1)failures.add("Config layout does not settle across idle frames");
        if(expandAverageMs>8)failures.add("Package expansion is not incremental enough: "+expandAverageMs+" ms average");
        if(packageMenuMs>100 || switchMs>100)failures.add("Package context/switch action exceeded the 100 ms response budget");
        if(!failures.isEmpty())throw new AssertionError(String.join("; ",failures));
        System.out.println("PASS: create-only toolbar, right-click actions, rule move-to-root, incremental expansion and responsive switches");
        System.out.println("PASS: Config opens promptly and add-audio geometry/text stays stable across idle frames");
    }
    private static void waitForIdle(SettingsScreen screen) throws Exception {
        long deadline=System.currentTimeMillis()+5000;
        while(screen.isBusy() && System.currentTimeMillis()<deadline) { Thread.sleep(10); screen.update(); }
        if(screen.isBusy()) throw new AssertionError("Package deletion did not finish");
    }
    private static void click(SettingsScreen screen,Actor actor,float fraction) {
        if(actor==null)throw new AssertionError("Missing UI actor"); Stage stage=actor.getStage();
        reveal(stage,actor); screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());stage.act(0);
        Vector2 point=actor.localToStageCoordinates(new Vector2(actor.getWidth()*fraction,actor.getHeight()/2));stage.stageToScreenCoordinates(point);
        point.y+=Gdx.graphics.getHeight()-stage.getViewport().getScreenHeight();
        int x=Math.round(point.x),y=Math.round(point.y); if(!stage.touchDown(x,y,0,0))throw new AssertionError("Click not received: "+actor.getName());
        stage.touchUp(x,y,0,0);stage.act(0);
    }
    private static void rightClick(SettingsScreen screen,Actor actor) {
        if(actor==null)throw new AssertionError("Missing right-click target");Stage stage=actor.getStage();
        screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
        Vector2 point=actor.localToStageCoordinates(new Vector2(actor.getWidth()*.35f,actor.getHeight()/2));
        com.badlogic.gdx.scenes.scene2d.InputEvent event=new com.badlogic.gdx.scenes.scene2d.InputEvent();
        event.setType(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown);
        event.setStageX(point.x); event.setStageY(point.y); event.setPointer(0); event.setButton(com.badlogic.gdx.Input.Buttons.RIGHT);
        actor.fire(event); stage.act(0);
        if(stage.getRoot().findActor("settings-context-menu")==null)
            throw new AssertionError("Right-click did not open context menu for "+actor.getName());
    }
    private static void reveal(Stage stage,Actor actor) {
        Actor ancestor=actor.getParent();while(ancestor!=null&&!(ancestor instanceof ScrollPane))ancestor=ancestor.getParent();
        if(ancestor!=null){ScrollPane pane=(ScrollPane)ancestor;pane.validate();Vector2 point=actor.localToAscendantCoordinates(pane.getWidget(),new Vector2());pane.scrollTo(point.x,point.y,actor.getWidth(),actor.getHeight());pane.updateVisualScroll();}
    }

}
