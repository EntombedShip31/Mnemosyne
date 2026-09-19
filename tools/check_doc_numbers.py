# -*- coding: utf-8 -*-
"""忆海设计文档数值：docs/tech/04 §三 总表 + docs/03 法术总表  ↔  Java 实现一致性校验。

为什么需要它
------------
`check_spell_numbers.py` 只把 `13_数值总表` 与 Java 对账。而 `tech/04` 与 `docs/03`
**各自又抄了一份数值**，这两份没有任何脚本看管 —— 09-19 实测发现
`docs/03` §二·补 五个新法术的法力与稀有度**全是提案稿数值**（无尽忆域的稀有度
甚至从 Epic 写成了 Legendary），而当时全部检查都是全绿的。

权威顺序：**Java 代码 > 13_数值总表 > tech/04 = docs/03（这两篇是副本）**。
本脚本校验的就是最后这两份副本。

用法
----
    python tools/check_doc_numbers.py                # 正常跑
    python tools/check_doc_numbers.py --verbose      # 列出每一条通过项

负向测试（证明不是恒真检查，改脚本后必做）
------------------------------------------
    sed 's/| 15 | 2 |/| 99 | 2 |/' docs/tech/04_法术等级强度表.md > build/_neg.md
    python tools/check_doc_numbers.py --tech04 build/_neg.md
    # 期望：忆矢 baseMana 报 FAIL

约定
----
- 法力列必须写成 `base + N/级`、`base（+N/级）` 或 `N（固定）`。
  只写一个裸数字（如 `15`）会被判 FAIL —— 因为无法判断它是基数还是满级值，
  而这种歧义正是当初数值漂移的温床。
- 冷却写成区间（`110~70s`）表示"按等级冷却"，只校验代码里有改写入口，不比数值。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TECH04 = ROOT / "docs" / "tech" / "04_法术等级强度表.md"
DOCS03 = ROOT / "docs" / "03_法术_总表与设计准则.md"
SPELL_DIR = ROOT / "src" / "main" / "java" / "com" / "etbs31" / "mnemosyne" / "spell"
LANG_PATH = ROOT / "src" / "main" / "resources" / "assets" / "mnemosyne" / "lang" / "zh_cn.json"

EXPECTED_SPELL_COUNT = 21

# ---------------------------------------------------------------- Java 侧

CAST_TIME_RE = re.compile(r"this\.castTime\s*=\s*(?P<v>\d+)\s*;")
DEFAULT_CAST_RE = re.compile(
    r"protected\s+int\s+defaultCastTime\s*\(\s*\)\s*\{\s*return\s+(?P<v>\d+)\s*;", re.DOTALL
)
CAST_TYPE_RE = re.compile(r"return\s+CastType\.(?P<t>\w+)\s*;")
LONG_PARENTS = {"MnemosyneLongCastSpell", "EncodeSpell"}
EXTENDS_RE = re.compile(r"public\s+(?:final\s+)?class\s+\w+\s+extends\s+(?P<p>\w+)")
CONFIG_RE = re.compile(
    r"super\(\s*memoryConfig\(\s*SpellRarity\.(?P<rarity>\w+)\s*,\s*"
    r"(?P<cd>[^,()]+?)\s*,\s*(?P<max>[^,()]+?)\s*\)\s*\)",
    re.DOTALL,
)
CONST_RE = re.compile(
    r"private\s+static\s+final\s+(?:double|float|int|long)\s+(?P<name>\w+)\s*=\s*"
    r"(?P<value>[\d.]+)[DdFfLl]?\s*;"
)
MANA_BASE_RE = re.compile(r"this\.baseManaCost\s*=\s*(?P<v>\d+)\s*;")
MANA_PER_RE = re.compile(r"this\.manaCostPerLevel\s*=\s*(?P<v>\d+)\s*;")
PER_LEVEL_CD_HOOK = "onCooldownAdded"


def camel_to_snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def parse_java(directory: Path, rep: "Report") -> dict[str, dict]:
    """返回 {id: 代码实测数值}。id 由类名推导（MemoryArrowSpell -> memory_arrow）。"""
    if not directory.is_dir():
        rep.fail(f"法术目录不存在：{directory}")
        return {}

    files = sorted(
        p for p in directory.rglob("*Spell.java")
        if "/base/" not in p.as_posix() and "\\base\\" not in str(p)
        and p.name != "BaseContractSelfTest.java"
    )
    if not files:
        rep.fail("法术目录里一个 `*Spell.java` 都没抓到（正则失配）")
        return {}

    spells: dict[str, dict] = {}
    for f in files:
        src = f.read_text(encoding="utf-8")
        sid = camel_to_snake(f.stem.removesuffix("Spell"))

        cm = CONFIG_RE.search(src)
        if not cm:
            rep.fail(f"{f.name}：抓不到 `super(memoryConfig(...))`")
            continue
        consts = {m.group("name"): float(m.group("value")) for m in CONST_RE.finditer(src)}

        def resolve(token: str) -> float:
            token = token.strip()
            m = re.match(r"^([\d.]+)[DdFfLl]?$", token)
            if m:
                return float(m.group(1))
            return consts.get(token, float("nan"))

        mb, mp = MANA_BASE_RE.search(src), MANA_PER_RE.search(src)
        if not mb or not mp:
            rep.fail(f"{f.name}：抓不到 baseManaCost / manaCostPerLevel")
            continue

        ct = CAST_TIME_RE.search(src)
        if ct:
            cast_ticks = int(ct.group("v"))
        else:
            dc = DEFAULT_CAST_RE.search(src)
            cast_ticks = int(dc.group("v")) if dc else 0

        ctm = CAST_TYPE_RE.search(src)
        if ctm:
            cast_type = ctm.group("t").upper()
        else:
            pm = EXTENDS_RE.search(src)
            cast_type = "LONG" if (pm and pm.group("p") in LONG_PARENTS) else "INSTANT"

        spells[sid] = {
            "file": f.name,
            "rarity": cm.group("rarity").upper(),
            "cd": resolve(cm.group("cd")),
            "mana_base": int(mb.group("v")),
            "mana_per": int(mp.group("v")),
            "cast_ticks": cast_ticks,
            "cast_type": cast_type,
            "has_cd_hook": PER_LEVEL_CD_HOOK in src,
        }
    return spells


# ---------------------------------------------------------------- 文档侧

def cells_of(line: str) -> list[str]:
    line = line.strip()
    if not line.startswith("|"):
        return []
    return [c.strip() for c in line.strip("|").split("|")]


def clean(cell: str) -> str:
    return cell.replace("**", "").replace("~~", "").replace("`", "").strip()


MANA_EXPLICIT_RE = re.compile(r"^(?P<base>\d+)\s*（\+\s*(?P<per>\d+)/级")
MANA_PLUS_RE = re.compile(r"^(?P<base>\d+)\s*\+\s*(?P<per>\d+)/级")
MANA_FIXED_RE = re.compile(r"^(?P<base>\d+)（固定）")
CD_RANGE_RE = re.compile(r"^(?P<first>\d+(?:\.\d+)?)~(?P<last>\d+(?:\.\d+)?)s?$")
# tech/04 的冷却列写的是裸数字（`8.0`），docs/03 带单位（`12s`）—— 两种都收
CD_ONE_RE = re.compile(r"^(?P<v>\d+(?:\.\d+)?)s?$")


def parse_mana(raw: str) -> tuple[int, int] | None:
    """返回 (base, per)。裸数字一律返回 None（语义不明，判 FAIL）。"""
    s = clean(raw)
    for rx in (MANA_EXPLICIT_RE, MANA_PLUS_RE):
        m = rx.match(s)
        if m:
            return int(m.group("base")), int(m.group("per"))
    m = MANA_FIXED_RE.match(s)
    if m:
        return int(m.group("base")), 0
    return None


def parse_cd(raw: str) -> tuple[str, float, float]:
    """返回 ("range", first, last) 或 ("one", v, v) 或 ("bad", 0, 0)。"""
    s = clean(raw)
    m = CD_RANGE_RE.match(s)
    if m:
        return "range", float(m.group("first")), float(m.group("last"))
    m = CD_ONE_RE.match(s)
    if m:
        return "one", float(m.group("v")), float(m.group("v"))
    return "bad", 0.0, 0.0


def parse_tech04(path: Path, rep: "Report") -> dict[str, dict]:
    """解析 `tech/04` §三 总表：| # | spellId | 中文名 | 英文名 | minRarity | CastType | castTime | 冷却(s) | baseMana | mana/Lv |"""
    if not path.is_file():
        rep.fail(f"文档不存在：{path}")
        return {}

    rows: dict[str, dict] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        cs = cells_of(line)
        if len(cs) != 10:
            continue
        if "已删除" in line:
            continue
        m = re.search(r"`([a-z_]+)`", cs[1])
        if not m:
            continue
        sid = m.group(1)
        rows[sid] = {
            "name": clean(cs[2]),
            "rarity": clean(cs[4]).upper(),
            "cast_type": clean(cs[5]).upper(),
            "cast_time_raw": clean(cs[6]),
            "cd_raw": cs[7],
            "mana_raw": cs[8],
            "per_raw": cs[9],
        }
    return rows


