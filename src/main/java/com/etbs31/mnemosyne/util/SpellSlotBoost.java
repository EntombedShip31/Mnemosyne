package com.etbs31.mnemosyne.util;

import com.etbs31.mnemosyne.MnemosyneMod;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.ISpellContainerMutable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

/**
 * 「法术上限」临时提升 —— 铭忆（{@code engrave}）的核心机制。
 *
 * <p><b>2026-09-18 新增</b>（用户要求：新增的写入类法术"主要用于短时间增加法术上限"）。
 *
 * <p><b>⭐⭐ 为什么需要单独一个类</b>：ISS 的"法术上限"不是玩家属性，
 * 而是**装备上的法术书自己带的** —— {@code ISpellContainer.getMaxSpellCount()}。
 * 法术轮盘（{@code SpellSelectionManager}）读的就是装备槽里那本书的容器。
 * 所以要"临时提高上限"，只能**改那本书的容器**，并在到期时改回去。
 * 这个"改 + 记原值 + 到期还原"的往返逻辑必须收敛在一处，
 * 否则任何一个调用点漏了还原，玩家的法术书就会**永久变大**（不可逆的存档污染）。
 *
 * <p><b>怎么找到那本书</b>：走 Curios 的 {@code spellbook} 槽
 * （与 ISS 的 {@code SpellSelectionManager} 一致）。
 * ⚠️ 这里**硬编码槽位名 {@code "spellbook"}** 而不是引用 ISS 的
 * {@code io.redspace.ironsspellbooks.compat.Curios} —— 那是**非 api 包**，
 * 本项目对非 api 引用有严格配额（见 MEMORY 的"两处受控例外"）。
 * 槽位标识符是稳定的字符串契约，ISS 自己也只把它当字符串用。
 *
 * <p><b>⭐⭐ 安全阀（本类最重要的设计）</b>：到期还原时，如果**借来的那几格里有法术**，
 * <b>不能直接缩容</b> —— {@code SpellContainer.setMaxSpellCount} 内部是
 * {@code Arrays.copyOf(slots, maxSpells)}，缩容会**静默截断**，玩家那本法术书里的
 * 法术就凭空消失了。所以这里改成：**延期 10 秒 + 提示玩家去清空**，
 * 直到槽位空了才真正还原。宁可效果多挂一会儿，也不能吃掉玩家的法术。
 */
public final class SpellSlotBoost {

    private SpellSlotBoost() {}

    /** Curios 的法术书槽位标识符（ISS {@code Curios.SPELLBOOK_SLOT} 的字符串值）。 */
    private static final String SPELLBOOK_SLOT = "spellbook";

    private static final String KEY_ORIG = "mnemosyne_slotboost_orig";
    private static final String KEY_BONUS = "mnemosyne_slotboost_bonus";
    private static final String KEY_UNTIL = "mnemosyne_slotboost_until";

    /** 安全阀触发时的延期（tick）。10 秒够玩家打开法术书把多余的法术取出来。 */
    private static final int DEFER_TICKS = 200;

    /** 安全阀提示的最小间隔（tick）—— 否则每秒刷一次动作栏，很烦。 */
    private static final int WARN_COOLDOWN_TICKS = 100;

    private static final String KEY_WARN_AT = "mnemosyne_slotboost_warn";

    /**
     * 临时提高法术上限。
     *
     * @param bonus         提高的格数
     * @param durationTicks 持续时长
     * @return 是否成功（身上没有可用的法术书时返回 {@code false}）
     */
    public static boolean apply(final ServerPlayer player, final int bonus, final int durationTicks) {
        final ItemStack book = findSpellBook(player);
        if (book.isEmpty()) {
            return false;
        }
        final ISpellContainer container = ISpellContainer.get(book);
        if (container == null) {
            return false;
        }

        final CompoundTag data = player.getPersistentData();
        final long now = nowTick(player);
        // ⚠️ 重复施放时**必须沿用最初记下的原值** ——
        //    否则第二次会把"已被提升过的上限"当成原值记下来，
        //    到期还原后上限会比原来更高（每次都涨 2 格，无限叠加）。
        final boolean active = data.contains(KEY_UNTIL) && data.getLong(KEY_UNTIL) > now;
        final int orig = active ? data.getInt(KEY_ORIG) : container.getMaxSpellCount();

        final ISpellContainerMutable mutable = container.mutableCopy();
        mutable.setMaxSpellCount(orig + bonus);
        ISpellContainer.set(book, mutable.toImmutable());

        data.putInt(KEY_ORIG, orig);
        data.putInt(KEY_BONUS, bonus);
        data.putLong(KEY_UNTIL, now + Math.max(1, durationTicks));
        data.remove(KEY_WARN_AT);
        return true;
    }

