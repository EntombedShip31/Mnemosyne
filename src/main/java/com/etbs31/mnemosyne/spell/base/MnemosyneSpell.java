package com.etbs31.mnemosyne.spell.base;

import com.etbs31.mnemosyne.registry.ModSchools;
import io.redspace.ironsspellbooks.api.config.DefaultConfig;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
// ⚠️ 这是本项目**唯一**故意 import 的非 api 包。理由见 hurtWithSpellDamage 的注释：
//    DamageSources.applyDamage 是 ISS 伤害管线真正的入口，api 包里没有等价物。
//    它是 1.20.1 分支的稳定入口（60 个 ISS 内部文件依赖它），
//    比"自己 target.hurt(...)"更不容易随版本漂移。
import io.redspace.ironsspellbooks.damage.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * 忆海所有法术的公共父类。
 *
 * <p><b>文件归属</b>：WS-C 法术基类。
 *
 * <p><b>为什么要有这一层</b>：{@code docs/tech/10} §九 的反模式清单里明确写了
 * "让 WS-D1/D2/D3 共用一个文件放公共逻辑 → 公共逻辑上提到 WS-C 的基类"。
 * 16 个法术共享「持有 DefaultConfig」「按公式算伤害」「用学派伤害源打伤害」这三件事，
 * 所以它们上提到这里，而不是复制 18 份。
 *
 * <p><b>⭐ 实测：{@code AbstractSpell} 只有一个无参构造器</b>
 * （源码逐字：`public AbstractSpell() {}`）。
 * 所以 {@code docs/tech/03} §3.4 骨架里写的 {@code super(config)} **编译不过**。
 * 正确做法是本类这样：自己 {@code super()}，把 config 存成字段，覆写 {@code getDefaultConfig()}。
 *
 * <p><b>⭐ 伤害公式（逐字取自 {@code AbstractSpell.getSpellPower} 源码）</b>：
 * <pre>
 * getSpellPower(level, source) = (baseSpellPower + spellPowerPerLevel × (level-1))
 *                                × SPELL_POWER(实体) × 学派强度(实体) × POWER_MULTIPLIER
 * 实际伤害 = getSpellPower(level, caster) × 系数
 * </pre>
 * <b>永远不要硬编码伤害数字</b> —— 那样 {@code memory_spell_power} 属性会完全失效，平衡直接崩。
 */
public abstract class MnemosyneSpell extends AbstractSpell {

    private final DefaultConfig defaultConfig;

    protected MnemosyneSpell(final DefaultConfig defaultConfig) {
        super();
        this.defaultConfig = defaultConfig;
    }

    @Override
    public final DefaultConfig getDefaultConfig() {
        return defaultConfig;
    }

    // ==================================================================
    // 伤害计算
    // ==================================================================

    /**
     * 本等级的**抽象威力**（已含 SPELL_POWER × 学派强度 × POWER_MULTIPLIER 三层乘算）。
     *
     * <p>⚠️ 调用它会触发 {@code getSchoolType()}，因此**法术必须已经注册**。
     */
    protected final float powerOf(final int spellLevel, final LivingEntity caster) {
        return getSpellPower(spellLevel, caster);
    }

    /**
     * 本等级的**基础威力**（只含等级成长，不含施法者加成）：
     * {@code baseSpellPower + spellPowerPerLevel × (level - 1)}。
     *
     * <p>用途：给"伤害系数"提供一个不依赖施法者的基准值。
     * 见 {@link #powerMultiplierOf(int, LivingEntity)} 的说明。
     */
    protected final float basePowerOf(final int spellLevel) {
        return baseSpellPower + (float) spellPowerPerLevel * (spellLevel - 1);
    }

    /**
     * 施法者带来的**加成倍率** = {@code getSpellPower / getBasePower}，
     * 也就是 {@code SPELL_POWER × 学派强度 × POWER_MULTIPLIER} 三者之积。
     *
     * <p><b>为什么要拆出这个</b>：{@code docs/tech/04} 里每个法术都写了
     * "baseSpellPower / spellPowerPerLevel / 伤害系数"，伤害表是按
     * {@code 基础威力 × 系数} 算出来的。若直接写
     * {@code getSpellPower(...) × 系数}，就会把 baseSpellPower 算两次。
     * 正确拆法：
     * <pre>
     * 最终伤害 = (baseSpellPower + spellPowerPerLevel × (level-1)) × 系数 × 加成倍率
     *          = basePowerOf(level) × 系数 × powerMultiplierOf(level, caster)
     * </pre>
     */
    protected final float powerMultiplierOf(final int spellLevel, final LivingEntity caster) {
        final float base = basePowerOf(spellLevel);
        if (base <= 0.0F) {
            return 1.0F;
        }
        return powerOf(spellLevel, caster) / base;
    }

    /**
     * 标准伤害计算：{@code 抽象威力 × 系数}。
     *
     * @param coefficient 伤害系数，取自 {@code docs/tech/04} 的"伤害系数"行
     */
    protected final float damageOf(final int spellLevel, final LivingEntity caster, final float coefficient) {
        return powerOf(spellLevel, caster) * coefficient;
    }

    // ==================================================================
    // 打伤害：必须走学派伤害源
    // ==================================================================

