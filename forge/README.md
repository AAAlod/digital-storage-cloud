# Forge 平台构建

Forge 1.20.1 / 47.4.10 使用独立 Gradle 构建，复用上级工程的 wrapper、版本属性和 common 源码选择。这样 ForgeGradle 与 Loom 不共享插件 classloader，也不建立第二份业务源码。

从仓库根目录使用 Java 17：

```powershell
.\gradlew.bat -p forge build --console=plain
```

当前处于移植阶段：接入器注册、菜单网络、服务器生命周期和可撤销 IItemHandler 库存已接入；高级漏斗及 Tom 去重/分析/迁移仍待实现。`digitalstorage selftest` 验证共享回归、真实 ForgeCaps、库存模拟/余量/稳定槽位和绑定生命周期，diagnostics 明确报告未接通部分；完整运行验收完成前不使用构建产物替换玩家安装。

最终 Forge 工作 JAR 为 `build/libs/digital-storage-cloud-forge-<version>.jar`。NightConfig 单独 relocation；Tom 是外部依赖，不嵌入发行 JAR。`build` 同时检查共享源码/字节码平台边界、元数据版本、重复 ZIP entry 和禁止依赖载荷。
