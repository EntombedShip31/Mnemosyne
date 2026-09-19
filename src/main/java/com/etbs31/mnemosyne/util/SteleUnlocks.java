package com.etbs31.mnemosyne.util;

import com.etbs31.mnemosyne.registry.ModSchools;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 忆碑的"记起一个法术"抽签逻辑。
 *
 * <p><b>文件归属</b>：WS-J 方块层（忆碑交互）。
 *
 * <p><b>契约来源</b>：{@code docs/08_建筑_忆者遗迹.md} §6.3。
 * <pre>
 *   Common 40% | Uncommon 30% | Rare 20% | Epic 8% | Legendary 2%
 *   保底：玩家读的第一座忆碑必定解锁一个 Common
 *   去重：不会解锁玩家已经拥有的法术；若该稀有度已集齐，权重自动重新分配
 * </pre>
 *
 * <p><b>⭐ "第一座忆碑"怎么判定</b>
 * <br>没有现成的字段能表达它，所以本类在**玩家自己的 {@code getPersistentData()}** 里
 * 记一个计数器（键 {@code mnemosyne_stele_read}）。
 * 刻意<b>不</b>写进 WS-B 的 {@code mnemosyne_engram} 根键 ——
 * 那是忆格系统的存档结构，动它会让老存档的忆格全部丢失
 * （见 {@code docs/tech} 与项目记忆里的硬约束）。用独立键，互不干扰。
 *
 * <p><b>⭐ 稀有度取 {@code getRarity(1)} 而不是 {@code getMinRarity()}</b>
 * <br>{@code getMinRarity()} 被标了 {@code @Deprecated(forRemoval = true)}。
 * {@code getRarity(1)} 在 {@code minRarity} 上等价（源码：
 * {@code if (maxLevel == 1) return values()[minRarity];} 之后按
 * {@code percentOfMaxLevel = 1/maxLevel} 落入第一档），但走的是未废弃的公开路径。
 */
public final class SteleUnlocks {

    private SteleUnlocks() {}

    /** 玩家 NBT 键：累计读过多少座忆碑。 */
    private static final String NBT_READ_COUNT = "mnemosyne_stele_read";

    /**
     * 五个稀有度的相对权重，下标 = {@code SpellRarity.getValue()}：
     * COMMON=0 / UNCOMMON=1 / RARE=2 / EPIC=3 / LEGENDARY=4。
     */
    /**
     * 五档稀有度权重（index = 稀有度 - 1）。
     *
     * <p>⭐ 2026-09-18 接入 Config.Loot.LEGENDARY_WEIGHT：
     *    第 5 档（Legendary）原本硬编码为 2（总权重 100 → 2%），
     *    改读配置后玩家能全局调整"忆碑抽出绝学的概率"。
     *
     * <p>实现取舍：用 {@link #currentWeights()} 在每次抽取时**动态算**权重，
     *    而不是用 {@code static final} 缓存 —— 因为配置文件可能在游戏运行期间
     *    被 mod 菜单修改（{@code ModConfigEvent.Reloading}），缓存会过期。
     *    5 元素的小数组，开销可忽略。
     */
    private static int[] currentWeights() {
        // 把概率（0.0~1.0）换算成"总权重 100 下的整数权重"。其它四档保持原配比。
        final int legendary = Math.max(0, (int) Math.round(
                com.etbs31.mnemosyne.Config.Loot.LEGENDARY_WEIGHT.get() * 100));
        return new int[]{40, 30, 20, 8, legendary};
    }

    /** 玩家累计读过的忆碑数。 */
    public static int readCount(final Player player) {
        return player.getPersistentData().getInt(NBT_READ_COUNT);
    }

    /** 记一次读取，返回**自增后**的计数（1 表示这是第一座）。 */
    public static int incrementReadCount(final Player player) {
        final int next = readCount(player) + 1;
        player.getPersistentData().putInt(NBT_READ_COUNT, next);
        return next;
    }

    /**
     * 抽一个"玩家还没学会的、属于忆海学派的"法术。
     *
     * @return 抽中的法术；若忆海所有法术都已被该玩家学会，返回 {@code null}
     */
    @Nullable
    public static AbstractSpell rollFor(final ServerPlayer player, final RandomSource random) {
        final List<AbstractSpell> school = SpellRegistry.getSpellsForSchool(ModSchools.MEMORY.get());
        if (school.isEmpty()) {
            return null;
        }

        // 按稀有度分桶，同时排除"已学会"与"不需要学习"的。
        final int[] weights = currentWeights();
        final List<List<AbstractSpell>> buckets = new ArrayList<>(weights.length);
        for (int i = 0; i < weights.length; i++) {
            buckets.add(new ArrayList<>());
        }
        for (final AbstractSpell spell : school) {
            // 不需要学习的法术（requiresLearning=false）本来就能随便抄，抽到它等于什么都没给。
            if (!spell.requiresLearning()) {
                continue;
            }
            // ⭐ 去重：isLearned 走的是 api 层
            //    （AbstractSpell.isLearned → MagicData.getPlayerMagicData(p).getSyncedData().isSpellLearned）
            //    所以这里不需要 import 非 api 的 SyncedSpellData。
            if (spell.isLearned(player)) {
                continue;
            }
            final int rarity = spell.getRarity(1).getValue();
            if (rarity >= 0 && rarity < buckets.size()) {
                buckets.get(rarity).add(spell);
            }
        }

        // 保底：第一座碑必定给 Common（若 Common 已集齐则退回正常权重）。
        final boolean firstEver = readCount(player) == 0;
        if (firstEver && !buckets.get(0).isEmpty()) {
            return pick(buckets.get(0), random);
        }

        // 权重抽样：空桶权重归零，等价于"权重自动重新分配"。
        int total = 0;
        for (int i = 0; i < buckets.size(); i++) {
            if (!buckets.get(i).isEmpty()) {
                total += weights[i];
            }
        }
        if (total <= 0) {
            return null;
        }

        int roll = random.nextInt(total);
        for (int i = 0; i < buckets.size(); i++) {
            final List<AbstractSpell> bucket = buckets.get(i);
            if (bucket.isEmpty()) {
                continue;
            }
            roll -= weights[i];
            if (roll < 0) {
                return pick(bucket, random);
            }
        }
        // 理论上不可达（total 已保证落点存在），兜底返回最后一个非空桶。
        for (int i = buckets.size() - 1; i >= 0; i--) {
            if (!buckets.get(i).isEmpty()) {
                return pick(buckets.get(i), random);
            }
        }
        return null;
    }

    private static AbstractSpell pick(final List<AbstractSpell> bucket, final RandomSource random) {
        return bucket.get(random.nextInt(bucket.size()));
    }
}