    /**
     * 用**学派伤害源**打伤害。
     *
     * <p>⚠️ {@code docs/tech/02} §3.5 的硬性要求：必须走
     * {@code getDamageSource()}，否则 ISS 的伤害管线不会生效 ——
     * 目标的 {@code memory_magic_resist} 与 {@code SPELL_RESIST} 形同虚设，
     * {@code SpellDamageEvent} 不触发，伤害平衡直接崩。
     *
     * <p>⚠️ 这也是**为什么编译期依赖必须用 ISS 的完整 jar 而不是 {@code :api} 分类器** ——
     * {@code :api} jar 里缺 {@code SpellDamageSource} 这个类，用它编译不过。见 {@code build.gradle} 注释。
     *
     * <p><b>⭐⭐ 2026-09-17 修正：光有 {@code getDamageSource()} 还不够</b>
     * <br>原先的实现是 {@code target.hurt(getDamageSource(...), amount)}。
     * 读 ISS 源码后确认这是**半截做法**：{@code SpellDamageSource} 只是一个"携带法术信息的
     * DamageSource"，真正让 ISS 管线生效的是
     * {@code io.redspace.ironsspellbooks.damage.DamageSources.applyDamage(target, amount, source)}
     * —— 抗性乘算（{@code getResist}）、{@code SpellDamageEvent}、
     * 召唤物友伤判定、{@code setLastHurtMob} 全在那个方法里。
     * <br>直接调 {@code hurt} 会绕过全部四项：目标的 {@code memory_magic_resist}
     * **完全不生效**（上面那段注释描述的正是这个目的，但代码没做到），
     * 而 {@code SpellDamageEvent} 不触发又会让共鸣加成与复现威力系数一起失效。
     * <br>实测依据：ISS 自己有 **60 个文件**（所有投射物 + 所有直伤法术）都走
     * {@code DamageSources.applyDamage}，我们没有任何理由绕开它。
     *
     * <p>默认属性下这个改动是**零行为差异**的：
     * {@code Utils.softCapFormula(1.0) = 1.0} → {@code getResist} 返回 {@code 2 - 1 = 1.0}。
     * 只有在玩家/目标真的堆了属性时才会看出区别 —— 而那正是设计想要的。
     *
     * @param directEntity 直接造成伤害的实体（法术直接命中时 = caster；投射物命中时 = 投射物）
     * @return 目标是否真的受到了伤害
     */
    protected final boolean hurtWithSpellDamage(final LivingEntity target, final Entity directEntity,
                                                final Entity caster, final float amount) {
        return dealSpellDamage(target, directEntity, caster, this, amount);
    }

    /** 便捷版：法术直接命中（直接实体 = 施法者）。 */
    protected final boolean hurtWithSpellDamage(final LivingEntity target, final Entity caster, final float amount) {
        return hurtWithSpellDamage(target, caster, caster, amount);
    }

    /**
     * 用任意法术的学派伤害源打伤害 —— {@link #hurtWithSpellDamage} 的静态版。
     *
     * <p>存在的原因：{@code EngramRelease} 要用**被释放的那条记忆记录的法术**
     * 作为伤害归属（例如复诵一条痛忆时，伤害应当算在"痛忆"头上，
     * 而不是算在"复诵"头上 —— 否则伤害类型、死亡消息、抗性归属全是错的）。
     *
     * @param spell 用来构造伤害源的法术（决定伤害类型与死亡消息）
     */
    protected static boolean dealSpellDamage(final LivingEntity target, final Entity directEntity,
                                             final Entity caster, final AbstractSpell spell, final float amount) {
        if (amount <= 0.0F || spell == null || target == null) {
            return false;
        }
        return DamageSources.applyDamage(target, amount, spell.getDamageSource(directEntity, caster));
    }

    // ==================================================================
    // 构造 DefaultConfig 的统一入口
    // ==================================================================

    /**
     * 统一构造忆海的 {@link DefaultConfig}。
     *
     * <p>字段填法与官方一致（见 {@code DefaultConfig.validate()}：
     * 必须 minRarity / maxLevel / schoolResource / cooldownInSeconds 全部就位，否则构造器抛异常）。
     *
     * <p><b>⭐ 2026-09-18：{@code maxLevel} 从"全流派统一 5"改为<b>逐法术显式传入</b></b>
     * <br>依据 {@code docs/tech/13_数值总表.md} §一 —— 它把最大等级收进调色板
     * {@code {1, 3, 4, 5, 6, 8, 10}}，并明确"稀有度由等级在自身区间里的位置推导"。
     * 意思是：<b>等级区间长度本身是设计旋钮</b>，不再是所有法术共用的常数。
     * <br>刻意<b>删掉原来的 2 参重载</b>而不是给它一个默认 5：留默认值会让漏改的法术
     * <b>静默停在 5 级</b>（编译通过、运行无错、只是永远升不上去），正是本项目最忌讳的失败模式。
     * 改成必填参数后，漏改 = 编译不过，由编译器兜底。
     *
     * <p><b>⚠️ 与等级数组的关系</b>：各法术的 {@code clampLevelIndex} 都以
     * {@code 数组.length} 为上界（例：{@code Math.min(RADIUS.length, spellLevel)}），
     * 所以 <b>maxLevel 必须与所有等级数组的长度一致</b>，否则高等级会取不到对应档位
     * （数组短 → 高等级被钳到最后一档；数组长 → 多出来的档位永远读不到）。
     *
     * @param minRarity     起始稀有度，取自 {@code docs/tech/13} §二 各法术表
     * @param cooldownSec   冷却秒数（固定值，不随等级变化）
     * @param maxLevel      最大等级，必须与本类所有 {@code *_BY_LEVEL} 数组长度一致
     */
    protected static DefaultConfig memoryConfig(final SpellRarity minRarity,
                                                final double cooldownSec, final int maxLevel) {
        return new DefaultConfig()
                .setMinRarity(minRarity)
                .setSchoolResource(ModSchools.MEMORY_RESOURCE)
                .setMaxLevel(maxLevel)
                .setCooldownSeconds(cooldownSec)
                .setAllowCrafting(true)
                .build();
    }
}
