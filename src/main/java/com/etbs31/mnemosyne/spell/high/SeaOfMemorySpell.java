package com.etbs31.mnemosyne.spell.high;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.capability.MnemosyneData;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.MnemosyneSpell;
import com.etbs31.mnemosyne.spell.low.MemoryArrowSpell;
import com.etbs31.mnemosyne.util.RaycastHelper;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * 忆海 Sea of Memory —— sea_of_memory。
 *
 * <p><b>归属</b>：WS-D3（本文件是 WS-A 建立的 stub，WS-D3 只填 onCast 的方法体）。
 *
 * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「忆海」（<b>唯一事实来源</b>，
 * 最大等级 10）；设计背景见 docs/tech/04_法术等级强度表.md 第四节第 17 条。
 * 构造器里那 5 个字段<b>逐字未动</b>，只把 {@code extends AbstractSpell} 换成了
 * {@link MnemosyneSpell}。
 *
 * <p><b>本法术做什么</b>（§四.17）：在脚下张开一片"记忆之海"领域，持续 15~30 秒。
 * 领域内自己的忆格上限 +3、每格共鸣 +30%~+40%、写入类法术不消耗施法时间，
 * 同时每秒给领域内的敌人叠 1 层「认知过载」。领域结束时对仍在领域内的敌人
 * **释放一次「认知崩坏」**（按当前层数结算）—— 所以它是"忆矢叠层 → 忆海放大 →
 * 认知崩坏兑现"这条爆发链的收束点。
 *
 * <table border="1">
 *   <tr><th>等级</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th>
 *       <th>6</th><th>7</th><th>8</th><th>9</th><th>10</th></tr>
 *   <tr><td>半径</td><td>10.0</td><td>10.4</td><td>10.9</td><td>11.3</td><td>11.8</td>
 *       <td>12.2</td><td>12.7</td><td>13.1</td><td>13.6</td><td>14.0</td></tr>
 *   <tr><td>持续</td><td>15s</td><td>17s</td><td>18s</td><td>20s</td><td>22s</td>
 *       <td>23s</td><td>25s</td><td>27s</td><td>28s</td><td>30s</td></tr>
 *   <tr><td>每格共鸣</td><td>+30.0%</td><td>+31.1%</td><td>+32.2%</td><td>+33.3%</td><td>+34.4%</td>
 *       <td>+35.6%</td><td>+36.7%</td><td>+37.8%</td><td>+38.9%</td><td>+40.0%</td></tr>
 *   <tr><td>层数上限</td><td>8</td><td>9</td><td>10</td><td>10</td><td>11</td>
 *       <td>12</td><td>13</td><td>13</td><td>14</td><td>15</td></tr>
 * </table>
 *
 * <p><b>⭐⭐ "领域内写入不消耗施法时间"是怎么做到的</b>：
 * 覆写 {@code getEffectiveCastTime(int, LivingEntity)}（在 {@code EncodeSpell} 基类里，
 * 三个写入法术一次覆盖）返回 0，条件是 {@link #isInsideField}。
 * <br>这条路径**不会造成客户端/服务端的施法条不同步**，因为实测
 * {@code AbstractSpell.attemptInitiateCast} 的**第一行**就是
 * {@code if (level.isClientSide) return false;} —— {@code getEffectiveCastTime}
 * 只在服务端被调用，算出来的值再通过 {@code UpdateCastingStatePacket} 推给客户端。
 * 换句话说：**LONG 法术的施法时长是服务端算好再告诉客户端的**，
 * 所以带施法者的 per-cast 覆写是安全的。（这条结论是本次串行集成时读 ISS 源码确认的。）
 *
 * <p><b>⚠️ 两处已知偏差（都已登记在交付说明里）</b>
 * <ol>
 *   <li><b>领域是"落点固定"而不是"跟随施法者"</b>：§四.17 只写了"领域内"，
 *       没有说它跟不跟人。选择固定落点的理由是可预测性 —— 跟随施法者意味着
 *       敌人永远逃不出去，而且"领域结束时对领域内所有敌人结算"会变成
 *       "只要没跑远就必中"，强度远超 §五 的校验基准。</li>
 *   <li><b>领域结束时的「认知崩坏」用的是本法术自己的等级</b>去查
 *       {@code CognitiveCollapseSpell} 的每层伤害表。§四.17 只写"按当前层数结算"，
 *       而 §四.17 的"理论最高结算 15 层 × 12 伤害 = 180"里的 **12** 正是
 *       认知崩坏**满级**的每层伤害 —— 用同一个等级索引与该理论值一致。</li>
 * </ol>
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SeaOfMemorySpell extends MnemosyneSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "sea_of_memory");

    /**
     * 各等级的领域半径（格，index = level − 1）。
     *
     * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「忆海」的"半径（格）"列：
     * L1~L10 = <b>10.0 / 10.4 / 10.9 / 11.3 / 11.8 / 12.2 / 12.7 / 13.1 / 13.6 / 14.0</b>。
     */
    private static final double[] RADIUS = {10.0D, 10.4D, 10.9D, 11.3D, 11.8D, 12.2D, 12.7D, 13.1D, 13.6D, 14.0D};

    /**
     * 领域持续时间（秒，index = level − 1）。
     *
     * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「忆海」的"持续（秒）"列：
     * L1~L10 = <b>15 / 17 / 18 / 20 / 22 / 23 / 25 / 27 / 28 / 30</b>。
     *
     * <p>⭐ 2026-09-18：<b>整体拉长约 2 倍</b>（原 {@code 8/9/10/12/15}）。
     * 理由：忆海是 LEGENDARY 的 120 法力 / 90 秒冷却大招，而旧时长下
     * "把敌人泡在里面叠层、等结算"这个核心玩法根本来不及展开 ——
     * 8 秒只够叠 8 层，敌人随便走两步就出去了。
     * 拉长之后才有"布场 → 拖时间 → 收割"的节奏。
     *
     * <p>⚠️ 随之而来的平衡影响（刻意接受）：结算时 {@code close()} 会对领域内所有人
     * 释放一次「认知崩坏」，时长越长 = 越容易把人留在里面。
     * 这本来就是"控场型传说法术"该有的强度，且层数上限 {@link #LAYER_CAP} 没动。
     */
    private static final int[] DURATION_SECONDS = {15, 17, 18, 20, 22, 23, 25, 27, 28, 30};

    /**
     * 各等级的每格共鸣基准（index = level − 1）。
     *
     * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「忆海」的"共鸣/格"列：
     * L1~L10 = <b>0.300 / 0.311 / 0.322 / 0.333 / 0.344 / 0.356 / 0.367 / 0.378 / 0.389 / 0.400</b>。
     */
    private static final double[] RESONANCE_PER_SLOT = {0.300D, 0.311D, 0.322D, 0.333D, 0.344D, 0.356D, 0.367D, 0.378D, 0.389D, 0.400D};

    /**
     * 各等级的认知过载层数上限（index = level − 1）。
     *
     * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「忆海」的"叠层上限"列：
     * L1~L10 = <b>8 / 9 / 10 / 10 / 11 / 12 / 13 / 13 / 14 / 15</b>。
     *
     * <p>⚠️ 本值经 {@code MemoryArrowSpell.stackCognitiveOverloadInSea} 时还会被
     * {@code Config.Overload.MAX_STACKS_IN_SEA} 再钳一次 —— 那是配置侧的天花板，刻意保留。
     */
    private static final int[] LAYER_CAP = {8, 9, 10, 10, 11, 12, 13, 13, 14, 15};

    /** §四.17："领域内：忆格上限 +3"。 */
    private static final int TEMP_SLOTS = 3;

    /** §四.17："敌人每秒叠 1 层认知过载"。 */
    private static final int STACK_INTERVAL_TICKS = 20;

    /**
     * 当前生效的领域。
     *
     * <p>为什么用静态表而不是给实体挂 NBT：领域是一个**世界侧的区域**，
     * 它既不属于施法者（施法者可以走开、可以死），也不属于任何实体 ——
     * 挂在谁身上都会出现"载体没了领域就没了"的错误语义。
     * 静态表只在服务端主线程被读写（{@code onCast} 与 {@code ServerTickEvent} 同线程），
     * 所以不需要并发容器。
     *
     * <p>⚠️ 与 {@code CognitiveCollapseSpell.STUNNED} 的取舍一致：
     * 服务端重启/区块卸载后领域丢失，这是可接受的（领域最长只有 15 秒）。
     */
    private static final List<Field> FIELDS = new ArrayList<>();

    /** 一片「忆海」领域。 */
    private static final class Field {

        private final ServerPlayer caster;
        private final ServerLevel level;
        private final Vec3 center;
        private final double radius;
        private final long expireTick;
        /** 开领域的刻（算老化比例用）。 */
        private final long startTick;
        private final int spellLevel;
        private final int capLayers;
        /** 下一次叠层的刻（每秒推进一次）。 */
        private long nextStackTick;
        /** 三个特效层各自的下一拍（tick）。分开计时，互不干扰 —— 见 {@link #spawnAmbience}。 */
        private long nextTideTick;
        private long nextRingTick;
        private long nextMoteTick;

        /** 领域的总时长（tick）—— 特效强度按"老化比例"计算时需要。 */
        private int totalTicks() {
            return Math.max(1, (int) (expireTick - startTick));
        }

        private Field(final ServerPlayer caster, final ServerLevel level, final Vec3 center,
                      final double radius, final long expireTick, final int spellLevel,
                      final int capLayers, final long nextStackTick) {
            this.caster = caster;
            this.level = level;
            this.center = center;
            this.radius = radius;
            this.expireTick = expireTick;
            // ⚠️ open() 传进来的 nextStackTick 就是"开领域那一刻"（见 open 的调用），
            //    所以开始刻直接用它 —— 不要另算，那会引入第二个真相来源。
            this.startTick = nextStackTick;
            this.spellLevel = spellLevel;
            this.capLayers = capLayers;
            this.nextStackTick = nextStackTick;
            this.nextTideTick = nextStackTick;
            this.nextRingTick = nextStackTick;
            this.nextMoteTick = nextStackTick;
        }
    }

    public SeaOfMemorySpell() {
        // 只把 DefaultConfig 交给基类；下面 5 个数值字段是 WS-A 冻结的契约，逐字不动。
        super(memoryConfig(SpellRarity.EPIC, 90.0D, 10));
        this.baseManaCost = 71;
        this.manaCostPerLevel = 14;
        this.baseSpellPower = 1;
        this.spellPowerPerLevel = 0;
        this.castTime = 60;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return SPELL_ID;
    }

    /** docs/tech/04 §三 总表：忆海是 LONG（castTime 60，不随等级变）。 */
    @Override
    public CastType getCastType() {
        return CastType.LONG;
    }

    /** 吟唱起手音：{@code spell.sea_of_memory.charge}（docs/tech/08 §3.2）。 */
    @Override
    public Optional<SoundEvent> getCastStartSound() {
        return Optional.of(ModSounds.SPELL_SEA_OF_MEMORY_CHARGE.get());
    }

    /** 领域张开音：{@code spell.sea_of_memory.loop}（docs/tech/08 §3.2）。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_SEA_OF_MEMORY_LOOP.get());
    }

    // ==================================================================
    // 落地
    // ==================================================================

    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide && entity instanceof ServerPlayer caster
                && level instanceof ServerLevel serverLevel) {
            open(caster, serverLevel, spellLevel);
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }

    private static void open(final ServerPlayer caster, final ServerLevel level, final int spellLevel) {
        final int index = clampLevelIndex(spellLevel);
        final int durationTicks = DURATION_SECONDS[index] * 20;
        final long now = level.getServer().overworld().getGameTime();

        FIELDS.add(new Field(caster, level, caster.position(), RADIUS[index],
                now + durationTicks, spellLevel, LAYER_CAP[index], now));

        // 领域内：忆格上限 +3。到期自动收回（tempExpire 自己会过期），
        // 所以**不要**在领域结束时去 clearTempSlots —— 那会把「忆格扩张」给的临时格一起清掉。
        MnemosyneData.addTempSlots(caster, TEMP_SLOTS, durationTicks);

        // 领域内：每格共鸣 +30%~+40%（覆盖配置基准 0.20）。
        // 法力惩罚与施法速度惩罚传 1.0 = 不改写（applyEngramBuff 的合并规则是取更有利的一方，
        // 传中性值不会把「忆格扩张」已经拿到的减半顶掉）。
        MnemosyneData.applyEngramBuff(caster, durationTicks, RESONANCE_PER_SLOT[index], 1.0D, 1.0D, false);

        // ⭐ 开领域爆发：先给一次"水漫上来"的强反馈，之后交给 spawnAmbience 维持。
        //    开场必须响 —— 否则 120 法力砸下去只有一行动作栏，玩家会怀疑没放出来。
        level.sendParticles(ParticleTypes.ENCHANT,
                caster.getX(), caster.getY() + 0.15D, caster.getZ(),
                48, RADIUS[index] * 0.9D, 0.1D, RADIUS[index] * 0.9D, 0.02D);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                caster.getX(), caster.getY() + 1.2D, caster.getZ(),
                36, RADIUS[index] * 0.7D, 1.0D, RADIUS[index] * 0.7D, 0.03D);

        // 立刻同步一次忆格：HUD 要马上显示多出来的 3 格。
        // （MnemosyneData 的脏标记最迟 20 tick 后也会发，但"领域开了、格子一秒后才出现"
        //   是明显的手感问题。）
        MnemosyneData.notifyEngramChange(caster);
    }

    // ==================================================================
    // 特效（⭐ 2026-09-18 新增 —— 在此之前忆海**完全没有视觉表现**）
    // ==================================================================

    /**
     * 特效的三个层次各自的节奏（tick）。
     *
     * <p><b>⭐⭐ 为什么要分三个节奏、而不是每 tick 一起发</b>：
     * 粒子的开销几乎全在**发包次数**上（{@code ServerLevel.sendParticles} 每次调用
     * 会构造一个 {@code ClientboundLevelParticlesPacket} 并按距离发给范围内的玩家）。
     * 所以优化思路是：<b>能合并成一次发包的，就绝不分开发</b>。
     * <ul>
     *   <li><b>水面</b>（{@link #TIDE_INTERVAL}）：一次发包带几十个粒子（用 {@code count} 参数），
     *       铺出"记忆漫过地面"的整片效果 —— 1 个包。</li>
     *   <li><b>边界环</b>（{@link #RING_INTERVAL}）：**唯一**必须逐点发包的一层
     *       （{@code count} 只能给出实心圆盘，画不出环）。所以它间隔最长、点数最少
     *       （{@link #RING_POINTS} 个点），把成本压到 ~1.2 包/tick。</li>
     *   <li><b>漂浮碎片</b>（{@link #MOTE_INTERVAL}）：一次发包带十几个粒子 —— 1 个包。</li>
     * </ul>
     * 合计约 <b>1.8 包/tick</b>。30 秒的领域总共约 1100 个包 ——
     * 对一个传说级控场大招是可以接受的量级。
     *
     * <p>⭐ 环是**玩法信息**（领域边界 = 会不会被结算），所以它必须存在；
     * 但正因为如此才更要省着发 —— 每 10 tick 一次、粒子存活期内视觉上是连续的。
     */
    private static final int TIDE_INTERVAL = 4;

    /** 边界环的发包间隔（tick）。 */
    private static final int RING_INTERVAL = 10;

    /** 边界环的采样点数。12 边形在 10~14 格半径下已经看不出棱角。 */
    private static final int RING_POINTS = 12;

    /** 漂浮碎片的发包间隔（tick）。 */
    private static final int MOTE_INTERVAL = 3;

    /**
     * 每 tick 推进一步特效。
     *
     * <p><b>强度随领域"老化"上升</b>：{@code progress} 从 0 涨到 1，
     * 粒子数量与环的亮度随之增加 —— 读起来就是"记忆在领域里越积越厚"，
     * 而且**零额外成本**（只是同一个发包里 count 大一点）。
     */
    private static void spawnAmbience(final Field field, final long now) {
        final ServerLevel level = field.level;
        final double r = field.radius;
        final double cx = field.center.x;
        final double cy = field.center.y;
        final double cz = field.center.z;
        final double progress = Math.min(1.0D, Math.max(0.0D,
                1.0D - (double) (field.expireTick - now) / Math.max(1.0D, field.totalTicks())));
        final double intensity = 0.55D + 0.45D * progress;

        // ① 水面 —— 贴地的一整片记忆符文（一次发包）
        if (now >= field.nextTideTick) {
            field.nextTideTick = now + TIDE_INTERVAL;
            final int count = (int) Math.min(64, (8 + r * 2.4D) * intensity);
            level.sendParticles(ParticleTypes.ENCHANT,
                    cx, cy + 0.15D, cz, count, r * 0.9D, 0.05D, r * 0.9D, 0.0D);
        }

        // ② 边界环 —— 唯一逐点发包的一层（缓慢旋转，读得出"领域在呼吸"）
        if (now >= field.nextRingTick) {
            field.nextRingTick = now + RING_INTERVAL;
            final double spin = (now % 80) * 0.015D;
            for (int i = 0; i < RING_POINTS; i++) {
                final double angle = Math.PI * 2.0D / RING_POINTS * i + spin;
                level.sendParticles(ParticleTypes.END_ROD,
                        cx + Math.cos(angle) * r, cy + 0.35D, cz + Math.sin(angle) * r,
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }

        // ③ 漂浮碎片 —— 上方的"原始记忆"在缓缓下沉/上浮（一次发包）
        if (now >= field.nextMoteTick) {
            field.nextMoteTick = now + MOTE_INTERVAL;
            final int count = (int) Math.min(28, (6 + r * 0.8D) * intensity);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    cx, cy + 1.5D, cz, count, r * 0.75D, 1.2D, r * 0.75D, 0.01D);
        }
    }

    /** 叠层那一拍（每秒一次）的可见反馈：中心向上的一束，让"又叠了一层"看得见。 */
    private static void spawnPulse(final Field field) {
        field.level.sendParticles(ParticleTypes.SCULK_CHARGE_POP,
                field.center.x, field.center.y + 0.6D, field.center.z,
                18, field.radius * 0.45D, 0.4D, field.radius * 0.45D, 0.02D);
    }

    // ==================================================================
    // 领域推进
    // ==================================================================

    @SubscribeEvent
    public static void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || FIELDS.isEmpty()) {
            return;
        }
        final long now = event.getServer().overworld().getGameTime();
        for (final Iterator<Field> it = FIELDS.iterator(); it.hasNext(); ) {
            final Field field = it.next();
            // 施法者没了（登出 / 死亡 / 被移除）→ 领域直接消散，不结算。
            // 刻意不结算：否则"放完忆海立刻自杀"会变成一种无成本的引爆手段。
            if (field.caster.isRemoved() || !field.caster.isAlive()) {
                it.remove();
                continue;
            }
            if (now >= field.expireTick) {
                close(field);
                it.remove();
                continue;
            }
            // 特效每 tick 推进（内部各自节流），与"叠层"是两条独立的时间线
            spawnAmbience(field, now);

            if (now >= field.nextStackTick) {
                field.nextStackTick = now + STACK_INTERVAL_TICKS;
                pulse(field);
                spawnPulse(field);
            }
        }
    }

    /** 每秒一次：给领域内的敌人各叠 1 层认知过载。 */
    private static void pulse(final Field field) {
        for (final LivingEntity target : enemiesIn(field)) {
            MemoryArrowSpell.stackCognitiveOverloadInSea(target, field.capLayers);
        }
    }

    /**
     * 领域结束：对仍在领域内的敌人各释放一次「认知崩坏」。
     *
     * <p>复用 {@code CognitiveCollapseSpell.settle} 而不是自己写一份结算 ——
     * 否则"每层伤害表 / 眩晕时长 / 后续加成"这套规则会在两处慢慢漂移。
     * 层数为 0 的目标会被 {@code settle} 自己跳过（没有可引爆的东西）。
     */
    private static void close(final Field field) {
        for (final LivingEntity target : enemiesIn(field)) {
            CognitiveCollapseSpell.settle(field.caster, target, field.spellLevel,
                    CognitiveCollapseSpell.currentLayers(target));
        }
        // ⭐ 收束特效：水面「塌下去」的一瞬间 —— 与结算同时发生，让玩家知道
        //    "就是现在结算了"，而不是只听到一声音效。
        field.level.sendParticles(ParticleTypes.SCULK_SOUL,
                field.center.x, field.center.y + 0.4D, field.center.z,
                60, field.radius * 0.8D, 0.3D, field.radius * 0.8D, 0.06D);
        field.level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                field.center.x, field.center.y + 1.0D, field.center.z,
                40, field.radius * 0.6D, 0.8D, field.radius * 0.6D, 0.05D);
        field.level.playSound(null, field.center.x, field.center.y, field.center.z,
                ModSounds.SPELL_SEA_OF_MEMORY_END.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    private static List<LivingEntity> enemiesIn(final Field field) {
        return RaycastHelper.findLivingInSphere(field.level, field.center, field.radius,
                field.caster, true);
    }

    /**
     * 目标是否站在任意一片「忆海」领域里。
     *
     * <p>唯一调用点是 {@code EncodeSpell.getEffectiveCastTime}（"领域内写入不消耗施法时间"）。
     *
     * <p>⚠️ 只能在**服务端**调用。实测 {@code attemptInitiateCast} 在
     * {@code level.isClientSide} 时直接 {@code return false}，所以本方法不会被客户端问到；
     * 但客户端上 {@link #FIELDS} 恒为空，真被问到也只会返回 {@code false}（不会崩）。
     */
    public static boolean isInsideField(final LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        for (final Field field : FIELDS) {
            if (field.level == entity.level()
                    && entity.position().distanceToSqr(field.center) <= field.radius * field.radius) {
                return true;
            }
        }
        return false;
    }

    /** 当前生效的领域数量。给调试/校验脚本用。 */
    public static int activeFieldCount() {
        return FIELDS.size();
    }

    private static int clampLevelIndex(final int spellLevel) {
        return Math.max(1, Math.min(RADIUS.length, spellLevel)) - 1;
    }
}
