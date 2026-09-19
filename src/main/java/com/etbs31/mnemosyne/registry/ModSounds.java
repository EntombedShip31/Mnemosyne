package com.etbs31.mnemosyne.registry;

import com.etbs31.mnemosyne.MnemosyneMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 忆海的 39 个音效事件。
 *
 * <p><b>文件归属</b>：WS-A 注册层。
 *
 * <p><b>零新增音频</b>：这些事件本身不携带任何 .ogg。
 * 它们指向的音频路径全部写在 {@code assets/mnemosyne/sounds.json} 里，
 * 且**全部引用 {@code irons_spellbooks:} 命名空间下已存在的 ogg**
 * （理由与授权分析见 {@code docs/tech/08_音效方案.md}）。
 * 校验脚本：{@code tools/check_sounds.py}。
 *
 * <p><b>不要新增 .ogg 文件</b>；要换音效，改 sounds.json，本文件一行都不用动。
 * 这就是引入这层"音效间接层"的价值。
 *
 * <p>事件名与 sounds.json 的键**必须逐字一致**（39 个，一一对应）。
 */
public final class ModSounds {

    private ModSounds() {}

    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MnemosyneMod.MODID);

    private static RegistryObject<SoundEvent> register(final String path) {
        return SOUNDS.register(path.replace('.', '_'),
                () -> SoundEvent.createVariableRangeEvent(
                        ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, path)));
    }

    // ------------------------------------------------------------------
    // 通用施法（3）
    // ------------------------------------------------------------------
    public static final RegistryObject<SoundEvent> CAST_DEFAULT = register("cast.default");
    public static final RegistryObject<SoundEvent> CAST_PREPARE = register("cast.prepare");
    public static final RegistryObject<SoundEvent> CAST_LONG = register("cast.long");

    // ------------------------------------------------------------------
    // 低阶法术（WS-D1）
    // ------------------------------------------------------------------
    public static final RegistryObject<SoundEvent> SPELL_MEMORY_ARROW_CAST = register("spell.memory_arrow.cast");
    public static final RegistryObject<SoundEvent> SPELL_MEMORY_ARROW_HIT = register("spell.memory_arrow.hit");
    public static final RegistryObject<SoundEvent> SPELL_GLIMPSE_CAST = register("spell.glimpse.cast");
    public static final RegistryObject<SoundEvent> SPELL_MEMORY_SHARD_CAST = register("spell.memory_shard.cast");
    public static final RegistryObject<SoundEvent> SPELL_MEMORY_SHARD_HIT = register("spell.memory_shard.hit");
    public static final RegistryObject<SoundEvent> SPELL_ENCODE_TRAIT_CAST = register("spell.encode_trait.cast");
    public static final RegistryObject<SoundEvent> SPELL_ENCODE_PAIN_CAST = register("spell.encode_pain.cast");
    public static final RegistryObject<SoundEvent> SPELL_ENGRAVE_CAST = register("spell.engrave.cast");
    public static final RegistryObject<SoundEvent> SPELL_AMNESIA_CAST = register("spell.amnesia.cast");

    // ------------------------------------------------------------------
    // 中阶法术（WS-D2）
    // ------------------------------------------------------------------
    public static final RegistryObject<SoundEvent> SPELL_EXPAND_MIND_CAST = register("spell.expand_mind.cast");
    public static final RegistryObject<SoundEvent> SPELL_RECOLLECTION_CAST = register("spell.recollection.cast");
    public static final RegistryObject<SoundEvent> SPELL_CURSE_OF_OBLIVION_CAST = register("spell.curse_of_oblivion.cast");

    // ------------------------------------------------------------------
    // 高阶法术（WS-D3）
    // ------------------------------------------------------------------
    public static final RegistryObject<SoundEvent> SPELL_COGNITIVE_COLLAPSE_CAST = register("spell.cognitive_collapse.cast");
    public static final RegistryObject<SoundEvent> SPELL_MEMORY_THEFT_CHARGE = register("spell.memory_theft.charge");
    public static final RegistryObject<SoundEvent> SPELL_MEMORY_THEFT_FINISH = register("spell.memory_theft.finish");
    public static final RegistryObject<SoundEvent> SPELL_MASS_AMNESIA_CAST = register("spell.mass_amnesia.cast");
    public static final RegistryObject<SoundEvent> SPELL_DEJA_VU_CAST = register("spell.deja_vu.cast");
    public static final RegistryObject<SoundEvent> SPELL_SEA_OF_MEMORY_CHARGE = register("spell.sea_of_memory.charge");
    public static final RegistryObject<SoundEvent> SPELL_SEA_OF_MEMORY_LOOP = register("spell.sea_of_memory.loop");
    public static final RegistryObject<SoundEvent> SPELL_SEA_OF_MEMORY_END = register("spell.sea_of_memory.end");
    public static final RegistryObject<SoundEvent> SPELL_FRAMEBIND_CAST = register("spell.framebind.cast");
    public static final RegistryObject<SoundEvent> SPELL_THOUSAND_MEMORIES_CHARGE = register("spell.thousand_memories.charge");
    public static final RegistryObject<SoundEvent> SPELL_THOUSAND_MEMORIES_RELEASE = register("spell.thousand_memories.release");
    public static final RegistryObject<SoundEvent> SPELL_THOUSAND_MEMORIES_HIT = register("spell.thousand_memories.hit");

    // ------------------------------------------------------------------
    // HUD（WS-I）
    // ------------------------------------------------------------------
    public static final RegistryObject<SoundEvent> HUD_ENGRAM_FILL = register("hud.engram.fill");
    public static final RegistryObject<SoundEvent> HUD_ENGRAM_DECAY = register("hud.engram.decay");
    public static final RegistryObject<SoundEvent> HUD_RESONANCE = register("hud.resonance");

    // ------------------------------------------------------------------
    // 界面（WS-J 忆碑 / WS-F 法书）
    // ------------------------------------------------------------------
    public static final RegistryObject<SoundEvent> UI_STELE_READ = register("ui.stele.read");
    public static final RegistryObject<SoundEvent> UI_LEARN = register("ui.learn");
    public static final RegistryObject<SoundEvent> UI_LOCKED_MEMORY = register("ui.locked_memory");
    public static final RegistryObject<SoundEvent> EQUIP_ROBE = register("equip.robe");

    // ------------------------------------------------------------------
    // 方块（WS-J）
    // ------------------------------------------------------------------
    public static final RegistryObject<SoundEvent> BLOCK_MEMORY_STELE_ACTIVATE = register("block.memory_stele.activate");
    public static final RegistryObject<SoundEvent> BLOCK_MEMORY_STELE_EXHAUSTED = register("block.memory_stele.exhausted");

    // ------------------------------------------------------------------
    // 音乐（忆者遗迹环境）
    // ------------------------------------------------------------------
    public static final RegistryObject<SoundEvent> MUSIC_MEMORY_RUIN = register("music.memory_ruin");
    public static final RegistryObject<SoundEvent> MUSIC_MEMORY_RUIN_ALT = register("music.memory_ruin_alt");

    /** 由主类在构造函数里调用。 */
    public static void register(final IEventBus modBus) {
        SOUNDS.register(modBus);
    }
}
