#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
忆海 Mnemosyne —— 卷轴图标生成器
=====================================

【为什么需要这个脚本】

铁魔法（ISS）的法术卷轴图标**全部是同一张底图的改色**。

实测证据（对 ISS 3.16.3 的 10 张卷轴贴图逐像素比对）：
  - scroll.png / scroll_fire / scroll_ice / ... / scroll_eldritch  共 10 张
  - 全部 16x16，**不透明像素数完全一致（137 个），alpha 掩码逐像素零差异**
  - 每张都是对同一张 11 色底图做**严格的 1:1 调色板替换**

所以：**不需要画新图**。只要把官方底图的 11 个颜色替换成忆海的配色即可。

【底图的 11 色调色板结构（实测）】

底图 `scroll.png` 的 11 个颜色按语义分为 3 组：

  PAPER 组（5 色，共 107 像素）—— 羊皮纸主体
    [0] (178,166,132)  n=25   中间调纸面
    [1] (218,210,188)  n=24   亮纸面
    [2] (235,235,235)  n=23   高光
    [3] (132,118,81)   n=18   边缘阴影
    [4] (150,139,108)  n=17   过渡阴影

  GLYPH 组（3 色，共 18 像素）—— 纸上的文字/符文（沿对角线分布）
    [5] (82,75,57)     n=9
    [6] (66,60,45)     n=6
    [10] (57,51,37)    n=3

  RIBBON 组（3 色，共 12 像素）—— 卷轴两端的绑带/封蜡（在四角分布）
    [7] (73,44,30)     n=5
    [8] (124,75,52)    n=4
    [9] (91,53,35)     n=3

【设计要点】

ISS 官方每个学派**只用一个色相**同时染 GLYPH 和 RIBBON。
忆海**用两个色相**：GLYPH = 靛蓝（书写），RIBBON = 品红（封印）。
这是忆海在 10 张卷轴里唯一"双色相"的图标，辨识度最高。

用法：
    python gen_scroll_texture.py --preset mnemosyne --out ../src/main/resources/assets/mnemosyne/textures/item/scroll_memory.png
    python gen_scroll_texture.py --preset mnemosyne --preview preview.png --scale 16
    python gen_scroll_texture.py --list-presets
