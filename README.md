# 忆海 · Mnemosyne

Iron's Spells 'n Spellbooks（铁魔法）的第三方学派附属模组 —— **记忆 / 认知**流派。

> 踏入忆海，代价是被遗忘。

## 特性

- **忆格**：以记忆碎片承载法术，容量有限（基础 3 / 上限 5），取舍即策略
- **遗忘**：从怪物身上抹去记忆，掠夺其能力为己用
- **忆者遗迹**：世界各处沉睡着被遗忘的档案馆与记忆碑
- **忆魇**：徘徊在记忆废墟之间的敌对实体

## 环境要求

| 组件 | 版本 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.4.0+ |
| Iron's Spells 'n Spellbooks | 1.20.1-3.15.0+（开发用 3.16.3） |
| Curios API | 5.14.1+1.20.1 |
| irons_lib | 1.20.1-2.x（ISS 的强制前置） |

以下为运行时前置，由 ISS 传递依赖引入：GeckoLib 4.7.1.1、player-animation-lib 1.0.2-rc1+1.20、Caelus 3.1.0+1.20。

## 从源码构建

```bash
./gradlew build
```

产物在 `build/libs/mnemosyne-<version>.jar`，可直接丢进 `mods` 目录。

`build` 默认还会把 jar 复制一份到本地 PCL2 实例目录，方便直接开游戏测。CI / 不需要时重定向掉：

```bash
./gradlew build -Ppcl2_mods_dir=./build/ci-mods
# 或干脆跳过
./gradlew build -x copyToMinecraftMods
```

只改了 Java 代码、想快点验证编译：

```bash
./gradlew compileJava
```

## 云端构建（GitHub Actions）

每次 push / PR 都会触发 `.github/workflows/build.yml`：JDK 17（**不是 21**）→ `./gradlew build` → 把 jar 上传为 Artifact。
在仓库的 **Actions → 对应 run → Artifacts** 里可以直接下载，有效期 30 天。

首次运行因为要反编译 Minecraft、拉取 Forge 与 ISS 依赖，可能要 20~40 分钟；之后命中 Gradle 缓存通常在 5 分钟内完成。

## 目录结构

```
src/main/java/com/etbs31/mnemosyne/   源码
src/main/resources/assets/mnemosyne/  模型 / 贴图 / 语言文件
docs/                                 设计文档
docs/tech/                            实现与实测结论
tools/                                资源自检与素材生成脚本
```

提交前建议跑一次资源自检：

```bash
python tools/check_resources.py
```

## 许可与版权

本项目源码以 **MIT** 发布。

Iron's Spells 'n Spellbooks 为 **All Rights Reserved**。本仓库**不包含** ISS 或任何其他模组的原始资源（贴图 / 音效 / 结构文件）——
模组内的素材均为自行生成，代码只通过 Maven 依赖在编译期引用 ISS 的 API，运行时依赖玩家自行安装 ISS。
仓库中亦不包含任何解包出来的第三方副本。

## 致谢

基于 [Iron's Spells 'n Spellbooks](https://www.curseforge.com/minecraft/mc-mods/irons-spells-n-spellbooks) 构建。
