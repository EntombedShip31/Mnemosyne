# ISS 官方 API 参考

> 本文档内容**全部来自对 `irons_spellbooks-1.20.1-3.16.3` 反编译核实**，不是推测。
> 上级：[README 总览](../README.md)　｜　相关：[00 技术总览](00_技术总览.md)、[02 学派与属性注册](02_学派与属性注册.md)

---

## 一、注册表体系总览

ISS 向 Forge 注册了 **3 个自定义注册表**，都通过 `ResourceKey<Registry<T>>` 暴露给外部：

| 注册表 | 注册表键 | 元素类型 | 键的可见性 |
|---|---|---|---|
| 学派 | `irons_spellbooks:schools` | `SchoolType` | `SchoolRegistry.SCHOOL_REGISTRY_KEY` **public** |
| 法术 | `irons_spellbooks:spells` | `AbstractSpell` | `SpellRegistry.SPELL_REGISTRY_KEY` **public** |
| 结构池元素 | — | `StructurePoolElementType<?>` | `StructureElementRegistry.STRUCTURE_POOL_ELEMENT_DEFERRED_REGISTER` **public** |

### 关键结论：**可以注册新学派**

`SchoolRegistry` 内部的 `DeferredRegister` 是 `private` 的，`registerSchool(...)` 也是 `private`。**但注册表键是 public 的**，所以附属模组可以用标准的 Forge 方式直接向同一个注册表注册：

```java
// 伪代码，展示思路
DeferredRegister<SchoolType> SCHOOLS = DeferredRegister.create(
        SchoolRegistry.SCHOOL_REGISTRY_KEY,   // ← 就是 irons_spellbooks:schools
        "mnemosyne");
SCHOOLS.register("mnemosyne", () -> new SchoolType(...));
SCHOOLS.register(modEventBus);
```

> 这是本次技术方案能成立的前提。反编译核实：`SchoolRegistry` 的静态初始化里调用了
> `ResourceLocation.fromNamespaceAndPath("irons_spellbooks", "schools")` 生成注册表键，
> 然后 `DeferredRegister.create(key, "irons_spellbooks")`。注册表键是全局共享的，命名空间只影响注册表内部 id 的前缀。

---

## 二、SchoolRegistry

**包路径**：`io.redspace.ironsspellbooks.api.registry.SchoolRegistry`（**在 api 包内，编译期可见**）

### 公开成员

| 成员 | 类型 | 说明 |
|---|---|---|
| `SCHOOL_REGISTRY_KEY` | `ResourceKey<Registry<SchoolType>>` | 注册表键，**用于注册新学派** |
| `REGISTRY` | `Supplier<IForgeRegistry<SchoolType>>` | 注册表本体，用于遍历 |
| `FIRE` / `ICE` / `LIGHTNING` / `HOLY` / `ENDER` / `BLOOD` / `EVOCATION` / `NATURE` / `ELDRITCH` | `RegistryObject<SchoolType>` | 9 个官方学派 |
| `FIRE_RESOURCE` … `ELDRITCH_RESOURCE` | `ResourceLocation` | 9 个学派的 id |
| `getSchool(ResourceLocation)` | static | 按 id 取学派 |
| `getSchoolFromFocus(ItemStack)` | static | **按焦点物品反查学派**（关键：用于"手持焦点才能抄写/施法"的逻辑） |
| `getSchoolsFromFocus(ItemStack)` | static → `List<SchoolType>` | 一个焦点可能对应多个学派 |

### 官方 9 个学派的 id

```
fire · ice · lightning · holy · ender · blood · evocation · nature · eldritch
```

> **注意**：语言文件里还存在 `school.irons_spellbooks.void = Void`，但 `SchoolRegistry` 中**没有 void**。
> 这是一个历史遗留的未使用条目，说明"虚空"这个学派曾经存在或被砍掉过。**我们不要去占用 `void` 这个名字**。

---

## 三、SchoolType（学派类型）

**包路径**：`io.redspace.ironsspellbooks.api.spells.SchoolType`

### 字段（全部 package-private，通过 getter 访问）

