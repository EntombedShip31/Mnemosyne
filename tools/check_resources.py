#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
忆海 Mnemosyne —— 跨工作流资源一致性校验器
==========================================

【为什么需要这个脚本】

项目有 5~14 条并行线各自写自己的文件（`docs/tech/10` 的文件所有权表）。
**"各自能编译"合起来跑不通是并行开发的常态**，而这个模组的失败模式里有一大类是
**静默失败** —— 不报错、不崩溃，只是"东西不见了"：

| 症状 | 真实原因 | 谁最容易踩 |
|---|---|---|
| 物品/方块显示**紫黑格** | `models/item/*.json` 的 `layer0` 指向不存在的贴图 | WS-A 写模型、WS-H 出贴图，两边对不上 |
| 法术**没音效 / 名字是原始 key** | 法术 id 在 stub / `sounds.json` / `lang` 三处不一致 | WS-A / WS-D |
| **存档直接加载不了** | `worldgen/**` JSON 引用了未注册的方块/物品 id → `RegistryDataLoader` 抛异常 | WS-G1 |
| 箱子**永远是空的** | `loot_tables/**` 引用未注册物品 → 只 `warn`，表变空 | WS-G1 |
| 中文界面**夹英文** | `zh_cn` 与 `en_us` 键集不一致 | WS-A |

这些在 `./gradlew build` 里**一个都不会报**。本脚本把它们变成一次静态检查。

实测依据（写在 `MEMORY.md` 里）：
- `worldgen` 注册表解析失败 → `IllegalStateException("Failed to load registries due to above errors")`，
  **存档加载不了**（`RegistryDataLoader.load()` L75-77）；
- `loot_tables` 解析失败只 `warn` 并把该表变**空** → 静默失效。

检查项：
    1  模型引用的贴图是否存在（缺贴图 → 紫黑格）
    2  法术 id 三处一致（stub / `sounds.json` / `lang`）+ 图标存在
    3  `lang` 键集一致性（en_us vs zh_cn）
    4  `sounds.json` schema + 引用的 ISS 音频是否存在
    5a `worldgen` / `loot_tables` / `tags` 引用的 id 是否真实存在（**注册表**判据，严格）
    5b 能力 / 特性 id 是否与 Java 常量表一致（**自定义数据**判据）
    6  孤儿贴图（有贴图但没有任何模型引用）

⚠️ 5a 与 5b 分开是有原因的：`data/` 下两类 JSON 的**判据完全不同**。
`data/mnemosyne/mnemosyne/oblivion/*.json` 由我们自己的重载监听器解析，
里面的 `mnemosyne:melee_attack` 是能力名而不是注册表对象 ——
用 5a 的判据去查它会得到 129 条**误报**（2026-09-16 实际发生过）。
校验器最危险的状态不是漏报，而是噪音多到没人看。

用法：
    python tools/check_resources.py            # 全部检查
    python tools/check_resources.py --verbose  # 打印每个被检查的对象
退出码：0 = 全通过；1 = 有 FAIL。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "mnemosyne")
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "mnemosyne")
JAVA = os.path.join(ROOT, "src", "main", "java", "com", "etbs31", "mnemosyne")

MODID = "mnemosyne"


def iss_root() -> str | None:
    """定位 ISS 的解包副本。

    ⭐ 2026-09-18：原先只在**仓库根目录**找 `[` 开头的目录。后来参考模组被统一
    收进了 `可以参考的模组/` 子目录，这里就返回 None —— 而它的失败方式是
    **静默降级成一条 warn、41 项音频检查全部消失**（检查项数从 350 掉到 309，
    不报错、不 FAIL，只是"查得少了"）。

    ⇒ 这正是「校验器必须能发现自己的盲区」那条规则的又一实例：
    检查**数量变少**本身就是一种失败，但没人会去数检查项总数。

    现在：根目录 + 一级子目录都找，并且优先匹配名字含 `irons_spellbooks` 的那个。
    """
    def find_in(parent: str) -> list[str]:
        try:
            entries = os.listdir(parent)
        except OSError:
            return []
        out = []
        for d in entries:
            full = os.path.join(parent, d)
            if d.startswith("[") and os.path.isdir(full):
                out.append(full)
        return out

    # 扫描时跳过的目录：它们只可能拖慢速度，不可能放参考模组
    SKIP = {"build", "run", "run-server", "run-data", "src", "docs",
            "tools", "gradle", ".gradle", ".workbuddy-ai", ".git"}

    cands = find_in(ROOT)
    try:
        subs = sorted(os.listdir(ROOT))
    except OSError:
        subs = []
    for d in subs:
        sub = os.path.join(ROOT, d)
        if d in SKIP or d.startswith(".") or not os.path.isdir(sub):
            continue
        cands += find_in(sub)

    if not cands:
        return None
    for c in cands:
        if "irons_spellbooks" in os.path.basename(c).lower():
            return c
    return cands[0]


# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------
class Report:
    """三级严重度。

    `fail`     —— 真缺陷。构建通过但游戏里一定坏，退出码 1。
    `pending`  —— **已知的、排期中的欠账**。比如结构 NBT 还没导出（WS-G2 卡在 runClient）。
                  刻意**不算 fail**：如果校验器永远红着，"红了是正常的"就会变成团队共识，
                  那这个校验器就死了 —— 一个校验器最危险的状态不是漏报，而是噪音多到没人看。
    `warn`     —— 提示，可能是有意为之（比如还没写模型的贴图）。
    """

    def __init__(self, verbose: bool = False):
        self.fails: list[str] = []
        self.warns: list[str] = []
        self.pendings: list[str] = []
        # ⚠️ 字段名不能叫 `uncovered` —— 会遮蔽下面的同名方法（list 不可调用）。
        self.uncovered_items: list[str] = []
        self.verbose = verbose
        self.checked = 0
        self._mark = 0

    def ok(self, msg: str):
        self.checked += 1
        if self.verbose:
            print(f"    ok   {msg}")

    def fail(self, section: str, msg: str):
        self.fails.append(f"[{section}] {msg}")
        print(f"  ✗ FAIL {msg}")

    def pending(self, section: str, msg: str):
        self.pendings.append(f"[{section}] {msg}")

    def warn(self, section: str, msg: str):
        self.warns.append(f"[{section}] {msg}")
        print(f"  ! warn {msg}")

    def uncovered(self, section: str, msg: str):
        """
        **本该检查、但这次没检查成**的项。

        ⭐⭐ 与 `warn` 的区别：`warn` 是"查过了，结果提示你一声"；
        `uncovered` 是**根本没查** —— 它不会进 `checked`，所以检查项总数会**变少**。
        本文件头 70~74 行写的就是"检查数量变少本身就是一种失败"：
        2026-09-19 实测 CI 上 424 → 379（缺 ISS 解包副本，45 项音频检查凭空消失），
        而当时只留了一条埋在 warn 明细里的提示，谁也没看见。
        所以它必须在汇总里**单独、显眼**地列出来，不能混进 warn。
        """
        self.uncovered_items.append(f"[{section}] {msg}")
        print(f"  ? 未覆盖 {msg}")

    def head(self, title: str):
        print(f"\n== {title}")
        self._mark = self.checked

    def summary(self):
        n = self.checked - self._mark
        print(f"  → {n} 项通过" if n else "  → 0 项通过")


def read_json(path: str):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def walk(d: str, ext: str = ".json"):
    for base, _dirs, files in os.walk(d):
        for f in files:
            if f.endswith(ext):
                yield os.path.join(base, f)


