package com.etbs31.mnemosyne.spell.low;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.capability.MnemosyneData;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.EncodeSpell;
import com.etbs31.mnemosyne.util.SpellFeedback;
import com.etbs31.mnemosyne.util.SpellSlotBoost;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;

import java.util.Optional;

/**
 * 铭忆 Engrave —— {@code engrave}。
 *
 * <p><b>2026-09-18 新增</b>（用户要求："增加一个写入类法术，主要用于短时间增加法术上限"）。
 * 它取代了同一天被删除的「术忆」（{@code encode_spell}）——
 * 术忆的产物是"把别人的法术存进忆格"，而那需要一整套"读取目标正在施放的法术"
 * 的脆弱逻辑（{@code LongCastTracker} 的监听窗口），且释放端依赖复诵。
 * 铭忆保留"写入"的骨架（长吟、需要空忆格、写入后由忆格承担风险），
 * 但把效果换成一个**自包含、不需要释放端**的东西：容量。
 *
 * <p><b>⭐ 名字的由来</b>：写入家族是「术忆 / 质忆 / 痛忆」——
 * 从**别人**身上取一样东西写进自己的忆格。铭忆是同族的第四位，
 * 但取的是"**容量**"这个抽象物：铭 = 刻写，把"能装更多"这件事刻进记忆。
 * 与「忆格扩张」（{@code expand_mind}）区分：那个只加临时忆格、且有等级门槛，
 * 铭忆是低阶、加的是**法术上限**（ISS 法术书能装几个法术）。
 *
 * <p><b>⭐⭐ 用户描述的三件事，与实现的对应关系（写在明处）</b>：
 * <ol>
 *   <li><b>「短时间增加法术上限」</b> → {@link SpellSlotBoost}：
 *       临时提高装备槽里那本法术书的 {@code maxSpellCount}，到期自动还原。</li>
 *   <li><b>「增加忆格 1 点」</b> 与 <b>「增加一格临时忆格上限」</b> ——
 *       ⚠️ 这两条在现有忆格模型里**是同一件事**：
 *       忆格上限 = 基础 + 装备加成 + 临时格数（见 {@code MnemosyneData.getMaxEngrams}），
 *       没有独立于"临时格"的第二种上限。所以本条实现为
 *       {@link #TEMP_ENGRAMS} 个**临时忆格**，同时满足两种说法。</li>
 * </ol>
 *
 * <p><b>代价（为什么这不是白送）</b>：它仍然走写入类的通用门槛 ——
 * <b>必须有一个空忆格</b>（{@code EncodeSpell.onCast} 会检查）。
 * 所以真实收益是"净 +1 临时格（先占 1 格再还 1 格）+ 法术上限"，
 * 而风险是：这条临时格到期时若里面装着记忆，记忆会一并消散
 * （见 {@code MnemosyneData} 的容量钳制）。
 *
 * <p><b>⚠️ 一个已知边界</b>：效果生效期间若把法术书从装备槽取下再装回，
 * 上限会停在提升后的值（不会还原）—— 见 {@link SpellSlotBoost#tick} 的注释。
 * 这是刻意选择的一侧：另一个方向（强行缩容）会**静默吃掉**玩家书里的法术。
 */
public class EngraveSpell extends EncodeSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "engrave");

    /** 临时提高的法术上限格数。 */
    public static final int SLOT_BONUS = 2;

    /** 额外给的临时忆格数（用户要求的"忆格 +1" / "临时忆格上限 +1"）。 */
    public static final int TEMP_ENGRAMS = 1;

    public EngraveSpell() {
        // 冷却 15 秒：容量类增益不该被刷 —— 它直接放大"能带几个法术"这个核心资源。
        super(memoryConfig(SpellRarity.LEGENDARY, 15.0D, 1));
        this.baseManaCost = 100;
        this.manaCostPerLevel = 0;
        this.baseSpellPower = 1;
        this.spellPowerPerLevel = 0;
        this.castTime = 20;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return SPELL_ID;
    }

    /** 写入类默认 {@link CastType#LONG}（见 {@code EncodeSpell.getCastType}），此处沿用。 */

    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_ENGRAVE_CAST.get());
    }

    /**
     * 铭忆是**对自己**施放的（写入自己的忆格）。
     *
     * <p>⚠️ 必须覆写 —— 基类默认 {@code false}，射线打不到目标时会
     * {@code SpellFeedback.noTarget} 并直接放弃施法。
     */
    @Override
    protected boolean allowsSelfTarget() {
        return true;
    }

    @Override
    protected void onEncode(final ServerPlayer caster, final LivingEntity target, final int spellLevel) {
        // 持续时长 = 忆格记忆有效期（默认 120 秒；穿齐忆者法袍 4 件套是 180 秒）。
        // ⭐ 刻意复用同一个来源：铭忆的时长不该有自己的一套数字，
        //    它"是一条记忆"，就该和别的记忆一起腐坏。
        final int seconds = MnemosyneData.getMemoryLifetimeSeconds(caster);
        final int ticks = seconds * 20;

        // ① 临时忆格 +1（用户要求）
        MnemosyneData.addTempSlots(caster, TEMP_ENGRAMS, ticks);
        // 视觉（2026-09-18 补）：本法术改的全是内部计数（忆格数 + 法术上限），
        // 屏幕上原本毫无变化，玩家只能靠动作栏那行字确认。
        // 两个效果用**两种不同的粒子语言**，好让玩家分清哪一条生效了：
        //   尘环 = 忆格（与「忆格扩张」「碎忆」同一套语言）
        //   字符柱 = 法术上限（ENCHANT 是原版唯一"刻写"语义的粒子）
        SpellFeedback.engramGain(caster.level(), caster, TEMP_ENGRAMS);

        // ② 法术上限 +2（用户要求的核心效果）
        final boolean boosted = SpellSlotBoost.apply(caster, SLOT_BONUS, ticks);
        if (boosted) {
            SpellFeedback.spellSlotMark(caster.level(), caster, SLOT_BONUS);
        }

        if (boosted) {
            SpellFeedback.actionBar(caster, Component.translatable(
                    "mnemosyne.msg.engrave_ok", SLOT_BONUS, seconds));
        } else {
            // ⚠️ 没有法术书时**仍然**给临时忆格（已经加了），但必须说清楚
            //    为什么"主要的那个效果"没发生 —— 静默半失效是最难排查的一类问题。
            SpellFeedback.actionBar(caster,
                    Component.translatable("mnemosyne.msg.engrave_no_book"));
        }
    }
}
