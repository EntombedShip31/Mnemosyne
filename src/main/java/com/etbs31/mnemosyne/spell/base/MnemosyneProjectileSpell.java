package com.etbs31.mnemosyne.spell.base;

import io.redspace.ironsspellbooks.api.config.DefaultConfig;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * 投射物法术的基类。
 *
 * <p><b>文件归属</b>：WS-C 法术基类。
 *
 * <p><b>适用</b>：忆矢（{@code memory_arrow}），并为将来的投射物类法术预留。
 *
 * <p><b>契约（冻结，{@code docs/tech/11} §七）</b>：
 * <pre>{@code
 * MnemosyneProjectileSpell(DefaultConfig)
 * protected abstract float getProjectileDamage(int spellLevel)
 * }</pre>
 *
 * <p><b>⭐ {@code getProjectileDamage(int)} 的精确语义</b>（这一条必须写死，否则伤害会算错）：
 * 它返回的是**该等级的"基础伤害"，不含施法者加成**，也就是
 * <pre>
 * getProjectileDamage(level) = basePowerOf(level) × 伤害系数
 * </pre>
 * 施法者加成（{@code SPELL_POWER × 学派强度 × POWER_MULTIPLIER}）由基类在
 * {@link #computeFinalDamage} 里乘上去。
 *
 * <p>以忆矢为例（{@code docs/tech/04} §四.1）：{@code baseSpellPower=12}、
 * {@code spellPowerPerLevel=2}、伤害系数 {@code ×0.5}。
 * 5 级时 {@code basePowerOf(5) = 12 + 2×4 = 20}，{@code getProjectileDamage(5) = 20 × 0.5 = 10} ——
 * 与文档里 5 级伤害 10 的表格**完全对上**。
 *
 * <p><b>⚠️ 投射物实体本身属于 WS-J</b>（{@code entity/**}），本基类**不创建实体**。
 * WS-J 交付 {@code entity/MemoryArrowEntity} 后，WS-D1 在 {@code MemoryArrowSpell.onCast} 里
 * {@code level.addFreshEntity(new MemoryArrowEntity(...))}，并在实体的命中回调里调
 * {@link #onProjectileHit}。这样 WS-C 与 WS-J 可以并行开发。
 */
public abstract class MnemosyneProjectileSpell extends MnemosyneSpell {

    protected MnemosyneProjectileSpell(final DefaultConfig defaultConfig) {
        super(defaultConfig);
    }

    // ==================================================================
    // 子类实现
    // ==================================================================

    /**
     * 该等级的**基础伤害**（不含施法者加成）。
     *
     * <p>实现范式：
     * <pre>{@code
     * @Override
     * protected float getProjectileDamage(int spellLevel) {
     *     return basePowerOf(spellLevel) * 0.5F;   // 0.5 = docs/tech/04 里的"伤害系数"
     * }
     * }</pre>
     */
    protected abstract float getProjectileDamage(int spellLevel);

    // ==================================================================
    // 共享逻辑
    // ==================================================================

    /**
     * 最终伤害 = 基础伤害 × 施法者加成倍率。
     *
     * <p>⚠️ 必须用这个，不要直接用 {@link #powerOf} —— 那会把 {@code baseSpellPower} 算两次。
     *
     * <p>⚠️ 2026-09-17 由 {@code protected} 放宽为 {@code public}：
     * 投射物实体（{@code entity/MemoryArrowEntity}）在命中时才算伤害，
     * 而它拿到的是"法术实例"（经 {@code SpellRegistry.getSpell}），不是本类的子类实例 ——
     * Java 的 protected 规则不允许"子类里访问另一个父类类型引用的 protected 成员"。
     * 放宽可见性是唯一的干净解法，比把伤害公式复制到实体里安全得多
     * （复制会让"baseSpellPower 只算一次"这条容易搞错的规则在两处漂移）。
     */
    public final float computeFinalDamage(final int spellLevel, final LivingEntity caster) {
        return getProjectileDamage(spellLevel) * powerMultiplierOf(spellLevel, caster);
    }

    /**
     * 投射物命中回调 —— **由投射物实体在命中时调用**（WS-J）。
     *
     * <p>⚠️ 取伤害源时**直接实体是投射物、间接实体是施法者**：
     * {@code getDamageSource(projectile, caster)}。
     * 文档 {@code docs/tech/03} §4.2 明确要求"在投射物的命中回调里，用**施法者**去取伤害源"，
     * 但 ISS 的两参版本正是为了区分"谁造成伤害"与"谁的属性生效" ——
     * 传 {@code (projectile, caster)} 时 ISS 会用 caster 的学派强度算加成、用 projectile 做直接归因。
     *
     * @return 目标是否真的受到了伤害
     */
    public final boolean onProjectileHit(final Level level, final LivingEntity caster,
                                         final Entity projectile, final Entity target,
                                         final int spellLevel) {
        if (level.isClientSide || !(target instanceof LivingEntity living)) {
            return false;
        }
        final float damage = computeFinalDamage(spellLevel, caster);
        final boolean hurt = hurtWithSpellDamage(living, projectile, caster, damage);
        onProjectileHitExtra(level, caster, projectile, living, spellLevel);
        return hurt;
    }

    /**
     * 命中后的额外效果（叠认知过载、播放命中音效、粒子等）。
     *
     * <p>默认空实现。WS-D1 在 {@code MemoryArrowSpell} 里覆写它加"命中叠 1 层认知过载"。
     */
    protected void onProjectileHitExtra(final Level level, final LivingEntity caster,
                                        final Entity projectile, final LivingEntity target,
                                        final int spellLevel) {
        // 子类覆写。
    }

    /**
     * {@inheritDoc}
     *
     * <p>⚠️ 投射物法术的 {@code onCast} 里**不要**直接打伤害 —— 伤害发生在命中时
     * （{@link #onProjectileHit}）。{@code onCast} 只负责生成投射物实体。
     *
     * <p><b>⭐ 2026-09-17：这个 TODO 已经清掉了</b>。原先这里什么都不做，
     * 而 {@code MemoryArrowSpell} 也照抄了一个 TODO —— 结果是**忆矢放出后完全没反应**：
     * 法力扣了、冷却转了、没有箭、没有伤害、没有音效。
     * 现在 {@code MemoryArrowSpell.onCast} 会生成 {@code entity/MemoryArrowEntity}。
     *
     * <p>基类这里保持"什么都不做"是**正确**的：不是每个投射物法术都要生成同一个实体。
     * 但子类**必须**覆写本方法，否则就是上面那个静默失效。
     */
    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }
}
