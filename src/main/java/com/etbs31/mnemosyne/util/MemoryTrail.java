package com.etbs31.mnemosyne.util;

import com.etbs31.mnemosyne.MnemosyneMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * 残秽（残留咒力）—— 世界事件的环形缓冲。
 *
 * <p><b>原型</b>：《咒术回战》的「残秽」：术式一旦行使，术师的咒力必如足迹般留在现场；
 * 追踪残秽可以还原"是谁用了术式""源头咒物在哪"。这是整套设定里最契合"记忆残留"的一条 ——
 * <b>残留的痕迹 = 可回溯的事件记录</b>。
 *
 * <p>本类是「残迹回溯」法术的数据源，也是一条<b>基础设施</b>：
 * 「走马灯」「既视感」将来都可以复用同一份缓冲，而不必各自再监听一遍事件。
 *
 * <p><b>⭐⭐ 为什么是内存环形缓冲，而不是写进存档</b>
 * <ol>
 *   <li><b>量级不对</b>：一条记录 8 个 double + 2 个 UUID，每秒可能产生几十条。
 *       写进玩家 NBT 会让存档无谓地膨胀。</li>
 *   <li><b>语义不对</b>：残秽是"现场残留的痕迹"，它属于<b>世界</b>不属于玩家。
 *       玩家走远了、下线了，痕迹照样消散。</li>
 *   <li><b>风险不对</b>：写进存档就要考虑跨版本兼容与损坏恢复，
 *       而它的价值只有"最近 60 秒" —— 重启丢失完全可接受。</li>
 * </ol>
 *
 * <p><b>⭐ 性能</b>：容量钳 {@link #CAPACITY} 条，**超出即丢弃最旧的**。
 * 写入是 O(1)（{@code ArrayDeque.addLast} + 满了就 {@code pollFirst}），
 * 查询是 O(n) 线性扫 512 条 —— 只在施法时发生一次，可以忽略。
 *
 * <p>⚠️ 只记录<b>服务端</b>事件。所有监听都先判 {@code isClientSide}。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MemoryTrail {

    private MemoryTrail() {}

    /** 伤害事件。 */
    public static final int KIND_DAMAGE = 0;
    /** 施法事件。 */
    public static final int KIND_CAST = 1;
    /** 死亡事件。 */
    public static final int KIND_DEATH = 2;

    /**
     * 缓冲容量。
     *
     * <p>512 条 × 约 60 秒。混战里可能更早写满（那就只回溯到更近的一段时间），
     * 单人探索时可能 60 秒都填不满 —— 两种情况下行为<b>都正确</b>：
     * 回溯窗口本来就是"最近 N 秒内**能查到的**事件"。
     */
    private static final int CAPACITY = 512;

    /** 一条残迹。 */
    public record Trail(
            long tick,
            double sx, double sy, double sz,
            double tx, double ty, double tz,
            int kind,
            float amount,
            UUID source,
            UUID target) {

        /** 事件发生的落点（画残影用）。 */
        public Vec3 at() {
            return new Vec3(tx, ty, tz);
        }

        /** 事件的起点（伤害 = 攻击者所在，画轨迹用）。 */
        public Vec3 from() {
            return new Vec3(sx, sy, sz);
        }
    }

    private static final Deque<Trail> TRAILS = new ArrayDeque<>(CAPACITY);

    private static void push(final Trail trail) {
        if (TRAILS.size() >= CAPACITY) {
            TRAILS.pollFirst();
        }
        TRAILS.addLast(trail);
    }

    /** 当前时间（主世界 gameTime，与项目其它计时口径一致）。 */
    private static long now(final ServerLevel level) {
        return level.getServer().overworld().getGameTime();
    }

    // ==================================================================
    // 三类事件的采集
    // ==================================================================

    @SubscribeEvent
    public static void onLivingHurt(final LivingHurtEvent event) {
        final LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || !(victim.level() instanceof ServerLevel level)) {
            return;
        }
        // 虚空 / 指令杀这类"没有现场"的伤害不留下痕迹
        if (event.getSource().is(DamageTypes.GENERIC_KILL)
                || event.getSource().is(DamageTypes.FELL_OUT_OF_WORLD)) {
            return;
        }
        final Vec3 from = event.getSource().getEntity() != null
                ? event.getSource().getEntity().position()
                : victim.position();
        push(new Trail(now(level), from.x, from.y + 1.0D, from.z,
                victim.getX(), victim.getY() + 0.5D, victim.getZ(),
                KIND_DAMAGE, event.getAmount(),
                event.getSource().getEntity() == null ? null : event.getSource().getEntity().getUUID(),
                victim.getUUID()));
    }

    @SubscribeEvent
    public static void onSpellCast(final SpellOnCastEvent event) {
        final Player player = event.getEntity();
        if (player.level().isClientSide || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        final Vec3 at = player.position();
        push(new Trail(now(level), at.x, at.y + 1.0D, at.z,
                at.x, at.y + 1.0D, at.z, KIND_CAST, event.getManaCost(),
                player.getUUID(), player.getUUID()));
    }

    @SubscribeEvent
    public static void onLivingDeath(final LivingDeathEvent event) {
        final LivingEntity dead = event.getEntity();
        if (dead.level().isClientSide || !(dead.level() instanceof ServerLevel level)) {
            return;
        }
        final Vec3 at = dead.position();
        push(new Trail(now(level), at.x, at.y, at.z,
                at.x, at.y + 0.5D, at.z, KIND_DEATH, 0.0F,
                event.getSource().getEntity() == null ? null : event.getSource().getEntity().getUUID(),
                dead.getUUID()));
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 查询一片区域、一段时间内的残迹。
     *
     * @param center    查询圆心
     * @param radius    查询半径
     * @param sinceTick 起始刻（含）；用它表达"回溯多少秒"
     * @param limit     最多返回多少条（保护渲染开销）
     */
    public static List<Trail> query(final Vec3 center, final double radius,
                                    final long sinceTick, final int limit) {
        final List<Trail> found = new ArrayList<>();
        final double rSqr = radius * radius;
        // 从**最新**往回扫：限条数时保留的是最近的事件，这才符合"回溯"的直觉。
        for (final java.util.Iterator<Trail> it = TRAILS.descendingIterator(); it.hasNext(); ) {
            final Trail trail = it.next();
            if (trail.tick() < sinceTick) {
                break;
            }
            if (trail.at().distanceToSqr(center) <= rSqr) {
                found.add(trail);
                if (found.size() >= limit) {
                    break;
                }
            }
        }
        return found;
    }

    /** 当前缓冲里的条数。给调试 / 校验脚本用。 */
    public static int size() {
        return TRAILS.size();
    }
}
