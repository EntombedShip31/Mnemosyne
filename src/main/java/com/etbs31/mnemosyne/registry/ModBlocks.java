package com.etbs31.mnemosyne.registry;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.block.MemoryCrystalClusterBlock;
import com.etbs31.mnemosyne.block.MemorySteleBlock;
import com.etbs31.mnemosyne.block.entity.MemorySteleBlockEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

/**
 * 忆海方块注册。
 *
 * <p><b>文件归属</b>：WS-J 方块层。
 *
 * <p><b>⭐ 为什么这四个方块必须存在（不是"锦上添花"）</b>
 * <br>{@code docs/tech/12} §五·五 的 23 个结构模板 NBT 要用它们搭出忆者遗迹；
 * 而 {@code docs/tech} 的 worldgen 硬事实是：**worldgen 注册表解析失败会让存档直接加载不了**
 * （{@code IllegalStateException("Failed to load registries due to above errors")}），
 * {@code loot_tables} 解析失败则把整张表**静默变空**。
 * 也就是说：NBT 里引用一个没注册的方块 id，轻则遗迹变空气，重则存档打不开。
 * 所以方块注册是 WS-G2（结构 NBT）的**前置依赖**，必须排在它前面。
 *
 * <p>这同时解掉 4 张孤儿贴图（{@code textures/block/*.png} 有文件但没有方块引用它们）。
 *
 * <p><b>⚠️ 仍然缺的两个方块</b>：{@code docs/07} §五 与 {@code docs/08} §4.4 还提到
 * **忆晶块**（亮度 7）与**忆晶灯**（亮度 14），但它们**没有贴图**
 * （{@code textures/block/} 下只有 4 个文件）。**刻意不注册** ——
 * 缺贴图的方块在游戏里是紫黑格，正是本项目最要防的静默失败。
 * 等美术补齐贴图后再加，已登记在 {@code docs/tech/12} 的欠账表里。
 */
public final class ModBlocks {

    private ModBlocks() {}

    private static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MnemosyneMod.MODID);

    private static final DeferredRegister<Item> BLOCK_ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MnemosyneMod.MODID);

    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MnemosyneMod.MODID);

    // ==================================================================
    // 忆砖（忆者遗迹的墙体主材）
    // ==================================================================

    /** 忆砖 —— 靛蓝色砖块，遗迹的墙体/地板主材。贴图 16×16 全不透明 → 完整立方体模型。 */
    public static final RegistryObject<Block> MNEMONIC_BRICKS = registerBlock("mnemonic_bricks",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(1.5F, 6.0F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()));

    /** 裂纹忆砖 —— 风蚀/坍塌区的变体。同样是完整立方体，只是换贴图。 */
    public static final RegistryObject<Block> MNEMONIC_BRICKS_CRACKED = registerBlock("mnemonic_bricks_cracked",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(1.5F, 6.0F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()));

    // ==================================================================
    // 忆碑（2 格高，核心交互物）
    // ==================================================================

    /**
     * 忆碑 —— 2 格高的石柱，右键解锁一个记忆法术。
     *
     * <p>光照：未读 12 / 已读 6（{@code docs/08} §6.1）。
     * 注意 {@code lightLevel} 拿到的 {@code BlockState} 一定带 {@code read} 属性 ——
     * 因为构造器里已经 {@code registerDefaultState} 给了默认值。
     *
     * <p>{@code pushReaction(BLOCK)}：双半结构被活塞推动会拆散，直接禁止推动。
     */
    public static final RegistryObject<Block> MEMORY_STELE = registerBlock("memory_stele",
            () -> new MemorySteleBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(2.5F, 8.0F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> state.getValue(MemorySteleBlock.READ) ? 6 : 12)
                    .pushReaction(PushReaction.BLOCK)));

    // ==================================================================
    // 忆晶簇（光源 + 忆晶产出）
    // ==================================================================

    /**
     * 忆晶簇 —— 地面生长的靛蓝晶簇，亮度 5，挖掉掉忆晶。
     *
     * <p>{@code noCollission}：走 {@code block/cross} 十字模型，不该挡路。
     * {@code noOcclusion}：十字模型必须关掉面剔除，否则相邻方块的面会被错误剔除。
     * <b>这两个属性缺一个都会出现"方块旁边有黑洞"的经典渲染 bug。</b>
     */
    public static final RegistryObject<Block> MEMORY_CRYSTAL_CLUSTER = registerBlock("memory_crystal_cluster",
            () -> new MemoryCrystalClusterBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .noCollission()
                    .noOcclusion()
                    .instabreak()
                    .sound(SoundType.AMETHYST)
                    .lightLevel(state -> 5)
                    .pushReaction(PushReaction.DESTROY)));

    // ==================================================================
    // 方块实体
    // ==================================================================

    /** 忆碑的方块实体（存 per-player 已读集合）。 */
    public static final RegistryObject<BlockEntityType<MemorySteleBlockEntity>> MEMORY_STELE_BE =
            BLOCK_ENTITIES.register("memory_stele",
                    () -> BlockEntityType.Builder
                            .of(MemorySteleBlockEntity::new, MEMORY_STELE.get())
                            .build(null));

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 注册方块**并**自动补一个 {@link BlockItem}。
     *
     * <p>每个方块都需要对应的物品，否则方块拿不到手上、进不了创造模式标签页、
     * 也没法在结构里被 {@code /setblock} 之外的途径获得。
     * 一起注册可以保证"加了方块但忘了加物品"这个经典遗漏不会发生。
     */
    private static <T extends Block> RegistryObject<T> registerBlock(final String name, final Supplier<T> supplier) {
        final RegistryObject<T> block = BLOCKS.register(name, supplier);
        BLOCK_ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }

    /** 由主类在构造函数里调用。 */
    public static void register(final IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}
