package com.etbs31.mnemosyne;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 忆海 · Mnemosyne 的配置定义。
 *
 * <p><b>文件归属</b>：{@link MnemosyneMod} / {@code Config.java} 属于 <b>WS-0（基础设施）</b>。
 * 其他工作流只读；要新增配置项请走接口变更申请（{@code docs/tech/10_多线程开发规范.md} §五）。
 *
 * <p><b>键名契约</b>：以下键名与默认值逐条对应 {@code docs/tech/03_法术实现规范.md} §十一「配置项清单」，
 * 已冻结，下游工作流（WS-B 忆格 / WS-D 法术 / WS-E 遗忘 / WS-I HUD）直接引用静态字段。
 *
 * <p>读取示例：
 * <pre>{@code
 * int base = Config.Engram.BASE_SLOTS.get();          // 服务端配置
 * boolean hud = Config.Hud.ENABLED.get();             // 客户端配置
 * }</pre>
 *
 * <p><b>注意</b>：{@code SERVER} 型配置在客户端单机 / 联机时都由服务端权威决定，
 * 不要在客户端逻辑里直接读 {@code SERVER_SPEC} 的值来做渲染判断。
 */
public final class Config {

    private Config() {}

    /** SERVER 侧配置（影响游戏平衡，由服务端权威）。 */
    public static final ForgeConfigSpec SERVER_SPEC;
    /** CLIENT 侧配置（纯本地显示，不影响平衡）。 */
    public static final ForgeConfigSpec CLIENT_SPEC;

    static {
        // 忆格 / 法术 / 遗忘 / 世界生成 / 战利品共用一个 SERVER SPEC，
        // HUD 相关项单独一个 CLIENT SPEC。
        SERVER_SPEC = buildServer();
        CLIENT_SPEC = buildClient();
    }

    // =====================================================================
    // SERVER 侧
    // =====================================================================

