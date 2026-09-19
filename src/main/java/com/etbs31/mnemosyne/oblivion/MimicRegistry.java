package com.etbs31.mnemosyne.oblivion;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表 C · 可复现的技能（走马灯）—— "目标最近用过的招，我也能用一次"。
 *
 * <p><b>文件归属</b>：WS-E 遗忘系统（{@code docs/tech/10} §一 把 {@code oblivion/*.java}
 * 整体划给 WS-E；{@code docs/tech/00} 的文件树也把本类列在 {@code oblivion/} 下）。
 * <b>消费方是 WS-D2 的「走马灯」与 WS-D3 的「记忆掠夺」</b>，本类只负责表与查询。
 *
 * <p><b>为什么单独一个类而不是塞进 {@link AbilityMap}</b>：三张表语义不同 ——
 * 表 A 是"能摘掉什么"（goal），表 B 是"能借到什么"（特性），
 * 表 C 是"能复现成什么法术"。混在一起会让 WS-D 的调用方读不懂。
 *
 * <p><b>⭐ 表 C 的值必须是真的铁魔法法术 id</b>
 * <br>{@code docs/02} §四 表 C 规定"复现的技能**以铁魔法法术的形式执行**"，
 * 所以映射目标不是自造 id，而是 {@code irons_spellbooks:*} 里真实存在的法术。
 * 本表的 id 全部从 ISS jar 的 {@code assets/irons_spellbooks/lang/en_us.json}
 * 里 {@code spell.irons_spellbooks.<id>} 键**逐个核对过**（2026-09-17 实测），
 * 不是照文档猜的 —— 猜错会静默失效（法术解析不出来 → 玩家花 50 法力放了个空气）。
 *
 * <p><b>⚠️ 本表"逐条落地"时做的取舍</b>（三处，均在下方常量处标注）：
 * <ul>
 *   <li>幻术师的"召唤分身"在 ISS 里**没有对应法术**（没有 mirror_image）→ 退化为
 *       {@code invisibility}，语义最接近（都是"让敌人打不到我"）。</li>
 *   <li>守卫者的"激光射线"→ {@code ray_of_frost}（ISS 唯一的持续射线）。</li>
 *   <li>岩浆怪的"高跳"→ {@code ascension}（ISS 唯一的垂直位移）。</li>
 * </ul>
 *
 * <p><b>⚠️ 明确不在本类范围内</b>（避免做出半成品，见 {@code docs/tech/03} §7.3 的教训）：
 * <br>"目标必须在最近 N 秒内**使用过**该技能"这个判定需要**逐生物的能力观测器**
 * （烈焰人喷火、骷髅射箭、监守者放声波各有各的触发点），只有「走马灯」法术的
 * 设计能定义清楚该挂在哪里。因此本类**只提供静态表**，
 * 时间窗口与"最近用过"的记录由 WS-D2 的 {@code RecollectionSpell} 自行实现。
 */
public final class MimicRegistry {

    private MimicRegistry() {}

    // ==================================================================
    // ISS 法术 id（全部经 jar 内 lang 键核对）
    // ==================================================================

    /** 烈焰人 · 小火球。 */
    public static final ResourceLocation FIREBOLT = iss("firebolt");
    /** 恶魂 · 大火球。 */
    public static final ResourceLocation FIREBALL = iss("fireball");
    /** 末影人 · 瞬移（16 格）。 */
    public static final ResourceLocation TELEPORT = iss("teleport");
    /** 女巫 · 投掷药水。 */
    public static final ResourceLocation POISON_SPLASH = iss("poison_splash");
    /** 骷髅 · 箭矢。 */
    public static final ResourceLocation MAGIC_ARROW = iss("magic_arrow");
    /** 潜影贝 · 追踪弹。 */
    public static final ResourceLocation MAGIC_MISSILE = iss("magic_missile");
    /** 唤魔者 · 尖牙陷阱。 */
    public static final ResourceLocation FANG_STRIKE = iss("fang_strike");
    /** 幻术师 · 召唤分身 → 退化用隐身（ISS 无 mirror_image）。 */
    public static final ResourceLocation INVISIBILITY = iss("invisibility");
    /** 守卫者 · 激光射线。 */
    public static final ResourceLocation RAY_OF_FROST = iss("ray_of_frost");
    /** 雪傀儡 · 雪球（击退）。 */
    public static final ResourceLocation SNOWBALL = iss("snowball");
    /** 岩浆怪 · 高跳。 */
    public static final ResourceLocation ASCENSION = iss("ascension");
    /** 幻翼 / 疣猪兽 / 山羊 · 冲刺。 */
    public static final ResourceLocation CHARGE = iss("charge");
    /** 溺尸 · 三叉戟投掷。 */
    public static final ResourceLocation THROW = iss("throw");
    /** 掠夺者 / 猪灵 · 弩箭。 */
    public static final ResourceLocation ARROW_VOLLEY = iss("arrow_volley");
    /** 监守者 · 声波冲击 —— {@code docs/02} §四 明写"这是最强的复现技能"。 */
    public static final ResourceLocation SONIC_BOOM = iss("sonic_boom");

    // ==================================================================
    // 降级常量（docs/tech/03 §7.4 第 5 条 / docs/02 §六）
    // ==================================================================

    /**
     * 「走马灯」复现失败时返还的法力比例 —— {@code docs/tech/03} §7.4 表格第 5 行、
     * {@code docs/02} §六 均写"复现失败，返还 50% 法力"。
     *
     * <p>放在这里而不是让法术自己写 {@code 0.5F}：数值属于映射表文档，
     * 整合包作者改不了代码但应该能一眼找到这个数字。
     * 实际退款动作（{@code MagicData.addMana}）由法术执行 —— 那是 ISS 的 API，不属于本类职责。
     */
    public static final float MANA_REFUND_FRACTION = 0.5F;

    // ==================================================================
    // 表 C（内建 + 数据包覆盖）
    // ==================================================================

    /** 数据包文件名 → {@code mnemosyne:mimics}（⚠️ 是**我们的**命名空间，不是 ISS 的）。 */
    static final ResourceLocation FILE_MIMICS = ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "mimics");

    private static final Map<ResourceLocation, List<ResourceLocation>> BUILT_IN = buildBuiltIn();
    private static volatile Map<ResourceLocation, List<ResourceLocation>> declared = BUILT_IN;

    /** 由 {@link OblivionManager} 的重载监听器调用。 */
    static void acceptDataPack(final Map<ResourceLocation, JsonElement> files) {
        final JsonElement element = files.get(FILE_MIMICS);
        if (element == null || !element.isJsonObject()) {
            declared = BUILT_IN;
            return;
        }
        final JsonObject root = element.getAsJsonObject();
        final Map<ResourceLocation, List<ResourceLocation>> out = new LinkedHashMap<>();
        if (!root.has("replace") || !root.get("replace").getAsBoolean()) {
            out.putAll(BUILT_IN);
        }
        final JsonElement tableElement = root.get("mimics");
        if (tableElement == null || !tableElement.isJsonObject()) {
            MnemosyneMod.LOGGER.warn("[WS-E] mimics.json 缺少 mimics 对象，沿用内建表 C");
            declared = Map.copyOf(out);
            return;
        }
        for (final Map.Entry<String, JsonElement> entry : tableElement.getAsJsonObject().entrySet()) {
            final ResourceLocation mob = ResourceLocation.tryParse(entry.getKey());
            if (mob == null || !entry.getValue().isJsonArray()) {
                MnemosyneMod.LOGGER.warn("[WS-E] mimics.json 忽略非法条目：{}", entry.getKey());
                continue;
            }
            final JsonArray array = entry.getValue().getAsJsonArray();
            final List<ResourceLocation> list = new ArrayList<>(array.size());
            for (final JsonElement item : array) {
                final ResourceLocation parsed = ResourceLocation.tryParse(item.getAsString());
                if (parsed == null) {
                    MnemosyneMod.LOGGER.warn("[WS-E] mimics.json 忽略非法法术 id：{}", item.getAsString());
                    continue;
                }
                list.add(parsed);
            }
            out.put(mob, List.copyOf(list));
        }
        MnemosyneMod.LOGGER.info("[WS-E] 表 C 已加载：{} 个生物映射", out.size());
        declared = Map.copyOf(out);
    }

    // ==================================================================
    // 查询 API
    // ==================================================================

    /**
     * 表 C：该生物可被复现的法术清单（数据包可覆盖）。
     *
     * @return 不可复现（含末影龙 / 凋灵 / 玩家 / 其他模组生物）返回空列表
     */
    public static List<ResourceLocation> getMimicSpells(final EntityType<?> type) {
        if (type == null) {
            return List.of();
        }
        return declared.getOrDefault(EntityType.getKey(type), List.of());
    }

    /** 该生物是否有任何可复现的技能。 */
    public static boolean canMimic(final EntityType<?> type) {
        return !getMimicSpells(type).isEmpty();
    }

    /**
     * 把表 C 里的 id 解析成法术实例。
     *
     * <p>解析失败（换了 ISS 版本、整合包剔除了该法术）返回 {@code null} ——
     * 调用方应当按"复现失败"处理并走 {@link #MANA_REFUND_FRACTION} 退款，
     * **不要抛异常**（一个改坏的整合包不该让玩家放不出法术）。
     */
    @Nullable
    public static AbstractSpell resolve(final ResourceLocation spellId) {
        if (spellId == null) {
            return null;
        }
        return SpellRegistry.getSpell(spellId.toString());
    }

    /** 供数据包校验与文档生成：全部内建可复现法术 id。 */
    public static Set<ResourceLocation> allSpellIds() {
        final Set<ResourceLocation> out = new LinkedHashSet<>();
        for (final List<ResourceLocation> list : BUILT_IN.values()) {
            out.addAll(list);
        }
        return Set.copyOf(out);
    }

    /** 供数据包校验：全部内建映射的生物数。 */
    public static int builtInMobCount() {
        return BUILT_IN.size();
    }

    // ==================================================================
    // 内建表（docs/02 §四 表 C 逐条）
    // ==================================================================

    private static Map<ResourceLocation, List<ResourceLocation>> buildBuiltIn() {
        final Map<ResourceLocation, List<ResourceLocation>> map = new LinkedHashMap<>();
        map.put(mc("blaze"), List.of(FIREBOLT));
        map.put(mc("ghast"), List.of(FIREBALL));
        map.put(mc("enderman"), List.of(TELEPORT));
        map.put(mc("witch"), List.of(POISON_SPLASH));
        map.put(mc("skeleton"), List.of(MAGIC_ARROW));
        map.put(mc("stray"), List.of(MAGIC_ARROW));
        map.put(mc("shulker"), List.of(MAGIC_MISSILE));
        map.put(mc("evoker"), List.of(FANG_STRIKE));
        map.put(mc("illusioner"), List.of(INVISIBILITY));
        map.put(mc("guardian"), List.of(RAY_OF_FROST));
        map.put(mc("elder_guardian"), List.of(RAY_OF_FROST));
        map.put(mc("snow_golem"), List.of(SNOWBALL));
        map.put(mc("magma_cube"), List.of(ASCENSION));
        map.put(mc("phantom"), List.of(CHARGE));
        map.put(mc("drowned"), List.of(THROW));
        map.put(mc("pillager"), List.of(ARROW_VOLLEY));
        map.put(mc("piglin"), List.of(ARROW_VOLLEY));
        map.put(mc("hoglin"), List.of(CHARGE));
        map.put(mc("goat"), List.of(CHARGE));
        map.put(mc("warden"), List.of(SONIC_BOOM));
        // 末影龙 / 凋灵刻意**不在表里** —— docs/02 §四 表 C 明写"不可复现"，
        // 理由是避免玩家复现凋灵之首。这条是设计红线，不要"顺手补上"。
        return Map.copyOf(map);
    }

    private static ResourceLocation iss(final String path) {
        return ResourceLocation.fromNamespaceAndPath("irons_spellbooks", path);
    }

    private static ResourceLocation mc(final String path) {
        return ResourceLocation.fromNamespaceAndPath("minecraft", path);
    }
}
