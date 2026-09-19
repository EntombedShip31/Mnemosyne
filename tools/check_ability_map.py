#!/usr/bin/env python3
"""校验 WS-E 的 AI 行为分类表（AbilityMap.RULES）。

为什么需要这个脚本
------------------
`AbilityMap.RULES` 是一张**有序的首次命中表**。一条放错位置的通用规则会
静默吞掉后面所有具体规则（例如把 `*SpellGoal` 放到 `*EvokerAttackSpellGoal`
前面，唤魔者的尖牙就会被归类成"通用施法"）。这类错误编译期完全看不出来，
只在游戏里表现为"某些生物遗忘不了"，极难排查。

本脚本**直接从 AbilityMap.java 源码里解析**规则表与自检用例，
所以不存在"测试和实现各写一份、慢慢漂移"的问题 —— 实现改了，脚本自动跟着改。

用法
----
    python tools/check_ability_map.py
    # 退出码 0 = 全部通过；1 = 有失败项

依据
----
原版 goal 类名于 2026-09-16 从 Forge `official` 映射源码逐个提取
（`net/minecraft/world/entity/monster/**` 与 `animal/**` 的 registerGoals()），
不是凭记忆写的。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ABILITY_MAP = REPO / "src/main/java/com/etbs31/mnemosyne/oblivion/AbilityMap.java"

RULE_RE = re.compile(r'rule\(\s*(\w+)\s*,\s*"([^"]+)"\s*\)')
CONST_RE = re.compile(r'ResourceLocation\s+(\w+)\s*=\s*ability\("([^"]+)"\)')
ENTRY_RE = re.compile(r'Map\.entry\(\s*"([^"]+)"\s*,\s*(\w+)\s*\)')


def read_source() -> str:
    if not ABILITY_MAP.is_file():
        sys.exit(f"找不到 {ABILITY_MAP}")
    return ABILITY_MAP.read_text(encoding="utf-8")


def parse_constants(src: str) -> dict[str, str]:
    """能力常量 → id 路径。排除 FILE_* 这类"数据包文件名"常量（它们不是能力）。"""
    return {
        name: path
        for name, path in CONST_RE.findall(src)
        if not name.startswith("FILE_")
    }


def parse_rules(src: str) -> list[tuple[str, str]]:
    """按源码顺序返回 [(ability 常量名, 模式)] —— 顺序是语义的一部分，不能排序。"""
    block = src.split("private static final List<Rule> RULES = List.of(", 1)
    if len(block) != 2:
        sys.exit("解析失败：找不到 RULES 声明")
    body = block[1].split(");", 1)[0]
    return RULE_RE.findall(body)


def parse_known_goals(src: str) -> list[tuple[str, str]]:
    block = src.split("private static final Map<String, ResourceLocation> KNOWN_GOALS = Map.ofEntries(", 1)
    if len(block) != 2:
        sys.exit("解析失败：找不到 KNOWN_GOALS 声明")
    body = block[1].split(");", 1)[0]
    return ENTRY_RE.findall(body)


def parse_ambient_goals(src: str) -> list[str]:
    block = src.split("private static final Set<String> AMBIENT_GOALS = Set.of(", 1)
    if len(block) != 2:
        sys.exit("解析失败：找不到 AMBIENT_GOALS 声明")
    body = block[1].split(");", 1)[0]
    return re.findall(r'"([^"]+)"', body)


def matches(pattern: str, simple: str, full: str) -> bool:
    """与 AbilityMap.matches() 逐字对应的 Python 版本。"""
    if pattern.startswith("*"):
        return simple.endswith(pattern[1:])
    if pattern.endswith("*"):
        return simple.startswith(pattern[:-1])
    return simple == pattern or full == pattern


def classify(rules: list[tuple[str, str]], simple: str, full: str) -> str | None:
    for ability, pattern in rules:
        if matches(pattern, simple, full):
            return ability
    return None


def parse_built_in(src: str) -> dict[str, list[str]]:
    """解析 buildBuiltIn() 的 map.put(mc("zombie"), List.of(MELEE_ATTACK, ...))。"""
    block = src.split("private static Map<ResourceLocation, List<ResourceLocation>> buildBuiltIn()", 1)
    if len(block) != 2:
        return {}
    body = block[1]
    out: dict[str, list[str]] = {}
    for mob, args in re.findall(r'map\.put\(mc\("([^"]+)"\),\s*List\.of\(([^)]*)\)\);', body):
        out[mob] = [a.strip() for a in args.split(",") if a.strip()]
    return out


def print_evidence(src: str, rules: list[tuple[str, str]],
                   known: list[tuple[str, str]], constants: dict[str, str]) -> None:
    """打印"生物 → 会被移除的 goal 类名"清单。

    这是"验收标准 2/3"能离线拿到的最强证据：把 buildBuiltIn() 的声明式映射
    与 KNOWN_GOALS 的实测类名表做一次反向连接，得到**具体到 goal 类名**的清单。
    游戏内验证时照这个清单逐条核对即可。
    """
    built_in = parse_built_in(src)
    if not built_in:
        print("（未解析到 buildBuiltIn()，跳过证据清单）")
        return
    # ability 常量名 → id 路径
    name_of = {const: path for const, path in constants.items()}
    print("\n=== 生物 → 会被移除的 goal 类名（离线可得的证据）===")
    for mob in ("blaze", "skeleton", "creeper", "evoker", "warden", "witch", "enderman"):
        if mob not in built_in:
            continue
        abilities = built_in[mob]
        paths = {name_of.get(a, a) for a in abilities}
        goals = [
            simple for simple, ability in known
            if name_of.get(ability, ability) in paths
        ]
        print(f"\nminecraft:{mob}")
        print(f"  声明能力 : {', '.join(sorted(paths))}")
        print(f"  对应 goal: {', '.join(goals) if goals else '（无 —— 走通用降级）'}")


def main() -> int:
    src = read_source()
    constants = parse_constants(src)
    rules = parse_rules(src)
    known = parse_known_goals(src)
    ambient = parse_ambient_goals(src)

    if not rules:
        sys.exit("解析失败：规则表为空")

    failures: list[str] = []

    for simple, ability_const in known:
        full = f"net.minecraft.world.entity.ai.goal.{simple}"
        actual = classify(rules, simple, full)
        if actual != ability_const:
            want = constants.get(ability_const, ability_const)
            got = constants.get(actual, actual) if actual else "None"
            failures.append(f"[正向] {simple}: 期望 {want}，实际 {got}")

    for simple in ambient:
        full = f"net.minecraft.world.entity.ai.goal.{simple}"
        actual = classify(rules, simple, full)
        if actual is not None:
            got = constants.get(actual, actual)
            failures.append(f"[反向] {simple}: 属于环境行为，不应被归类，实际 {got}")

    # 规则表卫生：同一条 (能力, 模式) 不应重复；每个能力都应至少被一条规则覆盖
    seen: set[tuple[str, str]] = set()
    for ability, pattern in rules:
        if (ability, pattern) in seen:
            failures.append(f"[重复] 规则 ({ability}, {pattern}) 出现了两次")
        seen.add((ability, pattern))

    covered = {ability for ability, _ in rules}
    for name in constants.values():
        const_name = next((k for k, v in constants.items() if v == name), None)
        if const_name and const_name not in covered:
            failures.append(f"[未覆盖] 能力 {name} 没有任何规则能产出它")

    total = len(known) + len(ambient)
    if failures:
        print(f"行为分类表校验失败（{len(failures)} 处）：")
        for line in failures:
            print(f"  - {line}")
        return 1

    print(f"行为分类表校验通过：{len(known)} 条正向 + {len(ambient)} 条反向，共 {len(rules)} 条规则。")
    if "--evidence" in sys.argv:
        print_evidence(src, rules, known, constants)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