| 字段 | 类型 |
|---|---|
| `id` | `ResourceLocation` |
| `focus` | `TagKey<Item>`（**焦点是一个物品标签，不是一个物品**） |
| `displayName` | `Component` |
| `displayStyle` | `Style` |
| `powerAttribute` | `Supplier<Attribute>` |
| `resistanceAttribute` | `Supplier<Attribute>` |
| `defaultCastSound` | `Supplier<SoundEvent>` |
| `damageType` | `ResourceKey<DamageType>` |
| `requiresLearning` | `boolean` |
| `allowLooting` | `boolean` |

### 公开构造器（8 参数版，最完整）

```
SchoolType(
    ResourceLocation id,
    TagKey<Item> focus,
    Component displayName,
    Supplier<Attribute> powerAttribute,
    Supplier<Attribute> resistanceAttribute,
    Supplier<SoundEvent> defaultCastSound,
    ResourceKey<DamageType> damageType,
    boolean requiresLearning,
    boolean allowLooting
)
```

另有 3 个简化重载（省略 `requiresLearning` / `allowLooting`）。

### 公开方法

| 方法 | 说明 |
|---|---|
| `getId()` | 学派 id |
| `getDisplayName()` | 显示名 |
| `getFocus()` | 焦点物品标签 |
| `isFocus(ItemStack)` | 判断某个物品是不是本学派焦点 |
| `getPowerFor(LivingEntity)` | 取实体对本学派的法术强度 |
| `getResistanceFor(LivingEntity)` | 取实体对本学派的法术抗性 |
| `getCastSound()` | 默认施法音效 |
| `getDamageType()` | 学派伤害类型 |
| `getTargetingColor()` | `Vector3f`，**瞄准时的准心颜色**（视觉定制点） |

### 两个关键设计点

**① 焦点是「物品标签」不是「物品」**

`focus` 字段是 `TagKey<Item>`。也就是说，一个学派可以有**多个焦点物品**（只要它们都在同一个标签里）。这一点可以用来做"忆晶的不同变体"。

同时需要在 `data/<modid>/tags/items/<focus_tag>.json` 里定义这个标签的内容。

**② `requiresLearning` 与 `allowLooting`**

- `requiresLearning = true` → 该学派的法术**不能直接抄写**，必须先"学会"（类似邪术 Eldritch 的手稿机制）。**记忆学派应该用 `true`**，因为我们的设计是"读忆碑才能解锁"。
- `allowLooting = false` → 该学派的法术**不会出现在战利品/随机生成里**。如果希望忆者遗迹的箱子里能开出记忆卷轴，就要设 `true`。

---

## 四、AttributeRegistry（属性注册表）

**包路径**：`io.redspace.ironsspellbooks.api.registry.AttributeRegistry`

### 全局属性（6 个）

| 属性 | 作用 |
|---|---|
| `MAX_MANA` | 最大法力 |
| `MANA_REGEN` | 法力回复速度 |
| `COOLDOWN_REDUCTION` | 冷却缩减 |
| `SPELL_POWER` | **全学派法术强度**（乘算，默认 1.0） |
| `SPELL_RESIST` | 全学派法术抗性 |
| `CAST_TIME_REDUCTION` | 施法时间缩减 |
| `SUMMON_DAMAGE` | 召唤物伤害 |
| `CASTING_MOVESPEED` | 施法时移动速度 |

### 学派属性（9 × 2 = 18 个）

每个学派两个：`<SCHOOL>_SPELL_POWER` 与 `<SCHOOL>_MAGIC_RESIST`。

```
FIRE_SPELL_POWER / FIRE_MAGIC_RESIST
ICE_SPELL_POWER / ICE_MAGIC_RESIST
LIGHTNING_… / HOLY_… / ENDER_… / BLOOD_… / EVOCATION_… / NATURE_… / ELDRITCH_…
```

### 我们要新增的 2 个属性

| 属性 | 注册表 | 建议 id |
|---|---|---|
| 记忆法术强度 | `ForgeRegistries.ATTRIBUTES` | `mnemosyne:memory_spell_power` |
| 记忆法术抗性 | `ForgeRegistries.ATTRIBUTES` | `mnemosyne:memory_magic_resist` |

