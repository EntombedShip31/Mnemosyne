package com.etbs31.mnemosyne.spell.mid;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.oblivion.AbilityMap;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.MnemosyneLongCastSpell;
import com.etbs31.mnemosyne.spell.base.MnemosyneSpell;
import com.etbs31.mnemosyne.util.RaycastHelper;
import com.etbs31.mnemosyne.util.SpellFeedback;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 帧缚 Framebind —— framebind（Rare / 长吟 1.5s）。
 *
 * <p><b>原型</b>：投影咒法 —— 把 1 秒切成 24 帧，施术者需在脑内预先描绘一条轨迹并在此 1 秒内
 * 执行完毕；被触碰者移动时同样必须遵守 24FPS，<b>违反即被冻结在某一帧内 1 秒</b>，
 * 该帧内毫无防备，击中帧可脱出但通常重伤。
 * 本质是<b>预演 / 脚本刻印</b>，是"记忆"母题里可玩性最强的一条。
 *
 * <p><b>机制</b>：吟唱期间记录你自己的移动（画出一串"帧"残影）。
 * 吟唱结束时，终点半径 {@link #APPLY_RADIUS} 内的敌人被强制"按 24 帧走路"：
 * 每 tick 位移超过 {@link #MAX_STEP} 格（≈ 疾跑上限）就触发冻结 1 秒。
 * 冻结期间<b>受到任意伤害即立即解除，但那一次伤害 ×1.5</b>。
 *
 * <p><b>⭐⭐ 双向二选一（这是全提案最漂亮的地方）</b>：
 * <br>进攻方要决定<b>打还是不打</b> —— 不打则敌人白站 1 秒；打则立刻解冻但它吃 1.5 倍。
 * <br>被冻结方也在<b>站着挨冻</b>和<b>骗对手打你</b>之间选。
 *
 * <p><b>⭐⭐ 为什么定身用"锁位置 + 清空速度"，而不是 {@code setNoAi}</b>
 * <br>{@code Mob.setNoAi(true)} 会连带关掉寻路、瞄准、受伤反应甚至部分渲染状态，
 * 而且一旦忘记复位就会留下永久呆滞的怪。这里只做两件可逆的小事。
 *
 * <p><b>⭐ 为什么不用 MobEffect 承载</b>
 * <br>与「铭刻适应」同理：项目红线是"效果只能是派生缓存"，牛奶桶能清空全部效果。
 * 这里的权威状态是 {@link #BOUND} 这张内存表，<b>不注册任何状态效果</b> —— 没有效果，牛奶无可清。
 *
 * <p><b>平衡限制（必须做，写在明处）</b>
 * <ul>
 *   <li>对<b>玩家</b>冻结时长减半（{@link #PLAYER_FREEZE_TICKS}），且同一目标
 *       {@link #FREEZE_COOLDOWN} 内只能被冻一次 —— 否则它封死了所有位移逃生，PvP 强度失控。</li>
 *   <li><b>BOSS 不冻结</b>：只吃一次短减速（红线：BOSS 免疫"剥夺"）。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class FramebindSpell extends MnemosyneLongCastSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "framebind");

    /** 各等级的持续（秒）。 */
    private static final int[] DURATION_SECONDS = {5, 6, 7, 8, 9};

    /** 各等级的违反应伤害。 */
    private static final double[] VIOLATION_DAMAGE = {6.0D, 7.5D, 9.0D, 10.5D, 12.0D};

    /**
     * 每 tick 允许的位移上限（格）。0.28 ≈ 疾跑速度（5.6 格/秒 ÷ 20）。
     *
     * <p>超过它 = 疾跑、跳跃冲刺、鞘翅、末影珍珠、瞬移，全部都会触发。
     */
    private static final double MAX_STEP = 0.28D;

    /** 吟唱结束时，以施法者为中心的生效半径。 */
    private static final double APPLY_RADIUS = 4.0D;

    /** 冻结时长（tick）。原作是 1 秒。 */
    private static final int FREEZE_TICKS = 20;

    /** 对玩家目标冻结减半 —— PvP 平衡。 */
    private static final int PLAYER_FREEZE_TICKS = 10;

    /** 同一目标两次冻结之间的最小间隔（tick）。 */
    private static final int FREEZE_COOLDOWN = 200;

    /** 冻结中被打时的伤害倍率（原作"击中帧可脱出但通常重伤"）。 */
    private static final double BREAK_MULTIPLIER = 1.5D;

    /** BOSS 违规时挂的减速（−50%）与时长。 */
    private static final double BOSS_SLOW = -0.5D;

    private static final int BOSS_SLOW_TICKS = 40;

    private static final UUID BOSS_SLOW_ID =
            UUID.nameUUIDFromBytes("mnemosyne:framebind_boss".getBytes(StandardCharsets.UTF_8));

    /** 吟唱期画"帧"残影的间隔（tick）。 */
    private static final int GHOST_INTERVAL = 3;

    /** 被帧缚的实体。键 = 目标 UUID。 */
    private static final Map<UUID, Bound> BOUND = new ConcurrentHashMap<>();

    /** 一段帧缚状态。 */
    private static final class Bound {
        private final UUID caster;
        private final long expireTick;
        private final int spellLevel;
        private final double damage;
        private final boolean player;
        /** 冻结到哪一刻；0 = 没在冻结。 */
        private long freezeUntil;
        private long lastFreezeAt;
        private Vec3 lastPos;
        private long lastTick;
        private Vec3 frozenAt;

        private Bound(final UUID caster, final long expireTick, final int spellLevel,
                      final double damage, final boolean player, final Vec3 pos, final long now) {
            this.caster = caster;
            this.expireTick = expireTick;
            this.spellLevel = spellLevel;
            this.damage = damage;
            this.player = player;
            this.freezeUntil = 0L;
            this.lastFreezeAt = Long.MIN_VALUE;
            this.lastPos = pos;
            this.lastTick = now;
            this.frozenAt = pos;
        }
    }

    public FramebindSpell() {
        super(memoryConfig(SpellRarity.RARE, 20.0D, 5));
        this.baseManaCost = 50;
        this.manaCostPerLevel = 10;
        this.baseSpellPower = 1;
        this.spellPowerPerLevel = 0;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return SPELL_ID;
    }

    /** 长吟 1.5 秒（30 tick）。 */
    @Override
    protected int defaultCastTime() {
        return 30;
    }

    /** 施法音效：{@code spell.framebind.cast}。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_FRAMEBIND_CAST.get());
    }

    /**
     * 吟唱中：沿自己的移动画出一串"帧"残影。
     *
     * <p>⭐ 这正是投影咒法的意象 —— <b>先在脑内（世界里）把轨迹描绘出来</b>，再让敌人照着走。
     */
    @Override
    protected void onLongCastTick(final ServerPlayer player, final int spellLevel,
                                  final float progress, final MagicData magicData) {
        final long now = player.getServer().overworld().getGameTime();
        if (now % GHOST_INTERVAL != 0) {
            return;
        }
        if (player.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.END_ROD,
                    player.getX(), player.getY() + 0.4D, player.getZ(),
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    @Override
    protected void onLongCastFinish(final ServerPlayer player, final int spellLevel) {
        final int index = clampLevelIndex(spellLevel);
        final long now = player.getServer().overworld().getGameTime();
        final long expire = now + (long) DURATION_SECONDS[index] * 20L;
        SpellFeedback.areaBurst(player.level(), SpellFeedback.chest(player),
                APPLY_RADIUS, SpellFeedback.MEMORY_INDIGO);
        for (final LivingEntity target : RaycastHelper.findLivingInSphere(
                player.level(), player.position(), APPLY_RADIUS, player, true)) {
            BOUND.put(target.getUUID(), new Bound(player.getUUID(), expire, spellLevel,
                    VIOLATION_DAMAGE[index], target instanceof ServerPlayer,
                    target.position(), now));
        }
    }

    // ==================================================================
    // 每 tick 判定
    // ==================================================================

    @SubscribeEvent
    public static void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || BOUND.isEmpty()) {
            return;
        }
        final long now = event.getServer().overworld().getGameTime();
        for (final Iterator<Map.Entry<UUID, Bound>> it = BOUND.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, Bound> entry = it.next();
            final Bound bound = entry.getValue();
            final Entity entity = event.getServer().overworld().getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity target) || !target.isAlive()
                    || now >= bound.expireTick) {
                it.remove();
                continue;
            }
            if (now < bound.freezeUntil) {
                // 冻结中：锁回被冻住的那一刻，清空速度
                target.setPos(bound.frozenAt.x, bound.frozenAt.y, bound.frozenAt.z);
                target.setDeltaMovement(Vec3.ZERO);
                if (now % 5 == 0) {
                    SpellFeedback.dazeAura(target);
                }
                continue;
            }
            final long elapsed = now - bound.lastTick;
            if (elapsed <= 0) {
                continue;
            }
            final double moved = target.position().distanceTo(bound.lastPos);
            final double perTick = moved / elapsed;
            bound.lastPos = target.position();
            bound.lastTick = now;
            if (perTick <= MAX_STEP) {
                continue;
            }
            // ---- 违反：要么冻结，要么（BOSS）只减速 ----
            if (AbilityMap.isBoss(target.getType())) {
                slowBoss(target);
                continue;
            }
            if (now - bound.lastFreezeAt < FREEZE_COOLDOWN) {
                continue;
            }
            bound.lastFreezeAt = now;
            bound.frozenAt = target.position();
            bound.freezeUntil = now + (bound.player ? PLAYER_FREEZE_TICKS : FREEZE_TICKS);
            target.setDeltaMovement(Vec3.ZERO);
            dealViolationDamage(target, bound);
            SpellFeedback.hitBurst(target.level(), target, SpellFeedback.MEMORY_MAGENTA);
        }
    }

    /** 违反规则的伤害走标准学派伤害源（会被抗性 / 事件正确处理）。 */
    private static void dealViolationDamage(final LivingEntity target, final Bound bound) {
        final AbstractSpell self = SpellRegistry.getSpell(SPELL_ID.toString());
        if (self == null || !(target.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        final Entity caster = serverLevel.getEntity(bound.caster);
        if (!(caster instanceof LivingEntity source)) {
            return;
        }
        MnemosyneSpell.dealSpellDamage(target, source, source, self, (float) bound.damage);
    }

    private static void slowBoss(final LivingEntity boss) {
        final AttributeInstance attribute = boss.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute == null) {
            return;
        }
        attribute.removeModifier(BOSS_SLOW_ID);
        attribute.addTransientModifier(new AttributeModifier(BOSS_SLOW_ID,
                "mnemosyne_framebind_boss", BOSS_SLOW, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    /**
     * 冻结中被打：立刻解冻，但那一下 ×1.5。
     *
     * <p>⭐ 这就是"打还是不打"的另一半 —— 它让被冻住的玩家也有了反制手段
     * （主动挨一下重击换回自由）。
     */
    @SubscribeEvent
    public static void onLivingHurt(final LivingHurtEvent event) {
        final Bound bound = BOUND.get(event.getEntity().getUUID());
        if (bound == null || bound.freezeUntil <= 0) {
            return;
        }
        final long now = event.getEntity().level().getServer().overworld().getGameTime();
        if (now >= bound.freezeUntil) {
            return;
        }
        bound.freezeUntil = 0L;
        event.setAmount(event.getAmount() * (float) BREAK_MULTIPLIER);
    }

    private static int clampLevelIndex(final int spellLevel) {
        return Math.max(1, Math.min(DURATION_SECONDS.length, spellLevel)) - 1;
    }
}
