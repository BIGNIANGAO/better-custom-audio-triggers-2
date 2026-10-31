package battleaudio.ui;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl.audio.OpenALAudio;
import com.badlogic.gdx.backends.lwjgl.audio.OpenALSound;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.LongMap;
import com.megacrit.cardcrawl.audio.SoundMaster;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import org.lwjgl.openal.AL10;
import java.lang.reflect.Field;
import java.util.Map;

/** Verifies click sounds using the game's real Scene2D stage and widgets. */
final class UiClickSoundsSmoke {
    private UiClickSoundsSmoke() { }

    static void run(SettingsScreen screen, Stage stage, Skin skin) throws Exception {
        final int[] sounds = {0};
        final boolean[] blocked = {false};
        UiClickSounds probe = new UiClickSounds(() -> blocked[0], () -> sounds[0]++);
        Table fixture = new Table();
        TextButton button = new TextButton("Action", skin);
        CheckBox check = new CheckBox("", skin);
        SelectBox<String> select = new SelectBox<>(skin);
        select.setItems("First", "Second");
        Slider slider = new Slider(0, 10, 1, false, skin);
        TextField field = new TextField("Text", skin);
        TextButton disabled = new TextButton("Disabled", skin); disabled.setDisabled(true);
        Label label = new Label("Label", skin);
        fixture.add(button).size(100, 50); fixture.add(check).size(48, 50);
        fixture.add(select).size(120, 50); fixture.add(slider).size(140, 50);
        fixture.add(field).size(120, 50); fixture.add(disabled).size(120, 50);
        fixture.add(label).size(100, 50);
        fixture.setBounds(80, 320, 748, 60);
        stage.addActor(fixture); stage.addCaptureListener(probe); fixture.validate();
        try {
            verifyNativePlayback(screen, stage, button);
            sounds[0] = 0;
            check.setChecked(true); select.setSelectedIndex(1); slider.setValue(3); field.setText("Changed");
            expect(sounds[0], 0, "programmatic changes");
            click(screen, stage, button, Input.Buttons.LEFT); expect(sounds[0], 1, "button");
            click(screen, stage, check, Input.Buttons.LEFT); expect(sounds[0], 2, "checkbox");
            click(screen, stage, select, Input.Buttons.LEFT); expect(sounds[0], 3, "dropdown");
            screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch()); stage.act(.2f);
            if (select.getList().getStage() == null) throw new AssertionError("Dropdown did not open");
            click(screen, stage, select.getList(), Input.Buttons.LEFT); expect(sounds[0], 4, "dropdown option");
            stage.act(.2f);
            Vector2 position = screenPosition(stage, slider);
            stage.touchDown(Math.round(position.x), Math.round(position.y), 0, Input.Buttons.LEFT);
            stage.touchDragged(Math.round(position.x + 10), Math.round(position.y), 0);
            stage.touchDragged(Math.round(position.x + 20), Math.round(position.y), 0);
            stage.touchUp(Math.round(position.x + 20), Math.round(position.y), 0, Input.Buttons.LEFT);
            expect(sounds[0], 5, "slider press and drag");
            click(screen, stage, field, Input.Buttons.LEFT); expect(sounds[0], 6, "text field");
            click(screen, stage, disabled, Input.Buttons.LEFT); expect(sounds[0], 6, "disabled button");
            click(screen, stage, label, Input.Buttons.LEFT); expect(sounds[0], 6, "non-interactive label");
            blocked[0] = true;
            click(screen, stage, button, Input.Buttons.LEFT); expect(sounds[0], 6, "blocked screen");
            blocked[0] = false;
            Table ruleRow = new Table(); ruleRow.setName("rule-row-fixture"); ruleRow.add(button);
            InputEvent right = new InputEvent(); right.setTarget(button);
            probe.touchDown(right, 0, 0, 0, Input.Buttons.RIGHT); expect(sounds[0], 7, "rule right-click");
            probe.touchDown(right, 0, 0, 0, Input.Buttons.MIDDLE); expect(sounds[0], 7, "middle-click");
            System.out.println("PASS: native UI click sounds trigger once for direct input only");
        } finally {
            select.hideList(); stage.removeCaptureListener(probe); fixture.remove();
            stage.setKeyboardFocus(null); stage.setScrollFocus(null);
        }
    }

    private static void verifyNativePlayback(SettingsScreen screen, Stage stage, Actor button) throws Exception {
        if (!(Gdx.audio instanceof OpenALAudio) || CardCrawlGame.sound == null)
            throw new AssertionError("Native game audio is unavailable");
        OpenALAudio audio = (OpenALAudio)Gdx.audio;
        Field soundsField = SoundMaster.class.getDeclaredField("map"); soundsField.setAccessible(true);
        Object soundEffect = ((Map<?,?>)soundsField.get(CardCrawlGame.sound)).get("UI_CLICK_1");
        if (soundEffect == null) throw new AssertionError("Game UI_CLICK_1 sound is not registered");
        Field soundField = soundEffect.getClass().getDeclaredField("sound"); soundField.setAccessible(true);
        Field bufferField = OpenALSound.class.getDeclaredField("bufferID"); bufferField.setAccessible(true);
        Field nextField = OpenALAudio.class.getDeclaredField("nextSoundId"); nextField.setAccessible(true);
        Field sourcesField = OpenALAudio.class.getDeclaredField("soundIdToSource"); sourcesField.setAccessible(true);
        boolean oldMute = CardCrawlGame.MUTE_IF_BG, oldBackgrounded = Settings.isBackgrounded;
        float oldListenerGain = AL10.alGetListenerf(AL10.AL_GAIN);
        long soundId = nextField.getLong(audio);
        try {
            CardCrawlGame.MUTE_IF_BG = false; Settings.isBackgrounded = false;
            AL10.alListenerf(AL10.AL_GAIN, 0);
            click(screen, stage, button, Input.Buttons.LEFT);
            if (nextField.getLong(audio) != soundId + 1) {
                Vector2 point=screenPosition(stage,button);
                Vector2 stagePoint=stage.screenToStageCoordinates(new Vector2(point));
                Actor hit=stage.hit(stagePoint.x,stagePoint.y,true);
                throw new AssertionError("Click did not start one native sound: delta="+(nextField.getLong(audio)-soundId)
                        +" screen="+point+" stage="+stagePoint+" hit="+(hit==null?"null":hit.getName())
                        +" viewport="+stage.getViewport().getScreenWidth()+"x"+stage.getViewport().getScreenHeight());
            }
            Object nativeSound = soundField.get(soundEffect);
            if (!(nativeSound instanceof OpenALSound)) throw new AssertionError("Game click sound is not an OpenAL sound");
            @SuppressWarnings("unchecked") LongMap<Integer> sources = (LongMap<Integer>)sourcesField.get(audio);
            Integer source = sources.get(soundId);
            if (source == null || AL10.alGetSourcei(source, AL10.AL_BUFFER) != bufferField.getInt(nativeSound))
                throw new AssertionError("Click did not play the game's UI_CLICK_1 audio buffer");
            audio.stopSound(soundId);
            System.out.println("PASS: direct UI click plays the game's UI_CLICK_1 OpenAL buffer");
        } finally {
            CardCrawlGame.MUTE_IF_BG = oldMute; Settings.isBackgrounded = oldBackgrounded;
            AL10.alListenerf(AL10.AL_GAIN, oldListenerGain);
        }
    }

    private static Vector2 screenPosition(Stage stage, Actor actor) {
        Vector2 point=stage.stageToScreenCoordinates(actor.localToStageCoordinates(
                new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        // The game's older Stage implementation flips Y using viewport height, not window height.
        point.y+=Gdx.graphics.getHeight()-stage.getViewport().getScreenHeight();
        return point;
    }

    private static void click(SettingsScreen screen, Stage stage, Actor actor, int button) {
        screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch()); stage.act(0);
        Vector2 point = screenPosition(stage, actor);
        int x = Math.round(point.x), y = Math.round(point.y);
        stage.touchDown(x, y, 0, button); stage.touchUp(x, y, 0, button); stage.act(0);
    }

    private static void expect(int actual, int expected, String interaction) {
        if (actual != expected) throw new AssertionError(interaction + " generated " + actual + " sounds; expected " + expected);
    }
}
