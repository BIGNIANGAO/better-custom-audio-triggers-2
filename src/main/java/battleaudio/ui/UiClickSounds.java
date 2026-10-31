package battleaudio.ui;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.Disableable;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import java.util.function.BooleanSupplier;

/** Plays the game's native click sound for direct settings-screen interactions. */
final class UiClickSounds extends InputListener {
    private final BooleanSupplier blocked;
    private final Runnable play;

    UiClickSounds(BooleanSupplier blocked) {
        this(blocked, () -> {
            if (CardCrawlGame.sound != null) CardCrawlGame.sound.play("UI_CLICK_1");
        });
    }

    UiClickSounds(BooleanSupplier blocked, Runnable play) {
        this.blocked = blocked;
        this.play = play;
    }

    @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
        if (!blocked.getAsBoolean() && isClickTarget(event.getTarget(), button)) play.run();
        return false;
    }

    private static boolean isClickTarget(Actor target, int button) {
        for (Actor actor = target; actor != null; actor = actor.getParent()) {
            if (actor instanceof Disableable && ((Disableable)actor).isDisabled()) return false;
            if (button == Input.Buttons.RIGHT) {
                String name = actor.getName();
                if (name != null && (name.startsWith("pack-row-") || name.startsWith("rule-row-"))) return true;
            } else if (button == Input.Buttons.LEFT) {
                if (actor instanceof Button || actor instanceof SelectBox || actor instanceof Slider || actor instanceof TextField)
                    return true;
                if (actor instanceof com.badlogic.gdx.scenes.scene2d.ui.List) {
                    com.badlogic.gdx.scenes.scene2d.ui.List<?> list = (com.badlogic.gdx.scenes.scene2d.ui.List<?>)actor;
                    return !list.getSelection().isDisabled() && list.getItems().size > 0;
                }
            }
        }
        return false;
    }
}
