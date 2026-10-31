package battleaudio.patches;

import battleaudio.BattleAudioMod;
import battleaudio.audio.BackgroundMusic;
import com.evacipated.cardcrawl.modthespire.lib.*;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.powers.AbstractPower;
import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

public final class GamePatches {
    private static int healDepth;
    public static int turnBlockReset;
    private static BattleAudioMod mod() { return BattleAudioMod.instance; }

    /** CardCrawlGame updates overlay controls outside AbstractDungeon.update. Freeze the entire game update for the modal. */
    @SpirePatch(clz = com.megacrit.cardcrawl.core.CardCrawlGame.class, method = "update")
    public static class ModalInput {
        @SpirePrefixPatch public static SpireReturn<Void> prefix() {
            if (mod() == null || !mod().pauseMenu()) return SpireReturn.Continue();
            com.megacrit.cardcrawl.helpers.input.InputHelper.updateFirst();
            if (com.megacrit.cardcrawl.core.CardCrawlGame.cursor != null) com.megacrit.cardcrawl.core.CardCrawlGame.cursor.update();
            clearClicks();
            basemod.BaseMod.publishPostUpdate();
            com.megacrit.cardcrawl.helpers.input.InputHelper.updateLast();
            return SpireReturn.Return(null);
        }
    }
    public static void clearClicks() {
        com.megacrit.cardcrawl.helpers.input.InputHelper.justClickedLeft = false;
        com.megacrit.cardcrawl.helpers.input.InputHelper.justClickedRight = false;
        com.megacrit.cardcrawl.helpers.input.InputHelper.justReleasedClickLeft = false;
        com.megacrit.cardcrawl.helpers.input.InputHelper.justReleasedClickRight = false;
        com.megacrit.cardcrawl.helpers.input.InputHelper.isMouseDown = false;
        com.megacrit.cardcrawl.helpers.input.InputHelper.isMouseDown_R = false;
        com.megacrit.cardcrawl.helpers.input.InputHelper.scrolledUp = false;
        com.megacrit.cardcrawl.helpers.input.InputHelper.scrolledDown = false;
        com.megacrit.cardcrawl.helpers.input.InputHelper.touchDown = false;
        com.megacrit.cardcrawl.helpers.input.InputHelper.touchUp = false;
    }

    @SpirePatch(clz = com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, method = "update")
    public static class PauseDungeon {
        @SpirePrefixPatch public static SpireReturn<Void> prefix() {
            return mod() != null && mod().settingsOpen() ? SpireReturn.Return(null) : SpireReturn.Continue();
        }
    }

    @SpirePatch(clz = com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen.class, method = "update")
    public static class PauseMenu {
        @SpirePrefixPatch public static SpireReturn<Void> prefix() {
            return mod() != null && mod().pauseMenu() ? SpireReturn.Return(null) : SpireReturn.Continue();
        }
    }

    @SpirePatch(clz = AbstractCreature.class, method = "healthBarUpdatedEvent")
    public static class HealthChanged {
        @SpirePostfixPatch public static void postfix(AbstractCreature __instance) {
            if (mod() != null) {
                if (__instance == AbstractDungeon.player) mod().healthChanged(healDepth > 0);
                mod().observeEvents();
            }
        }
    }

    @SpirePatch(clz = AbstractCreature.class, method = "heal", paramtypez = {int.class, boolean.class})
    public static class Healing {
        @SpirePrefixPatch public static void prefix(AbstractCreature __instance) {
            if (__instance == AbstractDungeon.player) healDepth++;
        }
        @SpirePostfixPatch public static void postfix(AbstractCreature __instance) {
            if (__instance == AbstractDungeon.player) {
                healDepth--;
            }
        }
    }

