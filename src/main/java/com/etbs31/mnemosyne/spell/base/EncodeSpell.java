package com.etbs31.mnemosyne.spell.base;

import com.etbs31.mnemosyne.capability.MnemosyneData;
import com.etbs31.mnemosyne.util.RaycastHelper;
import com.etbs31.mnemosyne.util.SpellFeedback;
// ⚠️ 基类 import 具体子类（spell.high）是一次刻意的方向让步，理由见
//    getEffectiveCastTime 的注释：「忆海」领域内免吟唱这条规则必须由三个写入法术
//    共享，否则三份拷贝迟早漂移。替代方案是新建一个中立的"领域登记处"文件，
//    但为一个布尔判定多开一个文件不划算（EngramResonance / EngramRelease 那种
//    级别的公共设施才值得单独成文件）。先例：OblivionSpell 也 import 了 oblivion 包。
import com.etbs31.mnemosyne.spell.high.SeaOfMemorySpell;
import io.redspace.ironsspellbooks.api.config.DefaultConfig;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 写入类法术的基类 —— 把"记忆"写进忆格。
 *
 * <p><b>文件归属</b>：WS-C 法术基类。
 *
 * <p><b>适用</b>：术忆（{@code encode_spell}）、质忆（{@code encode_trait}）、
 * 痛忆（{@code encode_pain}）。子类只实现 {@link #onEncode}。
 *
 * <p><b>契约（冻结，{@code docs/tech/11} §七）</b>：
 * <pre>{@code
 * EncodeSpell(DefaultConfig)
 * protected abstract void onEncode(ServerPlayer caster, LivingEntity target, int spellLevel)
 * }</pre>
 *
 * <p><b>共享逻辑</b>（{@code docs/tech/03} §3.1）：
 * <ol>
 *   <li>检查忆格是否有空位 → 没有则中断</li>
 *   <li>取目标（射线检测，排除自己与友军）</li>
 *   <li>构造对应的记忆条目并写入 + 设置腐坏时间</li>
 *   <li>同步忆格数据到客户端</li>
 * </ol>
 * 其中 ①③④ 依赖 WS-B 的忆格数据层（{@code capability/MnemosyneData}），
 * 本类通过 {@link #hasFreeEngramSlot} / {@link #syncEngrams} 两个可覆写钩子对接，
 * **不直接依赖 WS-B 的类** —— 这样 WS-B 与 WS-C 可以并行开发而不互相卡住。
 */
public abstract class EncodeSpell extends MnemosyneSpell {

    /** 写入类法术的默认射线射程（{@code docs/tech/03} §五：质忆 8 格）。 */
    protected static final float DEFAULT_ENCODE_RANGE = 8.0F;

    protected EncodeSpell(final DefaultConfig defaultConfig) {
        super(defaultConfig);
    }

    // ==================================================================
    // 子类实现
    // ==================================================================

    /**
     * 构造并写入记忆条目。
     *
     * @param target 射线命中的目标；**可能为 {@code null}**（痛忆这类"记录自己"的法术会忽略它）
     */
    protected abstract void onEncode(ServerPlayer caster, @Nullable LivingEntity target, int spellLevel);

    // ==================================================================
    // 可覆写钩子（对接 WS-B 忆格数据层）
    // ==================================================================

    /**
     * 是否有空忆格。
     *
     * <p>⚠️ 原实现是 {@code return true;}（WS-B 时代的占位），并在注释里写了
     * {@code TODO(WS-B)}。**WS-B 早已交付**，而那个占位如果留着，
     * 任何一个忘记覆写本方法的写入类都会变成"忆格满了照样施法、记忆静默丢失"——
     * 编译通过、日志无输出。2026-09-17 串行集成时已把它接上。
     *
     * <p>三个子类（{@code EncodeSpellSpell} / {@code EncodeTraitSpell} /
     * {@code EncodePainSpell}）过去各自覆写了一份**完全相同**的实现，
     * 现在它们继承本方法即可（保留覆写也不会出错，只是冗余）。
     */
    protected boolean hasFreeEngramSlot(final ServerPlayer caster) {
        return MnemosyneData.hasFreeSlot(caster);
    }

    /**
     * 把忆格数据同步到客户端。
     *
     * <p>⚠️ 原实现是空方法（{@code TODO(WS-B)}）。留空的后果同样是静默的：
     * 记忆写进去了、HUD 却不显示，玩家会以为法术没生效。
     * 2026-09-17 串行集成时已接上。
     */
    protected void syncEngrams(final ServerPlayer caster) {
        MnemosyneData.notifyEngramChange(caster);
    }

    // ==================================================================
    // 施法时间（「忆海」领域内为 0）
    // ==================================================================

    /**
     * 「忆海」领域内，写入**不消耗施法时间**（{@code docs/tech/04} §四.17）。
     *
     * <p><b>⭐⭐ 为什么覆写 {@code getEffectiveCastTime} 是安全的</b>：
     * 实测 {@code AbstractSpell.attemptInitiateCast} 的**第一行**就是
     * {@code if (level.isClientSide) return false;} —— 所以
     * {@code getEffectiveCastTime} **只在服务端被调用**，算出来的时长再通过
     * {@code UpdateCastingStatePacket} 推给客户端。也就是说 LONG 法术的施法条
     * 用的是服务端算出来的值，**per-cast 的带施法者覆写不会造成两端不同步**。
     * （对照：{@code getCastTime(int)} 只有一个 int 参数，拿不到施法者，
     * 所以"这一次施法变快"这类效果只能走本方法。）
     *
     * <p>三个写入法术共用这一处实现 —— 否则将来调整"领域内免吟唱"的判定条件时，
     * 三份拷贝里漏改一份，那一个法术就会静默地仍然要吟唱。
     */
    @Override
    public int getEffectiveCastTime(final int spellLevel, @Nullable final LivingEntity entity) {
        if (entity != null && SeaOfMemorySpell.isInsideField(entity)) {
            return 0;
        }
        return super.getEffectiveCastTime(spellLevel, entity);
    }

    /**
     * 取射线目标。
     *
     * <p>默认实现：从视线出发、8 格、被方块阻挡、排除自己与友军。
     * 子类若需要不同参数（例如更远、或允许命中友军）可覆写。
     */
    @Nullable
    protected LivingEntity resolveTarget(final Level level, final ServerPlayer caster) {
        return RaycastHelper.findLivingTarget(level, caster, DEFAULT_ENCODE_RANGE, true, true);
    }

    // ==================================================================
    // 施法类型
    // ==================================================================

    /**
     * 写入类法术**都是长吟**（{@code docs/tech/04} §三：
     * 术忆 8 tick、质忆 16 tick、痛忆 6 tick）。
     *
     * <p>⚠️ 但**不继承** {@link MnemosyneLongCastSpell} —— 因为写入类没有
     * "吟唱期逐 tick 机制"（不需要切换目标 / 点燃忆格），只是单纯需要一段起手时间。
     * 用 {@code LONG} + {@code castTime} 就够了，硬套长吟基类反而要空实现三个抽象方法。
     *
     * <p>非 final：将来若有"瞬发写入"法术，子类覆写本方法返回 {@code INSTANT} 即可。
     */
    @Override
    public CastType getCastType() {
        return CastType.LONG;
    }

    // ==================================================================
    // 主流程
    // ==================================================================

    /**
     * {@inheritDoc}
     *
     * <p>刻意**不**在 {@link #checkPreCastConditions} 里拦截"忆格已满"：
     * 按 {@code docs/tech/03} §3.1 的设计，"忆格满"时**照常扣法力**（这是刻意的惩罚），
     * 但"没打到目标"时**不扣法力**。
     */
    @Override
    public boolean checkPreCastConditions(final Level level, final int spellLevel,
                                          final LivingEntity entity, final MagicData playerMagicData) {
        if (entity instanceof ServerPlayer player) {
            // 无目标 → 不消耗法力
            return resolveTarget(level, player) != null || allowsSelfTarget();
        }
        return super.checkPreCastConditions(level, spellLevel, entity, playerMagicData);
    }

    /**
     * 本法术是否允许"没有目标也能施放"（例如痛忆记录自己受到的伤害）。
     * 默认 {@code false}。
     */
    protected boolean allowsSelfTarget() {
        return false;
    }

    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide && entity instanceof ServerPlayer player) {
            if (!hasFreeEngramSlot(player)) {
                // 忆格已满：法力照扣（刻意惩罚），但不写入。
                // ⭐ 2026-09-17：这里原来直接 return —— 玩家看到"法术放了、法力扣了、
                //    什么都没发生"，完全不知道是因为忆格满了。必须给反馈。
                SpellFeedback.noFreeSlot(player);
                return;
            }
            // ⚠️ 原来这里调用了两次 resolveTarget()：一次判空、一次取值。
            //    除了浪费一次射线检测，两次结果还可能不同（目标在两次调用之间移动）——
            //    表现是"判空说有目标、取回来是 null"。改成只取一次。
            final LivingEntity resolved = resolveTarget(level, player);
            final LivingEntity target = resolved != null ? resolved
                    : (allowsSelfTarget() ? player : null);
            if (target == null) {
                // 走到这里说明 checkPreCastConditions 没能拦住（ISS 的施法流程不保证调用它），
                // 必须在这里挡住 —— 否则 onEncode 会拿到 null 目标，轻则静默失效、重则 NPE。
                SpellFeedback.noTarget(player);
                return;
            }
            onEncode(player, target, spellLevel);
            SpellFeedback.castBurst(level, player, SpellFeedback.MEMORY_INDIGO);
            SpellFeedback.hitBurst(level, target, SpellFeedback.MEMORY_INDIGO);
            syncEngrams(player);
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }
}
