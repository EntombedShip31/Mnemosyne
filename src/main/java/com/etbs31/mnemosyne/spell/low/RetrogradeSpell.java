package com.etbs31.mnemosyne.spell.low;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.MnemosyneSpell;
import com.etbs31.mnemosyne.util.MemoryTrail;
import com.etbs31.mnemosyne.util.SpellFeedback;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 残迹回溯 Retrograde —— retrograde（Uncommon / 瞬发）。
 *
 * <p><b>原型</b>：残秽（{@link MemoryTrail}）—— 术式行使后咒力必如足迹般留在现场，
 * 追踪它可以还原"刚才发生了什么"。
 *
 * <p><b>机制</b>：回溯一片区域最近 N 秒内的三类事件 —— <b>伤害 / 施法 / 死亡</b>，
 * 用残影把它们重现出来；同时对<b>区域内最近一次伤害的施加者</b>打上「追迹」标记 5 秒，
 * 你对被标记目标的下一击提高 15%~27%。
 *
 * <p><b>⭐⭐ 与「窥忆」的区别（这是它存在的理由）</b>
 * <br>窥忆 = <b>单体 × 即时</b>（读一个目标<b>现在</b>的属性）；
 * <br>残迹回溯 = <b>区域 × 时间</b>（读一片地方<b>过去</b>发生的事）。
 * 维度完全不同 —— 尤其是它能读到<b>已经撤离的敌人</b>，这是窥忆做不到的。
 *
 * <p><b>⭐ 决策点</b>：战斗中放换增伤；战后放找人。冷却 12 秒，别连着放。
 *
 * <table border="1">
 *   <tr><th>等级</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th><th>6</th></tr>
 *   <tr><td>半径</td><td>12.0</td><td>12.8</td><td>13.6</td><td>14.4</td><td>15.2</td><td>16.0</td></tr>
 *   <tr><td>回溯</td><td>30s</td><td>34s</td><td>38s</td><td>42s</td><td>46s</td><td>50s</td></tr>
 *   <tr><td>标记增伤</td><td>15%</td><td>17.4%</td><td>19.8%</td><td>22.2%</td><td>24.6%</td><td>27%</td></tr>
 * </table>
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RetrogradeSpell extends MnemosyneSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "retrograde");

    /** 各等级的查询半径（格）：12.0 → 16.0（L1~L6，docs/tech/13_数值总表.md §残迹回溯）。 */
    private static final double[] RADIUS = {12.0D, 12.8D, 13.6D, 14.4D, 15.2D, 16.0D};

    /** 各等级的回溯时长（秒）：30 → 50（L1~L6，docs/tech/13_数值总表.md §残迹回溯）。 */
    private static final int[] LOOKBACK_SECONDS = {30, 34, 38, 42, 46, 50};

    /** 各等级的标记增伤（0~1）：0.150 → 0.270（L1~L6，docs/tech/13_数值总表.md §残迹回溯）。 */
    private static final double[] MARK_BONUS = {0.150D, 0.174D, 0.198D, 0.222D, 0.246D, 0.270D};

    /** 标记持续（秒）。 */
    private static final int MARK_SECONDS = 5;

    /**
     * 一次最多重现多少段残迹。
     *
     * <p>⭐ 这是渲染开销的闸门：混战里 30 秒内可能有上百条事件，
     * 全画出来既刷屏又浪费发包。12 段足够讲清"刚才这里发生了什么"。
     */
    private static final int MAX_TRAILS = 12;

    /** 被「追迹」标记的目标。键 = 目标 UUID。 */
    private static final Map<UUID, Mark> MARKS = new ConcurrentHashMap<>();

    /** 一个追迹标记。 */
    private static final class Mark {
        private final UUID caster;
        private final double bonus;
        private final long expireTick;

        private Mark(final UUID caster, final double bonus, final long expireTick) {
            this.caster = caster;
            this.bonus = bonus;
            this.expireTick = expireTick;
        }
    }

    public RetrogradeSpell() {
        super(memoryConfig(SpellRarity.UNCOMMON, 12.0D, 6));
        this.baseManaCost = 35;
        this.manaCostPerLevel = 7;
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

    /** 施法音效复用「窥忆」—— 两者都是"读取信息"，意象一致。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_GLIMPSE_CAST.get());
    }

    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide && entity instanceof ServerPlayer caster
                && level instanceof ServerLevel serverLevel) {
            read(caster, serverLevel, spellLevel);
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }

    private static void read(final ServerPlayer caster, final ServerLevel level, final int spellLevel) {
        final int index = clampLevelIndex(spellLevel);
        final double radius = RADIUS[index];
        final long now = level.getServer().overworld().getGameTime();
        final long since = now - (long) LOOKBACK_SECONDS[index] * 20L;

        final List<MemoryTrail.Trail> trails =
                MemoryTrail.query(caster.position(), radius, since, MAX_TRAILS);

        // ⭐ 读取环：先给一次"扫描过这片地方"的反馈，再逐条画残影。
        //    没有这一圈，玩家在空地上放法术时会完全分不清"没查到"和"法术没生效"。
        SpellFeedback.areaBurst(level, caster.position(), radius * 0.6D, SpellFeedback.MEMORY_INDIGO);

        UUID lastAttacker = null;
        for (final MemoryTrail.Trail trail : trails) {
            drawTrail(level, trail);
            if (lastAttacker == null && trail.kind() == MemoryTrail.KIND_DAMAGE
                    && trail.source() != null && !trail.source().equals(caster.getUUID())) {
                // query 从最新往回返回，所以第一条伤害事件就是"最近一次伤害"
                lastAttacker = trail.source();
            }
        }

        final Entity marked = lastAttacker == null ? null : level.getEntity(lastAttacker);
        if (marked instanceof LivingEntity target) {
            // 复用原版发光：它是服务端效果，队友也看得见轮廓。
            // 这让残迹回溯在多人里有了"点名"的价值 —— 与窥忆保持一致的反馈语言。
            target.addEffect(new MobEffectInstance(
                    MobEffects.GLOWING, MARK_SECONDS * 20, 0, false, false, true));
            MARKS.put(target.getUUID(), new Mark(caster.getUUID(), MARK_BONUS[index],
                    now + (long) MARK_SECONDS * 20L));
            SpellFeedback.actionBar(caster, Component.translatable("mnemosyne.retrograde.marked",
                            target.getDisplayName())
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        } else {
            SpellFeedback.actionBar(caster, Component.translatable("mnemosyne.retrograde.count",
                            trails.size())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /**
     * 画一段残影。
     *
     * <p><b>⭐ 三种事件三种形状</b> —— 形状就是语义，玩家不用看文字也能分辨：
     * <ul>
     *   <li><b>伤害</b>：从攻击者指向受害者的<b>一条线</b>（谁打了谁）</li>
     *   <li><b>死亡</b>：原地立起的一道<b>光柱</b>（有人死在这里）</li>
     *   <li><b>施法</b>：一团悬浮的<b>符文</b>（有人在这里施过法）</li>
     * </ul>
     */
    private static void drawTrail(final ServerLevel level, final MemoryTrail.Trail trail) {
        final Vec3 at = trail.at();
        switch (trail.kind()) {
            case MemoryTrail.KIND_DAMAGE -> SpellFeedback.beam(level, trail.from(), at, 8);
            case MemoryTrail.KIND_DEATH -> {
                for (int i = 0; i < 6; i++) {
                    level.sendParticles(ParticleTypes.END_ROD,
                            at.x, at.y + i * 0.45D, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            }
            default -> level.sendParticles(ParticleTypes.ENCHANT,
                    at.x, at.y + 0.3D, at.z, 6, 0.25D, 0.25D, 0.25D, 0.01D);
        }
    }

    /**
     * 标记的兑现：你对被标记目标的<b>下一击</b>提高伤害。
     *
     * <p>只兑现一次（用完即移除）—— 这是"追迹"而不是"永久易伤"。
     */
    @SubscribeEvent
    public static void onLivingHurt(final LivingHurtEvent event) {
        final LivingEntity target = event.getEntity();
        final Mark mark = MARKS.get(target.getUUID());
        if (mark == null) {
            return;
        }
        final Entity attacker = event.getSource().getEntity();
        if (attacker == null || !attacker.getUUID().equals(mark.caster)) {
            return;
        }
        MARKS.remove(target.getUUID());
        event.setAmount(event.getAmount() * (float) (1.0D + mark.bonus));
        SpellFeedback.hitBurst(target.level(), target, SpellFeedback.MEMORY_INDIGO);
    }

    /** 过期清理。标记只有 5 秒，不清会在内存里堆积。 */
    @SubscribeEvent
    public static void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || MARKS.isEmpty()) {
            return;
        }
        final long now = event.getServer().overworld().getGameTime();
        MARKS.values().removeIf(mark -> now >= mark.expireTick);
    }

    private static int clampLevelIndex(final int spellLevel) {
        return Math.max(1, Math.min(RADIUS.length, spellLevel)) - 1;
    }
}
