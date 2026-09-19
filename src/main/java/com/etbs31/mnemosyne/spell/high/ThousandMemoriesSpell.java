package com.etbs31.mnemosyne.spell.high;

import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.core.registries.Registries;
import com.etbs31.mnemosyne.registry.ModSchools;
import com.etbs31.mnemosyne.Config;
import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.capability.EngramEntry;
import com.etbs31.mnemosyne.capability.MnemosyneData;
import com.etbs31.mnemosyne.oblivion.OblivionManager;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.ElementalDoT;
import com.etbs31.mnemosyne.spell.base.MnemosyneSpell;
import com.etbs31.mnemosyne.util.RaycastHelper;
import com.etbs31.mnemosyne.util.SpellFeedback;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 千忆归一 Thousand Memories —— thousand_memories。
 *
 * <p><b>归属</b>：WS-D3（本文件是 WS-A 建立的 stub，WS-D3 只填 onCast 的方法体）。
 *
 * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「千忆归一」（<b>唯一事实来源</b>，
 * 最大等级 10）；设计背景见 docs/tech/04_法术等级强度表.md 第四节第 18 条。
 * 构造器里那 5 个字段<b>逐字未动</b>，只把 {@code extends AbstractSpell} 换成了
 * {@link MnemosyneSpell}。
 *
 * <p><b>本法术做什么</b>（§四.18）：**把自己的全部忆格烧掉**，把每一条记忆
 * 换成一份伤害砸在目标身上，并附上一段遗忘。它是整个流派的终结点 ——
 * 忆格从"资产"变成"弹药"，用完即空。
 *
 * <table border="1">
 *   <tr><th>等级</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th>
 *       <th>6</th><th>7</th><th>8</th><th>9</th><th>10</th></tr>
 *   <tr><td>每格伤害</td><td>8.0</td><td>8.7</td><td>9.3</td><td>10.0</td><td>10.7</td>
 *       <td>11.3</td><td>12.0</td><td>12.7</td><td>13.3</td><td>14.0</td></tr>
 *   <tr><td>5 格总伤</td><td>40</td><td>43.5</td><td>46.5</td><td>50</td><td>53.5</td>
 *       <td>56.5</td><td>60</td><td>63.5</td><td>66.5</td><td>70</td></tr>
 *   <tr><td>遗忘时长</td><td>4s</td><td>4s</td><td>5s</td><td>5s</td><td>6s</td>
 *       <td>6s</td><td>7s</td><td>7s</td><td>8s</td><td>8s</td></tr>
 *   <tr><td>附加</td><td>—</td><td>—</td><td>—</td><td>—</td>
 *       <td colspan="6">L5 起：消耗的永久记忆 50% 概率保留</td></tr>
 * </table>
 *
 * <p><b>⭐ 伤害是"固定每格表"而不是"威力 × 系数"</b>：{@code docs/tech/13_数值总表.md} §二
 * 的每格伤害逐级写死（L1~L10 = 8.0 ~ 14.0），且末行明确"**不受共鸣加成影响**" —— 所以本类**不用**
 * {@code damageOf(...)}，并且法术 id 已登记在 {@code EngramResonance.RESONANCE_EXEMPT} 里。
 * 理由很直白：伤害的**唯一**变量是"你囤了多少记忆"，如果它同时再吃共鸣加成，
 * 就会变成"囤得越多、加得越多、伤害越爆炸"的双重指数，直接违背
 * {@code docs/README.md} §二 原则二（不做最高伤害）。
 *
 * <p><b>⭐ "空手释放 → 0 伤害，仅施加遗忘"是自然结果，不是特判</b>：
 * 伤害 = {@code 每格伤害 × 已用忆格数}，所以忆格为空时它自然是 0，
 * 而遗忘那一行照常执行。刻意不写 {@code if (used == 0)} 分支 ——
 * 一个"看起来在特判、其实是恒等式"的分支只会在将来被人改坏。
 *
 * <p><b>⚠️ 一处已知偏差（已登记在交付说明里）</b>：
 * §四.18 写"每格伤害"，但没说被消耗的**临时忆格**（忆格扩张 / 忆海给的容量）
 * 算不算"格"。本实现按 {@code MnemosyneData.getUsedEngrams} 计 ——
 * 也就是**只算真的装了记忆的格子**，空的临时容量不算钱。
 * 反过来"消耗全部忆格"是照做的：{@code clearAll} 会连临时容量的计数一起清掉。
 * 这与 §二 的"5 格总伤"（满级 5 × 14.0 = 70）一致 —— 那里数的也是**记忆**而不是格子。
 */
