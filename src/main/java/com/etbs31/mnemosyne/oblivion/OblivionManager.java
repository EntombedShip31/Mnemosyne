package com.etbs31.mnemosyne.oblivion;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.capability.EngramEntry;
import com.etbs31.mnemosyne.capability.MnemosyneData;
import com.etbs31.mnemosyne.registry.ModEffects;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 遗忘系统的执行核心 —— 让敌人忘记怎么战斗。
 *
 * <p><b>文件归属</b>：WS-E 遗忘系统。本类同时是 {@code oblivion/**} 唯一的
 * Forge 事件订阅者（重载 / 效果到期 / 实体加载 / 死亡 / tick 都由这里统一驱动）。
 *
 * <p><b>冻结契约</b>（{@code docs/tech/12} 提示词 A，WS-D 依赖，不可改）：
 * <pre>{@code
 * OblivionManager.applyOblivion(ServerPlayer, LivingEntity, int tier)      : boolean
 * OblivionManager.restoreAll(LivingEntity)                                 : void
 * OblivionManager.stealTrait(ServerPlayer, LivingEntity, ResourceLocation) : boolean
 * }</pre>
 *
 * <p><b>⭐ 本工作流最大的实测结论：不需要 Mixin，也不需要反射</b>
 * <br>{@code docs/tech/03} §7.2 原文写"{@code goalSelector} 是 protected，
 * 需要 Accessor Mixin 或反射"。**已被实测推翻**：Forge 的 AccessTransformer
 * 把 {@code Mob.goalSelector} / {@code targetSelector} 提升成了
 * {@code public final GoalSelector}（读 official 映射源码确认），
 * {@code removeGoal} / {@code addGoal} / {@code getAvailableGoals} 全部直接可调。
 * 所以 {@code docs/tech/03} §7.3 的"方案 B（Mixin 拦截 canUse）"整段作废，
 * 统一用"移除 + 记录 + 到期恢复"。
 *
 * <p><b>⭐ 第二个实测结论：{@code getAvailableGoals()} 返回的是内部活集合</b>
 * <br>源码：{@code return this.availableGoals;} —— 没有做防御性拷贝。
 * 边遍历边 {@code removeGoal} 会抛 {@code ConcurrentModificationException}。
 * 所有遍历都走 {@link AbilityMap#snapshot(Mob)} 拿副本。
 *
 * <p><b>⭐ 第三个实测结论：记录放不进 MobEffectInstance</b>
 * <br>提示词 A 要求"把被移除 goal 的记录写进自定义 MobEffect 的 NBT"。实测不可行：
 * {@code MobEffectInstance} 在 1.20.1 **没有任何自定义 NBT 字段**，
 * 而且 {@code Goal} 是对象、不可序列化。实际分工：
 * <ul>
 *   <li><b>活的 Goal 实例</b> → {@link #ACTIVE} 运行时表 → 到期**原样**装回去，
 *       行为内部状态不丢（这是"移除+恢复"方案能干净工作的关键）。</li>
 *   <li><b>实体 persistentData</b> → 只存 {@code tier} + {@code expire} 两个标量，
 *       用于实体重载后重新施加。</li>
 * </ul>
 *
 * <p><b>⭐ 为什么这样反而更稳（残留问题自动消失）</b>
 * <br>生物的 goal 是在构造函数里 {@code registerGoals()} 注册的，
 * 所以**实体一旦被重新构造（区块卸载 / 服务器重启 / 存档重载），全部行为都会自动重建**。
 * 于是：
 * <ul>
 *   <li>不存在"goal 被永久删掉、存档里留下残废生物" —— 重载即痊愈；
 *   <li>重启后 {@link #onEntityJoin} 发现实体身上还有遗忘效果，就**按记录的 tier 重新移除一遍**，
 *       状态与效果时长保持一致；
 *   <li>效果已经过期而实体才被加载 → 直接清掉残留标记，不报错、不留痕。
 * </ul>
 * 验收标准 5（"服务器重启后没有残留的被永久削弱的生物"）就是靠这个机制达成的。
 *
 * <p><b>通用降级（{@code docs/tech/03} §7.4 的 5 条，必须实现）</b>：
 * 没有可移除行为的生物（纯被动生物、其他模组生物、非 Mob 的 LivingEntity）走降级：
 * <ul>
 *   <li>遗忘 → 受伤 +10%，持续 6 秒（{@link #applyDegradation}）</li>
 *   <li>失忆 → 停止攻击 1.5 秒（{@link #applyDegradation}）</li>
 *   <li>集体遗忘 → 移速 -20%，持续 6 秒（{@link #applyDegradation}）</li>
 *   <li>写入 · 质忆 → 写"通用记忆"，释放时 +10% 移速 30 秒（{@link #writeGenericMemory}
 *       ＋ {@link TraitRegistry#GENERIC}）</li>
 *   <li>走马灯 → 复现失败返还 50% 法力（数值常量在 {@link MimicRegistry#MANA_REFUND_FRACTION}，
 *       **退款动作由 WS-D2 的 {@code RecollectionSpell} 执行** —— 那是 ISS 的 {@code MagicData} API，
 *       不属于遗忘系统的职责）</li>
 * </ul>
 * 降级**必须存在**，否则装了其他模组的玩家会发现记忆流派完全失效。
 * 前 4 条在本工作流内闭合；第 5 条只提供常量，因为"复现"这个动作本身是法术的行为。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class OblivionManager {

    private OblivionManager() {}

    /** 实体 persistentData 上的标记根键（只存 tier / expire，见类注释）。 */
    public static final String NBT_MARKER = "mnemosyne_oblivion";
    private static final String KEY_TIER = "tier";
    private static final String KEY_EXPIRE = "expire";

    // ------------------------------------------------------------------
    // 通用降级数值（docs/tech/03 §7.4 / docs/02 §六）
    // ------------------------------------------------------------------
    private static final float DEGRADE_VULNERABILITY = 0.10F;   // 受伤 +10%
    private static final float DEGRADE_SLOW = 0.20F;            // 移速 -20%
    private static final int DEGRADE_NO_ATTACK_TICKS = 30;      // 停止攻击 1.5 秒

    /** 降级减速的固定 UUID（重复施加先移除旧的）。 */
    private static final UUID DEGRADE_SLOW_ID = UUID.fromString("2b8d0f51-4c63-4e70-9f2a-5d6e7f809102");

    // ==================================================================
    // 运行时状态
    // ==================================================================

    /** 被移除的 goal。必须成对记住"哪个选择器"，因为两个 selector 是独立对象。 */
    private record RemovedGoal(GoalSelector selector, Goal goal, int priority) {}

    /** 一次遗忘的完整记录。 */
    private static final class Active {

        private final Mob mob;
        /**
         * 当前记录的层级。
         *
         * <p>⚠️ **非 final**：重复施加时取"更强"的一方（{@code AMNESIA} > {@code FORGET}），
         * 这样先放「遗忘」再放「失忆」不会让后者降级成前者。
         */
        private OblivionTier tier;
        /** 到期刻。重复施加取更晚的一方 —— 后续施加会**延长**而不是缩短已有的遗忘。 */
        private long expireTick;
        private final List<RemovedGoal> removed = new ArrayList<>();

        private Active(final Mob mob, final OblivionTier tier, final long expireTick) {
            this.mob = mob;
            this.tier = tier;
            this.expireTick = expireTick;
        }

        /** 合并一次新的施加：层级取更强、到期取更晚。 */
        private void merge(final OblivionTier newTier, final long newExpireTick) {
            if (newTier.id() > this.tier.id()) {
                this.tier = newTier;
            }
            this.expireTick = Math.max(this.expireTick, newExpireTick);
        }

        /** 该 goal 是否已经在本记录里（避免同一个 goal 被摘两次、恢复两次）。 */
        private boolean alreadyRemoved(final Goal goal) {
            for (final RemovedGoal entry : this.removed) {
                if (entry.goal() == goal) {
                    return true;
                }
            }
            return false;
        }
    }

    /** key = 生物 UUID。到期或生物消失时移除，不会长期持有实体引用。 */
    private static final Map<UUID, Active> ACTIVE = new ConcurrentHashMap<>();

    /** 通用降级的计时状态。 */
    private static final class Debuff {

        private final LivingEntity entity;
        private float vulnerability;
        private long vulnerabilityUntil;
        private long noAttackUntil;
        private long slowUntil;

        private Debuff(final LivingEntity entity) {
            this.entity = entity;
        }
    }

    private static final Map<UUID, Debuff> DEBUFFS = new ConcurrentHashMap<>();

    // ==================================================================
    // 冻结契约
    // ==================================================================

    /**
     * 对一个目标施加遗忘。
     *
     * <p>调用顺序（每一道都不能省）：
     * <ol>
     *   <li>玩家 → 直接无效（{@code docs/02} §三 规则一：保护 PVP 体验）</li>
     *   <li>BOSS → 完全免疫，走 {@link BossImmunity#applyFallback}（数值化削弱）</li>
     *   <li>非 Mob → 通用降级</li>
     *   <li>没有可移除行为 → 通用降级</li>
     *   <li>否则移除行为 + 挂状态效果 + 记录</li>
     * </ol>
     *
     * @param tier 1 = 遗忘（随机一个），2 = 失忆（全部），3 = 集体遗忘
     * @return 是否**真的剥夺了什么**（走了降级或免疫路径时返回 {@code false}，
     *         调用方可以据此决定是否播"没有效果"的反馈音）
     */
    public static boolean applyOblivion(final ServerPlayer caster, final LivingEntity target,
                                       final int tier) {
        return applyOblivion(caster, target, tier, -1);
    }

    /**
     * 带**显式时长**的施加遗忘。
     *
     * <p>为什么需要这个重载：{@code docs/tech/04} §四.3 的「遗忘」持续时间是
     * <b>按等级递增</b>的（6/7/8/9/10 秒），而 {@link OblivionTier#durationTicks()}
     * 只能读一个固定配置值（{@code oblivion.forgetSeconds}）。
     * 三参版是**冻结契约**（{@code docs/tech/12} 提示词 A），不能改签名，
     * 所以新增本重载、让三参版委托过来（传 {@code -1} = 用层级默认值）。
     *
     * <p>同样地，{@code 遗忘诅咒} 的"每 2 秒遗忘一个、最多 3 个"也依赖这个重载 ——
     * 它需要在领域剩余时间内反复施加（见 {@link #removeAbilities} 的合并逻辑）。
     *
     * @param durationTicks 显式持续时长；{@code <= 0} 表示用 {@link OblivionTier#durationTicks()}
     */
    public static boolean applyOblivion(final ServerPlayer caster, final LivingEntity target,
                                       final int tier, final int durationTicks) {
        if (target == null || !target.isAlive() || target.level().isClientSide) {
            return false;
        }
        // 规则一：遗忘类法术对玩家完全无效
        if (target instanceof Player) {
            return false;
        }
        final OblivionTier parsed = OblivionTier.byId(tier);

        // 规则二：BOSS 完全免疫剥夺 → 数值化削弱
        if (BossImmunity.isImmune(target)) {
            BossImmunity.applyFallback(caster, target, parsed);
            return false;
        }

        if (!(target instanceof Mob mob)) {
            return applyDegradation(target, parsed);
        }
        final int duration = durationTicks > 0 ? durationTicks : scaledDuration(target, parsed);
        return removeAbilities(mob, parsed, duration);
    }

    /**
     * 范围版：对以 {@code center} 为球心、给定半径内的所有敌对生物逐个施加遗忘。
     *
     * <p>三参版用层级自带的半径（{@link OblivionTier#radius()}，
     * 集体遗忘 = 8 格）；四参版允许法术按**等级**指定半径
     * （{@code docs/tech/04} §四.15：集体遗忘的半径 8/8/9/10/12 格）。
     *
     * @return 实际受影响的目标数
     */
    public static int applyOblivionArea(final ServerPlayer caster, final Entity center, final int tier) {
        return applyOblivionArea(caster, center, tier, -1.0D, -1);
    }

    /** {@link #applyOblivionArea(ServerPlayer, Entity, int)} 的"按等级指定半径与时长"版。 */
    public static int applyOblivionArea(final ServerPlayer caster, final Entity center, final int tier,
                                       final double radius, final int durationTicks) {
        if (center == null) {
            return 0;
        }
        final OblivionTier parsed = OblivionTier.byId(tier);
        final double effective = radius > 0.0D
                ? radius
                : (parsed.radius() > 0.0D ? parsed.radius() : OblivionTier.MASS_RADIUS);
        int affected = 0;
        for (final LivingEntity living : center.level().getEntitiesOfClass(LivingEntity.class,
                center.getBoundingBox().inflate(effective))) {
            if (living == caster || living instanceof Player) {
                continue;
            }
            if (living.distanceToSqr(center) > effective * effective) {
                continue;
            }
            if (applyOblivion(caster, living, tier, durationTicks)) {
                affected++;
            }
        }
        return affected;
    }

    /**
     * 立刻恢复一个目标的全部被移除行为，并清掉所有相关状态。
     *
     * <p>用途：管理员指令、调试、以及"反制法术打断"这类需求。
     * 正常流程下不需要调用它 —— 状态效果到期会自动走同一条恢复路径。
     */
    public static void restoreAll(final LivingEntity target) {
        if (target == null) {
            return;
        }
        final Active active = ACTIVE.remove(target.getUUID());
        if (active != null) {
            restore(active);
        }
        DEBUFFS.remove(target.getUUID());
        BossImmunity.forget(target);
        removeOblivionEffects(target);
        clearMarker(target);
    }

    /**
     * 窃取特性（质忆 / 记忆掠夺的写入侧）。
     *
     * <p>写入 {@code permSlots}（**不腐坏**）—— {@code docs/tech/03} §6.2 规定
     * {@code permSlots} 就是"记忆掠夺写的"。写入前逐条检查：
     * 目标活着 → 目标不是 BOSS → 目标确实拥有该特性 → 玩家有空忆格。
     *
     * @return 是否成功写入
     */
    public static boolean stealTrait(final ServerPlayer caster, final LivingEntity target,
                                     final ResourceLocation traitId) {
        return stealTrait(caster, target, traitId, -1);
    }

    /**
     * 窃取特性（可指定生效时长）。
     *
     * <p>为什么需要显式时长：{@code docs/tech/04} §四.14 规定
     * 「记忆掠夺」在 1~2 级时对**精英怪**的窃取效果**减半**。
     * 三参版是冻结契约，不能改签名，所以新增本重载。
     *
     * @param durationTicks 显式生效时长（tick）；{@code <= 0} 表示用
     *                      {@link TraitRegistry#traitDurationTicks(ResourceLocation)}
     */
    public static boolean stealTrait(final ServerPlayer caster, final LivingEntity target,
                                     final ResourceLocation traitId, final int durationTicks) {
        if (caster == null || target == null || traitId == null) {
            return false;
        }
        if (!target.isAlive() || target.level().isClientSide) {
            return false;
        }
        if (BossImmunity.isImmune(target)) {
            BossImmunity.applyFallback(caster, target, OblivionTier.FORGET);
            return false;
        }
        if (!TraitRegistry.hasTrait(target.getType(), traitId)) {
            return false;
        }
        if (!MnemosyneData.hasFreeSlot(caster)) {
            return false;
        }
        final int duration = durationTicks > 0 ? durationTicks : TraitRegistry.traitDurationTicks(traitId);
        final EngramEntry entry = new EngramEntry.EssenceMemory(traitId, duration,
                nowTick(caster) + duration);
        return MnemosyneData.addEngram(caster, entry, true);
    }

    /**
     * 「写入 · 质忆」的**降级路径** —— {@code docs/tech/03} §7.4 表格第 4 行。
     *
     * <p>目标没有任何可窃取特性时（纯被动生物 / 其他模组生物），
     * {@link #stealTrait} 会返回 {@code false}；调用方接着调本方法写一条
     * **通用记忆**（{@link TraitRegistry#GENERIC}），释放时给 +10% 移速 30 秒。
     *
     * <p>这样"质忆"在装了任何模组的环境下都不会变成废牌 —— 这是 §7.4
     * "降级必须存在"的目的。
     *
     * <p><b>写入的是 {@code permSlots}</b>（与 {@code stealTrait} 一致）：
     * {@code docs/tech/03} §6.2 规定 {@code permSlots} 就是"记忆掠夺写的"。
     *
     * @return 是否成功写入（忆格满 → {@code false}，调用方应退还法力）
     */
    public static boolean writeGenericMemory(final ServerPlayer caster) {
        if (caster == null || !MnemosyneData.hasFreeSlot(caster)) {
            return false;
        }
        final int duration = TraitRegistry.DEFAULT_DURATION_TICKS;
        final EngramEntry entry = new EngramEntry.EssenceMemory(TraitRegistry.GENERIC, duration,
                nowTick(caster) + duration);
        return MnemosyneData.addEngram(caster, entry, true);
    }

    // ==================================================================
    // 内部：移除 / 恢复
    // ==================================================================

    /**
     * 把该生物身上属于 {@code chosen} 的能力对应 goal 全部摘下来。
     *
     * <p><b>⭐⭐ 2026-09-17 修正：重复施加必须**合并**而不是覆盖</b>
     * <br>原实现每次都是 {@code ACTIVE.put(uuid, new Active(...))}。这有两个后果，
     * 而且都不会报错：
     * <ol>
     *   <li><b>「遗忘」4~5 级要求一次摘 2 个行为</b>（{@code docs/tech/04} §四.3）→
     *       调两次 {@code applyOblivion} 时，第二次的记录覆盖第一次，
     *       于是第一次摘掉的那个 goal **永远不会被装回来** ——
     *       生物在本次生命周期内永久残废（重载区块才痊愈）。</li>
     *   <li><b>「遗忘诅咒」的设计就是反复施加</b> ——
     *       {@link OblivionTier} 的类注释明确要求"每 2 秒调一次 tier 1"。
     *       按原实现，领域每跳一次就丢一份恢复记录，
     *       8 秒的领域能让敌人永久失去 4 个能力。</li>
     * </ol>
     * 现在改成"已存在记录 → 追加并延长"，{@link Active#alreadyRemoved} 防止同一个 goal
     * 被摘两次（那会导致恢复时重复 addGoal）。
     *
     * @return 是否真的摘掉了东西（本次摘的 + 之前已摘的都算）
     */
    private static boolean removeAbilities(final Mob mob, final OblivionTier tier,
                                          final int durationTicks) {
        final List<ResourceLocation> present = AbilityMap.presentAbilities(mob);
        if (present.isEmpty()) {
            return applyDegradation(mob, tier);
        }

        // tier 1「遗忘」是"随机挑一个它确实拥有的行为"（docs/02 §2.1），
        // 因为 present 是从实例反推的，这里不需要"重新随机直到命中"的循环 —— 已经全是命中的。
        final List<ResourceLocation> chosen = tier.picksRandomAbility()
                ? List.of(present.get(mob.getRandom().nextInt(present.size())))
                : present;

        final long expireTick = nowTick(mob) + durationTicks;
        final Active existing = ACTIVE.get(mob.getUUID());
        final Active active;
        if (existing != null && existing.mob == mob) {
            active = existing;
            active.merge(tier, expireTick);
        } else {
            active = new Active(mob, tier, expireTick);
        }

        for (final ResourceLocation ability : chosen) {
            for (final AbilityMap.GoalEntry entry : AbilityMap.goalsFor(mob, ability)) {
                final Goal goal = entry.getGoal();
                if (active.alreadyRemoved(goal)) {
                    continue;
                }
                active.removed.add(new RemovedGoal(entry.selector(), goal, entry.getPriority()));
                // 先 stop 再 remove：否则"已移除但仍在运行"的 goal 会继续影响这一 tick 的行为
                entry.wrapped().stop();
                entry.selector().removeGoal(goal);
            }
        }
        if (active.removed.isEmpty()) {
            // 走到这里说明 goalsFor 对所有 chosen 都返回了空，且没有历史记录 —— 什么都没动过
            return applyDegradation(mob, tier);
        }

        ACTIVE.put(mob.getUUID(), active);
        writeMarker(mob, active.tier, active.expireTick);
        if (active.tier.clearsTarget()) {
            mob.setTarget(null);
        }
        final MobEffect effect = effectFor(active.tier);
        if (effect != null) {
            // 用"记录里剩余的时长"而不是本次传入的时长 ——
            // 合并后记录可能比本次施加活得更久，效果必须跟着记录走，否则会提前消失、
            // 导致"效果没了但 goal 还没装回来"（那条恢复路径由效果到期驱动）。
            final int remaining = (int) Math.max(1L, active.expireTick - nowTick(mob));
            mob.addEffect(new MobEffectInstance(effect, remaining, 0, false, true, true));
        }
        MnemosyneMod.LOGGER.debug("[WS-E] {} 被施加「{}」：本次后共移除 {} 个 AI 行为（{} tick）",
                mob.getName().getString(), active.tier.key(), active.removed.size(), durationTicks);
        return true;
    }

    /**
     * 恢复被移除的行为。
     *
     * <p>风险处理（{@code docs/tech/03} §7.3 列出的三条）：
     * <ol>
     *   <li>生物已死 / 已卸载 → 直接丢弃记录（重载会重建全部行为，不会留残废生物）</li>
     *   <li>期间已经有同实例的 goal 被加回来 → 不重复添加</li>
     *   <li>行为内部状态：因为我们持有的是**原实例**，状态本来就没被重置 ——
     *       这是"运行时表存实例"相对于"存类名再反射重建"的核心优势</li>
     * </ol>
     */
    private static void restore(final Active active) {
        final Mob mob = active.mob;
        if (!mob.isRemoved() && mob.isAlive()) {
            for (final RemovedGoal removed : active.removed) {
                if (alreadyPresent(removed)) {
                    continue;
                }
                removed.selector().addGoal(removed.priority(), removed.goal());
            }
            MnemosyneMod.LOGGER.debug("[WS-E] {} 的「{}」已结束，恢复 {} 个 AI 行为",
                    mob.getName().getString(), active.tier.key(), active.removed.size());
        }
        clearMarker(mob);
    }

    private static boolean alreadyPresent(final RemovedGoal removed) {
        for (final WrappedGoal wrapped : removed.selector().getAvailableGoals()) {
            if (wrapped.getGoal() == removed.goal()) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 内部：通用降级
    // ==================================================================

    private static boolean applyDegradation(final LivingEntity target, final OblivionTier tier) {
        final long now = nowTick(target);
        final Debuff debuff = DEBUFFS.computeIfAbsent(target.getUUID(), id -> new Debuff(target));
        switch (tier) {
            case FORGET -> {
                debuff.vulnerability = DEGRADE_VULNERABILITY;
                debuff.vulnerabilityUntil = now + tier.durationTicks();
            }
            case AMNESIA -> {
                debuff.noAttackUntil = now + DEGRADE_NO_ATTACK_TICKS;
                if (target instanceof Mob mob) {
                    mob.setTarget(null);
                    mob.getNavigation().stop();
                }
            }
            case MASS_AMNESIA -> {
                debuff.slowUntil = now + tier.durationTicks();
                applySlowModifier(target);
            }
        }
        MnemosyneMod.LOGGER.debug("[WS-E] {} 没有可移除的行为，走通用降级「{}」",
                target.getName().getString(), tier.key());
        return true;
    }

    private static void applySlowModifier(final LivingEntity target) {
        final AttributeInstance speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        speed.removeModifier(DEGRADE_SLOW_ID);
        speed.addTransientModifier(new AttributeModifier(DEGRADE_SLOW_ID,
                "mnemosyne:oblivion_degrade_slow", -DEGRADE_SLOW,
                AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    private static void removeSlowModifier(final LivingEntity target) {
        final AttributeInstance speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(DEGRADE_SLOW_ID);
        }
    }

    // ==================================================================
    // 内部：杂项
    // ==================================================================

    /** 精英怪的持续时间减半（{@code docs/02} §三 规则五）。 */
    private static int scaledDuration(final LivingEntity target, final OblivionTier tier) {
        final int base = tier.durationTicks();
        return AbilityMap.isElite(target.getType()) ? Math.max(1, base / 2) : base;
    }

    @Nullable
    private static MobEffect effectFor(final OblivionTier tier) {
        return tier.effectMode() == OblivionEffect.Mode.FORGET ? ModEffects.forget() : ModEffects.amnesia();
    }

    private static void removeOblivionEffects(final LivingEntity entity) {
        final MobEffect forget = ModEffects.forget();
        if (forget != null) {
            entity.removeEffect(forget);
        }
        final MobEffect amnesia = ModEffects.amnesia();
        if (amnesia != null) {
            entity.removeEffect(amnesia);
        }
    }

    private static CompoundTag marker(final LivingEntity entity) {
        return entity.getPersistentData().getCompound(NBT_MARKER);
    }

    private static void writeMarker(final Mob mob, final OblivionTier tier, final long expireTick) {
        final CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_TIER, tier.id());
        tag.putLong(KEY_EXPIRE, expireTick);
        mob.getPersistentData().put(NBT_MARKER, tag);
    }

    private static void clearMarker(final LivingEntity entity) {
        entity.getPersistentData().remove(NBT_MARKER);
    }

    /**
     * 当前世界刻 —— 与 WS-B（{@code MnemosyneData.nowTick}）**同一套约定**：
     * 统一取**主世界**的 {@code getGameTime()}。
     *
     * <p>不用 {@code entity.level().getGameTime()}：各维度有各自的计数器，
     * 目标跨维度时数字会跳变，导致状态瞬间过期或永不过期。
     */
    static long nowTick(final Entity entity) {
        if (entity.level() instanceof ServerLevel serverLevel && serverLevel.getServer() != null) {
            return serverLevel.getServer().overworld().getGameTime();
        }
        return entity.level().getGameTime();
    }

    /** 实体身上还剩多少 tick 的遗忘效果（重载后重新施加时用）。 */
    private static int remainingOblivionTicks(final LivingEntity entity) {
        for (final MobEffect effect : new MobEffect[]{ModEffects.forget(), ModEffects.amnesia()}) {
            if (effect == null) {
                continue;
            }
            final MobEffectInstance instance = entity.getEffect(effect);
            if (instance != null) {
                return instance.getDuration();
            }
        }
        return 0;
    }

    // ==================================================================
    // 事件：数据包
    // ==================================================================

    /**
     * 注册映射表重载监听器。
     *
     * <p>本工作流**没有**修改 {@code MnemosyneMod.java}（铁律：主类归 WS-0），
     * 所以监听器挂在 {@code @Mod.EventBusSubscriber} 上自动注册。
     */
    @SubscribeEvent
    public static void onAddReloadListener(final AddReloadListenerEvent event) {
        event.addListener(new MappingReloadListener());
    }

    /** 读 {@code data/mnemosyne/mnemosyne/oblivion/*.json}。 */
    private static final class MappingReloadListener extends SimpleJsonResourceReloadListener {

        private static final Gson GSON = new Gson();

        private MappingReloadListener() {
            super(GSON, "mnemosyne/oblivion");
        }

        @Override
        protected void apply(final Map<ResourceLocation, JsonElement> files,
                            final ResourceManager resourceManager, final ProfilerFiller profiler) {
            AbilityMap.acceptDataPack(files);
            TraitRegistry.acceptDataPack(files);
            MimicRegistry.acceptDataPack(files);
        }
    }

    // ==================================================================
    // 事件：状态效果
    // ==================================================================

    /**
     * 「遗忘类效果对玩家无效」的主防线。
     *
     * <p>⭐ 实测：{@code MobEffectEvent.Applicable} 在 1.20.1 是 {@code @HasResult}
     * （用 {@code setResult(Event.Result.DENY)}），**不是** {@code @Cancelable}。
     * 用错写法会编译不过 —— 或者更糟，写成 {@code setCanceled} 而静默无效。
     */
    @SubscribeEvent
    public static void onEffectApplicable(final MobEffectEvent.Applicable event) {
        if (!(event.getEffectInstance().getEffect() instanceof OblivionEffect effect)) {
            return;
        }
        if (effect.mode().blocksPlayers() && event.getEntity() instanceof Player) {
            event.setResult(Event.Result.DENY);
        }
    }

    /** 效果自然到期 → 恢复行为。 */
    @SubscribeEvent
    public static void onEffectExpired(final MobEffectEvent.Expired event) {
        onOblivionEffectGone(event.getEntity(), event.getEffectInstance());
    }

    /** 效果被提前移除（牛奶 / {@code removeEffect} / 其他模组）→ 同样恢复。 */
    @SubscribeEvent
    public static void onEffectRemoved(final MobEffectEvent.Remove event) {
        onOblivionEffectGone(event.getEntity(), event.getEffectInstance());
    }

    /**
     * 效果消失的统一处理。
     *
     * <p>幂等：{@code Expired} 与 {@code Remove} 可能对同一次到期都触发，
     * 但 {@link #ACTIVE}{@code .remove()} 只会有一次返回非 null，第二次是空操作。
     */
    private static void onOblivionEffectGone(final LivingEntity entity,
                                            @Nullable final MobEffectInstance instance) {
        if (instance == null || !(instance.getEffect() instanceof OblivionEffect effect)
                || !effect.mode().isOblivion()) {
            return;
        }
        final Active active = ACTIVE.remove(entity.getUUID());
        if (active != null) {
            restore(active);
        }
        clearMarker(entity);
    }

    // ==================================================================
    // 事件：实体生命周期
    // ==================================================================

    /**
     * 实体被加载（区块载入 / 服务器重启）。
     *
     * <p>这是"状态清理覆盖区块卸载与服务器重启"（提示词 A 要求 7）的关键一环。
     * {@code loadedFromDisk()} 区分"从存档读出来的"与"新生成的" ——
     * 新生成的生物没有历史包袱，不必处理。
     *
     * <p>因为重载必然重新跑过 {@code registerGoals()}：
     * <ul>
     *   <li>旧的运行时记录必然失效 → 丢弃；</li>
     *   <li>身上还有遗忘效果 → 按记录的 tier **重新移除一遍**，保持一致；</li>
     *   <li>效果已过期 → 清掉残留标记。</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onEntityJoin(final EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !event.loadedFromDisk()) {
            return;
        }
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        ACTIVE.remove(mob.getUUID());
        if (marker(mob).isEmpty()) {
            return;
        }
        final int remaining = remainingOblivionTicks(mob);
        if (remaining <= 0) {
            clearMarker(mob);
            return;
        }
        final OblivionTier tier = OblivionTier.byId(marker(mob).getInt(KEY_TIER));
        removeAbilities(mob, tier, remaining);
    }

    /** 生物死亡 → 丢弃全部状态（重载会重建行为，不会留下残废生物）。 */
    @SubscribeEvent
    public static void onLivingDeath(final LivingDeathEvent event) {
        final LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) {
            return;
        }
        ACTIVE.remove(entity.getUUID());
        DEBUFFS.remove(entity.getUUID());
        BossImmunity.forget(entity);
        clearMarker(entity);
    }

    // ==================================================================
    // 事件：降级伤害加成
    // ==================================================================

    /** 通用降级的"遗忘 → 受伤 +10%"。 */
    @SubscribeEvent
    public static void onLivingHurt(final LivingHurtEvent event) {
        if (event.getAmount() <= 0.0F) {
            return;
        }
        final Debuff debuff = DEBUFFS.get(event.getEntity().getUUID());
        if (debuff == null || debuff.vulnerability <= 0.0F) {
            return;
        }
        if (nowTick(event.getEntity()) >= debuff.vulnerabilityUntil) {
            return;
        }
        event.setAmount(event.getAmount() * (1.0F + debuff.vulnerability));
    }

    // ==================================================================
    // 事件：tick 驱动
    // ==================================================================

    /**
     * 服务端每 tick 的清扫。
     *
     * <p>三件事：恢复到期行为、推进降级计时、推进 BOSS 削弱计时。
     * 遍历的是**小的运行时表**（通常 0~2 条），不是全世界的实体 ——
     * 所以每 tick 跑是安全的；表为空时几乎是零开销。
     *
     * <p>{@code ACTIVE} 的恢复本来由 {@code MobEffectEvent.Expired} 驱动，
     * 这里的超时分支是**双保险**：万一某个模组把效果移除了却不发事件，
     * 行为也不会永远回不来。
     */
    @SubscribeEvent
    public static void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        final MinecraftServer server = event.getServer();
        final long now = server.overworld().getGameTime();

        if (!ACTIVE.isEmpty()) {
            for (final Iterator<Map.Entry<UUID, Active>> it = ACTIVE.entrySet().iterator(); it.hasNext(); ) {
                final Active active = it.next().getValue();
                if (active.mob.isRemoved() || !active.mob.isAlive()) {
                    // 已卸载 / 已死 → 丢弃记录。重载会重建全部行为，不需要"抢救"
                    it.remove();
                    continue;
                }
                if (now >= active.expireTick) {
                    restore(active);
                    it.remove();
                }
            }
        }

        if (!DEBUFFS.isEmpty()) {
            for (final Iterator<Map.Entry<UUID, Debuff>> it = DEBUFFS.entrySet().iterator(); it.hasNext(); ) {
                final Debuff debuff = it.next().getValue();
                final LivingEntity entity = debuff.entity;
                if (entity.isRemoved() || !entity.isAlive()) {
                    it.remove();
                    continue;
                }
                if (now < debuff.noAttackUntil && entity instanceof Mob mob) {
                    mob.setTarget(null);
                    mob.getNavigation().stop();
                }
                if (debuff.slowUntil > 0L && now >= debuff.slowUntil) {
                    removeSlowModifier(entity);
                    debuff.slowUntil = 0L;
                }
                if (now >= debuff.vulnerabilityUntil && debuff.slowUntil == 0L
                        && now >= debuff.noAttackUntil) {
                    it.remove();
                }
            }
        }

        BossImmunity.tick(now);
    }
}
