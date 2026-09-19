package com.etbs31.mnemosyne.spell.low;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.capability.MnemosyneData;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.MnemosyneSpell;
import com.etbs31.mnemosyne.spell.low.MemoryArrowSpell;
import com.etbs31.mnemosyne.util.SpellFeedback;
import io.redspace.ironsspellbooks.api.events.ChangeManaEvent;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 献忆 Oblation —— oblation（Uncommon / 瞬发）。
 *
 * <p><b>原型</b>：重面春太的「奇迹」—— 日常的小奇迹被<b>从记忆中抹除</b>后储存起来，
 * 濒死时释放以改写运气。这是原作里唯一一条<b>明码标价</b>的"以记忆换资源"机制。
 *
 * <p><b>机制</b>：烧掉 1 个忆格（优先临时格），换一段<b>免法窗口</b>
 * —— 窗口内所有法力消耗归零，且施法速度提高。
 * 代价是自身叠加 <b>2 层认知过载</b>（持续 20 秒）。
 *
 * <p><b>⭐⭐ 为什么收益是"免法窗口"而不是回血</b>：
 * 回血会直接撞上 ISS 圣 / 自然学派的治疗法术，违反准入准则 ⑤（不能与其他学派功能重叠）。
 * 而"一段时间里施法不要钱"是其他九大学派<b>给不了</b>的东西 ——
 * 它是记忆流派独占的收益形态，且天然要求玩家先囤够忆格，与流派核心一致。
 *
 * <p><b>⭐⭐ "法力归零"是怎么实现的（这里是本项目唯一一处改 ISS 的耗蓝路径）</b>
 * <br>两条互补的钩子，都实测过：
 * <ol>
 *   <li>{@link #onSpellCast}：{@code SpellOnCastEvent.setManaCost(0)}。
 *       这是 ISS 官方给的法术级入口，语义最正。</li>
 *   <li>{@link #onManaChange}：{@code ChangeManaEvent}，只要<b>法力下降</b>就把新值按回旧值。
 *       ⭐ 这一条是保险 —— 实测 {@code MagicData.setMana} 内部<b>一定会</b> post 本事件
 *       （ javap 反编译确认），所以无论 ISS 是先扣费再触发 {@code SpellOnCastEvent}
 *       还是反过来，下降的那一次都能被拦住。</li>
 * </ol>
 * 两条同时挂不会重复退费：走 (1) 时根本没有下降，(2) 自然不触发。
 *
 * <p><b>⭐ 施法速度加成走 ISS 的 {@code CAST_TIME_REDUCTION} 属性</b>
 * <br>它的语义（见 {@code EngramEffect}）是：属性值 {@code = 2 − 1/penalty}，
 * 其中 penalty 是施法速度倍率。所以"速度 ×s"对应的 {@code MULTIPLY_TOTAL} 修饰符值是
 * {@code 1 − 1/s}（s=1.15 → +0.130）。⚠️ 直接写 +0.15 是错的 —— 那是线性直觉，
 * 而这个属性是非线性的。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class OblationSpell extends MnemosyneSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "oblation");

    /** 各等级的免法窗口时长（秒）：10/11/13/14（docs/tech/13_数值总表.md §献忆）。 */
    private static final int[] WINDOW_SECONDS = {10, 11, 13, 14};

    /** 各等级的施法速度倍率（1.150 = +15%）：1.150 → 1.270（docs/tech/13_数值总表.md §献忆）。 */
    private static final double[] CAST_SPEED = {1.150D, 1.190D, 1.230D, 1.270D};

    /** 反噬：自身叠加的认知过载层数。 */
    private static final int BACKLASH_STACKS = 2;

    /** 反噬持续（秒）。 */
    private static final int BACKLASH_SECONDS = 20;

    /** 认知过载层数上限（与流派基准一致）。 */
    private static final int OVERLOAD_CAP = 5;

    /** 施法速度修饰符的固定 UUID：固定值 → 重复施加是替换而不是叠加。 */
    private static final UUID CAST_BONUS_ID =
            UUID.nameUUIDFromBytes("mnemosyne:oblation_cast_speed".getBytes(StandardCharsets.UTF_8));

    /** 正在生效的免法窗口。键 = 玩家 UUID。 */
    private static final Map<UUID, Window> WINDOWS = new ConcurrentHashMap<>();

    /** 一个免法窗口。 */
    private static final class Window {
        private final long expireTick;
        private final double castSpeed;

        private Window(final long expireTick, final double castSpeed) {
            this.expireTick = expireTick;
            this.castSpeed = castSpeed;
        }
    }

    public OblationSpell() {
        super(memoryConfig(SpellRarity.UNCOMMON, 25.0D, 4));
        this.baseManaCost = 34;
        this.manaCostPerLevel = 7;
        this.baseSpellPower = 1;
        this.spellPowerPerLevel = 0;
        this.castTime = 0;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return SPELL_ID;
    }

    @Override
    public CastType getCastType() {
        return CastType.INSTANT;
    }

    /** 施法音效复用「忆格扩张」—— 两者都是"动用忆格"的操作，意象一致。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_EXPAND_MIND_CAST.get());
    }

    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide && entity instanceof ServerPlayer caster) {
            if (!burn(caster, spellLevel, playerMagicData)) {
                // ⚠️ ISS 在调用 onCast **之前**就已经扣了法力，所以失败必须自己退回去，
                //    否则"没忆格时放献忆"会白白吃掉 20 点法力 —— 这是最容易挨骂的一类 bug。
                playerMagicData.addMana(getManaCost(spellLevel));
                SpellFeedback.noFreeSlot(caster);
                super.onCast(level, spellLevel, entity, castSource, playerMagicData);
                return;
            }
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }

    /**
     * 烧掉 1 个忆格并开启窗口。
     *
     * @return 是否成功（没有忆格时 false）
     */
    private static boolean burn(final ServerPlayer caster, final int spellLevel,
                                final MagicData playerMagicData) {
        if (MnemosyneData.getUsedEngrams(caster) <= 0) {
            return false;
        }
        final int index = clampLevelIndex(spellLevel);
        // ⭐ releaseEngram 的索引语义：先找临时格（slots），再找永久格（permSlots）。
        //    所以传 0 天然就是"**优先烧临时格**"，不需要我们自己判断哪一格是临时的。
        MnemosyneData.releaseEngram(caster, 0);

        final long now = caster.getServer().overworld().getGameTime();
        final long expire = now + (long) WINDOW_SECONDS[index] * 20L;
        WINDOWS.put(caster.getUUID(), new Window(expire, CAST_SPEED[index]));
        applyCastBonus(caster, CAST_SPEED[index]);

        // 反噬：自身叠 2 层认知过载。
        // ⭐ 这是"代价"而不是装饰 —— 过载 ≥3 层时写入类法术写入的内容会缩水，
        //    所以献忆之后 20 秒内不适合再写入。玩家要么先囤够再献，要么献完就打。
        for (int i = 0; i < BACKLASH_STACKS; i++) {
            MemoryArrowSpell.stackCognitiveOverload(caster, OVERLOAD_CAP);
        }

        // ⭐ 特效：胸口一圈品红环**向内收拢消失**（与获得忆格时的向外扩散相反 = "少了一格"），
        //    随后一层淡金薄光罩住全身，表示免法窗口生效。
        SpellFeedback.areaBurst(caster.level(), SpellFeedback.chest(caster),
                1.0D, SpellFeedback.MEMORY_MAGENTA);
        SpellFeedback.engramGain(caster.level(), caster, 1);
        SpellFeedback.actionBar(caster, Component.translatable("mnemosyne.oblation.window",
                        WINDOW_SECONDS[index])
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return true;
    }

    /** 施法速度加成：{@code 1 − 1/s}（ISS 的 CAST_TIME_REDUCTION 是非线性的，见类注释）。 */
    private static void applyCastBonus(final ServerPlayer caster, final double speed) {
        if (!AttributeRegistry.CAST_TIME_REDUCTION.isPresent()) {
            return;
        }
        final AttributeInstance attribute = caster.getAttribute(AttributeRegistry.CAST_TIME_REDUCTION.get());
        if (attribute == null) {
            return;
        }
        attribute.removeModifier(CAST_BONUS_ID);
        attribute.addTransientModifier(new AttributeModifier(CAST_BONUS_ID,
                "mnemosyne_oblation_cast_speed", 1.0D - 1.0D / speed,
                AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    // ==================================================================
    // 免法窗口的两条钩子
    // ==================================================================

    /** 法术级入口：把这一次的法力消耗改成 0。 */
    @SubscribeEvent
    public static void onSpellCast(final SpellOnCastEvent event) {
        if (active(event.getEntity().getUUID())) {
            event.setManaCost(0);
        }
    }

    /**
     * 兜底入口：任何一次<b>法力下降</b>都被按回去。
     *
     * <p>实测 {@code MagicData.setMana} 内部一定会 post 本事件，所以这条不依赖
     * ISS 的扣费顺序。只拦下降 —— 法力自然回复（上升）不受影响。
     */
    @SubscribeEvent
    public static void onManaChange(final ChangeManaEvent event) {
        if (!active(event.getEntity().getUUID())) {
            return;
        }
        if (event.getNewMana() < event.getOldMana()) {
            event.setNewMana(event.getOldMana());
        }
    }

    private static boolean active(final UUID id) {
        final Window window = WINDOWS.get(id);
        return window != null
                && net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer()
                        .overworld().getGameTime() < window.expireTick;
    }

    /** 到期清理：移除施法速度修饰符。⚠️ 不移除会永久留在玩家身上。 */
    @SubscribeEvent
    public static void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || WINDOWS.isEmpty()) {
            return;
        }
        final long now = event.getServer().overworld().getGameTime();
        for (final Iterator<Map.Entry<UUID, Window>> it = WINDOWS.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, Window> entry = it.next();
            final Window window = entry.getValue();
            if (now < window.expireTick) {
                continue;
            }
            it.remove();
            final ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null && AttributeRegistry.CAST_TIME_REDUCTION.isPresent()) {
                final AttributeInstance attribute =
                        player.getAttribute(AttributeRegistry.CAST_TIME_REDUCTION.get());
                if (attribute != null) {
                    attribute.removeModifier(CAST_BONUS_ID);
                }
            }
        }
    }

    private static int clampLevelIndex(final int spellLevel) {
        return Math.max(1, Math.min(WINDOW_SECONDS.length, spellLevel)) - 1;
    }
}
