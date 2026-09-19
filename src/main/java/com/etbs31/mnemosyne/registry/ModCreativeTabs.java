package com.etbs31.mnemosyne.registry;

import com.etbs31.mnemosyne.MnemosyneMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 忆海的创造模式标签页。
 *
 * <p><b>文件归属</b>：WS-J（补齐集成欠账）。
 *
 * <p><b>⭐ 为什么必须自建标签页</b>
 * <br>项目记忆里的硬事实：**全工程原本没有任何 {@code BuildCreativeModeTabContentsEvent}，
 * ISS 的 {@code CreativeTabRegistry} 也不扫描第三方物品** ——
 * 结果是忆晶、5 本法书、4 个方块全都**进不了创造模式物品栏**，
 * 只能靠 {@code /give} 拿。{@code docs/tech/10} §二 Phase 1 的验收项
 * "物品栏能看到忆晶"当时实际不成立。
 *
 * <p>挂到 ISS 的标签页上不可行（那需要往别人的事件里塞东西，且 ISS 的
 * 标签页注册时机早于我们）。自建一个标签页是最干净、最不依赖 ISS 内部实现的做法。
 *
 * <p>⚠️ {@code displayItems} 里的 {@code ModItems.X.get()} / {@code ModBlocks.X.get()}
 * 都是**运行时**才求值的（标签页在注册表冻结后才填充），所以不存在
 * "静态初始化顺序"问题。
 */
public final class ModCreativeTabs {

    private ModCreativeTabs() {}

    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MnemosyneMod.MODID);

    /** 忆海主标签页。图标用忆晶（流派焦点物）。 */
    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.mnemosyne.main"))
                    .icon(() -> new ItemStack(ModItems.MEMORY_CRYSTAL.get()))
                    .displayItems((parameters, output) -> {
                        // ---- 焦点与材料 ----
                        output.accept(ModItems.MEMORY_CRYSTAL.get());
                        output.accept(ModItems.MEMORY_RUNE.get());

                        // ---- WS-K：武器与材料（docs/07 §三 / §四） ----
                        // 少了这四行，玩家在创造模式里就拿不到权杖和匕首 ——
                        // 而它们不是"有配方就能拿到"的：忆晶只在遗迹里产。
                        output.accept(ModItems.MEMORY_STAFF.get());
                        output.accept(ModItems.RECOLLECTOR.get());
                        output.accept(ModItems.MNEMONIC_INK.get());
                        output.accept(ModItems.FADED_PAGE.get());

                        // ---- 忆者法袍 4 件套（WS-J） ----
                        output.accept(ModItems.MNEMONIC_ROBE_HELMET.get());
                        output.accept(ModItems.MNEMONIC_ROBE_CHESTPLATE.get());
                        output.accept(ModItems.MNEMONIC_ROBE_LEGGINGS.get());
                        output.accept(ModItems.MNEMONIC_ROBE_BOOTS.get());


                        // ---- 方块（WS-J） ----
                        output.accept(ModBlocks.MNEMONIC_BRICKS.get());
                        output.accept(ModBlocks.MNEMONIC_BRICKS_CRACKED.get());
                        output.accept(ModBlocks.MEMORY_STELE.get());
                        output.accept(ModBlocks.MEMORY_CRYSTAL_CLUSTER.get());
                    })
                    .build());

    /** 由主类在构造函数里调用。 */
    public static void register(final IEventBus modBus) {
        TABS.register(modBus);
    }
}
