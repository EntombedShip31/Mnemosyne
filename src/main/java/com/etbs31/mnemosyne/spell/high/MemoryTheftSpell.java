package com.etbs31.mnemosyne.spell.high;

import com.etbs31.mnemosyne.util.SpellFeedback;
import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.capability.MnemosyneData;
import com.etbs31.mnemosyne.oblivion.AbilityMap;
import com.etbs31.mnemosyne.oblivion.BossImmunity;
import com.etbs31.mnemosyne.oblivion.OblivionManager;
import com.etbs31.mnemosyne.oblivion.OblivionTier;
import com.etbs31.mnemosyne.oblivion.TraitRegistry;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.MnemosyneSpell;
import com.etbs31.mnemosyne.util.RaycastHelper;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;

/**
 * 记忆掠夺 Memory Theft —— memory_theft。
 *
 * <p><b>归属</b>：WS-D3（本文件是 WS-A 建立的 stub，WS-D3 只填 onCast 的方法体）。
 *
 * <p><b>数值来源</b>：docs/tech/04_法术等级强度表.md 第四节第 14 条。
 * 构造器里那 5 个字段<b>逐字未动</b>，只把 {@code extends AbstractSpell} 换成了
 * {@link MnemosyneSpell}。
 *
 * <p><b>为什么是 {@code MnemosyneSpell} 而不是 {@code MnemosyneLongCastSpell}</b>：
 * stub 的注释写的是"WS-C 完成后换成 MnemosyneLongCastSpell"。实际接线时发现
 * 长吟基类的三个抽象方法是给"吟唱期逐 tick 机制"（切换目标 / 点燃忆格）用的，
 * 而记忆掠夺只需要一段固定的起手时间 —— {@code CastType.LONG} + 覆写
 * {@link #getCastTime(int)} 就完全够了。硬套长吟基类反而要空实现三个方法，
 * 且与同为 WS-D3 的认知崩坏 / 集体遗忘 / 既视感的写法不一致。
 * 这是刻意的取舍，不是漏改。
 *
 * <p><b>本法术做什么</b>（§四.14）：把目标身上的一个**特性**写进自己的永久忆格。
 * 它是忆海唯一一条"从敌人身上拿东西"的路径，也是唯一往 {@code permSlots} 写记忆的法术
 * （所以它写下的记忆**永不腐坏**）。
 *
 * <table border="1">
 *   <tr><th>等级</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th></tr>
 *   <tr><td>施法时间</td><td>40t</td><td>40t</td><td>30t</td><td>30t</td><td>20t</td></tr>
 *   <tr><td>写入特性数</td><td>1</td><td>1</td><td>1</td><td>2</td><td>2</td></tr>
 *   <tr><td>精英怪</td><td>效果减半</td><td>减半</td><td>不减半</td><td>—</td><td>—</td></tr>
 *   <tr><td>附加</td><td>—</td><td>—</td><td>—</td><td>占 2 格</td>
 *       <td>永久记忆不计入共鸣惩罚</td></tr>
 * </table>
 *
 * <p><b>⭐ 永久记忆是真的永久</b>：{@code OblivionManager.stealTrait} 用
 * {@code addEngram(caster, entry, true)} 写进 {@code permSlots}，而
 * {@code MnemosyneData.tick} 只清理 {@code slots} —— 所以 {@code EssenceMemory}
 * 里那个 {@code expire} 字段对永久记忆**不参与腐坏判定**，它只是
 * "释放这条质忆时特性生效多久"（见 {@code EngramRelease.release} 的 ESSENCE 分支）。
 *
 * <p><b>⚠️ 一处已知偏差（已登记在交付说明里）</b>：
 * §四.14 的 3/4/5 级写的是「**自选** 1 / 2 个特性」。选择界面 + C2S 包属于
 * <b>WS-I</b> 的客户端工作，与 {@code ReciteSpell} 的"选忆格"是同一个欠账。
 * 在它交付前，本类按 {@code TraitRegistry.getTraits(目标)} 的**清单顺序**取前 N 个，
 * 并把实际写入的条数告诉玩家 —— 而不是假装选择了却什么都不说。
 */
