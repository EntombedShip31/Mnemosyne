package com.etbs31.mnemosyne.oblivion;

import com.etbs31.mnemosyne.MnemosyneMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;

import java.util.ArrayList;
import java.util.List;

/**
 * 从生物身上**探测**可借用的特质 —— 全部基于通用 API，对模组生物同样有效。
 *
 * <p><b>为什么需要它（2026-09-18）</b>
 * <br>「质忆」原先靠一张手写的"生物 → 特质"映射表。手写表的问题不是麻烦，而是
 * <b>它只对表里写了的生物有效</b> —— 整合包里加的任何模组生物都借不到东西，
 * 而且我们不可能枚举所有模组的生物。
 *
 * <p><b>答案是：MC/Forge 本身就提供了一套分层的通用特质查询接口。</b>
 * 越靠前的层越"通用"（对所有生物成立），越靠后越"精确"（但覆盖面窄）：
 *
 * <table border="1">
 *   <tr><th>层</th><th>数据来源</th><th>覆盖面</th><th>本类是否使用</th></tr>
 *   <tr><td>① 原版谓词</td>
 *       <td>{@code fireImmune()} / {@code canBreatheUnderwater()} / {@code getMobType()} /
 *           {@code getType().getCategory()}</td>
 *       <td><b>所有生物</b>（含模组生物）</td><td>✅ 主力</td></tr>
 *   <tr><td>② 属性表</td>
 *       <td>{@code KNOCKBACK_RESISTANCE} / {@code JUMP_STRENGTH} / {@code MOVEMENT_SPEED} /
 *           {@code FLYING_SPEED}</td>
 *       <td><b>所有生物</b></td><td>✅ 主力</td></tr>
 *   <tr><td>③ 实体类型标签</td>
 *       <td>{@code EntityTypeTags.*} + <b>本模组自建的 {@link #HAS_ESSENCE}</b></td>
 *       <td>原版标签 + <b>整合包可自行扩展</b></td><td>✅ 用于扩展点</td></tr>
 *   <tr><td>④ AI goal（本项目已有 {@code AbilityMap}）</td>
 *       <td>{@code goalSelector.getAvailableGoals()}</td>
 *       <td>走 goal 的生物；<b>Brain 生物为 0</b></td><td>❌ 见下</td></tr>
 *   <tr><td>⑤ Brain 行为</td>
 *       <td>{@code entity.getBrain()}</td>
 *       <td>Brain 生物</td><td>❌ 需 Mixin，属 WS-E2</td></tr>
 * </table>
 *
 * <p><b>⭐ 为什么不用第 ④ 层（goal）</b>：它回答的是"这个生物**会做什么**"
 * （攻击 / 逃跑 / 跟随），而质忆要的是"它**是什么**"（火焰免疫 / 会飞 / 是亡灵）。
 * 前者是行为、后者是属性 —— 用行为反推属性会得到大量错误结论
 * （"会近战攻击"不代表任何可借的特质）。而且 Brain 生物 goal 数为 0，
 * 那一层对它们完全失效。① ② 两层反而是**对 Brain 生物也成立**的。
 *
 * <p><b>⭐ 整合包怎么扩展</b>：往数据包里加一个实体类型标签
 * {@code data/<任何命名空间>/tags/entity_types/mnemosyne_has_essence.json}，
 * 把任意生物加进 {@code #mnemosyne:has_essence} 即可 —— 无需改代码。
 * （标签是合并语义，多个数据包可以各加各的。）
 */
public final class TraitProbe {

    private TraitProbe() {}

