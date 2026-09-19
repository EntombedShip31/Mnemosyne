package com.etbs31.mnemosyne.registry;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.item.MnemonicRobeItem;
import com.etbs31.mnemosyne.item.MnemosyneArmorMaterial;
import com.etbs31.mnemosyne.item.MnemosyneStaffItem;
import com.etbs31.mnemosyne.item.RecollectorDaggerItem;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.Tiers;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 忆海物品注册。
 *
 * <p><b>文件归属</b>：WS-A 注册层。
 *
 * <p>忆晶由 WS-A 注册；<b>5 个等级的忆海法书由 WS-F 追加</b>（2026-09-16）。
 * 法袍 4 件套 / 权杖 / 匕首（WS-H2 贴图就绪后）仍待追加 —— 追加时
 * **必须由 WS-A 或走接口变更申请** —— 本文件不允许多个工作流同时改。
 *
 * <p>忆晶是学派**焦点物**（Focus）：它被 {@code mnemosyne:memory_focus} 标签引用，
 * 标签定义在 {@code data/mnemosyne/tags/items/memory_focus.json}。
 * {@code SchoolRegistry.getSchoolFromFocus(stack)} 靠这个标签反查学派。
 *
 * <p><b>⚠️ 为什么这里没有 {@code memory_scroll}（WS-F 的实测结论）</b>
 * <br>忆海卷轴<b>复用官方 {@code irons_spellbooks:scroll}</b>，不注册新物品。
 * 三条字节码证据：
 * <ol>
 *   <li>{@code render.ScrollModel.getScrollModelLocation(SchoolType)} 会按学派拼出
 *       {@code ResourceLocation.fromNamespaceAndPath(ns, "item/scroll_" + path)}，
 *       忆海 → {@code mnemosyne:item/scroll_memory}；而
 *       {@code setup.ClientSetup.registerSpecialModels} 遍历 {@code SchoolRegistry} 的
 *       <b>全部</b>学派自动注册该模型 —— 我们的学派会被自动覆盖。</li>
 *   <li>{@code setup.ClientSetup.replaceItemModels} 只把
 *       {@code irons_spellbooks:scroll} 这一个 ModelResourceLocation 换成 {@code ScrollModel}
 *       → 自注册的卷轴物品<b>拿不到</b>按学派换贴图的能力。</li>
 *   <li>{@code item.Scroll.attemptRemoveScrollAfterCast} 用
 *       {@code instanceof io.redspace.ironsspellbooks.item.Scroll}（<b>具体类，不是 IScroll</b>）
 *       → 自注册的卷轴<b>不会</b>被官方逻辑销毁；
 *       且 {@code gui.inscription_table.InscriptionTableMenu} 同样按具体类判断输入槽
 *       → 自注册的卷轴<b>在官方铭刻台里不被接受</b>。</li>
 * </ol>
 * 结论：卷轴走官方物品 + 补一个模型 JSON（{@code assets/mnemosyne/models/item/scroll_memory.json}，
 * 已在本工作流补上）就是最优解；自注册是纯负收益。
 */
public final class ModItems {