def registered_ids() -> set[str]:
    """从 registry/*.java 里抓出所有注册 id，拼成 `mnemosyne:<id>` 全集。

    抓的是 `ITEMS.register("xxx"` / `SPELLS.register("xxx"` / `DeferredRegister.create(...)` 之后的
    `register("id", ...)` 调用 —— 这是本项目的统一写法（见 registry/Mod*.java）。
    """
    ids: set[str] = set()
    if not os.path.isdir(JAVA):
        return ids
    # ⚠️ 不能只认 `X.register("id"`：ModBlocks 走的是辅助方法
    #    `registerBlock("mnemonic_bricks", ...)`（内部再注册 Block + BlockItem），
    #    只认前一种写法会**漏掉 4 个方块和它们的 4 个方块物品**
    #    —— 实测 registered_ids() 从 28 掉到 24，"获取途径"检查因此只审了 10/14 个物品。
    #    所以这里放宽成 `register` + 可选后缀 + `(`，两种写法都能抓。
    pat = re.compile(r'\bregister[A-Za-z]*\(\s*"([a-z0-9_]+)"')
    for base, _dirs, files in os.walk(JAVA):
        for f in files:
            if f.endswith(".java"):
                with open(os.path.join(base, f), encoding="utf-8") as fh:
                    for m in pat.finditer(fh.read()):
                        ids.add(m.group(1))
    # 属性与伤害类型不走 DeferredRegister.register("id") 的写法，单独补
    for extra in ("memory_spell_power", "memory_magic_resist", "memory", "memory_focus"):
        ids.add(extra)
    return ids


# ---------------------------------------------------------------------------
# 检查 1：模型 → 贴图（紫黑格）
# ---------------------------------------------------------------------------
def check_models(rep: Report):
    rep.head("检查 1 · 模型引用的贴图是否存在（紫黑格）")
    models_dir = os.path.join(ASSETS, "models")
    if not os.path.isdir(models_dir):
        rep.warn("model", "没有 models/ 目录")
        return
    for path in sorted(walk(models_dir)):
        rel = os.path.relpath(path, ASSETS).replace(os.sep, "/")
        try:
            model = read_json(path)
        except json.JSONDecodeError as e:
            rep.fail("model", f"{rel} 不是合法 JSON：{e}")
            continue
        parent = model.get("parent", "")

        # 父模型必须真实存在 —— 父模型缺失 = 模型加载失败 = 紫黑格。
        # 只校验我们自己命名空间的：`minecraft:` 的原版模型不在本仓库里，无法离线核对。
        if parent:
            pns, _, pp = parent.partition(":")
            if not pp:                       # 裸 `item/generated` 等价于 `minecraft:item/generated`
                pns, pp = "minecraft", parent
            if pns == MODID:
                if os.path.isfile(os.path.join(ASSETS, "models", pp + ".json")):
                    rep.ok(f"{rel} parent → {parent}")
                else:
                    rep.fail("model", f"{rel} 的 parent = {parent} → 缺 models/{pp}.json（父模型缺失 = 紫黑格）")

        texs = model.get("textures", {})
        if not texs:
            # ⚠️ 2026-09-17 修正：原先这里对"没有 textures 段"一律 warn，
            #    但**纯 parent 继承的模型是完全合法的**（`{"parent": "mnemosyne:block/x"}`
            #    的贴图由父模型提供）。它一次报了 3 条假警报 ——
            #    而"校验器永远红着 = 红了是正常的"，所以假警报必须消灭。
            #    真正该报的是"既没 textures 也没 parent"，那才是必然加载失败的模型。
            if not parent:
                rep.fail("model", f"{rel} 既没有 textures 也没有 parent → 模型必然加载失败")
            else:
                rep.ok(f"{rel} 纯 parent 继承（贴图由 {parent} 提供）")
            continue
        for slot, ref in texs.items():
            ns, _, p = ref.partition(":")
            if not p:                       # 没写命名空间 → 默认 minecraft
                ns, p = "minecraft", ref
            if ns != MODID:
                rep.ok(f"{rel} {slot} → {ref}（外部命名空间，跳过）")
                continue
            png = os.path.join(ASSETS, "textures", p + ".png")
            if os.path.isfile(png):
                rep.ok(f"{rel} {slot} → {ref}")
            else:
                rep.fail("model", f"{rel} 的 {slot} = {ref} → 缺贴图 textures/{p}.png")
    rep.summary()


# ---------------------------------------------------------------------------
# 检查 2：法术 id 三处一致 + 图标存在
# ---------------------------------------------------------------------------
def spell_ids_from_java() -> tuple[list[str], list[str]]:
    """法术 id 来自每个 stub 类的 id 常量，不是 `ModSpells.java`。

    实测：`ModSpells` 走的是 `register(spell)` → `SPELLS.register(spell.getSpellName(), …)`，
    所以 id 的真身在 `spell/{low,mid,high}/*.java` 里。

    ⚠️ **两种写法并存**（2026-09-16 实测，第一版正则只认了前一种，
    结果 18 个 stub 只抓到 6 个 —— 12 个法术的 id/图标/音效**静默漏检**）：
    <pre>
    private static final ResourceLocation SPELL_ID = ResourceLocation.fromNamespaceAndPath(MODID, "memory_arrow");
    private final ResourceLocation spellId = ResourceLocation.fromNamespaceAndPath(MODID, "forget");
    </pre>
    所以这里匹配"变量名里含 spell + id"而不是写死常量名，并且**额外返回没抓到的文件**，
    让调用方能把"校验器自己的盲区"报出来 —— 校验器必须能发现自己漏了什么。

    @return (ids, 没抓到 id 的 stub 文件名列表)
    """
    ids: list[str] = []
    blind: list[str] = []
    pat = re.compile(
        r'\b\w*spell_?id\w*\s*=\s*'
        r'ResourceLocation\.fromNamespaceAndPath\(\s*[\w.]*\.MODID\s*,\s*"([a-z0-9_]+)"',
        re.IGNORECASE,
    )
    for sub in ("low", "mid", "high"):
        d = os.path.join(JAVA, "spell", sub)
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            if not f.endswith(".java"):
                continue
            with open(os.path.join(d, f), encoding="utf-8") as fh:
                m = pat.search(fh.read())
            if m:
                ids.append(m.group(1))
            else:
                blind.append(f"{sub}/{f}")
    return ids, blind


def check_spells(rep: Report):
    rep.head("检查 2 · 法术 id 三处一致（stub / sounds.json / lang）+ 图标存在")
    ids, blind = spell_ids_from_java()
    n_stub = len(ids) + len(blind)
    print(f"  从 {n_stub} 个 stub 类抓到 {len(ids)} 个法术 id")
    # ⭐ 先报"我漏了什么" —— 校验器的盲区比它报出的问题更危险
    for f in blind:
        rep.fail("spell", f"stub {f} 抓不到 id 常量 —— 这是**校验器正则失配**"
                          f"（不是模组的错），该 stub 的 id / 图标 / 音效**全部未被检查**，"
                          f"请更新 tools/check_resources.py 的 spell_ids_from_java()")
    if not ids:
        rep.fail("spell", "一个法术 id 都没抓到（spell/{low,mid,high} 下正则整体失配？）")
        return

    sounds_path = os.path.join(ASSETS, "sounds.json")
    sounds = read_json(sounds_path) if os.path.isfile(sounds_path) else {}
    # sounds.json 的键形如 spell.<id>.cast / spell.<id>.finish
    snd_ids = {k.split(".")[1] for k in sounds
               if isinstance(k, str) and k.startswith("spell.") and k.count(".") >= 2}

    langs = {}
    for code in ("en_us", "zh_cn"):
        p = os.path.join(ASSETS, "lang", f"{code}.json")
        langs[code] = read_json(p) if os.path.isfile(p) else {}

    for sid in ids:
        if sid not in snd_ids:
            rep.fail("spell", f"法术 `{sid}` 在 sounds.json 里没有 `spell.{sid}.*` 键 → 施法静默无声")
        else:
            rep.ok(f"{sid} sounds.json ✓")
        for code, table in langs.items():
            if f"spell.{MODID}.{sid}" not in table:
                rep.fail("spell", f"法术 `{sid}` 缺 lang 键 `spell.{MODID}.{sid}`（{code}）")
        icon = os.path.join(ASSETS, "textures", "gui", "spell_icons", f"{sid}.png")
        if os.path.isfile(icon):
            rep.ok(f"{sid} 图标 ✓")
        else:
            rep.fail("spell", f"法术 `{sid}` 缺图标 textures/gui/spell_icons/{sid}.png → 法术书里空白")
    rep.summary()


