# WS-E 遗忘系统 完成汇报

> 格式依 `docs/tech/10_多线程开发规范.md` §八。
> 规格来源：`docs/tech/12_下一阶段并行方案.md` §三 提示词 A（冻结契约）。
> 完成日期：2026-09-17。

---

## 一、改动文件

### 新建（`oblivion/` 包，WS-E 所有）

| 文件 | 作用 |
|---|---|
| `src/main/java/com/etbs31/mnemosyne/oblivion/OblivionManager.java` | 执行核心 + `oblivion/**` 唯一的 Forge 事件订阅者（重载 / 效果到期 / 实体加载 / 死亡 / tick） |
| `src/main/java/com/etbs31/mnemosyne/oblivion/AbilityMap.java` | 表 A（39 生物）+ goal 类名→能力分类（56 条规则）+ BOSS/精英表 + Brain 生物表 |
| `src/main/java/com/etbs31/mnemosyne/oblivion/TraitRegistry.java` | 表 B（21 特性 / 31 生物）+ 三类实现（属性 / 药水效果 / 被动免疫）+ 生效与到期清理 |
| `src/main/java/com/etbs31/mnemosyne/oblivion/OblivionTier.java` | 三个层级（遗忘 / 失忆 / 集体遗忘）的时长、半径、效果映射 |
| `src/main/java/com/etbs31/mnemosyne/oblivion/BossImmunity.java` | BOSS 完全免疫 + 数值化降级（-20% 攻击力 30 秒） |
| `src/main/java/com/etbs31/mnemosyne/oblivion/OblivionEffect.java` | 四个状态效果的共同实现（`Mode` 枚举驱动） |
| `src/main/java/com/etbs31/mnemosyne/oblivion/MimicRegistry.java` | **表 C（可复现技能 / 走马灯）** —— 见 §四 说明 1 |
| `src/main/java/com/etbs31/mnemosyne/registry/ModEffects.java` | 四个 `MobEffect` 注册（WS-A 从未创建过此文件） |
| `src/main/resources/data/mnemosyne/mnemosyne/oblivion/abilities.json` | 表 A 数据包（39 生物） |
| `src/main/resources/data/mnemosyne/mnemosyne/oblivion/traits.json` | 表 B 数据包（31 生物） |
| `src/main/resources/data/mnemosyne/mnemosyne/oblivion/bosses.json` | BOSS / 精英表 |
| `src/main/resources/data/mnemosyne/mnemosyne/oblivion/mimics.json` | 表 C 数据包（20 生物） |

### 新建（工具，离线自测）

| 文件 | 作用 |
|---|---|
| `tools/check_ability_map.py` | 校验 56 条分类规则（53 正向 + 19 反向），并生成"生物→goal 类名"证据清单 |
| `tools/check_mapping_tables.py` | 校验表 B / 表 C 与数据包一致性，**并拿 ISS jar 交叉核对法术 id 的真实性** |
| `tools/check_mob_goals.py` | 拿 Forge 反编译 jar 核对 Brain 生物表（防漏报 / 误报） |

### 修改

| 文件 | 改动 | 性质 |
|---|---|---|
| `src/main/java/com/etbs31/mnemosyne/spell/base/OblivionSpell.java` | 只改方法体，**签名一字未动**：把 3 个 `TODO(WS-E)` 钩子接到 `OblivionManager` / `BossImmunity`；删掉旧的"血量启发式"BOSS 判定 | ⚠️ **跨工作流改动（WS-C 的文件）→ 见 §三 接口变更申请** |
| `docs/tech/03_法术实现规范.md` | 追加 §7.2.1「更正二：有 6 个生物没有 goal」+ 女巫喝药的实测差异 | 文档更正（§7.2 上一轮已被同类更正过） |

---

## 二、验收结果