def parse_docs03(path: Path, lang: dict[str, str], rep: "Report") -> dict[str, dict]:
    """解析 `docs/03` 主表（9 列）与 §二·补（10 列）。

    主表没有 id 列，靠中文名 → id（经 lang 反查）。
    """
    if not path.is_file():
        rep.fail(f"文档不存在：{path}")
        return {}

    name_to_id = {}
    for k, v in lang.items():
        if k.startswith("spell.mnemosyne.") and "." not in k[len("spell.mnemosyne."):]:
            name_to_id[v] = k[len("spell.mnemosyne."):]

    rows: dict[str, dict] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "已删除" in line:
            continue
        cs = cells_of(line)
        # cells_of 会把首尾的 `|` 剥掉，所以列数 = 表头列数（主表 9 列 / §二·补 10 列）
        if len(cs) == 9:            # 主表：# 法术名 英文名 稀有度 类型 法力 冷却 核心作用 标签
            name = clean(cs[1])
            sid = name_to_id.get(name)
            if sid is None:
                continue
            off = 0
        elif len(cs) == 10:         # §二·补：多一列 id
            name = clean(cs[1])
            sid = clean(cs[3])
            if sid not in name_to_id.values():
                continue
            off = 1
        else:
            continue

        rows[sid] = {
            "name": name,
            "rarity": clean(cs[3 + off]).upper(),
            "type_raw": clean(cs[4 + off]),
            "mana_raw": cs[5 + off],
            "cd_raw": cs[6 + off],
        }
    return rows


