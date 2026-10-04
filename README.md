<p align="center">
  <img src="src/main/resources/assets/digitalstorage/icon.png" alt="Digital Storage Cloud 图标" width="240">
</p>

<h1 align="center">汤姆的简易存储：云上加仓</h1>

<p align="center">Digital Storage Cloud · 给 Tom's Simple Storage 换一个更适合大宗物品的存储后端</p>
<p align="center"><strong>Minecraft 1.20.1 · Fabric / Forge · Java 17</strong></p>

<p align="center">
  <a href="https://github.com/AAAlod/digital-storage-cloud/releases">GitHub Releases</a> ·
  <a href="https://www.curseforge.com/minecraft/mc-mods/digital-storage-cloud">CurseForge</a> ·
  <a href="https://www.mcmod.cn/class/30175.html">MC百科</a> ·
  <a href="PLAYER_GUIDE_zh_CN.md">玩家指南</a> ·
  <a href="CHANGELOG.md">更新日志</a> ·
  <a href="https://github.com/AAAlod/digital-storage-cloud/issues">Issues</a>
</p>

## 为什么会有 DSC

汤姆的简易存储（Tom's Simple Storage）提供存储终端、合成终端和存储网络。仓库扩大后，箱子等容器和负责搬运物品的存储漏斗也会增多，网络扫描和物品传输随之增加。

DSC 将大量同类物品集中保存在数字存储卷中，并为存储漏斗提供批量传输。它的目标是减少容器数量和传输次数，同时继续使用 Tom 的终端和网络。

圆石、铁锭和农作物等数量较多的物品适合放进 DSC；工具、装备和带复杂 NBT 的物品可以留在普通容器中，通过同一个 Tom 终端访问。卷所有者也可开启不可堆叠物品存储，但需要服务端允许。

## 两个核心部分

### 数字存储卷

存储卷属于玩家账户，不依附于某一个方块。数字存储访问器负责把卷接入 Tom 网络，多个访问器可以指向同一个卷；同一网络中重复接入时会按卷身份去重。

容量按物品种类计算：默认等级可存放 64 / 128 / 256 / 512 / 1024 种物品，不同 NBT 分开计数。每种物品最多存入 `2,147,483,647` 件；旧存档中的超限物品仍可取出，但不能继续增加。等级容量和升级材料可以通过数据包修改。

### 存储漏斗

DSC 优化 Tom 的基础存储漏斗，并添加高级存储漏斗。两者沿用 Tom 的物品过滤与红石控制，支持批量搬运；默认每批最多传输 16 和 64 件。传输成功后有冷却时间，连续失败时会延长尝试间隔，漏斗搜索存储连接器的时机也会分散到不同 tick。

这些调整用于减少逐件传输和集中搜索造成的开销。

## 其他功能

- 对 Tom 的部分网络扫描、存储端点和物品传输路径做了配套优化。
- 访问器提供基础的网络状态检查和迁移建议，可把适合的大宗物品分批迁入当前卷。
- 存储数据分片保存，损坏记录单独隔离；较新格式不会被旧版本直接重写。
- Fabric 与 Forge 共用存储、权限、配置和迁移业务，各自适配加载器和 Tom 接口。

网络分析可以辅助检查容器数量、重复接入的存储卷和迁移候选。

## 适合什么情况

DSC 主要面向需要存放大量同类物品、持续搬运资源的 Tom 网络，适合希望保留 Tom 操作方式的玩家和整合包作者。

是否能降低服务器开销，需要结合实际网络测试。DSC 作为 Tom 的扩展使用，仍需 Tom 的终端和网络。

**存储卷所有权控制的是创建、绑定、重命名、升级等管理操作，不限制 Tom 网络中的物品存取。** 卷接入网络后，能访问该网络的玩家就能存取卷内物品。私人仓库可配合 FTB Chunks 等领地或权限模组使用，并检查其设置是否限制了终端、无线终端及相关方块的访问。

## 安装

客户端与服务端均需安装相同版本的 DSC。选择与你的加载器对应的发行 JAR，更新时先移出旧 DSC 文件。`sources.jar` 是源码，不放进 `mods`。

