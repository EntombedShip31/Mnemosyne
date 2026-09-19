package com.etbs31.mnemosyne.oblivion;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 表 B · 可窃取的特性（质忆）—— 把"敌人是什么"变成"我暂时是什么"。
 *
 * <p><b>文件归属</b>：WS-E 遗忘系统。
 *
 * <p><b>本类负责两件事</b>：
 * <ol>
 *   <li><b>清单</b>：{@code EntityType → 可窃取特性} 的映射（{@code docs/02} §四 表 B，20 个特性）。
 *       数据包可覆盖（{@code data/mnemosyne/mnemosyne/oblivion/traits.json}）。</li>
 *   <li><b>生效</b>：{@link #applyTrait} —— 释放质忆时把特性装到玩家身上。</li>
 * </ol>
 * 写入侧（{@code OblivionManager.stealTrait} 把特性存进忆格）在本类只提供
 * {@link #hasTrait} 与 {@link #traitDurationTicks} 两个查询，不碰忆格 API ——
 * 那样才不会让 WS-B 的数据层被两个工作流同时改。
 *
 * <p><b>⭐ 为什么"生效"也归 WS-E 而不是 WS-D</b>：{@code docs/tech/03} §8.1 把特性分成三类
 * （被动免疫 / 属性修改 / 主动能力），前两类都是**事件与属性修饰符**，
 * 与遗忘系统共用同一套"瞬态 + 固定 UUID + 到期清理"机制。
 * 把它放在 WS-D 会让 4 个法术各自实现一遍，且必然出现"修饰符忘了移除"的残留 bug。
 *
 * <p><b>⭐ 实测：Forge 的 {@code LivingHurtEvent} **不是** Cancelable</b>
 * <br>它只提供 {@code setAmount(float)}（读源码确认：类上没有 {@code @Cancelable}）。
 * 所以"火焰免疫"必须写 {@code event.setAmount(0)} 而不是 {@code setCanceled(true)} ——
 * 后者根本编译不过。
 *
 * <p><b>⭐ 属性修饰符必须是 transient</b>（{@code docs/tech/03} §8.2）：
 * {@code addTransientModifier} 不会被写进存档，所以"服务器重启后不会留下永久加成的玩家"。
 * 修饰符 UUID 由特性 id **确定性派生**（{@link #uuidFor}），
 * 这样重复窃取同一个特性只会刷新而不会叠加，到期也能精确移除。
 *
 * <p><b>⚠️ 主动能力类（爬墙 / 短距传送 / 滑翔 / 无声行走 / 地形条件加速）推迟到 WS-E2</b>：
 * {@code docs/tech/03} §8.1 明确建议"先做前两类（覆盖 80%），第三类放到后期"。
 * 这里**不做半成品** —— 半成品的定时器/速度分量比不实现更危险（§7.3 的教训）。
 * 落在 {@link #TODO_E2_TRAITS} 里的特性，{@link #applyTrait} 会明确返回 {@code false} 并记日志。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TraitRegistry {

    private TraitRegistry() {}

    // ==================================================================
    // 特性 id（冻结）
    // ==================================================================

    public static final ResourceLocation FIRE_IMMUNITY = trait("fire_immunity");
    public static final ResourceLocation LAVA_AFFINITY = trait("lava_affinity");
    public static final ResourceLocation SLOW_FALLING = trait("slow_falling");
    public static final ResourceLocation SHORT_TELEPORT = trait("short_teleport");
    public static final ResourceLocation WALL_CLIMB = trait("wall_climb");
    public static final ResourceLocation SNOW_SPEED = trait("snow_speed");
    public static final ResourceLocation ICE_SPEED = trait("ice_speed");
    public static final ResourceLocation WATER_BREATHING = trait("water_breathing");
    public static final ResourceLocation SWIM_SPEED = trait("swim_speed");
    public static final ResourceLocation NIGHT_VISION = trait("night_vision");
    public static final ResourceLocation DARK_VISION = trait("dark_vision");
    public static final ResourceLocation KNOCKBACK_RESIST = trait("knockback_resist");
    public static final ResourceLocation JUMP_BOOST = trait("jump_boost");
    public static final ResourceLocation SILENT_WALK = trait("silent_walk");
    public static final ResourceLocation WITHER_IMMUNITY = trait("wither_immunity");
    public static final ResourceLocation REGENERATION = trait("regeneration");
    public static final ResourceLocation LEVITATION = trait("levitation");
    public static final ResourceLocation SPEED = trait("speed");
    public static final ResourceLocation GLIDE = trait("glide");

    /**
     * 「通用记忆」—— {@code docs/tech/03} §7.4 表格第 4 行 / {@code docs/02} §六 的**降级**特性。
     *
     * <p>当「写入 · 质忆」的目标**没有任何可窃取特性**（纯被动生物、其他模组生物）时，
     * 不写特性而是写这一条"通用记忆"：释放时给玩家 30 秒的 +10% 移速。
     *
     * <p><b>为什么用特性 id 而不是给 {@code EngramType} 加第四种类型</b>：
     * {@code EngramType} 是 WS-B 的**冻结**枚举（其注释明确写"不要加第四个"，
     * 三种记忆对应收集 / 削弱 / 爆发三条支柱）。用 {@code ESSENCE} 类型 + 这个特殊 id
     * 表达"通用记忆"，既满足降级要求，又不越界改 WS-B 的契约。
     *
     * <p>它**不会**出现在任何生物的 {@code traits.json} 里 —— 只能由降级路径写入。
     */
    public static final ResourceLocation GENERIC = trait("generic");

    // ==================================================================
    // 三类实现方式（docs/tech/03 §8.1）
    // ==================================================================

    /** 属性修改类：一次性挂瞬态修饰符，到期移除。 */
    private record AttributeTrait(Attribute attribute, double amount, AttributeModifier.Operation operation) {}

    private static final Map<ResourceLocation, AttributeTrait> ATTRIBUTE_TRAITS = Map.of(
            KNOCKBACK_RESIST, new AttributeTrait(Attributes.KNOCKBACK_RESISTANCE, 0.70D,
                    AttributeModifier.Operation.ADDITION),
            JUMP_BOOST, new AttributeTrait(Attributes.JUMP_STRENGTH, 0.50D,
                    AttributeModifier.Operation.MULTIPLY_TOTAL),
            SPEED, new AttributeTrait(Attributes.MOVEMENT_SPEED, 0.25D,
                    AttributeModifier.Operation.MULTIPLY_TOTAL),
            // 降级用「通用记忆」：+10% 移速（比正牌 speed 的 +25% 弱，符合"降级"语义）
            GENERIC, new AttributeTrait(Attributes.MOVEMENT_SPEED, 0.10D,
                    AttributeModifier.Operation.MULTIPLY_TOTAL));

    /** 药水效果类：直接用原版效果承载，简单且自带同步。 */
    private static final Map<ResourceLocation, MobEffect> EFFECT_TRAITS = Map.of(
            WATER_BREATHING, MobEffects.WATER_BREATHING,
            NIGHT_VISION, MobEffects.NIGHT_VISION,
            DARK_VISION, MobEffects.NIGHT_VISION,
            REGENERATION, MobEffects.REGENERATION,
            SLOW_FALLING, MobEffects.SLOW_FALLING,
            LEVITATION, MobEffects.SLOW_FALLING);

    /**
     * 被动免疫类：需要在事件 / tick 里拦截。
     *
     * <p>{@link #FIRE_IMMUNITY} → {@code LivingHurtEvent}；{@link #LEVITATION} → {@code LivingFallEvent}；
     * {@link #WITHER_IMMUNITY} / {@link #DARK_VISION} → {@link #tick} 里持续清效果。
     */
    private static final Set<ResourceLocation> IMMUNITY_TRAITS = Set.of(
            FIRE_IMMUNITY, WITHER_IMMUNITY, DARK_VISION, LEVITATION);

    /** 第三类「主动能力」与「地形条件加速」—— 推迟到 WS-E2，明确不实现。 */
    private static final Set<ResourceLocation> TODO_E2_TRAITS = Set.of(
            LAVA_AFFINITY, SHORT_TELEPORT, WALL_CLIMB, SNOW_SPEED, ICE_SPEED, SWIM_SPEED,
            SILENT_WALK, GLIDE);

    /** 特性统一持续 30 秒（{@code docs/02} §四 表 B 的"释放后效果"列）。 */
    public static final int DEFAULT_DURATION_TICKS = 600;

    // ==================================================================
    // 玩家身上的特性记录（NBT）
    // ==================================================================

    private static final String NBT_TRAITS = "mnemosyne_traits";
    private static final String KEY_ID = "id";
    private static final String KEY_UNTIL = "until";

    /** 每 20 tick 检查一次到期，不要每 tick 扫。 */
    private static final int CHECK_INTERVAL = 20;

    // ==================================================================
    // 表 B（内建 + 数据包覆盖）
    // ==================================================================

    private static final Map<ResourceLocation, List<ResourceLocation>> BUILT_IN = buildBuiltIn();
    private static volatile Map<ResourceLocation, List<ResourceLocation>> declared = BUILT_IN;

    static final ResourceLocation FILE_TRAITS = trait("traits");

    /** 由 {@link OblivionManager} 的重载监听器调用。 */
    static void acceptDataPack(final Map<ResourceLocation, JsonElement> files) {
        final JsonElement element = files.get(FILE_TRAITS);
        if (element == null || !element.isJsonObject()) {
            declared = BUILT_IN;
            return;
        }
        final JsonObject root = element.getAsJsonObject();
        final Map<ResourceLocation, List<ResourceLocation>> out = new LinkedHashMap<>();
        if (!root.has("replace") || !root.get("replace").getAsBoolean()) {
            out.putAll(BUILT_IN);
        }
        final JsonElement tableElement = root.get("traits");
        if (tableElement == null || !tableElement.isJsonObject()) {
            MnemosyneMod.LOGGER.warn("[WS-E] traits.json 缺少 traits 对象，沿用内建表 B");
            declared = Map.copyOf(out);
            return;
        }
        for (final Map.Entry<String, JsonElement> entry : tableElement.getAsJsonObject().entrySet()) {
            final ResourceLocation mob = ResourceLocation.tryParse(entry.getKey());
            if (mob == null || !entry.getValue().isJsonArray()) {
                MnemosyneMod.LOGGER.warn("[WS-E] traits.json 忽略非法条目：{}", entry.getKey());
                continue;
            }
            final JsonArray array = entry.getValue().getAsJsonArray();
            final List<ResourceLocation> list = new ArrayList<>(array.size());
            for (final JsonElement item : array) {
                final ResourceLocation parsed = ResourceLocation.tryParse(item.getAsString());
                if (parsed == null) {
                    MnemosyneMod.LOGGER.warn("[WS-E] traits.json 忽略非法特性 id：{}", item.getAsString());
                    continue;
                }
                list.add(parsed);
            }
            out.put(mob, List.copyOf(list));
        }
        MnemosyneMod.LOGGER.info("[WS-E] 表 B 已加载：{} 个生物映射", out.size());
        declared = Map.copyOf(out);
    }

    // ==================================================================
    // 查询 API
    // ==================================================================

    /** 表 B：该生物可被窃取的特性清单（数据包可覆盖）。 */
    public static List<ResourceLocation> getTraits(final EntityType<?> type) {
        if (type == null) {
            return List.of();
        }
        return declared.getOrDefault(EntityType.getKey(type), List.of());
    }

    /** 该生物是否拥有指定特性 —— {@code stealTrait} 的准入检查。 */
    public static boolean hasTrait(final EntityType<?> type, final ResourceLocation traitId) {
        return traitId != null && getTraits(type).contains(traitId);
    }

    /** 特性生效时长（tick）。当前所有特性统一 30 秒。 */
    public static int traitDurationTicks(final ResourceLocation traitId) {
        return DEFAULT_DURATION_TICKS;
    }

    // ==================================================================
    // 生效
    // ==================================================================

    /**
     * 把特性装到玩家身上（释放质忆 / 记忆掠夺的落地动作）。
     *
     * @param durationTicks 生效时长；{@code <= 0} 时用 {@link #DEFAULT_DURATION_TICKS}
     * @return 是否真的施加了什么东西（{@code false} = 该特性属于 WS-E2 的主动能力类，
     *         或 id 完全未知 —— 调用方应当**退还法力**而不是静默失败）
     */
    public static boolean applyTrait(final ServerPlayer player, final ResourceLocation traitId,
                                     final int durationTicks) {
        if (player == null || traitId == null) {
            return false;
        }
        if (TODO_E2_TRAITS.contains(traitId)) {
            MnemosyneMod.LOGGER.debug("[WS-E] 特性 {} 属于「主动能力类」，按 docs/tech/03 §8.1 推迟到 WS-E2",
                    traitId);
            return false;
        }
        final int duration = durationTicks > 0 ? durationTicks : DEFAULT_DURATION_TICKS;

        boolean applied = applyAttributeTrait(player, traitId);
        applied |= applyEffectTrait(player, traitId, duration);
        if (IMMUNITY_TRAITS.contains(traitId)) {
            applied = true;
        }
        if (!applied) {
            return false;
        }
        remember(player, traitId, duration);
        return true;
    }

    private static boolean applyAttributeTrait(final Player player, final ResourceLocation traitId) {
        final AttributeTrait spec = ATTRIBUTE_TRAITS.get(traitId);
        if (spec == null) {
            return false;
        }
        final AttributeInstance instance = player.getAttribute(spec.attribute());
        if (instance == null) {
            return false;
        }
        // 先移除同 UUID 的旧修饰符：重复窃取只刷新时长，不叠加数值
        instance.removeModifier(uuidFor(traitId));
        instance.addTransientModifier(new AttributeModifier(uuidFor(traitId),
                "mnemosyne:trait/" + traitId.getPath(), spec.amount(), spec.operation()));
        return true;
    }

    private static boolean applyEffectTrait(final Player player, final ResourceLocation traitId,
                                            final int durationTicks) {
        final MobEffect effect = EFFECT_TRAITS.get(traitId);
        if (effect == null) {
            return false;
        }
        player.addEffect(new MobEffectInstance(effect, durationTicks, 0, false, true, true));
        return true;
    }

    // ==================================================================
    // 记录与到期清理
    // ==================================================================

    private static void remember(final Player player, final ResourceLocation traitId, final int durationTicks) {
        final CompoundTag root = player.getPersistentData();
        final ListTag list = root.getList(NBT_TRAITS, Tag.TAG_COMPOUND);
        final long until = OblivionManager.nowTick(player) + durationTicks;
        // 已存在 → 只刷新到期时刻，不重复记录（避免列表无限增长）
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag tag = list.getCompound(i);
            if (traitId.toString().equals(tag.getString(KEY_ID))) {
                tag.putLong(KEY_UNTIL, until);
                return;
            }
        }
        final CompoundTag tag = new CompoundTag();
        tag.putString(KEY_ID, traitId.toString());
        tag.putLong(KEY_UNTIL, until);
        list.add(tag);
        root.put(NBT_TRAITS, list);
    }

    /** 玩家身上是否有一条**尚未到期**的指定特性。 */
    public static boolean hasActiveTrait(final Player player, final ResourceLocation traitId) {
        if (player == null || traitId == null) {
            return false;
        }
        final long now = OblivionManager.nowTick(player);
        final ListTag list = player.getPersistentData().getList(NBT_TRAITS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag tag = list.getCompound(i);
            if (traitId.toString().equals(tag.getString(KEY_ID)) && tag.getLong(KEY_UNTIL) > now) {
                return true;
            }
        }
        return false;
    }

    /**
     * 每 20 tick 的维护：到期清理 + 被动免疫的持续压制。
     *
     * <p>为什么免疫用"每 20 tick 清一次"而不是 {@code MobEffectEvent.Applicable}：
     * 后者的语义在 1.20.1 是 {@code @HasResult}（{@code Result.DENY}）而不是 {@code @Cancelable}，
     * 用错会静默失效；而且它只在"施加效果"那一刻触发 ——
     * 玩家先中凋零、**再**窃取凋零免疫时拦不住。每 20 tick 清一次覆盖了两种顺序。
     */
    static void tick(final ServerPlayer player) {
        if (player.tickCount % CHECK_INTERVAL != 0) {
            return;
        }
        final CompoundTag root = player.getPersistentData();
        final ListTag list = root.getList(NBT_TRAITS, Tag.TAG_COMPOUND);
        if (list.isEmpty()) {
            return;
        }
        final long now = OblivionManager.nowTick(player);
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            final CompoundTag tag = list.getCompound(i);
            final ResourceLocation id = ResourceLocation.tryParse(tag.getString(KEY_ID));
            if (id == null || now >= tag.getLong(KEY_UNTIL)) {
                if (id != null) {
                    removeAttributeTrait(player, id);
                }
                list.remove(i);
                changed = true;
                continue;
            }
            if (WITHER_IMMUNITY.equals(id)) {
                player.removeEffect(MobEffects.WITHER);
            }
            if (DARK_VISION.equals(id)) {
                player.removeEffect(MobEffects.BLINDNESS);
            }
        }
        if (changed) {
            root.put(NBT_TRAITS, list);
        }
    }

    /** 清空玩家的全部特性（死亡、管理员指令）。 */
    public static void clearAll(final Player player) {
        final CompoundTag root = player.getPersistentData();
        final ListTag list = root.getList(NBT_TRAITS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            final ResourceLocation id = ResourceLocation.tryParse(list.getCompound(i).getString(KEY_ID));
            if (id != null) {
                removeAttributeTrait(player, id);
            }
        }
        root.remove(NBT_TRAITS);
    }

    private static void removeAttributeTrait(final Player player, final ResourceLocation traitId) {
        final AttributeTrait spec = ATTRIBUTE_TRAITS.get(traitId);
        if (spec == null) {
            return;
        }
        final AttributeInstance instance = player.getAttribute(spec.attribute());
        if (instance != null) {
            instance.removeModifier(uuidFor(traitId));
        }
    }

    // ==================================================================
    // 事件（被动免疫类）
    // ==================================================================

    /** 火焰免疫：{@code docs/02} 表 B 的"30 秒内免疫火焰与岩浆伤害"。 */
    @SubscribeEvent
    public static void onLivingHurt(final LivingHurtEvent event) {
        if (event.getAmount() <= 0.0F || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!hasActiveTrait(player, FIRE_IMMUNITY)) {
            return;
        }
        // ⚠️ LivingHurtEvent 不是 Cancelable（实测），只能把伤害改成 0
        if (event.getSource().is(DamageTypeTags.IS_FIRE)) {
            event.setAmount(0.0F);
        }
    }

    /** 悬浮：{@code docs/02} 表 B 的"缓慢下落 + 不受摔落伤害"。 */
    @SubscribeEvent
    public static void onLivingFall(final LivingFallEvent event) {
        if (event.getDamageMultiplier() <= 0.0F || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (hasActiveTrait(player, LEVITATION)) {
            event.setDamageMultiplier(0.0F);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(final TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        tick(player);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 由特性 id 确定性派生修饰符 UUID。
     *
     * <p>为什么不用随机 UUID：随机 UUID 每次窃取都会新增一个修饰符，
     * 重复窃取 → 数值叠加；到期时也无从知道该移除哪一个。
     * 确定性派生让"刷新"与"移除"都变成精确操作。
     */
    private static UUID uuidFor(final ResourceLocation traitId) {
        return UUID.nameUUIDFromBytes(("mnemosyne:trait/" + traitId).getBytes(StandardCharsets.UTF_8));
    }

    private static ResourceLocation trait(final String path) {
        return ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, path);
    }

    private static ResourceLocation mc(final String path) {
        return ResourceLocation.fromNamespaceAndPath("minecraft", path);
    }

    private static Map<ResourceLocation, List<ResourceLocation>> buildBuiltIn() {
        final Map<ResourceLocation, List<ResourceLocation>> map = new LinkedHashMap<>();
        map.put(mc("blaze"), List.of(FIRE_IMMUNITY));
        map.put(mc("magma_cube"), List.of(FIRE_IMMUNITY, LAVA_AFFINITY));
        map.put(mc("ghast"), List.of(FIRE_IMMUNITY, SLOW_FALLING));
        map.put(mc("strider"), List.of(FIRE_IMMUNITY, LAVA_AFFINITY));
        map.put(mc("enderman"), List.of(SHORT_TELEPORT));
        map.put(mc("spider"), List.of(WALL_CLIMB));
        map.put(mc("cave_spider"), List.of(WALL_CLIMB));
        map.put(mc("snow_golem"), List.of(SNOW_SPEED));
        map.put(mc("polar_bear"), List.of(ICE_SPEED));
        map.put(mc("squid"), List.of(WATER_BREATHING));
        map.put(mc("glow_squid"), List.of(WATER_BREATHING));
        map.put(mc("dolphin"), List.of(SWIM_SPEED));
        map.put(mc("guardian"), List.of(WATER_BREATHING, SWIM_SPEED));
        map.put(mc("elder_guardian"), List.of(WATER_BREATHING, SWIM_SPEED));
        map.put(mc("bat"), List.of(NIGHT_VISION));
        map.put(mc("warden"), List.of(DARK_VISION));
        map.put(mc("iron_golem"), List.of(KNOCKBACK_RESIST));
        map.put(mc("llama"), List.of(KNOCKBACK_RESIST));
        map.put(mc("trader_llama"), List.of(KNOCKBACK_RESIST));
        map.put(mc("goat"), List.of(JUMP_BOOST));
        map.put(mc("rabbit"), List.of(JUMP_BOOST));
        map.put(mc("phantom"), List.of(SLOW_FALLING));
        map.put(mc("chicken"), List.of(SLOW_FALLING));
        map.put(mc("cat"), List.of(SILENT_WALK));
        map.put(mc("ocelot"), List.of(SILENT_WALK));
        map.put(mc("wither_skeleton"), List.of(WITHER_IMMUNITY));
        map.put(mc("axolotl"), List.of(REGENERATION));
        map.put(mc("shulker"), List.of(LEVITATION));
        map.put(mc("horse"), List.of(SPEED));
        map.put(mc("camel"), List.of(SPEED));
        map.put(mc("bee"), List.of(GLIDE));
        // 末影龙 / 凋灵刻意**不在表里** —— docs/02 表 B 明确"不可窃取"
        return Map.copyOf(map);
    }

    /** 供数据包校验与文档生成：全部内建特性 id。 */
    public static Set<ResourceLocation> allTraitIds() {
        final Set<ResourceLocation> out = new LinkedHashSet<>(ATTRIBUTE_TRAITS.keySet());
        out.addAll(EFFECT_TRAITS.keySet());
        out.addAll(IMMUNITY_TRAITS);
        out.addAll(TODO_E2_TRAITS);
        return Set.copyOf(out);
    }
}