public class MemoryTheftSpell extends MnemosyneSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "memory_theft");

    /** 射程 8 格（§四.14 的"射程"行）。 */
    private static final float RANGE = 8.0F;

    /** 各等级可写入的特性数（index = level - 1）。§四.14 的"可选特性数"列。 */
    /**
     * 每次掠夺的效果个数（index = level − 1）。
     *
     * <p>⚠️ 变量名是历史遗留（旧版掠夺的是"特质"，现在掠夺的是**药水效果**），
     * 数值语义不变：1~2 个。
     */
    private static final int[] TRAIT_COUNT = {1, 1, 1, 2, 2, 2};

    /**
     * 各等级的施法时间（tick）。§二 记忆掠夺 的"吟唱（tick）"列：40/36/32/28/24/20。
     *
     * <p>⚠️ 必须覆写 {@link #getCastTime(int)} —— ISS 的默认实现直接返回
     * 固定的 {@code castTime} 字段，不随等级变（见 {@code AbstractSpell} 源码）。
     */
    private static final int[] CAST_TIME = {40, 36, 32, 28, 24, 20};

    /** 到这个等级为止，对精英怪的效果**减半**（§四.14：3 级起"不减半"）。 */
    private static final int ELITE_HALF_UP_TO_LEVEL = 2;

    /** 满级解锁「永久记忆不计入共鸣惩罚」（§四.14 的"附加"列）。 */
    private static final int PERM_EXEMPT_LEVEL = 5;

    /** 掠夺到的效果持续时长倍率：目标剩余时长 × 1.5（设计文档 v2 §一）。 */
    private static final double STEAL_DURATION_SCALE = 1.5D;

    public MemoryTheftSpell() {
        // 只把 DefaultConfig 交给基类；下面 5 个数值字段是 WS-A 冻结的契约，逐字不动。
        super(memoryConfig(SpellRarity.EPIC, 45.0D, 6));
        this.baseManaCost = 60;
        this.manaCostPerLevel = 12;
        this.baseSpellPower = 1;
        this.spellPowerPerLevel = 0;
        this.castTime = 40;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return SPELL_ID;
    }

    /** docs/tech/04 §三 总表：记忆掠夺是 LONG。 */
    @Override
    public CastType getCastType() {
        return CastType.LONG;
    }

    /** §四.14：40/40/30/30/20 tick。 */
    @Override
    public int getCastTime(final int spellLevel) {
        return CAST_TIME[clampLevelIndex(spellLevel)];
    }

    /** 吟唱起手音：{@code spell.memory_theft.charge}（docs/tech/08 §3.2）。 */
    @Override
    public Optional<SoundEvent> getCastStartSound() {
        return Optional.of(ModSounds.SPELL_MEMORY_THEFT_CHARGE.get());
    }

    /** 吟唱结束音：{@code spell.memory_theft.finish}（docs/tech/08 §3.2）。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_MEMORY_THEFT_FINISH.get());
    }

    /**
     * 没有目标时不消耗法力。
     *
     * <p>与 {@code EncodeSpell} 的"没打到目标不扣蓝"是同一条设计原则
     * （{@code docs/tech/03} §3.1）：**玩家按了一个不可能有结果的键时不该被罚**。
     *
     * <p>⚠️ 这里检查一次并不能省掉 {@link #onCast} 里的第二次检查 ——
     * 本法术的起手有 20~40 tick，目标完全可能在吟唱期间走出射线。
     */
    @Override
    public boolean checkPreCastConditions(final Level level, final int spellLevel,
                                          final LivingEntity entity, final MagicData playerMagicData) {
        if (entity instanceof ServerPlayer player
                && RaycastHelper.findLivingTarget(level, player, RANGE, true, true) == null) {
            player.displayClientMessage(Component.translatable("mnemosyne.msg.theft_no_target"), true);
            return false;
        }
        return super.checkPreCastConditions(level, spellLevel, entity, playerMagicData);
    }

    // ==================================================================
    // 落地
    // ==================================================================

    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide && entity instanceof ServerPlayer caster) {
            steal(caster, spellLevel);
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }

    /**
     * 掠夺目标**当前身上生效的药水效果**（设计文档 v2 §一）。
     *
     * <p><b>⭐⭐ 2026-09-18 重做。</b>旧版是"从目标身上撕下它的 AI 能力
     * （{@code TraitRegistry} 的特性）写进忆格"。文档要求改成掠夺**药水效果**，理由是：
     * 撕 AI 能力要动大量 AI / 注册表，实现成本高且容易飘
     * （与本项目在「走马灯」上踩过的坑同类）；而药水效果是**现成的、可枚举的、语义明确的**。
     *
     * <p><b>与设计文档的一处偏离（写在明处）</b>：文档说"写到自己忆格"，
     * 这里实现的是**直接转移到自己身上**。原因：忆格的 {@code EssenceMemory}
     * 只存得下"特质 id + 时长"，装不下一整个 {@code MobEffectInstance}
     *（效果 + 等级 + 剩余时长 + 环境/可见标志）。要支持它得**新增一种 EngramEntry 类型**，
     * 那会动到存档格式。所以先做"当场抢过来"，把"存起来以后再用"留给后续。
     *
     * <p><b>掠夺规则</b>：
     * <ul>
     *   <li>数量 1~2 个（随等级，{@link #TRAIT_COUNT}）</li>
     *   <li>持续时间 = 目标身上的**剩余时长 × {@link #STEAL_DURATION_SCALE}（1.5）**</li>
     *   <li>5 级：抢来的效果**永久**（{@code duration = -1}，直到死亡）</li>
     *   <li>瞬时效果（时长 ≤ 0）与"服务端专属"效果抢不了 —— 见 {@link #isStealable}</li>
     * </ul>
     */
    private void steal(final ServerPlayer caster, final int spellLevel) {
        final LivingEntity target =
                RaycastHelper.findLivingTarget(caster.level(), caster, RANGE, true, true);
        if (target == null) {
            // 吟唱期间目标跑掉了。法力已经扣了（这是 LONG 施法的固有风险），
            // 但**必须说出来** —— 静默什么都不发生是玩家最难排查的一类问题。
            caster.displayClientMessage(Component.translatable("mnemosyne.msg.theft_no_target"), true);
            return;
        }

        // 设计红线三：BOSS 完全免疫"剥夺" → 改数值化削弱。
        if (BossImmunity.isImmune(target)) {
            BossImmunity.applyFallback(caster, target, OblivionTier.FORGET);
            caster.displayClientMessage(Component.translatable("mnemosyne.msg.theft_boss"), true);
            // ⭐ 2026-09-18 特效：BOSS 免疫走的是"削弱"而不是"掠夺"，
            //    所以链路方向是 **caster → target**（我打它），与成功路径的抽取链相反。
            SpellFeedback.beam(caster.level(), caster.getEyePosition(),
                    SpellFeedback.chest(target), 16);
            SpellFeedback.hitBurst(caster.level(), target, SpellFeedback.MEMORY_MAGENTA);
            return;
        }

        // 收集可掠夺的效果（先快照再改，避免遍历时改集合）
        final java.util.List<net.minecraft.world.effect.MobEffectInstance> pool =
                new java.util.ArrayList<>(4);
        for (final net.minecraft.world.effect.MobEffectInstance inst : target.getActiveEffects()) {
            if (isStealable(inst)) {
                pool.add(inst);
            }
        }
        if (pool.isEmpty()) {
            caster.displayClientMessage(
                    Component.translatable("mnemosyne.msg.theft_no_effect"), true);
            return;
        }

        final boolean permanent = spellLevel >= PERM_EXEMPT_LEVEL;
        final int wanted = Math.min(TRAIT_COUNT[clampLevelIndex(spellLevel)], pool.size());
        int stolen = 0;
        for (int i = 0; i < wanted; i++) {
            final net.minecraft.world.effect.MobEffectInstance src = pool.get(i);
            final int duration = permanent
                    ? -1
                    : Math.max(1, (int) (src.getDuration() * STEAL_DURATION_SCALE));
            // ① 先给施法者加上（继承原等级与显示标志）
            caster.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    src.getEffect(), duration, src.getAmplifier(),
                    src.isAmbient(), src.isVisible(), src.showIcon()));
            // ② 再从目标身上撕掉 —— "掠夺"意味着对方失去它
            target.removeEffect(src.getEffect());
            stolen++;
        }

        if (stolen > 0 && permanent) {
            // §四.14 L5：永久记忆**不计入共鸣惩罚**。
            // 只写不删 —— 这是一条"能力解锁"而不是一段状态（见 MnemosyneData 的注释）。
            MnemosyneData.setPermanentMemoryExempt(caster);
        }

        caster.displayClientMessage(stolen > 0
                ? Component.translatable("mnemosyne.msg.theft_ok", stolen)
                : Component.translatable("mnemosyne.msg.theft_failed"), true);
        // ⭐ 2026-09-18 特效：掠夺成功的两段表现。
        //    ① **抽取链**（目标 → 自己）：说明"东西是从它身上离开、到我这里的"；
        //    ② **环数 = 抢到的效果数**（1~2 圈）：动作栏那行「抢到 N 个」是数字，
        //       这里是同一个数字的可见形态。
        //    ⚠️ 原本这里是一发 hitBurst —— 但"掠夺"不是"打击"，
        //       在对方身上炸一下会把语义带偏（玩家会以为这是个伤害技能）。
        if (stolen > 0) {
            SpellFeedback.extractBeam(caster.level(), target, caster, 24);
            SpellFeedback.stealPulse(caster.level(), caster, stolen);
        } else {
            SpellFeedback.hitBurst(caster.level(), target, SpellFeedback.MEMORY_MAGENTA);
        }
    }

    /**
     * 这个效果能不能被抢。
     *
     * <p>排除两类：
     * <ol>
     *   <li><b>瞬时效果</b>（{@code isInstantenous()} 或剩余时长 ≤ 0）——
     *       它们本来就"立刻生效完毕"，抢过来没有任何意义；</li>
     *   <li><b>负面效果</b> —— 掠夺是"夺取对方的优势"，
     *       把对方的虚弱抢到自己身上是纯亏（玩家不会想按这个键）。</li>
     * </ol>
     *
     * <p>⚠️ 设计文档说"无法掠夺无敌 / 创造这类服务端效果"。这类效果没有通用判据
     *（各模组命名不同），所以本实现靠"负面 / 瞬时"两条客观判据过滤 ——
     * 如果某个模组的"无敌"是正面且有时长的，它会被抢走。这是已知的边界。
     */
    private static boolean isStealable(final net.minecraft.world.effect.MobEffectInstance inst) {
        if (inst.getDuration() <= 0) {
            return false;
        }
        if (inst.getEffect().isInstantenous()) {
            return false;
        }
        return inst.getEffect().isBeneficial();
    }

    private static int clampLevelIndex(final int spellLevel) {
        return Math.max(1, Math.min(TRAIT_COUNT.length, spellLevel)) - 1;
    }
}
