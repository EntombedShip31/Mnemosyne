package com.etbs31.mnemosyne.spell.mid;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.OblivionSpell;
import com.etbs31.mnemosyne.util.RaycastHelper;
import com.etbs31.mnemosyne.util.SpellFeedback;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 失忆 Amnesia —— amnesia。
 *
 * <p><b>归属</b>：WS-D1 建立，WS-D2 接管（2026-09-18 从中阶改档）。
 *
 * <p><b>⭐ 2026-09-18 改档</b>：原本「遗忘 forget」（tier 1 单体随机摘一项）与本法术
 * 效果太像 —— 都是"摘掉一个能力"，只是一个摘一个、一个摘全部 + 眩晕，
 * 玩家分不清该用哪个。所以<b>删除 {@code mnemosyne:forget}</b>，
 * 把本法术从「低阶 · Uncommon」提到<b>「中阶 · Rare」</b>，由它独自承担
 * "遗忘系单体起手技"这个位置。文件也从 {@code spell.low} 移到 {@code spell.mid}。
 *
 * <p><b>数值未动</b>：只有稀有度与所属包变了，法力 40(+6/级)、冷却 12 秒、
 * 持续时间 4~8 秒、发呆 30~40 tick 全部保持原样 —— 改档只改"它在法术书里的档位"，
 * 不改强度。{@code docs/tech/04} 第四节第 8 条仍是数值权威。
 *
 * <p><b>本法术做什么</b>：tier 2 —— **禁用目标的全部特殊能力并清空仇恨**，
 * 外加一段"发呆"（站着不动、不索敌）。
 *
 * <table border="1">
 *   <tr><th>等级</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th></tr>
 *   <tr><td>持续时间</td><td>4s</td><td>5s</td><td>6s</td><td>7s</td><td>8s</td></tr>
 *   <tr><td>发呆时间</td><td>1.5s</td><td>1.5s</td><td>1.5s</td><td>2s</td><td>2s</td></tr>
 *   <tr><td>影响目标数</td><td>1</td><td>1</td><td>1</td><td>1</td><td><b>2</b></td></tr>
 * </table>
 *
 * <p><b>⭐ 为什么需要自己的 tick 表来实现"发呆"</b>
 * <br>{@code OblivionManager} 的 tier 2 只做一件事：{@code mob.setTarget(null)}（一次性）。
 * 但被清掉仇恨的生物**下一 tick 就会重新索敌**，所以"发呆 1.5 秒"实际上根本不存在。
 * 原版也没有"眩晕"这种状态（{@code MobEffects} 里没有能同时禁止移动与索敌的效果），
 * 所以这里用一张**只存在内存里的短命表**：在发呆期间每 tick 清仇恨 + 停寻路。
 *
 * <p>刻意不写进 NBT：发呆最长 2 秒，服务器重启时丢掉它反而更安全
 * （与 {@code EncodePainSpell} 的记录窗口同一个理由）。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class AmnesiaSpell extends OblivionSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "amnesia");

    /** 各等级的持续时间（秒，index = level - 1）。§二 失忆 的"遗忘时长（秒）"列。 */
    private static final int[] DURATION_SECONDS = {4, 5, 6, 7, 8};

    /** 各等级的发呆时长（tick，index = level - 1）。§二 失忆 的"眩晕（tick）"列。 */
    private static final int[] DAZE_TICKS = {30, 33, 35, 38, 40};

    /** 5 级开始影响 2 个目标。§四.8 的"影响目标数"列。 */
    private static final int LEVEL_FOR_SECOND_TARGET = 5;

    /**
     * 5 级时"第二个目标"的搜索半径（格）。
     *
     * <p>§四.8 只写了"影响目标数 2"，没说第二个目标怎么选。
     * 这里取"主目标周围 8 格内最近的一个敌人" —— 与集体遗忘的最小半径一致，
     * 语义是"站在一起的两个人一起忘"。
     */
    private static final double SECOND_TARGET_RADIUS = 8.0D;

    /** 正在发呆的生物。键 = 实体 UUID，值 = 剩余 tick。 */
    private static final Map<UUID, Daze> DAZED = new ConcurrentHashMap<>();

    public AmnesiaSpell() {
        super(memoryConfig(SpellRarity.RARE, 12.0D, 5));
        this.baseManaCost = 50;
        this.manaCostPerLevel = 10;
        this.baseSpellPower = 1;
        this.spellPowerPerLevel = 0;
        this.castTime = 0;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return SPELL_ID;
    }

    /** 失忆是 tier 2：禁用全部特殊能力 + 清仇恨。 */
    @Override
    protected int getOblivionTier() {
        return 2;
    }

    /** 施法音效：{@code spell.amnesia.cast}（docs/tech/08 §3.2）。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_AMNESIA_CAST.get());
    }

    // ==================================================================
    // 落地
    // ==================================================================

    @Override
    protected void applyOblivion(final ServerPlayer caster, final LivingEntity target, final int spellLevel) {
        final int index = clampLevelIndex(spellLevel);
        final int durationTicks = DURATION_SECONDS[index] * 20;
        // 视觉（2026-09-18 补）：失忆摘掉的是怪的 AI goal —— **屏幕上本来完全看不出来**，
        // 玩家只能靠"它不打我了"反推。拉一条"施法者 → 目标"的锁定链，
        // 至少说明"我读的是这一只"。（命中爆发由基类 applyTo 统一放，这里不重复。）
        SpellFeedback.beam(target.level(), caster.getEyePosition(), SpellFeedback.chest(target), 20);
        strike(caster, target, durationTicks, DAZE_TICKS[index]);

        if (spellLevel >= LEVEL_FOR_SECOND_TARGET) {
            final LivingEntity second = findSecondTarget(caster, target);
            if (second != null) {
                // ⚠️ 第二个目标走不到基类的 applyTo（它是这里直接调的），
                //    所以它的锁定链与命中爆发必须自己补 —— 否则 5 级的"第二目标"
                //    只有伤害没有表现，玩家根本不知道多摘了一个。
                SpellFeedback.beam(second.level(), caster.getEyePosition(), SpellFeedback.chest(second), 16);
                SpellFeedback.hitBurst(second.level(), second, SpellFeedback.MEMORY_MAGENTA);
                strike(caster, second, durationTicks, DAZE_TICKS[index]);
            }
        }
    }

    private void strike(final ServerPlayer caster, final LivingEntity target,
                        final int durationTicks, final int dazeTicks) {
        // 返回 false = 目标没有可摘的行为（走了通用降级）。降级路径自己会清一次仇恨，
        // 但不会持续 —— 所以无论走哪条路都还要给它一段发呆，否则"失忆"对纯被动生物毫无意义。
        oblivionManager(caster, target, getOblivionTier(), durationTicks);
        daze(target, dazeTicks);
    }

    /**
     * 找"第二个目标"。
     *
     * <p>用 {@link RaycastHelper#findLivingInSphere} 取主目标周围 8 格内的活体，
     * 排除施法者自己与友军，取**距离主目标最近**的一个。
     */
    @Nullable
    private static LivingEntity findSecondTarget(final ServerPlayer caster, final LivingEntity primary) {
        final List<LivingEntity> candidates = new ArrayList<>(RaycastHelper.findLivingInSphere(
                primary.level(), primary.position(), SECOND_TARGET_RADIUS, caster, true));
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final LivingEntity candidate : candidates) {
            if (candidate == primary) {
                continue;
            }
            final double distance = candidate.distanceToSqr(primary);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    // ==================================================================
    // 发呆
    // ==================================================================

    /** 让一个生物发呆若干 tick。对玩家无效（{@code docs/02} §三 规则一）。 */
    private static void daze(final LivingEntity target, final int ticks) {
        if (!(target instanceof Mob mob) || ticks <= 0) {
            return;
        }
        DAZED.put(mob.getUUID(), new Daze(mob, ticks));
    }

    /**
     * 每 tick 推进发呆：清仇恨 + 停寻路 + 原地不动。
     *
     * <p>{@code setDeltaMovement} 只清水平分量 —— 直接清零会让正在下落的生物悬停在空中，
     * 那是一个很显眼的视觉 bug。
     */
    @SubscribeEvent
    public static void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || DAZED.isEmpty()) {
            return;
        }
        // 「发呆」本身是看不见的状态（只有清仇恨 + 停寻路，连效果图标都没有）。
        // 每 5 tick 在头顶冒一次记忆碎屑，否则玩家只会觉得"这只怪卡住了"。
        // 与 CognitiveCollapseSpell 的眩晕提示共用同一个 helper。
        final boolean auraTick = event.getServer().getTickCount() % 5 == 0;
        for (final Iterator<Map.Entry<UUID, Daze>> it = DAZED.entrySet().iterator(); it.hasNext(); ) {
            final Daze daze = it.next().getValue();
            final Mob mob = daze.mob;
            if (mob.isRemoved() || !mob.isAlive()) {
                it.remove();
                continue;
            }
            if (daze.remainingTicks-- <= 0) {
                it.remove();
                continue;
            }
            mob.setTarget(null);
            mob.getNavigation().stop();
            mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
            if (auraTick) {
                SpellFeedback.dazeAura(mob);
            }
        }
    }

    private static final class Daze {
        private final Mob mob;
        private int remainingTicks;

        private Daze(final Mob mob, final int remainingTicks) {
            this.mob = mob;
            this.remainingTicks = remainingTicks;
        }
    }

    private static int clampLevelIndex(final int spellLevel) {
        return Math.max(1, Math.min(DURATION_SECONDS.length, spellLevel)) - 1;
    }
}
