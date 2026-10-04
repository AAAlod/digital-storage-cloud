# 私有重启夹具

独立 `restartFixture` 源集，通过 `reobfRestartFixtureJar` 显式构建，不随玩家 binary/sources 打包。入口为 `RestartAudit`，必须指定 `digitalstorage.restartTestRoot`，且实际世界路径须精确匹配工作区专用 `maintenance/work/fabric-decoupling/*/audit-world`。

两次真实专用服务器生命周期验证编码失败后的同进程 Session、转移实例及返回物品栈引用保留，修复后恰好交付一次，再运行正式自检、诊断及保存。夹具 Mixin 仅支持第二次注册跳过、第一次进程退出与全局执行器关闭延迟；实际 DSC 和世界停止流程保持执行。此夹具不验证跨进程恢复或客户端行为。

另有 `TickAudit` 自然周期夹具，使用 `digitalstorage.tickTestRoot` 启用，与重启模式互斥。它在声明的私有世界放置 20 个普通/高级漏斗、真实连接器和独立箱子，临时强制加载自建区块，观察300真实世界tick的扫描相位、批量间隔、旋转和禁用状态；不手动调用 Tom update。源网络使用过滤器；通过反射只观察自然产生的源句柄，区分端点发现和网络首次转移。结束后移除自建方块并解除自己添加的强制加载。它不验证客户端操作、网络全部组合或大规模性能。
