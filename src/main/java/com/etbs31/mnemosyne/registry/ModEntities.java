package com.etbs31.mnemosyne.registry;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.entity.MemoryArrowEntity;
import com.etbs31.mnemosyne.entity.MemoryWraithEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 忆海实体注册。
 *
 * <p><b>文件归属</b>：WS-A 注册层（WS-J 实体层追加）。
 *
 * <p><b>⚠️⚠️ 注册实体有**两件**必须做的事，漏掉第二件的失败是静默的</b>
 * <ol>
 *   <li>{@link #ENTITY_TYPES} 注册 {@code EntityType}（走 {@code DeferredRegister}）</li>
 *   <li>⭐ 在 {@link EntityAttributeCreationEvent} 里 {@code put} 属性表</li>
 * </ol>
 * 只做第 1 步的话：编译通过、注册成功、{@code /summon} 也能生成 ——
 * 但实体**没有任何属性**，表现是"生成即死亡"或血量/伤害全为 0，
 * 而日志里只有一条容易被淹没的异常。这是 1.20.1 Forge 注册实体的经典坑。
 *
 * <p><b>刷怪蛋</b>：{@code ForgeSpawnEggItem} 需要 {@code Supplier<EntityType<? extends Mob>>}。
 * 用 {@code RegistryObject} 直接当 Supplier 是**惰性**的，不会踩到注册时序 ——
 * 写成 {@code MEMORY_WRAITH.get()} 会在类加载时就解引用，注册未完成时为 {@code null}。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModEntities {

    private ModEntities() {}

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MnemosyneMod.MODID);

    /** 刷怪蛋单独挂一个物品注册表 —— 与 {@code ModItems} 的互不干扰。 */
    private static final DeferredRegister<Item> SPAWN_EGG_ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MnemosyneMod.MODID);

    // ==================================================================
    // 忆魇 memory_wraith
    // ==================================================================

    /**
     * 忆魇（Memory Wraith）—— 忆者遗迹的专属怪物。
     *
     * <p>⚠️ <b>它目前不会被自然刷出来</b>：{@code worldgen/structure/memory_ruin.json}
     * 的 {@code spawn_overrides} 是空的。原因见 {@code MemoryWraithEntity} 的类注释 ——
     * {@code docs/08} 与 {@code docs/tech/06} 对"遗迹要不要有新怪"的结论**互相矛盾**，
     * 冲突未裁决前服从设计文档 {@code docs/08} 的"纯探索点"红线。
     * 实体本身是完整可用的：刷怪蛋 / {@code /summon} 都能用。
     */
    public static final RegistryObject<EntityType<MemoryWraithEntity>> MEMORY_WRAITH =
            ENTITY_TYPES.register("memory_wraith", () -> EntityType.Builder
                    .of(MemoryWraithEntity::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(8)
                    .build("memory_wraith"));

    /** 忆魇刷怪蛋。配色用学派主色（靛蓝底 + 品红斑点）。 */
    public static final RegistryObject<Item> MEMORY_WRAITH_SPAWN_EGG =
            SPAWN_EGG_ITEMS.register("memory_wraith_spawn_egg", () -> new ForgeSpawnEggItem(
                    MEMORY_WRAITH, 0x534AB7, 0xD4537E, new Item.Properties()));

    // ==================================================================
    // 忆矢 memory_arrow（法术投射物）
    // ==================================================================

    /**
     * 忆矢投射物。
     *
     * <p>⚠️ <b>这条注册是"忆矢法术完全无效"的修复的一部分</b>。
     * 在此之前投射物基类的 {@code onCast} 是 {@code TODO}，什么都不生成。
     *
     * <p>属性说明：
     * <ul>
     *   <li>{@code MobCategory.MISC} —— 投射物不是怪物，用 MONSTER 会让它计入刷怪上限；</li>
     *   <li>{@code sized(0.4F, 0.4F)} —— 判定箱略大于视觉体积，手感更好；</li>
     *   <li>{@code clientTrackingRange(6)} —— 投射物生命周期短，不需要大范围同步；</li>
     *   <li>{@code updateInterval(1)} —— <b>必须显式设 1</b>。默认值是 3，
     *       高速投射物每 3 tick 才同步一次位置，客户端看起来会"一跳一跳"；</li>
     *   <li>{@code noSummon()} —— 不给 {@code /summon} 用，它需要 owner 才有意义。</li>
     * </ul>
     *
     * <p>⭐ 投射物**不需要** {@code EntityAttributeCreationEvent} 的 {@code put} ——
     * 那个事件只给 {@code LivingEntity} 用。这是 {@code ModEntities} 类注释里
     * "漏第二件事"的例外情况，不要误以为这里漏了。
     */
    public static final RegistryObject<EntityType<MemoryArrowEntity>> MEMORY_ARROW =
            ENTITY_TYPES.register("memory_arrow", () -> EntityType.Builder
                    .<MemoryArrowEntity>of(MemoryArrowEntity::new, MobCategory.MISC)
                    .sized(0.4F, 0.4F)
                    .clientTrackingRange(6)
                    .updateInterval(1)
                    .noSummon()
                    .build("memory_arrow"));

    // ==================================================================
    // 装配
    // ==================================================================

    public static void register(final IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
        SPAWN_EGG_ITEMS.register(modBus);
    }

    /**
     * 属性表 —— 漏掉这个方法的后果是"实体生成出来没有属性"（见类注释）。
     */
    @SubscribeEvent
    public static void onEntityAttributeCreation(final EntityAttributeCreationEvent event) {
        event.put(MEMORY_WRAITH.get(), MemoryWraithEntity.createAttributes().build());
    }
}