    @SpirePatch(clz = AbstractCreature.class, method = "loseBlock", paramtypez = {int.class, boolean.class})
    public static class BlockChange {
        private static final java.util.Deque<Integer> before = new java.util.ArrayDeque<>();
        @SpirePrefixPatch public static void prefix(AbstractCreature __instance) { before.push(__instance.currentBlock); }
        @SpirePostfixPatch public static void postfix(AbstractCreature __instance) {
            int old = before.pop();
            if (mod() != null && __instance == AbstractDungeon.player && turnBlockReset == 0 && __instance.currentBlock < old)
                mod().blockLost(old, __instance.currentBlock);
        }
    }

    @SpirePatch(clz = com.megacrit.cardcrawl.actions.GameActionManager.class, method = "getNextAction")
    public static class NormalBlockReset {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if (call.getMethodName().equals("loseBlock")) call.replace(
                            "{ battleaudio.patches.GamePatches.turnBlockReset++; try { $proceed($$); } finally { battleaudio.patches.GamePatches.turnBlockReset--; } }");
                }
            };
        }
    }

    @SpirePatch(clz = com.megacrit.cardcrawl.actions.common.ApplyPowerAction.class, method = "update")
    public static class PowerApply {
        private static final java.util.Deque<Integer> before = new java.util.ArrayDeque<>();
        @SpirePrefixPatch public static void prefix(com.megacrit.cardcrawl.actions.common.ApplyPowerAction __instance,
                AbstractPower ___powerToApply) {
            if (__instance == null || __instance.target == null || ___powerToApply == null || ___powerToApply.ID == null) {
                before.push(Integer.MIN_VALUE);
                return;
            }
            AbstractPower existing = __instance.target.getPower(___powerToApply.ID);
            before.push(existing == null ? Integer.MIN_VALUE : existing.amount);
        }
        @SpirePostfixPatch public static void postfix(com.megacrit.cardcrawl.actions.common.ApplyPowerAction __instance,
                AbstractPower ___powerToApply) {
            int old = before.pop();
            if (__instance == null || __instance.target == null || ___powerToApply == null || ___powerToApply.ID == null) return;
            AbstractPower current = __instance.target.getPower(___powerToApply.ID);
            if (mod() != null && current != null) {
                if(old==Integer.MIN_VALUE && "Mantra".equals(current.ID) && __instance.target==com.megacrit.cardcrawl.dungeons.AbstractDungeon.player && current.amount>0)
                    mod().gameEvent(events->events.mantraIncreased(0,current.amount,current.amount));
                mod().gameEvent(events -> events.observePowers(__instance.target, false, __instance.source));
                mod().observeEvents();
            }
        }
    }

    @SpirePatch(clz = com.megacrit.cardcrawl.audio.MainMusic.class, method = SpirePatch.CONSTRUCTOR)
    public static class MainBgm {
        @SpirePostfixPatch public static void postfix(com.megacrit.cardcrawl.audio.MainMusic __instance) { BackgroundMusic.wrap(__instance); }
    }
    @SpirePatch(clz = com.megacrit.cardcrawl.audio.TempMusic.class, method = SpirePatch.CONSTRUCTOR,
            paramtypez = {String.class, boolean.class, boolean.class})
    public static class TempBgm {
        @SpirePostfixPatch public static void postfix(com.megacrit.cardcrawl.audio.TempMusic __instance) { BackgroundMusic.wrap(__instance); }
    }
    @SpirePatch(clz = com.megacrit.cardcrawl.audio.TempMusic.class, method = SpirePatch.CONSTRUCTOR,
            paramtypez = {String.class, boolean.class, boolean.class, boolean.class})
    public static class CachedBgm {
        @SpirePostfixPatch public static void postfix(com.megacrit.cardcrawl.audio.TempMusic __instance) { BackgroundMusic.wrap(__instance); }
    }
    @SpirePatch(clz = com.megacrit.cardcrawl.core.CardCrawlGame.class, method = "dispose")
    public static class Shutdown {
        @SpirePrefixPatch public static void prefix() { if (mod() != null) mod().shutdown(); }
    }
}
