package com.etbs31.mnemosyne.client;

import com.etbs31.mnemosyne.entity.MemoryWraithEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * 忆魇的模型 —— 直接复用**原版人形几何**（{@code ModelLayers.ZOMBIE} 那套），
 * 只做三件事：藏腿、手臂前伸、整体上下浮动。
 *
 * <p><b>文件归属</b>：WS-J 实体层（客户端侧）。
 *
 * <p><b>⭐ 为什么复用原版几何而不是自己写 {@code MeshDefinition}</b>
 * <br>自写几何意味着"模型 UV 布局"和"贴图"要人工对齐 —— 对不齐的表现是
 * 模型上出现**错位的色块**，而且没有报错。复用原版几何后，
 * 布局由 {@code HumanoidModel.createMesh} 保证，贴图按同一份布局画（见
 * {@code tools/gen_mnemosyne_art.py} 的 {@code gen_wraith}），两边不可能不一致。
 * 需要注册的 {@code ModelLayerLocation} 也省了 —— {@code ModelLayers.ZOMBIE} 是现成的。
 *
 * <p><b>⚠️ 为什么"上下浮动"改的是各个 {@code ModelPart.y}，而不是 {@code root.y}</b>
 * <br>两个坑叠在一起：
 * <ol>
 *   <li>{@code HumanoidModel.renderToBuffer} 是**逐个 part 渲染**的，根本不经过 root
 *       → 改 {@code root.y} 完全不会生效（不报错，只是没反应）；</li>
 *   <li>{@code ModelPart.y} 是"相对初始姿态的偏移"，每帧 {@code +=} 会**累积**成无限下落。</li>
 * </ol>
 * 所以构造时把各 part 的初始 y 存下来，每帧写成 {@code 初始值 + 浮动量}。
 */
public class MemoryWraithModel extends HumanoidModel<MemoryWraithEntity> {

    /** 浮动幅度（格）。 */
    private static final float BOB_AMPLITUDE = 0.9F;
    /** 浮动频率。 */
    private static final float BOB_SPEED = 0.09F;

    /** 各 part 的初始 y（{@code PartPose.offset} 给的值），用来做无累积的浮动。 */
    private final float headBaseY;
    private final float bodyBaseY;
    private final float rightArmBaseY;
    private final float leftArmBaseY;

    public MemoryWraithModel(final ModelPart root) {
        super(root);
        // 忆魇是漂浮的：藏掉双腿，只留兜帽 + 袍身 + 两条手臂
        this.rightLeg.visible = false;
        this.leftLeg.visible = false;

        this.headBaseY = this.head.y;
        this.bodyBaseY = this.body.y;
        this.rightArmBaseY = this.rightArm.y;
        this.leftArmBaseY = this.leftArm.y;
    }

    @Override
    public void setupAnim(final MemoryWraithEntity entity, final float limbSwing,
                          final float limbSwingAmount, final float ageInTicks,
                          final float netHeadYaw, final float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        // ---- 整体上下浮动 ----
        final float bob = Mth.sin(ageInTicks * BOB_SPEED) * BOB_AMPLITUDE;
        this.head.y = this.headBaseY + bob;
        this.body.y = this.bodyBaseY + bob;
        this.rightArm.y = this.rightArmBaseY + bob;
        this.leftArm.y = this.leftArmBaseY + bob;

        // ---- 双臂前伸 + 缓慢摆动（"伸手抓记忆"的姿态）----
        // 放在 super 之后：super 已经按行走动画算过手臂角度，这里整体覆盖掉 ——
        // 飞行怪的 walkAnimation 一直是 0，本来也摆不起来。
        this.rightArm.xRot = -1.15F + Mth.cos(ageInTicks * 0.10F) * 0.12F;
        this.leftArm.xRot = -1.15F + Mth.sin(ageInTicks * 0.10F) * 0.12F;
        this.rightArm.zRot = 0.18F;
        this.leftArm.zRot = -0.18F;

        // ---- 头部极轻微的摇晃：让它看起来"不太清醒"----
        this.head.zRot = Mth.sin(ageInTicks * 0.06F) * 0.06F;
    }
}
