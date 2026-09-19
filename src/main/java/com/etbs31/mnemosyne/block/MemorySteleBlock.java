package com.etbs31.mnemosyne.block;

import com.etbs31.mnemosyne.block.entity.MemorySteleBlockEntity;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.util.SteleUnlocks;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 忆碑 —— 忆者遗迹的核心交互物（2 格高石柱，右键"记起一个法术"）。
 *
 * <p><b>文件归属</b>：WS-J 方块层。
 *
 * <p><b>契约来源</b>：{@code docs/08_建筑_忆者遗迹.md} §六。
 *
 * <p><b>实现结构</b>
 * <ul>
 *   <li>2 格高 → 用原版的 {@code DOUBLE_BLOCK_HALF}（LOWER/UPPER）双半结构，
 *       照抄 {@code DoorBlock} 的放置/破坏/联动模式（这是原版验证过的最稳写法）。</li>
 *   <li>方块实体**只挂在 LOWER 半**（{@link #newBlockEntity} 对 UPPER 返回 {@code null}），
 *       UPPER 半的交互与查询都转发到下面那格。</li>
 *   <li>{@code read} 布尔状态 = "是否已被**任何人**读过" → 驱动光照（12 → 6）与染色
 *       （靛蓝 {@code #534AB7} → 品红 {@code #D4537E}，见 {@code client/ClientSetup}）。
 *       **per-player** 的已读记录在 {@link MemorySteleBlockEntity} 里。</li>
 * </ul>
 *
 * <p><b>⚠️ 与文档的两处偏差（已在 {@code docs/tech/12} 登记）</b>
 * <ol>
 *   <li>§6.2 的"8 秒失落记忆过场（锁视角 + 屏幕中央逐行浮现文本 + 反向混响）"
 *       是纯客户端表现层，属 WS-I。本类只把失落记忆文本用**聊天栏**发出去，
 *       并播放 {@code ui.stele.read} / {@code ui.learn} 音效与粒子。</li>
 *   <li>§6.1 的"未读发靛蓝光 / 已读变品红"**只有一张贴图**，靠方块染色实现
 *       颜色变化（光照等级是真实的 12 → 6）。</li>
 * </ol>
 *
 * <p><b>⚠️ 为什么"学习法术"要写 {@code var} 而不是显式类型</b>
 * <br>{@code MagicData.getSyncedData()} 的返回类型 {@code SyncedSpellData} 位于
 * {@code capabilities.magic} 包（**不是** {@code api} 包），按项目纪律不允许 import。
 * {@code var} 让 javac 用局部变量推断，既拿到 {@code learnSpell(AbstractSpell)}
 * 又不用把那行 {@code import} 写进源码。**读取**方向有 api 层的
 * {@code AbstractSpell.isLearned(Player)}，所以只有"写入"需要这一手。
 */
public class MemorySteleBlock extends Block implements EntityBlock {

    /** 双半结构（照抄 {@code DoorBlock}）。 */
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    /**
     * "已被任何人读过"。
     *
     * <p>⚠️ 自定义状态属性必须自己 {@code registerDefaultState} 给默认值，
     * 否则 {@code stateDefinition.any()} 里的属性是未赋值的，放置时会抛异常。
     */
    public static final BooleanProperty READ = BooleanProperty.create("read");

    public MemorySteleBlock(final Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(HALF, DoubleBlockHalf.LOWER)
                .setValue(READ, Boolean.FALSE));
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HALF, READ);
    }

    // ==================================================================
    // 双半结构
    // ==================================================================

    /** 只有上面那格可以被替换时才允许放置（否则只放下半段会留下悬空碑头）。 */
    @Nullable
    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        final BlockPos above = context.getClickedPos().above();
        if (!context.getLevel().getBlockState(above).canBeReplaced()) {
            return null;
        }
        return defaultBlockState().setValue(HALF, DoubleBlockHalf.LOWER).setValue(READ, Boolean.FALSE);
    }

    @Override
    public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state,
                            @Nullable final LivingEntity placer, final ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
    }

    /**
     * 拆掉任意一半时，另一半也要走正常掉落流程。
     *
     * <p>只有 UPPER → 拆 LOWER 需要显式处理；LOWER → 拆 UPPER 由
     * {@link #updateShape} 自动完成（下方邻居变了会触发它）。
     * 这是 {@code DoorBlock} 的原版做法。
     */
    @Override
    public void playerWillDestroy(final Level level, final BlockPos pos, final BlockState state, final Player player) {
        if (!level.isClientSide && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            final BlockPos below = pos.below();
            final BlockState belowState = level.getBlockState(below);
            if (belowState.is(this) && belowState.getValue(HALF) == DoubleBlockHalf.LOWER) {
                level.setBlock(below, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                level.levelEvent(player, 2001, below, Block.getId(belowState));
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * 双半联动：另一半没了就消失，同时把 {@code read} 同步过去，
     * 保证上下两格的光照与染色永远一致。
     */
    @Override
    public BlockState updateShape(final BlockState state, final Direction direction, final BlockState neighborState,
                                  final LevelAccessor level, final BlockPos pos, final BlockPos neighborPos) {
        final DoubleBlockHalf half = state.getValue(HALF);
        // 只有当"变化的邻居"是自己在 Y 轴上对应的那一半时才处理
        if (direction.getAxis() == Direction.Axis.Y
                && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)) {
            return neighborState.is(this) && neighborState.getValue(HALF) != half
                    ? state.setValue(READ, neighborState.getValue(READ))
                    : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    /** 方块实体只挂在下半段，避免同一座碑出现两份"已读玩家"记录。 */
    @Nullable
    @Override
    public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER
                ? new MemorySteleBlockEntity(pos, state)
                : null;
    }

    // ==================================================================
    // 交互
    // ==================================================================

    @Override
    public InteractionResult use(final BlockState state, final Level level, final BlockPos pos,
                                 final Player player, final InteractionHand hand, final BlockHitResult hit) {
        final BlockPos lowerPos = state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;

        if (level.isClientSide) {
            // 客户端只负责"挥手"；真正的逻辑全在服务端，避免双端不同步。
            return level.getBlockEntity(lowerPos) instanceof MemorySteleBlockEntity
                    ? InteractionResult.SUCCESS
                    : InteractionResult.PASS;
        }

        if (!(player instanceof ServerPlayer serverPlayer)
                || !(level.getBlockEntity(lowerPos) instanceof MemorySteleBlockEntity stele)) {
            return InteractionResult.PASS;
        }

        // ---- 已经读过 → 拒绝 ----
        if (stele.hasRead(serverPlayer.getUUID())) {
            serverPlayer.displayClientMessage(Component.translatable("mnemosyne.msg.stele_exhausted"), true);
            level.playSound(null, pos, ModSounds.BLOCK_MEMORY_STELE_EXHAUSTED.get(),
                    SoundSource.BLOCKS, 0.8F, 1.0F);
            return InteractionResult.CONSUME;
        }

        // ---- 抽签 ----
        final AbstractSpell unlocked = SteleUnlocks.rollFor(serverPlayer, level.random);
        if (unlocked == null) {
            serverPlayer.displayClientMessage(Component.translatable("mnemosyne.msg.stele_all_learned"), true);
            level.playSound(null, pos, ModSounds.BLOCK_MEMORY_STELE_EXHAUSTED.get(),
                    SoundSource.BLOCKS, 0.8F, 1.0F);
            return InteractionResult.CONSUME;
        }

        // ---- 学习 ----
        // ⚠️ 这里必须用 var：SyncedSpellData 在非 api 包，见类注释。
        final var synced = MagicData.getPlayerMagicData(serverPlayer).getSyncedData();
        synced.learnSpell(unlocked);

        stele.markRead(serverPlayer.getUUID());

        // 第一个读它的人 → 全碑变暗（光照 12 → 6，染色靛蓝 → 品红）
        if (stele.readerCount() == 1) {
            setRead(level, lowerPos, true);
        }

        // ---- 反馈 ----
        serverPlayer.displayClientMessage(
                Component.translatable("mnemosyne.msg.stele_learned",
                        unlocked.getDisplayName(serverPlayer)), false);
        level.playSound(null, pos, ModSounds.UI_STELE_READ.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
        level.playSound(null, pos, ModSounds.UI_LEARN.get(), SoundSource.PLAYERS, 1.0F, 1.2F);

        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.END_ROD,
                    pos.getX() + 0.5D, pos.getY() + 1.5D, pos.getZ() + 0.5D,
                    24, 0.6D, 0.9D, 0.6D, 0.05D);
        }

        // ---- 第一座碑的额外提示（docs/08 §6.2 第 3 步） ----
        final int reads = SteleUnlocks.incrementReadCount(serverPlayer);
        if (reads == 1) {
            serverPlayer.sendSystemMessage(Component.translatable("mnemosyne.msg.stele_first_hint"));
        }


        return InteractionResult.CONSUME;
    }


    /** 把 {@code read} 同时写到上下两格。 */
    private void setRead(final Level level, final BlockPos lowerPos, final boolean read) {
        final BlockState lower = level.getBlockState(lowerPos);
        if (lower.is(this)) {
            level.setBlock(lowerPos, lower.setValue(READ, read), Block.UPDATE_ALL);
        }
        final BlockPos upperPos = lowerPos.above();
        final BlockState upper = level.getBlockState(upperPos);
        if (upper.is(this)) {
            level.setBlock(upperPos, upper.setValue(READ, read), Block.UPDATE_ALL);
        }
    }
}
