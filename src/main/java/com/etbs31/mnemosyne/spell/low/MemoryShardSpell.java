package com.etbs31.mnemosyne.spell.low;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.capability.MnemosyneData;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.MnemosyneSpell;
import com.etbs31.mnemosyne.util.RaycastHelper;
import com.etbs31.mnemosyne.util.SpellFeedback;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * 碎忆 Memory Shard —— memory_shard。
 *
 * <p><b>归属</b>：WS-D1（本文件是 WS-A 建立的 stub，WS-D1 填实际逻辑）。
 *
 * <p><b>数值来源</b>：docs/tech/04_法术等级强度表.md §四.4（数值冻结，构造器 5 个字段逐字不动）。
 *
 * <p><b>基类</b>：{@link MnemosyneSpell} —— 碎忆要打伤害，必须用基类的
 * {@code hurtWithSpellDamage(...)} 走学派伤害源，否则目标的 {@code memory_magic_resist} 完全不生效
 * （{@code docs/tech/03} §4.1）。
 *
 * <p><b>⭐ 伤害自检</b>：{@code baseSpellPower = 8}、{@code spellPowerPerLevel = 2}、系数 {@code ×0.5}。
 * 1 级 {@code 8 × 0.5 = 4}、5 级 {@code 16 × 0.5 = 8} —— 与 §四.4 的表格逐行对上。
 *
 * <p><b>锥形</b>：前方 5 格、约 60°（半角 30°）。原版没有"锥形筛选"API，
 * 这里用「AABB 粗筛 + 视线夹角精筛」两步（{@code docs/tech/03} §五 的"前方 5 格锥形"）。
 *
 * <p><b>⚠️ 一处刻意的取舍</b>：{@code docs/04 §4} 写的是"对锥形范围内**所有**生物"，
 * 但本实现**排除友军**（{@link RaycastHelper#isAlly}）—— 与 {@code docs/tech/03} §五 里
 * 其他范围法术（集体遗忘 / 遗忘诅咒）的筛选规则保持一致，避免误伤自己的宠物与队友。
 */
public class MemoryShardSpell extends MnemosyneSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "memory_shard");

    /** 锥形射程（格）。 */
    private static final double CONE_RANGE = 5.0D;

    /** 锥形半角（度）。60° 全角 → 30° 半角。 */
    private static final double CONE_HALF_ANGLE_DEGREES = 30.0D;

    /**
     * 各等级的基础伤害（index = level - 1）。
     *
     * <p>⭐ <b>2026-09-18 伤害基准重定义</b>（依据 {@code docs/tech/13_数值总表.md} §二 碎忆表）：
     * 原来这里是一个标量 {@code 0.5}，配合 {@code baseSpellPower = 8 / spellPowerPerLevel = 2}
     * 得出「L1 = 4.0、L5 = 8.0」。而 13 号表的「伤害系数」列写的是
     * <b>L1 = 8.0 → L8 = 16.0</b>，即表里那一列就是<b>最终基础伤害本身</b>，不是要再乘一次的系数。
     *
     * <p>所以改成：{@code baseSpellPower = 1 / spellPowerPerLevel = 0}
     * → {@code basePowerOf} 恒为 1 → 基类的 {@code powerOf(level, caster)} 退化成
     * <b>纯粹的施法者加成倍率</b>（{@code SPELL_POWER × 学派强度 × POWER_MULTIPLIER}），
     * 本数组直接就是伤害数值，语义一目了然，也不会出现"系数被乘两次"的经典错误。
     *
     * <p>⚠️ 这也是全流派多数法术已有的写法（{@code baseSpellPower = 1}），本次只是让碎忆对齐。
     */
    private static final float[] DAMAGE_BY_LEVEL =
            {8.0F, 9.1F, 10.3F, 11.4F, 12.6F, 13.7F, 14.9F, 16.0F};

    /**
     * 击退强度换算。
     *
     * <p>原版近战用的是 {@code 0.4}（{@code Mob.doHurtTarget}），而 §四.4 的表是按"格"写的
     * （2 / 2 / 2.5 / 3 / 3 格）。这里取 {@code 0.35 × 格数} 做线性映射，
     * 实际距离还会被目标的 {@code KNOCKBACK_RESISTANCE} 属性削减 —— 那是原版语义，不在这里补偿。
     */
    private static final double KNOCKBACK_STRENGTH_PER_BLOCK = 0.35D;

    /** 各等级的击退格数（index = level - 1）。13 号表：2.00 → 3.00 线性。 */
    private static final double[] KNOCKBACK_BLOCKS =
            {2.00D, 2.14D, 2.29D, 2.43D, 2.57D, 2.71D, 2.86D, 3.00D};

    /** 各等级的临时忆格时长（秒，index = level - 1）。13 号表：15 → 40。 */
    private static final int[] TEMP_SLOT_SECONDS = {15, 19, 22, 26, 29, 33, 36, 40};

    public MemoryShardSpell() {
        super(memoryConfig(SpellRarity.COMMON, 4.0D, 8));
        this.baseManaCost = 27;
        this.manaCostPerLevel = 5;
        // ⭐ baseSpellPower/perLevel 归 1/0 —— 伤害改由 DAMAGE_BY_LEVEL 直接给出（见其注释）
        this.baseSpellPower = 1;
        this.spellPowerPerLevel = 0;
        this.castTime = 0;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return SPELL_ID;
    }

    @Override
    public CastType getCastType() {
        return CastType.INSTANT;
    }

    /** 施法音效：{@code spell.memory_shard.cast}（docs/tech/08 §3.2，官方三变体随机）。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_MEMORY_SHARD_CAST.get());
    }

    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide && entity instanceof ServerPlayer caster) {
            final int index = clampLevelIndex(spellLevel);
            // ① 先把"锥形判定范围"画出来 —— 不管有没有打中，"范围"本身就是玩家要看的信息
            //    （原版没有任何粒子形状能表达锥，见 SpellFeedback.coneSweep 的说明）
            SpellFeedback.castBurst(level, caster, SpellFeedback.MEMORY_INDIGO);
            SpellFeedback.coneSweep(level, caster, CONE_RANGE, CONE_HALF_ANGLE_DEGREES);
            // ② 结算（命中者各自在自己身上炸一次，见 strikeCone）
            final int hits = strikeCone(level, caster, spellLevel);
            if (hits > 0 && level instanceof ServerLevel serverLevel) {
                serverLevel.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                        ModSounds.SPELL_MEMORY_SHARD_HIT.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
            }
            // ③ 施放后自身 +1 个临时忆格（docs/tech/03 §6.7：碎忆 +1，15s ~ 40s）
            //    这一步改的是内部计数，屏幕上本来毫无变化 → 用 engramGain 给一个立刻的视觉确认
            MnemosyneData.addTempSlots(caster, 1, TEMP_SLOT_SECONDS[index] * 20);
            SpellFeedback.engramGain(level, caster, 1);
            syncNow(caster);
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }

    // ==================================================================
    // 锥形结算
    // ==================================================================

    /**
     * 对身前锥形内的目标逐个结算伤害与击退。
     *
     * @return 实际被命中的目标数
     */
    private int strikeCone(final Level level, final ServerPlayer caster, final int spellLevel) {
        final int index = clampLevelIndex(spellLevel);
        final Vec3 eye = caster.getEyePosition();
        final Vec3 look = caster.getLookAngle().normalize();
        final double minCos = Math.cos(Math.toRadians(CONE_HALF_ANGLE_DEGREES));
        final float damage = damageOf(spellLevel, caster, DAMAGE_BY_LEVEL[index]);
        final double strength = KNOCKBACK_BLOCKS[index] * KNOCKBACK_STRENGTH_PER_BLOCK;

        final List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class,
                caster.getBoundingBox().inflate(CONE_RANGE),
                candidate -> candidate.isAlive()
                        && candidate != caster
                        && !RaycastHelper.isAlly(caster, candidate));

        int hits = 0;
        for (final LivingEntity target : candidates) {
            // 精筛：以目标身体中心相对视线的夹角判断是否落在锥内
            final Vec3 toTarget = target.position()
                    .add(0.0D, target.getBbHeight() * 0.5D, 0.0D)
                    .subtract(eye);
            final double distance = toTarget.length();
            if (distance > CONE_RANGE || distance < 1.0E-4D) {
                continue;
            }
            if (toTarget.normalize().dot(look) < minCos) {
                continue;
            }
            // 伤害必须走学派伤害源（基类提供），否则 memory_magic_resist 形同虚设
            hurtWithSpellDamage(target, caster, damage);
            // 命中反馈：每个被打到的目标自己身上炸一次。
            // 2026-09-18 补 —— 原来锥形 AOE 打中几个人，屏幕上完全看不出打中了谁。
            SpellFeedback.hitBurst(level, target, SpellFeedback.MEMORY_INDIGO);
            // 击退方向 = 从施法者指向目标（LivingEntity.knockback 内部会再取反，见其源码）
            target.knockback(strength, target.getX() - caster.getX(), target.getZ() - caster.getZ());
            hits++;
        }
        return hits;
    }

    private static int clampLevelIndex(final int spellLevel) {
        return Math.max(1, Math.min(KNOCKBACK_BLOCKS.length, spellLevel)) - 1;
    }

    /**
     * 立刻把忆格同步给客户端。
     *
     * <p>不能等 {@code MnemosyneData.tick} 的 20 tick 轮询 —— 玩家会看到"临时忆格延迟一秒才出现"。
     */
    private static void syncNow(final ServerPlayer caster) {
        MnemosyneData.notifyEngramChange(caster);
    }
}
