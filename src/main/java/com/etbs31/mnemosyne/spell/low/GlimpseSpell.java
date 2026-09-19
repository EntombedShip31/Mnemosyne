package com.etbs31.mnemosyne.spell.low;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.oblivion.AbilityMap;
import com.etbs31.mnemosyne.oblivion.TraitProbe;
import com.etbs31.mnemosyne.registry.ModEffects;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.MnemosyneSpell;
import com.etbs31.mnemosyne.util.RaycastHelper;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 窥忆 Glimpse —— glimpse。
 *
 * <p><b>归属</b>：WS-D1（本文件是 WS-A 建立的 stub，WS-D1 填实际逻辑）。
 *
 * <p><b>数值来源</b>：docs/tech/04_法术等级强度表.md §四.2（数值冻结，构造器 5 个字段逐字不动）。
 *
 * <p><b>基类</b>：{@link MnemosyneSpell}（WS-C 的公共父类）。
 * 窥忆既不是投射物、也不是写入类，所以直接用公共父类拿"config 持有 + 学派伤害源"两件事。
 *
 * <p><b>信息解锁</b>（docs/tech/04 §四.2 的表，逐级 + 2026-09-18 扩充）：
 * <pre>
 * L1 生命 / 护甲 / 手持 / 当前攻击目标 / 可窃取特性 / 威胁等级
 * L2 + 移动速度
 * L3 + 全部状态效果（小字体 + 永久效果显示 ∞）
 * L4 + 生命百分比
 * L5 + 可被遗忘的能力清单 + 掉落预览
 * </pre>
 *
 * <p><b>⭐ 2026-09-18 四项改动</b>（用户要求）：
 * <ol>
 *   <li><b>射程 24 → 16 格</b>（{@link #RANGE}）：窥忆是瞬发 + 0 冷却 + 只要 10 法力，
 *       24 格太远，玩家可以站在安全距离无风险点名 —— 缩短后需要真的靠近。</li>
 *   <li><b>施法后给目标发光</b>（{@link #GLOW_TICKS}）：配合缩短的射程，
 *       形成"靠近 → 窥视 → 短暂标记"的战术节奏。</li>
 *   <li><b>状态效果换字体</b>：见 {@link #EFFECT_STYLE}。</li>
 *   <li><b>路径预测改为只发给施法者</b>：见 {@link #showPredictedPath}。</li>
 * </ol>
 *
 * <p><b>⚠️ 呈现方式</b>：设计意图是"浮空文字显示在目标头顶，仅自己可见"（{@code docs/04 §2}），
 * 那需要客户端渲染，属于 <b>WS-I</b>。本工作流用**只发给施法者自己的聊天栏消息**作为
 * 零依赖的服务端实现（同一份数据，换一个呈现层即可）。
 */
public class GlimpseSpell extends MnemosyneSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "glimpse");

    /**
     * 射程（格）。
     *
     * <p>⭐ 2026-09-18：<b>24 → 16</b>（用户要求"触发范围减少一些"）。
     * 理由：窥忆是 {@link CastType#INSTANT} + 0 冷却 + 只要 10 法力，
     * 24 格意味着玩家可以站在任何远程怪的射程之外无限侦察。16 格落在
     * "比近战远、比大部分远程近"的区间，逼玩家承担一点风险。
     */
    private static final float RANGE = 16.0F;

    /**
     * 施法后给目标叠的发光时长（tick）。
     *
     * <p>⭐ 2026-09-18 新增（用户要求）。200 tick = 10 秒 —— 足够打完一场遭遇战，
     * 又短到不会变成"永久透视"。
     *
     * <p>⚠️ 用原版 {@link MobEffects#GLOWING} 而不是自己写渲染：发光是**服务端效果**，
     * 原版会把它同步给所有能看到该实体的玩家（含隔着方块的轮廓）。
     * 这是刻意接受的设计 —— 见类注释第 2 条。
     */
    private static final int GLOW_TICKS = 200;

    /** 认知过载层数上限（docs/tech/04 §四.2 未按等级分档，统一取 5）。 */
    private static final int OVERLOAD_CAP = 5;

    /** 原版没有"状态效果"这个标签键，用中性的记号打头。 */
    private static final String EFFECT_MARKER = "✦ ";

    // ==================================================================
    // 小字体（用户要求：状态效果一行"特殊一点、小一点点"）
    // ==================================================================

    /**
     * 小字体：{@code assets/mnemosyne/font/small.json}。
     *
     * <p><b>怎么做出"小一点点"</b>：MC 的字体**没有字号**概念，只有字形位图。
     * 但 {@code bitmap} 字体提供器有一个 {@code height} 字段 —— 它决定把源贴图里的
     * 字形**缩放到多高**。所以我们引用<b>原版自己的</b> {@code minecraft:font/ascii.png}
     * 并把 {@code height} 从 8 压到 6，就得到一套 75% 大小的 ASCII 字形
     * （数字、罗马数字、∞、括号全部覆盖 —— 正是这一行需要的字符集）。
     *
     * <p>⚠️ <b>为什么不整行都用它</b>：{@code ascii.png} 里只有 ASCII。
     * 状态效果的名字是中文，不在里面。所以字体 JSON 的第三个 provider 回退到
     * {@code minecraft:include/default}（完整默认字体）——
     * <b>中文用原尺寸渲染，绝不会出现"缺字方块"</b>。
     * 宁可中文不小，也不能把文字弄碎。
     *
     * <p>⚠️ 字体资源位置是**跨包解析**的：引用 {@code minecraft:font/ascii.png} 是合法的，
     * 而把它复制进我们自己的 jar 才是有问题的（原版资源不可再分发）。
     */
    private static final ResourceLocation SMALL_FONT =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "small");

    /**
     * 状态效果那一行的样式：小字体 + 斜体 + 淡紫。
     *
     * <p>斜体让它在满屏直立的聊天文字里"看得出来是另一种信息"；
     * 淡紫与窥忆的学派色（靛蓝 + 品红）同族，但不与正文的白色抢注意力。
     */
    private static final Style EFFECT_STYLE = Style.EMPTY
            .withFont(SMALL_FONT)
            .withItalic(true)
            .withColor(ChatFormatting.LIGHT_PURPLE);

    /**
     * 时长超过这个值就显示 {@code ∞}。
     *
     * <p>1200 秒 = 20 分钟。原版没有"无限"以外的长时长概念，
     * 但模组效果动辄几十分钟甚至用 {@code -1}（无限）——
     * 前者显示成 "1800s" 既占地方又没有信息量，后者会显示成 "**:**"（很难看懂）。
     * 统一成 {@code ∞} 更直观。
     */
    private static final int INFINITE_DISPLAY_TICKS = 20 * 60 * 20;

    public GlimpseSpell() {
        super(memoryConfig(SpellRarity.COMMON, 2.0D, 5));
        this.baseManaCost = 17;
        this.manaCostPerLevel = 3;
        this.baseSpellPower = 1;
        this.spellPowerPerLevel = 0;
        this.castTime = 0;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return SPELL_ID;
    }

    @Override
    public CastType getCastType() {
        return CastType.INSTANT;
    }

    /** 施法音效：{@code spell.glimpse.cast}（docs/tech/08 §3.2 —— 官方 {@code planar_sight} 的"看穿"意象）。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_GLIMPSE_CAST.get());
    }

    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide && entity instanceof ServerPlayer caster) {
            final LivingEntity target = RaycastHelper.findLivingTarget(level, caster, RANGE, true, false);
            if (target != null) {
                // 窥忆无伤害，但命中叠 1 层认知过载（docs/tech/04 §四.2 末行）
                MemoryArrowSpell.stackCognitiveOverload(target, OVERLOAD_CAP);

                // ⭐ 2026-09-18（用户要求 4）：让目标发光 —— 窥忆不只是"看一眼"，
                //    它同时是一次**短暂标记**。发光由服务端同步，所以队友也看得见轮廓，
                //    这让窥忆在多人里有了"点名"的战术价值。
                //    ambient=false / visible=false：不显示药水图标（它是个标记，不是 buff），
                //    但 showIcon=true 保留 F1 隐藏界面外的兜底可读性。
                target.addEffect(new MobEffectInstance(
                        MobEffects.GLOWING, GLOW_TICKS, 0, false, false, true));

                caster.sendSystemMessage(buildReport(caster, target, spellLevel));

                if (level instanceof ServerLevel serverLevel) {
                    showPredictedPath(serverLevel, caster, target);
                }
            }
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }

    // ==================================================================
    // 路径预测
    // ==================================================================

    /** 预测时长：2 秒（40 tick）。 */
    private static final int PREDICT_TICKS = 40;

    /** 每 tick 的水平阻力（MC 大多数生物是 0.98）。 */
    private static final double DRAG = 0.98D;

    /** 原版默认重力（1.20.1 没有可读的重力 API，见 showPredictedPath 的注释）。 */
    private static final double DEFAULT_GRAVITY = 0.08D;

    /** 粒子取样间隔（tick）。2 tick 一个点 → 最多 20 个点。 */
    private static final int PREDICT_STEP = 2;

    /**
     * 在世界里画出目标**接下来 2 秒的预测路径**。
     *
     * <p><b>为什么不用寻路 API</b>：MC 里没有"读取生物移动意图"的通用接口 ——
     * {@code getNavigation().getPath()} 只对走地面寻路的生物有效，
     * 飞行 / 水生 / 被击退 / 被 {@code Brain} 驱动的生物全都没有可用路径。
     * 所以**不读意图，读物理**：用当前速度做外推，并补偿 MC 每 tick 实际施加的两个量
     * —— <b>重力</b>与<b>阻力</b>。
     *
     * <pre>
     * 水平：x(t) = x0 + vx · Σ(0.98^i, i=1..t)      // 阻力按乘性衰减
     * 垂直：y(t) = y0 + vy · t − g · t(t+1)/2       // 重力每 tick 减一次
     * </pre>
     *
     * <p><b>⭐⭐ 性能（用户要求"最优性能"）</b>：
     * <ol>
     *   <li><b>只发给施法者</b>：用 {@code sendParticles(ServerPlayer, ...)} 重载，
     *       而不是 {@code sendParticles(particle, ...)}。后者会把同一个粒子包
     *       广播给 32 格内**每一个**玩家 —— 20 个点 × N 个玩家。
     *       前者恒定 20 个包、恒定 1 个接收者，且与"只有我看得见"的设计一致。</li>
     *   <li><b>零世界查询</b>：不做 raycast、不查方块碰撞、不建路径对象，
     *       只做 20 次纯算术。整个方法没有一次世界访问。</li>
     *   <li><b>静止时直接返回</b>：目标速度 &lt; 0.001 时不画 ——
     *       避免"怪站着不动、脚下糊一堆粒子"，同时这是**性能闸门**：
     *       站着不动的怪（绝大多数时间）零开销。</li>
     * </ol>
     *
     * <p>⚠️ <b>本方法的已知不准确之处（写在明处）</b>：
     * <ul>
     *   <li>目标<b>主动转向</b>（AI 改变意图）时预测必然偏差 —— 这是物理外推的固有极限；</li>
     *   <li>飞行生物（{@code isNoGravity()}）跳过重力项，但它们的速度控制更自由，偏差更大；</li>
     *   <li>水 / 岩浆里的阻力与陆地不同，这里统一用 0.98。</li>
     * </ul>
     * 所以它是<b>参考线</b>而不是"必然发生"—— 用于判断"它会往哪边走"足够，
     * 不要当成精确落点。
     */
    private void showPredictedPath(final ServerLevel level, final ServerPlayer caster,
                                   final LivingEntity target) {
        final Vec3 v = target.getDeltaMovement();
        // 静止不动（或几乎不动）时不画 —— 免得在怪脚下一堆粒子，反而干扰视线。
        // ⚠️ 这一步同时是**性能闸门**：站着不动的怪（绝大多数时间）零开销。
        if (v.lengthSqr() < 0.001D) {
            return;
        }
        // ⚠️ 1.20.1 的 LivingEntity 上没有 getGravity()（实测编译不过），
        //    所以用原版默认值 0.08 —— 绝大多数生物都是这个值。
        final double gravity = target.isNoGravity() ? 0.0D : DEFAULT_GRAVITY;
        final double x0 = target.getX();
        final double y0 = target.getY();
        final double z0 = target.getZ();

        double dragSum = 0.0D;
        double dragFactor = 1.0D;
        for (int t = 1; t <= PREDICT_TICKS; t++) {
            dragFactor *= DRAG;
            dragSum += dragFactor;
            if (t % PREDICT_STEP != 0) {
                continue;                 // 只在中点放粒子，但阻力累加每 tick 都要做
            }
            final double x = x0 + v.x * dragSum;
            final double y = y0 + v.y * t - gravity * t * (t + 1) / 2.0D;
            final double z = z0 + v.z * dragSum;
            // ⭐ 只发给施法者（ServerPlayer 重载）—— 见方法注释的性能说明。
            level.sendParticles(caster, ParticleTypes.END_ROD, false,
                    x, y + 0.3D, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    // ==================================================================
    // 报告生成
    // ==================================================================

    /**
     * 生成读到的信息。
     *
     * <p>⚠️ <b>标签的本地化</b>：标签优先用**原版已有的键**
     * （{@code attribute.name.generic.*} / {@code item.modifiers.mainhand} /
     * {@code engram.mnemosyne.essence}），这样中英文都能正确显示，不出现"键名裸奔"。
     * 忆海自己的标签走 {@code mnemosyne.probe.*}。
     */
    private MutableComponent buildReport(final ServerPlayer caster, final LivingEntity target,
                                         final int spellLevel) {
        final List<Component> lines = new ArrayList<>();

        // 头部：窥忆 · <目标名> [+ 威胁标签]
        final MutableComponent head = Component.translatable("spell.mnemosyne.glimpse")
                .withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(target.getDisplayName().copy().withStyle(ChatFormatting.WHITE));
        // ⭐ 威胁等级直接写在标题行 —— 这是玩家最该先看到的一条。
        final Component threat = threatTag(target);
        if (threat != null) {
            head.append(Component.literal(" ")).append(threat);
        }
        lines.add(head);

        // L1 ①：生命 / 最大生命
        lines.add(labeled(Component.translatable("attribute.name.generic.max_health"),
                Component.literal(format(target.getHealth()) + " / " + format(target.getMaxHealth()))));

        // L1 ②：护甲值
        lines.add(labeled(Component.translatable("attribute.name.generic.armor"),
                Component.literal(Integer.toString(target.getArmorValue()))));

        // L1 ③：手持物品
        lines.add(labeled(Component.translatable("item.modifiers.mainhand"), describeHand(target.getMainHandItem())));

        // L1 ④：当前攻击目标
        if (target instanceof Mob mob && mob.getTarget() != null) {
            lines.add(Component.literal("→ ").withStyle(ChatFormatting.GRAY)
                    .append(mob.getTarget().getDisplayName().copy().withStyle(ChatFormatting.WHITE)));
        }

        // L1 ⑤：可窃取特性（TraitProbe 的通用探测结果）
        final List<ResourceLocation> traits = TraitProbe.probe(target);
        if (!traits.isEmpty()) {
            lines.add(Component.translatable("engram.mnemosyne.essence",
                    joinNames(traits)).withStyle(ChatFormatting.GRAY));
        }

        // L2：移动速度
        if (spellLevel >= 2) {
            lines.add(labeled(Component.translatable("attribute.name.generic.movement_speed"),
                    Component.literal(String.format("%.3f", target.getAttributeValue(Attributes.MOVEMENT_SPEED)))));
        }

        // L3+：全部状态效果（小字体 + ∞）
        if (spellLevel >= 3) {
            lines.add(effectsLine(target));
        }

        // L4+：生命百分比
        if (spellLevel >= 4) {
            final int percent = Math.round(target.getHealth() / Math.max(1.0F, target.getMaxHealth()) * 100.0F);
            lines.add(labeled(Component.translatable("attribute.name.generic.max_health"),
                    Component.literal(percent + "%")));
        }

        // ---- 忆海专属情报（"一般模组看不到的信息"）----

        // 认知过载层数
        final int overload = overloadStacks(target);
        if (overload > 0) {
            lines.add(probe("overload", Component.literal(Integer.toString(overload))));
        }

        // 遗忘剩余时间
        final int oblivion = oblivionTicks(target);
        if (oblivion > 0) {
            lines.add(probe("oblivion", Component.literal(seconds(oblivion))));
        }

        // 意识深度：Goal 驱动（可剥夺）还是 Brain 驱动（剥夺不可达）
        if (target instanceof Mob mob && !AbilityMap.removalSupported(mob.getType())) {
            lines.add(Component.translatable("mnemosyne.probe.brain").withStyle(ChatFormatting.DARK_GRAY));
        }

        // L5+：可被遗忘的能力清单 + 掉落预览
        if (spellLevel >= 5 && target instanceof Mob mob) {
            final List<ResourceLocation> abilities = AbilityMap.presentAbilities(mob);
            if (!abilities.isEmpty()) {
                lines.add(probe("abilities", Component.literal(joinAbilityNames(abilities))));
            }
            final Component drops = dropsLine(caster, target);
            if (drops != null) {
                lines.add(probe("drops", drops));
            }
        }

        final MutableComponent result = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                result.append(Component.literal("\n"));
            }
            result.append(lines.get(i));
        }
        return result;
    }

    /** 威胁标签：BOSS（免疫剥夺）→ 精英 → null。 */
    private static Component threatTag(final LivingEntity target) {
        if (AbilityMap.isBoss(target.getType())) {
            return Component.translatable("mnemosyne.probe.boss").withStyle(ChatFormatting.RED);
        }
        if (AbilityMap.isElite(target.getType())) {
            return Component.translatable("mnemosyne.probe.elite").withStyle(ChatFormatting.GOLD);
        }
        return null;
    }

    /** 忆海专属情报的一行：{@code ✦ <标签> <值>}，标签走 {@code mnemosyne.probe.*}。 */
    private static MutableComponent probe(final String key, final Component value) {
        return Component.literal(EFFECT_MARKER).withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.translatable("mnemosyne.probe." + key).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" "))
                .append(value.copy().withStyle(ChatFormatting.WHITE));
    }

    /** {@code 标签 值} 形式的一行。 */
    private static MutableComponent labeled(final Component label, final Component value) {
        return Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY)
                .append(label.copy().withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" "))
                .append(value.copy().withStyle(ChatFormatting.WHITE));
    }

    /**
     * 状态效果一行。
     *
     * <p>⭐ 2026-09-18（用户要求 2）：整行改用 {@link #EFFECT_STYLE}
     * —— <b>小字体 + 斜体 + 淡紫</b>，与正文区分开。
     *
     * <p>格式：{@code ✦ 力量 II 12s  速度 ∞}（用空格分隔，不用顿号 ——
     * 小字体下顿号与中文挤在一起会糊成一团）。
     */
    private static MutableComponent effectsLine(final LivingEntity target) {
        final MutableComponent line = Component.literal(EFFECT_MARKER)
                .withStyle(EFFECT_STYLE.withColor(ChatFormatting.DARK_GRAY));
        boolean first = true;
        for (final MobEffectInstance instance : target.getActiveEffects()) {
            if (!first) {
                line.append(Component.literal("  ").withStyle(EFFECT_STYLE));
            }
            line.append(instance.getEffect().getDisplayName().copy().withStyle(EFFECT_STYLE))
                    .append(Component.literal(" ").withStyle(EFFECT_STYLE))
                    .append(durationText(instance));
            first = false;
        }
        return first ? Component.empty() : line;
    }

    /**
     * 状态效果的时长文本。
     *
     * <p>⭐ 2026-09-18（用户要求 2）：<b>时长特别长时显示 {@code ∞}</b>。
     * 判据有两条：
     * <ul>
     *   <li>{@link MobEffectInstance#isInfiniteDuration()} —— 模组常用
     *       {@code duration = -1} 表示永久，原版默认会渲染成 {@code **:**}（很难看懂）；</li>
     *   <li>剩余 ≥ {@link #INFINITE_DISPLAY_TICKS}（20 分钟）—— 显示成
     *       "1800s" 既占地方又没信息量。</li>
     * </ul>
     */
    private static MutableComponent durationText(final MobEffectInstance instance) {
        if (instance.isInfiniteDuration() || instance.getDuration() >= INFINITE_DISPLAY_TICKS) {
            return Component.literal("∞").withStyle(EFFECT_STYLE);
        }
        // ⚠️ MobEffectUtil.formatDuration 返回的是 Component，必须 copy() 才能改样式
        //    （它内部缓存了实例，直接 withStyle 会污染原版其他地方的显示）。
        return MobEffectUtil.formatDuration(instance, 1.0F).copy().withStyle(EFFECT_STYLE);
    }

    /** 目标身上的认知过载层数（amplifier + 1）。 */
    private static int overloadStacks(final LivingEntity target) {
        final MobEffect effect = ModEffects.cognitiveOverload();
        if (effect == null) {
            return 0;
        }
        final MobEffectInstance instance = target.getEffect(effect);
        return instance == null ? 0 : instance.getAmplifier() + 1;
    }

    /** 目标身上剩余的遗忘 / 失忆时长（取更长的那个）。 */
    private static int oblivionTicks(final LivingEntity target) {
        int max = 0;
        for (final MobEffect effect : new MobEffect[] {ModEffects.forget(), ModEffects.amnesia()}) {
            if (effect == null) {
                continue;
            }
            final MobEffectInstance instance = target.getEffect(effect);
            if (instance != null) {
                max = Math.max(max, instance.getDuration());
            }
        }
        return max;
    }

    /**
     * 掉落预览（L5）。
     *
     * <p><b>为什么这是"一般模组看不到的信息"</b>：原版只有 JEI / REI 这类界面模组
     * 才会展示掉落表，而且它们是**离线数据**（不含抢夺附魔、不含生物当前状态）。
     * 这里用**真实的 {@code LootParams}**（带 {@code THIS_ENTITY}）抽样，
     * 所以反映的是这只怪此刻的掉落。
     *
     * <p>⚠️ <b>性能</b>：一次 {@code getLootTable} + 一次抽样。只在 L5 触发，
     * 且窥忆是瞬发法术 —— 摊到每次施法的开销可以忽略。
     * 结果做了去重与截断（最多 5 种），避免掉落表很长的怪刷屏。
     */
    private static Component dropsLine(final ServerPlayer caster, final LivingEntity target) {
        if (!(caster.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        try {
            final ResourceLocation tableId = target.getLootTable();
            final LootTable table = serverLevel.getServer().getLootData().getLootTable(tableId);
            if (table == LootTable.EMPTY) {
                return null;
            }
            final LootParams params = new LootParams.Builder(serverLevel)
                    .withParameter(LootContextParams.THIS_ENTITY, target)
                    .withParameter(LootContextParams.ORIGIN, target.position())
                    .create(LootContextParamSets.ENTITY);

            final Set<String> names = new LinkedHashSet<>();
            table.getRandomItems(params, stack -> {
                if (!stack.isEmpty() && names.size() < 5) {
                    names.add(stack.getHoverName().getString());
                }
            });
            if (names.isEmpty()) {
                return null;
            }
            return Component.literal(String.join("、", names)).withStyle(ChatFormatting.WHITE);
        } catch (final RuntimeException ex) {
            // ⚠️ 掉落表是数据包内容，可能被整合包改坏。
            //    一个坏掉的战利品表**不该**让窥忆整个法术失效 —— 吞掉异常、这一行不显示。
            MnemosyneMod.LOGGER.debug("[窥忆] 读取掉落表 {} 失败：{}",
                    target.getLootTable(), ex.toString());
            return null;
        }
    }

    private static Component describeHand(final ItemStack stack) {
        if (stack.isEmpty()) {
            return Component.literal("—").withStyle(ChatFormatting.DARK_GRAY);
        }
        final MutableComponent name = stack.getHoverName().copy().withStyle(ChatFormatting.WHITE);
        if (stack.getCount() > 1) {
            name.append(Component.literal(" ×" + stack.getCount()).withStyle(ChatFormatting.GRAY));
        }
        return name;
    }

    private static String joinNames(final List<ResourceLocation> ids) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(EncodeTraitSpell.traitDisplayName(ids.get(i)).getString());
        }
        return sb.toString();
    }

    /** 能力 id 列表 → 显示名（走 {@code ability.mnemosyne.*} 语言键）。 */
    private static String joinAbilityNames(final List<ResourceLocation> ids) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append('、');
            }
            final ResourceLocation id = ids.get(i);
            sb.append(Component.translatable(
                    "ability." + id.getNamespace() + "." + id.getPath()).getString());
        }
        return sb.toString();
    }

    /** 生命值显示：整数就不带小数点，避免 "12.0 / 20.0" 这种噪音。 */
    private static String format(final float value) {
        return value == Math.round(value) ? Integer.toString(Math.round(value)) : String.format("%.1f", value);
    }

    /** tick → 秒（整数就不带小数点）。 */
    private static String seconds(final int ticks) {
        final float s = ticks / 20.0F;
        return (s == Math.round(s) ? Integer.toString(Math.round(s)) : String.format("%.1f", s)) + "s";
    }
}