    private static ForgeConfigSpec buildServer() {
        final ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("忆格（Engram）系统 —— 记忆的容量、腐坏与共鸣").push("engram");
        Engram.BASE_SLOTS = b
                .comment("基础忆格数量。玩家默认能同时承载的记忆条数。")
                .defineInRange("baseSlots", 3, 0, 5);
        Engram.MAX_SLOTS = b
                .comment("""
                        常驻忆格硬上限。装备与增益只能把容量推到这一值，不可突破。
                        ⚠️ 2026-09-18 由 5 提到 8：设计文档 v2 的终局是「忆海法典 12 槽」，
                        而临时忆格（碎忆 +1 / 忆格扩张 +2~4 / 忆海 +3）需要有余量可推。
                        设为 5 时临时忆格经常撞顶，等于白给。""")
                .defineInRange("maxSlots", 8, 1, 12);
        Engram.DECAY_SECONDS = b
                .comment("记忆基础有效期（秒）。到期后记忆腐坏消散。")
                .defineInRange("decaySeconds", 120, 1, 3600);
        Engram.RESONANCE_PER_SLOT = b
                .comment("每占用 1 格忆格提供的法术强度加成（0.20 = +20%）。")
                .defineInRange("resonancePerSlot", 0.20D, 0.0D, 1.0D);
        Engram.CAST_SPEED_PENALTY_PER_SLOT = b
                .comment("每占用 1 格忆格带来的施法速度惩罚（0.08 = -8%）。")
                .defineInRange("castSpeedPenaltyPerSlot", 0.08D, 0.0D, 1.0D);
        Engram.MANA_PENALTY_PER_SLOT = b
                .comment("每占用 1 格忆格带来的法力消耗惩罚（0.10 = +10%）。")
                .defineInRange("manaPenaltyPerSlot", 0.10D, 0.0D, 1.0D);
        Engram.PERMANENT_FREE_OF_RESONANCE = b
                .comment("""
                        永久忆格是否免疫共鸣惩罚（占用不计入施法速度/法力惩罚）。
                        设计文档 v2 §十 建议默认开 true —— 否则「法袍 4 件套」与
                        「5 级记忆掠夺」这两条关于"永久记忆"的价值会被共鸣惩罚吃掉。""")
                .define("permanentFreeOfResonance", true);
        b.pop();

        b.comment("""
                认知过载（Cognitive Overload）—— 忆海的核心 debuff。
                层数用状态效果的 amplifier 承载（amplifier + 1 = 层数）。""").push("overload");
        Overload.DAMAGE_PER_STACK = b
                .comment("每层认知过载使目标受到的忆海法术伤害提高的比例（0.08 = +8%，线性）。")
                .defineInRange("damagePerStack", 0.08D, 0.0D, 1.0D);
        Overload.MAX_STACKS_IN_COMBAT = b
                .comment("普通战斗中的层数上限。忆矢 4 级起 / 忆者法袍套装加成后可推到更高。")
                .defineInRange("maxStacksInCombat", 6, 1, 32);
        Overload.MAX_STACKS_IN_SEA = b
                .comment("「忆海」领域内的层数上限（领域内层数上限更高，这是领域技的核心价值）。")
                .defineInRange("maxStacksInSea", 15, 1, 64);
        b.pop();

        b.comment("忆魇（Memory Wraith）—— 遗迹的守护者").push("wraith");
        Wraith.MAX_HEALTH = b
                .comment("最大生命值。设计文档 v2 §8.1 定的是 100（原来是 20，一箭就死）。")
                .defineInRange("maxHealth", 100.0D, 1.0D, 1024.0D);
        Wraith.NEUTRAL = b
                .comment("是否中立（true = 不主动攻击，被打才反击）。")
                .define("neutral", true);
        Wraith.ONLY_MEMORY_DAMAGE = b
                .comment("是否只受忆海法术的满额伤害。开启后其他一切来源固定只造成 universalDamageCap 点。")
                .define("onlyMemoryDamage", true);
        Wraith.UNIVERSAL_DAMAGE_CAP = b
                .comment("非忆海来源的伤害硬上限（1.0 = 固定 1 点）。")
                .defineInRange("universalDamageCap", 1.0D, 0.0D, 100.0D);
        Wraith.FOLLOW_RANGE = b
                .comment("跟随范围（格）。")
                .defineInRange("followRange", 32.0D, 1.0D, 128.0D);
        b.pop();

        b.comment("全局平衡 —— 设计文档 v2 §9.4").push("balance");
        Balance.DEJA_VU_BASE_POTENCY = b
                .comment("""
                        「既视感」基础重演威力（0.80 = 80%）—— 即 1 级值。
                        ⭐ 2026-09-18：默认 0.40 → 0.80 —— 对齐 docs/tech/13_数值总表.md
                        §二 既视感表的「重演威力」列（L1 = 0.800 → L6 = 0.950）。
                        配合 DejaVuSpell.POWER_PER_LEVEL = 0.0375，实际曲线正好落在表上。""")
                .defineInRange("dejaVuBasePotency", 0.80D, 0.0D, 2.0D);
        Balance.DEJA_VU_PER_STACK_PENALTY = b
                .comment("记忆库里每多一个法术，重演威力的衰减量（0.05 = -5%）。")
                .defineInRange("dejaVuPerStackPenalty", 0.05D, 0.0D, 0.5D);
        Balance.ENCODE_PAIN_BASE_REFUND = b
                .comment("「痛忆」释放时的伤害返还基础比例（0.50 = 50%）。")
                .defineInRange("encodePainBaseRefund", 0.50D, 0.0D, 2.0D);
        Balance.THOUSAND_TRUE_DAMAGE_PCT = b
                .comment("""
                        「千忆归一」伤害中**真实伤害**（无视护甲/抗性/减伤/抗性药水）的占比。
                        这部分走 mnemosyne:memory_true 伤害类型 + 原版 bypasses_* 标签，
                        不经过 ISS 的抗性管线，**立刻结算**。
                        ⭐ 2026-09-18：默认 0.10 → 0.15 —— 这是"烧光全部忆格"的保底，
                        10% 在对手堆了抗性时几乎看不出来。""")
                .defineInRange("thousandTrueDamagePct", 0.15D, 0.0D, 1.0D);
        Balance.THOUSAND_ELEMENTAL_DOT_PCT = b
                .comment("""
                        「千忆归一」伤害中**元素持续伤害（DoT）**的占比。
                        ⭐ 2026-09-18 起这部分是**真的 DoT**：2 秒内分 4 跳结算，
                        每跳走学派伤害源（受 memory_magic_resist 影响）。
                        同时额外附一个随机元素状态（火焰 / 凋零 / 中毒 / 迟缓）—— 那是表现层，
                        与伤害无关，所以抽到「迟缓」也不会少伤害。
                        ⚠️ 三者（真实 + 元素 + 普通）之和应为 1.0；普通段 = 1 − 真实 − 元素，
                        超出 1.0 的部分会被自动归一化（见 ThousandMemoriesSpell.dealSplitDamage）。""")
                .defineInRange("thousandElementalDoTPct", 0.25D, 0.0D, 1.0D);
        b.pop();

        b.comment("法术数值 —— 记忆类法术的威力系数与上限").push("spells");
        Spells.SPELL_MEMORY_POWER = b
                .comment("记忆释放的威力系数（0.70 = 原法术的 70% 威力）。"
                        + " ⚠️ 2026-09-18：术忆/复诵已删除，当前生效于走马灯与既视感。")
                .defineInRange("spellMemoryPower", 0.70D, 0.0D, 2.0D);
        Spells.PAIN_MEMORY_CAP_PERCENT = b
                .comment("痛忆反打的伤害上限，取目标最大生命值的比例（0.60 = 60%）。")
                .defineInRange("painMemoryCapPercent", 0.60D, 0.0D, 1.0D);
        b.pop();

        b.comment("遗忘（Oblivion）系统 —— 剥夺敌人记忆的持续时间与 BOSS 处理").push("oblivion");
        Oblivion.FORGET_SECONDS = b
                .comment("「遗忘」层级的效果持续（秒）。")
                .defineInRange("forgetSeconds", 6, 0, 120);
        Oblivion.AMNESIA_SECONDS = b
                .comment("「失忆」层级的效果持续（秒）。")
                .defineInRange("amnesiaSeconds", 4, 0, 120);
        Oblivion.BOSS_IMMUNITY = b
                .comment("BOSS 是否完全免疫遗忘/窃取/复现。关闭后 BOSS 可被剥夺（不推荐，会破坏平衡）。")
                .define("bossImmunity", true);
        Oblivion.BOSS_ATTACK_REDUCTION = b
                .comment("BOSS 免疫剥夺时，改为降低攻击力的幅度（0.20 = -20% 攻击力）。")
                .defineInRange("bossAttackReduction", 0.20D, 0.0D, 1.0D);
        b.pop();

        b.comment("世界生成 —— 忆者遗迹（memory_ruin）").push("worldgen");
        Worldgen.RUIN_SPACING = b
                .comment("""
                        忆者遗迹的平均生成间距（区块）。
                        ⚠️ 注意：1.20.1 的结构间距写在 data/mnemosyne/worldgen/structure_set/memory_ruin.json 里，
                        数据包在加载时即固定，本配置项只作为「期望值」的单一事实来源与文档用途，
                        修改后必须同步改 structure_set JSON 才会真正生效。""")
                .defineInRange("ruinSpacing", 64, 1, 512);
        Worldgen.RUIN_SEPARATION = b
                .comment("忆者遗迹的最小生成间距（区块），必须小于 ruinSpacing。同上，需同步 JSON。")
                .defineInRange("ruinSeparation", 40, 0, 512);
        b.pop();

        b.comment("战利品").push("loot");
        Loot.LEGENDARY_WEIGHT = b
                .comment("忆碑随机解锁时抽到 Legendary 法术的权重（0.02 = 2%）。")
                .defineInRange("legendaryWeight", 0.02D, 0.0D, 1.0D);
        b.pop();

        return b.build();
    }

