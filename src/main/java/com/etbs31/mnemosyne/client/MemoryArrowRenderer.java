package com.etbs31.mnemosyne.client;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.entity.MemoryArrowEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * 忆矢的渲染器 —— 面向相机的公告板四边形（billboard quad）。
 *
 * <p><b>为什么是"公告板 + 贴图"而不是别的</b>：
 * 这是 ISS 里 {@code MagicArrowRenderer} / {@code MagicMissileRenderer} 的做法 ——
 * 投射物生命周期只有一两秒、体积又小，用完整的 3D 模型（GeckoLib）是纯浪费：
 * 要么多一份模型文件与动画，要么多一次 {@code ModelLayerLocation} 注册。
 * 一个正对相机的四边形 + 一张 32×32 的自绘贴图就够，而且**贴图是我们自己生成的**，
 * 不涉及 ISS 的资源授权问题（ISS 是 All Rights Reserved，它的 png 不能进我们的 jar）。
 *
 * <p><b>⚠️ 漏注册这个渲染器的后果是静默的</b>：
 * 实体照常飞、照常命中、照常有拖尾粒子，只是**看不见本体**。
 * 日志里只有 debug 级的 {@code Missing renderer for entity type}，默认不输出。
 * 注册点见 {@link ClientSetup#onRegisterRenderers}。
 *
 * <p><b>渲染细节</b>：
 * <ul>
 *   <li>用 {@code entityTranslucent}（允许半透明边缘），贴图边缘做羽化，避免看到硬边方框；</li>
 *   <li>{@code cameraOrientation()} 拿到当前相机朝向的四元数，让四边形始终正对玩家；</li>
 *   <li>再绕 Y 转 180° 是为了抵消 {@code cameraOrientation} 的朝向，让贴图正着显示
 *       （照抄原版 {@code ThrownItemRenderer} / ISS 投射物渲染器的做法）；</li>
 *   <li>⚠️ 不要用 {@code RenderType.entityCutout}：它是 alpha 二值裁剪，
 *       羽化边缘会变成硬锯齿。</li>
 * </ul>
 *
 * <p><b>2026-09-18 增强</b>（配合 {@code MemoryArrowEntity} 的粒子强化）：
 * <ol>
 *   <li><b>外发光层</b>：把同一张贴图放大 {@link #HALO_SCALE} 倍、用
 *       {@code entityTranslucentEmissive} + 低顶点 alpha 再画一遍，做出"光晕"。
 *       贴图本身就是"白心 → 品红 → 靛蓝羽化边"的径向渐变，放大后天然就是一圈辉光，
 *       不需要第二张贴图；</li>
 *   <li><b>呼吸脉动</b>：本体大小随 tick 做 ±8% 的正弦缩放，静止悬停时也"活着"；</li>
 *   <li>外发光层用 {@code LightTexture.FULL_BRIGHT} —— 否则在洞穴里"发光的记忆碎片"会跟着变暗。</li>
 * </ol>
 */
public class MemoryArrowRenderer extends EntityRenderer<MemoryArrowEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "textures/entity/memory_arrow.png");

    /** 四边形半边长（格）。0.55 大约是一个玩家拳头的大小，在视野里足够醒目又不夸张。 */
    private static final float HALF_SIZE = 0.55F;

    /** 外发光层的相对大小。贴图边缘已羽化，放大到 2 倍以上只会得到一圈干净的辉光。 */
    private static final float HALO_SCALE = 2.0F;

    /** 外发光的顶点透明度（0~255）。太高会糊住本体，45 左右刚好是"看得见但不抢戏"。 */
    private static final int HALO_ALPHA = 45;

    /** 呼吸脉动的幅度（±8%）与角速度。 */
    private static final float PULSE_AMPLITUDE = 0.08F;
    private static final float PULSE_SPEED = 0.6F;

    public MemoryArrowRenderer(final EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(final MemoryArrowEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(final MemoryArrowEntity entity, final float entityYaw, final float partialTick,
                       final PoseStack pose, final MultiBufferSource buffer, final int packedLight) {
        pose.pushPose();

        // ① 正对相机
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        // ② 抵消朝向，让贴图正着显示（照抄原版 ThrownItemRenderer）
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));

        final PoseStack.Pose last = pose.last();
        final Matrix4f matrix = last.pose();
        final Matrix3f normal = last.normal();

        // ③ 呼吸脉动：本体大小随 tick 做正弦缩放
        final float pulse = 1.0F + PULSE_AMPLITUDE
                * Mth.sin((entity.tickCount + partialTick) * PULSE_SPEED);
        final float half = HALF_SIZE * pulse;

        // ④ 外发光层（全亮、低 alpha、放大）
        final VertexConsumer halo = buffer.getBuffer(RenderType.entityTranslucentEmissive(TEXTURE));
        quad(halo, matrix, normal, LightTexture.FULL_BRIGHT, half * HALO_SCALE, HALO_ALPHA);

        // ⑤ 本体
        final VertexConsumer core = buffer.getBuffer(RenderType.entityTranslucent(TEXTURE));
        quad(core, matrix, normal, packedLight, half, 255);

        pose.popPose();

        super.render(entity, entityYaw, partialTick, pose, buffer, packedLight);
    }

    /** 画一个以原点为中心、半边长 {@code half} 的公告板四边形。 */
    private static void quad(final VertexConsumer consumer, final Matrix4f matrix, final Matrix3f normal,
                             final int packedLight, final float half, final int alpha) {
        vertex(consumer, matrix, normal, packedLight, alpha, -half, -half, 0.0F, 0.0F, 1.0F);
        vertex(consumer, matrix, normal, packedLight, alpha, half, -half, 0.0F, 1.0F, 1.0F);
        vertex(consumer, matrix, normal, packedLight, alpha, half, half, 0.0F, 1.0F, 0.0F);
        vertex(consumer, matrix, normal, packedLight, alpha, -half, half, 0.0F, 0.0F, 0.0F);
    }

    /** 一个顶点。法线固定朝 +Z（公告板本来就正对相机，不需要逐面法线）。 */
    private static void vertex(final VertexConsumer consumer, final Matrix4f matrix, final Matrix3f normal,
                               final int packedLight, final int alpha,
                               final float x, final float y, final float z,
                               final float u, final float v) {
        consumer.vertex(matrix, x, y, z)
                .color(255, 255, 255, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(packedLight)
                .normal(normal, 0.0F, 0.0F, 1.0F)
                .endVertex();
    }
}
