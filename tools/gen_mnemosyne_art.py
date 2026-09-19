#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
忆海 Mnemosyne —— 美术资源生成器（WS-H2）
=========================================

【为什么要有这个脚本】

本脚本交付全部程序生成的贴图，其中 18 张是法术图标。手绘不现实（没法在仓库里复核），
所以全部**程序生成**：
  - 好处 1：任何一张都能改一行参数重新生成，review 时看 diff 就够
  - 好处 2：ISS 是 All Rights Reserved —— 程序生成的是我们自己的派生作品，没有授权风险
  - 好处 3：批量保证一致性（同一套描边、同一套配色、同一套几何母题）

【设计契约】（冻结）
  见 `docs/09_美术与音效.md` §一（图形母题 + 动势）、§二（配色系统）

  主色   靛蓝 #534AB7    浅 #AFA9EC   深 #26215C
  辅色   品红 #D4537E    浅 #ED93B1   深 #4B1528

  三种记忆的颜色编码（docs/09 §2.2 —— **这是最重要的视觉规则**）：
    术忆 = 靛蓝 #534AB7     质忆 = 品红 #D4537E     痛忆 = 深紫 #3C3489

  ⚠️ `docs/tech/12` 提示词 C 里写的"痛忆琥珀"是**错的**，本脚本按设计文档实现：
     ① docs/09 §2.2 与 docs/06 都写 痛忆 = 深紫 #3C3489；
     ② docs/09 §2.3 把"任何火焰橙红"列为**禁用色**（会和火学派撞车），琥珀属于该范围。

  几何母题：碎片（Shard）+ 环形（Ring）。记忆 = 碎片，认知 = 环形。
  动势：写入 = 由外向内；释放 = 由内向外；遗忘 = 向下坠落；过载 = 环绕。

【产出】
  item/           10 张  忆晶 / 权杖 / 匕首 / 忆之墨水 / 褪色书页 / 亲和戒指 / 法袍四件
  models/armor/    2 张  穿戴图层（64x32，原版护甲 UV）
  block/           4 张  忆者砖 / 裂纹变体 / 忆碑 / 忆晶簇
  gui/spell_icons/ 18 张 与 18 个 spellId 逐字同名

用法：
    python tools/gen_mnemosyne_art.py --all
    python tools/gen_mnemosyne_art.py --check
    python tools/gen_mnemosyne_art.py --contact-sheet docs/previews/ws_h2_contact_sheet.png