    private ModItems() {}

    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MnemosyneMod.MODID);

    /** 忆晶（Mnemosyne Crystal）—— 学派焦点物。id：{@code mnemosyne:memory_crystal}。 */
    public static final RegistryObject<Item> MEMORY_CRYSTAL = ITEMS.register("memory_crystal",
            () -> new Item(new Item.Properties().rarity(Rarity.UNCOMMON)));


    // ==================================================================
    // WS-J 追加（2026-09-17）：忆符文 / 忆者法袍四件套
    //
    // ⚠️ 这些物品原先**只有贴图没有注册** —— 即 6 张孤儿贴图，
    //    tools/check_resources.py 会以 warn 报出来（"没有任何模型引用 → 游戏里看不到"）。
    // ==================================================================

    /** 忆符文（Memory Rune）—— 遗迹材料，用于合成忆碑相关配方。 */
    public static final RegistryObject<Item> MEMORY_RUNE = ITEMS.register("memory_rune",
            () -> new Item(new Item.Properties().rarity(Rarity.UNCOMMON)));


    // ---- 忆者法袍 4 件套（docs/07 §二） ----
    // 单件护甲值写在 MnemosyneArmorMaterial，单件属性加成写在 MnemonicRobeItem 的构造参数里。
    // 套装效果（忆格 +1/+2、共鸣 +5%、记忆有效期 180 秒）由 MnemosyneData 按件数判定。
    //
    // ⚠️ 每件的属性修饰符 UUID 必须不同 —— 详见 MnemonicRobeItem 的类注释（同 UUID 会被去重，
    //    4 件套只加 5% 而不是 20%，且完全静默）。

    /** 忆者头冠：护甲 3，强度 +5%，抗性 +2.5%。 */
    public static final RegistryObject<Item> MNEMONIC_ROBE_HELMET = ITEMS.register("mnemonic_robe_helmet",
            () -> new MnemonicRobeItem(MnemosyneArmorMaterial.MNEMONIC, ArmorItem.Type.HELMET,
                    new Item.Properties().rarity(Rarity.RARE), 0.05D, 0.025D));

    /** 忆者法袍（胸甲）：护甲 8，强度 +5%，抗性 +2.5%。 */
    public static final RegistryObject<Item> MNEMONIC_ROBE_CHESTPLATE = ITEMS.register("mnemonic_robe_chestplate",
            () -> new MnemonicRobeItem(MnemosyneArmorMaterial.MNEMONIC, ArmorItem.Type.CHESTPLATE,
                    new Item.Properties().rarity(Rarity.RARE), 0.05D, 0.025D));

    /** 忆者护腿：护甲 6，强度 +5%，抗性 +2.5%。 */
    public static final RegistryObject<Item> MNEMONIC_ROBE_LEGGINGS = ITEMS.register("mnemonic_robe_leggings",
            () -> new MnemonicRobeItem(MnemosyneArmorMaterial.MNEMONIC, ArmorItem.Type.LEGGINGS,
                    new Item.Properties().rarity(Rarity.RARE), 0.05D, 0.025D));

    /** 忆者长靴：护甲 3，强度 +5%，抗性 +2.5%。 */
    public static final RegistryObject<Item> MNEMONIC_ROBE_BOOTS = ITEMS.register("mnemonic_robe_boots",
            () -> new MnemonicRobeItem(MnemosyneArmorMaterial.MNEMONIC, ArmorItem.Type.BOOTS,
                    new Item.Properties().rarity(Rarity.RARE), 0.05D, 0.025D));

    // ==================================================================
    // WS-K 追加（2026-09-17）：docs/07 §三/§四 剩下的武器与材料
    //
    // 权杖 / 匕首的行为写在 item/MnemosyneStaffItem 与 item/RecollectorDaggerItem。
    // 忆之墨水与褪色的书页是纯材料（无行为），直接用 Item —— 不要为它们建空壳类，
    // 空壳类只会让人以为"这里有逻辑"。
    // ==================================================================

    /** 忆晶权杖：记忆法术强度 +15%，施法命中叠认知过载（见 MnemosyneStaffItem）。 */
    public static final RegistryObject<Item> MEMORY_STAFF = ITEMS.register("memory_staff",
            () -> new MnemosyneStaffItem(new Item.Properties()
                    .stacksTo(1).rarity(Rarity.RARE)));

    /** 拾忆匕首：击杀 25% 概率自动拾取一个特性（60 秒），数值等同铁剑。 */
    public static final RegistryObject<Item> RECOLLECTOR = ITEMS.register("recollector",
            () -> new RecollectorDaggerItem(Tiers.IRON, 3, -2.4F,
                    new Item.Properties().rarity(Rarity.RARE)));

    /** 忆之墨水：抄写法术书页 / 合成法袍的材料。 */
    public static final RegistryObject<Item> MNEMONIC_INK = ITEMS.register("mnemonic_ink",
            () -> new Item(new Item.Properties().rarity(Rarity.UNCOMMON)));

    /** 褪色的书页：遗迹战利品，可合成忆之墨水。 */
    public static final RegistryObject<Item> FADED_PAGE = ITEMS.register("faded_page",
            () -> new Item(new Item.Properties().rarity(Rarity.COMMON)));

    /** 由主类在构造函数里调用。 */
    public static void register(final IEventBus modBus) {
        ITEMS.register(modBus);
    }
}