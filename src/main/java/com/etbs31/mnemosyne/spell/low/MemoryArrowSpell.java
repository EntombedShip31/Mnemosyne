package com.etbs31.mnemosyne.spell.low;

import com.etbs31.mnemosyne.Config;
import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.entity.MemoryArrowEntity;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.MnemosyneProjectileSpell;
import com.etbs31.mnemosyne.util.SpellFeedback;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Optional;

/**
 * 忆矢 Memory Arrow —— memory_arrow。
 *
 * <p><b>归属</b>：WS-D1（本文件是 WS-A 建立的 stub，WS-D1 填实际逻辑）。
 *
 * <p><b>数值来源</b>：docs/tech/04_法术等级强度表.md §四.1 —— 数值是<b>冻结契约</b>，
 * 改数值请先改文档再改这里。构造器里那 5 个字段<b>逐字保持</b>不变。
 *
 * <p><b>基类</b>：{@link MnemosyneProjectileSpell}（WS-C 交付）。
 * 它把"基础威力 × 系数 × 施法者加成"三段拆开，{@code getProjectileDamage(level)} 只需返回
 * <b>不含施法者加成</b>的基础伤害。
 *
 * <p><b>⭐ 伤害自检</b>（docs/tech/13 §二 忆矢表的"伤害系数"列）：
 * 伤害**直接取自** {@link #DAMAGE_BY_LEVEL}（L1 = 8.0 → L10 = 12.0），
 * {@code baseSpellPower = 1} / {@code spellPowerPerLevel = 0} 使
 * {@code powerMultiplierOf} 退化成纯施法者加成倍率，不再有"系数再乘一次"的中间层。
 *
 * <p><b>投射物实体由 WS-J 提供</b>：{@code onCast} 里留 {@code TODO(WS-J)}，
 * 现在只保证"能施放、扣法力、播施法音"。
 */
