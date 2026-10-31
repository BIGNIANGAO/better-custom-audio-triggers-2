package battleaudio.patches;

import battleaudio.BattleAudioMod;
import battleaudio.model.Config.Trigger;
import battleaudio.model.EventContext;
import com.evacipated.cardcrawl.modthespire.lib.*;
import com.megacrit.cardcrawl.cards.*;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.events.AbstractEvent;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.rooms.*;
import com.megacrit.cardcrawl.screens.CardRewardScreen;
import com.megacrit.cardcrawl.actions.AbstractGameAction;
import javassist.CannotCompileException;
import javassist.expr.*;
import java.util.*;

/** Narrow, causal vanilla paths. Queuing an effect is never a completed deck outcome. */
public final class VerifiedEventPatches {
    private static BattleAudioMod mod() { return BattleAudioMod.instance; }
    private static final Map<AbstractCard,String> rewards=new WeakHashMap<>();
    private static final Map<AbstractCard,AbstractCard> copies=new WeakHashMap<>(), transforms=new WeakHashMap<>();
    private static final Set<AbstractCard> removedCards=Collections.newSetFromMap(new WeakHashMap<AbstractCard,Boolean>());
    private static final Set<AbstractGameAction> displacement=Collections.newSetFromMap(new WeakHashMap<AbstractGameAction,Boolean>());
    private static final Deque<String> evokeCauses=new ArrayDeque<>();
    private static final Set<AbstractEvent> openEvents=Collections.newSetFromMap(new WeakHashMap<AbstractEvent,Boolean>());
    public static void reset() { rewards.clear(); copies.clear(); transforms.clear(); displacement.clear(); evokeCauses.clear(); openEvents.clear(); removedCards.clear(); attackKills.clear(); }
    private static String eventId(AbstractEvent event) {
        try { return String.valueOf(event.getClass().getField("ID").get(null)); }
        catch(Exception ignored) { return event.getClass().getSimpleName(); }
    }
    private static void fact(Trigger trigger,EventContext context) {
        if(mod()!=null) mod().gameEvent(events->events.fact(trigger,context));
    }
    public static void copied(AbstractCard original,AbstractCard result) {
        if(AbstractDungeon.player!=null && AbstractDungeon.player.masterDeck.group.contains(original)) copies.put(result,original);
    }
    public static void overflow(AbstractGameAction action) {
        if(action instanceof com.megacrit.cardcrawl.actions.defect.EvokeOrbAction) displacement.add(action);
    }
    public static String evokeCause() { return evokeCauses.isEmpty()?"ACTIVE":evokeCauses.peek(); }

