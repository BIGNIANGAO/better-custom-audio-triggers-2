package battleaudio.model;

import battleaudio.model.Config.Trigger;
import java.util.*;

/** One event definition per trigger; character tabs are views over those definitions. */
public final class Events {
    public enum Category { GENERAL, IRONCLAD, SILENT, DEFECT, WATCHER }
    public static final String[] CATEGORY_NAMES = {"通用", "铁甲战士", "静默猎手", "故障机器人", "观者"};
    public static final class Definition {
        public final Trigger trigger;
        public final String group, title;
        public final boolean threshold;
        public final String filter;
        public final EnumSet<Category> categories;
        private Definition(Trigger trigger, String group, String title, boolean threshold, String filter, Category... categories) {
            this.trigger = trigger; this.group = group; this.title = title; this.threshold = threshold; this.filter = filter;
            this.categories = EnumSet.copyOf(Arrays.asList(categories));
        }
    }
    private static final Map<Trigger, Definition> ALL = new LinkedHashMap<>();
    private static void add(Trigger trigger, String group, String title, boolean threshold, String filter, Category... categories) {
        ALL.put(trigger, new Definition(trigger, group, title, threshold, filter, categories));
    }
    static {
        Category g = Category.GENERAL, i = Category.IRONCLAD, s = Category.SILENT, d = Category.DEFECT, w = Category.WATCHER;
        add(Trigger.BATTLE_START,"战斗","每场战斗开始",false,"",g);
        add(Trigger.FIRST_BATTLE,"战斗","首战开始",false,"",g);
        add(Trigger.NORMAL_BATTLE_START,"战斗","普通小怪房战斗开始",false,"",g);
        add(Trigger.ELITE_START,"战斗","精英战开始",false,"",g);
        add(Trigger.BOSS_START,"战斗","首领战开始",false,"",g);
        add(Trigger.BATTLE_VICTORY,"战斗","战斗胜利",false,"",g);
        add(Trigger.NORMAL_BATTLE_VICTORY,"战斗","普通小怪房战斗胜利",false,"",g);
        add(Trigger.ELITE_VICTORY,"战斗","精英战斗胜利",false,"",g);
        add(Trigger.BOSS_VICTORY,"战斗","任意首领战斗胜利",false,"",g);
        add(Trigger.PLAYER_DEATH,"战斗","玩家死亡",false,"",g);

        add(Trigger.TURN_START,"回合","玩家回合开始",false,"",g);
        add(Trigger.TURN_END,"回合","玩家回合结束",false,"",g);

        add(Trigger.HP_LOSS,"生命与升级","玩家实际失去生命",false,"",g);
        add(Trigger.LOW_HEALTH,"生命与升级","低血量 ≤ 阈值",true,"legacy-health",g);
        add(Trigger.HIGH_HEALTH,"生命与升级","高血量 ≥ 阈值",true,"legacy-health",g);
        add(Trigger.HEAL_THRESHOLD,"生命与升级","回血达到阈值",true,"legacy-health",g);
        add(Trigger.BLOCK_LOSS,"生命与升级","扣盾",false,"",g);
        add(Trigger.CARD_UPGRADE,"生命与升级","升级卡牌",false,"",g);

        add(Trigger.SELF_DAMAGE,"生命","因自身效果或无法确认来源而失去生命",false,"",i);

        add(Trigger.PLAY_ATTACK,"打牌","打出攻击牌",false,"card",g);
        add(Trigger.PLAY_SKILL,"打牌","打出技能牌",false,"card",g);
        add(Trigger.PLAY_POWER,"打牌","打出能力牌",false,"card",g);
        add(Trigger.CARDS_PLAYED_TURN,"打牌","本回合打牌数量达到阈值",true,"",g,s);

        add(Trigger.DRAW_CARD,"牌堆","抽牌",false,"card",g);
        add(Trigger.DISCARD_CARD,"牌堆","弃牌",false,"card",g,s);
        add(Trigger.DISCARDED_TURN,"牌堆","本回合弃牌达到指定张数",true,"",s);
        add(Trigger.EXHAUST_CARD,"牌堆","消耗卡牌",false,"card",g,i);
        add(Trigger.EXHAUSTED_TURN,"牌堆","本回合消耗达到指定张数",true,"",i);
        add(Trigger.RETURN_DISCARD_CARD,"牌堆","从弃牌堆取回卡牌到手牌",false,"card",g,s);
        add(Trigger.RESHUFFLE,"牌堆","弃牌堆洗回抽牌堆",false,"",g);
        add(Trigger.CREATE_CARD,"牌堆","生成卡牌",false,"card",g);
        add(Trigger.GAIN_CURSE,"牌堆","实际获得诅咒",false,"card",g);
        add(Trigger.SCRY,"牌堆","占卜",false,"",w);
        add(Trigger.SCRY_CARDS,"牌堆","单次占卜至少 X 张",true,"",w);
        add(Trigger.SCRY_DISCARD_CARD,"牌堆","占卜后将卡牌置入弃牌堆",false,"card",w);
        add(Trigger.SCRY_KEEP_CARD,"牌堆","占卜后卡牌留在抽牌堆顶",false,"card",w);
        add(Trigger.RETAIN_CARD,"牌堆","保留卡牌到下一回合",false,"",w);
        add(Trigger.PLAY_RETAINED_CARD,"牌堆","打出上回合保留的卡牌",false,"",w);

        add(Trigger.GAIN_ENERGY,"资源","获得能量",false,"",g);
        add(Trigger.ENERGY_GAIN_THRESHOLD,"资源","玩家单次获得能量 ≥ X",true,"",g);
        add(Trigger.ENERGY_THRESHOLD,"资源","能量达到阈值",true,"",g);
        add(Trigger.GAIN_BLOCK,"资源","获得格挡",false,"",g);
        add(Trigger.BLOCK_THRESHOLD,"资源","格挡达到阈值",true,"",g);
        add(Trigger.LOW_HEALTH_FIRST_BLOCK,"资源","低血量期间首次实际获得格挡",true,"health",g,i);
        add(Trigger.GAIN_GOLD,"资源","实际获得金币",false,"",g);
        add(Trigger.SPEND_GOLD,"资源","实际花费金币",false,"",g);
        add(Trigger.USE_POTION,"资源","使用药水",false,"",g);
        add(Trigger.GAIN_RELIC,"资源","实际获得遗物",false,"",g);

        add(Trigger.POSITIVE_POWER,"状态","获得或增加正面能力",false,"legacy-power",g);
        add(Trigger.GAIN_SPECIFIED_POWER,"状态","获得指定能力",false,"power",g);
        add(Trigger.POWER_THRESHOLD,"状态","指定能力达到层数",true,"power",g);
        add(Trigger.POWER_DECREASE,"状态","玩家指定能力层数减少",false,"power",g);
        add(Trigger.POWER_REMOVED,"状态","玩家指定能力被移除",false,"power",g);
        add(Trigger.RECEIVE_DEBUFF,"状态","受到负面状态",false,"",g);
        add(Trigger.STRENGTH_INCREASE,"状态","力量实际增加",false,"",i);
        add(Trigger.STRENGTH_THRESHOLD,"状态","力量达到指定值",true,"",i);
        add(Trigger.STRENGTH_DECREASE,"状态","力量实际减少",false,"",i);
        add(Trigger.STRENGTH_NEGATIVE,"状态","力量从非负变为负数",false,"",i);
        add(Trigger.GAIN_REGEN,"状态","获得临时生命恢复能力",false,"",i);
        add(Trigger.REGEN_HEAL,"状态","再生实际恢复生命",false,"",i);
        add(Trigger.DEXTERITY_INCREASE,"状态","敏捷实际增加",false,"",s);
        add(Trigger.DEXTERITY_DECREASE,"状态","敏捷实际减少",false,"",s);
        add(Trigger.GAIN_INTANGIBLE,"状态","获得无实体",false,"",s);
        add(Trigger.INTANGIBLE_MITIGATE,"状态","无实体实际压低伤害",false,"",s);
        add(Trigger.MANTRA_INCREASE,"状态","实际获得/增加真言（含随即消耗）",false,"",w);
        add(Trigger.MANTRA_THRESHOLD,"状态","真言达到指定层数",true,"",w);
        add(Trigger.MANTRA_SPENT_DIVINITY,"状态","消耗真言并实际进入神格",false,"",w);

        add(Trigger.APPLY_ENEMY_POWER,"敌人","给敌人施加指定状态",false,"power",g);
        add(Trigger.DAMAGE_THRESHOLD,"敌人","单次实际伤害 ≥ 阈值",true,"damage",g);
        add(Trigger.ENEMY_LOW_HEALTH,"敌人","敌人血量 ≤ 阈值",true,"health",g);
        add(Trigger.KILL_ENEMY,"敌人","击杀敌人",false,"",g,i);
        add(Trigger.ATTACK_MULTI_KILL,"敌人","同一玩家攻击动作击杀至少两敌",false,"",g,i);

        add(Trigger.ACT_ENTER,"场景","实际进入新章节（非读档）",false,"scene",g);
        add(Trigger.MAP_NODE_ENTER,"场景","实际进入新地图节点",false,"scene",g);
        add(Trigger.EVENT_OPEN,"场景","进入？房间时",false,"scene",g);
        add(Trigger.EVENT_OPTION_COMPLETED,"场景","完成事件即时选项（大鱼回血/最大生命）",false,"scene",g);

        add(Trigger.CAMPFIRE_REST,"探索","篝火实际休息／睡觉",false,"",g);
        add(Trigger.CAMPFIRE_SMITH,"探索","篝火完成锻造／升级卡牌",false,"",g);
        add(Trigger.CHEST_OPEN,"探索","实际打开宝箱",false,"",g);

        add(Trigger.SHOP_BUY_CARD,"商店","成功购买卡牌",false,"card",g);
        add(Trigger.SHOP_BUY_RELIC,"商店","成功购买遗物",false,"",g);
        add(Trigger.SHOP_BUY_POTION,"商店","成功购买药水",false,"",g);
        add(Trigger.SHOP_REMOVE_CARD,"商店","在商店实际移除卡牌",false,"card",g);

        add(Trigger.SHOP_OPEN,"界面","打开商人交易界面",false,"",g);
        add(Trigger.COMBAT_CARD_REWARD_OPEN,"界面","打开战斗奖励选牌界面",false,"",g);
        add(Trigger.COMBAT_CARD_REWARD_PICK,"界面","选择战斗奖励卡",false,"card",g);
        add(Trigger.COMBAT_CARD_REWARD_SKIP,"界面","跳过战斗奖励卡",false,"",g);

        add(Trigger.COMBAT_CARD_REWARD_OBTAIN,"奖励","战斗奖励卡实际加入牌组",false,"card",g);

        add(Trigger.REMOVE_DECK_CARD,"牌组","实际移除牌组卡牌（含变换前移除）",false,"card",g);
        add(Trigger.COPY_DECK_CARD,"牌组","复制卡实际加入牌组（复制祭坛）",false,"card",g);
        add(Trigger.TRANSFORM_DECK_CARD,"牌组","变换后的卡实际加入牌组",false,"card",g);

        add(Trigger.CREATE_SHIV,"小刀","生成小刀",false,"",s);
        add(Trigger.PLAY_SHIV,"小刀","打出小刀",false,"",s);
        add(Trigger.SHIV_PLAYED_TURN,"小刀","本回合打出第 X 把小刀",true,"",s);
        add(Trigger.SHIV_DAMAGE,"小刀","小刀实际造成伤害",false,"",s);

        add(Trigger.APPLY_POISON,"毒","给敌人施加毒",false,"",s);
        add(Trigger.POISON_THRESHOLD,"毒","任意敌人的毒达到指定层数",true,"",s);
        add(Trigger.POISON_DAMAGE,"毒","毒实际造成伤害",false,"",s);
        add(Trigger.POISON_KILL,"毒","毒导致敌人死亡",false,"",s);
        add(Trigger.POISON_DECREASE,"毒","毒层数实际减少或移除",false,"",s);

        add(Trigger.CHANNEL_ORB,"充能球","生成充能球",false,"orb",d);
        add(Trigger.CHANNEL_ORBS_TURN,"充能球","本回合生成球数达到阈值",true,"",d);
        add(Trigger.GAIN_ORB_SLOT,"充能球","增加球槽",false,"",d);
        add(Trigger.ORB_SLOTS_FULL,"充能球","球槽从未满变为满",false,"",d);
        add(Trigger.ORB_SLOTS_UNFULL,"充能球","球槽从满变为未满",false,"",d);
        add(Trigger.ORB_PASSIVE,"充能球","触发充能球被动",false,"orb",d);
        add(Trigger.ORB_PASSIVE_DAMAGE,"充能球","闪电被动实际造成伤害",false,"",d);
        add(Trigger.ORB_PASSIVE_BLOCK,"充能球","冰霜被动实际获得格挡",false,"",d);
        add(Trigger.ORB_PASSIVE_ENERGY,"充能球","等离子被动实际获得能量",false,"",d);
        add(Trigger.DARK_ACCUMULATE,"充能球","黑暗球蓄积一次",false,"",d);
        add(Trigger.DARK_DAMAGE_THRESHOLD,"充能球","任意黑暗球蓄积伤害达到指定值",true,"",d);
        add(Trigger.EVOKE_ORB,"充能球","激发充能球（包括满槽挤出）",false,"orb",d);
        add(Trigger.DARK_EVOKE_DAMAGE,"充能球","黑暗球激发实际造成伤害",false,"",d);
        add(Trigger.PLASMA_EVOKE_ENERGY,"充能球","等离子激发实际获得能量",false,"",d);

        add(Trigger.FOCUS_INCREASE,"集中","集中增加",false,"",d);
        add(Trigger.FOCUS_THRESHOLD,"集中","集中达到指定值",true,"",d);
        add(Trigger.FOCUS_DECREASE,"集中","集中实际下降",false,"",d);
        add(Trigger.FOCUS_NEGATIVE,"集中","集中从非负变为负数",false,"",d);

        add(Trigger.STANCE_CHANGE,"姿态","实际切换姿态（包括普通姿态）",false,"",w);
        add(Trigger.STANCE_CHANGES_TURN,"姿态","本回合切换姿态达到指定次数",true,"",w);
        add(Trigger.EXIT_STANCE,"姿态","退出指定姿态",false,"stance",w);
        add(Trigger.ENTER_CALM,"姿态","进入平静",false,"",w);
        add(Trigger.CALM_GAIN_BLOCK,"姿态","平静状态下实际获得格挡",false,"",w);
        add(Trigger.CALM_EXIT_ENERGY,"姿态","离开平静后实际获得能量",false,"",w);
        add(Trigger.ENTER_WRATH,"姿态","进入愤怒",false,"",w);
        add(Trigger.WRATH_DAMAGE,"姿态","愤怒状态下对敌人实际造成伤害",false,"",w);
        add(Trigger.ENTER_DIVINITY,"姿态","进入神格",false,"",w);
        add(Trigger.DIVINITY_DAMAGE,"姿态","神格状态下对敌人实际造成伤害",false,"",w);
    }
    public static Collection<Definition> definitions() { return Collections.unmodifiableCollection(ALL.values()); }
    public static Definition get(Trigger trigger) { return ALL.get(trigger); }
    public static boolean isCounter(Trigger trigger) {
        return trigger == Trigger.CARDS_PLAYED_TURN || trigger == Trigger.EXHAUSTED_TURN
                || trigger == Trigger.DISCARDED_TURN || trigger == Trigger.STANCE_CHANGES_TURN
                || trigger == Trigger.CHANNEL_ORBS_TURN || trigger == Trigger.SHIV_PLAYED_TURN;
    }
    public static boolean requiresId(Trigger trigger) {
        Definition definition = get(trigger);
        return definition != null && (definition.filter.equals("power") || definition.filter.equals("stance"));
    }
}
