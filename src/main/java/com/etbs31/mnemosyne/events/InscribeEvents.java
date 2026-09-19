package com.etbs31.mnemosyne.events;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.capability.MnemosyneData;
import io.redspace.ironsspellbooks.api.events.InscribeSpellEvent;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 铭刻事件 —— 忆海法术的"记忆承载力"校验。
 *
 * <p><b>文件归属</b>：WS-F 卷轴与铭刻（{@code docs/tech/10} §六）。
 * <p>⚠️ 2026-09-18：忆海自己的 5 本法书已删除（复用 ISS 官方法书），
 * 本类只对<b>铭刻</b>（抄书）生效，与法书物品无关。
 *
 * <p><b>实现依据</b>：{@code docs/tech/05} §7.1。该节的示例代码调用了
 * {@code MnemosyneData.getMemoryLoad} / {@code getMaxMemoryLoad} ——
 * 这两个方法在 WS-B 交付的真实 API 里<b>不存在</b>，
 * 实际应映射到冻结契约里的 {@link MnemosyneData#getUsedEngrams} /
 * {@link MnemosyneData#getMaxEngrams}（{@code docs/tech/11} §六）。
 *
 * <p><b>⭐ 事件真的会被触发吗（实测）</b>：会。
 * 字节码实测 {@code gui.inscription_table.InscriptionTableMenu} 里构造并 post 了
 * {@code InscribeSpellEvent}，且 {@code isCancelable()} 返回 {@code true}。
 * <br>⚠️ 但同一批实测发现 {@code CustomizeScrollModNameEvent}（{@code docs/tech/05} §7.2
 * 列为"可用"）在 3.16.3 里<b>从未被 post</b> ——
 * 只有 {@code util.TooltipsUtils} 调用了它的<b>静态</b>方法
 * {@code resolveModLabel(String)}，常量池里没有任何 {@code new CustomizeScrollModNameEvent}
 * 的引用。所以本类<b>不</b>为它写监听器（写了也永远不会被调用）。
 * 卷轴 tooltip 的模组名由 {@code resolveModLabel("mnemosyne")} 直接查
 * {@code ModList} 得到，我们的 {@code mods.toml displayName} 已经配好，无需干预。
 *
 * <p><b>⚠️ 一条需要集成者裁决的设计问题</b>
 * <br>按 {@code docs/tech/05} §7.1 的字面实现，规则是"忆格已满 → 禁止铭刻忆海法术"。
 * 但玩家在战斗中忆格<b>经常是满的</b>，这条规则会变成"想抄书先丢记忆"的硬门槛。
 * 已按规范字面实现（见 {@link #onInscribe}），但建议改为更温和的规则，例如
 * "铭刻忆海法术的总数不超过 {@code getMaxEngrams(player)}"。
 * 改动只需调整本方法体，不影响任何跨工作流契约。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class InscribeEvents {

    private InscribeEvents() {}

    /** 铭刻被拒时的提示键（{@code en_us} / {@code zh_cn} 均已补齐）。 */
    public static final String KEY_MEMORY_OVERLOAD = "mnemosyne.msg.memory_overload";

    /**
     * 铭刻忆海法术时的记忆承载力校验。
     *
     * <p>只对<b>忆海学派</b>的法术生效；其他学派的铭刻完全不受影响。
     * 客户端不处理（铭刻的权威判定在服务端菜单里）。
     */
    @SubscribeEvent
    public static void onInscribe(final InscribeSpellEvent event) {
        final SpellData data = event.getSpellData();
        if (data == null || data.getSpell() == null) {
            return;
        }

        // 只拦忆海自己：用命名空间判断，而不是硬编码学派 ResourceLocation
        final ResourceLocation schoolId = data.getSpell().getSchoolType().getId();
        if (!MnemosyneMod.MODID.equals(schoolId.getNamespace())) {
            return;
        }

        final Player player = event.getEntity();
        if (player.level().isClientSide()) {
            return;
        }

        if (!MnemosyneData.hasFreeSlot(player)) {
            event.setCanceled(true);
            player.displayClientMessage(
                    Component.translatable(KEY_MEMORY_OVERLOAD,
                            MnemosyneData.getUsedEngrams(player),
                            MnemosyneData.getMaxEngrams(player)),
                    true);
            MnemosyneMod.LOGGER.debug("玩家 {} 忆格已满（{}/{}），拒绝铭刻 {}",
                    player.getName().getString(),
                    MnemosyneData.getUsedEngrams(player),
                    MnemosyneData.getMaxEngrams(player),
                    schoolId);
        }
    }
}
