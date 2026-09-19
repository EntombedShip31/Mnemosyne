package com.etbs31.mnemosyne.spell.low;

import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import com.etbs31.mnemosyne.util.SpellFeedback;
import com.etbs31.mnemosyne.spell.base.MnemosyneSpell;
import com.etbs31.mnemosyne.Config;
import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.capability.EngramEntry;
import com.etbs31.mnemosyne.capability.MnemosyneData;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.EncodeSpell;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 写入 · 痛忆 Encode: Pain —— encode_pain。
 *
 * <p><b>归属</b>：WS-D1（本文件是 WS-A 建立的 stub，WS-D1 填实际逻辑）。
 *
 * <p><b>数值来源</b>：docs/tech/04_法术等级强度表.md §四.7（数值冻结，构造器 5 个字段逐字不动）。
 *
 * <p><b>基类</b>：{@link EncodeSpell}。但本类的 {@code onEncode} **不是立刻写入**，
 * 而是**开启一个记录窗口**：窗口内自己受到的伤害被累加，窗口结束时才写进忆格
 * （{@code docs/04 §7}：6 秒记录窗口 → 等量反打）。
 *
 * <p><b>⭐ 为什么本类是事件订阅者</b>：记录伤害要 {@code LivingHurtEvent}、结算要每 tick 计时。
 * {@code events/**} 归 WS-F，而本工作流只拥有 {@code spell/low/} 下的 6 个文件 ——
 * 所以用 {@code @Mod.EventBusSubscriber} **自动注册**（{@code docs/tech/10} §九 明确把
 * "用 @Mod.EventBusSubscriber 自动注册"列为"主类只有一个主人"的正确解法）。
 *
 * <p><b>记录规则</b>（§四.7）：
 * <table>
 *   <tr><th>等级</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th></tr>
 *   <tr><td>记录窗口</td><td>6s</td><td>7s</td><td>8s</td><td>9s</td><td>10s</td></tr>
 *   <tr><td>反打比例</td><td>50%</td><td>54%</td><td>58%</td><td>62%</td><td>66%</td></tr>
 *   <tr><td>反打上限（最大生命）</td><td>60%</td><td>63%</td><td>66%</td><td>69%</td><td>72%</td></tr>
 * </table>
 * 反打比例的基准取配置 {@code balance.encodePainBaseRefund}（默认 0.50 = 1 级值），每级 +4%；
 * 上限的基准取配置 {@code spells.painMemoryCapPercent}（默认 0.60 = 1 级值），每级 +3%。
 * 两条都保留配置为基准 —— 13 号表定的是<b>曲线形状</b>，玩家仍可整体平移高低。
 *
 * <p><b>不计入的伤害</b>：{@code /kill}（{@code generic_kill}）、虚空（{@code out_of_world}）、
 * 以及自己造成的伤害（自杀式伤害）—— §四.7 的"不计入的伤害"行。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class EncodePainSpell extends EncodeSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "encode_pain");

    /** 记录窗口基准 6 秒（1 级），每级 +1 秒 → 6/7/8/9/10 秒，与 13 号表一致。 */
    private static final int WINDOW_BASE_SECONDS = 6;

    /**
     * 反打上限的每级增量（13 号表：0.60 → 0.72，每级 +0.03）。
     *
     * <p>⭐ 2026-09-18：0.05 → 0.03。旧值会让 5 级长到 0.80（80% 最大生命），
     * 比 13 号表的 0.72 高出 8 个百分点 —— 上限是"单次挨打能还多少"的天花板，
     * 偏高会让痛忆在被秒杀型伤害面前变成无条件反杀。
     */
    private static final double CAP_STEP_PER_LEVEL = 0.03D;

    /**
     * 反打比例的每级增量（13 号表：0.50 → 0.66，每级 +0.04）。
     *
     * <p>基准取配置 {@code balance.encodePainBaseRefund}（默认 0.50 = 1 级值），
     * 所以最终比例 = {@code 配置 + 0.04 × (等级 − 1)}。
     * 保留配置为基准是为了让玩家仍能整体平移高低，13 号表定的是<b>曲线形状</b>。
     */
    private static final double RETALIATION_STEP_PER_LEVEL = 0.04D;

    /**
     * 正在记录中的窗口。键 = 玩家 UUID。
     *
     * <p>刻意**只存在内存里**：服务器重启后正在记录的窗口丢失（玩家损失一次写入），
     * 这比"把半截记录写进存档、重启后意外生效"安全得多。
     */
    private static final Map<UUID, Recording> RECORDINGS = new ConcurrentHashMap<>();

    public EncodePainSpell() {
        super(memoryConfig(SpellRarity.UNCOMMON, 8.0D, 5));
        this.baseManaCost = 31;
        this.manaCostPerLevel = 6;
        this.baseSpellPower = 1;
        this.spellPowerPerLevel = 0;
        this.castTime = 6;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return SPELL_ID;
    }

    /** 施法音效：{@code spell.encode_pain.cast}（docs/tech/08 §3.2）。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_ENCODE_PAIN_CAST.get());
    }

    /**
     * 痛忆记录的是**自己**受的伤，所以不需要目标。
     *
     * <p>覆写后基类的 {@code checkPreCastConditions} 会恒返回 {@code true}
     * （不会因为"没瞄准生物"而拒绝施法），{@code onCast} 也会把 {@code target} 兜底成自己。
     */
    @Override
    protected boolean allowsSelfTarget() {
        return true;
    }

    // ==================================================================
    // 开启记录窗口
    // ==================================================================

    @Override
    protected void onEncode(final ServerPlayer caster, @Nullable final LivingEntity target, final int spellLevel) {
        // 已经在记录中 → 不覆盖。否则满级（窗口 10s > 冷却 8s）会把自己的记录冲掉。
        if (RECORDINGS.containsKey(caster.getUUID())) {
            return;
        }
        final int windowTicks = (WINDOW_BASE_SECONDS + (spellLevel - 1)) * 20;
        final double capPercent = Config.Spells.PAIN_MEMORY_CAP_PERCENT.get()
                + CAP_STEP_PER_LEVEL * (spellLevel - 1);
        // 反打比例 = 配置基准 + 每级增量（13 号表：0.50 → 0.66）
        final double ratio = Config.Balance.ENCODE_PAIN_BASE_REFUND.get()
                + RETALIATION_STEP_PER_LEVEL * (spellLevel - 1);
        RECORDINGS.put(caster.getUUID(),
                new Recording(windowTicks, (float) (capPercent * caster.getMaxHealth()), ratio));
        // ⭐ 2026-09-18 特效：窗口开启的"起手环"。
        //    在此之前，放完痛忆屏幕上什么都不变 —— 玩家无从判断它到底生效了没有。
        SpellFeedback.areaBurst(caster.level(), SpellFeedback.chest(caster),
                1.2D, SpellFeedback.MEMORY_MAGENTA);
    }

    // ==================================================================
    // 记录：受伤
    // ==================================================================

    /**
     * 累加记录窗口内的伤害。
     *
     * <p>用 {@code LivingHurtEvent}（护甲 / 减伤之后、吸收之前）而不是 {@code LivingDamageEvent}：
     * 设计要的是"**实际受到的伤害**"（{@code docs/04 §7} 明确写了"护盾会降低痛忆收益"），
     * 所以必须取减伤之后的值。
     */
    @SubscribeEvent
    public static void onLivingHurt(final LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        final Recording recording = RECORDINGS.get(player.getUUID());
        if (recording == null) {
            return;
        }
        final DamageSource source = event.getSource();
        if (isExcluded(source, player)) {
            return;
        }
        recording.amount = Math.min(recording.cap, recording.amount + event.getAmount());

        // ⭐⭐ 2026-09-18 逻辑重做（用户要求）：
        //    旧版是"窗口内累积 → 到期写进忆格 → 之后用复诵奉还"。
        //    新版是**即时奉还**：窗口期内挨的每一下，当场打回给攻击者。
        //
        //    为什么这样更好：旧版的链路太长（挨打 → 等窗口结束 → 写入 → 复诵），
        //    玩家在被打的时候看不到任何即时反馈，而"痛忆"的直觉应该是"你打我，我打回去"。
        //
        //    ⚠️ 递归防护：反射用的是忆海伤害类型，所以下面先判一次"这一下是不是
        //       我们自己打出去的"。否则 A 反射给 B、B 又反射回 A，会无限循环。
        if (isReflectedByUs(source)) {
            return;
        }
        final var attacker = source.getEntity();
        if (!(attacker instanceof LivingEntity living) || living == player || !living.isAlive()) {
            return;
        }
        final float reflect = event.getAmount();
        if (reflect <= 0.0F) {
            return;
        }
        final AbstractSpell self = SpellRegistry.getSpell(SPELL_ID.toString());
        if (self == null) {
            return;
        }
        // 奉还走标准学派伤害源（会被护甲减免 —— "原样"指的是数值，不是无视防御）
        MnemosyneSpell.dealSpellDamage(living, player, player, self,
                (float) (reflect * Config.Balance.ENCODE_PAIN_BASE_REFUND.get()));
        // ⭐ 2026-09-18 特效：**反打链**（自己 → 攻击者）+ 命中爆发。
        //    链路方向刻意与写入类的"抽取链"相反：那两条是"从对方拿来"，
        //    这条是"打回去"。玩家挨打时能立刻看到伤害飞向谁。
        SpellFeedback.beam(player.level(), player.getEyePosition(),
                SpellFeedback.chest(living), 16);
        SpellFeedback.hitBurst(player.level(), living, SpellFeedback.MEMORY_MAGENTA);
    }

    /** 这一下伤害是不是我们自己反射出去的（防递归）。 */
    private static boolean isReflectedByUs(final DamageSource source) {
        return source.is(com.etbs31.mnemosyne.registry.ModSchools.MEMORY_DAMAGE_TYPE)
                || source.is(com.etbs31.mnemosyne.registry.ModSchools.MEMORY_TRUE_DAMAGE_TYPE);
    }

    /** 自杀式伤害不计入（§四.7）。 */
    private static boolean isExcluded(final DamageSource source, final ServerPlayer player) {
        if (source.is(DamageTypes.GENERIC_KILL) || source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
            return true;
        }
        // 自己打自己（自伤法术、自己的箭）不计入
        return source.getEntity() == player || source.getDirectEntity() == player;
    }

    // ==================================================================
    // 结算：窗口到期
    // ==================================================================

    @SubscribeEvent
    public static void onPlayerTick(final TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        final Recording recording = RECORDINGS.get(player.getUUID());
        if (recording == null) {
            return;
        }
        if (!player.isAlive()) {
            // 记录期间死亡 → 记忆一并失去（§四.7："死亡即失忆"）
            RECORDINGS.remove(player.getUUID());
            return;
        }
        // ⭐ 2026-09-18 特效：记录窗口的**持续**提示，每 5 tick 一次。
        //    窗口是 6~10 秒的"隐式状态"：没有任何原版效果图标，玩家看不到自己在记录中。
        //    必须限频 —— 这是每 tick 都会执行的事件，逐 tick 发粒子会打爆带宽。
        if (player.tickCount % 5 == 0) {
            SpellFeedback.recordingAura(player);
        }
        if (--recording.remainingTicks > 0) {
            return;
        }
        RECORDINGS.remove(player.getUUID());
        settle(player, recording);
    }

    /**
     * 窗口结束。
     *
     * <p>⚠️ 2026-09-18：**不再写入忆格**（用户要求改成即时奉还）。
     * 所以这个方法现在只负责收尾提示 —— 伤害在 {@link #onLivingHurt} 里当场就打回去了。
     *
     * <p>⚠️ 设计后果（刻意接受）：痛忆**不再往忆格里放东西**，
     * 所以它与「复诵」经济脱钩了，名字里的"写入"是历史遗留。
     * 换来的是"挨打立刻还手"的直觉反馈。
     */
    private static void settle(final ServerPlayer player, final Recording recording) {
        if (recording.amount <= 0.0F) {
            // 窗口内没挨打 → 什么都没发生，给个提示免得玩家以为是 bug
            SpellFeedback.actionBar(player,
                    net.minecraft.network.chat.Component.translatable("mnemosyne.msg.pain_no_hit"));
            return;
        }
        SpellFeedback.actionBar(player,
                net.minecraft.network.chat.Component.translatable("mnemosyne.msg.pain_reflected"));
        if (player.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                    ModSounds.HUD_ENGRAM_FILL.get(), SoundSource.PLAYERS, 0.5F, 1.4F);
        }
    }

    // ==================================================================
    // 状态清理
    // ==================================================================

    /** 死亡（含"被复活"类模组）→ 丢弃半截记录，不要让它在重生后突然生效。 */
    @SubscribeEvent
    public static void onPlayerClone(final PlayerEvent.Clone event) {
        if (event.isWasDeath()) {
            RECORDINGS.remove(event.getOriginal().getUUID());
        }
    }

    /** 登出 → 丢弃记录，避免 UUID 复用导致串数据（与 {@code MnemosyneData} 的做法一致）。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(final PlayerEvent.PlayerLoggedOutEvent event) {
        RECORDINGS.remove(event.getEntity().getUUID());
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
    // 记录状态
    // ==================================================================

    /** 一个正在进行的记录窗口。 */
    private static final class Recording {
        private int remainingTicks;
        private final float cap;

        /**
         * 本次窗口的反打比例。
         *
         * <p>⚠️ 必须在<b>开窗口时</b>就存下来：{@code LivingHurtEvent} 里拿不到
         * {@code spellLevel}（事件只带伤害与实体），而反打比例是按<b>施法时的等级</b>算的。
         * 若改为"挨打时再查当前等级"，玩家在窗口内升级就会临时变强 —— 与"快照"语义不符。
         */
        private final double ratio;

        private float amount;

        private Recording(final int remainingTicks, final float cap, final double ratio) {
            this.remainingTicks = remainingTicks;
            this.cap = cap;
            this.ratio = ratio;
        }
    }
}
