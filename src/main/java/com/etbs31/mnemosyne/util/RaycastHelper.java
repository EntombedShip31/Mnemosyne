package com.etbs31.mnemosyne.util;

import io.redspace.ironsspellbooks.api.util.RaycastBuilder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * 射线与目标选择的薄封装。
 *
 * <p><b>文件归属</b>：WS-C 法术基类。
 *
 * <p><b>实测</b>：ISS 的 {@code io.redspace.ironsspellbooks.api.util.RaycastBuilder}
 * **在 api 包内，可直接使用**（javap 确认：`begin` / `start` / `end` / `range` /
 * `checkForBlocks` / `bbInflation` / `filter` / `build` / `performRaycast` 全部 public）。
 *
 * <p><b>为什么还要包一层</b>：16 个法术里绝大多数都要"从视线出发、排除自己、可选排除友军"
 * 这一套，直接调 {@code RaycastBuilder} 每个法术都要写 6~8 行链式调用。
 * 这里把最常用的三种形态固化成方法，各法术只传参数。
 *
 * <p>各法术的推荐参数见 {@code docs/tech/03_法术实现规范.md} §五。
 */
public final class RaycastHelper {

    private RaycastHelper() {}

    /** 命中率用的碰撞箱膨胀系数。ISS 官方常用的值，比 0 明显好命中，又不会打到方块后面。 */
    public static final float DEFAULT_BB_INFLATION = 0.25F;

    /**
     * 从施法者视线出发做射线检测。
     *
     * @param range       射程（格）
     * @param checkBlocks 是否被方块阻挡
     * @param filter      目标筛选；传 {@code null} 表示只排除施法者自己
     * @return 命中的实体，未命中返回 {@code null}
     */
    @Nullable
    public static Entity findEntity(final Level level, final LivingEntity caster,
                                    final float range, final boolean checkBlocks,
                                    @Nullable final Predicate<Entity> filter) {
        final HitResult hit = RaycastBuilder
                .begin(level, caster)
                .range(range)
                .checkForBlocks(checkBlocks)
                .bbInflation(DEFAULT_BB_INFLATION)
                .filter(e -> e != caster && (filter == null || filter.test(e)))
                .performRaycast();

        return hit instanceof EntityHitResult entityHit ? entityHit.getEntity() : null;
    }

    /**
     * 射线并只接受活体目标。
     *
     * @param excludeAllies 是否排除友军（同队伍、同主人、同为玩家）
     */
    @Nullable
    public static LivingEntity findLivingTarget(final Level level, final LivingEntity caster,
                                                final float range, final boolean checkBlocks,
                                                final boolean excludeAllies) {
        final Entity hit = findEntity(level, caster, range, checkBlocks,
                e -> e instanceof LivingEntity living
                        && (!excludeAllies || !isAlly(caster, living)));
        return hit instanceof LivingEntity living ? living : null;
    }

    /**
     * 球形范围内的活体目标。用于「集体遗忘」「遗忘诅咒」这类范围法术。
     *
     * @param excludeAllies 是否排除友军
     */
    public static List<LivingEntity> findLivingInSphere(final Level level, final Vec3 center,
                                                        final double radius, final LivingEntity caster,
                                                        final boolean excludeAllies) {
        final AABB box = new AABB(center, center).inflate(radius);
        return level.getEntitiesOfClass(LivingEntity.class, box, living ->
                        living.isAlive()
                                && living.distanceToSqr(center) <= radius * radius
                                && (!excludeAllies || !isAlly(caster, living)))
                .stream()
                .toList();
    }

    /**
     * 简易友军判定。
     *
     * <p>规则（从宽到严）：
     * <ol>
     *   <li>同一个实体 → 是</li>
     *   <li>两个都是玩家 → 是（不做 PVP 服务器判定；需要精确判定时由法术自己覆写）</li>
     *   <li>同一个主人（{@link OwnableEntity}）→ 是</li>
     *   <li>同一个队伍 → 是</li>
     *   <li>否则 → 否</li>
     * </ol>
     */
    public static boolean isAlly(final Entity a, final Entity b) {
        if (a == b) {
            return true;
        }
        if (a instanceof Player && b instanceof Player) {
            return true;
        }
        if (a instanceof OwnableEntity ownA && b instanceof OwnableEntity ownB) {
            final Entity ownerA = ownA.getOwner();
            final Entity ownerB = ownB.getOwner();
            if (ownerA != null && ownerA == ownerB) {
                return true;
            }
        }
        return a.isAlliedTo(b);
    }
}
