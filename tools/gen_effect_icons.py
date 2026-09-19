"""生成忆海的状态效果图标（assets/mnemosyne/textures/mob_effect/*.png）。

⭐ 为什么必须有这个脚本：状态效果的图标是**约定路径**
`assets/<namespace>/textures/mob_effect/<effect_id>.png`，
**缺文件不会报错** —— 游戏里只是显示成一个缺失贴图的方块/空白。
2026-09-18 实测发现我们注册的 10 个效果**一个图标都没有**，
而没有任何检查会发现这件事（`check_resources.py` 当时不查 mob_effect）。

画法：先在 4 倍尺寸（72×72）上用几何图元画，再 LANCZOS 缩到 18×18 ——
这样能得到平滑的边缘，而不是 18px 硬画的锯齿。

配色沿用 docs/09：靛蓝 #534AB7 / 品红 #D4537E / 灰紫 #5C5480 / 深靛 #3A2E8C / 浅靛 #7F77DD。
"""
import os

from PIL import Image, ImageDraw

SS = 4                      # 超采样倍率
SIZE = 18                   # MC 状态效果图标的标准尺寸
BIG = SIZE * SS

OUT = os.path.join("src", "main", "resources", "assets", "mnemosyne",
                   "textures", "mob_effect")

INDIGO = (0x53, 0x4A, 0xB7, 255)
MAGENTA = (0xD4, 0x53, 0x7E, 255)
GREY_PURPLE = (0x5C, 0x54, 0x80, 255)
DEEP_INDIGO = (0x3A, 0x2E, 0x8C, 255)
LIGHT_INDIGO = (0x7F, 0x77, 0xDD, 255)


def new_canvas():
    img = Image.new("RGBA", (BIG, BIG), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def save(img, name):
    os.makedirs(OUT, exist_ok=True)
    out = img.resize((SIZE, SIZE), Image.LANCZOS)
    path = os.path.join(OUT, name + ".png")
    out.save(path)
    print("  " + path)


# ---------------------------------------------------------------------------
# 每个图标
# ---------------------------------------------------------------------------

def icon_forget():
    """遗忘：一页被撕掉一角的纸。"""
    img, d = new_canvas()
    d.rounded_rectangle([14, 8, 58, 64], radius=5, fill=INDIGO)
    # 右上角撕口（用透明三角"咬"掉一块）
    d.polygon([(40, 8), (58, 8), (58, 28)], fill=(0, 0, 0, 0))
    # 纸上的字迹
    for y in (24, 34, 44):
        d.rectangle([22, y, 48, y + 3], fill=(0xEE, 0xED, 0xFE, 220))
    save(img, "forget")


def icon_amnesia():
    """失忆：实心圆一半在褪色成虚线。"""
    img, d = new_canvas()
    d.ellipse([12, 12, 60, 60], fill=DEEP_INDIGO)
    # 右半边打碎成小方块（"记忆碎裂"）
    d.pieslice([12, 12, 60, 60], -90, 90, fill=(0, 0, 0, 0))
    for i, y in enumerate(range(16, 60, 11)):
        w = 10 - i
        if w > 0:
            d.rectangle([40, y, 40 + w, y + 7], fill=DEEP_INDIGO)
    save(img, "amnesia")


def icon_cognitive_overload():
    """认知过载：三层叠放的碎片，越往上越亮。"""
    img, d = new_canvas()
    d.polygon([(36, 52), (58, 66), (36, 66), (14, 66)], fill=(0xA8, 0x3E, 0x63, 255))
    d.polygon([(36, 32), (56, 44), (36, 52), (16, 44)], fill=MAGENTA)
    d.polygon([(36, 10), (54, 22), (36, 30), (18, 22)], fill=(0xF0, 0x9C, 0xB8, 255))
    save(img, "cognitive_overload")


def icon_sluggish():
    """迟滞：一支下坠的箭头。"""
    img, d = new_canvas()
    d.rectangle([31, 10, 41, 40], fill=GREY_PURPLE)
    d.polygon([(36, 66), (14, 36), (58, 36)], fill=GREY_PURPLE)
    # 两侧拖尾（"变慢"）
    d.rectangle([14, 14, 24, 20], fill=(0x8C, 0x84, 0xAE, 200))
    d.rectangle([48, 14, 58, 20], fill=(0x8C, 0x84, 0xAE, 200))
    save(img, "sluggish")


def icon_engram_burden():
    """忆格负担：一颗忆晶下面挂着一个配重。"""
    img, d = new_canvas()
    d.polygon([(36, 6), (56, 26), (36, 46), (16, 26)], fill=GREY_PURPLE)
    d.polygon([(36, 14), (48, 26), (36, 38), (24, 26)], fill=(0x3A, 0x34, 0x52, 255))
    # 配重
    d.rectangle([20, 52, 52, 66], fill=(0x8C, 0x84, 0xAE, 255))
    d.rectangle([30, 48, 42, 54], fill=(0x8C, 0x84, 0xAE, 255))
    save(img, "engram_burden")


def icon_temporal_engram():
    """临时忆格：虚线边框的忆晶 + 一根时针。"""
    img, d = new_canvas()
    # 虚线菱形边框
    for i in range(0, 8):
        t0, t1 = i / 8, (i + 0.5) / 8
        pts = []
        corners = [(36, 6), (66, 36), (36, 66), (6, 36)]
        for seg in range(4):
            x0, y0 = corners[seg]
            x1, y1 = corners[(seg + 1) % 4]
            for t in (t0, t1):
                if seg / 4 <= t < (seg + 1) / 4:
                    lt = (t - seg / 4) * 4
                    pts.append((x0 + (x1 - x0) * lt, y0 + (y1 - y0) * lt))
        if len(pts) == 2:
            d.line([pts[0], pts[1]], fill=MAGENTA, width=5)
    # 时针
    d.line([(36, 36), (36, 18)], fill=(0xF0, 0x9C, 0xB8, 255), width=4)
    d.line([(36, 36), (50, 42)], fill=(0xF0, 0x9C, 0xB8, 255), width=4)
    save(img, "temporal_engram")


def icon_recollection_ward():
    """走马灯守护：一面盾。"""
    img, d = new_canvas()
    d.polygon([(36, 6), (62, 16), (62, 38), (36, 66), (10, 38), (10, 16)], fill=INDIGO)
    d.polygon([(36, 15), (54, 22), (54, 37), (36, 56), (18, 37), (18, 22)],
              fill=(0xEE, 0xED, 0xFE, 235))
    d.polygon([(36, 24), (46, 29), (46, 38), (36, 48), (26, 38), (26, 29)], fill=INDIGO)
    save(img, "recollection_ward")



def icon_memory_blank():
    """记忆空白：一个被划掉的圆。"""
    img, d = new_canvas()
    d.ellipse([10, 10, 62, 62], outline=MAGENTA, width=7)
    d.line([(16, 56), (56, 16)], fill=(0xF0, 0x9C, 0xB8, 255), width=7)
    save(img, "memory_blank")


ICONS = [
    icon_forget, icon_amnesia, icon_cognitive_overload, icon_sluggish,
    icon_engram_burden, icon_temporal_engram, icon_recollection_ward,
    icon_spell_listen, icon_memory_blank,
]


def main():
    print("生成状态效果图标（18×18，{0}× 超采样）".format(SS))
    for fn in ICONS:
        fn()
    print("完成：{0} 个".format(len(ICONS)))


if __name__ == "__main__":
    main()