public class MemoryArrowSpell extends MnemosyneProjectileSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "memory_arrow");

    /**
     * 「认知过载」状态效果的 id。
     *
     * <p>⚠️ 该效果由 <b>WS-E</b> 的 {@code registry/ModEffects.java} 注册，本文件**不依赖那个类**
     * （否则 WS-D1 会被 WS-E 卡住）。这里只按 id 去注册表里查，查不到就静默跳过 ——
     * WS-E 一交付，本法术的叠层立刻自动生效，不需要改这里一行代码。
     */
    private static final ResourceLocation COGNITIVE_OVERLOAD_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "cognitive_overload");

    /** 认知过载每层持续 10 秒（docs/tech/04 §四.1，固定值，不随等级变）。 */
    private static final int OVERLOAD_DURATION_TICKS = 10 * 20;

    /**
     * 各等级的基础伤害（index = level - 1），13 号表：8.0 → 12.0。
     *
     * <p>⭐ <b>2026-09-18 伤害基准重定义</b>（依据 {@code docs/tech/13_数值总表.md} §二 忆矢表）：
     * 原来是 {@code baseSpellPower = 8 / spellPowerPerLevel = 1} 配标量系数 {@code 0.5}，
     * 得出「L1 = 4.0、L5 = 6.0」；而 13 号表写的是 <b>L1 = 8.0 → L10 = 12.0</b> ——
     * 那一列是<b>最终基础伤害本身</b>，不是再乘一次的系数。
     *
     * <p>因此 {@code baseSpellPower} 归 1、{@code spellPowerPerLevel} 归 0：
     * {@code basePowerOf} 恒为 1，基类的 {@code powerMultiplierOf} 退化成
     * <b>纯施法者加成倍率</b>，本数组直接就是伤害数值。
     */
    private static final float[] DAMAGE_BY_LEVEL =
            {8.0F, 8.4F, 8.9F, 9.3F, 9.8F, 10.2F, 10.7F, 11.1F, 11.6F, 12.0F};

    public MemoryArrowSpell() {
        // 只把 DefaultConfig 交给基类；下面几个数值字段取自 docs/tech/13 §二，逐字不动。
        super(memoryConfig(SpellRarity.COMMON, 2.0D, 10));
        this.baseManaCost = 11;
        this.manaCostPerLevel = 2;
        // ⭐ 归 1/0 —— 伤害改由 DAMAGE_BY_LEVEL 直接给出（见其注释）
        this.baseSpellPower = 1;
        this.spellPowerPerLevel = 0;
        this.castTime = 0;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return SPELL_ID;
    }

    /** docs/tech/04 §三 总表：忆矢是 INSTANT（此时 castTime 字段被 ISS 强制忽略）。 */
    @Override
    public CastType getCastType() {
        return CastType.INSTANT;
    }

    /** 施法音效：{@code spell.memory_arrow.cast}（docs/tech/08 §3.2）。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_MEMORY_ARROW_CAST.get());
    }

    /**
     * {@inheritDoc}
     *
     * <p>⚠️ 这里返回的是**不含施法者加成**的基础伤害：{@code basePowerOf(level) × 0.5}。
     * 施法者加成由基类的 {@code computeFinalDamage} 乘上去 —— <b>不要再乘一次
     * {@code powerOf(...)}</b>，那会把 {@code baseSpellPower} 算两次。
     */
    @Override
    protected float getProjectileDamage(final int spellLevel) {
        return DAMAGE_BY_LEVEL[clampLevelIndex(spellLevel)];
    }

    /**
     * 等级 → 数组下标，并钳进 {@link #DAMAGE_BY_LEVEL} 的长度内。
     *
     * <p>⭐ 与全流派写法一致：上界取<b>数组长度</b>而不是写死 10，
     * 这样将来再调等级上限时，数组和 maxLevel 一起改即可，不会出现越界或漏档。
     */
    private static int clampLevelIndex(final int spellLevel) {
        return Math.max(1, Math.min(DAMAGE_BY_LEVEL.length, spellLevel)) - 1;
    }

    /**
     * 施放忆矢：生成投射物实体。
     *
     * <p><b>⭐ 2026-09-17：这里原先是一个 {@code TODO(WS-J)}，什么都不做</b> ——
     * 玩家放忆矢时法力扣了、冷却转了，但屏幕上没有箭、没有伤害、没有音效。
     * 这是"很多法术释放后无效果"里最严重的一条（**完全没实现**，不是逻辑错）。
     * 现在补齐：生成 {@link MemoryArrowEntity} 并朝视线方向射出。
     *
     * <p>⚠️ 伤害**不在这里**算，而在投射物的命中回调里（见 {@link #resolveHit}）。
     * 在这里打伤害会造成"还没飞出去就掉血"，而且无法命中判定。
     */
    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide) {
            final MemoryArrowEntity arrow = new MemoryArrowEntity(level, entity, spellLevel);
            level.addFreshEntity(arrow);
            // 施法点也要有表现，否则近距离施法看起来"什么都没发生"
            SpellFeedback.castBurst(level, entity, SpellFeedback.MEMORY_INDIGO);
            // 2026-09-18：再补一条**沿视线方向**的短光带 —— 球状爆发只说"我放了法术"，
            // 定向光带才说得出"箭往哪飞"（见 SpellFeedback.muzzleBurst 的说明）。
            SpellFeedback.muzzleBurst(level, entity);
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }

    /**
     * 投射物命中时的统一入口 —— <b>由 {@link MemoryArrowEntity} 在命中回调里调用</b>。
     *
     * <p>为什么绕一层：投射物实体拿到的是"法术实例"（{@code SpellRegistry.getSpell}），
     * 不是 {@link MemoryArrowSpell} 的具体引用，所以由本类提供一个静态桥，
     * 把调用转发给基类的 {@link MnemosyneProjectileSpell#onProjectileHit} ——
     * 伤害公式（{@code basePowerOf × 系数 × 施法者加成}）与叠层逻辑**只存在一份**。
     *
     * @return 目标是否真的受到了伤害
     */
    public static boolean resolveHit(final Level level, final LivingEntity caster,
                                     final Entity projectile, final LivingEntity target,
                                     final int spellLevel) {
        final AbstractSpell spell = SpellRegistry.getSpell(SPELL_ID);
        if (spell instanceof MnemosyneProjectileSpell projectileSpell) {
            return projectileSpell.onProjectileHit(level, caster, projectile, target, spellLevel);
        }
        return false;
    }

    /**
     * 命中后的额外效果：叠 1 层认知过载。
     *
     * <p>层数上限取自 docs/tech/04 §四.1 的"认知过载层数上限"列：1~3 级 5 层，4~5 级 6 层。
     *
     * <p><b>2026-09-18：这里原先还播了一遍命中音效，已删除。</b>
     * 投射物基类 {@code AbstractMagicProjectile.onHit()} 会调
     * {@code getImpactSound()}（我们覆写成同一个 {@code spell.memory_arrow.hit}），
     * 所以实体命中原本是**同一个音效叠两遍**。命中音效现在只有基类一处。
     */
    @Override
    protected void onProjectileHitExtra(final Level level, final LivingEntity caster,
                                        final Entity projectile, final LivingEntity target,
                                        final int spellLevel) {
        stackCognitiveOverload(target, spellLevel <= 3 ? 5 : 6);
    }

    // ==================================================================
    // 认知过载（忆矢与窥忆共用的叠层入口）
    // ==================================================================

    /**
     * 给目标叠 1 层「认知过载」。
     *
     * <p><b>为什么这个方法在这里而不是基类里</b>：{@code spell/base/**} 归 WS-C 且已冻结，
     * 而本工作流只拥有 {@code spell/low/} 下的 6 个文件、又不允许新建"公共工具文件"
     * （{@code docs/tech/10} §九 反模式："让 WS-D1/D2/D3 共用一个文件放公共逻辑"）。
     * 所以把「叠层」这件 WS-D1 内部共享的小事放在**忆矢**（叠层的原型法术）里。
     * 这是刻意的取舍，不是疏漏。
     *
     * <p>⚠️ 2026-09-17 由 {@code package-private} 改为 {@code public}：
     * 「忆海」（{@code spell/high/SeaOfMemorySpell}）领域内要每秒给敌人叠层，
     * 而它在**另一个包**里。放宽可见性比复制一份叠层逻辑安全得多
     * （复制会让"层数上限只挡往上叠、不压已有层数"这条规则在两处漂移）。
     *
     * <p>⚠️ 效果本身（"每层使目标受到的记忆法术伤害 +5%"）由 WS-E 的伤害事件处理，
     * 本方法只负责叠层与刷新时长。
     *
     * @param capLayers 层数上限；已经超过上限时**不降级**，只刷新持续时间
     */
    public static void stackCognitiveOverload(final LivingEntity target, final int capLayers) {
        // 普通战斗：上限再受 overload.maxStacksInCombat 钳制（设计文档 v2 §9.2）。
        // 传进来的 capLayers 是"这个法术自己允许的上限"，配置项是"全局天花板"，
        // 两者取小 —— 这样调配置就能整体压低/放开层数，不用改每个法术。
        stack(target, Math.min(capLayers, Config.Overload.MAX_STACKS_IN_COMBAT.get()));
    }

    /**
     * 「忆海」领域内的叠层 —— 上限用 {@code overload.maxStacksInSea}（默认 15）。
     *
     * <p>为什么单独开一个方法而不是复用上面那个：领域的层数上限（8~15）**远高于**
     * 普通战斗上限（6）。如果复用，上面那句 {@code min(..., MAX_STACKS_IN_COMBAT)}
     * 会把领域的上限一起压到 6，领域技的核心价值（"唯一能突破 6 层的途径"）就没了。
     */
    public static void stackCognitiveOverloadInSea(final LivingEntity target, final int capLayers) {
        stack(target, Math.min(capLayers, Config.Overload.MAX_STACKS_IN_SEA.get()));
    }

    /** 真正干活的：叠一层，受 capLayers 限制。 */
    private static void stack(final LivingEntity target, final int capLayers) {
        // 效果对玩家无效（docs/tech/04 §5.3 反作弊：认知类效果不该作用在玩家身上）
        if (target instanceof Player || !target.isAlive()) {
            return;
        }
        final MobEffect overload = ForgeRegistries.MOB_EFFECTS.getValue(COGNITIVE_OVERLOAD_ID);
        if (overload == null) {
            // WS-E 的 ModEffects 还没注册这个效果 —— 不是错误，静默跳过。
            return;
        }
        final MobEffectInstance current = target.getEffect(overload);
        final int currentLayers = current == null ? 0 : current.getAmplifier() + 1;
        // 上限只挡"继续往上叠"，不把已有层数压下来（否则低上限的法术会削掉高上限法术的成果）
        final int layers = Math.min(currentLayers + 1, Math.max(capLayers, currentLayers));
        target.addEffect(new MobEffectInstance(overload, OVERLOAD_DURATION_TICKS, layers - 1,
                false, true, true));
    }
}
