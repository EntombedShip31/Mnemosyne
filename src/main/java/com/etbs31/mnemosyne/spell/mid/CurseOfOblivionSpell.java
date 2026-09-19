package com.etbs31.mnemosyne.spell.mid;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.OblivionSpell;
import com.etbs31.mnemosyne.util.RaycastHelper;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 遗忘诅咒 Curse of Oblivion —— curse_of_oblivion。
 *
 * <p><b>归属</b>：WS-D2（本文件是 WS-A 建立的 stub，WS-D2 只填 onCast 的方法体）。
 *
 * <p><b>数值来源</b>：docs/tech/04_法术等级强度表.md 第四节第 12 条。
 * 构造器里那 5 个字段<b>逐字未动</b>，只把 {@code extends AbstractSpell} 换成了
 * {@link OblivionSpell}。
 *
 * <p><b>本法术做什么</b>（§四.12 / {@code docs/05 §12}）：在**目标位置**留下一个领域，
 * 领域内的敌人被持续随机遗忘能力，并且**造成的伤害降低 15%~25%**。
 * 它是记忆流派唯一的"区域压制"工具（{@code docs/05 §12} 的"设计意图"）。
 *
 * <table border="1">
 *   <tr><th>等级</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th></tr>
 *   <tr><td>领域半径</td><td>5</td><td>5</td><td>6</td><td>6</td><td>7</td></tr>
 *   <tr><td>持续时间</td><td>8s</td><td>9s</td><td>10s</td><td>12s</td><td>15s</td></tr>
 *   <tr><td>遗忘间隔</td><td>2s</td><td>2s</td><td>1.5s</td><td>1.5s</td><td>1s</td></tr>
 *   <tr><td>减伤</td><td>15%</td><td>15%</td><td>18%</td><td>20%</td><td>25%</td></tr>
 * </table>
 *
 * <p><b>⭐ 2026-09-18 领域表现重做（数值一个没动）</b>：
 * 改版前整个领域只在 {@code openField} 那一刻画一圈 48 颗 ENCHANT 粒子，
 * 之后 8~15 秒里<b>画面完全静止</b> —— 玩家既看不出领域还在不在，也看不出边界在哪。
 * 现在改成"整个存续期持续演出 + 结束后收束 3 秒"：
 * 两圈反向旋转的地面符阵、持续上升的记忆碎片、沿高度上爬的边界光柱，
 * 以及每抹掉一项能力时向外扩散的一圈脉冲（见 {@code Field#render} / {@code Field#pulse}）。
 *
 * <p><b>⭐⭐ 两个设计细节的实现方式</b>
 * <ol>
 *   <li><b>"每 N 秒随机遗忘 1 个，最多同时 3 个"</b> ——
 *       {@code OblivionTier} 的类注释明确要求本类在每一跳调
 *       {@code applyOblivion(caster, target, 1)}（tier 1 的"随机一个"语义），
 *       而**不能**用 tier 2 一次性清空 —— 那会把"持续压制"变成"一键瘫痪"。
 *       本类照做，并自己维护"每个目标已遗忘几个"的计数来卡住 3 个的上限。</li>
 *   <li><b>"离开领域后效果持续 3 秒"</b> —— 每一跳传入的持续时间是
 *       {@code 领域剩余时长 + 3 秒}。{@code OblivionManager} 的合并逻辑取
 *       {@code max(expireTick)}，所以最终到期时刻稳定收敛到"领域结束 + 3 秒"。
 *       若反过来用固定 3 秒，每次刷新都会把上一次的到期时间提前，
 *       目标反而会在领域里"恢复记忆"。</li>
 * </ol>
 *
 * <p><b>⚠️ 减伤的方向</b>：{@code docs/05 §12} 写的是"**造成的**伤害 -15%"，
 * 即领域内敌人**打出来的**伤害变低，不是"受到的伤害变低"。
 * 所以拦截点是 {@code LivingHurtEvent} 里的 {@code source.getEntity()}
 * （攻击者），不是 {@code event.getEntity()}（受害者）。
 * 这个方向如果搞反，诅咒会变成给敌人加护甲。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CurseOfOblivionSpell extends OblivionSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "curse_of_oblivion");

    /** 施法距离 20 格（§四.12 的"施法距离"行）。 */
    private static final float RANGE = 20.0F;

    /** 各等级的领域半径（格，index = level - 1）。§二 遗忘诅咒 的"半径"列。 */
    private static final double[] RADIUS = {5.0D, 5.3D, 5.6D, 5.9D, 6.1D, 6.4D, 6.7D, 7.0D};

    /** 各等级的持续时间（秒，index = level - 1）。§二 遗忘诅咒 的"持续（秒）"列。 */
    private static final int[] DURATION_SECONDS = {8, 9, 10, 11, 12, 13, 14, 15};

    /** 各等级的遗忘间隔（tick，index = level - 1）。§二 遗忘诅咒 的"间隔（tick）"列。 */
    private static final int[] FORGET_INTERVAL_TICKS = {40, 37, 34, 31, 29, 26, 23, 20};

    /** 各等级的减伤比例（index = level - 1）。§二 遗忘诅咒 的"目标减伤"列。 */
    private static final float[] DAMAGE_REDUCTION =
            {0.150F, 0.164F, 0.179F, 0.193F, 0.207F, 0.221F, 0.236F, 0.250F};

    /** 4 级起 ⭐ 保护友军：领域不再遗忘 / 削弱施法者自己人。 */
    private static final int LEVEL_FOR_ALLY_PROTECTION = 4;

    /** §四.12："最多同时遗忘 3 个能力"。 */
    private static final int MAX_FORGOTTEN = 3;

    /** §四.12："离开领域后效果持续 3s"。 */
    private static final int LINGER_TICKS = 60;

    // ------------------------------------------------------------------
    // 领域视觉参数（2026-09-18 加强）
    //
    // ⭐ 改版前：领域只在 openField 那一刻画一圈 48 颗 ENCHANT 粒子，
    //    之后 8~15 秒里**画面完全静止** —— 玩家看不出领域还在不在、边界在哪。
    //    现在改成"整个存续期持续演出 + 结束后收束 3 秒"。
    //
    // 发包预算（每 tick 平均粒子数，乘以在线玩家数）：
    //    符阵 36/3 = 12  + 上升碎片 2  + 边界光柱 12/5 ≈ 2.4  → 约 16/tick
    // 与一场持续 15 秒的领域相加约 4800 颗，量级和原版刷怪笼/营火一个档次。
    // ------------------------------------------------------------------

    /** 地面符阵的重画间隔（tick）。每 3 tick 画一次 —— 20→6.7 Hz 肉眼看仍是连续转动，发包量降到 1/3。 */
    private static final int RUNE_DRAW_INTERVAL = 3;

    /** 外圈符文段数（靛蓝 {@code ENCHANT}）。 */
    private static final int RUNE_OUTER_SEGMENTS = 24;

    /** 内圈符文段数（品红 {@code WITCH}）。 */
    private static final int RUNE_INNER_SEGMENTS = 12;

    /** 内圈半径 = 领域半径 × 该系数（两圈同心，视觉上才有"阵"的感觉）。 */
    private static final double RUNE_INNER_SCALE = 0.55D;

    /** 符阵的角速度（弧度 / tick）。外圈正转、内圈反转且更快。 */
    private static final double RUNE_SPIN = 0.035D;

    /** 每 tick 从领域内升起的"记忆碎片"数量。 */
    private static final int RISING_MOTES_PER_TICK = 2;

    /** 边界光柱的重画间隔（tick）。 */
    private static final int PILLAR_INTERVAL = 5;

    /** 边界光柱数量（沿圆周均分）。 */
    private static final int PILLAR_COUNT = 12;

    /** 边界光柱的高度（格）—— 给领域一个"看得见的边"。 */
    private static final double PILLAR_HEIGHT = 3.0D;

    /** 视觉残留时长：领域消失后符阵还会向内收束这么多 tick（与 {@link #LINGER_TICKS} 同值）。 */
    private static final int VISUAL_LINGER_TICKS = LINGER_TICKS;

    /**
     * 实际施加遗忘时用的层级：**tier 1（"随机一个"）**。
     *
     * <p>与 {@link #getOblivionTier()}（返回 2）的区别是刻意的：
     * {@code getOblivionTier()} 只用于 BOSS 降级的分档判定，
     * 而真正"摘行为"这一动作按 {@code OblivionTier} 的类注释要求
     * ——"遗忘诅咒每 2 秒调一次 tier 1" —— 必须是 tier 1。
     * <br>声明成常量而不是在 {@link OblivionManagerBridge} 里写字面量 {@code 1}：
     * 开局那一下与领域内每一跳必须同层级，两处各写一个 {@code 1} 迟早会分家。
     */
    private static final int FIELD_OBLIVION_TIER = 1;

    /** 正在生效的领域。数量极少（每个施法者至多 1 个），用普通列表足够。 */
    private static final List<Field> FIELDS = new ArrayList<>();

    /**
     * 领域内敌人的"出手减伤"登记表：实体 UUID → [到期刻, 减伤比例]。
     *
     * <p>用一张扁平表而不是"从领域反查实体"：{@code LivingHurtEvent} 是热路径，
     * 每次受伤都遍历所有领域并算距离会明显浪费。登记表让判定退化成一次哈希查找。
     */
    private static final Map<UUID, float[]> OUTGOING = new ConcurrentHashMap<>();

    public CurseOfOblivionSpell() {
        super(memoryConfig(SpellRarity.RARE, 25.0D, 8));
        this.baseManaCost = 62;
        this.manaCostPerLevel = 12;
        this.baseSpellPower = 1;
        this.spellPowerPerLevel = 0;
        this.castTime = 20;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return SPELL_ID;
    }

    /**
     * ⭐ 覆写为 {@link CastType#LONG} —— 遗忘诅咒需要一段 20 tick 的起手。
     *
     * <p><b>为什么必须覆写</b>：基类 {@link OblivionSpell#getCastType()} 返回
     * {@code INSTANT}，而 ISS 在 {@code INSTANT} 下会**强制把 {@code castTime} 归零**，
     * 于是构造器里那句 {@code this.castTime = 20} 会被静默忽略 ——
     * §二 遗忘诅咒 的吟唱列是 20 tick，其满级消耗率 5.62 = 146 / (25 + 1)
     * 也正是按这 20 tick（1 秒）算出来的。所以要拿到规范里的 20 tick 起手，
     * 只能声明为 {@code LONG}（基类该方法非 final，可覆写）。
     */
    @Override
    public CastType getCastType() {
        return CastType.LONG;
    }

    /** 领域本身不是"遗忘一个目标"，层级只用于 BOSS 降级判定 —— 取 tier 2。 */
    @Override
    protected int getOblivionTier() {
        return 2;
    }

    /**
     * 对**单个**目标施加遗忘 —— {@code OblivionSpell} 的抽象方法。
     *
     * <p>本法术有两条施加路径，都必须落到同一套规则上：
     * <ol>
     *   <li><b>开局那一下</b>：{@code onCast → openField}，玩家瞄到的那个目标立即被摘一次。
     *       走的是基类的 {@code applyTo → applyOblivion}，也就是本方法。</li>
     *   <li><b>领域存续期间</b>：{@link Field#tick} 每 {@code FORGET_INTERVAL_TICKS} 摘一次，
     *       走 {@link OblivionManagerBridge#apply}（它显式传 tier 1 与"剩余时长 + 余韵"）。</li>
     * </ol>
     *
     * <p>⭐ 两条路径的**层级都是 tier 1**（"随机一个"），这是 {@code OblivionTier}
     * 类注释明确要求的："遗忘诅咒每 2 秒调一次 tier 1"。所以本方法虽然
     * {@link #getOblivionTier()} 返回 2（那是给 BOSS 降级分档用的），
     * 实际施加时用的是 1 —— 与领域内每 2 秒那一次保持一致，
     * 否则"开局那一下"会一次摘掉目标全部行为，比领域内每次摘一个强得多。
     *
     * <p>时长同样用 tier 1 的等级表（{@code docs/tech/04} §四.12：8/9/10/12/15 秒）。
     */
    @Override
    protected void applyOblivion(final ServerPlayer caster, final LivingEntity target, final int spellLevel) {
        oblivionManager(caster, target, FIELD_OBLIVION_TIER, durationTicksOf(spellLevel));
    }

    /** §四.12 的"持续时间"列 → tick。开局那一下与领域内 tick 共用同一张表。 */
    private static int durationTicksOf(final int spellLevel) {
        return DURATION_SECONDS[clampLevelIndex(spellLevel)] * 20;
    }

    /** 施法音效：{@code spell.curse_of_oblivion.cast}（docs/tech/08 §3.2）。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_CURSE_OF_OBLIVION_CAST.get());
    }

    /**
     * 没瞄到目标就不消耗法力。
     *
     * <p>领域必须落在"某个位置"，而 §四.12 明确是"在**目标**位置生成"。
     * 没目标就没地方放 —— 与 {@code EncodeSpell} 同一条原则。
     */
    @Override
    public boolean checkPreCastConditions(final Level level, final int spellLevel,
                                          final LivingEntity entity, final MagicData playerMagicData) {
        if (entity instanceof ServerPlayer player
                && RaycastHelper.findLivingTarget(level, player, RANGE, true, true) == null) {
            player.displayClientMessage(Component.translatable("mnemosyne.msg.curse_no_target"), true);
            return false;
        }
        return super.checkPreCastConditions(level, spellLevel, entity, playerMagicData);
    }

    /**
     * {@inheritDoc}
     *
     * <p>刻意**不**走基类的 {@code resolveTarget → applyTo} 流程：基类那一套是"单体立即结算"，
     * 而本法术是"留下一个持续领域，之后由 tick 驱动"。领域内的每个目标仍然会走
     * {@link #applyTo}（因此 BOSS 免疫、玩家免疫、通用降级全部照旧生效）。
     */
    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide && entity instanceof ServerPlayer caster) {
            final LivingEntity target = resolveTarget(level, caster);
            if (target != null) {
                openField(caster, target.position(), spellLevel);
            }
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }

    private static void openField(final ServerPlayer caster, final Vec3 center, final int spellLevel) {
        final int index = clampLevelIndex(spellLevel);
        final long now = nowTick(caster);
        final long endTick = now + DURATION_SECONDS[index] * 20L;
        // 同一个施法者再放一次 → 覆盖旧的（旧领域的登记表会被新领域接管，见 tick 的清理）
        FIELDS.removeIf(field -> field.caster == caster);
        FIELDS.add(new Field(caster, center, RADIUS[index], endTick,
                FORGET_INTERVAL_TICKS[index], DAMAGE_REDUCTION[index],
                spellLevel >= LEVEL_FOR_ALLY_PROTECTION, now));

        if (caster.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, center.x, center.y, center.z,
                    ModSounds.SPELL_CURSE_OF_OBLIVION_CAST.get(), SoundSource.PLAYERS, 1.0F, 0.8F);
            burst(serverLevel, center, RADIUS[index]);
        }
    }

    /**
     * 领域展开的三重起手表现（一次性）。
     *
     * <p>① 外圈 48 段向外炸开（靛蓝）；② 内圈 24 段反向炸开（品红）；
     * ③ 中心 16 道向上冲的"记忆碎片"。三层方向不同，读起来才像"一个阵被撑开"，
     * 而不是"一圈粒子凭空出现"。
     */
    private static void burst(final ServerLevel level, final Vec3 center, final double radius) {
        final double y = center.y + 0.15D;
        for (int i = 0; i < 48; i++) {
            final double a = i / 48.0D * Math.PI * 2.0D;
            final double cos = Math.cos(a);
            final double sin = Math.sin(a);
            emit(level, ParticleTypes.ENCHANT,
                    center.x + cos * radius, y, center.z + sin * radius,
                    cos * 0.35D, 0.06D, sin * 0.35D);
        }
        for (int i = 0; i < 24; i++) {
            final double a = -i / 24.0D * Math.PI * 2.0D;
            final double cos = Math.cos(a);
            final double sin = Math.sin(a);
            emit(level, ParticleTypes.WITCH,
                    center.x + cos * radius * RUNE_INNER_SCALE, y + 0.25D,
                    center.z + sin * radius * RUNE_INNER_SCALE,
                    -cos * 0.20D, 0.12D, -sin * 0.20D);
        }
        for (int i = 0; i < 16; i++) {
            final double a = i / 16.0D * Math.PI * 2.0D;
            emit(level, ParticleTypes.SOUL_FIRE_FLAME,
                    center.x + Math.cos(a) * 0.45D, y, center.z + Math.sin(a) * 0.45D,
                    Math.cos(a) * 0.05D, 0.30D, Math.sin(a) * 0.05D);
        }
    }

    /**
     * 发一颗**方向确定**的粒子。
     *
     * <p><b>⭐⭐ 为什么不能直接用 {@code ServerLevel.sendParticles} 的 9 参重载</b>：
     * {@code ClientPacketListener.handleParticleEvent} 有两条互斥分支 ——
     * <ul>
     *   <li>{@code count > 0}：速度是三个 {@code nextGaussian() * maxSpeed}，
     *       <b>方向完全随机</b>，画不出符阵和光柱；</li>
     *   <li>{@code count == 0}：把 {@code (xDist, yDist, zDist) * maxSpeed}
     *       当作<b>确定的速度向量</b>。</li>
     * </ul>
     * 而 9 参重载内部是 {@code for (i = 0; i < count; i++)}，
     * {@code count = 0} 时<b>一颗都不发</b>。所以只能走
     * {@code sendParticles(ServerPlayer, ...)} 这个不循环的重载。
     *
     * <p>逐玩家发还顺带拿到了原版的距离裁剪（该重载内建 32 格判定），
     * 比 {@code PacketDistributor.ALL} 广播全服干净得多。
     */
    private static void emit(final ServerLevel level, final ParticleOptions type,
                             final double x, final double y, final double z,
                             final double vx, final double vy, final double vz) {
        for (final ServerPlayer viewer : level.players()) {
            level.sendParticles(viewer, type, false, x, y, z, 0, vx, vy, vz, 1.0D);
        }
    }

    // ==================================================================
    // 领域驱动
    // ==================================================================

    @SubscribeEvent
    public static void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || FIELDS.isEmpty()) {
            return;
        }
        final long now = event.getServer().overworld().getGameTime();
        for (final Iterator<Field> it = FIELDS.iterator(); it.hasNext(); ) {
            final Field field = it.next();
            // ⭐ 删除判据用 visualEndTick（领域结束 + 视觉残留 3 秒）而不是 endTick：
            //    用 endTick 的话领域一到期，粒子也在同一 tick 断掉，"收束"根本看不到。
            if (now >= field.visualEndTick || field.caster.isRemoved() || !field.caster.isAlive()) {
                it.remove();
                continue;
            }
            field.tick(now);
        }
        // 出手减伤的到期清理（与领域解耦：即使领域提前消失，登记也会自然过期）
        if (!OUTGOING.isEmpty()) {
            for (final Iterator<Map.Entry<UUID, float[]>> it = OUTGOING.entrySet().iterator(); it.hasNext(); ) {
                if (now >= (long) it.next().getValue()[0]) {
                    it.remove();
                }
            }
        }
    }

    /** 领域内敌人的出手减伤。 */
    @SubscribeEvent
    public static void onLivingHurt(final LivingHurtEvent event) {
        if (event.getAmount() <= 0.0F) {
            return;
        }
        final var attacker = event.getSource().getEntity();
        if (attacker == null) {
            return;
        }
        final float[] entry = OUTGOING.get(attacker.getUUID());
        if (entry == null || event.getEntity().level().getGameTime() >= (long) entry[0]) {
            return;
        }
        event.setAmount(event.getAmount() * (1.0F - entry[1]));
    }

    /** 一个正在生效的领域。 */
    private static final class Field {

        private final ServerPlayer caster;
        private final Vec3 center;
        private final double radius;
        private final long endTick;
        /** 视觉收尾刻 = {@code endTick + 3 秒}。玩法早已停止，但符阵还在向内收束。 */
        private final long visualEndTick;
        private final int intervalTicks;
        private final float damageReduction;
        /**
         * ⭐ 4 级起"保护友军"：为 {@code true} 时领域跳过施法者自己与友军。
         *
         * <p>1~3 级为 {@code false} —— 领域不分敌我，队友站在里面同样会被遗忘、被减伤，
         * 这正是 §二 把"保护友军"列为 4 级解锁的原因。
         */
        private final boolean protectAllies;
        private long nextForgetTick;
        /** 每个目标已经被遗忘的能力数（§四.12 的"最多 3 个"）。 */
        private final Map<UUID, Integer> forgotten = new ConcurrentHashMap<>();

        private Field(final ServerPlayer caster, final Vec3 center, final double radius,
                      final long endTick, final int intervalTicks, final float damageReduction,
                      final boolean protectAllies, final long now) {
            this.caster = caster;
            this.center = center;
            this.radius = radius;
            this.endTick = endTick;
            this.visualEndTick = endTick + VISUAL_LINGER_TICKS;
            this.intervalTicks = intervalTicks;
            this.damageReduction = damageReduction;
            this.protectAllies = protectAllies;
            this.nextForgetTick = now + intervalTicks;
        }

        /**
         * 每 tick 一次：先跑玩法，再画表现。
         *
         * <p>两者**故意分开**：玩法严格在 {@code now < endTick} 内生效，
         * 而表现一直画到 {@link #visualEndTick}。所以领域"结束"之后
         * 玩家还能看到 3 秒的收束动画，与 §四.12 的"领域消失后残留 3 秒"对齐。
         */
        private void tick(final long now) {
            if (now < endTick) {
                gameplay(now);
            }
            render(now);
        }

        /** 玩法层：出手减伤登记 + 周期性遗忘。 */
        private void gameplay(final long now) {
            final boolean forgetNow = now >= nextForgetTick;
            if (forgetNow) {
                nextForgetTick = now + intervalTicks;
            }
            // ⭐ 4 级起跳过施法者与友军（protectAllies）；1~3 级领域不分敌我。
            //    excludeAllies = false 时 findLivingInSphere 会把施法者自己也收进来，
            //    所以下面显式跳过 caster —— 否则他会给自己的出手"减伤"。
            for (final LivingEntity living : RaycastHelper.findLivingInSphere(
                    caster.level(), center, radius, caster, protectAllies)) {
                if (living == caster) {
                    continue;
                }
                // ① 出手减伤：登记到"领域结束 + 3 秒"，离开领域后仍然持续（§四.12）
                OUTGOING.put(living.getUUID(),
                        new float[]{endTick + LINGER_TICKS, damageReduction});

                // ② 每 N 秒随机遗忘一个能力，最多 3 个
                if (!forgetNow) {
                    continue;
                }
                final int count = forgotten.getOrDefault(living.getUUID(), 0);
                if (count >= MAX_FORGOTTEN) {
                    continue;
                }
                // 持续时间 = 领域剩余 + 3 秒；OblivionManager 取 max(expireTick)，
                // 所以多次刷新会稳定收敛到"领域结束 + 3 秒"（见类注释）。
                final int remaining = (int) Math.max(1L, endTick - now) + LINGER_TICKS;
                if (OblivionManagerBridge.apply(caster, living, remaining)) {
                    forgotten.put(living.getUUID(), count + 1);
                }
            }
            if (forgetNow) {
                pulse();
            }
        }

        /**
         * 表现层：四层叠加，整个存续期都在动。
         *
         * <ol>
         *   <li><b>地面符阵</b> —— 两圈同心反向旋转的符文（外圈靛蓝 24 段、内圈品红 12 段）。
         *       角度由 {@code now} 驱动，所以是**真的在转**，不是原地闪。</li>
         *   <li><b>上升的记忆碎片</b> —— 领域内随机取点、给一个确定的上抛速度，
         *       把"记忆正在被抽走"画出来。</li>
         *   <li><b>边界光柱</b> —— 圆周 12 个方位、沿高度上移的光点，
         *       让领域有一个<b>看得见的边</b>，玩家一眼就知道"别站里面"。</li>
         *   <li><b>收束</b> —— 领域结束后半径从满缩到 0，视觉上"闭拢"。</li>
         * </ol>
         */
        private void render(final long now) {
            if (!(caster.level() instanceof ServerLevel level)) {
                return;
            }
            final boolean active = now < endTick;
            // 残留期：一个 0→1 的衰减系数同时控制半径与密度
            final double fade = active ? 1.0D
                    : Math.max(0.0D, 1.0D - (double) (now - endTick) / VISUAL_LINGER_TICKS);
            final double r = active ? radius : radius * fade;
            if (r <= 0.08D) {
                return;
            }
            final double y = center.y + 0.12D;
            final RandomSource random = level.random;

            // ① 地面符阵（每 RUNE_DRAW_INTERVAL tick 重画一次，靠 now 驱动角度 = 真在转）
            if (now % RUNE_DRAW_INTERVAL == 0L) {
                final double spin = now * RUNE_SPIN;
                for (int i = 0; i < RUNE_OUTER_SEGMENTS; i++) {
                    final double a = spin + i / (double) RUNE_OUTER_SEGMENTS * Math.PI * 2.0D;
                    emit(level, ParticleTypes.ENCHANT,
                            center.x + Math.cos(a) * r, y, center.z + Math.sin(a) * r,
                            0.0D, 0.008D, 0.0D);
                }
                for (int i = 0; i < RUNE_INNER_SEGMENTS; i++) {
                    final double a = -spin * 1.7D + i / (double) RUNE_INNER_SEGMENTS * Math.PI * 2.0D;
                    emit(level, ParticleTypes.WITCH,
                            center.x + Math.cos(a) * r * RUNE_INNER_SCALE, y + 0.18D,
                            center.z + Math.sin(a) * r * RUNE_INNER_SCALE,
                            0.0D, 0.015D, 0.0D);
                }
            }

            // ② 上升的记忆碎片（sqrt 分布 → 面积均匀，不会全挤在圆心）
            for (int i = 0; i < RISING_MOTES_PER_TICK; i++) {
                final double a = random.nextDouble() * Math.PI * 2.0D;
                final double d = Math.sqrt(random.nextDouble()) * r;
                final double cos = Math.cos(a);
                final double sin = Math.sin(a);
                emit(level, random.nextBoolean() ? ParticleTypes.SOUL : ParticleTypes.SOUL_FIRE_FLAME,
                        center.x + cos * d, y + 0.1D, center.z + sin * d,
                        -cos * 0.006D, 0.045D + random.nextDouble() * 0.035D, -sin * 0.006D);
            }

            // ③ 边界光柱（只在领域生效期画；沿高度按相位上移，读起来像光在往上爬）
            if (active && now % PILLAR_INTERVAL == 0L) {
                final double phase = (now % (PILLAR_INTERVAL * 4L)) / (double) (PILLAR_INTERVAL * 4L);
                for (int i = 0; i < PILLAR_COUNT; i++) {
                    final double a = i / (double) PILLAR_COUNT * Math.PI * 2.0D;
                    final double px = center.x + Math.cos(a) * r;
                    final double pz = center.z + Math.sin(a) * r;
                    emit(level, ParticleTypes.SOUL_FIRE_FLAME, px, y + phase * PILLAR_HEIGHT, pz,
                            0.0D, 0.02D, 0.0D);
                }
            }
        }

        /**
         * 每次"抹掉一项能力"时向外扩散的一圈脉冲。
         *
         * <p>它的作用是<b>节奏可见</b>：玩家能凭脉冲次数数出"已经抹了几项"
         * （与 {@link #MAX_FORGOTTEN} 的 3 项上限对应），不用去读状态栏。
         */
        private void pulse() {
            if (!(caster.level() instanceof ServerLevel level)) {
                return;
            }
            final double y = center.y + 0.25D;
            for (int i = 0; i < 24; i++) {
                final double a = i / 24.0D * Math.PI * 2.0D;
                final double cos = Math.cos(a);
                final double sin = Math.sin(a);
                emit(level, ParticleTypes.SCULK_SOUL,
                        center.x + cos * radius * 0.85D, y, center.z + sin * radius * 0.85D,
                        cos * 0.12D, 0.04D, sin * 0.12D);
            }
        }
    }

    /**
     * 把"领域每一跳施加一次 tier 1 遗忘"这个动作桥接出去。
     *
     * <p>为什么需要一个静态桥：{@link Field} 是静态嵌套类，拿不到外层实例的
     * {@code oblivionManager(...)}（那是实例方法）。
     * 这里直接调 {@code OblivionManager} 的静态入口 —— 与基类方法体完全相同。
     */
    private static final class OblivionManagerBridge {

        private static boolean apply(final ServerPlayer caster, final LivingEntity target,
                                     final int durationTicks) {
            return com.etbs31.mnemosyne.oblivion.OblivionManager
                    .applyOblivion(caster, target, FIELD_OBLIVION_TIER, durationTicks);
        }
    }

    private static long nowTick(final ServerPlayer player) {
        return player.server.overworld().getGameTime();
    }

    private static int clampLevelIndex(final int spellLevel) {
        return Math.max(1, Math.min(RADIUS.length, spellLevel)) - 1;
    }

    /** 供调试 / WS-I：当前有几个领域在生效。 */
    public static int activeFieldCount() {
        return FIELDS.size();
    }
}
