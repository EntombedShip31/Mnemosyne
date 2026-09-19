package com.etbs31.mnemosyne.oblivion;

import com.etbs31.mnemosyne.Config;

/**
 * 遗忘的三个层级 —— 把 {@code docs/02} §二 的四张表压缩成**一个可穷尽枚举**。
 *
 * <p><b>文件归属</b>：WS-E 遗忘系统。
 *
 * <p><b>为什么需要它</b>：{@link OblivionManager#applyOblivion} 的冻结契约收的是一个
 * {@code int tier}（{@code docs/tech/12} 提示词 A），而"移除几个行为 / 要不要清仇恨 /
 * 有没有范围 / 用哪个状态效果"这四件事在四个法术之间并不一致。
 * 把它们散在 {@code switch} 里，将来 WS-D 加第五个遗忘法术时必然漏掉某一处；
 * 收进枚举后，加层级只要加一个枚举值，所有分支**编译期就会报错**提醒你补齐。
 *
 * <p><b>tier 号与法术的对应（{@code OblivionSpell.getOblivionTier()} 的返回值）</b>：
 * <table border="1">
 *   <tr><th>tier</th><th>法术</th><th>效果</th></tr>
 *   <tr><td>1</td><td>遗忘</td><td>随机移除 1 个 AI 行为，单体</td></tr>
 *   <tr><td>2</td><td>失忆 / 遗忘诅咒</td><td>禁用全部特殊能力 + 清仇恨</td></tr>
 *   <tr><td>3</td><td>集体遗忘</td><td>同 tier 2，半径 {@code MASS_RADIUS} 格</td></tr>
 * </table>
 *
 * <p><b>⚠️ 遗忘诅咒（tier 2 领域）怎么用</b>：它的规则是"每 2 秒随机遗忘**一个**能力，
 * 最多同时 3 个"（{@code docs/02} §2.4），所以 WS-D2 在领域的每一跳里应当调
 * {@code applyOblivion(caster, target, 1)}（tier 1 的"随机一个"语义），
 * 由法术自己维护"最多 3 个"的计数，而不是用 tier 2 一次性清空 ——
 * 那会让"持续压制"变成"一键瘫痪"，强度差了一个数量级。
 */
public enum OblivionTier {

    /**
     * 遗忘：随机移除 **1 个** AI 行为。
     *
     * <p>⚠️ 下面三个常量的构造参数里**必须写字面量**（{@code -1} / {@code 8.0D}）而不能引用
     * {@link #ALL_GOALS} / {@link #MASS_RADIUS} —— 实测 javac 会报
     * "非法前向引用"（枚举常量的参数里引用本枚举后置的静态字段，
     * 即使它是编译期常量也不允许，见 JLS 8.9.2）。
     * 两个命名常量仍然对外提供，供调用方使用。
     */
    FORGET(1, 1, false, 0.0D, OblivionEffect.Mode.FORGET),

    /** 失忆：禁用**全部**特殊能力，并清空仇恨。 */
    AMNESIA(2, -1, true, 0.0D, OblivionEffect.Mode.AMNESIA),

    /** 集体遗忘：同失忆，但作用范围为半径 8 格。 */
    MASS_AMNESIA(3, -1, true, 8.0D, OblivionEffect.Mode.AMNESIA);

    /** {@link #maxGoals()} 的哨兵值：移除该生物**全部**已识别的特殊行为。 */
    public static final int ALL_GOALS = -1;

    /** 集体遗忘的作用半径（格），取自 {@code docs/02} §2.3。 */
    public static final double MASS_RADIUS = 8.0D;

    private final int id;
    private final int maxGoals;
    private final boolean clearsTarget;
    private final double radius;
    private final OblivionEffect.Mode effectMode;

    OblivionTier(final int id, final int maxGoals, final boolean clearsTarget,
                 final double radius, final OblivionEffect.Mode effectMode) {
        this.id = id;
        this.maxGoals = maxGoals;
        this.clearsTarget = clearsTarget;
        this.radius = radius;
        this.effectMode = effectMode;
    }

    /**
     * 把法术传来的 {@code int tier} 映射成枚举。
     *
     * <p>未知值**降级到 tier 1** 而不是抛异常：遗忘是一次战斗内的高频调用，
     * 为了一个写错的数字让整局崩溃不值得，而 tier 1 是最保守的语义。
     */
    public static OblivionTier byId(final int tier) {
        return switch (tier) {
            case 2 -> AMNESIA;
            case 3 -> MASS_AMNESIA;
            default -> FORGET;
        };
    }

    /** tier 号，与 {@code OblivionSpell.getOblivionTier()} 一致。 */
    public int id() {
        return id;
    }

    /** 小写下划线名，用于 lang 键与日志（{@code forget} / {@code amnesia} / {@code mass_amnesia}）。 */
    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 单次施法最多移除几个"能力类别"。
     *
     * @return {@link #ALL_GOALS} 表示全部
     */
    public int maxGoals() {
        return maxGoals;
    }

    /** 是否"随机挑一个"而不是"全部清掉"。 */
    public boolean picksRandomAbility() {
        return maxGoals > 0;
    }

    /** 是否要清空攻击目标（"忘记你在打谁"）。 */
    public boolean clearsTarget() {
        return clearsTarget;
    }

    /** 作用半径（格）。{@code 0} = 单体。 */
    public double radius() {
        return radius;
    }

    /** 承载本层级的状态效果。 */
    public OblivionEffect.Mode effectMode() {
        return effectMode;
    }

    /**
     * 持续时间（tick）。
     *
     * <p>与 {@code OblivionSpell.getDurationTicks()} 保持同一套配置来源：
     * tier 1 读 {@code oblivion.forgetSeconds}，tier 2/3 读 {@code oblivion.amnesiaSeconds}。
     */
    public int durationTicks() {
        final int seconds = id <= 1
                ? Config.Oblivion.FORGET_SECONDS.get()
                : Config.Oblivion.AMNESIA_SECONDS.get();
        return seconds * 20;
    }
}
