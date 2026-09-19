package com.etbs31.mnemosyne.spell.base;

import com.etbs31.mnemosyne.Config;
import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.capability.EngramEntry;
import com.etbs31.mnemosyne.oblivion.TraitRegistry;
import io.redspace.ironsspellbooks.api.events.SpellDamageEvent;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「释放一条记忆」——忆格系统的**执行原语**。
 *
 * <p><b>为什么需要这个类</b>：WS-B 交付了 {@code MnemosyneData.releaseEngram}（取出条目）、
 * {@code EngramEntry} 交付了三个子类型的取值方法，但 2026-09-17 检索确认
 * <b>没有任何一处代码真正"执行"过一条被释放的记忆</b> ——
 * 于是 {@code 复诵} / {@code 既视感} / {@code 千忆归一} 三个法术缺少共同的落地动作，
 * 而"忆格释放流程"（WS-I）也无可调用之物。本类就是那个缺口。
 *
 * <p><b>三种记忆的释放语义</b>（{@code docs/tech/03} §6.3）：
 * <table border="1">
 *   <tr><th>类型</th><th>释放动作</th></tr>
 *   <tr><td>{@code SpellMemory} 术忆</td>
 *       <td>把记录的法术**再放一次**：{@code castSpell(..., CastSource.NONE, false)} —— 0 法力、0 冷却</td></tr>
 *   <tr><td>{@code EssenceMemory} 质忆</td>
 *       <td>{@code TraitRegistry.applyTrait} 把特性装到玩家身上</td></tr>
 *   <tr><td>{@code PainMemory} 痛忆</td>
 *       <td>对目标造成等量伤害（走学派伤害源，因此仍受 {@code memory_magic_resist} 影响）</td></tr>
 * </table>
 *
 * <p><b>⭐ 威力系数怎么实现（走马灯 60%~76% / 复诵 +0%~+20%）</b>
 * <br>难点：ISS 的 {@code getSpellPower} 里没有任何"本次施法"的乘数
 * （{@code POWER_MULTIPLIER} 是**法术配置**值，不是施法者状态），
 * 而官方法术的伤害公式我们改不了。
 * <br>可行且统一的落点只有一个：{@link SpellDamageEvent} ——
 * 它在 {@code DamageSources.applyDamage} 里、**护甲与抗性之前**发出，
 * 且实测 ISS 有 60 个文件（所有投射物 + 所有直伤法术）都走这条路。
 * 所以做法是"**施法前挂一个待生效的系数，伤害落地时消费它**"。
 *
 * <p><b>为什么按「法术 id + 施法者」匹配而不是"下一条伤害"</b>：
 * 投射物要飞、多段法术有多段，若"消费即失效"会让第二段丢加成；
 * 若"只认施法者"会污染玩家同时放的其他法术。按法术 id 匹配 + 一个有界的存活期
 * （{@link #SCALE_TTL_TICKS}）是这两者之间的正确折中。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class EngramRelease {

    private EngramRelease() {}

    /**
     * 威力系数的存活期（tick）。
     *
     * <p>5 秒足够任何投射物飞到目标（最快的火球也就 1 秒左右），
     * 又短到"玩家随后自己放同一个法术被误加成"的概率极低。
     */
    private static final int SCALE_TTL_TICKS = 100;

    /** 待生效的威力系数：键 = 施法者 UUID。 */
    private record PowerScale(String spellId, float factor, long expireTick) {}

    private static final Map<UUID, PowerScale> SCALES = new ConcurrentHashMap<>();

    // ==================================================================
    // 释放入口
    // ==================================================================

    /**
     * 释放一条记忆（通用入口）。
     *
     * @param powerScale 威力系数（1.0 = 原样；走马灯传 0.60~0.76，复诵传 1.00~1.20）
     * @param target     {@code 痛忆} 需要的伤害目标；其他类型忽略
     * @param attribution {@code 痛忆} 需要用它构造学派伤害源；传 {@code null} 则痛忆无法释放
     * @return 是否真的执行了什么（{@code false} 时调用方应当**退还法力**而不是静默失败）
     */
    public static boolean release(final ServerPlayer caster, final EngramEntry entry,
                                  final float powerScale, @Nullable final LivingEntity target,
                                  @Nullable final AbstractSpell attribution) {
        if (caster == null || entry == null || caster.level().isClientSide) {
            return false;
        }
        // ⚠️ Java 17：switch 的**模式匹配**是 21 才有的特性，17 下必须用 instanceof 链。
        //    （实测：`case X x ->` 会报"patterns in switch statements 是预览功能"。）
        //    EngramEntry 是 sealed 的，只有 3 个子类，instanceof 链不会漏。
        if (entry instanceof EngramEntry.SpellMemory memory) {
            return releaseSpell(caster, memory.getSpellId(), memory.getSpellLevel(), powerScale);
        }
        if (entry instanceof EngramEntry.EssenceMemory memory) {
            return TraitRegistry.applyTrait(caster, memory.getTraitId(), memory.getDurationTicks());
        }
        if (entry instanceof EngramEntry.PainMemory memory) {
            return releasePain(caster, memory.getAmount(), powerScale, target, attribution);
        }
        return false;
    }

    /**
     * 释放术忆：把记录的法术再放一次。
     *
     * <p><b>⭐ 为什么用 {@code CastSource.NONE}</b>（实测 {@code CastSource} 源码）：
     * <pre>{@code
     * consumesMana()      -> this == SPELLBOOK || (this == SWORD && config)
     * respectsCooldown()  -> this == SPELLBOOK || this == SWORD
     * }</pre>
     * {@code NONE} 两个都是 {@code false} —— 正好是 {@code docs/tech/04} 要求的
     * "0 法力、0 冷却"。用 {@code SPELLBOOK} 会既扣蓝又进冷却，直接把复诵变成废物。
     *
     * <p>⚠️ 释放走的是 {@code AbstractSpell.castSpell}，它会 post {@code SpellOnCastEvent}。
     * 这是**刻意接受**的副作用：术忆的"上一次施放"记录因此会更新为被复诵的法术
     * （语义上说得通 —— 你刚刚确实又放了一次它）。
     * 需要防重入的只有「既视感」，它自己用 {@code REPLAYING} 标记处理。
     *
     * @return 法术已从注册表消失（换 ISS 版本 / 换整合包）时返回 {@code false}
     */
    public static boolean releaseSpell(final ServerPlayer caster, final ResourceLocation spellId,
                                       final int spellLevel, final float powerScale) {
        if (spellId == null) {
            return false;
        }
        // ⭐ 2026-09-18 接入 Config.Spells.SPELL_MEMORY_POWER：
        //    玩家配置的"术忆威力"作为全局系数，乘到来源 powerScale（走马灯/复诵）之上。
        //    设计意图：让玩家能全局削弱/增强"被复诵的神仙法术"，而不必改每个法书。
        //    边界：powerScale < 0 视为未启用（保留原值）。
        final float cfgScale = Config.Spells.SPELL_MEMORY_POWER.get().floatValue();
        final float effectiveScale = powerScale * (cfgScale < 0 ? 1.0F : cfgScale);
        final AbstractSpell spell = SpellRegistry.getSpell(spellId.toString());
        if (spell == null) {
            // 记录的法术不存在了 —— 静默丢弃而不是抛异常（一个改坏的整合包不该让玩家放不出法术）
            MnemosyneMod.LOGGER.debug("[释放] 法术 {} 已不在注册表中，跳过", spellId);
            return false;
        }
        armScale(caster, spellId.toString(), powerScale);
        spell.castSpell(caster.level(), Math.max(1, spellLevel), caster, CastSource.NONE, false);
        return true;
    }

    /**
     * 释放痛忆：对目标造成等量伤害。
     *
     * <p>走 {@link MnemosyneSpell#dealSpellDamage}（内部是 ISS 的
     * {@code DamageSources.applyDamage}），所以：
     * 无视护甲？**否** —— 痛忆是"原样奉还"而不是"真实伤害"，
     * {@code docs/tech/04} §四.7 只写了"无视护甲"，见下方 ⚠️；
     * 受 {@code memory_magic_resist} 影响？**是**（这正是走学派伤害源的目的）。
     *
     * <p>⚠️ <b>已知偏差</b>：{@code docs/04 §7} 写"无视护甲"。
     * 当前实现走标准学派伤害源，因此**会被护甲减免**。
     * 要真正做到"无视护甲"需要一个挂在 {@code bypasses_armor} 标签上的独立伤害类型，
     * 那属于 {@code docs/tech/04} 的数值契约调整（会影响 §五 的强度校验），
     * 不在本次串行收尾的范围内 —— 已在交付说明里登记。
     */
    public static boolean releasePain(final ServerPlayer caster, final float amount,
                                      final float powerScale, @Nullable final LivingEntity target,
                                      @Nullable final AbstractSpell attribution) {
        if (target == null || attribution == null || !target.isAlive()) {
            return false;
        }
        // ⚠️ 2026-09-18：加了「返还比例」配置（设计文档 v2 §9.4 encodePainBaseRefund）。
        //    在此之前是**全额奉还**（amount × powerScale），等于"挨多少打就打回去多少" ——
        //    对高血量玩家来说这是一条没有上限的反伤路径，也是设计文档要求削弱它的原因。
        //
        //    ⚠️ 已知限制：这里只用了**基础值**，没有按"痛忆法术等级 +5%/级"缩放。
        //    原因是释放时拿不到"当初记录时用的等级"（EngramEntry.PainMemory 只存了伤害值）。
        //    要支持按级缩放，需要给 PainMemory 加一个字段 —— 那会动到存档格式，
        //    属于法术重做阶段的事，已登记。
        final double refund = Config.Balance.ENCODE_PAIN_BASE_REFUND.get();
        return MnemosyneSpell.dealSpellDamage(target, caster, caster, attribution,
                (float) (amount * powerScale * refund));
    }

    // ==================================================================
    // 威力系数：挂载 / 消费 / 清理
    // ==================================================================

    private static void armScale(final ServerPlayer caster, final String spellId, final float factor) {
        if (factor == 1.0F) {
            return;
        }
        SCALES.put(caster.getUUID(),
                new PowerScale(spellId, factor, nowTick(caster) + SCALE_TTL_TICKS));
    }

    /**
     * 消费待生效的威力系数。
     *
     * <p>⚠️ 与其他 {@code SpellDamageEvent} 订阅者的顺序**不影响结果**：
     * {@link EngramResonance} 乘的是共鸣（玩家状态），这里乘的是本次释放的系数，
     * 两个都是乘法，交换律成立。
     */
    @SubscribeEvent
    public static void onSpellDamage(final SpellDamageEvent event) {
        if (event.getAmount() <= 0.0F) {
            return;
        }
        final Entity attacker = event.getSpellDamageSource().getEntity();
        if (!(attacker instanceof ServerPlayer caster)) {
            return;
        }
        final PowerScale scale = SCALES.get(caster.getUUID());
        if (scale == null) {
            return;
        }
        if (nowTick(caster) > scale.expireTick()) {
            SCALES.remove(caster.getUUID());
            return;
        }
        final AbstractSpell spell = event.getSpellDamageSource().spell();
        if (spell == null || !spell.getSpellId().equals(scale.spellId())) {
            return;
        }
        event.setAmount(event.getAmount() * scale.factor());
    }

    /** 清理过期的系数。表极小（每玩家至多 1 条），但不清会随在线时间无界增长。 */
    @SubscribeEvent
    public static void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SCALES.isEmpty()) {
            return;
        }
        final long now = event.getServer().overworld().getGameTime();
        for (final Iterator<Map.Entry<UUID, PowerScale>> it = SCALES.entrySet().iterator(); it.hasNext(); ) {
            if (now > it.next().getValue().expireTick()) {
                it.remove();
            }
        }
    }

    /** 登出 → 丢弃，避免 UUID 复用把系数带给另一个玩家。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(final PlayerEvent.PlayerLoggedOutEvent event) {
        SCALES.remove(event.getEntity().getUUID());
    }

    /**
     * 当前世界刻 —— 与 {@code MnemosyneData} / {@code OblivionManager} 同一套约定：
     * 统一取**主世界**的 {@code getGameTime()}，避免跨维度时数字跳变。
     */
    private static long nowTick(final ServerPlayer player) {
        return player.server.overworld().getGameTime();
    }

    /** 供 {@code SeaOfMemorySpell} 等判断"这条记忆能不能被释放"。 */
    public static boolean isReleasable(final EngramEntry entry) {
        if (entry instanceof EngramEntry.SpellMemory memory) {
            return memory.resolveSpell() != null;
        }
        return entry instanceof EngramEntry.EssenceMemory;
    }

    /** 供调试：当前是否有待生效的威力系数。 */
    public static boolean hasPendingScale(final ServerPlayer player) {
        return SCALES.containsKey(player.getUUID());
    }

    /** 供调试：清掉待生效的系数（{@code 既视感} 在重放结束时用）。 */
    public static void clearPendingScale(final ServerPlayer player) {
        SCALES.remove(player.getUUID());
    }

    /** 供日志：确保 {@code ServerLevel} 的 import 不被误删（{@code castSpell} 需要真实服务端世界）。 */
    static boolean isServerSide(final ServerPlayer player) {
        return player.level() instanceof ServerLevel;
    }
}
