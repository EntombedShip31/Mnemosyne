package com.etbs31.mnemosyne.spell.high;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.registry.ModEffects;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.registry.ModSchools;
import com.etbs31.mnemosyne.spell.base.MnemosyneSpell;
import com.etbs31.mnemosyne.util.RaycastHelper;
import com.etbs31.mnemosyne.util.SpellFeedback;
import io.redspace.ironsspellbooks.api.events.SpellDamageEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SchoolType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认知崩坏 Cognitive Collapse —— cognitive_collapse。
 *
 * <p><b>归属</b>：WS-D3（本文件是 WS-A 建立的 stub，WS-D3 只填 onCast 的方法体）。
 *
 * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「认知崩坏」（<b>唯一事实来源</b>，
 * 最大等级 8）；设计背景见 docs/tech/04_法术等级强度表.md 第四节第 13 条。
 * 构造器里那 5 个字段<b>逐字未动</b>，只把 {@code extends AbstractSpell} 换成了
 * {@link MnemosyneSpell}（需要它的 {@code hurtWithSpellDamage} 走学派伤害源）。
 *
 * <p><b>本法术做什么</b>（§四.13）：把目标身上的**认知过载层数**一次引爆。
 * 它是记忆流派的"爆发收束"——忆矢/窥忆负责叠层，认知崩坏负责兑现。
 *
 * <table border="1">
 *   <tr><th>等级</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th><th>6</th><th>7</th><th>8</th></tr>
 *   <tr><td>每层伤害</td><td>8.0</td><td>8.6</td><td>9.1</td><td>9.7</td><td>10.3</td>
 *       <td>10.9</td><td>11.4</td><td>12.0</td></tr>
 *   <tr><td>5 层总伤</td><td>40</td><td>43</td><td>45.5</td><td>48.5</td><td>51.5</td>
 *       <td>54.5</td><td>57</td><td>60</td></tr>
 *   <tr><td>眩晕</td><td>3.0s</td><td>3.3s</td><td>3.55s</td><td>3.85s</td><td>4.15s</td>
 *       <td>4.45s</td><td>4.7s</td><td>5.0s</td></tr>
 *   <tr><td>后续加成</td><td>—</td><td>+2.9%</td><td>+5.7%</td><td>+8.6%</td><td>+11.4%</td>
 *       <td>+14.3%</td><td>+17.1%</td><td>+20.0%</td></tr>
 * </table>
 *
 * <p><b>⭐ 伤害是"固定每层表"而不是"威力 × 系数"</b>：{@code docs/tech/13_数值总表.md} §二
 * 的每层伤害逐级写死（L1~L8 = 8.0 ~ 12.0），且末行明确"**不受共鸣加成影响**" ——
 * 所以本类**不用** {@code damageOf(...)}（那会乘上 {@code memory_spell_power} 与共鸣），
 * 并且法术 id 被登记在 {@code EngramResonance.RESONANCE_EXEMPT} 里。
 * 这是本流派唯一一个"玩家属性完全不参与"的伤害来源，属于刻意的设计（用层数换伤害）。
 *
 * <p><b>⚠️ 三处已知偏差（都已登记在交付说明里）</b>
 * <ol>
 *   <li>§四.13 写"真实伤害（无视护甲）"。当前走标准学派伤害源，因此**会被护甲减免**。
 *       要做到无视护甲需要给一个独立伤害类型打上 {@code minecraft:bypasses_armor} 标签
 *       并自定义 DamageSource —— 那会改动 {@code docs/tech/04} §五 的强度校验基准，
 *       不在本次串行收尾的范围内。</li>
 *   <li>"眩晕"原版没有对应状态。本实现是「缓慢 VII + 每 tick 清仇恨 + 停寻路 + 清水平速度」，
 *       效果上等价于"站着挨打"，但生物**仍然可以缓慢转身**。</li>
 *   <li>§二 的"后续加成"（L1~L8 = 0.000 ~ 0.200，持续 5s）在本实现里作用于
 *       **所有忆海学派法术**造成的伤害（含官方法术被复现时的伤害），
 *       而不是只作用于"记忆伤害"这个模糊概念 —— 因为忆海学派的伤害类型本来就只有一种。</li>
 * </ol>
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CognitiveCollapseSpell extends MnemosyneSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "cognitive_collapse");

    /** 射程 24 格（§四.13 的"射程"行）。 */
    private static final float RANGE = 24.0F;

    /**
     * 各等级的每层伤害（index = level − 1）。
     *
     * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「认知崩坏」的"每层伤害"列：
     * L1~L8 = <b>8.0 / 8.6 / 9.1 / 9.7 / 10.3 / 10.9 / 11.4 / 12.0</b>。
     */
    private static final float[] DAMAGE_PER_LAYER = {8.0F, 8.6F, 9.1F, 9.7F, 10.3F, 10.9F, 11.4F, 12.0F};

    /**
     * 各等级的眩晕时长（tick，index = level − 1）。
     *
     * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「认知崩坏」的"眩晕（tick）"列：
     * L1~L8 = <b>60 / 66 / 71 / 77 / 83 / 89 / 94 / 100</b>（即 3.0s ~ 5.0s）。
     */
    private static final int[] STUN_TICKS = {60, 66, 71, 77, 83, 89, 94, 100};

    /**
     * 各等级的"后续记忆伤害加成"（index = level − 1）。
     *
     * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「认知崩坏」的"后续加成"列：
     * L1~L8 = <b>0.000 / 0.029 / 0.057 / 0.086 / 0.114 / 0.143 / 0.171 / 0.200</b>。
     *
     * <p>⚠️ 文档正文另有一行"L4 ⭐ 引爆后附加后续加成"，与本表的数值（L2 起即非零）冲突；
     * 此处以 §二 的<b>数值表</b>为准（它是"唯一事实来源"的数值部分）。
     */
    private static final float[] FOLLOWUP_BONUS = {0.0F, 0.029F, 0.057F, 0.086F, 0.114F, 0.143F, 0.171F, 0.200F};

    /** "后续加成"的持续时长（{@code docs/tech/13_数值总表.md} §二：**5s**）。 */
    private static final int FOLLOWUP_TICKS = 100;

    /** 眩晕用的「缓慢」等级：原版 I = -15%，VII = -105% → 实际为 0 速。 */
    private static final int STUN_SLOW_AMPLIFIER = 6;

    /** 正在眩晕的生物：实体 UUID → [实体, 剩余 tick]。 */
    private static final Map<UUID, Object[]> STUNNED = new ConcurrentHashMap<>();

    /** 后续记忆伤害加成：实体 UUID → [到期刻, 加成比例]。 */
    private static final Map<UUID, float[]> FOLLOWUP = new ConcurrentHashMap<>();

    public CognitiveCollapseSpell() {
        super(memoryConfig(SpellRarity.EPIC, 12.0D, 8));
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

    @Override
    public CastType getCastType() {
        return CastType.INSTANT;
    }

    /** 施法音效：{@code spell.cognitive_collapse.cast}（docs/tech/08 §3.2）。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_COGNITIVE_COLLAPSE_CAST.get());
    }

    // ==================================================================
    // 落地
    // ==================================================================

    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide && entity instanceof ServerPlayer caster) {
            final LivingEntity target =
                    RaycastHelper.findLivingTarget(level, caster, RANGE, true, true);
            if (target == null) {
                // ⭐ 2026-09-18：这里原来**什么都不做** —— 花了 70 法力、转了 12 秒冷却，
                //    屏幕上没有目标、没有提示，玩家只会以为法术坏了。
                SpellFeedback.noTarget(caster);
            } else {
                final int layers = currentLayers(target);
                if (layers <= 0) {
                    // 同理：没有层数就没有可引爆的东西（settle 里刻意提前 return），
                    // 但必须说一声，否则和"法术无效"无法区分。
                    SpellFeedback.noOverload(caster);
                } else {
                    settle(caster, target, spellLevel, layers);
                }
            }
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }

    /**
     * 结算一次引爆。
     *
     * <p><b>公开静态</b>：{@code 忆海} 领域结束时要"对领域内所有敌人释放一次认知崩坏
     * （按当前层数结算）"（{@code docs/tech/04} §四.17），它复用这个方法而不是自己写一份 ——
     * 否则两处的层数/伤害/眩晕规则迟早会漂移。
     *
     * @param layers 目标当前的认知过载层数；{@code <= 0} 时**什么都不做**
     *               （没有层数就没有可引爆的东西，这是刻意的：太早引爆就是空放）
     */
    public static void settle(final ServerPlayer caster, final LivingEntity target,
                              final int spellLevel, final int layers) {
        if (target == null || !target.isAlive() || layers <= 0) {
            return;
        }
        final int index = clampLevelIndex(spellLevel);
        final AbstractSpell self = SpellRegistry.getSpell(SPELL_ID.toString());
        final float damage = DAMAGE_PER_LAYER[index] * layers;

        // 视觉（2026-09-18 补）：这是全流派的"终结技"，原来打完屏幕上什么都没有 ——
        // 层数只能靠一个效果图标猜。现在先拉一条"施法者 → 目标"的锁定链，
        // 再让**爆发的规模直接等于层数**（见 SpellFeedback.stackDetonation）。
        SpellFeedback.beam(target.level(), caster.getEyePosition(), SpellFeedback.chest(target), 24);
        SpellFeedback.stackDetonation(target.level(), target.position(), layers);

        // 用本法术的学派伤害源 → 仍受 memory_magic_resist 影响（§四.13 明确要求）
        if (self != null) {
            dealSpellDamage(target, caster, caster, self, damage);
        }
        // 引爆后清空目标全部层数（§四.13）
        clearLayers(target);
        // 满层眩晕
        stun(target, STUN_TICKS[index]);
        // 后续记忆伤害加成：只有加成表 > 0 的等级才登记（见 FOLLOWUP_BONUS）
        if (FOLLOWUP_BONUS[index] > 0.0F) {
            FOLLOWUP.put(target.getUUID(),
                    new float[]{nowTick(target) + FOLLOWUP_TICKS, FOLLOWUP_BONUS[index]});
        }
    }

    // ==================================================================
    // 层数
    // ==================================================================

    /** 目标当前的认知过载层数（{@code amplifier + 1}）；没有该效果时返回 0。 */
    public static int currentLayers(final LivingEntity target) {
        final MobEffect overload = ModEffects.cognitiveOverload();
        if (overload == null) {
            return 0;
        }
        final MobEffectInstance instance = target.getEffect(overload);
        return instance == null ? 0 : instance.getAmplifier() + 1;
    }

    private static void clearLayers(final LivingEntity target) {
        final MobEffect overload = ModEffects.cognitiveOverload();
        if (overload != null) {
            target.removeEffect(overload);
        }
    }

    // ==================================================================
    // 眩晕
    // ==================================================================

    /**
     * 让一个目标眩晕若干 tick。
     *
     * <p>对玩家无效（{@code docs/02} §三 规则一：认知类效果不该作用在玩家身上 ——
     * 与 {@code MemoryArrowSpell.stackCognitiveOverload} 的判定保持一致）。
     */
    public static void stun(final LivingEntity target, final int ticks) {
        if (target instanceof Player || !target.isAlive() || ticks <= 0) {
            return;
        }
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks,
                STUN_SLOW_AMPLIFIER, false, true, true));
        if (target instanceof Mob mob) {
            STUNNED.put(mob.getUUID(), new Object[]{mob, ticks});
        }
    }

    /**
     * 每 tick 推进眩晕。
     *
     * <p>只清**水平**速度：直接清零会让正在下落的生物悬停，是很容易看出来的 bug。
     */
    @SubscribeEvent
    public static void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!STUNNED.isEmpty()) {
            // 眩晕的"可见性"：原版只有一个「缓慢 VII」图标，玩家分不清
            // "它被控住了"和"它刚好没动"。每 5 tick 在头顶冒一次记忆碎屑
            // （与 AmnesiaSpell 的发呆提示共用同一个 helper）。
            final boolean auraTick = event.getServer().getTickCount() % 5 == 0;
            for (final Iterator<Map.Entry<UUID, Object[]>> it = STUNNED.entrySet().iterator(); it.hasNext(); ) {
                final Object[] entry = it.next().getValue();
                final Mob mob = (Mob) entry[0];
                if (mob.isRemoved() || !mob.isAlive()) {
                    it.remove();
                    continue;
                }
                int remaining = (int) entry[1];
                if (remaining-- <= 0) {
                    it.remove();
                    continue;
                }
                entry[1] = remaining;
                mob.setTarget(null);
                mob.getNavigation().stop();
                mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
                if (auraTick) {
                    SpellFeedback.dazeAura(mob);
                }
            }
        }
        if (!FOLLOWUP.isEmpty()) {
            final long now = event.getServer().overworld().getGameTime();
            for (final Iterator<Map.Entry<UUID, float[]>> it = FOLLOWUP.entrySet().iterator(); it.hasNext(); ) {
                if (now >= (long) it.next().getValue()[0]) {
                    it.remove();
                }
            }
        }
    }

    // ==================================================================
    // 后续记忆伤害加成
    // ==================================================================

    /**
     * 被引爆过的目标在一段时间内受到更多忆海伤害。
     *
     * <p>判定条件有两个，缺一不可：
     * <ol>
     *   <li>伤害来源是**忆海学派**的法术（用学派 id 比对，不是"施法者是不是记忆法师"）</li>
     *   <li>受害者身上有未过期的加成登记</li>
     * </ol>
     */
    @SubscribeEvent
    public static void onSpellDamage(final SpellDamageEvent event) {
        if (event.getAmount() <= 0.0F) {
            return;
        }
        final float[] entry = FOLLOWUP.get(event.getEntity().getUUID());
        if (entry == null || nowTick(event.getEntity()) >= (long) entry[0]) {
            return;
        }
        final AbstractSpell spell = event.getSpellDamageSource().spell();
        if (spell == null || !isMemorySchool(spell.getSchoolType())) {
            return;
        }
        event.setAmount(event.getAmount() * (1.0F + entry[1]));
    }

    private static boolean isMemorySchool(@Nullable final SchoolType school) {
        return school != null && ModSchools.MEMORY_RESOURCE.equals(school.getId());
    }

    private static long nowTick(final LivingEntity entity) {
        return entity.level().getGameTime();
    }

    private static int clampLevelIndex(final int spellLevel) {
        return Math.max(1, Math.min(DAMAGE_PER_LAYER.length, spellLevel)) - 1;
    }
}
