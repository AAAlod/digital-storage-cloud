# 本地编译依赖

仓库直接保存了 DSC 当前验证基线所需的 Tom's Simple Storage 1.7.1 JAR，这样普通 clone 后就可以编译，不需要先手动下载一份特定版本的 Tom。

- Fabric：`libs/toms_storage_fabric-1.20-1.7.1.jar`
- Forge：`forge/libs/toms_storage-1.20-1.7.1.jar`

根 `build.gradle` 会为 Loom 准备兼容编译副本，并从 Tom 的 Fabric JAR 中提取本地开发运行需要的 Cloth Config 与 Basic Math。Forge 也会为开发运行准备自己的兼容副本。

这些处理只服务于**编译和开发运行**。DSC 的发行 JAR 不会把 Tom、Fabric API、Cloth Config 等前置依赖重新打包进去；玩家仍需正常安装与加载器匹配的 Tom。

精确版本、上游来源、SHA-256 和许可记录见[第三方声明](../THIRD_PARTY_NOTICES.md)，Tom 的许可文本位于 [`licenses/Toms-Storage-LICENSE`](../licenses/Toms-Storage-LICENSE)。
