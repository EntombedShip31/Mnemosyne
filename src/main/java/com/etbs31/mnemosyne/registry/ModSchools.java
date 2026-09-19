package com.etbs31.mnemosyne.registry;

import com.etbs31.mnemosyne.MnemosyneMod;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
import io.redspace.ironsspellbooks.api.spells.SchoolType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 忆海学派注册（{@code mnemosyne:memory}）。
 *
 * <p><b>文件归属</b>：WS-A 注册层。这是 WS-A 最关键的一个文件 —— 下游全部依赖它。
 *
 * <p><b>实测依据</b>（{@code io.redspace.ironsspellbooks.api.registry.SchoolRegistry.java}，
 * 随 ISS 的 {@code :api} jar 发布）：
 * <pre>{@code
 * public static final ResourceKey<Registry<SchoolType>> SCHOOL_REGISTRY_KEY =
 *         ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("irons_spellbooks", "schools"));
 * private static final DeferredRegister<SchoolType> SCHOOLS =
 *         DeferredRegister.create(SCHOOL_REGISTRY_KEY, "irons_spellbooks");
 * public static final Supplier<IForgeRegistry<SchoolType>> REGISTRY =
 *         SCHOOLS.makeRegistry(() -> new RegistryBuilder<SchoolType>().hasTags().disableSaving().disableOverrides());
 * }</pre>
 *
 * <p><b>⭐ 这里有一个必须小心的点</b>：
 * ISS 已经调用了 {@code makeRegistry(...)} 创建了这个注册表，
 * 而 {@code makeRegistry} 对同一个注册表键**只能调用一次**（第二次会抛异常）。
 * 所以我们的 {@code DeferredRegister} 只 {@code register(modBus)}，**绝不调用 makeRegistry**。
 * 条目会被追加进 ISS 建好的那个注册表里。
 * 依赖 {@code mods.toml} 里的 {@code ordering="AFTER"} 保证 ISS 先建表。
 *
 * <p><b>⭐ 另一个点</b>：{@code displayName} 必须带颜色。
 * {@code SchoolType.getTargetingColor()} 内部是
 * {@code Utils.deconstructRGB(displayStyle.getColor().getValue())} ——
 * 没有 style 颜色会 NPE。主色用靛蓝 {@code #534AB7}。
 */
public final class ModSchools {

    private ModSchools() {}

    /** 学派 id：{@code mnemosyne:memory}（冻结契约）。 */
    public static final ResourceLocation MEMORY_RESOURCE =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "memory");

    /** 焦点物品标签：{@code mnemosyne:memory_focus}（冻结契约）。 */
    public static final TagKey<Item> MEMORY_FOCUS =
            TagKey.create(Registries.ITEM,
                    ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "memory_focus"));

    /** 伤害类型：{@code mnemosyne:memory}（冻结契约），对应 {@code data/mnemosyne/damage_type/memory.json}。 */
    public static final ResourceKey<DamageType> MEMORY_DAMAGE_TYPE =
            ResourceKey.create(Registries.DAMAGE_TYPE, MEMORY_RESOURCE);

    /**
     * 「真实伤害」用的伤害类型 —— <b>无视一切减免</b>。
     *
     * <p>给「千忆归一」的伤害拆分用（设计文档 v2 §一：10% 真实伤害 /
     * 25% 元素持续伤害 / 65% 普通伤害）。
     *
     * <p>怎么做到"无视一切"：靠四个**原版伤害类型标签**
     * （{@code data/minecraft/tags/damage_type/} 下的 {@code bypasses_armor} /
     * {@code bypasses_effects} / {@code bypasses_enchantments} / {@code bypasses_resistance}）
     * 把 {@code mnemosyne:memory_true} 加进去。
     * ⚠️ 数据包标签默认是**合并**（{@code "replace": false}），所以不会覆盖原版已有的条目。
     *
     * <p>⚠️ 打这个伤害时**不要**走 {@code DamageSources.applyDamage} ——
     * 那条管线会套用 ISS 的抗性计算，与"真实伤害"的语义直接冲突。
     * 用原版 {@code target.hurt(source, amount)}，让标签决定减免。
     */
    public static final ResourceKey<DamageType> MEMORY_TRUE_DAMAGE_TYPE =
            ResourceKey.create(Registries.DAMAGE_TYPE,
                    ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "memory_true"));

    /** 主色：靛蓝（见 {@code docs/09_美术与音效.md} §配色系统）。 */
    public static final int MEMORY_COLOR = 0x534AB7;

    /**
     * ISS 自定义注册表 {@code irons_spellbooks:schools} 的写入句柄。
     * <p>⚠️ 不调用 {@code makeRegistry()} —— 见类注释。
     */
    private static final DeferredRegister<SchoolType> SCHOOLS =
            DeferredRegister.create(SchoolRegistry.SCHOOL_REGISTRY_KEY, MnemosyneMod.MODID);

    /** 忆海学派。id = {@code mnemosyne:memory}。 */
    public static final RegistryObject<SchoolType> MEMORY = SCHOOLS.register("memory", () -> new SchoolType(
            MEMORY_RESOURCE,
            MEMORY_FOCUS,
            Component.translatable("school.mnemosyne.memory").withStyle(Style.EMPTY.withColor(MEMORY_COLOR)),
            ModAttributes.MEMORY_SPELL_POWER,
            ModAttributes.MEMORY_MAGIC_RESIST,
            ModSounds.CAST_DEFAULT,
            MEMORY_DAMAGE_TYPE,
            // requiresLearning = true：必须先读忆碑解锁才能抄写（对齐邪术 Eldritch 的进度门槛）
            true,
            // allowLooting = true：忆者遗迹的箱子里要能开出记忆卷轴
            true
    ));

    /**
     * 由主类在构造函数里调用：把 DeferredRegister 挂到模组总线。
     *
     * <p><b>不需要 {@code event.enqueueWork(...)}</b>：{@code DeferredRegister} 走的是 Forge 的
     * {@code RegisterEvent}，本身就发生在主线程。文档里要求的 {@code enqueueWork}
     * 针对的是"在 {@code FMLCommonSetupEvent} 回调里直接往注册表写数据"的写法，我们不这么做。
     */
    public static void register(final IEventBus modBus) {
        SCHOOLS.register(modBus);
    }
}