> **属性不是注册在 ISS 的注册表里的**，而是注册在 Forge 原生的 `ForgeRegistries.ATTRIBUTES`。ISS 只是把它自己那 18 个属性也注册在那儿，然后在 `SchoolType` 里持有引用。
>
> 还需要监听 `EntityAttributeModificationEvent` 把新属性挂到 `Player` 上（否则玩家身上不存在这个属性，取值为 0）。

---

## 五、AbstractSpell（法术基类）

**包路径**：`io.redspace.ironsspellbooks.api.spells.AbstractSpell`

### 5.1 五个数值字段（子类在构造器里赋值）

```java
protected int baseManaCost;        // 1 级时的基础法力消耗
protected int manaCostPerLevel;    // 每升一级增加的法力
protected int baseSpellPower;      // 1 级时的基础威力
protected int spellPowerPerLevel;  // 每升一级增加的威力
protected int castTime;            // 施法时间（tick），仅非 INSTANT 类型有效
```

### 5.2 数值公式（**反编译核实，非推测**）

**法力消耗**

```
getManaCost(level) = (baseManaCost + manaCostPerLevel × (level − 1)) × MANA_MULTIPLIER
```

> 注意是 `level − 1`，也就是 **1 级时就是 `baseManaCost` 本身**。`MANA_MULTIPLIER` 是服务器配置里对该法术的法力倍率（默认 1.0）。

**法术威力**

```
getSpellPower(level, entity)
    = (baseSpellPower + spellPowerPerLevel × (level − 1))
      × SPELL_POWER(实体属性)
      × 学派强度属性(实体)
      × POWER_MULTIPLIER
```

> 三个乘数：全局法术强度、学派法术强度、服务器配置倍率。三者默认都是 1.0。

**施法时间**

```
getCastTime(level) = 0                       若 getCastType() == INSTANT
                   = castTime                否则
```

**冷却**

```
getSpellCooldown() = cooldownInSeconds × 20   （单位：tick）
```

> 冷却**不随等级变化**，是法术级的固定值。

### 5.3 必须覆写的方法

| 方法 | 返回 | 说明 |
|---|---|---|
| `getSpellResource()` | `ResourceLocation` | 法术 id |
| `getDefaultConfig()` | `DefaultConfig` | 稀有度 / 学派 / 最高等级 / 冷却 / 是否可抄写 |
| `getCastType()` | `CastType` | 施法类型 |
| `onCast(Level, int level, LivingEntity, CastSource, MagicData)` | void | **主逻辑**，法术生效的地方 |

### 5.4 可选覆写的方法（按用途分组）

**数值与信息**

| 方法 | 用途 |
|---|---|
| `getManaCost(int level)` | 覆写自定义法力曲线 |
| `getSpellPower(int level, Entity)` | 覆写自定义威力曲线 |
| `getSpellCooldown()` | 覆写冷却 |
| `getCastTime(int level)` | 覆写施法时间 |
| `getUniqueInfo(int level, LivingEntity)` | **法术书/卷轴 tooltip 里显示的自定义信息行**（如"伤害：12"、"持续时间：6 秒"） |
| `getMaxLevel()` / `getMinLevel()` | 等级上下限 |
| `getMinRarity()` | 最低稀有度 |
| `getRarity(int level)` | 某等级对应的稀有度（**默认实现已够用，一般不用覆写**） |
| `getRecastCount(int level, LivingEntity)` | 可重施次数（默认 0）。返回 >0 时，法术会在引导结束后允许再次施放 |

**生命周期钩子（按执行顺序）**

