#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
忆海 Mnemosyne —— 结构模板 NBT 生成器（WS-G2）
===============================================

【为什么是"生成"而不是"用结构方块手搓"】

`docs/tech/06` §四 描述的流程是"在游戏里搭 → 结构方块导出 → 复制 .nbt"。
那需要 runClient、需要人工框选、而且**23 个文件要重复 23 次**。
更重要的是：那个流程**无法被校验** —— 手搓出来的 NBT 少一个拼图块、
拼图块的 `name`/`target` 配错，游戏里只会表现为"遗迹里有个洞"或者
"遗迹只有一间房"，**零日志零报错**。

本脚本把 23 个模板变成**可复现的代码**：改一个数字重跑一次，
23 个文件全部一致更新，而且生成完会自己做一遍结构自检（见 `self_check`）。

=====================================================================
【第一部分：NBT 文件格式（逐条实测，不是猜的）】
=====================================================================

做法：用 `tools/inspect_structure.py` 解 ISS 自己随包发布的 236 个结构 NBT，
逐个字段比对。参考件是 `pyromancer_tower/cellar_dungeon/**`（我们的池命名
本来就是照着它抄的）与 `battleground/**`（地表废墟）。

根标签：`{size:[x,y,z], entities:[], blocks:[], palette:[], DataVersion:int}`

- `blocks[i]` = `{pos:[x,y,z], state:<palette 下标>, nbt:{...}(可选)}`
- `palette[j]` = `{Name:"<block id>", Properties:{...}(可选)}`
- **拼图块**（`minecraft:jigsaw`）的 palette 项必须带 `Properties:{orientation:"..."}`，
  方块实体 NBT 是
  `{id:"minecraft:jigsaw", name, target, pool, final_state, joint, placement_priority:0, selection_priority:0}`

⭐⭐ **必须写满整个包围盒 —— 这是最容易漏的一条。**

实测：ISS 的 `battleground/origin.nbt` 尺寸 26×17×26，方块数是 **11492**
= 26×17×26。`cellar_dungeon/wall.nbt` 尺寸 7×7×1，方块数 **49** = 7×7×1。
两个数都对得上 —— **ISS 把包围盒里每一个格子都显式写进了 `blocks`**。

为什么必须这样：`StructureTemplate.placeInWorld` 只放置 `blocks` 里列出的格子。
**没列出的格子不会被写成空气，而是"保持世界原样"**。如果只写墙体、不写走道，
遗迹埋在地下时走道里留的就是**原地的石头** —— 遗迹生成了、结构完整、
零报错，但**玩家挖进去发现里面是实心的**。

所以本生成器的 `Template.save()` 会遍历整个包围盒，未设置的格子一律写 `minecraft:air`。

⚠️ 我们**没有**照抄 ISS 的 `DataVersion`（他们有的文件写 3953，那是 1.21 的号；
有的写 3120）。`StructureTemplate.load` 不经过 DataFixer，这个字段纯粹是元数据 ——
但我们仍然写 1.20.1 的真值 **3465**，免得将来有人拿它当版本判据。

=====================================================================
【第二部分：拼图连接的全部规则（读 MC 反编译源码得到，非推测）】
=====================================================================

**1. 谁能和谁连（`JigsawBlock.canAttach`，逐字抄自 1.20.1 源码）**

```java
Direction direction  = getFrontFacing(parent.state());   // 父块的 front
Direction direction1 = getFrontFacing(child.state());    // 子块的 front
Direction direction2 = getTopFacing(parent.state());
Direction direction3 = getTopFacing(child.state());
JointType jt = JointType.byName(parent.nbt().getString("joint")).orElseGet(
        () -> direction.getAxis().isHorizontal() ? ALIGNED : ROLLABLE);
boolean flag = jt == ROLLABLE;                            // ← 只读父块的 joint！
return direction == direction1.getOpposite()              // 两个 front 必须反向
    && (flag || direction2 == direction3)                 // rollable 就免检 top
    && parent.nbt().getString("target").equals(child.nbt().getString("name"));
```

由此推出两条**必须遵守**的约定：

| 结论 | 理由 |
|---|---|
| **水平连接口一律 `joint="rollable"`** | `flag=true` 免掉 top 检查。而水平 front 在 4 个旋转里只有一个能让 `front` 反向，所以"宽松"不会带来歧义 |
| ⭐ **竖直连接口必须 `joint="aligned"`** | 竖直 front（up/down）绕 Y 轴旋转**永远是 up/down**！4 个旋转的 front 全都满足"反向"，只有 `aligned` 的 top 检查才能把旋转钉死成唯一一个。用 `rollable` 会让楼梯件随机转 90°，楼梯直接错位 |

**2. `orientation` 的合法取值（`FrontAndTop`，javap 实测共 12 个）**

```
down_east  down_north  down_south  down_west
up_east    up_north    up_south    up_west
west_up    east_up     north_up    south_up
```

⚠️ 写错一个字母不会报错：`StructureTemplate.load` 解析方块状态失败时**把该方块变成空气** ——
也就是"这个连接口凭空消失了"，然后遗迹在那里断开，**没有任何日志**。
所以 `self_check()` 会把这 12 个值当白名单硬校验。

**3. 子件的位置与旋转（`JigsawPlacement.Placer.tryPlacingChildren`）**

- 子件会被**依次尝试 4 个旋转**（`Rotation.getShuffled`），配合它自己的每一个
  `name` 匹配的拼图块；取第一个通过碰撞检测的组合。
- 因此：**子件放在哪个格子 = `父拼图块位置 + 父front` 减去 `旋转后的子拼图块局部位置`**。
  推论：只要子件的拼图块放在**它自己包围盒的边缘**，子件就会**紧贴**父件，不会重叠。

⭐⭐ **这条推论决定了整个几何设计：所有拼图块都必须贴在包围盒边缘，
且 front 指向包围盒外侧。** 如果拼图块放在包围盒正中，子件会有一半插进父件里 ——
碰撞检测会把这次放置整个拒掉，表现为"遗迹只有第一间房"。

**4. `final_state` 什么时候生效**

`SinglePoolElement.place()` 内部有一行
`structureplacesettings.addProcessor(JigsawReplacementProcessor.INSTANCE);`
—— 拼图替换处理器是**自动挂上**的，不用写进 processor_list。
`JigsawReplacementProcessor` 把拼图块换成 `final_state`；`minecraft:structure_void`
表示**删掉这格**。⚠️ `final_state` 写了一个不存在的方块 id 会抛 `RuntimeException`
—— 这是少数会**炸存档**的错误，所以 `self_check()` 用白名单卡死。

