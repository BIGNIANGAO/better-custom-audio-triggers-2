package battleaudio.ui;

import battleaudio.model.Config;
import battleaudio.model.MusicPack;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Measures input and render/update time using a disposable copy of the actual pack. */
public final class LoongPerformanceSmoke {
    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static java.lang.reflect.Method method(String name, Class<?>... types) throws Exception {
        java.lang.reflect.Method m = SettingsScreen.class.getDeclaredMethod(name, types); m.setAccessible(true); return m;
    }
    private static Map<String, Object> stats(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values); Collections.sort(sorted);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", sorted.size()); out.put("medianMs", sorted.get(sorted.size()/2));
        out.put("p95Ms", sorted.get((int)Math.ceil(sorted.size()*.95)-1)); out.put("maxMs", sorted.get(sorted.size()-1));
        return out;
    }
    private static void reveal(Stage stage, Actor actor) {
        Actor ancestor = actor.getParent();
        while (ancestor != null && !(ancestor instanceof ScrollPane)) ancestor = ancestor.getParent();
        if (ancestor != null) {
            ScrollPane pane = (ScrollPane)ancestor; pane.validate();
            Vector2 p = actor.localToAscendantCoordinates(pane.getWidget(), new Vector2());
            pane.scrollTo(p.x, p.y, actor.getWidth(), actor.getHeight()); pane.updateVisualScroll();
        }
    }
    private static double click(Stage stage, Actor actor) {
        if (actor == null) throw new AssertionError("Missing test control");
        reveal(stage, actor); stage.act(0);
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth()*.4f, actor.getHeight()/2));
        stage.stageToScreenCoordinates(point); int x = Math.round(point.x), y = Math.round(point.y);
        long start = System.nanoTime();
        if (!stage.touchDown(x, y, 0, Input.Buttons.LEFT)) throw new AssertionError("Input not received: " + actor.getName());
        stage.touchUp(x, y, 0, Input.Buttons.LEFT); stage.act(0);
        return (System.nanoTime()-start)/1000000d;
    }
    private static double rightClick(Stage stage, Actor actor) {
        if (actor == null) throw new AssertionError("Missing right-click control");
        reveal(stage, actor); Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth()*.4f, actor.getHeight()/2));
        InputEvent event = new InputEvent(); event.setType(InputEvent.Type.touchDown); event.setStageX(point.x); event.setStageY(point.y);
        event.setPointer(0); event.setButton(Input.Buttons.RIGHT);
        long start = System.nanoTime(); actor.fire(event); stage.act(0);
        double ms = (System.nanoTime()-start)/1000000d;
        if (stage.getRoot().findActor("settings-context-menu") == null) throw new AssertionError("Context menu did not open");
        return ms;
    }
    public static void run(SettingsScreen screen, double openMs) throws Exception {
        Stage stage = (Stage)field(screen, "stage"); Config draft = (Config)field(screen, "draft");
        MusicPack pack = draft.packs.stream().filter(p -> "loong".equals(p.name)).findFirst()
                .orElseThrow(() -> new AssertionError("loong fixture not loaded"));
        List<Config.Rule> rules = new ArrayList<>();
        for (Config.Rule rule : draft.rules) if (pack.id.equals(rule.packId)) rules.add(rule);
        if (rules.size() != 12) throw new AssertionError("Expected 12 loong rules");
        screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
        Table root = (Table)field(screen, "root"); root.validate();
        List<Double> expand = new ArrayList<>(), toggles = new ArrayList<>(), menus = new ArrayList<>(), selections = new ArrayList<>(), frames = new ArrayList<>();
        List<Double> ruleToggles = new ArrayList<>(), edits = new ArrayList<>();
        java.lang.reflect.Method closeMenu = method("closeContextMenu");
        for (int i = 0; i < 12; i++) {
            Actor button = stage.getRoot().findActor("pack-expand-" + pack.id);
            expand.add(click(stage, button));
        }
        @SuppressWarnings("unchecked") Map<String, Table> tables = (Map<String, Table>)field(screen, "packageRuleTables");
        if (!tables.get(pack.id).isVisible()) click(stage, stage.getRoot().findActor("pack-expand-" + pack.id));
        screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
        for (Config.Rule rule : rules) {
            Table row = stage.getRoot().findActor("rule-row-" + rule.id);
            if (row == null || row.getChildren().size != 1
                    || row.getChildren().first() != stage.getRoot().findActor("music-rule-name-" + rule.id))
                throw new AssertionError("loong rule row still includes a candidate count");
        }
        for (int i = 0; i < 8; i++) {
            CheckBox toggle = stage.getRoot().findActor("pack-enabled-" + pack.id);
            toggles.add(click(stage, toggle));
        }
        if (!pack.enabled) throw new AssertionError("Package should remain enabled after paired toggles");
        for (int i = 0; i < 8; i++) {
            menus.add(rightClick(stage, stage.getRoot().findActor("pack-row-" + pack.id)));
            closeMenu.invoke(screen);
        }
        List<Config.Rule> selectedRules = new ArrayList<>(rules); Collections.shuffle(selectedRules, new Random(20261028L));
        Config.Rule many = rules.stream().max(Comparator.comparingInt(r -> r.candidates.size())).get();
        selectedRules.remove(many); selectedRules.add(0, many);
        for (int i = 0; i < 5; i++) {
            Actor rule = stage.getRoot().findActor("music-rule-name-" + selectedRules.get(i).id);
            selections.add(click(stage, rule));
            screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
        }
        click(stage, stage.getRoot().findActor("music-rule-name-" + many.id));
        Actor warning = stage.getRoot().findActor("subscription-edit-warning");
        if ((pack.source == MusicPack.Source.SUBSCRIBED) != (warning != null))
            throw new AssertionError("Overwrite warning does not match the pack source");
        for (int i = 0; i < 6; i++) {
            rightClick(stage, stage.getRoot().findActor("music-rule-name-" + many.id));
            ruleToggles.add(click(stage, stage.getRoot().findActor("menu-toggle-rule")));
        }
        TextField name = stage.getRoot().findActor("audio-name");
        if (name == null) throw new AssertionError("Rule editor missing");
        String originalName = name.getText();
        for (int i = 0; i < 6; i++) {
            long start = System.nanoTime(); name.setText(originalName + " " + i);
            name.fire(new com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent());
            edits.add((System.nanoTime()-start)/1000000d); screen.update(); Thread.sleep(20);
        }
        name.setText(originalName); name.fire(new com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent());
        for (int i = 0; i < 100; i++) {
            long start = System.nanoTime(); screen.update(); screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
            frames.add((System.nanoTime()-start)/1000000d); Thread.sleep(10);
        }
        long flushStart = System.nanoTime();
        if (!screen.flushPendingChanges()) throw new AssertionError("Pending interaction changes failed to save");
        double flush = (System.nanoTime()-flushStart)/1000000d;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("packRules", rules.size()); report.put("packCandidates", rules.stream().mapToInt(r -> r.candidates.size()).sum());
        report.put("panelOpenMs", openMs); report.put("expandClick", stats(expand)); report.put("packageToggleClick", stats(toggles));
        report.put("contextMenu", stats(menus)); report.put("selectRule", stats(selections)); report.put("updateAndDraw", stats(frames));
        report.put("ruleToggle", stats(ruleToggles)); report.put("editRuleName", stats(edits));
        report.put("framesOver16_7Ms", frames.stream().filter(ms -> ms > 16.7).count());
        report.put("framesOver33_3Ms", frames.stream().filter(ms -> ms > 33.3).count()); report.put("flushMs", flush);
        Files.write(Paths.get("loong-ui-performance.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report).getBytes(StandardCharsets.UTF_8));
        System.out.println("LOONG_UI_PERF " + new com.google.gson.Gson().toJson(report));
    }
}
