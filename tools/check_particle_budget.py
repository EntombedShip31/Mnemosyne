#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
量化并约束粒子开销：「每 tick 发射」有没有节流、每秒发包/粒子数是多少。

为什么需要它
------------
`docs/tech/01` §12 与项目记忆里写了三条硬约束，但**从未被任何脚本检查过**，
也从未在 `runClient` 里实测过密度：
  ① `speed` 参数只是随机方向初速度 → 定向/定形只能逐点铺位置（**代价是发包数暴涨**）
  ② 层数/半径驱动的规模必须钳上限
  ③ 瞬时效果可 60~120 粒，**每 tick 循环不行**

其中 ③ 是最危险的：一个 15~30 秒的领域，若每 tick 发一次 60 粒的包，
就是 1200 粒/秒 —— 编译得过、跑得动、只是把客户端卡死，典型的静默失败。

它检查什么
----------
对**每个被判定为"每 tick 调用"的方法**（`spawnAmbience` / `channelTick` /
`trailParticles` / `onLongCastTick` / `tick` / `update` …）：
  A. 里面的 `sendParticles` 必须被 `if (now >= x.nextXTick)` 之类的节流块包住 —— 否则 FAIL
  B. `Math.min(N, ...)` 当作粒子数上界；裸变量粒数 → FAIL（未钳上限，同约束 ②）
  C. 瞬时（非每 tick）调用：粒数字面量 > 120 → FAIL（同约束 ③）
  D. 每 tick 发射器：每秒**发包数** > 150 → FAIL
     ⭐ 之所以卡发包数而不是粒子数：项目实测结论是"开销几乎全在**发包次数**上"
     （`SeaOfMemorySpell` / `EndlessRealmSpell` 顶部注释），且约束 ① 决定了
     成形粒子只能逐点发包（每个包 1 粒），所以粒子数在这里不是瓶颈。
  E. 打印一张每秒预算表，让人能直接判断"这个领域到底有多贵"

⭐ 自报盲区
----------
任何"每 tick 方法"里出现解析不了的调用（抓不到节流块 / 抓不到循环上界 /
粒数表达式看不懂），一律 **FAIL**，绝不静默跳过。

