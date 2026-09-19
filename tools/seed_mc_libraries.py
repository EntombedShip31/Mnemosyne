# -*- coding: utf-8 -*-
"""
把 PCL2 本地 .minecraft/libraries 的 MC 运行库，按 ForgeGradle 的
caches/forge_gradle/maven_downloader 布局原样铺进缓存，并补 .md5。

用途：forge_gradle 缓存被误删后，listLibraries 会走网络重下 ~130 个 jar，
本机代理会在某个连接上无限挂住。用本地已有副本顶掉，可以完全不碰网络。

用法：python tools/seed_mc_libraries.py

⚠️ 本文件必须放在 tools/（已入库）。放在 build/ 会被 .gitignore 排除，
   下次清理 build/ 就丢了 —— 而它是缓存被误删后唯一的离线救急手段。
参考：docs/tech/13_构建环境与缓存保护.md §2.7
"""
import hashlib
import json
import os
import shutil
import sys

GRADLE_HOME = r"C:\Users\Etbs31\.gradle"
VERSION_JSON = os.path.join(
    GRADLE_HOME, r"caches\forge_gradle\mcp_repo\versions\1.20.1\version.json"
)
CACHE = os.path.join(GRADLE_HOME, r"caches\forge_gradle\maven_downloader")
PCL2 = r"D:\PCL2\.minecraft\libraries"


def md5_of(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    if not os.path.isfile(VERSION_JSON):
        print("version.json not found:", VERSION_JSON)
        return 1
    if not os.path.isdir(PCL2):
        print("PCL2 libraries not found:", PCL2)
        return 1

    with open(VERSION_JSON, "r", encoding="utf-8") as f:
        vj = json.load(f)

    needed = []  # (rel_path,)
    for lib in vj.get("libraries", []):
        dl = lib.get("downloads") or {}
        art = dl.get("artifact")
        if art and art.get("path"):
            needed.append(art["path"])
        for cls in (dl.get("classifiers") or {}).values():
            if cls and cls.get("path"):
                needed.append(cls["path"])

    # 去重
    needed = sorted(set(needed))
    print("version.json 声明的库文件数:", len(needed))

    copied, present, missing = 0, 0, []
    for rel in needed:
        rel = rel.replace("/", os.sep)
        dst = os.path.join(CACHE, rel)
        if os.path.isfile(dst) and os.path.isfile(dst + ".md5"):
            present += 1
            continue
        src = os.path.join(PCL2, rel)
        if not os.path.isfile(src):
            missing.append(rel)
            continue
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
        with open(dst + ".md5", "w", encoding="utf-8") as f:
            f.write(md5_of(dst))
        copied += 1

    # 已有 jar 但缺 .md5 的补上
    fixed = 0
    for root, _dirs, files in os.walk(CACHE):
        for fn in files:
            if fn.endswith(".jar") and not os.path.isfile(os.path.join(root, fn + ".md5")):
                p = os.path.join(root, fn)
                try:
                    with open(p + ".md5", "w", encoding="utf-8") as f:
                        f.write(md5_of(p))
                    fixed += 1
                except OSError:
                    pass

    print("缓存已存在(跳过):", present)
    print("从 PCL2 拷入:", copied)
    print("补写 .md5:", fixed)
    print("PCL2 里也没有（仍需联网）:", len(missing))
    for m in missing[:20]:
        print("   -", m)
    if len(missing) > 20:
        print("   ... 另有", len(missing) - 20, "个")
    return 0


if __name__ == "__main__":
    sys.exit(main())