| 钩子 | 时机 |
|---|---|
| `checkPreCastConditions(Level, int, LivingEntity, MagicData)` | 施法前条件检查，返回 false 则中断 |
| `canBeCastedBy(int, CastSource, MagicData, Player)` | 返回 `CastResult`，判断能否施法（法力不足、冷却中、未学习等） |
| `onClientPreCast(Level, int, LivingEntity, InteractionHand, MagicData)` | 客户端施法前（用于预测动画） |
| `onServerPreCast(Level, int, LivingEntity, MagicData)` | 服务端施法前 |
| `onServerCastTick(Level, int, LivingEntity, MagicData)` | **引导期间每 tick 调用**（LONG / CONTINUOUS 类型） |
| `onCast(...)` | 法术生效 |
| `onServerCastComplete(Level, int, LivingEntity, MagicData, boolean cancelled)` | 引导结束 |
| `onClientCast(Level, int, LivingEntity, ICastData)` | 客户端施法 |
| `onRecastFinished(ServerPlayer, RecastInstance, RecastResult, ICastDataSerializable)` | 重施结束 |

**表现层**

| 方法 | 用途 |
|---|---|
| `getCastStartSound()` / `getCastFinishSound()` | 起手/收招音效（`Optional<SoundEvent>`） |
| `getCastStartAnimation()` / `getCastFinishAnimation()` | 施法动画（`AnimationHolder`） |
| `getTargetingColor()` | 准心颜色 |

**权限与规则**

| 方法 | 用途 |
|---|---|
| `isEnabled()` | 是否启用（受服务器配置影响） |
| `allowCrafting()` / `canBeCraftedBy(Player)` | 是否允许在抄写台抄写 |
| `requiresLearning()` | 是否必须先学会 |
| `isLearned(Player)` | 玩家是否已学会 |
| `allowLooting()` | 是否可出现在战利品里 |
| `canBeInterrupted(Player)` | 施法是否可被打断 |
| `shouldAIStopCasting(int, Mob, LivingEntity)` | AI 是否应停止施法 |
| `getDamageSource(Entity)` / `getDamageSource(Entity, Entity)` | 取本学派的 `SpellDamageSource`（**所有伤害都应该走这个，才能被学派抗性正确减免**） |

---

## 六、DefaultConfig（法术配置）

**包路径**：`io.redspace.ironsspellbooks.api.config.DefaultConfig`

### 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `minRarity` | `SpellRarity` | **最低稀有度**（不是"这个法术的稀有度"，见 §七） |
| `schoolResource` | `ResourceLocation` | 所属学派 id |
| `maxLevel` | `int` | 最高等级 |
| `enabled` | `boolean` | 是否启用 |
| `cooldownInSeconds` | `double` | 冷却（秒） |
| `allowCrafting` | `boolean` | 是否可抄写 |

### 构造方式（链式）

```
new DefaultConfig()
    .setMinRarity(SpellRarity.COMMON)
    .setSchoolResource(new ResourceLocation("mnemosyne", "mnemosyne"))
    .setMaxLevel(5)
    .setCooldownSeconds(1.5)
    .setAllowCrafting(true)
    .build()
```

### 校验规则（反编译核实）

`build()` 会校验四项，任一不满足就抛异常：

1. `minRarity != null`
2. `maxLevel >= 0`
3. `schoolResource != null`
4. `cooldownInSeconds >= 0`

---

## 七、SpellRarity（稀有度）—— 最容易被误解的部分

**包路径**：`io.redspace.ironsspellbooks.api.spells.SpellRarity`

### 五个枚举值

| 枚举 | `getValue()` | 语言键 | 颜色 |
|---|---|---|---|
| `COMMON` | 0 | `rarity.ironsspellbooks.common` | 灰 |
| `UNCOMMON` | 1 | `rarity.ironsspellbooks.uncommon` | 绿 |
| `RARE` | 2 | `rarity.ironsspellbooks.rare` | 蓝 |
| `EPIC` | 3 | `rarity.ironsspellbooks.epic` | 紫 |
| `LEGENDARY` | 4 | `rarity.ironsspellbooks.legendary` | 金 |

### 核心机制：稀有度由「等级 / 最高等级」的比值决定

这是**反编译核实**的算法，非常关键：

```
getRarity(level):
    if maxLevel == 1:            return SpellRarity.values()[minRarity]
    if level >= maxLevel:        return LEGENDARY
    ratio = level / maxLevel
    # 在累积权重里找 ratio 落在哪个桶
```

### 稀有度权重来自服务器配置

`RARITY_CONFIG` 是一个**必须恰好 5 个元素、且总和为 1.0** 的浮点列表。

