# 更新日志

从 1.2.0 起使用中文分类记录；1.1.x 历史记录保留发布时的原文。

## 1.2.5 - 2026-11-08

### 推荐迁移与批量转移

- “按建议移入”改用现有批量转移任务，移除旧迁移引擎；建议预选、数量调整、分页、停止和关闭界面后继续等行为沿用批量转移。
- 推荐候选与实际批量路由取交集，只列出当前真正可转移的物品；大网络推荐预览不再被无关的 4096 个变体直接阻断。
- Forge 修复 Tom 周期重建导致批量移出中途停止的问题；拓扑真正变化时仍会停止后续搬运。
- Fabric 批量导出单个步骤最多执行 64 次插入或访问 64 个槽位，低效第三方 Storage 不会在单 tick 内无界循环；未搬完的数量跨 tick 继续。

### Fabric 访问器

- 访问器提供带绑定租约的容量视图；解绑、重绑、区块/方块实体重载或移除后，旧 Storage 和旧 StorageView 不再能读写旧卷。
- Tom 端点识别和网络去重继续按实际卷处理；同一卷的多个访问器仍只计一份存储。

### 持久化与恢复

- 停服改为有界等待最终快照并记录最后成功代际；超时不会无限挂起服务器，也不能保证最新代际一定落盘。日志和诊断会报告 dirty、最后成功代际/时间和失败原因。
- 非原子文件系统上的备份在成功安装后只保留最近两份；启动时从新到旧尝试可验证的备份，最新备份损坏时可恢复次新有效备份。
- 优先使用原子替换；不支持原子替换时保留旧文件的验证备份后安装，失败时恢复备份。非原子替换期间进程或机器突然退出仍可能留下不完整目标，启动依赖备份恢复，不能承诺零丢失。
- 升级前请备份完整世界，并保留 `digitalstorage.dat.bak-*` 与 `digitalstorage-legacy-*` 供诊断。

### Forge 恢复与批量路径

- live 与 legacy `forge-transfer` 恢复目录并存时只读检查 recovery、incidents、hoppers 和同进程未落盘记录；存在未决或不可读证据时阻止新的批量转移，保留全部证据等待人工核对。
- `clear()` 立即撤销批量快路径；等价重建后恢复，真实结构变化则更新版本，避免正常 Tom 重建误停任务，同时保留真实变化停止搬运的语义。
- 对普通箱、双箱和符合条件的精妙木桶执行真实拆除回归，覆盖拆除后与正常停服/重启后的物品守恒；更广泛的第三方容器移除仍不在本版完整保证内。

### 漏斗

- 失败退避默认值从 `[20, 40, 100, 200, 400]` 调整为 `[20, 40, 80, 120, 200]`，最长等待约 10 秒而不是 20 秒。
- 已有 TOML 中的自定义或旧退避列表不会被自动改写；需要新默认值时请手动调整 `hopper.failureCooldownTicks`。

### 已知限制

- 1.2.5 仍使用单一 SavedData 文件，尚未实现逐记录损坏隔离；单条结构损坏仍可能使全部卷只读。
- 第三方直接移除容器且 Tom 尚未重建的窗口、精妙容器的完整移除覆盖和扩大后的精妙存储范围仍留待 1.3。
- 有界停服可能丢失超时前最后一批未落盘状态，发布说明不将其宣传为零丢失保证。
- Fabric/Forge 世界互相转换仍未验证；Forge 恢复记录冲突需要管理员人工核对。
- 客户端与服务端需要使用相同的 DSC 版本。

## 1.2.4 - 2026-11-07

### 数据安全与持久化

- 存储数据改用单一 SavedData 文件和后台快照；首次升级时会尝试迁移旧 `digitalstorage/` 数据并保留旧目录备份。升级前请备份世界。
- 保留暂时无法识别的物品数据；遇到损坏或更高版本的数据时采取保护措施，避免用空数据覆盖。临时不可用的卷不再导致访问器清除绑定。
- 新增所有账号和卷合计的物品变体数据预算，默认上限为 64 MiB；已有超限数据可读取和提取，恢复到限额前拒绝增加数据。
- 删除卷时保护已挂载的访问器；配置允许时可创建超过 64 个卷，已有卷保留。

### 漏斗

- 修复高级漏斗直接输入绑定数字卷的访问器时无法搬运物品的问题。新放置的高级漏斗使用 DSC 自有方块实体，旧世界中的原漏斗继续兼容；普通 Tom 漏斗保持原有行为。

### Fabric 批量移出

- 修复向符合条件的精妙存储容器批量移出时，仍有空槽却提示空间不足、需要重复点击的问题；一次开始移出可填充可插入的空槽。Forge 未包含此项修复，其同类行为尚未验证。

## 1.2.3 - 2026-11-06

- 修复相邻精妙容器组成的存储组被错误排除，支持从 Tom 连接器访问整个相邻容器组。
- 批量转移支持符合条件的精妙箱子、木桶、有限木桶和潜影盒的各材质等级。
- 修复部分旧版精妙存储未提供远程关联接口时被误排除的问题，并记录端点跳过原因。
- 隔离名称无效的删除标记，避免其妨碍世界加载，减少删除记录的常驻内存；精妙端点扫描不再查询无关方块的物品能力。
- 批量转移新增对精妙存储基础容器的有限支持；连接方式和排除范围见玩家指南。
- 支持符合条件的精妙堆叠升级容器，并在混合网络中按端点筛选；卷删除先记录删除意图，避免旧文件在进程异常退出后恢复。
- 修复潜行右键访问器时仍可能打开界面的问题，为手持物品交互放行。
- 修复删除空卷与异步写盘交错时，已删除卷可能重新进入索引的问题。
- 修复访问器界面可能不能及时反映外部绑定、卷列表和升级材料变化的问题。
- 每位玩家最多创建 64 个存储卷；原先配置为 0（无限）会调整为 64。已有超额卷保留，可使用命令管理。

