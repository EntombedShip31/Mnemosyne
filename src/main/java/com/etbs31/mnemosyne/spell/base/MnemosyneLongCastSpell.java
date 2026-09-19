package com.etbs31.mnemosyne.spell.base;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.util.LongCastTracker;
import io.redspace.ironsspellbooks.api.config.DefaultConfig;
import io.redspace.ironsspellbooks.api.events.SpellPreCastEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 长吟法术的基类 ⭐
 *
 * <p><b>文件归属</b>：WS-C 法术基类。
 *
 * <p><b>适用</b>：记忆掠夺（{@code memory_theft}）、忆海（{@code sea_of_memory}）、
 * 千忆归一（{@code thousand_memories}）—— 三个"大招"。
 *
 * <p><b>为什么是 {@code LONG} 而不是"蓄能"</b>：
 * {@code docs/tech/04} §〇 更正 1 —— ISS 的 {@code CastType} **没有 {@code CHARGE}**，
 * 只有 {@code NONE} / {@code INSTANT} / {@code LONG} / {@code CONTINUOUS}。
 * 而且 {@code INSTANT} 会强制 {@code castTime = 0}，所以需要起手动作的只能走 {@code LONG}。
 *
 * <p><b>契约（冻结，{@code docs/tech/11} §七）</b>：
 * <pre>{@code
 * MnemosyneLongCastSpell(DefaultConfig)
 * @Override public CastType getCastType()  → LONG
 * protected abstract int defaultCastTime()
 * protected abstract void onLongCastTick(ServerPlayer, int spellLevel, float progress, MagicData)
 * protected abstract void onLongCastFinish(ServerPlayer, int spellLevel)
 * }</pre>
 *
 * <p><b>⭐ 打断处理是本类最重要的部分</b>：
 * 吟唱被打断时 ISS **不再调用** {@code onServerCastTick}，
 * 所以不能靠"最后一 tick"清理状态。三道防线：
 * <ol>
 *   <li>{@link #onSpellPreCast} —— 每次吟唱开始时记录起始 tick</li>
 *   <li>{@link #onServerCastTick} —— 吟唱中每 tick 推进进度、给子类处理机会</li>
 *   <li>{@link LongCastTracker} 的 {@code PlayerTickEvent} 超时兜底 —— 超过名义时长仍未结束
 *       就判定被打断，调 {@link #onLongCastInterrupted}</li>
 * </ol>
 *
 * <p><b>被打断的语义</b>（{@code docs/06_法术_高阶_Epic与Legendary.md}）：
 * 三个大招被打断时**都不消耗忆格**，但**冷却照触发**。
 * 子类在 {@link #onLongCastInterrupted} 里做清理（不要在里面消耗资源）。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public abstract class MnemosyneLongCastSpell extends MnemosyneSpell {

    protected MnemosyneLongCastSpell(final DefaultConfig defaultConfig) {
        super(defaultConfig);
    }

    // ==================================================================
    // 子类实现
    // ==================================================================

    /** 基准吟唱时间（tick）。子类返回 {@code docs/tech/04} 里该法术的 {@code castTime}。 */
    protected abstract int defaultCastTime();

    /**
     * 吟唱期逐 tick 逻辑。
     *
     * <p>{@code spellLevel} 是**施法时的等级**，吟唱期间不变。
     *
     * @param progress 吟唱进度 0.0 ~ 1.0
     */
    protected abstract void onLongCastTick(ServerPlayer player, int spellLevel,
                                           float progress, MagicData magicData);

    /** 吟唱**正常完成**时的结算。被打断时不会调用这里，而是 {@link #onLongCastInterrupted}。 */
    protected abstract void onLongCastFinish(ServerPlayer player, int spellLevel);

    /**
     * 吟唱被打断时的清理。
     *
     * <p>默认只记日志。子类覆写时**不要消耗忆格/资源** —— 被打断的设计就是"不消耗"。
     *
     * <p>⚠️ {@code protected}：外部（{@code LongCastTracker}）请调 {@link #handleInterrupt}。
     */
    protected void onLongCastInterrupted(final ServerPlayer player) {
        MnemosyneMod.LOGGER.debug("长吟被打断：{}", player.getName().getString());
    }

    /**
     * 打断入口 —— **只给 {@link LongCastTracker} 的超时兜底调用**。
     *
     * <p>为什么要有这一层：{@code LongCastTracker} 在 {@code util} 包，
     * 不能访问 {@code protected} 的 {@link #onLongCastInterrupted}。
     * 与其把子类钩子放宽成 {@code public}，不如只暴露这一个窄接口，
     * 顺便把"结束计时"和"通知子类"绑成一次不可拆的操作 ——
     * 分开调用很容易漏掉其中一步，然后 HUD 就卡住了。
     */
    public final void handleInterrupt(final ServerPlayer player) {
        LongCastTracker.end(player);
        onLongCastInterrupted(player);
    }

    // ==================================================================
    // 施法类型与吟唱时间
    // ==================================================================

    @Override
    public final CastType getCastType() {
        return CastType.LONG;
    }

    /**
     * 吟唱时间（tick）。
     *
     * <p>返回固定的 {@link #defaultCastTime()} —— 与 ISS 的默认行为一致
     * （{@code AbstractSpell.getCastTime} 对非 INSTANT 返回固定的 {@code castTime}）。
     * 想让某个法术的吟唱时间随等级递减，**在子类覆写本方法**，例如：
     * <pre>{@code
     * @Override
     * public int getCastTime(int spellLevel) {
     *     return defaultCastTime() - (spellLevel - 1) * 4;   // 每级快 4 tick
     * }
     * }</pre>
     */
    @Override
    public int getCastTime(final int spellLevel) {
        return defaultCastTime();
    }

    // ==================================================================
    // 三道防线
    // ==================================================================

    /**
     * 第一道防线：吟唱开始时记录起始 tick。
     *
     * <p>监听的是 ISS 的 {@code SpellPreCastEvent}（在 api 包内，可直接用）。
     * 这里用 {@code SpellRegistry.getSpell(spellId)} 反查法术实例 ——
     * 拿到的是注册表里的**单例**，所以 {@link LongCastTracker} 持有它不会泄漏内存。
     */
    @SubscribeEvent
    public static void onSpellPreCast(final SpellPreCastEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        final AbstractSpell spell = SpellRegistry.getSpell(event.getSpellId());
        if (spell instanceof MnemosyneLongCastSpell longCast) {
            LongCastTracker.begin(player, longCast.getCastTime(event.getSpellLevel()), longCast);
        }
    }

    /** 第二道防线：吟唱中每 tick 推进进度。 */
    @Override
    public void onServerCastTick(final Level level, final int spellLevel,
                                 final LivingEntity entity, final MagicData magicData) {
        if (!(entity instanceof ServerPlayer player)) {
            return;
        }
        final float progress = LongCastTracker.progress(player, getCastTime(spellLevel));
        onLongCastTick(player, spellLevel, progress, magicData);
        super.onServerCastTick(level, spellLevel, entity, magicData);
    }

    /** 吟唱正常完成：先结束计时，再让子类结算。 */
    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide && entity instanceof ServerPlayer player) {
            LongCastTracker.end(player);
            onLongCastFinish(player, spellLevel);
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }
}
