package com.etbs31.mnemosyne.registry;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.spell.low.EngraveSpell;
import com.etbs31.mnemosyne.spell.low.MemoryArrowSpell;
import com.etbs31.mnemosyne.spell.low.GlimpseSpell;
import com.etbs31.mnemosyne.spell.low.MemoryShardSpell;
import com.etbs31.mnemosyne.spell.low.EncodeTraitSpell;
import com.etbs31.mnemosyne.spell.low.EncodePainSpell;
import com.etbs31.mnemosyne.spell.low.RetrogradeSpell;
import com.etbs31.mnemosyne.spell.low.OblationSpell;
import com.etbs31.mnemosyne.spell.mid.AmnesiaSpell;
import com.etbs31.mnemosyne.spell.mid.ExpandMindSpell;
import com.etbs31.mnemosyne.spell.mid.RecollectionSpell;
import com.etbs31.mnemosyne.spell.mid.CurseOfOblivionSpell;
import com.etbs31.mnemosyne.spell.mid.AdaptationSpell;
import com.etbs31.mnemosyne.spell.mid.FramebindSpell;
import com.etbs31.mnemosyne.spell.high.CognitiveCollapseSpell;
import com.etbs31.mnemosyne.spell.high.MemoryTheftSpell;
import com.etbs31.mnemosyne.spell.high.MassAmnesiaSpell;
import com.etbs31.mnemosyne.spell.high.DejaVuSpell;
import com.etbs31.mnemosyne.spell.high.EndlessRealmSpell;
import com.etbs31.mnemosyne.spell.high.SeaOfMemorySpell;
import com.etbs31.mnemosyne.spell.high.ThousandMemoriesSpell;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 忆海的法术注册行（当前 16 个：低阶 6 + 中阶 4 + 高阶 6）。
 *
 * <p><b>归属</b>：WS-A 注册层。
 *
 * <p><b>⭐ 已删除的 2 个法术（别再照着旧文档加回来）</b>：
 * 术忆（WS-A 之后删除，由「铭忆 engrave」替代）与<b>遗忘 {@code forget}</b>
 * （2026-09-18 删除 —— 与「失忆」效果太像，玩家分不清；删后失忆提到中阶 Rare 独占该位）。
 * 两者的 stub 都留在 {@code build/_trash_} 下的备份目录里，不参与编译。
 * ⚠️ 写这类路径时**别把星号与斜杠连写** —— 它会提前闭合 javadoc，
 *    之后整段注释变成代码，报的错是"需要 class/interface/enum"。
 *
 * <p><b>为什么这个文件在 Phase 1 就把全部注册行写完</b>（docs/tech/10 第六节）：
 * 它是并行开发里唯一的“共享热点”——每个法术工作流都想往这里加一行。
 * 所以 WS-A 一次性写完全部注册行 + 建好对应的 stub 类（当时 18 个），
 * 之后 WS-D1/D2/D3 只需要<b>填自己那个 stub 的方法体</b>，完全不碰本文件。
 * 这是“接口先冻结，实现后并行”最典型的应用。
 *
 * <p><b>实测依据</b>（io.redspace.ironsspellbooks.api.registry.SpellRegistry，源码随 ISS 的 :api jar 发布）：
 * <pre>
 * public static final ResourceKey&lt;Registry&lt;AbstractSpell&gt;&gt; SPELL_REGISTRY_KEY =
 *         ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("irons_spellbooks", "spells"));
 * private static final DeferredRegister&lt;AbstractSpell&gt; SPELLS =
 *         DeferredRegister.create(SPELL_REGISTRY_KEY, "irons_spellbooks");
 * public static final Supplier&lt;IForgeRegistry&lt;AbstractSpell&gt;&gt; REGISTRY =
 *         SPELLS.makeRegistry(() -&gt; new RegistryBuilder&lt;AbstractSpell&gt;().hasTags().disableSaving().disableOverrides());
 * private static RegistryObject&lt;AbstractSpell&gt; registerSpell(AbstractSpell spell) {
 *     return SPELLS.register(spell.getSpellName(), () -&gt; spell);
 * }
 * </pre>
 *
 * <p>注意：同 ModSchools —— ISS 已经调过 makeRegistry()，我们<b>只 register(modBus)</b>。
 *
 * <p>注意：注册名取自 spell.getSpellName()，而它是 getSpellResource().getPath()
 * ——所以<b>法术 id 由 stub 类里的 spellId 决定</b>，本文件不重复写 id。
 * id 必须与 assets/mnemosyne/sounds.json 里的 spell.&lt;id&gt;.cast 一致。
 */
public final class ModSpells {

    private ModSpells() {}

    private static final DeferredRegister<AbstractSpell> SPELLS =
            DeferredRegister.create(SpellRegistry.SPELL_REGISTRY_KEY, MnemosyneMod.MODID);