**默认值（从 class 常量池提取）：**

```
[0.3, 0.25, 0.2, 0.15, 0.1]
```

含义是：**每一档稀有度占据该法术等级区间的比例**。

算法细节：
1. 取 `RARITY_CONFIG` 从 `minRarity` 到 `LEGENDARY` 的子列表
2. 归一化（除以子列表总和）
3. 转成**累积分布**
4. `ratio` 落在哪个区间就是哪一档

### 计算示例（**可直接用于本流派**）

**示例 A：`minRarity = COMMON`，`maxLevel = 5`**

子列表 = `[0.3, 0.25, 0.2, 0.15, 0.1]`，总和 1.0，累积 = `[0.3, 0.55, 0.75, 0.9, 1.0]`

| 等级 | ratio | 落点 | 稀有度 |
|---|---|---|---|
| 1 | 0.2 | ≤ 0.3 | **COMMON** |
| 2 | 0.4 | 0.3–0.55 | **UNCOMMON** |
| 3 | 0.6 | 0.55–0.75 | **RARE** |
| 4 | 0.8 | 0.75–0.9 | **EPIC** |
| 5 | ≥ maxLevel | — | **LEGENDARY** |

> **这是最完美的情况：5 个等级恰好一一对应 5 档稀有度。** 因此本流派所有法术统一使用 `maxLevel = 5`。

**示例 B：`minRarity = UNCOMMON`，`maxLevel = 5`**

子列表 = `[0.25, 0.2, 0.15, 0.1]`，总和 0.7，累积 = `[0.357, 0.643, 0.857, 1.0]`

| 等级 | ratio | 稀有度 |
|---|---|---|
| 1 | 0.2 | UNCOMMON |
| 2 | 0.4 | RARE |
| 3 | 0.6 | RARE |
| 4 | 0.8 | EPIC |
| 5 | ≥ maxLevel | LEGENDARY |

**示例 C：`minRarity = RARE`，`maxLevel = 5`**

子列表 = `[0.2, 0.15, 0.1]`，总和 0.45，累积 = `[0.444, 0.778, 1.0]`

| 等级 | ratio | 稀有度 |
|---|---|---|
| 1 | 0.2 | RARE |
| 2 | 0.4 | RARE |
| 3 | 0.6 | EPIC |
| 4 | 0.8 | LEGENDARY |
| 5 | ≥ maxLevel | LEGENDARY |

### `getMinLevelForRarity(rarity)` 公式（反编译核实）

```
getMinLevelForRarity(r)
    = 0                                          若 r.getValue() < minRarity
    = 1                                          若 r.getValue() == minRarity
    = (int)(rarityWeights.get(r.getValue() − 1 − minRarity) × maxLevel) + 1
```

### `maxRarity` 是硬编码的

`AbstractSpell` 构造器里直接写死 `maxRarity = SpellRarity.LEGENDARY.getValue()`（= 4）。
**所有法术的稀有度上限都是 LEGENDARY，无法修改。**

### 对设计的直接影响

1. **法术的"稀有度"不是一个固定值，而是随等级变化的。** 同一本法术在 1 级是 Common 卷轴，5 级就是 Legendary 卷轴。
2. 卷轴的稀有度决定了它**能装进哪一档法术书**（抄写台会报 "This scroll is too rare for this spell book"）。
3. `minRarity` 决定的是**这个法术最低能以什么稀有度出现**（即它最早能在哪一档法术书里被抄写）。

---

## 八、CastType（施法类型）—— **只有 4 个值**

**包路径**：`io.redspace.ironsspellbooks.api.spells.CastType`

```java
NONE  ·  INSTANT  ·  LONG  ·  CONTINUOUS
```

| 值 | 含义 | `getCastTime()` |
|---|---|---|
| `NONE` | 无（仅用于占位法术 `none`） | — |
| `INSTANT` | 瞬发，按下立即生效 | **强制返回 0** |
| `LONG` | 有起手时间，引导完才生效 | 返回 `castTime` |
| `CONTINUOUS` | 按住持续施放，每 tick 生效 | 返回 `castTime` |

