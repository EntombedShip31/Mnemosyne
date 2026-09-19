package com.etbs31.mnemosyne.oblivion;

import com.etbs31.mnemosyne.Config;
import com.etbs31.mnemosyne.MnemosyneMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BOSS 免疫与降级路径 —— 设计红线的执行者。
 *
 * <p><b>文件归属</b>：WS-E 遗忘系统。
 *
 * <p><b>为什么这条规则存在</b>（{@code docs/02} §三"为什么 BOSS 免疫"）：
 * 如果 BOSS 能被遗忘，最有设计感的 Boss 战会退化成"按一下键然后站着砍"。
 * 这不是平衡问题，是**设计尊严问题**。所以 BOSS **完全免疫剥夺**，
 * 改为数值化削弱 —— 这样记忆流派在 Boss 战里仍然有价值（少数能削弱 Boss 的流派），
 * 又不会摧毁 Boss 的设计。
 *
 * <p><b>⭐ BOSS 判定必须自建</b>：实测原版**没有** BOSS 标签或 BOSS 注册表
 * （ISS 的 {@code api/util/BossbarManager} 只管自定义血条，不是分类）。
 * 判定表在 {@link AbilityMap#isBoss}，数据包可用 {@code bosses.json} 覆盖。
 *
 * <p><b>降级效果</b>：{@code -20%} 攻击力，持续 30 秒
 * （幅度取 {@code oblivion.bossAttackReduction}，默认 {@code 0.20}）。
 * 用**固定 UUID 的 transient 修饰符**：
 * <ul>
 *   <li>固定 UUID → 重复施加先移除旧的，不会叠成 -40% / -60%</li>
 *   <li>transient → 不写进存档，服务器重启后不会留下被永久削弱的 BOSS</li>
 * </ul>
 *
 * <p><b>⚠️ 与 {@code docs/02} §三 的差异（有意为之）</b>：
 * 设计文档给四个遗忘法术各配了一种 BOSS 效果（遗忘→-15% 攻击 / 失忆→停止攻击 1.5 秒 /
 * 集体遗忘→受伤 +15% / 遗忘诅咒→攻速 -20%）。
 * 本工作流按 {@code docs/tech/12} 提示词 A 的冻结规格实现**统一的一条**：
 * {@code -20% 攻击力 30 秒}。理由：四种各写一套需要四套独立的到期调度，
 * 而"受伤 +15%"在 1.20.1 没有对应属性，必须另开伤害事件 —— 那是 WS-E2
 * （规范里名字就叫「遗忘系统的 BOSS 免疫与降级路径」）的活。
 * 这里**不做半成品**：半成品的定时器比不实现更危险。
 */
public final class BossImmunity {

    private BossImmunity() {}

    /** 固定 UUID：重复施加先移除旧的，避免叠加。 */
    private static final UUID ATTACK_MODIFIER_ID = UUID.fromString("1a7c9e40-3b52-4d6f-8e19-4c5d6e7f8091");

    /** 降级持续时间：30 秒（提示词 A 的冻结规格）。 */
    private static final int FALLBACK_TICKS = 600;

    /** 待移除的修饰符：修饰符本身不会自动到期，必须自己记时间。 */
    private record Pending(LivingEntity entity, long expireTick) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    // ==================================================================
    // 判定
    // ==================================================================

    /**
     * 目标是否免疫剥夺。
     *
     * <p>三道判断，缺一不可：
     * <ol>
     *   <li>{@code oblivion.bossImmunity} 配置关掉 → 谁都不免疫（给整合包留的开关）</li>
     *   <li>玩家 → 不适用（遗忘类法术本来就对玩家无效，这里让语义完整）</li>
     *   <li>{@link AbilityMap#isBoss} → 真正判定</li>
     * </ol>
     */
    public static boolean isImmune(final LivingEntity target) {
        if (target == null || target instanceof Player) {
            return false;
        }
        if (!Config.Oblivion.BOSS_IMMUNITY.get()) {
            return false;
        }
        return AbilityMap.isBoss(target.getType());
    }

    // ==================================================================
    // 降级
    // ==================================================================

    /**
     * 对免疫剥夺的目标施加数值化削弱。
     *
     * @param tier 只用于日志与将来的分档扩展（当前四个层级统一 -20% 攻击力）
     */
    public static void applyFallback(final ServerPlayer caster, final LivingEntity target,
                                     final OblivionTier tier) {
        if (target == null || !target.isAlive()) {
            return;
        }
        final double reduction = Config.Oblivion.BOSS_ATTACK_REDUCTION.get();
        final AttributeInstance attack = target.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null && reduction > 0.0D) {
            // 先移除旧的同 id 修饰符 → 重复施加只刷新时长，不叠加数值
            attack.removeModifier(ATTACK_MODIFIER_ID);
            attack.addTransientModifier(new AttributeModifier(ATTACK_MODIFIER_ID,
                    "mnemosyne:oblivion_boss_attack", -reduction,
                    AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
        PENDING.put(target.getUUID(),
                new Pending(target, OblivionManager.nowTick(target) + FALLBACK_TICKS));
        MnemosyneMod.LOGGER.debug("[WS-E] {} 免疫 {} 剥夺，改为攻击力 -{}%",
                target.getName().getString(), tier.key(), Math.round(reduction * 100.0D));
    }

    /** 每 tick 由 {@link OblivionManager} 驱动：到期或目标消失时移除修饰符。 */
    static void tick(final long now) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (final Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            final Pending pending = it.next().getValue();
            final LivingEntity entity = pending.entity();
            if (entity.isRemoved() || !entity.isAlive() || now >= pending.expireTick()) {
                drop(entity);
                it.remove();
            }
        }
    }

    /** 目标死亡 / 被强制恢复时调用：立刻摘掉修饰符，不留残留。 */
    static void forget(final LivingEntity entity) {
        PENDING.remove(entity.getUUID());
        drop(entity);
    }

    private static void drop(final LivingEntity entity) {
        final AttributeInstance attack = entity.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null) {
            attack.removeModifier(ATTACK_MODIFIER_ID);
        }
    }
}
