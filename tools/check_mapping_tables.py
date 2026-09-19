#!/usr/bin/env python3
"""校验 WS-E 的三张映射表（表 B 特性 / 表 C 可复现技能）与数据包文件的一致性。

为什么需要这个脚本
------------------
表 B / 表 C 的**值是外部 id**，写错了编译期完全看不出来：

* 表 C 的值是**铁魔法（ISS）的法术 id**。写错一个字母（`sonic_boom` → `sonicboom`）
  编译照过、注册照过，只在游戏里表现为"玩家花了 50 法力、放了个空气"。
  本脚本**直接从 ISS 的 jar 解包目录里读 lang 键**做交叉核对，
  所以能抓到这一类错误 —— 这是唯一能离线发现的证据来源。
* 表 B 的值是 `mnemosyne:*` 特性 id，同理。
* `FILE_*` 常量是"数据包文件名"，必须是**我们自己的命名空间**（`mnemosyne:`）。
  2026-09-17 实际踩过这个坑：`MimicRegistry.FILE_MIMICS` 一度写成 `iss("mimics")`
  → `irons_spellbooks:mimics`，那样重载时永远查不到文件，且**静默**退回内建表。

本脚本从 Java 源码 + JSON 两侧解析，任何一侧漂移都会被抓到。

用法
----
    python tools/check_mapping_tables.py
    # 退出码 0 = 全部通过；1 = 有失败项

依据
----
ISS 法术 id 于 2026-09-17 从解包 jar 的
`assets/irons_spellbooks/lang/en_us.json` 的 `spell.irons_spellbooks.*` 键逐个提取。
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OBLIVION = REPO / "src/main/java/com/etbs31/mnemosyne/oblivion"
TRAIT_REGISTRY = OBLIVION / "TraitRegistry.java"
MIMIC_REGISTRY = OBLIVION / "MimicRegistry.java"
DATA_DIR = REPO / "src/main/resources/data/mnemosyne/mnemosyne/oblivion"

# TraitRegistry: ResourceLocation FOO = trait("bar")
TRAIT_CONST_RE = re.compile(r'ResourceLocation\s+(\w+)\s*=\s*trait\("([^"]+)"\)')
# MimicRegistry: ResourceLocation FOO = iss("bar")
ISS_CONST_RE = re.compile(r'ResourceLocation\s+(\w+)\s*=\s*iss\("([^"]+)"\)')
# FILE_* 常量（两种写法都要抓）
FILE_TRAIT_RE = re.compile(r'ResourceLocation\s+(FILE_\w+)\s*=\s*trait\("([^"]+)"\)')
FILE_ANY_RE = re.compile(r'ResourceLocation\s+(FILE_\w+)\s*=\s*(\w+)\("([^"]+)"\)')
# map.put(mc("blaze"), List.of(FIRE_IMMUNITY))
PUT_RE = re.compile(r'map\.put\(mc\("([^"]+)"\),\s*List\.of\(([^)]*)\)\);')

# 设计红线：这两个生物不可窃取 / 不可复现（docs/02 表 B / 表 C 明写）
FORBIDDEN_MOBS = {"ender_dragon", "wither"}


def read(path: Path) -> str:
    if not path.is_file():
        sys.exit(f"找不到 {path}")
    return path.read_text(encoding="utf-8")


def load_json(path: Path) -> dict:
    if not path.is_file():
        sys.exit(f"找不到 {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def find_iss_lang() -> Path | None:
    """在仓库里找 ISS 解包目录下的 lang 文件（唯一可信的法术 id 来源）。"""
    for candidate in REPO.glob("*/assets/irons_spellbooks/lang/en_us.json"):
        return candidate
    return None


def iss_spell_ids() -> set[str] | None:
    lang = find_iss_lang()
    if lang is None:
        return None
    text = lang.read_text(encoding="utf-8")
    return set(re.findall(r'"spell\.irons_spellbooks\.([a-z_0-9]+)"', text))


def parse_put_map(src: str) -> dict[str, list[str]]:
    """解析 buildBuiltIn() 里的 map.put(mc("x"), List.of(A, B))。"""
    out: dict[str, list[str]] = {}
    for mob, args in PUT_RE.findall(src):
        names = [a.strip() for a in args.split(",") if a.strip()]
        out[mob] = names
    return out


def main() -> int:
    failures: list[str] = []

    trait_src = read(TRAIT_REGISTRY)
    mimic_src = read(MIMIC_REGISTRY)

    trait_consts = dict(TRAIT_CONST_RE.findall(trait_src))     # NAME -> path
    iss_consts = dict(ISS_CONST_RE.findall(mimic_src))         # NAME -> path

    trait_ids = {f"mnemosyne:{p}" for p in trait_consts.values()}
    mimic_spell_ids = {f"irons_spellbooks:{p}" for p in iss_consts.values()}

    # ---------- 1. FILE_* 必须是 mnemosyne 命名空间 ----------
    for name, helper, path in FILE_ANY_RE.findall(mimic_src) + [
        (n, "trait", p) for n, p in FILE_TRAIT_RE.findall(trait_src)
    ]:
        if helper not in ("trait", "ability", "mimic", "mnemo", "mod"):
            failures.append(
                f"[命名空间] {name} 用了 {helper}(\"{path}\") → 会是 "
                f"{'irons_spellbooks' if helper == 'iss' else helper}:{path}，"
                f"应当是 mnemosyne:{path}（重载时会静默查不到文件）"
            )

    # ---------- 2. 表 C 的 ISS 法术 id 必须真实存在 ----------
    real_ids = iss_spell_ids()
    if real_ids is None:
        failures.append(
            "[跳过] 找不到 ISS 解包目录（*/assets/irons_spellbooks/lang/en_us.json），"
            "无法核对法术 id 的真实性"
        )
    else:
        for name, path in sorted(iss_consts.items()):
            if path not in real_ids:
                failures.append(
                    f"[表 C] MimicRegistry.{name} = irons_spellbooks:{path} —— "
                    f"ISS jar 里没有这个法术（会静默复现失败）"
                )

    # ---------- 3. 内建表不得包含不可剥夺的生物 ----------
    for label, src, consts in (
        ("表 B", trait_src, trait_ids),
        ("表 C", mimic_src, mimic_spell_ids),
    ):
        for mob in parse_put_map(src):
            if mob in FORBIDDEN_MOBS:
                failures.append(f"[红线] {label} 内建表含 minecraft:{mob} —— 设计上不可剥夺")

    # ---------- 4. 数据包文件与代码内建表一致 ----------
    traits_json = load_json(DATA_DIR / "traits.json")
    mimics_json = load_json(DATA_DIR / "mimics.json")

    for mob, ids in (traits_json.get("traits") or {}).items():
        for tid in ids:
            if tid not in trait_ids:
                failures.append(f"[traits.json] {mob} 引用了未声明的特性 {tid}")

    for mob, ids in (mimics_json.get("mimics") or {}).items():
        if mob.split(":")[-1] in FORBIDDEN_MOBS:
            failures.append(f"[红线] mimics.json 含 {mob} —— 设计上不可复现")
        for sid in ids:
            if sid not in mimic_spell_ids:
                failures.append(f"[mimics.json] {mob} 引用了未声明的法术 {sid}")

    # ---------- 5. 代码内建表与 JSON 的键集合应当一致 ----------
    code_mobs = set(parse_put_map(mimic_src))
    json_mobs = {m.split(":")[-1] for m in (mimics_json.get("mimics") or {})}
    for mob in sorted(code_mobs - json_mobs):
        failures.append(f"[表 C] 内建表有 minecraft:{mob}，但 mimics.json 没有 —— 两份清单漂移了")
    for mob in sorted(json_mobs - code_mobs):
        failures.append(f"[表 C] mimics.json 有 {mob}，但内建表没有 —— 两份清单漂移了")

    code_trait_mobs = set(parse_put_map(trait_src))
    json_trait_mobs = {m.split(":")[-1] for m in (traits_json.get("traits") or {})}
    for mob in sorted(code_trait_mobs - json_trait_mobs):
        failures.append(f"[表 B] 内建表有 minecraft:{mob}，但 traits.json 没有 —— 两份清单漂移了")
    for mob in sorted(json_trait_mobs - code_trait_mobs):
        failures.append(f"[表 B] traits.json 有 {mob}，但内建表没有 —— 两份清单漂移了")

    if failures:
        print(f"映射表校验失败（{len(failures)} 处）：")
        for line in failures:
            print(f"  - {line}")
        return 1

    checked = f"{len(real_ids)} 个真实 ISS 法术 id" if real_ids else "（未能核对 ISS id）"
    print(
        f"映射表校验通过：表 B {len(trait_ids)} 个特性 / {len(code_trait_mobs)} 个生物；"
        f"表 C {len(mimic_spell_ids)} 个法术 / {len(code_mobs)} 个生物；"
        f"交叉核对 {checked}。"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
