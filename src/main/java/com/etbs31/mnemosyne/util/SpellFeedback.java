package com.etbs31.mnemosyne.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * 法术的**可见反馈**工具 —— 让玩家"看得见自己放了什么"。
 *
 * <p><b>为什么需要这个类（这是本次修改最重要的一条）</b>
 * <br>2026-09-17 排查"很多法术释放后无效果"时发现，16 个法术里**只有 2 个**
 * （集体遗忘、遗忘诅咒）用了粒子，其余 16 个是**零视觉、零音效、零提示**的。
 * 而忆海的设计里大量法术是"隐式效果"：
 * <ul>
 *   <li>遗忘 / 失忆 —— 移除怪的 AI goal，玩家在屏幕上**看不到任何变化**；</li>
 *   <li>术忆系列 —— 往玩家自己的忆格里写数据，而忆格 GUI 当时也不显示；</li>
 *   <li>心念扩张 —— 加临时忆格、减惩罚，纯数值；</li>
 *   <li>复诵 / 拾忆 —— 依赖历史记录，没记录时直接 return。</li>
 * </ul>
 * 所以"法术没效果"里**有很大一部分不是逻辑错，而是缺反馈**：
 * 效果发生了，但玩家完全不知道。这个类就是补这一块。
 *
 * <p><b>三条设计原则</b>
 * <ol>
 *   <li><b>不引入任何新 GUI</b>。反馈只走原版渠道：世界粒子、音效、聊天栏 actionbar。
 *       这正是"删掉忆格 GUI"之后忆格系统的替代反馈通道。</li>
 *   <li><b>粒子只在客户端生成</b>。服务端用 {@code ServerLevel.sendParticles} 广播，
 *       客户端不要重复生成（否则粒子翻倍）。所以每个方法都先判 {@code isClientSide}。</li>
 *   <li><b>失败也要有反馈</b>。{@link #noTarget} 这类方法存在的意义就是：
 *       玩家对着空气施法时，必须看到"没有目标"，而不是以为 mod 坏了。</li>
 * </ol>
 *
 * <p><b>文件归属</b>：{@code util/}，本次修复新增。所有法术都可以用，
 * 但它**不含任何业务逻辑** —— 只负责"把已经发生的事说出来"。
 */
public final class SpellFeedback {

    private SpellFeedback() {}

    // ==================================================================
    // 学派配色（与 ModSchools / docs/09 一致）
    // ==================================================================

    /** 靛蓝 —— 忆海主色 {@code #534AB7}。 */
    public static final int MEMORY_INDIGO = 0x534AB7;

    /** 品红 —— 忆海第二主色 {@code #D4537E}。 */
    public static final int MEMORY_MAGENTA = 0xD4537E;

    /** 上面两个色值的尘粒子形态（{@code DustParticleOptions} 需要 {@code Vector3f}）。 */
    private static final Vector3f INDIGO_VECTOR = Vec3.fromRGB24(MEMORY_INDIGO).toVector3f();
    private static final Vector3f MAGENTA_VECTOR = Vec3.fromRGB24(MEMORY_MAGENTA).toVector3f();

    private static final double TWO_PI = Math.PI * 2.0D;

    // ==================================================================
    // 施法表现
    // ==================================================================

    /**
     * 施法瞬间在施法者周围爆一圈"记忆碎片"。
     *
     * <p>用 {@code ENCHANT}（附魔字符，像飘起的记忆碎片）+ {@code SCULK_SOUL}（幽蓝雾）
     * 两支组合，不依赖任何自定义贴图 —— 这是 ISS 的做法：**先用原版粒子拼出识别度**，
     * 只有确实需要时才上自定义粒子类型。
     */
    public static void castBurst(final Level level, final LivingEntity caster, final int color) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final Vec3 pos = caster.getEyePosition();
        serverLevel.sendParticles(ParticleTypes.ENCHANT,
                pos.x, pos.y, pos.z, 24, 0.5D, 0.5D, 0.5D, 0.35D);
        serverLevel.sendParticles(ParticleTypes.SCULK_SOUL,
                pos.x, pos.y, pos.z, 8, 0.35D, 0.35D, 0.35D, 0.02D);
    }

    /**
     * 长时间吟唱法术的**持续引导**表现。由 {@code MnemosyneLongCastSpell} 每若干 tick 调用。
     *
     * <p>这一条专门解决"长吟唱法术在吟唱期间毫无动静，玩家以为卡住了"。
     */
    public static void channelTick(final Level level, final LivingEntity caster, final float progress) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        // 越接近完成，粒子越密（progress ∈ [0,1]）
        final int count = 2 + (int) (progress * 10.0F);
        final double radius = 0.8D + progress * 0.6D;
        final Vec3 pos = caster.position();
        serverLevel.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                pos.x, pos.y + 1.0D, pos.z, count, radius, 0.6D, radius, 0.01D);
        serverLevel.sendParticles(ParticleTypes.CRIMSON_SPORE,
                pos.x, pos.y + 1.0D, pos.z, count / 2, radius, 0.6D, radius, 0.01D);
    }

    /**
     * 投射物**离手瞬间**的定向发射光带（2026-09-18 新增）。
     *
     * <p>为什么 {@link #castBurst} 不够：那是**以施法者为中心的球状爆发**，回答的是
     * "我放了一个法术"；但投射物还要回答"它往哪飞" —— 尤其第三人称 / 近距离时，
     * 只有球状爆发会让人看不出箭是从哪出去的。
     *
     * <p>⚠️ {@code sendParticles} 的 {@code speed} 参数只能给"随机方向的初速度"，
     * 做不出定向速度。所以这里改成**沿视线方向逐点铺粒子**（一条 8 点的短光带），
     * 用位置而不是速度来表达方向。
     */
    public static void muzzleBurst(final Level level, final LivingEntity caster) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final Vec3 dir = caster.getLookAngle();
        final Vec3 origin = caster.getEyePosition().add(dir.scale(0.45D));
        for (int i = 0; i < 8; i++) {
            final Vec3 p = origin.add(dir.scale(0.12D * i));
            serverLevel.sendParticles(memoryShardDust(), p.x, p.y, p.z, 1, 0.03D, 0.03D, 0.03D, 0.02D);
        }
        serverLevel.sendParticles(ParticleTypes.END_ROD,
                origin.x, origin.y, origin.z, 6, 0.06D, 0.06D, 0.06D, 0.25D);
        serverLevel.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                origin.x, origin.y, origin.z, 4, 0.05D, 0.05D, 0.05D, 0.12D);
    }

    /** 忆海主色的"记忆碎片"尘：靛蓝 → 品红过渡（原版 {@code dust_color_transition}）。 */
    private static ParticleOptions memoryShardDust() {
        return new DustColorTransitionOptions(
                Vec3.fromRGB24(MEMORY_INDIGO).toVector3f(),
                Vec3.fromRGB24(MEMORY_MAGENTA).toVector3f(),
                1.2F);
    }

    // ==================================================================
    // 命中 / 生效表现
    // ==================================================================

    /** 目标身上"记忆被抽走/写入"的爆发。 */
    public static void hitBurst(final Level level, final LivingEntity target, final int color) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final Vec3 pos = target.getEyePosition();
        serverLevel.sendParticles(ParticleTypes.REVERSE_PORTAL,
                pos.x, pos.y, pos.z, 20, 0.3D, 0.4D, 0.3D, 0.15D);
        serverLevel.sendParticles(ParticleTypes.SCULK_SOUL,
                pos.x, pos.y, pos.z, 10, 0.3D, 0.4D, 0.3D, 0.03D);
    }

    /** 以某个点为中心的范围爆发（集体遗忘、忆海这类 AOE）。 */
    public static void areaBurst(final Level level, final Vec3 center, final double radius, final int color) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final int count = (int) Math.min(120.0D, 24.0D * radius);
        serverLevel.sendParticles(ParticleTypes.SCULK_SOUL,
                center.x, center.y + 0.2D, center.z, count, radius, 0.4D, radius, 0.02D);
        serverLevel.sendParticles(ParticleTypes.ENCHANT,
                center.x, center.y + 0.2D, center.z, count, radius, 0.5D, radius, 0.4D);
    }

    // ==================================================================
    // 形状化表现（2026-09-18 第二轮特效：把"判定范围 / 数值大小"画出来）
    // ==================================================================
    //
    // 上面那些方法解决的是"法术有没有表现"；这一组解决的是**表现对不对**：
    // 一个锥形 AOE、一条锁定链、一次按层数结算的引爆，光有一团球状雾是读不出来的。
    // 原版没有任何"锥形 / 环 / 连线"粒子，所以只能自己按几何逐点铺。

    /**
     * 锥形扇面 —— 让"前方 N 格、M 度"这个判定范围**看得见**。
     *
     * <p>用在哪：{@code 碎忆}（{@code memory_shard}）的判定是"前方 5 格、60° 锥形"，
     * 而原版没有任何粒子形状能表达"锥"。这里按**等距锥壳**逐点铺：
     * 每一层距离上，把粒子放在半径 {@code distance × tan(半角)} 的圆上 ——
     * 于是画出来的是一个真正的圆锥壳，而不是一个平面扇形。
     *
     * <p>⚠️ 点数随层数增长（{@code 6 + 2×shell}），5 层共 60 粒 + 轴线 5 粒。
     * 这是个瞬时效果（施法那一下），不每 tick 跑，所以 65 粒是可以接受的；
     * 但**不要再往上加层**，否则近距离施法会糊住整个屏幕。
     */
    public static void coneSweep(final Level level, final LivingEntity caster,
                                 final double range, final double halfAngleDegrees) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final Vec3 eye = caster.getEyePosition();
        final Vec3 look = caster.getLookAngle().normalize();
        final Vec3 up = Math.abs(look.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        final Vec3 axisA = look.cross(up).normalize();
        final Vec3 axisB = axisA.cross(look).normalize();
        final double halfAngle = Math.toRadians(halfAngleDegrees);
        final ParticleOptions shard = memoryShardDust();
        final ParticleOptions haze = new DustParticleOptions(INDIGO_VECTOR, 1.3F);

        final int shells = 5;
        for (int shell = 1; shell <= shells; shell++) {
            final double distance = range * shell / shells;
            final double radius = distance * Math.tan(halfAngle);
            final Vec3 center = eye.add(look.scale(distance));
            final int count = 6 + shell * 2;
            for (int i = 0; i < count; i++) {
                final double angle = TWO_PI * i / count;
                final Vec3 offset = axisA.scale(Math.cos(angle) * radius)
                        .add(axisB.scale(Math.sin(angle) * radius));
                serverLevel.sendParticles(haze,
                        center.x + offset.x, center.y + offset.y, center.z + offset.z,
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
        // 轴线：把"锥尖 → 锥底"这条中轴补上，否则锥壳看起来是悬空的几圈
        for (int i = 0; i <= shells; i++) {
            final Vec3 p = eye.add(look.scale(range * i / shells));
            serverLevel.sendParticles(shard, p.x, p.y, p.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /**
     * 两点之间的光带 —— 表达"我锁定了它 / 我在引爆它"。
     *
     * <p>⚠️ 同上：{@code sendParticles} 的 {@code speed} 只能给随机方向初速度，
     * 所以方向只能靠**逐点铺位置**表达，不能靠速度。
     *
     * @param points 采样点数（含两端），内部钳到 2~40
     */
    public static void beam(final Level level, final Vec3 from, final Vec3 to, final int points) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final int n = Math.max(2, Math.min(points, 40));
        final ParticleOptions shard = memoryShardDust();
        for (int i = 0; i <= n; i++) {
            final Vec3 p = from.lerp(to, (double) i / n);
            serverLevel.sendParticles(shard, p.x, p.y, p.z, 1, 0.03D, 0.03D, 0.03D, 0.0D);
        }
    }

    /**
     * 按**层数**引爆 —— 把"我兑现了几层认知过载"直接画成规模。
     *
     * <p>用在哪：{@code 认知崩坏} 的伤害是 {@code 每层伤害 × 层数}，
     * 但玩家看不见层数（只有一个效果图标）。这里让**爆发的规模 = 层数**：
     * {@code stacks} 圈同心水平环，一圈比一圈大、一圈比一圈高，靛蓝与品红交替；
     * 再加一根高度也是 {@code 3 × stacks} 的竖直粒子柱。
     * 于是"这发打了几层"变成一眼可读的信息，而不是只能靠伤害数字猜。
     *
     * <p>⚠️ 6 层时约 105 + 18 粒。这是全流派最贵的一次爆发，但它一发一结算、
     * 且是"终结技"，可以接受。层数上限 {@code Config.Overload.MAX_STACKS_IN_SEA} 默认 15，
     * 所以这里必须钳到 8 —— 否则 15 层会瞬间打 500+ 粒。
     */
    public static void stackDetonation(final Level level, final Vec3 center, final int stacks) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final int rings = Math.max(1, Math.min(stacks, 8));
        // ① 爆闪：一帧白光，给"炸了"一个明确的瞬间
        serverLevel.sendParticles(ParticleTypes.FLASH, center.x, center.y, center.z,
                1, 0.0D, 0.0D, 0.0D, 0.0D);
        // ② 同心环：半径与高度都随环号增长 → 规模即层数
        for (int ring = 0; ring < rings; ring++) {
            final double radius = 0.55D + ring * 0.35D;
            final double y = center.y + ring * 0.18D;
            final int count = 10 + ring * 3;
            final ParticleOptions dust = (ring & 1) == 0
                    ? new DustParticleOptions(INDIGO_VECTOR, 1.2F)
                    : new DustParticleOptions(MAGENTA_VECTOR, 1.0F);
            for (int i = 0; i < count; i++) {
                final double angle = TWO_PI * i / count;
                serverLevel.sendParticles(dust,
                        center.x + Math.cos(angle) * radius, y, center.z + Math.sin(angle) * radius,
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
        // ③ 竖直柱：高度同样 = 层数
        for (int i = 0; i < rings * 3; i++) {
            serverLevel.sendParticles(ParticleTypes.SCULK_CHARGE_POP,
                    center.x, center.y + 0.3D + i * 0.25D, center.z,
                    1, 0.08D, 0.05D, 0.08D, 0.02D);
        }
    }

    /**
     * 忆格获得 —— 绕身体升起的"刻痕"环，环数 = 获得几格。
     *
     * <p>用在哪：{@code 忆格扩张}（临时忆格 2~4）与 {@code 碎忆}（+1）。
     * 这两个法术改的是**内部计数**，改完屏幕上什么都没有 ——
     * 玩家只能靠动作栏那行 {@code 忆格 N / M} 才知道生效了。
     * 这个环是"加了几格就转几圈"，配合动作栏给一个立刻的视觉确认。
     */
    public static void engramGain(final Level level, final LivingEntity target, final int slots) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final int rings = Math.max(1, Math.min(slots, 8));
        final Vec3 base = target.position();
        final double height = target.getBbHeight();
        final ParticleOptions shard = memoryShardDust();
        for (int ring = 0; ring < rings; ring++) {
            final double y = base.y + height * (0.25D + 0.6D * ring / rings);
            final int count = 8;
            for (int i = 0; i < count; i++) {
                final double angle = TWO_PI * i / count + ring * 0.5D;
                serverLevel.sendParticles(shard,
                        base.x + Math.cos(angle) * 0.75D, y, base.z + Math.sin(angle) * 0.75D,
                        1, 0.0D, 0.02D, 0.0D, 0.0D);
            }
        }
        serverLevel.sendParticles(ParticleTypes.END_ROD,
                base.x, base.y + height + 0.2D, base.z, rings * 3, 0.25D, 0.15D, 0.25D, 0.03D);
    }

    /**
     * 「发呆 / 眩晕」的持续提示 —— 每若干 tick 调一次，在实体头顶冒记忆碎屑。
     *
     * <p>用在哪：{@code 失忆} 的发呆、{@code 认知崩坏} 的眩晕。
     * 这两个状态的原版表现只有一个「缓慢」图标，玩家分不清"它被控住了"
     * 和"它刚好没动"。头顶的碎屑是唯一能持续说明"它正在被读记忆"的东西。
     *
     * <p>⚠️ 调用方自己控制频率（建议每 5 tick），不要在每 tick 的循环里直接调。
     */
    public static void dazeAura(final LivingEntity target) {
        if (!(target.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        final Vec3 head = target.getEyePosition().add(0.0D, 0.45D, 0.0D);
        serverLevel.sendParticles(memoryShardDust(), head.x, head.y, head.z,
                3, 0.18D, 0.05D, 0.18D, 0.01D);
        serverLevel.sendParticles(ParticleTypes.SCULK_CHARGE_POP, head.x, head.y, head.z,
                1, 0.12D, 0.02D, 0.12D, 0.0D);
    }

    /**
     * 「重放」的脉冲 —— 每成功重演一条法术，就在施法者身上荡开一圈环；
     * 第 N 圈比第 N−1 圈更高、更大。
     *
     * <p>用在哪：{@code 既视感}。它把过去 6 秒内放过的法术**按原顺序瞬间重演一遍**，
     * 而重演发生在同一 tick 里 —— 屏幕上原本只有一行动作栏文字，
     * 玩家完全看不出"刚才连了几发"。环数 = 重演条数，于是"连招长度"一眼可读。
     *
     * @param index 第几次重演（从 0 开始），决定环的高度与半径
     */
    public static void replayPulse(final Level level, final LivingEntity caster, final int index) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final Vec3 base = caster.position();
        final double y = base.y + 0.9D + index * 0.12D;
        final double radius = 1.0D + index * 0.18D;
        final int count = 12 + index * 2;
        final ParticleOptions dust = (index & 1) == 0
                ? memoryShardDust()
                : new DustParticleOptions(MAGENTA_VECTOR, 1.1F);
        for (int i = 0; i < count; i++) {
            final double angle = TWO_PI * i / count;
            serverLevel.sendParticles(dust,
                    base.x + Math.cos(angle) * radius, y, base.z + Math.sin(angle) * radius,
                    1, 0.0D, 0.02D, 0.0D, 0.0D);
        }
    }

    /**
     * 「法术上限 +N」的刻写表现 —— 绕施法者升起 N 条附魔字符柱。
     *
     * <p>用在哪：{@code 铭忆}。它同时做两件事：{@code +1 临时忆格}（走 {@link #engramGain}）
     * 与 {@code +N 法术上限}。后者改的是**装备层**（那本法术书能装几个法术），
     * 屏幕上完全没有落点。这里用 {@code ENCHANT} —— 原版唯一带"刻写 / 附魔"语义的粒子 ——
     * 绕体升起 N 条字符柱，与忆格的尘环在观感上区分开。
     */
    public static void spellSlotMark(final Level level, final LivingEntity caster, final int bonus) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final int lines = Math.max(1, Math.min(bonus, 6));
        final Vec3 base = caster.position();
        final double height = caster.getBbHeight();
        for (int line = 0; line < lines; line++) {
            final double angle = TWO_PI * line / lines;
            final double x = base.x + Math.cos(angle) * 0.6D;
            final double z = base.z + Math.sin(angle) * 0.6D;
            for (int step = 0; step < 6; step++) {
                serverLevel.sendParticles(ParticleTypes.ENCHANT,
                        x, base.y + 0.4D + height * 0.6D * step / 5.0D, z,
                        1, 0.02D, 0.02D, 0.02D, 0.0D);
            }
        }
    }

    // ==================================================================
    // 写入 / 掠夺：把东西从目标身上搬到施法者身上
    // ==================================================================

    /**
     * 「抽取链」—— 从 {@code from} 到 {@code to} 的一条链，**越靠近接收端越密**。
     *
     * <p>用在哪：{@code 质忆}（把目标的一项特质写进忆格）与 {@code 记忆掠夺}
     * （把目标身上的药水效果抢过来）。这两件事都是"东西从对方身上离开、到我这里"，
     * 与 {@link #beam}（锁定 / 引爆，两端对称）语义不同 ——
     * 所以链的密度做成**单向梯度**：起点稀、后半程变密，读起来就是"被吸过来了"。
     * 终点再补一簇 {@code END_ROD} 作为"到手了"的落点。
     *
     * <p>⚠️ 与 {@link #beam} 同理：方向只能靠**逐点铺位置**表达，
     * {@code sendParticles} 的 {@code speed} 只是随机方向初速度。
     *
     * @param points 采样点数（含两端），内部钳到 2~40
     */
    public static void extractBeam(final Level level, final LivingEntity from,
                                   final LivingEntity to, final int points) {
        final Vec3 a = chest(from);
        final Vec3 b = chest(to);
        extractBeam(level, a, b, points, true);
    }

    /**
     * 「稀薄抽取」—— 只抽到**半条**，且终点没有落点光。
     *
     * <p>用在哪：{@code 质忆} 的降级路径（目标是 BOSS、或探测不到任何可借之物，
     * 此时写入的是 {@code mnemosyne:generic} 通用记忆）。
     * 这条路径过去与正常写入**视觉上完全一样**，玩家只能事后发现
     * "这条记忆复诵了没反应"。链短一半 + 没有落点光 = 当场就能看出拿到的是次品。
     */
    public static void weakExtractBeam(final Level level, final LivingEntity from,
                                       final LivingEntity to) {
        final Vec3 a = chest(from);
        final Vec3 b = chest(to);
        // 只画到 55% 处 —— "没抽满"这件事必须看得出来
        extractBeam(level, a, a.lerp(b, 0.55D), 12, false);
    }

    /**
     * 抽取链的公共实现。
     *
     * @param arrival 是否在终点补"到手"落点光
     */
    private static void extractBeam(final Level level, final Vec3 from, final Vec3 to,
                                    final int points, final boolean arrival) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final int n = Math.max(2, Math.min(points, 40));
        final ParticleOptions shard = memoryShardDust();
        for (int i = 0; i <= n; i++) {
            final double t = (double) i / n;
            final Vec3 p = from.lerp(to, t);
            serverLevel.sendParticles(shard, p.x, p.y, p.z, 1, 0.03D, 0.03D, 0.03D, 0.0D);
            // 密度梯度：后半程补一颗 → 越靠近接收端越密（"被吸过去"）
            if (t > 0.55D) {
                serverLevel.sendParticles(new DustParticleOptions(INDIGO_VECTOR, 1.1F),
                        p.x, p.y, p.z, 1, 0.05D, 0.05D, 0.05D, 0.0D);
            }
        }
        if (arrival) {
            serverLevel.sendParticles(ParticleTypes.END_ROD,
                    to.x, to.y, to.z, 6, 0.2D, 0.2D, 0.2D, 0.03D);
        }
    }

    /**
     * 「掠夺到手」—— 绕施法者升起 N 圈环，**圈数 = 抢到的效果数**。
     *
     * <p>用在哪：{@code 记忆掠夺}（1~2 个药水效果）。它把目标身上的正面效果
     * 连等级带时长抢到自己身上，但屏幕上原本只有一行动作栏文字。
     *
     * <p>形状与 {@link #engramGain} 相同（都是"圈数 = 数量"），但**配色刻意分开**：
     * 忆格用双色记忆碎片尘，掠夺用品红尘 + {@code CRIMSON_SPORE}
     *（像从对方身上撕下来的东西）。两者不会同时出现，但颜色差异让
     * "我多了几格忆格"与"我抢到了几个效果"在事后回忆里也分得开。
     */
    public static void stealPulse(final Level level, final LivingEntity caster, final int count) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final int rings = Math.max(1, Math.min(count, 8));
        final Vec3 base = caster.position();
        final double height = caster.getBbHeight();
        for (int ring = 0; ring < rings; ring++) {
            final double y = base.y + height * (0.3D + 0.5D * ring / rings);
            final int points = 10;
            for (int i = 0; i < points; i++) {
                final double angle = TWO_PI * i / points + ring * 0.4D;
                serverLevel.sendParticles(new DustParticleOptions(MAGENTA_VECTOR, 1.2F),
                        base.x + Math.cos(angle) * 0.8D, y, base.z + Math.sin(angle) * 0.8D,
                        1, 0.0D, 0.02D, 0.0D, 0.0D);
            }
        }
        serverLevel.sendParticles(ParticleTypes.CRIMSON_SPORE,
                base.x, base.y + height * 0.7D, base.z, rings * 4, 0.35D, 0.25D, 0.35D, 0.01D);
    }

    /**
     * 「记录中」的持续提示 —— 每若干 tick 调一次，在施法者身上绕一圈品红尘。
     *
     * <p>用在哪：{@code 痛忆}。它开启一个 6~10 秒的记录窗口，窗口内挨的每一下
     * 都会**当场奉还**给攻击者。但窗口本身**没有任何原版表现** ——
     * 玩家放完法术，屏幕上什么都不变，直到挨打才知道"原来我还在记录状态"。
     *
     * <p>这与 {@link #dazeAura} 是同一类问题（状态存在但不可见），
     * 所以用同一个"持续微光"手法，只换配色：品红尘 + {@code CRIMSON_SPORE}（痛觉），
     * 与发呆的幽蓝碎屑区分开。
     *
     * <p>⚠️ 调用方自己控制频率（建议每 5 tick），不要在每 tick 里直接调。
     */
    public static void recordingAura(final LivingEntity caster) {
        if (!(caster.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        final Vec3 base = caster.position();
        final double y = base.y + caster.getBbHeight() * 0.6D;
        // 绕体缓慢旋转，避免每 5 tick 都打在同一个点上（那样看起来是静止的）
        final double angle = (caster.tickCount % 20) / 20.0D * TWO_PI;
        serverLevel.sendParticles(new DustParticleOptions(MAGENTA_VECTOR, 1.0F),
                base.x + Math.cos(angle) * 0.7D, y, base.z + Math.sin(angle) * 0.7D,
                2, 0.08D, 0.08D, 0.08D, 0.0D);
        serverLevel.sendParticles(ParticleTypes.CRIMSON_SPORE,
                base.x, y, base.z, 1, 0.25D, 0.2D, 0.25D, 0.0D);
    }

    /**
     * 实体胸口位置 —— 画锁定链 / 爆发统一用它，而不是脚底。
     *
     * <p>⭐ 抽成公共方法的理由：{@code 失忆}、{@code 认知崩坏}、{@code 千忆归一}
     * 三处都要"从施法者眼睛连到目标身上"。各自写一遍 {@code bbHeight * 0.5} 的话，
     * 迟早有一处会漂移成脚底（那样连线看起来是贴地的，很怪）。
     */
    public static Vec3 chest(final LivingEntity entity) {
        return entity.position().add(0.0D, entity.getBbHeight() * 0.5D, 0.0D);
    }

    // ==================================================================
    // 玩家提示（替代被删掉的忆格 GUI）
    // ==================================================================

    /**
     * 在**动作栏**（物品栏上方那行，不刷屏）给玩家一行提示。
     *
     * <p>这是忆格 GUI 删除后，忆格状态的主要反馈通道。
     */
    public static void actionBar(final ServerPlayer player, final Component message) {
        player.displayClientMessage(message, true);
    }

    /** 在聊天栏给玩家一行提示（重要信息，会留在聊天记录里）。 */
    public static void chat(final ServerPlayer player, final Component message) {
        player.displayClientMessage(message, false);
    }

    /** 法术失败：没有目标。**必须给反馈**，否则玩家以为 mod 坏了。 */
    public static void noTarget(final ServerPlayer player) {
        actionBar(player, Component.translatable("mnemosyne.feedback.no_target"));
    }

    /** 法术失败：目标免疫（BOSS）。 */
    public static void immune(final ServerPlayer player) {
        actionBar(player, Component.translatable("mnemosyne.feedback.immune"));
    }

    /**
     * 法术失败：目标身上没有「认知过载」层数。
     *
     * <p>⚠️ 2026-09-18 补：{@code 认知崩坏} 的伤害是 {@code 每层伤害 × 层数}，
     * 层数为 0 时原来**静默 return** —— 玩家花了 70 法力、等了 12 秒冷却，
     * 屏幕上什么都没有，只会以为 mod 坏了。这正是 {@code docs/tech/03} 里
     * "法术释放后无效果"的第 ② 类成因（目标条件不满足时静默 return）。
     */
    public static void noOverload(final ServerPlayer player) {
        actionBar(player, Component.translatable("mnemosyne.feedback.no_overload"));
    }

    /** 法术失败：忆格已满。 */
    public static void noFreeSlot(final ServerPlayer player) {
        actionBar(player, Component.translatable("mnemosyne.feedback.no_slot"));
    }

    // ==================================================================
    // 音效
    // ==================================================================

    /** 在施法者位置播一个音效（服务端广播）。 */
    public static void playAt(final Level level, final LivingEntity source, final SoundEvent sound,
                              final float volume, final float pitch) {
        if (level.isClientSide) {
            return;
        }
        level.playSound(null, source.getX(), source.getY(), source.getZ(),
                sound, SoundSource.PLAYERS, volume, pitch);
    }

    /** 在任意坐标播音效。 */
    public static void playAt(final Level level, final double x, final double y, final double z,
                              final SoundEvent sound, final float volume, final float pitch) {
        if (level.isClientSide) {
            return;
        }
        level.playSound(null, x, y, z, sound, SoundSource.PLAYERS, volume, pitch);
    }

    /** 方块位置的粒子（忆碑、忆晶簇这类）。 */
    public static void blockBurst(final Level level, final BlockPos pos, final ParticleOptions particle,
                                  final int count) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        serverLevel.sendParticles(particle,
                pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D,
                count, 0.4D, 0.4D, 0.4D, 0.02D);
    }
}
