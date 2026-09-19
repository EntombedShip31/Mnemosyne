package com.etbs31.mnemosyne.spell.high;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.oblivion.AbilityMap;
import com.etbs31.mnemosyne.registry.ModEffects;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.base.MnemosyneSpell;
import com.etbs31.mnemosyne.spell.low.MemoryArrowSpell;
import com.etbs31.mnemosyne.util.RaycastHelper;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 无尽忆域 Endless Realm —— endless_realm。
 *
 * <p><b>原型</b>：五条悟「无量空处」—— 无止境地灌入信息，使对象无法动弹。
 * 名字改成记忆相关的「无尽忆域」，<b>素材直接沿用「忆海」</b>（音效与粒子不新增文件），
 * 只把配色方向反过来：忆海是靛蓝 → 品红<b>向外发散</b>（记忆涌出去），
 * 无尽忆域是白 → 靛蓝<b>向内收敛</b>（信息灌进来）。
 *
 * <p><b>⭐⭐ 核心：它是一片「封闭区域」，不是一片「有范围的 buff」</b>
 * <ol>
 *   <li><b>封闭</b>：领域是一整个球体（含穹顶，不只是地面圆圈）。被卷进去的实体
 *       <b>出不去</b> —— 每 tick 会把越界的实体拉回边界内。</li>
 *   <li><b>无法行动</b>：领域内的敌人移动速度归零（{@code ENDLESS_BIND} 效果的属性修饰符），
 *       并且<b>攻击会被直接取消</b>（{@link #onLivingAttack}）。BOSS 走降级路径：
 *       只减速 50%，不会被定住。</li>
 *   <li><b>零伤害</b>：本法术<b>不造成任何直接伤害</b>。它的全部价值是"把战场按停 6~10 秒"，
 *       符合"记忆流派不做最高伤害"的红线。</li>
 *   <li><b>代价</b>：施法者在领域期间<b>同样无法移动</b>。</li>
 * </ol>
 *
 * <p><b>与「忆海」的区别</b>（两个都是 Legendary 领域，必须区分清楚）：
 * <br>忆海 = 自增益（忆格 +3 / 共鸣）+ 叠层 + <b>结束时结算伤害</b>；
 * <br>无尽忆域 = <b>纯控制、零伤害</b>，时长更短（6~10s vs 忆海 15~30s）。
 *
 * <table border="1">
 *   <tr><th>等级</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th>
 *       <th>6</th><th>7</th><th>8</th><th>9</th><th>10</th></tr>
 *   <tr><td>半径</td><td>10.0</td><td>10.4</td><td>10.9</td><td>11.3</td><td>11.8</td>
 *       <td>12.2</td><td>12.7</td><td>13.1</td><td>13.6</td><td>14.0</td></tr>
 *   <tr><td>持续</td><td>6s</td><td>6s</td><td>7s</td><td>7s</td><td>8s</td>
 *       <td>8s</td><td>9s</td><td>9s</td><td>10s</td><td>10s</td></tr>
 * </table>
 *
 * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「无尽忆域」（唯一事实来源，最大等级 10）。
 *
 * <p><b>⭐⭐ 为什么"定身"用属性修饰符 + 事件取消，而不是 {@code setNoAi}</b>：
 * {@code Mob.setNoAi(true)} 会连带关掉一大票原版逻辑（寻路、瞄准、受伤反应、甚至部分
 * 渲染状态），而且一旦忘记复位就会留下永久呆滞的怪。这里改成两件各自可逆的小事：
 * 移动速度 ×0（属性，效果移除即恢复）+ 攻击事件取消（只在本领域生效）。
 *
 * <p><b>⭐⭐ 效果的施加策略：每 tick 重刷，而不是一次给长时间</b>
 * <br>项目红线：效果只能是<b>派生缓存</b>（牛奶桶 / 净化会清空全部效果）。
 * 如果开领域时给一段 10 秒的效果，敌人喝桶牛奶就出去了。
 * 所以这里每 tick 重刷一个短效果（{@link #BIND_REFRESH_TICKS} = 40 tick）：
 * 牛奶能顶掉<b>一次</b>，下一 tick 立刻补回来；而领域结束时显式移除，
 * 即使补刷逻辑出意外，40 tick 后也会自己过期 —— 不会留下被永久定住的生物。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class EndlessRealmSpell extends MnemosyneSpell {

    private static final ResourceLocation SPELL_ID =
            ResourceLocation.fromNamespaceAndPath(MnemosyneMod.MODID, "endless_realm");

    /**
     * 各等级的领域半径（格，index = level − 1）。
     *
     * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「无尽忆域」的"半径（格）"列：
     * L1~L10 = <b>10.0 / 10.4 / 10.9 / 11.3 / 11.8 / 12.2 / 12.7 / 13.1 / 13.6 / 14.0</b>。
     */
    private static final double[] RADIUS = {10.0D, 10.4D, 10.9D, 11.3D, 11.8D, 12.2D, 12.7D, 13.1D, 13.6D, 14.0D};

    /**
     * 各等级的持续时间（秒，index = level − 1）。
     *
     * <p><b>数值来源</b>：{@code docs/tech/13_数值总表.md} §二「无尽忆域」的"持续（秒）"列：
     * L1~L10 = <b>6 / 6 / 7 / 7 / 8 / 8 / 9 / 9 / 10 / 10</b>。
     * 刻意做得比忆海（15~30s）短很多 —— 这是完全控制，不是增益场。
     */
    private static final int[] DURATION_SECONDS = {6, 6, 7, 7, 8, 8, 9, 9, 10, 10};

    /** 认知过载的层数上限。领域最长 10 秒 = 最多 10 层，钳到 5 与流派基准一致。 */
    private static final int STACK_CAP = 5;

    /** 每秒叠 1 层认知过载。 */
    private static final int STACK_INTERVAL_TICKS = 20;

    /** 定身效果的刷新时长（tick）。短时长的意义见类注释 —— 它是"抗牛奶"与"不留永久定身"的交点。 */
    private static final int BIND_REFRESH_TICKS = 40;

    /**
     * 「封闭 + 定身」的执行间隔（tick）。
     *
     * <p>⭐ 这是本法术最重要的一处性能优化。{@link #hold} 要做一次
     * {@code getEntitiesOfClass} 的实体查询 —— 如果每 tick 都做，
     * 一片领域就是 **20 次/秒**的范围查询，多片领域线性叠加。
     *
     * <p>而实际上完全不需要这么频繁：定身效果的时长是 {@link #BIND_REFRESH_TICKS} = 40 tick，
     * 每 10 tick 补一次仍有 4 倍余量。唯一的代价是"敌人喝牛奶后最多自由 10 tick（0.5 秒）"
     * —— 对一片持续 6~10 秒的领域来说，这个窗口小到不影响体感。
     *
     * <p>于是实体查询从 20 次/秒降到 <b>2 次/秒</b>。
     */
    private static final int HOLD_INTERVAL = 10;

    /** BOSS 降级：移动速度 −50%。BOSS 免疫"剥夺"，只吃数值削弱。 */
    private static final double BOSS_SLOW = -0.50D;

    /** BOSS 减速修饰符的固定 UUID：固定值 → 重复施加是替换而不是叠加。 */
    private static final UUID BOSS_SLOW_ID =
            UUID.nameUUIDFromBytes("mnemosyne:endless_realm_boss".getBytes(StandardCharsets.UTF_8));

    // ==================================================================
    // 特效节奏（⭐ 全部按"能合并就合并、能少发就少发"的原则定）
    // ==================================================================
    //
    // 粒子的开销几乎全在**发包次数**上：ServerLevel.sendParticles 的 9 参版
    // 一次调用 = 一个 ClientboundLevelParticlesPacket。而 `count` 参数只能给出
    // 实心圆盘，画不出"穹顶 / 环"这种形状 —— 所以定形的部分必须**逐点铺位置**。
    // 于是唯一的优化手段就是：拉长间隔 + 减少点数，靠粒子自身的存活期补齐视觉连续性。
    //
    // ① 穹顶：2 圈纬度 × 12 点，每 10 tick 一次  → 2.4 包/tick
    // ② 向内收敛环：12 点，每 6 tick 一次        → 2.0 包/tick
    // ③ 内部飘尘：一次发包带十几个粒子            → 0.25 包/tick
    // 合计约 4.6 包/tick，10 秒领域约 920 个包 —— 与「忆海」(1.8 包/tick、30 秒约 1100 包)
    // 同量级，对传说级大招是可接受的。

    /** 穹顶的发包间隔（tick）。 */
    private static final int DOME_INTERVAL = 10;

    /** 穹顶每一圈纬度的采样点数。12 边形在 10~14 格半径下已看不出棱角。 */
    private static final int DOME_POINTS = 12;

    /** 穹顶的纬度（相对半径的高度比例）。两圈：贴地一圈 + 接近顶的一圈。 */
    private static final double[] DOME_LATITUDES = {0.10D, 0.72D};

    /** 向内收敛环的发包间隔（tick）。 */
    private static final int COLLAPSE_INTERVAL = 6;

    /** 收敛环的点数。 */
    private static final int COLLAPSE_POINTS = 12;

    /** 收敛环一轮动画的时长（tick）—— 环从边界一路收到中心所需的时间。 */
    private static final int COLLAPSE_CYCLE = 30;

    /** 内部飘尘的发包间隔（tick）。 */
    private static final int MOTE_INTERVAL = 4;

    /** 当前生效的领域。与「忆海」同理：领域是世界侧的区域，挂在实体上语义是错的。 */
    private static final List<Realm> REALMS = new ArrayList<>();

    /** 一片「无尽忆域」。 */
    private static final class Realm {

        private final ServerPlayer caster;
        private final ServerLevel level;
        private final Vec3 center;
        private final double radius;
        private final long expireTick;
        private final long startTick;
        private final int spellLevel;

        private long nextStackTick;
        private long nextHoldTick;
        private long nextDomeTick;
        private long nextCollapseTick;
        private long nextMoteTick;

        private Realm(final ServerPlayer caster, final ServerLevel level, final Vec3 center,
                      final double radius, final long expireTick, final int spellLevel,
                      final long now) {
            this.caster = caster;
            this.level = level;
            this.center = center;
            this.radius = radius;
            this.expireTick = expireTick;
            this.startTick = now;
            this.spellLevel = spellLevel;
            this.nextStackTick = now;
            this.nextHoldTick = now;
            this.nextDomeTick = now;
            this.nextCollapseTick = now;
            this.nextMoteTick = now;
        }
    }

    public EndlessRealmSpell() {
        super(memoryConfig(SpellRarity.EPIC, 120.0D, 10));
        this.baseManaCost = 66;
        this.manaCostPerLevel = 13;
        this.baseSpellPower = 1;
        this.spellPowerPerLevel = 0;
        this.castTime = 60;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return SPELL_ID;
    }

    @Override
    public CastType getCastType() {
        return CastType.LONG;
    }

    /** 吟唱起手音：复用「忆海」的起手（素材直接沿用，不新增音效文件）。 */
    @Override
    public Optional<SoundEvent> getCastStartSound() {
        return Optional.of(ModSounds.SPELL_SEA_OF_MEMORY_CHARGE.get());
    }

    /** 领域张开音：复用「忆海」的翻涌。 */
    @Override
    public Optional<SoundEvent> getCastFinishSound() {
        return Optional.of(ModSounds.SPELL_SEA_OF_MEMORY_LOOP.get());
    }

    // ==================================================================
    // 开域
    // ==================================================================

    @Override
    public void onCast(final Level level, final int spellLevel, final LivingEntity entity,
                       final CastSource castSource, final MagicData playerMagicData) {
        if (!level.isClientSide && entity instanceof ServerPlayer caster
                && level instanceof ServerLevel serverLevel) {
            open(caster, serverLevel, spellLevel);
        }
        super.onCast(level, spellLevel, entity, castSource, playerMagicData);
    }

    private static void open(final ServerPlayer caster, final ServerLevel level, final int spellLevel) {
        final int index = clampLevelIndex(spellLevel);
        final int durationTicks = DURATION_SECONDS[index] * 20;
        final long now = level.getServer().overworld().getGameTime();
        final double r = RADIUS[index];
        final Vec3 center = caster.position();

        REALMS.add(new Realm(caster, level, center, r, now + durationTicks, spellLevel, now));

        // ⭐ 开场必须"响"：170 法力砸下去只有一行动作栏，玩家会怀疑没放出来。
        //    FLASH 是白光爆闪 —— 无量空处最标志性的就是那一下"视野全白"。
        level.sendParticles(ParticleTypes.FLASH,
                center.x, center.y + 1.0D, center.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.END_ROD,
                center.x, center.y + 1.0D, center.z, 40, r * 0.8D, 1.2D, r * 0.8D, 0.02D);
        level.playSound(null, center.x, center.y, center.z,
                ModSounds.SPELL_SEA_OF_MEMORY_LOOP.get(), SoundSource.PLAYERS, 1.0F, 0.85F);
    }

    // ==================================================================
    // 每 tick 推进
    // ==================================================================

    @SubscribeEvent
    public static void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || REALMS.isEmpty()) {
            return;
        }
        final long now = event.getServer().overworld().getGameTime();
        for (final Iterator<Realm> it = REALMS.iterator(); it.hasNext(); ) {
            final Realm realm = it.next();
            // 施法者没了（登出 / 死亡）→ 领域直接消散，并且**必须**解定身。
            // 否则会留下"怪被永久定在原地"这种最难排查的 bug。
            if (realm.caster.isRemoved() || !realm.caster.isAlive()) {
                dissipate(realm);
                it.remove();
                continue;
            }
            if (now >= realm.expireTick) {
                dissipate(realm);
                it.remove();
                continue;
            }
            // 封闭 + 定身：按 HOLD_INTERVAL 节流（每 tick 做一次实体查询太贵）
            if (now >= realm.nextHoldTick) {
                realm.nextHoldTick = now + HOLD_INTERVAL;
                hold(realm);
            }
            spawnAmbience(realm, now);
            if (now >= realm.nextStackTick) {
                realm.nextStackTick = now + STACK_INTERVAL_TICKS;
                stack(realm);
            }
        }
    }

    /**
     * 封闭 + 定身：领域的实际规则都在这里。
     *
     * <p>对每个实体做两件事：①越界就拉回来（封闭）；②施加定身（或 BOSS 降级）。
     */
    private static void hold(final Realm realm) {
        for (final LivingEntity target : RaycastHelper.findLivingInSphere(
                realm.level, realm.center, realm.radius, realm.caster, true)) {
            confine(target, realm);
            if (AbilityMap.isBoss(target.getType())) {
                slowBoss(target);
            } else {
                bind(target);
            }
        }
        // 施法者自己也被锚住 —— 这是"展开领域"的代价。
        confine(realm.caster, realm);
        bind(realm.caster);
    }

    /**
     * 封闭区域的边界：**出不去**。
     *
     * <p>为什么需要这一步：定身只保证"自己走不出去"，但击退、爆炸、水流、活塞
     * 都能把实体推出领域。没有这一步，"封闭区域"就是假的。
     *
     * <p>⭐ 只处理<b>越界</b>的实体（平时一个都不会命中），所以常态开销是零。
     */
    private static void confine(final LivingEntity entity, final Realm realm) {
        final Vec3 offset = entity.position().subtract(realm.center);
        final double distSqr = offset.lengthSqr();
        final double limit = realm.radius - 0.5D;
        if (distSqr <= limit * limit) {
            return;
        }
        final Vec3 pulled = offset.normalize().scale(limit).add(realm.center);
        entity.setPos(pulled.x, pulled.y, pulled.z);
        entity.setDeltaMovement(Vec3.ZERO);
    }

    /** 定身：移动速度归零 + 每 tick 重刷的短效果。 */
    private static void bind(final LivingEntity target) {
        target.addEffect(new MobEffectInstance(ModEffects.ENDLESS_BIND.get(),
                BIND_REFRESH_TICKS, 0, false, true, true));
    }

    /**
     * BOSS 降级：免疫"无法行动"，改为移动速度 −50%。
     *
     * <p>用 transient 修饰符（不写进存档）+ 固定 UUID（重复施加是替换不是叠加）。
     */
    private static void slowBoss(final LivingEntity boss) {
        final var attribute = boss.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute == null) {
            return;
        }
        attribute.removeModifier(BOSS_SLOW_ID);
        attribute.addTransientModifier(new AttributeModifier(
                BOSS_SLOW_ID, "endless_realm_boss_slow", BOSS_SLOW,
                AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    /** 每秒一次：给领域内的敌人叠 1 层认知过载。 */
    private static void stack(final Realm realm) {
        for (final LivingEntity target : RaycastHelper.findLivingInSphere(
                realm.level, realm.center, realm.radius, realm.caster, true)) {
            MemoryArrowSpell.stackCognitiveOverloadInSea(target, STACK_CAP);
        }
    }

    // ==================================================================
    // 收域
    // ==================================================================

    /**
     * 领域结束 / 中断：解定身 + 收束特效。
     *
     * <p>⭐ 解定身是<b>第一优先级</b>：先放人，再放特效。
     * 顺序反了的话，一旦中间抛异常就会留下被永久定住的怪。
     */
    private static void dissipate(final Realm realm) {
        for (final LivingEntity target : RaycastHelper.findLivingInSphere(
                realm.level, realm.center, realm.radius + 1.0D, realm.caster, false)) {
            target.removeEffect(ModEffects.ENDLESS_BIND.get());
            final var attribute = target.getAttribute(Attributes.MOVEMENT_SPEED);
            if (attribute != null) {
                attribute.removeModifier(BOSS_SLOW_ID);
            }
        }
        realm.caster.removeEffect(ModEffects.ENDLESS_BIND.get());
        final var casterSpeed = realm.caster.getAttribute(Attributes.MOVEMENT_SPEED);
        if (casterSpeed != null) {
            casterSpeed.removeModifier(BOSS_SLOW_ID);
        }

        realm.level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                realm.center.x, realm.center.y + 1.0D, realm.center.z,
                50, realm.radius * 0.7D, 0.8D, realm.radius * 0.7D, 0.06D);
        realm.level.playSound(null, realm.center.x, realm.center.y, realm.center.z,
                ModSounds.SPELL_SEA_OF_MEMORY_END.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    // ==================================================================
    // 「无法行动」的另一半：取消被定住者的攻击
    // ==================================================================

    /**
     * 被领域定住的生物<b>打不出伤害</b>。
     *
     * <p>移动速度归零只能让它走不动；攻击是另一条路径，必须单独拦。
     * {@code LivingAttackEvent} 是可取消的，且 {@code getSource().getEntity()}
     * 取到的是攻击者 —— 远程攻击（箭）的最终伤害也会经过这里，一并拦掉。
     *
     * <p>⚠️ 施法者<b>不拦</b>：领域期间玩家自己仍然可以输出（只是走不动），
     * 否则这个大招等于把自己也关掉，完全没法用。
     */
    @SubscribeEvent
    public static void onLivingAttack(final LivingAttackEvent event) {
        if (event.getEntity().level().isClientSide
                || !(event.getSource().getEntity() instanceof LivingEntity attacker)) {
            return;
        }
        if (!attacker.hasEffect(ModEffects.ENDLESS_BIND.get())) {
            return;
        }
        for (final Realm realm : REALMS) {
            if (realm.caster == attacker) {
                return;
            }
        }
        event.setCanceled(true);
    }

    // ==================================================================
    // 特效
    // ==================================================================

    /**
     * 三层特效：穹顶（定形，逐点）+ 向内收敛环（定形，逐点）+ 内部飘尘（一次发包）。
     *
     * <p><b>⭐ 视觉语义</b>：忆海是"记忆向外涌"，无尽忆域是"信息向内灌"。
     * 所以这里唯一必须有、也最能说明问题的一层是<b>向内收缩的环</b> ——
     * 它从边界一路收到中心，再重新开始，读起来就是"有无穷的信息正压进来"。
     */
    private static void spawnAmbience(final Realm realm, final long now) {
        final ServerLevel level = realm.level;
        final double r = realm.radius;
        final double cx = realm.center.x;
        final double cy = realm.center.y;
        final double cz = realm.center.z;

        // ① 穹顶 —— 两圈纬度，证明"这是一整个封闭的球，不是地面上的一个圈"
        if (now >= realm.nextDomeTick) {
            realm.nextDomeTick = now + DOME_INTERVAL;
            final double spin = (now % 120) * 0.012D;
            for (final double latitude : DOME_LATITUDES) {
                // 球面上某一纬度的圆半径：r·cos(θ)，高度：r·sin(θ)
                final double theta = Math.asin(latitude);
                final double ringR = r * Math.cos(theta);
                final double y = cy + r * latitude;
                for (int i = 0; i < DOME_POINTS; i++) {
                    final double angle = Math.PI * 2.0D / DOME_POINTS * i + spin;
                    level.sendParticles(ParticleTypes.END_ROD,
                            cx + Math.cos(angle) * ringR, y, cz + Math.sin(angle) * ringR,
                            1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            }
        }

        // ② 向内收敛环 —— 白 → 靛蓝，从边界收到中心（本法的视觉签名）
        if (now >= realm.nextCollapseTick) {
            realm.nextCollapseTick = now + COLLAPSE_INTERVAL;
            final double phase = (double) ((now - realm.startTick) % COLLAPSE_CYCLE) / COLLAPSE_CYCLE;
            final double ringR = r * (1.0D - phase);
            for (int i = 0; i < COLLAPSE_POINTS; i++) {
                final double angle = Math.PI * 2.0D / COLLAPSE_POINTS * i;
                level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                        cx + Math.cos(angle) * ringR, cy + 0.6D, cz + Math.sin(angle) * ringR,
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }

        // ③ 内部飘尘 —— 一次发包带十几个粒子，铺出"信息悬在空气里"
        if (now >= realm.nextMoteTick) {
            realm.nextMoteTick = now + MOTE_INTERVAL;
            level.sendParticles(ParticleTypes.ENCHANT,
                    cx, cy + 1.4D, cz, 14, r * 0.7D, 1.0D, r * 0.7D, 0.005D);
        }
    }

    /** 当前生效的领域数量。给调试/校验脚本用。 */
    public static int activeRealmCount() {
        return REALMS.size();
    }

    private static int clampLevelIndex(final int spellLevel) {
        return Math.max(1, Math.min(RADIUS.length, spellLevel)) - 1;
    }
}
