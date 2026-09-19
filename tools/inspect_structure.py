#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
结构模板 NBT 检视器（WS-G2 的配套工具）
========================================

【为什么需要它】

`tools/gen_structure_nbt.py` 里的 `self_check()` 检查的是**内存里的模型**，
不是磁盘上的字节。如果写入器本身有 bug（少写一个字段、列表元素类型写错、
gzip 写坏），自检**全绿也照样是坏文件** —— 而且游戏里只会表现为
"遗迹里有个洞"，零日志零报错。

所以这个工具的作用是：**把写出去的字节重新读回来**，用独立的解析器
（不是生成器里那套写入代码）把拼图块逐条打印出来。
两套代码互相印证，才算真的验证过。

它同时也是"读 ISS 的 NBT 反推约定"时用的那个解析器 —— 同一份代码，
既用来学习 ISS，也用来验收自己。

【用法】

    # 概览（尺寸 / 方块数 / 拼图块数 / 用到的方块 id）
    python tools/inspect_structure.py <file.nbt> [more.nbt ...]

    # 逐条打印拼图块（位置 / 朝向 / name / target / pool / final_state / joint）
    python tools/inspect_structure.py --jigsaw <file.nbt>

    # 整个目录
    python tools/inspect_structure.py --jigsaw "path/to/dir"
