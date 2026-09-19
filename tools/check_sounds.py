#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
忆海 Mnemosyne —— 音效引用校验器
==================================

【为什么需要这个脚本】

忆海的 `sounds.json` **不包含任何 .ogg 文件**，而是引用 `irons_spellbooks:` 命名空间下
已有的音效文件（原因见 sounds.json 顶部注释）。

这带来一个风险：**ISS 升级后如果改名/删除某个音效，我们的引用会静默失效（不崩溃，只是没声音）。**

这个脚本把 ISS 的 `sounds.json` 与 `assets/irons_spellbooks/sounds/` 目录对账，
校验忆海引用的每一条是否仍然存在。

用法：
    python check_sounds.py                    # 自动定位 ISS 与忆海
    python check_sounds.py --iss <ISS解压目录>
    python check_sounds.py --verbose

建议：每次升级 ISS 版本后跑一次。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

# sounds.json 里允许出现的非音效顶层键
META_KEYS = {"_comment"}


def strip_jsonc(text: str) -> str:
    """去掉 // 行注释（ISS 的 structure_set JSON 里有，sounds.json 一般没有，保险起见）。"""
    out = []
    for line in text.splitlines():
        # 粗略处理：不在字符串内的 // 视为注释
        in_str = False
        esc = False
        cut = None
        for i, ch in enumerate(line):
            if esc:
                esc = False
                continue
            if ch == "\\":
                esc = True
                continue
            if ch == '"':
                in_str = not in_str
                continue
            if not in_str and ch == "/" and i + 1 < len(line) and line[i + 1] == "/":
                cut = i
                break
        out.append(line if cut is None else line[:cut])
    return "\n".join(out)


def load_json(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        raw = f.read()
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return json.loads(strip_jsonc(raw))


def find_iss_dir() -> str | None:
    """自动定位 ISS 解压目录。"""
    here = os.path.dirname(os.path.abspath(__file__))
    roots = [here, os.path.join(here, ".."), os.path.join(here, "..", "..")]
    for root in roots:
        if not os.path.isdir(root):
            continue
        for name in os.listdir(root):
            full = os.path.join(root, name)
            if os.path.isdir(full) and "irons_spellbooks" in name.lower():
                if os.path.isfile(os.path.join(full, "assets", "irons_spellbooks", "sounds.json")):
                    return os.path.abspath(full)
    return None


def iter_sound_refs(entry: dict):
    """遍历一个音效事件里的所有声音引用，产出 (name, extra)。"""
    for s in entry.get("sounds", []):
        if isinstance(s, str):
            yield s, {}
        elif isinstance(s, dict) and "name" in s:
            yield s["name"], s


def main() -> int:
    ap = argparse.ArgumentParser(description="校验忆海 sounds.json 对 ISS 音效的引用")
    ap.add_argument("--iss", default=None, help="ISS 解压目录")
    ap.add_argument("--ours", default=None, help="忆海 sounds.json 路径")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    iss = args.iss or find_iss_dir()
    if not iss:
        sys.exit("找不到 ISS 目录，请用 --iss 指定（需含 assets/irons_spellbooks/sounds.json）")

    ours = args.ours or os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..", "src", "main", "resources", "assets", "mnemosyne", "sounds.json",
    )
    if not os.path.isfile(ours):
        sys.exit(f"找不到忆海的 sounds.json：{ours}")

    iss_sounds_json = os.path.join(iss, "assets", "irons_spellbooks", "sounds.json")
    iss_sounds_dir = os.path.join(iss, "assets", "irons_spellbooks", "sounds")

    iss_events = load_json(iss_sounds_json)
    mine = load_json(ours)

    # 建立 ISS 里"实际存在的音频文件路径"集合
    iss_files: set[str] = set()
    for dirpath, _dirnames, filenames in os.walk(iss_sounds_dir):
        for fn in filenames:
            if fn.lower().endswith(".ogg"):
                rel = os.path.relpath(os.path.join(dirpath, fn), iss_sounds_dir)
                iss_files.add(rel.replace("\\", "/")[:-4])  # 去掉 .ogg

    print(f"ISS 目录      : {iss}")
    print(f"ISS 音效事件  : {len(iss_events)}")
    print(f"ISS 音频文件  : {len(iss_files)}")
    print(f"忆海音效事件  : {len([k for k in mine if k not in META_KEYS])}")
    print()

    ok = 0
    broken: list[tuple[str, str, str]] = []
    our_own: list[tuple[str, str]] = []

    for ev, entry in mine.items():
        if ev in META_KEYS:
            continue
        if not isinstance(entry, dict):
            broken.append((ev, "?", "事件体不是对象"))
            continue
        for name, _extra in iter_sound_refs(entry):
            if ":" not in name:
                broken.append((ev, name, "缺少命名空间"))
                continue
            ns, path = name.split(":", 1)

            if ns == "mnemosyne":
                # 指向我们自己的音频文件，检查文件是否存在
                p = os.path.join(os.path.dirname(ours), "sounds", path + ".ogg")
                if os.path.isfile(p):
                    ok += 1
                    if args.verbose:
                        print(f"  OK(self) {ev:38s} -> {name}")
                else:
                    our_own.append((ev, name))
                    if args.verbose:
                        print(f"  TODO     {ev:38s} -> {name}  (待自制)")
                continue

            if ns != "irons_spellbooks":
                broken.append((ev, name, f"未知命名空间 {ns}"))
                continue

            if path in iss_files:
                ok += 1
                if args.verbose:
                    print(f"  OK       {ev:38s} -> {name}")
            else:
                broken.append((ev, name, "ISS 里找不到该音频文件"))

    print("=" * 72)
    print(f"通过 : {ok}")
    print(f"失效 : {len(broken)}")
    if our_own:
        print(f"待自制（指向 mnemosyne: 但文件尚未生成）: {len(our_own)}")
    print("=" * 72)

    if broken:
        print("\n失效引用（必须修复）：")
        for ev, name, why in broken:
            print(f"  [{ev}] -> {name}   ({why})")
        print("\n修复方式：到 ISS 的 sounds.json 里搜同类事件，找到替代音频路径后更新忆海的 sounds.json。")
        return 1

    if our_own and args.verbose:
        print("\n待自制清单（不影响构建，仅提示）：")
        for ev, name in our_own:
            print(f"  [{ev}] -> {name}")

    print("\n全部引用有效。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
