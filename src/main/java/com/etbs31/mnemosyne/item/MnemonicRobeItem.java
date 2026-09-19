package com.etbs31.mnemosyne.item;

import com.etbs31.mnemosyne.registry.ModAttributes;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 忆者法袍的单件（4 件套：头冠 / 法袍 / 护腿 / 长靴）。
 *
 * <p><b>文件归属</b>：WS-J 物品层。
 *
 * <p><b>数值来源</b>：{@code docs/07_装备与道具.md} §2.2 —— 每件
 * {@code memory_spell_power +5%}、{@code memory_magic_resist +2.5%}（合计 +20% / +10%）。
 *
 * <p><b>⭐⭐ 为什么必须继承 {@code ArmorItem} 而不是 {@code Item}</b>
 * <br>护甲值、装备音效、修复材料、贴图路径全部由 {@code ArmorItem} + {@code ArmorMaterial} 提供。
 * 自己实现等于把原版那套逻辑抄一遍。
 *
 * <p><b>⭐⭐ 本类最关键的一个坑：4 件的属性修饰符 UUID 必须互不相同</b>
 * <br>1.20.1 的 {@code LivingEntity} 收集装备属性时对每件护甲调用
 * {@code getDefaultAttributeModifiers(slot)}，然后按 <b>UUID 去重</b>地写进
 * {@code AttributeInstance}（同 UUID 视为"同一个修饰符"，后者覆盖前者）。
 * 所以如果 4 件都用同一个 UUID，玩家穿满全套只会拿到 <b>5%</b> 而不是 20% ——
 * 而且**编译通过、日志无输出、数值只是偏低**，是典型的静默失效。
 * <br>这里用 {@link UUID#nameUUIDFromBytes} 从
 * {@code "mnemosyne:<属性>:<部位>"} 推导 UUID：确定性（每次启动都一样、存档不会错乱）、
 * 可读、天然按部位区分。**不要改成硬编码的同一个常量。**
 *
 * <p><b>套装效果在哪</b>
 * <ul>
 *   <li>2 / 4 件 → 忆格上限 +1 / +2：{@code MnemosyneData.getEquipmentSlotBonus}</li>
 *   <li>4 件 → 共鸣加成 +5%（0.20 → 0.25）：{@code MnemosyneData.getEquipmentResonanceBonus}</li>
 *   <li>3 件 → 记忆有效期 120 → 180 秒：{@code MnemosyneData.getMemoryLifetimeSeconds}</li>
 * </ul>
 * 它们都靠本类的 {@link #countEquippedPieces(Player)} 判定件数，本类不自己实现套装逻辑 ——
 * 那样会让"套装效果"散落在物品层与数据层两处。
 */
public class MnemonicRobeItem extends ArmorItem {

    /** 2 件套的门槛。 */
    public static final int PIECES_FOR_TIER_1 = 2;

    /** 3 件套的门槛。 */
    public static final int PIECES_FOR_TIER_2 = 3;

    /** 4 件套的门槛（满套）。 */
    public static final int PIECES_FOR_TIER_3 = 4;

    private final AttributeModifier powerModifier;
    private final AttributeModifier resistModifier;

    /**
     * @param material     护甲材质（{@link MnemosyneArmorMaterial#MNEMONIC}）
     * @param type         部位
     * @param properties   物品属性
     * @param spellPower   法术强度加成，例如 {@code 0.05D} 表示 +5%
     * @param magicResist  法术抗性加成，例如 {@code 0.025D} 表示 +2.5%
     */
    public MnemonicRobeItem(final ArmorMaterial material, final ArmorItem.Type type,
                            final Properties properties, final double spellPower, final double magicResist) {
        super(material, type, properties);
        this.powerModifier = new AttributeModifier(
                slotUuid("power", type), "mnemosyne.robe.power",
                spellPower, AttributeModifier.Operation.MULTIPLY_BASE);
        this.resistModifier = new AttributeModifier(
                slotUuid("resist", type), "mnemosyne.robe.resist",
                magicResist, AttributeModifier.Operation.MULTIPLY_BASE);
    }

    /**
     * 按部位推导出**唯一**的修饰符 UUID。
     *
     * <p>用 {@code type.getName()}（{@code "helmet"} / {@code "chestplate"} /
     * {@code "leggings"} / {@code "boots"}）参与散列，保证四个部位各不相同。
     * 法术强度与抗性落在**两个不同的属性**上，各自有独立的 {@code AttributeInstance}，
     * 所以它们共用同一套 UUID 不会冲突 —— 但这里仍然带上属性名，
     * 免得将来有人把它们挪到同一属性上时踩坑。
     */
    private static UUID slotUuid(final String attribute, final ArmorItem.Type type) {
        return UUID.nameUUIDFromBytes(
                ("mnemosyne:" + attribute + ":" + type.getName()).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(final EquipmentSlot slot) {
        final Multimap<Attribute, AttributeModifier> base = super.getDefaultAttributeModifiers(slot);
        // 只有"这件护甲实际穿在的那个槽位"才给加成 —— 拿在手上不该生效。
        if (slot != this.getType().getSlot()) {
            return base;
        }
        // ⚠️ 属性实例在游戏内才取（.get()）—— 物品在 RegisterEvent 期间构造，
        //    那时 ModAttributes 虽已注册，但把 .get() 放进构造器仍然是把时序押在
        //    "两个 DeferredRegister 的注册顺序"上，不值得。
        final ImmutableMultimap.Builder<Attribute, AttributeModifier> builder = ImmutableMultimap.builder();
        builder.putAll(base);
        builder.put(ModAttributes.MEMORY_SPELL_POWER.get(), this.powerModifier);
        builder.put(ModAttributes.MEMORY_MAGIC_RESIST.get(), this.resistModifier);
        return builder.build();
    }

    /**
     * 当前穿在身上的忆者法袍件数（0-4）。
     *
     * <p>套装效果的唯一判据。用 {@code getArmorSlots()} 而不是逐槽 {@code getItemBySlot}，
     * 一次拿到头/胸/腿/靴四件。
     */
    public static int countEquippedPieces(final Player player) {
        int count = 0;
        for (final ItemStack stack : player.getArmorSlots()) {
            if (stack.getItem() instanceof MnemonicRobeItem) {
                count++;
            }
        }
        return count;
    }
}