> ### ⚠️ 重要更正
> **ISS 3.16.3 没有 `CHARGE`（蓄能）这个施法类型。** 网上部分资料（含早期 wiki）声称有四种类型并列出 "Charge"，这是**错误的**。
>
> **需要"蓄能越久威力越大"效果时怎么办**：
> 用 `LONG` 类型 + 覆写 `onServerCastTick(Level, int, LivingEntity, MagicData)`。
> 该方法在引导期间**每 tick 调用一次**，可以在里面累加一个计数器，存进 `MagicData` 的自定义 `ICastData`，在 `onCast` 里按累积值决定威力。
> 这是 ISS 官方实现"蓄力"效果的**唯一途径**。

`CastType` 另有 `immediatelySuppressRightClicks()`，用于判断该类型是否在施法瞬间就应抑制右键行为。

---

## 九、SpellData（卷轴/法术书上的法术数据）

**包路径**：`io.redspace.ironsspellbooks.api.spells.SpellData`

### NBT 键（public 常量）

```java
SpellData.SPELL_ID      // 法术 id
SpellData.SPELL_LEVEL   // 法术等级
SpellData.SPELL_LOCKED  // 是否锁定
```

### 构造与访问

| 成员 | 说明 |
|---|---|
| `new SpellData(AbstractSpell, int level)` | 构造 |
| `new SpellData(AbstractSpell, int level, boolean locked)` | 构造（带锁定） |
| `new SpellData(ResourceLocation, int, boolean)` | 按 id 构造 |
| `SpellData.EMPTY` | 空法术 |
| `getSpell()` | 取法术 |
| `getLevel()` | 取等级 |
| `isLocked()` | 是否锁定 |
| `getRarity()` | **取该等级对应的稀有度** |
| `getDisplayName()` | 显示名 |
| `writeToBuffer` / `readFromBuffer` | 网络序列化（`FriendlyByteBuf`） |
| `CODEC` | 数据包编解码器 |

> `SpellData` **在 api 包里，编译期可见**，可以直接用。

---

## 十、包可见性速查（决定实现方式）

| 类 | 包 | 编译期可见 | 应对 |
|---|---|---|---|
| `AbstractSpell` | `api.spells` | ✅ | 直接继承 |
| `SchoolType` | `api.spells` | ✅ | 直接 new |
| `SchoolRegistry` | `api.registry` | ✅ | 直接用 |
| `SpellRegistry` | `api.registry` | ✅ | 直接用 |
| `AttributeRegistry` | `api.registry` | ✅ | 直接用 |
| `SpellRarity` / `CastType` | `api.spells` | ✅ | 直接用 |
| `SpellData` | `api.spells` | ✅ | 直接用 |
| `DefaultConfig` | `api.config` | ✅ | 直接用 |
| `MagicData` | `api.magic` | ✅ | 直接调用 `getPlayerMagicData(player)` |
| `IScroll` | `api.item` | ✅ | 可实现 |
| `CastSource` / `CastResult` | `api.spells` | ✅ | 直接用 |
| `Scroll` | `item` | ❌ | **自己实现卷轴物品** |
| `SpellBook` / `UniqueSpellBook` | `item` | ❌ | 用 ISS 自带的，不要自己造 |
| `UpgradeOrbItem` | `item` | ❌ | 自己实现升级宝珠 |
| `SyncedSpellData` | `capabilities.magic` | ❌ | **反射调用**（参考已有 addon 的 `SpellLearnHelper`） |
| `RecastInstance` | `capabilities.magic` | ❌ | 反射或避免使用 |
| ISS 的投射物实体 | `entity.spells.*` | ❌ | **自己写投射物**（继承 `AbstractMagicProjectile` 也不可见，需自建基类） |

### 反射调用范式（借鉴已投产的 addon）

```
① 首次调用时用 getMethod 解析目标方法，缓存 Method 对象
② 用 AtomicBoolean 保证失败日志只打一次
③ 参数签名不确定时，准备多个候选签名依次尝试
④ 任何异常都降级返回 false，绝不抛出
```

