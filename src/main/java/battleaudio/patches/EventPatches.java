package battleaudio.patches;

import battleaudio.BattleAudioMod;
import battleaudio.model.Config.Trigger;
import battleaudio.model.EventContext;
import com.evacipated.cardcrawl.modthespire.lib.*;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.cards.*;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.orbs.AbstractOrb;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import javassist.CannotCompileException;
import javassist.expr.*;
import java.util.*;

public final class EventPatches {
    private static final Deque<DamageInfo> playerDamageSources=new ArrayDeque<>();
    private static final Map<com.megacrit.cardcrawl.actions.AbstractGameAction,AbstractOrb> passiveActions=new WeakHashMap<>();
    private static final Map<com.megacrit.cardcrawl.actions.AbstractGameAction,AbstractOrb> lightningDamageActions=new WeakHashMap<>();
    private static final Map<com.megacrit.cardcrawl.actions.AbstractGameAction,AbstractOrb> evokeActions=new WeakHashMap<>();
    private static final Set<com.megacrit.cardcrawl.actions.AbstractGameAction> shivDamageActions=
            Collections.newSetFromMap(new WeakHashMap<com.megacrit.cardcrawl.actions.AbstractGameAction,Boolean>());
    private static final Set<com.megacrit.cardcrawl.actions.watcher.ChangeStanceAction> mantraTransitions=
            Collections.newSetFromMap(new WeakHashMap<com.megacrit.cardcrawl.actions.watcher.ChangeStanceAction,Boolean>());
    private static final class ScrySelection {
        final CardGroup group;
        final List<AbstractCard> cards;
        boolean confirmed;
        ScrySelection(CardGroup group) { this.group=group; cards=new ArrayList<>(group.group); }
    }
    private static final Map<com.megacrit.cardcrawl.actions.utility.ScryAction,ScrySelection> scryCards=new WeakHashMap<>();
    private static final Set<com.megacrit.cardcrawl.actions.AbstractGameAction> calmExitActions=
            Collections.newSetFromMap(new WeakHashMap<com.megacrit.cardcrawl.actions.AbstractGameAction,Boolean>());
    private static Map<String,Integer> powerSnapshot(AbstractCreature creature) {
        Map<String,Integer> values=new HashMap<>();
        if(creature!=null) for(com.megacrit.cardcrawl.powers.AbstractPower power:creature.powers)
            values.put(power.ID,power.amount);
        return values;
    }
    private static void reportPowerDecreases(AbstractCreature target,AbstractCreature source,Map<String,Integer> before) {
        if(target!=AbstractDungeon.player || before==null || mod()==null) return;
        Map<String,Integer> after=powerSnapshot(target);
        for(Map.Entry<String,Integer> entry:before.entrySet()) {
            String id=entry.getKey(); int old=entry.getValue();
            int next=after.getOrDefault(id,0);
            if(old>next) {
                boolean removed=!after.containsKey(id);
                mod().gameEvent(events->events.powerDecreased(target,id,old,next,source,removed));
            }
        }
    }
    public static void passiveAction(com.megacrit.cardcrawl.actions.AbstractGameAction action,AbstractOrb orb) {
        passiveActions.put(action,orb);
    }
    public static void lightningDamageAction(com.megacrit.cardcrawl.actions.AbstractGameAction action,AbstractOrb orb) {
        if(orb!=null) lightningDamageActions.put(action,orb);
    }
    public static void evokeAction(com.megacrit.cardcrawl.actions.AbstractGameAction action,AbstractOrb orb) {
        if(action instanceof com.megacrit.cardcrawl.actions.common.DarkOrbEvokeAction
                || action instanceof com.megacrit.cardcrawl.actions.common.GainEnergyAction) evokeActions.put(action,orb);
    }
    public static void mantraTransition(com.megacrit.cardcrawl.actions.AbstractGameAction action,
            com.megacrit.cardcrawl.powers.watcher.MantraPower power) {
        if(action instanceof com.megacrit.cardcrawl.actions.watcher.ChangeStanceAction
                && power.owner==AbstractDungeon.player && power.amount>=10)
            mantraTransitions.add((com.megacrit.cardcrawl.actions.watcher.ChangeStanceAction)action);
    }
    public static void mantraGain(com.megacrit.cardcrawl.powers.watcher.MantraPower power,int before) {
        if(mod()!=null && power.owner==AbstractDungeon.player && power.owner.powers.contains(power) && power.amount>before)
            mod().gameEvent(events->events.mantraIncreased(before,power.amount-before,power.amount));
    }
    public static void shivDamageAction(com.megacrit.cardcrawl.actions.AbstractGameAction action) {
        if(action!=null) shivDamageActions.add(action);
    }
    public static void scryOpened(com.megacrit.cardcrawl.actions.utility.ScryAction action,CardGroup group) {
        if(action!=null && group!=null) scryCards.put(action,new ScrySelection(group));
    }
    public static void calmExitAction(com.megacrit.cardcrawl.actions.AbstractGameAction action) {
        calmExitActions.add(action);
    }
    public static EventContext playerDamageContext() {
        DamageInfo info=playerDamageSources.peek();
        if(info==null || info.owner==null) return new EventContext().source("UNKNOWN","");
        return new EventContext().source(info.owner==AbstractDungeon.player?"PLAYER":"ENEMY",info.owner.id);
    }
    private static BattleAudioMod mod() { return BattleAudioMod.instance; }
    public static void event(Trigger trigger) { if (mod() != null) mod().event(trigger, null); }
    public static void generated(AbstractCard card, CardGroup destination) {
        if (mod() != null) mod().gameEvent(events -> events.generated(card,pile(destination)));
    }
    public static void drawn(AbstractCard card) { if (mod() != null) mod().gameEvent(events -> events.drawn(card)); }
    public static void discarded(AbstractCard card, CardGroup source, boolean endTurn) {
        if (mod()!=null) mod().gameEvent(events -> events.discarded(card,pile(source),endTurn));
    }
    public static void retained(AbstractCard card) { if (mod() != null) mod().gameEvent(events -> events.retained(card)); }
    public static void rewardPicked(com.megacrit.cardcrawl.screens.CardRewardScreen screen, AbstractCard card) {
        if (mod()==null || screen==null || card==null || !combatCardReward(screen)) return;
        mod().gameEvent(events -> events.rewardOutcome(screen.rItem,card));
    }
    public static void rewardSkipped(com.megacrit.cardcrawl.screens.CardRewardScreen screen) {
        if (mod()!=null && combatCardReward(screen)) mod().gameEvent(events -> events.rewardOutcome(screen.rItem,null));
    }
    private static boolean combatCardReward(com.megacrit.cardcrawl.screens.CardRewardScreen screen) {
        com.megacrit.cardcrawl.rooms.AbstractRoom room=AbstractDungeon.getCurrRoom();
        return room instanceof com.megacrit.cardcrawl.rooms.MonsterRoom && room.isBattleOver
                && screen.rItem!=null && screen.rItem.type==com.megacrit.cardcrawl.rewards.RewardItem.RewardType.CARD;
    }
    public static void evoked(AbstractOrb orb,int position) { if (mod() != null) mod().gameEvent(events -> events.evoke(orb,VerifiedEventPatches.evokeCause(),position)); }
    private static void observe() { if (mod() != null) mod().observeEvents(); }

