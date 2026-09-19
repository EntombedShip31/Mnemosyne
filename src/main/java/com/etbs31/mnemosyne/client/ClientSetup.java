package com.etbs31.mnemosyne.client;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.block.MemorySteleBlock;
import com.etbs31.mnemosyne.registry.ModBlocks;
import com.etbs31.mnemosyne.registry.ModEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 忆海的客户端颜色处理（方块染色 / 物品染色）。
 *
 * <p><b>文件归属</b>：WS-J 方块层（客户端侧）。
 *
 * <p><b>忆碑的"靛蓝 → 品红"变色靠这里</b>
 * <br>{@code docs/08} §6.1 要求未读发靛蓝光、被读过后变暗品红，但我们**只有一张贴图**。
 * 解决办法：方块模型 {@code models/block/memory_stele.json} 的每个面都带
 * {@code "tintindex": 0}，再由这里按 {@code read} 状态给颜色。
 *
 * <p><b>⚠️ 为什么这段染色是"降级无害"的</b>
 * <br>未登记的 tint 会被原版解析成白色（{@code -1}），贴图**原样显示**。
 * 也就是说即使这个事件处理器因为任何原因没跑，忆碑也只是一个"不会变色的石柱"，
 * 而不是紫黑格。光照等级 12 → 6 是**独立生效**的（写在 {@code ModBlocks} 的
 * {@code lightLevel} 里，与染色无关）。
 *
 * <p><b>⭐ 曾经这里还有一个 {@code ItemBlockRenderTypes.setRenderLayer(...)}
 * 用来把忆晶簇设成 cutout —— 2026-09-17 已删除，改由模型 JSON 声明。</b>
 * <br>原因：Forge 1.20.1 **原生支持模型里的 {@code render_type} 键**（实测
 * {@code net.minecraftforge.client.model.ExtendedBlockModelDeserializer} 第 70-74 行
 * 读取 {@code render_type} 并解析成 {@code ResourceLocation}；
 * {@code NamedRenderTypeManager.preRegisterVanillaRenderTypes} 预注册了
 * {@code minecraft:solid / cutout / cutout_mipped / cutout_mipped_all / translucent / tripwire}）。
 * 所以 {@code models/block/memory_crystal_cluster.json} 里写
 * {@code "render_type": "minecraft:cutout"} 就够了，而且：
 * <ul>
 *   <li>{@code setRenderLayer} 在 Forge 1.20.1 里已被标 {@code @Deprecated(forRemoval = true)}，
 *       用它会污染编译输出（把真正的警告淹没在噪音里）；</li>
 *   <li>{@code RenderTypeGroup} 同时携带**方块**与**物品**两个渲染层
 *       （{@code NamedRenderTypeManager} 第 51 行：{@code cutout} →
 *       {@code RenderTypeGroup(RenderType.cutout(), ForgeRenderTypes.ITEM_LAYERED_CUTOUT)}），
 *       所以 JSON 写法**连物品栏里的渲染层一起修好了**，而 {@code setRenderLayer} 只管方块。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    private ClientSetup() {}

    /** 未读：靛蓝（与 {@code ModSchools.MEMORY_COLOR} 同值）。 */
    private static final int COLOR_UNREAD = 0x534AB7;

    /** 已读：品红（{@code docs/09} 配色系统的第二主色）。 */
    private static final int COLOR_READ = 0xD4537E;

    /** 方块染色：忆碑按 {@code read} 状态在靛蓝 / 品红之间切换。 */
    @SubscribeEvent
    public static void onRegisterBlockColors(final RegisterColorHandlersEvent.Block event) {
        event.register(
                (state, level, pos, tintIndex) ->
                        state != null && state.getValue(MemorySteleBlock.READ) ? COLOR_READ : COLOR_UNREAD,
                ModBlocks.MEMORY_STELE.get());
    }

    /** 物品染色：物品栏里的忆碑也应该是靛蓝的（否则 tintindex 会被解析成白色）。 */
    @SubscribeEvent
    public static void onRegisterItemColors(final RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> COLOR_UNREAD, ModBlocks.MEMORY_STELE.get());
    }

    /**
     * 实体渲染器（WS-J 实体层）。
     *
     * <p>⚠️ <b>漏掉这一行的后果是静默的</b>：实体照常生成、照常有 AI、照常能被打死，
     * 只是**完全不渲染**（客户端看不到它）。日志里只有一条
     * {@code Missing renderer for entity type} 的 debug 级别信息，默认配置根本不输出。
     */
    @SubscribeEvent
    public static void onRegisterRenderers(final EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.MEMORY_WRAITH.get(), MemoryWraithRenderer::new);
        // ⚠️ 忆矢的渲染器。漏掉这一行：箭照常飞、照常命中、有拖尾粒子，但**看不见本体**。
        event.registerEntityRenderer(ModEntities.MEMORY_ARROW.get(), MemoryArrowRenderer::new);
    }
}
