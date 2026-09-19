package com.etbs31.mnemosyne.spell.base;

import com.etbs31.mnemosyne.Config;
import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.capability.MnemosyneData;
import com.etbs31.mnemosyne.registry.ModEffects;
import com.etbs31.mnemosyne.registry.ModSchools;
import io.redspace.ironsspellbooks.api.events.SpellDamageEvent;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.SchoolType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * 忆格的**代价与回报**——把 {@code MnemosyneData} 的三个倍率真正接到 ISS 上。
 *
 * <p><b>为什么需要这个类（一个被漏掉的集成）</b>：
 * {@code MnemosyneData} 从 WS-B 起就提供了三个冻结契约方法
 * （{@code getResonanceMultiplier} / {@code getManaPenalty} / {@code getCastSpeedPenalty}），
 * 但 2026-09-17 全库检索确认：**除了它们自己的定义处，没有任何一处调用**。
 * 也就是说忆格系统最核心的风险收益（"记忆越多越强、但越贵越慢"）在游戏里**完全不存在** ——
 * 编译通过、日志无输出、只是"感觉流派没什么手感"。
 *
 * <p>本类把三个倍率分别接到 ISS 的三个真实钩子上：
 * <table border="1">
 *   <tr><th>倍率</th><th>接入点</th><th>为什么是这里</th></tr>
 *   <tr>
 *     <td>共鸣加成<br>（伤害 ×）</td>
 *     <td>{@link SpellDamageEvent}</td>
 *     <td>唯一能在**所有**伤害落地前改数值、且覆盖官方法术与自家法术的公共点
 *         （{@code DamageSources.applyDamage} 里 post，实测 60 个 ISS 文件都走它）</td>
 *   </tr>
 *   <tr>
 *     <td>法力惩罚<br>（耗蓝 ×）</td>
 *     <td>{@link SpellOnCastEvent#setManaCost(int)}</td>
 *     <td>ISS 在扣蓝**之前** post，改它就等于改真实消耗（不用自己扣一遍）</td>
 *   </tr>
 *   <tr>
 *     <td>施法速度惩罚<br>（吟唱时长 ×）</td>
 *     <td>{@code AttributeRegistry.CAST_TIME_REDUCTION}</td>
 *     <td>{@code getCastTime(int)} **拿不到施法者**（只有一个 int 参数），
 *         所以只能在"施法者身上"表达 —— ISS 的 {@code getEffectiveCastTime(level, entity)}
 *         正好读这个属性。用 transient 修饰符，不进存档</td>
 *   </tr>
 * </table>
 *
 * <p><b>⭐ 施法速度的非线性映射（逐字取自 ISS 源码）</b>
 * <pre>{@code
 * // AbstractSpell.getEffectiveCastTime(int spellLevel, LivingEntity entity)
 * entityCastTimeModifier = 2 - Utils.softCapFormula(entity.getAttributeValue(CAST_TIME_REDUCTION));
 * // Utils.softCapFormula(x) = x <= 1.5 ? x : -0.25/(x-1) + 2
 * }</pre>
 * 属性默认 1.0 → 倍率 {@code 2 - 1 = 1.0}（中性）。我们想要"施法速度 × penalty"
 * 即"吟唱时长 × 1/penalty"，所以：
 * <pre>
 * 目标属性值 = 2 - 1/penalty
 * 修饰符数值 = 目标属性值 - 1 = 1 - 1/penalty      （MULTIPLY_TOTAL，基准 1.0）
 * </pre>
 * 5 格时 penalty = 0.6 → 修饰符 -0.667 → 属性 0.333 → 倍率 1.667（吟唱慢 67%）。✔
 *
 * <p><b>不接入的两个特例</b>（{@code docs/tech/04} 明写"不受共鸣加成影响"）：
 * {@link #RESONANCE_EXEMPT} 里的两个爆发法术走的是**固定每格伤害表**，
 * 它们的设计是"用忆格数量换伤害"，再乘一次共鸣会直接翻倍。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class EngramResonance {

    private EngramResonance() {}

    /** 明确**不**吃共鸣加成的法术（{@code docs/tech/04} §四.13 / §四.18 末行）。 */
    private static final Set<String> RESONANCE_EXEMPT = Set.of(
            "mnemosyne:cognitive_collapse",
            "mnemosyne:thousand_memories");

    /**
     * 施法速度惩罚修饰符的固定 UUID。
     *
     * <p>固定 UUID 而不是随机：{@code addTransientModifier} 遇到同 UUID 会**替换**，
     * 所以每 20 tick 刷新一次不会越叠越慢；玩家退出重进也能被下一次刷新重新装上。
     */
    private static final UUID CAST_PENALTY_ID =
            UUID.nameUUIDFromBytes("mnemosyne:engram_cast_penalty".getBytes(StandardCharsets.UTF_8));

    /** 属性刷新间隔（tick）。忆格数量变化本身很低频，1 秒的延迟不值得每 tick 去同步。 */
    private static final int SYNC_INTERVAL = 20;

    /**
     * 「记忆空白」期间受到的忆海法术伤害倍率。
     *
     * <p>走马灯触发保命后的代价之一（设计文档 v2 §一：+50%）。
     * 定成常量而不是配置：它是"免死的价格"，属于机制而非调参项 ——
     * 玩家把它调成 1.0 就等于"免死无代价"，那这个法术的平衡就没了。
     */
    private static final float MEMORY_BLANK_DAMAGE_MULTIPLIER = 1.5F;

    // ==================================================================
    // 共鸣加成（伤害）
    // ==================================================================

    /**
     * 忆海学派法术的伤害乘上共鸣倍率。
     *
     * <p>判定用 {@code 伤害源 → 法术 → 学派}，而不是"施法者是不是记忆法师" ——
     * 后者会把玩家的**官方法术**（火球、冰锥）也一起加成，那是明显的越界。
     *
     * <p>⚠️ 只对**玩家**施法者生效。怪物用的记忆法术不吃玩家的共鸣，
     * 也不该受"忆格占用"这种玩家侧概念影响。
     */
    @SubscribeEvent
    public static void onSpellDamage(final SpellDamageEvent event) {
        if (event.getAmount() <= 0.0F) {
            return;
        }
        if (!(event.getSpellDamageSource().getEntity() instanceof ServerPlayer caster)) {
            return;
        }
        final AbstractSpell spell = event.getSpellDamageSource().spell();
        if (spell == null || !isMemorySchool(spell.getSchoolType())) {
            return;
        }
        if (RESONANCE_EXEMPT.contains(spell.getSpellId())) {
            return;
        }
        final double resonance = MnemosyneData.getResonanceMultiplier(caster);
        if (resonance != 1.0D) {
            event.setAmount((float) (event.getAmount() * resonance));
        }

        // ⭐⭐ 2026-09-18：认知过载的增伤 —— 这是本学派**最核心的机制**，
        //    而在此之前它**完全没有实现**：认知过载只是个"层数计数器"，
        //    除了「认知崩坏」会读层数算自己的伤害之外，叠层对任何伤害都没有影响。
        //
        //    表现就是玩家的原话："法术有什么用？"—— 你辛辛苦苦叠到 6 层，
        //    面板上什么都不变，因为确实什么都没发生。
        //
        //    这里补上设计文档 v2 §0.2 的定义：
        //      每层使目标受到的**所有忆海法术伤害** +damagePerStack（默认 8%，线性）。
        //
        //    ⚠️ 目标从事件本身拿：SpellDamageEvent extends LivingEvent，
        //       getEntity() 就是**被打的那个**（不是施法者）。施法者是
        //       spellDamageSource.getEntity()。两个别搞混。
        final MobEffect overload = ModEffects.cognitiveOverload();
        if (overload != null) {
            final MobEffectInstance stacks = event.getEntity().getEffect(overload);
            if (stacks != null) {
                final int layers = stacks.getAmplifier() + 1;
                final double bonus = 1.0D + Config.Overload.DAMAGE_PER_STACK.get() * layers;
                event.setAmount((float) (event.getAmount() * bonus));
            }
        }

        // 「记忆空白」的代价之一：受到的忆海法术伤害 +50%（设计文档 v2 §一）。
        //
        // ⚠️ 为什么不用属性表达：ISS 的 SPELL_RESIST 是"抗性"（减伤），
        //    要表达"受伤加成"得给抗性加负修饰符 —— 而抗性属性是全局的，
        //    会连带影响其他学派。留在事件里判定才能精准只作用于忆海法术。
        final MobEffect blank = ModEffects.memoryBlank();
        if (blank != null && event.getEntity().hasEffect(blank)) {
            event.setAmount(event.getAmount() * MEMORY_BLANK_DAMAGE_MULTIPLIER);
        }
    }

    // ==================================================================
    // 法力惩罚
    // ==================================================================

    /**
     * 忆海法术的法力消耗乘上惩罚倍率。
     *
     * <p>只改 {@code event.setManaCost(...)}，**不自己扣蓝** ——
     * ISS 的 {@code castSpell} 紧接着会读这个值去扣。
     * 自己再扣一次是最容易犯的重复扣费错误。
     *
     * <p>创造模式免疫（{@code ServerConfigs.CREATIVE_MANA_COST}）由 ISS 自己处理，这里不判。
     */
    @SubscribeEvent
    public static void onSpellOnCast(final SpellOnCastEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer caster)) {
            return;
        }
        if (!isMemorySchool(event.getSchoolType())) {
            return;
        }
        final double penalty = MnemosyneData.getManaPenalty(caster);
        if (penalty == 1.0D) {
            return;
        }
        event.setManaCost(Math.max(0, (int) Math.round(event.getManaCost() * penalty)));
    }

    // ==================================================================
    // 施法速度惩罚 —— 2026-09-18 已迁到原版状态效果承载
    // ==================================================================
    //
    // ⚠️ 这里原先有 onPlayerTick(@SubscribeEvent) + syncCastSpeedPenalty()：
    //    每 20 tick 手工 addTransientModifier 往 ISS 的 CAST_TIME_REDUCTION 上塞惩罚。
    //
    //    那是**在手工重造 MobEffect.addAttributeModifiers 已经做好的事**，已删除。
    //    现在由 oblivion/EngramEffect 的 BURDEN 模式承载：
    //      · 属性修饰符的增删由原版在效果增删时自动完成（不会漏 → 不会有孤儿修饰符）
    //      · 数值由 EngramEffect.getAttributeModifierValue 覆写计算（非线性公式，见那个类的注释）
    //      · 刷新时机由 MnemosyneData.refreshEngramEffects 统一驱动
    //
    //    getCastSpeedPenalty() 保留为**纯函数**：它是上面那条非线性公式的"可读版本"，
    //    改公式时两处必须同步（EngramEffect 里也有同样的提醒）。

    // ==================================================================
    // 工具
    // ==================================================================

    /** 该学派是不是忆海（用 id 比对，避免依赖 {@code ModSchools} 的单例是否已构造）。 */
    private static boolean isMemorySchool(final SchoolType school) {
        if (school == null) {
            return false;
        }
        final ResourceLocation id = school.getId();
        return ModSchools.MEMORY_RESOURCE.equals(id);
    }
}
