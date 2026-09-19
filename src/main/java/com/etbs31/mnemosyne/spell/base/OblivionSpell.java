package com.etbs31.mnemosyne.spell.base;

import com.etbs31.mnemosyne.Config;
import com.etbs31.mnemosyne.oblivion.BossImmunity;
import com.etbs31.mnemosyne.oblivion.OblivionManager;
import com.etbs31.mnemosyne.oblivion.OblivionTier;
import com.etbs31.mnemosyne.util.RaycastHelper;
import com.etbs31.mnemosyne.util.SpellFeedback;
import io.redspace.ironsspellbooks.api.config.DefaultConfig;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 遗忘类法术的基类 —— 从目标身上抹去记忆。
 *
 * <p><b>文件归属</b>：WS-C 法术基类。
 *
 * <p><b>适用</b>：遗忘（tier 1）、失忆（tier 2）、遗忘诅咒（tier 2，领域）、
 * 集体遗忘（tier 3，范围）。子类只实现 {@link #getOblivionTier} 与 {@link #applyOblivion}。
 *
 * <p><b>契约（冻结，{@code docs/tech/11} §七）</b>：
 * <pre>{@code
 * OblivionSpell(DefaultConfig)
 * protected abstract int getOblivionTier()
 * protected abstract void applyOblivion(ServerPlayer caster, LivingEntity target, int spellLevel)
 * }</pre>
 *
 * <p><b>设计红线</b>（{@code docs/README.md} §二 原则三）：
 * <b>BOSS 完全免疫"剥夺"</b>，改为数值化削弱。
 * 所以本类的流程是：取目标 → 判定免疫 → 免疫走 {@link #applyBossFallback}，
 * 不免疫走 {@link #applyOblivion}。子类**不需要**自己判 BOSS。
 *
 * <p><b>⭐ WS-E 已接线（2026-09-16）</b>：三个 {@code TODO(WS-E)} 钩子已实现，
 * 全部委托给 {@code com.etbs31.mnemosyne.oblivion} 包：
 * <ul>
 *   <li>{@link #oblivionManager} → {@link OblivionManager#applyOblivion}</li>
 *   <li>{@link #isOblivionImmune} → {@link BossImmunity#isImmune}（原先的血量启发式已删除）</li>
 *   <li>{@link #applyBossFallback} → {@link BossImmunity#applyFallback}</li>
 * </ul>
 * 本文件是 WS-C 的属地，WS-E 只改了这三个方法体与它们的注释，
 * **没有动任何签名**，也没有动 {@link #applyTo} 的流程。
 */
public abstract class OblivionSpell extends MnemosyneSpell {

    /** 遗忘类法术的默认射线射程（{@code docs/tech/03} §五：遗忘 20 格）。 */
    protected static final float DEFAULT_OBLIVION_RANGE = 20.0F;

    protected OblivionSpell(final DefaultConfig defaultConfig) {
        super(defaultConfig);
    }

    // ==================================================================
    // 子类实现
    // ==================================================================

    /**
     * 遗忘层级。1 = 遗忘，2 = 失忆，3 = 集体遗忘。
     * 影响持续时间与后续的映射表深度（见 {@code docs/tech/03} §七）。
     */
    protected abstract int getOblivionTier();

    /** 对**非免疫**目标施加遗忘效果。子类在这里调用 {@link #oblivionManager}。 */
    protected abstract void applyOblivion(ServerPlayer caster, LivingEntity target, int spellLevel);

    // ==================================================================
    // 可覆写钩子
    // ==================================================================

    /**
     * 实际执行"移除 AI 行为 / 剥夺特性"的入口。
     *
     * <p>实现：委托给 WS-E 的 {@link OblivionManager#applyOblivion}。
     * 那里会处理"随机一个 vs 全部"、通用降级、状态效果承载与到期恢复。
     *
     * @return 是否成功剥夺了什么（走降级路径时返回 {@code false}）
     */
    protected boolean oblivionManager(final ServerPlayer caster, final LivingEntity target, final int tier) {
        return OblivionManager.applyOblivion(caster, target, tier);
    }

    /**
     * 带**显式时长**的版本 —— 给"持续时间随等级递增"的遗忘法术用。
     *
     * <p>{@code docs/tech/04} §四.3/§四.8/§四.15 的持续时间都是按等级分档的
     * （遗忘 6/7/8/9/10 秒、失忆 4/5/6/7/8 秒、集体遗忘 6/7/8/9/12 秒），
     * 而 {@link OblivionTier#durationTicks()} 只能读一个固定配置值。
     * 这个重载转发到 {@code OblivionManager} 的四参版（它本身是三参冻结契约的扩展）。
     *
     * @param durationTicks 显式持续时长（tick）；{@code <= 0} 表示用层级默认值
     */
    protected boolean oblivionManager(final ServerPlayer caster, final LivingEntity target,
                                      final int tier, final int durationTicks) {
        return OblivionManager.applyOblivion(caster, target, tier, durationTicks);
    }

    /**
     * 目标是否免疫剥夺。
     *
     * <p>实现：委托给 WS-E 的 {@link BossImmunity#isImmune}，判定表是
     * {@code AbilityMap.isBoss}（数据包 {@code bosses.json} 可覆盖）。
     *
     * <p>原先这里的"怪物类别 + 血量 ≥ 100"启发式**已删除**：
     * 原版没有 BOSS 标签，但启发式会把铁傀儡这类高血量非 BOSS 误判，
     * 而 WS-E 的显式表既准确又可由整合包扩展。
     */
    protected boolean isOblivionImmune(final LivingEntity target) {
        return BossImmunity.isImmune(target);
    }

    /**
     * BOSS 的降级路径：不剥夺，改为数值化削弱。
     *
     * <p>实现：委托给 WS-E 的 {@link BossImmunity#applyFallback} ——
     * 攻击力 {@code -oblivion.bossAttackReduction}（默认 -20%），持续 30 秒，
     * 用固定 UUID 的 **transient** 修饰符（重复施加先移除旧的，不会叠加）。
     *
     * <p>⚠️ {@code docs/02} §三 给四个法术各配了一种 BOSS 效果的"分档版"
     * （失忆→停止攻击 1.5 秒 / 集体遗忘→受伤 +15% / 遗忘诅咒→攻速 -20%）
     * 属于 **WS-E2** 的范围，当前四个层级统一走 -20% 攻击力。
     */
    protected void applyBossFallback(final ServerPlayer caster, final LivingEntity target, final int spellLevel) {
        BossImmunity.applyFallback(caster, target, OblivionTier.byId(getOblivionTier()));
    }

    /**
     * 取射线目标。
     *
     * <p>默认：{@code DEFAULT_OBLIVION_RANGE} 格、被方块阻挡、排除自己与友军。
     */
    @Nullable
    protected LivingEntity resolveTarget(final Level level, final ServerPlayer caster) {
        return RaycastHelper.findLivingTarget(level, caster, DEFAULT_OBLIVION_RANGE, true, true);
    }

    // ==================================================================
    // 施法类型
    // ==================================================================

    /**
     * 遗忘类法术**都是瞬发**（{@code docs/tech/04} §三：
     * 遗忘 / 失忆 / 遗忘诅咒 / 集体遗忘 全部是 {@code INSTANT}）。
     *
     * <p>非 final：将来若有需要起手的遗忘法术，子类覆写本方法返回 {@code LONG} 即可。
     */
    @Override
    public CastType getCastType() {
        return CastType.INSTANT;
    }

    // ==================================================================
    // 主流程
    // ==================================================================

    /** 持续时间（tick）。tier 1 → {@code oblivion.forgetSeconds}，tier 2/3 → {@code oblivion.amnesiaSeconds}。 */
    protected final int getDurationTicks() {
        final int seconds = getOblivionTier() <= 1
                ? Config.Oblivion.FORGET_SECONDS.get()
                : Config.Oblivion.AMNESIA_SECONDS.get();
        return seconds * 20;
    }

    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide && entity instanceof ServerPlayer player) {
            final LivingEntity target = resolveTarget(level, player);
            if (target != null) {
                applyTo(player, target, spellLevel);
            } else {
                // ⭐ 2026-09-17：这里原来是**静默 return** —— 玩家对着空气/超出射程施法时，
                //    法力扣了、冷却转了、屏幕上什么都没发生，看起来就像"法术坏了"。
                //    这是"很多法术释放后无效果"投诉里占比最大的一类：**不是逻辑错，是缺反馈**。
                SpellFeedback.noTarget(player);
            }
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }

    /**
     * 对单个目标施加遗忘（含 BOSS 免疫分支）。
     * 范围类法术（集体遗忘 / 遗忘诅咒）在吟唱或结算时对每个目标各调一次。
     *
     * <p>范围版可以用 {@link OblivionManager#applyOblivionArea} 一次拿到全部目标，
     * 但它内部会对每个目标再走一遍 {@code applyOblivion}（含免疫判定），
     * 与本方法等价 —— 选哪个取决于子类要不要对每个目标做额外处理。
     */
    protected final void applyTo(final ServerPlayer caster, final LivingEntity target, final int spellLevel) {
        if (!target.isAlive()) {
            return;
        }
        if (isOblivionImmune(target)) {
            // ⭐ BOSS 免疫分支原本也是静默的：玩家看到"放了但没效果"，
            //    以为法术失效。现在明确告诉玩家是免疫，不是 bug。
            SpellFeedback.immune(caster);
            applyBossFallback(caster, target, spellLevel);
            return;
        }
        applyOblivion(caster, target, spellLevel);
        // 命中表现：让玩家看到"记忆被抽走了"
        SpellFeedback.hitBurst(caster.level(), target, SpellFeedback.MEMORY_MAGENTA);
    }
}