# ---------------------------------------------------------------- 报告

class Report:
    def __init__(self, verbose: bool = False) -> None:
        self.checked = 0
        self.fails: list[str] = []
        self.verbose = verbose

    def ok(self, what: str = "") -> None:
        self.checked += 1
        if self.verbose and what:
            print(f"  ✓ {what}")

    def fail(self, msg: str) -> None:
        self.checked += 1
        self.fails.append(msg)

    def head(self, title: str) -> None:
        print(f"\n== {title}")

    def summary(self) -> None:
        print("\n" + "=" * 72)
        print(f"检查项 {self.checked} 个 · FAIL {len(self.fails)} 条")
        if self.fails:
            print("\nFAIL 明细：")
            for f in self.fails:
                print(f"  ✗ {f}")
        else:
            print("\n全部通过 ✅")


# ---------------------------------------------------------------- 比对

def check_tech04(rep: Report, rows: dict[str, dict], java: dict[str, dict]) -> None:
    rep.head("检查 1 · docs/tech/04 §三 总表 ↔ Java")
    for sid in sorted(java):
        c = java[sid]
        r = rows.get(sid)
        label = f"「{r['name'] if r else sid}」{sid}"
        if r is None:
            rep.fail(f"{label}：tech/04 §三 总表**缺这一行**（代码 {c['file']} 有）")
            continue

        if r["rarity"] != c["rarity"]:
            rep.fail(f"{label}：起始稀有度 文档 {r['rarity']} ≠ 代码 {c['rarity']}")
        else:
            rep.ok(f"{label} 稀有度")

        if r["cast_type"] != c["cast_type"]:
            rep.fail(f"{label}：CastType 文档 {r['cast_type']} ≠ 代码 {c['cast_type']}")
        else:
            rep.ok(f"{label} CastType")

        try:
            doc_ticks = int(r["cast_time_raw"])
        except ValueError:
            rep.fail(f"{label}：castTime 文档 `{r['cast_time_raw']}` 不是整数")
            doc_ticks = -1
        if doc_ticks != c["cast_ticks"]:
            rep.fail(f"{label}：castTime 文档 {doc_ticks}t ≠ 代码 {c['cast_ticks']}t")
        else:
            rep.ok(f"{label} castTime")

        kind, first, _last = parse_cd(r["cd_raw"])
        if kind == "bad":
            rep.fail(f"{label}：冷却 文档 `{r['cd_raw']}` 解析不出秒数")
        elif kind == "range":
            if not c["has_cd_hook"]:
                rep.fail(f"{label}：冷却是区间 `{first}s` 但代码没有 `{PER_LEVEL_CD_HOOK}` 入口")
            else:
                rep.ok(f"{label} 冷却区间")
        else:
            if abs(first - c["cd"]) > 1e-6:
                rep.fail(f"{label}：冷却 文档 {first}s ≠ 代码 {c['cd']}s")
            else:
                rep.ok(f"{label} 冷却")

        try:
            doc_base = int(r["mana_raw"])
            doc_per = int(r["per_raw"])
        except ValueError:
            rep.fail(f"{label}：法力 文档 `{r['mana_raw']}` / `{r['per_raw']}` 不是整数")
        else:
            if doc_base != c["mana_base"]:
                rep.fail(f"{label}：baseMana 文档 {doc_base} ≠ 代码 {c['mana_base']}")
            else:
                rep.ok(f"{label} baseMana")
            if doc_per != c["mana_per"]:
                rep.fail(f"{label}：mana/Lv 文档 {doc_per} ≠ 代码 {c['mana_per']}")
            else:
                rep.ok(f"{label} mana/Lv")

    extra = sorted(set(rows) - set(java))
    for sid in extra:
        rep.fail(f"tech/04 §三 总表有 `{sid}`，但代码里没有这个法术（多半是已删除的残留）")


