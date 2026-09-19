package com.etbs31.mnemosyne.spell.base;

import io.redspace.ironsspellbooks.api.config.DefaultConfig;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * 四个法术基类的**编译期契约自检**。
 *
 * <p><b>文件归属</b>：WS-C 法术基类。
 *
 * <p><b>它解决什么问题</b>：并行开发里最贵的错误是"基类签名被改坏，而下游 16 个法术全炸"。
 * 这个文件为四个基类各提供一个**最小可实例化子类**，任何基类契约被破坏
 * （抽象方法增删、构造器签名变化）都会**立刻编译失败**，
 * 而不是等到 WS-D 写完 16 个法术、跑游戏时才炸。
 *
 * <p><b>为什么不接进启动流程</b>：这是开发期护栏，不是运行时功能。
 * 编译通过就达成目的，没必要在玩家的启动路径上跑测试代码。
 * 因此 {@link #PROBES} 只被声明、不被调用 —— 它的作用是让 javac 检查
 * "这四个构造器确实能被调用、这四个类确实不是抽象的"。
 *
 * <p><b>⚠️ 这些探针法术永远不会被注册</b>（不在 {@code registry/ModSpells.java} 里），
 * 所以它们的 {@code getSpellResource()} 返回的是不会被任何人解析的占位 id。
 */
final class BaseContractSelfTest {

    private BaseContractSelfTest() {}

    /** 声明即检查：四个基类都能被实例化。 */
    @SuppressWarnings("unused")
    private static final AbstractSpell[] PROBES = {
            new ProbeEncodeSpell(),
            new ProbeOblivionSpell(),
            new ProbeProjectileSpell(),
            new ProbeLongCastSpell()
    };

    private static ResourceLocation probeId(final String path) {
        return ResourceLocation.fromNamespaceAndPath("mnemosyne", "__contract_probe_" + path);
    }

    // ------------------------------------------------------------------

    /** 检查 {@link EncodeSpell} 的契约：只需实现 {@code onEncode}。 */
    private static final class ProbeEncodeSpell extends EncodeSpell {
        ProbeEncodeSpell() {
            super(memoryConfig(SpellRarity.COMMON, 1.0D));
        }

        @Override
        public ResourceLocation getSpellResource() {
            return probeId("encode");
        }

        @Override
        protected void onEncode(final ServerPlayer caster, final LivingEntity target, final int spellLevel) {
            // 契约探针，无逻辑。
        }
    }

    /** 检查 {@link OblivionSpell} 的契约：{@code getOblivionTier} + {@code applyOblivion}。 */
    private static final class ProbeOblivionSpell extends OblivionSpell {
        ProbeOblivionSpell() {
            super(memoryConfig(SpellRarity.COMMON, 1.0D));
        }

        @Override
        public ResourceLocation getSpellResource() {
            return probeId("oblivion");
        }

        @Override
        protected int getOblivionTier() {
            return 1;
        }

        @Override
        protected void applyOblivion(final ServerPlayer caster, final LivingEntity target, final int spellLevel) {
            // 契约探针，无逻辑。
        }
    }

    /** 检查 {@link MnemosyneProjectileSpell} 的契约：{@code getProjectileDamage} + {@code getCastType}。 */
    private static final class ProbeProjectileSpell extends MnemosyneProjectileSpell {
        ProbeProjectileSpell() {
            super(memoryConfig(SpellRarity.COMMON, 1.0D));
        }

        @Override
        public ResourceLocation getSpellResource() {
            return probeId("projectile");
        }

        @Override
        public CastType getCastType() {
            return CastType.INSTANT;
        }

        @Override
        protected float getProjectileDamage(final int spellLevel) {
            return basePowerOf(spellLevel) * 0.5F;
        }
    }

    /** 检查 {@link MnemosyneLongCastSpell} 的契约：三个抽象方法 + {@code getCastType} 被 final 锁成 LONG。 */
    private static final class ProbeLongCastSpell extends MnemosyneLongCastSpell {
        ProbeLongCastSpell() {
            super(memoryConfig(SpellRarity.LEGENDARY, 60.0D));
        }

        @Override
        public ResourceLocation getSpellResource() {
            return probeId("long_cast");
        }

        @Override
        protected int defaultCastTime() {
            return 40;
        }

        @Override
        protected void onLongCastTick(final ServerPlayer player, final int spellLevel,
                                      final float progress, final MagicData magicData) {
            // 契约探针，无逻辑。
        }

        @Override
        protected void onLongCastFinish(final ServerPlayer player, final int spellLevel) {
            // 契约探针，无逻辑。
        }
    }
}
