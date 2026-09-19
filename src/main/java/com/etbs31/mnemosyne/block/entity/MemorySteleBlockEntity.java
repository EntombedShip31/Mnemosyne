package com.etbs31.mnemosyne.block.entity;

import com.etbs31.mnemosyne.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 忆碑的方块实体 —— 存"**哪些玩家已经读过这座碑**"。
 *
 * <p><b>文件归属</b>：WS-J 方块层。
 *
 * <p><b>⭐ 为什么必须用方块实体而不是方块状态</b>
 * <br>{@code docs/08} §6.1 的契约是"忆碑对**每个玩家独立记录**是否已读，
 * 一座忆碑可以被服务器里所有玩家各读一次"。方块状态是**全局共享**的，
 * 表达不了"玩家 A 读过、玩家 B 没读过"。所以 per-player 集合只能放在 BE 里。
 *
 * <p>方块状态里那个 {@code read} 布尔是**另一件事**：它表示"这座碑是否已被**任何人**读过"，
 * 只用来驱动**光照等级**（12 → 6）与**染色**（靛蓝 → 品红）。
 * 两者互不替代：
 * <ul>
 *   <li>{@code read} 状态 → 全局视觉（任何人读过后就变暗）</li>
 *   <li>{@code readers} 集合 → 每个玩家还能不能读</li>
 * </ul>
 *
 * <p><b>⭐ 为什么 UUID 存成字符串而不是 {@code NbtUtils.createUUID}</b>
 * <br>{@code NbtUtils} 的 UUID 序列化是 {@code IntArrayTag}，读取侧要
 * {@code ListTag.getIntArray(i)} + {@code NbtUtils.loadUUID(Tag)} 两步，
 * 且这两个方法的签名在版本间挪过位置。存 {@code StringTag}
 * （{@code uuid.toString()} / {@code UUID.fromString}）零 API 风险，
 * 代价只是每个 UUID 多 4 字节。**BE 里最多几十个 UUID，不值得为 4 字节冒险。**
 *
 * <p><b>⚠️ 只在"下半段"创建</b>：{@code MemorySteleBlock.newBlockEntity} 对
 * {@code half=upper} 返回 {@code null}，避免同一座碑出现两个 BE、读到两份状态。
 */
public class MemorySteleBlockEntity extends BlockEntity {

    /** NBT 键：已读玩家 UUID 列表。 */
    private static final String READERS = "readers";

    private final Set<UUID> readers = new HashSet<>();

    public MemorySteleBlockEntity(final BlockPos pos, final BlockState state) {
        super(ModBlocks.MEMORY_STELE_BE.get(), pos, state);
    }

    /** 该玩家是否已经读过这座碑。 */
    public boolean hasRead(final UUID playerId) {
        return readers.contains(playerId);
    }

    /** 记录一次读取。重复调用是幂等的。 */
    public void markRead(final UUID playerId) {
        if (readers.add(playerId)) {
            setChanged();
        }
    }

    /** 已读玩家数。用来判断"这是否是第一个读它的人"。 */
    public int readerCount() {
        return readers.size();
    }

    @Override
    protected void saveAdditional(final CompoundTag tag) {
        super.saveAdditional(tag);
        if (!readers.isEmpty()) {
            final ListTag list = new ListTag();
            for (final UUID id : readers) {
                list.add(StringTag.valueOf(id.toString()));
            }
            tag.put(READERS, list);
        }
    }

    @Override
    public void load(final CompoundTag tag) {
        super.load(tag);
        readers.clear();
        final ListTag list = tag.getList(READERS, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            try {
                readers.add(UUID.fromString(list.getString(i)));
            } catch (final IllegalArgumentException ignored) {
                // 存档里出现非法 UUID 不该让整个方块实体加载失败 —— 跳过即可。
            }
        }
    }
}