def check_docs03(rep: Report, rows: dict[str, dict], java: dict[str, dict]) -> None:
    rep.head("检查 2 · docs/03 法术总表（主表 + §二·补）↔ Java")
    for sid in sorted(java):
        c = java[sid]
        r = rows.get(sid)
        label = f"「{r['name'] if r else sid}」{sid}"
        if r is None:
            rep.fail(f"{label}：docs/03 总表**缺这一行**（代码 {c['file']} 有）")
            continue

        if r["rarity"] != c["rarity"]:
            rep.fail(f"{label}：起始稀有度 文档 {r['rarity']} ≠ 代码 {c['rarity']}")
        else:
            rep.ok(f"{label} 稀有度")

        doc_long = r["type_raw"].startswith("长")
        code_long = c["cast_type"] == "LONG"
        if doc_long != code_long:
            rep.fail(
                f"{label}：施法类型 文档 {'长' if doc_long else '瞬'} ≠ "
                f"代码 {c['cast_type']}（castTime={c['cast_ticks']}t）"
            )
        else:
            rep.ok(f"{label} 施法类型")

        mana = parse_mana(r["mana_raw"])
        if mana is None:
            rep.fail(
                f"{label}：法力列 `{clean(r['mana_raw'])}` 语义不明 —— "
                f"必须写成 `base + N/级` / `base（+N/级）` / `N（固定）`"
            )
        else:
            base, per = mana
            if base != c["mana_base"]:
                rep.fail(f"{label}：法力基数 文档 {base} ≠ 代码 {c['mana_base']}")
            else:
                rep.ok(f"{label} 法力基数")
            if per != c["mana_per"]:
                rep.fail(f"{label}：法力每级 文档 {per} ≠ 代码 {c['mana_per']}")
            else:
                rep.ok(f"{label} 法力每级")

        kind, first, _last = parse_cd(r["cd_raw"])
        if kind == "bad":
            rep.fail(f"{label}：冷却 文档 `{r['cd_raw']}` 解析不出秒数")
        elif kind == "range":
            if not c["has_cd_hook"]:
                rep.fail(f"{label}：冷却是区间 `{first}s` 但代码没有 `{PER_LEVEL_CD_HOOK}` 入口")
            else:
                rep.ok(f"{label} 冷却区间")
        else:
            if abs(first - c["cd"]) > 1e-6:
                rep.fail(f"{label}：冷却 文档 {first}s ≠ 代码 {c['cd']}s")
            else:
                rep.ok(f"{label} 冷却")

    extra = sorted(set(rows) - set(java))
    for sid in extra:
        rep.fail(f"docs/03 总表有 `{sid}`，但代码里没有这个法术（多半是已删除的残留）")


# ---------------------------------------------------------------- main

def main() -> int:
    ap = argparse.ArgumentParser(description="忆海设计文档数值：tech/04 §三 总表 + docs/03 总表 ↔ Java")
    ap.add_argument("--tech04", default=str(TECH04))
    ap.add_argument("--docs03", default=str(DOCS03))
    ap.add_argument("--spell-dir", default=str(SPELL_DIR))
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    rep = Report(verbose=args.verbose)

    java = parse_java(Path(args.spell_dir), rep)
    if not java:
        rep.summary()
        return 1
    if len(java) != EXPECTED_SPELL_COUNT:
        rep.fail(f"法术总数 {len(java)} ≠ 预期 {EXPECTED_SPELL_COUNT}")

    lang = json.loads(LANG_PATH.read_text(encoding="utf-8"))

    tech04_rows = parse_tech04(Path(args.tech04), rep)
    docs03_rows = parse_docs03(Path(args.docs03), lang, rep)

    check_tech04(rep, tech04_rows, java)
    check_docs03(rep, docs03_rows, java)

    rep.summary()
    return 1 if rep.fails else 0


if __name__ == "__main__":
    sys.exit(main())
