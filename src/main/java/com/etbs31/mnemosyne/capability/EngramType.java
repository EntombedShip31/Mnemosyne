package com.etbs31.mnemosyne.capability;

/**
 * 三种记忆的类型。
 *
 * <p><b>文件归属</b>：WS-B 忆格系统。
 *
 * <p><b>契约（冻结，{@code docs/tech/11} §六）</b>：枚举值只有
 * {@code SPELL} / {@code ESSENCE} / {@code PAIN} 三个。
 * <b>不要加第四个</b> —— 三种记忆对应三条支柱（收集 → 削弱 → 爆发），
 * 加第四种会破坏"忆格稀缺"这条设计红线。
 *
 * <p><b>NBT 里的序列化名</b>用小写（{@code "spell"} / {@code "essence"} / {@code "pain"}），
 * 与 {@code docs/tech/03} §6.2 的结构一致。注意设计文档里"质忆"的英文是
 * Essence Memory，所以是 {@code ESSENCE} 而不是 {@code TRAIT}。
 */
public enum EngramType {

    /** 术忆：记住"敌人在做什么"。⚠️ 2026-09-18：术忆/复诵已删除，本类型暂无生产者。 */
    SPELL("spell", 0x534AB7),

    /** 质忆：记住"敌人是什么"。存一个特性，暂时借用。 */
    ESSENCE("essence", 0xD4537E),

    /** 痛忆：记住"敌人怎么伤害我"。存一段伤害，原样奉还。 */
    PAIN("pain", 0xE8A33D);

    private final String serializedName;
    private final int color;

    EngramType(final String serializedName, final int color) {
        this.serializedName = serializedName;
        this.color = color;
    }

    /** NBT 里的类型标记。 */
    public String getSerializedName() {
        return serializedName;
    }

    /** HUD / 粒子的类型色（靛蓝 / 品红 / 琥珀）。 */
    public int getColor() {
        return color;
    }

    /** 语言键：{@code engram.mnemosyne.type.<name>}。 */
    public String getTranslationKey() {
        return "engram.mnemosyne.type." + serializedName;
    }

    /**
     * 从 NBT 标记反查类型。
     *
     * @return 未知标记返回 {@code null}（调用方必须处理 —— 不要抛异常，
     *         否则一个被改坏存档就能让玩家进不了游戏）
     */
    public static EngramType byName(final String name) {
        for (final EngramType type : values()) {
            if (type.serializedName.equals(name)) {
                return type;
            }
        }
        return null;
    }
}