# ---------------------------------------------------------------------------
# 检查 3：lang 双语键集一致
# ---------------------------------------------------------------------------
def check_lang(rep: Report):
    rep.head("检查 3 · lang 键集一致性（en_us vs zh_cn）")
    tables = {}
    for code in ("en_us", "zh_cn"):
        p = os.path.join(ASSETS, "lang", f"{code}.json")
        if not os.path.isfile(p):
            rep.fail("lang", f"缺 {code}.json")
            return
        tables[code] = read_json(p)
    en, zh = set(tables["en_us"]), set(tables["zh_cn"])
    print(f"  en_us {len(en)} 键 / zh_cn {len(zh)} 键")
    for k in sorted(en - zh):
        rep.fail("lang", f"`{k}` 只有 en_us，zh_cn 缺 → 中文界面显示英文")
    for k in sorted(zh - en):
        rep.fail("lang", f"`{k}` 只有 zh_cn，en_us 缺")
    if en == zh:
        rep.ok("两语言键集完全一致")


# ---------------------------------------------------------------------------
# 检查 4：sounds.json 引用的 ISS 音频是否存在
# ---------------------------------------------------------------------------
def check_sounds(rep: Report):
    rep.head("检查 4 · sounds.json schema + 引用的 ISS 音频是否存在")
    p = os.path.join(ASSETS, "sounds.json")
    if not os.path.isfile(p):
        rep.fail("sound", "缺 sounds.json")
        return
    sounds = read_json(p)

    # ⭐⭐ schema 检查：每个顶层键都必须是对象。
    # 实测（反编译 client.jar 的 SoundManager / SoundEventRegistrationSerializer）：
    # 一个非对象的键（如 `"_comment": [...]`）会让 convertToJsonObject 抛 JsonSyntaxException，
    # 被 SoundManager 的 catch(RuntimeException) 吞掉 → **整份 sounds.json 被丢弃，零个事件注册**，
    # 而日志里只有一条 warn。2026-09-16 本工程实际踩到过这个坑（39 个事件全灭）。
    bad_keys = [k for k, v in sounds.items() if not isinstance(v, dict)]
    if bad_keys:
        for k in bad_keys:
            rep.fail("sound", f"sounds.json 顶层键 `{k}` 不是对象 → **整份文件会被丢弃**，"
                              f"39 个音效事件全部不注册（只有一条 warn 日志）")
    else:
        rep.ok(f"sounds.json 全部 {len(sounds)} 个顶层键都是对象")

    # ⭐⭐ 先把"本应检查多少条 ISS 音频引用"数出来 —— 这个数字**不依赖** ISS 目录是否存在。
    # 少了它，目录缺失时就只剩一句"跳过检查"，谁也不知道到底少查了多少项。
    iss_refs = 0
    for entry in sounds.values():
        if not isinstance(entry, dict):
            continue
        for snd in entry.get("sounds", []):
            name = snd if isinstance(snd, str) else snd.get("name", "")
            if name.partition(":")[0] == "irons_spellbooks":
                iss_refs += 1

    root = iss_root()
    if not root:
        rep.uncovered("sound", f"{iss_refs} 条 ISS 音频引用的存在性**未校验**"
                               f"（需要 ISS 解包副本，本机/本次运行没有；"
                               f"CI 上因 .gitignore 排除 `可以参考的模组/` 而必然缺失）")
        return
    missing = 0
    total = 0
    for key, entry in sounds.items():
        if not isinstance(entry, dict):
            continue
        for snd in entry.get("sounds", []):
            name = snd if isinstance(snd, str) else snd.get("name", "")
            ns, _, path = name.partition(":")
            if ns != "irons_spellbooks":
                continue
            total += 1
            ogg = os.path.join(root, "assets", "irons_spellbooks", "sounds", path + ".ogg")
            if os.path.isfile(ogg):
                rep.ok(f"{key} → {name}")
            else:
                missing += 1
                rep.fail("sound", f"`{key}` 引用 {name}，但 {path}.ogg 不存在")
    print(f"  共检查 {total} 条 ISS 音频引用，缺失 {missing} 条")
    rep.summary()


# ---------------------------------------------------------------------------
# 检查 5a：worldgen / loot_tables 里有没有引用未注册的 mnemosyne id
# ---------------------------------------------------------------------------
# ⭐⭐ 2026-09-16 教训：本检查最初的版本把**整棵 data/** 树**都当成"引用的都是注册表 id"，
# 结果对 `data/mnemosyne/mnemosyne/oblivion/*.json` 报了 129 条 FAIL —— 全是误报。
# 原因：那一层的 JSON 由我们自己的 `AddReloadListenerEvent` 监听器解析，
# 里面的 `mnemosyne:melee_attack` / `mnemosyne:fire_immunity` 是**能力名 / 特性名**，
# 不是注册表对象，压根不该在 Java 注册表里。
#
# 一个校验器最危险的状态不是"漏报"，而是"噪音太多以至于没人看"。
# 所以这里按**谁来解析这些 JSON** 把 data/ 分成两类，分别用不同的判据：
#
#   注册表加载器解析（严格）→ id 必须真实存在，否则存档直接加载不了
#   我们自己的监听器解析（宽松）→ 见 5b，校验的是"名字有没有拼错"
REGISTRY_DIRS = ("worldgen", "loot_tables", "damage_type", "tags", "recipes", "advancements")

# 我们自己的重载监听器读的目录（自定义格式，不走注册表）
CUSTOM_DIRS = ("mnemosyne",)


def datapack_ids() -> set[str]:
    """数据包自己声明的 id —— 即 `data/mnemosyne/**` 全部路径的后缀集合。

    为什么用"所有后缀"而不是精确解析出 id：同一个 id 在不同位置写法不同 ——
    `worldgen/template_pool/memory_ruin/start_pool.json` 声明的是
    `mnemosyne:memory_ruin/start_pool`，而引用处（`structure/memory_ruin.json`
    的 `start_pool`）也确实这么写；但标签写法是 `#mnemosyne:has_structure/memory_ruin`，
    短的模板池自引用又可能写成 `mnemosyne:memory_ruin`。

    把所有后缀都收进来，既覆盖全部写法，又**不需要维护"注册表目录 → id 前缀"映射表**
    （那张表会随 MC 版本漂移，是长期维护负担）。
    """
    ids: set[str] = set()
    for path in walk(DATA):
        rel = os.path.relpath(path, DATA).replace(os.sep, "/")
        if not rel.endswith(".json"):
            continue
        parts = rel[:-5].split("/")
        for i in range(len(parts)):
            ids.add("/".join(parts[i:]))
    return ids


def structure_nbt_path(ident: str) -> str:
    """拼图块 `location` → 结构模板 NBT 的磁盘路径。

    实测（读 `StructureTemplateManager`，Forge `official` 反编译源码）：
        private static final FileToIdConverter f_244413_ = new FileToIdConverter("structures", ".nbt");
        private Optional<StructureTemplate> m_230427_(ResourceLocation id) {
            ResourceLocation p = f_244413_.m_245698_(id);   // → "<ns>:structures/<path>"
            return m_230372_(() -> this.f_230347_.m_215595_(p), …);
        }
    所以 `mnemosyne:memory_ruin/chamber/corridor` 对应的文件是
    `data/mnemosyne/structures/memory_ruin/chamber/corridor.nbt`。
    """
    return os.path.join(DATA, "structures", ident + ".nbt")


