package com.etbs31.mnemosyne.spell.mid;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.capability.MnemosyneData;
import com.etbs31.mnemosyne.registry.ModEffects;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.MnemosyneSpell;
import com.etbs31.mnemosyne.util.SpellFeedback;
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
 *   <li>对自己挂 10 秒「走马灯守护」—— 期间**第一次致死**会被拦截</li>
 *   <li>拦截时：拉回 <b>50% 最大血量</b>，播放不死图腾的触发形态（配色换成靛蓝/品红）</li>
 *   <li>触发后立即进入「记忆空白」12~8 秒（随等级递减）：
 *       临时忆格**全部清空** / 受到的忆海法术伤害 **+50%** / 法力恢复 **−40%**</li>
 *   <li>10 秒内没死 → 守护自然消散，**不进负面状态**</li>
 * </ol>
 *
 * <p><b>为什么 CD 从 10 秒拉长到 90 秒</b>：它从"骚扰技"变成了"保命技"。
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

    /** 保命窗口时长：10 秒。 */
    private static final int WARD_TICKS = 10 * 20;

    /** 触发后拉回的血量比例：50% 最大生命值。 */
    private static final float REVIVE_HEALTH_FRACTION = 0.5F;

    /**
     * 「记忆空白」（触发致死拦截后的<b>代价/反噬</b>）持续秒数（index = level - 1）：12/10/8。
     *
     * <p>数值来源：docs/tech/13_数值总表.md §走马灯 —— 等级越高，代价越小。
     * ⚠️ 这是**代价**，不是守护窗口；守护窗口恒为 {@link #WARD_TICKS}（10 秒，全等级相同）。
     */
    private static final int[] BLANK_SECONDS = {12, 10, 8};

    public RecollectionSpell() {
        // CD 90s（设计文档 v2 §一；旧值 10s 是"骚扰技"时代的，对保命技来说等于常驻无敌）
        super(memoryConfig(SpellRarity.RARE, 90.0D, 3));
        this.baseManaCost = 86;
        this.manaCostPerLevel = 17;
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
                caster.addEffect(new MobEffectInstance(ward, WARD_TICKS, spellLevel - 1,
                        false, true, true));
            }
            SpellFeedback.actionBar(caster,
                    Component.translatable("mnemosyne.msg.recollection_ward"));
            SpellFeedback.castBurst(level, caster, SpellFeedback.MEMORY_INDIGO);
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
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
        final int index = Math.max(0, Math.min(BLANK_SECONDS.length - 1, spellLevel - 1));
        player.addEffect(new MobEffectInstance(blank, BLANK_SECONDS[index] * 20, 0,
                false, true, true));

        // 代价之一：临时忆格全部清空（"记忆被烧掉了"）。
        // ⚠️ 只清临时格 —— 常驻格与永久格是玩家辛苦攒的，一次免死不值得把它们也清掉。
        MnemosyneData.clearTempSlots(player);
    }
}
