package com.etbs31.mnemosyne.spell.high;

import io.redspace.ironsspellbooks.api.spells.CastType;
import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.OblivionSpell;
import com.etbs31.mnemosyne.util.RaycastHelper;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集体遗忘 Mass Amnesia —— mass_amnesia。
 *
 * <p><b>归属</b>：WS-D3（本文件是 WS-A 建立的 stub，WS-D3 只填 onCast 的方法体）。
 *
 * <p><b>数值来源</b>：docs/tech/04_法术等级强度表.md 第四节第 15 条。
 * 构造器里那 5 个字段<b>逐字未动</b>，只把 {@code extends AbstractSpell} 换成了
 * {@link OblivionSpell}。
 *
 * <p><b>本法术做什么</b>（§四.15）：以自身为中心，把一整片敌人的特殊能力全部抹掉。
 * 它是遗忘系统唯一的**群体控制**，也是 {@code docs/05 §12} 说的
 * "诅咒压制输出，集体遗忘清空仇恨"里那一半。
 *
 * <table border="1">
 *   <tr><th>等级</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th></tr>
 *   <tr><td>半径</td><td>8</td><td>8</td><td>9</td><td>10</td><td>12</td></tr>
 *   <tr><td>持续时间</td><td>6s</td><td>7s</td><td>8s</td><td>9s</td><td>12s</td></tr>
 *   <tr><td>迷茫减速</td><td>—</td><td>—</td><td>20%</td><td>30%</td><td>40%</td></tr>
 *   <tr><td>附加</td><td>—</td><td>—</td><td>—</td><td>盟友也免疫索敌</td>
 *       <td>盟友免疫 + 结束后 2s 索敌延迟</td></tr>
 * </table>
 *
 * <p><b>⭐ "盟友也免疫索敌"与"索敌延迟"怎么落地</b>
 * <br>这两个效果都不是"给某个实体挂状态"能表达的，它们拦的是**别人的行为**：
 * 前者是"被遗忘的怪物不许打我的队友"，后者是"被遗忘的怪物在两秒内不许锁定任何人"。
 * 原版 Forge 为此提供了 {@link LivingChangeTargetEvent}（实测 {@code @Cancelable}，
 * 在 {@code Mob.setTarget} 与 {@code StartAttacking} 两条路径上都会触发），
 * 所以这两条都能真正实现，而不是写成注释里的愿望。
 *
 * <p>两张表都只存在内存里（最长 12+2 秒），理由与 {@code AmnesiaSpell} 的发呆表一致。
 *
 * <p><b>⚠️ 一处数值偏差</b>：§四.15 的"迷茫减速"是 20%/30%/40%，
 * 而原版「缓慢」是每级 -15%（I=15% / II=30% / III=45%）。
 * 这里取**最接近且不超过太多**的档位：20%→I、30%→II、40%→III。
 * 用自定义属性修饰符能做到精确的 20/30/40，但那样就得自己管到期清理，
 * 为一个 5 个百分点的差异引入一条清理路径不划算。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MassAmnesiaSpell extends OblivionSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "mass_amnesia");

    /** 各等级的作用半径（格，index = level - 1）。§二 集体遗忘 的"半径（格）"列。 */
    private static final double[] RADIUS = {8.0D, 8.8D, 9.6D, 10.4D, 11.2D, 12.0D};

    /** 各等级的持续时间（秒，index = level - 1）。§二 集体遗忘 的"遗忘时长（秒）"列。 */
    private static final int[] DURATION_SECONDS = {6, 7, 8, 10, 11, 12};

    /** 各等级的「缓慢」等级（index = level - 1）。{@code -1} = 不加。§二 集体遗忘 的"迟缓等级"列。 */
    private static final int[] SLOW_AMPLIFIER = {-1, 0, 0, 1, 1, 2};

    /** 4 级起"盟友也免疫索敌"。 */
    private static final int LEVEL_FOR_ALLY_PROTECTION = 4;

    /** 5 级的"结束后 2s 索敌延迟"。 */
    private static final int LEVEL_FOR_TARGETING_DELAY = 5;
    private static final int TARGETING_DELAY_TICKS = 40;

    /** 被保护的盟友：实体 UUID → 到期刻。 */
    private static final Map<UUID, Long> PROTECTED = new ConcurrentHashMap<>();

    /** 被"锁住索敌"的怪物：实体 UUID → 到期刻。 */
    private static final Map<UUID, Long> NO_TARGETING = new ConcurrentHashMap<>();

    public MassAmnesiaSpell() {
        super(memoryConfig(SpellRarity.EPIC, 40.0D, 6));
        this.baseManaCost = 65;
        this.manaCostPerLevel = 13;
        this.baseSpellPower = 1;
        this.spellPowerPerLevel = 0;
        this.castTime = 40;   // ⭐ 2026-09-18：改成吟唱（设计文档 v2 §一要求）
    }

    @Override
    public ResourceLocation getSpellResource() {
        return SPELL_ID;
    }

    /** 集体遗忘是 tier 3：同失忆，但作用于一片区域。 */
    @Override
    protected int getOblivionTier() {
        return 3;
    }

    /**
     * 对**单个**目标施加遗忘 —— {@code OblivionSpell} 的抽象方法。
     *
     * <p>为什么本类明明有 {@link #burst} 还是必须实现它：{@code burst} 走的是
     * {@code applyTo(caster, target, spellLevel)}，而 {@code applyTo} 内部对
     * **非免疫**目标调用的正是本方法。也就是说 {@code burst} 负责"找一圈人"，
     * 本方法负责"把一个人的记忆摘掉"—— 两者是分工，不是重复。
     *
     * <p>时长必须**按等级**取（{@code docs/tech/04} §四.15：6/7/8/9/12 秒），
     * 不能走 {@code OblivionTier.durationTicks()}（那只有一个固定配置值）。
     */
    @Override
    protected void applyOblivion(final ServerPlayer caster, final LivingEntity target, final int spellLevel) {
        oblivionManager(caster, target, getOblivionTier(), durationTicksOf(spellLevel));
    }

    /** §四.15 的"持续时间"列 → tick。{@link #burst} 与 {@link #applyOblivion} 共用，避免两处漂移。 */
    private static int durationTicksOf(final int spellLevel) {
        return DURATION_SECONDS[clampLevelIndex(spellLevel)] * 20;
    }

    /** 施法音效：{@code spell.mass_amnesia.cast}（docs/tech/08 §3.2）。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_MASS_AMNESIA_CAST.get());
    }

    // ==================================================================
    // 落地
    // ==================================================================

    /**
     * {@inheritDoc}
     *
     * <p>刻意**不**走基类的单体 {@code resolveTarget → applyTo}：
     * 本法术以**自身**为中心（§四.15 的"以自身为中心"），没有"瞄准谁"这回事。
     * 但区域内的每个目标仍然逐个走 {@link #applyTo} ——
     * 所以"BOSS 免疫 → 数值化削弱""对玩家无效""没有可摘行为 → 通用降级"
     * 这三条规则一条都没有绕过。
     */
    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide && entity instanceof ServerPlayer caster) {
            burst(caster, spellLevel);
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }

    private void burst(final ServerPlayer caster, final int spellLevel) {
        final int index = clampLevelIndex(spellLevel);
        final int durationTicks = durationTicksOf(spellLevel);
        final double radius = RADIUS[index];
        if (caster.level() instanceof ServerLevel serverLevel) {
            burstDomain(serverLevel, caster, radius);
        }
        final long expireTick = nowTick(caster) + durationTicks;

        // 排除自己与友军（RaycastHelper.isAlly：同队 / 同主人 / 都是玩家）
        final List<LivingEntity> targets = RaycastHelper.findLivingInSphere(
                caster.level(), caster.position(), radius, caster, true);

        int affected = 0;
        for (final LivingEntity target : targets) {
            if (target == caster) {
                continue;
            }
            applyTo(caster, target, spellLevel);
            affected++;

            if (SLOW_AMPLIFIER[index] >= 0) {
                target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                        durationTicks, SLOW_AMPLIFIER[index], false, true, true));
            }
            if (spellLevel >= LEVEL_FOR_TARGETING_DELAY) {
                // "结束后 2s 索敌延迟"：领域结束之后再多锁 2 秒
                NO_TARGETING.put(target.getUUID(), expireTick + TARGETING_DELAY_TICKS);
            }
        }

        if (spellLevel >= LEVEL_FOR_ALLY_PROTECTION) {
            // 盟友也免疫索敌：把自己 + 半径内的友军登记为"不可被这些怪物锁定"
            PROTECTED.put(caster.getUUID(), expireTick);
            for (final LivingEntity ally : RaycastHelper.findLivingInSphere(
                    caster.level(), caster.position(), radius, caster, false)) {
                if (ally != caster && RaycastHelper.isAlly(caster, ally)) {
                    PROTECTED.put(ally.getUUID(), expireTick);
                }
            }
        }

        if (caster.level() instanceof ServerLevel serverLevel) {
            for (int i = 0; i < 64; i++) {
                final double angle = i / 64.0D * Math.PI * 2.0D;
                serverLevel.sendParticles(ParticleTypes.SCULK_SOUL,
                        caster.getX() + Math.cos(angle) * radius * 0.6D, caster.getY() + 0.4D,
                        caster.getZ() + Math.sin(angle) * radius * 0.6D,
                        1, 0.0D, 0.02D, 0.0D, 0.01D);
            }
            serverLevel.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                    ModSounds.SPELL_MASS_AMNESIA_CAST.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
        }
        MnemosyneMod.LOGGER.debug("[集体遗忘] {} 命中 {} 个目标（半径 {} 格 / {} tick）",
                caster.getName().getString(), affected, radius, durationTicks);
    }

    // ==================================================================
    // 索敌拦截
    // ==================================================================

    /**
     * 拦住"被遗忘的怪物锁定受保护的盟友"与"索敌延迟期内的任何锁定"。
     *
     * <p>实测：{@code LivingChangeTargetEvent} 在 Forge 1.20.1 是 {@code @Cancelable}
     * （见 {@code forge-1.20.1-47.4.0-patched.jar} 里该类的 {@code @Cancelable} 注解），
     * 取消后目标不会被改变，且 {@code LivingSetAttackTargetEvent} 不会再发出。
     */
    @SubscribeEvent
    public static void onChangeTarget(final LivingChangeTargetEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.world.entity.Mob mob)) {
            return;
        }
        final long now = nowTick(mob);

        final Long noTargetingUntil = NO_TARGETING.get(mob.getUUID());
        if (noTargetingUntil != null) {
            if (now >= noTargetingUntil) {
                NO_TARGETING.remove(mob.getUUID());
            } else {
                event.setCanceled(true);
                return;
            }
        }

        final LivingEntity newTarget = event.getNewTarget();
        if (newTarget == null) {
            return;
        }
        final Long protectedUntil = PROTECTED.get(newTarget.getUUID());
        if (protectedUntil == null) {
            return;
        }
        if (now >= protectedUntil) {
            PROTECTED.remove(newTarget.getUUID());
            return;
        }
        event.setCanceled(true);
    }

    /** 两张表的到期清理（每 20 tick 一次即可 —— 它们只在"还锁着"时有意义）。 */
    @SubscribeEvent
    public static void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        final long now = event.getServer().overworld().getGameTime();
        prune(PROTECTED, now);
        prune(NO_TARGETING, now);
    }

    private static void prune(final Map<UUID, Long> table, final long now) {
        if (table.isEmpty()) {
            return;
        }
        for (final Iterator<Map.Entry<UUID, Long>> it = table.entrySet().iterator(); it.hasNext(); ) {
            if (now >= it.next().getValue()) {
                it.remove();
            }
        }
    }

    private static long nowTick(final LivingEntity entity) {
        return entity.level().getGameTime();
    }

    /**
     * ⭐ 2026-09-18：从瞬发改成**吟唱**（设计文档 v2 §一）。
     *
     * <p>为什么该改：它的效果是"抹除**周围所有生物**的记忆"，
     * 影响面极大且不分敌我（3 级以下连队友一起忘）——
     * 瞬发意味着"被围住时无脑按一下就能脱身"，没有给对手任何反应窗口。
     * 改成 2 秒吟唱后：① 有被打断的风险，② 队友有机会跑出范围。
     *
     * <p>配套的 {@code castTime = 40}（2 秒）在构造器里。
     * ⚠️ 吟唱期间的表现由 {@code MnemosyneLongCastSpell} 那套统一处理；
     * 本类只负责声明类型。
     */
    @Override
    public CastType getCastType() {
        return CastType.LONG;
    }

    /**
     * 吟唱完成时的领域特效。
     *
     * <p>⭐ 这是"范围技必须看得见范围"的落实：只改判定半径而没有任何视觉，
     * 玩家根本不知道自己影响到多远 —— 而它的副作用（遗忘友军）是**有害**的。
     */
    private void burstDomain(final ServerLevel level, final ServerPlayer caster, final double radius) {
        // 地面一圈涟漪 + 中心爆发，半径直接对应实际判定半径
        for (int i = 0; i < 48; i++) {
            final double angle = (Math.PI * 2 / 48) * i;
            final double x = caster.getX() + Math.cos(angle) * radius;
            final double z = caster.getZ() + Math.sin(angle) * radius;
            level.sendParticles(ParticleTypes.SCULK_SOUL, x, caster.getY() + 0.2D, z,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        level.sendParticles(ParticleTypes.SCULK_CHARGE_POP,
                caster.getX(), caster.getY() + 1.0D, caster.getZ(),
                (int) (radius * 8), radius * 0.6D, 1.2D, radius * 0.6D, 0.05D);
    }

    private static int clampLevelIndex(final int spellLevel) {
        return Math.max(1, Math.min(RADIUS.length, spellLevel)) - 1;
    }

    /** 供调试 / WS-I：当前有几张索敌封锁登记。 */
    public static int blockedTargetingCount() {
        return NO_TARGETING.size();
    }
}
