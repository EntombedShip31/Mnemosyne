package com.etbs31.mnemosyne.entity;

import com.etbs31.mnemosyne.registry.ModEntities;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.spell.low.MemoryArrowSpell;
import io.redspace.ironsspellbooks.entity.spells.AbstractMagicProjectile;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 忆矢投射物 —— 忆海第一个真正会飞的实体。
 *
 * <p><b>为什么这个类必须存在</b>：在此之前 {@code MnemosyneProjectileSpell.onCast} 与
 * {@code MemoryArrowSpell.onCast} 都是 {@code TODO(WS-J)}，**什么都不生成**。
 * 玩家放出忆矢 = 法力扣了、冷却转了、屏幕上什么都没有。
 * 这是"很多法术释放后无效果"里最严重的一条，因为它是**完全没实现**，不是逻辑错。
 *
 * <p><b>文件归属</b>：实体层（原 WS-J 欠账）。本类同时补上了 WS-J 留下的实体缺口。
 *
 * <p><b>⭐⭐ 唯一一处受控的非 api 依赖</b>
 * <br>继承 {@link AbstractMagicProjectile}（{@code io.redspace.ironsspellbooks.entity.spells}，
 * 非 api 包）。理由与 {@code SpellBook} 那次相同 —— 这是**第三方附属做投射物的标准做法**，
 * 我们继承它换来四件自己写要几百行、且极易写错的东西：
 * <ol>
 *   <li>每 tick 的连续命中检测（{@code handleHitDetection} / {@code raycastForEntitiesAlongPath}）——
 *       自己写最常见的 bug 是"高速投射物穿过目标不掉血"；</li>
 *   <li>穿透 / 弹射 / 追踪（{@code setPierceLevel} / {@code setRicochetLevel} / {@code setHomingTarget}）；</li>
 *   <li>反魔法（{@code onAntiMagic}）与 ISS 的召唤物友伤判定接线；</li>
 *   <li>⭐ {@link #trailParticles()} / {@link #impactParticles(double, double, double)} 两个**特效钩子** ——
 *       这就是"炫酷特效"的落点，ISS 全部 40 多个投射物都靠它出效果。</li>
 * </ol>
 * 代价：非 api 引用。约束是**非 api 引用只许出现在本文件这一处**，
 * 不要扩散到法术类里（法术类仍然只 import {@code api.*}）。
 *
 * <p><b>伤害归属</b>：命中时用 {@code getDamageSource(this, getOwner())} 取源，
 * 即"直接实体 = 投射物、间接实体 = 施法者"，与 {@code docs/tech/03} §4.2 一致。
 * ⚠️ 必须走 {@link DamageSources#applyDamage}，不能 {@code target.hurt(...)}，
 * 否则目标的 {@code memory_magic_resist} 与 {@code SpellDamageEvent} 全部失效。
 */
public class MemoryArrowEntity extends AbstractMagicProjectile {

    /** 飞行速度（格/tick）。ISS 的魔法飞弹是 1.2，忆矢稍快一点，手感更"锐"。 */
    private static final float SPEED = 1.35F;

    /** 法术等级 —— 命中回调要把它传给法术，用来算伤害与叠层。 */
    private int spellLevel = 1;

    public MemoryArrowEntity(final EntityType<? extends MemoryArrowEntity> type, final Level level) {
        super(type, level);
    }

    /** 由法术调用：从施法者眼睛位置朝视线方向射出。 */
    public MemoryArrowEntity(final Level level, final LivingEntity shooter, final int spellLevel) {
        this(ModEntities.MEMORY_ARROW.get(), level);
        this.spellLevel = spellLevel;
        setOwner(shooter);
        setPos(shooter.getEyePosition().add(shooter.getLookAngle().scale(0.6D)));
        shoot(shooter.getLookAngle());
    }

    public void setSpellLevel(final int spellLevel) {
        this.spellLevel = spellLevel;
    }

    public int getSpellLevel() {
        return spellLevel;
    }

    // ==================================================================
    // 特效 —— "炫酷"就落在这两个钩子里
    // ==================================================================

    // ---- 配色：只用忆海的两支主色（docs/09）----

    /** 靛蓝 {@code #534AB7}（与 {@code util/SpellFeedback.MEMORY_INDIGO} 同值）。 */
    private static final Vector3f INDIGO = Vec3.fromRGB24(0x534AB7).toVector3f();

    /** 品红 {@code #D4537E}（与 {@code util/SpellFeedback.MEMORY_MAGENTA} 同值）。 */
    private static final Vector3f MAGENTA = Vec3.fromRGB24(0xD4537E).toVector3f();

    // ---- 预建粒子参数（ParticleOptions 不可变，可安全复用；scale 由基类钳到 0.01~4）----

    /** 主干"记忆碎片"：靛蓝→品红的**双色过渡**尘，原版 {@code dust_color_transition}。 */
    private static final ParticleOptions SHARD = new DustColorTransitionOptions(INDIGO, MAGENTA, 1.1F);

    /** 环绕箭身的靛蓝尘雾。 */
    private static final ParticleOptions INDIGO_HAZE = new DustParticleOptions(INDIGO, 1.3F);

    /** 外散的品红细尘。 */
    private static final ParticleOptions MAGENTA_MOTE = new DustParticleOptions(MAGENTA, 0.85F);

    /** 冲击波三圈：内圈细靛蓝 / 中圈品红 / 外圈粗靛蓝。 */
    private static final ParticleOptions RING_INNER = new DustParticleOptions(INDIGO, 0.8F);
    private static final ParticleOptions RING_MID = new DustParticleOptions(MAGENTA, 1.1F);
    private static final ParticleOptions RING_OUTER = new DustParticleOptions(INDIGO, 1.4F);

    /**
     * 飞行拖尾。基类每 tick 调一次。
     *
     * <p><b>⚠️ 本方法只在客户端被调用。</b>{@code AbstractMagicProjectile.tick()} 里写的是
     * {@code if (level.isClientSide) trailParticles();}（已用 javap 核对字节码）。
     * 所以这里一律走 {@code level().addParticle} 本地生成 —— 服务端不要再广播一次，
     * 否则粒子会翻倍。
     *
     * <p><b>2026-09-18 增强。</b>原先每 2 tick 在**同一个点**放 3 个粒子：在 1.35 格/tick 的速度下
     * 每两个点之间隔 2.7 格，拖尾断成一串"虚线点"，而且看不出"记忆"的意象。现在分四层：
     * <ol>
     *   <li><b>主干光带</b>：沿"上一 tick → 这一 tick"的线段插值补点，把离散位置连成连续光带；</li>
     *   <li><b>双螺旋</b>：绕飞行轴旋转的靛蓝尘，两粒反向偏移，给箭身"拧"的动感；</li>
     *   <li><b>飘散层</b>：品红细尘 + 冷蓝魂火 + 绯红孢子，向外扩散，做"记忆逸散"；</li>
     *   <li><b>点缀层</b>：附魔字符 / 幽匿魂 / 电火花 / 幽匿涟漪，低频出现，打散规律感。</li>
     * </ol>
     * 全部只用原版粒子 —— 不新增粒子类型、不新增贴图，也就不会踩"资源加了没人读"那条静默失效。
     */
    @Override
    public void trailParticles() {
        final Vec3 motion = getDeltaMovement();
        final Vec3 head = position().add(0.0D, getBbHeight() * 0.5D, 0.0D);
        final Vec3 tail = head.subtract(motion);
        final Vec3 dir = motion.lengthSqr() < 1.0E-6D ? Vec3.ZERO : motion.normalize();

        // ① 主干光带：两个插值点（t = 1/3、2/3）把这一 tick 走过的线段填满
        addParticle(SHARD, tail.lerp(head, 1.0D / 3.0D), Vec3.ZERO);
        addParticle(SHARD, tail.lerp(head, 2.0D / 3.0D), Vec3.ZERO);

        // ② 双螺旋：每 2 tick 在垂直于飞行轴的平面里取一个旋转角
        if (tickCount % 2 == 0) {
            final Vec3[] basis = perpendicularBasis(dir);
            final double angle = tickCount * 0.75D;
            final double radius = 0.15D;
            final Vec3 offset = basis[0].scale(Math.cos(angle) * radius)
                    .add(basis[1].scale(Math.sin(angle) * radius));
            addParticle(INDIGO_HAZE, head.add(offset), Vec3.ZERO);
            addParticle(INDIGO_HAZE, head.subtract(offset), Vec3.ZERO);
        }

        // ③ 飘散层
        if (tickCount % 3 == 0) {
            addParticle(ParticleTypes.SOUL_FIRE_FLAME, head, motion.scale(-0.06D));
        }
        if (tickCount % 4 == 0) {
            addParticle(MAGENTA_MOTE, head, randomVelocity(0.05D));
            addParticle(ParticleTypes.CRIMSON_SPORE, head, randomVelocity(0.03D));
        }

        // ④ 点缀层
        if (tickCount % 5 == 0) {
            addParticle(ParticleTypes.ENCHANT, head, randomVelocity(0.08D));
        }
        if (tickCount % 6 == 0) {
            addParticle(ParticleTypes.SCULK_SOUL, head, new Vec3(0.0D, 0.02D, 0.0D));
        }
        if (tickCount % 12 == 0) {
            addParticle(ParticleTypes.ELECTRIC_SPARK, head, randomVelocity(0.06D));
            addParticle(ParticleTypes.SCULK_CHARGE_POP, head, Vec3.ZERO);
        }
    }

    /**
     * 命中冲击。基类在打到实体或方块时调一次。
     *
     * <p><b>⚠️⚠️ 本方法只在服务端被调用</b> —— {@code AbstractMagicProjectile.onHit()} 的分支是
     * {@code if (!level.isClientSide) impactParticles(...)}（javap 核对过字节码）。
     * 所以这里必须用 {@code ServerLevel.sendParticles} 广播给所有客户端。
     *
     * <p><b>2026-09-18 修掉的静默失效：</b>原先这里写的是
     * {@code if (!level().isClientSide) return;} —— 而调用点**永远**是服务端，
     * 于是这段冲击特效**一次都没执行过**。方块命中之所以还能看到一点粒子，
     * 是因为子类 {@code onHitBlock} 自己多调了一次（那次是在客户端）；
     * 而<b>实体命中根本没有冲击表现，只有音效</b>。现在两边都由基类的单次服务端广播统一负责。
     */
    @Override
    public void impactParticles(final double x, final double y, final double z) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        // ① 爆闪：一帧白光，给"打中"一个明确的瞬间
        serverLevel.sendParticles(ParticleTypes.FLASH, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        // ② 记忆碎片：向外炸开的双色过渡尘
        serverLevel.sendParticles(SHARD, x, y, z, 28, 0.20D, 0.20D, 0.20D, 0.34D);
        // ③ 余韵：靛蓝 + 品红两层慢速尘雾
        serverLevel.sendParticles(INDIGO_HAZE, x, y, z, 18, 0.35D, 0.35D, 0.35D, 0.05D);
        serverLevel.sendParticles(MAGENTA_MOTE, x, y, z, 18, 0.35D, 0.35D, 0.35D, 0.05D);
        // ④ 残影：反传送门 + 幽匿魂 + 附魔字符，做"记忆被打碎"的观感
        serverLevel.sendParticles(ParticleTypes.REVERSE_PORTAL, x, y, z, 16, 0.25D, 0.25D, 0.25D, 0.12D);
        serverLevel.sendParticles(ParticleTypes.SCULK_SOUL, x, y, z, 10, 0.30D, 0.30D, 0.30D, 0.03D);
        serverLevel.sendParticles(ParticleTypes.ENCHANT, x, y, z, 12, 0.45D, 0.45D, 0.45D, 0.35D);
        // ⑤ 涟漪：低频幽匿爆点，给"记忆被读取"的读条感
        serverLevel.sendParticles(ParticleTypes.SCULK_CHARGE_POP, x, y, z, 6, 0.30D, 0.20D, 0.30D, 0.05D);
        // ⑥ 冲击波：以飞行方向为法线的三圈同心尘环
        shockwaveRings(serverLevel, x, y, z);
    }

    /**
     * 冲击波圆环 —— 在**垂直于飞行方向**的平面里画三圈同心尘环。
     *
     * <p>为什么值得单写：单点爆发看起来只是"一团雾"，加上一个有朝向的圆环之后才读得出
     * "撞上了"这个动作 —— 环的朝向本身就告诉玩家箭是从哪个方向来的。
     *
     * <p>⚠️ 环上的每个粒子都是**单发 + 零速度**（{@code sendParticles(..., 1, 0,0,0, 0)}）：
     * {@code sendParticles} 的 {@code speed} 参数只能给"随机方向的初速度"，
     * 想要确定的位置就必须逐粒自己算坐标。
     */
    private void shockwaveRings(final ServerLevel serverLevel, final double x, final double y, final double z) {
        final Vec3 motion = getDeltaMovement();
        final Vec3 dir = motion.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : motion.normalize();
        final Vec3[] basis = perpendicularBasis(dir);
        final double[] radii = {0.35D, 0.65D, 0.95D};
        final ParticleOptions[] rings = {RING_INNER, RING_MID, RING_OUTER};
        for (int ring = 0; ring < radii.length; ring++) {
            final int count = 12 + ring * 6;
            for (int i = 0; i < count; i++) {
                final double angle = (Math.PI * 2.0D) * i / count;
                final Vec3 offset = basis[0].scale(Math.cos(angle) * radii[ring])
                        .add(basis[1].scale(Math.sin(angle) * radii[ring]));
                serverLevel.sendParticles(rings[ring],
                        x + offset.x, y + offset.y, z + offset.z,
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    /** 客户端本地生成一个粒子（本方法的所有调用点都在客户端，见 {@link #trailParticles()}）。 */
    private void addParticle(final ParticleOptions particle, final Vec3 pos, final Vec3 velocity) {
        level().addParticle(particle, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
    }

    /**
     * 以 {@code dir} 为法线的两个正交单位向量 —— 画圆环 / 螺旋都要它。
     *
     * <p>⚠️ 必须先选一个"不与 dir 平行"的参考向量再叉乘，否则退化时结果是零向量。
     */
    private static Vec3[] perpendicularBasis(final Vec3 dir) {
        final Vec3 up = Math.abs(dir.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        final Vec3 axisA = dir.cross(up).normalize();
        return new Vec3[]{axisA, axisA.cross(dir).normalize()};
    }

    /** 各分量都在 {@code [-spread, spread]} 内的随机速度。 */
    private Vec3 randomVelocity(final double spread) {
        return new Vec3(
                (random.nextDouble() - 0.5D) * spread * 2.0D,
                (random.nextDouble() - 0.5D) * spread * 2.0D,
                (random.nextDouble() - 0.5D) * spread * 2.0D);
    }

    @Override
    public float getSpeed() {
        return SPEED;
    }

    @Override
    public Optional<Supplier<SoundEvent>> getImpactSound() {
        return Optional.of(ModSounds.SPELL_MEMORY_ARROW_HIT);
    }

    // ==================================================================
    // 命中结算
    // ==================================================================

    /**
     * 命中实体：走学派伤害源 + 交给法术做额外效果（叠认知过载）。
     *
     * <p>⭐ 这里是"法术效果真正生效"的地方 —— 忆矢的伤害与叠层**不在 {@code onCast} 里**，
     * 而在命中回调里。所以 {@code MemoryArrowSpell.onProjectileHit} 必须被调到，
     * 否则箭飞出去、打中了、却没有任何效果（那正是修改前的状态）。
     */
    @Override
    protected void onHitEntity(final EntityHitResult result) {
        super.onHitEntity(result);
        final Entity target = result.getEntity();
        final Entity owner = getOwner();
        if (!level().isClientSide && target instanceof LivingEntity living && owner instanceof LivingEntity caster) {
            // 伤害公式与叠层逻辑都在 MemoryArrowSpell / 基类里，这里只转发 —— 保证只有一份实现
            MemoryArrowSpell.resolveHit(level(), caster, this, living, spellLevel);
        }
        consumeEntityImpact(result, true);
    }

    /**
     * 命中方块。
     *
     * <p><b>2026-09-18 瘦身：</b>这里原先自己又调了一次 {@code impactParticles} + 播了**两遍**音效。
     * 但基类 {@code AbstractMagicProjectile.onHit()} 已经统一做过
     * {@code impactParticles(命中点)} + {@code getImpactSound()} —— 结果是
     * "撞一次墙放三遍音效"，而且服务端那次 {@code impactParticles} 是空转（见 {@link #impactParticles}）。
     * 现在撞击表现**只有基类一处**，这里只负责移除自己。
     */
    @Override
    protected void onHitBlock(final BlockHitResult result) {
        super.onHitBlock(result);
        discard();
    }
}