    private static final Deque<Set<AbstractRelic>> relicsBefore=new ArrayDeque<>();
    private static final Deque<Integer> circletBefore=new ArrayDeque<>();
    private static void beforeRelic(AbstractPlayer player) {
        Set<AbstractRelic> owned=Collections.newSetFromMap(new IdentityHashMap<>());
        if(player!=null) owned.addAll(player.relics);
        relicsBefore.push(owned);
        AbstractRelic circlet=player==null?null:player.getRelic("Circlet");
        circletBefore.push(circlet==null?Integer.MIN_VALUE:circlet.counter);
    }
    private static void afterRelic(AbstractPlayer player) {
        Set<AbstractRelic> owned=relicsBefore.pop();
        int oldCirclet=circletBefore.pop();
        if(player==null || mod()==null || !relicsBefore.isEmpty()) return;
        for(AbstractRelic relic:player.relics) if(!owned.contains(relic))
            mod().gameEvent(events->events.relicObtained(relic));
        AbstractRelic circlet=player.getRelic("Circlet");
        if(circlet!=null && owned.contains(circlet) && circlet.counter>oldCirclet)
            mod().gameEvent(events->events.relicObtained(circlet));
    }
    @SpirePatch(clz=AbstractRelic.class, method="obtain")
    @SpirePatch(clz=AbstractRelic.class, method="instantObtain", paramtypez={})
    public static class RelicObtain {
        @SpirePrefixPatch public static void before() { beforeRelic(AbstractDungeon.player); }
        @SpirePostfixPatch public static void after() { afterRelic(AbstractDungeon.player); }
    }
    @SpirePatch(clz=AbstractRelic.class, method="instantObtain",
            paramtypez={AbstractPlayer.class,int.class,boolean.class})
    @SpirePatch(clz=AbstractRelic.class, method="reorganizeObtain",
            paramtypez={AbstractPlayer.class,int.class,boolean.class,int.class})
    public static class RelicObtainForPlayer {
        @SpirePrefixPatch public static void before(AbstractRelic __instance, AbstractPlayer player) { beforeRelic(player); }
        @SpirePostfixPatch public static void after(AbstractRelic __instance, AbstractPlayer player) { afterRelic(player); }
    }