| # | 验收项 | 结果 | 证据 |
|---|---|---|---|
| 1 | `./gradlew build` 通过 | ✅ | `BUILD SUCCESSFUL in 18s` / `8 actionable tasks: 6 executed, 2 up-to-date`；jar 内含 `oblivion/MimicRegistry.class` 与 4 个 JSON |
| 2 | 至少 5 个不同类型的生物，AI 行为确实被移除 | ⚠️ **离线验证通过，游戏内未验证** | 见下方「离线证据」；**游戏内验证被 §五 的阻塞项挡住** |
| 3 | 遗忘结束后能力恢复（能再次攻击 / 移动） | ⚠️ **同上** | 恢复路径 `restore()` 逐条装回**原 Goal 实例**（内部状态不丢）；`alreadyPresent()` 防重复；到期由 `MobEffectEvent.Expired` + `onServerTick` 双保险驱动 |
| 4 | 对 BOSS 使用遗忘 → 完全无效，改为攻击力削弱 | ⚠️ **同上** | `BossImmunity.applyFallback`：固定 UUID `1a7c9e40-…` 的 `addTransientModifier`，`-20%` `MULTIPLY_TOTAL`，600 tick；重复施加先 `removeModifier` → 不叠加 |
| 5 | 服务器重启后没有残留的"被永久削弱"的生物 | ✅ **机制上已保证（可论证）** | 见下方「验收 5 的论证」 |

### 离线证据（本次实际跑出来的）

```
$ python tools/check_ability_map.py
行为分类表校验通过：53 条正向 + 19 条反向，共 56 条规则。

$ python tools/check_mapping_tables.py
映射表校验通过：表 B 21 个特性 / 31 个生物；表 C 15 个法术 / 20 个生物；
交叉核对 115 个真实 ISS 法术 id。

$ python tools/check_mob_goals.py
核对通过：能力表 39 个生物中，实测 6 个是 Brain 生物
（goat、hoglin、piglin、piglin_brute、warden、zoglin），与 BRAIN_MOBS 完全一致。
```

**生物 → 会被移除的 goal 类名**（`check_ability_map.py --evidence` 生成，游戏内照此逐条核对）：

| 生物 | 声明能力 | 会被移除的 goal |
|---|---|---|
| `minecraft:blaze` | ranged_attack, chase_target | `BlazeAttackGoal`、`RangedAttackGoal`、`NearestAttackableTargetGoal`、`HurtByTargetGoal` … |
| `minecraft:skeleton` | ranged_attack, chase_target, avoid_entity | `RangedBowAttackGoal`、`NearestAttackableTargetGoal`、`AvoidEntityGoal` … |
| `minecraft:creeper` | melee_attack, explode, chase_target | `SwellGoal`、`MeleeAttackGoal`、`NearestAttackableTargetGoal` … |
| `minecraft:evoker` | cast_spell, evoker_fangs, summon_vex, chase_target, avoid_entity | `EvokerAttackSpellGoal`、`EvokerSummonSpellGoal`、`EvokerCastingSpellGoal` … |
| `minecraft:enderman` | melee_attack, take_block, place_block, chase_target | `MeleeAttackGoal`、`EndermanTakeBlockGoal`、`EndermanLeaveBlockGoal` … |
| `minecraft:witch` | ranged_attack, chase_target | `RangedAttackGoal`、`NearestAttackableWitchTargetGoal` … |

### 验收 5 的论证（为什么"重启后不会留下残废生物"）

1. 生物的 goal 全部在构造函数 `registerGoals()` 里注册 → **实体被重新构造时行为自动重建**；
2. 存档里**只存两个标量**（`tier` / `expire`），不存 Goal 对象 → 不存在"存了个失效引用"；
3. 重启后 `onEntityJoin`（`loadedFromDisk()` 为真）检查：还有遗忘效果 → 按 tier 重新移除一遍；效果已过期 → 直接清标记；
4. BOSS 降级用 `addTransientModifier` → **不写进存档**，重启即消失；
5. 特性用 `addTransientModifier` + 确定性 UUID → 同理。

---

## 三、接口变更申请

### 申请 1（必须由集成者执行）：`MnemosyneMod.java` 加一行

```java
// MnemosyneMod.java 构造函数「② Forge 标准注册表」段，与 ModItems / ModSounds 并列
ModEffects.register(modBus);
```

- **不改的后果**：编译正常、不报错，但四个状态效果**不会被注册**，遗忘系统退化为"只移除行为、不挂特效"。
- WS-E **故意没有碰** `MnemosyneMod.java`（铁律：主类归 WS-0）。
- `ModEffects.forget()` / `amnesia()` 是防御性取值（未注册返回 `null`），所以集成前不会 NPE。

### 申请 2（已在 WS-C 文件上落地，请复核）：`OblivionSpell.java`