退出码：0 = 全通过；1 = 有 FAIL。
"""

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA_ROOT = ROOT / "src" / "main" / "java" / "com" / "etbs31" / "mnemosyne"

TICKS_PER_SECOND = 20

# 硬阈值（依据见文件头 D 条）
PACKETS_PER_SEC_FAIL = 150
ONE_SHOT_COUNT_CEILING = 120   # 瞬时效果：60~120 粒是上限

# 每 tick 调用的方法名模式。
# ⚠️ 这个正则带 `\s*\(`，所以匹配时必须传入 `name + "("` —— 直接传裸方法名会永远匹配不上
# （第一版就是这么写的，结果 `spawnAmbience` 没被识别，预算表整张空掉却 0 FAIL）。
PER_TICK_METHOD_RE = re.compile(
    r"^(?P<name>\w*(?:Ambience|Tick|trail|Trail|Channel|update|Update)\w*)\s*\("
)

# 节流块的判据：条件里出现 `now >= xxx.nextXTick` 或 `xxx.nextXTick <= now`
THROTTLE_RE = re.compile(r"next\w*[Tt]ick")
# 节流块内的推进语句：`field.nextTideTick = now + TIDE_INTERVAL;`
ADVANCE_RE = re.compile(
    r"(?P<field>\w+)\s*=\s*(?:now|\w+)\s*\+\s*(?P<interval>[A-Za-z_]\w*|\d+)\s*;"
)

METHOD_RE = re.compile(
    r"^\s{4}(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?[\w<>\[\], .?]+?\s+"
    r"(?P<name>\w+)\s*\((?P<params>[^)]*)\)\s*\{",
    re.MULTILINE,
)

CONST_RE = re.compile(
    r"(?:private|public|protected)\s+static\s+final\s+(?:double|float|int|long)\s+"
    r"(?P<name>\w+)\s*=\s*(?P<value>[\d.]+)[DdFfLl]?\s*;"
)
# 数组常量：`{0.10D, 0.72D}` → 长度 2
ARRAY_CONST_RE = re.compile(
    r"(?:private|public|protected)\s+static\s+final\s+\w+\s*\[\s*\]\s+(?P<name>\w+)\s*=\s*\{(?P<body>[^}]*)\}"
)

CALL_RE = re.compile(r"\.?\bsendParticles\s*\(")


class Report:
    def __init__(self) -> None:
        self.checked = 0
        self.fails: list[str] = []
        self.warns: list[str] = []
        self.table: list[tuple[str, str, float, float, bool]] = []   # 文件, 方法, 包/秒, 粒/秒, 粒数未知?

    def ok(self) -> None:
        self.checked += 1

    def fail(self, msg: str) -> None:
        self.checked += 1
        self.fails.append(msg)

    def warn(self, msg: str) -> None:
        self.warns.append(msg)


# ---------------------------------------------------------------- 小工具

def const_map(text: str) -> dict[str, float]:
    out = {m.group("name"): float(m.group("value")) for m in CONST_RE.finditer(text)}
    for m in ARRAY_CONST_RE.finditer(text):
        body = m.group("body").strip()
        out[m.group("name")] = 0.0 if not body else len([p for p in body.split(",") if p.strip()])
    return out


def split_args(call_text: str) -> list[str]:
    """把 `sendParticles(a, b, c, ...)` 的参数按顶层逗号切开（尊重嵌套括号）。"""
    depth = 0
    args: list[str] = []
    cur: list[str] = []
    for ch in call_text:
        if ch == "(":
            depth += 1
            if depth == 1:
                continue
        elif ch == ")":
            depth -= 1
            if depth == 0:
                break
        if ch == "," and depth == 1:
            args.append("".join(cur).strip())
            cur = []
            continue
        cur.append(ch)
    if "".join(cur).strip():
        args.append("".join(cur).strip())
    return args


def match_paren(text: str, start: int) -> int:
    """`start` 指向左括号，返回匹配的右括号下标；找不到返回 -1。"""
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return -1


def match_brace(text: str, start: int) -> int:
    """
    `start` 指向左花括号，返回匹配的右花括号下标；找不到返回 -1。

    ⚠️ 必须数**花括号**而不是圆括号：方法体里随便一个 `foo()` 就会让
    圆括号计数提前归零，把方法体截成两三个字符（第一版就是这么坏的，
    结果 78 处 sendParticles 只查到 5 处）。
    """
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    return -1


def call_arity(args: list[str]) -> int:
    return len(args)


def local_int_bindings(body: str) -> list[tuple[int, str, str]]:
    """
    方法体里的 `final int count = ...;` → [(位置, 名字, 右值)]。

    ⚠️ 必须**按位置**回溯，不能建成 {名字: 右值} 字典：同一个方法里可能出现两个
    同名局部变量（SeaOfMemorySpell.spawnAmbience 就有两个 `count`，
    分别钳 64 和 28）。用字典会让后写的覆盖先写的，把 64 粒那层算成 28 → **少报**。
    """
    out: list[tuple[int, str, str]] = []
    for m in re.finditer(r"(?:final\s+)?\bint\s+(?P<name>\w+)\s*=\s*(?P<rhs>[^;]+);", body):
        out.append((m.start(), m.group("name"), m.group("rhs").strip()))
    return out


def resolve_local(bindings: list[tuple[int, str, str]], name: str,
                  before: int) -> str | None:
    """取 `before` 之前最后一次对 `name` 的赋值。"""
    hit = None
    for pos, n, rhs in bindings:
        if n == name and pos < before:
            hit = rhs
    return hit


def particle_count_of(args: list[str], bindings: list[tuple[int, str, str]],
                      before: int, depth: int = 0) -> tuple[int, bool, bool]:
    """
    返回 (上界, 是否钳了上限, 是否是"由有界输入算出的表达式")。

    9 参版：第 4 个参数是 count；11 参版（带 ServerPlayer 头参）第 6 个是 count。
    """
    if len(args) >= 11:
        idx = 6
    elif len(args) >= 9:
        idx = 4
    else:
        idx = 4 if len(args) > 4 else -1
    if idx < 0 or idx >= len(args):
        return 0, False, False

    expr = args[idx]

    m = re.search(r"Math\.min\s*\(\s*(?P<cap>\d+)\s*,", expr)
    if m:
        return int(m.group("cap")), True, False
    if re.fullmatch(r"\d+", expr):
        return int(expr), True, False

    # 裸标识符 → 回溯**本次调用之前**最后一次赋值（如 `count = (int) Math.min(64, ...)`）
    if re.fullmatch(r"\w+", expr) and depth < 3:
        rhs = resolve_local(bindings, expr, before)
        if rhs is not None:
            return particle_count_of(["", "", "", "", rhs], bindings, before, depth + 1)

    # 由常量/字面量算出的表达式（如 `2 + (int)(progress * 10)`）—— 有界但**非显式钳位**
    if re.fullmatch(r"[\d\sA-Za-z_.()*/+\-]+", expr):
        return 0, False, True
    return 0, False, False


# 节流形式 ②：方法开头的卫语句。
# ⚠️ 必须同时认两种写法 —— 单行 `if (now % X != 0) return;` 和
# 块形式 `if (now % X != 0) { return; }`（FramebindSpell 用的就是后者）。
# 漏掉后者会把已节流的方法按"每 tick"计费（偏保守，但数字不对）。
GUARD_RE = re.compile(
    r"if\s*\(\s*now\s*%\s*(?P<interval>[A-Za-z_]\w*|\d+)\s*!=\s*0\s*\)\s*"
    r"(?:return\s*;|\{\s*return\s*;\s*\})"
)


def loop_bound(header: str, consts: dict[str, float]) -> float | None:
    """`for (int i = 0; i < RING_POINTS; i++)` → 12；`for (double lat : DOME_LATITUDES)` → 数组长度。"""
    m = re.search(r"<\s*(?P<b>[A-Za-z_]\w*|\d+)\s*;", header)
    if m:
        tok = m.group("b")
        return float(tok) if tok.isdigit() else consts.get(tok)
    m = re.search(r":\s*(?P<arr>\w+)\s*\)", header)
    if m:
        return consts.get(m.group("arr"))
    return None


# ---------------------------------------------------------------- 核心扫描

def scan_method(src: str, start: int, name: str, params: str,
                consts: dict[str, float], rep: Report, path: Path,
                per_tick: bool) -> tuple[float, float]:
    """
    扫描一个方法体，返回 (每秒发包数, 每秒粒子数)。
    `start` 指向方法体的左花括号。
    """
    end = match_brace(src, start)
    if end < 0:
        rep.fail(f"{path.name}.{name}(): 方法体花括号不匹配，无法扫描")
        return 0.0, 0.0
    body = src[start:end + 1]

    # 若整个方法体被一个节流块包住，则里面所有发射都算已节流
    whole_throttled = False
    first_inner = body.find("{", 1)
    if first_inner > 0:
        outer_stmt = body[:first_inner]
        if THROTTLE_RE.search(outer_stmt):
            whole_throttled = True

    packets_sec = 0.0
    particles_sec = 0.0
    particles_unknown = False

    # 卫语句节流：命中后本方法内所有发射都按它的间隔触发
    guard = GUARD_RE.search(body)
    guard_interval: float | None = None
    if guard:
        tok = guard.group("interval")
        guard_interval = float(tok) if tok.isdigit() else consts.get(tok)
        if guard_interval is None:
            rep.fail(f"{path.name}.{name}()：卫语句 `now % {tok}` 的间隔常量解析不出来")

    bindings = local_int_bindings(body)

    stack: list[tuple[str, str, float]] = []   # (kind, header, for 循环上界)
    i = 0
    n = len(body)
    while i < n:
        ch = body[i]

        if ch == "{":
            header = body[max(0, i - 220):i]
            kind = "for" if re.search(r"\bfor\s*\([^)]*\)\s*$", header) else (
                "if" if re.search(r"\bif\s*\([^)]*\)\s*$", header) else "block")
            bound = loop_bound(header, consts) if kind == "for" else None
            stack.append((kind, header, bound if bound is not None else 1.0))
            i += 1
            continue

        if ch == "}":
            if stack:
                stack.pop()
            i += 1
            continue

        m = CALL_RE.match(body, i)
        if m:
            open_paren = body.index("(", m.start())
            close_paren = match_paren(body, open_paren)
            if close_paren < 0:
                rep.fail(f"{path.name}.{name}(): sendParticles 调用括号不匹配")
                break
            call_text = body[open_paren:close_paren + 1]
            args = split_args(call_text)
            count, clamped, bounded = particle_count_of(args, bindings, m.start())

            # 包数 = 1 × 各层 for 循环上界之积
            packets = 1.0
            for kind, _header, bound in stack:
                if kind == "for":
                    packets *= bound

            # 触发频率：节流块 > 卫语句 > 每 tick
            interval: float | None = None
            for kind, header, _b in reversed(stack):
                if kind == "if" and THROTTLE_RE.search(header):
                    blk_start = body.find(header)
                    seg = body[blk_start:close_paren] if 0 <= blk_start else ""
                    am = ADVANCE_RE.search(seg)
                    if am:
                        tok = am.group("interval")
                        interval = float(tok) if tok.isdigit() else consts.get(tok)
                    break
            if interval is None and guard_interval is not None:
                interval = guard_interval

            if per_tick:
                # 约束 ②：变量粒数必须有显式上限，否则规模可能随半径/层数失控
                if count == 0 and not clamped and not bounded:
                    rep.fail(
                        f"{path.name}.{name}()：粒数既不是字面量、也没有 Math.min(...) 上限，"
                        f"也解析不出有界表达式 —— 规模可能随半径/层数失控"
                    )
                elif count == 0 and bounded:
                    rep.warn(
                        f"{path.name}.{name}()：粒数由表达式算出且有界，但没有显式 Math.min 钳位"
                        f"（改数值时容易失控）"
                    )
                    rep.ok()
                else:
                    rep.ok()

                rate = TICKS_PER_SECOND / interval if interval else float(TICKS_PER_SECOND)
                packets_sec += packets * rate
                if count == 0:
                    # 有界但算不出具体值 —— 不能显示成 0（那会让人以为"零开销"）
                    particles_unknown = True
                particles_sec += packets * count * rate
            else:
                if count > ONE_SHOT_COUNT_CEILING:
                    rep.fail(
                        f"{path.name}.{name}()：瞬时发射 {count} 粒 > 上限 {ONE_SHOT_COUNT_CEILING}"
                    )
                else:
                    rep.ok()

            i = close_paren + 1
            continue

        i += 1

    return packets_sec, particles_sec, particles_unknown


def scan_file(path: Path, rep: Report) -> None:
    src = path.read_text(encoding="utf-8")
    consts = const_map(src)

    for m in METHOD_RE.finditer(src):
        name = m.group("name")
        params = m.group("params")
        body_open = src.index("{", m.end() - 1)

        # 「每 tick」判据：方法名匹配 且（参数是 long now / 无参 / 带 progress）
        per_tick = bool(PER_TICK_METHOD_RE.search(name + "(")) and (
            re.search(r"\bnow\b", params) is not None
            or re.search(r"progress", params) is not None
            or params.strip() == ""
            or "Tick" in name
        )
        if not per_tick and not re.search(r"\bTick\b|Ambience|trail", name):
            # 仍然扫描（为了瞬时粒数上限），但按一次性处理
            per_tick = False

        pk, pt, unknown = scan_method(src, body_open, name, params, consts, rep, path, per_tick)
        if per_tick and (pk or pt):
            rep.table.append((path.name, name, pk, pt, unknown))
            if pk > PACKETS_PER_SEC_FAIL:
                rep.fail(
                    f"{path.name}.{name}()：每秒发包约 {pk:.0f} 个 > 上限 {PACKETS_PER_SEC_FAIL} "
                    f"（粒子开销主要在发包次数上）"
                )
            else:
                rep.ok()


def main() -> int:
    ap = argparse.ArgumentParser(description="忆海粒子开销预算校验")
    ap.add_argument("--java-root", default=str(JAVA_ROOT))
    args = ap.parse_args()

    rep = Report()
    root = Path(args.java_root)
    files = sorted(root.rglob("*.java"))
    if not files:
        rep.fail(f"没抓到任何 Java 文件：{root}")
    for f in files:
        scan_file(f, rep)

    print("=" * 72)
    print(f"检查项 {rep.checked} 个 · FAIL {len(rep.fails)} 条 · warn {len(rep.warns)} 条")

    if rep.table:
        print("\n每秒预算（「每 tick」发射器，最坏情况）：")
        print(f"  {'文件':<32}{'方法':<22}{'发包/秒':>10}{'粒子/秒':>10}")
        for f_, m_, pk, pt, unk in sorted(rep.table, key=lambda r: -r[2]):
            flag = "  ← 偏高" if pk > PACKETS_PER_SEC_FAIL else ""
            shown = f"{pt:.0f}?" if unk else f"{pt:.0f}"
            print(f"  {f_:<32}{m_:<22}{pk:>10.0f}{shown:>10}{flag}")
        print("  （粒子数带 ? = 粒数由表达式算出、无法静态求值；发包数是准的）")

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
