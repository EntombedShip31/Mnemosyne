#!/usr/bin/env python3
"""核对 AbilityMap 的能力表与**真实的原版 AI 结构**是否自洽。

为什么需要这个脚本
------------------
2026-09-17 实测发现了一类**会静默失效**的错误：

    监守者（Warden）在 1.20.1 **没有任何 goal** —— 它的 AI 走 Brain（行为树），
    声波冲击是 `ai.behavior.warden.SonicBoom` 这个 Behavior，不是 Goal。

后果：`AbilityMap.presentAbilities(warden)` 恒为空 →
`OblivionManager` 静默走通用降级 → **遗忘对监守者完全无效**，
但编译通过、日志正常、没有任何报错。而 `docs/02` §五 案例 3 把监守者
写成了流派的高光时刻。这类错误靠人眼审代码基本发现不了。

本脚本从 **Forge 反编译 jar**（SRG 名）里读真实源码：
  1. `EntityType.java` → 实体 id → 类名
  2. 沿继承链收集 `m_25352_`（= `GoalSelector.addGoal` 的 SRG 名）调用
  3. goal 数为 0 的生物 = Brain 生物 → **必须**出现在 `AbilityMap.BRAIN_MOBS` 里

用法
----
    python tools/check_mob_goals.py
    python tools/check_mob_goals.py --jar <path-to-decomp.jar>
    # 退出码 0 = 通过；1 = 有不一致

注意
----
反编译 jar 是**本机 Gradle 缓存**里的文件，不在仓库内。
找不到时脚本会**跳过**并打印提示（不当作失败），
因为"没装过 Gradle"不该让 CI 变红 —— 但会明确告诉你"这次没验证"。
"""

from __future__ import annotations

import argparse
import re
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ABILITY_MAP = REPO / "src/main/java/com/etbs31/mnemosyne/oblivion/AbilityMap.java"

DEFAULT_JAR_GLOBS = [
    "C:/Users/*/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/*/forge-*-decomp.jar",
    "/root/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/*/forge-*-decomp.jar",
]

# SRG 名（本 jar 是 SRG 映射，不是 official）
SRG_ADD_GOAL = "m_25352_"
SRG_ENTITY_REGISTER = "m_20634_"
SRG_BUILDER_OF = "m_20704_"

BRAIN_MOBS_RE = re.compile(r"private static final Set<ResourceLocation> BRAIN_MOBS = Set\.of\((.*?)\);", re.S)


def find_jar(explicit: str | None) -> Path | None:
    if explicit:
        p = Path(explicit)
        return p if p.is_file() else None
    import glob
    for pattern in DEFAULT_JAR_GLOBS:
        hits = sorted(glob.glob(pattern))
        if hits:
            return Path(hits[-1])
    return None


def parse_brain_mobs(src: str) -> set[str]:
    m = BRAIN_MOBS_RE.search(src)
    if not m:
        sys.exit("解析失败：找不到 BRAIN_MOBS 声明")
    return set(re.findall(r'mc\("([^"]+)"\)', m.group(1)))


def parse_mapped_mobs(src: str) -> set[str]:
    """buildBuiltIn() 里出现过的全部生物 id。"""
    block = src.split("private static Map<ResourceLocation, List<ResourceLocation>> buildBuiltIn()", 1)
    if len(block) != 2:
        return set()
    return set(re.findall(r'map\.put\(mc\("([^"]+)"\)', block[1]))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", default=None, help="Forge 反编译 jar 路径（默认自动在 Gradle 缓存里找）")
    args = ap.parse_args()

    src = ABILITY_MAP.read_text(encoding="utf-8")
    declared_brain = parse_brain_mobs(src)
    mapped = parse_mapped_mobs(src)

    jar = find_jar(args.jar)
    if jar is None:
        print("跳过：找不到 Forge 反编译 jar（Gradle 缓存里没有）。")
        print("      → 本次**未**核对 BRAIN_MOBS，请在有 Gradle 缓存的机器上重跑。")
        return 0

    print(f"使用 {jar}")
    with zipfile.ZipFile(jar) as z:
        srcs = {n: z.read(n).decode("utf-8", "replace") for n in z.namelist() if n.endswith(".java")}

    et = next((s for n, s in srcs.items() if n.endswith("world/entity/EntityType.java")), None)
    if et is None:
        print("跳过：jar 里找不到 EntityType.java")
        return 0

    id_to_class = dict(re.findall(
        rf'{SRG_ENTITY_REGISTER}\("([a-z_]+)",\s*EntityType\.Builder\.{SRG_BUILDER_OF}\((\w+)::new', et))

    by_simple: dict[str, str] = {}
    for n, s in srcs.items():
        m = re.search(r"^(?:public |abstract |final )*class (\w+)", s, re.M)
        if m:
            by_simple.setdefault(m.group(1), n)

    def count_goals(simple: str, seen: set[str] | None = None) -> int:
        seen = seen if seen is not None else set()
        if simple in seen or simple not in by_simple:
            return 0
        seen.add(simple)
        s = srcs[by_simple[simple]]
        total = len(re.findall(rf"{SRG_ADD_GOAL}\(", s))
        sup = re.search(r"class \w+(?:<[^>]*>)? extends ([\w.]+)", s)
        if sup:
            total += count_goals(sup.group(1).split(".")[-1], seen)
        return total

    failures: list[str] = []
    actually_brain: set[str] = set()

    for mob in sorted(mapped):
        cls = id_to_class.get(mob)
        if cls is None:
            continue
        if count_goals(cls) == 0:
            actually_brain.add(mob)

    for mob in sorted(actually_brain - declared_brain):
        failures.append(
            f"[漏报] minecraft:{mob} 在原版里 0 个 goal（Brain 生物），"
            f"但没有列进 AbilityMap.BRAIN_MOBS → 遗忘会静默降级"
        )
    for mob in sorted(declared_brain - actually_brain):
        note = "（该生物不在能力表里）" if mob not in mapped else ""
        failures.append(
            f"[误报] AbilityMap.BRAIN_MOBS 含 minecraft:{mob}，"
            f"但实测它有 goal，移除方案其实可用{note}"
        )

    if failures:
        print(f"\n能力表与真实 AI 结构不一致（{len(failures)} 处）：")
        for line in failures:
            print(f"  - {line}")
        return 1

    print(
        f"核对通过：能力表 {len(mapped)} 个生物中，实测 {len(actually_brain)} 个是 Brain 生物"
        f"（{'、'.join(sorted(actually_brain))}），与 BRAIN_MOBS 完全一致。"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
