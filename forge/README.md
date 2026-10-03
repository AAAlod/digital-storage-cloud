# Forge 平台构建

Forge 1.20.1 / 47.4.10 使用独立 Gradle 构建，复用上级工程的 wrapper、版本属性和 common 源码选择。这样 ForgeGradle 与 Loom 不共享插件 classloader，也不建立第二份业务源码。

从仓库根目录使用 Java 17：

```powershell
.\gradlew.bat -p forge build --console=plain
```

当前处于移植阶段：接入器注册、菜单网络、服务器生命周期、可撤销 IItemHandler 库存、Tom 聚合器同卷去重、网络分析、扫描器统计及玩家迁移调度已接入；高级漏斗和完整客户端验收仍待完成。迁移限制原卷所有者，按 tick 预算执行，每批复核绑定与拓扑，支持取消、退出和停服终止；未决恢复状态阻止启动。`digitalstorage selftest` 覆盖共享及 Forge/Tom 回归；放置方块的世界夹具仅在专用 audit-world 明确配置时运行，驱动实际 Tom 周期入口验证单箱/双箱扫描和重建、电缆/代理/熔炉顶面权限及方块移除。夹具不是连续 tick 或旋转交互验收。完整运行验收完成前不使用构建产物替换玩家安装。

扫描器统计在分析时关联实际网络根与目标卷，观察 Tom 原始漏斗尝试；源和目标属于同一卷时只计一次，不枚举库存槽位。报告包括最近 1200 tick 内活跃数量、连续失败至少 3 次的数量和平均尝试间隔；禁用、冷却及缺少必需过滤器时不作为扫描尝试。每 200 tick 清理失效关联与过期条目，停服清理服务器会话。统计不改变 Tom 的转移数量、返回余量或冷却行为；Forge 批量优化仍待实现。

恢复会话与物理提取结算已接入迁移。`digitalstorage recovery list` 查看自己的恢复条目，`recovery deliver <id> [amount]` 显式交付到自己拥有的原目标卷。容量/策略拒绝保留待恢复数量；交付中断或刷盘失败的 `DELIVERING` 不允许重试，需要管理员核对外部持久化结果。

管理员可用 `digitalstorage transferincident list`、`inspect <id>` 查看事件，`acknowledge <id> <explanation>` 保存明确核对说明和收据。事件只记录观察，核对事件不会交付物品或解除 `DELIVERING`。selftest、diagnostics、flush 和 transferincident 均要求权限等级 2。

专门交付核对使用权限等级 2 的 `digitalstorage recoveryadmin inspect <id>` 取得当前尝试标识，再执行 `recoveryadmin reconcile <id> <attempt> delivered|not_delivered <explanation>`。管理员必须基于外部持久化证据确认本次交付的结果并说明依据。已交付从条目扣除本次量，未交付恢复待交付状态；核对保存管理员收据，不直接改变卷内物品。状态与收据同文件替换，失败保留待刷盘记录并阻止再次交付；新尝试使用不同标识，旧请求拒绝。

最终 Forge 工作 JAR 为 `build/libs/digital-storage-cloud-forge-<version>.jar`。NightConfig 单独 relocation；Tom 是外部依赖，不嵌入发行 JAR。`build` 同时检查共享源码/字节码平台边界、元数据版本、重复 ZIP entry 和禁止依赖载荷。

`libs/toms_storage-1.20-1.7.1.jar` 是 MIT 许可的精确 Tom 编译依赖（SHA-256 `08552be86f960111e501227707dc089c91b8a55f6c1be09baee188338576ff9b`），由 ForgeGradle 重映射用于开发，运行环境仍需单独安装 Tom；许可见上级 `licenses/Toms-Storage-LICENSE`。聚合 Mixin 只补充明确的 DSC 卷身份，过滤和未知包装器保留原接口。
