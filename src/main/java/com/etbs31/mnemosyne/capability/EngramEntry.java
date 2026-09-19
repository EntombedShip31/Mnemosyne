package com.etbs31.mnemosyne.capability;

import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 一条记忆。忆格里存的就是它。
 *
 * <p><b>文件归属</b>：WS-B 忆格系统。
 *
 * <p><b>契约（冻结，{@code docs/tech/11} §六）</b>：三种子类型对应
 * {@link EngramType} 的三个值，NBT 结构逐条对应 {@code docs/tech/03} §6.2/§6.3。
 *
 * <p><b>为什么用 {@code sealed} + 嵌套子类，而不是一个"万能字段类"</b>：
 * 万能类意味着每个字段都要判空、每种组合都要考虑合法性，
 * 而三种记忆的字段**完全没有交集**。sealed 让 {@code switch} 可以穷尽检查 ——
 * 将来加第四种记忆时，所有 switch 会**编译报错**提醒你处理，而不是运行时静默走 default。
 *
 * <p><b>⭐ 到期时间是"世界游戏刻"，不是"已过 tick 数"</b>：
 * 存绝对值（{@code expire}）而不是剩余时长，这样玩家登出再登入、跨维度传送，
 * 腐坏计时都**继续走**（存剩余时长的话，登出就等于冻结记忆，是一个可利用的漏洞）。
 * 当前刻统一取**主世界**的 {@code getGameTime()} —— 各维度各自的 gameTime 在
 * 边界情况下（某维度未加载）可能不同步，用主世界那一个计数器最稳。
 */
