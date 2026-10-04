# 更新日志

从 1.2.0 起使用中文分类记录；1.1.x 历史记录保留发布时的原文。

## 1.2.0 - 2026-10-04

### Forge 支持

- 新增 Minecraft 1.20.1 Forge 构建，共用卷管理、配置、生命周期、权限、存储和迁移业务。
- 接入数字存储访问器、菜单与网络消息、绑定/解绑/检查、升级、网络分析及玩家迁移；沿用服务端所有权校验和 /dsc 命令别名。
- 注册高级存储漏斗及其合成、掉落和挖掘标签。普通/高级漏斗接入批量、成功冷却、失败退避、过滤唤醒及连接器扫描错峰；关闭优化保留 Tom 原路径。
- 漏斗旋转后清除源/目标能力缓存并重新发现端点，避免继续使用旧方向。
- 网络分析关联实际 Tom 扫描器，显示活跃数量、连续失败和平均尝试间隔，不额外枚举库存。
- 增加管理员库存探针、隔离性能基准、恢复查看、交接和核对命令。

### 转移与恢复保护

- 共享迁移按 tick 预算继续处理需分批提取的同一槽位，保留已结算数量和停止原因；取消、退出、停服或端点移除停止后续提取。
- Forge 未决恢复记录阻止新迁移。拒收保留余量，交付中断或刷盘失败不自动重放；明确交付结果、尝试身份和持久化收据阻止重复交付。
- Forge 漏斗保存稳定托管身份及独立状态，区块替换/移除前保留原实例；错误类型和未知版本数据原样保存，冲突镜像独立隔离。
- 托管目录不可用时保留内存原实例；恢复后先核对磁盘证据，同身份冲突不覆盖原记录。无法编码的返回栈须修复序列化后才能持久化。
- 管理员核对区分实际观察余量与已在外部结算的数量；未知数量不凭输入数量推断。永久交接/退役收据阻止过期镜像再次恢复物品。
- 异常记录区分来源数量未知与实际已读取数量，保留来源及调用观察。

### 共享存储与兼容性

- 将数量账本、增量快照、持久化和安全策略从 Fabric Transfer 接口分离；保留原外部事务与嵌套回滚。
- 使用不可变物品键，保留旧 Variant 编码、完整 NBT 和 long 数量；可保存参与身份和 NBT 限额计算的平台附加栈数据。
- 不可堆叠策略通过平台适配查询最大堆叠数量，Forge 支持依 NBT/能力变化的上限。
- 存活账本保留所属卷，避免弱缓存回收后重新加载另一份账本；无外部引用的卷仍可回收。
- 将等级重载、生命周期、屏幕消息、内容句柄与菜单创建工厂移至平台边界，保留消息格式和权限检查。
- 将 Tom 拓扑、快路径和 Mixin 接入集中到平台适配层；共享迁移保留拓扑失效、过滤、预算和回滚约束。

### Fabric 适配优化

- 复用存活条目视图及结构稳定时的完整遍历成员，数量实时读取；保留暂时零量条目在回滚后重新出现的行为，结构变化后重建成员。
- 复用仍附着条目的事务桥和空闲变更范围，嵌套及重入事务仍独立；清理脱离条目，避免缓存长期保留。
- 视图提取及共享迁移在精确物品/NBT复核后复用已有键，减少平台往返转换；拒收、部分插入与异常仍回滚同一事务。

### 构建

- 共享业务独立为 common 子项目，两端构建检查共享依赖边界及源码归属。
- 开发源码统一为官方 Mojang 映射，Fabric 发行 JAR 继续重映射为 intermediary。
- binary 和 sources 文件名明确带 fabric 或 forge，发行包不嵌入 Tom、Cloth Config 等依赖载荷。

### 已知限制

- 部分迁移和重负载漏斗场景仍有额外 CPU 开销，后续补丁继续优化；不承诺整体 CPU/TPS 改善。
- 跨 Fabric/Forge 的世界转换尚未验证。

## 1.1.14 - 2026-09-11

- Keep bulk migrations running across Tom's periodic rebuilds when Fabric sided
  inventory wrappers or transparent double-chest containers are replaced without
  changing their underlying slots or access direction. Unknown storage wrappers
  still invalidate conservatively; real endpoint changes still stop migration.
- Retain migration source handles for the job lifetime and preserve the per-tick
  scan budget, including empty slots, and transactional rollback protection.

## 1.1.13 - 2026-09-07

- Fix incremental persistence snapshots crashing after a provisional item variant
  is inserted and rolled back, including simulated insertion and failed hopper
  transfers. Track map structure separately from committed content versions.
- Discard partial snapshots after an unexpected iterator failure and rebuild
  pending snapshots for final flush/shutdown without changing the storage format.
- Add regression coverage for transaction invalidation, snapshot budgets, nested
  hopper rollback, defensive recovery, and final-flush file round trips.

## 1.1.12 - 2026-08-29

- Replace the legacy JSON configuration with a documented, sectioned TOML file.
- Automatically migrate existing `digitalstorage.json` values and archive the
  original file after the new TOML configuration is safely written.
