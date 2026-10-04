# 重启与自然 tick 测试夹具

`restartFixture` 是 Forge 端的私有集成测试源集，不进入 DSC 的 binary 或 sources JAR。它测试停服、同进程重启、自然世界 tick、Tom 周期入口和异常转移后的状态保留。

通过 `reobfRestartFixtureJar` 显式构建夹具。RestartAudit 必须设置 `digitalstorage.restartTestRoot`，TickAudit 必须设置 `digitalstorage.tickTestRoot`。世界路径须与所设属性一致，位于工作区 `maintenance/work/fabric-decoupling/` 下，并以 `audit-world` 结尾。路径不符合条件时测试拒绝运行。

## RestartAudit

`RestartAudit` 在同一进程中启动两次专用服务器。第一阶段主动制造物品编码失败，确认 Session、转移实例和返回栈在停服后仍被保留；第二阶段修复编码条件，确认物品只交付一次，再执行自检、诊断和保存。

为了允许同一 JVM 再次启动服务器，夹具自己的 Mixin 会跳过第二次模组注册，并延迟第一次进程退出和全局执行器关闭。DSC 本身和 Minecraft 世界的正常停服流程仍然照常执行。

这项测试验证的是**同进程生命周期**，不覆盖跨进程恢复或客户端行为。

## TickAudit

`TickAudit` 通过 `digitalstorage.tickTestRoot` 显式启用，不能和重启模式同时运行。

它会在专用世界中放置共 20 个基础存储漏斗和高级存储漏斗，以及存储连接器和独立箱子，临时强制加载测试区块，然后观察 300 个世界 tick 内的扫描相位、批量间隔、旋转和禁用状态。Tom 由世界周期驱动，测试不手动调用 update；反射只用于观察自然产生的源句柄。

结束后，夹具会移除自己放置的方块并解除自己添加的强制加载。

它仍然不是完整验收：客户端交互、所有网络组合和大规模服务器性能需要另外测试。
