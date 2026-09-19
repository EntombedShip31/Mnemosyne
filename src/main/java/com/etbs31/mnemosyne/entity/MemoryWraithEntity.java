package com.etbs31.mnemosyne.entity;

import com.etbs31.mnemosyne.Config;
import com.etbs31.mnemosyne.registry.ModSchools;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 忆魇（Memory Wraith）—— 忆者遗迹的专属怪物。
 *
 * <p><b>文件归属</b>：WS-J 实体层。
 *
 * <p><b>⚠️⚠️ 设计冲突未裁决 —— 读这段再决定要不要让它刷</b>
 * <br>{@code docs/08_建筑_忆者遗迹.md} §7.3 明确写着：
 * <blockquote>遗迹<b>不生成任何新的怪物</b>。它是一个<b>纯粹的探索点</b>，而不是战斗副本。</blockquote>
 * 但 {@code docs/tech/06_世界生成.md} §5.2 的示例 JSON 和验收表第 11 条
 * （"专属怪物能刷 → 忆魇出现"）要求它在这里刷。
 * 而 {@code docs/tech/06} §八 把忆魇的设计指向 {@code docs/02}，
 * <b>可 {@code docs/02} 里根本没有忆魇的任何设定</b>（那份设计从未写出来）。
 *
 * <p>所以本类**只负责"实体存在且可用"**：
 * <ul>
 *   <li>可以通过刷怪蛋 / {@code /summon mnemosyne:memory_wraith} 生成；</li>
 *   <li>但 {@code worldgen/structure/memory_ruin.json} 的 {@code spawn_overrides}
 *       **保持为空** —— 服从 {@code docs/08} 的"纯探索点"红线。</li>
 * </ul>
 * 要开启自然刷怪，在 {@code spawn_overrides} 里加一条
 * {@code {"type": "mnemosyne:memory_wraith", "minCount": 1, "maxCount": 2, "weight": 10}}
 * 即可（一行 JSON）。<b>但在裁决这个冲突之前不要加。</b>
 *
 * <p><b>行为</b>：漂浮的飞行怪，无重力、走飞行寻路（与幻翼/蜜蜂同一套）。
 * 复用原版 {@code vex} 的三个音效 —— 不自建音效事件就不用往 {@code sounds.json}
 * 里加条目，少一处会静默失效的地方。
 *
 * <p><b>⚠️ 为什么它是 {@code Monster} 而不是 {@code FlyingMob}</b>
 * <br>需要 {@code targetSelector}（主动索敌）。{@code FlyingMob} 不带这套目标选择器，
 * 用它就得自己拼一整套，收益为零。
 */
public class MemoryWraithEntity extends Monster {

    /** 悬浮高度容差：飞行寻路用它判断"到位了没有"。 */
    private static final double FLY_SPEED = 0.6D;

    public MemoryWraithEntity(final EntityType<? extends MemoryWraithEntity> type, final Level level) {
        super(type, level);
        this.xpReward = 12;
        // 漂浮：无重力 + 飞行移动控制 + 飞行寻路。
        // ⚠️ 三者缺一不可 —— 只设 noGravity 而不换 MoveControl 的话，
        //    怪物会"悬在空中用走路的逻辑蹭地"，表现为原地抖动。
        this.moveControl = new FlyingMoveControl(this, 20, true);
        this.setNoGravity(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                // ⚠️ 设计文档 v2 §8.1：血量 20 → 100。
                //    20 血在忆海法术面前是"一箭就死"，而它的定位是"遗迹的守护者"。
                .add(Attributes.MAX_HEALTH, cfg(Config.Wraith.MAX_HEALTH, 100.0D))
                .add(Attributes.ATTACK_DAMAGE, 4.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.FLYING_SPEED, FLY_SPEED)
                .add(Attributes.FOLLOW_RANGE, cfg(Config.Wraith.FOLLOW_RANGE, 32.0D));
    }

    /**
     * 安全读取配置。
     *
     * <p>⚠️ <b>为什么不能直接 {@code Config.X.get()}</b>：{@code createAttributes()} 由
     * {@code EntityAttributeCreationEvent} 在**注册阶段**调用，而 Forge 的配置文件
     * 可能还没加载完 —— 那时 {@code ConfigValue.get()} 会抛 {@code IllegalStateException}，
     * 表现是**启动即崩**，而且报错信息完全指不到配置项。
     *
     * <p>所以这里退回默认值。代价是"配置改动需要重启才能影响属性"，
     * 这本来就是属性的固有性质（属性表在注册期构建一次）。
     */
    private static double cfg(final ForgeConfigSpec.DoubleValue value, final double fallback) {
        try {
            return value.get();
        } catch (final IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    @Override
    protected PathNavigation createNavigation(final Level level) {
        final FlyingPathNavigation navigation = new FlyingPathNavigation(this, level);
        navigation.setCanOpenDoors(false);
        navigation.setCanFloat(true);
        navigation.setCanPassDoors(true);
        return navigation;
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.2D, false));
        this.goalSelector.addGoal(5, new MoveTowardsRestrictionGoal(this, 1.0D));
        this.goalSelector.addGoal(7, new WaterAvoidingRandomFlyingGoal(this, 1.0D));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        // 被打了就还手 —— 这一条**中立时也保留**，否则它变成活靶子。
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));

