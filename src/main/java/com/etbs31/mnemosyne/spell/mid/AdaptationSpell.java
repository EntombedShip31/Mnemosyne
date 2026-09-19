package com.etbs31.mnemosyne.spell.mid;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.MnemosyneSpell;
import com.etbs31.mnemosyne.util.SpellFeedback;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 铭刻适应 Adaptation —— adaptation（Rare / 瞬发）。
 *
 * <p><b>原型</b>：魔虚罗的「适应」—— 挨过一次就记住，下次不怕。
 * 记忆母题里最朴素也最硬的一条：<b>记忆就是学习</b>。
 *
 * <p><b>机制</b>：施法后进入 <b>8 秒铭刻期</b>，记录这期间自己受到的每一次伤害
 * <b>属于哪种类型</b>（按 1.20.1 {@code DamageSource#type()} 的 msgId 分组累加）。
 * 铭刻期结束结算：
 * <ul>
 *   <li>某单一类型占比 ≥ {@link #FOCUS_THRESHOLD} → 对该类型 <b>专注减伤</b>（30%~50%）</li>
 *   <li>否则 → 对期间出现过的<b>所有类型</b>各 <b>广谱减伤</b>（15%~23%）</li>
 * </ul>
 * 减伤持续 30~42 秒。
 *
 * <p><b>⭐⭐ 决策点</b>：铭刻期<b>必须主动挨打</b>才有收益 —— 躲得越干净，收益越低。
 * 这是它最反直觉的地方：别的减伤技是"躲开伤害"，这个是"吃下伤害换知识"。
 * 玩家还要在<b>专注 vs 广谱</b>之间选：打单一 Boss 时故意只吃它的主伤害类型换 30%；
 * 打杂兵群时尽量多挨几种类型换广谱 15%。
 *
 * <p><b>⭐⭐ 为什么权威数据不放进 {@code MobEffect}</b>
 * <br>项目红线：效果只能是<b>派生缓存</b> —— 牛奶桶 / 净化会清空全部效果。
 * 如果减伤挂在效果上，一桶牛奶就能把"学过的东西"抹掉，这在语义上是荒谬的。
 * 所以这里的权威数据是 {@link #ADAPTATIONS} 这张内存表，
 * <b>整个法术不注册任何状态效果</b> —— 没有效果，牛奶就没有可清的东西。
 *
 * <p>⚠️ 代价：服务器重启会丢掉正在生效的适应。这是刻意接受的 ——
 * 它是一个 30~42 秒的<b>战斗内</b>增益，跨重启保留没有意义，
 * 而"把半截记录写进存档、重启后意外生效"才是更危险的（同 {@code EncodePainSpell} 的取舍）。
 *
 * <table border="1">
 *   <tr><th>等级</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th><th>6</th><th>7</th><th>8</th></tr>
 *   <tr><td>铭刻期</td><td>8s</td><td>9s</td><td>9s</td><td>10s</td><td>10s</td><td>11s</td><td>11s</td><td>12s</td></tr>
 *   <tr><td>减伤持续</td><td>30s</td><td>32s</td><td>33s</td><td>35s</td><td>37s</td><td>39s</td><td>40s</td><td>42s</td></tr>
 *   <tr><td>专注</td><td>30.0%</td><td>32.9%</td><td>35.7%</td><td>38.6%</td><td>41.4%</td><td>44.3%</td><td>47.1%</td><td>50.0%</td></tr>
 *   <tr><td>广谱</td><td>15.0%</td><td>16.1%</td><td>17.3%</td><td>18.4%</td><td>19.6%</td><td>20.7%</td><td>21.9%</td><td>23.0%</td></tr>
 * </table>
 *
 * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「铭刻适应」（唯一事实来源，最大等级 8）。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class AdaptationSpell extends MnemosyneSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "adaptation");

    /**
     * 各等级的铭刻期时长（秒，index = level − 1）。
     *
     * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「铭刻适应」的"铭刻（秒）"列：
     * L1~L8 = <b>8 / 9 / 9 / 10 / 10 / 11 / 11 / 12</b>。
     */
    private static final int[] INSCRIBE_SECONDS = {8, 9, 9, 10, 10, 11, 11, 12};

    /**
     * 各等级的减伤持续（秒，index = level − 1）。
     *
     * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「铭刻适应」的"增益（秒）"列：
     * L1~L8 = <b>30 / 32 / 33 / 35 / 37 / 39 / 40 / 42</b>。
     */
    private static final int[] BUFF_SECONDS = {30, 32, 33, 35, 37, 39, 40, 42};

    /**
     * 各等级的专注减伤（0~1，index = level − 1）。
     *
     * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「铭刻适应」的"专精减伤"列：
     * L1~L8 = <b>0.300 / 0.329 / 0.357 / 0.386 / 0.414 / 0.443 / 0.471 / 0.500</b>。
     */
    private static final double[] FOCUS_REDUCTION = {0.300D, 0.329D, 0.357D, 0.386D, 0.414D, 0.443D, 0.471D, 0.500D};

    /**
     * 各等级的广谱减伤（0~1，index = level − 1）。
     *
     * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「铭刻适应」的"泛用减伤"列：
     * L1~L8 = <b>0.150 / 0.161 / 0.173 / 0.184 / 0.196 / 0.207 / 0.219 / 0.230</b>。
     */
    private static final double[] BROAD_REDUCTION = {0.150D, 0.161D, 0.173D, 0.184D, 0.196D, 0.207D, 0.219D, 0.230D};

    /** 判定"专注"的占比门槛。 */
    private static final double FOCUS_THRESHOLD = 0.60D;

    /** 铭刻期特效的节流间隔（tick）。{@code recordingAura} 由调用方限频，每 tick 打会刷屏。 */
    private static final int AURA_INTERVAL = 10;

    /** 正在铭刻中的玩家。键 = 玩家 UUID。 */
    private static final Map<UUID, Inscription> INSCRIPTIONS = new ConcurrentHashMap<>();

    /** 已结算、正在生效的适应。键 = 玩家 UUID。 */
    private static final Map<UUID, Adaptation> ADAPTATIONS = new ConcurrentHashMap<>();

    /** 一段铭刻期。 */
    private static final class Inscription {
        private final int level;
        private final long endTick;
        private final Map<String, Float> byType = new HashMap<>();
        private long nextAuraTick;

        private Inscription(final int level, final long endTick, final long now) {
            this.level = level;
            this.endTick = endTick;
            this.nextAuraTick = now;
        }
    }

    /** 一次已结算的适应。 */
    private static final class Adaptation {
        /** null = 广谱（对所有 {@link #types} 生效）；非 null = 专注（只对这一种生效）。 */
        private final String focusType;
        private final double focusReduction;
        private final double broadReduction;
        private final java.util.Set<String> types;
        private final long expireTick;

        private Adaptation(final String focusType, final double focusReduction,
                           final double broadReduction, final java.util.Set<String> types,
                           final long expireTick) {
            this.focusType = focusType;
            this.focusReduction = focusReduction;
            this.broadReduction = broadReduction;
            this.types = types;
            this.expireTick = expireTick;
        }

        /** 这一类型能减多少（0 = 不减）。 */
        private double reductionFor(final String type) {
            if (focusType != null) {
                return focusType.equals(type) ? focusReduction : 0.0D;
            }
            return types.contains(type) ? broadReduction : 0.0D;
        }
    }

    public AdaptationSpell() {
        super(memoryConfig(SpellRarity.RARE, 30.0D, 8));
        this.baseManaCost = 44;
        this.manaCostPerLevel = 9;
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

    /** 施法音效复用「写入 · 痛忆」—— 两者都是"记录自己受的伤"，意象一致。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_ENCODE_PAIN_CAST.get());
    }

    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide && entity instanceof ServerPlayer caster) {
            begin(caster, spellLevel);
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }

    /**
     * 开启铭刻期。
     *
     * <p>⚠️ 已经在铭刻中就直接返回，<b>不覆盖</b>：满级时铭刻期 12s 已经长于冷却 30s 的三分之一，
     * 允许覆盖会让玩家用第二次施法把第一次的记录冲掉（同 {@code EncodePainSpell} 的处理）。
     */
    private static void begin(final ServerPlayer caster, final int spellLevel) {
        final UUID id = caster.getUUID();
        if (INSCRIPTIONS.containsKey(id)) {
            return;
        }
        final int index = clampLevelIndex(spellLevel);
        final long now = caster.getServer().overworld().getGameTime();
        INSCRIPTIONS.put(id, new Inscription(spellLevel, now + (long) INSCRIBE_SECONDS[index] * 20L, now));
        // 起手环：告诉玩家"开始记录了"。这一圈微光是铭刻期唯一的可见凭证 ——
        // 没有它，玩家放完法术到第一次挨打之间是完全没有反馈的。
        SpellFeedback.areaBurst(caster.level(), SpellFeedback.chest(caster),
                1.2D, SpellFeedback.MEMORY_INDIGO);
        SpellFeedback.actionBar(caster, Component.translatable("mnemosyne.adaptation.start")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    // ==================================================================
    // 记录与结算
    // ==================================================================

    /**
     * 铭刻期内累加伤害；适应生效期内做减伤。
     *
     * <p>用 {@code LivingHurtEvent}（护甲之后、吸收之前）而不是 {@code LivingDamageEvent}：
     * 要的是"实际受到的伤害"，与「写入 · 痛忆」保持同一口径。
     */
    @SubscribeEvent
    public static void onLivingHurt(final LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        final DamageSource source = event.getSource();
        if (isExcluded(source, player)) {
            return;
        }
        final String type = source.type().msgId();
        final UUID id = player.getUUID();

        final Inscription inscription = INSCRIPTIONS.get(id);
        if (inscription != null) {
            inscription.byType.merge(type, event.getAmount(), Float::sum);
        }

        final Adaptation adaptation = ADAPTATIONS.get(id);
        if (adaptation == null) {
            return;
        }
        final double reduction = adaptation.reductionFor(type);
        if (reduction <= 0.0D) {
            return;
        }
        event.setAmount(event.getAmount() * (float) (1.0D - reduction));
        // ⭐ 减伤成功时给一次贴身的靛蓝涟漪 —— 否则玩家根本不知道"适应"生效了。
        //    它只在真的减到伤时触发，所以不会被普通挨打刷屏。
        SpellFeedback.areaBurst(player.level(), SpellFeedback.chest(player),
                0.9D, SpellFeedback.MEMORY_INDIGO);
    }

    /** 自杀式 / 虚空 / 自己造成的伤害不计入。 */
    private static boolean isExcluded(final DamageSource source, final ServerPlayer player) {
        if (source.is(DamageTypes.GENERIC_KILL) || source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
            return true;
        }
        return source.getEntity() == player || source.getDirectEntity() == player;
    }

    @SubscribeEvent
    public static void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (INSCRIPTIONS.isEmpty() && ADAPTATIONS.isEmpty()) {
            return;
        }
        final long now = event.getServer().overworld().getGameTime();

        for (final Iterator<Map.Entry<UUID, Inscription>> it = INSCRIPTIONS.entrySet().iterator();
             it.hasNext(); ) {
            final Map.Entry<UUID, Inscription> entry = it.next();
            final Inscription inscription = entry.getValue();
            if (now >= inscription.endTick) {
                it.remove();
                settle(entry.getKey(), inscription, now);
                continue;
            }
            if (now >= inscription.nextAuraTick) {
                inscription.nextAuraTick = now + AURA_INTERVAL;
                final ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    SpellFeedback.recordingAura(player);
                }
            }
        }

        // 过期的适应清掉。⚠️ 不清会在内存里堆死玩家 —— 这不是"缓存"，没有别的刷新入口。
        ADAPTATIONS.values().removeIf(adaptation -> now >= adaptation.expireTick);
    }

    /** 铭刻期结束：结算成专注或广谱，并通知玩家。 */
    private static void settle(final UUID id, final Inscription inscription, final long now) {
        final ServerPlayer player = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer()
                .getPlayerList().getPlayer(id);
        if (player == null) {
            return;
        }
        final int index = clampLevelIndex(inscription.level);
        float total = 0.0F;
        for (final float amount : inscription.byType.values()) {
            total += amount;
        }
        // ⭐ 一次都没挨到 → 没有可学的东西。这是设计的一部分："躲得干净 = 没收益"。
        if (total <= 0.0F || inscription.byType.isEmpty()) {
            SpellFeedback.actionBar(player, Component.translatable("mnemosyne.adaptation.nothing")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        String dominant = null;
        float dominantAmount = 0.0F;
        for (final Map.Entry<String, Float> entry : inscription.byType.entrySet()) {
            if (entry.getValue() > dominantAmount) {
                dominantAmount = entry.getValue();
                dominant = entry.getKey();
            }
        }
        final boolean focused = dominant != null && dominantAmount / total >= FOCUS_THRESHOLD;
        final long expire = now + (long) BUFF_SECONDS[index] * 20L;
        if (focused) {
            ADAPTATIONS.put(id, new Adaptation(dominant, FOCUS_REDUCTION[index], 0.0D,
                    java.util.Set.of(dominant), expire));
            SpellFeedback.actionBar(player, Component.translatable("mnemosyne.adaptation.focus",
                            damageTypeName(dominant), percent(FOCUS_REDUCTION[index]))
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        } else {
            ADAPTATIONS.put(id, new Adaptation(null, 0.0D, BROAD_REDUCTION[index],
                    java.util.Set.copyOf(inscription.byType.keySet()), expire));
            SpellFeedback.actionBar(player, Component.translatable("mnemosyne.adaptation.broad",
                            percent(BROAD_REDUCTION[index]))
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        SpellFeedback.areaBurst(player.level(), SpellFeedback.chest(player),
                1.6D, SpellFeedback.MEMORY_INDIGO);
    }

    /** 伤害类型的显示名：原版的 {@code death.attack.<msgId>} 键覆盖绝大多数类型。 */
    private static Component damageTypeName(final String msgId) {
        return Component.translatable("death.attack." + msgId);
    }

    private static String percent(final double value) {
        return Math.round(value * 100.0D) + "%";
    }

    private static int clampLevelIndex(final int spellLevel) {
        return Math.max(1, Math.min(INSCRIBE_SECONDS.length, spellLevel)) - 1;
    }
}