def check_registry_refs(rep: Report):
    rep.head("检查 5a · worldgen / loot_tables / tags 引用的 id 是否真实存在")
    reg = registered_ids()
    dp = datapack_ids()
    print(f"  Java 注册 id {len(reg)} 个 · 数据包自声明 id {len(dp)} 个")
    pat = re.compile(rf'"{MODID}:([a-z0-9_/]+)"')
    n_ref = 0
    n_nbt = 0
    for path in sorted(walk(DATA)):
        rel = os.path.relpath(path, DATA).replace(os.sep, "/")
        if rel.split("/")[0] not in REGISTRY_DIRS:
            continue
        with open(path, encoding="utf-8") as f:
            src = f.read()
        for m in pat.finditer(src):
            ident = m.group(1)
            n_ref += 1
            if ident in reg:
                rep.ok(f"{rel} → {MODID}:{ident}（Java 注册表）")
            elif ident in dp:
                rep.ok(f"{rel} → {MODID}:{ident}（数据包自声明）")
            elif os.path.isfile(structure_nbt_path(ident)):
                n_nbt += 1
                rep.ok(f"{rel} → {MODID}:{ident}（结构模板 NBT）")
            elif "/" in ident:
                # ⭐ 带 `/` 的名字在 worldgen 里只可能是"数据包自声明 id"或"结构模板"。
                # 两者都不匹配 → 一定是结构模板缺文件。
                # 实测的失败模式（**不是**存档加载失败，比那个更阴）：
                #   getOrCreate(m_230359_) → loadFromResource(m_230427_)
                #   → 文件不存在 → FileNotFoundException
                #   → m_230372_ 返回 Optional.empty()，**FileNotFoundException 分支不打日志**
                #   → getOrCreate 返回一个**全新的空 StructureTemplate** 并缓存
                # 结果：注册表照常加载、结构照常被选中、照常"生成" —— 生成出来的是空气。
                # 遗迹永远不出现，零日志零报错。
                rep.pending("nbt", f"{rel} 引用了 `{MODID}:{ident}`，但 "
                                  f"data/mnemosyne/structures/{ident}.nbt 不存在 → "
                                  f"该拼图块会被静默换成**空模板**，遗迹生成空气（WS-G2 待交付）")
            else:
                rep.fail("data", f"{rel} 引用了 `{MODID}:{ident}` —— 既不在 Java 注册表里，"
                                  f"也不是数据包自己声明的 id。worldgen 里这样写会让"
                                  f"**存档直接加载不了**（`Failed to load registries`）")
    print(f"  共检查 {n_ref} 条注册表引用（其中 {n_nbt} 条命中结构模板 NBT）")
    rep.summary()


# ---------------------------------------------------------------------------
# 检查 5b：能力 / 特性 id 与 Java 常量表是否一致
# ---------------------------------------------------------------------------
def custom_ids() -> dict[str, set[str]]:
    """从 `oblivion/*.java` 抓出"能力 id"与"特性 id"两套自定义 id。

    实测写法（`AbilityMap` / `TraitRegistry`）：
    <pre>
    public static final ResourceLocation MELEE_ATTACK  = ability("melee_attack");
    public static final ResourceLocation FIRE_IMMUNITY = trait("fire_immunity");
    </pre>
    `FILE_*`（`FILE_ABILITIES` / `FILE_TRAITS`）是**数据包文件名**常量，不是能力名，排除。
    """
    out: dict[str, set[str]] = {"ability": set(), "trait": set()}
    d = os.path.join(JAVA, "oblivion")
    if not os.path.isdir(d):
        return out
    for f in sorted(os.listdir(d)):
        if not f.endswith(".java"):
            continue
        with open(os.path.join(d, f), encoding="utf-8") as fh:
            src = fh.read()
        for kind in out:
            pat = re.compile(rf'ResourceLocation\s+(\w+)\s*=\s*{kind}\("([a-z0-9_]+)"\)')
            for m in pat.finditer(src):
                if not m.group(1).startswith("FILE_"):
                    out[kind].add(m.group(2))
    return out


def check_custom_data(rep: Report):
    """校验数据包里的能力/特性名**拼对了**。

    这是本项目最典型的静默失败之一：`AbilityMap.parseAbilities()` 对无法解析的条目
    只 `LOGGER.warn` 然后 `continue` —— 编译通过、游戏能起、存档能加载，
    只是"某个生物永远遗忘不了 / 某个特性永远窃取不到"，且日志淹没在几千行里。

    `tools/check_ability_map.py`（WS-E 自己的工具）校验的是 **RULES 模式表的顺序**，
    与本检查互补：那个管"Java 内部自洽"，这个管"Java ↔ 数据包一致"。
    """
    rep.head("检查 5b · 能力 / 特性 id 是否与 Java 常量表一致（数据包静默失效）")
    decl = custom_ids()
    print(f"  Java 声明：能力 {len(decl['ability'])} 个 · 特性 {len(decl['trait'])} 个")
    if not decl["ability"] and not decl["trait"]:
        rep.warn("data", "抓不到能力/特性常量（oblivion/*.java 正则失配？）")
        return

    used: dict[str, set[str]] = {"ability": set(), "trait": set()}
    for path in sorted(walk(DATA)):
        rel = os.path.relpath(path, DATA).replace(os.sep, "/")
        if rel.split("/")[0] not in CUSTOM_DIRS:
            continue
        try:
            doc = read_json(path)
        except json.JSONDecodeError as e:
            rep.fail("data", f"{rel} 不是合法 JSON：{e}")
            continue
        if not isinstance(doc, dict):
            continue
        for kind, key in (("ability", "abilities"), ("trait", "traits")):
            table = doc.get(key)
            if table is None:
                continue
            if not isinstance(table, dict):
                rep.fail("data", f"{rel} 的 `{key}` 不是对象 → 整张表被忽略")
                continue
            for mob, arr in table.items():
                if not isinstance(arr, list):
                    rep.fail("data", f"{rel} 的 {key}.{mob} 不是数组 → 该生物整条被忽略")
                    continue
                for raw in arr:
                    if not isinstance(raw, str) or ":" not in raw:
                        rep.fail("data", f"{rel} 的 {key}.{mob} 里有非法 id：{raw!r}")
                        continue
                    ns, _, ident = raw.partition(":")
                    if ns != MODID:
                        continue
                    used[kind].add(ident)
                    if ident in decl[kind]:
                        rep.ok(f"{rel} {key}.{mob} → {raw}")
                    else:
                        rep.fail("data", f"{rel} 的 {key}.{mob} 引用了 `{raw}`，但 Java 的 "
                                          f"{kind} 常量表里没有这个名字 → 该条被静默忽略"
                                          f"（这个生物忘不掉 / 窃不到）")

    for kind in ("ability", "trait"):
        unused = sorted(decl[kind] - used[kind])
        if unused:
            rep.warn("data", f"{kind} 常量在 Java 里声明了但数据包没用到：{', '.join(unused)}")
    rep.summary()


# ---------------------------------------------------------------------------
# 检查 6：贴图目录里有没有"没人引用"的孤儿
# ---------------------------------------------------------------------------
def check_orphan_textures(rep: Report):
    rep.head("检查 6 · 孤儿贴图（有贴图但没有任何模型引用）")
    refs: set[str] = set()
    models_dir = os.path.join(ASSETS, "models")
    for path in walk(models_dir) if os.path.isdir(models_dir) else []:
        try:
            model = read_json(path)
        except json.JSONDecodeError:
            continue
        for ref in model.get("textures", {}).values():
            ns, _, p = ref.partition(":")
            if not p:
                ns, p = "minecraft", ref
            if ns == MODID:
                refs.add(p)

    # 实体贴图是 Java 侧引用的（渲染器的 getTextureLocation），模型 JSON 里找不到。
    # 所以这里换个查法：**在 Java 源码里搜这条路径**。
    # 渲染器里写错一个字母 → 游戏里是紫黑格、零报错，正是要防的那一类。
    java_blob = ""
    for path in walk(JAVA, ".java") if os.path.isdir(JAVA) else []:
        try:
            with open(path, encoding="utf-8") as f:
                java_blob += f.read()
        except OSError:
            continue

    tex_root = os.path.join(ASSETS, "textures")
    for path in sorted(walk(tex_root, ".png")):
        rel = os.path.relpath(path, tex_root).replace(os.sep, "/")[:-4]
        if rel in refs:
            continue
        # 法术图标由 getSpellIconResource() 按约定引用，不算孤儿
        if rel.startswith("gui/spell_icons/"):
            continue
        # 护甲穿戴图层由 ArmorMaterial 引用（Java 侧），不算孤儿
        if rel.startswith("models/armor/"):
            continue
        # ⭐ 状态效果图标：MC 按**约定**解析 `textures/mob_effect/<effect_id>.png`，
        #    不经过任何模型 JSON。所以"没有模型引用"是正常的，不是孤儿。
        #    （与 gui/spell_icons/ 同类：由代码按 id 拼路径，模型系统看不到。）
        #    ⚠️ 排除它**不等于**不检查 —— 检查 13 专门负责"每个效果都有图标"。
        if rel.startswith("mob_effect/"):
            continue
        # 实体贴图：必须在 Java 里被引用，且路径要逐字对上
        if rel.startswith("entity/"):
            if f"textures/{rel}.png" not in java_blob:
                rep.fail("orphan",
                         f"textures/{rel}.png 在 Java 源码里找不到引用 → "
                         f"实体渲染出来是紫黑格（渲染器的 getTextureLocation 路径写错了？）")
            else:
                rep.ok(f"textures/{rel}.png 已被 Java 引用")
            continue
        rep.warn("orphan", f"textures/{rel}.png 没有任何模型引用 → 游戏里看不到")