| 项 | 内容 |
|---|---|
| 文件 | `spell/base/OblivionSpell.java`（**WS-C 所有**） |
| 改动 | 仅方法体：`oblivionManager(...)` → `OblivionManager.applyOblivion(...)`；`isOblivionImmune(...)` → `BossImmunity.isImmune(...)`；`applyBossFallback(...)` → `BossImmunity.applyFallback(...)` |
| 签名 | **一字未动** |
| 附带删除 | 旧的"血量启发式"BOSS 判定（`BOSS_HEALTH_FALLBACK_THRESHOLD = 100.0F` + `MobCategory.MONSTER`）—— 它会把高血量非 BOSS 误判，且 `docs/tech/12` 提示词 A 明确要求换成 `AbilityMap.isBoss` |
| 请复核者 | WS-C / 集成者 |

### 申请 3（新增公开 API，均为**追加**，不改任何已有签名）

| 文件 | 新增 | 用途 |
|---|---|---|
| `OblivionManager` | `applyOblivionArea(ServerPlayer, Entity, int) : int` | 范围版（集体遗忘 / 遗忘诅咒） |
| `OblivionManager` | `writeGenericMemory(ServerPlayer) : boolean` | §7.4 第 4 条降级的写入侧（质忆无特性可偷时写"通用记忆"） |
| `AbilityMap` | `removalSupported(EntityType) : boolean` | **监守者等 Brain 生物不能用移除方案**，见 §四 说明 2 |
| `AbilityMap` | `brainMobs() : Set<ResourceLocation>` | 供校验 / 文档 |
| `TraitRegistry` | `GENERIC`（特性 id）、`applyTrait` / `hasActiveTrait` / `clearAll` | 质忆的生效与清理 |
| `MimicRegistry` | 整个类（表 C） | 见 §四 说明 1 |

### 申请 4：冻结契约的**实现偏离**（1 处，重要）

> 提示词 A 要求 1：「被移除的 goal 记录（goal 实例 + priority）**写进自定义 MobEffect 的 NBT**」

**实测不可行，已改为等价方案**：

- `MobEffectInstance` 在 1.20.1 **没有任何自定义 NBT 字段**（读源码确认），且 `Goal` 是对象、不可序列化；
- 改为：**活的 Goal 实例 → 运行时表 `ACTIVE`**（到期**原样**装回，内部状态不丢）；
  **实体 `persistentData` → 只存 `tier` + `expire` 两个标量**（供重载后重新施加）；
- 这个改法**比原方案更稳**：因为持有的是原实例，`docs/tech/03` §7.3 列的风险①（恢复后内部状态被重置）**直接消失**；
  风险②用 `alreadyPresent()` 挡住；风险③（期间死亡）丢弃记录即可。

---

## 四、四个必须知道的实测结论

### 说明 1：表 C（`MimicRegistry`）是**超出提示词文件清单**的追加

- 提示词 A 的「你拥有的文件」列了 7 项，**没有** `MimicRegistry.java`；但它要求 4 明写"**三张映射表**逐条落地"。
- 依据：`docs/tech/10` §一 把 `oblivion/*.java` **整体**划给 WS-E；`docs/tech/00` 的文件树也把 `MimicRegistry.java` 列在 `oblivion/` 下。两处都支持由 WS-E 建它。
- 已确认**没有其他工作流认领**（`docs/tech/12` 的提示词 B/C/D/E 全文无 `MimicRegistry` / `mimic` 字样）。
- 表 C 的值是**真实的 ISS 法术 id**，从 ISS jar 的 `assets/irons_spellbooks/lang/en_us.json` 逐个核对（不是照文档猜的）。
  监守者 → `irons_spellbooks:sonic_boom`（ISS 真有这个法术）。
- ⚠️ **本表接口未冻结**：消费方是 WS-D2 的走马灯 / WS-D3 的记忆掠夺，它们可以要求改。
  三处映射取舍已在类注释里标注（幻术师分身 → `invisibility`；守卫者激光 → `ray_of_frost`；岩浆怪高跳 → `ascension`）。
- ⚠️ **"目标最近 N 秒内使用过该技能"的观测器没有实现** —— 它需要逐生物的能力触发点，
  只有走马灯法术的设计能定义清楚。表 C **只提供静态表**；时间窗口与"最近用过"的记录由 WS-D2 自行实现。

### 说明 2：⭐ 有 6 个生物**没有 goal**，遗忘对它们静默降级（已更正文档）

