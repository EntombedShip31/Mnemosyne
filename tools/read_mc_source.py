#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
读 Minecraft / Forge 反编译源码（本项目的"查 API 第一手段"）
============================================================

【为什么需要它】

这个项目反复踩的坑是"我以为 API 是这样，其实不是"：
`CastType` 没有 `CHARGE`、`MobEffectInstance` 没有自定义 NBT、
`AbstractSpell` 只有 3 个抽象方法、`getDamageSource(Entity)` 是 final……

**这些结论全部来自读源码，不是来自记忆。** 所以需要一个随手能用的工具：
不用 IDE、不用解压整个 jar，命令行里直接按名字把某个 `.java` 打出来，
或者全库搜一个字符串。

三个数据源（存在哪个就用哪个）：

| 源 | 内容 |
|---|---|
| `forge-…-mapped_official_1.20.1-sources.jar` | **MC + Forge 的反编译源码**，首选 |
| ISS 的 `api` jar 附带的部分 `.java` | ISS 的 API 源码 |
| ISS 的完整 jar（`.class`） | 只有字节码时用 javap |

【用法】

    # 打印一个类（可以只写类名后缀，会自动匹配）
    python tools/read_mc_source.py JigsawPlacement

    # 打印多个
    python tools/read_mc_source.py JigsawPlacement StructureTemplateManager

    # 全库搜字符串，输出 文件:行号: 行内容
    python tools/read_mc_source.py --grep "final_state"

    # 列出所有 .java
    python tools/read_mc_source.py --list

⚠️ 本机 Git Bash 没有 `strings`，且 `/tmp` 不是 Windows 能认的真实路径 ——
所以这个脚本用 Python 的 zipfile 直接读，不落临时文件。
"""

from __future__ import annotations

import argparse
import glob
import os
import sys
import zipfile

# MC + Forge 反编译源码 jar（ForgeGradle 生成）
GRADLE_CACHE = os.path.expanduser("~/.gradle/caches/forge_gradle")

SOURCE_JAR_GLOBS = [
    os.path.join(GRADLE_CACHE, "minecraft_user_repo", "net", "minecraftforge", "forge",
                 "1.20.1-*", "*_mapped_official_1.20.1-sources.jar"),
]


def find_source_jars() -> list[str]:
    out: list[str] = []
    for pat in SOURCE_JAR_GLOBS:
        out.extend(sorted(glob.glob(pat)))
    return out


def open_jar(path: str) -> zipfile.ZipFile:
    return zipfile.ZipFile(path)


def entries(zf: zipfile.ZipFile) -> list[str]:
    return [n for n in zf.namelist() if n.endswith(".java")]


def read_entry(zf: zipfile.ZipFile, name: str) -> str:
    return zf.read(name).decode("utf-8", "replace")


def cmd_print(jars: list[str], names: list[str]) -> int:
    rc = 0
    for want in names:
        needle = want if want.endswith(".java") else want + ".java"
        hits: list[tuple[str, str]] = []
        for jar in jars:
            with open_jar(jar) as zf:
                for n in entries(zf):
                    if n.endswith("/" + needle) or n == needle:
                        hits.append((n, read_entry(zf, n)))
        if not hits:
            print(f"!! 没找到 {want}")
            rc = 1
            continue
        for n, src in hits:
            print("=" * 30, n, "=" * 30)
            sys.stdout.write(src if src.endswith("\n") else src + "\n")
    return rc


def cmd_grep(jars: list[str], pattern: str) -> int:
    rc = 1
    for jar in jars:
        with open_jar(jar) as zf:
            for n in entries(zf):
                try:
                    src = read_entry(zf, n)
                except Exception:  # noqa: BLE001
                    continue
                if pattern not in src:
                    continue
                for i, line in enumerate(src.splitlines(), 1):
                    if pattern in line:
                        print(f"{n}:{i}: {line.strip()}")
                        rc = 0
    return rc


def cmd_list(jars: list[str]) -> int:
    for jar in jars:
        print(f"### {jar}")
        with open_jar(jar) as zf:
            for n in entries(zf):
                print("  " + n)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="读 MC/Forge 反编译源码")
    ap.add_argument("names", nargs="*", help="类名（可只写后缀）")
    ap.add_argument("--grep", metavar="STR", help="全库搜字符串")
    ap.add_argument("--list", action="store_true", help="列出所有 .java")
    ap.add_argument("--jar", action="append", default=[], help="额外指定一个 sources jar")
    args = ap.parse_args()

    jars = args.jar + find_source_jars()
    jars = [j for j in jars if os.path.isfile(j)]
    if not jars:
        print("找不到 MC 反编译源码 jar。先跑一次 `./gradlew compileJava` 让 ForgeGradle 生成它。")
        return 1

    if args.list:
        return cmd_list(jars)
    if args.grep:
        return cmd_grep(jars, args.grep)
    if not args.names:
        ap.print_help()
        return 1
    return cmd_print(jars, args.names)


if __name__ == "__main__":
    sys.exit(main())
