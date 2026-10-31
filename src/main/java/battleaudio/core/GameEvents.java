package battleaudio.core;

import battleaudio.model.Config.Trigger;
import battleaudio.model.EventContext;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.DamageInfo;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.orbs.*;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;
import java.util.*;

/** Receives actual mutations from patches; observation only detects value transitions. */
public final class GameEvents {
    private final RuleEngine engine;
    private final Map<AbstractCreature, Map<String, Integer>> powers = new IdentityHashMap<>();
    private final Set<AbstractCard> retained = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<AbstractCard> generated = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<AbstractMonster> killed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<com.megacrit.cardcrawl.rewards.RewardItem> resolvedRewards = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Object, Boolean> tracked = new IdentityHashMap<>();
    private int energy, block, slots;
    private boolean dead;
    public GameEvents(RuleEngine engine) { this.engine = engine; }
    public void reset() { powers.clear(); retained.clear(); generated.clear(); killed.clear(); resolvedRewards.clear(); tracked.clear(); dead = false; }
    public void baseline() { reset(); observe(true); }
    public void observe(boolean baseline) {
        AbstractPlayer p = AbstractDungeon.player;
        if (p == null) return;
        int nextEnergy = EnergyPanel.totalCount, nextBlock = p.currentBlock;
        if (!baseline && nextEnergy > energy) {
            int gained = nextEnergy - energy;
            energy = nextEnergy;
            engine.unfilteredEvent(Trigger.GAIN_ENERGY, null);
        }
        if (!baseline && nextBlock > block) engine.unfilteredEvent(Trigger.GAIN_BLOCK, null);
        energy = nextEnergy; block = nextBlock;
        engine.observe(Trigger.ENERGY_THRESHOLD, p, energy, 0, "", baseline);
        engine.observe(Trigger.BLOCK_THRESHOLD, p, block, 0, "", baseline);
        observePowers(p, baseline, null);
        int nextFocus = powerAmount(p, "Focus");
        engine.observe(Trigger.FOCUS_INCREASE, p, nextFocus, 0, "", baseline);
        engine.observe(Trigger.FOCUS_THRESHOLD, p, nextFocus, 0, "", baseline);
        engine.observe(Trigger.FOCUS_DECREASE, p, nextFocus, 0, "", baseline);
        engine.observe(Trigger.FOCUS_NEGATIVE, p, nextFocus, 0, "", baseline);
        engine.observe(Trigger.STRENGTH_THRESHOLD, p, powerAmount(p, "Strength"), 0, "", baseline);
        engine.observe(Trigger.MANTRA_THRESHOLD, p, powerAmount(p, "Mantra"), 0, "", baseline);
        if (!baseline && p.maxOrbs > slots) engine.event(Trigger.GAIN_ORB_SLOT, null);
        slots = p.maxOrbs;
        engine.observe(Trigger.ORB_SLOTS_FULL, p, slots > 0 && p.filledOrbCount() >= slots ? 1 : 0, 0, "", baseline);
        engine.observe(Trigger.ORB_SLOTS_UNFULL, p, slots > 0 && p.filledOrbCount() >= slots ? 1 : 0, 0, "", baseline);
        Set<Object> alive = Collections.newSetFromMap(new IdentityHashMap<>());
        if (AbstractDungeon.currMapNode != null && AbstractDungeon.getCurrRoom().monsters != null)
            for (AbstractMonster monster : AbstractDungeon.getCurrRoom().monsters.monsters) {
                alive.add(monster);
                observePowers(monster, baseline, null);
                EventContext enemy=new EventContext().target("ENEMY",monster.id,monster);
                enemy.enemyType=monster.type.name();
                engine.observe(Trigger.ENEMY_LOW_HEALTH, monster, monster.currentHealth, monster.maxHealth, "", baseline,enemy);
                engine.observe(Trigger.POISON_THRESHOLD, monster, powerAmount(monster, "Poison"), 0, "", baseline);
            }
        for (AbstractOrb orb : p.orbs) if (orb instanceof Dark) {
            alive.add(orb); engine.observe(Trigger.DARK_DAMAGE_THRESHOLD, orb, orb.evokeAmount, 0, "", baseline);
        }
        for (Object object : new ArrayList<>(tracked.keySet())) if (!alive.contains(object)) {
            engine.forget(object); tracked.remove(object);
            if (object instanceof AbstractCreature) powers.remove(object);
        }
        for (Object object : alive) tracked.put(object, true);
        if (!baseline && !dead && p.isDead) { dead = true; engine.death(); }
        if (baseline) dead = p.isDead;
    }
    public void observePowers(AbstractCreature creature, boolean baseline, AbstractCreature source) {
        Map<String, Integer> previous = powers.get(creature);
        if (previous == null) previous = new HashMap<>();
        Map<String, Integer> current = new HashMap<>();
        for (AbstractPower power : creature.powers) {
            current.put(power.ID, power.amount);
            Integer old = previous.get(power.ID);
            boolean gained = old == null || power.amount > old;
            if(!baseline && creature==AbstractDungeon.player && ("Strength".equals(power.ID)
                    || "Dexterity".equals(power.ID))) {
                int before=old==null?0:old;
                if(power.amount!=before) abilityChange(power.ID,before,power.amount,source);
            }
            if (!baseline && gained) {
                EventContext context=new EventContext().amount(power.amount-(old==null?0:old),old==null?0:old,power.amount)
                        .target(creature==AbstractDungeon.player?"PLAYER":"ENEMY",creature.id,creature);
                if (source!=null) context.source(source==AbstractDungeon.player?"PLAYER":"ENEMY",source.id);
                else context.source("UNKNOWN","");
                if (creature == AbstractDungeon.player) {
                    engine.event(Trigger.GAIN_SPECIFIED_POWER, power.ID,context);
                    if (power.type == AbstractPower.PowerType.DEBUFF) engine.event(Trigger.RECEIVE_DEBUFF, power.ID,context);
                    if (power.type == AbstractPower.PowerType.BUFF && (power.amount >= 0 || power.amount == -1 && !power.canGoNegative))
                        engine.event(Trigger.POSITIVE_POWER, power.ID,context);
                    if ("Regeneration".equals(power.ID) || "Regen".equals(power.ID)) engine.event(Trigger.GAIN_REGEN, power.ID);
                    if ("IntangiblePlayer".equals(power.ID) || "Intangible".equals(power.ID)) engine.event(Trigger.GAIN_INTANGIBLE, power.ID);
                } else if (source == AbstractDungeon.player) {
                    engine.event(Trigger.APPLY_ENEMY_POWER, power.ID,context);
                    if ("Poison".equals(power.ID)) engine.event(Trigger.APPLY_POISON, power.ID,context);
                }
            }
            if (creature == AbstractDungeon.player) engine.observe(Trigger.POWER_THRESHOLD, creature, power.amount, 0, power.ID, baseline);
        }
        if (creature == AbstractDungeon.player) for (String id : previous.keySet())
            if (!current.containsKey(id)) {
                engine.observe(Trigger.POWER_THRESHOLD, creature, 0, 0, id, baseline);
                if(!baseline && ("Strength".equals(id) || "Dexterity".equals(id)))
                    abilityChange(id,previous.get(id),0,source);
            }
        powers.put(creature, current);
    }
    private void abilityChange(String id,int before,int after,AbstractCreature source) {
        EventContext context=new EventContext().amount(Math.abs(after-before),before,after)
                .target("PLAYER",AbstractDungeon.player.id,AbstractDungeon.player);
        if(source!=null) context.source(source==AbstractDungeon.player?"PLAYER":"ENEMY",source.id);
        else context.source("UNKNOWN","");
        Trigger increase="Strength".equals(id)?Trigger.STRENGTH_INCREASE:Trigger.DEXTERITY_INCREASE;
        Trigger decrease="Strength".equals(id)?Trigger.STRENGTH_DECREASE:Trigger.DEXTERITY_DECREASE;
        if(after>before) engine.event(increase,null,context);
        if(after<before) engine.event(decrease,null,context);
        if("Strength".equals(id) && before>=0 && after<0) engine.event(Trigger.STRENGTH_NEGATIVE,null,context);
    }
    private int powerAmount(AbstractCreature creature, String id) { AbstractPower p = creature.getPower(id); return p == null ? 0 : p.amount; }
    private EventContext card(AbstractCard card) {
        return new EventContext().card(card.cardID,card.type.name(),card.costForTurn,card.upgraded,card.exhaust,
                generated.contains(card) ? Boolean.TRUE : null).target("CARD",card.cardID,card);
    }
    public void energyMutation(int old,int next) {
        if (next>old) {
            EventContext context=new EventContext().amount(next-old,old,next).target("PLAYER","",AbstractDungeon.player);
            engine.energyGained(next-old,context);
            engine.filteredEvent(Trigger.GAIN_ENERGY,null,context);
        }
    }
    public void blockMutation(AbstractCreature creature, int old, int next) {
        if(creature==AbstractDungeon.player) engine.lowHealthBlock(creature.currentHealth,creature.maxHealth,Math.max(0,next-old));
        if (creature==AbstractDungeon.player && next>old) {
            engine.filteredEvent(Trigger.GAIN_BLOCK,null,new EventContext().amount(next-old,old,next)
                    .target("PLAYER","",creature));
            if("Calm".equals(AbstractDungeon.player.stance.ID))
                engine.event(Trigger.CALM_GAIN_BLOCK,null,new EventContext().amount(next-old,old,next)
                        .target("PLAYER","",creature));
        }
    }
    public void goldMutation(int old,int next) {
        if(old==next) return;
        EventContext context=new EventContext().amount(Math.abs(next-old),old,next)
                .target("PLAYER","",AbstractDungeon.player);
        engine.event(next>old?Trigger.GAIN_GOLD:Trigger.SPEND_GOLD,null,context);
    }
    public void potionUsed(AbstractPotion potion) {
        engine.event(Trigger.USE_POTION,null,new EventContext().source("POTION",potion.ID)
                .target("PLAYER","",AbstractDungeon.player));
    }
    public void relicObtained(AbstractRelic relic) {
        engine.event(Trigger.GAIN_RELIC,null,new EventContext().source("RELIC",relic.relicId)
                .target("PLAYER","",AbstractDungeon.player));
    }
    public void powerDecreased(AbstractCreature target,String id,int old,int next,AbstractCreature source,boolean removed) {
        if(target!=AbstractDungeon.player || id==null || old<=next) return;
        EventContext context=new EventContext().amount(old-next,old,next).target("PLAYER",target.id,target);
        if(source==null) context.source("UNKNOWN","");
        else context.source(source==AbstractDungeon.player?"PLAYER":"ENEMY",source.id);
        engine.event(Trigger.POWER_DECREASE,id,context);
        if(removed) engine.event(Trigger.POWER_REMOVED,id,context);
    }
    public void shopBoughtCard(AbstractCard purchased,int spent) {
        EventContext context=card(purchased).source("SHOP","");
        context.amount=spent;
        engine.event(Trigger.SHOP_BUY_CARD,null,context);
    }
    public void shopBoughtRelic(AbstractRelic purchased,int spent) {
        engine.event(Trigger.SHOP_BUY_RELIC,null,new EventContext().source("RELIC",purchased.relicId)
                .target("PLAYER","",AbstractDungeon.player).amount(spent,0,spent));
    }
    public void shopBoughtPotion(AbstractPotion purchased,int spent) {
        engine.event(Trigger.SHOP_BUY_POTION,null,new EventContext().source("POTION",purchased.ID)
                .target("PLAYER","",AbstractDungeon.player).amount(spent,0,spent));
    }
    public void shopRemovedCard(AbstractCard removed) {
        engine.event(Trigger.SHOP_REMOVE_CARD,null,card(removed).source("SHOP","")
                .piles("MASTER","REMOVED"));
    }
    public void gainedCurse(AbstractCard curse) {
        engine.event(Trigger.GAIN_CURSE,null,card(curse).piles("","MASTER"));
    }
    public void regenHealed(AbstractCreature target,int oldHealth) {
        if(target!=AbstractDungeon.player || target.currentHealth<=oldHealth) return;
        engine.event(Trigger.REGEN_HEAL,null,new EventContext()
                .amount(target.currentHealth-oldHealth,oldHealth,target.currentHealth)
                .source("POWER","Regeneration").target("PLAYER",target.id,target));
    }
    public void intangibleMitigated(AbstractPlayer player,DamageInfo info) {
        EventContext context=new EventContext().amount(info.output-1,info.output,1)
                .target("PLAYER",player.id,player);
        if(info.owner==null) context.source("UNKNOWN","");
        else context.source(info.owner==player?"PLAYER":"ENEMY",info.owner.id);
        engine.event(Trigger.INTANGIBLE_MITIGATE,null,context);
    }
    public void rewardOutcome(com.megacrit.cardcrawl.rewards.RewardItem reward, AbstractCard card) {
        if(reward==null || !resolvedRewards.add(reward)) return;
        if(card==null) engine.event(Trigger.COMBAT_CARD_REWARD_SKIP,null);
        else engine.event(Trigger.COMBAT_CARD_REWARD_PICK,null,card(card));
    }
    public void turnStart() {
        retained.retainAll(AbstractDungeon.player.hand.group);
        engine.turnStart(); observe(false);
    }
    public void played(AbstractCard card) {
        if (card.dontTriggerOnUseCard) return;
        switch (card.type) {
            case ATTACK: engine.event(Trigger.PLAY_ATTACK, null,card(card)); break;
            case SKILL: engine.event(Trigger.PLAY_SKILL, null,card(card)); break;
            case POWER: engine.event(Trigger.PLAY_POWER, null,card(card)); break;
            default: break;
        }
        engine.count(Trigger.CARDS_PLAYED_TURN);
        if ("Shiv".equals(card.cardID)) {
            engine.event(Trigger.PLAY_SHIV, null,card(card));
            engine.count(Trigger.SHIV_PLAYED_TURN);
        }
        if (retained.remove(card)) engine.event(Trigger.PLAY_RETAINED_CARD, null,card(card));
    }
    public void drawn(AbstractCard card) { engine.event(Trigger.DRAW_CARD,null,card(card).piles("DRAW","HAND")); }
    public void returnedFromDiscard(AbstractCard card) {
        engine.event(Trigger.RETURN_DISCARD_CARD,null,card(card).piles("DISCARD","HAND"));
    }
    public void scryed(List<AbstractCard> viewed) {
        if(viewed==null || viewed.isEmpty()) return;
        int count=viewed.size();
        engine.amountThreshold(Trigger.SCRY_CARDS,count,new EventContext().amount(count,0,count).source("ACTION","Scry"));
        List<AbstractCard> top=new ArrayList<>();
        for(AbstractCard card:viewed) if(AbstractDungeon.player.drawPile.group.contains(card)) top.add(card);
        List<AbstractCard> pile=AbstractDungeon.player.drawPile.group;
        boolean retainedAtTop=pile.size()>=top.size();
        for(int i=pile.size()-top.size();retainedAtTop && i<pile.size();i++)
            if(!top.contains(pile.get(i))) retainedAtTop=false;
        for(AbstractCard card:viewed) {
            boolean discard=AbstractDungeon.player.discardPile.group.contains(card);
            boolean kept=AbstractDungeon.player.drawPile.group.contains(card);
            if(discard && !kept) engine.event(Trigger.SCRY_DISCARD_CARD,null,card(card).piles("DRAW","DISCARD"));
            else if(kept && !discard && retainedAtTop) engine.event(Trigger.SCRY_KEEP_CARD,null,card(card).piles("DRAW","DRAW"));
        }
    }
    public void discarded(boolean endTurn) {
        engine.unfilteredEvent(Trigger.DISCARD_CARD, null);
        if (!endTurn) engine.count(Trigger.DISCARDED_TURN);
    }
    public void discarded(AbstractCard card, String sourcePile, boolean endTurn) {
        EventContext context=card(card).piles(sourcePile,"DISCARD");
        engine.filteredEvent(Trigger.DISCARD_CARD,null,context);
        if (!endTurn) engine.filteredEvent(Trigger.DISCARDED_TURN,null,context);
    }
    public void exhausted(AbstractCard card) { exhausted(card,null); }
    public void exhausted(AbstractCard card,String sourcePile) {
        retained.remove(card); engine.event(Trigger.EXHAUST_CARD, null,card(card).piles(sourcePile,"EXHAUST")); engine.count(Trigger.EXHAUSTED_TURN);
    }
    public void generated(AbstractCard card,String destination) {
        if (!generated.add(card)) return;
        EventContext context=card(card).piles("",destination);
        engine.event(Trigger.CREATE_CARD, null,context);
        if ("Shiv".equals(card.cardID)) engine.event(Trigger.CREATE_SHIV, null,context);
    }
    public void retained(AbstractCard card) { retained.add(card); engine.event(Trigger.RETAIN_CARD, null,card(card)); }
    public void stance(String old, String next) {
        if (old.equals(next)) return;
        EventContext transition=new EventContext().states(old,next).source("STANCE",old).target("STANCE",next,AbstractDungeon.player);
        engine.event(Trigger.EXIT_STANCE, old,transition); engine.event(Trigger.STANCE_CHANGE,null,transition);
        engine.count(Trigger.STANCE_CHANGES_TURN);
        if ("Wrath".equals(next)) engine.event(Trigger.ENTER_WRATH, null,transition);
        if ("Calm".equals(next)) engine.event(Trigger.ENTER_CALM, null,transition);
        if ("Divinity".equals(next)) engine.event(Trigger.ENTER_DIVINITY, null,transition);
    }
    public void mantraSpentDivinity(String old) {
        engine.event(Trigger.MANTRA_SPENT_DIVINITY,null,new EventContext().amount(10,10,0)
                .source("POWER","Mantra").states(old,"Divinity").target("PLAYER",AbstractDungeon.player.id,AbstractDungeon.player));
    }
    public void damage(AbstractPlayer p, DamageInfo info, int oldHealth) {
        if (p.currentHealth < oldHealth && (info.owner == p || info.owner == null && info.type == DamageInfo.DamageType.HP_LOSS))
            engine.event(Trigger.SELF_DAMAGE, null,new EventContext().amount(oldHealth-p.currentHealth,oldHealth,p.currentHealth)
                    .source(info.owner==p?"PLAYER":"UNKNOWN","").target("PLAYER",p.id,p));
        observe(false);
    }
    public void enemyDamage(int amount,AbstractMonster target) {
        if(amount<=0 || AbstractDungeon.player==null) return;
        EventContext context=new EventContext().amount(amount,0,amount)
                .source("PLAYER",AbstractDungeon.player.id).target("ENEMY",target.id,target);
        engine.damage(amount,context);
        if("Wrath".equals(AbstractDungeon.player.stance.ID)) engine.event(Trigger.WRATH_DAMAGE,null,context);
        if("Divinity".equals(AbstractDungeon.player.stance.ID)) engine.event(Trigger.DIVINITY_DAMAGE,null,context);
    }
    public void shivDamage(AbstractMonster target,int old,int next) {
        if(target!=null && next<old) engine.event(Trigger.SHIV_DAMAGE,null,new EventContext().amount(old-next,old,next)
                .source("CARD","Shiv").target("ENEMY",target.id,target));
    }
    public void poisonResolved(AbstractCreature target, int beforeHealth, int beforePoison) {
        if (!(target instanceof AbstractMonster)) return;
        int afterHealth=Math.max(0,target.currentHealth);
        AbstractPower poison=target.getPower("Poison");
        int afterPoison=poison==null?0:poison.amount;
        if (beforeHealth>afterHealth) {
            EventContext context=new EventContext().amount(beforeHealth-afterHealth,beforeHealth,afterHealth)
                    .source("POWER","Poison").target("ENEMY",target.id,target);
            engine.event(Trigger.POISON_DAMAGE,null,context);
            if (beforeHealth>0 && afterHealth==0 && target.isDying) engine.event(Trigger.POISON_KILL,null,context);
        }
        if (beforePoison>afterPoison) engine.event(Trigger.POISON_DECREASE,null,
                new EventContext().amount(beforePoison-afterPoison,beforePoison,afterPoison)
                        .source("POWER","Poison").target("ENEMY",target.id,target));
    }
    public void killed(AbstractMonster monster) {
        if (monster.currentHealth <= 0 && !monster.escaped && !monster.halfDead && killed.add(monster)) {
            boolean last=true;
            if(AbstractDungeon.getMonsters()!=null) for(AbstractMonster other:AbstractDungeon.getMonsters().monsters)
                if(other!=monster && !other.isDead && !other.isDying && !other.escaped && !other.halfDead
                        && other.currentHealth>0) { last=false; break; }
            EventContext context=new EventContext().target("ENEMY",monster.id,monster);
            context.enemyType=monster.type.name(); context.lastEnemy=last;
            engine.event(Trigger.KILL_ENEMY,null,context);
        }
    }
    public void orb(Trigger trigger, AbstractOrb orb) {
        engine.event(trigger, orb.ID);
        if(trigger==Trigger.CHANNEL_ORB) engine.count(Trigger.CHANNEL_ORBS_TURN);
        observe(false);
    }
    public void orbPassiveEffect(Trigger trigger,AbstractOrb orb,int old,int next) {
        if(next<=old) return;
        engine.event(trigger,null,new EventContext().amount(next-old,old,next)
                .source("ORB",orb.ID).target("PLAYER","",AbstractDungeon.player));
    }
    public void orbPassiveDamage(AbstractOrb orb,AbstractMonster target,int old,int next) {
        if(orb==null || target==null || next>=old) return;
        engine.event(Trigger.ORB_PASSIVE_DAMAGE,null,new EventContext().amount(old-next,old,next)
                .source("ORB",orb.ID).target("ENEMY",target.id,target));
    }
    public void orbEvokeDamage(AbstractOrb orb,AbstractMonster target,int old,int next) {
        int loss=Math.max(0,old-Math.max(0,next));
        if(loss>0) engine.event(Trigger.DARK_EVOKE_DAMAGE,null,new EventContext().amount(loss,old,Math.max(0,next))
                .source("ORB",orb.ID).target("ENEMY",target.id,target));
    }
    public void calmExitEnergy(int old,int next) {
        if(next<=old) return;
        engine.event(Trigger.CALM_EXIT_ENERGY,null,new EventContext().amount(next-old,old,next)
                .source("STANCE","Calm").target("PLAYER","",AbstractDungeon.player));
    }
    public void fact(Trigger trigger,EventContext context) { engine.event(trigger,null,context); }
    public void deckCard(Trigger trigger,AbstractCard card,String sourceType,String sourceId) {
        engine.event(trigger,null,card(card).piles("","MASTER_DECK").source(sourceType,sourceId).amount(1,0,1));
    }
    public void mantraIncreased(int old,int gained,int next) {
        if(gained>0) engine.event(Trigger.MANTRA_INCREASE,null,new EventContext().amount(gained,old,next)
                .source("POWER","Mantra").target("PLAYER",AbstractDungeon.player.id,AbstractDungeon.player));
    }
    public void evoke(AbstractOrb orb,String cause,int position) {
        EventContext context=new EventContext().source("ORB",orb.ID).target("ORB",orb.ID,orb);
        context.evokeCause=cause; context.orbPosition=position;
        engine.event(Trigger.EVOKE_ORB,orb.ID,context); observe(false);
    }
    public void darkAccumulated(Dark orb, int previous) {
        engine.seed(Trigger.DARK_ACCUMULATE, orb, previous, 0, "");
        engine.observe(Trigger.DARK_ACCUMULATE, orb, orb.evokeAmount, 0, "", false);
        observe(false);
    }
}