        // ⚠️ 设计文档 v2 §8.1：中立生物（不主动攻击）。
        //    中立时**不加**主动索敌目标。注意别把 HurtByTargetGoal 一起删掉 ——
        //    "中立"的意思是"不主动挑事"，不是"不会还手"。
        if (!isNeutral()) {
            this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        }
    }

    /** 是否中立。配置未加载时按"中立"处理（设计文档 v2 的默认值）。 */
    private static boolean isNeutral() {
        try {
            return Config.Wraith.NEUTRAL.get();
        } catch (final IllegalStateException notLoadedYet) {
            return true;
        }
    }

    // ==================================================================
    // 伤害修正（设计文档 v2 §8.1）
    // ==================================================================

    /**
     * 只受忆海法术的满额伤害，其他一切来源固定只造成 1 点。
     *
     * <p><b>为什么这样设计</b>：忆魇是"记忆的守护者"，用剑砍它、用火烧它、
     * 让它摔死 —— 这些都与"记忆"无关，所以不该有用。想杀掉它，你必须用忆海的法术。
     * 这让它成为**检验玩家是否真的掌握了本学派**的敌人。
     *
     * <p>⚠️ 判定用**伤害类型**（{@code mnemosyne:memory}）而不是 {@code instanceof SpellDamageSource}：
     * 前者只需要一个 {@code ResourceKey}，不需要 import ISS 的非 api {@code damage} 包。
     * 代价是"任何使用忆海伤害类型的来源都算"—— 这正是我们想要的语义
     * （包括未来别的模组借用这个伤害类型的情况）。
     */
    @Override
    public boolean hurt(final DamageSource source, final float amount) {
        if (!level().isClientSide && !isMemoryDamage(source)) {
            try {
                if (Config.Wraith.ONLY_MEMORY_DAMAGE.get()) {
                    return super.hurt(source,
                            (float) Math.min(amount, Config.Wraith.UNIVERSAL_DAMAGE_CAP.get()));
                }
            } catch (final IllegalStateException notLoadedYet) {
                // 配置没加载时按"不限制"处理，绝不因为读配置失败而改变伤害语义
            }
        }
        return super.hurt(source, amount);
    }

    /** 这个伤害源是不是忆海学派的伤害。 */
    private static boolean isMemoryDamage(final DamageSource source) {
        return source.is(ModSchools.MEMORY_DAMAGE_TYPE);
    }

    // ==================================================================
    // 漂浮 → 不吃坠落伤害
    // ==================================================================

    @Override
    protected void checkFallDamage(final double y, final boolean onGround,
                                   final BlockState state, final BlockPos pos) {
        // 空实现：无重力生物不该累积坠落距离
    }

    @Override
    public boolean causeFallDamage(final float fallDistance, final float multiplier,
                                   final DamageSource source) {
        return false;
    }

    // ==================================================================
    // 音效 —— 复用原版 vex 的三件套
    // ==================================================================

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.VEX_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(final DamageSource source) {
        return SoundEvents.VEX_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.VEX_DEATH;
    }

    /** 比恼鬼低一档 —— 同一套音效但听感更沉、更"旧"。 */
    @Override
    public float getVoicePitch() {
        return 0.7F;
    }

    @Override
    public float getSoundVolume() {
        return 0.8F;
    }
}