    // ---- 低阶（WS-D1，7 个 · 遗忘已于 2026-09-18 删除） ----
    /** 忆矢 Memory Arrow（memory_arrow）。归属 WS-D1。 */
    public static final RegistryObject<AbstractSpell> MEMORYARROW = register(new MemoryArrowSpell());
    /** 窥忆 Glimpse（glimpse）。归属 WS-D1。 */
    public static final RegistryObject<AbstractSpell> GLIMPSE = register(new GlimpseSpell());
    /** 残迹回溯 Retrograde（retrograde）。读取区域 × 时间维度的残秽。 */
    public static final RegistryObject<AbstractSpell> RETROGRADE = register(new RetrogradeSpell());
    /** 献忆 Oblation（oblation）。烧 1 个忆格换免法窗口。 */
    public static final RegistryObject<AbstractSpell> OBLATION = register(new OblationSpell());
    /** 碎忆 Memory Shard（memory_shard）。归属 WS-D1。 */
    public static final RegistryObject<AbstractSpell> MEMORYSHARD = register(new MemoryShardSpell());
    /** 质忆 Encode: Trait（encode_trait）。归属 WS-D1。 */
    public static final RegistryObject<AbstractSpell> ENCODETRAIT = register(new EncodeTraitSpell());
    /** 痛忆 Encode: Pain（encode_pain）。归属 WS-D1。 */
    public static final RegistryObject<AbstractSpell> ENCODEPAIN = register(new EncodePainSpell());
    /** 铭忆 Engrave（engrave）。2026-09-18 新增，替代被删除的术忆。 */
    public static final RegistryObject<AbstractSpell> ENGRAVE = register(new EngraveSpell());

    // ---- 中阶（WS-D2，4 个 · 复诵已于 2026-09-18 删除，失忆同日由低阶改档进来） ----
    /**
     * 失忆 Amnesia（amnesia）。
     *
     * <p>⭐ 2026-09-18 从低阶 Uncommon 提到中阶 Rare，并挪进本分区：
     * 它与原「遗忘 forget」效果太像（都是"摘能力"），删掉 forget 后由它独占
     * 遗忘系的单体起手位。见 {@code AmnesiaSpell} 的类注释。
     */
    public static final RegistryObject<AbstractSpell> AMNESIA = register(new AmnesiaSpell());
    /** 忆格扩张 Expand Mind（expand_mind）。归属 WS-D2。 */
    public static final RegistryObject<AbstractSpell> EXPANDMIND = register(new ExpandMindSpell());
    /** 铭刻适应 Adaptation（adaptation）。先挨打，再换算成对应伤害类型的减伤。 */
    public static final RegistryObject<AbstractSpell> ADAPTATION = register(new AdaptationSpell());
    /** 帧缚 Framebind（framebind）。逼敌人按 24 帧走路，违反即冻结。 */
    public static final RegistryObject<AbstractSpell> FRAMEBIND = register(new FramebindSpell());
    /** 走马灯 Recollection（recollection）。归属 WS-D2。 */
    public static final RegistryObject<AbstractSpell> RECOLLECTION = register(new RecollectionSpell());
    /** 遗忘诅咒 Curse of Oblivion（curse_of_oblivion）。归属 WS-D2。 */
    public static final RegistryObject<AbstractSpell> CURSEOFOBLIVION = register(new CurseOfOblivionSpell());

    // ---- 高阶（WS-D3，6 个） ----
    /** 认知崩坏 Cognitive Collapse（cognitive_collapse）。归属 WS-D3。 */
    public static final RegistryObject<AbstractSpell> COGNITIVECOLLAPSE = register(new CognitiveCollapseSpell());
    /** 记忆掠夺 Memory Theft（memory_theft）。归属 WS-D3。 */
    public static final RegistryObject<AbstractSpell> MEMORYTHEFT = register(new MemoryTheftSpell());
    /** 集体遗忘 Mass Amnesia（mass_amnesia）。归属 WS-D3。 */
    public static final RegistryObject<AbstractSpell> MASSAMNESIA = register(new MassAmnesiaSpell());
    /** 既视感 Deja Vu（deja_vu）。归属 WS-D3。 */
    public static final RegistryObject<AbstractSpell> DEJAVU = register(new DejaVuSpell());
    /** 忆海 Sea of Memory（sea_of_memory）。归属 WS-D3。 */
    public static final RegistryObject<AbstractSpell> SEAOFMEMORY = register(new SeaOfMemorySpell());
    /** 千忆归一 Thousand Memories（thousand_memories）。归属 WS-D3。 */
    public static final RegistryObject<AbstractSpell> THOUSANDMEMORIES = register(new ThousandMemoriesSpell());
    /** 无尽忆域 Endless Realm（endless_realm）。领域类纯控制，零伤害。 */
    public static final RegistryObject<AbstractSpell> ENDLESSREALM = register(new EndlessRealmSpell());

    private static RegistryObject<AbstractSpell> register(final AbstractSpell spell) {
        return SPELLS.register(spell.getSpellName(), () -> spell);
    }

    /** 由主类在构造函数里调用。 */
    public static void register(final IEventBus modBus) {
        SPELLS.register(modBus);
    }
}