> 已核实的案例：ISS 的 `SyncedSpellData.learnSpell(AbstractSpell)` 在 3.16.3 里可能有两个签名
> （`learnSpell(AbstractSpell)` 与 `learnSpell(AbstractSpell, boolean)`），需要按参数个数分支处理。

---

## 十一、可用的关键事件

| 事件 | 总线 | 用途 |
|---|---|---|
| `SpellOnCastEvent` | Forge | 法术施放时（**可用于"术忆"记录上一个法术、给忆格加负荷**） |
| `SpellOnCastEvent.Post` | Forge | 法术施放后 |
| `LivingHurtEvent` | Forge | 受伤时（**"痛忆"记账**、火焰免疫拦截） |
| `LivingDamageEvent` | Forge | 伤害生效时 |
| `PlayerTickEvent` | Forge | 玩家每 tick（**忆格腐坏倒计时、悖论衰减**） |
| `ItemAttributeModifierEvent` | Forge | 物品属性（**套装提供学派强度**） |
| `EntityAttributeModificationEvent` | Mod | 给玩家/实体添加新属性 |
| `ServerStartingEvent` | Forge | 服务器启动（**清空注册表缓存**） |
| `AddReloadListenerEvent` | Forge | 数据包重载（**重新加载生物映射表**） |
| `MobSpawnEvent` | Forge | 生物生成 |
| `TickEvent.RenderTickEvent` | Forge | 渲染 tick（**HUD 绘制**，客户端） |

---

## 十二、投射物与粒子特效（2026-09-18 忆矢重做时用 javap 核实）

### 12.1 `AbstractMagicProjectile` 的两个特效钩子 —— **调用侧是相反的**

`io.redspace.ironsspellbooks.entity.spells.AbstractMagicProjectile` 有 4 个 abstract 成员：
`trailParticles()` / `impactParticles(double,double,double)` / `getSpeed()` /
`getImpactSound() : Optional<Supplier<SoundEvent>>`。

| 钩子 | 调用点 | 调用侧 | 正确写法 |
|---|---|---|---|
| `trailParticles()` | `tick()`（`m_8119_`）内 `if (level.isClientSide) trailParticles();` | **仅客户端** | `level().addParticle(...)` 本地生成 |
| `impactParticles(x,y,z)` | `onHit(HitResult)`（`m_6532_`）内 `if (!level.isClientSide) impactParticles(...)`；`onAntiMagic()` 亦然 | **仅服务端** | 必须 `ServerLevel.sendParticles(...)` 广播 |

⚠️⚠️ **踩坑记录**：`impactParticles` 里若写 `if (!level().isClientSide) return;`
（"只在客户端生成"的直觉写法），因为调用点永远是服务端，这段特效**一次都不会执行**。
忆矢的实体命中因此长期只有音效、没有冲击粒子。

⚠️ **撞击音效不要重复播**：`onHit()` 已经做过
`impactParticles(result.getLocation())` + `getImpactSound().ifPresent(this::doImpactSound)`。
子类 `onHitBlock` / `onHitEntity` 里再补一遍 → 撞一次墙放**三遍**音效。
`onHitBlock` 只需 `discard()`。

### 12.2 原版彩色粒子速查（不需要自定义 ParticleType）

| 类型 | 参数类 | 构造 | 用途 |
|---|---|---|---|
| `ParticleTypes.DUST` | `DustParticleOptions` | `(Vector3f color, float scale)` | 单色尘，**精确控制颜色** |
| `ParticleTypes.DUST_COLOR_TRANSITION` | `DustColorTransitionOptions` | `(Vector3f from, Vector3f to, float scale)` | 双色过渡尘，做"渐变/转化"观感 |
| `ParticleTypes.SCULK_CHARGE` | `SculkChargeParticleOptions` | `(float roll)` | 带滚转角，做涟漪 |

- 色值写法：`Vec3.fromRGB24(0x534AB7).toVector3f()`（`fromRGB24` 已做 /255）
- `DustParticleOptionsBase` 构造器把 `scale` 钳到 `[0.01, 4.0]`，超范围不会报错
- ⚠️ **`sendParticles` 的 `speed` 参数只是"随机方向的初速度"**，无法指定方向。
  要做出"定向光带 / 定向锥"，只能**沿方向逐点铺粒子位置**，不能靠速度。