# ---------------------------------------------------------------------------
def check_iss_school_assets(rep: Report):
    """检查 8 · ISS 按学派解析的**隐性资源约定**（只有 runClient 才会暴露）

    ⭐⭐ ISS 里有一批资源是**按学派拼 id** 解析的，它们既不在我们的注册代码里，
    也不在任何模型 JSON 里 —— 所以前面的检查一条都看不到。
    缺了它们，服务端**完全正常**，只有客户端 `ModelBakery` 打一行 WARN，
    玩家看到的是紫黑格。2026-09-17 第一次 runClient 才发现。

    已知的两条约定（实测，证据见备注）：
      · 亲和戒指   → `assets/<ns>/models/item/affinity_ring_<学派路径>.json`
                     ISS 只有一个物品 `irons_spellbooks:affinity_ring`，
                     模型却按学派解析；`AffinityRingRenderer` 负责。
      · 学派卷轴   → `assets/<ns>/models/item/scroll_<学派路径>.json`
                     见 ModItems 类注释（`ScrollModel.getScrollModelLocation`）。

    这里只检查**文件存在 + 模型指向的贴图存在**；不检查像素内容
    （内容归 `tools/gen_mnemosyne_art.py --check`）。
    """
    rep.head("检查 8 · ISS 按学派解析的隐性资源（缺了只有客户端会 WARN）")

    # 学派路径：与 SchoolRegistry 里注册的 id 的 path 一致（mnemosyne:memory）
    school_path = "memory"

    required = [
        (f"models/item/affinity_ring_{school_path}.json",
         f"textures/item/affinity_ring_{school_path}.png"),
        (f"models/item/scroll_{school_path}.json",
         None),
    ]
    for model_rel, tex_rel in required:
        model_path = os.path.join(ASSETS, model_rel.replace("/", os.sep))
        rep.checked += 1
        if not os.path.isfile(model_path):
            rep.fail("iss_school",
                     f"缺 {model_rel} → ISS 的亲和戒指/卷轴按学派解析到这里，玩家看到紫黑格")
            continue
        try:
            model = json.load(open(model_path, encoding="utf-8"))
        except json.JSONDecodeError as exc:
            rep.fail("iss_school", f"{model_rel} 不是合法 JSON：{exc}")
            continue
        rep.ok(model_rel)
        if tex_rel is None:
            continue
        # 模型指向的贴图必须真的存在
        for ref in model.get("textures", {}).values():
            ns, _, p = ref.partition(":")
            if not p:
                ns, p = "minecraft", ref
            if ns != MODID:
                continue
            rep.checked += 1
            tex_path = os.path.join(ASSETS, "textures", f"{p}.png".replace("/", os.sep))
            if os.path.isfile(tex_path):
                rep.ok(f"{model_rel} → textures/{p}.png")
            else:
                rep.fail("iss_school", f"{model_rel} 指向 textures/{p}.png，但文件不存在")


# ---------------------------------------------------------------------------
# 检查 9：每个注册的状态效果都必须有语言键
# ---------------------------------------------------------------------------
def check_effect_lang(rep: Report):
    """状态效果缺语言键 → 游戏里直接显示 `effect.mnemosyne.xxx` 原始键。

    ⭐ 这是 2026-09-18 实测发现的**真缺陷**：`forget` / `amnesia` /
    `cognitive_overload` / `sluggish` 四个效果注册了，但**一个语言键都没有**。

    为什么检查 3 查不出来：检查 3 只比对「en_us 与 zh_cn 的键集是否一致」——
    两边**都缺**的时候它是"一致"的，所以完全无声。这正是
    「校验器必须能发现自己的盲区」那条规则的又一个实例：
    **只比对两个副本之间的一致性，无法发现两边都错的情况。**
    """
    rep.head("检查 9 · 状态效果是否有语言键（缺了显示原始键）")
    src = os.path.join(ROOT, "src", "main", "java", "com", "etbs31", "mnemosyne",
                       "registry", "ModEffects.java")
    if not os.path.isfile(src):
        rep.fail("effects", "找不到 registry/ModEffects.java")
        return
    text = open(src, encoding="utf-8").read()
    ids = re.findall(r'EFFECTS\.register\(\s*"([a-z_]+)"', text)
    if not ids:
        # ⭐ 自己报盲区：抓不到 id 说明正则失配，绝不能当"没问题"
        rep.fail("effects", "没能从 ModEffects.java 抓到任何效果 id（正则失配？）")
        return

    langs = {}
    for code in ("en_us", "zh_cn"):
        p = os.path.join(ASSETS, "lang", f"{code}.json")
        if os.path.isfile(p):
            langs[code] = read_json(p)

    missing = 0
    for eid in ids:
        key = f"effect.mnemosyne.{eid}"
        for code, table in langs.items():
            if key not in table:
                rep.fail("effects", f"`{key}` 在 {code} 缺失 → 游戏里显示原始键")
                missing += 1
    if missing == 0:
        rep.ok(f"{len(ids)} 个状态效果全部有语言键")
    rep.summary()


# ---------------------------------------------------------------------------
# 检查 10：死配置（声明了但没有任何消费者）
# ---------------------------------------------------------------------------
# 已知且**有意为之**的白名单：这两个键的注释里写明了"数据包在加载时即固定，
# 本配置项只作为期望值的单一事实来源与文档用途"，所以没有 Java 消费者是正确的。
CONFIG_ALLOWLIST = {
    "RUIN_SPACING", "RUIN_SEPARATION",
}

# 已知欠账（**应当**接上但还没接）：不算 FAIL，列出来提醒。
# 这两个是早期工作流留下的，与本次改动无关。
CONFIG_KNOWN_DEBT = {
    "SPELL_MEMORY_POWER": "术忆释放的威力系数，应在 EngramRelease 释放术忆时乘上去",
    "LEGENDARY_WEIGHT": "忆碑抽传说法术的权重，应替换 SteleUnlocks.WEIGHTS 的最后一档",
}


def check_dead_config(rep: Report):
    """声明了但没有消费者的配置项。

    ⭐ 为什么这是个真问题：**死配置比没有配置更糟** ——
    玩家改了开关发现没反应，会以为模组坏了，而且他没有任何办法知道原因。
    2026-09-18 实测：删掉忆格 HUD 之后，`hud.enabled` / `hud.simpleMode` /
    `hud.offsetX` / `hud.offsetY` 四项立刻变成死配置（全项目零引用），
    而**没有任何检查会发现这件事** —— 它们语法正确、编译通过、配置文件里正常生成。

    本检查把"配置项必须有消费者"变成一条可执行的规则。
    """
    rep.head("检查 10 · 死配置（声明了但没有任何消费者）")
    cfg_path = os.path.join(ROOT, "src", "main", "java", "com", "etbs31", "mnemosyne", "Config.java")
    if not os.path.isfile(cfg_path):
        rep.fail("config", "找不到 Config.java")
        return
    cfg = open(cfg_path, encoding="utf-8").read()
    fields = re.findall(r'public static ForgeConfigSpec\.\w+Value\s+(\w+);', cfg)
    if not fields:
        rep.fail("config", "没能从 Config.java 抓到任何配置字段（正则失配？）")
        return

    # 把所有非 Config.java 的源码拼起来，检查每个字段是否被 `.<字段名>` 引用
    src = ""
    for root, _dirs, files in os.walk(os.path.join(ROOT, "src", "main", "java")):
        for fn in files:
            if fn.endswith(".java") and fn != "Config.java":
                src += open(os.path.join(root, fn), encoding="utf-8").read()

    dead, debt = [], []
    for f in fields:
        if f in CONFIG_ALLOWLIST:
            continue
        if not re.search(r"\.\s*" + f + r"\b", src):
            if f in CONFIG_KNOWN_DEBT:
                debt.append(f)
            else:
                dead.append(f)

    for f in dead:
        rep.fail("config", f"配置项 `{f}` 没有任何消费者 → 玩家改了没反应，会以为模组坏了")
    for f in debt:
        rep.warn("config", f"已知欠账：`{f}` 尚未接线（{CONFIG_KNOWN_DEBT[f]}）")
    if not dead:
        rep.ok(f"{len(fields)} 个配置字段全部有消费者（白名单 {len(CONFIG_ALLOWLIST)} 个、"
               f"已知欠账 {len(debt)} 个）")
    rep.summary()