    // =====================================================================
    // CLIENT 侧
    // =====================================================================

    private static ForgeConfigSpec buildClient() {
        final ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        // ⚠️ 2026-09-17：这里原来有 hud.enabled / simpleMode / offsetX / offsetY 四项，
        //    服务于已被删除的忆格 HUD（EngramHudOverlay）。
        //    GUI 删除后这些配置项**没有任何消费者** —— 留着就是"改了没反应"的死配置，
        //    比没有更糟（玩家会以为开关坏了）。所以整节删除。
        //
        //    客户端目前没有可配置项，但保留 CLIENT_SPEC 本身：
        //    删掉它要连带改 MnemosyneMod 的 registerConfig 调用，收益为零。

        return b.build();
    }

    // =====================================================================
    // 配置项静态字段（键名与默认值已冻结）
    // =====================================================================

    /** 忆格系统（{@code engram.*}）。WS-B 消费。 */
    public static final class Engram {
        public static ForgeConfigSpec.IntValue BASE_SLOTS;
        public static ForgeConfigSpec.IntValue MAX_SLOTS;
        public static ForgeConfigSpec.IntValue DECAY_SECONDS;
        public static ForgeConfigSpec.DoubleValue RESONANCE_PER_SLOT;
        public static ForgeConfigSpec.DoubleValue CAST_SPEED_PENALTY_PER_SLOT;
        public static ForgeConfigSpec.DoubleValue MANA_PENALTY_PER_SLOT;
        public static ForgeConfigSpec.BooleanValue PERMANENT_FREE_OF_RESONANCE;