**5. `pool` 与回退池**

```java
Optional<Holder<StructureTemplatePool>> optional = this.pools.getHolder(resourcekey);
if (optional.isEmpty()) { LOGGER.warn("Empty or non-existent pool: {}", ...); }
...
List<StructurePoolElement> list = Lists.newArrayList();
if (depth != maxDepth) list.addAll(pool.getShuffledTemplates(random));      // 主池
list.addAll(pool.getFallback().value().getShuffledTemplates(random));       // 回退池，永远加
```

- **池查不到只会 `warn` 然后跳过，不会抛异常** —— 安全但会刷日志。
- ⭐ `minecraft:empty` **是一个真实注册的空池**（`Pools.EMPTY`），
  而且源码里专门写了 `!holder.is(Pools.EMPTY)` 来豁免它的警告。
  所以 `pool="minecraft:empty"` 是"到此为止"的**标准且静默**的写法。
- ⭐ **回退池不是"最后手段"，而是每次都追加进候选列表**；
  且到达 `maxDepth` 时**只剩回退池**。所以回退件的权重是真实生效的。

**6. 缺文件的真实失败模式**

`StructureTemplateManager.getOrCreate` → 找不到文件时 `new StructureTemplate()`（空的）
**并缓存**。于是结构照常被选中、照常"生成"，**生成出来的是空气**。
日志里会有一条 `Couldn't load structure <id>` 的 ERROR —— 但一个正常存档启动时
日志几千行，这一条极容易漏掉。所以"缺 NBT"在 `check_resources.py` 里是 `pending`
（已知欠账，不阻塞退出码），但**必须**清空。

【用法】

    python tools/gen_structure_nbt.py            # 生成 + 自检
    python tools/gen_structure_nbt.py --check    # 只自检（不写文件）

