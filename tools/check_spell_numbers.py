#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
校验 `docs/tech/13_数值总表.md` §一 总表的数值，与 21 个法术 Java 里的实际数值是否一致。

为什么需要它
------------
2026-09-19 发现「走马灯」的**四项数值全都不对**：
使用文档与 lang 早就按 5 级制写好（"6-14s window"），但代码里还是 3 级制，
耗蓝甚至是 `86 + 17/级`（满级 154，而正确值是满级 70）—— 差两倍多。
这个错误**在仓库里躺了很久**：文档是对的、代码是错的，两边各自自洽，
没人会去逐行对表。本脚本就是把"逐行对表"变成一条命令。

检查项
------
A. 代码 ↔ 总表：起始稀有度 / 最大等级 / 法力基数 / 法力每级增量 / 冷却
B. 总表内部自洽：满级法力 == 基数 + 每级增量 × (最大等级 − 1)
C. 总表内部自洽：消耗率 == 满级法力 ÷ (满级冷却 + 吟唱 tick ÷ 20)
D. 覆盖：总表 21 行 ↔ 21 个法术类，两边都不许有多余
E. 按等级冷却：总表写区间（如 `110~70s`）时，代码必须有改写入口，否则判 FAIL

⭐ 自报盲区（与 check_resources.py 同一条纪律）
--------------------------------------------
任何一个法术抓不到 id / 抓不到 `memoryConfig(...)` / 抓不到法力字段，
一律判 **FAIL**，绝不静默跳过 —— "正则失配"和"真的没问题"必须区分开。
总表行数少于 21 也直接 FAIL。

⭐ 负向测试（证明它不是恒真）
----------------------------
本脚本支持 `--table` / `--spell-dir` 指向副本，用故意改坏一份副本来验证
它真的会 FAIL。见 `docs/tech/13` §走马灯 上方的说明。

