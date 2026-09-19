package com.etbs31.mnemosyne.item;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.registry.ModAttributes;
import com.etbs31.mnemosyne.spell.low.MemoryArrowSpell;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import io.redspace.ironsspellbooks.api.events.SpellDamageEvent;
import io.redspace.ironsspellbooks.item.CastingItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 忆晶权杖（Mnemosyne Staff）—— {@code docs/07_装备与道具.md} §3.1。
 *
 * <p><b>文件归属</b>：WS-K 装备层（2026-09-17 补）。
 *
 * <p><b>⭐⭐ 为什么继承 {@code io.redspace.ironsspellbooks.item.CastingItem}
 * 而不是自己写 {@code Item}</b>
 * <br>权杖的**全部价值**是"能施法"：{@code CastingItem} 提供右键读条、
 * {@code UseAnim}、法术 tooltip 与 {@code ISpellContainer} 能力。
 * 用普通 {@code Item} 做出来的"法杖"拿在手里什么都不会发生 ——
 * 而且**编译通过、日志无输出**，是本项目最忌讳的静默失效。
 *
 * <p><b>为什么不是 {@code item.weapons.StaffItem}</b>
 * <br>{@code StaffItem} 会 {@code hasCustomRendering() == true} 并在
 * {@code initializeClient} 里挂 ISS 自己的 3D 渲染器（依赖 GeckoLib 动画资源）。
 * 我们没有任何权杖动画资源 → 走那条路要么渲染成空、要么在客户端报错。
 * 所以用更底层的 {@code CastingItem}：**功能齐全、渲染退回普通 2D 物品图标**，
 * 是"确定能跑"和"可能更好看"之间的正确取舍。
 *
 * <p><b>攻击力 / 攻速怎么来的</b>
 * <br>{@code CastingItem} 继承的是 {@code Item} 而不是 {@code SwordItem}，
 * 没有"武器攻击力"这套字段。这里按原版剑的**同一套做法**补两个属性修饰符：
 * 基础攻击 1 + 3 = 4 点、基础攻速 4.0 - 3.0 = 1.0（{@code docs/07} §3.1 的数值）。
 *
 * <p><b>记忆标记（§3.1 特殊效果）</b>
 * <br>施法命中生物时叠 1 层认知过载，每 3 秒最多一次 —— 见 {@link #onSpellDamage}。
 *
 * <p><b>⚠️ 「指定释放」为什么没实现</b>
 * <br>§3.1 说"装备权杖时解锁指定释放忆格"。但"指定哪一个忆格"**需要一个选择 UI**，
 * 而 choice UI 整批被 WS-I 挂起（忆格选择界面等；原示例 {@code ReciteSpell} 已于 2026-09-18 删除、
 * {@code MemoryTheftSpell} 选特性都是同一批欠账）。
 * 在 UI 存在之前硬做"指定释放"只能做成"随机指定"，比不做更糟。
 * 所以这里**不提供**任何 {@code isHeld} 判定 —— 免得将来有人把它当成"已实现"接上。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID)
public class MnemosyneStaffItem extends CastingItem {

    /** 记忆标记的内置冷却（tick）—— §3.1「每 3 秒最多触发 1 次」。 */
    public static final int MARK_COOLDOWN_TICKS = 60;

    /** 记忆标记叠层的层数上限。 */
    private static final int MARK_CAP_LAYERS = 3;

    /** 记忆标记冷却的持久化键（挂在**目标**身上，按目标独立冷却）。 */
    private static final String KEY_MARK_TICK = "mnemosyne_staff_mark_tick";

    private static final UUID POWER_UUID =
            UUID.nameUUIDFromBytes("mnemosyne:staff:power".getBytes(StandardCharsets.UTF_8));
    private static final UUID DAMAGE_UUID =
            UUID.nameUUIDFromBytes("mnemosyne:staff:damage".getBytes(StandardCharsets.UTF_8));
    private static final UUID SPEED_UUID =
            UUID.nameUUIDFromBytes("mnemosyne:staff:speed".getBytes(StandardCharsets.UTF_8));

    public MnemosyneStaffItem(final Properties properties) {
        super(properties);
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(final EquipmentSlot slot) {
        final Multimap<Attribute, AttributeModifier> base = super.getDefaultAttributeModifiers(slot);
        // 拿在手上才生效（副手/背包里不该加成）
        if (slot != EquipmentSlot.MAINHAND) {
            return base;
        }
        final ImmutableMultimap.Builder<Attribute, AttributeModifier> builder = ImmutableMultimap.builder();
        builder.putAll(base);
        // ⭐ .get() 在这里才调用 —— 物品在 RegisterEvent 期间构造，那时属性还没注册完。
        builder.put(ModAttributes.MEMORY_SPELL_POWER.get(),
                new AttributeModifier(POWER_UUID, "mnemosyne.staff.power",
                        0.15D, AttributeModifier.Operation.MULTIPLY_BASE));
        builder.put(Attributes.ATTACK_DAMAGE,
                new AttributeModifier(DAMAGE_UUID, "mnemosyne.staff.damage",
                        3.0D, AttributeModifier.Operation.ADDITION));
        builder.put(Attributes.ATTACK_SPEED,
                new AttributeModifier(SPEED_UUID, "mnemosyne.staff.speed",
                        -3.0D, AttributeModifier.Operation.ADDITION));
        return builder.build();
    }

    /**
     * 记忆标记：施法命中生物 → 叠 1 层认知过载。
     *
     * <p><b>为什么挂在 {@code SpellDamageEvent} 上而不是自己判伤害</b>
     * <br>{@code SpellDamageEvent} 由 {@code DamageSources.applyDamage} 统一抛出，
     * 覆盖了**所有**记忆法术的伤害路径（投射物、AOE、领域结算）。
     * 自己逐条法术里加标记，任何新法术都会漏。
     *
     * <p><b>为什么冷却写在目标的 persistentData 上</b>
     * <br>用静态 Map 会在服务端重启后丢失、且要手动清理（内存泄漏）；
     * 写在施法者身上则"换目标"会被上一次的冷却挡住。
     * 目标的 persistentData 是**实体级的、会随实体一起消失**的，正合适。
     */
    @SubscribeEvent
    public static void onSpellDamage(final SpellDamageEvent event) {
        final LivingEntity target = event.getEntity();
        if (target == null || target.level().isClientSide) {
            return;
        }
        // 只标记"自己打的" —— 别人拿权杖时不该给自己叠层
        if (!(event.getSpellDamageSource().getEntity() instanceof Player player)) {
            return;
        }
        final ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof MnemosyneStaffItem)) {
            return;
        }
        final long now = target.level().getGameTime();
        final CompoundTag data = target.getPersistentData();
        if (now - data.getLong(KEY_MARK_TICK) < MARK_COOLDOWN_TICKS) {
            return;
        }
        data.putLong(KEY_MARK_TICK, now);
        MemoryArrowSpell.stackCognitiveOverload(target, MARK_CAP_LAYERS);
    }
}
