package com.etbs31.mnemosyne.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 忆晶簇 —— 从地面生长出的靛蓝色晶簇（{@code block/cross} 十字模型）。
 *
 * <p><b>文件归属</b>：WS-J 方块层。
 *
 * <p><b>⚠️ 为什么是"仅地面"而不是"可贴墙"</b>
 * <br>{@code docs/08} §五 写的是"从墙壁/地面生长出"。但实测贴图
 * {@code memory_crystal_cluster.png}（2026-09-17，Pillow 逐像素）：
 * <pre>
 *   列不透明数 [5,5,5,9,9,9,5,14,14,14,11,11,11,6,6,5]
 *   行不透明数 [0,0,3,3,3,6,6,9,9,9,11,16,16,16,16,16]
 *   包围盒 x:0-15 y:2-15
 * </pre>
 * 顶部两行全空、底部五行满宽 —— 这是**底边贴地的直立晶体**，正是
 * {@code block/cross}（两片交叉面）的语义。要支持六向贴附必须做成
 * 紫水晶簇那样的 6 向独立模型（{@code _north}/{@code _up}/… 共 7 个模型文件），
 * 而贴图只画了"向上生长"这一种朝向。
 * → 本类只实现**地面放置**；遗迹 NBT 把晶簇摆在核心室的**地板与墙边台阶**上即可
 * （{@code docs/08} §4.4 的"墙壁上生长"用墙脚地板位近似）。
 *
 * <p><b>⚠️ 为什么继承 {@code Block} 而不是 {@code BushBlock}</b>
 * <br>{@code BushBlock.mayPlaceOn} 只接受 {@code #minecraft:dirt} 一类方块，
 * 而忆晶簇要长在**忆砖 / 石头 / 深板岩**上。所以自己写 {@link #canSurvive}，
 * 判据放宽为"下方是完整实心面"。
 */
public class MemoryCrystalClusterBlock extends Block {

    /** 与贴图不透明包围盒对齐（x 1-15 / y 0-14）。无碰撞，仅供射线与轮廓使用。 */
    private static final VoxelShape SHAPE = Block.box(1.0D, 0.0D, 1.0D, 15.0D, 14.0D, 15.0D);

    public MemoryCrystalClusterBlock(final Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos,
                               final CollisionContext context) {
        return SHAPE;
    }

    /**
     * 下方必须是完整实心面。
     *
     * <p>用 {@code isFaceSturdy} 而不是硬编码方块白名单 —— 这样任何"看起来能站东西"的
     * 方块（忆砖、石头、深板岩、甚至其他模组的机器）都能承托晶簇。
     */
    @Override
    public boolean canSurvive(final BlockState state, final LevelReader level, final BlockPos pos) {
        final BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    /**
     * 支撑方块被挖掉时自动脱落。
     *
     * <p>⚠️ 返回 {@code Blocks.AIR.defaultBlockState()} 会让本方块**掉落自身**
     * （走正常战利品表），而不是静默消失 —— 这正是我们要的。
     */
    @Override
    public BlockState updateShape(final BlockState state, final Direction direction, final BlockState neighborState,
                                  final LevelAccessor level, final BlockPos pos, final BlockPos neighborPos) {
        if (direction == Direction.DOWN && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }
}
