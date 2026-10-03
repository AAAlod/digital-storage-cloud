# Forge 平台构建

高级库存漏斗已注册并随包提供合成、掉落与挖掘标签，复用 Tom 原始实体类型、过滤和红石行为。通过 Forge access transformer 扩展该实体类型的合法方块集合，保留原 Tom 方块；不会另建重复实体类型。当前仍使用原始单件转移，批量优化待后续阶段完成。

普通及高级漏斗均保存独立转移状态；旧存档没有该字段时保持就绪，有托管或不确定状态时阻止原始提取。保存/重载保留过滤物品，未知版本或错误类型的数据原样保存；已有未决实例不会被重复加载覆盖。安全转移内核已验证，但启用批量前仍需接入卸载/拆除保留、余量交付和管理员核对。

Forge 1.20.1 / 47.4.10 使用独立 Gradle 构建，复用上级工程的 wrapper、版本属性和 common 源码选择。这样 ForgeGradle 与 Loom 不共享插件 classloader，也不建立第二份业务源码。

从仓库根目录使用 Java 17：

```powershell
.\gradlew.bat -p forge build --console=plain
```

`runServer`/`runClient` 使用原始依赖生成仅开发运行的兼容副本：Toml 3.9.0 去除无版本目录的 Multi-Release 声明；Tom 保留实际 dist 判断，避免专用开发服务器提前验证客户端回调。原依赖、编译副本及发行依赖均不替换，发行仍只重定位 NightConfig、单独安装原始 Tom。开发任务显式加载 DSC Mixin 配置；ASM 使用当前 Gradle 分发自带库。服务端运行可加 `-x downloadAssets` 跳过客户端声音等资源，`-PdscRunDir=<目录>` 设置相对于 `forge/` 的独立运行目录。

当前处于移植阶段：接入器注册、菜单网络、服务器生命周期、可撤销 IItemHandler 库存、Tom 聚合器同卷去重、网络分析、扫描器统计及玩家迁移调度已接入；高级漏斗批量优化和完整客户端验收仍待完成。迁移限制原卷所有者，按 tick 预算执行，每批复核绑定与拓扑，支持取消、退出和停服终止；未决恢复状态阻止启动。`digitalstorage selftest` 覆盖共享及 Forge/Tom 回归；放置方块的世界夹具仅在专用 audit-world 明确配置时运行，驱动实际 Tom 周期入口验证单箱/双箱扫描和重建、电缆/代理/熔炉顶面权限及方块移除。夹具不是连续 tick 或旋转交互验收。完整运行验收完成前不使用构建产物替换玩家安装。

扫描器统计在分析时关联实际网络根与目标卷，观察 Tom 原始漏斗尝试；源和目标属于同一卷时只计一次，不枚举库存槽位。报告包括最近 1200 tick 内活跃数量、连续失败至少 3 次的数量和平均尝试间隔；禁用、冷却及缺少必需过滤器时不作为扫描尝试。每 200 tick 清理失效关联与过期条目，停服清理服务器会话。统计不改变 Tom 的转移数量、返回余量或冷却行为；Forge 批量优化仍待实现。

恢复会话与物理提取结算已接入迁移。`digitalstorage recovery list` 查看自己的恢复条目，`recovery deliver <id> [amount]` 显式交付到自己拥有的原目标卷。容量/策略拒绝保留待恢复数量；交付中断或刷盘失败的 `DELIVERING` 不允许重试，需要管理员核对外部持久化结果。

管理员可用 `digitalstorage transferincident list`、`inspect <id>` 查看事件，`acknowledge <id> <explanation>` 保存明确核对说明和收据。事件只记录观察，核对事件不会交付物品或解除 `DELIVERING`。selftest、diagnostics、flush 和 transferincident 均要求权限等级 2。

专门交付核对使用权限等级 2 的 `digitalstorage recoveryadmin inspect <id>` 取得当前尝试标识，再执行 `recoveryadmin reconcile <id> <attempt> delivered|not_delivered <explanation>`。管理员必须基于外部持久化证据确认本次交付的结果并说明依据。已交付从条目扣除本次量，未交付恢复待交付状态；核对保存管理员收据，不直接改变卷内物品。状态与收据同文件替换，失败保留待刷盘记录并阻止再次交付；新尝试使用不同标识，旧请求拒绝。

最终 Forge 工作 JAR 为 `build/libs/digital-storage-cloud-forge-<version>.jar`。NightConfig 单独 relocation；Tom 是外部依赖，不嵌入发行 JAR。`build` 同时检查共享源码/字节码平台边界、元数据版本、重复 ZIP entry 和禁止依赖载荷。

`libs/toms_storage-1.20-1.7.1.jar` 是 MIT 许可的精确 Tom 编译依赖（SHA-256 `08552be86f960111e501227707dc089c91b8a55f6c1be09baee188338576ff9b`），由 ForgeGradle 重映射用于开发，运行环境仍需单独安装 Tom；许可见上级 `licenses/Toms-Storage-LICENSE`。聚合 Mixin 只补充明确的 DSC 卷身份，过滤和未知包装器保留原接口。