## 1.2.2 - 2026-10-05

- 转移页以卷图标、名称、方向箭头和网络实体容器显示两端对象；长卷名省略并可悬停查看。不可堆叠筛选与排序并排，明确区分显示筛选与存储接收设置。
- 网络详情入口位于转移对象右侧；详情显示网络评分与容器数据，移除重复的物品转移入口。
- 转移方向可通过箭头按钮切换，数量排序使用箭头与数字图标；短请求立即锁定输入，持续处理时才显示置灰和进度，减少按钮闪烁。
- 新增按完整物品身份和指定数量执行的批量转移任务；迁出仅面向当前网络的实体容器，排除数字存储卷。
- 迁出保持网络过滤规则，未接收的余量保留在卷中；Forge 不确定交付保留恢复记录并停止继续搬运。
- 新增双向物品预览、搜索、全选/清空及每项数量编辑；同名而耐久、附魔或其他数据不同的物品分别选择。
- 物品预览支持数量升序/降序，移入默认从多到少、移出默认从少到多；切换排序保留选量，先排序整个预览再分页。排序位于预览左下角，紧凑分页位于右下角。
- 修复 Fabric 双箱正常重扫后错误拒绝移入的问题；转移结束自动刷新预览，保留完成反馈并清空旧选择。物品数量显示在图标上层，页码居中，翻页按钮缩小。
- 修复 Fabric 线缆连接器周期重连使有效转移预览反复失效的问题；通过线缆发现的网络也跟随主连接器扫描检查实际结构变化。
- 不可堆叠物品设置旁按需提供独立清理入口，关闭接收后仍可移出已有工具装备，清理不会隐式改变接收开关。
- 已绑定时可从“…”进入“我的存储卷”，创建、重命名、更改图标和删除自己的其他空卷，保留当前绑定与删除保护。
- 任务按 tick 预算执行，显示实际完成量及剩余量；支持停止、关闭界面后继续查看，以及网络失效、玩家离线等停止反馈。
- 本版提供手动发起的一次批量转移；持续自动迁出、长期物品筛选和指定容器路由留待后续版本。GUI 继续迭代。

## 1.2.1 - 2026-10-04

### 访问器界面

- Fabric/Forge 共用精炼单页主视图，突出当前存储卷、种类容量与物品总数；升级和网络详情按需打开。
- 存储卷选择改为可滚动列表，名称、等级、容量与绑定操作分开显示；创建和重命名使用各自的上下文输入框。
- 删除空卷与清除访问器绑定增加对象及后果确认，保留服务端权限和删除限制。
- 不可堆叠物品设置明确显示开/关与不可用原因；网络摘要区分故障和可选迁移机会。
- 长文本提供省略提示，多项升级费用支持换行和滚动；适应窗口高度，避免非整数字体缩放。
- 请求处理期间显示反馈；状态同步保持当前详情和编辑目标。
- 屏幕同步增加操作响应序号，避免库存更新提前结束请求等待；多人游戏需两端安装相同版本。
- 升级结果、满级提示和缺少材料提示保留嵌套翻译，按玩家语言显示等级及物品名称。
- 升级详情显示前后等级与容量对比、材料图标和独立费用区；满级页面缩短，减少空白。
- 网络页使用紧凑状态、简短处理建议、两项容器数据与移入进度，常规窗口无需滚动；移除启发式评分和扫描计数，明确操作方向为移入存储卷。卷管理和确认页使用卷图标、容量条及简短后果提示。
- 界面改用原版容器式灰色像素边框，修复面板遮住名称输入框的问题，增加输入占位文字；移除大段与重复悬停说明，清除绑定从主界面直接进入确认。
- 去除内容区域的内凹边框；不可堆叠设置改为滑动开关，提交设置时保持升级和清除绑定按钮的外观稳定。
- 存储卷图标默认箱子，可从背包格子、快捷栏或携带物品中选择，右键恢复默认；右下角提供箱子、钻石剑、红石块、铁锭、小麦及圆石六个无需持有物品的预置图标。选择不消耗物品，只保存物品种类，随卷持久化并同步至卷列表与其他访问器。
- 修复切换不可堆叠设置时短暂弹出提示、重新分析时移入按钮改变外观的问题。
- 图标页移除常驻说明；卷列表操作菜单直接进入重命名、图标选择或删除确认，清除绑定收纳至主视图菜单。卷较多时提供名称查找。
- 操作反馈按当前页面与请求归属显示，切页不再继承网络分析报错；超时提供关闭并重新核对入口，仍不自动重发请求。
- 首页区分种类容量与物品总量，控制者仅在只读状态显示；网络容器计数按需展开。
- 子页标题移入顶部栏，操作菜单紧贴触发按钮，卷图标与名称垂直居中；网络页收紧宽度，将迁移建议与目标卷分区显示。

### Forge 容量修复

- 向 Tom 暴露当前卷等级的真实槽位数，修复未升级卷显示 1024 格但无法使用额外格数的问题。
- 容量变化后刷新 Tom 聚合库存的缓存偏移，保持相邻实体库存、过滤包装与嵌套聚合的读写位置正确。
- 等级或数据包容量降低时保留已有物品的读取和提取；清空超出容量的物品后收回多余槽位。

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