| 平台 | DSC 文件 | 已验证基线 |
| --- | --- | --- |
| Fabric | `digital-storage-cloud-fabric-<版本>.jar` | Fabric Loader 0.15.11、Fabric API 0.92.2+1.20.1、Tom's Storage Fabric 1.7.1 |
| Forge | `digital-storage-cloud-forge-<版本>.jar` | Forge 47.4.10、Tom's Storage Forge 1.7.1 |

两端均使用 Minecraft 1.20.1 和 Java 17。Tom's Simple Storage 是必需前置，需要另装与加载器匹配的版本。表中列的是当前验证基线，并不表示其他 Tom 版本一定不能运行；超出该组合请自行测试。

发行文件可从 [GitHub Releases](https://github.com/AAAlod/digital-storage-cloud/releases) 或 [CurseForge](https://www.curseforge.com/minecraft/mc-mods/digital-storage-cloud) 下载。合成、绑定、升级和常见排障见[中文玩家使用指南](PLAYER_GUIDE_zh_CN.md)。更新前建议备份世界。

## 给整合包作者和服主

`config/digitalstorage.toml` 用来调整服务器侧行为，包括每名玩家可创建的卷数、不可堆叠物品策略、漏斗优化与批量大小、扫描错峰、迁移预算和持久化节奏等。修改后可用 `/dsc reloadconfig` 重新载入，需要权限等级 2。

存储等级本身是数据驱动的：默认 64–1024 种容量和升级材料可以通过数据包修改，不必改代码。

配置项比较多，推荐直接看 MC 百科的 [DSC 配置文件详解（1.1.12 以上）](https://www.mcmod.cn/post/6753.html)。

## 常用命令

日常卷管理可以在访问器界面完成，也可以使用命令：

```text
/digitalstorage volume create <name>
/digitalstorage volume list
/digitalstorage volume rename <volume UUID> <name>
/digitalstorage volume delete <volume UUID>

/digitalstorage accessor bind <x> <y> <z> <volume UUID>
/digitalstorage accessor clear <x> <y> <z>
/digitalstorage accessor inspect <x> <y> <z>
```

`/dsc` 是短别名。删除卷前必须先清空物品，并解除仍处于加载状态的访问器绑定。管理员诊断和恢复核对命令见 [Forge 平台说明](forge/README.md)；Fabric 的命令和排障说明见[玩家指南](PLAYER_GUIDE_zh_CN.md)。

## 当前限制

- 跨 Fabric / Forge 的世界转换尚未验证。
- Fabric 1.2.0 的部分迁移和重负载漏斗场景较旧版仍有额外 CPU 开销，后续版本会继续优化；实际收益取决于网络和服务器环境。
- 网络评分是启发式建议，不代表 TPS 或 MSPT 测量结果。
- 卷所有权不限制接入它的 Tom 网络中的物品存取。

## 源码与构建

需要 Java 17。仓库使用 Gradle Wrapper，根工程构建 Fabric，`forge/` 使用独立 ForgeGradle 构建；`common/` 会单独编译两端共享的业务代码，并检查平台依赖边界。

Windows PowerShell：

```powershell
.\gradlew.bat build
.\gradlew.bat -p forge build
```

Linux / macOS：

```sh
sh ./gradlew build
sh ./gradlew -p forge build
```

发行产物分别位于：

- Fabric：`build/libs/digital-storage-cloud-fabric-<版本>.jar`
- Forge：`forge/build/libs/digital-storage-cloud-forge-<版本>.jar`

开发、shadow 和 sources JAR 不是玩家安装包。Tom 1.7.1 的编译依赖已经保存在仓库中，但不会被打进 DSC 的发行 JAR。

进一步的实现说明放在对应模块：

- [共享模块](common/README.md)
- [Forge 平台](forge/README.md)
- [Fabric 适配](src/main/java/dev/kehai/digitalstorage/platform/fabric/README.md)
- [存储与持久化](src/main/java/dev/kehai/digitalstorage/storage/README.md)
- [网络分析与迁移](src/main/java/dev/kehai/digitalstorage/optimization/README.md)
- [本地依赖](libs/README.md)
- [纹理工具](tools/README.md)

## 开发说明

开发过程中使用了 OpenAI Codex 等 AI 工具辅助代码实现、审查、文档和测试规划。AI 生成内容不会直接作为发布依据；正式版本仍以实际构建、测试和人工检查结果为准。

项目使用 MIT License，版本变化见 [CHANGELOG](CHANGELOG.md)。
