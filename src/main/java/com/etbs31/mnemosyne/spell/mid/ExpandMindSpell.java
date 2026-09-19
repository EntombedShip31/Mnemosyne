package com.etbs31.mnemosyne.spell.mid;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.capability.MnemosyneData;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.EngramResonance;
import com.etbs31.mnemosyne.spell.base.MnemosyneSpell;
import com.etbs31.mnemosyne.util.SpellFeedback;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.Optional;

/**
 * 忆格扩张 Expand Mind —— expand_mind。
 *
 * <p><b>归属</b>：WS-D2（本文件是 WS-A 建立的 stub，WS-D2 只填 onCast 的方法体）。
 *
 * <p><b>数值来源</b>：docs/tech/04_法术等级强度表.md 第四节第 9 条。
 * 构造器里那 5 个字段<b>逐字未动</b>，只把 {@code extends AbstractSpell} 换成了
 * {@link MnemosyneSpell}（WS-C 的公共父类 —— 本法术没有伤害，但需要它持有 DefaultConfig）。
 *
 * <p><b>本法术做什么</b>（§四.9）：给自己**临时忆格**，持续一段时间。
 * 它是"格子不够"这个问题的答案 —— 也是记忆流派唯一能突破硬上限（默认 5）的手段。
 *
 * <table border="1">
 *   <tr><th>等级</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th></tr>
 *   <tr><td>临时忆格数</td><td>2</td><td><b>3</b></td><td>3</td><td><b>4</b></td><td>4</td></tr>
 *   <tr><td>持续时间</td><td>30s</td><td>38s</td><td>45s</td><td>53s</td><td>60s</td></tr>
 *   <tr><td>惩罚减免</td><td>—</td><td>—</td><td>—</td><td>施法速度惩罚减半</td>
 *       <td>施法速度与法力惩罚均减半</td></tr>
 * </table>
 *
 * <p><b>⭐ 惩罚减免走的是 {@code MnemosyneData.applyEngramBuff}</b>
 * <br>临时忆格只提高**上限**，不改变"已占用几格"，所以它本身不会带来惩罚；
 * 但 §四.9 给 4/5 级的减免是作用在**已有的**占用惩罚上的。
 * 那个减免没有别的落点：{@code getCastSpeedPenalty} / {@code getManaPenalty} 是纯函数，
 * 只能靠一份"玩家身上的临时增益"来改写。这就是 {@code applyEngramBuff} 存在的原因。
 *
 * <p><b>⚠️ 临时忆格到期的风险是刻意的</b>：{@code docs/tech/03} §6.7 规定
 * "到期时若其中有记忆，记忆一并消散"。{@code MnemosyneData.pruneTempSlots} 负责清理，
 * 本类**不要**贴心地帮玩家保住内容。
 */
public class ExpandMindSpell extends MnemosyneSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "expand_mind");

    /** 各等级的临时忆格数（index = level - 1）：2/3/3/4/4（docs/tech/13_数值总表.md §忆格扩张）。 */
    private static final int[] TEMP_SLOTS = {2, 3, 3, 4, 4};

    /** 各等级的持续时间（秒，index = level - 1）：30 → 60（docs/tech/13_数值总表.md §忆格扩张）。 */
    private static final int[] DURATION_SECONDS = {30, 38, 45, 53, 60};

    /** 施法速度惩罚的缩放（1.0 = 不减半）。§四.9：4 级起减半。 */
    private static final double HALF = 0.5D;

    public ExpandMindSpell() {
        super(memoryConfig(SpellRarity.RARE, 30.0D, 5));
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

    /** 施法音效：{@code spell.expand_mind.cast}（docs/tech/08 §3.2）。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_EXPAND_MIND_CAST.get());
    }

    // ==================================================================
    // 落地
    // ==================================================================

    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide && entity instanceof ServerPlayer caster) {
            final int index = clampLevelIndex(spellLevel);
            final int durationTicks = DURATION_SECONDS[index] * 20;

            // ① 加临时忆格
            MnemosyneData.addTempSlots(caster, TEMP_SLOTS[index], durationTicks);
            // 视觉（2026-09-18 补）：这个法术改的全是**内部计数** ——
            // 加了几格、惩罚减半，屏幕上原本一点变化都没有。
            // engramGain 的环数 = 格数，配合动作栏那行「忆格 N / M」给一个立刻的确认。
            SpellFeedback.engramGain(level, caster, TEMP_SLOTS[index]);

            // ② 4 级起减免惩罚。5 级把法力惩罚也一起减半。
            final boolean halfCastSpeed = spellLevel >= 4;
            final boolean halfMana = spellLevel >= 5;
            if (halfCastSpeed || halfMana) {
                MnemosyneData.applyEngramBuff(caster, durationTicks,
                        MnemosyneData.NO_RESONANCE_OVERRIDE,
                        halfCastSpeed ? HALF : 1.0D,
                        halfMana ? HALF : 1.0D,
                        false);
                // 惩罚变了 → 立刻刷新派生效果，不要让玩家等 1 秒
                // （2026-09-18：原来是 EngramResonance.syncCastSpeedPenalty，
                //   手工改属性；现在施法速度惩罚由 EngramEffect.BURDEN 承载，
                //   刷新走统一入口 refreshEngramEffects）
                MnemosyneData.refreshEngramEffects(caster);
            }

            syncNow(caster);
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                        ModSounds.HUD_RESONANCE.get(), SoundSource.PLAYERS, 0.8F, 1.2F);
            }
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }

    /** 立刻同步 —— 临时忆格要在 HUD 上马上多出来几格，不能等 20 tick 的轮询。 */
    private static void syncNow(final ServerPlayer caster) {
        MnemosyneData.notifyEngramChange(caster);
    }

    private static int clampLevelIndex(final int spellLevel) {
        return Math.max(1, Math.min(TEMP_SLOTS.length, spellLevel)) - 1;
    }
}
