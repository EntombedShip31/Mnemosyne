package com.etbs31.mnemosyne.registry;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.oblivion.EngramEffect;
import com.etbs31.mnemosyne.oblivion.MemoryStateEffect;
import com.etbs31.mnemosyne.oblivion.OblivionEffect;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.Nullable;

/**
 * 忆海的四个状态效果。
 *
 * <p><b>文件归属</b>：WS-E 遗忘系统。
 * <br>⚠️ 这是一个**新建**文件：{@code registry/**} 整体归 WS-A，但 WS-A 从未创建过它，
 * 所以新建它不算违反铁律一（{@code docs/tech/12} §1.4 纪律 ②）。
 * 建好之后其他工作流不要再动。
 *
 * <p><b>四个 id（冻结，{@code docs/tech/12} 提示词 A）</b>：
 * <table border="1">
 *   <tr><th>id</th><th>用途</th></tr>
 *   <tr><td>{@code mnemosyne:forget}</td><td>遗忘（tier 1）</td></tr>
 *   <tr><td>{@code mnemosyne:amnesia}</td><td>失忆 / 集体遗忘（tier 2/3）</td></tr>
 *   <tr><td>{@code mnemosyne:cognitive_overload}</td><td>认知过载，amplifier = 层数</td></tr>
 *   <tr><td>{@code mnemosyne:sluggish}</td><td>迟滞</td></tr>
 * </table>
 *
 * <p><b>⚠️⚠️ 集成者必读：本文件需要主类加一行，WS-E 故意没有加</b>
 * <br>{@code docs/tech/12} §1.4 纪律 ①：{@code MnemosyneMod.java} 归 WS-0，
 * **任何工作流都不许碰**。本文件的 DeferredRegister 必须由集成者在 Phase 末尾统一挂载：
 * <pre>{@code
 * // MnemosyneMod.java 构造函数「② Forge 标准注册表」那一段，与 ModItems / ModSounds 并列：
 * ModEffects.register(modBus);
 * }</pre>
 * 在那一行加上之前：编译完全正常，但四个效果**不会被注册**，
 * 遗忘系统会自动退化成"只移除行为、不挂状态效果"（见 {@link #forget()} 的防御性取用），
 * 不会崩、也不会报错。这就是为什么这里不用 {@code .get()} 直接取 —— 那会在挂载前抛 NPE。
 */
public final class ModEffects {

    private ModEffects() {}

