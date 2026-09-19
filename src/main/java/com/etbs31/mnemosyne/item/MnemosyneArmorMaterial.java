package com.etbs31.mnemosyne.item;

import com.etbs31.mnemosyne.registry.ModItems;
import com.etbs31.mnemosyne.registry.ModSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * 忆者法袍的护甲材质（{@code mnemosyne:mnemonic}）。
 *
 * <p><b>文件归属</b>：WS-J 物品层。
 *
 * <p><b>数值来源</b>：{@code docs/07_装备与道具.md} §2.2 —— 单件护甲值 3 / 8 / 6 / 3（合计 20），
 * 与铁魔法现有学派套装保持一致。**每件的法术强度 / 抗性加成不在这里**，
 * 而在 {@link MnemonicRobeItem#getDefaultAttributeModifiers}（那才是按部位区分的地方）。
 *
 * <p><b>⭐⭐ 1.20.1 的 {@code ArmorMaterial} 是一个纯接口，不是注册表对象</b>
 * <br>实测（2026-09-17，读 Forge {@code -patched.jar}）：
 * <ul>
 *   <li>{@code net.minecraft.core.registries.BuiltInRegistries} 里**没有** {@code ARMOR_MATERIAL} 字段
 *       （{@code Registries} 里也没有 —— 那是 1.20.5+ 才变成数据包注册表的）。</li>
 *   <li>{@code ArmorItem} 的构造器签名是
 *       {@code ArmorItem(ArmorMaterial, ArmorItem.Type, Item.Properties)} ——
 *       **直接吃实例，不吃 {@code Holder}**（javap 实测）。</li>
 *   <li>原版 {@code ArmorMaterials} 是个 {@code enum implements ArmorMaterial}，同样没有注册动作。</li>
 * </ul>
 * → 所以我们**不需要任何 DeferredRegister**，一个静态单例就够了。
 * 这也是为什么这里没有 {@code ModArmorMaterials} 注册类。
 *
 * <p><b>⭐⭐ 贴图路径靠 {@code getName()} 的命名空间前缀（Forge 专属行为）</b>
 * <br>原版 {@code HumanoidArmorLayer.m_117080_} 会拼
 * {@code "textures/models/armor/" + getName() + "_layer_1.png"} 并用
 * {@code new ResourceLocation(...)} 解析 —— 那会**强制 namespace = minecraft**，
 * 即自定义护甲贴图必须塞进 {@code assets/minecraft/}。
 * 但 Forge 1.20.1 打了补丁（{@code HumanoidArmorLayer.java.patch}），
 * 新增的 {@code getArmorResource} 会**主动切分 {@code getName()} 里的冒号**：
 * <pre>
 * String texture = item.getMaterial().getName();
 * String domain = "minecraft";
 * int idx = texture.indexOf(':');
 * if (idx != -1) { domain = texture.substring(0, idx); texture = texture.substring(idx + 1); }
 * String s1 = String.format("%s:textures/models/armor/%s_layer_%d%s.png", domain, texture, leggings ? 2 : 1, ...);
 * </pre>
 * 所以 {@link #getName()} 返回 {@code "mnemosyne:mnemonic"} 时，贴图正好落在
 * <b>{@code assets/mnemosyne/textures/models/armor/mnemonic_layer_1.png}</b> 与
 * {@code ..._layer_2.png} —— 与仓库里已有的两张贴图**完全对上**，不需要
 * {@code IClientItemExtensions}，也不需要把贴图挪进 {@code assets/minecraft/}。
 * <br>⚠️ 反过来：如果这里返回纯 {@code "mnemonic"}，Forge 会去找
 * {@code assets/minecraft/textures/models/armor/mnemonic_layer_1.png} → 缺贴图 →
 * 护甲会渲染成**紫黑格**。
 *
 * <p><b>⚠️ 为什么 {@code getEquipSound()} 与 {@code getRepairIngredient()} 是懒求值</b>
 * <br>{@link #MNEMONIC} 是静态字段，会在类初始化时就构造；
 * 而 {@code ModSounds.EQUIP_ROBE} / {@code ModItems.MEMORY_CRYSTAL} 要等
 * Forge 的 {@code RegisterEvent} 之后才有值。在构造器里取 {@code .get()} 会拿到
 * {@code null}（甚至 NPE）。这两个方法都只在游戏内被调用（装备 / 铁砧修复时），
 * 那时注册早已完成 —— 所以把取值放在方法体里，不放字段里。
 */
public final class MnemosyneArmorMaterial implements ArmorMaterial {

    /** 唯一实例。护甲材质在 1.20.1 不需要注册（见类注释）。 */
    public static final MnemosyneArmorMaterial MNEMONIC = new MnemosyneArmorMaterial();

    /**
     * 贴图与材质名。
     *
     * <p>⚠️ **必须带 {@code mnemosyne:} 前缀**，否则 Forge 会去 {@code assets/minecraft/} 找贴图 → 紫黑格。
     */
    private static final String NAME = "mnemosyne:mnemonic";

    /** 耐久系数：介于铁(15)与钻石(33)之间，与"比铁好、比钻石差"的定位一致。 */
    private static final int DURABILITY_MULTIPLIER = 26;

    private static final int ENCHANTMENT_VALUE = 15;
    private static final float TOUGHNESS = 1.0F;
    private static final float KNOCKBACK_RESISTANCE = 0.0F;

    private Ingredient repairIngredient;

    private MnemosyneArmorMaterial() {
    }

    /**
     * 耐久公式逐字照抄原版 {@code ArmorItem.Type}：
     * 头 11×、胸 16×、腿 15×、靴 13×，再乘材质系数。
     */
    @Override
    public int getDurabilityForType(final ArmorItem.Type type) {
        return switch (type) {
            case HELMET -> 11 * DURABILITY_MULTIPLIER;
            case CHESTPLATE -> 16 * DURABILITY_MULTIPLIER;
            case LEGGINGS -> 15 * DURABILITY_MULTIPLIER;
            case BOOTS -> 13 * DURABILITY_MULTIPLIER;
        };
    }

    /** 护甲值：头 3 / 胸 8 / 腿 6 / 靴 3（{@code docs/07} §2.2，合计 20）。 */
    @Override
    public int getDefenseForType(final ArmorItem.Type type) {
        return switch (type) {
            case HELMET -> 3;
            case CHESTPLATE -> 8;
            case LEGGINGS -> 6;
            case BOOTS -> 3;
        };
    }

    @Override
    public int getEnchantmentValue() {
        return ENCHANTMENT_VALUE;
    }

    /** 懒求值 —— 见类注释。 */
    @Override
    public SoundEvent getEquipSound() {
        return ModSounds.EQUIP_ROBE.get();
    }

    /** 懒求值 —— 见类注释。用学派焦点物（忆晶）修护甲。 */
    @Override
    public Ingredient getRepairIngredient() {
        if (repairIngredient == null) {
            repairIngredient = Ingredient.of(ModItems.MEMORY_CRYSTAL.get());
        }
        return repairIngredient;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public float getToughness() {
        return TOUGHNESS;
    }

    @Override
    public float getKnockbackResistance() {
        return KNOCKBACK_RESISTANCE;
    }
}
