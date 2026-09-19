package com.etbs31.mnemosyne.spell.high;

import com.etbs31.mnemosyne.Config;
import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.EngramRelease;
import com.etbs31.mnemosyne.spell.base.MnemosyneSpell;
import com.etbs31.mnemosyne.util.SpellFeedback;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 既视感 Deja Vu —— deja_vu。
 *
 * <p><b>归属</b>：WS-D3（本文件是 WS-A 建立的 stub，WS-D3 只填 onCast 的方法体）。
 *
 * <p><b>数值来源</b>：docs/tech/04_法术等级强度表.md 第四节第 16 条。
 * 构造器里那 5 个字段<b>逐字未动</b>，只把 {@code extends AbstractSpell} 换成了
 * {@link MnemosyneSpell}。
 *
 * <p><b>本法术做什么</b>（§四.16）：把**自己在过去 N 秒内放过的所有法术按原顺序重放一遍**。
 * 它是记忆流派的"连招兑现"——攒一段爆发窗口，然后用一次既视感全部打出去。
 *
 * <table border="1">
 *   <tr><th>等级</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th></tr>
 *   <tr><td>时间窗口</td><td>3s</td><td>3.5s</td><td>4s</td><td>5s</td><td><b>6s</b></td></tr>
 *   <tr><td>复现威力</td><td>80%</td><td>82%</td><td>85%</td><td>88%</td><td><b>95%</b></td></tr>
 * </table>
 *
 * <p>§四.16 的三条硬约束：<b>0 法力、0 冷却</b>（靠 {@code CastSource.NONE} 达成）、
 * <b>不消耗忆格</b>（本类根本不碰忆格数据）、<b>不能复现自身</b>（记录与重放都跳过 {@code deja_vu}）。
 *
 * <p><b>⭐ 为什么历史记录只存在内存里</b>
 * <br>与 {@code EncodePainSpell} 的记录窗口、{@code AmnesiaSpell} 的发呆表同一个理由：
 * 窗口最长 6 秒，服务器重启时丢掉它**比**把半截历史写进存档再在重启后突然重放安全得多。
 *
 * <p><b>⭐ 为什么要防重入（{@code REPLAYING}）</b>
 * <br>重放走的是 {@code AbstractSpell.castSpell}，它会 post {@code SpellOnCastEvent} ——
 * 也就是本类的记录器会被自己触发的重放再喂一遍。不挡住的话：
 * <ol>
 *   <li>历史会被重放内容污染（下一次既视感把上一次的重放又放一遍，指数级膨胀）；</li>
 *   <li>在极端情况下会形成"重放 → 记录 → 再重放"的自激循环。</li>
 * </ol>
 * 用一个玩家 UUID 集合在重放期间屏蔽记录，是最简单且无副作用的做法。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class DejaVuSpell extends MnemosyneSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "deja_vu");

    /**
     * 各等级的记忆回溯窗口（<b>tick</b>，index = level - 1）。
     *
     * <p>⭐⭐ 2026-09-18 <b>单位纠错</b>（依据 {@code docs/tech/13_数值总表.md} §二 既视感表）：
     * 13 号表的「记录窗口（秒）」列写的是 <b>60 / 72 / 84 / 96 / 108 / 120 秒</b>。
     * 而这里原先存的是 {@code {60, 70, 80, 100, 120}} 且被当成 tick 用
     * —— 也就是实际只有 <b>3 ~ 6 秒</b>，与文档差了整整 20 倍。
     *
     * <p>这不是"改数值"，是<b>修单位 bug</b>：窗口是拿
     * {@code now - cast.tick()}（两个 {@code getGameTime()} 之差，单位 tick）比的，
     * 所以存秒就必须 ×20。原值 60/70/80/100/120 本来就是照着"秒"那一列抄进来的，
     * 抄的时候漏了换算。
     *
     * <p>顺带说明为什么这个 bug 该修而不是该保留：既视感冷却 30 秒，
     * 若窗口只有 3 秒，玩家几乎不可能在"刚刚" cast 过东西的 3 秒内再开既视感
     * —— 回溯列表恒空，法术等同于废掉。改成 60~120 秒后它才真的能用。
     */
    private static final int[] WINDOW_TICKS = {1200, 1440, 1680, 1920, 2160, 2400};

    /**
     * 各等级的复现威力（index = level - 1），13 号表：0.800 → 0.950。
     *
     * <p>⚠️ 这个硬编码数组**不直接参与计算**，实际威力由
     * {@link #powerScale(int, int)} 从配置算出（设计文档 v2 §9.4 要求
     * "记录越多、重演越低"）。保留它是为了让"设计曲线"可查 ——
     * {@link #POWER_PER_LEVEL} 就是照着它反推出来的（见那里的注释）。
     */
    private static final float[] LEGACY_POWER_SCALE =
            {0.800F, 0.830F, 0.860F, 0.890F, 0.920F, 0.950F};

    /**
     * 等级带来的威力成长（相对增量）。
     *
     * <p>⭐ 2026-09-18：0.05 → <b>0.0375</b>。反推依据：13 号表满级 0.950 / 起始 0.800 = 1.1875，
     * 摊到 5 级增量 = {@code (1.1875 − 1) / 5 = 0.0375}。旧值 0.05 会让 6 级长到
     * {@code 0.80 × 1.25 = 1.0}（比表的 0.95 高出 5 个百分点）。
     */
    private static final double POWER_PER_LEVEL = 0.0375D;

    /**
     * 本次复现的威力系数 —— <b>设计文档 v2 §9.4 的公式</b>。
     *
     * <pre>
     * power = dejaVuBasePotency × (1 + 0.05 × (等级 − 1)) × (1 − dejaVuPerStackPenalty × (已记录数 − 1))
     * </pre>
     *
     * <p>为什么要有后半段的衰减：旧版是"记录越多、总收益越高"（8 条 × 80% = 640%），
     * 那让「既视感」变成"憋得越久越强"的蓄力技，与"重演刚刚发生的事"这个语义相反。
     * 加了衰减之后，**立刻重演**（记录少）威力最高，符合设计意图。
     *
     * @param historySize 当前记忆库里的条数（≥ 1）
     */
    private static float powerScale(final int spellLevel, final int historySize) {
        final double base = Config.Balance.DEJA_VU_BASE_POTENCY.get()
                * (1.0D + POWER_PER_LEVEL * (spellLevel - 1));
        final double decay = 1.0D - Config.Balance.DEJA_VU_PER_STACK_PENALTY.get()
                * Math.max(0, historySize - 1);
        return (float) Math.max(0.05D, base * Math.max(0.10D, decay));
    }

    /**
     * 每个玩家保留的历史条数上限。
     *
     * <p>只用于防止"窗口内刷几百次瞬发法术"把内存顶爆。
     *
     * <p>⭐ 2026-09-18：窗口从 3~6 秒修正为 <b>60~120 秒</b>后，这个上限的意义变了 ——
     * 它现在是"回溯列表最多能装几条"，而不是"连招长度"。窗口拉长后一次战斗里
     * 很容易攒出十几条，8 条仍然是个合理的内存护栏（列表只存 id + 等级 + tick，极轻）。
     */
    private static final int MAX_HISTORY = 8;

    /** 一次施法的记录。 */
    private record Cast(ResourceLocation spellId, int spellLevel, long tick) {}

    private static final Map<UUID, List<Cast>> HISTORY = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> REPLAYING = ConcurrentHashMap.newKeySet();

    public DejaVuSpell() {
        super(memoryConfig(SpellRarity.EPIC, 30.0D, 6));
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

    /** 施法音效：{@code spell.deja_vu.cast}（docs/tech/08 §3.2）。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_DEJA_VU_CAST.get());
    }

    // ==================================================================
    // 落地
    // ==================================================================

    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide && entity instanceof ServerPlayer caster) {
            replay(caster, spellLevel);
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }

    private void replay(final ServerPlayer caster, final int spellLevel) {
        final int index = clampLevelIndex(spellLevel);
        final long now = nowTick(caster);
        final List<Cast> history = HISTORY.get(caster.getUUID());
        if (history == null || history.isEmpty()) {
            caster.displayClientMessage(Component.translatable("mnemosyne.msg.deja_vu_empty"), true);
            return;
        }
        final List<Cast> window;
        synchronized (history) {
            window = new ArrayList<>(history);
        }
        // 按时间顺序重放窗口内的施法（§四.16："按原顺序"）
        final List<Cast> all = new ArrayList<>();
        for (final Cast cast : window) {
            if (now - cast.tick() <= WINDOW_TICKS[index]) {
                all.add(cast);
            }
        }
        if (all.isEmpty()) {
            caster.displayClientMessage(Component.translatable("mnemosyne.msg.deja_vu_empty"), true);
            return;
        }
        // ⭐ 2026-09-18（设计文档 v2 §一）：**记录容量 = 法术等级**（1 级 1 个，5 级 5 个）。
        //    只重演**最近的 spellLevel 条**。
        //    ⚠️ 为什么在重演侧限制而不是在记录侧：记录发生在别人施法时
        //      （{@code onSpellCast}），那一刻**不知道玩家身上既视感的等级** ——
        //      他可能根本没学过这个法术。在重演侧取最近 N 条，玩法效果相同且不需要额外状态。
        final List<Cast> inWindow = all.size() > spellLevel
                ? new ArrayList<>(all.subList(all.size() - spellLevel, all.size()))
                : all;

        REPLAYING.add(caster.getUUID());
        try {
            // 视觉（2026-09-18 补）：重演是在**同一 tick 内**发生的，屏幕上原本只有
            // 一行动作栏文字 —— 玩家完全看不出"刚才连了几发"。
            // 先在施法者身上荡一圈"唤起"，再让**每次成功重演各荡一圈**（见 replayPulse）。
            SpellFeedback.areaBurst(caster.level(), caster.position().add(0.0D, 1.0D, 0.0D),
                    1.5D, SpellFeedback.MEMORY_INDIGO);
            // ⭐ 记录越多、单发威力越低（设计文档 v2 §9.4）。
            //    逐条按当前记忆库剩余条数计算，所以第一条最接近满威力、最后一条最低。
            int replayed = 0;
            int remaining = inWindow.size();
            for (final Cast cast : inWindow) {
                final float scale = powerScale(spellLevel, Math.max(1, remaining));
                if (EngramRelease.releaseSpell(caster, cast.spellId(), cast.spellLevel(), scale)) {
                    SpellFeedback.replayPulse(caster.level(), caster, replayed);
                    replayed++;
                }
                remaining--;
            }
            final float shown = powerScale(spellLevel, Math.max(1, inWindow.size()));
            caster.displayClientMessage(Component.translatable("mnemosyne.msg.deja_vu_ok",
                    replayed, Math.round(shown * 100.0F)), true);

            // ⭐⭐ 2026-09-18「用过即焚」（设计文档 v2 §一）：
            //    被重演过的法术**从记忆库里移除**，必须重新施放才能再被记录。
            //    为什么必须这样：否则"放一次大招 → 既视感无限重演"会成为
            //    本流派最强的循环（每次只花一次法力），而且与"重演刚刚发生的事"
            //    这个语义完全相反 —— 记忆被用掉就该消失，这才是忆海的基调。
            synchronized (history) {
                history.removeAll(inWindow);
            }
        } finally {
            REPLAYING.remove(caster.getUUID());
        }
    }

    // ==================================================================
    // 记录
    // ==================================================================

    /**
     * 记录玩家成功施放的法术。
     *
     * <p>与 {@code EncodeSpellSpell.onSpellCast} 用同一个事件 —— 两处各自维护一份记录是
     * 刻意的：术忆只需要"最近一条"，既视感需要"最近 6 秒的一串"，
     * 共用一份数据只会让两边的清理规则互相打架。
     */
    @SubscribeEvent
    public static void onSpellCast(final SpellOnCastEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 防重入：重放期间不记录（见类注释）
        if (REPLAYING.contains(player.getUUID())) {
            return;
        }
        final String spellId = event.getSpellId();
        if (spellId == null || spellId.isEmpty() || SPELL_ID.toString().equals(spellId)) {
            // 不能复现自身（§四.16）→ 也不记录自身，否则重放时会自我放大
            return;
        }
        final ResourceLocation id = ResourceLocation.tryParse(spellId);
        if (id == null) {
            return;
        }
        final List<Cast> history = HISTORY.computeIfAbsent(player.getUUID(),
                key -> java.util.Collections.synchronizedList(new ArrayList<>()));
        synchronized (history) {
            history.add(new Cast(id, Math.max(1, event.getSpellLevel()), nowTick(player)));
            while (history.size() > MAX_HISTORY) {
                history.remove(0);
            }
        }
    }

    /** 登出 → 清历史，避免 UUID 复用把别人的连招带给新玩家。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(final PlayerEvent.PlayerLoggedOutEvent event) {
        HISTORY.remove(event.getEntity().getUUID());
        REPLAYING.remove(event.getEntity().getUUID());
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static long nowTick(final ServerPlayer player) {
        return player.server.overworld().getGameTime();
    }

    private static int clampLevelIndex(final int spellLevel) {
        return Math.max(1, Math.min(WINDOW_TICKS.length, spellLevel)) - 1;
    }

    /** 供调试 / WS-I：当前历史里有几条。 */
    public static int historySize(final ServerPlayer player) {
        final List<Cast> history = HISTORY.get(player.getUUID());
        if (history == null) {
            return 0;
        }
        synchronized (history) {
            return history.size();
        }
    }

    /** 供调试：清掉历史。 */
    public static void clearHistory(final ServerPlayer player) {
        HISTORY.remove(player.getUUID());
    }

    /** 供 WS-I：把窗口内的法术列成可显示的字符串（给选择/预览界面用）。 */
    public static List<ResourceLocation> windowContents(final ServerPlayer player, final int spellLevel) {
        final List<Cast> history = HISTORY.get(player.getUUID());
        if (history == null) {
            return List.of();
        }
        final long now = nowTick(player);
        final int window = WINDOW_TICKS[clampLevelIndex(spellLevel)];
        final List<ResourceLocation> out = new ArrayList<>();
        synchronized (history) {
            for (final Cast cast : history) {
                if (now - cast.tick() <= window) {
                    out.add(cast.spellId());
                }
            }
        }
        return out;
    }
}