        private Engram() {}
    }

    /** 认知过载（{@code overload.*}）。设计文档 v2 §9.2。 */
    public static final class Overload {
        public static ForgeConfigSpec.DoubleValue DAMAGE_PER_STACK;
        public static ForgeConfigSpec.IntValue MAX_STACKS_IN_COMBAT;
        public static ForgeConfigSpec.IntValue MAX_STACKS_IN_SEA;

        private Overload() {}
    }

    /** 忆魇（{@code wraith.*}）。设计文档 v2 §9.3。 */
    public static final class Wraith {
        public static ForgeConfigSpec.DoubleValue MAX_HEALTH;
        public static ForgeConfigSpec.BooleanValue NEUTRAL;
        public static ForgeConfigSpec.BooleanValue ONLY_MEMORY_DAMAGE;
        public static ForgeConfigSpec.DoubleValue UNIVERSAL_DAMAGE_CAP;
        public static ForgeConfigSpec.DoubleValue FOLLOW_RANGE;

        private Wraith() {}
    }

    /** 全局平衡（{@code balance.*}）。设计文档 v2 §9.4。 */
    public static final class Balance {
        public static ForgeConfigSpec.DoubleValue DEJA_VU_BASE_POTENCY;
        public static ForgeConfigSpec.DoubleValue DEJA_VU_PER_STACK_PENALTY;
        public static ForgeConfigSpec.DoubleValue ENCODE_PAIN_BASE_REFUND;
        public static ForgeConfigSpec.DoubleValue THOUSAND_TRUE_DAMAGE_PCT;
        public static ForgeConfigSpec.DoubleValue THOUSAND_ELEMENTAL_DOT_PCT;

        private Balance() {}
    }

    /** 法术数值（{@code spells.*}）。WS-D 消费。 */
    public static final class Spells {
        public static ForgeConfigSpec.DoubleValue SPELL_MEMORY_POWER;
        public static ForgeConfigSpec.DoubleValue PAIN_MEMORY_CAP_PERCENT;

        private Spells() {}
    }

    /** 遗忘系统（{@code oblivion.*}）。WS-E / WS-E2 消费。 */
    public static final class Oblivion {
        public static ForgeConfigSpec.IntValue FORGET_SECONDS;
        public static ForgeConfigSpec.IntValue AMNESIA_SECONDS;
        public static ForgeConfigSpec.BooleanValue BOSS_IMMUNITY;
        public static ForgeConfigSpec.DoubleValue BOSS_ATTACK_REDUCTION;

        private Oblivion() {}
    }

    /** 世界生成（{@code worldgen.*}）。WS-G1 参考值。 */
    public static final class Worldgen {
        public static ForgeConfigSpec.IntValue RUIN_SPACING;
        public static ForgeConfigSpec.IntValue RUIN_SEPARATION;

        private Worldgen() {}
    }

    /** 战利品（{@code loot.*}）。WS-G1 / WS-J 消费。 */
    public static final class Loot {
        public static ForgeConfigSpec.DoubleValue LEGENDARY_WEIGHT;

        private Loot() {}
    }

    /** 客户端 HUD（{@code hud.*}）。WS-I 消费。 */
    // ⚠️ 2026-09-17：原先这里还有一个 `Hud` 配置类（ENABLED / SIMPLE_MODE / OFFSET_X / OFFSET_Y），
    //    服务于已被删除的忆格 HUD。随 GUI 一起删除。
}
