package com.etbs31.mnemosyne;

import com.etbs31.mnemosyne.registry.ModAttributes;
import com.etbs31.mnemosyne.registry.ModBlocks;
import com.etbs31.mnemosyne.registry.ModCreativeTabs;
import com.etbs31.mnemosyne.registry.ModEffects;
import com.etbs31.mnemosyne.registry.ModEntities;
import com.etbs31.mnemosyne.registry.ModItems;
import com.etbs31.mnemosyne.registry.ModSchools;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.registry.ModSpells;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 忆海 · Mnemosyne —— Iron's Spells 'n Spellbooks 第三方学派附属模组（记忆 · 认知流派）。
 *
 * <p>目标环境：Minecraft 1.20.1 / Forge 47.4.0 / Java 17 / official 映射。
 *
 * <p><b>文件归属</b>：本文件属于工作流 <b>WS-0（基础设施）</b>，其他工作流只读。
 * 参见 {@code docs/tech/10_多线程开发规范.md} §六。
 *
 * <p><b>注册时序铁律</b>（{@code docs/tech/07_1.20.1Forge模组开发指南.md} 坑 #6）：
 * <ul>
 *   <li>Forge 标准注册表（属性 / 物品 / 声音 / 方块 / 实体 / 创造标签）→
 *       构造函数里 {@code DeferredRegister.register(modBus)}</li>
 *   <li>ISS 自定义注册表（{@code SchoolRegistry} / {@code SpellRegistry}）→
 *       <b>同样走 {@code DeferredRegister.register(modBus)}</b>。
 *       ⚠️ 本类注释原先写的是"必须在 {@link FMLCommonSetupEvent} 里
 *       {@code enqueueWork}"，那是**错的**，2026-09-17 集成时更正：
 *       {@code DeferredRegister} 走 Forge 的 {@code RegisterEvent}，本身就发生在主线程，
 *       不需要 {@code enqueueWork}（见 {@code ModSchools} 的类注释与实测结论）。
 *       {@code enqueueWork} 只针对"在 {@code FMLCommonSetupEvent} 回调里**直接**往注册表写数据"
 *       那种写法。</li>
 * </ul>
 */
@Mod(MnemosyneMod.MODID)
public class MnemosyneMod {

    /** mod_id，必须与 gradle.properties / mods.toml / 所有资源命名空间一致。 */
    public static final String MODID = "mnemosyne";

    /** 全局日志器，禁止使用 {@code System.out}。 */
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public MnemosyneMod() {
        final IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        final IEventBus forgeBus = MinecraftForge.EVENT_BUS;

        // ------------------------------------------------------------------
        // ① 配置文件（SERVER / CLIENT 两个 SPEC，见 Config.java）
        // ------------------------------------------------------------------
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);

        // ------------------------------------------------------------------
        // ② Forge 标准注册表（WS-A 注册层）
        //
        // ⚠️ 集成纪律（docs/tech/12 §1.4 铁律①）：本文件归 WS-0，各工作流只读。
        //    它们新建的注册类由**集成者**在阶段末尾统一挂到这一段里。
        // ------------------------------------------------------------------
        ModAttributes.register(modBus);
        ModItems.register(modBus);
        ModSounds.register(modBus);
        // WS-E 新建（registry/ModEffects.java）。忘掉这一行的后果是**静默**的：
        // 编译照过、游戏照起，只是四个遗忘效果不存在，遗忘系统退化成
        // "只移除行为、不挂状态效果"（ModEffects.forget() 做了防御性取用，不会 NPE）。
        ModEffects.register(modBus);
        // WS-J 新建（registry/ModBlocks.java）：忆砖 / 裂纹忆砖 / 忆碑 / 忆晶簇 + 忆碑方块实体。
        // 这一行同时挂了三个 DeferredRegister（BLOCKS / BLOCK_ITEMS / BLOCK_ENTITY_TYPES）。
        // ⚠️ 它是 WS-G2（23 个结构模板 NBT）的前置：NBT 引用了未注册的方块 id 时，
        //    worldgen 注册表会解析失败 → **存档直接加载不了**（不是静默降级）。
        ModBlocks.register(modBus);
        // WS-J 新建（registry/ModCreativeTabs.java）：忆海创造模式标签页。
        // 补的是项目长期欠账 —— 全工程原先没有任何创造标签注册，
        // 忆晶/法书/方块只能靠 /give 拿到。
        ModCreativeTabs.register(modBus);
        // WS-J 实体层新建（registry/ModEntities.java）：忆魇 memory_wraith + 刷怪蛋。
        // ⚠️ 这个类的 register() 只挂 EntityType 与刷怪蛋；
        //    属性表走它自己的 EntityAttributeCreationEvent（漏了的话实体生成即死）。
        ModEntities.register(modBus);

        // ISS 自定义注册表（irons_spellbooks:schools / :spells）：
        // 只挂 DeferredRegister，**绝不调用 makeRegistry** —— ISS 已经建过这两张表了。
        ModSchools.register(modBus);
        ModSpells.register(modBus);

        // ------------------------------------------------------------------
        // ③ ISS 自定义注册表 —— 只能在 FMLCommonSetupEvent + enqueueWork 里注册
        // ------------------------------------------------------------------
        modBus.addListener(MnemosyneMod::onCommonSetup);

        // ------------------------------------------------------------------
        // ④ Forge 事件总线
        //    注意：带 @Mod.EventBusSubscriber 的类会自动注册，此处切勿重复 register，
        //    否则同一个事件会被处理两次（见 07 号文档 §5.1）。
        //    只有需要手动注册的类才写在这里。
        // ------------------------------------------------------------------
        // forgeBus.register(...);  // 目前无手动注册项

        LOGGER.info("忆海 Mnemosyne 正在初始化（mod_id={}，版本由 mods.toml 提供）", MODID);
    }

    /**
     * 模组通用初始化。{@code FMLCommonSetupEvent} 是<b>并行</b>触发的，
     * 因此往 ISS 自定义注册表写入数据必须走 {@code enqueueWork} 推迟到主线程。
     */
    private static void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // ⚠️ 2026-09-17：原来这里有一行 NetworkHandler.register()。
            //    忆格 HUD（自定义 GUI）删除后，忆海已经没有需要网络包的功能了，
            //    所以 NetworkHandler / S2CSyncEngramsPacket 两个类一起删除，
            //    这个 enqueueWork 里暂时没有要推迟到主线程做的事。
            //    （保留 enqueueWork 结构而不是删掉整个回调：以后加包/加 ISS 注册表写入时还要用。）

            LOGGER.info("忆海 Mnemosyne 通用初始化完成（enqueueWork 主线程阶段）");
        });
    }
}