"""

from __future__ import annotations

import argparse
import gzip
import os
import struct
import sys

TAG_NAMES = {
    0: "End", 1: "Byte", 2: "Short", 3: "Int", 4: "Long", 5: "Float",
    6: "Double", 7: "ByteArray", 8: "String", 9: "List", 10: "Compound",
    11: "IntArray", 12: "LongArray",
}


# ======================================================================
# NBT 读取（与生成器的写入器完全独立的一份实现）
# ======================================================================


def _read_string(f) -> str:
    n = struct.unpack(">H", f.read(2))[0]
    return f.read(n).decode("utf-8")


def _read_payload(f, t: int):
    if t == 1:
        return struct.unpack(">b", f.read(1))[0]
    if t == 2:
        return struct.unpack(">h", f.read(2))[0]
    if t == 3:
        return struct.unpack(">i", f.read(4))[0]
    if t == 4:
        return struct.unpack(">q", f.read(8))[0]
    if t == 5:
        return struct.unpack(">f", f.read(4))[0]
    if t == 6:
        return struct.unpack(">d", f.read(8))[0]
    if t == 7:
        n = struct.unpack(">i", f.read(4))[0]
        return list(f.read(n))
    if t == 8:
        return _read_string(f)
    if t == 9:
        # ⚠️ 元素类型在**列表内部**，不在外面 —— 忘了读这一字节会让
        #    整个流错位，而且报错信息会指向完全无关的地方。
        et = f.read(1)[0]
        n = struct.unpack(">i", f.read(4))[0]
        return [_read_payload(f, et) for _ in range(n)]
    if t == 10:
        d = {}
        while True:
            tt = f.read(1)[0]
            if tt == 0:
                break
            key = _read_string(f)
            d[key] = _read_payload(f, tt)
        return d
    if t == 11:
        n = struct.unpack(">i", f.read(4))[0]
        return [struct.unpack(">i", f.read(4))[0] for _ in range(n)]
    if t == 12:
        n = struct.unpack(">i", f.read(4))[0]
        return [struct.unpack(">q", f.read(8))[0] for _ in range(n)]
    raise ValueError(f"未知的标签类型 {t}（流已经错位了）")


def load(path: str):
    """读一个结构 NBT，返回 (根标签名, 根 Compound)。"""
    with gzip.open(path, "rb") as f:
        root_type = f.read(1)[0]
        if root_type != 10:
            raise ValueError(f"{path}: 根标签类型是 {TAG_NAMES.get(root_type)}，不是 Compound")
        root_name = _read_string(f)
        root = _read_payload(f, 10)
        trailing = f.read(1)
    if trailing:
        raise ValueError(f"{path}: 根 Compound 结束后还有多余字节 —— 写入器多写了东西")
    return root_name, root


# ======================================================================
# 检视
# ======================================================================


def jigsaws(root) -> list[tuple]:
    """返回该结构里所有拼图块：[(pos, orientation, nbt_dict), ...]"""
    palette = root.get("palette", [])
    out = []
    for b in root.get("blocks", []):
        state = b.get("state", 0)
        if not (0 <= state < len(palette)):
            out.append((tuple(b.get("pos", [])), "<state 越界>", {}))
            continue
        entry = palette[state]
        if entry.get("Name") != "minecraft:jigsaw":
            continue
        ori = (entry.get("Properties") or {}).get("orientation", "<无 orientation>")
        out.append((tuple(b.get("pos", [])), ori, b.get("nbt", {})))
    return out


def summarise(path: str) -> None:
    root_name, root = load(path)
    size = root.get("size", [])
    palette = root.get("palette", [])
    blocks = root.get("blocks", [])
    jig = jigsaws(root)
    print(f"\n=== {os.path.basename(path)} ===")
    print(f"  根标签名 : {root_name!r}   (原版写空串)")
    print(f"  尺寸     : {size}")
    print(f"  DataVersion: {root.get('DataVersion')}   (1.20.1 真值 = 3465)")
    print(f"  调色板   : {len(palette)} 项 · 方块 {len(blocks)} 个 · 拼图块 {len(jig)} 个")
    ids = sorted({p.get("Name", "?") for p in palette})
    print(f"  方块 id  : {', '.join(ids)}")


def dump_jigsaws(path: str) -> None:
    root_name, root = load(path)
    print(f"\n=== {os.path.basename(path)}  size={root.get('size')} ===")
    for pos, ori, nbt in sorted(jigsaws(root)):
        print(f"  {str(pos):>12}  {ori:<10} name={nbt.get('name')!r:<38} "
              f"target={nbt.get('target')!r:<38} pool={nbt.get('pool')!r:<40} "
              f"final_state={nbt.get('final_state')!r} joint={nbt.get('joint')!r}")


def dump_column(path: str, x: int, z: int) -> None:
    """打印 (x, ·, z) 这根竖直柱子 —— 查"走道口上方有没有净空"最直观。"""
    _root_name, root = load(path)
    palette = root.get("palette", [])
    col: dict[int, str] = {}
    for b in root.get("blocks", []):
        px, py, pz = b.get("pos", [0, 0, 0])
        if px != x or pz != z:
            continue
        name = palette[b.get("state", 0)].get("Name", "?")
        props = palette[b.get("state", 0)].get("Properties") or {}
        extra = ""
        if name == "minecraft:jigsaw":
            nbt = b.get("nbt", {})
            extra = f"  <- jigsaw name={nbt.get('name')!r} target={nbt.get('target')!r} final_state={nbt.get('final_state')!r}"
        elif props:
            extra = f"  {props}"
        col[py] = name + extra
    sy = root.get("size", [0, 0, 0])[1]
    print(f"\n=== {os.path.basename(path)}  column ({x}, ·, {z})  size={root.get('size')} ===")
    for y in range(sy - 1, -1, -1):
        print(f"  y={y:<3} {col.get(y, '(未定义/空气)')}")


def collect(path: str) -> list[str]:
    if os.path.isdir(path):
        out = []
        for dirpath, _dirs, files in os.walk(path):
            for fn in sorted(files):
                if fn.endswith(".nbt"):
                    out.append(os.path.join(dirpath, fn))
        return out
    return [path]


def main() -> int:
    ap = argparse.ArgumentParser(description="结构模板 NBT 检视器")
    ap.add_argument("paths", nargs="+", help=".nbt 文件或目录")
    ap.add_argument("--jigsaw", action="store_true", help="逐条打印拼图块")
    ap.add_argument("--column", metavar="X,Z", help="打印 (X, ·, Z) 这根竖直柱子")
    args = ap.parse_args()

    targets: list[str] = []
    for p in args.paths:
        targets.extend(collect(p))

    if not targets:
        print("没找到任何 .nbt")
        return 1

    col = None
    if args.column:
        cx, cz = (int(v) for v in args.column.split(","))
        col = (cx, cz)

    rc = 0
    for p in targets:
        try:
            if col is not None:
                dump_column(p, col[0], col[1])
            elif args.jigsaw:
                dump_jigsaws(p)
            else:
                summarise(p)
        except Exception as exc:  # noqa: BLE001
            print(f"\n=== {os.path.basename(p)} ===")
            print(f"  !! 解析失败：{exc}")
            rc = 1
    return rc


if __name__ == "__main__":
    sys.exit(main())