生成目标：`src/main/resources/data/mnemosyne/structures/memory_ruin/**`（23 个文件）
"""

from __future__ import annotations

import argparse
import gzip
import io
import json
import os
import struct
import sys

MODID = "mnemosyne"
# 1.20.1 的 DataVersion。结构模板不走 DataFixer，这个值只是元数据。
DATA_VERSION = 3465

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATA_DIR = os.path.join(ROOT, "src", "main", "resources", "data", MODID)
OUT_DIR = os.path.join(DATA_DIR, "structures", "memory_ruin")
POOL_DIR = os.path.join(DATA_DIR, "worldgen", "template_pool", "memory_ruin")

# ======================================================================
# 方块 id —— 全部来自 ModBlocks 的注册清单（4 个自定义方块）+ 原版
# ======================================================================
#
# 建材配色照 `docs/tech/06` §3.3 的设计意图：
#   地下（通道件/楼梯件）用**深板岩系**（冷色，符合"遗忘"意象）
#   地表（入口/残迹）用**石砖系**（被自然重新占据）
#   `mnemonic_bricks` 是忆海专属建材，处理器会给它 35% 裂开 + 12% 变空气
#   —— 表现"记忆在崩解"，所以只用来做**点缀**，不做承重地板。

AIR = "minecraft:air"

# —— 地下：深板岩系
DEEP_BRICK = "minecraft:deepslate_bricks"
DEEP_TILE = "minecraft:deepslate_tiles"
DEEP_POLISH = "minecraft:polished_deepslate"

# —— 地表：石砖系
STONE = "minecraft:stone_bricks"
MOSSY = "minecraft:mossy_stone_bricks"
GRASS = "minecraft:grass_block"

# —— 忆海专属（ModBlocks 注册）
SIG = f"{MODID}:mnemonic_bricks"            # 铭忆砖
SIG_CRACKED = f"{MODID}:mnemonic_bricks_cracked"
CRYSTAL = f"{MODID}:memory_crystal_cluster"
STELE = f"{MODID}:memory_stele"

# —— 原版杂物
CHEST = "minecraft:chest"
BOOKSHELF = "minecraft:bookshelf"
BARREL = "minecraft:barrel"
CAULDRON = "minecraft:cauldron"
JIGSAW = "minecraft:jigsaw"

# 自检白名单：写错一个 id 会导致拼图替换抛 RuntimeException（炸存档），
# 所以这里硬卡。加新方块时**必须**同步加到这里。
LEGAL_BLOCKS = {
    AIR, DEEP_BRICK, DEEP_TILE, DEEP_POLISH, STONE, MOSSY, GRASS,
    SIG, SIG_CRACKED, CRYSTAL, STELE, CHEST, BOOKSHELF, BARREL, CAULDRON,
}

# `FrontAndTop` 的全部 12 个合法值（javap 实测）
VALID_ORIENTATIONS = {
    "down_east", "down_north", "down_south", "down_west",
    "up_east", "up_north", "up_south", "up_west",
    "west_up", "east_up", "north_up", "south_up",
}

# 战利品表（都在 data/mnemosyne/loot_tables/chests/memory_ruin/ 下）
LOOT_BASIC = f"{MODID}:chests/memory_ruin/basic_storage"
LOOT_VAULT = f"{MODID}:chests/memory_ruin/archive_vault"
LOOT_CORE = f"{MODID}:chests/memory_ruin/mnemonic_core"
LOOT_POT = f"{MODID}:chests/memory_ruin/decayed_pot"

# ======================================================================
# 拼图命名（冻结：改了会让遗迹拼不起来，而且是静默的）
# ======================================================================

POOL_START = f"{MODID}:memory_ruin/start_pool"
POOL_SURFACE = f"{MODID}:memory_ruin/surface_pool"
POOL_STAIR = f"{MODID}:memory_ruin/stair_pool"
POOL_CHAMBER = f"{MODID}:memory_ruin/chamber_pool"
POOL_FALLBACK = f"{MODID}:memory_ruin/chamber_fallback"
POOL_DECOR = f"{MODID}:memory_ruin/decoration_pool"

# 通道口：同池所有口共用同一个 name/target（ISS 的约定）
J_CHAMBER = f"{MODID}:memory_ruin_chamber"
J_STAIRWELL = f"{MODID}:memory_ruin_stairwell"
J_SURFACE = f"{MODID}:memory_ruin_surface"
# 装饰：插座 name 为空路径、target 指装饰名；装饰件反过来（实测 bookshelf_01.nbt）
J_SOCKET = f"{MODID}:"
J_DECOR = f"{MODID}:interior_decorations"

EMPTY = "minecraft:empty"

# ======================================================================
# 极简 NBT 写入器（只支持结构文件用得到的标签类型）
# ======================================================================

TAG_BYTE, TAG_SHORT, TAG_INT, TAG_LONG = 1, 2, 3, 4
TAG_STRING, TAG_LIST, TAG_COMPOUND = 8, 9, 10


class ListTag:
    """NBT 列表。空列表的元素类型是 TAG_End（0），与原版 `new ListTag()` 一致。"""

    def __init__(self, elem_type, items=None):
        self.elem_type = elem_type
        self.items = list(items or [])


class CompoundTag(dict):
    pass


def _write_payload(out: io.BytesIO, tag) -> None:
    if isinstance(tag, CompoundTag):
        for k, v in tag.items():
            out.write(bytes([_tag_type(v)]))
            _write_string(out, k)
            _write_payload(out, v)
        out.write(b"\x00")
    elif isinstance(tag, ListTag):
        out.write(bytes([tag.elem_type]))
        out.write(struct.pack(">i", len(tag.items)))
        for item in tag.items:
            _write_payload(out, item)
    elif isinstance(tag, str):
        _write_string(out, tag)
    elif isinstance(tag, bool):
        out.write(struct.pack(">b", 1 if tag else 0))
    elif isinstance(tag, int):
        out.write(struct.pack(">i", tag))
    else:  # pragma: no cover
        raise TypeError(f"不支持的 NBT 类型：{type(tag)}")


def _tag_type(tag) -> int:
    if isinstance(tag, CompoundTag):
        return TAG_COMPOUND
    if isinstance(tag, ListTag):
        return TAG_LIST
    if isinstance(tag, str):
        return TAG_STRING
    if isinstance(tag, bool):
        return TAG_BYTE
    if isinstance(tag, int):
        return TAG_INT
    raise TypeError(f"不支持的 NBT 类型：{type(tag)}")


def _write_string(out: io.BytesIO, s: str) -> None:
    raw = s.encode("utf-8")
    out.write(struct.pack(">H", len(raw)))
    out.write(raw)


def write_nbt(path: str, root: CompoundTag) -> None:
    out = io.BytesIO()
    out.write(bytes([TAG_COMPOUND]))
    _write_string(out, "")  # 根标签名（原版写空串）
    _write_payload(out, root)
    # 原版用 gzip（不是 zlib）—— 实测：直接 `gzip.open` 读得通
    with gzip.GzipFile(path, "wb", mtime=0) as f:
        f.write(out.getvalue())


# ======================================================================
# 模板构建器
# ======================================================================


class Template:
    """一个结构模板。坐标是 0-based，y=0 是模板底层。"""

    def __init__(self, sx: int, sy: int, sz: int):
        self.size = (sx, sy, sz)
        self._blocks: dict[tuple[int, int, int], tuple[str, dict | None]] = {}
        self._be: dict[tuple[int, int, int], CompoundTag] = {}

    # ---------- 基础写入 ----------

    def set(self, x: int, y: int, z: int, block: str, props: dict | None = None) -> None:
        self._blocks[(x, y, z)] = (block, props)

    def fill(self, x0, y0, z0, x1, y1, z1, block: str, props: dict | None = None) -> None:
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    self.set(x, y, z, block, props)

    def chest(self, x: int, y: int, z: int, loot: str) -> None:
        """箱子 —— 方块状态和方块实体一起写，避免"改了状态忘了改 NBT"。"""
        self.set(x, y, z, CHEST, {"facing": "north", "type": "single", "waterlogged": "false"})
        be = CompoundTag()
        be["id"] = CHEST
        be["LootTable"] = loot
        self._be[(x, y, z)] = be

    def stele(self, x: int, y: int, z: int) -> None:
        """2 格高的忆碑（下 + 上），`read=false`。

        属性名取自 `MemorySteleBlock`：`half`(lower/upper) + `read`(true/false)。
        """
        self.set(x, y, z, STELE, {"half": "lower", "read": "false"})
        self.set(x, y + 1, z, STELE, {"half": "upper", "read": "false"})

    def jigsaw(self, x: int, y: int, z: int, orientation: str, name: str, target: str,
               pool: str, final_state: str, joint: str = "rollable") -> None:
        """放一个拼图块。

        ⚠️ 必须**先**放方块再放拼图块，或者放在最后 —— 任何后写的 `set()` 都会把
        方块状态盖成别的、而方块实体 NBT 仍然残留，拼图系统照样按连接口处理。
        这种"半死"的拼图块在 `self_check()` 里会被点名。
        """
        self.set(x, y, z, JIGSAW, {"orientation": orientation})
        be = CompoundTag()
        be["id"] = JIGSAW
        be["name"] = name
        be["target"] = target
        be["pool"] = pool
        be["final_state"] = final_state
        be["joint"] = joint
        be["placement_priority"] = 0
        be["selection_priority"] = 0
        self._be[(x, y, z)] = be

    # ---------- 序列化 ----------

    def save(self, path: str) -> None:
        palette: list[tuple[str, dict | None]] = []
        index: dict[tuple[str, str], int] = {}
        blocks = ListTag(TAG_COMPOUND)
        sx, sy, sz = self.size

        # ⭐ 遍历**整个包围盒**，未设置的格子写空气 —— 见文件头第一部分。
        for y in range(sy):
            for z in range(sz):
                for x in range(sx):
                    pos = (x, y, z)
                    name, props = self._blocks.get(pos, (AIR, None))
                    key = (name, repr(sorted((props or {}).items())))
                    if key not in index:
                        index[key] = len(palette)
                        palette.append((name, props))
                    entry = CompoundTag()
                    entry["pos"] = ListTag(TAG_INT, [x, y, z])
                    entry["state"] = index[key]
                    if pos in self._be:
                        entry["nbt"] = self._be[pos]
                    blocks.items.append(entry)

        pal_list = ListTag(TAG_COMPOUND)
        for name, props in palette:
            p = CompoundTag()
            p["Name"] = name
            if props:
                pr = CompoundTag()
                for k, v in props.items():
                    pr[k] = v
                p["Properties"] = pr
            pal_list.items.append(p)

        root = CompoundTag()
        root["size"] = ListTag(TAG_INT, list(self.size))
        root["entities"] = ListTag(0, [])
        root["blocks"] = blocks
        root["palette"] = pal_list
        root["DataVersion"] = DATA_VERSION
        os.makedirs(os.path.dirname(path), exist_ok=True)
        write_nbt(path, root)

    # ---------- 查询 ----------

    def jigsaw_positions(self) -> list[tuple[int, int, int]]:
        return [p for p, be in self._be.items() if be.get("id") == JIGSAW]

    def block_at(self, x: int, y: int, z: int) -> str:
        return self._blocks.get((x, y, z), (AIR, None))[0]


# ======================================================================
# 几何常量（全部由设计推导，不是随手写的数字）
# ======================================================================

# 通道件：11×5×11，内部是 3 宽 3 高的十字走道
CH = 11          # 通道件边长
CH_H = 5         # 通道件高（y=0 地板，y=1..3 走道，y=4 顶棚）
CH_MID = 5       # 十字中心
CH_CORR = 1      # 走道半宽（|x-5|<=1 即 3 宽）

# 走道净空（y=1..3）
CORRIDOR_Y = tuple(range(1, CH_H - 1))

# 通道口的四个朝向。位置在**墙面的地板层（y=0）**、且必须落在包围盒边缘。
# ⚠️ 坐标是 CH-1（11 宽的模板下标是 0..10）。写成 CH 会越界，
#    而越界的方块在游戏里表现为"这个口根本不存在"，静默地少一条路。
WEST, EAST, NORTH, SOUTH = 0, 1, 2, 3
CHAMBER_SIDES = [
    ((0, 0, CH_MID), "west_up"),
    ((CH - 1, 0, CH_MID), "east_up"),
    ((CH_MID, 0, 0), "north_up"),
    ((CH_MID, 0, CH - 1), "south_up"),
]

# 各通道件保留哪几个口。没列出的口会被**封成实心墙**。
# ⚠️ 封口不是"把拼图块换成砖"（那样方块实体 NBT 还在，拼图系统仍会往外长），
#    而是**从一开始就不放拼图块** —— 与 ISS 的 `rubble_dead_end.nbt` 一致
#    （它只有 1 个拼图块，另外三面是实心石头）。
OPEN_SIDES = {
    "corridor": (WEST, EAST, NORTH, SOUTH),
    "rubble_dead_end": (SOUTH,),
    "junction": (WEST, EAST, NORTH, SOUTH),
    "bookshelf_gallery": (WEST, EAST, NORTH, SOUTH),
    "archive_vault": (WEST, EAST, NORTH, SOUTH),
    "burial_alcove": (WEST, EAST, NORTH, SOUTH),
    "cracked_wall_opening": (NORTH, SOUTH),
    "statue_pass": (WEST, EAST, NORTH, SOUTH),
    "mnemonic_core": (WEST, EAST, NORTH, SOUTH),
}

# 装饰插座：嵌在**墙块**里（不是走道里），front 指向走道内侧。
# 校验：(3,1,3) 与 (7,1,7) 都满足 |dx|>1 且 |dz|>1 → 是实心墙；
#       邻居 (4,1,3)/(6,1,7) 满足 |dx|<=1 或 |dz|<=1 → 是走道空气。
CHAMBER_SOCKETS = [
    ((3, 1, 3), "east_up"),
    ((7, 1, 7), "west_up"),
]


def mouth_cells(side: int) -> list[tuple[int, int]]:
    """该朝向在边缘上的**整条 3 格走道口**（x, z 平面坐标）。

    封口时必须把 3 格全填实 —— 只填中间那格会留下两个 1×3 的缝，
    玩家能看见、能挤过去，但拼图系统认为那里是墙。
    """
    if side == WEST:
        return [(0, CH_MID + d) for d in (-1, 0, 1)]
    if side == EAST:
        return [(CH - 1, CH_MID + d) for d in (-1, 0, 1)]
    if side == NORTH:
        return [(CH_MID + d, 0) for d in (-1, 0, 1)]
    return [(CH_MID + d, CH - 1) for d in (-1, 0, 1)]


def clear_above(t: Template, x: int, y: int, z: int, n: int = CH_H - 2) -> None:
    """把 (x, y+1..y+n, z) 显式写成空气。

    包围盒写满之后未设置的格子本来就是空气，这里再写一遍是**把意图写在代码里**：
    通道口上方必须有净空，否则玩家看得见走道但过不去。
    """
    for dy in range(1, n + 1):
        t.set(x, y + dy, z, AIR)


# ----------------------------------------------------------------------
# 通道件（9 种）
# ----------------------------------------------------------------------


def build_chamber(kind: str) -> Template:
    """9 个通道件共用同一副骨架（十字走道），只有内部陈设不同。"""
    t = Template(CH, CH_H, CH)
    open_sides = OPEN_SIDES[kind]

    # 地板 / 顶棚
    t.fill(0, 0, 0, CH - 1, 0, CH - 1, DEEP_BRICK)
    t.fill(0, CH_H - 1, 0, CH - 1, CH_H - 1, CH - 1, DEEP_BRICK)
    # 十字走道的地面换成石板，让"路"看得出来
    for i in range(CH):
        t.set(CH_MID, 0, i, DEEP_TILE)
        t.set(i, 0, CH_MID, DEEP_TILE)

    # 墙体：走道以外全部填实
    for x in range(CH):
        for z in range(CH):
            if abs(x - CH_MID) <= CH_CORR or abs(z - CH_MID) <= CH_CORR:
                continue
            for y in CORRIDOR_Y:
                t.set(x, y, z, DEEP_BRICK)

    # 四个口：开着的放拼图块并保证上方净空；关着的整条口填实
    for side, ((x, y, z), ori) in enumerate(CHAMBER_SIDES):
        if side in open_sides:
            t.jigsaw(x, y, z, ori, J_CHAMBER, J_CHAMBER, POOL_CHAMBER, DEEP_BRICK)
            clear_above(t, x, y, z)
        else:
            for (cx, cz) in mouth_cells(side):
                for yy in CORRIDOR_Y:
                    t.set(cx, yy, cz, DEEP_BRICK)

    # 两个装饰插座（嵌在墙块里，最后放，避免被上面的循环覆盖）
    for (x, y, z), ori in CHAMBER_SOCKETS:
        t.jigsaw(x, y, z, ori, J_SOCKET, J_DECOR, POOL_DECOR, DEEP_BRICK)

    # ---- 各变体的陈设 ----
    # ⚠️ 下面每一处 set() 都不能落在拼图块/插座的格子上。
    #    插座在 (3,1,3) 与 (7,1,7)，所以"墙面裂纹"只做 y=2..3。
    if kind == "corridor":
        pass   # 纯十字走道

    elif kind == "rubble_dead_end":
        for (dx, dz) in [(4, 5), (4, 6), (6, 4), (6, 6), (5, 4), (5, 6)]:
            t.set(dx, 1, dz, SIG_CRACKED)
        t.set(CH_MID, 1, CH_MID, SIG_CRACKED)

    elif kind == "junction":
        for (x, z) in [(4, 4), (4, 6), (6, 4), (6, 6)]:
            t.fill(x, 1, z, x, CH_H - 2, z, DEEP_BRICK)

    elif kind == "bookshelf_gallery":
        for x in (3, 7):
            for z in (4, 5, 6):
                t.set(x, 1, z, BOOKSHELF)
                t.set(x, 2, z, BOOKSHELF)
        for z in (3, 7):
            for x in (4, 5, 6):
                t.set(x, 1, z, BOOKSHELF)

    elif kind == "archive_vault":
        for z in (3, 4, 6, 7):
            t.set(4, 1, z, BOOKSHELF)
            t.set(6, 1, z, BOOKSHELF)
        t.chest(CH_MID, 1, CH_MID, LOOT_VAULT)

    elif kind == "burial_alcove":
        t.fill(4, 1, 4, 6, 1, 6, DEEP_POLISH)
        t.chest(4, 2, 4, LOOT_POT)
        t.chest(6, 2, 6, LOOT_POT)

    elif kind == "cracked_wall_opening":
        # 只留南北两个口（东西已由 OPEN_SIDES 封死），墙面多裂纹
        for y in (2, 3):
            t.set(3, y, 3, SIG_CRACKED)
            t.set(7, y, 7, SIG_CRACKED)
        t.set(2, 1, 8, SIG_CRACKED)
        t.set(8, 1, 2, SIG_CRACKED)

    elif kind == "statue_pass":
        t.fill(3, 1, 4, 3, 3, 4, DEEP_BRICK)
        t.fill(7, 1, 6, 7, 3, 6, DEEP_BRICK)
        t.set(3, 1, 6, CRYSTAL)
        t.set(7, 1, 4, CRYSTAL)

    elif kind == "mnemonic_core":
        t.stele(CH_MID, 1, CH_MID)
        t.chest(4, 1, 4, LOOT_CORE)
        t.chest(6, 1, 6, LOOT_CORE)
        t.set(3, 1, 5, CRYSTAL)
        t.set(7, 1, 5, CRYSTAL)

    else:  # pragma: no cover
        raise ValueError(f"未知的通道件类型：{kind}")

    return t


def build_sealed_wall() -> Template:
    """回退件：一个**实心的 11×5×11 堵头**。

    为什么不是一堵薄墙（`11×5×1`）：

    - 子件会被尝试 4 个旋转。一堵 11×5×1 的墙转 90° 就变成 1×5×11 ——
      本来要封住东西向的口，结果变成一根柱子，**封了个寂寞**。
      做成 11×11 的方体，4 个旋转下占地形状完全一样，怎么转都对。
    - 用同样的 11×5×11 占地，回退件和通道件**占同一块空间**，
      所以"通道件放得下"和"堵头放得下"是同一个条件，不会互相挤掉。

    四个面各留一个拼图块（name=J_CHAMBER 才能被通道口认领），
    但 `pool`/`target` 都是空 —— **到此为止，不再往外长**。
    """
    t = Template(CH, CH_H, CH)
    t.fill(0, 0, 0, CH - 1, CH_H - 1, CH - 1, DEEP_BRICK)
    for (x, y, z), ori in CHAMBER_SIDES:
        t.jigsaw(x, y, z, ori, J_CHAMBER, EMPTY, EMPTY, DEEP_BRICK)
    return t


# ----------------------------------------------------------------------
# 楼梯件
# ----------------------------------------------------------------------
#
# 尺寸 11×8×3。三个关键数字，每一个都有理由：
#
# 1. **顶部拼图块必须落在模板最高层（y=7）。**
#    子件位置 = 父拼图块位置 + 父front − 子拼图块局部位置。
#    遗迹的楼梯井拼图块在它的地板层（y=0，世界 Y），front=DOWN，
#    所以接点在世界 Y−1；楼梯件高 8、拼图块在 y=7，于是楼梯件的包围盒
#    正好落在世界 y ∈ [Y−8, Y−1] —— **完全在遗迹包围盒（y≥Y）之下，不重叠**。
#    重叠会被 `tryPlacingChildren` 的碰撞检测直接拒掉，表现为"楼梯永远不生成"。
#
# 2. **拼图块要压在走道面上**（不是悬在走道里），与 ISS 的通道口一致。
#
# 3. **不需要顶棚** —— 楼梯井上方就是遗迹的地板（y=Y），
#    遗迹地板上那个 3×3 的开口就是井口。给楼梯件加顶棚反而会把它自己封死。

STAIR_W, STAIR_H, STAIR_D = 11, 8, 3
STAIR_TOP_X = 1
STAIR_TOP_Y = STAIR_H - 1          # = 7
STAIR_EXIT_X = 10
# x -> 走道面高度。x=1 是平台（最高），一路降到 x=7，之后是平的
STAIR_RUN = {x: (8 - x) for x in range(1, 8)}


def build_stair(kind: str) -> Template:
    t = Template(STAIR_W, STAIR_H, STAIR_D)

    # 两侧墙（z=0 / z=2）全高填实
    for z in (0, STAIR_D - 1):
        t.fill(0, 0, z, STAIR_W - 1, STAIR_H - 1, z, DEEP_BRICK)

    # 西端封头
    t.fill(0, 0, 1, 0, STAIR_H - 1, 1, DEEP_BRICK)

    # 走道（z=1）：按 STAIR_RUN 铺台阶，上方保持空气
    for x in range(1, STAIR_W):
        surface = STAIR_RUN.get(x, 0)
        t.fill(x, 0, 1, x, surface, 1, DEEP_BRICK)

    # 顶部拼图块：front=UP、joint=aligned（竖直口必须 aligned，见文件头）
    t.jigsaw(STAIR_TOP_X, STAIR_TOP_Y, 1, "up_north",
             J_STAIRWELL, EMPTY, EMPTY, DEEP_BRICK, joint="aligned")
    # 底部拼图块：front=EAST，把通道件引到东侧
    t.jigsaw(STAIR_EXIT_X, 0, 1, "east_up",
             J_CHAMBER, J_CHAMBER, POOL_CHAMBER, DEEP_BRICK, joint="aligned")

    if kind == "staircase_cracked":
        for x in range(2, 9):
            t.set(x, STAIR_RUN.get(x, 0), 1, SIG_CRACKED)
        t.set(4, STAIR_H - 1, 1, SIG_CRACKED)
    elif kind == "staircase_collapsed":
        # 塌方：抽掉两级台阶，改成瓦砾堆（仍然可通行 —— 只是看起来更破）
        t.set(3, STAIR_RUN.get(3, 0), 1, SIG_CRACKED)
        t.set(6, STAIR_RUN.get(6, 0), 1, SIG_CRACKED)
        for z in (0, 2):
            t.set(6, 2, z, SIG_CRACKED)
    return t


# ----------------------------------------------------------------------
# 入口（地表主遗迹）
# ----------------------------------------------------------------------
#
# 15×8×15，y=0 = 地面（世界 Y）。
# 楼梯井：地面上留 3×3 开口（x=10..12, z=10..12），拼图块在正中 (11,0,11)。
# 开口必须存在：楼梯件在地面以下向东下降，如果地面上是实心地板，
# 玩家从楼梯井走下去会**撞到天花板**（地面本身）。

RUIN_W, RUIN_H, RUIN_D = 15, 8, 15
RUIN_MID = 7
RUIN_SHAFT = (10, 10, 12, 12)
RUIN_SHAFT_CENTER = (11, 11)
# 四个地表口（边缘中点）。注意别和角柱/边柱位置重合。
RUIN_SURFACE_MOUTHS = [
    ((0, 0, RUIN_MID), "west_up"),
    ((RUIN_W - 1, 0, RUIN_MID), "east_up"),
    ((RUIN_MID, 0, 0), "north_up"),
    ((RUIN_MID, 0, RUIN_D - 1), "south_up"),
]


def build_ruin_main() -> Template:
    t = Template(RUIN_W, RUIN_H, RUIN_D)

    # 地面：中间石板 + 外圈草皮（处理器会把草皮 100% 变成苔藓）
    t.fill(0, 0, 0, RUIN_W - 1, 0, RUIN_D - 1, STONE)
    for i in range(RUIN_W):
        t.set(i, 0, 0, GRASS)
        t.set(i, 0, RUIN_D - 1, GRASS)
        t.set(0, 0, i, GRASS)
        t.set(RUIN_W - 1, 0, i, GRASS)

    # 楼梯井开口（3×3 空气）
    x0, z0, x1, z1 = RUIN_SHAFT
    t.fill(x0, 0, z0, x1, 0, z1, AIR)

    # 围墙（y=1..3），确定性缺口 —— 不用随机数：同一个脚本永远生成同一份文件
    for x in range(RUIN_W):
        for z in range(RUIN_D):
            if not (x in (0, RUIN_W - 1) or z in (0, RUIN_D - 1)):
                continue
            for y in range(1, 4):
                if (x * 7 + z * 13 + y * 3) % 5 == 0:
                    continue   # 缺口
                t.set(x, y, z, SIG)

    # 四个地表口上方强制挖空 —— 否则围墙会把连接口堵死，
    # 拼图系统照样在墙外面接上一片废墟，但玩家过不去。
    for (mx, my, mz), _ori in RUIN_SURFACE_MOUTHS:
        clear_above(t, mx, my, mz)

    # 角柱与边柱（y=1..6，顶端刻意缺一格 = 断柱）。
    # ⚠️ 位置必须避开四个口所在的列（x=7 或 z=7 的中点），否则柱子会被挖空成悬空的。
    pillars = [
        (0, 0), (0, RUIN_D - 1), (RUIN_W - 1, 0), (RUIN_W - 1, RUIN_D - 1),
        (4, 0), (10, 0), (4, RUIN_D - 1), (10, RUIN_D - 1),
        (0, 4), (0, 10), (RUIN_W - 1, 4), (RUIN_W - 1, 10),
    ]
    for (px, pz) in pillars:
        for y in range(1, 7):
            if y == 6 and (px + pz) % 2 == 0:
                continue   # 断口
            t.set(px, y, pz, SIG_CRACKED)

    # 中心：忆碑 + 供台
    t.fill(6, 1, 6, 8, 1, 8, DEEP_POLISH)
    t.stele(7, 2, 7)

    # 陈设
    t.chest(5, 1, 5, LOOT_BASIC)
    t.set(3, 1, 11, CRYSTAL)
    t.set(11, 1, 3, CRYSTAL)
    t.set(13, 1, 12, SIG_CRACKED)
    t.set(2, 1, 13, SIG_CRACKED)

    # 楼梯井：front=DOWN、joint=aligned。
    # final_state=air —— 这是"打通"的连通口（docs/tech/06 §4.3 的规则），
    # 让井口保持敞开，玩家能走下去。
    sx, sz = RUIN_SHAFT_CENTER
    t.jigsaw(sx, 0, sz, "down_north", J_STAIRWELL, J_STAIRWELL, POOL_STAIR, AIR,
             joint="aligned")

    # 地表四向：front 指向外侧。horizontal → rollable。
    for (jx, jy, jz), ori in RUIN_SURFACE_MOUTHS:
        t.jigsaw(jx, jy, jz, ori, J_SURFACE, J_SURFACE, POOL_SURFACE, STONE)

    return t


# ----------------------------------------------------------------------
# 地表残迹（4 个）
# ----------------------------------------------------------------------
#
# 7×6×7。**拼图块必须在包围盒边缘**（见文件头第二部分的推论），
# 而且 front 指向外侧，本体向 −x 长 —— 这样接上去以后整块残迹都在遗迹外面。
# 如果按"拼图块放正中"来做，残迹会有一半插进遗迹里，碰撞检测会把这次放置拒掉。
#
# 拼图块放在 (6,0,3) east_up：front=EAST。
# 遗迹的西口 front=WEST，需要子件 front=EAST → 旋转 0° → 残迹落在遗迹西侧。
# 遗迹的东口 front=EAST，需要子件 front=WEST → 旋转 180° → 落在遗迹东侧。
# 南北口同理（旋转 90°/270°）。**一个拼图块就能覆盖四个方向。**

SURF_W, SURF_H = 7, 6
SURF_MID = 3


def build_surface(kind: str) -> Template:
    t = Template(SURF_W, SURF_H, SURF_W)
    # 地基铺满 y=0 整层（拼图块那格最后覆盖），免得残迹周围出现 1 格深的地缝
    t.fill(0, 0, 0, SURF_W - 1, 0, SURF_W - 1, SIG_CRACKED)

    if kind == "broken_column":
        t.fill(3, 1, 3, 3, 4, 3, SIG)
        t.set(3, 5, 3, SIG_CRACKED)      # 断口
        t.set(1, 1, 5, SIG_CRACKED)
        t.set(5, 1, 1, SIG_CRACKED)

    elif kind == "fallen_arch":
        t.fill(1, 1, 2, 1, 4, 2, SIG)
        t.fill(5, 1, 4, 5, 4, 5, SIG)
        t.fill(1, 4, 2, 5, 4, 5, SIG_CRACKED)
        t.set(3, 4, 3, AIR)              # 拱顶塌了一格

    elif kind == "collapsed_wall":
        t.fill(1, 1, 3, 5, 1, 3, SIG)
        t.fill(1, 2, 3, 3, 2, 3, SIG)
        t.set(4, 2, 3, SIG_CRACKED)
        t.set(2, 1, 5, SIG_CRACKED)
        t.set(4, 1, 1, SIG_CRACKED)

    elif kind == "rune_plaza":
        t.fill(0, 0, 0, SURF_W - 1, 0, SURF_W - 1, DEEP_POLISH)
        for (x, z) in [(2, 2), (4, 2), (2, 4), (4, 4)]:
            t.set(x, 1, z, SIG)
        t.set(3, 1, 3, CRYSTAL)
        t.set(1, 1, 1, CRYSTAL)
        t.set(5, 1, 5, CRYSTAL)

    else:  # pragma: no cover
        raise ValueError(f"未知的地表残迹类型：{kind}")

    t.jigsaw(SURF_W - 1, 0, SURF_MID, "east_up",
             J_SURFACE, J_SURFACE, POOL_SURFACE, SIG_CRACKED)
    return t


# ----------------------------------------------------------------------
# 装饰件（5 个）
# ----------------------------------------------------------------------
#
# 2×2×3。拼图块在 (0,0,1)（角落），front=WEST，本体向 +x 长 1 格。
# 插座在墙块里、front 指向走道内侧，所以装饰件会被摆成"从墙里伸进走道 1 格"。
# 高度只做 2 格（local y=0..1）：走道净空 3 格，装饰件不能把顶棚封死。
#
# 字段完全照抄 ISS 的 `room_decorations/bookshelf_01.nbt`：
#   name = 装饰名 / target = minecraft:empty / pool = minecraft:empty
# （`minecraft:empty` 是真实注册的空池 `Pools.EMPTY`，且源码专门豁免了它的警告）

DEC_W, DEC_H, DEC_D = 2, 2, 3


def build_decoration(kind: str) -> Template:
    t = Template(DEC_W, DEC_H, DEC_D)

    if kind == "bookshelf_ruined":
        t.fill(1, 0, 0, 1, 1, 2, BOOKSHELF)
        t.set(1, 0, 1, AIR)              # 缺一格 = 破书架
    elif kind == "decayed_pot":
        t.chest(1, 0, 1, LOOT_POT)
    elif kind == "memory_crystal_cluster":
        t.set(1, 0, 1, CRYSTAL)
        t.set(1, 0, 0, SIG_CRACKED)
    elif kind == "broken_stele":
        t.set(1, 0, 1, STELE, {"half": "lower", "read": "false"})
        t.set(1, 1, 1, SIG_CRACKED)      # 上半截没了，只剩断口
    elif kind == "collapsed_pillar":
        t.set(1, 0, 1, DEEP_BRICK)
        t.set(1, 0, 0, SIG_CRACKED)
        t.set(1, 0, 2, SIG_CRACKED)
        t.set(1, 1, 1, SIG_CRACKED)
    else:  # pragma: no cover
        raise ValueError(f"未知的装饰件类型：{kind}")

    t.jigsaw(0, 0, 1, "west_up", J_DECOR, EMPTY, EMPTY, AIR)
    return t


# ======================================================================
# 清单：**必须**与 template_pool/**/*.json 的 location 逐个对上
# ======================================================================

CHAMBER_KINDS = ["corridor", "rubble_dead_end", "junction", "bookshelf_gallery",
                 "archive_vault", "burial_alcove", "cracked_wall_opening",
                 "statue_pass", "mnemonic_core"]
STAIR_KINDS = ["staircase", "staircase_cracked", "staircase_collapsed"]
SURFACE_KINDS = ["broken_column", "fallen_arch", "collapsed_wall", "rune_plaza"]
DECOR_KINDS = ["bookshelf_ruined", "decayed_pot", "memory_crystal_cluster",
               "broken_stele", "collapsed_pillar"]

MANIFEST: list[tuple[str, Template]] = (
    [("ruin_main", build_ruin_main())]
    + [(f"chamber/{k}", build_chamber(k)) for k in CHAMBER_KINDS]
    + [("chamber/sealed_wall", build_sealed_wall())]
    + [(f"stair/{k}", build_stair(k)) for k in STAIR_KINDS]
    + [(f"surface/{k}", build_surface(k)) for k in SURFACE_KINDS]
    + [(f"decoration/{k}", build_decoration(k)) for k in DECOR_KINDS]
)

EXPECTED_COUNT = 23

# 实心堵头：它们的拼图块是"**被认领用的把手**"，不是通道口 ——
# 净空规则（"通道口上方必须能过人"）对它们不适用，堵头本来就是要堵死的。
# ⚠️ 但把手本身是必需的：`canAttach` 要求 `父.target == 子.name`，
#    没有 name=J_CHAMBER 的拼图块，堵头根本不会被认领。
SOLID_PLUGS = {"chamber/sealed_wall"}


# ======================================================================
# 自检
# ======================================================================


def load_pools() -> dict[str, dict]:
    """读 template_pool/*.json，得到 池 → {成员 location, fallback}。"""
    pools: dict[str, dict] = {}
    if not os.path.isdir(POOL_DIR):
        return pools
    for fn in sorted(os.listdir(POOL_DIR)):
        if not fn.endswith(".json"):
            continue
        with open(os.path.join(POOL_DIR, fn), encoding="utf-8") as f:
            data = json.load(f)
        pools[data["name"]] = {
            "locations": [e["element"]["location"] for e in data["elements"]],
            "fallback": data.get("fallback", EMPTY),
        }
    return pools


def ident_of(location: str) -> str:
    """`mnemosyne:memory_ruin/chamber/corridor` → `chamber/corridor`"""
    path = location.split(":", 1)[1]
    prefix = "memory_ruin/"
    return path[len(prefix):] if path.startswith(prefix) else path


def self_check() -> int:
    """对内存里的 23 个模板 + 磁盘上的池 JSON 做静态检查。

    这里查的都是"生成出来才发现、但游戏里只会静默出错"的东西：
    拼图块缺失/被覆盖、朝向写错、通道口被堵死、target 没人应答、
    池引用了不存在的模板。
    """
    problems: list[str] = []
    by_ident = dict(MANIFEST)
    total_jigsaws = 0

    # ---------- 1. 逐个模板 ----------
    for ident, t in MANIFEST:
        sx, sy, sz = t.size

        # 越界
        for (x, y, z) in t._blocks:
            if not (0 <= x < sx and 0 <= y < sy and 0 <= z < sz):
                problems.append(f"{ident}: 方块 {x},{y},{z} 超出尺寸 {t.size}")
                break

        # 方块 id 白名单
        for (x, y, z), (name, _props) in t._blocks.items():
            if name not in LEGAL_BLOCKS and name != JIGSAW:
                problems.append(f"{ident}: {x},{y},{z} 用了未登记的方块 {name}")
                break

        # 拼图块
        n_j = 0
        for pos, be in t._be.items():
            if be.get("id") != JIGSAW:
                continue
            n_j += 1
            total_jigsaws += 1
            # 必须真的在方块表里，且没被后面的 set() 盖掉
            if pos not in t._blocks:
                problems.append(f"{ident}: 拼图块 {pos} 不在方块表里")
                continue
            if t._blocks[pos][0] != JIGSAW:
                problems.append(
                    f"{ident}: 拼图块 {pos} 被 {t._blocks[pos][0]} 覆盖了 —— "
                    f"方块实体还在，拼图系统仍会往外长")
            for key in ("name", "target", "pool", "final_state", "joint"):
                if key not in be:
                    problems.append(f"{ident}: 拼图块 {pos} 缺字段 {key}")
            if be.get("joint") not in ("aligned", "rollable"):
                problems.append(f"{ident}: 拼图块 {pos} 的 joint 非法：{be.get('joint')}")
            # final_state 写错会抛 RuntimeException（炸存档）
            fs = be.get("final_state", "")
            if fs not in LEGAL_BLOCKS:
                problems.append(f"{ident}: 拼图块 {pos} 的 final_state 非法：{fs}")
            # orientation 必须在 12 个合法值里，否则该方块会静默变成空气
            props = t._blocks.get(pos, (AIR, None))[1] or {}
            ori = props.get("orientation")
            if ori not in VALID_ORIENTATIONS:
                problems.append(f"{ident}: 拼图块 {pos} 的 orientation 非法：{ori!r}")

        if n_j == 0:
            problems.append(f"{ident}: 一个拼图块都没有 —— 它永远拼不上去")

        # 走道口净空：水平通道口上方 3 格必须是空气（实心堵头不适用）
        for pos, be in t._be.items():
            if ident in SOLID_PLUGS:
                break
            if be.get("name") not in (J_CHAMBER, J_STAIRWELL, J_SURFACE):
                continue
            x, y, z = pos
            ori = (t._blocks.get(pos, (AIR, None))[1] or {}).get("orientation", "")
            if ori.startswith("up_") or ori.startswith("down_"):
                continue   # 竖直口不做这个检查
            for dy in (1, 2, 3):
                above = t._blocks.get((x, y + dy, z))
                if above is not None and above[0] != AIR:
                    problems.append(
                        f"{ident}: 通道口 {pos}({ori}) 上方 {dy} 格被 {above[0]} 堵住")

        # 装饰件高度：不能顶到走道顶棚
        if ident.startswith("decoration/"):
            top = max((y for (_x, y, _z), (name, _p) in t._blocks.items() if name != AIR),
                      default=-1)
            if top > 2:
                problems.append(f"{ident}: 装饰件高 {top + 1} 格，会顶到走道顶棚")

    # ---------- 2. 与池 JSON 交叉校验 ----------
    pools = load_pools()
    if not pools:
        problems.append(f"读不到任何模板池 JSON（{POOL_DIR}）—— 无法做交叉校验")
    else:
        # 池里引用的每个 location 都必须有对应的模板
        for pname, pdata in pools.items():
            for loc in pdata["locations"]:
                if ident_of(loc) not in by_ident:
                    problems.append(f"池 {pname} 引用了不存在的模板 {loc}")

        # 池 → 该池（含回退池）里所有模板的拼图块 name 集合
        def names_in(pname: str) -> set[str]:
            out: set[str] = set()
            seen: set[str] = set()
            stack = [pname]
            while stack:
                cur = stack.pop()
                if cur in seen or cur not in pools:
                    continue
                seen.add(cur)
                for loc in pools[cur]["locations"]:
                    tpl = by_ident.get(ident_of(loc))
                    if tpl is None:
                        continue
                    for be in tpl._be.values():
                        if be.get("id") == JIGSAW:
                            out.add(be.get("name", ""))
                fb = pools[cur]["fallback"]
                if fb != EMPTY:
                    stack.append(fb)
            return out

        # 每个非终结拼图块的 target 都必须有人应答，否则这条连接**永远不会发生**
        for ident, t in MANIFEST:
            for pos, be in t._be.items():
                if be.get("id") != JIGSAW:
                    continue
                pool = be.get("pool", EMPTY)
                target = be.get("target", "")
                if pool == EMPTY or target in (EMPTY, J_SOCKET):
                    continue   # 显式的"到此为止"
                if pool not in pools:
                    problems.append(f"{ident}: 拼图块 {pos} 指向未注册的池 {pool}")
                    continue
                if target not in names_in(pool):
                    problems.append(
                        f"{ident}: 拼图块 {pos} 的 target={target} 在池 {pool} 里"
                        f"没人应答 —— 这条连接永远不会发生")

    # ---------- 3. 清单数量 ----------
    if len(MANIFEST) != EXPECTED_COUNT:
        problems.append(f"清单里只有 {len(MANIFEST)} 个模板，应该是 {EXPECTED_COUNT} 个")

    print(f"自检：{len(MANIFEST)} 个模板 · {total_jigsaws} 个拼图块 · "
          f"{len(pools)} 个模板池")
    if problems:
        print(f"发现 {len(problems)} 个问题：")
        for p in problems:
            print("  ! " + p)
        return 1
    print("自检通过 ✅")
    return 0


# ======================================================================
# 主流程
# ======================================================================


def main() -> int:
    ap = argparse.ArgumentParser(description="生成忆者遗迹的 23 个结构模板 NBT")
    ap.add_argument("--check", action="store_true", help="只自检，不写文件")
    args = ap.parse_args()

    rc = self_check()
    if rc != 0:
        print("自检不通过，**不写文件**（免得把坏模板覆盖掉好模板）")
        return rc

    if args.check:
        return 0

    total_blocks = 0
    for ident, t in MANIFEST:
        path = os.path.join(OUT_DIR, ident.replace("/", os.sep) + ".nbt")
        t.save(path)
        total_blocks += t.size[0] * t.size[1] * t.size[2]
    print(f"已写出 {len(MANIFEST)} 个 NBT（共 {total_blocks} 格）→ "
          f"{os.path.relpath(OUT_DIR, ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