# ---------------------------------------------------------------------------
# 检查 11：跨命名空间的标签引用（data/<其他命名空间>/tags/**）
# ---------------------------------------------------------------------------
def check_foreign_tags(rep: Report):
    """模组往**别的命名空间**里写标签时，`mnemosyne:` 引用是否拼对。

    ⭐ 为什么需要这条：检查 5a 只遍历 `data/mnemosyne/**`。
    但模组的 datapack **经常必须**往 `data/minecraft/tags/**` 里加东西
    （典型例子：把自己的伤害类型加进 `bypasses_armor` / `bypasses_enchantments`）。
    这些文件**完全不在任何检查的覆盖范围内**。

    失败方式是典型的静默失效：把 `mnemosyne:memory_true` 拼成
    `mnemosyne:memoy_true` → 标签照常加载、只是里面没有这一项 →
    "无视护甲"悄悄失效 → 玩家只会觉得"这法术怎么不太疼"。

    ⚠️ **本检查声明的盲区**：只校验 `mnemosyne:` 前缀的值。
    原版 / 其他模组的 id（如 `minecraft:player_attack`）需要各自的注册表快照才能校验，
    本工具没有，所以**跳过并如实报数**，不假装查过。
    """
    rep.head("检查 11 · 跨命名空间标签引用（data/<其他 ns>/tags/**）")
    base = os.path.join(ROOT, "src", "main", "resources", "data")
    if not os.path.isdir(base):
        rep.fail("tags", "找不到 data/ 目录")
        return

    declared = datapack_ids()          # data/mnemosyne/** 声明的 id
    java_ids = registered_ids()        # Java 侧注册的 id

    files, checked, skipped = 0, 0, 0
    for ns in sorted(os.listdir(base)):
        if ns == "mnemosyne":
            continue
        tags_dir = os.path.join(base, ns, "tags")
        if not os.path.isdir(tags_dir):
            continue
        for path in sorted(walk(tags_dir)):
            if not path.endswith(".json"):
                continue
            files += 1
            data = read_json(path)
            rel = os.path.relpath(path, ROOT).replace(os.sep, "/")
            for value in _tag_values(data):
                name = value.lstrip("#")
                if not name.startswith("mnemosyne:"):
                    skipped += 1
                    continue
                checked += 1
                ident = name.split(":", 1)[1]
                if ident in declared or ident in java_ids:
                    rep.ok(f"{rel} → {name}")
                else:
                    rep.fail("tags", f"{rel} 引用了不存在的 `{name}` "
                                      f"→ 标签静默缺少该项，效果悄悄失效")

    print(f"  跨命名空间标签文件 {files} 个 · 校验 mnemosyne: 引用 {checked} 条 · "
          f"跳过其他命名空间 {skipped} 条（本工具无其注册表快照）")
    rep.summary()


def _tag_values(node) -> list[str]:
    """递归取出标签 JSON 里所有 values（支持 replace / 嵌套 dict 写法）。"""
    out: list[str] = []
    if isinstance(node, dict):
        for key, val in node.items():
            if key == "values" and isinstance(val, list):
                for v in val:
                    if isinstance(v, str):
                        out.append(v)
                    elif isinstance(v, dict) and isinstance(v.get("id"), str):
                        out.append(v["id"])
            else:
                out.extend(_tag_values(val))
    elif isinstance(node, list):
        for v in node:
            out.extend(_tag_values(v))
    return out


# ---------------------------------------------------------------------------
# 检查 12：法术的 onCast 必须真的产生可见效果
# ---------------------------------------------------------------------------
# "产生效果"的判据：出现下列任意一项，就说明这一发施法**至少会改变点什么**。
# 刻意列得很宽 —— 本检查要抓的是"整段 onCast 什么都没干"这种极端情况，
# 不是去评审具体逻辑对不对（那要靠游戏内验收）。
#
# ⚠️ sendSystemMessage 是「窥忆」的效果出口（聊天栏结构化情报）——
#    第一版清单漏了它，导致 GlimpseSpell 被误判为 FAIL。
#    补判据而不是豁免该法术：豁免会让这类用聊天栏输出的效果**永远不被检查**。
EFFECT_CALLS = (
    "SpellFeedback.", "displayClientMessage", "sendSystemMessage",
    "addEffect(", "addEngram(",
    "dealSpellDamage", "hurtWithSpellDamage", "hurtWithTrueDamage", "applyDamage",
    "OblivionManager.", "TraitRegistry.", "applyTrait", "applyOblivion",
    "addFreshEntity(", "release(", "releaseSpell(", "releaseEngram(",
    "castBurst", "hitBurst", "sendParticles", "broadcastEntityEvent",
    "clearAll(", "clearTempSlots(", "refreshEngramEffects", "notifyEngramChange",
    "learnSpell", "setHealth", "setRemainingFireTicks",
)

# 跟随本类方法调用的最大深度（见 _expand_delegates）。
# 3 层是实测值：CurseOfOblivionSpell 的 onCast → openField → burst → emit 正好用满。
# 调大没有风险（`seen` 保证每个方法只展开一次），但也没必要 —— 层数越深，
# 这个"宽判据"越接近恒真，检查的力度就越弱。
DELEGATE_DEPTH = 3


def _class_body(text: str, decl_index: int) -> str:
    """从 decl_index 处往后取第一个平衡大括号块的内容。"""
    start = text.find("{", decl_index)
    if start < 0:
        return ""
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    return text[start:]


def _parent_class(text: str) -> str | None:
    m = re.search(r"class\s+\w+\s+extends\s+([A-Za-z_]\w*)", text)
    return m.group(1) if m else None


def _expand_delegates(class_text: str, body: str, depth: int = DELEGATE_DEPTH,
                      seen: set[str] | None = None) -> str:
    """把 `onCast` 里**调用到的本类方法**的方法体一并纳入搜索范围（多层委托）。

    ⭐ 这是修一个**误报**：第一版只看 `onCast` 的字面内容，于是
    `GlimpseSpell` / `ReciteSpell` / `DejaVuSpell` 等 9 个法术全部被判 FAIL ——
    而它们的 onCast 是这样的：

    <pre>
    public void onCast(...) {
        if (!level.isClientSide &amp;&amp; entity instanceof ServerPlayer caster) {
            replay(caster, spellLevel);      // ← 效果全在私有方法里
        }
        super.onCast(...);
    }
    </pre>

    效果明明存在，只是被委托出去了。**校验器报 9 条假 FAIL 比不报更糟** ——
    它会训练出"红了是正常的"，然后这个校验器就死了。
    所以按项目规矩把它改成**真检查**：跟随方法调用。

    ⭐ 2026-09-18 由「一层」改成「{@code DELEGATE_DEPTH} 层」。触发原因是一条**假 FAIL**：
    `CurseOfOblivionSpell.onCast → openField → burst → emit → sendParticles`
    是**三层**委托，而当时只跟一层，于是这个明明有粒子表现的领域法术被判"什么都没干"。

    ⚠️ 与"豁免"的区别：豁免是把这条 FAIL 关掉（以后真的什么都不干的法术也不会被发现），
    这里是**把跟随深度调够**，判据本身没有放松 —— 深度用完仍然找不到效果调用的，
    照样 FAIL。`seen` 在递归中共享，方法被展开过就不再展开，所以自递归方法不会爆栈。
    """
    expanded = body
    if seen is None:
        seen = set()
    for m in re.finditer(r"\b([a-zA-Z_]\w*)\s*\(", body):
        name = m.group(1)
        if name in seen or name in (
                "if", "for", "while", "switch", "return", "new", "super", "this",
                "catch", "synchronized", "instanceof", "cast", "equals", "of"):
            continue
        seen.add(name)
        # 在本类里找同名方法的定义（放宽签名匹配，只要名字 + 参数表 + 大括号）
        dm = re.search(
            r"(?:private|protected|public|static|final|synchronized|\s)+"
            r"[\w<>\[\],.?\s]+\s+" + re.escape(name) + r"\s*\([^)]*\)\s*\{",
            class_text)
        if dm:
            sub = _class_body(class_text, dm.start())
            expanded += sub
            if depth > 1:
                expanded += _expand_delegates(class_text, sub, depth - 1, seen)
    return expanded


