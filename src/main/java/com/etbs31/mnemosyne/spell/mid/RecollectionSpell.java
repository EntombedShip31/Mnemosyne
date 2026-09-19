package com.etbs31.mnemosyne.spell.mid;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.capability.MnemosyneData;
import com.etbs31.mnemosyne.registry.ModEffects;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.MnemosyneSpell;
import com.etbs31.mnemosyne.util.SpellFeedback;
import io.redspace.ironsspellbooks.api.events.SpellCooldownAddedEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Optional;

/**
 * 走马灯 Recollection —— {@code recollection}。**保命技**。
 *
 * <p><b>⭐⭐ 2026-09-18 重做（设计文档 v2 §一）</b>
 * <br>旧版是"迫使目标重演它最近使用过的能力"。那个设计的根本问题是
 * <b>MC 里没有稳定的"逼 AI 重演某个技能"接口</b>：实现只能靠
 * "观察目标最近用过什么，然后替它施放一次"，于是逻辑完全取决于我们能观察到多少 ——
 * 观察不到的（走 Brain 的生物、原版内联逻辑）就静默失效，
 * 玩家只会觉得"这法术时灵时不灵"。设计文档要求改成保命技是对的。
 *
 * <p><b>新机制（不死图腾式）</b>
 * <ol>
 *   <li>对自己挂 <b>6~14 秒</b>（随等级）「走马灯守护」—— 期间**第一次致死**会被拦截</li>
 *   <li>拦截时：拉回 <b>50% 最大血量</b>，播放不死图腾的触发形态（配色换成靛蓝/品红）</li>
 *   <li>触发后立即进入「记忆空白」12~8 秒（随等级递减）：
 *       临时忆格**全部清空** / 受到的忆海法术伤害 **+50%** / 法力恢复 **−40%**</li>
 *   <li>窗口内没死 → 守护自然消散，**不进负面状态**</li>
 * </ol>
 *
 * <p><b>⭐⭐ 5 级制，四项数值全部线性（2026-09-19 补齐）</b>
 * <br>权威数值来自 {@code docs/忆海Mnemosyne_使用文档.html} 的走马灯卡片
 * （与 {@code docs/tech/13_数值总表.md} 的旧 3 级制<b>不一致</b>，以使用文档为准 ——
 * 玩家与 lang 都按 5 级制写的）。设计手法：<b>把旧的固定值当作 Lv3 中点</b>做线性展开，
 * 中等等级手感不变，低/高等级各自获得区分度。
 * <pre>
 *   免死窗口  4 + 2×Lv   6 / 8 / 10 / 12 / 14 秒
 *   冷却    120 − 10×Lv 110 / 100 / 90 / 80 / 70 秒
 *   耗蓝     45 + 5×Lv   50 / 55 / 60 / 65 / 70
 *   记忆空白 13 − Lv     12 / 11 / 10 / 9 / 8 秒
 * </pre>
 *
 * <p><b>为什么 CD 从 10 秒拉长</b>：它从"骚扰技"变成了"保命技"。
 * 10 秒冷却的免死等于常驻无敌。
 *
 * <p><b>⭐ 法术等级怎么传到触发时</b>：守护效果的 {@code amplifier} 存的就是
 * {@code spellLevel - 1}。这样触发时不需要额外的静态表 —— 状态跟着效果走，
 * 玩家下线再上线也不会丢（效果本身持久化），而且**不会引入一个需要清理的静态 Map**
 * （本项目在内存泄露审查里刚踩过这个坑，见 {@code LongCastTracker}）。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID)
public class RecollectionSpell extends MnemosyneSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "recollection");

    /** 最大等级。四项数值数组的长度都必须等于它。 */
    private static final int MAX_LEVEL = 5;

    /** 保命窗口秒数（index = level - 1）：{@code 4 + 2×Lv} → 6/8/10/12/14。 */
    private static final int[] WARD_SECONDS = {6, 8, 10, 12, 14};

    /**
     * 冷却秒数（index = level - 1）：{@code 120 − 10×Lv} → 110/100/90/80/70。
     *
     * <p>⚠️ 这些值<b>不是</b>直接塞进 {@code memoryConfig} 的 —— ISS 的
     * {@code getSpellCooldown()} 是<b>无参</b>的（读 {@code COOLDOWN_IN_SECONDS} 配置），
     * 拿不到等级。所以这里存的是"目标秒数"，实际改写走
     * {@link #onCooldownAdded(SpellCooldownAddedEvent.Pre)}。
     */
    private static final int[] COOLDOWN_SECONDS = {110, 100, 90, 80, 70};

    /**
     * 配置里的冷却秒数 = {@code COOLDOWN_SECONDS} 的 Lv3 中点（90 秒）。
     *
     * <p><b>为什么必须是中点</b>：改写冷却用的是"在 ISS 算出的值上乘一个比例"，
     * 比例 = {@code 目标秒数 / BASE_COOLDOWN_SECONDS}。取 Lv3 作基准 →
     * Lv3 比例为 1.0（原样不动），低/高等级各自放大/缩小。
     */
    private static final double BASE_COOLDOWN_SECONDS = 90.0D;

    /** 「记忆空白」（触发致死拦截后的<b>代价/反噬</b>）秒数：{@code 13 − Lv} → 12/11/10/9/8。 */
    private static final int[] BLANK_SECONDS = {12, 11, 10, 9, 8};

    /** 触发后拉回的血量比例：50% 最大生命值。 */
    private static final float REVIVE_HEALTH_FRACTION = 0.5F;

    public RecollectionSpell() {
        // maxLevel 5（5 级制）；CD 配置值 90s 只是 Lv3 中点，真正的按等级冷却见 onCooldownAdded
        super(memoryConfig(SpellRarity.RARE, BASE_COOLDOWN_SECONDS, MAX_LEVEL));
        // 耗蓝 45 + 5×Lv → 50/55/60/65/70。⚠️ 旧值 86 + 17/级（→ 满级 154）是 3 级制时代的。
        // getManaCost(level) = (base + per×(level-1)) × MANA_MULTIPLIER 配置 → 本来就是线性，无需改写。
        this.baseManaCost = 50;
        this.manaCostPerLevel = 5;
        this.baseSpellPower = 1;
        this.spellPowerPerLevel = 0;
        this.castTime = 0;
    }

    /** 等级 → 数组下标，越界钳到两端（效果/Curios 可能把等级抬到 maxLevel 以上）。 */
    private static int levelIndex(final int spellLevel) {
        return Math.max(0, Math.min(MAX_LEVEL - 1, spellLevel - 1));
    }

    @Override
    public ResourceLocation getSpellResource() {
        return SPELL_ID;
    }

    @Override
    public CastType getCastType() {
        return CastType.INSTANT;
    }

    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_RECOLLECTION_CAST.get());
    }

    // ==================================================================
    // 施法：给自己挂保命窗口
    // ==================================================================

    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide && entity instanceof ServerPlayer caster) {
            final MobEffect ward = ModEffects.recollectionWard();
            if (ward != null) {
                // ⚠️ 重复施放：同 amplifier 的 addEffect 只刷新时长、不叠加 —— 这正是想要的。
                //    amplifier 存 spellLevel - 1，供触发时读回等级。
                caster.addEffect(new MobEffectInstance(ward,
                        WARD_SECONDS[levelIndex(spellLevel)] * 20, spellLevel - 1,
                        false, true, true));
            }
            SpellFeedback.actionBar(caster,
                    Component.translatable("mnemosyne.msg.recollection_ward"));
            SpellFeedback.castBurst(level, caster, SpellFeedback.MEMORY_INDIGO);
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }

    // ==================================================================
    // 按等级冷却：ISS 没有按等级冷却的入口，只能改写冷却事件
    // ==================================================================

    /**
     * 把冷却从"配置里的固定 90 秒"改成 {@link #COOLDOWN_SECONDS} 的按等级值。
     *
     * <p><b>⭐ 为什么必须走事件，不能覆写 {@code getSpellCooldown()}</b>：
     * 实测（javap ISS 3.16.3 {@code AbstractSpell}）该方法是 <b>无参</b>的，
     * 只读 {@code SpellConfigManager.getSpellConfigValue(this, COOLDOWN_IN_SECONDS)}，
     * <b>拿不到等级</b>。而 {@code MagicManager.addCooldown} 在算完之后会 post
     * {@code SpellCooldownAddedEvent$Pre}（Forge 总线），它带
     * {@code getSpell()} / {@code getEntity()} / {@code setEffectiveCooldown(int)} —— 这是唯一干净的入口。
     *
     * <p><b>⭐⭐ 为什么是"乘比例"而不是"直接赋值"</b>：ISS 算出的
     * {@code getEffectiveCooldown()} 已经套用了玩家的
     * {@code COOLDOWN_REDUCTION} 属性与剑类施法的 {@code SWORDS_CD_MULTIPLIER}。
     * 直接 {@code setEffectiveCooldown(秒数 × 20)} 会把这些加成<b>全部抹掉</b>，
     * 堆冷却缩减的配装会瞬间失效。乘比例 = 保留 ISS 的全部修正，只替换等级带来的基准差异。
     *
     * <p><b>等级从哪来</b>：{@code AbstractSpell.getLevelFor(1, caster)}（final 方法），
     * 它会累加 Curios 的等级加成并 post {@code ModifySpellLevelEvent}，
     * 与 ISS 传给 {@code onCast} 的 {@code spellLevel} 是同一个口径。
     */
    @SubscribeEvent
    public static void onCooldownAdded(final SpellCooldownAddedEvent.Pre event) {
        if (!SPELL_ID.equals(event.getSpell().getSpellResource())) {
            return;
        }
        final int level = event.getSpell().getLevelFor(1, event.getEntity());
        final double ratio = COOLDOWN_SECONDS[levelIndex(level)] / BASE_COOLDOWN_SECONDS;
        event.setEffectiveCooldown((int) Math.round(event.getEffectiveCooldown() * ratio));
    }

    // ==================================================================
    // 触发：拦截死亡
    // ==================================================================

    /**
     * 致死拦截。
     *
     * <p><b>为什么用 {@code LivingDeathEvent} 而不是 {@code LivingDamageEvent}</b>：
     * 在伤害事件里判断"这一下会不会致死"要自己算（吸收、无敌帧、其他模组的减伤都得考虑），
     * 算错就会出现"血是 0 却没触发"或"没到 0 就触发了"。死亡事件是**确定性的那个时刻**。
     *
     * <p>⚠️ {@code LivingDeathEvent} 可取消（Forge 的 {@code @Cancelable}），
     * 取消后实体保持存活但血量仍是 0 —— **必须手动 setHealth**，
     * 否则会出现"血条空了但人还站着"的幽灵状态。
     */
    @SubscribeEvent
    public static void onLivingDeath(final LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        final MobEffect ward = ModEffects.recollectionWard();
        final MobEffectInstance instance = ward == null ? null : player.getEffect(ward);
        if (instance == null) {
            return;
        }

        // ① 先取消死亡，再改血量。
        //    ⚠️ 顺序不能反 —— 原版的死亡流程在事件返回后还会把血量重新置 0。
        event.setCanceled(true);
        player.removeEffect(ward);
        player.setHealth(Math.max(1.0F, player.getMaxHealth() * REVIVE_HEALTH_FRACTION));
        player.deathTime = 0;
        player.hurtTime = 0;

        // ② 进入「记忆空白」（代价）
        applyMemoryBlank(player, instance.getAmplifier() + 1);

        // ③ 视觉：不死图腾的触发形态（entity event 35），再叠我们自己的粒子
        player.level().broadcastEntityEvent(player, (byte) 35);
        if (player.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.ENCHANT,
                    player.getX(), player.getY() + 1.0D, player.getZ(),
                    60, 0.8D, 1.0D, 0.8D, 0.5D);
            serverLevel.sendParticles(ParticleTypes.CRIMSON_SPORE,
                    player.getX(), player.getY() + 1.0D, player.getZ(),
                    30, 0.6D, 0.8D, 0.6D, 0.05D);
            serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                    ModSounds.SPELL_RECOLLECTION_CAST.get(), SoundSource.PLAYERS, 1.0F, 0.8F);
        }
        SpellFeedback.chat(player, Component.translatable("mnemosyne.msg.recollection_triggered"));
    }

    /**
     * 施加「记忆空白」的代价。
     *
     * @param spellLevel 当初施放走马灯时的等级（从守护效果的 amplifier 读回）
     */
    private static void applyMemoryBlank(final ServerPlayer player, final int spellLevel) {
        final MobEffect blank = ModEffects.memoryBlank();
        if (blank == null) {
            return;
        }
        player.addEffect(new MobEffectInstance(blank,
                BLANK_SECONDS[levelIndex(spellLevel)] * 20, 0, false, true, true));

        // 代价之一：临时忆格全部清空（"记忆被烧掉了"）。
        // ⚠️ 只清临时格 —— 常驻格与永久格是玩家辛苦攒的，一次免死不值得把它们也清掉。
        MnemosyneData.clearTempSlots(player);
    }
}