"""

from __future__ import annotations

import argparse
import math
import os
import random
import sys

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.exit("需要 Pillow：pip install Pillow")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "mnemosyne", "textures")

# ---------------------------------------------------------------------------
# 18 个法术 id（冻结契约 —— 必须与 registry/ModSpells.java / sounds.json / lang 三处一致）
# 顺序取自 docs/tech/04_法术等级强度表.md §三 总表
# ---------------------------------------------------------------------------
SPELL_IDS = [
    "memory_arrow", "glimpse", "memory_shard", "engrave", "encode_trait",
    "encode_pain", "amnesia", "expand_mind", "recollection", "curse_of_oblivion",
    "cognitive_collapse", "memory_theft", "mass_amnesia", "deja_vu", "sea_of_memory",
    "thousand_memories",
]

# ---------------------------------------------------------------------------
# 调色板（docs/09 §2.1 §2.2）
# ---------------------------------------------------------------------------
INDIGO_M, INDIGO_L, INDIGO_D = "#534AB7", "#AFA9EC", "#26215C"
MAGENTA_M, MAGENTA_L, MAGENTA_D = "#D4537E", "#ED93B1", "#4B1528"
PAIN_M, PAIN_L, PAIN_D = "#3C3489", "#7A6FD6", "#1B1642"   # 痛忆 = 深紫
BONE, STONE_M, STONE_L, STONE_D = "#F1EFE8", "#888780", "#A8A79F", "#5E5D58"
PAPER_L, PAPER_M, PAPER_D = "#EFEDF7", "#B7B0D4", "#5C5480"


def _scheme(m, d, l, h, k, a, A, b):
    """一个方案：m/d/l/h 是主色四档，a/A/b 是"另一个色相"的辅色三档（忆海的双色相签名）。"""
    return {
        "k": k, "d": d, "m": m, "l": l, "h": h,
        "a": a, "A": A, "b": b,
        "s": STONE_M, "S": STONE_L, "x": STONE_D,
        "g": BONE, "p": PAPER_M, "P": PAPER_L, "q": PAPER_D,
    }


SCHEMES = {
    # 靛蓝主色，辅色用品红
    "indigo": _scheme(INDIGO_M, INDIGO_D, INDIGO_L, "#E6E3FA", "#16123A",
                      MAGENTA_M, MAGENTA_L, MAGENTA_D),
    # 品红主色，辅色用靛蓝
    "magenta": _scheme(MAGENTA_M, MAGENTA_D, MAGENTA_L, "#FBDCE8", "#2E0C17",
                       INDIGO_M, INDIGO_L, INDIGO_D),
    # 痛忆深紫主色，辅色用品红
    "pain": _scheme(PAIN_M, PAIN_D, PAIN_L, "#D6D1F5", "#0E0B22",
                    MAGENTA_M, MAGENTA_L, MAGENTA_D),
}

# spellId -> 配色方案
SPELL_SCHEME = {
    "memory_arrow": "indigo",
    "glimpse": "indigo",
    "memory_shard": "indigo",
    "encode_trait": "magenta",      # 质忆 = 品红
    "encode_pain": "pain",          # 痛忆 = 深紫
    "amnesia": "indigo",            # 失忆粒子 = 浅靛蓝
    "expand_mind": "indigo",
    "engrave": "indigo",       # 铭忆 = 靛蓝（写入家族）
    "recollection": "magenta",      # 走马灯 = 品红
    "curse_of_oblivion": "magenta",  # 遗忘诅咒领域 = 靛蓝 + 品红
    "cognitive_collapse": "magenta",  # 认知过载 = 品红
    "memory_theft": "indigo",
    "mass_amnesia": "indigo",       # 集体遗忘 = 靛蓝
    "deja_vu": "indigo",            # 既视感 = 靛蓝
    "sea_of_memory": "indigo",
    "thousand_memories": "indigo",
}

TRANSPARENT = (0, 0, 0, 0)


def hex_to_rgba(h: str) -> tuple[int, int, int, int]:
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255


def shade(c: tuple[int, int, int], f: float) -> tuple[int, int, int]:
    return tuple(max(0, min(255, round(v * f))) for v in c)


def jitter(c: tuple[int, int, int], j: int, rng: random.Random) -> tuple[int, int, int]:
    d = rng.randint(-j, j)
    return tuple(max(0, min(255, v + d)) for v in c)


# ---------------------------------------------------------------------------
# 光栅画布：像素值可以是"角色字母"（查 scheme）也可以是直接的 RGB(A)
# ---------------------------------------------------------------------------
class Raster:
    def __init__(self, w: int, h: int, scheme: dict | None = None):
        self.w, self.h = w, h
        self.scheme = scheme or {}
        self.data = [[TRANSPARENT] * w for _ in range(h)]

    def col(self, c):
        if isinstance(c, tuple):
            return c if len(c) == 4 else (c[0], c[1], c[2], 255)
        if c not in self.scheme:
            raise KeyError(f"角色 {c!r} 不在配色方案里")
        return hex_to_rgba(self.scheme[c])

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.data[y][x] = self.col(c)

    def get(self, x, y):
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.data[y][x]
        return TRANSPARENT

    def clear(self, x, y):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.data[y][x] = TRANSPARENT

    def opaque(self, x, y) -> bool:
        return self.get(x, y)[3] != 0

    def count(self) -> int:
        return sum(1 for row in self.data for p in row if p[3] != 0)

    def image(self) -> Image.Image:
        img = Image.new("RGBA", (self.w, self.h), TRANSPARENT)
        px = img.load()
        for y in range(self.h):
            for x in range(self.w):
                px[x, y] = self.data[y][x]
        return img


# ---------------------------------------------------------------------------
# 几何 DSL（全部基于"像素中心 = x+0.5"，与 PIL 的像素网格一致）
# ---------------------------------------------------------------------------
def _seg_dist(px, py, x0, y0, x1, y1) -> float:
    dx, dy = x1 - x0, y1 - y0
    L2 = dx * dx + dy * dy
    if L2 == 0.0:
        return math.hypot(px - x0, py - y0)
    t = max(0.0, min(1.0, ((px - x0) * dx + (py - y0) * dy) / L2))
    return math.hypot(px - (x0 + t * dx), py - (y0 + t * dy))


def seg(r: Raster, x0, y0, x1, y1, role, width=1.0):
    """线（端点按像素索引，内部自动 +0.5）。"""
    tol = width / 2.0 + 0.25
    for y in range(r.h):
        for x in range(r.w):
            if _seg_dist(x + 0.5, y + 0.5, x0 + 0.5, y0 + 0.5, x1 + 0.5, y1 + 0.5) <= tol:
                r.set(x, y, role)


def disc(r: Raster, cx, cy, rad, role):
    for y in range(r.h):
        for x in range(r.w):
            if math.hypot(x + 0.5 - cx, y + 0.5 - cy) <= rad + 0.25:
                r.set(x, y, role)


def ring(r: Raster, cx, cy, rad, role, thick=1.0):
    tol = thick / 2.0 + 0.25
    for y in range(r.h):
        for x in range(r.w):
            if abs(math.hypot(x + 0.5 - cx, y + 0.5 - cy) - rad) <= tol:
                r.set(x, y, role)


def diamond(r: Raster, cx, cy, rx, ry, role):
    for y in range(r.h):
        for x in range(r.w):
            if abs(x + 0.5 - cx) / rx + abs(y + 0.5 - cy) / ry <= 1.0:
                r.set(x, y, role)


def rect(r: Raster, x, y, w, h, role):
    for yy in range(y, y + h):
        for xx in range(x, x + w):
            r.set(xx, yy, role)


def rect_outline(r: Raster, x, y, w, h, role):
    for xx in range(x, x + w):
        r.set(xx, y, role)
        r.set(xx, y + h - 1, role)
    for yy in range(y, y + h):
        r.set(x, yy, role)
        r.set(x + w - 1, yy, role)


def shard(r: Raster, cx, cy, length, angle_deg, role, half=1.3):
    """碎片：从 (cx,cy) 沿 angle 方向延伸 length，末端收成尖。"""
    a = math.radians(angle_deg)
    tx, ty = cx + math.cos(a) * length, cy + math.sin(a) * length
    dx, dy = tx - cx, ty - cy
    L2 = dx * dx + dy * dy
    if L2 == 0:
        return
    for y in range(r.h):
        for x in range(r.w):
            pxc, pyc = x + 0.5, y + 0.5
            t = max(0.0, min(1.0, ((pxc - cx) * dx + (pyc - cy) * dy) / L2))
            if math.hypot(pxc - (cx + t * dx), pyc - (cy + t * dy)) <= half * (1.0 - t) + 0.25:
                r.set(x, y, role)


def outline(r: Raster, role="k"):
    """给所有不透明区域加一圈 1px 暗描边（透明邻域才描，内部不受影响）。

    这一步是整个图标集的"统一笔触"—— 18 张法术图标因此看起来是一套。
    """
    add = []
    for y in range(r.h):
        for x in range(r.w):
            if r.opaque(x, y):
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if r.opaque(x + dx, y + dy):
                    add.append((x, y))
                    break
    for x, y in add:
        r.set(x, y, role)


# ===========================================================================
# 一、18 张法术图标（16x16，与 ISS 官方图标同尺寸 —— 实测官方 116 张全是 16x16）
# ===========================================================================
def _encode_frame(r: Raster):
    """写入类三件套的共同骨架：环形（认知）+ 由外向内穿入的箭头（动势：写入）。"""
    ring(r, 8.0, 8.0, 5.6, "m", 1.0)
    seg(r, 13.5, 13.5, 10.4, 10.4, "l", 2.0)


def sp_memory_arrow(r):
    """忆矢：一枚碎片箭，由左下射向右上，带拖尾碎片。"""
    seg(r, 2, 13, 9, 6, "m", 2.0)
    diamond(r, 11.5, 3.5, 2.3, 2.3, "l")
    r.set(11, 3, "h")
    r.set(2, 10, "a")
    r.set(3, 15, "d")


def sp_glimpse(r):
    """窥忆：一只眼（杏仁形眼眶），瞳孔是一枚碎片，两侧有"扫描"标记。"""
    for y in range(2, 15):
        for x in range(1, 15):
            dx, dy = (x + 0.5 - 8.0) / 6.2, (y + 0.5 - 8.0) / 3.6
            v = abs(dx) ** 1.7 + abs(dy) ** 1.7
            if 0.72 <= v <= 1.30:
                r.set(x, y, "m")
    disc(r, 8.0, 8.0, 2.3, "l")
    r.set(7, 7, "h")
    r.set(1, 4, "a")
    r.set(14, 11, "a")



def sp_memory_shard(r):
    """碎忆：碎片由内向外炸开（动势：释放 = 由内向外）。"""
    for ang, role in ((45, "m"), (135, "l"), (225, "l"), (315, "m")):
        shard(r, 8.0, 8.0, 5.8, ang, role, half=1.2)
    disc(r, 8.0, 8.0, 1.5, "h")



def sp_engrave(r):
    """铭忆：一枚「刻进忆格」的印记 —— 圆环 + 向内收束的刻痕（写入 = 由外向内）。

    与「质忆 / 痛忆」必须一眼区分：那两个是**从别人身上取**（碎片向外），
    铭忆是**往自己身上刻**（环收束到中心），所以母题用环形 + 内收箭头。
    """
    ring(r, 7.5, 7.5, 4.6, "m", 1.4)          # 外环 = 忆格
    ring(r, 7.5, 7.5, 3.0, "d", 1.0)          # 内环 = 容量的层次
    # 四道向内收束的刻痕（写入的动势）
    for (x0, y0, x1, y1) in ((7.5, 1.6, 7.5, 4.2), (7.5, 13.4, 7.5, 10.8),
                             (1.6, 7.5, 4.2, 7.5), (13.4, 7.5, 10.8, 7.5)):
        seg(r, x0, y0, x1, y1, "l", 1.2)
    diamond(r, 7.5, 7.5, 2.0, 2.4, "h")       # 中心的忆晶（被刻下的那一点）
    r.set(7, 7, "l")
    r.set(8, 8, "l")
    r.set(6, 7, "a")                          # 一枚品红印记，标记"已铭刻"
    r.set(9, 8, "a")


def sp_encode_trait(r):
    """质忆：环形 + 内向箭头 + 被捕获的两个"眼睛"（生物特性）。"""
    _encode_frame(r)
    disc(r, 6.5, 7.5, 1.1, "h")
    disc(r, 9.5, 7.5, 1.1, "h")
    seg(r, 6.5, 10.0, 9.5, 10.0, "l", 1.0)


def sp_encode_pain(r):
    """痛忆：环形 + 内向箭头 + 一道锯齿状的"伤口"。"""
    _encode_frame(r)
    seg(r, 6.0, 9.5, 7.5, 6.5, "a", 1.0)
    seg(r, 7.5, 6.5, 9.0, 9.5, "a", 1.0)
    seg(r, 9.0, 9.5, 10.5, 6.5, "a", 1.0)


def sp_amnesia(r):
    """失忆：问号 + 正在消散的碎片（docs/09 粒子表：问号状粒子）。"""
    for p in ((6, 5), (7, 4), (8, 4), (9, 5), (5, 6), (9, 6), (8, 7), (7, 8)):
        r.set(*p, "l")
    r.set(7, 9, "l")
    r.set(7, 10, "l")
    r.set(7, 12, "h")
    r.set(4, 13, "d")
    r.set(10, 12, "d")
    r.set(2, 9, "a")
    r.set(13, 6, "a")


def sp_expand_mind(r):
    """忆格扩张：一个忆格向四个方向同时撑开（母题：碎片 + 环形）。"""
    diamond(r, 8.0, 8.0, 2.8, 3.4, "m")
    diamond(r, 8.0, 8.0, 1.3, 1.7, "h")
    for dx, dy in ((1, -1), (1, 1), (-1, 1), (-1, -1)):
        seg(r, 8.0 + dx * 4.0, 8.0 + dy * 4.0, 8.0 + dx * 6.2, 8.0 + dy * 6.2, "a", 1.0)
        diamond(r, 8.0 + dx * 6.2, 8.0 + dy * 6.2, 1.3, 1.3, "a")



def sp_recollection(r):
    """走马灯：三格画面沿弧线排开。"""
    for x, y, role in ((1, 6, "m"), (6, 3, "a"), (11, 6, "m")):
        rect_outline(r, x, y, 4, 4, role)
        rect(r, x + 1, y + 1, 2, 2, "l")
    r.set(5, 8, "d")
    r.set(10, 8, "d")


def sp_curse_of_oblivion(r):
    """遗忘诅咒：环形 + 两个向下坠落的符号（动势：遗忘 = 向下坠落）。"""
    ring(r, 8.0, 8.0, 5.6, "a", 1.0)
    seg(r, 5.5, 4.0, 5.5, 8.0, "l", 1.0)
    diamond(r, 5.5, 9.5, 1.4, 1.4, "l")
    seg(r, 10.5, 4.0, 10.5, 8.0, "l", 1.0)
    diamond(r, 10.5, 9.5, 1.4, 1.4, "l")
    disc(r, 8.0, 6.0, 1.5, "h")


def sp_cognitive_collapse(r):
    """认知崩坏：三层光环被一道裂纹劈开。"""
    ring(r, 8.0, 8.0, 2.4, "h", 1.0)
    ring(r, 8.0, 8.0, 4.2, "m", 1.0)
    ring(r, 8.0, 8.0, 6.0, "m", 1.0)
    for x0, y0, x1, y1 in ((2, 2, 5, 6), (5, 6, 8, 7), (8, 7, 11, 10), (11, 10, 13, 13)):
        seg(r, x0, y0, x1, y1, "b", 1.0)


def sp_memory_theft(r):
    """记忆掠夺：从容器里把碎片拽出来。"""
    ring(r, 7.0, 9.0, 4.6, "m", 1.0)
    for p in ((10, 5), (11, 5), (11, 6)):
        r.clear(*p)
    for p in ((9, 6), (10, 4), (12, 3)):
        r.set(*p, "a")
    diamond(r, 13.0, 2.5, 1.8, 1.8, "l")
    r.set(7, 9, "d")


def sp_mass_amnesia(r):
    """集体遗忘：一道大波纹，同时命中多个目标。"""
    ring(r, 8.0, 8.0, 3.2, "h", 1.0)
    ring(r, 8.0, 8.0, 6.0, "m", 1.0)
    for cx, cy in ((8.5, 2.5), (2.8, 11.0), (13.2, 11.0)):
        disc(r, cx, cy, 1.2, "a")


def sp_deja_vu(r):
    """既视感：两个错位的环（"重影"）+ 中心回声。"""
    ring(r, 5.6, 8.0, 4.4, "m", 1.0)
    ring(r, 10.4, 8.0, 4.4, "a", 1.0)
    disc(r, 8.0, 8.0, 1.4, "h")


def sp_sea_of_memory(r):
    """忆海：摊开的书页 + 向上飘浮的记忆碎片。"""
    rect(r, 1, 11, 6, 3, "l")
    rect(r, 9, 11, 6, 3, "l")
    seg(r, 1, 11, 6, 9, "m", 1.0)
    seg(r, 9, 9, 14, 11, "m", 1.0)
    seg(r, 7, 9, 7, 14, "m", 1.0)
    seg(r, 8, 9, 8, 14, "m", 1.0)
    seg(r, 3.5, 8.0, 3.5, 6.0, "m", 1.0)
    seg(r, 8.0, 7.0, 8.0, 4.0, "a", 1.0)
    seg(r, 12.5, 8.0, 12.5, 6.0, "m", 1.0)
    r.set(5, 3, "d")
    r.set(11, 3, "d")
    r.set(2, 4, "a")
    r.set(13, 4, "a")


def sp_thousand_memories(r):
    """千忆归一：大量碎片向中心收拢。"""
    roles = ("m", "a", "l", "m")
    for i in range(8):
        ang = i * 45.0
        shard(r, 8.0 + math.cos(math.radians(ang)) * 6.6,
              8.0 + math.sin(math.radians(ang)) * 6.6,
              2.6, ang + 180.0, roles[i % 4], half=1.1)
    ring(r, 8.0, 8.0, 3.0, "a", 1.0)
    disc(r, 8.0, 8.0, 1.9, "h")


SPELL_PAINTERS = {
    "memory_arrow": sp_memory_arrow,
    "glimpse": sp_glimpse,
    "memory_shard": sp_memory_shard,
    "engrave": sp_engrave,
    "encode_trait": sp_encode_trait,
    "encode_pain": sp_encode_pain,
    "amnesia": sp_amnesia,
    "expand_mind": sp_expand_mind,
    "recollection": sp_recollection,
    "curse_of_oblivion": sp_curse_of_oblivion,
    "cognitive_collapse": sp_cognitive_collapse,
    "memory_theft": sp_memory_theft,
    "mass_amnesia": sp_mass_amnesia,
    "deja_vu": sp_deja_vu,
    "sea_of_memory": sp_sea_of_memory,
    "thousand_memories": sp_thousand_memories,
}


# ===========================================================================
# 二、6 张物品图标（16x16）
# ===========================================================================
def it_memory_crystal(r):
    """忆晶：约 4cm 的不规则晶体，内部有一条流动的靛蓝光带（docs/07 §4.1）。"""
    widths = {2: 1, 3: 2, 4: 2, 5: 3, 6: 3, 7: 3, 8: 3, 9: 3, 10: 3, 11: 2, 12: 2, 13: 1}
    for y, w in widths.items():
        for x in range(8 - w, 8 + w):
            r.set(x, y, "m" if x < 8 else "d")
        r.set(8 - w, y, "l")          # 左棱高光
    for x, y, role in ((6, 7, "l"), (7, 7, "h"), (7, 8, "h"), (8, 8, "l"), (8, 9, "l")):
        r.set(x, y, role)             # 内部斜向流动光带
    r.set(6, 4, "l")
    r.set(9, 11, "l")



def it_mnemonic_robe_helmet(r):
    """忆者头冠：兜帽 + 品红帽檐 + 前端忆晶。"""
    hood = {3: (6, 9), 4: (5, 10), 5: (4, 11), 6: (3, 12), 7: (3, 12),
            8: (2, 13), 9: (2, 13), 10: (3, 12), 11: (4, 11)}
    for y, (x0, x1) in hood.items():
        for x in range(x0, x1 + 1):
            r.set(x, y, "m")
    for x in range(6, 10):
        r.set(x, 3, "l")
    for x in range(5, 11):
        r.set(x, 4, "l")
    for y in range(6, 11):            # 面部开口
        for x in range(6, 10):
            r.set(x, y, "d")
    for x in range(4, 12):            # 帽檐镶边
        r.set(x, 11, "a")
    r.set(7, 4, "h")
    r.set(8, 4, "h")


def it_mnemonic_robe_chestplate(r):
    """忆者法袍：深靛蓝 + 品红 V 领 + 胸前忆晶胸针（docs/07 §2.1）。"""
    rows = {2: (6, 9), 3: (4, 11), 4: (3, 12), 5: (2, 13), 6: (3, 12), 7: (4, 11),
            8: (4, 11), 9: (4, 11), 10: (4, 11), 11: (4, 11), 12: (5, 10), 13: (5, 10)}
    for y, (x0, x1) in rows.items():
        for x in range(x0, x1 + 1):
            r.set(x, y, "m")
    for x in range(4, 12):
        r.set(x, 3, "l")
    r.set(6, 4, "a")
    r.set(9, 4, "a")
    r.set(7, 5, "a")
    r.set(8, 5, "a")
    r.set(2, 6, "a")
    r.set(13, 6, "a")
    diamond(r, 8.0, 8.0, 1.7, 1.7, "h")
    r.set(8, 8, "l")


def it_mnemonic_robe_leggings(r):
    """忆者护腿：腰带 + 两条腿 + 品红裤脚。"""
    for y in (2, 3):
        for x in range(4, 12):
            r.set(x, y, "m")
    for x in range(4, 12):
        r.set(x, 2, "l")
    for x in range(4, 12):
        r.set(x, 4, "a")
    for y in range(5, 14):
        for x in range(4, 7):
            r.set(x, y, "m")
        for x in range(9, 12):
            r.set(x, y, "m")
    for y in (12, 13):
        for x in range(4, 7):
            r.set(x, y, "a")
        for x in range(9, 12):
            r.set(x, y, "a")


def it_mnemonic_robe_boots(r):
    """忆者长靴：品红靴口 + 前伸的靴尖。"""
    for y in range(4, 13):
        for x in range(3, 7):
            r.set(x, y, "m")
        for x in range(9, 13):
            r.set(x, y, "m")
    for y in (4, 5):
        for x in range(3, 7):
            r.set(x, y, "a")
        for x in range(9, 13):
            r.set(x, y, "a")
    for y in (11, 12):
        for x in range(2, 7):
            r.set(x, y, "m")
        for x in range(9, 14):
            r.set(x, y, "m")
    r.set(2, 12, "d")
    r.set(13, 12, "d")



def it_memory_staff(r):
    """忆晶权杖（docs/07 §3.1）：竖直杖身 + 顶端忆晶。

    权杖的核心卖点是"装备后解锁指定释放"，但**图标本身**要传达的是
    "这是一把能施法的长杖" —— 所以顶端忆晶画得比杖身更亮，一眼能认出是忆海系。
    """
    rect(r, 7, 4, 2, 11, "d")            # 杖身（深色木/金属）
    rect(r, 7, 4, 1, 11, "m")            # 左侧受光
    diamond(r, 7.5, 2.6, 2.4, 2.2, "l")  # 顶端忆晶
    diamond(r, 7.5, 2.6, 1.2, 1.0, "h")  # 内芯高光
    rect(r, 6, 5, 4, 1, "a")             # 晶托（品红）
    r.set(5, 6, "a")
    r.set(10, 6, "a")
    for y in range(8, 14, 2):            # 杖身缠绕的记忆纹带
        r.set(7 + (y % 4 == 0), y, "A")


def it_recollector(r):
    """拾忆匕首（docs/07 §3.2）：短剑。

    与"权杖"必须区分开 —— 匕首是**横向的短刃**，而且刃口要短（不到格子的 1/2），
    否则玩家会把它误认成普通剑。
    """
    for i, (x, y) in enumerate((          # 短刃：右上 → 左下，只有 6 格
            (12, 2), (11, 3), (10, 4), (9, 5), (8, 6), (7, 7))):
        r.set(x, y, "h")
        r.set(x - 1, y + 1, "l")          # 刃的下侧反光
        r.set(x + 1, y, "m")              # 刃的上侧暗面
    rect(r, 5, 8, 3, 1, "a")              # 护手（品红）
    r.set(4, 9, "a")
    r.set(8, 7, "a")
    for i, (x, y) in enumerate(((4, 10), (3, 11), (2, 12))):   # 握柄
        r.set(x, y, "d")
        r.set(x, y - 1, "m")
    r.set(2, 13, "d")                     # 柄尾配重
    r.set(1, 12, "A")


def it_mnemonic_ink(r):
    """忆之墨水（docs/07 §4.2）：深靛蓝墨水瓶，瓶口有旋转符号。

    瓶身下半用**主色深档**表示"里面有墨水"，上半留亮色表示"玻璃是空的" ——
    这样即使缩到 16x16 也能一眼看出是"墨水瓶"而不是"药水瓶"。
    """
    rect(r, 6, 1, 4, 1, "d")              # 瓶塞
    rect(r, 7, 2, 2, 2, "m")              # 瓶颈
    rect(r, 5, 4, 6, 1, "l")              # 瓶肩
    rect(r, 4, 5, 8, 8, "m")              # 瓶身
    rect(r, 5, 8, 6, 5, "d")              # 墨液面以下（深）
    rect(r, 5, 7, 6, 1, "l")              # 液面高光
    r.set(7, 6, "a")                      # 瓶口的旋转符号（品红）
    r.set(8, 6, "A")
    r.set(7, 3, "a")
    rect(r, 4, 5, 1, 8, "l")              # 左侧玻璃反光
    rect(r, 4, 13, 8, 1, "k")             # 瓶底


def it_faded_page(r):
    """褪色的书页（docs/07 §4.3）：泛黄纸页 + 无法辨认的符号。

    "无法辨认"要画出来 —— 所以符号是**断断续续的散点**，不是连续的文字行。
    """
    rect(r, 3, 2, 10, 12, "p")            # 纸面
    rect(r, 3, 2, 10, 1, "P")             # 顶部受光
    rect(r, 3, 13, 10, 1, "q")            # 底部阴影
    rect(r, 3, 2, 1, 12, "q")             # 左边缘
    rng = random.Random(20260917)
    for y in range(4, 12, 2):             # 断续的"文字"
        x = 5
        while x < 12:
            run_ = rng.randrange(1, 4)
            if rng.random() < 0.55:       # 随机断掉 —— 表现"褪色"
                for k in range(run_):
                    if x + k < 12:
                        r.set(x + k, y, "q")
            x += run_ + rng.randrange(1, 3)
    r.set(11, 3, "a")                     # 角落残留的忆晶微光
    r.set(4, 12, "a")


def it_affinity_ring_memory(r):
    """亲和戒指（记忆学派）—— **ISS 的硬性资源约定**，不是我们自己挑的装饰。

    ⭐⭐ ISS 只有一个戒指物品 {@code irons_spellbooks:affinity_ring}，
    但它的**模型是按学派解析**的：
    {@code <学派命名空间>:item/affinity_ring_<学派路径>} —— 忆海学派就是
    {@code mnemosyne:item/affinity_ring_memory}。
    缺了它 → 客户端 {@code ModelBakery} 报
    {@code FileNotFoundException: mnemosyne:models/item/affinity_ring_memory.json}，
    玩家给戒指随机到记忆学派时看到的是**紫黑格**。
    这条是 2026-09-17 首次 runClient 才暴露出来的（服务端永远发现不了）。
    """
    disc(r, 7.5, 8.5, 5.4, "m")            # 戒圈外沿
    for y in range(16):                     # 挖掉中心 → 真的"环"
        for x in range(16):
            if ((x - 7.5) ** 2 + (y - 8.5) ** 2) ** 0.5 <= 3.1:
                r.clear(x, y)
    for (x, y) in ((3, 5), (4, 4), (11, 5), (12, 6)):   # 圈上高光
        r.set(x, y, "h")
    diamond(r, 7.5, 3.4, 1.8, 2.0, "l")    # 顶端嵌的忆晶
    r.set(7, 3, "h")
    r.set(6, 4, "a")                        # 两侧品红镶爪
    r.set(9, 4, "a")


ITEM_PAINTERS = {
    "memory_crystal": it_memory_crystal,
    "memory_staff": it_memory_staff,
    "recollector": it_recollector,
    "mnemonic_ink": it_mnemonic_ink,
    "faded_page": it_faded_page,
    "affinity_ring_memory": it_affinity_ring_memory,
    "mnemonic_robe_helmet": it_mnemonic_robe_helmet,
    "mnemonic_robe_chestplate": it_mnemonic_robe_chestplate,
    "mnemonic_robe_leggings": it_mnemonic_robe_leggings,
    "mnemonic_robe_boots": it_mnemonic_robe_boots,
}


# ===========================================================================
# 三、4 张方块贴图（16x16，必须四方连续）
# ===========================================================================
BRICK = {
    "base": (74, 70, 92), "light": (96, 91, 118), "dark": (56, 52, 72),
    "mortar": (42, 39, 54), "fleck": (83, 74, 183),
}


def gen_bricks(cracked: bool = False) -> Image.Image:
    """忆者砖：深板岩砖的错缝排列 + 靛灰配色。16 是砖宽 8 / 砖高 4 的整数倍，可四方连续。"""
    rng = random.Random(20260916 + (7 if cracked else 0))
    r = Raster(16, 16)
    for y in range(16):
        band, yin = y // 4, y % 4
        off = 4 if band % 2 else 0
        for x in range(16):
            xs = (x + off) % 16
            if yin == 3 or xs % 8 == 7:
                c = jitter(BRICK["mortar"], 6, rng)
            else:
                c = jitter(BRICK["base"], 7, rng)
                if yin == 0:
                    c = shade(jitter(BRICK["light"], 6, rng), 1.0)
                elif yin == 2:
                    c = shade(jitter(BRICK["dark"], 6, rng), 1.0)
            r.set(x, y, c)
    for _ in range(5):                 # 靛蓝矿点（把砖和学派主色绑在一起）
        r.set(rng.randrange(16), rng.randrange(16), BRICK["fleck"])
    if cracked:
        x = rng.randrange(3, 13)
        for y in range(16):
            r.set(x, y, shade(BRICK["dark"], 0.62))
            if y % 2 == 0 and 0 < x < 15:
                r.set(x + 1, y, shade(BRICK["light"], 0.9))   # 裂纹的高光边
            x = max(1, min(14, x + rng.choice((-1, 0, 0, 0, 1))))
        for _ in range(3):             # 崩角
            r.set(rng.randrange(16), rng.randrange(16), BRICK["mortar"])
    return r.image()


def gen_stele() -> Image.Image:
    """忆碑：石面 + 中央凿刻的靛蓝符文（未读时发亮，见 docs/08 §六）。"""
    rng = random.Random(20260918)
    r = Raster(16, 16)
    for y in range(16):
        for x in range(16):
            c = jitter((136, 135, 128), 8, rng)
            if rng.random() < 0.10:
                c = shade(c, 1.10)
            elif rng.random() < 0.10:
                c = shade(c, 0.90)
            r.set(x, y, c)
    # 符文遮罩：环形 + 竖条 + 两点
    mask = set()
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 7.5, y + 0.5 - 7.5)
            if abs(d - 4.8) <= 0.6:
                mask.add((x, y))
    for y in range(3, 12):
        mask.add((7, y))
        mask.add((8, y))
    for p in ((4, 5), (11, 5)):
        mask.add(p)
    halo, glyph, deep = (175, 169, 236), (83, 74, 183), (38, 33, 92)
    for y in range(16):
        for x in range(16):
            if (x, y) in mask:
                r.set(x, y, glyph)
                continue
            near = any((x + dx, y + dy) in mask for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if near:
                r.set(x, y, halo)
    for y in range(16):                # 凿刻的暗部：符文内侧
        for x in range(16):
            if (x, y) in mask and not any((x + dx, y + dy) in mask
                                          for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                r.set(x, y, deep)
    return r.image()


def gen_crystal_cluster() -> Image.Image:
    """忆晶簇：岩基上长出多根忆晶（docs/08 §五）。"""
    rng = random.Random(20260919)
    r = Raster(16, 16)
    rock, rock_l, rock_d = (58, 55, 68), (76, 72, 88), (42, 40, 52)
    c_m, c_l, c_b, c_d = (83, 74, 183), (175, 169, 236), (230, 227, 250), (38, 33, 92)
    crystals = [(3, 5, 7), (7, 9, 2), (10, 12, 5), (1, 2, 11), (13, 14, 10)]
    for x0, x1, top in crystals:
        for y in range(top, 16):
            for x in range(x0, x1 + 1):
                if x == x0:
                    c = c_l
                elif x == x1:
                    c = c_d
                else:
                    c = c_m
                r.set(x, y, c)
        for x in range(x0, x1 + 1):
            r.set(x, top, c_b)
    for y in range(11, 16):            # 岩基（盖住晶体根部）
        for x in range(16):
            if y == 11 and rng.random() < 0.45:
                continue
            c = jitter(rock, 7, rng)
            if rng.random() < 0.12:
                c = rock_l
            elif rng.random() < 0.12:
                c = rock_d
            r.set(x, y, c)
    return r.image()


# ===========================================================================
# 四、2 张护甲穿戴图层（64x32，原版护甲 UV）
# ===========================================================================
# 原版护甲图层布局（公开规范，与原版 diamond_layer_*.png 一致）：
#   head / body / 右臂 / 右腿 的六个面；左臂与左腿由渲染器镜像右臂右腿得到。
ARMOR_REGIONS = {
    "head": {"top": (8, 0, 8, 8), "bottom": (16, 0, 8, 8), "right": (0, 8, 8, 8),
             "front": (8, 8, 8, 8), "left": (16, 8, 8, 8), "back": (24, 8, 8, 8)},
    "body": {"top": (20, 16, 8, 4), "bottom": (28, 16, 8, 4), "right": (16, 20, 4, 12),
             "front": (20, 20, 8, 12), "left": (28, 20, 4, 12), "back": (32, 20, 8, 12)},
    "rarm": {"top": (44, 16, 4, 4), "bottom": (48, 16, 4, 4), "right": (40, 20, 4, 12),
             "front": (44, 20, 4, 12), "left": (48, 20, 4, 12), "back": (52, 20, 4, 12)},
    "rleg": {"top": (4, 16, 4, 4), "bottom": (8, 16, 4, 4), "right": (0, 20, 4, 12),
             "front": (4, 20, 4, 12), "left": (8, 20, 4, 12), "back": (12, 20, 4, 12)},
}
FACE_SHADE = {"front": 1.00, "back": 0.92, "right": 0.86, "left": 1.06, "top": 1.14, "bottom": 0.76}

IND = (83, 74, 183)
IND_D = (38, 33, 92)
MAG = (212, 83, 126)
MAG_L = (237, 147, 177)


def _fill_face(r: Raster, rect_box, color, face, rng, weave=7):
    """铺一个面：按朝向做明暗 + 织纹噪声。"""
    x, y, w, h = rect_box
    base = shade(color, FACE_SHADE[face])
    for yy in range(y, y + h):
        for xx in range(x, x + w):
            c = base
            if (xx + yy) % 3 == 0:
                c = shade(base, 0.96)
            elif (xx * 2 + yy) % 5 == 0:
                c = shade(base, 1.05)
            r.set(xx, yy, jitter(c, weave, rng))


def _fill_band(r: Raster, rect_box, color, face, rows):
    """在某个面上铺若干行（rows 是相对行号集合），用于镶边/靴口。"""
    x, y, w, h = rect_box
    base = shade(color, FACE_SHADE[face])
    for yy in rows:
        if 0 <= yy < h:
            for xx in range(x, x + w):
                r.set(xx, y + yy, base)


def gen_armor_layer1() -> Image.Image:
    """头盔 + 胸甲 + 长靴（layer_1）。"""
    rng = random.Random(20260920)
    r = Raster(64, 32)
    for face, box in ARMOR_REGIONS["head"].items():
        _fill_face(r, box, IND, face, rng)
    _fill_band(r, ARMOR_REGIONS["head"]["front"], IND_D, "front", {2, 3, 4, 5})   # 面部开口
    _fill_band(r, ARMOR_REGIONS["head"]["front"], MAG, "front", {6, 7})           # 帽檐
    _fill_band(r, ARMOR_REGIONS["head"]["top"], MAG, "top", {0, 1})
    for face, box in ARMOR_REGIONS["body"].items():
        _fill_face(r, box, IND, face, rng)
        _fill_band(r, box, MAG, face, {0, 1})                                     # 领口
    _fill_band(r, ARMOR_REGIONS["body"]["front"], MAG_L, "front", {0})            # 领口高光
    bx, by, _, _ = ARMOR_REGIONS["body"]["front"]
    for yy in (2, 3):                                                             # 忆晶胸针
        for xx in (3, 4):
            r.set(bx + xx, by + yy, (230, 227, 250))
    for face, box in ARMOR_REGIONS["rarm"].items():
        _fill_face(r, box, IND, face, rng)
        _fill_band(r, box, MAG, face, {10, 11})                                   # 袖口
    for face, box in ARMOR_REGIONS["rleg"].items():
        _fill_face(r, box, shade(IND, 0.72), face, rng)
        _fill_band(r, box, MAG, face, {8})                                        # 靴口
    return r.image()


def gen_armor_layer2() -> Image.Image:
    """护腿（layer_2）：腰 + 双腿。"""
    rng = random.Random(20260921)
    r = Raster(64, 32)
    for face, box in ARMOR_REGIONS["body"].items():
        _fill_face(r, box, IND, face, rng)
        _fill_band(r, box, MAG, face, {0, 1, 2})                                  # 腰带
    for face, box in ARMOR_REGIONS["rleg"].items():
        _fill_face(r, box, IND, face, rng)
        _fill_band(r, box, MAG, face, {10, 11})                                   # 裤脚
    return r.image()


# ===========================================================================
# 五、产出与校验
# ===========================================================================
# ===========================================================================
# 四、忆魇实体贴图（64x64）
# ===========================================================================
#
# 用**原版 `HumanoidModel` 的 64x64 布局**（`ModelLayers.ZOMBIE` 那套几何）——
# 忆魇的模型类直接复用原版几何，不自己写 MeshDefinition，
# 这样"贴图布局"和"模型几何"由同一份原版代码保证一致。
#
# 布局（`ModelPart.Cube` 的盒子 UV 规则：宽 = 2d+2w，高 = d+h）：
#
#   头部  texOffs(0,0)   8x8x8   → 区域 (0,0)  32x16
#   帽子  texOffs(32,0)  8x8x8   → 区域 (32,0) 32x16   ← 整块留空
#   右腿  texOffs(0,16)  4x12x4  → 区域 (0,16)  16x16
#   身体  texOffs(16,16) 8x12x4  → 区域 (16,16) 24x16
#   右臂  texOffs(40,16) 4x12x4  → 区域 (40,16) 16x16
#   左腿  texOffs(16,48) 4x12x4  → 区域 (16,48) 16x16
#   左臂  texOffs(32,48) 4x12x4  → 区域 (32,48) 16x16
#
# ⭐ 只做**区域级**填色，不逐面画细节：vanilla 的左右肢有一处走 `mirror()`
# 复用同一块 UV 区域，逐面画会在"哪块区域被采样"上翻车。区域级填色对 UV 歧义免疫。
# 正面（north，模型朝向的那一面）的局部坐标是 (d, d) 起、尺寸 w×h。

WRAITH_ROBE = (83, 74, 183, 255)      # 靛蓝袍身（不透明 —— MC 不支持半透明实体贴图）
WRAITH_ROBE_D = (45, 40, 110, 255)    # 袍影（暗部 / 下摆）
WRAITH_HOOD = (38, 33, 92, 255)       # 兜帽
WRAITH_HOOD_L = (58, 50, 122, 255)    # 兜帽受光面（顶面）
WRAITH_FACE = (22, 18, 52, 255)       # 兜帽内的脸（更暗，衬眼睛）
WRAITH_TRIM = (212, 83, 126, 255)     # 品红滚边
WRAITH_EYE = (255, 210, 226, 255)     # 发光眼睛
WRAITH_EYE_GLOW = (237, 147, 177, 255)  # 眼下拖影
WRAITH_VOID = (0, 0, 0, 0)


def gen_wraith() -> Image.Image:
    """忆魇（Memory Wraith）实体贴图，64x64。

    <p><b>⭐⭐ 2026-09-18 重写 —— 上一版是错的。</b>
    上一版把 UV 区域**按记忆写**，两处硬伤：
    <ol>
      <li>把 {@code (0,8)} 当成了"脸"。实测 {@code HumanoidModel.createMesh} 的
          {@code head = texOffs(0, 0)}，8×8×8 盒子的展开是
          top(8,0) / bottom(16,0) / east(0,8) / <b>front(8,8)</b> / west(16,8) / back(24,8)
          —— {@code (0,8)} 是<b>右侧面</b>。所以眼睛被画在了太阳穴上。</li>
      <li>左右臂/腿用了 1.8 玩家皮肤的独立区域 {@code (32,48)} / {@code (16,48)}。
          但 {@code ModelLayers.ZOMBIE = LayerDefinition.create(HumanoidModel.createMesh(...))}，
          而 {@code HumanoidModel} 里 {@code left_arm = texOffs(40,16).mirror()}、
          {@code left_leg = texOffs(0,16).mirror()} —— <b>左右共用同一区域</b>，
          {@code (32,48)} / {@code (16,48)} 在这个模型上根本不会被采样。</li>
    </ol>
    另外上一版的袍身 alpha 是 200（半透明），MC 期望 255 —— 渲染出来是一堆洞。
    本版全部不透明。

    <p><b>UV 权威来源</b>：{@code net/minecraft/client/model/HumanoidModel.java}
    的 {@code createMesh}（用 {@code tools/read_mc_source.py HumanoidModel} 实测）。
    盒子展开规则：给定 {@code texOffs(u,v)} 与 (w,h,d)：
    {@code top(u+d,v) / bottom(u+d+w,v) / east(u,v+d) / front(u+d,v+d) /
    west(u+d+w,v+d) / back(u+d+w+d,v+d)}。

    <p><b>帽子层（hat）刻意留空</b>：hat 是 head 外扩 0.5px 的完整副本，
    它的 front 面盖在脸的正前方。填色的话会把眼睛糊掉。
    """
    r = Raster(64, 64)

    def box(u, v, w, h, d, color):
        """按 MC 盒子展开规则填一个立方体的 6 个面。"""
        for (x, y, bw, bh) in ((u + d, v, w, d),                # top
                               (u + d + w, v, w, d),            # bottom
                               (u, v + d, d, h),                # east
                               (u + d, v + d, w, h),            # front
                               (u + d + w, v + d, d, h),        # west
                               (u + d + w + d, v + d, w, h)):   # back
            for yy in range(y, y + bh):
                for xx in range(x, x + bw):
                    r.set(xx, yy, color)

    # ---- head (texOffs 0,0 · 8x8x8) ----
    box(0, 0, 8, 8, 8, WRAITH_HOOD)
    for x in range(8, 16):                    # 顶面稍亮，做出兜帽的体积感
        for y in range(0, 8):
            r.set(x, y, WRAITH_HOOD_L)
    # 脸（front = (8,8) 8x8）：再暗一档，让眼睛有对比
    for y in range(8, 16):
        for x in range(8, 16):
            r.set(x, y, WRAITH_FACE)
    # 眼睛：脸面局部 (2,4) 与 (5,4) —— 两眼间距 3px，符合原版生物的比例
    for ex in (10, 13):
        r.set(ex, 12, WRAITH_EYE)
        r.set(ex, 13, WRAITH_EYE_GLOW)         # 眼下拖影

    # ---- hat (texOffs 32,0 · 8x8x8) 留空（见 docstring） ----

    # ---- body (texOffs 16,16 · 8x12x4) ----
    box(16, 16, 8, 12, 4, WRAITH_ROBE)
    for x in range(16, 20):                    # east 侧面压暗
        for y in range(20, 32):
            r.set(x, y, WRAITH_ROBE_D)
    for x in range(28, 32):                    # west 侧面压暗
        for y in range(20, 32):
            r.set(x, y, WRAITH_ROBE_D)
    # 下摆滚边：四面最后 2 行
    for (x0, x1) in ((16, 20), (20, 28), (28, 32), (32, 40)):
        for y in (30, 31):
            for x in range(x0, x1):
                r.set(x, y, WRAITH_TRIM)
    # 胸口忆晶胸针：front = (20,20) 8x12，局部 (3,4)-(4,5)
    for (x, y) in ((23, 24), (24, 24), (23, 25), (24, 25)):
        r.set(x, y, WRAITH_EYE)

    # ---- 双臂（左右共用 texOffs 40,16 · 4x12x4） ----
    box(40, 16, 4, 12, 4, WRAITH_ROBE)
    for y in (30, 31):                         # 袖口
        for x in range(40, 56):
            r.set(x, y, WRAITH_TRIM)

    # ---- 双腿（左右共用 texOffs 0,16 · 4x12x4） ----
    #     模型里被藏掉了，仍然填色 —— 万一将来不藏腿，也不至于出现紫黑格。
    box(0, 16, 4, 12, 4, WRAITH_ROBE_D)

    return r.image()


def backdrop_under(r: Raster):
    """给法术图标铺一层**不透明**的学派色暗底（仿 ISS 官方图标的做法）。

    <p><b>为什么必须做这一步</b>（2026-09-17 实测）：
    实测 ISS 官方 **116 张法术图标全部是 256/256 完全不透明**，而我们的图标只有
    56~196/256 —— 图形之外全是透明。在法术轮盘 / 快捷栏里看起来就是"飘着的碎线"，
    对比度极差、辨识度低，也就是用户说的"图标很丑"。

    ISS 的做法是：**学派色调的渐变底板 + 亮色图形**。
    这里照抄这个思路，底板用"中心稍亮、四角压暗"的径向渐变：
    中心约 {@code shade(dark, 0.75) + main×0.14}，四角约 {@code shade(dark, 0.45)}。
    压得比 ISS 更暗，是因为我们的图形本身用亮色（l/h 档），
    暗底 + 亮图形才是最大对比度。

    ⚠️ <b>必须在 {@link outline} 之后调用</b>。{@code outline()} 的判据是
    "不透明像素的透明邻域"，如果先铺底、整张图都变成不透明，
    描边一步会**一个像素都不画**（静默失效，图标失去统一笔触）。
    所以本函数只填**仍然透明**的像素，把底板垫在图形下面。
    """
    main = hex_to_rgba(r.scheme["m"])[:3]
    dark = hex_to_rgba(r.scheme["d"])[:3]
    cx, cy = (r.w - 1) / 2.0, (r.h - 1) / 2.0
    max_d = math.hypot(cx, cy)
    for y in range(r.h):
        for x in range(r.w):
            if r.opaque(x, y):
                continue
            t = math.hypot(x - cx, y - cy) / max_d      # 0 = 中心，1 = 四角
            f = 1.0 - t * t                             # 中心亮、四角暗
            base = shade(dark, 0.45 + 0.30 * f)
            glow = tuple(round(main[i] * 0.14 * f) for i in range(3))
            r.data[y][x] = (min(255, base[0] + glow[0]),
                            min(255, base[1] + glow[1]),
                            min(255, base[2] + glow[2]), 255)


def gen_memory_arrow() -> Image.Image:
    """忆矢投射物贴图（64x64，供 entity/MemoryArrowEntity 的公告板渲染器用）。

    <p>设计：中心一枚亮核 + 品红内晕 + 靛蓝外晕，边缘**羽化到全透明**。
    羽化是必要的 —— 渲染器用 {@code entityTranslucent}，硬边会看到一个方块；
    而且它**不参与** {@link backdrop_under}（那是法术图标专用，投射物要透明背景）。
    """
    size = 64
    img = Image.new("RGBA", (size, size), TRANSPARENT)
    px = img.load()
    core = hex_to_rgba("#E6E3FA")[:3]      # 近白的靛蓝高光
    mid = hex_to_rgba(MAGENTA_M)[:3]       # 品红内晕
    outer = hex_to_rgba(INDIGO_M)[:3]      # 靛蓝外晕
    c = (size - 1) / 2.0
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - c, y - c) / c        # 0 = 中心，1 = 边缘
            if d >= 1.0:
                continue
            if d < 0.22:
                col = core
            elif d < 0.46:
                k = (d - 0.22) / 0.24
                col = tuple(round(core[i] * (1 - k) + mid[i] * k) for i in range(3))
            else:
                k = (d - 0.46) / 0.54
                col = tuple(round(mid[i] * (1 - k) + outer[i] * k) for i in range(3))
            # 羽化：外圈 alpha 平滑衰减（平方衰减比线性更像"辉光"）
            alpha = round(255 * (1.0 - d * d) ** 0.65)
            px[x, y] = (col[0], col[1], col[2], max(0, min(255, alpha)))
    return img


def build_all() -> dict[str, Image.Image]:
    """返回 {相对 TEX 的路径: 图像}。"""
    out: dict[str, Image.Image] = {}
    for sid in SPELL_IDS:
        r = Raster(16, 16, SCHEMES[SPELL_SCHEME[sid]])
        SPELL_PAINTERS[sid](r)
        outline(r)
        # ⚠️ 顺序不能颠倒：先描边、再铺底。反了的话 outline() 会静默失效（见 backdrop_under 注释）。
        backdrop_under(r)
        out[f"gui/spell_icons/{sid}.png"] = r.image()
    for name, fn in ITEM_PAINTERS.items():
        r = Raster(16, 16, SCHEMES["indigo"])
        fn(r)
        outline(r)
        out[f"item/{name}.png"] = r.image()
    out["block/mnemonic_bricks.png"] = gen_bricks(False)
    out["block/mnemonic_bricks_cracked.png"] = gen_bricks(True)
    out["block/memory_stele.png"] = gen_stele()
    out["block/memory_crystal_cluster.png"] = gen_crystal_cluster()
    out["models/armor/mnemonic_layer_1.png"] = gen_armor_layer1()
    out["models/armor/mnemonic_layer_2.png"] = gen_armor_layer2()
    out["entity/memory_wraith.png"] = gen_wraith()
    out["entity/memory_arrow.png"] = gen_memory_arrow()
    return out


EXPECTED_SIZE = {
    "gui/spell_icons": (16, 16),
    "item": (16, 16),
    "block": (16, 16),
    "models/armor": (64, 32),
    "entity": (64, 64),
}


def do_generate() -> int:
    assets = build_all()
    for rel, img in assets.items():
        path = os.path.join(TEX, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
        print(f"  写出 {rel}  ({img.size[0]}x{img.size[1]})")
    print(f"\n共 {len(assets)} 张。")
    return 0


def do_check() -> int:
    """逐条验收：存在 / 尺寸 / 非全透明 / 文件名与 spellId 一致。"""
    assets = build_all()
    bad = []
    print(f"{'路径':<44}{'尺寸':<12}{'不透明像素':<12}{'结果'}")
    print("-" * 80)
    for rel, img in sorted(assets.items()):
        path = os.path.join(TEX, rel.replace("/", os.sep))
        if not os.path.isfile(path):
            print(f"{rel:<44}{'-':<12}{'-':<12}缺失")
            bad.append(rel)
            continue
        disk = Image.open(path).convert("RGBA")
        n = sum(1 for v in disk.getchannel("A").tobytes() if v != 0)
        want = EXPECTED_SIZE[rel.split("/")[0] if rel.split("/")[0] in EXPECTED_SIZE
                             else "/".join(rel.split("/")[:2])]
        ok = disk.size == want and n > 0 and disk.size == img.size
        print(f"{rel:<44}{str(disk.size):<12}{n:<12}{'OK' if ok else 'FAIL'}")
        if not ok:
            bad.append(rel)

    # 文件名集合 == spellId 集合（逐字）
    icon_dir = os.path.join(TEX, "gui", "spell_icons")
    on_disk = sorted(f[:-4] for f in os.listdir(icon_dir)) if os.path.isdir(icon_dir) else []
    missing = sorted(set(SPELL_IDS) - set(on_disk))
    extra = sorted(set(on_disk) - set(SPELL_IDS))
    print("-" * 80)
    print(f"法术图标：期望 {len(SPELL_IDS)} 张，实际 {len(on_disk)} 张")
    if missing:
        print(f"  缺失：{missing}")
        bad.append("spell_icons:missing")
    if extra:
        print(f"  多余：{extra}")
        bad.append("spell_icons:extra")
    if not missing and not extra:
        print(f"  文件名与 {len(SPELL_IDS)} 个 spellId 逐字一致 ✅")

    print("-" * 80)
    if bad:
        print(f"校验失败：{len(bad)} 项 —— {bad}")
        return 1
    print(f"校验通过：{len(assets)} 张全部存在、尺寸正确、无全透明。")
    return 0


def do_contact_sheet(path: str, scale: int = 6) -> int:
    """把所有贴图拼成一张带标签的预览图，供肉眼检查。"""
    assets = build_all()
    groups = [
        ("法术图标 18", [f"gui/spell_icons/{s}.png" for s in SPELL_IDS], 16),
        ("物品 " + str(len(ITEM_PAINTERS)), [f"item/{n}.png" for n in ITEM_PAINTERS], 16),
        ("方块 4", [f"block/{n}.png" for n in
                    ("mnemonic_bricks", "mnemonic_bricks_cracked",
                     "memory_stele", "memory_crystal_cluster")], 16),
        ("护甲图层 2", ["models/armor/mnemonic_layer_1.png",
                        "models/armor/mnemonic_layer_2.png"], 64),
    ]
    cell = 16 * scale + 10
    per_row = 6
    rows = sum((len(items) + per_row - 1) // per_row + 1 for _, items, _ in groups)
    sheet = Image.new("RGBA", (per_row * cell + 20, rows * (cell + 18) + 20), (40, 38, 48, 255))
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.load_default()
    except Exception:
        font = None
    y = 14
    for title, items, _sz in groups:
        draw.text((14, y), title, fill=(240, 238, 250), font=font)
        y += 16
        for i, rel in enumerate(items):
            cx = 14 + (i % per_row) * cell
            cy = y + (i // per_row) * (cell + 18)
            img = assets[rel].resize((assets[rel].size[0] * scale, assets[rel].size[1] * scale),
                                     Image.NEAREST)
            bg = Image.new("RGBA", img.size, (58, 56, 70, 255))
            bg.alpha_composite(img)
            sheet.alpha_composite(bg, (cx, cy))
            label = rel.split("/")[-1][:-4]
            draw.text((cx + 2, cy + img.size[1] + 2), label[:22], fill=(215, 212, 230), font=font)
        rows_used = (len(items) + per_row - 1) // per_row
        y += rows_used * (cell + 18) + 6
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    sheet.convert("RGB").save(path)
    print(f"拼版预览已写出 {path}  ({sheet.size[0]}x{sheet.size[1]})")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="忆海美术资源生成器（WS-H2）")
    ap.add_argument("--all", action="store_true", help="生成全部贴图")
    ap.add_argument("--check", action="store_true", help="逐张校验（尺寸/非空/文件名）")
    ap.add_argument("--contact-sheet", default=None, help="输出拼版预览图路径")
    ap.add_argument("--scale", type=int, default=6, help="拼版预览放大倍数（默认 6）")
    ap.add_argument("--list", action="store_true", help="列出将要生成的 30 个路径")
    args = ap.parse_args()

    if args.list:
        for rel in sorted(build_all()):
            print(rel)
        return 0
    if args.check:
        return do_check()
    if args.contact_sheet:
        return do_contact_sheet(args.contact_sheet, args.scale)
    if args.all:
        return do_generate()
    ap.print_help()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