def check_spell_effects(rep: Report):
    """每个法术的 `onCast`（含继承与多层委托）必须至少产生一个可见效果。

    ⭐ 为什么这条值得做成检查：2026-09-17 实测发现「忆矢」的
    `MnemosyneProjectileSpell.onCast` 与 `MemoryArrowSpell.onCast` **都是 TODO，
    什么都不生成** —— 玩家放忆矢时法力扣了、冷却转了、屏幕上什么都没有。
    而它**编译通过、资源校验全绿、服务端零报错**。

    这一类失效（"onCast 是空的"）用静态检查抓得住，而且抓的是**根因**。

    ⚠️ 本检查的能力边界（写在明处，不假装查过）：
    它只回答"onCast 这一路有没有东西"，**不回答"逻辑对不对"** ——
    数值算错、条件写反、目标选错、超出 `DELEGATE_DEPTH` 层的调用链，
    这些都查不出来，仍须游戏内验收。
    """
    rep.head(f"检查 12 · 法术的 onCast 是否真的产生效果（含继承 + {DELEGATE_DEPTH} 层委托）")
    base = os.path.join(JAVA, "spell")
    if not os.path.isdir(base):
        rep.fail("spells", "找不到 spell/ 目录")
        return

    # 索引：类名 → 源码。base 只用于解析继承，不参与"这是不是一个法术"的判断
    #（修误报：EngramRelease 有个叫 spellId 的**参数**，被上一版误判成法术类）
    classes: dict[str, str] = {}
    concrete: list[str] = []
    for sub in ("low", "mid", "high", "base"):
        d = os.path.join(base, sub)
        if not os.path.isdir(d):
            continue
        for fn in sorted(os.listdir(d)):
            if fn.endswith(".java"):
                classes[fn[:-5]] = open(os.path.join(d, fn), encoding="utf-8").read()
                if sub in ("low", "mid", "high"):
                    concrete.append(fn[:-5])
    if not concrete:
        rep.fail("spells", "没能索引到任何具体法术类（目录结构变了？）")
        return

    ids, blind = spell_ids_from_java()
    if blind:
        rep.fail("spells", f"以下文件没抓到法术 id（正则失配）：{blind}")

    checked = 0
    for name in concrete:
        text = classes[name]
        if not re.search(r"\b\w*spell_?id\w*\s*=", text, re.IGNORECASE):
            continue
        checked += 1

        # 沿继承链找 onCast
        owner, body, depth = name, "", 0
        cur_name, cur_text = name, text
        while cur_text and depth < 6:
            m = re.search(r"public\s+void\s+onCast\s*\(", cur_text)
            if m:
                owner, body = cur_name, _class_body(cur_text, m.start())
                break
            parent = _parent_class(cur_text)
            if not parent or parent not in classes:
                break
            cur_name, cur_text, depth = parent, classes[parent], depth + 1

        if not body:
            rep.fail("spells", f"{name} 的继承链上找不到 onCast → 施放后不会产生任何效果")
            continue

        # ⭐ 跟随多层委托后再找效果调用（2026-09-18 由一层改为 DELEGATE_DEPTH 层）
        #
        # ⭐⭐ 2026-09-19：长吟法术（extends MnemosyneLongCastSpell）的**效果不在 onCast 里**。
        #     onCast 只负责起手，真正的效果在 onLongCastTick / onLongCastFinish ——
        #     它们由基类的 onServerCastTick 在吟唱推进 / 吟唱完成时回调。
        #     所以这里必须把这两个钩子的实现也并进搜索范围，否则「帧缚」这类法术
        #     会被误报成"放了什么都不发生"。
        searchable = _expand_delegates(classes.get(owner, text), body)
        hooks = ""
        for hook in ("onLongCastTick", "onLongCastFinish"):
            hm = re.search(r"protected\s+void\s+" + hook + r"\s*\(", text)
            if hm:
                hooks += _expand_delegates(text, _class_body(text, hm.start()))
        extra = f" + {len([1 for h in ('onLongCastTick', 'onLongCastFinish') if re.search(r'protected void ' + h + r'\(', text)])} 个长吟钩子" if hooks else ""
        if not any(call in searchable for call in EFFECT_CALLS) \
                and not (hooks and any(call in hooks for call in EFFECT_CALLS)):
            rep.fail("spells", f"{name} 的 {owner}.onCast（含 {DELEGATE_DEPTH} 层委托{extra}）里**没有任何效果调用** "
                              f"→ 施放后法力扣了但什么都不发生")
            continue

        early = len(re.findall(r"\breturn\s*;", body))
        feedback = body.count("SpellFeedback.") + body.count("displayClientMessage")
        if early > 0 and feedback == 0:
            rep.warn("spells", f"{name} 的 onCast 有 {early} 处裸 return 且自身没有反馈调用 "
                               f"→ 请确认失败路径玩家能看出原因")
        rep.ok(f"{name}（onCast 来自 {owner}）")

    print(f"  审了 {checked} 个具体法术类（继承链 + {DELEGATE_DEPTH} 层委托都已展开）")
    rep.summary()


# ---------------------------------------------------------------------------
# 检查 13：每个状态效果都必须有图标
# ---------------------------------------------------------------------------
def check_effect_icons(rep: Report):
    """状态效果的图标路径是**约定**的：`assets/<ns>/textures/mob_effect/<id>.png`。

    ⭐ 2026-09-18 实测发现：我们注册的 10 个效果**一个图标都没有**。
    缺文件**不报错** —— 游戏里只是显示成缺失贴图，而且只有打开效果栏才看得到，
    很容易一直没人发现。检查 9 查的是"有没有名字"，这条查"有没有图标"，
    两者是同一类"约定路径下的隐性资源"。

    ⚠️ 与检查 9 一样会**自报盲区**：抓不到 id 就报 FAIL，而不是当成"没问题"。
    """
    rep.head("检查 13 · 状态效果是否有图标（缺了显示缺失贴图）")
    src = os.path.join(ROOT, "src", "main", "java", "com", "etbs31", "mnemosyne",
                       "registry", "ModEffects.java")
    if not os.path.isfile(src):
        rep.fail("effects", "找不到 registry/ModEffects.java")
        return
    ids = re.findall(r'EFFECTS\.register\(\s*"([a-z_]+)"',
                     open(src, encoding="utf-8").read())
    if not ids:
        rep.fail("effects", "没能从 ModEffects.java 抓到任何效果 id（正则失配？）")
        return

    tex_dir = os.path.join(ASSETS, "textures", "mob_effect")
    missing = 0
    for eid in ids:
        path = os.path.join(tex_dir, eid + ".png")
        if not os.path.isfile(path):
            rep.fail("effects", f"缺图标 `textures/mob_effect/{eid}.png` "
                                f"→ 效果栏里显示缺失贴图（不会报错）")
            missing += 1
    if missing == 0:
        rep.ok(f"{len(ids)} 个状态效果全部有图标")
    rep.summary()


