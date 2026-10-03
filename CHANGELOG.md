# Changelog

## 1.2.0 - Unreleased

- Fabric 库存复用存活条目的视图，减少重复弱引用查找和物品 NBT 拷贝；事务结束及共享账本结构变化后清理脱离条目。

- 共享业务独立为 common 构建子项目，Fabric 发行合入共享产物；构建检查共享源码/字节码的平台依赖及源码归属。

- 开发源码统一使用 Minecraft 1.20.1 官方 Mojang 映射，为共用 Fabric/Forge 源码建立映射基线；Fabric 发行 JAR 继续 remap 为 intermediary。

- 接入器绑定和菜单业务改用平台注入的内容句柄/创建工厂，Fabric 库存接口与扩展开屏数据由独立适配实体提供。

- 将网络评分、迁移候选与逐 tick 迁移调度改为共享物品键和库存端点契约；Fabric 适配层保留 Tom 拓扑失效、过滤规则及原子转移回滚。

- 将 Tom 拓扑、库存快路径与 Mixin 接入集中到 Fabric 平台边界，屏幕通过注入服务调用；批量、退避和扫描错峰计算保留为共享策略。

- 将数量账本、增量快照和持久化从 Fabric Transfer 接口分离，通过适配器参与原有外部事务并保留嵌套回滚。

- 存储物品身份与安全策略改用自有不可变物品键，保留旧 Variant 编码、完整 NBT 和 long 数量。

- 将屏幕消息与等级资源重载的 Fabric 接线移入平台适配层，保留现有消息格式及服务端权限检查。

- Separate shared mod identity, configuration paths and server lifecycle services
  from Fabric event registration, preparing the storage backend for a Forge port.
- Preserve existing storage formats, item limits and Fabric transfer semantics.

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
