# Fabric 适配

本目录处理 Fabric 事件、网络消息、Tom 接入和 Transfer API 事务。存储卷、迁移策略和通用配置位于共享层，不引用这些平台接口。

目录大致分为：

- `tom/`：读取 Tom 拓扑，维护网络缓存、迁移生命周期和扫描统计。
- `mixin/`：Fabric 端对 Tom 的注入点。
- `hopper/`：漏斗转移快路径、过滤游标和相关调度。
- 其余适配器：物品键转换、屏幕网络、资源重载、服务端事件和数字库存包装。

## 数字库存接入

`FabricAccessorBlockEntity` 在共享访问器实体的基础上补上 Fabric 需要的库存和开屏接口。方块创建与已保存实体加载都必须落到这一平台子类，避免同一个访问器在新建和重载后暴露不同能力。

共享层通过 `InventoryEndpoint`、`TopologyToken` 和 `InventoryTransferExecutor` 请求平台能力；`FabricNetworkServices` 在加载器入口安装对应实现。批量大小、退避和扫描间隔等纯策略仍放在共享 `hopper/HopperPolicy`，不应复制到平台目录。

## Transfer API 不变量

Fabric 端通过 Transfer API 事务转移物品。来源提取后，只有目标完整接受同一数量才提交；部分插入、异常或外层事务回滚时，两侧都必须恢复。

适配器还必须保留 Tom 原本的过滤、保留最后一件和方向限制，不能为了快路径绕开这些语义。

数字存储包装器共享同一卷账本身份，因此多个访问器不会在 Tom 聚合时变成多份容量。与此同时，临时插入和事务回滚仍可能改变账本内部的条目集合；即使最终数量没有提交，也必须正确使旧的增量快照迭代状态失效。

Forge 的 `IItemHandler` 没有同样的事务模型，其转移结算和异常恢复在 `forge/` 中单独实现，不能照搬 Fabric 的提交假设。