# ---------------------------------------------------------------------------
# 检查 14：@SubscribeEvent 所在类必须有 @Mod.EventBusSubscriber
# ---------------------------------------------------------------------------
def check_event_subscribers(rep: Report):
    """写了 `@SubscribeEvent` 但类上没有 `@Mod.EventBusSubscriber` → 方法**永不执行**。

    ⭐ 2026-09-18 实测抓到的真 bug：「走马灯」改成不死图腾式之后
    **完全不生效**。原因是 `RecollectionSpell` 里写了
    `@SubscribeEvent public static void onLivingDeath(...)`，
    但类上**漏了 `@Mod.EventBusSubscriber`** ——
    于是那个致死拦截**从来没有被注册过**，效果自然一点都没有。

    ⚠️ 这类失效的可怕之处：**编译通过、校验全绿、日志零输出**。
    方法体写得再对也不会被调用，而代码读起来完全正常
    （`@SubscribeEvent` 就在那儿摆着）。静态检查抓它几乎是零成本。
    """
    rep.head("检查 14 · @SubscribeEvent 是否真的会被注册（缺 @Mod.EventBusSubscriber）")
    hits = 0
    for path in sorted(walk(JAVA, ".java")):
        text = open(path, encoding="utf-8").read()
        if "@SubscribeEvent" not in text:
            continue
        hits += 1
        rel = os.path.relpath(path, ROOT).replace(os.sep, "/")
        if "@Mod.EventBusSubscriber" not in text:
            rep.fail("events", f"{rel} 有 @SubscribeEvent 但没有 @Mod.EventBusSubscriber "
                               f"→ 这些方法**永远不会被调用**（编译通过、无任何报错）")
        else:
            rep.ok(f"{rel}")
    if hits == 0:
        rep.fail("events", "全项目没找到任何 @SubscribeEvent（正则失配？）")
    print(f"  含 @SubscribeEvent 的文件 {hits} 个")
    rep.summary()


# 检查 15：注册了的物品必须有**生存模式获取途径**
#
# 起因（2026-09-18 实测）：faded_page 等物品**全部拿不到** ——
# 既没有合成配方，也不在任何战利品表里，
# 只能靠创造模式 / 指令获得。`build` 不报、服务端不报、前 14 条检查一条都没报。
# 这正好是红线三说的"校验器盲区"：漏报比误报更危险。
#
# ⚠️ 自报盲区：一个注册物品都抓不到 → 判 FAIL（正则失配），不能当"没问题"。
def check_item_obtainability(rep: Report):
    rep.head("检查 15 · 注册物品是否有生存模式获取途径")

    # ⚠️ JAVA 常量已经是 ...\src\main\java\com\etbs31\mnemosyne，别再拼包路径
    moditems = os.path.join(JAVA, "registry", "ModItems.java")
    if not os.path.isfile(moditems):
        rep.fail("obtain", f"找不到 {moditems}（路径变了？本检查已失效）")
        rep.summary()
        return

    text = open(moditems, encoding="utf-8").read()
    reg = set(re.findall(r'(?:ITEMS|SPAWN_EGG_ITEMS)\.register\("([a-z0-9_]+)"', text))
    if not reg:
        rep.fail("obtain", "从 ModItems.java 里一个注册物品都没抓到（正则失配？）")
        rep.summary()
        return

    # ① 合成配方产物
    from_recipe: set[str] = set()
    rdir = os.path.join(DATA, "recipes")
    for path in walk(rdir, ".json"):
        try:
            d = json.load(open(path, encoding="utf-8"))
        except Exception:
            continue
        res = d.get("result")
        if isinstance(res, dict) and isinstance(res.get("item"), str):
            from_recipe.add(res["item"].split(":")[-1])
        elif isinstance(res, str):
            from_recipe.add(res.split(":")[-1])

    # ② 战利品表条目
    from_loot: set[str] = set()
    for path in walk(os.path.join(DATA, "loot_tables"), ".json"):
        blob = open(path, encoding="utf-8").read()
        for m in re.findall(r'"name":\s*"mnemosyne:([a-z0-9_]+)"', blob):
            from_loot.add(m)

    # ③ Java 里直接发放（忆碑送书、指令给物品等）
    # ⚠️ 必须排除创造标签页与注册表本身：ModCreativeTabs 会列出**每一个**物品，
    #    把它算作"获取途径"的话，本检查对任何物品都永远通过（假阴性）。
    #    创造模式不是生存模式的获取途径 —— 那正是这个检查要抓的东西。
    java_blob = "".join(open(path, encoding="utf-8").read()
                        for path in walk(JAVA, ".java")
                        if os.path.basename(path) not in
                        ("ModCreativeTabs.java", "ModItems.java"))
    from_java = set(re.findall(r'ModItems\.([A-Z0-9_]+)\.get\(\)', java_blob))
    # 常量名 → 物品 id（MNEMONIC_INK → mnemonic_ink）
    from_java = {n.lower() for n in from_java}

    obtainable = from_recipe | from_loot | from_java
    missing = sorted(i for i in reg if i not in obtainable)

    for i in missing:
        rep.fail("obtain",
                 f"mnemosyne:{i} 注册了但**生存模式拿不到** —— "
                 f"不在任何配方产物 / 战利品表 / Java 发放里（只能创造模式获得）")
    for i in sorted(reg):
        if i not in missing:
            rep.ok(i)

    print(f"  注册物品 {len(reg)} 个 · 有获取途径 {len(reg) - len(missing)} 个 · "
          f"缺失 {len(missing)} 个")
    print(f"  （配方 {len(from_recipe)} / 战利品 {len(from_loot)} / Java 发放 {len(from_java)}）")
    rep.summary()


def main() -> int:
    ap = argparse.ArgumentParser(description="忆海跨工作流资源一致性校验")
    ap.add_argument("--verbose", action="store_true", help="打印每个被检查的对象")
    ap.add_argument("--pending-detail", action="store_true",
                    help="逐条列出 pending 明细（默认只按缺失文件汇总）")
    args = ap.parse_args()

    rep = Report(args.verbose)
    check_models(rep)
    check_spells(rep)
    check_lang(rep)
    check_sounds(rep)
    check_registry_refs(rep)
    check_custom_data(rep)
    check_orphan_textures(rep)
    check_iss_school_assets(rep)
    check_effect_lang(rep)
    check_dead_config(rep)
    check_foreign_tags(rep)
    check_spell_effects(rep)
    check_effect_icons(rep)
    check_event_subscribers(rep)
    check_item_obtainability(rep)

    if rep.uncovered_items:
        # 必须放在汇总**之前**且独立成块：这些项没有计入 checked，
        # 混进 warn 明细就等于"检查项悄悄变少却没人发现"。
        print("\n" + "!" * 72)
        print("⚠️ 本次运行未覆盖以下检查项（总数会因此变少 —— 不等于『全通过』）：")
        for u in rep.uncovered_items:
            print("  … " + u)
        print("!" * 72)

    print("\n" + "=" * 72)
    print(f"检查项 {rep.checked} 个 · FAIL {len(rep.fails)} 条 · "
          f"pending {len(rep.pendings)} 条 · warn {len(rep.warns)} 条")

    if rep.pendings:
        # 同一个缺失的 NBT 会被多个池引用，汇总成"缺哪些文件"才有行动价值
        missing = sorted({
            p.split("data/mnemosyne/structures/")[1].split(".nbt")[0] + ".nbt"
            for p in rep.pendings if "data/mnemosyne/structures/" in p
        })
        print(f"\npending 明细（已知欠账，不阻塞退出码）：缺 {len(missing)} 个结构模板 NBT")
        if args.pending_detail:
            for p in rep.pendings:
                print("  … " + p)
        else:
            for m_ in missing:
                print(f"  … data/mnemosyne/structures/{m_}")
            print("  （加 --pending-detail 看是哪些池引用了它们）")

    if rep.fails:
        print("\nFAIL 明细：")
        for f in rep.fails:
            print("  " + f)
        return 1
    print("\n全部通过 ✅")
    if rep.warns:
        print("\nwarn 明细（不阻塞，但值得看一眼）：")
        for w in rep.warns:
            print("  " + w)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
