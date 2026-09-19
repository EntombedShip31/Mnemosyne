package com.etbs31.mnemosyne.oblivion;

import com.etbs31.mnemosyne.Config;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 忆格状态的**原版药水效果**承载者 —— 共鸣 / 负担 / 临时忆格。
 *
 * <p><b>为什么要有这个类（2026-09-18 架构调整）</b>
 * <br>在此之前，忆格的"加成与惩罚"是自己拿 NBT 记的
 * （{@code MnemosyneData} 里的 {@code mnemosyne_engram_buff}：resonance / castScale / manaScale / until），
 * 然后由 {@code EngramResonance.syncCastSpeedPenalty()} 在 {@code PlayerTickEvent} 里
 * **每 8 tick 手工**往属性表里 {@code addTransientModifier}。
 *
 * <p>那是在手工重造 {@link MobEffect#addAttributeModifiers} 已经做好的事。换成原版效果之后：
 * <ul>
 *   <li><b>到期自动移除修饰符</b> —— 手工路径最大的风险是"漏了一次移除 = 永久孤儿修饰符"，
 *       这类 bug 极难发现（属性悄悄不对，日志里什么都没有）；</li>
 *   <li><b>属性值自动同步客户端</b> —— 忆格数据存在玩家 NBT 里、根本不下发，
 *       客户端看到的属性可能是旧的；</li>
 *   <li><b>白送一个效果图标</b> —— 忆格 HUD 已于 2026-09-17 删除，
 *       这是目前唯一能让玩家"看见自己背了几格"的渠道；</li>
 *   <li><b>不再需要每 tick 干活</b> —— 原版只在效果增删 / amplifier 变化时重算。</li>
 * </ul>
 *
 * <p><b>⭐⭐ 最关键的一条：效果是"派生缓存"，NBT 才是权威数据源</b>
 * <br><b>喝牛奶会清空全部状态效果</b>（ISS 的净化、其他模组的驱散同理）。
 * 如果让效果当权威数据源，喝一口奶就会出现"我有 3 段记忆、但共鸣加成是 0"的状态撕裂。
 * <br>所以本类承载的三个效果**全部由 {@code MnemosyneData.refreshEngramEffects(player)}
 * 从 NBT 重算并刷新**。奶/净化只清掉缓存，下一次刷新（默认每秒一次）就自己长回来，无害。
 * <br>⇒ 任何时刻效果都必须能从 NBT 重新推导出来。**不要**在别处直接改效果来"表达"状态。
 *
 * <p><b>三个效果的分工</b>
 * <table border="1">
 *   <tr><th>Mode</th><th>amplifier</th><th>属性修饰符</th></tr>
 *   <tr><td>{@link Mode#BURDEN}</td><td>计入惩罚的格数 − 1</td>
 *       <td>ISS 的 {@code CAST_TIME_REDUCTION}（施法速度惩罚）</td></tr>
 *   <tr><td>{@link Mode#TEMPORAL}</td><td>临时格数 − 1</td>
 *       <td><b>无</b>（它只是"我还有几格临时"的计数器 + 倒计时）</td></tr>
 * </table>
 *
 */
public class EngramEffect extends MobEffect {

    /** 效果自身的持续时间（tick）。会被定期刷新，所以给一个"足够长但不是永久"的值。 */
    public static final int REFRESH_DURATION_TICKS = 60;

    /** 负担效果给施法速度加的修饰符 UUID。固定值 → 同 UUID 会替换而非叠加。 */
    private static final UUID BURDEN_CAST_ID =
            UUID.nameUUIDFromBytes("mnemosyne:engram_burden_cast".getBytes(StandardCharsets.UTF_8));

    /** 负担效果的显示色（灰紫，读作"debuff"）。 */
    public static final int COLOR_BURDEN = 0x5C5480;

    /** 临时忆格效果的显示色（品红，与"临时/不稳定"的语义一致）。 */
    public static final int COLOR_TEMPORAL = 0xD4537E;

    public enum Mode {
        /** 负担：amplifier = 计入惩罚的格数 − 1。给施法速度加惩罚修饰符。 */
        BURDEN(false),
        /** 临时忆格：amplifier = 临时格数 − 1。倒计时 + 计数，不加属性。 */
        TEMPORAL(true);

        private final boolean beneficial;

        Mode(final boolean beneficial) {
            this.beneficial = beneficial;
        }

        public boolean beneficial() {
            return beneficial;
        }
    }

    private final Mode mode;

    public EngramEffect(final Mode mode, final int color) {
        super(mode.beneficial() ? MobEffectCategory.BENEFICIAL : MobEffectCategory.HARMFUL, color);
        this.mode = mode;

        // 只有负担需要属性修饰符。这里声明的是"**每格**"的基准值，
        // 真正生效的数值由 getAttributeModifierValue 覆写计算（见下）。
        if (mode == Mode.BURDEN && AttributeRegistry.CAST_TIME_REDUCTION.isPresent()) {
            addAttributeModifier(AttributeRegistry.CAST_TIME_REDUCTION.get(),
                    BURDEN_CAST_ID.toString(), 0.0D, AttributeModifier.Operation.MULTIPLY_TOTAL);
        }
    }

    public Mode mode() {
        return mode;
    }

    /**
     * 覆写修饰符数值的算法 —— <b>这是本类唯一有技术含量的地方</b>。
     *
     * <p>原版默认实现是 {@code amount × (amplifier + 1)}，即**线性**。但施法速度惩罚
     * 在 {@code EngramResonance} 里是**非线性**的：
     * <pre>
     * penalty(n) = 1 − castSpeedPenaltyPerSlot × n        （n = 计入惩罚的格数）
     * 目标属性值 = 2 − 1 / penalty(n)                     （ISS 的 CAST_TIME_REDUCTION 语义）
     * 修饰符值   = 目标属性值 − 1                          （MULTIPLY_TOTAL，基准值 1.0）
     * </pre>
     * 举例（castSpeedPenaltyPerSlot = 0.08）：n=1 → −0.087；n=2 → −0.190；n=5 → −0.667。
     * 差值并不相等，所以**线性公式表达不了**，必须覆写本方法。
     *
     * <p>⚠️ 如果将来把 {@code EngramResonance} 的公式改了，**这里必须同步改** ——
     * 两处不一致的表现是"属性面板显示的惩罚和实际手感对不上"，很难查。
     * 这也是为什么 {@code EngramResonance} 里保留了同名纯函数供对照。
     */
    @Override
    public double getAttributeModifierValue(final int amplifier, final AttributeModifier modifier) {
        if (mode != Mode.BURDEN) {
            return 0.0D;
        }
        final int slots = amplifier + 1;
        final double penalty = Math.max(0.2D,
                1.0D - Config.Engram.CAST_SPEED_PENALTY_PER_SLOT.get() * slots);
        return 1.0D - 1.0D / penalty;
    }

    /** 三个效果都是纯状态标记，不需要每 tick 的 {@code applyEffectTick}。 */
    @Override
    public boolean isDurationEffectTick(final int duration, final int amplifier) {
        return false;
    }
}