    private static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, MnemosyneMod.MODID);

    // 颜色取自 docs/09 的配色系统（靛蓝 #534AB7 / 品红 #D4537E / 灰紫 #5C5480）
    public static final RegistryObject<MobEffect> FORGET = EFFECTS.register("forget",
            () -> new OblivionEffect(OblivionEffect.Mode.FORGET, 0x534AB7));
    public static final RegistryObject<MobEffect> AMNESIA = EFFECTS.register("amnesia",
            () -> new OblivionEffect(OblivionEffect.Mode.AMNESIA, 0x3A2E8C));
    public static final RegistryObject<MobEffect> COGNITIVE_OVERLOAD = EFFECTS.register("cognitive_overload",
            () -> new OblivionEffect(OblivionEffect.Mode.COGNITIVE_OVERLOAD, 0xD4537E));
    public static final RegistryObject<MobEffect> SLUGGISH = EFFECTS.register("sluggish",
            () -> new OblivionEffect(OblivionEffect.Mode.SLUGGISH, 0x5C5480));

    // ==================================================================
    // 忆格状态效果（2026-09-18 架构调整：数值层交给原版效果承载）
    // ==================================================================
    //
    // ⚠️ 这三个效果是**派生缓存**，不是权威数据源。
    //    忆格的真实状态存在玩家 NBT 里，由 MnemosyneData.refreshEngramEffects() 定期重算并刷新它们。
    //    喝牛奶 / 被净化只会清掉缓存，下一次刷新就自己长回来 —— 这是刻意设计，不要"修"。


    /** 负担：amplifier = 计入惩罚的格数 − 1。承载施法速度惩罚修饰符。 */
    public static final RegistryObject<MobEffect> ENGRAM_BURDEN = EFFECTS.register("engram_burden",
            () -> new EngramEffect(EngramEffect.Mode.BURDEN, EngramEffect.COLOR_BURDEN));

    /** 临时忆格：amplifier = 临时格数 − 1，duration = 临时格窗口。 */
    public static final RegistryObject<MobEffect> TEMPORAL_ENGRAM = EFFECTS.register("temporal_engram",
            () -> new EngramEffect(EngramEffect.Mode.TEMPORAL, EngramEffect.COLOR_TEMPORAL));

    /** 走马灯守护：10 秒保命窗口，致死时被拦截（设计文档 v2 §一）。 */
    public static final RegistryObject<MobEffect> RECOLLECTION_WARD = EFFECTS.register("recollection_ward",
            () -> new MemoryStateEffect(MemoryStateEffect.Mode.RECOLLECTION_WARD,
                    MemoryStateEffect.COLOR_WARD));

    /** 记忆空白：触发保命后的代价（法力恢复 −40% / 受到的忆海法术伤害 +50%）。 */
    public static final RegistryObject<MobEffect> MEMORY_BLANK = EFFECTS.register("memory_blank",
            () -> new MemoryStateEffect(MemoryStateEffect.Mode.MEMORY_BLANK,
                    MemoryStateEffect.COLOR_BLANK));

    /** 无尽忆域定身：移动速度归零。由 EndlessRealmSpell 每 tick 补刷（抗牛奶，且不会永久留人）。 */
    public static final RegistryObject<MobEffect> ENDLESS_BIND = EFFECTS.register("endless_bind",
            () -> new MemoryStateEffect(MemoryStateEffect.Mode.ENDLESS_BIND,
                    MemoryStateEffect.COLOR_BIND));

    /** 由主类（WS-0）在构造函数里调用 —— 见类注释的「集成者必读」。 */
    public static void register(final IEventBus modBus) {
        EFFECTS.register(modBus);
    }

    // ==================================================================
    // 防御性取用
    // ==================================================================

    /**
     * 取「遗忘」效果，**未注册时返回 {@code null}**。
     *
     * <p>为什么不直接用 {@code FORGET.get()}：{@code RegistryObject.get()} 在
     * DeferredRegister 尚未挂载时会抛异常。集成者还没加那一行时，
     * 遗忘系统应当"少一个视觉效果"而不是整局崩掉。
     */
    @Nullable
    public static MobEffect forget() {
        return FORGET.isPresent() ? FORGET.get() : null;
    }

    /** 取「失忆」效果，未注册时返回 {@code null}。 */
    @Nullable
    public static MobEffect amnesia() {
        return AMNESIA.isPresent() ? AMNESIA.get() : null;
    }

    /**
     * 取「认知过载」效果，未注册时返回 {@code null}。
     *
     * <p>给 WS-D3 的「认知崩坏」读层数用（{@code amplifier + 1} = 层数）。
     * 与 {@link #forget()} / {@link #amnesia()} 一样是**防御性取用**：
     * 主类还没挂载 {@code ModEffects} 时返回 {@code null}，调用方静默跳过叠层，
     * 而不是抛 NPE 把整局崩掉。
     */
    @Nullable
    public static MobEffect cognitiveOverload() {
        return COGNITIVE_OVERLOAD.isPresent() ? COGNITIVE_OVERLOAD.get() : null;
    }

    /**
     * 取「迟滞」效果，未注册时返回 {@code null}。
     *
     * <p>迟滞（攻击速度与移速 -15%，见 {@code OblivionEffect}）目前**没有法术在用** ——
     * 它是 {@code docs/tech/03} §七 为后续内容预留的减益。
     * 保留这个访问器是为了让"未使用"这件事是**明确的**，而不是让人以为漏了注册。
     */
    @Nullable
    public static MobEffect sluggish() {
        return SLUGGISH.isPresent() ? SLUGGISH.get() : null;
    }


    /** 取「负担」效果，未注册时返回 {@code null}。 */
    @Nullable
    public static MobEffect engramBurden() {
        return ENGRAM_BURDEN.isPresent() ? ENGRAM_BURDEN.get() : null;
    }

    /** 取「临时忆格」效果，未注册时返回 {@code null}。 */
    @Nullable
    public static MobEffect temporalEngram() {
        return TEMPORAL_ENGRAM.isPresent() ? TEMPORAL_ENGRAM.get() : null;
    }

    /** 取「走马灯守护」效果，未注册时返回 {@code null}。 */
    @Nullable
    public static MobEffect recollectionWard() {
        return RECOLLECTION_WARD.isPresent() ? RECOLLECTION_WARD.get() : null;
    }

    /** 取「记忆空白」效果，未注册时返回 {@code null}。 */
    @Nullable
    public static MobEffect memoryBlank() {
        return MEMORY_BLANK.isPresent() ? MEMORY_BLANK.get() : null;
    }

}