    /**
     * 数据包可扩展的"有本质"标签 —— {@code #mnemosyne:has_essence}。
     *
     * <p>整合包把一个生物加进这个标签，就等于声明"它有可被记录的本质"。
     * 本类会把它的可探测特质全部返回；如果它一个可探测特质都没有，
     * 则退回 {@link TraitRegistry#GENERIC}（通用记忆）——
     * 至少玩家借到的东西是**有效的**，而不是一个装着空记忆的忆格。
     */
    public static final TagKey<EntityType<?>> HAS_ESSENCE =
            TagKey.create(Registries.ENTITY_TYPE,
                    ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "has_essence"));

    /** 移动速度高于这个值算"快"（原版大多数陆生怪在 0.2~0.3）。 */
    private static final double FAST_MOVEMENT_SPEED = 0.30D;

    /** 跳跃强度高于这个值算"跳得高"（原版僵尸 0.42，马 0.4~1.0）。 */
    private static final double HIGH_JUMP_STRENGTH = 0.45D;

    /**
     * 探测一个生物身上**所有可借用**的特质。
     *
     * <p>只返回 {@code TraitRegistry} 里**真正实现过**的特质 ——
     * 探测出一个 {@code applyTrait} 会返回 false 的特质，
     * 等于给玩家一个**释放后毫无效果**的忆格（本项目一直在治的静默失效）。
     *
     * @return 可为空的列表；空表示"探测不到任何可借之物"
     */
    public static List<ResourceLocation> probe(final LivingEntity target) {
        final List<ResourceLocation> found = new ArrayList<>(4);

        // ---------- ① 原版谓词（对所有生物成立） ----------

        // 火焰免疫：烈焰人、岩浆怪、凋灵、以及任何覆写了 fireImmune() 的模组生物
        if (target.fireImmune()) {
            found.add(TraitRegistry.FIRE_IMMUNITY);
        }
        // 水下呼吸：鱼、海豚、守卫者、溺尸……
        if (target.canBreatheUnderwater()) {
            found.add(TraitRegistry.WATER_BREATHING);
        }
        // 亡灵：僵尸、骷髅、幻翼、凋灵……（亡灵天然免疫凋零）
        if (target.getMobType() == MobType.UNDEAD) {
            found.add(TraitRegistry.WITHER_IMMUNITY);
        }

        // ---------- ② 属性表（对所有生物成立） ----------

        // 击退抗性：铁傀儡、劫掠兽、以及任何堆了这个属性的生物
        if (value(target, Attributes.KNOCKBACK_RESISTANCE) > 0.0D) {
            found.add(TraitRegistry.KNOCKBACK_RESIST);
        }
        // 跳跃强度：马、兔子、山羊
        if (value(target, Attributes.JUMP_STRENGTH) > HIGH_JUMP_STRENGTH) {
            found.add(TraitRegistry.JUMP_BOOST);
        }
        // 移动速度：豹猫、马、以及任何"跑得快"的生物
        if (value(target, Attributes.MOVEMENT_SPEED) > FAST_MOVEMENT_SPEED) {
            found.add(TraitRegistry.SPEED);
        }
        // 飞行：幻翼、蜜蜂、恶魂……（FLYING_SPEED 有值 或 用的是飞行移动控制器）
        // ⚠️ getMoveControl() 在 Mob 上，不在 LivingEntity 上 —— 必须先判 instanceof。
        final boolean usesFlyingMoveControl =
                target instanceof net.minecraft.world.entity.Mob mob
                        && mob.getMoveControl() instanceof FlyingMoveControl;
        if (value(target, Attributes.FLYING_SPEED) > 0.0D || usesFlyingMoveControl) {
            found.add(TraitRegistry.LEVITATION);
        }

        // ---------- ③ 数据包标签（整合包扩展点） ----------
        //
        // ⚠️ 注意这里**不**判断"是否在 HAS_ESSENCE 里才返回"：
        //    标签的语义是"额外承认它有本质"，不是"唯一准入条件"——
        //    否则整合包不加标签就什么都借不到，那又回到手写表的老路了。
        //    标签的作用见 probeOrGeneric()。

        return found;
    }

    /**
     * 探测特质；探测不到时按"是否有数据包授权"决定退回通用记忆还是判定为无本质。
     *
     * @return 特质 id；{@code null} 表示"这具生物没有可被记录的本质"（调用方应给玩家提示）
     */
    public static ResourceLocation probeOrGeneric(final LivingEntity target) {
        final List<ResourceLocation> found = probe(target);
        if (!found.isEmpty()) {
            return found.get(target.getRandom().nextInt(found.size()));
        }
        // 探测不到 → 看数据包有没有把它标记为"有本质"
        if (target.getType().is(HAS_ESSENCE)) {
            return TraitRegistry.GENERIC;
        }
        return null;
    }

    /** 读一个属性值；生物没有这个属性时返回 0（例如鱼没有 KNOCKBACK_RESISTANCE）。 */
    private static double value(final LivingEntity entity, final net.minecraft.world.entity.ai.attributes.Attribute attribute) {
        final var instance = entity.getAttribute(attribute);
        return instance == null ? 0.0D : instance.getValue();
    }

    /** {@code MobCategory} 的语义说明用（文档 / 调试）。 */
    public static boolean isAquatic(final LivingEntity entity) {
        final MobCategory category = entity.getType().getCategory();
        return category == MobCategory.WATER_CREATURE
                || category == MobCategory.WATER_AMBIENT
                || entity.getMobType() == MobType.WATER;
    }
}
