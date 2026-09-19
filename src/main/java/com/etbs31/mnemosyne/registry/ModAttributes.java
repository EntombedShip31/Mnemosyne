package com.etbs31.mnemosyne.registry;

import com.etbs31.mnemosyne.MnemosyneMod;
import io.redspace.ironsspellbooks.api.attribute.MagicPercentAttribute;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 忆海的两个学派属性。
 *
 * <p><b>文件归属</b>：WS-A 注册层。
 *
 * <p><b>实测依据</b>（{@code io.redspace.ironsspellbooks.api.registry.AttributeRegistry.java}，
 * 该源码随 ISS 的 {@code :api} jar 一起发布）：
 * <pre>{@code
 * public static final RegistryObject<Attribute> FIRE_SPELL_POWER = newPowerAttribute("fire");
 * private static RegistryObject<Attribute> newPowerAttribute(String id) {
 *     return ATTRIBUTES.register(id + "_spell_power",
 *         () -> (new MagicPercentAttribute("attribute.irons_spellbooks." + id + "_spell_power",
 *                                          1.0D, -100, 100).setSyncable(true)));
 * }
 * }</pre>
 *
 * <p><b>⚠️ 关键：默认值是 1.0，不是 0.0。</b>
 * ISS 的 {@code SchoolType.getResistanceFor()} 是
 * {@code hasAttribute ? getAttributeValue : 1} —— 抗性属性是**乘数**，1.0 表示不减免。
 * 若照设计稿把默认值写成 0.0，所有记忆法术伤害会被乘以 0，直接变成零伤害。
 *
 * <p>属性 id 必须与 ISS 命名规范对齐（{@code <school>_spell_power} / {@code <school>_magic_resist}），
 * 不能用 {@code mnemosyne_spell_power} —— 见 {@code docs/tech/02_学派与属性注册.md} §3.2。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModAttributes {

    private ModAttributes() {}

    /** Forge 原生属性注册表（不是 ISS 的自定义注册表）。 */
    private static final DeferredRegister<Attribute> ATTRIBUTES =
            DeferredRegister.create(Registries.ATTRIBUTE, MnemosyneMod.MODID);

    /** 记忆法术强度，契约 id：{@code mnemosyne:memory_spell_power}，默认 1.0。 */
    public static final RegistryObject<Attribute> MEMORY_SPELL_POWER = ATTRIBUTES.register(
            "memory_spell_power",
            () -> new MagicPercentAttribute("attribute.mnemosyne.memory_spell_power", 1.0D, -100.0D, 100.0D)
                    .setSyncable(true));

    /** 记忆法术抗性，契约 id：{@code mnemosyne:memory_magic_resist}，默认 1.0（= 不减免）。 */
    public static final RegistryObject<Attribute> MEMORY_MAGIC_RESIST = ATTRIBUTES.register(
            "memory_magic_resist",
            () -> new MagicPercentAttribute("attribute.mnemosyne.memory_magic_resist", 1.0D, -100.0D, 100.0D)
                    .setSyncable(true));

    /** 由主类在构造函数里调用。 */
    public static void register(final IEventBus modBus) {
        ATTRIBUTES.register(modBus);
    }

    /**
     * 把属性挂到实体上。
     *
     * <p><b>不挂载的后果</b>：{@code LivingEntity.getAttribute(attr)} 返回 {@code null} → NPE，
     * 或属性值恒为 0 → 伤害恒为 0。
     *
     * <p>这里照抄 ISS 的做法：把全部属性加到**所有实体类型**上（不是只加玩家）。
     * 因为记忆法术的"抗性"要能作用于任何被打的生物。
     */
    @SubscribeEvent
    public static void onEntityAttributeModification(final EntityAttributeModificationEvent event) {
        event.getTypes().forEach(entityType ->
                ATTRIBUTES.getEntries().forEach(attribute ->
                        event.add(entityType, attribute.get())));
    }
}