    @SpirePatch(clz=com.megacrit.cardcrawl.vfx.campfire.CampfireSleepEffect.class, method="update")
    public static class CampfireRest {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if (call.getMethodName().equals("heal")) call.replace("{ $proceed($$); battleaudio.patches.EventPatches.event(battleaudio.model.Config.Trigger.CAMPFIRE_REST); }");
                }
            };
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.vfx.campfire.CampfireSmithEffect.class, method=SpirePatch.CLASS)
    public static class SmithState { public static SpireField<Boolean> fired = new SpireField<>(() -> false); }
    public static void smithCompleted(com.megacrit.cardcrawl.vfx.campfire.CampfireSmithEffect effect) {
        if (!SmithState.fired.get(effect)) { SmithState.fired.set(effect, true); event(Trigger.CAMPFIRE_SMITH); }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.vfx.campfire.CampfireSmithEffect.class, method="update")
    public static class CampfireSmith {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if (call.getMethodName().equals("upgrade")) call.replace("{ int before = $0.timesUpgraded; $proceed($$); if ($0.timesUpgraded > before) battleaudio.patches.EventPatches.smithCompleted(this); }");
                }
            };
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.rewards.chests.AbstractChest.class, method=SpirePatch.CLASS)
    public static class ChestState { public static SpireField<Boolean> fired = new SpireField<>(() -> false); }
    @SpirePatch(clz=com.megacrit.cardcrawl.rewards.chests.AbstractChest.class, method="open")
    @SpirePatch(clz=com.megacrit.cardcrawl.rewards.chests.BossChest.class, method="open")
    public static class ChestOpen {
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.rewards.chests.AbstractChest __instance) {
            if (!ChestState.fired.get(__instance)) { ChestState.fired.set(__instance, true); event(Trigger.CHEST_OPEN); }
        }
    }
    @SpirePatch(clz=AbstractMonster.class, method="damage")
    public static class EnemyDamage {
        private static final Deque<Integer> health = new ArrayDeque<>();
        @SpirePrefixPatch public static void before(AbstractMonster __instance) { health.push(__instance.currentHealth); }
        @SpirePostfixPatch public static void after(AbstractMonster __instance, DamageInfo info) {
            int loss = Math.max(0, health.pop() - Math.max(0, __instance.currentHealth));
            if (mod() != null && info.owner == AbstractDungeon.player)
                mod().gameEvent(events -> events.enemyDamage(loss,__instance));
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.unique.PoisonLoseHpAction.class,method="update")
    public static class PoisonSettlement {
        private static final class Snapshot {
            final int health, poison; final boolean done;
            Snapshot(com.megacrit.cardcrawl.actions.unique.PoisonLoseHpAction action) {
                health=action.target.currentHealth;
                com.megacrit.cardcrawl.powers.AbstractPower power=action.target.getPower("Poison");
                poison=power==null?0:power.amount; done=action.isDone;
            }
        }
        private static final Deque<Snapshot> before=new ArrayDeque<>();
        @SpirePrefixPatch public static void before(com.megacrit.cardcrawl.actions.unique.PoisonLoseHpAction __instance) {
            before.push(new Snapshot(__instance));
        }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.actions.unique.PoisonLoseHpAction __instance) {
            Snapshot old=before.pop();
            if (!old.done && __instance.isDone && mod()!=null)
                mod().gameEvent(events->events.poisonResolved(__instance.target,old.health,old.poison));
        }
    }

    @SpirePatch(clz=com.megacrit.cardcrawl.actions.unique.RegenAction.class,method="update")
    public static class RegenSettlement {
        private static final Deque<Integer> health=new ArrayDeque<>();
        @SpirePrefixPatch public static void before(com.megacrit.cardcrawl.actions.unique.RegenAction __instance) {
            health.push(__instance.target.currentHealth);
        }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.actions.unique.RegenAction __instance) {
            int old=health.pop();
            if(mod()!=null) mod().gameEvent(events->events.regenHealed(__instance.target,old));
        }
    }
    @SpirePatch(clz=AbstractPlayer.class, method="applyStartOfTurnRelics")
    public static class TurnStart {
        @SpirePrefixPatch public static void before() { if (mod() != null) mod().gameEvent(events -> events.turnStart()); }
        @SpirePostfixPatch public static void after() { observe(); }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.rooms.AbstractRoom.class, method="endTurn")
    public static class TurnEnd {
        @SpirePrefixPatch public static void before() { event(Trigger.TURN_END); }
    }
    @SpirePatch(clz=AbstractPlayer.class, method="useCard")
    public static class Play {
        @SpirePostfixPatch public static void after(AbstractPlayer __instance, AbstractCard c, AbstractMonster monster, int energyOnUse) {
            if (mod() != null) mod().gameEvent(events -> events.played(c)); observe();
        }
    }
    @SpirePatch(clz=AbstractPlayer.class, method="draw", paramtypez={int.class})
    public static class Draw {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if (call.getMethodName().equals("triggerWhenDrawn")) call.replace("{ $proceed($$); battleaudio.patches.EventPatches.drawn($0); }");
                }
            };
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.GameActionManager.class, method="incrementDiscard")
    public static class Discard {
        @SpirePostfixPatch public static void after(boolean endOfTurn) {
            if (mod() != null) mod().gameEvent(events -> events.discarded(endOfTurn));
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.common.DiscardAction.class, method="update")
    public static class DiscardCards {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if (call.getClassName().equals(CardGroup.class.getName()) && call.getMethodName().equals("moveToDiscardPile"))
                        call.replace("{ $proceed($$); battleaudio.patches.EventPatches.discarded($1,$0,this.endTurn); }");
                }
            };
        }
    }
    @SpirePatch(clz=CardGroup.class, method="moveToExhaustPile")
    public static class Exhaust {
        @SpirePostfixPatch public static void after(CardGroup __instance, AbstractCard c) {
            if (mod() != null) mod().gameEvent(events -> events.exhausted(c,pile(__instance)));
        }
    }
    @SpirePatch(clz=CardGroup.class, method="moveToHand",paramtypez={AbstractCard.class,CardGroup.class})
    public static class ReturnFromDiscard {
        private static final Deque<Boolean> eligible=new ArrayDeque<>();
        @SpirePrefixPatch public static void before(CardGroup __instance,AbstractCard card,CardGroup source) {
            eligible.push(AbstractDungeon.player!=null && source==AbstractDungeon.player.discardPile
                    && source.group.contains(card) && !AbstractDungeon.player.hand.group.contains(card));
        }
        @SpirePostfixPatch public static void after(CardGroup __instance,AbstractCard card,CardGroup source) {
            if(eligible.pop() && AbstractDungeon.player.hand.group.contains(card) && !source.group.contains(card)
                    && mod()!=null) mod().gameEvent(events->events.returnedFromDiscard(card));
        }
    }
    private static String pile(CardGroup group) {
        if (AbstractDungeon.player==null) return "";
        if (group==AbstractDungeon.player.hand) return "HAND";
        if (group==AbstractDungeon.player.drawPile) return "DRAW";
        if (group==AbstractDungeon.player.discardPile) return "DISCARD";
        if (group==AbstractDungeon.player.limbo) return "LIMBO";
        return "";
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.common.EmptyDeckShuffleAction.class, method="update")
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.defect.ShuffleAllAction.class, method="update")
    public static class Reshuffle {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if (call.getMethodName().equals("shuffle") && call.getClassName().equals(CardGroup.class.getName()))
                        call.replace("{ boolean hadCards = !$0.isEmpty(); $proceed($$); if (hadCards) battleaudio.patches.EventPatches.event(battleaudio.model.Config.Trigger.RESHUFFLE); }");
                }
            };
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.vfx.cardManip.ShowCardAndAddToHandEffect.class, method=SpirePatch.CONSTRUCTOR, paramtypez={AbstractCard.class})
    @SpirePatch(clz=com.megacrit.cardcrawl.vfx.cardManip.ShowCardAndAddToHandEffect.class, method=SpirePatch.CONSTRUCTOR, paramtypez={AbstractCard.class, float.class, float.class})
    @SpirePatch(clz=com.megacrit.cardcrawl.vfx.cardManip.ShowCardAndAddToDiscardEffect.class, method=SpirePatch.CONSTRUCTOR, paramtypez={AbstractCard.class})
    @SpirePatch(clz=com.megacrit.cardcrawl.vfx.cardManip.ShowCardAndAddToDiscardEffect.class, method=SpirePatch.CONSTRUCTOR, paramtypez={AbstractCard.class, float.class, float.class})
    @SpirePatch(clz=com.megacrit.cardcrawl.vfx.cardManip.ShowCardAndAddToDrawPileEffect.class, method=SpirePatch.CONSTRUCTOR, paramtypez={AbstractCard.class, float.class, float.class, boolean.class, boolean.class, boolean.class})
    @SpirePatch(clz=com.megacrit.cardcrawl.vfx.cardManip.ShowCardAndAddToDrawPileEffect.class, method=SpirePatch.CONSTRUCTOR, paramtypez={AbstractCard.class, boolean.class, boolean.class})
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.common.MakeTempCardAtBottomOfDeckAction.class, method="update")
    public static class CreateCards {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if (call.getClassName().equals(CardGroup.class.getName()) && call.getMethodName().startsWith("addTo"))
                        call.replace("{ $proceed($$); battleaudio.patches.EventPatches.generated($1,$0); }");
                }
            };
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.ui.panels.EnergyPanel.class, method="addEnergy")
    @SpirePatch(clz=com.megacrit.cardcrawl.ui.panels.EnergyPanel.class, method="setEnergy")
    public static class EnergyChange {
        private static final Deque<Integer> before=new ArrayDeque<>();
        @SpirePrefixPatch public static void before() { before.push(com.megacrit.cardcrawl.ui.panels.EnergyPanel.totalCount); }
        @SpirePostfixPatch public static void after() {
            int old=before.pop(), next=com.megacrit.cardcrawl.ui.panels.EnergyPanel.totalCount;
            if (mod()!=null) mod().gameEvent(events -> events.energyMutation(old,next));
            observe();
        }
    }
    @SpirePatch(clz=AbstractCreature.class, method="addBlock")
    public static class BlockGain {
        private static final Deque<Integer> before=new ArrayDeque<>();
        @SpirePrefixPatch public static void before(AbstractCreature __instance) { before.push(__instance.currentBlock); }
        @SpirePostfixPatch public static void after(AbstractCreature __instance) {
            int old=before.pop();
            if (mod()!=null) mod().gameEvent(events -> events.blockMutation(__instance,old,__instance.currentBlock));
        }
    }
    @SpirePatch(clz=AbstractPlayer.class,method="gainGold")
    @SpirePatch(clz=AbstractPlayer.class,method="loseGold")
    public static class GoldChange {
        private static final Deque<Integer> before=new ArrayDeque<>();
        @SpirePrefixPatch public static void before(AbstractPlayer __instance) { before.push(__instance.gold); }
        @SpirePostfixPatch public static void after(AbstractPlayer __instance) {
            int old=before.pop();
            if(mod()!=null) mod().gameEvent(events->events.goldMutation(old,__instance.gold));
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.ui.panels.EnergyPanel.class, method="useEnergy")
    @SpirePatch(clz=AbstractCreature.class, method="addBlock")
    @SpirePatch(clz=AbstractCreature.class, method="loseBlock", paramtypez={int.class, boolean.class})
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.common.ReducePowerAction.class, method="update")
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.common.RemoveSpecificPowerAction.class, method="update")
    public static class Resources {
        @SpirePostfixPatch public static void after() { observe(); }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.common.ReducePowerAction.class,method="update")
    public static class PowerDecrease {
        private static final Deque<Map<String,Integer>> before=new ArrayDeque<>();
        @SpirePrefixPatch public static void prefix(com.megacrit.cardcrawl.actions.common.ReducePowerAction __instance) {
            before.push(powerSnapshot(__instance.target));
        }
        @SpirePostfixPatch public static void postfix(com.megacrit.cardcrawl.actions.common.ReducePowerAction __instance) {
            reportPowerDecreases(__instance.target,__instance.source,before.pop());
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.common.RemoveSpecificPowerAction.class,method="update")
    public static class PowerRemove {
        private static final Deque<Map<String,Integer>> before=new ArrayDeque<>();
        @SpirePrefixPatch public static void prefix(com.megacrit.cardcrawl.actions.common.RemoveSpecificPowerAction __instance) {
            before.push(powerSnapshot(__instance.target));
        }
        @SpirePostfixPatch public static void postfix(com.megacrit.cardcrawl.actions.common.RemoveSpecificPowerAction __instance) {
            reportPowerDecreases(__instance.target,__instance.source,before.pop());
        }
    }    /** Mantra spends ten stacks inside stackPower; observe the reached value before that consumption. */
    @SpirePatch(clz=com.megacrit.cardcrawl.powers.watcher.MantraPower.class, method="stackPower")
    public static class Mantra {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    String name=call.getMethodName();
                    if (name.equals("stackPower"))
                        call.replace("{ int before = this.amount; $proceed($$); battleaudio.patches.EventPatches.mantraGain(this,before); if (battleaudio.BattleAudioMod.instance != null) battleaudio.BattleAudioMod.instance.observeEvents(); }");
                    else if (name.equals("addToTop"))
                        call.replace("{ battleaudio.patches.EventPatches.mantraTransition($1,this); $proceed($$); }");
                }
            };
        }
    }
    @SpirePatch(clz=AbstractMonster.class, method="die", paramtypez={boolean.class})
    public static class Kill {
        @SpirePostfixPatch public static void after(AbstractMonster __instance) {
            if (mod() != null) mod().gameEvent(events -> events.killed(__instance));
        }
    }
    @SpirePatch(clz=AbstractPlayer.class, method="damage")
    public static class SelfDamage {
        private static final Deque<Integer> health = new ArrayDeque<>();
        private static final Deque<Boolean> intangible = new ArrayDeque<>();
        @SpirePrefixPatch public static void before(AbstractPlayer __instance,DamageInfo info) {
            health.push(__instance.currentHealth); playerDamageSources.push(info);
            intangible.push(info.output>1 && __instance.hasPower("IntangiblePlayer"));
        }
        @SpirePostfixPatch public static void after(AbstractPlayer __instance, DamageInfo info) {
            int old = health.pop(); playerDamageSources.pop(); boolean mitigated=intangible.pop();
            if (mod() != null) mod().gameEvent(events -> {
                if(mitigated) events.intangibleMitigated(__instance,info);
                events.damage(__instance, info, old);
            });
        }
    }
    @SpirePatch(clz=AbstractPlayer.class, method="channelOrb")
    public static class Channel {
        private static final Deque<Set<AbstractOrb>> before = new ArrayDeque<>();
        @SpirePrefixPatch public static void prefix(AbstractPlayer __instance) {
            before.push(new HashSet<>(__instance.orbs)); observe();
        }
        @SpirePostfixPatch public static void after(AbstractPlayer __instance) {
            Set<AbstractOrb> previous = before.pop();
            for (AbstractOrb orb : __instance.orbs) if (!(orb instanceof com.megacrit.cardcrawl.orbs.EmptyOrbSlot) && !previous.contains(orb))
                if (mod() != null) mod().gameEvent(events -> events.orb(Trigger.CHANNEL_ORB, orb));
            observe();
        }
    }
    @SpirePatch(clz=AbstractPlayer.class, method="evokeOrb")
    @SpirePatch(clz=AbstractPlayer.class, method="evokeWithoutLosingOrb")
    public static class Evoke {
        private static final Deque<AbstractOrb> before = new ArrayDeque<>();
        @SpirePrefixPatch public static void prefix(AbstractPlayer __instance) {
            before.push(__instance.orbs.isEmpty() ? new com.megacrit.cardcrawl.orbs.EmptyOrbSlot() : __instance.orbs.get(0));
        }
        @SpirePostfixPatch public static void after() {
            AbstractOrb orb = before.pop(); if (!(orb instanceof com.megacrit.cardcrawl.orbs.EmptyOrbSlot)) evoked(orb,1); observe();
        }
    }
    @SpirePatch(clz=AbstractPlayer.class, method="evokeNewestOrb")
    public static class EvokeNewest {
        private static final Deque<AbstractOrb> before = new ArrayDeque<>();
        private static final Deque<Integer> positions = new ArrayDeque<>();
        @SpirePrefixPatch public static void prefix(AbstractPlayer __instance) {
            positions.push(__instance.orbs.size());
            before.push(__instance.orbs.isEmpty() ? new com.megacrit.cardcrawl.orbs.EmptyOrbSlot() : __instance.orbs.get(__instance.orbs.size() - 1));
        }
        @SpirePostfixPatch public static void after() {
            AbstractOrb orb = before.pop(); int position=positions.pop(); if (!(orb instanceof com.megacrit.cardcrawl.orbs.EmptyOrbSlot)) evoked(orb,position); observe();
        }
    }
    @SpirePatch(clz=AbstractPlayer.class, method="increaseMaxOrbSlots")
    @SpirePatch(clz=AbstractPlayer.class, method="decreaseMaxOrbSlots")
    public static class OrbValues { @SpirePostfixPatch public static void after() { observe(); } }
    @SpirePatch(clz=com.megacrit.cardcrawl.orbs.Dark.class, method="onEndOfTurn")
    public static class DarkPassive {
        private static final Deque<Integer> previous = new ArrayDeque<>();
        @SpirePrefixPatch public static void before(com.megacrit.cardcrawl.orbs.Dark __instance) { previous.push(__instance.evokeAmount); }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.orbs.Dark __instance) {
            int old = previous.pop();
            if (mod() != null) mod().gameEvent(events -> {
                events.orb(Trigger.ORB_PASSIVE, __instance);
                events.darkAccumulated(__instance, old);
            });
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.orbs.Lightning.class, method="onEndOfTurn")
    @SpirePatch(clz=com.megacrit.cardcrawl.orbs.Frost.class, method="onEndOfTurn")
    @SpirePatch(clz=com.megacrit.cardcrawl.orbs.Plasma.class, method="onStartOfTurn")
    public static class OrbPassive {
        @SpirePostfixPatch public static void after(AbstractOrb __instance) {
            if (mod() != null) mod().gameEvent(events -> events.orb(Trigger.ORB_PASSIVE, __instance));
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.orbs.Frost.class,method="onEndOfTurn")
    @SpirePatch(clz=com.megacrit.cardcrawl.orbs.Plasma.class,method="onStartOfTurn")
    public static class PassiveActionSource {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if(call.getMethodName().equals("addToBottom") && call.getClassName().equals(
                            com.megacrit.cardcrawl.actions.GameActionManager.class.getName()))
                        call.replace("{ battleaudio.patches.EventPatches.passiveAction($1,this); $proceed($$); }");
                }
            };
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.orbs.Dark.class,method="onEvoke")
    @SpirePatch(clz=com.megacrit.cardcrawl.orbs.Plasma.class,method="onEvoke")
    public static class EvokeActionSource {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() { @Override public void edit(MethodCall call) throws CannotCompileException {
                if(call.getMethodName().equals("addToTop") && call.getClassName().equals(
                        com.megacrit.cardcrawl.actions.GameActionManager.class.getName()))
                    call.replace("{ battleaudio.patches.EventPatches.evokeAction($1,this); $proceed($$); }");
            }};
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.common.DarkOrbEvokeAction.class,method="update")
    public static class DarkEvokeSettlement {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() { @Override public void edit(MethodCall call) throws CannotCompileException {
                if(call.getMethodName().equals("damage") && call.getClassName().equals(AbstractCreature.class.getName()))
                    call.replace("{ int old = $0.currentHealth; $proceed($$); battleaudio.patches.EventPatches.darkEvokeDamage(this,$0,old); }");
            }};
        }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.actions.common.DarkOrbEvokeAction __instance) {
            if(__instance.isDone) evokeActions.remove(__instance);
        }
    }
    public static void darkEvokeDamage(com.megacrit.cardcrawl.actions.AbstractGameAction action,AbstractCreature target,int old) {
        AbstractOrb orb=evokeActions.remove(action);
        if(orb instanceof com.megacrit.cardcrawl.orbs.Dark && target instanceof AbstractMonster && mod()!=null)
            mod().gameEvent(events->events.orbEvokeDamage(orb,(AbstractMonster)target,old,target.currentHealth));
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.stances.CalmStance.class,method="onExitStance")
    public static class CalmExitActionSource {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if(call.getMethodName().equals("addToBottom") && call.getClassName().equals(
                            com.megacrit.cardcrawl.actions.GameActionManager.class.getName()))
                        call.replace("{ battleaudio.patches.EventPatches.calmExitAction($1); $proceed($$); }");
                }
            };
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.common.GainBlockAction.class,method="update")
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.common.GainEnergyAction.class,method="update")
    public static class PassiveActualEffect {
        private static final Deque<Integer> before=new ArrayDeque<>();
        @SpirePrefixPatch public static void before(com.megacrit.cardcrawl.actions.AbstractGameAction __instance) {
            before.push(__instance instanceof com.megacrit.cardcrawl.actions.common.GainBlockAction?
                    AbstractDungeon.player.currentBlock:com.megacrit.cardcrawl.ui.panels.EnergyPanel.totalCount);
        }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.actions.AbstractGameAction __instance) {
            int old=before.pop(); AbstractOrb orb=passiveActions.get(__instance);
            if(mod()==null) return;
            boolean block=__instance instanceof com.megacrit.cardcrawl.actions.common.GainBlockAction;
            int next=block?AbstractDungeon.player.currentBlock:com.megacrit.cardcrawl.ui.panels.EnergyPanel.totalCount;
            if(orb!=null && next>old) mod().gameEvent(events->events.orbPassiveEffect(block?
                    Trigger.ORB_PASSIVE_BLOCK:Trigger.ORB_PASSIVE_ENERGY,orb,old,next));
            if(!block && calmExitActions.contains(__instance) && next>old)
                mod().gameEvent(events->events.calmExitEnergy(old,next));
            AbstractOrb evoked=evokeActions.get(__instance);
            if(!block && evoked instanceof com.megacrit.cardcrawl.orbs.Plasma && next>old)
                mod().gameEvent(events->events.orbPassiveEffect(Trigger.PLASMA_EVOKE_ENERGY,evoked,old,next));
            if(__instance.isDone || next>old) evokeActions.remove(__instance);
            if(__instance.isDone || next>old) calmExitActions.remove(__instance);
            if(__instance.isDone || next>old) passiveActions.remove(__instance);
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.defect.LightningOrbPassiveAction.class,method="update")
    public static class LightningDamageSource {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if(call.getMethodName().equals("addToTop"))
                        call.replace("{ if ($1 instanceof com.megacrit.cardcrawl.actions.common.DamageAction || "
                                + "$1 instanceof com.megacrit.cardcrawl.actions.common.DamageAllEnemiesAction) "
                                + "battleaudio.patches.EventPatches.lightningDamageAction($1,this.orb); $proceed($$); }");
                }
            };
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.common.DamageAction.class,method="update")
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.common.DamageAllEnemiesAction.class,method="update")
    public static class LightningDamageSettlement {
        private static final Deque<Map<AbstractMonster,Integer>> before=new ArrayDeque<>();
        @SpirePrefixPatch public static void before(com.megacrit.cardcrawl.actions.AbstractGameAction __instance) {
            Map<AbstractMonster,Integer> health=new IdentityHashMap<>();
            if(lightningDamageActions.containsKey(__instance)) {
                if(__instance instanceof com.megacrit.cardcrawl.actions.common.DamageAction) {
                    if(__instance.target instanceof AbstractMonster) {
                        AbstractMonster target=(AbstractMonster)__instance.target;
                        health.put(target,target.currentHealth);
                    }
                } else if(AbstractDungeon.getMonsters()!=null) {
                    for(AbstractMonster target:AbstractDungeon.getMonsters().monsters) health.put(target,target.currentHealth);
                }
            }
            before.push(health);
        }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.actions.AbstractGameAction __instance) {
            Map<AbstractMonster,Integer> health=before.pop();
            AbstractOrb orb=lightningDamageActions.get(__instance);
            if(orb!=null && mod()!=null) for(Map.Entry<AbstractMonster,Integer> entry:health.entrySet()) {
                AbstractMonster target=entry.getKey(); int old=entry.getValue();
                if(target.currentHealth<old) mod().gameEvent(events->events.orbPassiveDamage(orb,target,old,target.currentHealth));
            }
            if(__instance.isDone) lightningDamageActions.remove(__instance);
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.cards.tempCards.Shiv.class,method="use")
    public static class ShivDamageSource {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() { @Override public void edit(MethodCall call) throws CannotCompileException {
                if(call.getMethodName().equals("addToBot"))
                    call.replace("{ if ($1 instanceof com.megacrit.cardcrawl.actions.common.DamageAction) battleaudio.patches.EventPatches.shivDamageAction($1); $proceed($$); }");
            }};
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.common.DamageAction.class,method="update")
    public static class ShivDamageSettlement {
        private static final Deque<Integer> oldHealth=new ArrayDeque<>();
        @SpirePrefixPatch public static void before(com.megacrit.cardcrawl.actions.common.DamageAction __instance) {
            oldHealth.push(__instance.target instanceof AbstractMonster ? __instance.target.currentHealth : -1);
        }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.actions.common.DamageAction __instance) {
            int old=oldHealth.pop();
            if(old>=0 && shivDamageActions.contains(__instance) && mod()!=null && __instance.target instanceof AbstractMonster)
                mod().gameEvent(events->events.shivDamage((AbstractMonster)__instance.target,old,__instance.target.currentHealth));
            if(__instance.isDone) shivDamageActions.remove(__instance);
        }
    }
    private static boolean showing(AbstractDungeon.CurrentScreen screen) {
        return AbstractDungeon.isScreenUp && AbstractDungeon.screen == screen;
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.shop.ShopScreen.class, method="open")
    public static class ShopOpen {
        private static final Deque<Boolean> previous = new ArrayDeque<>();
        @SpirePrefixPatch public static void before() { previous.push(showing(AbstractDungeon.CurrentScreen.SHOP)); }
        @SpirePostfixPatch public static void after() {
            if (!previous.pop() && showing(AbstractDungeon.CurrentScreen.SHOP)) event(Trigger.SHOP_OPEN);
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.shop.ShopScreen.class, method="purchaseCard")
    public static class ShopCardPurchase {
        private static final Deque<Integer> gold=new ArrayDeque<>();
        private static final Deque<Integer> effects=new ArrayDeque<>();
        private static final Deque<AbstractCard> cards=new ArrayDeque<>();
        @SpirePrefixPatch public static void before(com.megacrit.cardcrawl.shop.ShopScreen __instance,
                com.megacrit.cardcrawl.cards.AbstractCard card) {
            gold.push(AbstractDungeon.player==null?0:AbstractDungeon.player.gold);
            effects.push(AbstractDungeon.topLevelEffects.size());
            cards.push(card);
        }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.shop.ShopScreen __instance,
                com.megacrit.cardcrawl.cards.AbstractCard card) {
            int old=gold.pop(), start=effects.pop(); AbstractCard purchased=cards.pop();
            boolean obtained=false;
            for(int i=start;i<AbstractDungeon.topLevelEffects.size();i++)
                if(AbstractDungeon.topLevelEffects.get(i) instanceof com.megacrit.cardcrawl.vfx.FastCardObtainEffect)
                    obtained=true;
            if(obtained && mod()!=null && AbstractDungeon.player!=null)
                mod().gameEvent(events->events.shopBoughtCard(purchased,Math.max(0,old-AbstractDungeon.player.gold)));
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.shop.StoreRelic.class, method="purchaseRelic")
    public static class ShopRelicPurchase {
        private static final Deque<AbstractRelic> items=new ArrayDeque<>();
        private static final Deque<Boolean> owned=new ArrayDeque<>();
        private static final Deque<Integer> gold=new ArrayDeque<>();
        @SpirePrefixPatch public static void before(com.megacrit.cardcrawl.shop.StoreRelic __instance) {
            items.push(__instance.relic);
            owned.push(AbstractDungeon.getCurrRoom().relics.contains(__instance.relic));
            gold.push(AbstractDungeon.player.gold);
        }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.shop.StoreRelic __instance) {
            AbstractRelic item=items.pop(); boolean wasOwned=owned.pop(); int old=gold.pop();
            if(!wasOwned && AbstractDungeon.getCurrRoom().relics.contains(item) && mod()!=null)
                mod().gameEvent(events->events.shopBoughtRelic(item,Math.max(0,old-AbstractDungeon.player.gold)));
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.shop.StorePotion.class, method="purchasePotion")
    public static class ShopPotionPurchase {
        private static final Deque<com.megacrit.cardcrawl.potions.AbstractPotion> items=new ArrayDeque<>();
        private static final Deque<Boolean> owned=new ArrayDeque<>();
        private static final Deque<Integer> gold=new ArrayDeque<>();
        @SpirePrefixPatch public static void before(com.megacrit.cardcrawl.shop.StorePotion __instance) {
            items.push(__instance.potion);
            owned.push(AbstractDungeon.player.potions.contains(__instance.potion));
            gold.push(AbstractDungeon.player.gold);
        }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.shop.StorePotion __instance) {
            com.megacrit.cardcrawl.potions.AbstractPotion item=items.pop(); boolean wasOwned=owned.pop(); int old=gold.pop();
            if(!wasOwned && AbstractDungeon.player.potions.contains(item) && mod()!=null)
                mod().gameEvent(events->events.shopBoughtPotion(item,Math.max(0,old-AbstractDungeon.player.gold)));
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.shop.ShopScreen.class, method="updatePurge")
    public static class ShopPurgeCompleted {
        private static final Deque<List<AbstractCard>> selected=new ArrayDeque<>();
        @SpirePrefixPatch public static void before() {
            List<AbstractCard> present=new ArrayList<>();
            if(AbstractDungeon.player!=null && AbstractDungeon.gridSelectScreen!=null)
                for(AbstractCard card:AbstractDungeon.gridSelectScreen.selectedCards)
                    if(AbstractDungeon.player.masterDeck.group.contains(card)) present.add(card);
            selected.push(present);
        }
        @SpirePostfixPatch public static void after() {
            List<AbstractCard> cards=selected.pop();
            if(mod()==null || AbstractDungeon.player==null) return;
            for(AbstractCard card:cards) if(!AbstractDungeon.player.masterDeck.group.contains(card))
                mod().gameEvent(events->events.shopRemovedCard(card));
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.cards.Soul.class,method="obtain")
    public static class CurseObtained {
        private static final Deque<Boolean> owned=new ArrayDeque<>();
        @SpirePrefixPatch public static void before(com.megacrit.cardcrawl.cards.Soul __instance,AbstractCard card) {
            owned.push(AbstractDungeon.player!=null && AbstractDungeon.player.masterDeck.group.contains(card));
        }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.cards.Soul __instance,AbstractCard card) {
            boolean wasOwned=owned.pop();
            if(!wasOwned && card.type==AbstractCard.CardType.CURSE && AbstractDungeon.player!=null
                    && AbstractDungeon.player.masterDeck.group.contains(card) && mod()!=null)
                mod().gameEvent(events->events.gainedCurse(card));
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.screens.CardRewardScreen.class, method="open")
    @SpirePatch(clz=com.megacrit.cardcrawl.screens.CardRewardScreen.class, method="reopen")
    public static class CombatCardRewardOpen {
        private static final Deque<Boolean> previous = new ArrayDeque<>();
        @SpirePrefixPatch public static void before() { previous.push(showing(AbstractDungeon.CurrentScreen.CARD_REWARD)); }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.screens.CardRewardScreen __instance) {
            boolean wasOpen = previous.pop();
            if (wasOpen || !showing(AbstractDungeon.CurrentScreen.CARD_REWARD) || AbstractDungeon.currMapNode == null) return;
            com.megacrit.cardcrawl.rooms.AbstractRoom room = AbstractDungeon.getCurrRoom();
            com.megacrit.cardcrawl.rewards.RewardItem reward = __instance.rItem;
            if (room instanceof com.megacrit.cardcrawl.rooms.MonsterRoom && room.isBattleOver
                    && reward != null && reward.type == com.megacrit.cardcrawl.rewards.RewardItem.RewardType.CARD
                    && __instance.rewardGroup != null && __instance.rewardGroup.size() > 1
                    && (room.rewards.contains(reward) || AbstractDungeon.combatRewardScreen.rewards.contains(reward)))
                event(Trigger.COMBAT_CARD_REWARD_OPEN);
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.screens.CardRewardScreen.class, method="acquireCard")
    public static class CombatCardRewardPick {
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.screens.CardRewardScreen __instance, AbstractCard card) {
            rewardPicked(__instance,card);
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.screens.CardRewardScreen.class, method="skippedCards")
    public static class CombatCardRewardSkip {
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.screens.CardRewardScreen __instance) {
            rewardSkipped(__instance);
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.watcher.ChangeStanceAction.class, method="update")
    public static class Stance {
        private static final Deque<String> old = new ArrayDeque<>();
        @SpirePrefixPatch public static void before() { old.push(AbstractDungeon.player.stance.ID); }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.actions.watcher.ChangeStanceAction __instance) {
            String previous = old.pop(), next=AbstractDungeon.player.stance.ID;
            if (mod() != null) mod().gameEvent(events -> {
                events.stance(previous,next);
                if(!"Divinity".equals(previous) && "Divinity".equals(next) && mantraTransitions.remove(__instance))
                    events.mantraSpentDivinity(previous);
            });
            if(__instance.isDone) mantraTransitions.remove(__instance);
            observe();
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.utility.ScryAction.class, method="update")
    public static class Scry {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if (call.getMethodName().equals("open") && call.getClassName().endsWith("GridCardSelectScreen"))
                        call.replace("{ $proceed($$); battleaudio.patches.EventPatches.scryOpened(this,$1); battleaudio.patches.EventPatches.event(battleaudio.model.Config.Trigger.SCRY); }");
                }
            };
        }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.actions.utility.ScryAction __instance) {
            if(__instance.isDone) {
                ScrySelection selection=scryCards.remove(__instance);
                if(selection!=null && selection.confirmed && mod()!=null)
                    mod().gameEvent(events->events.scryed(selection.cards));
            }
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.screens.select.GridCardSelectScreen.class,method="update")
    public static class ScryConfirm {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() { @Override public void edit(MethodCall call) throws CannotCompileException {
                if(call.getMethodName().equals("closeCurrentScreen"))
                    call.replace("{ boolean open = com.megacrit.cardcrawl.dungeons.AbstractDungeon.screen == com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.GRID; $proceed($$); if (open) battleaudio.patches.EventPatches.scryConfirmed(this); }");
            }};
        }
    }
    public static void scryConfirmed(com.megacrit.cardcrawl.screens.select.GridCardSelectScreen screen) {
        if(AbstractDungeon.screen==AbstractDungeon.CurrentScreen.GRID) return;
        for(ScrySelection selection:scryCards.values())
            if(selection.group==screen.targetGroup) selection.confirmed=true;
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.unique.RestoreRetainedCardsAction.class, method="update")
    public static class Retain {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if (call.getMethodName().equals("onRetained")) call.replace("{ $proceed($$); battleaudio.patches.EventPatches.retained($0); }");
                }
            };
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.common.DiscardAtEndOfTurnAction.class, method="update")
    public static class RetainHand {
        private static final Deque<Boolean> wasDone = new ArrayDeque<>();
        @SpirePrefixPatch public static void before(com.megacrit.cardcrawl.actions.common.DiscardAtEndOfTurnAction __instance) { wasDone.push(__instance.isDone); }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.actions.common.DiscardAtEndOfTurnAction __instance) {
            boolean done = wasDone.pop();
            if (!done && __instance.isDone && (AbstractDungeon.player.hasRelic("Runic Pyramid") || AbstractDungeon.player.hasPower("Equilibrium")))
                for (AbstractCard card : AbstractDungeon.player.hand.group) retained(card);
        }
    }
}
