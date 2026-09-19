package com.etbs31.mnemosyne.oblivion;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * 忆海的四个状态效果本体。
 *
 * <p><b>文件归属</b>：WS-E 遗忘系统。
 *
 * <p><b>为什么四个效果共用一个类</b>：{@code docs/tech/10} §九 的反模式清单明确要求
 * "公共逻辑上提，不要复制多份"。这四个效果的差别只有**类别 + 颜色 + 一个行为开关**，
 * 复制四个类只会产生四份几乎相同的代码。用一个 {@link Mode} 驱动，
 * 新增效果时只加一个枚举值。
 *
 * <p><b>四个 id（冻结，{@code docs/tech/12} 提示词 A）</b>：
 * <table border="1">
 *   <tr><th>id</th><th>Mode</th><th>承载的数据</th></tr>
 *   <tr><td>{@code mnemosyne:forget}</td><td>FORGET</td><td>被移除行为的记录（见下）</td></tr>
 *   <tr><td>{@code mnemosyne:amnesia}</td><td>AMNESIA</td><td>同上</td></tr>
 *   <tr><td>{@code mnemosyne:cognitive_overload}</td><td>COGNITIVE_OVERLOAD</td><td>层数（用 amplifier）</td></tr>
 *   <tr><td>{@code mnemosyne:sluggish}</td><td>SLUGGISH</td><td>无（纯属性修正）</td></tr>
 * </table>
 *
 * <p><b>⭐ 实测修正：被移除行为的记录**不能**放进 MobEffectInstance</b>
 * <br>提示词 A 写的是"记录写进自定义 MobEffect 的 NBT"。实测不可行，两条硬约束：
 * <ol>
 *   <li>1.20.1 的 {@code MobEffectInstance} **没有任何自定义 NBT 字段**
 *       （字段只有 duration / amplifier / ambient / visible / showIcon / hiddenEffect /
 *       curativeItems），它也不实现 {@code save}/{@code load} 之外的扩展序列化。</li>
 *   <li>被移除的 {@code Goal} 是**对象**（内部持有 owner 引用与状态），本身不可序列化。</li>
 * </ol>
 * 实际实现（见 {@link OblivionManager}）：
 * <ul>
 *   <li>活的 {@code Goal} 实例存在**运行时表**里 → 到期可以**原样**装回去，
 *       行为内部状态不丢。</li>
 *   <li>实体 {@code getPersistentData()} 只存 {@code tier} + {@code expire} 两个标量，
 *       用于"区块卸载 / 服务器重启后重新施加"。</li>
 * </ul>
 * 这个分工反而比原方案更稳：因为**实体重载时 {@code registerGoals()} 会重建全部行为**，
 * 所以不存在"goal 被永久删掉、存档里留下残废生物"的问题。
 *
 * <p><b>对玩家无效</b>（{@code docs/tech/03} §7.5 / {@code docs/02} §三 规则一）：
 * {@link Mode#blocksPlayers()} 为真的两个效果会被 {@code MobEffectEvent.Applicable}
 * 直接拒绝施加，并在 {@link #applyEffectTick} 里做一次兜底移除。
 * 注意只有**遗忘类**两个效果受此约束 —— 认知过载与迟滞是通用减益，
 * 由 WS-D 决定施加对象，不该在这里一刀切。
 */
public class OblivionEffect extends MobEffect {

    /**
     * 效果行为模式。
     *
     * <p>{@code oblivion} 标记"这是遗忘类效果" —— 决定三件事：
     * 对玩家无效、每 tick 做玩家兜底、到期时要恢复被移除的 AI 行为。
     */
    public enum Mode {
        /** 遗忘（tier 1）：随机移除 1 个 AI 行为。 */
        FORGET(true),
        /** 失忆（tier 2/3）：禁用全部特殊能力。 */
        AMNESIA(true),
        /** 认知过载：amplifier = 层数，伤害乘算由 WS-D 在伤害事件里消费。 */
        COGNITIVE_OVERLOAD(false),
        /** 迟滞：攻击速度与移速降低。 */
        SLUGGISH(false);

        private final boolean oblivion;

        Mode(final boolean oblivion) {
            this.oblivion = oblivion;
        }

        /** 是否为"遗忘类"效果（需要恢复 AI 行为、对玩家无效）。 */
        public boolean isOblivion() {
            return oblivion;
        }

        /** 是否必须对玩家无效。 */
        public boolean blocksPlayers() {
            return oblivion;
        }
    }

    // ------------------------------------------------------------------
    // 迟滞的固定 UUID
    // ------------------------------------------------------------------
    // 用固定 UUID 而不是随机 UUID：重复施加时 AttributeInstance 会先移除同 id 的旧修饰符，
    // 不会叠加出 -30% / -45% 这种滚雪球效果（docs/tech/03 §8.2 的要求）。
    private static final UUID SLUGGISH_MOVE_ID = UUID.fromString("6c1f5b20-9d3a-4e77-8f41-2a5b6c7d8e90");
    private static final UUID SLUGGISH_ATTACK_ID = UUID.fromString("7d2a6c31-0e4b-4f88-9a52-3b6c7d8e9f01");

    private final Mode mode;

    public OblivionEffect(final Mode mode, final int color) {
        super(MobEffectCategory.HARMFUL, color);
        this.mode = mode;

        // MobEffect 的默认 addAttributeModifiers 会用 addTransientModifier 施加，
        // 并按 getAttributeModifierValue(amplifier, modifier) 自动乘以 (amplifier + 1)。
        // 所以这里只声明"每级"的数值，不需要自己覆写 add/removeAttributeModifiers。
        // ⚠️ transient 是硬要求：效果消失/生物卸载后不能把减益留在存档里。
        if (mode == Mode.SLUGGISH) {
            addAttributeModifier(Attributes.MOVEMENT_SPEED, SLUGGISH_MOVE_ID.toString(),
                    -0.15D, AttributeModifier.Operation.MULTIPLY_TOTAL);
            addAttributeModifier(Attributes.ATTACK_SPEED, SLUGGISH_ATTACK_ID.toString(),
                    -0.15D, AttributeModifier.Operation.MULTIPLY_TOTAL);
        }
    }

    public Mode mode() {
        return mode;
    }

    /**
     * 只有遗忘类效果需要每 tick 跑一次 {@link #applyEffectTick}。
     *
     * <p>认知过载与迟滞都不需要逐 tick 逻辑（前者由伤害事件消费，后者由属性修饰符生效），
     * 返回 {@code false} 让 MC 完全跳过它们 —— 不为不存在的需求付出每 tick 开销。
     */
    @Override
    public boolean isDurationEffectTick(final int duration, final int amplifier) {
        return mode.blocksPlayers();
    }

    /**
     * 兜底的"对玩家无效"。
     *
     * <p>主防线是 {@code MobEffectEvent.Applicable} 里的 {@code Result.DENY}（见
     * {@link OblivionManager}），这里只是第二道保险：即使某个模组绕过事件直接把效果塞进来，
     * 也会在下一 tick 被清掉。
     */
    @Override
    public void applyEffectTick(final LivingEntity entity, final int amplifier) {
        if (mode.blocksPlayers() && entity instanceof Player) {
            entity.removeEffect(this);
        }
    }
}
