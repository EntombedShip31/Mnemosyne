package com.etbs31.mnemosyne.oblivion;

import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 「走马灯」保命技用到的两个状态效果。
 *
 * <p><b>设计文档 v2 §一</b>把「走马灯」从"逼敌人重演技能"改成了**不死图腾式的保命技**。
 * 原文的理由是对的：MC 里没有稳定的"逼 AI 重演某个技能"接口，
 * 旧实现的逻辑是飘的（要靠观察目标最近用过什么，再替它施放一次）。
 * <br>保命技则是纯本地状态机 —— 可靠、可测、语义清晰。
 *
 * <p><b>两个效果的分工</b>
 * <table border="1">
 *   <tr><th>Mode</th><th>类别</th><th>作用</th></tr>
 *   <tr><td>{@link Mode#RECOLLECTION_WARD}</td><td>增益</td>
 *       <td>10 秒的"保命窗口"。它本身**不做任何事**，只是被 {@code LivingDeathEvent}
 *           查一下"在不在"。触发后立刻移除。</td></tr>
 *   <tr><td>{@link Mode#MEMORY_BLANK}</td><td>减益</td>
 *       <td>触发保命后的代价：法力恢复 −40%（属性修饰符）+
 *           受到的忆海法术伤害 +50%（由 {@code EngramResonance} 读这个效果结算）+
 *           临时忆格被清空（由 {@code RecollectionSpell} 在触发时执行一次）。</td></tr>
 * </table>
 *
 * <p>⚠️ <b>为什么"受到的忆海法术伤害 +50%"不写成属性</b>：
 * ISS 的 {@code SPELL_RESIST} 是"抗性"，减伤用的；要表达"受伤加成"
 * 得给抗性加负修饰符，那会连带影响其他学派（属性是全局的）。
 * <br>所以这一条留在 {@code SpellDamageEvent} 里按目标身上的效果判定 ——
 * 精准作用于忆海法术，不污染其他学派。
 */
public class MemoryStateEffect extends MobEffect {

    /** 「记忆空白」的法力恢复惩罚：−40%。 */
    private static final double MANA_REGEN_PENALTY = -0.40D;

    /** 法力恢复修饰符的固定 UUID。固定值 → 同 UUID 会替换而非叠加。 */
    private static final UUID BLANK_MANA_ID =
            UUID.nameUUIDFromBytes("mnemosyne:memory_blank_mana".getBytes(StandardCharsets.UTF_8));

    /** 定身的移动速度修饰符固定 UUID。固定值 → 重复施加是替换而不是叠加。 */
    private static final UUID BIND_SPEED_ID =
            UUID.nameUUIDFromBytes("mnemosyne:endless_bind_speed".getBytes(StandardCharsets.UTF_8));

    /** 走马灯守护的显示色（靛蓝，学派主色）。 */
    public static final int COLOR_WARD = 0x534AB7;

    /** 记忆空白的显示色（品红，读作"代价"）。 */
    public static final int COLOR_BLANK = 0xD4537E;

    /**「无尽忆域」定身的显示色（白偏靛，读作"信息灌满、动不了"）。 */
    public static final int COLOR_BIND = 0x9E93E8;

    public enum Mode {
        /** 保命窗口：10 秒内第一次致死会被拦截。 */
        RECOLLECTION_WARD(true),
        /** 触发保命后的代价：法力恢复 −40%、受到的忆海法术伤害 +50%、临时忆格清空。 */
        MEMORY_BLANK(false),
        /**
         * 「无尽忆域」的定身：移动速度归零。
         *
         * <p>⚠️ 这个效果<b>不是</b>"缓存"，但同样必须每 tick 由领域补刷
         * （见 {@code EndlessRealmSpell} 类注释）：牛奶能顶掉一次，下一 tick 立刻补回。
         * 它自身时长很短，所以即使领域异常结束，也不会留下被永久定住的生物。
         */
        ENDLESS_BIND(false);

        private final boolean beneficial;

        Mode(final boolean beneficial) {
            this.beneficial = beneficial;
        }

        public boolean beneficial() {
            return beneficial;
        }
    }

    private final Mode mode;

    public MemoryStateEffect(final Mode mode, final int color) {
        super(mode.beneficial() ? MobEffectCategory.BENEFICIAL : MobEffectCategory.HARMFUL, color);
        this.mode = mode;

        // 「记忆空白」的法力恢复惩罚走属性修饰符 —— 原版会自动在效果增删时增删它，
        // 不会出现"效果没了但修饰符还挂着"的孤儿（手工路径最容易出的就是这类 bug）。
        if (mode == Mode.MEMORY_BLANK && AttributeRegistry.MANA_REGEN.isPresent()) {
            addAttributeModifier(AttributeRegistry.MANA_REGEN.get(),
                    BLANK_MANA_ID.toString(), MANA_REGEN_PENALTY,
                    AttributeModifier.Operation.MULTIPLY_TOTAL);
        }
        if (mode == Mode.ENDLESS_BIND) {
            // −1.0 的 MULTIPLY_TOTAL = 速度 ×(1−1) = 0，也就是彻底走不动。
            // 走属性而不是 setNoAi 的理由见 EndlessRealmSpell 的类注释。
            addAttributeModifier(Attributes.MOVEMENT_SPEED,
                    BIND_SPEED_ID.toString(), -1.0D,
                    AttributeModifier.Operation.MULTIPLY_TOTAL);
        }
    }

    public Mode mode() {
        return mode;
    }

    /** 纯状态标记，不需要每 tick 的 {@code applyEffectTick}。 */
    @Override
    public boolean isDurationEffectTick(final int duration, final int amplifier) {
        return false;
    }
}