public class ThousandMemoriesSpell extends MnemosyneSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "thousand_memories");

    /** 射程 24 格（§四.18 的"射程"行）。 */
    private static final float RANGE = 24.0F;

    /**
     * 每格忆格造成的伤害（index = level − 1）。
     *
     * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「千忆归一」的"每格伤害"列：
     * L1~L10 = <b>8.0 / 8.7 / 9.3 / 10.0 / 10.7 / 11.3 / 12.0 / 12.7 / 13.3 / 14.0</b>。
     *
     * <p>⚠️ 2026-09-18 削弱：15~25 → **8~14**（设计文档 v2 §一）。
     * 原来 8 格 × 25 = 200 点一发，远超其他传说级法术，而代价只是"清空忆格"
     * （清空后可以重新攒）。降到 8~14 之后 8 格是 64~112 点，
     * 仍是全流派最高单发，但不再是"无脑必带"。
     */
    private static final float[] DAMAGE_PER_ENGRAM = {8.0F, 8.7F, 9.3F, 10.0F, 10.7F, 11.3F, 12.0F, 12.7F, 13.3F, 14.0F};

    /**
     * 元素段（DoT）的总时长与跳数。
     *
     * <p>⭐ 2026-09-18：2 秒 / 4 跳（每 10 tick 一跳）。
     * 选 2 秒而不是更长，是因为这是一个"爆发"法术 ——
     * 拖到 5 秒以上就不再是爆发，而是"挂个 dot 走人"，与法术定位不符。
     */
    private static final int ELEMENTAL_DOT_TICKS = 40;

    /** 元素段的跳数。 */
    private static final int ELEMENTAL_DOT_HOPS = 4;

    /** 元素状态的持续时间：1 秒（设计文档 v2 §一）。 */
    private static final int ELEMENT_DURATION_TICKS = 20;

    /**
     * 各等级的遗忘时长（秒）。
     *
     * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「千忆归一」的"遗忘（秒）"列：
     * L1~L10 = <b>4 / 4 / 5 / 5 / 6 / 6 / 7 / 7 / 8 / 8</b>。
     */
    private static final int[] FORGET_SECONDS = {4, 4, 5, 5, 6, 6, 7, 7, 8, 8};

    /** 从这个等级起，消耗的永久记忆有概率保留（§四.18 的"附加"列）。 */
    private static final int KEEP_PERMANENT_LEVEL = 5;

    /** 保留概率（§四.18："50% 概率保留"）。 */
    private static final float KEEP_CHANCE = 0.5F;

    public ThousandMemoriesSpell() {
        // 只把 DefaultConfig 交给基类；下面 5 个数值字段是 WS-A 冻结的契约，逐字不动。
        super(memoryConfig(SpellRarity.EPIC, 90.0D, 10));
        this.baseManaCost = 65;
        this.manaCostPerLevel = 13;
        this.baseSpellPower = 1;
        this.spellPowerPerLevel = 0;
        this.castTime = 50;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return SPELL_ID;
    }

    /** docs/tech/04 §三 总表：千忆归一是 LONG（castTime 50，不随等级变）。 */
    @Override
    public CastType getCastType() {
        return CastType.LONG;
    }

    /** 吟唱起手音：{@code spell.thousand_memories.charge}（docs/tech/08 §3.2）。 */
    @Override
    public Optional<SoundEvent> getCastStartSound() {
        return Optional.of(ModSounds.SPELL_THOUSAND_MEMORIES_CHARGE.get());
    }

    /** 结算音：{@code spell.thousand_memories.release}（docs/tech/08 §3.2）。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_THOUSAND_MEMORIES_RELEASE.get());
    }

    /**
     * 没有目标时不消耗法力。
     *
     * <p>本法术要烧掉玩家**全部**忆格，代价极高 —— 所以"射线没打到人"这一下
     * 绝对不能静默发生。这里在吟唱**开始前**就挡掉，{@link #onCast} 里再挡一次
     * （20~50 tick 的起手足够让目标走出射线）。
     */
    @Override
    public boolean checkPreCastConditions(final Level level, final int spellLevel,
                                          final LivingEntity entity, final MagicData playerMagicData) {
        if (entity instanceof ServerPlayer player
                && RaycastHelper.findLivingTarget(level, player, RANGE, true, true) == null) {
            player.displayClientMessage(Component.translatable("mnemosyne.msg.thousand_no_target"), true);
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
            release(caster, spellLevel);
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }

    private void release(final ServerPlayer caster, final int spellLevel) {
        final LivingEntity target =
                RaycastHelper.findLivingTarget(caster.level(), caster, RANGE, true, true);
        if (target == null) {
            // 目标在吟唱期间跑掉了。**一条记忆都不烧** —— 代价这么高的法术，
            // "打空了还照扣"是不可接受的。
            caster.displayClientMessage(Component.translatable("mnemosyne.msg.thousand_no_target"), true);
            return;
        }

        final int index = clampLevelIndex(spellLevel);
        final int used = MnemosyneData.getUsedEngrams(caster);
        // 忆格为空 → 伤害自然为 0，但遗忘照常施加（§四.18："空手释放 → 0 伤害，仅施加遗忘"）。
        final float damage = DAMAGE_PER_ENGRAM[index] * used;

        // ⚠️ 保留骰子必须在 clearAll **之前**掷 —— 清空之后就再也读不到
        //    原来有哪些永久记忆了（这正是本类需要 MnemosyneData.getPermanentEngrams 的原因）。
        final List<EngramEntry> survivors = rollSurvivors(caster, spellLevel);

        if (damage > 0.0F) {
            // ⭐⭐ 2026-09-18：伤害拆分（设计文档 v2 §一）。
            //
            // 旧版是"一发全额走学派伤害源" —— 结果是一个传说级法术的伤害
            // 完全取决于目标的护甲与记忆抗性，堆满抗性的目标几乎不掉血，
            // 而"烧掉全部忆格"的代价是实打实的。拆成三段之后：
            //   · 10% 真实伤害 —— 无视一切减免，保证"这一发一定疼"
            //   · 25% 元素 DoT —— 火焰/凋零/中毒/迟缓 随机一种，持续 1 秒（惩罚"站着不动"）
            //   · 65% 普通伤害 —— 走学派伤害源，仍受 memory_magic_resist 影响
            // 三段的比例**自动归一化**，所以配置里三个数加起来不等于 1 也不会打出超额伤害。
            dealSplitDamage(caster, target, damage);
            // 视觉（2026-09-18 补）：伤害 = 每格伤害 × **消耗的忆格数**，但玩家看不见"烧了几格"。
            // 让**爆发的规模 = 消耗的忆格数**（与「认知崩坏」同一套语言），
            // 于是"这一发值多少"一眼可读 —— 否则只能靠动作栏那行字。
            SpellFeedback.beam(caster.level(), caster.getEyePosition(), SpellFeedback.chest(target), 24);
            SpellFeedback.stackDetonation(caster.level(), target.position(), used);
            if (caster.level() instanceof ServerLevel serverLevel) {
                serverLevel.playSound(null, target.getX(), target.getY(), target.getZ(),
                        ModSounds.SPELL_THOUSAND_MEMORIES_HIT.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
            }
        }

        // 遗忘（§四.18 的"遗忘时长"列）。tier 1 = 「遗忘」。
        // BOSS 的免疫判定在 OblivionManager.applyOblivion 内部完成（转成数值化削弱），
        // 这里不需要再判一次 —— 与记忆掠夺不同，本方法没有"对 BOSS 完全无效"的语义。
        if (target.isAlive()) {
            OblivionManager.applyOblivion(caster, target, 1, FORGET_SECONDS[index] * 20);
        }

        // 消耗全部忆格（含永久记忆与临时忆格）。
        // clearAll 一次把 slots / permSlots / tempExpire 三个键都删了，
        // 所以**不要**再调 clearTempSlots —— 那是冗余的（而且容易让人误以为只清了临时格）。
        MnemosyneData.clearAll(caster);

        // L5：消耗的永久记忆有 50% 概率保留 → 把骰赢的那几条原样装回去。
        // 此刻忆格是空的，所以 addEngram 一定能装下（容量上限 >= 1 恒成立）。
        for (final EngramEntry kept : survivors) {
            MnemosyneData.addEngram(caster, kept, true);
        }

        caster.displayClientMessage(Component.translatable("mnemosyne.msg.thousand_ok",
                used, Math.round(damage)), true);
    }

    /**
     * 掷「永久记忆 50% 保留」的骰子，返回幸存的那几条。
     *
     * @return 未满级时恒为空表（那时所有永久记忆都一并烧掉）
     */
    private static List<EngramEntry> rollSurvivors(final ServerPlayer caster, final int spellLevel) {
        if (spellLevel < KEEP_PERMANENT_LEVEL) {
            return List.of();
        }
        final List<EngramEntry> survivors = new ArrayList<>();
        for (final EngramEntry entry : MnemosyneData.getPermanentEngrams(caster)) {
            if (caster.getRandom().nextFloat() < KEEP_CHANCE) {
                survivors.add(entry);
            }
        }
        return survivors;
    }

    /**
     * 把一次总伤害拆成三段打出去（设计文档 v2 §一）。
     *
     * <p><b>为什么要拆</b>：旧版是"一发全额走学派伤害源" —— 于是这个传说级法术的
     * 实际伤害完全取决于目标的护甲与记忆抗性，堆满抗性的目标几乎不掉血，
     * 而"烧掉全部忆格"的代价是实打实的。拆成三段后"这一发一定疼"，
     * 代价与收益才对得上。
     *
     * <table border="1">
     *   <tr><th>段</th><th>默认占比</th><th>怎么打</th></tr>
     *   <tr><td>普通</td><td><b>55%</b></td><td>学派伤害源 → 受 {@code memory_magic_resist} 影响，<b>立刻结算</b></td></tr>
     *   <tr><td>真实</td><td><b>15%</b></td><td>{@code mnemosyne:memory_true} + 原版 {@code bypasses_*} 标签，无视一切减免，<b>立刻结算</b></td></tr>
     *   <tr><td>元素</td><td><b>30%</b></td><td>{@link ElementalDoT} —— <b>2 秒内分 4 跳</b>，每跳走学派伤害源</td></tr>
     * </table>
     *
     * <p><b>⭐ 2026-09-18 占比调整（用户要求"合理优化"）</b>：
     * <ul>
     *   <li>真实段 <b>10% → 15%</b>：这是"我烧光了全部忆格，至少得疼一下"的保底。
     *       10% 在对手堆了抗性时几乎看不出来，15% 才够撑起"保底"这个语义。</li>
     *   <li>元素段 <b>25% → 30%</b>：因为它现在**真的**是一个 2 秒的 DoT，
     *       承担了"持续压制"的角色（旧版只是名义上的 25%）。</li>
     *   <li>普通段随之从 65% 降到 <b>55%</b> —— 三段自动归一化，总和恒为 1。</li>
     * </ul>
     *
     * <p>⭐⭐ <b>2026-09-18 修掉了一个"名不副实的配置项"</b>：
     * 旧版把元素段的伤害并进普通段一次性打完，元素状态只是**纯表现**。
     * 于是 {@code thousandElementalDoTPct} 名义上是"元素持续伤害占比"，
     * 实际**没有任何持续伤害发生** —— 调这个数只会看到总伤害变化，看不到 DoT。
     * <br>现在由 {@link ElementalDoT} 真正按时间结算，"元素"这个词才名副其实。
     * 顺带解决了旧注释自己承认的矛盾：**「迟缓」本身零伤害**，
     * 但伤害不再由状态效果承载，所以抽到它也不会让这一发少掉 30%。
     *
     * <p>比例会**自动归一化**：配置里两个数之和若 ≥ 1，普通段会被压到 0
     * 并按比例缩放另外两段，不会打出超额伤害。
     */
    private void dealSplitDamage(final ServerPlayer caster, final LivingEntity target, final float total) {
        double truePct = Config.Balance.THOUSAND_TRUE_DAMAGE_PCT.get();
        double dotPct = Config.Balance.THOUSAND_ELEMENTAL_DOT_PCT.get();
        double normalPct = 1.0D - truePct - dotPct;
        if (normalPct < 0.0D) {
            // 配置不合法（两段加起来就超过 100%）→ 按比例压回总和 1，普通段归零
            final double sum = truePct + dotPct;
            if (sum <= 0.0D) {
                normalPct = 1.0D;
                truePct = 0.0D;
                dotPct = 0.0D;
            } else {
                truePct /= sum;
                dotPct /= sum;
                normalPct = 0.0D;
            }
        }

        // ① 普通段 —— 走学派伤害源，是这一发的"主力"
        final float normalAmount = (float) (total * normalPct);
        if (normalAmount > 0.0F) {
            hurtWithSpellDamage(target, caster, normalAmount);
        }

        // ② 真实段 —— 无视一切减免。⚠️ 不走 DamageSources.applyDamage（那会套用抗性）
        if (truePct > 0.0D && target.isAlive()) {
            hurtWithTrueDamage(caster, target, (float) (total * truePct));
        }

        // ③ 元素段 —— ⭐ 2026-09-18：**真正按时间拆开打**（见 ElementalDoT 的类注释）。
        //    旧版把这一段并进普通段一次性结算，配置项名不副实；
        //    现在它是真的 DoT：2 秒内分 4 跳，每跳走学派伤害源。
        if (dotPct > 0.0D && target.isAlive()) {
            ElementalDoT.schedule(target, caster, this, (float) (total * dotPct),
                    ELEMENTAL_DOT_TICKS, ELEMENTAL_DOT_HOPS);
        }

        // ④ 元素状态（表现层）—— 与 DoT **并行**，不再是伤害的载体。
        //    所以抽到「迟缓」（本身零伤害）也不会让这一发少掉元素段。
        applyRandomElement(target);
    }

    /**
     * 打一段**真实伤害** —— 无视护甲 / 附魔 / 抗性效果 / 记忆抗性。
     *
     * <p>靠 {@code mnemosyne:memory_true} 这个伤害类型 + 四个原版标签
     * （见 {@code ModSchools.MEMORY_TRUE_DAMAGE_TYPE} 的注释）。
     * <br>⚠️ 必须用原版 {@code hurt()} 而不是 {@code DamageSources.applyDamage} ——
     * 后者的抗性计算与"真实伤害"的语义直接冲突。
     */
    private void hurtWithTrueDamage(final ServerPlayer caster, final LivingEntity target, final float amount) {
        final var source = new DamageSource(
                target.level().registryAccess()
                        .registryOrThrow(Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(ModSchools.MEMORY_TRUE_DAMAGE_TYPE),
                caster, caster);
        target.hurt(source, amount);
    }

    /**
     * 附一个随机元素状态，持续 1 秒（20 tick）。
     *
     * <p>四选一：火焰（点燃） / 凋零 / 中毒 / 迟缓。
     * 这一步只负责"看得见"，伤害已经在上面的普通段里结算完了 —— 见 {@link #dealSplitDamage} 的取舍说明。
     */
    private void applyRandomElement(final LivingEntity target) {
        if (!target.isAlive()) {
            return;
        }
        switch (target.getRandom().nextInt(4)) {
            case 0 -> target.setRemainingFireTicks(ELEMENT_DURATION_TICKS);
            case 1 -> target.addEffect(new MobEffectInstance(MobEffects.WITHER,
                    ELEMENT_DURATION_TICKS, 1, false, true, true));
            case 2 -> target.addEffect(new MobEffectInstance(MobEffects.POISON,
                    ELEMENT_DURATION_TICKS, 1, false, true, true));
            default -> target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                    ELEMENT_DURATION_TICKS, 1, false, true, true));
        }
    }

    private static int clampLevelIndex(final int spellLevel) {
        return Math.max(1, Math.min(DAMAGE_PER_ENGRAM.length, spellLevel)) - 1;
    }
}
