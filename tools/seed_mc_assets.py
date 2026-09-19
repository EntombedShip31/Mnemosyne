# -*- coding: utf-8 -*-
"""
把 PCL2 的 MC 资源对象按 1.20.1 索引（5.json）精确补进 ForgeGradle 的
caches/forge_gradle/assets/objects，避免 dev 服务端启动时联网重下
~3500 个原版音效（Gradle 不走代理，会大量超时）。

用法：python tools/seed_mc_assets.py

⚠️ 本文件必须放在 tools/（已入库）。放在 build/ 会被 .gitignore 排除，
   下次清理 build/ 就丢了 —— 而它是缓存被误删后唯一的离线救急手段。
参考：docs/tech/13_构建环境与缓存保护.md §2.9
"""
import json
import os
import shutil

FG = r"C:\Users\Etbs31\.gradle\caches\forge_gradle\assets"
PCL2 = r"D:\PCL2\.minecraft\assets"
INDEX = "5.json"


def main():
    idx_path = os.path.join(FG, "indexes", INDEX)
    with open(idx_path, "r", encoding="utf-8") as f:
        idx = json.load(f)

    objs = idx.get("objects", {})
    hashes = {}
    for _name, meta in objs.items():
        h = meta.get("hash")
        if h:
            hashes[h] = meta.get("size", 0)

    print("索引声明的对象数:", len(hashes))

    copied, present, missing = 0, 0, []
    for h in hashes:
        dst = os.path.join(FG, "objects", h[:2], h)
        if os.path.isfile(dst):
            present += 1
            continue
        src = os.path.join(PCL2, "objects", h[:2], h)
        if not os.path.isfile(src):
            missing.append(h)
            continue
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
        copied += 1

    print("已存在(跳过):", present)
    print("从 PCL2 拷入:", copied)
    print("PCL2 也没有（仍需联网）:", len(missing))
    for m in missing[:10]:
        print("   -", m)
    return 0


if __name__ == "__main__":
    main()
