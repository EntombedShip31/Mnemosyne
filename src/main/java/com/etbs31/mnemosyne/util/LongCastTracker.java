package com.etbs31.mnemosyne.util;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.spell.base.MnemosyneLongCastSpell;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长吟法术的自计时器。
 *
 * <p><b>文件归属</b>：WS-C 法术基类。
 *
 * <p><b>为什么自己计时，不去读 {@code MagicData}</b>（{@code docs/tech/03} §3.4 的实测结论）：
 * ISS 的吟唱计时字段**不在 api 包内**，反射读它属于"版本脆弱"的做法。
 * 用 {@code player.tickCount} 的差值算进度：零反射、完全可控。
 *
 * <p><b>契约（冻结，{@code docs/tech/11} §七）</b>：
 * <pre>{@code
 * LongCastTracker.begin(ServerPlayer)      // 开始计时
 * LongCastTracker.elapsed(ServerPlayer)    // 已吟唱 tick 数；未在吟唱中返回 -1
 * LongCastTracker.end(ServerPlayer)        // 结束/清理
 * }</pre>
 *
 * <p><b>⭐ 为什么需要"超时兜底"</b>：吟唱被打断时 ISS **不再调用** {@code onServerCastTick}，
 * 所以不能依赖"最后一 tick"来清理状态。本类自己订阅 {@code PlayerTickEvent}，
 * 发现某个玩家的会话超过「预期吟唱时间 + 宽限」仍未结束时，判定为被打断，
 * 调子类覆写的 {@code onLongCastInterrupted(...)} 并清理。
 *
 * <p>会话表用 {@code ConcurrentHashMap} 是**刻意**的：玩家退出/切维度时
 * 事件可能落在不同线程，用普通 HashMap 会偶发 {@code ConcurrentModificationException}。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class LongCastTracker {

    private LongCastTracker() {}

    /** {@link #elapsed(ServerPlayer)} 的"未在吟唱中"返回值。 */
    public static final int NOT_CASTING = -1;

    /**
     * 判定被打断的宽限 tick 数。
     * 留 20 tick（1 秒）是因为服务端 tick 抖动 + 客户端延迟会让实际吟唱略长于名义时间，
     * 余量太小会误判"正常完成"为"被打断"。
     */
    private static final int INTERRUPT_GRACE_TICKS = 20;

    /** 一次长吟会话。{@code spell} 可以为 null（表示只关心计时，不关心打断回调）。 */
    private record Session(int startTick, int totalTicks, @Nullable MnemosyneLongCastSpell spell) {}

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // 契约方法
    // ------------------------------------------------------------------

    /**
     * 不参与超时兜底的哨兵值。
     *
     * <p>⚠️ <b>2026-09-18 修正了一个潜伏的整数溢出 bug</b>：这里原来写的是
     * {@code Integer.MAX_VALUE}，而超时判断是
     * {@code elapsed > session.totalTicks() + INTERRUPT_GRACE_TICKS}。
     * {@code Integer.MAX_VALUE + 20} **会溢出成负数**，于是
     * {@code elapsed > 负数} 恒为真 —— 任何用 {@link #begin(ServerPlayer)} 开的会话
     * 都会在**下一个 tick 就被误判为"被打断"**。
     *
     * <p>当时没有暴露，是因为三参版 {@link #begin(ServerPlayer, int, MnemosyneLongCastSpell)}
     * 是唯一被真正调用的入口（实测全项目只有 {@code MnemosyneLongCastSpell} 调它）。
     * 但一参版写在「冻结契约」的注释里，别的实现照着用就会踩中 ——
     * 所以用哨兵值 + 显式判空修掉，而不是留着当雷。
     */
    private static final int NO_TIMEOUT = -1;

    /** 开始计时（契约版：不记录预期时长，因此不参与超时兜底）。 */
    public static void begin(final ServerPlayer player) {
        SESSIONS.put(player.getUUID(), new Session(player.tickCount, NO_TIMEOUT, null));
    }

    /**
     * 开始计时（基类用的增强版）。
     *
     * @param totalTicks 本次吟唱的名义总时长（tick），用于超时兜底
     * @param spell      法术实例（注册表里的单例，持有它不会造成内存泄漏），用于打断回调
     */
    public static void begin(final ServerPlayer player, final int totalTicks, final MnemosyneLongCastSpell spell) {
        SESSIONS.put(player.getUUID(), new Session(player.tickCount, totalTicks, spell));
    }

    /** @return 已吟唱的 tick 数；未在吟唱中返回 {@link #NOT_CASTING}（-1）。 */
    public static int elapsed(final ServerPlayer player) {
        final Session session = SESSIONS.get(player.getUUID());
        return session == null ? NOT_CASTING : player.tickCount - session.startTick();
    }

    /** 结束并清理。吟唱正常完成、被打断、玩家登出时都必须调用。 */
    public static void end(final ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
    }

    // ------------------------------------------------------------------
    // 便捷方法
    // ------------------------------------------------------------------

    public static boolean isCasting(final ServerPlayer player) {
        return SESSIONS.containsKey(player.getUUID());    }

    /**
     * 吟唱进度。
     *
     * @return 0.0 ~ 1.0；未在吟唱中或 {@code totalTicks <= 0} 时返回 0.0
     */
    public static float progress(final ServerPlayer player, final int totalTicks) {
        if (totalTicks <= 0) {
            return 0.0F;
        }
        final int elapsed = elapsed(player);
        if (elapsed < 0) {
            return 0.0F;
        }
        return Math.min(1.0F, (float) elapsed / (float) totalTicks);
    }

    // ------------------------------------------------------------------
    // 超时兜底（打断处理）
    // ------------------------------------------------------------------

    /**
     * 每 tick 扫描一次会话表。
     *
     * <p>扫描成本是 O(正在吟唱的玩家数)，通常 0~2，可以忽略。
     * 刻意**不做**"每 20 tick 才扫一次"的优化 —— 打断清理晚 1 秒会让玩家看到
     * 一个卡住的 HUD 进度条，得不偿失。
     */
    @SubscribeEvent
    public static void onPlayerTick(final TickEvent.PlayerTickEvent event) {
        // 只在服务端、且每个 tick 只处理一次（PlayerTickEvent 有 START/END 两相）
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        final Session session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return;
        }

        final int elapsed = player.tickCount - session.startTick();

        // 正常完成：由 onCast 负责调 end()，这里不动。
        // 只处理"超时仍未结束"的情况 —— 说明 ISS 已经放弃了这个吟唱（被打断）。
        // ⚠️ NO_TIMEOUT 表示"本次会话不参与超时兜底"，必须显式判掉，
        //    否则 -1 + 20 = 19 会让 elapsed（很小）恒大于它，会话被立刻误杀。
        if (session.totalTicks() != NO_TIMEOUT
                && elapsed > session.totalTicks() + INTERRUPT_GRACE_TICKS) {
            final MnemosyneLongCastSpell spell = session.spell();
            if (spell != null) {
                // handleInterrupt 内部会 end() + 通知子类，两步绑在一起不会漏
                spell.handleInterrupt(player);
            } else {
                end(player);
            }
            MnemosyneMod.LOGGER.debug("长吟超时兜底触发：{} 的 {} 判定为被打断",
                    player.getName().getString(), spell == null ? "?" : spell.getSpellId());
        }
    }

    /**
     * 玩家登出时清理会话。
     *
     * <p>⭐ <b>2026-09-18 补上的内存泄露修复。</b>
     * {@link #SESSIONS} 是**静态**的、按玩家 UUID 存。
     * 在这之前唯一的清理路径是 {@link #end}（由吟唱完成/打断触发）
     * 和 {@link #onPlayerTick} 里的超时兜底 ——
     * <b>而这两条都要求玩家还在 tick</b>。
     * <br>玩家在吟唱中途登出/掉线时，两者都不会跑，那条会话就**永久留在静态 Map 里**
     * （UUID → Session，还持有法术单例引用）。小服务器无所谓，
     * 但长跑服务器上会随"登出过的玩家数"单调增长。
     *
     * <p>注意：这不是"泄漏的严重程度很高"，而是**它永远不会自愈** ——
     * 所以哪怕每次只漏几十字节，也应该在登出这个明确的边界上清掉。
     * 其他按 UUID 存的静态集合（{@code DejaVuSpell.HISTORY} /
     * {@code EncodePainSpell.RECORDINGS} / {@code EngramRelease.SCALES}）
     * 都已经有同样的登出清理，本类是漏掉的那个。
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(final PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            end(player);
        }
    }
}
