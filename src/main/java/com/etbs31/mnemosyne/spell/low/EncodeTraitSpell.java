package com.etbs31.mnemosyne.spell.low;

import com.etbs31.mnemosyne.oblivion.TraitProbe;
import com.etbs31.mnemosyne.util.SpellFeedback;
import com.etbs31.mnemosyne.oblivion.TraitRegistry;
import com.etbs31.mnemosyne.Config;
import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.capability.EngramEntry;
import com.etbs31.mnemosyne.capability.MnemosyneData;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.EncodeSpell;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * 写入 · 质忆 Encode: Trait —— encode_trait。
 *
 * <p><b>归属</b>：WS-D1（本文件是 WS-A 建立的 stub，WS-D1 填实际逻辑）。
 *
 * <p><b>数值来源</b>：docs/tech/04_法术等级强度表.md §四.6（数值冻结，构造器 5 个字段逐字不动）。
 *
 * <p><b>基类</b>：{@link EncodeSpell}。本类只实现 {@code onEncode}。
 *
 * <p><b>本法术做什么</b>：把视线内生物的一个特性写进忆格（表 B，见 {@code docs/02} §表 B）。
 * 释放（获得该特性 30~60 秒）由忆格释放流程负责，不是本类的事。
 *
 * <p><b>⭐ 关于表 B 的来源</b>：完整映射表最终属于 <b>WS-E</b> 的 {@code TraitRegistry}
 * （数据包可覆盖）。但 WS-E 尚未交付，而本工作流只拥有 {@code spell/low/} 下的 6 个文件、
 * 又不允许新建"公共文件"（{@code docs/tech/10} §九 反模式）。因此这里内置一份
 * **与 {@code docs/02} §表 B 逐行对齐**的兜底表，并把它作为包内共享入口
 * （{@link #fallbackTraitsOf}）供窥忆复用。
 * <b>WS-E 交付 TraitRegistry 后，本表整段删除、改为调用 TraitRegistry</b> —— 已列入汇报。
 *
 * <p><b>等级机制</b>（§四.6 的表）：1~2 级随机抽 1 个；3~5 级"从 N 个中选"需要选择界面
 * （{@code WS-I}），当前一律走随机 —— 见 {@code TODO(WS-I)}。
 */
public class EncodeTraitSpell extends EncodeSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "encode_trait");

    /**
     * 各等级的记忆时长基准秒数（index = level - 1）：30/45/60。
     *
     * <p>数值来源：docs/tech/13_数值总表.md §质忆（即 §四.6 的"特性持续"列）。
     * ⚠️ 此处**不含**共鸣加成 —— 见 {@link #RESONANCE_BONUS_SECONDS_PER_SLOT}。
     */
    private static final int[] BASE_DURATION_SECONDS = {30, 45, 60};

    /** 每占用 1 个其他忆格，持续时间 +5 秒（§四.6 末行"共鸣"）。 */
    private static final int RESONANCE_BONUS_SECONDS_PER_SLOT = 5;

    /** BOSS 血量兜底阈值（与 {@code OblivionSpell} 的临时启发式一致；真正判定归 WS-E）。 */
    private static final float BOSS_HEALTH_FALLBACK_THRESHOLD = 100.0F;

    /**
     * 通用记忆（docs/tech/03 §7.4 的降级路径：目标无可窃取特性 / BOSS）。
     *
     * <p>⭐⭐ <b>2026-09-18 修正了一个静默失效</b>：这里原先是
     * {@code ResourceLocation.fromNamespaceAndPath(MODID, "generic_memory")}，
     * 而 {@code TraitRegistry} 里注册的通用特质叫
     * {@code mnemosyne:generic}（{@link TraitRegistry#GENERIC}）——
     * **两个 id 不是同一个**。
     *
     * <p>后果：质忆走降级路径（目标没有可借之物 / 是 BOSS）时写入的记忆，
     * trait id 是 {@code mnemosyne:generic_memory}；复诵时
     * {@code TraitRegistry.applyTrait} 查不到这个 id → 返回 false →
     * <b>玩家拿到一个装着"空记忆"的忆格，而且完全不知道原因</b>。
     *
     * <p>现在直接指向 {@link TraitRegistry#GENERIC}，让 id 只有一个来源。
     * 这类"两个地方各写一个字符串常量"的漂移，正是校验器检查 5b 要治的东西。
     */
    public static final ResourceLocation GENERIC_TRAIT = TraitRegistry.GENERIC;

    private static final Random RANDOM = new Random();


    public EncodeTraitSpell() {
        super(memoryConfig(SpellRarity.UNCOMMON, 5.0D, 3));
        this.baseManaCost = 39;
        this.manaCostPerLevel = 8;
        this.baseSpellPower = 1;
        this.spellPowerPerLevel = 0;
        this.castTime = 16;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return SPELL_ID;
    }

    /**
     * 施法时间随等级递减：16 / 16 / 14 / 12 / 10 tick（§四.6 的表）。
     *
     * <p>⚠️ 必须覆写 —— ISS 默认返回固定的 {@code castTime} 字段。
     */
    @Override
    public int getCastTime(final int spellLevel) {
        if (spellLevel <= 2) {
            return 16;
        }
        return switch (spellLevel) {
            case 3 -> 14;
            case 4 -> 12;
            default -> 10;
        };
    }

    /** 施法音效：{@code spell.encode_trait.cast}（docs/tech/08 §3.2）。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_ENCODE_TRAIT_CAST.get());
    }

    // ==================================================================
    // 写入
    // ==================================================================

    @Override
    protected void onEncode(final ServerPlayer caster, @Nullable final LivingEntity target, final int spellLevel) {
        if (target == null) {
            // 基类的 checkPreCastConditions 已经拦住了"无目标"（不扣法力），这里是二重保险
            return;
        }
        final ResourceLocation traitId = selectTrait(target, spellLevel);
        if (traitId == null) {
            // 白名单外的生物：**不写入忆格**（也不该扣法力 —— 见 checkPreCastConditions）。
            // ⭐ 必须给反馈：旧实现是静默写入"通用记忆"，
            //    玩家拿到一个复诵后毫无效果的忆格，完全不知道原因。
            SpellFeedback.actionBar(caster,
                    Component.translatable("mnemosyne.msg.encode_no_essence"));
            return;
        }
        // 每占用 1 个其他忆格 +5 秒（共鸣）
        final int durationSeconds = BASE_DURATION_SECONDS[clampLevelIndex(spellLevel)]
                + RESONANCE_BONUS_SECONDS_PER_SLOT * MnemosyneData.getUsedEngrams(caster);
        MnemosyneData.addEngram(caster, new EngramEntry.EssenceMemory(
                traitId, durationSeconds * 20, MnemosyneData.newExpireTick(caster)));

        // ⭐ 2026-09-18 特效：抽取链（目标 → 自己）。
        //    写入类法术改的全是**内部计数**，屏幕上原本只有基类那一圈通用爆发，
        //    看不出"这东西是从它身上抽出来的"。
        //    ⚠️ 降级路径（BOSS / 探测不到本质 → mnemosyne:generic）刻意只画**半条链**
        //       且不给落点光：这条路径过去与正常写入视觉上完全一样，
        //       玩家只能事后发现"这条记忆复诵了没反应"。现在当场就能看出拿到的是次品。
        if (GENERIC_TRAIT.equals(traitId)) {
            SpellFeedback.weakExtractBeam(caster.level(), target, caster);
        } else {
            SpellFeedback.extractBeam(caster.level(), target, caster, 20);
        }
    }

    /**
     * 选一个要写入的特性。
     *
     * <p>优先级：BOSS（免疫窃取）→ 通用记忆；无可窃取特性（含其他模组生物）→ 通用记忆；
     * 否则从表 B 里挑一个。
     *
     * <p>TODO(WS-I)：3 级以上应该"从 N 个中选"（需要选择界面 + C2S 包），现在一律随机。
     */
    @Nullable
    private static ResourceLocation selectTrait(final LivingEntity target, final int spellLevel) {
        if (isBoss(target)) {
            return GENERIC_TRAIT;
        }
        // ⭐ 2026-09-18：改成**通用探测**（TraitProbe），不再是手写白名单。
        //    探测全部基于原版谓词（fireImmune / canBreatheUnderwater / getMobType）
        //    与属性表（击退抗性 / 跳跃 / 移速 / 飞行）—— 这两层对**所有生物**
        //    都成立，包括整合包里的模组生物，以及走 Brain 的生物（它们 goal 数为 0，
        //    靠 AI 目标反推特质的老路对它们完全失效）。
        //    探测不到时返回 null（由 onEncode 提示），除非数据包用
        //    #mnemosyne:has_essence 标签显式承认它有本质。
        return TraitProbe.probeOrGeneric(target);
    }

    /**
     * 是否免疫窃取。
     *
     * <p>⚠️ 这是**临时启发式**（血量 + 怪物类别），真正的判定属于 WS-E 的 {@code AbilityMap.isBoss}。
     * 尊重配置开关 {@code oblivion.bossImmunity}（它的注释里明确写了"遗忘/窃取/复现"三件事）。
     */
    private static boolean isBoss(final LivingEntity target) {
        if (!Config.Oblivion.BOSS_IMMUNITY.get()) {
            return false;
        }
        return target.getType().getCategory() == MobCategory.MONSTER
                && target.getMaxHealth() >= BOSS_HEALTH_FALLBACK_THRESHOLD;
    }

    // ==================================================================
    // WS-C 留的两个钩子（接 WS-B 忆格数据层）
    // ==================================================================

    @Override
    protected boolean hasFreeEngramSlot(final ServerPlayer caster) {
        return MnemosyneData.hasFreeSlot(caster);
    }

    @Override
    protected void syncEngrams(final ServerPlayer caster) {
        MnemosyneData.notifyEngramChange(caster);
    }

    // ==================================================================
    // 表 B · 可窃取特性（包内共享，窥忆复用）
    // ==================================================================

    private static ResourceLocation trait(final String path) {
        return ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, path);
    }

    private static final ResourceLocation FIRE_IMMUNITY = trait("fire_immunity");
    private static final ResourceLocation LAVA_AFFINITY = trait("lava_affinity");
    private static final ResourceLocation LEVITATION = trait("levitation");
    private static final ResourceLocation SHORT_TELEPORT = trait("short_teleport");
    private static final ResourceLocation WALL_CLIMB = trait("wall_climb");
    private static final ResourceLocation SNOW_SPEED = trait("snow_speed");
    private static final ResourceLocation ICE_SPEED = trait("ice_speed");
    private static final ResourceLocation WATER_BREATHING = trait("water_breathing");
    private static final ResourceLocation WATER_SPEED = trait("water_speed");
    private static final ResourceLocation NIGHT_VISION = trait("night_vision");
    private static final ResourceLocation DARK_VISION = trait("dark_vision");
    private static final ResourceLocation KNOCKBACK_RESIST = trait("knockback_resist");
    private static final ResourceLocation JUMP_BOOST = trait("jump_boost");
    private static final ResourceLocation SLOW_FALL = trait("slow_fall");
    private static final ResourceLocation SILENT_STEP = trait("silent_step");
    private static final ResourceLocation WITHER_IMMUNITY = trait("wither_immunity");
    private static final ResourceLocation REGENERATION = trait("regeneration");
    private static final ResourceLocation SPEED_BOOST = trait("speed_boost");
    private static final ResourceLocation SHORT_FLIGHT = trait("short_flight");

    /**
     * 表 B 的内置兜底映射（{@code docs/02} §表 B 逐行对齐）。
     *
     * <p>⚠️ 末影龙 / 凋灵**不在表里** —— 设计上不可窃取，由 {@link #isBoss} 走通用降级。
     */
    private static final Map<EntityType<?>, List<ResourceLocation>> FALLBACK_TRAITS = Map.ofEntries(
            Map.entry(EntityType.BLAZE, List.of(FIRE_IMMUNITY)),
            Map.entry(EntityType.MAGMA_CUBE, List.of(FIRE_IMMUNITY, LAVA_AFFINITY)),
            Map.entry(EntityType.GHAST, List.of(FIRE_IMMUNITY, LEVITATION)),
            Map.entry(EntityType.ENDERMAN, List.of(SHORT_TELEPORT)),
            Map.entry(EntityType.SPIDER, List.of(WALL_CLIMB)),
            Map.entry(EntityType.CAVE_SPIDER, List.of(WALL_CLIMB)),
            Map.entry(EntityType.SNOW_GOLEM, List.of(SNOW_SPEED)),
            Map.entry(EntityType.POLAR_BEAR, List.of(ICE_SPEED)),
            Map.entry(EntityType.SQUID, List.of(WATER_BREATHING)),
            Map.entry(EntityType.DOLPHIN, List.of(WATER_SPEED)),
            Map.entry(EntityType.GUARDIAN, List.of(WATER_BREATHING, WATER_SPEED)),
            Map.entry(EntityType.ELDER_GUARDIAN, List.of(WATER_BREATHING, WATER_SPEED)),
            Map.entry(EntityType.BAT, List.of(NIGHT_VISION)),
            Map.entry(EntityType.WARDEN, List.of(DARK_VISION)),
            Map.entry(EntityType.IRON_GOLEM, List.of(KNOCKBACK_RESIST)),
            Map.entry(EntityType.LLAMA, List.of(KNOCKBACK_RESIST)),
            Map.entry(EntityType.TRADER_LLAMA, List.of(KNOCKBACK_RESIST)),
            Map.entry(EntityType.GOAT, List.of(JUMP_BOOST)),
            Map.entry(EntityType.RABBIT, List.of(JUMP_BOOST)),
            Map.entry(EntityType.PHANTOM, List.of(SLOW_FALL)),
            Map.entry(EntityType.CHICKEN, List.of(SLOW_FALL)),
            Map.entry(EntityType.CAT, List.of(SILENT_STEP)),
            Map.entry(EntityType.OCELOT, List.of(SILENT_STEP)),
            Map.entry(EntityType.WITHER_SKELETON, List.of(WITHER_IMMUNITY)),
            Map.entry(EntityType.AXOLOTL, List.of(REGENERATION)),
            Map.entry(EntityType.SHULKER, List.of(LEVITATION)),
            Map.entry(EntityType.HORSE, List.of(SPEED_BOOST)),
            Map.entry(EntityType.DONKEY, List.of(SPEED_BOOST)),
            Map.entry(EntityType.MULE, List.of(SPEED_BOOST)),
            Map.entry(EntityType.CAMEL, List.of(SPEED_BOOST)),
            Map.entry(EntityType.BEE, List.of(SHORT_FLIGHT)));

    /**
     * 取某生物可被窃取的特性列表；没有映射时返回空列表。
     *
     * <p>包内共享入口：质忆（本类）与窥忆（{@link GlimpseSpell}）都用它。
     *
     * <p>TODO(WS-E)：整段改为 {@code TraitRegistry.getTraits(type)}。
     */
    static List<ResourceLocation> fallbackTraitsOf(final EntityType<?> type) {
        return FALLBACK_TRAITS.getOrDefault(type, List.of());
    }

    /**
     * 特性 id → 可显示的文本。
     *
     * <p>能对上原版状态效果 / 属性的，直接用原版键（中英文都能正确显示）；
     * 对不上的（爬墙、短距传送这类原版没有的概念）退回可读化的路径名。
     */
    static Component traitDisplayName(final ResourceLocation id) {
        return switch (id.getPath()) {
            case "fire_immunity" -> Component.translatable("effect.minecraft.fire_resistance");
            case "levitation" -> Component.translatable("effect.minecraft.levitation");
            case "water_breathing" -> Component.translatable("effect.minecraft.water_breathing");
            case "night_vision" -> Component.translatable("effect.minecraft.night_vision");
            case "jump_boost" -> Component.translatable("effect.minecraft.jump_boost");
            case "slow_fall" -> Component.translatable("effect.minecraft.slow_falling");
            case "regeneration" -> Component.translatable("effect.minecraft.regeneration");
            case "knockback_resist" -> Component.translatable("attribute.name.generic.knockback_resistance");
            case "speed_boost" -> Component.translatable("attribute.name.generic.movement_speed");
            default -> Component.literal(id.getPath().replace('_', ' '));
        };
    }

    private static int clampLevelIndex(final int spellLevel) {
        return Math.max(1, Math.min(BASE_DURATION_SECONDS.length, spellLevel)) - 1;
    }
}
