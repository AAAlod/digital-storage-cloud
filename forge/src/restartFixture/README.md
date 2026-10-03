# 私有重启夹具

独立 `restartFixture` 源集，通过 `reobfRestartFixtureJar` 显式构建，不随玩家 binary/sources 打包。入口为 `RestartAudit`，必须指定 `digitalstorage.restartTestRoot`，且实际世界路径须精确匹配工作区专用 `maintenance/work/fabric-decoupling/*/audit-world`。

两次真实专用服务器生命周期验证编码失败后的同进程 Session、转移实例及返回物品栈引用保留，修复后恰好交付一次，再运行正式自检、诊断及保存。夹具 Mixin 仅支持第二次注册跳过、第一次进程退出与全局执行器关闭延迟；实际 DSC 和世界停止流程保持执行。此夹具不验证跨进程恢复或客户端行为。
