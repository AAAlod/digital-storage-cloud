# 共享模块

`common` 编译 Fabric 和 Forge 共用的 DSC 业务代码。

它从 `../src/main/java` 中选择共享源码单独编译，清单由 `source-layout.gradle` 维护。这里可以依赖 Minecraft 1.20.1 官方 Mojang 映射、NightConfig 和普通 Java 类型，但不能直接引用 Fabric、Forge 或 Tom 的平台接口。

目前放在共享层的主要内容包括存储卷与账户、数量账本、配置、等级、权限策略、屏幕状态以及迁移策略。注册、加载器事件、库存接口、Tom 拓扑发现和外部库存的转移结算仍由各平台负责。

## 为什么要单独编译

共享源码最终会分别进入 Fabric 和 Forge 发行包。如果这里只靠“约定”不引用平台类，很容易在重构时无意中把 Fabric 或 Forge 类型带进来，所以 `verifySharedBoundary` 会同时检查源码和已编译 class 的常量池，包括反射字符串。

根工程的 `check` / `build` 会执行这项检查。Forge 构建也会用自己的环境重新编译同一份共享源码并做对应边界验证。

通用业务在共享层维护；依赖 Fabric Transfer API、Forge `IItemHandler` 或 Tom 内部类型的适配代码分别放在平台模块。
