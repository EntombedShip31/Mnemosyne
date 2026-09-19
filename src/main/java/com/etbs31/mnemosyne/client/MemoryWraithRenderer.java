package com.etbs31.mnemosyne.client;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.entity.MemoryWraithEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * 忆魇的渲染器。
 *
 * <p><b>文件归属</b>：WS-J 实体层（客户端侧）。
 *
 * <p><b>⭐ 贴图路径必须是 {@code textures/entity/memory_wraith.png}</b>
 * <br>贴图找不到时原版**不会报错**，只会渲染成紫黑格 —— 而紫黑格恰好是
 * 这个项目最常踩的静默失败。贴图由 {@code tools/gen_mnemosyne_art.py} 的
 * {@code gen_wraith()} 生成，{@code --check} 会校验它存在、尺寸 64x64、非全透明。
 *
 * <p><b>半透明渲染</b>：与 {@code VexRenderer} 同一做法。忆魇的贴图自带 alpha
 * （袍身 ~200），所以能透出后面的景物，是"幽灵"该有的观感。
 */
public class MemoryWraithRenderer extends MobRenderer<MemoryWraithEntity, MemoryWraithModel> {

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            MnemosyneMod.MODID, "textures/entity/memory_wraith.png");

    public MemoryWraithRenderer(final EntityRendererProvider.Context context) {
        // 复用原版人形几何（ZOMBIE 那套 64x64 布局）—— 见 MemoryWraithModel 的类注释
        super(context, new MemoryWraithModel(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(final MemoryWraithEntity entity) {
        return TEXTURE;
    }

    @Override
    protected RenderType getRenderType(final MemoryWraithEntity entity, final boolean visible,
                                       final boolean invisibleToPlayer, final boolean glowing) {
        return RenderType.entityTranslucent(TEXTURE);
    }
}
