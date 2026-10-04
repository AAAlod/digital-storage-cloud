# Forge 平台

`forge/` 是 DSC 的 Minecraft 1.20.1 Forge 适配层，当前验证环境为 Forge 47.4.10、Java 17 和 Tom's Simple Storage 1.7.1。

共享的卷管理、配置、权限、存储账本和迁移策略仍来自主源码；这里负责 Forge 注册、菜单与网络消息、`IItemHandler` 接入、Tom 拓扑、Mixin、漏斗状态以及 Forge 特有的异常恢复流程。

## 构建

ForgeGradle 与根工程的 Loom 分开加载，避免两个插件体系共享 classloader。两端仍使用同一份版本属性和共享源码清单。

从仓库根目录运行：

```powershell
.\gradlew.bat -p forge build --console=plain
```

发行 JAR 位于：

```text
forge/build/libs/digital-storage-cloud-forge-<version>.jar
```

`build` 不只编译代码，还会检查共享代码的平台引用、模组元数据版本、重复 ZIP entry，以及 Tom / Fabric 等不应被打进发行包的依赖载荷。NightConfig 会重定位进 DSC 自己的命名空间，Tom 仍然是外部必需依赖。

开发环境的 `runServer` / `runClient` 会生成仅用于本地运行的兼容依赖副本，不修改仓库中的原始依赖，也不改变最终发行包。服务端可以用 `-PdscRunDir=<目录>` 指定独立运行目录；只做服务端测试时可加 `-x downloadAssets` 跳过客户端资源下载。

## 漏斗接入

高级存储漏斗沿用 Tom 的实体类型、过滤和红石逻辑，通过 access transformer 把 DSC 方块加入该实体类型允许的方块集合。基础存储漏斗和高级存储漏斗默认单批最多转移 16 / 64 件；关闭 DSC 优化后回到 Tom 原来的单件更新路径。

优化开启时还会处理成功冷却、失败退避、过滤变化唤醒和连接器扫描错峰。漏斗旋转后必须丢弃旧的源/目标能力缓存，在后续扫描中重新发现端点，不能因为旧 capability 仍然存活就继续朝旧方向工作。

## 为什么 Forge 有额外的恢复状态

Fabric 可以把外部库存转移纳入 Transfer API 事务；Forge 的 `IItemHandler` 没有同样的跨库存原子事务。物品已经从一侧取出、另一侧又在异常过程中只接受一部分时，直接重试可能造成物品复制。

因此 Forge 端会为批量转移保存稳定身份、托管状态和持久化收据。无法确认结算结果时，相关来源停止提取，保留观察到的返回栈和状态供管理员核对。未知版本、损坏数据和身份冲突不会被自动覆盖或合并。

无法确认结算结果时停止转移，保留未决记录，不自动重放。

## 玩家恢复与管理员核对

卷迁移产生的可恢复余量可以通过：

```text
/digitalstorage recovery list
/digitalstorage recovery deliver <id> [amount]
```

交付回原目标卷。容量或策略暂时拒绝时，余量会继续保留。

漏斗没有可靠的玩家所有者，因此异常漏斗记录由权限等级 2 的管理员处理。常用入口包括：

```text
/digitalstorage hopperrecovery list
/digitalstorage hopperrecovery inspect <id>
/digitalstorage hopperrecovery handoff <id> <volume> <explanation>
```

普通 `handoff` 的“已确认”仅指原调用返回的余量。操作前仍须核对外部库存的持久化结果，尤其是异常退出后；不同库存与世界文件之间没有共同的原子提交。交接目标一旦确定，重试不能换卷；永久收据用于避免重复交接。

如果已经通过外部存档或实际库存确认结果，还可以使用：

```text
/digitalstorage hopperrecovery reconcile-empty <id> <explanation>
/digitalstorage hopperrecovery reconcile-handoff <id> <volume> <confirmed> <external> <explanation>
```

`confirmed` 是确认仍由 DSC 持有、需要进入恢复流程的正数量，`external` 是确认已在外部库存结算的非负数量，两者之和必须等于实际返回栈的数量。全量已在外部结算时使用 `reconcile-empty`，不再交接物品。命令不会替管理员读取其他模组的持久化文件；没有实际返回栈或数量尚未核实的条目不能交接。

交付进入 `DELIVERING` 后发生中断或刷盘失败时，不得直接重试。先用：

```text
/digitalstorage recoveryadmin inspect <id>
/digitalstorage recoveryadmin reconcile <id> <attempt> delivered|not_delivered <explanation>
```

根据外部持久化结果明确本次尝试究竟有没有完成。核对会保存永久收据，旧 attempt 之后不能再次作为新尝试使用。

`transferincident` 记录的是异常观察和核对证据，本身不会交付物品，也不会绕过恢复状态机。

## 迁移与网络统计

迁移按 tick 预算分批执行，每批都会重新确认绑定、端点和拓扑是否仍然有效。取消、玩家退出、停服、访问器失效或网络拓扑改变时会停止后续提取；存在未决恢复状态时也不会启动新的迁移。

网络统计记录 DSC/Tom 扫描的实际尝试，不额外遍历库存。它可用于检查连续失败或尝试过于频繁的扫描器，不代表服务器整体性能。

## 自检、夹具与诊断

`/digitalstorage selftest` 会运行共享层以及 Forge/Tom 的内置回归。需要实际放置方块和推进世界 tick 的测试只有显式指定专用 `audit-world` 时才运行，不会自动碰玩家世界。

重启与自然 tick 测试夹具见 [restartFixture](src/restartFixture/README.md)。

管理员诊断还包括只读库存探针和隔离微基准：

```text
/digitalstorage probe <pos>
/digitalstorage benchmark
```

`probe` 最多检查 65536 个槽位，不提取物品。`benchmark` 使用私有内存库存和临时恢复目录，最长运行约 30 秒；它测的是指定场景下的相对实现成本，Fabric 与 Forge 的基准实现也不同，因此不要直接拿绝对数字互相比较或据此宣称 TPS 提升。

Forge 使用的 Tom 1.7.1 编译依赖位于 `forge/libs/`，运行游戏时仍需正常安装 Tom。第三方来源和许可记录在仓库根目录的 `THIRD_PARTY_NOTICES.md` 与 `licenses/` 中。
