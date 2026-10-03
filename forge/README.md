# Forge 平台构建

Forge 1.20.1 / 47.4.10 使用独立 Gradle 构建，复用上级工程的 wrapper、版本属性和 common 源码选择。这样 ForgeGradle 与 Loom 不共享插件 classloader，也不建立第二份业务源码。

从仓库根目录使用 Java 17：

```powershell
.\gradlew.bat -p forge build --console=plain
```

当前处于移植阶段：接入器注册、菜单网络、服务器生命周期、可撤销 IItemHandler 库存和 Tom 聚合器同卷去重已接入；高级漏斗及 Tom 网络分析/迁移仍待实现。`digitalstorage selftest` 验证共享回归、真实 ForgeCaps、库存模拟/余量/稳定槽位、绑定生命周期和实际 Tom 聚合器/过滤器，diagnostics 明确报告未接通部分；完整运行验收完成前不使用构建产物替换玩家安装。

最终 Forge 工作 JAR 为 `build/libs/digital-storage-cloud-forge-<version>.jar`。NightConfig 单独 relocation；Tom 是外部依赖，不嵌入发行 JAR。`build` 同时检查共享源码/字节码平台边界、元数据版本、重复 ZIP entry 和禁止依赖载荷。

`libs/toms_storage-1.20-1.7.1.jar` 是 MIT 许可的精确 Tom 编译依赖（SHA-256 `08552be86f960111e501227707dc089c91b8a55f6c1be09baee188338576ff9b`），由 ForgeGradle 重映射用于开发，运行环境仍需单独安装 Tom；许可见上级 `licenses/Toms-Storage-LICENSE`。聚合 Mixin 只补充明确的 DSC 卷身份，过滤和未知包装器保留原接口。
