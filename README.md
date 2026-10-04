<p align="center">
  <img src="src/main/resources/assets/digitalstorage/icon.png" alt="Digital Storage Cloud 图标" width="240">
</p>

<h1 align="center">汤姆的简易存储：云上加仓</h1>

<p align="center">Digital Storage Cloud · 为 Tom's Simple Storage 提供玩家云端存储卷</p>
<p align="center"><strong>Minecraft 1.20.1 · Fabric / Forge · Java 17</strong></p>

## 简介

DSC 是 Tom's Simple Storage 的存储扩展。物品存放在玩家拥有的逻辑存储卷中，多个轻量访问器可以绑定同一卷，再接入 Tom 网络。Tom 的终端继续负责查看、搜索、存取和合成。

本模组主要面向大宗可堆叠物品；工具、装备等特殊物品可继续使用普通箱子。卷所有者可以在服务端允许时显式开启不可堆叠物品存储。

## 功能

- 玩家拥有的存储卷，容量等级通过数据包定义。
- 多个访问器绑定同一卷，Tom 网络按卷身份去重。
- 普通与高级库存漏斗的批量搬运、过滤、退避和连接器扫描错峰。
- 网络分析、诊断及按 tick 预算执行的迁移工具。
- 分片异步持久化、损坏文件隔离和存储格式版本保护。
- Fabric 与 Forge 共用存储、权限和迁移业务，各自接入加载器与 Tom 接口。

## 安装

选择对应加载器的文件并移出旧 DSC JAR；sources.jar 是源码，不放入 mods 文件夹。

| 平台 | DSC 文件 | 已验证依赖 |
| --- | --- | --- |
| Fabric | `digital-storage-cloud-fabric-<版本>.jar` | Fabric Loader 0.15.11、Fabric API 0.92.2+1.20.1、Tom's Storage Fabric 1.7.1 |
| Forge | `digital-storage-cloud-forge-<版本>.jar` | Forge 47.4.10、Tom's Storage Forge 1.7.1 |

两端均使用 Minecraft 1.20.1 和 Java 17。Tom's Storage 是必需前置，请另装与加载器匹配的版本。服务端及需要打开管理界面的客户端均须安装对应模组和依赖；Forge 不需要 Fabric API。

表中列出已验证基线，其他版本的兼容性以实际测试和模组元数据约束为准。更新前备份世界；Fabric 的已有物品编码保持兼容，但尚未验证 Fabric 与 Forge 之间的世界转换。

合成、绑定、升级与排障见[中文玩家使用指南](PLAYER_GUIDE_zh_CN.md)。

## 常用命令

~~~text
/digitalstorage volume create <name>
/digitalstorage volume list
/digitalstorage volume rename <volume UUID> <name>
/digitalstorage volume delete <volume UUID>

/digitalstorage accessor bind <x> <y> <z> <volume UUID>
/digitalstorage accessor clear <x> <y> <z>
/digitalstorage accessor inspect <x> <y> <z>
~~~

/dsc 为短别名。只有空卷且没有已加载访问器绑定时才能删除卷。管理员还可使用：

~~~text
/digitalstorage stats
/digitalstorage stats deep
/digitalstorage diagnostics
/digitalstorage probe <x> <y> <z>
/digitalstorage selftest
/digitalstorage benchmark
/digitalstorage reloadconfig
/digitalstorage flush
~~~

## 存储规则

- 默认容量为 64、128、256、512、1024 种精确物品变种。不同 NBT 的物品分别计数。
- 默认四次升级分别消耗 16、32、64、128 个钻石。数据包可调整等级，但每次升级只使用一种资源，不使用经验。
- 每种精确变种的新插入上限为 2,147,483,647 件；已有超限存储仍可取出。
- Fabric 保留嵌套事务回滚；Forge 处理部分接受与异常返回栈，未决物品保留托管状态供核对。
- 服务端 allowUnstackableItems 默认开启，但新卷仍默认拒收不可堆叠物品，须由所有者选择允许。旧 rejectUnstackableItems 配置按反向值迁移；关闭接收不妨碍取出已有物品。
- 物品过滤、安全策略和新变种 NBT 限额仍生效。
- 损坏卷单独隔离并保留原始 NBT；较新存储格式不会被旧版模组重写。

1.2.0 的部分迁移和重负载漏斗场景仍有额外 CPU 开销，后续补丁继续优化；当前不承诺所有场景都更快或服务器 TPS 提升。

## 源码与构建

使用 Java 17 JDK，设置 JAVA_HOME，将其 bin 加入 PATH。仓库 wrapper 使用 Gradle 8.6，无需另装 Gradle。源码采用 Minecraft 1.20.1 官方 Mojang 映射；Fabric 发行时重映射为 intermediary。

common 独立编译共享源码；根工程构建 Fabric，forge 子目录构建 Forge。构建检查源码归属和平台依赖边界。

Windows PowerShell，从仓库根目录运行：

~~~powershell
.\gradlew.bat build
.\gradlew.bat -p forge build
~~~

Linux/macOS：

~~~sh
sh ./gradlew build
sh ./gradlew -p forge build
~~~

首次构建需联网获取 Gradle/Maven 依赖。各端 Tom's Storage 1.7.1 编译依赖已保存在仓库，开发运行依赖由构建准备，不嵌入发行 JAR。构建不依赖本地世界或仓库外的文件。

- Fabric 产物：`build/libs/digital-storage-cloud-fabric-<版本>.jar`。
- Forge 产物：`forge/build/libs/digital-storage-cloud-forge-<版本>.jar`。
- 版本取自 gradle.properties 的 mod_version。开发、shadow 和 sources JAR 不是玩家安装包。

开发客户端/服务器使用 runClient、runServer；Forge 任务加 -p forge。服务器须先接受 EULA。更多平台构建说明见 [Forge 模块](forge/README.md)。

模块说明：[存储与持久化](src/main/java/dev/kehai/digitalstorage/storage/README.md)、[迁移与优化](src/main/java/dev/kehai/digitalstorage/optimization/README.md)、[本地依赖](libs/README.md)、[纹理工具](tools/README.md)。发行变更见 [CHANGELOG](CHANGELOG.md)。

## AI 辅助开发

本项目使用包括 OpenAI Codex 在内的 AI 工具协助实现、代码审查、文档与测试规划。维护者负责发行前的审查和验收，并对代码、文档及发布产物承担责任。
