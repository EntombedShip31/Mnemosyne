package com.etbs31.mnemosyne.spell.base;

import com.etbs31.mnemosyne.MnemosyneMod;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 延迟分段伤害 —— 「千忆归一」元素段的真正落点。
 *
 * <p><b>⭐⭐ 2026-09-18 新增，修的是一个"写在注释里的谎"。</b>
 * 旧实现把元素段的 25% 直接并进普通段一次性结算，然后只附加一个**纯表现**的
 * 元素状态（火焰 / 凋零 / 中毒 / 迟缓）。代码注释自己也承认了这个取舍
 * （"抽到迟缓时这一发就会静默少掉 25% 伤害"），于是它把伤害并进普通段来回避。
 * <br>结果就是：配置项 {@code thousandElementalDoTPct} 名义上是"元素持续伤害占比"，
 * <b>实际没有任何持续伤害发生</b> —— 玩家调这个数只会看到总伤害变化，
 * 看不到任何 DoT。这是个"名字与行为不符"的设计债。
 *
 * <p><b>本类做的事</b>：把元素段那部分伤害**真的按时间拆开**打出去。
 * 每 {@code intervalTicks} 结算一次，共 {@code hops} 跳。
 * 于是"元素"这个词终于名副其实，而且：
 * <ul>
 *   <li>抽到<b>迟缓</b>也不再少伤害 —— 伤害由本类结算，元素状态只是附加表现；</li>
 *   <li>DoT 走**学派伤害源**（与普通段同一条管线），所以
 *       {@code memory_magic_resist} / {@code SpellDamageEvent} 照常生效；</li>
 *   <li>击杀归属施法者（伤害源的 {@code directEntity} 传的是施法者）。</li>
 * </ul>
 *
 * <p><b>性能</b>：静态表 + 一次 {@code ServerTickEvent} 遍历。
 * 表里最多同时存在"几次千忆归一"的条目（法术有 90 秒冷却，实际通常 0~2 条），
 * 遍历开销可以忽略。与服务端主线程同线程，不需要并发容器
 * （与 {@code SeaOfMemorySpell.FIELDS} / {@code CognitiveCollapseSpell.STUNNED} 同一取舍）。
 *
 * <p><b>⚠️ 生命周期</b>：目标死亡 / 被移除 / 换了维度 → 条目立即丢弃。
 * 不写存档 —— 服务端重启会丢失未结算的 DoT 尾巴。这是可接受的
 * （总量只有 2 秒，且丢失方向对玩家不利的程度极低）。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ElementalDoT {

    private ElementalDoT() {}

    /** 同时生效的分段伤害条目。只在服务端主线程读写。 */
    private static final List<Instance> ACTIVE = new ArrayList<>();

    /** 一条待结算的分段伤害。 */
    private static final class Instance {
        private final LivingEntity target;
        private final Entity caster;
        private final AbstractSpell spell;
        private final float perHop;
        /** 每跳间隔（tick）。⚠️ 必须存下来 —— 用 "nextHopTick - now" 反推是错的（此刻它 ≤ 0）。 */
        private final int interval;
        private int hopsLeft;
        private long nextHopTick;

        private Instance(final LivingEntity target, final Entity caster, final AbstractSpell spell,
                         final float perHop, final int hopsLeft, final int interval,
                         final long nextHopTick) {
            this.target = target;
            this.caster = caster;
            this.spell = spell;
            this.perHop = perHop;
            this.hopsLeft = hopsLeft;
            this.interval = interval;
            this.nextHopTick = nextHopTick;
        }
    }

    /**
     * 安排一段分段伤害。
     *
     * @param total          这一段的总伤害
     * @param durationTicks  总时长
     * @param hops           跳数（≥ 1）
     */
    public static void schedule(final LivingEntity target, final Entity caster,
                                final AbstractSpell spell, final float total,
                                final int durationTicks, final int hops) {
        if (target == null || caster == null || spell == null || total <= 0.0F || hops <= 0) {
            return;
        }
        final int n = Math.max(1, hops);
        final long now = nowTick(caster);
        final int interval = Math.max(1, durationTicks / n);
        ACTIVE.add(new Instance(target, caster, spell, total / n, n, interval, now + interval));
    }

    /** 当前待结算的条目数（给调试 / 校验脚本用）。 */
    public static int activeCount() {
        return ACTIVE.size();
    }

    @SubscribeEvent
    public static void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) {
            return;
        }
        for (final Iterator<Instance> it = ACTIVE.iterator(); it.hasNext(); ) {
            final Instance inst = it.next();

            // 目标没了 / 换了维度 → 直接丢弃尾巴。不"补一发"到新维度去。
            if (inst.target.isRemoved() || !inst.target.isAlive()
                    || inst.target.level() != inst.caster.level()) {
                it.remove();
                continue;
            }
            final long now = nowTick(inst.caster);
            if (now < inst.nextHopTick) {
                continue;
            }
            // 跳数用完了 → 移除（先判再打，避免最后一跳打完后还留一格在表里）
            if (inst.hopsLeft <= 0) {
                it.remove();
                continue;
            }

            // ⚠️ 必须走 MnemosyneSpell.dealSpellDamage —— 它是 ISS 伤害管线的入口
            //    （抗性乘算 / SpellDamageEvent / 击杀归属都在里面）。
            //    本类与 MnemosyneSpell 同包，所以能直接调它的 protected static 方法。
            MnemosyneSpell.dealSpellDamage(inst.target, inst.caster, inst.caster,
                    inst.spell, inst.perHop);

            inst.hopsLeft--;
            // ⚠️ 用存的 interval 推进，而不是"now + 剩余" —— 后者会因为 tick 抖动越走越歪
            inst.nextHopTick = now + inst.interval;
        }
    }

    /** 与忆格系统同一把尺子：主世界 gameTime 绝对值（登出即冻结）。 */
    private static long nowTick(final Entity entity) {
        if (entity.level() instanceof net.minecraft.server.level.ServerLevel serverLevel
                && serverLevel.getServer() != null) {
            return serverLevel.getServer().overworld().getGameTime();
        }
        return entity.level().getGameTime();
    }
}