- Preserve the last valid runtime configuration when `/digitalstorage
  reloadconfig` encounters malformed TOML, and avoid rewriting unchanged files.
- Tune new-install defaults toward stable server TPS with stronger hopper
  backoff and lower per-tick migration and persistence budgets.

## 1.1.11 - 2026-08-29

- Explain why non-empty storage volumes cannot be deleted with localized button
  tooltips while retaining authoritative server-side deletion checks.
- Keep the configured inventory key from closing the accessor screen while the
  storage-volume name field is focused, without changing Escape behavior.

## 1.1.10 - 2026-08-27

- Add an owner-controlled per-Volume policy for accepting unstackable items,
  with a server-wide capability gate, legacy configuration migration, persistent
  state, immediate enforcement and consistent Tom analysis/migration behavior.
- Add a dedicated Digital Storage Cloud creative inventory tab instead of placing
  the Mod's items in the vanilla Redstone Blocks tab.
- Rename the Simplified Chinese display name of the Advanced Inventory Hopper to
  `高级存储漏斗`.

## 1.1.9 - 2026-08-26

- Fix Accessor GUI text clipping in English, Simplified Chinese, and long-value
  edge cases with right-aligned fitted values and flow-based wrapped messages.
- Move the recommended-migration explanation into the Migrate button tooltip,
  and shorten labels that must remain fully visible in the fixed-size layout.

## 1.1.8 - 2026-08-26

- Keep Tom's Simple Storage as a required dependency while removing the exact
  1.7.1 metadata pin for experimental compatibility testing with other versions.
- Replace the Digital Storage Accessor recipe's four gold ingots with four Tom
  Inventory Trims, and its three redstone dust with two diamonds and one comparator.
- Add the Simplified Chinese Mod Menu display name `汤姆的简易存储：云上加仓`
  while retaining `Digital Storage Cloud` as the English and metadata fallback.

## 1.1.7 - 2026-08-25

- Make `/digitalstorage stats` avoid cold-loading every Volume; it now reports
  how many content summaries are known and reserves exact full inspection for
  the explicit `/digitalstorage stats deep` command.
- Document and regression-test the intentional `2,147,483,647` item safety
  ceiling per exact variant while preserving extraction of stored over-limit data.
- Include the required marker-compatible Tom's Storage copy in Loom development
  runs without changing or bundling the distributable dependency.
- Redesign the accessor screen around storage, network, upgrade and optimization
  summaries; hide healthy low-level telemetry, clarify Volume names and show
  actionable warnings or migration progress only when relevant.
- Analyze a bound Tom network once when the accessor screen opens and deliver the
  result in the first server state update, while retaining manual refresh.

## 1.1.6 - 2026-08-23

- Fix production startup by remapping the connector-staggering redirect's
  Minecraft `World.getTime()` target while keeping Tom's method name unmapped.
- Make the optional connector-staggering optimization fail soft if a future
  Tom's Storage release changes the targeted invocation.

## 1.1.5 - 2026-08-23

- Optimize the public project icon from 1254x1254 to 256x256 while preserving
  the same artwork, reducing the image size by more than 90%.

## 1.1.4 - 2026-08-23

- Add the public project logo as the Fabric metadata icon and README identity.
- Link the project homepage, source repository and issue tracker from the Mod
  metadata.
- Add a player-facing README introduction with features, dependencies and
  installation guidance before the technical architecture documentation.

## 1.1.3 - 2026-08-23

- Move the exact Tom's Simple Storage 1.7.1 compile-time dependency into
  `libs/` without bundling it into the distributable JAR.
- Keep the dependency license, upstream source and SHA-256 explicitly recorded
  in the third-party notices.

## 1.1.2 - 2026-08-23

- Standardize the product identity as Digital Storage Cloud across the Gradle
  project, distributable JAR and embedded license filename.
- Make `/digitalstorage` the primary command and `/dsc` its short alias; replace
  the obsolete compatibility report with required-integration diagnostics.
- Apply one Volume deletion policy in the GUI and commands: the owned Volume
  must be empty and have no loaded accessor mounts.
- Require Tom's Simple Storage 1.7.1 and all core mixins, including hopper
  optimization, connector staggering, topology tracking and Volume deduplication.
- Add the Advanced Inventory Hopper with a default 64-item batch alongside the
  standard Tom hopper's 16-item batch.
- Deduplicate Tom network endpoints by Volume UUID so multiple accessors expose
  one canonical digital storage endpoint.
- Standardize all default tier upgrades on vanilla diamonds and keep
  datapack-defined tiers as the runtime source of truth.
- Make mined accessors always drop without controller or Volume binding data.
- Remove unused configuration fields and the unreleased `VariantCapacity`
  migration while retaining schema guards, quarantine and missing-tier safety.
- Rename internal accessor classes and advanced-hopper texture resources to
  match their final roles.
- Rework network performance and capacity wording for players in English and
  Simplified Chinese.
- Correct documentation for the Accessor recipe: 4 gold ingots, 3 redstone,
  1 beacon and 1 Tom inventory cable connector produce 4 accessors.