退出码：0 = 全通过；1 = 有 FAIL。
"""

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TABLE_PATH = ROOT / "docs" / "tech" / "13_数值总表.md"
SPELL_DIR = ROOT / "src" / "main" / "java" / "com" / "etbs31" / "mnemosyne" / "spell"

EXPECTED_SPELL_COUNT = 21

# 总表数据行：| **名称** | `id` | 稀有度 | **最大等级** | 法力 | 满级法力 | 冷却 | 吟唱 | 消耗率 |
ROW_RE = re.compile(
    r"^\|\s*\*\*(?P<name>.+?)\*\*\s*\|"
    r"\s*`(?P<id>[a-z_]+)`\s*\|"
    r"\s*(?P<rarity>[A-Za-z]+)\s*\|"
    r"\s*\*\*(?P<max>\d+)\*\*\s*\|"
    r"\s*(?P<mana_expr>.+?)\s*\|"
    r"\s*(?P<mana_max>\d+)\s*\|"
    r"\s*(?P<cooldown>.+?)\s*\|"
    r"\s*(?P<cast>.+?)\s*\|"
    r"\s*(?P<rate>[\d.]+)/秒\s*\|"
)

# 法力：`65 + 13/级` 或 `100（固定）`
MANA_EXPR_RE = re.compile(r"^(?P<base>\d+)\s*\+\s*(?P<per>\d+)/级$")
MANA_FIXED_RE = re.compile(r"^(?P<base>\d+)（固定）$")

# Java 侧
ID_RE = re.compile(r'fromNamespaceAndPath\(MnemosyneMod\.MODID,\s*"(?P<id>[a-z_]+)"\)')
CONFIG_RE = re.compile(
    r"super\(\s*memoryConfig\(\s*SpellRarity\.(?P<rarity>\w+)\s*,\s*"
    r"(?P<cd>[^,()]+?)\s*,\s*(?P<max>[^,()]+?)\s*\)\s*\)",
    re.DOTALL,
)
# 常量替换：memoryConfig 的两个数值参数都可能是常量名而非字面量
# （走马灯就是 `memoryConfig(SpellRarity.RARE, BASE_COOLDOWN_SECONDS, MAX_LEVEL)`）。
CONST_RE = re.compile(
    r"private\s+static\s+final\s+(?:double|float|int|long)\s+(?P<name>\w+)\s*="
    r"\s*(?P<value>[\d.]+)[DdFfLl]?\s*;"
)
MANA_BASE_RE = re.compile(r"this\.baseManaCost\s*=\s*(?P<v>\d+)\s*;")
MANA_PER_RE = re.compile(r"this\.manaCostPerLevel\s*=\s*(?P<v>\d+)\s*;")

# 吟唱时长：直接赋值，或长吟基类的抽象方法 defaultCastTime()（帧缚就是后者）
CAST_TIME_RE = re.compile(r"this\.castTime\s*=\s*(?P<v>\d+)\s*;")
DEFAULT_CAST_RE = re.compile(
    r"protected\s+int\s+defaultCastTime\s*\(\s*\)\s*\{\s*return\s+(?P<v>\d+)\s*;",
    re.DOTALL,
)
CAST_TYPE_RE = re.compile(r"return\s+CastType\.(?P<t>\w+)\s*;")
# 没覆写 getCastType() 的，长吟由基类决定：MnemosyneLongCastSpell（帧缚）与 EncodeSpell（写入三件套）
LONG_PARENTS = {"MnemosyneLongCastSpell", "EncodeSpell"}
EXTENDS_RE = re.compile(r"public\s+(?:final\s+)?class\s+\w+\s+extends\s+(?P<p>\w+)")

# 按等级冷却的改写入口（见 RecollectionSpell.onCooldownAdded）
PER_LEVEL_CD_HOOK = "onCooldownAdded"


class Report:
    def __init__(self, verbose: bool = False) -> None:
        self.checked = 0
        self.fails: list[str] = []
        self.warns: list[str] = []
        self.verbose = verbose

    def ok(self) -> None:
        self.checked += 1

    def fail(self, msg: str) -> None:
        self.checked += 1
        self.fails.append(msg)

    def warn(self, msg: str) -> None:
        self.warns.append(msg)


# ---------------------------------------------------------------- 总表

def parse_table(path: Path, rep: Report) -> dict[str, dict]:
    """解析 §一 总表，返回 {id: 行字段}。抓不到就 FAIL（自报盲区）。"""
    if not path.is_file():
        rep.fail(f"总表不存在：{path}")
        return {}

    lines = path.read_text(encoding="utf-8").splitlines()

    start = None
    for i, line in enumerate(lines):
        if line.startswith("## 一、总表"):
            start = i
            break
    if start is None:
        rep.fail("总表：找不到 `## 一、总表` 章节，无法定位数据")
        return {}

    rows: dict[str, dict] = {}
    for line in lines[start + 1:]:
        if line.startswith("## "):
            break
        m = ROW_RE.match(line)
        if not m:
            continue

        cd_raw = m.group("cooldown")
        cd_range = re.match(r"^([\d.]+)~([\d.]+)s$", cd_raw)
        cd_single = re.match(r"^([\d.]+)s$", cd_raw)
        if cd_range:
            cd_first, cd_last = float(cd_range.group(1)), float(cd_range.group(2))
        elif cd_single:
            cd_first = cd_last = float(cd_single.group(1))
        else:
            rep.fail(f"总表 {m.group('id')}：冷却列 `{cd_raw}` 解析不了（应为 `90s` 或 `110~70s`）")
            continue

        cast_raw = m.group("cast")
        if cast_raw == "瞬发":
            cast_ticks = 0
        else:
            cm = re.match(r"^(\d+)t$", cast_raw)
            if not cm:
                rep.fail(f"总表 {m.group('id')}：吟唱列 `{cast_raw}` 解析不了（应为 `瞬发` 或 `50t`）")
                continue
            cast_ticks = int(cm.group(1))

        mana_expr = m.group("mana_expr")
        mm = MANA_EXPR_RE.match(mana_expr)
        if mm:
            mana_base, mana_per = int(mm.group("base")), int(mm.group("per"))
        else:
            fm = MANA_FIXED_RE.match(mana_expr)
            if not fm:
                rep.fail(f"总表 {m.group('id')}：法力列 `{mana_expr}` 解析不了")
                continue
            mana_base, mana_per = int(fm.group("base")), 0

        if m.group("id") in rows:
            rep.fail(f"总表 {m.group('id')}：重复出现两次")

        rows[m.group("id")] = {
            "name": m.group("name"),
            "rarity": m.group("rarity").upper(),
            "max": int(m.group("max")),
            "mana_base": mana_base,
            "mana_per": mana_per,
            "mana_max": int(m.group("mana_max")),
            "cd_raw": cd_raw,
            "cd_first": cd_first,
            "cd_last": cd_last,
            "cd_is_range": bool(cd_range),
            "cast_ticks": cast_ticks,
            "rate": float(m.group("rate")),
        }

    if len(rows) != EXPECTED_SPELL_COUNT:
        rep.fail(f"总表行数 {len(rows)} ≠ 应有 {EXPECTED_SPELL_COUNT}（漏行或正则失配）")
    return rows


# ---------------------------------------------------------------- Java 侧

def parse_spells(directory: Path, table_ids: set[str], rep: Report) -> dict[str, dict]:
    """解析法术类，返回 {id: 代码里的实际数值}。"""
    if not directory.is_dir():
        rep.fail(f"法术目录不存在：{directory}")
        return {}

    files = sorted(
        p for p in directory.rglob("*Spell.java")
        if "/base/" not in p.as_posix() and "\\base\\" not in str(p)
    )
    if not files:
        rep.fail("法术目录里一个 `*Spell.java` 都没抓到（正则失配）")
        return {}

    spells: dict[str, dict] = {}
    for f in files:
        src = f.read_text(encoding="utf-8")

        # id：文件里可能出现多个 fromNamespaceAndPath（子效果 id、`path` 变量等），
        # 与总表 id 求交集 —— 命中且只命中一个才算解析成功。
        found = {m.group("id") for m in ID_RE.finditer(src)} & table_ids
        if len(found) != 1:
            rep.fail(f"{f.name}：无法确定法术 id（与总表交集 = {sorted(found) or '空'}）")
            continue
        sid = found.pop()
        if sid in spells:
            rep.fail(f"法术 id `{sid}` 被两个类声明：{spells[sid]['file']} 与 {f.name}")
            continue

        cm = CONFIG_RE.search(src)
        if not cm:
            rep.fail(f"{f.name}：抓不到 `super(memoryConfig(...))`")
            continue

        consts = {m.group("name"): float(m.group("value")) for m in CONST_RE.finditer(src)}

        def resolve(token: str, what: str) -> float | None:
            token = token.strip()
            num = re.match(r"^([\d.]+)[DdFfLl]?$", token)
            if num:
                return float(num.group(1))
            if token in consts:
                return consts[token]
            rep.fail(f"{f.name}：memoryConfig 的{what}参数 `{token}` 既不是数字也查不到常量定义")
            return None

        cd_value = resolve(cm.group("cd"), "冷却")
        max_value = resolve(cm.group("max"), "最大等级")
        if cd_value is None or max_value is None:
            continue

        mb = MANA_BASE_RE.search(src)
        mp = MANA_PER_RE.search(src)
        if not mb or not mp:
            rep.fail(f"{f.name}：抓不到 baseManaCost / manaCostPerLevel")
            continue

        # 吟唱时长：优先 `this.castTime = N`，退而求其次 `defaultCastTime() { return N; }`，
        # 都没有就是 0（= 瞬发）。
        ct = CAST_TIME_RE.search(src)
        if ct:
            cast_ticks = int(ct.group("v"))
        else:
            dc = DEFAULT_CAST_RE.search(src)
            cast_ticks = int(dc.group("v")) if dc else 0

        # 施法类型：显式 return CastType.X 优先；否则看父类是不是长吟基类
        ctm = CAST_TYPE_RE.search(src)
        if ctm:
            is_long = ctm.group("t").upper() == "LONG"
        else:
            pm = EXTENDS_RE.search(src)
            is_long = bool(pm) and pm.group("p") in LONG_PARENTS

        spells[sid] = {
            "file": f.name,
            "rarity": cm.group("rarity").upper(),
            "max": int(max_value),
            "cd": cd_value,
            "mana_base": int(mb.group("v")),
            "mana_per": int(mp.group("v")),
            "cast_ticks": cast_ticks,
            "is_long": is_long,
            "has_cd_hook": PER_LEVEL_CD_HOOK in src,
        }
    return spells


# ---------------------------------------------------------------- 比对

def compare(rep: Report, table: dict[str, dict], spells: dict[str, dict]) -> None:
    for sid in sorted(set(table) | set(spells)):
        t, c = table.get(sid), spells.get(sid)

        if t is None:
            rep.fail(f"总表缺法术 `{sid}`（代码 {c['file']} 有）")
            continue
        if c is None:
            rep.fail(f"代码缺法术 `{sid}`（总表「{t['name']}」有）")
            continue

        label = f"「{t['name']}」{sid}"

        if t["rarity"] != c["rarity"]:
            rep.fail(f"{label}：起始稀有度 总表 {t['rarity']} ≠ 代码 {c['rarity']}")
        else:
            rep.ok()

        if t["max"] != c["max"]:
            rep.fail(f"{label}：最大等级 总表 {t['max']} ≠ 代码 {c['max']}")
        else:
            rep.ok()

        if t["mana_base"] != c["mana_base"]:
            rep.fail(f"{label}：法力基数 总表 {t['mana_base']} ≠ 代码 {c['mana_base']}")
        else:
            rep.ok()

        if t["mana_per"] != c["mana_per"]:
            rep.fail(f"{label}：法力每级增量 总表 {t['mana_per']} ≠ 代码 {c['mana_per']}")
        else:
            rep.ok()

        # 吟唱：总表 ticks ↔ 代码 castTime
        if t["cast_ticks"] != c["cast_ticks"]:
            rep.fail(
                f"{label}：吟唱 总表 {t['cast_ticks']}t ≠ 代码 {c['cast_ticks']}t"
                f"（{c['file']}）"
            )
        else:
            rep.ok()

        # 代码自洽：CastType == LONG ⟺ castTime > 0
        # ISS 在 INSTANT 下会强制把 castTime 归零，写了也白写（见 CurseOfOblivionSpell 的注释）。
        if c["is_long"] != (c["cast_ticks"] > 0):
            rep.fail(
                f"{label}：代码自洽性问题 —— CastType="
                f"{'LONG' if c['is_long'] else 'INSTANT'} 但 castTime={c['cast_ticks']}；"
                f"两者必须同真同假，否则 castTime 会被 ISS 静默归零（{c['file']}）"
            )
        else:
            rep.ok()

        # 冷却：单值直接比；区间则是"按等级冷却"，配置值只作基准
        if t["cd_is_range"]:
            if not c["has_cd_hook"]:
                rep.fail(
                    f"{label}：总表冷却是区间 `{t['cd_raw']}`，但代码里没有 "
                    f"`{PER_LEVEL_CD_HOOK}` 改写入口 —— 会退化成固定冷却"
                )
            else:
                rep.ok()
        else:
            if abs(t["cd_first"] - c["cd"]) > 1e-6:
                rep.fail(f"{label}：冷却 总表 {t['cd_first']}s ≠ 代码 {c['cd']}s")
            else:
                rep.ok()

        # 总表内部：满级法力
        expect_max_mana = t["mana_base"] + t["mana_per"] * (t["max"] - 1)
        if t["mana_max"] != expect_max_mana:
            rep.fail(
                f"{label}：总表满级法力 {t['mana_max']} ≠ "
                f"{t['mana_base']} + {t['mana_per']}×({t['max']}−1) = {expect_max_mana}"
            )
        else:
            rep.ok()

        # 总表内部：消耗率 = 满级法力 ÷ (满级冷却 + 吟唱)
        # 冷却为区间时取**满级**那一端（走马灯 110~70s → 满级 70s）
        denom = t["cd_last"] + t["cast_ticks"] / 20.0
        expect_rate = round(t["mana_max"] / denom, 2) if denom else 0.0
        if abs(expect_rate - t["rate"]) > 1e-6:
            rep.fail(
                f"{label}：总表消耗率 {t['rate']}/秒 ≠ "
                f"{t['mana_max']} ÷ ({t['cd_last']} + {t['cast_ticks']}t) = {expect_rate}/秒"
            )
        else:
            rep.ok()


def main() -> int:
    ap = argparse.ArgumentParser(description="忆海法术数值：docs/tech/13 总表 ↔ Java 实现一致性校验")
    ap.add_argument("--table", default=str(TABLE_PATH),
                    help="总表路径（负向测试时指向故意改坏的副本）")
    ap.add_argument("--spell-dir", default=str(SPELL_DIR),
                    help="法术源码目录（负向测试时指向副本）")
    args = ap.parse_args()

    rep = Report()
    table = parse_table(Path(args.table), rep)
    spells = parse_spells(Path(args.spell_dir), set(table), rep)
    if table and spells:
        compare(rep, table, spells)

    print("=" * 72)
    print(f"检查项 {rep.checked} 个 · FAIL {len(rep.fails)} 条 · warn {len(rep.warns)} 条")

    if rep.fails:
        print("\nFAIL 明细：")
        for f_ in rep.fails:
            print("  ✗ " + f_)
        return 1
    print("\n全部通过 ✅")
    if rep.warns:
        print("\nwarn 明细（不阻塞，但值得看一眼）：")
        for w in rep.warns:
            print("  " + w)
    return 0


if __name__ == "__main__":
    sys.exit(main())
