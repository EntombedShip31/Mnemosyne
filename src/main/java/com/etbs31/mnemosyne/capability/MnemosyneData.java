package com.etbs31.mnemosyne.capability;

import com.etbs31.mnemosyne.Config;
import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.item.MnemonicRobeItem;
import com.etbs31.mnemosyne.oblivion.EngramEffect;
import com.etbs31.mnemosyne.registry.ModEffects;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.util.SpellFeedback;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 忆格（Engram）数据层 —— 存储 / 腐坏 / 共鸣 / 同步。
 *
 * <p><b>文件归属</b>：WS-B 忆格系统。
 *
 * <p><b>契约（冻结，{@code docs/tech/11} §六）</b> —— 下游 WS-D / WS-E / WS-I 全部依赖：
 * <pre>{@code
 * MnemosyneData.getUsedEngrams(Player)          : int
 * MnemosyneData.getMaxEngrams(Player)           : int      // 基础 3，上限 5
 * MnemosyneData.addEngram(Player, EngramEntry)  : boolean
 * MnemosyneData.releaseEngram(Player, int)      : EngramEntry
 * MnemosyneData.clearAll(Player)                : void
 * MnemosyneData.getResonanceMultiplier(Player)  : double   // 1 + 0.20 × used
 * MnemosyneData.getManaPenalty(Player)          : double   // 1 + 0.10 × used
 * MnemosyneData.getCastSpeedPenalty(Player)     : double   // 1 - 0.08 × used
 * MnemosyneData.tick(ServerPlayer)              : void
 * }</pre>
 *
 * <p><b>⭐ 为什么用 {@code player.getPersistentData()} 而不是 {@code AttachmentType}</b>：
 * {@code AttachmentType} 是 **NeoForge 1.21+** 的 API，**1.20.1 Forge 没有**。
 * 1.20.1 的正确存储就是 Forge 给 {@code Entity} 打的 {@code getPersistentData()}，
 * 它会被写进实体的 {@code ForgeData} NBT 并随存档持久化。
 *
 * <p><b>⭐ NBT 结构（与 {@code docs/tech/03} §6.2 的差异，已实测后修正）</b>：
 * 文档写的是"定长数组 + 空位填 null"，但 **NBT 的 ListTag 装不了 null**
 * （只能塞空 CompoundTag，然后到处判空）。实际实现用**紧凑列表**：
 * <pre>
 * mnemosyne_engram: {
 *   slots:     [ {type:"spell", spellId:"...", level:3, power:1.0, expire:2400}, ... ]  // 会腐坏的
 *   permSlots: [ ... ]                                                                  // 记忆掠夺写的，不腐坏
 *   tempExpire:[ 2400L, 2400L ]                                                         // 每个临时忆格的到期刻
 * }
 * </pre>
 * 索引语义不变（0-based，就是玩家在 HUD 上看到的第 1/2/3 格）。
 *
 * <p><b>⭐ 服务端 / 客户端的两套来源</b>：
 * 忆格数据**只存在服务端**（玩家 NBT，随存档持久化）；客户端没有副本。
 * 需要让玩家看到忆格状态时走 {@link #notifyEngramChange}（动作栏提示），
 * 不要试图在客户端查询数据。
 * 因为玩家的 {@code getPersistentData()} **不会自动同步到自己的客户端**，
 * HUD 想要显示忆格就必须靠我们自己的包。所有公开方法都会自动路由到正确的一侧。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MnemosyneData {

    private MnemosyneData() {}

    /** NBT 根键。改了会导致老存档的忆格全部丢失，**不要改**。 */
    public static final String NBT_ROOT = "mnemosyne_engram";

    private static final String KEY_SLOTS = "slots";
    private static final String KEY_PERM_SLOTS = "permSlots";
    private static final String KEY_TEMP_EXPIRE = "tempExpire";

    /**
     * 忆格增益（{@code 忆格扩张} / {@code 忆海} / {@code 记忆掠夺 L5} 施加）的 NBT 键。
     *
     * <p>结构：{@code {until, resonance, castScale, manaScale, permExempt}}。
     * 全部字段都是"可选的更好值"——重复施加时取更有利的一方，见 {@link #applyEngramBuff}。
     */
    private static final String KEY_BUFF = "mnemosyne_engram_buff";
    private static final String KEY_BUFF_UNTIL = "until";
    private static final String KEY_BUFF_RESONANCE = "resonance";
    private static final String KEY_BUFF_CAST = "castScale";
    private static final String KEY_BUFF_MANA = "manaScale";
    private static final String KEY_BUFF_PERM = "permExempt";

    /** {@link #applyEngramBuff} 的"不改写共鸣"哨兵值。 */
    public static final double NO_RESONANCE_OVERRIDE = -1.0D;

    /**
     * 「永久记忆不计入共鸣惩罚」的**永久**标记（{@code 记忆掠夺} 满级）。
     *
     * <p>为什么不复用 {@link #applyEngramBuff}：那条路径带到期时间，
     * 而这个效果按 {@code docs/tech/04} §四.14 是**永久**的
     * （"永久记忆不计入共鸣惩罚"—— 只要记忆还在，豁免就还在）。
     * 用带时限的 buff 表达"永久"只能塞一个天文数字，那是骗自己。
     */
    private static final String KEY_PERM_EXEMPT = "mnemosyne_perm_exempt";

    /** 腐坏检查间隔：每 20 tick（1 秒）扫一次，不要每 tick 扫。 */
    private static final int DECAY_CHECK_INTERVAL = 20;

    /** 上次显示过的 (used, max) 打包值（高 32 位 used、低 32 位 max）。 */
    private static final String KEY_SLOT_DISPLAY = "mnemosyne_slot_display";

    // ⚠️ 2026-09-17：原来的 CLIENT_CACHE（客户端缓存）与 DIRTY（脏标记）已删除。
    //    它们存在的唯一目的是喂 EngramHudOverlay 那个自定义 GUI。
    //    GUI 删除后，忆格状态改走"动作栏提示"（见 notifyEngramChange），
    //    不再需要 S2C 同步包、也不再需要客户端缓存 —— 少一套状态、少一类不同步 bug。

    // ==================================================================
    // 契约 API
    // ==================================================================

    /** 已占用的忆格数（含永久记忆）。 */
    public static int getUsedEngrams(final Player player) {
        return readAll(player).size();
    }

    /**
     * 忆格上限 = min(基础 + 装备加成, 硬上限) + 当前有效的临时忆格。
     *
     * <p>基础取 {@code engram.baseSlots}（默认 3），硬上限取 {@code engram.maxSlots}（默认 5）。
     */
    public static int getMaxEngrams(final Player player) {
        final int base = Math.min(
                Config.Engram.BASE_SLOTS.get() + getEquipmentSlotBonus(player),
                Config.Engram.MAX_SLOTS.get());
        return base + getTempSlotCount(player);
    }

    /**
     * 写入一条记忆。
     *
     * @return 忆格已满时返回 {@code false}（**不写入**，调用方自行决定是否扣法力）
     */
    public static boolean addEngram(final Player player, final EngramEntry entry) {
        return addEngram(player, entry, false);
    }

    /**
     * 写入一条记忆（带永久标记）。
     *
     * @param permanent {@code true} = 写进 {@code permSlots}，**永不腐坏**（记忆掠夺用）
     */
    public static boolean addEngram(final Player player, final EngramEntry entry, final boolean permanent) {
        if (entry == null || getUsedEngrams(player) >= getMaxEngrams(player)) {
            return false;
        }
        if (player.level().isClientSide) {
            // 客户端不做权威写入；等同步包覆盖。这样能避免"客户端先显示、服务端拒绝"的闪烁。
            return false;
        }
        final CompoundTag root = writableRootTag(player);
        final ListTag list = root.getList(permanent ? KEY_PERM_SLOTS : KEY_SLOTS, Tag.TAG_COMPOUND);
        list.add(entry.save());
        root.put(permanent ? KEY_PERM_SLOTS : KEY_SLOTS, list);
        markDirty(player);
        return true;
    }

    /**
     * 释放（取出并清空）指定索引的记忆。
     *
     * @return 该格的记忆；索引越界或该格为空时返回 {@code null}
     */
    @Nullable
    public static EngramEntry releaseEngram(final Player player, final int index) {
        if (player.level().isClientSide) {
            return null;
        }
        final CompoundTag root = writableRootTag(player);
        // 先找 slots，再找 permSlots —— 顺序与 getEngrams() 一致
        EngramEntry released = removeAt(root, KEY_SLOTS, index);
        if (released == null) {
            released = removeAt(root, KEY_PERM_SLOTS, index - sizeOf(root, KEY_SLOTS));
        }
        if (released != null) {
            markDirty(player);
        }
        return released;
    }

    /** 清空全部忆格（死亡、管理员指令）。 */
    public static void clearAll(final Player player) {
        if (player.level().isClientSide) {
            return;
        }
        final CompoundTag root = writableRootTag(player);
        root.remove(KEY_SLOTS);
        root.remove(KEY_PERM_SLOTS);
        root.remove(KEY_TEMP_EXPIRE);
        markDirty(player);
    }

    /**
     * 共鸣加成倍率：{@code 1 + resonancePerSlot × used}。
     *
     * <p>{@code resonancePerSlot} 默认取配置（0.20），但可以被
     * {@link #applyEngramBuff} 的临时增益覆盖（{@code 忆海} 领域内 +30%~+40%）。
     * 忆者法袍 4 件套的 +5% 加在**这个基准之上**。
     */
    public static double getResonanceMultiplier(final Player player) {
        final Buff buff = buffOf(player);
        final double perSlot = buff.resonance() >= 0.0D
                ? buff.resonance()
                : Config.Engram.RESONANCE_PER_SLOT.get();
        return 1.0D + (perSlot + getEquipmentResonanceBonus(player)) * getUsedEngrams(player);
    }

    /**
     * 法力消耗惩罚倍率：{@code 1 + manaPenaltyPerSlot × used}。
     *
     * <p>{@code used} 在"永久记忆豁免"生效时会扣掉 {@code permSlots} 的条数
     * （{@code 记忆掠夺} 满级的效果）；{@code manaPenaltyPerSlot} 会被增益按比例缩放
     * （{@code 忆格扩张} 满级"法力惩罚减半"）。
     */
    public static double getManaPenalty(final Player player) {
        final Buff buff = buffOf(player);
        return 1.0D + Config.Engram.MANA_PENALTY_PER_SLOT.get() * penaltySlots(player) * buff.manaScale();
    }

    /**
     * 施法速度惩罚倍率：{@code 1 - castSpeedPenaltyPerSlot × used}。
     *
     * <p>下限钳到 0.2 —— 5 格时是 {@code 1 - 0.08×5 = 0.6}，正常够用；
     * 但配置被改大时（例如 0.3/格）会算出负数，那会让施法时间变成负数。
     */
    public static double getCastSpeedPenalty(final Player player) {
        final Buff buff = buffOf(player);
        final double raw = 1.0D
                - Config.Engram.CAST_SPEED_PENALTY_PER_SLOT.get() * penaltySlots(player) * buff.castScale();
        return Math.max(0.2D, raw);
    }

    /**
     * 计入**惩罚**的忆格数。
     *
     * <p>与 {@link #getUsedEngrams} 的区别只有一处：{@code 记忆掠夺} 满级后
     * {@code permSlots} 里的永久记忆不再计入共鸣惩罚（{@code docs/06 §18}）。
     * 共鸣**加成**仍然按全部忆格计算 —— 设计意图是"永久记忆只给好处不给坏处"。
     */
    private static int penaltySlots(final Player player) {
        final int used = getUsedEngrams(player);
        // ⚠️ 2026-09-18：加了配置总开关 Config.Engram.PERMANENT_FREE_OF_RESONANCE。
        //    语义：**配置是总开关，NBT 标记是玩家侧的资格**，两者都成立才豁免。
        //    这样配置能整体关掉"永久格不惩罚"（关掉后永久格照常计入惩罚），
        //    而 5 级记忆掠夺给的那个标记仍然有意义。
        if (Config.Engram.PERMANENT_FREE_OF_RESONANCE.get() && isPermanentMemoryExempt(player)) {
            return Math.max(0, used - getPermanentEngramCount(player));
        }
        return used;
    }

    /**
     * 永久记忆是否已豁免共鸣惩罚。
     *
     * <p>两个来源任一成立即可：永久标记（{@code 记忆掠夺} 满级，本方法读的那个），
     * 或 {@link #applyEngramBuff} 的临时增益（为将来"限时豁免"类效果预留）。
     */
    private static boolean isPermanentMemoryExempt(final Player player) {
        if (buffOf(player).permExempt()) {
            return true;
        }
        return !player.level().isClientSide
                && player.getPersistentData().getBoolean(KEY_PERM_EXEMPT);
    }

    /**
     * 开启「永久记忆不计入共鸣惩罚」（{@code 记忆掠夺} 满级）。
     *
     * <p>只写不删 —— 这是一条**能力解锁**而不是一段状态：一旦玩家在满级用出了记忆掠夺，
     * 他就永久拥有了这条规则。要撤销只能清玩家数据（管理员场景）。
     */
    public static void setPermanentMemoryExempt(final ServerPlayer player) {
        player.getPersistentData().putBoolean(KEY_PERM_EXEMPT, true);
    }

    /**
     * {@code permSlots} 里的条数（{@code 记忆掠夺} 写入的永久记忆）。
     *
     * <p>客户端读不到权威值（同步包只带条目列表、不带"它来自哪个槽位"），
     * 返回 {@code 0}。本方法只服务服务端的惩罚计算，客户端拿到 0 不影响任何显示。
     */
    public static int getPermanentEngramCount(final Player player) {
        if (player.level().isClientSide) {
            return 0;
        }
        return rootTag(player).getList(KEY_PERM_SLOTS, Tag.TAG_COMPOUND).size();
    }

    /**
     * 普通忆格（{@code slots}，会腐坏的那些）的条数。
     *
     * <p><b>为什么需要它</b>：{@link #getEngrams} 返回的是
     * 「普通格 + 永久格」拼接后的**一个列表**，调用方拿到的只有下标，
     * 分不出第 N 条来自哪个槽位。
     * <br>这个区分是为「复诵」设计的（普通格消耗、永久格保留）——
     * ⚠️ 2026-09-18 复诵已删除，但**数据层的区分保留**：
     * 未来的任何释放端都可以复用同一套语义。
     * <br>约定：{@code index < getNormalEngramCount()} → 普通格；否则是永久格。
     */
    public static int getNormalEngramCount(final Player player) {
        if (player.level().isClientSide) {
            return 0;
        }
        return rootTag(player).getList(KEY_SLOTS, Tag.TAG_COMPOUND).size();
    }

    /**
     * 只读 {@code permSlots} 里的**永久记忆**（{@code 记忆掠夺} 写入的那些）。
     *
     * <p>为什么需要它（而不是从 {@link #getEngrams} 里切一段）：{@code 千忆归一}
     * 满级要"消耗的永久记忆有 50% 概率保留"，它必须能**逐条**读到永久记忆，
     * 才能在 {@code clearAll} 之前把骰赢的那几条留下来。
     * {@link #getPermanentEngramCount} 只给条数，救不了这个场景。
     *
     * <p>用"先读全部、再按条数截尾"是可以绕开的（{@code readAll} 的顺序是
     * slots 在前、permSlots 在后），但那把 {@code readAll} 的**内部顺序**
     * 变成了对外契约 —— 将来谁调整一下拼接顺序，这里就会**静默**拿到错的那几条。
     * 明确的方法比隐含的约定安全。
     *
     * <p>⚠️ 客户端恒返回空表（客户端读不到权威的槽位归属，与
     * {@link #getPermanentEngramCount} 同样的理由）。本方法只服务服务端逻辑。
     */
    public static List<EngramEntry> getPermanentEngrams(final Player player) {
        if (player.level().isClientSide) {
            return List.of();
        }
        final List<EngramEntry> out = new ArrayList<>();
        collect(rootTag(player), KEY_PERM_SLOTS, out);
        return out;
    }

    /**
     * 清空全部**临时**忆格（{@code 千忆归一} 的"消耗全部忆格含临时忆格"）。
     *
     * <p>只删 {@code tempExpire} 的计数，不动 {@code slots} —— 里面已有的记忆会
     * 因为"上限变小"而超出容量，但它们**不会**被自动删除（下一次 {@code addEngram}
     * 会因为没空位而失败）。{@code 千忆归一} 自己会先把 slots 清空，所以不会出现这种状态。
     */
    public static void clearTempSlots(final ServerPlayer player) {
        final CompoundTag root = writableRootTag(player);
        root.remove(KEY_TEMP_EXPIRE);
        markDirty(player);
    }

    // ==================================================================
    // 忆格增益（忆格扩张 / 忆海 / 记忆掠夺满级）
    // ==================================================================

    /**
     * 施加一段**忆格增益**，覆盖三类效果：
     * <ul>
     *   <li>{@code 忆格扩张} → {@code castPenaltyScale} / {@code manaPenaltyScale} = 0.5（满级减半）</li>
     *   <li>{@code 忆海} 领域 → {@code resonancePerSlot} = 0.30 ~ 0.40</li>
     *   <li>{@code 记忆掠夺 L5} → {@code permExempt} = {@code true}</li>
     * </ul>
     *
     * <p><b>合并规则</b>：重复施加时取"对玩家更有利"的一方，到期时间取更晚的一方。
     * 这样两个增益同时存在不会互相覆盖（例如先放忆格扩张再放忆海，
     * 减半惩罚与提高共鸣应当**同时**生效）。
     *
     * @param resonancePerSlot 新的每格共鸣基准；传 {@link #NO_RESONANCE_OVERRIDE} 表示不改写
     */
    public static void applyEngramBuff(final ServerPlayer player, final int durationTicks,
                                       final double resonancePerSlot, final double castPenaltyScale,
                                       final double manaPenaltyScale, final boolean permExempt) {
        if (player.level().isClientSide) {
            return;
        }
        final CompoundTag data = player.getPersistentData();
        final long now = nowTick(player);
        final CompoundTag old = data.getCompound(KEY_BUFF);
        final boolean oldActive = old.getLong(KEY_BUFF_UNTIL) > now;

        final CompoundTag tag = new CompoundTag();
        tag.putLong(KEY_BUFF_UNTIL, now + Math.max(1, durationTicks));
        tag.putDouble(KEY_BUFF_RESONANCE, oldActive
                ? Math.max(old.getDouble(KEY_BUFF_RESONANCE), resonancePerSlot)
                : resonancePerSlot);
        tag.putDouble(KEY_BUFF_CAST, oldActive
                ? Math.min(old.getDouble(KEY_BUFF_CAST), castPenaltyScale)
                : castPenaltyScale);
        tag.putDouble(KEY_BUFF_MANA, oldActive
                ? Math.min(old.getDouble(KEY_BUFF_MANA), manaPenaltyScale)
                : manaPenaltyScale);
        tag.putBoolean(KEY_BUFF_PERM, permExempt || (oldActive && old.getBoolean(KEY_BUFF_PERM)));
        data.put(KEY_BUFF, tag);
    }

    /** 当前生效的忆格增益；没有则返回全中性的 {@link Buff#NONE}。 */
    private static Buff buffOf(final Player player) {
        if (player.level().isClientSide) {
            return Buff.NONE;
        }
        final CompoundTag tag = player.getPersistentData().getCompound(KEY_BUFF);
        if (tag.isEmpty() || tag.getLong(KEY_BUFF_UNTIL) <= nowTick(player)) {
            return Buff.NONE;
        }
        return new Buff(
                tag.contains(KEY_BUFF_RESONANCE) ? tag.getDouble(KEY_BUFF_RESONANCE) : NO_RESONANCE_OVERRIDE,
                tag.contains(KEY_BUFF_CAST) ? tag.getDouble(KEY_BUFF_CAST) : 1.0D,
                tag.contains(KEY_BUFF_MANA) ? tag.getDouble(KEY_BUFF_MANA) : 1.0D,
                tag.getBoolean(KEY_BUFF_PERM));
    }

    /** 一段忆格增益。全中性值 = 什么都没加。 */
    private record Buff(double resonance, double castScale, double manaScale, boolean permExempt) {

        private static final Buff NONE = new Buff(NO_RESONANCE_OVERRIDE, 1.0D, 1.0D, false);
    }

    /**
     * 每 tick 的维护。
     *
     * <p>实现要点（{@code docs/tech/11} §六）：
     * <ul>
     *   <li><b>每 20 tick 才检查腐坏</b>，不是每 tick 全量扫描</li>
     *   <li>只有内容真的变了才标脏 → 只有脏了才发包</li>
     * </ul>
     */
    public static void tick(final ServerPlayer player) {
        if (player.tickCount % DECAY_CHECK_INTERVAL != 0) {
            return;
        }
        final long now = nowTick(player);
        boolean changed = false;

        // ⭐ 2026-09-18：铭忆的法术上限还原也挂在这里。
        //    复用这个"每秒一次"的节流入口，不另开 tick 监听器 ——
        //    一个每刻都在跑的全局监听器，只为了一件 15 秒冷却的事，不值得。
        com.etbs31.mnemosyne.util.SpellSlotBoost.tick(player);

        final CompoundTag root = writableRootTag(player);
        final ListTag slots = root.getList(KEY_SLOTS, Tag.TAG_COMPOUND);
        for (int i = slots.size() - 1; i >= 0; i--) {
            final EngramEntry entry = EngramEntry.load(slots.getCompound(i));
            if (entry == null || entry.isExpired(now)) {
                slots.remove(i);
                changed = true;
            }
        }
        if (changed) {
            root.put(KEY_SLOTS, slots);
        }

        // 临时忆格到期：先清内容再减计数（docs/tech/03 §6.7）
        // ⭐ 2026-09-18：到期格数拿去销毁溢出记忆（§2.3"记忆一并消散"）
        final int expiredTemp = pruneTempSlots(root, now);
        if (expiredTemp > 0) {
            changed = true;
            root.put(KEY_SLOTS, root.getList(KEY_SLOTS, Tag.TAG_COMPOUND));
            final int destroyed = enforceCapacity(root, player);
            if (destroyed > 0) {
                // 玩家最需要知道的一条：临时格到期把里面的记忆一起带走了。
                // 不提示的话，记忆消失看起来就是"莫名其妙少了一格"。
                player.displayClientMessage(Component.translatable(
                        "mnemosyne.msg.temp_slot_collapsed", destroyed), true);
            }
        }

        // ⭐ 兜底：任何原因造成的"已用 > 上限"都在这里收口
        //   （脱法袍 / 改配置 / 存档来自旧版本）。
        if (enforceCapacity(root, player) > 0) {
            changed = true;
        }

        if (changed) {
            // 有忆格自然腐坏/到期 —— 这是玩家最容易"莫名其妙少了一格"的场景，
            // 必须给提示（原来靠 HUD 常驻显示，现在靠这一行）。
            // notifyEngramChange 内部会顺带刷新派生效果。
            notifyEngramChange(player);
        } else {
            // ⭐ 2026-09-18：即使没有变化也刷新一次派生效果。
            //    这是"效果只是缓存"这条设计的**兜底**：喝牛奶 / 被其他模组净化之后，
            //    效果被清空但 NBT 没变，changed 为 false —— 如果不在这里重刷，
            //    玩家的共鸣图标和施法速度惩罚会一直缺失，直到下一次忆格变动。
            //    每秒一次的开销可以忽略（原版 addEffect 会做幂等刷新）。
            refreshEngramEffects(player);
        }
    }

    // ==================================================================
    // 扩展 API（WS-D / WS-I 会用到，但不是冻结契约的一部分）
    // ==================================================================

    /** 全部记忆，顺序 = HUD 上的显示顺序（先常驻，后永久）。不可变。 */
    public static List<EngramEntry> getEngrams(final Player player) {
        return Collections.unmodifiableList(readAll(player));
    }

    /** 是否有空忆格。{@link #addEngram} 的前置检查。 */
    public static boolean hasFreeSlot(final Player player) {
        return getUsedEngrams(player) < getMaxEngrams(player);
    }

    /** 当前有效的临时忆格数量。 */
    public static int getTempSlotCount(final Player player) {
        final ListTag list = rootTag(player).getList(KEY_TEMP_EXPIRE, Tag.TAG_LONG);
        return list.size();
    }

    /**
     * 增加临时忆格（碎忆 +1 / 忆格扩张 +2 / 忆海领域 +3）。
     *
     * <p>⚠️ 到期时**若其中有记忆，记忆一并消散** —— 这是刻意的风险设计
     * （{@code docs/tech/03} §6.7），不要"贴心地"帮玩家保住内容。
     */
    public static void addTempSlots(final ServerPlayer player, final int count, final int durationTicks) {
        final CompoundTag root = writableRootTag(player);
        final ListTag list = root.getList(KEY_TEMP_EXPIRE, Tag.TAG_LONG);
        final long expire = nowTick(player) + Math.max(1, durationTicks);
        for (int i = 0; i < count; i++) {
            list.add(LongTag.valueOf(expire));
        }
        root.put(KEY_TEMP_EXPIRE, list);
        markDirty(player);
    }

    /**
     * 忆格发生变化时通知玩家 —— <b>这是被删掉的忆格 HUD 的替代品</b>。
     *
     * <p>2026-09-17：原先忆格状态靠 {@code EngramHudOverlay}（自定义 GUI）常驻显示，
     * 由一个 S2C 同步包把数据推到客户端缓存。用户要求删掉那个 GUI，
     * 所以忆格反馈改成走**原版渠道**：动作栏一行字 + 一个音效。
     *
     * <p>为什么是动作栏（{@code displayClientMessage(msg, true)}）而不是聊天栏：
     * <ul>
     *   <li>动作栏在物品栏上方，玩家施法时视线本来就在屏幕中央附近，不挡视线；</li>
     *   <li>不会刷屏 —— 忆格变动很频繁（每次编码/释放/腐坏都变），
     *       走聊天栏会把玩家的聊天记录冲掉。</li>
     * </ul>
     *
     * <p>⚠️ 本方法**只在服务端有数据**（忆格存玩家 NBT，客户端没有权威副本）。
     * 所以它只能由服务端调用，且必须在 {@code ServerPlayer} 上调用。
     */
    /**
     * 忆格数量或上限变化时，刷一次物品栏上方那行白字。
     *
     * <p><b>⭐ 为什么需要单独一个方法（2026-09-18 补）</b>
     * 之前只有 {@link #notifyEngramChange} 会输出这行字，而它只挂在**写入路径**上
     * （`markDirty`）。于是有两个场景**永远看不到这行字**：
     * <ol>
     *   <li><b>刚进游戏</b> —— 没有任何写入发生，玩家不知道自己是 0/3</li>
     *   <li><b>上限变化</b> —— 穿上忆者法袍（+1/+2 格）、临时忆格增减，
     *       这些都不走写入路径，所以显示的上限是旧的</li>
     * </ol>
     * 而"上限"恰恰是玩家最需要感知的（它决定还能不能写入）。
     *
     * <p><b>实现</b>：把上次显示过的 (used, max) 打包成一个 long 存在玩家 NBT 里，
     * 两者任一变化才输出。用 NBT 而不是静态 Map 是刻意的 ——
     * 静态 Map 需要额外的登出清理（本项目在内存泄露审查里刚踩过这个坑）。
     * 登出时把那个键删掉，于是下次进游戏一定会重新显示一次。
     *
     * <p>调用频率：由 {@link #refreshEngramEffects} 每秒带一次，开销可忽略
     *（一次 NBT 读写 + 两次整数比较）。
     */
    private static void maybeAnnounceEngramSlots(final ServerPlayer player) {
        final int used = getUsedEngrams(player);
        final int max = getMaxEngrams(player);
        // 打包成一个 long：高 32 位 used、低 32 位 max
        final long now = ((long) used << 32) | (max & 0xFFFFFFFFL);

        final CompoundTag data = player.getPersistentData();
        if (data.getLong(KEY_SLOT_DISPLAY) == now) {
            return;
        }
        data.putLong(KEY_SLOT_DISPLAY, now);
        SpellFeedback.actionBar(player, Component.translatable(
                "mnemosyne.feedback.engram_slots", used, max));
    }

    public static void notifyEngramChange(final ServerPlayer player) {
        if (player == null) {
            return;
        }
        final int used = getUsedEngrams(player);
        final int max = getMaxEngrams(player);
        SpellFeedback.actionBar(player, Component.translatable(
                "mnemosyne.feedback.engram_slots", used, max));
        SpellFeedback.playAt(player.level(), player, ModSounds.HUD_ENGRAM_FILL.get(), 0.5F, 1.2F);
        // 忆格一变，派生效果也要跟着变（共鸣层数 / 负担层数 / 临时格数）
        refreshEngramEffects(player);
    }

    // ==================================================================
    // 忆格状态的「派生缓存」—— 原版药水效果
    // ==================================================================

    /**
     * 从 NBT 重算并刷新三个忆格状态效果。
     *
     * <p><b>⭐⭐ 这是"NBT 权威、效果派生"这条规则的唯一执行点。</b>
     * <br>喝牛奶 / 被净化会清空全部状态效果 —— 因为它们只是缓存，
     * 下一次刷新（每秒一次，见 {@link #tick}）会从 NBT 重新长回来，无害。
     * 反过来，如果哪天有人图省事直接改效果来"表达"忆格状态，奶就会造成状态撕裂。
     *
     * <p>三个效果的 amplifier 语义见 {@code EngramEffect}：
     * <ul>
     *   <li>共鸣 → 占用格数 − 1</li>
     *   <li>负担 → <b>计入惩罚的</b>格数 − 1（永久格在豁免时不计入）</li>
     *   <li>临时忆格 → 临时格数 − 1，时长 = 最晚到期的那个临时格的剩余时间</li>
     * </ul>
     *
     * <p>⚠️ 依赖 {@code ModEffects} 已挂载。未挂载时**静默跳过**（不崩），
     * 与 {@code ModEffects} 里那批防御性取用的取舍一致。
     */
    public static void refreshEngramEffects(final ServerPlayer player) {
        if (player == null || player.level().isClientSide) {
            return;
        }
        final MobEffect burden = ModEffects.engramBurden();
        final MobEffect temporal = ModEffects.temporalEngram();
        if (burden == null || temporal == null) {
            return;
        }

        final int penalty = penaltySlots(player);
        final int temp = getTempSlotCount(player);

        // ⚠️ 2026-09-18：「共鸣」效果已删除。
        //    它只是个"显示用了几个忆格"的图标，而**基础忆格槽位本来就该是内置的**，
        //    不该额外占一个药水效果位（玩家装几个模组就被挤掉了）。
        //    忆格数量现在只走动作栏那行白字，见 maybeAnnounceEngramSlots。
        applyOrClear(player, burden, penalty > 0 ? penalty - 1 : -1, EngramEffect.REFRESH_DURATION_TICKS);
        applyOrClear(player, temporal, temp > 0 ? temp - 1 : -1, remainingTempTicks(player));

        // 顺带检查"数量或上限变了没" —— 变了就刷那行白字。
        // 放在这里是因为本方法**每秒都会跑一次**，是唯一能覆盖"上限变化"的时机
        //（换装备、临时忆格增减都不会走 markDirty）。
        maybeAnnounceEngramSlots(player);
    }

    /**
     * 把某个效果调到目标 amplifier；{@code desired < 0} 表示"不该存在"。
     *
     * <p>⚠️ <b>为什么要 remove 再 add</b>：原版 {@code MobEffectInstance.update} 只会
     * 取<b>较大</b>的 amplifier，**降不下来**。所以"占用 3 格 → 释放 1 格"时，
     * 直接 {@code addEffect} 会让层数停在 3，表现是"效果图标显示的层数和实际不符"。
     * 同层数时则只刷新时长，避免 remove+add 让属性修饰符闪一下。
     */
    private static void applyOrClear(final ServerPlayer player, final MobEffect effect,
                                     final int desiredAmplifier, final int durationTicks) {
        final MobEffectInstance current = player.getEffect(effect);
        if (desiredAmplifier < 0) {
            if (current != null) {
                player.removeEffect(effect);
            }
            return;
        }
        final int duration = Math.max(1, durationTicks);
        if (current == null) {
            player.addEffect(new MobEffectInstance(effect, duration, desiredAmplifier, false, false, true));
        } else if (current.getAmplifier() != desiredAmplifier) {
            player.removeEffect(effect);
            player.addEffect(new MobEffectInstance(effect, duration, desiredAmplifier, false, false, true));
        } else {
            // 同层数：原地刷新时长（不 remove+add）
            current.update(new MobEffectInstance(effect, duration, desiredAmplifier, false, false, true));
        }
    }

    /** 最晚到期的那个临时忆格还剩多少 tick。没有临时格时返回 0。 */
    private static int remainingTempTicks(final Player player) {
        final ListTag list = rootTag(player).getList(KEY_TEMP_EXPIRE, Tag.TAG_LONG);
        final long now = nowTick(player);
        long maxRemaining = 0L;
        for (int i = 0; i < list.size(); i++) {
            final Tag tag = list.get(i);
            if (tag instanceof LongTag longTag) {
                maxRemaining = Math.max(maxRemaining, longTag.getAsLong() - now);
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, maxRemaining));
    }

    // ==================================================================
    // 装备加成钩子（WS-H2 的忆者法袍 4 件套）
    // ==================================================================

    /**
     * 装备提供的额外忆格数（忆者法袍套装）。
     *
     * <p>契约（{@code docs/07} §2.3）：**2 件 +1 格，4 件再 +1 格**（合计 +2）。
     * 外层 {@link #getMaxEngrams} 会做 {@code min(基础+加成, MAX_SLOTS)} 的钳制，
     * 所以这里可以放心返回 2 —— 基础 3 时 4 件套得到 5，正好等于硬上限。
     *
     * <p>（原 TODO(WS-H2) 占位实现恒返回 0，已于 2026-09-17 接线。）
     */
    private static int getEquipmentSlotBonus(final Player player) {
        final int pieces = MnemonicRobeItem.countEquippedPieces(player);
        if (pieces >= MnemonicRobeItem.PIECES_FOR_TIER_3) {
            return 2;
        }
        return pieces >= MnemonicRobeItem.PIECES_FOR_TIER_1 ? 1 : 0;
    }

    /**
     * 装备提供的额外共鸣加成（4 件套时 +5%，把 0.20 抬到 0.25）。
     *
     * <p>契约（{@code docs/07} §2.3）：满套时共鸣从 {@code +20%/格} 提升到 {@code +25%/格}。
     * 加在 {@code Config.Engram.RESONANCE_PER_SLOT} **之上**，见 {@link #getResonanceMultiplier}。
     *
     * <p>（原 TODO(WS-H2) 占位实现恒返回 0，已于 2026-09-17 接线。）
     */
    private static double getEquipmentResonanceBonus(final Player player) {
        return MnemonicRobeItem.countEquippedPieces(player) >= MnemonicRobeItem.PIECES_FOR_TIER_3
                ? 0.05D
                : 0.0D;
    }

    /**
     * 记忆的有效期（秒）。
     *
     * <p>契约（{@code docs/07} §2.3）：3 件套把记忆有效期从 120 秒延长到 **180 秒**。
     * 加成写在配置基础值之上，所以改配置时套装效果仍然相对生效（不会变成"改配置就失效"）。
     *
     * <p><b>⚠️ 所有计算记忆到期的代码都必须走这里，不要直接读
     * {@code Config.Engram.DECAY_SECONDS}</b> —— 直接读会静默丢掉套装效果
     * （编译通过、游戏里只是"感觉套装没生效"）。
     * 当前调用点是 {@code EncodeTraitSpell} / {@code EngraveSpell} 等写入类法术。
     */
    /**
     * 一条**新写入**的记忆应该在哪个游戏刻到期。
     *
     * <p>⭐ 2026-09-18：从被删除的 {@code EncodeSpellSpell.expireTickOf} 迁移过来。
     * 原先这个方法挂在「术忆」那个具体法术上，而它其实是**数据层**的概念
     * （"记忆的有效期"），却让 {@code EncodeTraitSpell} 不得不去依赖另一个法术类 ——
     * 删除术忆时才发现这个耦合。放到这里之后，任何写入类法术都能用，
     * 且不依赖具体法术。
     *
     * <p>用**主世界 gameTime 绝对值**（与 {@link #tick} 的腐坏判定同一把尺子），
     * 登出即冻结 —— 见忆格数据层的通用约定。
     */
    public static long newExpireTick(final Player player) {
        return nowTick(player) + (long) getMemoryLifetimeSeconds(player) * 20L;
    }

    public static int getMemoryLifetimeSeconds(final Player player) {
        return Config.Engram.DECAY_SECONDS.get()
                + (MnemonicRobeItem.countEquippedPieces(player) >= MnemonicRobeItem.PIECES_FOR_TIER_2 ? 60 : 0);
    }

    // ==================================================================
    // 内部实现
    // ==================================================================

    /**
     * 当前世界刻。**统一取主世界的 {@code getGameTime()}**。
     *
     * <p>2026-09-17 由 {@code private} 改为 {@code public}：拾忆匕首（WS-K 装备层）
     * 要按同一个时间基准算出忆格到期时刻。让外部自己算 {@code level().getGameTime()}
     * 必然会把"跨维度跳变"这个坑再踩一遍 —— 直接复用这个方法是唯一正确的做法。
     *
     * <p>为什么不直接用 {@code player.level().getGameTime()}：各维度有各自的 gameTime 计数器，
     * 玩家跨维度时数字会跳变，导致记忆瞬间全部腐坏或永不过期。
     * 主世界那一个计数器是所有维度共享的时间基准。
     */
    public static long nowTick(final Player player) {
        if (player.level() instanceof ServerLevel serverLevel && serverLevel.getServer() != null) {
            return serverLevel.getServer().overworld().getGameTime();
        }
        return player.level().getGameTime();
    }

    private static CompoundTag rootTag(final Player player) {
        return player.getPersistentData().getCompound(NBT_ROOT);
    }

    /**
     * 取**可写**的忆格根标签。
     *
     * <p><b>⭐⭐ 实测（1.20.1 {@code CompoundTag.getCompound} 源码逐字）</b>：
     * <pre>{@code
     * if (this.contains(key, 10)) return (CompoundTag) this.tags.get(key);  // 活引用
     * return new CompoundTag();                                             // 游离的新对象！
     * }</pre>
     * 也就是说：**根键不存在时 {@link #rootTag} 返回的是一个游离对象，
     * 往它里面写任何东西都会被静默丢弃。**
     *
     * <p>这是本项目最危险的一类静默失败：玩家第一次写入忆格时
     * {@code mnemosyne_engram} 还不存在 → {@code addEngram} 把条目写进游离标签 →
     * 函数返回 {@code true}、脏标记被置位、同步包照发 → 但存档里什么都没有。
     * <b>编译通过、日志无输出、游戏里表现为"第一条记忆总是消失"。</b>
     *
     * <p>所以所有**会修改**忆格数据的地方都必须走本方法（它保证根键先被创建并挂上去），
     * 只有纯读取可以继续用 {@link #rootTag}。
     */
    private static CompoundTag writableRootTag(final Player player) {
        final CompoundTag data = player.getPersistentData();
        if (!(data.get(NBT_ROOT) instanceof CompoundTag)) {
            data.put(NBT_ROOT, new CompoundTag());
        }
        return data.getCompound(NBT_ROOT);
    }

    /**
     * 读取全部记忆。
     *
     * <p>⚠️ 2026-09-17：原来是"服务端读 NBT / 客户端读同步缓存"的两套来源路由。
     * 客户端缓存随忆格 GUI 一起删除了，现在**只有服务端有权威数据**。
     * 客户端若调用本方法会得到空列表 —— 这不是 bug，是"忆格数据不下发到客户端"的设计。
     * 需要给玩家看忆格状态时，用 {@link #notifyEngramChange}（服务端侧）而不是客户端查询。
     */
    private static List<EngramEntry> readAll(final Player player) {
        if (player.level().isClientSide) {
            return List.of();
        }
        final CompoundTag root = rootTag(player);
        final List<EngramEntry> result = new ArrayList<>();
        collect(root, KEY_SLOTS, result);
        collect(root, KEY_PERM_SLOTS, result);
        return result;
    }

    private static void collect(final CompoundTag root, final String key, final List<EngramEntry> out) {
        final ListTag list = root.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            final EngramEntry entry = EngramEntry.load(list.getCompound(i));
            if (entry != null) {
                out.add(entry);
            }
        }
    }

    private static int sizeOf(final CompoundTag root, final String key) {
        return root.getList(key, Tag.TAG_COMPOUND).size();
    }

    @Nullable
    private static EngramEntry removeAt(final CompoundTag root, final String key, final int index) {
        final ListTag list = root.getList(key, Tag.TAG_COMPOUND);
        if (index < 0 || index >= list.size()) {
            return null;
        }
        final EngramEntry entry = EngramEntry.load(list.getCompound(index));
        list.remove(index);
        root.put(key, list);
        return entry;
    }

    /**
     * 清掉过期的临时忆格。
     *
     * <p>⭐ 2026-09-18 改为返回**到期的格数**（原来只返回 boolean）。
     * 因为 {@code docs/} §2.3 规定"到期时如果里面有记忆，记忆一并消散" ——
     * 调用方需要知道少了多少格才能销毁对应数量的记忆。
     */
    private static int pruneTempSlots(final CompoundTag root, final long now) {
        final ListTag list = root.getList(KEY_TEMP_EXPIRE, Tag.TAG_LONG);
        int expired = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            // ListTag 没有 getLong(int)（实测），必须自己取 Tag 再转
            final Tag tag = list.get(i);
            if (tag instanceof LongTag longTag && longTag.getAsLong() <= now) {
                list.remove(i);
                expired++;
            }
        }
        if (expired > 0) {
            root.put(KEY_TEMP_EXPIRE, list);
        }
        return expired;
    }

    /**
     * ⭐⭐ 2026-09-18 新增（{@code docs/} §2.3"到期时如果里面有记忆，记忆一并消散"）。
     *
     * <p><b>为什么必须有这个方法</b>：临时忆格到期只删 {@code tempExpire} 里的计时条目，
     * 上限变小了，但记忆还躺在 {@code slots} 里 —— 结果是
     * <b>已用格数 > 上限</b>，一个"超容"的非法状态。之前没人管这个状态：
     * 玩家看着动作栏显示「忆格 5 / 4」一脸茫然，而且下次 {@code addEngram}
     * 会静默失败（{@code hasFreeSlot} 为 false）。
     *
     * <p><b>为什么做成通用的"容量钳制"而不是"临时格专用"</b>：
     * 让上限变小的原因不止临时格到期 —— 脱下忆者法袍（装备 -2 格）、
     * 管理员改配置（{@code baseSlots} 调小）都会造成超容。
     * 一个通用入口覆盖全部路径，比在每个原因处各写一遍可靠。
     *
     * <p><b>销毁顺序（写在明处的设计取舍）</b>：先从 {@code slots}（普通记忆）尾部删，
     * 普通记忆删光了才动 {@code permSlots}（永久记忆）。
     * 理由：永久记忆是玩家投入最多的稀缺资产（红线二），
     * 让一次临时格到期把它吃掉，惩罚与原因不成比例。
     * 代价是"最后放进来的永久记忆"不一定正好躺在刚到期的那一格里 ——
     * 这是扁平存储结构的固有模糊，接受它。
     *
     * @return 被销毁的记忆条数
     */
    private static int enforceCapacity(final CompoundTag root, final Player player) {
        int destroyed = 0;
        int used = sizeOf(root, KEY_SLOTS) + sizeOf(root, KEY_PERM_SLOTS);
        final int max = getMaxEngrams(player);
        while (used > max) {
            // 优先删普通记忆；普通删光了才动永久记忆（见上面的取舍）
            final String key = sizeOf(root, KEY_SLOTS) > 0 ? KEY_SLOTS : KEY_PERM_SLOTS;
            final ListTag list = root.getList(key, Tag.TAG_COMPOUND);
            if (list.isEmpty()) {
                break; // 防御：理论上到不了这里（used > max ≥ 0 意味着至少有一条）
            }
            list.remove(list.size() - 1);
            root.put(key, list);
            used--;
            destroyed++;
        }
        return destroyed;
    }

    /**
     * 忆格数据被写过之后统一走这里。
     *
     * <p>⚠️ 2026-09-17 语义变更：原实现是"打脏标记，等每 20 tick 的 flush 发包给 HUD"。
     * HUD 删除后改成**直接给玩家一行动作栏提示**。
     *
     * <p>为什么保留这个名字而不是全部改叫 notifyEngramChange：本方法有十几处调用点，
     * 分布在 addEngram / releaseEngram / addTempSlots / clearAll 等所有写路径上。
     * 改名的收益是"名字更准"，代价是十几处改动 + 未来新写路径容易漏改；
     * 保留名字、改语义，**所有写路径自动获得反馈**，这才是不会漏的做法。
     *
     * <p>重复提示是无害的：同一 tick 内多次调用只会把同一行文字重复写进动作栏，
     * 玩家看到的就是一行（后写的覆盖先写的）。
     */
    private static void markDirty(final Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            notifyEngramChange(serverPlayer);
        }
    }

    // ==================================================================
    // 事件
    // ==================================================================

    /**
     * 玩家死亡 → 清空忆格。
     *
     * <p>为什么是 {@code PlayerEvent.Clone} 而不是 {@code LivingDeathEvent}：
     * 死亡会创建一个**新的** {@code ServerPlayer} 实体，{@code Clone} 事件正是
     * "旧实体 → 新实体" 的搬运点，{@code isWasDeath()} 区分死亡重生与跨维度传送。
     * 用 {@code LivingDeathEvent} 也行，但那样在"被复活"类模组下会把刚复活的玩家的忆格也清掉。
     *
     * <p>注意：{@code getPersistentData()} 在死亡后**不会自动清空**（它跟着玩家走），
     * 所以这一步是必须的，不是可选的。
     */
    @SubscribeEvent
    public static void onPlayerClone(final PlayerEvent.Clone event) {
        if (event.isWasDeath() && event.getEntity() instanceof ServerPlayer newPlayer) {
            clearAll(newPlayer);
            // 立刻给一次提示，否则玩家不知道自己辛苦攒的记忆已经随死亡消散了
            SpellFeedback.chat(newPlayer, Component.translatable("mnemosyne.feedback.engrams_lost"));
            MnemosyneMod.LOGGER.debug("玩家 {} 死亡，忆格已清空", newPlayer.getName().getString());
        }
    }

    /**
     * 每 tick 驱动腐坏检查与脏包发送。
     *
     * <p>只在服务端、且只在 {@code Phase.END} 跑一次 ——
     * {@code PlayerTickEvent} 有 START / END 两相，不判相会让所有逻辑每 tick 跑两遍。
     */
    @SubscribeEvent
    public static void onPlayerTick(final TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        tick(player);
    }

    /**
     * 玩家登出。
     *
     * <p>⚠️ 2026-09-17：原来这里要清客户端缓存（{@code evictClientCache}），
     * 防止 UUID 复用导致串数据。客户端缓存随忆格 GUI 一起删除后，
     * 这里已经没有需要清理的状态了 —— 保留方法体为空并留这条注释，
     * 是为了让以后的人知道"这里**曾经**有清理逻辑、为什么现在没有了"，
     * 而不是以为漏写了。
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(final PlayerEvent.PlayerLoggedOutEvent event) {
        // 删掉"上次显示过的忆格数量"缓存 —— 这样下次进游戏一定会重新显示一次。
        // 忆格数据本身不清理（它随玩家存档持久化，那是设计）。
        event.getEntity().getPersistentData().remove(KEY_SLOT_DISPLAY);
    }
}