"""

from __future__ import annotations

import argparse
import colorsys
import os
import sys
from typing import Iterable

try:
    from PIL import Image
except ImportError:
    sys.exit("需要 Pillow：pip install Pillow")

# ---------------------------------------------------------------------------
# 底图：ISS 官方卷轴的 11 色调色板（实测提取，按像素数降序）
# ---------------------------------------------------------------------------
BASE_PALETTE: list[tuple[int, int, int, int]] = [
    (178, 166, 132, 255),  # [0]  PAPER  中间调
    (218, 210, 188, 255),  # [1]  PAPER  亮
    (235, 235, 235, 255),  # [2]  PAPER  高光
    (132, 118, 81, 255),   # [3]  PAPER  边缘阴影
    (150, 139, 108, 255),  # [4]  PAPER  过渡阴影
    (82, 75, 57, 255),     # [5]  GLYPH
    (66, 60, 45, 255),     # [6]  GLYPH
    (73, 44, 30, 255),     # [7]  RIBBON
    (124, 75, 52, 255),    # [8]  RIBBON
    (91, 53, 35, 255),     # [9]  RIBBON
    (57, 51, 37, 255),     # [10] GLYPH
]

# 索引 → 语义分组
GROUP_OF: dict[int, str] = {
    0: "paper", 1: "paper", 2: "paper", 3: "paper", 4: "paper",
    5: "glyph", 6: "glyph", 10: "glyph",
    7: "ribbon", 8: "ribbon", 9: "ribbon",
}


# ---------------------------------------------------------------------------
# 颜色工具
# ---------------------------------------------------------------------------
def hex_to_rgb(h: str) -> tuple[int, int, int]:
    h = h.lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


def rgb_to_hsv(rgb: Iterable[int]) -> tuple[float, float, float]:
    r, g, b = (v / 255.0 for v in rgb)
    h, s, v = colorsys.rgb_to_hsv(r, g, b)
    return h * 360.0, s * 100.0, v * 100.0


def hsv_to_rgb(h: float, s: float, v: float) -> tuple[int, int, int]:
    r, g, b = colorsys.hsv_to_rgb((h % 360) / 360.0, s / 100.0, v / 100.0)
    return round(r * 255), round(g * 255), round(b * 255)


def mix(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    """在 sRGB 空间线性插值。t=0 返回 a，t=1 返回 b。"""
    t = max(0.0, min(1.0, t))
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


# ---------------------------------------------------------------------------
# 调色板构建
# ---------------------------------------------------------------------------
class PaletteSpec:
    """
    一个学派的卷轴配色规格。

    只需要 6 个锚点色，脚本自动按底图的明度分布展开成 11 色。

    paper_light / paper_mid / paper_dark
        羊皮纸的三档明度。对应底图 PAPER 组里最亮 / 中间 / 最暗的三档。
    glyph_light / glyph_dark
        文字/符文的明暗两档。
    ribbon_light / ribbon_mid / ribbon_dark
        绑带/封蜡的三档。
    """

    def __init__(
        self,
        name: str,
        paper_light: str,
        paper_mid: str,
        paper_dark: str,
        glyph_light: str,
        glyph_dark: str,
        ribbon_light: str,
        ribbon_mid: str,
        ribbon_dark: str,
    ) -> None:
        self.name = name
        self.paper_light = hex_to_rgb(paper_light)
        self.paper_mid = hex_to_rgb(paper_mid)
        self.paper_dark = hex_to_rgb(paper_dark)
        self.glyph_light = hex_to_rgb(glyph_light)
        self.glyph_dark = hex_to_rgb(glyph_dark)
        self.ribbon_light = hex_to_rgb(ribbon_light)
        self.ribbon_mid = hex_to_rgb(ribbon_mid)
        self.ribbon_dark = hex_to_rgb(ribbon_dark)

    # -- 按底图明度把锚点展开到具体索引 --------------------------------
    def build(self) -> list[tuple[int, int, int, int]]:
        """
        返回 11 个新颜色，顺序与 BASE_PALETTE 一一对应。

        展开规则：
          PAPER  组按底图 V 值降序 → light / light-mid / mid / mid-dark / dark
          GLYPH  组按底图 V 值降序 → light / mid / dark（3 档插值）
          RIBBON 组按底图 V 值降序 → light / mid / dark（3 档插值）
        """
        out: dict[int, tuple[int, int, int]] = {}

        for group in ("paper", "glyph", "ribbon"):
            idxs = [i for i, g in GROUP_OF.items() if g == group]
            # 按底图亮度降序排列（亮的排前面）
            idxs.sort(key=lambda i: rgb_to_hsv(BASE_PALETTE[i][:3])[2], reverse=True)

            if group == "paper":
                anchors = [self.paper_light, self.paper_mid, self.paper_dark]
            elif group == "glyph":
                anchors = [self.glyph_light, self.glyph_dark]
            else:
                anchors = [self.ribbon_light, self.ribbon_mid, self.ribbon_dark]

            n = len(idxs)
            for pos, idx in enumerate(idxs):
                # 把 pos/(n-1) 映射到锚点序列上的位置
                if n == 1:
                    out[idx] = anchors[0]
                    continue
                t = pos / (n - 1) * (len(anchors) - 1)
                lo = int(t)
                hi = min(lo + 1, len(anchors) - 1)
                out[idx] = mix(anchors[lo], anchors[hi], t - lo)

        return [(*out[i], 255) for i in range(len(BASE_PALETTE))]


# ---------------------------------------------------------------------------
# 预置配色
# ---------------------------------------------------------------------------
PRESETS: dict[str, PaletteSpec] = {
    # 忆海：靛蓝 #534AB7（书写）+ 品红 #D4537E（封印）
    # 纸面用冷调淡紫灰，让靛蓝与品红都能跳出来。
    "mnemosyne": PaletteSpec(
        name="mnemosyne",
        paper_light="#EFEDF7",   # 高光：近白冷紫
        paper_mid="#B7B0D4",     # 中间调：淡薰衣草
        paper_dark="#5C5480",    # 阴影：深靛灰
        glyph_light="#7A6FD6",   # 符文亮部：亮靛蓝
        glyph_dark="#2E2570",    # 符文暗部：近黑靛
        ribbon_light="#F2A6C2",  # 绑带亮部：浅品红
        ribbon_mid="#D4537E",    # 绑带主色：忆海品红
        ribbon_dark="#7C2745",   # 绑带暗部：深品红
    ),
    # 备选：如果想更"暗黑档案库"，用这套（整体压暗）
    "mnemosyne_dark": PaletteSpec(
        name="mnemosyne_dark",
        paper_light="#8C84AE",
        paper_mid="#544C78",
        paper_dark="#262047",
        glyph_light="#9C8FF0",
        glyph_dark="#3A2E8C",
        ribbon_light="#F2A6C2",
        ribbon_mid="#D4537E",
        ribbon_dark="#6B1F3A",
    ),
}


# ---------------------------------------------------------------------------
# 生成
# ---------------------------------------------------------------------------
def recolor(base_img: Image.Image, new_palette: list[tuple[int, int, int, int]]) -> Image.Image:
    """对底图做严格的 1:1 调色板替换。未被底图使用的颜色原样保留。"""
    lut = {old: new for old, new in zip(BASE_PALETTE, new_palette)}
    out = Image.new("RGBA", base_img.size, (0, 0, 0, 0))
    px_in = base_img.load()
    px_out = out.load()
    w, h = base_img.size
    for y in range(h):
        for x in range(w):
            p = px_in[x, y]
            px_out[x, y] = lut.get(p, p)
    return out


def find_base_texture() -> str | None:
    """在常见位置找 ISS 的 scroll.png 底图。"""
    candidates = [
        os.path.join(
            "..",
            "[Iron的法术与魔法书] irons_spellbooks-1.20.1-3.16.3",
            "assets", "irons_spellbooks", "textures", "item", "scroll.png",
        ),
        os.path.join(
            "[Iron的法术与魔法书] irons_spellbooks-1.20.1-3.16.3",
            "assets", "irons_spellbooks", "textures", "item", "scroll.png",
        ),
    ]
    for c in candidates:
        if os.path.isfile(c):
            return c
    return None


# ---------------------------------------------------------------------------
# 通用色相偏移（用于符文等"手工逐像素改色"的贴图）
# ---------------------------------------------------------------------------
def hue_shift(
    img: Image.Image,
    target_hue: float,
    sat_scale: float = 1.0,
    val_scale: float = 1.0,
    min_sat: float = 8.0,
) -> Image.Image:
    """
    把整张贴图的色相统一拉到 target_hue。

    实测结论：ISS 的 `<school>_rune.png` **不是**干净的色相旋转
    （逐像素色相差的标准差高达 17~103 度），而是美术手工改色。
    所以这里只能做**近似**：保留原图的明度与饱和度结构，只改色相。
    对符文这种小尺寸、高对比的图标，近似结果可以接受。

    min_sat：饱和度低于此值的像素（近灰/白/黑）不做色相偏移，
             否则会污染高光与阴影。
    """
    out = img.copy()
    px = out.load()
    w, h = out.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            hh, ss, vv = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            s100, v100 = ss * 100, vv * 100
            if s100 < min_sat:
                continue  # 近灰像素保持原样
            # 饱和度高的像素权重更大，避免半透明边缘被染脏
            weight = min(1.0, (s100 - min_sat) / 40.0)
            new_h = hh * 360 * (1 - weight) + target_hue * weight
            new_s = min(100.0, s100 * sat_scale)
            new_v = min(100.0, v100 * val_scale)
            nr, ng, nb = hsv_to_rgb(new_h, new_s, new_v)
            px[x, y] = (nr, ng, nb, a)
    return out


def gradient_map(
    img: Image.Image,
    ramp: list[tuple[int, int, int]],
    gamma: float = 1.0,
    preserve_alpha: bool = True,
) -> Image.Image:
    """
    亮度渐变映射（duotone / gradient map）。

    为什么需要这个模式：
      实测 ISS 的 `<school>_rune.png` 原图**饱和度极低**
      （arcane_rune 饱和度中位数只有 12.3，最大 46.6）。
      对低饱和图做色相偏移几乎看不出效果 —— 因为"灰"没有色相可转。
      官方美术的实际做法是"**提高饱和度 + 改色相**"，属于手工改色。

    这个函数用另一种更稳的方式达到同样效果：
      把每个像素的**相对亮度**映射到一条自定义渐变上。
      原图的明暗结构（符文的笔画、阴影、高光）被完整保留，
      但颜色完全由渐变决定 —— 结果是干净、鲜艳、可控的。

    ramp：从暗到亮的目标颜色列表（至少 2 个）。
    gamma：>1 压暗中间调，<1 提亮中间调。
    """
    if len(ramp) < 2:
        raise ValueError("ramp 至少需要 2 个颜色")

    # 预计算亮度 → 颜色 的查找表（256 级）
    lut: list[tuple[int, int, int]] = []
    for i in range(256):
        t = (i / 255.0) ** gamma
        pos = t * (len(ramp) - 1)
        lo = int(pos)
        hi = min(lo + 1, len(ramp) - 1)
        lut.append(mix(ramp[lo], ramp[hi], pos - lo))

    out = img.copy()
    px = out.load()
    w, h = out.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            # 感知亮度（Rec. 601）
            lum = (0.299 * r + 0.587 * g + 0.114 * b)
            nr, ng, nb = lut[max(0, min(255, int(lum)))]
            px[x, y] = (nr, ng, nb, a if preserve_alpha else 255)
    return out


def build_ramp(spec: "PaletteSpec", style: str) -> list[tuple[int, int, int]]:
    """按预设生成渐变映射用的色阶。"""
    if style == "indigo_magenta":     # 深靛 → 靛蓝 → 品红 → 浅粉
        return [spec.glyph_dark, spec.paper_dark, spec.glyph_light, spec.ribbon_mid, spec.ribbon_light]
    if style == "indigo_only":        # 深靛 → 靛蓝 → 淡紫
        return [spec.glyph_dark, spec.glyph_light, spec.paper_light]
    if style == "magenta_only":       # 深品红 → 品红 → 浅粉
        return [spec.ribbon_dark, spec.ribbon_mid, spec.ribbon_light]
    if style == "paper":              # 纯纸面（深灰紫 → 淡紫）
        return [spec.paper_dark, spec.paper_mid, spec.paper_light]
    raise ValueError(f"未知色阶风格 {style!r}")



ASSET_MATRIX = """
ISS 图标资产的可复用性（实测结论）
=====================================================================
资产                        掩码一致性   改色方式              结论
---------------------------------------------------------------------
scroll_<school>.png         完全一致     11 色严格调色板替换   ★ 精确生成
<school>_rune.png           完全一致     手工逐像素改色         ◐ 色相偏移近似
<rarity>_ink.png            不一致       独立美术               x 需美术或复用
upgrade_orb_<school>.png    不一致       独立美术 + 动画        x 需美术或复用
---------------------------------------------------------------------
★ = 本脚本可精确生成   ◐ = 本脚本可近似生成   x = 建议复用官方资产
"""



def main() -> int:
    ap = argparse.ArgumentParser(
        description="忆海图标生成器 —— 卷轴精确改色 / 符文色相偏移",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=ASSET_MATRIX,
    )
    ap.add_argument("--preset", default="mnemosyne", help="配色预设名（见 --list-presets）")
    ap.add_argument("--base", default=None, help="底图 png 路径（卷轴默认自动查找 ISS scroll.png）")
    ap.add_argument("--out", default=None, help="输出 png 路径")
    ap.add_argument("--preview", default=None, help="输出放大的预览图路径")
    ap.add_argument("--scale", type=int, default=16, help="预览放大倍数（默认 16）")
    ap.add_argument("--list-presets", action="store_true", help="列出所有配色预设")
    ap.add_argument("--matrix", action="store_true", help="打印 ISS 图标资产可复用性矩阵")

    # --- 模式 ---
    ap.add_argument("--mode", choices=("scroll", "hue", "ramp"), default="scroll",
                    help="scroll=卷轴精确调色板替换（默认）；hue=色相偏移；ramp=亮度渐变映射（符文推荐）")
    ap.add_argument("--hue-source", default="ribbon",
                    help="hue 模式的目标色相来源：paper|glyph|ribbon（默认 ribbon）")
    ap.add_argument("--target-hue", type=float, default=None,
                    help="hue 模式：直接指定目标色相角度，覆盖 --hue-source")
    ap.add_argument("--sat-scale", type=float, default=1.0, help="hue 模式的饱和度倍率")
    ap.add_argument("--val-scale", type=float, default=1.0, help="hue 模式的明度倍率")
    ap.add_argument("--ramp", default="indigo_magenta",
                    help="ramp 模式的色阶：indigo_magenta|indigo_only|magenta_only|paper")
    ap.add_argument("--gamma", type=float, default=1.0, help="ramp 模式的中间调 gamma（>1 压暗）")
    args = ap.parse_args()

    if args.matrix:
        print(ASSET_MATRIX)
        return 0

    if args.list_presets:
        for k, v in PRESETS.items():
            print(f"{k:20s} paper={v.paper_mid} glyph={v.glyph_light} ribbon={v.ribbon_mid}")
        return 0

    if args.preset not in PRESETS:
        sys.exit(f"未知预设 {args.preset!r}，可用：{', '.join(PRESETS)}")
    spec = PRESETS[args.preset]

    # ================= 模式 C：亮度渐变映射（符文推荐）=================
    if args.mode == "ramp":
        if not args.base:
            sys.exit("ramp 模式必须用 --base 指定底图（如 ISS 的 arcane_rune.png）")
        if not os.path.isfile(args.base):
            sys.exit(f"找不到底图：{args.base}")
        ramp = build_ramp(spec, args.ramp)
        src = Image.open(args.base).convert("RGBA")
        result = gradient_map(src, ramp, args.gamma)
        print(f"渐变映射模式：色阶 {args.ramp} gamma={args.gamma}")
        print("  色阶 " + " -> ".join("#%02X%02X%02X" % c for c in ramp))

    # ================= 模式 A：色相偏移 =================
    elif args.mode == "hue":
        if not args.base:
            sys.exit("色相偏移模式必须用 --base 指定底图（如 ISS 的 arcane_rune.png）")
        if not os.path.isfile(args.base):
            sys.exit(f"找不到底图：{args.base}")

        if args.target_hue is not None:
            target_hue = args.target_hue
        else:
            anchor = {
                "paper": spec.paper_mid,
                "glyph": spec.glyph_light,
                "ribbon": spec.ribbon_mid,
            }.get(args.hue_source, spec.ribbon_mid)
            target_hue = rgb_to_hsv(anchor)[0]

        src = Image.open(args.base).convert("RGBA")
        result = hue_shift(src, target_hue, args.sat_scale, args.val_scale)
        print(f"色相偏移模式：目标色相 {target_hue:.1f}°  (sat x{args.sat_scale}, val x{args.val_scale})")

    # ================= 模式 B：卷轴精确调色板替换 =================
    else:
        base_path = args.base or find_base_texture()
        if not base_path or not os.path.isfile(base_path):
            sys.exit("找不到 ISS 底图 scroll.png，请用 --base 指定路径")
        src = Image.open(base_path).convert("RGBA")
        if src.size != (16, 16):
            print(f"警告：底图尺寸为 {src.size}，预期 16x16", file=sys.stderr)
        result = recolor(src, spec.build())
        print(f"调色板替换模式：{args.preset}")

    # ================= 输出 =================
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        result.save(args.out)
        print(f"已写出 {args.out}  ({result.size[0]}x{result.size[1]})")

    if args.preview:
        os.makedirs(os.path.dirname(os.path.abspath(args.preview)), exist_ok=True)
        prev = result.resize(
            (result.size[0] * args.scale, result.size[1] * args.scale), Image.NEAREST
        )
        bg = Image.new("RGBA", prev.size, (60, 60, 70, 255))
        bg.alpha_composite(prev)
        bg.convert("RGB").save(args.preview)
        print(f"已写出预览 {args.preview}")

    if not args.out and not args.preview:
        print(f"调色板 [{args.preset}]：")
        for i, (b, n) in enumerate(zip(BASE_PALETTE, spec.build())):
            print(f"  [{i:2d}] {GROUP_OF[i]:6s} {str(b[:3]):18s} -> {n[:3]}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