    @SpirePatch(clz=EventRoom.class,method="onPlayerEntry",paramtypez={})
    public static class EventRoomEntered {
        @SpirePostfixPatch public static void after(EventRoom __instance) {
            if(AbstractDungeon.currMapNode==null) return;
            AbstractEvent event=__instance.event;
            if(event!=null && openEvents.add(event))
                fact(Trigger.EVENT_OPEN,new EventContext().source("EVENT",eventId(event)).target("EVENT",eventId(event),event));
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.events.exordium.BigFish.class,method="buttonEffect",paramtypez={int.class})
    public static class BigFishCompleted {
        private static final Deque<String> old=new ArrayDeque<>();
        @SpirePrefixPatch public static void before(com.megacrit.cardcrawl.events.exordium.BigFish __instance,int buttonPressed,Object ___screen) { old.push(String.valueOf(___screen)); }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.events.exordium.BigFish __instance,int buttonPressed,Object ___screen) {
            String before=old.pop();
            // The third option queues a curse/relic sequence. It is deliberately NOT called complete here.
            if("INTRO".equals(before) && !before.equals(String.valueOf(___screen)) && (buttonPressed==0 || buttonPressed==1))
                fact(Trigger.EVENT_OPTION_COMPLETED,new EventContext().source("EVENT",eventId(__instance))
                        .states(Integer.toString(buttonPressed),"COMPLETED").target("EVENT",eventId(__instance),__instance));
        }
    }
    @SpirePatch(clz=AbstractDungeon.class,method="nextRoomTransition",paramtypez={})
    public static class MapEntered {
        private static final Deque<Integer> floors=new ArrayDeque<>();
        @SpirePrefixPatch public static void before() { floors.push(AbstractDungeon.floorNum); }
        @SpirePostfixPatch public static void after() {
            int before=floors.pop(); MapRoomNode node=AbstractDungeon.currMapNode;
            if(node!=null && node.room!=null && AbstractDungeon.floorNum>before)
                fact(Trigger.MAP_NODE_ENTER,new EventContext().amount(1,before,AbstractDungeon.floorNum)
                        .source("MAP",AbstractDungeon.id).target("ROOM",node.room.getClass().getSimpleName(),node)
                        .states("",node.x+","+node.y));
        }
    }
    @SpirePatch(clz=CardCrawlGame.class,method="getDungeon",paramtypez={String.class,AbstractPlayer.class})
    public static class ActEntered {
        private static final Deque<Integer> acts=new ArrayDeque<>();
        @SpirePrefixPatch public static void before() { acts.push(AbstractDungeon.actNum); }
        @SpirePostfixPatch public static void after(CardCrawlGame __instance) {
            int before=acts.pop();
            if(before>0 && AbstractDungeon.actNum>before)
                fact(Trigger.ACT_ENTER,new EventContext().amount(1,before,AbstractDungeon.actNum)
                        .target("ACT",AbstractDungeon.id,__instance));
        }
    }
    @SpirePatch(clz=CardRewardScreen.class,method="acquireCard")
    public static class RewardQueued {
        @SpirePostfixPatch public static void after(CardRewardScreen __instance,AbstractCard card) {
            if(AbstractDungeon.currMapNode==null || card==null) return;
            AbstractRoom room=AbstractDungeon.getCurrRoom();
            if(room instanceof MonsterRoom && room.isBattleOver && __instance.rItem!=null
                    && __instance.rItem.type==com.megacrit.cardcrawl.rewards.RewardItem.RewardType.CARD)
                rewards.put(card,"COMBAT_REWARD");
        }
    }
    @SpirePatch(clz=Soul.class,method="obtain")
    public static class DeckObtained {
        private static final Deque<Boolean> owned=new ArrayDeque<>();
        @SpirePrefixPatch public static void before(Soul __instance,AbstractCard card) {
            owned.push(AbstractDungeon.player!=null && AbstractDungeon.player.masterDeck.group.contains(card));
        }
        @SpirePostfixPatch public static void after(Soul __instance,AbstractCard card) {
            boolean before=owned.pop();
            if(before || AbstractDungeon.player==null || !AbstractDungeon.player.masterDeck.group.contains(card)) return;
            String reward=rewards.remove(card); AbstractCard copy=copies.remove(card), original=transforms.remove(card);
            if(mod()==null) return;
            if(reward!=null) mod().gameEvent(events->events.deckCard(Trigger.COMBAT_CARD_REWARD_OBTAIN,card,"REWARD",reward));
            if(copy!=null) mod().gameEvent(events->events.deckCard(Trigger.COPY_DECK_CARD,card,"EVENT","Duplicator"));
            if(original!=null && !AbstractDungeon.player.masterDeck.group.contains(original))
                mod().gameEvent(events->events.deckCard(Trigger.TRANSFORM_DECK_CARD,card,"CARD",original.cardID));
        }
    }
    @SpirePatch(clz=CardGroup.class,method="removeCard",paramtypez={AbstractCard.class})
    @SpirePatch(clz=CardGroup.class,method="removeCard",paramtypez={String.class})
    public static class DeckRemoved {
        private static final Deque<List<AbstractCard>> before=new ArrayDeque<>();
        private static final Deque<Set<AbstractCard>> announced=new ArrayDeque<>();
        @SpirePrefixPatch public static void prefix(CardGroup __instance) {
            announced.push(Collections.newSetFromMap(new IdentityHashMap<AbstractCard,Boolean>()));
            before.push(AbstractDungeon.player!=null && __instance==AbstractDungeon.player.masterDeck?
                    new ArrayList<>(__instance.group):new ArrayList<>());
        }
        @SpirePostfixPatch public static void after(CardGroup __instance) {
            List<AbstractCard> previous=before.pop(); Set<AbstractCard> nested=announced.pop();
            for(AbstractCard card:previous) if(!__instance.group.contains(card) && !nested.contains(card)) {
                removedCards.add(card); for(Set<AbstractCard> parent:announced) parent.add(card);
                if(mod()!=null) mod().gameEvent(events->events.deckCard(Trigger.REMOVE_DECK_CARD,card,"UNKNOWN",""));
            }
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.events.shrines.Duplicator.class,method="update")
    public static class CopyQueued {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() { @Override public void edit(MethodCall call) throws CannotCompileException {
                if(call.getMethodName().equals("makeStatEquivalentCopy"))
                    call.replace("{ $_ = $proceed($$); battleaudio.patches.VerifiedEventPatches.copied($0,$_); }");
            }};
        }
    }
    @SpirePatch(clz=AbstractDungeon.class,method="transformCard",paramtypez={AbstractCard.class,boolean.class,com.megacrit.cardcrawl.random.Random.class})
    public static class TransformSelected {
        @SpirePostfixPatch public static void after(AbstractCard c) {
            if(AbstractDungeon.player!=null && (AbstractDungeon.player.masterDeck.group.contains(c) || removedCards.contains(c)) && AbstractDungeon.transformedCard!=null) transforms.put(AbstractDungeon.transformedCard,c);
        }
    }
    @SpirePatch(clz=AbstractPlayer.class,method="channelOrb")
    public static class OverflowSource {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() { @Override public void edit(MethodCall call) throws CannotCompileException {
                if((call.getMethodName().equals("addToTop") || call.getMethodName().equals("addToBottom"))
                        && call.getClassName().equals("com.megacrit.cardcrawl.actions.GameActionManager"))
                    call.replace("{ battleaudio.patches.VerifiedEventPatches.overflow($1); $proceed($$); }");
            }};
        }
    }
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.defect.EvokeOrbAction.class,method="update")
    public static class OverflowExecution {
        @SpirePrefixPatch public static void before(com.megacrit.cardcrawl.actions.defect.EvokeOrbAction __instance) {
            evokeCauses.push(displacement.contains(__instance)?"OVERFLOW":"ACTIVE");
        }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.actions.defect.EvokeOrbAction __instance) {
            evokeCauses.pop(); if(__instance.isDone) displacement.remove(__instance);
        }
    }
    // Inspect the exact DamageAllEnemiesAction call, not neighboring kill callbacks or turn totals.
    @SpirePatch(clz=com.megacrit.cardcrawl.actions.common.DamageAllEnemiesAction.class,method="update")
    public static class AttackMultiKill {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() { @Override public void edit(MethodCall call) throws CannotCompileException {
                if(call.getMethodName().equals("damage"))
                    call.replace("{ boolean alive = $0.currentHealth > 0 && !$0.isDying && !$0.isDead && !$0.escaped && !$0.halfDead; $proceed($$); battleaudio.patches.VerifiedEventPatches.attackDeath(this,$0,$1,alive); }");
            }};
        }
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.actions.common.DamageAllEnemiesAction __instance) {
            if(__instance.isDone) { Set<AbstractMonster> kills=attackKills.remove(__instance); if(kills!=null && kills.size()>=2)
                fact(Trigger.ATTACK_MULTI_KILL,new EventContext().amount(kills.size(),0,kills.size())
                        .source("PLAYER",AbstractDungeon.player.id).target("ACTION","DamageAllEnemiesAction",__instance)); }
        }
    }
    private static final Map<AbstractGameAction,Set<AbstractMonster>> attackKills=new WeakHashMap<>();
    public static void attackDeath(AbstractGameAction action,com.megacrit.cardcrawl.core.AbstractCreature target,DamageInfo info,boolean alive) {
        if(alive && info.owner==AbstractDungeon.player && info.type==DamageInfo.DamageType.NORMAL && target instanceof AbstractMonster
                && target.currentHealth<=0 && target.isDying && !((AbstractMonster)target).halfDead && !((AbstractMonster)target).escaped)
            attackKills.computeIfAbsent(action,unused->Collections.newSetFromMap(new IdentityHashMap<>())).add((AbstractMonster)target);
    }
}