实测（`tools/check_mob_goals.py`，拿 Forge 反编译 jar 逐生物核对）：

| 生物 | goal 数 | AI 实现 |
|---|---|---|
| **监守者** `warden` | **0** | `Brain` + `ai.behavior.warden.SonicBoom` |
| `goat` / `piglin` / `piglin_brute` / `zoglin` / `hoglin` | **0** | `Brain` |

**后果**：`docs/02` §五 案例 3 把"遗忘监守者"写成流派高光，**目前只能靠通用降级近似**。
**已落地**：`AbilityMap.BRAIN_MOBS` + `removalSupported()`；`presentAbilities()` 短路返回空。
**不在此修**：改 `Brain` 需动它的私有表，而 `Brain` **没有被** Forge AccessTransformer 提升（`goalSelector` 提升了，`Brain` 没有）→ 只能 Mixin/反射，已被明令排除。归 WS-E2。

### 说明 3：女巫的"喝药"同样无法遗忘

实测 `Witch.java` 只有 `FloatGoal` / `RangedAttackGoal` / 游荡 / 观察 goal，**没有喝药 goal** ——
喝药是 `aiStep()` 里的内联逻辑。所以 `docs/02` 案例 2 的"失忆 → 不能喝药"**做不到**，
只有"不能扔药"能实现。法术文案不要向玩家承诺前者。

### 说明 4：`getAvailableGoals()` 返回的是**内部活集合**

源码 `return this.availableGoals;` —— 没有防御性拷贝。边遍历边 `removeGoal` 会抛
`ConcurrentModificationException`。所有遍历都走 `AbilityMap.snapshot(Mob)` 拿副本。

---

## 五、未决问题 / 阻塞

### ⚠️ 阻塞（最高优先级）：`runClient` 至今没有成功过一次

- 验收标准 2 / 3 / 4 需要**游戏内观察 AI 行为**，本项目**一次都没能进游戏**（全局最大欠账）。
- 本次已把能离线做的验证做到最满：3 个脚本 + 56 条规则 + 表 B/C 与 ISS id 交叉核对 + Brain 生物核对。
- **但这不能替代游戏内验证。** 需要：
  1. 集成者加上 `ModEffects.register(modBus);`
  2. 修掉 `runClient` 的启动阻塞（`docs/tech/11` 记录的"MC 资产差 24 个"）
  3. 按 §二 的"生物 → goal 类名"清单逐条核对（建议用 `/summon` + 逐一施法）
- **为什么现在必须提**：WS-E 的接口已冻结，WS-D1/D2/D3 可以并行编译推进了；
  但**越往后堆代码，游戏内返工的代价越高**。建议把"能进游戏"当作下一个集成检查点。

### 其他未决

| 项 | 说明 |
|---|---|
| `docs/02` §三 的 4 种 BOSS 差异化效果未实现 | 冻结规格统一为「-20% 攻击力 30 秒」。4 种各写一套需 4 套独立到期调度，且"受伤 +15%"在 1.20.1 没有对应属性（要另开伤害事件）→ 归 WS-E2 |
| WS-E2 范围 | 主动能力类特性（爬墙 / 短距传送 / 滑翔 / 无声行走 / 地形条件加速）明确未实现，`applyTrait` 返回 `false` 并记日志；Brain 门控也归这里 |
| `docs/02` §四 表 C 与 ISS 法术的对应 | 3 处是"最接近的替代"而非字面对应（幻术师分身 / 守卫者激光 / 岩浆怪高跳），已在代码与 JSON 里注明 |
| 精英怪表默认为空 | `bosses.json` 的 `elites` 是 `[]`。原版没有精英概念，宁可不少算也不要用血量启发式猜（那会把铁傀儡误判） |
| `check_resources.py` 的 2 条新 warn **是预期内的，不要去"修"** | ① `drink_potion` 声明了但数据包没用到 —— **误报**：该规则（`UseItemGoal` → `drink_potion`）有效且应保留（对 `WanderingTrader` 与其他模组生物生效），只是表 A 里没有生物用到它。② `generic` 特性声明了但数据包没用到 —— **设计如此**：它是纯降级用的特性，永远不该出现在 `traits.json` 里（没有任何生物"拥有"通用记忆）。两者都是 `warn` 级、不阻塞退出码。 |