    /** 还有多少 tick 到期；没有生效时返回 0。 */
    public static int remainingTicks(final ServerPlayer player) {
        final CompoundTag data = player.getPersistentData();
        if (!data.contains(KEY_UNTIL)) {
            return 0;
        }
        return (int) Math.max(0L, data.getLong(KEY_UNTIL) - nowTick(player));
    }

    /**
     * 每（游戏）刻检查一次是否该还原。由 {@code MnemosyneData.tick} 调用
     * —— 那里已经是"每秒一次"的节流入口，不需要再自己节流。
     */
    public static void tick(final ServerPlayer player) {
        final CompoundTag data = player.getPersistentData();
        if (!data.contains(KEY_UNTIL)) {
            return;
        }
        final long now = nowTick(player);
        if (data.getLong(KEY_UNTIL) > now) {
            return;
        }

        final int orig = data.getInt(KEY_ORIG);
        final ItemStack book = findSpellBook(player);
        if (book.isEmpty()) {
            // 书被换掉 / 收起来了。**不能**在这里瞎还原（改的是另一本书），
            // 也不能留着记录 —— 留着的话下次装回那本书时会突然缩容。
            // 最安全的做法是清掉记录：那本书的上限就停在提升后的值。
            // ⚠️ 这是已知的边界：把书在效果期间取下来再装回去，上限会永久 +N。
            //    与"缩容静默吃掉法术"相比，这个方向的错误是可接受的一侧。
            clear(data);
            MnemosyneMod.LOGGER.debug("[铭忆] 生效期间法术书不在装备槽，放弃还原（上限保留在提升后的值）");
            return;
        }

        final ISpellContainer container = ISpellContainer.get(book);
        if (container == null) {
            clear(data);
            return;
        }

        // ⭐ 安全阀：借来的格子里还有法术 → 延期，绝不缩容（缩容 = 静默吃掉法术）
        final boolean occupied = container.getActiveSpells().stream()
                .anyMatch(slot -> slot.index() >= orig);
        if (occupied) {
            data.putLong(KEY_UNTIL, now + DEFER_TICKS);
            if (now - data.getLong(KEY_WARN_AT) >= WARN_COOLDOWN_TICKS) {
                data.putLong(KEY_WARN_AT, now);
                player.displayClientMessage(
                        Component.translatable("mnemosyne.msg.engrave_end_kept"), true);
            }
            return;
        }

        final ISpellContainerMutable mutable = container.mutableCopy();
        mutable.setMaxSpellCount(orig);
        ISpellContainer.set(book, mutable.toImmutable());
        clear(data);
        player.displayClientMessage(Component.translatable("mnemosyne.msg.engrave_end"), true);
    }

    /** 立刻撤销（死亡 / 管理员清空）。 */
    public static void cancel(final ServerPlayer player) {
        final CompoundTag data = player.getPersistentData();
        if (!data.contains(KEY_UNTIL)) {
            return;
        }
        data.putLong(KEY_UNTIL, 0L);
        data.putLong(KEY_WARN_AT, 0L);
        tick(player);
    }

    private static void clear(final CompoundTag data) {
        data.remove(KEY_ORIG);
        data.remove(KEY_BONUS);
        data.remove(KEY_UNTIL);
        data.remove(KEY_WARN_AT);
    }

    /**
     * 找到玩家装备槽里的法术书。
     *
     * <p>只认 Curios 的 {@code spellbook} 槽 —— 与 ISS 的法术轮盘取书位置一致。
     * 拿到之后还要确认它真的是法术书（{@code isSpellWheel()}），
     * 因为卷轴也是 {@code ISpellContainer}，但改卷轴的上限毫无意义。
     */
    private static ItemStack findSpellBook(final ServerPlayer player) {
        final var opt = CuriosApi.getCuriosInventory(player);
        if (!opt.isPresent()) {
            return ItemStack.EMPTY;
        }
        final ICuriosItemHandler inventory = opt.resolve().orElse(null);
        if (inventory == null) {
            return ItemStack.EMPTY;
        }
        final ICurioStacksHandler handler = inventory.getStacksHandler(SPELLBOOK_SLOT).orElse(null);
        if (handler == null) {
            return ItemStack.EMPTY;
        }
        final ItemStack stack = handler.getStacks().getStackInSlot(0);
        if (stack.isEmpty() || !ISpellContainer.isSpellContainer(stack)) {
            return ItemStack.EMPTY;
        }
        final ISpellContainer container = ISpellContainer.get(stack);
        return container != null && container.isSpellWheel() ? stack : ItemStack.EMPTY;
    }

    /** 与忆格系统同一把尺子：主世界 gameTime 绝对值（登出即冻结）。 */
    private static long nowTick(final ServerPlayer player) {
        return player.server.overworld().getGameTime();
    }
}