public abstract sealed class EngramEntry
        permits EngramEntry.SpellMemory, EngramEntry.EssenceMemory, EngramEntry.PainMemory {

    /** NBT 根键下的通用字段名。 */
    protected static final String KEY_TYPE = "type";
    protected static final String KEY_EXPIRE = "expire";

    private final long expireTick;

    protected EngramEntry(final long expireTick) {
        this.expireTick = expireTick;
    }

    /** 到期时刻（主世界游戏刻）。{@code <= now} 即已腐坏。 */
    public final long getExpireTick() {
        return expireTick;
    }

    public final boolean isExpired(final long nowTick) {
        return nowTick >= expireTick;
    }

    public abstract EngramType getType();

    /** 序列化到 NBT（写入忆格数组的一项）。 */
    public abstract CompoundTag save();

    /** 生成一份到期时间不同的副本。用于"刷新记忆"（例如复诵时不消耗）。 */
    public abstract EngramEntry withExpireTick(long newExpireTick);

    /** 给 HUD / 悬浮提示用的短描述。 */
    public abstract Component describe();

    // ==================================================================
    // 反序列化
    // ==================================================================

    /**
     * 从 NBT 读回一条记忆。
     *
     * @return 结构不合法（缺字段 / 未知 type / 未知法术 id）时返回 {@code null}。
     *         **调用方必须容忍 null** —— 一个被改坏的存档不应该让玩家进不了游戏。
     */
    @Nullable
    public static EngramEntry load(final CompoundTag tag) {
        if (tag == null || !tag.contains(KEY_TYPE) || !tag.contains(KEY_EXPIRE)) {
            return null;
        }
        final EngramType type = EngramType.byName(tag.getString(KEY_TYPE));
        if (type == null) {
            return null;
        }
        final long expire = tag.getLong(KEY_EXPIRE);
        return switch (type) {
            case SPELL -> SpellMemory.load(tag, expire);
            case ESSENCE -> EssenceMemory.load(tag, expire);
            case PAIN -> PainMemory.load(tag, expire);
        };
    }

    // ==================================================================
    // ⚠️ 2026-09-18：术忆（encode_spell）已删除，这个类型目前**没有生产者**。
    //    保留是因为它属于存档格式的一部分（sealed 子类 + NBT 类型分发），
    //    删掉会动到旧存档的读取路径。将来若要复用，先确认有写入端。
    // 术忆：记住"敌人在做什么"
    // ==================================================================

    /**
     * 术忆 —— 存一个法术，之后可以免费复诵。
     *
     * <p>⚠️ 2026-09-18：{@code EncodeSpellSpell}（术忆）与 {@code ReciteSpell}（复诵）
     * 都已删除，所以这个类型目前既没有写入端也没有释放端。
     *
     * <p>NBT：{@code type / spellId / level / power / expire}
     */
    public static final class SpellMemory extends EngramEntry {

        private static final String KEY_SPELL_ID = "spellId";
        private static final String KEY_LEVEL = "level";
        private static final String KEY_POWER = "power";

        private final ResourceLocation spellId;
        private final int spellLevel;
        /** 写入时的威力快照（{@code docs/tech/03} §6.3）。 */
        private final float power;

        public SpellMemory(final ResourceLocation spellId, final int spellLevel,
                           final float power, final long expireTick) {
            super(expireTick);
            this.spellId = spellId;
            this.spellLevel = Math.max(1, spellLevel);
            this.power = power;
        }

        public ResourceLocation getSpellId() {
            return spellId;
        }

        public int getSpellLevel() {
            return spellLevel;
        }

        public float getPower() {
            return power;
        }

        /** 反查法术实例；法术已被移除（换 ISS 版本 / 换整合包）时返回 {@code null}。 */
        @Nullable
        public AbstractSpell resolveSpell() {
            return SpellRegistry.getSpell(spellId.toString());
        }

        @Override
        public EngramType getType() {
            return EngramType.SPELL;
        }

        @Override
        public CompoundTag save() {
            final CompoundTag tag = new CompoundTag();
            tag.putString(KEY_TYPE, EngramType.SPELL.getSerializedName());
            tag.putString(KEY_SPELL_ID, spellId.toString());
            tag.putInt(KEY_LEVEL, spellLevel);
            tag.putFloat(KEY_POWER, power);
            tag.putLong(KEY_EXPIRE, getExpireTick());
            return tag;
        }

        @Override
        public EngramEntry withExpireTick(final long newExpireTick) {
            return new SpellMemory(spellId, spellLevel, power, newExpireTick);
        }

        @Override
        public Component describe() {
            final AbstractSpell spell = resolveSpell();
            final String name = spell == null
                    ? spellId.toString()
                    : Component.translatable(spell.getComponentId()).getString();
            return Component.translatable("engram.mnemosyne.spell", name, spellLevel);
        }

        @Nullable
        private static EngramEntry load(final CompoundTag tag, final long expire) {
            if (!tag.contains(KEY_SPELL_ID)) {
                return null;
            }
            final ResourceLocation id = ResourceLocation.tryParse(tag.getString(KEY_SPELL_ID));
            if (id == null) {
                return null;
            }
            return new SpellMemory(id, tag.getInt(KEY_LEVEL), tag.getFloat(KEY_POWER), expire);
        }
    }

    // ==================================================================
    // 质忆：记住"敌人是什么"
    // ==================================================================

    /**
     * 质忆 —— 存一个特性，暂时借用。
     *
     * <p>NBT：{@code type / traitId / duration / expire}
     */
    public static final class EssenceMemory extends EngramEntry {

        private static final String KEY_TRAIT_ID = "traitId";
        private static final String KEY_DURATION = "duration";

        private final ResourceLocation traitId;
        /** 特性生效时长（tick）。{@code docs/tech/03} §6.3。 */
        private final int durationTicks;

        public EssenceMemory(final ResourceLocation traitId, final int durationTicks, final long expireTick) {
            super(expireTick);
            this.traitId = traitId;
            this.durationTicks = durationTicks;
        }

        public ResourceLocation getTraitId() {
            return traitId;
        }

        public int getDurationTicks() {
            return durationTicks;
        }

        @Override
        public EngramType getType() {
            return EngramType.ESSENCE;
        }

        @Override
        public CompoundTag save() {
            final CompoundTag tag = new CompoundTag();
            tag.putString(KEY_TYPE, EngramType.ESSENCE.getSerializedName());
            tag.putString(KEY_TRAIT_ID, traitId.toString());
            tag.putInt(KEY_DURATION, durationTicks);
            tag.putLong(KEY_EXPIRE, getExpireTick());
            return tag;
        }

        @Override
        public EngramEntry withExpireTick(final long newExpireTick) {
            return new EssenceMemory(traitId, durationTicks, newExpireTick);
        }

        @Override
        public Component describe() {
            return Component.translatable("engram.mnemosyne.essence", traitId.getPath());
        }

        @Nullable
        private static EngramEntry load(final CompoundTag tag, final long expire) {
            if (!tag.contains(KEY_TRAIT_ID)) {
                return null;
            }
            final ResourceLocation id = ResourceLocation.tryParse(tag.getString(KEY_TRAIT_ID));
            if (id == null) {
                return null;
            }
            return new EssenceMemory(id, tag.getInt(KEY_DURATION), expire);
        }
    }

    // ==================================================================
    // 痛忆：记住"敌人怎么伤害我"
    // ==================================================================

    /**
     * 痛忆 —— 存一段伤害，原样奉还。
     *
     * <p>NBT：{@code type / amount / expire}
     */
    public static final class PainMemory extends EngramEntry {

        private static final String KEY_AMOUNT = "amount";

        private final float amount;

        public PainMemory(final float amount, final long expireTick) {
            super(expireTick);
            this.amount = amount;
        }

        /** 记录的伤害总量（已在写入时按 {@code spells.painMemoryCapPercent} 截断）。 */
        public float getAmount() {
            return amount;
        }

        @Override
        public EngramType getType() {
            return EngramType.PAIN;
        }

        @Override
        public CompoundTag save() {
            final CompoundTag tag = new CompoundTag();
            tag.putString(KEY_TYPE, EngramType.PAIN.getSerializedName());
            tag.putFloat(KEY_AMOUNT, amount);
            tag.putLong(KEY_EXPIRE, getExpireTick());
            return tag;
        }

        @Override
        public EngramEntry withExpireTick(final long newExpireTick) {
            return new PainMemory(amount, newExpireTick);
        }

        @Override
        public Component describe() {
            return Component.translatable("engram.mnemosyne.pain", String.format("%.1f", amount));
        }

        @Nullable
        private static EngramEntry load(final CompoundTag tag, final long expire) {
            if (!tag.contains(KEY_AMOUNT)) {
                return null;
            }
            return new PainMemory(tag.getFloat(KEY_AMOUNT), expire);
        }
    }
}
