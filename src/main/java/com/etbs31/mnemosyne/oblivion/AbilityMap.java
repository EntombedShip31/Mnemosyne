package com.etbs31.mnemosyne.oblivion;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表 A · 可遗忘的能力 —— 把"AI 行为"翻译成流派语言。
 *
 * <p><b>文件归属</b>：WS-E 遗忘系统。
 *
 * <p><b>它解决的核心问题</b>：设计文档（{@code docs/02} §四 表 A）用玩家语言描述能力 ——
 * "射箭""爬墙""发射火焰弹"。但代码里没有"射箭"这种东西，只有
 * {@code RangedBowAttackGoal} 这类 {@link Goal} 对象。本类就是这两套语言之间的翻译层。
 *
 * <p><b>⭐ 实测：原版 Goal 的真实类名（2026-09-16，读 Forge official 映射源码）</b>
 * <br>关键发现是**很多能力并不是独立类，而是怪物的内部类**：
 * <pre>
 * 烈焰人   Blaze.BlazeAttackGoal         → simpleName "BlazeAttackGoal"   （package-private！）
 * 恶魂     Ghast.GhastShootFireballGoal  → "GhastShootFireballGoal"
 * 守卫者   Guardian.GuardianAttackGoal   → "GuardianAttackGoal"
 * 潜影贝   Shulker.ShulkerAttackGoal     → "ShulkerAttackGoal"
 * 唤魔者   Evoker.EvokerAttackSpellGoal  → "EvokerAttackSpellGoal"
 * 幻术师   Illusioner.IllusionerMirrorSpellGoal → "IllusionerMirrorSpellGoal"
 * 溺尸     Drowned.DrownedTridentAttackGoal     → "DrownedTridentAttackGoal"
 * 幻翼     Phantom.PhantomSweepAttackGoal       → "PhantomSweepAttackGoal"
 * </pre>
 * 这些内部类**大多是 package-private**，{@code instanceof} 根本写不出来（编译期就不可见）。
 * 所以本类**按类名的字符串模式匹配**，而不是 {@code instanceof}：
 * <ul>
 *   <li>编译期无依赖 → 原版换版本不会炸，其他模组的 Goal 也能识别</li>
 *   <li>能匹配到不可见的内部类</li>
 *   <li>模式表可以整体被数据包覆盖（整合包作者不必改代码）</li>
 * </ul>
 *
 * <p><b>⭐ 另一个实测结论：能力清单要从生物**当前拥有的 goal** 反推，而不是查表</b>
 * <br>{@code docs/02} §2.1 要求"若目标没有对应的行为（例如僵尸没有远程行为），
 * 则重新随机，直到命中一个它确实拥有的行为"。
 * 与其按 EntityType 查表再猜它有没有，不如直接遍历 {@code goalSelector} /
 * {@code targetSelector}，把**实际存在的** goal 分类 —— 这样：
 * <ul>
 *   <li>继承来的行为自动包含（{@code Husk} 自己不写 {@code registerGoals}，
 *       行为来自 {@code Zombie}，遍历实例照样拿得到）</li>
 *   <li>其他模组的生物天然支持（它们只是"表 A 里没有，但 goal 能分类"）</li>
 *   <li>被 AI 动态增删行为的生物也准确</li>
 * </ul>
 * 所以 {@link #presentAbilities(Mob)}（实测派）与 {@link #getAbilities(EntityType)}（声明派）
 * 是两个**用途不同**的 API，不要混用：前者驱动遗忘的实际行为，后者是给 WS-D
 * （走马灯 / 质忆）与整合包作者看的"这张生物会什么"的清单。
 *
 * <p><b>数据包覆盖</b>：监听 {@code AddReloadListenerEvent}（由 {@link OblivionManager} 注册），
 * 读 {@code data/mnemosyne/mnemosyne/oblivion/abilities.json} 与 {@code bosses.json}。
 * 内建表在 JSON 缺失/损坏时兜底，保证"最坏情况下流派仍然可用"。
 */
public final class AbilityMap {

    private AbilityMap() {}

    // ==================================================================
    // 能力 id（冻结：数据包与 WS-D 都引用这些字符串）
    // ==================================================================

    /** 近战攻击。 */
    public static final ResourceLocation MELEE_ATTACK = ability("melee_attack");
    /** 远程攻击（射箭 / 弩 / 投掷 / 火球 / 激光 / 追踪弹）。 */
    public static final ResourceLocation RANGED_ATTACK = ability("ranged_attack");
    /** 追击与仇恨锁定。 */
    public static final ResourceLocation CHASE_TARGET = ability("chase_target");
    /** 跳跃突进（蜘蛛扑击 / 岩浆怪跳跃）。 */
    public static final ResourceLocation LEAP = ability("leap");
    /** 自爆引爆（苦力怕）。 */
    public static final ResourceLocation EXPLODE = ability("explode");
    /** 搬走方块（末影人）。 */
    public static final ResourceLocation TAKE_BLOCK = ability("take_block");
    /** 放置 / 吃掉方块（末影人 / 羊）。 */
    public static final ResourceLocation PLACE_BLOCK = ability("place_block");
    /** 破门（卫道士 / 僵尸）。 */
    public static final ResourceLocation BREAK_DOOR = ability("break_door");
    /** 坚守阵地（掠夺者）。 */
    public static final ResourceLocation HOLD_GROUND = ability("hold_ground");
    /** 施法（唤魔者 / 幻术师的公共吟唱行为）。 */
    public static final ResourceLocation CAST_SPELL = ability("cast_spell");
    /** 召唤尖牙陷阱（唤魔者）。 */
    public static final ResourceLocation EVOKER_FANGS = ability("evoker_fangs");
    /** 召唤恼鬼（唤魔者）。 */
    public static final ResourceLocation SUMMON_VEX = ability("summon_vex");
    /** 召唤分身（幻术师）。 */
    public static final ResourceLocation MIRROR_IMAGE = ability("mirror_image");
    /** 致盲（幻术师）。 */
    public static final ResourceLocation BLINDNESS = ability("blindness");
    /** 召唤同伴（蠹虫）。 */
    public static final ResourceLocation SUMMON_HELPERS = ability("summon_helpers");
    /** 俯冲攻击（幻翼）。 */
    public static final ResourceLocation DIVE_ATTACK = ability("dive_attack");
    /** 冲撞（山羊 / 疣猪兽）。 */
    public static final ResourceLocation RAM = ability("ram");
    /** 喝药水（女巫）。 */
    public static final ResourceLocation DRINK_POTION = ability("drink_potion");
    /** 走位躲避。 */
    public static final ResourceLocation AVOID_ENTITY = ability("avoid_entity");

    /** 全部内建能力 id，供数据包校验与文档生成使用。 */
    public static final List<ResourceLocation> ALL_ABILITIES = List.of(
            MELEE_ATTACK, RANGED_ATTACK, CHASE_TARGET, LEAP, EXPLODE, TAKE_BLOCK, PLACE_BLOCK,
            BREAK_DOOR, HOLD_GROUND, CAST_SPELL, EVOKER_FANGS, SUMMON_VEX, MIRROR_IMAGE,
            BLINDNESS, SUMMON_HELPERS, DIVE_ATTACK, RAM, DRINK_POTION, AVOID_ENTITY);

    // ==================================================================
    // 行为分类规则（有序！具体在前，通用在后，首次命中即返回）
    // ==================================================================

    /**
     * 一条分类规则。
     *
     * @param ability 能力 id
     * @param pattern 类名模式：无通配 = 精确匹配 simpleName（或全限定名）；
     *                {@code *Foo} = simpleName 以 Foo 结尾；{@code Foo*} = 以 Foo 开头
     */
    private record Rule(ResourceLocation ability, String pattern) {}

    private static final List<Rule> RULES = List.of(
            // —— 必须先匹配"具体的法术行为"，否则会被后面的 *AttackGoal / 通用规则吃掉 ——
            rule(EVOKER_FANGS, "*EvokerAttackSpellGoal"),
            rule(SUMMON_VEX, "*SummonSpellGoal"),
            rule(MIRROR_IMAGE, "*MirrorSpellGoal"),
            rule(BLINDNESS, "*BlindnessSpellGoal"),
            rule(CAST_SPELL, "*CastingSpellGoal"),
            rule(CAST_SPELL, "*UseSpellGoal"),
            // 兜底：未单列的吟唱行为（如 EvokerWololoSpellGoal）
            rule(CAST_SPELL, "*SpellGoal"),
            rule(SUMMON_HELPERS, "*WakeUpFriendsGoal"),
            rule(SUMMON_HELPERS, "*MergeWithStoneGoal"),
            rule(DIVE_ATTACK, "*SweepAttackGoal"),
            rule(DIVE_ATTACK, "*AttackStrategyGoal"),
            rule(RAM, "*RamTargetGoal"),
            rule(RAM, "*HoglinAttackGoal"),
            rule(EXPLODE, "SwellGoal"),
            rule(LEAP, "LeapAtTargetGoal"),
            // 岩浆怪 / 史莱姆的"跳跃"本体（分裂由 remove() 触发，不是 goal）
            rule(LEAP, "*KeepOnJumpingGoal"),
            rule(TAKE_BLOCK, "*TakeBlockGoal"),
            rule(PLACE_BLOCK, "*LeaveBlockGoal"),
            rule(PLACE_BLOCK, "EatBlockGoal"),
            rule(BREAK_DOOR, "*BreakDoorGoal"),
            rule(HOLD_GROUND, "*HoldGroundAttackGoal"),
            // —— 远程：原版的五个通用远程 goal + 各怪的专属远程内部类 ——
            rule(RANGED_ATTACK, "RangedAttackGoal"),
            rule(RANGED_ATTACK, "RangedBowAttackGoal"),
            rule(RANGED_ATTACK, "RangedCrossbowAttackGoal"),
            rule(RANGED_ATTACK, "*TridentAttackGoal"),
            rule(RANGED_ATTACK, "*ShootFireballGoal"),
            rule(RANGED_ATTACK, "*BlazeAttackGoal"),
            rule(RANGED_ATTACK, "*GuardianAttackGoal"),
            rule(RANGED_ATTACK, "*ShulkerAttackGoal"),
            rule(RANGED_ATTACK, "*SnowballAttackGoal"),
            rule(DRINK_POTION, "UseItemGoal"),
            rule(AVOID_ENTITY, "AvoidEntityGoal"),
            // —— 近战：显式列名，不用 *AttackGoal 通配 ——
            //    *AttackGoal 会把 ShulkerDefenseAttackGoal 之类"目标选择型"行为误判成近战，
            //    显式列名的代价是每加一个生物要补一行，但换来的是不会静默误判。
            rule(MELEE_ATTACK, "MeleeAttackGoal"),
            rule(MELEE_ATTACK, "*MeleeAttackGoal"),
            rule(MELEE_ATTACK, "ZombieAttackGoal"),
            // 溺尸的近战（extends ZombieAttackGoal，但类名不匹配任何后缀模式，必须显式列出）
            rule(MELEE_ATTACK, "DrownedAttackGoal"),
            rule(MELEE_ATTACK, "SpiderAttackGoal"),
            rule(MELEE_ATTACK, "OcelotAttackGoal"),
            rule(MELEE_ATTACK, "MoveTowardsTargetGoal"),
            rule(MELEE_ATTACK, "SlimeAttackGoal"),
            rule(MELEE_ATTACK, "PandaAttackGoal"),
            rule(MELEE_ATTACK, "BeeAttackGoal"),
            rule(MELEE_ATTACK, "EvilRabbitAttackGoal"),
            rule(MELEE_ATTACK, "VexChargeAttackGoal"),
            // —— 追击：目标选择器里的 goal ——
            rule(CHASE_TARGET, "*DefenseAttackGoal"),
            rule(CHASE_TARGET, "NearestAttackableTargetGoal"),
            rule(CHASE_TARGET, "NearestAttackableWitchTargetGoal"),
            rule(CHASE_TARGET, "NearestHealableRaiderTargetGoal"),
            rule(CHASE_TARGET, "NonTameRandomTargetGoal"),
            rule(CHASE_TARGET, "HurtByTargetGoal"),
            rule(CHASE_TARGET, "DefendVillageTargetGoal"),
            rule(CHASE_TARGET, "ResetUniversalAngerTargetGoal"),
            rule(CHASE_TARGET, "*NearestAttackGoal"),
            rule(CHASE_TARGET, "*AttackPlayerTargetGoal"),
            rule(CHASE_TARGET, "*CopyOwnerTargetGoal"),
            rule(CHASE_TARGET, "*BecomeAngryTargetGoal"));

    // ==================================================================
    // ⭐ 开发期自检：用真实原版类名验证整张模式表
    // ==================================================================

    /**
     * 真实原版 goal 类名 → 期望能力。
     *
     * <p>2026-09-16 从 Forge {@code official} 映射源码逐个提取（{@code monster/} 与 {@code animal/}
     * 两个包的全部 {@code registerGoals()}），**不是**凭记忆写的。
     *
     * <p>这张表存在的意义：{@link #RULES} 是**有序**的首次命中表，
     * 一条放错位置的通用规则会静默吞掉后面所有具体规则（例如把
     * {@code *SpellGoal} 放到 {@code *EvokerAttackSpellGoal} 前面，
     * 唤魔者的尖牙就会被归类成"通用施法"）。
     * 这类错误编译期完全看不出来，只在游戏里表现为"某些生物遗忘不了"。
     */
    private static final Map<String, ResourceLocation> KNOWN_GOALS = Map.ofEntries(
            // 近战
            Map.entry("MeleeAttackGoal", MELEE_ATTACK),
            Map.entry("ZombieAttackGoal", MELEE_ATTACK),
            Map.entry("SpiderAttackGoal", MELEE_ATTACK),
            Map.entry("VindicatorMeleeAttackGoal", MELEE_ATTACK),
            Map.entry("DrownedAttackGoal", MELEE_ATTACK),
            Map.entry("PolarBearMeleeAttackGoal", MELEE_ATTACK),
            Map.entry("PandaAttackGoal", MELEE_ATTACK),
            Map.entry("BeeAttackGoal", MELEE_ATTACK),
            Map.entry("SlimeAttackGoal", MELEE_ATTACK),
            Map.entry("VexChargeAttackGoal", MELEE_ATTACK),
            Map.entry("EvilRabbitAttackGoal", MELEE_ATTACK),
            Map.entry("OcelotAttackGoal", MELEE_ATTACK),
            Map.entry("MoveTowardsTargetGoal", MELEE_ATTACK),
            // 远程
            Map.entry("RangedAttackGoal", RANGED_ATTACK),
            Map.entry("RangedBowAttackGoal", RANGED_ATTACK),
            Map.entry("RangedCrossbowAttackGoal", RANGED_ATTACK),
            Map.entry("DrownedTridentAttackGoal", RANGED_ATTACK),
            Map.entry("GhastShootFireballGoal", RANGED_ATTACK),
            Map.entry("BlazeAttackGoal", RANGED_ATTACK),
            Map.entry("GuardianAttackGoal", RANGED_ATTACK),
            Map.entry("ShulkerAttackGoal", RANGED_ATTACK),
            // 追击
            Map.entry("NearestAttackableTargetGoal", CHASE_TARGET),
            Map.entry("NearestAttackableWitchTargetGoal", CHASE_TARGET),
            Map.entry("NonTameRandomTargetGoal", CHASE_TARGET),
            Map.entry("HurtByTargetGoal", CHASE_TARGET),
            Map.entry("DefendVillageTargetGoal", CHASE_TARGET),
            Map.entry("ResetUniversalAngerTargetGoal", CHASE_TARGET),
            Map.entry("ShulkerDefenseAttackGoal", CHASE_TARGET),
            Map.entry("ShulkerNearestAttackGoal", CHASE_TARGET),
            Map.entry("PhantomAttackPlayerTargetGoal", CHASE_TARGET),
            Map.entry("VexCopyOwnerTargetGoal", CHASE_TARGET),
            Map.entry("BeeBecomeAngryTargetGoal", CHASE_TARGET),
            // 其他专有能力
            Map.entry("LeapAtTargetGoal", LEAP),
            Map.entry("SwellGoal", EXPLODE),
            Map.entry("EndermanTakeBlockGoal", TAKE_BLOCK),
            Map.entry("EndermanLeaveBlockGoal", PLACE_BLOCK),
            Map.entry("EatBlockGoal", PLACE_BLOCK),
            Map.entry("VindicatorBreakDoorGoal", BREAK_DOOR),
            Map.entry("HoldGroundAttackGoal", HOLD_GROUND),
            Map.entry("PhantomSweepAttackGoal", DIVE_ATTACK),
            Map.entry("PhantomAttackStrategyGoal", DIVE_ATTACK),
            Map.entry("EvokerAttackSpellGoal", EVOKER_FANGS),
            Map.entry("EvokerSummonSpellGoal", SUMMON_VEX),
            Map.entry("EvokerCastingSpellGoal", CAST_SPELL),
            Map.entry("EvokerWololoSpellGoal", CAST_SPELL),
            Map.entry("SpellcasterCastingSpellGoal", CAST_SPELL),
            Map.entry("SpellcasterUseSpellGoal", CAST_SPELL),
            Map.entry("IllusionerMirrorSpellGoal", MIRROR_IMAGE),
            Map.entry("IllusionerBlindnessSpellGoal", BLINDNESS),
            Map.entry("SilverfishWakeUpFriendsGoal", SUMMON_HELPERS),
            Map.entry("SilverfishMergeWithStoneGoal", SUMMON_HELPERS),
            Map.entry("UseItemGoal", DRINK_POTION),
            Map.entry("AvoidEntityGoal", AVOID_ENTITY));

    /**
     * 环境行为 —— **必须**分类为 {@code null}。
     *
     * <p>遗忘不该让敌人连路都不会走（{@code docs/02} §2.3 要求集体遗忘期间敌人
     * "缓慢游荡"而不是"定住"），所以漂浮 / 漫步 / 看向玩家这些一律不归类。
     * 反向用例比正向用例更重要：正向错了只是"某个能力忘不掉"，
     * 反向错了是"敌人彻底瘫痪"，后者会直接破坏设计意图。
     */
    private static final Set<String> AMBIENT_GOALS = Set.of(
            "FloatGoal", "RandomLookAroundGoal", "LookAtPlayerGoal", "RandomStrollGoal",
            "WaterAvoidingRandomStrollGoal", "RandomSwimmingGoal", "PanicGoal", "TemptGoal",
            "RestrictSunGoal", "FleeSunGoal", "OpenDoorGoal", "BreedGoal",
            "FollowParentGoal", "FollowOwnerGoal", "FollowMobGoal", "SitWhenOrderedToGoal",
            "RandomFloatAroundGoal", "RandomStandGoal", "MoveToBlockGoal");

    static {
        selfCheck();
    }

    /** 自检失败**只记日志不抛异常** —— 一条分类规则的疏漏不该让玩家的游戏起不来。 */
    private static void selfCheck() {
        int failures = 0;
        for (final Map.Entry<String, ResourceLocation> entry : KNOWN_GOALS.entrySet()) {
            final ResourceLocation actual = classifyName(entry.getKey(), goalFqn(entry.getKey()));
            if (!entry.getValue().equals(actual)) {
                failures++;
                MnemosyneMod.LOGGER.warn("[WS-E] 行为分类自检失败：{} 期望 {}，实际 {}",
                        entry.getKey(), entry.getValue(), actual);
            }
        }
        for (final String ambient : AMBIENT_GOALS) {
            final ResourceLocation actual = classifyName(ambient, goalFqn(ambient));
            if (actual != null) {
                failures++;
                MnemosyneMod.LOGGER.warn("[WS-E] 行为分类自检失败：{} 是环境行为，不应被归类，实际 {}",
                        ambient, actual);
            }
        }
        if (failures == 0) {
            MnemosyneMod.LOGGER.info("[WS-E] 行为分类自检通过：{} 条正向 + {} 条反向（模式表共 {} 条规则）",
                    KNOWN_GOALS.size(), AMBIENT_GOALS.size(), RULES.size());
        } else {
            MnemosyneMod.LOGGER.warn("[WS-E] 行为分类自检发现 {} 处不一致，请检查 AbilityMap.RULES 的顺序与模式",
                    failures);
        }
    }

    private static String goalFqn(final String simpleName) {
        return "net.minecraft.world.entity.ai.goal." + simpleName;
    }

    // ==================================================================
    // 内建表 A（数据包缺失时的兜底）
    // ==================================================================

    private static final Map<ResourceLocation, List<ResourceLocation>> BUILT_IN = buildBuiltIn();

    /** 内建 BOSS 集合（{@code docs/02} §三：末影龙 / 凋灵 / 监守者视为 BOSS）。 */
    private static final Set<ResourceLocation> BUILT_IN_BOSSES = Set.of(
            mc("ender_dragon"), mc("wither"), mc("warden"));

    private static volatile Map<ResourceLocation, List<ResourceLocation>> declared = BUILT_IN;
    private static volatile Set<ResourceLocation> bosses = BUILT_IN_BOSSES;
    private static volatile Set<ResourceLocation> elites = Set.of();

    // ==================================================================
    // 查询 API
    // ==================================================================

    /**
     * 表 A：该生物**声明**拥有的能力（数据包可覆盖，JSON 缺失时用内建表）。
     *
     * <p>⚠️ 不要用它驱动遗忘的移除逻辑 —— 用 {@link #presentAbilities(Mob)}。
     * 本方法的用途是"给玩家/法术看的能力清单"（走马灯、质忆的候选列表）与数据包扩展。
     *
     * @return 不可变列表；该生物没有映射时返回空列表（调用方应回落到通用降级）
     */
    public static List<ResourceLocation> getAbilities(final EntityType<?> type) {
        if (type == null) {
            return List.of();
        }
        final ResourceLocation id = EntityType.getKey(type);
        return declared.getOrDefault(id, List.of());
    }

    /**
     * BOSS 判定 —— {@code docs/02} §三 规则二"BOSS 完全免疫剥夺"的唯一事实来源。
     *
     * <p>原版**没有** BOSS 标签或 BOSS 注册表（实测：{@code api/util/BossbarManager}
     * 只管自定义血条），所以这张表必须我们自己维护。
     * 数据包可用 {@code bosses.json} 覆盖（整合包作者可以把自己的 BOSS 加进来）。
     */
    public static boolean isBoss(final EntityType<?> type) {
        if (type == null) {
            return false;
        }
        return bosses.contains(EntityType.getKey(type));
    }

    /**
     * 精英怪判定 —— {@code docs/02} §三 规则五"对精英怪持续时间减半"。
     *
     * <p>原版同样没有这个概念，默认集合为**空**（宁可不少算，也不要凭血量猜错：
     * 血量启发式会把"铁傀儡"这类高血量的非精英怪误判）。
     * 由数据包显式声明。
     */
    public static boolean isElite(final EntityType<?> type) {
        if (type == null) {
            return false;
        }
        return elites.contains(EntityType.getKey(type));
    }

    // ==================================================================
    // ⭐ Brain 生物：没有 GoalSelector，移除方案对它们无效
    // ==================================================================

    /**
     * 使用 **Brain（行为树）** 而非 {@code GoalSelector} 的生物。
     *
     * <p><b>⭐ 2026-09-17 实测结论（重要，会静默失效的那一种）</b>
     * <br>下面这些生物**根本没有 {@code registerGoals()}**，它们的 AI 是
     * {@code net.minecraft.world.entity.ai.Brain} + {@code Behavior}：
     *
     * <pre>{@code
     * Warden    : 0 个 goal，声波冲击来自 ai.behavior.warden.SonicBoom（Brain 行为）
     * Goat      : 0 个 goal，冲撞来自 Brain 的 Ram 行为
     * Piglin    : 0 个 goal
     * PiglinBrute / Zoglin / Hoglin : 同上
     * }</pre>
     *
     * 验证方法（可复现）：从 Forge 反编译 jar
     * {@code forge-1.20.1-47.4.0-decomp.jar} 里读 {@code Warden.java} / {@code Goat.java}，
     * 搜索 {@code m_25352_}（= {@code GoalSelector.addGoal} 的 SRG 名）→ **0 处命中**，
     * 同时文件里有 {@code Brain} 字段。见 {@code tools/check_mob_goals.py}。
     *
     * <p><b>后果（必须知道，否则会以为"遗忘对监守者生效了"）</b>：
     * {@link #presentAbilities(Mob)} 对它们必然返回空列表 →
     * {@code OblivionManager} 走**通用降级**（受伤 +10% / 停止攻击 / 减速），
     * **不会**真的让监守者忘记声波冲击。而 {@code docs/02} §五 案例 3 把监守者
     * 写成了流派的高光时刻 —— 那条设计目前**只能靠降级近似**。
     *
     * <p><b>为什么不在本工作流里修</b>：改 Brain 需要动 {@code Brain} 的私有
     * {@code memories}/{@code behaviors} 表，而 {@code Brain} **没有**被 Forge 的
     * AccessTransformer 提升（{@code goalSelector} 被提升了，{@code Brain} 没有），
     * 所以只能走 Mixin 或反射 —— 而提示词 A 明确禁止引入 Mixin。
     * 正确做法是在 **WS-E2** 里单独设计 Brain 门控方案（例如注册一个
     * {@code Behavior} 包装器或改 {@code Activity} 权重），不要在这里做半成品。
     */
    private static final Set<ResourceLocation> BRAIN_MOBS = Set.of(
            mc("warden"), mc("goat"), mc("piglin"), mc("piglin_brute"),
            mc("zoglin"), mc("hoglin"));

    /**
     * 该生物能否通过"移除 AI 行为"被真正遗忘。
     *
     * <p>{@code false} 只代表"移除方案不适用"，**不代表遗忘完全无效** ——
     * 此时会走通用降级。法术/UI 应当据此调整文案，
     * 不要向玩家承诺"让监守者忘记声波"。
     *
     * <p>⚠️ 这是 {@link #getAbilities} 之外新增的公开 API（不在提示词 A 的冻结契约里），
     * 属于**追加**，不改变任何已有签名。
     */
    public static boolean removalSupported(final EntityType<?> type) {
        if (type == null) {
            return false;
        }
        return !BRAIN_MOBS.contains(EntityType.getKey(type));
    }

    /** 供数据包校验与文档生成：全部 Brain 生物。 */
    public static Set<ResourceLocation> brainMobs() {
        return BRAIN_MOBS;
    }

    /**
     * 把一个 {@link Goal} 实例翻译成能力 id。
     *
     * @return 无法归类（漂浮 / 随机漫步 / 看向玩家等"环境行为"）时返回 {@code null}。
     *         这些行为**刻意不归类** —— 遗忘不该让敌人连路都不会走
     *         （{@code docs/02} §2.3 要求集体遗忘期间敌人是"缓慢游荡"而不是"定住"）。
     */
    public static ResourceLocation classify(final Goal goal) {
        if (goal == null) {
            return null;
        }
        final Class<?> clazz = goal.getClass();
        return classifyName(clazz.getSimpleName(), clazz.getName());
    }

    /**
     * 分类的纯字符串实现（供 {@link #classify(Goal)} 与开发期自检共用）。
     *
     * <p>独立出来的理由：自检需要在不构造任何 {@code Goal}（它们都要 owner 实体）的前提下
     * 验证整张模式表，所以匹配逻辑必须与"取类名"这一步解耦。
     */
    static ResourceLocation classifyName(final String simpleName, final String fullName) {
        for (final Rule rule : RULES) {
            if (matches(rule.pattern(), simpleName, fullName)) {
                return rule.ability();
            }
        }
        return null;
    }

    /**
     * 生物**当前实际拥有**的可遗忘行为（实测派，驱动遗忘逻辑）。
     *
     * <p>遍历 {@code goalSelector} + {@code targetSelector}，对每个 goal 调
     * {@link #classify(Goal)}，去重后返回。因为是对**实例**分类，
     * 继承来的行为与其他模组的行为都能识别。
     *
     * <p>⚠️ 对 {@link #BRAIN_MOBS}（监守者 / 山羊 / 猪灵系）**必然返回空列表** ——
     * 它们没有 goal，AI 在 {@code Brain} 里。这不是 bug，是 1.20.1 的原版结构；
     * 调用方（{@code OblivionManager}）会据此走通用降级。
     * 显式短路是为了省掉一次无意义的遍历，也让"为什么监守者遗忘不了"在代码里可读。
     */
    public static List<ResourceLocation> presentAbilities(final Mob mob) {
        if (mob == null || !removalSupported(mob.getType())) {
            return List.of();
        }
        final Set<ResourceLocation> out = new LinkedHashSet<>();
        for (final GoalEntry entry : snapshot(mob)) {
            final ResourceLocation ability = classify(entry.getGoal());
            if (ability != null) {
                out.add(ability);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 取出该生物身上属于指定能力的全部 goal（连同它所属的选择器）。
     *
     * <p>返回 {@link GoalEntry} 而不是裸 {@code Goal}：移除时必须调**正确的那个**
     * selector 的 {@code removeGoal}，两个 selector 是独立对象。
     */
    public static List<GoalEntry> goalsFor(final Mob mob, final ResourceLocation ability) {
        final List<GoalEntry> out = new ArrayList<>();
        if (ability == null) {
            return out;
        }
        for (final GoalEntry entry : snapshot(mob)) {
            if (ability.equals(classify(entry.getGoal()))) {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * 生物两个选择器里全部 goal 的**快照**。
     *
     * <p>⭐ 实测（读 {@code GoalSelector} 源码）：{@code getAvailableGoals()} 返回的是
     * **内部那个活着的 {@code LinkedHashSet} 本身**，不是副本。
     * 所以"遍历它的同时 removeGoal"会抛 {@code ConcurrentModificationException}。
     * 所有调用方都必须经过本方法拿到副本 —— 这是本类存在的主要理由之一。
     */
    public static List<GoalEntry> snapshot(final Mob mob) {
        final List<GoalEntry> out = new ArrayList<>();
        collect(mob.goalSelector, out);
        collect(mob.targetSelector, out);
        return out;
    }

    // ==================================================================
    // 数据包
    // ==================================================================

    /** 数据包文件名（{@code data/mnemosyne/mnemosyne/oblivion/*.json}）。 */
    static final ResourceLocation FILE_ABILITIES = ability("abilities");
    static final ResourceLocation FILE_BOSSES = ability("bosses");

    /** 由 {@link OblivionManager} 的重载监听器调用。 */
    static void acceptDataPack(final Map<ResourceLocation, JsonElement> files) {
        declared = parseAbilities(files.get(FILE_ABILITIES));
        final JsonObject bossRoot = asObject(files.get(FILE_BOSSES));
        bosses = bossRoot == null ? BUILT_IN_BOSSES : parseIds(bossRoot, "bosses", BUILT_IN_BOSSES);
        elites = bossRoot == null ? Set.of() : parseIds(bossRoot, "elites", Set.of());
    }

    private static Map<ResourceLocation, List<ResourceLocation>> parseAbilities(final JsonElement element) {
        final JsonObject root = asObject(element);
        if (root == null) {
            return BUILT_IN;
        }
        final Map<ResourceLocation, List<ResourceLocation>> out = new LinkedHashMap<>();
        // replace=false（默认）→ 内建表打底，JSON 只做增量；true → 完全以 JSON 为准
        if (!root.has("replace") || !root.get("replace").getAsBoolean()) {
            out.putAll(BUILT_IN);
        }
        final JsonObject table = root.has("abilities") ? asObject(root.get("abilities")) : null;
        if (table == null) {
            MnemosyneMod.LOGGER.warn("[WS-E] abilities.json 缺少 abilities 对象，沿用内建表 A");
            return Map.copyOf(out);
        }
        for (final Map.Entry<String, JsonElement> entry : table.entrySet()) {
            final ResourceLocation mob = ResourceLocation.tryParse(entry.getKey());
            if (mob == null || !entry.getValue().isJsonArray()) {
                MnemosyneMod.LOGGER.warn("[WS-E] abilities.json 忽略非法条目：{}", entry.getKey());
                continue;
            }
            final JsonArray array = entry.getValue().getAsJsonArray();
            final List<ResourceLocation> list = new ArrayList<>(array.size());
            for (final JsonElement item : array) {
                final ResourceLocation parsed = ResourceLocation.tryParse(item.getAsString());
                if (parsed == null) {
                    MnemosyneMod.LOGGER.warn("[WS-E] abilities.json 忽略非法能力 id：{}（生物 {}）",
                            item.getAsString(), mob);
                    continue;
                }
                list.add(parsed);
            }
            out.put(mob, List.copyOf(list));
        }
        MnemosyneMod.LOGGER.info("[WS-E] 表 A 已加载：{} 个生物映射", out.size());
        return Map.copyOf(out);
    }

    private static Set<ResourceLocation> parseIds(final JsonObject root, final String key,
                                                  final Set<ResourceLocation> fallback) {
        final JsonElement element = root.get(key);
        if (element == null || !element.isJsonArray()) {
            return fallback;
        }
        final Set<ResourceLocation> out = new LinkedHashSet<>();
        for (final JsonElement item : element.getAsJsonArray()) {
            final ResourceLocation parsed = ResourceLocation.tryParse(item.getAsString());
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return Set.copyOf(out);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private static void collect(final GoalSelector selector, final List<GoalEntry> out) {
        for (final WrappedGoal wrapped : selector.getAvailableGoals()) {
            out.add(new GoalEntry(selector, wrapped));
        }
    }

    private static boolean matches(final String pattern, final String simple, final String full) {
        if (pattern.startsWith("*")) {
            return simple.endsWith(pattern.substring(1));
        }
        if (pattern.endsWith("*")) {
            return simple.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return simple.equals(pattern) || full.equals(pattern);
    }

    private static Rule rule(final ResourceLocation ability, final String pattern) {
        return new Rule(ability, pattern);
    }

    private static ResourceLocation ability(final String path) {
        return ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, path);
    }

    private static ResourceLocation mc(final String path) {
        return ResourceLocation.fromNamespaceAndPath("minecraft", path);
    }

    private static JsonObject asObject(final JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static Map<ResourceLocation, List<ResourceLocation>> buildBuiltIn() {
        final Map<ResourceLocation, List<ResourceLocation>> map = new LinkedHashMap<>();
        // —— P0：出现频率高、能力辨识度强（docs/02 §六）——
        map.put(mc("zombie"), List.of(MELEE_ATTACK, CHASE_TARGET));
        map.put(mc("skeleton"), List.of(RANGED_ATTACK, AVOID_ENTITY, CHASE_TARGET));
        map.put(mc("creeper"), List.of(EXPLODE, MELEE_ATTACK, CHASE_TARGET));
        map.put(mc("spider"), List.of(LEAP, MELEE_ATTACK, CHASE_TARGET));
        map.put(mc("cave_spider"), List.of(LEAP, MELEE_ATTACK, CHASE_TARGET));
        map.put(mc("enderman"), List.of(TAKE_BLOCK, PLACE_BLOCK, MELEE_ATTACK, CHASE_TARGET));
        map.put(mc("blaze"), List.of(RANGED_ATTACK, CHASE_TARGET));
        map.put(mc("witch"), List.of(RANGED_ATTACK, CHASE_TARGET));
        map.put(mc("warden"), List.of(CHASE_TARGET));
        // —— P1：有明确"特殊能力" ——
        map.put(mc("ghast"), List.of(RANGED_ATTACK));
        map.put(mc("magma_cube"), List.of(LEAP, MELEE_ATTACK));
        map.put(mc("slime"), List.of(LEAP, MELEE_ATTACK));
        map.put(mc("shulker"), List.of(RANGED_ATTACK, CHASE_TARGET));
        map.put(mc("evoker"), List.of(EVOKER_FANGS, SUMMON_VEX, CAST_SPELL, CHASE_TARGET, AVOID_ENTITY));
        map.put(mc("illusioner"), List.of(MIRROR_IMAGE, BLINDNESS, RANGED_ATTACK, CAST_SPELL, CHASE_TARGET));
        map.put(mc("pillager"), List.of(RANGED_ATTACK, HOLD_GROUND, CHASE_TARGET));
        map.put(mc("piglin"), List.of(RANGED_ATTACK, MELEE_ATTACK, CHASE_TARGET));
        map.put(mc("wither_skeleton"), List.of(MELEE_ATTACK, CHASE_TARGET));
        // —— P2：能力较弱，锦上添花 ——
        map.put(mc("husk"), List.of(MELEE_ATTACK, CHASE_TARGET));
        map.put(mc("stray"), List.of(RANGED_ATTACK, CHASE_TARGET));
        map.put(mc("drowned"), List.of(RANGED_ATTACK, MELEE_ATTACK, CHASE_TARGET));
        map.put(mc("phantom"), List.of(DIVE_ATTACK, CHASE_TARGET));
        map.put(mc("hoglin"), List.of(RAM, MELEE_ATTACK, CHASE_TARGET));
        map.put(mc("zoglin"), List.of(RAM, MELEE_ATTACK, CHASE_TARGET));
        map.put(mc("goat"), List.of(RAM));
        map.put(mc("iron_golem"), List.of(MELEE_ATTACK, CHASE_TARGET));
        map.put(mc("snow_golem"), List.of(RANGED_ATTACK));
        map.put(mc("guardian"), List.of(RANGED_ATTACK, CHASE_TARGET));
        map.put(mc("elder_guardian"), List.of(RANGED_ATTACK, CHASE_TARGET));
        map.put(mc("silverfish"), List.of(SUMMON_HELPERS, MELEE_ATTACK, CHASE_TARGET));
        map.put(mc("endermite"), List.of(CHASE_TARGET));
        map.put(mc("vindicator"), List.of(MELEE_ATTACK, BREAK_DOOR, CHASE_TARGET));
        map.put(mc("piglin_brute"), List.of(MELEE_ATTACK, CHASE_TARGET));
        map.put(mc("ravager"), List.of(MELEE_ATTACK, CHASE_TARGET));
        map.put(mc("vex"), List.of(MELEE_ATTACK, CHASE_TARGET));
        map.put(mc("wolf"), List.of(MELEE_ATTACK, LEAP, CHASE_TARGET));
        map.put(mc("polar_bear"), List.of(MELEE_ATTACK, CHASE_TARGET));
        map.put(mc("panda"), List.of(MELEE_ATTACK, CHASE_TARGET));
        map.put(mc("bee"), List.of(MELEE_ATTACK, CHASE_TARGET));
        return Map.copyOf(map);
    }

    // ==================================================================
    // GoalEntry
    // ==================================================================

    /**
     * "一个 goal + 它所属的选择器"。
     *
     * <p>必须成对保存：{@code goalSelector} 与 {@code targetSelector} 是两个独立的
     * {@link GoalSelector} 对象，把 goal 还错地方 = 行为永远回不来。
     */
    public record GoalEntry(GoalSelector selector, WrappedGoal wrapped) {

        public Goal getGoal() {
            return wrapped.getGoal();
        }

        public int getPriority() {
            return wrapped.getPriority();
        }
    }
}
