package com.etbs31.mnemosyne.item;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.capability.EngramEntry;
import com.etbs31.mnemosyne.capability.MnemosyneData;
import com.etbs31.mnemosyne.oblivion.AbilityMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * 拾忆匕首（Recollector）—— {@code docs/07_装备与道具.md} §3.2。
 *
 * <p><b>文件归属</b>：WS-K 装备层（2026-09-17 补）。
 *
 * <p>数值：与铁剑一致（攻击 6 / 攻速 1.6），无附加属性。
 * 构造参数 {@code 3, -2.4F} 就是原版铁剑的那两个数（基础攻击 1+3、基础攻速 4.0-2.4）。
 *
 * <p><b>拾忆（唯一特殊效果）</b>
 * <br>击杀生物时 25% 概率把该生物的一个特性写进**空忆格**，有效期 60 秒。
 * 设计意图（§3.2）是把"窃取"从主动操作变成被动收集 —— 但 60 秒有效期
 * 意味着不能囤，必须马上用掉。
 *
 * <p><b>⭐⭐ 为什么写进 {@code slots} 而不是 {@code permSlots}</b>
 * <br>{@code MnemosyneData.addEngram(player, entry, false)} 写的是会腐坏的
 * {@code slots} —— 60 秒后自己消失。写进 {@code permSlots} 会让"临时记忆"永不消失，
 * 直接破坏 §3.2 "不能囤"的设计。
 *
 * <p><b>为什么只取 {@link AbilityMap#presentAbilities} 里的特性</b>
 * <br>"该生物的一个特性"必须是**它真的拥有**的（{@code presentAbilities} 按当前
 * 活着的 Goal 判定）。取 {@code AbilityMap.getAbilities(type)} 会给到"这个物种
 * 理论上有、但这只个体没装"的能力，玩家拿到一个放出来没反应的忆格 ——
 * 又是静默失效。
 *
 * <p><b>为什么没有"通用记忆"降级</b>
 * <br>「写入 · 质忆」有降级（{@code OblivionManager.writeGenericMemory}）是为了
 * 让那张牌在任何情况下都不是废牌。匕首不同：它是**被动**的，没特性就不触发，
 * 玩家不会有任何"我损失了什么"的感觉，反而加了降级会让"拾忆"变得廉价。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID)
public class RecollectorDaggerItem extends SwordItem {

    /** §3.2：25% 触发概率。 */
    public static final float TRIGGER_CHANCE = 0.25F;

    /** §3.2：自动写入的记忆只活 60 秒（正常写入是 120 秒，正好一半）。 */
    public static final int LIFETIME_SECONDS = 60;

    private static final int LIFETIME_TICKS = LIFETIME_SECONDS * 20;

    public RecollectorDaggerItem(final Tier tier, final int attackDamageModifier,
                                 final float attackSpeedModifier, final Properties properties) {
        super(tier, attackDamageModifier, attackSpeedModifier, properties);
    }

    /** 修复材料：忆晶（与法袍一致，用 {@code Ingredient} 的惰性写法避开注册时序）。 */
    @Override
    public boolean isValidRepairItem(final ItemStack toRepair, final ItemStack repair) {
        return Ingredient.of(com.etbs31.mnemosyne.registry.ModItems.MEMORY_CRYSTAL.get()).test(repair)
                || super.isValidRepairItem(toRepair, repair);
    }

    /**
     * 拾忆：击杀 → 25% 概率写一个 60 秒的特性忆格。
     *
     * <p><b>为什么用 {@code LivingDeathEvent} 而不是 {@code LivingDropsEvent}</b>
     * <br>掉落事件可能被其他模组取消/改写，而"击杀"是判定的**语义**本身。
     * 且这里不依赖掉落物，只依赖死者与凶手。
     *
     * <p><b>三个静默失效的守卫，缺一不可</b>
     * <ol>
     *   <li>{@code isClientSide} —— 客户端也会收到死亡事件，不判会在单人世界里触发两次</li>
     *   <li>{@code hasFreeSlot} —— §3.2 明确"若忆格已满，效果不触发"</li>
     *   <li>{@code traits.isEmpty()} —— 该生物没有可窃取的特性时什么都不做</li>
     * </ol>
     */
    @SubscribeEvent
    public static void onLivingDeath(final LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof Player player)) {
            return;
        }
        if (!(player.getMainHandItem().getItem() instanceof RecollectorDaggerItem)) {
            return;
        }
        if (!MnemosyneData.hasFreeSlot(player)) {
            return;
        }
        final List<ResourceLocation> traits = AbilityMap.presentAbilities(mob);
        if (traits.isEmpty()) {
            return;
        }
        if (player.getRandom().nextFloat() >= TRIGGER_CHANCE) {
            return;
        }
        final ResourceLocation trait = traits.get(player.getRandom().nextInt(traits.size()));
        final long now = MnemosyneData.nowTick(player);
        MnemosyneData.addEngram(player,
                new EngramEntry.EssenceMemory(trait, LIFETIME_TICKS, now + LIFETIME_TICKS),
                false);
    }
}