- ⭐ 发光渲染：`RenderType.entityTranslucentEmissive(tex)` + `LightTexture.FULL_BRIGHT`
  （否则洞穴里"发光的"投射物会跟着环境变暗）；alpha 走顶点色 `.color(r,g,b,alpha)`。
- ⭐ 外发光不需要第二张贴图：同一张已羽化的贴图**放大 2×、顶点 alpha 压到 ~45** 再画一遍即可。

### 12.3 `util/SpellFeedback` —— 表现层的唯一入口（2026-09-18 三轮补全）

**设计原则：形状即语义。** 不要用"再来一团爆发"表示所有事情 —— 每种形状固定对应一种含义，
玩家才能把屏幕上的东西读成信息，而不是特效噪音。

| helper | 形状 | 读作 | 用在哪 |
|---|---|---|---|
| `castBurst` | 眼睛处碎片爆发 | "放了法术" | 通用起手 |
| `muzzleBurst` | 沿视线的**定向**光带 | "朝那个方向射出去了" | 忆矢 |
| `channelTick` | 密度随进度增长的引导 | "吟唱进行中" | 长吟法术 |
| `hitBurst` | 命中点爆发 | "打中了" | 通用命中 |
| `areaBurst` | 半径 R 的水平环 | "影响范围有这么大" | 领域 / 起手 |
| `coneSweep` | 等距锥壳 | "锥形判定范围" | 碎忆 |
| `beam` | 两点**对称**连线 | "我锁定 / 我引爆它" | 失忆 / 认知崩坏 / 千忆归一 |
| `extractBeam` | 两点连线，**密度单向递增** + 终点落点光 | "东西从它那儿到我这儿了" | 质忆 / 记忆掠夺 |
| `weakExtractBeam` | 只画到 55% 的断链 | "这次没抽满（降级）" | 质忆的通用记忆路径 |
| `stackDetonation` | **环数 = 层数**（钳 8） | "我兑现了几层过载" | 认知崩坏 |
| `engramGain` | 绕体环，**环数 = 格数** | "我多了几格忆格" | 忆格扩张 / 碎忆 |
| `stealPulse` | 绕体环，**环数 = 效果数**（品红） | "我抢到了几个效果" | 记忆掠夺 |
| `replayPulse` | 逐圈变大的环，**环数 = 重演数** | "这一发连了几段" | 既视感 |
| `spellSlotMark` | N 条竖排 `ENCHANT` 字符柱 | "法术上限 +N"（装备层） | 铭忆 |
| `dazeAura` | 头顶幽蓝碎屑（**调用方限频**） | "它正在发呆 / 眩晕" | 失忆 / 认知崩坏 |
| `recordingAura` | 绕体品红尘 + 痛觉孢子（**限频**） | "我还在记录窗口里" | 痛忆 |
| `chest` | 实体胸口取点（共享） | —— | 所有连线统一取点 |

**三条硬约束**（违反任一都会出问题）：

1. `sendParticles` 的 `speed` 只是随机方向初速度 → **定向 / 定形只能逐点铺位置**。
2. 由层数 / 数量驱动的规模**必须钳上限**（`stackDetonation` 钳 8，否则 15 层会瞬间 500+ 粒）。
3. 瞬时效果可以 60~120 粒，**但绝不能放在每 tick 的循环里**；
   持续状态一律走"每 N tick 一次的微光"（`dazeAura` / `recordingAura`，建议 N=5）。

⚠️ **两种环不要混用配色**：`engramGain`（忆格）与 `stealPulse`（掠夺效果）形状相同、
都是"圈数 = 数量"，但**量纲不同**，所以刻意用品红 / 痛觉孢子把后者区分开。

⚠️ **不要把 `hitBurst` 用在非打击语义上**：记忆掠夺原本在目标身上放命中爆发，
会让玩家以为它是个伤害技能；改成抽取链后语义才对。

---

*上级：[README 总览](../README.md)　｜　下一篇：[02 学派与属性注册](02_学派与属性注册.md)*
